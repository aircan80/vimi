package com.vimi.gui;

import com.vibeminer.rendering.bgfx.GpuFrameFile;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.bgfx.GpuSceneFile;
import com.vibeminer.rendering.bgfx.GpuTerrainSceneFile;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns one offscreen renderer; process, frame reads and control writes run outside JavaFX. */
final class GpuPreviewService {
    enum State {
        PREPARING,
        STARTING,
        READY,
        STOPPING,
        CLOSED,
        FAILED
    }

    record Status(State state, String message,
            com.vibeminer.rendering.bgfx.TerrainTextureStatus textures) {
        Status(State state, String message) { this(state, message, null); }
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

    @FunctionalInterface
    interface InlineStarter {
        Process start(Path scene, Path frames) throws IOException;
    }

    private sealed interface Control permits ViewRequest, TerrainRequest {
        long revision();
        int width();
        int height();
        String command();
    }

    record ViewRequest(
            long revision,
            int width,
            int height,
            double yaw,
            double pitch,
            double zoom,
            int flags) implements Control {
        ViewRequest {
            if (revision < 1
                    || width < 1
                    || width > 960
                    || height < 1
                    || height > 720
                    || !Double.isFinite(yaw)
                    || !Double.isFinite(pitch)
                    || !Double.isFinite(zoom)
                    || pitch < -1.1
                    || pitch > 1.1
                    || zoom < .4
                    || zoom > 3
                    || flags < 0
                    || flags > 15) throw new IllegalArgumentException("Invalid inline view");
        }

        public String command() {
            return "VIEW " + revision + " " + width + " " + height + " " + yaw + " " + pitch + " "
                    + zoom + " " + flags + "\n";
        }
    }

    record TerrainRequest(long revision, int width, int height, TerrainCamera camera)
            implements Control {
        TerrainRequest {
            Objects.requireNonNull(camera);
            if (revision < 1 || width < 1 || width > 800 || height < 1 || height > 500) {
                throw new IllegalArgumentException("Invalid inline terrain view");
            }
        }

        public String command() {
            return "WORLD " + revision + " " + width + " " + height + " " + camera.x()
                    + " " + camera.y() + " " + camera.z() + " " + camera.yaw()
                    + " " + camera.pitch() + "\n";
        }
    }

    record Frame(
            long sequence,
            long controlRevision,
            int width,
            int height,
            IntBuffer pixels,
            long bridgeNanos) {
        Frame(long sequence, long controlRevision, int width, int height, IntBuffer pixels) {
            this(sequence, controlRevision, width, height, pixels, 0);
        }
    }

    interface Frames extends AutoCloseable {
        Frame readLatest(long afterSequence) throws IOException;

        @Override
        void close() throws IOException;
    }

    @FunctionalInterface
    interface FrameFactory {
        Frames create(Path path) throws IOException;
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
    private final InlineStarter starter;
    private final WorldStreamTransport.Starter streamingStarter;
    private volatile com.vibeminer.rendering.bgfx.WorldGlobalTransport.Starter globalStarter =
            GpuPreviewService::startGlobalChild;
    private final FrameFactory frameFactory;
    private final boolean nativeDefaults;
    private final Path temporaryParent;
    private final long readyNanos, closeMillis;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private Request desired, active;
    private boolean running, closed;

    private enum RequestKind { MODEL, COLD_TERRAIN, STREAMING, GLOBAL }

    /** Runs on the existing lifecycle lane, off FX, inside actual Source authority. */
    @FunctionalInterface
    interface GlobalPreparer {
        WorldGlobalSourceBridge.Prepared prepare(Path requestParent,
                com.vibeminer.access.Cancellation cancellation) throws IOException;
    }

    /**
     * Small metadata handed to the view: the live session, its inspection camera and the detached
     * recorded group/floor/entrance outline.
     */
    record GlobalOpened(com.vibeminer.rendering.bgfx.WorldGlobalTransport.Session session,
            com.vibeminer.rendering.bgfx.WorldGlobalTransport.Camera initial,
            GlobalInteriorOutline outline) {
        GlobalOpened {
            java.util.Objects.requireNonNull(outline);
        }
    }

    private record Global(GlobalPreparer preparer, Consumer<GlobalOpened> opened) {}

    /** Detached, descriptive only: an unreadable outline never blocks the qualified render. */
    private static GlobalInteriorOutline outline(WorldGlobalSourceBridge.Prepared prepared) {
        try {
            return GlobalInteriorOutline.of(prepared.facts().groups(), prepared.bounds());
        } catch (RuntimeException unreadable) {
            return GlobalInteriorOutline.unreadable();
        }
    }

    private record Streaming(WorldStreamTransport.Spec spec,
            Consumer<WorldStreamTransport.Session> opened) {}

    private static final class Request {
        final RequestKind kind;
        final Streaming streaming;
        final Global global;
        final Object owner;
        final PreviewMesh mesh;
        final TerrainScene terrain;
        final WorldTextureSnapshot textures;
        volatile com.vibeminer.rendering.bgfx.TerrainTextureStatus textureStatus;
        final MeshAppearance appearance;
        final Set<Integer> parts;
        final Consumer<Status> listener;
        final Consumer<Frame> frames;
        final AtomicReference<Control> view = new AtomicReference<>();
        volatile long revision;
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        volatile boolean stopping, ready, protocolFailure;
        volatile String backend = "", errorCode = "";

        String failureMessage() {
            return "GPU preview unavailable"
                    + (errorCode.isEmpty() ? "" : " (" + errorCode + ")")
                    + ". Using the software preview.";
        }

        Request(
                Object owner,
                PreviewMesh mesh,
                MeshAppearance appearance,
                Set<Integer> parts,
                Consumer<Status> listener,
                Consumer<Frame> frames) {
            this.kind = RequestKind.MODEL;
            this.streaming = null;
            this.global = null;
            this.frames = frames;
            this.owner = owner;
            this.mesh = mesh;
            this.terrain = null;
            this.textures = null;
            this.appearance = appearance;
            this.parts = Set.copyOf(parts);
            this.listener = listener;
        }

        Request(Object owner, TerrainScene terrain, WorldTextureSnapshot textures,
                Consumer<Status> listener, Consumer<Frame> frames) {
            this.kind = RequestKind.COLD_TERRAIN;
            this.streaming = null;
            this.global = null;
            this.owner = owner;
            this.terrain = terrain;
            this.textures = textures == null ? null : textures.retain();
            this.mesh = null;
            this.appearance = null;
            this.parts = Set.of();
            this.listener = listener;
            this.frames = frames;
        }

        Request(Object owner, Streaming streaming, Consumer<Status> listener) {
            this.kind = RequestKind.STREAMING;
            this.streaming = streaming;
            this.global = null;
            this.owner = owner;
            this.listener = listener;
            this.frames = ignored -> {};
            this.mesh = null;
            this.terrain = null;
            this.textures = null;
            this.appearance = null;
            this.parts = Set.of();
        }

        Request(Object owner, Global global, Consumer<Status> listener) {
            this.kind = RequestKind.GLOBAL;
            this.streaming = null;
            this.global = global;
            this.owner = owner;
            this.listener = listener;
            this.frames = ignored -> {};
            this.mesh = null;
            this.terrain = null;
            this.textures = null;
            this.appearance = null;
            this.parts = Set.of();
        }

        void finish(Throwable failure) {
            // Release owned claims before completing any drain view. Completion callbacks may run
            // before earlier callbacks, so resource retirement cannot be a whenComplete listener.
            if (textures != null) textures.close();
            if (failure == null) drained.complete(null);
            else drained.completeExceptionally(failure);
        }

        void publish(State state, String message) {
            try {
                listener.accept(new Status(state, message, textureStatus));
            } catch (RuntimeException | Error ignored) {
                /* Observational UI delivery cannot interrupt native ownership/cleanup. */
            }
        }
    }

    private GpuPreviewService() {
        this(
                null,
                GpuSceneFile::write,
                GpuPreviewService::startChild,
                GpuPreviewService::createFrames,
                Duration.ofSeconds(20),
                Duration.ofSeconds(3), true);
    }

    GpuPreviewService(
            Path temporaryParent,
            SceneWriter writer,
            Starter starter,
            Duration readyTimeout,
            Duration closeTimeout) {
        this(
                temporaryParent,
                writer,
                (scene, frames) -> starter.start(scene),
                GpuPreviewService::createFrames,
                readyTimeout,
                closeTimeout);
    }

    GpuPreviewService(
            Path temporaryParent,
            SceneWriter writer,
            InlineStarter starter,
            FrameFactory frameFactory,
            Duration readyTimeout,
            Duration closeTimeout) {
        this(temporaryParent, writer, starter, frameFactory, readyTimeout, closeTimeout, false);
    }

    private GpuPreviewService(
            Path temporaryParent, SceneWriter writer, InlineStarter starter,
            FrameFactory frameFactory, Duration readyTimeout, Duration closeTimeout,
            boolean nativeDefaults) {
        this(temporaryParent, writer, starter, frameFactory, readyTimeout, closeTimeout,
                nativeDefaults, GpuPreviewService::startStreamingChild);
    }

    GpuPreviewService(Path temporaryParent, SceneWriter writer, InlineStarter starter,
            FrameFactory frameFactory, Duration readyTimeout, Duration closeTimeout,
            WorldStreamTransport.Starter streamingStarter) {
        this(temporaryParent, writer, starter, frameFactory, readyTimeout, closeTimeout,
                false, streamingStarter);
    }

    private GpuPreviewService(Path temporaryParent, SceneWriter writer, InlineStarter starter,
            FrameFactory frameFactory, Duration readyTimeout, Duration closeTimeout,
            boolean nativeDefaults, WorldStreamTransport.Starter streamingStarter) {
        this.streamingStarter = Objects.requireNonNull(streamingStarter);
        this.nativeDefaults = nativeDefaults;
        this.frameFactory = Objects.requireNonNull(frameFactory);
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
        return open(owner, mesh, appearance, parts, listener, ignored -> {});
    }

    CompletableFuture<Void> open(
            Object owner,
            PreviewMesh mesh,
            MeshAppearance appearance,
            Set<Integer> parts,
            Consumer<Status> listener,
            Consumer<Frame> frames) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(mesh);
        Objects.requireNonNull(listener);
        var request =
                new Request(
                        owner, mesh, appearance, parts, listener, Objects.requireNonNull(frames));
        return enqueue(request);
    }

    CompletableFuture<Void> openTerrain(Object owner, TerrainScene scene,
            Consumer<Status> listener, Consumer<Frame> frames) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(scene);
        Objects.requireNonNull(listener);
        Objects.requireNonNull(frames);
        return openTerrain(owner, scene, null, listener, frames);
    }

