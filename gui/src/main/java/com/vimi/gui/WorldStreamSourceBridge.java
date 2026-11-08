package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldExteriorObjects;
import com.vibeminer.concepts.world.WorldTerrainStreaming;
import com.vibeminer.concepts.world.WorldWaterPreview;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldStageTimings;
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
        private volatile List<String> waterIssues = List.of();
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

        /** Where this tile's water source disagrees with a convention the water followed. */
        List<String> waterIssues() {
            return waterIssues;
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
        private RefusalMetadata(WorldTerrainStreaming.Refusal.Reason reason, String site) {
            this.reason = reason;
            this.site = site;
        }
        /** A tile the World helper kept rejecting (issue #365); its site names it. */
        static RefusalMetadata helperRejected() {
            return new RefusalMetadata(
                    WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE,
                    WorldStreamSourceController.HELPER_REJECTED);
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
                            WorldExteriorObjects.Count.BATCH_IMAGE_CAPPED,
                            WorldExteriorObjects.Count.BATCH_CAPPED,
                            WorldExteriorObjects.Count.BATCH_UNREADABLE,
                            WorldExteriorObjects.Count.MODEL_BATCH_UNSUPPORTED,
                            WorldExteriorObjects.Count.MODEL_BATCH_BILLBOARD,
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
        /**
         * {@code objectFormats}: the BC formats the session's child samples as blocks (bit per
         * format code, {@code WorldStreamTransport.Session.objectFormats()}), 0 for RGBA8 only.
         */
        ExteriorCopy prepare(
                WorldTerrainStreaming source,
                WorldTerrainStreaming.Scope scope,
                WorldFormats.Tile tile,
                Cancellation cancellation,
                int objectFormats);
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
        return prepareExterior(source, scope, tile, cancellation, creatures, detail, false);
    }

    /**
     * As above; {@code interiors} also draws interior WMO groups, for dungeon and raid maps
     * (#280).
     */
    static ExteriorCopy prepareExterior(
            WorldTerrainStreaming source,
            WorldTerrainStreaming.Scope scope,
            WorldFormats.Tile tile,
            Cancellation cancellation,
            CreatureAppender creatures,
            WorldExteriorObjects.ImageDetail detail,
            boolean interiors) {
        return prepareExterior(
                source, scope, tile, cancellation, creatures, detail, interiors, 0);
    }

    /** As above, for a session whose child samples {@code objectFormats} as blocks (#374). */
    static ExteriorCopy prepareExterior(
            WorldTerrainStreaming source,
            WorldTerrainStreaming.Scope scope,
            WorldFormats.Tile tile,
            Cancellation cancellation,
            CreatureAppender creatures,
            WorldExteriorObjects.ImageDetail detail,
            boolean interiors,
            int objectFormats) {
        WorldExteriorObjects.TileObjects set = null;
        try {
            boolean failed = false;
            String note;
            var summary = ExteriorSummary.NONE;
            try {
                set = source.prepareExteriorObjects(
                        scope, tile, cancellation, detail, interiors, objectFormats);
                logModelFailure(set);
                set.tallyCounts();
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
            long copying = WorldStageTimings.start();
            try {
                return finish(failed ? null : ObjectSet.of(set), failed, note, summary, tile,
                        cancellation, creatures);
            } finally {
                WorldStageTimings.stop("objects.bridge.copyAndCreatures", copying);
            }
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
            int buildings,
            int compressedFormats) {
        ObjectSet {
            Objects.requireNonNull(meshes);
            modelDraws = modelDraws.clone();
            placements = List.copyOf(placements);
        }

        /** A set charged for a session that samples no BC format. */
        ObjectSet(java.util.function.BiConsumer<WorldStreamTileWriter.Exterior.Builder, Cancellation> meshes,
                int[] modelDraws, List<Placed> placements, int buildings) {
            this(meshes, modelDraws, placements, buildings, 0);
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
                    WorldStreamSourceBridge.buildings(set), set.compressedFormats());
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
            // The objects file charges images as the set was charged (#374).
            var builder = WorldStreamTileWriter.Exterior.builder(set.compressedFormats());
            copyInto(builder, set, cancellation, capped, recorded);
            return new ExteriorCopy(builder.build(), set.buildings(), false, note)
                    .withSummary(summary.capped(capped[0], capped[1]))
                    .withPicks(tile, recorded.sources());
        }
        var result = assemble(empty ? null : builder -> copyInto(builder, set, cancellation, capped, recorded),
                failed, note, creatures, tile, cancellation, set == null ? 0 : set.compressedFormats());
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
        return assemble(buildings, failed, note, creatures, tile, cancellation, 0);
    }

    /** As above, building an objects file that charges {@code compressedFormats} as blocks. */
    static ExteriorCopy assemble(
            Consumer<WorldStreamTileWriter.Exterior.Builder> buildings,
            boolean failed,
            String note,
            CreatureAppender creatures,
            WorldFormats.Tile tile,
            Cancellation cancellation,
            int compressedFormats) {
        var builder = WorldStreamTileWriter.Exterior.builder(compressedFormats);
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
                builder = WorldStreamTileWriter.Exterior.builder(compressedFormats);
            } catch (RuntimeException failure) {
                cancellation.check();
                failed = true;
                note = "exterior objects failed: " + failure.getClass().getSimpleName();
                WorldDiagnostics.degraded("exterior objects",
                        "World exterior objects copy failed; tile kept as terrain only", failure);
                buildings = null;
                builder = WorldStreamTileWriter.Exterior.builder(compressedFormats);
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
            var alone = WorldStreamTileWriter.Exterior.builder(compressedFormats);
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
        private volatile List<String> lastWaterIssues = List.of();

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
                        local.waterIssues = lastWaterIssues;
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
                var copy = new WaterCopy[1];
                positive.withWrittenResource(r -> copy[0] = r);
                lastWaterIssues = facts.issues();
                waterNote = "water " + facts.state() + " cells=" + facts.cells()
                        + " quads=" + copy[0].water().cells()
                        + (copy[0].finest() > 1
                                ? " depth coarsened to " + copy[0].finest() + "-cell blocks" : "")
                        + (copy[0].capped() ? " capped at " + copy[0].layers() + " of "
                                + facts.admittedLayers() + " layers" : "")
                        + (facts.issues().isEmpty() ? "" : "; " + String.join("; ", facts.issues()));
                return copy[0].water();
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
            // The chain as stored: RGBA8, or the file's own BC blocks (#374).
            builder.image(image.width(), image.height(), image.format(), image.data());
        }
        for (var model : set.models()) {
            boolean portals = startModel(builder, model.portals());
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
                builder.mesh(mesh.vertices(), mesh.indices(), ranges, blends, mesh.colours(),
                        portals ? mesh.groupOrdinal() : -1);
            }
        }
    }

    /**
     * Starts one copied model, with its portal set (#383 step 3a) when it has one and it still fits
     * the tile's portal tables; returns whether it was carried, so the meshes name their groups. A
     * set left out costs nothing else: the model draws exactly as before.
     */
    static boolean startModel(
            WorldStreamTileWriter.Exterior.Builder builder,
            com.vibeminer.rendering.world.WmoPortalSet portals) {
        builder.model();
        if (portals == null || !builder.portalsFit(portals)) return false;
        builder.portals(portals);
        return true;
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

    /**
     * Inert writer result: a detached validated copy; closing it releases nothing. A full chunk
     * (8 x 8 cells) whose used heights are all equal draws one plane, so its cells merge into
     * larger quads (a whole chunk, 4 x 4 or 2 x 2 cells) wherever one quad still reproduces
     * their depths within a byte: open sea and large lakes cost one quad per chunk instead of 64,
     * while a coastal chunk keeps its shallow-to-deep gradient. When the tile would pass the
     * packet's quad bound, blocks up to {@code finest} cells merge regardless of depth, using the
     * finest size that fits. If even whole chunks do not fit, layers are copied in source order
     * until the bound, and a layer that would pass it is left out whole ({@code capped}).
     */
    record WaterCopy(
            WorldStreamTileWriter.Water water, int sourceCells, int layers, boolean capped,
            int finest) implements AutoCloseable {
        /** The scalar water facts the copy reads: one actual Source borrow, or a test fixture. */
        interface Cells {
            int vertices();

            int indices();

            int layers();

            int width(int layer);

            int height(int layer);

            long mask(int layer);

            boolean planar(int layer);

            /** The layer's flat colour, 0xRRGGBB, or -1 for the water colour. */
            int colour(int layer);

            /** True when the client draws this water layer as ocean (#171). */
            boolean ocean(int layer);

            float north(int vertex);

            float west(int vertex);

            float up(int vertex);

            int depth(int vertex);
        }

        static WaterCopy of(WorldWaterPreview.CheckedBorrow borrow, Cancellation token) {
            return of(new Cells() {
                @Override public int vertices() { return borrow.vertices(); }
                @Override public int indices() { return borrow.indices(); }
                @Override public int layers() { return borrow.layers(); }
                @Override public int width(int layer) { return borrow.width(layer); }
                @Override public int height(int layer) { return borrow.height(layer); }
                @Override public long mask(int layer) { return borrow.mask(layer); }
                @Override public boolean planar(int layer) { return borrow.planar(layer); }
                @Override public int colour(int layer) { return borrow.colour(layer); }
                @Override public boolean ocean(int layer) { return borrow.ocean(layer); }
                @Override public float north(int vertex) { return borrow.north(vertex); }
                @Override public float west(int vertex) { return borrow.west(vertex); }
                @Override public float up(int vertex) { return borrow.up(vertex); }
                @Override public int depth(int vertex) { return borrow.depth(vertex); }
            }, token);
        }

        /** Depth bytes a merged block's drawn depth may differ from a source vertex's. */
        static final float MERGE_DEPTH_TOLERANCE = 1f;

        /** Block sizes in cells, finest first, that a tile's flat full chunks may be kept at. */
        private static final int[] BLOCKS = {1, 2, 4, 8};

        /** A full 8 x 8 layer whose used heights are all equal: its blocks can merge. */
        static boolean flatFull(Cells borrow, int layer) {
            return borrow.width(layer) == 8
                    && borrow.height(layer) == 8
                    && borrow.mask(layer) == -1L
                    && borrow.planar(layer);
        }

        /**
         * Quads one layer costs when blocks finer than {@code finest} cells always merge. A flat
         * full chunk is split as a quadtree (8, 4, 2, 1 cells): a block becomes one quad when it
         * is {@code finest} cells or when that quad draws every source depth in it within
         * {@link #MERGE_DEPTH_TOLERANCE}. The depth byte drives the water's colour and opacity,
         * so a coastal chunk keeps its shallow strip. Any other layer keeps every cell.
         * {@code source} is the layer's first vertex.
         */
        static int layerQuads(Cells borrow, int layer, int source, int finest) {
            return flatFull(borrow, layer)
                    ? blockQuads(borrow, source, 0, 0, 8, finest, null)
                    : Long.bitCount(borrow.mask(layer));
        }

        /**
         * Counts, and with {@code out} also copies, the quads of one block of a flat full chunk:
         * {@code size} cells from row {@code row}, column {@code column}.
         */
        private static int blockQuads(Cells borrow, int source, int row, int column, int size,
                int finest, Out out) {
            if (size <= finest || depthMatches(borrow, source, row, column, size)) {
                if (out != null) {
                    int last = size - 1;
                    out.quad(new int[] {
                        vertex(source, row, column, 0),
                        vertex(source, row, column + last, 1),
                        vertex(source, row + last, column, 2),
                        vertex(source, row + last, column + last, 3)});
                }
                return 1;
            }
            int half = size / 2;
            return blockQuads(borrow, source, row, column, half, finest, out)
                    + blockQuads(borrow, source, row, column + half, half, finest, out)
                    + blockQuads(borrow, source, row + half, column, half, finest, out)
                    + blockQuads(borrow, source, row + half, column + half, half, finest, out);
        }

        /** Cells are Y-major, each emitting NW, NE, SW, SE. */
        private static int vertex(int source, int row, int column, int corner) {
            return source + (row * 8 + column) * 4 + corner;
        }

        /**
         * True when one quad over the block, split on the NE--SW diagonal like every quad,
         * interpolates each of its cells' corner depths within the tolerance.
         */
        private static boolean depthMatches(Cells borrow, int source, int row, int column,
                int size) {
            if (size == 1) return true;
            int last = size - 1;
            float nw = borrow.depth(vertex(source, row, column, 0));
            float ne = borrow.depth(vertex(source, row, column + last, 1));
            float sw = borrow.depth(vertex(source, row + last, column, 2));
            float se = borrow.depth(vertex(source, row + last, column + last, 3));
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    for (int corner = 0; corner < 4; corner++) {
                        // u runs west to east and v north to south over the block, 0..1.
                        float u = (c + (corner & 1)) / (float) size;
                        float v = (r + (corner >> 1)) / (float) size;
                        float drawn = u + v <= 1
                                ? nw + u * (ne - nw) + v * (sw - nw)
                                : se + (1 - u) * (sw - se) + (1 - v) * (ne - se);
                        float actual = borrow.depth(vertex(source, row + r, column + c, corner));
                        if (Math.abs(actual - drawn) > MERGE_DEPTH_TOLERANCE) return false;
                    }
                }
            }
            return true;
        }

        private interface Out {
            void quad(int[] corners);
        }

        static WaterCopy of(Cells borrow, Cancellation token) {
            int vertices = borrow.vertices(), indexCount = borrow.indices(), layers = borrow.layers();
            if (vertices < 4 || vertices % 4 != 0 || indexCount != vertices / 4 * 6 || layers < 1) {
                throw new IllegalArgumentException("Water exceeds packet profile");
            }
            var sources = new int[layers];
            for (int l = 0, at = 0; l < layers; l++) {
                sources[l] = at;
                at += Long.bitCount(borrow.mask(l)) * 4;
                if (at > vertices) throw new IllegalArgumentException("Water exceeds packet profile");
            }
            // The finest block size at which every layer fits the packet bound. A tile that fits
            // only at whole chunks (8) copies layers in source order up to the bound, and a layer
            // that would pass it is left out whole.
            int finest = BLOCKS[BLOCKS.length - 1];
            var counts = new int[layers];
            for (int block : BLOCKS) {
                token.check();
                long total = 0;
                for (int l = 0; l < layers; l++) {
                    counts[l] = layerQuads(borrow, l, sources[l], block);
                    total += counts[l];
                }
                if (total <= WorldStreamTileWriter.Water.MAX_CELLS) {
                    finest = block;
                    break;
                }
            }
            int quads = 0, copied = 0, sourceCells = 0;
            boolean capped = false;
            for (int l = 0; l < layers; l++) {
                if (quads + counts[l] > WorldStreamTileWriter.Water.MAX_CELLS) {
                    capped = true;
                    break;
                }
                quads += counts[l];
                sourceCells += Long.bitCount(borrow.mask(l));
                copied++;
            }
            if (quads == 0) {
                throw new IllegalArgumentException("Water exceeds packet profile");
            }
            var positions = new float[quads * 12];
            var depths = new byte[quads * 4];
            var colours = new byte[quads * 4 * 3];
            var kinds = new byte[quads * 4];
            var indices = new int[quads * 6];
            int[] quad = {0};
            Out out = corners -> {
                for (int c = 0; c < 4; c++) {
                    put(borrow, corners[c], positions, depths, quad[0] * 4 + c);
                }
                quad[0]++;
            };
            for (int l = 0; l < copied; l++) {
                token.check();
                int cells = Long.bitCount(borrow.mask(l));
                int rgb = borrow.colour(l);
                if (rgb < -1 || rgb > 0xFFFFFF) {
                    throw new IllegalArgumentException("Water colour");
                }
                // A layer with its own flat colour (magma, fel, tar) is drawn flat and opaque; water
                // is shaded as ocean or river.
                byte kind =
                        rgb >= 0
                                ? WorldStreamTileWriter.Water.FLAT
                                : borrow.ocean(l)
                                        ? WorldStreamTileWriter.Water.OCEAN
                                        : WorldStreamTileWriter.Water.RIVER;
                rgb = rgb < 0 ? WorldStreamTileWriter.Water.WATER_RGB : rgb;
                int first = quad[0];
                if (flatFull(borrow, l)) {
                    blockQuads(borrow, sources[l], 0, 0, 8, finest, out);
                } else {
                    for (int c = 0; c < cells; c++) {
                        int v = sources[l] + c * 4;
                        out.quad(new int[] {v, v + 1, v + 2, v + 3});
                    }
                }
                for (int v = first * 4; v < quad[0] * 4; v++) {
                    colours[v * 3] = (byte) (rgb >>> 16);
                    colours[v * 3 + 1] = (byte) (rgb >>> 8);
                    colours[v * 3 + 2] = (byte) rgb;
                    kinds[v] = kind;
                }
            }
            // The source's explicit NE--SW diagonal for every quad.
            for (int q = 0; q < quads; q++) {
                int b = q * 4;
                indices[q * 6] = b;
                indices[q * 6 + 1] = b + 2;
                indices[q * 6 + 2] = b + 1;
                indices[q * 6 + 3] = b + 1;
                indices[q * 6 + 4] = b + 2;
                indices[q * 6 + 5] = b + 3;
            }
            return new WaterCopy(
                    new WorldStreamTileWriter.Water(
                            positions, depths, colours, kinds, indices, copied),
                    sourceCells, copied, capped, finest);
        }

        private static void put(Cells borrow, int vertex,
                float[] positions, byte[] depths, int at) {
            positions[at * 3] = borrow.north(vertex);
            positions[at * 3 + 1] = borrow.west(vertex);
            positions[at * 3 + 2] = borrow.up(vertex);
            int depth = borrow.depth(vertex);
            if (depth < 0 || depth > 255) {
                throw new IllegalArgumentException("Water depth byte");
            }
            depths[at] = (byte) depth;
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
                packet.splat(splatInput(borrow, material, splat.get(), stopping));
            } catch (RuntimeException failure) {
                packet.close();
                throw failure;
            }
        }
        return packet;
    }

    /** The tile's splatting inputs, decoding a layer diffuse only when the writer asks for it. */
    static WorldStreamTileWriter.SplatInput splatInput(
            WorldTerrainStreaming.CheckedBorrow borrow,
            WorldTerrainStreaming.Material material,
            com.vibeminer.rendering.world.TerrainTileSplat splat,
            BooleanSupplier stopping) {
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
        // The producer's stop reaches each layer decode: a stopped tile reads no more diffuses.
        var token = new Cancellation();
        return new WorldStreamTileWriter.SplatInput(ids, keys, table, splat.alpha(),
                id -> {
                    if (stopping.getAsBoolean()) token.cancel();
                    return borrow.diffuseDetail(id, token)
                            .map(detail -> new WorldStreamTileWriter.Diffuse(detail.chain()));
                });
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
