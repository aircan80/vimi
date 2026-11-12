package com.vimi.gui;

import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.world.WorldExteriorObjects;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.WorldObjectPicking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Click inspection of the drawn exterior objects (buildings, doodads and creature models) of the
 * resident tiles. Geometry and transforms come from the validated objects file each tile uploaded
 * ({@link WorldObjectPicking}); identities are the source records recorded in the same order as
 * the file's placements, so a selection never infers what it is. Immutable and thread-agnostic.
 */
final class WorldObjectPicks {
    private WorldObjectPicks() {}

    /** What one file placement was built from. */
    sealed interface Source permits Exterior, Creature, Unknown {}

    /**
     * The uploaded identity of one placement: its file model index, position and scale, exactly
     * as handed to the objects builder. A recorded source must match its placement's key.
     */
    record Key(int model, double x, double y, double z, double scale) {
        static Key of(WorldObjectPicking.Tile geometry, int placement) {
            var t = geometry.transform(placement);
            return new Key(geometry.model(placement), t[0], t[1], t[2], t[3]);
        }
    }

    /** One drawn building, MDDF doodad or WMO set doodad, with its model's mesh identities. */
    record Exterior(
            WorldExteriorObjects.Kind kind,
            int ordinal,
            long uniqueId,
            long fileId,
            int flags,
            int doodadSet,
            int nameSet,
            double scale,
            List<Long> meshIds,
            List<Integer> meshOrdinals,
            long parentFileId,
            Key placed) implements Source {
        Exterior {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(placed);
            meshIds = List.copyOf(meshIds);
            meshOrdinals = List.copyOf(meshOrdinals);
        }
    }

    /** One drawn static creature model at a declared spawn; npc is null when not in the snapshot. */
    record Creature(WorldSpawns.Spawn spawn, WorldSpawns.Npc npc, CreatureModelPolicy.Choice choice, double scale,
            Key placed) implements Source {
        Creature {
            Objects.requireNonNull(spawn);
            Objects.requireNonNull(choice);
            Objects.requireNonNull(placed);
        }
    }

    /** A placement whose source record was not carried (the identities did not line up). */
    record Unknown() implements Source {}

    /** One resident tile's drawn objects: the uploaded geometry and one source per placement. */
    record Tile(int tileX, int tileY, WorldObjectPicking.Tile geometry, List<Source> sources) {
        Tile {
            Objects.requireNonNull(geometry);
            sources = List.copyOf(sources);
            if (sources.size() != geometry.placements())
                throw new IllegalArgumentException("One source per drawn placement");
        }

        /**
         * Pairs an uploaded set with its recorded sources. Each source must carry its placement's
         * uploaded {@link Key}; when the counts differ or any key disagrees (a reorder, a drop or an
         * insertion), every placement is reported as unknown rather than matched by position.
         */
        static Tile of(int tileX, int tileY, WorldObjectPicking.Tile geometry, List<Source> sources) {
            if (aligned(geometry, sources)) return new Tile(tileX, tileY, geometry, sources);
            var unknown = new ArrayList<Source>(geometry.placements());
            for (int i = 0; i < geometry.placements(); i++) unknown.add(new Unknown());
            return new Tile(tileX, tileY, geometry, unknown);
        }

        private static boolean aligned(WorldObjectPicking.Tile geometry, List<Source> sources) {
            if (sources.size() != geometry.placements()) return false;
            for (int i = 0; i < sources.size(); i++) {
                var key = switch (sources.get(i)) {
                    case Exterior e -> e.placed();
                    case Creature c -> c.placed();
                    case Unknown u -> null;
                };
                if (!Key.of(geometry, i).equals(key)) return false;
            }
            return true;
        }
    }

    /** The picked placement and where its drawn surface was hit. */
    record Selected(Tile tile, WorldObjectPicking.Hit hit) {
        Selected {
            Objects.requireNonNull(tile);
            Objects.requireNonNull(hit);
        }

        Source source() {
            return tile.sources().get(hit.placement());
        }
    }

    /** Source records of one set's placements, in file order, for the drawn ones only. */
    static final class Recorder {
        private final List<Source> sources = new ArrayList<>();

