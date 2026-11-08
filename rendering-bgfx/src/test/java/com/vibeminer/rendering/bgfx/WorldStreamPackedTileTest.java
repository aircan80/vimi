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

    /** A flat root ADT of 256 chunks with heights only (no MCNR or MCCV), at tile 32, 32. */
    private static byte[] flatRoot() {
        return root(false);
    }

    /**
     * The flat root, or with {@code tiltFirst} chunk 0 sloping along its columns (native y): each
     * vertex 2 yards higher per column, so its derived normals lean in y and keep an up part.
     */
    private static byte[] root(boolean tiltFirst) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(
                part(
                        "MVER",
                        java.nio.ByteBuffer.allocate(4)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .putInt(18)
                                .array()));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var header = java.nio.ByteBuffer.allocate(128).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int x = ordinal % 16, y = ordinal / 16;
            float side = (float) (com.vibeminer.formats.world.WorldFormats.TILE_SIZE / 16);
            header.putInt(4, x).putInt(8, y).putFloat(104, -y * side).putFloat(108, -x * side);
            header.putFloat(112, 10);
            var chunk = new java.io.ByteArrayOutputStream();
            chunk.writeBytes(header.array());
            var heights = java.nio.ByteBuffer.allocate(580).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (tiltFirst && ordinal == 0) {
                for (int row = 0, at = 0; row < 17; row++) {
                    for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++, at++) {
                        heights.putFloat(at * 4, 2f * (col + (row % 2) * 0.5f));
                    }
                }
            }
            chunk.writeBytes(part("MCVT", heights.array()));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] part(String name, byte[] bytes) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        return java.nio.ByteBuffer.allocate(8 + bytes.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(tag)
                .putInt(bytes.length)
                .put(bytes)
                .array();
    }

    /**
     * #532: without MCNR the normal is derived from the triangles, signed, so it depends on the
     * winding of the parser's fan. Flat ADT ground must light as straight up, (128, 128, 255), at
     * every vertex: a flipped fan would point down and leave those chunks ambient-only.
     */
    @Test
    void flatAdtGroundWithoutMcnrPacksStraightUpNormals() throws Exception {
        var terrain = com.vibeminer.formats.world.WorldFormats.adt(flatRoot(), () -> false);
        var tile = new TerrainScene.Tile(32, 32, terrain);
        var input = read(tile, false);
        try (var packed = WorldStreamPackedTile.prepare(input, () -> false);
                var source = packed.take()) {
            var data = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            assertTrue(source.terrainVertices > 256 * 100);
            for (int v = 0; v < source.terrainVertices; v++) {
                int at = v * 24 + 20;
                assertEquals(128, data.get(at) & 255, "x at vertex " + v);
                assertEquals(128, data.get(at + 1) & 255, "y at vertex " + v);
                assertEquals(255, data.get(at + 2) & 255, "z at vertex " + v);
            }
        }
    }

    /**
     * #532: one sloped chunk among flat ones. Its derived normals lean in y but keep an up part (a
     * normal that lost its vertical component would pack z = 128), and the flat chunks stay
     * straight up.
     */
    @Test
    void aSlopedChunkWithoutMcnrLeansItsNormalsAndKeepsThemUp() throws Exception {
        var terrain = com.vibeminer.formats.world.WorldFormats.adt(root(true), () -> false);
        var input = read(new TerrainScene.Tile(32, 32, terrain), false);
        try (var packed = WorldStreamPackedTile.prepare(input, () -> false);
                var source = packed.take()) {
            var data = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            for (int v = 0; v < source.terrainVertices; v++) {
                int at = v * 24 + 20;
                int x = data.get(at) & 255, y = data.get(at + 1) & 255, z = data.get(at + 2) & 255;
                if (v < 145) {
                    // Rounding 127.5 + 127.5 x n puts an exact 0 at 127 or 128.
                    assertTrue(Math.abs(x - 128) <= 1, "sloped chunk x at vertex " + v);
                    assertTrue(Math.abs(y - 128) >= 10, "sloped chunk leans in y at vertex " + v);
                    assertTrue(z > 128 && z < 255, "sloped chunk keeps an up part at vertex " + v);
                } else {
                    assertEquals(128, x, "x at vertex " + v);
                    assertEquals(128, y, "y at vertex " + v);
                    assertEquals(255, z, "z at vertex " + v);
                }
            }
        }
    }
}
