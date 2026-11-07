package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;

/** Native ADT height-grid facts, not reconstructed from a rendered or reduced mesh. */
public final class TerrainGrid {
    public static final String REVISION = "adt18-native-grid-v1";

    /** MCNR bytes used: 145 normals of three signed bytes each (files pad the chunk to 448). */
    public static final int NORMAL_BYTES = 145 * 3;

    public static final int MAX_RETAINED_BYTES = 1 << 20;

    public enum ColorState {
        ABSENT,
        BGRA8,
        UNSUPPORTED
    }

    public record Vec3(float x, float y, float z) {}

    /** Whether a chunk carries MCNR vertex normals. */
    public enum NormalState {
        ABSENT,
        INT8,
        UNSUPPORTED
    }

    public static final class Chunk {
        private final int ordinal, x, y;
        private final long flags, holes;
        private final float originX, originY, originZ;
        private final float[] heights;
        private final ColorState colorState;
        private final byte[] colors;
        private final NormalState normalState;
        private final byte[] normals;

        public Chunk(
                int ordinal,
                int x,
                int y,
                long flags,
                float originX,
                float originY,
                float originZ,
                float[] heights,
                long holes,
                ColorState colorState,
                byte[] colors) {
            this(
                    ordinal,
                    x,
                    y,
                    flags,
                    originX,
                    originY,
                    originZ,
                    heights,
                    holes,
                    colorState,
                    colors,
                    NormalState.ABSENT,
                    new byte[0]);
        }

