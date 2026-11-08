package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.network.CdnKey;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BlteChunksTest {
    private static BlteChunks.Layout layout(FileFixtures.Encoded e, long length) {
        int n = BlteChunks.headerLength(Arrays.copyOf(e.bytes(), 8), e.bytes().length);
        return BlteChunks.parse(
                Arrays.copyOf(e.bytes(), n == 0 ? 8 : n), e.key(), e.bytes().length, length);
    }

    private static BlteChunks.Decoded decode(
            FileFixtures.Encoded e, BlteChunks.Layout l, int index, TactKeySource keys) {
        var c = l.chunks().get(index);
        return BlteChunks.decode(
                l,
                c,
                Arrays.copyOfRange(
                        e.bytes(),
                        (int) c.encodedOffset(),
                        (int) c.encodedOffset() + c.encodedSize()),
                e.key(),
                keys);
    }

    @Test
    void rawZlibAndEmptyChunksDecodeAndIndexSkipsEmpty() {
        byte[] a = {1, 2, 3}, b = {4, 5};
        var e =
                FileFixtures.chunked(
                        List.of(
                                FileFixtures.raw(new byte[0]),
                                FileFixtures.raw(a),
                                FileFixtures.zlib(b)));
        var l = layout(e, 5);
        assertEquals(1, l.indexAt(0));
        assertEquals(2, l.indexAt(3));
        assertArrayEquals(a, decode(e, l, 1, TactKeys.NONE).bytes());
        assertArrayEquals(b, decode(e, l, 2, TactKeys.NONE).bytes());
    }

    @Test
    void headerlessIsProvedAsWholeObject() {
        var e = FileFixtures.headerless(FileFixtures.zlib(new byte[] {1, 2, 3}));
        var l = layout(e, 3);
        assertArrayEquals(new byte[] {1, 2, 3}, decode(e, l, 0, TactKeys.NONE).bytes());
        byte[] damaged = e.bytes();
        damaged[damaged.length - 1] ^= 1;
        var bad = new FileFixtures.Encoded(e.key(), damaged);
        assertEquals(
                "BLTE_INTEGRITY",
                assertThrows(BlteChunks.Failure.class, () -> decode(bad, l, 0, TactKeys.NONE))
                        .code);
    }

    @Test
    void encryptedHoleKnownKeyAndWrongKeyAreDistinct() {
        long name = 0xfedcba9876543210L;
        byte[] secret = new byte[16];
        Arrays.fill(secret, (byte) 7);
        byte[] plain = {1, 2, 3, 4};
        var e =
                FileFixtures.chunked(
                        List.of(
                                FileFixtures.raw(new byte[] {9}),
                                FileFixtures.encrypted(name, secret, FileFixtures.zlib(plain), 1)));
        var l = layout(e, 5);
        assertEquals("FEDCBA9876543210", decode(e, l, 1, TactKeys.NONE).hole());
        var keys = new TactKeys().add(name, secret);
        assertArrayEquals(plain, decode(e, l, 1, keys).bytes());
        assertNull(decode(e, l, 0, keys).hole());
        assertEquals(
                FileReadState.WRONG_KEY,
                assertThrows(
                                BlteChunks.Failure.class,
                                () -> decode(e, l, 1, new TactKeys().add(name, new byte[16])))
                        .state);
    }

    @Test
    void headerAndChunkChecksPrecedeModeInterpretation() {
        var e = FileFixtures.chunked(List.of(FileFixtures.raw(new byte[] {1})));
        byte[] changed = e.bytes();
        changed[8] = 16;
        assertEquals(
                "BLTE_INTEGRITY",
                assertThrows(
                                BlteChunks.Failure.class,
                                () ->
                                        BlteChunks.parse(
                                                Arrays.copyOf(changed, 36),
                                                e.key(),
                                                changed.length,
                                                1L))
                        .code);
        var l = layout(e, 1);
        byte[] changedChunk = e.bytes();
        changedChunk[36] = 'F';
        var damaged = new FileFixtures.Encoded(e.key(), changedChunk);
        assertEquals(
                "BLTE_INTEGRITY",
                assertThrows(BlteChunks.Failure.class, () -> decode(damaged, l, 0, TactKeys.NONE))
                        .code);
    }

    @Test
    void unsupportedModesAndCiphersAreHonestStates() {
        var e = FileFixtures.chunked(List.of(new FileFixtures.Chunk(new byte[] {'F', 0}, 1)));
        var l = layout(e, 1);
        assertEquals(
                FileReadState.UNSUPPORTED,
                assertThrows(BlteChunks.Failure.class, () -> decode(e, l, 0, TactKeys.NONE)).state);
        byte[] envelope = new byte[17];
        envelope[0] = 'E';
        envelope[1] = 8;
        envelope[10] = 4;
        envelope[15] = 'A';
        var bad = FileFixtures.chunked(List.of(new FileFixtures.Chunk(envelope, 1)));
        assertEquals(
                FileReadState.UNSUPPORTED,
                assertThrows(
                                BlteChunks.Failure.class,
                                () -> decode(bad, layout(bad, 1), 0, TactKeys.NONE))
                        .state);
    }

    @Test
    void zlibRejectsTrailingTruncatedAndWrongLengths() {
        var good = FileFixtures.zlib(new byte[] {1, 2, 3});
        byte[] extra = Arrays.copyOf(good.encoded(), good.encoded().length + 1);
        byte[] shortBytes = Arrays.copyOf(good.encoded(), good.encoded().length - 1);
        for (var c :
                List.of(
                        new FileFixtures.Chunk(extra, 3),
                        new FileFixtures.Chunk(shortBytes, 3),
                        new FileFixtures.Chunk(good.encoded(), 2),
                        new FileFixtures.Chunk(good.encoded(), 4))) {
            var e = FileFixtures.chunked(List.of(c));
            assertEquals(
                    FileReadState.CORRUPT,
                    assertThrows(
                                    BlteChunks.Failure.class,
                                    () -> decode(e, layout(e, c.decodedSize()), 0, TactKeys.NONE))
                            .state);
        }
    }

    /** Reused inflaters decode byte-identically, also right after a chunk that failed midway. */
    @Test
    void reusedInflaterDecodesIdenticallyAcrossFailedChunks() {
        var random = new java.util.Random(405);
        byte[] first = new byte[300_000], second = new byte[70_000];
        for (int i = 0; i < first.length; i++) {
            first[i] = (byte) (i % 97 < 40 ? random.nextInt(256) : i % 13);
        }
        random.nextBytes(second);
        var good = FileFixtures.zlib(first);
        var other = FileFixtures.zlib(second);
        byte[] midway = good.encoded().clone();
        midway[midway.length / 2] ^= 0x55;
        byte[] checksum = good.encoded().clone();
        checksum[checksum.length - 1] ^= 1;
        byte[] truncated = Arrays.copyOf(good.encoded(), good.encoded().length / 3);
        var e =
                FileFixtures.chunked(
                        List.of(
                                good,
                                new FileFixtures.Chunk(midway, first.length),
                                other,
                                new FileFixtures.Chunk(checksum, first.length),
                                good,
                                new FileFixtures.Chunk(truncated, first.length),
                                other));
        var l = layout(e, 3L * first.length + 2L * second.length + 2L * first.length);
        for (int round = 0; round < 2; round++) {
            assertArrayEquals(first, decode(e, l, 0, TactKeys.NONE).bytes());
            for (int failed : new int[] {1, 3, 5}) {
                assertEquals(
                        FileReadState.CORRUPT,
                        assertThrows(
                                        BlteChunks.Failure.class,
                                        () -> decode(e, l, failed, TactKeys.NONE))
                                .state);
                assertArrayEquals(
                        failed == 3 ? first : second,
                        decode(e, l, failed + 1, TactKeys.NONE).bytes());
            }
        }
    }

    @Test
    void inflaterIsResetAndReusedAfterAFailure() throws Exception {
        // A test-local pool: the shared pool's FIFO contents depend on which tests ran before.
        var pool = new BlteChunks.InflaterPool(2);
        var inflater = pool.borrow();
        inflater.setInput(new byte[] {0x78, (byte) 0x9c, (byte) 0xff, (byte) 0xff});
        assertThrows(java.util.zip.DataFormatException.class, () -> inflater.inflate(new byte[8]));
        pool.release(inflater);
        var reused = pool.borrow();
        try {
            assertSame(inflater, reused);
            assertEquals(0, reused.getRemaining());
            assertEquals(0L, reused.getBytesRead());
        } finally {
            reused.end();
        }
        byte[] data = {9, 8, 7, 6};
        var e = FileFixtures.chunked(List.of(FileFixtures.zlib(data)));
        assertArrayEquals(data, decode(e, layout(e, 4), 0, TactKeys.NONE).bytes());
    }

    @Test
    void inflaterPoolEndsInflatersPastItsBound() {
        var pool = new BlteChunks.InflaterPool(1);
        var kept = pool.borrow();
        var extra = pool.borrow();
        pool.release(kept);
        pool.release(extra);
        assertThrows(RuntimeException.class, () -> extra.inflate(new byte[1]));
        assertSame(kept, pool.borrow());
        kept.end();
    }

    @Test
    void declaredTotalsAndChunkBoundsAreCheckedAfterHeaderProof() {
        var e = FileFixtures.chunked(List.of(FileFixtures.raw(new byte[] {1})));
        assertThrows(BlteChunks.Failure.class, () -> layout(e, 2));
        byte[] header = Arrays.copyOf(e.bytes(), 36);
        ByteBuffer.wrap(header).putInt(12, (16 << 20) + 1);
        var key = new CdnKey(FileFixtures.md5(header));
        assertEquals(
                "BLTE_LIMIT",
                assertThrows(
                                BlteChunks.Failure.class,
                                () -> BlteChunks.parse(header, key, 36L + (16 << 20) + 1, 1L))
                        .code);
    }

    @Test
    void keysOwnInputAndOutput() {
        byte[] key = new byte[16];
        key[0] = 3;
        var keys = new TactKeys().add(1, key);
        key[0] = 9;
        byte[] found = keys.find(1);
        assertEquals(3, found[0]);
        found[0] = 7;
        assertEquals(3, keys.find(1)[0]);
        assertThrows(IllegalArgumentException.class, () -> keys.add(2, new byte[15]));
    }
}
