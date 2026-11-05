package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldExteriorObjects;
import com.vibeminer.concepts.world.WorldTerrainStreaming;
import com.vibeminer.concepts.world.WorldWaterPreview;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTileWriter;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainTileAtlas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Closed source-to-packet path. All preparation, move and cleanup run off the FX thread. */
final class WorldStreamSourceBridge {
    private WorldStreamSourceBridge() {}

    /** One source base owner, shared by controller residency metadata rather than retained again. */
    static final class Prepared implements AutoCloseable {
        private final WorldTerrainStreaming.Facts facts;
        private WorldTerrainStreaming.WrittenTile<WorldStreamTileWriter.PacketFile> owned;
        private volatile int waterCells, exteriorPlacements;
        private volatile boolean exteriorFailed;

        /** Descriptive: this tile's exterior objects failed on the parent side; terrain kept. */
        boolean exteriorFailed() {
            return exteriorFailed;
        }

        /** Descriptive count of static water cells carried by this tile's moved packet. */
        int waterCells() {
            return waterCells;
        }

        /** Descriptive count of exterior placements carried by this tile's objects file. */
        int exteriorPlacements() {
            return exteriorPlacements;
        }

        private Prepared(WorldTerrainStreaming.WrittenTile<WorldStreamTileWriter.PacketFile> owned) {
            this.owned = owned;
            facts = owned.facts();
        }

        WorldTerrainStreaming.Facts facts() {
            return facts;
        }

        synchronized boolean isOpen() {
            return owned != null && owned.isOpen();
        }

        void withPayload(Consumer<WorldTerrainStreaming.CheckedBorrow> use) {
            WorldTerrainStreaming.WrittenTile<WorldStreamTileWriter.PacketFile> value;
            synchronized (this) {
                value = owned;
                if (value == null) {
                    throw new IllegalStateException("Source resident retired");
                }
            }
            value.withPayload(use);
        }

        @Override
        public void close() {
            WorldTerrainStreaming.WrittenTile<WorldStreamTileWriter.PacketFile> value;
            synchronized (this) {
                value = owned;
                owned = null;
            }
            if (value != null) {
                value.close();
            }
        }
    }

    /** Finite source-origin facts only; descriptive policy metadata, never source authority. */
    static final class RefusalMetadata {
        private final WorldTerrainStreaming.Refusal.Reason reason;
        private final String site;
        private RefusalMetadata(WorldTerrainStreaming.Refusal refused) {
            reason = refused.reason();
            site = refused.site();
        }
        WorldTerrainStreaming.Refusal.Reason reason() { return reason; }
        String site() { return site; }
    }

    /** The exterior object source operation of one producer; returns null for terrain only. */
    @FunctionalInterface
    interface ExteriorPreparer {
        WorldStreamTileWriter.Exterior prepare(Cancellation cancellation);
    }

    /** Single-use producer. Close retires an untaken base; take only after transport success. */
    static final class Producer implements WorldStreamTransport.PacketProducer, AutoCloseable {
        private final WorldTerrainStreaming source;
        private final WorldWaterPreview water;
        private final WorldTerrainStreaming.Scope scope;
        private final WorldFormats.Tile tile;
        private final BooleanSupplier latest;
        private boolean started, closed;
        private Prepared prepared;
        private volatile RefusalMetadata sourceRefusal;
        private volatile String waterNote = "", exteriorNote = "";
        private volatile boolean exteriorFailed;
        private final boolean exteriors;
        private final ExteriorPreparer exteriorPreparer;

        Producer(WorldTerrainStreaming source, WorldTerrainStreaming.Scope scope,
                WorldFormats.Tile tile, BooleanSupplier latest) {
            this(source, null, scope, tile, latest);
        }

        /** Optional water joins this tile's actual terrain owner before its packet MOVE. */
        Producer(WorldTerrainStreaming source, WorldWaterPreview water,
                WorldTerrainStreaming.Scope scope, WorldFormats.Tile tile, BooleanSupplier latest) {
            this(source, water, false, scope, tile, latest);
        }

