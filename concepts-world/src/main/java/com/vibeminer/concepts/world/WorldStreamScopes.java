package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
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
    private final BuildFiles files;
    private final BuildRef build;
    private final String locale;
    private final long owner;
    private final AtomicBoolean working = new AtomicBoolean();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean closed;
    private long generation;
    private Proof current, replacement;

    WorldStreamScopes(BuildTables tables, BuildFiles files) {
        maps = new WorldMapProofs(tables, files);
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
            working.set(false);
            if (closed) {
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
            finish = closed;
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
        TERRAIN_BASIC,
        BASIC_WMO
    }

    enum DerivedKind {
        WMO_ROOT,
        WMO_GROUP,
        WMO_IMAGE
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
        private int roots, groups, images;

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
            if (bindings.size() >= (profile == UseProfile.TERRAIN_BASIC ? 67 : 11)) {
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

        Input readRecorded(RecordedInput kind) {
            check();
            long id =
                    switch (kind) {
                        case ROOT -> recorded.rootId();
                        case TEX0 -> {
                            if (profile != UseProfile.TERRAIN_BASIC) {
                                throw new IllegalArgumentException("Unused WMO tex0 input");
                            }
                            yield recorded.tex0Id();
                        }
                        case OBJ0 -> {
                            if (profile != UseProfile.BASIC_WMO) {
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
            if (profile != UseProfile.BASIC_WMO) {
                throw new IllegalArgumentException("Wrong derived profile");
            }
            if (reserved || input != null) {
                throw new IllegalStateException("Input credit busy");
            }
            int limit =
                    switch (Objects.requireNonNull(kind)) {
                        case WMO_ROOT -> {
                            if (roots >= 1) {
                                throw new IllegalStateException("WMO root read limit");
                            }
                            roots++;
                            yield 1 << 10;
                        }
                        case WMO_GROUP -> {
                            if (groups >= 1) {
                                throw new IllegalStateException("WMO group read limit");
                            }
                            groups++;
                            yield 32 << 10;
                        }
                        case WMO_IMAGE -> {
                            if (images >= 6) {
                                throw new IllegalStateException("WMO image read limit");
                            }
                            images++;
                            yield 64 << 10;
                        }
                    };
            return readCandidate(id, limit);
        }

        private Input readCandidate(long id, int limit) {
            if (id <= 0 || id > 0xffff_ffffL) {
                throw new IllegalArgumentException("Missing or invalid input ID");
            }
            if (reserved || input != null) {
                throw new IllegalStateException("Input credit busy");
            }
            if (bindings.size() >= (profile == UseProfile.TERRAIN_BASIC ? 67 : 11)) {
                throw new IllegalStateException("Binding limit");
            }
            // Reservation precedes even stat/open, and remains held through actual read drain.
            reserved = true;
            Source source = null;
            try {
                source = engine.read(id, limit, token);
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
            bindings.clear();
            materialObservations = List.of();
            token = null;
            engine = null;
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
            if (finalIds.size() > (profile == UseProfile.TERRAIN_BASIC ? 67 : 11)) {
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
                    var fresh = files.stat(FileTypes.FileRef.id(observed.fileId()), token).join();
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
                    operationCancellation.check();
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
        var lookup = files.stat(FileTypes.FileRef.id(binding.id()), token).join();
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
        // Only standard async wrappers, at most eight links. Never walk arbitrary causes.
        for (int depth = 0; depth < 8; depth++) {
            if (failure instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            if (failure == null
                    || failure.getClass() != java.util.concurrent.CompletionException.class
                            && failure.getClass()
                                    != java.util.concurrent.ExecutionException.class) {
                return false;
            }
            failure = failure.getCause();
        }
        return failure instanceof java.util.concurrent.CancellationException;
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
        synchronized (this) {
            if (closed) {
                return drained.copy();
            }
            closed = true;
            current = null;
            replacement = null;
            active = cancelLane;
            finish = !working.get();
        }
        if (finish) {
            Throwable terminal;
            synchronized (this) {
                terminal = cleanupFailure;
            }
            completeDrain(terminal);
        }
        if (active != null) {
            active.request();
        }
        return drained.copy();
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
        var result = files.stat(FileTypes.FileRef.id(id), token).join();
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
        var before = stat(id, token);
        if (before.size() != null && before.size() > limit) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
        }
        var opened = files.openRead(FileTypes.FileRef.id(id), token).join();
        if (opened.stream() == null) {
            if (opened.state() == FileTypes.FileReadState.COMPLETE) {
                throw new IllegalStateException("Complete source has no stream");
            }
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
        }
        try (var stream = opened.stream()) {
            if (before.size() != null && before.size() != stream.length()) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_BINDING);
            }
            if (opened.state() != FileTypes.FileReadState.COMPLETE
                    || !stream.holes().isEmpty()
                    || stream.length() < 12
                    || stream.length() > limit) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
            }
            var bytes = ByteBuffer.allocate((int) stream.length());
            while (bytes.hasRemaining()) {
                token.check();
                if (stream.read(bytes, token).join() <= 0) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
                }
            }
            token.check();
            var after = stat(id, token, WorldSourceRefusals.Site.SOURCE_BINDING);
            // Download/local metadata enrichment is allowed; only consumed source identity binds.
            if (before.fileDataId() != after.fileDataId()
                    || !before.contentKey().equals(after.contentKey())
                    || after.size() != null && after.size() != bytes.capacity()) {
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