    CompletableFuture<Void> openTerrain(Object owner, TerrainScene scene,
            WorldTextureSnapshot textures, Consumer<Status> listener, Consumer<Frame> frames) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(scene);
        Objects.requireNonNull(listener);
        Objects.requireNonNull(frames);
        if (textures != null && textures.scene() != scene) {
            throw new IllegalArgumentException("Terrain texture snapshot belongs to another scene");
        }
        // A source batch may honestly qualify no atlas. Keep the native height path usable.
        var admitted = textures != null && textures.atlasCount() > 0 ? textures : null;
        return enqueue(new Request(owner, scene, admitted, listener, frames));
    }

    /** Same application GPU owner; the callback posts small controller metadata only. */
    CompletableFuture<Void> openStreaming(Object owner, WorldStreamTransport.Spec spec,
            Consumer<WorldStreamTransport.Session> opened, Consumer<Status> listener) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(spec);
        Objects.requireNonNull(opened);
        Objects.requireNonNull(listener);
        return enqueue(new Request(owner, new Streaming(spec, opened), listener));
    }

    /** Same application GPU owner and gate; metadata enqueue only, no IO on the caller. */
    CompletableFuture<Void> openGlobal(Object owner, GlobalPreparer preparer,
            Consumer<GlobalOpened> opened, Consumer<Status> listener) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(preparer);
        Objects.requireNonNull(opened);
        Objects.requireNonNull(listener);
        return enqueue(new Request(owner, new Global(preparer, opened), listener));
    }

    /** Test seam for the child launcher; production uses the application java command. */
    void globalStarter(com.vibeminer.rendering.bgfx.WorldGlobalTransport.Starter starter) {
        globalStarter = Objects.requireNonNull(starter);
    }

    private CompletableFuture<Void> enqueue(Request request) {
        Request superseded = null;
        boolean supersededPending = false, start = false, rejected, waiting;
        synchronized (gate) {
            rejected = closed;
            waiting = active != null;
            if (!rejected) {
                superseded = desired;
                supersededPending = superseded != null && superseded != active;
                if (superseded != null) superseded.stopping = true;
                desired = request;
                if (active != null) active.stopping = true;
                start = !running;
                running = true;
                gate.notifyAll();
            }
        }
        if (rejected) {
            request.publish(State.FAILED, "GPU preview service is unavailable");
            request.finish(new IllegalStateException("GPU preview service closed"));
            return request.drained.copy();
        }
        if (supersededPending) {
            superseded.publish(State.CLOSED, "GPU preview superseded");
            superseded.finish(null);
        }
        request.publish(State.PREPARING, waiting
                ? "Waiting for the previous GPU renderer to stop…" : "Preparing inline GPU preview…");
        if (start) Thread.ofVirtual().name("gpu-preview-lifecycle").start(this::run);
        var delivery = request.drained.copy();
        delivery.whenComplete(
                (ignored, failure) -> {
                    if (delivery.isCancelled()) cancel(request);
                });
        return delivery;
    }

    void update(Object owner, ViewRequest view) {
        updateControl(owner, view, RequestKind.MODEL);
    }

    void updateTerrain(Object owner, TerrainRequest view) {
        updateControl(owner, view, RequestKind.COLD_TERRAIN);
    }

    private void updateControl(Object owner, Control view, RequestKind kind) {
        Objects.requireNonNull(view);
        synchronized (gate) {
            if (desired != null
                    && desired.kind == kind
                    && desired.owner == owner
                    && !desired.stopping
                    && view.revision() > desired.revision) {
                desired.revision = view.revision();
                desired.view.set(view);
            }
        }
    }

    private static Frames createFrames(Path path) throws IOException {
        return createFrames(path, 960, 720);
    }

    private static Frames createFrames(Path path, int width, int height) throws IOException {
        var mapped = GpuFrameFile.create(path, width, height);
        return new Frames() {
            public Frame readLatest(long after) throws IOException {
                var frame = mapped.readLatest(after);
                return frame == null
                        ? null
                        : new Frame(
                                frame.sequence(),
                                frame.controlRevision(),
                                frame.width(),
                                frame.height(),
                                frame.pixels());
            }

            public void close() throws IOException {
                mapped.close();
            }
        };
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
            request.finish(null);
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
            pending.finish(null);
        }
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new));
    }

    CompletableFuture<Void> closeAsync() {
        Request pending = null;
        boolean idle;
        synchronized (gate) {
            closed = true;
            if (active != null) active.stopping = true;
            if (desired != null && desired != active) pending = desired;
            desired = null;
            idle = !running;
            gate.notifyAll();
        }
        if (idle) drained.complete(null);
        if (pending != null) {
            pending.stopping = true;
            pending.publish(State.CLOSED, "GPU preview closed");
            pending.finish(null);
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
            boolean finish, restart;
            synchronized (gate) {
                active = null;
                running = false;
                finish = closed;
                restart = !closed && desired != null;
                if (restart) running = true;
            }
            if (finish) drained.complete(null);
            else if (restart) Thread.ofVirtual().name("gpu-preview-lifecycle").start(this::run);
        }
    }

    private void performStreaming(Request request) {
        if (request.stopping) {
            request.finish(null);
            return;
        }
        WorldStreamTransport.PreparedSession prepared = null;
        WorldStreamTransport.Session session = null;
        Throwable failure = null;
        boolean actualDrain = false, launchAttempted = false;
        try {
            var parent = temporaryParent == null
                    ? Path.of(System.getProperty("java.io.tmpdir")) : temporaryParent;
            prepared = WorldStreamTransport.prepare(parent, request.streaming.spec());
            if (!request.stopping) {
                request.publish(State.STARTING, "Starting persistent GPU World renderer…");
                launchAttempted = true;
                session = prepared.launch(streamingStarter);
                var initialized = session.initialized();
                var protocol = session.completion();
                var drainedSession = session.terminatedAndDrained();
                boolean opened = false, closeSent = false, failureShown = false;
                while (!drainedSession.isDone()) {
                    if (request.stopping && !closeSent) {
                        closeSent = true;
                        request.publish(State.STOPPING, "Stopping persistent GPU World renderer…");
                        session.closeAsync();
                    }
                    if (!opened && !request.stopping && initialized.isDone()) {
                        initialized.join();
                        opened = true;
                        request.streaming.opened().accept(session);
                        request.publish(State.STARTING, "GPU World renderer started; preparing terrain…");
                    }
                    if (!failureShown && protocol.isCompletedExceptionally()) {
                        failureShown = true;
                        request.publish(State.FAILED, "GPU World unavailable; waiting for renderer cleanup…");
                        request.stopping = true;
                    }
                    Thread.sleep(10);
                }
                drainedSession.join();
                actualDrain = true;
                // Protocol success is separate from actual drain. This wait is bounded and runs
                // on this existing lifecycle lane, never on JavaFX or under the service monitor.
                protocol.get(closeMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable error) {
            String type = error.getClass().getName();
            failure = new IOException("Persistent GPU World lifecycle failed ("
                    + type.substring(0, Math.min(128, type.length())) + ")");
        } finally {
            if (session != null && !actualDrain) {
                request.publish(State.STOPPING, "Waiting for GPU World resources to drain…");
                try {
                    session.closeAsync();
                    session.terminatedAndDrained().join();
                    actualDrain = true;
                } catch (Throwable unconfirmed) {
                    failure = new IOException("GPU World resource drain could not be confirmed");
                }
            }
            if (prepared != null) {
                try {
                    prepared.closeAsync().join();
                    if (!launchAttempted) actualDrain = true;
                } catch (Throwable unconfirmed) {
                    actualDrain = false;
                    failure = new IOException("GPU World preparation resources did not drain");
                }
            }
            if (!actualDrain) failClosed(request);
            if (failure == null) {
                request.publish(State.CLOSED, "Persistent GPU World renderer stopped");
                request.finish(null);
            } else {
                request.publish(State.FAILED, "Persistent GPU World renderer unavailable");
                request.finish(failure);
            }
        }
    }

    private void performGlobal(Request request) {
        if (request.stopping) {
            request.publish(State.CLOSED, "Interior renderer canceled");
            request.finish(null);
            return;
        }
        var cancellation = new com.vibeminer.access.Cancellation();
        var preparing = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread.ofVirtual().name("gpu-global-cancel").start(() -> {
            while (preparing.get()) {
                if (request.stopping) {
                    cancellation.cancel();
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
        WorldGlobalSourceBridge.Prepared prepared = null;
        WorldGlobalSourceBridge.OwnedInput owned = null;
        com.vibeminer.rendering.bgfx.WorldGlobalTransport.Session session = null;
        Throwable failure = null;
        boolean ownedClosed = false, retirementUnknown = false;
        String message = "Interior renderer unavailable";
        try {
            var parent = temporaryParent == null
                    ? Path.of(System.getProperty("java.io.tmpdir")) : temporaryParent;
            request.publish(State.PREPARING, "Reading the instance interior from client data…");
            prepared = request.global.preparer().prepare(parent, cancellation);
            preparing.set(false);
            if (request.stopping) return;
            var initial = com.vibeminer.rendering.bgfx.WorldGlobalTransport.Camera.inspecting(
                    prepared.bounds());
            var outline = outline(prepared);
            owned = prepared.move();
            // MOVE transferred the file; Source payload/credit retires independently.
            var source = prepared;
            prepared = null;
            source.close();
            if (request.stopping) return;
            request.publish(State.STARTING, "Starting GPU interior renderer…");
            session = com.vibeminer.rendering.bgfx.WorldGlobalTransport.launch(
                    parent, owned.input(), null, globalStarter);
            if (!request.stopping) {
                try {
                    request.global.opened().accept(new GlobalOpened(session, initial, outline));
                } catch (RuntimeException | Error ignored) {
                    /* Observational view delivery cannot interrupt native ownership/cleanup. */
                }
            }
            var terminated = session.terminated();
            var consumed = session.inputConsumed();
            var ready = session.ready();
            boolean closeSent = false, readyShown = false;
            while (!terminated.isDone()) {
                if (request.stopping && !closeSent) {
                    closeSent = true;
                    request.publish(State.STOPPING, "Stopping GPU interior renderer…");
                    session.closeAsync();
                }
                if (!ownedClosed && consumed.isDone() && !consumed.isCompletedExceptionally()) {
                    ownedClosed = true;
                    try {
                        owned.close();
                    } catch (RuntimeException | Error deletion) {
                        // Unknown input retirement is fatal and sticky for the service.
                        retirementUnknown = true;
                        throw deletion;
                    }
                }
                if (!readyShown && ready.isDone()) {
                    readyShown = true;
                    if (ready.isCompletedExceptionally()) {
                        request.publish(State.FAILED,
                                "GPU interior renderer failed; waiting for cleanup…");
                        request.stopping = true;
                    } else {
                        request.ready = true;
                        request.publish(State.READY, "GPU interior");
                    }
                }
                Thread.sleep(10);
            }
            terminated.join();
        } catch (Throwable error) {
            failure = error;
            message = globalMessage(error);
            if (error instanceof com.vibeminer.rendering.bgfx.WorldGlobalTransport.UnconfirmedExit) {
                retirementUnknown = true;
            }
        } finally {
            preparing.set(false);
            boolean drained = !retirementUnknown;
            if (session != null) {
                if (!session.terminated().isDone()) {
                    session.closeAsync();
                }
                try {
                    session.terminated().join();
                } catch (Throwable ended) {
                    if (failure == null) failure = ended;
                }
                // Input deletion requires actual process exit, never kill intent; mapping/frames
                // retirement failure is unknown physical retirement.
                drained &= session.exited() && session.retired();
            }
            if (prepared != null) {
                try {
                    prepared.close();
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    drained = false;
                }
            }
            if (owned != null && !ownedClosed && drained
                    && (session == null || session.exited())) {
                try {
                    owned.close();
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    drained = false;
                }
            }
            if (!drained) failClosed(request);
            if (failure == null || drained && request.stopping && canceled(failure)) {
                request.publish(State.CLOSED, "GPU interior renderer stopped");
                request.finish(null);
            } else {
                request.publish(State.FAILED, message);
                request.finish(failure);
            }
        }
    }

    private static boolean canceled(Throwable failure) {
        // Exact standard cancellation, possibly inside future wrappers; nothing else is clean.
        Throwable at = failure;
        for (int depth = 0; depth < 8 && at != null; depth++) {
            if (at.getClass() == java.util.concurrent.CancellationException.class) return true;
            if (!(at instanceof java.util.concurrent.CompletionException)
                    && !(at instanceof java.util.concurrent.ExecutionException)) return false;
            at = at.getCause();
        }
        return false;
    }

    /** Bounded user text; never raw paths, provider messages or arbitrary causes. */
    static String globalMessage(Throwable error) {
        for (Throwable at = error; at != null; at = at.getCause()) {
            if (at instanceof com.vibeminer.concepts.world.WorldTerrainStreaming.Refusal refusal) {
                return switch (refusal.reason()) {
                    case CAPABILITY_UNAVAILABLE ->
                            "This instance is outside the supported interior profile ("
                                    + refusal.site() + ")";
                    case RETRY_LATER -> "Interior source is busy; retry shortly";
                    case SOURCE_INVALIDATED -> "Interior source changed; retry";
                };
            }
            if (at instanceof java.util.concurrent.CancellationException) {
                return "Interior preparation canceled";
            }
            if (at instanceof GlobalUnavailable unavailable) {
                return unavailable.getMessage();
            }
        }
        String type = error.getClass().getSimpleName();
        return "Interior renderer unavailable (" + type.substring(0, Math.min(64, type.length()))
                + ")";
    }

    /** Explicit user-safe unavailability created by application code only. */
    static final class GlobalUnavailable extends IOException {
        private static final long serialVersionUID = 1L;

        GlobalUnavailable(String message) {
            super(message);
        }
    }

    private void failClosed(Request request) {
        Request pending;
        synchronized (gate) {
            closed = true;
            pending = desired != null && desired != request ? desired : null;
            desired = null;
        }
        var failure = new IOException("Previous GPU resources did not drain");
        if (pending != null) {
            pending.publish(State.FAILED, "Waiting for previous GPU renderer cleanup");
            pending.finish(failure);
        }
        drained.completeExceptionally(failure);
    }

    private void perform(Request request) {
        if (request.kind == RequestKind.STREAMING) {
            performStreaming(request);
            return;
        }
        if (request.kind == RequestKind.GLOBAL) {
            performGlobal(request);
            return;
        }
        Path directory = null, scene = null, framePath = null, texturePath = null;
        Frames frames = null;
        Process child = null;
        Thread stdout = null, stderr = null, controls = null;
        Throwable failure = null;
        try {
            if (request.stopping) return;
            directory =
                    temporaryParent == null
                            ? Files.createTempDirectory("vimi-gpu-")
                            : Files.createTempDirectory(temporaryParent, "vimi-gpu-");
            scene = directory.resolve("scene.vgpu");
            if (request.kind == RequestKind.MODEL) {
                writer.write(scene, request.mesh, request.appearance, request.parts);
            } else {
                if (request.textures == null) {
                    GpuTerrainSceneFile.write(scene, request.terrain, () -> request.stopping);
                } else {
                    var geometry = GpuTerrainSceneFile.writeBound(scene, request.terrain, () -> request.stopping);
                    texturePath = directory.resolve("terrain.vatx");
                    final Path destination = texturePath;
                    request.textures.withBindings(bindings -> {
                        try {
                            com.vibeminer.rendering.bgfx.GpuTerrainTextureAnnex.write(
                                    destination, geometry, request.terrain, bindings, () -> request.stopping);
                        } catch (IOException writeFailure) {
                            throw new java.io.UncheckedIOException(writeFailure);
                        }
                    });
                }
            }
            long sceneLimit = request.kind == RequestKind.MODEL ? MAX_SCENE_BYTES : GpuTerrainSceneFile.MAX_BYTES;
            if (Files.size(scene) > sceneLimit)
                throw new IOException("GPU scene exceeds its size bound");
            if (request.stopping) return;
            framePath = directory.resolve("frames.vgpu");
            frames = request.kind == RequestKind.COLD_TERRAIN && nativeDefaults
                    ? createFrames(framePath, 800, 500) : frameFactory.create(framePath);
            request.publish(State.STARTING, "Starting inline GPU renderer…");
            child = request.kind == RequestKind.COLD_TERRAIN && nativeDefaults
                    ? startChild(scene, framePath, true, texturePath) : starter.start(scene, framePath);
            final Process process = child;
            stdout =
                    Thread.ofVirtual()
                            .name("gpu-preview-status")
                            .start(() -> readLines(process.getInputStream(), request, true));
            stderr =
                    Thread.ofVirtual()
                            .name("gpu-preview-log")
                            .start(() -> readLines(process.getErrorStream(), request, false));
            controls =
                    Thread.ofVirtual()
                            .name("gpu-preview-controls")
                            .start(
                                    () -> {
                                        try {
                                            while (process.isAlive() && !request.stopping) {
                                                var view = request.view.getAndSet(null);
                                                if (view != null) {
                                                    process.getOutputStream()
                                                            .write(
                                                                    view.command()
                                                                            .getBytes(
                                                                                    StandardCharsets
                                                                                            .US_ASCII));
                                                    process.getOutputStream().flush();
                                                }
                                                Thread.sleep(15);
                                            }
                                        } catch (IOException | InterruptedException error) {
                                            if (!request.stopping) request.protocolFailure = true;
                                        }
                                    });
            long sequence = 0, presentedRevision = 0;
            long frameProgress = System.nanoTime();
            long deadline = System.nanoTime() + readyNanos;
            while (child.isAlive() && !request.stopping && !request.protocolFailure) {
                if ((!request.ready || sequence == 0) && System.nanoTime() - deadline >= 0)
                    throw new IOException("GPU preview did not become ready");
                if (request.revision <= presentedRevision) frameProgress = System.nanoTime();
                else if (System.nanoTime() - frameProgress >= readyNanos)
                    throw new IOException("GPU preview stopped producing requested frames");
                long readStarted = System.nanoTime();
                var frame = frames.readLatest(sequence);
                if (frame != null) {
                    frame =
                            new Frame(
                                    frame.sequence(),
                                    frame.controlRevision(),
                                    frame.width(),
                                    frame.height(),
                                    frame.pixels(),
                                    System.nanoTime() - readStarted);
                    sequence = frame.sequence();
                    if (frame.controlRevision() > 0
                            && frame.controlRevision() <= request.revision
                            && !request.stopping
                            && !request.protocolFailure
                            && (request.textures == null || request.ready)) {
                        request.frames.accept(frame);
                        presentedRevision = Math.max(presentedRevision, frame.controlRevision());
                        frameProgress = System.nanoTime();
                    }
                }
                child.waitFor(33, TimeUnit.MILLISECONDS);
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
                        request.publish(State.STOPPING, "Stopping inline GPU renderer…");
                        stopChild(child);
                    }
                } catch (Throwable stopFailure) {
                    if (failure == null) failure = stopFailure;
                    else failure.addSuppressed(stopFailure);
                }
                final Process completedChild = child;
                var streamCloser =
                        Thread.ofVirtual()
                                .name("gpu-preview-stream-close")
                                .start(() -> closeStreams(completedChild));
                joinReader(streamCloser);
                joinReader(stdout);
                joinReader(stderr);
                joinReader(controls);
            }
            if (frames != null) {
                try {
                    frames.close();
                } catch (IOException | RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (child != null && !request.stopping && !request.ready && failure == null) {
                failure = new IOException("GPU preview closed before its first frame");
            }
            boolean terminated = child == null || !child.isAlive();
            if (terminated) {
                try {
                    if (texturePath != null) Files.deleteIfExists(texturePath);
                    if (scene != null) Files.deleteIfExists(scene);
                    if (framePath != null) Files.deleteIfExists(framePath);
                    if (directory != null) Files.deleteIfExists(directory);
                } catch (IOException cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            } else {
                failClosed(request);
            }
            if (failure == null) {
                request.publish(State.CLOSED, "Inline GPU renderer stopped");
                request.finish(null);
            } else {
                request.publish(State.FAILED, request.failureMessage());
                request.finish(failure);
            }
        }
    }

    private void stopChild(Process child) throws IOException, InterruptedException {
        Thread.ofVirtual()
                .name("gpu-preview-close")
                .start(
                        () -> {
                            try {
                                child.getOutputStream()
                                        .write("CLOSE\n".getBytes(StandardCharsets.US_ASCII));
                                child.getOutputStream().flush();
                            } catch (IOException ignored) {
                                /* Child may already be gone. */
                            }
                        });
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
        if (request.stopping || request.protocolFailure) return;
        if (line.startsWith("VIMI_GPU HELLO ")) {
            String backend = line.substring(15).strip();
            if (backend.isEmpty() || backend.length() > 128 || backend.equalsIgnoreCase("noop")) {
                request.protocolFailure = true;
                return;
            }
            request.backend = backend;
        } else if (line.startsWith("VIMI_GPU TEXTURES ")) {
            try {
                var textures = com.vibeminer.rendering.bgfx.TerrainTextureStatus.parse(line);
                if (request.kind == RequestKind.MODEL || request.textures == null || request.ready
                        || request.textureStatus != null
                        || textures.sceneTiles() != request.terrain.tiles().size()
                        || textures.activeAtlases() > request.textures.atlasCount()
                        || textures.coveredRects() > request.textures.coveredChunks()) {
                    throw new IllegalArgumentException("Unexpected terrain texture acknowledgement");
                }
                request.textureStatus = textures;
            } catch (IllegalArgumentException invalid) {
                request.errorCode = "TEXTURE_PROTOCOL";
                request.protocolFailure = true;
            }
        } else if (line.startsWith("VIMI_GPU READY ")) {
            String backend = line.substring(15).strip();
            if (backend.isEmpty() || !backend.equals(request.backend)
                    || (request.textures != null && request.textureStatus == null)) {
                request.protocolFailure = true;
                return;
            }
            request.ready = true;
            request.publish(State.READY, "Inline GPU renderer ready · " + backend);
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

    private static Process startChild(Path scene, Path frames) throws IOException {
        return startChild(scene, frames, false);
    }

    private static Process startChild(Path scene, Path frames, boolean terrain) throws IOException {
        return startChild(scene, frames, terrain, null);
    }

    private static Process startChild(Path scene, Path frames, boolean terrain, Path textures) throws IOException {
        var command = nativeCommand();
        command.add(terrain ? "--terrain-scene" : "--scene");
        command.add(scene.toAbsolutePath().toString());
        if (textures != null) {
            command.add("--terrain-textures");
            command.add(textures.toAbsolutePath().toString());
        }
        command.add("--frames");
        command.add(frames.toAbsolutePath().toString());
        command.add("--parent-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        return new ProcessBuilder(command).start();
    }

    private static Process startGlobalChild(List<String> arguments) throws IOException {
        var command = nativeCommand();
        command.addAll(arguments);
        return new ProcessBuilder(command).start();
    }

    private static Process startStreamingChild(List<String> arguments) throws IOException {
        var command = nativeCommand();
        command.addAll(arguments);
        return new ProcessBuilder(command).start();
    }

    private static ArrayList<String> nativeCommand() {
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
        return command;
    }
}
