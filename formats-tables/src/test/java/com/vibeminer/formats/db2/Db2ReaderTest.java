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
