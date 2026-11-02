package com.vibeminer.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Opt-in metadata-query benchmark, not an ingest or payload-integrity benchmark. Synthetic stored
 * objects have catalog metadata but no payload chunks, so they must never be read as content.
 */
public final class LibraryAccountingBenchmarks {
    private static final int DEFAULT_OBJECTS = 2_209_282;
    private static final int PAGE = 10_000;
    private static final String NUMBERS =
            "WITH RECURSIVE seq(n) AS (SELECT ? UNION ALL SELECT n+1 FROM seq WHERE n+1<?) ";
    private static final String KEY = "CAST(char(4)||char(0)||printf('%016x',n) AS BLOB)";
    private static final String LENGTH =
            "CASE WHEN n%10=0 THEN 2048 WHEN n%10<4 THEN 8192 WHEN n%10<8 THEN 49152 "
                    + "WHEN n%10=8 THEN 131072 ELSE 221184 END";

    private LibraryAccountingBenchmarks() {}

    public static void main(String[] args) throws Exception {
        int count = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_OBJECTS;
        if (count < 1000 || count > 3_000_000) {
            throw new IllegalArgumentException("Use 1000-3000000 objects per synthetic build");
        }
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "accounting-scale-");
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = null;
        System.out.println("fixture=metadata_only,no_payload_chunks=true,synthetic=true");
        try {
            store = SqliteObjectStore.open(path).join();
            try (LibraryCatalog catalog = LibraryCatalog.open(path).join()) {
                prepare(
                        catalog,
                        "build-a",
                        LibraryCatalog.Scope.BUILD,
                        "encoding",
                        LibraryCatalog.Role.ORIGINAL);
                prepare(
                        catalog,
                        "build-b",
                        LibraryCatalog.Scope.BUILD,
                        "encoding",
                        LibraryCatalog.Role.ORIGINAL);
                prepare(
                        catalog,
                        "build-a",
                        LibraryCatalog.Scope.BUILD,
                        "duplicate-source",
                        LibraryCatalog.Role.ORIGINAL);
                prepare(
                        catalog,
                        "build-a",
                        LibraryCatalog.Scope.BUILD,
                        "derived",
                        LibraryCatalog.Role.DERIVED);
                prepare(
                        catalog,
                        "library",
                        LibraryCatalog.Scope.GLOBAL,
                        "shared-provider",
                        LibraryCatalog.Role.ORIGINAL);
                catalog.retainOwner("foreground", LibraryCatalog.Scope.GLOBAL).join();
                long start = System.nanoTime();
                try (NativeSqlite connection = new NativeSqlite(path, false)) {
                    connection.exec("PRAGMA foreign_keys=ON");
                    connection.exec("PRAGMA synchronous=FULL");
                    String a = generation(connection, "build-a", "encoding");
                    String b = generation(connection, "build-b", "encoding");
                    int shift = count / 10;
                    seed(connection, a, 0, count, true);
                    seed(connection, b, shift, count + shift, false);
                    seedSmall(
                            connection,
                            generation(connection, "build-a", "duplicate-source"),
                            512,
                            false);
                    seedSmall(
                            connection,
                            generation(connection, "library", "shared-provider"),
                            128,
                            false);
                    seedSmall(connection, generation(connection, "build-a", "derived"), 128, true);
                    connection.query("PRAGMA wal_checkpoint(TRUNCATE)");
                    System.out.printf(
                            Locale.ROOT,
                            "objects_per_build=%d,overlap_objects=%d,metadata_seed_ms=%.3f,database_bytes=%d,sqlite=%s%n",
                            count,
                            count - shift,
                            (System.nanoTime() - start) / 1e6,
                            Files.size(path),
                            connection.query("SELECT sqlite_version()").getFirst()[0]);
                    System.out.println("phase=without_analyze");
                    printPlan(connection);
                }
                measure(catalog, store, count, "without_analyze");
                try (NativeSqlite connection = new NativeSqlite(path, false)) {
                    connection.exec("ANALYZE");
                    connection.query("PRAGMA wal_checkpoint(TRUNCATE)");
                    System.out.println("phase=with_analyze");
                    printPlan(connection);
                }
                measure(catalog, store, count, "with_analyze");
            }
        } finally {
            if (store != null) {
                store.closeAsync().join();
            }
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void measure(
            LibraryCatalog catalog, SqliteObjectStore store, int count, String phase) {
        var times = new ArrayList<Double>();
        ObjectKey absent = new ObjectKey(ObjectKey.CONFIG, new byte[16]);
        for (int round = 0; round < 3; round++) {
            long began = System.nanoTime();
            CompletableFuture<Timed> query =
                    catalog.figures("build-a", Cancellation.NONE)
                            .thenApply(
                                    result -> new Timed(result, (System.nanoTime() - began) / 1e6));
            long foregroundStart = System.nanoTime();
            boolean found = store.has(absent, Cancellation.NONE).join();
            double foregroundRead = (System.nanoTime() - foregroundStart) / 1e6;
            foregroundStart = System.nanoTime();
            catalog.retainOwner("foreground", LibraryCatalog.Scope.GLOBAL).join();
            double foregroundWrite = (System.nanoTime() - foregroundStart) / 1e6;
            Timed timed = query.join();
            LibraryCatalog.Figures result = timed.figures();
            double elapsed = timed.milliseconds();
            if (found
                    || result.original().referencedObjects() != count
                    || result.derived().referencedObjects() != 128
                    || !result.original().inventoryComplete()) {
                throw new AssertionError("Fixture accounting differs");
            }
            times.add(elapsed);
            System.out.printf(
                    Locale.ROOT,
                    "phase=%s,round=%d,figures_observed_ms=%.3f,concurrent_has_ms=%.3f,concurrent_catalog_write_ms=%.3f,original_stored=%d,original_stored_bytes=%d,shared_build_bytes=%d,unique_build_bytes=%d,global_overlap_bytes=%d%n",
                    phase,
                    round,
                    elapsed,
                    foregroundRead,
                    foregroundWrite,
                    result.original().storedObjects(),
                    result.original().storedBytes(),
                    result.original().sharedWithBuildBytes(),
                    result.original().uniqueToBuildBytes(),
                    result.original().sharedWithGlobalBytes());
        }
        times.sort(Double::compare);
        System.out.printf(
                Locale.ROOT,
                "phase=%s,figures_p50_ms=%.3f,min_ms=%.3f,max_ms=%.3f%n",
                phase,
                times.get(1),
                times.getFirst(),
                times.getLast());
    }

    private static void prepare(
            LibraryCatalog catalog,
            String owner,
            LibraryCatalog.Scope scope,
            String source,
            LibraryCatalog.Role role) {
        catalog.expect(owner, scope, source, role).join();
        catalog.replace(
                        owner,
                        source,
                        "synthetic-v1",
                        (limit, token) -> List.of(),
                        Cancellation.NONE)
                .join();
    }

    private static String generation(NativeSqlite connection, String owner, String source) {
        return (String)
                connection.query(
                                "SELECT active FROM catalog_expected WHERE owner=? AND source=?",
                                owner,
                                source)
                        .getFirst()[0];
    }

    private static void seed(
            NativeSqlite connection, String generation, int start, int end, boolean physical) {
        int nextProgress = start + 500_000;
        for (int offset = start; offset < end; offset += PAGE) {
            int from = offset;
            int to = Math.min(end, offset + PAGE);
            connection.transaction(
                    false,
                    () -> {
                        connection.exec(
                                NUMBERS
                                        + "INSERT INTO catalog_refs SELECT ?,"
                                        + KEY
                                        + ",'ORIGINAL',"
                                        + LENGTH
                                        + " FROM seq",
                                from,
                                to,
                                generation);
                        if (physical) {
                            connection.exec(
                                    NUMBERS
                                            + "INSERT INTO storage_chunk_sets(id) SELECT"
                                            + " 'fixture-original:'||n FROM seq WHERE n%100<10",
                                    from,
                                    to);
                            connection.exec(
                                    NUMBERS
                                            + "INSERT INTO storage_objects SELECT "
                                            + KEY
                                            + ","
                                            + LENGTH
                                            + ",'ORIGINAL','fixture-original:'||n,zeroblob(32),zeroblob((("
                                            + LENGTH
                                            + "+65535)/65536)*4),1,0 FROM seq WHERE n%100<10",
                                    from,
                                    to);
                        }
                    });
            if (to >= nextProgress || to == end) {
                System.out.printf(
                        Locale.ROOT,
                        "seed_refs=%d/%d,physical_metadata=%s%n",
                        to - start,
                        end - start,
                        physical);
                nextProgress += 500_000;
            }
        }
    }

    private static void seedSmall(
            NativeSqlite connection, String generation, int count, boolean derived) {
        if (!derived) {
            connection.transaction(
                    false,
                    () ->
                            connection.exec(
                                    NUMBERS
                                            + "INSERT INTO catalog_refs SELECT ?,"
                                            + KEY
                                            + ",'ORIGINAL',"
                                            + LENGTH
                                            + " FROM seq",
                                    0,
                                    count,
                                    generation));
            return;
        }
        String key = "CAST(char(7)||char(0)||printf('%016x',n) AS BLOB)";
        connection.transaction(
                false,
                () -> {
                    connection.exec(
                            NUMBERS
                                    + "INSERT INTO catalog_refs SELECT ?,"
                                    + key
                                    + ",'DERIVED',8192 FROM seq",
                            0,
                            count,
                            generation);
                    connection.exec(
                            NUMBERS
                                    + "INSERT INTO storage_chunk_sets(id) SELECT"
                                    + " 'fixture-derived:'||n FROM seq",
                            0,
                            count);
                    connection.exec(
                            NUMBERS
                                    + "INSERT INTO storage_objects SELECT "
                                    + key
                                    + ",8192,'DERIVED','fixture-derived:'||n,zeroblob(32),zeroblob(4),0,0"
                                    + " FROM seq",
                            0,
                            count);
                });
    }

    private record Timed(LibraryCatalog.Figures figures, double milliseconds) {}

    private static void printPlan(NativeSqlite connection) {
        for (Object[] row :
                connection.query(
                        "EXPLAIN QUERY PLAN " + LibraryCatalog.USAGE_SQL,
                        "build-a",
                        "build-a",
                        "build-a")) {
            System.out.println("usage_plan=" + Arrays.toString(row));
        }
    }
}
