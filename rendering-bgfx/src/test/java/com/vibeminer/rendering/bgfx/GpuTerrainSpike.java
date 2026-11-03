package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD2;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INSTANCING;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_GREATER;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_MAT4;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_copy;
import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_create_program;
import static org.lwjgl.bgfx.BGFX.bgfx_create_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_create_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_program;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Authored-data feasibility spike; not a production terrain renderer or GUI integration. */
public final class GpuTerrainSpike {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 256;
    public static final int INSTANCE_BYTES = 48;
    private static final int MAX_TRIANGLES = 9 * 65_536;
    private static final short INVALID = -1;

    private GpuTerrainSpike() {}

    public record Vertex(double x, double y, double z) {
        public Vertex {
            coordinate(x);
            coordinate(y);
            coordinate(z);
        }
    }

    public record Camera(double x, double y, double z, double yaw, double pitch) {
        public Camera {
            coordinate(x);
            coordinate(y);
            coordinate(z);
            coordinate(yaw);
            if (!Double.isFinite(pitch) || Math.abs(pitch) > 1.5) {
                throw new IllegalArgumentException("Invalid terrain pitch");
            }
        }
    }

    /** Base channels remain untruncated floating point in the range 0..255. */
    public record Triangle(Vertex a, Vertex b, Vertex c, double baseR, double baseG, double baseB) {
        public Triangle {
            java.util.Objects.requireNonNull(a);
            java.util.Objects.requireNonNull(b);
            java.util.Objects.requireNonNull(c);
            for (double value : new double[] {baseR, baseG, baseB}) {
                if (!Double.isFinite(value) || value < 0 || value > 255) {
                    throw new IllegalArgumentException("Invalid base color");
                }
            }
        }

        public static Triangle shaded(Vertex a, Vertex b, Vertex c) {
            double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
            double vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double light =
                    length == 0
                            ? .7
                            : .45 + .55 * Math.abs((nx * .3 + ny * .4 + nz * .866) / length);
            double altitude = (a.z + b.z + c.z) / 3;
            double rock = Math.clamp((altitude - 150) / 600, 0, 1);
            return new Triangle(
                    a,
                    b,
                    c,
                    (72 + rock * 65) * light,
                    (116 + rock * 10) * light,
                    (68 + rock * 57) * light);
        }
    }

    public static final class Scene implements AutoCloseable {
        private final Harness owner;
        private final Vertex origin;
        private final int triangleCount;
        private short instances = INVALID;

        private Scene(Harness owner, Vertex origin, int triangleCount) {
            this.owner = owner;
            this.origin = origin;
            this.triangleCount = triangleCount;
        }

        public int triangleCount() {
            return triangleCount;
        }

        public int uploadCount() {
            return instances == INVALID ? 0 : 1;
        }

        public long uploadBytes() {
            return (long) triangleCount * INSTANCE_BYTES;
        }

        @Override
        public void close() {
            if (instances != INVALID) {
                bgfx_destroy_vertex_buffer(instances);
                instances = INVALID;
            }
            owner.scenes.remove(this);
        }
    }

    public static final class Harness implements AutoCloseable {
        private final List<Scene> scenes = new ArrayList<>();

        private short color = INVALID, depth = INVALID, target = INVALID, staging = INVALID;
        private short template = INVALID, program = INVALID, skyProgram = INVALID;
        private short eye = INVALID, projection = INVALID, mode = INVALID;
        private ByteBuffer pixels;
        private boolean initialized;
        private int frames;

