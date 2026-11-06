package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObjectTextureMipsTest {
    private static final ObjectTextureMips.FileLevel NONE = (below, w, h) -> null;

    private static byte[] solid(int w, int h, int r, int g, int b, int a) {
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            out[i * 4] = (byte) r;
            out[i * 4 + 1] = (byte) g;
            out[i * 4 + 2] = (byte) b;
            out[i * 4 + 3] = (byte) a;
        }
        return out;
    }

    private static int passing(byte[] level) {
        int count = 0;
        for (int i = 3; i < level.length; i += 4) {
            if ((level[i] & 255) >= 128) {
                count++;
            }
        }
        return count;
    }

    @Test
    void chainGeometryMatchesBgfxMipLayoutForSquareAndNonSquareImages() {
        assertEquals(9, ObjectTextureMips.levels(256, 256));
        assertEquals(87_381, ObjectTextureMips.chainPixels(256, 256));
        assertEquals(8, ObjectTextureMips.levels(128, 64));
        // 128x64, 64x32, 32x16, 16x8, 8x4, 4x2, 2x1, 1x1
        assertEquals(
                8192 + 2048 + 512 + 128 + 32 + 8 + 2 + 1, ObjectTextureMips.chainPixels(128, 64));
        assertEquals(1, ObjectTextureMips.chainPixels(1, 1));
        assertEquals(4 * (128 * 64 + 64 * 32), ObjectTextureMips.levelOffset(128, 64, 2));
        // Never more than 4/3 of the top level plus one texel per level of slack.
        for (int w = 1; w <= 512; w *= 2) {
            for (int h = 1; h <= 512; h *= 2) {
                long top = (long) w * h;
                assertTrue(
                        ObjectTextureMips.chainPixels(w, h)
                                <= top * 4 / 3 + ObjectTextureMips.levels(w, h) * Math.max(w, h),
                        w + "x" + h);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ObjectTextureMips.levels(0, 4));
    }

    @Test
    void fileMipsAreUsedAsAuthoredWhenEveryLevelIsPresent() {
        var asked = new AtomicInteger();
        var chain =
                ObjectTextureMips.build(
                        4,
                        4,
                        solid(4, 4, 10, 20, 30, 255),
                        ObjectTextureMips.BLENDED,
                        (below, w, h) -> {
                            asked.incrementAndGet();
                            // Authored levels deliberately differ from a box filter of the top.
                            return solid(w, h, 200 + below, 0, 0, 255);
                        },
                        () -> false);
        assertEquals(ObjectTextureMips.Source.FILE, chain.source());
        assertEquals(2, asked.get());
        var levels = ObjectTextureMips.split(chain);
        assertEquals(3, levels.size());
        assertArrayEquals(solid(2, 2, 201, 0, 0, 255), levels.get(1));
        assertArrayEquals(solid(1, 1, 202, 0, 0, 255), levels.get(2));
    }

    @Test
    void missingOrWrongSizedFileLevelsAreGeneratedFromTheLastGoodLevel() {
        var generated =
                ObjectTextureMips.build(
                        4,
                        4,
                        solid(4, 4, 40, 80, 120, 255),
                        ObjectTextureMips.OPAQUE,
                        NONE,
                        () -> false);
        assertEquals(ObjectTextureMips.Source.GENERATED, generated.source());
        assertArrayEquals(solid(1, 1, 40, 80, 120, 255), ObjectTextureMips.split(generated).get(2));

        var asked = new AtomicInteger();
        var partial =
                ObjectTextureMips.build(
                        4,
                        4,
                        solid(4, 4, 0, 0, 0, 255),
                        ObjectTextureMips.OPAQUE,
                        (below, w, h) -> {
                            asked.incrementAndGet();
                            return below == 1 ? solid(w, h, 100, 100, 100, 255) : new byte[3];
                        },
                        () -> false);
        assertEquals(ObjectTextureMips.Source.PARTIAL, partial.source());
        assertEquals(2, asked.get(), "the file is not asked again after a bad level");
        // Level 2 is generated from the authored level 1, not from the top.
        assertArrayEquals(solid(1, 1, 100, 100, 100, 255), ObjectTextureMips.split(partial).get(2));

        var single =
                ObjectTextureMips.build(
                        1,
                        1,
                        solid(1, 1, 1, 2, 3, 4),
                        ObjectTextureMips.BLENDED,
                        NONE,
                        () -> false);
        assertEquals(ObjectTextureMips.Source.SINGLE, single.source());
    }

    @Test
    void generatedLevelsWeightColourByAlphaSoTransparentTexelsDoNotDarkenTheEdge() {
        // Left column opaque green, right column transparent black.
        byte[] top = new byte[2 * 1 * 4];
        top[1] = (byte) 200;
        top[3] = (byte) 255;
        var chain =
                ObjectTextureMips.build(2, 1, top, ObjectTextureMips.BLENDED, NONE, () -> false);
        byte[] one = ObjectTextureMips.split(chain).get(1);
        assertEquals(0, one[0] & 255);
        assertEquals(200, one[1] & 255, "green, not half-darkened");
        assertEquals(128, one[3] & 255, "blended keeps the averaged alpha");
    }

    @Test
    void opaqueChainsHaveFullAlphaAtEveryLevelEvenFromTransparentFileMips() {
        var chain =
                ObjectTextureMips.build(
                        4,
                        4,
                        solid(4, 4, 9, 9, 9, 0),
                        ObjectTextureMips.OPAQUE,
                        (below, w, h) -> solid(w, h, 9, 9, 9, 3),
                        () -> false);
        for (int i = 3; i < chain.rgba().length; i += 4) {
            assertEquals(255, chain.rgba()[i] & 255);
        }
    }

    /** Leaf-like cutout: soft-edged blobs on a transparent background, 40-60% covered. */
    private static byte[] leaves(int side) {
        byte[] top = new byte[side * side * 4];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                // Blobs on an 8-texel grid with a radial falloff and a deterministic jitter.
                double cx = (x % 8) - 3.5, cy = (y % 8) - 3.5;
                double d = Math.sqrt(cx * cx + cy * cy) + ((x * 7 + y * 13) % 5) * 0.15;
                int alpha = (int) Math.max(0, Math.min(255, (4.2 - d) * 140));
                int at = (y * side + x) * 4;
                top[at + 1] = (byte) 150;
                top[at + 3] = (byte) alpha;
            }
        }
        return top;
    }

    @Test
    void matchCoverageRescalesOneLevelToAWantedFraction() {
        // Four texels at alpha 40, 80, 120 and 160: one passes; asking for half makes two pass.
        byte[] rgba = new byte[16];
        int[] alpha = {40, 80, 120, 160};
        for (int i = 0; i < 4; i++) {
            rgba[i * 4 + 3] = (byte) alpha[i];
        }
        assertEquals(0.25, ObjectTextureMips.coverage(rgba, 0, 4));
        ObjectTextureMips.matchCoverage(rgba, 0, 4, 0.5);
        assertEquals(0.5, ObjectTextureMips.coverage(rgba, 0, 4));
        assertTrue((rgba[3] & 255) < 128 && (rgba[11] & 255) >= 128, "order is kept");
        ObjectTextureMips.matchCoverage(rgba, 0, 4, 1);
        assertEquals(1, ObjectTextureMips.coverage(rgba, 0, 4), "every texel passes");
        ObjectTextureMips.matchCoverage(rgba, 0, 4, 0);
        assertEquals(0, ObjectTextureMips.coverage(rgba, 0, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> ObjectTextureMips.matchCoverage(rgba, 0, 5, 0.5));
        assertThrows(
                IllegalArgumentException.class,
                () -> ObjectTextureMips.matchCoverage(rgba, 0, 4, 1.5));
    }

    @Test
    void cutoutCoverageStaysNearTheTopLevelWhilePlainAveragingThinsIt() {
        int side = 128;
        byte[] top = leaves(side);
        double coverage = passing(top) / (double) (side * side);
        assertTrue(coverage > 0.4 && coverage < 0.75, "fixture coverage " + coverage);
        var plain =
                ObjectTextureMips.split(
                        ObjectTextureMips.build(
                                side, side, top, ObjectTextureMips.BLENDED, NONE, () -> false));
        var preserved =
                ObjectTextureMips.split(
                        ObjectTextureMips.build(
                                side, side, top, ObjectTextureMips.CUTOUT, NONE, () -> false));
        // Ordinary mips: once a level averages whole blobs, alpha sits near the coverage and the
        // 0.5 test passes all or nothing; at level 3 (16x16, one texel per blob) it is lost.
        double plainLevel3 = passing(plain.get(3)) / 256.0;
        assertTrue(Math.abs(plainLevel3 - coverage) > 0.3, "plain level 3 " + plainLevel3);
        // Coverage-preserving levels stay within 12 points of the top down to 16x16 (one
        // texel per blob); the slack is
        // ties, since a regular blob grid gives few distinct averaged alphas.
        for (int k = 1; k <= 3; k++) {
            int pixels = preserved.get(k).length / 4;
            double kept = passing(preserved.get(k)) / (double) pixels;
            assertTrue(
                    Math.abs(kept - coverage) <= 0.12,
                    "top "
                            + coverage
                            + " level "
                            + k
                            + " coverage "
                            + kept
                            + " plain "
                            + passing(plain.get(k)) / (double) pixels);
        }
        // Colour is untouched by the alpha rescale.
        assertEquals(150, preserved.get(2)[1] & 255);
    }

    @Test
    void uniformAlphaTiesPassOrFailTogetherWhicheverIsNearer() {
        // One opaque column in four: each 4x4-averaged texel has alpha 64 (a tie for all).
        int side = 16;
        byte[] top = new byte[side * side * 4];
        for (int i = 0; i < side * side; i++) {
            top[i * 4 + 3] = (byte) (i % 4 == 0 ? 255 : 0);
        }
        var levels =
                ObjectTextureMips.split(
                        ObjectTextureMips.build(
                                side, side, top, ObjectTextureMips.CUTOUT, NONE, () -> false));
        // 25% wanted: none passing (25% off) is nearer than all passing (75% off).
        assertEquals(0, passing(levels.get(2)));
        byte[] dense = new byte[side * side * 4];
        for (int i = 0; i < side * side; i++) {
            dense[i * 4 + 3] = (byte) (i % 4 == 0 ? 0 : 255);
        }
        var denseLevels =
                ObjectTextureMips.split(
                        ObjectTextureMips.build(
                                side, side, dense, ObjectTextureMips.CUTOUT, NONE, () -> false));
        assertEquals(16, passing(denseLevels.get(2)), "75% wanted: all of a 4x4 level passes");
    }

    @Test
    void cutoutAlsoRescalesAuthoredFileMipsAndHandlesNoneOrAllPassing() {
        // Authored mips that faded alpha to 100 everywhere: coverage would drop to zero.
        var chain =
                ObjectTextureMips.build(
                        4,
                        4,
                        solid(4, 4, 1, 1, 1, 255),
                        ObjectTextureMips.CUTOUT,
                        (below, w, h) -> solid(w, h, 1, 1, 1, 100),
                        () -> false);
        assertEquals(ObjectTextureMips.Source.FILE, chain.source());
        for (var level : ObjectTextureMips.split(chain)) {
            assertEquals(level.length / 4, passing(level), "all texels still pass");
        }
        var none =
                ObjectTextureMips.build(
                        4,
                        4,
                        solid(4, 4, 1, 1, 1, 127),
                        ObjectTextureMips.CUTOUT,
                        (below, w, h) -> solid(w, h, 1, 1, 1, 200),
                        () -> false);
        for (var level : ObjectTextureMips.split(none)) {
            assertEquals(0, passing(level), "nothing passed at the top, so nothing passes below");
        }
    }

    @Test
    void cancellationStopsBetweenLevels() {
        var calls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        ObjectTextureMips.build(
                                64,
                                64,
                                new byte[64 * 64 * 4],
                                ObjectTextureMips.CUTOUT,
                                NONE,
                                () -> calls.incrementAndGet() > 2));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ObjectTextureMips.build(
                                4, 4, new byte[3], ObjectTextureMips.OPAQUE, NONE, () -> false));
    }
}
