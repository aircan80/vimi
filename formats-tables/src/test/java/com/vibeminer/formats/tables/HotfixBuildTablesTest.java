package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class HotfixBuildTablesTest {
    private static final TableTypes.Priority P = TableTypes.Priority.INTERACTIVE;

    private static byte[] entry(long hash, int push, int id, int state, byte[] payload) {
        return ByteBuffer.allocate(32 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x48544658)
                .putInt(70)
                .putInt(push)
                .putInt(1)
                .putInt((int) hash)
                .putInt(id)
                .putInt(payload.length)
                .put((byte) state)
                .put(new byte[3])
                .put(payload)
                .array();
    }

    private static byte[] number(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();
    }

    private static Db2ByteSource cache(byte[]... entries) {
        int length = 44;
        for (var e : entries) {
            length += e.length;
        }
        var bytes =
                ByteBuffer.allocate(length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x48544658)
                        .putInt(9)
                        .putInt(4)
                        .put(new byte[32]);
        for (var e : entries) {
            bytes.put(e);
        }
        return Db2ByteSource.memory(bytes.array());
    }

    private static BuildTables numbers(long hash, byte[]... changes) {
        return numbers(hash, cache(changes), false);
    }

    private static BuildTables numbers(long hash, Db2ByteSource source, boolean relation) {
        // Each test charges its own accountant, never the process-wide one.
        return numbers(hash, source, relation, new HotfixMemory(64L << 20, 256L << 20));
    }

    private static BuildTables numbers(
            long hash, Db2ByteSource source, boolean relation, HotfixMemory memory) {
        return numbers(
                hash,
                source,
                memory,
                9,
                relation ? "int ID\nint<Numbers::ID> Value" : "int ID\nint Value",
                "$id,noninline$ID<u32>\nValue<u32>");
    }

    private static BuildTables numbers(
            long hash,
            Db2ByteSource source,
            HotfixMemory memory,
            long baseValue,
            String columns,
            String fields) {
        return numbers(hash, source, memory, baseValue, columns, fields, 200_000);
    }

    /** Base row 7 (copied to 8) holds {@code baseValue} in a 32-bit physical Value field. */
    private static BuildTables numbers(
            long hash,
            Db2ByteSource source,
            HotfixMemory memory,
            long baseValue,
            String columns,
            String fields,
            int maxScanRows) {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, baseValue, new long[0], true);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(152, (int) hash);
        var files =
                new MemoryFiles()
                        .add(
                                TablesContract.BUILD,
                                "enUS",
                                new FileTypes.BuildFile(
                                        100,
                                        "dbfilesclient/numbers.db2",
                                        FileTypes.FilePathSource.LISTFILE,
                                        (long) bytes.length,
                                        "1".repeat(32),
                                        FileTypes.FileFormat.DB2,
                                        FileTypes.FileFormatSource.EXTENSION,
                                        FileTypes.FileStorage.STORED,
                                        false),
                                bytes,
                                List.of());
        var document = Db2Fixture.definition(columns, fields);
        String hex = String.format("%08X", hash);
        var definitions =
                new TableDefinitions(
                        BundledDefinitions.of(
                                List.of(
                                        new BundledDefinitions.DefinitionTable(
                                                hex, "Numbers", "100", true)),
                                Map.of(
                                        hex,
                                        new BundledDefinitions.Definition(
                                                "a".repeat(64), "authored", document))),
                        null,
                        null);
        var index =
                HotfixCacheReader.inspect(
                                source, 4, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        var options =
                new TablesOptions(
                                (b, t) -> CompletableFuture.completedFuture("1.2.3.4"),
                                new com.vibeminer.formats.db2.Db2Types.Limits(),
                                build -> com.vibeminer.formats.generated.TactKeyStatus.NONE,
                                new TableWorkScheduler(),
                                maxScanRows,
                                256,
                                1_000_000)
                        .withLocalDefinitionsOnly()
                        .withHotfix(
                                new HotfixBinding(
                                        TablesContract.BUILD, "enUS", "1.2.3.4", source, index))
                        .withHotfixMemory(memory);
        return new TablesService(
                        localInventory(files),
                        definitions,
                        new GeneratedTables(new MemoryGeneratedStore()),
                        options)
                .forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    /** One handle over several authored numeric tables: file 100 + i has hash {@code hashes[i]}. */
    private static BuildTables several(HotfixMemory memory, Db2ByteSource source, long... hashes) {
        return several(memory, new MemoryGeneratedStore(), false, source, hashes);
    }

    /** With {@code related}, every table after the first has a Value relation to the first. */
    private static BuildTables several(
            HotfixMemory memory,
            MemoryGeneratedStore store,
            boolean related,
            Db2ByteSource source,
            long... hashes) {
        var files = new MemoryFiles();
        var tables = new java.util.ArrayList<BundledDefinitions.DefinitionTable>();
        var documents = new java.util.HashMap<String, BundledDefinitions.Definition>();
        for (int i = 0; i < hashes.length; i++) {
            byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(152, (int) hashes[i]);
            files.add(
                    TablesContract.BUILD,
                    "enUS",
                    new FileTypes.BuildFile(
                            100 + i,
                            "dbfilesclient/numbers" + i + ".db2",
                            FileTypes.FilePathSource.LISTFILE,
                            (long) bytes.length,
                            String.format("%032x", i + 1),
                            FileTypes.FileFormat.DB2,
                            FileTypes.FileFormatSource.EXTENSION,
                            FileTypes.FileStorage.STORED,
                            false),
                    bytes,
                    List.of());
            String hex = String.format("%08X", hashes[i]);
            tables.add(
                    new BundledDefinitions.DefinitionTable(
                            hex, "Numbers" + i, Integer.toString(100 + i), true));
            documents.put(
                    hex,
                    new BundledDefinitions.Definition(
                            Integer.toString(i).repeat(64).substring(0, 64),
                            "authored",
                            Db2Fixture.definition(
                                    related && i > 0
                                            ? "int ID\nint<Numbers0::ID> Value"
                                            : "int ID\nint Value",
                                    "$id,noninline$ID<u32>\nValue<u32>")));
        }
        var definitions =
                new TableDefinitions(BundledDefinitions.of(tables, documents), null, null);
        var index =
                HotfixCacheReader.inspect(
                                source, 4, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        var options =
                new TablesOptions((b, t) -> CompletableFuture.completedFuture("1.2.3.4"))
                        .withLocalDefinitionsOnly()
                        .withHotfix(
                                new HotfixBinding(
                                        TablesContract.BUILD, "enUS", "1.2.3.4", source, index))
                        .withHotfixMemory(memory);
        return new TablesService(
                        localInventory(files), definitions, new GeneratedTables(store), options)
                .forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    private static Db2ByteSource rows(long hash, int first, int count) {
        var changes = new byte[count][];
        for (int i = 0; i < count; i++) {
            changes[i] = entry(hash, 1, first + i, 1, number(i));
        }
        return cache(changes);
    }

    private static Db2ByteSource join(long smallHash, long largeHash, int largeRows) {
        var changes = new byte[largeRows + 1][];
        changes[0] = entry(smallHash, 1, 1_000, 1, number(1));
        for (int i = 0; i < largeRows; i++) {
            changes[i + 1] = entry(largeHash, 1, 1_000 + i, 1, number(i));
        }
        return cache(changes);
    }

    @Test
    void fullSharedBudgetRefusesWithoutCollateralEvictionOrRepeatedCompiles() throws Exception {
        long small = 0x11223344L, large = 0x55667788L;
        // Each large view charges about 0.9 MiB; two do not fit 1.5 MiB together.
        var memory = new HotfixMemory(1 << 20, 3 << 19);
        var other = numbers(small, rows(small, 1_000, 1_000), false, memory);
        try (var tables = several(memory, join(small, large, 1_000), small, large)) {
            var hot = (HotfixBuildTables) tables;
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    other.get(TableTypes.Ref.id(100), 1_500, P, Cancellation.NONE)
                            .join()
                            .outcome());
            // The small table fits beside the other handle's large view.
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome());
            assertEquals(1, hot.compiles);
            // The large table cannot fit even after evicting the small view: refused without
            // evicting it, and decoded no rows.
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    tables.get(TableTypes.Ref.id(101), 1_500, P, Cancellation.NONE)
                            .join()
                            .outcome());
            assertEquals(1, hot.compiles, "the floor check refuses before decoding");
            assertTrue(
                    tables.hotfixInfo(TableTypes.Ref.id(100), null, P, Cancellation.NONE)
                                    .join()
                                    .orElseThrow()
                                    .unavailable()
                            == null,
                    "the small view was not evicted");
            for (int i = 0; i < 5; i++) {
                tables.get(TableTypes.Ref.id(101), 1_000 + i, P, Cancellation.NONE).join();
                tables.page(
                                new TableTypes.PageRequest(TableTypes.Ref.id(101)),
                                P,
                                Cancellation.NONE)
                        .join();
            }
            assertEquals(1, hot.compiles, "no retry until some handle releases memory");
            assertEquals(
                    HotfixViewInfo.RETAINED_BUDGET,
                    tables.hotfixInfo(TableTypes.Ref.id(101), null, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .unavailable());
            other.close();
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    tables.get(TableTypes.Ref.id(101), 1_500, P, Cancellation.NONE)
                            .join()
                            .outcome());
            assertEquals(2, hot.compiles, "one retry after the release");
            tables.get(TableTypes.Ref.id(101), 1_501, P, Cancellation.NONE).join();
            assertEquals(2, hot.compiles, "the retained view is reused");
        } finally {
            other.close();
        }
        assertEquals(0, memory.reserved());
    }

    private static ClientBuildTables base(BuildTables tables) throws Exception {
        var field = HotfixBuildTables.class.getDeclaredField("base");
        field.setAccessible(true);
        return (ClientBuildTables) field.get(tables);
    }

    @Test
    void bulkOrdinalsMatchGetOnTheDirectAndTheStoredPath() throws Exception {
        long hash = 0x11223344L;
        var store = new MemoryGeneratedStore();
        try (var tables =
                several(
                        new HotfixMemory(64L << 20, 256L << 20),
                        store,
                        false,
                        rows(hash, 7, 1),
                        hash)) {
            var base = base(tables);
            var ids = List.of(7L, 8L, 9L, 4_000_000_000L);
            for (var priority :
                    List.of(TableTypes.Priority.INTERACTIVE, TableTypes.Priority.BACKGROUND)) {
                int reads = store.reads() + store.rangeReads();
                var bulk =
                        base.foundOrdinals(TableTypes.Ref.id(100), ids, priority, Cancellation.NONE)
                                .join();
                if (priority == TableTypes.Priority.BACKGROUND) {
                    assertTrue(
                            store.reads() + store.rangeReads() > reads,
                            "background preparation reads the stored graph");
                }
                int found = 0;
                for (long id : ids) {
                    var row =
                            base.get(TableTypes.Ref.id(100), id, priority, Cancellation.NONE)
                                    .join();
                    if (row.outcome() == TableTypes.RowOutcome.FOUND) {
                        found++;
                        assertEquals(row.row().ordinal(), bulk.get(id), priority + " " + id);
                    } else {
                        assertFalse(bulk.containsKey(id), priority + " " + id);
                    }
                }
                assertEquals(found, bulk.size());
                assertTrue(found > 0);
            }
        }
    }

    @Test
    void damagedStoredGraphIsReportedOnceNotRetriedPerId() throws Exception {
        long hash = 0x11223344L;
        var store = new MemoryGeneratedStore();
        try (var tables =
                several(
                        new HotfixMemory(64L << 20, 256L << 20),
                        store,
                        false,
                        rows(hash, 1_000, 200),
                        hash)) {
            var base = base(tables);
            var background = TableTypes.Priority.BACKGROUND;
            base.get(TableTypes.Ref.id(100), 7, background, Cancellation.NONE).join();
            assertFalse(store.keys().isEmpty());
            for (var key : store.keys()) {
                store.damage(key);
            }
            int reads = store.reads() + store.rangeReads();
            assertEquals(
                    null,
                    base.foundOrdinals(
                                    TableTypes.Ref.id(100),
                                    java.util.stream.LongStream.range(1_000, 1_200)
                                            .boxed()
                                            .toList(),
                                    background,
                                    Cancellation.NONE)
                            .join());
            assertTrue(
                    store.reads() + store.rangeReads() - reads < 20,
                    "200 IDs do not each prepare the damaged table");
        }
    }

    @Test
    void unreadableBaseBacksOffWithoutDecodingAndRetriesLater() {
        long hash = 0x11223344L;
        try (var tables =
                numbers(
                        hash,
                        rows(hash, 1_000, 200),
                        false,
                        new HotfixMemory(64L << 20, 256L << 20))) {
            var hot = (HotfixBuildTables) tables;
            var now = new java.util.concurrent.atomic.AtomicLong();
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            hot.clock = now::get;
            hot.ordinalsProbe =
                    found -> {
                        reads.incrementAndGet();
                        return null;
                    };
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome());
            assertEquals(
                    HotfixViewInfo.BASE_UNAVAILABLE,
                    tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .unavailable());
            assertEquals(1, reads.get());
            assertEquals(0, hot.compiles, "an unreadable base costs no decode");
            for (int i = 0; i < 5; i++) {
                tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE).join();
                tables.page(
                                new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                P,
                                Cancellation.NONE)
                        .join();
            }
            assertEquals(1, reads.get(), "no retry while backing off");
            now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
            tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE).join();
            assertEquals(2, reads.get(), "one retry after the first delay");
            now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
            tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE).join();
            assertEquals(2, reads.get(), "the delay doubles after a second failure");
            now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
            hot.ordinalsProbe = found -> found;
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome(),
                    "a later retry applies the overlay");
            assertEquals(1, hot.compiles);
        }
    }

    @Test
    void tableRefusedOnlyByItsOwnPinnedViewsRecoversOnTheNextOperation() throws Exception {
        long[] hashes = {0x20000000L, 0x20000001L};
        var changes = new byte[2_000][];
        for (int i = 0; i < 1_000; i++) {
            // Table 0 changes row 7 plus 999 added rows; table 1 points 1,000 rows at row 7.
            changes[i] = entry(hashes[0], 1, i == 0 ? 7 : 10_000 + i, 1, number(i));
            changes[1_000 + i] = entry(hashes[1], 1, 20_000 + i, 1, number(7));
        }
        // Each view charges about 0.9 MiB; the shared total holds one of them.
        var memory = new HotfixMemory(1 << 20, 3 << 19);
        try (var tables =
                several(memory, new MemoryGeneratedStore(), true, cache(changes), hashes)) {
            var hot = (HotfixBuildTables) tables;
            // One operation pins table 0 and then needs table 1: refused by its own pins only.
            tables.referencedBy(
                            new TableTypes.ReferencesRequest(TableTypes.Ref.id(100), 7),
                            P,
                            Cancellation.NONE)
                    .join();
            assertEquals(
                    HotfixViewInfo.RETAINED_BUDGET,
                    tables.hotfixInfo(TableTypes.Ref.id(101), null, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .unavailable());
            long generation = memory.generation();
            // No other handle releases anything; the next operation unpins table 0 and retries.
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    tables.get(TableTypes.Ref.id(101), 20_000, P, Cancellation.NONE)
                            .join()
                            .outcome(),
                    "recovers without an outside release");
            assertTrue(hot.compiles >= 2);
            assertTrue(memory.generation() > generation, "table 0 was evicted to make room");
        }
    }

    @Test
    void repeatingAnOperationThatCannotFitBothTablesDoesNotDecodeAgain() throws Exception {
        long[] hashes = {0x40000000L, 0x40000001L};
        var changes = new byte[2_000][];
        for (int i = 0; i < 1_000; i++) {
            changes[i] = entry(hashes[0], 1, i == 0 ? 7 : 10_000 + i, 1, number(i));
            changes[1_000 + i] = entry(hashes[1], 1, 20_000 + i, 1, number(7));
        }
        // Each view charges about 0.9 MiB; the same operation needs both, which never fit. The
        // second table's minimum charge fits beside the first, so only a full decode finds out.
        var memory = new HotfixMemory(1 << 20, 1_782_579);
        try (var tables =
                several(memory, new MemoryGeneratedStore(), true, cache(changes), hashes)) {
            var hot = (HotfixBuildTables) tables;
            var request = new TableTypes.ReferencesRequest(TableTypes.Ref.id(100), 7);
            tables.referencedBy(request, P, Cancellation.NONE).join();
            int afterFirst = hot.compiles;
            for (int i = 0; i < 5; i++) {
                tables.referencedBy(request, P, Cancellation.NONE).join();
            }
            assertEquals(afterFirst, hot.compiles, "the own-pins retry does not decode every time");
            assertEquals(
                    HotfixViewInfo.RETAINED_BUDGET,
                    tables.hotfixInfo(TableTypes.Ref.id(101), null, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .unavailable());
        }
    }

    @Test
    void refusalHoldsForTheWholeOperationEvenWhenItsCauseClearsMidway() throws Exception {
        long[] hashes = {0x30000000L, 0x30000001L};
        var changes =
                new byte[][] {
                    entry(hashes[0], 1, 7, 1, number(70)), entry(hashes[1], 1, 50, 1, number(7))
                };
        try (var tables =
                several(
                        new HotfixMemory(64L << 20, 256L << 20),
                        new MemoryGeneratedStore(),
                        true,
                        cache(changes),
                        hashes)) {
            var hot = (HotfixBuildTables) tables;
            // Each clock read lands an hour later, so no back-off delay is ever still running.
            var now = new java.util.concurrent.atomic.AtomicLong();
            hot.clock = () -> now.addAndGet(java.util.concurrent.TimeUnit.HOURS.toNanos(1));
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            hot.ordinalsProbe = found -> reads.getAndIncrement() == 0 ? null : found;
            // referencedBy reads the target twice (get, then open) and then its sources. The first
            // read of table 0 is refused; the base is readable again by the second read, but the
            // operation must stay on base rows for table 0 and never decode it.
            var refs =
                    tables.referencedBy(
                                    new TableTypes.ReferencesRequest(TableTypes.Ref.id(100), 7),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals("9", refs.row().row().row().values().get(1), "target row is the base row");
            assertEquals(2, reads.get(), "table 0 once (refused), table 1 once");
            assertEquals(1, hot.compiles, "only table 1 decoded in that operation");
            // The next operation is fresh and applies table 0's overlay.
            assertEquals(
                    "70",
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE)
                            .join()
                            .row()
                            .row()
                            .values()
                            .get(1));
        }
    }

    @Test
    void moreThanEightTablesInOneOperationDegradeForThatOperationOnly() throws Exception {
        long[] hashes = new long[11];
        var changes = new byte[hashes.length][];
        for (int i = 0; i < hashes.length; i++) {
            hashes[i] = 0x10000000L + i;
            // Every table changes ID 7; tables after the first point it at Numbers0 row 7.
            changes[i] = entry(hashes[i], 1, i == 0 ? 7 : 50 + i, 1, number(7));
        }
        try (var tables =
                several(
                        new HotfixMemory(64L << 20, 256L << 20),
                        new MemoryGeneratedStore(),
                        true,
                        cache(changes),
                        hashes)) {
            var refs =
                    tables.referencedBy(
                                    new TableTypes.ReferencesRequest(TableTypes.Ref.id(100), 7),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.ReferencesOutcome.SEARCHED, refs.outcome());
            int degraded = 0;
            for (int i = 1; i < hashes.length; i++) {
                var info =
                        tables.hotfixInfo(TableTypes.Ref.id(100 + i), null, P, Cancellation.NONE)
                                .join();
                if (info.isPresent()
                        && HotfixViewInfo.OPERATION_TABLES.equals(info.get().unavailable())) {
                    degraded++;
                }
            }
            assertTrue(degraded > 0, "tables past the eighth read base rows in that operation");
            // A later operation is a fresh start: the same table now gets its overlay.
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    tables.get(
                                    TableTypes.Ref.id(100 + hashes.length - 1),
                                    50 + hashes.length - 1,
                                    P,
                                    Cancellation.NONE)
                            .join()
                            .outcome());
        }
    }

    @Test
    void unreachableHandleReleasesItsSharedCharge() throws Exception {
        var memory = new HotfixMemory(1 << 20, 1 << 20);
        leak(memory);
        for (int i = 0; i < 200 && memory.reserved() != 0; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(0, memory.reserved(), "a never-closed handle frees its charge");
    }

    private static void leak(HotfixMemory memory) {
        var tables = numbers(0x11223344L, manyRows(100), false, memory);
        assertEquals(
                TableTypes.RowOutcome.FOUND,
                tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE).join().outcome());
        assertTrue(memory.reserved() > 0);
    }

    private static com.vibeminer.files.Files localInventory(MemoryFiles memory) {
        return new com.vibeminer.files.Files() {
            public com.vibeminer.files.names.FileNames names() {
                return memory.names();
            }

            public CompletableFuture<FileTypes.FilesOpenResult> forBuild(
                    com.vibeminer.builds.BuildTypes.BuildRef build,
                    String locale,
                    Cancellation token) {
                return memory.forBuild(build, locale, token)
                        .thenApply(
                                open -> {
                                    var base = open.files();
                                    var proxy =
                                            (com.vibeminer.files.BuildFiles)
                                                    java.lang.reflect.Proxy.newProxyInstance(
                                                            com.vibeminer.files.BuildFiles.class
                                                                    .getClassLoader(),
                                                            new Class<?>[] {
                                                                com.vibeminer.files.BuildFiles.class
                                                            },
                                                            (ignored, method, args) -> {
                                                                if (method.getName()
                                                                        .equals(
                                                                                "listContentInventory")) {
                                                                    var range =
                                                                            (FileTypes.FileIdRange)
                                                                                    args[0];
                                                                    var stat =
                                                                            base.stat(
                                                                                            FileTypes
                                                                                                    .FileRef
                                                                                                    .id(
                                                                                                            range
                                                                                                                    .fromId()),
                                                                                            (Cancellation)
                                                                                                    args[
                                                                                                            1])
                                                                                    .join();
                                                                    return new com.vibeminer.files
                                                                                    .AsyncCursor<
                                                                            FileTypes
                                                                                    .ContentFile>() {
                                                                        boolean ended;

                                                                        public CompletableFuture<
                                                                                        List<
                                                                                                FileTypes
                                                                                                        .ContentFile>>
                                                                                next(
                                                                                        int n,
                                                                                        Cancellation
                                                                                                cancel) {
                                                                            cancel.check();
                                                                            if (ended
                                                                                    || stat.file()
                                                                                            == null) {
                                                                                return CompletableFuture
                                                                                        .completedFuture(
                                                                                                List
                                                                                                        .of());
                                                                            }
                                                                            ended = true;
                                                                            return CompletableFuture
                                                                                    .completedFuture(
                                                                                            List.of(
                                                                                                    new FileTypes
                                                                                                            .ContentFile(
                                                                                                            range
                                                                                                                    .fromId(),
                                                                                                            stat.file()
                                                                                                                    .size(),
                                                                                                            stat.file()
                                                                                                                    .contentKey())));
                                                                        }

                                                                        public void close() {
                                                                            ended = true;
                                                                        }
                                                                    };
                                                                }
                                                                try {
                                                                    return method.invoke(
                                                                            base, args);
                                                                } catch (
                                                                        java.lang.reflect
                                                                                        .InvocationTargetException
                                                                                failure) {
                                                                    throw failure.getCause();
                                                                }
                                                            });
                                    return new FileTypes.FilesOpenResult(
                                            open.build(),
                                            open.locale(),
                                            open.state(),
                                            proxy,
                                            open.code(),
                                            open.message(),
                                            open.observedAt());
                                });
            }
        };
    }

    private static byte[] byteArrayTable(long hash, byte[] values) {
        int start = 272;
        var b = ByteBuffer.allocate(start + values.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'W', 'D', 'C', '5'}).putInt(5);
        b.putInt(136, 1)
                .putInt(140, 1)
                .putInt(144, values.length)
                .putInt(152, (int) hash)
                .putInt(156, 0x01020304);
        b.putShort(172, (short) 4).putInt(176, 1).putInt(188, 24).putInt(200, 1);
        b.position(204);
        b.putLong(0)
                .putInt(start)
                .putInt(1)
                .putInt(0)
                .putInt(0)
                .putInt(4)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        b.putShort((short) 24).putShort((short) 0);
        b.putShort((short) 0)
                .putShort((short) (values.length * 8))
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        b.put(values).putInt(7);
        return b.array();
    }

    @Test
    void existingKeyBootstrapConsumesActualEffectiveByteArraysJoinedById() {
        var files = new MemoryFiles();
        var definitionList = new java.util.ArrayList<BundledDefinitions.DefinitionTable>();
        var definitions = new java.util.HashMap<String, BundledDefinitions.Definition>();
        for (int n = 0; n < 2; n++) {
            long id =
                    n == 0
                            ? BuildTactKeys.TACT_KEY_FILE_DATA_ID
                            : BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID;
            long hash = n == 0 ? 0xDF2F53CFL : 0xAFC190D1L;
            String name = n == 0 ? "TactKey" : "TactKeyLookup", column = n == 0 ? "Key" : "TACTID";
            byte[] values = new byte[n == 0 ? 16 : 8];
            java.util.Arrays.fill(values, (byte) 1);
            var bytes = byteArrayTable(hash, values);
            files.add(
                    TablesContract.BUILD,
                    "enUS",
                    new FileTypes.BuildFile(
                            id,
                            "dbfilesclient/" + name + ".db2",
                            FileTypes.FilePathSource.LISTFILE,
                            (long) bytes.length,
                            n == 0 ? "1".repeat(32) : "2".repeat(32),
                            FileTypes.FileFormat.DB2,
                            FileTypes.FileFormatSource.EXTENSION,
                            FileTypes.FileStorage.STORED,
                            false),
                    bytes,
                    List.of());
            String hex = String.format("%08X", hash);
            definitionList.add(
                    new BundledDefinitions.DefinitionTable(hex, name, Long.toString(id), true));
            var document =
                    Db2Fixture.definition(
                            "int ID\nint " + column,
                            "$id,noninline$ID<u32>\n" + column + "<u8>[" + values.length + "]");
            definitions.put(
                    hex,
                    new BundledDefinitions.Definition(
                            n == 0 ? "a".repeat(64) : "b".repeat(64), "authored", document));
        }
        byte[] hotfixKey = new byte[16];
        java.util.Arrays.fill(hotfixKey, (byte) 2);
        var input = cache(entry(0xDF2F53CFL, -1, 7, 1, hotfixKey));
        var index =
                HotfixCacheReader.inspect(
                                input, 4, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        var options =
                new TablesOptions((b, t) -> CompletableFuture.completedFuture("1.2.3.4"))
                        .withLocalDefinitionsOnly()
                        .withHotfix(
                                new HotfixBinding(
                                        TablesContract.BUILD, "enUS", "1.2.3.4", input, index));
        var service =
                new TablesService(
                        files,
                        new TableDefinitions(
                                BundledDefinitions.of(definitionList, definitions), null, null),
                        new GeneratedTables(new MemoryGeneratedStore()),
                        options);
        try (var tables =
                service.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var keySchema =
                    tables.schema(
                                    TableTypes.Ref.id(BuildTactKeys.TACT_KEY_FILE_DATA_ID),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertTrue(
                    TableQueries.isReadable(keySchema.table().state()),
                    String.valueOf(keySchema.table().reason()));
            var keys = BuildTactKeys.load(tables, P, Cancellation.NONE).join();
            assertEquals(BuildTactKeys.Outcome.READ, keys.keyTable().outcome());
            assertEquals(BuildTactKeys.Outcome.READ, keys.lookupTable().outcome());
            assertEquals(1, keys.count());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    hotfixKey, keys.find(0x0101010101010101L));
        }
    }

    @Test
    void tableWithManyHotfixedRowsFitsTheDecodedViewBudget() {
        // A real client cache hotfixes thousands of wide item rows in one table, which charges
        // tens of MiB of decoded values. This narrow table charges just over 10 MiB.
        int count = 16_384;
        var changes = new byte[count][];
        for (int i = 0; i < count; i++) {
            changes[i] = entry(0x11223344L, 1, 1_000 + i, 1, number(i));
        }
        try (var tables = numbers(0x11223344L, changes)) {
            var row =
                    tables.get(TableTypes.Ref.id(100), 1_000 + count - 1, P, Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.RowOutcome.FOUND, row.outcome());
            assertEquals(String.valueOf(count - 1), row.row().row().values().get(1));
            var view =
                    tables.hotfixInfo(TableTypes.Ref.id(100), null, P, Cancellation.NONE)
                            .join()
                            .orElseThrow();
            assertEquals(count, view.added());
            assertEquals(2 + count, view.effectiveRows());
        }
    }

    private static Db2ByteSource manyRows(int count) {
        var changes = new byte[count][];
        for (int i = 0; i < count; i++) {
            changes[i] = entry(0x11223344L, 1, 1_000 + i, 1, number(i));
        }
        return cache(changes);
    }

    @Test
    void oversizedTableReadsBaseRowsWithAVisibleReasonInsteadOfFailing() {
        var memory = new HotfixMemory(1 << 20, 1 << 20);
        try (var tables = numbers(0x11223344L, manyRows(4_096), false, memory)) {
            var base = tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join();
            assertEquals(TableTypes.RowOutcome.FOUND, base.outcome());
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    tables.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome());
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PageState.OK, page.state());
            assertEquals(2, page.rows().size());
            var info =
                    tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow();
            assertEquals(HotfixViewInfo.TABLE_BUDGET, info.unavailable());
            assertEquals(HotfixViewInfo.Origin.BASE, info.row().origin());
            assertEquals(HotfixViewInfo.TABLE_BUDGET, info.row().reason());
            assertEquals(0, memory.reserved());
        }
    }

    @Test
    void handlesShareOneOverlayBudgetAndRetryOnceItIsReleased() {
        // Each view charges about 0.9 MiB; two do not fit a 1.5 MiB shared ceiling.
        var memory = new HotfixMemory(1 << 20, 3 << 19);
        var first = numbers(0x11223344L, manyRows(1_000), false, memory);
        try (var second = numbers(0x11223344L, manyRows(1_000), false, memory)) {
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    first.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome());
            assertTrue(memory.reserved() > 0);
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    second.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome());
            assertEquals(
                    HotfixViewInfo.RETAINED_BUDGET,
                    second.hotfixInfo(TableTypes.Ref.id(100), null, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .unavailable());
            first.close();
            assertEquals(0, memory.reserved());
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    second.get(TableTypes.Ref.id(100), 1_000, P, Cancellation.NONE)
                            .join()
                            .outcome());
        } finally {
            first.close();
        }
        assertEquals(0, memory.reserved());
    }

    @Test
    void bulkBaseOrdinalsMatchOneByOneGets() throws Exception {
        try (var tables = numbers(0x11223344L, entry(0x11223344L, 1, 7, 1, number(99)))) {
            var field = HotfixBuildTables.class.getDeclaredField("base");
            field.setAccessible(true);
            var base = (ClientBuildTables) field.get(tables);
            var ids = List.of(7L, 8L, 9L, 4_000_000_000L);
            var bulk = base.foundOrdinals(TableTypes.Ref.id(100), ids, P, Cancellation.NONE).join();
            int found = 0;
            for (long id : ids) {
                var row = base.get(TableTypes.Ref.id(100), id, P, Cancellation.NONE).join();
                if (row.outcome() == TableTypes.RowOutcome.FOUND) {
                    found++;
                    assertEquals(row.row().ordinal(), bulk.get(id), "ordinal of " + id);
                } else {
                    assertFalse(bulk.containsKey(id), "not found " + id);
                }
            }
            assertEquals(found, bulk.size());
            assertTrue(found > 0, "fixture has base rows");
        }
    }

    private static BuildTables rawByte(long baseValue, byte[]... changes) {
        return rawByte(baseValue, 200_000, changes);
    }

    private static BuildTables rawByte(long baseValue, int maxScanRows, byte[]... changes) {
        // An unverified provider name ("?") stored in 32 physical bits but declared u8: base rows
        // keep the physical integer and the header carries a raw-physical interpretation.
        return numbers(
                0x11223344L,
                cache(changes),
                new HotfixMemory(64L << 20, 256L << 20),
                baseValue,
                "int ID\nint Value?",
                "$id,noninline$ID<u32>\nValue<u8>",
                maxScanRows);
    }

    private static TableTypes.RowOutcome rawRow(BuildTables tables) {
        return tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome();
    }

    @Test
    void shortScanAndChangedBaseAreInconclusiveAndRefused() {
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            // The base reports two rows; a scan that sees only one checked nothing conclusive.
            hot.rawScanProbe =
                    page ->
                            new TableTypes.Page(
                                    page.table(),
                                    page.state(),
                                    page.rows().subList(0, page.rows().size() - 1),
                                    null,
                                    page.identity(),
                                    page.scanned(),
                                    page.code(),
                                    page.message());
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "short scan");
        }
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            hot.rawScanIdentityProbe = identity -> identity + "-changed";
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "base changed during scan");
        }
    }

    @Test
    void failedScanIsNotCachedAndIsRetriedAfterABackOff() {
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            var now = new java.util.concurrent.atomic.AtomicLong(1);
            hot.clock = now::get;
            hot.rawScanProbe =
                    page -> {
                        throw new IllegalStateException("authored damaged base");
                    };
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables));
            assertEquals(1, hot.fitScans);
            hot.rawScanProbe = page -> page;
            for (int i = 0; i < 3; i++) {
                assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "backing off");
            }
            assertEquals(1, hot.fitScans, "no re-scan during the back-off");
            now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
            // The view is rebuilt and the scan retried: the failure was never cached.
            assertEquals(
                    TableTypes.RowOutcome.FOUND, rawRow(tables), "re-scanned after the back-off");
            assertEquals(2, hot.fitScans);
            assertEquals(TableTypes.RowOutcome.FOUND, rawRow(tables));
            assertEquals(2, hot.fitScans, "a completed scan is not repeated");
        }
    }

    @Test
    void completedScanSurvivesViewEviction() throws Exception {
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            assertEquals(TableTypes.RowOutcome.FOUND, rawRow(tables));
            assertEquals(1, hot.fitScans);
            // Evict the compiled view the way a full cache would.
            var viewsField = HotfixBuildTables.class.getDeclaredField("views");
            viewsField.setAccessible(true);
            var chargeField = HotfixBuildTables.class.getDeclaredField("charge");
            chargeField.setAccessible(true);
            var views = (java.util.Map<?, ?>) viewsField.get(hot);
            var view = views.remove(100L);
            var bytesField = view.getClass().getDeclaredField("bytes");
            bytesField.setAccessible(true);
            var release = chargeField.get(hot).getClass().getDeclaredMethod("release", long.class);
            release.setAccessible(true);
            release.invoke(chargeField.get(hot), bytesField.getLong(view));
            assertEquals(TableTypes.RowOutcome.FOUND, rawRow(tables), "rebuilt after eviction");
            assertEquals(1, hot.fitScans, "the fit result for this base was reused");
        }
    }

    @Test
    void cancellationDuringTheScanIsNotTakenForAnUnreadableBase() {
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            hot.rawScanProbe =
                    page -> {
                        // Wrapped, as an awaited future delivers it.
                        throw new java.util.concurrent.CompletionException(
                                new java.util.concurrent.CancellationException("authored"));
                    };
            var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            RuntimeException.class, () -> rawRow(tables));
            Throwable cause = failure;
            while (cause.getCause() != null
                    && !(cause instanceof java.util.concurrent.CancellationException)) {
                cause = cause.getCause();
            }
            assertTrue(
                    cause instanceof java.util.concurrent.CancellationException,
                    "the cancellation propagates: " + failure);
        }
    }

    @Test
    void rawColumnFitIsRefusedWhenTheBaseCannotBeScannedCompletely() {
        // Two base rows (7 and its copy 8) exceed a one-row scan bound.
        try (var tables = rawByte(9, 1, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "over the scan bound");
        }
        // A page that is not OK proves nothing.
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            hot.rawScanProbe =
                    page ->
                            new TableTypes.Page(
                                    page.table(),
                                    TableTypes.PageState.NOT_READABLE,
                                    List.of(),
                                    null,
                                    page.identity(),
                                    0,
                                    "AUTHORED",
                                    null);
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "non-OK page");
        }
        // A PARTIAL base returns OK pages with only its readable rows; hidden rows were never
        // checked, so the column is refused even though every visible value fits.
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            hot.rawScanProbe =
                    page -> {
                        var t = page.table();
                        var partial =
                                new TableTypes.Entry(
                                        t.fileDataId(),
                                        t.name(),
                                        TableTypes.State.PARTIAL,
                                        t.checked(),
                                        t.reason(),
                                        t.counts(),
                                        t.contentKey());
                        return new TableTypes.Page(
                                partial,
                                page.state(),
                                page.rows(),
                                page.nextAfter(),
                                page.identity(),
                                page.scanned(),
                                page.code(),
                                page.message());
                    };
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "PARTIAL base");
        }
        // A failing (damaged) source refuses the column without failing the view.
        try (var tables = rawByte(9, entry(0x11223344L, 1, 7, 1, u8(255)))) {
            var hot = (HotfixBuildTables) tables;
            hot.rawScanProbe =
                    page -> {
                        throw new IllegalStateException("authored damaged base");
                    };
            assertEquals(TableTypes.RowOutcome.UNKNOWN, rawRow(tables), "failing scan");
        }
    }

    private static byte[] u8(int value) {
        return new byte[] {(byte) value};
    }

    @Test
    void rawUnsignedByteColumnDecodesWhenEveryBaseValueFitsItsDeclaredWidth() {
        try (var tables =
                rawByte(
                        9,
                        entry(0x11223344L, 1, 7, 1, u8(255)),
                        entry(0x11223344L, 1, 20, 1, u8(0)))) {
            var schema = tables.schema(TableTypes.Ref.id(100), P, Cancellation.NONE).join();
            assertTrue(
                    schema.columns().get(1).interpretation() != null,
                    "fixture column is raw-physical in base rows");
            var replaced = tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join();
            assertEquals(TableTypes.RowOutcome.FOUND, replaced.outcome());
            assertEquals("255", replaced.row().row().values().get(1));
            var added = tables.get(TableTypes.Ref.id(100), 20, P, Cancellation.NONE).join();
            assertEquals("0", added.row().row().values().get(1));
            assertEquals(
                    HotfixViewInfo.Origin.HOTFIX,
                    tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .row()
                            .origin());
        }
    }

    @Test
    void rawColumnWhoseBaseValuesExceedTheDeclaredWidthStaysRefused() {
        // Like a float stored where the definition declares a small integer: base rows print a
        // value the declared width cannot hold, so a hotfix decode could not match their text.
        try (var tables = rawByte(1_050_214_400L, entry(0x11223344L, 1, 7, 1, u8(3)))) {
            assertEquals(
                    TableTypes.RowOutcome.UNKNOWN,
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome());
            assertEquals(
                    HotfixViewInfo.Origin.UNRESOLVED,
                    tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .row()
                            .origin());
        }
        try (var tables = rawByte(256, entry(0x11223344L, 1, 7, 1, u8(3)))) {
            assertEquals(
                    TableTypes.RowOutcome.UNKNOWN,
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome(),
                    "256 is one past the u8 range");
        }
    }

    @Test
    void keyBlockRowsAreShownAndTrailingBytesStayRefusedWithTheirReason() {
        var keyed = ByteBuffer.allocate(4 + 28).order(ByteOrder.LITTLE_ENDIAN);
        keyed.putInt(42).putInt(0xDF2F53CF);
        keyed.put(new byte[24]);
        var padded = ByteBuffer.allocate(4 + 3).order(ByteOrder.LITTLE_ENDIAN).putInt(43);
        try (var tables =
                numbers(
                        0x11223344L,
                        entry(0x11223344L, 1, 20, 1, keyed.array()),
                        entry(0x11223344L, 1, 21, 1, padded.array()))) {
            var shown = tables.get(TableTypes.Ref.id(100), 20, P, Cancellation.NONE).join();
            assertEquals(TableTypes.RowOutcome.FOUND, shown.outcome());
            assertEquals(List.of("20", "42"), shown.row().row().values(), "block stripped");
            assertEquals(
                    TableTypes.RowOutcome.UNKNOWN,
                    tables.get(TableTypes.Ref.id(100), 21, P, Cancellation.NONE).join().outcome());
            var keyRow =
                    tables.hotfixInfo(TableTypes.Ref.id(100), 20L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .row();
            assertEquals(HotfixViewInfo.Origin.HOTFIX, keyRow.origin());
            assertEquals("OPTIONAL_KEY_DATA", keyRow.reason());
            assertEquals(
                    "TRAILING_BYTES:3",
                    tables.hotfixInfo(TableTypes.Ref.id(100), 21L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .row()
                            .reason());
        }
    }

    @Test
    void replacementFilteringGetAndDenseCursorAgreeWithoutStaleBaseRows() {
        try (var tables =
                numbers(
                        0x11223344L,
                        entry(0x11223344L, 1, 7, 1, number(99)),
                        entry(0x11223344L, 1, 9, 1, number(42)))) {
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(List.of(8L, 7L, 9L), page.rows().stream().map(r -> r.row().id()).toList());
            assertEquals(
                    List.of(0L, 1L, 2L),
                    page.rows().stream().map(TableTypes.Row::ordinal).toList());
            assertEquals(
                    "99",
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE)
                            .join()
                            .row()
                            .row()
                            .values()
                            .get(1));
            assertEquals(
                    0,
                    tables.get(TableTypes.Ref.id(100), 8, P, Cancellation.NONE)
                            .join()
                            .row()
                            .ordinal());
            var filter = new TableTypes.Filter("Value", TableTypes.FilterOp.EQUALS, "9", null);
            var filtered =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(100),
                                            -1,
                                            50,
                                            List.of(filter),
                                            page.identity()),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(List.of(8L), filtered.rows().stream().map(r -> r.row().id()).toList());
            var info =
                    tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow();
            assertEquals(2, info.baseReadableRows());
            assertEquals(3, info.effectiveRows());
            assertEquals(1, info.replaced());
            assertEquals(HotfixViewInfo.Origin.HOTFIX, info.row().origin());
            assertEquals(2, page.table().counts().readableRows());
        }
    }

    @Test
    void deletedProtectedAndUnsupportedSelectedIdsNeverExposeOldValues() {
        try (var tables =
                numbers(
                        0x11223344L,
                        entry(0x11223344L, 1, 7, 4, new byte[0]),
                        entry(0x11223344L, 1, 8, 2, new byte[0]),
                        entry(0x11223344L, 1, 9, 1, new byte[] {1}))) {
            assertEquals(
                    TableTypes.RowOutcome.PROTECTED,
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome());
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    tables.get(TableTypes.Ref.id(100), 8, P, Cancellation.NONE).join().outcome());
            assertEquals(
                    TableTypes.RowOutcome.UNKNOWN,
                    tables.get(TableTypes.Ref.id(100), 9, P, Cancellation.NONE).join().outcome());
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertTrue(page.rows().isEmpty());
            assertEquals(TableTypes.State.PARTIAL, page.table().state());
            assertEquals(2, page.table().counts().readableRows());
        }
    }

    @Test
    void narrowCachedExceptionIsSameIdAndDoesNotSuppressPositivePushOrNotPublic() {
        for (long hash : new long[] {0xDF2F53CFL, 0x021826BBL, 0xAFC190D1L}) {
            try (var tables =
                    numbers(
                            hash,
                            entry(hash, -1, 7, 1, number(11)),
                            entry(hash, -1, 7, 3, new byte[0]),
                            entry(hash, -1, 8, 3, new byte[0]))) {
                assertEquals(
                        hash == 0xAFC190D1L
                                ? TableTypes.RowOutcome.UNKNOWN
                                : TableTypes.RowOutcome.FOUND,
                        tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE)
                                .join()
                                .outcome());
                assertEquals(
                        TableTypes.RowOutcome.UNKNOWN,
                        tables.get(TableTypes.Ref.id(100), 8, P, Cancellation.NONE)
                                .join()
                                .outcome());
            }
        }
        try (var tables =
                numbers(
                        0xDF2F53CFL,
                        entry(0xDF2F53CFL, -1, 7, 1, number(11)),
                        entry(0xDF2F53CFL, 1, 7, 2, new byte[0]),
                        entry(0xDF2F53CFL, -1, 8, 1, number(11)),
                        entry(0xDF2F53CFL, -1, 8, 4, new byte[0]))) {
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome());
            assertEquals(
                    TableTypes.RowOutcome.PROTECTED,
                    tables.get(TableTypes.Ref.id(100), 8, P, Cancellation.NONE).join().outcome());
        }
    }

    @Test
    void stablePushThenPhysicalOrderAndSnapshotIdentityInvalidateCursor() {
        try (var a =
                        numbers(
                                0x11223344L,
                                entry(0x11223344L, 2, 7, 1, number(12)),
                                entry(0x11223344L, -1, 7, 1, number(10)),
                                entry(0x11223344L, 2, 7, 1, number(13)));
                var b = numbers(0x11223344L, entry(0x11223344L, 2, 7, 1, number(14)))) {
            var p =
                    a.page(new TableTypes.PageRequest(TableTypes.Ref.id(100)), P, Cancellation.NONE)
                            .join();
            assertEquals(
                    "13",
                    a.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE)
                            .join()
                            .row()
                            .row()
                            .values()
                            .get(1));
            assertEquals(
                    TableTypes.PageState.STALE,
                    b.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(100),
                                            -1,
                                            50,
                                            List.of(),
                                            p.identity()),
                                    P,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @Test
    void ordinaryRelationsUseTheSameEffectiveRowsAsGetAndPage() {
        try (var tables =
                numbers(
                        0x11223344L,
                        cache(
                                entry(0x11223344L, 1, 7, 1, number(9)),
                                entry(0x11223344L, 1, 9, 1, number(7))),
                        true)) {
            var follow =
                    tables.follow(
                                    new TableTypes.FollowRequest(
                                            TableTypes.Ref.id(100), 7, "Value"),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.FollowOutcome.FOUND, follow.outcome());
            assertEquals(9, follow.rows().getFirst().row().id());
            var references =
                    tables.referencedBy(
                                    new TableTypes.ReferencesRequest(TableTypes.Ref.id(100), 9),
                                    P,
                                    Cancellation.NONE)
                            .join();
            var ids =
                    references.sources().stream()
                            .flatMap(r -> r.rowIds().stream())
                            .collect(java.util.stream.Collectors.toSet());
            assertEquals(java.util.Set.of(7L, 8L), ids);
        }
    }

    @Test
    void rawNativeCaptureStaysBaseWhileOrdinaryValuesAreOverlaid() {
        try (var tables = numbers(0x11223344L, entry(0x11223344L, 1, 7, 1, number(99)))) {
            var old =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            var prepared =
                    tables.prepareNativeEvidence(
                                    TableTypes.Ref.id(100),
                                    P,
                                    NativePreparationTypes.SourceAccess.CONFIGURED_SOURCE,
                                    p -> {},
                                    Cancellation.NONE)
                            .join();
            assertEquals(NativePreparationTypes.State.READY, prepared.state());
            var capture =
                    tables.capturePreparedLocal(TableTypes.Ref.id(100), Cancellation.NONE).join();
            assertEquals(
                    CapturedRowsTypes.CaptureState.CAPTURED,
                    capture.state(),
                    capture.code() + ":" + capture.message());
            try (var rows = capture.rows()) {
                var page = rows.readOrdinalPage(0, 1, Cancellation.NONE).join();
                assertEquals("9", page.rows().getFirst().row().values().get(1));
                assertEquals("1".repeat(32), rows.receipt().table().contentKey());
            }
            assertEquals(
                    "99",
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE)
                            .join()
                            .row()
                            .row()
                            .values()
                            .get(1));
            var after =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PageState.OK, after.state());
            assertEquals(
                    "99",
                    after.rows().stream()
                            .filter(r -> r.row().id() == 7)
                            .findFirst()
                            .orElseThrow()
                            .row()
                            .values()
                            .get(1));
            assertEquals(old.table().contentKey(), after.table().contentKey());
        }
    }

    @Test
    void tableListStateFilterRunsAfterOverlayAndCompletionMayCloseView() throws Exception {
        var tables = numbers(0x11223344L, entry(0x11223344L, 1, 7, 4, new byte[0]));
        try {
            try (var cursor =
                    tables.list(
                            new TableTypes.ListFilter(
                                    null, java.util.Set.of(TableTypes.State.PARTIAL)),
                            P,
                            Cancellation.NONE)) {
                var page =
                        cursor.next(10, Cancellation.NONE)
                                .get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, page.size());
                assertEquals(TableTypes.State.PARTIAL, page.getFirst().state());
            }
            tables.page(new TableTypes.PageRequest(TableTypes.Ref.id(100)), P, Cancellation.NONE)
                    .thenRun(tables::close)
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            tables.closeAsync().get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static final class HeldSource implements Db2ByteSource {
        final Db2ByteSource source;
        final java.util.concurrent.CompletableFuture<Void>
                entered = new java.util.concurrent.CompletableFuture<>(),
                canceled = new java.util.concurrent.CompletableFuture<>(),
                releaseCancel = new java.util.concurrent.CompletableFuture<>();
        final java.util.concurrent.CompletableFuture<Void> readDone =
                new java.util.concurrent.CompletableFuture<>();
        volatile boolean armed;
        Runnable release;

        HeldSource(Db2ByteSource source) {
            this.source = source;
        }

        public long length() {
            return source.length();
        }

        public List<com.vibeminer.formats.db2.Db2Types.Hole> holes() {
            return List.of();
        }

        public java.util.concurrent.CompletableFuture<Void> readRange(
                long offset, ByteBuffer target, Cancellation token) {
            if (!armed || offset < 76) {
                return source.readRange(offset, target, token);
            }
            armed = false;
            token.onCancel(
                    () -> {
                        canceled.complete(null);
                        releaseCancel.join();
                    });
            release =
                    () -> {
                        source.readRange(offset, target, Cancellation.NONE).join();
                        readDone.complete(null);
                    };
            entered.complete(null);
            return readDone;
        }
    }

    @Test
    void closeReturnsPromptlyAndWaitsActualCacheReadAndCancellationCallback() throws Exception {
        var source = new HeldSource(cache(entry(0x11223344L, 1, 7, 1, number(99))));
        var tables = numbers(0x11223344L, source, false);
        source.armed = true;
        assertTrue(
                tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                        .get(3, java.util.concurrent.TimeUnit.SECONDS)
                        .isEmpty());
        assertFalse(source.entered.isDone());
        var read = tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE);
        try {
            source.entered.get(3, java.util.concurrent.TimeUnit.SECONDS);
            var closing = tables.closeAsync();
            source.canceled.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(closing.isDone());
            assertFalse(read.isDone());
            source.release.run();
            assertFalse(closing.isDone());
            source.releaseCancel.complete(null);
            closing.get(3, java.util.concurrent.TimeUnit.SECONDS);
            // Delivery to the caller is asynchronous after the drain by design.
            read.handle((value, failure) -> null).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(read.isCompletedExceptionally());
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> tables.list(TableTypes.ListFilter.ALL, P, Cancellation.NONE));
        } finally {
            source.releaseCancel.complete(null);
            if (source.release != null && !source.readDone.isDone()) {
                source.release.run();
            }
            tables.closeAsync().get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void deleteOnlyInputStillRequiresTheLiteralInspectedSourceAndExactBuild() {
        var source = cache(entry(0x11223344L, 1, 7, 2, new byte[0]));
        var other = cache(entry(0x11223344L, 1, 8, 2, new byte[0]));
        assertEquals(source.length(), other.length());
        var index =
                HotfixCacheReader.inspect(
                                source, 4, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new HotfixBinding(TablesContract.BUILD, "enUS", "1.2.3.4", other, index));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new HotfixBinding(TablesContract.BUILD, "enUS", "1.2.3.5", source, index));
    }

    @Test
    void conflictingOpaqueRegionsForSameIdMaskUnknownWithoutGuessingCdnMapping() {
        var conflicting = entry(0x11223344L, 2, 7, 1, number(100));
        ByteBuffer.wrap(conflicting).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 71);
        var other = entry(0x11223344L, 3, 9, 1, number(42));
        ByteBuffer.wrap(other).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 71);
        try (var tables =
                numbers(0x11223344L, entry(0x11223344L, 1, 7, 1, number(99)), conflicting, other)) {
            assertEquals(
                    TableTypes.RowOutcome.UNKNOWN,
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome());
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    tables.get(TableTypes.Ref.id(100), 9, P, Cancellation.NONE).join().outcome());
            var info =
                    tables.hotfixInfo(TableTypes.Ref.id(100), 7L, P, Cancellation.NONE)
                            .join()
                            .orElseThrow();
            assertEquals("REGION_CONFLICT", info.row().reason());
            org.junit.jupiter.api.Assertions.assertNull(info.row().region());
            assertEquals(1, info.unknownRows());
        }
    }
}
