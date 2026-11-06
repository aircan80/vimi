package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLowRes;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class HorizonMeshTest {
    private static final double T = WorldFormats.TILE_SIZE;

    private static byte[] chunk(String tag, byte[] payload) {
        int code = 0;
        for (char c : tag.toCharArray()) {
            code = code << 8 | c;
        }
        return ByteBuffer.allocate(8 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(code)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /**
     * Tiles at {x, y}; corner height = 100 + row, centre = 200 + column. {@code holeTile} gets cell
     * (0, 0) and (3, 5) as holes.
     */
    private static WorldLowRes.LowRes lowRes(Set<Integer> tiles, int holeTile) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                chunk(
                        "MVER",
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(18).array()));
        int maofAt = out.size();
        out.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        int[] offsets = new int[4096];
        for (int key : tiles) {
            offsets[key] = out.size();
            var heights =
                    ByteBuffer.allocate(WorldLowRes.HEIGHTS * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < 17; row++) {
                for (int column = 0; column < 17; column++) {
                    heights.putShort((short) (100 + row));
                }
            }
            for (int row = 0; row < 16; row++) {
                for (int column = 0; column < 16; column++) {
                    heights.putShort((short) (200 + column));
                }
            }
            out.writeBytes(chunk("MARE", heights.array()));
            if (key == holeTile) {
                var holes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                holes.putShort(0, (short) 1).putShort(3 * 2, (short) (1 << 5));
                out.writeBytes(chunk("MAHO", holes.array()));
            }
        }
        byte[] bytes = out.toByteArray();
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4096; i++) {
            b.putInt(maofAt + 8 + i * 4, offsets[i]);
        }
        return WorldLowRes.read(bytes, () -> false);
    }

    private static int key(int x, int y) {
        return x * 64 + y;
    }

    @Test
    void positionsUseTheAdtTileOriginsAxesSpacingAndFanWinding() {
        var mesh =
                HorizonMesh.build(
                        lowRes(Set.of(key(30, 40)), -1), 30, 40, 0, (x, y) -> false, () -> false);
        assertEquals(1, mesh.tiles());
        assertEquals(545, mesh.vertexCount());
        assertEquals(1024, mesh.triangleCount());
        var p = mesh.positions();
        // Corner (0, 0) is the tile origin, the same as the first ADT MCNK's origin.
        assertEquals((float) ((32 - 30) * T), p.get(0));
        assertEquals((float) ((32 - 40) * T), p.get(1));
        assertEquals(100 - HorizonMesh.SINK, p.get(2));
        // Corner (2, 3): X falls along a row, Y along a column, both at the chunk size T / 16.
        int c23 = (2 * 17 + 3) * 3;
        assertEquals((float) ((32 - 30) * T - 2 * T / 16), p.get(c23));
        assertEquals((float) ((32 - 40) * T - 3 * T / 16), p.get(c23 + 1));
        assertEquals(102 - HorizonMesh.SINK, p.get(c23 + 2));
        // Centre (0, 4) sits in the middle of its cell.
        int m04 = (289 + 4) * 3;
        assertEquals((float) ((32 - 30) * T - .5 * T / 16), p.get(m04));
        assertEquals((float) ((32 - 40) * T - 4.5 * T / 16), p.get(m04 + 1));
        assertEquals(204 - HorizonMesh.SINK, p.get(m04 + 2));
        // Every point maps back to this tile (or its far edge) with the bookmarks' rule.
        for (int v = 0; v < mesh.vertexCount(); v++) {
            double x = p.get(v * 3), y = p.get(v * 3 + 1);
            double u = 32 - x / T, w = 32 - y / T;
            assertTrue(u > 30 - 1e-4 && u < 31 + 1e-4, "x " + u);
            assertTrue(w > 40 - 1e-4 && w < 41 + 1e-4, "y " + w);
        }
        // Cell (0, 0) uses the ADT fan a-m-b, b-m-e, e-m-d, d-m-a.
        var i = mesh.indices();
        int[] expected = {0, 289, 1, 1, 289, 18, 18, 289, 17, 17, 289, 0};
        for (int k = 0; k < expected.length; k++) {
            assertEquals(expected[k], i.get(k));
        }
    }

    @Test
    void holesDropExactlyTheirCells() {
        var mesh =
                HorizonMesh.build(
                        lowRes(Set.of(key(10, 10)), key(10, 10)),
                        10,
                        10,
                        0,
                        (x, y) -> false,
                        () -> false);
        assertEquals(1024 - 2 * 4, mesh.triangleCount());
        var indices = mesh.indices();
        // No triangle uses the centre of hole cell (0, 0) or (3, 5).
        for (int k = 0; k < indices.limit(); k++) {
            assertTrue(indices.get(k) != 289 && indices.get(k) != 289 + 3 * 16 + 5);
        }
    }

    @Test
    void hiddenTilesAreSkippedAndOnlyTheirNeighboursGetSkirts() {
        var tiles = new HashSet<Integer>();
        for (int x = 18; x <= 22; x++) {
            for (int y = 18; y <= 22; y++) {
                tiles.add(key(x, y));
            }
        }
        var lowRes = lowRes(tiles, -1);
        // The camera tile and its east neighbour are drawn at full detail.
        HorizonMesh.Hidden hidden = (x, y) -> x == 20 && (y == 20 || y == 21);
        var mesh = HorizonMesh.build(lowRes, 20, 20, 2, hidden, () -> false);
        assertEquals(25 - 2, mesh.tiles());
        // Neighbours of the two hidden tiles sharing an edge: (19,20), (21,20), (20,19),
        // (19,21), (21,21), (20,22). Each has one skirt edge: 17 vertices, 32 triangles.
        assertEquals(23 * 545 + 6 * 17, mesh.vertexCount());
        assertEquals(23 * 1024 + 6 * 32, mesh.triangleCount());
        // Skirt vertices are the corner heights lowered by the skirt depth.
        var p = mesh.positions();
        float lowest = Float.MAX_VALUE;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            lowest = Math.min(lowest, p.get(v * 3 + 2));
        }
        assertEquals(100 - HorizonMesh.SINK - HorizonMesh.SKIRT, lowest);
        // Nothing is drawn over a hidden tile's interior.
        for (int v = 0; v < mesh.vertexCount(); v++) {
            double x = p.get(v * 3), y = p.get(v * 3 + 1);
            int tx = (int) Math.floor(32 - x / T), ty = (int) Math.floor(32 - y / T);
            double fx = 32 - x / T - tx, fy = 32 - y / T - ty;
            boolean interior = fx > 1e-3 && fx < 1 - 1e-3 && fy > 1e-3 && fy < 1 - 1e-3;
            assertTrue(!(interior && hidden.hidden(tx, ty)), "vertex inside a hidden tile");
        }
        assertTrue(mesh.payloadBytes() < 1 << 20);
    }

    @Test
    void windowBoundsMapEdgesAndCancellation() {
        var lowRes = lowRes(Set.of(key(0, 0), key(0, 1), key(1, 0)), -1);
        var corner = HorizonMesh.build(lowRes, 0, 0, 1, (x, y) -> false, () -> false);
        assertEquals(3, corner.tiles());
        assertEquals(0, HorizonMesh.build(lowRes, 40, 40, 3, (x, y) -> false, () -> false).tiles());
        for (int[] bad : new int[][] {{-1, 0, 1}, {64, 0, 1}, {0, 0, -1}, {0, 0, 9}}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            HorizonMesh.build(
                                    lowRes, bad[0], bad[1], bad[2], (x, y) -> false, () -> false));
        }
        assertThrows(
                CancellationException.class,
                () -> HorizonMesh.build(lowRes, 0, 0, 1, (x, y) -> false, () -> true));
        // The largest window stays within its stated budget.
        var all = new HashSet<Integer>();
        for (int x = 20; x <= 36; x++) {
            for (int y = 20; y <= 36; y++) {
                all.add(key(x, y));
            }
        }
        var full =
                HorizonMesh.build(
                        lowRes(all, -1),
                        28,
                        28,
                        HorizonMesh.MAX_RADIUS,
                        (x, y) -> false,
                        () -> false);
        assertEquals(289, full.tiles());
        assertEquals(289 * 1024, full.triangleCount());
        assertTrue(full.payloadBytes() < 6 << 20, "bytes " + full.payloadBytes());
    }
}
