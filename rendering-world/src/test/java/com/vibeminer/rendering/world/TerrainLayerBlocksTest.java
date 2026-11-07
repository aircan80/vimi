package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class TerrainLayerBlocksTest {
    private static byte[][] randomLevels(int format, int count, Random random) {
        var levels = new byte[count][];
        for (int k = 0; k < count; k++) {
            int s = TerrainLayerBlocks.side(k);
            levels[k] = new byte[ObjectTextureFormats.levelBytes(format, s, s, 0)];
            random.nextBytes(levels[k]);
        }
        return levels;
    }

    @Test
    void oneLayerIsAFixed256BlockChain() {
        assertEquals(43_704, TerrainLayerBlocks.CHAIN_BYTES);
        assertEquals(9, TerrainLayerBlocks.LEVELS);
        assertEquals(1, TerrainLayerBlocks.side(8));
        assertEquals(32_768, TerrainLayerBlocks.levelBytes(0));
        assertEquals(43_696, TerrainLayerBlocks.levelOffset(8));
    }

    @Test
    void bc1BlocksAreCarriedUntouched() {
        var levels = randomLevels(ObjectTextureFormats.BC1, 9, new Random(174));
        var chain = TerrainLayerBlocks.fromBlocks(ObjectTextureFormats.BC1, levels);
        for (int k = 0; k < 9; k++) {
            assertArrayEquals(
                    levels[k],
                    java.util.Arrays.copyOfRange(
                            chain,
                            TerrainLayerBlocks.levelOffset(k),
                            TerrainLayerBlocks.levelOffset(k) + levels[k].length),
                    "level " + k);
        }
    }

    /** BC2 and BC3 colour halves decode to the same RGB as BC1, every block, every level. */
    @Test
    void bc2AndBc3ColourIsTranscodedWithoutLoss() {
        var random = new Random(2);
        for (int format : new int[] {ObjectTextureFormats.BC2, ObjectTextureFormats.BC3}) {
            var levels = randomLevels(format, 9, random);
            // Force some equal and some reversed endpoints.
            for (int b = 0; b < 64; b++) {
                int at = b * 16 + 8;
                levels[0][at + 2] = levels[0][at];
                levels[0][at + 3] = levels[0][at + 1];
            }
            var chain = TerrainLayerBlocks.fromBlocks(format, levels);
            for (int k = 0; k < 9; k++) {
                int s = TerrainLayerBlocks.side(k);
                var expected =
                        ObjectTextureFormats.decodeLevel(
                                format, s, s, ByteBuffer.wrap(levels[k]), 0);
                var actual =
                        ObjectTextureFormats.decodeLevel(
                                ObjectTextureFormats.BC1,
                                s,
                                s,
                                ByteBuffer.wrap(chain),
                                TerrainLayerBlocks.levelOffset(k));
                for (int p = 0; p < s * s; p++) {
                    for (int c = 0; c < 3; c++) {
                        assertEquals(
                                expected[p * 4 + c] & 255,
                                actual[p * 4 + c] & 255,
                                "format " + format + " level " + k + " texel " + p);
                    }
                }
            }
        }
    }

    @Test
    void missingTinyLevelsAreTheFourByFourMean() {
        // Levels 0..6 only: the 4 x 4 level is one block of selector 0, so its mean is c0.
        var levels = randomLevels(ObjectTextureFormats.BC1, 7, new Random(9));
        var four = ByteBuffer.wrap(levels[6]).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        four.putShort(0, (short) 0xF800).putShort(2, (short) 0).putInt(4, 0);
        var chain = TerrainLayerBlocks.fromBlocks(ObjectTextureFormats.BC1, levels);
        for (int k = 7; k < 9; k++) {
            var texel =
                    ObjectTextureFormats.decodeLevel(
                            ObjectTextureFormats.BC1,
                            1,
                            1,
                            ByteBuffer.wrap(chain),
                            TerrainLayerBlocks.levelOffset(k));
            assertEquals(255, texel[0] & 255);
            assertEquals(0, texel[1] & 255);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainLayerBlocks.fromBlocks(ObjectTextureFormats.BC1, new byte[6][]));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainLayerBlocks.fromBlocks(ObjectTextureFormats.RGBA8, levels));
        levels[2] = new byte[3];
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainLayerBlocks.fromBlocks(ObjectTextureFormats.BC1, levels));
    }

    @Test
    void missingTinyLevelsAreBoxFilteredFromTheLevelAbove() {
        // 4 x 4 level: red in columns 0-1, blue in columns 2-3 (selectors 0 and 1).
        var levels = randomLevels(ObjectTextureFormats.BC1, 7, new Random(3));
        int selectors = 0;
        for (int p = 0; p < 16; p++) {
            selectors |= (p % 4 < 2 ? 0 : 1) << (p * 2);
        }
        ByteBuffer.wrap(levels[6])
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort(0, (short) 0xF800)
                .putShort(2, (short) 0x001F)
                .putInt(4, selectors);
        var chain = TerrainLayerBlocks.fromBlocks(ObjectTextureFormats.BC1, levels);
        var two =
                ObjectTextureFormats.decodeLevel(
                        ObjectTextureFormats.BC1,
                        2,
                        2,
                        ByteBuffer.wrap(chain),
                        TerrainLayerBlocks.levelOffset(7));
        assertEquals(255, two[0] & 255, "2 x 2 left texel stays red");
        assertEquals(0, two[2] & 255);
        assertEquals(0, two[4] & 255, "2 x 2 right texel stays blue");
        assertEquals(255, two[6] & 255);
        var one =
                ObjectTextureFormats.decodeLevel(
                        ObjectTextureFormats.BC1,
                        1,
                        1,
                        ByteBuffer.wrap(chain),
                        TerrainLayerBlocks.levelOffset(8));
        assertEquals(128, one[0] & 255, 6, "1 x 1 is the mean");
        assertEquals(128, one[2] & 255, 6);
    }

    @Test
    void encodedPixelsStayCloseAtEveryLevel() {
        // A smooth 64 x 64 gradient, enlarged to 256 and encoded.
        int side = 64;
        var rgba = ByteBuffer.allocate(side * side * 4);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                rgba.put((byte) (x * 4)).put((byte) (y * 4)).put((byte) 96).put((byte) 255);
            }
        }
        var chain = TerrainLayerBlocks.encode(rgba.flip(), side);
        assertEquals(TerrainLayerBlocks.CHAIN_BYTES, chain.length);
        var top =
                ObjectTextureFormats.decodeLevel(
                        ObjectTextureFormats.BC1, 256, 256, ByteBuffer.wrap(chain), 0);
        long error = 0;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                // Expected: the bilinear enlargement, approximately x and y in source units.
                double sx = (x + .5) / 4 - .5, sy = (y + .5) / 4 - .5;
                if (sx < 0 || sy < 0 || sx > 63 || sy > 63) {
                    continue; // wrapped edge texels blend the opposite side
                }
                error += Math.abs((top[(y * 256 + x) * 4] & 255) - Math.round(sx * 4));
                error += Math.abs((top[(y * 256 + x) * 4 + 1] & 255) - Math.round(sy * 4));
            }
        }
        assertTrue(
                error / (2.0 * 254 * 254) < 4, "mean channel error " + error / (2.0 * 254 * 254));
        // A solid colour survives every level exactly (as its 565 value).
        var solid = ByteBuffer.allocate(4 * 4 * 4);
        for (int i = 0; i < 16; i++) {
            solid.put((byte) 0x84).put((byte) 0x20).put((byte) 0x10).put((byte) 255);
        }
        var flat = TerrainLayerBlocks.encode(solid.flip(), 4);
        for (int k = 0; k < 9; k++) {
            int s = TerrainLayerBlocks.side(k);
            var level =
                    ObjectTextureFormats.decodeLevel(
                            ObjectTextureFormats.BC1,
                            s,
                            s,
                            ByteBuffer.wrap(flat),
                            TerrainLayerBlocks.levelOffset(k));
            assertEquals(0x84, level[0] & 255, "level " + k);
            assertEquals(0x20, level[1] & 255, "level " + k);
            assertEquals(0x10, level[2] & 255, "level " + k);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainLayerBlocks.encode(ByteBuffer.allocate(48), 3));
    }
}