        /**
         * With exteriors, a BASIC_EXTERIOR_V1 object set is prepared first as its own serialized
         * source operation and published as this tile's objects file, which the terrain header
         * then names. Exterior failures never remove terrain.
         */
        Producer(WorldTerrainStreaming source, WorldWaterPreview water, boolean exteriors,
                WorldTerrainStreaming.Scope scope, WorldFormats.Tile tile, BooleanSupplier latest) {
            this(source, water, exteriors, scope, tile, latest, null);
        }

        /** Tests replace the exterior source operation; {@code null} keeps the actual one. */
        Producer(WorldTerrainStreaming source, WorldWaterPreview water, boolean exteriors,
                WorldTerrainStreaming.Scope scope, WorldFormats.Tile tile, BooleanSupplier latest,
                ExteriorPreparer exteriorPreparer) {
            this.exteriors = exteriors;
            this.exteriorPreparer = exteriorPreparer == null ? this::prepareExterior : exteriorPreparer;
            this.source = Objects.requireNonNull(source);
            this.water = water;
            this.scope = Objects.requireNonNull(scope);
            this.tile = Objects.requireNonNull(tile);
            this.latest = Objects.requireNonNull(latest);
        }

        /** Descriptive bounded water outcome for diagnostics; never source authority. */
        String waterNote() {
            return waterNote;
        }

        /** Descriptive bounded exterior outcome for diagnostics; never source authority. */
        String exteriorNote() {
            return exteriorNote;
        }

        @Override
        public WorldStreamTransport.OwnedPacket prepare(
                WorldStreamTransport.PacketDestination destination, BooleanSupplier stopping)
                throws Exception {
            synchronized (this) {
                if (started || closed) {
                    throw new IllegalStateException("Source producer already used or retired");
                }
                started = true;
            }
            var cancellation = new Cancellation();
            Prepared local = null;
            var moved = new WorldStreamTransport.OwnedPacket[1];
            Throwable failure = null;
            var registration = destination.onStopping(cancellation::cancel);
            WorldStreamTileWriter.ObjectsFile objects = null;
            int placements = 0;
            try {
                check(stopping, cancellation);
                if (exteriors && destination.exteriorObjects()) {
                    var exterior = exteriorPreparer.prepare(cancellation);
                    check(stopping, cancellation);
                    if (exterior != null) {
                        try {
                            objects = WorldStreamTileWriter.writeObjectsUnadvertised(destination,
                                    exterior, () -> stopping.getAsBoolean() || cancellation.isCanceled());
                            placements = exterior.placements();
                        } catch (IOException | RuntimeException failed) {
                            check(stopping, cancellation);
                            if (failed instanceof CancellationException canceled) {
                                throw canceled;
                            }
                            exteriorFailed = true;
                            exteriorNote = "exterior objects write failed";
                        }
                    }
                }
                var named = objects;
                var written = source.prepareAndWrite(scope, tile, cancellation,
                        (borrow, token) -> write(destination, borrow, named,
                                () -> stopping.getAsBoolean() || token.isCanceled()));
                local = new Prepared(written);
                local.exteriorPlacements = placements;
                local.exteriorFailed = exteriorFailed;
                check(stopping, cancellation);
                // Water reports its own cancellation; the MOVE below observes stopping too.
                var waterCopy = water == null ? null : prepareWater(written, cancellation);
                BooleanSupplier stop = () -> stopping.getAsBoolean() || cancellation.isCanceled();
                var attached = new boolean[1];
                written.withWrittenResource(file -> {
                    moved[0] = file.move(waterCopy, stop);
                    attached[0] = file.waterAttached();
                });
                if (waterCopy != null) {
                    if (attached[0]) {
                        local.waterCells = waterCopy.cells();
                    } else {
                        waterNote = "water packet append rolled back";
                    }
                }
                check(stopping, cancellation);
                // The actual source gate distinguishes known retirement from sticky cleanup faults.
                // R is inert after MOVE; this callback only checks/pins the source lifetime.
                written.withWrittenResource(ignored -> {});
                registration.close();
                registration = null;
                check(stopping, cancellation);
                synchronized (this) {
                    if (closed) {
                        throw new CancellationException();
                    }
                    prepared = local;
                    local = null;
                }
                var packet = moved[0];
                moved[0] = null;
                return packet;
            } catch (WorldTerrainStreaming.Refusal refused) {
                // Only clean origin-qualified source refusals cross as scalar transport policy.
                if (refused.getCause() != null || refused.getSuppressed().length != 0) {
                    failure = refused;
                    throw refused;
                }
                sourceRefusal = new RefusalMetadata(refused);
                var mapped = destination.refusal(
                        WorldStreamTransport.Refusal.valueOf(refused.reason().name()), refused.site());
                failure = mapped;
                throw mapped;
            } catch (Exception | Error problem) {
                failure = problem;
                throw problem;
            } finally {
                // Independent owners: failure closing the moved file cannot skip the source base.
                Throwable cleanup = null;
                if (registration != null) {
                    cleanup = WorldStreamSourceBridge.close(registration, cleanup);
                }
                if (moved[0] != null) {
                    cleanup = WorldStreamSourceBridge.close(moved[0], cleanup);
                }
                if (local != null) {
                    cleanup = WorldStreamSourceBridge.close(local, cleanup);
                }
                if (objects != null) {
                    // Inert once the terrain packet adopted it; otherwise deletes the file.
                    cleanup = WorldStreamSourceBridge.close(objects, cleanup);
                }
                if (cleanup != null) {
                    sourceRefusal = null;
                    if (failure != null) {
                        cleanup.addSuppressed(failure);
                    }
                    throw new IllegalStateException("Source packet cleanup failed", cleanup);
                }
            }
        }

