package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.world.WdtReferenceFacts;
import com.vibeminer.formats.world.WdtSceneFacts;
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

    CompletableFuture<Void> closeAsync() {
        Lane lane;
        boolean finish;
        Throwable failure;
        synchronized (this) {
            closed = true;
            current = null;
            replacement = null;
            lane = active;
            finish = !working;
            failure = cleanupFault;
        }
        if (lane != null) {
            lane.request();
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
            finish = closed;
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
            var before = stat(map.wdtId(), token, WorldSourceRefusals.Site.SOURCE_INPUT);
            if (before.size() != null && (before.size() < 12 || before.size() > INPUT_LIMIT)) {
                throw unavailable();
            }
            var opened = files.openRead(FileTypes.FileRef.id(map.wdtId()), token).join();
            stream = opened.stream();
            if (stream == null) {
                if (opened.state() == FileTypes.FileReadState.COMPLETE) {
                    throw new IllegalStateException("Complete global source has no stream");
                }
                throw unavailable();
            }
            if (opened.state() != FileTypes.FileReadState.COMPLETE
                    || !stream.holes().isEmpty()
                    || stream.length() < 12
                    || stream.length() > INPUT_LIMIT) {
                throw unavailable();
            }
            if (before.size() != null && before.size() != stream.length()) {
                throw changed();
            }
            var bytes = ByteBuffer.allocate((int) stream.length());
            while (bytes.hasRemaining()) {
                token.check();
                if (stream.read(bytes, token).join() <= 0) {
                    throw unavailable();
                }
            }
            token.check();
            if (stream.read(ByteBuffer.allocate(1), token).join() != -1) {
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
            throw profile();
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
                throw profile();
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
            throw profile();
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
        var value = files.stat(FileTypes.FileRef.id(id), token).join();
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

    private static WorldTerrainStreaming.Refusal unavailable() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.SOURCE_INPUT);
    }

    private static WorldTerrainStreaming.Refusal profile() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_PROFILE);
    }

    private static WorldTerrainStreaming.Refusal changed() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_WDT_BINDING);
    }

    private static WorldTerrainStreaming.Refusal retired() {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.SCOPE_RETIRED);
    }
}
