package com.vibeminer.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent canonical ownership and accounting over the object backend's own database. Inventories
 * describe references, including objects that have not been downloaded. Payload presence is read
 * directly from storage_objects, never copied into a reporting projection.
 */
public final class LibraryCatalog implements AutoCloseable {
    public static final int PAGE_LIMIT = 1024;
    private static final int SOURCES_PER_OWNER = 128;

    // A source generation already has unique keys. Stream the largest generation directly,
    // grouping only secondary sources, so deduplication need not sort the full owner inventory.
    static final String USAGE_SQL =
            """
            WITH sources AS MATERIALIZED (
                SELECT e.active generation,
                       (SELECT count(*) FROM catalog_refs r WHERE r.generation=e.active) refs
                FROM catalog_expected e WHERE e.owner=? AND e.active IS NOT NULL
            ), principal AS MATERIALIZED (
                SELECT generation FROM sources ORDER BY refs DESC,generation LIMIT 1
            ), rest AS MATERIALIZED (
                SELECT r.k,min(nullif(r.declared,0)) declared_min,max(r.declared) declared_max,
                       min(r.role) role_min,max(r.role) role_max
                FROM sources s CROSS JOIN catalog_refs r
                WHERE s.generation<>(SELECT generation FROM principal) AND r.generation=s.generation
                GROUP BY r.k
            ), own AS (
                SELECT p.k,max(p.declared,coalesce(rest.declared_max,p.declared)) declared,
                       min(p.role,coalesce(rest.role_min,p.role)) role,
                       CASE WHEN rest.declared_min<>rest.declared_max
                                   OR (p.declared>0 AND rest.declared_min<>p.declared)
                                   OR rest.role_min<>p.role OR rest.role_max<>p.role
                            THEN 1 ELSE 0 END conflicting
                FROM catalog_refs p LEFT JOIN rest ON rest.k=p.k
                WHERE p.generation=(SELECT generation FROM principal)
                UNION ALL
                SELECT rest.k,rest.declared_max,rest.role_min,
                       CASE WHEN rest.declared_min<>rest.declared_max OR rest.role_min<>rest.role_max
                            THEN 1 ELSE 0 END conflicting
                FROM rest WHERE NOT EXISTS(
                    SELECT 1 FROM catalog_refs p
                    WHERE p.generation=(SELECT generation FROM principal) AND p.k=rest.k)
            )
            SELECT coalesce(o.role,own.role) role,count(*),coalesce(sum(own.declared),0),
                   count(o.length),coalesce(sum(o.length),0),
                   coalesce(sum(CASE WHEN o.length IS NULL THEN 0
                       WHEN EXISTS(SELECT 1 FROM catalog_refs r
                                   JOIN catalog_expected e ON e.active=r.generation
                                   JOIN catalog_owners owner ON owner.id=e.owner
                                   WHERE r.k=own.k AND owner.retained=1
                                     AND owner.scope='BUILD' AND owner.id<>?)
                       THEN o.length ELSE 0 END),0),
                   coalesce(sum(CASE WHEN o.length IS NULL THEN 0
                       WHEN EXISTS(SELECT 1 FROM catalog_refs r
                                   JOIN catalog_expected e ON e.active=r.generation
                                   JOIN catalog_owners owner ON owner.id=e.owner
                                   WHERE r.k=own.k AND owner.retained=1
                                     AND owner.scope='GLOBAL' AND owner.id<>?)
                       THEN o.length ELSE 0 END),0),
                   coalesce(sum(own.conflicting),0)
            FROM own LEFT JOIN storage_objects o ON o.k=own.k
            GROUP BY coalesce(o.role,own.role)
            """;

    public enum Scope {
        BUILD,
        GLOBAL
    }

    /** Descriptive accounting category. Never authorizes deletion or determines retention. */
    public enum Role {
        ORIGINAL,
        DERIVED
    }

