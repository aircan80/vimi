package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_stats;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Opt-in actual hardware pixels; authored presentation only, never game-source qualification. */
public final class WorldWmoNativeProof {
    private static final int SIDE = 256;
    private static int acceptedFrames;

    private WorldWmoNativeProof() {}

    private static TerrainScene scene(boolean occluder) {
        float[] vertices =
                occluder
                        ? new float[] {8, -1.5f, -1.5f, 8, 1.5f, -1.5f, 8, 0, 1.5f}
                        : new float[] {0, 0, 0};
        return new TerrainScene(
                List.of(
                        TerrainScene.Tile.copyOf(
                                17, 21, vertices, occluder ? new int[] {0, 1, 2} : new int[0], 1)));
    }

    private record Packet(GpuTerrainSceneFile.Decoded base, Path annex) {}

    private static Packet packet(
            Path directory, String name, TerrainScene scene, WorldWmoAuthoredData data)
            throws IOException {
        Path base = directory.resolve(name + ".terrain"), annex = directory.resolve(name + ".wmo");
        var written = GpuTerrainSceneFile.writeBound(base, scene, () -> false);
        GpuWorldWmoAnnex.write(annex, written, scene, data, () -> false);
        return new Packet(GpuTerrainSceneFile.readBound(base, () -> false), annex);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected private authored proof output");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path scratch = Files.createTempDirectory(output, "wmo-");
        for (boolean reverse : new boolean[] {false, true}) {
            var scene = scene(true);
            var behind = new WorldWmoAuthoredData(scene, 12, false, reverse, false, false);
            var p = packet(scratch, "depth-behind-" + reverse, scene, behind);
            try (var gpu = new Harness();
                    var terrain = new BgfxTerrainRenderer(scene);
                    var wmo = BgfxWorldWmoPass.readBound(p.annex(), p.base(), () -> false)) {
                var camera = new TerrainCamera(0, 0, 0, 0, 0);
                int[] base = gpu.render(terrain, null, camera);
                int[] image = gpu.render(terrain, wmo, camera);
                require(
                        image[128 * SIDE + 128] == base[128 * SIDE + 128],
                        "Terrain foreground depth was cleared/overwritten");
                rgb(
                        image[160 * SIDE + 180],
                        223,
                        0,
                        0,
                        1,
                        "Visible WMO behind terrain edge/two-sided lighting");
                write(output.resolve("depth-behind-" + reverse + ".png"), image);
                long mesh = wmo.issuedMeshBytes(), textures = wmo.issuedTextureBytes();
                for (var view :
                        List.of(
                                camera.look(.03, .02),
                                camera.move(1, 0, 0),
                                camera.look(-.03, -.02))) {
                    gpu.render(terrain, wmo, view);
                    require(
                            wmo.issuedMeshBytes() == mesh
                                    && wmo.issuedTextureBytes() == textures
                                    && wmo.liveStagingBytes() == 0,
                            "Camera motion recreated uploads");
                }
            }
        }
        {
            var scene = scene(true);
            var data = new WorldWmoAuthoredData(scene, 4, false, false, false, false);
            var p = packet(scratch, "depth-front", scene, data);
            try (var gpu = new Harness();
                    var terrain = new BgfxTerrainRenderer(scene);
                    var wmo = BgfxWorldWmoPass.readBound(p.annex(), p.base(), () -> false)) {
                var image = gpu.render(terrain, wmo, new TerrainCamera(0, 0, 0, 0, 0));
                rgb(image[128 * SIDE + 128], 223, 0, 0, 1, "Near WMO must win GREATER depth");
            }
        }
        for (int mode = 0; mode < 5; mode++) {
            var scene = scene(false);
            boolean gradient = mode == 0, zero = mode == 1;
            float depth = mode == 2 ? 1400 : mode == 3 ? 2201 : zero ? 10 : 12;
            var data = new WorldWmoAuthoredData(scene, depth, false, false, gradient, zero);
            // Keep the distant test triangle large enough to contain independent sample rays.
            if (depth > 100) {
                for (int i = 0; i < 3; i++) {
                    data.positions[i * 3 + 1] *= depth / 12;
                    data.positions[i * 3 + 2] *= depth / 12;
                }
            }
            var p = packet(scratch, "recipe-" + mode, scene, data);
            try (var gpu = new Harness();
                    var terrain = new BgfxTerrainRenderer(scene);
                    var wmo = BgfxWorldWmoPass.readBound(p.annex(), p.base(), () -> false)) {
                var placement = data.meta.placement();
                var camera = new TerrainCamera(placement.tx(), placement.ty(), 0, 0, 0);
                var base = gpu.render(terrain, null, camera);
                var image = gpu.render(terrain, wmo, camera);
                if (mode == 0) {
                    rgb(
                            image[128 * SIDE + 128],
                            28,
                            140,
                            0,
                            1,
                            "Repeat/bilinear UV outside unit square");
                } else if (mode == 1) {
                    rgb(
                            image[127 * SIDE + 127],
                            85,
                            0,
                            0,
                            1,
                            "Canceling unit normals require ambient only");
                } else if (mode == 2) {
                    rgb(image[128 * SIDE + 128], 188, 92, 98, 1, "Fragment-depth fog at1400");
                } else if (mode == 3) {
                    require(
                            image[128 * SIDE + 128] == base[128 * SIDE + 128],
                            "Beyond2200 fragment was not discarded");
                } else {
                    rgb(image[128 * SIDE + 128], 223, 0, 0, 1, "Opaque fixed normal lighting");
                }
                write(output.resolve("recipe-" + mode + ".png"), image);
            }
        }
        {
            var scene = scene(false);
            var data = new WorldWmoAuthoredData(scene, 12, true, false, false, false);
            var p = packet(scratch, "maximum", scene, data);
            try (var gpu = new Harness();
                    var terrain = new BgfxTerrainRenderer(scene);
                    var wmo = BgfxWorldWmoPass.readBound(p.annex(), p.base(), () -> false)) {
                gpu.render(terrain, wmo, new TerrainCamera(0, 0, 0, 0, 0));
                require(
                        wmo.issuedMeshBytes() == 19456 && wmo.issuedTextureBytes() == 1572864,
                        "Maximum issued GPU/copy payload ledger differs");
                require(
                        wmo.liveStagingBytes() == 0
                                && wmo.peakMeshStagingBytes() == 19456
                                && wmo.peakTextureStagingBytes() == 262144,
                        "Serial bounded direct staging did not drain");
            }
        }
        {
            var scene = scene(false);
            var data = new WorldWmoAuthoredData(scene, 12, true, false, false, false);
            // First recorded batch is near red; every later batch is farther green.
            for (int vertex = 0; vertex < 3; vertex++) {
                data.positions[vertex * 3] = -4;
            }
            for (int at = 255; at < data.indices.length; at++) {
                data.indices[at] = 3 + at % 3;
            }
            for (int image = 1; image < data.images.size(); image++) {
                byte[] rgba = new byte[256 * 256 * 4];
                for (int at = 0; at < rgba.length; at += 4) {
                    rgba[at + 1] = (byte) 255;
                    rgba[at + 3] = (byte) 255;
                }
                data.images.set(
                        image,
                        new com.vibeminer.rendering.world.BasicWmoData.Image(
                                10 + image, 256, 256, ByteBuffer.wrap(rgba)));
            }
            var p = packet(scratch, "wmo-self-depth", scene, data);
            try (var gpu = new Harness();
                    var terrain = new BgfxTerrainRenderer(scene);
                    var wmo = BgfxWorldWmoPass.readBound(p.annex(), p.base(), () -> false)) {
                int[] image = gpu.render(terrain, wmo, new TerrainCamera(0, 0, 0, 0, 0));
                rgb(
                        image[128 * SIDE + 128],
                        223,
                        0,
                        0,
                        1,
                        "WMO must write depth before later farther batches");
            }
        }
        {
            var scene = scene(false);
            var data = new WorldWmoAuthoredData(scene, 12, true, false, false, false);
            var p = packet(scratch, "canceled", scene, data);
            try (var gpu = new Harness();
                    var terrain = new BgfxTerrainRenderer(scene)) {
                var camera = new TerrainCamera(0, 0, 0, 0, 0);
                gpu.render(terrain, null, camera);
                var baseline = counts();
                var canceled = new AtomicBoolean();
                boolean refused = false;
                try {
                    BgfxWorldWmoPass.readBound(
                            p.annex(),
                            p.base(),
                            canceled::get,
                            accepted -> canceled.set(accepted == 1));
                } catch (CancellationException expected) {
                    refused = true;
                }
                require(refused, "Cancellation after first texture did not abort native upload");
                for (int i = 0; i < 4; i++) {
                    bgfx_frame(BGFX_FRAME_NONE);
                }
                gpu.render(terrain, null, camera);
                require(
                        counts().equals(baseline),
                        "Canceled upload retained native handles after drain: "
                                + counts()
                                + " vs "
                                + baseline);
                // No second WMO allocation in this context: its conservative slot charge retires at
                // shutdown.
            }
        }
        System.out.println(
                "WORLD_WMO_NATIVE_PASS acceptedFrames="
                        + acceptedFrames
                        + " sameContextCameraReuse=true maxMesh=19456 maxRgba=1572864 maxDirectTexture=262144 sourceIndependent=true");
    }

