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
}
