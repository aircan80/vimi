package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Optional actual-source terrain capability owned by its WorldService. Preparation and cleanup
 * belong off FX. Generic writer output and descriptive facts never authenticate packet bytes.
 */
public final class WorldTerrainStreaming {
    public enum Outcome {
        TEXTURED,
        HEIGHT_FALLBACK
    }

    public enum Fallback {
        NONE,
        TERMINAL_MATERIAL_UNAVAILABLE,
        QUALIFIED_BINDING_REJECTED
    }

    public enum MapAuthority {
        COMPLETE_READY,
        OBSERVED_SELECTED_ROW
    }

    public enum DiffuseEncoding {
        PALETTED,
        BC1,
        BC2,
        BC3,
        BGRA8
    }

    /** Detached descriptive tuple; public construction confers no source authority. */
    public record SourceTuple(
            long fileDataId,
            String contentKey,
            long consumedLength,
            Long declaredLength,
            int sourceLimit) {
        public SourceTuple {
            Objects.requireNonNull(contentKey);
            if (fileDataId < 1
                    || fileDataId > 0xffff_ffffL
                    || contentKey.length() != 32
                    || !contentKey.matches("[0-9a-f]{32}")
                    || consumedLength < 12
                    || consumedLength > sourceLimit
                    || sourceLimit != 1 << 20 && sourceLimit != 2 << 20 && sourceLimit != 8 << 20
                    || declaredLength != null && declaredLength != consumedLength) {
                throw new IllegalArgumentException("Descriptive source tuple profile");
            }
        }
    }

    /** Detached bounded descriptor only; not a decoded image or a positive receipt factory. */
    public record Diffuse(
            SourceTuple source,
            int originalSide,
            int mipLevel,
            int mipFlags,
            DiffuseEncoding encoding,
            int alphaDepth,
            int alphaEncoding) {
        public Diffuse {
            Objects.requireNonNull(source);
            Objects.requireNonNull(encoding);
            if (mipLevel < 0
                    || mipLevel > 12
                    || originalSide != 4 << mipLevel
                    || mipFlags < 0
                    || mipFlags > 255
                    || alphaDepth < 0
                    || alphaDepth > 255
                    || alphaEncoding < 0
                    || alphaEncoding > 255) {
                throw new IllegalArgumentException("Descriptive mip profile");
            }
        }
    }

