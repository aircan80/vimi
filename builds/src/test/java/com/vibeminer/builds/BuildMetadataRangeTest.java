package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.access.SourceTypes.ServedFrom;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildPart;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes.Origin;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Public opening already crosses a layer2 whole-read boundary via ranges, as the C# opener does.
 */
@Timeout(20)
final class BuildMetadataRangeTest {
    private static final int WHOLE_BOUNDARY = 64, RANGE_BYTES = 23;

    private static BuildOpener opener(BoundarySource source) {
        return new BuildOpener(
                source, new BuildOpener.Options(4, 2, 512L << 20, 1L << 30, RANGE_BYTES, 2, 64));
    }

    @Test
    void configsEncodingRootAndBothIndexKindsOpenAcrossTheWholeReadBoundary() {
        BoundarySource source = new BoundarySource();
        BuildOpener opener = opener(source);
        try {
            var result = opener.open(source.data.ref).join();
            assertEquals(BuildOpenState.OPEN, result.state());
            assertEquals(2, result.opened().metadata().fileCount());
            // This is the same public PRESENT+known-length+no-bytes shape used for local objects
            // over64MiB.
            Set<ObjectKey> expected =
                    Set.of(
                            source.data.buildKey,
                            source.data.cdnKey,
                            source.data.encodingKey,
                            source.data.rootKey,
                            source.data.archiveIndexKey,
                            source.data.fileIndexKey);
            assertEquals(expected, source.largeWholeReplies);
            assertEquals(
                    expected,
                    source.ranges.stream()
                            .map(RangeCall::key)
                            .collect(java.util.stream.Collectors.toSet()));
            for (ObjectKey key : expected) {
                List<RangeCall> calls =
                        source.ranges.stream().filter(c -> c.key().equals(key)).toList();
                long at = 0;
                for (RangeCall call : calls) {
                    assertEquals(at, call.offset());
                    assertTrue(call.length() > 0 && call.length() <= RANGE_BYTES);
                    assertEquals(FetchPriority.INTERACTIVE, call.priority());
                    at += call.length();
                }
                assertEquals(source.data.objects.get(key).length, at);
            }
            assertTrue(
                    source.ranges.stream()
                            .filter(c -> c.key().equals(source.data.encodingKey))
                            .allMatch(
                                    c ->
                                            c.declaredLength() != null
                                                    && c.declaredLength()
                                                            == source.data.objects.get(
                                                                            source.data.encodingKey)
                                                                    .length));
            assertTrue(
                    source.ranges.stream()
                            .filter(c -> c.key().equals(source.data.rootKey))
                            .allMatch(
                                    c ->
                                            c.declaredLength() != null
                                                    && c.declaredLength()
                                                            == source.data.objects.get(
                                                                            source.data.rootKey)
                                                                    .length));
            assertEquals(0, source.largeFullBodiesReturned);
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void truncatedRangeIsStructuredCorruptionAndTheNextOpenCanRetry() {
        BoundarySource source = new BoundarySource();
        source.truncate = source.data.rootKey;
        BuildOpener opener = opener(source);
        try {
            var failed = opener.open(source.data.ref).join();
            assertEquals(BuildOpenState.CORRUPT, failed.state());
            assertEquals(BuildPart.ROOT, failed.part());
            assertEquals("BUILD_SOURCE_INVALID", failed.code());
            source.truncate = null;
            assertEquals(BuildOpenState.OPEN, opener.open(source.data.ref).join().state());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void failedRangeProofPreservesLayer2RefusalAndObservationTime() {
        BoundarySource source = new BoundarySource();
        source.refuse = source.data.encodingKey;
        BuildOpener opener = opener(source);
        try {
            var failed = opener.open(source.data.ref).join();
            assertEquals(BuildOpenState.CORRUPT, failed.state());
            assertEquals(BuildPart.ENCODING, failed.part());
            assertEquals("OBJECT_STORE_CORRUPT", failed.code());
            assertEquals(Instant.EPOCH, failed.observedAt());
            assertFalse(source.ranges.stream().anyMatch(r -> r.key().equals(source.data.rootKey)));
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void individuallyPresentButMixedEncodedRangesFailTheAggregateBlteIdentity() {
        BoundarySource source = new BoundarySource();
        source.tamper = source.data.rootKey;
        BuildOpener opener = opener(source);
        try {
            var failed = opener.open(source.data.ref).join();
            assertEquals(BuildOpenState.CORRUPT, failed.state());
            assertEquals(BuildPart.ROOT, failed.part());
            assertTrue(
                    failed.code().equals("BLTE_INTEGRITY") || failed.code().equals("BLTE_INVALID"));
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void cancellationWhileWaitingForARangeStopsOnlyThatOpenAndAllowsRetry() throws Exception {
        BoundarySource source = new BoundarySource();
        source.pause = source.data.rootKey;
        BuildOpener opener = opener(source);
        Cancellation token = new Cancellation();
        try {
            var pending = opener.open(source.data.ref, token);
            assertTrue(source.entered.await(5, TimeUnit.SECONDS));
            token.cancel();
            assertTrue(
                    pending.handle(
                                    (v, e) ->
                                            e instanceof java.util.concurrent.CancellationException)
                            .get(5, TimeUnit.SECONDS));
            source.pause = null;
            assertEquals(
                    BuildOpenState.OPEN,
                    opener.open(source.data.ref).get(5, TimeUnit.SECONDS).state());
        } finally {
            source.release.complete(null);
            opener.closeAsync().join();
        }
    }

    private record RangeCall(
            ObjectKey key, Long declaredLength, long offset, int length, FetchPriority priority) {}

    private static final class BoundarySource implements ObjectSource {
        final SyntheticBuild data = new SyntheticBuild();
        final Set<ObjectKey> largeWholeReplies = ConcurrentHashMap.newKeySet();
        final List<RangeCall> ranges = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        volatile ObjectKey truncate, refuse, tamper, pause;
        volatile int largeFullBodiesReturned;

        @Override
        public CompletableFuture<ObjectResult> getKey(
                ObjectRequest request, CdnContext cdn, FetchPriority priority, Cancellation token) {
            try {
                token.check();
                byte[] bytes = data.objects.get(request.key());
                if (bytes != null && bytes.length > WHOLE_BOUNDARY) {
                    largeWholeReplies.add(request.key());
                    return CompletableFuture.completedFuture(
                            new ObjectResult(
                                    request.key(),
                                    ObjectState.PRESENT,
                                    ServedFrom.STORE,
                                    Origin.UNKNOWN,
                                    (long) bytes.length,
                                    null,
                                    null,
                                    null,
                                    null));
                }
                return data.getKey(request, cdn, priority, token)
                        .thenApply(
                                result -> {
                                    if (result.bytes() != null
                                            && result.bytes().length > WHOLE_BOUNDARY) {
                                        largeFullBodiesReturned++;
                                    }
                                    return result;
                                });
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<ObjectResult> getRange(
                ObjectRequest request,
                long offset,
                int length,
                CdnContext cdn,
                FetchPriority priority,
                Cancellation token) {
            try {
                token.check();
                ranges.add(
                        new RangeCall(
                                request.key(), request.declaredLength(), offset, length, priority));
                if (request.key().equals(refuse)) {
                    return CompletableFuture.completedFuture(
                            new ObjectResult(
                                    request.key(),
                                    ObjectState.REFUSED,
                                    null,
                                    null,
                                    null,
                                    null,
                                    "OBJECT_STORE_CORRUPT",
                                    "range proof failed",
                                    Instant.EPOCH));
                }
                byte[] whole = data.objects.get(request.key());
                byte[] body = Arrays.copyOfRange(whole, (int) offset, (int) offset + length);
                if (request.key().equals(truncate)) {
                    body = Arrays.copyOf(body, Math.max(0, body.length - 1));
                }
                if (request.key().equals(tamper) && offset == 0) {
                    body[body.length - 1] ^= 1;
                }
                ObjectResult result =
                        new ObjectResult(
                                request.key(),
                                ObjectState.PRESENT,
                                ServedFrom.STORE,
                                Origin.UNKNOWN,
                                (long) whole.length,
                                body,
                                null,
                                null,
                                null);
                if (!request.key().equals(pause)) {
                    return CompletableFuture.completedFuture(result);
                }
                entered.countDown();
                CompletableFuture<ObjectResult> waiting = new CompletableFuture<>();
                Cancellation.Registration registration =
                        token.onCancel(
                                () ->
                                        waiting.completeExceptionally(
                                                new java.util.concurrent.CancellationException()));
                release.whenComplete(
                        (ignored, error) -> {
                            registration.close();
                            if (error == null) {
                                waiting.complete(result);
                            } else {
                                waiting.completeExceptionally(error);
                            }
                        });
                return waiting;
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<List<ObjectResult>> getKeys(
                List<ObjectRequest> requests,
                CdnContext cdn,
                FetchPriority priority,
                Consumer<ObjectProgress> progress,
                Cancellation token) {
            return data.getKeys(requests, cdn, priority, progress, token);
        }

        @Override
        public CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation token) {
            return data.hasKeys(keys, token);
        }

        @Override
        public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
            return data.countPresent(keys, token);
        }

        @Override
        public CompletableFuture<List<History.Entry>> readHistory(
                History.Query query, Cancellation token) {
            return data.readHistory(query, token);
        }

        @Override
        public CompletableFuture<ObservationResult> observe(
                ObservationRequest request, Cancellation token) {
            return data.observe(request, token);
        }

        @Override
        public CompletableFuture<ObservationResult> record(
                Observation observation, Cancellation token) {
            return data.record(observation, token);
        }
    }
}
