package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

/**
 * The liquid of one WMO group file (MOGP's MLIQ), as wowdev WMO (MLIQ) lays it out: a header of
 * vertex and tile counts (x then y), the grid's corner in group coordinates and a material; then
 * one 8-byte vertex per grid point, row by row along y, whose last four bytes are its height (water
 * vertices carry flow bytes before it, magma texture coordinates); then one flag byte per tile. A
 * tile whose low four flag bits are all set ({@code 0x0F}) holds no liquid, as open server
 * emulators read it. The grid's spacing is the terrain cell's ({@link #UNIT}: 533⅓ / 128 yards).
 * The group's MOGP liquid field is kept raw for the caller to interpret with the root's flags
 * ({@link #liquidType}).
 *
 * <p>Bounded; never retains the input.
 */
public final class WmoLiquid {
    /** Grid spacing in group units (yards). */
    public static final double UNIT = WorldFormats.TILE_SIZE / 128;

    /** Largest grid side in vertices; a group spans at most a few tiles. */
    public static final int MAX_SIDE = 1024;

    private static final int MVER = 0x4d564552, MOGP = 0x4d4f4750, MLIQ = 0x4d4c4951;
    private static final int HEADER = 30;

    private final int xVertices, yVertices, xTiles, yTiles;
    private final float baseX, baseY, baseZ;
    private final float[] heights;
    private final byte[] flags;
    private final int groupLiquid, groupFlags;
    private final int liveTiles;
    private final float minHeight, maxHeight;

    private WmoLiquid(
            int xVertices,
            int yVertices,
            int xTiles,
            int yTiles,
            float baseX,
            float baseY,
            float baseZ,
            float[] heights,
            byte[] flags,
            int groupLiquid,
            int groupFlags,
            int liveTiles,
            float minHeight,
            float maxHeight) {
        this.xVertices = xVertices;
        this.yVertices = yVertices;
        this.xTiles = xTiles;
        this.yTiles = yTiles;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.heights = heights;
        this.flags = flags;
        this.groupLiquid = groupLiquid;
        this.groupFlags = groupFlags;
        this.liveTiles = liveTiles;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }

