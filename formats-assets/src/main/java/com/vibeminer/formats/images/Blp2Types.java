package com.vibeminer.formats.images;

import java.util.List;

public final class Blp2Types {
    private Blp2Types() {}

    public record Limits(
            long maxSourceBytes,
            int maxDimension,
            long maxPixels,
            long maxMipBytes,
            long maxRgbaBytes) {
        public Limits() {
            this(64L << 20, 16_384, 16_777_216, 64L << 20, 64L << 20);
        }
    }

    public enum Encoding {
        PALETTED,
        BC1,
        BC2,
        BC3,
        BGRA8
    }

    public enum MipStorage {
        EXACT,
        COMPACT,
        UNRECOGNIZED
    }

    public record Mip(
            int level, int width, int height, long offset, long byteLength, MipStorage storage) {}

    /** Owned tightly packed top-left RGBA8, straight alpha, no color-space conversion. */
    public record Image(
            int width,
            int height,
            int rowStride,
            byte[] rgba,
            Encoding encoding,
            int alphaDepth,
            int alphaEncoding,
            int mipLevel,
            int baseWidth,
            int baseHeight,
            List<Mip> mips,
            int mipFlags) {
        public Image {
            rgba = rgba.clone();
            mips = List.copyOf(mips);
        }

        byte[] rgbaData() {
            return rgba;
        }

        @Override
        public byte[] rgba() {
            return rgba.clone();
        }
    }
}
