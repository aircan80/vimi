package com.vimi.gui;

import com.vibeminer.concepts.creatures.CreatureModelPolicy;
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
 * thread-agnostic; inputs are immutable snapshots. Drawn exterior objects are picked by
 * {@link WorldObjectPicks}; water and live creatures are not, and nothing here infers an identity.
 */
final class WorldPick {
    /** Farthest picked surface, matching the explorer's whole-triangle draw distance. */
    static final double MAX_DISTANCE = TerrainCamera.WHOLE_TRIANGLE_FAR;

    private static final double MIN_STEP = .25, MAX_STEP = 2, STEP_FRACTION = .01;
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
     * no surface is rendered (holes, unloaded tiles) are uncovered. When the ray enters covered
     * terrain from an uncovered stretch, it only counts as a hit if it is above the surface at
     * the coverage edge; a ray that passed through a hole or arrives below the surface keeps
     * marching. A camera already below the surface only hits after the ray emerges above it.
     * Empty when nothing is hit within {@link #MAX_DISTANCE}.
     */
    static Optional<Hit> terrain(Ray ray, TerrainGround ground) {
        Objects.requireNonNull(ray);
        Objects.requireNonNull(ground);
        if (ground.tiles() == 0) return Optional.empty();
        double previous = 0, start = clearance(ray, ground, 0);
        boolean covered = !Double.isNaN(start), above = covered && start > 0;
        double t = TerrainCamera.NEAR;
        for (int samples = 0; t <= MAX_DISTANCE && samples < MAX_SAMPLES; samples++) {
            double gap = clearance(ray, ground, t);
            if (Double.isNaN(gap)) {
                covered = false;
            } else if (gap > 0) {
                above = covered = true;
            } else {
                if (!covered) {
                    double edge = coverageEdge(ray, ground, previous, t), atEdge = clearance(ray, ground, edge);
                    if (!Double.isNaN(atEdge) && atEdge > 0) return Optional.of(refine(ray, ground, edge, t));
                    above = false;
                } else if (above) {
                    return Optional.of(refine(ray, ground, previous, t));
                }
                covered = true;
            }
            previous = t;
            t += Math.clamp(t * STEP_FRACTION, MIN_STEP, MAX_STEP);
        }
        return Optional.empty();
    }

    /** First covered distance between an uncovered and a covered sample. */
    private static double coverageEdge(Ray ray, TerrainGround ground, double uncovered, double coveredAt) {
        for (int i = 0; i < REFINE; i++) {
            double middle = (uncovered + coveredAt) / 2;
            if (Double.isNaN(clearance(ray, ground, middle))) uncovered = middle;
            else coveredAt = middle;
        }
        return coveredAt;
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

    /** Terrain inspection text without object picking (no drawn objects were tested). */
    static String describeTerrain(Hit hit, long mapId, String mapName, WorldCurrentArea.Result area) {
        return describeTerrain(hit, mapId, mapName, area, 0);
    }

    /**
     * Terrain inspection text; every unknown field says so. {@code objectTiles} is how many
     * resident tiles' drawn objects were tested and found farther than the terrain, or missed.
     */
    static String describeTerrain(Hit hit, long mapId, String mapName, WorldCurrentArea.Result area,
            int objectTiles) {
        Objects.requireNonNull(hit);
        Objects.requireNonNull(area);
        var text = new StringBuilder("Terrain · ").append(map(mapId, mapName)).append(" · ")
                .append(tile(hit.x(), hit.y())).append(" · ")
                .append(String.format(Locale.ROOT, "x %.1f / y %.1f / z %.1f · %.0f yd away",
                        hit.x(), hit.y(), hit.z(), hit.distance()))
                .append(" · ").append(area(area));
        text.append(" · rendered ADT surface");
        if (objectTiles > 0) text.append("; no drawn building, doodad or creature model of the ").append(objectTiles)
                .append(objectTiles == 1 ? " object tile" : " object tiles").append(" is nearer");
        else text.append("; no exterior objects are drawn here to pick");
        return text.append("; water is not picked").toString();
    }

    /** Pointer over sky, a hole or terrain that is not loaded: report that, never a guess. */
    static String describeMiss(boolean groundLoaded) {
        return groundLoaded
                ? "Nothing picked · no retained terrain heights under the pointer within "
                        + (int) MAX_DISTANCE + " yd (sky, a terrain hole, or terrain outside the"
                        + " retained height tiles)"
                : "Nothing picked · terrain heights are not loaded yet";
    }

    /** Declared spawn source record without a model outcome (no creature model resolver). */
    static String describeSpawn(WorldSpawns.Spawn spawn, WorldSpawns.Npc npc, String mapName) {
        return describeSpawn(spawn, npc, mapName, null, false);
    }

    /**
     * Declared spawn source record; NPC facts come only from the same snapshot's NPC table. With a
     * resolver, the creature model outcome follows: the qualified model file ID, its listfile name
     * and the chain source, or why no single model is claimed. A pending outcome says so.
     */
    static String describeSpawn(WorldSpawns.Spawn spawn, WorldSpawns.Npc npc, String mapName,
            WorldSpawnModels.Resolved model, boolean resolving) {
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
                .append(") · client ").append(known(source.build())).append(' ').append(known(source.locale()))
                .append(" · snapshot ").append(shortSnapshot(source.snapshot()));
        if (model != null) text.append(" · ").append(describeModel(model));
        else if (resolving) text.append(" · model: resolving…");
        return text.append(" · phase, patrols and live server state unknown").toString();
    }

    /** One creature model outcome; the terrain child draws only qualified static previews. */
    static String describeModel(WorldSpawnModels.Resolved model) {
        var choice = model.choice();
        var text = new StringBuilder("model ");
        switch (choice.state()) {
            case QUALIFIED -> text.append("QUALIFIED: FileDataID ").append(choice.modelFileDataId()).append(' ')
                    .append(model.modelPath() == null ? "(name unknown)" : model.modelPath())
                    .append(" · display ").append(choice.displayId()).append(" → CreatureModelData ")
                    .append(choice.modelId());
            case AMBIGUOUS -> text.append("AMBIGUOUS (").append(choice.displays()).append(" displays): ")
                    .append(choice.detail());
            case UNRESOLVED -> text.append("UNRESOLVED (").append(reason(choice.reason())).append("): ")
                    .append(choice.detail());
            case UNKNOWN -> text.append("UNKNOWN (").append(reason(choice.reason())).append("): ")
                    .append(choice.detail());
        }
        return text.append(" · source ").append(model.source()).append(choice.state() == com.vibeminer.concepts.creatures.CreatureModelPolicy.State.QUALIFIED
                ? " · drawn as a static model only when its preview qualifies (see World status)"
                : " · not drawn").toString();
    }

    /** Short refusal reason label for status counts. */
    static String reason(CreatureModelPolicy.Reason reason) {
        return switch (reason) {
            case SINGLE_DISPLAY -> "single display";
            case SEVERAL_DISPLAYS -> "several displays";
            case NO_DISPLAY -> "no display";
            case INVALID_DISPLAY -> "invalid display";
            case DISPLAY_UNREADABLE -> "display unreadable";
            case DISPLAY_ABSENT -> "display absent";
            case DISPLAY_PROTECTED -> "display encrypted";
            case NO_MODEL -> "no model";
            case MODEL_UNREADABLE -> "model unreadable";
            case MODEL_ABSENT -> "model absent";
            case MODEL_PROTECTED -> "model encrypted";
            case NO_MODEL_FILE -> "no model file";
            case CREATURE_ABSENT -> "no creature record";
            case CREATURE_PROTECTED -> "creature encrypted";
            case CREATURE_UNREADABLE -> "creature unreadable";
            case NO_CREATURE_DATA -> "no creature data";
        };
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
            case NONE -> "area catalog not loaded";
        };
        return "Area unknown (" + why + (area.areaId() > 0 ? "; recorded area ID " + area.areaId() : "") + ")";
    }

    private static String map(long mapId, String mapName) {
        return mapName == null || mapName.isBlank() ? "map " + mapId : "map " + mapId + " " + mapName;
    }

    private static String levels(WorldSpawns.Npc npc) {
        if (npc.levelMin() == null && npc.levelMax() == null) return "unknown";
        if (npc.levelMax() == null) return "≥ " + npc.levelMin() + " (maximum unknown)";
        if (npc.levelMin() == null) return "≤ " + npc.levelMax() + " (minimum unknown)";
        if (npc.levelMin().equals(npc.levelMax())) return String.valueOf(npc.levelMin());
        return npc.levelMin() + "–" + npc.levelMax();
    }

    private static String known(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String shortSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return "unknown";
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
