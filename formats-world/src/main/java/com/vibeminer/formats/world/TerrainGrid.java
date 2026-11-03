package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;

/** Native ADT height-grid facts, not reconstructed from a rendered or reduced mesh. */
public final class TerrainGrid {
    public static final String REVISION = "adt18-native-grid-v1";
    public static final int MAX_RETAINED_BYTES = 1 << 20;

    public enum ColorState {
        ABSENT,
        BGRA8,
        UNSUPPORTED
    }

    public record Vec3(float x, float y, float z) {}

    public static final class Chunk {
        private final int ordinal, x, y;
        private final long flags, holes;
        private final float originX, originY, originZ;
        private final float[] heights;
        private final ColorState colorState;
        private final byte[] colors;

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
            Objects.requireNonNull(heights);
            this.colorState = Objects.requireNonNull(colorState);
            Objects.requireNonNull(colors);
            if (ordinal < 0
                    || ordinal >= 256
                    || x < 0
                    || x >= 16
                    || y < 0
                    || y >= 16
                    || flags < 0
                    || flags > 0xffff_ffffL
                    || heights.length != 145
                    || (colorState == ColorState.BGRA8
                            ? colors.length != 580
                            : colors.length != 0)) {
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
            return 128L + 4L * heights.length + colors.length;
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
