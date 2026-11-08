package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Actual single-root water Source: qualified height+depth layers with their actual vertex heights,
 * flat or sloped. Resource callbacks and close belong off FX.
 */
public final class WorldWaterPreview {
    public static final String REVISION = "source-water-root-v2";

    public enum State {
        ABSENT,
        EMPTY,
        READY,
        PARTIAL,
        UNRESOLVED
    }

    public record TableObservation(
            long id, String contentKey, long nativeConsumedLength, String receiptSha256) {}

    /** Descriptions only: no constructor or Facts value can authorize a water owner. */
    public static final class Facts {
        private final WorldTerrainLiquids.Summary summary;
        private final WorldStreamScopes.AdmissionMetadata metadata;
        private final List<TableObservation> tables;
        private final int tileX, tileY;
        private final List<String> issues;
        private final Long rootDeclaredLength;
        private final int rootSourceLimit;

        private Facts(WorldTerrainLiquids.Borrow borrow, int tileX, int tileY) {
            summary = borrow.facts();
            issues = List.copyOf(borrow.issues());
            rootDeclaredLength = borrow.rootBinding().declaredSize();
            rootSourceLimit = borrow.rootBinding().sourceLimit();
            metadata = borrow.metadata();
            this.tileX = tileX;
            this.tileY = tileY;
            tables =
                    borrow.tableFacts().stream()
                            .map(
                                    t ->
                                            new TableObservation(
                                                    t.id(), t.key(), t.length(), t.receiptSha()))
                            .toList();
        }

        public State state() {
            return State.valueOf(summary.state().name());
        }

        /**
         * Where this tile's source disagrees with a convention the water followed (for example a
         * LiquidType row that resolves another vertex format); descriptive, never fatal.
         */
        public List<String> issues() {
            return issues;
        }

        public String reason() {
            return summary.reason().name();
        }

        public int admittedLayers() {
            return summary.admitted();
        }

        public int declaredLayers() {
            return summary.declared();
        }

        public int unresolvedLayers() {
            return summary.unresolved();
        }

        public int cells() {
            return summary.cells();
        }

        public long rootId() {
            return summary.rootId();
        }

        public String rootKey() {
            return summary.rootKey();
        }

        public long rootConsumedLength() {
            return summary.rootLength();
        }

        public Long rootDeclaredLength() {
            return rootDeclaredLength;
        }

        public int rootSourceLimit() {
            return rootSourceLimit;
        }

        public WorldTerrainStreaming.MapAuthority mapAuthority() {
            return WorldTerrainStreaming.MapAuthority.valueOf(metadata.mapAuthority().name());
        }

        public String nativeGeometrySha256() {
            return summary.geometrySha();
        }

        public BuildRef build() {
            return metadata.build();
        }

        public String locale() {
            return metadata.locale();
        }

        public long mapId() {
            return metadata.mapId();
        }

        public String scopeSha256() {
            return metadata.scopeDigest();
        }

        public long wdtId() {
            return metadata.wdtId();
        }

        public String wdtKey() {
            return metadata.wdtKey();
        }

        public long wdtConsumedLength() {
            return metadata.wdtLength();
        }

        public Long wdtDeclaredLength() {
            return metadata.wdtDeclaredLength();
        }

        public int wdtSourceLimit() {
            return metadata.wdtSourceLimit();
        }

        public int tileX() {
            return tileX;
        }

        public int tileY() {
            return tileY;
        }

        public List<TableObservation> tableObservations() {
            return tables;
        }
    }

    public static final class CheckedBorrow {
        private WorldTerrainLiquids.Borrow borrow;
        private final Facts facts;
        private final long worker = Thread.currentThread().threadId();

        private CheckedBorrow(WorldTerrainLiquids.Borrow borrow, Facts facts) {
            this.borrow = borrow;
            this.facts = facts;
        }

