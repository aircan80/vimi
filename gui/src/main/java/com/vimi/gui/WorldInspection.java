package com.vimi.gui;

import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.concepts.world.WorldCurrentArea;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * What the inspect card shows for one pick: a type chip, a title, a subtitle, a few short lines,
 * the game file path when one is known, and the full inspection text (the same text the explorer
 * has always shown, kept for Copy). Built from the same records the inspection text is built from;
 * unknown facts say so. No JavaFX.
 */
final class WorldInspection {
    private WorldInspection() {}

    /** The card's type chip. */
    enum Kind {
        TERRAIN("Terrain"),
        BUILDING("Building"),
        DOODAD("Doodad"),
        CREATURE("Creature model"),
        SPAWN("NPC spawn"),
        MARKER("Minimap NPC"),
        ENTRANCE("Entrance"),
        OBJECT("Object"),
        NOTE("Inspect");

        final String chip;

        Kind(String chip) {
            this.chip = chip;
        }
    }

    /**
     * One card. {@code file} is the full game path or null; {@code detail} is the complete
     * inspection text; {@code npcId} is the NPC for spawns and creature models, else 0.
     */
    record Content(Kind kind, String title, String subtitle, List<String> lines, String file,
            String detail, long npcId) {
        Content {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(title);
            subtitle = subtitle == null ? "" : subtitle;
            lines = List.copyOf(lines);
            Objects.requireNonNull(detail);
        }
    }

    /** A clicked minimap marker: a role NPC or an instance entrance, with its hover text. */
    static Content marker(WorldMinimap.Clicked clicked) {
        if (clicked.entrance() != null) {
            var link = clicked.entrance();
            var at = link.entrance();
            var lines = new ArrayList<String>();
            lines.add("leads to map #" + link.mapId());
            if (at != null) lines.add(WorldPick.tile(at.x(), at.y())
                    + String.format(Locale.ROOT, " · x %.0f · y %.0f", at.x(), at.y()));
            return new Content(Kind.ENTRANCE, link.name(),
                    (link.kind() == com.vibeminer.concepts.world.WorldInstanceLinks.Kind.RAID ? "raid" : "dungeon")
                            + " entrance on map " + (at == null ? "unknown" : at.mapId()), lines, null,
                    clicked.description(), 0);
        }
        var npc = clicked.role().npc();
        var lines = new ArrayList<String>();
        var roles = npc.layers().stream().sorted().map(MapRoleOverlay::label).toList();
        if (!roles.isEmpty()) lines.add("roles " + String.join(", ", roles));
        String faction = switch (npc.friendly()) {
            case ALLIANCE -> "Alliance";
            case HORDE -> "Horde";
            case BOTH -> "Alliance and Horde";
            case UNKNOWN -> null;
        };
        if (faction != null) lines.add("faction " + faction);
        return new Content(Kind.MARKER, npc.name() == null || npc.name().isBlank() ? "NPC " + npc.id() : npc.name(),
                (npc.subname() == null || npc.subname().isBlank() ? "" : "<" + npc.subname() + "> · ")
                        + "NPC ID " + npc.id() + " · minimap role marker",
                lines, null, clicked.description() == null ? "NPC " + npc.id() : clicked.description(), npc.id());
    }

    /** A message rather than a pick (nothing hit, not ready, failed). */
    static Content note(String text) {
        return new Content(Kind.NOTE, text, "", List.of(), null, text, 0);
    }

    static Content terrain(WorldPick.Hit hit, long mapId, String mapName, WorldCurrentArea.Result area,
            int objectTiles) {
        String title = area.state() == WorldCurrentArea.State.KNOWN
                ? area.label().replaceFirst("^Terrain area: ", "") : "Terrain";
        var lines = List.of(
                WorldPick.tile(hit.x(), hit.y()) + String.format(Locale.ROOT, " · x %.0f · y %.0f", hit.x(), hit.y()),
                String.format(Locale.ROOT, "alt %.0f · %.0f yd away", hit.z(), hit.distance()));
        String subtitle = area.state() == WorldCurrentArea.State.KNOWN
                ? "rendered ADT surface" : WorldPick.area(area);
        return new Content(Kind.TERRAIN, title, subtitle, lines, null,
                WorldPick.describeTerrain(hit, mapId, mapName, area, objectTiles), 0);
    }

