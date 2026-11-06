package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainStreaming;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainGround;

import java.lang.ref.WeakReference;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One bounded source/commit actor. No view widgets, legacy scenes, or application GPU launch. */
final class WorldStreamSourceController {
    record Request(
            long viewGeneration,
            BuildWorld world,
            WorldService.MapInfo map,
            WorldService.Inventory inventory,
            WorldFormats.Tile origin,
            WorldFormats.AreaPoint areaPoint,
            WorldCurrentArea.Index areaIndex) {
        Request {
            Objects.requireNonNull(world);
            Objects.requireNonNull(map);
            Objects.requireNonNull(inventory);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(areaIndex);
            if (viewGeneration < 0
                    || inventory.world() == null
                    || inventory.world().tiles().size() > 4096) {
                throw new IllegalArgumentException("World request bounds");
            }
        }
    }

    interface GpuStarter {
        GpuOwner start(String qualifiedScopeSha256);
    }

    interface GpuOwner {
        CompletableFuture<WorldStreamTransport.Session> session();

        CompletableFuture<Void> closeAsync();
    }

    interface Events {
        void changed();
    }

    enum Phase {
        QUALIFYING,
        STARTING,
        PREPARING,
        UPLOADING,
        COMMITTING,
        PRESENTING,
        READY,
        WAITING_CAPACITY,
        UNAVAILABLE,
        FAILED,
        CLOSED
    }

    record Seed(
            TerrainCamera camera,
            double originX,
            double originY,
            WorldTerrainStreaming.Bounds bounds) {}

    record Snapshot(
            Phase phase,
            String detail,
            int prepared,
            int desired,
            Seed seed,
            WorldCurrentArea.Result area) {}

    /** Detached pixels plus exact receipt metadata. No source/payload handles escape to FX. */
    static final class Presentation {
        private final WorldStreamTransport.Frame frame;
        private final long generation;

        private Presentation(WorldStreamTransport.Frame frame, long generation) {
            this.frame = frame;
            this.generation = generation;
        }

        long sequence() {
            return frame.sequence();
        }

        long cameraRevision() {
            return frame.cameraRevision();
        }

        long policy() {
            return frame.policy();
        }

        int width() {
            return frame.width();
        }

        int height() {
            return frame.height();
        }

        IntBuffer pixels() {
            return frame.pixels();
        }
    }

    private record Entry(
            WorldStreamTransport.Resident resident, WorldStreamSourceBridge.Prepared source) {}

    private static final class Pending {
        final WorldFormats.Tile tile;
        final WorldStreamTransport.TileReservation reservation;
        final WorldStreamSourceBridge.Producer producer;
        volatile boolean useful = true;
        CompletableFuture<WorldStreamTransport.Resident> result;
        CompletableFuture<Void> cancellation;

        Pending(
                WorldFormats.Tile tile,
                WorldStreamTransport.TileReservation reservation,
                WorldTerrainStreaming source,
                com.vibeminer.concepts.world.WorldWaterPreview water,
                WorldTerrainStreaming.Scope scope) {
            this.tile = tile;
            this.reservation = reservation;
            producer =
                    new WorldStreamSourceBridge.Producer(source, water, scope, tile, () -> useful);
        }
    }

    /**
     * One current resident's late BASIC_EXTERIOR objects: a preemptible source preparation on
     * the exterior worker, then the transport's own delivery. Never part of a terrain tile.
     */
    private static final class Exterior {
        final Entry entry;
        final Cancellation cancellation = new Cancellation();
        CompletableFuture<WorldStreamSourceBridge.ExteriorCopy> prepared;
        CompletableFuture<WorldStreamTransport.ObjectsOutcome> attached;
        WorldStreamSourceBridge.ExteriorSummary summary =
                WorldStreamSourceBridge.ExteriorSummary.NONE;
        boolean buildingsFailed;
        WorldTileCreatures.Summary creatures = WorldTileCreatures.Summary.NONE;
        WorldObjectPicks.Tile picks;

        Exterior(Entry entry) {
            this.entry = entry;
        }
    }

    private final Object lock = new Object();
    private final Request request;
    private final GpuStarter starter;
    private final WorldStreamSourceBridge.ExteriorPreparer exteriorPreparer;
    // Worker-owned: at most one late exterior delivery, and the residents already tried.
    private ExecutorService exteriorWorker;
    private Exterior exterior;
    private final java.util.Set<Entry> exteriorTried =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private Events events;
    private final Consumer<Runnable> fxPost;
    private final LongSupplier clock;
    private static final long RETRY_NANOS = 500_000_000L;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-source-controller").factory());
    private final AtomicBoolean scheduled = new AtomicBoolean(), wake = new AtomicBoolean();
    private final AtomicBoolean fxQueued = new AtomicBoolean();
    private final Cancellation qualificationCancellation = new Cancellation();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private volatile boolean closing;
    private volatile WorldStreamTransport.Camera camera;
    private volatile WorldStreamTransport.Session session;
    private List<WorldFormats.Tile> desired;
    private long demandRevision, presentationRevision, acceptedSequence;
    private Snapshot snapshot =
            new Snapshot(Phase.QUALIFYING, "Qualifying world sources", 0, 1, null, null);
    private Presentation queued, borrowed;
    // Opt-in measurement only: when the current receipt was applied, 0 while timings are off.
    private long committedAt;
    private boolean validated;
    // Worker owns every source/transport owner below. FX sees only immutable table aliases under
    // lock.
    private WorldTerrainStreaming source;
    private com.vibeminer.concepts.world.WorldWaterPreview water;
    private CompletableFuture<Boolean> waterTables;
    private long waterTablesStarted;
    private static final long WATER_TABLE_WAIT_NANOS = 15_000_000_000L;
    private WorldTerrainStreaming.Scope scope;
    private GpuOwner gpu;
    private CompletableFuture<WorldStreamTransport.Session> opening;
    private Pending pending;
    private final IdentityHashMap<WorldStreamTransport.Resident, Entry> entries =
            new IdentityHashMap<>();
    private List<Entry> current = List.of(), presented = List.of(), committing = List.of();
    // Worker-owned compact heights of current residents; FX reads only the immutable aggregate.
    private final IdentityHashMap<Entry, Optional<TerrainGround.Tile>> heights =
            new IdentityHashMap<>();
    private volatile TerrainGround ground = TerrainGround.empty();
    // Worker-published pick geometry of the current residents whose late objects are drawn.
    private volatile List<WorldObjectPicks.Tile> objects = List.of();
    // Worker-owned immutable area metadata of current residents, for pure point lookups.
    private final IdentityHashMap<Entry, Optional<WorldFormats.AreaMetadata>> areaMetadata =
            new IdentityHashMap<>();
    private volatile List<WorldFormats.AreaMetadata> areas = List.of();
    private CompletableFuture<WorldStreamTransport.CommitReceipt> commit;
    private WorldStreamTransport.CommitReceipt currentReceipt, acceptedReceipt;
    private Entry staged;
    private final LinkedHashMap<Integer, WorldStreamSourceBridge.RefusalMetadata> unavailable =
            new LinkedHashMap<>();
    private boolean initialBlocked;
    private long initialRetryAt, blockedAt;
    private boolean blockedOnMaterialPool;
    private long sourceRetirementRevision, blockedSourceRetirement;
    private long blockedCapacity = -1, blockedPresentation = -1, blockedDemand = -1;
    private volatile Throwable terminal;
    private volatile String unavailableDetail;
    private CompletableFuture<Void> physicalClosing;
    private boolean initialized;
    private boolean skySuppressed;
    private boolean historicalRaster;
    private WorldFormats.Tile creditTarget;
    private boolean retryCommit;
    private long commitRetryAt, commitRetryCapacity, commitIssuedCapacity;
    // Test seam: runs on the worker after a RETRY_LATER commit refusal, before it is recorded.
    volatile Runnable refusalSeam;
    // Test seam: runs on the worker after a RETRY_LATER tile-preparation refusal, before it is
    // recorded.
    volatile Runnable tileRefusalSeam;