        public Harness() throws IOException {
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
                long required = BGFX_CAPS_TEXTURE_READ_BACK | BGFX_CAPS_INSTANCING;
                if (caps.rendererType() != BGFX_RENDERER_TYPE_VULKAN
                        || caps.homogeneousDepth()
                        || (caps.supported() & required) != required
                        || !bgfx_is_texture_valid(
                                0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT)) {
                    throw new IOException(
                            "Terrain spike requires Vulkan, persistent instancing, D32F and readback");
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
                template = template();
                program = program("");
                skyProgram = program("sky/");
                eye = valid(bgfx_create_uniform("u_terrainEye", BGFX_UNIFORM_TYPE_MAT4, 1));
                projection =
                        valid(
                                bgfx_create_uniform(
                                        "u_terrainProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
                mode = valid(bgfx_create_uniform("u_terrainMode", BGFX_UNIFORM_TYPE_VEC4, 1));
                bgfx_set_view_frame_buffer(0, target);
                bgfx_set_view_rect(0, 0, 0, WIDTH, HEIGHT);
                bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 0, 0);
            } catch (IOException | RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        public Scene upload(List<Triangle> triangles) throws IOException {
            requireOpen();
            if (triangles.size() > MAX_TRIANGLES) {
                throw new IllegalArgumentException("Terrain triangle cap");
            }
            Vertex origin = triangles.isEmpty() ? new Vertex(0, 0, 0) : triangles.getFirst().a;
            var scene = new Scene(this, origin, triangles.size());
            if (!triangles.isEmpty()) {
                FloatBuffer packed =
                        MemoryUtil.memAllocFloat(
                                Math.multiplyExact(triangles.size(), INSTANCE_BYTES / Float.BYTES));
                try {
                    for (var triangle : triangles) {
                        put(packed, triangle.a, origin, triangle.baseR);
                        put(packed, triangle.b, origin, triangle.baseG);
                        put(packed, triangle.c, origin, triangle.baseB);
                    }
                    packed.flip();
                    try (var layout = BGFXVertexLayout.calloc()) {
                        bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
                        bgfx_vertex_layout_add(
                                layout,
                                BGFX_ATTRIB_TEXCOORD0,
                                4,
                                BGFX_ATTRIB_TYPE_FLOAT,
                                false,
                                false);
                        bgfx_vertex_layout_add(
                                layout,
                                BGFX_ATTRIB_TEXCOORD1,
                                4,
                                BGFX_ATTRIB_TYPE_FLOAT,
                                false,
                                false);
                        bgfx_vertex_layout_add(
                                layout,
                                BGFX_ATTRIB_TEXCOORD2,
                                4,
                                BGFX_ATTRIB_TYPE_FLOAT,
                                false,
                                false);
                        bgfx_vertex_layout_end(layout);
                        if (layout.stride() != INSTANCE_BYTES) {
                            throw new IOException("Terrain instance stride mismatch");
                        }
                        scene.instances =
                                valid(
                                        bgfx_create_vertex_buffer(
                                                bgfx_copy(packed), layout, BGFX_BUFFER_NONE));
                    }
                } finally {
                    MemoryUtil.memFree(packed);
                }
            }
            scenes.add(scene);
            return scene;
        }

        public int[] render(List<Triangle> triangles, Camera camera) throws IOException {
            try (var scene = upload(triangles)) {
                return render(scene, camera);
            }
        }

        public int[] render(Scene scene, Camera camera) throws IOException {
            requireOpen();
            if (scene.owner != this || !scenes.contains(scene)) {
                throw new IllegalArgumentException("Foreign or closed scene");
            }
            try (var stack = MemoryStack.stackPush()) {
                FloatBuffer eyeMatrix = eye(stack, scene.origin, camera);
                FloatBuffer projectionMatrix =
                        projection(stack, bgfx_get_caps().homogeneousDepth());
                FloatBuffer flags = stack.mallocFloat(4);
                bgfx_set_uniform(eye, eyeMatrix, 1);
                bgfx_set_uniform(projection, projectionMatrix, 1);
                flags.put(new float[] {1, HEIGHT, bgfx_get_caps().originBottomLeft() ? 1 : 0, 0})
                        .flip();
                bgfx_set_uniform(mode, flags, 1);
                bgfx_set_vertex_buffer(0, template, 0, 3);
                bgfx_set_state(
                        BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_ALWAYS,
                        0);
                bgfx_submit(0, skyProgram, 0, BGFX_DISCARD_ALL);
                if (scene.triangleCount != 0) {
                    bgfx_set_uniform(eye, eyeMatrix, 1);
                    bgfx_set_uniform(projection, projectionMatrix, 1);
                    flags.clear().put(new float[] {0, HEIGHT, 0, 0}).flip();
                    bgfx_set_uniform(mode, flags, 1);
                    bgfx_set_vertex_buffer(0, template, 0, 3);
                    bgfx_set_instance_data_from_vertex_buffer(
                            scene.instances, 0, scene.triangleCount);
                    bgfx_set_state(
                            BGFX_STATE_WRITE_RGB
                                    | BGFX_STATE_WRITE_A
                                    | BGFX_STATE_WRITE_Z
                                    | BGFX_STATE_DEPTH_TEST_GREATER,
                            0);
                    bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
                }
            }
            bgfx_blit(1, staging, 0, 0, 0, 0, color, 0, 0, 0, 0, WIDTH, HEIGHT, 1);
            int ready = bgfx_read_texture(staging, pixels, 0, 0);
            long deadline = System.nanoTime() + 10_000_000_000L;
            int frame;
            do {
                frame = bgfx_frame(BGFX_FRAME_NONE);
                if (System.nanoTime() > deadline) {
                    throw new IOException("Terrain readback timeout");
                }
            } while (Integer.compareUnsigned(frame, ready) < 0);
            int[] result = new int[WIDTH * HEIGHT];
            boolean invert = bgfx_get_caps().originBottomLeft();
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int at = (y * WIDTH + x) * 4;
                    result[(invert ? HEIGHT - 1 - y : y) * WIDTH + x] =
                            (pixels.get(at + 3) & 255) << 24
                                    | (pixels.get(at) & 255) << 16
                                    | (pixels.get(at + 1) & 255) << 8
                                    | pixels.get(at + 2) & 255;
                }
            }
            frames++;
            return result;
        }

        public int frames() {
            return frames;
        }

        private void requireOpen() {
            if (!initialized) {
                throw new IllegalStateException("Terrain harness closed");
            }
        }

        @Override
        public void close() {
            if (!initialized) {
                return;
            }
            for (var scene : List.copyOf(scenes)) {
                scene.close();
            }
            if (template != INVALID) {
                bgfx_destroy_vertex_buffer(template);
                template = INVALID;
            }
            if (program != INVALID) {
                bgfx_destroy_program(program);
                program = INVALID;
            }
            if (skyProgram != INVALID) {
                bgfx_destroy_program(skyProgram);
                skyProgram = INVALID;
            }
            if (eye != INVALID) {
                bgfx_destroy_uniform(eye);
                eye = INVALID;
            }
            if (projection != INVALID) {
                bgfx_destroy_uniform(projection);
                projection = INVALID;
            }
            if (mode != INVALID) {
                bgfx_destroy_uniform(mode);
                mode = INVALID;
            }
            if (target != INVALID) {
                bgfx_destroy_frame_buffer(target);
                target = INVALID;
            }
            if (color != INVALID) {
                bgfx_destroy_texture(color);
                color = INVALID;
            }
            if (depth != INVALID) {
                bgfx_destroy_texture(depth);
                depth = INVALID;
            }
            if (staging != INVALID) {
                bgfx_destroy_texture(staging);
                staging = INVALID;
            }
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
            bgfx_shutdown();
            initialized = false;
        }
    }