    static Content object(WorldObjectPicks.Selected selected, long mapId, String mapName,
            LongFunction<String> names) {
        var hit = selected.hit();
        var transform = selected.tile().geometry().transform(hit.placement());
        var lines = new ArrayList<String>();
        lines.add(WorldPick.tile(transform[0], transform[1])
                + String.format(Locale.ROOT, " · x %.0f · y %.0f", transform[0], transform[1]));
        lines.add(String.format(Locale.ROOT, "alt %.0f · %.0f yd away · %s surface", transform[2],
                hit.distance(), WorldObjectPicks.material(hit.material())));
        String detail = WorldObjectPicks.describe(selected, mapId, mapName, names);
        return switch (selected.source()) {
            case WorldObjectPicks.Exterior e -> {
                String file = path(names, e.fileId());
                Kind kind = e.kind() == com.vibeminer.concepts.world.WorldExteriorObjects.Kind.BUILDING
                        ? Kind.BUILDING : Kind.DOODAD;
                String subtitle = switch (e.kind()) {
                    case BUILDING -> "exterior building · BASIC";
                    case DOODAD -> "ADT doodad · BASIC";
                    case SET_DOODAD -> "WMO set doodad · BASIC" + (e.parentFileId() > 0
                            ? " · in " + base(path(names, e.parentFileId()), "WMO " + e.parentFileId()) : "");
                };
                lines.add((kind == Kind.BUILDING ? "WMO " : "M2 ") + "FileDataID " + e.fileId()
                        + " · unique ID " + e.uniqueId());
                yield new Content(kind, base(file, (kind == Kind.BUILDING ? "WMO " : "M2 ") + e.fileId()),
                        subtitle, lines, file, detail, 0);
            }
            case WorldObjectPicks.Creature c -> {
                var choice = c.choice();
                String file = choice.modelFileDataId() == null ? null : path(names, choice.modelFileDataId());
                lines.add("display " + (choice.displayId() == null ? "unknown" : choice.displayId())
                        + " · static rest pose");
                yield new Content(Kind.CREATURE, npcName(c.npc(), c.spawn().npcId()),
                        npcSubtitle(c.npc()), lines, file, detail, c.spawn().npcId());
            }
            case WorldObjectPicks.Unknown u -> new Content(Kind.OBJECT, "Drawn object",
                    "source record unknown", lines, null, detail, 0);
        };
    }

    /**
     * A declared NPC spawn. {@code roles} names the minimap role layers that list this NPC and
     * {@code faction} its recorded friendliness, both from the roles layer (null when unknown);
     * {@code spawnsOnMap} counts this NPC's declared points on the map (negative when unknown).
     */
    static Content spawn(WorldSpawns.Spawn spawn, WorldSpawns.Npc npc, String mapName,
            WorldSpawnModels.Resolved model, boolean resolving, List<String> roles, String faction,
            int spawnsOnMap) {
        var lines = new ArrayList<String>();
        if (npc != null) lines.add("level " + WorldPick.levels(npc)
                + (npc.rank() == null ? "" : " · rank " + npc.rank()));
        if (faction != null) lines.add("faction " + faction);
        if (roles != null && !roles.isEmpty()) lines.add("roles " + String.join(", ", roles));
        lines.add(WorldPick.tile(spawn.x(), spawn.y())
                + String.format(Locale.ROOT, " · x %.0f · y %.0f", spawn.x(), spawn.y())
                + (spawn.z() == null ? " · height not recorded" : String.format(Locale.ROOT, " · alt %.0f", spawn.z())));
        if (spawnsOnMap >= 0) lines.add(spawnsOnMap + (spawnsOnMap == 1 ? " spawn" : " spawns") + " on this map");
        String file = model != null && model.choice().state()
                == com.vibeminer.concepts.creatures.CreatureModelPolicy.State.QUALIFIED ? model.modelPath() : null;
        if (file != null && file.isBlank()) file = null;
        return new Content(Kind.SPAWN, npcName(npc, spawn.npcId()), npcSubtitle(npc), lines, file,
                WorldPick.describeSpawn(spawn, npc, mapName, model, resolving), spawn.npcId());
    }

    private static String npcName(WorldSpawns.Npc npc, long id) {
        return npc == null || npc.name() == null || npc.name().isBlank() ? "NPC " + id : npc.name();
    }

    private static String npcSubtitle(WorldSpawns.Npc npc) {
        if (npc == null) return "name unknown (no NPC record in this snapshot)";
        return (npc.subname() == null || npc.subname().isBlank() ? "" : "<" + npc.subname() + "> · ")
                + "NPC ID " + npc.id();
    }

    private static String path(LongFunction<String> names, long fileId) {
        try {
            var path = names.apply(fileId);
            return path == null || path.isBlank() ? null : path;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The file name without folders, or {@code fallback} when there is no path. */
    static String base(String path, String fallback) {
        if (path == null) return fallback;
        int at = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return at < 0 ? path : path.substring(at + 1);
    }
}
