package com.vibeminer.formats.images;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.images.Blp2Types.Encoding;
import com.vibeminer.formats.images.Blp2Types.Image;
import com.vibeminer.formats.images.Blp2Types.Limits;
import com.vibeminer.formats.images.Blp2Types.Mip;
import com.vibeminer.formats.images.Blp2Types.MipStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Bounded BLP2 decoder. Authentication belongs to the caller; sources remain caller owned. */
public final class Blp2Decoder {
    public static final String REVISION = "blp2-rgba8-v2";
    public static final int HEADER_BYTES = 148;

    private Blp2Decoder() {}

    private static BlpFormatException error(String code, String message) {
        return new BlpFormatException("BLP_" + code, message);
    }

    public static CompletableFuture<Image> decode(
            AssetSource source, int mip, Limits limits, Cancellation token) {
        return AssetTasks.run(() -> decodeCore(source, mip, 0, limits, token, null));
    }

    public static CompletableFuture<Image> decode(AssetSource source) {
        return decode(source, 0, new Limits(), Cancellation.NONE);
    }

    public static CompletableFuture<List<Mip>> readMips(
            AssetSource source, Limits limits, Cancellation token) {
        return AssetTasks.run(
                () -> {
                    List<Mip> declared = new ArrayList<>();
                    decodeCore(source, 0, 0, limits, token, declared);
                    return List.copyOf(declared);
                });
    }

    public static CompletableFuture<Image> decodeLargestWithin(
            AssetSource source, int maxSide, Limits limits, Cancellation token) {
        if (maxSide < 1) {
            throw new IllegalArgumentException("maxSide must be positive");
        }
        return AssetTasks.run(() -> decodeCore(source, 0, maxSide, limits, token, null));
    }

    public static boolean isMipFlags(int flags) {
        return flags == 0 || flags == 1 || flags == 0x10 || flags == 0x11;
    }

    public static boolean allowsMipLevels(int flags) {
        return (flags & 1) != 0;
    }

    public static boolean isSupported(Encoding encoding, int depth, int alpha) {
        return encoding != null
                && depth >= 0
                && depth <= 255
                && alpha >= 0
                && alpha <= 255
                && profile(
                                encoding == Encoding.PALETTED
                                        ? 1
                                        : encoding == Encoding.BGRA8 ? 3 : 2,
                                depth,
                                alpha)
                        == encoding;
    }

    static Encoding profile(int encoding, int depth, int alpha) {
        if (encoding == 1
                && ((alpha == 0 && (depth == 0 || depth == 1 || depth == 4 || depth == 8))
                        || alpha == 8 && depth == 0)) {
            return Encoding.PALETTED;
        }
        if (encoding == 2) {
            if ((depth == 0 || depth == 1) && alpha == 0) {
                return Encoding.BC1;
            }
            if ((depth == 4 || depth == 8) && alpha == 1) {
                return Encoding.BC2;
            }
            if ((depth == 8 || depth == 72) && alpha == 7) {
                return Encoding.BC3;
            }
        }
        if (encoding == 3 && (depth == 8 && alpha == 0 || depth == 1 && alpha == 8)) {
            return Encoding.BGRA8;
        }
        return null;
    }

