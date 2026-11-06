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
import java.util.List;
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
        private volatile ExteriorSummary exteriorSummary = ExteriorSummary.NONE;
        private volatile boolean exteriorFailed;

        /** Descriptive: this tile's exterior objects failed on the parent side; terrain kept. */
        boolean exteriorFailed() {
            return exteriorFailed;
        }

        /** Records late objects attached to this tile's resident (descriptive only). */
        void exteriorAttached(ExteriorSummary summary) {
            exteriorSummary = Objects.requireNonNull(summary);
        }

        /** Records a parent-side failure of this tile's late objects (descriptive only). */
        void exteriorFailedLate() {
            exteriorFailed = true;
        }

        private volatile WorldTileCreatures.Summary creatures = WorldTileCreatures.Summary.NONE;

        /** Records this tile's creature outcome once its late objects ended (descriptive only). */
        void creatures(WorldTileCreatures.Summary summary) {
            creatures = Objects.requireNonNull(summary);
        }

        /** This tile's creature outcome; NONE until its late objects ended. */
        WorldTileCreatures.Summary creatures() {
            return creatures;
        }

        private volatile WorldObjectPicks.Tile picks;

        /** Records the pick geometry and sources of this tile's attached late objects. */
        void picks(WorldObjectPicks.Tile tile) {
            picks = tile;
        }

        /** Pick geometry of this tile's attached late objects, or null. */
        WorldObjectPicks.Tile picks() {
            return picks;
        }

        /** Descriptive count of static water cells carried by this tile's moved packet. */
        int waterCells() {
            return waterCells;
        }

        /** Descriptive drawn/omitted counts of this tile's attached objects. */
        ExteriorSummary exteriorSummary() {
            return exteriorSummary;
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

    /**
     * Descriptive drawn and omitted counts of one tile's BASIC_EXTERIOR set, as written. Omitted
     * buildings and doodads are records (MODF, MDDF and selected MODD entries) that are not
     * drawn for any reason; capped doodads are the omitted doodads that qualified but did not fit
     * the per-tile doodad, model or draw caps (or the bridge's own draw-budget check).
     * Omitted surfaces are unsupported, image-less, capped or unreadable batches of drawn or
     * empty roots and models; unreadable surfaces are the cutout and blended building batches
     * whose image could not be read now (they never cost the building).
     */
    record ExteriorSummary(
            int buildings,
            int buildingsOmitted,
            int doodads,
            int doodadsOmitted,
            int doodadsCapped,
            int surfacesOmitted,
            int unreadable) {
        static final ExteriorSummary NONE = new ExteriorSummary(0, 0, 0, 0, 0, 0, 0);

        ExteriorSummary plus(ExteriorSummary o) {
            return new ExteriorSummary(
                    buildings + o.buildings,
                    buildingsOmitted + o.buildingsOmitted,
                    doodads + o.doodads,
                    doodadsOmitted + o.doodadsOmitted,
                    doodadsCapped + o.doodadsCapped,
                    surfacesOmitted + o.surfacesOmitted,
                    unreadable + o.unreadable);
        }

        int omitted() {
            return buildingsOmitted + doodadsOmitted + surfacesOmitted;
        }

        /** The counts after the bridge's own draw-budget cap (normally a no-op). */
        ExteriorSummary capped(int buildingsCapped, int doodadsCapped) {
            return new ExteriorSummary(
                    buildings - buildingsCapped,
                    buildingsOmitted + buildingsCapped,
                    doodads - doodadsCapped,
                    doodadsOmitted + doodadsCapped,
                    this.doodadsCapped + doodadsCapped,
                    surfacesOmitted,
                    unreadable);
        }

        static ExteriorSummary of(WorldExteriorObjects.TileObjects set) {
            int buildings = set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN);
            int buildingsOmitted =
                    sum(
                            set,
                            WorldExteriorObjects.Count.PLACEMENT_UNSUPPORTED,
                            WorldExteriorObjects.Count.PLACEMENT_CONFLICTING,
                            WorldExteriorObjects.Count.PLACEMENT_CAPPED,
                            WorldExteriorObjects.Count.PLACEMENT_FAILED_ROOT,
                            WorldExteriorObjects.Count.PLACEMENT_EMPTY);
            int doodads =
                    sum(
                            set,
                            WorldExteriorObjects.Count.DOODAD_DRAWN,
                            WorldExteriorObjects.Count.SET_DOODAD_DRAWN);
            int doodadsCapped =
                    sum(
                            set,
                            WorldExteriorObjects.Count.DOODAD_CAPPED,
                            WorldExteriorObjects.Count.SET_DOODAD_CAPPED);
            int doodadsOmitted =
                    doodadsCapped
                            + sum(
                                    set,
                                    WorldExteriorObjects.Count.DOODAD_UNSUPPORTED,
                                    WorldExteriorObjects.Count.DOODAD_CONFLICTING,
                                    WorldExteriorObjects.Count.DOODAD_FAILED_MODEL,
                                    WorldExteriorObjects.Count.DOODAD_EMPTY,
                                    WorldExteriorObjects.Count.SET_DOODAD_UNSUPPORTED,
                                    WorldExteriorObjects.Count.SET_DOODAD_FAILED_MODEL,
                                    WorldExteriorObjects.Count.SET_DOODAD_EMPTY);
            int surfaces =
                    sum(
                            set,
                            WorldExteriorObjects.Count.BATCH_UNSUPPORTED,
                            WorldExteriorObjects.Count.BATCH_MISSING_IMAGE,
                            WorldExteriorObjects.Count.BATCH_CAPPED,
                            WorldExteriorObjects.Count.BATCH_UNREADABLE,
                            WorldExteriorObjects.Count.MODEL_BATCH_UNSUPPORTED,
                            WorldExteriorObjects.Count.MODEL_BATCH_MISSING_IMAGE);
            return new ExteriorSummary(
                    buildings,
                    buildingsOmitted,
                    doodads,
                    doodadsOmitted,
                    doodadsCapped,
                    surfaces,
                    set.count(WorldExteriorObjects.Count.BATCH_UNREADABLE));
        }

        private static int sum(
                WorldExteriorObjects.TileObjects set, WorldExteriorObjects.Count... counts) {
            int total = 0;
            for (var count : counts) {
                total += set.count(count);
            }
            return total;
        }
    }

    /**
     * Outcome of one tile's BASIC_EXTERIOR preparation, detached from the source: a validated
     * copy to deliver late, or none ({@code failed} distinguishes a building failure from an empty
     * tile). {@code placements} counts buildings only; {@code creatures} is the tile's creature
     * outcome, whose drawn placements share the same copy.
     */
    record ExteriorCopy(
            WorldStreamTileWriter.Exterior exterior,
            int placements,
            boolean failed,
            String note,
            WorldTileCreatures.Summary creatures,
            ExteriorSummary summary,
            WorldObjectPicks.Tile picks) {
        ExteriorCopy {
            Objects.requireNonNull(creatures);
            Objects.requireNonNull(summary);
        }

        ExteriorCopy(
                WorldStreamTileWriter.Exterior exterior,
                int placements,
                boolean failed,
                String note,
                WorldTileCreatures.Summary creatures,
                ExteriorSummary summary) {
            this(exterior, placements, failed, note, creatures, summary, null);
        }

        /** A buildings-only copy whose summary counts {@code placements} buildings. */
        ExteriorCopy(
                WorldStreamTileWriter.Exterior exterior,
                int placements,
                boolean failed,
                String note) {
            this(exterior, placements, failed, note, WorldTileCreatures.Summary.NONE);
        }

        ExteriorCopy(
                WorldStreamTileWriter.Exterior exterior,
                int placements,
                boolean failed,
                String note,
                WorldTileCreatures.Summary creatures) {
            this(
                    exterior,
                    placements,
                    failed,
                    note,
                    creatures,
                    new ExteriorSummary(placements, 0, 0, 0, 0, 0, 0));
        }

        ExteriorCopy withSummary(ExteriorSummary replacement) {
            return new ExteriorCopy(exterior, placements, failed, note, creatures, replacement, picks);
        }

        /**
         * This copy with the pick geometry of its exterior and the recorded sources of its
         * buildings and doodads ({@code buildings}, only when they are in the copy) followed by its
         * drawn creatures. A pick failure only costs inspection, never the copy.
         */
        ExteriorCopy withPicks(WorldFormats.Tile tile, java.util.List<WorldObjectPicks.Source> buildings) {
            if (exterior == null) return this;
            var sources = new ArrayList<WorldObjectPicks.Source>();
            if (!failed) sources.addAll(buildings);
            sources.addAll(creatures.placed());
            try {
                var picked = WorldObjectPicks.Tile.of(tile.x(), tile.y(), exterior.pickTile(), sources);
                return new ExteriorCopy(exterior, placements, failed, note, creatures, summary, picked);
            } catch (RuntimeException invalid) {
                WorldDiagnostics.degraded("object picking",
                        "World object pick geometry failed; objects drawn but not inspectable", invalid);
                return this;
            }
        }
    }

    /**
     * One tile's exterior object source operation. It runs only while the controller has no
     * terrain work, and is canceled the moment terrain is wanted again.
     */
    @FunctionalInterface
    interface ExteriorPreparer {
        ExteriorCopy prepare(
                WorldTerrainStreaming source,
                WorldTerrainStreaming.Scope scope,
                WorldFormats.Tile tile,
                Cancellation cancellation);
    }

    /** Appends one tile's creatures after its buildings; only cancellation may propagate. */
    @FunctionalInterface
    interface CreatureAppender {
        WorldTileCreatures.Summary append(
                WorldStreamTileWriter.Exterior.Builder builder,
                WorldFormats.Tile tile,
                Cancellation cancellation);
    }

    /** The actual preparer without creatures. */
    static ExteriorCopy prepareExterior(
            WorldTerrainStreaming source,
            WorldTerrainStreaming.Scope scope,
            WorldFormats.Tile tile,
            Cancellation cancellation) {
        return prepareExterior(source, scope, tile, cancellation, null);
    }

    /**
     * The actual preparer: one serialized BASIC_EXTERIOR source operation outside any terrain
     * write, then (when given) the tile's creatures in the remaining objects budget. The neutral
     * set is copied and closed here. Only cancellation propagates; every other runtime failure
     * degrades this tile to terrain only, and a creature failure never costs its buildings.
     */
    static ExteriorCopy prepareExterior(
            WorldTerrainStreaming source,
            WorldTerrainStreaming.Scope scope,
            WorldFormats.Tile tile,
            Cancellation cancellation,
            CreatureAppender creatures) {
        return prepareExterior(
                source,
                scope,
                tile,
                cancellation,
                creatures,
                WorldExteriorObjects.ImageDetail.STANDARD);
    }

    /** As above, with the object texture budget tier (#267). */
    static ExteriorCopy prepareExterior(
            WorldTerrainStreaming source,
            WorldTerrainStreaming.Scope scope,
            WorldFormats.Tile tile,
            Cancellation cancellation,
            CreatureAppender creatures,
            WorldExteriorObjects.ImageDetail detail) {
        WorldExteriorObjects.TileObjects set = null;
        try {
            boolean failed = false;
            String note;
            var summary = ExteriorSummary.NONE;
            try {
                set = source.prepareExteriorObjects(scope, tile, cancellation, detail);
                logModelFailure(set);
                summary = ExteriorSummary.of(set);
                note = note(summary);
            } catch (CancellationException canceled) {
                throw canceled;
            } catch (WorldTerrainStreaming.Refusal refused) {
                cancellation.check();
                failed = true;
                note = "exterior objects refused: " + refused.site();
            } catch (RuntimeException failure) {
                cancellation.check();
                failed = true;
                note = "exterior objects failed: " + failure.getClass().getSimpleName();
                WorldDiagnostics.degraded("exterior objects",
                        "World exterior objects failed; tile kept as terrain only", failure);
            }
            return finish(failed ? null : ObjectSet.of(set), failed, note, summary, tile, cancellation,
                    creatures);
        } finally {
            if (set != null) set.close();
        }
    }

    /**
     * What the bridge copies from one prepared set: its images and models (written by {@code
     * meshes}), each model's draw cost, and its placements in source order with their recorded
     * sources. {@code buildings} counts the drawn MODF buildings.
     */
    record ObjectSet(
            java.util.function.BiConsumer<WorldStreamTileWriter.Exterior.Builder, Cancellation> meshes,
            int[] modelDraws,
            List<Placed> placements,
            int buildings) {
        ObjectSet {
            Objects.requireNonNull(meshes);
            modelDraws = modelDraws.clone();
            placements = List.copyOf(placements);
        }

        /** The neutral set's images, models and placements, with each placement's source. */
        static ObjectSet of(WorldExteriorObjects.TileObjects set) {
            var draws = new int[set.models().size()];
            for (int m = 0; m < draws.length; m++) {
                for (var mesh : set.models().get(m).meshes()) draws[m] += mesh.ranges().size();
            }
            var parents = WorldObjectPicks.Recorder.buildings(set);
            var placed = new ArrayList<Placed>(set.placements().size());
            for (var p : set.placements()) {
                placed.add(new Placed(p.kind() == WorldExteriorObjects.Kind.BUILDING, p.model(), p.x(), p.y(), p.z(),
                        p.scale(), p.rotation().rowMajor(), WorldObjectPicks.Recorder.exterior(set, p, parents)));
            }
            return new ObjectSet((builder, cancellation) -> copyMeshes(builder, set, cancellation), draws, placed,
                    WorldStreamSourceBridge.buildings(set));
        }
    }

    /** One placement to copy and the source record it was built from. */
    record Placed(boolean building, int model, double x, double y, double z, double scale, double[] rotation,
            WorldObjectPicks.Source source) {
        Placed {
            rotation = rotation.clone();
            Objects.requireNonNull(source);
        }
    }

    /**
     * Copies a prepared set (null when it failed) and, when given, the tile's creatures, then
     * attaches pick geometry whose sources follow the copied placements one to one.
     */
    static ExteriorCopy finish(
            ObjectSet set,
            boolean failed,
            String note,
            ExteriorSummary summary,
            WorldFormats.Tile tile,
            Cancellation cancellation,
            CreatureAppender creatures) {
        // Buildings and doodads; capped receives the bridge's own draw-budget drops.
        var capped = new int[2];
        var recorded = new WorldObjectPicks.Recorder();
        boolean empty = failed || set.placements().isEmpty();
        if (creatures == null) {
            if (empty) return new ExteriorCopy(null, 0, failed, note).withSummary(summary);
            var builder = WorldStreamTileWriter.Exterior.builder();
            copyInto(builder, set, cancellation, capped, recorded);
            return new ExteriorCopy(builder.build(), set.buildings(), false, note)
                    .withSummary(summary.capped(capped[0], capped[1]))
                    .withPicks(tile, recorded.sources());
        }
        var result = assemble(empty ? null : builder -> copyInto(builder, set, cancellation, capped, recorded),
                failed, note, creatures, tile, cancellation);
        // A building failure inside assemble leaves no buildings or doodads to describe.
        if (result.failed() && !failed) return result.withSummary(ExteriorSummary.NONE).withPicks(tile, List.of());
        return result.withSummary(result.exterior() == null && !empty
                ? ExteriorSummary.NONE : summary.capped(capped[0], capped[1]))
                .withPicks(tile, recorded.sources());
    }

    /**
     * Buildings first, then creatures, into one late objects copy. {@code buildings} copies the
     * tile's buildings into a builder and may run twice. A creature problem of any kind (a thrown
     * failure, a cancellation that is not this tile's, or a set that fails the file checks) never
     * costs the buildings: they are rebuilt alone and the creatures are reported failed. Only this
     * tile's own cancellation propagates.
     */
    static ExteriorCopy assemble(
            Consumer<WorldStreamTileWriter.Exterior.Builder> buildings,
            boolean failed,
            String note,
            CreatureAppender creatures,
            WorldFormats.Tile tile,
            Cancellation cancellation) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        int count = 0;
        if (buildings != null) {
            try {
                buildings.accept(builder);
                count = builder.placementCount();
            } catch (CancellationException canceled) {
                if (cancellation.isCanceled()) throw canceled;
                failed = true;
                note = "exterior objects failed: canceled outside this tile";
                buildings = null;
                builder = WorldStreamTileWriter.Exterior.builder();
            } catch (RuntimeException failure) {
                cancellation.check();
                failed = true;
                note = "exterior objects failed: " + failure.getClass().getSimpleName();
                WorldDiagnostics.degraded("exterior objects",
                        "World exterior objects copy failed; tile kept as terrain only", failure);
                buildings = null;
                builder = WorldStreamTileWriter.Exterior.builder();
            }
        }
        WorldTileCreatures.Summary summary;
        try {
            summary = creatures.append(builder, tile, cancellation);
            if (builder.placementCount() == 0) return new ExteriorCopy(null, 0, failed, note, summary);
            try {
                return new ExteriorCopy(builder.build(), count, failed, note, summary);
            } catch (RuntimeException invalid) {
                cancellation.check();
                WorldDiagnostics.degraded("creatures",
                        "World creature models failed the objects file checks; buildings kept", invalid);
                summary = WorldTileCreatures.Summary.failed(
                        "creature models invalid: " + invalid.getMessage());
            }
        } catch (CancellationException canceled) {
            if (cancellation.isCanceled()) throw canceled;
            summary = WorldTileCreatures.Summary.failed("creature models canceled outside this tile");
        } catch (RuntimeException failure) {
            cancellation.check();
            WorldDiagnostics.degraded("creatures", "World creature models failed; buildings kept", failure);
            summary = WorldTileCreatures.Summary.failed(
                    "creature models failed: " + failure.getClass().getSimpleName());
        }
        // The builder may hold a partial or invalid creature model: rebuild buildings alone.
        if (buildings == null) return new ExteriorCopy(null, 0, failed, note, summary);
        try {
            var alone = WorldStreamTileWriter.Exterior.builder();
            buildings.accept(alone);
            if (alone.placementCount() == 0) return new ExteriorCopy(null, 0, failed, note, summary);
            return new ExteriorCopy(alone.build(), alone.placementCount(), failed, note, summary);
        } catch (CancellationException canceled) {
            if (cancellation.isCanceled()) throw canceled;
            return new ExteriorCopy(null, 0, true, "exterior objects failed: canceled outside this tile", summary);
        } catch (RuntimeException failure) {
            cancellation.check();
            WorldDiagnostics.degraded("exterior objects",
                    "World exterior objects rebuild failed; tile kept as terrain only", failure);
            return new ExteriorCopy(
                    null, 0, true, "exterior objects failed: " + failure.getClass().getSimpleName(), summary);
        }
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

        /**
         * Optional water joins this tile's actual terrain owner before its packet MOVE. Exterior
         * objects never join the terrain packet: the controller delivers them late, so terrain
         * never waits for them.
         */
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
            } catch (IllegalArgumentException | IllegalStateException failed) {
                // Only a clean leaf failure (e.g. this bridge's own copy validation) degrades to
                // terrain-only. Source cleanup/health faults carry causes or suppressed failures
                // and stay fatal.
                if (failed.getCause() != null || failed.getSuppressed().length != 0) {
                    throw failed;
                }
                waterNote = "water failed: " + failed.getClass().getSimpleName();
                WorldDiagnostics.degraded("water", "World water failed; tile kept as terrain only", failed);
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

    private static String note(ExteriorSummary s) {
        return "exterior buildings drawn=" + s.buildings() + " omitted=" + s.buildingsOmitted()
                + " doodads drawn=" + s.doodads() + " omitted=" + s.doodadsOmitted()
                + " (capped " + s.doodadsCapped() + ") surfaces omitted=" + s.surfacesOmitted()
                + " (unreadable " + s.unreadable() + ")";
    }

    /**
     * A doodad reader failure omitted only its doodads and the tile kept the rest, but it is still
     * a reader bug, so it is logged as a degraded part.
     */
    static void logModelFailure(WorldExteriorObjects.TileObjects set) {
        var failure = set.modelFailure();
        if (failure != null) {
            WorldDiagnostics.degraded("doodad models",
                    "World doodad model reader failed; " + set.count(
                            WorldExteriorObjects.Count.MODEL_FAILED)
                            + " failed models omitted only their doodads", failure);
        }
    }

    /** Buildings drawn in a set (its other placements are doodads). */
    private static int buildings(WorldExteriorObjects.TileObjects set) {
        return set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN);
    }

    private static void copyMeshes(
            WorldStreamTileWriter.Exterior.Builder builder,
            WorldExteriorObjects.TileObjects set,
            Cancellation cancellation) {
        for (var image : set.images()) {
            cancellation.check();
            builder.image(image.width(), image.height(), image.rgba());
        }
        for (var model : set.models()) {
            builder.model();
            for (var mesh : model.meshes()) {
                cancellation.check();
                var ranges = new int[mesh.ranges().size() * 3];
                var blends = new int[mesh.ranges().size()];
                for (int r = 0; r < mesh.ranges().size(); r++) {
                    var range = mesh.ranges().get(r);
                    ranges[r * 3] = range.firstIndex();
                    ranges[r * 3 + 1] = range.indexCount();
                    ranges[r * 3 + 2] = range.image();
                    blends[r] = range.blend();
                }
                builder.mesh(mesh.vertices(), mesh.indices(), ranges, blends);
            }
        }
    }

    /** capped receives the buildings and doodads dropped by the bridge's draw-budget check. */
    private static void copyInto(
            WorldStreamTileWriter.Exterior.Builder builder,
            ObjectSet set,
            Cancellation cancellation,
            int[] capped,
            WorldObjectPicks.Recorder recorded) {
        capped[0] = capped[1] = 0;
        recorded.clear();
        set.meshes().accept(builder, cancellation);
        // The source already caps placements to the per-tile draw budget; this repeats it in
        // source order so the file profile can never fail the whole tile's set.
        int draws = 0;
        for (var placement : set.placements()) {
            int cost = set.modelDraws()[placement.model()];
            if (draws + cost > WorldStreamTileWriter.Exterior.MAX_DRAWS) {
                capped[placement.building() ? 0 : 1]++;
                continue;
            }
            draws += cost;
            builder.placement(placement.model(), placement.x(), placement.y(), placement.z(),
                    placement.scale(), placement.rotation());
            recorded.add(placement.source());
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
        WorldStreamTileWriter.PacketFile packet;
        try {
            packet = WorldStreamTileWriter.writeUnadvertised(
                    destination, data, borrow.geometry(), texture, null, stopping);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        var splat = borrow.splat();
        if (splat.isPresent() && material != null && texture != null) {
            try {
                packet.splat(splatInput(borrow, material, splat.get()));
            } catch (RuntimeException failure) {
                packet.close();
                throw failure;
            }
        }
        return packet;
    }

    /** The tile's splatting inputs, decoding a layer diffuse only when the writer asks for it. */
    private static WorldStreamTileWriter.SplatInput splatInput(
            WorldTerrainStreaming.CheckedBorrow borrow,
            WorldTerrainStreaming.Material material,
            com.vibeminer.rendering.world.TerrainTileSplat splat) {
        long[] ids = splat.diffuseIds();
        String[] keys = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            for (int d = 0; d < material.diffuseCount(); d++) {
                var source = material.diffuse(d).source();
                if (source.fileDataId() == ids[i]) keys[i] = source.contentKey();
            }
            if (keys[i] == null) throw new IllegalStateException("Splat diffuse outside its material");
        }
        byte[] table = new byte[splat.layers().remaining()];
        splat.layers().get(table);
        return new WorldStreamTileWriter.SplatInput(ids, keys, table, splat.alpha(),
                id -> borrow.diffuseDetail(id, Cancellation.NONE)
                        .map(detail -> new WorldStreamTileWriter.Diffuse(detail.side(), detail.rgba())));
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
