package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Source-free complete-window acceptance. Test allocations are not a production memory ledger. */
@Timeout(60)
class PackedTerrainPlannerAcceptanceTest {
    record XY(int x, int y) {}

    record Fixture(TerrainSourceWindow window, Map<XY, TerrainGrid> originals, ErrorWork work) {}

    private static PackedTerrainPlanner.Limits limits() {
        return new PackedTerrainPlanner.Limits(32, 9, 334080, 1769472);
    }

    private static final PackedTerrainPlanner.RingTolerances TOLERANCES =
            new PackedTerrainPlanner.RingTolerances(1, 4, 16);

    private static Fixture fixture(int anchorX, int anchorY, long holes) {
        return fixture(anchorX, anchorY, holes, false);
    }

    private static Fixture fixture(int anchorX, int anchorY, long holes, boolean peak) {
        var sources = new ArrayList<CompactTerrainSource>();
        var originals = new HashMap<XY, TerrainGrid>();
        var work = new ErrorWork(ErrorWork.MAX_ARITHMETIC, ErrorWork.MAX_PLANES);
        for (int x = Math.max(0, anchorX - 3); x <= Math.min(63, anchorX + 3); x++) {
            for (int y = Math.max(0, anchorY - 3); y <= Math.min(63, anchorY + 3); y++) {
                var chunks = new ArrayList<TerrainGrid.Chunk>();
                for (int i = 0; i < 256; i++) {
                    float ox =
                            (float)
                                    (10000
                                            + (anchorX - x) * WorldFormats.TILE_SIZE
                                            - (i / 16) * WorldFormats.TILE_SIZE / 16);
                    float oy =
                            (float)
                                    (20000
                                            + (anchorY - y) * WorldFormats.TILE_SIZE
                                            - (i % 16) * WorldFormats.TILE_SIZE / 16);
                    float[] heights = new float[145];
                    if (peak && x == anchorX - 1 && y == anchorY && i == 120) {
                        heights[9] = 100;
                    }
                    chunks.add(
                            new TerrainGrid.Chunk(
                                    i,
                                    i % 16,
                                    i / 16,
                                    0,
                                    ox,
                                    oy,
                                    0,
                                    heights,
                                    holes,
                                    TerrainGrid.ColorState.ABSENT,
                                    new byte[0]));
                }
                var grid = new TerrainGrid(chunks);
                originals.put(new XY(x, y), grid);
                sources.add(
                        CompactTerrainSource.copyOf(
                                new CompactTerrainSource.Descriptor(
                                        x,
                                        y,
                                        1,
                                        "a".repeat(32),
                                        "b".repeat(64),
                                        CompactTerrainSource.Outcome.READABLE),
                                grid,
                                work,
                                () -> false));
            }
        }
        // Canonical source ordinals must not depend on caller collection order.
        Collections.reverse(sources);
        return new Fixture(
                TerrainSourceWindow.of(anchorX, anchorY, sources), Map.copyOf(originals), work);
    }

    private static PackedTerrainPlanner.Result plan(Fixture fixture) {
        return PackedTerrainPlanner.plan(
                fixture.window, TOLERANCES, limits(), fixture.work, () -> false);
    }

    @Test
    void full49FlatWindowEmitsExactBoundedGeometryAndConformingFourEdgeFans() {
        var fixture = fixture(7, 19, 0);
        var result = plan(fixture);
        assertEquals(PackedTerrainPlanner.Status.READY, result.status());
        try (var plan = result.plan().orElseThrow()) {
            var output = new PackedPlanEmission(plan);
            assertEquals(206016, output.positions.length / 3);
            assertEquals(287680, output.indices.length / 3);
            assertEquals(6, output.batches.size());
            assertEquals(49, plan.counts().retainedSources());
            topology(fixture, plan, output, true);
            positions(fixture, plan, output);
            seams(fixture, plan, output);
            assertEquals(
                    15,
                    output.leaves.stream()
                            .mapToInt(PackedPlanEmission.Leaf::mask)
                            .reduce(0, (a, b) -> a | b));
            System.out.println(
                    "PACKED49_ACCEPTANCE flat groups="
                            + fixture.work.arithmeticUsed()
                            + " planes="
                            + fixture.work.planesUsed()
                            + " leaves="
                            + output.leaves.size());
        }
    }

