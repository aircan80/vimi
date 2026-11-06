package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
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
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import com.vibeminer.rendering.world.TerrainCamera;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Opt-in authored standalone candidate pixels, never Source or application qualification. */
@SuppressWarnings("try")
public final class GlobalWmoNativeProof {
    private static final int SIDE = 256;
    private static int acceptedFrames;

    private GlobalWmoNativeProof() {}

    private static final GlobalWmoCamera CAMERA = new GlobalWmoCamera(0, 0, 0, 0, 0);

    private static GlobalWmoAuthoredData triangle(
            float depth, float y, float radius, boolean reverse, float u, float v, byte[] image) {
        float[] vertices = {
            depth,
            y - radius,
            -radius,
            u,
            v,
            depth,
            y + radius,
            -radius,
            u,
            v,
            depth,
            y,
            radius,
            u,
            v
        };
        int[] indices = reverse ? new int[] {2, 1, 0} : new int[] {0, 1, 2};
        return new GlobalWmoAuthoredData(
                vertices,
                indices,
                List.of(image),
                List.of(new WorldGlobalHashes.Batch(0, 0, 0, 3)));
    }

    private static GpuGlobalWmoSceneFile.Decoded decoded(
            Path parent, String name, WorldGlobalSceneWriter.StructuralRead data)
            throws IOException {
        Path directory = Files.createDirectory(parent.resolve(name));
        var target =
                WorldGlobalSceneWriter.Destination.create(directory, new Object(), "7".repeat(64));
        try (var file = WorldGlobalSceneWriter.writeUnadvertised(target, data, () -> false)) {
            return GpuGlobalWmoSceneFile.read(target.path(), file.expected().input(), () -> false);
        }
    }

