package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Service-owned terrain qualification. Generic written resources never establish source authority.
 */
final class WorldPreparedTerrain {
    static final long MAX_RECEIPT_BYTES = 2048L + 64L * 512;

    /**
     * Written outputs alive at once: the World stream's 25 residents (a 5 x 5 neighborhood), its
     * staged tile, one pending tile and one retiring (issue #173).
     */
    static final int MAX_OUTPUTS = 28;

    static final long MAX_OUTPUT_RECEIPT_BYTES = MAX_OUTPUTS * MAX_RECEIPT_BYTES;

    enum Outcome {
        TEXTURED,
        HEIGHT_FALLBACK
    }

    enum Fallback {
        NONE,
        QUALIFIED_SOURCE_TERMINAL,
        QUALIFIED_BINDING_REJECTED
    }

    interface Output<R extends AutoCloseable> {
        /**
         * Own and close all unreturned allocations locally; never advertise inside this callback.
         */
        R writeUnadvertised(Borrow borrow, Cancellation cancel);
    }

    /** Private construction and synchronous use only; neither buffers nor this view may escape. */
    static final class Borrow {
        private TerrainScene.Tile tile;
        private TerrainTextureBinding texture;
        private WrittenTile<?> owner;
        private final long worker = Thread.currentThread().threadId();
        private boolean active = true;

        private Borrow(
                WrittenTile<?> owner, TerrainScene.Tile tile, TerrainTextureBinding texture) {
            this.owner = owner;
            this.tile = tile;
            this.texture = texture;
        }

        private void check() {
            if (!active || worker != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired terrain borrow");
            }
        }

        TerrainScene.Tile tile() {
            check();
            return tile;
        }

        WorldFormats.Tile recorded() {
            check();
            return owner.geometry.recorded();
        }

        TerrainTextureBinding texture() {
            check();
            return texture;
        }

        WorldStreamScopes.AdmissionMetadata metadata() {
            check();
            return owner.metadata;
        }

        WorldTerrainTextureSource.FileBinding root() {
            check();
            return owner.root;
        }

        String geometrySha256() {
            check();
            return owner.geometrySha256;
        }

        WorldTerrainTextureSource.Receipt material() {
            check();
            return owner.receipt;
        }

        Outcome outcome() {
            check();
            return owner.outcome;
        }

        Fallback fallback() {
            check();
            return owner.fallback;
        }

        WorldTerrainTextureSource.State materialState() {
            check();
            return owner.materialState;
        }

        WorldTerrainTextureSource.Reason terminalReason() {
            check();
            return owner.terminalReason;
        }

        int supportedChunks() {
            check();
            return owner.supportedChunks;
        }

        WorldFormats.AreaMetadata areas() {
            check();
            return owner.areas;
        }

        private void expire() {
            active = false;
            tile = null;
            texture = null;
            owner = null;
        }
    }

    /** One base geometry pin and at most one base atlas lease, moved through residency. */
    static final class WrittenTile<R extends AutoCloseable> implements AutoCloseable {
        private WorldPreparedTerrain source;
        private final Object sourceIdentity;
        private WorldStreamScopes.Scope scope;
        private WorldGeometryCache.Prepared geometry;
        private TerrainAtlasPool.Lease atlas;
        private R written;
        private WorldStreamScopes.AdmissionMetadata metadata;
        private WorldTerrainTextureSource.FileBinding root;
        private WorldFormats.AreaMetadata areas;
        private String geometrySha256;
        private WorldTerrainTextureSource.Receipt receipt;
        private TerrainTextureBinding.Metadata texture;
        private WorldTerrainTextureSource.State materialState;
        private WorldTerrainTextureSource.Reason terminalReason;
        private int supportedChunks;
        private Outcome outcome = Outcome.HEIGHT_FALLBACK;
        private Fallback fallback = Fallback.QUALIFIED_SOURCE_TERMINAL;
        private boolean constructing = true, closed;
        private int callbacks;

