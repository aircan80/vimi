package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.ObjectState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class DownloadAllJobsTest {
    private static DownloadAllJobs jobs(TrackingSource source, long free, int batch) {
        return new DownloadAllJobs(
                source,
                t -> CompletableFuture.completedFuture(free),
                new DownloadAllJobs.Options(2, batch, 2, 10, Duration.ZERO));
    }

    @Test
    void scansExistingPresenceAndFetchesMissingRowsInArchiveOrder() {
        TrackingSource source = new TrackingSource();
        source.stored.add(TrackingSource.key(1));
        TrackingBuild build = new TrackingBuild(1, new long[] {10, 20, 30}, true);
        build.locate(1, 1, 0);
        build.locate(2, 0, 100);
        DownloadAllJobs jobs = jobs(source, 1000, 2);
        List<DownloadAllJobs.Progress> progress = new CopyOnWriteArrayList<>();
        try {
            var job = jobs.start(build, progress::add);
            var result = job.completion().join();
            assertEquals(DownloadAllJobs.Outcome.COMPLETED, result.outcome());
            assertEquals(List.of(TrackingSource.key(3), TrackingSource.key(2)), source.requested);
            assertEquals(3, result.finalProgress().storedObjects());
            assertEquals(60, result.finalProgress().storedBytes());
            assertEquals(50, result.finalProgress().cdnBytes());
            assertEquals(result.finalProgress(), progress.getLast());
            assertNull(jobs.find(build.build()));
            assertSame(result, jobs.stop(build.build()).join());
        } finally {
            jobs.closeAsync().join();
        }
    }

    @Test
    void insufficientOrUnknownFreeSpacePreventsNetworkFetch() {
        TrackingSource source = new TrackingSource();
        TrackingBuild build = new TrackingBuild(1, new long[] {100}, true);
        DownloadAllJobs jobs = jobs(source, 109, 1);
        try {
            var result = jobs.start(build, null).completion().join();
            assertEquals(DownloadAllJobs.Outcome.DISK_FULL, result.outcome());
            assertEquals("DOWNLOAD_ALL_NOT_ENOUGH_SPACE", result.code());
            assertEquals(0, source.fetchCalls.get());
        } finally {
            jobs.closeAsync().join();
        }
        jobs =
                new DownloadAllJobs(
                        source,
                        t -> CompletableFuture.failedFuture(new IllegalStateException("disk")));
        try {
            assertEquals(
                    DownloadAllJobs.Outcome.STORE_FAILED,
                    jobs.start(build, null).completion().join().outcome());
            assertEquals(0, source.fetchCalls.get());
        } finally {
            jobs.closeAsync().join();
        }
    }

    @Test
    void noRouteNeedsNetworkWithoutScanningOrChangingPresence() {
        TrackingSource source = new TrackingSource();
        DownloadAllJobs jobs = jobs(source, 1000, 2);
        try {
            assertEquals(
                    DownloadAllJobs.Outcome.NEEDS_NETWORK,
                    jobs.start(new TrackingBuild(1, new long[] {10}, false), null)
                            .completion()
                            .join()
                            .outcome());
            assertEquals(0, source.hasCalls.get());
            assertEquals(0, source.fetchCalls.get());
        } finally {
            jobs.closeAsync().join();
        }
    }

    @Test
    void failedObjectsAreCountedExactlyAndRerunRetriesThem() {
        TrackingSource source = new TrackingSource();
        source.outcomes.put(TrackingSource.key(1), ObjectState.NOT_FOUND);
        source.outcomes.put(TrackingSource.key(2), ObjectState.UNSUPPORTED);
        TrackingBuild build = new TrackingBuild(1, new long[] {10, 20, 30}, true);
        DownloadAllJobs jobs = jobs(source, 1000, 2);
        try {
            var result = jobs.start(build, null).completion().join();
            assertEquals(DownloadAllJobs.Outcome.INCOMPLETE, result.outcome());
            assertEquals(1, result.notFound());
            assertEquals(1, result.unsupported());
            assertEquals(2, result.finalProgress().failedObjects());
            assertEquals(1, result.finalProgress().storedObjects());
            source.outcomes.clear();
            assertEquals(
                    DownloadAllJobs.Outcome.COMPLETED,
                    jobs.start(build, null).completion().join().outcome());
            assertEquals(3, source.stored.size());
        } finally {
            jobs.closeAsync().join();
        }
    }

    @Test
    void durableDiskFullFailureWinsOverOtherTerminalStates() {
        TrackingSource source = new TrackingSource();
        source.outcomes.put(TrackingSource.key(1), ObjectState.NEEDS_NETWORK);
        source.outcomes.put(TrackingSource.key(2), ObjectState.STORE_FAILED);
        source.codes.put(TrackingSource.key(2), "OBJECT_STORE_DISK_FULL");
        DownloadAllJobs jobs = jobs(source, 1000, 2);
        try {
            var result =
                    jobs.start(new TrackingBuild(1, new long[] {10, 20}, true), null)
                            .completion()
                            .join();
            assertEquals(DownloadAllJobs.Outcome.DISK_FULL, result.outcome());
            assertEquals("OBJECT_STORE_DISK_FULL", result.code());
        } finally {
            jobs.closeAsync().join();
        }
    }

    @Test
    void cancelKeepsCompletedBatchesAndSameBuildSharesOneJob() throws Exception {
        TrackingSource source = new TrackingSource();
        CompletableFuture<List<com.vibeminer.access.SourceTypes.ObjectResult>> blocked =
                new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        source.fetchOverride =
                requests -> {
                    if (requests.getFirst().key().equals(TrackingSource.key(1))) {
                        source.stored.add(TrackingSource.key(1));
                        return CompletableFuture.completedFuture(
                                List.of(
                                        new com.vibeminer.access.SourceTypes.ObjectResult(
                                                TrackingSource.key(1),
                                                ObjectState.PRESENT,
                                                null,
                                                null,
                                                10L,
                                                null,
                                                null,
                                                null,
                                                null)));
                    }
                    entered.countDown();
                    return blocked;
                };
        TrackingBuild build = new TrackingBuild(1, new long[] {10, 20}, true);
        DownloadAllJobs jobs = jobs(source, 1000, 1);
        try {
            var job = jobs.start(build, null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertSame(job, jobs.start(build, null));
            var result = jobs.stop(build.build()).get(5, TimeUnit.SECONDS);
            assertEquals(DownloadAllJobs.Outcome.CANCELED, result.outcome());
            assertEquals(1, result.finalProgress().storedObjects());
            assertTrue(source.stored.contains(TrackingSource.key(1)));
        } finally {
            blocked.complete(List.of());
            jobs.closeAsync().join();
        }
    }

    @Test
    void slowProgressIsCoalescedAndCompletionWaitsForTheFinalSnapshot() throws Exception {
        TrackingSource source = new TrackingSource();
        DownloadAllJobs jobs = jobs(source, 10000, 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<DownloadAllJobs.Progress> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch fetched = new CountDownLatch(1);
        source.afterFetch =
                () -> {
                    if (source.stored.contains(TrackingSource.key(10))) {
                        fetched.countDown();
                    }
                };
        try {
            var job =
                    jobs.start(
                            new TrackingBuild(1, new long[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, true),
                            p -> {
                                snapshots.add(p);
                                if (snapshots.size() == 1) {
                                    entered.countDown();
                                    await(release);
                                }
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(fetched.await(5, TimeUnit.SECONDS));
            assertFalse(job.completion().isDone());
            release.countDown();
            var result = job.completion().join();
            assertEquals(result.finalProgress(), snapshots.getLast());
            assertTrue(snapshots.size() <= 8);
        } finally {
            release.countDown();
            jobs.closeAsync().join();
        }
    }

    @Test
    void listenerFailuresDoNotStopJobsAndCloseRefusesNewJobs() {
        TrackingSource source = new TrackingSource();
        DownloadAllJobs jobs = jobs(source, 1000, 2);
        TrackingBuild build = new TrackingBuild(1, new long[] {10}, true);
        assertEquals(
                DownloadAllJobs.Outcome.COMPLETED,
                jobs.start(
                                build,
                                p -> {
                                    throw new AssertionError("UI");
                                })
                        .completion()
                        .join()
                        .outcome());
        jobs.closeAsync().join();
        assertThrows(IllegalStateException.class, () -> jobs.start(build, null));
    }

    @Test
    void primitiveFetchOrderSortsManyEqualAndDifferentArchivePositions() {
        TrackingSource source = new TrackingSource();
        long[] sizes = new long[257];
        java.util.Arrays.fill(sizes, 1);
        TrackingBuild build = new TrackingBuild(1, sizes, true);
        java.util.Random random = new java.util.Random(42);
        java.util.Map<com.vibeminer.network.CdnKey, long[]> positions = new java.util.HashMap<>();
        for (int row = 0; row < sizes.length; row++) {
            int archive = random.nextInt(3);
            long offset = random.nextInt(20);
            build.locate(row, archive, offset);
            positions.put(TrackingBuild.key(row + 1), new long[] {archive, offset, row});
        }
        DownloadAllJobs jobs = jobs(source, 10000, 257);
        try {
            assertEquals(
                    DownloadAllJobs.Outcome.COMPLETED,
                    jobs.start(build, null).completion().join().outcome());
            var expected =
                    positions.keySet().stream()
                            .sorted(
                                    java.util.Comparator.comparingLong(
                                                    (com.vibeminer.network.CdnKey key) ->
                                                            positions.get(key)[0])
                                            .thenComparingLong(key -> positions.get(key)[1])
                                            .thenComparingLong(key -> positions.get(key)[2]))
                            .map(
                                    key ->
                                            new com.vibeminer.store.ObjectKey(
                                                    com.vibeminer.store.ObjectKey.ENCODED,
                                                    key.bytes()))
                            .toList();
            assertEquals(expected, source.requested);
        } finally {
            jobs.closeAsync().join();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
