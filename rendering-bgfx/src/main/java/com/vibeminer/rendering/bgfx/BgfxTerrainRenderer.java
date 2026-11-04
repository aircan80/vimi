package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD2;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INSTANCING;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_GREATER;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_MAT4;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_copy;
import static org.lwjgl.bgfx.BGFX.bgfx_create_program;
import static org.lwjgl.bgfx.BGFX.bgfx_create_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_create_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_program;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Context-owned optionally textured terrain. Upload/close/render run on the initializing native
 * thread.
 */
final class BgfxTerrainRenderer implements AutoCloseable {
    static final int MAX_WIDTH = 800, MAX_HEIGHT = 500;
    static final int INSTANCE_BYTES = 48;
    static final long MAX_INSTANCE_BYTES = (long) TerrainScene.MAX_TRIANGLES * INSTANCE_BYTES;
    private static final short INVALID = -1;
    private final Thread owner = Thread.currentThread();
    private final TerrainScene scene;
    private final List<Draw> draws = new ArrayList<>();
    private short template = INVALID, terrainProgram = INVALID, skyProgram = INVALID;
    private short eyeUniform = INVALID, projectionUniform = INVALID, skyUniform = INVALID;
    private long uploadedBytes;
    private boolean closed;
    private short sampler = INVALID, atlasUniform = INVALID, dummy = INVALID;
    private final short[] textures = new short[9];
    private final TerrainTextureBinding.Metadata[] textureMetadata =
            new TerrainTextureBinding.Metadata[9];
    private int activeAtlases, coveredRectangles;
    private int textureStagingBytes, textureStagingPeakBytes;
    private boolean textureUploadFailed, textureCapabilityFailed;
    private static final int SAMPLER_FLAGS =
            BGFX_SAMPLER_MIN_POINT
                    | BGFX_SAMPLER_MAG_POINT
                    | BGFX_SAMPLER_MIP_POINT
                    | BGFX_SAMPLER_U_CLAMP
                    | BGFX_SAMPLER_V_CLAMP;

    private record Draw(short instances, int triangles, int tileOrdinal) {}

    BgfxTerrainRenderer(TerrainScene scene) throws IOException {
        this(scene, () -> false);
    }

    BgfxTerrainRenderer(TerrainScene scene, BooleanSupplier canceled) throws IOException {
        this(scene, null, canceled);
    }