        private WrittenTile(WorldPreparedTerrain source, WorldStreamScopes.Scope scope) {
            this.source = source;
            this.sourceIdentity = source.identity;
            this.scope = scope;
        }

        synchronized boolean isOpen() {
            return !closed && source != null && !source.invalidated && source.scopes.isKnown(scope);
        }

        <T extends AutoCloseable> T withWaterJoin(
                WorldPreparedTerrain expected,
                java.util.function.BiFunction<WorldStreamScopes.Scope, Borrow, T> use) {
            if (sourceIdentity != expected.identity) {
                throw new IllegalArgumentException("Foreign terrain source");
            }
            expected.requireHealthy();
            synchronized (this) {
                check();
            }
            var result = new Object[1];
            try {
                withPayload(borrow -> result[0] = use.apply(scope, borrow));
                expected.requireHealthy();
                synchronized (this) {
                    check();
                }
                @SuppressWarnings("unchecked")
                T value = (T) result[0];
                return value;
            } catch (RuntimeException | Error primary) {
                if (result[0] instanceof AutoCloseable unreturned) {
                    try {
                        unreturned.close();
                    } catch (Exception | Error cleanup) {
                        if (primary != cleanup) {
                            primary.addSuppressed(cleanup);
                        }
                        throw new IllegalStateException("Water join cleanup failed", primary);
                    }
                }
                throw primary;
            }
        }

        private synchronized void check() {
            if (source != null) {
                source.scopes.requireHealthy();
            }
            if (!isOpen()) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
            }
        }

        private void attachGeometry(WorldGeometryCache.Prepared value) {
            synchronized (this) {
                if (!closed) {
                    geometry = value;
                    return;
                }
            }
            value.close();
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }

        private synchronized void attachReceipt(WorldTerrainTextureSource.Receipt value) {
            check();
            receipt = value;
        }

        private void attachAtlas(TerrainAtlasPool.Lease value) {
            synchronized (this) {
                if (!closed) {
                    atlas = value;
                    return;
                }
            }
            value.close();
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }

        private void attachWritten(R value) {
            synchronized (this) {
                if (!closed) {
                    written = value;
                    return;
                }
            }
            closeResource(value);
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }

        /** File-only shell; a later closed bridge must MOVE its capability before advertisement. */
        synchronized R written() {
            check();
            return written;
        }

        /** Synchronous independently pinned ownership move; callback and cleanup run off FX. */
        void withWrittenResource(Consumer<R> use) {
            Objects.requireNonNull(use);
            R resource;
            synchronized (this) {
                check();
                if (constructing || callbacks != 0 || written == null) {
                    throw new IllegalStateException("Terrain resource callback unavailable");
                }
                callbacks++;
                resource = written;
            }
            Throwable primary = null;
            try {
                use.accept(resource);
            } catch (RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                synchronized (this) {
                    callbacks--;
                }
                try {
                    retire();
                } catch (RuntimeException | Error failure) {
                    if (primary == null) {
                        throw failure;
                    }
                    if (primary != failure) {
                        primary.addSuppressed(failure);
                    }
                }
            }
        }

        private synchronized long receiptBytes() {
            return receipt == null ? 0 : receipt.metadataBytes();
        }

        void withPayload(Consumer<Borrow> use) {
            Objects.requireNonNull(use);
            WorldGeometryCache.Prepared pinnedGeometry;
            TerrainAtlasPool.Lease pinnedAtlas;
            synchronized (this) {
                check();
                if (callbacks != 0) {
                    throw new IllegalStateException("Terrain borrow busy");
                }
                callbacks++;
                pinnedGeometry = geometry;
                pinnedAtlas = atlas;
            }
            Throwable primary = null;
            try {
                pinnedGeometry.withTile(
                        tile -> {
                            if (pinnedAtlas == null) {
                                useBorrow(tile, null, use);
                            } else {
                                pinnedAtlas.withAtlas(
                                        pixels ->
                                                useBorrow(
                                                        tile,
                                                        TerrainTextureBinding.borrow(
                                                                texture, pixels.pixels()),
                                                        use));
                            }
                        });
            } catch (RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                synchronized (this) {
                    callbacks--;
                }
                try {
                    retire();
                } catch (RuntimeException | Error failure) {
                    if (primary == null) {
                        throw failure;
                    }
                    if (primary != failure) {
                        primary.addSuppressed(failure);
                    }
                }
            }
        }

