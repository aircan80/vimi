package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.world.WdtReferenceFacts;
import com.vibeminer.formats.world.WdtSceneFacts;
import com.vibeminer.rendering.world.GlobalInteriorLimits;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Actual Map/WDT declaration authority only. No terrain, WMO body, GPU or public capability. */
final class WorldGlobalScopes {
    static final int INPUT_LIMIT = 1 << 20;
    static final String PROFILE = "GLOBAL_ZERO_DECLARATION_V1";

    static final class GlobalScope {
        private final long owner, generation, mapId, wdtId;
        private final String wdtKey, digest;

        private GlobalScope(long owner, long generation, Facts facts) {
            this.owner = owner;
            this.generation = generation;
            mapId = facts.mapId();
            wdtId = facts.wdtId();
            wdtKey = facts.wdtKey();
            digest = facts.scopeDigest();
        }

        long mapId() {
            return mapId;
        }

        String digest() {
            return digest;
        }
    }

    record Selection(long mapId, int ordinal, long expectedUid) {
        Selection {
            if (mapId < 0 || ordinal != 0 || expectedUid < 0 || expectedUid > 0xffff_ffffL) {
                throw new IllegalArgumentException("Global selection bounds");
            }
        }
    }

    /** Detached descriptions; neither this value nor the recorded root can authorize future IO. */
    record Facts(
            BuildRef build,
            String locale,
            long mapId,
            WorldMapProofs.Authority mapAuthority,
            String scopeDigest,
            long wdtId,
            String wdtKey,
            long consumedLength,
            Long declaredLength,
            int sourceLimit,
            String bodySha256,
            String declarationSha256,
            WdtSceneFacts.GlobalObject declaration,
            List<Long> environment) {
        Facts {
            environment = List.copyOf(environment);
        }
    }

    @FunctionalInterface
    interface DeclarationUse<R extends AutoCloseable> {
        R use(Borrow borrowed);
    }

    static final class Borrow {
        private Thread worker = Thread.currentThread();
        private Facts facts;

        private Borrow(Facts facts) {
            this.facts = facts;
        }

        Facts facts() {
            if (Thread.currentThread() != worker || facts == null) {
                throw new IllegalStateException(
                        "Global declaration borrow expired or wrong thread");
            }
            return facts;
        }

        private void expire() {
            facts = null;
            worker = null;
        }
    }

    private record Proof(GlobalScope scope, WorldMapProofs.Binding map, Facts facts) {}

    private static final class Candidate implements AutoCloseable {
        private Proof proof;

        Candidate(Proof proof) {
            this.proof = proof;
        }

        @Override
        public void close() {
            proof = null;
        }
    }

    @FunctionalInterface
    private interface Work<R> {
        R run(Cancellation token);
    }

    @FunctionalInterface
    private interface FinalGate<R> {
        void check(R result);
    }

    private final WorldMapProofs maps;
    private final BuildFiles files;
    private final long owner = new SecureRandom().nextLong();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private long generation;
    private Proof current, replacement;
    private boolean working, closed, inputReserved;
    private Lane active;
    private Throwable cleanupFault;
    private final java.util.ArrayList<PayloadCredit> payloads = new java.util.ArrayList<>(2);
    private final java.util.ArrayList<WorldGlobalAssetFactory.OwnedAsset> quarantine =
            new java.util.ArrayList<>(2);

    WorldGlobalScopes(BuildTables tables, BuildFiles files) {
        maps = new WorldMapProofs(tables, files);
        this.files = files;
    }

    GlobalScope create(WorldService.MapInfo comparison, Cancellation external) {
        Objects.requireNonNull(comparison);
        var result =
                execute(
                        external,
                        token -> {
                            var map = maps.bind(comparison.id(), token);
                            synchronized (this) {
                                healthy();
                                open();
                                replacement =
                                        null; // Retire old pending before constructing a third
                                // proof.
                            }
                            var facts = read(map, token);
                            validate(map, facts, token);
                            GlobalScope scope;
                            synchronized (this) {
                                if (generation == Long.MAX_VALUE) {
                                    throw new IllegalStateException("Global generation exhausted");
                                }
                                scope = new GlobalScope(owner, ++generation, facts);
                            }
                            return new Candidate(new Proof(scope, map, facts));
                        },
                        candidate -> replacement = candidate.proof);
        var scope = result.proof.scope();
        result.close();
        return scope;
    }

