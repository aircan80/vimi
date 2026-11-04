package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
@SuppressWarnings("try")
class WorldStreamProducerCancellationTest {
    @TempDir Path directory;

    @Test
    void onlyExactCleanJdkCancellationNormalizesStoppingProducerFailure() {
        var clean = new java.util.concurrent.CancellationException("normal stop");
        assertTrue(WorldStreamProducerCancellation.cleanCancellation(clean, true));
        assertFalse(WorldStreamProducerCancellation.cleanCancellation(clean, false));
        var cause = new java.util.concurrent.CancellationException("cleanup failed");
        cause.initCause(new java.io.IOException("real cleanup failure"));
        assertFalse(WorldStreamProducerCancellation.cleanCancellation(cause, true));
        var suppressed = new java.util.concurrent.CancellationException("cleanup failed");
        suppressed.addSuppressed(new java.io.IOException("real cleanup failure"));
        assertFalse(WorldStreamProducerCancellation.cleanCancellation(suppressed, true));
        var touched = new AtomicInteger();
        var subclass =
                new java.util.concurrent.CancellationException() {
                    @Override
                    public synchronized Throwable getCause() {
                        touched.incrementAndGet();
                        throw new AssertionError("cause trap");
                    }

                    @Override
                    public String getMessage() {
                        touched.incrementAndGet();
                        throw new AssertionError("message trap");
                    }
                };
        assertFalse(WorldStreamProducerCancellation.cleanCancellation(subclass, true));
        assertEquals(0, touched.get());
    }

    @Test
    void callbackMessageTrapCannotRetireAsSuccessOrPermitAnotherProducer() throws Exception {
        var lane = new WorldStreamProducerCancellation();
        var producer = lane.begin();
        var touched = new AtomicInteger();
        producer.onStopping(
                () -> {
                    throw new RuntimeException() {
                        @Override
                        public String getMessage() {
                            touched.incrementAndGet();
                            throw new AssertionError("message trap");
                        }
                    };
                });
        producer.stop();
        lane.work();
        producer.finished();
        assertEquals(0, touched.get());
        assertNotNull(lane.failure());
        assertTrue(producer.retired());
        assertThrows(IllegalStateException.class, lane::begin);
    }

