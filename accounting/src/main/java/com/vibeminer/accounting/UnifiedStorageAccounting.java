package com.vibeminer.accounting;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reporting over the authoritative shared catalog. Never copies physical presence into a mirror.
 */
public final class UnifiedStorageAccounting implements StorageAccounting {
    private final LibraryCatalog catalog;
    private final ObjectStore objects;
    private final ThreadPoolExecutor metadata =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    task -> {
                        Thread thread = new Thread(task, "unified-accounting-metadata");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private final Object gate = new Object();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();

    private record Source(String owner, String source) {}

    private final Set<Source> relationalSources =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private DerivedSqliteConnection database;
    private boolean closing;
    private CompletableFuture<Void> closed;

    private UnifiedStorageAccounting(LibraryCatalog catalog, ObjectStore objects) {
        this.catalog = Objects.requireNonNull(catalog);
        this.objects = Objects.requireNonNull(objects);
    }

    /** Borrows both services; closing this reporting facade never closes the shared library. */
    public static CompletableFuture<UnifiedStorageAccounting> open(
            Path path, LibraryCatalog catalog, ObjectStore objects) {
        var service = new UnifiedStorageAccounting(catalog, objects);
        CompletableFuture<UnifiedStorageAccounting> opening =
                service.submit(
                        () -> {
                            try (var header = DerivedSqliteConnection.openReadOnly(path)) {
                                header.run(
                                        Cancellation.NONE,
                                        false,
                                        session -> {
                                            if (scalar(session, "PRAGMA application_id") != 2
                                                    || scalar(
                                                                    session,
                                                                    "SELECT version FROM"
                                                                            + " catalog_metadata")
                                                            != 1) {
                                                throw new IllegalArgumentException(
                                                        "Initialize the unified pack catalog before"
                                                                + " accounting");
                                            }
                                            boolean own =
                                                    scalar(
                                                                    session,
                                                                    "SELECT count(*) FROM"
                                                                            + " sqlite_master WHERE"
                                                                            + " type='table' AND"
                                                                            + " name='catalog_reporting_metadata'")
                                                            != 0;
                                            if (own) {
                                                if (scalar(
                                                                        session,
                                                                        "SELECT count(*) FROM"
                                                                                + " catalog_reporting_metadata")
                                                                != 1
                                                        || scalar(
                                                                        session,
                                                                        "SELECT version FROM"
                                                                                + " catalog_reporting_metadata")
                                                                != 1) {
                                                    throw new IllegalArgumentException(
                                                            "Unsupported unified accounting"
                                                                    + " schema");
                                                }
                                            } else if (scalar(
                                                            session,
                                                            "SELECT count(*) FROM sqlite_master"
                                                                    + " WHERE type='table' AND"
                                                                    + " name='catalog_coverage'")
                                                    != 0) {
                                                throw new IllegalArgumentException(
                                                        "Unversioned unified accounting schema");
                                            }
                                            return null;
                                        });
                            }
                            service.database = new DerivedSqliteConnection(path);
                            service.database.run(
                                    Cancellation.NONE,
                                    false,
                                    session -> {
                                        session.execute("PRAGMA synchronous=FULL");
                                        return null;
                                    });
                            service.database.run(
                                    Cancellation.NONE,
                                    true,
                                    session -> {
                                        session.execute(
                                                "CREATE TABLE IF NOT EXISTS"
                                                        + " catalog_reporting_metadata(version INTEGER"
                                                        + " NOT NULL)");
                                        if (scalar(
                                                        session,
                                                        "SELECT count(*) FROM"
                                                                + " catalog_reporting_metadata")
                                                == 0) {
                                            session.execute(
                                                    "INSERT INTO catalog_reporting_metadata"
                                                            + " VALUES(1)");
                                        }
                                        session.execute(
                                                "CREATE TABLE IF NOT EXISTS catalog_coverage(owner"
                                                        + " TEXT NOT NULL,scope TEXT NOT NULL,revision"
                                                        + " TEXT NOT NULL,files INTEGER NOT NULL"
                                                        + " CHECK(files>=0),named INTEGER NOT NULL"
                                                        + " CHECK(named>=0 AND named<=files),PRIMARY"
                                                        + " KEY(owner,scope)) WITHOUT ROWID");
                                        return null;
                                    });
                            return service;
                        });
        var result = publish(opening);
        result.whenComplete(
                (value, failure) -> {
                    if (failure != null || result.isCancelled()) {
                        service.closeAsync();
                    }
                });
        return result;
    }

    @Override
    public CompletableFuture<Void> retain(
            String owner, boolean global, String source, UsageIndex.Role role) {
        Objects.requireNonNull(role);
        return track(
                () -> {
                    if (role == UsageIndex.Role.DATABASE) {
                        relationalSources.add(new Source(owner, source));
                        return catalog.retainOwner(
                                owner,
                                global ? LibraryCatalog.Scope.GLOBAL : LibraryCatalog.Scope.BUILD);
                    }
                    return catalog.expect(
                            owner,
                            global ? LibraryCatalog.Scope.GLOBAL : LibraryCatalog.Scope.BUILD,
                            source,
                            role(role));
                });
    }

    @Override
    public CompletableFuture<Boolean> current(String owner, String source, String version) {
        return track(() -> catalog.current(owner, source, version));
    }

    @Override
    public CompletableFuture<Void> replace(
            String owner,
            String source,
            String version,
            UsageIndex.Inventory inventory,
            UsageIndex.Presence ignored,
            Cancellation token) {
        Objects.requireNonNull(inventory);
        if (relationalSources.contains(new Source(owner, source))) {
            return track(
                    () -> {
                        token.check();
                        return CompletableFuture.completedFuture(null);
                    });
        }
        var cursor = new InventoryCursor(inventory, token);
        return track(
                () ->
                        catalog.replace(owner, source, version, cursor::next, token)
                                .handle(
                                        (value, failure) -> {
                                            try {
                                                cursor.close();
                                            } catch (RuntimeException | Error cleanup) {
                                                if (failure == null) {
                                                    throw cleanup;
                                                }
                                                failure.addSuppressed(cleanup);
                                            }
                                            if (failure != null) {
                                                throw new java.util.concurrent.CompletionException(
                                                        failure);
                                            }
                                            return null;
                                        }));
    }

    private final class InventoryCursor {
        private final UsageIndex.Inventory inventory;
        private final Cancellation token;
        private UsageIndex.Cursor cursor;
        private final java.util.ArrayDeque<LibraryCatalog.Reference> ready =
                new java.util.ArrayDeque<>();
        private boolean done;

        InventoryCursor(UsageIndex.Inventory inventory, Cancellation token) {
            this.inventory = inventory;
            this.token = Objects.requireNonNull(token);
        }

        List<LibraryCatalog.Reference> next(int limit, Cancellation cancellation) throws Exception {
            token.check();
            cancellation.check();
            if (cursor == null && !done) {
                cursor = inventory.open();
            }
            while (ready.isEmpty() && !done) {
                token.check();
                cancellation.check();
                List<UsageIndex.Resource> page = List.copyOf(cursor.next(token));
                if (page.size() > LibraryCatalog.PAGE_LIMIT) {
                    throw new IllegalArgumentException("Producer page exceeds accounting limit");
                }
                if (page.isEmpty()) {
                    close();
                    break;
                }
                var payloads =
                        page.stream()
                                .filter(resource -> resource.role() != UsageIndex.Role.DATABASE)
                                .toList();
                List<ObjectKey> keys =
                        payloads.stream().map(UnifiedStorageAccounting::key).toList();
                var unknown = new ArrayList<ObjectKey>();
                for (int i = 0; i < payloads.size(); i++) {
                    if (payloads.get(i).publishedBytes() < 0) {
                        unknown.add(keys.get(i));
                    }
                }
                List<Long> lengths =
                        unknown.isEmpty()
                                ? List.of()
                                : objects.storedLengths(unknown, token).join();
                int measured = 0;
                for (int i = 0; i < payloads.size(); i++) {
                    UsageIndex.Resource resource = payloads.get(i);
                    long declared =
                            resource.publishedBytes() >= 0
                                    ? resource.publishedBytes()
                                    : Math.max(0, lengths.get(measured++));
                    ready.addLast(new LibraryCatalog.Reference(keys.get(i), declared));
                }
            }
            var result = new ArrayList<LibraryCatalog.Reference>();
            while (!ready.isEmpty() && result.size() < limit) {
                result.add(ready.removeFirst());
            }
            return List.copyOf(result);
        }

        void close() {
            done = true;
            UsageIndex.Cursor retiring = cursor;
            cursor = null;
            if (retiring != null) {
                retiring.close();
            }
        }
    }

    /**
     * Presence is already authoritative in the shared object index; no projection refresh exists.
     */
    @Override
    public CompletableFuture<Void> refresh(
            String storage, UsageIndex.Presence ignored, Cancellation token) {
        return track(
                () -> {
                    token.check();
                    return CompletableFuture.completedFuture(null);
                });
    }

    @Override
    public CompletableFuture<Void> update(
            String storage, List<String> keys, UsageIndex.Presence ignored, Cancellation token) {
        return track(
                () -> {
                    token.check();
                    return CompletableFuture.completedFuture(null);
                });
    }

    @Override
    public CompletableFuture<UsageIndex.Figures> figures(String owner) {
        return track(
                () ->
                        catalog.figures(owner, Cancellation.NONE)
                                .thenCombine(
                                        submit(
                                                () ->
                                                        database.run(
                                                                Cancellation.NONE,
                                                                false,
                                                                session -> {
                                                                    long retained =
                                                                            ((Number)
                                                                                            session
                                                                                                    .query(
                                                                                                            "SELECT"
                                                                                                                    + " count(*)"
                                                                                                                    + " FROM"
                                                                                                                    + " catalog_owners"
                                                                                                                    + " WHERE"
                                                                                                                    + " retained=1"
                                                                                                                    + " AND scope='BUILD'")
                                                                                                    .getFirst()[
                                                                                                    0])
                                                                                    .longValue();
                                                                    long incomplete =
                                                                            ((Number)
                                                                                            session
                                                                                                    .query(
                                                                                                            "SELECT"
                                                                                                                    + " count(*)"
                                                                                                                    + " FROM"
                                                                                                                    + " catalog_owners"
                                                                                                                    + " o WHERE"
                                                                                                                    + " o.retained=1"
                                                                                                                    + " AND o.scope='BUILD'"
                                                                                                                    + " AND (NOT"
                                                                                                                    + " EXISTS(SELECT"
                                                                                                                    + " 1 FROM"
                                                                                                                    + " catalog_expected"
                                                                                                                    + " e WHERE"
                                                                                                                    + " e.owner=o.id"
                                                                                                                    + " AND e.role='ORIGINAL'"
                                                                                                                    + " AND e.source"
                                                                                                                    + " NOT GLOB"
                                                                                                                    + " 'storage:*')"
                                                                                                                    + " OR EXISTS(SELECT"
                                                                                                                    + " 1 FROM"
                                                                                                                    + " catalog_expected"
                                                                                                                    + " e WHERE"
                                                                                                                    + " e.owner=o.id"
                                                                                                                    + " AND e.role='ORIGINAL'"
                                                                                                                    + " AND e.active"
                                                                                                                    + " IS NULL))")
                                                                                                    .getFirst()[
                                                                                                    0])
                                                                                    .longValue();
                                                                    return new long[] {
                                                                        retained, incomplete
                                                                    };
                                                                })),
                                        (usage, summary) ->
                                                new UsageIndex.Figures(
                                                        split(usage.original()),
                                                        split(usage.derived()),
                                                        usage.original().declaredBytes(),
                                                        usage.original().storedObjects(),
                                                        usage.original().referencedObjects(),
                                                        0,
                                                        summary[0],
                                                        summary[1],
                                                        usage.derived().inventoryComplete()
                                                                && usage.derived()
                                                                        .sharingComplete(),
                                                        usage.original().inventoryComplete()
                                                                ? Instant.now()
                                                                : Instant.EPOCH)));
    }

    // Global overlap is not additive to the canonical unique/shared partition. The legacy UI's
    // global field was additive, so preserve correct totals and expose overlap through
    // LibraryCatalog.
    private static UsageIndex.Split split(LibraryCatalog.Usage usage) {
        return new UsageIndex.Split(usage.uniqueToBuildBytes(), usage.sharedWithBuildBytes(), 0, 0);
    }

    @Override
    public CompletableFuture<UsageIndex.Coverage> coverage(
            String owner, String scope, String revision) {
        return track(
                () ->
                        submit(
                                () ->
                                        database.run(
                                                Cancellation.NONE,
                                                false,
                                                session -> {
                                                    var rows =
                                                            session.query(
                                                                    "SELECT files,named FROM"
                                                                            + " catalog_coverage WHERE"
                                                                            + " owner=? AND scope=? AND"
                                                                            + " revision=?",
                                                                    owner,
                                                                    scope,
                                                                    revision);
                                                    return rows.isEmpty()
                                                            ? null
                                                            : new UsageIndex.Coverage(
                                                                    ((Number) rows.getFirst()[0])
                                                                            .longValue(),
                                                                    ((Number) rows.getFirst()[1])
                                                                            .longValue());
                                                })));
    }

    @Override
    public CompletableFuture<Void> saveCoverage(
            String owner,
            String scope,
            String revision,
            long files,
            long named,
            Cancellation token) {
        return track(
                () ->
                        submit(
                                () ->
                                        database.run(
                                                token,
                                                true,
                                                session -> {
                                                    if (files < 0 || named < 0 || named > files) {
                                                        throw new IllegalArgumentException(
                                                                "Invalid name coverage");
                                                    }
                                                    session.execute(
                                                            "INSERT INTO catalog_coverage"
                                                                    + " VALUES(?,?,?,?,?) ON"
                                                                    + " CONFLICT(owner,scope) DO UPDATE"
                                                                    + " SET revision=excluded.revision,files=excluded.files,named=excluded.named",
                                                            owner,
                                                            scope,
                                                            revision,
                                                            files,
                                                            named);
                                                    return null;
                                                })));
    }

    @Override
    public CompletableFuture<Void> forget(String owner) {
        return track(() -> catalog.forget(owner, Cancellation.NONE));
    }

    private static LibraryCatalog.Role role(UsageIndex.Role role) {
        return role == UsageIndex.Role.DERIVED
                ? LibraryCatalog.Role.DERIVED
                : LibraryCatalog.Role.ORIGINAL;
    }

    private static ObjectKey key(UsageIndex.Resource resource) {
        byte[] bytes = HexFormat.of().parseHex(resource.key());
        if (resource.storage().equals("generated")) {
            if (bytes.length != 16) {
                throw new IllegalArgumentException("Invalid generated object key");
            }
            return new ObjectKey(ObjectKey.GENERATED, bytes);
        }
        if (bytes.length != 18) {
            throw new IllegalArgumentException("Invalid typed object key");
        }
        return new ObjectKey(
                Byte.toUnsignedInt(bytes[0]),
                Byte.toUnsignedInt(bytes[1]),
                Arrays.copyOfRange(bytes, 2, 18));
    }

    private static long scalar(DerivedSqliteConnection.Session session, String sql) {
        return ((Number) session.query(sql).getFirst()[0]).longValue();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> submit(Work<T> work) {
        var result = new CompletableFuture<T>();
        try {
            metadata.execute(
                    () -> {
                        try {
                            result.complete(work.run());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private <T> CompletableFuture<T> track(Supplier<CompletableFuture<T>> work) {
        synchronized (gate) {
            if (closing || pending.size() >= 128) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Accounting closed or busy"));
            }
            CompletableFuture<T> actual;
            try {
                actual = work.get();
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            pending.add(actual);
            var result = new CompletableFuture<T>();
            actual.whenComplete(
                    (value, failure) -> {
                        synchronized (gate) {
                            pending.remove(actual);
                        }
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        result.complete(value);
                                    } else {
                                        result.completeExceptionally(failure);
                                    }
                                });
                    });
            return result;
        }
    }

    private static <T> CompletableFuture<T> publish(CompletableFuture<T> actual) {
        var result = new CompletableFuture<T>();
        actual.whenComplete(
                (value, failure) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (failure == null) {
                                        result.complete(value);
                                    } else {
                                        result.completeExceptionally(failure);
                                    }
                                }));
        return result;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closed != null) {
                return closed.copy();
            }
            closing = true;
            closed = new CompletableFuture<>();
            CompletableFuture<?>[] accepted = pending.toArray(CompletableFuture[]::new);
            Thread.startVirtualThread(
                    () -> {
                        try {
                            CompletableFuture.allOf(accepted)
                                    .handle((value, failure) -> null)
                                    .join();
                            metadata.shutdown();
                            while (!metadata.awaitTermination(1, TimeUnit.SECONDS)) {}
                            if (database != null) {
                                database.close();
                            }
                            closed.complete(null);
                        } catch (Throwable failure) {
                            closed.completeExceptionally(failure);
                        }
                    });
            return closed.copy();
        }
    }
}
