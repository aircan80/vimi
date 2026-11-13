package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8;
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
import com.vibeminer.rendering.world.WorldFrameLimits;
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
     * vertices, indices and RGBA8 textures with their full mip chains (53 MiB with every table at
     * its cap, the large and interior allowances of buildings drawn by group included). Real tiles
     * stay far below that, so instead of a per-slot worst case the objects share {@link
     * #OBJECT_BUDGET_BYTES}, charged in Stats.objectBytes. The owner's x4 multisampled colour and
     * depth target adds about 11 MB on top (issue #357).
     */
    static final int SLOT_COUNT = WorldStreamState.SLOTS;

    /**
     * STANDARD-tier GPU bytes all resident and building exterior object sets may reserve together:
     * 212 MiB, pinned as a literal: about 12% over the heaviest FAR neighbourhood measured, the 5 x
     * 5 tiles around Stormwind City (0:48:31, build 70862) whose 24 object sets take 189.7 MiB with
     * every interior of the city drawn by group (FAR's mean over Elwynn is about 172 MiB with mip
     * chains). The session's object texture tier selects the budget actually enforced (HIGH: 256
     * MiB). A set that does not fit fails alone and its terrain is kept; late objects load
     * nearest-first, so on FAR + HIGH the outer ring is what may report FAILED.
     */
    static final long OBJECT_BUDGET_BYTES = WorldStreamEnvelope.Session.STANDARD_OBJECT_BUDGET;

    /**
     * Texture handles the session may still create after the renderer opens, outside object sets:
     * each slot's splat mask and table (two per slot, created with its first splat tile) and the
     * shared splat layer array.
     */
    static final int LAZY_SLOT_TEXTURES = 2, LAZY_SHARED_TEXTURES = 1;

    /**
     * Texture handles kept for the native owner after the renderer opens: its colour and depth
     * target and three readbacks (five), twice over for a resize whose old target bgfx has not
     * returned yet, plus spare.
     */
    static final int OWNER_TEXTURE_RESERVE = 16;

    /**
     * The live check's share of that reserve. The owner's own handles are already counted live,
     * five, or ten while a resize's old target is still being returned, so with six more the live
     * room is never below the accounting's room except by handles bgfx has yet to return.
     */
    static final int LIVE_TEXTURE_SPARE = OWNER_TEXTURE_RESERVE - 10;

    /**
     * A build whose handles fit the session's accounting but not bgfx's live count waits at most
     * this long, then is refused as BUDGET like any other set that does not fit. Released handles
     * return at the next bgfx frame or the one after, so 250 ms is many frames at any present rate;
     * a shortage lasting longer is a handle the accounting does not know, which a quick retry would
     * not clear, so the refusal waits for a release like a full budget does.
     */
    static final long TEXTURE_WAIT_NANOS = 250_000_000L;

    /**
     * Per-slot mesh capacity: one tile's terrain (145 vertices per chunk, up to 256 chunks) plus
     * its optional static water, as WorldStreamPackedTile lays them out (issue #373).
     */
    static final int MAX_VERTICES = WorldStreamPackedTile.MAX_VERTICES,
            MAX_INDICES = WorldStreamPackedTile.MAX_INDICES;

    /**
     * The water opacity without recorded water light: 0.40, the constant blend water has always
     * drawn with, now carried by the shader's alpha.
     */
    static final float WATER_ALPHA = 0x66 / 255f;

    /**
     * The water uniform (u_worldWater, four vec4) for a frame's sky (#171): ocean close, ocean far,
     * river close and river far, each rgb 0..255 with its opacity in w (shallow for close, deep for
     * far). Without recorded water light every entry is the diagnostic teal at {@link
     * #WATER_ALPHA}, as water has always drawn; recorded water light is mixed in by its weight.
     */
    static float[] waterUniform(WorldStreamTransport.Sky sky) {
        int teal = WorldStreamTileFile.WATER_RGB;
        float[] base = {teal >>> 16 & 255, teal >>> 8 & 255, teal & 255};
        double w = sky == null ? 0 : sky.waterWeight();
        float[] colours = sky == null ? new float[12] : sky.water();
        float[] alphas = sky == null ? new float[4] : sky.waterAlphas();
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int c = 0; c < 3; c++) {
                out[i * 4 + c] = (float) (base[c] + (colours[i * 3 + c] - base[c]) * w);
            }
            out[i * 4 + 3] = (float) (WATER_ALPHA + (alphas[i] - WATER_ALPHA) * w);
        }
        return out;
    }

    static final int MAX_TICKETS = 64;

    /** One geometry upload slice in bytes: whole vertices and whole indices. */
    static final int GEOMETRY_CHUNK = 4096 * WorldStreamPackedTile.VERTEX_BYTES;

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
     * objectTextures: texture handles those sets hold or will create, at most objectTextureLimit,
     * each shared texture counted once (WorldStreamObjectTextures); compressedImages and
     * decodedImages: object textures uploaded as their own BC blocks, and compressed ones decoded
     * to RGBA8 because this backend does not sample their format.
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
            long decodedImages,
            // Texture handles held by resident and building object sets, the most they may hold
            // (bgfx's limit less every other texture of the session) and refusals for handles.
            int objectTextures,
            int objectTextureLimit,
            long textureRefusals,
            // Image references those sets hold (at least objectTextures: a texture several sets
            // share is one handle), and references served by a texture another set had uploaded.
            int objectTextureReferences,
            long sharedTextureReferences) {}

    /**
     * Diagnostic override: when this system property is {@code true}, every compressed object
     * texture takes the RGBA8 decode path, as on a backend without BC sampling.
     */
    static final String OBJECTS_RGBA8_PROPERTY = "vimi.world.objects.rgba8";

    /**
     * The WDL horizon of the last frame: tiles and triangles drawn, the fixed GPU allocation of its
     * buffers, the last rebuild's time on the horizon worker, and how many rebuilds were uploaded.
     */
    record HorizonCounts(
            int tiles,
            int triangles,
            long gpuBytes,
            long lastBuildNanos,
            long uploads,
            // Draw submits of the last frame: merged tile runs plus skirts facing full tiles.
            int submits) {}

    /**
     * Exterior objects of the last drawn frame: placements tested in the opaque/cutout pass, how
     * many of them the CPU cull skipped, object draw submits over both passes, and how many times a
     * camera uniform (eye, projection, rebase, for terrain and objects) was set; terrain chunk runs
     * tested, culled and submitted; object instances drawn over both passes, and placements not
     * drawn because the frame's transient instance data ran out (#382); portal walks of placed
     * roots with a portal set with the interior meshes they culled (#383 step 3b), per placement;
     * and placements tied to an interior group those walks did not reach.
     */
    record FrameCounts(
            int placements,
            int culled,
            int objectSubmits,
            int sortedSubmits,
            int cameraUniforms,
            int chunks,
            int chunksCulled,
            int terrainSubmits,
            int instances,
            int instancesDropped,
            int portalWalks,
            int portalMeshesCulled,
            int portalPlacementsCulled) {}

    /**
     * Object instance data of one frame at the caps (#382): every placement of every resident slot
     * (all may be in view), once in the opaque/cutout pass and once in the sorted pass.
     */
    static final int MAX_FRAME_INSTANCE_BYTES =
            frameInstanceBytes(SLOT_COUNT, WorldStreamObjectsFile.MAX_PLACEMENTS);

    /** Instance bytes of one frame for {@code tiles} resident tiles of {@code placements} each. */
    static int frameInstanceBytes(int tiles, int placements) {
        return Math.multiplyExact(
                Math.multiplyExact(tiles, placements), WorldObjectInstances.STRIDE * 2);
    }

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

        /** The model whose geometry uploads next, and how far its vertices and indices are. */
        private int model;

        private boolean failed, budget;

        /** Steps spent waiting for bgfx to return released texture handles, since waitStarted. */
        private int textureWaits;

        private long waitStarted;

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
        // Replaces the late set the generation already holds; that set stays drawn until this one
        // attaches, and stays when it does not.
        private final boolean replace;
        private boolean dropped;

        private LateUpload(WorldStreamState.Lease lease, ObjectsBuild build, boolean replace) {
            this.lease = lease;
            this.build = build;
            this.replace = replace;
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

        /** Per range: model-space centre of a blended range's bounds (#552). */
        final float[] rangeCentres;

        /** Per range: its mesh. */
        final int[] rangeMeshes;

        /** The most blended ranges in one model: the per-placement sort's length. */
        final int maxBlendedRanges;

        final short[] textures;

        /** Per model: whether any of its ranges is alpha blended (drawn in the sorted pass). */
        final boolean[] blendedModels;

        /** Whether any model is blended. */
        final boolean anyBlended;

        /** Per model: whether any of its ranges is drawn in the opaque/cutout pass. */
        final boolean[] unsortedModels;

        /**
         * Per-frame grouping scratch (#382), sized once per tile generation: this frame's cull
         * result (the sorted pass reads it too, so both passes agree), the visible placements the
         * model groups draw (all but portal roots, #383, which draw on their own), placements
         * dropped this frame for lack of instance data (so the sorted pass skips them too), grouped
         * placements by model, and each model's start in that order.
         */
        final boolean[] visible, grouped, dropped;

        final int[] order, starts;

        /** Per model: its portal traversal (#383), or null for a model without a portal set. */
        final com.vibeminer.rendering.world.WmoPortalTraversal[] portals;

        /** Per mesh: its group in its model's portal set, or -1. */
        final int[] meshGroups;

        /**
         * Per placement of a model with portals: the groups its last walk reached, reused every
         * frame; null for other placements.
         */
        final java.util.BitSet[] portalVisible;

        /**
         * Per placement: the placement whose walk it is tied to and its interior group there, or -1
         * and -1 (set doodads inside a room, culled with it).
         */
        final int[] placementParents, placementGroups;

        /** The session's geometry library this set holds its models' buffers from. */
        final WorldStreamObjectGeometry geometryLibrary;

        /**
         * Per model: its geometry entry, held from {@link #geometryLibrary}; null once released.
         */
        final WorldStreamObjectGeometry.Entry[] geometry;

        /** Per model: whether this set creates and fills its buffers (else it shares them). */
        final boolean[] createsGeometry;

        /** Per model: its vertex and index buffers; INVALID until created. */
        final short[] modelVertices, modelIndices;

        /** Per mesh: its model, and its first vertex and first index within its model's buffers. */
        final int[] meshModel, meshVertexBase, meshIndexBase;

        long bytes;

        /**
         * Building water ({@link WorldObjectWater}): static buffers in terrain water's layout and
         * per run its first index, index count, placement and mesh; INVALID and empty without.
         */
        short waterVertices = INVALID, waterIndices = INVALID;

        final int[] waterRuns;

        /** Per model: its idle animation (#384), or null for a static model. */
        final WorldStreamPackedObjects.ObjectAnimation[] animations;

        /**
         * Placements that may animate: their model has an idle animation and no sorted range (a
         * blended or additive range stays in the static sorted pass), in placement order.
         */
        final int[] animated;

        /** Per placement: the phase it plays ({@link WorldObjectAnimation#phase}). */
        final int[] phases;

        /** Per model and phase: its pose, created when first needed, or null. */
        final WorldObjectAnimation.Pose[] poses;

        /**
         * This set's share of the animation step offsets ({@link WorldObjectAnimation#stepOffset}):
         * one per built set, so sets with few animated models do not all step their model 0 in the
         * same frame.
         */
        final long stepSet = WorldObjectAnimation.nextStepSet();

        /** GPU bytes of this set's poses, reserved against the object budget. */
        long poseBytes;

        /** Per-frame scratch: the placements one pose draws. */
        final int[] poseMembers;

        /** Per model and phase: the animatable placements playing it, or null for none. */
        final int[][] members;

        /** The animated placements' box: min x, y, z then max x, y, z (empty when none). */
        final double[] animatedBounds;

        /**
         * GPU bytes of this set's own buffers, reserved against OBJECT_BUDGET_BYTES until
         * destroyed; its textures are reserved once each in the session's shared textures.
         */
        long reserved;

        /** The session's shared object textures, where this set holds one reference per image. */
        final WorldStreamObjectTextures shared;

        /** Per image: the texture it holds a reference to, or null once released. */
        final WorldStreamObjectTextures.Entry[] entries;

        /** Per image: whether this set creates and fills its texture (else it shares one). */
        final boolean[] creates;

        SlotObjects(
                WorldStreamPackedObjects source,
                WorldStreamObjectTextures shared,
                WorldStreamObjectTextures.Plan plan,
                WorldStreamObjectGeometry geometryLibrary,
                WorldStreamObjectGeometry.Plan geometryPlan) {
            this.shared = shared;
            this.geometryLibrary = geometryLibrary;
            int modelCount = source.models.length / 2;
            modelVertices = new short[modelCount];
            modelIndices = new short[modelCount];
            java.util.Arrays.fill(modelVertices, INVALID);
            java.util.Arrays.fill(modelIndices, INVALID);
            createsGeometry = new boolean[modelCount];
            for (int m = 0; m < modelCount; m++) {
                createsGeometry[m] = geometryPlan.creates(m);
            }
            int meshCount = source.meshes.length / 6;
            meshModel = new int[meshCount];
            meshVertexBase = new int[meshCount];
            meshIndexBase = new int[meshCount];
            for (int m = 0; m < modelCount; m++) {
                for (int mesh = source.models[m * 2];
                        mesh < source.models[m * 2] + source.models[m * 2 + 1];
                        mesh++) {
                    meshModel[mesh] = m;
                    meshVertexBase[mesh] = source.meshes[mesh * 6] - source.modelVertexFirst[m];
                    meshIndexBase[mesh] = source.meshes[mesh * 6 + 2] - source.modelIndexFirst[m];
                }
            }
            waterRuns = source.water.runs;
            models = source.models;
            meshes = source.meshes;
            ranges = source.ranges;
            placementModels = source.placementModels;
            images = source.images;
            placements = source.placements;
            spheres = source.spheres;
            rangeCentres = source.rangeCentres;
            rangeMeshes = new int[ranges.length / WorldStreamObjectsFile.RANGE_INTS];
            for (int mesh = 0; mesh < meshes.length / 6; mesh++) {
                for (int r = meshes[mesh * 6 + 4];
                        r < meshes[mesh * 6 + 4] + meshes[mesh * 6 + 5];
                        r++) {
                    rangeMeshes[r] = mesh;
                }
            }
            int most = 0;
            for (int m = 0; m < models.length / 2; m++) {
                most = Math.max(most, blendedRangeCount(models, meshes, ranges, m));
            }
            maxBlendedRanges = most;
            textures = new short[source.imageCount()];
            java.util.Arrays.fill(textures, INVALID);
            creates = new boolean[textures.length];
            for (int i = 0; i < creates.length; i++) {
                creates[i] = plan.creates(i);
            }
            blendedModels = new boolean[models.length / 2];
            for (int m = 0; m < blendedModels.length; m++) {
                for (int mesh = models[m * 2]; mesh < models[m * 2] + models[m * 2 + 1]; mesh++) {
                    for (int r = meshes[mesh * 6 + 4];
                            r < meshes[mesh * 6 + 4] + meshes[mesh * 6 + 5];
                            r++) {
                        blendedModels[m] |=
                                WorldStreamObjectsFile.sorted(
                                        ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3]);
                    }
                }
            }
            boolean any = false;
            for (boolean blended : blendedModels) {
                any |= blended;
            }
            anyBlended = any;
            unsortedModels = new boolean[models.length / 2];
            for (int m = 0; m < unsortedModels.length; m++) {
                for (int mesh = models[m * 2]; mesh < models[m * 2] + models[m * 2 + 1]; mesh++) {
                    for (int r = meshes[mesh * 6 + 4];
                            r < meshes[mesh * 6 + 4] + meshes[mesh * 6 + 5];
                            r++) {
                        unsortedModels[m] |=
                                !WorldStreamObjectsFile.sorted(
                                        ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3]);
                    }
                }
            }
            visible = new boolean[placementModels.length];
            grouped = new boolean[placementModels.length];
            dropped = new boolean[placementModels.length];
            order = new int[placementModels.length];
            starts = new int[models.length / 2 + 1];
            portals = new com.vibeminer.rendering.world.WmoPortalTraversal[blendedModels.length];
            for (int m = 0; m < portals.length; m++) {
                portals[m] = source.portals(m);
            }
            meshGroups = source.meshGroups;
            placementParents = source.placementParents;
            placementGroups = source.placementGroups;
            animations = source.animations;
            int animatedCount = 0;
            for (int p = 0; p < placementModels.length; p++) {
                int m = placementModels[p];
                if (animations[m] != null && !blendedModels[m]) {
                    animatedCount++;
                }
            }
            animated = new int[animatedCount];
            phases = new int[placementModels.length];
            for (int p = 0, at = 0; p < placementModels.length; p++) {
                int m = placementModels[p];
                if (animations[m] != null && !blendedModels[m]) {
                    animated[at++] = p;
                    phases[p] =
                            WorldObjectAnimation.phase(
                                    placements[p * WorldStreamObjectsFile.PLACEMENT_DOUBLES],
                                    placements[p * WorldStreamObjectsFile.PLACEMENT_DOUBLES + 1]);
                }
            }
            poses =
                    new WorldObjectAnimation.Pose
                            [animatedCount == 0
                                    ? 0
                                    : models.length / 2 * WorldObjectAnimation.PHASES];
            poseMembers = new int[animatedCount];
            members = WorldObjectAnimation.members(animated, placementModels, phases, poses.length);
            animatedBounds =
                    WorldObjectAnimation.bounds(
                            animated, placements, WorldStreamObjectsFile.PLACEMENT_DOUBLES);
            portalVisible = new java.util.BitSet[placementModels.length];
            for (int p = 0; p < portalVisible.length; p++) {
                var traversal = portals[placementModels[p]];
                if (traversal != null) {
                    portalVisible[p] = new java.util.BitSet(traversal.groupCount());
                }
            }
            // Held last, so a failure above holds nothing; the geometry is given back if the
            // textures fail. Shared buffers are known at once, created ones once this set makes
            // them (stepObjects).
            var held = geometryLibrary.hold(geometryPlan);
            try {
                entries = shared.hold(plan);
            } catch (RuntimeException | Error failure) {
                for (var entry : held) {
                    geometryLibrary.release(entry);
                }
                throw failure;
            }
            geometry = held;
            for (int m = 0; m < modelCount; m++) {
                if (geometry[m].complete()) {
                    modelVertices[m] = geometry[m].vertices();
                    modelIndices[m] = geometry[m].indices();
                }
            }
            for (int i = 0; i < textures.length; i++) {
                textures[i] = entries[i].handle();
            }
        }

        /** Model m's blended ranges. */
        static int blendedRangeCount(int[] models, int[] meshes, int[] ranges, int m) {
            int count = 0;
            for (int mesh = models[m * 2]; mesh < models[m * 2] + models[m * 2 + 1]; mesh++) {
                for (int r = meshes[mesh * 6 + 4];
                        r < meshes[mesh * 6 + 4] + meshes[mesh * 6 + 5];
                        r++) {
                    if (step(ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3]) == STEP_BLENDED) {
                        count++;
                    }
                }
            }
            return count;
        }

        void destroy() {
            for (int i = 0; i < poses.length; i++) {
                if (poses[i] != null) {
                    poses[i].close();
                    poses[i] = null;
                }
            }
            poseBytes = 0;
            if (waterVertices != INVALID) {
                bgfx_destroy_vertex_buffer(waterVertices);
                waterVertices = INVALID;
            }
            if (waterIndices != INVALID) {
                org.lwjgl.bgfx.BGFX.bgfx_destroy_index_buffer(waterIndices);
                waterIndices = INVALID;
            }
            // One release per model; the last holder of shared geometry keeps it as a zombie.
            for (int m = 0; m < geometry.length; m++) {
                if (geometry[m] != null) {
                    var entry = geometry[m];
                    geometry[m] = null;
                    modelVertices[m] = INVALID;
                    modelIndices[m] = INVALID;
                    geometryLibrary.release(entry);
                }
            }
            // One release per image; the last holder of a texture destroys it, created or not.
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] != null) {
                    var entry = entries[i];
                    entries[i] = null;
                    textures[i] = INVALID;
                    shared.release(entry);
                }
            }
            bytes = 0;
            reserved = 0;
        }
    }

    private final Thread owner = Thread.currentThread();
    // Reused by the blended pass every frame; grows to the most blended placements seen.
    private long[] blendKeys = new long[64];

    /** One placement's blended ranges, sorted back to front (#552); grows, never shrinks. */
    private long[] rangeKeys = new long[16];

    private final WorldStreamEnvelope.Session session;
    private final Slot[] slots;
    // The session tier's shared objects budget; only a native proof lowers it.
    private long objectBudget;

    // bgfx's texture handle limit (BGFX_CONFIG_MAX_TEXTURES, fixed when bgfx is compiled) and
    // the share of it object sets may hold; see objectTextureLimit(int, int, int).
    private int maxTextures, objectTextureLimit;
    private long textureRefusals, textureWaitSteps;
    // Only native proofs change these: extra live spare forces the wait, and its time bound.
    private int extraLiveSpare;
    private long textureWaitNanos = TEXTURE_WAIT_NANOS;

    // Object textures shared across sets by exact image identity (owner thread only).
    private final WorldStreamObjectTextures objectTextures =
            new WorldStreamObjectTextures(handle -> bgfx_destroy_texture(handle));

    /**
     * GPU bytes of model geometry no set holds that the session keeps for tiles that come back
     * (zombies, {@link WorldStreamObjectGeometry}); within the objects budget, and given back first
     * when a set needs room.
     */
    static final long GEOMETRY_ZOMBIE_BYTES = 64L << 20;

    // Object model geometry shared across sets by model asset (owner thread only, #380).
    private final WorldStreamObjectGeometry objectGeometry =
            new WorldStreamObjectGeometry(
                    (vertices, indices) -> {
                        if (vertices != INVALID) {
                            bgfx_destroy_dynamic_vertex_buffer(vertices);
                        }
                        if (indices != INVALID) {
                            bgfx_destroy_dynamic_index_buffer(indices);
                        }
                    },
                    GEOMETRY_ZOMBIE_BYTES);
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
    private short worldLight = INVALID, worldWater = INVALID;
    private WorldLight light = WorldLight.FIXED;
    private boolean terrainLighting = TERRAIN_LIGHTING;
    private short skyTop = INVALID, skyHorizon = INVALID, skyView = INVALID;
    private short wmoFog = INVALID, wmoFogRange = INVALID, wmoSkyTop = INVALID;
    // Read once per frame; #177 sets it from the light tables (sky(Sky)).
    private WorldStreamTransport.Sky recordedSky = WorldStreamTransport.Sky.EXPLORER;
    // Optional WDL horizon (#372): built on its own worker, uploaded and drawn here.
    private WorldStreamHorizon horizon;
    // Two buffer sets: [front] is drawn while the next build uploads into the other.
    private final short[] horizonVertices = {INVALID, INVALID}, horizonIndices = {INVALID, INVALID};
    private int horizonFront, horizonVertexCount, horizonIndexCount, horizonTiles;
    private long horizonBuildNanos, horizonUploads;
    private boolean horizonRequested, horizonSwapped;
    private WorldStreamHorizon.Packed horizonStaging;
    private com.vibeminer.rendering.world.HorizonMesh.Ranges horizonRanges;
    private int[] horizonDrawRanges = new int[0];
    private int frameHorizonSubmits;
    private int horizonStagedBytes;
    // Samples of the target this renderer draws into; see cutoutCoverage.
    private int targetSamples = 1;
    // Optional exterior objects pass (existing world-wmo shaders); INVALID program disables it.
    private short wmoProgram = INVALID, wmoEye = INVALID, wmoProjection = INVALID;
    private short wmoSampler = INVALID;
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
    private boolean closed, callbackFreed, objectCulling = true, objectInstancing = true;
    private boolean vertexColours = VERTEX_COLOURS;
    private FrameCounts lastFrame = new FrameCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private int frameInstances, frameInstancesDropped;
    // Proof seam: at most this many instances per frame (bgfx's own budget still applies).
    private int instanceLimit = Integer.MAX_VALUE;

    /** Portal culling of instance interiors (#383 step 3b); its arrays are reused per frame. */
    private final WorldStreamPortalCulling portalCulling = new WorldStreamPortalCulling();

    private int framePortalWalks, framePortalCulled, framePortalPlacementsCulled;

    /** Building water runs drawn and skipped (culled, or an interior not reached) last frame. */
    private int frameWaterSubmits, frameWaterCulled;

    /** Building water runs drawn in the last frame, and skipped (proofs). */
    int waterSubmits() {
        return frameWaterSubmits;
    }

    int waterCulled() {
        return frameWaterCulled;
    }

    private int frameChunks, frameChunksCulled, frameTerrainSubmits;
    private int framePlacements, frameCulled, frameObjectSubmits, frameCameraUniforms;

    /** Object submits of the sorted (blended, then additive) pass: a part of frameObjectSubmits. */
    private int frameSortedSubmits;

    // The u_wmoMaterial class last set this frame, or -1.
    private int frameMaterial;

    // The walking body: its model on the GPU once attached, and this frame's pose (null: none).
    private WorldStreamBodyDraw body;
    private WorldStreamTransport.Body bodyPose;
    private int frameBodySubmits;

    /**
     * Object animation (#384), per frame: placements drawn animated, animatable placements left
     * static (beyond the radius, over the instance cap, or without a pose within the budgets),
     * poses re-skinned and their vertices, and poses drawn a step late for the skin budget.
     */
    private int frameAnimated, frameAnimatedStatic, frameSkins, frameSkinVertices, frameStalePoses;

    /** Of {@link #frameAnimatedStatic}: beyond the radius, and refused a pose by its budgets. */
    private int frameAnimatedFar, frameAnimatedRefused;

    /**
     * Object animation off: every animatable placement draws its static pose (proofs compare both;
     * the application switches animation off on the parent side, whose files then carry none).
     */
    private boolean objectAnimation = true;

    /** The animation clock's start; poses are timed from it on this child's monotonic clock. */
    private final long animationEpoch = System.nanoTime();

    /** This frame's animation clock, milliseconds since {@link #animationEpoch}. */
    private long animationClock;

    /** Placements the last finished frame drew animated. */
    private int lastFrameAnimated;

    /** A proof's fixed animation clock, or -1. */
    private long proofClock = -1;

    /** Frames drawn so far: poses record the last one that drew them, for releasing. */
    private long animationFrame;

    /** This frame's poses whose clock stepped, skinned after the objects are submitted. */
    private WorldObjectAnimation.Pose[] pendingSkins = new WorldObjectAnimation.Pose[64];

    private int pendingCount;

    /** Poses released for budget so far (diagnostics). */
    private long posesReleased;

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
            worldLight = valid(bgfx_create_uniform("u_worldLight", BGFX_UNIFORM_TYPE_VEC4, 3));
            worldWater = valid(bgfx_create_uniform("u_worldWater", BGFX_UNIFORM_TYPE_VEC4, 4));
            skyTop = valid(bgfx_create_uniform("u_skyTop", BGFX_UNIFORM_TYPE_VEC4, 1));
            skyHorizon = valid(bgfx_create_uniform("u_skyHorizon", BGFX_UNIFORM_TYPE_VEC4, 1));
            skyView = valid(bgfx_create_uniform("u_skyView", BGFX_UNIFORM_TYPE_VEC4, 1));
            if (session.exteriorObjects()) {
                objectPass(profile);
            }
            maxTextures = caps.limits().maxTextures();
            objectTextureLimit =
                    objectTextureLimit(
                            maxTextures,
                            Short.toUnsignedInt(org.lwjgl.bgfx.BGFX.bgfx_get_stats().numTextures()),
                            slots.length);
            if (session.exteriorObjects()) {
                System.err.println(
                        "World object texture handles: "
                                + objectTextureLimit
                                + " of bgfx's "
                                + maxTextures);
                System.err.flush();
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
            wmoSampler = valid(bgfx_create_uniform("s_wmoDiffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            wmoMaterial = valid(bgfx_create_uniform("u_wmoMaterial", BGFX_UNIFORM_TYPE_VEC4, 1));
            wmoFog = valid(bgfx_create_uniform("u_wmoFog", BGFX_UNIFORM_TYPE_VEC4, 1));
            // .w is taken: the MOCV vertex colour switch (#375, wmoSkyTop()). Do not reuse it.
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
                    wmoEye, wmoProjection, wmoSampler, wmoMaterial, wmoFog, wmoSkyTop, wmoFogRange
                }) {
            if (uniform != INVALID) {
                bgfx_destroy_uniform(uniform);
            }
        }
        wmoProgram = wmoEye = wmoProjection = wmoSampler = INVALID;
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
                // A texture another set has uploaded is shared: only this set's buffers and the
                // textures it creates are charged, in bytes and in handles.
                var plan = planTextures(source);
                var geometryPlan = planGeometry(source);
                long need = setNeed(source, plan, geometryPlan);
                if (!objectsFit(objectBudget, reservedObjectBytes(), need)) {
                    // Geometry no set holds gives its bytes back first (all but the models this
                    // plan shares), then animation poses no frame drew lately (#384).
                    evictGeometry(reservedObjectBytes(), need, geometryPlan);
                    long fits = need;
                    releasePosesUntil(() -> objectsFit(objectBudget, reservedObjectBytes(), fits));
                }
                if (!objectsFit(objectBudget, reservedObjectBytes(), need)) {
                    build.budget = true;
                    build.fail();
                    return 0;
                }
                int images = plan.handles();
                int held = reservedObjectTextures();
                if (!objectTexturesFit(objectTextureLimit, held, images)) {
                    refuseForTextures(build, images, held);
                    return 0;
                }
                if (!objectTexturesFit(liveTextureRoom(), 0, images)) {
                    // Handles of a set released this frame are not back yet: wait a frame or two
                    // rather than fail at the textures step.
                    long now = System.nanoTime();
                    if (build.textureWaits++ == 0) {
                        build.waitStarted = now;
                    }
                    textureWaitSteps++;
                    if (!textureWaitOver(build.waitStarted, now, textureWaitNanos)) {
                        return 0;
                    }
                    refuseForTextures(build, images, held);
                    return 0;
                }
                var built =
                        new SlotObjects(source, objectTextures, plan, objectGeometry, geometryPlan);
                built.reserved = source.water.bytes();
                build.building = built;
                // Buffers for the models this set creates; shared ones are already complete.
                try (var layout = objectLayout()) {
                    for (int m = 0; m < built.geometry.length; m++) {
                        if (!built.createsGeometry[m]) {
                            continue;
                        }
                        short vertexBuffer =
                                valid(
                                        bgfx_create_dynamic_vertex_buffer(
                                                Math.max(1, source.modelVertexCount[m]),
                                                layout,
                                                BGFX_BUFFER_NONE));
                        short indexBuffer;
                        try {
                            indexBuffer =
                                    valid(
                                            bgfx_create_dynamic_index_buffer(
                                                    Math.max(1, source.modelIndexCount[m]),
                                                    BGFX_BUFFER_NONE));
                        } catch (RuntimeException failed) {
                            bgfx_destroy_dynamic_vertex_buffer(vertexBuffer);
                            throw failed;
                        }
                        objectGeometry.created(built.geometry[m], vertexBuffer, indexBuffer);
                    }
                }
                for (int m = 0; m < built.geometry.length; m++) {
                    // A repeat of an earlier model of this set takes that model's buffers.
                    built.modelVertices[m] = built.geometry[m].vertices();
                    built.modelIndices[m] = built.geometry[m].indices();
                }
                built.bytes = source.water.bytes();
                var water = source.water;
                if (water.vertices != null) {
                    // Static: building water never changes; bgfx_copy takes the bytes at once.
                    try (var layout = meshLayout()) {
                        built.waterVertices =
                                valid(
                                        bgfx_create_vertex_buffer(
                                                bgfx_copy(water.vertices),
                                                layout,
                                                BGFX_BUFFER_NONE));
                    }
                    built.waterIndices =
                            valid(
                                    org.lwjgl.bgfx.BGFX.bgfx_create_index_buffer(
                                            bgfx_copy(water.indices),
                                            org.lwjgl.bgfx.BGFX.BGFX_BUFFER_INDEX32));
                    built.bytes += water.bytes();
                }
                return 0;
            }
            var built = build.building;
            step = "geometry";
            // The models this set creates, one at a time: vertices, then indices, then shared.
            while (build.model < built.geometry.length) {
                int m = build.model;
                if (!built.createsGeometry[m]) {
                    build.model++;
                    continue;
                }
                int stride = WorldStreamPackedObjects.GPU_VERTEX_BYTES;
                int vertexBytes = source.modelVertexCount[m] * stride;
                if (build.vertexAt < vertexBytes) {
                    // Whole vertices only: the chunk is rounded down to the vertex stride.
                    int chunk = OBJECT_CHUNK / stride * stride;
                    int count = Math.min(chunk, vertexBytes - build.vertexAt);
                    bgfx_update_dynamic_vertex_buffer(
                            built.modelVertices[m],
                            build.vertexAt / stride,
                            bgfx_copy(
                                    source.vertices.slice(
                                            source.modelVertexFirst[m] * stride + build.vertexAt,
                                            count)));
                    build.vertexAt += count;
                    return count;
                }
                int indexBytes = source.modelIndexCount[m] * 2;
                if (build.indexAt < indexBytes) {
                    int count = Math.min(OBJECT_CHUNK, indexBytes - build.indexAt);
                    bgfx_update_dynamic_index_buffer(
                            built.modelIndices[m],
                            build.indexAt / 2,
                            bgfx_copy(
                                    source.indices.slice(
                                            source.modelIndexFirst[m] * 2 + build.indexAt, count)));
                    build.indexAt += count;
                    return count;
                }
                objectGeometry.complete(built.geometry[m]);
                build.model++;
                build.vertexAt = 0;
                build.indexAt = 0;
            }
            step = "textures";
            // Images whose texture this set shares need no upload: skip them all in this step.
            while (build.image < source.imageCount() && !built.creates[build.image]) {
                built.textures[build.image] = built.entries[build.image].handle();
                build.image++;
            }
            if (build.image < source.imageCount()) {
                int image = build.image;
                int width = source.width(image), height = source.height(image);
                if (build.imagePlan == null) {
                    // One mipmapped texture per image, created empty and owned by the build at
                    // once, so a partly filled chain is destroyed with it. A 256 x 256 RGBA8
                    // chain exceeds one step, so levels are filled in planned row bands; BC
                    // chains are filled in block rows.
                    int format = source.format(image);
                    if (uploadFormat(format, objectFormats) != format) {
                        throw new IllegalStateException("Object texture format not sampled");
                    }
                    var chain =
                            source.pixels.slice(
                                    source.imageOffset(image),
                                    ObjectTextureFormats.chainBytes(format, width, height));
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
                    objectTextures.created(built.entries[image], built.textures[image]);
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
                objectTextures.issued(built.entries[image], count);
                if (++build.imageStep == build.imagePlan.size()) {
                    // Fully issued: later sets share it.
                    objectTextures.complete(built.entries[image]);
                    build.imagePlan = null;
                    build.imageData = null;
                    build.image++;
                }
                return count;
            }
            // Counted once per completed set, however often an earlier build of it restarted:
            // packing decoded every format this renderer does not sample (#374).
            decodedImages += source.decodedImages;
            compressedImages += source.compressedImages;
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
        return beginLate(lease, packed, false);
    }

    /**
     * As above; with {@code replace} the generation must already hold objects, which the new set
     * replaces once built. Both are reserved against the budget meanwhile (textures they share are
     * charged once), so a replacement that does not fit fails as BUDGET and the held set stays.
     */
    LateUpload beginLate(
            WorldStreamState.Lease lease, WorldStreamPackedObjects packed, boolean replace) {
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
        var upload = new LateUpload(lease, new ObjectsBuild(packed), replace);
        if (!lateTarget(lease, replace)) {
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
        if (!upload.dropped && !lateTarget(upload.lease, upload.replace)) {
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
     * objects (a replacement: with the objects it replaces, which it then destroys). Otherwise the
     * built resources are destroyed and the result is DROPPED.
     */
    WorldStreamStatus.LateOutcome finishLate(LateUpload upload, boolean live) {
        requireOwner();
        requireOpen();
        requireLate(upload);
        if (!upload.ready()) {
            throw new IllegalStateException("Late objects upload still building");
        }
        late = null;
        if (upload.dropped || !live || !lateTarget(upload.lease, upload.replace)) {
            upload.build.abandon();
            return WorldStreamStatus.LateOutcome.DROPPED;
        }
        if (upload.build.failed) {
            return upload.build.budget
                    ? WorldStreamStatus.LateOutcome.BUDGET
                    : WorldStreamStatus.LateOutcome.FAILED;
        }
        var slot = slots[upload.lease.slot()];
        if (upload.replace) {
            // Built in full first, so the tile never shows neither set.
            slot.objects.destroy();
        }
        slot.objects = upload.build.take();
        objectsRevision++;
        return WorldStreamStatus.LateOutcome.ATTACHED;
    }

    /**
     * Draws the map's low-resolution horizon from now on; its buffers are allocated once at the
     * horizon's fixed GPU budget. Owner thread only.
     */
    void horizon(WorldStreamHorizon value) throws IOException {
        requireOwner();
        requireOpen();
        java.util.Objects.requireNonNull(value);
        if (horizon != null) {
            throw new IllegalStateException("World horizon already attached");
        }
        try (var layout = meshLayout()) {
            for (int set = 0; set < 2; set++) {
                horizonVertices[set] =
                        valid(
                                bgfx_create_dynamic_vertex_buffer(
                                        WorldStreamHorizon.MAX_VERTICES, layout, BGFX_BUFFER_NONE));
                horizonIndices[set] =
                        valid(
                                bgfx_create_dynamic_index_buffer(
                                        WorldStreamHorizon.MAX_INDICES,
                                        org.lwjgl.bgfx.BGFX.BGFX_BUFFER_INDEX32));
            }
        } catch (IOException | RuntimeException failed) {
            destroyHorizonBuffers();
            throw failed;
        }
        horizon = value;
    }

    private void destroyHorizonBuffers() {
        for (int set = 0; set < 2; set++) {
            if (horizonVertices[set] != INVALID) {
                bgfx_destroy_dynamic_vertex_buffer(horizonVertices[set]);
                horizonVertices[set] = INVALID;
            }
            if (horizonIndices[set] != INVALID) {
                bgfx_destroy_dynamic_index_buffer(horizonIndices[set]);
                horizonIndices[set] = INVALID;
            }
        }
    }

    /** A finished build is waiting or uploading: the owner should spend idle steps on it. */
    boolean horizonUploading() {
        return horizon != null && (horizonStaging != null || horizon.hasReady());
    }

    /**
     * One bounded step of the next horizon's upload into the back buffer set; when the build is
     * fully uploaded the sets swap and the next frame draws it. Owner thread, before drawing.
     */
    void stepHorizon() {
        requireOwner();
        if (horizon == null) {
            return;
        }
        if (horizonStaging == null) {
            horizonStaging = horizon.take();
            horizonStagedBytes = 0;
            if (horizonStaging == null) {
                return;
            }
        }
        var packed = horizonStaging;
        int back = 1 - horizonFront;
        int vertexBytes = packed.vertices().remaining(),
                total = vertexBytes + packed.indices().remaining();
        int budget = WorldStreamHorizon.STEP_BYTES;
        while (budget > 0 && horizonStagedBytes < total) {
            if (horizonStagedBytes < vertexBytes) {
                // Whole vertices only; the rest of this step's budget waits for the next call.
                int stride = WorldStreamHorizon.VERTEX_BYTES;
                int count = Math.min(budget, vertexBytes - horizonStagedBytes) / stride * stride;
                if (count == 0) {
                    break;
                }
                var slice = packed.vertices().slice(horizonStagedBytes, count);
                bgfx_update_dynamic_vertex_buffer(
                        horizonVertices[back], horizonStagedBytes / stride, bgfx_copy(slice));
                horizonStagedBytes += count;
                budget -= count;
            } else {
                int at = horizonStagedBytes - vertexBytes;
                int count = Math.min(budget, packed.indices().remaining() - at) & ~3;
                if (count == 0) {
                    break;
                }
                var slice = packed.indices().slice(at, count);
                bgfx_update_dynamic_index_buffer(horizonIndices[back], at / 4, bgfx_copy(slice));
                horizonStagedBytes += count;
                budget -= count;
            }
        }
        if (horizonStagedBytes == total) {
            horizonFront = back;
            horizonVertexCount = packed.vertexCount();
            horizonIndexCount = packed.indexCount();
            horizonTiles = packed.tiles();
            horizonBuildNanos = packed.buildNanos();
            horizonRanges = packed.ranges();
            horizonUploads++;
            horizonStaging = null;
            horizonSwapped = true;
            if (horizonUploads == 1) {
                // One diagnostic per session: the first horizon reached the GPU.
                System.err.printf(
                        "World horizon uploaded: %d tiles, %d triangles, %d GPU bytes,"
                                + " rebuild %.1f ms%n",
                        horizonTiles,
                        horizonIndexCount / 3,
                        WorldStreamHorizon.GPU_BUDGET_BYTES,
                        horizonBuildNanos / 1e6);
                System.err.flush();
            }
        }
    }

    /**
     * The horizon needs a frame even if nothing else changed: it was just attached and has not been
     * asked for yet, or a rebuilt horizon is waiting to be uploaded.
     */
    boolean horizonWaiting() {
        return horizon != null && (!horizonRequested || horizonSwapped);
    }

    HorizonCounts horizonCounts() {
        requireOwner();
        return new HorizonCounts(
                horizon == null ? 0 : horizonTiles,
                horizon == null ? 0 : horizonIndexCount / 3,
                horizon == null ? 0 : WorldStreamHorizon.GPU_BUDGET_BYTES,
                horizonBuildNanos,
                horizonUploads,
                frameHorizonSubmits);
    }

    /** Asks for the horizon around the camera; the build and its upload never block a frame. */
    private void refreshHorizon(TerrainCamera camera) {
        horizon.request(camera.x(), camera.y());
        horizonRequested = true;
        horizonSwapped = false;
    }

    /** Whether a tile is drawn at full detail this frame (an active slot holds it). */
    private boolean resident(int x, int y) {
        for (var lease : active) {
            var slot = slots[lease.slot()];
            if (slot.x == x && slot.y == y) {
                return true;
            }
        }
        return false;
    }

    /**
     * The horizon's draws this frame: every tile not drawn at full detail now, and the skirts that
     * face one. Chosen per frame from the active slots, so a tile committed or evicted since the
     * last rebuild is handled at once. {@code drawn} is this frame's {@link #horizonDrawn}, taken
     * once with its fog: the horizon's failure flag can flip mid-frame, and the fog and the horizon
     * must agree.
     */
    private void drawHorizon(MemoryStack stack, boolean drawn) {
        var ranges = horizonRanges;
        if (!drawn) {
            frameHorizonSubmits = 0;
            return;
        }
        if (horizonDrawRanges.length < ranges.maxDrawRangeInts()) {
            horizonDrawRanges = new int[ranges.maxDrawRangeInts()];
        }
        int count = ranges.drawRanges(this::resident, horizonDrawRanges);
        frameHorizonSubmits = count / 2;
        bgfx_set_uniform(atlasUniform, stack.floats(0, 0, 0, 0), 1);
        for (int i = 0; i < count; i += 2) {
            bgfx_set_dynamic_vertex_buffer(0, horizonVertices[horizonFront], 0, horizonVertexCount);
            bgfx_set_dynamic_index_buffer(
                    horizonIndices[horizonFront], horizonDrawRanges[i], horizonDrawRanges[i + 1]);
            bgfx_set_state(TERRAIN_STATE, 0);
            bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
        }
    }

    /** Whether this frame draws the horizon: one is uploaded and its rebuilds have not failed. */
    private boolean horizonDrawn() {
        return horizonDrawn(horizon, horizonRanges);
    }

    /** {@link #horizonDrawn} for a horizon and its uploaded ranges, either possibly null. */
    static boolean horizonDrawn(
            WorldStreamHorizon horizon, com.vibeminer.rendering.world.HorizonMesh.Ranges ranges) {
        return horizon != null && ranges != null && !horizon.failed();
    }

    /**
     * The sky of the next frames, recorded fog included; #177 feeds it from the light tables and
     * null is the explorer's own. Each frame turns it into its fog ({@link #frameFog}), with or
     * without the horizon.
     */
    void sky(WorldStreamTransport.Sky value) {
        requireOwner();
        recordedSky = value == null ? WorldStreamTransport.Sky.EXPLORER : value;
    }

    /**
     * A frame's fog from its sky ({@link WorldFog#sky}). When the horizon is drawn this frame
     * (#372, option (a)) the cut moves out to it; a recorded fog end below the horizon is kept, so
     * foggy zones stay foggy and fog is full from that end to the cut, and with no recorded fog the
     * end moves out to the horizon too (#547). Before the first upload and after the horizon has
     * failed, streamed terrain and objects keep the stream cut. The maximum is 1 on every path:
     * nothing past the cut is discarded, so fog alone must hide it.
     */
    static WorldFog frameFog(WorldStreamTransport.Sky sky, boolean horizonDrawn) {
        return WorldFog.sky(sky, horizonDrawn ? WorldStreamHorizon.FOG_END : Float.NaN);
    }

    /**
     * Terrain lighting (#373) from MCNR normals and the World light: on by default, as the client
     * draws; {@code -Dvimi.world.terrainLighting=false}, forwarded by the application to this
     * helper, draws terrain at its albedo for comparison.
     */
    static final boolean TERRAIN_LIGHTING =
            terrainLightingEnabled(System.getProperty("vimi.world.terrainLighting"));

    /** Only {@code false} (any letter case) turns terrain lighting off. */
    static boolean terrainLightingEnabled(String property) {
        return !"false".equalsIgnoreCase(property);
    }

    /** Attaches the walking body's model; drawn wherever a frame's camera carries its pose. */
    void body(WorldStreamBodyFile.Body value) throws IOException {
        requireOwner();
        requireOpen();
        java.util.Objects.requireNonNull(value);
        if (wmoProgram == INVALID) {
            throw new IOException("The body needs the objects pass");
        }
        // The body is charged to the objects' accounting like an object set (#658, #660): its
        // handles and bytes count against the same limits, and object sets see them as held.
        int handles = WorldStreamBodyDraw.handles(value);
        long bytes = WorldStreamBodyDraw.gpuBytes(value);
        int heldNow = reservedObjectTextures() - (body == null ? 0 : body.handles());
        long replaced = body == null ? 0 : body.bytes();
        // Geometry no set holds gives its bytes back first, as for an object set.
        evictGeometry(reservedObjectBytes() - replaced, bytes, null);
        long reservedNow = reservedObjectBytes() - replaced;
        if (!objectsFit(objectBudget, reservedNow, bytes)
                || !objectTexturesFit(objectTextureLimit, heldNow, handles)
                || !objectTexturesFit(liveTextureRoom(), 0, handles)) {
            throw new IOException(
                    "The body does not fit the objects' budget: "
                            + handles
                            + " textures, "
                            + bytes
                            + " bytes");
        }
        WorldStreamBodyDraw built;
        try (var layout = objectLayout()) {
            built = new WorldStreamBodyDraw(value, layout);
        }
        if (body != null) {
            body.close();
        }
        body = built;
    }

    /** The walking body's pose for the next frame, or null to draw none. */
    void bodyPose(WorldStreamTransport.Body value) {
        bodyPose = value;
    }

    /** Object submits of the walking body in the last drawn frame (proofs). */
    int bodySubmits() {
        return frameBodySubmits;
    }

    /** Vertex uploads of the walking body so far (proofs). */
    long bodyUploads() {
        return body == null ? 0 : body.uploads();
    }

    /** The light of the next frames; #177 feeds it from the light tables. */
    void light(WorldLight value) {
        requireOwner();
        light = java.util.Objects.requireNonNull(value);
    }

    /** Proof seam: overrides {@link #TERRAIN_LIGHTING} for this renderer. */
    void terrainLighting(boolean enabled) {
        requireOwner();
        terrainLighting = enabled;
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
     * counts and pixels with culling on and off. Portal culling (#383) uses the same view, so it is
     * off too. Production never turns it off.
     */
    void objectCulling(boolean enabled) {
        requireOwner();
        objectCulling = enabled;
    }

    /** Proof seam: false draws no building water, so a proof can compare pixels without it. */
    void objectWater(boolean enabled) {
        requireOwner();
        objectWater = enabled;
    }

    private boolean objectWater = true;

    /** Object animation on or off (#384); a proof compares both. */
    void objectAnimation(boolean enabled) {
        requireOwner();
        objectAnimation = enabled;
    }

    /** Proofs only: the animation clock every following frame uses, or -1 for the real clock. */
    void animationClockForProof(long millis) {
        requireOwner();
        proofClock = millis;
    }

    /**
     * Proof seam: false draws every placement as its own one-instance draw, the submit pattern
     * before instancing (#382), so a proof can compare draw counts and pixels. Production never
     * turns it off.
     */
    void objectInstancing(boolean enabled) {
        requireOwner();
        objectInstancing = enabled;
    }

    /** Proof seam: caps the instances drawn per frame, to exercise the overflow path. */
    void instanceLimit(int limit) {
        requireOwner();
        instanceLimit = limit;
    }

    /**
     * WMO vertex colours (MOCV, #375) added to the building light of groups lit as outdoors. On by
     * default; {@code -Dvimi.world.wmoVertexColours=false}, which the application forwards to this
     * helper, turns them off. The colours are always uploaded; the flag only reaches the shader
     * (u_wmoSkyTop.w), so turning it off needs no other shader.
     */
    static final boolean VERTEX_COLOURS =
            vertexColoursEnabled(System.getProperty("vimi.world.wmoVertexColours"));

    /** Only {@code false} (any letter case) turns WMO vertex colours off. */
    static boolean vertexColoursEnabled(String property) {
        return !"false".equalsIgnoreCase(property);
    }

    /** Proof seam: overrides {@link #VERTEX_COLOURS} for this renderer. */
    void vertexColours(boolean enabled) {
        requireOwner();
        vertexColours = enabled;
    }

    /** The object sky top uniform: the fog's top colour, with w the vertex colour switch. */
    static float[] wmoSkyTop(WorldFog fog, boolean vertexColours) {
        float[] value = fog.topUniform();
        value[3] = vertexColours ? 1 : 0;
        return value;
    }

    /** Changes whenever late objects become drawable, so an unchanged camera redraws once. */
    long objectsRevision() {
        return objectsRevision;
    }

    /**
     * Changes every {@link WorldObjectAnimation#REDRAW_MILLIS} while the last frame drew animated
     * placements (#384), so an unchanged camera redraws at that cadence and the idle keeps playing;
     * constant (-1) otherwise, so a still scene without animation costs no frames.
     */
    long animationRevision() {
        return lastFrameAnimated == 0
                ? -1
                : (System.nanoTime() - animationEpoch)
                        / 1_000_000
                        / WorldObjectAnimation.REDRAW_MILLIS;
    }

    /** Object animation counts of the frame just drawn (#384); see {@link #frameAnimated}. */
    record AnimationCounts(
            int animated,
            int animatedStatic,
            int far,
            int refused,
            int skins,
            int skinVertices,
            int stale,
            long poseBytes) {}

    AnimationCounts animationCounts() {
        return new AnimationCounts(
                frameAnimated,
                frameAnimatedStatic,
                frameAnimatedFar,
                frameAnimatedRefused,
                frameSkins,
                frameSkinVertices,
                frameStalePoses,
                poseBytes());
    }

    private boolean lateTarget(WorldStreamState.Lease lease, boolean replace) {
        var slot = slots[lease.slot()];
        return lateTarget(lease, slot.ready, slot.lease, slot.objects != null, active, replace);
    }

    /** The exact active generation in its ready slot, still without objects. */
    static boolean lateTarget(
            WorldStreamState.Lease lease,
            boolean ready,
            WorldStreamState.Lease slotLease,
            boolean hasObjects,
            List<WorldStreamState.Lease> active) {
        return lateTarget(lease, ready, slotLease, hasObjects, active, false);
    }

    /** As above; a replacement needs the generation to hold the objects it replaces. */
    static boolean lateTarget(
            WorldStreamState.Lease lease,
            boolean ready,
            WorldStreamState.Lease slotLease,
            boolean hasObjects,
            List<WorldStreamState.Lease> active,
            boolean replace) {
        return ready && lease.equals(slotLease) && hasObjects == replace && active.contains(lease);
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
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false);
        bgfx_vertex_layout_end(layout);
        if (layout.stride() != WorldStreamPackedObjects.GPU_VERTEX_BYTES) {
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
        if (cameraRevision <= 0 || policy <= 0 || !WorldFrameLimits.fits(width, height)) {
            throw new IllegalArgumentException("Frame ticket bounds");
        }
        return drawView(camera, cameraRevision, policy, width, height, width, height);
    }

    /**
     * #381 spike: draw into a window's backbuffer at its own size (up to 8192 per side). The
     * projection keeps the window's aspect; its matrix depends on the ratio only.
     */
    Ticket drawWindow(
            TerrainCamera camera, long cameraRevision, long policy, int width, int height) {
        requireOwner();
        requireOpen();
        if (cameraRevision <= 0
                || policy <= 0
                || width < 1
                || width > 8192
                || height < 1
                || height > 8192) {
            throw new IllegalArgumentException("Window frame bounds");
        }
        double fit = Math.min(1, Math.min(1280.0 / width, 960.0 / height));
        int projectionWidth = Math.max(1, (int) Math.round(width * fit));
        int projectionHeight = Math.max(1, (int) Math.round(height * fit));
        return drawView(
                camera, cameraRevision, policy, width, height, projectionWidth, projectionHeight);
    }

    private Ticket drawView(
            TerrainCamera camera,
            long cameraRevision,
            long policy,
            int width,
            int height,
            int projectionWidth,
            int projectionHeight) {
        bgfx_set_view_rect(0, 0, 0, width, height);
        bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 0, 0);
        if (horizon != null) {
            refreshHorizon(camera);
        }
        // With a drawn horizon, one fog range reaches out to it for every pass (#372, option (a)).
        // Taken once: the fog and the horizon draw must agree for the whole frame.
        boolean horizonThisFrame = horizonDrawn();
        var fog = frameFog(recordedSky, horizonThisFrame);
        try (var stack = MemoryStack.stackPush()) {
            var origin =
                    new TerrainScene.Origin(
                            (float) camera.x(), (float) camera.y(), (float) camera.z());
            var matrix = stack.mallocFloat(16).put(camera.modelToEye(origin)).flip();
            var project =
                    stack.mallocFloat(16)
                            .put(
                                    TerrainCamera.reversedProjection(
                                            projectionWidth, projectionHeight))
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
                bgfx_set_uniform(wmoSkyTop, stack.floats(wmoSkyTop(fog, vertexColours)), 1);
                bgfx_set_uniform(wmoFogRange, stack.floats(fog.rangeUniform()), 1);
            }
            bgfx_set_uniform(worldFog, stack.floats(fog.fogUniform()), 1);
            bgfx_set_uniform(worldSkyTop, stack.floats(fog.topUniform()), 1);
            bgfx_set_uniform(worldFogRange, stack.floats(fog.rangeUniform()), 1);
            // The recorded direct and ambient light of the camera's sky, when it has one (#177).
            bgfx_set_uniform(
                    worldLight,
                    stack.floats(light.withRecorded(recordedSky).uniform(terrainLighting)),
                    3);
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
            // The horizon right after the streamed terrain, before objects and the sky, with the
            // terrain state and the vertex-colour path (no atlas).
            if (horizon != null) {
                drawHorizon(stack, horizonThisFrame);
            }
            // One fixed set of object uniform buffers per frame: per-placement work allocates
            // nothing on the frame's MemoryStack, whatever the number of placements.
            var frame =
                    wmoProgram == INVALID
                            ? null
                            : ObjectFrame.on(stack, cutoutCoverage(targetSamples));
            framePlacements = frameCulled = frameObjectSubmits = frameSortedSubmits = 0;
            frameInstances = frameInstancesDropped = 0;
            framePortalWalks = framePortalCulled = framePortalPlacementsCulled = 0;
            frameWaterSubmits = frameWaterCulled = 0;
            portalCulling.frame(view);
            frameMaterial = -1;
            frameBodySubmits = 0;
            frameAnimated = frameAnimatedStatic = frameSkins = frameSkinVertices = 0;
            frameStalePoses = frameAnimatedFar = frameAnimatedRefused = 0;
            animationClock =
                    proofClock >= 0 ? proofClock : (System.nanoTime() - animationEpoch) / 1_000_000;
            animationFrame++;
            boolean bodyDrawn =
                    wmoProgram != INVALID
                            && body != null
                            && bodyPose != null
                            && !bodyPose.hidden()
                            && body.prepare(bodyPose);
            if (wmoProgram != INVALID) {
                for (var lease : active) {
                    var objects = slots[lease.slot()].objects;
                    if (objects != null) {
                        drawObjects(frame, view, objects, origin);
                    }
                }
                if (bodyDrawn) {
                    drawBody(frame, origin, false);
                }
                skinPoses();
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
            // Static diagnostic water after ALL opaque terrain: the nearest surface wins under
            // strict reversed depth, so coincident duplicates (shared chunk edges, or two source
            // layers at one height) cannot blend twice. RGB only keeps the opaque output alpha.
            for (var lease : active) {
                var slot = slots[lease.slot()];
                if (slot.water == 0) {
                    continue;
                }
                // w = -1 marks a water draw: the shader shades it by liquid kind and depth (#171).
                bgfx_set_uniform(atlasUniform, stack.floats(slot.anchorX, slot.anchorY, 0, -1), 1);
                bgfx_set_uniform(splatUniform, stack.floats(0, 0, 0, 0), 1);
                bgfx_set_uniform(worldWater, stack.floats(waterUniform(recordedSky)), 4);
                bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
                bindSplat(slot);
                // Water vertices follow the terrain's; its indices are relative to them.
                bgfx_set_dynamic_vertex_buffer(
                        0, slot.vertices, slot.terrainVertices, slot.waterVertices);
                bgfx_set_dynamic_index_buffer(slot.indices, slot.terrainIndices, slot.waterIndices);
                // The shader's alpha blends water (its opacity) and keeps flat liquids opaque.
                bgfx_set_state(
                        BGFX_STATE_WRITE_RGB
                                | BGFX_STATE_WRITE_Z
                                | BGFX_STATE_DEPTH_TEST_GREATER
                                | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC(
                                        org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA,
                                        org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA),
                        0);
                bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
            }
            // Building water (WMO liquid) with the same shader, light and state as terrain water,
            // after it; a placement culled this frame, or an interior its walk did not reach,
            // draws none.
            if (wmoProgram != INVALID && objectWater) {
                for (var lease : active) {
                    var slot = slots[lease.slot()];
                    if (slot.objects != null && slot.objects.waterVertices != INVALID) {
                        drawObjectWater(slot, slot.objects, recordedSky);
                    }
                }
            }
            // Alpha-blended object ranges last, after water, farthest placement first.
            if (wmoProgram != INVALID) {
                drawBlended(frame, view, origin);
                if (bodyDrawn) {
                    drawBody(frame, origin, true);
                }
            }
        }
        lastFrameAnimated = frameAnimated;
        lastFrame =
                new FrameCounts(
                        framePlacements,
                        frameCulled,
                        frameObjectSubmits,
                        frameSortedSubmits,
                        frameCameraUniforms,
                        frameChunks,
                        frameChunksCulled,
                        frameTerrainSubmits,
                        frameInstances,
                        frameInstancesDropped,
                        framePortalWalks,
                        framePortalCulled,
                        framePortalPlacementsCulled);
        return new Ticket(cameraRevision, content, policy, width, height);
    }

    /**
     * One object set's building water: each run whose placement this frame's culling kept, and
     * whose group (an interior of a portal set) the placement's walk reached, drawn as terrain
     * water is (u_worldAtlas.w = -1, the water light, blended, strict reversed depth with depth
     * write, so a building recorded by two tiles draws its water once).
     */
    private void drawObjectWater(
            Slot slot, SlotObjects objects, WorldStreamTransport.Sky recordedSky) {
        try (var stack = MemoryStack.stackPush()) {
            int stride = WorldObjectWater.RUN_INTS;
            for (int r = 0; r < objects.waterRuns.length / stride; r++) {
                int p = objects.waterRuns[r * stride + 2], mesh = objects.waterRuns[r * stride + 3];
                if (!objects.visible[p] || objects.dropped[p]) {
                    frameWaterCulled++;
                    continue;
                }
                var walked = objects.portalVisible[p];
                var portals = objects.portals[objects.placementModels[p]];
                if (walked != null
                        && portals != null
                        && portalSkipped(objects, portals, walked, mesh)) {
                    frameWaterCulled++;
                    continue;
                }
                bgfx_set_uniform(atlasUniform, stack.floats(slot.anchorX, slot.anchorY, 0, -1), 1);
                bgfx_set_uniform(splatUniform, stack.floats(0, 0, 0, 0), 1);
                bgfx_set_uniform(worldWater, stack.floats(waterUniform(recordedSky)), 4);
                bgfx_set_texture(0, sampler, slot.texture, SAMPLER_FLAGS);
                bindSplat(slot);
                bgfx_set_vertex_buffer(0, objects.waterVertices, 0, 0xFFFFFFFF);
                org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer(
                        objects.waterIndices,
                        objects.waterRuns[r * stride],
                        objects.waterRuns[r * stride + 1]);
                bgfx_set_state(
                        BGFX_STATE_WRITE_RGB
                                | BGFX_STATE_WRITE_Z
                                | BGFX_STATE_DEPTH_TEST_GREATER
                                | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC(
                                        org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA,
                                        org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA),
                        0);
                bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
                frameWaterSubmits++;
            }
        }
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
     * The walking body's opaque and cutout ranges ({@code sorted} false), or its blended then
     * additive ones, under its one instance row; with the objects' program, materials and states.
     * The sorted ranges draw after every object's blended ranges, in file order: they are not
     * sorted by distance with the objects' (#552), so a blended object between the camera and the
     * body draws under the body's blended ranges.
     */
    private void drawBody(ObjectFrame frame, TerrainScene.Origin origin, boolean sorted) {
        var model = body.body;
        int[] ranges = model.ranges(), blends = model.blends();
        boolean placed = false;
        for (int step = sorted ? 1 : 0; step < (sorted ? 3 : 1); step++) {
            for (int r = 0; r < blends.length; r++) {
                int kind = WorldStreamObjectsFile.kind(blends[r]);
                int wanted =
                        kind == WorldStreamObjectsFile.BLENDED
                                ? 1
                                : kind == WorldStreamObjectsFile.ADDITIVE ? 2 : 0;
                if (wanted != step) {
                    continue;
                }
                if (!placed) {
                    if (!body.place(bodyPose, origin)) {
                        return;
                    }
                    placed = true;
                }
                if (blends[r] != frameMaterial) {
                    bgfx_set_uniform(wmoMaterial, frame.material(blends[r]), 1);
                    frameMaterial = blends[r];
                }
                bgfx_set_dynamic_vertex_buffer(0, body.vertices, 0, model.vertexCount());
                org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer(
                        body.indices, ranges[r * 3], ranges[r * 3 + 1]);
                org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_buffer(body.instance(), 0, 1);
                bgfx_set_texture(
                        0, wmoSampler, body.textures[ranges[r * 3 + 2]], OBJECT_SAMPLER_FLAGS);
                bgfx_set_state(
                        !sorted
                                ? frame.state(blends[r])
                                : kind == WorldStreamObjectsFile.ADDITIVE
                                        ? ADDITIVE_STATE
                                        : BLENDED_STATE,
                        0);
                bgfx_submit(0, wmoProgram, 0, BGFX_DISCARD_ALL);
                frameObjectSubmits++;
                frameBodySubmits++;
                if (sorted) {
                    frameSortedSubmits++;
                }
            }
        }
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
     * The sampler flags of a range: {@link #OBJECT_SAMPLER_FLAGS}, clamped along U and V as its
     * class says (MOMT F_CLAMP_S and F_CLAMP_T, {@link
     * com.vibeminer.rendering.world.WmoMaterialClass}). Sampler state only: no shader changes.
     */
    static int objectSamplerFlags(int rangeClass) {
        return OBJECT_SAMPLER_FLAGS | clampFlags(rangeClass);
    }

    /** The bgfx clamp addressing flags a range class asks for. */
    static int clampFlags(int rangeClass) {
        return ((rangeClass & com.vibeminer.rendering.world.WorldObjectPicking.CLAMP_S) != 0
                        ? BGFX_SAMPLER_U_CLAMP
                        : 0)
                | ((rangeClass & com.vibeminer.rendering.world.WorldObjectPicking.CLAMP_T) != 0
                        ? BGFX_SAMPLER_V_CLAMP
                        : 0);
    }

    /**
     * Whether cutout ranges use alpha to coverage on a multisampled target. They do not: on an
     * integrated GPU (Intel UHD 770) it took Goldshire's rotate p95 from 18 to 30 ms, all of it in
     * the cutout object ranges. Keeping it only for placements near the camera does not help: the
     * cost comes from the dense mid-distance foliage, beyond about 40 yd under the canopy and 160
     * yd above it, and that is also the only place it visibly softens leaf silhouettes against the
     * sky; within a band cheap enough to keep 18 ms it changed no pixels. The coverage path stays
     * for a later per-GPU setting.
     */
    static final boolean CUTOUT_COVERAGE = false;

    /** Whether cutouts use alpha to coverage on a target with {@code samples} samples. */
    static boolean cutoutCoverage(int samples) {
        return CUTOUT_COVERAGE && samples > 1;
    }

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

    /**
     * The u_wmoMaterial value of a range class: x the alpha-test reference (cutouts), y 1 to output
     * sampled alpha (blended and additive), z 1 for the alpha-to-coverage ramp (cutouts on a
     * multisampled target), w 1 for unlit plus 2 for additive (its fog fades it out instead of
     * adding the fog colour) plus 4 for {@link WorldStreamObjectsFile#INTERIOR_LIGHT} (lit by the
     * vertex colour instead of the World sun, #383).
     */
    static float[] materialUniform(int rangeClass, boolean coverage) {
        int kind = WorldStreamObjectsFile.kind(rangeClass);
        float unlit = (rangeClass & WorldStreamObjectsFile.UNLIT) != 0 ? 1 : 0;
        float interior = (rangeClass & WorldStreamObjectsFile.INTERIOR_LIGHT) != 0 ? 4 : 0;
        float[] value =
                switch (kind) {
                    case WorldStreamObjectsFile.OPAQUE -> new float[] {0, 0, 0, 0};
                    case WorldStreamObjectsFile.CUTOUT -> cutoutMaterial(coverage);
                    case WorldStreamObjectsFile.BLENDED -> new float[] {0, 1, 0, 0};
                    case WorldStreamObjectsFile.ADDITIVE -> new float[] {0, 1, 0, 2};
                    default -> throw new IllegalArgumentException("Range class");
                };
        value[3] += unlit + interior;
        return value;
    }

    /**
     * Additive state (MOMT blend mode 3, #367): depth tested, no depth or alpha write, source
     * colour times its alpha added to the destination (GxBlend_Add: SrcAlpha, One).
     */
    static final long ADDITIVE_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_DEPTH_TEST_GREATER
                    | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC(
                            org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA,
                            org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ONE);

    /** Blend state: depth tested, no depth or alpha write, source-alpha over. */
    static final long BLENDED_STATE =
            BGFX_STATE_WRITE_RGB
                    | BGFX_STATE_DEPTH_TEST_GREATER
                    | org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC(
                            org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA,
                            org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA);

    /**
     * Bits of a blend key holding its entry: the tile's position in the active (producer) order
     * above the placement's bits: 13 placement bits for the tile ceiling of 5,184 placements (64
     * buildings and 5,120 doodads) and 5 position bits for the 26 slots of a far session (#276).
     */
    static final int BLEND_ENTRY_BITS = 18, BLEND_PLACEMENT_BITS = 13;

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
     * dropped mantissa bits are 2^-34 relative.
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
                // drawObjects ran over the same active slots this frame, so visible and dropped
                // hold this frame's answers.
                if (!sortedPassDraws(
                        objects.blendedModels,
                        objects.unsortedModels,
                        objects.placementModels,
                        objects.visible,
                        objects.dropped,
                        p)) {
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
        sortKeys(blendKeys, count);
        for (int i = count - 1; i >= 0; i--) {
            long key = blendKeys[i];
            var objects = slots[active.get(blendPosition(key)).slot()].objects;
            int p = blendPlacement(key);
            // Still one placement at a time, back to front: a one-instance draw. A portal root
            // (#383) reuses the groups its opaque pass walked this frame.
            frame.single[0] = p;
            int drawn = instances(frame, objects, frame.single, 0, 1, origin);
            drawSortedRanges(
                    frame,
                    objects,
                    p,
                    drawn,
                    view == null ? null : objects.portalVisible[p],
                    origin);
        }
    }

    /**
     * Whether the sorted pass draws placement p: its model has blended or additive ranges, this
     * frame's cull kept it, and the opaque/cutout pass did not drop it for lack of instance data. A
     * dropped placement is skipped here too, so it never draws its glass without its walls (#382);
     * a model with only sorted ranges has no walls to lose.
     */
    static boolean sortedPassDraws(
            boolean[] blendedModels,
            boolean[] unsortedModels,
            int[] placementModels,
            boolean[] visible,
            boolean[] dropped,
            int p) {
        int m = placementModels[p];
        return blendedModels[m] && visible[p] && !(unsortedModels[m] && dropped[p]);
    }

    /**
     * Opaque and cutout BASIC exterior objects after all terrain and before water, sharing
     * terrain's camera, reversed depth and float origin. The visible placements are grouped by
     * model (#382) and each model's opaque and cutout ranges are drawn once, with all its visible
     * placements as instances. With instancing off (a proof seam) every placement is its own
     * one-instance draw, as before.
     */
    private void drawObjects(
            ObjectFrame frame,
            WorldStreamObjectCulling.View view,
            SlotObjects objects,
            TerrainScene.Origin origin) {
        int placements = objects.placementModels.length;
        for (int p = 0; p < placements; p++) {
            framePlacements++;
            objects.visible[p] = view == null || view.visible(objects.spheres, p);
            objects.grouped[p] = objects.visible[p];
            objects.dropped[p] = false;
            if (!objects.visible[p]) {
                frameCulled++;
            }
        }
        // Portal roots (#383 step 3b) walk their portals from the camera first. Each has its own
        // visible set, so it leaves the model groups and draws as its own one-instance draw that
        // skips the interior meshes of groups the walk does not reach; the sorted pass reuses the
        // set, and a root dropped for lack of instance data is skipped there too.
        if (view != null) {
            for (int p = 0; p < placements; p++) {
                var groups = objects.portalVisible[p];
                if (groups == null || !objects.visible[p]) {
                    continue;
                }
                objects.grouped[p] = false;
                int m = objects.placementModels[p];
                if (portalCulling.walk(objects.portals[m], objects.placements, p, groups)) {
                    framePortalWalks++;
                }
                if (objects.unsortedModels[m]) {
                    frame.single[0] = p;
                    int drawn = instances(frame, objects, frame.single, 0, 1, origin);
                    drawRanges(frame, objects, m, drawn, groups);
                }
            }
            // Placements tied to an interior group (set doodads inside a room) follow their
            // parent's walk: one it did not reach this frame is skipped by both passes.
            for (int p = 0; p < placements; p++) {
                if (objects.visible[p]
                        && portalCulled(
                                objects.placementParents,
                                objects.placementGroups,
                                objects.visible,
                                objects.portalVisible,
                                p)) {
                    objects.visible[p] = false;
                    objects.grouped[p] = false;
                    framePortalPlacementsCulled++;
                }
            }
        }
        if (objects.animated.length > 0) {
            drawAnimated(frame, objects, origin);
        }
        int models = objects.models.length / 2;
        WorldObjectInstances.groupByModel(
                objects.placementModels, objects.grouped, models, objects.starts, objects.order);
        for (int m = 0; m < models; m++) {
            int first = objects.starts[m], count = objects.starts[m + 1] - first;
            if (count == 0 || !objects.unsortedModels[m]) {
                continue;
            }
            if (objectInstancing) {
                int drawn = instances(frame, objects, objects.order, first, count, origin);
                drawRanges(frame, objects, m, drawn, null);
            } else {
                for (int i = 0; i < count; i++) {
                    int drawn = instances(frame, objects, objects.order, first + i, 1, origin);
                    drawRanges(frame, objects, m, drawn, null);
                }
            }
        }
    }

    /**
     * Draws this set's animatable placements that animate this frame (#384) from their phase's pose
     * and takes them out of the static model groups. A set whose animated placements all lie beyond
     * {@link WorldObjectAnimation#RADIUS} is skipped without visiting them. Within a set, a
     * placement culled this frame, beyond the radius, over {@link
     * WorldObjectAnimation#FRAME_INSTANCES}, or whose pose cannot be created within {@link
     * WorldObjectAnimation#GPU_BYTES} and the object budget stays in its static group. A pose whose
     * clock stepped is queued for {@link #skinPoses}, which serves the stalest poses first.
     */
    private void drawAnimated(ObjectFrame frame, SlotObjects objects, TerrainScene.Origin origin) {
        if (!objectAnimation) {
            frameAnimatedStatic += objects.animated.length;
            return;
        }
        double radius = WorldObjectAnimation.RADIUS;
        if (WorldObjectAnimation.boundsDistance(
                        objects.animatedBounds, origin.x(), origin.y(), origin.z())
                > radius) {
            return;
        }
        int stride = WorldStreamObjectsFile.PLACEMENT_DOUBLES;
        for (int group = 0; group < objects.members.length; group++) {
            int[] candidates = objects.members[group];
            if (candidates == null) {
                continue;
            }
            int m = group / WorldObjectAnimation.PHASES,
                    phase = group % WorldObjectAnimation.PHASES;
            var animation = objects.animations[m];
            int members = 0;
            WorldObjectAnimation.Pose pose = null;
            boolean refused = false;
            for (int p : candidates) {
                if (!objects.grouped[p]) {
                    continue; // culled this frame: neither drawn nor a reason to redraw
                }
                double dx = objects.placements[p * stride] - origin.x();
                double dy = objects.placements[p * stride + 1] - origin.y();
                double dz = objects.placements[p * stride + 2] - origin.z();
                if (dx * dx + dy * dy + dz * dz > radius * radius) {
                    frameAnimatedStatic++;
                    frameAnimatedFar++;
                    continue;
                }
                if (refused || frameAnimated + members >= WorldObjectAnimation.FRAME_INSTANCES) {
                    frameAnimatedStatic++;
                    frameAnimatedRefused++;
                    continue;
                }
                if (pose == null) {
                    pose = pose(objects, group, animation);
                    if (pose == null) {
                        refused = true;
                        frameAnimatedStatic++;
                        frameAnimatedRefused++;
                        continue;
                    }
                }
                objects.poseMembers[members++] = p;
            }
            if (members == 0) {
                continue;
            }
            for (int i = 0; i < members; i++) {
                objects.grouped[objects.poseMembers[i]] = false;
            }
            int drawn = instances(frame, objects, objects.poseMembers, 0, members, origin);
            if (drawn > 0) {
                drawRangesStep(frame, objects, m, drawn, STEP_UNSORTED, null, pose);
                // Only what was drawn animated asks for idle redraws (animationRevision).
                frameAnimated += drawn;
                long millis =
                        WorldObjectAnimation.millis(
                                animationClock,
                                animation.animation.durationMillis(),
                                phase,
                                WorldObjectAnimation.stepOffset(objects.stepSet, m, phase));
                pose.used(animationFrame, millis);
                if (pose.skinned() != millis) {
                    queueSkin(pose);
                }
            }
        }
    }

    /**
     * Pose {@code group} (model times phases plus phase) of a set, created when first needed with
     * the model's baked vertices, so it draws its static pose until its first skin; null when the
     * pose budgets or bgfx refuse it even after releasing poses no frame drew lately.
     */
    private WorldObjectAnimation.Pose pose(
            SlotObjects objects, int group, WorldStreamPackedObjects.ObjectAnimation animation) {
        var pose = objects.poses[group];
        if (pose != null) {
            return pose;
        }
        long bytes = WorldObjectAnimation.bytes(animation);
        if (!poseFits(bytes)) {
            // Geometry no set holds gives object budget back first, as for an object set.
            evictGeometry(reservedObjectBytes(), bytes, null);
            releasePoses(bytes);
            if (!poseFits(bytes)) {
                return null;
            }
        }
        try (var layout = objectLayout()) {
            pose = WorldObjectAnimation.Pose.create(animation, layout);
        } catch (IOException unavailable) {
            return null;
        }
        if (pose == null) {
            return null;
        }
        objects.poses[group] = pose;
        objects.poseBytes += bytes;
        return pose;
    }

    /**
     * Destroys geometry zombies until {@code need} more bytes fit beside {@code reserved} under the
     * object budget, or none is left; keeps the zombies {@code keep} (when not null) shares.
     */
    private void evictGeometry(long reserved, long need, WorldStreamObjectGeometry.Plan keep) {
        long shortfall = reserved + need - objectBudget;
        if (shortfall <= 0) {
            return;
        }
        if (keep == null) {
            objectGeometry.evict(shortfall);
        } else {
            objectGeometry.evict(shortfall, keep);
        }
    }

    private boolean poseFits(long bytes) {
        return poseBytes() + bytes <= WorldObjectAnimation.GPU_BYTES
                && objectsFit(objectBudget, reservedObjectBytes(), bytes);
    }

    /**
     * Releases poses no frame drew in the last {@link WorldObjectAnimation#IDLE_FRAMES} frames,
     * least recently drawn first, until {@code needed} more pose bytes fit or none is left; returns
     * the bytes released. A pose refused for its budgets, and an object set refused for the object
     * budget, ask for this before giving up.
     */
    private long releasePoses(long needed) {
        return releasePosesUntil(() -> poseFits(needed));
    }

    /** As {@link #releasePoses(long)}, until {@code fits} holds. */
    private long releasePosesUntil(java.util.function.BooleanSupplier fits) {
        long released = 0;
        while (!fits.getAsBoolean()) {
            SlotObjects owner = null;
            int at = -1;
            long oldest = Long.MAX_VALUE;
            for (var slot : slots) {
                var objects = slot == null ? null : slot.objects;
                if (objects == null) {
                    continue;
                }
                for (int i = 0; i < objects.poses.length; i++) {
                    var pose = objects.poses[i];
                    if (pose != null
                            && animationFrame - pose.usedFrame() > WorldObjectAnimation.IDLE_FRAMES
                            && pose.usedFrame() < oldest) {
                        oldest = pose.usedFrame();
                        owner = objects;
                        at = i;
                    }
                }
            }
            if (owner == null) {
                break;
            }
            long bytes =
                    WorldObjectAnimation.bytes(owner.animations[at / WorldObjectAnimation.PHASES]);
            owner.poses[at].close();
            owner.poses[at] = null;
            owner.poseBytes -= bytes;
            released += bytes;
            posesReleased++;
        }
        return released;
    }

    /** Poses released for the budget so far (#384). */
    long posesReleased() {
        requireOwner();
        return posesReleased;
    }

    /** Proofs only: releases every pose no frame drew lately; returns the bytes released. */
    long releaseIdlePosesForProof() {
        requireOwner();
        return releasePosesUntil(() -> false);
    }

    private void queueSkin(WorldObjectAnimation.Pose pose) {
        if (pendingSkins.length == pendingCount) {
            pendingSkins = java.util.Arrays.copyOf(pendingSkins, pendingCount * 2);
        }
        pendingSkins[pendingCount++] = pose;
    }

    /**
     * Re-skins this frame's queued poses within {@link WorldObjectAnimation#FRAME_SKIN_VERTICES},
     * stalest first ({@link WorldObjectAnimation#skinOrder}), so over the budget every pose still
     * advances in turn and none freezes. A pose left over is drawn a step late. bgfx applies vertex
     * updates before the frame's draws, so skinning after submission still shows this frame.
     */
    private void skinPoses() {
        if (pendingCount == 0) {
            return;
        }
        int chosen =
                WorldObjectAnimation.skinOrder(
                        pendingSkins, pendingCount, WorldObjectAnimation.FRAME_SKIN_VERTICES);
        for (int i = 0; i < pendingCount; i++) {
            var pose = pendingSkins[i];
            pendingSkins[i] = null;
            if (i >= chosen) {
                frameStalePoses++;
                continue;
            }
            long skinning = com.vibeminer.formats.world.WorldStageTimings.start();
            pose.skin(pose.target(), animationClock);
            com.vibeminer.formats.world.WorldStageTimings.stop("child.frame.skin", skinning);
            frameSkins++;
            frameSkinVertices += pose.vertexCount();
        }
        pendingCount = 0;
    }

    /**
     * Allocates this frame's transient instance data for placements {@code order[first..first +
     * count)} and writes their rows into {@code frame.instances}. bgfx's transient instance data is
     * bounded per frame: placements beyond what is available are not drawn, are marked dropped (so
     * the sorted pass skips them too) and counted. Returns how many instances were written; their
     * data stays valid until the next allocation.
     */
    private int instances(
            ObjectFrame frame,
            SlotObjects objects,
            int[] order,
            int first,
            int count,
            TerrainScene.Origin origin) {
        int available =
                Math.min(
                        org.lwjgl.bgfx.BGFX.bgfx_get_avail_instance_data_buffer(
                                count, WorldObjectInstances.STRIDE),
                        Math.max(0, instanceLimit - frameInstances));
        if (available < count) {
            for (int i = available; i < count; i++) {
                objects.dropped[order[first + i]] = true;
            }
            frameInstancesDropped += count - available;
            count = available;
        }
        if (count == 0) {
            return 0;
        }
        var instances = frame.instances;
        org.lwjgl.bgfx.BGFX.bgfx_alloc_instance_data_buffer(
                instances, count, WorldObjectInstances.STRIDE);
        var data = instances.data().order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) {
            WorldObjectInstances.put(objects.placements, order[first + i], origin, data);
        }
        frameInstances += count;
        return count;
    }

    /**
     * The object uniform buffers of one frame, allocated once from the frame's MemoryStack, plus
     * the instance data descriptor every model's draws reuse, so drawing allocates nothing per
     * placement or per model.
     */
    static final class ObjectFrame {
        /** The current allocation's instance data, valid until the next allocation. */
        final org.lwjgl.bgfx.BGFXInstanceDataBuffer instances;

        /** One placement index, for a one-instance draw. */
        final int[] single = new int[1];

        /**
         * u_wmoMaterial per kind (opaque, cutout, blended, additive), lit/unlit and World or
         * interior light.
         */
        private final java.nio.FloatBuffer[] materials = new java.nio.FloatBuffer[16];

        final long cutoutState;

        private ObjectFrame(MemoryStack stack, boolean coverage) {
            instances = org.lwjgl.bgfx.BGFXInstanceDataBuffer.malloc(stack);
            for (int kind = 0; kind <= WorldStreamObjectsFile.ADDITIVE; kind++) {
                for (int unlit = 0; unlit < 2; unlit++) {
                    for (int interior = 0; interior < 2; interior++) {
                        int rangeClass =
                                kind
                                        | (unlit == 1 ? WorldStreamObjectsFile.UNLIT : 0)
                                        | (interior == 1
                                                ? WorldStreamObjectsFile.INTERIOR_LIGHT
                                                : 0);
                        materials[materialSlot(rangeClass)] =
                                stack.floats(materialUniform(rangeClass, coverage));
                    }
                }
            }
            cutoutState = cutoutState(coverage);
        }

        /** {@code coverage}: the target is multisampled, so cutouts use alpha to coverage. */
        static ObjectFrame on(MemoryStack stack, boolean coverage) {
            return new ObjectFrame(stack, coverage);
        }

        /** The draw state of one range class outside the blended pass. */
        long state(int rangeClass) {
            return WorldStreamObjectsFile.kind(rangeClass) == WorldStreamObjectsFile.CUTOUT
                    ? cutoutState
                    : BgfxWorldWmoPass.DRAW_STATE;
        }

        /** The u_wmoMaterial value of one range class. */
        java.nio.FloatBuffer material(int rangeClass) {
            return materials[materialSlot(rangeClass)];
        }

        /** The {@link #materials} slot of a range class: its kind, unlit and interior flags. */
        static int materialSlot(int rangeClass) {
            int unlit = (rangeClass & WorldStreamObjectsFile.UNLIT) != 0 ? 1 : 0;
            int interior = (rangeClass & WorldStreamObjectsFile.INTERIOR_LIGHT) != 0 ? 1 : 0;
            return WorldStreamObjectsFile.kind(rangeClass) * 4 + unlit * 2 + interior;
        }
    }

    /**
     * Model m's opaque and cutout ranges, in file order, drawn for the {@code count} instances just
     * written to {@code frame.instances}. With {@code groups} (one portal root's walk, #383),
     * interior meshes of groups not in it are skipped.
     */
    private void drawRanges(
            ObjectFrame frame, SlotObjects objects, int m, int count, java.util.BitSet groups) {
        if (count == 0) {
            return;
        }
        drawRangesStep(frame, objects, m, count, STEP_UNSORTED, groups, null);
    }

    /**
     * Placement p's sorted ranges, drawn for its one instance just written to {@code
     * frame.instances}: its blended ranges back to front by the camera distance of each range's
     * bounds centre (#552), then its additive ones (#449): additive light is order-independent, but
     * a blended range drawn after it would cover it. With {@code groups}, as {@link #drawRanges}.
     * The sort reuses one primitive array; nothing is allocated per frame once it has grown.
     */
    private void drawSortedRanges(
            ObjectFrame frame,
            SlotObjects objects,
            int p,
            int count,
            java.util.BitSet groups,
            TerrainScene.Origin origin) {
        if (count == 0) {
            return;
        }
        int m = objects.placementModels[p];
        if (rangeKeys.length < objects.maxBlendedRanges) {
            rangeKeys = new long[objects.maxBlendedRanges];
        }
        int sorted =
                blendedRangeKeys(
                        objects.models,
                        objects.meshes,
                        objects.ranges,
                        objects.rangeCentres,
                        objects.placements,
                        m,
                        p,
                        origin.x(),
                        origin.y(),
                        origin.z(),
                        rangeKeys);
        var portals = groups == null ? null : objects.portals[m];
        for (int i = sorted - 1; i >= 0; i--) {
            int r = keyRange(rangeKeys[i]);
            int mesh = objects.rangeMeshes[r];
            if (portals != null && portalSkipped(objects, portals, groups, mesh)) {
                continue;
            }
            submitRange(
                    frame,
                    objects,
                    objects.modelVertices[objects.meshModel[mesh]],
                    objects.meshVertexBase[mesh],
                    mesh,
                    r,
                    count,
                    true);
        }
        drawRangesStep(frame, objects, m, count, STEP_ADDITIVE, groups, null);
    }

    /**
     * Model m's blended ranges for placement p as sort keys in {@code keys[0..n)}, sorted
     * ascending; returns n. Read backwards they are farthest first by the squared distance from
     * {@code (x, y, z)} to each range's bounds centre under the placement's transform (the one
     * {@link #objectMatrices} draws). Equal distances keep file order. {@code keys} holds at least
     * the model's blended ranges.
     */
    static int blendedRangeKeys(
            int[] models,
            int[] meshes,
            int[] ranges,
            float[] centres,
            double[] placements,
            int m,
            int p,
            double x,
            double y,
            double z,
            long[] keys) {
        int at = p * WorldStreamObjectsFile.PLACEMENT_DOUBLES;
        double s = placements[at + 3];
        int count = 0;
        for (int mesh = models[m * 2]; mesh < models[m * 2] + models[m * 2 + 1]; mesh++) {
            for (int r = meshes[mesh * 6 + 4];
                    r < meshes[mesh * 6 + 4] + meshes[mesh * 6 + 5];
                    r++) {
                if (step(ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3]) != STEP_BLENDED) {
                    continue;
                }
                double cx = centres[r * 3], cy = centres[r * 3 + 1], cz = centres[r * 3 + 2];
                double squared = 0;
                for (int row = 0; row < 3; row++) {
                    int rotation = at + 4 + row * 3;
                    double world =
                            placements[at + row]
                                    + s
                                            * (placements[rotation] * cx
                                                    + placements[rotation + 1] * cy
                                                    + placements[rotation + 2] * cz);
                    double d = world - (row == 0 ? x : row == 1 ? y : z);
                    squared += d * d;
                }
                keys[count++] = rangeKey(squared, r);
            }
        }
        sortKeys(keys, count);
        return count;
    }

    /**
     * Sorts {@code keys[0, count)} ascending in place without allocating. {@code Arrays.sort} on a
     * {@code long[]} allocates a small pivot array per partition that only a fully optimized JIT
     * removes, and this runs per placement per frame and once per frame over every blended
     * placement. Insertion sort for the usual handful of keys, heapsort past that.
     */
    static void sortKeys(long[] keys, int count) {
        if (count <= 32) {
            for (int i = 1; i < count; i++) {
                long key = keys[i];
                int j = i - 1;
                while (j >= 0 && keys[j] > key) {
                    keys[j + 1] = keys[j];
                    j--;
                }
                keys[j + 1] = key;
            }
            return;
        }
        for (int i = count / 2 - 1; i >= 0; i--) {
            siftDown(keys, i, count);
        }
        for (int end = count - 1; end > 0; end--) {
            long top = keys[0];
            keys[0] = keys[end];
            keys[end] = top;
            siftDown(keys, 0, end);
        }
    }

    private static void siftDown(long[] keys, int i, int count) {
        long key = keys[i];
        for (int child = 2 * i + 1; child < count; child = 2 * i + 1) {
            if (child + 1 < count && keys[child + 1] > keys[child]) {
                child++;
            }
            if (keys[child] <= key) {
                break;
            }
            keys[i] = keys[child];
            i = child;
        }
        keys[i] = key;
    }

    /**
     * A blended range's sort key: the squared distance as float bits above the inverted range
     * index. Non-negative floats order like their bits, so an ascending sort read backwards is
     * farthest first, and equal distances read in file order.
     */
    static long rangeKey(double squaredDistance, int range) {
        if (!(squaredDistance >= 0) || range < 0) {
            throw new IllegalArgumentException("Range key");
        }
        // +0.0f for -0.0, so the sign bit never inverts the order; past float range, the largest.
        float distance = (float) Math.min(squaredDistance, Float.MAX_VALUE) + 0.0f;
        return (long) Float.floatToRawIntBits(distance) << 32
                | (Integer.MAX_VALUE - range) & 0xffffffffL;
    }

    /** The range of a {@link #rangeKey}. */
    static int keyRange(long key) {
        return Integer.MAX_VALUE - (int) key;
    }

    /** Draw steps of one placement: opaque and cutout; then, sorted, blended and additive. */
    static final int STEP_UNSORTED = 0, STEP_BLENDED = 1, STEP_ADDITIVE = 2;

    /** The draw step of a range class. */
    static int step(int rangeClass) {
        int kind = WorldStreamObjectsFile.kind(rangeClass);
        return kind == WorldStreamObjectsFile.ADDITIVE
                ? STEP_ADDITIVE
                : kind == WorldStreamObjectsFile.BLENDED ? STEP_BLENDED : STEP_UNSORTED;
    }

    /**
     * Whether placement {@code p}, tied to an interior group of its parent's portal set, is culled
     * this frame: its parent was in view, so it walked its portals this frame, and that walk did
     * not reach the group. A parent out of view did not walk, so its tied placements keep their own
     * frustum test.
     */
    static boolean portalCulled(
            int[] parents, int[] groups, boolean[] visible, java.util.BitSet[] walked, int p) {
        int parent = parents[p];
        return parent >= 0
                && visible[parent]
                && walked[parent] != null
                && !walked[parent].get(groups[p]);
    }

    /** Whether a portal root's walk ({@code groups}) leaves out this interior mesh. */
    private static boolean portalSkipped(
            SlotObjects objects,
            com.vibeminer.rendering.world.WmoPortalTraversal portals,
            java.util.BitSet groups,
            int mesh) {
        int group = objects.meshGroups[mesh];
        return group >= 0 && portals.interior(group) && !groups.get(group);
    }

    /** Model m's ranges of one step in file order; any step but unsorted is the sorted pass. */
    private void drawRangesStep(
            ObjectFrame frame,
            SlotObjects objects,
            int m,
            int count,
            int step,
            java.util.BitSet groups,
            WorldObjectAnimation.Pose pose) {
        boolean sortedPass = step != STEP_UNSORTED;
        var portals = groups == null ? null : objects.portals[m];
        for (int mesh = objects.models[m * 2];
                mesh < objects.models[m * 2] + objects.models[m * 2 + 1];
                mesh++) {
            if (portals != null && portalSkipped(objects, portals, groups, mesh)) {
                if (!sortedPass) {
                    framePortalCulled++;
                }
                continue;
            }
            int at = mesh * 6;
            for (int r = objects.meshes[at + 4];
                    r < objects.meshes[at + 4] + objects.meshes[at + 5];
                    r++) {
                if (step(objects.ranges[r * WorldStreamObjectsFile.RANGE_INTS + 3]) == step) {
                    if (pose == null) {
                        submitRange(
                                frame,
                                objects,
                                objects.modelVertices[objects.meshModel[mesh]],
                                objects.meshVertexBase[mesh],
                                mesh,
                                r,
                                count,
                                sortedPass);
                    } else {
                        submitRange(
                                frame,
                                objects,
                                pose.vertices,
                                objects.animations[m].meshOffsets[mesh - objects.models[m * 2]],
                                mesh,
                                r,
                                count,
                                sortedPass);
                    }
                }
            }
        }
    }

    /**
     * One draw of range r of a mesh for the {@code count} instances in {@code frame.instances}.
     * bgfx uniforms persist across submits: the camera is set once per frame and the material only
     * when its class changes; transforms are per instance.
     */
    private void submitRange(
            ObjectFrame frame,
            SlotObjects objects,
            short vertexBuffer,
            int firstVertex,
            int mesh,
            int r,
            int count,
            boolean sortedPass) {
        int stride = WorldStreamObjectsFile.RANGE_INTS;
        int at = mesh * 6;
        int kind = objects.ranges[r * stride + 3];
        if (kind != frameMaterial) {
            bgfx_set_uniform(wmoMaterial, frame.material(kind), 1);
            frameMaterial = kind;
        }
        bgfx_set_dynamic_vertex_buffer(0, vertexBuffer, firstVertex, objects.meshes[at + 1]);
        bgfx_set_dynamic_index_buffer(
                objects.modelIndices[objects.meshModel[mesh]],
                objects.meshIndexBase[mesh] + objects.ranges[r * stride],
                objects.ranges[r * stride + 1]);
        org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_buffer(frame.instances, 0, count);
        bgfx_set_texture(
                0,
                wmoSampler,
                objects.textures[objects.ranges[r * stride + 2]],
                objectSamplerFlags(kind));
        bgfx_set_state(
                sortedPass
                        ? WorldStreamObjectsFile.kind(kind) == WorldStreamObjectsFile.ADDITIVE
                                ? ADDITIVE_STATE
                                : BLENDED_STATE
                        : frame.state(kind),
                0);
        bgfx_submit(0, wmoProgram, 0, BGFX_DISCARD_ALL);
        frameObjectSubmits++;
        if (sortedPass) {
            frameSortedSubmits++;
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
                decodedImages,
                reservedObjectTextures(),
                objectTextureLimit,
                textureRefusals,
                objectTextures.references(),
                objectTextures.sharedReferences());
    }

    /**
     * The compressed object texture formats this renderer uploads as blocks, one bit per {@link
     * ObjectTextureFormats} code; reported to the parent in SESSION (issue #374).
     */
    int objectFormatMask() {
        int mask = 0;
        for (int f = ObjectTextureFormats.BC1; f <= ObjectTextureFormats.BC3; f++) {
            if (objectFormats[f]) {
                mask |= 1 << f;
            }
        }
        return mask;
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

    /** Whether a wait for released handles that began at {@code started} has run out. */
    static boolean textureWaitOver(long started, long now, long bound) {
        return now - started >= bound;
    }

    /**
     * Test seam: {@code spare} more handles kept out of the live room, so a set that fits the
     * accounting waits for handles, for at most {@code waitNanos}.
     */
    void liveTextureSpareForProof(int spare, long waitNanos) {
        requireOwner();
        if (spare < 0 || waitNanos < 0) {
            throw new IllegalArgumentException("Proof texture spare");
        }
        extraLiveSpare = spare;
        textureWaitNanos = waitNanos;
    }

    /** Proof observation: steps object builds have spent waiting for released handles. */
    long textureWaitStepsForProof() {
        requireOwner();
        return textureWaitSteps;
    }

    /** Test seam: a native proof fills past a small budget without hundreds of MiB of objects. */
    void objectBudgetForProof(long bytes) {
        requireOwner();
        if (bytes < 0 || bytes > session.objectBudgetBytes()) {
            throw new IllegalArgumentException("Proof budget above the session budget");
        }
        objectBudget = bytes;
    }

    /**
     * The texture handles object sets may hold together: bgfx's limit less the handles live when
     * the renderer opened (the owner's context and this renderer's fixed textures), the ones it may
     * still create outside object sets, and the owner's reserve; never negative.
     */
    static int objectTextureLimit(int maxTextures, int liveAtOpen, int slots) {
        if (maxTextures < 0 || liveAtOpen < 0 || slots < 0) {
            throw new IllegalArgumentException("Texture handle counts");
        }
        long room =
                (long) maxTextures
                        - liveAtOpen
                        - (long) slots * LAZY_SLOT_TEXTURES
                        - LAZY_SHARED_TEXTURES
                        - OWNER_TEXTURE_RESERVE;
        return (int) Math.max(0, room);
    }

    /**
     * Whether a set of {@code need} images fits beside {@code held} handles under {@code limit}.
     */
    static boolean objectTexturesFit(int limit, int held, int need) {
        return need >= 0 && held >= 0 && need <= (long) limit - held;
    }

    /** The stderr line of a set refused for texture handles; never the protocol. */
    static String textureRefusal(int need, int held, int limit, int bgfxLimit, long refusals) {
        return "World objects set refused for texture handles: needs "
                + need
                + ", "
                + held
                + " of "
                + limit
                + " object handles held (bgfx limit "
                + bgfxLimit
                + "); offered again when a set is released ("
                + refusals
                + (refusals == 1 ? " refusal" : " refusals")
                + " this session)";
    }

    private void refuseForTextures(ObjectsBuild build, int need, int held) {
        build.budget = true;
        build.fail();
        textureRefusals++;
        System.err.println(
                textureRefusal(need, held, objectTextureLimit, maxTextures, textureRefusals));
        System.err.flush();
    }

    /**
     * Handles bgfx can still create for object sets right now: its limit less every live handle
     * (released ones count until bgfx returns them at a frame), the images building sets have yet
     * to create, the textures this session may still create outside object sets and {@link
     * #LIVE_TEXTURE_SPARE}.
     */
    private int liveTextureRoom() {
        int live = Short.toUnsignedInt(org.lwjgl.bgfx.BGFX.bgfx_get_stats().numTextures());
        int pending = objectTextures.uncreated();
        int lazy = splatArray == INVALID ? LAZY_SHARED_TEXTURES : 0;
        for (var slot : slots) {
            if (slot != null && slot.splatAlpha == INVALID) {
                lazy += LAZY_SLOT_TEXTURES;
            }
        }
        return Math.max(
                0, maxTextures - live - pending - lazy - LIVE_TEXTURE_SPARE - extraLiveSpare);
    }

    /** Texture handles resident and building object sets hold; owner thread only. */
    int objectTexturesHeld() {
        requireOwner();
        return reservedObjectTextures();
    }

    /** Texture handles object sets hold: each shared texture once, created or reserved. */
    private int reservedObjectTextures() {
        return objectTextures.entries() + (body == null ? 0 : body.handles());
    }

    /** Image references resident and building object sets hold; owner thread only. */
    int objectTextureReferences() {
        requireOwner();
        return objectTextures.references();
    }

    /** The set's models against the session's geometry library. */
    private WorldStreamObjectGeometry.Plan planGeometry(WorldStreamPackedObjects source) {
        var bytes = new long[source.modelKeys.length];
        for (int m = 0; m < bytes.length; m++) {
            bytes[m] = source.modelBytes(m);
        }
        return objectGeometry.plan(source.modelKeys, bytes);
    }

    /** GPU bytes a set adds: its water and the geometry and textures it creates. */
    private static long setNeed(
            WorldStreamPackedObjects source,
            WorldStreamObjectTextures.Plan textures,
            WorldStreamObjectGeometry.Plan geometry) {
        return source.water.bytes() + geometry.newBytes() + textures.newBytes();
    }

    /** How a set's images would be held now: shared, repeated in the set, or created. */
    private WorldStreamObjectTextures.Plan planTextures(WorldStreamPackedObjects source) {
        int count = source.imageCount();
        var keys = new WorldStreamObjectTextures.Key[count];
        var sizes = new long[count];
        for (int i = 0; i < count; i++) {
            keys[i] = source.key(i);
            sizes[i] = source.imageBytes(i);
        }
        return objectTextures.plan(keys, sizes);
    }

    private long reservedObjectBytes() {
        var upload = uploading.get();
        long total =
                (upload == null || upload.objects == null || upload.objects.building == null
                                ? 0
                                : upload.objects.building.reserved)
                        + objectTextures.reservedBytes()
                        + objectGeometry.reservedBytes()
                        + (body == null ? 0 : body.bytes());
        if (late != null && late.build.building != null) {
            total += late.build.building.reserved;
        }
        for (var slot : slots) {
            if (slot != null && slot.objects != null) {
                total += slot.objects.reserved + slot.objects.poseBytes;
            }
        }
        return total;
    }

    /** The session's shared model geometry (measurement and native proofs). Owner thread only. */
    WorldStreamObjectGeometry objectGeometry() {
        requireOwner();
        return objectGeometry;
    }

    /** GPU bytes all object sets, textures, the body and poses reserve now (measurement). */
    long objectsReserved() {
        requireOwner();
        return reservedObjectBytes();
    }

    /** GPU bytes all resident sets' animation poses hold (#384). */
    private long poseBytes() {
        long total = 0;
        for (var slot : slots) {
            if (slot != null && slot.objects != null) {
                total += slot.objects.poseBytes;
            }
        }
        return total;
    }

    private long objectBytes(Upload upload) {
        long total =
                (upload == null || upload.objects == null ? 0 : upload.objects.builtBytes())
                        + objectTextures.issuedBytes()
                        + objectGeometry.reservedBytes();
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
        if (horizon != null) {
            horizon.close();
        }
        destroyHorizonBuffers();
        if (body != null) {
            body.close();
            body = null;
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
        }
        // Every set released its geometry: what remains are zombies, destroyed with the session.
        objectGeometry.close();
        for (var slot : slots) {
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
                    worldLight,
                    worldWater,
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

    /**
     * Terrain and water mesh vertices (24 bytes): float position, then normalized RGBA8 fallback
     * colour, MCCV colour and normal ({@link WorldStreamPackedTile#VERTEX_BYTES}).
     */
    private static BGFXVertexLayout meshLayout() {
        var layout = BGFXVertexLayout.calloc();
        bgfx_vertex_layout_begin(layout, bgfx_get_caps().rendererType());
        bgfx_vertex_layout_add(
                layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        for (int attribute :
                new int[] {
                    org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR0,
                    org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR1,
                    BGFX_ATTRIB_NORMAL
                }) {
            bgfx_vertex_layout_add(
                    layout, attribute, 4, org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8, true, false);
        }
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
