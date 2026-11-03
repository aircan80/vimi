package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RaceClothingGeometryTest {
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
}
