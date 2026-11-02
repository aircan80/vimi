package com.vibeminer.accounting;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Rebuildable local reporting projection. References never authorize deletion. */
public final class UsageIndex implements StorageAccounting {
    public static final int PAGE_SIZE = 256;

    public enum Role {
        BLIZZARD_ENCODED,
        DERIVED,
        PROVIDER,
        DATABASE
    }

    public record Resource(String storage, String key, Role role, long publishedBytes) {
        public Resource {
            text(storage);
            text(key);
            Objects.requireNonNull(role);
            if (publishedBytes < -1) {
                throw new IllegalArgumentException("Invalid declared length");
            }
        }
    }

    /** A producer's immutable inventory; empty page terminates it. Each cursor is single-use. */
    public interface Cursor extends AutoCloseable {
        List<Resource> next(Cancellation token) throws Exception;

        @Override
        default void close() {}
    }

    @FunctionalInterface
    public interface Inventory {
        Cursor open() throws Exception;
    }

    @FunctionalInterface
    public interface Presence {
        /** One payload length per resource, -1 for absent; no payload reads or downloads. */
        List<Long> lengths(List<Resource> resources, Cancellation token) throws Exception;
    }

    public record Split(long onlyThisBuild, long shared, long global, long databaseBytes) {}

    public record Figures(
            Split original,
            Split derived,
            long publishedBytes,
            long storedObjects,
            long publishedObjects,
            long sizeMismatches,
            long retainedBuilds,
            long incompleteBuilds,
            boolean derivedComplete,
            Instant measuredAt) {}

    public record Coverage(long files, long named) {}

    private final ThreadPoolExecutor actor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    task -> {
                        Thread thread = new Thread(task, "vimi-accounting");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private DerivedSqliteConnection db;
    private boolean closing;
    private CompletableFuture<Void> closed;

    private UsageIndex() {}

    public static CompletableFuture<UsageIndex> open(Path path) {
        var index = new UsageIndex();
        var initialized =
                index.submit(
                        () -> {
                            try {
                                Files.createDirectories(path.toAbsolutePath().getParent());
                                index.db = new DerivedSqliteConnection(path);
                                index.run(
                                        Cancellation.NONE,
                                        false,
                                        session -> {
                                            session.execute("PRAGMA cache_size=-2048");
                                            session.execute("PRAGMA temp_store=FILE");
                                            session.execute("PRAGMA temp.cache_size=-2048");
                                            return null;
                                        });
                                index.run(
                                        Cancellation.NONE,
                                        true,
                                        session -> {
                                            session.execute(
                                                    "CREATE TABLE IF NOT EXISTS owners(id TEXT PRIMARY KEY, global_owner INTEGER NOT NULL)");
                                            session.execute(
                                                    "CREATE TABLE IF NOT EXISTS expected(owner TEXT NOT NULL,source TEXT NOT NULL,role TEXT NOT NULL,PRIMARY KEY(owner,source)) WITHOUT ROWID");
                                            session.execute(
                                                    "CREATE TABLE IF NOT EXISTS receipts(owner TEXT NOT NULL,source TEXT NOT NULL,version TEXT NOT NULL,measured INTEGER NOT NULL,PRIMARY KEY(owner,source)) WITHOUT ROWID");
                                            session.execute(
                                                    "CREATE TABLE IF NOT EXISTS objects(storage TEXT NOT NULL,k TEXT NOT NULL,role TEXT NOT NULL,length INTEGER NOT NULL,PRIMARY KEY(storage,k)) WITHOUT ROWID");
                                            session.execute(
                                                    "CREATE TABLE IF NOT EXISTS refs(owner TEXT NOT NULL,source TEXT NOT NULL,storage TEXT NOT NULL,k TEXT NOT NULL,declared INTEGER NOT NULL,PRIMARY KEY(owner,source,storage,k)) WITHOUT ROWID");
                                            session.execute(
                                                    "CREATE INDEX IF NOT EXISTS refs_object ON refs(storage,k,owner)");
                                            session.execute(
                                                    "CREATE TABLE IF NOT EXISTS coverage(owner TEXT NOT NULL,scope TEXT NOT NULL,revision TEXT NOT NULL,files INTEGER NOT NULL,named INTEGER NOT NULL,PRIMARY KEY(owner,scope)) WITHOUT ROWID");
                                            return null;
                                        });
                                return index;
                            } catch (Exception | Error error) {
                                if (index.db != null) {
                                    try {
                                        index.db.close();
                                    } catch (RuntimeException cleanup) {
                                        error.addSuppressed(cleanup);
                                    }
                                }
                                index.actor.shutdown();
                                throw error;
                            }
                        });
        var result = new CompletableFuture<UsageIndex>();
        initialized.whenComplete(
                (value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        if (!result.complete(value)) {
                            value.closeAsync();
                        }
                    }
                });
        return result;
    }

