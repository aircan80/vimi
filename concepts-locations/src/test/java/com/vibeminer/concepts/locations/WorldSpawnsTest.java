package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class WorldSpawnsTest {
    private static final LocationTypes.Source SOURCE =
            new LocationTypes.Source(
                    LocationTypes.Provider.POLESTAR,
                    "a".repeat(40),
                    "Forever",
                    "1.60.1.70532",
                    "enUS",
                    "GPL-3.0-only");

    private static Map<String, Object> spawn(
            String kind, long id, long map, long n, double x, double y, Double z) {
        var row = new HashMap<String, Object>();
        row.put("kind", kind);
        row.put("id", id);
        row.put("map", map);
        row.put("n", n);
        row.put("x", x);
        row.put("y", y);
        if (z != null) {
            row.put("z", z);
        }
        return row;
    }

    private static WorldSpawns.Index index(List<Map<String, Object>> spawns) {
        return WorldSpawns.polestar(
                SOURCE,
                spawns,
                List.of(
                        Map.of(
                                "id", 3143L,
                                "name", "Gornek",
                                "level_min", 10L,
                                "level_max", 10L,
                                "rank", 0L),
                        Map.of("id", 7L)),
                List.of(),
                Cancellation.NONE);
    }

    @Test
    void recordedWorldYardsStayOnTheirClientMapWithHeightProvenance() {
        var index =
                index(
                        List.of(
                                spawn("npc", 3143, 1, 1, -600.5, -4186.5, 41.3),
                                spawn("npc", 3143, 0, 1, -600.5, -4186.5, 12.0),
                                spawn("npc", 7, 1, 1, -610, -4186.5, null),
                                spawn("object", 9, 1, 1, -600, -4186, 40.0)));
        assertEquals(LocationTypes.State.READY, index.state());
        assertEquals(3, index.spawns());
        assertEquals(1, index.heightless());
        assertEquals(2, index.count(1));
        assertEquals(1, index.count(0));
        var near = index.near(1, -600, -4186, 50, 10);
        assertEquals(1, near.placed().size());
        var placed = near.placed().getFirst();
        assertEquals(3143, placed.npcId());
        assertEquals(-600.5, placed.x());
        assertEquals(-4186.5, placed.y());
        assertEquals(41.3, placed.z());
        assertEquals(WorldSpawns.Height.RECORDED, placed.height());
        assertEquals("npc:3143:1:1", placed.evidence().record());
        assertEquals("1.60.1.70532", placed.evidence().source().build());
        assertEquals(1, near.heightless(), "Height-less points are disclosed, not placed");
        assertEquals("Gornek", index.npc(3143).orElseThrow().name());
        assertEquals(null, index.npc(7).orElseThrow().name());
        assertTrue(index.npc(9).isEmpty(), "Game objects are not NPC placements");
        assertTrue(index.near(2, -600, -4186, 50, 10).placed().isEmpty());
    }

    @Test
    void nearbyIsHorizontalNearestFirstStableAndBoundedAcrossCells() {
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(spawn("npc", 20, 0, 1, 130, 0, 0.0));
        rows.add(spawn("npc", 10, 0, 1, -130, 0, 500.0));
        rows.add(spawn("npc", 30, 0, 1, 0, 255.9, 0.0));
        rows.add(spawn("npc", 40, 0, 1, 0, 1.0, 0.0));
        rows.add(spawn("npc", 50, 0, 1, 0, 400, 0.0));
        var index = index(rows);
        var near = index.near(0, 0, 0, 300, 3);
        assertEquals(List.of(40L, 10L, 20L), near.placed().stream().map(s -> s.npcId()).toList());
        assertEquals(1, near.omitted());
        assertEquals(0, near.heightless());
        var all = index.near(0, 0, 0, 300, 512);
        assertEquals(4, all.placed().size());
        assertEquals(0, all.omitted());
        assertThrows(IllegalArgumentException.class, () -> index.near(0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> index.near(0, 0, 0, 2_001, 1));
        assertThrows(IllegalArgumentException.class, () -> index.near(0, 0, 0, 10, 513));
        assertThrows(IllegalArgumentException.class, () -> index.near(0, Double.NaN, 0, 10, 1));
    }

    @Test
    void negativeCoordinatesUseFloorCellsWithoutLosingBoundaryPoints() {
        var index =
                index(
                        List.of(
                                spawn("npc", 1, 0, 1, -128.0, -0.01, 1.0),
                                spawn("npc", 2, 0, 1, -127.99, 0.0, 1.0),
                                spawn("npc", 3, 0, 1, -16_000, 16_000, 1.0)));
        assertEquals(2, index.near(0, -128, 0, 1, 10).placed().size());
        assertEquals(1, index.near(0, -16_000, 16_000, 1, 10).placed().size());
    }

    @Test
    void identicalDuplicatesMergeAndConflictingDuplicatesAreOmittedWithDiagnostics() {
        var index =
                index(
                        List.of(
                                spawn("npc", 1, 0, 1, 10, 10, 1.0),
                                spawn("npc", 1, 0, 1, 10, 10, 1.0),
                                spawn("npc", 2, 0, 1, 20, 20, 1.0),
                                spawn("npc", 2, 0, 1, 21, 20, 1.0),
                                spawn("npc", 2, 0, 1, 20, 20, 1.0)));
        assertEquals(LocationTypes.State.PARTIAL, index.state());
        assertEquals(1, index.spawns());
        assertEquals(
                List.of(1L),
                index.near(0, 15, 15, 50, 10).placed().stream().map(s -> s.npcId()).toList());
        assertTrue(index.issues().stream().anyMatch(i -> i.contains("Conflicting")));
    }

    @Test
    void malformedOrOutOfGridRowsDoNotHideNeighbours() {
        var bad = new HashMap<String, Object>(spawn("npc", 4, 0, 1, 1, 1, 1.0));
        bad.put("x", "1");
        var badHeight = new HashMap<String, Object>(spawn("npc", 5, 0, 1, 2, 2, null));
        badHeight.put("z", "high");
        var index =
                index(
                        List.of(
                                spawn("npc", 1, 0, 1, 0, 0, 1.0),
                                bad,
                                badHeight,
                                spawn("npc", 6, 0, 1, 40_000, 0, 1.0),
                                spawn("npc", 7, 0, 1, 3, 3, 1e9),
                                spawn("creature", 8, 0, 1, 0, 0, 1.0),
                                spawn("npc", 0, 0, 1, 0, 0, 1.0)));
        assertEquals(LocationTypes.State.PARTIAL, index.state());
        var near = index.near(0, 0, 0, 20, 10);
        assertEquals(List.of(1L), near.placed().stream().map(s -> s.npcId()).toList());
        assertEquals(2, near.heightless(), "Unsupported heights keep planar evidence only");
        assertFalse(index.issues().isEmpty());
        assertTrue(index.issues().size() <= 32);
    }

    @Test
    void readIssuesSurviveAndOnlyPolestarIsAdmitted() {
        var partial =
                WorldSpawns.polestar(
                        SOURCE,
                        List.of(spawn("npc", 1, 0, 1, 0, 0, 1.0)),
                        List.of(),
                        List.of("Spawns: prefix retained"),
                        Cancellation.NONE);
        assertEquals(LocationTypes.State.PARTIAL, partial.state());
        assertEquals(List.of("Spawns: prefix retained"), partial.issues());
        var questie =
                new LocationTypes.Source(
                        LocationTypes.Provider.QUESTIE, "v1", "wowf", "1", "enUS", null);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldSpawns.polestar(
                                questie, List.of(), List.of(), List.of(), Cancellation.NONE));
        var unavailable =
                WorldSpawns.unavailable(LocationTypes.State.UNSUPPORTED, "No world-yard source");
        assertTrue(unavailable.source().isEmpty());
        assertTrue(unavailable.near(0, 0, 0, 10, 10).placed().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldSpawns.unavailable(LocationTypes.State.READY, "x"));
    }

    @Test
    void indexingHonorsCancellation() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 5000; i++) {
            rows.add(spawn("npc", i, 0, 1, i % 100, i / 100.0, 1.0));
        }
        var token = new Cancellation();
        token.cancel();
        assertThrows(
                CancellationException.class,
                () -> WorldSpawns.polestar(SOURCE, rows, List.of(), List.of(), token));
    }
}