    /** A declared length of zero means unknown; actual stored payloads are always nonempty. */
    public record Reference(ObjectKey key, long declaredLength) {
        public Reference {
            Objects.requireNonNull(key);
            if (declaredLength < 0 || declaredLength > Format.MAX_PAYLOAD) {
                throw new IllegalArgumentException("Invalid declared payload length");
            }
        }
    }

    /** Called on an inventory worker, outside any SQLite transaction. Empty page means complete. */
    @FunctionalInterface
    public interface Inventory {
        List<Reference> next(int limit, Cancellation token) throws Exception;
    }

    /**
     * Shared/unique classify membership among canonical BUILD owners. Global overlap is a separate
     * subset and must not be added to these two buckets. Byte totals exclude relational databases.
     */
    public record Usage(
            long referencedObjects,
            long declaredBytes,
            long storedObjects,
            long storedBytes,
            long uniqueToBuildBytes,
            long sharedWithBuildBytes,
            long sharedWithGlobalBytes,
            boolean inventoryComplete,
            boolean sharingComplete) {}

    public record Figures(Usage original, Usage derived) {}

    public record Owner(String id, Scope scope) {}

    private final Path path;
    private final Object gate = new Object();
    private final ThreadPoolExecutor writer = Async.pool("library-catalog-writer", 1);
    private final ThreadPoolExecutor readers = Async.pool("library-catalog-reader", 2);
    private final ThreadPoolExecutor inventories = Async.pool("library-catalog-inventory", 1);
    private NativeSqlite database;
    Runnable beforeReadProbe; // Deterministic tests can pause a reader before executing SQL.
    private WriterLock lock;
    private boolean closing;
    private CompletableFuture<Void> closeFuture;

    private LibraryCatalog(Path path) {
        this.path = path;
    }

    /**
     * Backend initialization must precede opening its catalog. Only one catalog writer may open.
     */
    public static CompletableFuture<LibraryCatalog> open(Path path) {
        Objects.requireNonNull(path);
        LibraryCatalog catalog = new LibraryCatalog(path.toAbsolutePath().normalize());
        return publish(
                Async.submit(
                        catalog.writer,
                        () -> {
                            try {
                                catalog.initialize();
                                return catalog;
                            } catch (Throwable failure) {
                                catalog.release();
                                catalog.writer.shutdown();
                                catalog.readers.shutdown();
                                catalog.inventories.shutdown();
                                throw failure;
                            }
                        }));
    }

