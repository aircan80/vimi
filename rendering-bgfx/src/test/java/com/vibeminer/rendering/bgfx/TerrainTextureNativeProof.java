package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Authored source-independent point sampling, fog, transparency and source-order Vulkan oracles.
 */
public final class TerrainTextureNativeProof {
    private static final int SIDE = 256;
    private static int frames;

    private TerrainTextureNativeProof() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path scratch = Files.createTempDirectory(output, "run-");
        try (var gpu = new TerrainProductionAcceptance.Harness()) {
            for (float offset : new float[] {0, 100000}) {
                var scene = scene(1, offset);
                var camera = new TerrainCamera(offset - 256, offset - 256, 100, 0, -1.5);
                var annex = annex(scratch, "gradient-" + (int) offset, scene, true, false, false);
                try (var renderer = new BgfxTerrainRenderer(scene, annex, () -> false)) {
                    for (var view : List.of(camera, camera.look(.7, .2), camera.look(-.8, .3))) {
                        int[] image = gpu.render(renderer, view);
                        frames++;
                        assertGradient(image, view, offset);
                        require(
                                renderer.textureStatus(TerrainTextureStatus.Code.NONE).state()
                                        == TerrainTextureStatus.State.READY,
                                "full atlas status");
                    }
                }
            }
            var fogScene = scene(1, 0);
            var fogCamera = new TerrainCamera(-256, -256, 800, 0, -1.5);
            try (var renderer =
                    new BgfxTerrainRenderer(
                            fogScene,
                            annex(scratch, "fog", fogScene, true, false, false),
                            () -> false)) {
                int[] image = gpu.render(renderer, fogCamera);
                frames++;
                assertGradient(image, fogCamera, 0);
            }
            var scene = scene(1, 0);
            var camera = new TerrainCamera(-256, -256, 100, 0, -1.5);
            int[] height;
            try (var renderer = new BgfxTerrainRenderer(scene)) {
                height = gpu.render(renderer, camera);
                frames++;
            }
            var sparse = annex(scratch, "sparse", scene, false, true, false);
            try (var renderer = new BgfxTerrainRenderer(scene, sparse, () -> false)) {
                int[] image = gpu.render(renderer, camera);
                frames++;
                // At these rays the only covered header rectangle (0,0) is not visible.
                for (int y : new int[] {64, 128, 192}) {
                    for (int x : new int[] {64, 128, 192}) {
                        require(
                                image[y * SIDE + x] == height[y * SIDE + x],
                                "alpha-zero fallback changed original height result");
                    }
                }
                require(
                        renderer.textureStatus(TerrainTextureStatus.Code.NONE).state()
                                == TerrainTextureStatus.State.PARTIAL,
                        "sparse status");
            }
            var overlapping = scene(2, 0);
            for (boolean reverse : new boolean[] {false, true}) {
                var atlas = annex(scratch, "order-" + reverse, overlapping, false, false, reverse);
                try (var renderer = new BgfxTerrainRenderer(overlapping, atlas, () -> false)) {
                    int[] image = gpu.render(renderer, camera);
                    frames++;
                    int expected = reverse ? 0xff1339c7 : 0xffd32b11;
                    require(
                            image[128 * SIDE + 128] == expected,
                            "coplanar different textures did not preserve source first-wins: "
                                    + Integer.toHexString(image[128 * SIDE + 128]));
                }
            }
        }
        Files.writeString(
                scratch.resolve("terrain-texture-native.txt"),
                "PASS "
                        + frames
                        + " actual Vulkan frames: gradient axes at rebased cameras, alpha0 legacy fallback, opaque/source-order coplanar tiles.\n");
        System.out.println("Terrain texture native proof PASS " + frames + " authored frames");
    }

    private static void assertGradient(int[] image, TerrainCamera camera, float origin) {
        // Independent ground-plane ray/plane intersection. No renderer projection/sample helper.
        int checked = 0;
        for (int y : new int[] {96, 112, 128, 144, 160}) {
            for (int x : new int[] {96, 112, 128, 144, 160}) {
                double right = (x + .5 - 128) / 204.8, up = (128 - y - .5) / 204.8;
                double vertical = Math.sin(camera.pitch()) + Math.cos(camera.pitch()) * up;
                double d = -camera.z() / vertical;
                double forward = Math.cos(camera.pitch()) - Math.sin(camera.pitch()) * up;
                double nativeX =
                        camera.x()
                                + d
                                        * (Math.cos(camera.yaw()) * forward
                                                - Math.sin(camera.yaw()) * right);
                double nativeY =
                        camera.y()
                                + d
                                        * (Math.sin(camera.yaw()) * forward
                                                + Math.cos(camera.yaw()) * right);
                double u = (origin - nativeY) * .96, v = (origin - nativeX) * .96;
                if (u < 1
                        || u > 510
                        || v < 1
                        || v > 510
                        || Math.abs(u - Math.rint(u)) < .04
                        || Math.abs(v - Math.rint(v)) < .04) {
                    continue;
                }
                // Fog uses original triangle maximum depth, not this fragment depth.
                // All test quads/cameras here with nonzero fog have yaw0 and both triangles
                // contain a max-nativeX corner; its far depth is independent of fragment XY.
                double maximumDepth =
                        -Math.sin(camera.pitch()) * camera.z()
                                + Math.cos(camera.pitch()) * (origin - camera.x());
                double fog = Math.clamp((maximumDepth - 600) / 1600, 0, .85);
                int red = (int) Math.floor(((int) Math.floor(u) & 255) * (1 - fog) + 153 * fog);
                int green = (int) Math.floor(((int) Math.floor(v) & 255) * (1 - fog) + 184 * fog);
                int blue = (int) Math.floor(31 * (1 - fog) + 196 * fog);
                int expected = 0xff000000 | (red << 16) | (green << 8) | blue;
                int actual = image[y * SIDE + x];
                require(
                        actual == expected,
                        "point atlas independent UV expected="
                                + Integer.toHexString(expected)
                                + " actual="
                                + Integer.toHexString(actual)
                                + " camera="
                                + camera
                                + " pixel="
                                + x
                                + ","
                                + y);
                checked++;
            }
        }
        require(checked >= 15, "insufficient analytic interior coverage");
    }

    private static GpuTerrainTextureAnnex.Decoded annex(
            Path scratch,
            String name,
            TerrainScene scene,
            boolean gradient,
            boolean sparse,
            boolean reverse)
            throws Exception {
        var bindings = new ArrayList<TerrainTextureBinding>();
        for (int ordinal = 0; ordinal < scene.tiles().size(); ordinal++) {
            var tile = scene.tiles().get(ordinal);
            float max = tile.vertices().get(0);
            long[] mask = sparse ? new long[] {1, 0, 0, 0} : new long[] {-1, -1, -1, -1};
            var meta =
                    TerrainTextureBinding.fromSnapshot(
                            ordinal,
                            tile,
                            max,
                            max,
                            mask,
                            new TerrainTextureBinding.Sources(
                                    1, "ab".repeat(16), 2, "cd".repeat(16)),
                            () -> false);
            byte[] rgba = new byte[TerrainTextureBinding.PIXEL_BYTES];
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    if (sparse && (x >= 32 || y >= 32)) {
                        continue;
                    }
                    int p = (y * 512 + x) * 4;
                    boolean first = (ordinal == 0) != reverse;
                    rgba[p] = (byte) (gradient ? x : first ? 211 : 19);
                    rgba[p + 1] = (byte) (gradient ? y : first ? 43 : 57);
                    rgba[p + 2] = (byte) (gradient ? 31 : first ? 17 : 199);
                    rgba[p + 3] = (byte) 255;
                }
            }
            bindings.add(
                    TerrainTextureBinding.borrow(meta, ByteBuffer.wrap(rgba).asReadOnlyBuffer()));
        }
        Path geometry = scratch.resolve(name + ".terrain"),
                annex = scratch.resolve(name + ".atlas");
        var digest = GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        GpuTerrainTextureAnnex.write(annex, digest, scene, bindings, () -> false);
        return GpuTerrainTextureAnnex.read(
                annex, GpuTerrainSceneFile.readBound(geometry, () -> false), () -> false);
    }

    private static TerrainScene scene(int count, float offset) {
        float[] vertices = new float[256 * 145 * 3];
        int at = 0;
        for (int chunk = 0; chunk < 256; chunk++) {
            double ax = offset - (chunk / 16) * (1600.0 / 3 / 16),
                    ay = offset - (chunk % 16) * (1600.0 / 3 / 16);
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    vertices[at++] = (float) (ax - row * (1600.0 / 3 / 256));
                    vertices[at++] = (float) (ay - (col * 2 + row % 2) * (1600.0 / 3 / 256));
                    vertices[at++] = 0;
                }
            }
        }
        int a = 0, b = 15 * 145 + 8, c = 240 * 145 + 136, d = 255 * 145 + 144;
        var tiles = new ArrayList<TerrainScene.Tile>();
        for (int i = 0; i < count; i++) {
            tiles.add(TerrainScene.Tile.copyOf(i, 0, vertices, new int[] {a, b, c, b, d, c}, 256));
        }
        return new TerrainScene(tiles);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
