package com.vimi.gui;

import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.TerrainRenderer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * CPU picking for the World explorer: viewport pixels to camera rays, rays against the retained
 * rendered-surface heights, and screen-space hits on already projected spawn markers. Pure and
 * thread-agnostic; inputs are immutable snapshots. Only ADT terrain surfaces are picked: WMOs,
 * doodads, water and live creatures are not, and nothing here infers an identity.
 */
final class WorldPick {
    /** Farthest picked surface, matching the explorer's whole-triangle draw distance. */
    static final double MAX_DISTANCE = TerrainCamera.WHOLE_TRIANGLE_FAR;

    private static final double MIN_STEP = .25, MAX_STEP = 8, STEP_FRACTION = .01;
    private static final int REFINE = 40, MAX_SAMPLES = 4096;

    private WorldPick() {}

    /** Unit-length world-space ray from the camera eye. */
    record Ray(double x, double y, double z, double dx, double dy, double dz) {
        double[] at(double t) {
            return new double[] {x + dx * t, y + dy * t, z + dz * t};
        }
    }

    /** A rendered-surface hit in native world yards. */
    record Hit(double x, double y, double z, double distance) {}

    /**
     * Ray through a viewport pixel, inverting the explorer's frame fit (centered, aspect kept) and
     * the terrain renderer's eye convention (focal length 0.8 frame widths). Empty for letterbox
     * pixels and degenerate sizes.
     */
    static Optional<Ray> ray(TerrainRenderer.Camera camera, double frameWidth, double frameHeight,
            double viewWidth, double viewHeight, double px, double py) {
        Objects.requireNonNull(camera);
        if (!(frameWidth > 0) || !(frameHeight > 0) || !(viewWidth > 0) || !(viewHeight > 0)
                || !Double.isFinite(px) || !Double.isFinite(py)) return Optional.empty();
        double scale = Math.min(viewWidth / frameWidth, viewHeight / frameHeight);
        double left = (viewWidth - frameWidth * scale) / 2, top = (viewHeight - frameHeight * scale) / 2;
        double fx = (px - left) / scale, fy = (py - top) / scale;
        if (fx < 0 || fx > frameWidth || fy < 0 || fy > frameHeight) return Optional.empty();
        double focal = frameWidth * .8;
        double right = (fx - frameWidth * .5) / focal, up = (frameHeight * .5 - fy) / focal, depth = 1;
        double cy = Math.cos(camera.yaw()), sy = Math.sin(camera.yaw());
        double cp = Math.cos(camera.pitch()), sp = Math.sin(camera.pitch());
        // Inverse of TerrainCamera.eye: rotate eye right/up/depth back to native X/Y/Z.
        double forward = cp * depth - sp * up, dz = sp * depth + cp * up;
        double dx = cy * forward + sy * right, dy = sy * forward - cy * right;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Optional.of(new Ray(camera.x(), camera.y(), camera.z(), dx / length, dy / length, dz / length));
    }

    /**
     * First crossing from above to on-or-below the rendered surface along the ray. Samples where
     * no surface is rendered (holes, unloaded tiles) count as open air. A camera already below the
     * surface only hits after the ray emerges above it again. Empty when nothing is hit within
     * {@link #MAX_DISTANCE}.
     */
    static Optional<Hit> terrain(Ray ray, TerrainGround ground) {
        Objects.requireNonNull(ray);
        Objects.requireNonNull(ground);
        if (ground.tiles() == 0) return Optional.empty();
        double previous = 0, start = clearance(ray, ground, 0);
        boolean above = Double.isNaN(start) || start > 0;
        double t = TerrainCamera.NEAR;
        for (int samples = 0; t <= MAX_DISTANCE && samples < MAX_SAMPLES; samples++) {
            double gap = clearance(ray, ground, t);
            if (Double.isNaN(gap) || gap > 0) {
                above = true;
            } else if (above) {
                return Optional.of(refine(ray, ground, previous, t));
            }
            previous = t;
            t += Math.clamp(t * STEP_FRACTION, MIN_STEP, MAX_STEP);
        }
        return Optional.empty();
    }

    private static Hit refine(Ray ray, TerrainGround ground, double open, double solid) {
        for (int i = 0; i < REFINE; i++) {
            double middle = (open + solid) / 2, gap = clearance(ray, ground, middle);
            if (Double.isNaN(gap) || gap > 0) open = middle;
            else solid = middle;
        }
        var point = ray.at(solid);
        double height = ground.heightAt(point[0], point[1]);
        return new Hit(point[0], point[1], Double.isNaN(height) ? point[2] : height, solid);
    }

    /** Ray height above the rendered surface at distance t, or NaN where none is rendered. */
    private static double clearance(Ray ray, TerrainGround ground, double t) {
        var point = ray.at(t);
        double height = ground.heightAt(point[0], point[1]);
        return Double.isNaN(height) ? Double.NaN : point[2] - height;
    }

    /**
     * The nearest marker whose drawn stem or head lies under the pointer. Markers are painted far
     * first, so the last match is the one drawn on top.
     */
    static Optional<WorldSpawnOverlay.Marker> marker(List<WorldSpawnOverlay.Marker> markers,
            double px, double py) {
        Objects.requireNonNull(markers);
        WorldSpawnOverlay.Marker hit = null;
        for (var marker : markers) {
            double radius = WorldSpawnOverlay.headRadius(marker.depth()) + 3;
            double dx = px - marker.x(), dy = py - marker.headY();
            boolean head = dx * dx + dy * dy <= radius * radius;
            double low = Math.min(marker.headY(), marker.footY()), high = Math.max(marker.headY(), marker.footY());
            boolean stem = Math.abs(dx) <= 3 && py >= low - 1 && py <= high + 1;
            if (head || stem) hit = marker;
        }
        return Optional.ofNullable(hit);
    }