    private void initialize() throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Initialize the object database before its catalog");
        }
        lock = new WriterLock(path.resolveSibling(path.getFileName() + ".catalog.lock"));
        try (var header = new NativeSqlite(path, true)) {
            validateHeader(header);
        }
        database = new NativeSqlite(path, false);
        database.exec("PRAGMA foreign_keys=ON");
        database.exec("PRAGMA journal_mode=WAL");
        // The catalog shares the library's WAL; it never deletes payloads, so a lost commit only
        // loses a suffix that ordering keeps consistent (docs/durability-policy.md).
        database.exec("PRAGMA synchronous=NORMAL");
        // A separate catalog process lock makes crash recovery safe against another importer.
        transaction(
                Cancellation.NONE,
                () -> {
                    database.exec(
                            "CREATE TABLE IF NOT EXISTS catalog_metadata(version INTEGER NOT"
                                    + " NULL)");
                    if (database.scalar("SELECT count(*) FROM catalog_metadata") == 0) {
                        database.exec("INSERT INTO catalog_metadata VALUES(1)");
                    }
                    database.exec(
                            "CREATE TABLE IF NOT EXISTS catalog_owners(id TEXT PRIMARY KEY, scope"
                                    + " TEXT NOT NULL CHECK(scope IN ('BUILD','GLOBAL')), retained"
                                    + " INTEGER NOT NULL CHECK(retained IN (0,1)))");
                    database.exec(
                            "CREATE TABLE IF NOT EXISTS catalog_expected(owner TEXT NOT NULL"
                                    + " REFERENCES catalog_owners(id), source TEXT NOT NULL, role TEXT"
                                    + " NOT NULL CHECK(role IN ('ORIGINAL','DERIVED')), active TEXT,"
                                    + " pending TEXT, version TEXT, PRIMARY KEY(owner,source)) WITHOUT"
                                    + " ROWID");
                    database.exec(
                            "CREATE TABLE IF NOT EXISTS catalog_generations(id TEXT PRIMARY KEY,"
                                    + " owner TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT"
                                    + " NULL, state TEXT NOT NULL CHECK(state IN"
                                    + " ('INCOMPLETE','ACTIVE','RETIRED')), FOREIGN KEY(owner,source)"
                                    + " REFERENCES catalog_expected(owner,source))");
                    database.exec(
                            "CREATE TABLE IF NOT EXISTS catalog_refs(generation TEXT NOT NULL"
                                    + " REFERENCES catalog_generations(id), k BLOB NOT NULL"
                                    + " CHECK(length(k)=18), role TEXT NOT NULL CHECK(role IN"
                                    + " ('ORIGINAL','DERIVED')), declared INTEGER NOT NULL"
                                    + " CHECK(declared>=0), PRIMARY KEY(generation,k)) WITHOUT ROWID");
                    database.exec(
                            "CREATE INDEX IF NOT EXISTS catalog_refs_key ON"
                                    + " catalog_refs(k,generation)");
                    database.exec(
                            "CREATE INDEX IF NOT EXISTS catalog_generation_state ON"
                                    + " catalog_generations(state,id)");
                    database.exec(
                            "CREATE UNIQUE INDEX IF NOT EXISTS catalog_active_generation ON"
                                    + " catalog_expected(active) WHERE active IS NOT NULL");
                    database.exec(
                            "CREATE INDEX IF NOT EXISTS catalog_generation_owner ON"
                                    + " catalog_generations(owner,state)");
                    database.exec(
                            "UPDATE catalog_generations SET state='RETIRED' WHERE"
                                    + " state='INCOMPLETE'");
                    database.exec(
                            "UPDATE catalog_expected SET pending=NULL WHERE pending IS NOT NULL");
                    return null;
                });
    }

    private static void validateHeader(NativeSqlite header) {
        requireSqliteVersion((String) header.query("SELECT sqlite_version()").getFirst()[0]);
        try {
            if (header.scalar("PRAGMA application_id") != 0x56494d49
                            && header.scalar("PRAGMA application_id") != 2
                    || header.scalar("SELECT count(*) FROM storage_metadata") != 1
                    || header.scalar("SELECT version FROM storage_metadata") != 1) {
                throw new IllegalStateException("Unsupported object database identity/version");
            }
            header.query("SELECT k,length,role FROM storage_objects LIMIT 0");
            boolean versioned =
                    header.scalar(
                                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND"
                                            + " name='catalog_metadata'")
                            != 0;
            if (versioned) {
                if (header.scalar("SELECT count(*) FROM catalog_metadata") != 1
                        || header.scalar("SELECT version FROM catalog_metadata") != 1) {
                    throw new IllegalStateException("Unsupported catalog database version");
                }
                header.query("SELECT id,scope,retained FROM catalog_owners LIMIT 0");
                header.query(
                        "SELECT owner,source,role,active,pending,version FROM catalog_expected"
                                + " LIMIT 0");
                header.query(
                        "SELECT id,owner,source,version,state FROM catalog_generations LIMIT 0");
                header.query("SELECT generation,k,role,declared FROM catalog_refs LIMIT 0");
            } else if (header.scalar(
                            "SELECT count(*) FROM sqlite_master WHERE name GLOB 'catalog_*'")
                    != 0) {
                throw new IllegalStateException("Unversioned catalog schema");
            }
        } catch (RuntimeException error) {
            throw new StoreException(
                    "OBJECT_STORE_FORMAT", "Unsupported library catalog database", error);
        }
    }

    static void requireSqliteVersion(String version) {
        var parsed =
                java.util.regex.Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})")
                        .matcher(version == null ? "" : version);
        if (!parsed.matches()) {
            throw new StoreException(
                    "OBJECT_STORE_NATIVE_LIBRARY",
                    "Cannot determine SQLite version; the library catalog requires SQLite 3.35 or"
                            + " newer");
        }
        int major = Integer.parseInt(parsed.group(1));
        int minor = Integer.parseInt(parsed.group(2));
        if (major < 3 || (major == 3 && minor < 35)) {
            throw new StoreException(
                    "OBJECT_STORE_NATIVE_LIBRARY",
                    "The library catalog requires SQLite 3.35 or newer for bounded ownership"
                            + " queries; install a newer SQLite shared library");
        }
    }

    public CompletableFuture<Void> retainOwner(String owner, Scope scope) {
        text(owner);
        Objects.requireNonNull(scope);
        return write(
                Cancellation.NONE,
                () -> {
                    retain(owner, scope);
                    return null;
                });
    }

    /** Declare all inventory sources before importing, so missing snapshots remain visible. */
    public CompletableFuture<Void> expect(String owner, Scope scope, String source, Role role) {
        text(owner);
        text(source);
        if (source.startsWith("storage:")) {
            throw new IllegalArgumentException("Publication inventories are owned by storage");
        }
        Objects.requireNonNull(scope);
        Objects.requireNonNull(role);
        return write(
                Cancellation.NONE,
                () -> {
                    retain(owner, scope);
                    List<Object[]> existing =
                            database.query(
                                    "SELECT role FROM catalog_expected WHERE owner=? AND source=?",
                                    owner,
                                    source);
                    if (!existing.isEmpty()) {
                        if (!existing.getFirst()[0].equals(role.name())) {
                            throw new IllegalArgumentException(
                                    "Inventory role differs from its declaration");
                        }
                        return null;
                    }
                    if (database.scalar(
                                    "SELECT count(*) FROM catalog_expected WHERE owner=?", owner)
                            >= SOURCES_PER_OWNER) {
                        throw new IllegalArgumentException("Too many inventory sources for owner");
                    }
                    database.exec(
                            "INSERT INTO catalog_expected(owner,source,role) VALUES(?,?,?)",
                            owner,
                            source,
                            role.name());
                    return null;
                });
    }

    private void retain(String owner, Scope scope) {
        List<Object[]> existing =
                database.query("SELECT scope FROM catalog_owners WHERE id=?", owner);
        if (!existing.isEmpty() && !existing.getFirst()[0].equals(scope.name())) {
            throw new IllegalArgumentException("Canonical owner scope cannot change");
        }
        database.exec(
                "INSERT INTO catalog_owners VALUES(?,?,1) ON CONFLICT(id) DO UPDATE SET retained=1",
                owner,
                scope.name());
    }

    /** Stage bounded pages, then switch the completed generation in one small transaction. */
    public CompletableFuture<Void> replace(
            String owner, String source, String version, Inventory inventory, Cancellation token) {
        text(owner);
        text(source);
        if (source.startsWith("storage:")) {
            throw new IllegalArgumentException("Publication inventories are owned by storage");
        }
        text(version);
        Objects.requireNonNull(inventory);
        Objects.requireNonNull(token);
        synchronized (gate) {
            if (closing) {
                return closed();
            }
            return publish(
                    Async.submit(
                            inventories,
                            () -> {
                                String generation =
                                        Async.submit(
                                                        writer,
                                                        () ->
                                                                transaction(
                                                                        token,
                                                                        () ->
                                                                                begin(
                                                                                        owner,
                                                                                        source,
                                                                                        version)))
                                                .join();
                                try {
                                    while (true) {
                                        token.check();
                                        List<Reference> page =
                                                List.copyOf(inventory.next(PAGE_LIMIT, token));
                                        token.check();
                                        if (page.size() > PAGE_LIMIT) {
                                            throw new IllegalArgumentException(
                                                    "Inventory page exceeds limit");
                                        }
                                        if (page.isEmpty()) {
                                            break;
                                        }
                                        Async.submit(
                                                        writer,
                                                        () ->
                                                                transaction(
                                                                        token,
                                                                        () -> {
                                                                            append(
                                                                                    generation,
                                                                                    page);
                                                                            return null;
                                                                        }))
                                                .join();
                                    }
                                    Async.submit(
                                                    writer,
                                                    () ->
                                                            transaction(
                                                                    token,
                                                                    () -> {
                                                                        publish(
                                                                                owner,
                                                                                source,
                                                                                version,
                                                                                generation);
                                                                        return null;
                                                                    }))
                                            .join();
                                    return null;
                                } catch (Exception | Error failure) {
                                    try {
                                        Async.submit(
                                                        writer,
                                                        () ->
                                                                transaction(
                                                                        Cancellation.NONE,
                                                                        () -> {
                                                                            // Never retire a
                                                                            // generation
                                                                            // that actually became
                                                                            // active
                                                                            // after an
                                                                            // uncertain commit
                                                                            // result.
                                                                            database.exec(
                                                                                    "UPDATE"
                                                                                            + " catalog_generations"
                                                                                            + " SET state='RETIRED'"
                                                                                            + " WHERE"
                                                                                            + " id=?"
                                                                                            + " AND state='INCOMPLETE'",
                                                                                    generation);
                                                                            database.exec(
                                                                                    "UPDATE"
                                                                                            + " catalog_expected"
                                                                                            + " SET pending=NULL"
                                                                                            + " WHERE"
                                                                                            + " owner=?"
                                                                                            + " AND source=?"
                                                                                            + " AND pending=?",
                                                                                    owner,
                                                                                    source,
                                                                                    generation);
                                                                            return null;
                                                                        }))
                                                .join();
                                    } catch (Exception | Error cleanup) {
                                        failure.addSuppressed(cleanup);
                                    }
                                    throw failure;
                                }
                            }));
        }
    }

    private String begin(String owner, String source, String version) {
        List<Object[]> rows =
                database.query(
                        "SELECT e.pending FROM catalog_expected e JOIN catalog_owners o ON"
                                + " o.id=e.owner WHERE e.owner=? AND e.source=? AND o.retained=1",
                        owner,
                        source);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Unknown inventory");
        }
        // This instance has one inventory worker. A pending generation here can only be
        // abandoned work whose cleanup failed; an active concurrent catalog is excluded by lock.
        if (rows.getFirst()[0] != null) {
            database.exec(
                    "UPDATE catalog_generations SET state='RETIRED' WHERE id=? AND"
                            + " state='INCOMPLETE'",
                    rows.getFirst()[0]);
        }
        String id = UUID.randomUUID().toString();
        database.exec(
                "INSERT INTO catalog_generations VALUES(?,?,?,?,'INCOMPLETE')",
                id,
                owner,
                source,
                version);
        database.exec(
                "UPDATE catalog_expected SET pending=? WHERE owner=? AND source=?",
                id,
                owner,
                source);
        return id;
    }

    private void append(String generation, List<Reference> page) {
        List<Object[]> rows =
                database.query(
                        "SELECT e.role FROM catalog_generations g JOIN catalog_expected e ON"
                                + " e.pending=g.id JOIN catalog_owners o ON o.id=e.owner WHERE g.id=?"
                                + " AND g.state='INCOMPLETE' AND o.retained=1",
                        generation);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Inventory is no longer pending");
        }
        String role = (String) rows.getFirst()[0];
        for (int offset = 0; offset < page.size(); offset += 128) {
            List<Reference> part = page.subList(offset, Math.min(offset + 128, page.size()));
            var sql = new StringBuilder("INSERT INTO catalog_refs VALUES");
            var parameters = new java.util.ArrayList<Object>();
            for (Reference reference : part) {
                if (!parameters.isEmpty()) {
                    sql.append(',');
                }
                sql.append("(?,?,?,?)");
                parameters.add(generation);
                parameters.add(reference.key().indexBytes());
                parameters.add(role);
                parameters.add(reference.declaredLength());
            }
            sql.append(
                    " ON CONFLICT(generation,k) DO UPDATE SET declared=CASE WHEN"
                            + " catalog_refs.declared=0 THEN excluded.declared WHEN excluded.declared=0"
                            + " OR catalog_refs.declared=excluded.declared THEN catalog_refs.declared"
                            + " ELSE -1 END");
            try {
                database.exec(sql.toString(), parameters.toArray());
            } catch (NativeSqlite.Failure failure) {
                if ((failure.result & 255) == 19) {
                    throw new IllegalArgumentException(
                            "Conflicting lengths for the same object", failure);
                }
                throw failure;
            }
        }
    }

    private void publish(String owner, String source, String version, String generation) {
        List<Object[]> rows =
                database.query(
                        "SELECT e.active FROM catalog_expected e JOIN catalog_owners o ON"
                                + " o.id=e.owner WHERE e.owner=? AND e.source=? AND e.pending=? AND"
                                + " o.retained=1",
                        owner,
                        source,
                        generation);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Inventory is no longer pending");
        }
        Object previous = rows.getFirst()[0];
        if (previous != null) {
            database.exec("UPDATE catalog_generations SET state='RETIRED' WHERE id=?", previous);
        }
        database.exec(
                "UPDATE catalog_generations SET state='ACTIVE' WHERE id=? AND state='INCOMPLETE'",
                generation);
        database.exec(
                "UPDATE catalog_expected SET active=?,pending=NULL,version=? WHERE owner=? AND"
                        + " source=?",
                generation,
                version,
                owner,
                source);
    }

    /** Returns retained canonical owner intent in bounded, stable ID order after restart. */
    public CompletableFuture<List<Owner>> owners(String after, int limit, Cancellation token) {
        if (limit < 1 || limit > PAGE_LIMIT) {
            throw new IllegalArgumentException("Owner page limit must be 1-1024");
        }
        Objects.requireNonNull(token);
        return read(
                token,
                connection -> {
                    List<Object[]> rows =
                            after == null
                                    ? connection.query(
                                            "SELECT id,scope FROM catalog_owners WHERE retained=1"
                                                    + " ORDER BY id LIMIT ?",
                                            limit)
                                    : connection.query(
                                            "SELECT id,scope FROM catalog_owners WHERE retained=1"
                                                    + " AND id>? ORDER BY id LIMIT ?",
                                            after,
                                            limit);
                    return rows.stream()
                            .map(row -> new Owner((String) row[0], Scope.valueOf((String) row[1])))
                            .toList();
                });
    }

    public CompletableFuture<Boolean> current(String owner, String source, String version) {
        text(owner);
        text(source);
        text(version);
        return read(
                Cancellation.NONE,
                connection ->
                        connection.scalar(
                                        "SELECT count(*) FROM catalog_expected e JOIN"
                                                + " catalog_owners o ON o.id=e.owner WHERE e.owner=?"
                                                + " AND e.source=? AND e.version=? AND e.active IS NOT"
                                                + " NULL AND o.retained=1",
                                        owner,
                                        source,
                                        version)
                                != 0);
    }

    public CompletableFuture<Figures> figures(String owner, Cancellation token) {
        text(owner);
        Objects.requireNonNull(token);
        return read(
                token,
                connection -> {
                    List<Object[]> rows =
                            connection.query(
                                    "SELECT scope FROM catalog_owners WHERE id=? AND retained=1",
                                    owner);
                    if (rows.isEmpty()) {
                        throw new IllegalArgumentException("Unknown retained owner");
                    }
                    Scope scope = Scope.valueOf((String) rows.getFirst()[0]);
                    var totals = new java.util.EnumMap<Role, Object[]>(Role.class);
                    for (Object[] row : connection.query(USAGE_SQL, owner, owner, owner)) {
                        if (number(row[7]) != 0) {
                            throw new IllegalStateException(
                                    "Conflicting metadata for a referenced object across inventory"
                                            + " sources");
                        }
                        totals.put(Role.valueOf((String) row[0]), row);
                    }
                    return new Figures(
                            usage(
                                    connection,
                                    owner,
                                    scope,
                                    Role.ORIGINAL,
                                    totals.get(Role.ORIGINAL)),
                            usage(
                                    connection,
                                    owner,
                                    scope,
                                    Role.DERIVED,
                                    totals.get(Role.DERIVED)));
                });
    }

    private Usage usage(
            NativeSqlite connection, String owner, Scope scope, Role role, Object[] totals) {
        if (totals == null) {
            totals = new Object[] {role.name(), 0L, 0L, 0L, 0L, 0L, 0L, 0L};
        }
        boolean missing =
                connection.scalar(
                                "SELECT count(*) FROM catalog_expected WHERE owner=? AND role=? AND"
                                        + " active IS NULL",
                                owner,
                                role.name())
                        != 0;
        boolean undeclared =
                scope == Scope.BUILD
                        && role == Role.ORIGINAL
                        && connection.scalar(
                                        "SELECT count(*) FROM catalog_expected WHERE owner=? AND"
                                                + " role=? AND source NOT GLOB 'storage:*'",
                                        owner,
                                        role.name())
                                == 0;
        boolean sharingComplete =
                connection.scalar(
                                """
                                SELECT count(*) FROM catalog_owners o WHERE retained=1 AND (
                                    EXISTS(SELECT 1 FROM catalog_expected e WHERE e.owner=o.id AND e.role=? AND e.active IS NULL)
                                    OR (o.scope='BUILD' AND ?='ORIGINAL' AND NOT EXISTS(
                                        SELECT 1 FROM catalog_expected e WHERE e.owner=o.id AND e.role='ORIGINAL' AND e.source NOT GLOB 'storage:*')))
                                """,
                                role.name(),
                                role.name())
                        == 0;
        long stored = number(totals[4]);
        long sharedBuild = number(totals[5]);
        return new Usage(
                number(totals[1]),
                number(totals[2]),
                number(totals[3]),
                stored,
                stored - sharedBuild,
                sharedBuild,
                number(totals[6]),
                !missing && !undeclared,
                sharingComplete);
    }

    /**
     * Removes membership immediately without deleting payloads or doing an unbounded refs delete.
     */
    public CompletableFuture<Void> forget(String owner, Cancellation token) {
        text(owner);
        return write(
                token,
                () -> {
                    database.exec("UPDATE catalog_owners SET retained=0 WHERE id=?", owner);
                    database.exec(
                            "UPDATE catalog_generations SET state='RETIRED' WHERE owner=? AND"
                                    + " state<>'RETIRED'",
                            owner);
                    database.exec(
                            "UPDATE catalog_expected SET active=NULL,pending=NULL,version=NULL"
                                    + " WHERE owner=?",
                            owner);
                    return null;
                });
    }

    /** Reclaims at most PAGE_LIMIT retired reference rows, never touches stored payloads. */
    public CompletableFuture<Integer> reclaim(Cancellation token) {
        return write(
                token,
                () -> {
                    List<Object[]> rows =
                            database.query(
                                    "SELECT r.generation,r.k FROM catalog_refs r JOIN"
                                            + " catalog_generations g ON g.id=r.generation WHERE"
                                            + " g.state='RETIRED' LIMIT ?",
                                    PAGE_LIMIT);
                    for (Object[] row : rows) {
                        database.exec(
                                "DELETE FROM catalog_refs WHERE generation=? AND k=?",
                                row[0],
                                row[1]);
                    }
                    database.exec(
                            "DELETE FROM catalog_generations WHERE id IN (SELECT g.id FROM"
                                    + " catalog_generations g WHERE g.state='RETIRED' AND NOT"
                                    + " EXISTS(SELECT 1 FROM catalog_refs r WHERE r.generation=g.id)"
                                    + " LIMIT ?)",
                            PAGE_LIMIT);
                    return rows.size();
                });
    }

    private <T> CompletableFuture<T> write(Cancellation token, Async.Work<T> work) {
        Objects.requireNonNull(token);
        synchronized (gate) {
            return closing
                    ? closed()
                    : publish(Async.submit(writer, () -> transaction(token, work)));
        }
    }

    private <T> T transaction(Cancellation token, Async.Work<T> work) throws Exception {
        token.check();
        AtomicReference<T> result = new AtomicReference<>();
        return database.withCancellation(
                token,
                () -> {
                    database.transaction(
                            false,
                            () -> {
                                try {
                                    result.set(work.run());
                                    token.check();
                                } catch (RuntimeException | Error error) {
                                    throw error;
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            });
                    return result.get();
                });
    }

    private <T> CompletableFuture<T> read(
            Cancellation token, java.util.function.Function<NativeSqlite, T> work) {
        synchronized (gate) {
            if (closing) {
                return closed();
            }
            return publish(
                    Async.submit(
                            readers,
                            () -> {
                                if (beforeReadProbe != null) {
                                    beforeReadProbe.run();
                                }
                                token.check();
                                try (NativeSqlite connection = new NativeSqlite(path, true)) {
                                    return connection.withCancellation(
                                            token,
                                            () -> {
                                                connection.exec("BEGIN");
                                                try {
                                                    return work.apply(connection);
                                                } finally {
                                                    connection.uncancelled(
                                                            () -> connection.exec("ROLLBACK"));
                                                }
                                            });
                                }
                            }));
        }
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closeFuture != null) {
                return publish(closeFuture);
            }
            closing = true;
            inventories.shutdown();
            closeFuture = new CompletableFuture<>();
            Thread closer =
                    new Thread(
                            () -> {
                                try {
                                    await(inventories);
                                    writer.shutdown();
                                    readers.shutdown();
                                    await(writer);
                                    await(readers);
                                    // No connection operation remains; closing on this drain thread
                                    // cannot
                                    // race an actor and does not need space in an already-full
                                    // worker queue.
                                    release();
                                    closeFuture.complete(null);
                                } catch (Throwable failure) {
                                    closeFuture.completeExceptionally(failure);
                                }
                            },
                            "library-catalog-close");
            closer.setDaemon(true);
            closer.start();
            return publish(closeFuture);
        }
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    private void release() {
        try {
            if (database != null) {
                database.close();
            }
        } finally {
            if (lock != null) {
                lock.close();
            }
        }
    }

    private static void await(ThreadPoolExecutor executor) throws InterruptedException {
        while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            // Accepted work drains; producer calls are deliberately outside SQLite transactions.
        }
    }

    // User continuations may synchronously close this catalog. Never publish them on a
    // worker that close must drain. Cancellation of this view does not cancel accepted work.
    private static <T> CompletableFuture<T> publish(CompletableFuture<T> work) {
        var result = new CompletableFuture<T>();
        work.whenComplete(
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

    private static <T> CompletableFuture<T> closed() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Library catalog is closing"));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) {
            throw new IllegalArgumentException("Catalog identifier must contain 1-1024 characters");
        }
    }
}
