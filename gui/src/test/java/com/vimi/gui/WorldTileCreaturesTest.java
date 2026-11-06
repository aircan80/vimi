package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTileWriter;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless checks of static creature models for spawn points: the BASIC conversion and its
 * refusals, tile ownership, the shared objects budget, caching per revision and failure isolation.
 * Authored meshes only; no game data.
 */
public final class WorldTileCreaturesTest {
    private WorldTileCreaturesTest() {}

    /** Native x and y in (-533.33, 0] belong to tile 32,32. */
    private static final WorldFormats.Tile TILE = new WorldFormats.Tile(32, 32, 1, 0, 0);

    public static void main(String[] args) throws Exception {
        conversionKeepsOpaqueSurfacesAndDropsCards();
        conversionRefusesWhatItCannotDraw();
        texturesReduceToPowerOfTwoOpaqueImages();
        appendDrawsOnlyQualifiedSpawnsOfTheTile();
        buildingsKeepTheirBudgetFirst();
        cachesPerRevisionAndRetriesFailures();
        cancellationPropagatesAndSourceFailuresAreNotes();
        statusDisclosure();
        capsStopLookupsAndForeignCancellationIsAFailure();
        failedIndexLoadsBackOff();
        conversionChecksVertexValuesAndLightsMissingNormals();
        BridgeAssembly.run();
        System.out.println("WorldTileCreaturesTest passed");
    }

    private static void conversionKeepsOpaqueSurfacesAndDropsCards() {
        var converted = WorldTileCreatures.convert(preview(7, 0L, 0L, appearance(true)), Cancellation.NONE);
        require(converted.model() != null, "An opaque creature converts: " + converted.detail());
        var model = converted.model();
        require(model.indices().length == 3, "Only the opaque triangle remains");
        require(model.vertexCount() == 3, "Vertices are compacted to the drawn triangles");
        require(model.omittedSurfaces() == 3, "Alpha-keyed, unbound and card sections are counted");
        require(model.ranges().length == 3 && model.ranges()[0] == 0 && model.ranges()[1] == 3
                && model.ranges()[2] == 0, "One range binds the one used image");
        require(model.images().size() == 1, "Only used textures are carried");
        float[] v = model.vertices();
        require(v[0] == 0 && v[1] == 0 && v[2] == 0 && v[5] == 1 && v[6] == 0 && v[7] == 0,
                "Position, normal and uv are kept in M2 model space");
    }