    /** Terrain inspection text; every unknown field says so. */
    static String describeTerrain(Hit hit, long mapId, String mapName, WorldCurrentArea.Result area) {
        Objects.requireNonNull(hit);
        Objects.requireNonNull(area);
        var text = new StringBuilder("Terrain · ").append(map(mapId, mapName)).append(" · ")
                .append(tile(hit.x(), hit.y())).append(" · ")
                .append(String.format(Locale.ROOT, "x %.1f / y %.1f / z %.1f · %.0f yd away",
                        hit.x(), hit.y(), hit.z(), hit.distance()))
                .append(" · ").append(area(area));
        return text.append(" · rendered ADT surface only; buildings, doodads and water are not picked").toString();
    }

    /** Pointer over sky, a hole or terrain that is not loaded: report that, never a guess. */
    static String describeMiss(boolean groundLoaded) {
        return groundLoaded
                ? "Nothing picked · no rendered terrain under the pointer within "
                        + (int) MAX_DISTANCE + " yd (sky, a terrain hole or an unloaded tile)"
                : "Nothing picked · terrain heights are not loaded yet";
    }

    /** Declared spawn source record; NPC facts come only from the same snapshot's NPC table. */
    static String describeSpawn(WorldSpawns.Spawn spawn, WorldSpawns.Npc npc, String mapName) {
        Objects.requireNonNull(spawn);
        var text = new StringBuilder("NPC spawn · ");
        if (npc == null) {
            text.append("NPC ").append(spawn.npcId()).append(" · name unknown (no NPC record in this snapshot)");
        } else {
            text.append(npc.name() == null ? "name unknown" : npc.name());
            if (npc.subname() != null && !npc.subname().isBlank()) text.append(" <").append(npc.subname()).append('>');
            text.append(" · NPC ID ").append(npc.id()).append(" · level ").append(levels(npc))
                    .append(" · rank ").append(npc.rank() == null ? "unknown" : npc.rank());
        }
        text.append(" · ").append(map(spawn.mapId(), mapName)).append(" · ").append(tile(spawn.x(), spawn.y()))
                .append(String.format(Locale.ROOT, " · x %.1f / y %.1f / z %s", spawn.x(), spawn.y(),
                        spawn.z() == null ? "not recorded" : String.format(Locale.ROOT, "%.1f", spawn.z())));
        var evidence = spawn.evidence();
        var source = evidence.source();
        text.append(" · source: ").append(provider(source.provider())).append(' ').append(evidence.dataset())
                .append(" record ").append(evidence.record()).append(" (fields ").append(evidence.field())
                .append(") · client ").append(source.build()).append(' ').append(source.locale())
                .append(" · snapshot ").append(shortSnapshot(source.snapshot()));
        return text.append(" · phase, patrols and live server state unknown").toString();
    }

    static String tile(double x, double y) {
        int tx = WorldBookmarks.tileIndex(x), ty = WorldBookmarks.tileIndex(y);
        return tx < 0 || ty < 0 ? "outside the 64×64 tile grid" : "tile " + tx + "," + ty;
    }

    static String area(WorldCurrentArea.Result area) {
        if (area.state() == WorldCurrentArea.State.KNOWN) return area.label().replaceFirst("^Terrain area: ", "Area: ");
        String why = switch (area.reason()) {
            case NO_LOADED_METADATA -> "no area metadata loaded";
            case NO_COVERAGE -> "no loaded area chunk covers this point";
            case OVERLAP -> "overlapping area chunks";
            case UNKNOWN_AREA -> "area record not in the catalog";
            case DUPLICATE_ID -> "duplicate area records";
            case UNKNOWN_PARENT -> "parent area unknown";
            case CYCLE -> "area hierarchy cycle";
            case MAP_MISMATCH -> "area belongs to another map";
            case DEPTH_LIMIT -> "area hierarchy too deep";
            case NONE -> "not resolved";
        };
        return "Area unknown (" + why + (area.areaId() > 0 ? "; recorded area ID " + area.areaId() : "") + ")";
    }

    private static String map(long mapId, String mapName) {
        return mapName == null || mapName.isBlank() ? "map " + mapId : "map " + mapId + " " + mapName;
    }

    private static String levels(WorldSpawns.Npc npc) {
        if (npc.levelMin() == null && npc.levelMax() == null) return "unknown";
        if (npc.levelMin() == null || npc.levelMax() == null || npc.levelMin().equals(npc.levelMax()))
            return String.valueOf(npc.levelMin() == null ? npc.levelMax() : npc.levelMin());
        return npc.levelMin() + "–" + npc.levelMax();
    }

    private static String shortSnapshot(String snapshot) {
        return snapshot.length() > 12 ? snapshot.substring(0, 12) : snapshot;
    }

    private static String provider(LocationTypes.Provider provider) {
        return switch (provider) {
            case POLESTAR -> "Polestar";
            case QUESTIE -> "Questie";
            case EG_LINK -> "EG Link";
        };
    }
}
