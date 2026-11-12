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

    /**
     * Range material classes, as in the objects file: opaque, alpha-key cutout, blended and additive
     * (drawn in the World objects' sorted blended and additive passes, both with images prepared as
     * blended ones).
     */
    static final int OPAQUE = com.vibeminer.rendering.world.ObjectTextureMips.OPAQUE,
            CUTOUT = com.vibeminer.rendering.world.ObjectTextureMips.CUTOUT,
            BLENDED = com.vibeminer.rendering.world.ObjectTextureMips.BLENDED,
            ADDITIVE = com.vibeminer.rendering.world.WorldObjectPicking.ADDITIVE;

    /** Range class flag: drawn at full intensity, without the light (objects file). */
    static final int UNLIT = com.vibeminer.rendering.world.WorldObjectPicking.UNLIT;

    /**
     * Per-tile bounds, on top of the shared objects file profile that buildings use first. Sized
     * from the measured need with the EG Link display fallback (70862): busy town tiles hold up
     * to 223 spawns and 109 distinct displays. Placements reuse a model and cost little, so up to
     * {@value #TILE_PLACEMENTS} are drawn. Distinct models are bounded by their texture bytes
     * ({@link #TILE_TEXTURE_BYTES}), with {@value #TILE_MODELS} as a hard ceiling, and at most
     * {@value #TILE_PREVIEWS} new displays are converted per tile so one tile cannot stall the
     * rest. Models converted on an earlier tile are added without a preview load, so what is
     * capped depends on the cache: a warm pass can draw models a cold pass capped. Creatures are
     * opt-in, so this memory is spent only when the user enables them.
     */
    static final int TILE_MODELS = 64, TILE_PLACEMENTS = 256, TILE_PREVIEWS = 48;

    /**
     * The texture bytes (RGBA8 mip chains) one tile's distinct creature models may use. A trial
     * with 24 models a tile measured up to 10.5 MB on the busiest tiles; the shared tile file
     * holds about 18.7 MB of images in all, and buildings draw first.
     */
    static final long TILE_TEXTURE_BYTES = 12L << 20;

    /**
     * The objects budget share all resident tiles' creatures may hold together ({@link
     * #gpuBytes}). Creatures share each tile's objects set with its buildings, and the session's
     * object budget is 212 MiB; the densest Far window measured reserves about 161 MiB for
     * buildings alone, so creatures stay within what that leaves and never push buildings out.
     * Unbounded, the creatures of a Far window around Goldshire (EG Link display fallback, 70862)
     * added about 37 MB; with this share the window's peak stays near 137 MB. Tiles nearest the
     * camera are prepared first, so they get the share first.
     */
    static final long AREA_BYTES = 40L << 20;

    /** Whole-model ceiling so one creature never takes a building tile's budget. */
    static final int MODEL_VERTICES = 32_768, MODEL_INDICES = 98_304;

    static final int CHOICE_CACHE = 4096;

    /**
     * Converted models are remembered across tiles up to this many bytes (vertices, indices and
     * texture chains). An area with the EG Link fallback uses 49-189 distinct models; a count of
     * 32 evicted them, so warm passes converted again.
     */
    static final long MODEL_CACHE_BYTES = 128L << 20;

    /**
     * Humanoid NPC appearances draw their pre-baked texture at most this many pixels a side, a
     * quarter of the texture memory of {@link #IMAGE_SIDE}: busy town tiles hold dozens of them.
     * Their canvases are already at most 256 pixels a side, and close-up renders of town NPCs at
     * 256 and 128 showed no visible difference, so one side serves every distance.
     */
    static final int NPC_IMAGE_SIDE = 128;

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
        NO_OPAQUE_SURFACE("no opaque, alpha-keyed, additive or blended textured surface"),
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
                List.of(), 0);

        private final int[] counts;
        private final int models, restPosed, billboardsLeftOut;
        private final NpcCounts npcs;
        private final String note;
        private final List<WorldObjectPicks.Creature> placed;
        private final long bytes;

        private Summary(int[] counts, int models, int restPosed, int billboardsLeftOut, NpcCounts npcs,
                String note, List<WorldObjectPicks.Creature> placed, long bytes) {
            this.bytes = bytes;
            this.counts = counts.clone();
            this.models = models;
            this.restPosed = restPosed;
            this.billboardsLeftOut = billboardsLeftOut;
            this.npcs = Objects.requireNonNull(npcs);
            this.note = note;
            this.placed = List.copyOf(placed);
        }

        /** Only drawn placements: {@code drawn} of {@code models} models adding {@code bytes}. */
        static Summary drawn(int drawn, int models, long bytes) {
            var counts = new int[Count.values().length];
            counts[Count.DRAWN.ordinal()] = drawn;
            return new Summary(counts, models, 0, 0, NpcCounts.NONE, null, List.of(), bytes);
        }

        static Summary failed(String note) {
            return new Summary(new int[Count.values().length], 0, 0, 0, NpcCounts.NONE,
                    Objects.requireNonNull(note), List.of(), 0);
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

        /** The bytes the tile's creature models add to its objects set ({@link #gpuBytes}). */
        long bytes() {
            return bytes;
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
                    note != null ? note : other.note, List.of(), bytes + other.bytes);
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
            return await(handle.npcAppearance(extendedDisplayInfoId, token), token);
        };
    }

    /**
     * Waits for {@code future} unless {@code token} is canceled first (#717): the races component
     * observes the token only once queued work starts, so a tile canceled while its appearance is
     * still queued must not keep its worker waiting for the queue to drain. A canceled wait throws
     * {@link CancellationException}; the queued work then sees the canceled token and does not run.
     */
    @SuppressWarnings("try")
    static <T> T await(java.util.concurrent.CompletableFuture<T> future, Cancellation token) {
        var canceled = new java.util.concurrent.CompletableFuture<Void>();
        try (var registration = token.onCancel(() -> canceled.complete(null))) {
            java.util.concurrent.CompletableFuture.anyOf(future, canceled)
                    .handle((value, failure) -> null)
                    .join();
        }
        if (!future.isDone()) token.check();
        return future.join();
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

        /**
         * A chooser for these NPCs (one tile's, not yet chosen) that may read what they share at
         * once, such as their outside displays; this one by default.
         */
        default Chooser forNpcs(java.util.Collection<Long> npcIds, Cancellation token) throws Exception {
            return this;
        }

        /**
         * What the choices depend on besides the World's own revision (outside display sources),
         * or null. When it changes, choices are made again; converted models, keyed by display,
         * are kept. Tiles already drawn keep their choices until they are prepared again.
         */
        default Object revision() {
            return null;
        }
    }

    /**
     * A choice that cannot be made now and whose cause was already reported once (an outside
     * source that could not be read for the tile): counted as failed, not logged again, not cached.
     */
    static final class ChoiceLater extends Exception {
        private static final long serialVersionUID = 1L;

        ChoiceLater(String message) {
            super(message, null, false, false);
        }
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

        /**
         * The display's table relationships without its model ({@link
         * BuildDisplayModels#reference}), so a humanoid NPC's display is known without reading
         * the character model its preview would load; null when a source cannot tell cheaply,
         * and the preview answers instead.
         */
        default DisplayModelTypes.Reference reference(long displayId, Cancellation token) throws Exception {
            return null;
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
     * one material class per range ({@link #OPAQUE}, {@link #CUTOUT}, {@link #BLENDED} with or
     * without {@link #UNLIT}, or {@link #ADDITIVE} with {@link #UNLIT}).
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
    private final ModelCache models = new ModelCache(MODEL_CACHE_BYTES);
    private Object cachedRevision;
    /** The chooser's last {@link Chooser#revision}; guarded by this. */
    private Object choiceRevision;
    /**
     * Counts the times {@code choices} was emptied; guarded by this. A choice made across a change
     * (another tile saw new outside sources while it was being made) is used once, not cached.
     */
    private long choiceGeneration;
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

    /**
     * Converted models by display, least recently used dropped first past {@code limit} bytes
     * ({@link #bytes}). Not a {@link Map}: only these operations exist, so every change is counted.
     * The newest entry is kept even when it alone is larger than the limit. Not synchronized; the
     * owner guards it.
     */
    static final class ModelCache {
        private final long limit;
        private final LinkedHashMap<Long, Converted> entries = new LinkedHashMap<>(64, .75f, true);
        private long bytes;

        ModelCache(long limit) {
            if (limit < 1) throw new IllegalArgumentException("Model cache limit");
            this.limit = limit;
        }

        Converted get(long display) {
            return entries.get(display);
        }

        boolean contains(long display) {
            return entries.containsKey(display);
        }

        void put(long display, Converted converted) {
            Objects.requireNonNull(converted);
            var before = entries.put(display, converted);
            bytes += WorldTileCreatures.bytes(converted) - (before == null ? 0 : WorldTileCreatures.bytes(before));
            var eldest = entries.values().iterator();
            while (bytes > limit && entries.size() > 1) {
                bytes -= WorldTileCreatures.bytes(eldest.next());
                eldest.remove();
            }
        }

        void remove(long display) {
            var before = entries.remove(display);
            if (before != null) bytes -= WorldTileCreatures.bytes(before);
        }

        void clear() {
            entries.clear();
            bytes = 0;
        }

        int size() {
            return entries.size();
        }

        long totalBytes() {
            return bytes;
        }
    }

    /** The remembered conversion of a display, or null (tests). */
    synchronized Converted cached(long display) {
        return models.get(display);
    }

    /**
     * The GPU bytes one model adds to a tile's objects set: its RGBA8 texture chains, its vertices
     * as the renderer packs them (the objects file's 32-byte vertex and a 4-byte colour) and its
     * 16-bit indices.
     */
    static long gpuBytes(Model m) {
        return m.pixels() * 4 + (long) m.vertexCount() * 36 + (long) m.indices().length * 2;
    }

    /** The bytes one remembered conversion holds; a refusal holds next to none. */
    static long bytes(Converted converted) {
        var m = converted.model();
        if (m == null) return 64;
        return (long) m.vertices().length * 4 + (long) m.indices().length * 4 + m.pixels() * 4 + 256;
    }

    private static <V> Map<Long, V> lru(int capacity) {
        return new LinkedHashMap<>(64, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * The client-only chooser: the data-only creature chain of the opened build. The World uses
     * {@link WorldNpcDisplays}, which falls back to outside sources.
     */
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
        public DisplayModelTypes.Reference reference(long displayId, Cancellation token) {
            var models = component();
            return models == null ? null : models.reference(displayId, token).join();
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
        return append(builder, mapId, tile, Long.MAX_VALUE, token);
    }

    /**
     * As above, adding models only while their {@link #gpuBytes} stay within {@code allowance}: the
     * part of {@link #AREA_BYTES} the resident tiles' creatures leave (see
     * {@code WorldStreamSourceController}). The models past it are counted as capped.
     */
    Summary append(WorldStreamTileWriter.Exterior.Builder builder, long mapId, WorldFormats.Tile tile,
            long allowance, Cancellation token) {
        if (allowance < 0) throw new IllegalArgumentException("Creature allowance");
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
        var placing = new Placing(builder, allowance, spawns, tileChooser(nearby.placed(), tile, token), token);
        var deferred = new ArrayList<Deferred>();
        for (var spawn : nearby.placed()) {
            token.check();
            if (WorldBookmarks.tileIndex(spawn.x()) != tile.x() || WorldBookmarks.tileIndex(spawn.y()) != tile.y()) continue;
            placing.place(spawn, null, deferred);
        }
        // A display refused because the model reader was busy is asked again before the tile's
        // objects are attached (#484): after a short, doubling pause, a few times, within the
        // tile's preview budget. Still busy after that, its spawns count as busy as before.
        for (int round = 0; round < BUSY_RETRIES && !deferred.isEmpty(); round++) {
            pause.pause(BUSY_PAUSE_MILLIS << round, token);
            placing.busyDisplays.values().removeIf(refusal -> refusal == Count.PREVIEWS_BUSY);
            var again = new ArrayList<>(deferred);
            deferred.clear();
            placing.retries++;
            for (var pending : again) {
                token.check();
                placing.place(pending.spawn(), pending.chosen(), deferred);
            }
        }
        placing.counts[Count.PREVIEWS_BUSY.ordinal()] += deferred.size();
        return placing.summary(nearby.omitted() == 0 ? null
                : nearby.omitted() + " nearby points beyond the query limit were not considered");
    }

    /** Busy-refused spawns: asked again at most this many times per tile (#484). */
    static final int BUSY_RETRIES = 3;

    /** The first pause before a busy reader is asked again; it doubles each time (#484). */
    static final long BUSY_PAUSE_MILLIS = 100;

    /** Waits between busy retries; returns early, throwing, when the tile is canceled. */
    @FunctionalInterface
    interface Pause {
        void pause(long millis, Cancellation token);

        Pause SLEEP = WorldTileCreatures::sleep;
    }

    /** Sleeps {@code millis}, or less when {@code token} is canceled, which then throws. */
    @SuppressWarnings("try")
    static void sleep(long millis, Cancellation token) {
        var woken = new java.util.concurrent.CountDownLatch(1);
        try (var registration = token.onCancel(woken::countDown)) {
            woken.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted");
        }
        token.check();
    }

    private volatile Pause pause = Pause.SLEEP;

    /** Tests replace the pause between busy retries. */
    void pause(Pause next) {
        pause = Objects.requireNonNull(next);
    }

    /** A spawn whose display was refused as busy, with its choice, to place on a retry. */
    private record Deferred(WorldSpawns.Spawn spawn, Chosen chosen) {}

    /** One tile's placement state across its first pass and its busy retries. */
    private final class Placing {
        final WorldStreamTileWriter.Exterior.Builder builder;
        final long allowance;
        final WorldSpawns.Index spawns;
        final Chooser tileChooser;
        final Cancellation token;
        final int[] counts = new int[Count.values().length];
        final HashMap<Long, Integer> added = new HashMap<>();
        final java.util.ArrayList<WorldObjectPicks.Creature> placed = new java.util.ArrayList<>();
        int placements, previewsTried, retries;
        long textureBytes, setBytes;
        // Displays whose preview was busy, or whose NPC appearance cannot be read yet, on this
        // tile: not asked again for every later spawn of the same pass.
        final HashMap<Long, Count> busyDisplays = new HashMap<>();
        // Builder model indices drawn in their rest pose; their placements are disclosed.
        final HashSet<Integer> restModels = new HashSet<>();
        // Builder model indices of humanoid NPCs, with what each placement leaves undrawn.
        final HashMap<Integer, NpcCounts> npcModels = new HashMap<>();
        NpcCounts npcCounts = NpcCounts.NONE;
        int restPosed, billboardsLeftOut;
        // Builder model indices whose Stand pose left out billboard triangles.
        final HashSet<Integer> billboardModels = new HashSet<>();

        Placing(WorldStreamTileWriter.Exterior.Builder builder, long allowance, WorldSpawns.Index spawns,
                Chooser tileChooser, Cancellation token) {
            this.builder = builder;
            this.allowance = allowance;
            this.spawns = spawns;
            this.tileChooser = tileChooser;
            this.token = token;
        }

        /**
         * Places one spawn of the tile, or counts why it is not drawn; a spawn whose display the
         * reader refused as busy goes to {@code deferred} instead. {@code known} is its choice
         * from an earlier pass, or null.
         */
        void place(WorldSpawns.Spawn spawn, Chosen known, List<Deferred> deferred) {
            token.check();
            // Once nothing more can be placed, the rest is capped without any lookup.
            if (placements == TILE_PLACEMENTS || builder.placementRoom() == 0 || builder.drawRoom() == 0) {
                counts[Count.CAPPED.ordinal()]++;
                return;
            }
            Chosen chosen = known != null ? known : choose(spawn);
            if (chosen == null) return;
            var choice = chosen.choice();
            if (choice.state() != CreatureModelPolicy.State.QUALIFIED || choice.displayId() == null) {
                counts[Count.NOT_QUALIFIED.ordinal()]++;
                return;
            }
            long display = choice.displayId();
            Integer model = added.get(display);
            if (model == null) {
                Converted converted;
                synchronized (WorldTileCreatures.this) {
                    converted = models.get(display);
                }
                if (converted == null && busyDisplays.containsKey(display)) {
                    var refusal = busyDisplays.get(display);
                    // A busy reader is asked again after this pass (#484), not counted yet.
                    if (refusal == Count.PREVIEWS_BUSY) deferred.add(new Deferred(spawn, chosen));
                    else counts[refusal.ordinal()]++;
                    return;
                }
                if (converted == null) {
                    if (previewsTried == TILE_PREVIEWS) {
                        counts[Count.CAPPED.ordinal()]++;
                        return;
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
                if (converted.refusal() == Count.PREVIEWS_BUSY) {
                    deferred.add(new Deferred(spawn, chosen));
                    return;
                }
                if (converted.refusal() != null) {
                    counts[converted.refusal().ordinal()]++;
                    return;
                }
                var m = converted.model();
                if (added.size() == TILE_MODELS
                        || textureBytes + m.pixels() * 4 > TILE_TEXTURE_BYTES
                        || gpuBytes(m) > allowance - setBytes
                        || m.ranges().length / 3 > builder.drawRoom()
                        || !builder.fits(m.images().size(), m.pixels(), 1, m.vertexCount(), m.indices().length,
                                m.ranges().length / 3)) {
                    counts[Count.CAPPED.ordinal()]++;
                    return;
                }
                model = add(builder, m);
                textureBytes += m.pixels() * 4;
                setBytes += gpuBytes(m);
                added.put(display, model);
                if (m.restPose() != null) restModels.add(model);
                if (m.billboardTriangles() > 0) billboardModels.add(model);
                if (m.npc() != null) npcModels.put(model, m.npc());
            }
            if (builder.drawCost(model) > builder.drawRoom()) {
                counts[Count.CAPPED.ordinal()]++;
                return;
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

        /** The spawn's choice, or null after counting why it has none. */
        private Chosen choose(WorldSpawns.Spawn spawn) {
            try {
                return chosen(tileChooser, spawn.npcId(), token);
            } catch (CancellationException canceled) {
                // Only this tile's cancellation propagates; a component's own is a failed lookup.
                if (token.isCanceled()) throw canceled;
            } catch (ChoiceLater later) {
                // Counted as failed below.
            } catch (Exception failed) {
                token.check();
                WorldDiagnostics.degraded("creature lookups", "World creature model lookup failed", failed);
            }
            counts[Count.FAILED.ordinal()]++;
            return null;
        }

        Summary summary(String note) {
            return new Summary(counts, added.size(), restPosed, billboardsLeftOut, npcCounts, note, placed, setBytes);
        }
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

    /**
     * The chooser for one tile: the NPCs of its spawns not chosen yet are handed to {@link
     * Chooser#forNpcs} together. A failure there leaves each NPC to be chosen by itself.
     */
    private Chooser tileChooser(List<WorldSpawns.Spawn> spawns, WorldFormats.Tile tile, Cancellation token) {
        var ids = new java.util.LinkedHashSet<Long>();
        synchronized (this) {
            for (var spawn : spawns) {
                if (WorldBookmarks.tileIndex(spawn.x()) == tile.x() && WorldBookmarks.tileIndex(spawn.y()) == tile.y()
                        && !choices.containsKey(spawn.npcId())) {
                    ids.add(spawn.npcId());
                }
            }
        }
        if (ids.isEmpty()) return chooser;
        try {
            return Objects.requireNonNull(chooser.forNpcs(List.copyOf(ids), token));
        } catch (CancellationException canceled) {
            if (token.isCanceled()) throw canceled;
            return chooser;
        } catch (Exception failed) {
            token.check();
            WorldDiagnostics.degraded("creature lookups", "World creature display lookup failed", failed);
            return chooser;
        }
    }

    private Chosen chosen(Chooser tileChooser, long npcId, Cancellation token) throws Exception {
        long generation;
        synchronized (this) {
            var cached = choices.get(npcId);
            if (cached != null) return cached;
            generation = choiceGeneration;
        }
        var chosen = tileChooser.choose(npcId, token);
        token.check();
        synchronized (this) {
            if (!closed && generation == choiceGeneration) choices.put(npcId, chosen);
        }
        return chosen;
    }

    private Converted load(long display, Cancellation token) {
        Converted converted;
        try {
            // A humanoid NPC's display draws its race appearance: its preview (the character model,
            // read whole) is not needed, only its extended display info.
            var reference = previews.reference(display, token);
            token.check();
            Long extended = reference == null ? null : reference.extendedDisplayInfoId();
            if (extended != null && extended > 0) {
                converted = npc(extended, token);
            } else {
                var preview = previews.preview(display, token);
                token.check();
                extended = preview == null || preview.reference() == null ? null
                        : preview.reference().extendedDisplayInfoId();
                converted = extended != null && extended > 0 ? npc(extended, token) : stand(display, preview, token);
            }
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
        var converted = surfaces(result.appearance(), visible, new int[0], false, NPC_IMAGE_SIDE, token);
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
     * appearance, models without an opaque, alpha-keyed, additive or blended diffuse surface, and
     * every geoset choice it cannot make from recorded facts ({@link #visibleGeosets}), including a
     * model whose every section the display's rows hide. Never a guessed variant.
     */
    static Converted convert(DisplayModelTypes.Preview preview, Cancellation token) {
        return convert(preview, new int[0], token);
    }

    /** As {@link #convert(DisplayModelTypes.Preview, Cancellation)}, leaving out {@code leftOut}. */
    static Converted convert(DisplayModelTypes.Preview preview, int[] leftOut, Cancellation token) {
        return convert(preview, leftOut, null, token);
    }

    /**
     * As {@link #convert(DisplayModelTypes.Preview, int[], Cancellation)}; {@code sources}, when
     * given, receives the rest vertex of each model vertex in model order, so a skinned pose of the
     * rest surface can be gathered into the model's vertices.
     */
    static Converted convert(DisplayModelTypes.Preview preview, int[] leftOut,
            java.util.function.IntConsumer sources, Cancellation token) {
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
        return surfaces(appearance, visible.ids(), leftOut, true, IMAGE_SIDE, sources, token);
    }

    /**
     * The opaque and alpha-keyed diffuse surfaces of the {@code visible} geoset sections of one
     * appearance, as a model; rigid cards are dropped.
     */
    static Converted surfaces(MeshAppearance appearance, Set<Integer> visibleIds, Cancellation token) {
        return surfaces(appearance, visibleIds, new int[0], false, IMAGE_SIDE, null, token);
    }

    /**
     * As {@link #surfaces(MeshAppearance, Set, Cancellation)}, also leaving out every triangle with
     * a vertex in {@code leftOut} (weighted to a camera-facing bone, #384), counted on the model. A
     * surface left with no triangle is an omitted surface, as one made only of rigid cards is. With
     * {@code sorted}, unlit additive surfaces and blended surfaces (the creature World binding's,
     * #361) draw as {@link #ADDITIVE} and {@link #BLENDED} ranges; humanoid NPC appearances keep
     * leaving them out.
     */
    static Converted surfaces(MeshAppearance appearance, Set<Integer> visibleIds, int[] leftOut,
            boolean sorted, Cancellation token) {
        return surfaces(appearance, visibleIds, leftOut, sorted, IMAGE_SIDE, null, token);
    }

    /** As above, with textures reduced to at most {@code imageSide} pixels a side. */
    static Converted surfaces(MeshAppearance appearance, Set<Integer> visibleIds, int[] leftOut,
            boolean sorted, int imageSide, Cancellation token) {
        return surfaces(appearance, visibleIds, leftOut, sorted, imageSide, null, token);
    }

    private static Converted surfaces(MeshAppearance appearance, Set<Integer> visibleIds, int[] leftOut,
            boolean sorted, int imageSide, java.util.function.IntConsumer sources, Cancellation token) {
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
                    || blend == MeshAppearance.Blend.ADDITIVE
                            && (!sorted || surface.material().lighting() != MeshAppearance.Lighting.UNLIT)
                    || blend == MeshAppearance.Blend.BLENDED && !sorted
                    || surface.textureIndex() >= textures.size()) {
                omitted++;
                continue;
            }
            // Additive surfaces are the World binding's unlit additive ones (GxBlend_Add), blended
            // ones its alpha-blended ones (GxBlend_Alpha), lit or unlit.
            int materialClass = switch (blend) {
                case ALPHA_KEY -> CUTOUT;
                case ADDITIVE, BLENDED -> BLENDED;
                case OPAQUE -> OPAQUE;
            };
            int rangeClass = switch (blend) {
                case ADDITIVE -> ADDITIVE | UNLIT;
                case BLENDED -> surface.material().lighting() == MeshAppearance.Lighting.UNLIT
                        ? BLENDED | UNLIT : BLENDED;
                default -> materialClass;
            };
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
                        if (sources != null) sources.accept(vertex);
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
            int key = surface.textureIndex() * 3 + materialClass;
            Integer slot = imageSlots.get(key);
            if (slot == null) {
                var image = reduce(textures.get(surface.textureIndex()), materialClass, imageSide, token);
                if (image.width() < 1 || image.height() < 1)
                    return Converted.refused(Count.MODEL_UNAVAILABLE, "texture has no pixels");
                slot = images.size();
                imageSlots.put(key, slot);
                images.add(image);
            }
            ranges.add(first);
            ranges.add(count);
            ranges.add(slot);
            blends.add(rangeClass);
        }
        if ((ranges.isEmpty() || remap.size() < 3) && billboardTriangles > 0)
            return Converted.refused(Count.ALL_BILLBOARD,
                    "every drawable triangle is weighted to a camera-facing bone");
        if (ranges.isEmpty() || remap.size() < 3)
            return Converted.refused(Count.NO_OPAQUE_SURFACE,
                    "no opaque, alpha-keyed, additive or blended diffuse surface with a texture");
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
     * Blended images (for additive and blended surfaces) keep alpha weighted the same way, without
     * coverage matching, as the objects' blended chains do.
     */
    static Image reduce(MeshAppearance.Texture texture, int materialClass, Cancellation token) {
        return reduce(texture, materialClass, IMAGE_SIDE, token);
    }

    /** As above, to at most {@code side} pixels a side (a power of two up to {@link #IMAGE_SIDE}). */
    static Image reduce(MeshAppearance.Texture texture, int materialClass, int side, Cancellation token) {
        if (materialClass != OPAQUE && materialClass != CUTOUT && materialClass != BLENDED)
            throw new IllegalArgumentException("Material class");
        if (side < 1 || side > IMAGE_SIDE || Integer.bitCount(side) != 1)
            throw new IllegalArgumentException("Image side");
        int width = texture.width(), height = texture.height();
        long sourcePassing = 0;
        int w = Math.min(side, Integer.highestOneBit(width));
        int h = Math.min(side, Integer.highestOneBit(height));
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
        if (materialClass == CUTOUT && (w != width || h != height))
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
        Object choicesNow;
        try {
            choicesNow = chooser.revision();
        } catch (RuntimeException unavailable) {
            choicesNow = choiceRevision; // unknown now: the choices stand
        }
        synchronized (this) {
            if (closed) throw new CancellationException("Creature models closed");
            if (!Objects.equals(choicesNow, choiceRevision)) {
                // Outside display sources changed: choose again, but keep converted models.
                choices.clear();
                choiceGeneration++;
                choiceRevision = choicesNow;
            }
            var now = revision.get();
            // A failed load is reused within its revision until the back-off ends, so a broken
            // source costs one load per minute, not one per tile.
            if (index == null || !Objects.equals(now, cachedRevision)
                    || index.isCompletedExceptionally() && clock.getAsLong() - indexFailedAt >= INDEX_RETRY_NANOS) {
                if (indexLoad != null) indexLoad.cancel();
                if (!Objects.equals(now, cachedRevision)) {
                    choices.clear();
                    choiceGeneration++;
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
