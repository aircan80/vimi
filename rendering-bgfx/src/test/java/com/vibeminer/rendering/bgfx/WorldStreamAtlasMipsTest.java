package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class WorldStreamAtlasMipsTest {
    private static final int SIDE = WorldStreamAtlasMips.SIDE;

    /** Each chunk (cx, cy) gets color(cx, cy), or alpha 0 and black when uncovered. */
    private interface Chunks {
        int rgba(int cx, int cy);
    }

    private static ByteBuffer chain(Chunks chunks) {
        var chain = ByteBuffer.allocateDirect(WorldStreamAtlasMips.CHAIN_BYTES);
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                chain.putInt((y * SIDE + x) * 4, chunks.rgba(x / 32, y / 32));
            }
        }
        WorldStreamAtlasMips.build(chain, () -> false);
        return chain;
    }

    private static int texel(ByteBuffer chain, int level, int x, int y) {
        int side = WorldStreamAtlasMips.side(level);
        // Big-endian view: 0xRRGGBBAA in the RGBA8 byte order.
        return chain.getInt(WorldStreamAtlasMips.offset(level) + (y * side + x) * 4);
    }

    private static int rgba(int r, int g, int b, int a) {
        return r << 24 | g << 16 | b << 8 | a;
    }

    @Test
    void chainLayoutIsTheFullTenLevelPyramid() {
        assertEquals(1_398_100, WorldStreamAtlasMips.CHAIN_BYTES);
        assertEquals(WorldStreamTileFile.ATLAS_BYTES, WorldStreamAtlasMips.offset(1));
        assertEquals(1, WorldStreamAtlasMips.side(WorldStreamAtlasMips.LEVELS - 1));
        assertEquals(
                WorldStreamAtlasMips.CHAIN_BYTES - 4,
                WorldStreamAtlasMips.offset(WorldStreamAtlasMips.LEVELS - 1));
    }

    @Test
    void uploadPlanCoversEveryRowOnceWithinTheStepAndTicketBudgets() {
        var covered = new int[WorldStreamAtlasMips.LEVELS];
        int referenced = 0, total = 0;
        for (var step : WorldStreamAtlasMips.STEPS) {
            int bytes = 0;
            for (var slice : step) {
                int side = WorldStreamAtlasMips.side(slice.level());
                assertEquals(covered[slice.level()], slice.row(), "rows in order, no gaps");
                assertEquals(slice.rows() * side * 4, slice.bytes());
                assertEquals(
                        WorldStreamAtlasMips.offset(slice.level()) + slice.row() * side * 4,
                        slice.offset());
                covered[slice.level()] += slice.rows();
                referenced += slice.referenced() ? 1 : 0;
                bytes += slice.bytes();
            }
            assertTrue(bytes > 0 && bytes <= WorldStreamAtlasMips.STEP_BYTES);
            total += bytes;
        }
        for (int level = 0; level < WorldStreamAtlasMips.LEVELS; level++) {
            assertEquals(WorldStreamAtlasMips.side(level), covered[level]);
        }
        assertEquals(WorldStreamAtlasMips.CHAIN_BYTES, total);
        assertEquals(22, WorldStreamAtlasMips.STEPS.size());
        // Level 0 keeps the former 16 steps of 32 rows; mips add six steps.
        assertEquals(
                16, WorldStreamAtlasMips.STEPS.stream().filter(s -> s.get(0).level() == 0).count());
        // Worst-case geometry tickets plus atlas references fit the 64 release tickets.
        int geometryTickets =
                (WorldStreamPackedTile.MAX_GEOMETRY_BYTES
                                + BgfxWorldStreamRenderer.GEOMETRY_CHUNK
                                - 1)
                        / BgfxWorldStreamRenderer.GEOMETRY_CHUNK;
        assertEquals(21, referenced);
        assertTrue(geometryTickets + referenced <= BgfxWorldStreamRenderer.MAX_TICKETS);
        // A step adds at most one atlas step to two geometry chunks: the 256 KiB slice bound.
        assertTrue(
                2 * BgfxWorldStreamRenderer.GEOMETRY_CHUNK + WorldStreamAtlasMips.STEP_BYTES
                        <= 262144);
    }

    @Test
    void uniformCoveredAtlasKeepsItsColorAtEveryLevel() {
        int color = rgba(17, 34, 51, 255);
        var chain = chain((cx, cy) -> color);
        for (int level = 0; level < WorldStreamAtlasMips.LEVELS; level++) {
            int side = WorldStreamAtlasMips.side(level);
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    assertEquals(color, texel(chain, level, x, y));
                }
            }
        }
    }

    @Test
    void levelsOneToFiveNeverMixTwoChunks() {
        Chunks checker =
                (cx, cy) -> (cx + cy) % 2 == 0 ? rgba(200, 10, 10, 255) : rgba(10, 10, 200, 255);
        var chain = chain(checker);
        for (int level = 0; level <= 5; level++) {
            int side = WorldStreamAtlasMips.side(level), perChunk = 32 >> level;
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    assertEquals(
                            checker.rgba(x / perChunk, y / perChunk), texel(chain, level, x, y));
                }
            }
        }
        // Level 6 averages 2 x 2 chunks: two red and two blue.
        assertEquals(rgba(105, 10, 105, 255), texel(chain, 6, 0, 0));
    }

    @Test
    void uncoveredChunksNeverDarkenCoveredGroundAndTakeNearbyColor() {
        int grass = rgba(60, 140, 40, 255);
        // Left half covered, right half unsupported (alpha 0, black RGB as the composer writes).
        var chain = chain((cx, cy) -> cx < 8 ? grass : 0);
        for (int level = 0; level < WorldStreamAtlasMips.LEVELS; level++) {
            int side = WorldStreamAtlasMips.side(level);
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int texel = texel(chain, level, x, y);
                    // RGB is grass everywhere: covered texels exactly, uncovered ones by the fill.
                    assertEquals(grass & 0xffffff00, texel & 0xffffff00, level + ":" + x + "," + y);
                    int alpha = texel & 255;
                    if (level == WorldStreamAtlasMips.LEVELS - 1) {
                        assertEquals(128, alpha);
                    } else {
                        assertEquals(x < side / 2 ? 255 : 0, alpha);
                    }
                }
            }
        }
        // Emulated bilinear tap halfway across the border at level 0: grass, half covered.
        int left = texel(chain, 0, 255, 0), right = texel(chain, 0, 256, 0);
        assertEquals(grass & 0xffffff00, left & 0xffffff00);
        assertEquals(grass & 0xffffff00, right & 0xffffff00);
    }

    @Test
    void mixedCoverageAveragesOnlyCoveredTexels() {
        int sand = rgba(200, 180, 120, 255);
        // One covered chunk in each 2 x 2 chunk block.
        var chain = chain((cx, cy) -> cx % 2 == 0 && cy % 2 == 0 ? sand : 0);
        int texel = texel(chain, 6, 3, 5);
        assertEquals(sand & 0xffffff00, texel & 0xffffff00);
        assertEquals(64, texel & 255);
        // An uncovered level-5 texel takes the level-6 fill, not black.
        assertEquals(sand & 0xffffff00, texel(chain, 5, 1, 0) & 0xffffff00);
        assertEquals(0, texel(chain, 5, 1, 0) & 255);
    }

    @Test
    void fullyUncoveredAtlasStaysTransparent() {
        var chain = chain((cx, cy) -> 0);
        for (int level = 0; level < WorldStreamAtlasMips.LEVELS; level++) {
            assertEquals(0, texel(chain, level, 0, 0));
        }
    }

    @Test
    void sizeAndCancellationAreChecked() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamAtlasMips.build(ByteBuffer.allocate(1 << 20), () -> false));
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamAtlasMips.build(
                                ByteBuffer.allocate(WorldStreamAtlasMips.CHAIN_BYTES), () -> true));
    }

    @Test
    void streamSamplerIsTrilinearAndClamped() {
        assertEquals(
                org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP,
                BgfxWorldStreamRenderer.SAMPLER_FLAGS);
    }
}
