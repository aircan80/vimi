package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class WorldLiquidsTest {
    private static final int TABLE = 3072;

    private static byte[] root(byte[] payload) {
        byte[] terrain = WorldFormatsTest.adt(0, 0, 0);
        var b =
                ByteBuffer.allocate(terrain.length + payload.length + 8)
                        .order(ByteOrder.LITTLE_ENDIAN);
        return b.put(terrain).putInt(0x4d48324f).putInt(payload.length).put(payload).array();
    }

    private static byte[] payload(int secondary, int width, int height, int mask) {
        int vertices = (width + 1) * (height + 1);
        var b =
                ByteBuffer.allocate(TABLE + 24 + 16 + 8 + vertices * 5)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, TABLE).putInt(4, 1).putInt(8, TABLE + 24);
        b.position(TABLE);
        b.putShort((short) 1240).putShort((short) secondary).putFloat(2).putFloat(100);
        b.put((byte) 1).put((byte) 2).put((byte) width).put((byte) height);
        b.putInt(TABLE + 40).putInt(TABLE + 48);
        b.putLong(TABLE + 24, 123).putLong(TABLE + 32, 456);
        b.putLong(TABLE + 40, mask);
        b.position(TABLE + 48);
        for (int v = 0; v < vertices; v++) {
            b.putFloat(v + 2);
        }
        for (int v = 0; v < vertices; v++) {
            b.put((byte) (200 + v));
        }
        return b.array();
    }

    @Test
    void absenceEmptyAndCheckedTerrainRemainDistinct() {
        byte[] terrain = WorldFormatsTest.adt(0, 0, 0);
        assertEquals(
                WorldLiquids.Presence.ABSENT,
                WorldLiquids.readMh2o(terrain, () -> false).presence());
        var empty = WorldLiquids.readMh2o(root(new byte[TABLE]), () -> false);
        assertEquals(WorldLiquids.Presence.PRESENT, empty.presence());
        assertTrue(empty.layers().isEmpty());
        assertEquals(TABLE, empty.payloadBytes());
        var before = WorldFormats.adtGrid(terrain, () -> false).chunks().getFirst();
        var after =
                WorldFormats.adtGrid(root(payload(0, 2, 1, 3)), () -> false).chunks().getFirst();
        assertEquals(before.position(3, 2), after.position(3, 2));
        assertEquals(before.holes(), after.holes());
        byte[] malformed = root(new byte[TABLE]);
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 19);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquids.readMh2o(malformed, () -> false));
    }

    @Test
    void planarLayoutBitmapAxesAndUnsignedDepthAreDetached() {
        byte[] bytes = root(payload(0, 2, 2, 0b1001));
        var raw = WorldLiquids.readMh2o(bytes, () -> false);
        var decoded = WorldLiquids.decodeHeightDepth0(raw, 0, () -> false);
        assertEquals(9, decoded.presentCells());
        assertEquals(1, decoded.layer().x());
        assertEquals(2, decoded.layer().y());
        assertEquals(1240, decoded.layer().liquidType());
        assertEquals(123, decoded.layer().attributes().fishable());
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 2, decoded.heights().get(i));
            assertEquals(200 + i, Byte.toUnsignedInt(decoded.depths().get(i)));
        }
        Arrays.fill(bytes, (byte) 0);
        assertEquals(10, WorldLiquids.decodeHeightDepth0(raw, 0, () -> false).heights().get(8));
        assertThrows(ReadOnlyBufferException.class, () -> decoded.heights().put(0, 0));
        assertThrows(ReadOnlyBufferException.class, () -> decoded.depths().put(0, (byte) 0));
        assertThrows(UnsupportedOperationException.class, () -> raw.layers().clear());
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.decodeHeightDepth0(raw, 1, () -> false));
    }

    @Test
    void indirectSecondaryRetainsRawRectangleButUsesFullGridAndNoHeightGuess() {
        byte[] payload = payload(42, 8, 8, -1);
        var b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        b.put(TABLE + 12, (byte) 250).put(TABLE + 14, (byte) 0);
        var raw = WorldLiquids.readMh2o(root(payload), () -> false);
        var layer = raw.layers().getFirst();
        assertEquals(250, layer.rawX());
        assertEquals(0, layer.rawWidth());
        assertEquals(0, layer.x());
        assertEquals(8, layer.width());
        assertEquals(
                81, WorldLiquids.decodeHeightDepth0(raw, 0, () -> false).heights().remaining());
        b.putInt(TABLE + 20, 0);
        var absent = WorldLiquids.readMh2o(root(payload), () -> false);
        assertThrows(
                UnsupportedOperationException.class,
                () -> WorldLiquids.decodeHeightDepth0(absent, 0, () -> false));
    }

    @Test
    void unusedFiniteZeroHeightsArePreservedButUsedOutOfBoundsAndNonfiniteReject() {
        byte[] payload = payload(0, 2, 2, 1);
        var b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(TABLE + 48 + 8 * 4, 0);
        var raw = WorldLiquids.readMh2o(root(payload), () -> false);
        assertEquals(0, WorldLiquids.decodeHeightDepth0(raw, 0, () -> false).heights().get(8));
        b.putLong(TABLE + 40, 8);
        var used = WorldLiquids.readMh2o(root(payload), () -> false);
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.decodeHeightDepth0(used, 0, () -> false));
        b.putLong(TABLE + 40, 0).putFloat(TABLE + 48 + 8 * 4, Float.NaN);
        var nan = WorldLiquids.readMh2o(root(payload), () -> false);
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.decodeHeightDepth0(nan, 0, () -> false));
    }

    @Test
    void malformedCountsDimensionsOffsetsAliasesAndBoundsReject() {
        for (int mode = 0; mode < 13; mode++) {
            byte[] p = payload(0, 2, 2, 3);
            var b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            switch (mode) {
                case 0 -> b.putInt(4, 5);
                case 1 -> b.putInt(0, 1);
                case 2 -> b.putInt(8, TABLE);
                case 3 -> b.putInt(TABLE + 16, TABLE);
                case 4 -> b.putInt(TABLE + 20, TABLE + 40);
                case 5 -> b.putInt(TABLE + 20, Integer.MAX_VALUE);
                case 6 -> b.put(TABLE + 14, (byte) 0);
                case 7 -> b.put(TABLE + 12, (byte) 8);
                case 8 -> b.putFloat(TABLE + 4, Float.NaN);
                case 9 -> b.putFloat(TABLE + 8, -5);
                case 10 -> b.putInt(12, TABLE).putInt(16, 1);
                case 11 -> b.putInt(20, TABLE + 24); // different headers cannot alias attributes
                case 12 -> b.putInt(TABLE + 16, -1);
                default -> throw new AssertionError();
            }
            assertThrows(
                    WorldLiquids.FormatException.class,
                    () -> WorldLiquids.readMh2o(root(p), () -> false),
                    "variant " + mode);
        }
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.readMh2o(root(new byte[TABLE - 1]), () -> false));
        assertThrows(
                WorldLiquids.FormatException.class,
                () ->
                        WorldLiquids.readMh2o(
                                root(new byte[WorldLiquids.MAX_PAYLOAD_BYTES + 1]), () -> false));
        byte[] one = root(payload(0, 2, 2, 3));
        byte[] second = root(payload(0, 2, 2, 3));
        byte[] duplicate =
                Arrays.copyOf(
                        one, one.length + second.length - WorldFormatsTest.adt(0, 0, 0).length);
        System.arraycopy(
                second,
                WorldFormatsTest.adt(0, 0, 0).length,
                duplicate,
                one.length,
                duplicate.length - one.length);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquids.readMh2o(duplicate, () -> false));
    }

    @Test
    void unknownFormatsDoNotGuessTailAndDecodeRechecksKnownIntervals() {
        byte[] p = payload(41, 2, 2, 1);
        var b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(TABLE + 20, p.length - 1);
        var raw = WorldLiquids.readMh2o(root(p), () -> false);
        assertEquals(41, raw.layers().getFirst().secondary());
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.decodeHeightDepth0(raw, 0, () -> false));
    }

    @Test
    void maximumCountAndSharedPerHeaderAttributesStayBounded() {
        var b = ByteBuffer.allocate(TABLE + 1024 * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (int h = 0; h < 256; h++) {
            b.putInt(h * 12, TABLE + h * 96).putInt(h * 12 + 4, 4);
            for (int i = 0; i < 4; i++) {
                b.putShort(TABLE + h * 96 + i * 24 + 2, (short) 42);
            }
        }
        var raw = WorldLiquids.readMh2o(root(b.array()), () -> false);
        assertEquals(1024, raw.layers().size());
        assertEquals(255, raw.layers().getLast().headerOrdinal());
        assertEquals(3, raw.layers().getLast().instanceOrdinal());
        var maximum = raw;
        assertThrows(
                UnsupportedOperationException.class,
                () -> WorldLiquids.decodeHeightDepth0(maximum, 1023, () -> false));

        b = ByteBuffer.allocate(TABLE + 48 + 16 + 2 + 40).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, TABLE).putInt(4, 2).putInt(8, TABLE + 48);
        for (int i = 0; i < 2; i++) {
            int p = TABLE + i * 24;
            b.putFloat(p + 4, 3).putFloat(p + 8, 3);
            b.put(p + 14, (byte) 1).put(p + 15, (byte) 1);
            b.putInt(p + 16, TABLE + 64 + i).putInt(p + 20, TABLE + 66 + i * 20);
            b.put(TABLE + 64 + i, (byte) 1);
            for (int v = 0; v < 4; v++) {
                b.putFloat(TABLE + 66 + i * 20 + v * 4, 3);
            }
        }
        raw = WorldLiquids.readMh2o(root(b.array()), () -> false);
        assertTrue(raw.layers().get(0).attributes() == raw.layers().get(1).attributes());
        assertEquals(1, WorldLiquids.decodeHeightDepth0(raw, 1, () -> false).presentCells());
        b.putInt(TABLE + 24 + 20, TABLE + 66);
        byte[] aliased = b.array();
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.readMh2o(root(aliased), () -> false));
    }

    @Test
    void zeroBitmapIsLayoutAllPresentWhileExplicitClearMaskHasNoUsedHeights() {
        byte[] p = payload(0, 2, 2, 0);
        var b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(TABLE + 48, 0);
        var raw = WorldLiquids.readMh2o(root(p), () -> false);
        assertEquals(0, WorldLiquids.decodeHeightDepth0(raw, 0, () -> false).presentCells());
        b.putFloat(TABLE + 48, 2).putInt(TABLE + 16, 0);
        raw = WorldLiquids.readMh2o(root(p), () -> false);
        assertEquals(15, WorldLiquids.decodeHeightDepth0(raw, 0, () -> false).presentCells());
    }

    @Test
    void cancellationBeforeInspectionAndDuringDecodeIsNotAbsence() {
        assertThrows(
                CancellationException.class,
                () -> WorldLiquids.readMh2o(root(new byte[TABLE]), () -> true));
        var raw = WorldLiquids.readMh2o(root(payload(0, 2, 2, 3)), () -> false);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(
                CancellationException.class,
                () -> WorldLiquids.decodeHeightDepth0(raw, 0, () -> calls.incrementAndGet() > 3));
    }

    @Test
    void layersWithoutVertexHeightsTakeTheirDeclaredHeight() {
        // No vertex data at all: every vertex is the single declared height, depths are zero.
        byte[] none = payload(42, 8, 8, -1);
        var b = ByteBuffer.wrap(none).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(TABLE + 4, 7.5f).putFloat(TABLE + 8, 7.5f).putInt(TABLE + 20, 0);
        var flat = WorldLiquids.readMh2o(root(none), () -> false);
        var decoded = WorldLiquids.decodeDeclaredHeight(flat, 0, false, () -> false);
        assertEquals(-1L, decoded.presentCells());
        assertEquals(81, decoded.heights().remaining());
        for (int v = 0; v < 81; v++) {
            assertEquals(7.5f, decoded.heights().get(v));
            assertEquals(0, decoded.depths().get(v));
        }
        assertThrows(
                UnsupportedOperationException.class,
                () -> WorldLiquids.decodeHeightDepth0(flat, 0, () -> false));
        // Signed zeros are one declared height.
        b.putFloat(TABLE + 4, -0f).putFloat(TABLE + 8, 0f);
        var zero = WorldLiquids.readMh2o(root(none), () -> false);
        assertEquals(
                0f,
                WorldLiquids.decodeDeclaredHeight(zero, 0, false, () -> false).heights().get(0),
                0f);
        // A declared range gives no per-vertex height.
        b.putFloat(TABLE + 4, 2f).putFloat(TABLE + 8, 3f);
        var range = WorldLiquids.readMh2o(root(none), () -> false);
        assertThrows(
                UnsupportedOperationException.class,
                () -> WorldLiquids.decodeDeclaredHeight(range, 0, false, () -> false));
    }

    @Test
    void depthOnlyStreamsCarryDepthsAtTheDeclaredHeight() {
        // Direct format 2: one depth byte per vertex, no heights.
        byte[] depth = payload(2, 2, 2, 0b1111);
        var b = ByteBuffer.wrap(depth).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(TABLE + 4, 5f).putFloat(TABLE + 8, 5f);
        for (int v = 0; v < 9; v++) {
            b.put(TABLE + 48 + v, (byte) (10 + v));
        }
        var raw = WorldLiquids.readMh2o(root(depth), () -> false);
        var decoded = WorldLiquids.decodeDeclaredHeight(raw, 0, true, () -> false);
        assertEquals(0b1111, decoded.presentCells());
        for (int v = 0; v < 9; v++) {
            assertEquals(5f, decoded.heights().get(v));
            assertEquals(10 + v, decoded.depths().get(v));
        }
        // A stream of a format the caller did not qualify is not read as depths.
        assertThrows(
                UnsupportedOperationException.class,
                () -> WorldLiquids.decodeDeclaredHeight(raw, 0, false, () -> false));
        // A depth stream overlapping the bitmap refuses that instance when it is decoded.
        b.putInt(TABLE + 20, TABLE + 40);
        var aliased = WorldLiquids.readMh2o(root(depth), () -> false);
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.decodeDeclaredHeight(aliased, 0, true, () -> false));
    }

    @Test
    void magmaAndSlimeFormatsReadTheirHeightsAndOnlySlimeHasDepth() {
        int width = 2, height = 1, vertices = (width + 1) * (height + 1);
        for (int format : new int[] {1, 3}) {
            // A direct secondary names the format and keeps the layer's own rectangle.
            byte[] base = payload(format, width, height, 0b11);
            // Room for the format's whole stream: height, two u16 coordinates (and depth).
            byte[] stream = java.util.Arrays.copyOf(base, TABLE + 48 + vertices * 9);
            var b = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
            for (int v = 0; v < vertices; v++) {
                b.putShort(TABLE + 48 + vertices * 4 + v * 4, (short) 999);
                b.put(TABLE + 48 + vertices * 8 + v, (byte) (50 + v));
            }
            var raw = WorldLiquids.readMh2o(root(stream), () -> false);
            var decoded = WorldLiquids.decodeHeights(raw, 0, format, () -> false);
            for (int v = 0; v < vertices; v++) {
                assertEquals(v + 2, decoded.heights().get(v), "format " + format);
                assertEquals(format == 3 ? 50 + v : 0, decoded.depths().get(v) & 255);
            }
        }
        var raw = WorldLiquids.readMh2o(root(payload(0, width, height, 0b11)), () -> false);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquids.decodeHeights(raw, 0, 2, () -> false));
    }

    @Test
    void aStreamInsideANeighboursMagmaStreamIsRefused() {
        // Instance 0: direct format 0 (5 bytes per vertex); instance 1: direct format 1 (8 bytes
        // per vertex). Both cover one cell (four vertices). Instance 0's stream starts 8 bytes
        // into instance 1's 32-byte stream, so it overlaps that stream past its first byte.
        int instances = TABLE, magma = TABLE + 48, size = magma + 4 * 8;
        var b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, instances).putInt(4, 2);
        for (int i = 0; i < 2; i++) {
            int at = instances + i * 24;
            b.putShort(at, (short) 1240).putShort(at + 2, (short) (i == 0 ? 0 : 1));
            b.putFloat(at + 4, 0).putFloat(at + 8, 100);
            b.put(at + 12, (byte) 0)
                    .put(at + 13, (byte) 0)
                    .put(at + 14, (byte) 1)
                    .put(at + 15, (byte) 1);
            b.putInt(at + 16, 0).putInt(at + 20, i == 0 ? magma + 8 : magma);
        }
        var raw = WorldLiquids.readMh2o(root(b.array()), () -> false);
        assertThrows(
                WorldLiquids.FormatException.class,
                () -> WorldLiquids.decodeHeightDepth0(raw, 0, () -> false));
    }
}
