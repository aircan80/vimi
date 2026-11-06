package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_GREATER;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC1;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC2;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BC3;
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

import com.vibeminer.rendering.world.ObjectTextureFormats;
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
    /**
     * At most twenty-six slots (FAR: 25 active, one staged); a NEAR session allocates ten. Each
     * holds one terrain and water mesh of at most {@link WorldStreamPackedTile#MAX_GEOMETRY_BYTES}
     * (#373) and one mipmapped 512 x 512 RGBA8 atlas (1.40 MB). Exterior objects add at most one
     * validated objects file per slot: up to {@link WorldStreamObjectsFile#MAX_BYTES} of GPU
     * vertices, indices and RGBA8 textures with their full mip chains (the version 3 maximum
     * profile with HIGH chains is about 27 MB). Real tiles stay far below that, so instead of a
     * per-slot worst case the objects share {@link #OBJECT_BUDGET_BYTES}, charged in
     * Stats.objectBytes. The owner's x4 multisampled colour and depth target adds about 11 MB on
     * top (issue #357).
     */
    static final int SLOT_COUNT = WorldStreamState.SLOTS;

    /**
     * STANDARD-tier GPU bytes all resident and building exterior object sets may reserve together:
     * 192 MiB, pinned as a literal: about 12% over FAR's measured mean of about 172 MiB with mip
     * chains. The session's object texture tier selects the budget actually enforced (HIGH: 256
     * MiB). A set that does not fit fails alone and its terrain is kept; late objects load
     * nearest-first, so on FAR + HIGH the outer ring is what may report FAILED.
     */
    static final long OBJECT_BUDGET_BYTES = WorldStreamEnvelope.Session.STANDARD_OBJECT_BUDGET;

    /**
     * Per-slot mesh capacity: one tile's terrain (145 vertices per chunk, up to 256 chunks) plus
     * its optional static water, as WorldStreamPackedTile lays them out (issue #373).
     */
    static final int MAX_VERTICES = WorldStreamPackedTile.MAX_VERTICES,
            MAX_INDICES = WorldStreamPackedTile.MAX_INDICES;

    /** Constant 0.40 diagonal blend factor for diagnostic water over opaque terrain. */
    static final int WATER_BLEND_FACTOR = 0x66666666;

    static final int MAX_TICKETS = 64;

    /** One geometry upload slice in bytes: whole vertices and whole indices. */
    static final int GEOMETRY_CHUNK = 6144 * WorldStreamPackedTile.VERTEX_BYTES;

    private static final short INVALID = -1;

    /**
     * Anisotropic over the worker-built mip chain with linear mips (issues #174, #357; the owner
     * resets with BGFX_RESET_MAXANISOTROPY); clamp keeps each tile from sampling the opposite edge
     * of its own atlas.
     */
    static final int SAMPLER_FLAGS =
            BGFX_SAMPLER_U_CLAMP
                    | BGFX_SAMPLER_V_CLAMP
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;

    record Ticket(long camera, long content, long policy, int width, int height) {}

    /**
     * objectBytes: issued GPU bytes of all resident and building exterior object sets;
     * compressedImages and decodedImages: object textures uploaded as their own BC blocks, and
     * compressed ones decoded to RGBA8 because this backend does not sample their format.
     */
    record Stats(
            long callbacks,
            int references,
            long liveUploadBytes,
            long peakUploadBytes,
            int peakStepBytes,
            long maximumStepNanos,
            long objectBytes,
            long compressedImages,
            long decodedImages) {}

    /**
     * Diagnostic override: when this system property is {@code true}, every compressed object
     * texture takes the RGBA8 decode path, as on a backend without BC sampling.
     */
    static final String OBJECTS_RGBA8_PROPERTY = "vimi.world.objects.rgba8";

    /**
     * Exterior objects of the last drawn frame: placements tested in the opaque/cutout pass, how
     * many of them the CPU cull skipped, object draw submits over both passes, and how many times a
     * camera uniform (eye, projection, rebase, for terrain and objects) was set.
     */
    record FrameCounts(
            int placements,
            int culled,
            int objectSubmits,
            int cameraUniforms,
            int chunks,
            int chunksCulled,
            int terrainSubmits) {}

    /**
     * Most bytes one step call may issue: two geometry chunks plus one atlas step for terrain, or
     * one object step. Exceeding it is a planning bug, so both step paths treat it as fatal.
     */
    static final int STEP_BOUND = 262144;

    /** Bounded object upload slice: a whole step, since object steps never share one. */
    static final int OBJECT_CHUNK = STEP_BOUND;

    static final class Upload {
        private final long tag;
        private final WorldStreamState.Lease lease;
        private final WorldStreamPackedTile.Source source;
        private final AtomicLong issued = new AtomicLong(), released = new AtomicLong();
        private final AtomicInteger references = new AtomicInteger();
        private int geometryAt, atlasStep, splatStep, splatSteps, tickets;

        // Whether this upload's slot inputs (mask chain, table) go up; shared layers always do.
        private boolean splatSlot;

        // The mask chain's upload plan (RGBA8 slices of at most STEP_BYTES), or empty.
        private List<List<WorldStreamObjectMips.Slice>> maskPlan = List.of();
        private boolean canceled, finished, successful;
        private volatile String failure;
        // Inline objects named by the tile header; the build owns their packed input.
        private final ObjectsBuild objects;
        private final int initialObjectsState;

        private Upload(long tag, WorldStreamPackedTile.Source source) {
            this.tag = tag;
            this.source = source;
            lease = source.header.lease();
            atlasStep = source.atlas == null ? WorldStreamAtlasMips.STEPS.size() : 0;
            initialObjectsState = source.objectsState;
            objects =
                    source.objectsState == 1 && source.objects != null
                            ? new ObjectsBuild(source.objects)
                            : null;
            source.objects = null;
        }

        /** -1 outside the exterior profile; else 0 none, 1 drawn, 2 failed (terrain kept). */
        int objectsState() {
            return objects != null && objects.failed ? 2 : initialObjectsState;
        }

        private boolean objectsPending() {
            return objects != null && objects.pending();
        }

        private void closeInputs() {
            source.close();
            if (objects != null) {
                objects.closeSource();
            }
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
            return source.payloadBytes() + (objects == null ? 0 : objects.inputBytes());
        }

        WorldStreamState.Lease lease() {
            return lease;
        }
    }

    private static final class Slot {
        short vertices = INVALID, indices = INVALID, texture = INVALID;
        // Splatting inputs (issue #174 step 2), created on first use: mask chain and layer table.
        short splatAlpha = INVALID, splatTable = INVALID;

        // Side of splatAlpha (512 or 1024, by the tile's tier); 0 while it does not exist.
        int splatMaskSide;
        boolean splatted;
        WorldStreamState.Lease lease;
        int triangles, water, x, y;
        int terrainVertices, waterVertices, terrainIndices, waterIndices;

        /** Terrain cull runs (first index, count) and their world spheres. */
        int[] runs = new int[0];

        double[] runSpheres = new double[0];

        /** Per-frame cull scratch: run visibility and merged index ranges. */
        boolean[] visible = new boolean[0];

        int[] ranges = new int[0];
        float anchorX, anchorY;
        boolean textured, ready;
        SlotObjects objects;
    }

    /**
     * Bounded building of one tile's objects: resource creation, then vertex and index slices and
     * image mip-chain steps (WorldStreamObjectMips) of at most OBJECT_CHUNK bytes, each a
     * bgfx_copy. Owns its packed input until the last slice is issued; any failure destroys only
     * this build, including a texture whose chain is only partly filled.
     */
    private static final class ObjectsBuild {
        private WorldStreamPackedObjects source;
        private SlotObjects building;
        private int vertexAt, indexAt, image;

        /** The current image's upload plan, or null before its texture is created. */
        private List<List<WorldStreamObjectMips.Slice>> imagePlan;

        /** The current image's chain as uploaded: its packed input, or its RGBA8 decode. */
        private java.nio.ByteBuffer imageData;

        /** The current image's upload format ({@link ObjectTextureFormats}). */
        private int imageFormat;

        private int imageStep;
        private boolean failed, budget;

        ObjectsBuild(WorldStreamPackedObjects source) {
            this.source = java.util.Objects.requireNonNull(source);
        }

        boolean pending() {
            return source != null;
        }

        long inputBytes() {
            return source == null ? 0 : source.payloadBytes();
        }

        long builtBytes() {
            return building == null ? 0 : building.bytes;
        }

        void closeSource() {
            imageData = null; // may slice the packed native input freed below
            if (source != null) {
                source.close();
                source = null;
            }
        }

        /** Drops everything without attaching; idempotent. */
        void abandon() {
            if (building != null) {
                building.destroy();
                building = null;
            }
            closeSource();
        }

        void fail() {
            abandon();
            failed = true;
        }

        SlotObjects take() {
            if (pending() || failed || building == null) {
                throw new IllegalStateException("Objects build not complete");
            }
            var result = building;
            building = null;
            return result;
        }
    }

    /**
     * Late objects for an already staged or active slot generation, delivered after its terrain.
     * They attach only if that exact generation is still active when the last slice is issued.
     */
    static final class LateUpload {
        private final WorldStreamState.Lease lease;
        private final ObjectsBuild build;
        private boolean dropped;

        private LateUpload(WorldStreamState.Lease lease, ObjectsBuild build) {
            this.lease = lease;
            this.build = build;
        }

        WorldStreamState.Lease lease() {
            return lease;
        }

        /** True once no further slice is needed: built, failed or dropped. */
        boolean ready() {
            return !build.pending();
        }
    }

    /** One tile's uploaded exterior objects; dropped with its slot generation. */
    private static final class SlotObjects {
        final int[] models, meshes, ranges, placementModels, images;
        final double[] placements;

        /** Per placement world bounding sphere, tested before any of its draws. */
        final double[] spheres;

        final short[] textures;

        /** Per model: whether any of its ranges is alpha blended (drawn in the sorted pass). */
        final boolean[] blendedModels;

        /** Whether any model is blended. */
        final boolean anyBlended;

        short vertices = INVALID, indices = INVALID;
        long bytes;

        /** Full GPU size reserved against OBJECT_BUDGET_BYTES until destroyed. */
        long reserved;

        SlotObjects(WorldStreamPackedObjects source) {
            models = source.models;
            meshes = source.meshes;
            ranges = source.ranges;
            placementModels = source.placementModels;
            images = source.images;
            placements = source.placements;
            spheres = source.spheres;
            textures = new short[source.imageCount()];
            java.util.Arrays.fill(textures, INVALID);
            blendedModels = new boolean[models.length / 2];
            for (int m = 0; m < blendedModels.length; m++) {
                for (int mesh = models[m * 2]; mesh < models[m * 2] + models[m * 2 + 1]; mesh++) {
                    for (int r = meshes[mesh * 6 + 4];
                            r < meshes[mesh * 6 + 4] + meshes[mesh * 6 + 5];
                            r++) {
                        blendedModels[m] |=
                                ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3]
                                        == WorldStreamObjectsFile.BLENDED;
                    }
                }
            }
            boolean any = false;
            for (boolean blended : blendedModels) {
                any |= blended;
            }
            anyBlended = any;
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
            reserved = 0;
        }
    }

    private final Thread owner = Thread.currentThread();
    // Reused by the blended pass every frame; grows to the most blended placements seen.
    private long[] blendKeys = new long[64];
    private final WorldStreamEnvelope.Session session;
    private final Slot[] slots;
    // The session tier's shared objects budget; only a native proof lowers it.
    private long objectBudget;
    private final AtomicReference<Upload> uploading = new AtomicReference<>();
    private final AtomicLong callbackCount = new AtomicLong();
    private final AtomicReference<String> callbackFailure = new AtomicReference<>();
    private final BGFXReleaseFunctionCallback release =
            BGFXReleaseFunctionCallback.create(this::released);
    private short program = INVALID, skyProgram = INVALID, template = INVALID;
    private short eye = INVALID, projection = INVALID, rebase = INVALID, atlasUniform = INVALID;
    private short sampler = INVALID, sky = INVALID;
    // Splat samplers on stages 1 to 3 and u_worldSplat (issue #174 step 2b-2,
    // world-stream/splat.sh).
    private short splatLayersSampler = INVALID, splatMaskSampler = INVALID;
    private short splatTableSampler = INVALID, splatUniform = INVALID;
    // Bound to the layer stage while the shared array does not exist: every declared sampler is
    // bound on every draw, so no backend sees an empty slot. Two layers, so it is an array view.
    private short splatDummyArray = INVALID;
    // Fog shared by terrain, objects and the sky's horizon (WorldFog).
    private short worldFog = INVALID, worldFogRange = INVALID, worldSkyTop = INVALID;
    private short skyTop = INVALID, skyHorizon = INVALID, skyView = INVALID;
    private short wmoFog = INVALID, wmoFogRange = INVALID, wmoSkyTop = INVALID;
    // Read once per frame; #177 sets it from the light tables (fog(WorldFog)).
    private WorldFog fog = WorldFog.STREAM;
    // Samples of the target this renderer draws into; above 1, cutouts use alpha to coverage.
    private int targetSamples = 1;
    // Optional exterior objects pass (existing world-wmo shaders); INVALID program disables it.
    private short wmoProgram = INVALID, wmoEye = INVALID, wmoProjection = INVALID;
    private short wmoModel = INVALID, wmoNormal = INVALID, wmoSampler = INVALID;
    private short wmoMaterial = INVALID;
    // Relative shader family and profile of the objects pass, named in its stderr diagnostics.
    private String wmoFamily = "world-wmo/";
    private final ObjectsFailureLog objectsFailures = new ObjectsFailureLog();

    /** Per object texture format: whether it is uploaded as stored (RGBA8 always is). */
    private final boolean[] objectFormats = new boolean[ObjectTextureFormats.BC3 + 1];

    private long compressedImages, decodedImages;

    /** Whether the first completed object set's texture paths have been reported. */
    private boolean objectPathsReported;

    private List<WorldStreamState.Lease> active = List.of();
    private WorldStreamState.Lease staged;
    private LateUpload late;
    private long content, nextTag, liveBytes, peakBytes, maximumStepNanos, objectsRevision;
    private int peakStepBytes;
    private boolean closed, callbackFreed, objectCulling = true;
    private FrameCounts lastFrame = new FrameCounts(0, 0, 0, 0, 0, 0, 0);
    private int frameChunks, frameChunksCulled, frameTerrainSubmits;
    private int framePlacements, frameCulled, frameObjectSubmits, frameCameraUniforms;
    // The u_wmoMaterial class last set this frame, or -1.
    private int frameMaterial;

    BgfxWorldStreamRenderer(WorldStreamEnvelope.Session session) throws IOException {
        this.session = java.util.Objects.requireNonNull(session);
        // A near session allocates fewer slots of the same fixed size; never more than the ceiling.
        slots = new Slot[session.slots()];
        objectBudget = session.objectBudgetBytes();
        try {
            var caps = bgfx_get_caps();
            BgfxTerrainRenderer.requireCapabilities(
                    caps.supported(),
                    caps.homogeneousDepth(),
                    org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT));
            if (!org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                    0, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_NONE)) {
                throw new IOException("World terrain needs mipmapped RGBA8 textures");
            }
            String profile = BgfxModelRenderer.shaderProfile(caps.rendererType());
            boolean forced = Boolean.getBoolean(OBJECTS_RGBA8_PROPERTY);
            objectFormats[ObjectTextureFormats.RGBA8] = true;
            for (int f = ObjectTextureFormats.BC1; f <= ObjectTextureFormats.BC3; f++) {
                objectFormats[f] =
                        !forced
                                && org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                                        0, false, 1, bgfxFormat(f), BGFX_TEXTURE_NONE);
            }
            System.err.println(objectFormatsReport(objectFormats, forced));
            System.err.flush();
            for (int i = 0; i < slots.length; i++) {
                slots[i] = new Slot();
            }
            try (var layout = meshLayout()) {
                for (var slot : slots) {
                    slot.vertices =
                            valid(
                                    bgfx_create_dynamic_vertex_buffer(
                                            MAX_VERTICES, layout, BGFX_BUFFER_NONE));
                    slot.indices =
                            valid(bgfx_create_dynamic_index_buffer(MAX_INDICES, BGFX_BUFFER_NONE));
                    slot.texture =
                            valid(
                                    bgfx_create_texture_2d(
                                            WorldStreamAtlasMips.SIDE,
                                            WorldStreamAtlasMips.SIDE,
                                            true,
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
            splatLayersSampler =
                    valid(bgfx_create_uniform("s_worldSplatLayers", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            splatMaskSampler =
                    valid(bgfx_create_uniform("s_worldSplatMask", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            splatTableSampler =
                    valid(bgfx_create_uniform("s_worldSplatTable", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            splatUniform = valid(bgfx_create_uniform("u_worldSplat", BGFX_UNIFORM_TYPE_VEC4, 1));
            if ((bgfx_get_caps().supported() & org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_2D_ARRAY)
                    != 0) {
                splatDummyArray =
                        valid(
                                bgfx_create_texture_2d(
                                        1,
                                        1,
                                        false,
                                        2,
                                        BGFX_TEXTURE_FORMAT_RGBA8,
                                        BGFX_TEXTURE_NONE,
                                        null,
                                        0));
            }
            sky = valid(bgfx_create_uniform("u_terrainMode", BGFX_UNIFORM_TYPE_VEC4, 1));
            worldFog = valid(bgfx_create_uniform("u_worldFog", BGFX_UNIFORM_TYPE_VEC4, 1));
            worldSkyTop = valid(bgfx_create_uniform("u_worldSkyTop", BGFX_UNIFORM_TYPE_VEC4, 1));
            worldFogRange =
                    valid(bgfx_create_uniform("u_worldFogRange", BGFX_UNIFORM_TYPE_VEC4, 1));
            skyTop = valid(bgfx_create_uniform("u_skyTop", BGFX_UNIFORM_TYPE_VEC4, 1));
            skyHorizon = valid(bgfx_create_uniform("u_skyHorizon", BGFX_UNIFORM_TYPE_VEC4, 1));
            skyView = valid(bgfx_create_uniform("u_skyView", BGFX_UNIFORM_TYPE_VEC4, 1));
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

    /**
     * Optional: on failure every tile's objects report failed while terrain stays intact. The
     * reason, naming the shader family, profile and step, goes to stderr only, never the protocol;
     * the parent logs the redacted stderr tail with the first objects failure of the session.
     */
    private void objectPass(String profile) {
        wmoFamily = "world-wmo/" + profile;
        String step = "RGBA8 textures";
        try {
            if (!org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                    0, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_NONE)) {
                throw new IOException("Exterior objects need mipmapped RGBA8 textures");
            }
            step = "program";
            wmoProgram = program(profile, "world-wmo/");
            step = "uniforms";
            wmoEye = valid(bgfx_create_uniform("u_wmoEye", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoProjection =
                    valid(bgfx_create_uniform("u_wmoProjection", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoModel = valid(bgfx_create_uniform("u_wmoModel", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoNormal = valid(bgfx_create_uniform("u_wmoNormal", BGFX_UNIFORM_TYPE_MAT4, 1));
            wmoSampler = valid(bgfx_create_uniform("s_wmoDiffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            wmoMaterial = valid(bgfx_create_uniform("u_wmoMaterial", BGFX_UNIFORM_TYPE_VEC4, 1));
            wmoFog = valid(bgfx_create_uniform("u_wmoFog", BGFX_UNIFORM_TYPE_VEC4, 1));
            wmoSkyTop = valid(bgfx_create_uniform("u_wmoSkyTop", BGFX_UNIFORM_TYPE_VEC4, 1));
            wmoFogRange = valid(bgfx_create_uniform("u_wmoFogRange", BGFX_UNIFORM_TYPE_VEC4, 1));
        } catch (IOException | RuntimeException failed) {
            destroyObjectPass();
            System.err.println(objectsReason("pass unavailable", wmoFamily, step, failed));
            System.err.flush();
        }
    }

    private void destroyObjectPass() {
        if (wmoProgram != INVALID) {
            bgfx_destroy_program(wmoProgram);
        }
        for (short uniform :
                new short[] {
                    wmoEye,
                    wmoProjection,
                    wmoModel,
                    wmoNormal,
                    wmoSampler,
                    wmoMaterial,
                    wmoFog,
                    wmoSkyTop,
                    wmoFogRange
                }) {
            if (uniform != INVALID) {
                bgfx_destroy_uniform(uniform);
            }
        }
        wmoProgram = wmoEye = wmoProjection = wmoModel = wmoNormal = wmoSampler = INVALID;
        wmoMaterial = wmoFog = wmoFogRange = wmoSkyTop = INVALID;
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
            long payload = source.payloadBytes();
            var upload = new Upload(++nextTag, source);
            var slot = slots[lease.slot()];
            slot.ready = false;
            slot.lease = lease;
            slot.splatted = false;
            upload.splatSteps = splatSteps(upload);
            // A new slot generation drops the previous generation's objects, late ones included.
            if (slot.objects != null) {
                slot.objects.destroy();
                slot.objects = null;
            }
            dropLate(lease.slot());
            if (upload.objectsPending() && wmoProgram == INVALID) {
                upload.objects.fail();
            }
            uploading.set(upload);
            liveBytes = payload;
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
                // Vertices then indices; a slice never crosses into the index section.
                int split = upload.source.indexOffset();
                boolean vertex = upload.geometryAt < split;
                int end = vertex ? split : upload.source.geometry.capacity();
                int count = Math.min(GEOMETRY_CHUNK, end - upload.geometryAt);
                var data =
                        upload.source
                                .geometry
                                .duplicate()
                                .position(upload.geometryAt)
                                .limit(upload.geometryAt + count)
                                .slice();
                var slot = slots[upload.lease.slot()];
                if (vertex) {
                    bgfx_update_dynamic_vertex_buffer(
                            slot.vertices,
                            upload.geometryAt / WorldStreamPackedTile.VERTEX_BYTES,
                            reference(upload, data));
                } else {
                    bgfx_update_dynamic_index_buffer(
                            slot.indices, (upload.geometryAt - split) / 2, reference(upload, data));
                }
                upload.geometryAt += count;
                bytes += count;
            }
            if (upload.atlasStep == WorldStreamAtlasMips.STEPS.size()
                    && upload.splatStep < upload.splatSteps
                    && (bytes == 0 || System.nanoTime() - started < 2_000_000)) {
                // Several splat steps per call (a HIGH mask is about 86 slices), never past the
                // step bound: each issues at most MAX_SPLAT_STEP_BYTES.
                do {
                    bytes += stepSplat(upload);
                } while (upload.splatStep < upload.splatSteps
                        && bytes + MAX_SPLAT_STEP_BYTES <= STEP_BOUND
                        && System.nanoTime() - started < 2_000_000);
            } else if (upload.atlasStep < WorldStreamAtlasMips.STEPS.size()
                    && (bytes == 0 || System.nanoTime() - started < 2_000_000)) {
                for (var slice : WorldStreamAtlasMips.STEPS.get(upload.atlasStep)) {
                    var data =
                            upload.source
                                    .atlas
                                    .duplicate()
                                    .position(slice.offset())
                                    .limit(slice.offset() + slice.bytes())
                                    .slice();
                    int side = WorldStreamAtlasMips.side(slice.level());
                    bgfx_update_texture_2d(
                            slots[upload.lease.slot()].texture,
                            0,
                            slice.level(),
                            0,
                            slice.row(),
                            side,
                            slice.rows(),
                            slice.referenced() ? reference(upload, data) : bgfx_copy(data),
                            side * 4);
                    bytes += slice.bytes();
                }
                upload.atlasStep++;
            }
        }
        if (!upload.canceled && bytes == 0 && terrainSubmitted(upload) && upload.objectsPending()) {
            bytes = stepObjects(upload.objects);
        }
        maximumStepNanos = Math.max(maximumStepNanos, System.nanoTime() - started);
        peakStepBytes = Math.max(peakStepBytes, bytes);
        if (bytes > STEP_BOUND) {
            throw new IllegalStateException("Upload slice escaped bound");
        }
        pollUploads();
        return bytes;
    }

    private static boolean terrainSubmitted(Upload upload) {
        return (upload.source.geometry == null
                        || upload.geometryAt == upload.source.geometry.capacity())
                && upload.atlasStep == WorldStreamAtlasMips.STEPS.size()
                && upload.splatStep == upload.splatSteps;
    }

    /**
     * Shared splat layer array: 255 layers of 256 x 256 BC1 with mips (about 10.6 MiB), or of 128 x
     * 128 RGBA8 (about 21.3 MiB) where BC1 arrays are not sampled, created on the first packet that
     * carries splatting inputs, only where 2D arrays are supported.
     */
    private short splatArray = INVALID;

    /** Whether the layer array holds BC1 blocks (else the RGBA8 128 x 128 fallback). */
    private boolean splatBlocks;

    /**
     * Diagnostic override: when this system property is {@code true}, the splat layer array takes
     * its RGBA8 fallback, as on a backend without BC1 array sampling.
     */
    static final String SPLAT_RGBA8_PROPERTY = "vimi.world.splat.rgba8";

    private boolean splatSupported = true;

    /** Proof observation: whether {@code slot}'s current generation uploaded splat inputs. */
    boolean splatted(int slot) {
        requireOwner();
        return slots[slot].splatted;
    }

    /** Proof observation: whether the shared splat layer array exists. */
    boolean splatArrayCreated() {
        requireOwner();
        return splatArray != INVALID;
    }

    private boolean splatReady() {
        if (splatArray != INVALID) {
            return true;
        }
        if (!splatSupported
                || (bgfx_get_caps().supported() & org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_2D_ARRAY)
                        == 0) {
            splatSupported = false;
            return false;
        }
        splatBlocks =
                !Boolean.getBoolean(SPLAT_RGBA8_PROPERTY)
                        && org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid(
                                0,
                                false,
                                WorldStreamDiffuseArray.CAPACITY,
                                BGFX_TEXTURE_FORMAT_BC1,
                                BGFX_TEXTURE_NONE);
        int side =
                splatBlocks ? WorldStreamDiffuseArray.SIDE : WorldStreamDiffuseArray.FALLBACK_SIDE;
        splatArray =
                bgfx_create_texture_2d(
                        side,
                        side,
                        true,
                        WorldStreamDiffuseArray.CAPACITY,
                        splatBlocks ? BGFX_TEXTURE_FORMAT_BC1 : BGFX_TEXTURE_FORMAT_RGBA8,
                        BGFX_TEXTURE_NONE,
                        null,
                        0);
        if (splatArray == INVALID) {
            splatSupported = false;
            return false;
        }
        System.err.println(splatReport(splatBlocks, Boolean.getBoolean(SPLAT_RGBA8_PROPERTY)));
        System.err.flush();
        return true;
    }

    /** Steps of one packet's splat upload: mask chain, table, then each new layer. */
    private int splatSteps(Upload upload) {
        var source = upload.source;
        upload.splatSlot = false;
        if (source.splatAlpha == null || !splatReady()) {
            return 0;
        }
        var slot = slots[upload.lease.slot()];
        upload.splatSlot = true;
        if (slot.splatAlpha != INVALID && slot.splatMaskSide != source.splatMaskSide) {
            // The tier changed since this slot's last splat tile: a mask of the new side.
            destroySplatTextures(slot);
        }
        if (slot.splatAlpha == INVALID) {
            try {
                createSplatTextures(slot, source.splatMaskSide);
            } catch (IOException unavailable) {
                // Optional: this slot keeps its atlas, and no half-created pair survives. Its new
                // shared layers still go up: the parent records them resident either way.
                if (slot.splatAlpha != INVALID) {
                    bgfx_destroy_texture(slot.splatAlpha);
                }
                slot.splatAlpha = slot.splatTable = INVALID;
                slot.splatMaskSide = 0;
                upload.splatSlot = false;
            }
        }
        upload.maskPlan =
                upload.splatSlot
                        ? WorldStreamObjectMips.plan(
                                ObjectTextureFormats.RGBA8,
                                source.splatMaskSide,
                                source.splatMaskSide,
                                WorldStreamAtlasMips.STEP_BYTES)
                        : List.of();
        return splatStepCount(upload.maskPlan.size(), upload.splatSlot, source.splatLayers.length);
    }

    private void destroySplatTextures(Slot slot) {
        for (short texture : new short[] {slot.splatAlpha, slot.splatTable}) {
            if (texture != INVALID) {
                bgfx_destroy_texture(texture);
            }
        }
        slot.splatAlpha = slot.splatTable = INVALID;
        slot.splatMaskSide = 0;
    }

    /**
     * RGBA8 bytes of every slot's splat mask chain now on the GPU; proof and budget observation.
     */
    long splatMaskBytes() {
        long bytes = 0;
        for (var slot : slots) {
            if (slot.splatAlpha != INVALID) {
                bytes +=
                        com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(
                                        slot.splatMaskSide, slot.splatMaskSide)
                                * 4L;
            }
        }
        return bytes;
    }

    /**
     * Steps of one packet's splat upload: the slot's mask chain and table when its textures exist,
     * then every new shared layer, which is uploaded even when the slot's textures are not.
     */
    static int splatStepCount(int maskSteps, boolean slotInputs, int layers) {
        return (slotInputs ? maskSteps + 1 : 0) + layers;
    }

    /** Most bytes one splat step issues: a mask slice, a BC1 layer, or an RGBA8 fallback layer. */
    static final int MAX_SPLAT_STEP_BYTES =
            Math.max(
                    WorldStreamAtlasMips.STEP_BYTES,
                    Math.max(
                            WorldStreamDiffuseArray.LAYER_BYTES,
                            WorldStreamDiffuseArray.FALLBACK_LAYER_BYTES));

    private void createSplatTextures(Slot slot, int maskSide) throws IOException {
        slot.splatMaskSide = maskSide;
        slot.splatAlpha =
                valid(
                        bgfx_create_texture_2d(
                                maskSide,
                                maskSide,
                                true,
                                1,
                                BGFX_TEXTURE_FORMAT_RGBA8,
                                BGFX_TEXTURE_NONE,
                                null,
                                0));
        slot.splatTable =
                valid(
                        bgfx_create_texture_2d(
                                16,
                                16,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_RGBA8,
                                BGFX_TEXTURE_NONE,
                                null,
                                0));
    }

    /** One bounded splat slice; every slice is a bgfx_copy, so it holds no release ticket. */
    private int stepSplat(Upload upload) {
        var source = upload.source;
        var slot = slots[upload.lease.slot()];
        int step = upload.splatStep++;
        int alphaSteps = upload.splatSlot ? upload.maskPlan.size() : 0;
        int slotSteps = upload.splatSlot ? alphaSteps + 1 : 0;
        if (step < alphaSteps) {
            int bytes = 0;
            for (var slice : upload.maskPlan.get(step)) {
                bgfx_update_texture_2d(
                        slot.splatAlpha,
                        0,
                        slice.level(),
                        0,
                        slice.row(),
                        Math.max(1, source.splatMaskSide >> slice.level()),
                        slice.rows(),
                        copyHeap(source.splatAlpha.slice(slice.offset(), slice.bytes())),
                        slice.pitch());
                bytes += slice.bytes();
            }
            return bytes;
        }
        if (step < slotSteps) {
            bgfx_update_texture_2d(
                    slot.splatTable,
                    0,
                    0,
                    0,
                    0,
                    16,
                    16,
                    copyHeap(java.nio.ByteBuffer.wrap(source.splatTable)),
                    16 * 4);
            return source.splatTable.length;
        }
        int index = step - slotSteps;
        int layer = source.splatLayers[index];
        var chain = source.splatChains[index];
        if (splatBlocks) {
            // The layer's BC1 chain as carried, in block rows (one step: 43,704 bytes).
            int bytes = 0;
            for (var stepSlices :
                    WorldStreamObjectMips.plan(
                            ObjectTextureFormats.BC1,
                            WorldStreamDiffuseArray.SIDE,
                            WorldStreamDiffuseArray.SIDE,
                            OBJECT_CHUNK)) {
                for (var slice : stepSlices) {
                    bgfx_update_texture_2d(
                            splatArray,
                            layer,
                            slice.level(),
                            0,
                            slice.row(),
                            com.vibeminer.rendering.world.TerrainLayerBlocks.side(slice.level()),
                            slice.rows(),
                            copyHeap(chain.slice(slice.offset(), slice.bytes())),
                            updatePitch(ObjectTextureFormats.BC1, slice));
                    bytes += slice.bytes();
                }
            }
            return bytes;
        }
        var rgba = WorldStreamDiffuseArray.fallback(chain);
        for (int level = 0; level < WorldStreamDiffuseArray.FALLBACK_LEVELS; level++) {
            int side = WorldStreamDiffuseArray.fallbackSide(level);
            bgfx_update_texture_2d(
                    splatArray,
                    layer,
                    level,
                    0,
                    0,
                    side,
                    side,
                    copyHeap(
                            rgba.slice(
                                    WorldStreamDiffuseArray.fallbackOffset(level),
                                    side * side * 4)),
                    side * 4);
        }
        return WorldStreamDiffuseArray.FALLBACK_LAYER_BYTES;
    }

    /** Whether the shared splat layer array holds BC1 blocks; proof observation. */
    boolean splatBlocks() {
        requireOwner();
        return splatArray != INVALID && splatBlocks;
    }

    /** One stderr line naming the splat layer array's format, when it is created. */
    static String splatReport(boolean blocks, boolean forced) {
        return "World splat layers: "
                + (blocks
                        ? "BC1 compressed, " + WorldStreamDiffuseArray.SIDE
                        : "decoded to RGBA8, " + WorldStreamDiffuseArray.FALLBACK_SIDE)
                + " px, "
                + WorldStreamDiffuseArray.CAPACITY
                + " layers"
                + (forced ? " (" + SPLAT_RGBA8_PROPERTY + ")" : "");
    }

    /** bgfx_copy reads native memory: stage a heap slice through a short-lived native buffer. */
    private static org.lwjgl.bgfx.BGFXMemory copyHeap(java.nio.ByteBuffer heap) {
        var direct = MemoryUtil.memAlloc(heap.remaining());
        try {
            direct.put(heap.duplicate()).flip();
            return bgfx_copy(direct);
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    /**
     * One bounded object slice: resource creation, then vertex and index copies and image mip-chain
     * steps of at most OBJECT_CHUNK bytes. Any failure drops only this build's objects.
     */
    private int stepObjects(ObjectsBuild build) {
        var source = build.source;
        String step = "buffers";
        try {
            if (build.building == null) {
                long need = source.payloadBytes();
                if (!objectsFit(objectBudget, reservedObjectBytes(), need)) {
                    build.budget = true;
                    build.fail();
                    return 0;
                }
                var built = new SlotObjects(source);
                built.reserved = need;
                build.building = built;
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
            var built = build.building;
            step = "vertices";
            if (build.vertexAt < source.vertices.capacity()) {
                int count = Math.min(OBJECT_CHUNK, source.vertices.capacity() - build.vertexAt);
                bgfx_update_dynamic_vertex_buffer(
                        built.vertices,
                        build.vertexAt / WorldStreamObjectsFile.VERTEX_BYTES,
                        bgfx_copy(source.vertices.slice(build.vertexAt, count)));
                build.vertexAt += count;
                return count;
            }
            step = "indices";
            if (build.indexAt < source.indices.capacity()) {
                int count = Math.min(OBJECT_CHUNK, source.indices.capacity() - build.indexAt);
                bgfx_update_dynamic_index_buffer(
                        built.indices,
                        build.indexAt / 2,
                        bgfx_copy(source.indices.slice(build.indexAt, count)));
                build.indexAt += count;
                return count;
            }
            step = "textures";
            if (build.image < source.imageCount()) {
                int image = build.image;
                int width = source.width(image), height = source.height(image);
                if (build.imagePlan == null) {
                    // One mipmapped texture per image, created empty and owned by the build at
                    // once, so a partly filled chain is destroyed with it. A 256 x 256 RGBA8
                    // chain exceeds one step, so levels are filled in planned row bands; BC
                    // chains are filled in block rows.
                    int stored = source.format(image);
                    int format = uploadFormat(stored, objectFormats);
                    var chain =
                            source.pixels.slice(
                                    source.imageOffset(image),
                                    ObjectTextureFormats.chainBytes(stored, width, height));
                    if (format != stored) {
                        // This backend does not sample the stored blocks: decode the chain.
                        byte[] rgba =
                                ObjectTextureFormats.decodeChain(stored, width, height, chain, 0);
                        chain = java.nio.ByteBuffer.allocateDirect(rgba.length).put(rgba).flip();
                        decodedImages++;
                    } else if (ObjectTextureFormats.compressed(format)) {
                        compressedImages++;
                    }
                    build.imageData = chain;
                    build.imageFormat = format;
                    build.imagePlan =
                            WorldStreamObjectMips.plan(format, width, height, OBJECT_CHUNK);
                    build.imageStep = 0;
                    built.textures[image] =
                            valid(
                                    bgfx_create_texture_2d(
                                            width,
                                            height,
                                            true,
                                            1,
                                            bgfxFormat(format),
                                            BGFX_TEXTURE_NONE,
                                            null,
                                            0));
                }
                int count = 0;
                for (var slice : build.imagePlan.get(build.imageStep)) {
                    bgfx_update_texture_2d(
                            built.textures[image],
                            0,
                            slice.level(),
                            0,
                            slice.row(),
                            com.vibeminer.rendering.world.ObjectTextureMips.levelWidth(
                                    width, slice.level()),
                            slice.rows(),
                            bgfx_copy(build.imageData.slice(slice.offset(), slice.bytes())),
                            updatePitch(build.imageFormat, slice));
                    count += slice.bytes();
                }
                built.bytes += count;
                if (++build.imageStep == build.imagePlan.size()) {
                    build.imagePlan = null;
                    build.imageData = null;
                    build.image++;
                }
                return count;
            }
            // Every slice is a bgfx-owned copy; the packed native input can retire now.
            build.closeSource();
            if (!objectPathsReported) {
                // Once per session: how the first complete set's textures reached the GPU.
                objectPathsReported = true;
                System.err.println(
                        "World object textures uploaded: "
                                + compressedImages
                                + " compressed, "
                                + decodedImages
                                + " decoded to RGBA8");
                System.err.flush();
            }
            return 0;
        } catch (IOException | RuntimeException failed) {
            build.fail();
            long count = objectsFailures.admit(step, failed);
            if (count > 0) {
                System.err.println(
                        objectsReason("build failed", wmoFamily, step, failed)
                                + " ("
                                + count
                                + (count == 1 ? " object build" : " object builds")
                                + " failed this session)");
                System.err.flush();
            }
            return 0;
        }
    }

    /**
     * Takes ownership of one late objects set for {@code lease}. If this renderer's slot does not
     * hold that generation, or the objects pass is unavailable, the upload is born dropped or
     * failed and issues nothing.
     */
    LateUpload beginLate(WorldStreamState.Lease lease, WorldStreamPackedObjects packed) {
        java.util.Objects.requireNonNull(packed);
        try {
            requireOwner();
            requireOpen();
            java.util.Objects.requireNonNull(lease);
            if (late != null) {
                throw new IllegalStateException("One late objects upload already held");
            }
        } catch (RuntimeException | Error refused) {
            packed.close();
            throw refused;
        }
        var upload = new LateUpload(lease, new ObjectsBuild(packed));
        if (!lateTarget(lease)) {
            upload.dropped = true;
            upload.build.abandon();
        } else if (wmoProgram == INVALID) {
            upload.build.fail();
        }
        late = upload;
        return upload;
    }

    /** One bounded late slice; the caller gives terrain uploads priority. */
    int stepLate(LateUpload upload) {
        requireOwner();
        requireOpen();
        requireLate(upload);
        if (upload.ready()) {
            return 0;
        }
        if (!upload.dropped && !lateTarget(upload.lease)) {
            // Retired by a commit since it began: stop building for a generation never drawn.
            upload.dropped = true;
            upload.build.abandon();
            return 0;
        }
        long started = System.nanoTime();
        int bytes = stepObjects(upload.build);
        maximumStepNanos = Math.max(maximumStepNanos, System.nanoTime() - started);
        peakStepBytes = Math.max(peakStepBytes, bytes);
        if (bytes > OBJECT_CHUNK) {
            throw new IllegalStateException("Object slice escaped bound");
        }
        return bytes;
    }

    /**
     * Retires a ready late upload. It attaches only when {@code live} (the child state still admits
     * that generation) and this renderer's slot still holds that exact active generation without
     * objects. Otherwise the built resources are destroyed and the result is DROPPED.
     */
    WorldStreamStatus.LateOutcome finishLate(LateUpload upload, boolean live) {
        requireOwner();
        requireOpen();
        requireLate(upload);
        if (!upload.ready()) {
            throw new IllegalStateException("Late objects upload still building");
        }
        late = null;
        if (upload.dropped || !live || !lateTarget(upload.lease)) {
            upload.build.abandon();
            return WorldStreamStatus.LateOutcome.DROPPED;
        }
        if (upload.build.failed) {
            return upload.build.budget
                    ? WorldStreamStatus.LateOutcome.BUDGET
                    : WorldStreamStatus.LateOutcome.FAILED;
        }
        slots[upload.lease.slot()].objects = upload.build.take();
        objectsRevision++;
        return WorldStreamStatus.LateOutcome.ATTACHED;
    }

    /** The fog and sky of the next frames; #177 feeds it from the light tables. */
    void fog(WorldFog value) {
        requireOwner();
        fog = java.util.Objects.requireNonNull(value);
    }

    /** The sample count of the target the owner created; above 1 enables alpha to coverage. */
    void targetSamples(int samples) {
        requireOwner();
        if (samples < 1) {
            throw new IllegalArgumentException("Target samples");
        }
        targetSamples = samples;
    }

    /** Object counts of the last {@link #draw}. */
    FrameCounts frameCounts() {
        requireOwner();
        return lastFrame;
    }

    /**
     * Proof seam: false draws every placement, as before CPU culling, so a proof can compare draw
     * counts and pixels with culling on and off. Production never turns it off.
     */
    void objectCulling(boolean enabled) {
        requireOwner();
        objectCulling = enabled;
    }

    /** Changes whenever late objects become drawable, so an unchanged camera redraws once. */
    long objectsRevision() {
        return objectsRevision;
    }

    private boolean lateTarget(WorldStreamState.Lease lease) {
        var slot = slots[lease.slot()];
        return lateTarget(lease, slot.ready, slot.lease, slot.objects != null, active);
    }

    /** The exact active generation in its ready slot, still without objects. */
    static boolean lateTarget(
            WorldStreamState.Lease lease,
            boolean ready,
            WorldStreamState.Lease slotLease,
            boolean hasObjects,
            List<WorldStreamState.Lease> active) {
        return ready && lease.equals(slotLease) && !hasObjects && active.contains(lease);
    }

    private void dropLate(int slot) {
        if (late != null && late.lease.slot() == slot && !late.dropped) {
            late.dropped = true;
            late.build.abandon();
        }
    }

    private void requireLate(LateUpload upload) {
        if (upload == null || upload != late) {
            throw new IllegalStateException("Foreign or finished late objects upload");
        }
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
            dropLate(upload.lease.slot());
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
            slot.terrainVertices = upload.source.terrainVertices;
            slot.waterVertices = upload.source.waterVertices;
            slot.terrainIndices = upload.source.terrainIndices;
            slot.waterIndices = upload.source.waterIndices;
            slot.runs = upload.source.runs;
            slot.runSpheres = upload.source.runSpheres;
            // Per-frame cull scratch, sized once per tile generation.
            slot.visible = new boolean[slot.runs.length / 2];
            slot.ranges = new int[slot.runs.length];
            slot.x = upload.source.header.x();
            slot.y = upload.source.header.y();
            staged = upload.lease;
            slot.anchorX = upload.source.header.maxX();
            slot.anchorY = upload.source.header.maxY();
            slot.textured = upload.source.header.outcome() == WorldStreamTileFile.Outcome.TEXTURED;
            if (upload.objects != null && !upload.objects.failed) {
                slot.objects = upload.objects.take();
            }
            slot.ready = true;
            slot.splatted = upload.splatSlot;
            upload.successful = true;
        } else if (upload.objects != null) {
            upload.objects.abandon();
        }
        upload.closeInputs();
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
                || ordered.size() > session.maxActive()) {
            throw new IllegalArgumentException("Commit revision/count");
        }
        boolean[] seen = new boolean[slots.length], coordinates = new boolean[4096];
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
            frameCameraUniforms = 0;
            // Frame-constant uniforms once per frame: bgfx keeps a uniform's value across submits
            // until it is set again, and no other pass writes these handles.
            frameUniform(eye, matrix);
            frameUniform(projection, project);
            frameUniform(rebase, shift);
            if (wmoProgram != INVALID) {
                frameUniform(wmoEye, matrix);
                frameUniform(wmoProjection, project);
                bgfx_set_uniform(wmoFog, stack.floats(fog.fogUniform()), 1);
                bgfx_set_uniform(wmoSkyTop, stack.floats(fog.topUniform()), 1);
                bgfx_set_uniform(wmoFogRange, stack.floats(fog.rangeUniform()), 1);
            }
            bgfx_set_uniform(worldFog, stack.floats(fog.fogUniform()), 1);
            bgfx_set_uniform(worldSkyTop, stack.floats(fog.topUniform()), 1);
            bgfx_set_uniform(worldFogRange, stack.floats(fog.rangeUniform()), 1);
            var view =
                    objectCulling
                            ? WorldStreamObjectCulling.View.of(camera, width, height, fog.cut())
                            : null;
            frameChunks = frameChunksCulled = frameTerrainSubmits = 0;
            for (var lease : active) {
                var slot = slots[lease.slot()];
                if (slot.triangles == 0) {
                    continue;
                }
                bgfx_set_uniform(
                        atlasUniform,
                        stack.floats(slot.anchorX, slot.anchorY, 3f / 1600, slot.textured ? 1 : 0),
                        1);
                bgfx_set_uniform(
                        splatUniform,
                        stack.floats(splatDrawn(slot) ? 1 : 0, slot.splatMaskSide, 0, 0),
                        1);
                drawTerrain(slot, view);
            }
            // One fixed set of object uniform buffers per frame: per-placement work allocates
            // nothing on the frame's MemoryStack, whatever the number of placements.
            var frame = wmoProgram == INVALID ? null : ObjectFrame.on(stack, targetSamples > 1);
            framePlacements = frameCulled = frameObjectSubmits = 0;
            frameMaterial = -1;
            if (wmoProgram != INVALID) {
                for (var lease : active) {
                    var objects = slots[lease.slot()].objects;
                    if (objects != null) {
                        drawObjects(frame, view, objects, origin);
                    }
                }
            }
            // Sky after every opaque surface: reversed depth clears to 0 and every drawn surface
            // is nearer, so GEQUAL at depth 0 shades only the pixels nothing covered. It must
            // come before water, which writes depth (the sky would then be rejected under it),
            // and before blended objects, which blend over whatever is behind them.
            bgfx_set_uniform(
                    sky,
                    stack.floats(width, height, bgfx_get_caps().originBottomLeft() ? 1 : 0, 1),
                    1);
            // The horizon gradient: the sky meets the terrain and object fog in one colour.
            bgfx_set_uniform(skyTop, stack.floats(fog.topUniform()), 1);
            bgfx_set_uniform(skyHorizon, stack.floats(fog.horizonUniform()), 1);
            bgfx_set_uniform(skyView, stack.floats(fog.viewUniform(camera.pitch(), width)), 1);
            bgfx_set_vertex_buffer(0, template, 0, 3);
            bgfx_set_state(SKY_STATE, 0);
            bgfx_submit(0, skyProgram, 0, BGFX_DISCARD_ALL);
            // Static diagnostic water after ALL opaque terrain: nearest flat surface wins under
            // strict reversed depth, so coincident duplicates cannot blend twice. RGB only keeps
            // the opaque output alpha.
            for (var lease : active) {
                var slot = slots[lease.slot()];
                if (slot.water == 0) {
                    continue;
                }
                bgfx_set_uniform(atlasUniform, stack.floats(slot.anchorX, slot.anchorY, 0, 0), 1);
                bgfx_set_uniform(splatUniform, stack.floats(0, 0, 0, 0), 1);
                bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
                bindSplat(slot);
                // Water vertices follow the terrain's; its indices are relative to them.
                bgfx_set_dynamic_vertex_buffer(
                        0, slot.vertices, slot.terrainVertices, slot.waterVertices);
                bgfx_set_dynamic_index_buffer(slot.indices, slot.terrainIndices, slot.waterIndices);
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
            // Alpha-blended object ranges last, after water, farthest placement first.
            if (wmoProgram != INVALID) {
                drawBlended(frame, view, origin);
            }
        }
        lastFrame =
                new FrameCounts(
                        framePlacements,
                        frameCulled,
                        frameObjectSubmits,
                        frameCameraUniforms,
                        frameChunks,
                        frameChunksCulled,
                        frameTerrainSubmits);
        return new Ticket(cameraRevision, content, policy, width, height);
    }

    /** Sky state: colour and alpha, no depth write, only where depth is still the cleared 0. */
    static final long SKY_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_WRITE_A
                    | org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_GEQUAL;

    /** Terrain draw state: opaque, depth written, strict reversed depth. */
    static final long TERRAIN_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_WRITE_A
                    | BGFX_STATE_WRITE_Z
                    | BGFX_STATE_DEPTH_TEST_GREATER;

    /**
     * One tile's terrain: each run of chunk triangles is tested against the frame's view, and
     * consecutive visible runs are drawn as one index range. Null view: every run is drawn.
     */
    private void drawTerrain(Slot slot, WorldStreamObjectCulling.View view) {
        int runCount = slot.runs.length / 2;
        var visible = slot.visible;
        for (int r = 0; r < runCount; r++) {
            visible[r] = view == null || view.visible(slot.runSpheres, r);
            frameChunksCulled += visible[r] ? 0 : 1;
        }
        frameChunks += runCount;
        var ranges = slot.ranges;
        int rangeInts = visibleRanges(slot.runs, visible, ranges);
        for (int i = 0; i < rangeInts; i += 2) {
            bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
            bindSplat(slot);
            bgfx_set_dynamic_vertex_buffer(0, slot.vertices, 0, slot.terrainVertices);
            bgfx_set_dynamic_index_buffer(slot.indices, ranges[i], ranges[i + 1]);
            bgfx_set_state(TERRAIN_STATE, 0);
            bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
            frameTerrainSubmits++;
        }
    }

    /** Layer array sampling: repeat (eight repeats per chunk), trilinear, anisotropic. */
    static final int SPLAT_LAYER_FLAGS =
            org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;

    /** Mask map sampling: clamped, trilinear; the shader keeps it inside each chunk. */
    static final int SPLAT_MASK_FLAGS = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;

    /** Chunk table sampling: one texel per chunk, read exactly. */
    static final int SPLAT_TABLE_FLAGS =
            BGFX_SAMPLER_U_CLAMP
                    | BGFX_SAMPLER_V_CLAMP
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;

    /**
     * Whether {@code slot}'s committed generation draws its splat inputs: it uploaded them, and the
     * shared layer array and its own mask and table exist. Otherwise it draws the atlas.
     */
    private boolean splatDrawn(Slot slot) {
        return slot.splatted
                && splatArray != INVALID
                && slot.splatAlpha != INVALID
                && slot.splatTable != INVALID;
    }

    /**
     * Binds the splat stages for one terrain or water submit. A slot that does not draw splat binds
     * stand-ins (u_worldSplat.x is 0, so the shader ignores them).
     */
    private void bindSplat(Slot slot) {
        if (splatDrawn(slot)) {
            bgfx_set_texture(1, splatLayersSampler, splatArray, SPLAT_LAYER_FLAGS);
            bgfx_set_texture(2, splatMaskSampler, slot.splatAlpha, SPLAT_MASK_FLAGS);
            bgfx_set_texture(3, splatTableSampler, slot.splatTable, SPLAT_TABLE_FLAGS);
            return;
        }
        short layers = splatArray != INVALID ? splatArray : splatDummyArray;
        if (layers != INVALID) {
            bgfx_set_texture(1, splatLayersSampler, layers, SPLAT_LAYER_FLAGS);
        }
        bgfx_set_texture(2, splatMaskSampler, slot.texture, SPLAT_MASK_FLAGS);
        bgfx_set_texture(3, splatTableSampler, slot.texture, SPLAT_TABLE_FLAGS);
    }

    /** Index ranges for the visible runs, as a new array; see the in-place overload. */
    static int[] visibleRanges(int[] runs, java.util.function.IntPredicate visible) {
        var flags = new boolean[runs.length / 2];
        for (int r = 0; r < flags.length; r++) {
            flags[r] = visible.test(r);
        }
        var result = new int[runs.length];
        return java.util.Arrays.copyOf(result, visibleRanges(runs, flags, result));
    }

    /**
     * Writes the index ranges (first, count) to draw for the runs (first index, count) marked
     * {@code visible} into {@code result} and returns how many ints it wrote: consecutive visible
     * runs whose indices are contiguous merge into one range. Allocates nothing.
     */
    static int visibleRanges(int[] runs, boolean[] visible, int[] result) {
        int size = 0, first = -1, count = 0;
        for (int r = 0; r < runs.length / 2; r++) {
            if (!visible[r]) {
                continue;
            }
            if (first >= 0 && runs[r * 2] == first + count) {
                count += runs[r * 2 + 1];
                continue;
            }
            if (first >= 0) {
                result[size++] = first;
                result[size++] = count;
            }
            first = runs[r * 2];
            count = runs[r * 2 + 1];
        }
        if (first >= 0) {
            result[size++] = first;
            result[size++] = count;
        }
        return size;
    }

    private void frameUniform(short handle, java.nio.FloatBuffer value) {
        bgfx_set_uniform(handle, value, 1);
        frameCameraUniforms++;
    }

    /**
     * Alpha-test reference for cutout ranges: a BASIC approximation, not client parity. Cutout mip
     * chains preserve the coverage this reference sees (issue #265).
     */
    static final float CUTOUT_REFERENCE =
            com.vibeminer.rendering.world.ObjectTextureMips.CUTOUT_REFERENCE;

    /**
     * Object textures: anisotropic min and mag with linear mips over each image's mip chain (issues
     * #265, #357), with repeat addressing because model texture coordinates tile.
     */
    static final int OBJECT_SAMPLER_FLAGS =
            org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC
                    | org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;

    /**
     * The u_wmoMaterial value of cutout ranges. Multisampled, z turns the alpha test into an alpha
     * to coverage ramp around the same reference, so leaf and grate edges resolve smoothly;
     * single-sampled, the plain alpha test.
     */
    static float[] cutoutMaterial(boolean coverage) {
        return new float[] {CUTOUT_REFERENCE, 0, coverage ? 1 : 0, 0};
    }

    /**
     * Cutout draw state. With alpha to coverage the shader's alpha sets the sample mask, and alpha
     * is not written, so the frame's output alpha stays opaque.
     */
    static long cutoutState(boolean coverage) {
        return coverage
                ? BGFX_STATE_WRITE_RGB
                        | BGFX_STATE_WRITE_Z
                        | BGFX_STATE_DEPTH_TEST_GREATER
                        | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA_TO_COVERAGE
                : BgfxWorldWmoPass.DRAW_STATE;
    }

    /** Blend state: depth tested, no depth or alpha write, source-alpha over. */
    static final long BLENDED_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_DEPTH_TEST_GREATER
                    | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC(
                            org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA,
                            org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA);

    /**
     * Bits of a blend key holding its entry: the tile's position in the active (producer) order
     * above the placement's bits. Five position bits hold FAR's 26 slots (#276); #440 widens the
     * placement bits when tiles may carry more placements.
     */
    static final int BLEND_ENTRY_BITS = 15, BLEND_PLACEMENT_BITS = 10;

    static {
        // The key's entry bits must hold every active position and every placement of a tile.
        if (WorldStreamObjectsFile.MAX_PLACEMENTS > 1 << BLEND_PLACEMENT_BITS
                || SLOT_COUNT > 1 << (BLEND_ENTRY_BITS - BLEND_PLACEMENT_BITS)) {
            throw new ExceptionInInitializerError("Blend key bit budget");
        }
    }

    /**
     * One primitive sort key per blended placement: the squared camera distance's IEEE bits with
     * the lowest {@link #BLEND_ENTRY_BITS} replaced by the inverted entry. Non-negative doubles
     * order like their bits, so an ascending sort read backwards is farthest first, and equal
     * distances keep active-order-then-placement order, as the stable sort this replaced did. The
     * dropped mantissa bits are 2^-37 relative.
     */
    static long blendKey(double squaredDistance, int position, int placement) {
        if (!(squaredDistance >= 0)
                || position < 0
                || position >= SLOT_COUNT
                || placement < 0
                || placement >= 1 << BLEND_PLACEMENT_BITS) {
            throw new IllegalArgumentException("Blend key");
        }
        int entry = position << BLEND_PLACEMENT_BITS | placement;
        long mask = (1L << BLEND_ENTRY_BITS) - 1;
        // +0.0 for -0.0, so the sign bit never inverts the order.
        return Double.doubleToRawLongBits(squaredDistance + 0.0) & ~mask | mask - entry;
    }

    /** The key's position in the active order. */
    static int blendPosition(long key) {
        return blendEntry(key) >>> BLEND_PLACEMENT_BITS;
    }

    static int blendPlacement(long key) {
        return blendEntry(key) & ((1 << BLEND_PLACEMENT_BITS) - 1);
    }

    private static int blendEntry(long key) {
        long mask = (1L << BLEND_ENTRY_BITS) - 1;
        return (int) (mask - (key & mask));
    }

    /**
     * Blended placements of every active slot, sorted back to front by the squared distance of
     * their placement origin to the camera origin (per placement, not per triangle). Sorts one
     * reused primitive array; nothing is boxed or allocated per frame once it has grown.
     */
    private void drawBlended(
            ObjectFrame frame, WorldStreamObjectCulling.View view, TerrainScene.Origin origin) {
        int count = 0;
        for (int position = 0; position < active.size(); position++) {
            var objects = slots[active.get(position).slot()].objects;
            if (objects == null || !objects.anyBlended) {
                continue;
            }
            for (int p = 0; p < objects.placementModels.length; p++) {
                if (!objects.blendedModels[objects.placementModels[p]]
                        || view != null && !view.visible(objects.spheres, p)) {
                    continue;
                }
                int at = p * WorldStreamObjectsFile.PLACEMENT_DOUBLES;
                double dx = objects.placements[at] - origin.x();
                double dy = objects.placements[at + 1] - origin.y();
                double dz = objects.placements[at + 2] - origin.z();
                if (count == blendKeys.length) {
                    blendKeys = java.util.Arrays.copyOf(blendKeys, Math.max(64, count * 2));
                }
                blendKeys[count++] = blendKey(dx * dx + dy * dy + dz * dz, position, p);
            }
        }
        java.util.Arrays.sort(blendKeys, 0, count);
        for (int i = count - 1; i >= 0; i--) {
            long key = blendKeys[i];
            var objects = slots[active.get(blendPosition(key)).slot()].objects;
            drawPlacement(frame, objects, blendPlacement(key), origin, true);
        }
    }

    /**
     * Opaque and cutout BASIC exterior objects after all terrain and before water, sharing
     * terrain's camera, reversed depth and float origin. Each model matrix is the double placement
     * position minus that origin, cast to float once.
     */
    private void drawObjects(
            ObjectFrame frame,
            WorldStreamObjectCulling.View view,
            SlotObjects objects,
            TerrainScene.Origin origin) {
        for (int p = 0; p < objects.placementModels.length; p++) {
            framePlacements++;
            if (view != null && !view.visible(objects.spheres, p)) {
                frameCulled++;
                continue;
            }
            drawPlacement(frame, objects, p, origin, false);
        }
    }

    /**
     * The object uniform buffers of one frame, allocated once from the frame's MemoryStack. Every
     * placement rewrites them in place, so drawing allocates nothing per placement.
     */
    static final class ObjectFrame {
        final java.nio.FloatBuffer model, normal, opaque, cutout, blend;

        final long cutoutState;

        private ObjectFrame(MemoryStack stack, boolean coverage) {
            model = stack.mallocFloat(16);
            normal = stack.mallocFloat(16);
            opaque = stack.floats(0, 0, 0, 0);
            cutout = stack.floats(cutoutMaterial(coverage));
            blend = stack.floats(0, 1, 0, 0);
            cutoutState = cutoutState(coverage);
        }

        /** {@code coverage}: the target is multisampled, so cutouts use alpha to coverage. */
        static ObjectFrame on(MemoryStack stack, boolean coverage) {
            return new ObjectFrame(stack, coverage);
        }

        /** The draw state of one range class outside the blended pass. */
        long state(int kind) {
            return kind == WorldStreamObjectsFile.CUTOUT
                    ? cutoutState
                    : BgfxWorldWmoPass.DRAW_STATE;
        }

        /** Writes placement p's matrices into this frame's buffers. */
        void placement(double[] placements, int p, TerrainScene.Origin origin) {
            objectMatrices(placements, p, origin, model, normal);
        }

        /** The u_wmoMaterial value of one range class. */
        java.nio.FloatBuffer material(int kind) {
            return kind == WorldStreamObjectsFile.OPAQUE
                    ? opaque
                    : kind == WorldStreamObjectsFile.CUTOUT ? cutout : blend;
        }
    }

    /** One placement's ranges of one pass: opaque and cutout, or blended only. */
    private void drawPlacement(
            ObjectFrame frame,
            SlotObjects objects,
            int p,
            TerrainScene.Origin origin,
            boolean blendedPass) {
        frame.placement(objects.placements, p, origin);
        // bgfx uniforms persist across submits: the camera is set once per frame, the matrices
        // once per placement and the material only when its class changes.
        bgfx_set_uniform(wmoModel, frame.model, 1);
        bgfx_set_uniform(wmoNormal, frame.normal, 1);
        int stride = WorldStreamObjectsFile.RANGE_INTS;
        int m = objects.placementModels[p];
        for (int mesh = objects.models[m * 2];
                mesh < objects.models[m * 2] + objects.models[m * 2 + 1];
                mesh++) {
            int at = mesh * 6;
            for (int r = objects.meshes[at + 4];
                    r < objects.meshes[at + 4] + objects.meshes[at + 5];
                    r++) {
                int kind = objects.ranges[r * stride + 3];
                if ((kind == WorldStreamObjectsFile.BLENDED) != blendedPass) {
                    continue;
                }
                if (kind != frameMaterial) {
                    bgfx_set_uniform(wmoMaterial, frame.material(kind), 1);
                    frameMaterial = kind;
                }
                bgfx_set_dynamic_vertex_buffer(
                        0, objects.vertices, objects.meshes[at], objects.meshes[at + 1]);
                bgfx_set_dynamic_index_buffer(
                        objects.indices,
                        objects.meshes[at + 2] + objects.ranges[r * stride],
                        objects.ranges[r * stride + 1]);
                bgfx_set_texture(
                        0,
                        wmoSampler,
                        objects.textures[objects.ranges[r * stride + 2]],
                        OBJECT_SAMPLER_FLAGS);
                bgfx_set_state(blendedPass ? BLENDED_STATE : frame.state(kind), 0);
                bgfx_submit(0, wmoProgram, 0, BGFX_DISCARD_ALL);
                frameObjectSubmits++;
            }
        }
    }

    /**
     * Column-major model and normal matrices for placement p relative to origin. The translation is
     * computed in double and cast to float once; the rotation is the placement's row-major matrix
     * (the reviewed ADT heading rule for buildings and MDDF doodads) times its uniform scale.
     */
    static void objectMatrices(
            double[] placements,
            int p,
            TerrainScene.Origin origin,
            java.nio.FloatBuffer model,
            java.nio.FloatBuffer normal) {
        int at = p * WorldStreamObjectsFile.PLACEMENT_DOUBLES;
        double x = placements[at], y = placements[at + 1], z = placements[at + 2];
        double s = placements[at + 3];
        int r = at + 4;
        model.clear();
        normal.clear();
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                double value = placements[r + row * 3 + column];
                model.put((float) (s * value));
                normal.put((float) value);
            }
            model.put(0);
            normal.put(0);
        }
        model.put((float) (x - origin.x()))
                .put((float) (y - origin.y()))
                .put((float) (z - origin.z()))
                .put(1)
                .flip();
        normal.put(0).put(0).put(0).put(1).flip();
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
                objectBytes(upload),
                compressedImages,
                decodedImages);
    }

    /**
     * The pitch argument of one upload slice. Compressed slices pass UINT16_MAX, so bgfx derives
     * the source pitch from the format, its default path, and never sizes a staging copy as texel
     * rows times a block-row pitch. RGBA8 slices pass their texel-row pitch. The layout ladders of
     * nativeGpuWorldStreamObjectsProof check every block of every level on Vulkan and D3D11.
     */
    static int updatePitch(int format, WorldStreamObjectMips.Slice slice) {
        return ObjectTextureFormats.compressed(format) ? 0xFFFF : slice.pitch();
    }

    /** The bgfx texture format of an object texture format. */
    static int bgfxFormat(int format) {
        return switch (format) {
            case ObjectTextureFormats.RGBA8 -> BGFX_TEXTURE_FORMAT_RGBA8;
            case ObjectTextureFormats.BC1 -> BGFX_TEXTURE_FORMAT_BC1;
            case ObjectTextureFormats.BC2 -> BGFX_TEXTURE_FORMAT_BC2;
            case ObjectTextureFormats.BC3 -> BGFX_TEXTURE_FORMAT_BC3;
            default -> throw new IllegalArgumentException("Object texture format");
        };
    }

    /** The stored format when this backend samples it, otherwise RGBA8 (the decode fallback). */
    static int uploadFormat(int stored, boolean[] supported) {
        if (!ObjectTextureFormats.valid(stored)) {
            throw new IllegalArgumentException("Object texture format");
        }
        return supported[stored] ? stored : ObjectTextureFormats.RGBA8;
    }

    /** One stderr line naming the upload path of each compressed object texture format. */
    static String objectFormatsReport(boolean[] supported, boolean forced) {
        var line = new StringBuilder("World object textures:");
        String[] names = {"RGBA8", "BC1", "BC2", "BC3"};
        for (int f = ObjectTextureFormats.BC1; f <= ObjectTextureFormats.BC3; f++) {
            line.append(' ')
                    .append(names[f])
                    .append(supported[f] ? " compressed" : " decoded to RGBA8")
                    .append(f < ObjectTextureFormats.BC3 ? "," : "");
        }
        if (forced) {
            line.append(" (").append(OBJECTS_RGBA8_PROPERTY).append(")");
        }
        return line.toString();
    }

    /** Whether an objects set of {@code need} GPU bytes fits beside {@code reserved} bytes. */
    static boolean objectsFit(long reserved, long need) {
        return objectsFit(OBJECT_BUDGET_BYTES, reserved, need);
    }

    /** Whether {@code need} more GPU bytes fit beside {@code reserved} under {@code budget}. */
    static boolean objectsFit(long budget, long reserved, long need) {
        return need >= 0 && reserved >= 0 && need <= budget - reserved;
    }

    /** Test seam: a native proof fills past a small budget without hundreds of MiB of objects. */
    void objectBudgetForProof(long bytes) {
        requireOwner();
        if (bytes < 0 || bytes > session.objectBudgetBytes()) {
            throw new IllegalArgumentException("Proof budget above the session budget");
        }
        objectBudget = bytes;
    }

    private long reservedObjectBytes() {
        var upload = uploading.get();
        long total =
                upload == null || upload.objects == null || upload.objects.building == null
                        ? 0
                        : upload.objects.building.reserved;
        if (late != null && late.build.building != null) {
            total += late.build.building.reserved;
        }
        for (var slot : slots) {
            if (slot != null && slot.objects != null) {
                total += slot.objects.reserved;
            }
        }
        return total;
    }

    private long objectBytes(Upload upload) {
        long total = upload == null || upload.objects == null ? 0 : upload.objects.builtBytes();
        if (late != null) {
            total += late.build.builtBytes();
        }
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
            if (pending.objects != null) {
                pending.objects.abandon();
            }
        }
        if (late != null) {
            late.dropped = true;
            late.build.abandon();
            late = null;
        }
        if (splatDummyArray != INVALID) {
            bgfx_destroy_texture(splatDummyArray);
            splatDummyArray = INVALID;
        }
        if (splatArray != INVALID) {
            bgfx_destroy_texture(splatArray);
            splatArray = INVALID;
        }
        for (var slot : slots) {
            if (slot != null && slot.objects != null) {
                slot.objects.destroy();
                slot.objects = null;
            }
            if (slot != null) {
                if (slot.vertices != INVALID) {
                    bgfx_destroy_dynamic_vertex_buffer(slot.vertices);
                    slot.vertices = INVALID;
                }
                if (slot.indices != INVALID) {
                    bgfx_destroy_dynamic_index_buffer(slot.indices);
                    slot.indices = INVALID;
                }
                for (short splat : new short[] {slot.splatAlpha, slot.splatTable}) {
                    if (splat != INVALID) {
                        bgfx_destroy_texture(splat);
                    }
                }
                slot.splatAlpha = slot.splatTable = INVALID;
                if (slot.texture != INVALID) {
                    bgfx_destroy_texture(slot.texture);
                    slot.texture = INVALID;
                }
            }
        }
        for (short uniform :
                new short[] {
                    eye,
                    projection,
                    rebase,
                    atlasUniform,
                    sampler,
                    splatLayersSampler,
                    splatMaskSampler,
                    splatTableSampler,
                    splatUniform,
                    sky,
                    worldFog,
                    worldFogRange,
                    worldSkyTop,
                    skyTop,
                    skyHorizon,
                    skyView
                }) {
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
            pending.closeInputs();
            pending.finished = true;
        }
        liveBytes = 0;
        if (!callbackFreed) {
            release.free();
            callbackFreed = true;
        }
    }

    /** Terrain and water mesh vertices: float position, normalized RGBA8 colour (16 bytes). */
    private static BGFXVertexLayout meshLayout() {
        var layout = BGFXVertexLayout.calloc();
        bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(
                layout,
                org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR0,
                4,
                org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8,
                true,
                false);
        bgfx_vertex_layout_end(layout);
        if (layout.stride() != WorldStreamPackedTile.VERTEX_BYTES) {
            layout.free();
            throw new IllegalStateException("Unexpected terrain vertex stride");
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

    /**
     * One stderr diagnostic line for the objects pass. It names only the relative shader family and
     * profile, the step and the exception (this class's own messages are fixed text); the parent
     * redacts the stderr tail before logging it.
     */
    static String objectsReason(String what, String family, String step, Throwable failed) {
        return "World exterior objects " + what + " (" + family + ", " + step + "): " + failed;
    }

    /**
     * Rate limit for per-tile objects build failures on stderr: each distinct reason is written
     * once (up to {@link #DISTINCT} of them), then any failure only every 64th. Owner thread only.
     */
    static final class ObjectsFailureLog {
        static final int DISTINCT = 8;
        // Each remembered reason is cut to this many characters, so the set stays small.
        static final int MAX_KEY_CHARS = 256;
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private long count;

        /** The session's failure count when this one should be written, else 0. */
        long admit(String step, Throwable failed) {
            count++;
            String reason = step + ": " + failed.getClass().getName() + ": " + failed.getMessage();
            if (reason.length() > MAX_KEY_CHARS) {
                reason = reason.substring(0, MAX_KEY_CHARS);
            }
            boolean fresh = seen.size() < DISTINCT && seen.add(reason);
            return fresh || count % 64 == 0 ? count : 0;
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
