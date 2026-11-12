package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_NONE;
import static org.lwjgl.bgfx.BGFX.bgfx_copy;
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;

import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The walking body's GPU side (owner thread only): its model's index buffer and textures, a dynamic
 * vertex buffer in the objects' layout that is re-skinned whenever the pose changes, and the one
 * instance row that places it. Skinning, gathering and upload reuse arrays and native memory
 * allocated once, so a frame allocates nothing on the Java heap.
 */
final class WorldStreamBodyDraw implements AutoCloseable {
    private static final short INVALID = (short) 0xffff;

    final WorldStreamBodyFile.Body body;
    final short vertices, indices;
    final short[] textures;
    private final PreviewAnimation.Skinner[] skinners;
    private final float[] positions, normals;
    private final ByteBuffer staging;
    private final org.lwjgl.bgfx.BGFXInstanceDataBuffer instance;
    private int skinnedSequence = -1;
    private long skinnedMillis = -1;
    private long uploads;
    private boolean closed;

    WorldStreamBodyDraw(WorldStreamBodyFile.Body body, org.lwjgl.bgfx.BGFXVertexLayout layout)
            throws IOException {
        this.body = body;
        var animations = body.animations();
        skinners = new PreviewAnimation.Skinner[animations.size()];
        for (int i = 0; i < skinners.length; i++) {
            skinners[i] = animations.get(i).skinner();
        }
        int rest = animations.getFirst().vertexCount();
        positions = new float[rest * 3];
        normals = animations.getFirst().hasNormals() ? new float[rest * 3] : null;
        int count = body.vertexCount();
        staging = MemoryUtil.memCalloc(count * WorldStreamPackedObjects.GPU_VERTEX_BYTES);
        instance = org.lwjgl.bgfx.BGFXInstanceDataBuffer.calloc();
        short createdVertices = INVALID, createdIndices = INVALID;
        textures = new short[body.images().size()];
        java.util.Arrays.fill(textures, INVALID);
        try {
            createdVertices = bgfx_create_dynamic_vertex_buffer(count, layout, BGFX_BUFFER_NONE);
            if (createdVertices == INVALID) {
                throw new IOException("Body vertex buffer unavailable");
            }
            var indexData = MemoryUtil.memAlloc(body.indices().length * 2);
            try {
                for (int index : body.indices()) {
                    indexData.putShort((short) index);
                }
                indexData.flip();
                createdIndices = bgfx_create_index_buffer(bgfx_copy(indexData), BGFX_BUFFER_NONE);
            } finally {
                MemoryUtil.memFree(indexData);
            }
            if (createdIndices == INVALID) {
                throw new IOException("Body index buffer unavailable");
            }
            for (int i = 0; i < textures.length; i++) {
                var image = body.images().get(i);
                var chain = MemoryUtil.memAlloc(image.rgba().length);
                try {
                    chain.put(image.rgba()).flip();
                    textures[i] =
                            bgfx_create_texture_2d(
                                    image.width(),
                                    image.height(),
                                    true,
                                    1,
                                    BGFX_TEXTURE_FORMAT_RGBA8,
                                    BGFX_TEXTURE_NONE,
                                    bgfx_copy(chain),
                                    0);
                } finally {
                    MemoryUtil.memFree(chain);
                }
                if (textures[i] == INVALID) {
                    throw new IOException("Body texture unavailable");
                }
            }
        } catch (IOException | RuntimeException | Error failed) {
            if (createdVertices != INVALID) {
                bgfx_destroy_dynamic_vertex_buffer(createdVertices);
            }
            if (createdIndices != INVALID) {
                bgfx_destroy_index_buffer(createdIndices);
            }
            for (short texture : textures) {
                if (texture != INVALID) {
                    bgfx_destroy_texture(texture);
                }
            }
            MemoryUtil.memFree(staging);
            instance.free();
            throw failed;
        }
        vertices = createdVertices;
        indices = createdIndices;
    }

    /**
     * Skins the body for {@code pose} and uploads its vertices, when the pose's sequence or clock
     * differs from the last upload. False when the model has no such sequence (nothing drawn).
     */
    boolean prepare(WorldStreamTransport.Body pose) {
        int at = -1;
        int[] sequences = body.sequences();
        for (int i = 0; i < sequences.length; i++) {
            if (sequences[i] == pose.sequence()) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return false;
        }
        if (pose.sequence() == skinnedSequence && pose.millis() == skinnedMillis) {
            return true;
        }
        skinners[at].skin(pose.millis(), positions, normals);
        gather(body.vertices(), body.sources(), positions, normals, staging);
        long memory =
                org.lwjgl.bgfx.BGFX.nbgfx_copy(MemoryUtil.memAddress(staging), staging.capacity());
        org.lwjgl.bgfx.BGFX.nbgfx_update_dynamic_vertex_buffer(vertices, 0, memory);
        skinnedSequence = pose.sequence();
        skinnedMillis = pose.millis();
        uploads++;
        return true;
    }

