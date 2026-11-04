package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import java.util.Arrays;

/** Explicit source-free schedule diagnostic; not part of CI and not a shipping work policy. */
public final class TerrainLodErrorScheduleProof {
    private TerrainLodErrorScheduleProof() {}

    public static void main(String[] args) {
        float[] heights = new float[145];
        Arrays.fill(heights, 3);
        var source =
                new TerrainGrid.Chunk(
                        0,
                        0,
                        0,
                        0,
                        1_000_000,
                        1_000_000,
                        0,
                        heights,
                        0,
                        TerrainGrid.ColorState.ABSENT,
                        new byte[0]);
        var work = new ErrorWork(ErrorWork.MAX_ARITHMETIC, ErrorWork.MAX_PLANES);
        long begin = System.nanoTime();
        int leaves = 0;
        for (int tileX = -3; tileX <= 3; tileX++) {
            for (int tileY = -3; tileY <= 3; tileY++) {
                int ring = Math.max(Math.abs(tileX), Math.abs(tileY)), size = 1 << ring;
                for (int chunk = 0; chunk < 256; chunk++) {
                    for (int row = 0; row < 8; row += size) {
                        for (int column = 0; column < 8; column += size) {
                            var result =
                                    TerrainLodError.evaluate(
                                            CheckedLeafPlan.create(source, row, column, size, 0),
                                            work,
                                            () -> false);
                            if (!result.certifies(ring == 0 ? 0 : 1)) {
                                throw new AssertionError(
                                        "Flat schedule refused at "
                                                + tileX
                                                + ","
                                                + tileY
                                                + " "
                                                + result.reason());
                            }
                            leaves++;
                        }
                    }
                }
            }
        }
        // Independent ring arithmetic: center256*64; 8*256*16;16*256*4;24*256.
        if (leaves != 71680
                || work.planesUsed() != 8_175_616
                || work.arithmeticUsed() != 63_338_496) {
            throw new AssertionError(
                    "Unexpected charged schedule "
                            + leaves
                            + " "
                            + work.planesUsed()
                            + " "
                            + work.arithmeticUsed());
        }
        System.out.println(
                "SOURCE_FREE_LOD_SCHEDULE tiles=49 chunksPerTile=256 leaves="
                        + leaves
                        + " planeEvents="
                        + work.planesUsed()
                        + " arithmeticGroups="
                        + work.arithmeticUsed()
                        + " elapsedMs="
                        + (System.nanoTime() - begin) / 1_000_000.0
                        + " flatOnly=true splitMask=0 sourceIO=false geometryAllocated=false shippingPolicy=false");
    }
}