        private void useBorrow(
                TerrainScene.Tile tile, TerrainTextureBinding pixels, Consumer<Borrow> use) {
            var borrowed = new Borrow(this, tile, pixels);
            try {
                use.accept(borrowed);
            } finally {
                borrowed.expire();
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                closed = true;
            }
            retire();
        }

        private void constructed() {
            synchronized (this) {
                constructing = false;
            }
            retire();
        }

        private void retire() {
            WorldPreparedTerrain owner;
            WorldGeometryCache.Prepared retiredGeometry;
            TerrainAtlasPool.Lease retiredAtlas;
            R retiredWritten;
            synchronized (this) {
                if (!closed || constructing || callbacks != 0 || source == null) {
                    return;
                }
                owner = source;
                source = null;
                scope = null;
                retiredGeometry = geometry;
                retiredAtlas = atlas;
                retiredWritten = written;
                geometry = null;
                atlas = null;
                written = null;
                metadata = null;
                root = null;
                areas = null;
                geometrySha256 = null;
                receipt = null;
                texture = null;
            }
            Throwable failure = null;
            try {
                closeResource(retiredWritten);
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            try {
                if (retiredAtlas != null) {
                    retiredAtlas.close();
                }
            } catch (RuntimeException | Error error) {
                failure = append(failure, error);
            }
            try {
                if (retiredGeometry != null) {
                    retiredGeometry.close();
                }
            } catch (RuntimeException | Error error) {
                failure = append(failure, error);
            }
            owner.release(this, failure);
            rethrow(failure);
        }
    }

    /** Engine closes a failed returned aggregate BEFORE releasing its source admission. */
    private static final class AdmissionResult<R extends AutoCloseable> implements AutoCloseable {
        private final WrittenTile<R> tile;

        private AdmissionResult(WrittenTile<R> tile) {
            this.tile = tile;
        }

        @Override
        public void close() {
            tile.close();
            tile.constructed();
        }
    }

    private final WorldService world;
    private final WorldStreamScopes scopes;
    private final Object identity = new Object();
    private final WorldTerrainTextureSource materials;
    private final ArrayList<WrittenTile<?>> outputs = new ArrayList<>();
    private final CompletableFuture<Void> outputsDrained = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private volatile boolean invalidated;
    private volatile boolean closed;
    private Throwable cleanupFailure;

    /** Only WorldService supplies its own cache and exact configured helper. */
    WorldPreparedTerrain(
            WorldService world, WorldStreamScopes scopes, WorldTerrainTextureSource materials) {
        this.world = Objects.requireNonNull(world);
        this.scopes = Objects.requireNonNull(scopes);
        this.materials = Objects.requireNonNull(materials);
    }

    <T extends AutoCloseable> T withWaterJoin(
            WorldTerrainStreaming.WrittenTile<?> terrain,
            java.util.function.BiFunction<WorldStreamScopes.Scope, Borrow, T> use) {
        return WorldTerrainStreaming.withWaterJoin(terrain, this, use);
    }

    WorldTerrainLiquids waterSource() {
        checkOpen();
        return scopes.liquids();
    }

    void requireHealthy() {
        scopes.requireHealthy();
        synchronized (this) {
            if (cleanupFailure != null) {
                throw new IllegalStateException("Terrain source cleanup failed", cleanupFailure);
            }
        }
        checkOpen();
    }

    WorldStreamScopes.Scope createScope(WorldService.MapInfo comparison, Cancellation cancel) {
        checkOpen();
        return scopes.create(comparison, cancel);
    }

    long geometryReleaseRevision() {
        return world.geometryReleaseRevision();
    }

