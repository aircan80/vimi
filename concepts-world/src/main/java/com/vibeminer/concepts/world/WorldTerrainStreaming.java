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
    /**
     * Selects the splat mask resolution of newly composed tiles from the texture quality tier
     * (issue #174 step 2b-2): HIGH carries the native 64 x 64 alpha map per chunk (1024 per tile),
     * STANDARD every other texel (512).
     */
    public static void splatMasks(WorldExteriorObjects.ImageDetail detail) {
        splatMasks(detail, false);
    }

    /**
     * As {@link #splatMasks(WorldExteriorObjects.ImageDetail)} for a session's view distance: the
     * FAR view distance uses STANDARD masks on any tier ({@link
     * com.vibeminer.rendering.world.TerrainTileSplat#maskSide}).
     */
    public static void splatMasks(WorldExteriorObjects.ImageDetail detail, boolean far) {
        WorldTerrainTextureSource.splatMask =
                com.vibeminer.rendering.world.TerrainTileSplat.maskSide(
                        Objects.requireNonNull(detail) == WorldExteriorObjects.ImageDetail.HIGH,
                        far);
    }

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

    /** Expected negative source outcome; never a successful height packet or a drain receipt. */
    public static final class Refusal extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public enum Reason {
            RETRY_LATER,
            CAPABILITY_UNAVAILABLE,
            SOURCE_INVALIDATED
        }

        private final Reason reason;
        private final String site, detail;

        private Refusal(WorldSourceRefusals.Site origin, String detail) {
            super("Terrain source: " + origin.name(), null);
            reason = origin.reason();
            site = origin.name();
            this.detail = detail;
            if (getMessage().length() > 256
                    || detail != null && (detail.isBlank() || detail.length() > 160)) {
                throw new IllegalArgumentException("Source refusal scalar bound");
            }
        }

        public Reason reason() {
            return reason;
        }

        public String site() {
            return site;
        }

        /**
         * Which profile check refused, in plain words built only from fixed text and counts; empty
         * when the site alone says it. Never a path, name or provider message.
         */
        public java.util.Optional<String> detail() {
            return java.util.Optional.ofNullable(detail);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    static Refusal refusal(WorldSourceRefusals.Site site) {
        return new Refusal(site, null);
    }

    static Refusal refusal(WorldSourceRefusals.Site site, String detail) {
        return new Refusal(site, java.util.Objects.requireNonNull(detail));
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

    /**
     * A layer diffuse for splatting as its 256 x 256 BC1 chain ({@link
     * com.vibeminer.rendering.world.TerrainLayerBlocks}); {@code chain} is a read-only view, and
     * {@code fileBlocks} says whether it is the file's own blocks or an encoding of its pixels;
     * {@code sourceEncoding} is the file's encoding (BC1, BC2, BC3, PALETTED or BGRA8), whose alpha
     * the opaque layer drops.
     */
    public record DiffuseDetail(
            long fileId,
            String contentKey,
            java.nio.ByteBuffer chain,
            boolean fileBlocks,
            String sourceEncoding) {
        public DiffuseDetail {
            Objects.requireNonNull(contentKey);
            Objects.requireNonNull(chain);
            if (!chain.isReadOnly()
                    || chain.remaining()
                            != com.vibeminer.rendering.world.TerrainLayerBlocks.CHAIN_BYTES) {
                throw new IllegalArgumentException("Invalid diffuse detail");
            }
        }
    }

    /** Splat layers prepared from file blocks, and with the lossy BC1 encoder. */
    public record SplatLayerCounts(long fileBlocks, long encoded) {}

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

        /** Splatting inputs of this tile's atlas, if composed (issue #174 step 2). */
        public java.util.Optional<com.vibeminer.rendering.world.TerrainTileSplat> splat() {
            return java.util.Optional.ofNullable(live().splat());
        }

        /**
         * How this source's splat layers were prepared so far (issue #174): from the file's own
         * blocks, and with the lossy encoder; for measuring how often real data needs the encoder.
         */
        public SplatLayerCounts splatLayerCounts() {
            long[] counts = live().splatLayerCounts();
            return new SplatLayerCounts(counts[0], counts[1]);
        }

        /**
         * One layer diffuse of this tile for splatting, as its 256 x 256 BC1 layer chain, read
         * through the same bounds and revalidation as the atlas's own mips, and cached by content.
         * Empty when the diffuse is not part of this tile's material.
         */
        public java.util.Optional<DiffuseDetail> diffuseDetail(
                long fileId, com.vibeminer.access.Cancellation token) {
            var detail = live().detail(fileId, token);
            return detail == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(
                            new DiffuseDetail(
                                    detail.fileId(),
                                    detail.contentKey(),
                                    detail.blocks(),
                                    detail.fileBlocks(),
                                    detail.sourceEncoding()));
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
            try {
                tile.withPayload(
                        borrow -> {
                            var checked = new CheckedBorrow(borrow, facts);
                            try {
                                use.accept(checked);
                            } finally {
                                checked.expire();
                            }
                        });
            } catch (RuntimeException failure) {
                throw WorldSourceRefusals.boundary(failure);
            }
        }

        /**
         * Off-FX synchronous move only. R must not escape; a separately owned moved resource may.
         * Source close waits this accepted callback before closing the now-inert R/base owners.
         */
        public void withWrittenResource(Consumer<R> moveOnly) {
            try {
                tile.withWrittenResource(moveOnly);
            } catch (RuntimeException failure) {
                throw WorldSourceRefusals.boundary(failure);
            }
        }

        @Override
        public void close() {
            try {
                tile.close();
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Source output cleanup failed", failure);
            }
        }
    }

    static <T extends AutoCloseable> T withWaterJoin(
            WrittenTile<?> terrain,
            WorldPreparedTerrain expected,
            java.util.function.BiFunction<WorldStreamScopes.Scope, WorldPreparedTerrain.Borrow, T>
                    use) {
        return Objects.requireNonNull(terrain).tile.withWaterJoin(expected, use);
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
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
        return current;
    }

    public Scope createScope(WorldService.MapInfo comparison, Cancellation cancel) {
        Objects.requireNonNull(comparison);
        if (comparison.id() < 0 || comparison.id() > 0xffff_ffffL) {
            throw new IllegalArgumentException("Map ID profile");
        }
        try {
            return new Scope(source().createScope(comparison, cancel));
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    /**
     * Revision of the shared terrain geometry lane's releases. A tile refused at {@code
     * GEOMETRY_CREDIT} can only succeed after it moves, so wait on it instead of retrying (#304).
     */
    public long geometryReleaseRevision() {
        try {
            return source().geometryReleaseRevision();
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    /**
     * Runs {@code listener} after each geometry lane or output release, on the releasing thread and
     * outside any source lock. The listener must only signal; close the result to remove it.
     */
    public AutoCloseable onGeometryRelease(Runnable listener) {
        try {
            return source().onGeometryRelease(Objects.requireNonNull(listener));
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    public void activate(Scope scope) {
        try {
            source().activate(Objects.requireNonNull(scope).token);
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    /**
     * Prepares one recorded tile's BASIC_EXTERIOR_V1 outdoor building set as its own serialized
     * source operation. Call it outside any terrain write. The returned set is immutable neutral
     * heap data that the caller closes; omissions are counted, never guessed.
     */
    public WorldExteriorObjects.TileObjects prepareExteriorObjects(
            Scope scope, WorldFormats.Tile comparison, Cancellation cancel) {
        return prepareExteriorObjects(
                scope, comparison, cancel, WorldExteriorObjects.ImageDetail.STANDARD);
    }

    /** As above, with the object texture budget tier (#267). */
    public WorldExteriorObjects.TileObjects prepareExteriorObjects(
            Scope scope,
            WorldFormats.Tile comparison,
            Cancellation cancel,
            WorldExteriorObjects.ImageDetail detail) {
        return prepareExteriorObjects(scope, comparison, cancel, detail, false);
    }

    /**
     * As above; {@code interiors} also draws interior WMO groups, for dungeon and raid maps (#280),
     * within the budgets each building's exterior groups leave.
     */
    public WorldExteriorObjects.TileObjects prepareExteriorObjects(
            Scope scope,
            WorldFormats.Tile comparison,
            Cancellation cancel,
            WorldExteriorObjects.ImageDetail detail,
            boolean interiors) {
        return prepareExteriorObjects(scope, comparison, cancel, detail, interiors, 0);
    }

    /**
     * As above, for a GPU session whose child samples {@code compressedFormats} as blocks (bit per
     * {@link com.vibeminer.rendering.world.ObjectTextureFormats} code, from its SESSION status):
     * chains carried in those formats are charged their stored bytes and may take the larger sides
     * (#374). The set reports the mask its objects file must be built with.
     */
    public WorldExteriorObjects.TileObjects prepareExteriorObjects(
            Scope scope,
            WorldFormats.Tile comparison,
            Cancellation cancel,
            WorldExteriorObjects.ImageDetail detail,
            boolean interiors,
            int compressedFormats) {
        Objects.requireNonNull(detail);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(comparison);
        Objects.requireNonNull(cancel);
        if (comparison.x() < 0
                || comparison.x() >= 64
                || comparison.y() < 0
                || comparison.y() >= 64) {
            throw new IllegalArgumentException("Tile coordinate profile");
        }
        try {
            return source().exteriorObjects(
                            scope.token, comparison, cancel, detail, interiors, compressedFormats);
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
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
        try {
            return prepareOwned(scope, comparison, cancel, writer);
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    private <R extends AutoCloseable> WrittenTile<R> prepareOwned(
            Scope scope, WorldFormats.Tile comparison, Cancellation cancel, Output<R> writer) {
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
        Throwable primary = null;
        try {
            var result = new WrittenTile<>(owned, Objects.requireNonNull(facts[0]));
            transferred = true;
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!transferred) {
                try {
                    owned.close();
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

    private static SourceTuple tuple(WorldTerrainTextureSource.FileBinding binding) {
        return new SourceTuple(
                binding.fileId(),
                binding.contentKey(),
                binding.length(),
                binding.declaredSize(),
                binding.sourceLimit());
    }
}