        private WorldTerrainLiquids.Borrow checked() {
            if (borrow == null || worker != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired or foreign water borrow");
            }
            return borrow;
        }

        public Facts facts() {
            checked();
            return facts;
        }

        public int vertices() {
            return checked().vertexCount();
        }

        public int indices() {
            return checked().indexCount();
        }

        public float north(int vertex) {
            return checked().vertexNorth(vertex);
        }

        public float west(int vertex) {
            return checked().vertexWest(vertex);
        }

        public float up(int vertex) {
            return checked().vertexUp(vertex);
        }

        public int depth(int vertex) {
            return checked().vertexDepth(vertex);
        }

        public int index(int ordinal) {
            return checked().index(ordinal);
        }

        public int layers() {
            return checked().layers();
        }

        public int header(int layer) {
            return checked().header(layer);
        }

        public int instance(int layer) {
            return checked().instance(layer);
        }

        public int rawType(int layer) {
            return checked().liquidType(layer);
        }

        public int secondary(int layer) {
            return checked().secondary(layer);
        }

        /**
         * True when the client draws this layer as ocean rather than river or lake water (#171):
         * its LiquidObject is 42, or its LiquidType is 14, as public notes on the client's liquid
         * registration give it. On 1.60.1 every LiquidObject 42 layer is Generic Ocean.
         */
        public boolean ocean(int layer) {
            var b = checked();
            return b.secondary(layer) == 42 || b.liquidType(layer) == 14;
        }

        public int x(int layer) {
            return checked().x(layer);
        }

        public int y(int layer) {
            return checked().y(layer);
        }

        public int width(int layer) {
            return checked().width(layer);
        }

        public int height(int layer) {
            return checked().height(layer);
        }

        public long mask(int layer) {
            return checked().mask(layer);
        }

        /** True when every used height sample of the layer is the same value. */
        public boolean planar(int layer) {
            return checked().planar(layer);
        }

        /**
         * The layer's flat colour as 0xRRGGBB (LiquidType MinimapStaticCol) for magma, slime and
         * tar layers (vertex formats 1 and 3), or -1 for water.
         */
        public int colour(int layer) {
            return checked().colour(layer);
        }

        /** Actual height at one sample of the layer's (width + 1) x (height + 1) grid. */
        public float usedHeight(int layer, int vertex) {
            return checked().actualUsedHeight(layer, vertex);
        }

        private void expire() {
            borrow = null;
        }
    }

    /**
     * Runs synchronously on the single Source worker lane while the terrain tile is exclusively
     * pinned. Keep it bounded; never hop to FX or block on other Source work.
     */
    @FunctionalInterface
    public interface Output<R extends AutoCloseable> {
        R writeUnadvertised(CheckedBorrow borrow, Cancellation actualAdmissionToken);
    }

    public sealed interface Preparation<R extends AutoCloseable> extends AutoCloseable
            permits WrittenWater, NoSurface {
        Facts facts();

        @Override
        void close();
    }

    public static final class NoSurface<R extends AutoCloseable> implements Preparation<R> {
        private final Facts facts;

        private NoSurface(Facts facts) {
            this.facts = facts;
        }

        @Override
        public Facts facts() {
            return facts;
        }

        @Override
        public void close() {}
    }

    public static final class WrittenWater<R extends AutoCloseable> implements Preparation<R> {
        private final WorldWaterPreview facade;
        private final WorldTerrainLiquids.Owner owner;
        private final Facts facts;

        private WrittenWater(
                WorldWaterPreview facade, WorldTerrainLiquids.Owner owner, Facts facts) {
            this.facade = facade;
            this.owner = owner;
            this.facts = facts;
        }

        @Override
        public Facts facts() {
            return facts;
        }

        public boolean isOpen() {
            return facade.open && owner.isOpen();
        }

