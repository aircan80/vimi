package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class TerrainGridTest {
    @Test
    void rejectsOversizedPublicListBeforeAccessingElements() {
        var oversized =
                new AbstractList<TerrainGrid.Chunk>() {
                    @Override
                    public int size() {
                        return 257;
                    }

                    @Override
                    public TerrainGrid.Chunk get(int index) {
                        throw new AssertionError("Oversized list must not be copied");
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> new TerrainGrid(oversized));
    }

    private static byte[] colors(byte[] source, byte[] colors) {
        return sub(source, 0x4d434356, colors);
    }

    /** Appends one sub-chunk to the first (only) MCNK. */
    private static byte[] sub(byte[] source, int tag, byte[] payload) {
        byte[] result = Arrays.copyOf(source, source.length + 8 + payload.length);
        var b = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(16, b.getInt(16) + 8 + payload.length);
        b.position(source.length);
        b.putInt(tag).putInt(payload.length).put(payload);
        return result;
    }

    private static final int MCNR = 0x4d434e52;

    @Test
    void normalsAreCopiedInFileOrderWithPaddingAndBadShapesAreUnsupported() {
        byte[] padded = new byte[448];
        for (int i = 0; i < TerrainGrid.NORMAL_BYTES; i++) {
            padded[i] = (byte) (i % 3 == 1 ? 127 : i % 3 == 0 ? -(i % 100) : i % 50);
        }
        Arrays.fill(padded, TerrainGrid.NORMAL_BYTES, 448, (byte) 99);
        byte[] base = WorldFormatsTest.adt(0, 0, 0);
        var expected = WorldFormats.adt(base, () -> false);
        assertEquals(
                TerrainGrid.NormalState.ABSENT,
                WorldFormats.adtGrid(base, () -> false).chunks().getFirst().normalState());
        for (byte[] payload :
                new byte[][] {padded, Arrays.copyOf(padded, TerrainGrid.NORMAL_BYTES)}) {
            byte[] raw = sub(base, MCNR, payload);
            var chunk = WorldFormats.adtGrid(raw, () -> false).chunks().getFirst();
            assertEquals(TerrainGrid.NormalState.INT8, chunk.normalState());
            var normals = chunk.normals();
            assertEquals(TerrainGrid.NORMAL_BYTES, normals.remaining());
            assertEquals(0, normals.get(0));
            assertEquals(127, normals.get(1));
            assertEquals(2, normals.get(2));
            assertEquals(-3, normals.get(3));
            assertEquals(payload[434], normals.get(434));
            Arrays.fill(raw, (byte) 0);
            assertEquals(127, chunk.normals().get(1));
            assertThrows(ReadOnlyBufferException.class, () -> chunk.normals().put(0, (byte) 1));
            assertFalse(chunk.normals().hasArray());
            assertArrayEquals(
                    expected.vertices(),
                    WorldFormats.adt(sub(base, MCNR, payload), () -> false).vertices());
        }
        for (byte[] bad :
                new byte[][] {
                    sub(base, MCNR, new byte[TerrainGrid.NORMAL_BYTES - 1]),
                    sub(base, MCNR, new byte[449]),
                    sub(sub(base, MCNR, padded), MCNR, padded)
                }) {
            var chunk = WorldFormats.adtGrid(bad, () -> false).chunks().getFirst();
            assertEquals(TerrainGrid.NormalState.UNSUPPORTED, chunk.normalState());
            assertEquals(0, chunk.normals().remaining());
            assertArrayEquals(expected.vertices(), WorldFormats.adt(bad, () -> false).vertices());
        }
        var source = WorldFormats.adtGrid(base, () -> false).chunks().getFirst();
        for (var shape :
                new Object[][] {
                    {TerrainGrid.NormalState.INT8, new byte[0]},
                    {TerrainGrid.NormalState.INT8, new byte[448]},
                    {TerrainGrid.NormalState.ABSENT, new byte[TerrainGrid.NORMAL_BYTES]},
                    {TerrainGrid.NormalState.UNSUPPORTED, new byte[3]}
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new TerrainGrid.Chunk(
                                    0,
                                    0,
                                    0,
                                    0,
                                    source.originX(),
                                    source.originY(),
                                    source.originZ(),
                                    new float[145],
                                    0,
                                    TerrainGrid.ColorState.ABSENT,
                                    new byte[0],
                                    (TerrainGrid.NormalState) shape[0],
                                    (byte[]) shape[1]));
        }
    }

    @Test
    void nativeStaggeredSamplesAndNormalizedHolesMatchUnchangedGeometry() {
        for (int[] hole : new int[][] {{0, 0, 0}, {0, 1, 0}, {0x10000, 0, 1}}) {
            byte[] raw = WorldFormatsTest.adt(hole[0], hole[1], hole[2]);
            var grid = WorldFormats.adtGrid(raw, () -> false);
            var original = WorldFormats.adt(raw, () -> false);
            var c = grid.chunks().getFirst();
            int sample = 0;
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    var p = c.position(row, col);
                    var vertices = original.vertices();
                    assertEquals(vertices[3 * sample], p.x());
                    assertEquals(vertices[3 * sample + 1], p.y());
                    assertEquals(vertices[3 * sample + 2], p.z());
                    sample++;
                }
            }
            assertEquals(145, sample);
            assertEquals(64 - Long.bitCount(c.holes()), original.triangles().length / 12);
            assertEquals(TerrainGrid.ColorState.ABSENT, c.colorState());
            assertTrue(grid.retainedBytes() < TerrainGrid.MAX_RETAINED_BYTES);
        }
    }

    @Test
    void optionalColorsAreImmutableFactsAndCannotWidenOrRemoveLegacyGeometry() {
        byte[] pixels = new byte[580];
        for (int at = 0; at < pixels.length; at += 4) {
            pixels[at] = 10;
            pixels[at + 1] = 20;
            pixels[at + 2] = 30;
            pixels[at + 3] = (byte) 255;
        }
        byte[] raw = colors(WorldFormatsTest.adt(0, 0, 0), pixels);
        var expected = WorldFormats.adt(raw, () -> false);
        var grid = WorldFormats.adtGrid(raw, () -> false);
        var chunk = grid.chunks().getFirst();
        assertEquals(TerrainGrid.ColorState.BGRA8, chunk.colorState());
        assertEquals(10, chunk.colors().get(0));
        Arrays.fill(raw, (byte) 0);
        Arrays.fill(pixels, (byte) 0);
        assertEquals(10, chunk.colors().get(0));
        assertEquals(300, chunk.position(0, 0).z());
        assertThrows(ReadOnlyBufferException.class, () -> chunk.colors().put(0, (byte) 0));
        assertThrows(ReadOnlyBufferException.class, () -> chunk.heights().put(0, 0));
        assertFalse(chunk.colors().hasArray());
        assertFalse(chunk.heights().hasArray());
        for (byte[] bad :
                new byte[][] {
                    colors(WorldFormatsTest.adt(0, 0, 0), new byte[4]),
                    colors(colors(WorldFormatsTest.adt(0, 0, 0), new byte[580]), new byte[580])
                }) {
            assertArrayEquals(expected.vertices(), WorldFormats.adt(bad, () -> false).vertices());
            assertArrayEquals(expected.triangles(), WorldFormats.adt(bad, () -> false).triangles());
            assertEquals(
                    TerrainGrid.ColorState.UNSUPPORTED,
                    WorldFormats.adtGrid(bad, () -> false).chunks().getFirst().colorState());
        }
    }

    @Test
    void sourceOrdinalsRemainDistinctFromNativeAndFilenameAxes() {
        byte[] first = WorldFormatsTest.adt(0, 0, 0), second = WorldFormatsTest.adt(0, 0, 0);
        var b = ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(24, 1);
        b.putFloat(20 + 108, 150);
        byte[] raw = Arrays.copyOf(first, first.length + second.length - 12);
        System.arraycopy(second, 12, raw, first.length, second.length - 12);
        var grid = WorldFormats.adtGrid(raw, () -> false);
        var chunk = grid.chunks().get(1);
        assertEquals(1, chunk.ordinal());
        assertEquals(1, chunk.x());
        assertEquals(0, chunk.y());
        assertEquals(100, chunk.position(0, 0).x());
        assertEquals(150, chunk.position(0, 0).y());
        assertThrows(UnsupportedOperationException.class, () -> grid.chunks().clear());
        assertThrows(IllegalArgumentException.class, () -> chunk.position(1, 8));
        assertThrows(IllegalArgumentException.class, () -> chunk.hole(8, 0));
        assertThrows(CancellationException.class, () -> WorldFormats.adtGrid(raw, () -> true));
        b.putInt(24, 0);
        System.arraycopy(second, 12, raw, first.length, second.length - 12);
        assertThrows(IllegalArgumentException.class, () -> WorldFormats.adtGrid(raw, () -> false));
    }

    /** z = a * (originX - x) + b * (originY - y): the true normal is (a, b, 1), normalised. */
    @Test
    void normalsUseTheGridAxesAndMatchHeightDerivedNormalsOnASlope() {
        double unit = WorldFormats.TILE_SIZE / 256, a = 0.3, b = -0.5;
        float[] heights = new float[145];
        byte[] normals = new byte[TerrainGrid.NORMAL_BYTES];
        double length = Math.sqrt(a * a + b * b + 1);
        for (int row = 0; row < 17; row++) {
            for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                int index = (row / 2) * 17 + (row % 2) * 9 + col;
                heights[index] = (float) (a * row * unit + b * (col * 2 * unit + (row % 2) * unit));
                normals[index * 3] = (byte) Math.round(127 * a / length);
                normals[index * 3 + 1] = (byte) Math.round(127 * b / length);
                normals[index * 3 + 2] = (byte) Math.round(127 / length);
            }
        }
        var chunk =
                new TerrainGrid.Chunk(
                        0,
                        0,
                        0,
                        0,
                        1000,
                        2000,
                        50,
                        heights,
                        0,
                        TerrainGrid.ColorState.ABSENT,
                        new byte[0],
                        TerrainGrid.NormalState.INT8,
                        normals);
        for (int row = 2; row <= 14; row += 2) {
            for (int col = 1; col <= 7; col++) {
                var r0 = chunk.position(row - 2, col);
                var r1 = chunk.position(row + 2, col);
                var c0 = chunk.position(row, col - 1);
                var c1 = chunk.position(row, col + 1);
                double[] tr = {r1.x() - r0.x(), r1.y() - r0.y(), r1.z() - r0.z()};
                double[] tc = {c1.x() - c0.x(), c1.y() - c0.y(), c1.z() - c0.z()};
                double[] derived = {
                    tr[1] * tc[2] - tr[2] * tc[1],
                    tr[2] * tc[0] - tr[0] * tc[2],
                    tr[0] * tc[1] - tr[1] * tc[0]
                };
                double sign = derived[2] < 0 ? -1 : 1;
                double size =
                        Math.sqrt(
                                derived[0] * derived[0]
                                        + derived[1] * derived[1]
                                        + derived[2] * derived[2]);
                var normal = chunk.normal(row, col);
                double dot =
                        sign
                                * (normal.x() * derived[0]
                                        + normal.y() * derived[1]
                                        + normal.z() * derived[2])
                                / size;
                assertTrue(dot > 0.999, "dot " + dot + " at " + row + "," + col);
            }
        }
        var flat = chunk.normal(1, 3);
        assertTrue(flat.x() > 0 && flat.y() < 0 && flat.z() > 0);
        // -128 is clamped to -127 before normalising.
        byte[] extreme = new byte[TerrainGrid.NORMAL_BYTES];
        extreme[0] = -128;
        var edge =
                new TerrainGrid.Chunk(
                        0,
                        0,
                        0,
                        0,
                        1000,
                        2000,
                        50,
                        new float[145],
                        0,
                        TerrainGrid.ColorState.ABSENT,
                        new byte[0],
                        TerrainGrid.NormalState.INT8,
                        extreme);
        assertEquals(-1f, edge.normal(0, 0).x());
        assertEquals(1f, edge.normal(0, 1).z());
        assertThrows(IllegalArgumentException.class, () -> edge.normal(1, 8));
        var none = WorldFormats.adtGrid(WorldFormatsTest.adt(0, 0, 0), () -> false);
        assertThrows(IllegalStateException.class, () -> none.chunks().getFirst().normal(0, 0));
    }

    @Test
    void normalsBeforeHeightsAreStillRead() {
        byte[] padded = new byte[448];
        padded[2] = 127;
        var b =
                ByteBuffer.allocate(12 + 8 + 128 + 8 + 448 + 8 + 580)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x4d564552).putInt(4).putInt(18);
        b.putInt(0x4d434e4b).putInt(128 + 8 + 448 + 8 + 580);
        int start = b.position();
        b.putFloat(start + 104, 100).putFloat(start + 108, 200).putFloat(start + 112, 300);
        b.position(start + 128);
        b.putInt(MCNR).putInt(448).put(padded);
        b.putInt(0x4d435654).putInt(580);
        var chunk = WorldFormats.adtGrid(b.array(), () -> false).chunks().getFirst();
        assertEquals(TerrainGrid.NormalState.INT8, chunk.normalState());
        assertEquals(1f, chunk.normal(0, 0).z());
    }
}
