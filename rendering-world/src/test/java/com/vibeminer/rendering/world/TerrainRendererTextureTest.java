package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainRendererTextureTest {
    private record Fixture(
            TerrainRenderer.Scene scene,
            TerrainTextureBinding texture,
            WorldFormats.Terrain terrain,
            TerrainScene.Tile tile) {}

    @Test
    void texturedGroundMatchesIndependentRayPlaneIntersectionAndPartialCoverage() {
        var f = fixture();
        int compared = 0, fallback = 0;
        for (var camera :
                List.of(
                        new TerrainRenderer.Camera(-560, -280, 110, .2, -.45),
                        new TerrainRenderer.Camera(-260, -260, 50.1, .2, -.5))) {
            var base = TerrainRenderer.render(f.scene, camera, 160, 120, () -> false);
            assertArrayEquals(
                    base.pixels(),
                    TerrainRenderer.render(f.scene, camera, 160, 120, List.of(), () -> false)
                            .pixels());
            var frame =
                    TerrainRenderer.renderBorrowed(
                            List.of(f.tile), camera, 160, 120, List.of(f.texture), () -> false);
            assertArrayEquals(
                    TerrainRenderer.render(
                                    f.scene, camera, 160, 120, List.of(f.texture), () -> false)
                            .pixels(),
                    frame.pixels());
            // Ground is z=50. Intersect each independently constructed eye ray with that plane.
            for (int y = 0; y < 120; y++) {
                for (int x = 0; x < 160; x++) {
                    double horizontal = (x + .5 - 80) / 128;
                    double vertical = (60 - y - .5) / 128;
                    double cp = Math.cos(camera.pitch()), sp = Math.sin(camera.pitch());
                    double cy = Math.cos(camera.yaw()), sy = Math.sin(camera.yaw());
                    double dz = sp + vertical * cp;
                    double distance = (50 - camera.z()) / dz;
                    double nx =
                            camera.x()
                                    + distance * (cp * cy - horizontal * sy - vertical * sp * cy);
                    double ny =
                            camera.y()
                                    + distance * (cp * sy + horizontal * cy - vertical * sp * sy);
                    if (distance < .51
                            || nx >= -.01
                            || nx <= -533.32
                            || ny >= -.01
                            || ny <= -533.32) {
                        continue;
                    }
                    int tx = (int) Math.floor(-ny * .96), ty = (int) Math.floor(-nx * .96);
                    if (ty < 256) {
                        assertEquals(
                                0xff00001f | ((tx & 255) << 16) | ((ty & 255) << 8),
                                frame.pixels()[y * 160 + x],
                                "sample at " + x + "," + y);
                        compared++;
                    } else {
                        assertEquals(base.pixels()[y * 160 + x], frame.pixels()[y * 160 + x]);
                        fallback++;
                    }
                }
            }
        }
        assertTrue(compared > 100, "covered sample count " + compared);
        assertTrue(fallback > 100, "height fallback sample count " + fallback);
    }

    @Test
    void textureReceivesOriginalTriangleFogAndCoplanarFirstSurfaceWins() {
        var f = fixture();
        var camera = new TerrainRenderer.Camera(-1100, -266, 150, 0, -.1);
        var frame =
                TerrainRenderer.renderBorrowed(
                        List.of(f.tile), camera, 160, 120, List.of(f.texture), () -> false);
        var overlap =
                TerrainRenderer.render(
                        new TerrainRenderer.Scene(List.of(f.terrain, f.terrain)),
                        camera,
                        160,
                        120,
                        List.of(f.texture),
                        () -> false);
        assertArrayEquals(frame.pixels(), overlap.pixels());
        double vertical = -.5 / 128;
        double distance = -100 / (Math.sin(-.1) + vertical * Math.cos(-.1));
        double nx = -1100 + distance * (Math.cos(-.1) - vertical * Math.sin(-.1));
        double ny = -266 + distance * (.5 / 128);
        int tx = (int) Math.floor(-ny * .96), ty = (int) Math.floor(-nx * .96);
        assertTrue(ty >= 0 && ty < 256);
        double far = Math.cos(-.1) * 1100 + Math.sin(-.1) * -100;
        double fog = (far - 600) / 1600;
        int r = (int) ((tx & 255) * (1 - fog) + 153 * fog);
        int g = (int) ((ty & 255) * (1 - fog) + 184 * fog);
        int b = (int) (31 * (1 - fog) + 196 * fog);
        assertEquals(0xff000000 | r << 16 | g << 8 | b, frame.pixels()[60 * 160 + 80]);
    }

    @Test
    void independentSingleTilePacketOrdinalsMatchResidentCoordinatesAndExactGeometry() {
        var first = fixture();
        var shifted = first.terrain.vertices();
        for (int at = 0; at < shifted.length; at += 3) {
            shifted[at] -= (float) WorldFormats.TILE_SIZE;
        }
        var secondTerrain = new WorldFormats.Terrain(shifted, first.terrain.triangles(), 256);
        var second = new TerrainScene.Tile(8, 19, secondTerrain);
        var red = solid(first.tile, 0, true);
        var green = solid(second, 0, false);
        var greenOrdinalOne = withOrdinal(second, green, 1);
        var camera = new TerrainRenderer.Camera(-1100, -266, 150, 0, -.1);
        var expected =
                TerrainRenderer.render(
                        new TerrainRenderer.Scene(List.of(first.terrain, secondTerrain)),
                        camera,
                        160,
                        120,
                        List.of(red, greenOrdinalOne),
                        () -> false);
        var actual =
                TerrainRenderer.renderBorrowed(
                        List.of(first.tile, second),
                        camera,
                        160,
                        120,
                        List.of(green, red),
                        () -> false);
        assertArrayEquals(expected.pixels(), actual.pixels());
        assertEquals(0, red.metadata().tileOrdinal());
        assertEquals(0, green.metadata().tileOrdinal());
        int redPixels = 0, greenPixels = 0;
        for (int pixel : actual.pixels()) {
            int r = pixel >>> 16 & 255, g = pixel >>> 8 & 255;
            if (r > g + 10) {
                redPixels++;
            }
            if (g > r + 10) {
                greenPixels++;
            }
        }
        assertTrue(redPixels > 10 && greenPixels > 10);
        var changed = first.terrain.vertices();
        changed[2] += 1;
        var wrongGeometry =
                TerrainScene.Tile.copyOf(7, 19, changed, first.terrain.triangles(), 256);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                List.of(wrongGeometry),
                                camera,
                                160,
                                120,
                                List.of(red),
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                List.of(first.tile),
                                camera,
                                160,
                                120,
                                List.of(green),
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainRenderer.renderBorrowed(
                                List.of(first.tile),
                                camera,
                                160,
                                120,
                                List.of(red, red),
                                () -> false));
    }

    private static TerrainTextureBinding withOrdinal(
            TerrainScene.Tile tile, TerrainTextureBinding original, int ordinal) {
        var m = original.metadata();
        return TerrainTextureBinding.borrow(
                TerrainTextureBinding.fromSnapshot(
                        ordinal,
                        tile,
                        m.nativeMaxX(),
                        m.nativeMaxY(),
                        new long[] {-1, -1, -1, -1},
                        m.sources(),
                        () -> false),
                original.pixels());
    }

    private static TerrainTextureBinding solid(TerrainScene.Tile tile, int ordinal, boolean red) {
        var metadata =
                TerrainTextureBinding.fromSnapshot(
                        ordinal,
                        tile,
                        tile.vertices().get(0),
                        tile.vertices().get(1),
                        new long[] {-1, -1, -1, -1},
                        new TerrainTextureBinding.Sources(
                                red ? 1 : 3, "a".repeat(32), red ? 2 : 4, "b".repeat(32)),
                        () -> false);
        var rgba = ByteBuffer.allocate(TerrainTextureBinding.PIXEL_BYTES);
        for (int at = 0; at < rgba.capacity(); at += 4) {
            rgba.put(at, (byte) (red ? 255 : 0))
                    .put(at + 1, (byte) (red ? 0 : 255))
                    .put(at + 3, (byte) 255);
        }
        return TerrainTextureBinding.borrow(metadata, rgba.asReadOnlyBuffer());
    }

    private static Fixture fixture() {
        float[] vertices = new float[256 * 145 * 3];
        int at = 0;
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            float ax = (float) (-(ordinal / 16) * WorldFormats.TILE_SIZE / 16);
            float ay = (float) (-(ordinal % 16) * WorldFormats.TILE_SIZE / 16);
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    vertices[at++] = (float) (ax - row * WorldFormats.TILE_SIZE / 256);
                    vertices[at++] =
                            (float) (ay - (2 * col + row % 2) * WorldFormats.TILE_SIZE / 256);
                    vertices[at++] = 50;
                }
            }
        }
        int a = 0, b = 15 * 145 + 8, c = 240 * 145 + 136, d = 255 * 145 + 144;
        int[] indices = {a, b, c, b, d, c};
        var tile = TerrainScene.Tile.copyOf(7, 19, vertices, indices, 256);
        var metadata =
                TerrainTextureBinding.fromSnapshot(
                        0,
                        tile,
                        0,
                        0,
                        new long[] {-1, -1, 0, 0},
                        new TerrainTextureBinding.Sources(1, "a".repeat(32), 2, "b".repeat(32)),
                        () -> false);
        var rgba = ByteBuffer.allocate(TerrainTextureBinding.PIXEL_BYTES);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 512; x++) {
                int p = (y * 512 + x) * 4;
                rgba.put(p, (byte) x)
                        .put(p + 1, (byte) y)
                        .put(p + 2, (byte) 31)
                        .put(p + 3, (byte) 255);
            }
        }
        var terrain = new WorldFormats.Terrain(vertices, indices, 256);
        return new Fixture(
                new TerrainRenderer.Scene(List.of(terrain)),
                TerrainTextureBinding.borrow(metadata, rgba.asReadOnlyBuffer()),
                terrain,
                tile);
    }
}
