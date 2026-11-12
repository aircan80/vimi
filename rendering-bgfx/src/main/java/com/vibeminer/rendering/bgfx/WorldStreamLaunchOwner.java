package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Child and local-worker lifetime only. No protocol success, source evidence or arbiter exists
 * here.
 */
final class WorldStreamLaunchOwner {
    @FunctionalInterface
    interface Starter {
        Process start(List<String> arguments) throws Exception;
    }

    @FunctionalInterface
    interface Attachment {
        void attach(Process process, Handle owner) throws Exception;
    }

    @FunctionalInterface
    interface Worker {
        void run() throws Exception;
    }

    static final class Drain {
        private final long pid;
        private final int exit;

        private Drain(Process process) {
            pid = process == null ? 0 : process.pid();
            exit = process == null ? 0 : process.exitValue();
        }

        OptionalLong childPid() {
            return pid == 0 ? OptionalLong.empty() : OptionalLong.of(pid);
        }

        OptionalInt exitCode() {
            return pid == 0 ? OptionalInt.empty() : OptionalInt.of(exit);
        }
    }

    /**
     * Transport workers: commands, statuses, stderr, files, frames, cancellation, objects and
     * liveness.
     */
    static final int MAX_WORKERS = 8;

    private WorldStreamLaunchOwner() {}

    static Handle launch(
            WorldStreamPreparedFiles prepared, Starter starter, Attachment attachment) {
        return launch(prepared, starter, attachment, 250, 5000);
    }

    static Handle launch(
            WorldStreamPreparedFiles prepared,
            Starter starter,
            Attachment attachment,
            long graceMillis,
            long deadlineMillis) {
        return launch(prepared, starter, attachment, graceMillis, deadlineMillis, 0);
    }

    static Handle launch(
            WorldStreamPreparedFiles prepared,
            Starter starter,
            Attachment attachment,
            long graceMillis,
            long deadlineMillis,
            long startupMillis) {
        return launch(
                prepared,
                starter,
                attachment,
                graceMillis,
                deadlineMillis,
                startupMillis,
                System::nanoTime);
    }

    /**
     * As above, with the monotonic clock of startup, progress and reply liveness: tests pass their
     * own so the startup deadline does not depend on scheduling.
     */
    static Handle launch(
            WorldStreamPreparedFiles prepared,
            Starter starter,
            Attachment attachment,
            long graceMillis,
            long deadlineMillis,
            long startupMillis,
            java.util.function.LongSupplier clock) {
        Objects.requireNonNull(starter);
        Objects.requireNonNull(attachment);
        Objects.requireNonNull(clock);
        if (graceMillis < 0 || deadlineMillis < 1 || startupMillis < 0) {
            throw new IllegalArgumentException("Invalid close timing");
        }
        // Files change owner BEFORE any scheduling or externally supplied Starter invocation.
        var files = prepared.claimForLaunch();
        var owner = new Handle(files, graceMillis, deadlineMillis, startupMillis);
        owner.clockForTest(clock);
        owner.start(starter, attachment);
        return owner;
    }

    static final class Handle {
        private final Object lock = new Object();
        private final WorldStreamPreparedFiles.LaunchFiles files;
        private final long graceMillis, deadlineMillis, startupMillis;
        private final AtomicBoolean initialized = new AtomicBoolean();
        // When the child last showed it is working (System.nanoTime); see progress().
        private volatile long lastProgress = System.nanoTime();
        // The monotonic clock of progress and reply liveness; only tests replace it.
        private volatile java.util.function.LongSupplier clock = System::nanoTime;
        private volatile boolean startupDone;
        private final AtomicBoolean stopping = new AtomicBoolean();
        // Runs once, without a lock, when stopping first becomes true (or at once if it already
        // is). Must not block.
        private final java.util.concurrent.CopyOnWriteArrayList<Runnable> stopListeners =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> childExited = new CompletableFuture<>();
        // The child's own terminal CLOSED status: after it, only process exit remains.
        private final CompletableFuture<Void> closedAcknowledged = new CompletableFuture<>();
        private final CompletableFuture<Drain> actualDrain = new CompletableFuture<>();
        private final CompletableFuture<Drain> boundedClose = new CompletableFuture<>();
        private final List<AtomicBoolean> workers = new ArrayList<>(MAX_WORKERS);
        private volatile Process process;
        private volatile CompletableFuture<Process> processExit;
        private volatile boolean attachmentDone;
        private volatile IOException failure;
        private boolean sealed;
        // Guarded by lock; only the already-admitted callback can finish bounded bootstrap.
        private Thread attachmentThread;

        private Handle(
                WorldStreamPreparedFiles.LaunchFiles files,
                long graceMillis,
                long deadlineMillis,
                long startupMillis) {
            this.files = files;
            this.graceMillis = graceMillis;
            this.deadlineMillis = deadlineMillis;
            this.startupMillis = startupMillis;
            startupDone = startupMillis == 0;
        }

