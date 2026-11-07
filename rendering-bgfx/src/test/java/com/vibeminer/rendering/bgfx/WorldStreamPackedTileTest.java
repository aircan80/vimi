package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainScene;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldStreamPackedTileTest {
    @TempDir Path directory;

    private WorldStreamTileFile.Payload read(TerrainScene.Tile tile, boolean textured)
            throws Exception {
        var expected =
                WorldStreamTileFile.write(
                        directory,
                        WorldStreamTileFileTest.header(tile, textured),
                        tile,
                        textured ? WorldStreamTileFileTest.atlas() : null,
                        () -> false);
        return WorldStreamTileFile.read(directory.resolve("tile-42.wst"), expected, () -> false);
    }

    @Test
    void nativeAxisColorAndAbsoluteCoordinatesPackWithoutRawInputRetention() throws Exception {
        var tile =
                TerrainScene.Tile.copyOf(
                        7, 19, new float[] {10, 0, 0, 10, 1, 0, 10, 0, 1}, new int[] {0, 1, 2}, 1);
        var input = read(tile, false);
        try (var packed = WorldStreamPackedTile.prepare(input, () -> false);
                var source = packed.take()) {
            assertThrows(IllegalStateException.class, input::tile);
            assertThrows(IllegalStateException.class, input::atlas);
            assertThrows(IllegalStateException.class, packed::take);
            // Three 24-byte vertices and three u16 indices.
            assertEquals(3 * 24 + 3 * 2, source.payloadBytes());
            assertNull(source.atlas);
            var data = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            for (int v = 0; v < 3; v++) {
                assertEquals(10f, data.getFloat(v * 24));
            }
            assertEquals(1f, data.getFloat(24 + 4));
            assertEquals(1f, data.getFloat(48 + 8));
            // The unlit low-altitude grass tint (the shader lights it, #373), as RGBA8.
            assertEquals(72, data.get(12) & 255);
            assertEquals(116, data.get(13) & 255);
            assertEquals(68, data.get(14) & 255);
            assertEquals(255, data.get(15) & 255);
            // No MCCV: all four colour bytes zero.
            assertEquals(0, data.getInt(16));
            // No MCNR: the triangle's own +X normal, as unsigned normalized bytes.
            assertEquals(255, data.get(20) & 255);
            assertEquals(128, data.get(21) & 255);
            assertEquals(128, data.get(22) & 255);
            assertEquals(255, data.get(23) & 255);
            assertEquals(3, source.terrainVertices);
            assertEquals(3, source.terrainIndices);
            assertEquals(72, source.indexOffset());
            assertEquals(0, data.getShort(72));
            assertEquals(1, data.getShort(74));
            assertEquals(2, data.getShort(76));
            assertArrayEquals(new int[] {0, 3}, source.runs);
        }
    }

    /**
     * Issue #373: the packet carries each vertex's MCNR normal and MCCV colour; the packed vertex
     * takes the normal (else the triangle's own) and the colour (else four zero bytes).
     */
    @Test
    void mcnrNormalsAndMccvColoursReachThePackedVertex() throws Exception {
        byte[] attributes = new byte[3 * 8];
        // Vertex 0: MCNR straight up, MCCV (10, 20, 30). Vertex 1: tilted MCNR, no MCCV.
        attributes[2] = 127;
        attributes[4] = 10;
        attributes[5] = 20;
        attributes[6] = 30;
        attributes[7] = (byte) 255;
        attributes[8] = (byte) -128; // clamped to -127
        attributes[10] = 0;
        var tile =
                TerrainScene.Tile.copyOf(
                        7,
                        19,
                        new float[] {10, 0, 0, 10, 1, 0, 10, 0, 1},
                        new int[] {0, 1, 2},
                        1,
                        attributes);
        var input = read(tile, false);
        // The packet round-trips them exactly.
        assertArrayEquals(attributes, attributesOf(input.tile()));
        try (var packed = WorldStreamPackedTile.prepare(input, () -> false);
                var source = packed.take()) {
            var data = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            // Vertex 0: MCCV and the MCNR normal (0, 0, 1).
            assertEquals(10, data.get(16) & 255);
            assertEquals(20, data.get(17) & 255);
            assertEquals(30, data.get(18) & 255);
            assertEquals(255, data.get(19) & 255);
            assertEquals(128, data.get(20) & 255);
            assertEquals(128, data.get(21) & 255);
            assertEquals(255, data.get(22) & 255);
            // Vertex 1: no MCCV; MCNR (-127, 0, 0) is -X.
            assertEquals(0, data.getInt(24 + 16));
            assertEquals(0, data.get(24 + 20) & 255);
            assertEquals(128, data.get(24 + 21) & 255);
            // Vertex 2: no MCNR, so the triangle's +X normal.
            assertEquals(255, data.get(48 + 20) & 255);
        }
    }

    private static byte[] attributesOf(TerrainScene.Tile tile) {
        var buffer = tile.attributes();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    @Test
    void maximumPackedOwnershipIsGeometryPlusMipChainAndOneShotTransferSurvivesWrapperClose()
            throws Exception {
        var input = read(WorldStreamTileFileTest.full(true), true);
        var packed = WorldStreamPackedTile.prepare(input, () -> false);
        try (var source = packed.take()) {
            packed.close();
            assertThrows(IllegalStateException.class, input::tile);
            assertEquals(WorldStreamNativeProof.TERRAIN_FIXTURE_BYTES, source.payloadBytes());
            assertEquals(2_682_196, WorldStreamNativeProof.TERRAIN_FIXTURE_BYTES);
            assertEquals(3_566_932, WorldStreamPackedTile.MAX_PAYLOAD_BYTES);
            assertTrue(source.payloadBytes() <= WorldStreamPackedTile.MAX_PAYLOAD_BYTES);
            assertEquals(37120 * 24 + 196608 * 2, source.geometry.capacity());
            assertEquals(WorldStreamAtlasMips.CHAIN_BYTES, source.atlas.capacity());
            assertEquals(0, source.atlas.position());
            assertEquals(WorldStreamAtlasMips.CHAIN_BYTES, source.atlas.limit());
            assertEquals(17, source.atlas.get(0));
            // A fully covered level 0 is the qualified atlas byte for byte; the chain follows it.
            var atlas = WorldStreamTileFileTest.atlas();
            for (int at = 0; at < WorldStreamTileFile.ATLAS_BYTES; at++) {
                if (atlas.get(at) != source.atlas.get(at)) {
                    throw new AssertionError("Level 0 differs at " + at);
                }
            }
            int last = WorldStreamAtlasMips.offset(WorldStreamAtlasMips.LEVELS - 1);
            assertEquals(WorldStreamAtlasMips.CHAIN_BYTES - 4, last);
            assertEquals(17, source.atlas.get(last));
            assertEquals(51, source.atlas.get(last + 2));
            assertEquals(-1, source.atlas.get(last + 3));
        }
    }

    @Test
    void cancellationConsumesAndClosesDecodedInputWithoutReturningAnOwner() throws Exception {
        var input = read(WorldStreamTileFileTest.full(true), true);
        assertThrows(
                CancellationException.class,
                () -> WorldStreamPackedTile.prepare(input, () -> true));
        assertThrows(IllegalStateException.class, input::tile);
        input.close();
    }
}
