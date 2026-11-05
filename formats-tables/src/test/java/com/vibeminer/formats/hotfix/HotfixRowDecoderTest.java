package com.vibeminer.formats.hotfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HotfixRowDecoderTest {
    private static final long HASH = 0x11223344L;
    private static final HotfixRowDecoder.Limits LIMITS = new HotfixRowDecoder.Limits();

    private static HotfixRowDecoder.Layout layout(String columns, String fields) {
        var document = Db2Fixture.definition(columns, fields);
        var block =
                document.selectFile("1.2.3.4", "01020304", Cancellation.NONE).blocks().getFirst();
        var schema = TableSchema.fromBlock(block, TableSchema.SchemaMatch.LAYOUT, WowLocale.EN_US);
        var actual = new ArrayList<Db2Types.Column>();
        int id = -1, flags = 0;
        for (int i = 0; i < schema.columns().size(); i++) {
            var f = schema.columns().get(i);
            if (f.id() != TableSchema.KeyStorage.NONE) {
                id = i;
                if (f.id() == TableSchema.KeyStorage.NONINLINE) {
                    flags = 4;
                }
            }
            for (int n = 0; n < f.arrayLength(); n++) {
                actual.add(
                        new Db2Types.Column(
                                f.arrayLength() == 1 ? f.name() : f.name() + "[" + n + "]",
                                switch (f.kind()) {
                                    case INT -> "integer";
                                    case FLOAT -> "float";
                                    case STRING, LOCSTRING -> "string";
                                },
                                f.id() != TableSchema.KeyStorage.NONE,
                                null,
                                null));
            }
        }
        return new HotfixRowDecoder.Layout(
                schema,
                new Db2Types.Header(5, "11223344", "01020304", 1, 0, 8, actual, "WDC5"),
                flags,
                id);
    }

    private static HotfixRowDecoder.Decoded decode(
            HotfixRowDecoder.Layout layout, long id, ByteBuffer body) {
        return HotfixRowDecoder.decode(layout, HASH, id, body, LIMITS, Cancellation.NONE);
    }

    private static void refused(
            HotfixRowDecoder.Reason reason,
            HotfixRowDecoder.Layout layout,
            long id,
            ByteBuffer body) {
        assertEquals(
                reason,
                assertThrows(HotfixRowDecoder.Invalid.class, () -> decode(layout, id, body))
                        .reason());
    }

    @Test
    void noninlineIdAndEveryPrimitiveSignedWidthUseLittleEndianNaturalStorage() {
        var layout =
                layout(
                        "int ID\nint A\nint B\nint C\nint D\nint E\nint F\nint G\nint H\nfloat J",
                        "$id,noninline$ID<u32>\nA<8>\nB<u8>\nC<16>\nD<u16>\nE<32>\nF<u32>\nG<64>\nH<u64>\nJ");
        var body =
                ByteBuffer.allocate(34)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) -2)
                        .put((byte) 254)
                        .putShort(Short.MIN_VALUE)
                        .putShort((short) -1)
                        .putInt(Integer.MIN_VALUE)
                        .putInt(-1)
                        .putLong(Long.MIN_VALUE)
                        .putLong(-1)
                        .putInt(0x80000000)
                        .flip();
        var result = decode(layout, 0xffff_ffffL, body);
        assertEquals(0, body.position());
        assertEquals(34, result.consumedBytes());
        assertEquals(
                List.of(
                        "4294967295",
                        "-2",
                        "254",
                        "-32768",
                        "65535",
                        "-2147483648",
                        "4294967295",
                        "-9223372036854775808",
                        "18446744073709551615",
                        "-0"),
                result.row().values());
        assertFalse(result.row().isCopy());
        assertEquals(List.of(), result.row().textIssues());
    }

    @Test
    void floatsUseTheSameTextAsBaseDb2Rows() {
        // Effective views mix hotfix and base rows in one table: filters, spell text and
        // BigDecimal consumers must see one spelling for the same single value.
        var layout = layout("int ID\nfloat V", "$id,noninline$ID<u32>\nV[5]");
        float[] values = {20f, 1.0E-4f, 1.5f, -0f, 3.0E9f};
        var body = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            body.putFloat(value);
        }
        body.flip();
        assertEquals(
                List.of("9", "20", "0.0001", "1.5", "-0", "3E+09"),
                decode(layout, 9, body).row().values());
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY}) {
            var nonfinite = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            nonfinite.putFloat(bad).putFloat(0).putFloat(0).putFloat(0).putFloat(0).flip();
            refused(HotfixRowDecoder.Reason.FLOAT, layout, 9, nonfinite);
        }
    }

    private static HotfixRowDecoder.Layout raw(
            HotfixRowDecoder.Layout layout, String warning, java.util.Set<Integer> fitted) {
        var columns = new ArrayList<>(layout.header().columns());
        for (int i = 1; i < columns.size(); i++) {
            var plain = columns.get(i);
            var field = layout.schema().columns().get(i);
            columns.set(
                    i,
                    new Db2Types.Column(
                            plain.name(),
                            plain.kind(),
                            plain.isId(),
                            new Db2Types.Interpretation(
                                    Db2Types.RAW_PHYSICAL_POLICY,
                                    32,
                                    false,
                                    field.bits(),
                                    Boolean.TRUE.equals(field.unsigned()),
                                    warning),
                            null));
        }
        var h = layout.header();
        return new HotfixRowDecoder.Layout(
                layout.schema(),
                new Db2Types.Header(
                        h.schemaVersion(),
                        h.tableHash(),
                        h.layoutHash(),
                        h.declaredRecords(),
                        h.declaredCopyRecords(),
                        h.recordSize(),
                        columns,
                        h.format()),
                layout.nativeFlags(),
                layout.nativeIdFieldIndex(),
                fitted);
    }

    @Test
    void fittedRawColumnsDecodeAtTheirDeclaredWidth() {
        var plain = layout("int ID\nint A\nint B", "$id,noninline$ID<u32>\nA<u8>\nB<8>");
        var fitted = raw(plain, Db2Types.UNVERIFIED_RAW_WARNING, java.util.Set.of(1, 2));
        assertEquals(
                List.of("9", "0", "127"),
                decode(fitted, 9, ByteBuffer.allocate(2).put((byte) 0).put((byte) 127).flip())
                        .row()
                        .values());
        assertEquals(
                List.of("9", "255", "5"),
                decode(fitted, 9, ByteBuffer.allocate(2).put((byte) 255).put((byte) 5).flip())
                        .row()
                        .values());
        // A negative declared value has no single physical spelling in the base row.
        refused(
                HotfixRowDecoder.Reason.RAW_INTEGER,
                fitted,
                9,
                ByteBuffer.allocate(2).put((byte) 1).put((byte) -1).flip());
        // Not shown to fit the base values: refused as before.
        refused(
                HotfixRowDecoder.Reason.COLUMNS,
                raw(plain, Db2Types.UNVERIFIED_RAW_WARNING, java.util.Set.of(1)),
                9,
                ByteBuffer.allocate(2).put((byte) 1).put((byte) 1).flip());
        // Raw for another reason (explicit evidence or low-bit collisions): always refused.
        refused(
                HotfixRowDecoder.Reason.COLUMNS,
                raw(
                        plain,
                        "Candidate integer width is narrower than the physical value width.",
                        java.util.Set.of(1, 2)),
                9,
                ByteBuffer.allocate(2).put((byte) 1).put((byte) 1).flip());
    }

    @Test
    void baseTextFitsTheDeclaredWidthOnlyWhenItIsANonNegativeInRangeValue() {
        var u8 =
                new Db2Types.Interpretation(
                        Db2Types.RAW_PHYSICAL_POLICY,
                        32,
                        false,
                        8,
                        true,
                        Db2Types.UNVERIFIED_RAW_WARNING);
        var s8 =
                new Db2Types.Interpretation(
                        Db2Types.RAW_PHYSICAL_POLICY,
                        32,
                        false,
                        8,
                        false,
                        Db2Types.UNVERIFIED_RAW_WARNING);
        assertTrue(HotfixRowDecoder.fitsDeclared("0", u8));
        assertTrue(HotfixRowDecoder.fitsDeclared("255", u8));
        assertFalse(HotfixRowDecoder.fitsDeclared("256", u8));
        assertFalse(HotfixRowDecoder.fitsDeclared("4294967295", u8));
        assertFalse(HotfixRowDecoder.fitsDeclared("-1", u8));
        assertFalse(HotfixRowDecoder.fitsDeclared("x", u8));
        assertTrue(HotfixRowDecoder.fitsDeclared("127", s8));
        assertFalse(HotfixRowDecoder.fitsDeclared("128", s8));
    }

    @Test
    void relationColumnsNeverQualifyAsFittedRaw() {
        var layout = layout("int ID\nint Parent", "$id$ID<u32>\n$relation,noninline$Parent<u16>");
        var column =
                new Db2Types.Column(
                        "Parent",
                        "integer",
                        false,
                        new Db2Types.Interpretation(
                                Db2Types.RAW_PHYSICAL_POLICY,
                                32,
                                false,
                                16,
                                true,
                                Db2Types.UNVERIFIED_RAW_WARNING),
                        null);
        assertFalse(HotfixRowDecoder.rawCandidate(column, layout.schema().columns().get(1)));
    }

    @Test
    void trailingBytesAreRefusedWithTheirCountAndOptionalKeyDataIsNamed() {
        var layout = layout("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u8>");
        // A recognized optional key data block: TactKey table hash, then 24 authored bytes.
        var keyed = ByteBuffer.allocate(1 + 28).order(ByteOrder.LITTLE_ENDIAN);
        keyed.put((byte) 5).putInt(0xDF2F53CF);
        for (int i = 0; i < 24; i++) {
            keyed.put((byte) (i * 7));
        }
        assertEquals(
                HotfixRowDecoder.Reason.OPTIONAL_KEY_DATA,
                assertThrows(HotfixRowDecoder.Invalid.class, () -> decode(layout, 9, keyed.flip()))
                        .reason());
        // Zero padding is not assumed harmless: the layout may simply not describe this row.
        var padded = ByteBuffer.allocate(1 + 3).put((byte) 5).rewind();
        var padding = assertThrows(HotfixRowDecoder.Invalid.class, () -> decode(layout, 9, padded));
        assertEquals(HotfixRowDecoder.Reason.TRAILING_BYTES, padding.reason());
        assertEquals(3, padding.trailingBytes());
        assertEquals("TRAILING_BYTES:3", padding.detail());
        // 28 bytes without the TactKey hash, and the hash with another length, stay unexplained.
        var unexplained = ByteBuffer.allocate(1 + 28).order(ByteOrder.LITTLE_ENDIAN);
        unexplained.put((byte) 5).putInt(0x12345678).rewind();
        assertEquals(
                "TRAILING_BYTES:28",
                assertThrows(HotfixRowDecoder.Invalid.class, () -> decode(layout, 9, unexplained))
                        .detail());
        // The key block plus one more byte is not the recognized block.
        var keyPlusOne = ByteBuffer.allocate(1 + 29).order(ByteOrder.LITTLE_ENDIAN);
        keyPlusOne.put((byte) 5).putInt(0xDF2F53CF).rewind();
        assertEquals(
                "TRAILING_BYTES:29",
                assertThrows(HotfixRowDecoder.Invalid.class, () -> decode(layout, 9, keyPlusOne))
                        .detail());
        var shortKey = ByteBuffer.allocate(1 + 8).order(ByteOrder.LITTLE_ENDIAN);
        shortKey.put((byte) 5).putInt(0xDF2F53CF).rewind();
        assertEquals(
                "TRAILING_BYTES:8",
                assertThrows(HotfixRowDecoder.Invalid.class, () -> decode(layout, 9, shortKey))
                        .detail());
    }

    @Test
    void inlineIdMustMatchAndNoninlineRelationActuallyConsumesBodyInDbdOrder() {
        var layout =
                layout(
                        "int ID\nint Parent\nint Flags",
                        "$id$ID<u32>\n$relation,noninline$Parent<u16>\nFlags<u8>[2]");
        var body =
                ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(7)
                        .putShort((short) 0x1234)
                        .put((byte) 255)
                        .put((byte) 3)
                        .flip();
        assertEquals(List.of("7", "4660", "255", "3"), decode(layout, 7, body).row().values());
        refused(HotfixRowDecoder.Reason.INLINE_ID, layout, 8, body);
        refused(HotfixRowDecoder.Reason.TRUNCATED, layout, 7, body.slice(0, 6));
    }

    @Test
    void textArraysAndSelectedLocaleAreInlineStrictUtf8WithExactTerminator() {
        var layout =
                layout(
                        "int ID\nstring Text\nlocstring Name",
                        "$id,noninline$ID<u32>\nText[2]\nName");
        var body =
                ByteBuffer.wrap("café\0\0authored private text\0".getBytes(StandardCharsets.UTF_8));
        var result = decode(layout, 7, body);
        assertEquals(List.of("7", "café", "", "authored private text"), result.row().values());
        assertFalse(result.toString().contains("authored private text"));
        refused(
                HotfixRowDecoder.Reason.TEXT,
                layout,
                7,
                ByteBuffer.wrap(new byte[] {(byte) 0xc3, 0x28, 0, 0, 0}));
        refused(HotfixRowDecoder.Reason.TRUNCATED, layout, 7, ByteBuffer.wrap(new byte[] {65}));
    }

    @Test
    void tableLayoutColumnAndIdStorageMismatchesRefuseBeforeBodyInterpretation() {
        var valid = layout("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u8>");
        var empty = ByteBuffer.allocate(0);
        assertEquals(
                HotfixRowDecoder.Reason.TABLE_HASH,
                assertThrows(
                                HotfixRowDecoder.Invalid.class,
                                () ->
                                        HotfixRowDecoder.decode(
                                                valid, 0, 7, empty, LIMITS, Cancellation.NONE))
                        .reason());
        var header = valid.header();
        var wrong =
                new Db2Types.Header(5, "11223344", "ffffffff", 1, 0, 8, header.columns(), "WDC5");
        refused(
                HotfixRowDecoder.Reason.LAYOUT,
                new HotfixRowDecoder.Layout(valid.schema(), wrong, 4, 0),
                7,
                empty);
        refused(
                HotfixRowDecoder.Reason.ID_STORAGE,
                new HotfixRowDecoder.Layout(valid.schema(), header, 0, 0),
                7,
                empty);
        refused(
                HotfixRowDecoder.Reason.ID_STORAGE,
                new HotfixRowDecoder.Layout(valid.schema(), header, 4, 3),
                7,
                empty);
        var columns = new ArrayList<>(header.columns());
        columns.set(1, new Db2Types.Column("Wrong", "integer", false, null, null));
        var renamed = new Db2Types.Header(5, "11223344", "01020304", 1, 0, 8, columns, "WDC5");
        refused(
                HotfixRowDecoder.Reason.COLUMNS,
                new HotfixRowDecoder.Layout(valid.schema(), renamed, 4, 0),
                7,
                empty);
        assertEquals(
                List.of("7", "12"),
                decode(
                                new HotfixRowDecoder.Layout(valid.schema(), header, 4, 65535),
                                7,
                                ByteBuffer.wrap(new byte[] {12}))
                        .row()
                        .values());
    }

    @Test
    void payloadTextColumnOutputAndTailBoundsStayExplicitAndCancellationIsOriginal() {
        var layout = layout("int ID\nstring Text", "$id,noninline$ID<u32>\nText");
        refused(HotfixRowDecoder.Reason.LIMIT, layout, 7, ByteBuffer.allocate(65537));
        refused(
                HotfixRowDecoder.Reason.LIMIT,
                layout,
                7,
                ByteBuffer.wrap(("x".repeat(16385) + "\0").getBytes(StandardCharsets.US_ASCII)));
        refused(
                HotfixRowDecoder.Reason.TRAILING_BYTES,
                layout,
                7,
                ByteBuffer.wrap(new byte[] {0, 1}));
        assertEquals(
                HotfixRowDecoder.Reason.LIMIT,
                assertThrows(
                                HotfixRowDecoder.Invalid.class,
                                () ->
                                        HotfixRowDecoder.decode(
                                                layout,
                                                HASH,
                                                7,
                                                ByteBuffer.wrap(new byte[] {0}),
                                                new HotfixRowDecoder.Limits(64, 1, 32, 32),
                                                Cancellation.NONE))
                        .reason());
        assertEquals(
                HotfixRowDecoder.Reason.LIMIT,
                assertThrows(
                                HotfixRowDecoder.Invalid.class,
                                () ->
                                        HotfixRowDecoder.decode(
                                                layout,
                                                HASH,
                                                7,
                                                ByteBuffer.wrap(new byte[] {65, 66, 0}),
                                                new HotfixRowDecoder.Limits(64, 4, 32, 2),
                                                Cancellation.NONE))
                        .reason());
        var stop = new Cancellation();
        stop.cancel();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        HotfixRowDecoder.decode(
                                layout, HASH, 7, ByteBuffer.allocate(65537), LIMITS, stop));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HotfixRowDecoder.Limits(65537, 128, 16384, 131072));
    }

    private static HotfixRowDecoder.Layout wide(int fields) {
        var columns = new StringBuilder("int ID");
        var layout = new StringBuilder("$id,noninline$ID<u32>");
        for (int i = 0; i < fields; i++) {
            columns.append("\nint C").append(i);
            layout.append("\nC").append(i).append("<u8>");
        }
        return layout(columns.toString(), layout.toString());
    }

    @Test
    void wideTablesDecodeUpToTheBaseReadersColumnBound() {
        assertEquals(
                new Db2Types.Limits().maxColumns(),
                HotfixRowDecoder.Limits.MAX_COLUMNS,
                "the hotfix ceiling is the base reader's flattened-column bound");
        // PlayerCondition has 224 columns; 223 fields plus the noninline ID.
        var layout = wide(223);
        var body = ByteBuffer.allocate(223);
        for (int i = 0; i < 223; i++) {
            body.put((byte) (i % 100));
        }
        var values = decode(layout, 9, body.flip()).row().values();
        assertEquals(224, values.size());
        assertEquals("9", values.get(0));
        assertEquals("22", values.get(223));
        // Past the bound (one array flattened to 513 columns plus the ID) is still refused.
        int over = HotfixRowDecoder.Limits.MAX_COLUMNS + 1;
        refused(
                HotfixRowDecoder.Reason.LIMIT,
                layout("int ID\nint C", "$id,noninline$ID<u32>\nC<u8>[" + over + "]"),
                9,
                ByteBuffer.allocate(over));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HotfixRowDecoder.Limits(
                                65536, HotfixRowDecoder.Limits.MAX_COLUMNS + 1, 16384, 131072));
    }

    @Test
    void realBitpackedNativeRecordDoesNotSupplyHotfixPhysicalWidth() {
        var doc = Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<8>");
        var nativeBytes = Db2Fixture.numeric("WDC5", 1, 12, 4095, new long[0], false);
        var table =
                Db2Reader.open(
                                Db2ByteSource.memory(nativeBytes),
                                doc,
                                "1.2.3.4",
                                new Db2Types.Limits(),
                                Cancellation.NONE)
                        .join();
        {
            assertEquals(
                    List.of("7", "-1"),
                    table.readAll(Cancellation.NONE).join().getFirst().values());
            var schema =
                    TableSchema.fromBlock(
                            doc.selectFile("1.2.3.4", "01020304", Cancellation.NONE)
                                    .blocks()
                                    .getFirst(),
                            TableSchema.SchemaMatch.LAYOUT,
                            WowLocale.EN_US);
            var layout = new HotfixRowDecoder.Layout(schema, table.header(), 4, 0);
            assertEquals(
                    List.of("7", "-2"),
                    decode(layout, 7, ByteBuffer.wrap(new byte[] {(byte) 254})).row().values());
            refused(
                    HotfixRowDecoder.Reason.TRAILING_BYTES,
                    layout,
                    7,
                    ByteBuffer.wrap(new byte[] {(byte) 254, 15}));
        }
    }

    @Test
    void unsupportedWidthsAndRawPhysicalInterpretationsAreNotGuessed() {
        var original = layout("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u8>");
        // The DBD parser itself rejects24bits; exercise the public structural DTO defense too.
        var fields = new ArrayList<>(original.schema().columns());
        var f = fields.get(1);
        fields.set(
                1,
                new TableSchema.SchemaColumn(
                        f.name(),
                        f.kind(),
                        24,
                        true,
                        f.arrayLength(),
                        f.foreignKey(),
                        f.id(),
                        f.relation(),
                        f.verifiedName(),
                        f.locale(),
                        f.columnComment(),
                        f.fieldComment()));
        var oddSchema =
                new TableSchema(
                        fields,
                        original.schema().layoutHashes(),
                        original.schema().match(),
                        original.schema().locale(),
                        original.schema().comment());
        var odd = new HotfixRowDecoder.Layout(oddSchema, original.header(), 4, 0);
        refused(HotfixRowDecoder.Reason.SCHEMA, odd, 7, ByteBuffer.allocate(3));
        var columns = new ArrayList<>(original.header().columns());
        columns.set(
                1,
                new Db2Types.Column(
                        "Value",
                        "integer",
                        false,
                        new Db2Types.Interpretation(
                                "raw-physical-integer-v1",
                                32,
                                false,
                                8,
                                true,
                                "Authored unknown physical domain"),
                        null));
        var header = new Db2Types.Header(5, "11223344", "01020304", 1, 0, 8, columns, "WDC5");
        refused(
                HotfixRowDecoder.Reason.COLUMNS,
                new HotfixRowDecoder.Layout(original.schema(), header, 4, 0),
                7,
                ByteBuffer.allocate(1));
    }
}