    private record Handles(
            int vertices, int indices, int textures, int uniforms, int programs, int shaders) {}

    private static Handles counts() {
        var s = bgfx_get_stats();
        return new Handles(
                Short.toUnsignedInt(s.numVertexBuffers()),
                Short.toUnsignedInt(s.numIndexBuffers()),
                Short.toUnsignedInt(s.numTextures()),
                Short.toUnsignedInt(s.numUniforms()),
                Short.toUnsignedInt(s.numPrograms()),
                Short.toUnsignedInt(s.numShaders()));
    }

    private static void rgb(int pixel, int r, int g, int b, int tolerance, String reason) {
        require(
                (pixel >>> 24) == 255
                        && Math.abs(((pixel >>> 16) & 255) - r) <= tolerance
                        && Math.abs(((pixel >>> 8) & 255) - g) <= tolerance
                        && Math.abs((pixel & 255) - b) <= tolerance,
                reason + " actual=" + Integer.toHexString(pixel));
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    private static void write(Path path, int[] pixels) throws IOException {
        var image = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, SIDE, SIDE, pixels, 0, SIDE);
        ImageIO.write(image, "png", path.toFile());
    }

    private static final class Harness implements AutoCloseable {
        private short color = -1, depth = -1, target = -1, staging = -1;
        private ByteBuffer pixels;
        private boolean initialized;

