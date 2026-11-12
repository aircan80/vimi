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

    private volatile java.util.function.LongConsumer presentedListener;
    private final java.util.concurrent.atomic.AtomicLong latestNative = new java.util.concurrent.atomic.AtomicLong();
    // #381: the content the helper last reported presenting natively; -1 before any (lock).
    private long nativeContent = -1;
    private final java.util.concurrent.atomic.AtomicBoolean nativePosted = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * #381: a frame the helper presented into its child window for {@code camera} over content
     * {@code content}. When that is the current committed content, it is accepted exactly like a
     * presented readback frame (the current table becomes the presented one), so the phase
     * reaches READY and capacity waiting on accepted pixels is relieved. The FX listener is told
     * the latest camera, coalesced to one posted task at a time.
     */
    void presentedNatively(long camera, long content) {
        boolean accepted;
        synchronized (lock) {
            // Kept: the helper presents one interval after COMMITTED and may report it before the
            // worker has recorded the receipt; it never repeats it for a still camera.
            nativeContent = content;
            accepted = acceptNativeLocked();
        }
        if (accepted) kick();
        latestNative.set(camera);
        if (presentedListener != null && nativePosted.compareAndSet(false, true)) {
            fxPost.accept(() -> {
                nativePosted.set(false);
                var listener = presentedListener;
                if (listener != null) listener.accept(latestNative.get());
            });
        }
    }

    /** The current committed content revision, or -1 before the first commit; for tests. */
    long currentContentRevision() {
        synchronized (lock) {
            return currentReceipt == null ? -1 : currentReceipt.contentRevision();
        }
    }

    /**
     * #381, native presentation: {@code listener} runs on the FX thread with the camera revision
     * of each frame the helper presented into its window.
     */
    void onPresented(java.util.function.LongConsumer listener) {
        presentedListener = listener;
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
        // Only the basic sky, drawn while terrain in the viewing direction is still loading.
        private final boolean skyOnly;

        private Presentation(WorldStreamTransport.Frame frame, long generation, boolean skyOnly) {
            this.frame = frame;
            this.generation = generation;
            this.skyOnly = skyOnly;
        }

        boolean skyOnly() {
            return skyOnly;
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

        /** Valid until {@link #discard}; the pixel array is then reused by a later frame. */
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
        /**
         * Presentation, demand and source-retirement revisions sampled under the lock when this
         * reservation was issued. A refusal waits for a change since issue, not since processing,
         * so an FX bump that lands in between still wakes the retry (#305).
         */
        long issuedPresentation, issuedDemand, issuedSourceRetirement, issuedGeometry;
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
        // Objects release revision when this offer was issued.
        long releaseRevision;
        // The later creatures pass (#175): buildings again with the tile's creatures, replacing the
        // drawn buildings-only set (or attached as the first set when the tile had no buildings).
        final boolean creaturesPass;
        // A creature pass: the buildings its tile's buildings pass kept, reused instead of being
        // prepared again, or null. A buildings pass: what it kept (set on the exterior worker).
        WorldStreamSourceBridge.KeptBuildings reuse;
        volatile WorldStreamSourceBridge.KeptBuildings kept;

        Exterior(Entry entry) {
            this(entry, false);
        }

        Exterior(Entry entry, boolean creaturesPass) {
            this.entry = entry;
            this.creaturesPass = creaturesPass;
        }
    }

    private final Object lock = new Object();
    private final Request request;
    private final GpuStarter starter;
    private final WorldStreamSourceBridge.ExteriorPreparer exteriorPreparer;
    // Worker-owned: at most one late exterior delivery, and the residents already tried.
    private ExecutorService exteriorWorker;
    // Volatile only so measurement probes can tell whether objects are still loading.
    private volatile Exterior exterior;
    // Worker-owned: residents refused for the objects GPU budget, with the session's objects
    // release revision at the refusal. They are offered again only once a drawn objects set was
    // released (its resident replaced), never just because the camera turned or moved.
    private final IdentityHashMap<Entry, Long> exteriorBudget = new IdentityHashMap<>();
    // Residents whose objects set with creatures was refused for budget: offered again at once
    // without creatures, so creatures never cost a tile its buildings.
    private final java.util.Set<Entry> creaturesShed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final java.util.Set<Entry> exteriorTried =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    // With a preparer that appends creatures, a tile's buildings go first, without them, so they
    // never wait for creature conversion (#175); its creatures follow in a second pass once every
    // wanted tile's buildings are in. Residents whose buildings pass ended and whose creatures pass
    // has not, and those whose creature set was refused for budget (offered again, like
    // exteriorBudget, once a drawn set was released).
    private final boolean creaturesLater;
    private final java.util.Set<Entry> creaturesPending =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Entry, Long> creatureBudget = new IdentityHashMap<>();
    // Creature passes canceled for buildings since one last ran to its end (worker-owned).
    private int creaturePreemptions;
    // Buildings prepared in a tile's buildings pass and kept for its creature pass (#175), by
    // resident, and the bytes they hold: at most KEPT_BUILDINGS_BYTES, past which a tile's
    // creature pass prepares its buildings again. Dropped once the creature pass ends, the tile
    // retires or the session closes. Worker-owned.
    private final IdentityHashMap<Entry, WorldStreamSourceBridge.KeptBuildings> keptBuildings =
            new IdentityHashMap<>();
    private long keptBuildingsBytes;
    static final long KEPT_BUILDINGS_BYTES = 64L << 20;
    static final int CREATURE_PREEMPTIONS = 3;
    private Events events;
    private final Consumer<Runnable> fxPost;
    private final LongSupplier clock;
    private static final long RETRY_NANOS = 500_000_000L;
    // Most residents one commit names: the 5 x 5 neighborhood in production.
    private final int residents;
    // Source entries: every resident, the staged tile, one pending tile and one retiring.
    private final int maxEntries;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-source-controller").factory());
    private final AtomicBoolean scheduled = new AtomicBoolean(), wake = new AtomicBoolean();
    private final AtomicBoolean frameWake = new AtomicBoolean();
    // The earliest timed wait's due time on the step clock, valid only while armed: an FX tick
    // wakes the worker once it is due. Compared by subtraction, so the clock may wrap.
    private volatile long pulseAt;
    private volatile boolean pulseArmed;
    // Diagnostics and tests: steps run and ground/area recomputations (#366).
    final java.util.concurrent.atomic.AtomicLong steps = new java.util.concurrent.atomic.AtomicLong(),
            skyWakeAtStep = new java.util.concurrent.atomic.AtomicLong(-1),
            areaScans = new java.util.concurrent.atomic.AtomicLong();
    // Worker-only: the camera and table the ground and area were last computed for.
    private WorldStreamTransport.Camera areaCamera;
    private List<Entry> areaTable;
    private final AtomicBoolean fxQueued = new AtomicBoolean();
    private final Cancellation qualificationCancellation = new Cancellation();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private volatile boolean closing;
    private volatile WorldStreamTransport.Camera camera;
    // The recorded sky (#177-2a), behind -Dvimi.world.sky=recorded; null when off.
    private volatile WorldSkyFeed sky;
    private volatile WorldStreamTransport.Session session;
    // Whether this session's WDL horizon was asked for (#372); once per session.
    private boolean horizonStarted;
    // The body the view wants drawn (a key, its display, and its file); each key is sent once,
    // one at a time, and a different key replaces the drawn body (walking murloc, flying gryphon).
    private record BodyWanted(
            long key, java.util.function.Supplier<java.util.concurrent.CompletableFuture<byte[]>> source) {}
    private volatile BodyWanted bodyWanted;
    // The key last sent (reset after a failed send so it is retried), whether a send is
    // unanswered, the drawn key, and when a failed body may be sent again.
    private volatile long bodySent = -1;
    private volatile long bodyRetryAt;
    private final java.util.concurrent.atomic.AtomicInteger bodyFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean bodyInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long bodyDrawn = -1;
    private List<WorldFormats.Tile> desired;
    // Tiles one ring beyond the demand (#366): a pending tile there is kept, not canceled.
    private List<WorldFormats.Tile> keep = List.of();
    private long demandRevision, presentationRevision, acceptedSequence;
    private Snapshot snapshot =
            new Snapshot(Phase.QUALIFYING, "Qualifying world sources", 0, 1, null, null);
    private Presentation queued, borrowed;
    // Opt-in measurement only: when the current receipt was applied, 0 while timings are off.
    private long committedAt;
    // Opt-in hand-off stamps (worker-owned); 0 while timings are off.
    private volatile long resultAt;
    private long resultSeenAt, commitDoneAt;
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
    // The swimming surface: the residents' terrain liquid with the liquid of the buildings read for
    // walking collision on top; republished whenever either changes.
    private volatile com.vibeminer.rendering.world.LiquidSurface liquids =
            com.vibeminer.rendering.world.LiquidSurface.empty();
    private com.vibeminer.rendering.world.LiquidSurface terrainLiquids =
            com.vibeminer.rendering.world.LiquidSurface.empty();
    private List<com.vibeminer.rendering.world.LiquidSurface.Placed> buildingLiquids = List.of();
    private final java.util.Map<Integer, List<com.vibeminer.rendering.world.LiquidSurface.Placed>> collisionLiquids =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Walking collision of buildings and doodads: the parts of the 3x3 tiles around the camera,
    // read and prepared on their own worker (never the source lane), merged there and published
    // as one set. Parts farther than one tile are dropped, so at most nine are held.
    private ExecutorService collisionWorker;
    private final Cancellation collisionCancellation = new Cancellation();
    private final java.util.Map<Integer, com.vibeminer.rendering.world.ObjectCollision.Prepared> collisionParts =
            new java.util.concurrent.ConcurrentHashMap<>();
    // When a tile whose read failed, or found a source unreadable, may be read again (nanoTime).
    private final java.util.Map<Integer, Long> collisionRetryAt = new java.util.concurrent.ConcurrentHashMap<>();
    // Failed or partial reads of a tile in a row, which double its retry delay.
    private final java.util.Map<Integer, Integer> collisionAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> collisionPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile java.util.Set<Integer> collisionMerged = java.util.Set.of();
    private volatile java.util.Set<Integer> collisionRecorded;
    private volatile com.vibeminer.rendering.world.ObjectCollision objectCollision =
            com.vibeminer.rendering.world.ObjectCollision.empty();
    // Worker-published pick geometry of the current residents whose late objects are drawn.
    private volatile List<WorldObjectPicks.Tile> objects = List.of();
    // Worker-owned immutable area metadata of current residents, for pure point lookups.
    private final IdentityHashMap<Entry, Optional<WorldFormats.AreaMetadata>> areaMetadata =
            new IdentityHashMap<>();
    private volatile List<WorldFormats.AreaMetadata> areas = List.of();
    private CompletableFuture<WorldStreamTransport.CommitReceipt> commit;
    private WorldStreamTransport.CommitReceipt currentReceipt, acceptedReceipt;
    // The table and receipt the borrowed frame was drawn from (#366): a commit may replace
    // current while FX holds the frame, and its handshake is judged against what it shows.
    private List<Entry> borrowedTable = List.of();
    private WorldStreamTransport.CommitReceipt borrowedReceipt;
    private Entry staged;
    // The staged entry's reservation, kept so an unwanted staged tile can be canceled instead of
    // committed over a wanted resident (#366), and that cancellation while it drains.
    private WorldStreamTransport.TileReservation stagedReservation;
    private CompletableFuture<Void> stagedCancellation;
    private final LinkedHashMap<Integer, WorldStreamSourceBridge.RefusalMetadata> unavailable =
            new LinkedHashMap<>();
    private boolean initialBlocked;
    private long initialRetryAt, blockedAt;
    // Tiles the helper rejected (issue #365): rejection count and the earliest retry time.
    private final java.util.Map<Integer, Integer> helperRejections =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, Long> helperRetryAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** After this many helper rejections a tile is unavailable for as long as it stays wanted. */
    static final int MAX_HELPER_REJECTIONS = 3;
    private boolean blockedOnMaterialPool, blockedOnGeometry;
    private long blockedGeometry;
    /** Wakes the controller when the source's geometry lane releases (#304). */
    private AutoCloseable geometryWake;
    private long sourceRetirementRevision, blockedSourceRetirement;
    private long blockedPresentation = -1, blockedDemand = -1;
    /** Whether capacity moved for a reason other than the refused reservation (#283, #366). */
    private java.util.function.BooleanSupplier blockedCapacityMoved = () -> true;
    /** Presentation and demand revisions as of this step's own reads (worker thread only). */
    private long stepPresentation, stepDemand;
    private volatile Throwable terminal;
    private volatile String unavailableDetail;
    private CompletableFuture<Void> physicalClosing;
    private boolean initialized;
    // The newest frame drew only sky while wanted terrain is still loading (whether or not that
    // frame is shown): capacity relief then still needs a useful frame.
    private boolean skyPending;
    private static final String LOADING_IN_VIEW = "Loading terrain in viewing direction";
    private static final String SHOWING_PREVIOUS = "Loading terrain; showing previous view";
    private static final String LOADING_NEARBY = "Loading nearby terrain";
    private boolean historicalRaster;
    private WorldFormats.Tile creditTarget;
    private boolean retryCommit;
    private long commitRetryAt, commitRetryCapacity, commitIssuedCapacity;
    // Test seam: runs on the worker after a RETRY_LATER commit refusal, before it is recorded.
    volatile Runnable refusalSeam;
    // Test seam: runs on the worker after a RETRY_LATER tile-preparation refusal, before it is
    // recorded.
    volatile Runnable tileRefusalSeam;
    // Helper rejections that arrived for tiles already out of demand (not counted, #365).
    private final java.util.concurrent.atomic.AtomicInteger rejectionsOutOfDemand =
            new java.util.concurrent.atomic.AtomicInteger();
    // Test seam: runs on the worker after this step read its demand and got a tile reservation,
    // just before the reservation records its revisions and starts.
    volatile Runnable tileIssueSeam;

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
        this(request, starter, events, fxPost, clock,
                (source, scope, tile, cancellation, formats, creatureBytes) -> WorldStreamSourceBridge.prepareExterior(
                        source, scope, tile, cancellation, null,
                        com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD, false, formats));
    }

    /** Tests replace the exterior source operation. */
    WorldStreamSourceController(
            Request request,
            GpuStarter starter,
            Events events,
            Consumer<Runnable> fxPost,
            LongSupplier clock,
            WorldStreamSourceBridge.ExteriorPreparer exteriorPreparer) {
        this(request, starter, events, fxPost, clock, exteriorPreparer,
                WorldStreamGpuOwner.VIEW_DISTANCE.residents());
    }

    /** Tests may also bound the neighborhood below the protocol's resident limit. */
    WorldStreamSourceController(
            Request request,
            GpuStarter starter,
            Events events,
            Consumer<Runnable> fxPost,
            LongSupplier clock,
            WorldStreamSourceBridge.ExteriorPreparer exteriorPreparer,
            int residents) {
        if (residents < 1 || residents > WorldStreamTransport.MAX_RESIDENTS)
            throw new IllegalArgumentException("Resident bound");
        this.residents = residents;
        maxEntries = residents + 3;
        this.exteriorPreparer = Objects.requireNonNull(exteriorPreparer);
        this.creaturesLater = exteriorPreparer.appendsCreatures();
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
        // The sky lookup runs outside the lock, and a failing one only leaves the camera's sky
        // unchanged: camera() must not fail every frame because of it.
        var feed=sky;
        if(feed!=null) {
            try { next=feed.attach(next); }
            catch(RuntimeException failure) {
                WorldDiagnostics.degraded("sky", "World sky lookup failed; the explorer's sky stays", failure);
            }
        }
        RuntimeException problem=null;
        synchronized (lock) {
            if (closing) return;
            if (camera != null && next.revision() <= camera.revision()) {
                throw new IllegalArgumentException("Camera revision must advance");
            }
            // Actual final Session.camera only replaces metadata; no IO/external callback.
            // Use the same gate as initial publication so an older initial camera cannot follow.
            // A camera the session refuses as an invalid transition is not kept (#365).
            boolean accepted=true;
            if(session!=null) {
                try { accepted=session.camera(next); }
                catch(RuntimeException failure) { problem=failure; }
            }
            if(accepted) camera=next;
        }
        if(problem!=null) failLater(problem);
        kick();
    }

    void neighborhood(List<WorldFormats.Tile> ordered) {
        neighborhood(ordered, List.of());
    }

    /**
     * The demand in load order, plus the tiles one ring beyond it whose preparation, once
     * started, is kept rather than canceled (#366).
     */
    void neighborhood(List<WorldFormats.Tile> ordered, List<WorldFormats.Tile> beyond) {
        Objects.requireNonNull(ordered);
        var kept = List.copyOf(beyond);
        if (ordered.size() > residents)
            throw new IllegalArgumentException("Neighborhood bounds");
        var copy = List.copyOf(ordered);
        for (int i = 0; i < copy.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (same(copy.get(i), copy.get(j)))
                    throw new IllegalArgumentException("Duplicate neighborhood tile");
            }
        }
        synchronized (lock) {
            if (closing) return;
            // Published only once the demand is validated (#366 review). A changed keep set alone
            // still kicks: a pending tile it no longer covers must be canceled.
            boolean keepChanged = !keep.equals(kept);
            keep = kept;
            if (desired.equals(copy)) {
                if (!keepChanged) return;
            } else {
                desired = copy;
                demandRevision++;
            }
        }
        kick();
    }

    /**
     * Once per session: reads the map's WDL and hands it to the World child, which draws the
     * low-resolution horizon (#372). Any failure only means no horizon.
     */
    private void startHorizon() {
        if (horizonStarted || closing) return;
        horizonStarted = true;
        var opened = session;
        request.world().horizon(request.map(), qualificationCancellation)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        WorldDiagnostics.degradedUnlessClean("horizon",
                                "World horizon unavailable; terrain continues without it", failure);
                        return;
                    }
                    byte[] wdl = result.sourceBytes();
                    if (result.state() != com.vibeminer.concepts.world.WorldHorizon.State.READY
                            || wdl == null || closing) return;
                    opened.attachHorizon(wdl).whenComplete((outcome, sendFailure) -> {
                        if (sendFailure != null)
                            WorldDiagnostics.degradedUnlessClean("horizon",
                                    "World horizon was not delivered", sendFailure);
                    });
                });
    }

    /**
     * The body to draw ({@link com.vibeminer.rendering.bgfx.WorldStreamBodyFile}) under {@code key}
     * (its creature display): asked for and sent once the session draws, replacing the drawn body
     * when the key differs from the last one sent. The same key again is not sent again; any
     * failure only means no body (see {@link #bodyDrawn}).
     */
    void body(long key, java.util.function.Supplier<java.util.concurrent.CompletableFuture<byte[]>> source) {
        bodyWanted = new BodyWanted(key, Objects.requireNonNull(source));
        kick();
    }

    /**
     * For tests: when a refused body may be sent again, on the controller's clock; 0 while a body
     * send is in flight or when no retry waits.
     */
    long bodyRetryAt() {
        return bodyInFlight.get() ? 0 : bodyRetryAt;
    }

    /** The key of the body the World child draws now, or -1 before any (or after a failed swap). */
    long bodyDrawn() {
        return bodyDrawn;
    }

    /**
     * Delay before a body the child refused, or that was not delivered, is sent again: 2 s,
     * doubling with each such failure in a row, at most a minute.
     */
    static final long BODY_RETRY_NANOS = 2_000_000_000L, BODY_RETRY_MAX_NANOS = 60_000_000_000L;

    static long bodyRetryDelay(int failures) {
        return Math.min(BODY_RETRY_MAX_NANOS, BODY_RETRY_NANOS << Math.min(5, Math.max(0, failures - 1)));
    }

    /** Hands the wanted body to the World child when it is not the one last sent; one at a time. */
    private void startBody() {
        var wanted = bodyWanted;
        if (closing || wanted == null || wanted.key() == bodySent) return;
        long retryAt = bodyRetryAt;
        if (retryAt != 0 && clock.getAsLong() - retryAt < 0) {
            wakeAt(retryAt);
            return;
        }
        if (!bodyInFlight.compareAndSet(false, true)) return;
        bodySent = wanted.key();
        // From now until its BODY_DONE the child may still draw the previous body: draw none.
        bodyDrawn = -1;
        var opened = session;
        wanted.source().get().whenComplete((file, failure) -> {
            if (failure != null || closing) {
                if (failure != null)
                    WorldDiagnostics.degradedUnlessClean("body", "World body unavailable", failure);
                bodyDrawn = -1;
                bodyInFlight.set(false);
                kick();
                return;
            }
            opened.attachBody(file).whenComplete((outcome, sendFailure) -> {
                if (sendFailure != null)
                    WorldDiagnostics.degradedUnlessClean("body", "World body was not delivered", sendFailure);
                else if (outcome != com.vibeminer.rendering.bgfx.WorldStreamTransport.BodyOutcome.DRAWN)
                    WorldDiagnostics.degraded("body", "The World child could not draw the body", null);
                // A failed swap leaves the child's previous body, which is not the one wanted.
                boolean drawn = sendFailure == null
                        && outcome == com.vibeminer.rendering.bgfx.WorldStreamTransport.BodyOutcome.DRAWN;
                bodyDrawn = drawn ? wanted.key() : -1;
                if (drawn) {
                    bodyFailures.set(0);
                    bodyRetryAt = 0;
                } else if (!closing) {
                    // A refusal may be transient (the objects' budget): send it again later.
                    bodyRetryAt = clock.getAsLong() + bodyRetryDelay(bodyFailures.incrementAndGet());
                    bodySent = -1;
                }
                bodyInFlight.set(false);
                kick();
            });
        });
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

    /** The resident bound, never above what the open session's view distance admits. */
    private int residentLimit() {
        var open = session;
        return open == null ? residents : Math.min(residents, open.viewDistance().residents());
    }

    /** True when the recorded sky has moved on with the clock and the camera should be resent. */
    boolean skyStale() {
        return skyStale(sky);
    }

    /**
     * Like {@link #camera}, a failing sky lookup is logged and changes nothing: it must not break
     * the World view's pulse. The feed looks at each half-minute once, so it is not retried on
     * every pulse.
     */
    static boolean skyStale(WorldSkyFeed feed) {
        if (feed == null) return false;
        try {
            return feed.stale();
        } catch (RuntimeException failure) {
            WorldDiagnostics.degraded("sky", "World sky lookup failed; the explorer's sky stays", failure);
            return false;
        }
    }

    /**
     * The FX timer's tick. Steps are event-driven (#366): camera, demand, frames, presentation,
     * transport capacity and every async completion kick the worker themselves, so a tick only
     * wakes a step whose timed wait (a retry timeout) has come due.
     */
    void pulse() {
        if (pulseArmed && clock.getAsLong() - pulseAt >= 0) kick();
    }

    /** Worker-only: the step returns waiting until {@code deadline}; a tick then wakes it. */
    private void wakeAt(long deadline) {
        if (!pulseArmed || deadline - pulseAt < 0) pulseAt = deadline;
        pulseArmed = true;
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

    /** Terrain liquid surface of the current resident tiles, for a swimming body. */
    com.vibeminer.rendering.world.LiquidSurface liquids() {
        return closing ? com.vibeminer.rendering.world.LiquidSurface.empty() : liquids;
    }

    /** Buildings and doodads around the camera for a walking body; empty until read. */
    com.vibeminer.rendering.world.ObjectCollision objectCollision() {
        return closing ? com.vibeminer.rendering.world.ObjectCollision.empty() : objectCollision;
    }

    /**
     * Delay before a tile is read again after a failed or partly unreadable read: 15 s, doubling
     * with each such read in a row, at most four minutes.
     */
    static final long COLLISION_RETRY_NANOS = 15_000_000_000L, COLLISION_RETRY_MAX_NANOS = 240_000_000_000L;

    static long collisionRetryDelay(int attempts) {
        return Math.min(COLLISION_RETRY_MAX_NANOS, COLLISION_RETRY_NANOS << Math.min(10, Math.max(0, attempts - 1)));
    }

    /**
     * Whether the buildings and doodads of the tile under (x, y) are in {@link #objectCollision}.
     * A tile the map does not record (open sea, the map edge) has none to wait for.
     */
    boolean objectsReady(double x, double y) {
        int key = collisionKey(tileOf(x), tileOf(y));
        return !recordedTiles().contains(key) || collisionMerged.contains(key);
    }

    private java.util.Set<Integer> recordedTiles() {
        var known = collisionRecorded;
        if (known == null) {
            var keys = new java.util.HashSet<Integer>();
            for (var tile : request.inventory().world().tiles()) keys.add(collisionKey(tile.x(), tile.y()));
            known = collisionRecorded = java.util.Set.copyOf(keys);
        }
        return known;
    }

    static int tileOf(double native_) {
        return (int) Math.floor(32 - native_ / WorldFormats.TILE_SIZE);
    }

    static int collisionKey(int x, int y) {
        return x * 64 + y;
    }

    /**
     * Reads the collision of the nearest recorded tile within one tile of the camera that has
     * none yet (or whose retry time came), one at a time on the collision worker; parts farther
     * than one tile are dropped. Runs on the source worker with the camera it just used, and only
     * schedules work.
     */
    private void updateCollision(WorldStreamTransport.Camera latest) {
        var lane = source;
        if (latest == null || lane == null || closing) return;
        int cx = tileOf(latest.view().x()), cy = tileOf(latest.view().y());
        java.util.function.IntPredicate far = key -> Math.abs(key / 64 - cx) > 1 || Math.abs(key % 64 - cy) > 1;
        collisionRetryAt.keySet().removeIf(far::test);
        collisionAttempts.keySet().removeIf(far::test);
        boolean dropped = collisionParts.keySet().removeIf(far::test);
        collisionLiquids.keySet().removeIf(far::test);
        if (!collisionPending.isEmpty()) return;
        long now = System.nanoTime();
        WorldFormats.Tile next = null;
        int best = Integer.MAX_VALUE;
        for (var tile : request.inventory().world().tiles()) {
            int dx = Math.abs(tile.x() - cx), dy = Math.abs(tile.y() - cy);
            int key = collisionKey(tile.x(), tile.y());
            Long retry = collisionRetryAt.get(key);
            boolean due = retry == null ? !collisionParts.containsKey(key) : now - retry >= 0;
            if (dx > 1 || dy > 1 || !due) continue;
            if (dx + dy < best) {
                best = dx + dy;
                next = tile;
            }
        }
        if (next == null && !dropped) return;
        if (collisionWorker == null)
            collisionWorker = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-walk-collision").factory());
        if (next == null) {
            collisionWorker.execute(this::mergeCollision);
            return;
        }
        var tile = next;
        int key = collisionKey(tile.x(), tile.y());
        collisionPending.add(key);
        CompletableFuture.runAsync(() -> {
            try {
                var part = lane.prepareCollision(tile, collisionCancellation);
                collisionParts.put(key, com.vibeminer.rendering.world.ObjectCollision.prepare(part.placements()));
                collisionLiquids.put(key, part.liquids());
                if (part.complete()) {
                    collisionRetryAt.remove(key);
                    collisionAttempts.remove(key);
                } else {
                    collisionRetryAt.put(key, System.nanoTime() + collisionRetryDelay(collisionAttempts.merge(key, 1, Integer::sum)));
                }
            } catch (RuntimeException failure) {
                if (!collisionCancellation.isCanceled())
                    WorldDiagnostics.degraded("walking collision", "Walking collision could not be read for a tile", failure);
                collisionRetryAt.put(key, System.nanoTime() + collisionRetryDelay(collisionAttempts.merge(key, 1, Integer::sum)));
            } finally {
                mergeCollision();
                collisionPending.remove(key);
                kick();
            }
        }, collisionWorker);
    }

    /** Publishes the current parts as one collision set; runs on the collision worker only. */
    private void mergeCollision() {
        var parts = new ArrayList<com.vibeminer.rendering.world.ObjectCollision.Prepared>();
        var keys = new java.util.HashSet<Integer>();
        for (var entry : collisionParts.entrySet()) {
            parts.add(entry.getValue());
            keys.add(entry.getKey());
        }
        objectCollision = com.vibeminer.rendering.world.ObjectCollision.merge(parts);
        collisionMerged = java.util.Set.copyOf(keys);
        var placed = new ArrayList<com.vibeminer.rendering.world.LiquidSurface.Placed>();
        for (var key : keys) {
            var part = collisionLiquids.get(key);
            if (part == null) continue;
            for (var liquid : part) {
                if (placed.size() < com.vibeminer.rendering.world.LiquidSurface.MAX_PLACED) placed.add(liquid);
            }
        }
        publishLiquids(null, List.copyOf(placed));
    }

    /** Republishes the swimming surface with new terrain liquid or building liquid (null: kept). */
    private synchronized void publishLiquids(com.vibeminer.rendering.world.LiquidSurface terrain,
            List<com.vibeminer.rendering.world.LiquidSurface.Placed> buildings) {
        if (terrain != null) terrainLiquids = terrain;
        if (buildings != null) buildingLiquids = buildings;
        liquids = buildingLiquids.isEmpty() ? terrainLiquids : terrainLiquids.with(buildingLiquids);
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
            borrowedTable = current;
            borrowedReceipt = currentReceipt;
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
                    // The first accepted frame drawn from the committed receipt; a borrowed frame
                    // of an older table, accepted later, does not end the measurement.
                    if (borrowedReceipt == currentReceipt && acceptedReceipt != currentReceipt) {
                        WorldStageTimings.stop("parent.commit.toAcceptedFrame", committedAt);
                        committedAt = 0L;
                    }
                    historicalRaster=false;
                    acceptedSequence = value.sequence();
                    acceptedReceipt = borrowedReceipt;
                    // Sky-only pixels show no resident terrain, so they pin no resident; otherwise
                    // the frame pins exactly the table it was drawn from (#366).
                    presented = value.skyOnly ? List.of() : List.copyOf(borrowedTable);
                    presentationRevision++;
                }
                borrowedTable = List.of();
                borrowedReceipt = null;
            }
        }
        // Keep the label true to what is now on screen until the worker's next publication:
        // live sky is no longer a retained previous view, and terrain is no longer "loading in
        // the viewing direction".
        if (accepted) {
            boolean relabeled = false;
            synchronized (lock) {
                String detail = snapshot.detail();
                String next = value.skyOnly
                        ? detail.equals(SHOWING_PREVIOUS) ? LOADING_IN_VIEW : null
                        : detail.equals(LOADING_IN_VIEW) || detail.equals(SHOWING_PREVIOUS)
                                ? LOADING_NEARBY : null;
                if (next != null) {
                    snapshot = new Snapshot(snapshot.phase(), next, snapshot.prepared(),
                            snapshot.desired(), snapshot.seed(), snapshot.area());
                    relabeled = true;
                }
            }
            if (relabeled) changed();
        }
        kick();
        return accepted;
    }

    /** Ends the FX borrow; the frame's pixels are released for reuse and must not be read again. */
    void discard(Presentation value) {
        synchronized (lock) {
            if (borrowed == value) {
                borrowed = null;
                validated = false;
                borrowedTable = List.of();
                borrowedReceipt = null;
            }
        }
        drop(value.frame, "discard");
        kick();
    }

    /**
     * Under the lock: accepts the content the helper last reported presenting natively once it is
     * the current receipt and drawable as readback pixels must be (see {@link #drawableLocked}).
     * Rechecked when the report arrives, when a receipt is recorded and on every worker step.
     */
    private boolean acceptNativeLocked() {
        if (closing || nativeContent < 0 || currentReceipt == null
                || currentReceipt.contentRevision() != nativeContent) return false;
        if (acceptedReceipt == currentReceipt && !historicalRaster && presented.equals(current))
            return false;
        if (!drawableLocked(current)) return false;
        historicalRaster = false;
        acceptedSequence++;
        acceptedReceipt = currentReceipt;
        presented = List.copyOf(current);
        presentationRevision++;
        return true;
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
                || borrowedReceipt != value.frame.content()) return false;
        return drawableLocked(borrowedTable);
    }

    /**
     * Under the lock: {@code table} (the one a frame shows) draws terrain where the camera is,
     * from open sources. Readback judges the borrowed frame's table; native acceptance the current.
     */
    private boolean drawableLocked(List<Entry> table) {
        // A valid receipt can still draw only sky after motion beyond its resident neighborhood.
        // Keep the last readable pixels until the nearest requested recorded tile is GPU-current.
        // Judged on the table the frame shows, which a later commit does not change. A nearest
        // tile the helper keeps rejecting will not become current (#365): show the neighbours
        // instead of holding the last pixels forever.
        if(!desired.isEmpty() && table.stream().noneMatch(entry->same(desired.getFirst(),entry.resident()))
                && helperRejections.getOrDefault(key(desired.getFirst()),0) < MAX_HELPER_REJECTIONS) return false;
        for (var entry : table) if (!entry.source().isOpen()) return false;
        return true;
    }

    CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            if (closing) return drained.copy();
            closing = true;
            if (queued != null) drop(queued.frame, "close");
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
        schedule();
    }

    /** Transport frame-reader callback: polls only the new frame, never a full step. */
    private void frameArrived() {
        frameWake.set(true);
        schedule();
    }

    private void schedule() {
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
                boolean full = wake.getAndSet(false);
                boolean frame = frameWake.getAndSet(false);
                if (full) {
                    if (closing) retire();
                    else step();
                } else if (frame && !closing) pollArrivedFrame();
            } while ((wake.get() || frameWake.get()) && !drained.isDone());
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
            if ((wake.get() || frameWake.get()) && !drained.isDone()) schedule();
        }
    }

    /** Worker-only: the step() frame poll, gated on the same opened-and-initialized session. */
    private void pollArrivedFrame() {
        var opened = session;
        if (opened == null || !opened.initialized().isDone()
                || opened.initialized().isCompletedExceptionally()) return;
        pollFrame();
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
        boolean nativeAccepted;
        steps.incrementAndGet();
        pulseArmed = false; // no timed wait until this step returns on one
        synchronized (lock) {
            nativeAccepted = acceptNativeLocked();
            // Pixels this step decides from; a later FX acceptance must still wake a refusal.
            stepPresentation = presentationRevision;
        }
        if (nativeAccepted) kick();
        if (!initialized) {
            if (initialBlocked && clock.getAsLong() - initialRetryAt < RETRY_NANOS) {
                wakeAt(initialRetryAt + RETRY_NANOS);
                return;
            }
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
                // A GEOMETRY_CREDIT refusal waits for this release instead of re-polling. It kicks
                // on every release, not only while blocked: a filter on blockedOnGeometry would
                // drop a release landing between issue and the refusal being recorded, and kicks
                // coalesce in schedule(), so an unneeded one costs a gate check.
                geometryWake=source.onGeometryRelease(this::kick);
                if(WorldSkyFeed.enabled())
                    sky=new WorldSkyFeed(request.world().skyIndex(qualificationCancellation),
                            request.map().id(),WorldSkyFeed.clock(),System::nanoTime);
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
                // Re-run the guard now (#366 review): it arms its deadline; nothing else wakes it.
                wake.set(true);
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
            session.onFrame(this::frameArrived);
            // #381: native presentation reports presented frames instead of mapping frames.
            session.onPresented(this::presentedNatively);
            // Credits, retirements and releases wake a step waiting on capacity (#366).
            session.onCapacity(this::kick);
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
        startHorizon();
        startBody();
        pollFrame();
        updateArea();
        stepExterior();
        var wanted = demand();
        if(creditTarget!=null && !contains(wanted,creditTarget)) creditTarget=null;
        unavailable.keySet().removeIf(key -> wanted.stream().noneMatch(tile -> key(tile) == key));
        helperRejections.keySet().removeIf(key -> wanted.stream().noneMatch(tile -> key(tile) == key));
        helperRetryAt.keySet().removeIf(key -> wanted.stream().noneMatch(tile -> key(tile) == key));
        if (pending != null) {
            List<WorldFormats.Tile> kept;
            synchronized (lock) {
                kept = keep;
            }
            // Hysteresis (#366): a tile one ring beyond the demand keeps its preparation, unless
            // the camera tile or a wanted tile around it is still missing: the single lane then
            // goes to what the view needs now (#366 review).
            // A preparation that already ended in a helper rejection is terminal: nothing to cancel.
            if (!contains(wanted, pending.tile)
                    && (!contains(kept, pending.tile) || nearestMissing(wanted))
                    && pending.cancellation == null
                    && !rejectedByHelper(pending.result)) {
                // Parent's explicit stop MUST precede the bridge's latest=false cancellation.
                pending.cancellation = pending.reservation.cancel();
                pending.useful = false;
                pending.cancellation.whenComplete((ignored, failure) -> kick());
            }
            if (pending.cancellation != null) {
                if (!pending.cancellation.isDone()) return;
                try {
                    pending.cancellation.join();
                } catch (CompletionException failure) {
                    // The helper rejected the packet before the cancel reached it (#365): the
                    // reservation already ended; the tile left demand, so nothing is retried.
                    if (!(failure.getCause() instanceof WorldStreamTransport.CancellationRefusal refused)
                            || refused.reason() != WorldStreamTransport.CancellationReason.PACKET_REJECTED)
                        throw failure;
                }
                pending.producer.close();
                pending = null;
            } else {
                if (!pending.result.isDone()) return;
                try {
                    var resident = pending.result.join();
                    var prepared = pending.producer.takePrepared();
                    // A tile that stages again starts its rejection count afresh.
                    helperRejections.remove(key(pending.tile));
                    helperRetryAt.remove(key(pending.tile));
                    if(creditTarget!=null && same(creditTarget,pending.tile)) creditTarget=null;
                    staged = new Entry(resident, prepared);
                    stagedReservation = pending.reservation;
                    resultSeenAt = WorldStageTimings.lap(
                            "handoff.controller.resultToSeen", resultAt);
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
                    var refusedTile = pending.tile;
                    // Kept while the refused reservation is still at hand (#283).
                    var refusedReservation = pending.reservation;
                    // Revisions as of issue, read before the pending reservation is dropped (#305).
                    long issuedPresentation = pending.issuedPresentation, issuedDemand = pending.issuedDemand;
                    long issuedSourceRetirement = pending.issuedSourceRetirement;
                    long issuedGeometry = pending.issuedGeometry;
                    if(creditTarget!=null && same(creditTarget,pending.tile)
                            && refusal.reason()!=WorldStreamTransport.Refusal.RETRY_LATER) creditTarget=null;
                    pending.producer.close();
                    pending = null;
                    if (refusal.reason() == WorldStreamTransport.Refusal.PACKET_REJECTED) {
                        // A tile that already left demand is not counted: it must not publish
                        // UNAVAILABLE (and turn the view off) for terrain nobody asked for.
                        if (contains(wanted, refusedTile)) helperRejected(failedTile);
                        else {
                            rejectionsOutOfDemand.incrementAndGet();
                            wake.set(true);
                        }
                        return;
                    }
                    if (origin != null && !origin.reason().name().equals(refusal.reason().name()))
                        throw failure;
                    if (origin == null
                            && refusal.reason() != WorldStreamTransport.Refusal.SOURCE_INVALIDATED)
                        throw failure;
                    if (refusal.reason() == WorldStreamTransport.Refusal.CAPABILITY_UNAVAILABLE
                            && origin != null
                            && origin.reason().name().equals(refusal.reason().name())
                            && tileUnavailable(origin)) {
                        // As for a helper rejection: a tile kept one ring beyond, whose demand has
                        // already left, publishes no UNAVAILABLE for terrain nobody asked for.
                        if (!contains(wanted, refusedTile)) {
                            rejectionsOutOfDemand.incrementAndGet();
                            wake.set(true);
                            return;
                        }
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
                    blockedOnGeometry = origin != null && origin.site().equals("GEOMETRY_CREDIT");
                    blockedSourceRetirement = issuedSourceRetirement;
                    blockedGeometry = issuedGeometry;
                    var seam = tileRefusalSeam;
                    if (seam != null) seam.run();
                    blockedAt = clock.getAsLong();
                    // Capacity is judged against what the refused reservation itself accounts
                    // for, read live: a retirement or credit from anything else that landed after
                    // it was issued (even between the refusal and this step) still wakes the retry
                    // (#283). The reservation's own drain, retirement and unused-credit CREDIT are
                    // excluded, so a refusal never wakes itself (#366 review); with nothing else
                    // moving, only the RETRY_NANOS deadline retries it. A GEOMETRY_CREDIT refusal
                    // ignores capacity and instead waits on the source's geometry release
                    // revision, so a busy lane is not polled (#304).
                    // Likewise presentation and demand: both are bumped on the FX thread, so the
                    // values sampled at issue are compared, never the ones current now (#305).
                    blockedCapacityMoved = refusedReservation::capacityChangedElsewhere;
                    blockedPresentation = issuedPresentation;
                    blockedDemand = issuedDemand;
                    publish(Phase.WAITING_CAPACITY, "Waiting for streaming resource capacity");
                    // Re-run the guard now (#366 review): it proceeds if capacity, demand or the pool
                    // already moved in a coalesced kick, or arms its own deadline.
                    wake.set(true);
                    return;
                }
                pending.producer.close();
                pending = null;
            }
        }
        if(retryCommit) {
            if(session.capacityRevision()==commitRetryCapacity && clock.getAsLong()-commitRetryAt<RETRY_NANOS) {
                wakeAt(commitRetryAt + RETRY_NANOS);
                return;
            }
            commit=issueCommit(camera.policy(),committing.stream().map(Entry::resident)
                    .toArray(WorldStreamTransport.Resident[]::new));
            retryCommit=false;
            commit.whenComplete((ignored,failure)->kick());
            publish(Phase.COMMITTING,"Activating prepared terrain");
            return;
        }
        if (commit != null) {
            if (!commit.isDone()) return;
            // No wait for a frame the FX side still borrows (#366). The borrow keeps the table
            // and receipt it was drawn from: its handshake is judged against those, an accepted
            // frame records exactly the table it shows, and retirement keeps that table's
            // sources until the borrow ends.
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
                // Re-run the guard now (#366 review): it proceeds if the retirement already landed
                // in a coalesced kick, or arms its own deadline. Nothing else would wake it.
                wake.set(true);
                return;
            }
            commitDoneAt = WorldStageTimings.start();
            synchronized (lock) {
                current = committing;
                currentReceipt = receipt;
                committedAt = WorldStageTimings.start();
                skyPending=false;
                committing = List.of();
                // A queued frame of the old receipt can no longer be presented; release it so
                // this commit's own first frame is taken at once.
                if (queued != null && queued.frame.content() != receipt) {
                    drop(queued.frame, "superseded");
                    queued = null;
                }
                nativeAccepted = acceptNativeLocked();
            }
            if (nativeAccepted) kick();
            commit = null;
            pollFrame();
        }
        if (stagedCancellation != null) {
            // An unwanted staged tile is being canceled: drop it once the child has let it go.
            if (!stagedCancellation.isDone()) return;
            stagedCancellation.join();
            stagedCancellation = null;
            entries.remove(staged.resident());
            staged.source().close();
            sourceRetirementRevision++;
            staged = null;
            stagedReservation = null;
        }
        if (staged != null) {
            var latest = camera;
            if (latest == null) return;
            var target = new ArrayList<>(current);
            if (target.size() >= residentLimit()) {
                int outgoing = -1;
                for (int i = target.size() - 1; i >= 0; i--) {
                    if (!containsResident(wanted, target.get(i).resident())) {
                        outgoing = i;
                        break;
                    }
                }
                if (outgoing < 0 && !containsResident(wanted, staged.resident())) {
                    // A tile kept by hysteresis that is no longer wanted never displaces a
                    // wanted resident (#366): cancel it instead of committing it.
                    stagedCancellation = stagedReservation.cancel();
                    stagedCancellation.whenComplete((ignored, failure) -> kick());
                    publish(Phase.PREPARING, "Releasing a tile the camera left");
                    return;
                }
                target.remove(outgoing < 0 ? target.size() - 1 : outgoing);
            }
            target.add(staged);
            staged = null;
            stagedReservation = null;
            if (resultSeenAt != 0L) {
                WorldStageTimings.lap(
                        "handoff.controller.resultSeenToCommitIssued", resultSeenAt);
                resultSeenAt = 0L;
            }
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
            var site=unavailable.get(key(wanted.getFirst())).site();
            // A tile the helper keeps rejecting does not stall its neighbours (#365); the ready
            // status lists it among the unavailable sites instead.
            if(!site.equals(HELPER_REJECTED)) {
                publish(Phase.UNAVAILABLE,"Nearest terrain unavailable: "+site);
                return;
            }
        }
        long geometry = blockedGeometry;
        if (blockedOnGeometry) {
            try {
                geometry = source.geometryReleaseRevision();
            } catch (RuntimeException retired) {
                // A retired source cannot release; retry now so the attempt reports why.
                geometry = blockedGeometry + 1;
            }
        }
        // Read outside the lock: it takes the transport's lock, under which capacity listeners run.
        boolean capacityMoved = blockedCapacityMoved.getAsBoolean();
        synchronized (lock) {
            // The geometry lane frees only on its own release; capacity and pixels don't help.
            if (blockedOnGeometry
                    && blockedGeometry == geometry
                    && blockedDemand == demandRevision
                    && clock.getAsLong() - blockedAt < RETRY_NANOS) {
                // The release listener normally wakes it; the timeout stays the fallback.
                wakeAt(blockedAt + RETRY_NANOS);
                return;
            }
            if (!blockedOnGeometry
                    && (blockedOnMaterialPool || !capacityMoved)
                    && (blockedOnMaterialPool
                            ? blockedSourceRetirement == sourceRetirementRevision
                            : blockedPresentation == presentationRevision)
                    && blockedDemand == demandRevision
                    && clock.getAsLong() - blockedAt < RETRY_NANOS) {
                wakeAt(blockedAt + RETRY_NANOS);
                return;
            }
        }
        boolean needsSource=wanted.stream().anyMatch(tile->!unavailable.containsKey(key(tile))
                && current.stream().noneMatch(entry->same(tile,entry.resident())));
        if (entries.size() >= maxEntries && needsSource) {
            // Any missing center needs a source credit, even if never previously presented.
            boolean missingCenter=!wanted.isEmpty() && current.stream().noneMatch(
                    entry->same(wanted.getFirst(),entry.resident()));
            boolean needsUsefulFrame=missingCenter || skyPending;
            var subset=new ArrayList<>(current.stream().filter(
                    entry->containsResident(wanted,entry.resident())).toList());
            // A tile the borrowed frame shows is kept like a presented one (#366): dropping it frees
            // nothing until the borrow ends, so it is never the release a shrink commit counts on.
            // Both are written by the FX side under the lock: read one consistent pair.
            List<Entry> shown, borrowing;
            synchronized (lock) {
                shown = presented;
                borrowing = borrowedTable;
            }
            boolean releases=current.stream().anyMatch(entry->!subset.contains(entry) && !shown.contains(entry)
                    && !borrowing.contains(entry));
            if(needsUsefulFrame && !releases) {
                Entry victim=null;
                int farthest=-1;
                for(var entry:current) {
                    if(shown.contains(entry) || borrowing.contains(entry)
                            || !wanted.isEmpty() && same(wanted.getFirst(),entry.resident())) continue;
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
            wakeAt(waterTablesStarted + WATER_TABLE_WAIT_NANOS);
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
            var retryAt = helperRetryAt.get(key(tile));
            if (retryAt != null && clock.getAsLong() - retryAt < 0) {
                // Event-driven steps (#366): the back-off arms its own deadline, so an FX tick
                // on the controller clock retries the tile even if nothing else happens.
                wakeAt(retryAt);
                continue;
            }
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
            var issueSeam = tileIssueSeam;
            if (issueSeam != null) issueSeam.run();
            blockedOnMaterialPool = false;
            blockedOnGeometry = false;
            pending = new Pending(tile, reservation.orElseThrow(), source, waterReady() ? water : null, scope);
            // Revisions of the pixels and demand this tile was chosen from, not of now: a bump
            // landing later in this step must still wake a refusal.
            pending.issuedPresentation = stepPresentation;
            pending.issuedDemand = stepDemand;
            pending.issuedSourceRetirement = sourceRetirementRevision;
            pending.issuedGeometry = source.geometryReleaseRevision();
            long producing = WorldStageTimings.start();
            pending.result = pending.reservation.produce(pending.producer);
            if (commitDoneAt != 0L) {
                WorldStageTimings.lap(
                        "handoff.controller.commitDoneToNextReserve", commitDoneAt);
                commitDoneAt = 0L;
            }
            pending.result.whenComplete((ignored, failure) -> {
                WorldStageTimings.stop(failure == null ? "parent.tile.produceToStaged"
                        : "parent.tile.produceFailed", producing);
                resultAt = WorldStageTimings.start();
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
                                ? "Terrain unavailable: " + unavailableSites()
                                : acceptedReceipt == currentReceipt && currentReceipt != null
                                        ? readyDetail()
                                        : "Waiting for accepted viewport pixels");
    }

    private void startExterior(List<WorldFormats.Tile> wanted) {
        if (closing || session == null || !session.exteriorObjects()) return;
        if (exterior != null) {
            preemptCreaturesFor(wanted);
            return;
        }
        for (var tile : wanted) {
            for (var entry : current) {
                if (!buildingsWanted(tile, entry)) continue;
                var work = new Exterior(entry);
                long creatureBytes = creaturesLater ? WorldStreamSourceBridge.ExteriorPreparer.BUILDINGS_ONLY
                        : creaturesShed.contains(entry) ? 0 : creatureAllowance();
                prepare(work, tile, creatureBytes, "parent.objects.prepare");
                return;
            }
        }
        if (!creaturesLater) return;
        // Every wanted tile's buildings are in: creatures next, still nearest first.
        for (var tile : wanted) {
            for (var entry : current) {
                if (!same(tile, entry.resident())
                        || !creaturesPending.contains(entry)
                        || creatureBudgetDeferred(entry)) continue;
                var objects = entry.resident().exteriorObjects();
                if (objects != WorldStreamTransport.ExteriorObjects.DRAWN
                        && objects != WorldStreamTransport.ExteriorObjects.NONE) {
                    creaturesPending.remove(entry);
                    dropKept(entry);
                    continue;
                }
                var work = new Exterior(entry, true);
                work.reuse = takeKept(entry);
                prepare(work, tile, creatureAllowance(), "parent.objects.prepareCreatures");
                return;
            }
        }
    }

    /** Whether {@code entry} is {@code tile}'s resident and its buildings are still to be offered. */
    private boolean buildingsWanted(WorldFormats.Tile tile, Entry entry) {
        return same(tile, entry.resident())
                && !exteriorTried.contains(entry)
                && !budgetDeferred(entry)
                && entry.resident().exteriorObjects() == WorldStreamTransport.ExteriorObjects.NONE;
    }

    /**
     * A creature pass still preparing gives way to a wanted tile's buildings, so a long creature
     * conversion never holds back buildings the camera just reached; its tile stays pending and
     * is prepared again once no buildings are. So that constant movement cannot starve creatures,
     * a pass is preempted at most {@link #CREATURE_PREEMPTIONS} times in a row; then one runs to
     * its end.
     */
    private void preemptCreaturesFor(List<WorldFormats.Tile> wanted) {
        var work = exterior;
        if (!work.creaturesPass
                || work.prepared == null
                || work.prepared.isDone()
                || work.cancellation.isCanceled()
                || creaturePreemptions >= CREATURE_PREEMPTIONS) return;
        for (var tile : wanted) {
            for (var entry : current) {
                if (!buildingsWanted(tile, entry)) continue;
                creaturePreemptions++;
                WorldStageTimings.add("parent.objects.creaturesPreempted", 1);
                work.cancellation.cancel();
                return;
            }
        }
    }

    private void prepare(Exterior work, WorldFormats.Tile tile, long creatureBytes, String timing) {
        if (exteriorWorker == null)
            exteriorWorker = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("world-exterior-objects").factory());
        var lane = source;
        var owner = scope;
        // The child's sampled BC formats, reported in SESSION, set how textures are charged.
        int formats = session == null ? 0 : session.objectFormats();
        work.prepared = CompletableFuture.supplyAsync(
                () -> {
                    long preparing = WorldStageTimings.start();
                    try {
                        return exteriorPreparer.prepare(
                                lane, owner, tile, work.cancellation, formats, creatureBytes, work.reuse,
                                creaturesLater && !work.creaturesPass ? kept -> {
                                    // A session closing meanwhile no longer looks at this pass.
                                    if (closing) kept.close();
                                    else work.kept = kept;
                                } : null);
                    } finally {
                        WorldStageTimings.stop(work.cancellation.isCanceled()
                                ? "parent.objects.prepareCanceled" : timing, preparing);
                    }
                },
                exteriorWorker);
        exterior = work;
        work.prepared.whenComplete((ignored, failure) -> kick());
    }

    /** Keeps {@code kept} for {@code entry}'s creature pass when the bound allows; else closes it. */
    private void keep(Entry entry, WorldStreamSourceBridge.KeptBuildings kept) {
        if (kept == null) return;
        if (closing || !live(entry) || !current.contains(entry)
                || keptBuildingsBytes + kept.bytes() > KEPT_BUILDINGS_BYTES) {
            kept.close();
            WorldStageTimings.add("parent.objects.buildingsNotKept", 1);
            return;
        }
        var old = keptBuildings.put(entry, kept);
        keptBuildingsBytes += kept.bytes();
        if (old != null) {
            keptBuildingsBytes -= old.bytes();
            old.close();
        }
    }

    /** The buildings kept for {@code entry}, now owned by the caller, or null. */
    private WorldStreamSourceBridge.KeptBuildings takeKept(Entry entry) {
        var kept = keptBuildings.remove(entry);
        if (kept != null) keptBuildingsBytes -= kept.bytes();
        return kept;
    }

    /** Closes the buildings kept for {@code entry}, if any. */
    private void dropKept(Entry entry) {
        var kept = takeKept(entry);
        if (kept != null) kept.close();
    }

    /** A creature set refused for budget waits, like a buildings set, for a release since. */
    private boolean creatureBudgetDeferred(Entry entry) {
        Long refused = creatureBudget.get(entry);
        if (refused == null) return false;
        if (refused == session.objectsReleaseRevision()) return true;
        creatureBudget.remove(entry);
        return false;
    }

    /**
     * What the next tile's creatures may add: {@link WorldTileCreatures#AREA_BYTES} less the bytes
     * the resident tiles' attached creatures hold. A tile retired gives its share back.
     */
    private long creatureAllowance() {
        long held = 0;
        for (var entry : entries.values()) held += entry.source().creatures().bytes();
        return Math.max(0, WorldTileCreatures.AREA_BYTES - held);
    }

    /** A budget-refused resident waits until objects budget was released after its refusal. */
    private boolean budgetDeferred(Entry entry) {
        Long refused = exteriorBudget.get(entry);
        if (refused == null) return false;
        if (refused == session.objectsReleaseRevision()) return true;
        exteriorBudget.remove(entry);
        return false;
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
                // A canceled creature pass keeps its tile's buildings for when it resumes.
                if (work.creaturesPass && work.reuse != null) {
                    if (work.cancellation.isCanceled() && creaturesPending.contains(work.entry)) keep(work.entry, work.reuse);
                    else work.reuse.close();
                    work.reuse = null;
                }
                // Preempted by terrain (or retired): untried, so it restarts when terrain idles.
                if (work.creaturesPass) {
                    // Canceled (for terrain or buildings), it stays pending. Failed, its buildings
                    // stay as they are and creatures are not tried again; either way it ended, so
                    // the next pass may give way to buildings again.
                    if (!work.cancellation.isCanceled()) creaturePreemptions = 0;
                    if (!work.cancellation.isCanceled() && live(work.entry)) {
                        WorldDiagnostics.degraded("world creatures",
                                "World creature objects preparation failed; tile kept without creatures", failed);
                        creaturesPending.remove(work.entry);
                    }
                    return;
                }
                if (!work.cancellation.isCanceled() && live(work.entry)) {
                    WorldDiagnostics.degraded("exterior objects",
                            "World exterior objects preparation failed; tile kept as terrain only", failed);
                    exteriorTried.add(work.entry);
                    work.entry.source().exteriorFailedLate();
                }
                return;
            }
            work.prepared = null;
            if (work.reuse != null) {
                // Copied into this pass's set: no longer needed.
                work.reuse.close();
                work.reuse = null;
            }
            // Kept only while the tile waits for its creature pass; dropped below if it does not.
            if (work.kept != null) {
                keep(work.entry, work.kept);
                work.kept = null;
            }
            // A creature pass ran to its end: the next one may give way to buildings again.
            if (work.creaturesPass) creaturePreemptions = 0;
            if (work.creaturesPass && (copy.exterior() == null || copy.failed() || copy.creatures().drawn() == 0)) {
                // Nothing to add to what is drawn (or the buildings failed this time): the drawn
                // set stays, and the tile's creature counts are recorded as they are.
                exterior = null;
                if (!live(work.entry) || closing || !current.contains(work.entry)) return;
                creaturesPending.remove(work.entry);
                if (!copy.failed()) work.entry.source().creatures(copy.creatures());
                changed();
                return;
            }
            if (copy.exterior() == null || closing || !current.contains(work.entry)) {
                exterior = null;
                if (!live(work.entry)) return;
                if (copy.failed()) work.entry.source().exteriorFailedLate();
                if (copy.exterior() == null) work.entry.source().creatures(copy.creatures());
                if (copy.exterior() == null || copy.failed()) exteriorTried.add(work.entry);
                // A tile without buildings may still have creatures: attached as its first set.
                if (creaturesLater && copy.exterior() == null && !copy.failed()) {
                    creaturesPending.add(work.entry);
                    changed();
                } else {
                    dropKept(work.entry);
                }
                return;
            }
            work.summary = copy.summary();
            work.buildingsFailed = copy.failed();
            work.creatures = copy.creatures();
            work.picks = copy.picks();
            long attaching = WorldStageTimings.start();
            work.releaseRevision = session.objectsReleaseRevision();
            boolean replace = work.creaturesPass
                    && work.entry.resident().exteriorObjects() == WorldStreamTransport.ExteriorObjects.DRAWN;
            work.attached = replace ? session.replaceObjects(work.entry.resident(), copy.exterior())
                    : session.attachObjects(work.entry.resident(), copy.exterior());
            work.attached.whenComplete((ignored, failure) -> {
                WorldStageTimings.stop(work.creaturesPass ? "parent.objects.creaturesRoundTrip"
                        : "parent.objects.attachRoundTrip", attaching);
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
        var outcome = work.attached;
        if (!outcome.isCompletedExceptionally()
                && outcome.join() == WorldStreamTransport.ObjectsOutcome.BUDGET) {
            // Not a fault: the shared GPU budget was full. Offer it again once a drawn set has
            // been released since this offer was issued (not since the refusal arrived, so a
            // release landing in between still counts), still nearest first.
            WorldStageTimings.add("parent.objects.budgetRefused", 1);
            if (work.creaturesPass) {
                // The buildings it would replace stay drawn.
                WorldStageTimings.add("parent.objects.creaturesBudgetRefused", 1);
                creatureBudget.put(work.entry, work.releaseRevision);
                changed();
                return;
            }
            if (work.creatures.bytes() > 0 && creaturesShed.add(work.entry)) {
                // Its creatures may be what did not fit: its buildings are offered again at once,
                // without them.
                dropKept(work.entry);
                changed();
                return;
            }
            exteriorBudget.put(work.entry, work.releaseRevision);
            // Its buildings pass runs again; what it keeps then replaces these.
            dropKept(work.entry);
            changed();
            return;
        }
        exteriorTried.add(work.entry);
        if (work.creaturesPass) creaturesPending.remove(work.entry);
        try {
            switch (work.attached.join()) {
                case ATTACHED -> {
                    work.entry.source().exteriorAttached(work.summary);
                    work.entry.source().creatures(work.creatures);
                    work.entry.source().picks(work.picks);
                    if (work.buildingsFailed) work.entry.source().exteriorFailedLate();
                    if (creaturesLater && !work.creaturesPass) creaturesPending.add(work.entry);
                    updateObjects();
                }
                case FAILED -> {
                    if (work.creaturesPass && work.entry.resident().exteriorObjects()
                            == WorldStreamTransport.ExteriorObjects.DRAWN) {
                        // A failed replacement leaves the buildings drawn.
                        WorldDiagnostics.degraded("world creatures",
                                "World creature objects delivery failed; tile kept without creatures", null);
                        break;
                    }
                    // Write, read or upload failed; the helper reports no detail of its own.
                    WorldDiagnostics.degraded("exterior objects",
                            "World exterior objects delivery failed; tile kept as terrain only", null);
                    work.entry.source().exteriorFailedLate();
                }
                case DROPPED, BUDGET -> {}
            }
            // Kept buildings serve only a creature pass still to come.
            if (!creaturesPending.contains(work.entry)) dropKept(work.entry);
        } catch (RuntimeException failed) {
            // A stopping session cancels delivery; anything else fails only these objects.
            if (!closing && !CleanCancellation.is(failed)) work.entry.source().exteriorFailedLate();
            // Same rule as the guard above: a clean cancellation is routine, anything else is logged.
            if (!closing) WorldDiagnostics.degradedUnlessClean("exterior objects",
                    "World exterior objects delivery failed; tile kept as terrain only", failed);
        }
        changed();
    }

    /** Whether a tile's buildings or creatures are being prepared or delivered (measurement probes). */
    boolean loadingObjects() {
        return exterior != null;
    }

    /** The current creature part of the ready detail, unclipped (measurement probes). */
    String creatureStatus() {
        int tiles = 0;
        var creatures = WorldTileCreatures.Summary.NONE;
        for (var entry : current) {
            var tileCreatures = entry.source().creatures();
            if (tileCreatures.drawn() > 0) tiles++;
            creatures = creatures.plus(tileCreatures);
        }
        return creatureDetail(creatures, tiles);
    }

    /** Drawn creature models and the per-reason counts of spawn points not drawn. */
    static String creatureDetail(WorldTileCreatures.Summary creatures, int tiles) {
        var text = new StringBuilder();
        if (creatures.drawn() > 0)
            text.append("; ").append(creatures.drawn()).append(creatures.drawn() == 1 ? " static creature model on " : " static creature models on ").append(tiles)
                    .append(tiles == 1 ? " tile" : " tiles").append(creatures.restPosed() == 0 ? " (Stand pose, facing north"
                            : creatures.restPosed() == creatures.drawn() ? " (rest pose, facing north"
                            : " (" + creatures.restPosed() + " in rest pose, facing north")
                    // The World batch rule (#361) draws texture 1 of each surface, as World objects
                    // do; humanoid NPC appearances do not go through it.
                    .append(creatures.drawn() > creatures.npcs().drawn() ? ", first texture per surface)" : ")");
        // Camera-facing parts are not drawn in the World (#360, #384): disclosed per placement.
        if (creatures.billboardsLeftOut() > 0)
            text.append(", ").append(creatures.billboardsLeftOut())
                    .append(" with camera-facing parts left out");
        // Models carrying their idle (#384) play it near the camera, within the child's budgets.
        if (creatures.animated() > 0)
            text.append(", ").append(creatures.animated())
                    .append(" playing their idle near the camera");
        var npcs = creatures.npcs();
        if (npcs.drawn() > 0) {
            // Humanoid NPCs (#385): their item models and item records left out are disclosed.
            text.append(", of which ").append(plural(npcs.drawn(), "humanoid NPC"));
            var notes = new java.util.ArrayList<String>();
            // Drawn with visible sections it cannot draw (such as blended hair): disclosed.
            if (npcs.withOmittedSurfaces() > 0)
                notes.add(npcs.withOmittedSurfaces() + " with " + plural(npcs.omittedSurfaces(), "surface") + " left out");
            var undrawn = new java.util.ArrayList<String>();
            if (npcs.attachments() > 0) undrawn.add(plural(npcs.attachments(), "item model"));
            if (npcs.omittedItems() > 0) undrawn.add(plural(npcs.omittedItems(), "item record"));
            if (!undrawn.isEmpty()) notes.add(String.join(" and ", undrawn) + " not drawn");
            if (!notes.isEmpty()) text.append(" (").append(String.join("; ", notes)).append(")");
        }
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
        int textured = 0, partial = 0, height = 0, watered = 0, waterNoted = 0;
        String waterIssue = "";
        int buildingTiles = 0, buildingFailures = 0, creatureTiles = 0;
        var objects = WorldStreamSourceBridge.ExteriorSummary.NONE;
        var creatures = WorldTileCreatures.Summary.NONE;
        String reason = "";
        for (var entry : current) {
            if (entry.source().waterCells() > 0) watered++;
            var issues = entry.source().waterIssues();
            if (!issues.isEmpty()) {
                waterNoted++;
                if (waterIssue.isEmpty()) waterIssue = issues.getFirst();
            }
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
                // Chunks with no layers have nothing to draw: compare with what the material drew.
                if (entry.resident().coveredRectangles() < facts.supportedChunks()
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
                + (waterNoted == 0 ? "" : "; water source notes on " + plural(waterNoted, "tile")
                        + " (" + waterIssue + ")")
                + (sky == null || sky.label() == null ? "" : "; " + sky.label())
                + (buildingTiles == 0 ? "" : objectsDetail(objects, buildingTiles))
                + (buildingFailures == 0 ? "" : "; building objects failed on " + buildingFailures)
                + creatureDetail(creatures, creatureTiles)
                + (exterior == null ? "" : exterior.creaturesPass ? "; loading creatures" : "; loading buildings")
                + (water != null && waterTables != null && waterTables.isDone() && !waterReady()
                        ? "; water tables unavailable"
                        : "")
                + (unavailable.isEmpty()
                        ? ""
                        : "; "
                                + unavailable.size()
                                + " unavailable ("
                                + unavailableSites()
                                + ")");
    }

    /** Test seam: every frame released without presentation, with its drop path's name. */
    volatile java.util.function.BiConsumer<String, WorldStreamTransport.Frame> dropSeam;

    /** The one release point for frames the controller owns. */
    private void drop(WorldStreamTransport.Frame frame, String path) {
        frame.release();
        var seam = dropSeam;
        if (seam != null) seam.accept(path, frame);
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
            if(candidate.content()!=currentReceipt) { drop(candidate, "content"); return; }
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
                // Terrain in the viewing direction is still loading: present the live sky frame
                // rather than freezing on the previous one, and keep asking for useful terrain.
                boolean changed=!skyPending;
                skyPending=true;
                synchronized(lock) {
                    if(!closing && borrowed==null && queued==null && entries.size()>=maxEntries
                            && !presented.isEmpty() && candidate.content()==currentReceipt) {
                        // Source pins retire now: should this frame be refused (nearest tile not
                        // current), the old pixels stay a historical raster, not a live borrow.
                        presented=List.of();
                        historicalRaster=true;
                        changed=true;
                    }
                }
                publish(Phase.PREPARING,LOADING_IN_VIEW);
                queue(candidate,true);
                // This may run on the frame-only path, which runs no full step: a newly pending
                // sky or released pins must still reach one, which may now make room or retire
                // the old sources (#366).
                if(changed) {
                    skyWakeAtStep.set(steps.get()); // diagnostics and tests: the step count at this wake
                    wake.set(true);
                }
                return;
            }
            skyPending=false;
            queue(candidate,false);
        }
    }

    private void queue(WorldStreamTransport.Frame candidate, boolean skyOnly) {
        synchronized (lock) {
            if (!closing)
                queued = new Presentation(candidate, request.viewGeneration(), skyOnly);
            else {
                drop(candidate, "closing");
                return;
            }
        }
        changed();
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
        var liquidTiles = new ArrayList<com.vibeminer.rendering.world.LiquidSurface.Tile>();
        for (var entry : current) {
            var liquid = entry.source().liquids();
            if (liquid != null && liquidTiles.size() < com.vibeminer.rendering.world.LiquidSurface.MAX_TILES)
                liquidTiles.add(liquid);
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
        publishLiquids(com.vibeminer.rendering.world.LiquidSurface.of(liquidTiles), null);
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
        var latest = camera;
        var table = current;
        // Ground and area depend only on the camera and the current table, each replaced (never
        // mutated) when it changes: skip the per-chunk scan while neither did (#366).
        if (latest == areaCamera && table == areaTable) return;
        areaCamera = latest;
        areaTable = table;
        areaScans.incrementAndGet();
        updateGround();
        updateCollision(latest);
        if (latest == null) return;
        // The same chunk rule as a click's areaAt, over the metadata updateGround just retained.
        var result =
                WorldCurrentArea.locateIn(
                        request.map().id(),
                        latest.view().x(),
                        latest.view().y(),
                        areas,
                        request.areaIndex());
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

    /**
     * Whether the first wanted tile (the camera tile) or a wanted tile within one ring of it is
     * neither current nor known unavailable.
     */
    private boolean nearestMissing(List<WorldFormats.Tile> wanted) {
        if (wanted.isEmpty()) return false;
        var first = wanted.getFirst();
        for (var tile : wanted) {
            if (Math.max(Math.abs(tile.x() - first.x()), Math.abs(tile.y() - first.y())) > 1) continue;
            if (unavailable.containsKey(key(tile))) continue;
            if (current.stream().noneMatch(entry -> same(tile, entry.resident()))) return true;
        }
        return false;
    }

    private void retireUnused() {
        var remove = new ArrayList<Entry>();
        synchronized (lock) {
            for (var entry : entries.values()) {
                if (!current.contains(entry)
                        && !presented.contains(entry)
                        && !borrowedTable.contains(entry)
                        && !committing.contains(entry)
                        && entry != staged) remove.add(entry);
            }
        }
        for (var entry : remove) {
            entry.source().close();
            sourceRetirementRevision++;
            entries.remove(entry.resident());
            exteriorTried.remove(entry);
            exteriorBudget.remove(entry);
            creaturesShed.remove(entry);
            creaturesPending.remove(entry);
            creatureBudget.remove(entry);
            dropKept(entry);
        }
    }

    private List<WorldFormats.Tile> demand() {
        synchronized (lock) {
            // The revision of exactly this demand, read atomically with it (#305).
            stepDemand = demandRevision;
            return desired;
        }
    }

    private void publish(Phase phase, String detail) {
        synchronized (lock) {
            if(historicalRaster && phase!=Phase.FAILED && phase!=Phase.CLOSED
                    && phase!=Phase.UNAVAILABLE) detail=SHOWING_PREVIOUS;
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
            if (queued != null) drop(queued.frame, "retire");
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
        exteriorBudget.clear();
        creaturesShed.clear();
        creaturesPending.clear();
        for (var kept : keptBuildings.values()) kept.close();
        keptBuildings.clear();
        keptBuildingsBytes = 0;
        // What the pass in flight took or kept goes with the session too.
        var running = exterior;
        if (running != null) {
            if (running.reuse != null) running.reuse.close();
            if (running.kept != null) running.kept.close();
            running.reuse = null;
            running.kept = null;
        }
        creatureBudget.clear();
        exterior = null;
        if (exteriorWorker != null) exteriorWorker.shutdown();
        collisionCancellation.cancel();
        if (collisionWorker != null) collisionWorker.shutdown();
        collisionParts.clear();
        collisionLiquids.clear();
        objectCollision = com.vibeminer.rendering.world.ObjectCollision.empty();
        collisionMerged = java.util.Set.of();
        if (geometryWake != null) {
            try {
                geometryWake.close();
            } catch (Exception problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
            geometryWake = null;
        }
        source = null;
        water = null;
        waterTables = null;
        scope = null;
        opening = null;
        if (session != null) {
            // Stop the transport calling back into a retired controller (#366 review).
            session.onCapacity(null);
        }
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

    /**
     * The helper rejected one tile's packet (issue #365). The producer is deterministic, so the
     * same tile is likely rejected again: retry it after a doubling back-off, and after {@link
     * #MAX_HELPER_REJECTIONS} rejections leave it unavailable while it stays wanted, with a
     * visible status. Other tiles keep streaming meanwhile.
     */
    private void helperRejected(int tile) {
        int count = helperRejections.merge(tile, 1, Integer::sum);
        if (count >= MAX_HELPER_REJECTIONS) {
            helperRetryAt.remove(tile);
            unavailable.put(tile, WorldStreamSourceBridge.RefusalMetadata.helperRejected());
            publish(Phase.UNAVAILABLE, "Terrain unavailable: " + HELPER_REJECTED);
            wake.set(true);
            return;
        }
        long delay = RETRY_NANOS << (count - 1);
        helperRetryAt.put(tile, clock.getAsLong() + delay);
        publish(Phase.WAITING_CAPACITY, "Retrying a terrain tile the World helper rejected");
        wake.set(true);
        CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.NANOSECONDS)
                .execute(this::kick);
    }

    static final String HELPER_REJECTED = "HELPER_REJECTED";

    /** The distinct sites of the unavailable tiles, in first-seen order, e.g. "SOURCE_INPUT, HELPER_REJECTED". */
    private String unavailableSites() {
        var sites = new java.util.LinkedHashSet<String>();
        for (var origin : unavailable.values()) sites.add(origin.site());
        return String.join(", ", sites);
    }

    /** Whether a tile preparation already ended in a helper packet rejection. */
    private static boolean rejectedByHelper(CompletableFuture<?> result) {
        if (result == null || !result.isCompletedExceptionally()) return false;
        try {
            result.join();
            return false;
        } catch (CompletionException failure) {
            return failure.getCause() instanceof WorldStreamTransport.PreparationRefusal refusal
                    && refusal.reason() == WorldStreamTransport.Refusal.PACKET_REJECTED;
        } catch (java.util.concurrent.CancellationException canceled) {
            return false;
        }
    }

    /**
     * Test seam: whether the pending preparation has already ended in a helper rejection, so the
     * next step handles it. Called from the test thread while the controller worker is held, so
     * no step can replace {@code pending}; the read is unsynchronized and callers poll it.
     */
    boolean pendingRejectedByHelper() {
        var current = pending;
        return current != null && rejectedByHelper(current.result);
    }

    /**
     * Test observation: helper rejections and unavailable-terrain refusals that arrived for tiles
     * out of demand.
     */
    int rejectionsOutOfDemand() {
        return rejectionsOutOfDemand.get();
    }

    /** Test observation: helper rejections per tile key. */
    int helperRejections(int tileKey) {
        return helperRejections.getOrDefault(tileKey, 0);
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
