package com.vibeminer.formats.images;

import com.vibeminer.formats.images.Blp2Types.Encoding;
import com.vibeminer.formats.images.Blp2Types.Image;
import com.vibeminer.formats.images.Blp2Types.Limits;
import com.vibeminer.formats.images.Blp2Types.Mip;
import com.vibeminer.formats.images.Blp2Types.MipStorage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** C# compatible VMIM v1 image derivative payload, never a substitute for source authentication. */
final class BlpImagePayload {
    private static final int FIXED = 44, MIP_BYTES = 29;

    private BlpImagePayload() {}

    static long size(Image image) {
        return FIXED + (long) image.mips().size() * MIP_BYTES + image.rgbaData().length;
    }

    static byte[] write(Image image) {
        ByteBuffer b =
                ByteBuffer.allocate(Math.toIntExact(size(image))).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'V', 'M', 'I', 'M'})
                .putInt(1)
                .putInt(image.width())
                .putInt(image.height())
                .putInt(image.rowStride())
                .put((byte) image.encoding().ordinal())
                .put((byte) image.alphaDepth())
                .put((byte) image.alphaEncoding())
                .put((byte) image.mipFlags())
                .putInt(image.mipLevel())
                .putInt(image.baseWidth())
                .putInt(image.baseHeight())
                .putInt(image.mips().size());
        for (Mip mip : image.mips()) {
            b.putInt(mip.level())
                    .putInt(mip.width())
                    .putInt(mip.height())
                    .putLong(mip.offset())
                    .putLong(mip.byteLength())
                    .put((byte) mip.storage().ordinal());
        }
        byte[] rgba = image.rgbaData();
        b.putInt(rgba.length).put(rgba);
        return b.array();
    }

    static Image read(byte[] bytes, int level, Limits limits) {
        if (bytes.length < FIXED) {
            return null;
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != 0x4d494d56 || b.getInt() != 1) {
                return null;
            }
            int width = b.getInt(),
                    height = b.getInt(),
                    stride = b.getInt(),
                    code = b.get() & 255,
                    depth = b.get() & 255,
                    alpha = b.get() & 255,
                    flags = b.get() & 255,
                    mipLevel = b.getInt(),
                    baseWidth = b.getInt(),
                    baseHeight = b.getInt(),
                    count = b.getInt();
            if (code >= Encoding.values().length
                    || !Blp2Decoder.isSupported(Encoding.values()[code], depth, alpha)
                    || !Blp2Decoder.isMipFlags(flags)
                    || count < 1
                    || count > 16
                    || mipLevel != level
                    || mipLevel < 0
                    || mipLevel >= count
                    || bytes.length < FIXED + (long) count * MIP_BYTES) {
                return null;
            }
            var mips = new ArrayList<Mip>();
            for (int i = 0; i < count; i++) {
                int n = b.getInt(), w = b.getInt(), h = b.getInt();
                long offset = b.getLong(), length = b.getLong();
                int storage = b.get() & 255;
                if (n != i
                        || storage >= MipStorage.values().length
                        || w < 1
                        || h < 1
                        || offset < 1
                        || length < 1) {
                    return null;
                }
                mips.add(new Mip(n, w, h, offset, length, MipStorage.values()[storage]));
            }
            int rgbaLength = b.getInt();
            Mip selected = mips.get(mipLevel);
            if (width != selected.width()
                    || height != selected.height()
                    || selected.storage() != MipStorage.EXACT
                    || baseWidth != mips.getFirst().width()
                    || baseHeight != mips.getFirst().height()
                    || baseWidth > limits.maxDimension()
                    || baseHeight > limits.maxDimension()
                    || stride != (long) width * 4
                    || rgbaLength != (long) width * height * 4
                    || rgbaLength > limits.maxRgbaBytes()
                    || rgbaLength < 0
                    || b.remaining() != rgbaLength) {
                return null;
            }
            byte[] rgba = new byte[rgbaLength];
            b.get(rgba);
            return new Image(
                    width,
                    height,
                    stride,
                    rgba,
                    Encoding.values()[code],
                    depth,
                    alpha,
                    mipLevel,
                    baseWidth,
                    baseHeight,
                    mips,
                    flags);
        } catch (java.nio.BufferUnderflowException | ArithmeticException e) {
            return null;
        }
    }
}
