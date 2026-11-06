package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * A map's WDL: one low-resolution height grid per ADT tile, used for the distant horizon.
 *
 * <p>{@code MAOF} holds 64 x 64 absolute file offsets in the WDT {@code MAIN} order (index = tile.x
 * * 64 + tile.y); 0 means no tile. Each offset points at a {@code MARE} chunk of 545 signed 16-bit
 * absolute heights: a 17 x 17 grid of corners, then a 16 x 16 grid of cell centres, like an ADT
 * chunk's {@code MCVT} at tile scale. An optional {@code MAHO} of 16 unsigned 16-bit rows of hole
 * bits may follow. Other chunks are skipped. A tile that cannot be read is reported with its reason
 * and the rest of the file still reads.
 */
public final class WorldLowRes {
    public static final String REVISION = "wdl-low-res-v1";
    public static final int MAX_SOURCE_BYTES = 8 << 20;
    public static final int CORNERS = 17, CENTRES = 16;
    public static final int HEIGHTS = CORNERS * CORNERS + CENTRES * CENTRES;
    private static final int MVER = 0x4d564552,
            MAOF = 0x4d414f46,
            MARE = 0x4d415245,
            MAHO = 0x4d41484f;

    private WorldLowRes() {}

    /** Why a declared tile was not read. */
    public enum TileIssue {
        /** The offset, or the chunk it points at, runs past the end of the file. */
        TRUNCATED,
        /** The offset does not point at a 1090-byte MARE, or a following MAHO is malformed. */
        BAD_CHUNK
    }

    public record Problem(int x, int y, TileIssue issue) {
        public Problem {
            Objects.requireNonNull(issue);
            if (x < 0 || x >= 64 || y < 0 || y >= 64) {
                throw new IllegalArgumentException("Invalid low-resolution tile");
            }
        }
    }

    /** One tile's heights and holes. Immutable. */
    public static final class Tile {
        private final int x, y;
        private final short[] heights;
        private final long[] holes;

        private Tile(int x, int y, short[] heights, long[] holes) {
            this.x = x;
            this.y = y;
            this.heights = heights;
            this.holes = holes;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        /** Corner height, row and column 0..16. */
        public int corner(int row, int column) {
            if (row < 0 || row >= CORNERS || column < 0 || column >= CORNERS) {
                throw new IllegalArgumentException("Invalid low-resolution corner");
            }
            return heights[row * CORNERS + column];
        }

        /** Cell-centre height, row and column 0..15. */
        public int centre(int row, int column) {
            if (row < 0 || row >= CENTRES || column < 0 || column >= CENTRES) {
                throw new IllegalArgumentException("Invalid low-resolution cell");
            }
            return heights[CORNERS * CORNERS + row * CENTRES + column];
        }

        /**
         * Whether a cell is a hole: bit {@code column} of MAHO row {@code row}. The bit order is
         * inferred from the format description and is checked against real data separately.
         */
        public boolean hole(int row, int column) {
            if (row < 0 || row >= CENTRES || column < 0 || column >= CENTRES) {
                throw new IllegalArgumentException("Invalid low-resolution cell");
            }
            int bit = row * CENTRES + column;
            return (holes[bit / 64] & 1L << (bit % 64)) != 0;
        }

        public boolean hasHoles() {
            return (holes[0] | holes[1] | holes[2] | holes[3]) != 0;
        }
    }

    /** All readable tiles of one WDL, ordered by MAOF index, and the declared tiles refused. */
    public static final class LowRes {
        private final Tile[] byIndex;
        private final List<Tile> tiles;
        private final List<Problem> problems;

        private LowRes(Tile[] byIndex, List<Tile> tiles, List<Problem> problems) {
            this.byIndex = byIndex;
            this.tiles = List.copyOf(tiles);
            this.problems = List.copyOf(problems);
        }

        public List<Tile> tiles() {
            return tiles;
        }

        public List<Problem> problems() {
            return problems;
        }

        /** The tile at WDT coordinates, or null when the WDL has none or it was refused. */
        public Tile tile(int x, int y) {
            if (x < 0 || x >= 64 || y < 0 || y >= 64) {
                return null;
            }
            return byIndex[x * 64 + y];
        }

        /** Heap retained by the decoded heights and hole masks. */
        public long retainedBytes() {
            return 4096L * 8 + tiles.size() * (64L + HEIGHTS * 2L + 4 * 8L);
        }
    }

    public static LowRes read(byte[] bytes, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length < 12 || bytes.length > MAX_SOURCE_BYTES) {
            throw invalid("WDL source bound");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean version = false;
        int offsets = -1, count = 0;
        while (input.remaining() >= 8) {
            check(canceled);
            if (++count > 4096 * 2 + 64) {
                throw invalid("WDL chunk count");
            }
            int tag = input.getInt(), size = input.getInt(), start = input.position();
            if (size < 0 || size > input.limit() - start) {
                // MARE chunks sit inside the region MAOF points at; a top-level walk that meets
                // a malformed size stops here and relies on the offsets alone.
                break;
            }
            if (tag == MVER) {
                if (version || size != 4 || input.getInt(start) != 18) {
                    throw invalid("WDL version");
                }
                version = true;
            } else if (tag == MAOF) {
                if (offsets >= 0 || size != 4096 * 4) {
                    throw invalid("WDL MAOF size");
                }
                offsets = start;
            }
            input.position(start + size);
        }
        if (!version || offsets < 0) {
            throw invalid("WDL requires MVER 18 and MAOF");
        }
        var byIndex = new Tile[4096];
        var tiles = new ArrayList<Tile>();
        var problems = new ArrayList<Problem>();
        for (int index = 0; index < 4096; index++) {
            if (index % 64 == 0) {
                check(canceled);
            }
            long offset = Integer.toUnsignedLong(input.getInt(offsets + index * 4));
            if (offset == 0) {
                continue;
            }
            int x = index / 64, y = index % 64;
            if (offset > bytes.length - 8L - HEIGHTS * 2L) {
                problems.add(new Problem(x, y, TileIssue.TRUNCATED));
                continue;
            }
            int at = (int) offset;
            if (input.getInt(at) != MARE || input.getInt(at + 4) != HEIGHTS * 2) {
                problems.add(new Problem(x, y, TileIssue.BAD_CHUNK));
                continue;
            }
            short[] heights = new short[HEIGHTS];
            for (int i = 0; i < HEIGHTS; i++) {
                heights[i] = input.getShort(at + 8 + i * 2);
            }
            long[] holes = new long[4];
            int next = at + 8 + HEIGHTS * 2;
            if (next <= bytes.length - 8 && input.getInt(next) == MAHO) {
                if (input.getInt(next + 4) != 32 || next + 8 + 32 > bytes.length) {
                    problems.add(new Problem(x, y, TileIssue.BAD_CHUNK));
                    continue;
                }
                for (int row = 0; row < CENTRES; row++) {
                    int mask = Short.toUnsignedInt(input.getShort(next + 8 + row * 2));
                    for (int column = 0; column < CENTRES; column++) {
                        if ((mask & 1 << column) != 0) {
                            int bit = row * CENTRES + column;
                            holes[bit / 64] |= 1L << (bit % 64);
                        }
                    }
                }
            }
            var tile = new Tile(x, y, heights, holes);
            byIndex[index] = tile;
            tiles.add(tile);
        }
        return new LowRes(byIndex, tiles, problems);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail);
    }
}
