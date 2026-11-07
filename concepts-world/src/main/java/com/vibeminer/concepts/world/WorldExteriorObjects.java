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
import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
import com.vibeminer.rendering.world.TerrainScene;
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
    static final int MAX_PLACEMENTS = 64, MAX_ROOTS = 24, MAX_GROUPS = 256, MAX_IMAGES = 64;
    static final int MAX_VERTICES = 98_304, MAX_INDICES = 294_912;
    static final int MAX_RANGES = 1024, MAX_MESHES = 256, MAX_PIXELS = 1 << 20;

    /**
     * BASIC doodad caps, separate from the building caps so buildings are never displaced. Distinct
     * models are the binding cap on dense tiles: Zephras Isle read 48 models per tile and capped
     * 791 set doodads at 48, while no tile came near 512 doodad placements, so 96 models (and 96
     * model images) are read.
     */
    static final int MAX_DOODADS = 512, MAX_MODELS = 96, MAX_MODEL_IMAGES = 96;

    static final int MAX_DOODAD_VERTICES = 65_536, MAX_DOODAD_INDICES = 196_608;
    static final int MAX_DOODAD_RANGES = 512, MAX_DOODAD_PIXELS = 1 << 19;

    /**
     * Cutout and blended building surfaces have their own budget on top of the opaque building
     * caps, so admitting them never caps, displaces or fails a surface the opaque profile draws.
     */
    static final int MAX_MATERIAL_IMAGES = 32, MAX_MATERIAL_PIXELS = 1 << 18;

    static final int MAX_MATERIAL_VERTICES = 32_768, MAX_MATERIAL_INDICES = 98_304;
    static final int MAX_MATERIAL_RANGES = 256;

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

    /** Per-tile draw budget: the sum over all placements of their model's ranges. */
    static final int MAX_DRAWS = 4096;

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

    /** MOMT flags bit 0x1, F_UNLIT: the material is not lit. */
    static final long MOMT_F_UNLIT = 0x1;

    /**
     * Flag on a range class (MOMT F_UNCULLED, flags bit 0x4, documented as "two-sided", issue
     * #375): the surface is seen from both sides. The World pass culls no faces yet, so every
     * surface is still drawn two-sided; the flag is carried for single-sided culling.
     */
    public static final int TWO_SIDED = 0x200;

    /** MOMT flags bit 0x4, F_UNCULLED: two-sided. */
    static final long MOMT_F_UNCULLED = 0x4;

    /**
     * MOMT flags this profile applies. Any other set bit (F_UNFOGGED, F_EXTLIGHT, F_SIDN, F_WINDOW,
     * the clamp bits and the undocumented rest) is ignored and counted in {@link
     * Count#BATCH_FLAGS_IGNORED}: their documented effects name no formula to apply.
     */
    static final long MOMT_APPLIED = MOMT_F_UNLIT | MOMT_F_UNCULLED;

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
    static final int[] IMAGE_SIDES = {256, 128, 64, 32};

    /** Doodad image sides: at most 128. */
    static final int[] MODEL_IMAGE_SIDES = {128, 64, 32};

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
        if (shaderId < 0 || shaderId > 0xffff || textureCount < 1) {
            return false;
        }
        var names = M2Combiners.name(textureCount, shaderId);
        return names != null
                && M2Combiners.namedBitsOnly(textureCount, shaderId)
                && names.vertexShader().startsWith("Diffuse_T1");
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
                    1 << 20, 1 << 20, 256, 256, 4096, 65536, 131072, 256, 4, 4L << 20);
    private static final Blp2Types.Limits IMAGE_LIMITS =
            new Blp2Types.Limits(1 << 20, 8192, 65536, 256 << 10, 256 << 10);

    private WorldExteriorObjects() {}

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
     * groups and their batches for roots that were not failed or capped; BATCH_CUTOUT and
     * BATCH_BLENDED are the subsets of BATCH_DRAWN with those material classes. Root caps and the
     * opaque image budget are decided by opaque batches alone, exactly as the opaque-only profile
     * did; cutout and blended batches are then added from their own budget. Images: every distinct
     * texture1 (per alpha treatment) that an otherwise drawable batch needed lands in one IMAGE
     * bucket.
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
        /** Malformed, or outside the parser ceilings (256 groups/materials, 4096 GFID). */
        ROOT_FAILED,
        /** Parsed, but MOTX names, no GFID, zero groups or GFID not a multiple of groups. */
        ROOT_UNSUPPORTED,
        ROOT_UNAVAILABLE,
        ROOT_CAPPED,
        ROOT_EMPTY,
        GROUP_DRAWN,
        /** An interior group, not drawn: the map is not a dungeon or raid (#280). */
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
        ROOT_PORTALS_DROPPED
    }

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
            Rotation rotation) {}

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
         * the mesh has none (doodads, and groups without MOGP flag 0x4).
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
     */
    public record Model(long rootId, boolean doodad, List<Mesh> meshes, WmoPortalSet portals) {
        public Model {
            meshes = List.copyOf(meshes);
        }

        public Model(long rootId, boolean doodad, List<Mesh> meshes) {
            this(rootId, doodad, meshes, null);
        }

        /**
         * The root's retained portals and LOD0 group headers (#383 step 3a), carried for drawn
         * roots on dungeon and raid maps; each mesh's {@link Mesh#groupOrdinal} is its group in
         * them. Null for doodads, on open-world maps, and for a root whose set is not carried whole
         * ({@link Count#ROOT_PORTALS_DROPPED}).
         */
        @Override
        public WmoPortalSet portals() {
            return portals;
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
                ImageDetail detail) {
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
                p += image.width * image.height;
            }
            require(
                    this.placements.size() <= MAX_PLACEMENTS + MAX_DOODADS
                            && this.models.size() <= MAX_ROOTS + MAX_MODELS
                            && this.images.size()
                                    <= MAX_IMAGES + MAX_MODEL_IMAGES + MAX_MATERIAL_IMAGES
                            && v <= MAX_VERTICES + MAX_DOODAD_VERTICES + MAX_MATERIAL_VERTICES
                            && i <= MAX_INDICES + MAX_DOODAD_INDICES + MAX_MATERIAL_INDICES
                            && r <= MAX_RANGES + MAX_DOODAD_RANGES + MAX_MATERIAL_RANGES
                            && m <= MAX_MESHES + MAX_MODELS
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
     * As above; with {@code interiors} (dungeon and raid maps, #280) interior WMO groups are drawn
     * too, after every building's exterior groups and only within the budgets they leave.
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
        return prepare(engine, scope, tile, token, modelReader, detail, retainLimit, false);
    }

    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token,
            ModelReader modelReader,
            ImageDetail detail,
            long retainLimit,
            boolean interiors) {
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
                            new Builder(admission, modelReader, detail, retainLimit, interiors)
                                    .build());
        } finally {
            WorldStageTimings.stop("objects.tile.total", started);
        }
    }

    private enum RootState {
        DRAWN,
        FAILED,
        CAPPED,
        EMPTY
    }

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
        private int modelsRead, modelImagesRead, doodadVertices, doodadIndices, doodadRanges;
        private int doodads, draws;
        private int materialImagesRead, materialVertices, materialIndices;
        private int materialRanges;

        private final ModelReader modelReader;
        private RuntimeException modelFailure;

        private final ImageDetail detail;

        /** Draw interior WMO groups too (dungeon and raid maps, #280). */
        private final boolean interiors;

        /** Interior meshes of drawn roots, held for {@link #placeInteriors}; bounded copies. */
        private final Map<Long, List<PendingMesh>> heldInteriors = new LinkedHashMap<>();

        private long heldVertices, heldIndices;

        /** Portal table rows the carried sets use so far (#383), against WmoPortalSet bounds. */
        private int portalSets, portalGroups, portalCount, portalVertices, portalRefs;

        Builder(
                WorldStreamScopes.TileAdmission admission,
                ModelReader modelReader,
                ImageDetail detail,
                long retainLimit,
                boolean interiors) {
            this.interiors = interiors;
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
                WorldStageTimings.stop("objects.phase.buildings", phase);
                // Admission ran largest first; the set keeps obj0 source order.
                placements.sort(java.util.Comparator.comparingInt(Placement::ordinal));
                // Buildings first, then MDDF doodads, then the drawn buildings' doodad sets, and
                // only then instance interiors (#280): every exterior, doodad and set doodad draw
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
                phase = WorldStageTimings.start();
                placeInteriors();
                placeSetDoodads(building -> deferred.contains(building.rootId()));
                WorldStageTimings.stop("objects.phase.interiors", phase);
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
            for (var model : models) {
                if (model.portals() != null) {
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
                            detail);
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
                var state = rootStates.get(rootId);
                if (state == null) {
                    if (rootsRead >= MAX_ROOTS) {
                        count(Count.PLACEMENT_CAPPED);
                        continue;
                    }
                    state = readRoot(rootId);
                    rootStates.put(rootId, state);
                }
                switch (state) {
                    case FAILED -> count(Count.PLACEMENT_FAILED_ROOT);
                    case CAPPED -> count(Count.PLACEMENT_CAPPED);
                    case EMPTY -> count(Count.PLACEMENT_EMPTY);
                    case DRAWN -> {
                        // The opaque-only profile's cost decides; cutout and blended ranges are
                        // dropped later (fitMaterialDraws) if they overflow the budget.
                        int model = modelSlots.get(rootId), cost = admissionDraws(model);
                        if (cost > MAX_DRAWS - draws) {
                            count(Count.PLACEMENT_CAPPED);
                            continue;
                        }
                        draws += cost;
                        if (cost > 0) {
                            opaquePlacements++;
                        }
                        count(Count.PLACEMENT_DRAWN);
                        placements.add(
                                new Placement(
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
                                        buildingRotation(
                                                record, transform.cos(), transform.sin())));
                    }
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
            for (var record : records) {
                token.check();
                var group = byUid.get(record.uniqueId());
                boolean conflict = false;
                for (var other : group) {
                    conflict |= !sameDoodad(group.getFirst(), other);
                }
                if (conflict) {
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
                var outcome =
                        doodad(
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
                                rotation);
                count(
                        switch (outcome) {
                            case DRAWN -> Count.DOODAD_DRAWN;
                            case FAILED -> Count.DOODAD_FAILED_MODEL;
                            case CAPPED -> Count.DOODAD_CAPPED;
                            case EMPTY -> Count.DOODAD_EMPTY;
                        });
            }
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
                var declared = rootDoodads.get(building.rootId());
                if (declared == null || !declared.declared()) {
                    continue; // the root declares no doodads
                }
                if (!declared.retained()
                        || !declared.fileIdsPresent()
                        || declared.sets().isEmpty()) {
                    count(Count.SET_UNSUPPORTED);
                    continue;
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
                        setDoodad(building, declared, set, (int) d);
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
                Placement building, WmoFormats.Doodads declared, int set, int ordinal) {
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
            // The full MODD quaternion (x, y, z, w) in raw WMO-local axes, applied before the
            // building transform: building * T(position) * fromQuat(q) * S(scale) (wowdev WMO rev
            // 37115 MODD placement matrix; wow.export 4474fa4 WMORenderer.js:400-466).
            var rotation = building.rotation().times(Rotation.quaternion(qx, qy, qz, qw));
            var outcome =
                    doodad(
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
                            rotation);
            count(
                    switch (outcome) {
                        case DRAWN -> Count.SET_DOODAD_DRAWN;
                        case FAILED -> Count.SET_DOODAD_FAILED_MODEL;
                        case CAPPED -> Count.SET_DOODAD_CAPPED;
                        case EMPTY -> Count.SET_DOODAD_EMPTY;
                    });
        }

        /** Places one qualified doodad, reading its model on first use. */
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
                Rotation rotation) {
            if (doodads >= MAX_DOODADS) {
                return RootState.CAPPED;
            }
            var state = doodadStates.get(modelId);
            if (state == null) {
                if (modelsRead >= MAX_MODELS) {
                    return RootState.CAPPED;
                }
                state = readModel(modelId);
                doodadStates.put(modelId, state);
            }
            if (state != RootState.DRAWN) {
                return state;
            }
            int model = doodadSlots.get(modelId), cost = modelDraws(model);
            if (cost > MAX_DRAWS - draws) {
                return RootState.CAPPED;
            }
            draws += cost;
            doodads++;
            placements.add(
                    new Placement(
                            kind, ordinal, uniqueId, modelId, model, flags, doodadSet, 0, x, y, z,
                            scale, cos, sin, rotation));
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

            long[] mark() {
                return new long[] {
                    vertices, indices, ranges, pendingVertices, pendingIndices, materialVertices
                };
            }

            void reset(long[] m) {
                vertices = m[0];
                indices = m[1];
                ranges = m[2];
                pendingVertices = m[3];
                pendingIndices = m[4];
                materialVertices = m[5];
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
                if (materialOnly
                        && !mesh.ranges().isEmpty()
                        && meshVertices
                                > MAX_MATERIAL_VERTICES
                                        - Builder.this.materialVertices
                                        - materialVertices) {
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
                pendingVertices += meshVertices;
                pendingIndices += mesh.indices().length;
                if (pendingVertices > MAX_VERTICES + MAX_MATERIAL_VERTICES
                        || pendingIndices > MAX_INDICES + MAX_MATERIAL_INDICES) {
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
                if (vertices > MAX_VERTICES - Builder.this.vertices
                        || indices > MAX_INDICES - Builder.this.indices
                        || ranges > MAX_RANGES - Builder.this.ranges) {
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
            if (heldVertices + meshVertices > MAX_VERTICES + MAX_MATERIAL_VERTICES
                    || heldIndices + mesh.indices().length > MAX_INDICES + MAX_MATERIAL_INDICES) {
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
         * Instance interiors (#280), after every building's exterior and every MDDF and set doodad
         * of the drawn exteriors: drawn buildings in admission order (largest first), each held
         * interior group only if it still fits the tile budgets, the mesh count and the draw budget
         * left after every placement already drawn, for every placement of its building. A group
         * that does not fit is GROUP_INTERIOR_CAPPED; it never caps or displaces an exterior or a
         * doodad. A building left with no meshes (an interior-only root none of whose groups fit)
         * is removed, its root and placements counted EMPTY.
         *
         * <p>An interior-only root takes building slots for its placements when its first group is
         * admitted, opaque or not, and only if they are free: interiors lose every slot tie, and
         * since material draws were already fitted, they never push an exterior's glass or foliage
         * out. Interior images share the building pixel pool with exterior images; the budget
         * shares it by level, so a dense interior can lower exterior texture levels but never drops
         * an exterior image.
         */
        private void placeInteriors() {
            if (heldInteriors.isEmpty()) {
                return;
            }
            int[] uses = new int[models.size()];
            for (var placement : placements) {
                uses[placement.model()]++;
            }
            int meshes = 0;
            // Every exterior draw is reserved first, opaque, cutout and blended alike, so admitting
            // an interior never makes fitMaterialDraws trim an exterior's glass or foliage.
            long reserved = 0;
            for (int m = 0; m < models.size(); m++) {
                meshes += models.get(m).meshes().size();
                reserved += (long) uses[m] * modelDraws(m);
            }
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
                // yet; its placements need free slots before an interior can give it one.
                boolean needsSlots = admissionDraws(m) == 0;
                if (needsSlots && uses[m] > MAX_PLACEMENTS - opaquePlacements) {
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
                            || (long) uses[m] * candidateRanges > MAX_DRAWS - reserved) {
                        local[Count.GROUP_INTERIOR_CAPPED.ordinal()]++;
                        continue;
                    }
                    var admission = new RootBudget().admit(mesh, local);
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
                    reserved += (long) uses[m] * mapped.ranges().size();
                    if (needsSlots && !slotsTaken) {
                        opaquePlacements += uses[m];
                        slotsTaken = true;
                    }
                    local[Count.GROUP_DRAWN.ordinal()]++;
                    kept.add(mapped);
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

        private RootState readRoot(long rootId) {
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
            for (int g = 0; g < lod0; g++) {
                if (root.gfid(g) != 0) {
                    reads++;
                }
            }
            if (reads > MAX_GROUPS - groupsRead) {
                count(Count.ROOT_CAPPED);
                return RootState.CAPPED;
            }
            // Tentative per-root counts merge only if the root is not capped: caps never split.
            int[] local = new int[counts.length];
            var pending = new ArrayList<PendingMesh>();
            var budget = new RootBudget();
            // Instance interiors (#280) are held for the tile-level pass after every building's
            // exterior, so they never cap or displace an exterior.
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
                groupsRead++;
                var mesh = readGroup(g, groupId, root, local, headers);
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
                    }
                    continue;
                }
                switch (budget.admit(mesh, local)) {
                    case ADMITTED -> pending.add(mesh);
                    case DROPPED -> {}
                    case OVER -> {
                        releaseInteriors(interiors);
                        count(Count.ROOT_CAPPED);
                        return RootState.CAPPED;
                    }
                }
            }
            var built = new ArrayList<Mesh>(pending.size());
            for (var mesh : pending) {
                token.check();
                var mapped = resolveBuilding(mesh, local);
                if (mapped == null) {
                    local[Count.GROUP_EMPTY.ordinal()]++;
                    continue;
                }
                for (var range : mapped.ranges()) {
                    if (kind(range.blend()) == CUTOUT) {
                        local[Count.BATCH_CUTOUT.ordinal()]++;
                    } else if (kind(range.blend()) >= BLENDED) {
                        // Additive surfaces count with the blended ones.
                        local[Count.BATCH_BLENDED.ordinal()]++;
                    }
                }
                local[Count.GROUP_DRAWN.ordinal()]++;
                built.add(mapped);
            }
            for (int i = 0; i < counts.length; i++) {
                counts[i] += local[i];
            }
            var held = interiors;
            if (built.isEmpty() && held.isEmpty()) {
                count(Count.ROOT_EMPTY);
                return RootState.EMPTY;
            }
            if (!held.isEmpty()) {
                // An interior-only root draws nothing until the interior pass; a model with no
                // meshes costs no draws and takes no building slot until then.
                heldInteriors.put(rootId, held);
            }
            rootDoodads.put(rootId, root.doodads());
            modelSlots.put(rootId, models.size());
            models.add(
                    new Model(
                            rootId, false, built, this.interiors ? portals(root, headers) : null));
            count(Count.ROOT_DRAWN);
            return RootState.DRAWN;
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
                vertices += vertexCount;
                indices += used;
                ranges += kept.size();
            }
            boolean materialVerticesCharged = false;
            for (var range : mesh.ranges()) {
                if (kind(range.blend()) == OPAQUE) {
                    continue;
                }
                int extraVertices = opaqueVertices || materialVerticesCharged ? 0 : vertexCount;
                if (extraVertices > MAX_MATERIAL_VERTICES - materialVertices
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
                } catch (IllegalArgumentException outsideMopr) {
                    set = null;
                }
            }
            if (set == null
                    || portalSets >= WmoPortalSet.MAX_TILE_SETS
                    || set.groups().size() > WmoPortalSet.MAX_TILE_GROUPS - portalGroups
                    || set.portals().size() > WmoPortalSet.MAX_TILE_PORTALS - portalCount
                    || set.vertexCount() > WmoPortalSet.MAX_TILE_VERTICES - portalVertices
                    || set.refs().size() > WmoPortalSet.MAX_TILE_REFS - portalRefs) {
                count(Count.ROOT_PORTALS_DROPPED);
                return null;
            }
            portalSets++;
            portalGroups += set.groups().size();
            portalCount += set.portals().size();
            portalVertices += set.vertexCount();
            portalRefs += set.refs().size();
            return set;
        }

        private PendingMesh readGroup(
                int ordinal,
                long id,
                WmoFormats.Root root,
                int[] local,
                WmoFormats.GroupHeader[] headers) {
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
         * Copies one exterior group; null (unsupported) for unusable normals or overlapping
         * batches.
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
                long blend = material.word(2);
                if (blend > ADDITIVE || material.word(3) == 0) {
                    unsupported++;
                    continue;
                }
                // MOMT flags word 0: bit 0x1 F_UNLIT (no lighting), bit 0x4 F_UNCULLED (two-sided).
                long momtFlags = material.word(0);
                if ((momtFlags & MOMT_F_UNLIT) != 0) {
                    blend |= UNLIT;
                }
                if ((momtFlags & MOMT_F_UNCULLED) != 0) {
                    blend |= TWO_SIDED;
                }
                if ((momtFlags & ~MOMT_APPLIED) != 0) {
                    ignoredFlags++;
                }
                candidates.add(
                        new Candidate(
                                (int) batch.firstIndex(),
                                batch.indexCount(),
                                index,
                                material.word(3),
                                (int) blend));
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
            // Only exterior groups carry it (#375): an instance interior's MOCV is its main light,
            // not an addition to the world light, so interiors (#280) carry none and are not
            // counted in the colour or winding evidence.
            boolean exterior = (flags & INTERIOR) == 0;
            int[] colours = new int[0];
            var mocv = borrow.colors();
            if (exterior && (flags & VERTEX_COLOURS) != 0 && mocv != null) {
                colours = new int[count];
                for (int v = 0; v < count; v++) {
                    colours[v] =
                            packColour(mocv.get(v * 4), mocv.get(v * 4 + 1), mocv.get(v * 4 + 2));
                }
            }
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
                                candidate.blend()));
                used += candidate.indexCount();
            }
            if (exterior) {
                winding(vertices, indices, local);
            }
            local[Count.BATCH_UNSUPPORTED.ordinal()] += unsupported;
            local[Count.BATCH_FLAGS_IGNORED.ordinal()] += ignoredFlags;
            if (colours.length > 0) {
                local[Count.GROUP_VERTEX_COLOURS.ordinal()]++;
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
                pending = copyModel(id, skinId, model, skin, local);
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
         */
        private PendingMesh copyModel(
                long id, long skinId, M2Types.Model model, M2Types.Skin skin, int[] local) {
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
                token.check();
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
                token.check();
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
                    token.check();
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
            boolean full =
                    switch (pool) {
                        case BUILDING -> imagesRead >= MAX_IMAGES;
                        case MATERIAL -> materialImagesRead >= MAX_MATERIAL_IMAGES;
                        case DOODAD -> modelImagesRead >= MAX_MODEL_IMAGES;
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
                    case BUILDING -> imagesRead++;
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
            for (int k = 0; k < levels; k++) {
                var mip = declared.get(k);
                if (mip.level() != k
                        || mip.storage() != Blp2Types.MipStorage.EXACT
                        || mip.byteLength()
                                != ObjectTextureFormats.levelBytes(
                                        format, (int) width, (int) height, k)) {
                    return -1;
                }
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
            for (var model : models) {
                for (var mesh : model.meshes()) {
                    for (var range : mesh.ranges()) {
                        used[range.image()] = true;
                    }
                }
            }
            for (var pool : Pool.values()) {
                var chosen = new ArrayList<Demand>();
                var requests = new ArrayList<WorldImageBudget.Request>();
                for (var demand : demands) {
                    if (demand != null && demand.pool() == pool && used[demand.slot()]) {
                        chosen.add(demand);
                        requests.add(
                                new WorldImageBudget.Request(
                                        demand.width(), demand.height(), levelsDown(demand)));
                    }
                }
                long budget =
                        switch (pool) {
                            case BUILDING -> detail.buildingPixels();
                            case MATERIAL -> detail.materialPixels();
                            case DOODAD -> detail.doodadPixels();
                        };
                int[] levels =
                        WorldImageBudget.allocate(
                                requests,
                                budget,
                                pool == Pool.DOODAD ? MODEL_IMAGE_SIDES : IMAGE_SIDES);
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
            // Registered pixels are reused only when the drawn level lies above them; every other
            // demand lets them go now instead of holding them, unbudgeted, until the chains.
            for (int i = 0; i < demands.size(); i++) {
                var demand = demands.get(i);
                if (demand != null
                        && demand.registeredRgba() != null
                        && !(images.get(demand.slot()).mipLevel() < demand.registered())) {
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
            for (var demand : demands) {
                if (demand == null || !used[demand.slot()]) {
                    continue;
                }
                token.check();
                var top = images.get(demand.slot());
                int level = top.mipLevel();
                if (demand.blockFormat() >= 0) {
                    byte[] blocks = blockChain(demand, level, top.width(), top.height());
                    if (blocks != null) {
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
                                (below, w, h) -> file.level(level + below, w, h),
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
            }
        }

        /** The image's retained source bytes, or a second read when they were not kept. */
        private byte[] source(Demand demand) {
            byte[] kept = sources.get(demand.id());
            if (kept != null || unreadSources.contains(demand.id())) {
                return kept; // A refused second read is not tried again.
            }
            // A refused second read of a cutout or blended image keeps what the first read gave,
            // as the first read would have: it never costs the tile its set.
            var input =
                    derived(
                            demand.id(),
                            imageKind(demand.pool()),
                            demand.pool() == Pool.MATERIAL ? new boolean[1] : null);
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

        private Image relevel(Demand demand, int level) {
            var registered = images.get(demand.slot());
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
         * (flagged in unreadable[0]). Every other refusal, including any read the opaque-only
         * profile made and any changed source, fails the whole set.
         */
        private WorldStreamScopes.Input derived(
                long id, WorldStreamScopes.DerivedKind kind, boolean[] unreadable) {
            if (id <= 0 || id > 0xffff_ffffL) {
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