    private static BgfxGlobalWmoRenderer upload(
            Harness gpu,
            GpuGlobalWmoSceneFile.Decoded data,
            java.util.function.BooleanSupplier canceled)
            throws IOException {
        // Sequential oracle scenes never overlap retired native resources or queued old uploads.
        gpu.assertBaseline();
        return BgfxGlobalWmoRenderer.upload(data, canceled);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected private output directory");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path scratch = Files.createTempDirectory(output, "global-");
        try (var gpu = new Harness()) {
            for (boolean reversed : new boolean[] {false, true}) {
                // Authored source=(-10,+4,z) becomes native=(+10,-4,z): east is screen-right.
                try (var scene =
                        upload(
                                gpu,
                                decoded(
                                        scratch,
                                        "east-" + reversed,
                                        triangle(
                                                10,
                                                -4,
                                                2,
                                                reversed,
                                                0,
                                                0,
                                                GlobalWmoAuthoredData.solid(255, 0, 0))),
                                () -> false)) {
                    int[] image = gpu.render(scene, CAMERA);
                    rgb(image[128 * SIDE + at(.4)], 255, 0, 0, 0, "D/handedness east landmark");
                    rgb(image[128 * SIDE + at(-.4)], 17, 34, 51, 0, "West stays background");
                    rgb(
                            gpu.render(scene, CAMERA.move(0, 4, 0))[128 * SIDE + 128],
                            255,
                            0,
                            0,
                            0,
                            "Right strafe centers east landmark");
                    require(scene.stats().liveStagingBytes() == 0, "Staging escaped upload");
                    write(output.resolve("east-" + reversed + ".png"), image);
                }
            }
            byte[] checker = {
                (byte) 255,
                0,
                0,
                (byte) 255,
                0,
                (byte) 255,
                0,
                (byte) 255,
                0,
                0,
                (byte) 255,
                (byte) 255,
                (byte) 255,
                (byte) 255,
                (byte) 255,
                (byte) 255
            };
            int[][] expected = {{255, 0, 0}, {128, 128, 128}, {128, 128, 0}};
            float[][] samples = {{-.75f, -.75f}, {.5f, .5f}, {0, .25f}};
            for (int i = 0; i < samples.length; i++) {
                try (var scene =
                        upload(
                                gpu,
                                decoded(
                                        scratch,
                                        "uv-" + i,
                                        triangle(
                                                10,
                                                0,
                                                4,
                                                false,
                                                samples[i][0],
                                                samples[i][1],
                                                checker)),
                                () -> false)) {
                    int[] image = gpu.render(scene, CAMERA);
                    rgb(
                            image[128 * SIDE + 128],
                            expected[i][0],
                            expected[i][1],
                            expected[i][2],
                            1,
                            "Repeated negative/bilinear UV " + i);
                }
            }
            try (var scene =
                    upload(
                            gpu,
                            decoded(
                                    scratch,
                                    "far",
                                    triangle(
                                            3000,
                                            0,
                                            2000,
                                            false,
                                            0,
                                            0,
                                            GlobalWmoAuthoredData.solid(255, 0, 0))),
                            () -> false)) {
                rgb(
                        gpu.render(scene, CAMERA)[128 * SIDE + 128],
                        255,
                        0,
                        0,
                        0,
                        "No terrain far or fog");
            }
            try (var scene =
                    upload(
                            gpu,
                            decoded(
                                    scratch,
                                    "inside",
                                    triangle(
                                            10,
                                            0,
                                            2,
                                            false,
                                            0,
                                            0,
                                            GlobalWmoAuthoredData.solid(0, 255, 0))),
                            () -> false)) {
                rgb(
                        gpu.render(scene, new GlobalWmoCamera(9.4, 0, 0, 0, 0))[128 * SIDE + 128],
                        0,
                        255,
                        0,
                        0,
                        "Inside camera near visible");
                rgb(
                        gpu.render(scene, new GlobalWmoCamera(9.6, 0, 0, 0, 0))[128 * SIDE + 128],
                        17,
                        34,
                        51,
                        0,
                        "Near clips foreground");
            }
            float[] vertices = {10, -4, -4, 0, 0, 10, 4, -4, 0, 0, 10, 0, 4, 0, 0};
            for (boolean reverse : new boolean[] {false, true}) {
                var batches =
                        List.of(
                                new WorldGlobalHashes.Batch(0, reverse ? 1 : 0, 0, 3),
                                new WorldGlobalHashes.Batch(0, reverse ? 0 : 1, 3, 3));
                var authored =
                        new GlobalWmoAuthoredData(
                                vertices,
                                new int[] {0, 1, 2, 2, 1, 0},
                                List.of(
                                        GlobalWmoAuthoredData.solid(255, 0, 0),
                                        GlobalWmoAuthoredData.solid(0, 255, 0)),
                                batches);
                try (var scene =
                        upload(
                                gpu,
                                decoded(scratch, "coplanar-" + reverse, authored),
                                () -> false)) {
                    int[] image = gpu.render(scene, CAMERA);
                    rgb(
                            image[128 * SIDE + 128],
                            reverse ? 0 : 255,
                            reverse ? 255 : 0,
                            0,
                            0,
                            "Coplanar source first wins");
                }
            }
            try (var scene =
                    upload(
                            gpu,
                            decoded(
                                    scratch,
                                    "three-readbacks",
                                    triangle(
                                            10,
                                            0,
                                            5,
                                            false,
                                            0,
                                            0,
                                            GlobalWmoAuthoredData.solid(255, 0, 0))),
                            () -> false)) {
                gpu.threeTickets(scene);
                var rejected = new java.util.concurrent.CompletableFuture<Boolean>();
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        scene.close();
                                        rejected.complete(false);
                                    } catch (IllegalStateException expectedFailure) {
                                        rejected.complete(true);
                                    }
                                });
                require(
                        rejected.get(3, java.util.concurrent.TimeUnit.SECONDS),
                        "Foreign native owner accepted");
                rgb(
                        gpu.render(scene, CAMERA)[128 * SIDE + 128],
                        255,
                        0,
                        0,
                        0,
                        "Foreign close leaves actual owner intact");
            }
            var crossing =
                    new GlobalWmoAuthoredData(
                            new float[] {.2f, -1, -1, 0, 0, 2, 1, -1, 0, 0, 2, 0, 1, 0, 0},
                            new int[] {0, 1, 2},
                            List.of(GlobalWmoAuthoredData.solid(0, 255, 0)),
                            List.of(new WorldGlobalHashes.Batch(0, 0, 0, 3)));
            try (var scene =
                    upload(gpu, decoded(scratch, "near-crossing", crossing), () -> false)) {
                rgb(
                        gpu.render(scene, CAMERA)[128 * SIDE + 128],
                        0,
                        255,
                        0,
                        0,
                        "Near-crossing triangle remains visible");
                rgb(
                        gpu.render(scene, CAMERA, 160, 80)[40 * SIDE + 80],
                        0,
                        255,
                        0,
                        0,
                        "Actual requested dimensions");
            }
            try (var scene =
                    upload(
                            gpu,
                            decoded(scratch, "max", GlobalWmoAuthoredData.maximum()),
                            () -> false)) {
                var stats = scene.stats();
                // Expected sizes follow the layout constants: position and UV floats per vertex,
                // WorldGlobalLayout.INDEX_BYTES per index (32-bit since #281), RGBA8 texels.
                var counts = GlobalWmoAuthoredData.maximum().counts();
                long mesh =
                        counts.vertices() * 20L
                                + (long) counts.indices() * WorldGlobalLayout.INDEX_BYTES;
                long image = counts.pixels() * 4L;
                require(
                        stats.meshBytes() == mesh && stats.imageBytes() == image,
                        "Exact maximum uploaded bytes: mesh="
                                + stats.meshBytes()
                                + " expected "
                                + mesh
                                + ", image="
                                + stats.imageBytes()
                                + " expected "
                                + image);
                require(
                        stats.peakMeshStagingBytes() == mesh
                                && stats.peakImageStagingBytes()
                                        == GlobalWmoNativeScene.DIRECT_IMAGE
                                && stats.liveStagingBytes() == 0,
                        "Actual maximum staging ownership");
                require(
                        stats.images() == 18 && stats.batches() == 64,
                        "Exact maximum handles and batches");
                gpu.render(scene, CAMERA);
                System.out.println(
                        "GLOBAL_MAX mesh="
                                + stats.meshBytes()
                                + " rgba="
                                + stats.imageBytes()
                                + " meshStaging="
                                + stats.peakMeshStagingBytes()
                                + " imageStaging="
                                + stats.peakImageStagingBytes());
            }
            gpu.assertBaseline();
            var partial =
                    new GlobalWmoAuthoredData(
                            vertices,
                            new int[] {0, 1, 2, 2, 1, 0},
                            List.of(
                                    GlobalWmoAuthoredData.solid(255, 0, 0),
                                    GlobalWmoAuthoredData.solid(0, 255, 0)),
                            List.of(
                                    new WorldGlobalHashes.Batch(0, 0, 0, 3),
                                    new WorldGlobalHashes.Batch(0, 1, 3, 3)));
            for (String phase : List.of("uploadMesh", "uploadImages", "errorImages")) {
                var fatal = new AssertionError("Authored fatal upload probe");
                String method = phase.equals("errorImages") ? "uploadImages" : phase;
                var data = decoded(scratch, "cancel-" + phase, partial);
                int[] checks = {0};
                boolean canceled = false;
                try {
                    var unexpected =
                            upload(
                                    gpu,
                                    data,
                                    () -> {
                                        boolean inside =
                                                StackWalker.getInstance()
                                                        .walk(
                                                                frames ->
                                                                        frames.anyMatch(
                                                                                f ->
                                                                                        f.getClassName()
                                                                                                        .equals(
                                                                                                                BgfxGlobalWmoRenderer
                                                                                                                        .class
                                                                                                                        .getName())
                                                                                                && f.getMethodName()
                                                                                                        .equals(
                                                                                                                method)));
                                        boolean stop = inside && ++checks[0] == 4;
                                        if (stop && phase.equals("errorImages")) {
                                            throw fatal;
                                        }
                                        return stop;
                                    });
                    unexpected.close();
                } catch (CancellationException expectedFailure) {
                    require(!phase.equals("errorImages"), "Fatal upload became cancellation");
                    canceled = true;
                } catch (AssertionError actual) {
                    require(
                            phase.equals("errorImages") && actual == fatal,
                            "Fatal upload identity lost");
                    canceled = true;
                }
                require(canceled, "Expected partial native upload cancellation " + phase);
                boolean closed = false;
                try {
                    data.vertex(0, 0);
                } catch (IllegalStateException expectedFailure) {
                    closed = true;
                }
                require(closed, "Canceled adoption retains CPU mesh");
                for (int n = 0; n < 4; n++) {
                    bgfx_frame(BGFX_FRAME_NONE);
                }
                gpu.assertBaseline();
            }
        }
        System.out.println(
                "GLOBAL_NATIVE_PROOF PASS frames="
                        + acceptedFrames
                        + " candidateProfiles=4 dx11=COMPILED_UNTESTED noSource=true noGui=true");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    /** Pixel {@code offset} focal lengths from the frame center (the authored rays). */
    private static int at(double offset) {
        return (int) Math.round(SIDE * .5 + offset * TerrainCamera.focalPixels(SIDE));
    }

    private static void rgb(int value, int r, int g, int b, int tolerance, String why) {
        if (Math.abs((value >> 16 & 255) - r) > tolerance
                || Math.abs((value >> 8 & 255) - g) > tolerance
                || Math.abs((value & 255) - b) > tolerance) {
            throw new AssertionError(
                    why
                            + " expected="
                            + r
                            + ","
                            + g
                            + ","
                            + b
                            + " actual="
                            + Integer.toHexString(value));
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
        private final short[] extraTextures = {-1, -1};
        private final ByteBuffer[] extraPixels = new ByteBuffer[2];
        private boolean initialized;
        private List<Integer> baseline;

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
                        "GLOBAL_PROOF_DEVICE vendor="
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
                for (int n = 0; n < 4; n++) {
                    bgfx_frame(BGFX_FRAME_NONE);
                }
                baseline = counts();
            } catch (IOException | RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        int[] render(BgfxGlobalWmoRenderer scene, GlobalWmoCamera camera) throws IOException {
            return render(scene, camera, SIDE, SIDE);
        }

        int[] render(BgfxGlobalWmoRenderer scene, GlobalWmoCamera camera, int width, int height)
                throws IOException {
            bgfx_set_view_frame_buffer(0, target);
            bgfx_set_view_rect(0, 0, 0, width, height);
            bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
            bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x112233ff, 0f, 0);
            scene.draw(camera, width, height);
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

        private record Ticket(
                GlobalWmoCamera camera, int width, int height, int ready, int ordinal) {}

        void threeTickets(BgfxGlobalWmoRenderer scene) throws IOException {
            short[] textures = {staging, -1, -1};
            ByteBuffer[] destinations = {pixels, null, null};
            Ticket[] tickets = new Ticket[3];
            try {
                for (int i = 1; i < 3; i++) {
                    textures[i] =
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
                    extraTextures[i - 1] = textures[i];
                    destinations[i] = MemoryUtil.memAlloc(SIDE * SIDE * 4);
                    extraPixels[i - 1] = destinations[i];
                }
                for (int i = 0; i < 3; i++) {
                    int width = 256 - i * 48, height = 256 - i * 64;
                    var camera = CAMERA.move(0, i == 1 ? 20 : i * .5, 0);
                    bgfx_set_view_frame_buffer(0, target);
                    bgfx_set_view_rect(0, 0, 0, width, height);
                    bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
                    bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x112233ff, 0f, 0);
                    scene.draw(camera, width, height);
                    bgfx_blit(1, textures[i], 0, 0, 0, 0, color, 0, 0, 0, 0, SIDE, SIDE, 1);
                    int ready = bgfx_read_texture(textures[i], destinations[i], 0, 0);
                    tickets[i] = new Ticket(camera, width, height, ready, i);
                    bgfx_frame(BGFX_FRAME_NONE);
                }
                for (var ticket : tickets) {
                    await(ticket.ready());
                    require(
                            ticket.camera()
                                    .equals(
                                            CAMERA.move(
                                                    0,
                                                    ticket.ordinal() == 1
                                                            ? 20
                                                            : ticket.ordinal() * .5,
                                                    0)),
                            "Readback lost draw-camera metadata");
                    int at = ((ticket.height() / 2) * SIDE + ticket.width() / 2) * 4;
                    var bytes = destinations[ticket.ordinal()];
                    boolean background = ticket.ordinal() == 1;
                    require(
                            (bytes.get(at) & 255) == (background ? 17 : 255)
                                    && (bytes.get(at + 1) & 255) == (background ? 34 : 0)
                                    && (bytes.get(at + 2) & 255) == (background ? 51 : 0),
                            "Concurrent readback pixel/camera differs");
                    acceptedFrames++;
                }
            } finally {
                // Never free native readback destinations while an issued read is pending.
                for (var ticket : tickets) {
                    if (ticket != null) {
                        await(ticket.ready());
                    }
                }
                for (int i = 1; i < 3; i++) {
                    if (textures[i] != -1) {
                        bgfx_destroy_texture(textures[i]);
                        extraTextures[i - 1] = -1;
                    }
                    if (destinations[i] != null) {
                        MemoryUtil.memFree(destinations[i]);
                        extraPixels[i - 1] = null;
                    }
                }
            }
        }

        private static void await(int ready) throws IOException {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (Integer.compareUnsigned(bgfx_frame(BGFX_FRAME_NONE), ready) < 0) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("Global actual readback timeout");
                }
            }
        }

        private static List<Integer> counts() {
            var s = bgfx_get_stats();
            return List.of(
                    Short.toUnsignedInt(s.numVertexBuffers()),
                    Short.toUnsignedInt(s.numIndexBuffers()),
                    Short.toUnsignedInt(s.numTextures()),
                    Short.toUnsignedInt(s.numUniforms()),
                    Short.toUnsignedInt(s.numPrograms()),
                    Short.toUnsignedInt(s.numShaders()));
        }

        void assertBaseline() {
            for (int n = 0; n < 4; n++) {
                bgfx_frame(BGFX_FRAME_NONE);
            }
            require(
                    counts().equals(baseline),
                    "Global handles fail actual retirement: " + counts() + " vs " + baseline);
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
                for (short extra : extraTextures) {
                    if (extra != -1) {
                        bgfx_destroy_texture(extra);
                    }
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
            // Exceptional ticket-drain paths keep these direct destinations until shutdown returns.
            for (int i = 0; i < extraPixels.length; i++) {
                if (extraPixels[i] != null) {
                    MemoryUtil.memFree(extraPixels[i]);
                    extraPixels[i] = null;
                }
            }
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
        }
    }
}
