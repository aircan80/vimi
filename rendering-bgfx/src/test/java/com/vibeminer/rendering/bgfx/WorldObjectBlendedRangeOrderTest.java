package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issue #552: a placement's blended ranges are drawn back to front. */
class WorldObjectBlendedRangeOrderTest {
    private static final int OPAQUE = WorldStreamObjectsFile.OPAQUE;
    private static final int CUTOUT = WorldStreamObjectsFile.CUTOUT;
    private static final int BLENDED = WorldStreamObjectsFile.BLENDED;
    private static final int ADDITIVE = WorldStreamObjectsFile.ADDITIVE;

    /** One model of one mesh whose ranges have these classes, in file order. */
    private static int[] ranges(int... classes) {
        var ranges = new int[classes.length * WorldStreamObjectsFile.RANGE_INTS];
        for (int r = 0; r < classes.length; r++) {
            ranges[r * WorldStreamObjectsFile.RANGE_INTS + 1] = 3;
            ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3] = classes[r];
        }
        return ranges;
    }

    private static double[] placement(double x, double y, double z, double scale, double... rot) {
        var placement = new double[WorldStreamObjectsFile.PLACEMENT_DOUBLES];
        placement[0] = x;
        placement[1] = y;
        placement[2] = z;
        placement[3] = scale;
        System.arraycopy(rot, 0, placement, 4, 9);
        return placement;
    }

    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    /** Back-to-front range order: the keys read backwards. */
    private static int[] order(int[] ranges, float[] centres, double[] placement, double x) {
        int count = ranges.length / WorldStreamObjectsFile.RANGE_INTS;
        var keys = new long[count];
        int sorted =
                BgfxWorldStreamRenderer.blendedRangeKeys(
                        new int[] {0, 1},
                        new int[] {0, 0, 0, 0, 0, count},
                        ranges,
                        centres,
                        placement,
                        0,
                        0,
                        x,
                        0,
                        0,
                        keys);
        var result = new int[sorted];
        for (int i = 0; i < sorted; i++) {
            result[i] = BgfxWorldStreamRenderer.keyRange(keys[sorted - 1 - i]);
        }
        return result;
    }

    @Test
    void twoBlendedRangesInOnePlacementDrawBackToFront() {
        // Range 0 is the near pane and comes first in the file; range 1 is 10 yd farther along +x.
        var ranges = ranges(BLENDED, BLENDED);
        float[] centres = {2, 0, 0, 12, 0, 0};
        assertArrayEquals(
                new int[] {1, 0}, order(ranges, centres, placement(0, 0, 0, 1, IDENTITY), 0));
        // From the other side the near pane is range 1, so range 0 draws first.
        assertArrayEquals(
                new int[] {0, 1}, order(ranges, centres, placement(0, 0, 0, 1, IDENTITY), 20));
    }

    @Test
    void theOrderFollowsThePlacementsTransform() {
        var ranges = ranges(BLENDED, BLENDED);
        float[] centres = {2, 0, 0, 12, 0, 0};
        // Turned half round about z and moved to x = 100: range 1 now lies at x = 76, range 0 at
        // x = 96, so a camera at x = 0 sees range 0 farther.
        double[] turned = {-1, 0, 0, 0, -1, 0, 0, 0, 1};
        assertArrayEquals(
                new int[] {0, 1}, order(ranges, centres, placement(100, 0, 0, 2, turned), 0));
        assertArrayEquals(
                new int[] {1, 0}, order(ranges, centres, placement(100, 0, 0, 2, IDENTITY), 0));
    }

    @Test
    void onlyBlendedRangesAreSortedAndOthersKeepTheirSteps() {
        // Additive stays in its own step after the blended one (#449); opaque and cutout stay in
        // the unsorted pass, in file order.
        var ranges = ranges(OPAQUE, BLENDED, ADDITIVE, CUTOUT, BLENDED);
        float[] centres = {0, 0, 0, 3, 0, 0, 50, 0, 0, 0, 0, 0, 9, 0, 0};
        assertArrayEquals(
                new int[] {4, 1}, order(ranges, centres, placement(0, 0, 0, 1, IDENTITY), 0));
        assertEquals(BgfxWorldStreamRenderer.STEP_UNSORTED, BgfxWorldStreamRenderer.step(OPAQUE));
        assertEquals(BgfxWorldStreamRenderer.STEP_UNSORTED, BgfxWorldStreamRenderer.step(CUTOUT));
        assertEquals(BgfxWorldStreamRenderer.STEP_ADDITIVE, BgfxWorldStreamRenderer.step(ADDITIVE));
        assertTrue(BgfxWorldStreamRenderer.STEP_BLENDED < BgfxWorldStreamRenderer.STEP_ADDITIVE);
    }

    @Test
    void equalDistancesKeepFileOrder() {
        var ranges = ranges(BLENDED, BLENDED, BLENDED);
        float[] centres = {0, 5, 0, 0, -5, 0, 5, 0, 0};
        assertArrayEquals(
                new int[] {0, 1, 2}, order(ranges, centres, placement(0, 0, 0, 1, IDENTITY), 0));
    }

    @Test
    void rangeKeysRoundTripAndOrderByDistance() {
        for (int range : new int[] {0, 1, 4095, WorldStreamObjectsFile.MAX_RANGES - 1}) {
            assertEquals(
                    range,
                    BgfxWorldStreamRenderer.keyRange(BgfxWorldStreamRenderer.rangeKey(7.5, range)));
        }
        assertTrue(BgfxWorldStreamRenderer.rangeKey(1, 0) < BgfxWorldStreamRenderer.rangeKey(2, 0));
        assertTrue(
                BgfxWorldStreamRenderer.rangeKey(-0.0, 3)
                        < BgfxWorldStreamRenderer.rangeKey(Double.MAX_VALUE, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> BgfxWorldStreamRenderer.rangeKey(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> BgfxWorldStreamRenderer.rangeKey(1, -1));
    }

    @Test
    void sortingAPlacementsRangesAllocatesNothing() {
        int count = 64;
        var classes = new int[count];
        var centres = new float[count * 3];
        for (int r = 0; r < count; r++) {
            classes[r] = r % 4 == 3 ? ADDITIVE : BLENDED;
            centres[r * 3] = (r * 37) % 64;
        }
        var ranges = ranges(classes);
        int[] models = {0, 1}, meshes = {0, 0, 0, 0, 0, count};
        var placement = placement(0, 0, 0, 1, IDENTITY);
        var keys = new long[count];
        var threads =
                (com.sun.management.ThreadMXBean)
                        java.lang.management.ManagementFactory.getThreadMXBean();
        for (int warm = 0; warm < 20_000; warm++) {
            BgfxWorldStreamRenderer.blendedRangeKeys(
                    models, meshes, ranges, centres, placement, 0, 0, warm % 70, 0, 0, keys);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20_000; i++) {
            BgfxWorldStreamRenderer.blendedRangeKeys(
                    models, meshes, ranges, centres, placement, 0, 0, i % 70, 0, 0, keys);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        // Slack for the measurement itself; 20,000 sorts that allocated would take megabytes.
        assertTrue(allocated < 4096, "allocated " + allocated + " bytes over 20,000 sorts");
    }

    @Test
    void sortingKeysMatchesArraysSortAtEverySize() {
        var random = new java.util.Random(552);
        for (int count = 0; count <= 200; count++) {
            for (int round = 0; round < 5; round++) {
                var keys = new long[count + 3];
                for (int i = 0; i < keys.length; i++) {
                    // Few distinct values, so duplicates are common.
                    keys[i] = round % 2 == 0 ? random.nextLong() : random.nextInt(8) - 4;
                }
                var expected = keys.clone();
                java.util.Arrays.sort(expected, 0, count);
                BgfxWorldStreamRenderer.sortKeys(keys, count);
                assertArrayEquals(expected, keys, "count " + count);
            }
        }
    }

    @Test
    void sortingFewKeysAllocatesNothing() {
        var keys = new long[16];
        var threads =
                (com.sun.management.ThreadMXBean)
                        java.lang.management.ManagementFactory.getThreadMXBean();
        sortFewKeys(keys);
        long before = threads.getCurrentThreadAllocatedBytes();
        sortFewKeys(keys);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4096, "allocated " + allocated + " bytes over 20,000 sorts");
    }

    private static void sortFewKeys(long[] keys) {
        for (int i = 0; i < 20_000; i++) {
            for (int k = 0; k < keys.length; k++) {
                keys[k] = (k * 7L + i) % keys.length;
            }
            BgfxWorldStreamRenderer.sortKeys(keys, keys.length);
        }
    }

    /**
     * The per-frame placement sort sees hundreds of blended placements, so it takes the heapsort
     * path. Measured in a child JVM limited to C1, where nothing removes {@code Arrays.sort}'s
     * scratch allocations, so the check doesn't depend on C2 having compiled the loop.
     */
    @Test
    void sortingManyKeysAllocatesNothingWithoutC2() throws Exception {
        var command =
                java.util.List.of(
                        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                                .toString(),
                        "-XX:TieredStopAtLevel=1",
                        "-cp",
                        System.getProperty("java.class.path"),
                        SortAllocation.class.getName());
        var child = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var out = child.getInputStream()) {
            output = new String(out.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), output);
        assertEquals(0, child.exitValue(), output);
        var line =
                output.lines()
                        .filter(l -> l.startsWith("sortKeys "))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(output));
        long allocated = Long.parseLong(line.split(" ")[1]);
        assertTrue(allocated < 4096, "allocated " + allocated + " bytes; " + output);
    }

    /** Child: sorts 1,000 keys 2,000 times and prints the bytes this thread allocated. */
    public static final class SortAllocation {
        private SortAllocation() {}

        public static void main(String[] args) {
            var threads =
                    (com.sun.management.ThreadMXBean)
                            java.lang.management.ManagementFactory.getThreadMXBean();
            var keys = new long[1000];
            // One lambda each, created before measuring: a lambda's first evaluation allocates.
            Sort sortKeys = BgfxWorldStreamRenderer::sortKeys;
            Sort arraysSort = (k, c) -> java.util.Arrays.sort(k, 0, c);
            sortMany(keys, sortKeys);
            long before = threads.getCurrentThreadAllocatedBytes();
            sortMany(keys, sortKeys);
            System.out.println("sortKeys " + (threads.getCurrentThreadAllocatedBytes() - before));
            // For comparison only: what the previous per-frame Arrays.sort allocated here.
            sortMany(keys, arraysSort);
            before = threads.getCurrentThreadAllocatedBytes();
            sortMany(keys, arraysSort);
            System.out.println(
                    "Arrays.sort " + (threads.getCurrentThreadAllocatedBytes() - before));
        }

        private interface Sort {
            void sort(long[] keys, int count);
        }

        private static void sortMany(long[] keys, Sort sort) {
            long seed = 552;
            for (int i = 0; i < 2_000; i++) {
                for (int k = 0; k < keys.length; k++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    keys[k] = seed >>> 1;
                }
                sort.sort(keys, keys.length);
            }
        }
    }

    @Test
    void aBlendedRangesCentreIsTheMiddleOfItsBounds() {
        int floats = WorldStreamObjectsFile.VERTEX_FLOATS;
        var vertices = new float[5 * floats];
        float[][] positions = {{0, 0, 0}, {4, 2, 0}, {2, 6, 8}, {100, 100, 100}, {-100, 0, 0}};
        for (int v = 0; v < positions.length; v++) {
            System.arraycopy(positions[v], 0, vertices, v * floats, 3);
        }
        // Range 0 (blended) references vertices 0..2; range 1 (opaque) references 3, 4 and 0.
        short[] indices = {0, 1, 2, 3, 4, 0};
        int[] ranges = {0, 3, 0, BLENDED, 3, 3, 0, OPAQUE};
        var centres =
                WorldStreamObjectCulling.rangeCentres(
                        new int[] {0, 5, 0, 6, 0, 2}, ranges, vertices, indices, () -> false);
        assertArrayEquals(new float[] {2, 3, 4, 0, 0, 0}, centres);
    }
}
