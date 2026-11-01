package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.builds.formats.ArchiveIndex;
import com.vibeminer.builds.formats.ArchiveLocations;
import com.vibeminer.builds.formats.Blte;
import com.vibeminer.builds.formats.ConfigFiles;
import com.vibeminer.builds.formats.EncodingTable;
import com.vibeminer.builds.formats.RootNameHash;
import com.vibeminer.builds.formats.RootTable;
import com.vibeminer.builds.formats.TactConfig;
import com.vibeminer.network.CdnKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class MetadataParserTest {
    private static final Runnable NONE = () -> {};

    private static void code(String code, Runnable work) {
        assertEquals(code, assertThrows(BuildFormatException.class, work::run).code());
    }

    @Test
    void configGrammarPreservesUnknownFieldsAndEmptyValues() {
        var config =
                TactConfig.parse(
                        MetadataFixtures.text(
                                "# ignored\r\nroot = x\nempty = \nunknown-field = a\tb c\n"));
        assertEquals(List.of(), config.fields().get("empty"));
        assertEquals(List.of("a", "b", "c"), config.fields().get("unknown-field"));
        assertThrows(
                UnsupportedOperationException.class, () -> config.fields().get("root").add("y"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "root x",
                "root = x = y",
                "Root = x",
                "-root = x",
                "root = x\nroot = y",
                "root = x\rbroken",
                "root = x\u0000",
                "\ufeffroot = x"
            })
    void malformedConfigGrammarIsRefused(String text) {
        code("CONFIG_INVALID", () -> TactConfig.parse(MetadataFixtures.text(text)));
    }

    @Test
    void configBoundsAndUtf8FailuresHaveStableCodes() {
        code("CONFIG_LIMIT", () -> TactConfig.parse(new byte[(1 << 20) + 1]));
        code("CONFIG_INVALID", () -> TactConfig.parse(new byte[] {(byte) 0xc0, (byte) 0xaf}));
        code("CONFIG_LIMIT", () -> TactConfig.parse(MetadataFixtures.text("#\n".repeat(16385))));
    }

    @Test
    void configDeclarationsRejectZeroKeysMissingSizesAndVfsRoots() {
        String valid =
                "root = "
                        + MetadataFixtures.key(1).hex()
                        + "\ninstall = "
                        + MetadataFixtures.key(2).hex()
                        + "\nencoding = "
                        + MetadataFixtures.key(3).hex()
                        + " "
                        + MetadataFixtures.key(4).hex()
                        + "\nencoding-size = 100 50\n";
        assertEquals(
                100,
                ConfigFiles.build(MetadataFixtures.text(valid), NONE).encodingSize().decoded());
        code(
                "BUILD_CONFIG_INVALID",
                () ->
                        ConfigFiles.build(
                                MetadataFixtures.text(
                                        valid.replace("encoding-size = 100 50\n", "")),
                                NONE));
        code(
                "BUILD_CONFIG_INVALID",
                () ->
                        ConfigFiles.build(
                                MetadataFixtures.text(valid.replace("100 50", "0100 50")), NONE));
        code(
                "BUILD_CONFIG_UNSUPPORTED",
                () ->
                        ConfigFiles.build(
                                MetadataFixtures.text(valid + "root-size = 100 50\n"), NONE));
        code(
                "CDN_CONFIG_INVALID",
                () ->
                        ConfigFiles.cdn(
                                MetadataFixtures.text("archives = " + "0".repeat(32) + "\n"),
                                NONE));
        assertEquals(
                0, ConfigFiles.cdn(MetadataFixtures.text("archives = \n"), NONE).archives().size());
    }

    @Test
    void rawAndZlibBlteDecodeOnlyAfterBothIdentitiesMatch() {
        byte[] content = MetadataFixtures.text("bounded synthetic metadata");
        byte[] raw = MetadataFixtures.raw(content);
        CdnKey contentKey = new CdnKey(MetadataFixtures.md5(content));
        assertArrayEquals(
                content,
                Blte.decode(
                        raw,
                        new CdnKey(MetadataFixtures.md5(raw)),
                        contentKey,
                        (long) content.length,
                        1000,
                        NONE));
        var compressed = MetadataFixtures.chunked(content, 'Z');
        assertArrayEquals(
                content,
                Blte.decode(
                        compressed.encoded(),
                        compressed.encodedKey(),
                        contentKey,
                        (long) content.length,
                        1000,
                        NONE));
        code(
                "BLTE_INTEGRITY",
                () ->
                        Blte.decode(
                                raw,
                                MetadataFixtures.key(9),
                                contentKey,
                                (long) content.length,
                                1000,
                                NONE));
        code(
                "BLTE_INTEGRITY",
                () ->
                        Blte.decode(
                                raw,
                                new CdnKey(MetadataFixtures.md5(raw)),
                                MetadataFixtures.key(9),
                                (long) content.length,
                                1000,
                                NONE));
    }

    @Test
    void encryptedAndUnsupportedModesAreClaimedOnlyAfterAuthentication() {
        var encrypted = MetadataFixtures.chunked(MetadataFixtures.text("secret"), 'E');
        assertEquals(
                BuildFailure.ENCRYPTED,
                assertThrows(
                                BuildFormatException.class,
                                () ->
                                        Blte.decode(
                                                encrypted.encoded(),
                                                encrypted.encodedKey(),
                                                null,
                                                null,
                                                1000,
                                                NONE))
                        .failure());
        byte[] bad = encrypted.encoded().clone();
        bad[bad.length - 1] ^= 1;
        code(
                "BLTE_INTEGRITY",
                () -> Blte.decode(bad, encrypted.encodedKey(), null, null, 1000, NONE));
        var unsupported = MetadataFixtures.chunked(MetadataFixtures.text("nested"), 'F');
        code(
                "BLTE_UNSUPPORTED",
                () ->
                        Blte.decode(
                                unsupported.encoded(),
                                unsupported.encodedKey(),
                                null,
                                null,
                                1000,
                                NONE));
    }

    @Test
    void blteRejectsTruncationTrailingZlibAndDeclaredSizeOverflow() {
        var compressed = MetadataFixtures.chunked(new byte[2000], 'Z');
        code(
                "BLTE_LIMIT",
                () ->
                        Blte.decode(
                                compressed.encoded(),
                                compressed.encodedKey(),
                                null,
                                2000L,
                                100,
                                NONE));
        byte[] truncated = Arrays.copyOf(compressed.encoded(), compressed.encoded().length - 1);
        code(
                "BLTE_INVALID",
                () -> Blte.decode(truncated, compressed.encodedKey(), null, null, 10000, NONE));
        byte[] raw = MetadataFixtures.raw(new byte[] {1});
        raw[8] = 'Z';
        code(
                "BLTE_INVALID",
                () ->
                        Blte.decode(
                                raw,
                                new CdnKey(MetadataFixtures.md5(raw)),
                                null,
                                null,
                                1000,
                                NONE));
    }

    @Test
    void rootPreservesV2RawFlagsVariantsAndLocaleMemberships() {
        byte[] root =
                MetadataFixtures.root(
                        2,
                        List.of(
                                new MetadataFixtures.RootBlock(
                                        2,
                                        0,
                                        8,
                                        1,
                                        List.of(
                                                new MetadataFixtures.RootRow(
                                                        100, MetadataFixtures.key(1), "x/y"))),
                                new MetadataFixtures.RootBlock(
                                        32,
                                        0,
                                        0,
                                        0,
                                        List.of(
                                                new MetadataFixtures.RootRow(
                                                        100, MetadataFixtures.key(2), "x/y")))));
        RootTable table = RootTable.parse(root, new RootTable.Limits(), NONE);
        assertEquals(1, table.fileCount());
        assertEquals(2, table.entryCount());
        assertEquals(34, table.localeUnion());
        assertEquals(0x20008, table.find(100).getFirst().contentFlags());
        assertEquals(1, table.find(100).getFirst().raw().third());
        assertEquals(100L, table.findByNameHash(RootNameHash.of("X\\Y")));
    }

    @Test
    void rootRejectsTruncationUnsupportedVersionsAndUnsignedDeltaOverflow() {
        byte[] root =
                MetadataFixtures.root(
                        1,
                        List.of(
                                new MetadataFixtures.RootBlock(
                                        2,
                                        RootTable.NO_NAMES,
                                        0,
                                        0,
                                        List.of(
                                                new MetadataFixtures.RootRow(
                                                        1, MetadataFixtures.key(1), ""),
                                                new MetadataFixtures.RootRow(
                                                        2, MetadataFixtures.key(2), "")))));
        code(
                "ROOT_INVALID",
                () ->
                        RootTable.parse(
                                Arrays.copyOf(root, root.length - 1),
                                new RootTable.Limits(),
                                NONE));
        byte[] unsupported = root.clone();
        ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 3);
        code("ROOT_UNSUPPORTED", () -> RootTable.parse(unsupported, new RootTable.Limits(), NONE));
        byte[] overflow = root.clone();
        ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putInt(32, -1);
        code("ROOT_INVALID", () -> RootTable.parse(overflow, new RootTable.Limits(), NONE));
    }

    @Test
    void rootNameHashHasLookup3EmptyVectorAndPathNormalization() {
        assertEquals(0xdeadbeefdeadbeefL, RootNameHash.of(""));
        assertEquals(RootNameHash.of("WORLD\\MAPS\\X.WDT"), RootNameHash.of("world/maps/x.wdt"));
        assertNotEquals(RootNameHash.of("world/maps/x.wdt"), RootNameHash.of("world/maps/y.wdt"));
    }

    @Test
    void rootNameHashMatchesOriginalCsharpAsciiAndUnicodeVectors() {
        assertEquals(0x9d5b90c1ba105ca7L, RootNameHash.of("world/maps/x.wdt"));
        assertEquals(0xee34a0ce95399460L, RootNameHash.of("four score and seven years ago"));
        assertEquals(0x106ab3d0da2f94dbL, RootNameHash.of("straße/test"));
        assertEquals(0x0f319b2bae54d416L, RootNameHash.of("ıx"));
        assertEquals(0xe33c9ff5b2678feaL, RootNameHash.of("ß"));
        assertEquals(0xff0aa5a0ccb4dcceL, RootNameHash.of("é/x"));
    }

    @Test
    void encodingChecksEveryPageAndSupportsConcurrentIndependentLookups() {
        var rows =
                List.of(
                        new MetadataFixtures.EncodingRow(
                                MetadataFixtures.key(1), 10, MetadataFixtures.key(2), 20),
                        new MetadataFixtures.EncodingRow(
                                MetadataFixtures.key(3), 30, MetadataFixtures.key(4), 40));
        byte[] bytes = MetadataFixtures.encoding(rows);
        EncodingTable table = EncodingTable.open(bytes, new EncodingTable.Limits(), NONE);
        assertEquals(2, table.contentCount());
        assertEquals(2, table.encodedCount());
        assertEquals(30, table.findContent(MetadataFixtures.key(3)).decodedSize());
        assertEquals(40, table.findEncoded(MetadataFixtures.key(4)).encodedSize());
        assertNull(table.findContent(MetadataFixtures.key(8)));
        bytes[60] ^= 1;
        assertEquals(20, table.findEncoded(MetadataFixtures.key(2)).encodedSize());
        code("EN_INTEGRITY", () -> EncodingTable.open(bytes, new EncodingTable.Limits(), NONE));
    }

    @Test
    void encodingAndRootHeaderClaimsAreBoundedBeforeAllocation() {
        byte[] en =
                MetadataFixtures.encoding(
                        List.of(
                                new MetadataFixtures.EncodingRow(
                                        MetadataFixtures.key(1), 10, MetadataFixtures.key(2), 20)));
        ByteBuffer.wrap(en).order(ByteOrder.BIG_ENDIAN).putInt(9, -1);
        code("EN_LIMIT", () -> EncodingTable.open(en, new EncodingTable.Limits(), NONE));
        byte[] root = MetadataFixtures.root(1, List.of());
        ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1);
        code("ROOT_LIMIT", () -> RootTable.parse(root, new RootTable.Limits(), NONE));
    }

    @Test
    void archiveAndLooseIndexesVerifyAllProofsAndPagePadding() {
        var index =
                MetadataFixtures.index(
                        List.of(new MetadataFixtures.IndexRow(MetadataFixtures.key(1), 123, 42)),
                        false);
        var rows =
                ArchiveIndex.read(
                        index.bytes(),
                        index.key(),
                        ArchiveIndex.Kind.ARCHIVE,
                        new ArchiveIndex.Limits(),
                        NONE);
        assertEquals(42, rows.getFirst().offset());
        assertEquals(123, rows.getFirst().encodedSize());
        byte[] bad = index.bytes().clone();
        bad[0] ^= 1;
        code(
                "ARCHIVE_INDEX_INTEGRITY",
                () ->
                        ArchiveIndex.read(
                                bad,
                                index.key(),
                                ArchiveIndex.Kind.ARCHIVE,
                                new ArchiveIndex.Limits(),
                                NONE));
        var loose =
                MetadataFixtures.index(
                        List.of(new MetadataFixtures.IndexRow(MetadataFixtures.key(1), 123, 0)),
                        true);
        assertEquals(
                0,
                ArchiveIndex.read(
                                loose.bytes(),
                                loose.key(),
                                ArchiveIndex.Kind.FILE_INDEX,
                                new ArchiveIndex.Limits(),
                                NONE)
                        .getFirst()
                        .offset());
    }

    @Test
    void archiveLocationChoiceAndCrossIndexSizeConflictMatchReference() {
        var first = MetadataFixtures.key(10);
        var second = MetadataFixtures.key(11);
        var key = MetadataFixtures.key(1);
        var locations =
                ArchiveLocations.build(
                        List.of(
                                new ArchiveLocations.Indexed(
                                        first,
                                        List.of(
                                                new ArchiveIndex.Entry(key, 100, 50),
                                                new ArchiveIndex.Entry(key, 100, 40))),
                                new ArchiveLocations.Indexed(
                                        second, List.of(new ArchiveIndex.Entry(key, 100, 1)))),
                        List.of(new ArchiveIndex.Entry(key, 100, 0)),
                        new ArchiveLocations.Limits(),
                        NONE);
        assertEquals(first, locations.locate(key).location().archive());
        assertEquals(40, locations.locate(key).location().offset());
        assertEquals(100L, locations.looseSize(key));
        code(
                "ARCHIVE_INDEX_SIZE_CONFLICT",
                () ->
                        ArchiveLocations.build(
                                List.of(
                                        new ArchiveLocations.Indexed(
                                                first,
                                                List.of(new ArchiveIndex.Entry(key, 100, 50)))),
                                List.of(new ArchiveIndex.Entry(key, 101, 0)),
                                new ArchiveLocations.Limits(),
                                NONE));
    }

    @Test
    void cancellationIsCheckedBeforeParserWork() {
        Runnable canceled =
                () -> {
                    throw new CancellationException();
                };
        assertThrows(CancellationException.class, () -> TactConfig.parse(new byte[0], canceled));
        assertThrows(
                CancellationException.class,
                () -> RootTable.parse(new byte[0], new RootTable.Limits(), canceled));
        assertThrows(
                CancellationException.class,
                () -> EncodingTable.open(new byte[0], new EncodingTable.Limits(), canceled));
        assertThrows(
                CancellationException.class,
                () ->
                        ArchiveIndex.read(
                                new byte[0],
                                MetadataFixtures.key(1),
                                ArchiveIndex.Kind.ARCHIVE,
                                new ArchiveIndex.Limits(),
                                canceled));
    }
}
