package com.vibeminer.formats.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class Db2ReaderTest {
    private static Db2Table open(byte[] bytes, String schema) {
        return Db2Reader.open(
                        Db2ByteSource.memory(bytes),
                        Db2Fixture.definition(
                                "int ID\nint Value", "$id,noninline$ID<u32>\nValue" + schema),
                        "1.2.3.4",
                        new Db2Types.Limits(),
                        Cancellation.NONE)
                .join();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WDC3", "WDC4", "WDC5"})
    void versionsResolveCopiesAndOrdinals(String version) {
        var bytes = Db2Fixture.numeric(version, 0, 32, 0xffffffffL, new long[0], true);
        var table = open(bytes, "<32>");
        assertEquals(version, table.header().format());
        assertEquals("01020304", Db2Reader.layoutHash(bytes));
        var rows = table.readAll(Cancellation.NONE).join();
        assertEquals(List.of("7", "-1"), rows.getFirst().values());
        assertEquals(List.of("8", "-1"), rows.get(1).values());
        assertTrue(rows.get(1).isCopy());
        assertEquals(7L, rows.get(1).copySourceId());
        assertEquals(1, table.findWithOrdinal(8, Cancellation.NONE).join().ordinal());
        assertEquals(rows.get(1), table.readOrdinals(1, 1, Cancellation.NONE).join().getFirst());
        assertInstanceOf(Db2Types.Absent.class, table.find(9, Cancellation.NONE).join());
    }

    @ParameterizedTest
    @CsvSource({
        "0,8,255,<32>,255",
        "0,32,4294967295,<8>,-1",
        "1,12,4095,<8>,-1",
        "5,2,3,<8>,-1",
        "5,2,3,<u8>,255"
    })
    void integerStoragePreservesSignAndWidths(
            int kind, int width, long raw, String schema, String expected) {
        assertEquals(
                expected,
                open(Db2Fixture.numeric("WDC5", kind, width, raw, new long[0], false), schema)
                        .readAll(Cancellation.NONE)
                        .join()
                        .getFirst()
                        .values()
                        .get(1));
    }

    @Test
    void paletteNarrowingIsVisibleAndCollisionsRemainRaw() {
        var table = open(Db2Fixture.numeric("WDC5", 3, 1, 0, new long[] {256}, false), "<u8>");
        assertEquals("0", table.readAll(Cancellation.NONE).join().getFirst().values().get(1));
        assertNotNull(table.header().columns().get(1).narrowing());
        table =
                open(
                        Db2Fixture.numeric("WDC5", 3, 1, 0, new long[] {5, 0xffff0005L}, false),
                        "<u16>");
        assertNotNull(table.header().columns().get(1).interpretation());
        assertEquals("5", table.readAll(Cancellation.NONE).join().getFirst().values().get(1));
    }

    @Test
    void varyingPaletteUpperBitsRefuseBeforeHeader() {
        assertThrows(
                CompletionException.class,
                () ->
                        open(
                                Db2Fixture.numeric("WDC5", 3, 1, 0, new long[] {256, 512}, false),
                                "<u8>"));
    }

    @Test
    void sparseInlineStringsRespectVariableRecordFraming() {
        var table =
                Db2Reader.open(
                                Db2ByteSource.memory(Db2Fixture.sparse()),
                                Db2Fixture.definition(
                                        "int ID\nstring Name\nint Value",
                                        "$id,noninline$ID<u32>\nName\nValue<u32>"),
                                "1.2.3.4",
                                new Db2Types.Limits(),
                                Cancellation.NONE)
                        .join();
        assertEquals(
                List.of("7", "café", "123"),
                table.readAll(Cancellation.NONE).join().getFirst().values());
    }

    @Test
    void trailingBytesAndUnrepresentableCellsRefuseAtTheirOwnPhase() {
        var bytes = Db2Fixture.numeric("WDC5", 0, 32, 256, new long[0], false);
        var table = open(bytes, "<u8>");
        var failure =
                assertThrows(
                        CompletionException.class, () -> table.readAll(Cancellation.NONE).join());
        assertEquals("DB2_SCHEMA_MISMATCH", ((Db2FormatException) failure.getCause()).code());
        assertThrows(CompletionException.class, () -> open(Db2Fixture.trailing(bytes), "<u32>"));
    }

    @Test
    void fullReadCursorCancellationReachesItsPendingSourceRead() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], false);
        var started = new java.util.concurrent.CompletableFuture<Void>();
        Db2ByteSource source =
                new Db2ByteSource() {
                    @Override
                    public long length() {
                        return bytes.length;
                    }

                    @Override
                    public List<Db2Types.Hole> holes() {
                        return List.of();
                    }

                    @Override
                    public java.util.concurrent.CompletableFuture<Void> readRange(
                            long offset, java.nio.ByteBuffer destination, Cancellation token) {
                        if (offset == 272 && destination.remaining() == 8) {
                            var pending = new java.util.concurrent.CompletableFuture<Void>();
                            var registration = token.onCancel(() -> pending.cancel(false));
                            pending.whenComplete((value, error) -> registration.close());
                            started.complete(null);
                            return pending;
                        }
                        token.check();
                        destination.put(bytes, (int) offset, destination.remaining());
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                };
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var table =
                Db2Reader.open(
                                source,
                                definition,
                                "1.2.3.4",
                                new Db2Types.Limits(),
                                Cancellation.NONE)
                        .join();
        var owner = new Cancellation();
        try (var cursor = table.rows(owner)) {
            var pending = cursor.next(1, Cancellation.NONE);
            started.join();
            owner.cancel();
            var failure = assertThrows(CompletionException.class, pending::join);
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
        }
    }

    @Test
    void inlineIdStoredAfterArrayFieldsIsReadFromItsOwnStorage() {
        // Classic sky-table layouts (for example LightParams 51C96BAD) store the inline ID at
        // field index 2, after two three-element arrays.
        var definition =
                Db2Fixture.definition(
                        "int ID\nint Colour\nint Range\nint Value",
                        "Colour<32>[3]\nRange<32>[3]\n$id$ID<32>\nValue<32>");
        var table =
                Db2Reader.open(
                                Db2ByteSource.memory(idAfterArrays(2)),
                                definition,
                                "1.2.3.4",
                                new Db2Types.Limits(),
                                Cancellation.NONE)
                        .join();
        var rows = table.readAll(Cancellation.NONE).join();
        assertEquals(2, rows.size());
        assertEquals(41, rows.getFirst().id());
        assertEquals(42, rows.get(1).id());
        // The arrays before the ID and the field after it keep their own offsets.
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "41", "-7"), rows.getFirst().values());
        assertEquals(List.of("11", "12", "13", "14", "15", "16", "42", "-8"), rows.get(1).values());
        var found = table.findWithOrdinal(42, Cancellation.NONE).join();
        assertEquals(1, found.ordinal());
        assertEquals(rows.get(1), ((Db2Types.Found) found.lookup()).row());
        // A header whose ID index points anywhere else is refused, never read silently.
        for (int idIndex : new int[] {0, 1, 3, 65535}) {
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    Db2Reader.open(
                                                    Db2ByteSource.memory(idAfterArrays(idIndex)),
                                                    definition,
                                                    "1.2.3.4",
                                                    new Db2Types.Limits(),
                                                    Cancellation.NONE)
                                            .join());
            assertEquals(
                    "DB2_UNSUPPORTED",
                    ((Db2FormatException) failure.getCause()).code(),
                    "idIndex " + idIndex);
        }
    }

    /** Two 32-byte records: Colour[3], Range[3], inline ID, Value; all uncompressed 32-bit. */
    private static byte[] idAfterArrays(int idIndex) {
        int fields = 4, records = 2, recordSize = 32;
        int start = 204 + 40 + fields * 4 + fields * 24;
        var b =
                java.nio.ByteBuffer.allocate(start + records * recordSize)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("WDC5".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(5);
        int at = 136;
        b.putInt(at, records);
        b.putInt(at + 4, fields);
        b.putInt(at + 8, recordSize);
        b.putInt(at + 16, 0x11223344);
        b.putInt(at + 20, 0x01020304);
        b.putInt(at + 24, 41);
        b.putInt(at + 28, 42);
        b.putShort(at + 36, (short) 0);
        b.putShort(at + 38, (short) idIndex);
        b.putInt(at + 40, fields);
        b.putInt(at + 52, fields * 24);
        b.putInt(at + 64, 1);
        b.position(204);
        b.putLong(0).putInt(start).putInt(records).putInt(0).putInt(0).putInt(0).putInt(0);
        b.putInt(0).putInt(0);
        int[] offsets = {0, 12, 24, 28}, widths = {96, 96, 32, 32};
        for (int offset : offsets) {
            b.putShort((short) 0).putShort((short) offset);
        }
        for (int i = 0; i < fields; i++) {
            b.putShort((short) (offsets[i] * 8))
                    .putShort((short) widths[i])
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0);
        }
        b.position(start);
        for (int r = 0; r < records; r++) {
            b.putInt(10 * r + 1).putInt(10 * r + 2).putInt(10 * r + 3);
            b.putInt(10 * r + 4).putInt(10 * r + 5).putInt(10 * r + 6);
            b.putInt(41 + r).putInt(-(r + 7));
        }
        return b.array();
    }

    @Test
    void unknownMagicRetainsLegacyMandatoryHeaderBeforeFormatRefusal() {
        for (int length : new int[] {4, 204}) {
            byte[] bytes = new byte[length];
            System.arraycopy(
                    "WDC1".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 0, 4);
            var failure = assertThrows(CompletionException.class, () -> open(bytes, "<u32>"));
            assertEquals(
                    length < 204 ? "DB2_INVALID" : "DB2_UNSUPPORTED",
                    ((Db2FormatException) failure.getCause()).code());
        }
    }
}
