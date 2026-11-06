package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorldSkyTest {
    private static WorldSky.Sample grey(int level, double fogEnd) {
        int c = level << 16 | level << 8 | level;
        return new WorldSky.Sample(c, c, c, c, c, c, fogEnd, 0.5);
    }

    private static WorldSky.Light defaultLight(long id, long map, long param) {
        return new WorldSky.Light(id, map, 0, 0, 0, 0, 0, param);
    }

    private static WorldSky.Light at(
            long id, long map, double x, double start, double end, long p) {
        return new WorldSky.Light(id, map, x, 0, 0, start, end, p);
    }

    private static WorldSky.Index index(List<WorldSky.Light> lights, List<WorldSky.Key> keys) {
        return WorldSky.Index.of(lights, keys, Map.of(), List.of());
    }

    @Test
    void interpolatesBetweenTheKeysAroundTheTimeAndWrapsAtMidnight() {
        var keys =
                List.of(
                        new WorldSky.Key(10, 1, 600, grey(0, 100)),
                        new WorldSky.Key(11, 1, 1800, grey(120, 700)),
                        new WorldSky.Key(12, 1, 2400, grey(240, 1000)));
        var sky = index(List.of(), keys);

        var mid = sky.sample(1, 1200);
        assertEquals(grey(60, 400), mid.sample());
        assertEquals(List.of(10L, 11L), mid.dataRows());

        // 2400 -> 600 spans midnight: 1080 half-minutes; 0 is 480 of them past 2400.
        var night = sky.sample(1, 0);
        assertEquals(List.of(12L, 10L), night.dataRows());
        double f = 480.0 / 1080;
        assertEquals(Math.round(240 * (1 - f)), night.sample().skyTop() & 0xff);
        assertEquals(1000 + (100 - 1000) * f, night.sample().fogEnd(), 1e-9);

        var exact = sky.sample(1, 1800);
        assertEquals(List.of(11L), exact.dataRows());
        assertEquals(grey(120, 700), exact.sample());

        assertNull(sky.sample(2, 0));
        assertThrows(IllegalArgumentException.class, () -> sky.sample(1, WorldSky.DAY));
    }

    @Test
    void aSingleKeyHoldsAllDay() {
        var sky = index(List.of(), List.of(new WorldSky.Key(1, 7, 900, grey(50, 300))));
        for (int time : new int[] {0, 899, 900, 2879}) {
            assertEquals(grey(50, 300), sky.sample(7, time).sample());
        }
    }

    @Test
    void positionalLightsFallOffLinearlyIntoTheMapWideLight() {
        var sky =
                index(
                        List.of(defaultLight(1, 0, 100), at(2, 0, 1000, 100, 300, 200)),
                        List.of(
                                new WorldSky.Key(1, 100, 0, grey(0, 0)),
                                new WorldSky.Key(2, 200, 0, grey(200, 1000))));

        var inside = sky.at(0, 1050, 0, 0, 0);
        assertEquals(WorldSky.State.READY, inside.state());
        assertEquals(1, inside.contributions().size());
        assertEquals(2, inside.contributions().getFirst().lightId());
        assertEquals(grey(200, 1000), inside.sample());

        // Distance 200 is halfway between the inner radius 100 and the outer radius 300.
        var half = sky.at(0, 800, 0, 0, 0);
        assertEquals(2, half.contributions().size());
        assertEquals(0.5, half.contributions().get(0).weight(), 1e-12);
        assertEquals(grey(100, 500), half.sample());

        // The radius is 3D: the same horizontal spot 300 above is outside.
        var above = sky.at(0, 1000, 0, 300, 0);
        assertEquals(List.of(1L), above.contributions().stream().map(c -> c.lightId()).toList());
        assertTrue(above.contributions().getFirst().mapDefault());

        var other = sky.at(5, 1000, 0, 0, 0);
        assertEquals(WorldSky.State.UNAVAILABLE, other.state());
        assertNull(other.sample());
        assertTrue(other.issues().getFirst().contains("no map-wide light"));
    }

    @Test
    void overlappingLightsAreScaledToOneAndTheMapLightGetsNothing() {
        var sky =
                index(
                        List.of(
                                defaultLight(1, 0, 100),
                                at(2, 0, 0, 500, 600, 200),
                                at(3, 0, 10, 500, 600, 300)),
                        List.of(
                                new WorldSky.Key(1, 100, 0, grey(255, 0)),
                                new WorldSky.Key(2, 200, 0, grey(0, 0)),
                                new WorldSky.Key(3, 300, 0, grey(100, 0))));
        var result = sky.at(0, 5, 0, 0, 0);
        assertEquals(
                List.of(2L, 3L), result.contributions().stream().map(c -> c.lightId()).toList());
        assertEquals(0.5, result.contributions().get(1).weight(), 1e-12);
        assertEquals(grey(50, 0), result.sample());
        assertEquals("Sky: Light 2 (+1 blended) · LightParams 200 · 00:00", result.label());
    }

    @Test
    void aMapsRecordedTimeWinsOverTheRequestedTime() {
        var sky =
                WorldSky.Index.of(
                        List.of(defaultLight(1, 9, 100)),
                        List.of(
                                new WorldSky.Key(1, 100, 0, grey(0, 0)),
                                new WorldSky.Key(2, 100, 1440, grey(200, 0))),
                        Map.of(9L, 1440, 4L, -1),
                        List.of());
        var result = sky.at(9, 0, 0, 0, 0);
        assertTrue(result.timeFixedByMap());
        assertEquals(1440, result.time());
        assertEquals(grey(200, 0), result.sample());
        assertTrue(result.label().endsWith("12:00 (map time)"));
    }

    @Test
    void gapsAreNamedAndTheRemainingLightsRenormalised() {
        var sky =
                WorldSky.Index.of(
                        List.of(
                                defaultLight(1, 0, 100),
                                defaultLight(2, 0, 101),
                                at(3, 0, 0, 0, 200, 300),
                                at(4, 0, 0, 0, 200, 400)),
                        List.of(new WorldSky.Key(1, 300, 0, grey(80, 10))),
                        Map.of(),
                        List.of("Light is partial: 9 of 10 rows are readable."));
        var result = sky.at(0, 150, 0, 0, 0);
        assertEquals(WorldSky.State.PARTIAL, result.state());
        assertEquals(grey(80, 10), result.sample());
        assertEquals(1, result.contributions().size());
        var issues = String.join("\n", result.issues());
        assertTrue(issues.contains("Light is partial"));
        assertTrue(issues.contains("records 2 map-wide lights"));
        assertTrue(issues.contains("LightParams 400 of Light 4 has no LightData rows"));
        assertTrue(result.label().endsWith("· partial"));
    }

    @Test
    void rejectsDuplicateRowsAndRepeatedTimesAreReported() {
        assertThrows(
                IllegalArgumentException.class,
                () -> index(List.of(defaultLight(1, 0, 1), defaultLight(1, 0, 1)), List.of()));
        var sky =
                index(
                        List.of(defaultLight(1, 0, 1)),
                        List.of(
                                new WorldSky.Key(5, 1, 100, grey(10, 0)),
                                new WorldSky.Key(4, 1, 100, grey(20, 0))));
        assertEquals(List.of(4L), sky.sample(1, 100).dataRows());
        assertTrue(sky.sourceIssues().getFirst().contains("repeat a time"));
        assertFalse(sky.at(0, 0, 0, 0, 100).issues().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldSky.Sample(0x0100_0000, 0, 0, 0, 0, 0, 0, 0));
    }
}