        /**
         * Copies one actual Source water preparation for this exact terrain owner and retires the
         * water owner immediately; no water Source credit outlives this call. Optional: clean
         * refusals and unexpected water failures leave terrain intact. Cancellation propagates.
         */
        private WorldStreamTileWriter.Water prepareWater(
                WorldTerrainStreaming.WrittenTile<WorldStreamTileWriter.PacketFile> written,
                Cancellation cancellation) {
            try (var prepared = water.prepareAndWrite(written, cancellation, WaterCopy::of)) {
                var facts = prepared.facts();
                if (!(prepared instanceof WorldWaterPreview.WrittenWater<WaterCopy> positive)) {
                    waterNote = "no water surface: " + facts.state();
                    return null;
                }
                var copy = new WorldStreamTileWriter.Water[1];
                positive.withWrittenResource(r -> copy[0] = r.water());
                waterNote = "water " + facts.state() + " cells=" + facts.cells();
                return copy[0];
            } catch (CancellationException canceled) {
                throw canceled;
            } catch (WorldTerrainStreaming.Refusal refused) {
                if (refused.getCause() != null || refused.getSuppressed().length != 0) {
                    throw refused;
                }
                waterNote = "water refused: " + refused.site();
                return null;
            } catch (IllegalArgumentException | IllegalStateException failed) {
                // Only a clean leaf failure (e.g. this bridge's own copy validation) degrades to
                // terrain-only. Source cleanup/health faults carry causes or suppressed failures
                // and stay fatal.
                if (failed.getCause() != null || failed.getSuppressed().length != 0) {
                    throw failed;
                }
                waterNote = "water failed: " + failed.getClass().getSimpleName();
                return null;
            }
        }

