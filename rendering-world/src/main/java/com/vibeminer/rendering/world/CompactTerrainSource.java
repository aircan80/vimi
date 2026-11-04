package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Compact descriptive source facts only; construction does not authenticate files or receipts. */
public final class CompactTerrainSource {
    public static final int CHUNKS = 256, HEIGHTS = CHUNKS * 145;
    public static final long PAYLOAD_BYTES = (HEIGHTS + CHUNKS * 3) * 4L + CHUNKS * 8L;

    public enum Outcome {
        READABLE,
        ABSENT,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public record Descriptor(
            int x,
            int y,
            long rootFileDataId,
            String contentKey,
            String geometrySha256,
            Outcome outcome) {
        public Descriptor {
            Objects.requireNonNull(outcome);
            if (x < 0
                    || x >= 64
                    || y < 0
                    || y >= 64
                    || rootFileDataId < 0
                    || rootFileDataId > 0xffff_ffffL
                    || outcome == Outcome.READABLE
                            && (rootFileDataId == 0
                                    || !key(contentKey, 32)
                                    || !key(geometrySha256, 64))
                    || outcome != Outcome.READABLE
                            && (contentKey != null && !key(contentKey, 32)
                                    || geometrySha256 != null && !key(geometrySha256, 64))) {
                throw new IllegalArgumentException("Invalid descriptive terrain source");
            }
        }

        private static boolean key(String value, int length) {
            return value != null && value.length() == length && value.matches("[0-9a-f]+");
        }
    }

    private final Descriptor descriptor;
    private final float[] origins, heights;
    private final long[] holes;

    private CompactTerrainSource(
            Descriptor descriptor, float[] origins, float[] heights, long[] holes) {
        this.descriptor = descriptor;
        this.origins = origins;
        this.heights = heights;
        this.holes = holes;
    }

    public static CompactTerrainSource omitted(Descriptor descriptor) {
        Objects.requireNonNull(descriptor);
        if (descriptor.outcome() == Outcome.READABLE) {
            throw new IllegalArgumentException("Readable source requires grid facts");
        }
        return new CompactTerrainSource(descriptor, null, null, null);
    }

    public static CompactTerrainSource copyOf(
            Descriptor descriptor, TerrainGrid grid, ErrorWork work, BooleanSupplier canceled) {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(grid);
        Objects.requireNonNull(work);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (descriptor.outcome() != Outcome.READABLE || grid.chunks().size() != CHUNKS) {
            throw new IllegalArgumentException("Full readable source required");
        }
        // Reserve the complete bounded copy/validation work before allocating any source arrays.
        if (!work.reserveArithmetic(CHUNKS * (145 + 3 + 1 + 1))) {
            throw new IllegalArgumentException("Source work budget exhausted");
        }
        float[] origins = new float[CHUNKS * 3], heights = new float[HEIGHTS];
        long[] holes = new long[CHUNKS];
        for (int i = 0; i < CHUNKS; i++) {
            check(canceled);
            var chunk = grid.chunks().get(i);
            if (chunk.ordinal() != i || chunk.x() != i % 16 || chunk.y() != i / 16) {
                throw new IllegalArgumentException("Noncanonical source grid");
            }
            origins[i * 3] = chunk.originX();
            origins[i * 3 + 1] = chunk.originY();
            origins[i * 3 + 2] = chunk.originZ();
            chunk.heights().get(heights, i * 145, 145);
            holes[i] = chunk.holes();
        }
        check(canceled);
        return new CompactTerrainSource(descriptor, origins, heights, holes);
    }

    public Descriptor descriptor() {
        return descriptor;
    }

    public boolean readable() {
        return descriptor.outcome() == Outcome.READABLE;
    }

    public long retainedBytes() {
        return 256 + (readable() ? PAYLOAD_BYTES : 0);
    }

    public FloatBuffer heights() {
        requireReadable();
        return FloatBuffer.wrap(heights).asReadOnlyBuffer();
    }

    public FloatBuffer origins() {
        requireReadable();
        return FloatBuffer.wrap(origins).asReadOnlyBuffer();
    }

    public LongBuffer holes() {
        requireReadable();
        return LongBuffer.wrap(holes).asReadOnlyBuffer();
    }

    long holes(int chunk) {
        return holes[chunk];
    }

    TerrainGrid.Chunk chunk(
            int ordinal, float[] scratch, ErrorWork work, BooleanSupplier canceled) {
        requireReadable();
        check(canceled);
        if (ordinal < 0 || ordinal >= CHUNKS || scratch.length != 145) {
            throw new IllegalArgumentException("Invalid chunk scratch");
        }
        if (!work.reserveArithmetic(145 + 145 + 64)) {
            throw new IllegalArgumentException("Chunk work budget exhausted");
        }
        System.arraycopy(heights, ordinal * 145, scratch, 0, 145);
        return new TerrainGrid.Chunk(
                ordinal,
                ordinal % 16,
                ordinal / 16,
                0,
                origins[ordinal * 3],
                origins[ordinal * 3 + 1],
                origins[ordinal * 3 + 2],
                scratch,
                holes[ordinal],
                TerrainGrid.ColorState.ABSENT,
                new byte[0]);
    }

    TerrainGrid.Chunk chunkUncharged(int ordinal, float[] scratch, BooleanSupplier canceled) {
        requireReadable();
        check(canceled);
        if (ordinal < 0 || ordinal >= CHUNKS || scratch.length != 145) {
            throw new IllegalArgumentException("Invalid chunk scratch");
        }
        System.arraycopy(heights, ordinal * 145, scratch, 0, 145);
        return new TerrainGrid.Chunk(
                ordinal,
                ordinal % 16,
                ordinal / 16,
                0,
                origins[ordinal * 3],
                origins[ordinal * 3 + 1],
                origins[ordinal * 3 + 2],
                scratch,
                holes[ordinal],
                TerrainGrid.ColorState.ABSENT,
                new byte[0]);
    }

    void copyPosition(int chunk, int row, int col, float[] target, int offset) {
        int sample = (row / 2) * 17 + (row % 2) * 9 + col;
        target[offset] =
                (float)
                        (origins[chunk * 3]
                                - row * (com.vibeminer.formats.world.WorldFormats.TILE_SIZE / 256));
        target[offset + 1] =
                (float)
                        (origins[chunk * 3 + 1]
                                - col * (com.vibeminer.formats.world.WorldFormats.TILE_SIZE / 128)
                                - (row % 2)
                                        * (com.vibeminer.formats.world.WorldFormats.TILE_SIZE
                                                / 256));
        target[offset + 2] = origins[chunk * 3 + 2] + heights[chunk * 145 + sample];
    }

    private void requireReadable() {
        if (!readable()) {
            throw new IllegalStateException("Source is not readable");
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