        /** The child reported CLOSED: its drain is done and only process exit remains. */
        void closedAcknowledged() {
            closedAcknowledged.complete(null);
        }

        /**
         * How long close lets a live child finish on its own before signalling it. A child that has
         * not yet reported CLOSED is still draining (decoders, GPU teardown take a few hundred ms
         * on a loaded host), so it gets up to {@code drainMillis}; after CLOSED only {@code
         * graceMillis} for process exit. Pure, for tests.
         */
        static long exitWaitMillis(
                boolean acknowledged, long sinceCloseMillis, long graceMillis, long drainMillis) {
            return acknowledged ? graceMillis : drainMillis - sinceCloseMillis;
        }

        /**
         * The child showed it is still working (it wrote diagnostics or a status line): the startup
         * deadline counts quiet time from now.
         */
        void progress() {
            lastProgress = clock.getAsLong();
        }

        /** Tests drive progress and reply liveness with their own clock. */
        void clockForTest(java.util.function.LongSupplier next) {
            clock = Objects.requireNonNull(next);
            lastProgress = next.getAsLong();
        }

        /** Metadata acknowledgement only; never a callback or a source readiness claim. */
        void initialized() {
            initialized.set(true);
        }

        boolean stopping() {
            return stopping.get();
        }

        /** Wake-up hook for parked owner workers: runs once when stopping begins. */
        void onStopping(Runnable listener) {
            stopListeners.add(listener);
            if (stopping.get() && stopListeners.remove(listener)) {
                listener.run();
            }
        }

        IOException failure() {
            return failure;
        }

        WorldStreamPreparedFiles.LaunchFiles files() {
            return files;
        }

        /** Registration precedes scheduling. Completion means the real worker finally has run. */
        void runWorker(String name, Worker task) {
            Objects.requireNonNull(task);
            if (name == null || !name.matches("[a-z0-9-]{1,40}")) {
                throw new IllegalArgumentException("Invalid worker name");
            }
            var done = new AtomicBoolean();
            synchronized (lock) {
                if (sealed
                        || stopping.get() && Thread.currentThread() != attachmentThread
                        || workers.size() == MAX_WORKERS) {
                    throw new IllegalStateException("World worker registration is closed/full");
                }
                workers.add(done);
            }
            try {
                Thread.ofVirtual()
                        .name("world-v2-parent-" + name)
                        .start(
                                () -> {
                                    try {
                                        task.run();
                                    } catch (Throwable error) {
                                        try {
                                            // A worker failing because the session was already
                                            // failed (a hung helper killed) keeps that cause.
                                            if (failure == null || !stopping.get()) {
                                                failure = detached(error);
                                            }
                                        } finally {
                                            closeAsync();
                                        }
                                    } finally {
                                        done.set(true);
                                    }
                                });
            } catch (RuntimeException | Error error) {
                done.set(true);
                throw error;
            }
        }

        private void start(Starter starter, Attachment attachment) {
            try {
                if (startupMillis > 0) {
                    try {
                        Thread.ofVirtual()
                                .name("world-v2-parent-startup-deadline")
                                .start(this::watchStartup);
                    } catch (RuntimeException | Error error) {
                        startupDone = true;
                        throw error;
                    }
                }
                Thread.ofVirtual()
                        .name("world-v2-parent-start")
                        .start(
                                () -> {
                                    try {
                                        var child =
                                                Objects.requireNonNull(
                                                        starter.start(files.arguments()));
                                        process =
                                                child; // Irrevocable ownership before any fallible
                                        // setup.
                                        processExit = child.onExit();
                                        processExit.whenComplete(
                                                (ignored, error) -> {
                                                    try {
                                                        if (error == null && !child.isAlive()) {
                                                            childExited.complete(null);
                                                        }
                                                    } finally {
                                                        closeAsync();
                                                    }
                                                });
                                        boolean admitted;
                                        synchronized (lock) {
                                            admitted = !stopping.get();
                                            if (admitted) {
                                                attachmentThread = Thread.currentThread();
                                            }
                                        }
                                        if (admitted) {
                                            attachment.attach(child, this);
                                        }
                                    } catch (Throwable error) {
                                        try {
                                            failure = detached(error);
                                        } finally {
                                            closeAsync();
                                        }
                                    } finally {
                                        synchronized (lock) {
                                            attachmentThread = null;
                                            sealed = true;
                                        }
                                        attachmentDone = true;
                                    }
                                });
            } catch (RuntimeException | Error error) {
                try {
                    failure = detached(error);
                } finally {
                    closeAsync();
                }
                synchronized (lock) {
                    sealed = true;
                }
                attachmentDone = true;
                closeAsync();
            }
        }

