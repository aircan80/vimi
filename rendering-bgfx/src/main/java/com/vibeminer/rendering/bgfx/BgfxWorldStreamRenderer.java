package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD1;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD2;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
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
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_program;
import static org.lwjgl.bgfx.BGFX.bgfx_create_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_create_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_program;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_make_ref_release;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_update_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_update_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_update_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_add;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_begin;
import static org.lwjgl.bgfx.BGFX.bgfx_vertex_layout_end;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.bgfx.BGFXReleaseFunctionCallback;
import org.lwjgl.bgfx.BGFXVertexLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Fixed-resource context-owned persistent terrain. All bgfx methods run on its native owner. */
final class BgfxWorldStreamRenderer implements AutoCloseable {
    static final int SLOT_COUNT = 10, INSTANCE_BYTES = 48;

    /** Terrain 65536 plus optional static water 16384 instances in one per-slot buffer. */
    static final int MAX_TRIANGLES = 65536 + WorldStreamPackedTile.MAX_WATER_TRIANGLES;

    /** Constant 0.40 diagonal blend factor for diagnostic water over opaque terrain. */
    static final int WATER_BLEND_FACTOR = 0x66666666;

    private static final int MAX_TICKETS = 64;
    static final int GEOMETRY_CHUNK = 2048 * INSTANCE_BYTES, ATLAS_CHUNK = 32 * 512 * 4;
    private static final short INVALID = -1;
    private static final int SAMPLER_FLAGS =
            BGFX_SAMPLER_MIN_POINT
                    | BGFX_SAMPLER_MAG_POINT
                    | BGFX_SAMPLER_MIP_POINT
                    | BGFX_SAMPLER_U_CLAMP
                    | BGFX_SAMPLER_V_CLAMP;

    record Ticket(long camera, long content, long policy, int width, int height) {}

    /** objectBytes: issued GPU bytes of all resident and building exterior object sets. */
    record Stats(
            long callbacks,
            int references,
            long liveUploadBytes,
            long peakUploadBytes,
            int peakStepBytes,
            long maximumStepNanos,
            long objectBytes) {}

    /** Bounded object upload slice; same bound as the terrain step. */
    static final int OBJECT_CHUNK = 262144;

    static final class Upload {
        private final long tag;
        private final WorldStreamState.Lease lease;
        private final WorldStreamPackedTile.Source source;
        private final AtomicLong issued = new AtomicLong(), released = new AtomicLong();
        private final AtomicInteger references = new AtomicInteger();
        private int geometryAt, atlasRow, tickets;
        private boolean canceled, finished, successful;
        private volatile String failure;
        private SlotObjects building;
        private int objectVertexAt, objectIndexAt, objectImage, objectsState;

        private Upload(long tag, WorldStreamPackedTile.Source source) {
            this.tag = tag;
            this.source = source;
            lease = source.header.lease();
            atlasRow = source.atlas == null ? 512 : 0;
            objectsState = source.objectsState;
        }

        /** -1 outside the exterior profile; else 0 none, 1 drawn, 2 failed (terrain kept). */
        int objectsState() {
            return objectsState;
        }

        private boolean objectsPending() {
            return objectsState == 1 && source.objects != null;
        }

        boolean finished() {
            return finished;
        }

        boolean successful() {
            return successful;
        }

        int issuedReferences() {
            return Long.bitCount(issued.get());
        }

        int releasedReferences() {
            return Long.bitCount(released.get());
        }

        int outstandingReferences() {
            return references.get();
        }

        long retainedInputBytes() {
            return source.payloadBytes();
        }

        WorldStreamState.Lease lease() {
            return lease;
        }
    }

    private static final class Slot {
        short buffer = INVALID, texture = INVALID;
        WorldStreamState.Lease lease;
        int triangles, water, x, y;
        float anchorX, anchorY;
        boolean textured, ready;
        SlotObjects objects;
    }