    @Test
    void checkerboardHolesRemainExactAndFarthestSourcesAreOmittedWithinNineBatches() {
        var fixture = fixture(7, 19, 0xaa55aa55aa55aa55L);
        var result = plan(fixture);
        assertEquals(PackedTerrainPlanner.Status.LIMITED, result.status());
        assertEquals(PackedTerrainPlanner.Reason.GEOMETRY_CAP, result.reason());
        try (var plan = result.plan().orElseThrow()) {
            var output = new PackedPlanEmission(plan);
            topology(fixture, plan, output, false);
            positions(fixture, plan, output);
            assertEquals(49, plan.counts().retainedSources() + plan.counts().omittedSources());
            assertTrue(output.batches.size() <= 9);
            assertTrue(output.positions.length / 3 <= 334080);
            assertTrue(output.indices.length <= 1769472);
            Set<Integer> retained = new HashSet<>();
            output.leaves.forEach(l -> retained.add(l.source()));
            int maxRetained = 0, minOmitted = 4;
            for (int source = 0; source < 49; source++) {
                var d = plan.descriptor(source);
                int ring = Math.max(Math.abs(d.x() - 7), Math.abs(d.y() - 19));
                if (retained.contains(source)) {
                    maxRetained = Math.max(maxRetained, ring);
                } else {
                    minOmitted = Math.min(minOmitted, ring);
                }
                if (ring <= 1) {
                    assertTrue(retained.contains(source), "Nearest nine sources survive");
                }
            }
            assertTrue(maxRetained <= minOmitted, "Farther ring omitted before nearer ring");
        }
    }

