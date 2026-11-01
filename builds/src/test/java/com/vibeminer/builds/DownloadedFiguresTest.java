package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class DownloadedFiguresTest {
    @Test
    void initialMeasurementIsBatchedAndCachedByBuildInstance() {
        TrackingSource source = new TrackingSource();
        source.stored.add(TrackingSource.key(2));
        TrackingBuild build = new TrackingBuild(1, new long[] {10, 20, 30}, true);
        DownloadedFigures figures =
                new DownloadedFigures(source, null, new DownloadedFigures.Options(2, 1, 8, 2));
        try {
            var measured = figures.get(build, Cancellation.NONE).join();
            assertEquals(new DownloadedFigures.Figure(1, 3, 20, 60), measured);
            assertEquals(1.0 / 3, measured.share());
            assertEquals(2, source.hasCalls.get());
            figures.get(build, Cancellation.NONE).join();
            assertEquals(2, source.hasCalls.get());
            TrackingBuild replacement = new TrackingBuild(1, new long[] {10, 20, 30}, true);
            figures.get(replacement, Cancellation.NONE).join();
            assertEquals(4, source.hasCalls.get());
        } finally {
            figures.closeAsync().join();
        }
    }

    @Test
    void sharedWaitersCancelIndependentlyAndAbandonedMeasurementCanBeRetried() throws Exception {
        TrackingSource source = new TrackingSource();
        CompletableFuture<List<Boolean>> gate = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        source.hasOverride =
                keys -> {
                    entered.countDown();
                    return gate;
                };
        DownloadedFigures figures = new DownloadedFigures(source);
        TrackingBuild build = new TrackingBuild(1, new long[] {10}, true);
        try {
            Cancellation token = new Cancellation();
            var one = figures.get(build, token);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var two = figures.get(build, Cancellation.NONE);
            token.cancel();
            assertTrue(
                    one.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .join());
            assertFalse(two.isDone());
            gate.complete(List.of(true));
            assertEquals(1, two.join().storedObjects());
            assertEquals(1, source.hasCalls.get());
        } finally {
            gate.complete(List.of(true));
            figures.closeAsync().join();
        }
    }

    @Test
    void commitsOverrideStalePresenceAnswersDuringMeasurement() throws Exception {
        TrackingSource source = new TrackingSource();
        CompletableFuture<List<Boolean>> gate = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1), applied = new CountDownLatch(1);
        source.hasOverride =
                keys -> {
                    entered.countDown();
                    return gate;
                };
        DownloadedFigures figures =
                new DownloadedFigures(source, source.commits, new DownloadedFigures.Options());
        figures.commitProbe = applied::countDown;
        try {
            var measured =
                    figures.get(new TrackingBuild(1, new long[] {10}, true), Cancellation.NONE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            source.commits.publish(List.of(TrackingSource.key(1)), List.of());
            assertTrue(applied.await(5, TimeUnit.SECONDS));
            gate.complete(List.of(false));
            assertEquals(1, measured.join().storedObjects());
            assertEquals(10, measured.join().storedBytes());
        } finally {
            gate.complete(List.of(false));
            figures.closeAsync().join();
        }
    }

    @Test
    void laterRefreshWinsOverAnOlderMeasurementFinishingLast() throws Exception {
        TrackingSource source = new TrackingSource();
        CompletableFuture<List<Boolean>> gate = new CompletableFuture<>();
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        source.hasOverride =
                keys -> {
                    if (calls.getAndIncrement() == 0) {
                        first.countDown();
                        return gate;
                    }
                    return CompletableFuture.completedFuture(List.of(true));
                };
        DownloadedFigures figures = new DownloadedFigures(source);
        TrackingBuild build = new TrackingBuild(1, new long[] {10}, true);
        try {
            var older = figures.get(build, Cancellation.NONE);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertEquals(1, figures.refresh(build, Cancellation.NONE).join().storedObjects());
            gate.complete(List.of(false));
            assertEquals(1, older.join().storedObjects());
            assertEquals(1, figures.get(build, Cancellation.NONE).join().storedObjects());
        } finally {
            gate.complete(List.of(false));
            figures.closeAsync().join();
        }
    }

    @Test
    void missedCommitQueueRemeasuresInsteadOfKeepingAnIncompleteTally() throws Exception {
        TrackingSource source = new TrackingSource();
        DownloadedFigures figures =
                new DownloadedFigures(
                        source, source.commits, new DownloadedFigures.Options(4096, 4, 1, 2));
        TrackingBuild build = new TrackingBuild(1, new long[] {10, 20, 30}, true);
        CountDownLatch blocked = new CountDownLatch(1),
                release = new CountDownLatch(1),
                fresh = new CountDownLatch(1);
        try {
            figures.get(build, Cancellation.NONE).join();
            figures.onChanged(
                    (b, f) -> {
                        if (f.storedObjects() == 1) {
                            blocked.countDown();
                            await(release);
                        }
                        if (f.storedObjects() == 3) {
                            fresh.countDown();
                        }
                    });
            source.stored.add(TrackingSource.key(1));
            source.commits.publish(List.of(TrackingSource.key(1)), List.of());
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            source.stored.add(TrackingSource.key(2));
            source.stored.add(TrackingSource.key(3));
            source.commits.publish(List.of(TrackingSource.key(2)), List.of());
            source.commits.publish(List.of(TrackingSource.key(3)), List.of());
            release.countDown();
            assertTrue(fresh.await(5, TimeUnit.SECONDS));
            assertEquals(3, figures.get(build, Cancellation.NONE).join().storedObjects());
            assertTrue(source.hasCalls.get() >= 2);
        } finally {
            release.countDown();
            figures.closeAsync().join();
        }
    }

    @Test
    void closeCancelsAllMeasurementsAndRefusesNewOnes() throws Exception {
        TrackingSource source = new TrackingSource();
        CompletableFuture<List<Boolean>> gate = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        source.hasOverride =
                keys -> {
                    entered.countDown();
                    return gate;
                };
        DownloadedFigures figures = new DownloadedFigures(source);
        TrackingBuild build = new TrackingBuild(1, new long[] {10}, true);
        var pending = figures.get(build, Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        figures.closeAsync().get(5, TimeUnit.SECONDS);
        assertTrue(pending.handle((v, e) -> e != null).join());
        assertThrows(IllegalStateException.class, () -> figures.get(build, Cancellation.NONE));
        gate.complete(List.of(false));
    }

    @Test
    void measurementSubscriberCanRefreshWithASingleWorker() {
        TrackingSource source = new TrackingSource();
        TrackingBuild build = new TrackingBuild(1, new long[] {10}, true);
        DownloadedFigures figures =
                new DownloadedFigures(source, null, new DownloadedFigures.Options(4096, 4, 8, 1));
        java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            figures.onChanged(
                    (b, figure) -> {
                        if (first.compareAndSet(true, false)) {
                            figures.refresh(build, Cancellation.NONE).join();
                        }
                    });
            assertEquals(0, figures.get(build, Cancellation.NONE).join().storedObjects());
            assertEquals(2, source.hasCalls.get());
        } finally {
            figures.closeAsync().join();
        }
    }

    @Test
    void subscriberErrorsDoNotFailMeasurementsOrKillCommitPump() throws Exception {
        TrackingSource source = new TrackingSource();
        TrackingBuild build = new TrackingBuild(1, new long[] {10}, true);
        DownloadedFigures figures =
                new DownloadedFigures(source, source.commits, new DownloadedFigures.Options());
        CountDownLatch committed = new CountDownLatch(1);
        figures.commitProbe = committed::countDown;
        try {
            figures.onChanged(
                    (b, figure) -> {
                        throw new AssertionError("subscriber");
                    });
            assertEquals(0, figures.get(build, Cancellation.NONE).join().storedObjects());
            source.commits.publish(List.of(TrackingSource.key(1)), List.of());
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            assertEquals(1, figures.get(build, Cancellation.NONE).join().storedObjects());
        } finally {
            figures.closeAsync().join();
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