    synchronized void activate(GlobalScope scope) {
        foreign(scope);
        healthy();
        open();
        if (working) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_WORKER);
        }
        if (replacement == null || replacement.scope() != scope) {
            throw retired();
        }
        current = replacement;
        replacement = null;
    }

    <R extends AutoCloseable> R withRecordedDeclaration(
            GlobalScope scope, Selection comparison, Cancellation external, DeclarationUse<R> use) {
        return withDeclaration(scope, comparison, external, use, token -> {});
    }

    private <R extends AutoCloseable> R withDeclaration(
            GlobalScope scope,
            Selection comparison,
            Cancellation external,
            DeclarationUse<R> use,
            java.util.function.Consumer<Cancellation> finalSources) {
        Objects.requireNonNull(comparison);
        Objects.requireNonNull(use);
        return execute(
                external,
                token -> {
                    Proof proof;
                    synchronized (this) {
                        proof = resolve(scope);
                    }
                    var expected = proof.facts();
                    if (comparison.mapId() != expected.mapId()
                            || comparison.ordinal() != expected.declaration().ordinal()
                            || comparison.expectedUid() != expected.declaration().uniqueId()) {
                        throw new IllegalArgumentException(
                                "Global selection does not match recorded declaration");
                    }
                    Facts observed;
                    try {
                        validate(proof.map(), expected, token);
                        observed = read(proof.map(), token);
                        if (!observed.scopeDigest().equals(expected.scopeDigest())
                                || observed.consumedLength() != expected.consumedLength()
                                || !observed.declarationSha256()
                                        .equals(expected.declarationSha256())) {
                            throw changed();
                        }
                    } catch (RuntimeException | Error failure) {
                        invalidateFailed(scope, failure);
                        throw failure;
                    }
                    var borrow = new Borrow(observed);
                    R result;
                    try {
                        result = Objects.requireNonNull(use.use(borrow));
                    } finally {
                        borrow.expire();
                    }
                    try {
                        finalSources.accept(token);
                        validate(proof.map(), expected, token);
                        return result;
                    } catch (RuntimeException | Error failure) {
                        invalidateFailed(scope, failure);
                        // execute has not yet received R: this lexical phase still owns it.
                        throw closeFailure(result, failure);
                    }
                },
                result -> resolve(scope));
    }

    enum AssetKind {
        ROOT,
        GROUP,
        IMAGE
    }

    /** Descriptive actual consumption; constructing a tuple never grants ancestry. */
    record AssetReceipt(
            AssetKind kind,
            long id,
            String key,
            long length,
            Long declared,
            int limit,
            String sha256) {}

    @FunctionalInterface
    interface AssetUse<R extends AutoCloseable> {
        R use(AssetAdmission admission);
    }

    @FunctionalInterface
    interface RawUse {
        void use(AssetInput input);
    }

    static final class AssetInput {
        private byte[] raw;
        private final AssetReceipt receipt;
        private final long thread = Thread.currentThread().threadId();

        private AssetInput(byte[] raw, AssetReceipt receipt) {
            this.raw = raw;
            this.receipt = receipt;
        }

        private void check() {
            if (raw == null || Thread.currentThread().threadId() != thread) {
                throw new IllegalStateException("Expired asset input");
            }
        }

        // Internal fixed factory only. Its lexical parser must not retain/mutate these owned bytes.
        byte[] bytes() {
            check();
            return raw;
        }

        AssetReceipt receipt() {
            check();
            return receipt;
        }

        private void expire() {
            raw = null;
        }
    }

    /** Candidate bytes only. The fixed factory alone proves actual parsed root ancestry. */
    static final class AssetAdmission {
        private WorldGlobalScopes engine;
        private Cancellation token;
        private Facts facts;
        private final long thread = Thread.currentThread().threadId();
        private final java.util.ArrayList<AssetReceipt> consumed = new java.util.ArrayList<>();
        private int roots, groups, images;

        private AssetAdmission(WorldGlobalScopes engine, Facts facts, Cancellation token) {
            this.engine = engine;
            this.facts = facts;
            this.token = token;
        }

        private void check() {
            if (engine == null || Thread.currentThread().threadId() != thread) {
                throw new IllegalStateException("Expired global asset admission");
            }
            token.check();
        }

        Facts facts() {
            check();
            return facts;
        }

        Cancellation cancellation() {
            check();
            return token;
        }

        List<AssetReceipt> receipts() {
            check();
            return List.copyOf(consumed);
        }

        void root(RawUse use) {
            check();
            candidate(facts.declaration().reference(), AssetKind.ROOT, use);
        }

        void groupCandidate(long id, RawUse use) {
            candidate(id, AssetKind.GROUP, use);
        }

        void imageCandidate(long id, RawUse use) {
            candidate(id, AssetKind.IMAGE, use);
        }

        private void candidate(long id, AssetKind kind, RawUse use) {
            check();
            Objects.requireNonNull(use);
            if (id <= 0
                    || id > 0xffff_ffffL
                    || id == facts.wdtId()
                    || consumed.size() >= GlobalInteriorLimits.ASSETS
                    || consumed.stream().anyMatch(value -> value.id() == id)) {
                throw new IllegalArgumentException("Global candidate identity/count");
            }
            if (kind == AssetKind.ROOT && ++roots > 1
                    || kind == AssetKind.GROUP && ++groups > GlobalInteriorLimits.GROUPS
                    || kind == AssetKind.IMAGE && ++images > GlobalInteriorLimits.IMAGES) {
                throw new IllegalStateException("Global role credit exhausted");
            }
            if (kind != AssetKind.ROOT && roots != 1) {
                throw new IllegalStateException("Root candidate must precede derived reads");
            }
            engine.readAsset(id, kind, token, use, consumed);
        }

        private void expire() {
            engine = null;
            token = null;
            facts = null;
            consumed.clear();
        }
    }

    /** Fixed G1 source operation; no mesh/image ownership or public facade is admitted here. */
    <R extends AutoCloseable> R withAssetInputs(
            GlobalScope scope, Selection comparison, Cancellation external, AssetUse<R> use) {
        Objects.requireNonNull(use);
        var finalBindings = new java.util.ArrayList<AssetReceipt>(GlobalInteriorLimits.ASSETS);
        return withDeclaration(
                scope,
                comparison,
                external,
                borrow -> {
                    Cancellation token;
                    synchronized (this) {
                        resolve(scope);
                        token = active.child;
                    }
                    var admission = new AssetAdmission(this, borrow.facts(), token);
                    R result = null;
                    try {
                        result = Objects.requireNonNull(use.use(admission));
                        finalBindings.addAll(admission.consumed);
                        return result;
                    } catch (RuntimeException | Error failure) {
                        if (failure instanceof WorldTerrainStreaming.Refusal refused
                                && refused.reason()
                                        == WorldTerrainStreaming.Refusal.Reason
                                                .SOURCE_INVALIDATED) {
                            invalidateFailed(scope, failure);
                        }
                        if (result != null) {
                            throw closeFailure(result, failure);
                        }
                        throw failure;
                    } finally {
                        admission.expire();
                    }
                },
                token -> {
                    try {
                        for (var binding : finalBindings) {
                            var actual =
                                    stat(
                                            binding.id(),
                                            token,
                                            WorldSourceRefusals.Site.GLOBAL_ASSET_BINDING);
                            if (!binding.key().equals(actual.contentKey())
                                    || actual.size() != null && actual.size() != binding.length()) {
                                throw assetChanged();
                            }
                        }
                    } catch (RuntimeException | Error failure) {
                        invalidateFailed(scope, failure);
                        throw failure;
                    } finally {
                        finalBindings.clear();
                    }
                });
    }

    private void readAsset(
            long id, AssetKind kind, Cancellation token, RawUse use, List<AssetReceipt> consumed) {
        int limit =
                switch (kind) {
                    case ROOT -> GlobalInteriorLimits.ROOT_BYTES;
                    case GROUP -> GlobalInteriorLimits.GROUP_BYTES;
                    case IMAGE -> GlobalInteriorLimits.IMAGE_BYTES;
                };
        synchronized (this) {
            if (inputReserved) {
                throw new IllegalStateException("Global input owner already live");
            }
            inputReserved = true;
        }
        FileReadStream stream = null;
        AssetInput borrowed = null;
        Throwable primary = null;
        try {
            var before = stat(id, token, WorldSourceRefusals.Site.SOURCE_INPUT);
            if (before.size() != null && (before.size() < 12 || before.size() > limit)) {
                throw oversize(kind, limit);
            }
            var opened = WorldSourceFutures.await(files.openRead(FileTypes.FileRef.id(id), token));
            stream = opened.stream();
            if (stream == null) {
                if (opened.state() == FileTypes.FileReadState.COMPLETE) {
                    throw new IllegalStateException("Complete asset has no stream");
                }
                throw notRead(kind, opened);
            }
            long length = stream.length();
            if (opened.state() != FileTypes.FileReadState.COMPLETE) {
                throw notRead(kind, opened);
            }
            if (!stream.holes().isEmpty()) {
                // The files layer leaves holes only where an encryption key is unknown.
                throw encrypted(kind.name().toLowerCase(java.util.Locale.ROOT));
            }
            if (length < 12 || length > limit) {
                throw oversize(kind, limit);
            }
            if (before.size() != null && before.size() != length) {
                throw assetChanged();
            }
            var data = ByteBuffer.allocate((int) length);
            while (data.hasRemaining()) {
                token.check();
                int position = data.position();
                int count = WorldSourceFutures.await(stream.read(data, token));
                if (data.limit() != length || count != data.position() - position) {
                    throw new IllegalStateException("Global source read progress differs");
                }
                if (count <= 0) {
                    throw pending(); // The stream stopped early: a fetch failed mid-read.
                }
            }
            token.check();
            if (data.position() != length || data.limit() != length) {
                throw new IllegalStateException("Global source consumption differs");
            }
            var eof = ByteBuffer.allocate(1);
            int extra = WorldSourceFutures.await(stream.read(eof, token));
            token.check();
            if (extra != -1 || eof.position() != 0 || eof.limit() != 1) {
                throw new IllegalStateException("Asset source EOF differs");
            }
            var receipt =
                    new AssetReceipt(
                            kind,
                            id,
                            before.contentKey(),
                            data.capacity(),
                            before.size(),
                            limit,
                            sha(data.array(), 0, data.capacity()));
            borrowed = new AssetInput(data.array(), receipt);
            use.use(borrowed);
            token.check();
            var after = stat(id, token, WorldSourceRefusals.Site.GLOBAL_ASSET_BINDING);
            if (!before.contentKey().equals(after.contentKey())
                    || after.size() != null && after.size() != data.capacity()) {
                throw assetChanged();
            }
            consumed.add(receipt);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (borrowed != null) {
                borrowed.expire();
            }
            try {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (RuntimeException | Error failure) {
                        var fatal = cleanup(primary, failure);
                        synchronized (this) {
                            cleanupFault = fatal;
                            current = null;
                            replacement = null;
                        }
                        throw fatal;
                    }
                }
            } finally {
                synchronized (this) {
                    inputReserved = false;
                }
            }
        }
    }

    private static WorldTerrainStreaming.Refusal assetChanged() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_ASSET_BINDING);
    }

    synchronized void requireHealthy() {
        healthy();
    }

    synchronized Facts descriptiveFacts(GlobalScope scope) {
        return resolve(scope).facts();
    }

    CompletableFuture<Void> whenClosed() {
        return drained.copy();
    }

    synchronized boolean isKnown(GlobalScope scope) {
        return cleanupFault == null
                && !closed
                && scope != null
                && scope.owner == owner
                && (current != null && current.scope() == scope
                        || replacement != null && replacement.scope() == scope);
    }

    synchronized int retainedProofs() {
        return (current == null ? 0 : 1) + (replacement == null ? 0 : 1);
    }

    /** One of exactly two source-owned payload reservations, including construction and pins. */
    static final class PayloadCredit {
        private final WorldGlobalScopes source;
        private final GlobalScope scope;
        private WorldGlobalAssetFactory.OwnedAsset asset;
        private boolean closing;

        private PayloadCredit(WorldGlobalScopes source, GlobalScope scope) {
            this.source = source;
            this.scope = scope;
        }

        boolean isOpen(WorldGlobalAssetFactory.OwnedAsset expected) {
            synchronized (source) {
                return source.isKnown(scope)
                        && !closing
                        && asset == expected
                        && source.payloads.contains(this)
                        && expected.available();
            }
        }

        void pin(WorldGlobalAssetFactory.OwnedAsset expected) {
            source.pinPayload(this, expected);
        }

        void retired() {
            retired(null);
        }

        void retired(Throwable failure) {
            source.retirePayload(this, failure);
        }
    }

    synchronized PayloadCredit reservePayload(GlobalScope scope) {
        resolve(scope);
        if (payloads.size() == 2) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_ASSET_CAPACITY);
        }
        var credit = new PayloadCredit(this, scope);
        payloads.add(credit);
        return credit;
    }

    synchronized void attachPayload(
            PayloadCredit credit, WorldGlobalAssetFactory.OwnedAsset asset) {
        if (!payloads.contains(credit) || credit.asset != null) {
            throw new IllegalStateException("Invalid global payload reservation");
        }
        credit.asset = asset;
        if (credit.closing || closed) {
            throw new CancellationException();
        }
    }

    synchronized void publishPayload(
            PayloadCredit credit, WorldGlobalAssetFactory.OwnedAsset asset) {
        resolve(credit.scope);
        if (!payloads.contains(credit) || credit.asset != asset || credit.closing) {
            throw new IllegalStateException("Global payload reservation retired");
        }
        asset.publish(); // Fixed private metadata only, source -> owner lock order.
    }

    private synchronized void pinPayload(
            PayloadCredit credit, WorldGlobalAssetFactory.OwnedAsset asset) {
        resolve(credit.scope);
        if (!payloads.contains(credit) || credit.asset != asset || credit.closing) {
            throw new IllegalStateException("Global payload unavailable");
        }
        asset.pin(); // No callbacks/IO; never acquire source while holding owner.
    }

    private void retirePayload(PayloadCredit credit, Throwable cleanup) {
        boolean finish;
        Throwable failure;
        synchronized (this) {
            if (cleanup != null) {
                if (credit.asset != null) {
                    quarantine.add(credit.asset);
                }
                if (cleanupFault == null) {
                    cleanupFault = cleanup;
                } else if (cleanupFault != cleanup) {
                    cleanupFault.addSuppressed(cleanup);
                }
            }
            payloads.remove(credit);
            credit.asset = null;
            finish = closed && !working && payloads.isEmpty();
            failure = cleanupFault;
        }
        if (finish) {
            completeDrain(failure);
        }
    }

    synchronized int payloadCredits() {
        return payloads.size();
    }

    CompletableFuture<Void> closeAsync() {
        Lane lane;
        boolean finish;
        Throwable failure;
        java.util.List<WorldGlobalAssetFactory.OwnedAsset> owners;
        synchronized (this) {
            closed = true;
            current = null;
            replacement = null;
            lane = active;
            finish = !working && payloads.isEmpty();
            failure = cleanupFault;
            owners = new java.util.ArrayList<>(2);
            for (var credit : payloads) {
                credit.closing = true;
                if (credit.asset != null) {
                    owners.add(credit.asset);
                }
            }
        }
        try {
            if (lane != null) {
                lane.request();
            }
        } finally {
            Throwable closingFailure = null;
            for (var asset : owners) {
                try {
                    asset.close();
                } catch (RuntimeException | Error cleanup) {
                    if (closingFailure == null) {
                        closingFailure = cleanup;
                    } else if (closingFailure != cleanup) {
                        closingFailure.addSuppressed(cleanup);
                    }
                }
            }
            if (closingFailure instanceof Error fatal) {
                throw fatal;
            }
            if (closingFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
        if (finish) {
            completeDrain(failure);
        }
        return drained.copy();
    }

    private synchronized Lane begin() {
        healthy();
        open();
        if (working) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_WORKER);
        }
        active = new Lane();
        working = true;
        return active;
    }

    /** Owns R until lower callback drain AND the final source/owner gate have completed. */
    private <R extends AutoCloseable> R execute(
            Cancellation external, Work<R> work, FinalGate<R> gate) {
        Objects.requireNonNull(external);
        var lane = begin();
        R result = null;
        Throwable primary = null, sticky = null;
        try {
            Cancellation.Registration registration = null;
            try {
                registration = external.onCancel(lane::request);
                lane.check();
                external.check();
                result = work.run(lane.child);
            } catch (RuntimeException | Error failure) {
                primary =
                        failure instanceof RuntimeException runtime
                                ? WorldSourceRefusals.boundary(runtime)
                                : failure;
            } finally {
                if (registration != null) {
                    registration.close();
                }
            }
            lane.seal();
            try {
                lane.check();
                external.check();
            } catch (RuntimeException | Error failure) {
                if (primary == null) {
                    primary = failure;
                }
            }
            if (primary != null && result != null) {
                var failure = closeOwned(result, primary);
                if (failure != primary) {
                    sticky = failure;
                }
                primary = failure;
                result = null;
            }
            try {
                lane.done.join();
            } catch (RuntimeException | Error failure) {
                primary = cleanup(primary, failure);
                sticky = primary;
            }
            if (primary == null) {
                try {
                    synchronized (this) {
                        external.check();
                        lane.check();
                        healthy();
                        open();
                        gate.check(result);
                    }
                } catch (RuntimeException | Error failure) {
                    primary = failure;
                }
            }
            if (primary != null && result != null) {
                var failure = closeOwned(result, primary);
                if (failure != primary) {
                    sticky = failure;
                }
                primary = failure;
                result = null;
            }
            rethrow(primary);
            return result;
        } finally {
            release(sticky);
        }
    }

    private void release(Throwable sticky) {
        boolean finish;
        Throwable finalFault;
        synchronized (this) {
            if (sticky != null) {
                cleanupFault = sticky;
                current = null;
                replacement = null;
            }
            active = null;
            working = false;
            finish = closed && payloads.isEmpty();
            finalFault = cleanupFault;
        }
        if (finish) {
            completeDrain(finalFault);
        }
    }

    private static final class Lane {
        private final Cancellation child = new Cancellation();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private boolean requested, sealed;

        void request() {
            synchronized (this) {
                if (requested || sealed) {
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
                                done.completeExceptionally(cleanup(null, failure));
                            }
                        });
            } catch (RuntimeException | Error failure) {
                done.completeExceptionally(cleanup(null, failure));
                throw failure;
            }
        }

        synchronized void check() {
            if (requested) {
                throw new CancellationException();
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
    }

    private void validate(WorldMapProofs.Binding map, Facts facts, Cancellation token) {
        if (!maps.revalidate(map, token)) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MAP_BINDING);
        }
        var actual = stat(map.wdtId(), token, WorldSourceRefusals.Site.GLOBAL_WDT_BINDING);
        if (!facts.wdtKey().equals(actual.contentKey())
                || actual.size() != null && actual.size() != facts.consumedLength()) {
            throw changed();
        }
    }

    private Facts read(WorldMapProofs.Binding map, Cancellation token) {
        synchronized (this) {
            if (inputReserved) {
                throw new IllegalStateException("Global input credit already owned");
            }
            inputReserved = true;
        }
        FileReadStream stream = null;
        Throwable primary = null;
        try {
            var before = wdtStat(map.wdtId(), token);
            if (before.size() != null && (before.size() < 12 || before.size() > INPUT_LIMIT)) {
                throw wdtUnavailable();
            }
            var opened =
                    WorldSourceFutures.await(
                            files.openRead(FileTypes.FileRef.id(map.wdtId()), token));
            stream = opened.stream();
            if (stream == null) {
                if (opened.state() == FileTypes.FileReadState.COMPLETE) {
                    throw new IllegalStateException("Complete global source has no stream");
                }
                throw wdtNotRead(opened);
            }
            long length = stream.length();
            if (opened.state() != FileTypes.FileReadState.COMPLETE) {
                throw wdtNotRead(opened);
            }
            if (!stream.holes().isEmpty()) {
                throw wdtEncrypted();
            }
            if (length < 12 || length > INPUT_LIMIT) {
                throw wdtUnavailable();
            }
            if (before.size() != null && before.size() != length) {
                throw changed();
            }
            var bytes = ByteBuffer.allocate((int) length);
            while (bytes.hasRemaining()) {
                token.check();
                int position = bytes.position();
                int count = WorldSourceFutures.await(stream.read(bytes, token));
                if (bytes.limit() != length || count != bytes.position() - position) {
                    throw new IllegalStateException("Global source read progress differs");
                }
                if (count <= 0) {
                    throw pending(); // The stream stopped early: a fetch failed mid-read.
                }
            }
            token.check();
            if (bytes.position() != length || bytes.limit() != length) {
                throw new IllegalStateException("Global source consumption differs");
            }
            var eof = ByteBuffer.allocate(1);
            int extra = WorldSourceFutures.await(stream.read(eof, token));
            token.check();
            if (extra != -1 || eof.position() != 0 || eof.limit() != 1) {
                throw new IllegalStateException("Global source EOF differs");
            }
            var result = parse(map, before.contentKey(), before.size(), bytes.array(), token);
            var after = stat(map.wdtId(), token, WorldSourceRefusals.Site.GLOBAL_WDT_BINDING);
            if (!before.contentKey().equals(after.contentKey())
                    || after.size() != null && after.size() != bytes.capacity()) {
                throw changed();
            }
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (RuntimeException | Error failure) {
                        var fatal = cleanup(primary, failure);
                        synchronized (this) {
                            cleanupFault = fatal;
                            current = null;
                            replacement = null;
                        }
                        throw fatal;
                    }
                }
            } finally {
                synchronized (this) {
                    inputReserved = false;
                }
            }
        }
    }

    private Facts parse(
            WorldMapProofs.Binding map,
            String key,
            Long declared,
            byte[] bytes,
            Cancellation token) {
        var refs = WdtReferenceFacts.scan(bytes, token::isCanceled);
        var scene = WdtSceneFacts.read(bytes, token::isCanceled);
        if (scene.mode() != WdtSceneFacts.Mode.GLOBAL_WMO_ONLY) {
            throw profile(
                    scene.mode() == WdtSceneFacts.Mode.TERRAIN_ONLY
                            ? "this instance is a terrain (ADT) map, not a single interior WMO;"
                                    + " it can only be explored as a map"
                            : "the map is neither terrain-only nor one interior WMO");
        }
        if (!refs.mainPresent()
                || !refs.maidPresent()
                || !refs.modfPresent()
                || refs.nonzeroMainWords() != 0
                || refs.positiveMaidReferences() != 0
                || refs.modfRecords() != 1
                || refs.mphdFlags() != 0x241
                || scene.mode() != WdtSceneFacts.Mode.GLOBAL_WMO_ONLY
                || scene.nameBlockPresent()
                || scene.globalObjects().size() != 1) {
            throw profile("the map declaration is not one plain WMO placement");
        }
        var environment = new ArrayList<Long>(7);
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        String declaration = null;
        while (input.hasRemaining()) {
            token.check();
            count++;
            int tag = input.getInt(), size = input.getInt(), start = input.position();
            int expected =
                    switch (tag) {
                        case 0x4d564552 -> 4;
                        case 0x4d504844 -> 32;
                        case 0x4d41494e -> 32768;
                        case 0x4d414944 -> 131072;
                        case 0x4d4f4446 -> 64;
                        default -> -1;
                    };
            if (size != expected) {
                throw profile("the map file has chunks a WMO-only map does not use");
            }
            if (tag == 0x4d504844) {
                for (int i = 1; i < 8; i++) {
                    environment.add(Integer.toUnsignedLong(input.getInt(start + i * 4)));
                }
            }
            if (tag == 0x4d4f4446) {
                declaration = sha(bytes, start, size);
            }
            input.position(start + size);
        }
        var object = scene.globalObjects().getFirst();
        if (count != 5
                || environment.size() != 7
                || declaration == null
                || object.referenceKind() != WdtSceneFacts.ReferenceKind.FILE_DATA_ID
                || object.reference() == 0
                || object.flags() != 8
                || object.doodadSet() != 0
                || object.nameSet() != 0
                || object.scale() != 1024
                || object.position().x() != 0
                || object.position().y() != 0
                || object.position().z() != 0
                || object.rotation().x() != 0
                || object.rotation().y() != 0
                || object.rotation().z() != 0) {
            throw profile("the interior WMO is placed with a transform, doodad set or name set");
        }
        String body = sha(bytes, 0, bytes.length);
        String digest = scopeDigest(map, key, body);
        return new Facts(
                maps.build(),
                maps.locale(),
                map.mapId(),
                map.authority(),
                digest,
                map.wdtId(),
                key,
                bytes.length,
                declared,
                INPUT_LIMIT,
                body,
                declaration,
                object,
                environment);
    }

    private FileTypes.BuildFile stat(long id, Cancellation token, WorldSourceRefusals.Site site) {
        token.check();
        var value = WorldSourceFutures.await(files.statContent(FileTypes.FileRef.id(id), token));
        token.check();
        if (value.state() != FileTypes.FileLookupState.FOUND
                || value.file() == null
                || value.file().fileDataId() != id
                || value.file().contentKey() == null
                || !value.file().contentKey().matches("[0-9a-f]{32}")) {
            throw WorldSourceRefusals.at(site);
        }
        return value.file();
    }

    private String scopeDigest(WorldMapProofs.Binding map, String key, String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var output =
                    new DataOutputStream(
                            new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            output.writeUTF(PROFILE);
            for (String value :
                    List.of(
                            maps.build().product(),
                            maps.build().buildConfig(),
                            maps.build().cdnConfig(),
                            maps.locale())) {
                output.writeUTF(value);
            }
            output.writeBoolean(maps.build().region() != null);
            if (maps.build().region() != null) {
                output.writeUTF(maps.build().region());
            }
            output.writeLong(map.mapId());
            output.writeLong(map.wdtId());
            output.writeUTF(map.identity());
            output.writeUTF(map.sourceStamp());
            output.writeUTF(key);
            output.writeUTF(body);
            output.flush();
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sha(byte[] bytes, int offset, int length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private synchronized void invalidateFailed(GlobalScope scope, Throwable failure) {
        if (failure instanceof RuntimeException runtime
                && WorldSourceRefusals.boundary(runtime).getClass()
                        == CancellationException.class) {
            return;
        }
        if (current != null && current.scope() == scope) {
            current = null;
        }
        if (replacement != null && replacement.scope() == scope) {
            replacement = null;
        }
    }

    private void foreign(GlobalScope scope) {
        if (scope == null || scope.owner != owner) {
            throw new IllegalStateException("Foreign global scope");
        }
    }

    private Proof resolve(GlobalScope scope) {
        foreign(scope);
        healthy();
        open();
        if (current != null && current.scope() == scope) {
            return current;
        }
        if (replacement != null && replacement.scope() == scope) {
            return replacement;
        }
        throw retired();
    }

    private void healthy() {
        if (cleanupFault != null) {
            throw new IllegalStateException("Global cleanup failed", cleanupFault);
        }
    }

    private void open() {
        if (closed) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
    }

    private void completeDrain(Throwable failure) {
        if (failure == null) {
            drained.complete(null);
        } else {
            drained.completeExceptionally(failure);
        }
    }

    private RuntimeException closeFailure(AutoCloseable result, Throwable primary) {
        var failure = closeOwned(result, primary);
        if (failure != primary) {
            synchronized (this) {
                cleanupFault = failure;
                current = null;
                replacement = null;
            }
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException(failure);
    }

    private static Throwable closeOwned(AutoCloseable result, Throwable primary) {
        try {
            result.close();
            return primary;
        } catch (Exception | Error failure) {
            return cleanup(primary, failure);
        }
    }

    private static IllegalStateException cleanup(Throwable primary, Throwable failure) {
        var fatal =
                new IllegalStateException(
                        "Global operation cleanup failed", primary == null ? failure : primary);
        if (primary != null && primary != failure) {
            fatal.addSuppressed(failure);
        }
        return fatal;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    /** How an unsuccessful source open is reported. */
    enum ReadOutcome {
        /** Needs the network (offline, no CDN route, or the store failed): retrying can help. */
        NETWORK,
        /**
         * Encrypted with a key this client does not have. The files layer reports PARTIAL and
         * PROTECTED only for holes whose key is unknown; retrying does not help.
         */
        ENCRYPTED,
        /** Missing, corrupt, unsupported or otherwise unreadable. */
        UNAVAILABLE
    }

    /** Classifies an open that did not return a complete stream. */
    static ReadOutcome classify(FileTypes.FileReadState state, String code) {
        if (state == null) {
            return ReadOutcome.UNAVAILABLE;
        }
        return switch (state) {
            case NEEDS_NETWORK, STORE_FAILED -> ReadOutcome.NETWORK;
            case UNAVAILABLE ->
                    "CDN_OFFLINE".equals(code) || "OBJECT_ACCESS_NO_CDN_CONTEXT".equals(code)
                            ? ReadOutcome.NETWORK
                            : ReadOutcome.UNAVAILABLE;
            case PARTIAL, PROTECTED, WRONG_KEY -> ReadOutcome.ENCRYPTED;
            default -> ReadOutcome.UNAVAILABLE;
        };
    }

    private static WorldTerrainStreaming.Refusal notRead(
            AssetKind kind, FileTypes.FileOpen opened) {
        return switch (classify(opened.state(), opened.code())) {
            case NETWORK -> pending();
            case ENCRYPTED -> encrypted(kind.name().toLowerCase(java.util.Locale.ROOT));
            case UNAVAILABLE -> unavailable();
        };
    }

    /** Retry later: user-safe fixed text, shown as is. */
    private static WorldTerrainStreaming.Refusal pending() {
        return WorldSourceRefusals.at(
                WorldSourceRefusals.Site.GLOBAL_ASSET_PENDING,
                "some of the interior's files need the network (offline or not downloaded yet);"
                        + " try again when online");
    }

    /**
     * A file this client cannot decrypt. A group with it is skipped under this reason; a root or
     * image refuses the interior with it.
     */
    private static WorldTerrainStreaming.Refusal encrypted(String what) {
        return WorldSourceRefusals.at(
                WorldSourceRefusals.Site.SOURCE_INPUT,
                "a WMO " + what + " file is encrypted and its decryption key is not available");
    }

    private static WorldTerrainStreaming.Refusal oversize(AssetKind kind, int limit) {
        return WorldSourceRefusals.at(
                WorldSourceRefusals.Site.SOURCE_INPUT,
                "a WMO "
                        + kind.name().toLowerCase(java.util.Locale.ROOT)
                        + " file is empty or larger than "
                        + (limit >> 10)
                        + " KiB");
    }

    private static final String WDT_UNAVAILABLE =
            "the map's WDT file is not readable from local client data (missing, encrypted or"
                    + " too large)";

    private FileTypes.BuildFile wdtStat(long id, Cancellation token) {
        try {
            return stat(id, token, WorldSourceRefusals.Site.SOURCE_INPUT);
        } catch (WorldTerrainStreaming.Refusal missing) {
            throw wdtUnavailable();
        }
    }

    private static WorldTerrainStreaming.Refusal wdtNotRead(FileTypes.FileOpen opened) {
        return switch (classify(opened.state(), opened.code())) {
            case NETWORK -> pending();
            case ENCRYPTED -> wdtEncrypted();
            case UNAVAILABLE -> wdtUnavailable();
        };
    }

    private static WorldTerrainStreaming.Refusal wdtEncrypted() {
        return WorldSourceRefusals.at(
                WorldSourceRefusals.Site.SOURCE_INPUT,
                "the map's WDT file is encrypted and its decryption key is not available");
    }

    private static WorldTerrainStreaming.Refusal wdtUnavailable() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT, WDT_UNAVAILABLE);
    }

    private static WorldTerrainStreaming.Refusal unavailable() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
    }

    private static WorldTerrainStreaming.Refusal profile() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_PROFILE);
    }

    private static WorldTerrainStreaming.Refusal profile(String detail) {
        return detail == null
                ? profile()
                : WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_PROFILE, detail);
    }

    private static WorldTerrainStreaming.Refusal changed() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_WDT_BINDING);
    }

    private static WorldTerrainStreaming.Refusal retired() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.SCOPE_RETIRED);
    }
}