    /**
     * The group's liquid, or null when it has no MLIQ or no tile holds liquid. Throws {@link
     * IllegalArgumentException} for a malformed group or MLIQ.
     */
    public static WmoLiquid read(byte[] bytes, BooleanSupplier canceled) {
        if (bytes == null || bytes.length < 12 || bytes.length > WmoFormats.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("WMO group size outside bounds");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int chunks = 0;
        while (input.remaining() >= 8) {
            check(canceled);
            if (++chunks > WmoFormats.MAX_CHUNKS) {
                throw new IllegalArgumentException("WMO chunk count");
            }
            int tag = input.getInt(), size = input.getInt();
            if (size < 0 || size > input.remaining()) {
                throw new IllegalArgumentException("WMO chunk interval");
            }
            int end = input.position() + size;
            if (tag == MOGP) {
                if (size < 68) {
                    throw new IllegalArgumentException("MOGP header truncated");
                }
                int groupFlags = input.getInt(input.position() + 8);
                int groupLiquid = input.getInt(input.position() + 48);
                input.position(input.position() + 68);
                int nested = 0;
                while (end - input.position() >= 8) {
                    check(canceled);
                    if (++nested > WmoFormats.MAX_CHUNKS) {
                        throw new IllegalArgumentException("WMO chunk count");
                    }
                    int inner = input.getInt(), length = input.getInt();
                    if (length < 0 || length > end - input.position()) {
                        throw new IllegalArgumentException("WMO chunk interval");
                    }
                    if (inner == MLIQ) {
                        return liquid(
                                input.slice(input.position(), length), groupLiquid, groupFlags);
                    }
                    input.position(input.position() + length);
                }
                return null;
            } else if (tag != MVER && chunks == 1) {
                throw new IllegalArgumentException("Not a WMO group");
            }
            input.position(end);
        }
        throw new IllegalArgumentException("WMO group has no MOGP");
    }

    private static WmoLiquid liquid(ByteBuffer chunk, int groupLiquid, int groupFlags) {
        chunk.order(ByteOrder.LITTLE_ENDIAN);
        if (chunk.remaining() < HEADER) {
            throw new IllegalArgumentException("MLIQ header truncated");
        }
        int xVertices = chunk.getInt(), yVertices = chunk.getInt();
        int xTiles = chunk.getInt(), yTiles = chunk.getInt();
        float baseX = chunk.getFloat(), baseY = chunk.getFloat(), baseZ = chunk.getFloat();
        chunk.getShort(); // material
        if (xVertices < 2
                || yVertices < 2
                || xVertices > MAX_SIDE
                || yVertices > MAX_SIDE
                || xTiles != xVertices - 1
                || yTiles != yVertices - 1
                || !finite(baseX)
                || !finite(baseY)
                || !finite(baseZ)) {
            throw new IllegalArgumentException("MLIQ grid");
        }
        long vertexBytes = 8L * xVertices * yVertices, tileBytes = (long) xTiles * yTiles;
        if (chunk.remaining() < vertexBytes + tileBytes) {
            throw new IllegalArgumentException("MLIQ truncated");
        }
        var heights = new float[xVertices * yVertices];
        for (int v = 0; v < heights.length; v++) {
            chunk.getInt();
            heights[v] = chunk.getFloat();
        }
        var flags = new byte[xTiles * yTiles];
        chunk.get(flags);
        int live = 0;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int ty = 0; ty < yTiles; ty++) {
            for (int tx = 0; tx < xTiles; tx++) {
                if ((flags[ty * xTiles + tx] & 0x0F) == 0x0F) {
                    continue;
                }
                for (int c = 0; c < 4; c++) {
                    float h = heights[(ty + (c >> 1)) * xVertices + tx + (c & 1)];
                    if (!finite(h)) {
                        throw new IllegalArgumentException("MLIQ height");
                    }
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
                live++;
            }
        }
        if (live == 0) {
            return null;
        }
        return new WmoLiquid(
                xVertices,
                yVertices,
                xTiles,
                yTiles,
                baseX,
                baseY,
                baseZ,
                heights,
                flags,
                groupLiquid,
                groupFlags,
                live,
                min,
                max);
    }

    /** The MOGP liquid field as stored (its meaning depends on the root's flags). */
    public int groupLiquid() {
        return groupLiquid;
    }

    /** The MOGP flags as stored. */
    public int groupFlags() {
        return groupFlags;
    }

    /**
     * The group's LiquidType ID as open server emulators derive it: with the root's flag 0x4 the
     * MOGP field is the ID, otherwise the field plus one (15 meaning none); with none, the first
     * live tile's low flag bits plus one. Basic IDs 1 to 20 then map by their kind ({@code (id - 1)
     * & 3}: water, ocean, magma, slime) to the WMO rows 13 or 14 (water; 14 when the group has MOGP
     * flag 0x80000, or the kind is ocean), 19 (magma) and 20 (slime). 0 when unknown.
     */
    public int liquidType(int rootFlags) {
        int id;
        if ((rootFlags & 0x4) != 0) {
            id = groupLiquid;
        } else {
            id = groupLiquid == 15 || groupLiquid == 0 ? 0 : groupLiquid + 1;
        }
        if (id == 0) {
            int tile = firstTileKind();
            id = tile == 0x0F ? 0 : tile + 1;
        }
        if (id > 0 && id < 21) {
            return switch ((id - 1) & 3) {
                case 0 -> (groupFlags & 0x80000) != 0 ? 14 : 13;
                case 1 -> 14;
                case 2 -> 19;
                default -> 20;
            };
        }
        return id;
    }

    /** The low four flag bits of the first tile holding liquid (legacy liquid kind). */
    public int firstTileKind() {
        for (byte flag : flags) {
            if ((flag & 0x0F) != 0x0F) {
                return flag & 0x0F;
            }
        }
        return 0x0F;
    }

    public int xTiles() {
        return xTiles;
    }

    public int yTiles() {
        return yTiles;
    }

    /** Tiles holding liquid. */
    public int liveTiles() {
        return liveTiles;
    }

    /** The grid's corner in group coordinates. */
    public float baseX() {
        return baseX;
    }

    public float baseY() {
        return baseY;
    }

    public float baseZ() {
        return baseZ;
    }

    /** Lowest and highest height of a vertex of a tile holding liquid. */
    public float minHeight() {
        return minHeight;
    }

    public float maxHeight() {
        return maxHeight;
    }

    /**
     * The liquid surface height at group coordinates (x, y), or NaN where no tile holds liquid. A
     * tile is split on the diagonal from its (x, y) corner to its (x + 1, y + 1) corner, as open
     * server emulators interpolate it.
     */
    public double heightAt(double x, double y) {
        double fx = (x - baseX) / UNIT, fy = (y - baseY) / UNIT;
        if (!(fx >= 0 && fy >= 0 && fx <= xTiles && fy <= yTiles)) {
            return Double.NaN;
        }
        int tx = Math.min(xTiles - 1, (int) fx), ty = Math.min(yTiles - 1, (int) fy);
        if ((flags[ty * xTiles + tx] & 0x0F) == 0x0F) {
            return Double.NaN;
        }
        double dx = fx - tx, dy = fy - ty;
        double h00 = heights[ty * xVertices + tx],
                h10 = heights[ty * xVertices + tx + 1],
                h01 = heights[(ty + 1) * xVertices + tx],
                h11 = heights[(ty + 1) * xVertices + tx + 1];
        return dx > dy
                ? h00 + dx * (h10 - h00) + dy * (h11 - h10)
                : h00 + dy * (h01 - h00) + dx * (h11 - h01);
    }

    /** Bytes this liquid retains. */
    public long retainedBytes() {
        return 96 + heights.length * 4L + flags.length;
    }

    private static boolean finite(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 1_000_000;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
