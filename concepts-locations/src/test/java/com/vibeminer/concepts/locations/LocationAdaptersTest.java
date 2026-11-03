package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LocationAdaptersTest {
    static final LocationTypes.Source QUESTIE =
            new LocationTypes.Source(
                    LocationTypes.Provider.QUESTIE,
                    "q1",
                    "Classic",
                    null,
                    "enUS",
                    "retained notices");
    static final LocationTypes.Source POLESTAR =
            new LocationTypes.Source(
                    LocationTypes.Provider.POLESTAR,
                    "p1",
                    "Forever",
                    "1.60.1.70707",
                    "enUS",
                    "GPL-3.0-only");
    static final LocationTypes.Source EG =
            new LocationTypes.Source(
                    LocationTypes.Provider.EG_LINK, "e1", "wowf", "1.60.1.70532", "", "CC BY 4.0");
    static final LocationTypes.Entity NPC =
            new LocationTypes.Entity(LocationTypes.EntityKind.NPC, 42);
    static final LocationTypes.Entity OBJECT =
            new LocationTypes.Entity(LocationTypes.EntityKind.OBJECT, 42);
    static final LocationTypes.Entity ITEM =
            new LocationTypes.Entity(LocationTypes.EntityKind.ITEM, 42);

    @Test
    void questieKeepsMultipleAreaPercentPositionsAndPhaseIsNotHeight() {
        var result =
                LocationAdapters.questieLocations(
                        NPC,
                        Map.of(
                                "spawns",
                                Map.of(
                                        "12",
                                        List.of(List.of(36.43, 55.89, 7), List.of(40, 60)),
                                        "14",
                                        List.of(List.of(1, 2)))),
                        QUESTIE);
        assertEquals(LocationTypes.State.READY, result.state());
        assertEquals(3, result.locations().size());
        var phase =
                result.locations().stream()
                        .filter(p -> p.phase() != null)
                        .findFirst()
                        .orElseThrow();
        assertEquals(7L, phase.phase());
        var coordinate = phase.coordinates().getFirst();
        assertEquals(LocationTypes.MapKind.AREA, coordinate.map().kind());
        assertEquals(12, coordinate.map().id());
        assertEquals(LocationTypes.Units.PERCENT, coordinate.units());
        assertEquals(36.43, coordinate.x());
        assertNull(coordinate.z());
        assertEquals(
                LocationTypes.State.UNSUPPORTED, LocationProjection.directUiMap(phase).state());
    }

    @Test
    void sparseQuestieTablesAndUnsupportedPhaseRetainCoordinates() {
        var result =
                LocationAdapters.questieLocations(
                        NPC,
                        Map.of(
                                "spawns",
                                Map.of(
                                        "12",
                                        Map.of(
                                                "1",
                                                Map.of("1", 20, "2", 30, "3", "bad"),
                                                "3",
                                                List.of(40, 50)))),
                        QUESTIE);
        assertEquals(2, result.locations().size());
        assertEquals(LocationTypes.State.PARTIAL, result.state());
        assertTrue(result.locations().stream().allMatch(p -> p.phase() == null));
    }

    @Test
    void invalidQuestiePointDoesNotHideValidNeighbor() {
        var result =
                LocationAdapters.questieLocations(
                        NPC,
                        Map.of(
                                "spawns",
                                Map.of(
                                        "12",
                                        List.of(
                                                List.of(-1, -1),
                                                List.of(10, 20),
                                                List.of(Double.NaN, 2)))),
                        QUESTIE);
        assertEquals(1, result.locations().size());
        assertEquals(LocationTypes.State.PARTIAL, result.state());
        assertEquals(10, result.locations().getFirst().coordinates().getFirst().x());
    }

    @Test
    void boundedQuestieContainerRetainsValidPrefix() {
        var points = new ArrayList<List<Integer>>();
        for (int i = 0; i < 4097; i++) {
            points.add(List.of(10, 20));
        }
        var result =
                LocationAdapters.questieLocations(
                        NPC, Map.of("spawns", Map.of("12", points)), QUESTIE);
        assertEquals(4096, result.locations().size());
        assertEquals(LocationTypes.State.PARTIAL, result.state());
    }

    @Test
    void questieStartedByHasThreeTypedGroupsAndIgnoresObjectivesFinishersAndProvidedItems() {
        var result =
                LocationAdapters.questieStarters(
                        783,
                        Map.of(
                                "startedBy",
                                List.of(List.of(10), List.of(20), List.of(30)),
                                "finishedBy",
                                List.of(List.of(99)),
                                "objectives",
                                List.of(List.of(88)),
                                "sourceItemId",
                                77,
                                "zoneOrSort",
                                -1),
                        QUESTIE);
        assertEquals(
                List.of(
                        LocationTypes.EntityKind.NPC,
                        LocationTypes.EntityKind.OBJECT,
                        LocationTypes.EntityKind.ITEM),
                result.starters().stream().map(s -> s.entity().kind()).toList());
        assertEquals(
                List.of(10L, 20L, 30L),
                result.starters().stream().map(s -> s.entity().id()).toList());
        assertTrue(
                result.starters().stream()
                        .allMatch(s -> s.evidence().field().startsWith("startedBy[")));
    }

    @Test
    void missingQuestieStartersNeverFallBackToFinisher() {
        var result =
                LocationAdapters.questieStarters(
                        783,
                        Map.of("finishedBy", List.of(List.of(99)), "sourceItemId", 30),
                        QUESTIE);
        assertEquals(LocationTypes.State.UNKNOWN, result.state());
        assertTrue(result.starters().isEmpty());
    }

    @Test
    void explicitEmptyAndUnknownSourceFieldsAreDistinguished() {
        assertEquals(
                LocationTypes.State.READY,
                LocationAdapters.questieStarters(1, Map.of("startedBy", List.of()), QUESTIE)
                        .state());
        assertEquals(
                LocationTypes.State.UNKNOWN,
                LocationAdapters.questieLocations(NPC, Map.of(), QUESTIE).state());
        assertEquals(
                LocationTypes.State.READY,
                LocationAdapters.questieLocations(NPC, Map.of("spawns", Map.of()), QUESTIE)
                        .state());
    }

    @Test
    void polestarSeparatesNpcAndObjectIdsAndKeepsWorldAxesMapZeroAndHeight() {
        var result =
                LocationAdapters.polestarLocations(
                        NPC,
                        List.of(
                                Map.of(
                                        "kind", "npc", "id", 42, "map", 0, "n", 1, "x", 100, "y",
                                        200, "z", 3),
                                Map.of(
                                        "kind", "object", "id", 42, "map", 0, "n", 1, "x", 900, "y",
                                        900)),
                        POLESTAR);
        assertEquals(1, result.locations().size());
        var coordinate = result.locations().getFirst().coordinates().getFirst();
        assertEquals(LocationTypes.MapKind.WORLD_MAP, coordinate.map().kind());
        assertEquals(0, coordinate.map().id());
        assertEquals(100, coordinate.x());
        assertEquals(200, coordinate.y());
        assertEquals(3.0, coordinate.z());
        assertEquals(
                LocationTypes.State.UNSUPPORTED,
                LocationProjection.directUiMap(result.locations().getFirst()).state());
    }

    @Test
    void unsupportedOptionalPolestarHeightDoesNotDiscardWorldXY() {
        var result =
                LocationAdapters.polestarLocations(
                        NPC,
                        List.of(
                                Map.of(
                                        "kind", "npc", "id", 42, "map", 0, "n", 1, "x", 100, "y",
                                        200, "z", "unknown")),
                        POLESTAR);
        assertEquals(1, result.locations().size());
        assertNull(result.locations().getFirst().coordinates().getFirst().z());
        assertEquals(LocationTypes.State.PARTIAL, result.state());
    }

    @Test
    void polestarQuestStartsDoNotConfuseStarterIdWithQuestId() {
        var rows =
                List.<Map<String, Object>>of(
                        Map.of("quest", 783, "kind", "npc", "id", 197),
                        Map.of("quest", 783, "kind", "item", "id", 159),
                        Map.of("quest", 197, "kind", "npc", "id", 783));
        var result = LocationAdapters.polestarStarters(783, rows, POLESTAR);
        assertEquals(2, result.starters().size());
        assertEquals(
                List.of(197L, 159L), result.starters().stream().map(s -> s.entity().id()).toList());
    }

    @Test
    void signedWorldFrameProjectionDoesNotSwapAxesOrSortBounds() {
        var location =
                LocationAdapters.polestarLocations(
                                NPC,
                                List.of(
                                        Map.of(
                                                "kind", "npc", "id", 42, "map", 1, "n", 1, "x", 175,
                                                "y", 50)),
                                POLESTAR)
                        .locations()
                        .getFirst();
        var frame =
                LocationAdapters.polestarFrames(
                                List.of(
                                        Map.of(
                                                "ui_map", 1411, "map", 1, "left", 100, "right",
                                                -100, "top", 200, "bottom", 100)),
                                POLESTAR)
                        .frames()
                        .getFirst();
        var result = LocationProjection.throughFrame(location, frame);
        assertEquals(LocationTypes.State.READY, result.state());
        assertEquals(1411, result.coordinate().map().id());
        assertEquals(.25, result.coordinate().x());
        assertEquals(.25, result.coordinate().y());
    }

    @Test
    void worldProjectionRejectsWrongMapOutsideBoundsAndCrossSnapshotFrames() {
        var location =
                LocationAdapters.polestarLocations(
                                NPC,
                                List.of(
                                        Map.of(
                                                "kind", "npc", "id", 42, "map", 1, "n", 1, "x",
                                                1000, "y", 50)),
                                POLESTAR)
                        .locations()
                        .getFirst();
        var evidence = new LocationTypes.Evidence(POLESTAR, "ui_map", "1", "bounds");
        assertEquals(
                LocationTypes.State.UNKNOWN,
                LocationProjection.throughFrame(
                                location,
                                new LocationTypes.WorldFrame(1, 1, 100, -100, 200, 100, evidence))
                        .state());
        assertEquals(
                LocationTypes.State.UNSUPPORTED,
                LocationProjection.throughFrame(
                                location,
                                new LocationTypes.WorldFrame(1, 0, 100, -100, 200, 100, evidence))
                        .state());
        var other =
                new LocationTypes.Evidence(
                        new LocationTypes.Source(
                                LocationTypes.Provider.POLESTAR,
                                "p2",
                                "Forever",
                                "1.60.1.70707",
                                "enUS",
                                "GPL"),
                        "ui_map",
                        "1",
                        "bounds");
        assertEquals(
                LocationTypes.State.UNSUPPORTED,
                LocationProjection.throughFrame(
                                location,
                                new LocationTypes.WorldFrame(1, 1, 100, -100, 200, 100, other))
                        .state());
    }

    @Test
    void egPositionAlternativesAreOnePlayerObservationNotTwoSpawns() {
        var result =
                LocationAdapters.egLocations(
                        NPC,
                        Map.of(
                                "positions",
                                List.of(
                                        Map.of(
                                                "instance",
                                                1,
                                                "uiMap",
                                                1411,
                                                "mapX",
                                                .4214,
                                                "mapY",
                                                .6841,
                                                "worldX",
                                                -603.2,
                                                "worldY",
                                                -4190.7))),
                        EG);
        assertEquals(1, result.locations().size());
        var point = result.locations().getFirst();
        assertEquals(LocationTypes.Origin.OBSERVED_INTERACTION, point.origin());
        assertEquals(2, point.coordinates().size());
        assertEquals(.4214, LocationProjection.directUiMap(point).coordinate().x());
        assertEquals("npcs/npcs", point.evidence().dataset());
    }

    @Test
    void egInvalidUiCoordinateRetainsWorldAlternativeWithoutInventingUiMapZero() {
        var result =
                LocationAdapters.egLocations(
                        NPC,
                        Map.of(
                                "positions",
                                List.of(
                                        Map.of(
                                                "instance",
                                                0,
                                                "uiMap",
                                                1411,
                                                "mapX",
                                                1.2,
                                                "mapY",
                                                .5,
                                                "worldX",
                                                10,
                                                "worldY",
                                                20),
                                        Map.of(
                                                "instance",
                                                0,
                                                "uiMap",
                                                0,
                                                "mapX",
                                                0,
                                                "mapY",
                                                0,
                                                "worldX",
                                                30,
                                                "worldY",
                                                40))),
                        EG);
        assertEquals(2, result.locations().size());
        assertEquals(LocationTypes.State.PARTIAL, result.state());
        assertTrue(
                result.locations().stream()
                        .allMatch(
                                p ->
                                        p.coordinates().size() == 1
                                                && p.coordinates().getFirst().map().kind()
                                                        == LocationTypes.MapKind.WORLD_MAP));
    }

    @Test
    void egCombatLogShapeIsNotInterpretedAsStandardInteractionPosition() {
        var result =
                LocationAdapters.egLocations(
                        NPC,
                        Map.of(
                                "positions",
                                List.of(Map.of("map", 1, "uiMap", 1411, "x", 123, "y", 456))),
                        EG);
        assertTrue(result.locations().isEmpty());
        assertEquals(LocationTypes.State.PARTIAL, result.state());
    }

    @Test
    void egOnlyOffersCreatesPickupsAndItemStartersHaveNoIntrinsicLocation() {
        var issues = new ArrayList<String>();
        var starts =
                LocationAdapters.egOffers(
                        OBJECT,
                        Map.of(
                                "offers",
                                Map.of("783", Map.of("title", "Authored")),
                                "takes",
                                Map.of("99", Map.of()),
                                "objectives",
                                List.of(88)),
                        EG,
                        issues);
        assertEquals(1, starts.size());
        assertEquals(783, starts.getFirst().questId());
        assertEquals(LocationTypes.EntityKind.OBJECT, starts.getFirst().entity().kind());
        assertTrue(
                LocationAdapters.egOffers(NPC, Map.of("takes", Map.of("783", Map.of())), EG, issues)
                        .isEmpty());
        assertEquals(
                LocationTypes.State.UNSUPPORTED,
                LocationAdapters.questieLocations(ITEM, Map.of("npcDrops", List.of(10)), QUESTIE)
                        .state());
    }

    @Test
    void malformedEgOfferArraysCannotTurnTheirArrayIndicesIntoQuestIds() {
        var issues = new ArrayList<String>();
        assertTrue(
                LocationAdapters.egOffers(
                                NPC, Map.of("offers", List.of(Map.of("id", 783))), EG, issues)
                        .isEmpty());
        assertFalse(issues.isEmpty());
        assertTrue(
                LocationAdapters.egOffers(NPC, Map.of("offers", Map.of("783", true)), EG, issues)
                        .isEmpty());
        assertEquals(
                LocationTypes.State.PARTIAL,
                LocationAdapters.egLocations(
                                NPC,
                                Map.of(
                                        "positions",
                                        Map.of("1", Map.of("uiMap", 1, "mapX", .1, "mapY", .2))),
                                EG)
                        .state());
    }

    @Test
    void jsonParsingRejectsDuplicateTruncatedOverdeepAndTrailingRecords() throws Exception {
        assertEquals(
                1,
                LocationJson.parse("{\"positions\":[{\"instance\":0,\"worldX\":1,\"worldY\":2}]}")
                        .size());
        assertThrows(java.io.IOException.class, () -> LocationJson.parse("{\"x\":1,\"x\":2}"));
        assertThrows(java.io.IOException.class, () -> LocationJson.parse("{\"positions\":["));
        assertThrows(java.io.IOException.class, () -> LocationJson.parse("{} {}"));
        assertThrows(
                java.io.IOException.class,
                () -> LocationJson.parse("{\"x\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}"));
    }

    @Test
    void coordinateContractsRejectNonFiniteAndConfusedSpaces() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LocationTypes.MapRef(LocationTypes.MapKind.UI_MAP, 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LocationTypes.Coordinate(
                                new LocationTypes.MapRef(LocationTypes.MapKind.AREA, 12),
                                LocationTypes.Units.WORLD_YARDS,
                                1,
                                2,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LocationTypes.Coordinate(
                                new LocationTypes.MapRef(LocationTypes.MapKind.UI_MAP, 1),
                                LocationTypes.Units.NORMALIZED,
                                Double.POSITIVE_INFINITY,
                                0,
                                null));
        assertFalse(
                LocationAdapters.polestarFrames(
                                List.of(
                                        Map.of(
                                                "ui_map", 1, "map", 0, "left", 1, "right", 1, "top",
                                                2, "bottom", 0)),
                                POLESTAR)
                        .issues()
                        .isEmpty());
    }
}
