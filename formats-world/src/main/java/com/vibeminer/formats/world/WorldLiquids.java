package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Raw ADT18 MH2O declarations. Layout decoding does not qualify LiquidType/Material or source IO.
 */
public final class WorldLiquids {
    public static final String REVISION = "adt18-mh2o-raw-height-depth0-declared-v2";
    public static final int MAX_PAYLOAD_BYTES = 2 << 20;
    private static final int HEADERS = 256 * 12;

    private WorldLiquids() {}

    public enum Presence {
        ABSENT,
        PRESENT
    }

    public static final class FormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private FormatException(String message) {
            super(message);
        }
    }

    /** Raw attribute bits; absence remains explicit rather than a fabricated default. */
    public record Attributes(boolean present, long fishable, long deep) {}

    public record RawLayer(
            int ordinal,
            int headerOrdinal,
            int instanceOrdinal,
            int liquidType,
            int secondary,
            float minHeight,
            float maxHeight,
            int rawX,
            int rawY,
            int rawWidth,
            int rawHeight,
            int x,
            int y,
            int width,
            int height,
            long bitmapOffset,
            long vertexOffset,
            Attributes attributes) {}

    public static final class RawLiquids {
        private final Presence presence;
        private final byte[] payload;
        private final List<RawLayer> layers;
        private final List<Interval> fixed;

        private RawLiquids(
                Presence presence, byte[] payload, List<RawLayer> layers, List<Interval> fixed) {
            this.presence = presence;
            this.payload = payload;
            this.layers = List.copyOf(layers);
            this.fixed = List.copyOf(fixed);
        }

        public Presence presence() {
            return presence;
        }

        public List<RawLayer> layers() {
            return layers;
        }

        public int payloadBytes() {
            return payload.length;
        }
    }

    public static final class DecodedLayer {
        private final RawLayer layer;
        private final long presentCells;
        private final float[] heights;
        private final byte[] depths;

        private DecodedLayer(RawLayer layer, long cells, float[] heights, byte[] depths) {
            this.layer = layer;
            this.presentCells = cells;
            this.heights = heights;
            this.depths = depths;
        }

        public RawLayer layer() {
            return layer;
        }

        /** LSB first, X before Y in the effective rectangle; not native world coordinates. */
        public long presentCells() {
            return presentCells;
        }

        public FloatBuffer heights() {
            return FloatBuffer.wrap(heights).asReadOnlyBuffer();
        }

        public ByteBuffer depths() {
            return ByteBuffer.wrap(depths).asReadOnlyBuffer();
        }
    }

    private record Interval(long start, long end) {}

    /**
     * Checks the entire ADT using the shared terrain walker before copying just one MH2O payload.
     */
    public static RawLiquids readMh2o(byte[] root, BooleanSupplier canceled) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(canceled);
        int[] source;
        try {
            source = WorldFormats.checkedAdtPayload(root, 0x4d48324f, canceled);
        } catch (IllegalArgumentException malformedRoot) {
            throw invalid("Invalid ADT18 liquid envelope");
        }
        if (source[0] < 0) {
            return new RawLiquids(Presence.ABSENT, new byte[0], List.of(), List.of());
        }
        int size = source[1];
        if (size < HEADERS || size > MAX_PAYLOAD_BYTES) {
            throw invalid("MH2O payload size");
        }
        var input = ByteBuffer.wrap(root, source[0], size).slice().order(ByteOrder.LITTLE_ENDIAN);
        var fixed = new ArrayList<Interval>();
        fixed.add(new Interval(0, HEADERS));
        // Validate every instance table before inspecting any variable data.
        for (int h = 0; h < 256; h++) {
            check(canceled);
            long offset = u32(input, h * 12), count = u32(input, h * 12 + 4);
            if (count > 4 || count > 0 && offset == 0 || count == 0 && offset != 0) {
                throw invalid("MH2O instance count/offset");
            }
            if (count > 0) {
                add(fixed, range(offset, count * 24, size));
            }
        }
        var attributes = new Attributes[256];
        for (int h = 0; h < 256; h++) {
            check(canceled);
            long offset = u32(input, h * 12 + 8);
            if (offset != 0) {
                add(fixed, range(offset, 16, size));
                attributes[h] =
                        new Attributes(
                                true, input.getLong((int) offset), input.getLong((int) offset + 8));
            } else {
                attributes[h] = new Attributes(false, 0, 0);
            }
        }
        var layers = new ArrayList<RawLayer>();
        var variable = new ArrayList<Interval>(fixed);
        for (int h = 0; h < 256; h++) {
            int count = (int) u32(input, h * 12), n = (int) u32(input, h * 12 + 4);
            for (int i = 0; i < n; i++) {
                check(canceled);
                int p = count + i * 24, type = u16(input, p), secondary = u16(input, p + 2);
                float min = input.getFloat(p + 4), max = input.getFloat(p + 8);
                finite(min);
                finite(max);
                if (min > max) {
                    throw invalid("MH2O height bounds");
                }
                int rx = u8(input, p + 12),
                        ry = u8(input, p + 13),
                        rw = u8(input, p + 14),
                        rh = u8(input, p + 15);
                int x = secondary >= 42 ? 0 : rx, y = secondary >= 42 ? 0 : ry;
                int w = secondary >= 42 ? 8 : rw, height = secondary >= 42 ? 8 : rh;
                if (w < 1 || height < 1 || w > 8 || height > 8 || x + w > 8 || y + height > 8) {
                    throw invalid("MH2O rectangle");
                }
                long bitmap = u32(input, p + 16), vertex = u32(input, p + 20);
                if (bitmap != 0) {
                    add(variable, range(bitmap, (w * height + 7) / 8, size));
                }
                if (vertex != 0) {
                    outside(fixed, range(vertex, 1, size));
                }
                layers.add(
                        new RawLayer(
                                layers.size(),
                                h,
                                i,
                                type,
                                secondary,
                                min,
                                max,
                                rx,
                                ry,
                                rw,
                                rh,
                                x,
                                y,
                                w,
                                height,
                                bitmap,
                                vertex,
                                attributes[h]));
            }
        }
        // Direct layout 0 and 2 lengths are known; unresolved formats do not acquire guessed tails.
        for (var layer : layers) {
            check(canceled);
            long bytes = directBytes(layer);
            if (bytes > 0 && layer.vertexOffset() != 0) {
                add(variable, range(layer.vertexOffset(), bytes, size));
            }
        }
        check(canceled);
        return new RawLiquids(
                Presence.PRESENT,
                Arrays.copyOfRange(root, source[0], source[0] + size),
                layers,
                fixed);
    }

    /**
     * Low-level layout reader only. Caller separately qualifies LVF0; zero vertex data is
     * unsupported.
     */
    public static DecodedLayer decodeHeightDepth0(
            RawLiquids owner, int ordinal, BooleanSupplier canceled) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (ordinal < 0 || ordinal >= owner.layers.size()) {
            throw invalid("MH2O layer ordinal");
        }
        var layer = owner.layers.get(ordinal);
        if (layer.vertexOffset() == 0) {
            throw new UnsupportedOperationException("MH2O vertex data absent");
        }
        checkStreams(owner, ordinal, vertexCount(layer) * 5L, canceled);
        var input = ByteBuffer.wrap(owner.payload).order(ByteOrder.LITTLE_ENDIAN);
        int vertices = vertexCount(layer), p = (int) layer.vertexOffset();
        long mask = presentCells(input, layer);
        var heights = new float[vertices];
        var depths = new byte[vertices];
        for (int v = 0; v < vertices; v++) {
            check(canceled);
            heights[v] = input.getFloat(p + v * 4);
            finite(heights[v]);
            depths[v] = input.get(p + vertices * 4 + v);
        }
        int cells = layer.width() * layer.height();
        for (int c = 0; c < cells; c++) {
            if ((mask & (1L << c)) == 0) {
                continue;
            }
            int vertex = (c / layer.width()) * (layer.width() + 1) + c % layer.width();
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    float height = heights[vertex + dy * (layer.width() + 1) + dx];
                    if (height < layer.minHeight() || height > layer.maxHeight()) {
                        throw invalid("MH2O used height outside bounds");
                    }
                }
            }
        }
        return new DecodedLayer(layer, mask, heights, depths);
    }

    /**
     * Low-level layout reader for a layer without vertex heights: one with no vertex data, or one
     * whose caller-qualified format is depth-only (LVF 2: one depth byte per vertex). Every vertex
     * takes the instance's declared height, which must be a single value (min equals max); a
     * declared range gives no per-vertex height and is unsupported. Depths are zero without vertex
     * data.
     */
    public static DecodedLayer decodeDeclaredHeight(
            RawLiquids owner, int ordinal, boolean depthStream, BooleanSupplier canceled) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (ordinal < 0 || ordinal >= owner.layers.size()) {
            throw invalid("MH2O layer ordinal");
        }
        var layer = owner.layers.get(ordinal);
        if (Float.compare(layer.minHeight(), layer.maxHeight()) != 0
                && !(layer.minHeight() == 0 && layer.maxHeight() == 0)) {
            throw new UnsupportedOperationException("MH2O declared height range without heights");
        }
        if (!depthStream && layer.vertexOffset() != 0) {
            throw new UnsupportedOperationException("MH2O vertex data of an unqualified format");
        }
        int vertices = vertexCount(layer);
        boolean stream = depthStream && layer.vertexOffset() != 0;
        checkStreams(owner, ordinal, stream ? vertices : 0, canceled);
        var input = ByteBuffer.wrap(owner.payload).order(ByteOrder.LITTLE_ENDIAN);
        long mask = presentCells(input, layer);
        var heights = new float[vertices];
        var depths = new byte[vertices];
        Arrays.fill(heights, layer.minHeight());
        if (stream) {
            for (int v = 0; v < vertices; v++) {
                check(canceled);
                depths[v] = input.get((int) layer.vertexOffset() + v);
            }
        }
        return new DecodedLayer(layer, mask, heights, depths);
    }

    /** Bytes of a direct-format vertex stream whose length is known, or 0 when unknown. */
    private static long directBytes(RawLayer layer) {
        return switch (layer.secondary()) {
            case 0 -> vertexCount(layer) * 5L;
            case 2 -> vertexCount(layer);
            default -> 0;
        };
    }

    /**
     * Checks the selected layer's stream ({@code ownBytes} long, 0 for none) against every fixed
     * table, bitmap and known-length stream; unknown lengths may not start inside them.
     */
    private static void checkStreams(
            RawLiquids owner, int ordinal, long ownBytes, BooleanSupplier canceled) {
        var intervals = new ArrayList<Interval>(owner.fixed);
        for (var other : owner.layers) {
            check(canceled);
            if (other.bitmapOffset() != 0) {
                add(
                        intervals,
                        range(
                                other.bitmapOffset(),
                                (other.width() * other.height() + 7) / 8,
                                owner.payload.length));
            }
        }
        for (var other : owner.layers) {
            if (other.vertexOffset() == 0) {
                continue;
            }
            long bytes = other.ordinal() == ordinal ? ownBytes : directBytes(other);
            if (bytes > 0) {
                add(intervals, range(other.vertexOffset(), bytes, owner.payload.length));
            }
        }
        // Unknown lengths remain unknown, but their start may not alias a known interval.
        for (var other : owner.layers) {
            if (other.vertexOffset() != 0
                    && other.ordinal() != ordinal
                    && directBytes(other) == 0) {
                outside(intervals, range(other.vertexOffset(), 1, owner.payload.length));
            }
        }
    }

    /** The layer's present cells: its bitmap, or every cell of its rectangle without one. */
    private static long presentCells(ByteBuffer input, RawLayer layer) {
        int cells = layer.width() * layer.height();
        long mask = cells == 64 ? -1L : (1L << cells) - 1;
        if (layer.bitmapOffset() != 0) {
            mask = 0;
            for (int bit = 0; bit < cells; bit++) {
                if ((u8(input, (int) layer.bitmapOffset() + bit / 8) & (1 << (bit % 8))) != 0) {
                    mask |= 1L << bit;
                }
            }
        }
        return mask;
    }

    private static int vertexCount(RawLayer l) {
        return (l.width() + 1) * (l.height() + 1);
    }

    private static int u8(ByteBuffer b, int p) {
        return Byte.toUnsignedInt(b.get(p));
    }

    private static int u16(ByteBuffer b, int p) {
        return Short.toUnsignedInt(b.getShort(p));
    }

    private static long u32(ByteBuffer b, int p) {
        return Integer.toUnsignedLong(b.getInt(p));
    }

    private static Interval range(long start, long length, int size) {
        if (start < HEADERS || length < 1 || start > size || length > size - start) {
            throw invalid("MH2O payload range");
        }
        return new Interval(start, start + length);
    }

    private static void outside(List<Interval> intervals, Interval next) {
        for (var interval : intervals) {
            if (next.start < interval.end && interval.start < next.end) {
                throw invalid("MH2O payload overlap");
            }
        }
    }

    private static void add(List<Interval> intervals, Interval next) {
        outside(intervals, next);
        intervals.add(next);
    }

    private static void finite(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw invalid("MH2O nonfinite/out-of-range height");
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private static FormatException invalid(String message) {
        return new FormatException(message);
    }
}
