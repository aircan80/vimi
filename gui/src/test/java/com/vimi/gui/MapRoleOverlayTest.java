package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.MapRoles;
import com.vibeminer.concepts.locations.MapTypes;
import com.vibeminer.questie.QuestieTypes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Headless role-overlay disclosure checks; no JavaFX toolkit or game data required. */
public final class MapRoleOverlayTest {
    static final LocationTypes.Source SOURCE = new LocationTypes.Source(
            LocationTypes.Provider.QUESTIE, "questie-forever", "Forever", null, "enUS",
            "See retained QuestieDB source notices");

    private MapRoleOverlayTest() {}

    public static void main(String[] args) {
        summaryCountsEnabledLayersOnTheMap();
        multiRoleColorFollowsEnabledLayers();
        factionFilterKeepsBothFactionNpcsAndDisclosesUnrecorded();
        hoverFindsTheNearestDrawnMarkersInNearbyCells();
        hoverDetailsListRecordedFactsOnly();
        System.out.println("Map role overlay: summary, colors, faction filter and hover details passed");
    }

    static Map<String, Object> npc(long id, long flags, List<Object> starts, double x, double y) {
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("name", "NPC " + id);
        row.put("npcFlags", flags);
        if (starts != null) row.put("questStarts", starts);
        row.put("spawns", Map.of("12", List.of(List.of(x, y))));
        return row;
    }

    /** Places every recorded location on {@code uiMap} at its percent position. */
    static MapRoles.Placement placement(String release, long uiMap, List<Map<String, Object>> rows) {
        var catalog = MapRoles.fromRows(SOURCE, QuestieTypes.Flavor.FOREVER, release, rows, List.of(),
                Cancellation.NONE);
        var resolutions = new ArrayList<MapTypes.Resolution>();
        for (var location : MapRoles.locations(catalog)) {
            var at = location.coordinates().getFirst();
            resolutions.add(new MapTypes.Resolution(MapTypes.State.READY, null, "enUS", location,
                    List.of(new MapTypes.Candidate(new MapTypes.MapInfo(uiMap, "Authored", null, 3, null),
                            at.x() / 100, at.y() / 100, 1L, MapTypes.Basis.AREA_ASSIGNMENT, List.of(), null)),
                    List.of(), null));
        }
        return MapRoles.place(catalog, resolutions);
    }

    static void summaryCountsEnabledLayersOnTheMap() {
        var placement = placement("v1.0.10", 1429, List.of(
                npc(2, 4, null, 25, 25), npc(3, 4 | 16, null, 50, 50), npc(4, 0, List.of(7L), 75, 75)));
        var layers = EnumSet.of(MapRoles.Layer.VENDOR, MapRoles.Layer.TRAINER);
        var markers = placement.markers(1429, layers);
        require(markers.size() == 2, "quest-only NPC filtered out");
        var text = MapRoleOverlay.summary(placement, 1429L, layers, markers);
        require(text.contains("QuestieDB v1.0.10 Forever"), text);
        require(text.contains("2 vendors, 1 trainers"), text);
        require(text.contains("3 role NPCs, 3 recorded positions"), text);
        require(text.endsWith("faction, phase and quest availability not evaluated"), text);
        require(!text.contains("not placed"), text);
        var none = MapRoleOverlay.summary(placement, null, layers, List.of());
        require(!none.contains("on this map"), none);
        var partial = placement("v9.9.9", 1429, List.of(npc(2, 4, null, 25, 25)));
        var other = MapRoleOverlay.summary(partial, 1429L, layers, partial.markers(1429, layers));
        require(other.contains("partial: NPC flag layout was reviewed at QuestieDB v1.0.10"), other);
    }

