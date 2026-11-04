package com.vibeminer.formats.hotfix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Types;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class HotfixCacheReaderTest {
    private static final int BUILD = 70707;

    private static byte[] entry(int region, int push, int id, int state, byte[] payload) {
        return ByteBuffer.allocate(32 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x48544658)
                .putInt(region)
                .putInt(push)
                .putInt(19)
                .putInt(0xabcdef12)
                .putInt(id)
                .putInt(payload.length)
                .put((byte) state)
                .put(new byte[3])
                .put(payload)
                .array();
    }

    private static byte[] cache(byte[]... entries) {
        int size = 44;
        for (var e : entries) {
            size += e.length;
        }
        var bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x48544658).putInt(9).putInt(BUILD).put(new byte[32]);
        for (var e : entries) {
            bytes.put(e);
        }
        return bytes.array();
    }

    private static Throwable root(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static void refuses(String code, byte[] bytes, HotfixCacheReader.Limits limits) {
        var failure =
                root(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        HotfixCacheReader.inspect(
                                                        Db2ByteSource.memory(bytes),
                                                        BUILD,
                                                        limits,
                                                        Cancellation.NONE)
                                                .join()));
        assertEquals(
                "HOTFIX_" + code, assertInstanceOf(HotfixFormatException.class, failure).code());
    }

    @Test
    void preservesPhysicalOrderRepeatedIdsStatesAndUnsignedIdentityWithoutApplyingOverlay()
            throws Exception {
        var bytes =
                cache(
                        entry(70, 20, -1, 1, new byte[] {1, 2, 3}),
                        entry(70, -1, -1, 4, new byte[0]),
                        entry(71, 15, 1328613, 2, new byte[0]));
        var result =
                HotfixCacheReader.inspect(
                                Db2ByteSource.memory(bytes),
                                BUILD,
                                new HotfixCacheReader.Limits(),
                                Cancellation.NONE)
                        .join();
        assertEquals(BUILD, result.build());
        assertEquals(9, result.version());
        assertEquals(bytes.length, result.length());
        assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                result.sha256());
        assertEquals("00".repeat(32), result.opaqueHeader());
        assertEquals(3, result.entries().size());
        var first = result.entries().getFirst();
        assertEquals(0xffff_ffffL, first.recordId());
        assertEquals(0xabcdef12L, first.tableHash());
        assertEquals(76, first.payloadOffset());
        assertEquals(3, first.payloadBytes());
        assertEquals(20, first.pushId());
        assertEquals(-1, result.entries().get(1).pushId());
        assertEquals(HotfixCacheReader.State.NOT_PUBLIC, result.entries().get(1).state());
        assertEquals(2, result.entries().get(2).ordinal());
        assertEquals(71, result.entries().get(2).regionId());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().clear());
    }

    @Test
    void mismatchedBuildFetchesOnlyHeaderAndNoPayload() {
        var bytes = cache(entry(70, 1, 1328613, 1, new byte[70000]));
        var fetched = new AtomicInteger();
        Db2ByteSource inner = Db2ByteSource.memory(bytes);
        var source =
                new Source(inner) {
                    @Override
                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer out, Cancellation token) {
                        fetched.addAndGet(out.remaining());
                        return super.readRange(offset, out, token);
                    }
                };
        var failure =
                root(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        HotfixCacheReader.inspect(
                                                        source,
                                                        BUILD + 1,
                                                        new HotfixCacheReader.Limits(),
                                                        Cancellation.NONE)
                                                .join()));
        assertEquals("HOTFIX_BUILD_MISMATCH", ((HotfixFormatException) failure).code());
        assertEquals(44, fetched.get());
    }

    @Test
    void streamingCrossesHeaderAndPayloadBoundariesWithOneBoundedReadBuffer() throws Exception {
        var bytes = cache(entry(70, 1, 1, 1, new byte[65520]), entry(70, 2, 2, 1, new byte[65537]));
        var maximum = new AtomicInteger();
        var fetched = new AtomicInteger();
        var source =
                new Source(Db2ByteSource.memory(bytes)) {
                    @Override
                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer out, Cancellation token) {
                        maximum.accumulateAndGet(out.remaining(), Math::max);
                        fetched.addAndGet(out.remaining());
                        return super.readRange(offset, out, token);
                    }
                };
        var index =
                HotfixCacheReader.inspect(
                                source, BUILD, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        assertEquals(2, index.entries().size());
        assertEquals(bytes.length, fetched.get());
        assertTrue(maximum.get() <= 65536);
        assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                index.sha256());
    }

    @Test
    void rejectsMalformedUnsupportedAndOverLimitSourcesWithoutPartialIndex() {
        var limits = new HotfixCacheReader.Limits();
        refuses("INVALID", new byte[43], limits);
        var version = cache();
        put(version, 4, 10);
        refuses("UNSUPPORTED", version, limits);
        var magic = cache();
        magic[0] = 0;
        refuses("UNSUPPORTED", magic, limits);
        var valid = cache(entry(70, 1, 1, 1, new byte[] {1}));
        refuses("INVALID", Arrays.copyOf(valid, valid.length - 1), limits);
        refuses("INVALID", Arrays.copyOf(valid, valid.length + 1), limits);
        var state = valid.clone();
        state[44 + 28] = 5;
        refuses("UNSUPPORTED", state, limits);
        var reserved = valid.clone();
        reserved[44 + 29] = 1;
        refuses("UNSUPPORTED", reserved, limits);
        var size = valid.clone();
        put(size, 44 + 24, -1);
        refuses("INVALID", size, limits);
        var entryMagic = valid.clone();
        entryMagic[44] = 0;
        refuses("INVALID", entryMagic, limits);
        refuses("LIMIT", valid, new HotfixCacheReader.Limits(44, 10, 1));
        refuses("LIMIT", valid, new HotfixCacheReader.Limits(1000, 0, 1));
        refuses("LIMIT", valid, new HotfixCacheReader.Limits(1000, 10, 0));
        assertEquals(
                0,
                HotfixCacheReader.inspect(
                                Db2ByteSource.memory(cache()), BUILD, limits, Cancellation.NONE)
                        .join()
                        .entries()
                        .size());
    }

    @Test
    void selectedPayloadIsBoundToOriginalSourceAndItsInspectedBodyHash() {
        var bytes = cache(entry(70, 1, 1328613, 1, new byte[] {4, 5, 6}));
        var reads = new AtomicInteger();
        var source =
                new Source(Db2ByteSource.memory(bytes)) {
                    @Override
                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer out, Cancellation token) {
                        token.check();
                        reads.incrementAndGet();
                        out.put(bytes, (int) offset, out.remaining());
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var index =
                HotfixCacheReader.inspect(
                                source, BUILD, new HotfixCacheReader.Limits(), Cancellation.NONE)
                        .join();
        assertArrayEquals(
                new byte[] {4, 5, 6},
                HotfixCacheReader.readPayload(source, index, 0, 3, Cancellation.NONE).join());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HotfixCacheReader.readPayloadBlocking(
                                Db2ByteSource.memory(bytes), index, 0, 3, Cancellation.NONE));
        int before = reads.get();
        assertThrows(
                HotfixFormatException.class,
                () ->
                        HotfixCacheReader.readPayloadBlocking(
                                source, index, 0, 2, Cancellation.NONE));
        assertEquals(before, reads.get(), "Size refusal occurs before body fetch/allocation");
        bytes[76] = 7;
        var changed =
                assertThrows(
                        HotfixFormatException.class,
                        () ->
                                HotfixCacheReader.readPayloadBlocking(
                                        source, index, 0, 3, Cancellation.NONE));
        assertEquals("HOTFIX_SOURCE_CHANGED", changed.code());
    }

    @Test
    void sourceFailuresKeepTheirIdentityAndCancellationWaitsForActualLowerRead() throws Exception {
        var original = new IOException("synthetic source failure");
        var failing =
                new Source(Db2ByteSource.memory(cache())) {
                    @Override
                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer out, Cancellation token) {
                        return CompletableFuture.failedFuture(original);
                    }
                };
        assertSame(
                original,
                root(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        HotfixCacheReader.inspect(
                                                        failing,
                                                        BUILD,
                                                        new HotfixCacheReader.Limits(),
                                                        Cancellation.NONE)
                                                .join())));
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var source =
                new Source(Db2ByteSource.memory(cache(entry(70, 1, 1, 1, new byte[16])))) {
                    @Override
                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer out, Cancellation token) {
                        if (offset == 0) {
                            return super.readRange(offset, out, token);
                        }
                        var registration = token.onCancel(() -> canceled.complete(null));
                        entered.complete(null);
                        return release.thenCompose(ignored -> super.readRange(offset, out, token))
                                .whenComplete((value, failure) -> registration.close());
                    }
                };
        var token = new Cancellation();
        var result =
                HotfixCacheReader.inspect(source, BUILD, new HotfixCacheReader.Limits(), token);
        try {
            entered.get(3, TimeUnit.SECONDS);
            token.cancel();
            canceled.get(3, TimeUnit.SECONDS);
            assertFalse(result.isDone(), "Actual lower read still owns the buffer");
        } finally {
            release.complete(null);
        }
        assertInstanceOf(
                java.util.concurrent.CancellationException.class,
                root(assertThrows(Exception.class, () -> result.get(3, TimeUnit.SECONDS))));
    }

    private static void put(byte[] bytes, int at, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
    }

    private static class Source implements Db2ByteSource {
        private final Db2ByteSource inner;

        Source(Db2ByteSource inner) {
            this.inner = inner;
        }

        @Override
        public long length() {
            return inner.length();
        }

        @Override
        public List<Db2Types.Hole> holes() {
            return inner.holes();
        }

        @Override
        public CompletableFuture<Void> readRange(long offset, ByteBuffer out, Cancellation token) {
            return inner.readRange(offset, out, token);
        }
    }
}
