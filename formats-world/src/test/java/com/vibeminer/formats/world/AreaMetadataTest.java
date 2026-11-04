package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class AreaMetadataTest {
    private static final int HEADER = 20;
    private static final int HEIGHT = HEADER + 128 + 8;

    @Test
    void pinsUnsignedAreaOffsetAndHeightDerivedEntryAgainstActualMeshVertex() {
        byte[] bytes = WorldFormatsTest.adt(0, 0, 0);
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(HEADER + 48, 77).putInt(HEADER + 52, -2).putInt(HEADER + 56, 88);
        var chunk = WorldFormats.adtAreas(bytes, () -> false).chunks().getFirst();
        assertEquals(0xfffffffeL, chunk.areaId());
        assertEquals(300, chunk.originZ());
        assertEquals(309, chunk.entryReference().z());
        var vertices = WorldFormats.adt(bytes, () -> false).vertices();
        assertEquals(vertices[27], chunk.entryReference().x());
        assertEquals(vertices[28], chunk.entryReference().y());
        assertEquals(vertices[29], chunk.entryReference().z());
        assertThrows(
                UnsupportedOperationException.class,
                () -> WorldFormats.adtAreas(bytes, () -> false).chunks().clear());
    }

    @Test
    void combinedTileRetainsIdenticalGeometryAndAreaEvidenceFromOneWalker() {
        byte[] bytes = WorldFormatsTest.adt(0x10000, 0, 1);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(HEADER + 52, 12);
        var combined = WorldFormats.adtTile(bytes, () -> false);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                WorldFormats.adt(bytes, () -> false).vertices(), combined.terrain().vertices());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                WorldFormats.adt(bytes, () -> false).triangles(), combined.terrain().triangles());
        assertEquals(WorldFormats.adtAreas(bytes, () -> false), combined.areas());
        var huge =
                new java.util.AbstractList<WorldFormats.AreaChunk>() {
                    public int size() {
                        return 257;
                    }

                    public WorldFormats.AreaChunk get(int i) {
                        throw new AssertionError("Oversized metadata copied");
                    }
                };
        assertThrows(IllegalArgumentException.class, () -> new WorldFormats.AreaMetadata(huge));
    }

    @Test
    void lowAndHighHolesChooseFirstRenderedCellAndFullyHoledChunkHasNoEntry() {
        var high = WorldFormats.adtAreas(WorldFormatsTest.adt(0x10000, 0, 1), () -> false);
        assertEquals(310, high.chunks().getFirst().entryReference().z());
        var low = WorldFormats.adtAreas(WorldFormatsTest.adt(0, 1, 0), () -> false);
        assertEquals(311, low.chunks().getFirst().entryReference().z());
        var empty = WorldFormats.adtAreas(WorldFormatsTest.adt(0x10000, 0, -1L), () -> false);
        assertNull(empty.chunks().getFirst().entryReference());
        assertEquals(1, empty.chunks().size());
    }

    @Test
    void retainsSourceOrderAndDistinctAreasAcrossChunks() {
        byte[] one = WorldFormatsTest.adt(0, 0, 0);
        byte[] two = WorldFormatsTest.adt(0, 0, 0);
        ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN).putInt(HEADER + 52, 10);
        ByteBuffer.wrap(two)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(HEADER + 4, 3)
                .putInt(HEADER + 8, 7)
                .putInt(HEADER + 52, 20);
        byte[] joined =
                ByteBuffer.allocate(one.length + two.length - 12)
                        .put(one)
                        .put(two, 12, two.length - 12)
                        .array();
        var chunks = WorldFormats.adtAreas(joined, () -> false).chunks();
        assertEquals(2, chunks.size());
        assertEquals(10, chunks.getFirst().areaId());
        assertEquals(20, chunks.getLast().areaId());
        assertEquals(3, chunks.getLast().x());
        assertEquals(7, chunks.getLast().y());
    }

    @Test
    void everyHeightAndDerivedCoordinateIsValidatedEvenOutsideEntryCell() {
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 1_000_000}) {
            byte[] bytes = WorldFormatsTest.adt(0, 0, 0);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(HEIGHT + 144 * 4, bad);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldFormats.adtAreas(bytes, () -> false));
            assertThrows(
                    IllegalArgumentException.class, () -> WorldFormats.adt(bytes, () -> false));
        }
    }

    @Test
    void duplicateChunkMalformedHeightAndCancellationHaveSameAcceptanceAsGeometry() {
        byte[] one = WorldFormatsTest.adt(0, 0, 0);
        byte[] duplicate =
                ByteBuffer.allocate(one.length * 2 - 12)
                        .put(one)
                        .put(one, 12, one.length - 12)
                        .array();
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldFormats.adtAreas(duplicate, () -> false));
        assertThrows(
                IllegalArgumentException.class, () -> WorldFormats.adt(duplicate, () -> false));
        ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN).putInt(HEIGHT - 4, 144 * 4);
        assertThrows(IllegalArgumentException.class, () -> WorldFormats.adtAreas(one, () -> false));
        assertThrows(
                CancellationException.class,
                () -> WorldFormats.adtAreas(WorldFormatsTest.adt(0, 0, 0), () -> true));
    }
}
