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
        header.putInt(8, groupFlags).putInt(48, groupLiquid);
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
    void theLiquidTypeFollowsTheRootFlagAndTheLegacyRules() {
        // Legacy (root flag 0x4 clear): the field plus one; 0 or 15 falls back to the tile.
        assertEquals(
                13,
                WmoLiquid.read(group(0, 0, mliq((byte) 0, (byte) 0)), () -> false).liquidType(0));
        assertEquals(
                14,
                WmoLiquid.read(group(0x80000, 0, mliq((byte) 0, (byte) 0)), () -> false)
                        .liquidType(0));
        assertEquals(
                14,
                WmoLiquid.read(group(0, 0, mliq((byte) 1, (byte) 0)), () -> false).liquidType(0));
        assertEquals(
                19,
                WmoLiquid.read(group(0, 2, mliq((byte) 0, (byte) 0)), () -> false).liquidType(0));
        assertEquals(
                20,
                WmoLiquid.read(group(0, 15, mliq((byte) 3, (byte) 0)), () -> false).liquidType(0));
        // Root flag 0x4: the field is the ID.
        assertEquals(
                19,
                WmoLiquid.read(group(0, 19, mliq((byte) 0, (byte) 0)), () -> false).liquidType(4));
        assertEquals(
                181,
                WmoLiquid.read(group(0, 181, mliq((byte) 0, (byte) 0)), () -> false).liquidType(4));
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
