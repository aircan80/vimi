package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
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

import com.vibeminer.rendering.world.BasicWmoData;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One immutable cold-world WMO draw slot. No camera/context/target/clear, source lookup or hot
 * path. Closing issues destruction; the enclosing native owner still owes its actual GPU/context
 * drain.
 */
final class BgfxWorldWmoPass implements AutoCloseable {
    static final int VERTEX_BYTES = 32, MAX_MESH_BYTES = 19456, MAX_TEXTURE_BYTES = 1572864;
    static final long DRAW_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_WRITE_A
                    | BGFX_STATE_WRITE_Z
                    | BGFX_STATE_DEPTH_TEST_GREATER;
    // Zero min/mag/U/V flags explicitly mean linear filtering/repeat, one physical mip only.
    static final int SAMPLER_STATE = BGFX_SAMPLER_MIP_POINT;
    private static final short INVALID = -1;
    private final Thread owner = Thread.currentThread();
    private final TerrainScene.Origin origin;
    private final BasicWmoData.Metadata metadata;
    private final FloatBuffer modelTransform, normalTransform;
    private final int vertexCount;
    private final short[] textures = new short[6];
    private GpuWorldWmoAnnex.Decoded data;
    private List<BasicWmoData.Batch> batches = List.of();
    private int[] materialImages = new int[0];
    private short vertices = INVALID, indices = INVALID, program = INVALID;
    private short eye = INVALID,
            projection = INVALID,
            model = INVALID,
            normal = INVALID,
            sampler = INVALID;
    private long meshBytes, textureBytes;
    private int liveStaging, peakMeshStaging, peakTextureStaging;
    private boolean closed;

    /** Actual annex/base pairing completes before native allocation; no neutral DTO constructor. */
    static BgfxWorldWmoPass readBound(
            Path annex, GpuTerrainSceneFile.Decoded base, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(base);
        Objects.requireNonNull(canceled);
        var decoded = GpuWorldWmoAnnex.read(annex, base, canceled);
        return new BgfxWorldWmoPass(decoded, base.scene().origin(), canceled);
    }

