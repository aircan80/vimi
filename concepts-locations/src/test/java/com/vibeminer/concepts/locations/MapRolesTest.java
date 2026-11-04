package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.questie.QuestieTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MapRolesTest {
    private static Map<String, Object> npc(long id, Long flags, List<Object> starts) {
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("name", "NPC " + id);
        if (flags != null) {
            row.put("npcFlags", flags);
        }
        if (starts != null) {
            row.put("questStarts", starts);
        }
        row.put("friendlyToFaction", id % 2 == 0 ? "A" : "H");
        row.put("spawns", Map.of("14", List.of(List.of(42.5, 68.25))));
        return row;
    }

    private static MapRoles.Catalog catalog(
            QuestieTypes.Flavor flavor, String tag, List<Map<String, Object>> rows) {
        return MapRoles.fromRows(
                LocationAdaptersTest.QUESTIE, flavor, tag, rows, List.of(), Cancellation.NONE);
    }

    @Test
    void foreverAndVanillaUseTheReviewedLowBitLayout() {
        var rows =
                List.of(
                        npc(2, 4L, null),
                        npc(3, 16L, null),
                        npc(4, 8L, null),
                        npc(5, 2L | 1L, null),
                        npc(6, 128L, null),
                        npc(7, 0L, List.of(788L)));
        for (var flavor : List.of(QuestieTypes.Flavor.FOREVER, QuestieTypes.Flavor.VANILLA)) {
            var result = catalog(flavor, MapRoles.REVIEWED_RELEASE, rows);
            assertEquals(LocationTypes.State.READY, result.state());
            var byId = new HashMap<Long, Set<MapRoles.Layer>>();
            result.npcs().forEach(n -> byId.put(n.id(), n.layers()));
            assertEquals(Set.of(MapRoles.Layer.VENDOR), byId.get(2L));
            assertEquals(Set.of(MapRoles.Layer.TRAINER), byId.get(3L));
            assertEquals(Set.of(MapRoles.Layer.FLIGHT_MASTER), byId.get(4L));
            assertEquals(
                    Set.of(MapRoles.Layer.QUEST_GIVER),
                    byId.get(7L),
                    "Quest givers come from explicit questStarts");
            assertTrue(
                    !byId.containsKey(5L),
                    "The QUEST_GIVER flag alone is not an explicit starter relationship");
            assertTrue(!byId.containsKey(6L), "Bit 128 is INNKEEPER in this layout, not vendor");
        }
    }

    @Test
    void laterFlavorsUseTheirOwnLayout() {
        var result =
                catalog(
                        QuestieTypes.Flavor.TBC,
                        MapRoles.REVIEWED_RELEASE,
                        List.of(npc(2, 128L, null), npc(3, 8192L, null), npc(4, 4L | 8L, null)));
        var byId = new HashMap<Long, Set<MapRoles.Layer>>();
        result.npcs().forEach(n -> byId.put(n.id(), n.layers()));
        assertEquals(Set.of(MapRoles.Layer.VENDOR), byId.get(2L));
        assertEquals(Set.of(MapRoles.Layer.FLIGHT_MASTER), byId.get(3L));
        assertTrue(!byId.containsKey(4L));
    }

    @Test
    void locationsStayAreaPercentWithProvenanceAndFriendlyFaction() {
        var result = catalog(QuestieTypes.Flavor.FOREVER, "v1.0.10", List.of(npc(2, 4L, null)));
        var npc = result.npcs().getFirst();
        assertEquals("NPC 2", npc.name());
        assertEquals(MapRoles.Friendly.ALLIANCE, npc.friendly());
        assertEquals(1, result.locations());
        var coordinate = npc.locations().getFirst().coordinates().getFirst();
        assertEquals(LocationTypes.MapKind.AREA, coordinate.map().kind());
        assertEquals(14, coordinate.map().id());
        assertEquals(LocationTypes.Units.PERCENT, coordinate.units());
        assertEquals(42.5, coordinate.x());
        assertEquals("questStarts,npcFlags", npc.evidence().field());
    }

    @Test
    void otherReleaseTagsAndMalformedRowsArePartialNotSilent() {
        var bad = npc(9, null, null);
        bad.put("npcFlags", "vendor");
        var noSpawns = npc(10, 4L, null);
        noSpawns.remove("spawns");
        var result =
                catalog(
                        QuestieTypes.Flavor.FOREVER,
                        "v1.1.0",
                        List.of(npc(2, 4L, null), bad, Map.of("id", "x"), noSpawns));
        assertEquals(LocationTypes.State.PARTIAL, result.state());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("reviewed at QuestieDB")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("npcFlags")));
        assertEquals(2, result.npcs().size(), "A role without recorded spawns stays listed");
        assertEquals(1, result.locations());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MapRoles.fromRows(
                                LocationAdaptersTest.POLESTAR,
                                QuestieTypes.Flavor.FOREVER,
                                "v1.0.10",
                                List.of(),
                                List.of(),
                                Cancellation.NONE));
    }

    private static MapTypes.Candidate candidate(long uiMap, Double x, Double y) {
        return new MapTypes.Candidate(
                new MapTypes.MapInfo(uiMap, "Map " + uiMap, null, 3, null),
                x,
                y,
                7L,
                MapTypes.Basis.AREA_ASSIGNMENT,
                List.of(),
                null);
    }

    private static MapTypes.Resolution resolved(
            LocationTypes.Location location, List<MapTypes.Candidate> candidates) {
        boolean none = candidates.stream().allMatch(c -> c.x() == null);
        return new MapTypes.Resolution(
                none ? MapTypes.State.UNAVAILABLE : MapTypes.State.READY,
                null,
                "enUS",
                location,
                candidates,
                List.of(),
                none ? "No reviewed selected-build map relationship." : null);
    }

    @Test
    void placementDrawsOnlyAgreeingCandidatesAndCountsTheRest() {
        var second = npc(3, 0L, List.of(5L));
        second.put(
                "spawns",
                Map.of("14", List.of(List.of(10.0, 20.0)), "15", List.of(List.of(1.0, 2.0))));
        var catalog =
                catalog(
                        QuestieTypes.Flavor.FOREVER,
                        MapRoles.REVIEWED_RELEASE,
                        List.of(npc(2, 4L, null), second));
        var locations = MapRoles.locations(catalog);
        assertEquals(3, locations.size());
        var placement =
                MapRoles.place(
                        catalog,
                        List.of(
                                resolved(locations.get(0), List.of(candidate(1411, .425, .6825))),
                                resolved(
                                        locations.get(1),
                                        List.of(
                                                candidate(1411, .1, .2),
                                                candidate(1411, .3, .2),
                                                candidate(1412, .5, .5))),
                                resolved(locations.get(2), List.of(candidate(1411, null, null)))));
        assertEquals(3, placement.locations());
        assertEquals(1, placement.ambiguous(), "Disagreeing assignments choose no position");
        assertEquals(1, placement.unplaced(), "No drawable candidate leaves the location unplaced");
        assertTrue(placement.issues().stream().anyMatch(i -> i.contains("No reviewed")));
        var all = Set.of(MapRoles.Layer.values());
        assertEquals(1, placement.markers(1411, all).size());
        assertEquals(0.425, placement.markers(1411, all).getFirst().x());
        assertEquals(3L, placement.markers(1412, all).getFirst().npc().id());
        assertEquals(1, placement.markers(1412, Set.of(MapRoles.Layer.QUEST_GIVER)).size());
        assertTrue(placement.markers(1412, Set.of(MapRoles.Layer.VENDOR)).isEmpty());
        assertTrue(placement.markers(1, all).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MapRoles.place(catalog, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MapRoles.place(
                                catalog,
                                List.of(
                                        resolved(locations.get(1), List.of()),
                                        resolved(locations.get(0), List.of()),
                                        resolved(locations.get(2), List.of()))));
    }

    @Test
    void outOfFrameAndNonQuestStartEntriesAreReportedNotDrawn() {
        var odd = npc(4, 0L, java.util.Arrays.asList(0L, "x", 1.5));
        var catalog =
                catalog(
                        QuestieTypes.Flavor.FOREVER,
                        MapRoles.REVIEWED_RELEASE,
                        List.of(npc(2, 4L, null), odd));
        assertTrue(catalog.npcs().stream().noneMatch(n -> n.id() == 4L));
        assertTrue(catalog.issues().stream().anyMatch(i -> i.contains("not quest IDs")));
        var location = MapRoles.locations(catalog).getFirst();
        var placement =
                MapRoles.place(
                        catalog, List.of(resolved(location, List.of(candidate(1411, -.01, -.01)))));
        assertTrue(placement.markers(1411, Set.of(MapRoles.Layer.values())).isEmpty());
        assertEquals(1, placement.unplaced());
        assertTrue(placement.issues().stream().anyMatch(i -> i.contains("outside the map frame")));
    }
}
