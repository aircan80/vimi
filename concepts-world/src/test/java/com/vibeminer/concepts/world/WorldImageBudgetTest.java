package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.images.Blp2Types;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldImageBudgetTest {
    private static final int[] SIDES = {256, 128, 64, 32};

    private static WorldImageBudget.Request square(int side) {
        int levels = 1;
        while ((side >> (levels - 1)) > 1) {
            levels++;
        }
        return new WorldImageBudget.Request(side, side, levels);
    }

    @Test
    void levelForPicksTheLargestLevelWithinTheSideAndStopsAtRecordedLevels() {
        assertEquals(0, WorldImageBudget.levelFor(256, 256, 256, 9));
        assertEquals(1, WorldImageBudget.levelFor(512, 512, 256, 10));
        assertEquals(2, WorldImageBudget.levelFor(1024, 512, 256, 11));
        assertEquals(4, WorldImageBudget.levelFor(512, 8, 32, 10));
        assertEquals(1, WorldImageBudget.levelFor(1024, 1024, 32, 2), "file stops at level 1");
        assertEquals(16384, WorldImageBudget.pixels(256, 256, 1));
        assertEquals(1, WorldImageBudget.pixels(4, 4, 5));
        assertThrows(IllegalArgumentException.class, () -> WorldImageBudget.levelFor(0, 1, 1, 1));
    }

    @Test
    void anAmpleBudgetGivesEveryImageTheLargestSide() {
        var requests = List.of(square(512), square(256), square(64));
        assertArrayEquals(new int[] {1, 0, 0}, WorldImageBudget.allocate(requests, 1 << 20, SIDES));
    }

    @Test
    void aTightBudgetLowersResolutionRoundByRoundInPriorityOrder() {
        // Costs are whole chains (#265): 256^2 is 87,381 texels, 128^2 is 21,845. 20 images in
        // the chain of a 1024^2 texture (1,398,101): all reach 128^2 (436,900), then the first
        // 14 can also take 256^2 (14 x 65,536 more), the rest stay at 128^2. None is dropped.
        assertEquals(87_381, WorldImageBudget.cost(256, 256, 0));
        assertEquals(21_845, WorldImageBudget.cost(256, 256, 1));
        var requests = new ArrayList<WorldImageBudget.Request>();
        for (int i = 0; i < 20; i++) {
            requests.add(square(256));
        }
        int[] levels = WorldImageBudget.allocate(requests, 1_398_101, SIDES);
        long total = 0;
        for (int i = 0; i < 20; i++) {
            assertEquals(i < 14 ? 0 : 1, levels[i], "image " + i);
            total += WorldImageBudget.cost(256, 256, levels[i]);
        }
        assertEquals(14 * 87_381 + 6 * 21_845, total);
    }

    @Test
    void theDoodadPoolCapsSidesAt128AndDownscalesWithinItsBudget() {
        var requests = new ArrayList<WorldImageBudget.Request>();
        for (int i = 0; i < 40; i++) {
            requests.add(square(256));
        }
        int[] levels =
                WorldImageBudget.allocate(
                        requests,
                        WorldExteriorObjects.MAX_DOODAD_PIXELS,
                        WorldExteriorObjects.MODEL_IMAGE_SIDES);
        long total = 0;
        for (int level : levels) {
            assertTrue(level >= 1, "never above 128");
            total += WorldImageBudget.pixels(256, 256, level);
        }
        assertTrue(total <= WorldExteriorObjects.MAX_DOODAD_PIXELS);
        assertEquals(1, levels[0], "the first image reaches 128");
        assertEquals(2, levels[39], "the last ones fall to 64 instead of being dropped");
    }

    @Test
    void imagesAreDroppedOnlyWhenEvenTheSmallestSideCannotFit() {
        // A file that records only level 0 of 1024^2 cannot shrink below 1 Mi pixels: it is the
        // one dropped, and the lower-priority image after it still fits at 32^2.
        var requests = List.of(square(64), new WorldImageBudget.Request(1024, 1024, 1), square(64));
        int[] levels = WorldImageBudget.allocate(requests, 4096, SIDES);
        assertArrayEquals(new int[] {1, -1, 1}, levels);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldImageBudget.allocate(List.of(), 1, new int[] {32, 64}));
    }

    private static Blp2Types.Mip mip(int level, Blp2Types.MipStorage storage) {
        return new Blp2Types.Mip(level, 64 >> level, 64 >> level, 148 + level, 8, storage);
    }

    @Test
    void recordedLevelsFollowTheDecodersDeclaredMipsAndStopAtCompactLevels() {
        var exact = Blp2Types.MipStorage.EXACT;
        assertEquals(
                3,
                WorldImageBudget.recordedLevels(
                        List.of(mip(0, exact), mip(1, exact), mip(2, exact))));
        assertEquals(
                2,
                WorldImageBudget.recordedLevels(
                        List.of(
                                mip(0, exact),
                                mip(1, exact),
                                mip(2, Blp2Types.MipStorage.COMPACT),
                                mip(3, exact))),
                "a compact block level is never decoded");
        assertEquals(0, WorldImageBudget.recordedLevels(List.of()));
        // Real decoder parsing: a BC1 file declaring levels 0..2 of 16x16.
        var blp = ByteBuffer.allocate(148 + 128 + 32 + 8).order(ByteOrder.LITTLE_ENDIAN);
        blp.put(new byte[] {'B', 'L', 'P', '2'}).putInt(1);
        blp.put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 1).putInt(16).putInt(16);
        blp.putInt(20, 148).putInt(24, 276).putInt(28, 308);
        blp.putInt(84, 128).putInt(88, 32).putInt(92, 8);
        var declared =
                com.vibeminer.formats.images.Blp2Decoder.readMipsSynchronous(
                        com.vibeminer.formats.AssetSource.bytes(blp.array()),
                        new Blp2Types.Limits(),
                        com.vibeminer.access.Cancellation.NONE,
                        Blp2Types.MipProfile.STRICT);
        assertEquals(3, WorldImageBudget.recordedLevels(declared));
    }

    @Test
    void compressedRequestsCostTheirBlocksAndRgbaKeepsItsCap() {
        int bc1 = com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        var compressed = new WorldImageBudget.Request(1024, 1024, 11, bc1, Integer.MAX_VALUE);
        var rgba =
                new WorldImageBudget.Request(
                        1024,
                        1024,
                        11,
                        com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8,
                        256);
        int[] sides = {512, 256, 128, 64, 32};
        // A budget of one 256^2 RGBA8 chain: the BC1 request reaches 512 (1/8 the bytes).
        long budget = WorldImageBudget.cost(256, 256, 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {1}, WorldImageBudget.allocate(List.of(compressed), budget, sides));
        // The RGBA8 request stops at its own 256 cap even with room for more.
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {2}, WorldImageBudget.allocate(List.of(rgba), budget * 16, sides));
        // Levels under one block are charged as RGBA8.
        org.junit.jupiter.api.Assertions.assertEquals(
                WorldImageBudget.cost(1024, 1024, 9), compressed.cost(9));
    }
}