    private static void put(FloatBuffer target, Vertex value, Vertex origin, double base) {
        target.put((float) (value.x - origin.x))
                .put((float) (value.y - origin.y))
                .put((float) (value.z - origin.z))
                .put((float) base);
    }

    private static FloatBuffer eye(MemoryStack stack, Vertex origin, Camera camera) {
        double cy = Math.cos(camera.yaw),
                sy = Math.sin(camera.yaw),
                cp = Math.cos(camera.pitch),
                sp = Math.sin(camera.pitch);
        double x = origin.x - camera.x, y = origin.y - camera.y, z = origin.z - camera.z;
        return stack.mallocFloat(16)
                .put(
                        new float[] {
                            (float) -sy,
                            (float) (-sp * cy),
                            (float) (-cp * cy),
                            0,
                            (float) cy,
                            (float) (-sp * sy),
                            (float) (-cp * sy),
                            0,
                            0,
                            (float) cp,
                            (float) -sp,
                            0,
                            (float) (-sy * x + cy * y),
                            (float) (-sp * (cy * x + sy * y) + cp * z),
                            (float) (-cp * (cy * x + sy * y) - sp * z),
                            1
                        })
                .flip();
    }

    private static FloatBuffer projection(MemoryStack stack, boolean homogeneous) {
        return stack.mallocFloat(16)
                .put(
                        new float[] {
                            1.6f,
                            0,
                            0,
                            0,
                            0,
                            1.6f * WIDTH / HEIGHT,
                            0,
                            0,
                            0,
                            0,
                            homogeneous ? 1 : 0,
                            -1,
                            0,
                            0,
                            homogeneous ? 1 : .5f,
                            0
                        })
                .flip();
    }

