package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpFormatException;
import com.vibeminer.formats.models.M2Combiners;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2SkinReader;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.models.ModelFormatException;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WmoGroupSpans;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.world.ExteriorObjectLimits;
import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.WmoMaterialClass;
import com.vibeminer.rendering.world.WmoPortalSet;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * BASIC_EXTERIOR_V1 source factory (issue #172, docs/world-outdoor-objects-proposal.md).
 *
 * <p>One serialized source operation per recorded ADT tile turns that tile's obj0 MODF placements,
 * their WMO doodad sets and the tile's MDDF doodads into an immutable, neutral heap object set:
 * shared per-root exterior meshes in local WMO space, shared per-model static M2 doodad meshes,
 * texture1 images and per-placement ADT transforms. Opaque, alpha-key (cutout) and alpha-blended
 * materials are drawn; every other material, and everything else outside the documented profile, is
 * omitted and counted by reason; nothing is guessed. Selection and omission depend only on obj0
 * source order, never on a camera or on neighboring tiles. There is no claim of client lighting,
 * animation or material parity.
 */
public final class WorldExteriorObjects {
    public static final String RECIPE = "BASIC_EXTERIOR_V1";
    // Pool bounds come from ExteriorObjectLimits, which the objects file sums into its tables.
    static final int MAX_PLACEMENTS = ExteriorObjectLimits.BUILDING_PLACEMENTS;
    static final int MAX_ROOTS = ExteriorObjectLimits.BUILDING_ROOTS, MAX_GROUPS = 256;

    /**
     * MOGI-interior groups a border-crossing root may have and still be sized with them (#383): a
     * root with more is sized by its exteriors alone. A property of the root only, so every tile
     * listing a placement makes the same decision. A tile reads at most this many groups per root
     * to size it, at most {@link #MAX_ROOTS} roots, a bound the read profile carries.
     */
    static final int MAX_SIZING_READS = MAX_GROUPS;

    static final int MAX_IMAGES = ExteriorObjectLimits.BUILDING_IMAGES;
    static final int MAX_VERTICES = ExteriorObjectLimits.BUILDING_VERTICES;
    static final int MAX_INDICES = ExteriorObjectLimits.BUILDING_INDICES;
    static final int MAX_RANGES = ExteriorObjectLimits.BUILDING_RANGES;

    /** The interior allowance of the tile's own buildings, on top of the building pool. */
    static final int MAX_TILE_INTERIOR_VERTICES = ExteriorObjectLimits.TILE_INTERIOR_VERTICES;

    static final int MAX_TILE_INTERIOR_INDICES = ExteriorObjectLimits.TILE_INTERIOR_INDICES;
    static final int MAX_TILE_INTERIOR_RANGES = ExteriorObjectLimits.TILE_INTERIOR_RANGES;
    static final int MAX_MESHES = ExteriorObjectLimits.BUILDING_MESHES, MAX_PIXELS = 1 << 20;

    /**
     * The allowance of very large buildings drawn by group (see {@link Owner}) and of roots drawn
     * whole from it ({@link Owner#whole}); one pool with the interior allowance below.
     */
    static final int MAX_LARGE_VERTICES = ExteriorObjectLimits.LARGE_VERTICES;

    static final int MAX_LARGE_INDICES = ExteriorObjectLimits.LARGE_INDICES;
    static final int MAX_LARGE_RANGES = ExteriorObjectLimits.LARGE_RANGES;
    static final int MAX_LARGE_IMAGES = ExteriorObjectLimits.LARGE_IMAGES;

    /**
     * The interior allowance, pooled with the large allowance: exteriors of roots drawn by group
     * (and roots drawn whole from it) are admitted first against both together, and their interiors
     * take what every exterior left.
     */
    static final int MAX_LARGE_INTERIOR_VERTICES = ExteriorObjectLimits.LARGE_INTERIOR_VERTICES;

    static final int MAX_LARGE_INTERIOR_INDICES = ExteriorObjectLimits.LARGE_INTERIOR_INDICES;
    static final int MAX_LARGE_INTERIOR_RANGES = ExteriorObjectLimits.LARGE_INTERIOR_RANGES;
    static final int MAX_LARGE_INTERIOR_IMAGES = ExteriorObjectLimits.LARGE_INTERIOR_IMAGES;

    /**
     * BASIC doodad caps, separate from the building caps so buildings are never displaced. The
     * measured reasons for each bound are on {@link ExteriorObjectLimits} (#308): placements were
     * the only cap that bound on Elwynn; a placement is 112 bytes in the objects file and draws its
     * model's ranges, which {@link #MAX_DRAWS} bounds.
     */
    static final int MAX_DOODADS = ExteriorObjectLimits.DOODAD_PLACEMENTS;

    static final int MAX_MODELS = ExteriorObjectLimits.DOODAD_MODELS;
    static final int MAX_MODEL_IMAGES = ExteriorObjectLimits.DOODAD_IMAGES;

    /**
     * Doodad models and their image slots read beyond {@link #MAX_MODELS} and {@link
     * #MAX_MODEL_IMAGES}, only for set doodads tied to one drawn interior group ({@link
     * Placement#portalGroup}), which the renderer culls with that group.
     */
    static final int MAX_INTERIOR_MODELS = ExteriorObjectLimits.DOODAD_INTERIOR_MODELS;

    static final int MAX_INTERIOR_MODEL_IMAGES = ExteriorObjectLimits.DOODAD_INTERIOR_IMAGES;
    static final int MAX_DOODAD_VERTICES = ExteriorObjectLimits.DOODAD_VERTICES;
    static final int MAX_DOODAD_INDICES = ExteriorObjectLimits.DOODAD_INDICES;
    static final int MAX_DOODAD_RANGES = ExteriorObjectLimits.DOODAD_RANGES;
    static final int MAX_DOODAD_PIXELS = 1 << 19;

    /**
     * Cutout and blended building surfaces have their own budget on top of the opaque building
     * caps, so admitting them never caps, displaces or fails a surface the opaque profile draws.
     */
    static final int MAX_MATERIAL_IMAGES = ExteriorObjectLimits.MATERIAL_IMAGES;

    static final int MAX_MATERIAL_PIXELS = 1 << 18;

    static final int MAX_MATERIAL_VERTICES = ExteriorObjectLimits.MATERIAL_VERTICES;
    static final int MAX_MATERIAL_INDICES = ExteriorObjectLimits.MATERIAL_INDICES;
    static final int MAX_MATERIAL_RANGES = ExteriorObjectLimits.MATERIAL_RANGES;

    /**
     * Per-tile object texture budgets (#267). STANDARD keeps the original pixel budgets; HIGH
     * doubles every pool's pixels for GPUs that can afford it. Image counts are the same in both.
     * The objects file and renderer admit the HIGH totals.
     */
    public enum ImageDetail {
        // Budgets count whole mip chains (#265): each is the chain of one texture of the old
        // top-level size (1 Mi, 256 Ki, 512 Ki texels; doubled on HIGH), so the same images keep
        // the same top levels while the chains below them are paid for.
        STANDARD(chain(1024, 1024), chain(512, 512), chain(1024, 512)),
        HIGH(chain(2048, 1024), chain(1024, 512), chain(1024, 1024));
        private final int buildingPixels, materialPixels, doodadPixels;

        ImageDetail(int buildingPixels, int materialPixels, int doodadPixels) {
            this.buildingPixels = buildingPixels;
            this.materialPixels = materialPixels;
            this.doodadPixels = doodadPixels;
        }

        public int buildingPixels() {
            return buildingPixels;
        }

        public int materialPixels() {
            return materialPixels;
        }

        public int doodadPixels() {
            return doodadPixels;
        }

        private static int chain(int width, int height) {
            return ObjectTextureMips.chainPixels(width, height);
        }

        /** All pools together: the most RGBA8 pixels one tile's object set can carry. */
        public int totalPixels() {
            return buildingPixels + materialPixels + doodadPixels;
        }
    }

    /**
     * Image source bytes a tile build keeps between an image's first read, its budget relevel and
     * its mip chain (#265); real tiles read about 7 MiB of BLP data.
     */
    static final long RETAINED_SOURCE_BYTES = 32L << 20;

    /** Per-tile draw budget: the sum over all placements of their model's ranges (#308). */
    static final int MAX_DRAWS = ExteriorObjectLimits.DRAWS;

    /** Largest admitted placement scale (building scale times doodad scale). */
    static final double MAX_SCALE = 64;

    /** Material classes carried by every draw range. */
    public static final int OPAQUE = 0, CUTOUT = 1, BLENDED = 2;

    /**
     * Additive blend (MOMT blend mode 3, issue #367): sorted with the blended surfaces and added to
     * what is behind them. Its images are prepared as blended ones.
     */
    public static final int ADDITIVE = 3;

    /**
     * Flag on a range class (MOMT F_UNLIT, flags bit 0x1): the surface is drawn at full intensity,
     * without the light. The World objects file carries it in the same int.
     */
    public static final int UNLIT = 0x100;

    /**
     * Flag on a range class (MOMT F_UNCULLED, flags bit 0x4, documented as "two-sided", issue
     * #375): the surface is seen from both sides. The World pass culls no faces yet, so every
     * surface is still drawn two-sided; the flag is carried for single-sided culling.
     */
    public static final int TWO_SIDED = 0x200;

    /**
     * Flag on a range class: the range belongs to an interior group (MOGP 0x2000) whose vertex
     * colours are carried, so it is lit by them instead of the World sun (#383, #375): in the
     * client an interior group is lit by its baked MOCV, not the outdoor light. The colours are the
     * raw MOCV; the root's MOHD ambient is not added (see copyGroup). An interior group without
     * vertex colours keeps the World light rather than drawing black.
     */
    public static final int INTERIOR_LIGHT =
            com.vibeminer.rendering.world.WorldObjectPicking.INTERIOR_LIGHT;

    /** The kind of a range class, without its flags. */
    public static int kind(int rangeClass) {
        return rangeClass & 0xff;
    }

    /** The image preparation class of a range class: additive images are prepared as blended. */
    static int imageClass(int rangeClass) {
        int kind = kind(rangeClass);
        return kind == ADDITIVE ? BLENDED : kind;
    }

    /**
     * The format an image drawn by {@code rangeClass} is carried in when its file stores {@code
     * fileFormat} blocks (an {@link ObjectTextureFormats} code, or -1 when it has none), or -1 for
     * RGBA8 (issue #374). Only the kind counts, never the UNLIT flag: opaque ranges, which ignore
     * alpha, take BC1, BC2 or BC3; blended and additive ones BC2 or BC3 only, because BC1 alpha is
     * not sampled on every backend; cutout ranges keep RGBA8, whose mips preserve alpha-test
     * coverage (#265). The World objects file refuses BC1 for any range but an opaque one.
     */
    public static int blockFormatFor(int fileFormat, int rangeClass) {
        int image = imageClass(rangeClass);
        if (fileFormat < 0 || image == CUTOUT) {
            return -1;
        }
        return image == BLENDED && fileFormat == ObjectTextureFormats.BC1 ? -1 : fileFormat;
    }

    /** Position xyz, unit normal xyz, texture1 uv: 32 bytes per vertex. */
    static final int VERTEX_FLOATS = 8;

    /**
     * Packs one MOCV vertex colour, stored B, G, R, A, as {@code r | g << 8 | b << 16}; the alpha
     * byte is not carried.
     */
    static int packColour(int b, int g, int r) {
        return r | g << 8 | b << 16;
    }

    /**
     * Building image sides, largest first. Images are read when their surfaces are admitted, but
     * the pixel budget is shared only once every building has been read: {@link
     * WorldImageBudget#allocate} gives every image the smallest side first and then raises them
     * round by round, larger buildings first, so a tight budget lowers resolution instead of
     * dropping surfaces.
     */
    static final int[] IMAGE_SIDES = {512, 256, 128, 64, 32};

    /**
     * Doodad image sides. The smallest is 16 so that every doodad image slot at the caps ({@link
     * #MAX_MODEL_IMAGES} and {@link #MAX_INTERIOR_MODEL_IMAGES}) keeps a level within the STANDARD
     * doodad budget: 920 chains of 32 x 32 would need about 1.26 Mi texels of its 699 Ki, 920 of 16
     * x 16 about 314 Ki. Images reach 16 only when the budget is that tight.
     */
    static final int[] MODEL_IMAGE_SIDES = {256, 128, 64, 32, 16};

    /**
     * The side the interior-only images' reserve is held at, in every pool: 32, as before the
     * doodad sides reached 16, so exterior doodads never take the interiors' share below it.
     */
    static final int INTERIOR_RESERVE_SIDE = 32;

    /**
     * Largest side of a chain uploaded as RGBA8: 256 for buildings and their surfaces, 128 for
     * doodads. The larger sides above are only for chains the session samples as blocks (#374),
     * which cost a quarter (BC2, BC3) or an eighth (BC1) of the GPU bytes.
     */
    static final int RGBA_SIDE = 256, MODEL_RGBA_SIDE = 128;

    /** Images are first decoded at this side; the budget later picks the uploaded level. */
    static final int REGISTRATION_SIDE = 128;

    /**
     * MDDF flags: 0x40 direct FileDataID (required). 0x2, 0x20, 0x200 and 0x1000 select neither the
     * reference nor the transform in any reviewed source (wowdev ADT/v18 rev 37119, TrinityCore
     * ec72c19 adtfile.cpp:149, wow.export 4474fa4 M2Renderer.js:984-1008), so they are admitted
     * without being interpreted; the meaning of 0x200 itself is not pinned.
     */
    private static final int DOODAD_DIRECT_ID = 0x40, DOODAD_ADMITTED = 0x2 | 0x20 | 0x200 | 0x1000;

    /**
     * MODD flags (the high byte over the 24-bit reference) admitted without being interpreted: 0x1
     * accepts projected textures, 0x2 selects interior rather than exterior lighting, and 0x4, 0x8
     * and 0x10 are undocumented (0x10 lies in the bits wowdev records as unused as of 7.0.1.20994).
     * No reviewed source lets any of them change the model reference or the transform: wowdev WMO
     * (MODD, SMODoodadDef) keeps them apart from nameIndex, position, orientation and scale, and
     * wow.export 4474fa4 reads them in WMOLoader.js but never uses them; WMORendererGL.js:380-418
     * resolves the model from the reference alone and places it from position, rotation and scale.
     * Zephras Isle records 0x10, 0x12, 0x16 and 0x1e on 1,337 set doodads. 0x20 to 0x80 are not
     * seen and stay refused.
     */
    private static final int SET_DOODAD_ADMITTED = 0x1 | 0x2 | 0x4 | 0x8 | 0x10;

    // MODD 0x2 selects interior rather than exterior lighting; vimi lights every set doodad as
    // exterior (BASIC profile, fixed light), so the flag is admitted but not honoured.

    /**
     * Whether texture1 of a batch is sampled with UV0: the batch's shader ID and texture count name
     * a vertex shader ({@link M2Combiners#name}, the reviewed transcription of wow.export
     * ShaderMapper {@code getVertexShader} and {@code SHADER_ARRAY}) that is {@code Diffuse_T1*},
     * and the shader ID sets no bit outside that naming, as {@link M2Combiners#admit} requires. The
     * M2 texture-coordinate lookup table is unused since Cataclysm and is not read. Sources: the
     * reference tree's {@code .local/references/m2-static/export-shadermapper.js:9-46,52-90},
     * {@code export-renderer.js:606} and {@code formatlib-m2.cs:282}.
     */
    static boolean firstTextureUsesUv0(int shaderId, int textureCount) {
        // Shared with World creatures (DisplayModelService.worldBatch, #361).
        return M2Combiners.firstTextureUsesUv0(textureCount, shaderId);
    }

    /** M2 bone flags that billboard (spherical or cylindrical); no view policy here. */
    private static final long BILLBOARD = 0x78;

    /**
     * Bones that billboard or inherit a billboard: a bone whose own flags carry {@link #BILLBOARD}
     * and every descendant of one, since a child's transform composes its parent's camera-facing
     * rotation. Empty when the model has no billboard bone; a parent cycle stops the walk.
     *
     * <p>Together with {@link #billboardWeighted} (any non-zero weight) this deliberately leaves
     * out more rather than less: a triangle only partly bound to a camera-facing bone would be
     * drawn static at a wrong place, while leaving it out only loses a part the profile cannot draw
     * correctly anyway.
     */
    static BitSet billboardBones(List<M2Types.Bone> bones) {
        var result = new BitSet(bones.size());
        boolean any = false;
        for (var bone : bones) {
            any |= (bone.flags() & BILLBOARD) != 0;
        }
        if (!any) {
            return result;
        }
        for (int index = 0; index < bones.size(); index++) {
            int steps = 0;
            for (int at = index;
                    at >= 0 && at < bones.size() && steps++ <= bones.size();
                    at = bones.get(at).parent()) {
                if ((bones.get(at).flags() & BILLBOARD) != 0) {
                    result.set(index);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Whether a vertex has a non-zero weight on a bone in {@code billboard}. Bone indices outside
     * the model's bones are not billboards (they were never checked before this rule either).
     */
    static boolean billboardWeighted(M2Types.Vertex vertex, BitSet billboard) {
        for (int k = 0; k < 4; k++) {
            int weight = (int) (vertex.boneWeights() >>> (k * 8) & 255);
            int bone = (int) (vertex.boneIndices() >>> (k * 8) & 255);
            if (weight != 0 && billboard.get(bone)) {
                return true;
            }
        }
        return false;
    }

    private static final M2Types.Limits MODEL_LIMITS =
            new M2Types.Limits(
                    2L << 20, 32L << 20, 65_536, 65_536, 196_608, 256, 256, 512, 32, 32, 128, 4096,
                    65_536);

    /** MODF flags: 0x2 LOD-capable, 0x4 scale-enable, 0x8 direct FileDataID (required). */
    private static final int DIRECT_ID = 0x8, SCALED = 0x4, LOD_CAPABLE = 0x2;

    /**
     * MODF 0x80 (modf_use_sets_from_mwds, Shadowlands+, wowdev ADT/v18): doodadSet names an MWDR
     * entry whose inclusive MWDS range lists the WMO MODS sets to load. It changes neither the
     * model reference nor the transform.
     */
    private static final int SETS_FROM_MWDS = 0x80;

    /** MOGP exterior and interior flags; interior wins when both are present. */
    private static final long EXTERIOR = 0x8, INTERIOR = 0x2000;

    /** MOGP flag 0x4: the group has vertex colours (its first MOCV). */
    private static final long VERTEX_COLOURS = 0x4;

    private static final WmoFormats.Limits ROOT_LIMITS =
            new WmoFormats.Limits(
                    1 << 20,
                    1 << 20,
                    WmoFormats.MAX_GROUPS,
                    256,
                    4096,
                    65536,
                    131072,
                    256,
                    4,
                    4L << 20);
    private static final Blp2Types.Limits IMAGE_LIMITS =
            new Blp2Types.Limits(1 << 20, 8192, 65536, 256 << 10, 256 << 10);

    private WorldExteriorObjects() {}

    /**
     * Which WMO interior groups a tile draws. {@link #INSTANCE} (dungeon and raid maps, #280) reads
     * each root's interior groups with its exterior ones, so a dungeon's own building comes first
     * by size. {@link #OPEN_WORLD} (every other map, #383) reads a root's MOGI-interior groups only
     * after every building's exterior groups, from the group reads they leave, so an interior never
     * caps an exterior root.
     */
    public enum Interiors {
        NONE,
        INSTANCE,
        OPEN_WORLD;

        /** The historical flag: true for instance interiors, false for none. */
        public static Interiors of(boolean interiors) {
            return interiors ? INSTANCE : NONE;
        }
    }

    /**
     * Whether LOD0 group {@code group} is left unread: without interiors, when the root's retained
     * MOGI flags mark it interior (interior wins over exterior, as for MOGP). A group read with its
     * root's exterior groups still follows its own MOGP flags, whatever its MOGI flags said.
     */
    static boolean skipInterior(WmoFormats.Root root, int group, boolean interiors) {
        return !interiors && mogiInterior(root, group);
    }

    /**
     * Whether LOD0 group {@code group} waits for the tile's interior read pass (#383): on
     * open-world maps ({@code deferred}), when the root's retained MOGI flags mark it interior.
     * Every building's exterior groups are read first, so interior reads only spend what of the
     * tile's group reads is left. A deferred group is admitted as an interior, from what every
     * exterior left, whatever its own MOGP flags say.
     */
    static boolean deferInterior(WmoFormats.Root root, int group, boolean deferred) {
        return deferred && mogiInterior(root, group);
    }

    private static boolean mogiInterior(WmoFormats.Root root, int group) {
        return root.groupInfoRetained() && (root.groupInfoFlags(group) & INTERIOR) != 0;
    }

    /**
     * Registered pixels when they are exactly one {@code w x h} RGBA level, else null so the chain
     * decodes that level from the source instead.
     */
    static byte[] reusable(byte[] kept, int w, int h) {
        return kept != null && kept.length == (long) w * h * 4 ? kept : null;
    }

    /**
     * Mutually exclusive outcome buckets. Placements: every obj0 MODF record lands in exactly one
     * PLACEMENT bucket. Roots: every distinct root that was read lands in one ROOT bucket (roots
     * never read because of the root cap only cap their placements). Groups and batches count LOD0
     * groups and their batches for roots that were not failed or capped, except that every LOD0
     * group of a root drawn by group lands in one group bucket on each tile listing it, capped
     * there or not (GROUP_CAPPED, {@link #GROUP_OUTCOMES}). A split share of a root drawn whole
     * here (PLACEMENT_SPLIT_SHARE) adds no group or batch counts, drawn or capped: its groups are
     * the root's, counted once with it, and a capped share is recorded by PLACEMENT_CAPPED alone.
     * BATCH_CUTOUT and BATCH_BLENDED are the subsets of BATCH_DRAWN with those material classes.
     * Root caps and the opaque image budget are decided by opaque batches alone, exactly as the
     * opaque-only profile did; cutout and blended batches are then added from their own budget.
     * Images: every distinct texture1 (per alpha treatment) that an otherwise drawable batch needed
     * lands in one IMAGE bucket.
     *
     * <p>Doodads: every MDDF record lands in exactly one DOODAD bucket. Every MODD entry of a drawn
     * building's selected sets (set 0 and its MODF doodad set) lands in one SET_DOODAD bucket;
     * SET_UNSUPPORTED counts drawn buildings whose doodad declarations cannot be used. Every
     * distinct doodad model that was read lands in one MODEL bucket; MODEL_BATCH and MODEL_IMAGE
     * count the batches and images of models that were not failed or capped.
     */
    public enum Count {
        PLACEMENT_DRAWN,
        PLACEMENT_SHARED_DUPLICATE,
        PLACEMENT_UNSUPPORTED,
        PLACEMENT_CONFLICTING,
        PLACEMENT_CAPPED,
        PLACEMENT_FAILED_ROOT,
        PLACEMENT_EMPTY,
        ROOT_DRAWN,
        /** Malformed, or outside the parser ceilings (512 groups, 256 materials, 4096 GFID). */
        ROOT_FAILED,
        /** Parsed, but MOTX names, no GFID, zero groups or GFID not a multiple of groups. */
        ROOT_UNSUPPORTED,
        ROOT_UNAVAILABLE,
        ROOT_CAPPED,
        ROOT_EMPTY,
        GROUP_DRAWN,
        /**
         * An interior group, not drawn: the map is not a dungeon or raid (#280). Counts groups
         * whose root MOGI flags mark them interior, which are then never read, and read groups
         * whose own MOGP flags do.
         */
        GROUP_INTERIOR,
        /**
         * An instance interior group that did not fit the tile budgets left after the building's
         * exterior groups; only that group is omitted (#280).
         */
        GROUP_INTERIOR_CAPPED,
        GROUP_UNSUPPORTED,
        GROUP_UNAVAILABLE,
        GROUP_EMPTY,
        BATCH_DRAWN,
        BATCH_CUTOUT,
        BATCH_BLENDED,
        BATCH_UNSUPPORTED,
        BATCH_MISSING_IMAGE,
        /**
         * A building batch whose image did not fit the per-tile image count cap (IMAGE_CAPPED); a
         * budget, not a data problem.
         */
        BATCH_IMAGE_CAPPED,
        /** A cutout or blended batch over the separate material budget. */
        BATCH_CAPPED,
        /**
         * A cutout or blended batch whose image read was refused at SOURCE_INPUT (a partial,
         * network or store state, or a short read). Only these surfaces are omitted.
         */
        BATCH_UNREADABLE,
        IMAGE_DECODED,
        IMAGE_UNAVAILABLE,
        IMAGE_UNDECODABLE,
        IMAGE_CAPPED,
        DOODAD_DRAWN,
        DOODAD_SHARED_DUPLICATE,
        /** Flags, reference kind, zero ID, scale, rotation or translation outside the profile. */
        DOODAD_UNSUPPORTED,
        DOODAD_CONFLICTING,
        DOODAD_CAPPED,
        DOODAD_FAILED_MODEL,
        DOODAD_EMPTY,
        SET_DOODAD_DRAWN,
        /**
         * Name-table (MODN) reference, zero or out-of-range MODI entry, a MODD flag outside 0x1F,
         * or a bad transform.
         */
        SET_DOODAD_UNSUPPORTED,
        SET_DOODAD_CAPPED,
        SET_DOODAD_FAILED_MODEL,
        SET_DOODAD_EMPTY,
        /**
         * A drawn building whose declared doodad sets are oversized, name-only or lack the MODF
         * doodad set.
         */
        SET_UNSUPPORTED,
        MODEL_DRAWN,
        /** Malformed, or outside the reader ceilings. */
        MODEL_FAILED,
        /** Parsed, but no SKIN, an unsupported version or zero normals. */
        MODEL_UNSUPPORTED,
        /**
         * Parsed and otherwise admitted, but every admitted triangle is weighted to a billboard
         * bone, so nothing static is left to draw. Unlike the other refused models, its batch
         * buckets (MODEL_BATCH_BILLBOARD, MODEL_BATCH_UNSUPPORTED, MODEL_TRIANGLE_BILLBOARD) are
         * kept, so real-data counts show what billboards still cost.
         */
        MODEL_ALL_BILLBOARD,
        MODEL_UNAVAILABLE,
        MODEL_CAPPED,
        MODEL_EMPTY,
        MODEL_BATCH_DRAWN,
        MODEL_BATCH_UNSUPPORTED,
        /**
         * An otherwise admitted doodad batch omitted because every one of its triangles has a
         * vertex weighted to a billboard bone (or a descendant of one). Billboards turn to face the
         * camera, which the static BASIC profile does not evaluate; the rest of the model draws.
         */
        MODEL_BATCH_BILLBOARD,
        /**
         * Admitted doodad batches that keep some triangles but lost others to billboard bones: the
         * place to look for a torn wedge in a drawn model. Not an outcome bucket; such a batch also
         * lands in one of the MODEL_BATCH outcomes.
         */
        MODEL_BATCH_TRIMMED,
        /** Doodad triangles left out because a vertex is weighted to a billboard bone. */
        MODEL_TRIANGLE_BILLBOARD,
        MODEL_BATCH_MISSING_IMAGE,
        MODEL_IMAGE_DECODED,
        MODEL_IMAGE_UNAVAILABLE,
        MODEL_IMAGE_UNDECODABLE,
        MODEL_IMAGE_CAPPED,
        /**
         * Doodad source reads (model, SKIN or image) refused for a partial, network or store state
         * or a short read. Each is also counted as that read's UNAVAILABLE bucket; unlike a
         * building read, it omits only its doodads instead of failing the whole set.
         */
        DOODAD_SOURCE_INPUT,
        /**
         * Decoded images (building and model) by where their lower mip levels came from (issue
         * #265): all from the file's own mips, some from the file and the rest generated, or all
         * generated. A 1 x 1 image counts in none of these.
         */
        IMAGE_MIPS_FILE,
        IMAGE_MIPS_PARTIAL,
        IMAGE_MIPS_GENERATED,
        /**
         * Copied exterior groups whose vertex colours are carried: MOGP flag 0x4 with a first MOCV
         * of one colour per vertex (issue #375). Not an outcome bucket.
         */
        GROUP_VERTEX_COLOURS,
        /**
         * Copied interior groups lit from their vertex colours (#383): MOGP 0x2000 and 0x4 with a
         * first MOCV of one colour per vertex, carried as is and drawn with {@link #INTERIOR_LIGHT}
         * instead of the World sun. Not an outcome bucket.
         */
        GROUP_INTERIOR_LIGHT,
        /**
         * Copied exterior triangles whose winding, read counter-clockwise in raw local axes, faces
         * the same way as their vertex normals (issue #375): the evidence that decides which side
         * single-sided surfaces may cull. Not an outcome bucket.
         */
        TRIANGLE_WINDING_AGREES,
        /** Copied exterior triangles whose winding faces against their vertex normals. */
        TRIANGLE_WINDING_OPPOSES,
        /**
         * Admitted batches whose MOMT flags set a bit this profile does not apply (anything but
         * F_UNLIT 0x1 and F_UNCULLED 0x4). Not an outcome bucket; the batch is drawn without it.
         */
        BATCH_FLAGS_IGNORED,
        /**
         * Images carried as the file's own BC1, BC2 or BC3 blocks (issue #374): opaque DXT, or
         * blended BC2/BC3, whose file records every level from the drawn one down to 1 x 1. They
         * also count in IMAGE_MIPS_FILE.
         */
        IMAGE_BLOCKS,
        /**
         * Why capped doodads were left out (#308), over MDDF and set doodads together: these four
         * sum to DOODAD_CAPPED + SET_DOODAD_CAPPED. The tile's doodad placements were full
         * (MAX_DOODADS), its distinct doodad models were all read (MAX_MODELS), the doodad's model
         * was over the remaining doodad vertex, index or range budget (MODEL_CAPPED), or its draws
         * were over the tile's remaining draw budget (MAX_DRAWS).
         */
        DOODAD_CAP_PLACEMENTS,
        DOODAD_CAP_MODELS,
        DOODAD_CAP_MODEL_SIZE,
        DOODAD_CAP_DRAWS,
        /**
         * Chains allotted as sampled blocks whose blocks were not usable (issue #374): decoded to
         * RGBA8 instead, at the largest level costing no more than the blocks were charged.
         */
        IMAGE_BLOCKS_FALLBACK,
        /**
         * Drawn roots carrying their portals and group headers (#383 step 3a), on dungeon and raid
         * maps. Not an outcome bucket.
         */
        ROOT_PORTALS,
        /**
         * Drawn roots on dungeon and raid maps whose declared portals are not carried: not retained
         * by the root reader, a LOD0 group without a readable header, a MOGP portal range outside
         * MOPR, or past the tile's portal tables (WmoPortalSet). The root draws unculled. Not an
         * outcome bucket.
         */
        ROOT_PORTALS_DROPPED,
        /**
         * A very large placement drawn by group ({@link Owner}) none of whose groups this tile
         * draws: the tiles holding their MOGI box centres draw them. A placement bucket.
         */
        PLACEMENT_OTHER_TILE,
        /**
         * The root of such a placement, read here only to find which of its groups the tile draws.
         * A root bucket.
         */
        ROOT_OTHER_TILE,
        /** A LOD0 group of a very large placement that another tile draws. A group bucket. */
        GROUP_OTHER_TILE,
        /**
         * A set doodad of a very large placement drawn by group that another tile places: the one
         * it stands in, or, inside an interior group's MOGI box, the one owning that group. A set
         * doodad bucket.
         */
        SET_DOODAD_OTHER_TILE,
        /**
         * Drawn roots of very large placements, drawn by group over the tiles that list them, from
         * the large allowance ({@link ExteriorObjectLimits#LARGE_VERTICES}). Not an outcome bucket.
         */
        ROOT_BY_GROUP,
        /**
         * An interior group of a root drawn by group that this tile owns, not drawn because the
         * root carries no portal set here (its portals were not retained or their per-group ranges
         * could not be recovered from the root, see WmoPortalSet.ofGroupInfo, or the set did not
         * fit the tile's portal tables): unculled interiors would cost every frame. Counted without
         * reading the group. Also a group read as exterior whose MOGP flags say interior while its
         * MOGI flags do not: the set, built from MOGI, would never cull it. A group bucket.
         */
        GROUP_INTERIOR_NO_PORTALS,
        /**
         * A set doodad of a root drawn by group standing inside one of its interior groups' MOGI
         * boxes, on the tile owning that group, which does not draw it: not placed, so it never
         * floats without walls or spends a cap. A set doodad bucket.
         */
        SET_DOODAD_INTERIOR,
        /**
         * Drawn set doodads tied to one interior group of their building's portal set ({@link
         * Placement#portalGroup}): that group is drawn here, its MODR lists the doodad and no other
         * group this tile read does. The renderer draws them only while the group is visible. Not
         * an outcome bucket.
         */
        SET_DOODAD_IN_GROUP,
        /**
         * Set doodads of a root with a portal set that the MODR of more than one group read here
         * lists; they are never tied to a group, so they draw whenever they are in view. Not an
         * outcome bucket.
         */
        SET_DOODAD_GROUPS_SHARED,
        /**
         * Doodad models read beyond the {@link #MAX_MODELS} cap from the interior allowance ({@link
         * #MAX_INTERIOR_MODELS}), for set doodads tied to an interior group: every read, whatever
         * its outcome, as each spends a slot. Not an outcome bucket.
         */
        MODEL_INTERIOR_ALLOWANCE,
        /**
         * An exterior group this tile owns of a root drawn by group whose share here is capped
         * (ROOT_CAPPED); its owned interiors are GROUP_INTERIOR_CAPPED. A group bucket.
         */
        GROUP_CAPPED,
        /**
         * Drawn roots drawn whole from the large allowance ({@link Owner#whole}): a root in one
         * tile over what the tile's building pool left (or over an empty pool), or a border-
         * crossing root drawn whole that fits an empty tile but not this one. Not an outcome
         * bucket.
         */
        ROOT_WHOLE_LARGE,
        /**
         * Drawn border-crossing placements of a root drawn whole here that the owner rule splits:
         * each draws only its share of the root's drawn model, from the large allowance. Not an
         * outcome bucket (they are also PLACEMENT_DRAWN). A share adds no group counts; a capped
         * one is PLACEMENT_CAPPED only.
         */
        PLACEMENT_SPLIT_SHARE,
        /**
         * Derived source reads left unread because the operation's read profile for their kind was
         * spent (WorldStreamScopes.UseProfile); each is treated as unavailable and only it is
         * omitted, so it also lands in its kind's outcome bucket for an unavailable source
         * (ROOT_UNAVAILABLE for a WMO root, GROUP_UNAVAILABLE for a group, IMAGE_UNAVAILABLE for a
         * building image, MODEL_UNAVAILABLE for a doodad model or its SKIN, MODEL_IMAGE_UNAVAILABLE
         * for a doodad image) and every bucket sum still holds. Not an outcome bucket itself.
         */
        SOURCE_READ_CAPPED,
        /**
         * MOGI-interior groups a split share would have drawn here, left out because the share fits
         * only without them and is placed with its exteriors only (no other tile owns them).
         * Counted once per group left out, only for a share that is placed (a capped share is
         * PLACEMENT_CAPPED alone). Not an outcome bucket: the groups are the root's, counted once
         * with it.
         */
        SHARE_INTERIOR_DROPPED
    }

    /** The group buckets ({@link Count}). */
    static final List<Count> GROUP_OUTCOMES =
            List.of(
                    Count.GROUP_DRAWN,
                    Count.GROUP_INTERIOR,
                    Count.GROUP_INTERIOR_CAPPED,
                    Count.GROUP_UNSUPPORTED,
                    Count.GROUP_UNAVAILABLE,
                    Count.GROUP_EMPTY,
                    Count.GROUP_OTHER_TILE,
                    Count.GROUP_INTERIOR_NO_PORTALS,
                    Count.GROUP_CAPPED);

    /** What a placement draws: an outdoor WMO, an MDDF doodad or a WMO doodad-set entry. */
    public enum Kind {
        BUILDING,
        DOODAD,
        SET_DOODAD
    }

    /**
     * Row-major 3x3 rotation in native terrain axes (rows are output X, Y, Z; columns are raw
     * model-local x, y, z). Buildings use the reviewed ADT heading rule. MDDF doodads add pitch and
     * roll ({@link #mddf}); set doodads compose the building heading with their MODD quaternion.
     */
    public record Rotation(
            double m00,
            double m01,
            double m02,
            double m10,
            double m11,
            double m12,
            double m20,
            double m21,
            double m22) {
        static Rotation heading(double cos, double sin) {
            return new Rotation(-cos, sin, 0, -sin, -cos, 0, 0, 0, 1);
        }

        /**
         * The MDDF rotation in degrees: the heading (rotation y), then pitch (rotation x) about
         * model-local y, then roll (rotation z) about model-local x, applied to the model first:
         * {@code heading(y) * Ry(x) * Rx(z)}. This is TrinityCore ec72c19's {@code
         * Matrix3::fromEulerAnglesZYX(y, x, z)} (ModelInstance.cpp:29, Matrix3.cpp:1664-1681) in
         * its (z, x, up) placement frame (wmo.h:68), turned 180 degrees about up into the native
         * axes, which is exactly the heading rule's extra half turn. The wowdev ADT/v18 placement
         * matrix (rev 37119) gives the same matrix.
         */
        static Rotation mddf(double cos, double sin, double pitchDegrees, double rollDegrees) {
            var heading = heading(cos, sin);
            if (pitchDegrees == 0 && rollDegrees == 0) {
                return heading; // bit-identical to the heading-only profile
            }
            double p = pitchDegrees * (StrictMath.PI / 180.0);
            double r = rollDegrees * (StrictMath.PI / 180.0);
            double cp = StrictMath.cos(p), sp = StrictMath.sin(p);
            double cr = StrictMath.cos(r), sr = StrictMath.sin(r);
            var pitch = new Rotation(cp, 0, sp, 0, 1, 0, -sp, 0, cp);
            var roll = new Rotation(1, 0, 0, 0, cr, -sr, 0, sr, cr);
            return heading.times(pitch.times(roll));
        }

        boolean finite() {
            for (double v : rowMajor()) {
                if (!Double.isFinite(v)) {
                    return false;
                }
            }
            return true;
        }

        /** Unit quaternion (x, y, z, w) to a rotation matrix; the input is normalized in double. */
        static Rotation quaternion(double x, double y, double z, double w) {
            double n = StrictMath.sqrt(x * x + y * y + z * z + w * w);
            x /= n;
            y /= n;
            z /= n;
            w /= n;
            return new Rotation(
                    1 - 2 * (y * y + z * z),
                    2 * (x * y - z * w),
                    2 * (x * z + y * w),
                    2 * (x * y + z * w),
                    1 - 2 * (x * x + z * z),
                    2 * (y * z - x * w),
                    2 * (x * z - y * w),
                    2 * (y * z + x * w),
                    1 - 2 * (x * x + y * y));
        }

        Rotation times(Rotation b) {
            return new Rotation(
                    m00 * b.m00 + m01 * b.m10 + m02 * b.m20,
                    m00 * b.m01 + m01 * b.m11 + m02 * b.m21,
                    m00 * b.m02 + m01 * b.m12 + m02 * b.m22,
                    m10 * b.m00 + m11 * b.m10 + m12 * b.m20,
                    m10 * b.m01 + m11 * b.m11 + m12 * b.m21,
                    m10 * b.m02 + m11 * b.m12 + m12 * b.m22,
                    m20 * b.m00 + m21 * b.m10 + m22 * b.m20,
                    m20 * b.m01 + m21 * b.m11 + m22 * b.m21,
                    m20 * b.m02 + m21 * b.m12 + m22 * b.m22);
        }

        public double[] rowMajor() {
            return new double[] {m00, m01, m02, m10, m11, m12, m20, m21, m22};
        }
    }

    /**
     * One drawn placement. Model matrices are built later from these doubles, never here. For
     * buildings and MDDF doodads cos/sin are the heading; for set doodads they are the parent
     * building's heading, and {@link #rotation()} is authoritative for every kind. Set doodads use
     * {@code ordinal} for their MODD index, {@code doodadSet} for the selected MODS set the entry
     * came from and {@code uniqueId} for their building's.
     */
    public record Placement(
            Kind kind,
            int ordinal,
            long uniqueId,
            long rootId,
            int model,
            int flags,
            int doodadSet,
            int nameSet,
            double x,
            double y,
            double z,
            double scale,
            double cos,
            double sin,
            Rotation rotation,
            int portalGroup) {
        /**
         * {@code portalGroup}: for a set doodad tied to one interior group, that group's ordinal in
         * its building's portal set (the building is the BUILDING placement with the same {@code
         * uniqueId}), drawn only while a walk of that set reaches the group; otherwise -1.
         */
        public Placement {
            if (portalGroup < -1 || portalGroup >= 0 && kind != Kind.SET_DOODAD) {
                throw new IllegalArgumentException("Placement portal group");
            }
        }

        /** A placement tied to no portal group. */
        public Placement(
                Kind kind,
                int ordinal,
                long uniqueId,
                long rootId,
                int model,
                int flags,
                int doodadSet,
                int nameSet,
                double x,
                double y,
                double z,
                double scale,
                double cos,
                double sin,
                Rotation rotation) {
            this(
                    kind, ordinal, uniqueId, rootId, model, flags, doodadSet, nameSet, x, y, z,
                    scale, cos, sin, rotation, -1);
        }
    }

    /**
     * Index range into one mesh, drawn with one decoded image (index into images()); blend is the
     * range class: {@link #OPAQUE}, {@link #CUTOUT}, {@link #BLENDED} or {@link #ADDITIVE}, plus
     * {@link #UNLIT} for an unlit surface. Use {@link #kind} for the kind alone.
     */
    public record DrawRange(int firstIndex, int indexCount, int image, int material, int blend) {}

    /** One exterior LOD0 group of a root, in local WMO coordinates. */
    public static final class Mesh {
        private final int groupOrdinal;
        private final long groupId, groupFlags;
        private final float[] vertices;
        private final int[] colours;
        private final int[] indices;
        private final List<DrawRange> ranges;

        private Mesh(
                int groupOrdinal,
                long groupId,
                long groupFlags,
                float[] vertices,
                int[] colours,
                int[] indices,
                List<DrawRange> ranges) {
            this.groupOrdinal = groupOrdinal;
            this.groupId = groupId;
            this.groupFlags = groupFlags;
            this.vertices = vertices;
            this.colours = colours;
            this.indices = indices;
            this.ranges = List.copyOf(ranges);
        }

        public int groupOrdinal() {
            return groupOrdinal;
        }

        public long groupId() {
            return groupId;
        }

        public long groupFlags() {
            return groupFlags;
        }

        public int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }

        public FloatBuffer vertices() {
            return FloatBuffer.wrap(vertices).asReadOnlyBuffer();
        }

        /**
         * Vertex colours, one {@code r | g << 8 | b << 16} (0..255 each) per vertex, or empty when
         * the mesh has none (doodads, and groups without MOGP flag 0x4). An exterior group's are
         * its MOCV, added to the World light; an interior group's are its MOCV as its whole light
         * ({@link #INTERIOR_LIGHT}).
         */
        public IntBuffer colours() {
            return IntBuffer.wrap(colours).asReadOnlyBuffer();
        }

        public IntBuffer indices() {
            return IntBuffer.wrap(indices).asReadOnlyBuffer();
        }

        public List<DrawRange> ranges() {
            return ranges;
        }
    }

    /**
     * Shared geometry; several placements may reference one model. A doodad model's rootId is its
     * M2 FileDataID and its single mesh's groupId is its SKIN FileDataID.
     *
     * @param portals the root's retained portals and LOD0 group headers (#383 step 3a), carried for
     *     drawn roots on dungeon and raid maps; each mesh's {@link Mesh#groupOrdinal} is its group
     *     in them. Null for doodads, on open-world maps, and for a root whose set is not carried
     *     whole ({@link Count#ROOT_PORTALS_DROPPED}).
     */
    public record Model(long rootId, boolean doodad, List<Mesh> meshes, WmoPortalSet portals) {
        public Model {
            meshes = List.copyOf(meshes);
        }

        public Model(long rootId, boolean doodad, List<Mesh> meshes) {
            this(rootId, doodad, meshes, null);
        }
    }

    /** Decoded RGBA8; alpha is forced to 255 for images drawn only by opaque ranges. */
    public static final class Image {
        private final long id;
        private final int width, height, mipLevel;
        private final byte[] rgba;
        private final ObjectTextureMips.Source mips;

        /** Storage format of {@link #data} ({@link ObjectTextureFormats}). */
        private final int format;

        private Image(long id, int width, int height, int mipLevel, byte[] rgba) {
            this(id, width, height, mipLevel, rgba, null);
        }

        private Image(
                long id,
                int width,
                int height,
                int mipLevel,
                byte[] rgba,
                ObjectTextureMips.Source mips) {
            this(id, width, height, mipLevel, rgba, mips, ObjectTextureFormats.RGBA8);
        }

        private Image(
                long id,
                int width,
                int height,
                int mipLevel,
                byte[] data,
                ObjectTextureMips.Source mips,
                int format) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.mipLevel = mipLevel;
            this.rgba = data;
            this.mips = mips;
            this.format = format;
        }

        /**
         * How the chain is stored and uploaded (issue #374): {@link ObjectTextureFormats#RGBA8}, or
         * the file's own {@link ObjectTextureFormats#BC1}, {@link ObjectTextureFormats#BC2} or
         * {@link ObjectTextureFormats#BC3} blocks.
         */
        public int format() {
            return format;
        }

        /** The whole chain in {@link #format()}, top level first, as the GPU takes it. */
        public ByteBuffer data() {
            return ByteBuffer.wrap(rgba).asReadOnlyBuffer();
        }

        /**
         * Where the chain's lower levels came from; null only while a builder still holds the
         * single registered level.
         */
        public ObjectTextureMips.Source mips() {
            return mips;
        }

        public long id() {
            return id;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int mipLevel() {
            return mipLevel;
        }

        /**
         * The whole mip chain as RGBA8, top level first ({@link ObjectTextureMips#chainPixels}
         * texels), for every image a tile set returns. For a compressed chain this decodes and
         * allocates a new RGBA8 copy on every call; uploads use {@link #data()} instead.
         */
        public ByteBuffer rgba() {
            if (format == ObjectTextureFormats.RGBA8) {
                return ByteBuffer.wrap(rgba).asReadOnlyBuffer();
            }
            return ByteBuffer.wrap(
                            ObjectTextureFormats.decodeChain(
                                    format, width, height, ByteBuffer.wrap(rgba), 0))
                    .asReadOnlyBuffer();
        }
    }

    /**
     * How one tile's placements use its models, for instancing estimates (#382): distinct building
     * and doodad models with at least one placement, the draws of one submit per placement and
     * range ({@code draws}), the draws if each model were submitted once for all its placements
     * ({@code instancedDraws}), and placements per used model, largest first.
     */
    public record ModelUse(
            int buildingModels,
            int doodadModels,
            long draws,
            long instancedDraws,
            int[] placementsPerModel) {
        public ModelUse {
            placementsPerModel = placementsPerModel.clone();
        }

        @Override
        public int[] placementsPerModel() {
            return placementsPerModel.clone();
        }

        /** {@code placementModel} holds each placement's model index into the other arrays. */
        public static ModelUse of(int[] modelRanges, boolean[] doodad, int[] placementModel) {
            if (modelRanges.length != doodad.length) {
                throw new IllegalArgumentException("Model arrays differ in length");
            }
            int[] placed = new int[modelRanges.length];
            long draws = 0;
            for (int model : placementModel) {
                placed[model]++;
                draws += modelRanges[model];
            }
            int buildings = 0, doodads = 0;
            long instanced = 0;
            int used = 0;
            for (int m = 0; m < placed.length; m++) {
                if (placed[m] == 0) {
                    continue;
                }
                used++;
                instanced += modelRanges[m];
                if (doodad[m]) {
                    doodads++;
                } else {
                    buildings++;
                }
            }
            int[] counts = new int[used];
            int at = 0;
            for (int count : placed) {
                if (count > 0) {
                    counts[at++] = count;
                }
            }
            Arrays.sort(counts);
            for (int i = 0, j = counts.length - 1; i < j; i++, j--) {
                int swap = counts[i];
                counts[i] = counts[j];
                counts[j] = swap;
            }
            return new ModelUse(buildings, doodads, draws, instanced, counts);
        }
    }

    /** Immutable neutral per-tile set. Close only retires access; it holds no native resource. */
    public static final class TileObjects implements AutoCloseable {
        private final long mapId;
        private final int tileX, tileY;
        private final List<Model> models;
        private final List<Placement> placements;
        private final List<Image> images;
        private final int[] counts;
        private final int vertices, indices, ranges, pixels;
        private final RuntimeException modelFailure;
        private volatile boolean closed;

        /** Compressed formats the session samples as blocks; the objects file charges them so. */
        private final int compressedFormats;

        /** Most image source bytes its builder kept at once (#265); diagnostics and tests. */
        long retainedSourceBytes;

        /** BLP levels its builder decoded, over every image (#360); diagnostics and tests. */
        int levelDecodes;

        private TileObjects(
                long mapId,
                int tileX,
                int tileY,
                List<Model> models,
                List<Placement> placements,
                List<Image> images,
                int[] counts,
                RuntimeException modelFailure,
                ImageDetail detail,
                int compressedFormats) {
            this.compressedFormats = compressedFormats;
            this.modelFailure = modelFailure;
            this.mapId = mapId;
            this.tileX = tileX;
            this.tileY = tileY;
            this.models = List.copyOf(models);
            this.placements = List.copyOf(placements);
            this.images = List.copyOf(images);
            this.counts = counts.clone();
            int v = 0, i = 0, r = 0, m = 0, p = 0;
            for (var model : this.models) {
                for (var mesh : model.meshes()) {
                    v += mesh.vertexCount();
                    i += mesh.indices.length;
                    r += mesh.ranges.size();
                    m++;
                }
            }
            for (var image : this.images) {
                // Charged as uploaded: a chain the session samples as blocks at its stored bytes
                // (in RGBA8 texel units), any other at its top level's texels.
                p +=
                        (compressedFormats & (1 << image.format)) != 0
                                ? ObjectTextureFormats.chainBytes(
                                                image.format, image.width, image.height)
                                        / 4
                                : image.width * image.height;
            }
            require(
                    this.placements.size() <= MAX_PLACEMENTS + MAX_DOODADS
                            && this.models.size() <= MAX_ROOTS + MAX_MODELS + MAX_INTERIOR_MODELS
                            && this.images.size()
                                    <= MAX_IMAGES
                                            + MAX_LARGE_IMAGES
                                            + MAX_LARGE_INTERIOR_IMAGES
                                            + MAX_MODEL_IMAGES
                                            + MAX_INTERIOR_MODEL_IMAGES
                                            + MAX_MATERIAL_IMAGES
                            && v
                                    <= MAX_VERTICES
                                            + MAX_TILE_INTERIOR_VERTICES
                                            + MAX_LARGE_VERTICES
                                            + MAX_LARGE_INTERIOR_VERTICES
                                            + MAX_DOODAD_VERTICES
                                            + MAX_MATERIAL_VERTICES
                            && i
                                    <= MAX_INDICES
                                            + MAX_TILE_INTERIOR_INDICES
                                            + MAX_LARGE_INDICES
                                            + MAX_LARGE_INTERIOR_INDICES
                                            + MAX_DOODAD_INDICES
                                            + MAX_MATERIAL_INDICES
                            && r
                                    <= MAX_RANGES
                                            + MAX_TILE_INTERIOR_RANGES
                                            + MAX_LARGE_RANGES
                                            + MAX_LARGE_INTERIOR_RANGES
                                            + MAX_DOODAD_RANGES
                                            + MAX_MATERIAL_RANGES
                            && m <= MAX_MESHES + MAX_MODELS + MAX_INTERIOR_MODELS
                            && p <= detail.totalPixels(),
                    "EXTERIOR_TILE_BOUNDS");
            vertices = v;
            indices = i;
            ranges = r;
            pixels = p;
        }

        private void check() {
            if (closed) {
                throw new IllegalStateException("Exterior object set retired");
            }
        }

        /**
         * Opt-in measurement ({@code -Dvimi.world.stageTimings}): adds every non-zero count bucket
         * of this set to the process tallies {@code objects.count.<BUCKET>}, plus its tile, vertex,
         * index, range and pixel totals. Does nothing while the timings are off.
         */
        public void tallyCounts() {
            check();
            if (!WorldStageTimings.enabled()) {
                return;
            }
            WorldStageTimings.add("objects.count.tiles", 1);
            for (var count : Count.values()) {
                int n = counts[count.ordinal()];
                if (n != 0) {
                    WorldStageTimings.add("objects.count." + count.name(), n);
                }
            }
            WorldStageTimings.add("objects.count.vertices", vertices);
            WorldStageTimings.add("objects.count.indices", indices);
            WorldStageTimings.add("objects.count.ranges", ranges);
            WorldStageTimings.add("objects.count.pixels", pixels);
            int[] modelRanges = new int[models.size()];
            boolean[] doodad = new boolean[models.size()];
            for (int m = 0; m < modelRanges.length; m++) {
                doodad[m] = models.get(m).doodad();
                for (var mesh : models.get(m).meshes()) {
                    modelRanges[m] += mesh.ranges.size();
                }
            }
            int[] placementModel = new int[placements.size()];
            for (int p = 0; p < placementModel.length; p++) {
                placementModel[p] = placements.get(p).model();
            }
            var use = ModelUse.of(modelRanges, doodad, placementModel);
            WorldStageTimings.value("objects.models.buildingsPerTile", use.buildingModels());
            WorldStageTimings.value("objects.models.doodadsPerTile", use.doodadModels());
            WorldStageTimings.value("objects.models.drawsPerTile", use.draws());
            WorldStageTimings.value("objects.models.instancedDrawsPerTile", use.instancedDraws());
            for (int placed : use.placementsPerModel()) {
                WorldStageTimings.value("objects.models.placementsPerModel", placed);
            }
            for (int rank = 0; rank < Math.min(5, use.placementsPerModel().length); rank++) {
                WorldStageTimings.value(
                        "objects.models.top" + (rank + 1) + "Placements",
                        use.placementsPerModel()[rank]);
            }
        }

        public String recipe() {
            check();
            return RECIPE;
        }

        public long mapId() {
            check();
            return mapId;
        }

        public int tileX() {
            check();
            return tileX;
        }

        public int tileY() {
            check();
            return tileY;
        }

        public List<Model> models() {
            check();
            return models;
        }

        public List<Placement> placements() {
            check();
            return placements;
        }

        public List<Image> images() {
            check();
            return images;
        }

        public int count(Count count) {
            check();
            return counts[count.ordinal()];
        }

        /**
         * The first unexpected doodad reader failure counted as {@link Count#MODEL_FAILED}, or
         * null. Only its doodads were omitted; the caller logs it so reader bugs stay visible.
         */
        public RuntimeException modelFailure() {
            check();
            return modelFailure;
        }

        public int vertices() {
            check();
            return vertices;
        }

        public int indices() {
            check();
            return indices;
        }

        public int ranges() {
            check();
            return ranges;
        }

        public int pixels() {
            check();
            return pixels;
        }

        /**
         * The compressed formats this set was charged for as blocks (bit per {@link
         * ObjectTextureFormats} code): the objects file must be built with the same mask.
         */
        public int compressedFormats() {
            check();
            return compressedFormats;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Reads and qualifies one recorded tile's outdoor exteriors under BASIC_EXTERIOR. */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token) {
        return prepare(engine, scope, tile, token, ModelReader.STANDARD, ImageDetail.STANDARD);
    }

    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ImageDetail detail) {
        return prepare(engine, scope, tile, token, ModelReader.STANDARD, detail);
    }

    /** Reads one doodad model's bytes; tests substitute a failing reader. */
    @FunctionalInterface
    interface ModelReader {
        ModelReader STANDARD =
                (id, raw, token) -> M2ModelReader.read(AssetSource.bytes(raw), MODEL_LIMITS, token);

        java.util.concurrent.CompletableFuture<M2Types.Model> read(
                long id, byte[] raw, Cancellation token);
    }

    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader) {
        return prepare(engine, scope, tile, token, modelReader, ImageDetail.STANDARD);
    }

    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail) {
        return prepare(
                engine, scope, tile, token, modelReader, detail, RETAINED_SOURCE_BYTES, false);
    }

    /**
     * As above; with {@code interiors} (instance interiors, #280) interior WMO groups are drawn
     * too, only within the budgets every building's exterior groups leave.
     */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail,
            boolean interiors) {
        return prepare(
                engine, scope, tile, token, modelReader, detail, RETAINED_SOURCE_BYTES, interiors);
    }

    /** As above, with the retained image source bound; tests pass 0 to force every re-read. */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail,
            long retainLimit) {
        return prepare(engine, scope, tile, token, modelReader, detail, retainLimit, false, 0);
    }

    /** As above, with {@code interiors} drawn too (instance interiors, #280). */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail,
            long retainLimit,
            boolean interiors) {
        return prepare(engine, scope, tile, token, modelReader, detail, retainLimit, interiors, 0);
    }

    /**
     * As above, for a session whose child samples {@code compressedFormats} as blocks (bit per
     * {@link ObjectTextureFormats} code, issue #374): chains carried in those formats are charged
     * their stored bytes and may take the larger sides.
     */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail,
            long retainLimit,
            boolean interiors,
            int compressedFormats) {
        return prepare(
                engine,
                scope,
                tile,
                token,
                modelReader,
                detail,
                retainLimit,
                Interiors.of(interiors),
                compressedFormats);
    }

    /** As above, with the map's interior mode (#280, #383). */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail,
            long retainLimit,
            Interiors interiors,
            int compressedFormats) {
        Objects.requireNonNull(interiors);
        if ((compressedFormats & ~0xE) != 0) {
            throw new IllegalArgumentException("Compressed format mask");
        }
        if (retainLimit < 0) {
            throw new IllegalArgumentException("Retained source bound");
        }
        Objects.requireNonNull(modelReader);
        Objects.requireNonNull(detail);
        Objects.requireNonNull(engine);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(tile);
        Objects.requireNonNull(token);
        long started = WorldStageTimings.start();
        try {
            return engine.withRecordedTile(
                    scope,
                    tile,
                    WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                    token,
                    admission ->
                            new Builder(
                                            admission,
                                            modelReader,
                                            detail,
                                            retainLimit,
                                            interiors,
                                            compressedFormats)
                                    .build());
        } finally {
            WorldStageTimings.stop("objects.tile.total", started);
        }
    }

    private enum RootState {
        DRAWN,
        FAILED,
        CAPPED,
        EMPTY,
        /** Drawn by group: read and admitted, placed after every other building. */
        BY_GROUP,
        /** Drawn by group, and none of its groups is this tile's. */
        OTHER_TILE
    }

    /**
     * Which LOD0 groups of a very large placement a tile draws. A placement whose recorded MODF
     * bounds cross a tile border, and whose root's exterior groups are over the opaque building
     * pool of an empty tile (on maps drawing open-world interiors, its exterior and MOGI-interior
     * groups together), cannot be drawn whole by any tile that lists it. It is drawn by group
     * instead: each group belongs to the tile holding the centre of its MOGI box, placed by the
     * building transform and kept within the tiles the recorded bounds cover. A root without
     * retained MOGI bounds belongs whole to the tile holding the centre of the recorded bounds. The
     * rule reads only the placement and its root, so the tiles that list a placement agree on every
     * group's owner without seeing each other, and each group is drawn by exactly one of them. A
     * part of the building is then resident exactly while its tile is, as terrain is. The rule
     * assumes every ADT that lists the placement records an identical MODF entry (position,
     * rotation, scale and bounds), as Blizzard's data does; tiles whose records differed would
     * compute different owners.
     */
    static final class Owner {
        private final int tileX, tileY, minX, maxX, minY, maxY;
        private final double x, y, scale;
        private final double[] rotation;
        private final double centreX, centreY;
        private final boolean whole;

        private Owner(
                int tileX,
                int tileY,
                int[] footprint,
                double x,
                double y,
                double scale,
                Rotation rotation,
                double centreX,
                double centreY) {
            this.tileX = tileX;
            this.tileY = tileY;
            minX = footprint[0];
            maxX = footprint[1];
            minY = footprint[2];
            maxY = footprint[3];
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.rotation = rotation.rowMajor();
            this.centreX = centreX;
            this.centreY = centreY;
            whole = false;
        }

        /** {@link #whole}: owns every group and every set doodad on tile (tileX, tileY). */
        private Owner(int tileX, int tileY) {
            this.tileX = tileX;
            this.tileY = tileY;
            minX = maxX = tileX;
            minY = maxY = tileY;
            x = y = centreX = centreY = 0;
            scale = 1;
            rotation = new double[9];
            whole = true;
        }

        /**
         * The rule of a root drawn whole from the large allowance ({@link Builder#readRoot}): it
         * owns every group and set doodad, so the tile draws the root as it would draw it from its
         * building pool. It changes only which allowance pays: every tile listing a border-crossing
         * placement still draws it whole, as without the fallback, and the copies coincide where
         * several of them do.
         */
        static Owner whole(int tileX, int tileY) {
            return new Owner(tileX, tileY);
        }

        /**
         * The owner rule of a qualified placement on tile ({@code tileX}, {@code tileY}); null when
         * its recorded bounds lie in one tile, so it is never drawn by group.
         */
        static Owner of(
                WorldPlacements.WorldObject record,
                double x,
                double y,
                double scale,
                Rotation rotation,
                int tileX,
                int tileY) {
            if (!crosses(record)) {
                return null;
            }
            double c = 51200.0 / 3.0;
            // Recorded bounds are raw ADT axes, as the position: native x from raw z, y from x.
            double lowX = c - (double) record.upperBounds().z();
            double highX = c - (double) record.lowerBounds().z();
            double lowY = c - (double) record.upperBounds().x();
            double highY = c - (double) record.lowerBounds().x();
            if (!finite(lowX, highX, lowY) || !Double.isFinite(highY)) {
                return null;
            }
            int[] footprint = {tile(highX), tile(lowX), tile(highY), tile(lowY)};
            if (footprint[0] == footprint[1] && footprint[2] == footprint[3]) {
                return null;
            }
            return new Owner(
                    tileX,
                    tileY,
                    footprint,
                    x,
                    y,
                    scale,
                    rotation,
                    (lowX + highX) / 2,
                    (lowY + highY) / 2);
        }

        /** Whether a placement's recorded bounds cross a tile border (the owner rule applies). */
        static boolean crosses(WorldPlacements.WorldObject record) {
            double c = 51200.0 / 3.0;
            double lowX = c - (double) record.upperBounds().z();
            double highX = c - (double) record.lowerBounds().z();
            double lowY = c - (double) record.upperBounds().x();
            double highY = c - (double) record.lowerBounds().x();
            if (!finite(lowX, highX, lowY) || !Double.isFinite(highY)) {
                return false;
            }
            return tile(highX) != tile(lowX) || tile(highY) != tile(lowY);
        }

        /** The filename tile index on one native axis, the camera's rule, within the map. */
        static int tile(double nativeCoordinate) {
            double index = Math.floor(32 - nativeCoordinate / WorldFormats.TILE_SIZE);
            return (int) Math.max(0, Math.min(63, index));
        }

        /** Whether the native point belongs to this tile, kept within the recorded bounds. */
        boolean owns(double nativeX, double nativeY) {
            if (whole) {
                return true;
            }
            int tx = Math.max(minX, Math.min(maxX, tile(nativeX)));
            int ty = Math.max(minY, Math.min(maxY, tile(nativeY)));
            return tx == tileX && ty == tileY;
        }

        /** Per LOD0 group of {@code root}, whether this tile draws it. */
        boolean[] owned(WmoFormats.Root root) {
            var owned = new boolean[(int) root.header().groupCount()];
            if (whole) {
                Arrays.fill(owned, true);
                return owned;
            }
            if (!root.groupInfoBoundsRetained()) {
                Arrays.fill(owned, owns(centreX, centreY));
                return owned;
            }
            double[] m = rotation;
            for (int g = 0; g < owned.length; g++) {
                var lower = root.groupInfoLower(g);
                var upper = root.groupInfoUpper(g);
                double a = ((double) lower.x() + upper.x()) / 2;
                double b = ((double) lower.y() + upper.y()) / 2;
                double h = ((double) lower.z() + upper.z()) / 2;
                owned[g] =
                        owns(
                                x + scale * (m[0] * a + m[1] * b + m[2] * h),
                                y + scale * (m[3] * a + m[4] * b + m[5] * h));
            }
            return owned;
        }
    }

    /**
     * A root drawn by group, its owned groups classified, waiting for {@link Builder#placeByGroup}:
     * per LOD0 group the exterior copy the sizing pass already read (null otherwise) and whether
     * the group is still to be read, and the same for its owned MOGI-interior groups.
     */
    private record ByGroup(
            WmoFormats.Root root,
            PendingMesh[] ready,
            boolean[] unread,
            PendingMesh[] readyInterior,
            boolean[] unreadInterior,
            int[] local) {}

    /**
     * One group the sizing pass of a border-crossing root read: its exterior copy (null when it
     * failed, was dropped or is an interior), whether its MOGP flags say interior, and the counts
     * its read made, kept apart so a root drawn by group counts only its own groups.
     */
    private record Sized(PendingMesh mesh, boolean interior, int[] counts) {}

    /** A placement of a root drawn by group, with its owner rule. */
    private record Queued(
            WorldPlacements.WorldObject record,
            WorldBasicWmo.Transform transform,
            Owner owner,
            boolean fallback) {}

    /** Which opaque allowance a root budget answers to. */
    private enum Allowance {
        /** What is left of the tile's building pool. */
        TILE,
        /**
         * What is left of the tile's building pool with the interior allowance of the tile's own
         * buildings added ({@link #MAX_TILE_INTERIOR_VERTICES}): only for their interior groups,
         * after every exterior.
         */
        TILE_INTERIOR,
        /** An empty tile's building pool: whether the root could ever fit one alone. */
        ALONE,
        /**
         * What is left of the tile's large allowance with the interior allowance added, one pool
         * for roots drawn by group and roots drawn whole from it: every such root's exteriors
         * first, then their interiors from what the exteriors left.
         */
        LARGE
    }

    /** Where resolveBuilding charges a mesh's kept opaque geometry and its images. */
    private enum Charge {
        BUILDING,
        /** The large and interior allowances together, their image slots included. */
        LARGE
    }

    /** What exteriors placed after an interior step need of the tile's budgets. */
    private record LaterExteriors(
            int meshes, long draws, int slots, long vertices, long indices, long ranges) {}

    /** A drawn root's MOGI-interior groups (flagged) waiting for the interior read pass (#383). */
    private record DeferredInteriors(
            WmoFormats.Root root, WmoFormats.GroupHeader[] headers, boolean[] deferred) {}

    private record Candidate(int firstIndex, int indexCount, int material, long image, int blend) {}

    /** copyModel's marker for a model whose every admitted triangle billboards. */
    private static final PendingMesh ALL_BILLBOARD =
            new PendingMesh(0, 0, 0, new float[0], new int[0], new int[0], List.of());

    private record PendingMesh(
            int ordinal,
            long id,
            long flags,
            float[] vertices,
            int[] colours,
            int[] indices,
            List<Candidate> ranges) {}

    /** Image pools with separate read caps, pixel budgets and mip sides. */
    private enum Pool {
        BUILDING,
        /** Cutout and blended building surfaces. */
        MATERIAL,
        DOODAD
    }

    /** image() results that are not slots. */
    private static final int MISSING = -1, UNREADABLE = -2, IMAGE_FULL = -3;

    private static final class Builder {
        private final WorldStreamScopes.TileAdmission admission;
        private final Cancellation token;
        private final int[] counts = new int[Count.values().length];
        private final List<Model> models = new ArrayList<>();
        private final List<Placement> placements = new ArrayList<>();
        private final List<Image> images = new ArrayList<>();
        private final Map<Long, Integer> imageSlots = new HashMap<>();
        private final Map<Long, Integer> modelImageSlots = new HashMap<>();
        private final Map<Long, Integer> materialImageSlots = new HashMap<>();
        private final Map<Long, RootState> rootStates = new HashMap<>();
        private final Map<Long, Integer> modelSlots = new HashMap<>();
        private final Map<Long, WmoFormats.Doodads> rootDoodads = new HashMap<>();
        private final Map<Long, RootState> doodadStates = new HashMap<>();

        /** The current placement pass's qualified doodads, in order ({@link #placeQueued}). */
        private final List<QueuedDoodad> queued = new ArrayList<>();

        private final Map<Long, Integer> doodadSlots = new HashMap<>();

        /** obj0 MWDR ranges and MWDS set indexes, for MODF flag 0x80 placements. */
        private List<WorldPlacements.SetRange> setRanges = List.of();

        private List<Integer> setIds = List.of();

        /**
         * Image source bytes by file, kept from an image's first read until its chain is built, so
         * the budget's relevel and the chain's file mips need no second read (#265). One file used
         * under two material classes is kept and counted once. Bounded by retainLimit: past it a
         * re-read source serves its one use and is dropped.
         */
        private final Map<Long, byte[]> sources = new HashMap<>();

        /** Files whose second read was refused; never tried again. */
        private final java.util.Set<Long> unreadSources = new java.util.HashSet<>();

        private final long retainLimit;
        private long retainedSourceBytes, peakRetainedSourceBytes;

        /** Pixel decodes of single BLP levels (registration, relevel and chain); diagnostics. */
        private int levelDecodes;

        /** Registered images by slot, in admission (priority) order; null for other slots. */
        private final List<Demand> demands = new ArrayList<>();

        /** Building placements with opaque draws; only these take one of MAX_PLACEMENTS. */
        private int opaquePlacements;

        private int rootsRead, groupsRead, imagesRead, vertices, indices, ranges;

        /**
         * MOGI-interior groups read only to size border-crossing roots ({@link
         * #interiorsOverAlone}), for the measurement probe. Never charged to {@code groupsRead}, so
         * sizing never takes a group read an exterior could use, and never part of a decision: the
         * read profile allows {@link #MAX_SIZING_READS} for every root a tile may read.
         */
        private int sizingReads;

        private int modelsRead, modelImagesRead, doodadVertices, doodadIndices, doodadRanges;

        /** While true, a doodad model being read charges its images to the interior slots. */
        private boolean interiorModelCharge;

        /** Doodad models (M2 FileDataIDs) read from the interior allowance. */
        private final java.util.Set<Long> interiorModels = new java.util.HashSet<>();

        /**
         * Per root's doodad declarations (by identity, as each root holds one), each MODD entry's
         * only listing group among the groups this tile read (their MODR): -1 for none, -2 for more
         * than one. One short per declared doodad, so a malformed MODR costs no more.
         */
        private final Map<WmoFormats.Doodads, short[]> doodadGroups =
                new java.util.IdentityHashMap<>();

        private int doodads, draws;
        private int materialImagesRead, materialVertices, materialIndices;
        private int materialRanges;

        private final ModelReader modelReader;
        private RuntimeException modelFailure;

        private final ImageDetail detail;

        /** Compressed formats the session samples as blocks (bit per format code). */
        private final int compressedFormats;

        /** Draw interior WMO groups too (#280, #383). */
        private final boolean interiors;

        /** Open-world maps: MOGI-interior groups are read after every exterior (#383). */
        private final boolean deferInteriors;

        /** Interior meshes of drawn roots, held for {@link #placeInteriors}; bounded copies. */
        private final Map<Long, List<PendingMesh>> heldInteriors = new LinkedHashMap<>();

        /**
         * Drawn roots' MOGI-interior groups, read by {@link #readInteriors}, in admission order.
         */
        private final Map<Long, DeferredInteriors> deferredInteriors = new LinkedHashMap<>();

        /** Interior-only roots whose interior groups did not fit the tile's group reads. */
        private final java.util.Set<Long> interiorReadsCapped = new java.util.HashSet<>();

        /**
         * True while interiors are read and admitted: a group or image refused at SOURCE_INPUT (not
         * readable now, such as a file not stored yet) omits only that interior group or batch, as
         * a missing one does, and never costs the tile its exteriors (#383).
         */
        private boolean interiorPass;

        /** Interior meshes admitted by {@link #placeInteriors}, by identity. */
        private final java.util.Set<Mesh> interiorMeshes =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        /**
         * Image slots that only interior meshes, or roots drawn by group, draw, from {@link
         * #allocateImages}: they get their levels after every exterior image, and a second read of
         * one is tolerated as in the interior pass.
         */
        private boolean[] interiorOnlySlots = new boolean[0];

        private long heldVertices, heldIndices;

        /** Portal table rows the carried sets use so far (#383), against WmoPortalSet bounds. */
        private int portalSets, portalGroups, portalCount, portalVertices, portalRefs;

        /** Opaque geometry charged to or reserved from the large allowance (MAX_LARGE_*). */
        private long largeVertices, largeIndices, largeRanges;

        /** Opaque images registered for roots drawn by group (MAX_LARGE_IMAGES). */
        private int largeImagesRead;

        /** Where resolveBuilding charges kept opaque geometry. */
        private Charge charge = Charge.BUILDING;

        /**
         * resolveBuilding caps every cutout and blended range: a root drawn by group short of
         * draws.
         */
        private boolean skipMaterials;

        /** Roots drawn by group, classified, by root; placed by {@link #placeByGroup}. */
        private final Map<Long, ByGroup> byGroupReads = new HashMap<>();

        /** Every root this tile decided to draw by group, drawn or not, for its set doodads. */
        private final Map<Long, WmoFormats.Root> byGroupRootFacts = new HashMap<>();

        /**
         * Buildings drawn by group, and placements of such roots this tile draws no group of
         * (another tile's, empty or capped here): each places the set doodads standing in this
         * tile, after the tile's own.
         */
        // Kept as placed: removeModels renumbers the set's placements but not these copies, so
        // their model() is only a drawn (>= 0) or doodads-only (-1) flag, never an index.
        private final List<Placement> byGroupBuildings = new ArrayList<>();

        /** The one placement each root drawn by group is placed for, in admission order. */
        private final Map<Long, Queued> byGroupQueue = new LinkedHashMap<>();

        /** Roots with a border-crossing placement on this tile (the owner rule applies to it). */
        private final java.util.Set<Long> crossingRoots = new java.util.HashSet<>();

        /**
         * Roots drawn whole here, decided by a placement inside the tile, that the owner rule would
         * draw by group when sized alone with their interiors ({@link #splitsAlone}): their
         * border-crossing placements are not drawn here, since a neighbour deciding one of them
         * splits it.
         */
        private final java.util.Set<Long> splitsAlone = new java.util.HashSet<>();

        /**
         * Border-crossing placements of roots drawn whole here that the owner rule splits: each
         * draws only the groups and set doodads it gives this tile ({@link #placeSplitShares}).
         */
        private final List<Queued> splitShares = new ArrayList<>();

        /**
         * Model slots of drawn shares: their portal set is the root's, counted once (ROOT_PORTALS).
         * Slots, not models: dropping unused images rebuilds every model in its slot.
         */
        private final java.util.Set<Integer> shareModels = new java.util.HashSet<>();

        /** Roots drawn by group whose exteriors are placed, waiting for their interiors. */
        private final List<Object[]> byGroupLater = new ArrayList<>();

        /** Roots drawn whole from the large allowance ({@link Owner#whole}), by root. */
        private final Map<Long, Owner> wholeOwners = new HashMap<>();

        /**
         * Roots drawn whole from the large allowance that are known to fit an empty tile's building
         * pool: only their border-crossing further placements may be drawn whole here. A neighbour
         * sizing such a placement of any other root alone could split it, and its groups would then
         * be drawn twice.
         */
        private final java.util.Set<Long> wholeFitsAlone = new java.util.HashSet<>();

        /** The unique ID of the first (drawn) placement of a root drawn whole, by root. */
        private final Map<Long, Long> wholeFirst = new HashMap<>();

        /** Further placements of roots drawn whole from the large allowance, by root. */
        private final Map<Long, List<Queued>> wholeExtras = new LinkedHashMap<>();

        /** Roots drawn by group: their images are allotted after the tile's exteriors. */
        private final java.util.Set<Long> byGroupRoots = new java.util.HashSet<>();

        /**
         * Placements of roots drawn by group, by unique ID: their set doodads follow the owners.
         */
        private final Map<Long, Owner> owners = new HashMap<>();

        /**
         * The interior groups each drawn placement of a root drawn by group draws, by unique ID:
         * the set doodads inside them are placed.
         */
        private final Map<Long, java.util.BitSet> interiorsDrawn = new HashMap<>();

        Builder(
                WorldStreamScopes.TileAdmission admission,
                ModelReader modelReader,
                ImageDetail detail,
                long retainLimit,
                Interiors interiors,
                int compressedFormats) {
            this.compressedFormats = compressedFormats;
            this.interiors = interiors != Interiors.NONE;
            this.deferInteriors = interiors == Interiors.OPEN_WORLD;
            this.detail = detail;
            this.retainLimit = retainLimit;
            this.admission = admission;
            this.token = admission.cancellation();
            this.modelReader = modelReader;
        }

        TileObjects build() {
            var recorded = admission.recorded();
            if (recorded.obj0Id() != 0) {
                WorldPlacements.Metadata metadata;
                try (var input = admission.readRecorded(WorldStreamScopes.RecordedInput.OBJ0)) {
                    metadata = WorldPlacements.readObj0(input.bytes(), token::isCanceled);
                }
                setRanges = metadata.setRanges();
                setIds = metadata.setIds();
                long phase = WorldStageTimings.start();
                place(metadata.worldObjects());
                fitMaterialDraws();
                // After fitMaterialDraws, so a root it removed never spends interior reads,
                // portal rows or held copies.
                int exteriorReads = groupsRead;
                readInteriors();
                int interiorReads = groupsRead - exteriorReads;
                WorldStageTimings.stop("objects.phase.buildings", phase);
                // Admission ran largest first; the set keeps obj0 source order.
                placements.sort(java.util.Comparator.comparingInt(Placement::ordinal));
                // Buildings first, then MDDF doodads, then the drawn buildings' doodad sets, and
                // only then interiors (#280, #383): every exterior, doodad and set doodad draw
                // is spent before an interior group is admitted, so interiors use only what is
                // left. An interior-only root's own set doodads wait until its interiors decide
                // whether it draws at all.
                phase = WorldStageTimings.start();
                placeDoodads(metadata.doodads());
                WorldStageTimings.stop("objects.phase.doodads", phase);
                phase = WorldStageTimings.start();
                var deferred = new java.util.HashSet<Long>();
                placeSetDoodads(
                        building -> {
                            boolean pending =
                                    models.get(building.model()).meshes().isEmpty()
                                            && heldInteriors.containsKey(building.rootId());
                            if (pending) {
                                deferred.add(building.rootId());
                            }
                            return !pending;
                        });
                WorldStageTimings.stop("objects.phase.setDoodads", phase);
                // objects.phase.interiors also covers the second set-doodad pass: the doodad sets
                // of interior-only roots, deferred above, are placed here and are not counted
                // under objects.phase.setDoodads.
                // The exteriors of roots drawn by group (split and drawn whole from the large
                // allowance) come before every interior: their group reads, draws, slots, meshes,
                // images and surfaces come from what the tile's own buildings and doodads left.
                phase = WorldStageTimings.start();
                placeByGroup();
                WorldStageTimings.stop("objects.phase.byGroup", phase);
                phase = WorldStageTimings.start();
                placeInteriors();
                placeSetDoodads(building -> deferred.contains(building.rootId()));
                WorldStageTimings.stop("objects.phase.interiors", phase);
                // Then their interiors, the further placements of roots drawn whole and the
                // split shares (whose draws and slots the tile's interiors reserved), and the set
                // doodads of all of them.
                phase = WorldStageTimings.start();
                placeByGroupInteriors();
                placeWholeExtras();
                placeSplitShares();
                placeByGroupSetDoodads();
                WorldStageTimings.stop("objects.phase.byGroup", phase);
                // LOD0 group files this tile read (MAX_GROUPS), for the measurement probe; the
                // interior pass's share separately (#383).
                WorldStageTimings.value("objects.groups.readPerTile", groupsRead);
                WorldStageTimings.value("objects.groups.interiorReadPerTile", interiorReads);
                // Interior groups read only to size border-crossing roots, not in readPerTile.
                WorldStageTimings.value("objects.groups.sizingReadPerTile", sizingReads);
            }
            long phase = WorldStageTimings.start();
            allocateImages();
            WorldStageTimings.stop("objects.phase.imageBudget", phase);
            phase = WorldStageTimings.start();
            chainImages();
            WorldStageTimings.stop("objects.phase.mipChains", phase);
            phase = WorldStageTimings.start();
            sources.clear();
            unreadSources.clear();
            retainedSourceBytes = 0;
            dropUnusedImages();
            for (int m = 0; m < models.size(); m++) {
                if (models.get(m).portals() != null && !shareModels.contains(m)) {
                    count(Count.ROOT_PORTALS);
                }
            }
            var objects =
                    new TileObjects(
                            admission.mapId(),
                            recorded.x(),
                            recorded.y(),
                            models,
                            placements,
                            images,
                            counts,
                            modelFailure,
                            detail,
                            compressedFormats);
            objects.retainedSourceBytes = peakRetainedSourceBytes;
            WorldStageTimings.stop("objects.phase.assembly", phase);
            objects.levelDecodes = levelDecodes;
            return objects;
        }

        private void count(Count count) {
            counts[count.ordinal()]++;
        }

        /**
         * A building's draw cost under the opaque-only profile: its opaque ranges. A building with
         * only cutout or blended ranges, which that profile left empty, costs nothing here, so it
         * can never cap a later building that profile drew; fitMaterialDraws trims it instead.
         */
        private int admissionDraws(int model) {
            int opaque = 0;
            for (var mesh : models.get(model).meshes()) {
                for (var range : mesh.ranges()) {
                    opaque += kind(range.blend()) == OPAQUE ? 1 : 0;
                }
            }
            return opaque;
        }

        /**
         * First caps material-only placements, last first, beyond MAX_PLACEMENTS building
         * placements. Then, when the drawn buildings' full cost exceeds the draw budget, drops
         * cutout and blended ranges, last root first, until it fits. A building that keeps opaque
         * ranges keeps its placements: a building the opaque-only profile drew is never capped. A
         * building left with nothing returns to that profile's outcome: its placements and root
         * count as EMPTY, and its model and placements are removed. A material-only building whose
         * placements were all capped is removed too, its root counted EMPTY. Kept placements keep
         * their source order; kept models are renumbered in order.
         */
        private void fitMaterialDraws() {
            capMaterialOnlyPlacements();
            int[] uses = new int[models.size()];
            for (var placement : placements) {
                uses[placement.model()]++;
            }
            long total = 0;
            for (int m = 0; m < models.size(); m++) {
                total += (long) uses[m] * modelDraws(m);
            }
            boolean[] removed = new boolean[models.size()];
            for (int m = models.size() - 1; m >= 0; m--) {
                var model = models.get(m);
                if (model.doodad() || admissionDraws(m) == modelDraws(m) || awaitingInteriors(m)) {
                    continue;
                }
                boolean unusedMaterialOnly = uses[m] == 0 && admissionDraws(m) == 0;
                if (!unusedMaterialOnly && (uses[m] == 0 || total <= MAX_DRAWS)) {
                    continue;
                }
                var kept = new ArrayList<Mesh>();
                int dropped = 0;
                for (var mesh : model.meshes()) {
                    var opaque = new ArrayList<DrawRange>();
                    int end = 0;
                    for (var range : mesh.ranges()) {
                        if (kind(range.blend()) == OPAQUE) {
                            opaque.add(range);
                            end = Math.max(end, range.firstIndex() + range.indexCount());
                            continue;
                        }
                        dropped++;
                        count(Count.BATCH_CAPPED);
                        counts[Count.BATCH_DRAWN.ordinal()]--;
                        counts[
                                (kind(range.blend()) == CUTOUT
                                                ? Count.BATCH_CUTOUT
                                                : Count.BATCH_BLENDED)
                                        .ordinal()]--;
                    }
                    if (opaque.isEmpty()) {
                        counts[Count.GROUP_DRAWN.ordinal()]--;
                        count(Count.GROUP_EMPTY);
                        continue;
                    }
                    // Opaque ranges come first, so their indices are a prefix of the mesh's.
                    kept.add(
                            new Mesh(
                                    mesh.groupOrdinal,
                                    mesh.groupId,
                                    mesh.groupFlags,
                                    mesh.vertices,
                                    mesh.colours,
                                    Arrays.copyOf(mesh.indices, end),
                                    opaque));
                }
                if (kept.isEmpty()) {
                    removed[m] = true;
                    counts[Count.PLACEMENT_DRAWN.ordinal()] -= uses[m];
                    counts[Count.PLACEMENT_EMPTY.ordinal()] += uses[m];
                    counts[Count.ROOT_DRAWN.ordinal()]--;
                    count(Count.ROOT_EMPTY);
                    rootStates.put(model.rootId(), RootState.EMPTY);
                    rootDoodads.remove(model.rootId());
                }
                models.set(m, new Model(model.rootId(), false, kept, model.portals()));
                total -= (long) uses[m] * dropped;
            }
            draws = (int) total;
            removeModels(removed);
        }

        /** A building model with no meshes yet whose interiors the interior pass still holds. */
        private boolean awaitingInteriors(int model) {
            var value = models.get(model);
            return !value.doodad()
                    && value.meshes().isEmpty()
                    && heldInteriors.containsKey(value.rootId());
        }

        /** Caps the last material-only placements while building placements exceed the slots. */
        private void capMaterialOnlyPlacements() {
            int excess = placements.size() - MAX_PLACEMENTS;
            if (excess <= 0) {
                return;
            }
            var capped = new boolean[placements.size()];
            for (int i = placements.size() - 1; i >= 0 && excess > 0; i--) {
                // An interior-only root waiting for the interior pass is decided there: it draws
                // only if its placements find free slots then.
                if (admissionDraws(placements.get(i).model()) == 0
                        && !awaitingInteriors(placements.get(i).model())) {
                    capped[i] = true;
                    excess--;
                    counts[Count.PLACEMENT_DRAWN.ordinal()]--;
                    count(Count.PLACEMENT_CAPPED);
                }
            }
            var kept = new ArrayList<Placement>(MAX_PLACEMENTS);
            for (int i = 0; i < placements.size(); i++) {
                if (!capped[i]) {
                    kept.add(placements.get(i));
                }
            }
            placements.clear();
            placements.addAll(kept);
        }

        /** Removes the flagged building models and their placements, renumbering the rest. */
        private void removeModels(boolean[] removed) {
            int[] renumbered = new int[removed.length];
            var keptModels = new ArrayList<Model>();
            for (int m = 0; m < removed.length; m++) {
                renumbered[m] = removed[m] ? -1 : keptModels.size();
                if (!removed[m]) {
                    keptModels.add(models.get(m));
                }
            }
            if (keptModels.size() == models.size()) {
                return;
            }
            var keptPlacements = new ArrayList<Placement>();
            for (var p : placements) {
                int model = renumbered[p.model()];
                if (model < 0) {
                    continue;
                }
                keptPlacements.add(
                        new Placement(
                                p.kind(),
                                p.ordinal(),
                                p.uniqueId(),
                                p.rootId(),
                                model,
                                p.flags(),
                                p.doodadSet(),
                                p.nameSet(),
                                p.x(),
                                p.y(),
                                p.z(),
                                p.scale(),
                                p.cos(),
                                p.sin(),
                                p.rotation()));
            }
            models.clear();
            models.addAll(keptModels);
            placements.clear();
            placements.addAll(keptPlacements);
            modelSlots.clear();
            for (int m = 0; m < models.size(); m++) {
                if (!models.get(m).doodad()) {
                    modelSlots.put(models.get(m).rootId(), m);
                }
            }
            doodadSlots.replaceAll((id, slot) -> renumbered[slot]);
        }

        /**
         * Drops decoded images that no kept range draws (those of ranges fitMaterialDraws dropped),
         * so they are never uploaded, and renumbers the ranges' image slots.
         */
        private void dropUnusedImages() {
            boolean[] used = new boolean[images.size()];
            for (var model : models) {
                for (var mesh : model.meshes()) {
                    for (var range : mesh.ranges()) {
                        used[range.image()] = true;
                    }
                }
            }
            int[] slot = new int[images.size()];
            var kept = new ArrayList<Image>();
            for (int i = 0; i < used.length; i++) {
                slot[i] = used[i] ? kept.size() : -1;
                if (used[i]) {
                    kept.add(images.get(i));
                }
            }
            if (kept.size() == images.size()) {
                return;
            }
            for (int m = 0; m < models.size(); m++) {
                var model = models.get(m);
                var meshes = new ArrayList<Mesh>();
                for (var mesh : model.meshes()) {
                    var ranges = new ArrayList<DrawRange>();
                    for (var r : mesh.ranges()) {
                        ranges.add(
                                new DrawRange(
                                        r.firstIndex(),
                                        r.indexCount(),
                                        slot[r.image()],
                                        r.material(),
                                        r.blend()));
                    }
                    meshes.add(
                            new Mesh(
                                    mesh.groupOrdinal,
                                    mesh.groupId,
                                    mesh.groupFlags,
                                    mesh.vertices,
                                    mesh.colours,
                                    mesh.indices,
                                    ranges));
                }
                models.set(m, new Model(model.rootId(), model.doodad(), meshes, model.portals()));
            }
            images.clear();
            images.addAll(kept);
        }

        private int modelDraws(int model) {
            int sum = 0;
            for (var mesh : models.get(model).meshes()) {
                sum += mesh.ranges().size();
            }
            return sum;
        }

        private void place(List<WorldPlacements.WorldObject> records) {
            // Identical duplicate unique IDs share one placement; conflicting ones are all omitted.
            var byUid = new LinkedHashMap<Long, List<WorldPlacements.WorldObject>>();
            for (var record : records) {
                token.check();
                byUid.computeIfAbsent(record.uniqueId(), ignored -> new ArrayList<>()).add(record);
            }
            var conflicting = new HashMap<Long, Boolean>();
            for (var entry : byUid.entrySet()) {
                var first = entry.getValue().getFirst();
                boolean conflict = false;
                for (var other : entry.getValue()) {
                    conflict |= !sameDeclaration(first, other);
                }
                conflicting.put(entry.getKey(), conflict);
            }
            // Larger buildings are admitted first, so caps and image budgets never favour a small
            // building only because obj0 records it earlier. Equal sizes keep obj0 order.
            var ordered = new ArrayList<>(records);
            ordered.sort(
                    java.util.Comparator.comparingDouble(
                            (WorldPlacements.WorldObject r) -> -recordedSize(r)));
            // Roots with a border-crossing placement this tile could place: qualified, neither
            // conflicting nor a duplicate. No slot count is cut here: which records take a
            // building slot is only known as they are admitted.
            for (var record : ordered) {
                if (conflicting.get(record.uniqueId())
                        || byUid.get(record.uniqueId()).getFirst() != record
                        || qualify(record) == null) {
                    continue;
                }
                if (Owner.crosses(record)) {
                    crossingRoots.add(record.reference().value());
                }
            }
            for (var record : ordered) {
                token.check();
                var group = byUid.get(record.uniqueId());
                if (conflicting.get(record.uniqueId())) {
                    count(Count.PLACEMENT_CONFLICTING);
                    continue;
                }
                if (group.getFirst() != record) {
                    count(Count.PLACEMENT_SHARED_DUPLICATE);
                    continue;
                }
                var transform = qualify(record);
                if (transform == null) {
                    count(Count.PLACEMENT_UNSUPPORTED);
                    continue;
                }
                long rootId = record.reference().value();
                // As in the opaque-only profile, only placements with opaque draws fill the
                // slots; material-only ones are trimmed in fitMaterialDraws if they overflow.
                if (opaquePlacements >= MAX_PLACEMENTS) {
                    count(Count.PLACEMENT_CAPPED);
                    continue;
                }
                var owner =
                        Owner.of(
                                record,
                                transform.originX(),
                                transform.originY(),
                                transform.scale(),
                                buildingRotation(record, transform.cos(), transform.sin()),
                                admission.recorded().x(),
                                admission.recorded().y());
                var state = rootStates.get(rootId);
                if (state == null) {
                    if (rootsRead >= MAX_ROOTS) {
                        count(Count.PLACEMENT_CAPPED);
                        continue;
                    }
                    try {
                        // A root is decided once per tile, by its largest placement.
                        state = readRoot(rootId, owner);
                    } finally {
                        // A root that stops early (capped or failed) leaves group hints behind:
                        // withdraw them so none of its groups is fetched after all (#371).
                        admission.cancelPrefetch();
                    }
                    rootStates.put(rootId, state);
                }
                switch (state) {
                    case FAILED -> count(Count.PLACEMENT_FAILED_ROOT);
                    case CAPPED -> count(Count.PLACEMENT_CAPPED);
                    case EMPTY -> count(Count.PLACEMENT_EMPTY);
                    case OTHER_TILE -> {
                        if (owner == null) {
                            // Another placement of the root, inside one tile: the root was decided
                            // by its largest placement, and this one has no owner rule.
                            count(Count.PLACEMENT_CAPPED);
                        } else {
                            count(Count.PLACEMENT_OTHER_TILE);
                            doodadsOnly(record, transform, rootId, owner);
                        }
                    }
                    case BY_GROUP -> {
                        // Its groups are this placement's; another placement of a split root is
                        // not drawn here. A root drawn whole from the large allowance keeps its
                        // rule, and its other placements draw after it.
                        var whole = wholeOwners.get(rootId);
                        var queued =
                                whole == null
                                        ? new Queued(record, transform, owner, false)
                                        : new Queued(record, transform, whole, true);
                        if (byGroupQueue.putIfAbsent(rootId, queued) != null && whole != null) {
                            if (owner != null && !wholeFitsAlone.contains(rootId)) {
                                // A border-crossing placement of a root not known to fit an
                                // empty tile: a neighbour listing it may draw it by group, so it
                                // draws here only the share its owner rule gives this tile.
                                splitShares.add(new Queued(record, transform, owner, false));
                            } else {
                                // Drawn whole: its other placements draw the same model, placed
                                // once it is decided (placeWholeExtras).
                                wholeExtras
                                        .computeIfAbsent(rootId, ignored -> new ArrayList<>())
                                        .add(queued);
                            }
                        } else if (byGroupQueue.get(rootId) == queued) {
                            if (whole != null) {
                                wholeFirst.put(rootId, record.uniqueId());
                            }
                        } else {
                            count(Count.PLACEMENT_CAPPED);
                        }
                    }
                    case DRAWN -> {
                        if (owner != null && splitsAlone.contains(rootId)) {
                            // A border-crossing placement of a root decided whole by one inside
                            // the tile: a neighbour sizing it alone splits it, so this tile draws
                            // only the share the owner rule gives it (placeSplitShares).
                            splitShares.add(new Queued(record, transform, owner, false));
                        } else {
                            placeBuilding(record, transform, rootId);
                        }
                    }
                }
            }
        }

        /** The building placement record of a qualified MODF entry for model {@code model}. */
        private static Placement buildingPlacement(
                WorldPlacements.WorldObject record,
                WorldBasicWmo.Transform transform,
                long rootId,
                int model) {
            return new Placement(
                    Kind.BUILDING,
                    record.ordinal(),
                    record.uniqueId(),
                    rootId,
                    model,
                    record.flags(),
                    record.doodadSet(),
                    record.nameSet(),
                    transform.originX(),
                    transform.originY(),
                    transform.originZ(),
                    transform.scale(),
                    transform.cos(),
                    transform.sin(),
                    buildingRotation(record, transform.cos(), transform.sin()));
        }

        /**
         * A placement of a root drawn by group that draws no group here (another tile's, empty or
         * capped): it still places the set doodads standing in this tile, which no other tile
         * places. It takes no building slot or draw.
         */
        private void doodadsOnly(
                WorldPlacements.WorldObject record,
                WorldBasicWmo.Transform transform,
                long rootId,
                Owner owner) {
            owners.put(record.uniqueId(), owner);
            byGroupBuildings.add(buildingPlacement(record, transform, rootId, -1));
        }

        /**
         * Places a drawn root's building when its draws fit. The opaque-only profile's cost
         * decides; cutout and blended ranges are dropped later (fitMaterialDraws) if they overflow
         * the budget.
         */
        private void placeBuilding(
                WorldPlacements.WorldObject record,
                WorldBasicWmo.Transform transform,
                long rootId) {
            int model = modelSlots.get(rootId), cost = admissionDraws(model);
            if (cost > MAX_DRAWS - draws) {
                count(Count.PLACEMENT_CAPPED);
                return;
            }
            draws += cost;
            if (cost > 0) {
                opaquePlacements++;
            }
            count(Count.PLACEMENT_DRAWN);
            placements.add(buildingPlacement(record, transform, rootId, model));
        }

        /**
         * The exteriors of roots drawn by group ({@link Owner}) in admission order, then of roots
         * drawn whole from the large allowance ({@link Owner#whole}) in admission order, after the
         * tile's own buildings and doodads and before any interior: the owned exterior groups the
         * sizing pass did not read are read now from the group reads left, and every owned exterior
         * is admitted against the large and interior allowances together and resolved, its opaque
         * images in their own slots. Placement slots, meshes, draws and the cutout and blended
         * budget come only from what the tile's buildings and doodads left, so a root drawn by
         * group never displaces any of them, and no interior ever displaces it. Every limit is
         * checked before anything is resolved; when its cutout and blended ranges do not fit the
         * draws left, only its opaque ranges are drawn. Its owned interior groups follow after the
         * tile's own interiors ({@link #placeByGroupInteriors}), from what they left, with its
         * portal set. A split root that does not fit, or keeps nothing, still places the set
         * doodads standing in this tile; a root drawn whole that does not fit is capped as a tile
         * root, without them.
         */
        private void placeByGroup() {
            // Every split root's exteriors first, then the exteriors of roots drawn whole from the
            // large allowance (Owner#whole), then all of their interiors in the same order (as
            // the tile's own roots): no interior takes what an exterior could use, and a root
            // drawn whole only takes what every split root's exteriors left.
            var later = new ArrayList<Object[]>();
            var order = new ArrayList<Map.Entry<Long, Queued>>(byGroupQueue.size());
            for (boolean fallback : new boolean[] {false, true}) {
                for (var entry : byGroupQueue.entrySet()) {
                    if (entry.getValue().fallback() == fallback) {
                        order.add(entry);
                    }
                }
            }
            for (var entry : order) {
                token.check();
                long rootId = entry.getKey();
                var queued = entry.getValue();
                var read = byGroupReads.remove(rootId);
                var ready = read.ready();
                var local = read.local();
                int unread = 0;
                for (boolean u : read.unread()) {
                    unread += u ? 1 : 0;
                }
                long buildings = placements.stream().filter(p -> p.kind() == Kind.BUILDING).count();
                if (opaquePlacements >= MAX_PLACEMENTS
                        || buildings >= MAX_PLACEMENTS
                        || unread > MAX_GROUPS - groupsRead) {
                    capByGroup(rootId, queued, read, local);
                    continue;
                }
                var root = read.root();
                for (int g = 0; g < ready.length; g++) {
                    if (read.unread()[g]) {
                        admission.prefetch(root.gfid(g), WorldStreamScopes.DerivedKind.WMO_GROUP);
                    }
                }
                var pending = new ArrayList<PendingMesh>();
                var budget = new RootBudget(Allowance.LARGE);
                boolean over = false;
                try {
                    var headers = new WmoFormats.GroupHeader[ready.length];
                    for (int g = 0; g < ready.length && !over; g++) {
                        var mesh = ready[g];
                        if (read.unread()[g]) {
                            token.check();
                            groupsRead++;
                            mesh = readGroup(g, root.gfid(g), root, local, headers);
                            if (mesh != null && (mesh.flags() & INTERIOR) != 0) {
                                local[interiorNotDrawn().ordinal()]++;
                                mesh = null;
                            }
                        }
                        if (mesh == null) {
                            continue;
                        }
                        switch (budget.admit(mesh, local)) {
                            case ADMITTED -> pending.add(mesh);
                            case DROPPED -> {}
                            case OVER -> over = true;
                        }
                    }
                } finally {
                    admission.cancelPrefetch();
                }
                // Every draw already placed, cutout and blended ones of interiors included.
                int meshes = 0;
                long drawn = 0;
                for (var model : models) {
                    meshes += model.doodad() ? 0 : model.meshes().size();
                }
                for (var placement : placements) {
                    drawn += modelDraws(placement.model());
                }
                long candidates = 0, opaqueCandidates = 0;
                for (var mesh : pending) {
                    for (var range : mesh.ranges()) {
                        candidates++;
                        opaqueCandidates += kind(range.blend()) == OPAQUE ? 1 : 0;
                    }
                }
                if (over
                        || pending.size() > MAX_MESHES - meshes
                        || opaqueCandidates > MAX_DRAWS - drawn) {
                    capByGroup(rootId, queued, read, local);
                    continue;
                }
                // Only opaque ranges when every candidate range would not fit the draws left.
                skipMaterials = candidates > MAX_DRAWS - drawn;
                charge = Charge.LARGE;
                var built = new ArrayList<Mesh>(pending.size());
                try {
                    prefetchBuildingImages(pending);
                    for (var mesh : pending) {
                        token.check();
                        var mapped = resolveBuilding(mesh, local);
                        if (mapped == null) {
                            local[Count.GROUP_EMPTY.ordinal()]++;
                            continue;
                        }
                        countMaterials(mapped, local);
                        local[Count.GROUP_DRAWN.ordinal()]++;
                        built.add(mapped);
                    }
                } finally {
                    charge = Charge.BUILDING;
                    skipMaterials = false;
                }
                for (int i = 0; i < counts.length; i++) {
                    counts[i] += local[i];
                }
                if (built.isEmpty()) {
                    if (interiorCandidates(read) > 0) {
                        later.add(new Object[] {rootId, queued, read, -1});
                    } else {
                        emptyByGroup(rootId, queued);
                    }
                    continue;
                }
                later.add(new Object[] {rootId, queued, read, addByGroup(rootId, queued, built)});
            }
            byGroupQueue.clear();
            byGroupLater.clear();
            byGroupLater.addAll(later);
        }

        /**
         * The owned interiors of roots drawn by group ({@link #byGroupInteriors}), after their
         * exteriors ({@link #placeByGroup}) and after the tile's own interiors, in the same order.
         * Model ordinals are looked up again: the tile's interior pass may have removed a model.
         */
        private void placeByGroupInteriors() {
            var later = new ArrayList<>(byGroupLater);
            byGroupLater.clear();
            for (var entry : later) {
                token.check();
                long rootId = (Long) entry[0];
                var queued = (Queued) entry[1];
                var read = (ByGroup) entry[2];
                int model = (Integer) entry[3] < 0 ? -1 : modelSlots.get(rootId);
                int[] local = new int[counts.length];
                long buildings = placements.stream().filter(p -> p.kind() == Kind.BUILDING).count();
                var reserve = laterExteriors();
                if (model < 0
                        && (opaquePlacements >= MAX_PLACEMENTS - reserve.slots()
                                || buildings >= MAX_PLACEMENTS - reserve.slots())) {
                    // An interior-only share needs a free building slot, as the tile's own.
                    local[Count.GROUP_INTERIOR_CAPPED.ordinal()] += interiorCandidates(read);
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] += local[i];
                    }
                    emptyByGroup(rootId, queued);
                    continue;
                }
                int meshes = 0;
                long drawn = 0;
                for (var m : models) {
                    meshes += m.doodad() ? 0 : m.meshes().size();
                }
                for (var placement : placements) {
                    drawn += modelDraws(placement.model());
                }
                var interiors = new ArrayList<Mesh>();
                // The shares' and further placements' exteriors placed after these interiors are
                // held back from them: draws, mesh rows and the large allowance's geometry.
                largeVertices += reserve.vertices();
                largeIndices += reserve.indices();
                largeRanges += reserve.ranges();
                WmoPortalSet portals;
                try {
                    portals =
                            byGroupInteriors(
                                    read,
                                    interiors,
                                    meshes + reserve.meshes(),
                                    drawn + reserve.draws(),
                                    1 + extrasOf(rootId),
                                    local);
                } finally {
                    largeVertices -= reserve.vertices();
                    largeIndices -= reserve.indices();
                    largeRanges -= reserve.ranges();
                }
                for (int i = 0; i < counts.length; i++) {
                    counts[i] += local[i];
                }
                if (interiors.isEmpty()) {
                    if (model < 0) {
                        emptyByGroup(rootId, queued);
                    }
                    continue;
                }
                // With its interiors, the root's portal set over all of its groups: each tile that
                // draws some of them walks the whole set and skips its own unseen interiors.
                var meshesOf = new ArrayList<Mesh>();
                if (model < 0) {
                    model = addByGroup(rootId, queued, interiors);
                } else {
                    meshesOf.addAll(models.get(model).meshes());
                }
                meshesOf.addAll(interiors);
                models.set(model, new Model(rootId, false, meshesOf, portals));
                interiorsDrawn.put(queued.record().uniqueId(), drawnGroups(interiors, 0));
                long total = 0;
                for (var placement : placements) {
                    total += modelDraws(placement.model());
                }
                draws = (int) total;
            }
        }

        /**
         * The further placements of roots drawn whole from the large allowance, in admission order,
         * after every root drawn by group and its interiors: each draws the root's model when a
         * building slot and its draws are left, as a tile root's further placement, and places its
         * own set doodads; otherwise, or when the root was not drawn, it is capped.
         */
        private void placeWholeExtras() {
            for (var entry : wholeExtras.entrySet()) {
                long rootId = entry.getKey();
                Integer slot =
                        rootStates.get(rootId) == RootState.DRAWN && byGroupRoots.contains(rootId)
                                ? modelSlots.get(rootId)
                                : null;
                for (var queued : entry.getValue()) {
                    token.check();
                    long buildings =
                            placements.stream().filter(p -> p.kind() == Kind.BUILDING).count();
                    int cost = slot == null ? 0 : modelDraws(slot);
                    if (slot == null
                            || opaquePlacements >= MAX_PLACEMENTS
                            || buildings >= MAX_PLACEMENTS
                            || cost > MAX_DRAWS - draws) {
                        count(Count.PLACEMENT_CAPPED);
                        continue;
                    }
                    draws += cost;
                    opaquePlacements++;
                    count(Count.PLACEMENT_DRAWN);
                    var placement =
                            buildingPlacement(queued.record(), queued.transform(), rootId, slot);
                    int at = 0;
                    while (at < placements.size()
                            && placements.get(at).kind() == Kind.BUILDING
                            && placements.get(at).ordinal() < placement.ordinal()) {
                        at++;
                    }
                    placements.add(at, placement);
                    owners.put(placement.uniqueId(), queued.owner());
                    byGroupBuildings.add(placement);
                    var drawnInteriors = interiorsDrawn.get(wholeFirst.get(rootId));
                    if (drawnInteriors != null) {
                        interiorsDrawn.put(placement.uniqueId(), drawnInteriors);
                    }
                }
            }
            wholeExtras.clear();
        }

        /**
         * The placements of {@link #splitShares}, after every root drawn by group: the groups of
         * the root's drawn model whose owner is this tile under the placement's own rule, as a root
         * drawn by group draws them, so every tile listing the placement draws its own share
         * whichever placement decided the root there, and no group is drawn by two tiles. The share
         * takes no reads (its meshes are the drawn model's) but its own model, placement slot,
         * draws and geometry from the large allowance; its set doodads follow the owner rule
         * ({@link #placeByGroupSetDoodads}). A share with no group here, or one that does not fit,
         * still places the set doodads standing in this tile, as a split root's.
         */
        private void placeSplitShares() {
            for (var queued : splitShares) {
                token.check();
                var record = queued.record();
                long rootId = record.reference().value();
                var root = byGroupRootFacts.get(rootId);
                if (root == null) {
                    count(Count.PLACEMENT_CAPPED); // never recorded: no rule to place doodads by
                    continue;
                }
                var owned = queued.owner().owned(root);
                boolean any = false;
                for (boolean o : owned) {
                    any |= o;
                }
                if (!any) {
                    count(Count.PLACEMENT_OTHER_TILE);
                    doodadsOnly(record, queued.transform(), rootId, queued.owner());
                    continue;
                }
                Integer slot =
                        rootStates.get(rootId) == RootState.DRAWN ? modelSlots.get(rootId) : null;
                var whole = slot == null ? null : models.get(slot);
                var portals = whole == null ? null : whole.portals();
                if (portals != null && !portalsFit(portals)) {
                    count(Count.ROOT_PORTALS_DROPPED);
                    portals = null;
                }
                var share = new ArrayList<Mesh>();
                for (var mesh : whole == null ? List.<Mesh>of() : whole.meshes()) {
                    int g = mesh.groupOrdinal();
                    if (g < 0 || g >= owned.length || !owned[g]) {
                        continue;
                    }
                    if (portals == null && mogiInterior(root, g)) {
                        continue; // unculled interiors would cost every frame (as split roots)
                    }
                    share.add(mesh);
                }
                // Its exteriors were held for it before the interiors were admitted; its
                // interiors were not, so a share that does not fit with them is placed with its
                // exteriors only, rather than leave its exteriors undrawn.
                int dropped = 0;
                if (!shareFits(share)) {
                    int before = share.size();
                    share.removeIf(mesh -> mogiInterior(root, mesh.groupOrdinal()));
                    dropped = before - share.size();
                }
                long vertices = 0, indices = 0, ranges = 0;
                for (var mesh : share) {
                    vertices += mesh.vertexCount();
                    indices += mesh.indices().remaining();
                    ranges += mesh.ranges().size();
                }
                if (!shareFits(share)) {
                    // PLACEMENT_CAPPED alone records it (its groups are the root's, counted once
                    // with it), and the set doodads standing here are still placed, as for a
                    // capped split root.
                    count(Count.PLACEMENT_CAPPED);
                    doodadsOnly(record, queued.transform(), rootId, queued.owner());
                    continue;
                }
                if (portals != null) {
                    chargePortals(portals);
                }
                largeVertices += vertices;
                largeIndices += indices;
                largeRanges += ranges;
                draws += (int) ranges;
                opaquePlacements++;
                int model = models.size();
                var shareModel = new Model(rootId, false, share, portals);
                models.add(shareModel);
                shareModels.add(model);
                count(Count.PLACEMENT_DRAWN);
                count(Count.PLACEMENT_SPLIT_SHARE);
                // Only a share placed without its interiors leaves them out; a capped one is
                // PLACEMENT_CAPPED alone.
                counts[Count.SHARE_INTERIOR_DROPPED.ordinal()] += dropped;
                var placement = buildingPlacement(record, queued.transform(), rootId, model);
                int at = 0;
                while (at < placements.size()
                        && placements.get(at).kind() == Kind.BUILDING
                        && placements.get(at).ordinal() < placement.ordinal()) {
                    at++;
                }
                placements.add(at, placement);
                owners.put(placement.uniqueId(), queued.owner());
                byGroupBuildings.add(placement);
                interiorsDrawn.put(placement.uniqueId(), drawnGroups(share, 0));
            }
            splitShares.clear();
        }

        /**
         * Whether a share of {@code share} meshes fits what the tile has left: a building model
         * row, a placement slot, mesh rows, draws and the large allowance's geometry.
         */
        private boolean shareFits(List<Mesh> share) {
            long vertices = 0, indices = 0, ranges = 0;
            for (var mesh : share) {
                vertices += mesh.vertexCount();
                indices += mesh.indices().remaining();
                ranges += mesh.ranges().size();
            }
            int meshes = 0, buildingModels = 0;
            for (var m : models) {
                meshes += m.doodad() ? 0 : m.meshes().size();
                buildingModels += m.doodad() ? 0 : 1;
            }
            long buildings = placements.stream().filter(p -> p.kind() == Kind.BUILDING).count();
            // The share is a model of its own, within the tile's building model rows.
            return !share.isEmpty()
                    && buildingModels < MAX_ROOTS
                    && opaquePlacements < MAX_PLACEMENTS
                    && buildings < MAX_PLACEMENTS
                    && share.size() <= MAX_MESHES - meshes
                    && ranges <= MAX_DRAWS - draws
                    && vertices <= MAX_LARGE_VERTICES + MAX_LARGE_INTERIOR_VERTICES - largeVertices
                    && indices <= MAX_LARGE_INDICES + MAX_LARGE_INTERIOR_INDICES - largeIndices
                    && ranges <= MAX_LARGE_RANGES + MAX_LARGE_INTERIOR_RANGES - largeRanges;
        }

        /** A root drawn by group that draws nothing here: its local set doodads are kept. */
        private void emptyByGroup(long rootId, Queued queued) {
            rootStates.put(rootId, RootState.EMPTY);
            count(Count.ROOT_EMPTY);
            count(Count.PLACEMENT_EMPTY);
            if (!queued.fallback()) {
                doodadsOnly(queued.record(), queued.transform(), rootId, queued.owner());
            }
        }

        /** Owned interior groups of a root drawn by group still to be drawn or counted. */
        private static int interiorCandidates(ByGroup read) {
            int candidates = 0;
            for (int g = 0; g < read.unreadInterior().length; g++) {
                candidates += read.unreadInterior()[g] || read.readyInterior()[g] != null ? 1 : 0;
            }
            return candidates;
        }

        /** Adds a root drawn by group with {@code built} and places it; its model ordinal. */
        private int addByGroup(long rootId, Queued queued, List<Mesh> built) {
            int model = models.size();
            models.add(new Model(rootId, false, built, null));
            modelSlots.put(rootId, model);
            byGroupRoots.add(rootId);
            rootStates.put(rootId, RootState.DRAWN);
            count(Count.ROOT_DRAWN);
            count(queued.fallback() ? Count.ROOT_WHOLE_LARGE : Count.ROOT_BY_GROUP);
            count(Count.PLACEMENT_DRAWN);
            opaquePlacements++;
            // The tile's true draw total, so the set doodads after it stay within MAX_DRAWS.
            long total = 0;
            for (var placement : placements) {
                total += modelDraws(placement.model());
            }
            draws = (int) (total + modelDraws(model));
            var placement = buildingPlacement(queued.record(), queued.transform(), rootId, model);
            // Buildings stay first, in obj0 order, ahead of every doodad.
            int at = 0;
            while (at < placements.size()
                    && placements.get(at).kind() == Kind.BUILDING
                    && placements.get(at).ordinal() < placement.ordinal()) {
                at++;
            }
            placements.add(at, placement);
            owners.put(placement.uniqueId(), queued.owner());
            byGroupBuildings.add(placement);
            return model;
        }

        /**
         * A root drawn by group that does not fit here: capped, its local set doodads kept. Its
         * LOD0 groups were already classified for this tile ({@link #readByGroup}), so each still
         * lands in one group bucket, as on the tiles drawing the others: a group settled by then
         * keeps its bucket (another tile's, an interior or unsupported group, a failed or empty
         * read), an owned interior still to be drawn is GROUP_INTERIOR_CAPPED and an owned exterior
         * GROUP_CAPPED. Nothing else it read or admitted is counted, as for a capped root.
         */
        private void capByGroup(long rootId, Queued queued, ByGroup read, int[] local) {
            int settled = 0;
            for (var bucket : GROUP_OUTCOMES) {
                counts[bucket.ordinal()] += local[bucket.ordinal()];
                settled += local[bucket.ordinal()];
            }
            int interiors = interiorCandidates(read);
            counts[Count.GROUP_INTERIOR_CAPPED.ordinal()] += interiors;
            counts[Count.GROUP_CAPPED.ordinal()] +=
                    Math.max(0, read.ready().length - settled - interiors);
            rootStates.put(rootId, RootState.CAPPED);
            count(Count.ROOT_CAPPED);
            count(Count.PLACEMENT_CAPPED);
            if (!queued.fallback()) {
                // A root drawn whole places no set doodad without its building, as a capped
                // tile root.
                doodadsOnly(queued.record(), queued.transform(), rootId, queued.owner());
            }
        }

        /**
         * The owned interior groups of a root drawn by group ({@link #readByGroup}), after its
         * exteriors and from what they left: they need the root's portal set, built from the root
         * alone ({@link WmoPortalSet#ofGroupInfo}) and carried within the tile's portal tables;
         * without one none is read (GROUP_INTERIOR_NO_PORTALS) and the set is counted
         * ROOT_PORTALS_DROPPED. Each group in order is read from the tile's group reads left, and
         * admitted while its candidate ranges fit the draws left, a mesh is left and its geometry
         * fits what every exterior left of the large and interior allowances together ({@link
         * Allowance#LARGE}), its images in their image slots; otherwise it is GROUP_INTERIOR_CAPPED
         * and later ones may still fit. Admitted meshes are added to {@code built}. Returns the set
         * when an interior was admitted (its tables charged), else null.
         */
        private WmoPortalSet byGroupInteriors(
                ByGroup read,
                List<Mesh> built,
                int meshes,
                long drawn,
                int placementsDrawing,
                int[] local) {
            var root = read.root();
            int candidates = interiorCandidates(read);
            if (candidates == 0) {
                return null;
            }
            WmoPortalSet set = null;
            if (root.portals().declared()) {
                try {
                    set = WmoPortalSet.ofGroupInfo(root);
                } catch (IllegalArgumentException refused) {
                    set = null; // not retained, ranges not recoverable, or past the file's bound
                }
            }
            if (set == null || !portalsFit(set)) {
                if (root.portals().declared()) {
                    count(Count.ROOT_PORTALS_DROPPED);
                }
                local[interiorNotDrawn().ordinal()] += candidates;
                return null;
            }
            int before = built.size();
            long used = drawn;
            for (var mesh : built) {
                used += mesh.ranges().size();
            }
            var headers = new WmoFormats.GroupHeader[read.unreadInterior().length];
            interiorPass = true;
            charge = Charge.LARGE;
            try {
                for (int g = 0; g < read.unreadInterior().length; g++) {
                    if (read.unreadInterior()[g]) {
                        admission.prefetch(root.gfid(g), WorldStreamScopes.DerivedKind.WMO_GROUP);
                    }
                }
                for (int g = 0; g < read.unreadInterior().length; g++) {
                    var mesh = read.readyInterior()[g];
                    if (read.unreadInterior()[g]) {
                        token.check();
                        if (groupsRead >= MAX_GROUPS) {
                            local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                            continue;
                        }
                        groupsRead++;
                        mesh = readGroup(g, root.gfid(g), root, local, headers);
                    }
                    if (mesh == null) {
                        continue; // counted by readGroup
                    }
                    // Whatever its own MOGP flags say, a MOGI-interior group is an interior here,
                    // as the set says.
                    if (meshes + built.size() >= MAX_MESHES
                            || (long) placementsDrawing * mesh.ranges().size() > MAX_DRAWS - used) {
                        local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        continue;
                    }
                    var admitted = new RootBudget(Allowance.LARGE).admit(mesh, local);
                    if (admitted == Admission.DROPPED) {
                        continue; // counted by admit: BATCH_CAPPED, GROUP_EMPTY
                    }
                    if (admitted == Admission.OVER) {
                        local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        continue;
                    }
                    var mapped = resolveBuilding(mesh, local);
                    if (mapped == null) {
                        local[Count.GROUP_EMPTY.ordinal()]++;
                        continue;
                    }
                    countMaterials(mapped, local);
                    local[Count.GROUP_DRAWN.ordinal()]++;
                    used += (long) placementsDrawing * mapped.ranges().size();
                    built.add(mapped);
                }
            } finally {
                interiorPass = false;
                charge = Charge.BUILDING;
                admission.cancelPrefetch();
            }
            if (built.size() == before) {
                return null;
            }
            chargePortals(set);
            return set;
        }

        /** The group ordinals of {@code built} from {@code from} on: the interiors drawn. */
        private static java.util.BitSet drawnGroups(List<Mesh> built, int from) {
            var groups = new java.util.BitSet();
            for (int i = from; i < built.size(); i++) {
                groups.set(built.get(i).groupOrdinal());
            }
            return groups;
        }

        /** How an owned interior group of a root drawn by group is counted. */
        private Count interiorNotDrawn() {
            return interiors ? Count.GROUP_INTERIOR_NO_PORTALS : Count.GROUP_INTERIOR;
        }

        /**
         * The set doodads of every placement of a root drawn by group, after the tile's own set
         * doodads: drawn buildings first, then those that draw no group here, each in admission
         * order. Only the doodads this tile owns are placed ({@link ByGroupFilter}): those standing
         * in it, outside every interior group's box, and those inside an interior group it draws.
         */
        private void placeByGroupSetDoodads() {
            var ordered = new ArrayList<Placement>(byGroupBuildings.size());
            for (var building : byGroupBuildings) {
                if (building.model() >= 0) {
                    ordered.add(building);
                }
            }
            for (var building : byGroupBuildings) {
                if (building.model() < 0) {
                    ordered.add(building);
                }
            }
            var filters = new ArrayList<ByGroupFilter>(ordered.size());
            for (var building : ordered) {
                token.check();
                var root = byGroupRootFacts.get(building.rootId());
                var filter =
                        new ByGroupFilter(
                                owners.get(building.uniqueId()),
                                root,
                                building.model() < 0
                                        ? null
                                        : interiorsDrawn.get(building.uniqueId()));
                placeSetsOf(building, root.doodads(), filter);
                filters.add(filter);
            }
            // Then the doodads inside the interiors drawn: they never take a model or a draw an
            // outdoor set doodad could have had.
            for (int i = 0; i < ordered.size(); i++) {
                var building = ordered.get(i);
                var filter = filters.get(i);
                filter.deferring = false;
                var declared = byGroupRootFacts.get(building.rootId()).doodads();
                for (int[] entry : filter.deferred) {
                    token.check();
                    setDoodad(building, declared, entry[0], entry[1], filter);
                }
            }
            placeQueued();
            byGroupBuildings.clear();
        }

        /**
         * Which set doodads of a root drawn by group a tile places. A doodad standing inside one of
         * the root's interior groups' MOGI boxes (the first such group) belongs to the tile owning
         * that group, which places it only while it draws the group, so it never floats without
         * walls; any other doodad belongs to the tile it stands in (the owner rule on its
         * position). The tiles agree on every doodad's tile without seeing each other.
         */
        private static final class ByGroupFilter {
            private final Owner owner;
            private final float[] interiorBoxes;
            private final int[] interiorGroups;
            private final boolean[] owned;
            private final java.util.BitSet drawn;

            /** While true, doodads inside a drawn interior wait in {@link #deferred}. */
            boolean deferring = true;

            /** Whether the last doodad placed stands inside a drawn interior. */
            boolean inside;

            /** That interior's group, the one whose box assigned the doodad; else -1. */
            int insideGroup = -1;

            /** Set and MODD ordinal of each doodad waiting for the interiors' turn. */
            final List<int[]> deferred = new ArrayList<>();

            ByGroupFilter(Owner owner, WmoFormats.Root root, java.util.BitSet drawn) {
                this.owner = owner;
                this.drawn = drawn == null ? new java.util.BitSet() : drawn;
                var boxes = new ArrayList<float[]>();
                var groups = new ArrayList<Integer>();
                if (root.groupInfoBoundsRetained()) {
                    for (int g = 0; g < root.header().groupCount(); g++) {
                        if ((root.groupInfoFlags(g) & INTERIOR) == 0) {
                            continue;
                        }
                        var lower = root.groupInfoLower(g);
                        var upper = root.groupInfoUpper(g);
                        boxes.add(
                                new float[] {
                                    lower.x(), lower.y(), lower.z(), upper.x(), upper.y(), upper.z()
                                });
                        groups.add(g);
                    }
                }
                interiorBoxes = new float[boxes.size() * 6];
                interiorGroups = new int[groups.size()];
                for (int i = 0; i < boxes.size(); i++) {
                    System.arraycopy(boxes.get(i), 0, interiorBoxes, i * 6, 6);
                    interiorGroups[i] = groups.get(i);
                }
                owned = boxes.isEmpty() ? new boolean[0] : owner.owned(root);
            }

            /** Null to place the doodad, else the bucket it is left out under. */
            Count refuse(double nativeX, double nativeY, double a, double b, double c) {
                inside = false;
                insideGroup = -1;
                for (int i = 0; i < interiorGroups.length; i++) {
                    int at = i * 6;
                    if (a >= interiorBoxes[at]
                            && b >= interiorBoxes[at + 1]
                            && c >= interiorBoxes[at + 2]
                            && a <= interiorBoxes[at + 3]
                            && b <= interiorBoxes[at + 4]
                            && c <= interiorBoxes[at + 5]) {
                        int g = interiorGroups[i];
                        if (!owned[g]) {
                            return Count.SET_DOODAD_OTHER_TILE;
                        }
                        inside = drawn.get(g);
                        insideGroup = inside ? g : -1;
                        return inside ? null : Count.SET_DOODAD_INTERIOR;
                    }
                }
                return owner.owns(nativeX, nativeY) ? null : Count.SET_DOODAD_OTHER_TILE;
            }
        }

        /** Counts a resolved mesh's cutout and blended (additive included) ranges. */
        private static void countMaterials(Mesh mapped, int[] local) {
            for (var range : mapped.ranges()) {
                if (kind(range.blend()) == CUTOUT) {
                    local[Count.BATCH_CUTOUT.ordinal()]++;
                } else if (kind(range.blend()) >= BLENDED) {
                    local[Count.BATCH_BLENDED.ordinal()]++;
                }
            }
        }

        /** MDDF doodads in obj0 order, under the heading rule plus pitch and roll. */
        private void placeDoodads(List<WorldPlacements.Doodad> records) {
            var byUid = new LinkedHashMap<Long, List<WorldPlacements.Doodad>>();
            for (var record : records) {
                token.check();
                byUid.computeIfAbsent(record.uniqueId(), ignored -> new ArrayList<>()).add(record);
            }
            // Each unique ID's group is compared with its first record once, not once per record.
            var conflicting = new HashMap<Long, Boolean>();
            for (var entry : byUid.entrySet()) {
                token.check();
                var first = entry.getValue().getFirst();
                boolean conflict = false;
                for (var other : entry.getValue()) {
                    conflict |= !sameDoodad(first, other);
                }
                conflicting.put(entry.getKey(), conflict);
            }
            for (var record : records) {
                token.check();
                var group = byUid.get(record.uniqueId());
                if (conflicting.get(record.uniqueId())) {
                    count(Count.DOODAD_CONFLICTING);
                    continue;
                }
                if (group.getFirst() != record) {
                    count(Count.DOODAD_SHARED_DUPLICATE);
                    continue;
                }
                int flags = record.flags();
                if ((flags & DOODAD_DIRECT_ID) == 0
                        || (flags & ~(DOODAD_DIRECT_ID | DOODAD_ADMITTED)) != 0
                        || record.reference().kind() != WorldPlacements.ReferenceKind.FILE_DATA_ID
                        || record.reference().value() <= 0
                        || record.scale() <= 0) {
                    count(Count.DOODAD_UNSUPPORTED);
                    continue;
                }
                double scale = record.scale() / 1024.0;
                var transform =
                        new WorldBasicWmo.Transform(
                                record.position(), record.rotation().y(), scale);
                var rotation =
                        Rotation.mddf(
                                transform.cos(),
                                transform.sin(),
                                record.rotation().x(),
                                record.rotation().z());
                if (scale > MAX_SCALE
                        || !finite(transform.originX(), transform.originY(), transform.originZ())
                        || !rotation.finite()) {
                    count(Count.DOODAD_UNSUPPORTED);
                    continue;
                }
                queued.add(
                        new QueuedDoodad(
                                Kind.DOODAD,
                                record.reference().value(),
                                record.ordinal(),
                                record.uniqueId(),
                                flags,
                                0,
                                transform.originX(),
                                transform.originY(),
                                transform.originZ(),
                                scale,
                                transform.cos(),
                                transform.sin(),
                                rotation,
                                -1));
            }
            placeQueued();
        }

        /**
         * Each drawn building's set 0 and, when nonzero, its MODF doodad set, in MODD order. A MODD
         * entry is placed in the building's local axes: its position goes through the building
         * transform and its full quaternion composes with the building heading. Buildings with
         * opaque draws go first, then material-only ones, each in placement order, so a building
         * the opaque-only profile left empty never spends doodad, model or draw budget ahead of one
         * it drew. Set doodads are appended in this order.
         */
        private void placeSetDoodads(java.util.function.Predicate<Placement> include) {
            var buildings = new ArrayList<Placement>(placements.size());
            for (int pass = 0; pass < 2; pass++) {
                for (var placement : placements) {
                    boolean opaque =
                            placement.kind() == Kind.BUILDING
                                    && admissionDraws(placement.model()) > 0;
                    if (opaque == (pass == 0)) {
                        buildings.add(placement);
                    }
                }
            }
            for (var building : buildings) {
                token.check();
                if (building.kind() != Kind.BUILDING || !include.test(building)) {
                    continue;
                }
                placeSetsOf(building, rootDoodads.get(building.rootId()), null);
            }
            placeQueued();
        }

        /**
         * One building's set 0 and selected doodad set, in MODD order; {@code filter} (roots drawn
         * by group) leaves out the doodads this tile does not place.
         */
        private void placeSetsOf(
                Placement building, WmoFormats.Doodads declared, ByGroupFilter filter) {
            {
                if (declared == null || !declared.declared()) {
                    return; // the root declares no doodads
                }
                if (!declared.retained()
                        || !declared.fileIdsPresent()
                        || declared.sets().isEmpty()) {
                    count(Count.SET_UNSUPPORTED);
                    return;
                }
                var selected = new ArrayList<Integer>(2);
                selected.add(0);
                if ((building.flags() & SETS_FROM_MWDS) != 0) {
                    // MODF doodadSet names an MWDR range of MWDS set indexes (set 0 is kept).
                    if (!selectFromMwds(building.doodadSet(), declared, selected)) {
                        count(Count.SET_UNSUPPORTED);
                    }
                } else if (building.doodadSet() != 0) {
                    if (building.doodadSet() >= declared.sets().size()) {
                        count(Count.SET_UNSUPPORTED);
                    } else {
                        selected.add(building.doodadSet());
                    }
                }
                for (int set : selected) {
                    var range = declared.sets().get(set);
                    long end = range.firstDefinition() + range.count();
                    for (long d = range.firstDefinition(); d < end; d++) {
                        token.check();
                        if (d >= declared.definitions().size()) {
                            count(Count.SET_DOODAD_UNSUPPORTED);
                            continue;
                        }
                        setDoodad(building, declared, set, (int) d, filter);
                    }
                }
            }
        }

        /**
         * Adds the MWDS sets of MWDR entry {@code entry}; false (nothing added) when the entry or
         * any set index it lists is outside the recorded MWDR, MWDS or MODS.
         */
        private boolean selectFromMwds(
                int entry, WmoFormats.Doodads declared, List<Integer> selected) {
            if (entry >= setRanges.size()) {
                return false;
            }
            var range = setRanges.get(entry);
            if (range.end() < range.begin() || range.end() >= setIds.size()) {
                return false;
            }
            var listed = new ArrayList<Integer>();
            for (long i = range.begin(); i <= range.end(); i++) {
                int set = setIds.get((int) i);
                if (set >= declared.sets().size()) {
                    return false;
                }
                if (!selected.contains(set) && !listed.contains(set)) {
                    listed.add(set);
                }
            }
            selected.addAll(listed);
            return true;
        }

        private void setDoodad(
                Placement building,
                WmoFormats.Doodads declared,
                int set,
                int ordinal,
                ByGroupFilter filter) {
            var def = declared.definitions().get(ordinal);
            long id =
                    def.reference() < declared.fileIdCount() ? declared.fileId(def.reference()) : 0;
            double qx = def.qx(), qy = def.qy(), qz = def.qz(), qw = def.qw();
            double norm = StrictMath.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            double scale = building.scale() * def.scale();
            if (id <= 0
                    || (def.flags() & ~SET_DOODAD_ADMITTED) != 0
                    || !finite(def.x(), def.y(), def.z())
                    || !Double.isFinite(norm)
                    || Math.abs(norm - 1) > 1e-3
                    || !Float.isFinite(def.scale())
                    || !(def.scale() > 0)
                    || !(scale <= MAX_SCALE)) {
                count(Count.SET_DOODAD_UNSUPPORTED);
                return;
            }
            // The building's full rotation (heading, pitch, roll) and scale carry the MODD
            // position; for an untilted building this is exactly the heading rule.
            double[] m = building.rotation().rowMajor();
            double s = building.scale(), a = def.x(), b = def.y(), c = def.z();
            double x = building.x() + s * (m[0] * a + m[1] * b + m[2] * c);
            double y = building.y() + s * (m[3] * a + m[4] * b + m[5] * c);
            double z = building.z() + s * (m[6] * a + m[7] * b + m[8] * c);
            if (!finite(x, y, z)) {
                count(Count.SET_DOODAD_UNSUPPORTED);
                return;
            }
            // A root drawn by group: only the set doodads standing in this tile (the tile they
            // stand in places the others), and none inside an interior group not drawn.
            var refused = filter == null ? null : filter.refuse(x, y, a, b, c);
            if (refused != null) {
                count(refused);
                return;
            }
            if (filter != null && filter.deferring && filter.inside) {
                filter.deferred.add(new int[] {set, ordinal});
                return;
            }
            // The full MODD quaternion (x, y, z, w) in raw WMO-local axes, applied before the
            // building transform: building * T(position) * fromQuat(q) * S(scale) (wowdev WMO rev
            // 37115 MODD placement matrix; wow.export 4474fa4 WMORenderer.js:400-466).
            var rotation = building.rotation().times(Rotation.quaternion(qx, qy, qz, qw));
            int group = portalGroup(building, declared, ordinal);
            // A root drawn by group: this tile reads only its own groups, so another tile's group
            // might also list the doodad. Tie it only to the interior whose box placed it here.
            if (filter != null && group != filter.insideGroup) {
                group = -1;
            }
            queued.add(
                    new QueuedDoodad(
                            Kind.SET_DOODAD,
                            id,
                            ordinal,
                            building.uniqueId(),
                            def.flags(),
                            set,
                            x,
                            y,
                            z,
                            scale,
                            building.cos(),
                            building.sin(),
                            rotation,
                            group));
        }

        /**
         * One qualified doodad, queued by a placement pass in its placement order. Qualifying a
         * doodad never depends on how an earlier one was placed, so a pass queues them all and
         * {@link #placeQueued} places them afterwards, in the same order.
         */
        private record QueuedDoodad(
                Kind kind,
                long modelId,
                int ordinal,
                long uniqueId,
                int flags,
                int doodadSet,
                double x,
                double y,
                double z,
                double scale,
                double cos,
                double sin,
                Rotation rotation,
                int portalGroup) {}

        /**
         * Places the doodads the pass queued, in order, after hinting their model reads (and each
         * model's skin) to the read-ahead ({@link #prefetchModels}).
         */
        private void placeQueued() {
            try {
                prefetchModels();
                for (var q : queued) {
                    token.check();
                    var outcome =
                            doodad(
                                    q.kind(),
                                    q.modelId(),
                                    q.ordinal(),
                                    q.uniqueId(),
                                    q.flags(),
                                    q.doodadSet(),
                                    q.x(),
                                    q.y(),
                                    q.z(),
                                    q.scale(),
                                    q.cos(),
                                    q.sin(),
                                    q.rotation(),
                                    q.portalGroup());
                    if (q.kind() == Kind.DOODAD) {
                        count(
                                switch (outcome) {
                                    case DRAWN -> Count.DOODAD_DRAWN;
                                    case FAILED -> Count.DOODAD_FAILED_MODEL;
                                    case CAPPED -> Count.DOODAD_CAPPED;
                                    case EMPTY -> Count.DOODAD_EMPTY;
                                    case BY_GROUP, OTHER_TILE ->
                                            throw new IllegalStateException("Doodad state");
                                });
                        continue;
                    }
                    if (outcome == RootState.DRAWN && q.portalGroup() >= 0) {
                        count(Count.SET_DOODAD_IN_GROUP);
                    }
                    count(
                            switch (outcome) {
                                case DRAWN -> Count.SET_DOODAD_DRAWN;
                                case FAILED -> Count.SET_DOODAD_FAILED_MODEL;
                                case CAPPED -> Count.SET_DOODAD_CAPPED;
                                case EMPTY -> Count.SET_DOODAD_EMPTY;
                                case BY_GROUP, OTHER_TILE ->
                                        throw new IllegalStateException("Doodad state");
                            });
                }
            } finally {
                queued.clear();
                // Every hint is consumed above; withdraw any left by a failure all the same.
                admission.cancelPrefetch();
            }
        }

        /**
         * Hints, in placement order, exactly the model reads {@link #doodad} will make for the
         * queued doodads (#371), each followed by its model's first SKIN once the model's bytes
         * parse as {@link #readModel} parses them. A model is hinted only where its read is
         * certain: the first use of a model not yet read, within the model cap (the interior
         * allowance only for a tied doodad), while even every queued doodad before it drawing would
         * leave the placement cap unreached. Once its SKIN is read, the images its admitted batches
         * name follow, in the order {@link #resolve} reads them, within the images the cap still
         * leaves: which of those the model really reads depends on what the tile registered and
         * charged before it, so one it skips is only read for nothing, and one the tile already
         * consumed is not read ahead again.
         */
        private void prefetchModels() {
            int reads = modelsRead;
            int placed = doodads;
            var hinted = new java.util.HashSet<Long>();
            for (var q : queued) {
                if (placed >= MAX_DOODADS) {
                    break; // later reads hang on how many of these draw
                }
                placed++;
                long id = q.modelId();
                if (doodadStates.containsKey(id) || hinted.contains(id)) {
                    continue;
                }
                if (reads >= MAX_MODELS + (q.portalGroup() >= 0 ? MAX_INTERIOR_MODELS : 0)) {
                    continue; // capped here, so not read; a later tied use may still read it
                }
                reads++;
                hinted.add(id);
                var parsed = new java.util.concurrent.atomic.AtomicReference<M2Types.Model>();
                admission.prefetch(
                        id,
                        WorldStreamScopes.DerivedKind.M2_MODEL,
                        WorldStreamScopes.DerivedKind.M2_SKIN,
                        (bytes, cancel) -> firstSkin(id, bytes, cancel, parsed),
                        WorldStreamScopes.DerivedKind.M2_IMAGE,
                        (bytes, cancel) -> modelImages(id, parsed.get(), bytes, cancel),
                        // At most the images the doodad image cap still leaves (the interior
                        // allowance aside), so reads past that count are never fetched; an image
                        // of a model later capped, or of a batch later dropped, may still be.
                        MAX_MODEL_IMAGES - modelImagesRead);
            }
        }

        /**
         * The SKIN {@link #readModel} reads after these model bytes, or 0 when it reads none: the
         * same reader over the same bytes, on the read-ahead thread. Keeps the parsed model in
         * {@code parsed} for {@link #modelImages}.
         */
        private long firstSkin(
                long id,
                byte[] bytes,
                Cancellation cancel,
                java.util.concurrent.atomic.AtomicReference<M2Types.Model> parsed) {
            M2Types.Model model;
            try {
                model = modelReader.read(id, bytes, cancel).join();
            } catch (RuntimeException failed) {
                return 0;
            }
            var skins = model.skinFileDataIds();
            if (skins.isEmpty() || skins.getFirst() <= 0) {
                return 0;
            }
            parsed.set(model);
            return skins.getFirst();
        }

        /**
         * The images {@link #resolve} reads for this model's mesh after these SKIN bytes, in its
         * order and once each, before the tile's registered images and caps rule some out: the same
         * reader and the same {@link #copyModel} over the same bytes, on the read-ahead thread,
         * stopping as soon as that read is canceled (withdrawn, or the tile canceled).
         */
        private long[] modelImages(
                long id, M2Types.Model model, byte[] bytes, Cancellation cancel) {
            if (model == null) {
                return new long[0];
            }
            M2Types.Skin skin;
            try {
                skin =
                        M2SkinReader.read(AssetSource.bytes(bytes), model, MODEL_LIMITS, cancel)
                                .join();
            } catch (RuntimeException failed) {
                return new long[0];
            }
            var pending =
                    copyModel(
                            id,
                            model.skinFileDataIds().getFirst(),
                            model,
                            skin,
                            new int[counts.length],
                            cancel);
            if (pending == null || pending == ALL_BILLBOARD) {
                return new long[0];
            }
            return pending.ranges().stream().mapToLong(range -> range.image()).distinct().toArray();
        }

        /**
         * Places one qualified doodad, reading its model on first use. A set doodad tied to an
         * interior group ({@code portalGroup} at least 0) may read its model from the interior
         * allowance once the {@link #MAX_MODELS} cap is reached.
         */
        private RootState doodad(
                Kind kind,
                long modelId,
                int ordinal,
                long uniqueId,
                int flags,
                int doodadSet,
                double x,
                double y,
                double z,
                double scale,
                double cos,
                double sin,
                Rotation rotation,
                int portalGroup) {
            if (doodads >= MAX_DOODADS) {
                count(Count.DOODAD_CAP_PLACEMENTS);
                return RootState.CAPPED;
            }
            var state = doodadStates.get(modelId);
            if (state == null) {
                if (modelsRead >= MAX_MODELS + (portalGroup >= 0 ? MAX_INTERIOR_MODELS : 0)) {
                    count(Count.DOODAD_CAP_MODELS);
                    return RootState.CAPPED;
                }
                boolean interior = modelsRead >= MAX_MODELS;
                interiorModelCharge = interior;
                try {
                    state = readModel(modelId);
                } finally {
                    interiorModelCharge = false;
                }
                if (interior) {
                    interiorModels.add(modelId);
                    count(Count.MODEL_INTERIOR_ALLOWANCE);
                }
                doodadStates.put(modelId, state);
            }
            if (state != RootState.DRAWN) {
                if (state == RootState.CAPPED) {
                    count(Count.DOODAD_CAP_MODEL_SIZE);
                }
                return state;
            }
            int model = doodadSlots.get(modelId), cost = modelDraws(model);
            if (cost > MAX_DRAWS - draws) {
                count(Count.DOODAD_CAP_DRAWS);
                return RootState.CAPPED;
            }
            draws += cost;
            doodads++;
            placements.add(
                    new Placement(
                            kind,
                            ordinal,
                            uniqueId,
                            modelId,
                            model,
                            flags,
                            doodadSet,
                            0,
                            x,
                            y,
                            z,
                            scale,
                            cos,
                            sin,
                            rotation,
                            portalGroup));
            return RootState.DRAWN;
        }

        private enum Admission {
            ADMITTED,
            /** A material-only mesh resolve would cap entirely; counted, its copy not kept. */
            DROPPED,
            /** Over a per-tile budget: the root is capped, or an interior group is skipped. */
            OVER
        }

        /**
         * One root's tentative charges against the tile budgets. Caps are decided by opaque
         * candidates alone, exactly as the opaque-only profile did; cutout and blended candidates
         * draw from their own budget later. Pending copies (every candidate vertex and index of
         * every kept group) never exceed what both budgets together could draw.
         */
        private final class RootBudget {
            long vertices, indices, ranges, pendingVertices, pendingIndices, materialVertices;

            /**
             * Sized alone, the copies of cutout- and blend-only groups, kept out of the OVER
             * decision: those groups are capped against the tile's material budget when resolved,
             * so they never make a root that fits an empty tile split. Bounded as the other copies.
             */
            long aloneMaterialVertices, aloneMaterialIndices;

            private final Allowance allowance;

            RootBudget() {
                this(Allowance.TILE);
            }

            RootBudget(Allowance allowance) {
                this.allowance = allowance;
            }

            /** Whether the root's opaque charges fit what is left of the tile's building pool. */
            boolean fitsTile() {
                return vertices <= MAX_VERTICES - Builder.this.vertices
                        && indices <= MAX_INDICES - Builder.this.indices
                        && ranges <= MAX_RANGES - Builder.this.ranges;
            }

            long[] mark() {
                return new long[] {
                    vertices,
                    indices,
                    ranges,
                    pendingVertices,
                    pendingIndices,
                    materialVertices,
                    aloneMaterialVertices,
                    aloneMaterialIndices
                };
            }

            void reset(long[] m) {
                vertices = m[0];
                indices = m[1];
                ranges = m[2];
                pendingVertices = m[3];
                pendingIndices = m[4];
                materialVertices = m[5];
                aloneMaterialVertices = m[6];
                aloneMaterialIndices = m[7];
            }

            /** Whether the mesh alone fits the tile's empty budgets; otherwise never hold it. */
            boolean couldEverFit(PendingMesh mesh) {
                return mesh.vertices().length / VERTEX_FLOATS
                                <= MAX_VERTICES + MAX_MATERIAL_VERTICES
                        && mesh.indices().length <= MAX_INDICES + MAX_MATERIAL_INDICES;
            }

            Admission admit(PendingMesh mesh, int[] local) {
                int meshVertices = mesh.vertices().length / VERTEX_FLOATS;
                boolean materialOnly =
                        mesh.ranges().stream().noneMatch(r -> kind(r.blend()) == OPAQUE);
                // Sized alone, nothing is dropped here: a drop would depend on the tile, or on
                // other tiles' groups of a root drawn by group. A mesh that does not fit is capped
                // against this tile's material budget when it is resolved, with the same counts.
                long tileMaterial = Builder.this.materialVertices;
                if (allowance != Allowance.ALONE
                        && materialOnly
                        && !mesh.ranges().isEmpty()
                        && meshVertices > MAX_MATERIAL_VERTICES - tileMaterial - materialVertices) {
                    // resolveBuilding would cap every range of this cutout- or blend-only mesh on
                    // its vertices (BATCH_CAPPED, then GROUP_EMPTY) before reading any image; do it
                    // now so its copy is never held. Material vertices still pending are charged
                    // as if kept, a conservative bound.
                    local[Count.BATCH_CAPPED.ordinal()] += mesh.ranges().size();
                    local[Count.GROUP_EMPTY.ordinal()]++;
                    return Admission.DROPPED;
                }
                if (materialOnly) {
                    materialVertices += meshVertices;
                }
                if (allowance == Allowance.ALONE && materialOnly && !mesh.ranges().isEmpty()) {
                    // Its copy is bounded on its own (one more set of both budgets), never
                    // against the opaque candidates: only a root past that bound is over.
                    aloneMaterialVertices += meshVertices;
                    aloneMaterialIndices += mesh.indices().length;
                    return aloneMaterialVertices > MAX_VERTICES + MAX_MATERIAL_VERTICES
                                    || aloneMaterialIndices > MAX_INDICES + MAX_MATERIAL_INDICES
                            ? Admission.OVER
                            : Admission.ADMITTED;
                }
                long capVertices =
                        switch (allowance) {
                            case LARGE -> MAX_LARGE_VERTICES + MAX_LARGE_INTERIOR_VERTICES;
                            case TILE_INTERIOR -> MAX_VERTICES + MAX_TILE_INTERIOR_VERTICES;
                            default -> MAX_VERTICES;
                        };
                long capIndices =
                        switch (allowance) {
                            case LARGE -> MAX_LARGE_INDICES + MAX_LARGE_INTERIOR_INDICES;
                            case TILE_INTERIOR -> MAX_INDICES + MAX_TILE_INTERIOR_INDICES;
                            default -> MAX_INDICES;
                        };
                long capRanges =
                        switch (allowance) {
                            case LARGE -> MAX_LARGE_RANGES + MAX_LARGE_INTERIOR_RANGES;
                            case TILE_INTERIOR -> MAX_RANGES + MAX_TILE_INTERIOR_RANGES;
                            default -> MAX_RANGES;
                        };
                pendingVertices += meshVertices;
                pendingIndices += mesh.indices().length;
                if (pendingVertices > capVertices + MAX_MATERIAL_VERTICES
                        || pendingIndices > capIndices + MAX_MATERIAL_INDICES) {
                    return Admission.OVER;
                }
                int opaqueIndices = 0, opaqueRanges = 0;
                for (var range : mesh.ranges()) {
                    if (kind(range.blend()) == OPAQUE) {
                        opaqueIndices += range.indexCount();
                        opaqueRanges++;
                    }
                }
                if (opaqueRanges > 0) {
                    vertices += meshVertices;
                }
                indices += opaqueIndices;
                ranges += opaqueRanges;
                long usedVertices =
                        switch (allowance) {
                            case TILE, TILE_INTERIOR -> Builder.this.vertices;
                            case LARGE -> largeVertices;
                            case ALONE -> 0;
                        };
                long usedIndices =
                        switch (allowance) {
                            case TILE, TILE_INTERIOR -> Builder.this.indices;
                            case LARGE -> largeIndices;
                            case ALONE -> 0;
                        };
                long usedRanges =
                        switch (allowance) {
                            case TILE, TILE_INTERIOR -> Builder.this.ranges;
                            case LARGE -> largeRanges;
                            case ALONE -> 0;
                        };
                if (vertices > capVertices - usedVertices
                        || indices > capIndices - usedIndices
                        || ranges > capRanges - usedRanges) {
                    return Admission.OVER;
                }
                return Admission.ADMITTED;
            }
        }

        /**
         * Holds one interior mesh for the interior pass while the tile could still draw it: false,
         * and nothing held, past both budgets together. Bounds held copies across all roots.
         */
        private boolean holdInterior(PendingMesh mesh) {
            long meshVertices = mesh.vertices().length / VERTEX_FLOATS;
            if (heldVertices + meshVertices
                            > MAX_VERTICES + MAX_TILE_INTERIOR_VERTICES + MAX_MATERIAL_VERTICES
                    || heldIndices + mesh.indices().length
                            > MAX_INDICES + MAX_TILE_INTERIOR_INDICES + MAX_MATERIAL_INDICES) {
                return false;
            }
            heldVertices += meshVertices;
            heldIndices += mesh.indices().length;
            return true;
        }

        /** Returns a capped root's held interior copies to the bound. */
        private void releaseInteriors(List<PendingMesh> interiors) {
            for (var mesh : interiors) {
                heldVertices -= mesh.vertices().length / VERTEX_FLOATS;
                heldIndices -= mesh.indices().length;
            }
        }

        /**
         * Interiors (#280, #383), after every building's exterior and every MDDF and set doodad of
         * the drawn exteriors: drawn buildings in admission order (largest first), each held
         * interior group only if it still fits the tile budgets, the mesh count and the draw budget
         * left after every placement already drawn, for every placement of its building. A group
         * that does not fit is GROUP_INTERIOR_CAPPED; it never caps or displaces an exterior or a
         * doodad. A building left with no meshes (an interior-only root none of whose groups fit)
         * is removed, its root and placements counted EMPTY.
         *
         * <p>An interior-only root takes building slots for its placements when its first group is
         * admitted, opaque or not, and only if they are free: interiors lose every slot tie, and
         * since material draws were already fitted, they never push an exterior's glass or foliage
         * out. Interior images share the building pixel pool with exterior images: exteriors are
         * allotted first from the pool less each interior-only image's smallest level, so a dense
         * interior can lower an exterior level only by that reserve and never drops an exterior
         * image.
         */
        private void placeInteriors() {
            if (heldInteriors.isEmpty()) {
                return;
            }
            interiorPass = true;
            try {
                admitInteriors();
            } finally {
                interiorPass = false;
            }
        }

        /**
         * What the exteriors placed after an interior step need: the further placements of roots
         * drawn whole (each draws its root's model as it stands) and each split share that owns an
         * exterior group of a drawn model here and could be placed (a free building model row, its
         * geometry within what the large allowance has left), with a mesh row per owned exterior
         * group, its draws and its geometry. A share's interiors are not held for it: a share that
         * does not fit with them is placed with its exteriors only ({@link #placeSplitShares}).
         */
        private LaterExteriors laterExteriors() {
            int meshes = 0, slots = 0;
            long draws = 0, vertices = 0, indices = 0, ranges = 0;
            for (var entry : wholeExtras.entrySet()) {
                Integer slot = modelSlots.get(entry.getKey());
                if (slot != null && byGroupRoots.contains(entry.getKey())) {
                    draws += (long) entry.getValue().size() * modelDraws(slot);
                    slots += entry.getValue().size();
                }
            }
            int buildingModels = 0;
            for (var m : models) {
                buildingModels += m.doodad() ? 0 : 1;
            }
            for (var queued : splitShares) {
                long rootId = queued.record().reference().value();
                var root = byGroupRootFacts.get(rootId);
                Integer slot =
                        rootStates.get(rootId) == RootState.DRAWN ? modelSlots.get(rootId) : null;
                if (root == null || slot == null) {
                    continue;
                }
                var owned = queued.owner().owned(root);
                int shareMeshes = 0;
                long shareDraws = 0, shareVertices = 0, shareIndices = 0;
                for (var mesh : models.get(slot).meshes()) {
                    int g = mesh.groupOrdinal();
                    if (g >= 0 && g < owned.length && owned[g] && !mogiInterior(root, g)) {
                        shareMeshes++;
                        shareDraws += mesh.ranges().size();
                        shareVertices += mesh.vertexCount();
                        shareIndices += mesh.indices().remaining();
                    }
                }
                if (shareMeshes == 0
                        || buildingModels + 1 > MAX_ROOTS
                        || largeVertices + vertices + shareVertices
                                > MAX_LARGE_VERTICES + MAX_LARGE_INTERIOR_VERTICES
                        || largeIndices + indices + shareIndices
                                > MAX_LARGE_INDICES + MAX_LARGE_INTERIOR_INDICES
                        || largeRanges + ranges + shareDraws
                                > MAX_LARGE_RANGES + MAX_LARGE_INTERIOR_RANGES) {
                    continue; // nothing to hold here, or it could not be placed anyway
                }
                buildingModels++;
                meshes += shareMeshes;
                draws += shareDraws;
                vertices += shareVertices;
                indices += shareIndices;
                ranges += shareDraws;
                slots++;
            }
            return new LaterExteriors(meshes, draws, slots, vertices, indices, ranges);
        }

        /** Further placements of root {@code rootId} drawn whole, each drawing its whole model. */
        private int extrasOf(long rootId) {
            var extras = wholeExtras.get(rootId);
            return extras == null || !byGroupRoots.contains(rootId) ? 0 : extras.size();
        }

        private void admitInteriors() {
            int[] uses = new int[models.size()];
            for (var placement : placements) {
                uses[placement.model()]++;
            }
            int meshes = 0;
            // Every exterior draw is reserved first, opaque, cutout and blended alike, so admitting
            // an interior never makes fitMaterialDraws trim an exterior's glass or foliage.
            long reserved = 0;
            for (int m = 0; m < models.size(); m++) {
                // Building meshes only: doodad models have their own rows (MAX_MODELS).
                meshes += models.get(m).doodad() ? 0 : models.get(m).meshes().size();
                reserved += (long) uses[m] * modelDraws(m);
            }
            // Exteriors placed after the interiors keep what they need too (laterExteriors).
            var later = laterExteriors();
            meshes += later.meshes();
            reserved += later.draws();
            int reservedSlots = later.slots();
            int[] local = new int[counts.length];
            boolean[] removed = new boolean[models.size()];
            for (int m = 0; m < models.size(); m++) {
                var model = models.get(m);
                if (model.doodad()) {
                    continue;
                }
                var held = heldInteriors.remove(model.rootId());
                if (held == null || uses[m] == 0) {
                    if (held != null) {
                        releaseInteriors(held); // a root with no placement draws nothing
                    }
                    continue;
                }
                // A model without opaque draws (an interior-only root) holds no building slot
                // yet; its placements need free slots before an interior can give it one. An
                // interior-only root whose groups did not fit the tile's reads is capped too.
                boolean needsSlots = admissionDraws(m) == 0;
                boolean unread = interiorReadsCapped.contains(model.rootId());
                if (unread
                        || needsSlots
                                && uses[m] > MAX_PLACEMENTS - opaquePlacements - reservedSlots) {
                    local[Count.GROUP_INTERIOR_CAPPED.ordinal()] += held.size();
                    if (model.meshes().isEmpty()) {
                        removed[m] = true;
                        counts[Count.PLACEMENT_DRAWN.ordinal()] -= uses[m];
                        counts[Count.PLACEMENT_CAPPED.ordinal()] += uses[m];
                        counts[Count.ROOT_DRAWN.ordinal()]--;
                        count(Count.ROOT_CAPPED);
                        rootStates.put(model.rootId(), RootState.CAPPED);
                        rootDoodads.remove(model.rootId());
                    }
                    continue;
                }
                var kept = new ArrayList<>(model.meshes());
                boolean slotsTaken = false;
                for (var mesh : held) {
                    token.check();
                    int candidateRanges = mesh.ranges().size();
                    if (meshes >= MAX_MESHES
                            || (long) (uses[m] + extrasOf(model.rootId())) * candidateRanges
                                    > MAX_DRAWS - reserved) {
                        local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        continue;
                    }
                    var admission = new RootBudget(Allowance.TILE_INTERIOR).admit(mesh, local);
                    if (admission == Admission.DROPPED) {
                        continue; // counted by admit: BATCH_CAPPED, GROUP_EMPTY
                    }
                    if (admission == Admission.OVER) {
                        local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        continue;
                    }
                    var mapped = resolveBuilding(mesh, local);
                    if (mapped == null) {
                        local[Count.GROUP_EMPTY.ordinal()]++;
                        continue;
                    }
                    int opaque = 0;
                    for (var range : mapped.ranges()) {
                        if (kind(range.blend()) == CUTOUT) {
                            local[Count.BATCH_CUTOUT.ordinal()]++;
                        } else if (kind(range.blend()) >= BLENDED) {
                            local[Count.BATCH_BLENDED.ordinal()]++;
                        } else {
                            opaque++;
                        }
                    }
                    draws += uses[m] * opaque;
                    reserved +=
                            (long) (uses[m] + extrasOf(model.rootId())) * mapped.ranges().size();
                    if (needsSlots && !slotsTaken) {
                        opaquePlacements += uses[m];
                        slotsTaken = true;
                    }
                    local[Count.GROUP_DRAWN.ordinal()]++;
                    kept.add(mapped);
                    interiorMeshes.add(mapped);
                    meshes++;
                }
                if (kept.isEmpty()) {
                    removed[m] = true;
                    counts[Count.PLACEMENT_DRAWN.ordinal()] -= uses[m];
                    counts[Count.PLACEMENT_EMPTY.ordinal()] += uses[m];
                    counts[Count.ROOT_DRAWN.ordinal()]--;
                    count(Count.ROOT_EMPTY);
                    rootStates.put(model.rootId(), RootState.EMPTY);
                    rootDoodads.remove(model.rootId());
                    continue;
                }
                models.set(m, new Model(model.rootId(), false, kept, model.portals()));
            }
            for (int i = 0; i < counts.length; i++) {
                counts[i] += local[i];
            }
            removeModels(removed);
        }

        /**
         * Reads and admits one root. With an {@code owner} (a placement whose recorded bounds cross
         * a tile border) the root's exterior is first sized against an empty tile's building pool,
         * on open-world maps together with its MOGI-interior groups ({@link #interiorsOverAlone}):
         * one that fits is admitted against what the tile left, exactly as without an owner, its
         * interiors still waiting for the interior pass; one over it is drawn by group instead
         * ({@link #readByGroup}). A root to be drawn whole that does not fit what the tile's
         * building pool left is drawn whole from the large allowance instead of capped ({@link
         * #readWhole}).
         */
        private RootState readRoot(long rootId, Owner owner) {
            rootsRead++;
            WmoFormats.Root root;
            var input = derived(rootId, WorldStreamScopes.DerivedKind.WMO_ROOT);
            if (input == null) {
                count(Count.ROOT_UNAVAILABLE);
                return RootState.FAILED;
            }
            long parsing = WorldStageTimings.start();
            try (input) {
                root = WmoFormats.root(input.bytes(), ROOT_LIMITS, token::isCanceled);
                WorldStageTimings.stop("objects.wmo.rootParse", parsing);
            } catch (IllegalArgumentException malformed) {
                count(Count.ROOT_FAILED);
                return RootState.FAILED;
            }
            long groupCount = root.header().groupCount();
            if (root.textureNameBlockPresent()
                    || !root.gfidPresent()
                    || groupCount == 0
                    || root.gfidCount() == 0
                    || root.gfidCount() % groupCount != 0) {
                count(Count.ROOT_UNSUPPORTED);
                return RootState.FAILED;
            }
            int lod0 = (int) groupCount, reads = 0;
            // Without interiors, groups whose MOGI flags say interior are never read: they would
            // only be dropped (GROUP_INTERIOR), and must not spend the tile's group reads. With
            // interiors they wait for readInteriors, after every building's exterior groups, so
            // an interior never caps an exterior root (#383).
            var skipped = new boolean[lod0];
            var deferred = new boolean[lod0];
            int deferredCount = 0;
            for (int g = 0; g < lod0; g++) {
                if (root.gfid(g) == 0) {
                    continue;
                }
                skipped[g] = skipInterior(root, g, this.interiors);
                deferred[g] = deferInterior(root, g, deferInteriors);
                if (deferred[g]) {
                    deferredCount++;
                } else if (!skipped[g]) {
                    reads++;
                }
            }
            if (reads > MAX_GROUPS - groupsRead) {
                count(Count.ROOT_CAPPED);
                return RootState.CAPPED;
            }
            // Read ahead (#371): this uncapped root reads its groups next, in this order. Only a
            // later geometry cap (ROOT_CAPPED) stops it, at most a window of reads early. Roots
            // are not read ahead: caps decided by earlier parsing leave many of them unread, and
            // a capped source must never be fetched. Its images are, once its groups are read
            // (prefetchBuildingImages), within the image caps.
            for (int g = 0; g < lod0; g++) {
                if (root.gfid(g) != 0 && !skipped[g] && !deferred[g]) {
                    admission.prefetch(root.gfid(g), WorldStreamScopes.DerivedKind.WMO_GROUP);
                }
            }
            // Tentative per-root counts merge only if the root is not capped: caps never split.
            int[] local = new int[counts.length];
            var pending = new ArrayList<PendingMesh>();
            var budget = new RootBudget(owner == null ? Allowance.TILE : Allowance.ALONE);
            // Each read's counts are kept per group, so that a root drawn by group, or drawn whole
            // from the large allowance, keeps its reads and counts.
            var sized = new Sized[lod0];
            // Decided by a placement inside the tile while a border-crossing one of the root is
            // listed too: its exteriors are kept for the owner rule's sizing ({@link
            // #splitsAlone}).
            boolean sizeAlone = owner == null && crossingRoots.contains(rootId);
            var exteriorsRead = new ArrayList<PendingMesh>();
            // Interior groups read here (#280, and MOGP-interior groups on open-world maps, #383)
            // are held for the tile-level pass after every building's exterior, so they never cap
            // or displace an exterior.
            var interiors = new ArrayList<PendingMesh>();
            // Every LOD0 group's header, for the root's portal set (#383); null when unread.
            var headers = new WmoFormats.GroupHeader[lod0];
            for (int g = 0; g < lod0; g++) {
                token.check();
                long groupId = root.gfid(g);
                if (groupId == 0) {
                    local[Count.GROUP_UNSUPPORTED.ordinal()]++;
                    continue;
                }
                if (skipped[g]) {
                    local[Count.GROUP_INTERIOR.ordinal()]++;
                    continue;
                }
                if (deferred[g]) {
                    continue; // counted by readInteriors
                }
                groupsRead++;
                int[] groupCounts = new int[counts.length];
                var mesh = readGroup(g, groupId, root, groupCounts, headers);
                sized[g] =
                        new Sized(
                                mesh == null || (mesh.flags() & INTERIOR) != 0 ? null : mesh,
                                mesh != null && (mesh.flags() & INTERIOR) != 0,
                                groupCounts.clone());
                for (int i = 0; i < local.length; i++) {
                    local[i] += groupCounts[i];
                }
                if (mesh == null) {
                    continue;
                }
                if ((mesh.flags() & INTERIOR) != 0) {
                    // Bounded as it is read: past what both budgets could draw, a copy is never
                    // held (GROUP_INTERIOR_CAPPED). Released again if this root is capped.
                    if (!holdInterior(mesh)) {
                        local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                    } else {
                        interiors.add(mesh);
                        // Kept for the interiors of a root drawn by group (instance maps).
                        sized[g] = new Sized(mesh, true, sized[g].counts());
                    }
                    continue;
                }
                if (sizeAlone) {
                    exteriorsRead.add(mesh);
                }
                var admitted = budget.admit(mesh, groupCounts);
                // A dropped copy is not kept; its counts stay with the group.
                for (int i = 0; i < local.length; i++) {
                    local[i] += groupCounts[i] - sized[g].counts()[i];
                }
                if (admitted == Admission.DROPPED) {
                    sized[g] = new Sized(null, false, groupCounts.clone());
                }
                switch (admitted) {
                    case ADMITTED -> pending.add(mesh);
                    case DROPPED -> {}
                    case OVER -> {
                        if (owner != null) {
                            // Over an empty tile's building pool: no tile could draw it whole.
                            return readByGroup(
                                    rootId, root, owner, skipped, deferred, sized, interiors);
                        }
                        // In one tile and over what its building pool left (or over an empty
                        // pool): drawn whole from the large allowance if it fits there. With a
                        // border-crossing placement listed too, sized as the owner rule sizes it,
                        // its unread exteriors and deferred interiors included.
                        boolean fitsAlone = false;
                        if (sizeAlone) {
                            var later = new boolean[lod0];
                            for (int next = g + 1; next < lod0; next++) {
                                later[next] =
                                        root.gfid(next) != 0 && !skipped[next] && !deferred[next];
                            }
                            fitsAlone =
                                    !splitsAlone(
                                            root,
                                            exteriorsRead,
                                            later,
                                            deferredCount > 0 ? deferred : new boolean[lod0]);
                            noteSplitsAlone(rootId, root, !fitsAlone);
                        }
                        return readWhole(
                                rootId, root, skipped, deferred, sized, interiors, fitsAlone);
                    }
                }
            }
            if (owner != null && deferredCount > 0 && interiorsOverAlone(root, budget, deferred)) {
                // Its exteriors fit an empty tile, but not with its interiors: drawn whole, every
                // tile listing it would cap interiors it could draw by group.
                return readByGroup(rootId, root, owner, skipped, deferred, sized, interiors);
            }
            if (sizeAlone) {
                noteSplitsAlone(
                        rootId,
                        root,
                        splitsAlone(
                                root,
                                exteriorsRead,
                                new boolean[lod0],
                                deferredCount > 0 ? deferred : new boolean[lod0]));
            }
            if (!budget.fitsTile()) {
                // Sized alone (an owner): it fits an empty tile, not what this one left. Drawn
                // whole, as every tile listing it decides, from the large allowance if it fits.
                return readWhole(rootId, root, skipped, deferred, sized, interiors, true);
            }
            var built = new ArrayList<Mesh>(pending.size());
            prefetchBuildingImages(pending);
            for (var mesh : pending) {
                token.check();
                var mapped = resolveBuilding(mesh, local);
                if (mapped == null) {
                    local[Count.GROUP_EMPTY.ordinal()]++;
                    continue;
                }
                countMaterials(mapped, local);
                local[Count.GROUP_DRAWN.ordinal()]++;
                built.add(mapped);
            }
            for (int i = 0; i < counts.length; i++) {
                counts[i] += local[i];
            }
            var held = interiors;
            if (built.isEmpty() && held.isEmpty() && deferredCount == 0) {
                count(Count.ROOT_EMPTY);
                return RootState.EMPTY;
            }
            if (!held.isEmpty() || deferredCount > 0) {
                // An interior-only root draws nothing until the interior pass; a model with no
                // meshes costs no draws and takes no building slot until then.
                heldInteriors.put(rootId, held);
            }
            rootDoodads.put(rootId, root.doodads());
            modelSlots.put(rootId, models.size());
            WmoPortalSet portals = null;
            if (deferredCount > 0) {
                // The portal set needs every group's header: it is decided after the reads.
                deferredInteriors.put(rootId, new DeferredInteriors(root, headers, deferred));
            } else if (this.interiors) {
                portals = portals(root, headers);
            }
            models.add(new Model(rootId, false, built, portals));
            count(Count.ROOT_DRAWN);
            return RootState.DRAWN;
        }

        /**
         * Whether the owner rule would draw a root by group when one of its border-crossing
         * placements decides it: its exterior groups read so far ({@code exteriors}) over an empty
         * tile's building pool, or over it with the exteriors not yet read ({@code later}, {@link
         * #laterOverAlone}), or, with deferred MOGI interiors ({@code deferred}), over it with them
         * too ({@link #interiorsOverAlone}, under the same read bound). The same sizing a neighbour
         * deciding that placement does, so the tiles agree on whether it is drawn whole or by
         * group.
         */
        private boolean splitsAlone(
                WmoFormats.Root root,
                List<PendingMesh> exteriors,
                boolean[] later,
                boolean[] deferred) {
            var alone = new RootBudget(Allowance.ALONE);
            int[] scratch = new int[counts.length];
            // An empty pool is looser than what the tile's pool left for opaque geometry, but not
            // for cutout or blended groups: the tile drops one over its material budget, an empty
            // pool's sizing counts it (within one more set of both budgets), so exteriors that fit
            // the tile can still be over here.
            for (var mesh : exteriors) {
                if (alone.admit(mesh, scratch) == Admission.OVER) {
                    return true;
                }
            }
            if (laterOverAlone(root, alone, later)) {
                return true;
            }
            for (boolean d : deferred) {
                if (d) {
                    return interiorsOverAlone(root, alone, deferred);
                }
            }
            return false;
        }

        /**
         * Reads the exterior groups in {@code later} for their size only and admits each against
         * {@code budget} as the owner rule's sizing does: a group whose MOGP flags say interior is
         * held there, not admitted, so it is not charged here either. The budget keeps what they
         * took, for the deferred interiors after them.
         */
        private boolean laterOverAlone(WmoFormats.Root root, RootBudget budget, boolean[] later) {
            int[] scratch = new int[counts.length];
            var headers = new WmoFormats.GroupHeader[later.length];
            try {
                for (int g = 0; g < later.length; g++) {
                    if (later[g]) {
                        admission.prefetch(root.gfid(g), WorldStreamScopes.DerivedKind.WMO_GROUP);
                    }
                }
                for (int g = 0; g < later.length; g++) {
                    if (!later[g]) {
                        continue;
                    }
                    token.check();
                    sizingReads++;
                    var mesh = readGroup(g, root.gfid(g), root, scratch, headers, false);
                    if (mesh != null
                            && (mesh.flags() & INTERIOR) == 0
                            && budget.admit(mesh, scratch) == Admission.OVER) {
                        return true;
                    }
                }
                return false;
            } finally {
                admission.cancelPrefetch();
            }
        }

        /** Records the owner rule's sizing of a root decided by a placement inside the tile. */
        private void noteSplitsAlone(long rootId, WmoFormats.Root root, boolean splits) {
            if (splits) {
                splitsAlone.add(rootId);
                byGroupRootFacts.put(rootId, root);
            }
        }

        /**
         * Whether the MOGI-interior groups a border-crossing root defers on an open-world map
         * ({@code deferred}) take its sizing ({@code budget}, its exteriors already admitted
         * against an empty tile's building pool) over that pool (#383): an interior there is
         * charged as an exterior would be. Each is read for its size only, in order until one is
         * over: its copy, counts, header and doodad references are discarded, so a root that still
         * fits draws exactly as before, its interiors read again in {@link #readInteriors}, and a
         * root drawn by group reads its owned interiors again in {@link #byGroupInteriors}. The
         * decision reads only the root, so every tile listing the placement agrees: a root with
         * more than {@link #MAX_SIZING_READS} interiors is sized by its exteriors alone, whatever
         * other roots the tile sized; a missing, protected or over-limit interior
         * (SOURCE_UNAVAILABLE, a property of the source) is left out of the size; and an interior
         * not readable now (SOURCE_INPUT) fails the tile as an exterior read does, so it is
         * prepared again later rather than deciding differently from its neighbours. The reads are
         * not charged to the tile's group reads. The budget is left as it was.
         */
        private boolean interiorsOverAlone(
                WmoFormats.Root root, RootBudget budget, boolean[] deferred) {
            int reads = 0;
            for (boolean d : deferred) {
                reads += d ? 1 : 0;
            }
            if (reads > MAX_SIZING_READS) {
                return false;
            }
            long[] mark = budget.mark();
            int[] scratch = new int[counts.length];
            var headers = new WmoFormats.GroupHeader[deferred.length];
            // Outside the interior pass: a SOURCE_INPUT refusal fails the tile (see above).
            try {
                for (int g = 0; g < deferred.length; g++) {
                    if (deferred[g]) {
                        admission.prefetch(root.gfid(g), WorldStreamScopes.DerivedKind.WMO_GROUP);
                    }
                }
                for (int g = 0; g < deferred.length; g++) {
                    if (!deferred[g]) {
                        continue;
                    }
                    token.check();
                    sizingReads++;
                    var mesh = readGroup(g, root.gfid(g), root, scratch, headers, false);
                    if (mesh != null && budget.admit(mesh, scratch) == Admission.OVER) {
                        return true;
                    }
                }
                return false;
            } finally {
                budget.reset(mark);
                admission.cancelPrefetch();
            }
        }

        /**
         * A root to be drawn whole that does not fit what the tile's building pool left: a root in
         * one tile (over what the pool left, or over an empty pool), or a border-crossing one that
         * fits an empty tile but not this one. It goes to the large allowance as a root drawn by
         * group whose rule owns every group ({@link Owner#whole}), so its groups, interiors and set
         * doodads are read, admitted and placed as there, after every split root's exteriors
         * ({@link #placeByGroup}). A border-crossing root is still decided whole from the root
         * alone, as on every tile that lists it; only the allowance that pays depends on this tile.
         * {@code fitsAlone}: the root is known to fit an empty tile's building pool (a border-
         * crossing one sized alone), so its border-crossing placements are drawn whole by every
         * tile listing them; otherwise such a placement may be split by a neighbour and is not
         * drawn whole here ({@link #wholeFitsAlone}).
         */
        private RootState readWhole(
                long rootId,
                WmoFormats.Root root,
                boolean[] skipped,
                boolean[] deferred,
                Sized[] sized,
                List<PendingMesh> held,
                boolean fitsAlone) {
            var whole = Owner.whole(admission.recorded().x(), admission.recorded().y());
            wholeOwners.put(rootId, whole);
            if (fitsAlone) {
                wholeFitsAlone.add(rootId);
            }
            return readByGroup(rootId, root, whole, skipped, deferred, sized, held);
        }

        /**
         * Classifies the LOD0 groups of a root drawn by group ({@link Owner}). Every group another
         * tile owns is GROUP_OTHER_TILE and is never read here. An owned exterior group the sizing
         * pass read keeps that copy and its counts, so no group is read or charged twice; the
         * others are read by {@link #placeByGroup}, after everything else the tile draws. With
         * interiors, an owned group MOGI marks interior waits for placeByGroup too (its copy kept
         * when the sizing pass read it, on instance maps); it is drawn only with the root's portal
         * set, which describes every group from MOGI. A group read as exterior whose MOGP flags say
         * interior while MOGI does not is not drawn (GROUP_INTERIOR_NO_PORTALS): the set would call
         * it exterior and never cull it. A root none of whose groups is this tile's is
         * ROOT_OTHER_TILE. {@code held} are the interior copies the sizing pass held: all leave the
         * shared held-copy bound here. An owned MOGI-interior group the sizing pass never reached
         * (instance maps) waits for the interiors too.
         */
        private RootState readByGroup(
                long rootId,
                WmoFormats.Root root,
                Owner owner,
                boolean[] skipped,
                boolean[] deferred,
                Sized[] sized,
                List<PendingMesh> held) {
            admission.cancelPrefetch(); // the sizing pass's unread hints
            byGroupRootFacts.put(rootId, root);
            var owned = owner.owned(root);
            int lod0 = owned.length;
            boolean any = false;
            int[] local = new int[counts.length];
            var ready = new PendingMesh[lod0];
            var unread = new boolean[lod0];
            var readyInterior = new PendingMesh[lod0];
            var unreadInterior = new boolean[lod0];
            for (int g = 0; g < lod0; g++) {
                if (root.gfid(g) == 0) {
                    local[Count.GROUP_UNSUPPORTED.ordinal()]++;
                } else if (!owned[g]) {
                    local[Count.GROUP_OTHER_TILE.ordinal()]++;
                } else {
                    any = true;
                    if (skipped[g]) {
                        local[Count.GROUP_INTERIOR.ordinal()]++;
                    } else if (deferred[g]) {
                        unreadInterior[g] = true;
                    } else if (sized[g] == null) {
                        // An interior the sizing pass never reached (instance maps) waits for
                        // the interiors, as a deferred one.
                        if (interiors && mogiInterior(root, g)) {
                            unreadInterior[g] = true;
                        } else {
                            unread[g] = true;
                        }
                    } else {
                        // Its read's own counts (colours, windings, a failed or dropped read).
                        for (int i = 0; i < local.length; i++) {
                            local[i] += sized[g].counts()[i];
                        }
                        if (!sized[g].interior()) {
                            ready[g] = sized[g].mesh();
                        } else if (!mogiInterior(root, g)) {
                            local[interiorNotDrawn().ordinal()]++;
                        } else if (sized[g].mesh() == null) {
                            local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        } else {
                            readyInterior[g] = sized[g].mesh();
                        }
                    }
                }
            }
            // Every held copy leaves the shared bound now, so the tile's later roots can still
            // hold theirs; the kept ones are bounded by this root's sizing pass.
            releaseInteriors(held);
            if (!any) {
                for (int i = 0; i < counts.length; i++) {
                    counts[i] += local[i];
                }
                count(Count.ROOT_OTHER_TILE);
                return RootState.OTHER_TILE;
            }
            byGroupReads.put(
                    rootId, new ByGroup(root, ready, unread, readyInterior, unreadInterior, local));
            return RootState.BY_GROUP;
        }

        /**
         * The interior read pass (#383): after every building's exterior groups were read and
         * admitted, each drawn root's MOGI-interior groups, roots in admission order (largest
         * first). A root's interiors are read whole or not at all, from what is left of the tile's
         * group reads (MAX_GROUPS): one that does not fit is skipped, its groups counted
         * GROUP_INTERIOR_CAPPED and its exterior drawn as without interiors, and smaller roots
         * after it may still fit. Read groups are held for {@link #placeInteriors}. The root's
         * portal set is decided here, once every group's header is known.
         */
        private void readInteriors() {
            if (deferredInteriors.isEmpty()) {
                return;
            }
            var placed = new java.util.HashSet<Integer>();
            for (var placement : placements) {
                placed.add(placement.model());
            }
            for (var entry : deferredInteriors.entrySet()) {
                token.check();
                long rootId = entry.getKey();
                var pending = entry.getValue();
                Integer slot = modelSlots.get(rootId);
                if (slot == null) {
                    continue; // removed by fitMaterialDraws, its root and placements counted there
                }
                int m = slot;
                int[] local = new int[counts.length];
                var held = heldInteriors.get(rootId);
                int reads = 0;
                for (int g = 0; g < pending.deferred().length; g++) {
                    if (pending.deferred()[g]) {
                        reads++;
                    }
                }
                // A root with no drawn placement could never show its interiors: never read.
                if (!placed.contains(m) || reads > MAX_GROUPS - groupsRead) {
                    local[Count.GROUP_INTERIOR_CAPPED.ordinal()] += reads;
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] += local[i];
                    }
                    if (models.get(m).meshes().isEmpty() && held.isEmpty()) {
                        interiorReadsCapped.add(rootId);
                    }
                    continue;
                }
                var root = pending.root();
                interiorPass = true;
                try {
                    for (int g = 0; g < pending.deferred().length; g++) {
                        if (pending.deferred()[g]) {
                            admission.prefetch(
                                    root.gfid(g), WorldStreamScopes.DerivedKind.WMO_GROUP);
                        }
                    }
                    for (int g = 0; g < pending.deferred().length; g++) {
                        if (!pending.deferred()[g]) {
                            continue;
                        }
                        token.check();
                        groupsRead++;
                        var mesh = readGroup(g, root.gfid(g), root, local, pending.headers());
                        if (mesh == null) {
                            continue;
                        }
                        // Whatever its own MOGP flags say, a MOGI-interior group is admitted as
                        // an interior: only from what every exterior left.
                        if (!holdInterior(mesh)) {
                            local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        } else {
                            held.add(mesh);
                        }
                    }
                } finally {
                    interiorPass = false;
                    admission.cancelPrefetch();
                }
                for (int i = 0; i < counts.length; i++) {
                    counts[i] += local[i];
                }
                var model = models.get(m);
                models.set(
                        m,
                        new Model(rootId, false, model.meshes(), portals(root, pending.headers())));
            }
            deferredInteriors.clear();
        }

        /**
         * Hints, in the order {@link #resolveBuilding} reads them for {@code meshes}, the images it
         * will register: per mesh its opaque ranges' images, then, unless materials are skipped,
         * its cutout and blended ones; never one the tile already registered in that class, nor
         * more than each pool's image cap leaves. One the material budgets then leave unread is
         * only read for nothing.
         */
        private void prefetchBuildingImages(List<PendingMesh> meshes) {
            var hinted = new java.util.HashSet<Long>();
            // As image(): a root drawn by group registers its opaque images in the large pool.
            int opaqueRoom =
                    charge != Charge.BUILDING
                            ? MAX_LARGE_IMAGES + MAX_LARGE_INTERIOR_IMAGES - largeImagesRead
                            : MAX_IMAGES - imagesRead;
            int materialRoom = MAX_MATERIAL_IMAGES - materialImagesRead;
            // Every hint's room is the pools' together: each image consumed from now on spends one.
            int room = Math.max(0, opaqueRoom) + Math.max(0, materialRoom);
            for (var mesh : meshes) {
                for (int pass = 0; pass < 2; pass++) {
                    for (var range : mesh.ranges()) {
                        boolean opaque = kind(range.blend()) == OPAQUE;
                        if (opaque != (pass == 0) || !opaque && skipMaterials) {
                            continue;
                        }
                        long id = range.image();
                        long key = id * 3 + (opaque ? OPAQUE : imageClass(range.blend()));
                        if ((opaque ? imageSlots : materialImageSlots).containsKey(key)
                                || (opaque ? opaqueRoom : materialRoom) <= 0
                                || !hinted.add(key)) {
                            continue;
                        }
                        if (opaque) {
                            opaqueRoom--;
                        } else {
                            materialRoom--;
                        }
                        admission.prefetchOnce(id, WorldStreamScopes.DerivedKind.WMO_IMAGE, room);
                    }
                }
            }
        }

        /**
         * Opaque ranges first, in recorded order and against the opaque budget, so the opaque
         * outcome is the opaque-only profile's. Cutout and blended ranges follow, each only if it
         * fits the material budget; a missing, unreadable or capped one omits only itself. The
         * mesh's vertices are charged to the opaque budget when an opaque range is kept, and to the
         * material budget otherwise. Null when nothing is kept.
         */
        private Mesh resolveBuilding(PendingMesh mesh, int[] local) {
            var kept = new ArrayList<DrawRange>();
            var compact = new int[mesh.indices().length];
            int used = 0, vertexCount = mesh.vertices().length / VERTEX_FLOATS;
            for (var range : mesh.ranges()) {
                if (kind(range.blend()) != OPAQUE) {
                    continue;
                }
                int slot = image(range.image(), OPAQUE, Pool.BUILDING, local);
                if (slot < 0) {
                    local[
                            (slot == IMAGE_FULL
                                            ? Count.BATCH_IMAGE_CAPPED
                                            : Count.BATCH_MISSING_IMAGE)
                                    .ordinal()]++;
                    continue;
                }
                System.arraycopy(
                        mesh.indices(), range.firstIndex(), compact, used, range.indexCount());
                // The range class keeps its flags (an unlit opaque surface stays unlit).
                kept.add(
                        new DrawRange(
                                used, range.indexCount(), slot, range.material(), range.blend()));
                used += range.indexCount();
                local[Count.BATCH_DRAWN.ordinal()]++;
            }
            boolean opaqueVertices = !kept.isEmpty();
            if (opaqueVertices) {
                switch (charge) {
                    case BUILDING -> {
                        vertices += vertexCount;
                        indices += used;
                        ranges += kept.size();
                    }
                    case LARGE -> {
                        largeVertices += vertexCount;
                        largeIndices += used;
                        largeRanges += kept.size();
                    }
                }
            }
            boolean materialVerticesCharged = false;
            for (var range : mesh.ranges()) {
                if (kind(range.blend()) == OPAQUE) {
                    continue;
                }
                int extraVertices = opaqueVertices || materialVerticesCharged ? 0 : vertexCount;
                if (skipMaterials
                        || extraVertices > MAX_MATERIAL_VERTICES - materialVertices
                        || range.indexCount() > MAX_MATERIAL_INDICES - materialIndices
                        || materialRanges >= MAX_MATERIAL_RANGES) {
                    local[Count.BATCH_CAPPED.ordinal()]++;
                    continue;
                }
                int slot = image(range.image(), imageClass(range.blend()), Pool.MATERIAL, local);
                if (slot < 0) {
                    local[
                            (slot == UNREADABLE
                                            ? Count.BATCH_UNREADABLE
                                            : slot == IMAGE_FULL
                                                    ? Count.BATCH_IMAGE_CAPPED
                                                    : Count.BATCH_MISSING_IMAGE)
                                    .ordinal()]++;
                    continue;
                }
                materialVertices += extraVertices;
                materialVerticesCharged |= extraVertices > 0;
                materialIndices += range.indexCount();
                materialRanges++;
                System.arraycopy(
                        mesh.indices(), range.firstIndex(), compact, used, range.indexCount());
                kept.add(
                        new DrawRange(
                                used, range.indexCount(), slot, range.material(), range.blend()));
                used += range.indexCount();
                local[Count.BATCH_DRAWN.ordinal()]++;
            }
            if (kept.isEmpty()) {
                return null;
            }
            return new Mesh(
                    mesh.ordinal(),
                    mesh.id(),
                    mesh.flags(),
                    mesh.vertices(),
                    mesh.colours(),
                    Arrays.copyOf(compact, used),
                    kept);
        }

        /** Decodes a pending mesh's images and compacts its kept ranges; null when none kept. */
        private Mesh resolve(PendingMesh mesh, Pool pool, int[] local, Count missing, Count drawn) {
            var kept = new ArrayList<DrawRange>();
            var compact = new int[mesh.indices().length];
            int used = 0;
            for (var range : mesh.ranges()) {
                int slot = image(range.image(), imageClass(range.blend()), pool, local);
                if (slot < 0) {
                    local[missing.ordinal()]++;
                    continue;
                }
                System.arraycopy(
                        mesh.indices(), range.firstIndex(), compact, used, range.indexCount());
                kept.add(
                        new DrawRange(
                                used, range.indexCount(), slot, range.material(), range.blend()));
                used += range.indexCount();
                local[drawn.ordinal()]++;
            }
            if (kept.isEmpty()) {
                return null;
            }
            return new Mesh(
                    mesh.ordinal(),
                    mesh.id(),
                    mesh.flags(),
                    mesh.vertices(),
                    mesh.colours(),
                    Arrays.copyOf(compact, used),
                    kept);
        }

        /**
         * The root's portal set when it can be carried whole within what is left of the tile's
         * portal tables (#383 step 3a); null, counted, otherwise. Null without declared portals.
         */
        private WmoPortalSet portals(WmoFormats.Root root, WmoFormats.GroupHeader[] headers) {
            var recorded = root.portals();
            if (!recorded.declared()) {
                return null;
            }
            WmoPortalSet set = null;
            if (recorded.retained() && Arrays.stream(headers).allMatch(Objects::nonNull)) {
                try {
                    set = WmoPortalSet.of(recorded, Arrays.asList(headers));
                } catch (IllegalArgumentException refused) {
                    // A MOGP range outside MOPR, or a value past the objects file's bound.
                    set = null;
                }
            }
            if (set == null || !portalsFit(set)) {
                count(Count.ROOT_PORTALS_DROPPED);
                return null;
            }
            chargePortals(set);
            return set;
        }

        /** Charges {@code set} to the tile's portal tables ({@link #portalsFit} first). */
        private void chargePortals(WmoPortalSet set) {
            portalSets++;
            portalGroups += set.groups().size();
            portalCount += set.portals().size();
            portalVertices += set.vertexCount();
            portalRefs += set.refs().size();
        }

        /** Whether {@code set} fits what is left of the tile's portal tables. */
        private boolean portalsFit(WmoPortalSet set) {
            return portalSets < WmoPortalSet.MAX_TILE_SETS
                    && set.groups().size() <= WmoPortalSet.MAX_TILE_GROUPS - portalGroups
                    && set.portals().size() <= WmoPortalSet.MAX_TILE_PORTALS - portalCount
                    && set.vertexCount() <= WmoPortalSet.MAX_TILE_VERTICES - portalVertices
                    && set.refs().size() <= WmoPortalSet.MAX_TILE_REFS - portalRefs;
        }

        private PendingMesh readGroup(
                int ordinal,
                long id,
                WmoFormats.Root root,
                int[] local,
                WmoFormats.GroupHeader[] headers) {
            return readGroup(ordinal, id, root, local, headers, true);
        }

        /**
         * As above; {@code keepRefs} false leaves the group's MODR out of the set doodad ties, for
         * a read that only sizes a root.
         */
        private PendingMesh readGroup(
                int ordinal,
                long id,
                WmoFormats.Root root,
                int[] local,
                WmoFormats.GroupHeader[] headers,
                boolean keepRefs) {
            var input = derived(id, WorldStreamScopes.DerivedKind.WMO_GROUP);
            if (input == null) {
                local[Count.GROUP_UNAVAILABLE.ordinal()]++;
                return null;
            }
            var result = new PendingMesh[1];
            var outcome = new Count[1];
            try (input) {
                WmoGroupSpans.withGroup(
                        input.bytes(),
                        token::isCanceled,
                        borrow -> {
                            headers[ordinal] = borrow.header();
                            if (keepRefs) {
                                keepDoodadRefs(root, ordinal, borrow.doodadRefs());
                            }
                            long flags = borrow.header().flags();
                            if ((flags & INTERIOR) != 0 && !interiors) {
                                outcome[0] = Count.GROUP_INTERIOR;
                                return;
                            }
                            if ((flags & (EXTERIOR | INTERIOR)) == 0) {
                                outcome[0] = Count.GROUP_UNSUPPORTED;
                                return;
                            }
                            result[0] = copyGroup(ordinal, id, flags, root, borrow, local);
                            if (result[0] == null) {
                                outcome[0] = Count.GROUP_UNSUPPORTED;
                            }
                        });
            } catch (IllegalArgumentException malformed) {
                local[Count.GROUP_UNSUPPORTED.ordinal()]++;
                return null;
            }
            if (outcome[0] != null) {
                local[outcome[0].ordinal()]++;
                return null;
            }
            if (result[0].ranges().isEmpty()) {
                local[Count.GROUP_EMPTY.ordinal()]++;
                return null;
            }
            return result[0];
        }

        /** Keeps group {@code ordinal}'s MODR for tying set doodads to their group. */
        private void keepDoodadRefs(WmoFormats.Root root, int ordinal, WmoGroupSpans.Indices refs) {
            var declared = root.doodads();
            int groups = (int) Math.min(root.header().groupCount(), WmoFormats.MAX_GROUPS);
            if (declared == null || ordinal >= groups) {
                return;
            }
            int defs = declared.definitions().size();
            if (refs == null || defs == 0) {
                return;
            }
            var only =
                    doodadGroups.computeIfAbsent(
                            declared,
                            ignored -> {
                                var fresh = new short[defs];
                                Arrays.fill(fresh, (short) -1);
                                return fresh;
                            });
            for (int i = 0; i < refs.size(); i++) {
                int d = refs.get(i);
                // References past the root's MODD name no doodad and are dropped.
                if (d < defs && only[d] != ordinal) {
                    only[d] = (short) (only[d] == -1 ? ordinal : -2);
                }
            }
        }

        /**
         * The interior group set doodad {@code ordinal} of {@code building} is tied to, or -1: the
         * group must be the only one read here whose MODR lists the doodad, and interior in the
         * building model's portal set. A doodad listed by several groups is never tied
         * (SET_DOODAD_GROUPS_SHARED), so it draws whenever it is in view. A root drawn by group
         * places a doodad inside an interior only while it draws that interior; any other root's
         * doodads are placed as before, so a tied doodad of an interior not drawn (capped) is now
         * culled with the room instead of always drawing.
         */
        private int portalGroup(Placement building, WmoFormats.Doodads declared, int ordinal) {
            if (building.model() < 0) {
                return -1;
            }
            var set = models.get(building.model()).portals();
            var groups = doodadGroups.get(declared);
            if (set == null || groups == null) {
                return -1;
            }
            int g = ordinal < groups.length ? groups[ordinal] : -1;
            if (g == -2) {
                count(Count.SET_DOODAD_GROUPS_SHARED);
                return -1;
            }
            if (g < 0
                    || g >= set.groups().size()
                    || (set.groups().get(g).flags() & INTERIOR) == 0) {
                return -1;
            }
            return g;
        }

        /**
         * Counts copied triangles whose winding agrees or disagrees with their vertex normals
         * (issue #375): the face normal (b - a) x (c - a) of raw local positions against the sum of
         * the three unit vertex normals. Triangles where the dot product is zero count in neither.
         */
        static void winding(float[] vertices, int[] indices, int[] local) {
            int agrees = 0, opposes = 0;
            for (int t = 0; t + 2 < indices.length; t += 3) {
                int a = indices[t] * VERTEX_FLOATS,
                        b = indices[t + 1] * VERTEX_FLOATS,
                        c = indices[t + 2] * VERTEX_FLOATS;
                double ux = vertices[b] - vertices[a],
                        uy = vertices[b + 1] - vertices[a + 1],
                        uz = vertices[b + 2] - vertices[a + 2];
                double vx = vertices[c] - vertices[a],
                        vy = vertices[c + 1] - vertices[a + 1],
                        vz = vertices[c + 2] - vertices[a + 2];
                double fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
                double nx = vertices[a + 3] + vertices[b + 3] + vertices[c + 3];
                double ny = vertices[a + 4] + vertices[b + 4] + vertices[c + 4];
                double nz = vertices[a + 5] + vertices[b + 5] + vertices[c + 5];
                double dot = fx * nx + fy * ny + fz * nz;
                if (dot > 0) {
                    agrees++;
                } else if (dot < 0) {
                    opposes++;
                }
            }
            local[Count.TRIANGLE_WINDING_AGREES.ordinal()] += agrees;
            local[Count.TRIANGLE_WINDING_OPPOSES.ordinal()] += opposes;
        }

        /**
         * Copies one group (exterior, or an interior lit from its vertex colours); null
         * (unsupported) for unusable normals or overlapping batches.
         */
        private PendingMesh copyGroup(
                int ordinal,
                long id,
                long flags,
                WmoFormats.Root root,
                WmoGroupSpans.Borrow borrow,
                int[] local) {
            // Overlapping recorded batches would draw triangles twice; refuse the whole group.
            var ordered = new ArrayList<>(borrow.batches());
            ordered.sort(java.util.Comparator.comparingLong(WmoFormats.Batch::firstIndex));
            long end = 0;
            for (var batch : ordered) {
                if (batch.firstIndex() < end) {
                    return null;
                }
                end = batch.firstIndex() + batch.indexCount();
            }
            var candidates = new ArrayList<Candidate>();
            int unsupported = 0, candidateIndices = 0, ignoredFlags = 0;
            var materials = root.materials();
            for (var batch : borrow.batches()) {
                // Index ranges and vertex domains are already validated by WmoGroupSpans.
                int index = batch.materialIndex();
                if (index < 0 || index >= materials.size()) {
                    unsupported++;
                    continue;
                }
                var material = materials.get(index);
                // MOMT blend mode (EGxBlend): 0 opaque, 1 alpha key (cutout), 2 alpha (blended),
                // 3 add (additive). Higher modes (modulate and their variants) stay refused until
                // their behaviour is pinned (#367).
                // The shared MOMT mapping (#297): blend mode word 2, flags word 0.
                int blend = WmoMaterialClass.of(material.word(0), material.word(2));
                if (blend == WmoMaterialClass.UNSUPPORTED || material.word(3) == 0) {
                    unsupported++;
                    continue;
                }
                if (WmoMaterialClass.ignoresFlags(material.word(0))) {
                    ignoredFlags++;
                }
                candidates.add(
                        new Candidate(
                                (int) batch.firstIndex(),
                                batch.indexCount(),
                                index,
                                material.word(3),
                                blend));
                candidateIndices += batch.indexCount();
            }
            if (candidates.isEmpty()) {
                // Nothing drawable: no vertex or index copy is made for this group.
                local[Count.BATCH_UNSUPPORTED.ordinal()] += unsupported;
                return new PendingMesh(
                        ordinal, id, flags, new float[0], new int[0], new int[0], List.of());
            }
            var positions = borrow.positions();
            var normals = borrow.normals();
            var uv = borrow.uv();
            int count = positions.size() / 3;
            float[] vertices = new float[count * VERTEX_FLOATS];
            for (int v = 0; v < count; v++) {
                if ((v & 1023) == 0) {
                    token.check();
                }
                double a = normals.get(v * 3), b = normals.get(v * 3 + 1);
                double c = normals.get(v * 3 + 2);
                double length = StrictMath.sqrt(a * a + b * b + c * c);
                if (!(length > 1e-6) || !Double.isFinite(length)) {
                    return null;
                }
                int at = v * VERTEX_FLOATS;
                vertices[at] = positions.get(v * 3);
                vertices[at + 1] = positions.get(v * 3 + 1);
                vertices[at + 2] = positions.get(v * 3 + 2);
                vertices[at + 3] = (float) (a / length);
                vertices[at + 4] = (float) (b / length);
                vertices[at + 5] = (float) (c / length);
                vertices[at + 6] = uv.get(v * 2);
                vertices[at + 7] = uv.get(v * 2 + 1);
            }
            // MOGP 0x4 declares the first MOCV: B, G, R, A per vertex, its count already checked.
            // An exterior group carries it as an addition to the World light (#375). An interior
            // group's MOCV is its whole light (#383): its ranges are drawn with INTERIOR_LIGHT
            // instead of the sun. The MOHD ambient is not added: the client's FixColorVertexAlpha
            // is understood to subtract it from interior MOCV at load and its shader to add it
            // back, so the raw MOCV already holds the full light. That adjustment's alpha term
            // and the MOHD flags 0x2 and 0x8 are not applied (#474). A group flagged both
            // exterior (0x8) and interior (0x2000) is lit as interior, as interior wins
            // elsewhere; the client may light it as outdoor. Interiors are not counted in the
            // exterior colour or winding evidence.
            boolean exterior = (flags & INTERIOR) == 0;
            int[] colours = new int[0];
            var mocv = borrow.colors();
            if ((flags & VERTEX_COLOURS) != 0 && mocv != null) {
                colours = new int[count];
                for (int v = 0; v < count; v++) {
                    colours[v] =
                            packColour(mocv.get(v * 4), mocv.get(v * 4 + 1), mocv.get(v * 4 + 2));
                }
            }
            int lightFlag = !exterior && colours.length > 0 ? INTERIOR_LIGHT : 0;
            // Only candidate batch indices are copied, compacted in recorded batch order.
            var source = borrow.indices();
            int[] indices = new int[candidateIndices];
            var rebased = new ArrayList<Candidate>(candidates.size());
            int used = 0;
            for (var candidate : candidates) {
                token.check();
                for (int i = 0; i < candidate.indexCount(); i++) {
                    indices[used + i] = source.get(candidate.firstIndex() + i);
                }
                rebased.add(
                        new Candidate(
                                used,
                                candidate.indexCount(),
                                candidate.material(),
                                candidate.image(),
                                candidate.blend() | lightFlag));
                used += candidate.indexCount();
            }
            if (exterior) {
                winding(vertices, indices, local);
            }
            local[Count.BATCH_UNSUPPORTED.ordinal()] += unsupported;
            local[Count.BATCH_FLAGS_IGNORED.ordinal()] += ignoredFlags;
            if (colours.length > 0) {
                local[
                        (exterior ? Count.GROUP_VERTEX_COLOURS : Count.GROUP_INTERIOR_LIGHT)
                                .ordinal()]++;
            }
            return new PendingMesh(ordinal, id, flags, vertices, colours, indices, rebased);
        }

        /**
         * Reads one doodad model: the M2, its first SKIN and the texture1 of each admitted batch.
         * Static bind pose only; colour, transparency and UV animation tracks are not evaluated.
         */
        private RootState readModel(long id) {
            modelsRead++;
            M2Types.Model model;
            var input = derived(id, WorldStreamScopes.DerivedKind.M2_MODEL);
            if (input == null) {
                count(Count.MODEL_UNAVAILABLE);
                return RootState.FAILED;
            }
            long parsing = WorldStageTimings.start();
            try (input) {
                byte[] raw = input.bytes();
                model = await(modelReader.read(id, raw, token));
                WorldStageTimings.stop("objects.m2.modelParse", parsing);
            } catch (ModelFormatException malformed) {
                count(modelOutcome(malformed));
                return RootState.FAILED;
            } catch (RuntimeException failure) {
                return readerFailed(failure);
            }
            if (model.skinFileDataIds().isEmpty() || model.skinFileDataIds().getFirst() <= 0) {
                count(Count.MODEL_UNSUPPORTED);
                return RootState.FAILED;
            }
            long skinId = model.skinFileDataIds().getFirst();
            M2Types.Skin skin;
            var skinInput = derived(skinId, WorldStreamScopes.DerivedKind.M2_SKIN);
            if (skinInput == null) {
                count(Count.MODEL_UNAVAILABLE);
                return RootState.FAILED;
            }
            long skinning = WorldStageTimings.start();
            try (skinInput) {
                byte[] raw = skinInput.bytes();
                skin = await(M2SkinReader.read(AssetSource.bytes(raw), model, MODEL_LIMITS, token));
                WorldStageTimings.stop("objects.m2.skinParse", skinning);
            } catch (ModelFormatException malformed) {
                count(modelOutcome(malformed));
                return RootState.FAILED;
            } catch (RuntimeException failure) {
                return readerFailed(failure);
            }
            int[] local = new int[counts.length];
            PendingMesh pending;
            try {
                pending = copyModel(id, skinId, model, skin, local, token);
            } catch (RuntimeException failure) {
                return readerFailed(failure);
            }
            if (pending == ALL_BILLBOARD) {
                mergeLocal(local);
                count(Count.MODEL_ALL_BILLBOARD);
                return RootState.FAILED;
            }
            if (pending == null) {
                count(Count.MODEL_UNSUPPORTED);
                return RootState.FAILED;
            }
            if (pending.ranges().isEmpty()) {
                mergeLocal(local);
                count(Count.MODEL_EMPTY);
                return RootState.EMPTY;
            }
            if (pending.vertices().length / VERTEX_FLOATS > MAX_DOODAD_VERTICES - doodadVertices
                    || pending.indices().length > MAX_DOODAD_INDICES - doodadIndices
                    || pending.ranges().size() > MAX_DOODAD_RANGES - doodadRanges) {
                count(Count.MODEL_CAPPED);
                return RootState.CAPPED;
            }
            var mesh =
                    resolve(
                            pending,
                            Pool.DOODAD,
                            local,
                            Count.MODEL_BATCH_MISSING_IMAGE,
                            Count.MODEL_BATCH_DRAWN);
            mergeLocal(local);
            if (mesh == null) {
                count(Count.MODEL_EMPTY);
                return RootState.EMPTY;
            }
            doodadVertices += mesh.vertexCount();
            doodadIndices += mesh.indices.length;
            doodadRanges += mesh.ranges.size();
            doodadSlots.put(id, models.size());
            models.add(new Model(id, true, List.of(mesh)));
            count(Count.MODEL_DRAWN);
            return RootState.DRAWN;
        }

        /**
         * Any other runtime failure while reading or copying one doodad model omits only that
         * model's doodads. This tile's own cancellation propagates; a source refusal from the scope
         * engine (a changed source) propagates too, as for every read.
         */
        private RootState readerFailed(RuntimeException failure) {
            if (failure instanceof WorldTerrainStreaming.Refusal refusal) {
                throw refusal;
            }
            token.check();
            count(Count.MODEL_FAILED);
            if (modelFailure == null) {
                modelFailure = failure;
            }
            return RootState.FAILED;
        }

        private void mergeLocal(int[] local) {
            for (int i = 0; i < counts.length; i++) {
                counts[i] += local[i];
            }
        }

        /**
         * One static mesh over the SKIN's vertex lookup, with one range per admitted batch; null
         * when an admitted section references a zero or non-finite normal, the mesh exceeds the
         * 16-bit index domain; {@link #ALL_BILLBOARD} when billboard bones leave no admitted
         * triangle. Triangles with a vertex weighted to a billboard bone are left out; a batch left
         * with none is counted as MODEL_BATCH_BILLBOARD, one left with some as MODEL_BATCH_TRIMMED.
         * Checks {@code cancel}: the tile's own, or on the read-ahead thread the read's, which the
         * tile's cancels too.
         */
        private PendingMesh copyModel(
                long id,
                long skinId,
                M2Types.Model model,
                M2Types.Skin skin,
                int[] local,
                Cancellation cancel) {
            var lookup = skin.vertexLookup();
            var triangles = skin.triangleLookup();
            var sections = skin.sections();
            if (lookup.isEmpty() || lookup.size() > 65_536) {
                return null;
            }
            var candidates = new ArrayList<Candidate>();
            var seen = new boolean[sections.size()];
            int candidateIndices = 0;
            for (var batch : skin.batches()) {
                cancel.check();
                int section = batch.sectionIndex();
                if (batch.materialLayer() != 0
                        || batch.textureCount() < 1
                        || section < 0
                        || section >= sections.size()
                        || seen[section]
                        || batch.materialIndex() < 0
                        || batch.materialIndex() >= model.materials().size()
                        || batch.textureComboIndex() < 0
                        || batch.textureComboIndex() >= model.textureCombos().size()
                        || !firstTextureUsesUv0(batch.shaderId(), batch.textureCount())) {
                    local[Count.MODEL_BATCH_UNSUPPORTED.ordinal()]++;
                    continue;
                }
                // M2 blend mode: 0 opaque, 1 alpha key (cutout), 2 alpha (blended).
                int blend = model.materials().get(batch.materialIndex()).blendMode();
                int ordinal = model.textureCombos().get(batch.textureComboIndex());
                var texture =
                        ordinal < model.textures().size() ? model.textures().get(ordinal) : null;
                var range = sections.get(section);
                if (blend > BLENDED
                        || texture == null
                        || texture.type() != 0
                        || texture.fileDataId() == null
                        || texture.fileDataId() <= 0
                        || range.triangleCount() == 0
                        || range.triangleCount() % 3 != 0
                        || range.triangleStart() % 3 != 0
                        || range.triangleStart() + range.triangleCount() > triangles.size()) {
                    local[Count.MODEL_BATCH_UNSUPPORTED.ordinal()]++;
                    continue;
                }
                seen[section] = true;
                candidates.add(
                        new Candidate(
                                (int) range.triangleStart(),
                                range.triangleCount(),
                                batch.materialIndex(),
                                texture.fileDataId(),
                                blend));
                candidateIndices += range.triangleCount();
            }
            if (candidates.isEmpty()) {
                return new PendingMesh(
                        0, skinId, 0, new float[0], new int[0], new int[0], List.of());
            }
            var source = model.vertices();
            var billboard = billboardBones(model.bones());
            boolean[] facing = null;
            if (!billboard.isEmpty()) {
                facing = new boolean[lookup.size()];
                for (int v = 0; v < lookup.size(); v++) {
                    int global = lookup.get(v);
                    facing[v] =
                            global >= 0
                                    && global < source.size()
                                    && billboardWeighted(source.get(global), billboard);
                }
            }
            float[] vertices = new float[lookup.size() * VERTEX_FLOATS];
            boolean[] referenced = new boolean[lookup.size()];
            int[] indices = new int[candidateIndices];
            var rebased = new ArrayList<Candidate>(candidates.size());
            int used = 0, billboarded = 0, trimmed = 0, dropped = 0;
            for (var candidate : candidates) {
                cancel.check();
                int kept = 0;
                for (int i = 0; i < candidate.indexCount(); i += 3) {
                    boolean faces = false;
                    for (int k = 0; k < 3; k++) {
                        int local16 = triangles.get(candidate.firstIndex() + i + k);
                        if (local16 < 0 || local16 >= lookup.size()) {
                            return null;
                        }
                        faces |= facing != null && facing[local16];
                    }
                    if (faces) {
                        dropped++;
                        continue;
                    }
                    for (int k = 0; k < 3; k++) {
                        int local16 = triangles.get(candidate.firstIndex() + i + k);
                        indices[used + kept++] = local16;
                        referenced[local16] = true;
                    }
                }
                if (kept == 0) {
                    billboarded++;
                    continue;
                }
                if (kept < candidate.indexCount()) {
                    trimmed++;
                }
                rebased.add(
                        new Candidate(
                                used,
                                kept,
                                candidate.material(),
                                candidate.image(),
                                candidate.blend()));
                used += kept;
            }
            local[Count.MODEL_BATCH_BILLBOARD.ordinal()] += billboarded;
            local[Count.MODEL_BATCH_TRIMMED.ordinal()] += trimmed;
            local[Count.MODEL_TRIANGLE_BILLBOARD.ordinal()] += dropped;
            if (rebased.isEmpty()) {
                return ALL_BILLBOARD; // every admitted triangle billboards
            }
            if (used < indices.length) {
                indices = Arrays.copyOf(indices, used);
            }
            for (int v = 0; v < lookup.size(); v++) {
                if ((v & 1023) == 0) {
                    cancel.check();
                }
                int global = lookup.get(v);
                if (global < 0 || global >= source.size()) {
                    return null;
                }
                var vertex = source.get(global);
                double a = vertex.normal().x(), b = vertex.normal().y(), c = vertex.normal().z();
                double length = StrictMath.sqrt(a * a + b * b + c * c);
                var p = vertex.position();
                if (!finite(p.x(), p.y(), p.z())
                        || !finite(vertex.uv0().x(), vertex.uv0().y(), 0)) {
                    return null;
                }
                if (!(length > 1e-6) || !Double.isFinite(length)) {
                    if (referenced[v]) {
                        return null;
                    }
                    a = 0;
                    b = 0;
                    c = length = 1; // unreferenced: never drawn
                }
                int at = v * VERTEX_FLOATS;
                vertices[at] = p.x();
                vertices[at + 1] = p.y();
                vertices[at + 2] = p.z();
                vertices[at + 3] = (float) (a / length);
                vertices[at + 4] = (float) (b / length);
                vertices[at + 5] = (float) (c / length);
                vertices[at + 6] = vertex.uv0().x();
                vertices[at + 7] = vertex.uv0().y();
            }
            return new PendingMesh(0, skinId, 0, vertices, new int[0], indices, rebased);
        }

        /**
         * Image slot for a texture1 FileDataID; MISSING when it is missing for any other reason,
         * UNREADABLE when a material-pool read was refused at SOURCE_INPUT.
         */
        private int image(long id, int materialClass, Pool pool, int[] local) {
            // Alpha is forced for opaque ranges and coverage-preserved down the chain for cutout
            // ones, so one ID may decode once per material class.
            long key = id * 3 + materialClass;
            var slots =
                    switch (pool) {
                        case BUILDING -> imageSlots;
                        case MATERIAL -> materialImageSlots;
                        case DOODAD -> modelImageSlots;
                    };
            var known = slots.get(key);
            if (known != null) {
                return known;
            }
            int slot = MISSING;
            // A root drawn by group registers its opaque images in its own slots, never the tile's.
            boolean large = pool == Pool.BUILDING && charge != Charge.BUILDING;
            boolean full =
                    switch (pool) {
                        case BUILDING ->
                                large
                                        ? largeImagesRead
                                                >= MAX_LARGE_IMAGES + MAX_LARGE_INTERIOR_IMAGES
                                        : imagesRead >= MAX_IMAGES;
                        case MATERIAL -> materialImagesRead >= MAX_MATERIAL_IMAGES;
                        case DOODAD ->
                                modelImagesRead
                                        >= MAX_MODEL_IMAGES
                                                + (interiorModelCharge
                                                        ? MAX_INTERIOR_MODEL_IMAGES
                                                        : 0);
                    };
            if (full) {
                local[
                        (pool == Pool.DOODAD ? Count.MODEL_IMAGE_CAPPED : Count.IMAGE_CAPPED)
                                .ordinal()]++;
                // Building batches report the budget, not a data problem; doodad batches keep
                // their missing-image count.
                slot = pool == Pool.DOODAD ? MISSING : IMAGE_FULL;
            } else {
                switch (pool) {
                    case BUILDING -> {
                        if (large) {
                            largeImagesRead++;
                        } else {
                            imagesRead++;
                        }
                    }
                    case MATERIAL -> materialImagesRead++;
                    case DOODAD -> modelImagesRead++;
                }
                slot = decode(id, materialClass, pool, local);
            }
            slots.put(key, slot);
            return slot;
        }

        /**
         * One registered image: its slot, pool, source, level-0 size, recorded mip count, the level
         * it was first decoded at and that level's pixels. The chain reuses those pixels when the
         * allotted level lies above the registered one, so no level is decoded twice (opaque pixels
         * already have alpha 255, which the opaque chain sets anyway).
         */
        private record Demand(
                int slot,
                Pool pool,
                long id,
                int materialClass,
                long width,
                long height,
                int recorded,
                int registered,
                byte[] registeredRgba,
                int blockFormat) {
            Demand withoutRegisteredRgba() {
                return new Demand(
                        slot,
                        pool,
                        id,
                        materialClass,
                        width,
                        height,
                        recorded,
                        registered,
                        null,
                        blockFormat);
            }
        }

        /**
         * Reads and decodes one image at the registration side; the uploaded level is chosen later
         * by {@link #allocateImages}. Missing, protected or undecodable images are counted here.
         */
        private int decode(long id, int materialClass, Pool pool, int[] local) {
            boolean opaque = materialClass == OPAQUE;
            boolean building = pool != Pool.DOODAD;
            var unavailable = building ? Count.IMAGE_UNAVAILABLE : Count.MODEL_IMAGE_UNAVAILABLE;
            var undecodable = building ? Count.IMAGE_UNDECODABLE : Count.MODEL_IMAGE_UNDECODABLE;
            boolean[] unreadable = new boolean[1];
            var input = derived(id, imageKind(pool), pool == Pool.MATERIAL ? unreadable : null);
            if (input == null) {
                local[unavailable.ordinal()]++;
                return unreadable[0] ? UNREADABLE : MISSING;
            }
            try (input) {
                byte[] raw = input.bytes();
                var header = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                if (raw.length < Blp2Decoder.HEADER_BYTES
                        || header.getInt(0) != 0x32504c42
                        || header.getInt(4) != 1) {
                    local[undecodable.ordinal()]++;
                    return -1;
                }
                long width = Integer.toUnsignedLong(header.getInt(12));
                long height = Integer.toUnsignedLong(header.getInt(16));
                int recorded;
                List<Blp2Types.Mip> declared = List.of();
                long mipsRead = WorldStageTimings.start();
                try {
                    declared =
                            Blp2Decoder.readMipsSynchronous(
                                    AssetSource.borrowed(raw),
                                    IMAGE_LIMITS,
                                    token,
                                    Blp2Types.MipProfile.STRICT);
                    recorded = WorldImageBudget.recordedLevels(declared);
                } catch (BlpFormatException undeclared) {
                    recorded = 0;
                } finally {
                    WorldStageTimings.stop("objects.blp.readMips", mipsRead);
                }
                if (width == 0 || height == 0 || width > 8192 || height > 8192 || recorded == 0) {
                    local[undecodable.ordinal()]++;
                    return -1;
                }
                // The registration level first, then every other recorded level with both sides
                // at most 256, so an image the 256-side profile could draw is never lost here.
                int preferred =
                        WorldImageBudget.levelFor(width, height, REGISTRATION_SIDE, recorded);
                int level = -1;
                Image image = null;
                for (int k = 0; k < recorded && image == null; k++) {
                    int candidate =
                            k <= recorded - 1 - preferred ? preferred + k : recorded - 1 - k;
                    image = decodeLevel(raw, id, width, height, candidate, opaque);
                    level = candidate;
                }
                if (image == null) {
                    local[undecodable.ordinal()]++;
                    return -1;
                }
                images.add(image);
                int slot = images.size() - 1;
                retain(id, raw);
                while (demands.size() < slot) {
                    demands.add(null);
                }
                demands.add(
                        new Demand(
                                slot,
                                pool,
                                id,
                                materialClass,
                                width,
                                height,
                                recorded,
                                level,
                                image.rgba,
                                blockFormat(raw, width, height, declared, materialClass)));
                local[(building ? Count.IMAGE_DECODED : Count.MODEL_IMAGE_DECODED).ordinal()]++;
                return slot;
            }
        }

        /**
         * The BC format this image can be uploaded in as the file's own blocks (issue #374), or -1.
         * The file must be DXT and record every level down to 1 x 1 in exact storage. Opaque
         * surfaces take BC1, BC2 or BC3 (alpha is not drawn); blended ones BC2 or BC3, because BC1
         * alpha is not sampled on every backend; cutout surfaces keep RGBA8, whose mips preserve
         * alpha-test coverage (#265).
         */
        static int blockFormat(
                byte[] raw, long width, long height, List<Blp2Types.Mip> declared, int material) {
            if (raw.length < Blp2Decoder.HEADER_BYTES || raw[8] != 2) {
                return -1;
            }
            int format =
                    blockFormatFor(
                            switch (raw[10] & 255) {
                                case 0 -> ObjectTextureFormats.BC1;
                                case 1 -> ObjectTextureFormats.BC2;
                                case 7 -> ObjectTextureFormats.BC3;
                                default -> -1;
                            },
                            material);
            if (format < 0) {
                return -1;
            }
            int levels = ObjectTextureMips.levels((int) width, (int) height);
            if (declared.size() < levels) {
                return -1;
            }
            var taken = new ArrayList<long[]>();
            for (int k = 0; k < levels; k++) {
                var mip = declared.get(k);
                int bytes = ObjectTextureFormats.levelBytes(format, (int) width, (int) height, k);
                // As blockChain will demand: past the header, inside the file, and no two levels
                // sharing bytes. Checked up front, so a planned chain rarely falls back (#374).
                if (mip.level() != k
                        || mip.storage() != Blp2Types.MipStorage.EXACT
                        || mip.byteLength() != bytes
                        || mip.offset() < Blp2Decoder.HEADER_BYTES
                        || mip.offset() + bytes > raw.length) {
                    return -1;
                }
                for (long[] range : taken) {
                    if (mip.offset() < range[1] && range[0] < mip.offset() + bytes) {
                        return -1;
                    }
                }
                taken.add(new long[] {mip.offset(), mip.offset() + bytes});
            }
            return format;
        }

        /**
         * The file's own blocks from {@code level} down to 1 x 1 as one packed chain, or null when
         * the source is not readable again or no longer matches (the RGBA8 path is used instead).
         */
        private byte[] blockChain(Demand demand, int level, int width, int height) {
            if (width < 4 || height < 4) {
                return null; // a compressed top level is at least one block (#420)
            }
            byte[] raw = source(demand);
            if (raw == null) {
                return null;
            }
            List<Blp2Types.Mip> declared;
            try {
                // Synchronous: the borrowed source is done with raw before the blocks are copied.
                declared =
                        Blp2Decoder.readMipsSynchronous(
                                AssetSource.borrowed(raw),
                                IMAGE_LIMITS,
                                token,
                                Blp2Types.MipProfile.STRICT);
            } catch (BlpFormatException changed) {
                return null;
            }
            int format = demand.blockFormat();
            byte[] chain = new byte[ObjectTextureFormats.chainBytes(format, width, height)];
            int at = 0;
            var taken = new ArrayList<long[]>();
            for (int k = 0; k < ObjectTextureMips.levels(width, height); k++) {
                int bytes = ObjectTextureFormats.levelBytes(format, width, height, k);
                if (level + k >= declared.size()) {
                    return null;
                }
                var mip = declared.get(level + k);
                // As the decoder demands: level data lies past the 148-byte header, inside the
                // file, and no two carried levels share bytes; otherwise the RGBA8 path decides.
                if (mip.byteLength() != bytes
                        || mip.offset() < Blp2Decoder.HEADER_BYTES
                        || mip.offset() + bytes > raw.length) {
                    return null;
                }
                for (long[] range : taken) {
                    if (mip.offset() < range[1] && range[0] < mip.offset() + bytes) {
                        return null;
                    }
                }
                taken.add(new long[] {mip.offset(), mip.offset() + bytes});
                System.arraycopy(raw, (int) mip.offset(), chain, at, bytes);
                at += bytes;
            }
            return chain;
        }

        private static WorldStreamScopes.DerivedKind imageKind(Pool pool) {
            return pool == Pool.DOODAD
                    ? WorldStreamScopes.DerivedKind.M2_IMAGE
                    : WorldStreamScopes.DerivedKind.WMO_IMAGE;
        }

        /** One recorded level with both sides at most 256, or null when it does not decode. */
        private Image decodeLevel(
                byte[] raw, long id, long width, long height, int level, boolean opaque) {
            if (Math.max(1, width >> level) > 256 || Math.max(1, height >> level) > 256) {
                return null;
            }
            Blp2Types.Image image;
            long decoding = WorldStageTimings.start();
            levelDecodes++;
            try {
                image =
                        Blp2Decoder.decodeSynchronous(
                                AssetSource.borrowed(raw),
                                level,
                                IMAGE_LIMITS,
                                token,
                                Blp2Types.MipProfile.STRICT);
            } catch (BlpFormatException undecodableImage) {
                return null;
            } finally {
                WorldStageTimings.stop("objects.blp.decodeLevel", decoding);
            }
            int w = image.width(), h = image.height();
            if (image.mipLevel() != level
                    || w != Math.max(1, width >> level)
                    || h != Math.max(1, height >> level)) {
                return null;
            }
            byte[] rgba = image.rgba();
            if (opaque) {
                for (int i = 3; i < rgba.length; i += 4) {
                    rgba[i] = (byte) 255;
                }
            }
            return new Image(id, w, h, level, rgba);
        }

        /**
         * Shares each pool's pixel budget among the images its kept ranges use, larger buildings
         * (earlier admission) first, and replaces each registered image with its allotted level:
         * the file's own mip when recorded, otherwise a 2x2 box reduction of the registered level.
         */
        private void allocateImages() {
            boolean[] used = new boolean[images.size()];
            boolean[] exterior = new boolean[images.size()];
            // A doodad model from the interior allowance that only tied set doodads place is
            // allotted with the interiors too, so it never lowers another doodad image's level
            // beyond its reserve.
            boolean[] untied = new boolean[models.size()];
            for (var placement : placements) {
                if (placement.model() >= 0) {
                    untied[placement.model()] |= placement.portalGroup() < 0;
                }
            }
            for (int m = 0; m < models.size(); m++) {
                var model = models.get(m);
                // A root drawn by group is allotted with the interiors, from what the tile's own
                // exteriors leave, so it never lowers one of their levels beyond its reserve.
                boolean byGroup = !model.doodad() && byGroupRoots.contains(model.rootId());
                boolean interiorModel =
                        model.doodad() && !untied[m] && interiorModels.contains(model.rootId());
                for (var mesh : model.meshes()) {
                    boolean interior = byGroup || interiorModel || interiorMeshes.contains(mesh);
                    for (var range : mesh.ranges()) {
                        used[range.image()] = true;
                        exterior[range.image()] |= !interior;
                    }
                }
            }
            interiorOnlySlots = new boolean[images.size()];
            for (int i = 0; i < used.length; i++) {
                interiorOnlySlots[i] = used[i] && !exterior[i];
            }
            // Exterior images first, from the pool less every interior-only image's smallest
            // level, then interior-only images from what they leave (#383): beyond that reserve an
            // interior never takes a level step an exterior could have had, and every interior
            // image still keeps at least its smallest level, as when they shared the pool.
            for (var pool : Pool.values()) {
                allocatePool(pool, exterior, interiorOnlySlots);
            }
            // Registered pixels are reused only when the drawn level lies above them; every other
            // demand lets them go now instead of holding them, unbudgeted, until the chains.
            releaseRegisteredPixels();
        }

        /** Allots levels to the pool's exterior demands, then to its interior-only ones. */
        private void allocatePool(Pool pool, boolean[] exterior, boolean[] interior) {
            var firstDemands = new ArrayList<Demand>();
            var secondDemands = new ArrayList<Demand>();
            var first = new ArrayList<WorldImageBudget.Request>();
            var second = new ArrayList<WorldImageBudget.Request>();
            for (var demand : demands) {
                if (demand == null || demand.pool() != pool) {
                    continue;
                }
                boolean isExterior = exterior[demand.slot()];
                if (!isExterior && !interior[demand.slot()]) {
                    continue;
                }
                (isExterior ? firstDemands : secondDemands).add(demand);
                boolean blocks = sampled(demand);
                (isExterior ? first : second)
                        .add(
                                new WorldImageBudget.Request(
                                        demand.width(),
                                        demand.height(),
                                        levelsDown(demand),
                                        blocks ? demand.blockFormat() : ObjectTextureFormats.RGBA8,
                                        blocks
                                                ? Integer.MAX_VALUE
                                                : pool == Pool.DOODAD
                                                        ? MODEL_RGBA_SIDE
                                                        : RGBA_SIDE));
            }
            long budget =
                    switch (pool) {
                        case BUILDING -> detail.buildingPixels();
                        case MATERIAL -> detail.materialPixels();
                        case DOODAD -> detail.doodadPixels();
                    };
            int[][] levels =
                    WorldImageBudget.allocateFirst(
                            first,
                            second,
                            budget,
                            pool == Pool.DOODAD ? MODEL_IMAGE_SIDES : IMAGE_SIDES,
                            INTERIOR_RESERVE_SIDE);
            relevelAll(firstDemands, levels[0]);
            relevelAll(secondDemands, levels[1]);
        }

        private void relevelAll(List<Demand> chosen, int[] levels) {
            for (int i = 0; i < chosen.size(); i++) {
                token.check();
                // Caps keep every pool far above its smallest sides; never drop here.
                require(levels[i] >= 0, "EXTERIOR_IMAGE_BUDGET");
                var demand = chosen.get(i);
                if (levels[i] != demand.registered()) {
                    images.set(demand.slot(), relevel(demand, levels[i]));
                }
            }
        }

        private void releaseRegisteredPixels() {
            for (int i = 0; i < demands.size(); i++) {
                var demand = demands.get(i);
                if (demand == null || demand.registeredRgba() == null) {
                    continue;
                }
                if (sampled(demand)) {
                    // A chain to be uploaded as sampled blocks keeps them until its blocks are
                    // read: they are its RGBA8 fallback should the blocks not be usable. Counted
                    // with the retained sources (at most 64 KiB each, at the 128 registration
                    // side), so sources yield to them, and dropped as soon as the chain is built.
                    retainedSourceBytes += demand.registeredRgba().length;
                    peakRetainedSourceBytes =
                            Math.max(peakRetainedSourceBytes, retainedSourceBytes);
                } else if (!(images.get(demand.slot()).mipLevel() < demand.registered())) {
                    demands.set(i, demand.withoutRegisteredRgba());
                }
            }
        }

        /**
         * Replaces every used image's final level with its full mip chain (#265): the file's own
         * mips below that level while it records them, generated after that, with the alpha
         * treatment of the image's material class.
         */
        private void chainImages() {
            boolean[] used = new boolean[images.size()];
            for (var model : models) {
                for (var mesh : model.meshes()) {
                    for (var range : mesh.ranges()) {
                        used[range.image()] = true;
                    }
                }
            }
            for (int d = 0; d < demands.size(); d++) {
                var demand = demands.get(d);
                if (demand == null || !used[demand.slot()]) {
                    continue;
                }
                token.check();
                var top = images.get(demand.slot());
                int level = top.mipLevel();
                if (demand.blockFormat() >= 0) {
                    byte[] blocks = blockChain(demand, level, top.width(), top.height());
                    if (blocks != null) {
                        releaseSampledRgba(d);
                        counts[Count.IMAGE_BLOCKS.ordinal()]++;
                        counts[Count.IMAGE_MIPS_FILE.ordinal()]++;
                        images.set(
                                demand.slot(),
                                new Image(
                                        top.id,
                                        top.width(),
                                        top.height(),
                                        level,
                                        blocks,
                                        ObjectTextureMips.Source.FILE,
                                        demand.blockFormat()));
                        continue;
                    }
                }
                // A sampled chain allotted under one 4 x 4 block was planned as RGBA8 from the
                // start
                // (relevel), so its blocks were never expected and it is no fallback.
                if (sampled(demand)
                        && (top.rgba == null || top.width() >= 4 && top.height() >= 4)) {
                    // The blocks were not usable after all: decode the largest RGBA8 level within
                    // the RGBA8 cap whose chain costs no more than the blocks were charged, so the
                    // fallback never spends past the tile's budget.
                    top =
                            relevelRgba(
                                    demand,
                                    blockRequest(demand)
                                            .rgbaLevelWithin(
                                                    level,
                                                    demand.pool() == Pool.DOODAD
                                                            ? MODEL_RGBA_SIDE
                                                            : RGBA_SIDE));
                    level = top.mipLevel();
                    counts[Count.IMAGE_BLOCKS_FALLBACK.ordinal()]++;
                }
                int chainLevel = level;
                // The file's own mips below the final level, when it records any: the registered
                // level from its first decode, the others decoded once each from the source,
                // which is fetched only when such a level is asked for.
                var file =
                        new Object() {
                            boolean fetched;
                            byte[] raw;

                            byte[] level(int at, int w, int h) {
                                if (at >= demand.recorded()) {
                                    return null;
                                }
                                if (at == demand.registered()) {
                                    byte[] kept = reusable(demand.registeredRgba(), w, h);
                                    if (kept != null) {
                                        return kept;
                                    }
                                }
                                if (!fetched) {
                                    fetched = true;
                                    raw = source(demand);
                                }
                                return raw == null ? null : fileMip(raw, at, w, h);
                            }
                        };
                var chain =
                        ObjectTextureMips.build(
                                top.width(),
                                top.height(),
                                top.rgba,
                                demand.materialClass(),
                                (below, w, h) -> file.level(chainLevel + below, w, h),
                                token::isCanceled);
                switch (chain.source()) {
                    case FILE -> counts[Count.IMAGE_MIPS_FILE.ordinal()]++;
                    case PARTIAL -> counts[Count.IMAGE_MIPS_PARTIAL.ordinal()]++;
                    case GENERATED -> counts[Count.IMAGE_MIPS_GENERATED.ordinal()]++;
                    case SINGLE -> {}
                }
                images.set(
                        demand.slot(),
                        new Image(
                                top.id,
                                top.width(),
                                top.height(),
                                level,
                                chain.rgba(),
                                chain.source()));
                releaseSampledRgba(d);
            }
        }

        /** The budget request a sampled chain was allotted under (its blocks' format). */
        private WorldImageBudget.Request blockRequest(Demand demand) {
            return new WorldImageBudget.Request(
                    demand.width(),
                    demand.height(),
                    levelsDown(demand),
                    demand.blockFormat(),
                    Integer.MAX_VALUE);
        }

        /** Drops the registration pixels a sampled demand kept for its fallback, once built. */
        private void releaseSampledRgba(int index) {
            var demand = demands.get(index);
            if (demand.registeredRgba() != null && sampled(demand)) {
                retainedSourceBytes -= demand.registeredRgba().length;
                demands.set(index, demand.withoutRegisteredRgba());
            }
        }

        /** The image's retained source bytes, or a second read when they were not kept. */
        private byte[] source(Demand demand) {
            byte[] kept = sources.get(demand.id());
            if (kept != null || unreadSources.contains(demand.id())) {
                return kept; // A refused second read is not tried again.
            }
            // A refused second read of a cutout or blended image keeps what the first read gave,
            // as the first read would have: it never costs the tile its set. Neither does one of
            // an image only interiors draw (#383).
            boolean interior =
                    demand.slot() < interiorOnlySlots.length && interiorOnlySlots[demand.slot()];
            boolean wasInteriorPass = interiorPass;
            interiorPass = wasInteriorPass || interior;
            WorldStreamScopes.Input input;
            try {
                input =
                        derived(
                                demand.id(),
                                imageKind(demand.pool()),
                                demand.pool() == Pool.MATERIAL ? new boolean[1] : null);
            } finally {
                interiorPass = wasInteriorPass;
            }
            if (input == null) {
                unreadSources.add(demand.id());
                return null;
            }
            try (input) {
                byte[] raw = input.bytes();
                retain(demand.id(), raw);
                return raw;
            }
        }

        /**
         * Keeps one file's source bytes while they fit the bound; counts each file once. The array
         * is kept as read, not copied: every source read allocates its own array, the input only
         * drops its reference on close, and nothing here writes to source bytes.
         */
        private void retain(long id, byte[] raw) {
            if (sources.containsKey(id) || retainedSourceBytes + raw.length > retainLimit) {
                return;
            }
            sources.put(id, raw);
            retainedSourceBytes += raw.length;
            peakRetainedSourceBytes = Math.max(peakRetainedSourceBytes, retainedSourceBytes);
        }

        /** The file's own mip {@code level}, or null when it does not decode at that size. */
        private byte[] fileMip(byte[] raw, int level, int width, int height) {
            long decoding = WorldStageTimings.start();
            levelDecodes++;
            try {
                var mip =
                        Blp2Decoder.decodeSynchronous(
                                AssetSource.borrowed(raw),
                                level,
                                IMAGE_LIMITS,
                                token,
                                Blp2Types.MipProfile.STRICT);
                return mip.mipLevel() == level && mip.width() == width && mip.height() == height
                        ? mip.rgba()
                        : null;
            } catch (BlpFormatException | IllegalArgumentException absent) {
                return null;
            } finally {
                WorldStageTimings.stop("objects.blp.fileMip", decoding);
            }
        }

        /** Every level down to 1x1: recorded levels come from the file, the rest are reduced. */
        private static int levelsDown(Demand demand) {
            int levels = 1;
            while (levels < 16
                    && (demand.width() >> (levels - 1) > 1
                            || demand.height() >> (levels - 1) > 1)) {
                levels++;
            }
            return Math.max(levels, demand.recorded());
        }

        /** Whether this image will be uploaded as blocks the session samples. */
        private boolean sampled(Demand demand) {
            return demand.blockFormat() >= 0
                    && (compressedFormats & (1 << demand.blockFormat())) != 0;
        }

        private Image relevel(Demand demand, int level) {
            int w = (int) Math.max(1, demand.width() >> level);
            int h = (int) Math.max(1, demand.height() >> level);
            if (sampled(demand) && w >= 4 && h >= 4) {
                // Uploaded as the file's blocks: no RGBA8 decode at this level (it may exceed the
                // RGBA8 sides); chainImages copies the blocks, or decodes a smaller level instead.
                return new Image(demand.id(), w, h, level, null);
            }
            return relevelRgba(demand, level);
        }

        private Image relevelRgba(Demand demand, int level) {
            var registered = images.get(demand.slot());
            if (registered.rgba == null) {
                // A blocks placeholder replaced the registration decode: rebuild it from the
                // pixels the demand kept (they are kept while the drawn level lies above them).
                int r = demand.registered();
                registered =
                        new Image(
                                demand.id(),
                                (int) Math.max(1, demand.width() >> r),
                                (int) Math.max(1, demand.height() >> r),
                                r,
                                Objects.requireNonNull(demand.registeredRgba()));
            }
            if (level < demand.recorded()) {
                byte[] raw = source(demand);
                if (raw != null) {
                    var image =
                            decodeLevel(
                                    raw,
                                    demand.id(),
                                    demand.width(),
                                    demand.height(),
                                    level,
                                    demand.materialClass() == OPAQUE);
                    if (image != null) {
                        return image;
                    }
                }
                if (level < demand.registered()) {
                    return registered; // a larger level is unavailable; keep the smaller one
                }
            }
            return reduce(registered, level - demand.registered());
        }

        /**
         * Halves the image {@code steps} times with a 2x2 box filter. Colour is weighted by alpha
         * (straight alpha), so transparent texels do not darken the edge; alpha is the plain mean.
         * An odd side truncates, like the halved level sizes: its last row or column is dropped.
         */
        static Image reduce(Image image, int steps) {
            int w = image.width, h = image.height;
            byte[] rgba = image.rgba;
            for (int step = 0; step < steps; step++) {
                int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
                byte[] next = new byte[nw * nh * 4];
                for (int y = 0; y < nh; y++) {
                    for (int x = 0; x < nw; x++) {
                        int x0 = Math.min(w - 1, 2 * x), x1 = Math.min(w - 1, 2 * x + 1);
                        int y0 = Math.min(h - 1, 2 * y), y1 = Math.min(h - 1, 2 * y + 1);
                        int[] at = {
                            (y0 * w + x0) * 4,
                            (y0 * w + x1) * 4,
                            (y1 * w + x0) * 4,
                            (y1 * w + x1) * 4
                        };
                        int alpha = 0;
                        for (int k : at) {
                            alpha += rgba[k + 3] & 255;
                        }
                        int out = (y * nw + x) * 4;
                        for (int c = 0; c < 3; c++) {
                            int weighted = 0, plain = 0;
                            for (int k : at) {
                                weighted += (rgba[k + c] & 255) * (rgba[k + 3] & 255);
                                plain += rgba[k + c] & 255;
                            }
                            next[out + c] =
                                    (byte)
                                            (alpha == 0
                                                    ? (plain + 2) / 4
                                                    : (weighted + alpha / 2) / alpha);
                        }
                        next[out + 3] = (byte) ((alpha + 2) / 4);
                    }
                }
                rgba = next;
                w = nw;
                h = nh;
            }
            return new Image(image.id, w, h, image.mipLevel + steps, rgba);
        }

        private WorldStreamScopes.Input derived(long id, WorldStreamScopes.DerivedKind kind) {
            return derived(id, kind, null);
        }

        /**
         * Null for an unavailable source (missing, protected or over its cap), for a doodad source
         * refused at SOURCE_INPUT (decoration never costs the tile its buildings), and, when {@code
         * unreadable} is given, for a cutout or blended building image refused at SOURCE_INPUT
         * (flagged in unreadable[0]), and for a group or image the interior pass reads refused at
         * SOURCE_INPUT. Every other refusal, including any read the opaque-only profile made and
         * any changed source, fails the whole set.
         */
        private WorldStreamScopes.Input derived(
                long id, WorldStreamScopes.DerivedKind kind, boolean[] unreadable) {
            if (id <= 0 || id > 0xffff_ffffL) {
                return null;
            }
            if (admission.derivedReadsLeft(kind) <= 0) {
                // The profile's reads of this kind are spent: this one source is left unread, as
                // an unavailable one, and the tile keeps everything else.
                count(Count.SOURCE_READ_CAPPED);
                return null;
            }
            try {
                return admission.readDerivedCandidate(id, kind);
            } catch (WorldTerrainStreaming.Refusal refusal) {
                if (WorldSourceRefusals.Site.SOURCE_UNAVAILABLE.name().equals(refusal.site())) {
                    return null;
                }
                boolean doodad =
                        kind == WorldStreamScopes.DerivedKind.M2_MODEL
                                || kind == WorldStreamScopes.DerivedKind.M2_SKIN
                                || kind == WorldStreamScopes.DerivedKind.M2_IMAGE;
                if (interiorPass
                        && !doodad
                        && unreadable == null
                        && WorldSourceRefusals.Site.SOURCE_INPUT.name().equals(refusal.site())
                        && refusal.getCause() == null
                        && refusal.getSuppressed().length == 0) {
                    token.check();
                    return null; // counted by the caller as unavailable
                }
                if ((doodad || unreadable != null)
                        && WorldSourceRefusals.Site.SOURCE_INPUT.name().equals(refusal.site())
                        && refusal.getCause() == null
                        && refusal.getSuppressed().length == 0) {
                    token.check();
                    if (doodad) {
                        count(Count.DOODAD_SOURCE_INPUT);
                    } else {
                        unreadable[0] = true;
                    }
                    return null;
                }
                throw refusal;
            }
        }

        private <T> T await(java.util.concurrent.CompletableFuture<T> future) {
            try {
                return future.join();
            } catch (CompletionException | CancellationException failure) {
                var cause = com.vibeminer.formats.AssetTasks.unwrap(failure);
                if (cause instanceof CancellationException canceled) {
                    throw canceled;
                }
                if (cause instanceof RuntimeException runtime) {
                    token.check();
                    throw runtime;
                }
                throw failure;
            }
        }
    }

    private static Count modelOutcome(ModelFormatException failure) {
        return switch (failure.code()) {
            case "MODEL_UNSUPPORTED", "MODEL_LIMIT" -> Count.MODEL_UNSUPPORTED;
            default -> Count.MODEL_FAILED;
        };
    }

    private static boolean finite(double x, double y, double z) {
        for (double q : new double[] {x, y, z}) {
            if (!Double.isFinite(q) || Math.abs(q) > TerrainScene.MAX_COORDINATE) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameDoodad(WorldPlacements.Doodad a, WorldPlacements.Doodad b) {
        return a.reference().equals(b.reference())
                && a.position().equals(b.position())
                && a.rotation().equals(b.rotation())
                && a.flags() == b.flags()
                && a.scale() == b.scale();
    }

    /**
     * A MODF rotation: the same construction as MDDF ({@link Rotation#mddf}), heading from rotation
     * y, pitch from rotation x and roll from rotation z. wowdev ADT/v18 builds MODF and MDDF
     * placement matrices with the identical sequence (rotation[1] - 270, -rotation[0], rotation[2]
     * - 90), and TrinityCore ec72c19 places WMO and M2 instances alike through {@code
     * ModelInstance} with {@code fromEulerAnglesZYX}. Untilted placements stay bit-identical to the
     * heading rule.
     */
    static Rotation buildingRotation(WorldPlacements.WorldObject p, double cos, double sin) {
        return Rotation.mddf(cos, sin, p.rotation().x(), p.rotation().z());
    }

    /** Placement policy; null means unsupported. The ADT heading rule is WorldBasicWmo's. */
    private static WorldBasicWmo.Transform qualify(WorldPlacements.WorldObject p) {
        int flags = p.flags();
        if ((flags & DIRECT_ID) == 0
                || (flags & ~(DIRECT_ID | SCALED | LOD_CAPABLE | SETS_FROM_MWDS)) != 0
                || p.reference().kind() != WorldPlacements.ReferenceKind.FILE_DATA_ID
                || p.reference().value() <= 0
                || !buildingRotation(p, 1, 0).finite()) {
            return null;
        }
        double scale = 1.0;
        if ((flags & SCALED) != 0) {
            if (p.scale() <= 0) {
                return null;
            }
            scale = p.scale() / 1024.0;
        }
        var transform = new WorldBasicWmo.Transform(p, scale);
        for (double q :
                new double[] {transform.originX(), transform.originY(), transform.originZ()}) {
            if (!Double.isFinite(q) || Math.abs(q) > TerrainScene.MAX_COORDINATE) {
                return null;
            }
        }
        return transform;
    }

    /** The recorded MODF bounds diagonal (scaled native units); 0 when not finite. */
    static double recordedSize(WorldPlacements.WorldObject record) {
        double dx = (double) record.upperBounds().x() - record.lowerBounds().x();
        double dy = (double) record.upperBounds().y() - record.lowerBounds().y();
        double dz = (double) record.upperBounds().z() - record.lowerBounds().z();
        double size = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Double.isFinite(size) ? size : 0;
    }

    private static boolean sameDeclaration(
            WorldPlacements.WorldObject a, WorldPlacements.WorldObject b) {
        return a.reference().equals(b.reference())
                && a.position().equals(b.position())
                && a.rotation().equals(b.rotation())
                && a.lowerBounds().equals(b.lowerBounds())
                && a.upperBounds().equals(b.upperBounds())
                && a.flags() == b.flags()
                && a.doodadSet() == b.doodadSet()
                && a.nameSet() == b.nameSet()
                && a.scale() == b.scale();
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new IllegalArgumentException(code);
        }
    }
}