    private static Image decodeCore(
            AssetSource source,
            int mipLevel,
            int maxSide,
            Limits limits,
            Cancellation token,
            List<Mip> declared) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(token);
        token.check();
        if (limits == null) {
            limits = new Limits();
        }
        if (limits.maxSourceBytes() < HEADER_BYTES
                || limits.maxDimension() < 1
                || limits.maxPixels() < 1
                || limits.maxMipBytes() < 1
                || limits.maxRgbaBytes() < 4) {
            throw new IllegalArgumentException("Invalid BLP limits");
        }
        if (mipLevel < 0 || mipLevel > 15) {
            throw new IllegalArgumentException("mipLevel must be 0..15");
        }
        long length = source.length();
        if (length > limits.maxSourceBytes()) {
            throw error("LIMIT", "BLP source exceeds its byte bound.");
        }
        if (length < HEADER_BYTES) {
            throw error("INVALID", "BLP2 core header is truncated.");
        }
        byte[] header = read(source, 0, HEADER_BYTES, token);
        if (u(header, 0) != 0x32504c42L) {
            throw error("UNSUPPORTED", "Only BLP2 containers are supported.");
        }
        if (u(header, 4) != 1) {
            throw error(
                    "UNSUPPORTED",
                    "Only BLP2 direct/palette/block content is supported; JPEG is unsupported.");
        }
        int depth = header[9] & 255, alpha = header[10] & 255, flags = header[11] & 255;
        Encoding encoding = profile(header[8] & 255, depth, alpha);
        if (encoding == null) {
            throw error("UNSUPPORTED", "Unsupported BLP colour/alpha profile.");
        }
        if (!isMipFlags(flags)) {
            throw error("UNSUPPORTED", "Unsupported BLP mip flags.");
        }
        int width = dimension(u(header, 12), limits), height = dimension(u(header, 16), limits);
        int minimum = encoding == Encoding.PALETTED ? HEADER_BYTES + 1024 : HEADER_BYTES;
        if (length < minimum) {
            throw error("INVALID", "BLP2 palette is truncated.");
        }
        List<Mip> mips = new ArrayList<>();
        boolean absent = false;
        for (int level = 0; level < 16; level++) {
            token.check();
            long offset = u(header, 20 + level * 4), count = u(header, 84 + level * 4);
            if (offset == 0 && count == 0) {
                absent = true;
                continue;
            }
            if (offset == 0 || count == 0 || absent) {
                throw error(
                        "INVALID",
                        "BLP mip descriptors must form a contiguous prefix with paired offsets and lengths.");
            }
            if (level > 0 && !allowsMipLevels(flags)) {
                throw error("INVALID", "The BLP mip flag excludes additional declared levels.");
            }
            if (level > 0 && (Math.max(width, height) >> (level - 1)) <= 1) {
                throw error("INVALID", "BLP declares levels beyond its terminal one-pixel level.");
            }
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            mips.add(new Mip(level, w, h, offset, count, storage(count, w, h, encoding, depth)));
        }
        if (mips.isEmpty()) {
            throw error("INVALID", "BLP source has no declared top-level mip.");
        }
        if (declared != null) {
            declared.addAll(mips);
            return null;
        }
        if (maxSide > 0) {
            mipLevel = -1;
            for (Mip mip : mips) {
                if (mip.width() <= maxSide
                        && mip.height() <= maxSide
                        && mip.storage() != MipStorage.COMPACT) {
                    mipLevel = mip.level();
                    break;
                }
            }
            if (mipLevel < 0) {
                throw error(
                        "MIP_UNAVAILABLE",
                        "No declared mip fits the requested size; no level was resized or substituted.");
            }
        }
        if (mipLevel >= mips.size()) {
            throw error(
                    "MIP_UNAVAILABLE",
                    "The requested mip is not present; no alternative level was substituted.");
        }
        Mip selected = mips.get(mipLevel);
        if (selected.storage() == MipStorage.COMPACT) {
            throw error("UNSUPPORTED", "Compact BLP block level is not decoded.");
        }
        if (selected.storage() != MipStorage.EXACT) {
            throw error("INVALID", "BLP mip length differs from its exact pixel/block geometry.");
        }
        if (selected.offset() < minimum
                || selected.offset() > length
                || selected.byteLength() > length - selected.offset()) {
            throw error("INVALID", "BLP mip overlaps metadata or lies outside source.");
        }
        for (Mip other : mips) {
            if (other.level() != selected.level()
                    && other.offset() < selected.offset() + selected.byteLength()
                    && selected.offset() < other.offset() + other.byteLength()) {
                throw error("INVALID", "BLP mip overlaps another declared mip.");
            }
        }
        long pixels = (long) selected.width() * selected.height();
        if (pixels > limits.maxPixels()
                || pixels > limits.maxRgbaBytes() / 4
                || pixels > Integer.MAX_VALUE / 4
                || selected.byteLength() > limits.maxMipBytes()
                || selected.byteLength() > Integer.MAX_VALUE) {
            throw error("LIMIT", "Selected BLP mip exceeds decoded pixel/working-byte bounds.");
        }
        byte[] palette =
                encoding == Encoding.PALETTED
                        ? read(source, HEADER_BYTES, 1024, token)
                        : new byte[0];
        byte[] payload = read(source, selected.offset(), (int) selected.byteLength(), token);
        byte[] rgba = new byte[(int) pixels * 4];
        if (encoding == Encoding.PALETTED) {
            palette(payload, palette, rgba, selected.width(), selected.height(), depth, token);
        } else if (encoding == Encoding.BGRA8) {
            bgra(payload, rgba, selected.width(), selected.height(), token);
        } else {
            blocks(payload, rgba, selected.width(), selected.height(), encoding, depth, token);
        }
        token.check();
        if (source.length() != length) {
            throw error("INVALID", "BLP source changed length during decoding.");
        }
        return new Image(
                selected.width(),
                selected.height(),
                selected.width() * 4,
                rgba,
                encoding,
                depth,
                alpha,
                mipLevel,
                width,
                height,
                mips,
                flags);
    }

    private static int dimension(long value, Limits limits) {
        if (value == 0) {
            throw error("INVALID", "BLP dimensions must be positive.");
        }
        if (value > limits.maxDimension()) {
            throw error("LIMIT", "BLP dimensions exceed policy.");
        }
        return (int) value;
    }

    private static MipStorage storage(
            long count, int width, int height, Encoding encoding, int depth) {
        long pixels = (long) width * height;
        try {
            long exact =
                    switch (encoding) {
                        case PALETTED ->
                                Math.addExact(
                                        pixels,
                                        Math.addExact(Math.multiplyExact(pixels, depth), 7) / 8);
                        case BGRA8 -> Math.multiplyExact(pixels, 4);
                        default ->
                                Math.multiplyExact(
                                        Math.multiplyExact((width + 3L) / 4, (height + 3L) / 4),
                                        encoding == Encoding.BC1 ? 8 : 16);
                    };
            if (count == exact) {
                return MipStorage.EXACT;
            }
            if (encoding == Encoding.BC1 || encoding == Encoding.BC2 || encoding == Encoding.BC3) {
                long compact =
                        Math.max(
                                encoding == Encoding.BC1 ? 8 : 16,
                                encoding == Encoding.BC1 ? pixels / 2 : pixels);
                if (count == compact && compact < exact) {
                    return MipStorage.COMPACT;
                }
            }
            return MipStorage.UNRECOGNIZED;
        } catch (ArithmeticException e) {
            throw error("LIMIT", "BLP mip geometry exceeds integer byte domain.");
        }
    }

    static long u(byte[] bytes, int at) {
        return (bytes[at] & 255L)
                | (bytes[at + 1] & 255L) << 8
                | (bytes[at + 2] & 255L) << 16
                | (bytes[at + 3] & 255L) << 24;
    }

    private static int ushort(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private static byte[] read(AssetSource source, long offset, int count, Cancellation token) {
        token.check();
        if (offset > source.length() || count > source.length() - offset) {
            throw error("INVALID", "BLP bytes are truncated.");
        }
        byte[] bytes = new byte[count];
        for (int done = 0; done < count; ) {
            token.check();
            int take = Math.min(65536, count - done);
            byte[] part;
            try {
                part = source.readAt(offset + done, take, token).join();
            } catch (RuntimeException e) {
                if (AssetTasks.unwrap(e) instanceof java.io.EOFException) {
                    throw error("INVALID", "BLP bytes ended unexpectedly.");
                }
                throw e;
            }
            if (part.length != take) {
                throw error("INVALID", "BLP bytes ended unexpectedly.");
            }
            System.arraycopy(part, 0, bytes, done, take);
            done += take;
        }
        return bytes;
    }

    private static void palette(
            byte[] payload,
            byte[] palette,
            byte[] rgba,
            int width,
            int height,
            int depth,
            Cancellation token) {
        int pixels = width * height;
        for (int y = 0; y < height; y++) {
            token.check();
            for (int x = 0; x < width; x++) {
                int index = y * width + x, color = (payload[index] & 255) * 4, target = index * 4;
                rgba[target] = palette[color + 2];
                rgba[target + 1] = palette[color + 1];
                rgba[target + 2] = palette[color];
                rgba[target + 3] =
                        (byte)
                                switch (depth) {
                                    case 0 -> 255;
                                    case 1 ->
                                            ((payload[pixels + index / 8] >> (index % 8)) & 1)
                                                    * 255;
                                    case 4 ->
                                            ((payload[pixels + index / 2] >> (index % 2 * 4)) & 15)
                                                    * 17;
                                    default -> payload[pixels + index] & 255;
                                };
            }
        }
    }

    private static void bgra(
            byte[] payload, byte[] rgba, int width, int height, Cancellation token) {
        for (int y = 0; y < height; y++) {
            token.check();
            for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4;
                rgba[at] = payload[at + 2];
                rgba[at + 1] = payload[at + 1];
                rgba[at + 2] = payload[at];
                rgba[at + 3] = payload[at + 3];
            }
        }
    }

    private static void blocks(
            byte[] payload,
            byte[] rgba,
            int width,
            int height,
            Encoding encoding,
            int depth,
            Cancellation token) {
        int blockBytes = encoding == Encoding.BC1 ? 8 : 16,
                wide = (width + 3) / 4,
                high = (height + 3) / 4;
        int[] colors = new int[16], alphas = new int[8];
        for (int by = 0; by < high; by++) {
            token.check();
            for (int bx = 0; bx < wide; bx++) {
                token.check();
                int at = (by * wide + bx) * blockBytes,
                        colorAt = at + (encoding == Encoding.BC1 ? 0 : 8);
                int c0 = ushort(payload, colorAt), c1 = ushort(payload, colorAt + 2);
                color565(c0, colors, 0);
                color565(c1, colors, 4);
                boolean four = encoding != Encoding.BC1 || c0 > c1;
                for (int ch = 0; ch < 3; ch++) {
                    colors[8 + ch] =
                            four
                                    ? (2 * colors[ch] + colors[4 + ch]) / 3
                                    : (colors[ch] + colors[4 + ch]) / 2;
                    colors[12 + ch] = four ? (colors[ch] + 2 * colors[4 + ch]) / 3 : 0;
                }
                colors[11] = 255;
                colors[15] = four || depth == 0 ? 255 : 0;
                long selectors = u(payload, colorAt + 4), alphaSelectors = 0;
                if (encoding == Encoding.BC3) {
                    alphas[0] = payload[at] & 255;
                    alphas[1] = payload[at + 1] & 255;
                    if (alphas[0] > alphas[1]) {
                        for (int i = 2; i < 8; i++) {
                            alphas[i] = ((8 - i) * alphas[0] + (i - 1) * alphas[1]) / 7;
                        }
                    } else {
                        for (int i = 2; i < 6; i++) {
                            alphas[i] = ((6 - i) * alphas[0] + (i - 1) * alphas[1]) / 5;
                        }
                        alphas[6] = 0;
                        alphas[7] = 255;
                    }
                    for (int i = 0; i < 6; i++) {
                        alphaSelectors |= (payload[at + 2 + i] & 255L) << (8 * i);
                    }
                }
                for (int py = 0; py < 4 && by * 4 + py < height; py++) {
                    for (int px = 0; px < 4 && bx * 4 + px < width; px++) {
                        int pixel = py * 4 + px,
                                choice = (int) ((selectors >>> (pixel * 2)) & 3),
                                target = ((by * 4 + py) * width + bx * 4 + px) * 4;
                        for (int ch = 0; ch < 4; ch++) {
                            rgba[target + ch] = (byte) colors[choice * 4 + ch];
                        }
                        if (encoding == Encoding.BC2) {
                            rgba[target + 3] =
                                    (byte)
                                            (((payload[at + pixel / 2] >>> (pixel % 2 * 4)) & 15)
                                                    * 17);
                        } else if (encoding == Encoding.BC3) {
                            rgba[target + 3] =
                                    (byte) alphas[(int) ((alphaSelectors >>> (pixel * 3)) & 7)];
                        }
                    }
                }
            }
        }
    }

    private static void color565(int value, int[] colors, int at) {
        int r = (value >>> 11) & 31, g = (value >>> 5) & 63, b = value & 31;
        colors[at] = (r << 3) | (r >>> 2);
        colors[at + 1] = (g << 2) | (g >>> 4);
        colors[at + 2] = (b << 3) | (b >>> 2);
        colors[at + 3] = 255;
    }
}
