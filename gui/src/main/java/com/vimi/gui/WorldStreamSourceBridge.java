package com.vimi.gui;

import com.vibeminer.access.Cancellation;
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
        private volatile int waterCells;

        /** Descriptive count of static water cells carried by this tile's moved packet. */
        int waterCells() {
            return waterCells;
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
        private volatile String waterNote = "";

        Producer(WorldTerrainStreaming source, WorldTerrainStreaming.Scope scope,
                WorldFormats.Tile tile, BooleanSupplier latest) {
            this(source, null, scope, tile, latest);
        }

        /** Optional water joins this tile's actual terrain owner before its packet MOVE. */
        Producer(WorldTerrainStreaming source, WorldWaterPreview water,
                WorldTerrainStreaming.Scope scope, WorldFormats.Tile tile, BooleanSupplier latest) {
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
            try {
                check(stopping, cancellation);
                var written = source.prepareAndWrite(scope, tile, cancellation,
                        (borrow, token) -> write(destination, borrow,
                                () -> stopping.getAsBoolean() || token.isCanceled()));
                local = new Prepared(written);
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
            } catch (RuntimeException failed) {
                // Optional preview: report bounded diagnostics, keep terrain. Fatal Source health
                // still fails the following terrain MOVE gate.
                waterNote = "water failed: " + failed.getClass().getSimpleName();
                return null;
            }
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
            if (vertices > WorldStreamTileWriter.Water.MAX_CELLS * 4) {
                throw new IllegalStateException("Water exceeds packet bound");
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
                depths[v] = (byte) borrow.depth(v);
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
            WorldTerrainStreaming.CheckedBorrow borrow, BooleanSupplier stopping) {
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
                    destination, data, borrow.geometry(), texture, stopping);
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