    /** Retained membership persists across GUI close and process restart. */
    @Override
    public CompletableFuture<Void> retain(String owner, boolean global, String source, Role role) {
        text(owner);
        text(source);
        Objects.requireNonNull(role);
        return submit(
                () ->
                        run(
                                Cancellation.NONE,
                                true,
                                session -> {
                                    var prior =
                                            session.query(
                                                    "SELECT global_owner FROM owners WHERE id=?",
                                                    owner);
                                    if (!prior.isEmpty()
                                            && (long) prior.getFirst()[0] != (global ? 1 : 0)) {
                                        throw new IllegalArgumentException("Owner kind changed");
                                    }
                                    session.execute(
                                            "INSERT OR IGNORE INTO owners VALUES(?,?)",
                                            owner,
                                            global ? 1 : 0);
                                    session.execute(
                                            "INSERT OR IGNORE INTO expected VALUES(?,?,?)",
                                            owner,
                                            source,
                                            role.name());
                                    return null;
                                }));
    }

    @Override
    public CompletableFuture<Boolean> current(String owner, String source, String version) {
        return submit(
                () ->
                        run(
                                Cancellation.NONE,
                                false,
                                s ->
                                        !s.query(
                                                        "SELECT 1 FROM receipts WHERE owner=? AND source=? AND version=?",
                                                        owner,
                                                        source,
                                                        version)
                                                .isEmpty()));
    }

    /**
     * Atomically replace one producer snapshot. Failure/cancellation retains its prior generation.
     */
    @Override
    public CompletableFuture<Void> replace(
            String owner,
            String source,
            String version,
            Inventory inventory,
            Presence presence,
            Cancellation token) {
        text(owner);
        text(source);
        text(version);
        Objects.requireNonNull(inventory);
        Objects.requireNonNull(presence);
        return submit(
                () -> {
                    token.check();
                    try (Cursor cursor = inventory.open()) {
                        return run(
                                token,
                                true,
                                session -> {
                                    var expected =
                                            session.query(
                                                    "SELECT role FROM expected WHERE owner=? AND source=?",
                                                    owner,
                                                    source);
                                    if (expected.isEmpty()) {
                                        throw new IllegalArgumentException(
                                                "Retain producer before publication");
                                    }
                                    session.execute(
                                            "DELETE FROM refs WHERE owner=? AND source=?",
                                            owner,
                                            source);
                                    try {
                                        while (true) {
                                            token.check();
                                            var page = List.copyOf(cursor.next(token));
                                            if (page.isEmpty()) {
                                                break;
                                            }
                                            if (page.size() > PAGE_SIZE) {
                                                throw new IllegalArgumentException(
                                                        "Inventory page exceeds bound");
                                            }
                                            var lengths = presence.lengths(page, token);
                                            if (lengths.size() != page.size()) {
                                                throw new IllegalArgumentException(
                                                        "Presence length mismatch");
                                            }
                                            for (int i = 0; i < page.size(); i++) {
                                                token.check();
                                                var row = page.get(i);
                                                long length = lengths.get(i);
                                                if (length < -1
                                                        || row.role() == Role.BLIZZARD_ENCODED
                                                                && row.publishedBytes() < 0
                                                        || !row.role()
                                                                .name()
                                                                .equals(expected.getFirst()[0])) {
                                                    throw new IllegalArgumentException(
                                                            "Invalid resource measurement");
                                                }
                                                var existing =
                                                        session.query(
                                                                "SELECT role FROM objects WHERE storage=? AND k=?",
                                                                row.storage(),
                                                                row.key());
                                                if (!existing.isEmpty()
                                                        && !existing.getFirst()[0].equals(
                                                                row.role().name())) {
                                                    throw new IllegalArgumentException(
                                                            "Resource provenance changed");
                                                }
                                                session.execute(
                                                        "INSERT INTO objects VALUES(?,?,?,?) ON CONFLICT(storage,k) DO UPDATE SET length=excluded.length",
                                                        row.storage(),
                                                        row.key(),
                                                        row.role().name(),
                                                        length);
                                                session.execute(
                                                        "INSERT INTO refs VALUES(?,?,?,?,?) ON CONFLICT(owner,source,storage,k) DO UPDATE SET declared=excluded.declared",
                                                        owner,
                                                        source,
                                                        row.storage(),
                                                        row.key(),
                                                        row.publishedBytes());
                                            }
                                        }
                                    } catch (Exception error) {
                                        throw new InventoryFailure(error);
                                    }
                                    token.check();
                                    session.execute(
                                            "DELETE FROM objects WHERE NOT EXISTS(SELECT 1 FROM refs r WHERE r.storage=objects.storage AND r.k=objects.k)");
                                    session.execute(
                                            "INSERT INTO receipts VALUES(?,?,?,?) ON CONFLICT(owner,source) DO UPDATE SET version=excluded.version,measured=excluded.measured",
                                            owner,
                                            source,
                                            version,
                                            System.currentTimeMillis());
                                    return null;
                                });
                    }
                });
    }

