package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class WorldFormatsTest {
    @Test
    void wdtRetainsOnlyDeclaredTilesAndExactUnsignedFileIds() {
        var b =
                ByteBuffer.allocate(12 + 8 + 4096 * 8 + 8 + 4096 * 32)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x4d564552).putInt(4).putInt(18).putInt(0x4d41494e).putInt(4096 * 8);
        b.putInt(b.position() + (2 * 64 + 3) * 8, 1);
        b.position(12 + 8 + 4096 * 8);
        b.putInt(0x4d414944).putInt(4096 * 32);
        int at = b.position() + (2 * 64 + 3) * 32;
        b.putInt(at, -2);
        b.putInt(at + 4, -1);
        b.putInt(at + 8, -3);
        b.putInt(at + 12, 77);
        b.putInt(at + 16, 88);
        b.putInt(at + 24, -4);
        b.putInt(at + 20, 55);
        b.putInt(at + 28, 66);
        var world = WorldFormats.wdt(b.array(), () -> false);
        assertEquals(1, world.tiles().size());
        var tile = world.tiles().getFirst();
        assertEquals(2, tile.x());
        assertEquals(3, tile.y());
        assertEquals(0xfffffffeL, tile.rootId());
        assertEquals(55, tile.mapTextureId());
        assertEquals(66, tile.minimapId());
        assertEquals(0xffff_ffffL, tile.obj0Id());
        assertEquals(0xffff_fffdL, tile.obj1Id());
        assertEquals(77, tile.tex0Id());
        assertEquals(88, tile.lodId());
        assertEquals(0xffff_fffcL, tile.mapTextureNId());
        var legacy = new WorldFormats.Tile(2, 3, -1, 55, 66);
        assertEquals(-1, legacy.rootId());
        assertEquals(0, legacy.obj0Id());
        assertEquals(0, legacy.obj1Id());
        assertEquals(0, legacy.tex0Id());
        assertEquals(0, legacy.lodId());
        assertEquals(0, legacy.mapTextureNId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldFormats.Tile(2, 3, 0, 0, 0, -1, 0, 0, 0, 0));
    }

    @Test
    void adtKeepsNativePositionsAndInnerHeightSamples() {
        var t = WorldFormats.adt(adt(0, 0, 0), () -> false);
        assertEquals(1, t.chunks());
        assertEquals(145 * 3, t.vertices().length);
        assertEquals(64 * 12, t.triangles().length);
        assertEquals(100, t.vertices()[0]);
        assertEquals(200, t.vertices()[1]);
        assertEquals(300, t.vertices()[2]);
        assertEquals(309, t.vertices()[9 * 3 + 2]);
        assertTrue(t.vertices()[9 * 3] < 100 && t.vertices()[9 * 3 + 1] < 200);
        var copy = t.vertices();
        copy[0] = 0;
        assertEquals(100, t.vertices()[0]);
    }

    @Test
    void lowResolutionAndHighResolutionHolesRemoveDifferentCellCounts() {
        assertEquals(60 * 12, WorldFormats.adt(adt(0, 1, 0), () -> false).triangles().length);
        assertEquals(63 * 12, WorldFormats.adt(adt(0x10000, 0, 1), () -> false).triangles().length);
    }

    @Test
    void rejectsTruncatedInvalidVersionAndOversizedChunks() {
        var bytes = adt(0, 0, 0);
        bytes[8] = 17;
        assertThrows(IllegalArgumentException.class, () -> WorldFormats.adt(bytes, () -> false));
        assertThrows(
                IllegalArgumentException.class, () -> WorldFormats.adt(new byte[12], () -> false));
        var bad =
                ByteBuffer.allocate(12)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x4d564552)
                        .putInt(Integer.MAX_VALUE)
                        .putInt(18)
                        .array();
        assertThrows(IllegalArgumentException.class, () -> WorldFormats.wdt(bad, () -> false));
    }

    @Test
    void cancellationIsPropagated() {
        assertThrows(CancellationException.class, () -> WorldFormats.adt(adt(0, 0, 0), () -> true));
    }

    static byte[] adt(int flags, int low, long high) {
        var b = ByteBuffer.allocate(12 + 8 + 128 + 8 + 145 * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x4d564552).putInt(4).putInt(18).putInt(0x4d434e4b).putInt(128 + 8 + 145 * 4);
        int start = b.position();
        b.putInt(start, flags);
        b.putLong(start + 20, high);
        b.putShort(start + 60, (short) low);
        b.putFloat(start + 104, 100).putFloat(start + 108, 200).putFloat(start + 112, 300);
        b.position(start + 128);
        b.putInt(0x4d435654).putInt(145 * 4);
        for (int i = 0; i < 145; i++) {
            b.putFloat(i);
        }
        return b.array();
    }
}
