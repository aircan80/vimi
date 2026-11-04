package com.vimi.gui;

import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.TerrainRenderer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Headless CPU picking checks against synthetic terrain heights and spawn records; no assets. */
public final class WorldPickTest {
    private static final double UNIT = WorldFormats.TILE_SIZE / 256, CELL = 2 * UNIT;
    private static final double PX = 1000, PY = 2000;

    private WorldPickTest() {}

    public static void main(String[] args) {
        rayInvertsTheTerrainProjection();
        rayHitsTheRenderedSurfaceUnderThePixel();
        holesSkyAndUndergroundAreNeverGuessed();
        markerHitPrefersTheMarkerDrawnOnTop();
        descriptionsReportUnknownsHonestly();
        System.out.println("World pick: ray inversion, surface hits, misses, marker hits and disclosure passed");
    }

    /** Plane z = 10 + 0.25 * (PX - x) - 0.1 * (PY - y), sampled on one ADT chunk. */
    static double plane(double x, double y) {
        return 10 + .25 * (PX - x) - .1 * (PY - y);
    }

    /** One chunk on the documented 145-vertex grid with every cell rendered except {@code hole}. */
    static TerrainGround ground(int holeRow, int holeCol) {
        var vertices = FloatBuffer.allocate(145 * 3);
        for (int row = 0; row < 17; row++) {
            for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                float x = (float) (PX - row * UNIT), y = (float) (PY - col * CELL - (row % 2) * UNIT);
                vertices.put(x).put(y).put((float) plane(x, y));
            }
        }
        vertices.flip();
        var indices = new ArrayList<Integer>();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                if (row == holeRow && col == holeCol) continue;
                int a = row * 17 + col, m = a + 9, b = a + 1, d = a + 17, e = d + 1;
                for (int index : new int[] {a, m, b, b, m, e, e, m, d, d, m, a}) indices.add(index);
            }
        }
        var packed = IntBuffer.allocate(indices.size());
        indices.forEach(packed::put);
        packed.flip();
        var tile = TerrainGround.Tile.of(vertices, packed);
        require(tile != null, "Synthetic chunk qualifies as an ADT18 layout");
        return TerrainGround.of(List.of(tile));
    }

    private static void rayInvertsTheTerrainProjection() {
        var camera = new TerrainRenderer.Camera(-600, -4180, 60, 2.1, -.35);
        var eyeCamera = TerrainCamera.from(camera);
        double[][] points = {{-640.5, -4120.5, 41.3}, {-650, -4100, 30}, {-625, -4135, 52}};
        for (var point : points) {
            var eye = eyeCamera.eye(point[0], point[1], point[2]);
            double sx = TerrainCamera.screenX(eye, 800, 500), sy = TerrainCamera.screenY(eye, 800, 500);
            // Frame 800x500 fit into 1600x1200: scale 2 with a 100 px top letterbox.
            var ray = WorldPick.ray(camera, 800, 500, 1600, 1200, sx * 2, sy * 2 + 100).orElseThrow();
            double t = Math.sqrt(sq(point[0] - ray.x()) + sq(point[1] - ray.y()) + sq(point[2] - ray.z()));
            var along = ray.at(t);
            require(near(along[0], point[0], 1e-6) && near(along[1], point[1], 1e-6) && near(along[2], point[2], 1e-6),
                    "Ray through a projected pixel passes through its world point");
        }
        require(near(sq(WorldPick.ray(camera, 800, 500, 800, 500, 13, 77).orElseThrow().dx())
                + sq(WorldPick.ray(camera, 800, 500, 800, 500, 13, 77).orElseThrow().dy())
                + sq(WorldPick.ray(camera, 800, 500, 800, 500, 13, 77).orElseThrow().dz()), 1, 1e-12), "Ray is unit length");
        require(WorldPick.ray(camera, 800, 500, 1600, 1200, 800, 50).isEmpty(), "Letterbox pixels pick nothing");
        require(WorldPick.ray(camera, 0, 500, 800, 500, 1, 1).isEmpty(), "Degenerate frames pick nothing");
    }

    private static void rayHitsTheRenderedSurfaceUnderThePixel() {
        var ground = ground(-1, -1);
        var camera = new TerrainRenderer.Camera(PX + 40, PY - 16, 70, Math.PI, -.7);
        var eyeCamera = TerrainCamera.from(camera);
        double[][] targets = {{PX - 9.3, PY - 12.1}, {PX - 30.2, PY - 3.7}, {PX - 2.5, PY - 31.9}};
        for (var target : targets) {
            double z = plane(target[0], target[1]);
            require(near(ground.heightAt(target[0], target[1]), z, 1e-3), "Planar oracle matches sampled heights");
            var eye = eyeCamera.eye(target[0], target[1], z);
            var ray = WorldPick.ray(camera, 800, 500, 800, 500,
                    TerrainCamera.screenX(eye, 800, 500), TerrainCamera.screenY(eye, 800, 500)).orElseThrow();
            var hit = WorldPick.terrain(ray, ground).orElseThrow();
            require(near(hit.x(), target[0], .02) && near(hit.y(), target[1], .02) && near(hit.z(), z, .02),
                    "Pick lands on the rendered surface point under the pixel: " + hit);
            require(near(hit.distance(), Math.sqrt(sq(target[0] - camera.x()) + sq(target[1] - camera.y())
                    + sq(z - camera.z())), .05), "Pick distance is measured along the ray");
        }
    }

    private static void holesSkyAndUndergroundAreNeverGuessed() {
        // Straight down through the center of cell (row 3, col 4), with and without that cell.
        double x = PX - (2 * 3 + 1) * UNIT, y = PY - (4 + .5) * CELL;
        var down = new WorldPick.Ray(x, y, 200, 0, 0, -1);
        require(WorldPick.terrain(down, ground(-1, -1)).isPresent(), "Rendered cell is picked");
        require(WorldPick.terrain(down, ground(3, 4)).isEmpty(), "A terrain hole is open air, not interpolated");
        require(WorldPick.terrain(new WorldPick.Ray(x, y, 200, 0, 0, 1), ground(-1, -1)).isEmpty(), "Sky picks nothing");
        require(WorldPick.terrain(new WorldPick.Ray(x, y, -50, 0, 0, 1), ground(-1, -1)).isEmpty(),
                "A camera below the surface does not pick the underside it starts beneath");
        require(WorldPick.terrain(down, TerrainGround.empty()).isEmpty(), "Unloaded heights pick nothing");
        require(WorldPick.terrain(new WorldPick.Ray(PX + 5000, PY, 200, 0, 0, -1), ground(-1, -1)).isEmpty(),
                "Points off the loaded tiles pick nothing");
    }

    private static void markerHitPrefersTheMarkerDrawnOnTop() {
        var index = WorldSpawnOverlayTest.index(List.of(
                WorldSpawnOverlayTest.spawn(1, 1, 100, 0, 0.0),
                WorldSpawnOverlayTest.spawn(1, 2, 140, 0, 0.0)));
        var placed = index.near(0, 0, 0, 300, 256).placed();
        var far = new WorldSpawnOverlay.Marker(placed.get(1), 400, 250, 230, 140);
        var nearer = new WorldSpawnOverlay.Marker(placed.get(0), 402, 260, 228, 100);
        var markers = List.of(far, nearer);
        require(WorldPick.marker(markers, 401, 229).orElseThrow() == nearer, "Overlapping markers pick the one drawn on top");
        require(WorldPick.marker(markers, 402, 255).orElseThrow() == nearer, "The stem is clickable");
        require(WorldPick.marker(markers, 460, 229).isEmpty(), "Clicks away from markers pick none");
        require(WorldPick.marker(List.of(), 1, 1).isEmpty(), "No drawn markers pick none");
    }

    private static void descriptionsReportUnknownsHonestly() {
        var named = WorldSpawnOverlayTest.index(List.of(WorldSpawnOverlayTest.spawn(1, 3, -640.5, -4120.5, 41.3)));
        var spawn = named.near(0, -640.5, -4120.5, 10, 4).placed().getFirst();
        String text = WorldPick.describeSpawn(spawn, named.npc(1).orElseThrow(), "Eastern Kingdoms");
        require(text.contains("Authored guard") && text.contains("NPC ID 1") && text.contains("rank 1"),
                "Named NPC record is shown: " + text);
        require(text.contains("level unknown"), "Missing levels are unknown: " + text);
        require(text.contains("Polestar spawn record npc:1:0:3 (fields x,y,z)") && text.contains("client 1.60.1.70532"),
                "Source record and client build are shown: " + text);
        require(text.contains("map 0 Eastern Kingdoms") && text.contains("tile 33,39"), "Map and tile are shown: " + text);
        require(text.contains("phase, patrols and live server state unknown"), "Live state is unknown: " + text);

        var orphan = WorldSpawnsIndexWithoutNames.spawn();
        String unnamed = WorldPick.describeSpawn(orphan, null, null);
        require(unnamed.contains("NPC 7 · name unknown") && unnamed.contains("map 0 ·"),
                "Missing NPC record and map name stay unknown: " + unnamed);

        var hit = new WorldPick.Hit(-640.5, -4120.5, 41.3, 70);
        var known = WorldCurrentArea.resolve(0, 10, WorldCurrentArea.Index.of(List.of(
                new WorldService.Area(1, "Elwynn Forest", 0, 0), new WorldService.Area(10, "Goldshire", 1, 0))));
        String terrain = WorldPick.describeTerrain(hit, 0, "Eastern Kingdoms", known);
        require(terrain.startsWith("Terrain · map 0 Eastern Kingdoms · tile 33,39 · x -640.5 / y -4120.5 / z 41.3 · 70 yd away")
                && terrain.contains("Area: Elwynn Forest · Goldshire"), "Terrain fields are shown: " + terrain);
        var unknownArea = WorldCurrentArea.resolve(0, 42, WorldCurrentArea.Index.of(List.of()));
        require(WorldPick.describeTerrain(hit, 0, null, unknownArea)
                        .contains("Area unknown (area record not in the catalog; recorded area ID 42)"),
                "Unresolved area IDs are reported, not named");
        require(WorldPick.tile(WorldFormats.TILE_SIZE * 40, 0).equals("outside the 64×64 tile grid"),
                "Off-grid points have no tile");
        require(WorldPick.describeMiss(false).contains("not loaded"), "Unloaded heights are disclosed");
    }

    /** A spawn whose NPC has no record in the snapshot's NPC table. */
    private static final class WorldSpawnsIndexWithoutNames {
        static com.vibeminer.concepts.locations.WorldSpawns.Spawn spawn() {
            var index = com.vibeminer.concepts.locations.WorldSpawns.polestar(WorldSpawnOverlayTest.SOURCE,
                    List.of(WorldSpawnOverlayTest.spawn(7, 1, 10, 10, 1.0)), List.<Map<String, Object>>of(), List.of(),
                    com.vibeminer.access.Cancellation.NONE);
            require(index.npc(7).isEmpty(), "Fixture has no NPC record");
            return index.near(0, 10, 10, 5, 4).placed().getFirst();
        }
    }

    private static double sq(double value) {
        return value * value;
    }

    private static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