    @Test
    void tileStopBeforeRegistrationIsAsynchronousOnceAndDestinationExpires() throws Exception {
        var lane = new WorldStreamProducerCancellation();
        var producer = lane.begin();
        var count = new AtomicInteger();
        var invoked = new CompletableFuture<Thread>();
        producer.stop();
        var registration =
                producer.onStopping(
                        () -> {
                            count.incrementAndGet();
                            invoked.complete(Thread.currentThread());
                        });
        assertEquals(0, count.get());
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        lane.work();
                                    } catch (InterruptedException error) {
                                        throw new AssertionError(error);
                                    }
                                });
        try {
            assertFalse(invoked.get(2, TimeUnit.SECONDS) == Thread.currentThread());
            producer.stop();
            producer.stop();
            assertEquals(1, count.get());
            registration.close();
            assertThrows(IllegalStateException.class, () -> producer.onStopping(() -> {}));
            producer.finished();
            assertTrue(producer.retired());
            assertThrows(IllegalStateException.class, () -> producer.onStopping(() -> {}));
        } finally {
            producer.finished();
            lane.stopSession();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertTrue(lane.drained());
    }

    @Test
    void scheduledThenSealedStillRunsButSealBeforeStopDropsCallback() throws Exception {
        for (boolean scheduled : new boolean[] {false, true}) {
            var lane = new WorldStreamProducerCancellation();
            var producer = lane.begin();
            var calls = new AtomicInteger();
            var registration = producer.onStopping(calls::incrementAndGet);
            if (scheduled) {
                producer.stop();
            }
            registration.close();
            producer.stop();
            producer.finished();
            lane.stopSession();
            lane.work();
            assertEquals(scheduled ? 1 : 0, calls.get());
            assertTrue(producer.retired());
            assertTrue(lane.drained());
        }
    }

    @Test
    void finishedProducerAndClosedRegistrationCannotRetireAStillRunningTileCancel()
            throws Exception {
        var lane = new WorldStreamProducerCancellation();
        var producer = lane.begin();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registration =
                producer.onStopping(
                        () -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            }
                        });
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        lane.work();
                                    } catch (InterruptedException error) {
                                        throw new AssertionError(error);
                                    }
                                });
        try {
            producer.stop(); // This tile only: the session itself remains open.
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            registration.close();
            producer.finished();
            assertFalse(producer.retired());
            assertThrows(IllegalStateException.class, lane::begin);
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!producer.retired() && System.nanoTime() < until) {
                Thread.sleep(1);
            }
            assertTrue(producer.retired());
            var next = lane.begin();
            producer.stop(); // Escaped old token cannot cancel the replacement producer.
            var called = new AtomicInteger();
            next.onStopping(called::incrementAndGet).close();
            next.finished();
            assertEquals(0, called.get());
        } finally {
            release.countDown();
            producer.finished();
            lane.stopSession();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
    }

    @Test
    void alreadyStoppedSessionDispatchesLateRegistrationOnlyOnTheWorker() throws Exception {
        var lane = new WorldStreamProducerCancellation();
        var producer = lane.begin();
        lane.stopSession();
        var invoked = new CompletableFuture<Thread>();
        producer.onStopping(() -> invoked.complete(Thread.currentThread()));
        assertFalse(invoked.isDone());
        producer.finished();
        Thread worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        lane.work();
                                    } catch (InterruptedException error) {
                                        throw new AssertionError(error);
                                    }
                                });
        assertFalse(invoked.get(2, TimeUnit.SECONDS) == Thread.currentThread());
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertTrue(producer.retired());
        assertTrue(lane.drained());
    }

    @Test
    void callbackFailureIsDetachedTerminalAndCannotBeReusedAsAnotherProducer() throws Exception {
        var lane = new WorldStreamProducerCancellation();
        var producer = lane.begin();
        producer.onStopping(
                () -> {
                    throw new IllegalStateException("authored request failure");
                });
        lane.stopSession();
        lane.work();
        producer.finished();
        assertNotNull(lane.failure());
        assertTrue(lane.failure().getMessage().length() <= 256);
        assertThrows(IllegalStateException.class, lane::begin);
    }

    @Test
    void
            realSourceTokenHeldLowerCancellationPreventsProducerReuseAndParentCleanupUntilActualFinally()
                    throws Exception {
        var lane = new WorldStreamProducerCancellation();
        var sourceToken = new Cancellation();
        var lowerEntered = new CountDownLatch(1);
        var releaseLower = new CountDownLatch(1);
        var producerReady = new CountDownLatch(1);
        var lowerRetired = new CompletableFuture<Void>();
        var producerEnded = new CountDownLatch(1);
        var callbackThread = new AtomicReference<Thread>();
        var sourceRegistration =
                sourceToken.onCancel(
                        () -> {
                            callbackThread.set(Thread.currentThread());
                            lowerEntered.countDown();
                            try {
                                releaseLower.await();
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            } finally {
                                lowerRetired.complete(null);
                            }
                        });
        var session =
                new WorldStreamEnvelope.Session(
                        new WorldStreamState.Nonce(1, 2), "ab".repeat(32), 1);
        var prepared = WorldStreamPreparedFiles.create(directory, session, null);
        var child = new WorldStreamLaunchOwnerTest.Child();
        var producerRef = new AtomicReference<WorldStreamProducerCancellation.Producer>();
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        args -> child,
                        (process, handle) -> {
                            handle.runWorker("cancel", () -> lane.work(handle::stopping));
                            handle.runWorker(
                                    "producer",
                                    () -> {
                                        var producer = lane.begin();
                                        producerRef.set(producer);
                                        Path partial =
                                                handle.files()
                                                        .directory()
                                                        .resolve("registered-unpublished-partial");
                                        try (var registration =
                                                producer.onStopping(sourceToken::cancel)) {
                                            Files.writeString(partial, "owned producer data");
                                            producerReady.countDown();
                                            lowerRetired
                                                    .join(); // Actual lower callback retirement,
                                            // not Future.cancel.
                                        } finally {
                                            Files.deleteIfExists(partial);
                                            producer.finished();
                                            producerEnded.countDown();
                                        }
                                    });
                        },
                        0,
                        100);
        try {
            assertTrue(producerReady.await(2, TimeUnit.SECONDS));
            var closing = assertTimeoutPreemptively(Duration.ofMillis(500), owner::closeAsync);
            assertTrue(lowerEntered.await(2, TimeUnit.SECONDS));
            assertFalse(callbackThread.get() == Thread.currentThread());
            assertTrue(sourceToken.isCanceled());
            assertFalse(producerRef.get().retired());
            assertThrows(IllegalStateException.class, lane::begin);
            assertEquals(1, producerEnded.getCount());
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> closing.get(2, TimeUnit.SECONDS));
            assertTrue(Files.exists(owner.files().framePath()));
            assertFalse(owner.terminatedAndDrained().isDone());
        } finally {
            releaseLower.countDown();
            lane.stopSession();
            owner.closeAsync();
            owner.terminatedAndDrained().get(4, TimeUnit.SECONDS);
            sourceRegistration.close();
        }
        assertEquals(0, producerEnded.getCount());
        assertTrue(producerRef.get().retired());
        assertFalse(Files.exists(owner.files().directory()));
    }
}
