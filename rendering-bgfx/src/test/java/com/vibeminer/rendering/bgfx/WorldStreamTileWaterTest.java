package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldStreamTileWaterTest {
    @TempDir Path directory;

    private Path path() {
        return directory.resolve("tile-42.wst");
    }

    /** Cells laid out on the synthetic full tile footprint (native x/y both in [-533, 0]). */
    static WorldStreamTileFile.Water water(int cells, float offsetX) {
        float[] positions = new float[cells * 12];
        byte[] depths = new byte[cells * 4];
        int[] indices = new int[cells * 6];
        for (int cell = 0; cell < cells; cell++) {
            float x0 = -10 - (cell / 64) * 4f + offsetX, y0 = -10 - (cell % 64) * 4f;
            for (int corner = 0; corner < 4; corner++) {
                int vertex = cell * 4 + corner;
                positions[vertex * 3] = x0 - (corner / 2) * 4f;
                positions[vertex * 3 + 1] = y0 - (corner % 2) * 4f;
                positions[vertex * 3 + 2] = 12.5f;
                depths[vertex] = (byte) (corner * 60);
            }
            int base = cell * 4, at = cell * 6;
            indices[at] = base;
            indices[at + 1] = base + 2;
            indices[at + 2] = base + 1;
            indices[at + 3] = base + 1;
            indices[at + 4] = base + 2;
            indices[at + 5] = base + 3;
        }
        return new WorldStreamTileFile.Water(positions, depths, indices, 1);
    }

    private WorldStreamTileFile.Expected terrain(TerrainScene.Tile tile, boolean textured)
            throws Exception {
        return WorldStreamTileFile.write(
                directory,
                WorldStreamTileFileTest.header(tile, textured),
                tile,
                textured ? WorldStreamTileFileTest.atlas() : null,
                () -> false);
    }

    @Test
    void maximumTerrainAndWaterFillExactPacketBoundAndPackAfterTerrain() throws Exception {
        var tile = WorldStreamTileFileTest.full(true);
        var header = WorldStreamTileFileTest.header(tile, true);
        var before = terrain(tile, true);
        var water = water(WorldStreamTileFile.MAX_WATER_CELLS, 0);
        var expected = WorldStreamTileFile.appendWater(path(), before, header, water, () -> false);
        assertEquals(WorldStreamEnvelope.MAX_PACKET_BYTES, expected.length());
        assertEquals(WorldStreamEnvelope.MAX_PACKET_BYTES, Files.size(path()));
        assertNotEquals(before.sha256(), expected.sha256());
        // The terrain-only expectation no longer describes these bytes.
        assertThrows(
                IOException.class, () -> WorldStreamTileFile.read(path(), before, () -> false));
        var input = WorldStreamTileFile.read(path(), expected, () -> false);
        assertArrayEquals(water.positions(), input.water().positions());
        assertArrayEquals(water.depths(), input.water().depths());
        try (var packed = WorldStreamPackedTile.prepare(input, () -> false);
                var source = packed.take()) {
            assertEquals(65536, source.triangles);
            assertEquals(16384, source.waterTriangles);
            assertEquals(WorldStreamPackedTile.MAX_GEOMETRY_BYTES, source.geometry.capacity());
            assertEquals(
                    BgfxWorldStreamRenderer.MAX_VERTICES * WorldStreamPackedTile.VERTEX_BYTES
                            + BgfxWorldStreamRenderer.MAX_INDICES * 2,
                    source.geometry.capacity());
            assertEquals(37120, source.terrainVertices);
            assertEquals(WorldStreamTileFile.MAX_WATER_CELLS * 4, source.waterVertices);
            assertEquals(16384 * 3, source.waterIndices);
            var data = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            // The first water vertex follows the terrain's, in diagnostic teal.
            int at = 37120 * WorldStreamPackedTile.VERTEX_BYTES;
            assertEquals(-10f, data.getFloat(at));
            assertEquals(-10f, data.getFloat(at + 4));
            assertEquals(12.5f, data.getFloat(at + 8));
            assertEquals((int) WorldStreamPackedTile.WATER_R, data.get(at + 12) & 255);
            assertEquals((int) WorldStreamPackedTile.WATER_G, data.get(at + 13) & 255);
            assertEquals((int) WorldStreamPackedTile.WATER_B, data.get(at + 14) & 255);
            // Water indices follow the terrain's, relative to the first water vertex.
            int indices = source.indexOffset() + 196608 * 2;
            var mesh = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            org.junit.jupiter.api.Assertions.assertTrue(
                    (mesh.getShort(indices) & 0xffff) < source.waterVertices);
        }
    }

    @Test
    void terrainOnlyPacketsStillDecodeWithoutWater() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var expected = terrain(tile, false);
        try (var input = WorldStreamTileFile.read(path(), expected, () -> false)) {
            assertNull(input.water());
        }
    }

    @Test
    void childRejectsWaterBoundToDifferentTerrainOrOffTile() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, false);
        var other =
                WorldStreamTileFileTest.header(
                        TerrainScene.Tile.copyOf(
                                7,
                                19,
                                new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9},
                                new int[] {0, 1, 2},
                                1),
                        false);
        var before = terrain(tile, false);
        // Append claims the actual header but the trailer is encoded for another geometry.
        var foreign =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                WorldStreamTileFile.appendWater(
                                        path(), before, other, water(1, 0), () -> false));
        assertEquals("Water append expectation", foreign.getMessage());
        assertEquals(before.length(), Files.size(path()));

        var offTile =
                WorldStreamTileFile.appendWater(
                        path(), before, header, water(1, 2000), () -> false);
        var refused =
                assertThrows(
                        IOException.class,
                        () -> WorldStreamTileFile.read(path(), offTile, () -> false));
        assertEquals("Water outside terrain tile", refused.getCause().getMessage());
    }

    @Test
    void tamperedTrailerAndChangedTerrainBytesAreRefused() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, false);
        var before = terrain(tile, false);
        try (var channel = FileChannel.open(path(), StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {1}), 5000);
        }
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamTileFile.appendWater(
                                path(), before, header, water(2, 0), () -> false));
        assertEquals(before.length(), Files.size(path()));

        Files.delete(path());
        var clean = terrain(tile, false);
        var expected =
                WorldStreamTileFile.appendWater(path(), clean, header, water(2, 0), () -> false);
        try (var channel = FileChannel.open(path(), StandardOpenOption.WRITE)) {
            channel.write(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 7),
                    clean.length() + 12);
        }
        assertThrows(
                IOException.class, () -> WorldStreamTileFile.read(path(), expected, () -> false));
    }

    @Test
    void canceledAppendRestoresTerrainOnlyPacket() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, false);
        var before = terrain(tile, false);
        var checks = new AtomicInteger();
        // Cancel after the trailer bytes were written: the file must be truncated back.
        int budget = (before.length() + (64 << 10) - 1) / (64 << 10) + 1;
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamTileFile.appendWater(
                                path(),
                                before,
                                header,
                                water(16, 0),
                                () -> checks.incrementAndGet() > budget));
        assertEquals(before.length(), Files.size(path()));
        try (var input = WorldStreamTileFile.read(path(), before, () -> false)) {
            assertNull(input.water());
        }
    }

    @Test
    void ownerMovesOnlyAnExactReplacementOfItsOwnPath() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, false);
        var before = terrain(tile, false);
        var identity = new Object();
        var owner = WorldStreamPacketFile.finalized(before, path(), identity);
        var after =
                WorldStreamTileFile.appendWater(path(), before, header, water(3, 0), () -> false);
        var wrong =
                new WorldStreamTileFile.Expected(
                        after.nonce(),
                        after.scope(),
                        after.transaction(),
                        after.lease(),
                        after.length() - 4,
                        after.sha256());
        assertThrows(IOException.class, () -> owner.moveReplacing(wrong));
        try (var moved = owner.moveReplacing(after)) {
            assertEquals(after, moved.expected());
            assertEquals(identity, moved.destinationIdentity());
        }
        assertEquals(false, Files.exists(path()));
    }

    /**
     * Sloped source cells keep their own corner heights, a merged whole chunk is one 33-yard quad,
     * and a tile may carry many more layers than quads only when each layer is one quad.
     */
    @Test
    void slopedCellsAndMergedChunksSurviveTheTrailerExactly() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, false);
        var before = terrain(tile, false);
        var cells = water(2, 0);
        float[] positions = cells.positions().clone();
        // Cell 0: a river falling 3.25 yards from north to south; cell 1: a whole chunk.
        positions[2] = 20f;
        positions[5] = 20f;
        positions[8] = 16.75f;
        positions[11] = 16.75f;
        float chunk = (float) (com.vibeminer.formats.world.WorldFormats.TILE_SIZE / 16);
        for (int corner = 0; corner < 4; corner++) {
            int at = (4 + corner) * 3;
            positions[at] = -100 - (corner / 2) * chunk;
            positions[at + 1] = -100 - (corner % 2) * chunk;
            positions[at + 2] = 9f;
        }
        var water = new WorldStreamTileFile.Water(positions, cells.depths(), cells.indices(), 2);
        var expected = WorldStreamTileFile.appendWater(path(), before, header, water, () -> false);
        try (var input = WorldStreamTileFile.read(path(), expected, () -> false)) {
            assertArrayEquals(positions, input.water().positions());
            assertEquals(2, input.water().layers());
        }
        // Layers count copied source layers: each is at least one quad, at most 1024.
        var many = water(WorldStreamTileFile.MAX_WATER_LAYERS, 0);
        assertEquals(
                WorldStreamTileFile.MAX_WATER_LAYERS,
                new WorldStreamTileFile.Water(
                                many.positions(),
                                many.depths(),
                                many.indices(),
                                WorldStreamTileFile.MAX_WATER_LAYERS)
                        .layers());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamTileFile.Water(
                                many.positions(),
                                many.depths(),
                                many.indices(),
                                WorldStreamTileFile.MAX_WATER_LAYERS + 1));
    }

    @Test
    void structuralWaterRejectsRepairedOrInventedGeometry() {
        var good = water(2, 0);
        int[] diagonal = good.indices().clone();
        diagonal[1] = 3;
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamTileFile.Water(good.positions(), good.depths(), diagonal, 1));
        float[] infinite = good.positions().clone();
        infinite[4] = Float.NaN;
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamTileFile.Water(infinite, good.depths(), good.indices(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamTileFile.Water(
                                good.positions(), good.depths(), good.indices(), 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamTileFile.Water(new float[0], new byte[0], new int[0], 1));
    }
}