    WorldStreamSourceController(
            Request request, GpuStarter starter, Events events, Consumer<Runnable> fxPost) {
        this(request, starter, events, fxPost, System::nanoTime);
    }

    WorldStreamSourceController(
            Request request,
            GpuStarter starter,
            Events events,
            Consumer<Runnable> fxPost,
            LongSupplier clock) {
        this(request, starter, events, fxPost, clock, WorldStreamSourceBridge::prepareExterior);
    }

    /** Tests replace the exterior source operation. */
    WorldStreamSourceController(
            Request request,
            GpuStarter starter,
            Events events,
            Consumer<Runnable> fxPost,
            LongSupplier clock,
            WorldStreamSourceBridge.ExteriorPreparer exteriorPreparer) {
        this.exteriorPreparer = Objects.requireNonNull(exteriorPreparer);
        this.clock = Objects.requireNonNull(clock);
        this.request = Objects.requireNonNull(request);
        this.starter = Objects.requireNonNull(starter);
        this.events = Objects.requireNonNull(events);
        this.fxPost = Objects.requireNonNull(fxPost);
        desired = List.of(request.origin());
        kick();
    }

    void camera(WorldStreamTransport.Camera next) {
        Objects.requireNonNull(next);
        RuntimeException problem=null;
        synchronized (lock) {
            if (closing) return;
            if (camera != null && next.revision() <= camera.revision()) {
                throw new IllegalArgumentException("Camera revision must advance");
            }
            camera=next;
            // Actual final Session.camera only replaces metadata; no IO/external callback.
            // Use the same gate as initial publication so an older initial camera cannot follow.
            if(session!=null) {
                try { session.camera(next); }
                catch(RuntimeException failure) { problem=failure; }
            }
        }
        if(problem!=null) failLater(problem);
        kick();
    }