    private static void conversionRefusesWhatItCannotDraw() {
        require(WorldTileCreatures.convert(preview(7, 5L, 0L, appearance(true)), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.CHARACTER_APPEARANCE, "Extended display info is refused");
        require(WorldTileCreatures.convert(preview(7, null, 0L, appearance(true)), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.CHARACTER_APPEARANCE, "Unknown extended display info is refused");
        require(WorldTileCreatures.convert(preview(7, 0L, 3L, appearance(true)), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED, "Geoset data is refused");
        require(WorldTileCreatures.convert(preview(7, 0L, 0L, geosets()), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.VISIBILITY_UNRESOLVED, "Nonzero geoset parts are refused");
        require(WorldTileCreatures.convert(preview(7, 0L, 0L, appearance(false)), Cancellation.NONE).refusal()
                == WorldTileCreatures.Count.NO_OPAQUE_SURFACE, "No opaque surface is refused");
        var missing = new DisplayModelTypes.Preview(DisplayModelTypes.State.UNAVAILABLE, null, null, Set.of(),
                List.of(), false, "Model read is ABSENT");
        var refused = WorldTileCreatures.convert(missing, Cancellation.NONE);
        require(refused.refusal() == WorldTileCreatures.Count.MODEL_UNAVAILABLE
                && refused.detail().equals("Model read is ABSENT"), "An unavailable preview keeps its reason");
    }

    private static void texturesReduceToPowerOfTwoOpaqueImages() {
        int[] argb = new int[300 * 6];
        for (int i = 0; i < argb.length; i++) argb[i] = 0x10ff8040;
        var image = WorldTileCreatures.reduce(new MeshAppearance.Texture(300, 6, argb), Cancellation.NONE);
        require(image.width() == 128 && image.height() == 4, "Sides become powers of two of at most 128");
        require((image.rgba()[0] & 255) == 0xff && (image.rgba()[1] & 255) == 0x80
                && (image.rgba()[2] & 255) == 0x40 && (image.rgba()[3] & 255) == 255,
                "Box filtering keeps a flat colour and forces alpha opaque");
        var small = WorldTileCreatures.reduce(new MeshAppearance.Texture(2, 2,
                new int[] {0xff000000, 0xffffffff, 0xff000000, 0xffffffff}), Cancellation.NONE);
        require(small.width() == 2 && (small.rgba()[4] & 255) == 255, "Small images are kept exactly");
        require(image.rgba().length
                        == com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(128, 4) * 4
                        && small.rgba().length == (4 + 1) * 4,
                "Creature images carry their generated mip chain");
    }

    private static void appendDrawsOnlyQualifiedSpawnsOfTheTile() {
        var previews = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(1, 2, -120, -100, 11.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0),
                WorldSpawnOverlayTest.spawn(1, 3, -600, -100, 13.0),
                WorldSpawnOverlayTest.spawn(1, 4, -100, -110, null))),
                () -> "r1",
                (npc, token) -> npc == 1 ? qualified(7, 1.5) : new WorldTileCreatures.Chosen(
                        new CreatureModelPolicy.Choice(CreatureModelPolicy.State.AMBIGUOUS,
                                CreatureModelPolicy.Reason.SEVERAL_DISPLAYS, 2, null, null, null, "2"), null),
                (display, token) -> {
                    previews.incrementAndGet();
                    return preview(display, 0L, 0L, appearance(true));
                });
        var builder = WorldStreamTileWriter.Exterior.builder();
        var summary = creatures.append(builder, 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 2 && summary.count(WorldTileCreatures.Count.NOT_QUALIFIED) == 1,
                "Two qualified points of the tile are drawn and the ambiguous one is counted");
        require(summary.omitted() == 1, "Neighbour-tile and height-less points are not counted for this tile");
        require(summary.models() == 1 && previews.get() == 1, "One display shares one model and one preview");
        require(builder.placementCount() == 2, "Both placements reach the objects builder");
        require(summary.placed().size() == 2
                        && summary.placed().stream().allMatch(c -> c.spawn().npcId() == 1 && c.npc() != null
                                && c.choice().displayId() == 7 && c.scale() == 1.5),
                "Each drawn placement records its spawn, NPC record, display choice and scale in file order");
        require(summary.plus(summary).placed().isEmpty(), "A summed outcome carries no per-tile records");
        var exterior = builder.build();
        require(exterior.placements() == 2 && exterior.draws() == 2, "One range per placement");
        var transform = WorldTileCreatures.transform(
                index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0)))
                        .near(0, -100, -100, 1, 1).placed().getFirst(), qualified(7, 1.5));
        require(transform[0] == -100 && transform[1] == -100 && transform[2] == 10 && transform[3] == 1.5
                && transform[4] == -1 && transform[5] == 0, "Recorded point, client scale and identity heading");
        require(WorldTileCreatures.transform(
                index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0)))
                        .near(0, -100, -100, 1, 1).placed().getFirst(), qualified(7, null))[3] == 1,
                "An unrecorded scale draws at 1");
        var empty = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0,
                new WorldFormats.Tile(10, 10, 1, 0, 0), Cancellation.NONE);
        require(empty.drawn() == 0 && empty.omitted() == 0 && empty.note() == null, "A tile without spawns is empty");
    }

    private static void buildingsKeepTheirBudgetFirst() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 40; i++) rows.add(WorldSpawnOverlayTest.spawn(1, i + 1, -100 - i, -100, 10.0));
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1",
                (npc, token) -> qualified(7, null), (display, token) -> preview(display, 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == WorldTileCreatures.TILE_PLACEMENTS
                && summary.count(WorldTileCreatures.Count.CAPPED) == 40 - WorldTileCreatures.TILE_PLACEMENTS,
                "Creature placements stop at the per-tile creature cap");
        // A tile whose buildings and doodads leave room for four placements gets four creatures.
        var builder = WorldStreamTileWriter.Exterior.builder();
        int room = builder.placementRoom();
        builder.image(1, 1, ByteBuffer.allocate(4));
        builder.model();
        builder.mesh(FloatBuffer.wrap(new float[24]), IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 0});
        for (int p = 0; p < room - 4; p++) builder.placement(0, 0, 0, 0, 1, 1, 0);
        var shared = creatures.append(builder, 0, TILE, Cancellation.NONE);
        require(shared.drawn() == 4 && shared.count(WorldTileCreatures.Count.CAPPED) == 36,
                "Buildings keep their placements; creatures fill only what remains");
        require(builder.build().placements() == room, "The shared file stays within its profile");
    }

    private static void cachesPerRevisionAndRetriesFailures() {
        var revision = new AtomicReference<Object>("r1");
        var loads = new AtomicInteger();
        var chooses = new AtomicInteger();
        var previews = new AtomicInteger();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var creatures = new WorldTileCreatures(token -> {
            loads.incrementAndGet();
            return index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0)));
        }, revision::get, (npc, token) -> {
            chooses.incrementAndGet();
            return qualified(7, null);
        }, (display, token) -> {
            previews.incrementAndGet();
            if (fail.getAndSet(false)) throw new IllegalStateException("Display previews busy");
            return preview(display, 0L, 0L, appearance(true));
        });
        var failed = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(failed.count(WorldTileCreatures.Count.FAILED) == 1, "A failed preview is counted");
        var drawn = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(drawn.drawn() == 1 && previews.get() == 2, "A failed preview is retried, not cached");
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 1 && chooses.get() == 1 && previews.get() == 2,
                "Index, choice and model are cached within one revision");
        revision.set("r2");
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 2 && chooses.get() == 2 && previews.get() == 3,
                "A new revision reloads the index and drops cached choices and models");
        creatures.close();
        var closed = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(closed.drawn() == 0 && closed.note().contains("closed"),
                "A closed source is a creature note under a live tile token, never a cancellation");
    }

    private static void cancellationPropagatesAndSourceFailuresAreNotes() {
        var token = new Cancellation();
        var creatures = new WorldTileCreatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))),
                () -> "r1", (npc, t) -> {
                    token.cancel();
                    return qualified(7, null);
                }, (display, t) -> preview(display, 0L, 0L, appearance(true)));
        try {
            creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, token);
            require(false, "Cancellation propagates");
        } catch (CancellationException expected) {
            // Canceled.
        }
        var broken = new WorldTileCreatures(t -> {
            throw new IllegalStateException("no snapshot");
        }, () -> "r1", (npc, t) -> qualified(7, null), (display, t) -> null);
        var summary = broken.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == 0 && summary.note().contains("spawn source failed"),
                "A failed spawn source is a note, never a tile failure");
        var none = new WorldTileCreatures(t -> WorldSpawns.unavailable(
                com.vibeminer.concepts.locations.LocationTypes.State.UNKNOWN, "Polestar disabled"),
                () -> "r1", (npc, t) -> qualified(7, null), (display, t) -> null);
        require(none.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE).note()
                .equals("no prepared spawn source"), "No source is disclosed");
    }

    private static void statusDisclosure() {
        var creatures = new WorldTileCreatures(token -> index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0),
                WorldSpawnOverlayTest.spawn(2, 1, -110, -110, 12.0))), () -> "r1",
                (npc, token) -> npc == 1 ? qualified(7, null) : qualified(8, null),
                (display, token) -> preview(display, display == 8 ? 4L : 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        String text = WorldStreamSourceController.creatureDetail(summary, 1);
        require(text.equals("; 1 static creature model on 1 tile (rest pose, facing north)"
                + "; spawns not drawn: 1 character appearance not evaluated"), "Status line: " + text);
        require(WorldStreamSourceController.creatureDetail(WorldTileCreatures.Summary.NONE, 0).isEmpty(),
                "Nothing to disclose without creatures");
        require(WorldStreamSourceController.creatureDetail(WorldTileCreatures.Summary.failed("no prepared spawn source"), 0)
                .equals("; creatures: no prepared spawn source"), "A note is shown when nothing was considered");
    }

    private static void capsStopLookupsAndForeignCancellationIsAFailure() {
        var rows = new ArrayList<Map<String, Object>>();
        // Forty different NPCs, so each drawn point costs one uncached lookup.
        for (int i = 0; i < 40; i++) rows.add(WorldSpawnOverlayTest.spawn(i + 1, 1, -100 - i, -100, 10.0));
        var chooses = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> index(rows), () -> "r1", (npc, token) -> {
            chooses.incrementAndGet();
            return qualified(7, null);
        }, (display, token) -> preview(display, 0L, 0L, appearance(true)));
        var summary = creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(summary.drawn() == WorldTileCreatures.TILE_PLACEMENTS, "The cap still draws its placements");
        require(chooses.get() == WorldTileCreatures.TILE_PLACEMENTS,
                "Spawns past the placement cap are capped without a lookup: " + chooses.get());
        var foreign = new WorldTileCreatures(token -> index(List.of(WorldSpawnOverlayTest.spawn(2, 1, -100, -100, 10.0))),
                () -> "r1", (npc, token) -> {
                    throw new CancellationException("component retired");
                }, (display, token) -> {
                    throw new CancellationException("preview future canceled");
                });
        var failed = foreign.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(failed.count(WorldTileCreatures.Count.FAILED) == 1,
                "A component's own cancellation under a live tile token is a failed lookup");
        var previewCanceled = new WorldTileCreatures(
                token -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))), () -> "r1",
                (npc, token) -> qualified(7, null), (display, token) -> {
                    throw new CancellationException("preview future canceled");
                });
        require(previewCanceled.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE)
                .count(WorldTileCreatures.Count.FAILED) == 1, "A canceled preview future is a failed model");
    }

    private static void failedIndexLoadsBackOff() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var loads = new AtomicInteger();
        var creatures = new WorldTileCreatures(token -> {
            loads.incrementAndGet();
            throw new IllegalStateException("broken snapshot");
        }, () -> "r1", (npc, token) -> qualified(7, null), (display, token) -> null, now::get);
        require(creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE).note()
                .contains("spawn source failed"), "A failed load is a note");
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 1, "A failed load is reused within its back-off, not retried per tile");
        now.addAndGet(WorldTileCreatures.INDEX_RETRY_NANOS);
        creatures.append(WorldStreamTileWriter.Exterior.builder(), 0, TILE, Cancellation.NONE);
        require(loads.get() == 2, "The load is retried after the back-off");
    }

    private static void conversionChecksVertexValuesAndLightsMissingNormals() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
        var bound = List.of(new MeshAppearance.Surface(0, 0, true, true, false));
        var texture = List.of(new MeshAppearance.Texture(1, 1, new int[] {-1}));
        var lit = WorldTileCreatures.convert(preview(7, 0L, 0L, new MeshAppearance(mesh, new float[6], bound, texture)),
                Cancellation.NONE);
        float[] v = lit.model().vertices();
        require(v[3] == 0 && v[4] == 0 && v[5] == 1, "Missing normals light the model as facing up");
        // Preview meshes already bound their values; convert repeats the objects file's vertex
        // check per model, and the bridge test below covers a bad vertex reaching the file check.
    }

    /** Buildings survive every kind of creature problem on a tile that has buildings. */
    static final class BridgeAssembly {
        static void run() {
            java.util.function.Consumer<WorldStreamTileWriter.Exterior.Builder> buildings = builder -> {
                builder.image(1, 1, ByteBuffer.allocate(4));
                builder.model();
                builder.mesh(FloatBuffer.wrap(new float[24]), IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 0});
                builder.placement(0, 0, 0, 0, 1, 1, 0);
                builder.placement(0, 5, 0, 0, 1, 1, 0);
            };
            var good = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) ->
                    creatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))))
                            .append(builder, 0, tile, token), TILE, Cancellation.NONE);
            require(good.exterior().placements() == 3 && good.placements() == 2 && good.creatures().drawn() == 1,
                    "Buildings and creatures share one copy; only buildings count as buildings");
            // A bad vertex added past convert's checks fails the file check at build: buildings stay.
            var invalid = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                builder.image(1, 1, ByteBuffer.allocate(4));
                builder.model();
                float[] bad = new float[24];
                bad[0] = Float.NaN;
                builder.mesh(FloatBuffer.wrap(bad), IntBuffer.wrap(new int[] {0, 1, 2}), new int[] {0, 3, 1});
                builder.placement(1, 0, 0, 0, 1, -1, 0);
                return WorldTileCreatures.Summary.NONE;
            }, TILE, Cancellation.NONE);
            requireBuildings(invalid, "invalid", "A creature set failing the file checks keeps the buildings");
            var closedSource = creatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))));
            closedSource.close();
            var closed = WorldStreamSourceBridge.assemble(buildings, false, "b",
                    (builder, tile, token) -> closedSource.append(builder, 0, tile, token), TILE, Cancellation.NONE);
            requireBuildings(closed, "closed", "A closed creature source keeps the buildings");
            var canceledPreview = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) ->
                    new WorldTileCreatures(t -> index(List.of(WorldSpawnOverlayTest.spawn(1, 1, -100, -100, 10.0))),
                            () -> "r1", (npc, t) -> qualified(7, null), (display, t) -> {
                                var future = new java.util.concurrent.CompletableFuture<DisplayModelTypes.Preview>();
                                future.cancel(false);
                                return future.join();
                            }).append(builder, 0, tile, token), TILE, Cancellation.NONE);
            require(canceledPreview.exterior().placements() == 2 && !canceledPreview.failed()
                            && canceledPreview.creatures().count(WorldTileCreatures.Count.FAILED) == 1,
                    "A canceled preview future keeps the buildings");
            var foreign = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                builder.model();
                throw new CancellationException("component retired");
            }, TILE, Cancellation.NONE);
            requireBuildings(foreign, "canceled outside", "A cancellation that is not the tile's keeps the buildings");
            var thrown = WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                builder.model();
                throw new IllegalStateException("authored");
            }, TILE, Cancellation.NONE);
            requireBuildings(thrown, "failed", "A thrown creature failure keeps the buildings");
            var own = new Cancellation();
            try {
                WorldStreamSourceBridge.assemble(buildings, false, "b", (builder, tile, token) -> {
                    own.cancel();
                    throw new CancellationException("tile canceled");
                }, TILE, own);
                require(false, "The tile's own cancellation propagates");
            } catch (CancellationException expected) {
                // Preempted by terrain.
            }
            var none = WorldStreamSourceBridge.assemble(null, true, "refused", (builder, tile, token) -> {
                throw new IllegalStateException("authored");
            }, TILE, Cancellation.NONE);
            require(none.exterior() == null && none.failed() && none.note().equals("refused"),
                    "Without buildings a creature failure delivers nothing and keeps the building note");
        }

        private static WorldTileCreatures creatures(WorldSpawnOverlay.Loader loader) {
            return new WorldTileCreatures(loader, () -> "r1", (npc, token) -> qualified(7, null),
                    (display, token) -> preview(display, 0L, 0L, appearance(true)));
        }

        private static void requireBuildings(WorldStreamSourceBridge.ExteriorCopy copy, String note, String message) {
            require(copy.exterior() != null && copy.exterior().placements() == 2 && copy.placements() == 2
                    && !copy.failed() && copy.creatures().drawn() == 0
                    && copy.creatures().note() != null && copy.creatures().note().contains(note),
                    message + ": " + (copy.creatures().note()));
        }
    }

    static WorldSpawns.Index index(List<Map<String, Object>> rows) {
        return WorldSpawnOverlayTest.index(rows);
    }

    static WorldTileCreatures.Chosen qualified(int display, Double scale) {
        return new WorldTileCreatures.Chosen(new CreatureModelPolicy.Choice(CreatureModelPolicy.State.QUALIFIED,
                CreatureModelPolicy.Reason.SINGLE_DISPLAY, 1, display, 20L, 123456L, "single"), scale);
    }

    static DisplayModelTypes.Preview preview(long display, Long extended, Long geosets, MeshAppearance appearance) {
        return new DisplayModelTypes.Preview(DisplayModelTypes.State.PARTIAL,
                new DisplayModelTypes.Reference(display, 20, 123456, List.of(), extended, geosets),
                appearance, Set.of(0), List.of(), false, null);
    }

    /**
     * Four parts: part 0 one opaque triangle, part 1 alpha-keyed, part 2 unbound and part 3 a rigid
     * card on an unlit additive surface. With {@code opaque} false, part 0 is alpha-keyed too.
     */
    static MeshAppearance appearance(boolean opaque) {
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0, 5, 5, 5, 6, 5, 5, 5, 6, 5, 7, 7, 7};
        int[] indices = {0, 1, 2, 0, 2, 1, 0, 1, 6, 3, 4, 5};
        var mesh = new PreviewMesh(positions, indices, List.of(new PreviewMesh.Part(0, 0, 3),
                new PreviewMesh.Part(0, 3, 3), new PreviewMesh.Part(0, 6, 3), new PreviewMesh.Part(0, 9, 3)));
        float[] uv = new float[7 * 2];
        float[] normals = new float[7 * 3];
        for (int v = 0; v < 7; v++) normals[v * 3 + 2] = 1;
        var texture = new MeshAppearance.Texture(2, 2, new int[] {-1, -1, -1, -1});
        var cards = List.of(new MeshAppearance.RigidBillboard(3, new MeshAppearance.Vec3(5, 5, 5), new int[] {3, 4, 5}));
        var additive = new MeshAppearance.Material(MeshAppearance.Combiner.DIFFUSE,
                List.of(new MeshAppearance.TextureStage(0, MeshAppearance.Coordinate.UV0, true, true)),
                MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.ADDITIVE);
        return new MeshAppearance(mesh, uv, normals,
                List.of(new MeshAppearance.Surface(0, 0, true, true, !opaque),
                        new MeshAppearance.Surface(1, 0, true, true, true),
                        new MeshAppearance.Surface(3, additive)),
                List.of(texture), cards);
    }

    static MeshAppearance geosets() {
        var mesh = new PreviewMesh(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(101, 0, 3)));
        return new MeshAppearance(mesh, new float[6], List.of(new MeshAppearance.Surface(0, 0, true, true, false)),
                List.of(new MeshAppearance.Texture(1, 1, new int[] {-1})));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