    @Test
    void clippedMapCornerWindowUsesCanonicalDescriptorsAndLimitsNeverPublishPartialPlans() {
        var fixture = fixture(0, 0, 0);
        var result = plan(fixture);
        assertEquals(PackedTerrainPlanner.Status.READY, result.status());
        try (var plan = result.plan().orElseThrow()) {
            assertEquals(16, plan.counts().retainedSources());
            var output = new PackedPlanEmission(plan);
            topology(fixture, plan, output, true);
            positions(fixture, plan, output);
        }
        var edgeFixture = fixture(0, 19, 0);
        var edgeResult = plan(edgeFixture);
        assertEquals(PackedTerrainPlanner.Status.READY, edgeResult.status());
        assertEquals(4, edgeFixture.window.rows());
        assertEquals(7, edgeFixture.window.columns());
        try (var plan = edgeResult.plan().orElseThrow()) {
            assertEquals(28, plan.counts().retainedSources());
            var output = new PackedPlanEmission(plan);
            topology(edgeFixture, plan, output, true);
            positions(edgeFixture, plan, output);
        }
        var refused =
                PackedTerrainPlanner.plan(
                        fixture.window, TOLERANCES, limits(), new ErrorWork(1, 1), () -> false);
        assertEquals(PackedTerrainPlanner.Reason.WORK_BUDGET, refused.reason());
        assertTrue(refused.plan().isEmpty());
        var planeWork = new ErrorWork(ErrorWork.MAX_ARITHMETIC, 1);
        var planeRefusal =
                PackedTerrainPlanner.plan(
                        fixture.window, TOLERANCES, limits(), planeWork, () -> false);
        assertEquals(PackedTerrainPlanner.Reason.WORK_BUDGET, planeRefusal.reason());
        assertTrue(planeRefusal.plan().isEmpty());
        assertEquals(1, planeWork.planesUsed());
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        PackedTerrainPlanner.plan(
                                fixture.window,
                                TOLERANCES,
                                limits(),
                                new ErrorWork(ErrorWork.MAX_ARITHMETIC, ErrorWork.MAX_PLANES),
                                () -> counter.incrementAndGet() > 2000));
    }

    @Test
    void isolatedPeakRefinesAndEveryFinalFanInItsChunkPassesIndependentNativeReference() {
        var fixture = fixture(7, 19, 0, true);
        var result = plan(fixture);
        assertEquals(PackedTerrainPlanner.Status.READY, result.status());
        try (var plan = result.plan().orElseThrow()) {
            var output = new PackedPlanEmission(plan);
            topology(fixture, plan, output, true);
            int checked = 0;
            for (var leaf : output.leaves) {
                var d = plan.descriptor(leaf.source());
                if (d.x() != 6 || d.y() != 19 || leaf.chunk() != 120) {
                    continue;
                }
                if (leaf.row() == 0 && leaf.column() == 0) {
                    assertEquals(1, leaf.size(), "Peak cannot survive coarse interpolation");
                }
                var grid = fixture.originals.get(new XY(d.x(), d.y())).chunks().get(120);
                var fine = new ArrayList<TerrainErrorReference.NativeTriangle>();
                for (int row = leaf.row(); row < leaf.row() + leaf.size(); row++) {
                    for (int col = leaf.column(); col < leaf.column() + leaf.size(); col++) {
                        var a = nativeVertex(grid.position(row * 2, col));
                        var b = nativeVertex(grid.position(row * 2, col + 1));
                        var e = nativeVertex(grid.position(row * 2 + 2, col + 1));
                        var f = nativeVertex(grid.position(row * 2 + 2, col));
                        var m = nativeVertex(grid.position(row * 2 + 1, col));
                        fine.add(new TerrainErrorReference.NativeTriangle(a, m, b));
                        fine.add(new TerrainErrorReference.NativeTriangle(b, m, e));
                        fine.add(new TerrainErrorReference.NativeTriangle(e, m, f));
                        fine.add(new TerrainErrorReference.NativeTriangle(f, m, a));
                    }
                }
                var coarse = new ArrayList<TerrainErrorReference.NativeTriangle>();
                for (int at = leaf.firstIndex();
                        at < leaf.firstIndex() + leaf.indexCount();
                        at += 3) {
                    coarse.add(
                            new TerrainErrorReference.NativeTriangle(
                                    vertex(output, at),
                                    vertex(output, at + 1),
                                    vertex(output, at + 2)));
                }
                double error =
                        new TerrainErrorReference(5000000, () -> false)
                                .compareNative(fine, coarse)
                                .upperBound();
                assertTrue(error <= 1, "Every emitted final fan meets its ring1 tolerance");
                checked++;
            }
            assertTrue(checked > 16, "The isolated peak triggers additional final leaves");
        }
        var passFixture = fixture(7, 19, 0, true);
        var passRefused =
                PackedTerrainPlanner.plan(
                        passFixture.window,
                        TOLERANCES,
                        new PackedTerrainPlanner.Limits(2, 9, 334080, 1769472),
                        passFixture.work,
                        () -> false);
        assertEquals(PackedTerrainPlanner.Reason.PASS_LIMIT, passRefused.reason());
        assertTrue(passRefused.plan().isEmpty());
    }

    private static TerrainErrorReference.NativeVertex nativeVertex(TerrainGrid.Vec3 p) {
        return new TerrainErrorReference.NativeVertex(p.x(), p.y(), p.z());
    }

    private static TerrainErrorReference.NativeVertex vertex(PackedPlanEmission output, int at) {
        int offset = output.indices[at] * 3;
        return new TerrainErrorReference.NativeVertex(
                output.positions[offset],
                output.positions[offset + 1],
                output.positions[offset + 2]);
    }

    private static void positions(
            Fixture fixture, PackedTerrainPlanner.ScopedPlan plan, PackedPlanEmission output) {
        for (int i = 0; i < output.sourceSamples.length; i++) {
            int ref = output.sourceSamples[i],
                    source = ref / 37120,
                    chunk = (ref % 37120) / 145,
                    sample = ref % 145;
            var d = plan.descriptor(source);
            var grid = fixture.originals.get(new XY(d.x(), d.y()));
            int outer = sample / 17,
                    within = sample % 17,
                    row = outer * 2 + (within >= 9 ? 1 : 0),
                    col = within >= 9 ? within - 9 : within;
            var p = grid.chunks().get(chunk).position(row, col);
            assertEquals(
                    Float.floatToRawIntBits(p.x()),
                    Float.floatToRawIntBits(output.positions[i * 3]));
            assertEquals(
                    Float.floatToRawIntBits(p.y()),
                    Float.floatToRawIntBits(output.positions[i * 3 + 1]));
            assertEquals(
                    Float.floatToRawIntBits(p.z()),
                    Float.floatToRawIntBits(output.positions[i * 3 + 2]));
        }
    }

    private static void topology(
            Fixture fixture,
            PackedTerrainPlanner.ScopedPlan plan,
            PackedPlanEmission output,
            boolean exactMasks) {
        int width = fixture.window.columns() * 128, height = fixture.window.rows() * 128;
        byte[] levels = new byte[width * height];
        Set<Integer> retained = new HashSet<>();
        for (var leaf : output.leaves) {
            retained.add(leaf.source());
            var d = plan.descriptor(leaf.source());
            int row =
                    (d.x() - fixture.window.firstX()) * 128 + (leaf.chunk() / 16) * 8 + leaf.row();
            int col =
                    (d.y() - fixture.window.firstY()) * 128
                            + (leaf.chunk() % 16) * 8
                            + leaf.column();
            for (int r = 0; r < leaf.size(); r++) {
                for (int c = 0; c < leaf.size(); c++) {
                    int at = (row + r) * width + col + c;
                    assertEquals(0, levels[at], "Fine cell covered once");
                    levels[at] = (byte) leaf.size();
                }
            }
        }
        for (int source : retained) {
            var d = plan.descriptor(source);
            var grid = fixture.originals.get(new XY(d.x(), d.y()));
            for (int chunk = 0; chunk < 256; chunk++) {
                for (int r = 0; r < 8; r++) {
                    for (int c = 0; c < 8; c++) {
                        int row = (d.x() - fixture.window.firstX()) * 128 + (chunk / 16) * 8 + r,
                                col =
                                        (d.y() - fixture.window.firstY()) * 128
                                                + (chunk % 16) * 8
                                                + c;
                        assertEquals(
                                grid.chunks().get(chunk).hole(r, c),
                                levels[row * width + col] == 0,
                                "Exactly source holes are uncovered");
                    }
                }
            }
        }
        for (var leaf : output.leaves) {
            var d = plan.descriptor(leaf.source());
            int row =
                    (d.x() - fixture.window.firstX()) * 128 + (leaf.chunk() / 16) * 8 + leaf.row();
            int
                    col =
                            (d.y() - fixture.window.firstY()) * 128
                                    + (leaf.chunk() % 16) * 8
                                    + leaf.column(),
                    expected = 0;
            for (int edge = 0; edge < 4; edge++) {
                for (int along = 0; along < leaf.size(); along++) {
                    int r = edge == 0 ? row - 1 : edge == 2 ? row + leaf.size() : row + along;
                    int c = edge == 1 ? col + leaf.size() : edge == 3 ? col - 1 : col + along;
                    if (r < 0 || c < 0 || r >= height || c >= width) {
                        continue;
                    }
                    int other = levels[r * width + c];
                    if (other == 0) {
                        continue;
                    }
                    assertTrue(
                            leaf.size() <= other * 2 && other <= leaf.size() * 2,
                            "2:1 adjacent spacing");
                    if (other < leaf.size()) {
                        expected |= 1 << edge;
                    }
                }
            }
            assertEquals(
                    expected,
                    expected & leaf.mask(),
                    "Every finer neighbor gets a matching midpoint");
            if (exactMasks) {
                assertEquals(expected, leaf.mask(), "No unexplained split on complete window");
            }
        }
        areas(fixture, plan, output);
    }

    private static long determinant(PackedPlanEmission output, int at) {
        int a = output.sourceSamples[output.indices[at]],
                b = output.sourceSamples[output.indices[at + 1]],
                c = output.sourceSamples[output.indices[at + 2]];
        assertEquals(a / 145, b / 145, "Triangle cannot cross an original source chunk");
        assertEquals(a / 145, c / 145, "Triangle cannot cross an original source chunk");
        int[] x = new int[3], y = new int[3];
        int n = 0;
        for (int ref : new int[] {a, b, c}) {
            int sample = ref % 145, block = sample / 17, within = sample % 17;
            x[n] = block * 2 + (within >= 9 ? 1 : 0);
            y[n] = within >= 9 ? (within - 9) * 2 + 1 : within * 2;
            n++;
        }
        long determinant =
                (long) (x[1] - x[0]) * (y[2] - y[0]) - (long) (y[1] - y[0]) * (x[2] - x[0]);
        assertTrue(
                determinant > 0,
                "Every emitted fan triangle has positive nonzero source-domain area");
        return determinant;
    }

    private static void areas(
            Fixture fixture, PackedTerrainPlanner.ScopedPlan plan, PackedPlanEmission output) {
        var sourceChunkArea = new HashMap<Integer, Long>();
        for (var leaf : output.leaves) {
            long twiceArea = 0;
            for (int at = leaf.firstIndex(); at < leaf.firstIndex() + leaf.indexCount(); at += 3) {
                long det = determinant(output, at);
                twiceArea += det;
                int key = output.sourceSamples[output.indices[at]] / 145;
                sourceChunkArea.merge(key, det, Long::sum);
            }
            assertEquals(
                    8L * leaf.size() * leaf.size(),
                    twiceArea,
                    "Final fan covers its entire leaf exactly in source domain");
        }
        for (var entry : sourceChunkArea.entrySet()) {
            int source = entry.getKey() / 256, chunk = entry.getKey() % 256;
            var d = plan.descriptor(source);
            long holes = fixture.originals.get(new XY(d.x(), d.y())).chunks().get(chunk).holes();
            assertEquals(
                    8L * (64 - Long.bitCount(holes)),
                    entry.getValue().longValue(),
                    "Exact source-hole area, no filled or expanded holes");
        }
    }

    private static void seams(
            Fixture fixture, PackedTerrainPlanner.ScopedPlan plan, PackedPlanEmission output) {
        // Compare selected seam samples with the original fine boundary, preserving float gaps.
        var selected = new HashMap<Integer, Set<Integer>>();
        for (int ref : output.sourceSamples) {
            selected.computeIfAbsent(ref / 145, k -> new HashSet<>()).add(ref % 145);
        }
        var ordinalByCoordinate = new HashMap<XY, Integer>();
        for (int source = 0; source < fixture.window.sources().size(); source++) {
            var d = plan.descriptor(source);
            ordinalByCoordinate.put(new XY(d.x(), d.y()), source);
        }
        int measuredGaps = 0, tileEdges = 0;
        for (int source = 0; source < fixture.window.sources().size(); source++) {
            var d = plan.descriptor(source);
            var grid = fixture.originals.get(new XY(d.x(), d.y()));
            for (int chunk = 0; chunk < 256; chunk++) {
                for (int axis = 0; axis < 2; axis++) {
                    int next = axis == 0 ? chunk + 16 : chunk + 1, nextSource = source;
                    boolean tileEdge = axis == 0 ? chunk / 16 == 15 : chunk % 16 == 15;
                    TerrainGrid nextGrid = grid;
                    if (tileEdge) {
                        var coordinate =
                                new XY(d.x() + (axis == 0 ? 1 : 0), d.y() + (axis == 1 ? 1 : 0));
                        var neighbor = ordinalByCoordinate.get(coordinate);
                        if (neighbor == null) {
                            continue;
                        }
                        nextSource = neighbor;
                        nextGrid = fixture.originals.get(coordinate);
                        next = axis == 0 ? chunk % 16 : (chunk / 16) * 16;
                        tileEdges++;
                    }
                    var left = selected.get(source * 256 + chunk);
                    var right = selected.get(nextSource * 256 + next);
                    for (int k = 0; k <= 8; k++) {
                        int sa = axis == 0 ? 136 + k : k * 17 + 8, sb = axis == 0 ? k : k * 17;
                        assertEquals(
                                left.contains(sa),
                                right.contains(sb),
                                "Conforming MCNK and source-tile boundary subdivisions");
                        if (left.contains(sa)) {
                            var pa =
                                    grid.chunks()
                                            .get(chunk)
                                            .position(axis == 0 ? 16 : k * 2, axis == 0 ? k : 8);
                            var pb =
                                    nextGrid.chunks()
                                            .get(next)
                                            .position(axis == 0 ? 0 : k * 2, axis == 0 ? k : 0);
                            if (pa.x() != pb.x() || pa.y() != pb.y() || pa.z() != pb.z()) {
                                measuredGaps++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(1344, tileEdges, "Both source-axis seams across all 7x7 neighbors");
        assertTrue(
                measuredGaps > 0,
                "Quantized native seams remain observable instead of silently welded");
    }
}
