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
        // Each marker keeps the recorded location it was placed from, for hover details.
        assertEquals(locations.get(0), placement.markers(1411, all).getFirst().location());
        assertEquals(locations.get(1), placement.markers(1412, all).getFirst().location());
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

    @Test
    void friendlyToFactionIgnoresCaseAndTreatsOtherValuesAsUnrecognised() {
        assertEquals(MapRoles.Friendly.ALLIANCE, MapRoles.friendly("A"));
        assertEquals(MapRoles.Friendly.ALLIANCE, MapRoles.friendly("a"));
        assertEquals(MapRoles.Friendly.HORDE, MapRoles.friendly(" h "));
        assertEquals(MapRoles.Friendly.BOTH, MapRoles.friendly("ah"));
        assertEquals(MapRoles.Friendly.BOTH, MapRoles.friendly("HA"));
        for (Object other : java.util.Arrays.asList("X", "", "AHX", "Alliance", 1L, null)) {
            assertEquals(
                    MapRoles.Friendly.UNKNOWN, MapRoles.friendly(other), String.valueOf(other));
        }
    }

    private static MapTypes.WorldFrame frame(
            long id,
            long uiMap,
            double uiMinX,
            double uiMinY,
            double uiMaxX,
            double uiMaxY,
            double minX,
            double minY,
            double maxX,
            double maxY) {
        return new MapTypes.WorldFrame(
                id, uiMap, 1, uiMinX, uiMinY, uiMaxX, uiMaxY, minX, minY, maxX, maxY);
    }

    @Test
    void worldFrameInverseRoundTripsWithinTolerance() {
        var random = new java.util.Random(7);
        for (int trial = 0; trial < 200; trial++) {
            double ax = random.nextDouble(), bx = random.nextDouble();
            double ay = random.nextDouble(), by = random.nextDouble();
            if (Math.abs(ax - bx) < 1e-3 || Math.abs(ay - by) < 1e-3) {
                continue;
            }
            double minX = -20000 + random.nextDouble() * 20000,
                    minY = -20000 + random.nextDouble() * 20000;
            var f =
                    frame(
                            trial,
                            1,
                            ax,
                            ay,
                            bx,
                            by,
                            minX,
                            minY,
                            minX + 1 + random.nextDouble() * 9000,
                            minY + 1 + random.nextDouble() * 9000);
            double wx = f.minX() + random.nextDouble() * (f.maxX() - f.minX());
            double wy = f.minY() + random.nextDouble() * (f.maxY() - f.minY());
            var ui = f.toUi(wx, wy);
            var back = f.toWorld(ui[0], ui[1]);
            assertEquals(wx, back[0], 1e-6, "x round trip");
            assertEquals(wy, back[1], 1e-6, "y round trip");
            var again = f.toUi(back[0], back[1]);
            assertEquals(ui[0], again[0], 1e-9);
            assertEquals(ui[1], again[1], 1e-9);
        }
        var flat = frame(1, 1, 0, 0, 1, 1, 10, 10, 10, 20);
        assertTrue(!flat.invertible() && flat.toWorld(.5, .5) == null);
        var collapsedUi = frame(1, 1, .5, 0, .5, 1, 0, 0, 10, 10);
        assertTrue(!collapsedUi.invertible() && collapsedUi.toWorld(.5, .5) == null);
        assertTrue(frame(1, 1, 0, 0, .5, .5, 0, 0, 10, 10).toWorld(.75, .25) == null, "outside UI");
    }

    @Test
    void roleMarkersGetAWorldPointOnlyFromAgreeingRecordedFrames() {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        for (long id = 2; id <= 6; id++) {
            rows.add(npc(id, 4L, null));
        }
        var catalog = catalog(QuestieTypes.Flavor.FOREVER, MapRoles.REVIEWED_RELEASE, rows);
        var locations = MapRoles.locations(catalog);
        var resolutions = new java.util.ArrayList<MapTypes.Resolution>();
        double[][] at = {{.25, .25}, {.75, .75}, {.5, .5}, {.9, .9}, {.4, .4}};
        long[] maps = {10, 10, 11, 12, 13};
        for (int i = 0; i < locations.size(); i++) {
            resolutions.add(
                    resolved(locations.get(i), List.of(candidate(maps[i], at[i][0], at[i][1]))));
        }
        var placement = MapRoles.place(catalog, resolutions);
        var frames =
                new MapTypes.WorldFrames(
                        MapTypes.State.READY,
                        1,
                        List.of(
                                // UiMap 10: one frame covering everything.
                                frame(100, 10, 0, 0, 1, 1, 0, 0, 1000, 1000),
                                // UiMap 11: two frames that disagree on the point.
                                frame(110, 11, 0, 0, 1, 1, 0, 0, 1000, 1000),
                                frame(111, 11, 0, 0, 1, 1, 0, 0, 2000, 2000),
                                // UiMap 12: a frame that does not contain .9,.9.
                                frame(120, 12, 0, 0, .5, .5, 0, 0, 500, 500),
                                // UiMap 13: only a degenerate frame contains the point.
                                frame(130, 13, 0, 0, 1, 1, 0, 0, 0, 1000)),
                        List.of(),
                        null);
        var world = MapRoles.world(placement, frames);
        assertEquals(2, world.markers().size());
        var first = world.markers().getFirst();
        // toUi: ui.x = relY*uiMin.x + (1-relY)*uiMax.x, so ui (.25,.25) is world (750,750).
        assertEquals(750, first.x(), 1e-9);
        assertEquals(750, first.y(), 1e-9);
        assertEquals(100, first.assignmentId());
        assertEquals(1, world.ambiguous());
        assertEquals(1, world.outside());
        assertEquals(1, world.degenerate());
        var otherMap =
                MapRoles.world(
                        placement,
                        new MapTypes.WorldFrames(
                                MapTypes.State.READY, 2, List.of(), List.of(), null));
        assertTrue(
                otherMap.markers().isEmpty()
                        && otherMap.outside() == 0
                        && otherMap.ambiguous() == 0,
                "UiMaps without frames on a map are not counted");
    }
}
