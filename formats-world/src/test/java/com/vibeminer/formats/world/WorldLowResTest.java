package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class WorldLowResTest {
    private static byte[] chunk(String tag, byte[] payload) {
        int code = 0;
        for (char c : tag.toCharArray()) {
            code = code << 8 | c;
        }
        var b = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        return b.putInt(code).putInt(payload.length).put(payload).array();
    }

    /** Heights encode their own position: corners 1000 + row*17+col, centres -(row*16+col). */
    private static byte[] mare(int base) {
        var b = ByteBuffer.allocate(WorldLowRes.HEIGHTS * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 17 * 17; i++) {
            b.putShort((short) (base + i));
        }
        for (int i = 0; i < 16 * 16; i++) {
            b.putShort((short) -(base + i));
        }
        return chunk("MARE", b.array());
    }

    private static byte[] maho(int... rowMasks) {
        var b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        for (int row = 0; row < 16; row++) {
            b.putShort((short) (row < rowMasks.length ? rowMasks[row] : 0));
        }
        return chunk("MAHO", b.array());
    }

    /** A WDL with the given tiles; each entry is {x, y, base, holes(0/1)}. */
    private static byte[] wdl(List<int[]> tiles, byte[] prefix) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(chunk("MVER", ints(18)));
        out.writeBytes(prefix);
        int maofAt = out.size();
        out.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        int[] offsets = new int[4096];
        for (var tile : tiles) {
            offsets[tile[0] * 64 + tile[1]] = out.size();
            out.writeBytes(mare(tile[2]));
            if (tile[3] != 0) {
                out.writeBytes(maho(0b101, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x8000));
            }
        }
        byte[] bytes = out.toByteArray();
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4096; i++) {
            b.putInt(maofAt + 8 + i * 4, offsets[i]);
        }
        return bytes;
    }

    private static byte[] ints(int... values) {
        var b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    @Test
    void tilesAreReadInMainOrderWithCornersCentresAndHoles() {
        byte[] source =
                wdl(
                        List.of(new int[] {32, 48, 1000, 1}, new int[] {0, 63, 2000, 0}),
                        chunk("MWMO", new byte[3]));
        var lowRes = WorldLowRes.read(source, () -> false);
        assertEquals(2, lowRes.tiles().size());
        assertTrue(lowRes.problems().isEmpty());
        // Ordered by MAOF index x * 64 + y.
        assertEquals(0, lowRes.tiles().get(0).x());
        assertEquals(63, lowRes.tiles().get(0).y());
        var tile = lowRes.tile(32, 48);
        assertEquals(1000, tile.corner(0, 0));
        assertEquals(1000 + 16, tile.corner(0, 16));
        assertEquals(1000 + 17, tile.corner(1, 0));
        assertEquals(1000 + 288, tile.corner(16, 16));
        assertEquals(-1000, tile.centre(0, 0));
        assertEquals(-(1000 + 255), tile.centre(15, 15));
        assertTrue(tile.hole(0, 0));
        assertFalse(tile.hole(0, 1));
        assertTrue(tile.hole(0, 2));
        assertTrue(tile.hole(15, 15));
        assertFalse(tile.hole(15, 14));
        assertTrue(tile.hasHoles());
        assertFalse(lowRes.tile(0, 63).hasHoles());
        assertNull(lowRes.tile(1, 1));
        assertNull(lowRes.tile(-1, 0));
        assertNull(lowRes.tile(64, 0));
        Arrays.fill(source, (byte) 0);
        assertEquals(1000, tile.corner(0, 0));
        assertTrue(lowRes.retainedBytes() < 64 << 10);
        assertThrows(IllegalArgumentException.class, () -> tile.corner(17, 0));
        assertThrows(IllegalArgumentException.class, () -> tile.centre(0, 16));
        assertThrows(IllegalArgumentException.class, () -> tile.hole(16, 0));
    }

    @Test
    void unreadableTilesAreReportedAndTheRestStillRead() {
        byte[] source = wdl(List.of(new int[] {1, 2, 10, 0}, new int[] {3, 4, 20, 0}), new byte[0]);
        var b = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int maof = 12 + 8;
        // Tile 5,6 points past the end; tile 7,8 points at the MAOF itself (not a MARE).
        b.putInt(maof + (5 * 64 + 6) * 4, source.length - 10);
        b.putInt(maof + (7 * 64 + 8) * 4, 12);
        // Tile 3,4's MARE header is damaged.
        int tile34 = b.getInt(maof + (3 * 64 + 4) * 4);
        b.putInt(tile34 + 4, 1000);
        var lowRes = WorldLowRes.read(source, () -> false);
        assertEquals(1, lowRes.tiles().size());
        assertEquals(10, lowRes.tile(1, 2).corner(0, 0));
        assertEquals(
                List.of(
                        new WorldLowRes.Problem(3, 4, WorldLowRes.TileIssue.BAD_CHUNK),
                        new WorldLowRes.Problem(5, 6, WorldLowRes.TileIssue.TRUNCATED),
                        new WorldLowRes.Problem(7, 8, WorldLowRes.TileIssue.BAD_CHUNK)),
                lowRes.problems());
        // A MAHO of the wrong size, or cut off, keeps the tile's heights without holes.
        byte[] base = wdl(List.of(new int[] {9, 9, 30, 0}), new byte[0]);
        for (byte[] tail :
                new byte[][] {
                    chunk("MAHO", new byte[30]), Arrays.copyOf(chunk("MAHO", new byte[32]), 20)
                }) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(base);
            out.writeBytes(tail);
            var badHoles = WorldLowRes.read(out.toByteArray(), () -> false);
            assertEquals(1, badHoles.tiles().size());
            assertEquals(30, badHoles.tile(9, 9).corner(0, 0));
            assertFalse(badHoles.tile(9, 9).hasHoles());
            assertEquals(
                    List.of(new WorldLowRes.Problem(9, 9, WorldLowRes.TileIssue.TRUNCATED)),
                    badHoles.problems());
        }
    }

    @Test
    void headerAndBoundsAreRequiredAndCancellationStops() {
        byte[] good = wdl(List.of(new int[] {1, 1, 5, 0}), new byte[0]);
        for (byte[] bad :
                new byte[][] {
                    new byte[8],
                    new byte[(8 << 20) + 1],
                    Arrays.copyOfRange(good, 12, good.length),
                    chunk("MVER", ints(18)),
                    ByteBuffer.wrap(good.clone())
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(8, 17)
                            .array()
                }) {
            assertThrows(IllegalArgumentException.class, () -> WorldLowRes.read(bad, () -> false));
        }
        // Two MAOFs before the header is complete are refused.
        var twoMaof = new ByteArrayOutputStream();
        twoMaof.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        twoMaof.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        twoMaof.writeBytes(chunk("MVER", ints(18)));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLowRes.read(twoMaof.toByteArray(), () -> false));
        // The header must be complete within its chunk budget.
        var late = new ByteArrayOutputStream();
        late.writeBytes(chunk("MVER", ints(18)));
        for (int i = 0; i < WorldLowRes.HEADER_CHUNKS; i++) {
            late.writeBytes(chunk("MWMO", new byte[0]));
        }
        late.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLowRes.read(late.toByteArray(), () -> false));
        assertThrows(CancellationException.class, () -> WorldLowRes.read(good, () -> true));
        // A map whose WDL lists no tiles is valid and empty.
        assertTrue(WorldLowRes.read(wdl(List.of(), new byte[0]), () -> false).tiles().isEmpty());
    }

    @Test
    void anyNumberOfPerTileChunksAfterTheHeaderIsFine() {
        // A full map's MARE and MAHO plus newer per-tile chunk types: far more top-level
        // chunks than a fixed walk cap would allow.
        var tiles = new java.util.ArrayList<int[]>();
        for (int i = 0; i < 4096; i += 3) {
            tiles.add(new int[] {i / 64, i % 64, i % 1000, i % 2});
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(wdl(tiles, new byte[0]));
        for (int i = 0; i < 6000; i++) {
            out.writeBytes(chunk(i % 2 == 0 ? "MLDD" : "MLMX", new byte[4]));
        }
        var lowRes = WorldLowRes.read(out.toByteArray(), () -> false);
        assertEquals(tiles.size(), lowRes.tiles().size());
        assertTrue(lowRes.problems().isEmpty());
        assertEquals(999, lowRes.tile(999 / 64, 999 % 64).corner(0, 0));
    }
}