    static void multiRoleColorFollowsEnabledLayers() {
        var both = Set.of(MapRoles.Layer.VENDOR, MapRoles.Layer.TRAINER);
        require(MapRoleOverlay.primary(both, EnumSet.allOf(MapRoles.Layer.class)) == MapRoles.Layer.VENDOR,
                "declaration order");
        require(MapRoleOverlay.primary(both, EnumSet.of(MapRoles.Layer.TRAINER)) == MapRoles.Layer.TRAINER,
                "enabled layer wins");
        for (var layer : MapRoles.Layer.values())
            for (var other : MapRoles.Layer.values())
                require(layer == other || !MapRoleOverlay.color(layer).equals(MapRoleOverlay.color(other)),
                        "distinct layer colors");
    }

    static Map<String, Object> factionNpc(long id, long flags, String friendly, double x, double y) {
        var row = npc(id, flags, (List<Object>) null, x, y);
        if (friendly != null) row.put("friendlyToFaction", friendly);
        return row;
    }

    static void factionFilterKeepsBothFactionNpcsAndDisclosesUnrecorded() {
        var f = MapRoleOverlay.Faction.values();
        for (var friendly : MapRoles.Friendly.values()) {
            require(MapRoleOverlay.visible(friendly, MapRoleOverlay.Faction.ALL), "all shows " + friendly);
        }
        require(MapRoleOverlay.visible(MapRoles.Friendly.ALLIANCE, MapRoleOverlay.Faction.ALLIANCE), "A");
        require(MapRoleOverlay.visible(MapRoles.Friendly.BOTH, MapRoleOverlay.Faction.ALLIANCE), "AH for A");
        require(!MapRoleOverlay.visible(MapRoles.Friendly.HORDE, MapRoleOverlay.Faction.ALLIANCE), "H not A");
        require(!MapRoleOverlay.visible(MapRoles.Friendly.UNKNOWN, MapRoleOverlay.Faction.ALLIANCE), "unknown not A");
        require(MapRoleOverlay.visible(MapRoles.Friendly.HORDE, MapRoleOverlay.Faction.HORDE), "H");
        require(MapRoleOverlay.visible(MapRoles.Friendly.BOTH, MapRoleOverlay.Faction.HORDE), "AH for H");
        require(!MapRoleOverlay.visible(MapRoles.Friendly.ALLIANCE, MapRoleOverlay.Faction.HORDE), "A not H");
        require(!MapRoleOverlay.visible(MapRoles.Friendly.UNKNOWN, MapRoleOverlay.Faction.HORDE), "unknown not H");
        require(f.length == 3, "three choices");
        var placement = placement("v1.0.10", 1429, List.of(
                factionNpc(2, 4, "A", 25, 25), factionNpc(3, 4, "H", 50, 50), factionNpc(4, 4, "AH", 60, 60), factionNpc(5, 4, null, 75, 75)));
        var layers = EnumSet.of(MapRoles.Layer.VENDOR);
        var shown = placement.markers(1429, layers).stream()
                .filter(m -> MapRoleOverlay.visible(m.npc().friendly(), MapRoleOverlay.Faction.ALLIANCE)).toList();
        require(shown.size() == 2, "Alliance and both");
        var text = MapRoleOverlay.summary(placement, 1429L, layers, shown, MapRoleOverlay.Faction.ALLIANCE, 1, 1);
        require(text.contains("positions on this map (Alliance-friendly): 2 vendors"), text);
        require(text.contains("hidden by the faction filter: 1 position of the other faction,"
                + " 1 position with no recognised faction"), text);
        require(text.contains("4 recorded positions (all factions)"), text);
        require(text.endsWith("faction from Questie's record, not the player's reputation;"
                + " phase and quest availability not evaluated"), text);
        var plural = MapRoleOverlay.summary(placement, 1429L, layers, shown, MapRoleOverlay.Faction.ALLIANCE, 0, 2);
        require(plural.contains("hidden by the faction filter: 2 positions with no recognised faction")
                && !plural.contains("other faction"), plural);
        var all = MapRoleOverlay.summary(placement, 1429L, layers, shown);
        require(!all.contains("hidden by") && !all.contains("Alliance-friendly")
                && !all.contains("(all factions)"), all);
    }

