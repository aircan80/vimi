package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;

/** Issue #382: per-instance transforms and the grouping of visible placements by model. */
class WorldObjectInstancesTest {
    private static final double H = Math.sqrt(0.5);

    /** A scaled, rotated placement far from the origin, as the hot pass draws it. */
    private static final double[] PLACEMENT = {
        10_123.25, -12_456.5, 300.75, 1.5, 1, 0, 0, 0, H, -H, 0, H, H
    };

    private static final TerrainScene.Origin ORIGIN =
            new TerrainScene.Origin(10_100.5f, -12_400.25f, 299f);

    private static float[] rows(double[] placements, int p) {
        var bytes = ByteBuffer.allocate(WorldObjectInstances.STRIDE).order(ByteOrder.nativeOrder());
        WorldObjectInstances.put(placements, p, ORIGIN, bytes);
        assertEquals(WorldObjectInstances.STRIDE, bytes.position(), "one instance, three rows");
        bytes.flip();
        var rows = new float[12];
        bytes.asFloatBuffer().get(rows);
        return rows;
    }

    @Test
    void instanceRowsAreTheModelMatrixRows() {
        var model = FloatBuffer.allocate(16);
        var normal = FloatBuffer.allocate(16);
        BgfxWorldStreamRenderer.objectMatrices(PLACEMENT, 0, ORIGIN, model, normal);
        var rows = rows(PLACEMENT, 0);
        // Row r of the instance is row r of the column-major model matrix, translation included.
        var expected = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                expected[row * 4 + column] = model.get(column * 4 + row);
            }
        }
        assertArrayEquals(expected, rows);
        // The translation is the double difference cast once, as before.
        assertEquals((float) (10_123.25 - 10_100.5f), rows[3]);
    }

    @Test
    void theVertexShaderNormalMatchesTheOldNormalMatrix() {
        // world-wmo's vertex shader: n = (row . normal) / |row0|, for a uniform scale times an
        // orthonormal rotation. It must equal the rotation-only normal matrix it replaces.
        var model = FloatBuffer.allocate(16);
        var normal = FloatBuffer.allocate(16);
        BgfxWorldStreamRenderer.objectMatrices(PLACEMENT, 0, ORIGIN, model, normal);
        var rows = rows(PLACEMENT, 0);
        double[][] sources = {{0, 0, 1}, {1, 0, 0}, {0.6, -0.8, 0}, {0.2, 0.3, -0.9}};
        double scale = Math.sqrt(rows[0] * rows[0] + rows[1] * rows[1] + rows[2] * rows[2]);
        for (var n : sources) {
            for (int row = 0; row < 3; row++) {
                double shader =
                        (rows[row * 4] * n[0] + rows[row * 4 + 1] * n[1] + rows[row * 4 + 2] * n[2])
                                / scale;
                double old =
                        normal.get(row) * n[0]
                                + normal.get(4 + row) * n[1]
                                + normal.get(8 + row) * n[2];
                assertEquals(old, shader, 1e-6, "normal row " + row);
            }
        }
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
    void theColdPassModelMatrixKeepsItsNormals() {
        // The cold pass's model matrix is size times the heading rotation; dividing by a row's
        // length gives back the rotation its old normal matrix held.
        double size = 2.5, co = Math.cos(0.7), si = Math.sin(0.7);
        var model =
                FloatBuffer.wrap(
                        new float[] {
                            (float) (-size * co),
                            (float) (-size * si),
                            0,
                            0,
                            (float) (size * si),
                            (float) (-size * co),
                            0,
                            0,
                            0,
                            0,
                            (float) size,
                            0,
                            5,
                            6,
                            7,
                            1
                        });
        var bytes = ByteBuffer.allocate(WorldObjectInstances.STRIDE).order(ByteOrder.nativeOrder());
        WorldObjectInstances.putColumnMajor(model, bytes);
        bytes.flip();
        var rows = new float[12];
        bytes.asFloatBuffer().get(rows);
        double scale = Math.sqrt(rows[0] * rows[0] + rows[1] * rows[1] + rows[2] * rows[2]);
        assertEquals(size, scale, 1e-6);
        assertEquals(-co, rows[0] / scale, 1e-6);
        assertEquals(si, rows[1] / scale, 1e-6);
        assertEquals(1, rows[10] / scale, 1e-6);
        assertArrayEquals(new float[] {5, 6, 7}, new float[] {rows[3], rows[7], rows[11]});
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
        // Every resident slot can be in view, in both passes.
        assertEquals(
                BgfxWorldStreamRenderer.SLOT_COUNT
                        * WorldStreamObjectsFile.MAX_PLACEMENTS
                        * WorldObjectInstances.STRIDE
                        * 2,
                frame);
        int bgfxDefault = 6 << 20;
        assertEquals(
                Math.max(bgfxDefault, frame + (1 << 20)),
                WorldStreamNativeOwner.transientVertexBytes(bgfxDefault));
        // A larger neighbourhood at raised caps raises the budget above bgfx's default.
        int far = BgfxWorldStreamRenderer.frameInstanceBytes(26, 4160);
        assertEquals(10_383_360, far);
        int budget = WorldStreamNativeOwner.transientVertexBytes(bgfxDefault, far);
        assertTrue(budget >= far + (1 << 20), "a frame of instances fits");
        assertTrue(budget > bgfxDefault, "raised above bgfx's default");
        // Never below bgfx's own default.
        assertEquals(bgfxDefault, WorldStreamNativeOwner.transientVertexBytes(bgfxDefault, 0));
    }

    @Test
    void theSortedPassSkipsWhatTheOpaquePassDropped() {
        // Model 0: walls (opaque) and glass (blended). Model 1: glass only. Model 2: walls only.
        boolean[] blended = {true, true, false};
        boolean[] unsorted = {true, false, true};
        int[] models = {0, 0, 1, 2, 0};
        boolean[] visible = {true, true, true, true, false};
        boolean[] dropped = {false, true, true, false, false};
        // Visible, kept by the opaque pass: its glass is drawn.
        assertTrue(
                BgfxWorldStreamRenderer.sortedPassDraws(
                        blended, unsorted, models, visible, dropped, 0));
        // Dropped for lack of instance data: never its glass without its walls (#382).
        assertFalse(
                BgfxWorldStreamRenderer.sortedPassDraws(
                        blended, unsorted, models, visible, dropped, 1));
        // A glass-only model has no walls to lose; its own allocation decides.
        assertTrue(
                BgfxWorldStreamRenderer.sortedPassDraws(
                        blended, unsorted, models, visible, dropped, 2));
        // Nothing sorted to draw.
        assertFalse(
                BgfxWorldStreamRenderer.sortedPassDraws(
                        blended, unsorted, models, visible, dropped, 3));
        // Culled this frame.
        assertFalse(
                BgfxWorldStreamRenderer.sortedPassDraws(
                        blended, unsorted, models, visible, dropped, 4));
    }
}