        /**
         * {@code normals} are the first {@link #NORMAL_BYTES} MCNR bytes in file order: three
         * signed components per vertex, 127 = 1.0, in the same 9/8 staggered order as the heights.
         * The components map directly onto this grid's axes (see {@link #normal(int, int)}).
         */
        public Chunk(
                int ordinal,
                int x,
                int y,
                long flags,
                float originX,
                float originY,
                float originZ,
                float[] heights,
                long holes,
                ColorState colorState,
                byte[] colors,
                NormalState normalState,
                byte[] normals) {
            Objects.requireNonNull(heights);
            this.colorState = Objects.requireNonNull(colorState);
            Objects.requireNonNull(colors);
            this.normalState = Objects.requireNonNull(normalState);
            Objects.requireNonNull(normals);
            if (ordinal < 0
                    || ordinal >= 256
                    || x < 0
                    || x >= 16
                    || y < 0
                    || y >= 16
                    || flags < 0
                    || flags > 0xffff_ffffL
                    || heights.length != 145
                    || (colorState == ColorState.BGRA8 ? colors.length != 580 : colors.length != 0)
                    || (normalState == NormalState.INT8
                            ? normals.length != NORMAL_BYTES
                            : normals.length != 0)) {
                throw new IllegalArgumentException("Invalid native grid chunk shape");
            }
            for (float value : new float[] {originX, originY, originZ}) {
                if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
                    throw new IllegalArgumentException("Invalid native grid origin");
                }
            }
            for (float value : heights) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Nonfinite grid height");
                }
            }
            this.ordinal = ordinal;
            this.x = x;
            this.y = y;
            this.flags = flags;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.heights = heights.clone();
            this.colors = colors.clone();
            this.normals = normals.clone();
            this.holes = holes;
            // Validate source positions with exactly the legacy geometry arithmetic.
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    var point = position(row, col);
                    if (!Float.isFinite(point.x())
                            || Math.abs(point.x()) > 1_000_000
                            || !Float.isFinite(point.y())
                            || Math.abs(point.y()) > 1_000_000
                            || !Float.isFinite(point.z())
                            || Math.abs(point.z()) > 1_000_000) {
                        throw new IllegalArgumentException("Invalid native grid position");
                    }
                }
            }
        }

        public int ordinal() {
            return ordinal;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public long flags() {
            return flags;
        }

        public float originX() {
            return originX;
        }

        public float originY() {
            return originY;
        }

        public float originZ() {
            return originZ;
        }

        public long holes() {
            return holes;
        }

        public ColorState colorState() {
            return colorState;
        }

        public NormalState normalState() {
            return normalState;
        }

        /**
         * Raw MCNR components in file order; empty unless {@link NormalState#INT8}. A component can
         * be -128, one past -1.0: clamp or normalise before use, as {@link #normal} does.
         */
        public ByteBuffer normals() {
            return ByteBuffer.wrap(normals).asReadOnlyBuffer();
        }

        /**
         * The unit normal of one staggered sample, in the same axes as {@link #position}: the file
         * components are (x, y, z) with z up, so flat ground is about (0, 0, 127). No sign flips
         * are needed even though x and y decrease with row and column: on 139,565 sloped Elwynn
         * Forest samples (retail 12.1.5) this mapping agrees with normals derived from the heights
         * to a mean dot product of 0.998, and every other axis order or sign scored 0.60 or less.
         * Components are clamped to [-127, 127] before normalising.
         */
        public Vec3 normal(int row, int column) {
            if (normalState != NormalState.INT8) {
                throw new IllegalStateException("Chunk has no normals");
            }
            if (row < 0 || row >= 17 || column < 0 || column >= (row % 2 == 0 ? 9 : 8)) {
                throw new IllegalArgumentException("Invalid staggered grid sample");
            }
            int index = ((row / 2) * 17 + (row % 2) * 9 + column) * 3;
            float x = Math.max(-127, normals[index]),
                    y = Math.max(-127, normals[index + 1]),
                    z = Math.max(-127, normals[index + 2]);
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length == 0) {
                return new Vec3(0, 0, 1);
            }
            return new Vec3(x / length, y / length, z / length);
        }

        public FloatBuffer heights() {
            return FloatBuffer.wrap(heights).asReadOnlyBuffer();
        }

        public ByteBuffer colors() {
            return ByteBuffer.wrap(colors).asReadOnlyBuffer();
        }

        public boolean hole(int row, int column) {
            if (row < 0 || row >= 8 || column < 0 || column >= 8) {
                throw new IllegalArgumentException("Invalid grid cell");
            }
            return ((holes >>> (row * 8 + column)) & 1) != 0;
        }

        public Vec3 position(int row, int column) {
            if (row < 0 || row >= 17 || column < 0 || column >= (row % 2 == 0 ? 9 : 8)) {
                throw new IllegalArgumentException("Invalid staggered grid sample");
            }
            int index = (row / 2) * 17 + (row % 2) * 9 + column;
            return new Vec3(
                    (float) (originX - row * (WorldFormats.TILE_SIZE / 256)),
                    (float)
                            (originY
                                    - column * (WorldFormats.TILE_SIZE / 128)
                                    - (row % 2) * (WorldFormats.TILE_SIZE / 256)),
                    originZ + heights[index]);
        }

        long retainedBytes() {
            return 128L + 4L * heights.length + colors.length + normals.length;
        }
    }

    private final List<Chunk> chunks;
    private final long retainedBytes;

    public TerrainGrid(List<Chunk> chunks) {
        Objects.requireNonNull(chunks);
        if (chunks.isEmpty() || chunks.size() > 256) {
            throw new IllegalArgumentException("Invalid grid count");
        }
        this.chunks = List.copyOf(chunks);
        boolean[] seen = new boolean[256];
        long bytes = 2048;
        for (int i = 0; i < this.chunks.size(); i++) {
            var c = this.chunks.get(i);
            int key = c.y() * 16 + c.x();
            if (c.ordinal() != i || seen[key]) {
                throw new IllegalArgumentException("Duplicate or unordered source grid");
            }
            seen[key] = true;
            bytes += c.retainedBytes();
        }
        if (bytes > MAX_RETAINED_BYTES) {
            throw new IllegalArgumentException("Grid metadata byte bound");
        }
        retainedBytes = bytes;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public long retainedBytes() {
        return retainedBytes;
    }
}
