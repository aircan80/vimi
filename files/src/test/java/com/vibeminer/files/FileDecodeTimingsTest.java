package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class FileDecodeTimingsTest {
    @AfterEach
    void restore() {
        FileDecodeTimings.enable(false);
        FileDecodeTimings.reset();
    }

    private static byte[] decodeLast(FileFixtures.Encoded e, long length) {
        int n = BlteChunks.headerLength(Arrays.copyOf(e.bytes(), 8), e.bytes().length);
        var l =
                BlteChunks.parse(
                        Arrays.copyOf(e.bytes(), n == 0 ? 8 : n),
                        e.key(),
                        e.bytes().length,
                        length);
        var c = l.chunks().getLast();
        return BlteChunks.decode(
                        l,
                        c,
                        Arrays.copyOfRange(
                                e.bytes(),
                                (int) c.encodedOffset(),
                                (int) (c.encodedOffset() + c.encodedSize())),
                        e.key(),
                        TactKeys.NONE)
                .bytes();
    }

    @Test
    void disabledCountsNothing() {
        var e = FileFixtures.chunked(List.of(FileFixtures.zlib(new byte[] {1, 2, 3})));
        assertArrayEquals(new byte[] {1, 2, 3}, decodeLast(e, 3));
        var totals = FileDecodeTimings.snapshot();
        assertEquals(0, totals.zlibChunks());
        assertEquals(0, totals.zlibBytes());
        assertEquals(0, totals.zlibNanos());
    }

    @Test
    void enabledCountsZlibChunksAndDecodedBytes() {
        FileDecodeTimings.enable(true);
        var e =
                FileFixtures.chunked(
                        List.of(
                                FileFixtures.raw(new byte[] {9}),
                                FileFixtures.zlib(new byte[] {1, 2, 3, 4})));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decodeLast(e, 5));
        var totals = FileDecodeTimings.snapshot();
        assertEquals(1, totals.zlibChunks());
        assertEquals(4, totals.zlibBytes());
        assertTrue(totals.zlibNanos() >= 0);
    }

    @Test
    void rawChunksAreNotCountedAsInflation() {
        FileDecodeTimings.enable(true);
        var e = FileFixtures.chunked(List.of(FileFixtures.raw(new byte[] {7, 8})));
        assertArrayEquals(new byte[] {7, 8}, decodeLast(e, 2));
        assertEquals(0, FileDecodeTimings.snapshot().zlibChunks());
    }

    @Test
    void resetClearsTotals() {
        FileDecodeTimings.enable(true);
        var e = FileFixtures.chunked(List.of(FileFixtures.zlib(new byte[] {1})));
        decodeLast(e, 1);
        FileDecodeTimings.reset();
        assertEquals(0, FileDecodeTimings.snapshot().zlibChunks());
    }

    @Test
    void sharesTheWorldStageTimingsProperty() {
        assertEquals("vimi.world.stageTimings", FileDecodeTimings.PROPERTY);
    }
}
