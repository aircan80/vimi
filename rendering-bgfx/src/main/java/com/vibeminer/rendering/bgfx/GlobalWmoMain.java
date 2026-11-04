package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;

/**
 * Explicit GLOBAL child: one immutable scene, one fixed mapping, one latest parent camera. Status
 * records are finite (consumed, ready, closed) and written off the native thread.
 */
final class GlobalWmoMain {
    /** Halt if cooperative owner retirement plus status drain has not finished by then. */
    static final long STOP_DEADLINE_MILLIS = 9_000;

    private GlobalWmoMain() {}

    static boolean requested(String[] args) {
        if (args == null) {
            return false;
        }
        // Flag positions only: a value equal to the flag text never selects GLOBAL mode.
        for (int i = 0; i < args.length; i += 2) {
            if ("--global-scene".equals(args[i])) {
                return true;
            }
        }
        return false;
    }

    static int execute(String[] args) {
        try {
            var launch = GlobalWmoLaunch.parse(args);
            var parent =
                    ProcessHandle.of(launch.parentPid())
                            .filter(ProcessHandle::isAlive)
                            .orElseThrow(() -> new IOException("Global parent is unavailable"));
            try (var statuses = new StatusWriter(System.out)) {
                var control = new Control(launch.expected().identity().nonce());
                control.start(new BufferedInputStream(System.in), parent.onExit());
                run(launch, control, statuses, new NativeContext(launch.backend()));
                statuses.drain();
            }
            return 0;
        } catch (Throwable failure) {
            System.err.println("GLOBAL_CHILD_FAILED " + safe(failure));
            System.err.flush();
            return 1;
        }
    }

    /** Native context seam; the production implementation is real bgfx only. */
    interface Context {
        void initialize() throws IOException;

        void shutdown();
    }

    static void run(GlobalWmoLaunch launch, Control control, StatusWriter statuses, Context context)
            throws IOException {
        var identity = launch.expected().identity();
        var frames = GpuFrameFile.open(launch.frames(), 800, 500);
        try {
            launch.verifyMapping(frames);
            try {
                render(launch, control, statuses, context, frames);
            } catch (CancellationException canceled) {
                // Only exact standard cancellation after an actual stop request is healthy.
                if (canceled.getClass() != CancellationException.class
                        || !control.stopping()
                        || control.failure() != null) {
                    throw canceled;
                }
            }
            if (control.failure() != null) {
                throw new IOException("Invalid global control", control.failure());
            }
            // All owners retired (or none was created); the child mapping closes before the
            // positive terminal status. A context that never existed is not claimed initialized.
            frames.close();
            statuses.emit(new WorldGlobalStatus.Closed(identity));
        } finally {
            frames.close();
        }
    }

    private static void render(
            GlobalWmoLaunch launch,
            Control control,
            StatusWriter statuses,
            Context context,
            GpuFrameFile frames)
            throws IOException {
        var identity = launch.expected().identity();
        if (control.stopping()) {
            return;
        }
        // Same-handle whole-SHA verification happens inside read; consumed is emitted only
        // after it returns and the sole file handle is closed.
        var decoded =
                GpuGlobalWmoSceneFile.read(launch.scene(), launch.expected(), control::stopping);
        var counts = decoded.counts();
        GlobalWmoFrameOwner owner;
        try {
            owner = GlobalWmoFrameOwner.allocate(frames);
        } catch (RuntimeException | Error failure) {
            decoded.close();
            throw failure;
        }
        boolean contextLive = false;
        try {
            statuses.emit(
                    new WorldGlobalStatus.InputConsumed(identity.nonce(), identity.fileSha256()));
            context.initialize();
            contextLive = true;
            owner.initialize(decoded, control::stopping);
            boolean announced = false;
            while (!control.stopping()) {
                var request = control.latest();
                if (request != null && request.revision() > control.submitted()) {
                    if (owner.submit(request)) {
                        control.submitted(request.revision());
                    }
                }
                if (owner.advance() && !announced) {
                    announced = true;
                    statuses.emit(
                            new WorldGlobalStatus.Ready(
                                    identity,
                                    counts.vertices(),
                                    counts.indices() / 3,
                                    counts.drawnTriangles(),
                                    counts.groups(),
                                    counts.batches(),
                                    counts.images()));
                }
                if (!owner.pending()) {
                    control.await(
                            request == null || request.revision() <= control.submitted()
                                    ? 20_000_000L
                                    : 1_000_000L);
                }
            }
        } finally {
            try {
                owner.close();
            } finally {
                if (contextLive) {
                    context.shutdown();
                    owner.afterContextShutdown();
                }
                // Idempotent: initialize adopts Decoded, but an early failure may precede it.
                decoded.close();
            }
        }
    }

    /** Dedicated control lane: bounded lines, one latest camera, no history. */
    static final class Control {
        private final String nonce;
        private final AtomicReference<GlobalWmoFrameRequest> latest = new AtomicReference<>();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Thread waiter;
        private long submitted;

        Control(String nonce) {
            this.nonce = WorldGlobalLayout.hex(nonce, 32);
        }