        /**
         * One serialized BASIC_EXTERIOR source operation outside any terrain write. The neutral set
         * is copied and closed here. Only cancellation propagates: every other runtime failure
         * degrades this tile to terrain only, because the terrain write that follows re-checks the
         * source and fails by itself when the source is really broken.
         */
        private WorldStreamTileWriter.Exterior prepareExterior(Cancellation cancellation) {
            try (var set = source.prepareExteriorObjects(scope, tile, cancellation)) {
                int drawn = set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN);
                int omitted = set.count(WorldExteriorObjects.Count.PLACEMENT_UNSUPPORTED)
                        + set.count(WorldExteriorObjects.Count.PLACEMENT_CONFLICTING)
                        + set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED)
                        + set.count(WorldExteriorObjects.Count.PLACEMENT_FAILED_ROOT)
                        + set.count(WorldExteriorObjects.Count.PLACEMENT_EMPTY);
                exteriorNote = "exterior placements drawn=" + drawn + " omitted=" + omitted;
                if (set.placements().isEmpty()) {
                    return null;
                }
                return copy(set, cancellation);
            } catch (CancellationException canceled) {
                throw canceled;
            } catch (WorldTerrainStreaming.Refusal refused) {
                cancellation.check();
                exteriorFailed = true;
                exteriorNote = "exterior objects refused: " + refused.site();
                return null;
            } catch (RuntimeException failed) {
                cancellation.check();
                exteriorFailed = true;
                exteriorNote = "exterior objects failed: " + failed.getClass().getSimpleName();
                return null;
            }
        }

        private static WorldStreamTileWriter.Exterior copy(
                WorldExteriorObjects.TileObjects set, Cancellation cancellation) {
            var builder = WorldStreamTileWriter.Exterior.builder();
            for (var image : set.images()) {
                cancellation.check();
                builder.image(image.width(), image.height(), image.rgba());
            }
            for (var model : set.models()) {
                builder.model();
                for (var mesh : model.meshes()) {
                    cancellation.check();
                    var ranges = new int[mesh.ranges().size() * 3];
                    for (int r = 0; r < mesh.ranges().size(); r++) {
                        var range = mesh.ranges().get(r);
                        ranges[r * 3] = range.firstIndex();
                        ranges[r * 3 + 1] = range.indexCount();
                        ranges[r * 3 + 2] = range.image();
                    }
                    builder.mesh(mesh.vertices(), mesh.indices(), ranges);
                }
            }
            // Placements beyond the per-tile draw budget are capped in source order, never
            // failing the whole tile's set.
            var modelDraws = new int[set.models().size()];
            for (int m = 0; m < modelDraws.length; m++) {
                for (var mesh : set.models().get(m).meshes()) {
                    modelDraws[m] += mesh.ranges().size();
                }
            }
            int draws = 0;
            for (var placement : set.placements()) {
                int cost = modelDraws[placement.model()];
                if (draws + cost > WorldStreamTileWriter.Exterior.MAX_DRAWS) {
                    continue;
                }
                draws += cost;
                builder.placement(placement.model(), placement.x(), placement.y(), placement.z(),
                        placement.scale(), placement.cos(), placement.sin());
            }
            return builder.build();
        }

        private void check(BooleanSupplier stopping, Cancellation cancellation) {
            cancellation.check();
            if (stopping.getAsBoolean() || !latest.getAsBoolean()) {
                throw new CancellationException();
            }
        }

        RefusalMetadata sourceRefusal() { return sourceRefusal; }

        synchronized Prepared takePrepared() {
            if (prepared == null || closed) {
                throw new IllegalStateException("No prepared source resident");
            }
            var value = prepared;
            prepared = null;
            return value;
        }

        @Override
        public void close() {
            Prepared value;
            synchronized (this) {
                closed = true;
                value = prepared;
                prepared = null;
            }
            if (value != null) {
                value.close();
            }
        }
    }

    /** Inert writer result: a detached validated copy; closing it releases nothing. */
    record WaterCopy(WorldStreamTileWriter.Water water) implements AutoCloseable {
        static WaterCopy of(WorldWaterPreview.CheckedBorrow borrow, Cancellation token) {
            int vertices = borrow.vertices(), indexCount = borrow.indices();
            if (vertices < 4
                    || vertices > WorldStreamTileWriter.Water.MAX_CELLS * 4
                    || vertices % 4 != 0
                    || indexCount != vertices / 4 * 6) {
                throw new IllegalArgumentException("Water exceeds packet profile");
            }
            var positions = new float[vertices * 3];
            var depths = new byte[vertices];
            var indices = new int[indexCount];
            for (int v = 0; v < vertices; v++) {
                if ((v & 1023) == 0) {
                    token.check();
                }
                positions[v * 3] = borrow.north(v);
                positions[v * 3 + 1] = borrow.west(v);
                positions[v * 3 + 2] = borrow.up(v);
                int depth = borrow.depth(v);
                if (depth < 0 || depth > 255) {
                    throw new IllegalArgumentException("Water depth byte");
                }
                depths[v] = (byte) depth;
            }
            for (int i = 0; i < indexCount; i++) {
                indices[i] = borrow.index(i);
            }
            return new WaterCopy(
                    new WorldStreamTileWriter.Water(positions, depths, indices, borrow.layers()));
        }

        @Override
        public void close() {}
    }

    private static WorldStreamTileWriter.PacketFile write(
            WorldStreamTransport.PacketDestination destination,
            WorldTerrainStreaming.CheckedBorrow borrow,
            WorldStreamTileWriter.ObjectsFile objects, BooleanSupplier stopping) {
        var facts = borrow.facts();
        var material = borrow.material();
        var texture = borrow.texture();
        var coverage = new long[4];
        float maxX = 0, maxY = 0;
        double error = 0;
        if (texture != null) {
            var metadata = texture.metadata();
            metadata.rectangleCoverage().get(coverage);
            maxX = metadata.nativeMaxX();
            maxY = metadata.nativeMaxY();
            error = metadata.maxSourceUvErrorTexels();
        }
        var diffuse = new ArrayList<WorldStreamTileWriter.Source>();
        if (material != null && texture != null) {
            for (int i = 0; i < material.diffuseCount(); i++) {
                var mip = material.diffuse(i);
                var input = mip.source();
                diffuse.add(new WorldStreamTileWriter.Source(input.fileDataId(), input.contentKey(),
                        input.consumedLength(), input.declaredLength(), input.sourceLimit(),
                        mip.originalSide(), mip.mipLevel(), mip.mipFlags(),
                        WorldStreamTileWriter.Encoding.valueOf(mip.encoding().name()),
                        mip.alphaDepth(), mip.alphaEncoding()));
            }
        }
        var data = new WorldStreamTileWriter.Data(facts.scopeSha256(), TerrainTileAtlas.RECIPE,
                facts.geometrySha256(), WorldStreamTileWriter.Outcome.valueOf(facts.outcome().name()),
                WorldStreamTileWriter.Reason.valueOf(facts.fallback().name()), coverage,
                maxX, maxY, error, structural(facts.wdt()), structural(facts.root()),
                material == null ? null : structural(material.textures()), diffuse);
        try {
            return WorldStreamTileWriter.writeUnadvertised(
                    destination, data, borrow.geometry(), texture, objects, stopping);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static WorldStreamTileWriter.Source structural(WorldTerrainStreaming.SourceTuple input) {
        return new WorldStreamTileWriter.Source(input.fileDataId(), input.contentKey(),
                input.consumedLength(), input.declaredLength(), input.sourceLimit(),
                0, 0, 0, null, 0, 0);
    }

    private static Throwable close(AutoCloseable value, Throwable prior) {
        try {
            value.close();
        } catch (Exception | Error problem) {
            if (prior == null) {
                return problem;
            }
            prior.addSuppressed(problem);
        }
        return prior;
    }
}
