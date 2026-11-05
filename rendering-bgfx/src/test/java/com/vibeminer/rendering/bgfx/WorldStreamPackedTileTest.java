package com.vibeminer.rendering.bgfx;

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
            assertEquals(48, source.payloadBytes());
            assertNull(source.atlas);
            var data = source.geometry.asFloatBuffer();
            assertEquals(10f, data.get(0));
            assertEquals(10f, data.get(4));
            assertEquals(10f, data.get(8));
            assertEquals(44.28f, data.get(3), .00001f);
            assertEquals(71.34f, data.get(7), .00001f);
            assertEquals(41.82f, data.get(11), .00001f);
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
            assertEquals(4_543_828, WorldStreamNativeProof.TERRAIN_FIXTURE_BYTES);
            assertEquals(5_330_260, WorldStreamPackedTile.MAX_PAYLOAD_BYTES);
            assertTrue(source.payloadBytes() <= WorldStreamPackedTile.MAX_PAYLOAD_BYTES);
            assertEquals(3 << 20, source.geometry.capacity());
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
