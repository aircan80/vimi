package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class ObjectTextureFormatsTest {
    private static final int RED = 0xF800, BLUE = 0x001F;

    /** One BC1 block: two 565 colours and 16 two-bit selectors (pixel 0 in the low bits). */
    static byte[] bc1(int c0, int c1, int selectors) {
        return ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) c0)
                .putShort((short) c1)
                .putInt(selectors)
                .array();
    }

    private static int[] texel(byte[] rgba, int index) {
        int at = index * 4;
        return new int[] {
            rgba[at] & 255, rgba[at + 1] & 255, rgba[at + 2] & 255, rgba[at + 3] & 255
        };
    }

    @Test
    void chainSizesUseWholeBlocksDownToOneByOne() {
        // 256^2 BC1: 32,768 + 8,192 + 2,048 + 512 + 128 + 32 + 8, then 8 for 2x2 and 1x1.
        assertEquals(43_704, ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC1, 256, 256));
        assertEquals(87_408, ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC3, 256, 256));
        assertEquals(
                ObjectTextureMips.chainPixels(256, 64) * 4,
                ObjectTextureFormats.chainBytes(ObjectTextureFormats.RGBA8, 256, 64));
        // Non-square: 256 x 4 is 64 x 1 blocks, then 128 x 2 still one block row.
        assertEquals(512, ObjectTextureFormats.levelBytes(ObjectTextureFormats.BC1, 256, 4, 0));
        assertEquals(256, ObjectTextureFormats.levelBytes(ObjectTextureFormats.BC1, 256, 4, 1));
        assertEquals(8, ObjectTextureFormats.levelBytes(ObjectTextureFormats.BC1, 256, 4, 8));
        assertEquals(
                ObjectTextureFormats.levelBytes(ObjectTextureFormats.BC3, 256, 256, 0),
                ObjectTextureFormats.levelOffset(ObjectTextureFormats.BC3, 256, 256, 1));
        assertEquals(4, ObjectTextureFormats.unitRows(ObjectTextureFormats.BC2));
        assertEquals(1024, ObjectTextureFormats.pitch(ObjectTextureFormats.RGBA8, 256));
        assertEquals(1024, ObjectTextureFormats.pitch(ObjectTextureFormats.BC3, 256));
        assertEquals(8, ObjectTextureFormats.pitch(ObjectTextureFormats.BC1, 1));
        assertThrows(IllegalArgumentException.class, () -> ObjectTextureFormats.unitBytes(4));
    }

    @Test
    void bc1FourColourAndPunchThroughFollowGpuSampling() {
        // Selectors 0, 1, 2, 3 for pixels 0..3, then 0.
        int selectors = 0b11_10_01_00;
        var four =
                ObjectTextureFormats.decodeLevel(
                        ObjectTextureFormats.BC1,
                        4,
                        4,
                        ByteBuffer.wrap(bc1(RED, BLUE, selectors)),
                        0);
        assertArrayEquals(new int[] {255, 0, 0, 255}, texel(four, 0));
        assertArrayEquals(new int[] {0, 0, 255, 255}, texel(four, 1));
        assertArrayEquals(new int[] {170, 0, 85, 255}, texel(four, 2));
        assertArrayEquals(new int[] {85, 0, 170, 255}, texel(four, 3));
        assertArrayEquals(new int[] {255, 0, 0, 255}, texel(four, 15));
        var three =
                ObjectTextureFormats.decodeLevel(
                        ObjectTextureFormats.BC1,
                        4,
                        4,
                        ByteBuffer.wrap(bc1(BLUE, RED, selectors)),
                        0);
        assertArrayEquals(new int[] {128, 0, 128, 255}, texel(three, 2));
        assertArrayEquals(new int[] {0, 0, 0, 0}, texel(three, 3), "index 3 is transparent black");
    }

    @Test
    void bc2AndBc3AlphaAndClippedSmallLevels() {
        var bc2 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        // Explicit 4-bit alpha: pixel 0 = 0, pixel 1 = 15, the rest 8.
        bc2.put((byte) 0xF0);
        for (int i = 1; i < 8; i++) {
            bc2.put((byte) 0x88);
        }
        bc2.put(bc1(BLUE, RED, 0)); // BC2 colour is always four-colour: selector 0 is c0.
        var two = ObjectTextureFormats.decodeLevel(ObjectTextureFormats.BC2, 2, 2, bc2.flip(), 0);
        assertEquals(16, two.length, "a 2 x 2 level reads one block and keeps four texels");
        assertArrayEquals(new int[] {0, 0, 255, 0}, texel(two, 0));
        assertArrayEquals(new int[] {0, 0, 255, 255}, texel(two, 1));
        assertArrayEquals(new int[] {0, 0, 255, 136}, texel(two, 2));

        var bc3 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        // Eight-value alpha 255 -> 0; selectors: pixel 0 -> 0, pixel 1 -> 1, pixel 2 -> 2.
        bc3.put((byte) 255).put((byte) 0);
        long alphaSelectors = 0 | 1L << 3 | 2L << 6;
        for (int i = 0; i < 6; i++) {
            bc3.put((byte) (alphaSelectors >>> (8 * i)));
        }
        bc3.put(bc1(RED, RED, 0));
        var three = ObjectTextureFormats.decodeLevel(ObjectTextureFormats.BC3, 4, 1, bc3.flip(), 0);
        assertArrayEquals(new int[] {255, 0, 0, 255}, texel(three, 0));
        assertArrayEquals(new int[] {255, 0, 0, 0}, texel(three, 1));
        assertArrayEquals(new int[] {255, 0, 0, 219}, texel(three, 2));
    }

    @Test
    void decodedChainHasTheRgbaLayoutAndRgbaIsCopied() {
        // 8 x 4 BC1: two blocks, then 4 x 2, 2 x 1 and 1 x 1 levels of one block each.
        int width = 8, height = 4;
        var source =
                ByteBuffer.allocate(
                        4
                                + ObjectTextureFormats.chainBytes(
                                        ObjectTextureFormats.BC1, width, height));
        source.position(4);
        source.put(bc1(RED, RED, 0)).put(bc1(BLUE, BLUE, 0));
        for (int level = 1; level < 4; level++) {
            source.put(bc1(level == 3 ? RED : BLUE, 0, 0));
        }
        var chain =
                ObjectTextureFormats.decodeChain(
                        ObjectTextureFormats.BC1, width, height, source, 4);
        assertEquals(ObjectTextureMips.chainPixels(width, height) * 4, chain.length);
        assertArrayEquals(new int[] {255, 0, 0, 255}, texel(chain, 3));
        assertArrayEquals(new int[] {0, 0, 255, 255}, texel(chain, 4));
        int last = ObjectTextureMips.chainPixels(width, height) - 1;
        assertArrayEquals(new int[] {255, 0, 0, 255}, texel(chain, last));
        var rgba = ByteBuffer.wrap(chain);
        assertArrayEquals(
                chain,
                ObjectTextureFormats.decodeChain(
                        ObjectTextureFormats.RGBA8, width, height, rgba, 0));
    }
}
