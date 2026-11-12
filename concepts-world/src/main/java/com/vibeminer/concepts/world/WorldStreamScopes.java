package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldStageTimings;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated worker-only source authority engine. No caller DTO can mint a heavy proof. */
final class WorldStreamScopes {
    static final CapturedRowsTypes.ReadLimits READ_LIMITS = WorldMapProofs.READ_LIMITS;

    static final class Scope {
        private final long owner, generation, mapId, wdtId;
        private final String wdtKey, digest;

        private Scope(
                long owner, long generation, long mapId, long wdtId, String wdtKey, String digest) {
            this.owner = owner;
            this.generation = generation;
            this.mapId = mapId;
            this.wdtId = wdtId;
            this.wdtKey = wdtKey;
            this.digest = digest;
        }

        long mapId() {
            return mapId;
        }

        long wdtId() {
            return wdtId;
        }

        String wdtKey() {
            return wdtKey;
        }

        String digest() {
            return digest;
        }
    }

    enum MapAuthority {
        COMPLETE_READY,
        OBSERVED_SELECTED_ROW
    }

    private static final class Source implements AutoCloseable {
        private final String key;
        private final Long declaredLength;
        private final int sourceLimit;
        private byte[] bytes;

        Source(String key, byte[] bytes, Long declaredLength, int sourceLimit) {
            this.key = key;
            this.bytes = bytes;
            this.declaredLength = declaredLength;
            this.sourceLimit = sourceLimit;
        }

        String key() {
            return key;
        }

        byte[] bytes() {
            return Objects.requireNonNull(bytes, "Source input retired");
        }

        @Override
        public void close() {
            bytes = null;
        }
    }

    private record Maid(
            String key,
            long length,
            Long declaredLength,
            int sourceLimit,
            int[] tuples,
            BitSet present) {}

    private record Proof(
            Scope token,
            WorldMapProofs.Binding map,
            long wdtLength,
            Long wdtDeclaredLength,
            int wdtSourceLimit,
            int[] maid,
            BitSet present) {}

    private final WorldMapProofs maps;
    private final BuildTables borrowedTables;
    private WorldTerrainLiquids liquidInspector;
    private final ArrayList<LiquidCredit> liquidCredits = new ArrayList<>(2);
    private final ArrayList<WorldTerrainLiquids.Owner> liquidQuarantine = new ArrayList<>(2);
    private final BuildFiles files;
    private final BuildRef build;
    private final String locale;
    private final long owner;
    private final AtomicBoolean working = new AtomicBoolean();

    /**
     * Lowered derived read limits by kind, for tests only: a value below the operation's profile
     * replaces it, so the profile's limit paths can be reached without exhausting it.
     */
    private final int[] loweredDerivedReads = new int[DerivedKind.values().length];

    {
        java.util.Arrays.fill(loweredDerivedReads, Integer.MAX_VALUE);
    }

    /** Lowers the derived read limit of {@code kind} for later operations (tests only). */
    void lowerDerivedReadsForTest(DerivedKind kind, int reads) {
        loweredDerivedReads[kind.ordinal()] = Math.max(0, reads);
    }

    /** Largest read-ahead bytes any operation held unconsumed (#371); diagnostics and tests. */
    final java.util.concurrent.atomic.AtomicLong prefetchPeakHeldBytes =
            new java.util.concurrent.atomic.AtomicLong();

    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean closed;
    private long generation;
    private Proof current, replacement;

    /** The build's files, for readers outside the scoped source lane (walking collision). */
    BuildFiles files() {
        return files;
    }

    /**
     * The session's source cache: the files derived reads (building groups, doodad models, their
     * images) bind, shared with walking collision, which keeps its parsed sources there too.
     */
    private final WorldSourceCache sources;

    WorldSourceCache sources() {
        return sources;
    }

    WorldStreamScopes(BuildTables tables, BuildFiles files) {
        this(tables, files, new WorldSourceCache());
    }

    /** With a given source cache (tests: {@link WorldSourceCache#off} for re-read paths). */
    WorldStreamScopes(BuildTables tables, BuildFiles files, WorldSourceCache sources) {
        this.sources = Objects.requireNonNull(sources);
        maps = new WorldMapProofs(tables, files);
        borrowedTables = tables;
        this.files = files;
        build = maps.build();
        locale = maps.locale();
        long nonce;
        do {
            nonce = new SecureRandom().nextLong();
        } while (nonce == 0);
        owner = nonce;
    }