        /** Never joins, closes a pipe, waits for a Process or calls user code on the caller. */
        CompletableFuture<Drain> closeAsync() {
            if (stopping.compareAndSet(false, true)) {
                for (var listener : stopListeners) {
                    if (stopListeners.remove(listener)) {
                        listener.run();
                    }
                }
                Thread.ofVirtual()
                        .name("world-v2-parent-close-deadline")
                        .start(
                                () -> {
                                    long until =
                                            System.nanoTime()
                                                    + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
                                    while (!actualDrain.isDone() && System.nanoTime() - until < 0) {
                                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                                    }
                                    if (!actualDrain.isDone()) {
                                        boundedClose.completeExceptionally(
                                                new IOException(
                                                        "World process/local owners did not drain"));
                                    }
                                });
                Thread.ofVirtual().name("world-v2-parent-close").start(this::closeOwned);
            }
            return boundedClose.copy();
        }

        /** Only confirmed OS exit; failed setup/cleanup and no-child start never imply this. */
        CompletableFuture<Void> childExited() {
            return childExited.copy();
        }

        /** Observes an existing close deadline without requesting or starting close. */
        CompletableFuture<Drain> boundedClose() {
            return boundedClose.copy();
        }

        CompletableFuture<Drain> terminatedAndDrained() {
            return actualDrain.copy();
        }

        private void closeOwned() {
            try {
                // A blocked Starter may still produce a child. Never infer no-child before finally.
                while (process == null && !attachmentDone) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                var child = process;
                if (child != null) {
                    var exit = processExit;
                    while (exit == null && !attachmentDone) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                        exit = processExit;
                    }
                    if (exit == null) {
                        signal(
                                child,
                                true); // Best effort, never a proof of exit or cleanup permission.
                        throw new IOException("Started process has no exit observation");
                    }
                    if (!awaitOrderlyExit(exit)) {
                        signal(child, false);
                        if (!await(exit, 250)) {
                            signal(child, true);
                            if (!await(exit, 2000)) {
                                boundedClose.completeExceptionally(
                                        new IOException("World process did not terminate"));
                            }
                        }
                    }
                    // Keep the single existing drain owner for a late actual exit; no successor.
                    exit.join();
                    if (child.isAlive()) {
                        throw new IOException("Exit observation disagrees with live child");
                    }
                    childExited.complete(null);
                }
                while (!attachmentDone || !startupDone || !workersDone()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                if (child != null) {
                    // Streams are closed only after confirmed exit AND actual worker finally drain.
                    child.getOutputStream().close();
                    child.getInputStream().close();
                    child.getErrorStream().close();
                }
                files.closeAsync().join();
                var receipt = new Drain(child);
                process = null;
                processExit = null;
                actualDrain.complete(receipt);
                boundedClose.complete(receipt);
            } catch (Throwable error) {
                failure = detached(error);
                actualDrain.completeExceptionally(failure);
                boundedClose.completeExceptionally(failure);
            }
        }