    /**
     * Writes each drawn vertex in the objects' GPU layout: its source rest vertex's skinned
     * position and normal (the model's own normal when the surface records none), its texture
     * coordinate, and no vertex colour. Allocates nothing.
     */
    static void gather(
            float[] model, int[] sources, float[] positions, float[] normals, ByteBuffer out) {
        int stride = WorldStreamPackedObjects.GPU_VERTEX_BYTES;
        for (int v = 0; v < sources.length; v++) {
            int at = v * stride, s = sources[v] * 3, m = v * WorldStreamBodyFile.VERTEX_FLOATS;
            out.putFloat(at, positions[s]);
            out.putFloat(at + 4, positions[s + 1]);
            out.putFloat(at + 8, positions[s + 2]);
            if (normals != null) {
                out.putFloat(at + 12, normals[s]);
                out.putFloat(at + 16, normals[s + 1]);
                out.putFloat(at + 20, normals[s + 2]);
            } else {
                out.putFloat(at + 12, model[m + 3]);
                out.putFloat(at + 16, model[m + 4]);
                out.putFloat(at + 20, model[m + 5]);
            }
            out.putFloat(at + 24, model[m + 6]);
            out.putFloat(at + 28, model[m + 7]);
            out.putInt(at + 32, 0);
        }
    }

    /**
     * Allocates this frame's one instance row for the body and writes it: the body's scale times
     * its yaw about +Z, with its feet relative to the frame origin. False when bgfx has no instance
     * data left this frame.
     */
    boolean place(WorldStreamTransport.Body pose, TerrainScene.Origin origin) {
        if (org.lwjgl.bgfx.BGFX.bgfx_get_avail_instance_data_buffer(1, WorldObjectInstances.STRIDE)
                < 1) {
            return false;
        }
        org.lwjgl.bgfx.BGFX.nbgfx_alloc_instance_data_buffer(
                instance.address(), 1, (short) WorldObjectInstances.STRIDE);
        long data =
                MemoryUtil.memGetAddress(
                        instance.address() + org.lwjgl.bgfx.BGFXInstanceDataBuffer.DATA);
        double scale = body.scale(), c = Math.cos(pose.yaw()) * scale;
        double s = Math.sin(pose.yaw()) * scale;
        row(data, c, -s, 0, pose.x() - origin.x());
        row(data + 16, s, c, 0, pose.y() - origin.y());
        row(data + 32, 0, 0, scale, pose.z() - origin.z());
        return true;
    }

    private static void row(long at, double a, double b, double c, double d) {
        MemoryUtil.memPutFloat(at, (float) a);
        MemoryUtil.memPutFloat(at + 4, (float) b);
        MemoryUtil.memPutFloat(at + 8, (float) c);
        MemoryUtil.memPutFloat(at + 12, (float) d);
    }

    /** The instance row {@link #place} wrote, for this frame's draws. */
    org.lwjgl.bgfx.BGFXInstanceDataBuffer instance() {
        return instance;
    }

    /** Texture handles this body holds. */
    int handles() {
        return textures.length;
    }

    /** GPU bytes this body holds. */
    long bytes() {
        return gpuBytes(body);
    }

    /** Texture handles a body holds: one per image. */
    static int handles(WorldStreamBodyFile.Body body) {
        return body.images().size();
    }

    /**
     * GPU bytes a body holds: its vertex buffer in the objects' layout, its 16-bit indices and
     * every image's full mip chain.
     */
    static long gpuBytes(WorldStreamBodyFile.Body body) {
        long bytes =
                (long) body.vertexCount() * WorldStreamPackedObjects.GPU_VERTEX_BYTES
                        + (long) body.indices().length * 2;
        for (var image : body.images()) {
            bytes += image.rgba().length;
        }
        return bytes;
    }

    /** Vertex uploads so far (a new pose each). */
    long uploads() {
        return uploads;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        bgfx_destroy_dynamic_vertex_buffer(vertices);
        bgfx_destroy_index_buffer(indices);
        for (short texture : textures) {
            bgfx_destroy_texture(texture);
        }
        MemoryUtil.memFree(staging);
        instance.free();
    }
}