    private BgfxWorldWmoPass(
            GpuWorldWmoAnnex.Decoded decoded, TerrainScene.Origin origin, BooleanSupplier canceled)
            throws IOException {
        this.data = decoded;
        this.metadata = decoded.metadata();
        this.origin = origin;
        modelTransform = GpuWorldWmoAnnex.modelToOrigin(metadata);
        normalTransform = normalMatrix(metadata.placement());
        vertexCount = decoded.positions().remaining() / 3;
        Arrays.fill(textures, INVALID);
        try {
            check(canceled);
            var caps = bgfx_get_caps();
            if (caps.homogeneousDepth()
                    || !bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT)
                    || !bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_NONE)) {
                throw new IOException("WMO requires qualified zero-to-one reversed D32F and RGBA8");
            }
            program = program(BgfxModelRenderer.shaderProfile(caps.rendererType()));
            eye = valid(bgfx_create_uniform("u_wmoEye", BGFX_UNIFORM_TYPE_MAT4, 1));
            projection = valid(bgfx_create_uniform("u_wmoProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
            model = valid(bgfx_create_uniform("u_wmoModel", BGFX_UNIFORM_TYPE_MAT4, 1));
            normal = valid(bgfx_create_uniform("u_wmoNormal", BGFX_UNIFORM_TYPE_MAT4, 1));
            sampler = valid(bgfx_create_uniform("s_wmoDiffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            uploadMesh(canceled);
            uploadTextures(canceled);
            batches = decoded.batches();
            materialImages = new int[decoded.materialCount()];
            for (int m = 0; m < materialImages.length; m++) {
                int found = -1;
                for (int image = 0; image < decoded.imageCount(); image++) {
                    if (decoded.materialWord(m, 3) == decoded.image(image).id()) {
                        found = image;
                    }
                }
                if (found < 0) {
                    throw new IOException("WMO material/image binding differs");
                }
                materialImages[m] = found;
            }
            check(canceled);
        } catch (IOException | RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void uploadMesh(BooleanSupplier canceled) throws IOException {
        int count = vertexCount, indexCount = data.indices().remaining();
        int bytes = count * VERTEX_BYTES + indexCount * 2;
        if (bytes > MAX_MESH_BYTES) {
            throw new IOException("WMO mesh upload cap");
        }
        ByteBuffer staging = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
        liveStaging += bytes;
        peakMeshStaging = Math.max(peakMeshStaging, liveStaging);
        try {
            var p = data.positions();
            var n = data.normals();
            var uv = data.uv();
            for (int vertex = 0; vertex < count; vertex++) {
                check(canceled);
                for (int i = 0; i < 3; i++) {
                    staging.putFloat(p.get());
                }
                for (int i = 0; i < 3; i++) {
                    staging.putFloat(n.get());
                }
                staging.putFloat(uv.get()).putFloat(uv.get());
            }
            var sourceIndices = data.indices();
            while (sourceIndices.hasRemaining()) {
                staging.putShort((short) sourceIndices.get());
            }
            staging.flip();
            check(canceled);
            try (var layout = BGFXVertexLayout.calloc()) {
                bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_end(layout);
                if (layout.stride() != VERTEX_BYTES) {
                    throw new IOException("WMO vertex layout differs");
                }
                meshBytes = bytes;
                vertices =
                        valid(
                                bgfx_create_vertex_buffer(
                                        bgfx_copy(staging.slice(0, count * VERTEX_BYTES)),
                                        layout,
                                        BGFX_BUFFER_NONE));
                indices =
                        valid(
                                bgfx_create_index_buffer(
                                        bgfx_copy(
                                                staging.slice(
                                                        count * VERTEX_BYTES, indexCount * 2)),
                                        BGFX_BUFFER_NONE));
            }
        } finally {
            liveStaging -= bytes;
            MemoryUtil.memFree(staging);
        }
    }

    private void uploadTextures(BooleanSupplier canceled) throws IOException {
        for (int i = 0; i < data.imageCount(); i++) {
            check(canceled);
            var image = data.image(i);
            int bytes = image.rgba().remaining();
            if (bytes > 262144 || textureBytes + bytes > MAX_TEXTURE_BYTES) {
                throw new IOException("WMO texture upload cap");
            }
            ByteBuffer staging = MemoryUtil.memAlloc(bytes);
            liveStaging += bytes;
            peakTextureStaging = Math.max(peakTextureStaging, liveStaging);
            try {
                staging.put(image.rgba()).flip();
                check(canceled);
                textureBytes += bytes;
                textures[i] =
                        valid(
                                bgfx_create_texture_2d(
                                        image.width(),
                                        image.height(),
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_NONE,
                                        bgfx_copy(staging),
                                        0));

            } finally {
                liveStaging -= bytes;
                MemoryUtil.memFree(staging);
            }
        }
    }

    /**
     * Called after terrain on already configured SEQUENTIAL view0, with that exact frame camera.
     */
    void draw(TerrainCamera camera, int width, int height) {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("WMO pass closed");
        }
        if (width < 1 || height < 1 || width > 800 || height > 500) {
            throw new IllegalArgumentException("WMO frame exceeds world budget");
        }
        Objects.requireNonNull(camera);
        try (var stack = MemoryStack.stackPush()) {
            var e = stack.mallocFloat(16).put(camera.modelToEye(origin)).flip();
            var p =
                    stack.mallocFloat(16)
                            .put(TerrainCamera.reversedProjection(width, height))
                            .flip();
            var m = stack.mallocFloat(16).put(modelTransform.asReadOnlyBuffer()).flip();
            var n = stack.mallocFloat(16).put(normalTransform.asReadOnlyBuffer()).flip();
            for (var batch : batches) {
                bgfx_set_uniform(eye, e, 1);
                bgfx_set_uniform(projection, p, 1);
                bgfx_set_uniform(model, m, 1);
                bgfx_set_uniform(normal, n, 1);
                bgfx_set_vertex_buffer(0, vertices, 0, vertexCount);
                bgfx_set_index_buffer(indices, batch.firstIndex(), batch.indexCount());
                bgfx_set_texture(
                        0, sampler, textures[materialImages[batch.material()]], SAMPLER_STATE);
                bgfx_set_state(DRAW_STATE, 0);
                bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
            }
        }
    }

    static FloatBuffer normalMatrix(BasicWmoData.Placement p) {
        float co = (float) p.cosine(), si = (float) p.sine();
        return FloatBuffer.wrap(new float[] {-co, -si, 0, 0, si, -co, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1})
                .asReadOnlyBuffer();
    }

    long issuedMeshBytes() {
        return meshBytes;
    }

    long issuedTextureBytes() {
        return textureBytes;
    }

    int liveStagingBytes() {
        return liveStaging;
    }

    int peakMeshStagingBytes() {
        return peakMeshStaging;
    }

    int peakTextureStagingBytes() {
        return peakTextureStaging;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("WMO resources belong to native owner");
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        if (vertices != INVALID) {
            bgfx_destroy_vertex_buffer(vertices);
            vertices = INVALID;
        }
        if (indices != INVALID) {
            bgfx_destroy_index_buffer(indices);
            indices = INVALID;
        }
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] != INVALID) {
                bgfx_destroy_texture(textures[i]);
                textures[i] = INVALID;
            }
        }
        if (program != INVALID) {
            bgfx_destroy_program(program);
            program = INVALID;
        }
        for (short u : new short[] {eye, projection, model, normal, sampler}) {
            if (u != INVALID) {
                bgfx_destroy_uniform(u);
            }
        }
        eye = projection = model = normal = sampler = INVALID;
        data = null;
        batches = List.of();
        materialImages = new int[0];
        // Issued byte totals deliberately remain charged: close is not a GPU drain certificate.
    }

    private static short program(String profile) throws IOException {
        short vertex = INVALID, fragment = INVALID;
        try {
            vertex = shader(profile, "vs_model");
            fragment = shader(profile, "fs_model");
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

    private static short shader(String profile, String name) throws IOException {
        String path =
                "/com/vibeminer/rendering/bgfx/shaders/world-wmo/" + profile + "/" + name + ".bin";
        try (var input = BgfxWorldWmoPass.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("WMO shader profile unavailable");
            }
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length < 4 || bytes.length > 16384) {
                throw new IOException("WMO shader reserve exceeded");
            }
            var raw = MemoryUtil.memAlloc(bytes.length);
            try {
                return valid(bgfx_create_shader(bgfx_copy(raw.put(bytes).flip())));
            } finally {
                MemoryUtil.memFree(raw);
            }
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("WMO native resource creation failed");
        }
        return handle;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("WMO upload canceled");
        }
    }
}
