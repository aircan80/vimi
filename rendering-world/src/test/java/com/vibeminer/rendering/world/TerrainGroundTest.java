package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainGroundTest {
    static final float ORIGIN_X = 123.125f, ORIGIN_Y = -800.25f;

    /** Authored ADT18 root with both hole encodings and non-planar, chunk-specific heights. */
    static byte[] root(boolean holes) {
        int chunkBytes = 8 + 128 + 8 + 580;
        var bytes = ByteBuffer.allocate(12 + 256 * chunkBytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x4d564552).putInt(4).putInt(18);
        for (int i = 0; i < 256; i++) {
            bytes.putInt(0x4d434e4b).putInt(chunkBytes - 8);
            int start = bytes.position();
            int x = i % 16, y = i / 16;
            boolean high = i % 2 == 0;
            bytes.putInt(start, high ? 0x10000 : 0);
            bytes.putInt(start + 4, x).putInt(start + 8, y);
            bytes.putLong(start + 20, holes && high && i % 3 == 0 ? 1L << (i % 64) : 0);
            bytes.putShort(start + 60, (short) (holes && !high && i % 5 == 0 ? 1 << (i % 16) : 0));
            bytes.putFloat(start + 104, (float) (ORIGIN_X - y * WorldFormats.TILE_SIZE / 16));
            bytes.putFloat(start + 108, (float) (ORIGIN_Y - x * WorldFormats.TILE_SIZE / 16));
            bytes.putFloat(start + 112, 50.25f);
            bytes.position(start + 128);
            bytes.putInt(0x4d435654).putInt(580);
            for (int sample = 0; sample < 145; sample++) {
                bytes.putFloat(i / 8f + (sample * 37 % 23) / 4f);
            }
        }
        return bytes.array();
    }

    static TerrainScene.Tile tile(boolean holes) {
        return new TerrainScene.Tile(3, 4, WorldFormats.adt(root(holes), () -> false));
    }

    @Test
    void everyRenderedTriangleInteriorMatchesItsOwnPlane() {
        var source = tile(true);
        var ground = TerrainGround.of(List.of(TerrainGround.Tile.of(source)));
        FloatBuffer v = source.vertices();
        IntBuffer t = source.indices();
        int checked = 0;
        for (int i = 0; i < t.limit(); i += 3) {
            int a = t.get(i) * 3, b = t.get(i + 1) * 3, c = t.get(i + 2) * 3;
            // Independent oracle: the centroid height is the mean of this triangle's corners.
            double x = (v.get(a) + v.get(b) + v.get(c)) / 3,
                    y = (v.get(a + 1) + v.get(b + 1) + v.get(c + 1)) / 3,
                    z = (v.get(a + 2) + v.get(b + 2) + v.get(c + 2)) / 3;
            assertEquals(z, ground.heightAt(x, y), 1e-3, "triangle " + i / 3);
            checked++;
        }
        assertEquals(source.triangleCount(), checked);
        assertTrue(checked < 256 * 64 * 4, "authored source contains holes");
    }

    @Test
    void renderedCellCentersReturnTheirRecordedHeights() {
        // Authored chunk seams are deliberately discontinuous, so only interior fan centers are
        // unambiguous vertex oracles; shared-edge ownership is covered by triangle centroids.
        var source = tile(false);
        var ground = TerrainGround.of(List.of(TerrainGround.Tile.of(source)));
        FloatBuffer v = source.vertices();
        IntBuffer t = source.indices();
        for (int i = 1; i < t.limit(); i += 12) {
            int m = t.get(i) * 3;
            assertEquals(v.get(m + 2), ground.heightAt(v.get(m), v.get(m + 1)), 1e-3);
        }
    }

    @Test
    void holesAndUncoveredPointsAreUnavailableNotInterpolated() {
        var grid = WorldFormats.adtGrid(root(true), () -> false);
        var ground = TerrainGround.of(List.of(TerrainGround.Tile.of(tile(true))));
        double unit = WorldFormats.TILE_SIZE / 256;
        int holes = 0;
        for (var chunk : grid.chunks()) {
            for (int cell = 0; cell < 64; cell++) {
                if ((chunk.holes() >>> cell & 1) == 0) {
                    continue;
                }
                int row = cell / 8, col = cell % 8;
                double x = chunk.originX() - (2 * row + 1) * unit,
                        y = chunk.originY() - (2 * col + 1) * unit;
                assertTrue(Double.isNaN(ground.heightAt(x, y)), "hole " + chunk.x() + "," + cell);
                holes++;
            }
        }
        assertTrue(holes > 10, "both hole encodings are exercised");
        assertTrue(Double.isNaN(ground.heightAt(ORIGIN_X + 1, ORIGIN_Y - 10)));
        assertTrue(Double.isNaN(ground.heightAt(ORIGIN_X - 10, ORIGIN_Y + 1)));
        assertTrue(Double.isNaN(ground.heightAt(Double.NaN, 0)));
        assertTrue(Double.isNaN(TerrainGround.empty().heightAt(ORIGIN_X - 1, ORIGIN_Y - 1)));
    }

    @Test
    void differentMeshLayoutsAreNotQualified() {
        var source = tile(false);
        float[] vertices = new float[source.vertexCount() * 3];
        source.vertices().get(vertices);
        int[] indices = new int[source.indexCount()];
        source.indices().get(indices);
        assertNotNull(TerrainGround.Tile.of(FloatBuffer.wrap(vertices), IntBuffer.wrap(indices)));

        float[] moved = vertices.clone();
        moved[3 * 20] += 1;
        assertNull(TerrainGround.Tile.of(FloatBuffer.wrap(moved), IntBuffer.wrap(indices)));

        int[] swapped = indices.clone();
        swapped[1] = indices[2];
        swapped[2] = indices[1];
        assertNull(TerrainGround.Tile.of(FloatBuffer.wrap(vertices), IntBuffer.wrap(swapped)));

        int[] partial = java.util.Arrays.copyOf(indices, 9);
        assertNull(TerrainGround.Tile.of(FloatBuffer.wrap(vertices), IntBuffer.wrap(partial)));

        float[] truncated = java.util.Arrays.copyOf(vertices, vertices.length - 3);
        assertNull(TerrainGround.Tile.of(FloatBuffer.wrap(truncated), IntBuffer.wrap(new int[0])));

        var unqualified = TerrainGround.of(Collections.singletonList(null));
        assertSame(TerrainGround.empty(), unqualified);
    }

    @Test
    void sharedTilesAreBoundedAndSearchedInOrder() {
        var qualified = TerrainGround.Tile.of(tile(false));
        var thirteen = new ArrayList<TerrainGround.Tile>();
        for (int i = 0; i < 13; i++) {
            thirteen.add(qualified);
        }
        assertThrows(IllegalArgumentException.class, () -> TerrainGround.of(thirteen));
        assertEquals(12, TerrainGround.of(thirteen.subList(0, 12)).tiles());
        assertEquals(256, qualified.chunks());
    }

    @Test
    void floatRoundedChunkSeamsRemainCovered() {
        var source = tile(false);
        var ground = TerrainGround.of(List.of(TerrainGround.Tile.of(source)));
        double chunk = WorldFormats.TILE_SIZE / 16;
        for (int seam = 1; seam < 16; seam++) {
            double x = ORIGIN_X - seam * chunk, y = ORIGIN_Y - 7.3;
            for (double offset : new double[] {-1e-5, 0, 1e-5}) {
                assertFalse(Double.isNaN(ground.heightAt(x + offset, y)), "X seam " + seam);
                assertFalse(
                        Double.isNaN(
                                ground.heightAt(
                                        y - ORIGIN_Y + ORIGIN_X - 9,
                                        ORIGIN_Y - seam * chunk + offset)),
                        "Y seam " + seam);
            }
        }
        assertTrue(Double.isNaN(ground.heightAt(ORIGIN_X + .5, ORIGIN_Y - 7.3)));
    }
}
