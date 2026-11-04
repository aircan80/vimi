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
        Objects.requireNonNull(starter);
        Objects.requireNonNull(attachment);
        if (graceMillis < 0 || deadlineMillis < 1 || startupMillis < 0) {
            throw new IllegalArgumentException("Invalid close timing");
        }
        // Files change owner BEFORE any scheduling or externally supplied Starter invocation.
        var files = prepared.claimForLaunch();
        var owner = new Handle(files, graceMillis, deadlineMillis, startupMillis);
        owner.start(starter, attachment);
        return owner;
    }

    static final class Handle {
        private final Object lock = new Object();
        private final WorldStreamPreparedFiles.LaunchFiles files;
        private final long graceMillis, deadlineMillis, startupMillis;
        private final AtomicBoolean initialized = new AtomicBoolean();
        private volatile boolean startupDone;
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final CompletableFuture<Void> childExited = new CompletableFuture<>();
        private final CompletableFuture<Drain> actualDrain = new CompletableFuture<>();
        private final CompletableFuture<Drain> boundedClose = new CompletableFuture<>();
        private final List<AtomicBoolean> workers = new ArrayList<>(6);
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

        /** Metadata acknowledgement only; never a callback or a source readiness claim. */
        void initialized() {
            initialized.set(true);
        }

        boolean stopping() {
            return stopping.get();
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
                        || workers.size() == 6) {
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
                                            failure = detached(error);
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
                    if (!await(exit, graceMillis)) {
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

        private void watchStartup() {
            try {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupMillis);
                while (!initialized.get() && !stopping.get()) {
                    if (System.nanoTime() - until >= 0) {
                        failure = new IOException("World initialization deadline exceeded");
                        closeAsync();
                        return;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
            } finally {
                startupDone = true;
            }
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
