package com.vibeminer.formats.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RowCodecReadLimitsTest {
    private static void limited(byte[] encoded, RowCodec.DecodeLimits limits) {
        var failure =
                assertThrows(
                        GeneratedStoreException.class, () -> RowCodec.decode(17, encoded, limits));
        assertEquals(GeneratedTypes.LIMIT, failure.code());
    }

    @Test
    void exactLimitsPreserveCopyFlagsTextAndNativeIssueChannel() {
        var row =
                new Db2Types.Row(
                        17,
                        true,
                        12L,
                        List.of("é", "abc"),
                        List.of(new Db2Types.TextIssue(0, "C", "ff")));
        byte[] encoded = RowCodec.encode(row);
        assertEquals(
                row,
                RowCodec.decode(17, encoded, new RowCodec.DecodeLimits(encoded.length, 2, 1, 3)));
        assertEquals(row, RowCodec.decode(17, encoded));
        limited(encoded, new RowCodec.DecodeLimits(encoded.length - 1, 2, 1, 3));
        limited(encoded, new RowCodec.DecodeLimits(encoded.length, 1, 1, 3));
        limited(encoded, new RowCodec.DecodeLimits(encoded.length, 2, 1, 2));
    }

    @Test
    void utf8ByteLimitIsAppliedBeforeCharacterDecoding() {
        byte[] encoded = RowCodec.encode(new Db2Types.Row(17, false, null, List.of("é"), null));
        limited(encoded, new RowCodec.DecodeLimits(encoded.length, 1, 1, 1));
        assertEquals(
                "é",
                RowCodec.decode(17, encoded, new RowCodec.DecodeLimits(encoded.length, 1, 1, 2))
                        .values()
                        .getFirst());
    }

    @Test
    void validLargeCellAndIssueInventoriesReturnLimitsNotCorruption() {
        var cells = new Db2Types.Row(17, false, null, java.util.Collections.nCopies(129, ""), null);
        byte[] encoded = RowCodec.encode(cells);
        limited(encoded, new RowCodec.DecodeLimits(65536, 128, 128, 65536));
        assertEquals(cells, RowCodec.decode(17, encoded));
        var issues =
                new Db2Types.Row(
                        17,
                        false,
                        null,
                        List.of("x"),
                        List.of(
                                new Db2Types.TextIssue(0, "C", "ff"),
                                new Db2Types.TextIssue(0, "C", "00")));
        byte[] encodedIssues = RowCodec.encode(issues);
        limited(encodedIssues, new RowCodec.DecodeLimits(65536, 128, 1, 65536));
        assertEquals(issues, RowCodec.decode(17, encodedIssues));
    }

    @Test
    void malformedUtf8AndTruncatedRecordsRemainCorruptionWithinLimits() {
        // flags, one value, one-byte UTF8 string containing an invalid continuation byte.
        for (byte[] invalid :
                List.of(
                        new byte[] {0, 1, 1, (byte) 0x80},
                        new byte[] {0, 1, 4, 65},
                        new byte[] {0, (byte) 0x80})) {
            var failure =
                    assertThrows(
                            GeneratedStoreException.class,
                            () ->
                                    RowCodec.decode(
                                            17,
                                            invalid,
                                            new RowCodec.DecodeLimits(65536, 128, 128, 65536)));
            assertEquals(GeneratedTypes.CORRUPT, failure.code());
        }
    }
}
