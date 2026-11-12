package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.world.ExteriorObjectLimits;
import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
import com.vibeminer.rendering.world.WmoPortalSet;
import com.vibeminer.rendering.world.WmoPortalTraversal;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable per-tile BASIC_EXTERIOR_V1 objects file {@code tile-<txn>.wso}, version 9. Structural
 * only: the application's closed source bridge is its sole producer, and the OBJECTS command (or
 * the terrain tile header) names its exact length and SHA-256.
 *
 * <p>Little-endian layout: a 256-byte header, then the model, mesh, range (first index, index
 * count, image, material class), placement (model, portal tie, translation, scale and a row-major
 * rotation) and image (width, height, format) tables, then 32-byte vertices (position, normal, uv
 * floats), one 4-byte vertex colour per vertex (R, G, B, then a zero byte), u16 mesh-relative
 * indices padded to four bytes, and image data. Each image's data is its full mip chain, top level
 * down to 1 x 1 and tightly packed, in its format: RGBA8 texels ({@link ObjectTextureMips}, issue
 * #265) or BC1, BC2 or BC3 blocks ({@link ObjectTextureFormats}, issue #374). Then one group
 * ordinal per mesh (-1 for a mesh of a model without portals), and the portal tables of #383: per
 * set (model, groups, portals, vertices, references), per group (MOGP flags, first reference,
 * reference count), the group boxes (six floats), per portal its vertex count, the portal planes
 * (four floats), the portal vertices (three floats) and per reference (portal, group, side), every
 * index relative to its set. Then the animation section of #384 ({@link Animations}): per entry its
 * model, vertex count and animation byte length, the rest vertex of each of the model's vertices,
 * and the animation's bytes padded to four. Then the liquid section ({@link Liquids}): per entry
 * the mesh whose group holds it, its kind and colour, its grid's vertex counts and corner, one
 * height per grid vertex and one live byte per tile padded to four. Every count, offset, index and
 * float is validated; reserved bytes must be zero.
 */
final class WorldStreamObjectsFile {
    // Version 5 (#367): range classes add ADDITIVE and the UNLIT flag, on #420's version 4.
    // Version 6 (#375): vertex colours and the TWO_SIDED range flag. Header int 148 carries the
    // compressed formats the file charges at their stored size, those its session's child samples
    // as blocks (#374); a reader before #374 refuses a nonzero mask there. Version 7 (#383 step
    // 3a): mesh group ordinals and the portal tables of building roots on instance maps, whose
    // counts follow at header bytes 152-171. Version 8 (#383): the INTERIOR_LIGHT range flag, an
    // interior group lit by its vertex colours (its raw MOCV) instead of the sun. Version 9: the
    // placement's formerly reserved int is its portal tie ({@link #tie}), zero for none. Version 10
    // (#384): an animation section after the portal tables ({@link Animations}), whose entry count
    // and byte length follow at header bytes 172-179. Version 11: a liquid section after the
    // animations ({@link Liquids}, building water), its entry count and byte length at 180-187.
    static final int VERSION = 11, HEADER_BYTES = 256;
    // The tables are the sums of the producer's pools (ExteriorObjectLimits): opaque buildings,
    // their cutout and blended surfaces, and doodads. Creature models share whatever room remains.
    static final int MAX_MODELS = ExteriorObjectLimits.MODELS;
    static final int MAX_MESHES = ExteriorObjectLimits.MESHES;
    static final int MAX_RANGES = ExteriorObjectLimits.RANGES;
    static final int MAX_PLACEMENTS = ExteriorObjectLimits.PLACEMENTS;
    static final int MAX_IMAGES = ExteriorObjectLimits.IMAGES;
    static final int MAX_VERTICES = ExteriorObjectLimits.VERTICES;
    static final int MAX_INDICES = ExteriorObjectLimits.INDICES;

    /**
     * Largest image side. 512 is reachable only by chains charged compressed (issue #374): the
     * producer keeps RGBA8 chains at most 256, and every image still fits the GPU byte budget.
     */
    static final int MAX_IMAGE_SIDE = 512, MAX_MESH_VERTICES = 65_536;

    /** Largest side of a chain uploaded as RGBA8, stored so or decoded from unsampled blocks. */
    static final int MAX_RGBA_SIDE = 256;

    /**
     * Texels of whole mip chains (issue #265) at the HIGH texture tier's per-tile budgets (#267),
     * which are themselves the chains of a 2048 x 1024 (buildings), 1024 x 512 (cutout and blended
     * surfaces) and 1024 x 1024 (doodads) texture: 4,893,355 texels, about 18.7 MiB.
     */
    static final int MAX_PIXELS =
            ObjectTextureMips.chainPixels(2048, 1024)
                    + ObjectTextureMips.chainPixels(1024, 512)
                    + ObjectTextureMips.chainPixels(1024, 1024);

    /** Image data bytes per tile: MAX_PIXELS as RGBA8; compressed chains take less of it. */
    static final int MAX_IMAGE_BYTES = MAX_PIXELS * 4;

    /**
     * GPU bytes all images may take as the child uploads them: each image in a format the file's
     * {@code compressedFormats} names at its stored size, every other image at its decoded RGBA8
     * size, because a child that does not sample a format decodes it (up to 8x). The child refuses
     * a file that names a format it does not sample, so its GPU memory never exceeds this.
     */
    static final int MAX_DECODED_BYTES = MAX_IMAGE_BYTES;

    /** Smallest side of a compressed image's top level: one 4 x 4 block. */
    static final int MIN_COMPRESSED_SIDE = 4;

    /** Per image: width, height, format ({@link ObjectTextureFormats}). */
    static final int IMAGE_INTS = 3;

    /**
     * Range material classes: depth-writing opaque, alpha-tested cutout, sorted alpha blend and
     * sorted additive blend (MOMT blend mode 3, #367). A class may carry {@link #UNLIT} (MOMT
     * F_UNLIT): drawn at full intensity, without the light, and {@link #TWO_SIDED} (MOMT
     * F_UNCULLED), which changes nothing while the pass culls no faces, and {@link
     * #INTERIOR_LIGHT}.
     */
    static final int OPAQUE = 0, CUTOUT = 1, BLENDED = 2, ADDITIVE = 3;

    static final int UNLIT = com.vibeminer.rendering.world.WorldObjectPicking.UNLIT;

    static final int TWO_SIDED = com.vibeminer.rendering.world.WorldObjectPicking.TWO_SIDED;

    /**
     * Range flag (version 8, #383): a range of an interior WMO group whose vertex colours hold its
     * light (its raw MOCV), drawn with that light instead of the World sun.
     */
    static final int INTERIOR_LIGHT =
            com.vibeminer.rendering.world.WorldObjectPicking.INTERIOR_LIGHT;

    /** The kind of a range class, without its flags. */
    static int kind(int rangeClass) {
        return com.vibeminer.rendering.world.WorldObjectPicking.kind(rangeClass);
    }

    /** Kinds drawn in the sorted blended pass. */
    static boolean sorted(int rangeClass) {
        int kind = kind(rangeClass);
        return kind == BLENDED || kind == ADDITIVE;
    }

    static final int RANGE_INTS = 4, PLACEMENT_DOUBLES = 13;

    /**
     * A placement's portal tie as stored (version 9): zero for none, else {@code (parent + 1) << 16
     * | group}, a set doodad drawn only while a walk of placement {@code parent}'s portal set
     * reaches interior group {@code group}.
     */
    static int tie(int parent, int group) {
        return parent < 0 ? 0 : (parent + 1) << 16 | group;
    }

    /** Portal tables (#383): ints per set, group, portal and reference; floats per box, plane. */
    static final int PORTAL_SET_INTS = 5, PORTAL_GROUP_INTS = 3, PORTAL_REF_INTS = 3;

    static final int PORTAL_BOX_FLOATS = 6, PORTAL_PLANE_FLOATS = 4;

    static final int MAX_PORTAL_SETS = WmoPortalSet.MAX_TILE_SETS;
    static final int MAX_PORTAL_GROUPS = WmoPortalSet.MAX_TILE_GROUPS;
    static final int MAX_PORTALS = WmoPortalSet.MAX_TILE_PORTALS;
    static final int MAX_PORTAL_VERTICES = WmoPortalSet.MAX_TILE_VERTICES;
    static final int MAX_PORTAL_REFS = WmoPortalSet.MAX_TILE_REFS;

    /** Draw calls per tile (sum over placements of their model's ranges); bounds a frame. */
    static final int MAX_DRAWS = ExteriorObjectLimits.DRAWS;

    static final int VERTEX_FLOATS = 8, VERTEX_BYTES = 32, COLOUR_BYTES = 4;

    /**
     * Animated models per tile (#384): creature models with an idle sequence. A tile draws at most
     * 64 creature models, so this bounds every one of them.
     */
    static final int MAX_ANIMATED = 64;

    /**
     * Bytes of the animation section per tile and per entry. An entry is one model's rest-vertex
     * map (four bytes per vertex) and its serialized idle animation (bones, keys, the rest surface
     * and its influences); busy town tiles measured well under these.
     */
    static final int MAX_ANIMATION_BYTES = 16 << 20, MAX_ENTRY_ANIMATION_BYTES = 6 << 20;

    /**
     * Liquids per tile (building water, WMO MLIQ) and their grid vertices together: the Stormwind
     * canals are one grid of 98 x 143 vertices (14,014). The section is at most about 1.1 MiB.
     */
    static final int MAX_LIQUIDS = 256, MAX_LIQUID_VERTICES = 1 << 18;

    /** Liquid kinds, as terrain water's: river or lake water, ocean, and a flat-coloured liquid. */
    static final int LIQUID_RIVER = 0, LIQUID_OCEAN = 1, LIQUID_FLAT = 2;

    /**
     * The file ceiling: every table at its cap ({@link #length}, with the mesh group column) plus
     * the portal tables at theirs ({@link #portalLength}), rounded up to the next MiB. With the
     * #308 pools, #375's vertex colours, #383's portal tables, the large and interior allowances of
     * buildings drawn by group, the raised doodad pools and the interior allowance of the tile's
     * own buildings that is 53 MiB; it is derived so a raised pool can never outgrow the file.
     */
    static final int MAX_BYTES =
            ceilingMiB(
                    length(
                                    MAX_MODELS,
                                    MAX_MESHES,
                                    MAX_RANGES,
                                    MAX_PLACEMENTS,
                                    MAX_IMAGES,
                                    MAX_VERTICES,
                                    MAX_INDICES,
                                    MAX_IMAGE_BYTES)
                            + portalLength(
                                    WmoPortalSet.MAX_TILE_SETS,
                                    WmoPortalSet.MAX_TILE_GROUPS,
                                    WmoPortalSet.MAX_TILE_PORTALS,
                                    WmoPortalSet.MAX_TILE_VERTICES,
                                    WmoPortalSet.MAX_TILE_REFS)
                            + MAX_ANIMATION_BYTES
                            + Liquids.maxBytes());

    private static int ceilingMiB(int bytes) {
        return (bytes + (1 << 20) - 1) >>> 20 << 20;
    }

    static final double MAX_SCALE = 64;
    private static final int SCRATCH = 64 << 10;
    private static final byte[] MAGIC = "VIMIWSO1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Animated models of one object set (#384): per entry a model whose vertices the child may
     * re-skin, the rest vertex of each of that model's vertices (in mesh order) in its animation's
     * rest surface, and the animation as {@link
     * com.vibeminer.rendering.models.PreviewAnimation#write} bytes. Models ascend and are unique.
     * The animation bytes are parsed only by the child's packing worker; this class checks bounds.
     */
    static final class Animations {
        static final Animations NONE = new Animations(new int[0], new int[0][], new byte[0][]);

        final int[] models;
        final int[][] sources;
        final byte[][] animations;

        Animations(int[] models, int[][] sources, byte[][] animations) {
            this.models = Objects.requireNonNull(models);
            this.sources = Objects.requireNonNull(sources);
            this.animations = Objects.requireNonNull(animations);
            require(
                    models.length <= MAX_ANIMATED
                            && sources.length == models.length
                            && animations.length == models.length,
                    "Animation counts");
            long bytes = 0;
            for (int i = 0; i < models.length; i++) {
                require(i == 0 || models[i] > models[i - 1], "Animated models ascend");
                require(
                        sources[i] != null
                                && animations[i] != null
                                && animations[i].length >= 1
                                && animations[i].length <= MAX_ENTRY_ANIMATION_BYTES,
                        "Animation entry");
                for (int source : sources[i]) {
                    require(source >= 0 && source < MAX_MESH_VERTICES, "Animation source");
                }
                bytes += entryBytes(sources[i].length, animations[i].length);
            }
            require(bytes <= MAX_ANIMATION_BYTES, "Animation section bytes");
        }

        int count() {
            return models.length;
        }

        /** The section's bytes in the file. */
        int bytes() {
            long bytes = 0;
            for (int i = 0; i < models.length; i++) {
                bytes += entryBytes(sources[i].length, animations[i].length);
            }
            return (int) bytes;
        }

        static long entryBytes(int vertices, int animationBytes) {
            return 12L + vertices * 4L + (animationBytes + 3L) / 4 * 4;
        }
    }

    /**
     * Building liquids of one object set: per entry the mesh whose group holds it (portal culling
     * follows that group), its kind ({@link #LIQUID_RIVER}, {@link #LIQUID_OCEAN} or {@link
     * #LIQUID_FLAT}) and 0xRRGGBB colour (a flat liquid's; 0 otherwise), its grid's vertex counts x
     * and y (2 to 1024 each), corner (model space), one height per vertex row by row along y, and
     * one byte per tile: 1 where it holds liquid, 0 where not. Meshes ascend and are unique.
     */
    static final class Liquids {
        static final Liquids NONE =
                new Liquids(
                        new int[0],
                        new int[0],
                        new int[0],
                        new int[0],
                        new float[0],
                        new float[0][],
                        new byte[0][]);

        final int[] meshes, kinds, colours, grids;
        final float[] corners;
        final float[][] heights;
        final byte[][] live;

        Liquids(
                int[] meshes,
                int[] kinds,
                int[] colours,
                int[] grids,
                float[] corners,
                float[][] heights,
                byte[][] live) {
            this.meshes = Objects.requireNonNull(meshes);
            this.kinds = Objects.requireNonNull(kinds);
            this.colours = Objects.requireNonNull(colours);
            this.grids = Objects.requireNonNull(grids);
            this.corners = Objects.requireNonNull(corners);
            this.heights = Objects.requireNonNull(heights);
            this.live = Objects.requireNonNull(live);
            int n = meshes.length;
            require(
                    n <= MAX_LIQUIDS
                            && kinds.length == n
                            && colours.length == n
                            && grids.length == n * 2
                            && corners.length == n * 3
                            && heights.length == n
                            && live.length == n,
                    "Liquid counts");
            long vertices = 0;
            for (int i = 0; i < n; i++) {
                require(i == 0 || meshes[i] > meshes[i - 1], "Liquid meshes ascend");
                int xv = grids[i * 2], yv = grids[i * 2 + 1];
                require(
                        xv >= 2
                                && xv <= 1024
                                && yv >= 2
                                && yv <= 1024
                                && kinds[i] >= LIQUID_RIVER
                                && kinds[i] <= LIQUID_FLAT
                                && (colours[i] & ~0xFFFFFF) == 0
                                && heights[i] != null
                                && heights[i].length == xv * yv
                                && live[i] != null
                                && live[i].length == (xv - 1) * (yv - 1),
                        "Liquid entry");
                for (byte b : live[i]) {
                    require(b == 0 || b == 1, "Liquid tile byte");
                }
                for (float h : heights[i]) {
                    require(Float.isFinite(h) && Math.abs(h) <= 1_000_000, "Liquid height");
                }
                for (int c = 0; c < 3; c++) {
                    float v = corners[i * 3 + c];
                    require(Float.isFinite(v) && Math.abs(v) <= 1_000_000, "Liquid corner");
                }
                vertices += (long) xv * yv;
            }
            require(vertices <= MAX_LIQUID_VERTICES, "Liquid vertices");
        }

        int count() {
            return meshes.length;
        }

        int bytes() {
            long bytes = 0;
            for (int i = 0; i < meshes.length; i++) {
                bytes += entryBytes(grids[i * 2], grids[i * 2 + 1]);
            }
            return (int) bytes;
        }

        /** Live tiles of entry {@code i}. */
        int liveTiles(int i) {
            int count = 0;
            for (byte b : live[i]) {
                count += b;
            }
            return count;
        }

        static long entryBytes(int xVertices, int yVertices) {
            long tiles = (long) (xVertices - 1) * (yVertices - 1);
            return 32L + xVertices * (long) yVertices * 4 + (tiles + 3) / 4 * 4;
        }

        /** The section's ceiling: every entry header and every vertex and tile at its cap. */
        static int maxBytes() {
            return (int) (MAX_LIQUIDS * 36L + MAX_LIQUID_VERTICES * 5L);
        }
    }

    /** Validated immutable object set. Arrays are owned; accessors never expose them mutable. */
    static final class Data {
        /** Per model: first mesh, mesh count. */
        final int[] models;

        /** Per mesh: first vertex, vertex count, first index, index count, first range, ranges. */
        final int[] meshes;

        /** Per range: mesh-relative first index, index count, image, material class. */
        final int[] ranges;

        /** Per placement model ordinal. */
        final int[] placementModels;

        /**
         * Per placement: x, y, z, scale, then a row-major rotation (rows are output X, Y, Z) in
         * native terrain axes.
         */
        final double[] placements;

        /** Per image: width, height, format. */
        final int[] images;

        final float[] vertices;

        /**
         * Per vertex: {@code r | g << 8 | b << 16} (MOCV, #375), each 0..255; zero for a vertex
         * without a colour. The shader adds it to the light.
         */
        final int[] colours;

        final short[] indices;

        /** Every image's packed chain in its format, in image order. */
        final byte[] pixels;

        /** Per mesh: its group ordinal in its model's portal set, or -1 without a set (#383). */
        final int[] meshGroups;

        /** Per portal set: model, groups, portals, vertices, references (#383). */
        final int[] portalSets;

        /** Per portal group: MOGP flags, first reference and reference count within its set. */
        final int[] portalGroups;

        /** Per portal group: min x, y, z, max x, y, z. */
        final float[] portalBoxes;

        /** Per portal: its vertex count; its vertices follow the previous portal's. */
        final int[] portalVertexCounts;

        /** Per portal: its MOPT plane normal x, y, z and distance. */
        final float[] portalPlanes;

        /** Per portal vertex: x, y, z. */
        final float[] portalVertices;

        /** Per reference: portal, group (both within its set) and the raw side. */
        final int[] portalRefs;

        /**
         * Per placement: the placement whose portal set it is tied to, or -1; with {@link
         * #placementGroups}, an interior group of that set. A tied placement is drawn only while a
         * walk of its parent's set reaches the group.
         */
        final int[] placementParents;

        /** Per placement: its tied group in its parent's portal set, or -1. */
        final int[] placementGroups;

        final int draws;

        /** Animated models (#384); {@link Animations#NONE} without any. */
        final Animations animations;

        /** Building liquids; {@link Liquids#NONE} without any. */
        final Liquids liquids;

        /**
         * Compressed formats this file charges at their stored size (bit per {@link
         * ObjectTextureFormats} code): those its session's child samples as blocks (#374).
         */
        final int compressedFormats;

        Data(
                int[] models,
                int[] meshes,
                int[] ranges,
                int[] placementModels,
                double[] placements,
                int[] images,
                float[] vertices,
                int[] colours,
                short[] indices,
                byte[] pixels) {
            this(
                    models,
                    meshes,
                    ranges,
                    placementModels,
                    placements,
                    images,
                    vertices,
                    colours,
                    indices,
                    pixels,
                    0);
        }

        Data(
                int[] models,
                int[] meshes,
                int[] ranges,
                int[] placementModels,
                double[] placements,
                int[] images,
                float[] vertices,
                int[] colours,
                short[] indices,
                byte[] pixels,
                int compressedFormats) {
            this(
                    models,
                    meshes,
                    ranges,
                    placementModels,
                    placements,
                    images,
                    vertices,
                    colours,
                    indices,
                    pixels,
                    compressedFormats,
                    unset(meshes.length / 6),
                    new int[0],
                    new int[0],
                    new float[0],
                    new int[0],
                    new float[0],
                    new float[0],
                    new int[0]);
        }

        Data(
                int[] models,
                int[] meshes,
                int[] ranges,
                int[] placementModels,
                double[] placements,
                int[] images,
                float[] vertices,
                int[] colours,
                short[] indices,
                byte[] pixels,
                int compressedFormats,
                int[] meshGroups,
                int[] portalSets,
                int[] portalGroups,
                float[] portalBoxes,
                int[] portalVertexCounts,
                float[] portalPlanes,
                float[] portalVertices,
                int[] portalRefs) {
            this(
                    models,
                    meshes,
                    ranges,
                    placementModels,
                    placements,
                    images,
                    vertices,
                    colours,
                    indices,
                    pixels,
                    compressedFormats,
                    meshGroups,
                    portalSets,
                    portalGroups,
                    portalBoxes,
                    portalVertexCounts,
                    portalPlanes,
                    portalVertices,
                    portalRefs,
                    unset(placementModels.length),
                    unset(placementModels.length));
        }

        Data(
                int[] models,
                int[] meshes,
                int[] ranges,
                int[] placementModels,
                double[] placements,
                int[] images,
                float[] vertices,
                int[] colours,
                short[] indices,
                byte[] pixels,
                int compressedFormats,
                int[] meshGroups,
                int[] portalSets,
                int[] portalGroups,
                float[] portalBoxes,
                int[] portalVertexCounts,
                float[] portalPlanes,
                float[] portalVertices,
                int[] portalRefs,
                int[] placementParents,
                int[] placementGroups) {
            require((compressedFormats & ~0xE) == 0, "Compressed format mask");
            this.compressedFormats = compressedFormats;
            this.models = Objects.requireNonNull(models);
            this.meshes = Objects.requireNonNull(meshes);
            this.ranges = Objects.requireNonNull(ranges);
            this.placementModels = Objects.requireNonNull(placementModels);
            this.placements = Objects.requireNonNull(placements);
            this.images = Objects.requireNonNull(images);
            this.vertices = Objects.requireNonNull(vertices);
            this.colours = Objects.requireNonNull(colours);
            this.indices = Objects.requireNonNull(indices);
            this.pixels = Objects.requireNonNull(pixels);
            this.meshGroups = Objects.requireNonNull(meshGroups);
            this.portalSets = Objects.requireNonNull(portalSets);
            this.portalGroups = Objects.requireNonNull(portalGroups);
            this.portalBoxes = Objects.requireNonNull(portalBoxes);
            this.portalVertexCounts = Objects.requireNonNull(portalVertexCounts);
            this.portalPlanes = Objects.requireNonNull(portalPlanes);
            this.portalVertices = Objects.requireNonNull(portalVertices);
            this.portalRefs = Objects.requireNonNull(portalRefs);
            this.placementParents = Objects.requireNonNull(placementParents);
            this.placementGroups = Objects.requireNonNull(placementGroups);
            this.animations = Animations.NONE;
            this.liquids = Liquids.NONE;
            draws = validate();
        }

        private Data(Data base, Animations animations, Liquids liquids) {
            compressedFormats = base.compressedFormats;
            models = base.models;
            meshes = base.meshes;
            ranges = base.ranges;
            placementModels = base.placementModels;
            placements = base.placements;
            images = base.images;
            vertices = base.vertices;
            colours = base.colours;
            indices = base.indices;
            pixels = base.pixels;
            meshGroups = base.meshGroups;
            portalSets = base.portalSets;
            portalGroups = base.portalGroups;
            portalBoxes = base.portalBoxes;
            portalVertexCounts = base.portalVertexCounts;
            portalPlanes = base.portalPlanes;
            portalVertices = base.portalVertices;
            portalRefs = base.portalRefs;
            placementParents = base.placementParents;
            placementGroups = base.placementGroups;
            draws = base.draws;
            this.animations = Objects.requireNonNull(animations);
            this.liquids = Objects.requireNonNull(liquids);
            for (int i = 0; i < liquids.count(); i++) {
                require(liquids.meshes[i] >= 0 && liquids.meshes[i] < meshCount(), "Liquid mesh");
            }
            for (int i = 0; i < animations.count(); i++) {
                int model = animations.models[i];
                require(model >= 0 && model < modelCount(), "Animated model");
                require(
                        animations.sources[i].length == modelVertices(model),
                        "Animation sources differ from the model's vertices");
            }
        }

        /** The same set with {@code animations} (#384), checked against its models. */
        Data withAnimations(Animations animations) {
            return animations.count() == 0 && this.animations.count() == 0
                    ? this
                    : new Data(this, animations, liquids);
        }

        /** The same set with building {@code liquids}, checked against its meshes. */
        Data withLiquids(Liquids liquids) {
            return liquids.count() == 0 && this.liquids.count() == 0
                    ? this
                    : new Data(this, animations, liquids);
        }

        /** Vertices of all of a model's meshes. */
        int modelVertices(int model) {
            int first = models[model * 2], count = models[model * 2 + 1], sum = 0;
            for (int m = first; m < first + count; m++) {
                sum += meshes[m * 6 + 1];
            }
            return sum;
        }

        private static int[] unset(int meshes) {
            var groups = new int[meshes];
            Arrays.fill(groups, -1);
            return groups;
        }

        int portalSetCount() {
            return portalSets.length / PORTAL_SET_INTS;
        }

        int portalGroupCount() {
            return portalGroups.length / PORTAL_GROUP_INTS;
        }

        int portalCount() {
            return portalVertexCounts.length;
        }

        int portalVertexCount() {
            return portalVertices.length / 3;
        }

        int portalRefCount() {
            return portalRefs.length / PORTAL_REF_INTS;
        }

        /** The model portal set {@code set} belongs to. */
        int portalModel(int set) {
            return portalSets[set * PORTAL_SET_INTS];
        }

        /** Portal set {@code set} as the traversal takes it; values are the file's floats. */
        WmoPortalSet portalSet(int set) {
            int group = 0, portal = 0, vertex = 0, ref = 0;
            for (int s = 0; s < set; s++) {
                group += portalSets[s * PORTAL_SET_INTS + 1];
                portal += portalSets[s * PORTAL_SET_INTS + 2];
                vertex += portalSets[s * PORTAL_SET_INTS + 3];
                ref += portalSets[s * PORTAL_SET_INTS + 4];
            }
            int at = set * PORTAL_SET_INTS;
            int groupCount = portalSets[at + 1], portals = portalSets[at + 2];
            int refCount = portalSets[at + 4];
            var groups = new java.util.ArrayList<WmoPortalTraversal.Group>(groupCount);
            for (int g = group; g < group + groupCount; g++) {
                int b = g * PORTAL_BOX_FLOATS;
                groups.add(
                        new WmoPortalTraversal.Group(
                                Integer.toUnsignedLong(portalGroups[g * PORTAL_GROUP_INTS]),
                                portalBoxes[b],
                                portalBoxes[b + 1],
                                portalBoxes[b + 2],
                                portalBoxes[b + 3],
                                portalBoxes[b + 4],
                                portalBoxes[b + 5],
                                portalGroups[g * PORTAL_GROUP_INTS + 1],
                                portalGroups[g * PORTAL_GROUP_INTS + 2]));
            }
            var list = new java.util.ArrayList<WmoPortalTraversal.Portal>(portals);
            for (int p = portal; p < portal + portals; p++) {
                int count = portalVertexCounts[p];
                if (count < 3 || count > portalVertexCount() - vertex) {
                    throw new IllegalArgumentException("Portal vertices");
                }
                var v = new double[count * 3];
                for (int i = 0; i < v.length; i++) {
                    v[i] = portalVertices[vertex * 3 + i];
                }
                vertex += count;
                int q = p * PORTAL_PLANE_FLOATS;
                list.add(
                        new WmoPortalTraversal.Portal(
                                v,
                                portalPlanes[q],
                                portalPlanes[q + 1],
                                portalPlanes[q + 2],
                                portalPlanes[q + 3]));
            }
            var refs = new java.util.ArrayList<WmoPortalTraversal.Ref>(refCount);
            for (int r = ref; r < ref + refCount; r++) {
                int q = r * PORTAL_REF_INTS;
                refs.add(
                        new WmoPortalTraversal.Ref(
                                portalRefs[q], portalRefs[q + 1], portalRefs[q + 2]));
            }
            return new WmoPortalSet(groups, list, refs);
        }

        int modelCount() {
            return models.length / 2;
        }

        int meshCount() {
            return meshes.length / 6;
        }

        int rangeCount() {
            return ranges.length / RANGE_INTS;
        }

        int placementCount() {
            return placementModels.length;
        }

        int imageCount() {
            return images.length / IMAGE_INTS;
        }

        int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }

        int indexCount() {
            return indices.length;
        }

        int imageBytes() {
            return pixels.length;
        }

        int bytes() {
            long bytes =
                    (long)
                                    length(
                                            modelCount(),
                                            meshCount(),
                                            rangeCount(),
                                            placementCount(),
                                            imageCount(),
                                            vertexCount(),
                                            indexCount(),
                                            imageBytes())
                            + portalLength(
                                    portalSetCount(),
                                    portalGroupCount(),
                                    portalCount(),
                                    portalVertexCount(),
                                    portalRefCount())
                            + animations.bytes()
                            + liquids.bytes();
            return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
        }

        private int validate() {
            int modelCount = modelCount(), meshCount = meshCount(), rangeCount = rangeCount();
            int placementCount = placementCount(), imageCount = imageCount();
            int vertexCount = vertexCount(), indexCount = indexCount(), imageBytes = imageBytes();
            require(
                    models.length % 2 == 0
                            && meshes.length % 6 == 0
                            && ranges.length % RANGE_INTS == 0
                            && images.length % IMAGE_INTS == 0
                            && vertices.length % VERTEX_FLOATS == 0
                            && colours.length == vertexCount
                            && imageBytes % 4 == 0
                            && placements.length == placementCount * PLACEMENT_DOUBLES
                            && modelCount >= 1
                            && modelCount <= MAX_MODELS
                            && meshCount >= modelCount
                            && meshCount <= MAX_MESHES
                            && rangeCount >= meshCount
                            && rangeCount <= MAX_RANGES
                            && placementCount >= 1
                            && placementCount <= MAX_PLACEMENTS
                            && imageCount >= 1
                            && imageCount <= MAX_IMAGES
                            && vertexCount >= 3
                            && vertexCount <= MAX_VERTICES
                            && indexCount >= 3
                            && indexCount <= MAX_INDICES
                            && imageBytes >= 1
                            && imageBytes <= MAX_IMAGE_BYTES,
                    "Object counts");
            int meshAt = 0;
            for (int m = 0; m < modelCount; m++) {
                require(models[m * 2] == meshAt && models[m * 2 + 1] >= 1, "Model meshes");
                meshAt += models[m * 2 + 1];
                require(meshAt <= meshCount, "Model meshes");
            }
            require(meshAt == meshCount, "Model meshes");
            int vertexAt = 0, indexAt = 0, rangeAt = 0;
            int[] meshRanges = new int[meshCount];
            for (int mesh = 0; mesh < meshCount; mesh++) {
                int at = mesh * 6;
                int vertexes = meshes[at + 1], indexes = meshes[at + 3], count = meshes[at + 5];
                require(
                        meshes[at] == vertexAt
                                && vertexes >= 3
                                && vertexes <= MAX_MESH_VERTICES
                                && meshes[at + 2] == indexAt
                                && indexes >= 3
                                && indexes % 3 == 0
                                && meshes[at + 4] == rangeAt
                                && count >= 1
                                && (long) vertexAt + vertexes <= vertexCount
                                && (long) indexAt + indexes <= indexCount
                                && (long) rangeAt + count <= rangeCount,
                        "Mesh table");
                for (int i = indexAt; i < indexAt + indexes; i++) {
                    require(Short.toUnsignedInt(indices[i]) < vertexes, "Mesh index");
                }
                for (int r = rangeAt; r < rangeAt + count; r++) {
                    int first = ranges[r * RANGE_INTS],
                            length = ranges[r * RANGE_INTS + 1],
                            image = ranges[r * RANGE_INTS + 2],
                            blend = ranges[r * RANGE_INTS + 3];
                    require(
                            first >= 0
                                    && first % 3 == 0
                                    && length >= 3
                                    && length % 3 == 0
                                    && (long) first + length <= indexes
                                    && image >= 0
                                    && image < imageCount
                                    && com.vibeminer.rendering.world.WorldObjectPicking.validClass(
                                            blend),
                            "Draw range");
                }
                meshRanges[mesh] = count;
                vertexAt += vertexes;
                indexAt += indexes;
                rangeAt += count;
            }
            require(
                    vertexAt == vertexCount && indexAt == indexCount && rangeAt == rangeCount,
                    "Mesh table");
            for (float value : vertices) {
                require(Float.isFinite(value) && Math.abs(value) <= 1_000_000, "Vertex value");
            }
            for (int colour : colours) {
                require((colour & 0xff000000) == 0, "Vertex colour");
            }
            long draws = 0;
            for (int p = 0; p < placementCount; p++) {
                int model = placementModels[p];
                require(model >= 0 && model < modelCount, "Placement model");
                int at = p * PLACEMENT_DOUBLES;
                double x = placements[at], y = placements[at + 1], z = placements[at + 2];
                double scale = placements[at + 3];
                require(
                        Double.isFinite(x)
                                && Double.isFinite(y)
                                && Double.isFinite(z)
                                && Math.abs(x) <= 1_000_000
                                && Math.abs(y) <= 1_000_000
                                && Math.abs(z) <= 1_000_000
                                && Double.isFinite(scale)
                                && scale > 0
                                && scale <= MAX_SCALE
                                && rotation(placements, at + 4),
                        "Placement transform");
                for (int mesh = models[model * 2];
                        mesh < models[model * 2] + models[model * 2 + 1];
                        mesh++) {
                    draws += meshRanges[mesh];
                }
            }
            require(draws <= MAX_DRAWS, "Object draw budget");
            long byteSum = 0, decodedSum = 0;
            for (int i = 0; i < imageCount; i++) {
                int width = images[i * IMAGE_INTS], height = images[i * IMAGE_INTS + 1];
                int format = images[i * IMAGE_INTS + 2];
                require(side(width) && side(height), "Image size");
                require(ObjectTextureFormats.valid(format), "Image format");
                // D3D11 refuses block-compressed textures whose top level is under one block.
                require(
                        !ObjectTextureFormats.compressed(format)
                                || width >= MIN_COMPRESSED_SIDE && height >= MIN_COMPRESSED_SIDE,
                        "Compressed image size");
                require(
                        (compressedFormats & (1 << format)) != 0
                                || width <= MAX_RGBA_SIDE && height <= MAX_RGBA_SIDE,
                        "RGBA8 image size");
                byteSum += ObjectTextureFormats.chainBytes(format, width, height);
                decodedSum +=
                        (compressedFormats & (1 << format)) != 0
                                ? ObjectTextureFormats.chainBytes(format, width, height)
                                : 4L * ObjectTextureMips.chainPixels(width, height);
            }
            require(byteSum == imageBytes, "Image data");
            require(decodedSum <= MAX_DECODED_BYTES, "Decoded image budget");
            // BC1 alpha is not sampled on every backend (bgfx Vulkan uses the RGB block format),
            // so only opaque ranges, which ignore alpha, may sample a BC1 image.
            for (int r = 0; r < rangeCount; r++) {
                require(
                        kind(ranges[r * RANGE_INTS + 3]) == OPAQUE
                                || images[ranges[r * RANGE_INTS + 2] * IMAGE_INTS + 2]
                                        != ObjectTextureFormats.BC1,
                        "BC1 image sampled with alpha");
            }
            validatePortals(modelCount, meshCount);
            require(bytes() <= MAX_BYTES, "Objects file ceiling");
            return (int) draws;
        }

        /**
         * Portal tables (#383): sets in increasing model order, each a valid {@link WmoPortalSet},
         * the tables used exactly, and one group ordinal per mesh that names a group of its model's
         * set, or -1 for a model without one.
         */
        private void validatePortals(int modelCount, int meshCount) {
            int sets = portalSetCount(), groups = portalGroupCount(), portals = portalCount();
            int vertices = portalVertexCount(), refs = portalRefCount();
            require(
                    portalSets.length % PORTAL_SET_INTS == 0
                            && portalGroups.length % PORTAL_GROUP_INTS == 0
                            && portalBoxes.length == groups * PORTAL_BOX_FLOATS
                            && portalPlanes.length == portals * PORTAL_PLANE_FLOATS
                            && portalVertices.length % 3 == 0
                            && portalRefs.length % PORTAL_REF_INTS == 0
                            && meshGroups.length == meshCount
                            && sets <= MAX_PORTAL_SETS
                            && groups <= MAX_PORTAL_GROUPS
                            && portals <= MAX_PORTALS
                            && vertices <= MAX_PORTAL_VERTICES
                            && refs <= MAX_PORTAL_REFS,
                    "Portal counts");
            for (float[] table : new float[][] {portalBoxes, portalPlanes, portalVertices}) {
                for (float value : table) {
                    require(
                            Float.isFinite(value) && Math.abs(value) <= WmoPortalSet.MAX_VALUE,
                            "Portal value");
                }
            }
            int[] setGroups = new int[modelCount];
            Arrays.fill(setGroups, -1);
            long groupSum = 0, portalSum = 0, vertexSum = 0, refSum = 0;
            int previous = -1;
            for (int set = 0; set < sets; set++) {
                int at = set * PORTAL_SET_INTS, model = portalSets[at];
                require(
                        model > previous
                                && model < modelCount
                                && portalSets[at + 1] >= 1
                                && portalSets[at + 2] >= 0
                                && portalSets[at + 3] >= 0
                                && portalSets[at + 4] >= 0,
                        "Portal sets");
                previous = model;
                long vertexAt = vertexSum;
                for (int p = (int) portalSum;
                        p < portalSum + portalSets[at + 2] && p < portals;
                        p++) {
                    vertexAt += portalVertexCounts[p];
                }
                groupSum += portalSets[at + 1];
                portalSum += portalSets[at + 2];
                vertexSum += portalSets[at + 3];
                refSum += portalSets[at + 4];
                require(
                        groupSum <= groups
                                && portalSum <= portals
                                && vertexSum <= vertices
                                && refSum <= refs
                                && vertexAt == vertexSum,
                        "Portal sets");
                try {
                    portalSet(set);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Portal set", invalid);
                }
                setGroups[model] = portalSets[at + 1];
            }
            require(
                    groupSum == groups
                            && portalSum == portals
                            && vertexSum == vertices
                            && refSum == refs,
                    "Portal sets");
            for (int m = 0; m < modelCount; m++) {
                for (int mesh = models[m * 2]; mesh < models[m * 2] + models[m * 2 + 1]; mesh++) {
                    int group = meshGroups[mesh];
                    require(
                            setGroups[m] < 0 ? group == -1 : group >= 0 && group < setGroups[m],
                            "Mesh groups");
                }
            }
            validateTies(setGroups);
        }

        /**
         * Portal ties: none (both -1), or another placement whose model has a portal set and one
         * interior group of that set.
         */
        private void validateTies(int[] setGroups) {
            int count = placementCount();
            require(
                    placementParents.length == count && placementGroups.length == count,
                    "Placement ties");
            int[] firstGroup = new int[setGroups.length];
            for (int set = 0, at = 0; set < portalSetCount(); set++) {
                firstGroup[portalSets[set * PORTAL_SET_INTS]] = at;
                at += portalSets[set * PORTAL_SET_INTS + 1];
            }
            for (int p = 0; p < count; p++) {
                int parent = placementParents[p], group = placementGroups[p];
                if (parent == -1 && group == -1) {
                    continue;
                }
                require(parent >= 0 && parent < count && parent != p, "Placement ties");
                int model = placementModels[parent];
                require(
                        model >= 0
                                && model < setGroups.length
                                && group >= 0
                                && group < setGroups[model]
                                && (portalGroups[(firstGroup[model] + group) * PORTAL_GROUP_INTS]
                                                & WmoPortalTraversal.INTERIOR)
                                        != 0,
                        "Placement ties");
            }
        }
    }

    /** Exact identity of the one terrain tile this file belongs to. */
    record Identity(
            WorldStreamState.Nonce nonce,
            String scope,
            long transaction,
            WorldStreamState.Lease lease,
            int x,
            int y) {
        Identity {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            require(
                    scope != null
                            && scope.matches("[0-9a-f]{64}")
                            && transaction > 0
                            && transaction < Long.MAX_VALUE
                            && x >= 0
                            && x < 64
                            && y >= 0
                            && y < 64,
                    "Objects identity");
        }

        static Identity of(WorldStreamTileFile.Header header) {
            return new Identity(
                    header.nonce(),
                    header.scope(),
                    header.transaction(),
                    header.lease(),
                    header.x(),
                    header.y());
        }
    }

    /** Length and whole-file SHA-256, as named in the terrain tile header. */
    record Reference(int length, String sha256) {
        Reference {
            require(
                    length >= HEADER_BYTES
                            && length <= MAX_BYTES
                            && sha256 != null
                            && sha256.matches("[0-9a-f]{64}"),
                    "Objects reference");
        }
    }

    private WorldStreamObjectsFile() {}

    static String basename(long transaction) {
        return "tile-" + transaction + ".wso";
    }

    /** A proper rotation: finite, orthonormal rows within 1e-6 and determinant +1. */
    static boolean rotation(double[] m, int at) {
        for (int i = 0; i < 9; i++) {
            if (!Double.isFinite(m[at + i])) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double dot = 0;
                for (int k = 0; k < 3; k++) {
                    dot += m[at + i * 3 + k] * m[at + j * 3 + k];
                }
                if (Math.abs(dot - (i == j ? 1 : 0)) > 1e-6) {
                    return false;
                }
            }
        }
        double det =
                m[at] * (m[at + 4] * m[at + 8] - m[at + 5] * m[at + 7])
                        - m[at + 1] * (m[at + 3] * m[at + 8] - m[at + 5] * m[at + 6])
                        + m[at + 2] * (m[at + 3] * m[at + 7] - m[at + 4] * m[at + 6]);
        return Math.abs(det - 1) <= 1e-6;
    }

    static int length(
            int models,
            int meshes,
            int ranges,
            int placements,
            int images,
            int vertices,
            int indices,
            int imageBytes) {
        long bytes =
                HEADER_BYTES
                        + models * 8L
                        + meshes * 24L
                        + ranges * (RANGE_INTS * 4L)
                        + placements * (8L + PLACEMENT_DOUBLES * 8L)
                        + images * (IMAGE_INTS * 4L)
                        + vertices * (long) (VERTEX_BYTES + COLOUR_BYTES)
                        + (indices * 2L + 3) / 4 * 4
                        + imageBytes
                        + meshes * 4L;
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    /** Bytes of the portal tables (#383); {@link #length} counts the mesh group column. */
    static int portalLength(int sets, int groups, int portals, int vertices, int refs) {
        long bytes =
                sets * (PORTAL_SET_INTS * 4L)
                        + groups * (PORTAL_GROUP_INTS * 4L + PORTAL_BOX_FLOATS * 4L)
                        + portals * (4L + PORTAL_PLANE_FLOATS * 4L)
                        + vertices * 12L
                        + refs * (PORTAL_REF_INTS * 4L);
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    /** CREATE_NEW publication of {@code tile-<txn>.wso} in directory; never replaces a file. */
    static Reference write(Path directory, Identity identity, Data data, BooleanSupplier canceled)
            throws IOException {
        long started = WorldStageTimings.start();
        try {
            return writeTimed(directory, identity, data, canceled);
        } finally {
            WorldStageTimings.stop("objectsFile.write.total", started);
        }
    }

    private static Reference writeTimed(
            Path directory, Identity identity, Data data, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(data);
        Objects.requireNonNull(canceled);
        check(canceled);
        Path target = directory.resolve(basename(identity.transaction()));
        Path part = directory.resolve(basename(identity.transaction()) + ".part");
        var digest = digest();
        boolean created = false, published = false;
        try {
            try (var channel =
                    FileChannel.open(
                            part,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                var output =
                        new DataOutputStream(
                                new DigestOutputStream(
                                        new BufferedOutputStream(
                                                Channels.newOutputStream(channel), SCRATCH),
                                        digest));
                output.write(encodeHeader(identity, data));
                for (int value : data.models) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int value : data.meshes) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int value : data.ranges) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int p = 0; p < data.placementCount(); p++) {
                    output.writeInt(Integer.reverseBytes(data.placementModels[p]));
                    output.writeInt(
                            Integer.reverseBytes(
                                    tie(data.placementParents[p], data.placementGroups[p])));
                    for (int i = 0; i < PLACEMENT_DOUBLES; i++) {
                        output.writeLong(
                                Long.reverseBytes(
                                        Double.doubleToRawLongBits(
                                                data.placements[p * PLACEMENT_DOUBLES + i])));
                    }
                }
                for (int value : data.images) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int i = 0; i < data.vertices.length; i++) {
                    if ((i & 4095) == 0) {
                        check(canceled);
                    }
                    output.writeInt(
                            Integer.reverseBytes(Float.floatToRawIntBits(data.vertices[i])));
                }
                for (int i = 0; i < data.colours.length; i++) {
                    if ((i & 4095) == 0) {
                        check(canceled);
                    }
                    output.writeInt(Integer.reverseBytes(data.colours[i]));
                }
                for (int i = 0; i < data.indices.length; i++) {
                    if ((i & 4095) == 0) {
                        check(canceled);
                    }
                    output.writeShort(Short.reverseBytes(data.indices[i]));
                }
                if ((data.indices.length & 1) != 0) {
                    output.writeShort(0);
                }
                for (int at = 0; at < data.pixels.length; at += SCRATCH) {
                    check(canceled);
                    output.write(data.pixels, at, Math.min(SCRATCH, data.pixels.length - at));
                }
                check(canceled);
                for (int[] table :
                        new int[][] {data.meshGroups, data.portalSets, data.portalGroups}) {
                    writeInts(output, table);
                }
                writeFloats(output, data.portalBoxes);
                writeInts(output, data.portalVertexCounts);
                writeFloats(output, data.portalPlanes);
                writeFloats(output, data.portalVertices);
                writeInts(output, data.portalRefs);
                var animations = data.animations;
                for (int i = 0; i < animations.count(); i++) {
                    check(canceled);
                    output.writeInt(Integer.reverseBytes(animations.models[i]));
                    output.writeInt(Integer.reverseBytes(animations.sources[i].length));
                    output.writeInt(Integer.reverseBytes(animations.animations[i].length));
                    writeInts(output, animations.sources[i]);
                    output.write(animations.animations[i]);
                    for (int pad = animations.animations[i].length; pad % 4 != 0; pad++) {
                        output.writeByte(0);
                    }
                }
                var liquids = data.liquids;
                for (int i = 0; i < liquids.count(); i++) {
                    check(canceled);
                    for (int v :
                            new int[] {
                                liquids.meshes[i],
                                liquids.kinds[i],
                                liquids.colours[i],
                                liquids.grids[i * 2],
                                liquids.grids[i * 2 + 1]
                            }) {
                        output.writeInt(Integer.reverseBytes(v));
                    }
                    writeFloats(output, Arrays.copyOfRange(liquids.corners, i * 3, i * 3 + 3));
                    writeFloats(output, liquids.heights[i]);
                    output.write(liquids.live[i]);
                    for (int pad = liquids.live[i].length; pad % 4 != 0; pad++) {
                        output.writeByte(0);
                    }
                }
                output.flush();
                if (channel.size() != data.bytes()) {
                    throw new IOException("Objects file length differs");
                }
                WorldStageTimings.add("objectsFile.write.bytes", data.bytes());
                // No fsync, as for tile files: a session-private scratch file the child reads back
                // and the parent deletes, which no restart reuses. A torn or short file fails the
                // exact length and full digest on read. A flush to disk only added latency, which
                // on Windows can reach seconds while other writers keep the disk busy.
            }
            check(canceled);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Objects file exists");
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new Reference(data.bytes(), HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (created && !published) {
                Files.deleteIfExists(part);
            }
        }
    }

    /** Reads and fully validates one objects file bound to exactly this tile and reference. */
    static Data read(Path path, Identity identity, Reference reference, BooleanSupplier canceled)
            throws IOException {
        long started = WorldStageTimings.start();
        try {
            return readTimed(path, identity, reference, canceled);
        } finally {
            WorldStageTimings.stop("child.objectsFile.read", started);
        }
    }

    private static Data readTimed(
            Path path, Identity identity, Reference reference, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(reference);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (path.getFileName() == null
                || !path.getFileName().toString().equals(basename(identity.transaction()))) {
            throw new IOException("Unexpected objects file basename");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Objects file is not a regular private file");
        }
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != reference.length()) {
                throw new IOException("Objects file size");
            }
            var digest = digest();
            var input =
                    new DataInputStream(
                            new DigestInputStream(
                                    new BufferedInputStream(
                                            Channels.newInputStream(channel), SCRATCH),
                                    digest));
            byte[] head = new byte[HEADER_BYTES];
            input.readFully(head);
            var in = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            require(
                    Arrays.equals(MAGIC, Arrays.copyOf(head, 8))
                            && in.getInt(8) == VERSION
                            && in.getInt(12) == HEADER_BYTES
                            && in.getLong(16) == reference.length()
                            && in.getLong(24) == identity.nonce().high()
                            && in.getLong(32) == identity.nonce().low()
                            && HexFormat.of().formatHex(head, 40, 72).equals(identity.scope())
                            && in.getLong(72) == identity.transaction()
                            && in.getLong(80) == identity.lease().generation()
                            && in.getInt(88) == identity.lease().slot()
                            && in.getInt(92) == identity.x()
                            && in.getInt(96) == identity.y()
                            && in.getInt(100) == 0
                            && in.getLong(104) == identity.lease().serial(),
                    "Objects header identity");
            for (int i = 188; i < HEADER_BYTES; i++) {
                require(head[i] == 0, "Reserved objects bytes");
            }
            int liquidCount = in.getInt(180), liquidBytes = in.getInt(184);
            require(
                    liquidCount >= 0
                            && liquidCount <= MAX_LIQUIDS
                            && liquidBytes >= 0
                            && liquidBytes <= Liquids.maxBytes()
                            && (liquidCount == 0) == (liquidBytes == 0),
                    "Objects liquid counts");
            int animated = in.getInt(172), animationBytes = in.getInt(176);
            require(
                    animated >= 0
                            && animated <= MAX_ANIMATED
                            && animationBytes >= 0
                            && animationBytes <= MAX_ANIMATION_BYTES
                            && (animated == 0) == (animationBytes == 0),
                    "Objects animation counts");
            int portalSets = in.getInt(152), portalGroups = in.getInt(156);
            int portals = in.getInt(160), portalVertices = in.getInt(164);
            int portalRefs = in.getInt(168);
            require(
                    portalSets >= 0
                            && portalSets <= MAX_PORTAL_SETS
                            && portalGroups >= 0
                            && portalGroups <= MAX_PORTAL_GROUPS
                            && portals >= 0
                            && portals <= MAX_PORTALS
                            && portalVertices >= 0
                            && portalVertices <= MAX_PORTAL_VERTICES
                            && portalRefs >= 0
                            && portalRefs <= MAX_PORTAL_REFS,
                    "Objects portal counts");
            int models = in.getInt(112), meshes = in.getInt(116), ranges = in.getInt(120);
            int placements = in.getInt(124), images = in.getInt(128), vertices = in.getInt(132);
            int indices = in.getInt(136), imageBytes = in.getInt(140), draws = in.getInt(144);
            require(
                    models >= 1
                            && models <= MAX_MODELS
                            && meshes >= 1
                            && meshes <= MAX_MESHES
                            && ranges >= 1
                            && ranges <= MAX_RANGES
                            && placements >= 1
                            && placements <= MAX_PLACEMENTS
                            && images >= 1
                            && images <= MAX_IMAGES
                            && vertices >= 3
                            && vertices <= MAX_VERTICES
                            && indices >= 3
                            && indices <= MAX_INDICES
                            && imageBytes >= 1
                            && imageBytes <= MAX_IMAGE_BYTES
                            && imageBytes % 4 == 0
                            && (in.getInt(148) & ~0xE) == 0
                            && (long)
                                                    length(
                                                            models,
                                                            meshes,
                                                            ranges,
                                                            placements,
                                                            images,
                                                            vertices,
                                                            indices,
                                                            imageBytes)
                                            + portalLength(
                                                    portalSets,
                                                    portalGroups,
                                                    portals,
                                                    portalVertices,
                                                    portalRefs)
                                            + animationBytes
                                            + liquidBytes
                                    == reference.length(),
                    "Objects header counts");
            // Bounded by the counts above before any body allocation.
            int[] modelTable = ints(input, models * 2);
            int[] meshTable = ints(input, meshes * 6);
            int[] rangeTable = ints(input, ranges * RANGE_INTS);
            int[] placementModels = new int[placements];
            int[] parents = new int[placements], tiedGroups = new int[placements];
            double[] transforms = new double[placements * PLACEMENT_DOUBLES];
            for (int p = 0; p < placements; p++) {
                placementModels[p] = Integer.reverseBytes(input.readInt());
                int tie = Integer.reverseBytes(input.readInt());
                // Zero, or a parent in 1..MAX_PLACEMENTS above a 16-bit group; Data checks both.
                require(tie >= 0 && tie >>> 16 <= MAX_PLACEMENTS, "Placement tie");
                parents[p] = tie == 0 ? -1 : (tie >>> 16) - 1;
                tiedGroups[p] = tie == 0 ? -1 : tie & 0xffff;
                require(tie == 0 || parents[p] >= 0, "Placement tie");
                for (int i = 0; i < PLACEMENT_DOUBLES; i++) {
                    transforms[p * PLACEMENT_DOUBLES + i] =
                            Double.longBitsToDouble(Long.reverseBytes(input.readLong()));
                }
            }
            int[] imageTable = ints(input, images * IMAGE_INTS);
            float[] vertexData = new float[vertices * VERTEX_FLOATS];
            for (int i = 0; i < vertexData.length; i++) {
                if ((i & 4095) == 0) {
                    check(canceled);
                }
                vertexData[i] = Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
            }
            int[] colourData = new int[vertices];
            for (int i = 0; i < colourData.length; i++) {
                if ((i & 4095) == 0) {
                    check(canceled);
                }
                colourData[i] = Integer.reverseBytes(input.readInt());
            }
            short[] indexData = new short[indices];
            for (int i = 0; i < indexData.length; i++) {
                if ((i & 4095) == 0) {
                    check(canceled);
                }
                indexData[i] = Short.reverseBytes(input.readShort());
            }
            if ((indices & 1) != 0) {
                require(input.readShort() == 0, "Index padding");
            }
            byte[] pixelData = new byte[imageBytes];
            for (int at = 0; at < pixelData.length; at += SCRATCH) {
                check(canceled);
                input.readFully(pixelData, at, Math.min(SCRATCH, pixelData.length - at));
            }
            check(canceled);
            int[] meshGroupData = ints(input, meshes);
            int[] portalSetData = ints(input, portalSets * PORTAL_SET_INTS);
            int[] portalGroupData = ints(input, portalGroups * PORTAL_GROUP_INTS);
            float[] portalBoxData = floats(input, portalGroups * PORTAL_BOX_FLOATS);
            int[] portalVertexCountData = ints(input, portals);
            float[] portalPlaneData = floats(input, portals * PORTAL_PLANE_FLOATS);
            float[] portalVertexData = floats(input, portalVertices * 3);
            int[] portalRefData = ints(input, portalRefs * PORTAL_REF_INTS);
            int[] animatedModels = new int[animated];
            int[][] animationSources = new int[animated][];
            byte[][] animationData = new byte[animated][];
            long animationLeft = animationBytes;
            for (int i = 0; i < animated; i++) {
                check(canceled);
                require(animationLeft >= 12, "Animation entry bounds");
                animatedModels[i] = Integer.reverseBytes(input.readInt());
                int count = Integer.reverseBytes(input.readInt());
                int length = Integer.reverseBytes(input.readInt());
                require(
                        count >= 0
                                && count <= MAX_VERTICES
                                && length >= 1
                                && length <= MAX_ENTRY_ANIMATION_BYTES
                                && Animations.entryBytes(count, length) <= animationLeft,
                        "Animation entry bounds");
                animationLeft -= Animations.entryBytes(count, length);
                animationSources[i] = ints(input, count);
                animationData[i] = new byte[length];
                input.readFully(animationData[i]);
                for (int pad = length; pad % 4 != 0; pad++) {
                    require(input.readByte() == 0, "Animation padding");
                }
            }
            require(animationLeft == 0, "Animation section length");
            int[] liquidMeshes = new int[liquidCount], liquidKinds = new int[liquidCount];
            int[] liquidColours = new int[liquidCount], liquidGrids = new int[liquidCount * 2];
            float[] liquidCorners = new float[liquidCount * 3];
            float[][] liquidHeights = new float[liquidCount][];
            byte[][] liquidLive = new byte[liquidCount][];
            long liquidLeft = liquidBytes;
            for (int i = 0; i < liquidCount; i++) {
                check(canceled);
                require(liquidLeft >= 32, "Liquid entry bounds");
                liquidMeshes[i] = Integer.reverseBytes(input.readInt());
                liquidKinds[i] = Integer.reverseBytes(input.readInt());
                liquidColours[i] = Integer.reverseBytes(input.readInt());
                int xv = Integer.reverseBytes(input.readInt());
                int yv = Integer.reverseBytes(input.readInt());
                require(
                        xv >= 2
                                && xv <= 1024
                                && yv >= 2
                                && yv <= 1024
                                && Liquids.entryBytes(xv, yv) <= liquidLeft,
                        "Liquid entry bounds");
                liquidLeft -= Liquids.entryBytes(xv, yv);
                liquidGrids[i * 2] = xv;
                liquidGrids[i * 2 + 1] = yv;
                float[] corner = floats(input, 3);
                System.arraycopy(corner, 0, liquidCorners, i * 3, 3);
                liquidHeights[i] = floats(input, xv * yv);
                liquidLive[i] = new byte[(xv - 1) * (yv - 1)];
                input.readFully(liquidLive[i]);
                for (int pad = liquidLive[i].length; pad % 4 != 0; pad++) {
                    require(input.readByte() == 0, "Liquid padding");
                }
            }
            require(liquidLeft == 0, "Liquid section length");
            check(canceled);
            if (input.read() != -1
                    || channel.size() != reference.length()
                    || !HexFormat.of().formatHex(digest.digest()).equals(reference.sha256())) {
                throw new IOException("Objects bytes or digest differ");
            }
            var data =
                    new Data(
                            modelTable,
                            meshTable,
                            rangeTable,
                            placementModels,
                            transforms,
                            imageTable,
                            vertexData,
                            colourData,
                            indexData,
                            pixelData,
                            in.getInt(148),
                            meshGroupData,
                            portalSetData,
                            portalGroupData,
                            portalBoxData,
                            portalVertexCountData,
                            portalPlaneData,
                            portalVertexData,
                            portalRefData,
                            parents,
                            tiedGroups);
            require(data.draws == draws, "Object draw count");
            return data.withAnimations(
                            new Animations(animatedModels, animationSources, animationData))
                    .withLiquids(
                            new Liquids(
                                    liquidMeshes,
                                    liquidKinds,
                                    liquidColours,
                                    liquidGrids,
                                    liquidCorners,
                                    liquidHeights,
                                    liquidLive));
        } catch (EOFException truncated) {
            throw new IOException("Truncated objects file", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid World objects file", invalid);
        }
    }

    private static byte[] encodeHeader(Identity identity, Data data) {
        var out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt(HEADER_BYTES).putLong(data.bytes());
        out.putLong(identity.nonce().high()).putLong(identity.nonce().low());
        out.put(HexFormat.of().parseHex(identity.scope())).putLong(identity.transaction());
        out.putLong(identity.lease().generation()).putInt(identity.lease().slot());
        out.putInt(identity.x()).putInt(identity.y()).putInt(0).putLong(identity.lease().serial());
        out.putInt(data.modelCount()).putInt(data.meshCount()).putInt(data.rangeCount());
        out.putInt(data.placementCount()).putInt(data.imageCount()).putInt(data.vertexCount());
        out.putInt(data.indexCount()).putInt(data.imageBytes()).putInt(data.draws);
        out.putInt(data.compressedFormats);
        out.putInt(data.portalSetCount()).putInt(data.portalGroupCount());
        out.putInt(data.portalCount()).putInt(data.portalVertexCount());
        out.putInt(data.portalRefCount());
        out.putInt(data.animations.count()).putInt(data.animations.bytes());
        out.putInt(data.liquids.count()).putInt(data.liquids.bytes());
        return out.array();
    }

    private static void writeInts(DataOutputStream output, int[] values) throws IOException {
        for (int value : values) {
            output.writeInt(Integer.reverseBytes(value));
        }
    }

    private static void writeFloats(DataOutputStream output, float[] values) throws IOException {
        for (float value : values) {
            output.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(value)));
        }
    }

    private static float[] floats(DataInputStream input, int count) throws IOException {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
        }
        return values;
    }

    private static int[] ints(DataInputStream input, int count) throws IOException {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = Integer.reverseBytes(input.readInt());
        }
        return values;
    }

    private static boolean side(int value) {
        return value >= 1 && value <= MAX_IMAGE_SIDE && Integer.bitCount(value) == 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
