package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Source-neutral World process lifetime. The application's existing GPU arbiter supplies Starter
 * and retains its permit until terminatedAndDrained completes successfully.
 */
public final class WorldStreamTransport {
    public enum Backend {
        DEFAULT,
        VULKAN,
        METAL,
        D3D11
    }

    public record Spec(String scopeSha256, long initialPolicy, Backend backend) {
        public Spec {
            Objects.requireNonNull(backend);
            new WorldStreamEnvelope.Session(
                    new WorldStreamState.Nonce(1, 1), scopeSha256, initialPolicy);
        }
    }

    public record Camera(long revision, long policy, int width, int height, TerrainCamera view) {
        public Camera {
            new WorldStreamControl.Camera(
                    new WorldStreamState.Nonce(1, 1), revision, policy, width, height, view);
        }
    }

    /** Must return every spawned child, or throw only when no child was spawned. */
    @FunctionalInterface
    public interface Starter {
        Process start(List<String> arguments) throws Exception;
    }

    public static final class DrainReceipt {
        private final OptionalLong pid;
        private final OptionalInt exit;

        private DrainReceipt(WorldStreamLaunchOwner.Drain drain) {
            pid = drain.childPid();
            exit = drain.exitCode();
        }

        public OptionalLong childPid() {
            return pid;
        }

        public OptionalInt exitCode() {
            return exit;
        }
    }

    public static final class PreparedSession implements AutoCloseable {
        private final WorldStreamEnvelope.Session descriptor;
        private final WorldStreamPreparedFiles files;

        private PreparedSession(
                WorldStreamEnvelope.Session descriptor, WorldStreamPreparedFiles files) {
            this.descriptor = descriptor;
            this.files = files;
        }

        /** Transfers file ownership before asynchronous external process creation. */
        public Session launch(Starter starter) {
            return launch(starter, 10000, 5000);
        }

        Session launch(Starter starter, long startupMillis, long closeMillis) {
            Objects.requireNonNull(starter);
            var session = new Session(descriptor);
            var owner =
                    WorldStreamLaunchOwner.launch(
                            files,
                            starter::start,
                            session::attach,
                            250,
                            closeMillis,
                            startupMillis);
            session.launched(owner);
            return session;
        }

        /**
         * Never waits or closes files on the caller. Inert once launch has transferred ownership.
         */
        @Override
        public void close() {
            files.closePreparationAsync();
        }

        public CompletableFuture<Void> closeAsync() {
            return files.closePreparationAsync();
        }
    }

    /** Filesystem preparation; call on a bounded application worker, never on JavaFX. */
    public static PreparedSession prepare(Path scratchParent, Spec spec) throws IOException {
        Objects.requireNonNull(spec);
        var random = new SecureRandom();
        long high, low;
        do {
            high = random.nextLong();
            low = random.nextLong();
        } while (high == 0 && low == 0);
        var descriptor =
                new WorldStreamEnvelope.Session(
                        new WorldStreamState.Nonce(high, low),
                        spec.scopeSha256(),
                        spec.initialPolicy());
        String backend =
                spec.backend() == Backend.DEFAULT
                        ? null
                        : spec.backend().name().toLowerCase(java.util.Locale.ROOT);
        return new PreparedSession(
                descriptor, WorldStreamPreparedFiles.create(scratchParent, descriptor, backend));
    }

    public static final class Session {
        private final Object lock = new Object();
        private final WorldStreamEnvelope.Session descriptor;
        private final WorldStreamParentState state;
        private final WorldStreamParentFrameGate frames;
        private final CompletableFuture<Void> initialized = new CompletableFuture<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile WorldStreamLaunchOwner.Handle owner;
        private volatile boolean closing, closedStatus;
        private volatile IOException failure;
        private WorldStreamControl.Camera pendingCamera;
        private boolean closeWritten, actualExit, statusesDrained;

        private Session(WorldStreamEnvelope.Session descriptor) {
            this.descriptor = descriptor;
            state = new WorldStreamParentState(descriptor);
            frames = new WorldStreamParentFrameGate(descriptor);
        }

        private void launched(WorldStreamLaunchOwner.Handle handle) {
            owner = handle;
            handle.childExited().thenRun(this::childExited);
            handle.boundedClose()
                    .whenComplete(
                            (drain, error) -> {
                                if (error != null) {
                                    fail(handle.failure() == null ? error : handle.failure());
                                }
                            });
            handle.terminatedAndDrained()
                    .whenComplete(
                            (drain, error) -> {
                                state.close();
                                frames.close();
                                if (!initialized.isDone()) {
                                    initialized.completeExceptionally(
                                            problem("World child did not initialize"));
                                }
                                if (error != null) {
                                    fail(error);
                                } else if (handle.failure() != null) {
                                    fail(handle.failure());
                                } else if (failure != null) {
                                    completion.completeExceptionally(failure);
                                } else if (!closedStatus
                                        || drain.exitCode().isEmpty()
                                        || drain.exitCode().getAsInt() != 0) {
                                    fail(
                                            new IOException(
                                                    "World child exited without successful CLOSED"));
                                } else {
                                    completion.complete(null);
                                }
                            });
        }

