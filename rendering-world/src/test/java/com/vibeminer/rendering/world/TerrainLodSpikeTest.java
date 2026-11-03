package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerrainLodSpikeTest {
    private static TerrainLodSpike mixed(boolean hole) {
        return new TerrainLodSpike(
                8,
                16,
                (r, c) -> c < 8 ? 1 : 8,
                (r, c) -> hole && (r == 3 && c == 11 || r == 4 && c == 12),
                () -> false);
    }

    @Test
    void coarseFineSharedEdgeUsesIdenticalSourceHeightsAndNoTJunctions() {
        var plan = mixed(false);
        assertEquals(2, plan.maximumNeighborRatio());
        var mesh = plan.mesh(1000, 2000, (r, c) -> Math.sin(r) * 7 + c * c * .2);
        Set<String> left = new HashSet<>(), right = new HashSet<>();
        for (int i = 0; i < mesh.indices().length; i += 3) {
            int[] ids = {mesh.indices()[i], mesh.indices()[i + 1], mesh.indices()[i + 2]};
            double side = 0;
            for (int id : ids) {
                side += col(mesh, id);
            }
            for (int edge = 0; edge < 3; edge++) {
                int a = ids[edge], b = ids[(edge + 1) % 3];
                if (col(mesh, a) == 16 && col(mesh, b) == 16) {
                    int lo = Math.min(row(mesh, a), row(mesh, b)),
                            hi = Math.max(row(mesh, a), row(mesh, b));
                    String signature =
                            lo
                                    + ":"
                                    + hi
                                    + ":"
                                    + Float.floatToIntBits(mesh.xyz()[a * 3 + 2])
                                    + ":"
                                    + Float.floatToIntBits(mesh.xyz()[b * 3 + 2]);
                    // Normalize orientation separately from endpoint heights.
                    int low = row(mesh, a) <= row(mesh, b) ? a : b, high = low == a ? b : a;
                    signature =
                            lo
                                    + ":"
                                    + hi
                                    + ":"
                                    + Float.floatToIntBits(mesh.xyz()[low * 3 + 2])
                                    + ":"
                                    + Float.floatToIntBits(mesh.xyz()[high * 3 + 2]);
                    (side < 48 ? left : right).add(signature);
                    assertEquals(2, hi - lo, "Near full-resolution source edges remain exact");
                }
            }
        }
        assertEquals(8, left.size());
        assertEquals(left, right, "No long coarse edge crossing a fine edge midpoint");
        assertTrue(mesh.count().triangles() < 2 * 256);
    }

    @Test
    void holesAreExactCellsNotExpandedBlocksOrFilledFansAndAreaIsConserved() {
        var plan = mixed(true);
        var mesh = plan.mesh(1000, 2000, (r, c) -> r + c);
        double area = 0;
        for (int i = 0; i < mesh.indices().length; i += 3) {
            int a = mesh.indices()[i], b = mesh.indices()[i + 1], c = mesh.indices()[i + 2];
            double twice =
                    (row(mesh, b) - row(mesh, a)) * (col(mesh, c) - col(mesh, a))
                            - (col(mesh, b) - col(mesh, a)) * (row(mesh, c) - row(mesh, a));
            assertTrue(twice != 0);
            area += Math.abs(twice) / 8.0;
        }
        assertEquals(8 * 16 - 2, area, 1e-8);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 16; c++) {
                boolean covered = false;
                for (int at = 0; at < mesh.indices().length; at += 3) {
                    int a = mesh.indices()[at],
                            b = mesh.indices()[at + 1],
                            d = mesh.indices()[at + 2];
                    covered |=
                            inside(
                                    r * 2 + 1,
                                    c * 2 + 1,
                                    row(mesh, a),
                                    col(mesh, a),
                                    row(mesh, b),
                                    col(mesh, b),
                                    row(mesh, d),
                                    col(mesh, d));
                }
                assertEquals(!(r == 3 && c == 11 || r == 4 && c == 12), covered, r + "," + c);
            }
        }
        assertTrue(plan.maximumNeighborRatio() <= 2);
    }

    @Test
    void sevenBySevenCountsFitExistingGeometryCapsWithoutRetaining49FullMeshes() {
        int side = 7 * 128;
        var plan =
                new TerrainLodSpike(
                        side,
                        side,
                        (r, c) -> {
                            int ring = Math.max(Math.abs(r / 128 - 3), Math.abs(c / 128 - 3));
                            return 1 << ring;
                        },
                        (r, c) -> false,
                        () -> false);
        var count = plan.count();
        assertTrue(plan.planningBytes() < 1L << 20);
        assertTrue(count.vertices() <= TerrainLodSpike.MAX_VERTICES);
        assertTrue(count.triangles() <= TerrainScene.MAX_TRIANGLES);
        assertTrue(count.triangles() >= 286720 && count.triangles() < 300000);
        var mesh = plan.mesh(10000, 12000, (r, c) -> .5 * r + .25 * c);
        assertEquals(count.vertices() * 3, mesh.xyz().length);
        assertEquals(count.triangles() * 3, mesh.indices().length);
        assertTrue((long) mesh.xyz().length * 4 + (long) mesh.indices().length * 4 <= 11_086_848);
        assertEquals(2, plan.maximumNeighborRatio());
        System.out.println(
                "LOD_SPIKE 7x7 vertices="
                        + count.vertices()
                        + " triangles="
                        + count.triangles()
                        + " planningBytes="
                        + plan.planningBytes()
                        + " passes="
                        + count.balancePasses());
    }

    @Test
    void checkerboardHoleWorstCaseRefusesBeforeGeometryAllocationInsteadOfRaisingCaps() {
        var plan =
                new TerrainLodSpike(
                        7 * 128, 7 * 128, (r, c) -> 8, (r, c) -> (r + c) % 2 == 0, () -> false);
        assertTrue(plan.count().triangles() > TerrainScene.MAX_TRIANGLES);
        assertThrows(TerrainLodSpike.Refused.class, () -> plan.mesh(1000, 2000, (r, c) -> 0));
    }

    @Test
    void sourceSamplesKeepNativeAxesAndBoundsAndCanceledPlanningStops() {
        var plan = new TerrainLodSpike(8, 8, (r, c) -> 1, (r, c) -> false, () -> false);
        var mesh = plan.mesh(1000, 2000, (r, c) -> r * 10 + c);
        for (int id = 0; id < mesh.xyz().length / 3; id++) {
            assertEquals(row(mesh, id) * 5 + col(mesh, id) * .5, mesh.xyz()[id * 3 + 2], 1e-4);
        }
        assertEquals(145, mesh.count().vertices());
        assertEquals(256, mesh.count().triangles());
        assertThrows(
                TerrainLodSpike.Refused.class, () -> plan.mesh(1000, 2000, (r, c) -> Double.NaN));
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> new TerrainLodSpike(8, 8, (r, c) -> 8, (r, c) -> false, () -> true));
        assertFalse(mesh.indices().length == 0);
    }

    private static int row(TerrainLodSpike.Mesh mesh, int id) {
        return (int) Math.round((1000 - mesh.xyz()[id * 3]) / TerrainLodSpike.UNIT * 2);
    }

    private static int col(TerrainLodSpike.Mesh mesh, int id) {
        return (int) Math.round((2000 - mesh.xyz()[id * 3 + 1]) / TerrainLodSpike.UNIT * 2);
    }

    private static double edge(double x, double y, double ax, double ay, double bx, double by) {
        return (x - ax) * (by - ay) - (y - ay) * (bx - ax);
    }

    private static boolean inside(
            double x, double y, double ax, double ay, double bx, double by, double cx, double cy) {
        double a = edge(x, y, ax, ay, bx, by),
                b = edge(x, y, bx, by, cx, cy),
                c = edge(x, y, cx, cy, ax, ay);
        return a >= 0 && b >= 0 && c >= 0 || a <= 0 && b <= 0 && c <= 0;
    }
}
