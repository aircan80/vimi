package com.vimi.gui;

import com.vibeminer.concepts.world.WorldExteriorObjects;
import com.vibeminer.rendering.bgfx.WorldStreamTileWriter;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainRenderer;
import com.vibeminer.rendering.world.WorldObjectPicking;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;

/** Headless object picking, source records, inspection text and highlight checks; no assets. */
public final class WorldObjectPicksTest {
    private WorldObjectPicksTest() {}

    public static void main(String[] args) {
        nearestDrawnObjectAcrossTilesWins();
        mismatchedSourcesAreUnknownNeverGuessed();
        descriptionsCarryTheSourceRecords();
        highlightProjectsTheDrawnBounds();
        System.out.println("World object picks: exact hits, source records, disclosure and highlight passed");
    }

    /** A wall in the native plane x = {@code x}: the local quad y = 0 turned a quarter about z. */
    static WorldObjectPicks.Tile wall(int tileX, double x, List<WorldObjectPicks.Source> sources) {
        return walls(tileX, new double[] {x}, sources);
    }

    /** One wall model placed at each {@code x}. */
    static WorldObjectPicks.Tile walls(int tileX, double[] xs, List<WorldObjectPicks.Source> sources) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, (byte) 255}));
        builder.model();
        builder.mesh(FloatBuffer.wrap(new float[] {
                -1, 0, -1, 0, 1, 0, 0, 0,
                1, 0, -1, 0, 1, 0, 1, 0,
                1, 0, 1, 0, 1, 0, 1, 1,
                -1, 0, 1, 0, 1, 0, 0, 1}), IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3}), new int[] {0, 6, 0});
        for (double x : xs) builder.placement(0, x, 0, 0, 2, new double[] {0, -1, 0, 1, 0, 0, 0, 0, 1});
        return WorldObjectPicks.Tile.of(tileX, 20, builder.build().pickTile(), sources);
    }

    /** The key of a wall placed at {@code x}. */
    static WorldObjectPicks.Key key(double x) {
        return new WorldObjectPicks.Key(0, x, 0, 0, 2);
    }

    static WorldObjectPicks.Exterior building(double x) {
        return building(key(x));
    }

    static WorldObjectPicks.Exterior building(WorldObjectPicks.Key placed) {
        return new WorldObjectPicks.Exterior(WorldExteriorObjects.Kind.BUILDING, 3, 789, 1001, 0xc, 1, 0, 2,
                List.of(2001L), List.of(4), 0, placed);
    }

    private static void nearestDrawnObjectAcrossTilesWins() {
        var far = wall(10, 100, List.of(building(100)));
        var near = wall(11, 50, List.of(building(50)));
        var eye = new TerrainCamera(0, 0, 0, 0, 0);
        var forward = new WorldPick.Ray(0, 0, 0, 1, 0, 0);
        var hit = WorldObjectPicks.pick(List.of(far, near), eye, forward, WorldPick.MAX_DISTANCE).orElseThrow();
        require(hit.tile() == near && near(hit.hit().distance(), 50), "The nearest tile's object wins");
        require(WorldObjectPicks.pick(List.of(far, near), eye, forward, 40).isEmpty(),
                "Terrain nearer than every object keeps the terrain");
        require(WorldObjectPicks.pick(List.of(far), eye, new WorldPick.Ray(0, 0, 0, 1, .03, 0), 2200).isEmpty(),
                "A ray past the drawn edge picks nothing");
        require(WorldObjectPicks.pick(List.of(), eye, forward, 2200).isEmpty(), "No resident objects, no pick");
    }

    private static void mismatchedSourcesAreUnknownNeverGuessed() {
        var tile = wall(10, 100, List.of(building(100), building(100)));
        require(tile.sources().size() == 1 && tile.sources().getFirst() instanceof WorldObjectPicks.Unknown,
                "Sources that do not line up with the drawn placements are reported unknown");
        var eye = new TerrainCamera(0, 0, 0, 0, 0);
        var picked = WorldObjectPicks.pick(List.of(tile), eye, new WorldPick.Ray(0, 0, 0, 1, 0, 0), 2200).orElseThrow();
        require(WorldObjectPicks.describe(picked, 0, null, id -> "never").startsWith("Drawn object · source record unknown"),
                "An unknown source names nothing");
        // Same count, but the sources are in the other order: still never matched by position.
        var swapped = walls(10, new double[] {100, 50}, List.of(building(50), building(100)));
        require(swapped.sources().stream().allMatch(source -> source instanceof WorldObjectPicks.Unknown),
                "A reorder with the same count is detected by the placement keys");
        var aligned = walls(10, new double[] {100, 50}, List.of(building(100), building(50)));
        require(aligned.sources().stream().allMatch(source -> source instanceof WorldObjectPicks.Exterior),
                "Keys that match their placements keep their records");
        require(wall(10, 100, List.of(building(new WorldObjectPicks.Key(1, 100, 0, 0, 2)))).sources().getFirst()
                        instanceof WorldObjectPicks.Unknown, "A different model index is a mismatch");
    }

    private static void descriptionsCarryTheSourceRecords() {
        var eye = new TerrainCamera(0, 0, 0, 0, 0);
        var ray = new WorldPick.Ray(0, 0, 0, 1, .01, .005);
        var names = Map.of(1001L, "world/wmo/authored/keep.wmo", 3001L, "world/doodads/authored/barrel.m2");
        java.util.function.LongFunction<String> lookup = names::get;

        var b = WorldObjectPicks.pick(List.of(wall(32, 100, List.of(building(100)))), eye, ray, 2200).orElseThrow();
        String building = WorldObjectPicks.describe(b, 0, "Authored continent", lookup);
        require(building.startsWith("Building · WMO FileDataID 1001 world/wmo/authored/keep.wmo · group 4"
                        + " (FileDataID 2001) · MODF record 3 · unique ID 789 · doodad set 1 · name set 0 · flags 0xc"),
                "Building record fields: " + building);
        require(building.contains("map 0 Authored continent · carried by tile 32,20")
                        && building.contains("placed at x 100.00 / y 0.00 / z 0.00, scale 2.0000")
                        && building.contains("opaque surface"),
                "Placement transform and surface class: " + building);

        var doodad = new WorldObjectPicks.Exterior(WorldExteriorObjects.Kind.DOODAD, 7, 55, 3002, 0x40, 0, 0, 1,
                List.of(3102L), List.of(0), 0, key(100));
        String d = WorldObjectPicks.describe(
                WorldObjectPicks.pick(List.of(wall(32, 100, List.of(doodad))), eye, ray, 2200).orElseThrow(), 0, null,
                lookup);
        require(d.startsWith("Doodad · M2 FileDataID 3002 (name unknown) · SKIN FileDataID 3102 · MDDF record 7"
                + " · unique ID 55 · flags 0x40"), "Unnamed doodad: " + d);

        var set = new WorldObjectPicks.Exterior(WorldExteriorObjects.Kind.SET_DOODAD, 12, 789, 3001, 0, 1, 0, 1,
                List.of(3101L), List.of(0), 1001, key(100));
        String s = WorldObjectPicks.describe(
                WorldObjectPicks.pick(List.of(wall(32, 100, List.of(set))), eye, ray, 2200).orElseThrow(), 0, null,
                lookup);
        require(s.startsWith("WMO set doodad · M2 FileDataID 3001 world/doodads/authored/barrel.m2 · SKIN FileDataID"
                        + " 3101 · MODD entry 12 of doodad set 1 · building WMO FileDataID 1001"
                        + " world/wmo/authored/keep.wmo, unique ID 789"),
                "Set doodad and its building: " + s);

        var index = WorldSpawnOverlayTest.index(List.of(WorldSpawnOverlayTest.spawn(1, 1, 100, 0, 0.0),
                WorldSpawnOverlayTest.spawn(9, 1, 100, 0, 0.0)));
        var spawns = index.near(0, 100, 0, 10, 10).placed();
        var guard = spawns.stream().filter(p -> p.npcId() == 1).findFirst().orElseThrow();
        var stranger = spawns.stream().filter(p -> p.npcId() == 9).findFirst().orElseThrow();
        var choice = WorldTileCreaturesTest.qualified(7, null).choice();
        String c = WorldObjectPicks.describe(WorldObjectPicks.pick(List.of(wall(32, 100, List.of(
                new WorldObjectPicks.Creature(guard, index.npc(1).orElseThrow(), choice, 1, key(100))))), eye, ray, 2200)
                .orElseThrow(), 0, null, id -> id == 123456 ? "creature/authored/guard.m2" : null);
        require(c.startsWith("Creature model · Authored guard · NPC ID 1 · display ID 7 → CreatureModelData 20"
                        + " · model FileDataID 123456 creature/authored/guard.m2 · spawn ")
                        && c.contains("facing is not recorded"),
                "Creature record: " + c);
        String unnamed = WorldObjectPicks.describe(WorldObjectPicks.pick(List.of(wall(32, 100, List.of(
                new WorldObjectPicks.Creature(stranger, null, choice, 1, key(100))))), eye, ray, 2200)
                .orElseThrow(), 0, null, id -> { throw new IllegalStateException("listfile closed"); });
        require(unnamed.startsWith("Creature model · NPC 9 · name unknown (no NPC record in this snapshot)")
                        && unnamed.contains("123456 (name unknown)"),
                "A failed name lookup and a missing NPC record stay unknown: " + unnamed);
    }

    private static void highlightProjectsTheDrawnBounds() {
        var tile = wall(32, 100, List.of(building(100)));
        var corners = tile.geometry().corners(0).orElseThrow();
        var view = new WorldSpawnOverlay.View(new TerrainRenderer.Camera(0, 0, 0, 0, 0), 0, 800, 500);
        var segments = WorldSelectionOverlay.segments(corners, view, 800, 500);
        require(segments.size() == 12, "A box in front of the camera draws its 12 edges");
        // The flat wall projects to a square centered on the frame: |y|, |z| <= 2 at depth 100.
        double half = 2 * 800 * .8 / 100;
        for (var s : segments) {
            for (int i = 0; i < 4; i += 2) {
                require(near(Math.abs(s[i] - 400), half), "Edge x on the projected wall: " + s[i]);
                require(near(Math.abs(s[i + 1] - 250), half), "Edge y on the projected wall: " + s[i + 1]);
            }
        }
        var behind = new WorldSpawnOverlay.View(new TerrainRenderer.Camera(200, 0, 0, 0, 0), 0, 800, 500);
        require(WorldSelectionOverlay.segments(corners, behind, 800, 500).isEmpty(), "A box behind the eye draws nothing");
        var inside = new WorldSpawnOverlay.View(new TerrainRenderer.Camera(100, 0, 0, Math.PI / 2 - .3, 0), 0, 800, 500);
        for (var s : WorldSelectionOverlay.segments(corners, inside, 800, 500))
            for (double v : s) require(Double.isFinite(v), "Edges through the near plane are clipped, not projected");
        require(WorldObjectPicks.material(WorldObjectPicking.CUTOUT).startsWith("cutout"), "Material class label");
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