    /** Reconcile local presence after restarts/missed notifications; references are unchanged. */
    @Override
    public CompletableFuture<Void> refresh(String storage, Presence presence, Cancellation token) {
        text(storage);
        return submit(
                () ->
                        run(
                                token,
                                true,
                                session -> {
                                    String after = "";
                                    while (true) {
                                        token.check();
                                        var rows =
                                                session.query(
                                                        "SELECT k,role FROM objects WHERE storage=? AND k>? ORDER BY k LIMIT 256",
                                                        storage,
                                                        after);
                                        if (rows.isEmpty()) {
                                            break;
                                        }
                                        var page = new ArrayList<Resource>();
                                        for (var row : rows) {
                                            page.add(
                                                    new Resource(
                                                            storage,
                                                            (String) row[0],
                                                            Role.valueOf((String) row[1]),
                                                            -1));
                                        }
                                        try {
                                            var lengths =
                                                    presence.lengths(List.copyOf(page), token);
                                            if (lengths.size() != page.size()) {
                                                throw new IllegalArgumentException(
                                                        "Presence length mismatch");
                                            }
                                            for (int i = 0; i < page.size(); i++) {
                                                long length = lengths.get(i);
                                                if (length < -1) {
                                                    throw new IllegalArgumentException(
                                                            "Invalid length");
                                                }
                                                session.execute(
                                                        "UPDATE objects SET length=? WHERE storage=? AND k=?",
                                                        length,
                                                        storage,
                                                        page.get(i).key());
                                            }
                                        } catch (Exception error) {
                                            throw new InventoryFailure(error);
                                        }
                                        after = page.getLast().key();
                                    }
                                    session.execute(
                                            "UPDATE receipts SET measured=? WHERE EXISTS(SELECT 1 FROM refs r WHERE r.owner=receipts.owner AND r.source=receipts.source AND r.storage=?)",
                                            System.currentTimeMillis(),
                                            storage);
                                    return null;
                                }));
    }

    /** Bounded notification reconciliation; unknown keys await a producer inventory. */
    @Override
    public CompletableFuture<Void> update(
            String storage, List<String> keys, Presence presence, Cancellation token) {
        if (keys.size() > PAGE_SIZE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Too many changes"));
        }
        var owned = List.copyOf(keys);
        return submit(
                () ->
                        run(
                                token,
                                true,
                                s -> {
                                    var page = new ArrayList<Resource>();
                                    for (String key : owned) {
                                        var rows =
                                                s.query(
                                                        "SELECT role FROM objects WHERE storage=? AND k=?",
                                                        storage,
                                                        key);
                                        if (!rows.isEmpty()) {
                                            page.add(
                                                    new Resource(
                                                            storage,
                                                            key,
                                                            Role.valueOf(
                                                                    (String) rows.getFirst()[0]),
                                                            -1));
                                        }
                                    }
                                    try {
                                        var lengths = presence.lengths(List.copyOf(page), token);
                                        if (lengths.size() != page.size()) {
                                            throw new IllegalArgumentException(
                                                    "Presence length mismatch");
                                        }
                                        for (int i = 0; i < page.size(); i++) {
                                            long length = lengths.get(i);
                                            if (length < -1) {
                                                throw new IllegalArgumentException(
                                                        "Invalid length");
                                            }
                                            s.execute(
                                                    "UPDATE objects SET length=? WHERE storage=? AND k=?",
                                                    length,
                                                    storage,
                                                    page.get(i).key());
                                        }
                                    } catch (Exception error) {
                                        throw new InventoryFailure(error);
                                    }
                                    return null;
                                }));
    }

