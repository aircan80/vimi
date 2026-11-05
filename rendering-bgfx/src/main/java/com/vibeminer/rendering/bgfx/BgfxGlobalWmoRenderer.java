package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_INDEX32;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INDEX32;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
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
import static org.lwjgl.bgfx.BGFX.bgfx_copy;
import static org.lwjgl.bgfx.BGFX.bgfx_create_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_program;
import static org.lwjgl.bgfx.BGFX.bgfx_create_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_create_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_program;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;

import com.vibeminer.rendering.world.GlobalInteriorLimits;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Private immutable standalone global draw resources; no context, target, Source or GUI. */
final class BgfxGlobalWmoRenderer implements GlobalWmoNativeScene {
    private static final short INVALID = -1;
    private static final long DRAW_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_WRITE_A
                    | BGFX_STATE_WRITE_Z
                    | BGFX_STATE_DEPTH_TEST_GREATER;
    private static final int SAMPLER_STATE = BGFX_SAMPLER_MIP_POINT;
    private final Thread owner = Thread.currentThread();
    private final short[] textures = new short[GlobalInteriorLimits.IMAGES];
    private final int[] materialImages = new int[GlobalInteriorLimits.MATERIALS];
    private GpuGlobalWmoSceneFile.Decoded data;
    private final int vertexCount;
    private final GlobalWmoCamera initialCamera;
    private short vertices = INVALID, indices = INVALID, program = INVALID;
    private short eye = INVALID, projection = INVALID, sampler = INVALID;
    private long meshBytes, imageBytes;
    private int stagingBytes, peakMesh, peakImage;
    private boolean closed;