        private void attach(Process process, WorldStreamLaunchOwner.Handle handle) {
            owner = handle;
            handle.runWorker("commands", () -> writeCommands(process, handle));
            handle.runWorker(
                    "statuses",
                    () -> {
                        try {
                            readStatuses(process, handle);
                        } finally {
                            statusesDrained();
                        }
                    });
            handle.runWorker("stderr", () -> drainErrors(process));
        }

        public CompletableFuture<Void> initialized() {
            return initialized.copy();
        }

        /** Protocol success is separate from process/resource drain. */
        public CompletableFuture<Void> completion() {
            return completion.copy();
        }

        /** Replaces one pending camera; no pipe I/O or external callback occurs on the caller. */
        public void camera(Camera camera) {
            Objects.requireNonNull(camera);
            var command =
                    new WorldStreamControl.Camera(
                            descriptor.nonce(),
                            camera.revision(),
                            camera.policy(),
                            camera.width(),
                            camera.height(),
                            camera.view());
            synchronized (lock) {
                if (closing || owner != null && owner.stopping()) {
                    throw new IllegalStateException("World session is stopping");
                }
                frames.desire(command);
                pendingCamera = command;
                lock.notifyAll();
            }
        }

        public CompletableFuture<DrainReceipt> closeAsync() {
            requestClose();
            return owner.closeAsync().thenApply(DrainReceipt::new);
        }

        public CompletableFuture<DrainReceipt> terminatedAndDrained() {
            return owner.terminatedAndDrained().thenApply(DrainReceipt::new);
        }

        private void requestClose() {
            synchronized (lock) {
                closing = true;
                pendingCamera = null;
                lock.notifyAll();
            }
            frames.close();
        }

        private void childExited() {
            requestClose();
            synchronized (lock) {
                actualExit = true;
                if (statusesDrained) {
                    state.processExited();
                }
            }
        }

        private void statusesDrained() {
            synchronized (lock) {
                statusesDrained = true;
                if (actualExit) {
                    state.processExited();
                }
            }
        }

        private void writeCommands(Process process, WorldStreamLaunchOwner.Handle handle)
                throws Exception {
            var output = process.getOutputStream();
            while (true) {
                WorldStreamControl.Command command;
                synchronized (lock) {
                    if ((closing || handle.stopping()) && !closeWritten) {
                        closeWritten = true;
                        command = new WorldStreamControl.Close(descriptor.nonce());
                    } else if (closeWritten) {
                        return;
                    } else if (pendingCamera != null) {
                        command = pendingCamera;
                        pendingCamera = null;
                    } else {
                        lock.wait(20);
                        continue;
                    }
                }
                output.write(WorldStreamControl.encode(command));
                output.flush();
            }
        }

        private void readStatuses(Process process, WorldStreamLaunchOwner.Handle handle)
                throws Exception {
            try {
                while (true) {
                    var message = WorldStreamStatus.read(process.getInputStream());
                    if (message == null) {
                        if (!closedStatus) {
                            throw new IOException("World status EOF before CLOSED");
                        }
                        return;
                    }
                    var change = state.status(message);
                    switch (change) {
                        case SESSION -> {
                            handle.initialized();
                            if (!closing && !handle.stopping()) {
                                initialized.complete(null);
                            } else {
                                initialized.completeExceptionally(
                                        problem("World session stopped during initialization"));
                            }
                        }
                        case CAMERA -> frames.applied((WorldStreamStatus.CameraApplied) message);
                        case CLOSED -> {
                            closedStatus = true;
                            requestClose();
                            handle.closeAsync();
                        }
                        case IGNORED, CREDIT -> {}
                        default ->
                                throw new IOException(
                                        "Unexpected World status without published operation");
                    }
                }
            } catch (Throwable error) {
                fail(error);
                throw error;
            }
        }

        private static void drainErrors(Process process) throws IOException {
            // Drain without retaining arbitrary diagnostics or user data.
            byte[] scratch = new byte[4096];
            while (process.getErrorStream().read(scratch) != -1) {}
        }

        private IOException problem(String message) {
            return failure == null ? new IOException(message) : failure;
        }

        private void fail(Throwable error) {
            // Secure the lifetime before diagnostic encoding or any future/user callback.
            requestClose();
            var handle = owner;
            if (handle != null) {
                handle.closeAsync();
            }
            var detached = WorldStreamLaunchOwner.Handle.detached(error);
            if (failure == null) {
                failure = detached;
            }
            initialized.completeExceptionally(failure);
            completion.completeExceptionally(failure);
        }
    }

    private WorldStreamTransport() {}
}
