package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLiquids;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Source-qualified single-root MH2O height+depth geometry. No scene or drawing. Every qualified
 * layer keeps its actual used vertex heights, flat or not; overlapping instances of one chunk are
 * all kept, as the source declares them.
 */
final class WorldTerrainLiquids {
    static final String REVISION = "source-mh2o-local-root-inspection-v4";

    /**
     * One root: 256 chunk headers with at most four instances each. Cells are bounded as if every
     * cell of the root were covered once (256 x 64); overlapping instances beyond that are left out
     * as CAPPED.
     */
    static final int MAX_LAYERS = 1024, MAX_CELLS = 16384, MAX_SAMPLES = MAX_LAYERS * 81;

    /** Distinct direct format 1/3 LiquidTypes looked up per root, one capture each. */
    static final int MAX_DIRECT_TYPES = 8;

    enum State {
        ABSENT,
        EMPTY,
        READY,
        PARTIAL,
        UNRESOLVED
    }

    enum Reason {
        NONE,
        TABLE_UNAVAILABLE,
        TABLE_PROFILE,
        ROW_NOT_OBSERVED,
        ROW_ISSUE,
        SOURCE_CHANGED,
        GRID_PROFILE,
        VERTEX_ABSENT,
        UNSUPPORTED_LAYOUT,
        UNRESOLVED_FOOTPRINT,
        CAPPED,
        MALFORMED
    }

    /** Building liquid types looked up per tile: one table lookup per distinct type. */
    static final int MAX_APPEARANCE_TYPES = 16;

    /**
     * How building liquids (WMO MLIQ) of these LiquidType IDs are drawn, by the rule terrain water
     * follows: the type's LiquidMaterial vertex format 0 or 2 is water (ocean for LiquidType 14,
     * WMO ocean; river or lake otherwise), 1 or 3 a flat liquid in the type's MinimapStaticCol.
     * Each type is looked up on its own, so a missing row leaves only that type unknown. Answers
     * kind (0 river, 1 ocean, 2 flat) and colour per known type.
     */
    java.util.Map<Integer, int[]> appearances(java.util.Set<Integer> types, Cancellation token) {
        var out = new java.util.HashMap<Integer, int[]>();
        int looked = 0;
        for (int type : types) {
            token.check();
            if (type <= 0 || looked++ >= MAX_APPEARANCE_TYPES) {
                continue;
            }
            try {
                var row =
                        tables.observe(
                                WorldLiquidTables.Profile.TYPE,
                                java.util.Set.of((long) type),
                                token);
                int material = row.value(type);
                var materials =
                        tables.observe(
                                WorldLiquidTables.Profile.MATERIAL,
                                java.util.Set.of((long) material),
                                token);
                int format = materials.value(material);
                if (format == 0 || format == 2) {
                    out.put(type, new int[] {type == 14 ? 1 : 0, 0});
                } else if (format == 1 || format == 3) {
                    out.put(type, new int[] {2, row.minimapColour(type) & 0xFFFFFF});
                }
            } catch (WorldLiquidTables.Unavailable missing) {
                // Unknown: not drawn.
            }
        }
        return java.util.Map.copyOf(out);
    }