    @Override
    public CompletableFuture<Figures> figures(String owner) {
        text(owner);
        return submit(
                () ->
                        run(
                                Cancellation.NONE,
                                true,
                                session -> {
                                    long retained =
                                            scalar(
                                                    session,
                                                    "SELECT count(*) FROM owners WHERE global_owner=0");
                                    long incomplete =
                                            scalar(
                                                    session,
                                                    "SELECT count(*) FROM owners o WHERE global_owner=0 AND (NOT EXISTS(SELECT 1 FROM expected e WHERE e.owner=o.id AND e.role='BLIZZARD_ENCODED') OR EXISTS(SELECT 1 FROM expected e LEFT JOIN receipts p ON e.owner=p.owner AND e.source=p.source WHERE e.owner=o.id AND e.role='BLIZZARD_ENCODED' AND p.owner IS NULL))");
                                    long derivedMissing =
                                            scalar(
                                                    session,
                                                    "SELECT count(*) FROM owners o WHERE global_owner=0 AND (NOT EXISTS(SELECT 1 FROM expected e WHERE e.owner=o.id AND e.role='DERIVED') OR EXISTS(SELECT 1 FROM expected e LEFT JOIN receipts p ON e.owner=p.owner AND e.source=p.source WHERE e.owner=o.id AND e.role IN ('DERIVED','DATABASE') AND p.owner IS NULL))");
                                    var totals =
                                            session.query(
                                                            "SELECT coalesce(sum(declared),0),count(*),coalesce(sum(length>=0),0),coalesce(sum(length>=0 AND declared>=0 AND length<>declared),0) FROM (SELECT r.storage,r.k,max(r.declared) declared,o.length FROM refs r JOIN objects o USING(storage,k) WHERE r.owner=? AND o.role='BLIZZARD_ENCODED' GROUP BY r.storage,r.k)",
                                                            owner)
                                                    .getFirst();
                                    long measured =
                                            scalar(
                                                    session,
                                                    "SELECT coalesce(min(measured),0) FROM receipts WHERE owner=?",
                                                    owner);
                                    return new Figures(
                                            split(session, owner, "BLIZZARD_ENCODED"),
                                            split(session, owner, "DERIVED"),
                                            (long) totals[0],
                                            (long) totals[2],
                                            (long) totals[1],
                                            (long) totals[3],
                                            retained,
                                            incomplete,
                                            derivedMissing == 0,
                                            Instant.ofEpochMilli(measured));
                                }));
    }

    private static Split split(DerivedSqliteConnection.Session s, String owner, String role) {
        var row =
                s.query(
                                "SELECT coalesce(sum(CASE WHEN consumers=1 AND globals=0 THEN length ELSE 0 END),0),coalesce(sum(CASE WHEN consumers>1 AND globals=0 THEN length ELSE 0 END),0),coalesce(sum(CASE WHEN globals>0 THEN length ELSE 0 END),0) FROM (SELECT o.storage,o.k,o.length,(SELECT count(DISTINCT r2.owner) FROM refs r2 JOIN owners own ON own.id=r2.owner WHERE r2.storage=o.storage AND r2.k=o.k AND own.global_owner=0) consumers,(SELECT count(*) FROM refs r3 JOIN owners own ON own.id=r3.owner WHERE r3.storage=o.storage AND r3.k=o.k AND own.global_owner=1) globals FROM objects o WHERE o.role=? AND o.length>=0 AND EXISTS(SELECT 1 FROM refs r WHERE r.owner=? AND r.storage=o.storage AND r.k=o.k))",
                                role,
                                owner)
                        .getFirst();
        long databases =
                role.equals("DERIVED")
                        ? scalar(
                                s,
                                "SELECT coalesce(sum(length),0) FROM objects o WHERE role='DATABASE' AND length>=0 AND EXISTS(SELECT 1 FROM refs r WHERE r.owner=? AND r.storage=o.storage AND r.k=o.k)",
                                owner)
                        : 0;
        return new Split((long) row[0], (long) row[1], (long) row[2], databases);
    }