        Harness() throws IOException {
            try {
                try (var stack = MemoryStack.stackPush()) {
                    var init = BGFXInit.calloc(stack);
                    bgfx_init_ctor(init);
                    init.type(BGFX_RENDERER_TYPE_VULKAN);
                    init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                    BGFXPlatform.bgfx_render_frame(0);
                    if (!bgfx_init(init)) {
                        throw new IOException("Hardware Vulkan unavailable");
                    }
                    initialized = true;
                }
                var caps = bgfx_get_caps();
                require(
                        caps.rendererType() == BGFX_RENDERER_TYPE_VULKAN
                                && !caps.homogeneousDepth(),
                        "Expected reversed zero-to-one Vulkan");
                GpuViewerMain.requireHardware(
                        Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
                System.out.println(
                        "WMO_PROOF_DEVICE vendor="
                                + Short.toUnsignedInt(caps.vendorId())
                                + " device="
                                + Short.toUnsignedInt(caps.deviceId()));
                color =
                        valid(
                                bgfx_create_texture_2d(
                                        SIDE,
                                        SIDE,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_RT,
                                        null,
                                        0));
                depth =
                        valid(
                                bgfx_create_texture_2d(
                                        SIDE,
                                        SIDE,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_D32F,
                                        BGFX_TEXTURE_RT,
                                        null,
                                        0));
                target =
                        valid(
                                bgfx_create_frame_buffer_from_handles(
                                        new short[] {color, depth}, false));
                staging =
                        valid(
                                bgfx_create_texture_2d(
                                        SIDE,
                                        SIDE,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                                        null,
                                        0));
                pixels = MemoryUtil.memAlloc(SIDE * SIDE * 4);
            } catch (IOException | RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        int[] render(BgfxTerrainRenderer terrain, BgfxWorldWmoPass wmo, TerrainCamera camera)
                throws IOException {
            bgfx_set_view_frame_buffer(0, target);
            terrain.render(camera, SIDE, SIDE);
            if (wmo != null) {
                wmo.draw(camera, SIDE, SIDE);
            }
            bgfx_blit(1, staging, 0, 0, 0, 0, color, 0, 0, 0, 0, SIDE, SIDE, 1);
            int ready = bgfx_read_texture(staging, pixels, 0, 0);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (Integer.compareUnsigned(bgfx_frame(BGFX_FRAME_NONE), ready) < 0) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("WMO actual frame timeout");
                }
            }
            int[] result = new int[SIDE * SIDE];
            boolean invert = bgfx_get_caps().originBottomLeft();
            for (int y = 0; y < SIDE; y++) {
                for (int x = 0; x < SIDE; x++) {
                    int at = (y * SIDE + x) * 4;
                    int value =
                            (pixels.get(at + 3) & 255) << 24
                                    | (pixels.get(at) & 255) << 16
                                    | (pixels.get(at + 1) & 255) << 8
                                    | pixels.get(at + 2) & 255;
                    require((value >>> 24) == 255, "Frame is not opaque");
                    result[(invert ? SIDE - 1 - y : y) * SIDE + x] = value;
                }
            }
            acceptedFrames++;
            return result;
        }

        private static short valid(short handle) throws IOException {
            if (handle == -1) {
                throw new IOException("Proof target unavailable");
            }
            return handle;
        }

        @Override
        public void close() {
            if (initialized) {
                if (target != -1) {
                    bgfx_destroy_frame_buffer(target);
                }
                for (short texture : new short[] {staging, color, depth}) {
                    if (texture != -1) {
                        bgfx_destroy_texture(texture);
                    }
                }
                for (int i = 0; i < 4; i++) {
                    bgfx_frame(BGFX_FRAME_NONE);
                }
                bgfx_shutdown();
                initialized = false;
            }
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
        }
    }
}
