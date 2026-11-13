package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RaceClothingGeometryTest {
    @Test
    void activeHairstyleKeepsBaseBodyAndLeavesDormantGroupsUnchanged() {
        var defaults = Set.of(0, 1, 101, 701, 3201);
        var hair = RaceMedia.applyCustomizationParts(defaults, Set.of(0), Set.of(2));
        assertTrue(hair.containsAll(Set.of(0, 2, 101, 701, 3201)));
        assertFalse(hair.contains(1));
        var bald = RaceMedia.applyCustomizationParts(defaults, Set.of(0), Set.of(0));
        assertTrue(bald.containsAll(Set.of(0, 101, 701, 3201)));
        assertFalse(bald.contains(1));
    }

    @Test
    void robeSelectsSleevesAndClosedSkirtWhileKeepingBootFeetAndSuppressingShaft() {
        var parts =
                java.util.stream.IntStream.of(
                                501, 502, 801, 802, 901, 1001, 1101, 1301, 1302, 2001, 2002, 2201,
                                2801)
                        .mapToObj(id -> new PreviewMesh.Part(id, 0, 3))
                        .toList();
        var mesh =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[] {0, 1, 2}, parts);
        var items =
                List.of(
                        new RaceAppearancePlan.Item(55, 8, 6077, List.of(1, 0, 0, 0, 0, 0)),
                        new RaceAppearancePlan.Item(56, 20, 10455, List.of(1, 0, 1, 0, 0, 0)),
                        new RaceAppearancePlan.Item(1395, 7, 1882, List.of(0, 0, 0, 0, 0, 0)));
        var shown = RaceClothingGeometry.apply(mesh, Set.of(501, 801, 1301, 2001), items);
        assertTrue(shown.containsAll(Set.of(501, 802, 1302, 2002)));
        assertFalse(shown.contains(502));
        assertFalse(shown.contains(801));
        assertFalse(shown.contains(1301));
        assertFalse(shown.contains(2001));
    }

    @Test
    void taurenShapedSurfaceKeepsBodyAndHornsWithRobeAndInventsNoFeetOrSleeves() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        java.util.stream.IntStream.of(0, 1, 802, 1301, 1302, 2401, 3201)
                                .mapToObj(id -> new PreviewMesh.Part(id, 0, 3))
                                .toList());
        var shown =
                RaceClothingGeometry.apply(
                        mesh,
                        Set.of(0, 1, 1301, 2401, 3201),
                        List.of(
                                new RaceAppearancePlan.Item(1, 7, 1, List.of(0, 0, 0, 0, 0, 0)),
                                new RaceAppearancePlan.Item(2, 20, 2, List.of(0, 0, 1, 0, 0, 0))));
        assertTrue(shown.containsAll(Set.of(0, 1, 1302, 2401, 3201)));
        assertFalse(shown.contains(1301));
        assertFalse(shown.contains(802));
        assertFalse(shown.stream().anyMatch(id -> id / 100 == 20));
    }

    @Test
    void clothingSlotsOnlyOverlayTheirReviewedAtlasSections() {
        assertTrue(RaceClothingGeometry.allowsSection(20, 6));
        assertFalse(RaceClothingGeometry.allowsSection(20, 7));
        assertTrue(RaceClothingGeometry.allowsSection(8, 7));
        assertFalse(RaceClothingGeometry.allowsSection(8, 3));
        assertTrue(RaceClothingGeometry.allowsSection(7, 5));
        assertFalse(RaceClothingGeometry.allowsSection(7, 1));
    }

    @Test
    void unsupportedRobeDoesNotSuppressOtherGeometryAndDefaultDoesNotEraseSpecificVariant() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        java.util.stream.IntStream.of(
                                        501, 502, 801, 802, 1001, 1301, 1302, 2002, 2201, 2801)
                                .mapToObj(id -> new PreviewMesh.Part(id, 0, 3))
                                .toList());
        var items =
                List.of(
                        new RaceAppearancePlan.Item(1, 4, 1, List.of(1, 0, 0, 0, 0, 0)),
                        new RaceAppearancePlan.Item(2, 5, 2, List.of(0, 0, 0, 0, 0, 0)),
                        new RaceAppearancePlan.Item(3, 8, 3, List.of(1, 0, 0, 0, 0, 0)),
                        new RaceAppearancePlan.Item(4, 20, 4, List.of()));
        var shown = RaceClothingGeometry.apply(mesh, Set.of(501, 801, 1301), items);
        assertTrue(shown.contains(802));
        assertFalse(shown.contains(801));
        assertTrue(shown.contains(502));
        assertTrue(shown.contains(1301));
        assertFalse(shown.contains(1302));
    }

    private static PreviewMesh mesh(int... ids) {
        return new PreviewMesh(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2},
                java.util.stream.IntStream.of(ids)
                        .mapToObj(id -> new PreviewMesh.Part(id, 0, 3))
                        .toList());
    }

    @Test
    void faceGroupDrawsOneFullFaceNeverItsAlternativesTogether() {
        // Human: a recessed cap (3201) and three coincident full faces.
        assertEquals(
                Set.of(0, 101, 3202),
                RaceMedia.defaultParts(mesh(0, 101, 102, 3201, 3202, 3203, 3204)));
        // Most models: the cap and one full face.
        assertEquals(Set.of(0, 3202), RaceMedia.defaultParts(mesh(0, 3201, 3202)));
        // A model with only the cap keeps it.
        assertEquals(Set.of(0, 3201), RaceMedia.defaultParts(mesh(0, 3201)));
        // Group 12, 17 and 35 still show only when chosen.
        assertEquals(Set.of(0), RaceMedia.defaultParts(mesh(0, 1201, 1701, 3501)));
    }

    @Test
    void aFaceChoiceReplacesTheDefaultFace() {
        var defaults = RaceMedia.defaultParts(mesh(0, 3201, 3202, 3203, 3204));
        assertEquals(
                Set.of(0, 3204),
                RaceMedia.applyCustomizationParts(defaults, Set.of(32), Set.of(3204)));
    }

    @Test
    void customizationGeosetMinusOneHidesItsOwnGroupAndNeverReadsAsTheGroupBelow() {
        // Breechcloth choice 28271 (model 89) uses row 12028: type 14, GeosetID -1.
        assertEquals(Set.of(), RaceMedia.customizationPart(14, -1).show());
        assertEquals(Set.of(14), RaceMedia.customizationPart(14, -1).groups());
        var visible =
                RaceMedia.applyCustomizationParts(Set.of(0, 1301, 1401), Set.of(14), Set.of());
        assertEquals(Set.of(0, 1301), visible);
        // A chest's default robe group still applies: nothing reads as variant 1399.
        var shown =
                RaceClothingGeometry.apply(
                        mesh(0, 1301, 1302, 1401),
                        visible,
                        List.of(new RaceAppearancePlan.Item(1, 5, 1, List.of(0, 0, 0, 0, 0, 0))));
        assertEquals(Set.of(0, 1301), shown);
        // Values outside 0..99 (garbage such as 1818453348 on 70862) are ignored outright.
        assertEquals(null, RaceMedia.customizationPart(45, 1818453348));
        assertEquals(null, RaceMedia.customizationPart(14, -2));
        assertEquals(null, RaceMedia.customizationPart(14, 100));
        assertEquals(Set.of(1407), RaceMedia.customizationPart(14, 7).show());
    }

    @Test
    void itemGeosetGroupMinusOneLeavesTheGroupAsItIs() {
        var mesh = mesh(0, 801, 802, 1001, 1301, 1302, 2002, 2201, 2202, 2801, 2802, 501, 502);
        var sample = Set.of(0, 801, 1001, 1301, 2201, 2801, 501);
        // 680059: a chest whose robe, tabard and group 28 values are all -1.
        var chest =
                RaceClothingGeometry.apply(
                        mesh,
                        sample,
                        List.of(
                                new RaceAppearancePlan.Item(
                                        1, 5, 1, List.of(1, 0, -1, -1, -1, 0))));
        assertEquals(Set.of(0, 802, 1001, 1301, 2201, 2801, 501), chest);
        // Boots whose group 20 value is -1 keep the bare feet the body already shows.
        var boots =
                RaceClothingGeometry.apply(
                        mesh,
                        sample,
                        List.of(new RaceAppearancePlan.Item(2, 8, 2, List.of(1, -1, 0, 0, 0, 0))));
        assertEquals(Set.of(0, 801, 1001, 1301, 2201, 2801, 502), boots);
    }

    private static RaceAppearancePlan.Item item(int inventory, long flags, Integer... groups) {
        return new RaceAppearancePlan.Item(0, inventory, inventory, List.of(groups), flags);
    }

    private static final PreviewMesh CLOTHED =
            mesh(
                    0, 1001, 1002, 1101, 1102, 1103, 1104, 1201, 1202, 1203, 1301, 1302, 1501, 1502,
                    1503, 1504, 1801, 1802, 2201, 2202);

    @Test
    void aTabardShowsItsGeosetAndArmoredTorsoAndKeepsTheChestSkirtAndShortTrousersOff() {
        var shown =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 1101, 1301, 1501, 2201),
                        List.of(
                                item(5, 0, 0, 1, 0, 0, 0, 0), // chest: GeosetGroup[1] 1 (1002)
                                item(7, 0, 1, 0, 0, 0, 0, 0), // trousers: GeosetGroup[0] 1 (1102)
                                item(19, 0, 1, 0, 0, 0, 0, 0))); // tabard: 1202
        assertTrue(shown.containsAll(Set.of(0, 1101, 1202, 1301, 1501, 2202)), shown.toString());
        assertFalse(shown.contains(1002), "a shown tabard keeps the chest's group 10 off");
        assertFalse(shown.contains(1102), "and trousers' group 11 up to variant 2");
        assertFalse(shown.contains(2201));
        // Trousers of GeosetGroup[0] above 2 still apply (1104).
        var armored =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 1101),
                        List.of(item(7, 0, 3, 0, 0, 0, 0, 0), item(19, 0, 1, 0, 0, 0, 0, 0)));
        assertTrue(armored.containsAll(Set.of(1104, 1202)), armored.toString());
        // Without the tabard the chest and trousers apply as before.
        var bare =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 1101, 2201),
                        List.of(item(5, 0, 0, 1, 0, 1, 0, 0), item(7, 0, 1, 0, 0, 0, 0, 0)));
        assertTrue(bare.containsAll(Set.of(1002, 1102, 2202)), bare.toString());
        assertFalse(bare.stream().anyMatch(id -> id / 100 == 12));
    }

    @Test
    void aBeltThatClipsTheLowerTabardShowsTabardVariantThree() {
        var items = List.of(item(6, 0x200, 1, 0, 0, 0, 0, 0), item(19, 0, 1, 0, 0, 0, 0, 0));
        var shown = RaceClothingGeometry.apply(CLOTHED, Set.of(0, 2201), items);
        assertTrue(shown.containsAll(Set.of(1203, 1802, 2202)), shown.toString());
        assertFalse(shown.contains(1202));
        // A belt without the flag leaves the tabard at 1202.
        var plain =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 2201),
                        List.of(item(6, 0, 1, 0, 0, 0, 0, 0), item(19, 0, 1, 0, 0, 0, 0, 0)));
        assertTrue(plain.containsAll(Set.of(1202, 1802)), plain.toString());
        // A model without 1203 shows no tabard geoset then, as the client would.
        var withoutVariant =
                RaceClothingGeometry.apply(mesh(0, 1201, 1202, 1802, 2201, 2202), Set.of(0), items);
        assertFalse(withoutVariant.stream().anyMatch(id -> id / 100 == 12));
    }

    @Test
    void aDressHidesTheTabardGeosetButNotItsTorso() {
        // A robe: the chest sets GeosetGroup[2].
        var robe =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 1301, 2201),
                        List.of(item(20, 0, 0, 0, 1, 0, 0, 0), item(19, 0, 1, 0, 0, 0, 0, 0)));
        assertTrue(robe.containsAll(Set.of(1302, 2202)), robe.toString());
        assertFalse(robe.stream().anyMatch(id -> id / 100 == 12));
        // Without a chest, trousers that set GeosetGroup[2] are a dress too.
        var trousers =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 1301, 2201),
                        List.of(item(7, 0, 0, 0, 1, 0, 0, 0), item(19, 0, 1, 0, 0, 0, 0, 0)));
        assertFalse(trousers.stream().anyMatch(id -> id / 100 == 12), trousers.toString());
        // With a chest that sets no robe group, such trousers are not a dress.
        var chest =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 1301, 2201),
                        List.of(
                                item(5, 0, 0, 0, 0, 0, 0, 0),
                                item(7, 0, 0, 0, 1, 0, 0, 0),
                                item(19, 0, 1, 0, 0, 0, 0, 0)));
        assertTrue(chest.contains(1202), chest.toString());
    }

    @Test
    void aTabardFlaggedToKeepTheTorsoLeavesItToTheChestAndGroupZeroShowsNoTabard() {
        var kept =
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0, 2201),
                        List.of(
                                item(5, 0, 0, 0, 0, 0, 0, 0),
                                item(
                                        19,
                                        RaceClothingGeometry.TABARD_KEEPS_TORSO,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0)));
        assertTrue(kept.contains(2201), kept.toString());
        assertFalse(kept.contains(2202));
        assertFalse(kept.stream().anyMatch(id -> id / 100 == 12));
        // GeosetGroup[0] 0: no tabard geoset, but the torso still follows the tabard.
        var none =
                RaceClothingGeometry.apply(
                        CLOTHED, Set.of(0, 2201), List.of(item(19, 0, 0, 0, 0, 0, 0, 0)));
        assertTrue(none.contains(2202), none.toString());
        assertFalse(none.stream().anyMatch(id -> id / 100 == 12));
    }

    @Test
    void aCapeShowsItsStyleAndGroupZeroChangesNothing() {
        var shown =
                RaceClothingGeometry.apply(
                        CLOTHED, Set.of(0, 1501), List.of(item(16, 0, 3, 0, 0, 0, 0, 0)));
        assertTrue(shown.contains(1504), shown.toString());
        assertFalse(shown.contains(1501));
        var none =
                RaceClothingGeometry.apply(
                        CLOTHED, Set.of(0), List.of(item(16, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(Set.of(0), none);
        // An overridden display (no groups) changes nothing either.
        assertEquals(
                Set.of(0),
                RaceClothingGeometry.apply(
                        CLOTHED,
                        Set.of(0),
                        List.of(new RaceAppearancePlan.Item(0, 16, 16, List.of(), 0))));
    }
}