    AutoCloseable onGeometryRelease(Runnable listener) {
        return world.onGeometryRelease(listener);
    }

    void activate(WorldStreamScopes.Scope scope) {
        checkOpen();
        scopes.activate(scope);
    }

    /** One serialized BASIC_EXTERIOR source operation for a recorded tile; owns no output. */
    WorldExteriorObjects.TileObjects exteriorObjects(
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation cancel,
            WorldExteriorObjects.ImageDetail detail) {
        checkOpen();
        return WorldExteriorObjects.prepare(scopes, scope, tile, cancel, detail);
    }

    void invalidate() {
        invalidated = true;
    }

    private void checkOpen() {
        if (invalidated) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
    }

    private synchronized <R extends AutoCloseable> WrittenTile<R> reserve(
            WorldStreamScopes.Scope scope) {
        checkOpen();
        if (outputs.size() >= MAX_OUTPUTS) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OUTPUT_CREDIT);
        }
        var value = new WrittenTile<R>(this, Objects.requireNonNull(scope));
        outputs.add(value);
        return value;
    }

    <R extends AutoCloseable> WrittenTile<R> prepareAndWrite(
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile selector,
            Cancellation cancel,
            Output<R> writer) {
        Objects.requireNonNull(writer);
        var owned = this.<R>reserve(scope);
        boolean transferred = false;
        Throwable primary = null;
        try {
            var result =
                    scopes.withRecordedTile(
                            scope,
                            selector,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            cancel,
                            admission -> prepareInsideAdmission(admission, owned, writer));
            owned.check();
            transferred = true;
            return result.tile;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!transferred) {
                owned.close();
            }
            try {
                owned.constructed();
            } catch (RuntimeException | Error failure) {
                if (primary == null) {
                    throw failure;
                }
                if (primary != failure) {
                    primary.addSuppressed(failure);
                }
            }
        }
    }

    private <R extends AutoCloseable> AdmissionResult<R> prepareInsideAdmission(
            WorldStreamScopes.TileAdmission admission, WrittenTile<R> owned, Output<R> writer) {
        boolean returned = false;
        Throwable primary = null;
        try {
            owned.check();
            owned.metadata = admission.metadata();
            owned.attachGeometry(world.prepareGeometry(admission));
            owned.root = owned.geometry.rootBinding();
            owned.areas = owned.geometry.areas();
            owned.geometrySha256 = owned.geometry.fingerprint();
            prepareMaterial(admission, owned);
            owned.withPayload(
                    borrow ->
                            owned.attachWritten(
                                    Objects.requireNonNull(
                                            writer.writeUnadvertised(
                                                    borrow, admission.cancellation()),
                                            "Missing unadvertised output")));
            var result = new AdmissionResult<>(owned);
            returned = true;
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!returned) {
                owned.close();
                try {
                    owned.constructed();
                } catch (RuntimeException | Error failure) {
                    if (primary == null) {
                        throw failure;
                    }
                    if (primary != failure) {
                        primary.addSuppressed(failure);
                    }
                }
            }
        }
    }

    private void prepareMaterial(WorldStreamScopes.TileAdmission admission, WrittenTile<?> owned) {
        var namespace = owned.metadata;
        var tile = admission.recorded();
        if (!materials.matchesNamespace(namespace.build(), namespace.locale())) {
            throw new IllegalStateException("Configured material namespace differs");
        }
        var map = new WorldService.MapInfo(namespace.mapId(), null, null, 0, namespace.wdtId());
        // Reuse the geometry's checked root parse and fingerprint instead of a second read.
        var root = owned.geometry.materialRoot();
        var operation =
                root != null && root.binding().fileId() == tile.rootId()
                        ? materials.readOwned(
                                map, List.of(tile), admission.cancellation(), p -> {}, root)
                        : materials.readOwned(
                                map, List.of(tile), admission.cancellation(), p -> {});
        WorldTerrainTextureSource.BatchResult batch = null;
        try {
            Throwable materialFailure = null;
            try {
                batch = operation.awaitResult();
            } catch (RuntimeException | Error failure) {
                materialFailure = failure;
                throw failure;
            } finally {
                try {
                    operation.awaitDrained();
                } catch (RuntimeException | Error failure) {
                    if (materialFailure == null) {
                        throw failure;
                    }
                    if (materialFailure != failure) {
                        materialFailure.addSuppressed(failure);
                    }
                }
            }
            admission.observeMaterial(operation);
            owned.check();
            if (batch.tiles().size() != 1 || !batch.tiles().getFirst().tile().equals(tile)) {
                throw new IllegalStateException("Material selection differs");
            }
            var result = batch.tiles().getFirst();
            owned.materialState = result.state();
            owned.supportedChunks = result.supportedChunks();
            if (result.state() == WorldTerrainTextureSource.State.POOL_BUSY) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MATERIAL_POOL);
            }
            if (result.state() == WorldTerrainTextureSource.State.UNAVAILABLE) {
                if (result.issues().isEmpty()) {
                    throw new IllegalStateException("Missing terminal material reason");
                }
                owned.terminalReason = result.issues().getFirst().reason();
                if (owned.terminalReason == WorldTerrainTextureSource.Reason.CHANGED
                        || owned.terminalReason == WorldTerrainTextureSource.Reason.POOL_BUSY
                        || owned.terminalReason == WorldTerrainTextureSource.Reason.UNSUPPORTED) {
                    throw new IllegalStateException("Nonterminal material capability failure");
                }
                return;
            }
            var receipt = Objects.requireNonNull(result.receipt());
            if (!namespace.build().equals(receipt.build())
                    || !namespace.locale().equals(receipt.locale())
                    || namespace.mapId() != receipt.mapId()
                    || !receipt.tile().equals(tile)
                    || receipt.wdt().fileId() != namespace.wdtId()
                    || !receipt.wdt().contentKey().equals(namespace.wdtKey())
                    || receipt.wdt().length() != namespace.wdtLength()
                    || receipt.root().fileId() != owned.root.fileId()
                    || !receipt.root().contentKey().equals(owned.root.contentKey())
                    || receipt.root().length() != owned.root.length()
                    || receipt.textures().fileId() != tile.tex0Id()) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.MATERIAL_BINDING);
            }
            // With a prepared root both sides come from the geometry cache's single walk, so
            // this holds by construction; the cache asserts (enabled in tests) that the grid and
            // the mesh of that walk fingerprint alike, so a parser divergence still fails there.
            if (!receipt.geometrySha256().equals(owned.geometry.fingerprint())) {
                throw new IllegalStateException(
                        "Material geometry disagrees with the same root source");
            }
            profileReceipt(receipt);
            owned.attachReceipt(receipt);
            owned.attachAtlas(batch.retainAtlas(tile.x(), tile.y()).orElseThrow());
            try {
                owned.geometry.withTile(
                        nativeTile ->
                                owned.atlas.withAtlas(
                                        atlas -> {
                                            owned.texture =
                                                    TerrainTextureBinding.qualify(
                                                            0,
                                                            nativeTile,
                                                            atlas,
                                                            receipt.geometrySha256(),
                                                            new TerrainTextureBinding.Sources(
                                                                    receipt.root().fileId(),
                                                                            receipt.root()
                                                                                    .contentKey(),
                                                                    receipt.textures().fileId(),
                                                                            receipt.textures()
                                                                                    .contentKey()),
                                                            admission.cancellation()::isCanceled);
                                            TerrainTextureBinding.validatePixels(
                                                    TerrainTextureBinding.borrow(
                                                            owned.texture, atlas.pixels()),
                                                    admission.cancellation()::isCanceled);
                                        }));
            } catch (IllegalArgumentException unsupportedBinding) {
                owned.texture = null;
                owned.atlas.close();
                owned.atlas = null;
                owned.fallback = Fallback.QUALIFIED_BINDING_REJECTED;
                return;
            }
            owned.outcome = Outcome.TEXTURED;
            owned.fallback = Fallback.NONE;
        } finally {
            if (batch != null) {
                batch.close();
            }
        }
    }

    /** Conservative charge per issued owner, even when several alias the same pool receipt. */
    synchronized long retainedReceiptBytes() {
        long total = 0;
        for (var output : outputs) {
            total += output.receiptBytes();
        }
        if (total > MAX_OUTPUT_RECEIPT_BYTES) {
            throw new IllegalStateException("Issued receipt metadata bound");
        }
        return total;
    }

    private static void profileReceipt(WorldTerrainTextureSource.Receipt receipt) {
        if (receipt.diffuse().size() > 64
                || receipt.metadataBytes() > MAX_RECEIPT_BYTES
                || receipt.cacheKey() != null && !hex(receipt.cacheKey(), 64)
                || !hex(receipt.geometrySha256(), 64)
                || receipt.build().product().length() > 32
                || receipt.build().buildConfig().length() != 32
                || receipt.build().cdnConfig().length() != 32
                || receipt.build().region() != null && receipt.build().region().length() > 32
                || receipt.locale().length() > 16) {
            throw new IllegalStateException("Material receipt metadata profile");
        }
        for (var mip : receipt.diffuse()) {
            if (!hex(mip.file().contentKey(), 32)
                    || mip.encoding().length() > 16
                    || !switch (mip.encoding()) {
                        case "PALETTED", "BC1", "BC2", "BC3", "BGRA8" -> true;
                        default -> false;
                    }) {
                throw new IllegalStateException("Material mip metadata profile");
            }
        }
    }

    private static boolean hex(String text, int length) {
        if (text == null || text.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private void release(WrittenTile<?> value, Throwable failure) {
        boolean complete;
        Throwable terminal;
        synchronized (this) {
            cleanupFailure = append(cleanupFailure, failure);
            outputs.remove(value);
            complete = closed && outputs.isEmpty();
            terminal = cleanupFailure;
        }
        if (complete) {
            completeOutputs(terminal);
        }
    }

    private void completeOutputs(Throwable failure) {
        if (failure == null) {
            outputsDrained.complete(null);
        } else {
            outputsDrained.completeExceptionally(failure);
        }
    }

    CompletableFuture<Void> closeAsync() {
        List<WrittenTile<?>> retired;
        synchronized (this) {
            if (closed) {
                return drained.copy();
            }
            invalidated = true;
            closed = true;
            retired = List.copyOf(outputs);
        }
        Thread.startVirtualThread(
                () -> {
                    CompletableFuture<Void> scopeDrain;
                    try {
                        // Request source cancellation before any arbitrary returned-R cleanup.
                        scopeDrain = scopes.closeAsync();
                    } catch (RuntimeException | Error startFailure) {
                        // Failed cleanup is not evidence that other Source owners drained.
                        scopeDrain =
                                CompletableFuture.allOf(
                                        scopes.whenClosed(),
                                        CompletableFuture.failedFuture(startFailure));
                    }
                    for (var value : retired) {
                        try {
                            value.close();
                        } catch (RuntimeException | Error recorded) {
                            // WrittenTile.retire records this in release BEFORE rethrowing.
                            // Continue closing the other outputs; outputsDrained reports it once.
                        }
                    }
                    boolean empty;
                    Throwable terminal;
                    synchronized (this) {
                        empty = outputs.isEmpty();
                        terminal = cleanupFailure;
                    }
                    if (empty) {
                        completeOutputs(terminal);
                    }
                    var pending = CompletableFuture.allOf(scopeDrain, outputsDrained);
                    pending.whenComplete(
                            (ignored, error) -> {
                                if (error != null) {
                                    drained.completeExceptionally(error);
                                } else {
                                    drained.complete(null);
                                }
                            });
                });
        return drained.copy();
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (next != null && first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void closeResource(AutoCloseable value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Terrain output cleanup", failure);
        }
    }
}
