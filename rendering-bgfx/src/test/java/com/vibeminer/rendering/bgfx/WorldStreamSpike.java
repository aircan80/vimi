package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD2;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INSTANCING;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_BLIT;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_GREATER;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_MAT4;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_copy;
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_create_program;
import static org.lwjgl.bgfx.BGFX.bgfx_create_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_create_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_program;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_stats;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_make_ref_release;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;
import static org.lwjgl.bgfx.BGFX.bgfx_update_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_update_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;

import com.vibeminer.rendering.bgfx.GpuTerrainSpike.Triangle;
import com.vibeminer.rendering.bgfx.GpuTerrainSpike.Vertex;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.bgfx.BGFXReleaseFunctionCallback;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Authored native experiment only: no World GUI, packet parser or production renderer changes. */
public final class WorldStreamSpike {
    public static final int WIDTH = 256, HEIGHT = 256, SLOT_COUNT = 10, MAX_TRIANGLES = 65536;
    public static final int INSTANCE_BYTES = 48, ATLAS_BYTES = 512 * 512 * 4;
    public static final int GEOMETRY_CHUNK = 2048 * INSTANCE_BYTES, ATLAS_CHUNK = 32 * 512 * 4;
    private static final short INVALID = -1;
    private static final int SAMPLER_FLAGS =
            BGFX_SAMPLER_MIN_POINT
                    | BGFX_SAMPLER_MAG_POINT
                    | BGFX_SAMPLER_MIP_POINT
                    | BGFX_SAMPLER_U_CLAMP
                    | BGFX_SAMPLER_V_CLAMP;

    private WorldStreamSpike() {}

    public record Resident(int slot, long generation) {}

    public record Rendered(long worldRevision, long cameraRevision, int[] pixels) {}

    public record Stats(
            int dynamicBuffers,
            int backingBuffers,
            int textures,
            long callbacks,
            int outstandingReferences,
            long liveUploadBytes,
            long peakUploadBytes,
            int peakStepBytes,
            long maximumStepNanos,
            int frames,
            long gpuHeapUsage) {}

    public static final class Upload {
        private final long generation;
        private final int slot, triangles;
        private final float anchorX, anchorY;
        private final boolean textured;
        private final AtomicLong issued = new AtomicLong(), released = new AtomicLong();
        private final AtomicInteger references = new AtomicInteger();
        private ByteBuffer geometry, rgba;
        private int geometryAt, atlasRow, tickets;
        private boolean canceled, finished, successful;
        private volatile String callbackFailure;

        private Upload(
                long generation,
                int slot,
                int triangles,
                float anchorX,
                float anchorY,
                boolean textured,
                ByteBuffer geometry,
                ByteBuffer rgba) {
            this.generation = generation;
            this.slot = slot;
            this.triangles = triangles;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.textured = textured;
            this.geometry = geometry;
            this.rgba = rgba;
        }

        public int outstandingReferences() {
            return references.get();
        }

        public boolean finished() {
            return finished;
        }

        public boolean successful() {
            return successful;
        }
    }

    private static final class Slot {
        short buffer = INVALID, texture = INVALID;
        long generation;
        int triangles;
        float anchorX, anchorY;
        boolean textured, ready;
    }

    public static final class Harness implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Slot[] slots = new Slot[SLOT_COUNT];
        private final AtomicReference<Upload> uploading = new AtomicReference<>();
        private final AtomicLong callbackCount = new AtomicLong();
        private final AtomicReference<String> callbackFailure = new AtomicReference<>();
        private final BGFXReleaseFunctionCallback release =
                BGFXReleaseFunctionCallback.create(this::released);
        private short program = INVALID, template = INVALID, eye = INVALID, projection = INVALID;
        private short rebase = INVALID, atlasUniform = INVALID, sampler = INVALID;
        private short color = INVALID, depth = INVALID, target = INVALID, readback = INVALID;
        private ByteBuffer pixels;
        private List<Resident> active = List.of();
        private long worldRevision, nextGeneration, liveBytes, peakBytes, maximumStepNanos;
        private int frames, peakStepBytes;
        private boolean initialized, closed;