    /** Detached scalar bounds, computed only inside an actual checked geometry borrow. */
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Bounds {
            if (!Float.isFinite(minX)
                    || !Float.isFinite(minY)
                    || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX)
                    || !Float.isFinite(maxY)
                    || !Float.isFinite(maxZ)
                    || minX > maxX
                    || minY > maxY
                    || minZ > maxZ) {
                throw new IllegalArgumentException("Terrain bounds profile");
            }
        }
    }

    /** Private-constructed detached token. No source/service/MAID proof is retained here. */
    public static final class Scope {
        private final WorldStreamScopes.Scope token;

        private Scope(WorldStreamScopes.Scope token) {
            this.token = token;
        }

        public long mapId() {
            return token.mapId();
        }

        public String scopeSha256() {
            return token.digest();
        }
    }

    /**
     * Detached bounded scalars/tuples only. There is no receipt, diffuse list, area graph or
     * payload here. These facts can describe an earlier observation; only the owning WrittenTile's
     * current metadata validity may authorize a new handoff/borrow.
     */
    public static final class Facts {
        private final BuildRef build;
        private final String locale, scope, geometry;
        private final long owner, generation, mapId;
        private final int x, y, chunks;
        private final SourceTuple wdt, root;
        private final Bounds bounds;
        private final Outcome outcome;
        private final Fallback fallback;
        private final MapAuthority authority;
        private final WorldTerrainTextureSource.State materialState;
        private final WorldTerrainTextureSource.Reason terminalReason;

        private Facts(WorldPreparedTerrain.Borrow borrow, Cancellation cancel) {
            var namespace = borrow.metadata();
            build = namespace.build();
            locale = namespace.locale();
            scope = namespace.scopeDigest();
            owner = namespace.owner();
            generation = namespace.generation();
            mapId = namespace.mapId();
            x = borrow.tile().x();
            y = borrow.tile().y();
            geometry = borrow.geometrySha256();
            wdt =
                    new SourceTuple(
                            namespace.wdtId(),
                            namespace.wdtKey(),
                            namespace.wdtLength(),
                            namespace.wdtDeclaredLength(),
                            namespace.wdtSourceLimit());
            root = tuple(borrow.root());
            outcome =
                    switch (borrow.outcome()) {
                        case TEXTURED -> Outcome.TEXTURED;
                        case HEIGHT_FALLBACK -> Outcome.HEIGHT_FALLBACK;
                    };
            fallback =
                    switch (borrow.fallback()) {
                        case NONE -> Fallback.NONE;
                        case QUALIFIED_SOURCE_TERMINAL -> Fallback.TERMINAL_MATERIAL_UNAVAILABLE;
                        case QUALIFIED_BINDING_REJECTED -> Fallback.QUALIFIED_BINDING_REJECTED;
                    };
            authority =
                    switch (namespace.mapAuthority()) {
                        case COMPLETE_READY -> MapAuthority.COMPLETE_READY;
                        case OBSERVED_SELECTED_ROW -> MapAuthority.OBSERVED_SELECTED_ROW;
                    };
            chunks = borrow.supportedChunks();
            materialState = borrow.materialState();
            terminalReason = borrow.terminalReason();
            var vertices = borrow.tile().vertices();
            float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
            float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int at = 0; at < vertices.remaining(); at += 3) {
                if ((at & 1023) == 0) {
                    cancel.check();
                }
                float vx = vertices.get(at), vy = vertices.get(at + 1), vz = vertices.get(at + 2);
                minX = Math.min(minX, vx);
                maxX = Math.max(maxX, vx);
                minY = Math.min(minY, vy);
                maxY = Math.max(maxY, vy);
                minZ = Math.min(minZ, vz);
                maxZ = Math.max(maxZ, vz);
            }
            bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
            cancel.check();
        }

        public BuildRef build() {
            return build;
        }

        public String locale() {
            return locale;
        }

        public String scopeSha256() {
            return scope;
        }

        public long ownerIdentity() {
            return owner;
        }

        public long generation() {
            return generation;
        }

        public long mapId() {
            return mapId;
        }

        public int tileX() {
            return x;
        }

        public int tileY() {
            return y;
        }

        public String geometrySha256() {
            return geometry;
        }

        public SourceTuple wdt() {
            return wdt;
        }

        public SourceTuple root() {
            return root;
        }

        public Bounds bounds() {
            return bounds;
        }

        public Outcome outcome() {
            return outcome;
        }

        public Fallback fallback() {
            return fallback;
        }

        public MapAuthority mapAuthority() {
            return authority;
        }

        public WorldTerrainTextureSource.State materialState() {
            return materialState;
        }

        public WorldTerrainTextureSource.Reason terminalReason() {
            return terminalReason;
        }

        public int supportedChunks() {
            return chunks;
        }
    }

    /** Expiring material metadata view; no full receipt or receipt list can escape this API. */
    public static final class Material {
        private CheckedBorrow parent;

        private Material(CheckedBorrow parent) {
            this.parent = parent;
        }

        private WorldTerrainTextureSource.Receipt receipt() {
            if (parent == null) {
                throw new IllegalStateException("Expired material view");
            }
            return Objects.requireNonNull(parent.live().material());
        }

        public SourceTuple wdt() {
            return tuple(receipt().wdt());
        }

        public SourceTuple root() {
            return tuple(receipt().root());
        }

        public SourceTuple textures() {
            return tuple(receipt().textures());
        }

        /** May be null for honest partial material; never treat it as a complete cache entry. */
        public String compositionCacheSha256() {
            return receipt().cacheKey();
        }

        public String geometrySha256() {
            return receipt().geometrySha256();
        }

        public int diffuseCount() {
            return receipt().diffuse().size();
        }

        public Diffuse diffuse(int index) {
            var mip = receipt().diffuse().get(index);
            return new Diffuse(
                    tuple(mip.file()),
                    mip.originalSide(),
                    mip.level(),
                    mip.mipFlags(),
                    DiffuseEncoding.valueOf(mip.encoding()),
                    mip.alphaDepth(),
                    mip.alphaEncoding());
        }
    }

    /** Same-thread, synchronous view. Geometry, pixels and area graphs must not escape. */
    public static final class CheckedBorrow {
        private WorldPreparedTerrain.Borrow borrow;
        private final Facts facts;
        private final long thread = Thread.currentThread().threadId();
        private Material material;

        private CheckedBorrow(WorldPreparedTerrain.Borrow borrow, Facts facts) {
            this.borrow = borrow;
            this.facts = facts;
            if (borrow.material() != null) {
                material = new Material(this);
            }
        }

        private WorldPreparedTerrain.Borrow live() {
            if (borrow == null || thread != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired terrain view");
            }
            return borrow;
        }

        public Facts facts() {
            live();
            return facts;
        }

        public TerrainScene.Tile geometry() {
            return live().tile();
        }

        public TerrainTextureBinding texture() {
            return live().texture();
        }

        public WorldFormats.AreaMetadata areas() {
            return live().areas();
        }

        public Material material() {
            live();
            return material;
        }

        private void expire() {
            if (material != null) {
                material.parent = null;
            }
            material = null;
            borrow = null;
        }
    }

    @FunctionalInterface
    public interface Output<R extends AutoCloseable> {
        /**
         * Clean every unreturned resource locally. Return only unadvertised output; never publish.
         */
        R writeUnadvertised(CheckedBorrow borrow, Cancellation cancel);
    }

    /** Singular base owner; cleanup and ownership-move callbacks must run off FX. */
    public static final class WrittenTile<R extends AutoCloseable> implements AutoCloseable {
        private final WorldPreparedTerrain.WrittenTile<R> tile;
        private final Facts facts;

        private WrittenTile(WorldPreparedTerrain.WrittenTile<R> tile, Facts facts) {
            this.tile = tile;
            this.facts = facts;
        }

        public Facts facts() {
            return facts;
        }

        public boolean isOpen() {
            return tile.isOpen();
        }

        public void withPayload(Consumer<CheckedBorrow> use) {
            Objects.requireNonNull(use);
            tile.withPayload(
                    borrow -> {
                        var checked = new CheckedBorrow(borrow, facts);
                        try {
                            use.accept(checked);
                        } finally {
                            checked.expire();
                        }
                    });
        }

        /**
         * Off-FX synchronous move only. R must not escape; a separately owned moved resource may.
         * Source close waits this accepted callback before closing the now-inert R/base owners.
         */
        public void withWrittenResource(Consumer<R> moveOnly) {
            tile.withWrittenResource(moveOnly);
        }

        @Override
        public void close() {
            tile.close();
        }
    }

    private volatile WorldPreparedTerrain source;

    private WorldTerrainStreaming(WorldPreparedTerrain source) {
        this.source = source;
    }

    static WorldTerrainStreaming owned(WorldPreparedTerrain source) {
        return new WorldTerrainStreaming(Objects.requireNonNull(source));
    }

    void invalidate() {
        source = null;
    }

    private WorldPreparedTerrain source() {
        var current = source;
        if (current == null) {
            throw new IllegalStateException("Terrain source closed");
        }
        return current;
    }

    public Scope createScope(WorldService.MapInfo comparison, Cancellation cancel) {
        Objects.requireNonNull(comparison);
        if (comparison.id() < 0 || comparison.id() > 0xffff_ffffL) {
            throw new IllegalArgumentException("Map ID profile");
        }
        return new Scope(source().createScope(comparison, cancel));
    }

    public void activate(Scope scope) {
        source().activate(Objects.requireNonNull(scope).token);
    }

    public <R extends AutoCloseable> WrittenTile<R> prepareAndWrite(
            Scope scope, WorldFormats.Tile comparison, Cancellation cancel, Output<R> writer) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(comparison);
        Objects.requireNonNull(cancel);
        Objects.requireNonNull(writer);
        if (comparison.x() < 0
                || comparison.x() >= 64
                || comparison.y() < 0
                || comparison.y() >= 64) {
            throw new IllegalArgumentException("Tile coordinate profile");
        }
        var facts = new Facts[1];
        var owned =
                source().prepareAndWrite(
                                scope.token,
                                comparison,
                                cancel,
                                (borrow, token) -> {
                                    facts[0] = new Facts(borrow, token);
                                    var checked = new CheckedBorrow(borrow, facts[0]);
                                    try {
                                        return writer.writeUnadvertised(checked, token);
                                    } finally {
                                        checked.expire();
                                    }
                                });
        boolean transferred = false;
        try {
            var result = new WrittenTile<>(owned, Objects.requireNonNull(facts[0]));
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                owned.close();
            }
        }
    }

    private static SourceTuple tuple(WorldTerrainTextureSource.FileBinding binding) {
        return new SourceTuple(
                binding.fileId(),
                binding.contentKey(),
                binding.length(),
                binding.declaredSize(),
                binding.sourceLimit());
    }
}
