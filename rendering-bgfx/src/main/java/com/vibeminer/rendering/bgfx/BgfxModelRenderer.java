package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_OPENGL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LESS;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_PT_LINES;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_MAT4;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
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
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_type;
import static org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;

import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Context-owned immutable GPU uploads, presented through the bounded inline frame bridge. */
final class BgfxModelRenderer implements AutoCloseable {
    private static final short INVALID = (short) 0xffff;
    private final GpuModelScene scene;
    private short vertices = INVALID, indices = INVALID, edges = INVALID, program = INVALID;
    private short matrixUniform = INVALID, lightUniform = INVALID, materialUniform = INVALID;
    private short sampler = INVALID, white = INVALID;
    private final List<Short> textures = new ArrayList<>();
    private boolean closed;

    BgfxModelRenderer(GpuModelScene scene) throws IOException {
        this.scene = scene;
        try {
            int renderer = bgfx_get_renderer_type();
            String profile = shaderProfile(renderer);
            program = program(profile);
            matrixUniform = valid(bgfx_create_uniform("u_modelToClip", BGFX_UNIFORM_TYPE_MAT4, 1));
            lightUniform =
                    valid(bgfx_create_uniform("u_lightDirection", BGFX_UNIFORM_TYPE_VEC4, 1));
            materialUniform = valid(bgfx_create_uniform("u_material", BGFX_UNIFORM_TYPE_VEC4, 1));
            sampler = valid(bgfx_create_uniform("s_diffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            try (var layout = BGFXVertexLayout.calloc()) {
                bgfx_vertex_layout_begin(layout, renderer);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_add(
                        layout, BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT, false, false);
                bgfx_vertex_layout_end(layout);
                FloatBuffer raw = MemoryUtil.memAllocFloat(scene.vertexData().remaining());
                try {
                    raw.put(scene.vertexData()).flip();
                    vertices =
                            valid(
                                    bgfx_create_vertex_buffer(
                                            bgfx_copy(raw), layout, BGFX_BUFFER_NONE));
                } finally {
                    MemoryUtil.memFree(raw);
                }
            }
            indices = uploadIndices(scene.indexData(), false);
            edges = uploadIndices(scene.indexData(), true);
            for (var texture : scene.textures()) {
                ByteBuffer raw = MemoryUtil.memAlloc(texture.rgba8().remaining());
                try {
                    raw.put(texture.rgba8()).flip();
                    textures.add(
                            valid(
                                    bgfx_create_texture_2d(
                                            texture.width(),
                                            texture.height(),
                                            false,
                                            1,
                                            BGFX_TEXTURE_FORMAT_RGBA8,
                                            BGFX_TEXTURE_NONE,
                                            bgfx_copy(raw),
                                            0)));
                } finally {
                    MemoryUtil.memFree(raw);
                }
            }
            try (var stack = MemoryStack.stackPush()) {
                var pixel = stack.malloc(4);
                pixel.putInt(-1).flip();
                white =
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
            bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
            bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x14202aff, 1.0f, 0);
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    static String shaderProfile(int renderer) {
        return switch (renderer) {
            case BGFX_RENDERER_TYPE_DIRECT3D11 -> "dx11";
            case BGFX_RENDERER_TYPE_METAL -> "metal";
            case BGFX_RENDERER_TYPE_VULKAN -> "spirv";
            case BGFX_RENDERER_TYPE_OPENGL -> "glsl";
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported GPU preview backend: " + renderer);
        };
    }

    void draw(int width, int height, StaticMeshRenderer.View view, boolean textured) {
        if (closed) {
            throw new IllegalStateException("GPU model renderer is closed");
        }
        var camera =
                GpuCamera.create(scene, width, height, view, bgfx_get_caps().homogeneousDepth());
        bgfx_set_view_rect(0, 0, 0, width, height);
        bgfx_touch(0);
        try (var stack = MemoryStack.stackPush()) {
            var matrix = stack.mallocFloat(16).put(camera.modelToClip()).flip();
            var light = stack.mallocFloat(4).put(camera.lightDirection()).flip();
            var material = stack.mallocFloat(4);
            for (var draw : scene.draws()) {
                if (!draw.visible(view.parts()) || draw.indexCount() == 0) {
                    continue;
                }
                submit(draw, matrix, light, material, textured, false);
            }
            if (view.wireframe()) {
                for (var draw : scene.draws()) {
                    if (draw.visible(view.parts()) && draw.indexCount() != 0) {
                        submit(draw, matrix, light, material, textured, true);
                    }
                }
            }
        }
    }

    private void submit(
            GpuModelScene.Draw draw,
            FloatBuffer matrix,
            FloatBuffer light,
            FloatBuffer material,
            boolean textured,
            boolean edgePass) {
        boolean sample = textured && draw.textured();
        material.clear()
                .put(sample ? 1f : 0f)
                .put(draw.alphaKey() ? 1f : 0f)
                .put(scene.hasNormals() ? 1f : 0f)
                .put(edgePass ? 1f : 0f)
                .flip();
        bgfx_set_uniform(matrixUniform, matrix, 1);
        bgfx_set_uniform(lightUniform, light, 1);
        bgfx_set_uniform(materialUniform, material, 1);
        int flags = BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT | BGFX_SAMPLER_MIP_POINT;
        if (!draw.wrapU()) {
            flags |= BGFX_SAMPLER_U_CLAMP;
        }
        if (!draw.wrapV()) {
            flags |= BGFX_SAMPLER_V_CLAMP;
        }
        bgfx_set_texture(0, sampler, sample ? textures.get(draw.textureIndex()) : white, flags);
        bgfx_set_vertex_buffer(0, vertices, 0, scene.vertexCount());
        bgfx_set_index_buffer(
                edgePass ? edges : indices,
                draw.firstIndex() * (edgePass ? 2 : 1),
                draw.indexCount() * (edgePass ? 2 : 1));
        long state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A;
        state |=
                edgePass
                        ? BGFX_STATE_DEPTH_TEST_LEQUAL | BGFX_STATE_PT_LINES
                        : BGFX_STATE_DEPTH_TEST_LESS | BGFX_STATE_WRITE_Z;
        bgfx_set_state(state, 0);
        bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
    }

    private static short uploadIndices(IntBuffer input, boolean lines) {
        var raw = MemoryUtil.memAllocShort(input.remaining() * (lines ? 2 : 1));
        try {
            while (input.hasRemaining()) {
                int a = input.get(), b = input.get(), c = input.get();
                raw.put((short) a).put((short) b);
                if (lines) {
                    raw.put((short) b).put((short) c).put((short) c).put((short) a);
                } else {
                    raw.put((short) c);
                }
            }
            raw.flip();
            return valid(bgfx_create_index_buffer(bgfx_copy(raw), BGFX_BUFFER_NONE));
        } finally {
            MemoryUtil.memFree(raw);
        }
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
        String path = "/com/vibeminer/rendering/bgfx/shaders/" + profile + "/" + name + ".bin";
        try (var input = BgfxModelRenderer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("GPU shader is unavailable for backend " + profile);
            }
            byte[] bytes = input.readNBytes(1 << 20);
            if (bytes.length == 0 || bytes.length >= 1 << 20 || input.read() != -1) {
                throw new IOException("Invalid GPU shader size");
            }
            ByteBuffer raw = MemoryUtil.memAlloc(bytes.length);
            try {
                raw.put(bytes).flip();
                return valid(bgfx_create_shader(bgfx_copy(raw)));
            } finally {
                MemoryUtil.memFree(raw);
            }
        }
    }

    private static short valid(short handle) {
        if (handle == INVALID) {
            throw new IllegalStateException("GPU resource creation failed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (vertices != INVALID) {
            bgfx_destroy_vertex_buffer(vertices);
        }
        if (indices != INVALID) {
            bgfx_destroy_index_buffer(indices);
        }
        if (edges != INVALID) {
            bgfx_destroy_index_buffer(edges);
        }
        for (short texture : textures) {
            bgfx_destroy_texture(texture);
        }
        if (white != INVALID) {
            bgfx_destroy_texture(white);
        }
        if (program != INVALID) {
            bgfx_destroy_program(program);
        }
        for (short uniform : new short[] {matrixUniform, lightUniform, materialUniform, sampler}) {
            if (uniform != INVALID) {
                bgfx_destroy_uniform(uniform);
            }
        }
    }
}