        void start(InputStream input, CompletableFuture<?> parentExit) {
            Objects.requireNonNull(input);
            parentExit.whenComplete((ignored, error) -> stop(null));
            Thread.ofVirtual().name("global-child-control").start(() -> read(input));
        }

        void read(InputStream input) {
            long previous = 0;
            try {
                for (; ; ) {
                    String line = GlobalWmoControl.readLine(input);
                    if (line == null) {
                        stop(null);
                        return;
                    }
                    var command = GlobalWmoControl.parse(line, nonce, previous);
                    if (command instanceof GlobalWmoControl.Close) {
                        stop(null);
                        return;
                    }
                    var camera = (GlobalWmoControl.Camera) command;
                    previous = camera.request().revision();
                    latest.set(camera.request());
                    var thread = waiter;
                    if (thread != null) {
                        LockSupport.unpark(thread);
                    }
                }
            } catch (IOException | RuntimeException invalid) {
                stop(invalid);
            }
        }

        void stop(Throwable cause) {
            if (cause != null) {
                failure.compareAndSet(null, cause);
            }
            if (stopping.compareAndSet(false, true)) {
                var thread = waiter;
                if (thread != null) {
                    LockSupport.unpark(thread);
                }
                Thread.ofPlatform()
                        .daemon()
                        .name("global-child-deadline")
                        .start(
                                () -> {
                                    try {
                                        Thread.sleep(STOP_DEADLINE_MILLIS);
                                    } catch (InterruptedException ignored) {
                                        return;
                                    }
                                    Runtime.getRuntime().halt(3);
                                });
            }
        }

        boolean stopping() {
            return stopping.get();
        }

        Throwable failure() {
            return failure.get();
        }

        GlobalWmoFrameRequest latest() {
            return latest.get();
        }

        long submitted() {
            return submitted;
        }

        void submitted(long revision) {
            submitted = revision;
        }

        void await(long nanos) {
            waiter = Thread.currentThread();
            // Re-check after publishing the waiter so a camera arriving in between is not lost.
            var newest = latest.get();
            if (!stopping.get() && (newest == null || newest.revision() <= submitted)) {
                LockSupport.parkNanos(nanos);
            }
            waiter = null;
        }
    }

    /** At most four finite records; blocked stdout fails the child instead of the native thread. */
    static final class StatusWriter implements AutoCloseable {
        private static final WorldGlobalStatus END =
                new WorldGlobalStatus.Closed(
                        new WorldGlobalStatus.Identity(
                                "0".repeat(32), "0".repeat(64), "0".repeat(64)));
        private final ArrayBlockingQueue<WorldGlobalStatus> queue = new ArrayBlockingQueue<>(5);
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private int emitted;

        StatusWriter(OutputStream output) {
            Objects.requireNonNull(output);
            var printer = new PrintStream(output, false, StandardCharsets.US_ASCII);
            Thread.ofVirtual()
                    .name("global-child-status")
                    .start(
                            () -> {
                                try {
                                    for (; ; ) {
                                        var status = queue.take();
                                        if (status == END) {
                                            break;
                                        }
                                        printer.print(status.protocolLine() + "\n");
                                        printer.flush();
                                        if (printer.checkError()) {
                                            throw new IOException("Global status pipe failed");
                                        }
                                    }
                                    drained.complete(null);
                                } catch (Throwable error) {
                                    drained.completeExceptionally(error);
                                }
                            });
        }

        void emit(WorldGlobalStatus status) {
            Objects.requireNonNull(status);
            if (++emitted > 4 || !queue.offer(status)) {
                throw new IllegalStateException("Global status bound exceeded");
            }
        }

        void drain() throws IOException {
            queue.offer(END);
            try {
                drained.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IOException("Global status writer failed", failure.getCause());
            } catch (java.util.concurrent.TimeoutException blocked) {
                throw new IOException("Global status pipe blocked");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Global status drain interrupted");
            }
        }

        @Override
        public void close() {
            queue.offer(END);
        }
    }

    /** Hardware-only bgfx with no default backbuffer and no software fallback. */
    static final class NativeContext implements Context {
        private final GlobalWmoLaunch.Backend backend;

        NativeContext(GlobalWmoLaunch.Backend backend) {
            this.backend = Objects.requireNonNull(backend);
        }

        @Override
        public void initialize() throws IOException {
            try (var stack = MemoryStack.stackPush()) {
                var init = BGFXInit.calloc(stack);
                bgfx_init_ctor(init);
                init.type(
                                GpuViewerMain.backend(
                                        backend == GlobalWmoLaunch.Backend.DEFAULT
                                                ? null
                                                : backend.name()
                                                        .toLowerCase(java.util.Locale.ROOT)))
                        .fallback(false);
                init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                BGFXPlatform.bgfx_render_frame(0);
                if (!bgfx_init(init)) {
                    throw new IOException("Unable to initialize GPU backend");
                }
            }
            var caps = bgfx_get_caps();
            try {
                GpuViewerMain.requireHardware(
                        Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
            } catch (IOException software) {
                bgfx_shutdown();
                throw software;
            }
        }

        @Override
        public void shutdown() {
            bgfx_shutdown();
        }
    }

    private static String safe(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        return type.replaceAll("[^A-Za-z0-9_]", "").substring(0, Math.min(64, type.length()));
    }
}