        /**
         * Same-thread scalar borrow. A concurrent or nested borrow is a typed RETRY_LATER refusal.
         * Coordinates are native terrain axes: north (terrain x), west (terrain y), up (z).
         */
        public <T> T withPayload(Function<CheckedBorrow, T> read) {
            Objects.requireNonNull(read);
            facade.check();
            return pressureRefused(
                    () ->
                            owner.withBorrow(
                                    b -> {
                                        var checked = new CheckedBorrow(b, facts);
                                        try {
                                            return read.apply(checked);
                                        } finally {
                                            checked.expire();
                                        }
                                    }));
        }

        /**
         * Pinned borrow of the returned writer resource. This is NOT an ownership transfer: this
         * owner still closes R. A consumer that needs data beyond this owner copies it.
         */
        public void withWrittenResource(Consumer<R> moveOnly) {
            Objects.requireNonNull(moveOnly);
            facade.check();
            pressureRefused(
                    () -> {
                        owner.withResource(
                                resource -> {
                                    @SuppressWarnings("unchecked")
                                    R r = (R) resource;
                                    moveOnly.accept(r);
                                });
                        return null;
                    });
        }

        public CompletableFuture<Void> closeAsync() {
            return owner.closeAsync();
        }

        @Override
        public void close() {
            owner.close();
        }
    }

    private static <T> T pressureRefused(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (WorldTerrainLiquids.Pressure busy) {
            if (busy.getCause() == null && busy.getSuppressed().length == 0) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.WATER_OUTPUT_CREDIT);
            }
            throw busy;
        }
    }

    private final WorldPreparedTerrain source;
    private volatile boolean open = true;

    WorldWaterPreview(WorldPreparedTerrain source) {
        this.source = Objects.requireNonNull(source);
    }

    void invalidate() {
        open = false;
    }

    private void check() {
        source.requireHealthy();
        if (!open) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
    }

    public <R extends AutoCloseable> Preparation<R> prepareAndWrite(
            WorldTerrainStreaming.WrittenTile<?> terrain,
            Cancellation cancellation,
            Output<R> output) {
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(cancellation);
        Objects.requireNonNull(output);
        try {
            return source.withWaterJoin(
                    terrain,
                    (scope, pinned) -> {
                        check();
                        var tile = pinned.recorded();
                        var facts = new Facts[1];
                        WorldTerrainLiquids.Owner owner = null;
                        boolean returned = false;
                        try {
                            owner =
                                    source.waterSource()
                                            .inspectAndWrite(
                                                    scope,
                                                    tile,
                                                    pinned.root(),
                                                    pinned.geometrySha256(),
                                                    cancellation,
                                                    (borrow, actual) -> {
                                                        facts[0] =
                                                                new Facts(
                                                                        borrow, tile.x(), tile.y());
                                                        var checked =
                                                                new CheckedBorrow(borrow, facts[0]);
                                                        try {
                                                            return output.writeUnadvertised(
                                                                    checked, actual);
                                                        } finally {
                                                            checked.expire();
                                                        }
                                                    });
                            if (facts[0] == null) {
                                facts[0] = owner.withBorrow(b -> new Facts(b, tile.x(), tile.y()));
                                return new NoSurface<R>(facts[0]);
                            }
                            check();
                            cancellation.check();
                            var result = new WrittenWater<R>(this, owner, facts[0]);
                            returned = true;
                            return result;
                        } finally {
                            if (!returned && owner != null) {
                                owner.close();
                            }
                        }
                    });
        } catch (RuntimeException failure) {
            var checked = WorldSourceRefusals.boundary(failure);
            if (checked.getClass() == WorldTerrainLiquids.Pressure.class
                    && checked.getSuppressed().length == 0
                    && checked.getCause() == null) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.WATER_OUTPUT_CREDIT);
            }
            if (checked.getClass() == WorldTerrainLiquids.Changed.class
                    && checked.getSuppressed().length == 0
                    && checked.getCause() == null) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.WATER_ROOT_BINDING);
            }
            throw checked;
        }
    }
}
