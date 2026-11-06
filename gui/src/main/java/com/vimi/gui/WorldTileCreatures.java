package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.models.BuildDisplayModels;
import com.vibeminer.concepts.models.DisplayModelTypes;
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
 * geoset group draws one variant decided from recorded facts ({@link #visibleGeosets}: the
 * display's own geoset rows, otherwise the group's x00 section); only its opaque and alpha-keyed
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
        CHARACTER_APPEARANCE("character appearance not evaluated"),
        VISIBILITY_UNRESOLVED("surface visibility unresolved"),
        NO_OPAQUE_SURFACE("no opaque or alpha-keyed textured surface"),
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
        static final Summary NONE = new Summary(new int[Count.values().length], 0, null, List.of());

        private final int[] counts;
        private final int models;
        private final String note;
        private final List<WorldObjectPicks.Creature> placed;

        private Summary(int[] counts, int models, String note, List<WorldObjectPicks.Creature> placed) {
            this.counts = counts.clone();
            this.models = models;
            this.note = note;
            this.placed = List.copyOf(placed);
        }

        static Summary failed(String note) {
            return new Summary(new int[Count.values().length], 0, Objects.requireNonNull(note), List.of());
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
            return new Summary(sum, models + other.models, note != null ? note : other.note, List.of());
        }
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
        DisplayModelTypes.Preview preview(long displayId, Cancellation token) throws Exception;
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
            int omittedSurfaces) {
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

    /** A display's conversion: a model, or the bucket that refuses it. */
    record Converted(Model model, Count refusal, String detail) {
        Converted {
            if ((model == null) == (refusal == null)) throw new IllegalArgumentException("Inconsistent conversion");
        }

        static Converted refused(Count refusal, String detail) {
            return new Converted(null, refusal, detail);
        }
    }

    private final WorldSpawnOverlay.Loader loader;
    private final Supplier<Object> revision;
    private final Chooser chooser;
    private final Previews previews;
    private final Map<Long, Chosen> choices = lru(CHOICE_CACHE);
    private final Map<Long, Converted> models = lru(MODEL_CACHE);
    private Object cachedRevision;
    private CompletableFuture<WorldSpawns.Index> index;
    private long indexFailedAt;
    private final java.util.function.LongSupplier clock;
    private Cancellation indexLoad;
    private boolean closed;

    WorldTileCreatures(WorldSpawnOverlay.Loader loader, Supplier<Object> revision, Chooser chooser, Previews previews) {
        this(loader, revision, chooser, previews, System::nanoTime);
    }

    /** Tests supply the clock that times the index back-off. */
    WorldTileCreatures(WorldSpawnOverlay.Loader loader, Supplier<Object> revision, Chooser chooser, Previews previews,
            java.util.function.LongSupplier clock) {
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
            BuildDisplayModels models;
            synchronized (this) {
                if (closed) throw new CancellationException("Creature models closed");
                var now = revision.get();
                if (current == null || !Objects.equals(now, currentRevision)) {
                    if (current != null) current.closeAsync();
                    current = factory.get();
                    currentRevision = now;
                }
                models = current;
            }
            if (models == null) {
                return new DisplayModelTypes.Preview(DisplayModelTypes.State.UNAVAILABLE, null, null,
                        java.util.Set.of(), List.of(), false, "Model files are not open");
            }
            return models.preview(displayId, token).join();
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
        // Displays whose preview was busy on this tile: not asked again for every later spawn.
        var busyDisplays = new HashSet<Long>();
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
                if (converted == null && busyDisplays.contains(display)) {
                    counts[Count.PREVIEWS_BUSY.ordinal()]++;
                    continue;
                }
                if (converted == null) {
                    if (previewsTried == TILE_PREVIEWS) {
                        counts[Count.CAPPED.ordinal()]++;
                        continue;
                    }
                    previewsTried++;
                    converted = load(display, token);
                    // A busy or closed reader learned nothing: refund the preview it cost.
                    if (converted.refusal() == Count.PREVIEWS_BUSY) {
                        previewsTried--;
                        busyDisplays.add(display);
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
            counts[Count.DRAWN.ordinal()]++;
        }
        return new Summary(counts, added.size(), nearby.omitted() == 0 ? null
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
            converted = convert(previews.preview(display, token), token);
        } catch (Exception failed) {
            // Only this tile's cancellation propagates; a closed or retired component is a failure.
            token.check();
            if (busy(failed))
                return Converted.refused(Count.PREVIEWS_BUSY, failed.getClass().getSimpleName());
            WorldDiagnostics.degraded("creature models", "World creature model read failed", failed);
            // Not cached: a failed read may succeed on the next tile.
            return Converted.refused(Count.FAILED, failed.getClass().getSimpleName());
        }
        synchronized (this) {
            if (!closed) models.put(display, converted);
        }
        return converted;
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
     * it cannot make from recorded facts ({@link #visibleGeosets}). Each geoset group draws the
     * display's own choice where it records one, otherwise its x00 section; never a guessed variant.
     */
    static Converted convert(DisplayModelTypes.Preview preview, Cancellation token) {
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
            if (!visible.ids().contains(part.id())) continue;
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
        if (ranges.isEmpty() || remap.size() < 3)
            return Converted.refused(Count.NO_OPAQUE_SURFACE, "no opaque or alpha-keyed diffuse surface with a texture");
        var vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertexArray.length; i++) vertexArray[i] = vertices.get(i);
        return new Converted(new Model(vertexArray, indices.stream().mapToInt(Integer::intValue).toArray(),
                ranges.stream().mapToInt(Integer::intValue).toArray(),
                blends.stream().mapToInt(Integer::intValue).toArray(), images, omitted), null, null);
    }

    /** The geoset IDs a model draws, or why that cannot be decided from recorded facts. */
    record Geosets(Set<Integer> ids, String refusal) {}

    /**
     * Which geoset sections draw (#361). Each group of sections (geoset ID / 100) shows one variant:
     * the display's own choice when it records one (CreatureDisplayInfoGeosetData, as group =
     * GeosetIndex + 1 and section = group * 100 + value), otherwise the group's x00 section. A
     * choice is never guessed: an unreadable CreatureGeosetDataID, unknown or conflicting display
     * rows, a chosen variant the model does not have, and a group without its x00 default all
     * refuse. A non-zero CreatureGeosetDataID without rows for this display refuses too. The ID is
     * recorded per model (CreatureModelData) while the rows are per display, so it does not prove
     * that this display has rows: a sibling display of the same model may rely on the defaults.
     * Which rule the client applies there (and whether it defaults creature groups to x00 or x01)
     * is not settled; until it is, that case is refused rather than guessed.
     */
    static Geosets visibleGeosets(DisplayModelTypes.Reference reference, com.vibeminer.rendering.models.PreviewMesh mesh) {
        if (reference.creatureGeosetDataId() == null)
            return new Geosets(Set.of(), "creature geoset data could not be read");
        var chosen = reference.displayGeosets();
        if (chosen == null) return new Geosets(Set.of(), "display geoset selections are unknown");
        if (reference.creatureGeosetDataId() != 0 && chosen.isEmpty())
            return new Geosets(Set.of(), "the model records geoset data but this display has no geoset rows");
        var present = new java.util.TreeMap<Integer, java.util.TreeSet<Integer>>();
        for (var part : mesh.parts()) present.computeIfAbsent(part.id() / 100, g -> new java.util.TreeSet<>()).add(part.id());
        var ids = new HashSet<Integer>();
        for (var choice : chosen.entrySet()) {
            int group = choice.getKey(), value = choice.getValue();
            if (value == DisplayModelTypes.Reference.CONFLICTING_GEOSET)
                return new Geosets(Set.of(), "the display's geoset rows conflict for group " + group);
            if (!present.containsKey(group)) continue; // the model has no such group: nothing to show
            if (!present.get(group).contains(group * 100 + value))
                return new Geosets(Set.of(), "geoset " + (group * 100 + value) + " chosen by the display is not in the model");
        }
        for (var group : present.entrySet()) {
            Integer value = chosen.get(group.getKey());
            int id = group.getKey() * 100 + (value == null ? 0 : value);
            if (!group.getValue().contains(id))
                return new Geosets(Set.of(), "geoset group " + group.getKey() + " has no default x00 section");
            ids.add(id);
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