    /** One tile's uploaded exterior objects; dropped with its slot generation. */
    private static final class SlotObjects {
        final int[] models, meshes, ranges, placementModels, images;
        final double[] placements;
        final short[] textures;
        short vertices = INVALID, indices = INVALID;
        long bytes;

        SlotObjects(WorldStreamPackedObjects source) {
            models = source.models;
            meshes = source.meshes;
            ranges = source.ranges;
            placementModels = source.placementModels;
            images = source.images;
            placements = source.placements;
            textures = new short[source.imageCount()];
            java.util.Arrays.fill(textures, INVALID);
        }

        void destroy() {
            if (vertices != INVALID) {
                bgfx_destroy_dynamic_vertex_buffer(vertices);
                vertices = INVALID;
            }
            if (indices != INVALID) {
                bgfx_destroy_dynamic_index_buffer(indices);
                indices = INVALID;
            }
            for (int i = 0; i < textures.length; i++) {
                if (textures[i] != INVALID) {
                    bgfx_destroy_texture(textures[i]);
                    textures[i] = INVALID;
                }
            }
            bytes = 0;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final WorldStreamEnvelope.Session session;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final AtomicReference<Upload> uploading = new AtomicReference<>();
    private final AtomicLong callbackCount = new AtomicLong();
    private final AtomicReference<String> callbackFailure = new AtomicReference<>();
    private final BGFXReleaseFunctionCallback release =
            BGFXReleaseFunctionCallback.create(this::released);
    private short program = INVALID, skyProgram = INVALID, template = INVALID;
    private short eye = INVALID, projection = INVALID, rebase = INVALID, atlasUniform = INVALID;
    private short sampler = INVALID, sky = INVALID;
    // Optional exterior objects pass (existing world-wmo shaders); INVALID program disables it.
    private short wmoProgram = INVALID, wmoEye = INVALID, wmoProjection = INVALID;
    private short wmoModel = INVALID, wmoNormal = INVALID, wmoSampler = INVALID;
    private List<WorldStreamState.Lease> active = List.of();
    private WorldStreamState.Lease staged;
    private long content, nextTag, liveBytes, peakBytes, maximumStepNanos;
    private int peakStepBytes;
    private boolean closed, callbackFreed;

    BgfxWorldStreamRenderer(WorldStreamEnvelope.Session session) throws IOException {
        this.session = java.util.Objects.requireNonNull(session);
        try {
            var caps = bgfx_get_caps();
            BgfxTerrainRenderer.requireCapabilities(
                    caps.supported(),
                    caps.homogeneousDepth(),
                    org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT));
            String profile = BgfxModelRenderer.shaderProfile(caps.rendererType());
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
            program = program(profile, "world-stream/");
            skyProgram = program(profile, "terrain/sky/");
            eye = valid(bgfx_create_uniform("u_worldEye", BGFX_UNIFORM_TYPE_MAT4, 1));
            projection = valid(bgfx_create_uniform("u_worldProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
            rebase = valid(bgfx_create_uniform("u_worldRebase", BGFX_UNIFORM_TYPE_VEC4, 1));
            atlasUniform = valid(bgfx_create_uniform("u_worldAtlas", BGFX_UNIFORM_TYPE_VEC4, 1));
            sampler = valid(bgfx_create_uniform("s_worldAtlas", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            sky = valid(bgfx_create_uniform("u_terrainMode", BGFX_UNIFORM_TYPE_VEC4, 1));
            if (session.exteriorObjects()) {
                objectPass(profile);
            }
        } catch (IOException | RuntimeException | Error failure) {
            close();
            // No upload is reachable before construction succeeds, hence no callback was
            // registered.
            release.free();
            callbackFreed = true;
            throw failure;
        }
    }

    /** Optional: on failure every tile's objects report failed while terrain stays intact. */
    private void objectPass(String profile) {
        try {
            if (!org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                    0, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_NONE)) {
                throw new IOException("Exterior objects need RGBA8 textures");
            }
            wmoProgram = program(profile, "world-wmo/");
            wmoEye = valid(bgfx_create_uniform("u_wmoEye", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoProjection =
                    valid(bgfx_create_uniform("u_wmoProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoModel = valid(bgfx_create_uniform("u_wmoModel", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoNormal = valid(bgfx_create_uniform("u_wmoNormal", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoSampler = valid(bgfx_create_uniform("s_wmoDiffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1));
        } catch (IOException | RuntimeException failed) {
            destroyObjectPass();
        }
    }

    private void destroyObjectPass() {
        if (wmoProgram != INVALID) {
            bgfx_destroy_program(wmoProgram);
        }
        for (short uniform : new short[] {wmoEye, wmoProjection, wmoModel, wmoNormal, wmoSampler}) {
            if (uniform != INVALID) {
                bgfx_destroy_uniform(uniform);
            }
        }
        wmoProgram = wmoEye = wmoProjection = wmoModel = wmoNormal = wmoSampler = INVALID;
    }

    Upload begin(WorldStreamPackedTile packed) {
        requireOwner();
        requireOpen();
        if (uploading.get() != null || staged != null) {
            throw new IllegalStateException("One upload credit already held");
        }
        var source = packed.take();
        try {
            var header = source.header;
            var lease = header.lease();
            var old = slots[lease.slot()].lease;
            if (!session.nonce().equals(header.nonce())
                    || !session.scope().equals(header.scope())
                    || active.stream().anyMatch(r -> r.slot() == lease.slot())
                    || old != null && lease.generation() <= old.generation()) {
                throw new IllegalArgumentException("Foreign, active or stale upload lease");
            }
            if (nextTag == (Long.MAX_VALUE >>> 8)) {
                throw new IllegalStateException("Callback tags exhausted");
            }
            var upload = new Upload(++nextTag, source);
            var slot = slots[lease.slot()];
            slot.ready = false;
            slot.lease = lease;
            // A new slot generation drops the previous generation's objects.
            if (slot.objects != null) {
                slot.objects.destroy();
                slot.objects = null;
            }
            if (upload.objectsPending() && wmoProgram == INVALID) {
                failObjects(upload);
            }
            uploading.set(upload);
            liveBytes = source.payloadBytes();
            peakBytes = Math.max(peakBytes, liveBytes);
            return upload;
        } catch (RuntimeException | Error failure) {
            source.close();
            throw failure;
        }
    }

    int step(Upload upload) {
        requireOwner();
        requireOpen();
        requireUpload(upload);
        long started = System.nanoTime();
        int bytes = 0;
        if (!upload.canceled) {
            for (int i = 0;
                    i < 2
                            && upload.source.geometry != null
                            && upload.geometryAt < upload.source.geometry.capacity();
                    i++) {
                if (bytes != 0 && System.nanoTime() - started >= 2_000_000) {
                    break;
                }
                int count =
                        Math.min(
                                GEOMETRY_CHUNK,
                                upload.source.geometry.capacity() - upload.geometryAt);
                var data =
                        upload.source
                                .geometry
                                .duplicate()
                                .position(upload.geometryAt)
                                .limit(upload.geometryAt + count)
                                .slice();
                bgfx_update_dynamic_vertex_buffer(
                        slots[upload.lease.slot()].buffer,
                        upload.geometryAt / INSTANCE_BYTES,
                        reference(upload, data));
                upload.geometryAt += count;
                bytes += count;
            }
            if (upload.atlasRow < 512 && (bytes == 0 || System.nanoTime() - started < 2_000_000)) {
                int first = upload.atlasRow * 512 * 4;
                var data =
                        upload.source
                                .atlas
                                .duplicate()
                                .position(first)
                                .limit(first + ATLAS_CHUNK)
                                .slice();
                bgfx_update_texture_2d(
                        slots[upload.lease.slot()].texture,
                        0,
                        0,
                        0,
                        upload.atlasRow,
                        512,
                        32,
                        reference(upload, data),
                        512 * 4);
                upload.atlasRow += 32;
                bytes += ATLAS_CHUNK;
            }
        }
        if (!upload.canceled && bytes == 0 && terrainSubmitted(upload) && upload.objectsPending()) {
            bytes = stepObjects(upload);
        }
        maximumStepNanos = Math.max(maximumStepNanos, System.nanoTime() - started);
        peakStepBytes = Math.max(peakStepBytes, bytes);
        if (bytes > 262144) {
            throw new IllegalStateException("Upload slice escaped bound");
        }
        pollUploads();
        return bytes;
    }

    private static boolean terrainSubmitted(Upload upload) {
        return (upload.source.geometry == null
                        || upload.geometryAt == upload.source.geometry.capacity())
                && upload.atlasRow == 512;
    }

    /**
     * One bounded object slice: resource creation, then vertex, index and image copies of at most
     * OBJECT_CHUNK bytes. Any failure drops only this tile's objects.
     */
    private int stepObjects(Upload upload) {
        var source = upload.source.objects;
        try {
            if (upload.building == null) {
                var built = new SlotObjects(source);
                upload.building = built;
                try (var layout = objectLayout()) {
                    built.vertices =
                            valid(
                                    bgfx_create_dynamic_vertex_buffer(
                                            source.vertices.capacity()
                                                    / WorldStreamObjectsFile.VERTEX_BYTES,
                                            layout,
                                            BGFX_BUFFER_NONE));
                }
                built.indices =
                        valid(
                                bgfx_create_dynamic_index_buffer(
                                        source.indices.capacity() / 2, BGFX_BUFFER_NONE));
                built.bytes = source.vertices.capacity() + (long) source.indices.capacity();
                return 0;
            }
            var built = upload.building;
            if (upload.objectVertexAt < source.vertices.capacity()) {
                int count =
                        Math.min(OBJECT_CHUNK, source.vertices.capacity() - upload.objectVertexAt);
                bgfx_update_dynamic_vertex_buffer(
                        built.vertices,
                        upload.objectVertexAt / WorldStreamObjectsFile.VERTEX_BYTES,
                        bgfx_copy(source.vertices.slice(upload.objectVertexAt, count)));
                upload.objectVertexAt += count;
                return count;
            }
            if (upload.objectIndexAt < source.indices.capacity()) {
                int count =
                        Math.min(OBJECT_CHUNK, source.indices.capacity() - upload.objectIndexAt);
                bgfx_update_dynamic_index_buffer(
                        built.indices,
                        upload.objectIndexAt / 2,
                        bgfx_copy(source.indices.slice(upload.objectIndexAt, count)));
                upload.objectIndexAt += count;
                return count;
            }
            if (upload.objectImage < source.imageCount()) {
                int image = upload.objectImage;
                int width = source.images[image * 2], height = source.images[image * 2 + 1];
                int count = width * height * 4;
                built.textures[image] =
                        valid(
                                bgfx_create_texture_2d(
                                        width,
                                        height,
                                        false,
                                        1,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_NONE,
                                        bgfx_copy(
                                                source.pixels.slice(
                                                        source.imageOffset(image), count)),
                                        0));
                built.bytes += count;
                upload.objectImage++;
                return count;
            }
            // Every slice is a bgfx-owned copy; the packed native input can retire now.
            source.close();
            upload.source.objects = null;
            return 0;
        } catch (IOException | RuntimeException failed) {
            failObjects(upload);
            return 0;
        }
    }

    private static void failObjects(Upload upload) {
        if (upload.building != null) {
            upload.building.destroy();
            upload.building = null;
        }
        if (upload.source.objects != null) {
            upload.source.objects.close();
            upload.source.objects = null;
        }
        upload.objectsState = 2;
    }

    private static BGFXVertexLayout objectLayout() throws IOException {
        var layout = BGFXVertexLayout.calloc();
        bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_end(layout);
        if (layout.stride() != WorldStreamObjectsFile.VERTEX_BYTES) {
            layout.free();
            throw new IOException("Object vertex layout differs");
        }
        return layout;
    }

    void cancel(Upload upload) {
        requireOwner();
        requireOpen();
        if (upload != null && upload.finished && upload.successful && upload.lease.equals(staged)) {
            var slot = slots[upload.lease.slot()];
            slot.ready = false;
            if (slot.objects != null) {
                slot.objects.destroy();
                slot.objects = null;
            }
            staged = null;
            upload.canceled = true;
            upload.successful = false;
            return;
        }
        requireUpload(upload);
        upload.canceled = true;
        pollUploads();
    }

    void pollUploads() {
        requireOwner();
        if (callbackFailure.get() != null) {
            throw new IllegalStateException(callbackFailure.get());
        }
        var upload = uploading.get();
        if (upload == null) {
            return;
        }
        if (upload.failure != null) {
            throw new IllegalStateException(upload.failure);
        }
        boolean submitted = terrainSubmitted(upload) && !upload.objectsPending();
        if (upload.references.get() != 0 || !upload.canceled && !submitted) {
            return;
        }
        if (upload.failure != null) {
            throw new IllegalStateException(upload.failure);
        }
        if (upload.issued.get() != upload.released.get()) {
            throw new IllegalStateException("Reference mismatch");
        }
        if (!upload.canceled) {
            var slot = slots[upload.lease.slot()];
            slot.triangles = upload.source.triangles;
            slot.water = upload.source.waterTriangles;
            slot.x = upload.source.header.x();
            slot.y = upload.source.header.y();
            staged = upload.lease;
            slot.anchorX = upload.source.header.maxX();
            slot.anchorY = upload.source.header.maxY();
            slot.textured = upload.source.header.outcome() == WorldStreamTileFile.Outcome.TEXTURED;
            if (upload.objectsState == 1) {
                slot.objects = upload.building;
                upload.building = null;
            }
            slot.ready = true;
            upload.successful = true;
        } else if (upload.building != null) {
            upload.building.destroy();
            upload.building = null;
        }
        upload.source.close();
        liveBytes = 0;
        upload.finished = true;
        uploading.set(null);
    }

    private org.lwjgl.bgfx.BGFXMemory reference(Upload upload, ByteBuffer data) {
        if (upload.tickets >= MAX_TICKETS) {
            throw new IllegalStateException("Upload references exceed bound");
        }
        int ticket = upload.tickets++;
        upload.issued.getAndUpdate(v -> v | 1L << ticket);
        upload.references.incrementAndGet();
        return bgfx_make_ref_release(data, release, (upload.tag << 8) | ticket);
    }

    private void released(long data, long userData) {
        var upload = uploading.get();
        if (upload == null || upload.tag != userData >>> 8) {
            callbackFailure.compareAndSet(null, "Foreign callback generation");
            return;
        }
        int ticket = (int) (userData & 255);
        long bit = 1L << ticket;
        if (ticket >= MAX_TICKETS || (upload.issued.get() & bit) == 0) {
            upload.failure = "Unknown callback ticket";
            return;
        }
        long before = upload.released.getAndUpdate(v -> v | bit);
        if ((before & bit) != 0) {
            upload.failure = "Duplicate callback ticket";
            return;
        }
        if (!inRange(upload.source.geometry, data) && !inRange(upload.source.atlas, data)) {
            upload.failure = "Foreign source address";
        }
        callbackCount.incrementAndGet();
        upload.references.decrementAndGet();
    }

    private static boolean inRange(ByteBuffer buffer, long address) {
        return buffer != null
                && address >= MemoryUtil.memAddress(buffer)
                && address < MemoryUtil.memAddress(buffer) + buffer.capacity();
    }

    void commit(long expected, long next, List<WorldStreamState.Lease> ordered) {
        requireOwner();
        requireOpen();
        if (expected != content
                || expected == Long.MAX_VALUE
                || next != expected + 1
                || ordered == null
                || ordered.size() > 9) {
            throw new IllegalArgumentException("Commit revision/count");
        }
        boolean[] seen = new boolean[SLOT_COUNT], coordinates = new boolean[4096];
        for (var lease : ordered) {
            if (lease == null
                    || seen[lease.slot()]
                    || !slots[lease.slot()].ready
                    || !lease.equals(slots[lease.slot()].lease)
                    || !active.contains(lease) && !lease.equals(staged)) {
                throw new IllegalArgumentException("Unready or stale commit lease");
            }
            int coordinate = slots[lease.slot()].y * 64 + slots[lease.slot()].x;
            if (coordinates[coordinate]) {
                throw new IllegalArgumentException("Duplicate tile coordinates");
            }
            coordinates[coordinate] = true;
            seen[lease.slot()] = true;
        }
        active = List.copyOf(ordered);
        if (staged != null && active.contains(staged)) {
            staged = null;
        }
        content = next;
    }

    Ticket draw(TerrainCamera camera, long cameraRevision, long policy, int width, int height) {
        requireOwner();
        requireOpen();
        if (cameraRevision <= 0
                || policy <= 0
                || width < 1
                || width > 800
                || height < 1
                || height > 500) {
            throw new IllegalArgumentException("Frame ticket bounds");
        }
        bgfx_set_view_rect(0, 0, 0, width, height);
        bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 0, 0);
        try (var stack = MemoryStack.stackPush()) {
            var origin =
                    new TerrainScene.Origin(
                            (float) camera.x(), (float) camera.y(), (float) camera.z());
            var matrix = stack.mallocFloat(16).put(camera.modelToEye(origin)).flip();
            var project =
                    stack.mallocFloat(16)
                            .put(TerrainCamera.reversedProjection(width, height))
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
            bgfx_set_uniform(
                    sky, stack.floats(0, height, bgfx_get_caps().originBottomLeft() ? 1 : 0, 0), 1);
            bgfx_set_vertex_buffer(0, template, 0, 3);
            bgfx_set_state(
                    BGFX_STATE_WRITE_RGB
                            | BGFX_STATE_WRITE_A
                            | org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS,
                    0);
            bgfx_submit(0, skyProgram, 0, BGFX_DISCARD_ALL);
            for (var lease : active) {
                var slot = slots[lease.slot()];
                if (slot.triangles == 0) {
                    continue;
                }
                bgfx_set_uniform(eye, matrix, 1);
                bgfx_set_uniform(projection, project, 1);
                bgfx_set_uniform(rebase, shift, 1);
                bgfx_set_uniform(
                        atlasUniform,
                        stack.floats(slot.anchorX, slot.anchorY, 3f / 1600, slot.textured ? 1 : 0),
                        1);
                bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
                bgfx_set_vertex_buffer(0, template, 0, 3);
                bgfx_set_instance_data_from_dynamic_vertex_buffer(slot.buffer, 0, slot.triangles);
                bgfx_set_state(
                        BGFX_STATE_WRITE_RGB
                                | BGFX_STATE_WRITE_A
                                | BGFX_STATE_WRITE_Z
                                | BGFX_STATE_DEPTH_TEST_GREATER,
                        0);
                bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
            }
            if (wmoProgram != INVALID) {
                for (var lease : active) {
                    var objects = slots[lease.slot()].objects;
                    if (objects != null) {
                        drawObjects(stack, objects, origin, matrix, project);
                    }
                }
            }
            // Static diagnostic water after ALL opaque terrain: nearest flat surface wins under
            // strict reversed depth, so coincident duplicates cannot blend twice. RGB only keeps
            // the opaque output alpha.
            for (var lease : active) {
                var slot = slots[lease.slot()];
                if (slot.water == 0) {
                    continue;
                }
                bgfx_set_uniform(eye, matrix, 1);
                bgfx_set_uniform(projection, project, 1);
                bgfx_set_uniform(rebase, shift, 1);
                bgfx_set_uniform(atlasUniform, stack.floats(slot.anchorX, slot.anchorY, 0, 0), 1);
                bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
                bgfx_set_vertex_buffer(0, template, 0, 3);
                bgfx_set_instance_data_from_dynamic_vertex_buffer(
                        slot.buffer, slot.triangles, slot.water);
                bgfx_set_state(
                        BGFX_STATE_WRITE_RGB
                                | BGFX_STATE_WRITE_Z
                                | BGFX_STATE_DEPTH_TEST_GREATER
                                | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC(
                                        org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FACTOR,
                                        org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_FACTOR),
                        WATER_BLEND_FACTOR);
                bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
            }
        }
        return new Ticket(cameraRevision, content, policy, width, height);
    }

    /**
     * Opaque BASIC exterior objects after all terrain and before water, sharing terrain's camera,
     * reversed depth and float origin. Each model matrix is the double placement position minus
     * that origin, cast to float once.
     */
    private void drawObjects(
            MemoryStack stack,
            SlotObjects objects,
            TerrainScene.Origin origin,
            java.nio.FloatBuffer eyeMatrix,
            java.nio.FloatBuffer projectionMatrix) {
        var model = stack.mallocFloat(16);
        var normal = stack.mallocFloat(16);
        for (int p = 0; p < objects.placementModels.length; p++) {
            objectMatrices(objects.placements, p, origin, model, normal);
            int m = objects.placementModels[p];
            for (int mesh = objects.models[m * 2];
                    mesh < objects.models[m * 2] + objects.models[m * 2 + 1];
                    mesh++) {
                int at = mesh * 6;
                for (int r = objects.meshes[at + 4];
                        r < objects.meshes[at + 4] + objects.meshes[at + 5];
                        r++) {
                    bgfx_set_uniform(wmoEye, eyeMatrix, 1);
                    bgfx_set_uniform(wmoProjection, projectionMatrix, 1);
                    bgfx_set_uniform(wmoModel, model, 1);
                    bgfx_set_uniform(wmoNormal, normal, 1);
                    bgfx_set_dynamic_vertex_buffer(
                            0, objects.vertices, objects.meshes[at], objects.meshes[at + 1]);
                    bgfx_set_dynamic_index_buffer(
                            objects.indices,
                            objects.meshes[at + 2] + objects.ranges[r * 3],
                            objects.ranges[r * 3 + 1]);
                    bgfx_set_texture(
                            0,
                            wmoSampler,
                            objects.textures[objects.ranges[r * 3 + 2]],
                            BgfxWorldWmoPass.SAMPLER_STATE);
                    bgfx_set_state(BgfxWorldWmoPass.DRAW_STATE, 0);
                    bgfx_submit(0, wmoProgram, 0, BGFX_DISCARD_ALL);
                }
            }
        }
    }

    /**
     * Column-major model and normal matrices for placement p relative to origin. The translation is
     * computed in double and cast to float once; rotation is the reviewed ADT heading rule.
     */
    static void objectMatrices(
            double[] placements,
            int p,
            TerrainScene.Origin origin,
            java.nio.FloatBuffer model,
            java.nio.FloatBuffer normal) {
        double x = placements[p * 6], y = placements[p * 6 + 1], z = placements[p * 6 + 2];
        double s = placements[p * 6 + 3], co = placements[p * 6 + 4], si = placements[p * 6 + 5];
        model.clear()
                .put((float) (-s * co))
                .put((float) (-s * si))
                .put(0)
                .put(0)
                .put((float) (s * si))
                .put((float) (-s * co))
                .put(0)
                .put(0)
                .put(0)
                .put(0)
                .put((float) s)
                .put(0)
                .put((float) (x - origin.x()))
                .put((float) (y - origin.y()))
                .put((float) (z - origin.z()))
                .put(1)
                .flip();
        float c = (float) co, n = (float) si;
        normal.clear()
                .put(-c)
                .put(-n)
                .put(0)
                .put(0)
                .put(n)
                .put(-c)
                .put(0)
                .put(0)
                .put(0)
                .put(0)
                .put(1)
                .put(0)
                .put(0)
                .put(0)
                .put(0)
                .put(1)
                .flip();
    }

    long contentRevision() {
        return content;
    }

    Stats stats() {
        requireOwner();
        var upload = uploading.get();
        return new Stats(
                callbackCount.get(),
                upload == null ? 0 : upload.references.get(),
                liveBytes,
                peakBytes,
                peakStepBytes,
                maximumStepNanos,
                objectBytes(upload));
    }

    private long objectBytes(Upload upload) {
        long total = upload == null || upload.building == null ? 0 : upload.building.bytes;
        for (var slot : slots) {
            if (slot != null && slot.objects != null) {
                total += slot.objects.bytes;
            }
        }
        return total;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Wrong native owner");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Renderer closed");
        }
    }

    private void requireUpload(Upload upload) {
        if (upload == null || upload != uploading.get() || upload.finished) {
            throw new IllegalStateException("Foreign or finished upload");
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        var pending = uploading.get();
        if (pending != null) {
            pending.canceled = true;
            if (pending.building != null) {
                pending.building.destroy();
                pending.building = null;
            }
        }
        for (var slot : slots) {
            if (slot != null && slot.objects != null) {
                slot.objects.destroy();
                slot.objects = null;
            }
            if (slot != null) {
                if (slot.buffer != INVALID) {
                    bgfx_destroy_dynamic_vertex_buffer(slot.buffer);
                    slot.buffer = INVALID;
                }
                if (slot.texture != INVALID) {
                    bgfx_destroy_texture(slot.texture);
                    slot.texture = INVALID;
                }
            }
        }
        for (short uniform : new short[] {eye, projection, rebase, atlasUniform, sampler, sky}) {
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
        if (skyProgram != INVALID) {
            bgfx_destroy_program(skyProgram);
        }
        destroyObjectPass();
        active = List.of();
        // Callback closure/source pointers are intentionally retained until actual bgfx_shutdown.
    }

    void afterShutdown() {
        requireOwner();
        if (!closed) {
            throw new IllegalStateException("Native resources not retired");
        }
        var pending = uploading.getAndSet(null);
        if (pending != null) {
            pending.source.close();
            pending.finished = true;
        }
        liveBytes = 0;
        if (!callbackFreed) {
            release.free();
            callbackFreed = true;
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
        data.put(new float[] {0, -1, -1, 1, 3, -1, 2, -1, 3}).flip();
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

    private static short program(String profile, String family) throws IOException {
        short vertex = INVALID, fragment = INVALID;
        try {
            vertex = shader(profile, family, "vs_model.bin");
            fragment = shader(profile, family, "fs_model.bin");
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

    private static short shader(String profile, String family, String name) throws IOException {
        String path = "/com/vibeminer/rendering/bgfx/shaders/" + family + profile + "/" + name;
        try (var input = BgfxWorldStreamRenderer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("World streaming shader unavailable");
            }
            byte[] bytes = input.readNBytes((1 << 20) + 1);
            if (bytes.length < 4 || bytes.length > 1 << 20) {
                throw new IOException("Shader size");
            }
            var data = MemoryUtil.memAlloc(bytes.length);
            try {
                return valid(bgfx_create_shader(bgfx_copy(data.put(bytes).flip())));
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Native resource allocation failed");
        }
        return handle;
    }
}
