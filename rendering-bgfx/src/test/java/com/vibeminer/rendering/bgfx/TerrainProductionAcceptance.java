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
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Opt-in independent production shader oracles; no game assets and no shared expected-color math.
 */
public final class TerrainProductionAcceptance {
    private static final int WIDTH = 256, HEIGHT = 256;
    private static final TerrainCamera ZERO = new TerrainCamera(0, 0, 0, 0, 0);
    private static int frames;

    private record Vertex(double x, double y, double z) {}

    private record Triangle(Vertex a, Vertex b, Vertex c) {}

    private TerrainProductionAcceptance() {}

    private static Vertex v(double x, double y, double z) {
        return new Vertex(x, y, z);
    }

    private static Triangle triangle(Vertex a, Vertex b, Vertex c) {
        return new Triangle(a, b, c);
    }

    private static Triangle panel(double depth, boolean bright) {
        return triangle(
                v(depth, -.2 * depth, bright ? -100 : -.1 * depth),
                v(depth, .2 * depth, bright ? -100 : -.1 * depth),
                v(depth, 0, bright ? 2000 : .2 * depth));
    }

    private static TerrainScene scene(List<Triangle> triangles) {
        float[] vertices = new float[Math.max(3, triangles.size() * 9)];
        int[] indices = new int[triangles.size() * 3];
        int at = 0;
        for (var triangle : triangles) {
            for (var vertex : List.of(triangle.a, triangle.b, triangle.c)) {
                vertices[at++] = (float) vertex.x;
                vertices[at++] = (float) vertex.y;
                vertices[at++] = (float) vertex.z;
            }
        }
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return new TerrainScene(List.of(TerrainScene.Tile.copyOf(17, 21, vertices, indices, 1)));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected authored artifact directory");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        try (var gpu = new Harness()) {
            projection(gpu, output);
            color(gpu);
            clipping(gpu);
            depth(gpu);
            persistentAndBounds(gpu);
        }
        Files.writeString(
                output.resolve("terrain-production-acceptance.txt"),
                "PASS "
                        + frames
                        + " actual Vulkan production terrain frames; independent analytic projection, native color, depth, bounds and persistent ownership\n");
        System.out.println(
                "Production terrain independent acceptance PASS: " + frames + " native frames");
    }

    private static void pixel(int[] image, int x, int y, int expected) {
        int actual = image[y * WIDTH + x];
        for (int shift : new int[] {0, 8, 16}) {
            if (Math.abs((actual >> shift & 255) - (expected >> shift & 255)) > 1) {
                throw new AssertionError(
                        "Interior pixel "
                                + x
                                + ","
                                + y
                                + " expected "
                                + Integer.toHexString(expected)
                                + " actual "
                                + Integer.toHexString(actual));
            }
        }
    }

    private static void exact(int[] image, int x, int y, int expected) {
        if (image[y * WIDTH + x] != expected) {
            throw new AssertionError(
                    "Exact sky row "
                            + y
                            + " expected "
                            + Integer.toHexString(expected)
                            + " actual "
                            + Integer.toHexString(image[y * WIDTH + x]));
        }
    }

    private static void panelPixels(int[] image, int color) {
        // At focal204.8, source corners project to (87.04,148.48),(168.96,148.48),(128,87.04).
        for (int[] point : new int[][] {{128, 128}, {100, 140}, {155, 140}, {128, 100}}) {
            pixel(image, point[0], point[1], color);
        }
        exact(image, 80, 128, 0xff83abc3);
        exact(image, 128, 80, 0xff749fbc);
    }

    private static void projection(Harness gpu, Path output) throws Exception {
        int[] first = gpu.render(List.of(panel(10, false)), ZERO);
        panelPixels(first, 0xff2c4729);
        panelPixels(
                gpu.render(
                        List.of(triangle(v(2, 10, -1), v(-2, 10, -1), v(0, 10, 2))),
                        new TerrainCamera(0, 0, 0, Math.PI / 2, 0)),
                0xff304d2d);
        panelPixels(
                gpu.render(
                        List.of(
                                triangle(
                                        v(9.160254037844386, -2, 4.133974596215562),
                                        v(9.160254037844386, 2, 4.133974596215562),
                                        v(7.660254037844386, 0, 6.732050807568877))),
                        new TerrainCamera(0, 0, 0, 0, Math.PI / 6)),
                0xff3b6038);
        // Altitude stays native: translating this patch upward clamps rock=1 before rebasing.
        panelPixels(
                gpu.render(
                        List.of(
                                triangle(
                                        v(999010, -999002, 998999),
                                        v(999010, -998998, 998999),
                                        v(999010, -999000, 999002))),
                        new TerrainCamera(999000, -999000, 999000, 0, 0)),
                0xff544d4c);
        var original = panel(10, false);
        panelPixels(
                gpu.render(List.of(triangle(original.c, original.b, original.a)), ZERO),
                0xff2c4729);
        var bitmap = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        bitmap.setRGB(0, 0, WIDTH, HEIGHT, first, 0, WIDTH);
        ImageIO.write(bitmap, "png", output.resolve("terrain-production-axes.png").toFile());
    }

    private static void color(Harness gpu) throws Exception {
        // Native mean altitude300 -> rock.25, +X normal -> light.615, original max depth1000 ->
        // fog.25.
        // Untruncated base (54.27375,72.8775,50.58375) yields final RGB78,100,86.
        pixel(
                gpu.render(
                        List.of(triangle(v(1000, -200, 200), v(1000, 200, 200), v(1000, 0, 500))),
                        new TerrainCamera(0, 0, 300, 0, 0)),
                128,
                128,
                0xff4e6456);
    }

    private static void clipping(Harness gpu) throws Exception {
        pixel(
                gpu.render(List.of(triangle(v(.25, -.1, -.1), v(2, .6, -.1), v(2, 0, .6))), ZERO),
                128,
                128,
                0xff3d633a);
        pixel(
                gpu.render(
                        List.of(triangle(v(.25, -.1, -.1), v(1000, 300, -50), v(1000, 0, 300))),
                        ZERO),
                128,
                128,
                0xff567b5e);
        int[] sky = gpu.render(List.of(panel(.49, false)), ZERO);
        exact(sky, 0, 0, 0xff5b8db1);
        exact(sky, 0, 3, 0xff5b8db1);
        exact(sky, 255, 255, 0xffaac8d5);
        exact(gpu.render(List.of(panel(2200.1, false)), ZERO), 128, 128, 0xff83abc3);
        pixel(gpu.render(List.of(panel(2200, false)), ZERO), 128, 128, 0xff88a7ac);
        int[] holes = gpu.render(List.of(), ZERO);
        exact(holes, 128, 128, 0xff83abc3);
    }

    private static void depth(Harness gpu) throws Exception {
        for (double gap : new double[] {.01, .1}) {
            var near = panel(1999.9, false);
            var far = panel(1999.9 + gap, true);
            for (var order : List.of(List.of(near, far), List.of(far, near))) {
                pixel(gpu.render(order, ZERO), 128, 128, 0xff88a7ac);
            }
        }
        // A remains at2000, allowing a center fragment near95333 to survive whole-triangle far
        // rejection.
        var near = triangle(v(2000, -800000, 0), v(100000, 40000, 40000), v(100000, 40000, -40000));
        double s = 1.00001;
        var far =
                triangle(
                        v(2000 * s, -800000 * s, 0),
                        v(100000 * s, 40000 * s, 80000 * s),
                        v(100000 * s, 40000 * s, -40000 * s));
        for (var order : List.of(List.of(near, far), List.of(far, near))) {
            pixel(gpu.render(order, ZERO), 128, 128, 0xff88a6ac);
        }
        for (double d : new double[] {.5, 10, 1999.9}) {
            var dark = panel(d, false);
            var bright = panel(d, true);
            pixel(
                    gpu.render(List.of(dark, bright), ZERO),
                    128,
                    128,
                    d < 600 ? 0xff2c4729 : 0xff88a7ac);
            pixel(
                    gpu.render(List.of(bright, dark), ZERO),
                    128,
                    128,
                    d < 600 ? 0xff4a4b44 : 0xff8da7b0);
        }
    }

    private static void persistentAndBounds(Harness gpu) throws Exception {
        try (var renderer = new BgfxTerrainRenderer(scene(List.of(panel(10, false))))) {
            for (var camera : List.of(ZERO, new TerrainCamera(1, .5, .25, .1, .05), ZERO)) {
                gpu.render(renderer, camera);
                if (renderer.triangleCount() != 1
                        || renderer.uploadedBytes() != 48
                        || renderer.tileBufferCount() != 1) {
                    throw new AssertionError("Camera motion changed persistent upload");
                }
            }
        }
        float[] vertices = new float[TerrainScene.MAX_VERTICES_PER_TILE * 3];
        int[] high = {32768, 32769, 37119};
        var panel = panel(10, false);
        int corner = 0;
        for (var v : List.of(panel.a, panel.b, panel.c)) {
            int at = high[corner++] * 3;
            vertices[at] = (float) v.x;
            vertices[at + 1] = (float) v.y;
            vertices[at + 2] = (float) v.z;
        }
        try (var renderer =
                new BgfxTerrainRenderer(
                        new TerrainScene(
                                List.of(TerrainScene.Tile.copyOf(17, 21, vertices, high, 256))))) {
            pixel(gpu.render(renderer, ZERO), 128, 128, 0xff2c4729);
        }
        var tiles = new ArrayList<TerrainScene.Tile>();
        int[] maximumIndices = new int[TerrainScene.MAX_INDICES_PER_TILE];
        for (int i = 0; i < 9; i++) {
            tiles.add(TerrainScene.Tile.copyOf(i, 0, vertices, maximumIndices, 256));
        }
        try (var renderer = new BgfxTerrainRenderer(new TerrainScene(tiles))) {
            if (renderer.triangleCount() != 589824
                    || renderer.uploadedBytes() != 28311552L
                    || renderer.tileBufferCount() != 9) {
                throw new AssertionError("Nine-tile maximum upload differs");
            }
            exact(gpu.render(renderer, ZERO), 128, 128, 0xff83abc3);
        }
        var canceled = new java.util.concurrent.atomic.AtomicInteger();
        try {
            new BgfxTerrainRenderer(
                    scene(List.of(panel(10, false))), () -> canceled.incrementAndGet() > 1);
            throw new AssertionError("Canceled upload succeeded");
        } catch (java.util.concurrent.CancellationException expected) {
            /* constructor releases partial resources */
        }
        pixel(gpu.render(List.of(panel(10, false)), ZERO), 128, 128, 0xff2c4729);
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
                        throw new IOException("Headless Vulkan unavailable");
                    }
                    initialized = true;
                }
                var caps = bgfx_get_caps();
                if (caps.rendererType() != BGFX_RENDERER_TYPE_VULKAN || caps.homogeneousDepth()) {
                    throw new IOException("Expected zero-to-one Vulkan");
                }
                GpuViewerMain.requireHardware(
                        Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
                color =
                        valid(
                                bgfx_create_texture_2d(
                                        WIDTH,
                                        HEIGHT,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_RT,
                                        null,
                                        0));
                depth =
                        valid(
                                bgfx_create_texture_2d(
                                        WIDTH,
                                        HEIGHT,
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
                                        WIDTH,
                                        HEIGHT,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                                        null,
                                        0));
                pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
            } catch (IOException | RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        int[] render(List<Triangle> triangles, TerrainCamera camera) throws IOException {
            try (var renderer = new BgfxTerrainRenderer(scene(triangles))) {
                return render(renderer, camera);
            }
        }

        int[] render(BgfxTerrainRenderer renderer, TerrainCamera camera) throws IOException {
            bgfx_set_view_frame_buffer(0, target);
            renderer.render(camera, WIDTH, HEIGHT);
            bgfx_blit(1, staging, 0, 0, 0, 0, color, 0, 0, 0, 0, WIDTH, HEIGHT, 1);
            int ready = bgfx_read_texture(staging, pixels, 0, 0);
            long deadline = System.nanoTime() + 10_000_000_000L;
            int frame;
            do {
                frame = bgfx_frame(BGFX_FRAME_NONE);
                if (System.nanoTime() > deadline) {
                    throw new IOException("Production terrain readback timeout");
                }
            } while (Integer.compareUnsigned(frame, ready) < 0);
            int[] result = new int[WIDTH * HEIGHT];
            boolean invert = bgfx_get_caps().originBottomLeft();
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int at = (y * WIDTH + x) * 4;
                    int value =
                            (pixels.get(at + 3) & 255) << 24
                                    | (pixels.get(at) & 255) << 16
                                    | (pixels.get(at + 1) & 255) << 8
                                    | pixels.get(at + 2) & 255;
                    if ((value >>> 24) != 255) {
                        throw new AssertionError("Production frame not opaque");
                    }
                    result[(invert ? HEIGHT - 1 - y : y) * WIDTH + x] = value;
                }
            }
            frames++;
            return result;
        }

        private static short valid(short handle) throws IOException {
            if (handle == -1) {
                throw new IOException("Native resource unavailable");
            }
            return handle;
        }

        @Override
        public void close() {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
            if (initialized) {
                if (staging != -1) {
                    bgfx_destroy_texture(staging);
                }
                if (target != -1) {
                    bgfx_destroy_frame_buffer(target);
                }
                if (color != -1) {
                    bgfx_destroy_texture(color);
                }
                if (depth != -1) {
                    bgfx_destroy_texture(depth);
                }
                bgfx_frame(BGFX_FRAME_NONE);
                bgfx_shutdown();
                initialized = false;
            }
        }
    }
}
