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
    void nineBorrowedSurfacesPreserveHeightFogClippingGoldensAfterCorrectingHorizontalHandedness()
            throws Exception {
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
        // First captured at integration38acbe5 (reflected: the geographic fix changed only
        // horizontal handedness, not shading/depth/holes). Recaptured for the shared 90 degree
        // field of view (TerrainCamera.HORIZONTAL_FOV); the triangle counts are unchanged.
        var hashes =
                List.of(
                        "a2f7d4a7141b28df957f23d41a190605b72adb208a9e10e3ee89fb90dc8ff102",
                        "57b5b9f09d24924f0a7de36bb9d377690de374932cd071d531b5494470de259a",
                        "9a900eb38de78eb06f377b3185d56c17f31350822c0ad9b31b66fe0cc47677fa");
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
            for (int row = 0; row < 96; row++) {
                for (int column = 127; column >= 0; column--) {
                    pixels.putInt(frame.pixels()[row * 128 + column]);
                }
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