    BgfxTerrainRenderer(
            TerrainScene scene, GpuTerrainTextureAnnex.Decoded annex, BooleanSupplier canceled)
            throws IOException {
        this.scene = Objects.requireNonNull(scene);
        java.util.Arrays.fill(textures, INVALID);
        Objects.requireNonNull(canceled);
        try {
            check(canceled);
            var caps = bgfx_get_caps();
            requireCapabilities(
                    caps.supported(),
                    caps.homogeneousDepth(),
                    bgfx_is_texture_valid(0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT));
            String profile = BgfxModelRenderer.shaderProfile(caps.rendererType());
            terrainProgram = program(profile, "");
            skyProgram = program(profile, "sky/");
            eyeUniform = valid(bgfx_create_uniform("u_terrainEye", BGFX_UNIFORM_TYPE_MAT4, 1));
            projectionUniform =
                    valid(bgfx_create_uniform("u_terrainProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
            skyUniform = valid(bgfx_create_uniform("u_terrainMode", BGFX_UNIFORM_TYPE_VEC4, 1));
            sampler = valid(bgfx_create_uniform("s_terrainAtlas", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            atlasUniform = valid(bgfx_create_uniform("u_terrainAtlas", BGFX_UNIFORM_TYPE_VEC4, 1));
            try (var stack = MemoryStack.stackPush()) {
                var pixel = stack.calloc(4);
                dummy =
                        valid(
                                bgfx_create_texture_2d(
                                        1,
                                        1,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_NONE,
                                        bgfx_copy(pixel),
                                        0));
            }
            template = template();
            int ordinal = 0;
            for (var tile : scene.tiles()) {
                check(canceled);
                if (tile.triangleCount() != 0) {
                    upload(tile, ordinal, canceled);
                }
                ordinal++;
            }
            if (annex != null) {
                uploadAtlases(annex, canceled);
            }
            check(canceled);
        } catch (IOException | RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static void requireCapabilities(long supported, boolean homogeneousDepth, boolean d32fSupported)
            throws IOException {
        if ((supported & BGFX_CAPS_INSTANCING) == 0 || homogeneousDepth || !d32fSupported) {
            throw new IOException(
                    "Terrain requires instancing and qualified zero-to-one reversed D32F depth");
        }
    }

    int triangleCount() {
        return scene.triangleCount();
    }

    int tileBufferCount() {
        return draws.size();
    }

    long uploadedBytes() {
        return uploadedBytes;
    }

    private void upload(TerrainScene.Tile tile, int ordinal, BooleanSupplier canceled)
            throws IOException {
        long bytes = (long) tile.triangleCount() * INSTANCE_BYTES;
        if (bytes > (3L << 20) || uploadedBytes + bytes > MAX_INSTANCE_BYTES) {
            throw new IOException("Terrain upload budget exceeded");
        }
        var vertices = tile.vertices();
        var indices = tile.indices();
        FloatBuffer packed = MemoryUtil.memAllocFloat(Math.toIntExact(bytes / Float.BYTES));
        try {
            var origin = scene.origin();
            for (int at = 0; at < tile.indexCount(); at += 3) {
                if ((at & 4095) == 0) {
                    check(canceled);
                }
                int a = indices.get(at) * 3,
                        b = indices.get(at + 1) * 3,
                        c = indices.get(at + 2) * 3;
                float ax = vertices.get(a), ay = vertices.get(a + 1), az = vertices.get(a + 2);
                float bx = vertices.get(b), by = vertices.get(b + 1), bz = vertices.get(b + 2);
                float cx = vertices.get(c), cy = vertices.get(c + 1), cz = vertices.get(c + 2);
                double ux = bx - ax,
                        uy = by - ay,
                        uz = bz - az,
                        vx = cx - ax,
                        vy = cy - ay,
                        vz = cz - az;
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double light =
                        length == 0
                                ? .7
                                : .45 + .55 * Math.abs((nx * .3 + ny * .4 + nz * .866) / length);
                double altitude = (az + bz + cz) / 3.0,
                        rock = Math.clamp((altitude - 150) / 600, 0, 1);
                corner(packed, origin, ax, ay, az, (72 + rock * 65) * light);
                corner(packed, origin, bx, by, bz, (116 + rock * 10) * light);
                corner(packed, origin, cx, cy, cz, (68 + rock * 57) * light);
            }
            packed.flip();
            check(canceled);
            try (var layout = BGFXVertexLayout.calloc()) {
                bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_TEXCOORD0, 4, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_TEXCOORD1, 4, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_TEXCOORD2, 4, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_end(layout);
                if (layout.stride() != INSTANCE_BYTES) {
                    throw new IOException("Terrain instance layout differs");
                }
                short instances =
                        valid(
                                bgfx_create_vertex_buffer(
                                        bgfx_copy(packed), layout, BGFX_BUFFER_NONE));
                try {
                    draws.add(new Draw(instances, tile.triangleCount(), ordinal));
                } catch (RuntimeException | Error failure) {
                    bgfx_destroy_vertex_buffer(instances);
                    throw failure;
                }
                uploadedBytes += bytes;
            }
        } finally {
            MemoryUtil.memFree(packed);
        }
    }

    void render(TerrainCamera camera, int width, int height) {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("Terrain renderer closed");
        }
        if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("Terrain frame exceeds 800x500 resource budget");
        }
        Objects.requireNonNull(camera);
        bgfx_set_view_rect(0, 0, 0, width, height);
        bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 0, 0);
        try (var stack = MemoryStack.stackPush()) {
            var eye = stack.mallocFloat(16).put(camera.modelToEye(scene.origin())).flip();
            var projection =
                    stack.mallocFloat(16)
                            .put(TerrainCamera.reversedProjection(width, height))
                            .flip();
            var sky =
                    stack.mallocFloat(4)
                            .put(
                                    new float[] {
                                        0, height, bgfx_get_caps().originBottomLeft() ? 1 : 0, 0
                                    })
                            .flip();
            bgfx_set_uniform(skyUniform, sky, 1);
            bgfx_set_vertex_buffer(0, template, 0, 3);
            bgfx_set_state(
                    BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_DEPTH_TEST_ALWAYS, 0);
            bgfx_submit(0, skyProgram, 0, BGFX_DISCARD_ALL);
            for (var draw : draws) {
                var meta = textureMetadata[draw.tileOrdinal()];
                var atlasParameters = stack.mallocFloat(4);
                if (meta == null) {
                    atlasParameters.put(new float[] {0, 0, 3f / 1600, 0}).flip();
                } else {
                    atlasParameters
                            .put(
                                    new float[] {
                                        (float) (meta.nativeMaxX() - scene.origin().x()),
                                        (float) (meta.nativeMaxY() - scene.origin().y()),
                                        3f / 1600,
                                        1
                                    })
                            .flip();
                }
                bgfx_set_uniform(atlasUniform, atlasParameters, 1);
                bgfx_set_texture(
                        0,
                        sampler,
                        meta == null ? dummy : textures[draw.tileOrdinal()],
                        SAMPLER_FLAGS);
                bgfx_set_uniform(eyeUniform, eye, 1);
                bgfx_set_uniform(projectionUniform, projection, 1);
                bgfx_set_vertex_buffer(0, template, 0, 3);
                bgfx_set_instance_data_from_vertex_buffer(draw.instances, 0, draw.triangles);
                bgfx_set_state(
                        BGFX_STATE_WRITE_RGB
                                | BGFX_STATE_WRITE_A
                                | BGFX_STATE_WRITE_Z
                                | BGFX_STATE_DEPTH_TEST_GREATER,
                        0);
                bgfx_submit(0, terrainProgram, 0, BGFX_DISCARD_ALL);
            }
        }
    }

    TerrainTextureStatus textureStatus(TerrainTextureStatus.Code inputFailure) {
        var code =
                inputFailure != TerrainTextureStatus.Code.NONE
                        ? inputFailure
                        : textureCapabilityFailed
                                ? TerrainTextureStatus.Code.CAPABILITY
                                : textureUploadFailed
                                        ? TerrainTextureStatus.Code.UPLOAD
                                        : TerrainTextureStatus.Code.NONE;
        return TerrainTextureStatus.of(
                activeAtlases, scene.tiles().size(), coveredRectangles, code);
    }

    int textureStagingBytes() {
        return textureStagingBytes;
    }

    int textureStagingPeakBytes() {
        return textureStagingPeakBytes;
    }

    private void uploadAtlases(GpuTerrainTextureAnnex.Decoded annex, BooleanSupplier canceled) {
        if (!bgfx_is_texture_valid(0, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_NONE)) {
            textureCapabilityFailed = true;
            return;
        }
        for (var binding : annex.bindings()) {
            check(canceled);
            short handle = INVALID;
            ByteBuffer staging = MemoryUtil.memAlloc(TerrainTextureBinding.PIXEL_BYTES);
            textureStagingBytes += staging.capacity();
            textureStagingPeakBytes = Math.max(textureStagingPeakBytes, textureStagingBytes);
            try {
                staging.put(binding.pixels()).flip();
                check(canceled);
                handle =
                        bgfx_create_texture_2d(
                                512,
                                512,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_RGBA8,
                                BGFX_TEXTURE_NONE,
                                bgfx_copy(staging),
                                0);
                if (handle == INVALID) {
                    textureUploadFailed = true;
                    continue;
                }
                int ordinal = binding.metadata().tileOrdinal();
                textures[ordinal] = handle;
                textureMetadata[ordinal] = binding.metadata();
                handle = INVALID;
                activeAtlases++;
                var coverage = binding.metadata().rectangleCoverage();
                while (coverage.hasRemaining()) {
                    coveredRectangles += Long.bitCount(coverage.get());
                }
            } finally {
                if (handle != INVALID) {
                    bgfx_destroy_texture(handle);
                }
                textureStagingBytes -= staging.capacity();
                MemoryUtil.memFree(staging);
            }
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "Terrain resources belong to the native renderer thread");
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        for (var draw : draws) {
            bgfx_destroy_vertex_buffer(draw.instances);
        }
        draws.clear();
        if (template != INVALID) {
            bgfx_destroy_vertex_buffer(template);
            template = INVALID;
        }
        if (terrainProgram != INVALID) {
            bgfx_destroy_program(terrainProgram);
            terrainProgram = INVALID;
        }
        if (skyProgram != INVALID) {
            bgfx_destroy_program(skyProgram);
            skyProgram = INVALID;
        }
        for (short texture : textures) {
            if (texture != INVALID) {
                bgfx_destroy_texture(texture);
            }
        }
        java.util.Arrays.fill(textures, INVALID);
        if (dummy != INVALID) {
            bgfx_destroy_texture(dummy);
            dummy = INVALID;
        }
        for (short uniform :
                new short[] {eyeUniform, projectionUniform, skyUniform, sampler, atlasUniform}) {
            if (uniform != INVALID) {
                bgfx_destroy_uniform(uniform);
            }
        }
        eyeUniform = projectionUniform = skyUniform = sampler = atlasUniform = INVALID;
    }

    private static void corner(
            FloatBuffer output,
            TerrainScene.Origin origin,
            double x,
            double y,
            double z,
            double base) {
        output.put((float) (x - origin.x()))
                .put((float) (y - origin.y()))
                .put((float) (z - origin.z()))
                .put((float) base);
    }

    private static short template() throws IOException {
        var data = MemoryUtil.memAllocFloat(9);
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

    private static short program(String profile, String directory) throws IOException {
        short vertex = INVALID, fragment = INVALID;
        try {
            vertex = shader(profile, directory, "vs_model");
            fragment = shader(profile, directory, "fs_model");
            return valid(bgfx_create_program(vertex, fragment, false));
        } finally {
            if (vertex != INVALID) {
                bgfx_destroy_shader(vertex);
            }
            if (fragment != INVALID) {
                bgfx_destroy_shader(fragment);
            }
        }
    }

    private static short shader(String profile, String directory, String name) throws IOException {
        String path =
                "/com/vibeminer/rendering/bgfx/shaders/terrain/"
                        + directory
                        + profile
                        + "/"
                        + name
                        + ".bin";
        try (var input = BgfxTerrainRenderer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Terrain shader unavailable: " + profile);
            }
            byte[] bytes = input.readNBytes((1 << 20) + 1);
            if (bytes.length < 4 || bytes.length > 1 << 20) {
                throw new IOException("Terrain shader size invalid");
            }
            ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
            try {
                return valid(bgfx_create_shader(bgfx_copy(raw.put(bytes).flip())));
            } finally {
                MemoryUtil.memFree(raw);
            }
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Terrain native resource creation failed");
        }
        return handle;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Terrain upload canceled");
        }
    }
}
