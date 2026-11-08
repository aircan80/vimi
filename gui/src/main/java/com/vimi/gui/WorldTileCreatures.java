package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.models.BuildDisplayModels;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceNpcs;
import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTileWriter;
import com.vibeminer.rendering.models.MeshAppearance;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Static creature models for declared NPC spawns, appended to one terrain tile's late objects so
 * the terrain child draws them in its own depth-shared object pass (issue #175, third slice).
 *
 * <p>Only a spawn with a recorded height whose NPC has a {@link CreatureModelPolicy} QUALIFIED
 * display is drawn. The display's static preview must need no character appearance, and each
 * geoset group draws one variant decided from recorded facts ({@link #visibleGeosets}: when the
 * model records geoset data, the display's own geoset rows choose and unchosen groups 1 to 8 are
 * hidden; otherwise group 0 draws section 0 and every other group its x01 section, or nothing);
 * only its opaque and alpha-keyed
 * (cutout) diffuse surfaces are drawn, with
 * textures reduced to at most {@value #IMAGE_SIDE} pixels a side. Facing is not recorded by the source, so every
 * model faces native north. Each spawn belongs to exactly one tile (the filename-index rule), and
 * selection is nearest to the tile centre first, so it never depends on the camera. Everything
 * else is counted by reason, never guessed. The spawn index, choices and converted models are
 * cached per source revision; work runs on the caller's (exterior objects) thread.
 */
final class WorldTileCreatures implements AutoCloseable {
    static final int IMAGE_SIDE = 256;

    /** Range material classes, as in the objects file: opaque and alpha-key cutout. */
    static final int OPAQUE = com.vibeminer.rendering.world.ObjectTextureMips.OPAQUE,
            CUTOUT = com.vibeminer.rendering.world.ObjectTextureMips.CUTOUT;

    /** Per tile, on top of the shared objects file profile that buildings use first. */
    static final int TILE_MODELS = 8, TILE_PLACEMENTS = 32, TILE_PREVIEWS = 16;

    /** Whole-model ceiling so one creature never takes a building tile's budget. */
    static final int MODEL_VERTICES = 32_768, MODEL_INDICES = 98_304;

    static final int MODEL_CACHE = 32, CHOICE_CACHE = 4096;

    /** Back-off before a failed spawn index load is tried again within one revision. */
    static final long INDEX_RETRY_NANOS = 60_000_000_000L;

    /** Exactly one bucket per considered spawn point of the tile. */
    enum Count {
        DRAWN("drawn"),
        NOT_QUALIFIED("no single qualified model"),
        /** A character (humanoid NPC) appearance that is refused or not provided; cached. */
        CHARACTER_APPEARANCE("character appearance unavailable"),
        /**
         * A humanoid NPC appearance whose data cannot be read yet (#385): not cached, asked again on
         * a later tile, and asked once per tile.
         */
        CHARACTER_RETRY("character appearance not readable yet"),
        VISIBILITY_UNRESOLVED("surface visibility unresolved"),
        NO_OPAQUE_SURFACE("no opaque or alpha-keyed textured surface"),
        /**
         * Every drawable triangle of its Stand pose is weighted to a camera-facing bone, which the
         * World does not draw: refused, as World objects refuse such a model (#360, #384).
         */
        ALL_BILLBOARD("only camera-facing surfaces"),
        MODEL_UNAVAILABLE("model unavailable"),
        /** The preview component was busy or closed; not cached and costs no preview budget. */
        PREVIEWS_BUSY("model reader busy or closed"),
        CAPPED("over the tile budget"),
        FAILED("lookup failed");

        final String label;

        Count(String label) {
            this.label = label;
        }
    }

    /** One tile's creature outcome; immutable. */
    static final class Summary {
        static final Summary NONE = new Summary(new int[Count.values().length], 0, 0, 0, NpcCounts.NONE, null,
                List.of());

        private final int[] counts;
        private final int models, restPosed, billboardsLeftOut;
        private final NpcCounts npcs;
        private final String note;
        private final List<WorldObjectPicks.Creature> placed;

        private Summary(int[] counts, int models, int restPosed, int billboardsLeftOut, NpcCounts npcs,
                String note, List<WorldObjectPicks.Creature> placed) {
            this.counts = counts.clone();
            this.models = models;
            this.restPosed = restPosed;
            this.billboardsLeftOut = billboardsLeftOut;
            this.npcs = Objects.requireNonNull(npcs);
            this.note = note;
            this.placed = List.copyOf(placed);
        }

        static Summary failed(String note) {
            return new Summary(new int[Count.values().length], 0, 0, 0, NpcCounts.NONE,
                    Objects.requireNonNull(note), List.of());
        }

        /** Drawn humanoid NPC placements and what their items left undrawn (#385). */
        NpcCounts npcs() {
            return npcs;
        }

        /** Drawn placements whose model is in its rest (bind) pose because no Stand pose was baked. */
        int restPosed() {
            return restPosed;
        }

        /**
         * Drawn placements whose Stand pose leaves out triangles weighted to camera-facing
         * (billboard) bones, as World objects do (#360, #384).
         */
        int billboardsLeftOut() {
            return billboardsLeftOut;
        }

        int count(Count count) {
            return counts[count.ordinal()];
        }

        int drawn() {
            return count(Count.DRAWN);
        }

        /** Spawn points considered but not drawn. */
        int omitted() {
            int sum = 0;
            for (var count : Count.values()) {
                if (count != Count.DRAWN) sum += counts[count.ordinal()];
            }
            return sum;
        }

        /** Distinct creature models added to the tile. */
        int models() {
            return models;
        }

        /**
         * One tile's drawn creatures in the order their placements were appended; empty for a sum
         * or a failure.
         */
        List<WorldObjectPicks.Creature> placed() {
            return placed;
        }

        /** Why nothing could be considered (no source, lookup failure), or null. */
        String note() {
            return note;
        }

        Summary plus(Summary other) {
            var sum = counts.clone();
            for (int i = 0; i < sum.length; i++) sum[i] += other.counts[i];
            return new Summary(sum, models + other.models, restPosed + other.restPosed,
                    billboardsLeftOut + other.billboardsLeftOut, npcs.plus(other.npcs),
                    note != null ? note : other.note, List.of());
        }
    }

    /**
     * Drawn humanoid NPC placements, the item records left out of their appearances, the item
     * models (helms, shoulders, held items) not drawn on them, the placements drawn with visible
     * sections left out (not plain diffuse opaque or alpha-keyed, such as blended hair) and those
     * sections, summed per placement.
     */
    record NpcCounts(int drawn, int omittedItems, int attachments, int withOmittedSurfaces,
            int omittedSurfaces) {
        static final NpcCounts NONE = new NpcCounts(0, 0, 0, 0, 0);

        NpcCounts plus(NpcCounts other) {
            return new NpcCounts(drawn + other.drawn, omittedItems + other.omittedItems,
                    attachments + other.attachments, withOmittedSurfaces + other.withOmittedSurfaces,
                    omittedSurfaces + other.omittedSurfaces);
        }
    }

    /**
     * Humanoid NPC appearances (#385): the race model with the NPC's recorded choices, clothing
     * geometry and pre-baked texture, by its extended display info.
     */
    @FunctionalInterface
    interface Npcs {
        /** No humanoid appearances: every humanoid display is a stated {@link Count#CHARACTER_APPEARANCE}. */
        Npcs NONE = (extendedDisplayInfoId, token) ->
                RaceNpcs.Appearance.refused("character appearances are not provided by this source");

        RaceNpcs.Appearance appearance(long extendedDisplayInfoId, Cancellation token) throws Exception;
    }

    /** Production NPC appearances from the workspace's races component, when it is open. */
    static Npcs npcs(Supplier<BuildRaces> races) {
        Objects.requireNonNull(races);
        return (extendedDisplayInfoId, token) -> {
            var handle = races.get();
            if (handle == null) return RaceNpcs.Appearance.refused("race data is not open");
            return handle.npcAppearance(extendedDisplayInfoId, token).join();
        };
    }

    /** A policy choice and the client-data scale of its display (null when not recorded). */
    record Chosen(CreatureModelPolicy.Choice choice, Double scale) {
        Chosen {
            Objects.requireNonNull(choice);
        }
    }

    @FunctionalInterface
    interface Chooser {
        Chosen choose(long npcId, Cancellation token) throws Exception;
    }

    @FunctionalInterface
    interface Previews {
        /**
         * The display's static model as the World binds it ({@link
         * com.vibeminer.concepts.models.BuildDisplayModels#worldPreview}).
         */
        DisplayModelTypes.Preview preview(long displayId, Cancellation token) throws Exception;

        /**
         * The display's Stand sequence over the same rest surface as {@link #preview}. A source
         * without idle sequences answers UNSUPPORTED, a lasting answer cached with the model.
         */
        default DisplayModelTypes.Idle idle(long displayId, Cancellation token) throws Exception {
            return new DisplayModelTypes.Idle(DisplayModelTypes.IdleState.UNSUPPORTED, null, null,
                    "Idle animation is not provided by this source.");
        }

        /**
         * The display's Stand for the World ({@link
         * com.vibeminer.concepts.models.BuildDisplayModels#worldIdle}): camera-facing bones are
         * posed as ordinary bones and the vertices weighted to them are listed as left out.
         */
        default DisplayModelTypes.Idle worldIdle(long displayId, Cancellation token) throws Exception {
            return idle(displayId, token);
        }

        /** {@link #idle} then {@link #worldIdle}, for measurements; may read the model once. */
        default List<DisplayModelTypes.Idle> stands(long displayId, Cancellation token) throws Exception {
            return List.of(idle(displayId, token), worldIdle(displayId, token));
        }
    }

    /**
     * RGBA8 image with power-of-two sides of at most {@link #IMAGE_SIDE}, as its full packed mip
     * chain ({@link com.vibeminer.rendering.world.ObjectTextureMips}).
     */
    record Image(int width, int height, byte[] rgba) {}

    /**
     * A converted static model in M2 model space (Z up, +X forward): 8 floats per vertex
     * (position, normal, uv), u16-range indices, (first index, index count, local image) ranges and
     * one material class per range ({@link #OPAQUE} or {@link #CUTOUT}).
     */
    record Model(float[] vertices, int[] indices, int[] ranges, int[] blends, List<Image> images,
            int omittedSurfaces, String restPose, NpcCounts npc, int billboardTriangles) {
        Model(float[] vertices, int[] indices, int[] ranges, int[] blends, List<Image> images,
                int omittedSurfaces) {
            this(vertices, indices, ranges, blends, images, omittedSurfaces, null, null, 0);
        }

        Model(float[] vertices, int[] indices, int[] ranges, int[] blends, List<Image> images,
                int omittedSurfaces, String restPose) {
            this(vertices, indices, ranges, blends, images, omittedSurfaces, restPose, null, 0);
        }

        /** The same model with why it stays in its rest pose (null: baked in the Stand pose). */
        Model withRestPose(String reason) {
            return new Model(vertices, indices, ranges, blends, images, omittedSurfaces, reason, npc,
                    billboardTriangles);
        }

        /** The same model as one humanoid NPC's, with what each placement leaves undrawn. */
        Model withNpc(int omittedItems, int attachments) {
            return new Model(vertices, indices, ranges, blends, images, omittedSurfaces, restPose,
                    new NpcCounts(1, omittedItems, attachments, omittedSurfaces > 0 ? 1 : 0, omittedSurfaces),
                    billboardTriangles);
        }

        Model {
            images = List.copyOf(images);
            if (blends.length * 3 != ranges.length) throw new IllegalArgumentException("Range classes");
        }

        int vertexCount() {
            return vertices.length / 8;
        }

        long pixels() {
            long sum = 0;
            for (var image : images) {
                sum += com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(
                        image.width(), image.height());
            }
            return sum;
        }
    }

    /**
     * A display's conversion: a model, or the bucket that refuses it. {@code transientPose} marks a
     * model drawn in its rest pose only because its Stand sequence could not be read yet; it is
     * drawn on this tile but not cached, so a later tile asks again.
     */
    record Converted(Model model, Count refusal, String detail, boolean transientPose) {
        Converted(Model model, Count refusal, String detail) {
            this(model, refusal, detail, false);
        }

        Converted {
            if ((model == null) == (refusal == null)) throw new IllegalArgumentException("Inconsistent conversion");
        }

        /** A refusal asked again on a later tile instead of being cached. */
        static Converted later(Count refusal, String detail) {
            return new Converted(null, refusal, detail, true);
        }

        static Converted refused(Count refusal, String detail) {
            return new Converted(null, refusal, detail);
        }
    }

    private final WorldSpawnOverlay.Loader loader;
    private final Supplier<Object> revision;
    private final Chooser chooser;
    private final Previews previews;
    private final Npcs npcs;
    private final Map<Long, Chosen> choices = lru(CHOICE_CACHE);
    private final Map<Long, Converted> models = lru(MODEL_CACHE);
    private Object cachedRevision;
    private CompletableFuture<WorldSpawns.Index> index;
    private long indexFailedAt;
    private final java.util.function.LongSupplier clock;
    private Cancellation indexLoad;
    private boolean closed;

    WorldTileCreatures(WorldSpawnOverlay.Loader loader, Supplier<Object> revision, Chooser chooser, Previews previews) {
        this(loader, revision, chooser, previews, Npcs.NONE);
    }

    WorldTileCreatures(WorldSpawnOverlay.Loader loader, Supplier<Object> revision, Chooser chooser, Previews previews,
            Npcs npcs) {
        this(loader, revision, chooser, previews, npcs, System::nanoTime);
    }

    /** Tests supply the clock that times the index back-off. */
    WorldTileCreatures(WorldSpawnOverlay.Loader loader, Supplier<Object> revision, Chooser chooser, Previews previews,
            java.util.function.LongSupplier clock) {
        this(loader, revision, chooser, previews, Npcs.NONE, clock);
    }

    WorldTileCreatures(WorldSpawnOverlay.Loader loader, Supplier<Object> revision, Chooser chooser, Previews previews,
            Npcs npcs, java.util.function.LongSupplier clock) {
        this.npcs = Objects.requireNonNull(npcs);
        this.clock = Objects.requireNonNull(clock);
        this.loader = Objects.requireNonNull(loader);
        this.revision = Objects.requireNonNull(revision);
        this.chooser = Objects.requireNonNull(chooser);
        this.previews = Objects.requireNonNull(previews);
    }

    private static <V> Map<Long, V> lru(int capacity) {
        return new LinkedHashMap<>(64, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Production chooser: the data-only creature chain of the opened build. */
    static Chooser chooser(Supplier<BuildCreatures> creatures) {
        return (npcId, token) -> {
            var handle = creatures.get();
            if (handle == null) return new Chosen(CreatureModelPolicy.unknown("Creature data is not open"), null);
            if (npcId < 0 || npcId > 0xffffffffL)
                return new Chosen(CreatureModelPolicy.unknown("NPC ID is outside the client creature range"), null);
            var result = handle.getData(npcId, TableTypes.Priority.BACKGROUND, token).join();
            token.check();
            var choice = CreatureModelPolicy.choose(result);
            return new Chosen(choice, choice.displayId() == null ? null
                    : CreatureModelPolicy.scale(result, choice.displayId()));
        };
    }

    /**
     * Production previews: one display-model component per source revision, closed when the
     * revision changes or this source closes.
     */
    static final class DisplayPreviews implements Previews, AutoCloseable {
        private final Supplier<BuildDisplayModels> factory;
        private final Supplier<Object> revision;
        private BuildDisplayModels current;
        private Object currentRevision;
        private boolean closed;

        DisplayPreviews(Supplier<BuildDisplayModels> factory, Supplier<Object> revision) {
            this.factory = Objects.requireNonNull(factory);
            this.revision = Objects.requireNonNull(revision);
        }

        @Override
        public DisplayModelTypes.Preview preview(long displayId, Cancellation token) {
            var models = component();
            if (models == null) {
                return new DisplayModelTypes.Preview(DisplayModelTypes.State.UNAVAILABLE, null, null,
                        java.util.Set.of(), List.of(), false, "Model files are not open");
            }
            // The World's binding (#361): the World objects' batch rule, not the preview's profiles.
            return models.worldPreview(displayId, token).join();
        }

        @Override
        public DisplayModelTypes.Idle idle(long displayId, Cancellation token) {
            var models = component();
            return models == null ? DisplayModelTypes.Idle.unavailable("Model files are not open")
                    : models.idle(displayId, token).join();
        }

        @Override
        public DisplayModelTypes.Idle worldIdle(long displayId, Cancellation token) {
            var models = component();
            return models == null ? DisplayModelTypes.Idle.unavailable("Model files are not open")
                    : models.worldIdle(displayId, token).join();
        }

        @Override
        public List<DisplayModelTypes.Idle> stands(long displayId, Cancellation token) {
            var models = component();
            if (models == null) {
                var closed = DisplayModelTypes.Idle.unavailable("Model files are not open");
                return List.of(closed, closed);
            }
            return models.stands(displayId, token).join();
        }

        private BuildDisplayModels component() {
            synchronized (this) {
                if (closed) throw new CancellationException("Creature models closed");
                var now = revision.get();
                if (current == null || !Objects.equals(now, currentRevision)) {
                    if (current != null) current.closeAsync();
                    current = factory.get();
                    currentRevision = now;
                }
                return current;
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (current != null) current.closeAsync();
            current = null;
        }
    }

    /**
     * Appends the tile's drawable creatures to {@code builder} after whatever it already holds
     * (buildings first). Models are added whole or not at all. Only cancellation propagates.
     */
    Summary append(WorldStreamTileWriter.Exterior.Builder builder, long mapId, WorldFormats.Tile tile,
            Cancellation token) {
        Objects.requireNonNull(builder);
        Objects.requireNonNull(tile);
        Objects.requireNonNull(token);
        WorldSpawns.Index spawns;
        try {
            spawns = index(token);
        } catch (CancellationException canceled) {
            if (token.isCanceled()) throw canceled;
            return Summary.failed("creature source closed or retired");
        } catch (RuntimeException failed) {
            token.check();
            WorldDiagnostics.degraded("creature spawns", "World creature spawn source failed", failed);
            return Summary.failed("spawn source failed: " + failed.getClass().getSimpleName());
        }
        if (spawns.spawns() == 0) return Summary.failed("no prepared spawn source");
        double size = WorldFormats.TILE_SIZE;
        double centerX = (31.5 - tile.x()) * size, centerY = (31.5 - tile.y()) * size;
        // Only this tile's own points take the query's limit: a dense neighbour tile inside the
        // circle around the tile can no longer starve them.
        var nearby = spawns.near(mapId, centerX, centerY, size * Math.sqrt(.5) + 1, WorldSpawns.MAX_NEARBY,
                spawn -> WorldBookmarks.tileIndex(spawn.x()) == tile.x()
                        && WorldBookmarks.tileIndex(spawn.y()) == tile.y());
        var counts = new int[Count.values().length];
        var added = new HashMap<Long, Integer>();
        var placed = new java.util.ArrayList<WorldObjectPicks.Creature>();
        int placements = 0, previewsTried = 0;
        // Displays whose preview was busy, or whose NPC appearance cannot be read yet, on this
        // tile: not asked again for every later spawn.
        var busyDisplays = new HashMap<Long, Count>();
        // Builder model indices drawn in their rest pose; their placements are disclosed.
        var restModels = new HashSet<Integer>();
        // Builder model indices of humanoid NPCs, with what each placement leaves undrawn.
        var npcModels = new HashMap<Integer, NpcCounts>();
        var npcCounts = NpcCounts.NONE;
        int restPosed = 0, billboardsLeftOut = 0;
        // Builder model indices whose Stand pose left out billboard triangles.
        var billboardModels = new HashSet<Integer>();
        for (var spawn : nearby.placed()) {
            token.check();
            if (WorldBookmarks.tileIndex(spawn.x()) != tile.x() || WorldBookmarks.tileIndex(spawn.y()) != tile.y()) continue;
            // Once nothing more can be placed, the rest is capped without any lookup.
            if (placements == TILE_PLACEMENTS || builder.placementRoom() == 0 || builder.drawRoom() == 0) {
                counts[Count.CAPPED.ordinal()]++;
                continue;
            }
            Chosen chosen;
            try {
                chosen = chosen(spawn.npcId(), token);
            } catch (CancellationException canceled) {
                // Only this tile's cancellation propagates; a component's own is a failed lookup.
                if (token.isCanceled()) throw canceled;
                counts[Count.FAILED.ordinal()]++;
                continue;
            } catch (Exception failed) {
                token.check();
                WorldDiagnostics.degraded("creature lookups", "World creature model lookup failed", failed);
                counts[Count.FAILED.ordinal()]++;
                continue;
            }
            var choice = chosen.choice();
            if (choice.state() != CreatureModelPolicy.State.QUALIFIED || choice.displayId() == null) {
                counts[Count.NOT_QUALIFIED.ordinal()]++;
                continue;
            }
            long display = choice.displayId();
            Integer model = added.get(display);
            if (model == null) {
                Converted converted;
                synchronized (this) {
                    converted = models.get(display);
                }
                if (converted == null && busyDisplays.containsKey(display)) {
                    counts[busyDisplays.get(display).ordinal()]++;
                    continue;
                }
                if (converted == null) {
                    if (previewsTried == TILE_PREVIEWS) {
                        counts[Count.CAPPED.ordinal()]++;
                        continue;
                    }
                    previewsTried++;
                    converted = load(display, token);
                    // A busy or closed reader learned nothing: refund the preview it cost. An NPC
                    // appearance that cannot be read yet keeps its cost but is asked once per tile.
                    if (converted.refusal() == Count.PREVIEWS_BUSY) {
                        previewsTried--;
                        busyDisplays.put(display, Count.PREVIEWS_BUSY);
                    } else if (converted.refusal() == Count.CHARACTER_RETRY) {
                        busyDisplays.put(display, Count.CHARACTER_RETRY);
                    }
                }
                if (converted.refusal() != null) {
                    counts[converted.refusal().ordinal()]++;
                    continue;
                }
                var m = converted.model();
                if (added.size() == TILE_MODELS
                        || m.ranges().length / 3 > builder.drawRoom()
                        || !builder.fits(m.images().size(), m.pixels(), 1, m.vertexCount(), m.indices().length,
                                m.ranges().length / 3)) {
                    counts[Count.CAPPED.ordinal()]++;
                    continue;
                }
                model = add(builder, m);
                added.put(display, model);
                if (m.restPose() != null) restModels.add(model);
                if (m.billboardTriangles() > 0) billboardModels.add(model);
                if (m.npc() != null) npcModels.put(model, m.npc());
            }
            if (builder.drawCost(model) > builder.drawRoom()) {
                counts[Count.CAPPED.ordinal()]++;
                continue;
            }
            var transform = transform(spawn, chosen);
            builder.placement(model, transform[0], transform[1], transform[2], transform[3], transform[4],
                    transform[5]);
            placed.add(new WorldObjectPicks.Creature(spawn, spawns.npc(spawn.npcId()).orElse(null), choice,
                    transform[3], new WorldObjectPicks.Key(model, transform[0], transform[1], transform[2],
                            transform[3])));
            placements++;
            if (restModels.contains(model)) restPosed++;
            if (billboardModels.contains(model)) billboardsLeftOut++;
            var npc = npcModels.get(model);
            if (npc != null) npcCounts = npcCounts.plus(npc);
            counts[Count.DRAWN.ordinal()]++;
        }
        return new Summary(counts, added.size(), restPosed, billboardsLeftOut, npcCounts, nearby.omitted() == 0 ? null
                : nearby.omitted() + " nearby points beyond the query limit were not considered", placed);
    }

    /**
     * x, y, z, scale, cos, sin of one drawn spawn. The recorded point is the model origin (feet);
     * the client-data scale applies when recorded, else 1. cos -1, sin 0 makes the reviewed ADT
     * heading matrix the identity, so M2 +X (forward) faces native +X (north): facing is not
     * recorded by the source.
     */
    static double[] transform(WorldSpawns.Spawn spawn, Chosen chosen) {
        double scale = chosen.scale() == null ? 1 : chosen.scale();
        return new double[] {spawn.x(), spawn.y(), spawn.z(), scale, -1, 0};
    }

    private Chosen chosen(long npcId, Cancellation token) throws Exception {
        synchronized (this) {
            var cached = choices.get(npcId);
            if (cached != null) return cached;
        }
        var chosen = chooser.choose(npcId, token);
        token.check();
        synchronized (this) {
            if (!closed) choices.put(npcId, chosen);
        }
        return chosen;
    }

    private Converted load(long display, Cancellation token) {
        Converted converted;
        try {
            var preview = previews.preview(display, token);
            token.check();
            var extended = preview == null || preview.reference() == null ? null
                    : preview.reference().extendedDisplayInfoId();
            converted = extended != null && extended > 0 ? npc(extended, token) : stand(display, preview, token);
        } catch (Exception failed) {
            // Only this tile's cancellation propagates; a closed or retired component is a failure.
            token.check();
            if (busy(failed))
                return Converted.refused(Count.PREVIEWS_BUSY, failed.getClass().getSimpleName());
            WorldDiagnostics.degraded("creature models", "World creature model read failed", failed);
            // Not cached: a failed read may succeed on the next tile.
            return Converted.refused(Count.FAILED, failed.getClass().getSimpleName());
        }
        if (converted.transientPose()) return converted; // asked again on a later tile
        synchronized (this) {
            if (!closed) models.put(display, converted);
        }
        return converted;
    }

    /**
     * Converts the preview in its Stand pose (issue #377): the display's idle sequence evaluated at
     * its first frame, baked once on the CPU. When there is no qualified Stand sequence for the
     * same rest surface, the rest pose is converted and the reason recorded on the model. An
     * UNSUPPORTED sequence or one for another rest surface is a lasting answer and is cached with
     * the model. An UNAVAILABLE sequence ("could not be read; retry can reassess it") or an idle
     * read failure draws the rest pose on this tile only: it is not cached. A busy or closed reader
     * and a cancellation propagate.
     */
    Converted stand(long display, DisplayModelTypes.Preview preview, Cancellation token) throws Exception {
        if (preview == null || preview.appearance() == null) return convert(preview, token);
        String restPose;
        boolean retry = false;
        var appearance = preview.appearance();
        int[] leftOut = new int[0];
        try {
            var idle = previews.worldIdle(display, token);
            token.check();
            if (idle == null || idle.state() != DisplayModelTypes.IdleState.AVAILABLE) {
                restPose = idle == null ? "no idle result" : idle.message();
                retry = idle == null || idle.state() == DisplayModelTypes.IdleState.UNAVAILABLE;
            } else if (!idle.animation().matches(appearance)) {
                restPose = "the Stand sequence belongs to another rest surface";
            } else {
                appearance = idle.animation().pose(appearance, 0);
                leftOut = idle.leftOut();
                restPose = null;
            }
        } catch (Exception failed) {
            token.check();
            if (busy(failed) || failed instanceof CancellationException) throw failed;
            restPose = "Stand pose failed: " + failed.getClass().getSimpleName();
            retry = true;
        }
        var converted = convert(new DisplayModelTypes.Preview(preview.state(), preview.reference(), appearance,
                preview.visibleParts(), preview.issues(), preview.restored(), preview.message()), leftOut, token);
        return converted.model() == null || restPose == null ? converted
                : new Converted(converted.model().withRestPose(restPose), null, null, retry);
    }

    /**
     * A humanoid NPC's display (#385): drawn whole from its race appearance, which is static (rest
     * pose, disclosed), with its item models left undrawn and counted; refused as
     * {@link Count#CHARACTER_APPEARANCE} (cached), or {@link Count#CHARACTER_RETRY} when its data
     * cannot be read yet (asked again on a later tile). Cancellation and a busy reader propagate.
     */
    Converted npc(long extendedDisplayInfoId, Cancellation token) throws Exception {
        var answer = npcs.appearance(extendedDisplayInfoId, token);
        token.check();
        if (answer == null) return Converted.refused(Count.CHARACTER_APPEARANCE, "no NPC appearance");
        if (answer.refusal() != null)
            return answer.retry() ? Converted.later(Count.CHARACTER_RETRY, answer.refusal())
                    : Converted.refused(Count.CHARACTER_APPEARANCE, answer.refusal());
        var result = answer.result();
        if (result.appearance() == null)
            return Converted.refused(Count.CHARACTER_APPEARANCE,
                    result.message() == null ? "no NPC appearance" : result.message());
        var visible = result.visibleParts();
        var mesh = result.appearance().mesh();
        if (mesh.parts().stream().noneMatch(part -> visible.contains(part.id())))
            return Converted.refused(Count.VISIBILITY_UNRESOLVED, "the NPC appearance shows no geoset");
        var converted = surfaces(result.appearance(), visible, token);
        return converted.model() == null ? converted
                : new Converted(converted.model()
                        .withNpc(answer.omittedItems(), answer.omittedAttachments())
                        .withRestPose("NPC appearances are static"), null, null);
    }

    /** Whether a preview failure is the component's busy or closed refusal, however wrapped. */
    static boolean busy(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof DisplayModelTypes.ServiceUnavailable) return true;
        }
        return false;
    }

    private static int add(WorldStreamTileWriter.Exterior.Builder builder, Model model) {
        var images = new int[model.images().size()];
        for (int i = 0; i < images.length; i++) {
            var image = model.images().get(i);
            images[i] = builder.image(image.width(), image.height(), ByteBuffer.wrap(image.rgba()));
        }
        var ranges = model.ranges().clone();
        for (int r = 2; r < ranges.length; r += 3) ranges[r] = images[ranges[r]];
        int index = builder.model();
        builder.mesh(FloatBuffer.wrap(model.vertices()), IntBuffer.wrap(model.indices()), ranges,
                model.blends().clone());
        return index;
    }

    /**
     * The tile-independent BASIC conversion of one display's static preview. Refuses character
     * appearance, models without an opaque or alpha-keyed diffuse surface, and every geoset choice
     * it cannot make from recorded facts ({@link #visibleGeosets}), including a model whose every
     * section the display's rows hide. Never a guessed variant.
     */
    static Converted convert(DisplayModelTypes.Preview preview, Cancellation token) {
        return convert(preview, new int[0], token);
    }

    /** As {@link #convert(DisplayModelTypes.Preview, Cancellation)}, leaving out {@code leftOut}. */
    static Converted convert(DisplayModelTypes.Preview preview, int[] leftOut, Cancellation token) {
        if (preview == null || preview.appearance() == null || preview.reference() == null)
            return Converted.refused(Count.MODEL_UNAVAILABLE,
                    preview == null || preview.message() == null ? "no static preview" : preview.message());
        var reference = preview.reference();
        if (reference.extendedDisplayInfoId() == null || reference.extendedDisplayInfoId() != 0)
            return Converted.refused(Count.CHARACTER_APPEARANCE, "extended display info is not evaluated");
        var appearance = preview.appearance();
        var mesh = appearance.mesh();
        var visible = visibleGeosets(reference, mesh);
        if (visible.refusal() != null) return Converted.refused(Count.VISIBILITY_UNRESOLVED, visible.refusal());
        // Nothing left to draw is a visibility outcome, not a material one.
        if (mesh.parts().stream().noneMatch(part -> visible.ids().contains(part.id())))
            return Converted.refused(Count.VISIBILITY_UNRESOLVED, rowsDecideSome(reference, mesh)
                    ? "every geoset is hidden by the display's rows"
                    : "the model has no section 0 or x01 section to draw");
        return surfaces(appearance, visible.ids(), leftOut, token);
    }

    /**
     * The opaque and alpha-keyed diffuse surfaces of the {@code visible} geoset sections of one
     * appearance, as a model; rigid cards are dropped.
     */
    static Converted surfaces(MeshAppearance appearance, Set<Integer> visibleIds, Cancellation token) {
        return surfaces(appearance, visibleIds, new int[0], token);
    }

    /**
     * As {@link #surfaces(MeshAppearance, Set, Cancellation)}, also leaving out every triangle with
     * a vertex in {@code leftOut} (weighted to a camera-facing bone, #384), counted on the model. A
     * surface left with no triangle is an omitted surface, as one made only of rigid cards is.
     */
    static Converted surfaces(MeshAppearance appearance, Set<Integer> visibleIds, int[] leftOut,
            Cancellation token) {
        var mesh = appearance.mesh();
        var billboard = new HashSet<Integer>();
        for (int vertex : leftOut) billboard.add(vertex);
        int billboardTriangles = 0;
        var surfaces = new HashMap<Integer, MeshAppearance.Surface>();
        for (var surface : appearance.surfaces()) surfaces.put(surface.sectionIndex(), surface);
        var cards = new HashSet<Integer>();
        for (var card : appearance.billboards()) for (int vertex : card.vertexIndices()) cards.add(vertex);
        float[] positions = mesh.positions(), uv = appearance.uv(), normals = appearance.normals();
        int[] source = mesh.indices();
        var textures = appearance.textures();
        var remap = new HashMap<Integer, Integer>();
        var vertices = new ArrayList<Float>();
        var indices = new ArrayList<Integer>();
        var ranges = new ArrayList<Integer>();
        var blends = new ArrayList<Integer>();
        var imageSlots = new HashMap<Integer, Integer>();
        var images = new ArrayList<Image>();
        int omitted = 0;
        for (int ordinal = 0; ordinal < mesh.parts().size(); ordinal++) {
            token.check();
            var part = mesh.parts().get(ordinal);
            // Hidden geosets are not surface omissions: the section is hidden by the chosen geosets.
            if (!visibleIds.contains(part.id())) continue;
            var surface = surfaces.get(ordinal);
            var blend = surface == null ? null : surface.material().blend();
            if (surface == null
                    || surface.material().combiner() != MeshAppearance.Combiner.DIFFUSE
                    || blend != MeshAppearance.Blend.OPAQUE && blend != MeshAppearance.Blend.ALPHA_KEY
                    || surface.textureIndex() >= textures.size()) {
                omitted++;
                continue;
            }
            int materialClass = blend == MeshAppearance.Blend.ALPHA_KEY ? CUTOUT : OPAQUE;
            int first = indices.size();
            for (int at = part.firstIndex(); at + 2 < part.firstIndex() + part.indexCount(); at += 3) {
                int a = source[at], b = source[at + 1], c = source[at + 2];
                // Rigid cards face the camera at run time; a fixed card would be a wrong claim.
                if (cards.contains(a) || cards.contains(b) || cards.contains(c)) continue;
                // Weighted to a camera-facing bone: left out, as World objects do (#360).
                if (billboard.contains(a) || billboard.contains(b) || billboard.contains(c)) {
                    billboardTriangles++;
                    continue;
                }
                for (int vertex : new int[] {a, b, c}) {
                    Integer mapped = remap.get(vertex);
                    if (mapped == null) {
                        mapped = remap.size();
                        remap.put(vertex, mapped);
                        float[] values = {positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2],
                            normals.length == 0 ? 0f : normals[vertex * 3],
                            normals.length == 0 ? 0f : normals[vertex * 3 + 1],
                            // Without recorded normals the model is lit as if facing up.
                            normals.length == 0 ? 1f : normals[vertex * 3 + 2],
                            uv[vertex * 2], uv[vertex * 2 + 1]};
                        for (float value : values) {
                            // The objects file's own vertex check, applied per model, not per tile.
                            if (!Float.isFinite(value) || Math.abs(value) > 1_000_000)
                                return Converted.refused(Count.MODEL_UNAVAILABLE, "vertex value outside the objects profile");
                            vertices.add(value);
                        }
                    }
                    indices.add(mapped);
                }
                if (remap.size() > MODEL_VERTICES || indices.size() > MODEL_INDICES)
                    return Converted.refused(Count.CAPPED, "model exceeds the creature vertex ceiling");
            }
            int count = indices.size() - first;
            if (count == 0) {
                omitted++;
                continue;
            }
            // One image per texture and material class: opaque forces alpha, cutout keeps it.
            int key = surface.textureIndex() * 2 + materialClass;
            Integer slot = imageSlots.get(key);
            if (slot == null) {
                var image = reduce(textures.get(surface.textureIndex()), materialClass, token);
                if (image.width() < 1 || image.height() < 1)
                    return Converted.refused(Count.MODEL_UNAVAILABLE, "texture has no pixels");
                slot = images.size();
                imageSlots.put(key, slot);
                images.add(image);
            }
            ranges.add(first);
            ranges.add(count);
            ranges.add(slot);
            blends.add(materialClass);
        }
        if ((ranges.isEmpty() || remap.size() < 3) && billboardTriangles > 0)
            return Converted.refused(Count.ALL_BILLBOARD,
                    "every drawable triangle is weighted to a camera-facing bone");
        if (ranges.isEmpty() || remap.size() < 3)
            return Converted.refused(Count.NO_OPAQUE_SURFACE, "no opaque or alpha-keyed diffuse surface with a texture");
        var vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertexArray.length; i++) vertexArray[i] = vertices.get(i);
        return new Converted(new Model(vertexArray, indices.stream().mapToInt(Integer::intValue).toArray(),
                ranges.stream().mapToInt(Integer::intValue).toArray(),
                blends.stream().mapToInt(Integer::intValue).toArray(), images, omitted, null, null,
                billboardTriangles), null, null);
    }

    /**
     * Whether the display's rows decide any of the model's sections: the model records geoset data
     * and has a section in a group from 1 to 8 or in a group a row chose. Otherwise only the
     * default decided what is hidden.
     */
    static boolean rowsDecideSome(DisplayModelTypes.Reference reference, com.vibeminer.rendering.models.PreviewMesh mesh) {
        if (reference.creatureGeosetDataId() == null || reference.creatureGeosetDataId() <= 0) return false;
        var chosen = reference.displayGeosets() == null ? Map.<Integer, Integer>of() : reference.displayGeosets();
        return mesh.parts().stream().anyMatch(part -> {
            int g = part.id() / 100;
            return g >= 1 && (g <= 8 || chosen.containsKey(g));
        });
    }

    /** The geoset IDs a model draws, or why that cannot be decided from recorded facts. */
    record Geosets(Set<Integer> ids, String refusal) {}

    /**
     * Which geoset sections draw (#361, #455). Sections are grouped by geoset ID / 100.
     *
     * <ul>
     *   <li>When the model's CreatureGeosetDataID is above 0, the display's own rows
     *       (CreatureDisplayInfoGeosetData: group = GeosetIndex + 1, section = group * 100 + value)
     *       choose: each listed group draws its chosen section, and a group from 1 to 8 without a
     *       row is hidden. Groups 9 and above without a row take the default below.
     *   <li>When it is 0, display rows are ignored and every group takes the default.
     *   <li>The default: group 0 draws section 0; every other group draws its x01 section, and a
     *       group without one draws nothing.
     * </ul>
     *
     * The gating follows wow.export ({@code DBCreatures.js}: rows are used only when the model's ID
     * is above 0, and then every geoset from 1 to 899 not listed is hidden). That is a viewer's
     * behaviour, not confirmed client behaviour. The x01 default follows the models themselves:
     * on build 70862, of 1,900 geoset groups 1 and above in 1,047 creature models without geoset
     * data, 1,134 have an x01 section (one of them also x00) and the other 766 have neither,
     * starting at x02 or later (optional pieces); x01 is also the character default. Nothing else is guessed: an unreadable ID,
     * unknown or conflicting rows when they apply, a chosen section the model does not have, and
     * a model without section 0 in group 0 all refuse.
     */
    static Geosets visibleGeosets(DisplayModelTypes.Reference reference, com.vibeminer.rendering.models.PreviewMesh mesh) {
        if (reference.creatureGeosetDataId() == null)
            return new Geosets(Set.of(), "creature geoset data could not be read");
        // A negative ID is treated like 0: only a positive one says the display rows apply.
        boolean rows = reference.creatureGeosetDataId() > 0;
        var chosen = reference.displayGeosets();
        if (rows && chosen == null) return new Geosets(Set.of(), "display geoset selections are unknown");
        var present = new java.util.TreeMap<Integer, java.util.TreeSet<Integer>>();
        for (var part : mesh.parts()) present.computeIfAbsent(part.id() / 100, g -> new java.util.TreeSet<>()).add(part.id());
        var ids = new HashSet<Integer>();
        if (rows) {
            for (var choice : chosen.entrySet()) {
                int group = choice.getKey(), value = choice.getValue();
                // Rows key groups GeosetIndex + 1, so never group 0; refuse to read one as such.
                if (group < 1) continue;
                if (value == DisplayModelTypes.Reference.CONFLICTING_GEOSET)
                    return new Geosets(Set.of(), "the display's geoset rows conflict for group " + group);
                if (!present.containsKey(group)) continue; // the model has no such group: nothing to show
                if (!present.get(group).contains(group * 100 + value))
                    return new Geosets(Set.of(), "geoset " + (group * 100 + value) + " chosen by the display is not in the model");
                ids.add(group * 100 + value);
            }
        }
        for (var group : present.entrySet()) {
            int g = group.getKey();
            if (g == 0) {
                if (!group.getValue().contains(0))
                    return new Geosets(Set.of(), "geoset group 0 has no section 0");
                ids.add(0);
                continue;
            }
            // With rows, a group a row chose draws only that section (added above), and groups
            // 1 to 8 without a row are hidden.
            if (rows && (chosen.containsKey(g) || g <= 8)) continue;
            // The default: the x01 section, or nothing when the group has none.
            if (group.getValue().contains(g * 100 + 1)) ids.add(g * 100 + 1);
        }
        return new Geosets(Set.copyOf(ids), null);
    }

    /** {@link #reduce(MeshAppearance.Texture, int, Cancellation)} for an opaque surface. */
    static Image reduce(MeshAppearance.Texture texture, Cancellation token) {
        return reduce(texture, OPAQUE, token);
    }

    /**
     * Box-filters ARGB to power-of-two sides of at most {@link #IMAGE_SIDE} and builds its mip
     * chain for the material class. Opaque images become alpha 255; cutout images keep alpha, with
     * colour weighted by alpha so transparent texels do not darken the edges, and their chain keeps
     * the top level's alpha-test coverage ({@link com.vibeminer.rendering.world.ObjectTextureMips}).
     */
    static Image reduce(MeshAppearance.Texture texture, int materialClass, Cancellation token) {
        if (materialClass != OPAQUE && materialClass != CUTOUT) throw new IllegalArgumentException("Material class");
        int width = texture.width(), height = texture.height();
        long sourcePassing = 0;
        int w = Math.min(IMAGE_SIDE, Integer.highestOneBit(width));
        int h = Math.min(IMAGE_SIDE, Integer.highestOneBit(height));
        if (w < 1 || h < 1) return new Image(Math.max(w, 0), Math.max(h, 0), new byte[0]);
        // A read-only view: the texture's pixels are not copied again for every conversion.
        var argb = texture.argbView();
        boolean opaque = materialClass == OPAQUE;
        var rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            token.check();
            int y0 = (int) ((long) y * height / h), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * height / h));
            for (int x = 0; x < w; x++) {
                int x0 = (int) ((long) x * width / w), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * width / w));
                long r = 0, g = 0, b = 0, a = 0, wr = 0, wg = 0, wb = 0, n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int pixel = argb.get(sy * width + sx);
                        int alpha = pixel >>> 24, red = pixel >>> 16 & 255, green = pixel >>> 8 & 255,
                                blue = pixel & 255;
                        if (alpha >= com.vibeminer.rendering.world.ObjectTextureMips.CUTOUT_ALPHA) sourcePassing++;
                        r += red;
                        g += green;
                        b += blue;
                        a += alpha;
                        wr += (long) red * alpha;
                        wg += (long) green * alpha;
                        wb += (long) blue * alpha;
                        n++;
                    }
                }
                int at = (y * w + x) * 4;
                if (opaque || a == 0) {
                    rgba[at] = (byte) (r / n);
                    rgba[at + 1] = (byte) (g / n);
                    rgba[at + 2] = (byte) (b / n);
                } else {
                    rgba[at] = (byte) ((wr + a / 2) / a);
                    rgba[at + 1] = (byte) ((wg + a / 2) / a);
                    rgba[at + 2] = (byte) ((wb + a / 2) / a);
                }
                rgba[at + 3] = (byte) (opaque ? 255 : (a + n / 2) / n);
            }
        }
        // Averaging alpha thins strands (manes, feathers) below the cutout reference; the reduced
        // top level keeps the source's alpha-test coverage, as the chain's lower levels do.
        if (!opaque && (w != width || h != height))
            com.vibeminer.rendering.world.ObjectTextureMips.matchCoverage(rgba, 0, w * h,
                    sourcePassing / ((double) width * height));
        // Creature textures arrive as one level: the chain is generated (issue #265).
        var chain = com.vibeminer.rendering.world.ObjectTextureMips.build(w, h, rgba, materialClass,
                (below, lw, lh) -> null, token::isCanceled);
        return new Image(w, h, chain.rgba());
    }

    /** The source revision's spawn index, loaded once on its own daemon thread. */
    private WorldSpawns.Index index(Cancellation token) {
        CompletableFuture<WorldSpawns.Index> future;
        synchronized (this) {
            if (closed) throw new CancellationException("Creature models closed");
            var now = revision.get();
            // A failed load is reused within its revision until the back-off ends, so a broken
            // source costs one load per minute, not one per tile.
            if (index == null || !Objects.equals(now, cachedRevision)
                    || index.isCompletedExceptionally() && clock.getAsLong() - indexFailedAt >= INDEX_RETRY_NANOS) {
                if (indexLoad != null) indexLoad.cancel();
                if (!Objects.equals(now, cachedRevision)) {
                    choices.clear();
                    models.clear();
                }
                cachedRevision = now;
                var load = new Cancellation();
                indexLoad = load;
                var started = new CompletableFuture<WorldSpawns.Index>();
                index = started;
                Thread.ofPlatform().daemon().name("world-creature-spawns").start(() -> {
                    try {
                        started.complete(Objects.requireNonNull(loader.load(load)));
                    } catch (Throwable failed) {
                        synchronized (WorldTileCreatures.this) {
                            indexFailedAt = clock.getAsLong();
                        }
                        started.completeExceptionally(failed);
                    }
                });
            }
            future = index;
        }
        while (true) {
            token.check();
            try {
                return future.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // Keep waiting while the caller is live; the load itself is never caller-canceled.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted");
            } catch (ExecutionException failed) {
                var cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(cause);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            if (indexLoad != null) indexLoad.cancel();
            choices.clear();
            models.clear();
        }
        if (previews instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing a preview component never fails the World view.
            }
        }
    }
}
