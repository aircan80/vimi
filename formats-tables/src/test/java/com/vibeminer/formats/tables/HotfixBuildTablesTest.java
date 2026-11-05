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
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
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
        var document =
                Db2Fixture.definition(
                        relation ? "int ID\nint<Numbers::ID> Value" : "int ID\nint Value",
                        "$id,noninline$ID<u32>\nValue<u32>");
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
                new TablesOptions((b, t) -> CompletableFuture.completedFuture("1.2.3.4"))
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
                            Integer.toString(i + 1).repeat(32),
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
                                    "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>")));
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
                        localInventory(files),
                        definitions,
                        new GeneratedTables(new MemoryGeneratedStore()),
                        options)
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