        public Harness() throws IOException {
            try {
                initialize();
                initialized = true;
                for (int i = 0; i < slots.length; i++) {
                    slots[i] = new Slot();
                }
                try (var layout = instanceLayout()) {
                    for (var slot : slots) {
                        slot.buffer =
                                valid(
                                        bgfx_create_dynamic_vertex_buffer(
                                                MAX_TRIANGLES, layout, BGFX_BUFFER_NONE));
                        slot.texture =
                                valid(
                                        bgfx_create_texture_2d(
                                                512,
                                                512,
                                                false,
                                                1,
                                                BGFX_TEXTURE_FORMAT_RGBA8,
                                                BGFX_TEXTURE_NONE,
                                                null,
                                                0));
                    }
                }
                template = template();
                program = program();
                eye = valid(bgfx_create_uniform("u_worldEye", BGFX_UNIFORM_TYPE_MAT4, 1));
                projection =
                        valid(bgfx_create_uniform("u_worldProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
                rebase = valid(bgfx_create_uniform("u_worldRebase", BGFX_UNIFORM_TYPE_VEC4, 1));
                atlasUniform =
                        valid(bgfx_create_uniform("u_worldAtlas", BGFX_UNIFORM_TYPE_VEC4, 1));
                sampler = valid(bgfx_create_uniform("s_worldAtlas", BGFX_UNIFORM_TYPE_SAMPLER, 1));
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
                readback =
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
                bgfx_set_view_frame_buffer(0, target);
                bgfx_set_view_rect(0, 0, 0, WIDTH, HEIGHT);
                bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x334455ff, 0, 0);
                advance();
                advance();
            } catch (IOException | RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        public Upload beginUpload(
                int slotIndex,
                List<Triangle> triangles,
                ByteBuffer rgba,
                float anchorX,
                float anchorY,
                boolean textured) {
            requireOwner();
            requireOpen();
            if (slotIndex < 0
                    || slotIndex >= SLOT_COUNT
                    || triangles == null
                    || triangles.size() > MAX_TRIANGLES
                    || rgba == null
                    || rgba.remaining() != ATLAS_BYTES
                    || !Float.isFinite(anchorX)
                    || !Float.isFinite(anchorY)
                    || Math.abs(anchorX) > 1_000_000
                    || Math.abs(anchorY) > 1_000_000
                    || uploading.get() != null
                    || active.stream().anyMatch(r -> r.slot() == slotIndex)) {
                throw new IllegalArgumentException("Invalid or occupied staging slot");
            }
            ByteBuffer geometry = null, atlas = null;
            try {
                if (!triangles.isEmpty()) {
                    geometry =
                            MemoryUtil.memAlloc(triangles.size() * INSTANCE_BYTES)
                                    .order(ByteOrder.nativeOrder());
                    var out = geometry.asFloatBuffer();
                    for (var triangle : triangles) {
                        corner(out, triangle.a(), triangle.baseR());
                        corner(out, triangle.b(), triangle.baseG());
                        corner(out, triangle.c(), triangle.baseB());
                    }
                }
                atlas = MemoryUtil.memAlloc(ATLAS_BYTES);
                atlas.put(rgba.duplicate()).flip();
                if (++nextGeneration > (Long.MAX_VALUE >>> 8)) {
                    throw new IllegalStateException("Generation exhausted");
                }
                var upload =
                        new Upload(
                                nextGeneration,
                                slotIndex,
                                triangles.size(),
                                anchorX,
                                anchorY,
                                textured,
                                geometry,
                                atlas);
                slots[slotIndex].ready = false;
                slots[slotIndex].generation = upload.generation;
                uploading.set(upload);
                liveBytes = (geometry == null ? 0 : geometry.capacity()) + (long) atlas.capacity();
                peakBytes = Math.max(peakBytes, liveBytes);
                return upload;
            } catch (RuntimeException | Error failure) {
                if (geometry != null) {
                    MemoryUtil.memFree(geometry);
                }
                if (atlas != null) {
                    MemoryUtil.memFree(atlas);
                }
                throw failure;
            }
        }

        /** At most two 96KiB geometry chunks plus one 64KiB strip, checked between calls. */
        public int step(Upload upload) {
            requireOwner();
            requireOpen();
            requireUpload(upload);
            long started = System.nanoTime();
            int bytes = 0;
            if (!upload.canceled) {
                for (int i = 0;
                        i < 2
                                && upload.geometry != null
                                && upload.geometryAt < upload.geometry.capacity();
                        i++) {
                    if (bytes != 0 && System.nanoTime() - started >= 2_000_000) {
                        break;
                    }
                    int length =
                            Math.min(
                                    GEOMETRY_CHUNK, upload.geometry.capacity() - upload.geometryAt);
                    var slice =
                            upload.geometry
                                    .duplicate()
                                    .position(upload.geometryAt)
                                    .limit(upload.geometryAt + length)
                                    .slice();
                    var memory = reference(upload, slice);
                    bgfx_update_dynamic_vertex_buffer(
                            slots[upload.slot].buffer, upload.geometryAt / INSTANCE_BYTES, memory);
                    upload.geometryAt += length;
                    bytes += length;
                }
                if (upload.atlasRow < 512
                        && (bytes == 0 || System.nanoTime() - started < 2_000_000)) {
                    int at = upload.atlasRow * 512 * 4;
                    var slice =
                            upload.rgba.duplicate().position(at).limit(at + ATLAS_CHUNK).slice();
                    bgfx_update_texture_2d(
                            slots[upload.slot].texture,
                            0,
                            0,
                            0,
                            upload.atlasRow,
                            512,
                            32,
                            reference(upload, slice),
                            512 * 4);
                    upload.atlasRow += 32;
                    bytes += ATLAS_CHUNK;
                }
            }
            maximumStepNanos = Math.max(maximumStepNanos, System.nanoTime() - started);
            peakStepBytes = Math.max(peakStepBytes, bytes);
            if (bytes > 262144) {
                throw new AssertionError("Upload byte budget escaped");
            }
            completeUpload();
            return bytes;
        }

        private org.lwjgl.bgfx.BGFXMemory reference(Upload upload, ByteBuffer data) {
            if (upload.tickets >= 48) {
                throw new IllegalStateException("Too many source references");
            }
            int ticket = upload.tickets++;
            upload.issued.getAndUpdate(bits -> bits | (1L << ticket));
            upload.references.incrementAndGet();
            return bgfx_make_ref_release(data, release, (upload.generation << 8) | ticket);
        }

        private void released(long data, long id) {
            var upload = uploading.get();
            if (upload == null || upload.generation != (id >>> 8)) {
                callbackFailure.compareAndSet(null, "Unknown or stale release generation");
                return;
            }
            int ticket = (int) (id & 255);
            long bit = 1L << ticket;
            if (ticket >= 48 || (upload.issued.get() & bit) == 0) {
                upload.callbackFailure = "Unknown release ticket";
                return;
            }
            long before = upload.released.getAndUpdate(bits -> bits | bit);
            if ((before & bit) != 0) {
                upload.callbackFailure = "Duplicate release ticket";
                return;
            }
            boolean pointer = inRange(upload.geometry, data) || inRange(upload.rgba, data);
            if (!pointer) {
                upload.callbackFailure = "Foreign source pointer";
            }
            callbackCount.incrementAndGet();
            upload.references.decrementAndGet();
        }

        private static boolean inRange(ByteBuffer buffer, long address) {
            return buffer != null
                    && address >= MemoryUtil.memAddress(buffer)
                    && address < MemoryUtil.memAddress(buffer) + buffer.capacity();
        }

        private void completeUpload() {
            if (callbackFailure.get() != null) {
                throw new IllegalStateException(callbackFailure.get());
            }
            Upload upload = uploading.get();
            if (upload == null) {
                return;
            }
            if (upload.callbackFailure != null) {
                throw new IllegalStateException(upload.callbackFailure);
            }
            boolean all =
                    (upload.geometry == null || upload.geometryAt == upload.geometry.capacity())
                            && upload.atlasRow == 512;
            if (upload.references.get() != 0 || (!upload.canceled && !all)) {
                return;
            }
            if (upload.issued.get() != upload.released.get()) {
                throw new IllegalStateException("Unreleased source ticket");
            }
            if (!upload.canceled) {
                var slot = slots[upload.slot];
                slot.triangles = upload.triangles;
                slot.anchorX = upload.anchorX;
                slot.anchorY = upload.anchorY;
                slot.textured = upload.textured;
                slot.ready = true;
                upload.successful = true;
            }
            freeUpload(upload);
            upload.finished = true;
            uploading.set(null);
        }

        public void cancel(Upload upload) {
            requireOwner();
            requireOpen();
            requireUpload(upload);
            upload.canceled = true;
            completeUpload();
        }

        public Resident resident(Upload upload) {
            requireOwner();
            requireOpen();
            if (!upload.successful
                    || !slots[upload.slot].ready
                    || slots[upload.slot].generation != upload.generation) {
                throw new IllegalStateException("No ready resident token");
            }
            return new Resident(upload.slot, upload.generation);
        }

        public void commit(long expectedRevision, long nextRevision, List<Resident> ordered) {
            requireOwner();
            requireOpen();
            if (expectedRevision != worldRevision
                    || nextRevision != worldRevision + 1
                    || ordered == null
                    || ordered.size() > 9) {
                throw new IllegalArgumentException("Invalid content revision/count");
            }
            boolean[] seen = new boolean[SLOT_COUNT];
            for (var resident : ordered) {
                if (resident.slot() < 0
                        || resident.slot() >= SLOT_COUNT
                        || seen[resident.slot()]
                        || !slots[resident.slot()].ready
                        || slots[resident.slot()].generation != resident.generation()) {
                    throw new IllegalArgumentException(
                            "Unready, duplicate or stale resident token");
                }
                seen[resident.slot()] = true;
            }
            active = List.copyOf(ordered);
            worldRevision = nextRevision;
        }

        public Rendered render(TerrainCamera camera, long cameraRevision) throws IOException {
            requireOwner();
            requireOpen();
            long content = worldRevision;
            try (var stack = MemoryStack.stackPush()) {
                var origin =
                        new TerrainScene.Origin(
                                (float) camera.x(), (float) camera.y(), (float) camera.z());
                var matrix = stack.mallocFloat(16).put(camera.modelToEye(origin)).flip();
                var project =
                        stack.mallocFloat(16)
                                .put(TerrainCamera.reversedProjection(WIDTH, HEIGHT))
                                .flip();
                var shift =
                        stack.mallocFloat(4)
                                .put(
                                        new float[] {
                                            (float) origin.x(),
                                            (float) origin.y(),
                                            (float) origin.z(),
                                            0
                                        })
                                .flip();
                for (var resident : active) {
                    var slot = slots[resident.slot()];
                    if (slot.triangles == 0) {
                        continue;
                    }
                    var texture =
                            stack.mallocFloat(4)
                                    .put(
                                            new float[] {
                                                slot.anchorX,
                                                slot.anchorY,
                                                3f / 1600,
                                                slot.textured ? 1 : 0
                                            })
                                    .flip();
                    bgfx_set_uniform(eye, matrix, 1);
                    bgfx_set_uniform(projection, project, 1);
                    bgfx_set_uniform(rebase, shift, 1);
                    bgfx_set_uniform(atlasUniform, texture, 1);
                    bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
                    bgfx_set_vertex_buffer(0, template, 0, 3);
                    bgfx_set_instance_data_from_dynamic_vertex_buffer(
                            slot.buffer, 0, slot.triangles);
                    bgfx_set_state(
                            BGFX_STATE_WRITE_RGB
                                    | BGFX_STATE_WRITE_A
                                    | BGFX_STATE_WRITE_Z
                                    | BGFX_STATE_DEPTH_TEST_GREATER,
                            0);
                    bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
                }
                bgfx_touch(0);
            }
            bgfx_blit(1, readback, 0, 0, 0, 0, color, 0, 0, 0, 0, WIDTH, HEIGHT, 1);
            int ready = bgfx_read_texture(readback, pixels, 0, 0);
            long deadline = System.nanoTime() + 10_000_000_000L;
            int frame;
            do {
                frame = advance();
                if (System.nanoTime() > deadline) {
                    throw new IOException("World spike readback timeout");
                }
            } while (Integer.compareUnsigned(frame, ready) < 0);
            int[] result = new int[WIDTH * HEIGHT];
            for (int i = 0; i < result.length; i++) {
                int at = i * 4;
                result[i] =
                        (pixels.get(at + 3) & 255) << 24
                                | (pixels.get(at) & 255) << 16
                                | (pixels.get(at + 1) & 255) << 8
                                | pixels.get(at + 2) & 255;
            }
            frames++;
            return new Rendered(content, cameraRevision, result);
        }

        public int advance() {
            requireOwner();
            requireOpen();
            int frame = bgfx_frame(BGFX_FRAME_NONE);
            completeUpload();
            return frame;
        }

        public Stats stats() {
            requireOwner();
            requireOpen();
            var stats = bgfx_get_stats();
            var upload = uploading.get();
            return new Stats(
                    Short.toUnsignedInt(stats.numDynamicVertexBuffers()),
                    Short.toUnsignedInt(stats.numVertexBuffers()),
                    Short.toUnsignedInt(stats.numTextures()),
                    callbackCount.get(),
                    upload == null ? 0 : upload.references.get(),
                    liveBytes,
                    peakBytes,
                    peakStepBytes,
                    maximumStepNanos,
                    frames,
                    stats.gpuMemoryUsed());
        }

        private void requireUpload(Upload upload) {
            if (upload == null || uploading.get() != upload || upload.finished) {
                throw new IllegalStateException("Foreign or retired upload");
            }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Wrong native owner");
            }
        }

        private void requireOpen() {
            if (closed || !initialized) {
                throw new IllegalStateException("World spike closed");
            }
        }

        private void freeUpload(Upload upload) {
            if (upload.geometry != null) {
                MemoryUtil.memFree(upload.geometry);
                upload.geometry = null;
            }
            if (upload.rgba != null) {
                MemoryUtil.memFree(upload.rgba);
                upload.rgba = null;
            }
            liveBytes = 0;
        }

        @Override
        public void close() {
            requireOwner();
            if (closed) {
                return;
            }
            closed = true;
            if (initialized) {
                var pending = uploading.get();
                if (pending != null) {
                    pending.canceled = true;
                }
                // Keep callback and referenced source buffers alive through native shutdown.
                for (var slot : slots) {
                    if (slot != null) {
                        if (slot.buffer != INVALID) {
                            bgfx_destroy_dynamic_vertex_buffer(slot.buffer);
                        }
                        if (slot.texture != INVALID) {
                            bgfx_destroy_texture(slot.texture);
                        }
                    }
                }
                for (short uniform : new short[] {eye, projection, rebase, atlasUniform, sampler}) {
                    if (uniform != INVALID) {
                        bgfx_destroy_uniform(uniform);
                    }
                }
                if (template != INVALID) {
                    bgfx_destroy_vertex_buffer(template);
                }
                if (program != INVALID) {
                    bgfx_destroy_program(program);
                }
                if (target != INVALID) {
                    bgfx_destroy_frame_buffer(target);
                }
                for (short texture : new short[] {color, depth, readback}) {
                    if (texture != INVALID) {
                        bgfx_destroy_texture(texture);
                    }
                }
                bgfx_shutdown();
                initialized = false;
                if (pending != null) {
                    freeUpload(pending);
                    pending.finished = true;
                    uploading.set(null);
                }
            }
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
            release.free();
        }
    }

    /**
     * Public statistics plus spare-capacity probes distinguish the pinned 4MiB max-tile backing.
     */
    public static void allocationProbe() throws IOException {
        boolean initialized = false;
        var full = new ArrayList<Short>();
        var probes = new ArrayList<Short>();
        try {
            initialize();
            initialized = true;
            for (int i = 0; i < 3; i++) {
                bgfx_frame(BGFX_FRAME_NONE);
            }
            int before = Short.toUnsignedInt(bgfx_get_stats().numVertexBuffers());
            long baselineHeap = bgfx_get_stats().gpuMemoryUsed();
            try (var layout = instanceLayout()) {
                for (int i = 0; i < 10; i++) {
                    full.add(
                            valid(
                                    bgfx_create_dynamic_vertex_buffer(
                                            MAX_TRIANGLES, layout, BGFX_BUFFER_NONE)));
                }
                pump(3);
                int ten = Short.toUnsignedInt(bgfx_get_stats().numVertexBuffers());
                long tenHeap = bgfx_get_stats().gpuMemoryUsed();
                require(ten - before == 10, "Ten full tiles require ten physical backings");
                // Full allocation3MiB+48 leaves1MiB-48. This probe requests1MiB-64 including
                // padding.
                for (int i = 0; i < 10; i++) {
                    probes.add(
                            valid(
                                    bgfx_create_dynamic_vertex_buffer(
                                            21843, layout, BGFX_BUFFER_NONE)));
                }
                pump(3);
                int filled = Short.toUnsignedInt(bgfx_get_stats().numVertexBuffers());
                require(filled == ten, "Ten near1MiB probes fit existing spare regions");
                probes.add(valid(bgfx_create_dynamic_vertex_buffer(1, layout, BGFX_BUFFER_NONE)));
                pump(3);
                int spill = Short.toUnsignedInt(bgfx_get_stats().numVertexBuffers());
                require(spill == ten + 1, "Small spill exceeds all ten near4MiB capacities");
                System.out.println(
                        "WORLD_DYNAMIC_ALLOCATION_PASS before="
                                + before
                                + " full10="
                                + ten
                                + " spareFilled="
                                + filled
                                + " spill="
                                + spill
                                + " pinnedLogicalBacking=10x4194304 baselineHeapUsage="
                                + baselineHeap
                                + " tenBackingHeapUsage="
                                + tenHeap
                                + " probeHeapUsage="
                                + bgfx_get_stats().gpuMemoryUsed());
                for (short probe : probes) {
                    bgfx_destroy_dynamic_vertex_buffer(probe);
                }
                probes.clear();
                pump(6);
                require(
                        Short.toUnsignedInt(bgfx_get_stats().numDynamicVertexBuffers()) == 10,
                        "Probe handles retire");
            }
        } finally {
            if (initialized) {
                for (short probe : probes) {
                    bgfx_destroy_dynamic_vertex_buffer(probe);
                }
                for (short buffer : full) {
                    bgfx_destroy_dynamic_vertex_buffer(buffer);
                }
                pump(6);
                bgfx_shutdown();
            }
        }
    }

    private static void initialize() throws IOException {
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.calloc(stack);
            bgfx_init_ctor(init);
            init.type(BGFX_RENDERER_TYPE_VULKAN).fallback(false);
            init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
            BGFXPlatform.bgfx_render_frame(0);
            if (!bgfx_init(init)) {
                throw new IOException("Headless Vulkan unavailable");
            }
        }
        try {
            var caps = bgfx_get_caps();
            if (caps.rendererType() != BGFX_RENDERER_TYPE_VULKAN
                    || caps.homogeneousDepth()
                    || (caps.supported()
                                    & (BGFX_CAPS_INSTANCING
                                            | BGFX_CAPS_TEXTURE_READ_BACK
                                            | BGFX_CAPS_TEXTURE_BLIT))
                            != (BGFX_CAPS_INSTANCING
                                    | BGFX_CAPS_TEXTURE_READ_BACK
                                    | BGFX_CAPS_TEXTURE_BLIT)) {
                throw new IOException(
                        "World spike requires qualified Vulkan depth/instancing/readback");
            }
            GpuViewerMain.requireHardware(
                    Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
        } catch (IOException | RuntimeException | Error failure) {
            bgfx_shutdown();
            throw failure;
        }
    }

    private static BGFXVertexLayout instanceLayout() {
        var layout = BGFXVertexLayout.calloc();
        bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_TEXCOORD0, 4, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_TEXCOORD1, 4, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_TEXCOORD2, 4, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_end(layout);
        if (layout.stride() != INSTANCE_BYTES) {
            layout.free();
            throw new IllegalStateException("Unexpected instance stride");
        }
        return layout;
    }

    private static short template() throws IOException {
        var data = MemoryUtil.memAllocFloat(9);
        data.put(new float[] {0, 0, 0, 1, 0, 0, 2, 0, 0}).flip();
        try (var layout = BGFXVertexLayout.calloc()) {
            bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
            bgfx_vertex_layout_add(
                    layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
            bgfx_vertex_layout_end(layout);
            return valid(bgfx_create_vertex_buffer(bgfx_copy(data), layout, BGFX_BUFFER_NONE));
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static short program() throws IOException {
        short vertex = shader("vs_model.bin"), fragment = INVALID;
        try {
            fragment = shader("fs_model.bin");
            short result = bgfx_create_program(vertex, fragment, true);
            if (result == INVALID) {
                throw new IOException("World spike shader program unavailable");
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            bgfx_destroy_shader(vertex);
            if (fragment != INVALID) {
                bgfx_destroy_shader(fragment);
            }
            throw failure;
        }
    }

    private static short shader(String name) throws IOException {
        String path = "/com/vibeminer/rendering/bgfx/world-stream-spike/spirv/" + name;
        byte[] bytes;
        try (var stream = WorldStreamSpike.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing authored shader " + path);
            }
            bytes = stream.readAllBytes();
        }
        var data = MemoryUtil.memAlloc(bytes.length);
        try {
            return valid(bgfx_create_shader(bgfx_copy(data.put(bytes).flip())));
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Native resource allocation failed");
        }
        return handle;
    }

    private static void corner(FloatBuffer target, Vertex vertex, double color) {
        target.put((float) vertex.x())
                .put((float) vertex.y())
                .put((float) vertex.z())
                .put((float) color);
    }

    private static void pump(int frames) {
        for (int i = 0; i < frames; i++) {
            bgfx_frame(BGFX_FRAME_NONE);
        }
    }

    public static ByteBuffer rgba(int red, int green, int blue) {
        var result = ByteBuffer.allocate(ATLAS_BYTES);
        for (int i = 0; i < 512 * 512; i++) {
            result.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
        }
        return result.flip();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void color(Rendered frame, int r, int g, int b) {
        int pixel = frame.pixels()[128 * WIDTH + 128];
        require(
                Math.abs(((pixel >>> 16) & 255) - r) <= 1
                        && Math.abs(((pixel >>> 8) & 255) - g) <= 1
                        && Math.abs((pixel & 255) - b) <= 1
                        && (pixel >>> 24) == 255,
                "Center color differs: " + Integer.toHexString(pixel));
    }

    private static List<Triangle> panel(double origin, int r, int g, int b) {
        var a = new Vertex(origin + 10, origin - 2, origin - 2);
        var v = new Vertex(origin + 10, origin + 2, origin - 2);
        var c = new Vertex(origin + 10, origin + 2, origin + 2);
        var d = new Vertex(origin + 10, origin - 2, origin + 2);
        return List.of(new Triangle(a, v, c, r, g, b), new Triangle(a, c, d, r, g, b));
    }

    private static Resident upload(
            Harness harness,
            int slot,
            List<Triangle> triangles,
            ByteBuffer pixels,
            float x,
            float y,
            boolean textured)
            throws IOException {
        var job = harness.beginUpload(slot, triangles, pixels, x, y, textured);
        while (!job.finished()) {
            harness.step(job);
            harness.advance();
        }
        return harness.resident(job);
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "/tmp/vimi-world-stream-spike" : args[0]);
        Files.createDirectories(output);
        allocationProbe();
        try (var harness = new Harness()) {
            var camera = new TerrainCamera(0, 0, 0, 0, 0);
            long revision = 0;
            var initial = upload(harness, 0, panel(0, 255, 0, 0), rgba(255, 0, 0), 20, 20, true);
            var active = new ArrayList<Resident>();
            active.add(initial);
            var hidden =
                    new Triangle(
                            new Vertex(-1, 0, 0),
                            new Vertex(-1, 0, 0),
                            new Vertex(-1, 0, 0),
                            0,
                            0,
                            0);
            for (int slot = 1; slot < 9; slot++) {
                active.add(upload(harness, slot, List.of(hidden), rgba(0, 0, 0), 20, 20, true));
            }
            harness.commit(revision, ++revision, active);
            int visibleSlot = 0;
            color(harness.render(camera, 1), 255, 0, 0);
            var old = initial;
            for (int cycle = 0; cycle < 104; cycle++) {
                int slot = cycle % 2 == 0 ? 9 : 0;
                var job =
                        harness.beginUpload(
                                slot, panel(0, 255, 255, 255), rgba(0, 255, 255), 20, 20, true);
                harness.step(job);
                if (cycle % 7 == 0) {
                    require(job.outstandingReferences() > 0, "Reference retained before frame");
                    harness.cancel(job);
                    while (!job.finished()) {
                        harness.advance();
                    }
                    require(!job.successful(), "Canceled upload cannot commit");
                    job =
                            harness.beginUpload(
                                    slot, panel(0, 255, 255, 255), rgba(0, 255, 255), 20, 20, true);
                }
                while (!job.finished()) {
                    harness.step(job);
                    var frame = harness.render(camera, 2 + cycle);
                    require(
                            frame.worldRevision() == revision,
                            "Old world remains identified while streaming");
                    color(frame, cycle == 0 ? 255 : 0, cycle == 0 ? 0 : 255, cycle == 0 ? 0 : 255);
                }
                var next = harness.resident(job);
                int retiredSlot = visibleSlot;
                active.removeIf(resident -> resident.slot() == retiredSlot);
                active.add(next);
                harness.commit(revision, ++revision, active);
                visibleSlot = slot;
                var frame = harness.render(camera, 2 + cycle);
                require(
                        frame.worldRevision() == revision,
                        "Stationary commit changes content revision");
                color(frame, 0, 255, 255);
                if (cycle == 0) {
                    old = initial;
                }
            }
            try {
                harness.commit(revision, revision + 1, List.of(old));
                throw new AssertionError("ABA accepted");
            } catch (IllegalArgumentException expected) {
            }
            var degenerate =
                    new Triangle(
                            new Vertex(-1, 0, 0),
                            new Vertex(-1, 0, 0),
                            new Vertex(-1, 0, 0),
                            0,
                            0,
                            0);
            var completePanel = panel(0, 255, 255, 255);
            var maximum =
                    new AbstractList<Triangle>() {
                        public int size() {
                            return MAX_TRIANGLES;
                        }

                        public Triangle get(int index) {
                            return index < 2 ? completePanel.get(index) : degenerate;
                        }
                    };
            int stagingSlot = visibleSlot == 0 ? 9 : 0;
            var max = harness.beginUpload(stagingSlot, maximum, rgba(0, 255, 255), 20, 20, true);
            int ticks = 0;
            var cameraImages = new HashSet<Integer>();
            while (!max.finished()) {
                harness.step(max);
                var movingFrame =
                        harness.render(
                                new TerrainCamera(0, 0, 0, (ticks % 5 - 2) * .025, 0), 500 + ticks);
                cameraImages.add(Arrays.hashCode(movingFrame.pixels()));
                color(movingFrame, 0, 255, 255);
                ticks++;
            }
            require(
                    cameraImages.size() >= 3,
                    "Camera produces distinct pixels during maximum upload");
            int retiredSlot = visibleSlot;
            active.removeIf(resident -> resident.slot() == retiredSlot);
            active.add(harness.resident(max));
            harness.commit(revision, ++revision, active);
            color(harness.render(camera, 999), 0, 255, 255);
            require(harness.stats().peakUploadBytes() == 4194304, "Max source buffers are4MiB");
            var snapshot = harness.render(camera, 1000);
            var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, WIDTH, HEIGHT, snapshot.pixels(), 0, WIDTH);
            ImageIO.write(image, "PNG", output.resolve("world-stream-authored.png").toFile());
            System.out.println(
                    "WORLD_STREAM_LIFETIME_PASS cycles=104 fullTileTicks="
                            + ticks
                            + " distinctCameraFrames="
                            + cameraImages.size()
                            + " activePairs="
                            + active.size()
                            + " stationaryWorldRevision="
                            + revision
                            + " "
                            + harness.stats());
        }
        System.out.println(
                "WORLD_STREAM_SPIKE_PASS authoredVulkanOnly productionUnchanged wireProtocolNotImplemented");
    }
}
