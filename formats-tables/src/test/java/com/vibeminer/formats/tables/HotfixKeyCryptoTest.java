package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.files.FileFixtures;
import com.vibeminer.files.FileSystemOptions;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.FilesScenario;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.formats.hotfix.HotfixCacheReader;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Synthetic keys only: actual Salsa20 BLTE and WDC protected-section decoding, not a key-map mock.
 */
@Timeout(30)
class HotfixKeyCryptoTest {
    private static final long NAME = 123;
    private static final TableTypes.Priority P = TableTypes.Priority.BACKGROUND;

    private static byte[] arrayTable(long hash, byte[] values) {
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
        b.putShort((short) 24)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) (values.length * 8))
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        return b.put(values).putInt(7).array();
    }

    private static HotfixBinding binding(byte[] syntheticKey) {
        var b = ByteBuffer.allocate(44 + 32 + 16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x48544658).putInt(9).putInt(4).put(new byte[32]);
        b.putInt(0x48544658)
                .putInt(70)
                .putInt(-1)
                .putInt(1)
                .putInt(0xDF2F53CF)
                .putInt(7)
                .putInt(16)
                .put((byte) 1)
                .put(new byte[3])
                .put(syntheticKey);
        var source = Db2ByteSource.memory(b.array());
        var index =
                HotfixCacheReader.inspect(
                                source, 4, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        return new HotfixBinding(FilesScenario.REF, "enUS", "1.2.3.4", source, index);
    }

    private static BuildTactKeys.KeySet bootstrap(byte[] syntheticKey, boolean withHotfix) {
        var files = new MemoryFiles();
        var manifest = new ArrayList<BundledDefinitions.DefinitionTable>();
        var defs = new HashMap<String, BundledDefinitions.Definition>();
        for (int n = 0; n < 2; n++) {
            long id =
                    n == 0
                            ? BuildTactKeys.TACT_KEY_FILE_DATA_ID
                            : BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID;
            long hash = n == 0 ? 0xDF2F53CFL : 0xAFC190D1L;
            byte[] values =
                    n == 0
                            ? new byte[16]
                            : ByteBuffer.allocate(8)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putLong(NAME)
                                    .array();
            var bytes = arrayTable(hash, values);
            String table = n == 0 ? "TactKey" : "TactKeyLookup",
                    field = n == 0 ? "Key" : "TACTID",
                    hex = String.format("%08X", hash);
            files.add(
                    FilesScenario.REF,
                    "enUS",
                    new FileTypes.BuildFile(
                            id,
                            "dbfilesclient/" + table + ".db2",
                            FileTypes.FilePathSource.LISTFILE,
                            (long) bytes.length,
                            n == 0 ? "1".repeat(32) : "2".repeat(32),
                            FileTypes.FileFormat.DB2,
                            FileTypes.FileFormatSource.EXTENSION,
                            FileTypes.FileStorage.STORED,
                            false),
                    bytes,
                    List.of());
            manifest.add(
                    new BundledDefinitions.DefinitionTable(hex, table, Long.toString(id), true));
            defs.put(
                    hex,
                    new BundledDefinitions.Definition(
                            n == 0 ? "a".repeat(64) : "b".repeat(64),
                            "authored",
                            Db2Fixture.definition(
                                    "int ID\nint " + field,
                                    "$id,noninline$ID<u32>\n"
                                            + field
                                            + "<u8>["
                                            + values.length
                                            + "]")));
        }
        var options =
                new TablesOptions((b, t) -> CompletableFuture.completedFuture("1.2.3.4"))
                        .withLocalDefinitionsOnly();
        if (withHotfix) {
            options = options.withHotfix(binding(syntheticKey));
        }
        var tables =
                new TablesService(
                                files,
                                new TableDefinitions(
                                        BundledDefinitions.of(manifest, defs), null, null),
                                new GeneratedTables(new MemoryGeneratedStore()),
                                options)
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        try (tables) {
            return BuildTactKeys.load(tables, P, Cancellation.NONE).join();
        }
    }

    private record ProtectedTable(byte[] bytes, int bodyStart) {}

    private static ProtectedTable protectedTable() {
        byte[] original = Db2Fixture.numeric("WDC5", 0, 32, 555, new long[0], false);
        int old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(212),
                start = old + 8;
        var b = ByteBuffer.allocate(original.length + 8).order(ByteOrder.LITTLE_ENDIAN);
        b.put(original, 0, old).putInt(1).putInt(7).put(original, old, original.length - old);
        b.putLong(204, NAME).putInt(212, start);
        return new ProtectedTable(b.array(), start);
    }

    private static FilesScenario scenario(
            ProtectedTable table, byte[] syntheticKey, com.vibeminer.files.TactKeySource supplied) {
        var defaults = FileSystemOptions.defaults();
        var options =
                new FileSystemOptions(
                        defaults.presenceBatch(),
                        defaults.progressInterval(),
                        defaults.clock(),
                        defaults.maxReadBytes(),
                        supplied,
                        defaults.names());
        var result = new FilesScenario(false, options);
        var encoded =
                FileFixtures.chunked(
                        List.of(
                                FileFixtures.raw(Arrays.copyOf(table.bytes(), table.bodyStart())),
                                FileFixtures.encrypted(
                                        NAME,
                                        syntheticKey,
                                        FileFixtures.zlib(
                                                Arrays.copyOfRange(
                                                        table.bytes(),
                                                        table.bodyStart(),
                                                        table.bytes().length)),
                                        1)));
        result.variants(
                100,
                List.of(
                        new BuildTypes.FileVariant(
                                2,
                                0x8000000,
                                new CdnKey(FileFixtures.md5(table.bytes())),
                                (long) table.bytes().length,
                                encoded.key(),
                                (long) encoded.bytes().length,
                                null)));
        result.objects.put(
                new ObjectKey(ObjectKey.ENCODED, encoded.key().bytes()), encoded.bytes());
        return result;
    }

    private static TableDefinitions definitions() {
        var doc = Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        return new TableDefinitions(
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        "11223344", "Numbers", "100", true)),
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "authored", doc))),
                null,
                null);
    }

    @Test
    void actualHotfixOnlyKeyUnlocksSalsaBlteAndNativeSectionAndInvalidatesPartialGraph() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        var before = bootstrap(key, false);
        var unlocked = bootstrap(key, true);
        assertEquals(0, before.count());
        assertEquals(1, unlocked.count());
        assertArrayEquals(key, unlocked.keys().find(NAME));
        var encrypted = protectedTable();
        var noKey = scenario(encrypted, key, before.keys());
        var hasKey = scenario(encrypted, key, unlocked.keys());
        var generated = new GeneratedTables(new MemoryGeneratedStore());
        var missingOptions =
                new TablesOptions((b, t) -> CompletableFuture.completedFuture("1.2.3.4"));
        var base = missingOptions;
        var unlockedOptions =
                new TablesOptions(
                        base.buildVersion(),
                        base.limits(),
                        b -> unlocked.status(),
                        base.scheduler(),
                        base.maxScanRows(),
                        base.maxReferenceSources(),
                        base.maxReferenceScanRows());
        String partialIdentity;
        try (var tables =
                new TablesService(noKey.files, definitions(), generated, missingOptions)
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.State.ENCRYPTED, page.table().state());
            assertTrue(page.rows().isEmpty());
            assertEquals(
                    TableTypes.RowOutcome.PROTECTED,
                    tables.get(TableTypes.Ref.id(100), 7, P, Cancellation.NONE).join().outcome());
            partialIdentity = page.identity();
        }
        String completeIdentity;
        try (var tables =
                new TablesService(hasKey.files, definitions(), generated, unlockedOptions)
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.State.READY, page.table().state());
            assertEquals("555", page.rows().getFirst().row().values().get(1));
            completeIdentity = page.identity();
            assertNotEquals(partialIdentity, completeIdentity);
        }
        var repeated = scenario(encrypted, key, unlocked.keys());
        try (var tables =
                new TablesService(repeated.files, definitions(), generated, unlockedOptions)
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(completeIdentity, page.identity());
            assertEquals(1, page.rows().size());
        }
    }
}
