package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.store.ObjectKey;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class BlteRangeReaderTest {
    private static final CdnContext CONTEXT =
            new CdnContext(new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"));

    private static byte[] md5(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private record Blob(ObjectKey key, byte[] bytes, int header) {}

    private static Blob blob(int chunks, int size, int seed) {
        int header = 12 + 24 * chunks;
        byte[] bytes = new byte[header + chunks * size];
        ByteBuffer b = ByteBuffer.wrap(bytes);
        b.put(new byte[] {'B', 'L', 'T', 'E'})
                .putInt(header)
                .put((byte) 15)
                .put((byte) (chunks >>> 16))
                .put((byte) (chunks >>> 8))
                .put((byte) chunks);
        for (int i = 0; i < chunks; i++) {
            byte[] body = new byte[size];
            Arrays.fill(body, (byte) (i + seed));
            body[0] = 'N';
            b.putInt(size).putInt(size).put(md5(body));
            System.arraycopy(body, 0, bytes, header + i * size, size);
        }
        return new Blob(
                new ObjectKey(ObjectKey.ENCODED, md5(Arrays.copyOf(bytes, header))), bytes, header);
    }

    private static CdnException refused(String code) {
        return new CdnException(
                List.of(
                        new Attempt(
                                "host",
                                URI.create("https://us.cdn.blizzard.com/test"),
                                Failure.VERIFICATION_FAILED,
                                206,
                                Instant.EPOCH,
                                "bad proof",
                                code)));
    }

    private static BlteRangeReader.Fetch fetch(
            Blob blob, List<long[]> calls, List<CdnContext> contexts) {
        return (key, offset, length, total, check, context, priority, token) -> {
            token.check();
            calls.add(new long[] {offset, length});
            contexts.add(context);
            byte[] bytes = Arrays.copyOfRange(blob.bytes, (int) offset, (int) offset + length);
            String failure = check.refusal(bytes);
            return failure == null
                    ? CompletableFuture.completedFuture(bytes)
                    : CompletableFuture.failedFuture(refused(failure));
        };
    }

    @Test
    void readsAcrossHeaderAndChunksAndCoalescesOnlyWithinTheRangeBudget() {
        Blob blob = blob(4, 20, 1);
        List<long[]> calls = new ArrayList<>();
        BlteRangeReader reader = new BlteRangeReader(fetch(blob, calls, new ArrayList<>()), 128);
        byte[] actual =
                reader.read(
                                new ObjectRequest(blob.key, (long) blob.bytes.length),
                                blob.header - 5,
                                65,
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertArrayEquals(
                Arrays.copyOfRange(blob.bytes, blob.header - 5, blob.header + 60), actual);
        assertEquals(2, calls.size());
        assertEquals(60, calls.get(1)[1]);
        calls.clear();
        reader.read(
                        new ObjectRequest(blob.key, (long) blob.bytes.length),
                        blob.header,
                        1,
                        CONTEXT,
                        FetchPriority.INTERACTIVE,
                        Cancellation.NONE)
                .join();
        assertEquals(1, calls.size());
        assertEquals(blob.header, calls.getFirst()[0]);
    }

    @Test
    void cachedProofNeverCapturesAnEarlierCallsRoute() {
        Blob blob = blob(1, 10, 1);
        List<CdnContext> contexts = new ArrayList<>();
        BlteRangeReader reader =
                new BlteRangeReader(fetch(blob, new ArrayList<>(), contexts), 1024);
        ObjectRequest request = new ObjectRequest(blob.key, (long) blob.bytes.length);
        reader.read(request, blob.header, 1, CONTEXT, FetchPriority.INTERACTIVE, Cancellation.NONE)
                .join();
        CdnContext other = new CdnContext(new CdnRoute(List.of("eu.cdn.blizzard.com"), "tpr/wow"));
        reader.read(request, blob.header, 1, other, FetchPriority.BACKGROUND, Cancellation.NONE)
                .join();
        assertEquals(other, contexts.getLast());
    }

    @Test
    void longHeadersAreFetchedWholeAndProvenBeforeAnyChunk() {
        Blob blob = blob(2731, 1, 1);
        List<long[]> calls = new ArrayList<>();
        BlteRangeReader reader =
                new BlteRangeReader(fetch(blob, calls, new ArrayList<>()), 1 << 20);
        byte[] result =
                reader.read(
                                new ObjectRequest(blob.key, (long) blob.bytes.length),
                                blob.header + 100,
                                1,
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertArrayEquals(new byte[] {'N'}, result);
        assertEquals(3, calls.size());
        assertEquals(65536, calls.get(0)[1]);
        assertEquals(blob.header, calls.get(1)[1]);
    }

    @Test
    void failedLongHeaderProofFallsBackButOtherNetworkFailuresPropagate() {
        Blob blob = blob(2731, 1, 1);
        BlteRangeReader.Fetch base = fetch(blob, new ArrayList<>(), new ArrayList<>());
        BlteRangeReader reader =
                new BlteRangeReader(
                        (key, offset, length, total, check, context, priority, token) ->
                                length > 65536
                                        ? CompletableFuture.failedFuture(
                                                refused("OBJECT_KEY_MISMATCH"))
                                        : base.range(
                                                key, offset, length, total, check, context,
                                                priority, token),
                        1 << 20);
        assertNull(
                reader.read(
                                new ObjectRequest(blob.key, (long) blob.bytes.length),
                                blob.header,
                                1,
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join());
    }

    @Test
    void headerlessAndOversizedChunksFallBackToWholeAcquisition() {
        byte[] bytes = {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N', 1};
        Blob headerless = new Blob(new ObjectKey(ObjectKey.ENCODED, md5(bytes)), bytes, 8);
        BlteRangeReader reader =
                new BlteRangeReader(fetch(headerless, new ArrayList<>(), new ArrayList<>()), 1024);
        assertNull(
                reader.read(
                                new ObjectRequest(headerless.key, 10L),
                                8,
                                1,
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join());
        Blob blob = blob(1, 200, 1);
        reader = new BlteRangeReader(fetch(blob, new ArrayList<>(), new ArrayList<>()), 128);
        assertNull(
                reader.read(
                                new ObjectRequest(blob.key, (long) blob.bytes.length),
                                blob.header,
                                1,
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join());
    }

    @Test
    void chunkCorruptionNeverReturnsPartialOutputAndCacheDoesNotSuppressChecks() {
        Blob blob = blob(1, 200, 1);
        BlteRangeReader reader =
                new BlteRangeReader(fetch(blob, new ArrayList<>(), new ArrayList<>()), 1024);
        ObjectRequest request = new ObjectRequest(blob.key, (long) blob.bytes.length);
        reader.read(request, 0, 8, CONTEXT, FetchPriority.INTERACTIVE, Cancellation.NONE).join();
        blob.bytes[blob.header + 100] ^= 1;
        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () ->
                                reader.read(
                                                request,
                                                blob.header,
                                                1,
                                                CONTEXT,
                                                FetchPriority.INTERACTIVE,
                                                Cancellation.NONE)
                                        .join());
        assertEquals("OBJECT_BLTE_INVALID", ((CdnException) failure.getCause()).code());
    }

    @Test
    void headerCacheIsLruBoundedAndRangeArgumentsAreChecked() {
        List<long[]> calls = new ArrayList<>();
        java.util.Map<ObjectKey, Blob> blobs = new java.util.HashMap<>();
        BlteRangeReader reader =
                new BlteRangeReader(
                        (key, offset, length, total, check, context, priority, token) ->
                                fetch(blobs.get(key), calls, new ArrayList<>())
                                        .range(
                                                key, offset, length, total, check, context,
                                                priority, token),
                        1024);
        List<Blob> added = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            Blob blob = blob(1, 2, i);
            added.add(blob);
            blobs.put(blob.key, blob);
            reader.read(
                            new ObjectRequest(blob.key, (long) blob.bytes.length),
                            0,
                            8,
                            CONTEXT,
                            FetchPriority.INTERACTIVE,
                            Cancellation.NONE)
                    .join();
        }
        assertEquals(17, calls.size());
        Blob latest = added.getLast();
        reader.read(
                        new ObjectRequest(latest.key, (long) latest.bytes.length),
                        0,
                        8,
                        CONTEXT,
                        FetchPriority.INTERACTIVE,
                        Cancellation.NONE)
                .join();
        assertEquals(17, calls.size());
        Blob first = added.getFirst();
        ObjectRequest request = new ObjectRequest(first.key, (long) first.bytes.length);
        reader.read(request, 0, 8, CONTEXT, FetchPriority.INTERACTIVE, Cancellation.NONE).join();
        assertEquals(18, calls.size());
        assertThrows(
                CompletionException.class,
                () ->
                        reader.read(
                                        request,
                                        first.bytes.length,
                                        1,
                                        CONTEXT,
                                        FetchPriority.INTERACTIVE,
                                        Cancellation.NONE)
                                .join());
        Cancellation token = new Cancellation();
        token.cancel();
        assertTrue(
                reader.read(request, 0, 1, CONTEXT, FetchPriority.INTERACTIVE, token)
                        .isCompletedExceptionally());
    }
}