        void clear() {
            sources.clear();
        }

        void add(Source source) {
            sources.add(Objects.requireNonNull(source));
        }

        List<Source> sources() {
            return List.copyOf(sources);
        }

        /** Root FileDataIDs of the set's drawn buildings by unique ID (deduplicated by the source). */
        static java.util.Map<Long, Long> buildings(WorldExteriorObjects.TileObjects set) {
            var roots = new java.util.HashMap<Long, Long>();
            for (var p : set.placements())
                if (p.kind() == WorldExteriorObjects.Kind.BUILDING) roots.putIfAbsent(p.uniqueId(), p.rootId());
            return roots;
        }

        /** The source of one drawn exterior placement of {@code set}; parents from {@link #buildings}. */
        static Exterior exterior(WorldExteriorObjects.TileObjects set, WorldExteriorObjects.Placement p,
                java.util.Map<Long, Long> parents) {
            var model = set.models().get(p.model());
            var ids = new ArrayList<Long>(model.meshes().size());
            var ordinals = new ArrayList<Integer>(model.meshes().size());
            for (var mesh : model.meshes()) {
                ids.add(mesh.groupId());
                ordinals.add(mesh.groupOrdinal());
            }
            long parent = p.kind() == WorldExteriorObjects.Kind.SET_DOODAD
                    ? parents.getOrDefault(p.uniqueId(), 0L) : 0;
            return new Exterior(p.kind(), p.ordinal(), p.uniqueId(), p.rootId(), p.flags(), p.doodadSet(),
                    p.nameSet(), p.scale(), ids, ordinals, parent,
                    new Key(p.model(), p.x(), p.y(), p.z(), p.scale()));
        }
    }

