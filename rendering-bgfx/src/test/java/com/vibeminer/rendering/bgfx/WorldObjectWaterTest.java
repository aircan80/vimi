package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.vibeminer.formats.world.WmoLiquid;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

/** Building water as the child builds it: runs, the tile cap, and placed corners. */
final class WorldObjectWaterTest {
    static final double U = WmoLiquid.UNIT;

    /** A mesh of the current model with an {@code xv} x {@code yv} liquid, every tile live. */
    static void wetMesh(
            WorldStreamTileWriter.Exterior.Builder builder, int xv, int yv, float height) {
        builder.mesh(
                FloatBuffer.wrap(WorldStreamObjectsFileTest.small().data().vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0});
        var heights = new float[xv * yv];
        java.util.Arrays.fill(heights, height);
        var live = new boolean[(xv - 1) * (yv - 1)];
        java.util.Arrays.fill(live, true);
        builder.liquid(
                WorldStreamTileWriter.Exterior.Builder.LIQUID_RIVER,
                0,
                xv,
                yv,
                0,
                0,
                0,
                heights,
                live);
    }

    static WorldStreamTileWriter.Exterior.Builder builder() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(
                2, 2, WorldStreamObjectsFileTest.chain(2, 2, java.nio.ByteBuffer.allocate(16)));
        return builder;
    }

    @Test
    void aSetWithoutLiquidsHasNoWater() {
        var builder = builder();
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(WorldStreamObjectsFileTest.small().data().vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        assertSame(
                WorldObjectWater.NONE, WorldObjectWater.build(builder.build().data(), () -> false));
    }

    @Test
    void runsCarryTheirPlacementAndMeshAndTheTileCapCountsWhatIsLeftOut() {
        var builder = builder();
        // Model 0: mesh 0 a liquid over the tile cap (300 x 300 vertices, 89,401 tiles), mesh 1 a
        // small one (2 tiles). Model 1: no liquid. Model 2: mesh 3 a small one.
        builder.model();
        wetMesh(builder, 300, 300, 1);
        wetMesh(builder, 3, 2, 2);
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(WorldStreamObjectsFileTest.small().data().vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0});
        builder.model();
        wetMesh(builder, 2, 2, 3);
        builder.placement(1, 0, 0, 0, 1, 1, 0);
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        builder.placement(2, 0, 0, 0, 1, 1, 0);
        builder.placement(0, 50, 0, 0, 1, 1, 0);
        var water = WorldObjectWater.build(builder.build().data(), () -> false);
        try {
            assertEquals(2, water.dropped, "the big liquid of both model-0 placements is left out");
            assertEquals(3, water.runCount());
            // Per run: placement, mesh.
            int[][] runs = new int[3][];
            for (int r = 0; r < 3; r++) {
                runs[r] = new int[] {water.runs[r * 4 + 2], water.runs[r * 4 + 3]};
            }
            assertArrayEquals(new int[] {1, 1}, runs[0]);
            assertArrayEquals(new int[] {2, 3}, runs[1]);
            assertArrayEquals(new int[] {3, 1}, runs[2]);
            assertEquals(2 + 1 + 2, water.tiles);
            // Index ranges follow each other.
            assertEquals(0, water.runs[0]);
            assertEquals(12, water.runs[1]);
            assertEquals(12, water.runs[4]);
            assertEquals(6, water.runs[5]);
        } finally {
            water.free();
        }
        assertNull(water.vertices, "freed");
    }

    @Test
    void aTransformedPlacementsCornersAreItsLiquidThroughTheTransform() {
        var builder = builder();
        builder.model();
        wetMesh(builder, 2, 2, 4);
        // Scale 3, a quarter turn: local (a, b, h) -> (10 + 3 * -b, 20 + 3 * a, 30 + 3 * h).
        builder.placement(0, 10, 20, 30, 3, new double[] {0, -1, 0, 1, 0, 0, 0, 0, 1});
        var water = WorldObjectWater.build(builder.build().data(), () -> false);
        try {
            int stride = WorldStreamPackedTile.VERTEX_BYTES;
            double[][] local = {{0, 0}, {U, 0}, {0, U}, {U, U}};
            for (int c = 0; c < 4; c++) {
                double a = local[c][0], b = local[c][1];
                assertEquals(10 - 3 * b, water.vertices.getFloat(c * stride), 1e-3, "x of " + c);
                assertEquals(
                        20 + 3 * a, water.vertices.getFloat(c * stride + 4), 1e-3, "y of " + c);
                assertEquals(
                        30 + 3 * 4, water.vertices.getFloat(c * stride + 8), 1e-3, "z of " + c);
            }
        } finally {
            water.free();
        }
    }
}
