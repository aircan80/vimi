package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainRenderer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Headless analytic projection and disclosure checks for World NPC spawn markers. */
public final class WorldSpawnOverlayTest {
    static final LocationTypes.Source SOURCE = new LocationTypes.Source(
            LocationTypes.Provider.POLESTAR, "a".repeat(40), "Forever", "1.60.1.70532", "enUS", "GPL-3.0-only");

    private WorldSpawnOverlayTest() {}

    public static void main(String[] args) {
        analyticProjection();
        projectionMatchesTerrainCameraConvention();
        disclosure();
        System.out.println("World spawn overlay: analytic projection, culling and disclosure passed");
    }

    static Map<String, Object> spawn(long id, long n, double x, double y, Double z) {
        var row = new HashMap<String, Object>();
        row.put("kind", "npc");
        row.put("id", id);
        row.put("map", 0L);
        row.put("n", n);
        row.put("x", x);
        row.put("y", y);
        if (z != null) row.put("z", z);
        return row;
    }

    static WorldSpawns.Index index(List<Map<String, Object>> rows) {
        return WorldSpawns.polestar(SOURCE, rows,
                List.of(Map.of("id", 1L, "name", "Authored guard", "rank", 1L)), List.of(), Cancellation.NONE);
    }

    private static void analyticProjection() {
        var camera = new TerrainRenderer.Camera(0, 0, 0, 0, 0);
        var index = index(List.of(
                spawn(1, 1, 100, 0, 0.0),
                spawn(2, 1, 100, -10, 0.0),
                spawn(3, 1, -100, 0, 0.0),
                spawn(4, 1, 100, 0, null),
                spawn(5, 1, 100, 250, 0.0)));
        var spawns = index.near(0, 0, 0, 300, 256).placed();
        var markers = WorldSpawnOverlay.project(spawns, camera, 800, 500, 1600, 1200);
        // Frame 800x500 fits the 1600x1200 viewport at scale 2 with 100 px vertical letterbox.
        var ahead = marker(markers, 1);
        require(near(ahead.x(), 800) && near(ahead.footY(), 600), "Point ahead projects to the frame center");
        require(near(ahead.headY(), 600 - 2 * (2 * TerrainCamera.focalPixels(800) / 100)), "Head stem rises by two yards");
        var east = marker(markers, 2);
        require(near(east.x(), 800 + 2 * (10 * TerrainCamera.focalPixels(800) / 100)), "Negative native Y (east) is screen right when facing north");
        require(markers.stream().noneMatch(m -> m.spawn().npcId() == 3), "Points behind the camera are culled");
        require(markers.stream().noneMatch(m -> m.spawn().npcId() == 4), "Points without height are never placed");
        require(markers.stream().noneMatch(m -> m.spawn().npcId() == 5), "Points outside the frame are culled");
        require(markers.getFirst().depth() >= markers.getLast().depth(), "Far markers paint first");
        require(WorldSpawnOverlay.project(spawns, camera, 0, 500, 100, 100).isEmpty(), "Empty frame draws nothing");
        // Turning left (positive yaw) moves a northern point to screen right.
        var turned = WorldSpawnOverlay.project(spawns, new TerrainRenderer.Camera(0, 0, 0, .2, 0), 800, 500, 800, 500);
        require(marker(turned, 1).x() > 400, "Positive yaw turns left");
    }

    private static void projectionMatchesTerrainCameraConvention() {
        var camera = new TerrainRenderer.Camera(-600, -4180, 60, 2.1, -.35);
        var index = index(List.of(spawn(1, 1, -640.5, -4120.5, 41.3), spawn(1, 2, -660, -4160, 39.0)));
        var markers = WorldSpawnOverlay.project(index.near(0, -600, -4180, 300, 256).placed(), camera, 800, 500, 800, 500);
        require(!markers.isEmpty(), "Authored points are visible");
        var eyeCamera = TerrainCamera.from(camera);
        for (var marker : markers) {
            var eye = eyeCamera.eye(marker.spawn().x(), marker.spawn().y(), marker.spawn().z());
            require(near(marker.x(), TerrainCamera.screenX(eye, 800, 500))
                    && near(marker.footY(), TerrainCamera.screenY(eye, 800, 500)),
                    "Overlay uses the terrain renderer screen convention");
        }
    }

    private static void disclosure() {
        var index = index(List.of(spawn(1, 1, 10, 0, 1.0), spawn(1, 2, 20, 0, null)));
        var nearby = index.near(0, 0, 0, 300, 256);
        String text = WorldSpawnOverlay.summary(index, nearby, 1, 0);
        require(text.contains("Polestar") && text.contains("1.60.1.70532"), "Source and its client build are shown");
        require(text.contains("1 in view of 1 placed") && text.contains("1 without recorded height"), "Counts are disclosed");
        require(text.contains("not depth-tested") && text.contains("phase unknown"), "Limitations are disclosed");
        var none = index.near(0, 5_000, 5_000, 300, 256);
        require(WorldSpawnOverlay.summary(index, none, 0, 0).contains("0 in view of 0 placed"), "Empty neighborhoods are explicit");
        var unavailable = WorldSpawns.unavailable(LocationTypes.State.UNSUPPORTED, "Polestar is disabled.");
        require(WorldSpawnOverlay.summary(unavailable, null, 0, 0).contains("unavailable: Polestar is disabled."),
                "Unavailable reason is shown");
        require(WorldSpawnOverlay.summary(index, nearby, 1, 3).contains("3 beyond the 256-marker cap"), "Cap is disclosed");
        require(WorldSpawnOverlay.rankColor(3) != WorldSpawnOverlay.rankColor(0), "Rank colors differ");
    }

    private static WorldSpawnOverlay.Marker marker(List<WorldSpawnOverlay.Marker> markers, long id) {
        return markers.stream().filter(m -> m.spawn().npcId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("Missing marker " + id));
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 1e-6;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