    /** Synchronous preparation operation: caller must run it on a source worker, never FX. */
    @SuppressWarnings("try")
    Scope create(WorldService.MapInfo requested, Cancellation external) {
        begin();
        var operationCancellation = cancelLane;
        Throwable primary = null;
        try (var registration = external.onCancel(operationCancellation::request)) {
            var token = lifetimeChild;
            operationCancellation.check();
            token.check();
            WorldMapProofs.Binding map = maps.bind(requested.id(), token);
            synchronized (this) {
                replacement = null;
            }
            Maid maid = readMaid(map.wdtId(), token);
            // Recheck the qualified selected row and WDT identity before committing a proof.
            if (!maps.revalidate(map, token)
                    || !maid.key()
                            .equals(
                                    statContent(
                                                    map.wdtId(),
                                                    token,
                                                    WorldSourceRefusals.Site.MAP_BINDING)
                                            .contentKey())) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_BINDING);
            }
            String digest = digest(map, maid.key());
            synchronized (this) {
                checkOpen();
                operationCancellation.check();
                if (generation == Long.MAX_VALUE) {
                    throw new IllegalStateException("Scope generation exhausted");
                }
                var scope =
                        new Scope(
                                owner, ++generation, map.mapId(), map.wdtId(), maid.key(), digest);
                replacement =
                        new Proof(
                                scope,
                                map,
                                maid.length(),
                                maid.declaredLength(),
                                maid.sourceLimit(),
                                maid.tuples(),
                                maid.present());
                return scope;
            }
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            end(primary);
        }
    }

    // Owns and retires source bytes/parsed inventory before any next Map capture is opened.
    private Maid readMaid(long id, Cancellation token) {
        try (var wdt = read(id, 1 << 20, token)) {

            var world = WorldFormats.wdt(wdt.bytes(), token::isCanceled);
            int[] maid = new int[4096 * 8];
            var present = new BitSet(4096);
            for (var tile : world.tiles()) {
                token.check();
                int ordinal = tile.y() * 64 + tile.x();
                if (present.get(ordinal)) {
                    throw new IllegalArgumentException("Duplicate MAID coordinate");
                }
                present.set(ordinal);
                long[] refs = references(tile);
                for (int i = 0; i < 8; i++) {
                    if (refs[i] < 0 || refs[i] > 0xffff_ffffL) {
                        throw new IllegalArgumentException("Invalid MAID reference");
                    }
                    maid[ordinal * 8 + i] = (int) refs[i];
                }
            }
            return new Maid(
                    wdt.key(),
                    wdt.bytes().length,
                    wdt.declaredLength,
                    wdt.sourceLimit,
                    maid,
                    present);
        }
    }

    // Per-operation linked token is protected by the one-active CAS, never exposed in a Scope.
    private Cancellation lifetimeChild;
    private CancelLane cancelLane;
    private Throwable cleanupFailure;

    private static final class CancelLane {
        private final Cancellation child = new Cancellation();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private boolean requested, sealed;

        void request() {
            synchronized (this) {
                if (sealed || requested) {
                    return;
                }
                requested = true;
            }
            try {
                Thread.startVirtualThread(
                        () -> {
                            try {
                                child.cancel();
                                done.complete(null);
                            } catch (RuntimeException | Error failure) {
                                done.completeExceptionally(
                                        new IllegalStateException(
                                                "Source cancellation callback failed", failure));
                            }
                        });
            } catch (RuntimeException | Error startFailure) {
                done.completeExceptionally(
                        new IllegalStateException(
                                "Source cancellation dispatch failed", startFailure));
                throw startFailure;
            }
        }

        synchronized void check() {
            if (requested) {
                throw new java.util.concurrent.CancellationException();
            }
            child.check();
        }

        void seal() {
            boolean idle;
            synchronized (this) {
                sealed = true;
                idle = !requested;
            }
            if (idle) {
                done.complete(null);
            }
        }

        void sealAndDrain() {
            seal();
            done.join();
        }
    }

    private void begin() {
        requireHealthy();
        if (!working.compareAndSet(false, true)) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_WORKER);
        }
        boolean rejected;
        Throwable fault;
        synchronized (this) {
            fault = cleanupFailure;
            rejected = closed || fault != null;
            if (!rejected) {
                cancelLane = new CancelLane();
                lifetimeChild = cancelLane.child;
            }
        }
        if (rejected) {
            boolean finish;
            synchronized (this) {
                working.set(false);
                finish = closed && liquidCredits.isEmpty();
            }
            if (finish) {
                completeDrain(fault);
            }
            if (fault != null) {
                throw new IllegalStateException("Source cleanup failed", fault);
            }
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
    }

    private Throwable drainCancellation() {
        try {
            cancelLane.sealAndDrain();
            return null;
        } catch (RuntimeException | Error failure) {
            return new IllegalStateException(
                    "Source cancellation did not finish successfully", failure);
        }
    }

    /** Active remains owned until all returned/unreturned R cleanup has completed. */
    private void releaseWorking(Throwable problem) {
        boolean finish;
        Throwable terminal;
        synchronized (this) {
            if (problem != null) {
                cleanupFailure = problem;
                current = null;
                replacement = null;
            }
            lifetimeChild = null;
            cancelLane = null;
            working.set(false);
            finish = closed && liquidCredits.isEmpty();
            terminal = cleanupFailure;
        }
        if (finish) {
            completeDrain(terminal);
        }
    }

    private synchronized void recordCleanupFailure(Throwable problem) {
        cleanupFailure = problem;
        current = null;
        replacement = null;
    }

    private void completeDrain(Throwable failure) {
        // Every completion path must honor the actual worker and all inspection pins.
        synchronized (this) {
            if (!closed || working.get() || !liquidCredits.isEmpty()) {
                return;
            }
        }
        if (failure == null) {
            drained.complete(null);
        } else {
            drained.completeExceptionally(failure);
        }
    }

    synchronized void requireHealthy() {
        if (cleanupFailure != null) {
            throw new IllegalStateException("Source cleanup failed", cleanupFailure);
        }
    }

    private void end(Throwable primary) {
        Throwable failure = null;
        try {
            var laneFailure = drainCancellation();
            if (laneFailure != null) {
                failure = cleanupFailure(primary, laneFailure);
            }
        } finally {
            releaseWorking(failure);
        }
        rethrow(failure);
    }

    synchronized void activate(Scope scope) {
        if (scope == null || scope.owner != owner) {
            throw new IllegalStateException("Foreign scope");
        }
        requireHealthy();
        checkOpen();
        if (working.get()) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_WORKER);
        }
        if (replacement == null || !replacement.token().equals(scope)) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SCOPE_RETIRED);
        }
        current = replacement;
        replacement = null;
    }

    @SuppressWarnings("try")
    WorldFormats.Tile recorded(Scope scope, WorldFormats.Tile caller, Cancellation external) {
        begin();
        var operationCancellation = cancelLane;
        Throwable primary = null;
        try (var registration = external.onCancel(operationCancellation::request)) {
            var token = lifetimeChild;
            operationCancellation.check();
            Proof proof;
            synchronized (this) {
                proof = resolve(scope);
            }
            validateProof(proof, token);
            int ordinal = caller.y() * 64 + caller.x();
            if (!proof.present().get(ordinal)) {
                throw new IllegalArgumentException("Unrecorded terrain coordinate");
            }
            long[] refs = references(caller);
            for (int i = 0; i < 8; i++) {
                if (refs[i] != Integer.toUnsignedLong(proof.maid()[ordinal * 8 + i])) {
                    throw new IllegalArgumentException("Caller differs from recorded MAID");
                }
            }
            operationCancellation.check();
            synchronized (this) {
                resolve(scope);
            }
            return caller;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            end(primary);
        }
    }

    /** Doodad models (and their first SKIN) a tile may read, the interior allowance's too. */
    static final int DOODAD_MODEL_READS =
            com.vibeminer.rendering.world.ExteriorObjectLimits.DOODAD_MODELS
                    + com.vibeminer.rendering.world.ExteriorObjectLimits.DOODAD_INTERIOR_MODELS;

    /**
     * Building image slots: the tile's opaque images, those of very large buildings drawn by group
     * and their interiors (one shared allowance), and the cutout and blended images.
     */
    static final int WMO_IMAGE_SLOTS =
            com.vibeminer.rendering.world.ExteriorObjectLimits.BUILDING_IMAGES
                    + com.vibeminer.rendering.world.ExteriorObjectLimits.LARGE_IMAGES
                    + com.vibeminer.rendering.world.ExteriorObjectLimits.LARGE_INTERIOR_IMAGES
                    + com.vibeminer.rendering.world.ExteriorObjectLimits.MATERIAL_IMAGES;

    /**
     * Reads of one image slot's source file at most, when the retained-source bound keeps none of
     * them (WorldExteriorObjects). An RGBA8 chain: the registration decode, the level the budget
     * allots (relevelRgba from allocateImages) and the file's own mip levels for its chain (the
     * level fetch in the mip chain). A chain sampled as blocks: the registration decode, the file's
     * blocks (blockChain in chainImages), and, when those no longer match, the RGBA8 fallback's own
     * relevelRgba read and its level fetch. The worst case is four.
     */
    static final int IMAGE_READS_PER_SLOT = 4;

    /** Doodad image slots, the interior allowance's included. */
    static final int DOODAD_IMAGE_SLOTS =
            com.vibeminer.rendering.world.ExteriorObjectLimits.DOODAD_IMAGES
                    + com.vibeminer.rendering.world.ExteriorObjectLimits.DOODAD_INTERIOR_IMAGES;

    /**
     * Groups a tile may read only to size border-crossing roots with their interiors: {@link
     * WorldExteriorObjects#MAX_SIZING_READS} for each of its {@link WorldExteriorObjects#MAX_ROOTS}
     * roots, the worst case, so no tile-wide running total decides anything.
     */
    static final int SIZING_GROUP_READS =
            WorldExteriorObjects.MAX_ROOTS * WorldExteriorObjects.MAX_SIZING_READS;

    /**
     * Groups a tile may read a second time: on instance maps, an owned interior of a root drawn by
     * group whose copy the bound on held copies dropped is read again when it is placed, outside
     * the tile's group reads. Each is one of the groups the tile read ({@link
     * WorldExteriorObjects#MAX_GROUPS}), at most once.
     */
    static final int REREAD_GROUP_READS = WorldExteriorObjects.MAX_GROUPS;

    enum UseProfile {
        TERRAIN_BASIC(67, 0, 0, 0, 0, 0, 0),
        BASIC_WMO(11, 1, 1 << 10, 1, 32 << 10, 6, 64 << 10),
        LIQUID_INSPECTION(11, 0, 0, 0, 0, 0, 0),
        /**
         * Per-tile outdoor exterior set: WDT, OBJ0, the capped derived WMO reads (256 groups, up to
         * 256 more for each of the 24 roots, read only to size border-crossing buildings with their
         * interiors, up to 256 read again, {@link #REREAD_GROUP_READS}, and every building image
         * slot, {@link #WMO_IMAGE_SLOTS}) and the capped BASIC doodad reads (the doodad models,
         * their first SKIN and their image slots, the interior allowance's included, {@link
         * #DOODAD_MODEL_READS} and {@link #DOODAD_IMAGE_SLOTS}). Every image slot is a distinct
         * source, read again for each later use whose bytes were not retained
         * (WorldExteriorObjects.RETAINED_SOURCE_BYTES): {@link #IMAGE_READS_PER_SLOT} times at
         * most. The image counts derive from the caps. The exterior set never calls {@link
         * TileAdmission#readDerivedCandidate} past a limit: it checks {@link
         * TileAdmission#derivedReadsLeft} first and leaves that one source unread instead.
         */
        BASIC_EXTERIOR(
                2
                        + 24
                        + 256
                        + SIZING_GROUP_READS
                        + WMO_IMAGE_SLOTS
                        + DOODAD_MODEL_READS
                        + DOODAD_MODEL_READS
                        + DOODAD_IMAGE_SLOTS,
                24,
                1 << 20,
                // A group read again binds the source it read before: no binding of its own.
                256 + SIZING_GROUP_READS + REREAD_GROUP_READS,
                com.vibeminer.formats.world.WmoGroupSpans.MAX_BYTES,
                IMAGE_READS_PER_SLOT * WMO_IMAGE_SLOTS,
                1 << 20,
                DOODAD_MODEL_READS,
                2 << 20,
                DOODAD_MODEL_READS,
                1 << 20,
                // Doodad images too: every slot, the interior allowance's included.
                IMAGE_READS_PER_SLOT * DOODAD_IMAGE_SLOTS,
                1 << 20);
        private final int bindings, roots, rootLimit, groups, groupLimit, images, imageLimit;
        private final int models, modelLimit, skins, skinLimit, modelImages, modelImageLimit;

        UseProfile(
                int bindings,
                int roots,
                int rootLimit,
                int groups,
                int groupLimit,
                int images,
                int imageLimit) {
            this(
                    bindings,
                    roots,
                    rootLimit,
                    groups,
                    groupLimit,
                    images,
                    imageLimit,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0);
        }

        UseProfile(
                int bindings,
                int roots,
                int rootLimit,
                int groups,
                int groupLimit,
                int images,
                int imageLimit,
                int models,
                int modelLimit,
                int skins,
                int skinLimit,
                int modelImages,
                int modelImageLimit) {
            this.bindings = bindings;
            this.roots = roots;
            this.rootLimit = rootLimit;
            this.groups = groups;
            this.groupLimit = groupLimit;
            this.images = images;
            this.imageLimit = imageLimit;
            this.models = models;
            this.modelLimit = modelLimit;
            this.skins = skins;
            this.skinLimit = skinLimit;
            this.modelImages = modelImages;
            this.modelImageLimit = modelImageLimit;
        }

        /** Maximum distinct consumed source identities, including the scope WDT. */
        int bindingLimit() {
            return bindings;
        }

        /** Profiles that read OBJ0 and derived WMO root/group/image candidates. */
        boolean derivedWmo() {
            return roots > 0;
        }

        int derivedReads(DerivedKind kind) {
            return switch (kind) {
                case WMO_ROOT -> roots;
                case WMO_GROUP -> groups;
                case WMO_IMAGE -> images;
                case M2_MODEL -> models;
                case M2_SKIN -> skins;
                case M2_IMAGE -> modelImages;
            };
        }

        int derivedLimit(DerivedKind kind) {
            return switch (kind) {
                case WMO_ROOT -> rootLimit;
                case WMO_GROUP -> groupLimit;
                case WMO_IMAGE -> imageLimit;
                case M2_MODEL -> modelLimit;
                case M2_SKIN -> skinLimit;
                case M2_IMAGE -> modelImageLimit;
            };
        }
    }

    /**
     * Names, from a read-ahead read's bytes, the read its consumer makes right after consuming it;
     * runs on the read-ahead thread under that read's own cancellation.
     */
    @FunctionalInterface
    interface Follow {
        long next(byte[] bytes, Cancellation cancel);
    }

    /**
     * Names, from a read-ahead read's bytes, every read its consumer makes right after consuming
     * it, in the order it makes them; runs on the read-ahead thread under that read's own
     * cancellation. May name reads the consumer then skips: those are only read for nothing.
     */
    @FunctionalInterface
    interface FollowAll {
        long[] next(byte[] bytes, Cancellation cancel);
    }

    enum DerivedKind {
        WMO_ROOT,
        WMO_GROUP,
        WMO_IMAGE,
        /** BASIC_EXTERIOR doodad model, its first SKIN, and its texture images. */
        M2_MODEL,
        M2_SKIN,
        M2_IMAGE
    }

    enum RecordedInput {
        ROOT,
        TEX0,
        OBJ0
    }

    @FunctionalInterface
    interface TileSourceUse<R extends AutoCloseable> {
        R prepare(TileAdmission admission);
    }

    private record Binding(long id, String key, long length) {}

    /** A callback-local input. No service or admission remains behind an expired owner. */
    static final class Input implements AutoCloseable {
        private Source source;
        private TileAdmission admission;
        private final Binding binding;
        private final Long declaredLength;
        private final int sourceLimit;

        private Input(Source source, TileAdmission admission, Binding binding) {
            this.source = source;
            this.admission = admission;
            this.binding = binding;
            this.declaredLength = source.declaredLength;
            this.sourceLimit = source.sourceLimit;
        }

        Long declaredLength() {
            return declaredLength;
        }

        int sourceLimit() {
            return sourceLimit;
        }

        byte[] bytes() {
            if (source == null) {
                throw new IllegalStateException("Input retired");
            }
            admission.check();
            return source.bytes();
        }

        long fileDataId() {
            return binding.id();
        }

        String contentKey() {
            return binding.key();
        }

        long consumedLength() {
            return binding.length();
        }

        @Override
        public void close() {
            if (source != null) {
                admission.checkThread();
                source.close();
                source = null;
                admission.input = null;
                admission = null;
            }
        }
    }

    /** Detached observations only; no services, Scope, heavy proof or replay authority. */
    static final class AdmissionMetadata {
        private final BuildRef build;
        private final String locale, scopeDigest, wdtKey;
        private final long owner, generation, mapId, wdtId, wdtLength;
        private final Long wdtDeclaredLength;
        private final int wdtSourceLimit;
        private final MapAuthority mapAuthority;

        private AdmissionMetadata(WorldStreamScopes engine, Proof proof) {
            build = engine.build;
            locale = engine.locale;
            var scope = proof.token();
            owner = scope.owner;
            generation = scope.generation;
            mapId = scope.mapId();
            scopeDigest = scope.digest();
            wdtId = scope.wdtId();
            wdtKey = scope.wdtKey();
            wdtLength = proof.wdtLength();
            wdtDeclaredLength = proof.wdtDeclaredLength();
            wdtSourceLimit = proof.wdtSourceLimit();
            mapAuthority =
                    switch (proof.map().authority()) {
                        case COMPLETE_READY -> MapAuthority.COMPLETE_READY;
                        case OBSERVED_SELECTED_ROW -> MapAuthority.OBSERVED_SELECTED_ROW;
                    };
        }

        MapAuthority mapAuthority() {
            return mapAuthority;
        }

        BuildRef build() {
            return build;
        }

        String locale() {
            return locale;
        }

        long owner() {
            return owner;
        }

        long generation() {
            return generation;
        }

        long mapId() {
            return mapId;
        }

        String scopeDigest() {
            return scopeDigest;
        }

        long wdtId() {
            return wdtId;
        }

        String wdtKey() {
            return wdtKey;
        }

        long wdtLength() {
            return wdtLength;
        }

        Long wdtDeclaredLength() {
            return wdtDeclaredLength;
        }

        int wdtSourceLimit() {
            return wdtSourceLimit;
        }
    }

    static final class TileAdmission {
        private WorldStreamScopes engine;
        private Cancellation token;
        private final WorldFormats.Tile recorded;
        private final UseProfile profile;
        private final long mapId;
        private final AdmissionMetadata metadata;
        private final ArrayList<Binding> bindings = new ArrayList<>();
        private List<WorldTerrainTextureSource.Observation> materialObservations = List.of();
        private Input input;
        private final long threadId = Thread.currentThread().threadId();
        private boolean reserved;
        private final int[] derivedReads = new int[DerivedKind.values().length];
        private Prefetcher prefetcher;

        private TileAdmission(
                WorldStreamScopes engine,
                Cancellation token,
                WorldFormats.Tile recorded,
                UseProfile profile,
                Proof proof) {
            this.engine = engine;
            this.token = token;
            this.recorded = recorded;
            this.profile = profile;
            this.mapId = proof.token().mapId();
            this.metadata = new AdmissionMetadata(engine, proof);
            bindings.add(
                    new Binding(proof.token().wdtId(), proof.token().wdtKey(), proof.wdtLength()));
        }

        private void checkThread() {
            if (Thread.currentThread().threadId() != threadId) {
                throw new IllegalStateException("Admission belongs to source worker");
            }
        }

        private void check() {
            checkThread();
            if (engine == null) {
                throw new IllegalStateException("Admission expired");
            }
            engine.cancelLane.check();
        }

        WorldFormats.Tile recorded() {
            check();
            return recorded;
        }

        AdmissionMetadata metadata() {
            check();
            return metadata;
        }

        UseProfile profile() {
            check();
            return profile;
        }

        long mapId() {
            check();
            return mapId;
        }

        Cancellation cancellation() {
            check();
            return token;
        }

        /** Metadata from the actual owned operation, available only after its worker drains. */
        void observeMaterial(WorldTerrainTextureSource.Operation operation) {
            check();
            if (profile != UseProfile.TERRAIN_BASIC
                    || reserved
                    || input != null
                    || !materialObservations.isEmpty()) {
                throw new IllegalStateException("Material observation phase");
            }
            var observed = operation.observations();
            var ids = new java.util.HashSet<Long>();
            for (var binding : bindings) {
                ids.add(binding.id());
            }
            for (var value : observed) {
                ids.add(value.fileId());
            }
            if (ids.size() > 67) {
                throw new IllegalStateException("Material observation limit");
            }
            materialObservations = observed;
        }

        /** Rechecks a private cache's existing actual-read proof; never constructs such a proof. */
        boolean reuseRecordedRoot(String key, long length) {
            check();
            if (reserved || input != null) {
                throw new IllegalStateException("Input credit busy");
            }
            var fresh =
                    engine.statContent(
                            recorded.rootId(), token, WorldSourceRefusals.Site.SOURCE_BINDING);
            if (!Objects.equals(key, fresh.contentKey())
                    || fresh.size() != null && fresh.size() != length) {
                return false;
            }
            if (length < 12 || length > (profile == UseProfile.BASIC_WMO ? 2 << 20 : 8 << 20)) {
                throw new IllegalArgumentException("Root proof bound");
            }
            var binding = new Binding(recorded.rootId(), key, length);
            if (bindings.size() >= profile.bindingLimit()) {
                throw new IllegalStateException("Binding limit");
            }
            for (var prior : bindings) {
                if (prior.id() == binding.id() && !prior.equals(binding)) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
                }
            }
            if (!bindings.contains(binding)) {
                bindings.add(binding);
            }
            return true;
        }

        void liquidCleanupFailed(WorldLiquidTables.CleanupFailure failure) {
            checkThread();
            if (engine == null || profile != UseProfile.LIQUID_INSPECTION) {
                throw new IllegalStateException("Expired liquid cleanup context");
            }
            engine.recordCleanupFailure(failure);
        }

        void rememberLiquidTable(WorldLiquidTables.Observed observed) {
            check();
            if (profile != UseProfile.LIQUID_INSPECTION || reserved || input != null) {
                throw new IllegalStateException("Liquid table phase differs");
            }
            // Identity facts only: never load listfile names or sniff formats for a liquid table.
            var fresh =
                    engine.statContent(
                            observed.sourceId(), token, WorldSourceRefusals.Site.SOURCE_BINDING);
            if (!observed.sourceKey().equals(fresh.contentKey())
                    || fresh.size() != null && fresh.size() != observed.sourceLength()) {
                throw new WorldTerrainLiquids.Changed();
            }
            var next =
                    new Binding(observed.sourceId(), observed.sourceKey(), observed.sourceLength());
            for (var prior : bindings) {
                if (prior.id() == next.id()) {
                    if (!prior.equals(next)) {
                        throw new WorldTerrainLiquids.Changed();
                    }
                    return;
                }
            }
            if (bindings.size() == 4) {
                throw new IllegalStateException("Liquid binding bound");
            }
            bindings.add(next);
        }

        Input readRecorded(RecordedInput kind) {
            check();
            long id =
                    switch (kind) {
                        case ROOT -> {
                            if (profile == UseProfile.BASIC_EXTERIOR) {
                                throw new IllegalArgumentException("Unused exterior root input");
                            }
                            yield recorded.rootId();
                        }
                        case TEX0 -> {
                            if (profile != UseProfile.TERRAIN_BASIC) {
                                throw new IllegalArgumentException("Unused WMO tex0 input");
                            }
                            yield recorded.tex0Id();
                        }
                        case OBJ0 -> {
                            if (!profile.derivedWmo()) {
                                throw new IllegalArgumentException("Wrong input profile");
                            }
                            yield recorded.obj0Id();
                        }
                    };
            if (kind == RecordedInput.ROOT && id == 0) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.NUMERIC_ROOT);
            }
            return readCandidate(
                    id,
                    profile == UseProfile.BASIC_WMO && kind == RecordedInput.ROOT
                            ? 2 << 20
                            : 8 << 20);
        }

        /** Reads of {@code kind} this operation may still make under its profile. */
        int derivedReadsLeft(DerivedKind kind) {
            return readLimit(kind) - derivedReads[Objects.requireNonNull(kind).ordinal()];
        }

        /** The profile's derived read limit of {@code kind}, or a lower one a test set. */
        private int readLimit(DerivedKind kind) {
            return Math.min(profile.derivedReads(kind), engine.loweredDerivedReads[kind.ordinal()]);
        }

        /** Actual bytes only: private WMO factory must independently prove parent ancestry. */
        Input readDerivedCandidate(long id, DerivedKind kind) {
            check();
            if (!profile.derivedWmo()) {
                throw new IllegalArgumentException("Wrong derived profile");
            }
            if (reserved || input != null) {
                throw new IllegalStateException("Input credit busy");
            }
            int ordinal = Objects.requireNonNull(kind).ordinal();
            if (derivedReads[ordinal] >= readLimit(kind)) {
                throw new IllegalStateException(
                        switch (kind) {
                            case WMO_ROOT -> "WMO root read limit";
                            case WMO_GROUP -> "WMO group read limit";
                            case WMO_IMAGE -> "WMO image read limit";
                            case M2_MODEL -> "Doodad model read limit";
                            case M2_SKIN -> "Doodad skin read limit";
                            case M2_IMAGE -> "Doodad image read limit";
                        });
            }
            derivedReads[ordinal]++;
            int limit = profile.derivedLimit(kind);
            return readCandidate(id, limit, profile == UseProfile.BASIC_EXTERIOR, kind);
        }

        /**
         * Hints that this operation will soon consume {@code id} as {@code kind}, so its read may
         * start now on a bounded pool (#371). A hint only moves I/O earlier: limits are rechecked,
         * bindings and counts are recorded only at consumption, and a hint never consumed is closed
         * unread-by-anyone when the operation ends. Callers hint only reads they will make next, in
         * order; hints beyond the kind's remaining reads or the queue bound are ignored.
         */
        void prefetch(long id, DerivedKind kind) {
            prefetch(id, kind, null, null);
        }

        /**
         * As {@link #prefetch(long, DerivedKind)} for a read the operation makes at most once (an
         * image it registers): not read ahead when already consumed, and kept in the earlier place
         * when an earlier hint names it too. Taking it passes no other hint, so a hint the consumer
         * then skips (an image the tile could not register after all) is only read for nothing. At
         * most {@code room} reads of {@code kind}, less every one the operation consumes from now
         * on, are read ahead: the caller's own cap on them.
         */
        void prefetchOnce(long id, DerivedKind kind, int room) {
            check();
            Objects.requireNonNull(kind);
            if (!Prefetcher.enabled || !profile.derivedWmo() || id <= 0 || id > 0xffff_ffffL) {
                return;
            }
            if (prefetcher == null) {
                prefetcher = new Prefetcher(engine, token, profile == UseProfile.BASIC_EXTERIOR);
            }
            prefetcher.hint(
                    id,
                    kind,
                    profile.derivedLimit(kind),
                    Math.min(room, readLimit(kind) - derivedReads[kind.ordinal()]),
                    null,
                    true);
        }

        /**
         * As {@link #prefetch(long, DerivedKind)}, and once that read has its bytes, {@code follow}
         * names on the read-ahead thread the {@code next} read the consumer will make right after
         * consuming this one (0 for none). That read is hinted in its place, ahead of every later
         * hint. {@code follow} must name only a read the consumer will make for these bytes, and
         * must not change them; any failure of it only leaves the next read to the consumer.
         */
        void prefetch(long id, DerivedKind kind, DerivedKind next, Follow follow) {
            prefetch(id, kind, next, follow, null, null, 0);
        }

        /**
         * As {@link #prefetch(long, DerivedKind, DerivedKind, Follow)}, and once that follow-up
         * read has its bytes, {@code followAll} names on the read-ahead thread the {@code last}
         * reads the consumer makes right after consuming it, in order (a doodad model's images
         * after its SKIN). Each is read ahead at most once per operation: one the operation has
         * already consumed is not read again, and one an earlier hint also names is read in the
         * earlier place. {@code followAll} may name reads the consumer then skips (an image the
         * tile registered meanwhile); those are read for nothing and dropped when passed. At most
         * {@code lastRoom} of them, less every {@code last} read the operation consumes from now
         * on, are read ahead: the caller's own cap on them (its images left to register).
         */
        void prefetch(
                long id,
                DerivedKind kind,
                DerivedKind next,
                Follow follow,
                DerivedKind last,
                FollowAll followAll,
                int lastRoom) {
            check();
            Objects.requireNonNull(kind);
            if (!Prefetcher.enabled || !profile.derivedWmo() || id <= 0 || id > 0xffff_ffffL) {
                return;
            }
            if (prefetcher == null) {
                prefetcher = new Prefetcher(engine, token, profile == UseProfile.BASIC_EXTERIOR);
            }
            Prefetcher.Next after =
                    followAll == null
                            ? null
                            : new Prefetcher.Next(
                                    Objects.requireNonNull(last),
                                    null,
                                    followAll,
                                    profile.derivedLimit(last),
                                    Math.min(
                                            lastRoom,
                                            readLimit(last) - derivedReads[last.ordinal()]),
                                    null);
            Prefetcher.Next then =
                    follow == null
                            ? null
                            : new Prefetcher.Next(
                                    Objects.requireNonNull(next),
                                    follow,
                                    null,
                                    profile.derivedLimit(next),
                                    readLimit(next) - derivedReads[next.ordinal()],
                                    after);
            prefetcher.hint(
                    id,
                    kind,
                    profile.derivedLimit(kind),
                    readLimit(kind) - derivedReads[kind.ordinal()],
                    then,
                    false);
        }

        /**
         * Withdraws every outstanding hint and cancels its read: the caller stopped before
         * consuming them (a capped or failed root), and a source it will not use is never fetched
         * on its behalf.
         */
        void cancelPrefetch() {
            // Only the thread check: this runs in a finally after the root read, and a canceled
            // lane must neither replace that read's own exception nor skip the withdrawal.
            checkThread();
            if (prefetcher != null) {
                prefetcher.cancelAll();
            }
        }

        private Input readCandidate(long id, int limit) {
            return readCandidate(id, limit, false, null);
        }

        /**
         * With {@code classifyUnavailable}, only a missing, protected or over-limit source is
         * refused at SOURCE_UNAVAILABLE; every other refusal keeps its original site. A derived
         * {@code kind} may take its bytes from an earlier {@link #prefetch} of the same read.
         */
        private Input readCandidate(
                long id, int limit, boolean classifyUnavailable, DerivedKind kind) {
            if (id <= 0 || id > 0xffff_ffffL) {
                throw new IllegalArgumentException("Missing or invalid input ID");
            }
            if (reserved || input != null) {
                throw new IllegalStateException("Input credit busy");
            }
            if (bindings.size() >= profile.bindingLimit()) {
                throw new IllegalStateException("Binding limit");
            }
            // Reservation precedes even stat/open, and remains held through actual read drain.
            reserved = true;
            Source source = null;
            try {
                var prefetched =
                        kind == null || prefetcher == null ? null : prefetcher.take(id, kind);
                if (prefetched != null) {
                    // A read-ahead hit is timed apart: source.read.* stays a full read only, so
                    // its numbers compare with runs without read-ahead. The series count is the
                    // number of hits. A miss falls through to an ordinary, timed read.
                    long waiting = WorldStageTimings.start();
                    // The taken read has left the set the operation's cancel hook reaches, so
                    // link it to the operation here: a cancel interrupts the wait as it would the
                    // serial read, and the fallback read below then refuses at once.
                    var linked = token.onCancel(prefetched.cancel::cancel);
                    try {
                        source = Prefetcher.await(prefetched.future);
                    } finally {
                        linked.close();
                    }
                    if (source != null && waiting != 0L) {
                        WorldStageTimings.stop("source.prefetchWait." + profile.name(), waiting);
                    }
                }
                if (source == null) {
                    long started = WorldStageTimings.start();
                    source =
                            kind == null
                                    ? engine.read(id, limit, token, classifyUnavailable)
                                    : engine.readDerived(id, limit, token, classifyUnavailable);
                    if (started != 0L) {
                        WorldStageTimings.stop("source.read." + profile.name(), started);
                        WorldStageTimings.add(
                                "source.readBytes." + profile.name(), source.bytes().length);
                    }
                }
                if (kind != null && prefetcher != null && prefetched == null) {
                    // A taken read was counted consumed when taken.
                    prefetcher.consumed(id, kind);
                }
                var binding = new Binding(id, source.key(), source.bytes().length);
                for (var prior : bindings) {
                    if (prior.id() == id && !prior.equals(binding)) {
                        throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
                    }
                }
                if (!bindings.contains(binding)) {
                    bindings.add(binding);
                }
                input = new Input(source, this, binding);
                source = null;
                return input;
            } finally {
                if (source != null) {
                    source.close();
                }
                reserved = false;
            }
        }

        private void expire() {
            if (input != null) {
                input.close();
            }
            if (prefetcher != null) {
                // Stops and drains every unconsumed read before the operation can end.
                prefetcher.close();
                engine.prefetchPeakHeldBytes.accumulateAndGet(
                        prefetcher.peakHeldBytes(), Math::max);
                prefetcher = null;
            }
            bindings.clear();
            materialObservations = List.of();
            token = null;
            engine = null;
        }
    }

    /**
     * One operation's read-ahead (#371). Hinted derived reads run on virtual threads, at most
     * {@link #READS} at once and at most {@link #WINDOW} started and not yet consumed.
     *
     * <p>Memory: once finished, unconsumed reads hold {@link #HELD_BYTES}, no new read starts, so
     * the bytes held outside the operation's single input credit stay below HELD_BYTES plus the
     * in-flight reads, each within its kind's existing limit. {@link #peakHeldBytes} records the
     * observed peak.
     *
     * <p>Order: hints are consumed in the order given. Taking a hint drops every earlier hint the
     * consumer passed (it will not come back for them), and nothing else is ever dropped, so the
     * read the consumer needs next is never evicted. A hint may name a follow-up read that its
     * bytes decide (a doodad model's SKIN), consumed right after it: it is queued in that place,
     * and may repeat a read another hint's follow-up names, since the consumer reads it once for
     * each. That follow-up may in turn name every read its consumer makes next (the model's
     * images), queued in order right after it. Those are read once per operation: a read already
     * consumed is not queued, and one another hint also names is kept in the earlier of the two
     * places, moving it there if it was queued or started later, so a take never passes the reads
     * between. {@link #cancelAll} withdraws every hint and cancels every started read, for a root
     * that stops early. Each read has its own cancellation, also canceled with the operation's;
     * {@link #close} cancels, joins and drops everything before the operation ends.
     */
    static final class Prefetcher {
        static final int READS = 4, WINDOW = 8, HELD_BYTES = 8 << 20;

        /**
         * Hints waiting to start: every doodad model a tile may read, its first SKIN and every
         * doodad image slot, so the read-ahead of one placement pass is never cut short by the
         * queue. A hint is a few words; memory stays bounded by {@link #WINDOW} and {@link
         * #HELD_BYTES}.
         */
        static final int QUEUE = 2 * DOODAD_MODEL_READS + DOODAD_IMAGE_SLOTS;

        /**
         * Orders of hints given by the caller are this far apart; the follow-ups a hint's reads
         * name take the orders in between, in the order they are named.
         */
        static final long STRIDE = 1L << 16;

        /** Read-ahead on or off for new hints; off reads serially as before. Tests compare both. */
        static volatile boolean enabled = true;

        /**
         * Test seam: told, under the read-ahead's lock, each once read named while an earlier
         * caller hint still holds it back in {@link #named}.
         */
        static volatile java.util.function.LongConsumer heldBackForTest;

        private record Key(long id, DerivedKind kind) {}

        /**
         * The reads a hint's bytes name next: their kind, how to find them (one read, or every read
         * in order), their kind's size limit, how many reads of their kind the operation may still
         * consume when hinted, and what each of their bytes names in turn. Reads named all at once
         * are read once per operation. The read-ahead keeps {@code remaining} as a ceiling on the
         * kind's consumed reads ({@link #ceilings}), so each consumption since the hint lowers what
         * may still be read ahead.
         */
        record Next(
                DerivedKind kind,
                Follow follow,
                FollowAll all,
                int limit,
                int remaining,
                Next then) {}

        /**
         * Caller hints are spaced by {@link #STRIDE}: the follow-ups their reads name take the
         * orders just after them, so they are consumed, and dropped by {@link #take}, exactly as if
         * they had been hinted in their place. {@code once}: a read named with others, never queued
         * again once consumed. {@code ceiling}: the most reads of its kind the operation may have
         * consumed when this one is read ahead. {@code keep}: for a once read, the latest order
         * that also names it, where it moves instead of being dropped when the consumer passes its
         * own place without taking it (an earlier model that skipped an image a later one reads).
         */
        private record Hint(
                Key key, int limit, int ceiling, long order, Next next, boolean once, long keep) {
            Hint(Key key, int limit, int ceiling, long order, Next next, boolean once) {
                this(key, limit, ceiling, order, next, once, order);
            }

            Hint at(long place, long wanted) {
                return new Hint(key, limit, ceiling, place, next, once, wanted);
            }
        }

        private static final class Started {
            final Key key;
            long order;
            final CompletableFuture<Source> future = new CompletableFuture<>();
            final Cancellation cancel = new Cancellation();
            long held;

            /** Set under the lock when the read leaves the set without a consumer taking it. */
            boolean dropped;

            /** A read named with others ({@link Hint#once}). */
            final boolean once;

            /** {@link Hint#keep}. */
            long keep;

            Started(Key key, long order, boolean once, long keep) {
                this.key = key;
                this.order = order;
                this.once = once;
                this.keep = keep;
            }
        }

        private final WorldStreamScopes engine;
        private final Cancellation.Registration link;
        private final boolean classifyUnavailable;
        private final java.util.PriorityQueue<Hint> queue =
                new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(Hint::order));

        /** Started and not yet taken or dropped, by order. */
        private final java.util.TreeMap<Long, Started> started = new java.util.TreeMap<>();

        /** Per caller hint's order, the follow-up orders after it already given. */
        private final java.util.TreeMap<Long, Long> followUps = new java.util.TreeMap<>();

        /** Every read the operation consumed, read ahead or not. */
        private final java.util.Set<Key> consumed = new java.util.HashSet<>();

        /** Reads consumed per kind, read ahead or not. */
        private final int[] consumedKinds = new int[DerivedKind.values().length];

        /**
         * Caller hints, by order, whose reads may still name once reads (a model whose images its
         * SKIN names): once reads named after them wait until they have named theirs or can no
         * longer, so once reads are queued in the order they are consumed and each kind's room is
         * spent on the earliest.
         */
        private final java.util.TreeSet<Long> unresolved = new java.util.TreeSet<>();

        /** Once reads named and waiting for every earlier caller hint to resolve, by its order. */
        private final java.util.TreeMap<Long, List<Hint>> named = new java.util.TreeMap<>();

        private long nextOrder, heldBytes, peakHeldBytes;
        private int running;
        private boolean closed;

        private Prefetcher(
                WorldStreamScopes engine, Cancellation operation, boolean classifyUnavailable) {
            this.engine = engine;
            this.classifyUnavailable = classifyUnavailable;
            this.link = operation.onCancel(this::cancelReads);
        }

        synchronized void hint(
                long id, DerivedKind kind, int limit, int remaining, Next next, boolean once) {
            var key = new Key(id, kind);
            if (closed || queue.size() >= QUEUE) {
                return;
            }
            for (var read : started.values()) {
                if (read.key.equals(key)) {
                    return;
                }
            }
            for (var hint : queue) {
                if (hint.key().equals(key)) {
                    return;
                }
            }
            if (enqueue(key, limit, ceiling(kind, remaining), nextOrder, ceilings(next), once)) {
                if (namesOnce(next)) {
                    unresolved.add(nextOrder);
                }
                nextOrder += STRIDE;
                pump();
            }
        }

        /** Whether the reads {@code next} names, or theirs in turn, include once reads. */
        private static boolean namesOnce(Next next) {
            return next != null && (next.all() != null || namesOnce(next.then()));
        }

        /** The caller hint's order at or before {@code order}. */
        private static long base(long order) {
            return order - Math.floorMod(order, STRIDE);
        }

        /**
         * The caller hint at {@code base} names no more once reads: queues, in order, every named
         * list no earlier unresolved hint still holds back.
         */
        private void resolved(long base) {
            unresolved.remove(base);
            release();
        }

        /** Queues, in order, every named list no earlier unresolved caller hint holds back. */
        private void release() {
            while (!named.isEmpty()
                    && (unresolved.isEmpty() || unresolved.first() > named.firstKey())) {
                for (var hint : named.pollFirstEntry().getValue()) {
                    enqueue(hint.key(), hint.limit(), hint.ceiling(), hint.order(), null, true);
                }
            }
        }

        /** The consumed reads of {@code kind} at which {@code remaining} more are spent. */
        private int ceiling(DerivedKind kind, int remaining) {
            return (int)
                    Math.min(Integer.MAX_VALUE, (long) consumedKinds[kind.ordinal()] + remaining);
        }

        /** {@code next} with each remaining count turned into its kind's ceiling as of now. */
        private Next ceilings(Next next) {
            return next == null
                    ? null
                    : new Next(
                            next.kind(),
                            next.follow(),
                            next.all(),
                            next.limit(),
                            ceiling(next.kind(), next.remaining()),
                            ceilings(next.then()));
        }

        /** How many more reads of {@code kind} may be read ahead under {@code ceiling}. */
        private int room(DerivedKind kind, int ceiling) {
            return ceiling - consumedKinds[kind.ordinal()];
        }

        /**
         * Records a read the operation consumed without taking it from the read-ahead: a once read
         * is never queued after it, and it spends one of its kind's room.
         */
        synchronized void consumed(long id, DerivedKind kind) {
            consumedLocked(new Key(id, kind));
        }

        private void consumedLocked(Key key) {
            consumed.add(key);
            consumedKinds[key.kind().ordinal()]++;
        }

        /**
         * Queues one hint unless the queue is full or its kind has no reads left to consume. A once
         * hint is not queued when consumed already, or when the same read is queued or started at
         * an earlier order; queued or started at a later one, it moves to this order instead.
         */
        private boolean enqueue(
                Key key, int limit, int ceiling, long order, Next next, boolean once) {
            if (closed || queue.size() >= QUEUE) {
                return false;
            }
            if (once) {
                if (consumed.contains(key)) {
                    return false;
                }
                Hint same = null;
                for (var hint : queue) {
                    if (hint.key().equals(key)) {
                        same = hint;
                    }
                }
                if (same != null) {
                    // One read, at the earlier place, kept until the later one is passed too.
                    queue.remove(same);
                    queue.add(
                            same.at(
                                    Math.min(same.order(), order),
                                    Math.max(same.keep(), Math.max(same.order(), order))));
                    return same.order() > order;
                }
                for (var read : started.values()) {
                    if (read.key.equals(key)) {
                        read.keep = Math.max(read.keep, Math.max(read.order, order));
                        if (read.order > order) {
                            started.remove(read.order);
                            read.order = order;
                            started.put(order, read);
                        }
                        return false;
                    }
                }
            }
            int pending = 0;
            for (var hint : queue) {
                if (hint.key().kind() == key.kind()) {
                    pending++;
                }
            }
            for (var read : started.values()) {
                if (read.key.kind() == key.kind()) {
                    pending++;
                }
            }
            // Never read ahead more of a kind than the operation may still consume.
            if (pending >= room(key.kind(), ceiling)) {
                return false;
            }
            queue.add(new Hint(key, limit, ceiling, order, next, once));
            return true;
        }

        /**
         * The place a passed once read moves to instead of being dropped: {@code keep} when a later
         * place already names it, else a later place whose names still wait in {@link #named}
         * (claimed: that place reads it from here); -1 when none does.
         */
        private long laterPlace(Key key, long keep, long passed) {
            if (keep > passed) {
                return keep;
            }
            for (var list : named.tailMap(base(passed), true).values()) {
                for (var it = list.iterator(); it.hasNext(); ) {
                    var hint = it.next();
                    if (hint.key().equals(key) && hint.order() > passed) {
                        it.remove();
                        return hint.order();
                    }
                }
            }
            return -1;
        }

        /** The next follow-up order after the caller hint at or before {@code order}, or -1. */
        private long followUp(long order) {
            long base = order - Math.floorMod(order, STRIDE);
            long given = followUps.merge(base, 1L, Long::sum);
            return given < STRIDE ? base + given : -1;
        }

        /**
         * The started read of the earliest hint of exactly this read, now owned by the caller, or
         * null when the caller reads it itself (never hinted, or hinted and not yet started). Every
         * earlier hint is dropped: the consumer has passed it. A read named with others is taken
         * alone and passes nothing, since it may sit later than where it is consumed. The
         * operation's cancel hook no longer reaches the returned read, so the caller links its
         * cancellation for as long as it waits.
         */
        Started take(long id, DerivedKind kind) {
            var key = new Key(id, kind);
            var dropped = new ArrayList<Started>();
            Started result = null;
            synchronized (this) {
                long order = Long.MAX_VALUE;
                Started mine = null;
                Hint queued = null;
                for (var read : started.values()) {
                    if (read.key.equals(key)) {
                        mine = read;
                        order = read.order;
                        break; // by order: the earliest
                    }
                }
                for (var hint : queue) {
                    if (hint.key().equals(key) && hint.order() < order) {
                        mine = null;
                        queued = hint;
                        order = hint.order();
                    }
                }
                if (order == Long.MAX_VALUE) {
                    return null;
                }
                if (mine != null) {
                    // Consumed from here on, whatever the wait gives: a read the consumer then
                    // makes itself is this same consumption.
                    consumedLocked(key);
                }
                if (mine == null && namesOnce(queued.next())) {
                    // Read by the consumer itself: its follow-ups are never named here.
                    resolved(base(queued.order()));
                }
                if (mine != null ? mine.once : queued.once()) {
                    // A read named with others may sit later than where it is consumed, when the
                    // read that should have named it in place was not read ahead: take it alone
                    // and pass nothing, since the consumer still comes back for every hint before
                    // it.
                    if (mine != null) {
                        started.remove(order);
                        heldBytes -= mine.held;
                    } else {
                        queue.remove(queued);
                    }
                    pump();
                    return mine;
                }
                long passed = order;
                // A once read a later place also names moves there instead of being dropped.
                var moved = new ArrayList<Hint>();
                queue.removeIf(
                        hint -> {
                            if (hint.order() > passed) {
                                return false;
                            }
                            long later =
                                    hint.once() ? laterPlace(hint.key(), hint.keep(), passed) : -1;
                            if (later > 0) {
                                moved.add(hint.at(later, later));
                            }
                            return true;
                        });
                queue.addAll(moved);
                followUps.headMap(base(passed)).clear();
                // Hints the consumer passed name nothing it still reads.
                named.headMap(base(passed)).clear();
                if (!unresolved.isEmpty() && unresolved.first() < base(passed)) {
                    unresolved.headSet(base(passed)).clear();
                    release();
                }
                var kept = new ArrayList<Started>();
                for (var entry = started.headMap(passed).entrySet().iterator(); entry.hasNext(); ) {
                    var next = entry.next().getValue();
                    entry.remove();
                    long later = next.once ? laterPlace(next.key, next.keep, passed) : -1;
                    if (later > 0) {
                        next.order = later;
                        next.keep = later;
                        kept.add(next);
                        continue;
                    }
                    next.dropped = true;
                    dropped.add(next);
                    heldBytes -= next.held;
                }
                for (var read : kept) {
                    started.put(read.order, read);
                }
                if (mine != null) {
                    started.remove(passed);
                    heldBytes -= mine.held;
                    result = mine;
                }
                pump();
            }
            drop(dropped);
            return result;
        }

        /** Withdraws every hint and cancels and drops every started read (a root stopped early). */
        void cancelAll() {
            List<Started> dropped;
            synchronized (this) {
                queue.clear();
                unresolved.clear();
                named.clear();
                dropped = new ArrayList<>(started.values());
                dropped.forEach(read -> read.dropped = true);
                started.clear();
                heldBytes = 0;
            }
            drop(dropped);
        }

        synchronized long peakHeldBytes() {
            return peakHeldBytes;
        }

        private void pump() {
            while (!closed
                    && running < READS
                    && started.size() < WINDOW
                    && heldBytes < HELD_BYTES
                    && !queue.isEmpty()) {
                var hint = queue.poll();
                if (hint.once() && consumed.contains(hint.key())) {
                    continue; // consumed since it was queued: never read again
                }
                int reading = 0;
                for (var read : started.values()) {
                    if (read.key.kind() == hint.key().kind()) {
                        reading++;
                    }
                }
                if (reading >= room(hint.key().kind(), hint.ceiling())) {
                    // Consumption since it was queued spent its room: started now, it might be
                    // a read the operation can no longer make.
                    if (namesOnce(hint.next())) {
                        resolved(base(hint.order()));
                    }
                    continue;
                }
                running++;
                var read = new Started(hint.key(), hint.order(), hint.once(), hint.keep());
                started.put(hint.order(), read);
                Thread.ofVirtual()
                        .name("world-source-prefetch")
                        .start(
                                () -> {
                                    Source source = null;
                                    Throwable failure = null;
                                    long[] next = NONE;
                                    try {
                                        source =
                                                engine.readDerived(
                                                        hint.key().id(),
                                                        hint.limit(),
                                                        read.cancel,
                                                        classifyUnavailable);
                                        next = follow(hint, source, read.cancel);
                                    } catch (Throwable thrown) {
                                        failure = thrown;
                                    }
                                    finished(hint, read, source, next);
                                    if (failure != null) {
                                        read.future.completeExceptionally(failure);
                                    } else {
                                        read.future.complete(source);
                                    }
                                });
            }
        }

        private static final long[] NONE = new long[0];

        /**
         * The reads the hint's bytes name next, in order, or none. Runs before the read completes,
         * so the follow-ups are queued before the consumer can take these bytes and come back for
         * them.
         */
        private static long[] follow(Hint hint, Source source, Cancellation cancel) {
            var next = hint.next();
            if (next == null) {
                return NONE;
            }
            try {
                long[] ids =
                        next.follow() != null
                                ? new long[] {next.follow().next(source.bytes(), cancel)}
                                : next.all().next(source.bytes(), cancel);
                return Arrays.stream(ids).filter(id -> id > 0 && id <= 0xffff_ffffL).toArray();
            } catch (RuntimeException ignored) {
                return NONE; // The consumer reads them itself, or decides not to.
            }
        }

        private synchronized void finished(Hint hint, Started read, Source source, long[] next) {
            running--;
            if (source != null && started.get(read.order) == read) {
                read.held = source.bytes().length;
                heldBytes += read.held;
                peakHeldBytes = Math.max(peakHeldBytes, heldBytes);
            }
            // A taken read is still consumed, so its follow-ups are too; a dropped one's are not.
            var then = hint.next();
            boolean live = !read.dropped && !read.cancel.isCanceled();
            if (then != null && then.all() != null) {
                // Once reads wait in their place until every earlier caller hint resolves.
                var list = new ArrayList<Hint>();
                for (long id : live ? next : NONE) {
                    long order = followUp(read.order);
                    if (order < 0) {
                        break;
                    }
                    list.add(
                            new Hint(
                                    new Key(id, then.kind()),
                                    then.limit(),
                                    then.remaining(), // a ceiling since hint()
                                    order,
                                    null,
                                    true));
                }
                if (!list.isEmpty()) {
                    var held = heldBackForTest;
                    if (held != null
                            && !unresolved.isEmpty()
                            && unresolved.first() < base(read.order)) {
                        list.forEach(once -> held.accept(once.key().id()));
                    }
                    named.merge(
                            base(read.order),
                            list,
                            (a, b) -> {
                                a.addAll(b);
                                return a;
                            });
                }
                resolved(base(read.order));
            } else if (then != null) {
                boolean queued = false;
                for (long id : live ? next : NONE) {
                    long order = followUp(read.order);
                    if (order < 0) {
                        break;
                    }
                    queued |=
                            enqueue(
                                    new Key(id, then.kind()),
                                    then.limit(),
                                    then.remaining(), // a ceiling since hint()
                                    order,
                                    then.then(),
                                    false);
                }
                if (!queued && namesOnce(then)) {
                    resolved(base(read.order)); // nothing left to name its once reads
                }
            }
            pump();
        }

        private synchronized void cancelReads() {
            for (var read : started.values()) {
                read.cancel.cancel();
            }
        }

        /**
         * The read's own source, or null when it failed for a reason of the read rather than of the
         * source (store queue pressure, cancellation of the read-ahead): the caller then reads it
         * itself, so its own read decides the outcome exactly as without read-ahead. A source that
         * changed under the read (SOURCE_BINDING) is the source's own outcome and is rethrown,
         * never masked by a second read.
         */
        static Source await(CompletableFuture<Source> future) {
            try {
                return future.join();
            } catch (java.util.concurrent.CompletionException wrapped) {
                if (wrapped.getCause() instanceof WorldTerrainStreaming.Refusal refusal
                        && WorldSourceRefusals.Site.SOURCE_BINDING.name().equals(refusal.site())) {
                    throw refusal;
                }
                return null;
            } catch (RuntimeException missed) {
                return null;
            }
        }

        void close() {
            List<Started> dropped;
            synchronized (this) {
                closed = true;
                queue.clear();
                unresolved.clear();
                named.clear();
                dropped = new ArrayList<>(started.values());
                dropped.forEach(read -> read.dropped = true);
                started.clear();
                heldBytes = 0;
            }
            drop(dropped);
            link.close();
        }

        /** Cancels, waits for and empties reads nobody will consume; never under the lock. */
        private static void drop(List<Started> reads) {
            for (var read : reads) {
                read.cancel.cancel();
            }
            for (var read : reads) {
                try {
                    read.future.join().close();
                } catch (RuntimeException ignored) {
                    // A canceled or failed read that nobody consumed holds no bytes.
                }
            }
        }
    }

    /** The callback owns unreturned outputs; this method owns only successfully returned R. */
    @SuppressWarnings("try")
    <R extends AutoCloseable> R withRecordedTile(
            Scope scope,
            WorldFormats.Tile caller,
            UseProfile profile,
            Cancellation external,
            TileSourceUse<R> use) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(use);
        begin();
        TileAdmission admission = null;
        R result = null;
        Throwable primary = null;
        var operationCancellation = cancelLane;
        try (var registration = external.onCancel(operationCancellation::request)) {
            var token = lifetimeChild;
            operationCancellation.check();
            Proof proof;
            synchronized (this) {
                proof = resolve(scope);
            }
            validateProof(proof, token);
            int ordinal = caller.y() * 64 + caller.x();
            if (!proof.present().get(ordinal)) {
                throw new IllegalArgumentException("Unrecorded coordinate");
            }
            var refs = references(caller);
            for (int i = 0; i < 8; i++) {
                if (refs[i] != Integer.toUnsignedLong(proof.maid()[ordinal * 8 + i])) {
                    throw new IllegalArgumentException("Caller differs from recorded MAID");
                }
            }
            admission = new TileAdmission(this, token, caller, profile, proof);
            result = Objects.requireNonNull(use.prepare(admission), "Missing owned result");
            if (admission.input != null || admission.reserved) {
                throw new IllegalStateException("Input not drained before transfer");
            }
            var finalIds = new java.util.HashSet<Long>();
            for (var binding : admission.bindings) {
                finalIds.add(binding.id());
            }
            for (var observed : admission.materialObservations) {
                finalIds.add(observed.fileId());
            }
            if (finalIds.size() > profile.bindingLimit()) {
                throw new IllegalStateException("Final binding observation limit");
            }
            for (var binding : admission.bindings) {
                try {
                    validateBinding(binding, token);
                } catch (RuntimeException | Error failure) {
                    if (binding.id() == scope.wdtId()) {
                        invalidateFailedProof(scope, token, failure);
                    }
                    throw failure;
                }
            }
            for (var observed : admission.materialObservations) {
                try {
                    token.check();
                    var fresh =
                            WorldSourceFutures.await(
                                    files.statContent(
                                            FileTypes.FileRef.id(observed.fileId()), token));
                    token.check();
                    if (!observed.matches(fresh)) {
                        throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MATERIAL_BINDING);
                    }
                } catch (RuntimeException | Error failure) {
                    if (observed.fileId() == scope.wdtId()) {
                        invalidateFailedProof(scope, token, failure);
                    }
                    throw failure;
                }
            }
            validateProof(proof, token);
            synchronized (this) {
                operationCancellation.check();
                resolve(scope);
            }
        } catch (RuntimeException | Error failure) {
            primary = failure;
        }
        Throwable cleanupProblem = null;
        try {
            if (admission != null) {
                Throwable before = primary;
                primary = closePreserving(admission::expire, primary);
                if (primary != before) {
                    cleanupProblem = primary;
                }
            }
            operationCancellation.seal();
            if (primary == null) {
                try {
                    operationCancellation.check();
                } catch (RuntimeException | Error failure) {
                    primary = failure;
                }
            }
            // Known failure/cancellation retires R before awaiting callbacks that may depend on it.
            if (primary != null && result != null) {
                Throwable before = primary;
                primary = closePreserving(result, primary);
                if (primary != before) {
                    cleanupProblem = primary;
                }
                result = null;
            }
            var laneFailure = drainCancellation();
            if (laneFailure != null) {
                primary = cleanupFailure(primary, laneFailure);
                cleanupProblem = primary;
            }
            if (cleanupProblem != null) {
                recordCleanupFailure(cleanupProblem);
            }
            if (primary == null) {
                try {
                    synchronized (this) {
                        requireHealthy();
                        external.check();
                        operationCancellation.check();
                        resolve(scope);
                    }
                } catch (RuntimeException | Error failure) {
                    primary = failure;
                }
            }
            if (primary != null && result != null) {
                Throwable before = primary;
                primary = closePreserving(result, primary);
                if (primary != before) {
                    cleanupProblem = primary;
                }
                result = null;
            }
        } finally {
            releaseWorking(cleanupProblem);
        }
        rethrow(primary);
        return result;
    }

    private static Throwable closePreserving(AutoCloseable owner, Throwable primary) {
        try {
            owner.close();
        } catch (Exception | Error failure) {
            return cleanupFailure(primary, failure);
        }
        return primary;
    }

    private static IllegalStateException cleanupFailure(Throwable primary, Throwable cleanup) {
        var fatal =
                new IllegalStateException(
                        "Source operation cleanup failed", primary == null ? cleanup : primary);
        if (primary != null && cleanup != primary) {
            fatal.addSuppressed(cleanup);
        }
        return fatal;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new IllegalStateException("Source operation failed", failure);
        }
    }

    private void validateBinding(Binding binding, Cancellation token) {
        token.check();
        var lookup =
                WorldSourceFutures.await(
                        files.statContent(FileTypes.FileRef.id(binding.id()), token));
        token.check();
        var fresh = lookup.file();
        if (lookup.state() != FileTypes.FileLookupState.FOUND
                || fresh == null
                || fresh.fileDataId() != binding.id()
                || !binding.key().equals(fresh.contentKey())
                || fresh.size() != null && fresh.size() != binding.length()) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
        }
    }

    private void validateProof(Proof proof, Cancellation token) {
        try {
            if (!maps.revalidate(proof.map(), token)) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_BINDING);
            }
            validateBinding(
                    new Binding(proof.token().wdtId(), proof.token().wdtKey(), proof.wdtLength()),
                    token);
            synchronized (this) {
                resolve(proof.token());
            }
        } catch (RuntimeException | Error failure) {
            invalidateFailedProof(proof.token(), token, failure);
            throw failure;
        }
    }

    private void invalidateFailedProof(Scope scope, Cancellation token, Throwable failure) {
        if (!token.isCanceled() && !wrappedCancellation(failure)) {
            synchronized (this) {
                invalidate(scope);
            }
        }
    }

    private static boolean wrappedCancellation(Throwable failure) {
        // Standard wrappers around a pure cancellation chain only; never walk arbitrary causes.
        return WorldSourceRefusals.cleanCancellation(failure) != null;
    }

    /** Metadata only: no capture, stat, source access or preparation. */
    synchronized boolean isKnown(Scope scope) {
        return !closed
                && cleanupFailure == null
                && scope != null
                && scope.owner == owner
                && (current != null && current.token().equals(scope)
                        || replacement != null && replacement.token().equals(scope));
    }

    synchronized int retainedProofs() {
        return (current == null ? 0 : 1) + (replacement == null ? 0 : 1);
    }

    synchronized int retainedMaidBytes() {
        return retainedProofs() * 131_584;
    }

    CompletableFuture<Void> closeAsync() {
        CancelLane active;
        boolean finish;
        var liquidOwners = new ArrayList<WorldTerrainLiquids.Owner>(2);
        synchronized (this) {
            if (closed) {
                return drained.copy();
            }
            closed = true;
            current = null;
            replacement = null;
            active = cancelLane;
            finish = !working.get() && liquidCredits.isEmpty();
            for (var credit : liquidCredits) {
                credit.closing = true;
                if (credit.asset != null) {
                    liquidOwners.add(credit.asset);
                }
            }
        }
        Throwable requestFailure = null;
        try {
            if (active != null) {
                active.request();
            }
        } catch (RuntimeException | Error failure) {
            requestFailure = failure;
            throw failure;
        } finally {
            Throwable cleanup = null;
            for (var liquid : liquidOwners) {
                try {
                    liquid.close();
                } catch (RuntimeException | Error error) {
                    if (cleanup == null) {
                        cleanup = error;
                    } else if (cleanup != error) {
                        cleanup.addSuppressed(error);
                    }
                }
            }
            if (cleanup != null && requestFailure != null && cleanup != requestFailure) {
                // Preserve the cancellation-request failure behind the cleanup failure.
                cleanup.addSuppressed(requestFailure);
            }
            if (cleanup instanceof Error fatal) {
                throw fatal;
            }
            if (cleanup instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
        if (finish) {
            Throwable terminal;
            synchronized (this) {
                terminal = cleanupFailure;
            }
            completeDrain(terminal);
        }
        return drained.copy();
    }

    CompletableFuture<Void> whenClosed() {
        return drained.copy();
    }

    synchronized WorldTerrainLiquids liquids() {
        checkOpen();
        requireHealthy();
        if (liquidInspector == null) {
            liquidInspector = new WorldTerrainLiquids(this, borrowedTables, files, build, locale);
        }
        return liquidInspector;
    }

    static final class LiquidCredit {
        private final WorldStreamScopes engine;
        private final Scope scope;
        private WorldTerrainLiquids.Owner asset;
        private boolean closing;

        private LiquidCredit(WorldStreamScopes engine, Scope scope) {
            this.engine = engine;
            this.scope = scope;
        }

        void pin(WorldTerrainLiquids.Owner owner) {
            engine.pinLiquid(this, owner);
        }

        void retired() {
            retired(null, null);
        }

        void retired(Throwable failure, WorldTerrainLiquids.Owner owner) {
            engine.retireLiquid(this, failure, owner);
        }

        boolean isOpen(WorldTerrainLiquids.Owner expected) {
            synchronized (engine) {
                return engine.isKnown(scope)
                        && !closing
                        && asset == expected
                        && engine.liquidCredits.contains(this)
                        && expected.available();
            }
        }
    }

    synchronized LiquidCredit reserveLiquid(Scope scope) {
        requireHealthy();
        resolve(scope);
        if (liquidCredits.size() == 2) {
            throw new WorldTerrainLiquids.Pressure();
        }
        var credit = new LiquidCredit(this, scope);
        liquidCredits.add(credit);
        return credit;
    }

    synchronized void attachLiquid(LiquidCredit credit, WorldTerrainLiquids.Owner owner) {
        if (!liquidCredits.contains(credit) || credit.asset != null) {
            throw new IllegalStateException("Invalid liquid reservation");
        }
        credit.asset = owner;
        if (closed || credit.closing) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    synchronized void publishLiquid(
            LiquidCredit credit, WorldTerrainLiquids.Owner owner, Cancellation external) {
        external.check();
        requireHealthy();
        resolve(credit.scope);
        if (!liquidCredits.contains(credit) || credit.asset != owner || credit.closing) {
            throw new IllegalStateException("Liquid reservation retired");
        }
        owner.publish();
    }

    private synchronized void pinLiquid(LiquidCredit credit, WorldTerrainLiquids.Owner owner) {
        requireHealthy();
        resolve(credit.scope);
        if (!liquidCredits.contains(credit) || credit.asset != owner || credit.closing) {
            throw new IllegalStateException("Liquid owner unavailable");
        }
        owner.pin();
    }

    private void retireLiquid(
            LiquidCredit credit, Throwable failure, WorldTerrainLiquids.Owner owner) {
        boolean finish;
        Throwable terminal;
        synchronized (this) {
            if (failure != null) {
                recordCleanupFailure(failure);
                if (owner != null) {
                    liquidQuarantine.add(owner);
                }
            }
            liquidCredits.remove(credit);
            credit.asset = null;
            finish = closed && !working.get() && liquidCredits.isEmpty();
            terminal = cleanupFailure;
        }
        if (finish) {
            completeDrain(terminal);
        }
    }

    synchronized int liquidCredits() {
        return liquidCredits.size();
    }

    private Proof resolve(Scope scope) {
        checkOpen();
        if (scope == null || scope.owner != owner) {
            throw new IllegalStateException("Foreign scope");
        }
        if (current != null && current.token().equals(scope)) {
            return current;
        }
        if (replacement != null && replacement.token().equals(scope)) {
            return replacement;
        }
        throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SCOPE_RETIRED);
    }

    private void invalidate(Scope scope) {
        if (current != null && current.token().equals(scope)) {
            current = null;
        }
        if (replacement != null && replacement.token().equals(scope)) {
            replacement = null;
        }
    }

    private void checkOpen() {
        if (closed) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
    }

    /** A lookup with index facts only: file id, size and content key, never names. */
    private FileTypes.BuildFile statContent(
            long id, Cancellation token, WorldSourceRefusals.Site unbound) {
        token.check();
        return bound(
                id,
                WorldSourceFutures.await(files.statContent(FileTypes.FileRef.id(id), token)),
                token,
                unbound);
    }

    private static FileTypes.BuildFile bound(
            long id,
            FileTypes.FileStat result,
            Cancellation token,
            WorldSourceRefusals.Site unbound) {
        token.check();
        if (result.state() != FileTypes.FileLookupState.FOUND
                || result.file() == null
                || result.file().fileDataId() != id
                || result.file().contentKey() == null
                || !result.file().contentKey().matches("[0-9a-f]{32}")) {
            throw WorldSourceRefusals.at(unbound);
        }
        return result.file();
    }

    private Source read(long id, int limit, Cancellation token) {
        return read(id, limit, token, false);
    }

    /**
     * A derived source (a building group, a doodad model or skin, an image) from the session's
     * {@link #sources} cache, or read as {@link #read(long, int, Cancellation, boolean)} reads it
     * and kept there. A cached file was bound by a complete read of this build, so a hit refuses
     * only what that read would: a file over this read's {@code limit}.
     */
    private Source readDerived(
            long id, int limit, Cancellation token, boolean classifyUnavailable) {
        token.check();
        long keys = files.keyRevision();
        sources.keyRevision(keys);
        var cached = sources.file(id);
        if (cached != null) {
            if (cached.bytes().length > limit) {
                throw WorldSourceRefusals.at(
                        classifyUnavailable
                                ? WorldSourceRefusals.Site.SOURCE_UNAVAILABLE
                                : WorldSourceRefusals.Site.SOURCE_INPUT);
            }
            return new Source(cached.contentKey(), cached.bytes(), cached.declaredLength(), limit);
        }
        var source = read(id, limit, token, classifyUnavailable);
        sources.putFile(
                id,
                new WorldSourceCache.File(source.key(), source.bytes(), source.declaredLength),
                keys);
        return source;
    }

    private static final Set<FileTypes.FileReadState> UNAVAILABLE_READS =
            EnumSet.of(
                    FileTypes.FileReadState.PROTECTED,
                    FileTypes.FileReadState.WRONG_KEY,
                    FileTypes.FileReadState.UNKNOWN_FILE,
                    FileTypes.FileReadState.NOT_IN_LOCALE,
                    FileTypes.FileReadState.NOT_IN_ENCODING,
                    FileTypes.FileReadState.MISSING,
                    FileTypes.FileReadState.TOO_LARGE);

    private Source read(long id, int limit, Cancellation token, boolean classifyUnavailable) {
        var unavailable =
                classifyUnavailable
                        ? WorldSourceRefusals.Site.SOURCE_UNAVAILABLE
                        : WorldSourceRefusals.Site.SOURCE_INPUT;
        if (classifyUnavailable) {
            token.check();
            var looked =
                    WorldSourceFutures.await(files.statContent(FileTypes.FileRef.id(id), token));
            token.check();
            if (looked.state() != FileTypes.FileLookupState.FOUND) {
                throw WorldSourceRefusals.at(unavailable); // missing from this build/locale
            }
        }
        // Index facts only: a source read binds file id, size and content key, never names or
        // a sniffed format, so it skips the listfile lookups and the first-sight format sniff.
        var before = statContent(id, token, WorldSourceRefusals.Site.SOURCE_INPUT);
        if (before.size() != null && before.size() > limit) {
            throw WorldSourceRefusals.at(unavailable);
        }
        var opened = WorldSourceFutures.await(files.openRead(FileTypes.FileRef.id(id), token));
        if (opened.stream() == null) {
            if (opened.state() == FileTypes.FileReadState.COMPLETE) {
                throw new IllegalStateException("Complete source has no stream");
            }
            if (classifyUnavailable && opened.state() == FileTypes.FileReadState.NEEDS_NETWORK) {
                // Only the network has it now: left out, and asked for again on a later pass.
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_PENDING);
            }
            throw WorldSourceRefusals.at(
                    UNAVAILABLE_READS.contains(opened.state())
                            ? unavailable
                            : WorldSourceRefusals.Site.SOURCE_INPUT);
        }
        try (var stream = opened.stream()) {
            final long observedLength = stream.length();
            if (before.size() != null && before.size() != observedLength) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
            }
            if (observedLength > limit
                    || UNAVAILABLE_READS.contains(opened.state())
                    || opened.state() == FileTypes.FileReadState.COMPLETE
                            && !stream.holes().isEmpty()) {
                // Over the cap, protected/keyless, or encrypted holes in a complete stream.
                throw WorldSourceRefusals.at(unavailable);
            }
            if (opened.state() != FileTypes.FileReadState.COMPLETE
                    || !stream.holes().isEmpty()
                    || observedLength < 12) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
            }
            var bytes = ByteBuffer.allocate(Math.toIntExact(observedLength));
            while (bytes.hasRemaining()) {
                token.check();
                int position = bytes.position();
                int read = WorldSourceFutures.await(stream.read(bytes, token));
                if (read <= 0) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
                }
                if (read != bytes.position() - position || bytes.limit() != bytes.capacity()) {
                    throw new IllegalStateException("Source stream progress differs");
                }
            }
            token.check();
            if (bytes.position() != observedLength || bytes.limit() != bytes.capacity()) {
                throw new IllegalStateException("Source buffer extent differs");
            }
            var eof = ByteBuffer.allocate(1);
            int extra = WorldSourceFutures.await(stream.read(eof, token));
            token.check();
            if (eof.limit() != eof.capacity()) {
                throw new IllegalStateException("Source EOF buffer extent differs");
            }
            if (extra > 0 || eof.position() != 0) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
            }
            if (extra != -1) {
                throw new IllegalStateException("Source EOF result differs");
            }
            var after = statContent(id, token, WorldSourceRefusals.Site.SOURCE_BINDING);
            // Download/local metadata enrichment is allowed; only consumed source identity binds.
            if (before.fileDataId() != after.fileDataId()
                    || !before.contentKey().equals(after.contentKey())
                    || after.size() != null && after.size() != observedLength) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
            }
            return new Source(before.contentKey(), bytes.array(), before.size(), limit);
        }
    }

    private String digest(WorldMapProofs.Binding map, String key) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF("vimi-world-stream-scope-v1");
                WorldMapProofs.profileBuild(build, locale);
                for (String value :
                        List.of(build.product(), build.buildConfig(), build.cdnConfig(), locale)) {
                    out.writeUTF(value);
                }
                out.writeBoolean(build.region() != null);
                if (build.region() != null) {
                    out.writeUTF(build.region());
                }
                out.writeLong(map.mapId());
                out.writeLong(map.wdtId());
                out.writeUTF(map.identity());
                out.writeUTF(key);
            }
            return sha(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long[] references(WorldFormats.Tile tile) {
        return new long[] {
            tile.rootId(),
            tile.mapTextureId(),
            tile.minimapId(),
            tile.obj0Id(),
            tile.obj1Id(),
            tile.tex0Id(),
            tile.lodId(),
            tile.mapTextureNId()
        };
    }
}