    private static short template() throws IOException {
        FloatBuffer data = MemoryUtil.memAllocFloat(9);
        try (var layout = BGFXVertexLayout.calloc()) {
            data.put(new float[] {0, -1, -1, 1, 3, -1, 2, -1, 3}).flip();
            bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
            bgfx_vertex_layout_add(
                    layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
            bgfx_vertex_layout_end(layout);
            return valid(bgfx_create_vertex_buffer(bgfx_copy(data), layout, BGFX_BUFFER_NONE));
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static short program(String directory) throws IOException {
        short vertex = shader(directory, "vs_model"), fragment = INVALID;
        try {
            fragment = shader(directory, "fs_model");
            return valid(bgfx_create_program(vertex, fragment, false));
        } finally {
            bgfx_destroy_shader(vertex);
            if (fragment != INVALID) {
                bgfx_destroy_shader(fragment);
            }
        }
    }

    private static short shader(String directory, String name) throws IOException {
        String resource =
                "/com/vibeminer/rendering/bgfx/terrain-spike/"
                        + directory
                        + "spirv/"
                        + name
                        + ".bin";
        try (var stream = GpuTerrainSpike.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing spike shader " + resource);
            }
            byte[] bytes = stream.readNBytes((1 << 20) + 1);
            if (bytes.length > 1 << 20) {
                throw new IOException("Oversized spike shader");
            }
            ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
            try {
                return valid(bgfx_create_shader(bgfx_copy(data.put(bytes).flip())));
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Terrain spike native resource creation failed");
        }
        return handle;
    }

    private static void coordinate(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw new IllegalArgumentException("Invalid terrain coordinate");
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected authored output directory");
        }
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        try (var harness = new Harness()) {
            var empty = harness.render(List.of(), new Camera(0, 0, 0, 0, 0));
            if (empty[0] != 0xff5b8db1 || empty[128 * WIDTH + 128] != 0xff83abc3) {
                throw new IOException("Integer-row sky gradient proof failed");
            }
            var triangles =
                    List.of(
                            new Triangle(
                                    new Vertex(4, -2, -1),
                                    new Vertex(4, 2, -1),
                                    new Vertex(4, 0, 2),
                                    100,
                                    80,
                                    60));
            try (var scene = harness.upload(triangles)) {
                int[] first = harness.render(scene, new Camera(0, 0, 0, 0, 0));
                for (int value : first) {
                    if ((value >>> 24) != 255) {
                        throw new IOException("Terrain alpha must be opaque");
                    }
                }
                if (first[0] != 0xff5b8db1) {
                    throw new IOException(
                            "Terrain sky did not cover viewport: " + Integer.toHexString(first[0]));
                }
                int pixel = first[128 * WIDTH + 128];
                if (pixel != 0xff64503c) {
                    throw new IOException(
                            "48-byte triangle selector/color proof failed: "
                                    + Integer.toHexString(pixel));
                }
                harness.render(scene, new Camera(0, 0, 0, .1, .05));
                if (scene.uploadCount() != 1 || scene.uploadBytes() != 48) {
                    throw new IOException("Camera update reuploaded geometry");
                }
                var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, WIDTH, HEIGHT, first, 0, WIDTH);
                ImageIO.write(
                        image, "png", directory.resolve("terrain-instance-spike.png").toFile());
            }
            var caps = bgfx_get_caps();
            Files.writeString(
                    directory.resolve("terrain-instance-spike.json"),
                    "{\"renderer\":\""
                            + bgfx_get_renderer_name(caps.rendererType())
                            + "\",\"frames\":"
                            + harness.frames
                            + ",\"instanceStride\":48,\"depth\":\"D32F-reversed-infinite\"}\n");
            System.out.println("GPU terrain spike passed: " + harness.frames + " authored frames");
        }
    }
}