    /**
     * The nearest drawn object surface along a ray from the camera eye across the resident tiles,
     * no farther than {@code maxDistance} along the ray (infinite: only the eye-depth discard
     * clips).
     */
    static Optional<Selected> pick(List<Tile> tiles, TerrainCamera camera, WorldPick.Ray ray, double maxDistance) {
        Objects.requireNonNull(tiles);
        Objects.requireNonNull(camera);
        Objects.requireNonNull(ray);
        Selected best = null;
        double limit = maxDistance;
        for (var tile : tiles) {
            var hit = WorldObjectPicking.pick(tile.geometry(), camera, ray.dx(), ray.dy(), ray.dz(), limit);
            if (hit.isPresent() && (best == null || hit.get().distance() < best.hit().distance())) {
                best = new Selected(tile, hit.get());
                limit = hit.get().distance();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Inspection text of a selection; {@code names} returns a listfile path or null (it may read
     * metadata, never payloads). Every unknown field says so.
     */
    static String describe(Selected selected, long mapId, String mapName, LongFunction<String> names) {
        Objects.requireNonNull(selected);
        Objects.requireNonNull(names);
        var hit = selected.hit();
        var tile = selected.tile();
        var text = new StringBuilder();
        switch (selected.source()) {
            case Exterior e -> describeExterior(text, e, hit, names);
            case Creature c -> describeCreature(text, c, names);
            case Unknown u -> text.append("Drawn object · source record unknown (the uploaded placements and"
                    + " their recorded sources did not line up)");
        }
        var transform = tile.geometry().transform(hit.placement());
        text.append(" · map ").append(mapId);
        if (mapName != null && !mapName.isBlank()) text.append(' ').append(mapName);
        text.append(" · carried by tile ").append(tile.tileX()).append(',').append(tile.tileY())
                .append(String.format(Locale.ROOT, " · placed at x %.2f / y %.2f / z %.2f, scale %.4f",
                        transform[0], transform[1], transform[2], transform[3]))
                .append(String.format(Locale.ROOT, " · hit x %.1f / y %.1f / z %.1f, %.0f yd away",
                        hit.x(), hit.y(), hit.z(), hit.distance()))
                .append(" · ").append(material(hit.material())).append(" surface");
        return text.append(" · BASIC static draw; lighting, animation and interiors are not evaluated").toString();
    }

    private static void describeExterior(StringBuilder text, Exterior e, WorldObjectPicking.Hit hit,
            LongFunction<String> names) {
        long mesh = hit.mesh() < e.meshIds().size() ? e.meshIds().get(hit.mesh()) : 0;
        switch (e.kind()) {
            case BUILDING -> {
                text.append("Building · WMO FileDataID ").append(e.fileId()).append(' ').append(name(names, e.fileId()))
                        .append(" · group ");
                if (hit.mesh() < e.meshOrdinals().size()) text.append(e.meshOrdinals().get(hit.mesh()));
                else text.append("unknown");
                text.append(" (FileDataID ").append(mesh).append(") · MODF record ").append(e.ordinal())
                        .append(" · unique ID ").append(e.uniqueId()).append(" · doodad set ").append(e.doodadSet())
                        .append(" · name set ").append(e.nameSet())
                        .append(String.format(Locale.ROOT, " · flags 0x%x", e.flags()));
            }
            case DOODAD -> text.append("Doodad · M2 FileDataID ").append(e.fileId()).append(' ')
                    .append(name(names, e.fileId())).append(" · SKIN FileDataID ").append(mesh)
                    .append(" · MDDF record ").append(e.ordinal()).append(" · unique ID ").append(e.uniqueId())
                    .append(String.format(Locale.ROOT, " · flags 0x%x", e.flags()));
            case SET_DOODAD -> {
                text.append("WMO set doodad · M2 FileDataID ").append(e.fileId()).append(' ')
                        .append(name(names, e.fileId())).append(" · SKIN FileDataID ").append(mesh)
                        .append(" · MODD entry ").append(e.ordinal()).append(" of doodad set ").append(e.doodadSet())
                        .append(" · building ");
                if (e.parentFileId() > 0) text.append("WMO FileDataID ").append(e.parentFileId()).append(' ')
                        .append(name(names, e.parentFileId()));
                else text.append("WMO unknown");
                text.append(", unique ID ").append(e.uniqueId())
                        .append(String.format(Locale.ROOT, " · MODD flags 0x%x", e.flags()));
            }
        }
    }

    private static void describeCreature(StringBuilder text, Creature c, LongFunction<String> names) {
        var npc = c.npc();
        var spawn = c.spawn();
        text.append("Creature model · ");
        if (npc == null) {
            text.append("NPC ").append(spawn.npcId()).append(" · name unknown (no NPC record in this snapshot)");
        } else {
            text.append(npc.name() == null ? "name unknown" : npc.name());
            if (npc.subname() != null && !npc.subname().isBlank()) text.append(" <").append(npc.subname()).append('>');
            text.append(" · NPC ID ").append(npc.id());
        }
        var choice = c.choice();
        text.append(" · display ID ").append(choice.displayId() == null ? "unknown" : choice.displayId())
                .append(" → CreatureModelData ").append(choice.modelId() == null ? "unknown" : choice.modelId())
                .append(" · model FileDataID ");
        if (choice.modelFileDataId() == null) text.append("unknown");
        else text.append(choice.modelFileDataId()).append(' ').append(name(names, choice.modelFileDataId()));
        // Where the display came from: the client record or an outside source, and how it was chosen.
        text.append(" · display choice: ").append(choice.detail());
        var evidence = spawn.evidence();
        text.append(" · spawn ").append(evidence.dataset()).append(" record ").append(evidence.record())
                .append(" · static rest pose facing north (facing is not recorded)");
    }

    private static String name(LongFunction<String> names, long fileId) {
        String path;
        try {
            path = names.apply(fileId);
        } catch (RuntimeException unavailable) {
            path = null;
        }
        return path == null || path.isBlank() ? "(name unknown)" : path;
    }

    static String material(int kind) {
        return switch (kind) {
            case WorldObjectPicking.OPAQUE -> "opaque";
            case WorldObjectPicking.CUTOUT -> "cutout (alpha-tested)";
            case WorldObjectPicking.BLENDED -> "blended";
            case WorldObjectPicking.ADDITIVE -> "additive";
            default -> "unknown";
        };
    }
}
