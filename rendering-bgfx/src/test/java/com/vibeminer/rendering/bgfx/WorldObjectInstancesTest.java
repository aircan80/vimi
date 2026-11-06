package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.rendering.world.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;

/** Issue #382: per-instance transforms and grouping of visible placements by model. */
class WorldObjectInstancesTest {
    @Test
    void instanceRowsAreTheModelMatrixRows() {
        double h = Math.sqrt(0.5);
        var placements =
                new double[] {10_123.25, -12_456.5, 300.75, 1.5, 1, 0, 0, 0, h, -h, 0, h, h};
        var origin = new TerrainScene.Origin(10_100.5f, -12_400.25f, 299f);
        var model = FloatBuffer.allocate(16);
        var normal = FloatBuffer.allocate(16);
        BgfxWorldStreamRenderer.objectMatrices(placements, 0, origin, model, normal);
        var bytes = ByteBuffer.allocate(WorldObjectInstances.STRIDE).order(ByteOrder.nativeOrder());
        WorldObjectInstances.put(placements, 0, origin, bytes);
        bytes.flip();
        var rows = new float[12];
        bytes.asFloatBuffer().get(rows);
        // Row r of the instance is row r of the column-major model matrix, translation included.
        var expected = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                expected[row * 4 + column] = model.get(column * 4 + row);
            }
        }
        assertArrayEquals(expected, rows);
        // The translation is the double difference cast once.
        assertEquals((float) (10_123.25 - 10_100.5f), rows[3]);
    }

    @Test
    void columnMajorMatricesBecomeTheSameRows() {
        var model = FloatBuffer.allocate(16);
        for (int i = 0; i < 16; i++) {
            model.put(i, i + 1);
        }
        var bytes = ByteBuffer.allocate(WorldObjectInstances.STRIDE).order(ByteOrder.nativeOrder());
        WorldObjectInstances.putColumnMajor(model, bytes);
        bytes.flip();
        var rows = new float[12];
        bytes.asFloatBuffer().get(rows);
        assertArrayEquals(new float[] {1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15}, rows);
    }

    @Test
    void visiblePlacementsGroupByModelInAscendingOrder() {
        int[] placementModels = {2, 0, 2, 1, 0, 2, 1};
        boolean[] visible = {true, true, false, true, true, true, false};
        var starts = new int[4];
        var order = new int[placementModels.length];
        int total = WorldObjectInstances.groupByModel(placementModels, visible, 3, starts, order);
        assertEquals(5, total);
        assertArrayEquals(new int[] {0, 2, 3, 5}, starts);
        assertArrayEquals(new int[] {1, 4, 3, 0, 5}, java.util.Arrays.copyOf(order, total));
        // Reused scratch: a second call with nothing visible resets it.
        assertEquals(
                0,
                WorldObjectInstances.groupByModel(
                        placementModels, new boolean[7], 3, starts, order));
        assertArrayEquals(new int[] {0, 0, 0, 0}, starts);
    }

    @Test
    void transientBudgetHoldsAFrameOfInstancesAtTheCaps() {
        int frame = BgfxWorldStreamRenderer.MAX_FRAME_INSTANCE_BYTES;
        assertEquals(
                9 * WorldStreamObjectsFile.MAX_PLACEMENTS * WorldObjectInstances.STRIDE * 2, frame);
        int bgfxDefault = 6 << 20;
        // Never below bgfx's default, and always a whole frame of instances plus 1 MiB.
        assertEquals(
                Math.max(bgfxDefault, frame + (1 << 20)),
                WorldStreamNativeOwner.transientVertexBytes(bgfxDefault));
        assertEquals(frame + (1 << 20), WorldStreamNativeOwner.transientVertexBytes(0));
        // At #440's raised caps (4,160 placements per tile) a frame needs about 3.6 MB: with the
        // extra MiB it still fits bgfx's default budget, and the init raises it otherwise.
        int raised = 9 * 4160 * WorldObjectInstances.STRIDE * 2;
        org.junit.jupiter.api.Assertions.assertTrue(
                raised + (1 << 20) <= bgfxDefault, "raised caps fit");
    }
}