    /**
     * Adopts the actual private decoder output, including all failure paths; never Source proof.
     */
    static BgfxGlobalWmoRenderer upload(
            GpuGlobalWmoSceneFile.Decoded data, BooleanSupplier canceled) throws IOException {
        Objects.requireNonNull(data);
        try {
            return new BgfxGlobalWmoRenderer(data, Objects.requireNonNull(canceled));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                data.close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private BgfxGlobalWmoRenderer(GpuGlobalWmoSceneFile.Decoded data, BooleanSupplier canceled)
            throws IOException {
        this.data = data;
        vertexCount = data.counts().vertices();
        Arrays.fill(textures, INVALID);
        Arrays.fill(materialImages, -1);
        initialCamera = boundsCamera(data);
        try {
            check(canceled);
            var caps = bgfx_get_caps();
            if (caps.homogeneousDepth()
                    || (caps.supported() & BGFX_CAPS_INDEX32) == 0
                    || !bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT)
                    || !bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_NONE)) {
                throw new GlobalWmoCapabilityException(
                        "Global requires reversed D32F, RGBA8 and 32-bit indices");
            }
            program = program(BgfxModelRenderer.shaderProfile(caps.rendererType()));
            eye = valid(bgfx_create_uniform("u_globalEye", BGFX_UNIFORM_TYPE_MAT4, 1));
            projection =
                    valid(bgfx_create_uniform("u_globalProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
            sampler = valid(bgfx_create_uniform("s_globalDiffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            uploadMesh(canceled);
            uploadImages(canceled);
            for (int m = 0; m < data.counts().materials(); m++) {
                long id = data.description().materials().get(m).words()[3];
                for (int i = 0; i < data.counts().images(); i++) {
                    if (data.description().images().get(i).id() == id) {
                        materialImages[m] = i;
                    }
                }
            }
            for (var batch : data.batches()) {
                if (materialImages[batch.material()] < 0) {
                    throw new IOException("Global material image absent");
                }
            }
            check(canceled);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static GlobalWmoCamera boundsCamera(GpuGlobalWmoSceneFile.Decoded data) {
        float[] min = {4096, 4096, 4096}, max = {-4096, -4096, -4096};
        for (int v = 0; v < data.counts().vertices(); v++) {
            for (int c = 0; c < 3; c++) {
                float value = data.vertex(v, c);
                min[c] = Math.min(min[c], value);
                max[c] = Math.max(max[c], value);
            }
        }
        return GlobalWmoCamera.initial(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    GlobalWmoCamera initialCamera() {
        requireOpen();
        return initialCamera;
    }

    private void uploadMesh(BooleanSupplier canceled) throws IOException {
        int indexCount = data.counts().indices();
        int size = vertexCount * 20 + indexCount * WorldGlobalLayout.INDEX_BYTES;
        if (size > GPU_MESH) {
            throw new IOException("Global mesh cap");
        }
        var staging = MemoryUtil.memAlloc(size).order(ByteOrder.nativeOrder());
        stagingBytes = size;
        peakMesh = size;
        try {
            for (int v = 0; v < vertexCount; v++) {
                if ((v & 255) == 0) {
                    check(canceled);
                }
                for (int c = 0; c < 5; c++) {
                    staging.putFloat(data.vertex(v, c));
                }
            }
            for (int i = 0; i < indexCount; i++) {
                if ((i & 1023) == 0) {
                    check(canceled);
                }
                staging.putInt(data.index(i));
            }
            staging.flip();
            check(canceled);
            try (var layout = BGFXVertexLayout.calloc()) {
                bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_end(layout);
                if (layout.stride() != 20) {
                    throw new IOException("Global vertex layout differs");
                }
                meshBytes = size;
                vertices =
                        valid(
                                bgfx_create_vertex_buffer(
                                        bgfx_copy(staging.slice(0, vertexCount * 20)),
                                        layout,
                                        BGFX_BUFFER_NONE));
                check(canceled);
                indices =
                        valid(
                                bgfx_create_index_buffer(
                                        bgfx_copy(
                                                staging.slice(
                                                        vertexCount * 20,
                                                        indexCount
                                                                * WorldGlobalLayout.INDEX_BYTES)),
                                        BGFX_BUFFER_INDEX32));
            }
        } finally {
            stagingBytes = 0;
            MemoryUtil.memFree(staging);
        }
    }

    private void uploadImages(BooleanSupplier canceled) throws IOException {
        var staging = MemoryUtil.memAlloc((int) DIRECT_IMAGE);
        stagingBytes = (int) DIRECT_IMAGE;
        peakImage = stagingBytes;
        try {
            for (int image = 0; image < data.counts().images(); image++) {
                check(canceled);
                int bytes = data.imageBytes(image);
                if (bytes > DIRECT_IMAGE || imageBytes + bytes > GPU_RGBA) {
                    throw new IOException("Global image cap");
                }
                staging.clear().limit(bytes);
                for (int i = 0; i < bytes; i++) {
                    if ((i & 4095) == 0) {
                        check(canceled);
                    }
                    staging.put((byte) data.imageByte(image, i));
                }
                staging.flip();
                check(canceled);
                var description = data.description().images().get(image);
                imageBytes += bytes;
                textures[image] =
                        valid(
                                bgfx_create_texture_2d(
                                        description.width(),
                                        description.height(),
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_NONE,
                                        bgfx_copy(staging),
                                        0));
            }
        } finally {
            stagingBytes = 0;
            MemoryUtil.memFree(staging);
        }
    }

    @Override
    public void draw(GlobalWmoCamera camera, int width, int height) {
        requireOpen();
        Objects.requireNonNull(camera);
        var projectionMatrix = GlobalWmoCamera.projection(width, height);
        try (var stack = MemoryStack.stackPush()) {
            var e = stack.mallocFloat(16).put(camera.modelToEye()).flip();
            var p = stack.mallocFloat(16).put(projectionMatrix).flip();
            for (var batch : data.batches()) {
                bgfx_set_uniform(eye, e, 1);
                bgfx_set_uniform(projection, p, 1);
                bgfx_set_vertex_buffer(0, vertices, 0, vertexCount);
                bgfx_set_index_buffer(indices, batch.firstIndex(), batch.indexCount());
                bgfx_set_texture(
                        0, sampler, textures[materialImages[batch.material()]], SAMPLER_STATE);
                bgfx_set_state(DRAW_STATE, 0);
                bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
            }
        }
    }

    @Override
    public Stats stats() {
        requireOwner();
        return new Stats(
                meshBytes,
                imageBytes,
                meshBytes,
                imageBytes,
                stagingBytes,
                peakMesh,
                peakImage,
                data == null ? 0 : data.counts().images(),
                data == null ? 0 : data.counts().batches());
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Global native thread differs");
        }
    }

    private void requireOpen() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("Global scene closed");
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        short v = vertices, i = indices, p = program;
        vertices = indices = program = INVALID;
        failure =
                destroy(
                        failure,
                        () -> {
                            if (v != INVALID) {
                                bgfx_destroy_vertex_buffer(v);
                            }
                        });
        failure =
                destroy(
                        failure,
                        () -> {
                            if (i != INVALID) {
                                bgfx_destroy_index_buffer(i);
                            }
                        });
        for (int n = 0; n < textures.length; n++) {
            short t = textures[n];
            textures[n] = INVALID;
            failure =
                    destroy(
                            failure,
                            () -> {
                                if (t != INVALID) {
                                    bgfx_destroy_texture(t);
                                }
                            });
        }
        failure =
                destroy(
                        failure,
                        () -> {
                            if (p != INVALID) {
                                bgfx_destroy_program(p);
                            }
                        });
        for (short u : new short[] {eye, projection, sampler}) {
            failure =
                    destroy(
                            failure,
                            () -> {
                                if (u != INVALID) {
                                    bgfx_destroy_uniform(u);
                                }
                            });
        }
        eye = projection = sampler = INVALID;
        var decoded = data;
        data = null;
        failure =
                destroy(
                        failure,
                        () -> {
                            if (decoded != null) {
                                decoded.close();
                            }
                        });
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
    }

    private static Throwable destroy(Throwable prior, Runnable destroy) {
        try {
            destroy.run();
        } catch (RuntimeException | Error failure) {
            if (prior == null) {
                return failure;
            }
            if (failure != prior) {
                prior.addSuppressed(failure);
            }
        }
        return prior;
    }

    private static short program(String profile) throws IOException {
        short vertex = INVALID, fragment = INVALID, result = INVALID;
        Throwable failure = null;
        try {
            vertex = shader(profile, "vs_model");
            fragment = shader(profile, "fs_model");
            result = valid(bgfx_create_program(vertex, fragment, false));
        } catch (IOException | RuntimeException | Error problem) {
            failure = problem;
        }
        short v = vertex, f = fragment;
        failure =
                destroy(
                        failure,
                        () -> {
                            if (v != INVALID) {
                                bgfx_destroy_shader(v);
                            }
                        });
        failure =
                destroy(
                        failure,
                        () -> {
                            if (f != INVALID) {
                                bgfx_destroy_shader(f);
                            }
                        });
        if (failure != null) {
            short p = result;
            failure =
                    destroy(
                            failure,
                            () -> {
                                if (p != INVALID) {
                                    bgfx_destroy_program(p);
                                }
                            });
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) failure;
        }
        return result;
    }

    private static short shader(String profile, String name) throws IOException {
        if (!List.of("glsl", "spirv", "metal", "dx11").contains(profile)) {
            throw new IOException("Global candidate profile unqualified");
        }
        String path =
                "/com/vibeminer/rendering/bgfx/shaders/global-candidate/"
                        + profile
                        + "/"
                        + name
                        + ".bin";
        byte[] bytes;
        String expected;
        try (var in = BgfxGlobalWmoRenderer.class.getResourceAsStream(path);
                var digest = BgfxGlobalWmoRenderer.class.getResourceAsStream(path + ".sha256")) {
            if (in == null || digest == null) {
                throw new IOException("Global candidate shaders not installed");
            }
            bytes = in.readNBytes(16385);
            byte[] text = digest.readNBytes(66);
            expected = new String(text, java.nio.charset.StandardCharsets.US_ASCII);
            if (bytes.length < 4 || bytes.length > 16384 || !expected.matches("[0-9a-f]{64}\n")) {
                throw new IOException("Global shader bounds");
            }
        }
        try {
            String actual =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
            if (!actual.equals(expected.strip())) {
                throw new IOException("Global shader digest differs");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        var staging = MemoryUtil.memAlloc(bytes.length);
        try {
            return valid(bgfx_create_shader(bgfx_copy(staging.put(bytes).flip())));
        } finally {
            MemoryUtil.memFree(staging);
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Global native resource unavailable");
        }
        return handle;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Global upload canceled");
        }
    }
}
