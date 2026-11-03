package com.vimi.gui;

import com.vibeminer.rendering.bgfx.GpuSceneFile;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns one direct-rendering child window; no graphics or process wait runs on the FX thread. */
final class GpuPreviewService {
    enum State {
        PREPARING,
        STARTING,
        READY,
        STOPPING,
        CLOSED,
        FAILED
    }

    record Status(State state, String message) {
        boolean busy() {
            return state == State.PREPARING || state == State.STARTING || state == State.STOPPING;
        }
    }

    @FunctionalInterface
    interface SceneWriter {
        void write(Path file, PreviewMesh mesh, MeshAppearance appearance, Set<Integer> parts)
                throws IOException;
    }

    @FunctionalInterface
    interface Starter {
        Process start(Path scene) throws IOException;
    }

    private static final class ApplicationService {
        private static final GpuPreviewService INSTANCE = new GpuPreviewService();
    }

    static GpuPreviewService application() {
        return ApplicationService.INSTANCE;
    }

    private static final long MAX_SCENE_BYTES = GpuSceneFile.MAX_BYTES;
    private final Object gate = new Object();
    private final SceneWriter writer;
    private final Starter starter;
    private final Path temporaryParent;
    private final long readyNanos, closeMillis;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private Request desired, active;
    private boolean running, closed;

    private static final class Request {
        final Object owner;
        final PreviewMesh mesh;
        final MeshAppearance appearance;
        final Set<Integer> parts;
        final Consumer<Status> listener;
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        volatile boolean stopping, ready, protocolFailure;
        volatile String backend = "", errorCode = "";

        String failureMessage() {
            return "GPU preview unavailable"
                    + (errorCode.isEmpty() ? "" : " (" + errorCode + ")")
                    + ". The inline software preview remains available.";
        }

        Request(
                Object owner,
                PreviewMesh mesh,
                MeshAppearance appearance,
                Set<Integer> parts,
                Consumer<Status> listener) {
            this.owner = owner;
            this.mesh = mesh;
            this.appearance = appearance;
            this.parts = Set.copyOf(parts);
            this.listener = listener;
        }

        void publish(State state, String message) {
            try {
                listener.accept(new Status(state, message));
            } catch (RuntimeException ignored) {
                /* UI delivery cannot orphan the native process. */
            }
        }
    }

    private GpuPreviewService() {
        this(
                null,
                GpuSceneFile::write,
                GpuPreviewService::startChild,
                Duration.ofSeconds(20),
                Duration.ofSeconds(3));
    }

    GpuPreviewService(
            Path temporaryParent,
            SceneWriter writer,
            Starter starter,
            Duration readyTimeout,
            Duration closeTimeout) {
        this.temporaryParent = temporaryParent;
        this.writer = Objects.requireNonNull(writer);
        this.starter = Objects.requireNonNull(starter);
        readyNanos = readyTimeout.toNanos();
        closeMillis = closeTimeout.toMillis();
        if (readyNanos < 1 || closeMillis < 1)
            throw new IllegalArgumentException("Positive GPU timeouts required");
    }

