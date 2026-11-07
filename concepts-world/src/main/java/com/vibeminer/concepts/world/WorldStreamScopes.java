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

    /** Largest read-ahead bytes any operation held unconsumed (#371); diagnostics and tests. */
    final java.util.concurrent.atomic.AtomicLong prefetchPeakHeldBytes =
            new java.util.concurrent.atomic.AtomicLong();

    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean closed;
    private long generation;
    private Proof current, replacement;

    WorldStreamScopes(BuildTables tables, BuildFiles files) {
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
                                    stat(map.wdtId(), token, WorldSourceRefusals.Site.MAP_BINDING)
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

    enum UseProfile {
        TERRAIN_BASIC(67, 0, 0, 0, 0, 0, 0),
        BASIC_WMO(11, 1, 1 << 10, 1, 32 << 10, 6, 64 << 10),
        LIQUID_INSPECTION(11, 0, 0, 0, 0, 0, 0),
        /**
         * Per-tile outdoor exterior set: WDT, OBJ0, the capped derived WMO reads (64 opaque and 32
         * cutout or blended images) and the capped BASIC doodad reads (M2 models, their first SKIN
         * and their images).
         */
        BASIC_EXTERIOR(
                2 + 24 + 256 + 64 + 32 + 96 + 96 + 96,
                24,
                1 << 20,
                256,
                com.vibeminer.formats.world.WmoGroupSpans.MAX_BYTES,
                // Each image may be read twice: once when admitted, once at its allotted level.
                2 * (64 + 32),
                1 << 20,
                96,
                2 << 20,
                96,
                1 << 20,
                2 * 96,
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
                    engine.stat(recorded.rootId(), token, WorldSourceRefusals.Site.SOURCE_BINDING);
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
            if (derivedReads[ordinal] >= profile.derivedReads(kind)) {
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
                    profile.derivedReads(kind) - derivedReads[kind.ordinal()]);
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
                    source = engine.read(id, limit, token, classifyUnavailable);
                    if (started != 0L) {
                        WorldStageTimings.stop("source.read." + profile.name(), started);
                        WorldStageTimings.add(
                                "source.readBytes." + profile.name(), source.bytes().length);
                    }
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
     * read the consumer needs next is never evicted. {@link #cancelAll} withdraws every hint and
     * cancels every started read, for a root that stops early. Each read has its own cancellation,
     * also canceled with the operation's; {@link #close} cancels, joins and drops everything before
     * the operation ends.
     */
    static final class Prefetcher {
        static final int READS = 4, WINDOW = 8, QUEUE = 256, HELD_BYTES = 8 << 20;

        /** Read-ahead on or off for new hints; off reads serially as before. Tests compare both. */
        static volatile boolean enabled = true;

        private record Key(long id, DerivedKind kind) {}

        private record Hint(Key key, int limit, long order) {}

        private static final class Started {
            final long order;
            final CompletableFuture<Source> future = new CompletableFuture<>();
            final Cancellation cancel = new Cancellation();
            long held;

            Started(long order) {
                this.order = order;
            }
        }

        private final WorldStreamScopes engine;
        private final Cancellation.Registration link;
        private final boolean classifyUnavailable;
        private final java.util.ArrayDeque<Hint> queue = new java.util.ArrayDeque<>();
        private final java.util.LinkedHashMap<Key, Started> started =
                new java.util.LinkedHashMap<>();
        private long nextOrder, heldBytes, peakHeldBytes;
        private int running;
        private boolean closed;

        private Prefetcher(
                WorldStreamScopes engine, Cancellation operation, boolean classifyUnavailable) {
            this.engine = engine;
            this.classifyUnavailable = classifyUnavailable;
            this.link = operation.onCancel(this::cancelReads);
        }

        synchronized void hint(long id, DerivedKind kind, int limit, int remaining) {
            var key = new Key(id, kind);
            if (closed || started.containsKey(key) || queue.size() >= QUEUE) {
                return;
            }
            int pending = 0;
            for (var hint : queue) {
                if (hint.key().equals(key)) {
                    return;
                }
                if (hint.key().kind() == kind) {
                    pending++;
                }
            }
            for (var other : started.keySet()) {
                if (other.kind() == kind) {
                    pending++;
                }
            }
            // Never read ahead more of a kind than the operation may still consume.
            if (pending >= remaining) {
                return;
            }
            queue.add(new Hint(key, limit, nextOrder++));
            pump();
        }

        /**
         * The started read of exactly this hint, now owned by the caller, or null when the caller
         * reads it itself (never hinted, or hinted and not yet started). Every earlier hint is
         * dropped: the consumer has passed it. The operation's cancel hook no longer reaches the
         * returned read, so the caller links its cancellation for as long as it waits.
         */
        Started take(long id, DerivedKind kind) {
            var key = new Key(id, kind);
            var dropped = new ArrayList<Started>();
            Started result = null;
            synchronized (this) {
                long order = -1;
                var mine = started.get(key);
                if (mine != null) {
                    order = mine.order;
                } else {
                    for (var hint : queue) {
                        if (hint.key().equals(key)) {
                            order = hint.order();
                            break;
                        }
                    }
                }
                if (order < 0) {
                    return null;
                }
                long passed = order;
                queue.removeIf(hint -> hint.order() <= passed);
                for (var entry = started.entrySet().iterator(); entry.hasNext(); ) {
                    var next = entry.next().getValue();
                    if (next.order < passed) {
                        dropped.add(next);
                        heldBytes -= next.held;
                        entry.remove();
                    }
                }
                if (mine != null) {
                    started.remove(key);
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
                dropped = new ArrayList<>(started.values());
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
                running++;
                var read = new Started(hint.order());
                started.put(hint.key(), read);
                Thread.ofVirtual()
                        .name("world-source-prefetch")
                        .start(
                                () -> {
                                    Source source = null;
                                    Throwable failure = null;
                                    try {
                                        source =
                                                engine.read(
                                                        hint.key().id(),
                                                        hint.limit(),
                                                        read.cancel,
                                                        classifyUnavailable);
                                    } catch (Throwable thrown) {
                                        failure = thrown;
                                    }
                                    finished(hint.key(), read, source);
                                    if (failure != null) {
                                        read.future.completeExceptionally(failure);
                                    } else {
                                        read.future.complete(source);
                                    }
                                });
            }
        }

        private synchronized void finished(Key key, Started read, Source source) {
            running--;
            if (source != null && started.get(key) == read) {
                read.held = source.bytes().length;
                heldBytes += read.held;
                peakHeldBytes = Math.max(peakHeldBytes, heldBytes);
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
                dropped = new ArrayList<>(started.values());
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
                                    files.stat(FileTypes.FileRef.id(observed.fileId()), token));
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
                WorldSourceFutures.await(files.stat(FileTypes.FileRef.id(binding.id()), token));
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

    private FileTypes.BuildFile stat(long id, Cancellation token) {
        return stat(id, token, WorldSourceRefusals.Site.SOURCE_INPUT);
    }

    private FileTypes.BuildFile stat(
            long id, Cancellation token, WorldSourceRefusals.Site unbound) {
        token.check();
        return bound(
                id,
                WorldSourceFutures.await(files.stat(FileTypes.FileRef.id(id), token)),
                token,
                unbound);
    }

    /** As {@link #stat} with index facts only: file id, size and content key, never names. */
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
            var looked = WorldSourceFutures.await(files.stat(FileTypes.FileRef.id(id), token));
            token.check();
            if (looked.state() != FileTypes.FileLookupState.FOUND) {
                throw WorldSourceRefusals.at(unavailable); // missing from this build/locale
            }
        }
        var before = stat(id, token);
        if (before.size() != null && before.size() > limit) {
            throw WorldSourceRefusals.at(unavailable);
        }
        var opened = WorldSourceFutures.await(files.openRead(FileTypes.FileRef.id(id), token));
        if (opened.stream() == null) {
            if (opened.state() == FileTypes.FileReadState.COMPLETE) {
                throw new IllegalStateException("Complete source has no stream");
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
            var after = stat(id, token, WorldSourceRefusals.Site.SOURCE_BINDING);
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
