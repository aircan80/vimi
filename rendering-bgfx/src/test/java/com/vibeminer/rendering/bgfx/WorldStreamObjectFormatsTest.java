package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC1;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC2;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC3;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Object texture formats on the child side (issue #374): upload choice and decode fallback. */
final class WorldStreamObjectFormatsTest {
    @Test
    void storedFormatIsUploadedOnlyWhereTheBackendSamplesIt() {
        boolean[] all = {true, true, true, true}, none = {true, false, false, false};
        boolean[] bc1Only = {true, true, false, false};
        for (int f = ObjectTextureFormats.RGBA8; f <= ObjectTextureFormats.BC3; f++) {
            assertEquals(f, BgfxWorldStreamRenderer.uploadFormat(f, all));
            assertEquals(ObjectTextureFormats.RGBA8, BgfxWorldStreamRenderer.uploadFormat(f, none));
        }
        assertEquals(
                ObjectTextureFormats.BC1,
                BgfxWorldStreamRenderer.uploadFormat(ObjectTextureFormats.BC1, bc1Only));
        assertEquals(
                ObjectTextureFormats.RGBA8,
                BgfxWorldStreamRenderer.uploadFormat(ObjectTextureFormats.BC3, bc1Only));
        assertThrows(
                IllegalArgumentException.class, () -> BgfxWorldStreamRenderer.uploadFormat(4, all));
        assertEquals(
                BGFX_TEXTURE_FORMAT_RGBA8,
                BgfxWorldStreamRenderer.bgfxFormat(ObjectTextureFormats.RGBA8));
        assertEquals(
                BGFX_TEXTURE_FORMAT_BC1,
                BgfxWorldStreamRenderer.bgfxFormat(ObjectTextureFormats.BC1));
        assertEquals(
                BGFX_TEXTURE_FORMAT_BC2,
                BgfxWorldStreamRenderer.bgfxFormat(ObjectTextureFormats.BC2));
        assertEquals(
                BGFX_TEXTURE_FORMAT_BC3,
                BgfxWorldStreamRenderer.bgfxFormat(ObjectTextureFormats.BC3));
        assertEquals(
                "World object textures: BC1 compressed, BC2 compressed, BC3 compressed",
                BgfxWorldStreamRenderer.objectFormatsReport(all, false));
        assertEquals(
                "World object textures: BC1 compressed, BC2 decoded to RGBA8, BC3 decoded to RGBA8",
                BgfxWorldStreamRenderer.objectFormatsReport(bc1Only, false));
        assertTrue(
                BgfxWorldStreamRenderer.objectFormatsReport(none, true)
                        .endsWith("(" + BgfxWorldStreamRenderer.OBJECTS_RGBA8_PROPERTY + ")"));
    }

    @Test
    void compressedSlicesLetBgfxDeriveThePitch() {
        var bc =
                WorldStreamObjectMips.plan(ObjectTextureFormats.BC3, 64, 64, 1 << 20).get(0).get(0);
        assertEquals(0xFFFF, BgfxWorldStreamRenderer.updatePitch(ObjectTextureFormats.BC3, bc));
        var rgba = WorldStreamObjectMips.plan(64, 64, 1 << 20).get(0).get(0);
        assertEquals(256, BgfxWorldStreamRenderer.updatePitch(ObjectTextureFormats.RGBA8, rgba));
    }

    /**
     * The fallback decoder agrees with the BLP decoder the producer uses, block for block, for the
     * alpha settings a producer may carry as blocks: BC1 with 1-bit alpha, BC2 and BC3.
     */
    @Test
    void decodeFallbackMatchesTheBlpDecoderOnRandomBlocks() {
        var random = new Random(374);
        int[][] tuples = {
            {ObjectTextureFormats.BC1, 0, 1}, // compression, alpha encoding, alpha depth
            {ObjectTextureFormats.BC2, 1, 8},
            {ObjectTextureFormats.BC3, 7, 8}
        };
        for (var tuple : tuples) {
            int format = tuple[0];
            for (int[] size : new int[][] {{16, 16}, {32, 8}, {4, 64}}) {
                int width = size[0], height = size[1];
                var chain = new byte[ObjectTextureFormats.chainBytes(format, width, height)];
                random.nextBytes(chain);
                byte[] blp = blp(width, height, format, tuple[1], tuple[2], chain);
                var decoded =
                        ObjectTextureFormats.decodeChain(
                                format, width, height, ByteBuffer.wrap(chain), 0);
                for (int level = 0; level < ObjectTextureMips.levels(width, height); level++) {
                    var reference =
                            Blp2Decoder.decodeSynchronous(
                                            AssetSource.bytes(blp),
                                            level,
                                            new Blp2Types.Limits(
                                                    1 << 20, 8192, 65536, 1 << 20, 1 << 20),
                                            Cancellation.NONE,
                                            Blp2Types.MipProfile.STRICT)
                                    .rgba();
                    int at = ObjectTextureMips.levelOffset(width, height, level);
                    for (int i = 0; i < reference.length; i++) {
                        int expected = reference[i] & 255, actual = decoded[at + i] & 255;
                        // Interpolated colours and alphas may round one unit apart.
                        assertTrue(
                                Math.abs(expected - actual) <= 1,
                                "format " + format + " " + width + "x" + height + " level " + level
                                        + " byte " + i + ": " + expected + " vs " + actual);
                    }
                }
            }
        }
    }

    /** A BLP2 DXT file holding exactly {@code chain}'s levels. */
    private static byte[] blp(
            int width, int height, int format, int alphaEncoding, int alphaDepth, byte[] chain) {
        var header = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {'B', 'L', 'P', '2'}).putInt(1);
        header.put((byte) 2).put((byte) alphaDepth).put((byte) alphaEncoding).put((byte) 1);
        header.putInt(width).putInt(height);
        for (int level = 0; level < ObjectTextureMips.levels(width, height); level++) {
            header.putInt(
                    20 + level * 4,
                    148 + ObjectTextureFormats.levelOffset(format, width, height, level));
            header.putInt(
                    84 + level * 4, ObjectTextureFormats.levelBytes(format, width, height, level));
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(chain);
        return out.toByteArray();
    }
}