    static final class Pressure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Pressure() {
            super("Liquid inspection capacity", null);
        }
    }

    static final class Changed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Changed() {
            super("Liquid source changed", null);
        }
    }

    record Summary(
            State state,
            Reason reason,
            int declared,
            int admitted,
            int unresolved,
            int cells,
            long rootId,
            String rootKey,
            long rootLength,
            String geometrySha) {}

    record TableFact(long id, String key, long length, String receiptSha) {}

    private record Layer(
            int header,
            int instance,
            int type,
            int secondary,
            int x,
            int y,
            int width,
            int height,
            long mask,
            boolean planar,
            float[] heights,
            byte[] depth,
            int colour) {}

    @FunctionalInterface
    interface Read<T> {
        T read(Borrow borrow);
    }

    static final class Owner implements AutoCloseable {
        private WorldStreamScopes.LiquidCredit credit;
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private Summary summary;
        private List<Layer> layers = List.of();
        private float[] positions;
        private byte[] depths;
        private int[] indices;
        private int pins = 1;
        private boolean closing, qualified, retiring, construction = true;
        private AutoCloseable written;
        private WorldStreamScopes.AdmissionMetadata metadata;
        private WorldTerrainTextureSource.FileBinding rootBinding;
        private List<WorldLiquidTables.Observed> observations = List.of();
        private List<TableFact> tableFacts = List.of();
        private List<String> issues = List.of();

        private Owner(WorldStreamScopes.LiquidCredit credit) {
            this.credit = credit;
        }

        synchronized void publish() {
            if (closing || summary == null || pins != 1 || qualified) {
                throw new IllegalStateException("Liquid output not publishable");
            }
            qualified = true;
        }

        synchronized void pin() {
            if (closing || !qualified || credit == null) {
                throw new IllegalStateException("Liquid owner closed");
            }
            if (pins != 0) {
                throw new Pressure();
            }
            pins++;
        }

        /** Source facts that disagree with a convention this owner followed; never fatal. */
        synchronized List<String> issues() {
            return issues;
        }

        synchronized Summary summary() {
            if (!qualified) {
                throw new IllegalStateException("Unqualified liquid owner");
            }
            return summary;
        }

        <T> T withBorrow(Read<T> read) {
            Objects.requireNonNull(read);
            WorldStreamScopes.LiquidCredit source;
            synchronized (this) {
                source = credit;
            }
            if (source == null) {
                throw new IllegalStateException("Liquid owner closed");
            }
            source.pin(this);
            Borrow borrow = null;
            try {
                borrow = new Borrow(this);
                return read.read(borrow);
            } finally {
                if (borrow != null) {
                    borrow.expire();
                }
                releasePin();
            }
        }

        private void releasePin() {
            synchronized (this) {
                if (pins < 1) {
                    throw new IllegalStateException("Liquid pin underflow");
                }
                pins--;
            }
            retire();
        }

        private void retire() {
            WorldStreamScopes.LiquidCredit source;
            AutoCloseable resource;
            synchronized (this) {
                if (!closing || pins != 0 || credit == null || retiring) {
                    return;
                }
                retiring = true;
                source = credit;
                resource = written;
            }
            Throwable failure = null;
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception | Error error) {
                failure = error;
            }
            synchronized (this) {
                layers = List.of();
                positions = null;
                depths = null;
                indices = null;
                observations = List.of();
                metadata = null;
                rootBinding = null;
                tableFacts = List.of();
                credit = null;
                if (failure == null) {
                    written = null;
                }
            }
            source.retired(failure, this);
            if (failure == null) {
                drained.complete(null);
            } else {
                var fatal = new IllegalStateException("Water resource cleanup failed", failure);
                drained.completeExceptionally(fatal);
                throw fatal;
            }
        }

        private void finishConstruction() {
            synchronized (this) {
                if (!construction) {
                    return;
                }
                construction = false;
            }
            releasePin();
        }

        boolean isOpen() {
            WorldStreamScopes.LiquidCredit source;
            synchronized (this) {
                source = credit;
            }
            return source != null && source.isOpen(this);
        }

        synchronized boolean available() {
            return qualified && !closing && credit != null;
        }

        void withResource(java.util.function.Consumer<AutoCloseable> use) {
            withBorrow(
                    b -> {
                        use.accept(Objects.requireNonNull(written));
                        return null;
                    });
        }

        @Override
        public void close() {
            synchronized (this) {
                closing = true;
            }
            retire();
        }

        CompletableFuture<Void> closeAsync() {
            close();
            return drained.copy();
        }
    }

    /** Same-worker scalar read. No array, resource R, receipt, packet or source handle escape. */
    static final class Borrow {
        private Owner owner;
        private final long thread = Thread.currentThread().threadId();

        private Borrow(Owner owner) {
            this.owner = owner;
        }

        private Owner checked() {
            if (owner == null || thread != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired or foreign liquid borrow");
            }
            return owner;
        }

        int layers() {
            return checked().layers.size();
        }

        private Layer layer(int ordinal) {
            var a = checked();
            return a.layers.get(Objects.checkIndex(ordinal, a.layers.size()));
        }

        int header(int ordinal) {
            return layer(ordinal).header();
        }

        int instance(int ordinal) {
            return layer(ordinal).instance();
        }

        int liquidType(int ordinal) {
            return layer(ordinal).type();
        }

        int secondary(int ordinal) {
            return layer(ordinal).secondary();
        }

        int x(int ordinal) {
            return layer(ordinal).x();
        }

        int y(int ordinal) {
            return layer(ordinal).y();
        }

        int width(int ordinal) {
            return layer(ordinal).width();
        }

        int height(int ordinal) {
            return layer(ordinal).height();
        }

        long mask(int ordinal) {
            return layer(ordinal).mask();
        }

        /** True when every used height sample of the layer is the same value. */
        boolean planar(int ordinal) {
            return layer(ordinal).planar();
        }

        /**
         * Actual height at one sample of the layer's (width + 1) x (height + 1) grid, signed zero
         * canonicalized. Samples no present cell uses keep their finite source value.
         */
        float actualUsedHeight(int ordinal, int vertex) {
            var l = layer(ordinal);
            return l.heights()[Objects.checkIndex(vertex, l.heights().length)];
        }

        /**
         * The layer's flat colour as 0xRRGGBB, the client's LiquidType MinimapStaticCol, for the
         * non-water liquids (vertex formats 1 and 3: magma, slime, tar); -1 for water, which keeps
         * the renderer's water colour.
         */
        int colour(int ordinal) {
            return layer(ordinal).colour();
        }

        int depth(int ordinal, int vertex) {
            var l = layer(ordinal);
            return l.depth()[Objects.checkIndex(vertex, l.depth().length)] & 255;
        }

        int vertexCount() {
            var a = checked();
            return a.depths == null ? 0 : a.depths.length;
        }

        int indexCount() {
            var a = checked();
            return a.indices == null ? 0 : a.indices.length;
        }

        private float coordinate(int vertex, int component) {
            var a = checked();
            Objects.checkIndex(vertex, a.depths == null ? 0 : a.depths.length);
            return a.positions[vertex * 3 + component];
        }

        float vertexNorth(int vertex) {
            return coordinate(vertex, 0);
        }

        float vertexWest(int vertex) {
            return coordinate(vertex, 1);
        }

        float vertexUp(int vertex) {
            return coordinate(vertex, 2);
        }

        int vertexDepth(int vertex) {
            var a = checked();
            return a.depths[Objects.checkIndex(vertex, a.depths == null ? 0 : a.depths.length)]
                    & 255;
        }

        int index(int ordinal) {
            var a = checked();
            return a.indices[Objects.checkIndex(ordinal, a.indices == null ? 0 : a.indices.length)];
        }

        Summary facts() {
            return checked().summary;
        }

        WorldStreamScopes.AdmissionMetadata metadata() {
            return checked().metadata;
        }

        WorldTerrainTextureSource.FileBinding rootBinding() {
            return checked().rootBinding;
        }

        List<TableFact> tableFacts() {
            return checked().tableFacts;
        }

        /** Source facts that disagree with a convention this owner followed. */
        List<String> issues() {
            return checked().issues;
        }

        private void expire() {
            owner = null;
        }
    }

    private final WorldStreamScopes scopes;
    private final WorldLiquidTables tables;

    WorldTerrainLiquids(
            WorldStreamScopes scopes,
            BuildTables tables,
            BuildFiles files,
            com.vibeminer.builds.BuildTypes.BuildRef build,
            String locale) {
        this.scopes = scopes;
        this.tables = new WorldLiquidTables(tables, files, build, locale);
    }

    Owner inspect(WorldStreamScopes.Scope scope, WorldFormats.Tile tile, Cancellation external) {
        Objects.requireNonNull(tile);
        Objects.requireNonNull(external);
        var credit =
                scopes.reserveLiquid(scope); // BEFORE root IO, raw copy or optional native capture.
        Owner owner = null;
        boolean published = false;
        try {
            owner = new Owner(credit);
            scopes.attachLiquid(credit, owner);
            var building = owner;
            var result =
                    scopes.withRecordedTile(
                            scope,
                            tile,
                            WorldStreamScopes.UseProfile.LIQUID_INSPECTION,
                            external,
                            admission -> {
                                populate(building, admission);
                                building.observations = List.of();
                                return building;
                            });
            scopes.publishLiquid(credit, result, external);
            published = true;
            return result;
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        } finally {
            if (owner == null) {
                credit.retired();
            } else {
                if (!published) {
                    owner.close();
                }
                owner.finishConstruction();
            }
        }
    }

    @FunctionalInterface
    interface Writer<R extends AutoCloseable> {
        R write(Borrow borrow, Cancellation actualToken);
    }

    private static final class AdmissionResult implements AutoCloseable {
        private final Owner owner;

        private AdmissionResult(Owner owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            try {
                owner.close();
            } finally {
                owner.finishConstruction();
            }
        }
    }

    <R extends AutoCloseable> Owner inspectAndWrite(
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            WorldTerrainTextureSource.FileBinding expectedRoot,
            String expectedGeometry,
            Cancellation external,
            Writer<R> writer) {
        Objects.requireNonNull(writer);
        var credit = scopes.reserveLiquid(scope);
        Owner owner = null;
        boolean published = false;
        try {
            owner = new Owner(credit);
            scopes.attachLiquid(credit, owner);
            var building = owner;
            var returned =
                    scopes.withRecordedTile(
                            scope,
                            tile,
                            WorldStreamScopes.UseProfile.LIQUID_INSPECTION,
                            external,
                            admission -> {
                                try {
                                    populate(building, admission);
                                    building.metadata = admission.metadata();
                                    var actual = building.summary;
                                    if (actual.rootId() != expectedRoot.fileId()
                                            || !actual.rootKey().equals(expectedRoot.contentKey())
                                            || actual.rootLength() != expectedRoot.length()
                                            || actual.geometrySha() != null
                                                    && !Objects.equals(
                                                            actual.geometrySha(),
                                                            expectedGeometry)) {
                                        throw new Changed();
                                    }
                                    if (actual.cells() > 0) {
                                        var borrow = new Borrow(building);
                                        try {
                                            building.written =
                                                    Objects.requireNonNull(
                                                            writer.write(
                                                                    borrow,
                                                                    admission.cancellation()));
                                        } finally {
                                            borrow.expire();
                                        }
                                    }
                                    for (var observed : building.observations) {
                                        try {
                                            tables.revalidate(observed, admission.cancellation());
                                        } catch (WorldLiquidTables.Unavailable stale) {
                                            throw new Changed();
                                        } catch (WorldLiquidTables.CleanupFailure cleanup) {
                                            admission.liquidCleanupFailed(cleanup);
                                            throw cleanup;
                                        }
                                    }
                                    building.observations = List.of();
                                    return new AdmissionResult(building);
                                } catch (RuntimeException | Error primary) {
                                    try {
                                        new AdmissionResult(building).close();
                                    } catch (RuntimeException | Error cleanup) {
                                        if (primary != cleanup) {
                                            primary.addSuppressed(cleanup);
                                        }
                                        throw new IllegalStateException(
                                                "Water construction cleanup failed", primary);
                                    }
                                    throw primary;
                                }
                            });
            scopes.publishLiquid(credit, returned.owner, external);
            published = true;
            return returned.owner;
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        } finally {
            if (owner == null) {
                credit.retired();
            } else {
                try {
                    if (!published) {
                        owner.close();
                    }
                } finally {
                    owner.finishConstruction();
                }
            }
        }
    }

    private record Root(
            WorldLiquids.RawLiquids raw,
            long id,
            String key,
            long length,
            String geometry,
            boolean malformed,
            float[] anchors,
            Cancellation token,
            WorldTerrainTextureSource.FileBinding binding) {}

    private static Root root(WorldStreamScopes.TileAdmission admission) {
        try (var input = admission.readRecorded(WorldStreamScopes.RecordedInput.ROOT)) {
            var token = admission.cancellation();
            // Prove terrain validity first: an optional malformed MH2O cannot erase terrain facts.
            var grid = WorldFormats.adtGrid(input.bytes(), token::isCanceled);
            boolean canonical = grid.chunks().size() == 256;
            for (var chunk : grid.chunks()) {
                canonical &= chunk.ordinal() == chunk.y() * 16 + chunk.x();
            }
            String geometry =
                    canonical
                            ? TerrainGeometryFingerprint.sha256(
                                    admission.recorded().x(), admission.recorded().y(), grid)
                            : null;
            float[] anchors = null;
            if (canonical) {
                anchors = new float[512];
                for (var chunk : grid.chunks()) {
                    int offset = (chunk.y() * 16 + chunk.x()) * 2;
                    anchors[offset] = chunk.originX();
                    anchors[offset + 1] = chunk.originY();
                }
            }
            WorldLiquids.RawLiquids raw;
            try {
                raw = WorldLiquids.readMh2o(input.bytes(), token::isCanceled);
            } catch (WorldLiquids.FormatException malformed) {
                return new Root(
                        null,
                        input.fileDataId(),
                        input.contentKey(),
                        input.consumedLength(),
                        geometry,
                        true,
                        anchors,
                        token,
                        new WorldTerrainTextureSource.FileBinding(
                                input.fileDataId(),
                                input.contentKey(),
                                input.consumedLength(),
                                input.declaredLength(),
                                input.sourceLimit()));
            }
            return new Root(
                    raw,
                    input.fileDataId(),
                    input.contentKey(),
                    input.consumedLength(),
                    geometry,
                    false,
                    anchors,
                    token,
                    new WorldTerrainTextureSource.FileBinding(
                            input.fileDataId(),
                            input.contentKey(),
                            input.consumedLength(),
                            input.declaredLength(),
                            input.sourceLimit()));
        }
    }

    private void populate(Owner output, WorldStreamScopes.TileAdmission admission) {
        try {
            populateActual(output, admission);
        } catch (WorldLiquidTables.CleanupFailure failure) {
            admission.liquidCleanupFailed(failure);
            throw failure;
        }
    }

    private void populateActual(Owner output, WorldStreamScopes.TileAdmission admission) {
        var root = root(admission);
        var raw = root.raw();
        var token = admission.cancellation();
        if (root.malformed()) {
            set(output, root, State.UNRESOLVED, Reason.MALFORMED, 0, List.of(), 0);
            return;
        }
        if (raw.presence() == WorldLiquids.Presence.ABSENT) {
            set(output, root, State.ABSENT, Reason.NONE, 0, List.of(), 0);
            return;
        }
        if (raw.layers().isEmpty()) {
            set(output, root, State.EMPTY, Reason.NONE, 0, List.of(), 0);
            return;
        }
        if (root.geometry() == null) {
            set(
                    output,
                    root,
                    State.UNRESOLVED,
                    Reason.GRID_PROFILE,
                    raw.layers().size(),
                    List.of(),
                    0);
            return;
        }
        var selected = new java.util.HashSet<Long>();
        for (var layer : raw.layers()) {
            // LiquidType 2 is depth-only by rule and needs no table row.
            if (layer.secondary() >= 42 && layer.liquidType() != 2) {
                selected.add((long) layer.liquidType());
            }
        }
        WorldLiquidTables.Observed types = null, materials = null, failedContext = null;
        // Direct format 1/3 LiquidType rows, one lookup per type, and those lookups' proofs.
        var directTypes = new java.util.HashMap<Long, WorldLiquidTables.Observed>();
        var directProofs = new ArrayList<WorldLiquidTables.Observed>();
        var oceanProof = new WorldLiquidTables.Observed[2];
        try {
            if (!selected.isEmpty()) {
                types = tables.observe(WorldLiquidTables.Profile.TYPE, selected, token);
                var ids = new java.util.HashSet<Long>();
                for (long id : types.ids()) {
                    ids.add((long) types.value(id));
                }
                materials = tables.observe(WorldLiquidTables.Profile.MATERIAL, ids, token);
            }
            // A direct format 1 or 3 layer (secondary below 42) is coloured by its own LiquidType
            // row. Each such type is looked up on its own, so a missing row leaves only the layers
            // of that type undrawn: never another type's layers, nor the tile's water. At most
            // MAX_DIRECT_TYPES are looked up; layers of further types stay undrawn.
            var direct = new java.util.TreeSet<Long>();
            for (var l : raw.layers()) {
                long type = l.liquidType();
                if ((l.secondary() == 1 || l.secondary() == 3)
                        && (types == null || !types.ids().contains(type))) {
                    direct.add(type);
                }
            }
            for (long type : direct) {
                if (directProofs.size() == MAX_DIRECT_TYPES) {
                    break;
                }
                try {
                    var row =
                            tables.observe(
                                    WorldLiquidTables.Profile.TYPE, java.util.Set.of(type), token);
                    directTypes.put(type, row);
                    directProofs.add(row);
                } catch (WorldLiquidTables.Unavailable missing) {
                    if (missing.reason == WorldLiquidTables.Reason.SOURCE_CHANGED) {
                        throw new Changed();
                    }
                    // The negative result stays tied to unchanged source, like the main path's.
                    if (missing.actualContext() != null) {
                        directProofs.add(missing.actualContext());
                    }
                }
            }
            // Each layer's vertex format follows the established extractor convention: a secondary
            // value below 42 is the format itself; LiquidType 2 with a LiquidObject is depth-only
            // (format 2) without a table lookup; any other type takes its qualified
            // LiquidType -> LiquidMaterial LVF. Formats 0 (height and depth) and 2 (depth only)
            // are water. Formats 1 and 3 (magma, slime, tar) are drawn in their LiquidType's flat
            // colour when that row was observed; otherwise they stay unsupported.
            var formats = new int[raw.layers().size()];
            var supported = new boolean[raw.layers().size()];
            var colours = new int[raw.layers().size()];
            boolean unknown = false;
            for (var l : raw.layers()) {
                token.check();
                int format =
                        l.secondary() < 42
                                ? l.secondary()
                                : l.liquidType() == 2
                                        ? 2
                                        : materials != null
                                                ? materials.value(types.value(l.liquidType()))
                                                : -1;
                formats[l.ordinal()] = format;
                var rows =
                        types != null && types.ids().contains((long) l.liquidType())
                                ? types
                                : directTypes.get((long) l.liquidType());
                boolean coloured = (format == 1 || format == 3) && rows != null;
                supported[l.ordinal()] = format == 0 || format == 2 || coloured;
                colours[l.ordinal()] =
                        coloured ? rows.minimapColour(l.liquidType()) & 0xFFFFFF : -1;
                // Every known format's stream length is checked for overlaps, drawn or not, so an
                // undrawn instance never hides the tile.
                if (stride(format) == 0 && l.vertexOffset() != 0) {
                    unknown = true;
                }
            }
            output.issues = oceanConvention(raw, token, oceanProof);
            if (unknown) {
                set(
                        output,
                        root,
                        State.UNRESOLVED,
                        Reason.UNRESOLVED_FOOTPRINT,
                        raw.layers().size(),
                        List.of(),
                        0);
            } else {
                // All nonzero streams have a known footprint. A pair of overlapping streams
                // refuses both instances; the rest of the tile still decodes.
                var refused = validateKnownStreams(raw, supported, formats);
                decode(output, root, supported, formats, colours, refused, token);
            }
        } catch (WorldLiquidTables.Unavailable optional) {
            if (optional.reason == WorldLiquidTables.Reason.SOURCE_CHANGED) {
                throw new Changed();
            }
            failedContext = optional.actualContext();
            set(
                    output,
                    root,
                    State.UNRESOLVED,
                    Reason.valueOf(optional.reason.name()),
                    raw.layers().size(),
                    List.of(),
                    0);
        } catch (WorldLiquids.FormatException malformed) {
            set(
                    output,
                    root,
                    State.UNRESOLVED,
                    Reason.MALFORMED,
                    raw.layers().size(),
                    List.of(),
                    0);
        }
        var observations = new ArrayList<WorldLiquidTables.Observed>(3);
        var proofs =
                new ArrayList<WorldLiquidTables.Observed>(
                        java.util.Arrays.asList(
                                types, materials, failedContext, oceanProof[0], oceanProof[1]));
        proofs.addAll(directProofs);
        // Even known-negative selected-row observations must stay tied to unchanged source.
        for (var proof : proofs) {
            if (proof == null) {
                continue;
            }
            try {
                tables.revalidate(proof, token);
            } catch (WorldLiquidTables.Unavailable stale) {
                throw new Changed();
            }
            admission.rememberLiquidTable(proof);
            observations.add(proof);
        }
        output.observations = List.copyOf(observations);
        output.tableFacts =
                observations.stream()
                        .map(
                                o ->
                                        new TableFact(
                                                o.sourceId(),
                                                o.sourceKey(),
                                                o.sourceLength(),
                                                o.receiptSha()))
                        .toList();
    }

    /**
     * LiquidType 2 is drawn depth-only by convention. When this source's own LiquidType row 2
     * resolves another format, that disagreement is recorded as an issue, not obeyed or hidden. A
     * missing or unreadable row is no disagreement.
     */
    private List<String> oceanConvention(
            WorldLiquids.RawLiquids raw, Cancellation token, WorldLiquidTables.Observed[] proof) {
        boolean ocean = false;
        for (var layer : raw.layers()) {
            ocean |= layer.secondary() >= 42 && layer.liquidType() == 2;
        }
        if (!ocean) {
            return List.of();
        }
        try {
            var type = tables.observe(WorldLiquidTables.Profile.TYPE, java.util.Set.of(2L), token);
            proof[0] = type;
            if (!type.ids().contains(2L)) {
                return List.of();
            }
            long material = type.value(2);
            var materials =
                    tables.observe(
                            WorldLiquidTables.Profile.MATERIAL, java.util.Set.of(material), token);
            proof[1] = materials;
            int format = materials.value(material);
            return format == 2
                    ? List.of()
                    : List.of(
                            "LiquidType 2 resolves LiquidMaterial "
                                    + material
                                    + " with vertex format "
                                    + format
                                    + "; drawn depth-only by the established extractor"
                                    + " convention.");
        } catch (WorldLiquidTables.Unavailable unreadable) {
            // A changed source must restart the inspection, never pass as "no disagreement".
            if (unreadable.reason == WorldLiquidTables.Reason.SOURCE_CHANGED) {
                throw new Changed();
            }
            return List.of();
        }
    }

    /**
     * Bytes per vertex of a known format: height and depth (0: 5), height and texture coordinates
     * (1: 8), depth only (2: 1), height, texture coordinates and depth (3: 9); 0 when the format is
     * unknown.
     */
    private static long stride(int format) {
        return switch (format) {
            case 0 -> 5;
            case 1 -> 8;
            case 2 -> 1;
            case 3 -> 9;
            default -> 0;
        };
    }

    private static boolean[] validateKnownStreams(
            WorldLiquids.RawLiquids raw, boolean[] supported, int[] formats) {
        var refused = new boolean[raw.layers().size()];
        for (int i = 0; i < raw.layers().size(); i++) {
            var a = raw.layers().get(i);
            if (stride(formats[i]) == 0 || a.vertexOffset() == 0) {
                continue;
            }
            long end = a.vertexOffset() + (a.width() + 1L) * (a.height() + 1L) * stride(formats[i]);
            if (end > raw.payloadBytes()) {
                // A known-length stream that runs past the payload refuses its own instance.
                refused[i] = true;
                continue;
            }
            for (int j = 0; j < i; j++) {
                var b = raw.layers().get(j);
                if (stride(formats[j]) == 0 || b.vertexOffset() == 0) {
                    continue;
                }
                long otherEnd =
                        b.vertexOffset()
                                + (b.width() + 1L) * (b.height() + 1L) * stride(formats[j]);
                if (a.vertexOffset() < otherEnd && b.vertexOffset() < end) {
                    refused[i] = true;
                    refused[j] = true;
                }
            }
        }
        return refused;
    }

    private static void decode(
            Owner output,
            Root root,
            boolean[] supported,
            int[] formats,
            int[] colours,
            boolean[] refused,
            Cancellation token) {
        var result = new ArrayList<Layer>();
        int unresolved = 0, cells = 0, samples = 0, declaredLevels = 0, malformed = 0;
        Reason reason = Reason.NONE;
        for (var layer : root.raw().layers()) {
            token.check();
            // A malformed instance is reported as such even when its format is unsupported.
            if (refused[layer.ordinal()]) {
                unresolved++;
                malformed++;
                reason = Reason.MALFORMED;
                continue;
            }
            if (!supported[layer.ordinal()]) {
                unresolved++;
                reason = Reason.UNSUPPORTED_LAYOUT;
                continue;
            }
            WorldLiquids.DecodedLayer d;
            int format = formats[layer.ordinal()];
            if (format != 2 && layer.vertexOffset() != 0) {
                try {
                    d =
                            WorldLiquids.decodeHeights(
                                    root.raw(), layer.ordinal(), format, token::isCanceled);
                } catch (WorldLiquids.FormatException bad) {
                    // A bad instance (aliased stream, used height outside its bounds) is
                    // refused alone.
                    unresolved++;
                    malformed++;
                    reason = Reason.MALFORMED;
                    continue;
                }
            } else {
                // No vertex heights (no vertex data, or depth only): the declared height, which
                // must be a single value.
                try {
                    d =
                            WorldLiquids.decodeDeclaredHeight(
                                    root.raw(),
                                    layer.ordinal(),
                                    formats[layer.ordinal()] == 2,
                                    token::isCanceled);
                } catch (UnsupportedOperationException range) {
                    unresolved++;
                    reason = Reason.VERTEX_ABSENT;
                    continue;
                } catch (WorldLiquids.FormatException bad) {
                    unresolved++;
                    malformed++;
                    reason = Reason.MALFORMED;
                    continue;
                }
                // The extractor convention would place this layer at 0; the source wins.
                if (layer.minHeight() != 0) {
                    declaredLevels++;
                }
            }
            long mask = d.presentCells();
            if (mask == 0) {
                continue;
            }
            int vertices = (layer.width() + 1) * (layer.height() + 1);
            var heights = new float[vertices];
            d.heights().get(heights);
            Float first = null;
            boolean planar = true;
            for (int v = 0; v < vertices; v++) {
                // Source signed-zero policy: -0 and +0 are one height.
                heights[v] = heights[v] == 0 ? 0f : heights[v];
            }
            for (int c = 0; c < layer.width() * layer.height(); c++) {
                if ((mask & (1L << c)) != 0) {
                    int start = c / layer.width() * (layer.width() + 1) + c % layer.width();
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            float h = heights[start + dy * (layer.width() + 1) + dx];
                            if (first == null) {
                                first = h;
                            } else if (first.floatValue() != h) {
                                planar = false;
                            }
                        }
                    }
                }
            }
            int count = Long.bitCount(mask);
            if (result.size() == MAX_LAYERS
                    || cells + count > MAX_CELLS
                    || samples + vertices > MAX_SAMPLES) {
                unresolved++;
                reason = Reason.CAPPED;
                continue;
            }
            // Reserve all per-owner counters before allocating the detached depth output.
            cells += count;
            samples += vertices;
            var depth = new byte[vertices];
            d.depths().get(depth);
            result.add(
                    new Layer(
                            layer.headerOrdinal(),
                            layer.instanceOrdinal(),
                            layer.liquidType(),
                            layer.secondary(),
                            layer.x(),
                            layer.y(),
                            layer.width(),
                            layer.height(),
                            mask,
                            planar,
                            heights,
                            depth,
                            colours[layer.ordinal()]));
        }
        State state =
                result.isEmpty()
                        ? (unresolved == 0 ? State.EMPTY : State.UNRESOLVED)
                        : (unresolved == 0 ? State.READY : State.PARTIAL);
        set(output, root, state, reason, unresolved, result, cells);
        if (malformed > 0) {
            var issues = new ArrayList<>(output.issues);
            issues.add(
                    malformed
                            + " malformed liquid instance(s) refused; the rest of the tile kept.");
            output.issues = List.copyOf(issues);
        }
        if (declaredLevels > 0) {
            var issues = new ArrayList<>(output.issues);
            issues.add(
                    declaredLevels
                            + " layer(s) without vertex heights are drawn at their declared"
                            + " level; the established extractor convention would place them"
                            + " at 0.");
            output.issues = List.copyOf(issues);
        }
    }

    private static void set(
            Owner owner,
            Root root,
            State state,
            Reason reason,
            int unresolved,
            List<Layer> layers,
            int cells) {
        owner.layers = List.copyOf(layers);
        owner.rootBinding = root.binding();
        geometry(owner, root, cells);
        owner.summary =
                new Summary(
                        state,
                        reason,
                        root.raw() == null ? -1 : root.raw().layers().size(),
                        layers.size(),
                        root.raw() == null ? -1 : unresolved,
                        cells,
                        root.id(),
                        root.key(),
                        root.length(),
                        root.geometry());
    }

    private static void geometry(Owner owner, Root root, int cells) {
        if (cells < 0 || cells > MAX_CELLS || owner.layers.size() > MAX_LAYERS) {
            throw new IllegalStateException("Liquid geometry count profile");
        }
        if (cells == 0) {
            return;
        }
        if (root.anchors() == null || root.geometry() == null) {
            throw new IllegalStateException("Liquid geometry lacks actual ROOT anchors");
        }
        int vertices = Math.multiplyExact(cells, 4), indexCount = Math.multiplyExact(cells, 6);
        owner.positions = new float[Math.multiplyExact(vertices, 3)];
        owner.depths = new byte[vertices];
        owner.indices = new int[indexCount];
        int vertex = 0, index = 0;
        for (var layer : owner.layers) {
            root.token().check();
            float north = root.anchors()[layer.header() * 2];
            float west = root.anchors()[layer.header() * 2 + 1];
            for (int y = 0; y < layer.height(); y++) {
                for (int x = 0; x < layer.width(); x++) {
                    if ((layer.mask() & (1L << (y * layer.width() + x))) == 0) {
                        continue;
                    }
                    root.token().check();
                    int base = vertex;
                    for (int corner = 0; corner < 4; corner++) {
                        int row = y + corner / 2, column = x + corner % 2;
                        int offset = vertex * 3;
                        owner.positions[offset] =
                                (float)
                                        (north
                                                - (2 * (layer.y() + row))
                                                        * (WorldFormats.TILE_SIZE / 256));
                        owner.positions[offset + 1] =
                                (float)
                                        (west
                                                - (layer.x() + column)
                                                        * (WorldFormats.TILE_SIZE / 128));
                        owner.positions[offset + 2] =
                                layer.heights()[row * (layer.width() + 1) + column];
                        owner.depths[vertex] = layer.depth()[row * (layer.width() + 1) + column];
                        vertex++;
                    }
                    // Explicit NE--SW diagonal; winding is +nativeZ for native north/west axes. For
                    // a nonplanar cell this diagonal is a renderer convention, not a source fact.
                    owner.indices[index++] = base;
                    owner.indices[index++] = base + 2;
                    owner.indices[index++] = base + 1;
                    owner.indices[index++] = base + 1;
                    owner.indices[index++] = base + 2;
                    owner.indices[index++] = base + 3;
                }
            }
        }
        if (vertex != vertices || index != indexCount) {
            throw new IllegalStateException("Liquid geometry counts changed");
        }
    }
}
