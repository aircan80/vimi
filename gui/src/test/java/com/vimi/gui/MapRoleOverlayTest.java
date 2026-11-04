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
        System.out.println("Map role overlay: summary disclosure and layer colors passed");
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

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
