package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class TerrainBorrowedRendererTest {
    @Test
    void nineBorrowedSurfacesPreservePreChangeHeightFogClippingAndCameraGoldens() throws Exception {
        var raw = new ArrayList<WorldFormats.Terrain>();
        var nativeTiles = new ArrayList<TerrainScene.Tile>();
        for (int i = 0; i < 9; i++) {
            float x = 1 + i * 260, y = (i % 3 - 1) * 4, z = (i % 2) * 30;
            var terrain =
                    new WorldFormats.Terrain(
                            new float[] {
                                x - .75f, y - 3, z - 1, x + 6, y + 3, z - 1, x + 6, y, z + 6
                            },
                            new int[] {0, 1, 2},
                            1);
            raw.add(terrain);
            nativeTiles.add(new TerrainScene.Tile(7 + i, 19, terrain));
        }
        var cameras =
                List.of(
                        new TerrainRenderer.Camera(0, 0, 0, 0, 0),
                        new TerrainRenderer.Camera(-500, -10, 80, .2, -.1),
                        new TerrainRenderer.Camera(1300, 0, 40, Math.PI, -.2));
        // Captured with the unmodified array-based renderer at integration38acbe5.
        var hashes =
                List.of(
                        "e2bd3dd7761c53d4f8339258511fed89a0fb1e57370d447d028bbd260544c3e1",
                        "40c4052af98b0bf00bd834a316714e1ee91c9da18a7d8cd029bb7b1008d8394a",
                        "fc664647b2eb600ea91c41f68d0ceaefdc57182669d2b32dd9468962b6d6085a");
        int[] triangles = {10, 7, 6};
        var scene = new TerrainRenderer.Scene(raw);
        for (int i = 0; i < cameras.size(); i++) {
            var frame =
                    TerrainRenderer.renderBorrowed(
                            nativeTiles, cameras.get(i), 128, 96, List.of(), () -> false);
            assertEquals(triangles[i], frame.triangles());
            assertArrayEquals(
                    TerrainRenderer.render(scene, cameras.get(i), 128, 96, () -> false).pixels(),
                    frame.pixels());
            var pixels = ByteBuffer.allocate(frame.pixels().length * 4);
            for (int pixel : frame.pixels()) {
                pixels.putInt(pixel);
            }
            assertEquals(
                    hashes.get(i),
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256").digest(pixels.array())));
        }
    }

    @Test
    void borrowedFallbackKeepsBoundsCancellationAndFreshBufferPositions() {
        var tile =
                TerrainScene.Tile.copyOf(
                        7, 19, new float[] {2, -2, -1, 5, 2, -1, 5, 0, 2}, new int[] {0, 1, 2}, 1);
        var camera = new TerrainRenderer.Camera(0, 0, 0, 0, 0);
        var first =
                TerrainRenderer.renderBorrowed(
                        List.of(tile), camera, 32, 24, List.of(), () -> false);
        tile.vertices().limit(0);
        tile.indices().position(tile.indexCount());
        assertArrayEquals(
                first.pixels(),
                TerrainRenderer.renderBorrowed(
                                List.of(tile), camera, 32, 24, List.of(), () -> false)
                        .pixels());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                List.of(tile, tile), camera, 32, 24, List.of(), () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                java.util.Collections.nCopies(10, tile),
                                camera,
                                32,
                                24,
                                List.of(),
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                List.of(tile), camera, 1201, 800, List.of(), () -> false));
        assertThrows(
                CancellationException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                List.of(tile), camera, 32, 24, List.of(), () -> true));
        assertArrayEquals(
                TerrainRenderer.render(
                                new TerrainRenderer.Scene(List.of()), camera, 32, 24, () -> false)
                        .pixels(),
                TerrainRenderer.renderBorrowed(List.of(), camera, 32, 24, List.of(), () -> false)
                        .pixels());
    }
}