    static void hoverFindsTheNearestDrawnMarkersInNearbyCells() {
        var placement = placement("v1.0.10", 1429, List.of(
                factionNpc(2, 4, null, 25, 25), factionNpc(3, 4, null, 50, 50), factionNpc(4, 4, null, 75, 75)));
        var markers = placement.markers(1429, EnumSet.of(MapRoles.Layer.VENDOR));
        var hits = new ArrayList<MapRoleOverlay.Hit>();
        // Canvas positions straddling a cell boundary (CELL = 16): 15.0 and 19.0 are in different cells.
        hits.add(new MapRoleOverlay.Hit(markers.get(0), 15, 15));
        hits.add(new MapRoleOverlay.Hit(markers.get(1), 19, 15));
        hits.add(new MapRoleOverlay.Hit(markers.get(2), 200, 200));
        hits.add(new MapRoleOverlay.Hit(markers.get(1), 19.5, 15)); // same NPC listed once
        var grid = new MapRoleOverlay.HitGrid(hits);
        var near = grid.near(18, 15).markers();
        require(near.size() == 2, "two NPCs within reach across cells: " + near.size());
        require(near.get(0).npc().id() == 3 && near.get(1).npc().id() == 2, "nearest first");
        require(grid.near(100, 100).markers().isEmpty(), "nothing in reach");
        require(grid.near(200 + MapRoleOverlay.RADIUS + 0.5, 200).markers().isEmpty(), "outside radius");
        require(grid.near(200 + MapRoleOverlay.RADIUS, 200).markers().size() == 1, "radius inclusive");
        require(MapRoleOverlay.HitGrid.EMPTY.near(0, 0).markers().isEmpty(), "empty grid");
        require(!grid.near(18, 15).more(), "two NPCs are not truncated");
        var crowd = new ArrayList<Map<String, Object>>();
        for (long id = 10; id < 20; id++) crowd.add(factionNpc(id, 4, null, 40, 40));
        var many = placement("v1.0.10", 1429, crowd).markers(1429, EnumSet.of(MapRoles.Layer.VENDOR));
        var stacked = new ArrayList<MapRoleOverlay.Hit>();
        for (var marker : many) stacked.add(new MapRoleOverlay.Hit(marker, 40, 40));
        var crowded = new MapRoleOverlay.HitGrid(stacked).near(40, 40);
        require(crowded.markers().size() == MapRoleOverlay.HOVER_LIMIT && crowded.more(),
                "hover lists at most the limit and says more are in reach");
        var exact = new MapRoleOverlay.HitGrid(stacked.subList(0, MapRoleOverlay.HOVER_LIMIT)).near(40, 40);
        require(exact.markers().size() == MapRoleOverlay.HOVER_LIMIT && !exact.more(),
                "exactly the limit is not reported as more");
    }

    static void hoverDetailsListRecordedFactsOnly() {
        var row = factionNpc(2, 4 | 16, "AH", 42.5, 68.25);
        row.put("subName", "Weapon Merchant");
        var placement = placement("v1.0.10", 1429, List.of(row, factionNpc(3, 8, null, 10, 10)));
        var markers = placement.markers(1429, EnumSet.allOf(MapRoles.Layer.class));
        var text = MapRoleOverlay.details(markers, false, placement.catalog());
        require(!text.contains("More role NPCs"), text);
        require(MapRoleOverlay.details(markers, true, placement.catalog()).contains("More role NPCs are in reach"),
                "truncation is disclosed");
        require(text.startsWith("NPC 2 <Weapon Merchant>\nNPC 2 · Vendor, Trainer"), text);
        require(text.contains("Faction: Alliance and Horde"), text);
        require(text.contains("Recorded at AreaID 12, 42.5, 68.3 %"), text);
        require(text.contains("NPC 3 · Flight master") && text.contains("Faction: not recorded or unrecognised"), text);
        require(text.endsWith("Source: QuestieDB v1.0.10 Forever"), text);
        require(!text.contains(":\\") && !text.contains("/Users/") && !text.contains("\\Users\\"),
                "no local paths: " + text);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
