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
                        <= BgfxWorldStreamRenderer.STEP_BOUND);
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
    }

    /**
     * Emulates the stream sampler (clamped trilinear over texel centers) and the fragment shader's
     * alpha 0.5 cut across a covered/uncovered border, at every LOD and sub-texel offset.
     */
    @Test
    void trilinearTapsAcrossACoverageBorderNeverDarkenAndCutAtTheBorder() {
        int grass = rgba(60, 140, 40, 255);
        var chain = chain((cx, cy) -> cx < 8 ? grass : 0);
        for (double lod = 0; lod <= WorldStreamAtlasMips.LEVELS - 1; lod += .25) {
            for (double u = .45; u <= .55; u += 1.0 / 2048) {
                double[] tap = trilinear(chain, lod, u, .37);
                if (tap[3] >= .5) {
                    for (int c = 0; c < 3; c++) {
                        double expected = (grass >>> (24 - 8 * c) & 255) / 255.0;
                        assertEquals(expected, tap[c], 1.0 / 255, "lod " + lod + " u " + u);
                    }
                }
                if (lod <= 5) {
                    // Through level 5 the cut stays within half a level-0 texel of the border.
                    double margin = .5 / WorldStreamAtlasMips.side((int) Math.ceil(lod));
                    if (u < .5 - margin) {
                        assertTrue(tap[3] >= .5, "covered side kept at lod " + lod + " u " + u);
                    } else if (u > .5 + margin) {
                        assertTrue(tap[3] < .5, "uncovered side cut at lod " + lod + " u " + u);
                    }
                }
            }
        }
    }

    /** Random chunk coverage and colors: filtered ground stays within covered colors. */
    @Test
    void randomCoverageNeverFiltersInBlackOrMixesChunksBelowLevelSix() {
        var random = new java.util.Random(174);
        for (int round = 0; round < 6; round++) {
            int[] colors = new int[256];
            int lowest = 255;
            for (int i = 0; i < 256; i++) {
                if (random.nextInt(3) != 0) {
                    int r = 100 + random.nextInt(101),
                            g = 100 + random.nextInt(101),
                            b = 100 + random.nextInt(101);
                    lowest = Math.min(lowest, Math.min(r, Math.min(g, b)));
                    colors[i] = rgba(r, g, b, 255);
                }
            }
            var chain = chain((cx, cy) -> colors[cy * 16 + cx]);
            for (int level = 1; level <= 5; level++) {
                int side = WorldStreamAtlasMips.side(level), perChunk = 32 >> level;
                for (int y = 0; y < side; y++) {
                    for (int x = 0; x < side; x++) {
                        int expected = colors[(y / perChunk) * 16 + x / perChunk];
                        int texel = texel(chain, level, x, y);
                        if (expected != 0) {
                            assertEquals(expected, texel);
                        } else {
                            assertEquals(0, texel & 255);
                        }
                    }
                }
            }
            for (int level = 6; level < WorldStreamAtlasMips.LEVELS; level++) {
                int side = WorldStreamAtlasMips.side(level), span = 1 << (level - 5);
                for (int y = 0; y < side; y++) {
                    for (int x = 0; x < side; x++) {
                        boolean any = false;
                        for (int cy = y * span; cy < (y + 1) * span; cy++) {
                            for (int cx = x * span; cx < (x + 1) * span; cx++) {
                                any |= colors[cy * 16 + cx] != 0;
                            }
                        }
                        assertEquals(any, (texel(chain, level, x, y) & 255) != 0);
                    }
                }
            }
            for (int sample = 0; sample < 4000; sample++) {
                double[] tap =
                        trilinear(
                                chain,
                                random.nextDouble() * (WorldStreamAtlasMips.LEVELS - 1),
                                random.nextDouble(),
                                random.nextDouble());
                if (tap[3] >= .5) {
                    for (int c = 0; c < 3; c++) {
                        assertTrue(tap[c] * 255 >= lowest - 1, "black filtered into ground");
                    }
                }
            }
        }
    }

    /** Clamped bilinear tap of one level at normalized (u, v); RGBA in 0..1. */
    private static double[] bilinear(ByteBuffer chain, int level, double u, double v) {
        int side = WorldStreamAtlasMips.side(level);
        double x = u * side - .5, y = v * side - .5;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double[] out = new double[4];
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int tx = Math.clamp(x0 + dx, 0, side - 1), ty = Math.clamp(y0 + dy, 0, side - 1);
                double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                int texel = texel(chain, level, tx, ty);
                for (int c = 0; c < 4; c++) {
                    out[c] += weight * (texel >>> (24 - 8 * c) & 255) / 255.0;
                }
            }
        }
        return out;
    }

    private static double[] trilinear(ByteBuffer chain, double lod, double u, double v) {
        int low = (int) Math.floor(lod), high = Math.min(low + 1, WorldStreamAtlasMips.LEVELS - 1);
        double t = lod - low;
        double[] a = bilinear(chain, low, u, v), b = bilinear(chain, high, u, v);
        for (int c = 0; c < 4; c++) {
            a[c] = a[c] * (1 - t) + b[c] * t;
        }
        return a;
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
    void faintCoverageRoundsUpSoPushNeverOverwritesItsColor() {
        var chain = ByteBuffer.allocateDirect(WorldStreamAtlasMips.CHAIN_BYTES);
        chain.putInt(0, rgba(200, 100, 50, 1));
        WorldStreamAtlasMips.build(chain, () -> false);
        for (int level = 1; level < WorldStreamAtlasMips.LEVELS; level++) {
            int texel = texel(chain, level, 0, 0);
            assertEquals(rgba(200, 100, 50, 0) >>> 8, texel >>> 8, "level " + level);
            assertTrue((texel & 255) > 0, "faint coverage kept at level " + level);
        }
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
    void streamSamplerIsAnisotropicWithLinearMipsAndClampsBothAxes() {
        assertEquals(
                org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP
                        | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP
                        | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC
                        | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC,
                BgfxWorldStreamRenderer.SAMPLER_FLAGS);
        assertEquals(
                0,
                BgfxWorldStreamRenderer.SAMPLER_FLAGS & org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_MASK);
    }
}
