package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
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
    static final CapturedRowsTypes.ReadLimits READ_LIMITS =
            new CapturedRowsTypes.ReadLimits(256 << 10, 128 << 10, 64 << 10, 128, 128, 64 << 10);

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

    private record MapBinding(long mapId, long wdtId, String identity, String sourceStamp) {}

    private record MapContext(
            CapturedRowsTypes.Receipt receipt,
            TableTypes.SchemaResult schema,
            int column,
            String layout,
            String stamp) {}

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
            MapBinding map,
            long wdtLength,
            Long wdtDeclaredLength,
            int wdtSourceLimit,
            int[] maid,
            BitSet present) {}

    private final BuildTables tables;
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
        this.tables = Objects.requireNonNull(tables);
        this.files = Objects.requireNonNull(files);
        build = Objects.requireNonNull(tables.build());
        locale = tables.locale();
        profileBuild(build, locale);
        if (!Objects.equals(build, files.build()) || !Objects.equals(locale, files.locale())) {
            throw new IllegalArgumentException("Source build/locale mismatch");
        }
        WowLocale.parse(locale);
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
        try (var registration = external.onCancel(operationCancellation::request)) {
            var token = lifetimeChild;
            operationCancellation.check();
            token.check();
            MapBinding map = map(requested.id(), token);
            synchronized (this) {
                replacement = null;
            }
            Maid maid = readMaid(map.wdtId(), token);
            // Recheck the qualified selected row and WDT identity before committing a proof.
            if (!revalidateMap(map, token)
                    || !maid.key().equals(stat(map.wdtId(), token).contentKey())) {
                throw new IllegalStateException("Scope source changed");
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
        } finally {
            end();
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
            Thread.startVirtualThread(
                    () -> {
                        try {
                            child.cancel();
                        } finally {
                            done.complete(null);
                        }
                    });
        }

        synchronized void check() {
            if (requested) {
                throw new java.util.concurrent.CancellationException();
            }
            child.check();
        }

        void sealAndDrain() {
            synchronized (this) {
                sealed = true;
                if (!requested) {
                    done.complete(null);
                }
            }
            done.join();
        }
    }

    private void begin() {
        if (!working.compareAndSet(false, true)) {
            throw new IllegalStateException("Source preparation busy");
        }
        boolean rejected;
        synchronized (this) {
            rejected = closed;
            if (!rejected) {
                cancelLane = new CancelLane();
                lifetimeChild = cancelLane.child;
            }
        }
        if (rejected) {
            working.set(false);
            drained.complete(null);
            throw new IllegalStateException("Scope source closed");
        }
    }

    private void end() {
        // Source worker waits for the single off-caller cancellation dispatch to drain.
        cancelLane.sealAndDrain();
        boolean finish;
        synchronized (this) {
            lifetimeChild = null;
            cancelLane = null;
            working.set(false);
            finish = closed;
        }
        if (finish) {
            drained.complete(null);
        }
    }

    synchronized void activate(Scope scope) {
        checkOpen();
        if (working.get()) {
            throw new IllegalStateException("Source preparation busy");
        }
        if (replacement == null || !replacement.token().equals(scope)) {
            throw new IllegalStateException("Stale scope");
        }
        current = replacement;
        replacement = null;
    }

    @SuppressWarnings("try")
    WorldFormats.Tile recorded(Scope scope, WorldFormats.Tile caller, Cancellation external) {
        begin();
        var operationCancellation = cancelLane;
        try (var registration = external.onCancel(operationCancellation::request)) {
            var token = lifetimeChild;
            operationCancellation.check();
            Proof proof;
            synchronized (this) {
                proof = resolve(scope);
            }
            try {
                if (!revalidateMap(proof.map(), token)
                        || !scope.wdtKey().equals(stat(scope.wdtId(), token).contentKey())) {
                    throw new IllegalStateException("Scope source changed");
                }
            } catch (RuntimeException failure) {
                if (!token.isCanceled()) {
                    synchronized (this) {
                        invalidate(scope);
                    }
                }
                throw failure;
            }
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
        } finally {
            end();
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

        /** Rechecks a private cache's existing actual-read proof; never constructs such a proof. */
        boolean reuseRecordedRoot(String key, long length) {
            check();
            if (reserved || input != null) {
                throw new IllegalStateException("Input credit busy");
            }
            var fresh = engine.stat(recorded.rootId(), token);
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
                    throw new IllegalStateException("Source changed");
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
                        throw new IllegalStateException("Source changed");
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
            for (var binding : admission.bindings) {
                validateBinding(binding, token);
            }
            validateProof(proof, token);
            synchronized (this) {
                operationCancellation.check();
                resolve(scope);
                R transferred = result;
                result = null;
                return transferred;
            }
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Throwable cleanup = primary;
            try {
                if (admission != null) {
                    cleanup = closePreserving(admission::expire, cleanup);
                }
                if (result != null) {
                    cleanup = closePreserving(result, cleanup);
                }
            } finally {
                end();
            }
            if (primary == null && cleanup != null) {
                if (cleanup instanceof Error error) {
                    throw error;
                }
                if (cleanup instanceof RuntimeException failure) {
                    throw failure;
                }
                throw new IllegalStateException("Output cleanup failed", cleanup);
            }
        }
    }

    private static Throwable closePreserving(AutoCloseable owner, Throwable primary) {
        try {
            owner.close();
        } catch (Exception | Error failure) {
            if (primary == null) {
                return failure;
            }
            if (failure != primary) {
                primary.addSuppressed(failure);
            }
        }
        return primary;
    }

    private void validateBinding(Binding binding, Cancellation token) {
        token.check();
        var fresh = stat(binding.id(), token);
        if (!binding.key().equals(fresh.contentKey())
                || fresh.size() != null && fresh.size() != binding.length()) {
            throw new IllegalStateException("Consumed source changed");
        }
    }

    private void validateProof(Proof proof, Cancellation token) {
        if (!revalidateMap(proof.map(), token)) {
            throw new IllegalStateException("Map source changed");
        }
        validateBinding(
                new Binding(proof.token().wdtId(), proof.token().wdtKey(), proof.wdtLength()),
                token);
        synchronized (this) {
            resolve(proof.token());
        }
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
            drained.complete(null);
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
        throw new IllegalStateException("Stale scope");
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
            throw new IllegalStateException("Scope source closed");
        }
    }

    private CapturedRows openMap(Cancellation token) {
        var capture = tables.capturePreparedLocal(TableTypes.Ref.forName("Map"), token).join();
        if (capture.rows() == null) {
            throw new UnsupportedOperationException("Prepared native Map capture unavailable");
        }
        return capture.rows();
    }

    private MapContext qualifyMap(CapturedRows rows, Cancellation token) {
        token.check();
        var receipt = rows.receipt();
        requireEntry(receipt.table());
        if (!receipt.build().equals(build)
                || !receipt.locale().equals(locale)
                || receipt.rowCount() > 10_000
                || receipt.completion().excludedEncryptedSections() != 0
                || receipt.completion().excludedPhysicalRecords() != 0
                || receipt.completion().excludedCopyRecords() != 0) {
            throw new IllegalStateException("Unqualified Map capture scope");
        }
        validateNative(receipt);
        var schema =
                new TableTypes.SchemaResult(receipt.table(), receipt.columns(), receipt.schema());
        int column = WorldService.column(schema, "WdtFileDataID", true);
        if (column < 0) {
            throw new IllegalStateException("Unqualified Map WDT field");
        }
        String layout = mapLayout(schema);
        return new MapContext(
                receipt, schema, column, layout, sourceStamp(receipt, column, layout));
    }

    private boolean revalidateMap(MapBinding binding, Cancellation token) {
        try (var rows = openMap(token)) {
            var context = qualifyMap(rows, token);
            probeEof(rows, context.receipt(), token);
            return binding.sourceStamp().equals(context.stamp());
        }
    }

    private static void probeEof(
            CapturedRows rows, CapturedRowsTypes.Receipt receipt, Cancellation token) {
        token.check();
        var page = rows.readOrdinalPage(receipt.rowCount(), 1, READ_LIMITS, token).join();
        token.check();
        if (page.state() != CapturedRowsTypes.ReadState.READ
                || !page.identity().equals(receipt.graphIdentity())
                || page.firstOrdinal() != receipt.rowCount()
                || !page.rows().isEmpty()
                || !page.cellIssues().isEmpty()
                || page.nextOrdinal() != null) {
            throw new UnsupportedOperationException(
                    "Fresh Map capture lacks bounded EOF capability");
        }
    }

    private MapBinding map(long requestedId, Cancellation token) {
        try (var rows = openMap(token)) {
            var context = qualifyMap(rows, token);
            var receipt = context.receipt();
            var schema = context.schema();
            int column = context.column();
            String layout = context.layout();
            long ordinal = 0;
            MapBinding found = null;
            do {
                token.check();
                var page = rows.readOrdinalPage(ordinal, 1, READ_LIMITS, token).join();
                if (page.state() == CapturedRowsTypes.ReadState.LIMIT_EXCEEDED) {
                    throw new UnsupportedOperationException(
                            "Map source exceeds bounded hot scope profile");
                }
                token.check();
                int expectedRows = (int) Math.min(1, receipt.rowCount() - ordinal);
                long expectedNext = ordinal + expectedRows;
                Long next = expectedNext < receipt.rowCount() ? expectedNext : null;
                if (page.state() != CapturedRowsTypes.ReadState.READ
                        || !page.identity().equals(receipt.graphIdentity())
                        || page.firstOrdinal() != ordinal
                        || page.rows().size() != expectedRows
                        || !Objects.equals(page.nextOrdinal(), next)) {
                    throw new IllegalStateException("Noncontiguous Map capture page");
                }
                for (var row : page.rows()) {
                    if (row.ordinal() != ordinal || row.ordinal() >= receipt.rowCount()) {
                        throw new IllegalStateException("Invalid Map row ordinal");
                    }
                    if (row.row().id() != requestedId) {
                        continue;
                    }
                    if (found != null) {
                        throw new IllegalStateException("Duplicate Map ID");
                    }
                    if (row.row().textIssues() == null
                            || row.row().textIssues().stream()
                                    .anyMatch(issue -> issue.columnIndex() == column)
                            || page.cellIssues().stream()
                                    .anyMatch(
                                            issue ->
                                                    issue.columnIndex() < 0
                                                            || issue.columnIndex() == column)) {
                        throw new IllegalStateException("Unqualified Map numeric issue channel");
                    }
                    Long wdt = WorldService.number(schema, row.row(), "WdtFileDataID");
                    if (wdt == null || wdt <= 0 || wdt > 0xffff_ffffL) {
                        throw new IllegalStateException("Missing Map WDT reference");
                    }
                    String binding =
                            receipt.table().fileDataId()
                                    + ":"
                                    + receipt.table().contentKey()
                                    + ":"
                                    + layout
                                    + ":"
                                    + receipt.graphIdentity()
                                    + ":"
                                    + receipt.definitionSha256()
                                    + ":"
                                    + receipt.blockIdentity()
                                    + ":"
                                    + receipt.issueChannelEvidence().sourceIdentitySha256()
                                    + ":"
                                    + row.ordinal()
                                    + ":"
                                    + requestedId
                                    + ":"
                                    + column
                                    + ":"
                                    + wdt
                                    + ":"
                                    + context.stamp();
                    found =
                            new MapBinding(
                                    requestedId,
                                    wdt,
                                    sha(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                    context.stamp());
                }
                if (page.nextOrdinal() == null) {
                    if (ordinal + page.rows().size() != receipt.rowCount()) {
                        throw new IllegalStateException("Truncated Map capture");
                    }
                    break;
                }
                ordinal = page.nextOrdinal();
            } while (true);
            if (found == null) {
                throw new IllegalStateException("Unknown Map ID");
            }
            probeEof(rows, receipt, token);
            return found;
        }
    }

    private static void validateNative(CapturedRowsTypes.Receipt r) {
        profileMetadata(r);
        var e = r.issueChannelEvidence();
        if (e.state() != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                || !e.graphIdentity().equals(r.graphIdentity())
                || !e.resolvedBlockSha256().equals(r.blockIdentity())
                || !e.readerOptionsSha256().equals(r.readerOptions())
                || !e.actualColumnsSha256().equals(NativeIssueIdentity.columns(r.columns()))) {
            throw new IllegalStateException("Map capture lacks matching native issue proof");
        }
        for (var locale : new String[] {null, r.locale()}) {
            for (var encoding : new String[] {null, r.encodingScope()}) {
                byte[] tuple =
                        NativeIssueIdentity.tuple(
                                GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                                e.policyId(),
                                e.producerId(),
                                locale,
                                r.table().contentKey(),
                                r.blockIdentity(),
                                r.readerOptions(),
                                r.readerRevision(),
                                r.rowEncoding(),
                                encoding);
                if (tuple.length > 4096) {
                    throw new IllegalStateException("Map capture identity exceeds bound");
                }
                if (r.sourceIdentity().equals("native-tuple-hex=" + HexFormat.of().formatHex(tuple))
                        && e.sourceIdentitySha256().equals(sha(tuple))) {
                    return;
                }
            }
        }
        throw new IllegalStateException("Map native issue identity differs");
    }

    private static void profileBuild(BuildRef build, String locale) {
        profileString(build.product(), 32, false);
        profileString(build.buildConfig(), 32, false);
        profileString(build.cdnConfig(), 32, false);
        profileString(build.region(), 32, true);
        profileString(locale, 16, false);
    }

    // Bound all variable inputs BEFORE allocating canonical identity encodings or tuple scratch.
    private static void profileMetadata(CapturedRowsTypes.Receipt r) {
        profileBuild(r.build(), r.locale());
        if (r.columns().size() > 128
                || r.schema().columns().size() > 128
                || r.schema().layoutHashes().size() > 32
                || r.header().columns().size() > 128
                || r.sections().size() > 128
                || r.holeKeys().size() > 128
                || r.effectiveKeys().size() > 128) {
            throw new UnsupportedOperationException("Map metadata shape exceeds hot profile");
        }
        long bytes = 1024;
        bytes +=
                profileString(r.header().tableHash(), 16, false)
                        + profileString(r.header().layoutHash(), 16, false)
                        + profileString(r.header().format(), 16, false)
                        + profileString(r.completion().scope(), 64, false);
        if (r.table().counts() == null) {
            throw new UnsupportedOperationException("Missing Map counts");
        }
        bytes +=
                profileString(r.table().counts().format(), 16, false)
                        + profileString(r.table().counts().layoutHash(), 16, false);
        for (var section : r.sections()) {
            bytes += 64 + profileString(section.key(), 64, false);
        }
        for (var key : r.holeKeys()) {
            bytes += 32 + profileString(key, 64, false);
        }
        for (var key : r.effectiveKeys()) {
            bytes +=
                    64
                            + profileString(key.name(), 64, false)
                            + profileString(key.fingerprint(), 64, true);
        }

        bytes += profileString(r.sourceIdentity(), 8256, false);
        bytes += profileString(r.definitionSha256(), 64, false);
        bytes += profileString(r.graphIdentity(), 32, false);
        bytes += profileString(r.blockIdentity(), 64, false);
        bytes += profileString(r.readerOptions(), 64, false);
        bytes += profileString(r.readerRevision(), 128, false);
        bytes += profileString(r.rowEncoding(), 64, false);
        bytes += profileString(r.encodingScope(), 256, true);
        bytes += profileString(r.schema().comment(), 1024, true);
        for (var layout : r.schema().layoutHashes()) {
            bytes += profileString(layout, 8, false);
        }
        for (var column : r.columns()) {
            bytes +=
                    64
                            + profileString(column.name(), 128, false)
                            + profileString(column.kind(), 32, false);
            if (column.interpretation() != null) {
                bytes +=
                        64
                                + profileString(column.interpretation().policy(), 128, false)
                                + profileString(column.interpretation().warning(), 256, false);
            }
            if (bytes > 64 << 10) {
                throw new UnsupportedOperationException("Map metadata exceeds hot profile");
            }
        }
        for (var column : r.schema().columns()) {
            bytes +=
                    128
                            + profileString(column.name(), 128, false)
                            + profileString(column.columnComment(), 256, true)
                            + profileString(column.fieldComment(), 256, true);
            if (column.foreignKey() != null) {
                bytes +=
                        profileString(column.foreignKey().table(), 128, false)
                                + profileString(column.foreignKey().column(), 128, false);
            }
            if (bytes > 64 << 10) {
                throw new UnsupportedOperationException("Map metadata exceeds hot profile");
            }
        }
        if (bytes > 64 << 10) {
            throw new UnsupportedOperationException("Map metadata exceeds hot profile");
        }
        if (!r.header().columns().equals(r.columns())) {
            throw new IllegalStateException("Map header columns differ");
        }
        var e = r.issueChannelEvidence();
        long tuple = 64;
        for (var value :
                new String[] {
                    GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                    e.policyId(),
                    e.producerId(),
                    r.locale(),
                    r.table().contentKey(),
                    r.blockIdentity(),
                    r.readerOptions(),
                    r.readerRevision(),
                    r.rowEncoding(),
                    r.encodingScope()
                }) {
            tuple += 5 + profileString(value, 256, true);
        }
        if (tuple > 4096) {
            throw new UnsupportedOperationException("Map source tuple exceeds hot profile");
        }
    }

    private static long profileString(String value, int maximumChars, boolean nullable) {
        if (value == null) {
            if (nullable) {
                return 0;
            }
            throw new UnsupportedOperationException("Missing hot source metadata");
        }
        if (value.length() > maximumChars) {
            throw new UnsupportedOperationException("Hot source metadata scalar exceeds profile");
        }
        return 16L + 4L * value.length();
    }

    // Canonical v1 stamp streams into SHA-256; no full-schema serialization buffer is retained.
    private static String sourceStamp(CapturedRowsTypes.Receipt r, int column, String layout) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var out =
                    new DataOutputStream(
                            new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
                out.writeUTF("vimi-world-map-source-stamp-v1");
                var build = r.build();
                for (var value :
                        new String[] {
                            build.product(),
                            build.buildConfig(),
                            build.cdnConfig(),
                            build.region(),
                            r.locale()
                        }) {
                    text(out, value);
                }
                var table = r.table();
                out.writeLong(table.fileDataId());
                text(out, table.name());
                out.writeBoolean(table.checked());
                text(out, table.state().name());
                text(out, table.contentKey());
                var counts = table.counts();
                out.writeLong(counts.declaredRows());
                out.writeLong(counts.readableRows());
                out.writeInt(counts.excludedSections());
                out.writeLong(counts.excludedRecords());
                text(out, counts.format());
                text(out, counts.layoutHash());
                for (var value :
                        new String[] {
                            r.definitionSha256(),
                            r.blockIdentity(),
                            r.graphIdentity(),
                            r.sourceIdentity(),
                            r.readerOptions(),
                            r.readerRevision(),
                            r.rowEncoding(),
                            r.encodingScope(),
                            r.retention().name(),
                            r.membership().name()
                        }) {
                    text(out, value);
                }
                out.writeInt(column);
                text(out, layout);
                var schema = r.schema();
                text(out, schema.match().name());
                text(out, schema.locale().name());
                text(out, schema.comment());
                out.writeInt(schema.layoutHashes().size());
                for (var value : schema.layoutHashes()) {
                    text(out, value);
                }
                out.writeInt(schema.columns().size());
                for (var field : schema.columns()) {
                    text(out, field.name());
                    text(out, field.kind() == null ? null : field.kind().name());
                    number(out, field.bits());
                    bool(out, field.unsigned());
                    out.writeInt(field.arrayLength());
                    out.writeBoolean(field.foreignKey() != null);
                    if (field.foreignKey() != null) {
                        text(out, field.foreignKey().table());
                        text(out, field.foreignKey().column());
                    }
                    text(out, field.id() == null ? null : field.id().name());
                    text(out, field.relation() == null ? null : field.relation().name());
                    out.writeBoolean(field.verifiedName());
                    text(out, field.locale() == null ? null : field.locale().name());
                    text(out, field.columnComment());
                    text(out, field.fieldComment());
                }
                var e = r.issueChannelEvidence();
                for (var value :
                        new String[] {
                            e.state().name(),
                            e.policyId(),
                            e.producerId(),
                            e.graphIdentity(),
                            e.sourceIdentitySha256(),
                            e.resolvedBlockSha256(),
                            e.readerOptionsSha256(),
                            e.actualColumnsSha256()
                        }) {
                    text(out, value);
                }
                var header = r.header();
                out.writeLong(header.schemaVersion());
                text(out, header.tableHash());
                text(out, header.layoutHash());
                out.writeLong(header.declaredRecords());
                out.writeLong(header.declaredCopyRecords());
                out.writeInt(header.recordSize());
                text(out, header.format());
                // Header/receipt columns equality and native actual-columns digest were verified
                // first.
                text(out, e.actualColumnsSha256());
                out.writeLong(r.rowCount());
                var completion = r.completion();
                out.writeLong(completion.declaredRecords());
                out.writeLong(completion.physicalRows());
                out.writeLong(completion.copyRows());
                out.writeInt(completion.excludedEncryptedSections());
                out.writeLong(completion.excludedPhysicalRecords());
                out.writeLong(completion.excludedCopyRecords());
                text(out, completion.scope());
                out.writeLong(completion.consumedBytes());
                out.writeLong(completion.textIssueCount());
                out.writeInt(r.sections().size());
                for (var section : r.sections()) {
                    out.writeInt(section.index());
                    out.writeInt(section.recordCount());
                    out.writeInt(section.copyCount());
                    text(out, section.key());
                    out.writeBoolean(section.isEncrypted());
                    out.writeInt(section.protectedCopyCount());
                    out.writeInt(section.unresolvedCopyCount());
                    out.writeBoolean(section.isDecrypted());
                }
                out.writeInt(r.holeKeys().size());
                for (var key : r.holeKeys()) {
                    text(out, key);
                }
                out.writeInt(r.effectiveKeys().size());
                for (var key : r.effectiveKeys()) {
                    text(out, key.name());
                    text(out, key.fingerprint());
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeUTF(value);
        }
    }

    private static void number(DataOutputStream out, Integer value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeInt(value);
        }
    }

    private static void bool(DataOutputStream out, Boolean value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeBoolean(value);
        }
    }

    private static String mapLayout(TableTypes.SchemaResult schema) {
        if (schema.table().counts() == null
                || schema.table().counts().layoutHash() == null
                || !schema.table().counts().layoutHash().matches("[0-9a-fA-F]{8}")
                || !schema.schema().layoutHashes().contains(schema.table().counts().layoutHash())) {
            throw new IllegalStateException("Unbound Map layout");
        }
        var field =
                schema.schema().columns().stream()
                        .filter(c -> c.name().equals("WdtFileDataID"))
                        .toList();
        if (field.size() != 1) {
            throw new IllegalStateException("Ambiguous Map WDT field");
        }
        var column = field.getFirst();
        return schema.table().counts().layoutHash()
                + ":"
                + column.kind()
                + ":"
                + column.bits()
                + ":"
                + column.unsigned()
                + ":"
                + column.verifiedName()
                + ":"
                + column.arrayLength();
    }

    private static void requireEntry(TableTypes.Entry entry) {
        if (entry == null
                || !entry.checked()
                || entry.state() != TableTypes.State.READY
                || !"Map".equals(entry.name())
                || entry.contentKey() == null
                || !entry.contentKey().matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Unqualified Map source");
        }
    }

    private FileTypes.BuildFile stat(long id, Cancellation token) {
        var result = files.stat(FileTypes.FileRef.id(id), token).join();
        if (result.state() != FileTypes.FileLookupState.FOUND
                || result.file() == null
                || result.file().fileDataId() != id
                || result.file().contentKey() == null
                || !result.file().contentKey().matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Unbound source");
        }
        return result.file();
    }

    private Source read(long id, int limit, Cancellation token) {
        var before = stat(id, token);
        if (before.size() != null && before.size() > limit) {
            throw new IllegalStateException("Source exceeds bound");
        }
        var opened = files.openRead(FileTypes.FileRef.id(id), token).join();
        if (opened.stream() == null) {
            throw new IllegalStateException("Source unavailable");
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE
                    || !stream.holes().isEmpty()
                    || stream.length() < 12
                    || stream.length() > limit
                    || before.size() != null && before.size() != stream.length()) {
                throw new IllegalStateException("Incomplete source");
            }
            var bytes = ByteBuffer.allocate((int) stream.length());
            while (bytes.hasRemaining()) {
                token.check();
                if (stream.read(bytes, token).join() <= 0) {
                    throw new IllegalStateException("Truncated source");
                }
            }
            token.check();
            var after = stat(id, token);
            // Download/local metadata enrichment is allowed; only consumed source identity binds.
            if (before.fileDataId() != after.fileDataId()
                    || !before.contentKey().equals(after.contentKey())
                    || after.size() != null && after.size() != bytes.capacity()) {
                throw new IllegalStateException("Source changed during read");
            }
            return new Source(before.contentKey(), bytes.array(), before.size(), limit);
        }
    }

    private String digest(MapBinding map, String key) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF("vimi-world-stream-scope-v1");
                profileBuild(build, locale);
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
