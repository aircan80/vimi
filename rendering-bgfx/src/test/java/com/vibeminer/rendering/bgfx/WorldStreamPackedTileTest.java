package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void maximumPackedOwnershipIsFourMiBAndOneShotTransferSurvivesWrapperClose() throws Exception {
        var input = read(WorldStreamTileFileTest.full(true), true);
        var packed = WorldStreamPackedTile.prepare(input, () -> false);
        try (var source = packed.take()) {
            packed.close();
            assertThrows(IllegalStateException.class, input::tile);
            assertEquals(4L << 20, source.payloadBytes());
            assertEquals(3 << 20, source.geometry.capacity());
            assertEquals(1 << 20, source.atlas.capacity());
            assertEquals(17, source.atlas.get(0));
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
