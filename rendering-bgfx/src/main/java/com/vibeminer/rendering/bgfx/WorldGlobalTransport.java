package com.vibeminer.rendering.bgfx;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Source-neutral single-child GLOBAL parent transport. It borrows an already moved canonical input
 * and never closes or deletes it: the application closes that input after {@link
 * Session#inputConsumed()} or after {@link Session#terminated()} confirms actual process exit.
 */
public final class WorldGlobalTransport {
    /** Cooperative CLOSE covers the child's 5s readback retirement plus status drain. */
    static final long STOP_MILLIS = 10_000;

    private WorldGlobalTransport() {}

    @FunctionalInterface
    public interface Starter {
        Process start(List<String> arguments) throws IOException;
    }

    /** Public scalar camera with the same separate global bounds and free-flight math. */
    public record Camera(double x, double y, double z, double yaw, double pitch) {
        public Camera {
            new GlobalWmoCamera(x, y, z, yaw, pitch);
        }

        private Camera(GlobalWmoCamera camera) {
            this(camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
        }

        /** Inspection framing only; never a client entrance, spawn, floor or navigation claim. */
        public static Camera inspecting(float[] nativeBounds) {
            if (nativeBounds == null || nativeBounds.length != 6) {
                throw new IllegalArgumentException("Six native bounds required");
            }
            var b = nativeBounds;
            return new Camera(GlobalWmoCamera.initial(b[0], b[1], b[2], b[3], b[4], b[5]));
        }

        public Camera move(double forward, double right, double up) {
            return new Camera(internal().move(forward, right, up));
        }

        public Camera look(double left, double up) {
            return new Camera(internal().look(left, up));
        }

        private GlobalWmoCamera internal() {
            return new GlobalWmoCamera(x, y, z, yaw, pitch);
        }
    }

    public record Frame(long sequence, long revision, int width, int height, IntBuffer pixels) {}

    /**
     * Prepares the request-only frames directory, mapping and exact argv, then starts one child.
     */
    public static Session launch(
            Path requestParent,
            WorldGlobalSceneWriter.MovedInput input,
            String backend,
            Starter starter)
            throws IOException {
        Objects.requireNonNull(requestParent);
        Objects.requireNonNull(input);
        Objects.requireNonNull(starter);
        var directory =
                Files.createTempDirectory(requestParent, "global-frames-")
                        .toAbsolutePath()
                        .normalize();
        Path framesPath = directory.resolve("frames.vgpu");
        GpuFrameFile frames = null;
        Process process = null;
        try {
            frames = GpuFrameFile.create(framesPath, 800, 500);
            var launch =
                    new GlobalWmoLaunch(
                            input.path(),
                            input.expected().input(),
                            framesPath,
                            frames.sessionNonce(),
                            ProcessHandle.current().pid(),
                            backend == null
                                    ? GlobalWmoLaunch.Backend.DEFAULT
                                    : GlobalWmoLaunch.Backend.valueOf(
                                            backend.toUpperCase(java.util.Locale.ROOT)));
            process = Objects.requireNonNull(starter.start(launch.arguments()));
            var session = new Session(input.expected(), frames, directory, framesPath, process);
            session.start();
            return session;
        } catch (IOException | RuntimeException | Error failure) {
            if (process != null && !confirmExit(process)) {
                // A started child may still map the frames and read the input: keep both.
                var unconfirmed = new UnconfirmedExit();
                unconfirmed.addSuppressed(failure);
                throw unconfirmed;
            }
            try {
                if (frames != null) {
                    frames.close();
                }
                Files.deleteIfExists(framesPath);
                Files.deleteIfExists(directory);
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** A started child's exit could not be confirmed; its input and mapping must be retained. */
    public static final class UnconfirmedExit extends IOException {
        private static final long serialVersionUID = 1L;

        UnconfirmedExit() {
            super("Global renderer exit could not be confirmed");
        }
    }

    private static boolean confirmExit(Process process) {
        process.destroyForcibly();
        try {
            return process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    public static final class Session {
        private final WorldGlobalSceneWriter.ExpectedFile expected;
        private final GpuFrameFile frames;
        private final Path directory, framesPath;
        private final Process process;
        private final CompletableFuture<Void> consumed = new CompletableFuture<>();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<Void> closedStatus = new CompletableFuture<>();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private final CompletableFuture<Void> readerDone = new CompletableFuture<>();
        private volatile boolean retired;
        private final Object lock = new Object();
        private GlobalWmoFrameRequest pending;
        private long revision;
        private long sequence;
        private boolean closeRequested, closeSent, failed;
        private String failure = "";

        private Session(
                WorldGlobalSceneWriter.ExpectedFile expected,
                GpuFrameFile frames,
                Path directory,
                Path framesPath,
                Process process) {
            this.expected = expected;
            this.frames = frames;
            this.directory = directory;
            this.framesPath = framesPath;
            this.process = process;
        }

        private void start() {
            Thread.ofVirtual().name("global-transport-status").start(this::readStatuses);
            Thread.ofVirtual().name("global-transport-log").start(this::drainErrors);
            Thread.ofVirtual().name("global-transport-lifecycle").start(this::lifecycle);
        }

        /** Coalesces to one latest camera; returns its revision. Never blocks on the pipe. */
        public long camera(int width, int height, Camera camera) {
            Objects.requireNonNull(camera);
            synchronized (lock) {
                if (closeRequested || failed) {
                    return revision;
                }
                var request =
                        new GlobalWmoFrameRequest(revision + 1, camera.internal(), width, height);
                revision = request.revision();
                pending = request;
                lock.notifyAll();
                return revision;
            }
        }

        /** True only after actual exit AND successful mapping/frames-directory retirement. */
        public boolean retired() {
            return retired;
        }

        /** True only once the actual child process has exited; kill intent is never exit. */
        public boolean exited() {
            return !process.isAlive();
        }

        public CompletableFuture<Void> inputConsumed() {
            return consumed.copy();
        }

        public CompletableFuture<Void> ready() {
            return ready.copy();
        }

        /** Completes after actual exit, reader drain and mapping/file/directory retirement. */
        public CompletableFuture<Void> terminated() {
            return terminated.copy();
        }

        /** Exact metadata selection BEFORE detached pixel allocation; null after closure. */
        public Frame poll(long minimumRevision, int width, int height) throws IOException {
            if (!ready.isDone() || ready.isCompletedExceptionally()) {
                return null;
            }
            long latest;
            synchronized (lock) {
                latest = revision;
                if (failed || latest < Math.max(1, minimumRevision)) {
                    return null;
                }
            }
            var selection =
                    new GpuFrameFile.GlobalSelection(
                            Math.max(1, minimumRevision), latest, width, height);
            GpuFrameFile.Frame frame;
            try {
                synchronized (frames) {
                    frame = frames.readLatestGlobal(sequence, selection);
                }
            } catch (IllegalStateException closed) {
                return null;
            }
            if (frame == null) {
                return null;
            }
            sequence = frame.sequence();
            return new Frame(
                    frame.sequence(),
                    frame.controlRevision(),
                    frame.width(),
                    frame.height(),
                    frame.pixels());
        }

        public void closeAsync() {
            synchronized (lock) {
                if (closeRequested) {
                    return;
                }
                closeRequested = true;
                lock.notifyAll();
            }
            // A blocked control pipe cannot postpone the bounded stop: force termination later.
            Thread.ofVirtual()
                    .name("global-transport-stop-deadline")
                    .start(
                            () -> {
                                try {
                                    if (!process.waitFor(STOP_MILLIS, TimeUnit.MILLISECONDS)) {
                                        process.destroyForcibly();
                                    }
                                } catch (InterruptedException interrupted) {
                                    process.destroyForcibly();
                                }
                            });
        }

        private void fail(String reason) {
            synchronized (lock) {
                if (!failed) {
                    failed = true;
                    failure = reason;
                }
                lock.notifyAll();
            }
            var error = new IOException("Global renderer failed: " + reason);
            consumed.completeExceptionally(error);
            ready.completeExceptionally(error);
            closedStatus.completeExceptionally(error);
        }

        private void readStatuses() {
            var identity = expected.identity();
            try (InputStream input = new BufferedInputStream(process.getInputStream())) {
                for (; ; ) {
                    String line = GlobalWmoControl.readLine(input);
                    if (line == null) {
                        break;
                    }
                    var status = WorldGlobalStatus.parse(line);
                    if (status instanceof WorldGlobalStatus.InputConsumed c
                            && !consumed.isDone()
                            && c.nonce().equals(identity.nonce())
                            && c.fileSha256().equals(identity.fileSha256())) {
                        consumed.complete(null);
                    } else if (status instanceof WorldGlobalStatus.Ready r
                            && consumed.isDone()
                            && !ready.isDone()
                            && r.matches(identity, expected.counts())) {
                        ready.complete(null);
                    } else if (status instanceof WorldGlobalStatus.Closed c
                            && !closedStatus.isDone()
                            && c.identity().equals(identity)) {
                        closedStatus.complete(null);
                    } else {
                        throw new IOException("Unexpected global status");
                    }
                }
            } catch (IOException | RuntimeException invalid) {
                fail("PROTOCOL");
                process.destroyForcibly();
            } finally {
                readerDone.complete(null);
            }
        }

        private void drainErrors() {
            try (InputStream errors = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                while (errors.read(buffer) != -1) {
                    // Diagnostics are discarded: raw paths/messages are never surfaced.
                }
            } catch (IOException ignored) {
                // Process exit closes the stream.
            }
        }

        private void lifecycle() {
            Throwable error = null;
            boolean exited = false;
            try {
                writeControls();
                exited = process.waitFor(STOP_MILLIS, TimeUnit.MILLISECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    exited = process.waitFor(2, TimeUnit.SECONDS);
                    fail("STOP_TIMEOUT");
                }
                if (exited && process.exitValue() != 0) {
                    fail("EXIT");
                }
            } catch (Throwable unexpected) {
                error = unexpected;
                fail("LIFECYCLE");
                process.destroyForcibly();
                try {
                    exited = process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!exited) {
                terminated.completeExceptionally(
                        new IOException("Global renderer exit could not be confirmed", error));
                return;
            }
            // Every trailing record is validated before health is decided: reader EOF first.
            try {
                readerDone.get(2, TimeUnit.SECONDS);
            } catch (Exception undrained) {
                fail("STATUS_UNDRAINED");
            }
            if (!closedStatus.isDone()) {
                fail("CLOSED_MISSING");
            }
            try {
                synchronized (frames) {
                    frames.close();
                }
                Files.deleteIfExists(framesPath);
                Files.deleteIfExists(directory);
                retired = true;
            } catch (IOException | RuntimeException cleanup) {
                terminated.completeExceptionally(cleanup);
                return;
            }
            boolean healthy;
            String reason;
            synchronized (lock) {
                healthy = !failed && closeSent;
                reason = !failure.isEmpty() ? failure : "UNEXPECTED_EXIT";
            }
            if (healthy) {
                terminated.complete(null);
            } else {
                terminated.completeExceptionally(
                        new IOException("Global renderer ended unsuccessfully: " + reason));
            }
        }

        private void writeControls() throws IOException, InterruptedException {
            String nonce = expected.identity().nonce();
            OutputStream output = process.getOutputStream();
            for (; ; ) {
                GlobalWmoFrameRequest next;
                boolean close;
                boolean failing;
                synchronized (lock) {
                    while (pending == null && !closeRequested && !failed && process.isAlive()) {
                        lock.wait(50);
                    }
                    if (!process.isAlive() && !closeRequested) {
                        return;
                    }
                    next = pending;
                    pending = null;
                    close = closeRequested || failed;
                    failing = failed;
                }
                if (close) {
                    synchronized (lock) {
                        closeSent = closeRequested;
                    }
                    try {
                        if (next != null && !failing) {
                            // An accepted camera revision precedes Close when both arrive together.
                            output.write(
                                    GlobalWmoControl.encode(
                                            new GlobalWmoControl.Camera(nonce, next)));
                        }
                        output.write(GlobalWmoControl.encode(new GlobalWmoControl.Close(nonce)));
                        output.flush();
                        output.close();
                    } catch (IOException gone) {
                        // An already exited child still has its exit status checked.
                    }
                    return;
                }
                output.write(GlobalWmoControl.encode(new GlobalWmoControl.Camera(nonce, next)));
                output.flush();
            }
        }
    }
}
