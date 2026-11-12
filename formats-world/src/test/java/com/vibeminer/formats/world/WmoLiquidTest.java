package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class WmoLiquidTest {
    static final double U = WmoLiquid.UNIT;

    static ByteBuffer buffer(int count) {
        return ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] chunk(String tag, byte[] bytes) {
        int n = 0;
        for (char c : tag.toCharArray()) {
            n = n << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(n).putInt(bytes.length).put(bytes).array();
    }

    static byte[] join(byte[]... parts) {
        var b = new ByteArrayOutputStream();
        for (var p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    /**
     * A 3 x 2 vertex grid (two tiles along x) at (10, 20, 5): heights 1 2 3 on the first row, 1 4 3
     * on the second; tile flags as given.
     */
    static byte[] mliq(byte first, byte second) {
        var b = buffer(30 + 6 * 8 + 2);
        b.putInt(3).putInt(2).putInt(2).putInt(1);
        b.putFloat(10).putFloat(20).putFloat(5).putShort((short) 0);
        for (float h : new float[] {1, 2, 3, 1, 4, 3}) {
            b.putInt(0).putFloat(h);
        }
        b.put(first).put(second);
        return b.array();
    }

    static byte[] group(int groupFlags, int groupLiquid, byte[] mliq) {
        var header = buffer(68);
        header.putInt(8, groupFlags).putInt(52, groupLiquid);
        return join(
                chunk("MVER", buffer(4).putInt(17).array()),
                chunk(
                        "MOGP",
                        join(header.array(), mliq == null ? new byte[0] : chunk("MLIQ", mliq))));
    }

    @Test
    void heightsAreInterpolatedOnTheTilesDiagonalAndEmptyTilesHoldNone() {
        var liquid = WmoLiquid.read(group(0, 0, mliq((byte) 0, (byte) 0x0F)), () -> false);
        assertEquals(1, liquid.liveTiles());
        assertEquals(1, liquid.heightAt(10, 20), 1e-6);
        // dx > dy: along x first, then up the far edge.
        assertEquals(1 + .5 + .25 * 2, liquid.heightAt(10 + .5 * U, 20 + .25 * U), 1e-6);
        // dy > dx: along y first, then along the far row.
        assertEquals(1 + .25 * 3, liquid.heightAt(10 + .25 * U, 20 + .5 * U), 1e-6);
        assertTrue(Double.isNaN(liquid.heightAt(10 + 1.5 * U, 20 + .5 * U)), "a 0x0F tile");
        assertTrue(Double.isNaN(liquid.heightAt(9, 20)), "outside the grid");
        assertEquals(1, liquid.minHeight());
        assertEquals(4, liquid.maxHeight());
        assertEquals(10, liquid.baseX());
        assertEquals(20 + U, liquid.baseY() + liquid.yTiles() * U, 1e-6);
    }

    @Test
    void theLiquidFieldIsTheOneTheGroupHeaderReads() {
        // A full group WmoFormats.group accepts (as WmoFormatsTest builds one), with distinct
        // fog IDs (+48) and liquid field (+52).
        var header = buffer(68);
        header.putInt(-1).putInt(-2).putInt(0x82009).put(floats(-1, -1, -1, 2, 2, 1));
        header.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        header.putShort((short) 1).putShort((short) 0x1234);
        header.putInt(0x01020304).putInt(6).putInt(-5).putInt(-6).putInt(-7);
        var batch = buffer(24);
        batch.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        batch.putShort((short) 0).putShort((short) -1).putInt(0);
        batch.putShort((short) 3).putShort((short) 0).putShort((short) 2).put((byte) 2);
        batch.put((byte) 255);
        var movi = buffer(6).putShort((short) 0).putShort((short) 1).putShort((short) 2);
        var bytes =
                join(
                        chunk("MVER", buffer(4).putInt(17).array()),
                        chunk(
                                "MOGP",
                                join(
                                        header.array(),
                                        chunk("MOVT", floats(0, 0, 0, 1, 0, 0, 0, 2, 0)),
                                        chunk("MONR", floats(0, 0, 1, 0, 0, 1, 0, 0, 1)),
                                        chunk("MOTV", floats(0, 0, 1, 0, 0, 1)),
                                        chunk("MOVI", movi.array()),
                                        chunk("MOPY", new byte[] {1, (byte) 255}),
                                        chunk("MOBA", batch.array()),
                                        chunk("MLIQ", mliq((byte) 0, (byte) 0)))));
        var parsed = WmoFormats.group(bytes, WmoFormats.Limits.basicPreview(), () -> false);
        var liquid = WmoLiquid.read(bytes, () -> false);
        assertEquals(parsed.header().liquidType(), liquid.groupLiquid());
        assertEquals(6, liquid.groupLiquid(), "not the fog IDs at +48");
        assertEquals(parsed.header().flags(), Integer.toUnsignedLong(liquid.groupFlags()));
    }

    static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float f : values) {
            b.putFloat(f);
        }
        return b.array();
    }

    static int type(int groupFlags, int field, int firstTile, int rootFlags) {
        return WmoLiquid.read(
                        group(groupFlags, field, mliq((byte) firstTile, (byte) 0)), () -> false)
                .liquidType(rootFlags);
    }

    @Test
    void theLiquidTypeFollowsTheExtractorsRules() {
        // Root flag 0x4 clear: GetLiquidTypeId(field + 1), only 15 meaning none: field 0 is
        // water whatever the tiles say.
        assertEquals(13, type(0, 0, 2, 0));
        assertEquals(14, type(0x80000, 0, 0, 0), "MOGP flag 0x80000: ocean");
        assertEquals(14, type(0, 1, 0, 0));
        assertEquals(19, type(0, 2, 0, 0));
        assertEquals(20, type(0, 3, 0, 0));
        // 15: none, so the first tile whose low bits are not 15 gives it (3 + 1: slime).
        assertEquals(20, type(0, 15, 3, 0));
        // Root flag 0x4: GetLiquidTypeId(field), and 0 falls back to the tile.
        assertEquals(19, type(0, 19, 0, 4));
        assertEquals(19, type(0, 3, 0, 4));
        assertEquals(181, type(0, 181, 0, 4));
        assertEquals(13, type(0, 0, 0, 4), "field 0 with the flag: the tile's kind 0 + 1, water");
    }

    @Test
    void noLiquidReadsAsNullAndABrokenOneIsRefused() {
        assertNull(WmoLiquid.read(group(0, 0, null), () -> false), "no MLIQ");
        assertNull(
                WmoLiquid.read(group(0, 0, mliq((byte) 0x0F, (byte) 0x4F)), () -> false),
                "every tile empty");
        byte[] truncated = java.util.Arrays.copyOf(mliq((byte) 0, (byte) 0), 40);
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoLiquid.read(group(0, 0, truncated), () -> false));
        var grid = buffer(30).putInt(3).putInt(2).putInt(5).putInt(1).array();
        assertThrows(
                IllegalArgumentException.class,
                () -> WmoLiquid.read(group(0, 0, grid), () -> false));
    }
}
