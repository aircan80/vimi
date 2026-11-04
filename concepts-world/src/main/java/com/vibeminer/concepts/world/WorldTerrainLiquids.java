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

/** Single-root MH2O source-qualified LOCAL cell facts only. No native XY transform or drawing. */
final class WorldTerrainLiquids {
    static final String REVISION = "source-mh2o-local-root-inspection-v1";

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
        NONPLANAR_PROFILE,
        MULTILAYER,
        CAPPED,
        MALFORMED
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
            float usedHeight,
            byte[] depth) {}

    @FunctionalInterface
    interface Read<T> {
        T read(Borrow borrow);
    }

    static final class Owner implements AutoCloseable {
        private WorldStreamScopes.LiquidCredit credit;
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private Summary summary;
        private List<Layer> layers = List.of();
        private int pins = 1;
        private boolean closing, qualified;

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
            synchronized (this) {
                if (!closing || pins != 0 || credit == null) {
                    return;
                }
                layers = List.of();
                source = credit;
                credit = null;
            }
            source.retired();
            drained.complete(null);
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

        float actualUsedHeight(int ordinal) {
            return layer(ordinal).usedHeight();
        }

        int depth(int ordinal, int vertex) {
            var l = layer(ordinal);
            return l.depth()[Objects.checkIndex(vertex, l.depth().length)] & 255;
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
                owner.releasePin();
            }
        }
    }

    private record Root(
            WorldLiquids.RawLiquids raw,
            long id,
            String key,
            long length,
            String geometry,
            boolean malformed) {}

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
                        true);
            }
            return new Root(
                    raw,
                    input.fileDataId(),
                    input.contentKey(),
                    input.consumedLength(),
                    geometry,
                    false);
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
            if (layer.secondary() >= 42 && layer.liquidType() != 2) {
                selected.add((long) layer.liquidType());
            }
        }
        WorldLiquidTables.Observed types = null, materials = null, failedContext = null;
        try {
            if (!selected.isEmpty()) {
                types = tables.observe(WorldLiquidTables.Profile.TYPE, selected, token);
                var ids = new java.util.HashSet<Long>();
                for (long id : types.ids()) {
                    ids.add((long) types.value(id));
                }
                materials = tables.observe(WorldLiquidTables.Profile.MATERIAL, ids, token);
            }
            var supported = new boolean[raw.layers().size()];
            boolean unknown = false;
            for (var l : raw.layers()) {
                token.check();
                supported[l.ordinal()] =
                        l.liquidType() != 2
                                && (l.secondary() == 0
                                        || l.secondary() >= 42
                                                && materials != null
                                                && materials.value(types.value(l.liquidType()))
                                                        == 0);
                if (!supported[l.ordinal()] && l.vertexOffset() != 0) {
                    unknown = true;
                }
            }
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
                // All nonzero streams have known LVF0 footprint. Cross-layer aliases cannot hide
                // behind the low-level decoder's unresolved-format boundary.
                validateKnownStreams(raw, supported);
                decode(output, root, supported, token);
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
        } catch (WorldLiquids.FormatException | Malformed malformed) {
            set(
                    output,
                    root,
                    State.UNRESOLVED,
                    Reason.MALFORMED,
                    raw.layers().size(),
                    List.of(),
                    0);
        }
        // Even known-negative selected-row observations must stay tied to unchanged source.
        for (var proof : new WorldLiquidTables.Observed[] {types, materials, failedContext}) {
            if (proof == null) {
                continue;
            }
            try {
                tables.revalidate(proof, token);
            } catch (WorldLiquidTables.Unavailable stale) {
                throw new Changed();
            }
            admission.rememberLiquidTable(proof);
        }
    }

    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private Malformed() {
            super("Qualified liquid stream overlap", null);
        }
    }

    private static void validateKnownStreams(WorldLiquids.RawLiquids raw, boolean[] supported) {
        for (int i = 0; i < raw.layers().size(); i++) {
            var a = raw.layers().get(i);
            if (!supported[i] || a.vertexOffset() == 0) {
                continue;
            }
            long end = a.vertexOffset() + (a.width() + 1L) * (a.height() + 1L) * 5;
            for (int j = 0; j < i; j++) {
                var b = raw.layers().get(j);
                if (!supported[j] || b.vertexOffset() == 0) {
                    continue;
                }
                long otherEnd = b.vertexOffset() + (b.width() + 1L) * (b.height() + 1L) * 5;
                if (a.vertexOffset() < otherEnd && b.vertexOffset() < end) {
                    throw new Malformed();
                }
            }
        }
    }

    private static void decode(Owner output, Root root, boolean[] supported, Cancellation token) {
        var result = new ArrayList<Layer>();
        var occupancy = new long[256];
        int unresolved = 0, cells = 0, samples = 0;
        Reason reason = Reason.NONE;
        // Decline every populated layer in a multiply-overlapping header, never pick a winner.
        var overlap = new boolean[256];
        for (var layer : root.raw().layers()) {
            token.check();
            if (!supported[layer.ordinal()] || layer.vertexOffset() == 0) {
                continue;
            }
            var d = WorldLiquids.decodeHeightDepth0(root.raw(), layer.ordinal(), token::isCanceled);
            long mask = headerMask(layer, d.presentCells());
            if ((occupancy[layer.headerOrdinal()] & mask) != 0) {
                overlap[layer.headerOrdinal()] = true;
            }
            occupancy[layer.headerOrdinal()] |= mask;
        }
        for (var layer : root.raw().layers()) {
            token.check();
            if (!supported[layer.ordinal()] || layer.vertexOffset() == 0) {
                unresolved++;
                reason =
                        supported[layer.ordinal()]
                                ? Reason.VERTEX_ABSENT
                                : Reason.UNSUPPORTED_LAYOUT;
                continue;
            }
            var d = WorldLiquids.decodeHeightDepth0(root.raw(), layer.ordinal(), token::isCanceled);
            if (overlap[layer.headerOrdinal()]) {
                unresolved++;
                reason = Reason.MULTILAYER;
                continue;
            }
            long mask = d.presentCells();
            if (mask == 0) {
                continue;
            }
            Float constant = null;
            boolean same = true;
            var heights = d.heights();
            for (int c = 0; c < layer.width() * layer.height(); c++) {
                if ((mask & (1L << c)) != 0) {
                    int start = c / layer.width() * (layer.width() + 1) + c % layer.width();
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            float h = heights.get(start + dy * (layer.width() + 1) + dx);
                            h = h == 0 ? 0f : h;
                            if (constant == null) {
                                constant = h;
                            } else if (constant.floatValue() != h) {
                                same = false;
                            }
                        }
                    }
                }
            }
            if (!same) {
                unresolved++;
                reason = Reason.NONPLANAR_PROFILE;
                continue;
            }
            int count = Long.bitCount(mask), vertices = (layer.width() + 1) * (layer.height() + 1);
            if (result.size() == 128 || cells + count > 8192 || samples + vertices > 10368) {
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
                            Objects.requireNonNull(constant),
                            depth));
        }
        State state =
                result.isEmpty()
                        ? (unresolved == 0 ? State.EMPTY : State.UNRESOLVED)
                        : (unresolved == 0 ? State.READY : State.PARTIAL);
        set(output, root, state, reason, unresolved, result, cells);
    }

    private static long headerMask(WorldLiquids.RawLayer layer, long mask) {
        long result = 0;
        for (int row = 0; row < layer.height(); row++) {
            for (int col = 0; col < layer.width(); col++) {
                if ((mask & (1L << (row * layer.width() + col))) != 0) {
                    result |= 1L << ((layer.y() + row) * 8 + layer.x() + col);
                }
            }
        }
        return result;
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
}
