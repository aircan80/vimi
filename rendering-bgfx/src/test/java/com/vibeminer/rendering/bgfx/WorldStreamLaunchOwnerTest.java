package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class WorldStreamLaunchOwnerTest {
    @TempDir Path directory;
    private static final WorldStreamEnvelope.Session SESSION =
            new WorldStreamEnvelope.Session(new WorldStreamState.Nonce(10, 20), "ab".repeat(32), 1);

    @Test
    void actualExitDuringAdmittedAttachmentStillRegistersAndDrainsItsBoundedWorkers()
            throws Exception {
        var child = new Child();
        var admitted = new CountDownLatch(1);
        var releaseAttachment = new CountDownLatch(1);
        var lateEntered = new CountDownLatch(1);
        var releaseLate = new CountDownLatch(1);
        var owner =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> child,
                        (process, handle) -> {
                            handle.runWorker("first", () -> {});
                            admitted.countDown();
                            releaseAttachment.await();
                            handle.runWorker(
                                    "late-drain",
                                    () -> {
                                        lateEntered.countDown();
                                        releaseLate.await();
                                    });
                        },
                        0,
                        3000);
        try {
            assertTrue(admitted.await(2, TimeUnit.SECONDS));
            child.exit(0);
            owner.childExited().get(2, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!owner.stopping() && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertTrue(owner.stopping());
            assertThrows(
                    IllegalStateException.class,
                    () -> owner.runWorker("foreign-after-stop", () -> {}));
            assertFalse(owner.terminatedAndDrained().isDone());
            releaseAttachment.countDown();
            assertTrue(
                    lateEntered.await(2, TimeUnit.SECONDS),
                    "Already admitted attachment must finish registering its actual workers");
            assertFalse(
                    owner.terminatedAndDrained().isDone(),
                    "Actual exit is not completion of the late registered worker");
            assertTrue(Files.exists(owner.files().framePath()));
            releaseLate.countDown();
            owner.terminatedAndDrained().get(3, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNull(owner.failure());
            assertFalse(Files.exists(owner.files().directory()));
        } finally {
            releaseAttachment.countDown();
            releaseLate.countDown();
            owner.closeAsync();
            owner.terminatedAndDrained().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void hostileStarterAndWorkerMessagesCannotSuppressFailureOrClose() throws Exception {
        var touched = new AtomicBoolean();
        var unknown =
                new RuntimeException() {
                    @Override
                    public String getMessage() {
                        touched.set(true);
                        throw new AssertionError("message trap");
                    }
                };
        var noChild =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> {
                            throw unknown;
                        },
                        (process, handle) -> {},
                        0,
                        1000);
        assertTrue(noChild.terminatedAndDrained().get(3, TimeUnit.SECONDS).childPid().isEmpty());
        assertFalse(touched.get());
        assertNotNull(noChild.failure());
        assertTrue(noChild.failure().getMessage().contains("WorldStreamLaunchOwnerTest"));
        assertFalse(Files.exists(noChild.files().directory()));

        var child = new Child();
        var worker =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> child,
                        (process, handle) ->
                                handle.runWorker(
                                        "hostile-failure",
                                        () -> {
                                            throw new java.io.IOException() {
                                                @Override
                                                public String getMessage() {
                                                    touched.set(true);
                                                    throw new AssertionError("worker message trap");
                                                }
                                            };
                                        }),
                        0,
                        1000);
        assertEquals(
                0, worker.terminatedAndDrained().get(3, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertFalse(touched.get());
        assertNotNull(worker.failure());
        assertFalse(Files.exists(worker.files().directory()));
    }

    @Test
    void confirmedExitPrecedesHeldWorkerDrainButIsNeverInferredFromCleanupFailure()
            throws Exception {
        var release = new CountDownLatch(1);
        var attached = new CountDownLatch(1);
        var child = new Child();
        var owner =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> child,
                        (process, handle) -> {
                            handle.runWorker("held-exit", release::await);
                            attached.countDown();
                        },
                        0,
                        1000);
        try {
            assertTrue(attached.await(2, TimeUnit.SECONDS));
            owner.closeAsync();
            owner.childExited().get(2, TimeUnit.SECONDS);
            assertFalse(child.isAlive());
            assertFalse(owner.terminatedAndDrained().isDone());
            assertTrue(Files.exists(owner.files().framePath()));
        } finally {
            release.countDown();
        }
        owner.terminatedAndDrained().get(3, TimeUnit.SECONDS);
    }

    @Test
    void startupDeadlineCoversHeldStarterWithoutInventingDrainAndAcknowledgementDisarmsIt()
            throws Exception {
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> {
                            entered.countDown();
                            release.await();
                            throw new java.io.IOException("authored no-child start");
                        },
                        (process, handle) -> {},
                        0,
                        100,
                        50);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!owner.stopping() && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertTrue(owner.stopping());
            assertTrue(owner.failure().getMessage().contains("initialization deadline"));
            assertFalse(owner.terminatedAndDrained().isDone());
            assertTrue(Files.exists(owner.files().framePath()));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> owner.closeAsync().get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertTrue(owner.terminatedAndDrained().get(3, TimeUnit.SECONDS).childPid().isEmpty());
        assertFalse(Files.exists(owner.files().directory()));

        var attached = new CountDownLatch(1);
        var ready =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> new Child(),
                        (process, handle) -> {
                            handle.initialized();
                            attached.countDown();
                        },
                        0,
                        1000,
                        100);
        assertTrue(attached.await(2, TimeUnit.SECONDS));
        Thread.sleep(160);
        assertFalse(ready.stopping());
        ready.closeAsync().get(3, TimeUnit.SECONDS);
        assertFalse(Files.exists(ready.files().directory()));
    }

    @Test
    void heldStarterCloseOldPreparationAndBoundedFailureNeverDeleteBeforeLateChildExit()
            throws Exception {
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, "vulkan");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var child = new Child();
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> {
                            entered.countDown();
                            release.await();
                            return child;
                        },
                        (process, handle) -> {},
                        0,
                        100);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var closing = assertTimeoutPreemptively(Duration.ofMillis(500), owner::closeAsync);
        prepared.closePreparationAsync().get(1, TimeUnit.SECONDS);
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> closing.get(2, TimeUnit.SECONDS));
        assertFalse(owner.terminatedAndDrained().isDone());
        assertTrue(Files.exists(owner.files().framePath()));
        release.countDown();
        var receipt = owner.terminatedAndDrained().get(3, TimeUnit.SECONDS);
        assertFalse(child.isAlive());
        assertEquals(919, receipt.childPid().orElseThrow());
        assertFalse(Files.exists(owner.files().directory()));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> owner.closeAsync().get());
    }

    @Test
    void starterFailureIsNoChildDrainAndAttachmentFailureStillOwnsReturnedProcess()
            throws Exception {
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var noChild =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> {
                            throw new java.io.IOException("authored pre-child failure");
                        },
                        (process, handle) -> {},
                        0,
                        1000);
        var empty = noChild.terminatedAndDrained().get(3, TimeUnit.SECONDS);
        assertTrue(empty.childPid().isEmpty());
        assertTrue(empty.exitCode().isEmpty());
        assertNotNull(noChild.failure());
        assertFalse(Files.exists(noChild.files().directory()));

        var child = new Child();
        var next = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var failed =
                WorldStreamLaunchOwner.launch(
                        next,
                        arguments -> child,
                        (process, handle) -> {
                            Path scratch =
                                    handle.files().directory().resolve("unreturned-local-output");
                            try {
                                Files.writeString(scratch, "authored setup");
                                throw new java.io.IOException("authored post-child setup failure");
                            } finally {
                                Files.deleteIfExists(
                                        scratch); // Callback owns unreturned resources.
                            }
                        },
                        0,
                        1000);
        var receipt = failed.terminatedAndDrained().get(3, TimeUnit.SECONDS);
        assertEquals(919, receipt.childPid().orElseThrow());
        assertNotNull(failed.failure());
        assertFalse(child.isAlive());
        assertFalse(Files.exists(failed.files().directory()));
    }

    @Test
    void actualWorkerFinallyAndBlockedStreamCloseBothKeepFilesUntilTheyDrain() throws Exception {
        var child = new Child();
        var workerEntered = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var workerFinally = new AtomicBoolean();
        var closeEntered = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        child.output =
                new ByteArrayOutputStream() {
                    @Override
                    public void close() {
                        closeEntered.countDown();
                        try {
                            releaseClose.await();
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                    }
                };
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> child,
                        (process, handle) ->
                                handle.runWorker(
                                        "held-writer",
                                        () -> {
                                            workerEntered.countDown();
                                            try {
                                                releaseWorker.await();
                                            } finally {
                                                workerFinally.set(true);
                                            }
                                        }),
                        0,
                        100);
        try {
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            var closing = owner.closeAsync();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> closing.get(2, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
            assertFalse(workerFinally.get());
            assertEquals(
                    1, closeEntered.getCount()); // No stream close against a still-running worker.
            assertTrue(Files.exists(owner.files().framePath()));
            releaseWorker.countDown();
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            assertTrue(workerFinally.get());
            assertFalse(owner.terminatedAndDrained().isDone());
            assertTrue(Files.exists(owner.files().framePath()));
            releaseClose.countDown();
            owner.terminatedAndDrained().get(3, TimeUnit.SECONDS);
            assertFalse(Files.exists(owner.files().directory()));
        } finally {
            releaseWorker.countDown();
            releaseClose.countDown();
            owner.closeAsync();
            owner.terminatedAndDrained().get(4, TimeUnit.SECONDS);
        }
    }

    @Test
    void unkillableChildKeepsOwnershipAndCanceledFutureCannotForgeActualDrain() throws Exception {
        var child = new Child();
        child.killAllowed = false;
        var attached = new CountDownLatch(1);
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> child,
                        (process, handle) -> attached.countDown(),
                        0,
                        100);
        assertTrue(attached.await(2, TimeUnit.SECONDS));
        var canceledCopy = owner.terminatedAndDrained();
        canceledCopy.cancel(true);
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> owner.closeAsync().get(2, TimeUnit.SECONDS));
        assertTrue(child.isAlive());
        assertFalse(owner.terminatedAndDrained().isDone());
        assertTrue(Files.exists(owner.files().framePath()));
        child.exit(7);
        var receipt = owner.terminatedAndDrained().get(4, TimeUnit.SECONDS);
        assertEquals(7, receipt.exitCode().orElseThrow());
        assertFalse(Files.exists(owner.files().directory()));
    }

    @Test
    @Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
    void realOwnedJavaChildExitsThroughHandleBeforeMappingAndWorkerCleanup() throws Exception {
        var actual = new java.util.concurrent.atomic.AtomicReference<Process>();
        var ready = new CountDownLatch(1);
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> {
                            var child =
                                    new ProcessBuilder(
                                                    WorldStreamTestChildren.command(
                                                            WorldStreamTestChildren.Wait.class))
                                            .start();
                            actual.set(child);
                            return child;
                        },
                        (process, handle) ->
                                handle.runWorker(
                                        "actual-stdout",
                                        () -> {
                                            try (var reader = process.inputReader()) {
                                                assertEquals("READY", reader.readLine());
                                                ready.countDown();
                                                while (reader.readLine() != null) {
                                                    // The authored child emits only READY and waits
                                                    // for termination.
                                                }
                                            }
                                        }),
                        0,
                        5000);
        try {
            // READY or the child's exit ends the wait; the guard only stops a genuine hang.
            long until =
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(
                                    WorldStreamTestChildren.STARTUP_GUARD_MILLIS);
            while (!ready.await(20, TimeUnit.MILLISECONDS)) {
                var started = actual.get();
                assertTrue(
                        started == null || started.isAlive(),
                        () -> "Child exited with " + started.exitValue() + " before READY");
                assertTrue(System.nanoTime() - until < 0, "Child not READY within the guard");
            }
            long pid = actual.get().pid();
            var receipt = owner.closeAsync().get(5, TimeUnit.SECONDS);
            assertEquals(pid, receipt.childPid().orElseThrow());
            assertFalse(actual.get().isAlive());
            assertFalse(Files.exists(owner.files().directory()));
        } finally {
            var child = actual.get();
            if (child != null && child.isAlive()) {
                child.toHandle().destroyForcibly();
                assertTrue(child.waitFor(30, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void workerRegistrationHasFixedSixSlotsAndCannotContinueAfterAttachmentOrClose()
            throws Exception {
        var child = new Child();
        var release = new CountDownLatch(1);
        var checked = new CompletableFuture<Void>();
        var prepared = WorldStreamPreparedFiles.create(directory, SESSION, null);
        var owner =
                WorldStreamLaunchOwner.launch(
                        prepared,
                        arguments -> child,
                        (process, handle) -> {
                            try {
                                for (int i = 0; i < WorldStreamLaunchOwner.MAX_WORKERS; i++) {
                                    handle.runWorker("bounded-" + i, release::await);
                                }
                                assertThrows(
                                        IllegalStateException.class,
                                        () -> handle.runWorker("one-too-many", () -> {}));
                                checked.complete(null);
                            } catch (Throwable error) {
                                checked.completeExceptionally(error);
                                throw error;
                            }
                        },
                        0,
                        1000);
        try {
            checked.get(3, TimeUnit.SECONDS);
            owner.closeAsync();
            assertThrows(IllegalStateException.class, () -> owner.runWorker("late", () -> {}));
        } finally {
            release.countDown();
            owner.closeAsync();
            owner.terminatedAndDrained().get(3, TimeUnit.SECONDS);
        }
    }

    static final class Child extends Process {
        private final CompletableFuture<Process> exited = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile int code;
        volatile boolean killAllowed = true;
        volatile boolean observesExit = true;
        volatile OutputStream output = new ByteArrayOutputStream();
        volatile InputStream input = new ByteArrayInputStream(new byte[0]);
        volatile InputStream error = new ByteArrayInputStream(new byte[0]);
        final java.util.concurrent.CountDownLatch errorRequested =
                new java.util.concurrent.CountDownLatch(1);
        private final ProcessHandle handle =
                new ProcessHandle() {
                    @Override
                    public long pid() {
                        return 919;
                    }

                    @Override
                    public Optional<ProcessHandle> parent() {
                        return Optional.empty();
                    }

                    @Override
                    public Stream<ProcessHandle> children() {
                        return Stream.empty();
                    }

                    @Override
                    public Stream<ProcessHandle> descendants() {
                        return Stream.empty();
                    }

                    @Override
                    public Info info() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public CompletableFuture<ProcessHandle> onExit() {
                        return exited.thenApply(ignored -> this);
                    }

                    @Override
                    public boolean supportsNormalTermination() {
                        return true;
                    }

                    @Override
                    public boolean destroy() {
                        if (killAllowed) {
                            exit(0);
                        }
                        return killAllowed;
                    }

                    @Override
                    public boolean destroyForcibly() {
                        return destroy();
                    }

                    @Override
                    public boolean isAlive() {
                        return alive.get();
                    }

                    @Override
                    public int compareTo(ProcessHandle other) {
                        return Long.compare(pid(), other.pid());
                    }
                };

        void exit(int value) {
            code = value;
            alive.set(false);
            exited.complete(this);
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            errorRequested.countDown();
            return error;
        }

        @Override
        public int waitFor() {
            return exited.join().exitValue();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException();
            }
            return code;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void destroy() {
            throw new AssertionError("Use ProcessHandle, not stream-closing Process.destroy");
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            if (!observesExit) {
                throw new UnsupportedOperationException("authored unavailable exit observation");
            }
            return exited.copy();
        }

        @Override
        public long pid() {
            return 919;
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    @Test
    void startupRunsOutOnQuietOrAtTheCapNotWhileTheChildShowsProgress() {
        long q = TimeUnit.MILLISECONDS.toNanos(100);
        long start = 1_000_000_000L;
        assertEquals(
                null, WorldStreamLaunchOwner.Handle.startupExpired(start, start, start + q - 1, q));
        assertTrue(
                WorldStreamLaunchOwner.Handle.startupExpired(start, start, start + q, q)
                        .contains("no sign of progress"));
        // Progress restarts the quiet window, up to the cap of four windows.
        assertEquals(
                null,
                WorldStreamLaunchOwner.Handle.startupExpired(
                        start, start + 3 * q, start + 3 * q + q / 2, q));
        assertTrue(
                WorldStreamLaunchOwner.Handle.startupExpired(
                                start, start + 4 * q - 1, start + 4 * q, q)
                        .contains("still not ready"));
        // A progress time older than the start (set before launch) counts from the start.
        assertEquals(
                null,
                WorldStreamLaunchOwner.Handle.startupExpired(
                        start, start - 5 * q, start + q / 2, q));
    }

    @Test
    void aChildThatKeepsShowingProgressOutlastsTheQuietWindowAndAHungOneDoesNot() throws Exception {
        // Ready after 600 ms with progress every 20 ms: more than twice the 250 ms quiet window,
        // with room for scheduling jitter both within a window and before the 1 s cap.
        var ready = new CountDownLatch(1);
        var busy =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> new Child(),
                        (process, handle) ->
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    long until =
                                                            System.nanoTime()
                                                                    + TimeUnit.MILLISECONDS.toNanos(
                                                                            600);
                                                    while (System.nanoTime() < until) {
                                                        handle.progress();
                                                        try {
                                                            Thread.sleep(20);
                                                        } catch (InterruptedException e) {
                                                            return;
                                                        }
                                                    }
                                                    handle.initialized();
                                                    ready.countDown();
                                                }),
                        0,
                        1000,
                        250);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertFalse(busy.stopping(), "a slow child that shows progress starts");
        busy.closeAsync().get(3, TimeUnit.SECONDS);
        // Progress forever without becoming ready: stopped at the cap of four windows.
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var spinning =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> new Child(),
                        (process, handle) ->
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    while (!stop.get()) {
                                                        handle.progress();
                                                        try {
                                                            Thread.sleep(10);
                                                        } catch (InterruptedException e) {
                                                            return;
                                                        }
                                                    }
                                                }),
                        0,
                        1000,
                        100);
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!spinning.stopping() && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertTrue(spinning.stopping(), "a child that never becomes ready is stopped");
            assertTrue(
                    spinning.failure().getMessage().contains("still not ready"),
                    spinning.failure().getMessage());
        } finally {
            stop.set(true);
        }
        try {
            spinning.closeAsync().get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
            // The startup failure.
        }
    }

    @Test
    void onlyAFinishedStderrLineCountsAsProgress() {
        byte[] partial = "World v2 rend".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] line = "erer: Vulkan\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertFalse(WorldStreamTransport.Session.endsALine(partial, partial.length));
        assertTrue(WorldStreamTransport.Session.endsALine(line, line.length));
        assertFalse(
                WorldStreamTransport.Session.endsALine(line, line.length - 1),
                "only the read part");
    }

    @Test
    void aReplyTimesOutOnlyAfterTheQuietTimeSinceItWasOwedOrTheLastProgress() {
        long q = TimeUnit.SECONDS.toNanos(30);
        long owed = 5_000_000_000L;
        assertEquals(
                null,
                WorldStreamLaunchOwner.Handle.repliesExpired(
                        "1 command", owed, 0, owed + q - 1, q));
        String reason =
                WorldStreamLaunchOwner.Handle.repliesExpired(
                        "1 command (a commit)", owed, 0, owed + q, q);
        assertTrue(
                reason.contains("no sign of progress for 30000 ms") && reason.contains("a commit"),
                reason);
        // Progress after the reply was owed restarts the quiet time; progress before it does not.
        assertEquals(
                null,
                WorldStreamLaunchOwner.Handle.repliesExpired("x", owed, owed + q / 2, owed + q, q));
        assertTrue(
                WorldStreamLaunchOwner.Handle.repliesExpired("x", owed, owed - q, owed + q, q)
                        != null);
        assertEquals(30_000, WorldStreamLaunchOwner.Handle.replyQuietMillis(null));
        assertEquals(30_000, WorldStreamLaunchOwner.Handle.replyQuietMillis("50"));
        assertEquals(30_000, WorldStreamLaunchOwner.Handle.replyQuietMillis("x"));
        assertEquals(5_000, WorldStreamLaunchOwner.Handle.replyQuietMillis(" 5000 "));
    }

    @Test
    void aChildThatOwesAReplyAndStopsShowingProgressIsFailedAndAProgressingOneIsNot()
            throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var attached = new CountDownLatch(1);
        var handles =
                new java.util.concurrent.atomic.AtomicReference<WorldStreamLaunchOwner.Handle>();
        var owner =
                WorldStreamLaunchOwner.launch(
                        WorldStreamPreparedFiles.create(directory, SESSION, null),
                        arguments -> new Child(),
                        (process, handle) -> {
                            handle.clockForTest(now::get);
                            handle.initialized();
                            handles.set(handle);
                            attached.countDown();
                        },
                        0,
                        1000,
                        0);
        assertTrue(attached.await(2, TimeUnit.SECONDS));
        var handle = handles.get();
        var owed = new java.util.concurrent.atomic.AtomicReference<String>();
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var watcher =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        handle.watchReplies(
                                                () -> {
                                                    polls.incrementAndGet();
                                                    return owed.get();
                                                },
                                                30_000,
                                                TimeUnit.MILLISECONDS.toNanos(1)));
        // Nothing owed: an hour of quiet is fine.
        now.addAndGet(TimeUnit.HOURS.toNanos(1));
        awaitPolls(polls, 5);
        assertFalse(owner.stopping());
        // Owed, with progress every 10 s for two minutes: still fine.
        owed.set("1 command (a commit)");
        awaitPolls(polls, 5);
        for (int i = 0; i < 12; i++) {
            now.addAndGet(TimeUnit.SECONDS.toNanos(10));
            handle.progress();
            awaitPolls(polls, 3);
        }
        assertFalse(owner.stopping(), "a slow child that keeps showing progress is not failed");
        // Then 30 s without progress: failed, naming what it owed.
        now.addAndGet(TimeUnit.SECONDS.toNanos(30));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!owner.stopping() && System.nanoTime() < until) {
            Thread.sleep(2);
        }
        assertTrue(owner.stopping(), "a hung child is failed");
        assertTrue(
                owner.failure().getMessage().contains("stopped answering"),
                owner.failure().getMessage());
        assertTrue(owner.failure().getMessage().contains("a commit"), owner.failure().getMessage());
        watcher.join(5_000);
        assertFalse(watcher.isAlive(), "the watcher ends with the session");
        try {
            owner.closeAsync().get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
            // The liveness failure.
        }
    }

    private static void awaitPolls(java.util.concurrent.atomic.AtomicInteger polls, int more)
            throws InterruptedException {
        int target = polls.get() + more;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (polls.get() < target && System.nanoTime() < until) {
            Thread.sleep(1);
        }
    }

    @Test
    void nothingIsOwedOnceTheSessionIsClosingAndWhatIsOwedIsNamed() {
        assertEquals(null, WorldStreamTransport.Session.owed(false, 0, false, false, false, false));
        assertEquals(
                null,
                WorldStreamTransport.Session.owed(true, 3, true, true, false, true),
                "a slow teardown after CLOSE is not a hang");
        assertEquals(
                "1 command (a commit)",
                WorldStreamTransport.Session.owed(false, 1, true, false, false, false));
        assertEquals(
                "3 commands (a commit, a tile upload, late objects)",
                WorldStreamTransport.Session.owed(false, 3, true, true, true, true));
        assertEquals(
                "1 command (a tile cancellation)",
                WorldStreamTransport.Session.owed(false, 1, false, false, true, false));
        assertEquals(
                "2 commands",
                WorldStreamTransport.Session.owed(false, 2, false, false, false, false));
    }

    @Test
    void theChildBeatsWhileItStepsOrPresentsAndNotWhenAStepIsStuck() {
        long beat = WorldStreamSession.HEARTBEAT_NANOS;
        long now = 100 * beat;
        assertTrue(WorldStreamSession.beating(false, now - beat, now), "a step finished recently");
        assertFalse(
                WorldStreamSession.beating(false, now - 2 * beat, now),
                "a step stuck for two beats");
        assertTrue(
                WorldStreamSession.beating(true, now - 60 * beat, now),
                "a present that blocks (a minimised window) is not a hang");
    }
}