    @Override
    public CompletableFuture<Coverage> coverage(String owner, String scope, String revision) {
        text(owner);
        text(scope);
        text(revision);
        return submit(
                () ->
                        run(
                                Cancellation.NONE,
                                false,
                                s -> {
                                    var rows =
                                            s.query(
                                                    "SELECT files,named FROM coverage WHERE owner=? AND scope=? AND revision=?",
                                                    owner,
                                                    scope,
                                                    revision);
                                    return rows.isEmpty()
                                            ? null
                                            : new Coverage(
                                                    (long) rows.getFirst()[0],
                                                    (long) rows.getFirst()[1]);
                                }));
    }

    @Override
    public CompletableFuture<Void> saveCoverage(
            String owner,
            String scope,
            String revision,
            long files,
            long named,
            Cancellation token) {
        text(owner);
        text(scope);
        text(revision);
        if (files < 0 || named < 0 || named > files) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid coverage"));
        }
        return submit(
                () ->
                        run(
                                token,
                                true,
                                s -> {
                                    s.execute(
                                            "INSERT INTO coverage VALUES(?,?,?,?,?) ON CONFLICT(owner,scope) DO UPDATE SET revision=excluded.revision,files=excluded.files,named=excluded.named",
                                            owner,
                                            scope,
                                            revision,
                                            files,
                                            named);
                                    return null;
                                }));
    }

    /** Explicit library removal, not closing an active GUI handle. Does not delete data. */
    @Override
    public CompletableFuture<Void> forget(String owner) {
        text(owner);
        return submit(
                () ->
                        run(
                                Cancellation.NONE,
                                true,
                                s -> {
                                    for (String table :
                                            List.of("refs", "receipts", "expected", "coverage")) {
                                        s.execute("DELETE FROM " + table + " WHERE owner=?", owner);
                                    }
                                    s.execute("DELETE FROM owners WHERE id=?", owner);
                                    return null;
                                }));
    }

    private static long scalar(DerivedSqliteConnection.Session s, String sql, Object... args) {
        return (long) s.query(sql, args).getFirst()[0];
    }

    private <T> T run(
            Cancellation token,
            boolean transaction,
            java.util.function.Function<DerivedSqliteConnection.Session, T> work)
            throws Exception {
        token.check();
        return db.run(token, transaction, work);
    }

    private static void text(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException("Invalid accounting identity");
        }
    }

    @FunctionalInterface
    private interface Task<T> {
        T run() throws Exception;
    }

    private synchronized <T> CompletableFuture<T> submit(Task<T> task) {
        if (closing) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Accounting is closing"));
        }
        var result = new CompletableFuture<T>();
        try {
            actor.execute(
                    () -> {
                        T value = null;
                        Throwable failure = null;
                        try {
                            value = task.run();
                        } catch (Throwable error) {
                            failure = error instanceof InventoryFailure ? error.getCause() : error;
                        }
                        T complete = value;
                        Throwable problem = failure;
                        Thread.startVirtualThread(
                                () -> {
                                    if (problem == null) {
                                        result.complete(complete);
                                    } else {
                                        result.completeExceptionally(problem);
                                    }
                                });
                    });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closed != null) {
            return closed.copy();
        }
        closed = new CompletableFuture<>();
        closing = true;
        actor.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        // Close after accepted actor work has drained; no native call remains
                        // active.
                        while (!actor.awaitTermination(1, TimeUnit.DAYS)) {}
                        if (db != null) {
                            db.close();
                        }
                        closed.complete(null);
                    } catch (Throwable error) {
                        closed.completeExceptionally(error);
                    }
                });
        return closed.copy();
    }

    private static final class InventoryFailure extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InventoryFailure(Throwable cause) {
            super(cause);
        }
    }
}
