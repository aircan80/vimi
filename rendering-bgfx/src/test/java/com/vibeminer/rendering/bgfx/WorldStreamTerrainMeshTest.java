package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Issue #373: one indexed mesh per tile, culled by ADT chunk. GPU- and FX-free. */
class WorldStreamTerrainMeshTest {
    /** Chunk c's 145 vertices at x = -c * 40 (8 yd cells), z = 10, as the ADT reader emits. */
    private static float[] vertices(int chunks) {
        var result = new float[chunks * 145 * 3];
        int at = 0;
        for (int chunk = 0; chunk < chunks; chunk++) {
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    result[at++] = -chunk * 40f - row * 2f;
                    result[at++] = -(col * 2 + row % 2) * 2f;
                    result[at++] = 10;
                }
            }
        }
        return result;
    }

    /** The reader's four-triangle fan per cell, skipping cells in {@code holes}. */
    private static int[] fans(int chunks, long holes) {
        var result = new java.util.ArrayList<Integer>();
        for (int chunk = 0; chunk < chunks; chunk++) {
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    if (chunk == 1 && (holes >>> (row * 8 + col) & 1) != 0) {
                        continue;
                    }
                    int a = chunk * 145 + row * 17 + col, b = a + 1, d = a + 17, e = d + 1;
                    int m = a + 9;
                    for (int index : new int[] {a, m, b, b, m, e, e, m, d, d, m, a}) {
                        result.add(index);
                    }
                }
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void eachChunkIsOneRunOfItsTrianglesInTheTileOrderHolesIncluded() {
        // Chunk 1 has two holed cells: 62 cells of 4 triangles.
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices(3), fans(3, 0b11), 3);
        var mesh = WorldStreamPackedTile.mesh(tile, null, () -> false);
        assertEquals(3 * 145, mesh.terrainVertices());
        assertEquals((64 + 62 + 64) * 12, mesh.terrainIndices());
        assertArrayEquals(new int[] {0, 768, 768, 744, 768 + 744, 768}, mesh.runs());
        // Each run's sphere bounds its own chunk: centres 40 yd apart along -x.
        var spheres = mesh.spheres();
        for (int run = 0; run < 3; run++) {
            assertEquals(-run * 40 - 16, spheres[run * 4], 1e-6);
            assertTrue(spheres[run * 4 + 3] < 30, "chunk radius " + spheres[run * 4 + 3]);
        }
        assertEquals(mesh.bytes(), 3 * 145 * 16 + mesh.terrainIndices() * 2);
    }

    @Test
    void anUnusualMeshWithTooManyRunsIsOneRun() {
        // Triangles alternating between two chunks' vertices.
        int triangles = WorldStreamPackedTile.MAX_RUNS + 10;
        var indices = new int[triangles * 3];
        for (int t = 0; t < triangles; t++) {
            int base = t % 2 == 0 ? 0 : 145;
            indices[t * 3] = base;
            indices[t * 3 + 1] = base + 1;
            indices[t * 3 + 2] = base + 17;
        }
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices(2), indices, 2);
        var mesh = WorldStreamPackedTile.mesh(tile, null, () -> false);
        assertArrayEquals(new int[] {0, triangles * 3}, mesh.runs());
    }

    @Test
    void waterCountsFollowTheTerrainsAndPlanningStopsWhenCanceled() {
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices(1), fans(1, 0), 1);
        var mesh =
                WorldStreamPackedTile.mesh(tile, WorldStreamTileWaterTest.water(3, 0), () -> false);
        assertEquals(12, mesh.waterVertices());
        assertEquals(18, mesh.waterIndices());
        assertEquals((145 + 12) * 16 + (768 + 18) * 2, mesh.bytes());
        assertThrows(
                CancellationException.class,
                () -> WorldStreamPackedTile.mesh(tile, null, () -> true));
    }

    @Test
    void visibleRunsMergeIntoContiguousIndexRanges() {
        int[] runs = {0, 30, 30, 30, 60, 30, 90, 30};
        assertArrayEquals(
                new int[] {0, 120}, BgfxWorldStreamRenderer.visibleRanges(runs, r -> true));
        assertArrayEquals(
                new int[] {0, 30, 60, 60},
                BgfxWorldStreamRenderer.visibleRanges(runs, r -> r != 1));
        assertArrayEquals(
                new int[] {30, 30}, BgfxWorldStreamRenderer.visibleRanges(runs, r -> r == 1));
        assertArrayEquals(new int[0], BgfxWorldStreamRenderer.visibleRanges(runs, r -> false));
    }

    @Test
    void chunksBehindOrBesideTheCameraAreCulled() {
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices(3), fans(3, 0), 3);
        var mesh = WorldStreamPackedTile.mesh(tile, null, () -> false);
        // Standing over chunk 1 (x -40..-72), looking along +x, level: chunk 0 is ahead and
        // chunk 2 behind.
        var view =
                WorldStreamObjectCulling.View.of(new TerrainCamera(-60, -16, 20, 0, 0), 800, 500);
        assertTrue(view.visible(mesh.spheres(), 0));
        assertTrue(view.visible(mesh.spheres(), 1), "the chunk under the camera");
        assertFalse(view.visible(mesh.spheres(), 2));
    }

    @Test
    void aCameraExactlyOnAChunkEdgeSeesBothChunks() {
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices(3), fans(3, 0), 3);
        var mesh = WorldStreamPackedTile.mesh(tile, null, () -> false);
        // Chunk 0 spans x 0..-32 and chunk 1 x -40..-72; the camera stands on chunk 0's -x edge,
        // looking along -x toward chunk 1, level and pitched down.
        for (double pitch : new double[] {0, -0.6, -1.4}) {
            var view =
                    WorldStreamObjectCulling.View.of(
                            new TerrainCamera(-32, -16, 12, Math.PI, pitch), 800, 500);
            assertTrue(view.visible(mesh.spheres(), 1), "chunk ahead, pitch " + pitch);
            // Chunk 0 is behind the camera but its sphere reaches past the camera.
            assertTrue(view.visible(mesh.spheres(), 0), "chunk at the edge, pitch " + pitch);
        }
    }

    @Test
    void theCullCutFollowsTheFrameFogCut() {
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices(3), fans(3, 0), 3);
        var mesh = WorldStreamPackedTile.mesh(tile, null, () -> false);
        // Looking along +x from 100 yd ahead of chunk 0: chunk 2's sphere is ~200 yd away.
        var camera = new TerrainCamera(-200, -16, 12, 0, 0);
        assertTrue(
                WorldStreamObjectCulling.View.of(camera, 800, 500, 2200)
                        .visible(mesh.spheres(), 0));
        assertFalse(
                WorldStreamObjectCulling.View.of(camera, 800, 500, 150).visible(mesh.spheres(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamObjectCulling.View.of(camera, 800, 500, 0));
    }

    @Test
    void meshVertexCountsFitU16Indices() {
        assertTrue(WorldStreamPackedTile.MAX_TERRAIN_VERTICES <= 65536);
        assertTrue(WorldStreamPackedTile.MAX_WATER_VERTICES <= 65536);
    }

    @Test
    void inPlaceRangesMatchAndAllocateNothing() {
        int[] runs = {0, 30, 30, 30, 60, 30, 90, 30};
        var out = new int[runs.length];
        int ints =
                BgfxWorldStreamRenderer.visibleRanges(
                        runs, new boolean[] {true, false, true, true}, out);
        assertEquals(4, ints);
        assertArrayEquals(new int[] {0, 30, 60, 60}, java.util.Arrays.copyOf(out, ints));
    }
}
