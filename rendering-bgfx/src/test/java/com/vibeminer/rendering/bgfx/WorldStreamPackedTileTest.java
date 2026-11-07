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
            // Three 16-byte vertices and three u16 indices.
            assertEquals(3 * 16 + 3 * 2, source.payloadBytes());
            assertNull(source.atlas);
            var data = source.geometry.duplicate().order(java.nio.ByteOrder.nativeOrder());
            for (int v = 0; v < 3; v++) {
                assertEquals(10f, data.getFloat(v * 16));
            }
            assertEquals(1f, data.getFloat(16 + 4));
            assertEquals(1f, data.getFloat(32 + 8));
            // The +X face light (.45 + .55 * .3) on the low-altitude grass tint, as RGBA8.
            assertEquals(44, data.get(12) & 255);
            assertEquals(71, data.get(13) & 255);
            assertEquals(42, data.get(14) & 255);
            assertEquals(255, data.get(15) & 255);
            assertEquals(3, source.terrainVertices);
            assertEquals(3, source.terrainIndices);
            assertEquals(48, source.indexOffset());
            assertEquals(0, data.getShort(48));
            assertEquals(1, data.getShort(50));
            assertEquals(2, data.getShort(52));
            assertArrayEquals(new int[] {0, 3}, source.runs);
        }
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
            assertEquals(2_385_236, WorldStreamNativeProof.TERRAIN_FIXTURE_BYTES);
            assertEquals(3_007_828, WorldStreamPackedTile.MAX_PAYLOAD_BYTES);
            assertTrue(source.payloadBytes() <= WorldStreamPackedTile.MAX_PAYLOAD_BYTES);
            assertEquals(37120 * 16 + 196608 * 2, source.geometry.capacity());
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