    void neighborhood(List<WorldFormats.Tile> ordered) {
        Objects.requireNonNull(ordered);
        if (ordered.size() > 9) throw new IllegalArgumentException("Neighborhood bounds");
        var copy = List.copyOf(ordered);
        for (int i = 0; i < copy.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (same(copy.get(i), copy.get(j)))
                    throw new IllegalArgumentException("Duplicate neighborhood tile");
            }
        }
        synchronized (lock) {
            if (closing || desired.equals(copy)) return;
            desired = copy;
            demandRevision++;
        }
        kick();
    }

    /** Records the capacity revision before the transport can refuse this commit. */
    private java.util.concurrent.CompletableFuture<WorldStreamTransport.CommitReceipt> issueCommit(
            long policy, WorldStreamTransport.Resident[] ordered) {
        commitIssuedCapacity = session.capacityRevision();
        long started = WorldStageTimings.start();
        var issued = session.commit(policy, ordered);
        if (started != 0L) {
            issued.whenComplete(
                    (ignored, failure) ->
                            WorldStageTimings.stop(
                                    failure == null
                                            ? "parent.commit.roundTrip"
                                            : "parent.commit.refusedRoundTrip",
                                    started));
        }
        return issued;
    }

    void pulse() {
        kick();
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    /** Rendered-surface heights of the current resident tiles; never source or payload handles. */
    TerrainGround ground() {
        return closing ? TerrainGround.empty() : ground;
    }

    /**
     * Pick geometry and sources of the exterior objects the current residents draw; immutable,
     * any thread.
     */
    List<WorldObjectPicks.Tile> objects() {
        return closing ? List.of() : objects;
    }

    /**
     * Recorded terrain area at a native point of the current residents. Pure lookup over retained
     * immutable metadata; never borrows a payload, so any thread may call it.
     */
    WorldCurrentArea.Result areaAt(double x, double y) {
        var known = closing ? List.<WorldFormats.AreaMetadata>of() : areas;
        return WorldCurrentArea.locateIn(request.map().id(), x, y, known, request.areaIndex());
    }

    /**
     * Bounded evidence for a stalled-frame report: the phase, how many desired tiles are prepared,
     * whether a presentation is queued or borrowed, and the transport's own summary. Never waits on
     * the worker or the helper.
     */
    String stallSummary() {
        Snapshot current;
        boolean queuedFrame, borrowedFrame;
        long accepted;
        synchronized (lock) {
            current = snapshot;
            queuedFrame = queued != null;
            borrowedFrame = borrowed != null;
            accepted = acceptedSequence;
        }
        var transport = session;
        String helper;
        try {
            helper = transport == null ? "no session" : transport.stallSummary();
        } catch (RuntimeException unavailable) {
            helper = "session summary unavailable";
        }
        return "phase " + current.phase()
                + " (" + WorldDiagnostics.clean(current.detail()) + ")"
                + "; tiles " + current.prepared() + "/" + current.desired()
                + "; accepted frame sequence " + accepted
                + "; presentation " + (borrowedFrame ? "borrowed" : queuedFrame ? "queued" : "none")
                + "; " + helper;
    }

    Optional<Presentation> takePresentation() {
        synchronized (lock) {
            if (closing || borrowed != null || queued == null) return Optional.empty();
            borrowed = queued;
            queued = null;
            validated = false;
            return Optional.of(borrowed);
        }
    }

    boolean mayPresent(Presentation value) {
        synchronized (lock) {
            validated = validPresentation(value);
            return validated;
        }
    }

    boolean presented(Presentation value) {
        boolean accepted;
        synchronized (lock) {
            accepted = validated && validPresentation(value);
            if (borrowed == value) {
                borrowed = null;
                validated = false;
                if (accepted) {
                    if (acceptedReceipt != currentReceipt) {
                        WorldStageTimings.stop("parent.commit.toAcceptedFrame", committedAt);
                        committedAt = 0L;
                    }
                    historicalRaster=false;
                    acceptedSequence = value.sequence();
                    acceptedReceipt = currentReceipt;
                    presented = List.copyOf(current);
                    presentationRevision++;
                }
            }
        }
        kick();
        return accepted;
    }

    void discard(Presentation value) {
        synchronized (lock) {
            if (borrowed == value) {
                borrowed = null;
                validated = false;
            }
        }
        kick();
    }

    private boolean validPresentation(Presentation value) {
        if (closing
                || borrowed != value
                || value.generation != request.viewGeneration()
                || value.sequence() <= acceptedSequence
                || camera == null
                || camera.policy() != value.policy()
                || camera.width() != value.width()
                || camera.height() != value.height()
                || currentReceipt != value.frame.content()) return false;
        // A valid receipt can still draw only sky after motion beyond its resident neighborhood.
        // Keep the last readable pixels until the nearest requested recorded tile is GPU-current.
        if(!desired.isEmpty() && current.stream().noneMatch(entry->same(desired.getFirst(),entry.resident()))) return false;
        for (var entry : current) if (!entry.source().isOpen()) return false;
        return true;
    }

    CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            if (closing) return drained.copy();
            closing = true;
            queued = null;
        }
        // This private token has only the actual engine's coalesced metadata cancellation listener.
        try {
            qualificationCancellation.cancel();
        } catch (RuntimeException | Error failure) {
            failLater(failure);
        }
        kick();
        return drained.copy();
    }

    private void kick() {
        wake.set(true);
        if (scheduled.compareAndSet(false, true)) {
            try {
                worker.execute(this::run);
            } catch (RuntimeException failure) {
                scheduled.set(false);
                if (!drained.isDone()) drained.completeExceptionally(failure);
            }
        }
    }

    private void run() {
        try {
            do {
                wake.set(false);
                if (closing) retire();
                else step();
            } while (wake.get() && !drained.isDone());
        } catch (Throwable failure) {
            if (!(closing && CleanCancellation.is(failure))) {
                if (failure instanceof WorldTerrainStreaming.Refusal refusal
                        && refusal.getCause() == null
                        && refusal.getSuppressed().length == 0
                        && refusal.reason() != WorldTerrainStreaming.Refusal.Reason.RETRY_LATER) {
                    unavailableDetail = "Source unavailable: " + refusal.site();
                } else {
                    terminal = failure;
                    WorldDiagnostics.warn("World streaming failed", failure);
                }
                closing = true;
                publish(
                        unavailableDetail == null ? Phase.FAILED : Phase.UNAVAILABLE,
                        unavailableDetail == null
                                ? "World streaming failed: " + WorldDiagnostics.brief(failure)
                                : unavailableDetail);
            }
            wake.set(true);
        } finally {
            scheduled.set(false);
            if (wake.get() && !drained.isDone()) kick();
        }
    }

    private void failLater(Throwable failure) {
        WorldDiagnostics.warn("World streaming failed", failure);
        synchronized (lock) {
            if (terminal == null) terminal = failure;
            else if(terminal!=failure) terminal.addSuppressed(failure);
            closing = true;
        }
        try { qualificationCancellation.cancel(); }
        catch(RuntimeException|Error cancelFailure) {
            WorldDiagnostics.warn("World qualification cancellation failed", cancelFailure);
            synchronized(lock) {
                if(terminal==null) terminal=cancelFailure;
                else if(terminal!=cancelFailure) terminal.addSuppressed(cancelFailure);
            }
        }
        kick();
    }

    private void step() {
        if (!initialized) {
            if (initialBlocked && clock.getAsLong() - initialRetryAt < RETRY_NANOS) return;
            if(source==null) {
                var capability=request.world().terrainStreaming();
                if(capability.isEmpty()) {
                    unavailableDetail="Qualified source streaming is unavailable";
                    closing=true;
                    publish(Phase.UNAVAILABLE,unavailableDetail);
                    wake.set(true);
                    return;
                }
                source=capability.orElseThrow();
                // Static diagnostic water joins each actual terrain tile; disable with
                // -Dvimi.world.water=false.
                if(!"false".equals(System.getProperty("vimi.world.water")))
                    water=request.world().waterPreview().orElse(null);
                if(water!=null) {
                    waterTablesStarted=clock.getAsLong();
                    waterTables=request.world().prepareWaterTables(qualificationCancellation);
                    waterTables.whenComplete((ignored, failure) -> {
                        // Closing cancels the qualification token: only that is routine.
                        if (failure != null)
                            WorldDiagnostics.degradedUnlessClean("water tables",
                                    "World water tables unavailable; terrain continues without water", failure);
                        kick();
                    });
                }
            }
            try {
                if (scope == null)
                    scope = source.createScope(request.map(), qualificationCancellation);
                source.activate(scope);
            } catch (WorldTerrainStreaming.Refusal refusal) {
                if (refusal.reason() != WorldTerrainStreaming.Refusal.Reason.RETRY_LATER
                        || refusal.getCause() != null
                        || refusal.getSuppressed().length != 0) throw refusal;
                initialBlocked = true;
                initialRetryAt = clock.getAsLong();
                publish(Phase.WAITING_CAPACITY, "Waiting for source capacity: " + refusal.site());
                return;
            }
            initialized = true;
            if (closing) {
                wake.set(true);
                return;
            }
            publish(Phase.STARTING, "Starting persistent GPU viewer");
            gpu = Objects.requireNonNull(starter.start(scope.scopeSha256()));
            opening = gpu.session();
            opening.whenComplete((ignored, failure) -> kick());
            if (closing) {
                wake.set(true);
                return;
            }
        }
        if (session == null) {
            if (!opening.isDone()) return;
            var opened=opening.join();
            synchronized(lock) {
                session=opened;
                if(camera!=null && !closing) opened.camera(camera);
            }
            session.initialized().whenComplete((ignored, failure) -> kick());
            session.completion()
                    .whenComplete(
                            (ignored, failure) -> {
                                if (!closing)
                                    failLater(
                                            failure == null
                                                    ? new IllegalStateException(
                                                            "World session ended")
                                                    : failure);
                            });
        }
        if (!session.initialized().isDone()) return;
        session.initialized().join();
        pollFrame();
        updateArea();
        stepExterior();
        var wanted = demand();
        if(creditTarget!=null && !contains(wanted,creditTarget)) creditTarget=null;
        unavailable.keySet().removeIf(key -> wanted.stream().noneMatch(tile -> key(tile) == key));
        if (pending != null) {
            if (!contains(wanted, pending.tile) && pending.cancellation == null) {
                // Parent's explicit stop MUST precede the bridge's latest=false cancellation.
                pending.cancellation = pending.reservation.cancel();
                pending.useful = false;
                pending.cancellation.whenComplete((ignored, failure) -> kick());
            }
            if (pending.cancellation != null) {
                if (!pending.cancellation.isDone()) return;
                pending.cancellation.join();
                pending.producer.close();
                pending = null;
            } else {
                if (!pending.result.isDone()) return;
                try {
                    var resident = pending.result.join();
                    var prepared = pending.producer.takePrepared();
                    if(creditTarget!=null && same(creditTarget,pending.tile)) creditTarget=null;
                    staged = new Entry(resident, prepared);
                    entries.put(resident, staged);
                    seed(prepared);
                } catch (CompletionException failure) {
                    if (failure.getClass() != CompletionException.class
                            || failure.getSuppressed().length != 0
                            || !(failure.getCause()
                                    instanceof WorldStreamTransport.PreparationRefusal refusal)
                            || refusal.getCause() != null
                            || refusal.getSuppressed().length != 0) throw failure;
                    var origin = pending.producer.sourceRefusal();
                    int failedTile = key(pending.tile);
                    // Read while the refused reservation is still at hand (#283).
                    long refusedCapacity = pending.reservation.capacityBaseline();
                    if(creditTarget!=null && same(creditTarget,pending.tile)
                            && refusal.reason()!=WorldStreamTransport.Refusal.RETRY_LATER) creditTarget=null;
                    pending.producer.close();
                    pending = null;
                    if (origin != null && !origin.reason().name().equals(refusal.reason().name()))
                        throw failure;
                    if (origin == null
                            && refusal.reason() != WorldStreamTransport.Refusal.SOURCE_INVALIDATED)
                        throw failure;
                    if (refusal.reason() == WorldStreamTransport.Refusal.CAPABILITY_UNAVAILABLE
                            && origin != null
                            && origin.reason().name().equals(refusal.reason().name())
                            && tileUnavailable(origin)) {
                        unavailable.put(failedTile, origin);
                        publish(Phase.UNAVAILABLE, "Terrain unavailable: " + origin.site());
                        wake.set(true);
                        return;
                    }
                    if (refusal.reason() != WorldStreamTransport.Refusal.RETRY_LATER) {
                        unavailableDetail =
                                "Source unavailable: "
                                        + (origin == null
                                                ? refusal.reason().name()
                                                : origin.site());
                        closing = true;
                        publish(Phase.UNAVAILABLE, unavailableDetail);
                        wake.set(true);
                        return;
                    }
                    // This refusal's own unused-credit ACK is not a source-pool wakeup.
                    blockedOnMaterialPool = origin != null && origin.site().equals("MATERIAL_POOL");
                    blockedSourceRetirement = sourceRetirementRevision;
                    var seam = tileRefusalSeam;
                    if (seam != null) seam.run();
                    blockedAt = clock.getAsLong();
                    // The revision the refused reservation itself accounts for, not the current
                    // one: a retirement or credit that landed after it was issued (even between
                    // the refusal and this step) must still wake the retry (#283). The
                    // reservation's own drain and retirement are excluded; its unused-credit
                    // CREDIT arrives as an ordinary capacity change and deliberately wakes one
                    // retry per refusal, as before (polling while the geometry lane is busy is
                    // pre-existing and tracked separately).
                    blockedCapacity = refusedCapacity;
                    synchronized (lock) {
                        blockedPresentation = presentationRevision;
                        blockedDemand = demandRevision;
                    }
                    publish(Phase.WAITING_CAPACITY, "Waiting for streaming resource capacity");
                    return;
                }
                pending.producer.close();
                pending = null;
            }
        }
        if(retryCommit) {
            if(session.capacityRevision()==commitRetryCapacity && clock.getAsLong()-commitRetryAt<RETRY_NANOS) return;
            commit=issueCommit(camera.policy(),committing.stream().map(Entry::resident)
                    .toArray(WorldStreamTransport.Resident[]::new));
            retryCommit=false;
            commit.whenComplete((ignored,failure)->kick());
            publish(Phase.COMMITTING,"Activating prepared terrain");
            return;
        }
        if (commit != null) {
            if (!commit.isDone()) return;
            synchronized (lock) {
                if (borrowed != null) return;
            }
            WorldStreamTransport.CommitReceipt receipt;
            try { receipt=commit.join(); }
            catch(CompletionException failure) {
                if(failure.getClass()!=CompletionException.class || failure.getSuppressed().length!=0
                        || !(failure.getCause() instanceof WorldStreamTransport.PreparationRefusal refusal)
                        || refusal.reason()!=WorldStreamTransport.Refusal.RETRY_LATER
                        || refusal.getCause()!=null || refusal.getSuppressed().length!=0) throw failure;
                commit=null;
                var seam=refusalSeam;
                if(seam!=null) seam.run();
                retryCommit=true;
                commitRetryAt=clock.getAsLong();
                // Capacity observed when the refused commit was issued, not now: a retirement that
                // landed between the refusal and this step must still count as a change, or a
                // frozen clock (and in production, 500 ms) waits for a revision already seen.
                commitRetryCapacity=commitIssuedCapacity;
                publish(Phase.WAITING_CAPACITY,"Waiting for commit input retirement");
                return;
            }
            synchronized (lock) {
                current = committing;
                currentReceipt = receipt;
                committedAt = WorldStageTimings.start();
                skySuppressed=false;
                committing = List.of();
            }
            commit = null;
            pollFrame();
        }
        if (staged != null) {
            var latest = camera;
            if (latest == null) return;
            var target = new ArrayList<>(current);
            if (target.size() == 9) {
                int outgoing = target.size() - 1;
                for (int i = target.size() - 1; i >= 0; i--) {
                    if (!containsResident(wanted, target.get(i).resident())) {
                        outgoing = i;
                        break;
                    }
                }
                target.remove(outgoing);
            }
            target.add(staged);
            staged = null;
            committing = List.copyOf(target);
            commit =
                    issueCommit(
                            latest.policy(),
                            target.stream()
                                    .map(Entry::resident)
                                    .toArray(WorldStreamTransport.Resident[]::new));
            commit.whenComplete((ignored, failure) -> kick());
            publish(Phase.COMMITTING, "Activating prepared terrain");
            return;
        }
        retireUnused();
        updateArea();
        if(!wanted.isEmpty() && unavailable.containsKey(key(wanted.getFirst()))
                && current.stream().noneMatch(entry->same(wanted.getFirst(),entry.resident()))) {
            publish(Phase.UNAVAILABLE,"Nearest terrain unavailable: "+unavailable.get(key(wanted.getFirst())).site());
            return;
        }
        synchronized (lock) {
            if ((blockedOnMaterialPool || blockedCapacity == session.capacityRevision())
                    && (blockedOnMaterialPool
                            ? blockedSourceRetirement == sourceRetirementRevision
                            : blockedPresentation == presentationRevision)
                    && blockedDemand == demandRevision
                    && clock.getAsLong() - blockedAt < RETRY_NANOS) return;
        }
        boolean needsSource=wanted.stream().anyMatch(tile->!unavailable.containsKey(key(tile))
                && current.stream().noneMatch(entry->same(tile,entry.resident())));
        if (entries.size() >= 12 && needsSource) {
            // Any missing center needs a source credit, even if never previously presented.
            boolean missingCenter=!wanted.isEmpty() && current.stream().noneMatch(
                    entry->same(wanted.getFirst(),entry.resident()));
            boolean needsUsefulFrame=missingCenter || skySuppressed;
            var subset=new ArrayList<>(current.stream().filter(
                    entry->containsResident(wanted,entry.resident())).toList());
            boolean releases=current.stream().anyMatch(entry->!subset.contains(entry) && !presented.contains(entry));
            if(needsUsefulFrame && !releases) {
                Entry victim=null;
                int farthest=-1;
                for(var entry:current) {
                    if(presented.contains(entry) || !wanted.isEmpty() && same(wanted.getFirst(),entry.resident())) continue;
                    int rank=wanted.size();
                    for(int i=0;i<wanted.size();i++) if(same(wanted.get(i),entry.resident())) { rank=i; break; }
                    if(rank>farthest) { farthest=rank; victim=entry; }
                }
                if(victim!=null) { subset.remove(victim); releases=true; }
            }
            if(needsUsefulFrame && releases && camera!=null) {
                creditTarget=wanted.stream().filter(tile->!unavailable.containsKey(key(tile))
                        && current.stream().noneMatch(entry->same(tile,entry.resident()))).findFirst().orElse(null);
                committing=List.copyOf(subset);
                commit=issueCommit(camera.policy(),subset.stream().map(Entry::resident)
                        .toArray(WorldStreamTransport.Resident[]::new));
                commit.whenComplete((ignored,failure)->kick());
                publish(Phase.COMMITTING,"Making room for nearest terrain");
                return;
            }
            publish(Phase.WAITING_CAPACITY, "Waiting for accepted viewport pixels");
            return;
        }
        var admissionOrder=new ArrayList<>(wanted);
        boolean centerMissing=!wanted.isEmpty() && current.stream().noneMatch(entry->same(wanted.getFirst(),entry.resident()));
        if(!centerMissing && creditTarget!=null && contains(admissionOrder,creditTarget)) {
            var priority=creditTarget;
            admissionOrder.removeIf(tile->same(tile,priority));
            admissionOrder.addFirst(priority);
        }
        if(waterTables!=null && !waterTables.isDone()
                && clock.getAsLong()-waterTablesStarted<WATER_TABLE_WAIT_NANOS) {
            // Bounded wait so the first tiles can carry water; terrain proceeds after timeout.
            publish(Phase.PREPARING, "Preparing water tables");
            return;
        }
        if (exterior != null && exterior.prepared != null
                && admissionOrder.stream().anyMatch(tile -> !unavailable.containsKey(key(tile))
                        && current.stream().noneMatch(entry -> same(tile, entry.resident())))) {
            // The source admits one operation at a time: terrain preempts object preparation and
            // starts as soon as the canceled preparation has drained (its completion kicks).
            exterior.cancellation.cancel();
            publish(Phase.PREPARING, "Preparing terrain, textures and upload");
            return;
        }
        for (var tile : admissionOrder) {
            if (unavailable.containsKey(key(tile))) continue;
            boolean found = false;
            for (var entry : current)
                if (same(tile, entry.resident())) {
                    found = true;
                    break;
                }
            if (found) continue;
            var reservation = session.tryReserveTile(tile.x(), tile.y());
            if (reservation.isEmpty()) {
                publish(Phase.WAITING_CAPACITY, "Waiting for GPU upload capacity");
                return;
            }
            blockedOnMaterialPool = false;
            pending = new Pending(tile, reservation.orElseThrow(), source, waterReady() ? water : null, scope);
            long producing = WorldStageTimings.start();
            pending.result = pending.reservation.produce(pending.producer);
            pending.result.whenComplete((ignored, failure) -> {
                WorldStageTimings.stop(failure == null ? "parent.tile.produceToStaged"
                        : "parent.tile.produceFailed", producing);
                kick();
            });
            publish(Phase.PREPARING, "Preparing terrain, textures and upload");
            return;
        }
        // Terrain is idle: every wanted tile is current or unavailable, nothing is staged,
        // committing or pending. Only now may object preparation take the source lane.
        startExterior(wanted);
        publish(
                wanted.isEmpty() || current.isEmpty() && !unavailable.isEmpty()
                        ? Phase.UNAVAILABLE
                        : acceptedReceipt == currentReceipt && currentReceipt != null
                                ? Phase.READY
                                : Phase.PRESENTING,
                wanted.isEmpty()
                        ? "No recorded terrain near this camera"
                        : current.isEmpty() && !unavailable.isEmpty()
                                ? "Terrain unavailable: "
                                        + unavailable.values().iterator().next().site()
                                : acceptedReceipt == currentReceipt && currentReceipt != null
                                        ? readyDetail()
                                        : "Waiting for accepted viewport pixels");
    }

    private void startExterior(List<WorldFormats.Tile> wanted) {
        if (exterior != null || closing || session == null || !session.exteriorObjects()) return;
        for (var tile : wanted) {
            for (var entry : current) {
                if (!same(tile, entry.resident())
                        || exteriorTried.contains(entry)
                        || entry.resident().exteriorObjects()
                                != WorldStreamTransport.ExteriorObjects.NONE) continue;
                var work = new Exterior(entry);
                if (exteriorWorker == null)
                    exteriorWorker = Executors.newSingleThreadExecutor(
                            Thread.ofPlatform().daemon().name("world-exterior-objects").factory());
                var lane = source;
                var owner = scope;
                work.prepared = CompletableFuture.supplyAsync(
                        () -> {
                            long preparing = WorldStageTimings.start();
                            try {
                                return exteriorPreparer.prepare(lane, owner, tile, work.cancellation);
                            } finally {
                                WorldStageTimings.stop(work.cancellation.isCanceled()
                                        ? "parent.objects.prepareCanceled" : "parent.objects.prepare",
                                        preparing);
                            }
                        },
                        exteriorWorker);
                exterior = work;
                work.prepared.whenComplete((ignored, failure) -> kick());
                return;
            }
        }
    }

    /** Advances the one late exterior delivery; its failures never reach terrain. */
    private void stepExterior() {
        var work = exterior;
        if (work == null) return;
        if (work.prepared != null) {
            if (!work.prepared.isDone()) {
                if (!current.contains(work.entry)) work.cancellation.cancel();
                return;
            }
            WorldStreamSourceBridge.ExteriorCopy copy;
            try {
                copy = work.prepared.join();
            } catch (RuntimeException failed) {
                exterior = null;
                // Preempted by terrain (or retired): untried, so it restarts when terrain idles.
                if (!work.cancellation.isCanceled() && live(work.entry)) {
                    WorldDiagnostics.degraded("exterior objects",
                            "World exterior objects preparation failed; tile kept as terrain only", failed);
                    exteriorTried.add(work.entry);
                    work.entry.source().exteriorFailedLate();
                }
                return;
            }
            work.prepared = null;
            if (copy.exterior() == null || closing || !current.contains(work.entry)) {
                exterior = null;
                if (!live(work.entry)) return;
                if (copy.failed()) work.entry.source().exteriorFailedLate();
                if (copy.exterior() == null) work.entry.source().creatures(copy.creatures());
                if (copy.exterior() == null || copy.failed()) exteriorTried.add(work.entry);
                return;
            }
            work.summary = copy.summary();
            work.buildingsFailed = copy.failed();
            work.creatures = copy.creatures();
            work.picks = copy.picks();
            long attaching = WorldStageTimings.start();
            work.attached = session.attachObjects(work.entry.resident(), copy.exterior());
            work.attached.whenComplete((ignored, failure) -> {
                WorldStageTimings.stop("parent.objects.attachRoundTrip", attaching);
                kick();
            });
            return;
        }
        if (!work.attached.isDone()) return;
        exterior = null;
        // Retired (and its source closed) while the attach was in flight: nothing to record.
        if (!live(work.entry)) {
            changed();
            return;
        }
        exteriorTried.add(work.entry);
        try {
            switch (work.attached.join()) {
                case ATTACHED -> {
                    work.entry.source().exteriorAttached(work.summary);
                    work.entry.source().creatures(work.creatures);
                    work.entry.source().picks(work.picks);
                    if (work.buildingsFailed) work.entry.source().exteriorFailedLate();
                    updateObjects();
                }
                case FAILED -> {
                    // Write, read or upload failed; the helper reports no detail of its own.
                    WorldDiagnostics.degraded("exterior objects",
                            "World exterior objects delivery failed; tile kept as terrain only", null);
                    work.entry.source().exteriorFailedLate();
                }
                case DROPPED -> {}
            }
        } catch (RuntimeException failed) {
            // A stopping session cancels delivery; anything else fails only these objects.
            if (!closing && !CleanCancellation.is(failed)) work.entry.source().exteriorFailedLate();
            // Same rule as the guard above: a clean cancellation is routine, anything else is logged.
            if (!closing) WorldDiagnostics.degradedUnlessClean("exterior objects",
                    "World exterior objects delivery failed; tile kept as terrain only", failed);
        }
        changed();
    }

    /** Drawn creature models and the per-reason counts of spawn points not drawn. */
    static String creatureDetail(WorldTileCreatures.Summary creatures, int tiles) {
        var text = new StringBuilder();
        if (creatures.drawn() > 0)
            text.append("; ").append(creatures.drawn()).append(creatures.drawn() == 1 ? " static creature model on " : " static creature models on ").append(tiles)
                    .append(tiles == 1 ? " tile" : " tiles").append(" (rest pose, facing north)");
        if (creatures.omitted() > 0) {
            text.append("; spawns not drawn: ");
            String separator = "";
            for (var count : WorldTileCreatures.Count.values()) {
                if (count == WorldTileCreatures.Count.DRAWN || creatures.count(count) == 0) continue;
                text.append(separator).append(creatures.count(count)).append(' ').append(count.label);
                separator = ", ";
            }
        }
        if (creatures.drawn() == 0 && creatures.omitted() == 0 && creatures.note() != null)
            text.append("; creatures: ").append(creatures.note());
        return text.toString();
    }

    /** Whether this entry still owns its resident, i.e. retireUnused has not closed it. */
    private boolean live(Entry entry) {
        return entries.get(entry.resident()) == entry;
    }

    private boolean waterReady() {
        if(water==null || waterTables==null || !waterTables.isDone()) return false;
        try { return waterTables.getNow(false); }
        catch(RuntimeException failed) { return false; }
    }

    /**
     * Drawn BASIC exterior objects and, when any were not drawn, what was omitted. Counts are the
     * attached tiles' source records; nothing omitted is drawn approximately.
     */
    static String objectsDetail(WorldStreamSourceBridge.ExteriorSummary s, int tiles) {
        var text = new StringBuilder("; ").append(s.buildings()).append(" BASIC exterior buildings");
        if (s.doodads() > 0) text.append(" and ").append(s.doodads()).append(" doodads");
        text.append(" on ").append(tiles);
        if (s.omitted() > 0) {
            var parts = new java.util.ArrayList<String>();
            if (s.buildingsOmitted() > 0) parts.add(plural(s.buildingsOmitted(), "building"));
            if (s.doodadsOmitted() > 0) {
                parts.add(plural(s.doodadsOmitted(), "doodad")
                        + (s.doodadsCapped() > 0 ? " (" + s.doodadsCapped() + " capped)" : ""));
            }
            if (s.surfacesOmitted() > 0) {
                parts.add(plural(s.surfacesOmitted(), "surface")
                        + (s.unreadable() > 0 ? " (" + s.unreadable() + " unreadable)" : ""));
            }
            text.append(" (not drawn: ").append(String.join(", ", parts)).append(')');
        }
        return text.toString();
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private String readyDetail() {
        int textured = 0, partial = 0, height = 0, watered = 0;
        int buildingTiles = 0, buildingFailures = 0, creatureTiles = 0;
        var objects = WorldStreamSourceBridge.ExteriorSummary.NONE;
        var creatures = WorldTileCreatures.Summary.NONE;
        String reason = "";
        for (var entry : current) {
            if (entry.source().waterCells() > 0) watered++;
            var tileCreatures = entry.source().creatures();
            if (tileCreatures.drawn() > 0) creatureTiles++;
            creatures = creatures.plus(tileCreatures);
            switch (entry.resident().exteriorObjects()) {
                case DRAWN -> {
                    var summary = entry.source().exteriorSummary();
                    if (summary.buildings() > 0 || summary.doodads() > 0) buildingTiles++;
                    objects = objects.plus(summary);
                    if (entry.source().exteriorFailed()) buildingFailures++;
                }
                case FAILED -> buildingFailures++;
                default -> {
                    if (entry.source().exteriorFailed()) buildingFailures++;
                }
            }
            var facts = entry.source().facts();
            if (facts.outcome() == WorldTerrainStreaming.Outcome.TEXTURED) {
                textured++;
                if (entry.resident().coveredRectangles() < 256
                        || facts.materialState() == WorldTerrainTextureSource.State.PARTIAL) partial++;
            } else {
                height++;
                reason =
                        facts.terminalReason() == null
                                ? facts.fallback().name()
                                : facts.terminalReason().name();
            }
        }
        return current.size()
                + " tiles: "
                + textured
                + " textured ("
                + partial
                + " partial), "
                + height
                + " height fallback"
                + (reason.isEmpty() ? "" : "; " + reason)
                + (watered == 0 ? "" : "; static diagnostic water on " + watered)
                + (buildingTiles == 0 ? "" : objectsDetail(objects, buildingTiles))
                + (buildingFailures == 0 ? "" : "; building objects failed on " + buildingFailures)
                + creatureDetail(creatures, creatureTiles)
                + (exterior == null ? "" : "; loading buildings")
                + (water != null && waterTables != null && waterTables.isDone() && !waterReady()
                        ? "; water tables unavailable"
                        : "")
                + (unavailable.isEmpty()
                        ? ""
                        : "; "
                                + unavailable.size()
                                + " unavailable ("
                                + unavailable.values().iterator().next().site()
                                + ")");
    }

    private void pollFrame() {
        if (commit != null) return;
        synchronized (lock) {
            if (borrowed != null || queued != null || currentReceipt == null) return;
        }
        long taking = WorldStageTimings.start();
        var frame = session.takeLatestFrame();
        if (frame.isPresent()) {
            WorldStageTimings.stop("parent.frame.takeLatest", taking);
            var candidate=frame.orElseThrow();
            if(candidate.content()!=currentReceipt) return;
            var latest=camera;
            boolean pendingWanted, hasPresented;
            synchronized(lock) {
                hasPresented=acceptedSequence>0;
                pendingWanted=desired.stream().anyMatch(tile->!unavailable.containsKey(key(tile))
                        && current.stream().noneMatch(entry->same(tile,entry.resident())));
            }
            if(hasPresented && latest!=null && latest.view().pitch()<0 && pendingWanted
                    && WorldStreamBackground.matchesBasicSky(candidate.pixels(),candidate.width(),
                            candidate.height(),()->closing)) {
                skySuppressed=true;
                synchronized(lock) {
                    if(!closing && borrowed==null && queued==null && entries.size()>=12
                            && !presented.isEmpty() && candidate.content()==currentReceipt) {
                        // Detached old GUI pixels remain a historical raster, not a live Source borrow.
                        // No new receipt/sequence is acknowledged and no new pixels are displayed.
                        presented=List.of();
                        historicalRaster=true;
                    }
                }
                publish(Phase.PREPARING,"Loading terrain in viewing direction");
                return;
            }
            skySuppressed=false;
            synchronized (lock) {
                if (!closing)
                    queued = new Presentation(frame.orElseThrow(), request.viewGeneration());
            }
            changed();
        }
    }

    private void seed(WorldStreamSourceBridge.Prepared prepared) {
        synchronized (lock) {
            if (snapshot.seed() != null) return;
        }
        var bounds = prepared.facts().bounds();
        var point = request.areaPoint();
        double x = point == null ? (bounds.minX() + bounds.maxX()) / 2 : point.x();
        double y = point == null ? (bounds.minY() + bounds.maxY()) / 2 : point.y();
        double[] height = {bounds.maxZ()}, nearest = {Double.POSITIVE_INFINITY};
        prepared.withPayload(
                borrow -> {
                    var vertices = borrow.geometry().vertices();
                    while (vertices.hasRemaining()) {
                        double vx = vertices.get(), vy = vertices.get(), z = vertices.get();
                        double distance = Math.hypot(vx - x, vy - y);
                        if (distance < nearest[0]) {
                            nearest[0] = distance;
                            height[0] = z;
                        }
                    }
                });
        var view =
                new TerrainCamera(
                        x, y, (point == null ? height[0] : point.z()) + 100, Math.PI, -.8);
        synchronized (lock) {
            snapshot =
                    new Snapshot(
                            snapshot.phase(),
                            snapshot.detail(),
                            snapshot.prepared(),
                            snapshot.desired(),
                            new Seed(view, bounds.maxX(), bounds.maxY(), bounds),
                            snapshot.area());
        }
        changed();
    }

    private void updateGround() {
        var live = java.util.Collections.newSetFromMap(new IdentityHashMap<Entry, Boolean>());
        live.addAll(current);
        heights.keySet().retainAll(live);
        areaMetadata.keySet().retainAll(live);
        var tiles = new ArrayList<TerrainGround.Tile>();
        var metadata = new ArrayList<WorldFormats.AreaMetadata>();
        for (var entry : current) {
            var known = heights.get(entry);
            var recorded = areaMetadata.get(entry);
            if (known == null || recorded == null) {
                // Current residents are never retired here; borrow failures surface like updateArea.
                // One borrow reads both facts so neither depends on a second payload access.
                TerrainGround.Tile[] built = {null};
                WorldFormats.AreaMetadata[] read = {null};
                entry.source().withPayload(borrow -> {
                    built[0] = TerrainGround.Tile.of(borrow.geometry());
                    read[0] = borrow.areas();
                });
                known = Optional.ofNullable(built[0]);
                recorded = Optional.ofNullable(read[0]);
                heights.put(entry, known);
                areaMetadata.put(entry, recorded);
            }
            known.ifPresent(tiles::add);
            recorded.ifPresent(metadata::add);
        }
        ground = TerrainGround.of(tiles);
        areas = List.copyOf(metadata);
        updateObjects();
    }

    /** Publishes the pick tiles of the current residents whose late objects are drawn. */
    private void updateObjects() {
        var drawn = new ArrayList<WorldObjectPicks.Tile>();
        for (var entry : current) {
            var picks = entry.source().picks();
            if (picks != null
                    && entry.resident().exteriorObjects() == WorldStreamTransport.ExteriorObjects.DRAWN)
                drawn.add(picks);
        }
        objects = List.copyOf(drawn);
    }

    private void updateArea() {
        updateGround();
        var latest = camera;
        if (latest == null) return;
        long[] selected = {0};
        int[] count = {0};
        for (var entry : current)
            entry.source()
                    .withPayload(
                            borrow -> {
                                for (var chunk : borrow.areas().chunks()) {
                                    float minX =
                                            (float) (chunk.originX() - WorldFormats.TILE_SIZE / 16);
                                    float minY =
                                            (float) (chunk.originY() - WorldFormats.TILE_SIZE / 16);
                                    if (latest.view().x() > minX
                                            && latest.view().x() <= chunk.originX()
                                            && latest.view().y() > minY
                                            && latest.view().y() <= chunk.originY()) {
                                        selected[0] = chunk.areaId();
                                        count[0]++;
                                    }
                                }
                            });
        var result =
                count[0] == 1
                        ? WorldCurrentArea.resolve(
                                request.map().id(), selected[0], request.areaIndex())
                        : new WorldCurrentArea.Result(
                                count[0] > 1
                                        ? WorldCurrentArea.State.AMBIGUOUS
                                        : WorldCurrentArea.State.UNAVAILABLE,
                                count[0] > 1
                                        ? WorldCurrentArea.Reason.OVERLAP
                                        : WorldCurrentArea.Reason.NO_COVERAGE,
                                0,
                                List.of());
        synchronized (lock) {
            snapshot =
                    new Snapshot(
                            snapshot.phase(),
                            snapshot.detail(),
                            snapshot.prepared(),
                            snapshot.desired(),
                            snapshot.seed(),
                            result);
        }
    }

    private void retireUnused() {
        var remove = new ArrayList<Entry>();
        synchronized (lock) {
            for (var entry : entries.values()) {
                if (!current.contains(entry)
                        && !presented.contains(entry)
                        && !committing.contains(entry)
                        && entry != staged) remove.add(entry);
            }
        }
        for (var entry : remove) {
            entry.source().close();
            sourceRetirementRevision++;
            entries.remove(entry.resident());
            exteriorTried.remove(entry);
        }
    }

    private List<WorldFormats.Tile> demand() {
        synchronized (lock) {
            return desired;
        }
    }

    private void publish(Phase phase, String detail) {
        synchronized (lock) {
            if(historicalRaster && phase!=Phase.FAILED && phase!=Phase.CLOSED
                    && phase!=Phase.UNAVAILABLE) detail="Loading terrain; showing previous view";
            else if(historicalRaster && phase==Phase.UNAVAILABLE) detail+="; previous view retained";
            int available=0;
            for(var tile:desired) {
                for(var entry:current) {
                    if(same(tile,entry.resident())) { available++; break; }
                }
            }
            snapshot =
                    new Snapshot(
                            phase,
                            detail.length()>256?detail.substring(0,256):detail,
                            available,
                            desired.size(),
                            snapshot.seed(),
                            snapshot.area());
        }
        changed();
    }

    private void changed() {
        if (fxQueued.compareAndSet(false, true)) {
            WeakReference<Events> sink;
            synchronized (lock) {
                sink = new WeakReference<>(events);
            }
            var flag = fxQueued;
            var controller = new WeakReference<>(this);
            try {
                fxPost.accept(
                        () -> {
                            flag.set(false);
                            var target = sink.get();
                            if (target != null) {
                                try {
                                    target.changed();
                                } catch (RuntimeException | Error failure) {
                                    var owner = controller.get();
                                    if (owner != null) owner.failLater(failure);
                                }
                            }
                        });
            } catch (RuntimeException | Error failure) {
                flag.set(false);
                if (!closing) failLater(failure);
            }
        }
    }

    private void retire() {
        // Start real cancellation/physical shutdown BEFORE any potentially blocking payload close.
        if (pending != null && pending.cancellation == null) {
            pending.cancellation = pending.reservation.cancel();
            pending.useful = false;
        }
        if (exterior != null) exterior.cancellation.cancel();
        if (physicalClosing == null) {
            try {
                physicalClosing =
                        gpu == null ? CompletableFuture.completedFuture(null) : gpu.closeAsync();
            } catch (RuntimeException | Error failure) {
                physicalClosing = CompletableFuture.failedFuture(failure);
            }
            physicalClosing.whenComplete((ignored, failure) -> kick());
        }
        var physical = physicalClosing;
        if (pending != null) pending.producer.close();
        if (!physical.isDone()) return;
        // The canceled object preparation still holds the source lane until it drains.
        if (exterior != null && exterior.prepared != null && !exterior.prepared.isDone()) return;
        Throwable failure = terminal;
        boolean retirementProblem = false;
        try {
            physical.join();
        } catch (RuntimeException problem) {
            retirementProblem = true;
            if (failure == null) failure = problem;
            else failure.addSuppressed(problem);
        }
        synchronized (lock) {
            if (borrowed != null) return;
            queued = null;
            current = List.of();
            objects = List.of();
            presented = List.of();
            committing = List.of();
        }
        for (var entry : new ArrayList<>(entries.values())) {
            try {
                entry.source().close();
            } catch (RuntimeException | Error problem) {
                retirementProblem = true;
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        entries.clear();
        exteriorTried.clear();
        exterior = null;
        if (exteriorWorker != null) exteriorWorker.shutdown();
        source = null;
        water = null;
        waterTables = null;
        scope = null;
        opening = null;
        session = null;
        gpu = null;
        if (pending != null) {
            pending.producer.close();
            pending = null;
        }
        // The original terminal failure was logged when it occurred; log again only for new
        // retirement problems so a hung physical close cannot hide the first cause.
        if (failure != null && retirementProblem)
            WorldDiagnostics.warn("World viewer retired after failure", failure);
        publish(
                failure != null
                        ? Phase.FAILED
                        : unavailableDetail != null ? Phase.UNAVAILABLE : Phase.CLOSED,
                failure != null
                        ? "World viewer failed: " + WorldDiagnostics.brief(failure)
                        : unavailableDetail != null ? unavailableDetail : "World viewer closed");
        synchronized (lock) {
            events = null;
        }
        worker.shutdown();
        if (failure == null) drained.complete(null);
        else drained.completeExceptionally(failure);
    }

    private static int key(WorldFormats.Tile tile) {
        return tile.x() * 64 + tile.y();
    }

    private static boolean tileUnavailable(WorldStreamSourceBridge.RefusalMetadata origin) {
        if (origin.reason() != WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE)
            return false;
        return switch (origin.site()) {
            case "SOURCE_INPUT", "NUMERIC_ROOT", "TERRAIN_PROFILE" -> true;
            default -> false;
        };
    }

    private static boolean containsResident(
            List<WorldFormats.Tile> tiles, WorldStreamTransport.Resident resident) {
        return tiles.stream().anyMatch(value -> same(value, resident));
    }

    private static boolean same(WorldFormats.Tile a, WorldFormats.Tile b) {
        return a.x() == b.x() && a.y() == b.y();
    }

    private static boolean same(WorldFormats.Tile a, WorldStreamTransport.Resident b) {
        return a.x() == b.tileX() && a.y() == b.tileY();
    }

    private static boolean contains(List<WorldFormats.Tile> tiles, WorldFormats.Tile tile) {
        return tiles.stream().anyMatch(value -> same(value, tile));
    }
}