    CompletableFuture<Void> open(
            Object owner,
            PreviewMesh mesh,
            MeshAppearance appearance,
            Set<Integer> parts,
            Consumer<Status> listener) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(mesh);
        Objects.requireNonNull(listener);
        var request = new Request(owner, mesh, appearance, parts, listener);
        Request superseded;
        boolean supersededPending, start;
        synchronized (gate) {
            if (closed)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("GPU preview service closed"));
            superseded = desired;
            supersededPending = superseded != null && superseded != active;
            if (superseded != null) superseded.stopping = true;
            desired = request;
            if (active != null) active.stopping = true;
            start = !running;
            running = true;
            gate.notifyAll();
        }
        if (supersededPending) {
            superseded.publish(State.CLOSED, "GPU preview superseded");
            superseded.drained.complete(null);
        }
        request.publish(State.PREPARING, "Preparing GPU preview window…");
        if (start) Thread.ofVirtual().name("gpu-preview-lifecycle").start(this::run);
        var delivery = request.drained.copy();
        delivery.whenComplete(
                (ignored, failure) -> {
                    if (delivery.isCancelled()) cancel(request);
                });
        return delivery;
    }

    private void cancel(Request request) {
        boolean pending;
        synchronized (gate) {
            request.stopping = true;
            pending = request != active;
            if (desired == request) desired = null;
            gate.notifyAll();
        }
        if (pending) {
            request.publish(State.CLOSED, "GPU preview canceled");
            request.drained.complete(null);
        }
    }

    CompletableFuture<Void> closeOwner(Object owner) {
        var waits = new ArrayList<CompletableFuture<Void>>();
        Request pending = null;
        synchronized (gate) {
            if (active != null && active.owner == owner) {
                active.stopping = true;
                waits.add(active.drained);
            }
            if (desired != null && desired.owner == owner) {
                desired.stopping = true;
                if (desired != active) pending = desired;
                waits.add(desired.drained);
                desired = null;
            }
            gate.notifyAll();
        }
        if (pending != null) {
            pending.publish(State.CLOSED, "GPU preview closed");
            pending.drained.complete(null);
        }
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new));
    }

    CompletableFuture<Void> closeAsync() {
        Request pending = null;
        synchronized (gate) {
            closed = true;
            if (active != null) active.stopping = true;
            if (desired != null && desired != active) pending = desired;
            desired = null;
            if (!running) drained.complete(null);
            gate.notifyAll();
        }
        if (pending != null) {
            pending.stopping = true;
            pending.publish(State.CLOSED, "GPU preview closed");
            pending.drained.complete(null);
        }
        return drained.copy();
    }

    private void run() {
        try {
            while (true) {
                Request request;
                synchronized (gate) {
                    request = desired;
                    if (closed || request == null) return;
                    active = request;
                }
                perform(request);
                synchronized (gate) {
                    active = null;
                    if (desired == request) desired = null;
                }
            }
        } finally {
            synchronized (gate) {
                active = null;
                running = false;
                if (closed) drained.complete(null);
                else if (desired != null) {
                    running = true;
                    Thread.ofVirtual().name("gpu-preview-lifecycle").start(this::run);
                }
            }
        }
    }

    private void perform(Request request) {
        Path directory = null, scene = null;
        Process child = null;
        Thread stdout = null, stderr = null;
        Throwable failure = null;
        try {
            if (request.stopping) return;
            directory =
                    temporaryParent == null
                            ? Files.createTempDirectory("vimi-gpu-")
                            : Files.createTempDirectory(temporaryParent, "vimi-gpu-");
            scene = directory.resolve("scene.vgpu");
            writer.write(scene, request.mesh, request.appearance, request.parts);
            if (Files.size(scene) > MAX_SCENE_BYTES)
                throw new IOException("GPU scene exceeds its size bound");
            if (request.stopping) return;
            request.publish(State.STARTING, "Opening GPU preview window…");
            child = starter.start(scene);
            final Process process = child;
            stdout =
                    Thread.ofVirtual()
                            .name("gpu-preview-status")
                            .start(() -> readLines(process.getInputStream(), request, true));
            stderr =
                    Thread.ofVirtual()
                            .name("gpu-preview-log")
                            .start(() -> readLines(process.getErrorStream(), request, false));
            long deadline = System.nanoTime() + readyNanos;
            while (child.isAlive() && !request.stopping && !request.protocolFailure) {
                if (!request.ready && System.nanoTime() - deadline >= 0)
                    throw new IOException("GPU preview did not become ready");
                child.waitFor(100, TimeUnit.MILLISECONDS);
            }
            if (!request.stopping && child.isAlive())
                throw new IOException("GPU preview failed during startup");
            if (!request.stopping && !child.isAlive() && child.exitValue() != 0)
                throw new IOException("GPU preview process exited unexpectedly");
        } catch (Throwable error) {
            failure = error;
            request.publish(State.FAILED, request.failureMessage());
        } finally {
            if (child != null) {
                try {
                    if (child.isAlive()) {
                        request.publish(State.STOPPING, "Closing GPU preview window…");
                        stopChild(child);
                    }
                } catch (Throwable stopFailure) {
                    if (failure == null) failure = stopFailure;
                    else failure.addSuppressed(stopFailure);
                }
                closeStreams(child);
                joinReader(stdout);
                joinReader(stderr);
            }
            if (child != null && !request.stopping && !request.ready && failure == null) {
                failure = new IOException("GPU preview closed before its first frame");
            }
            boolean terminated = child == null || !child.isAlive();
            if (terminated) {
                try {
                    if (scene != null) Files.deleteIfExists(scene);
                    if (directory != null) Files.deleteIfExists(directory);
                } catch (IOException cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            } else {
                synchronized (gate) {
                    closed = true;
                    if (desired != null && desired != request) {
                        desired.drained.completeExceptionally(
                                new IOException("Previous GPU process did not stop"));
                    }
                    desired = null;
                    drained.completeExceptionally(new IOException("GPU process did not stop"));
                }
            }
            if (failure == null) {
                request.publish(State.CLOSED, "GPU preview window closed");
                request.drained.complete(null);
            } else {
                request.publish(State.FAILED, request.failureMessage());
                request.drained.completeExceptionally(failure);
            }
        }
    }

    private void stopChild(Process child) throws IOException, InterruptedException {
        try {
            child.getOutputStream().write("CLOSE\n".getBytes(StandardCharsets.US_ASCII));
            child.getOutputStream().flush();
        } catch (IOException ignored) {
            /* A dying child may already have closed stdin. */
        }
        if (child.waitFor(closeMillis, TimeUnit.MILLISECONDS)) return;
        child.destroy();
        if (child.waitFor(250, TimeUnit.MILLISECONDS)) return;
        child.destroyForcibly();
        if (!child.waitFor(2000, TimeUnit.MILLISECONDS))
            throw new IOException("GPU process did not terminate");
    }

    private void readLines(java.io.InputStream input, Request request, boolean protocol) {
        try (var buffered = new BufferedInputStream(input)) {
            var line = new ByteArrayOutputStream(4096);
            boolean oversized = false;
            int value;
            while ((value = buffered.read()) != -1) {
                if (value == '\n') {
                    if (protocol && !oversized)
                        accept(request, line.toString(StandardCharsets.UTF_8).strip());
                    if (protocol && oversized) request.protocolFailure = true;
                    line.reset();
                    oversized = false;
                } else if (line.size() < 4096) line.write(value);
                else oversized = true;
            }
            if (protocol && !oversized && line.size() != 0)
                accept(request, line.toString(StandardCharsets.UTF_8).strip());
        } catch (IOException ignored) {
            /* Process exit/close terminates stream readers. */
        }
    }

    private static void accept(Request request, String line) {
        if (request.stopping) return;
        if (line.startsWith("VIMI_GPU HELLO ")) {
            String backend = line.substring(15).strip();
            if (backend.isEmpty() || backend.length() > 128 || backend.equalsIgnoreCase("noop")) {
                request.protocolFailure = true;
                return;
            }
            request.backend = backend;
        } else if (line.startsWith("VIMI_GPU READY ")) {
            String backend = line.substring(15).strip();
            if (backend.isEmpty() || !backend.equals(request.backend)) {
                request.protocolFailure = true;
                return;
            }
            request.ready = true;
            request.publish(State.READY, "GPU preview window ready · " + backend);
        } else if (line.startsWith("VIMI_GPU ERROR ")) {
            String details = line.substring(15);
            String code = details.split(" ", 2)[0];
            request.errorCode = code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "PROTOCOL";
            request.protocolFailure = true;
            request.publish(State.FAILED, request.failureMessage());
        }
    }

    private static void closeStreams(Process child) {
        for (var stream :
                List.<AutoCloseable>of(
                        child.getInputStream(), child.getErrorStream(), child.getOutputStream())) {
            try {
                stream.close();
            } catch (Exception ignored) {
                /* Already closed by process exit. */
            }
        }
    }

    private static void joinReader(Thread reader) {
        if (reader == null) return;
        try {
            reader.join(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Process startChild(Path scene) throws IOException {
        var command = new ArrayList<String>();
        boolean windows =
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        command.add(
                Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                        .toString());
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac"))
            command.add("-XstartOnFirstThread");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.vibeminer.rendering.bgfx.GpuViewerMain");
        command.add("--scene");
        command.add(scene.toAbsolutePath().toString());
        command.add("--parent-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        return new ProcessBuilder(command).start();
    }
}