        /**
         * Startup fails when the child stays quiet (no {@link #progress}) for {@code
         * startupMillis}, or is still not ready after {@link #STARTUP_CAP} times that however busy
         * it looks. A child that exits fails at once through its exit, never waiting out either
         * bound.
         */
        private void watchStartup() {
            try {
                long started = clock.getAsLong();
                long quiet = TimeUnit.MILLISECONDS.toNanos(startupMillis);
                while (!initialized.get() && !stopping.get()) {
                    long now = clock.getAsLong();
                    String reason = startupExpired(started, lastProgress, now, quiet);
                    if (reason != null) {
                        failure =
                                new IOException(
                                        "World initialization deadline exceeded: " + reason);
                        closeAsync();
                        return;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
            } finally {
                startupDone = true;
            }
        }

        /**
         * After startup: fails the session when a command awaits the child's reply ({@code owed}
         * names it, or null when nothing is owed) and the child shows no sign of progress (a status
         * line, its heartbeat included, or a diagnostic line) for {@code quietMillis}. A healthy
         * child sends a heartbeat every second from its render loop, so this catches a child stuck
         * in a commit, an upload or a driver call; a slow but working one keeps beating. Runs as an
         * owner worker until stopping.
         */
        void watchReplies(
                java.util.function.Supplier<String> owed, long quietMillis, long pollNanos) {
            long quiet = TimeUnit.MILLISECONDS.toNanos(quietMillis);
            long owedSince = 0;
            boolean owing = false;
            while (!stopping.get()) {
                long now = clock.getAsLong();
                String waiting = initialized.get() ? owed.get() : null;
                if (waiting == null) {
                    owing = false;
                } else {
                    if (!owing) {
                        owing = true;
                        owedSince = now;
                    }
                    String reason = repliesExpired(waiting, owedSince, lastProgress, now, quiet);
                    if (reason != null) {
                        failure = new IOException("World helper stopped answering: " + reason);
                        closeAsync();
                        return;
                    }
                }
                LockSupport.parkNanos(pollNanos);
            }
        }

        /**
         * Why the child is judged hung at {@code now}, or null: {@code owed} has waited since
         * {@code owedSince} and nothing showed progress for {@code quietNanos} since then. Quiet
         * time before the reply was owed does not count. Pure, for tests.
         */
        static String repliesExpired(
                String owed, long owedSince, long lastProgress, long now, long quietNanos) {
            long since = lastProgress - owedSince > 0 ? lastProgress : owedSince;
            if (now - since < quietNanos) {
                return null;
            }
            return "no sign of progress for "
                    + TimeUnit.NANOSECONDS.toMillis(now - since)
                    + " ms while "
                    + owed
                    + " awaited its reply";
        }

        /**
         * The quiet time after which a child owing a reply is judged hung: {@code
         * vimi.world.replyQuietMillis} when set within 1 s to 10 minutes, else 30 s.
         */
        static long replyQuietMillis(String property) {
            if (property != null) {
                try {
                    long millis = Long.parseLong(property.strip());
                    if (millis >= 1_000 && millis <= 600_000) {
                        return millis;
                    }
                } catch (NumberFormatException invalid) {
                    // The default below.
                }
            }
            return 30_000;
        }

        /** How many quiet windows a busy child may take in all before startup fails. */
        static final int STARTUP_CAP = 4;

        /**
         * Why startup has run out at {@code now}, or null while it may go on: no progress for the
         * quiet window, or the overall cap of {@link #STARTUP_CAP} windows reached. Pure, for
         * tests.
         */
        static String startupExpired(long started, long lastProgress, long now, long quietNanos) {
            if (now - started >= quietNanos * STARTUP_CAP) {
                return "still not ready after "
                        + TimeUnit.NANOSECONDS.toMillis(quietNanos * STARTUP_CAP)
                        + " ms";
            }
            if (now - Math.max(started, lastProgress) >= quietNanos) {
                return "no sign of progress for "
                        + TimeUnit.NANOSECONDS.toMillis(quietNanos)
                        + " ms";
            }
            return null;
        }

        private boolean workersDone() {
            synchronized (lock) {
                for (var done : workers) {
                    if (!done.get()) {
                        return false;
                    }
                }
                return true;
            }
        }

        static IOException detached(Throwable error) {
            Class<?> type = error.getClass();
            // Only these exact JDK classes have trusted message accessors; no arbitrary callback.
            String message =
                    type == IOException.class
                                    || type == IllegalStateException.class
                                    || type == IllegalArgumentException.class
                            ? error.getMessage()
                            : type.getName();
            if (message == null) {
                message = type.getName();
            }
            var failure = new IOException(message.substring(0, Math.min(256, message.length())));
            failure.setStackTrace(new StackTraceElement[0]);
            return failure;
        }

        /**
         * Waits for the child to exit on its own: until it reports CLOSED (bounded by the drain
         * budget, which leaves the signal steps inside the close deadline), then {@code
         * graceMillis} more for the exit itself. Signalling a child before its CLOSED terminates it
         * mid-drain (exit 1 on Windows) and the session ends with "EOF before CLOSED" (#431).
         */
        private boolean awaitOrderlyExit(CompletableFuture<Process> exit) throws Exception {
            long start = System.nanoTime();
            long drainMillis = Math.max(graceMillis, deadlineMillis - SIGNAL_RESERVE_MILLIS);
            while (true) {
                long since = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                boolean acknowledged = closedAcknowledged.isDone();
                long wait = exitWaitMillis(acknowledged, since, graceMillis, drainMillis);
                if (!acknowledged) {
                    if (wait <= 0) {
                        return exit.isDone();
                    }
                    // Wake on exit or on CLOSED, whichever comes first.
                    try {
                        CompletableFuture.anyOf(exit, closedAcknowledged)
                                .get(wait, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException timeout) {
                        return exit.isDone();
                    }
                    if (exit.isDone()) {
                        return true;
                    }
                    continue; // CLOSED arrived: now only the exit grace
                }
                return await(exit, wait);
            }
        }

        /**
         * Kept for the exit grace and the signal steps after the orderly wait, inside the deadline.
         */
        // 250 ms exit grace + 250 ms after destroy + 2 s after destroyForcibly, plus headroom.
        static final long SIGNAL_RESERVE_MILLIS = 3000;

        private static boolean await(CompletableFuture<Process> exit, long millis)
                throws Exception {
            try {
                exit.get(millis, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException timeout) {
                return false;
            }
        }

        private static void signal(Process child, boolean force) {
            // Process.destroy can itself close stream monitors. Signal through the handle instead.
            var handle = child.toHandle();
            if (force) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        }
    }
}
