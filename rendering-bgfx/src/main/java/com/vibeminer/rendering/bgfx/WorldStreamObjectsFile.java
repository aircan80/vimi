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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable per-tile BASIC_EXTERIOR_V1 objects file {@code tile-<txn>.wso}, version 13. Structural
 * only: the application's closed source bridge is its sole producer, and the OBJECTS command (or
 * the terrain tile header) names its exact length and SHA-256.
 *
 * <p>Little-endian layout: a 256-byte header, then the reference section of version 13 ({@link
 * References}: per referenced model, then per referenced image, its index and digest; empty when
 * the file carries everything), then the model, mesh, range (first index, index count, image,
 * material class), placement (model, portal tie, translation, scale and a row-major rotation) and
 * image (width, height, format) tables, then 32-byte vertices (position, normal, uv floats), one
 * 4-byte vertex colour per vertex (R, G, B, then a zero byte), u16 mesh-relative indices padded to
 * four bytes, and image data. Each image's data is its full mip chain, top level down to 1 x 1 and
 * tightly packed, in its format: RGBA8 texels ({@link ObjectTextureMips}, issue #265) or BC1, BC2
 * or BC3 blocks ({@link ObjectTextureFormats}, issue #374). Then one group ordinal per mesh (-1 for
 * a mesh of a model without portals), and the portal tables of #383: per set (model, groups,
 * portals, vertices, references), per group (MOGP flags, first reference, reference count), the
 * group boxes (six floats), per portal its vertex count, the portal planes (four floats), the
 * portal vertices (three floats) and per reference (portal, group, side), every index relative to
 * its set. Then the animation section of #384 ({@link Animations}): per entry its model, vertex
 * count and animation byte length, the rest vertex of each of the model's vertices, and the
 * animation's bytes padded to four. Then the liquid section ({@link Liquids}): per entry the mesh
 * whose group holds it, its kind and colour, its grid's vertex counts and corner, one height per
 * grid vertex and one live byte per tile padded to four.
 *
 * <p>A referenced model's meshes keep their table rows, ranges and group ordinals; their vertices,
 * colours and indices, and the model's portal set tables, animation entry and liquids, are left out
 * of those sections and come from its asset ({@link WorldStreamAssetPack}); a referenced image's
 * chain likewise. The header counts every table row, vertex, index and image byte, carried or not;
 * its animation and liquid counts are those of the sections as written. Every count, offset, index
 * and float is validated; reserved bytes must be zero.
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
    // Version 13 (#380 stage 4): models and images may be named by asset digest instead of carried
    // ({@link References}); their counts at header bytes 200-207, the reference section right
    // after the header, and their payloads come from an asset pack ({@link WorldStreamAssetPack}).
    // Version 14 (#474): the CLAMP_S and CLAMP_T range flags (MOMT F_CLAMP_S, F_CLAMP_T).
    static final int VERSION = 14, HEADER_BYTES = 256;
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

    /**
     * Models and images a file names by asset digest instead of carrying (#380 stage 4): model and
     * image indices ascending and unique, each with its asset's digest. A referenced model's meshes
     * keep their table rows and draw ranges, which are the tile's own; their vertices, colours and
     * indices, the model's portal set tables, animation entry and liquids come from its asset. A
     * referenced image keeps its table row; its chain comes from its asset.
     */
    record References(
            int[] models,
            WorldStreamObjectAssets.Digest[] modelDigests,
            int[] images,
            WorldStreamObjectAssets.Digest[] imageDigests) {
        static final References NONE =
                new References(
                        new int[0],
                        new WorldStreamObjectAssets.Digest[0],
                        new int[0],
                        new WorldStreamObjectAssets.Digest[0]);

        References {
            models = models.clone();
            modelDigests = modelDigests.clone();
            images = images.clone();
            imageDigests = imageDigests.clone();
            require(
                    models.length == modelDigests.length
                            && images.length == imageDigests.length
                            && models.length <= MAX_MODELS
                            && images.length <= MAX_IMAGES,
                    "Reference counts");
            for (int i = 0; i < models.length; i++) {
                require(i == 0 || models[i] > models[i - 1], "Referenced models ascend");
                require(models[i] >= 0 && modelDigests[i] != null, "Referenced model");
            }
            for (int i = 0; i < images.length; i++) {
                require(i == 0 || images[i] > images[i - 1], "Referenced images ascend");
                require(images[i] >= 0 && imageDigests[i] != null, "Referenced image");
            }
        }

        int count() {
            return models.length + images.length;
        }

        /** The reference section's bytes: per entry its index and digest. */
        int bytes() {
            return count() * REFERENCE_BYTES;
        }

        boolean model(int m) {
            return Arrays.binarySearch(models, m) >= 0;
        }

        boolean image(int i) {
            return Arrays.binarySearch(images, i) >= 0;
        }
    }

    /** One portal set's slices of a set's tables (portal sets in table order). */
    static final class PortalSlices {
        private final Data data;
        private final int[] groupAt, portalAt, vertexAt, refAt;

        PortalSlices(Data data) {
            this.data = data;
            int n = data.portalSetCount();
            groupAt = new int[n + 1];
            portalAt = new int[n + 1];
            vertexAt = new int[n + 1];
            refAt = new int[n + 1];
            for (int s = 0; s < n; s++) {
                int at = s * PORTAL_SET_INTS;
                groupAt[s + 1] = groupAt[s] + data.portalSets[at + 1];
                portalAt[s + 1] = portalAt[s] + data.portalSets[at + 2];
                vertexAt[s + 1] = vertexAt[s] + data.portalSets[at + 3];
                refAt[s + 1] = refAt[s] + data.portalSets[at + 4];
            }
        }

        int[] groups(int s) {
            return Arrays.copyOfRange(
                    data.portalGroups,
                    groupAt[s] * PORTAL_GROUP_INTS,
                    groupAt[s + 1] * PORTAL_GROUP_INTS);
        }

        float[] boxes(int s) {
            return Arrays.copyOfRange(
                    data.portalBoxes,
                    groupAt[s] * PORTAL_BOX_FLOATS,
                    groupAt[s + 1] * PORTAL_BOX_FLOATS);
        }

        int[] vertexCounts(int s) {
            return Arrays.copyOfRange(data.portalVertexCounts, portalAt[s], portalAt[s + 1]);
        }

        float[] planes(int s) {
            return Arrays.copyOfRange(
                    data.portalPlanes,
                    portalAt[s] * PORTAL_PLANE_FLOATS,
                    portalAt[s + 1] * PORTAL_PLANE_FLOATS);
        }

        float[] vertices(int s) {
            return Arrays.copyOfRange(data.portalVertices, vertexAt[s] * 3, vertexAt[s + 1] * 3);
        }

        int[] refs(int s) {
            return Arrays.copyOfRange(
                    data.portalRefs, refAt[s] * PORTAL_REF_INTS, refAt[s + 1] * PORTAL_REF_INTS);
        }
    }

    /** A reference entry: an index and a 128-bit digest. */
    static final int REFERENCE_BYTES = 4 + 16;

    /** An objects file named assets its asset pack did not hold. */
    static final class MissingAssets extends IOException {
        private static final long serialVersionUID = 1L;

        final transient List<WorldStreamObjectAssets.Digest> missing;

        MissingAssets(List<WorldStreamObjectAssets.Digest> missing) {
            super("Objects file names " + missing.size() + " assets the pack does not hold");
            this.missing = List.copyOf(missing);
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
        return write(directory, identity, data, References.NONE, canceled);
    }

    /**
     * Writes {@code data} naming {@code references}' models and images by digest instead of
     * carrying them; a reader given their assets reads back exactly {@code data}.
     */
    static Reference write(
            Path directory,
            Identity identity,
            Data data,
            References references,
            BooleanSupplier canceled)
            throws IOException {
        long started = WorldStageTimings.start();
        try {
            return writeTimed(directory, identity, data, references, canceled);
        } finally {
            WorldStageTimings.stop("objectsFile.write.total", started);
        }
    }

    /** The file length of {@code data} written with {@code references}. */
    static long fileLength(Data data, References references) {
        if (references.count() == 0) {
            return data.bytes();
        }
        long omitted = 0;
        for (int m : references.models) {
            int first = data.models[m * 2], count = data.models[m * 2 + 1];
            for (int mesh = first; mesh < first + count; mesh++) {
                omitted += data.meshes[mesh * 6 + 1] * (VERTEX_FLOATS * 4L + 4);
            }
        }
        long indices = 0;
        for (int mesh = 0; mesh < data.meshCount(); mesh++) {
            if (!references.model(owner(data, mesh))) {
                indices += data.meshes[mesh * 6 + 3];
            }
        }
        omitted += (data.indexCount() + 1L) / 2 * 4 - (indices + 1) / 2 * 4;
        for (int i : references.images) {
            omitted +=
                    ObjectTextureFormats.chainBytes(
                            data.images[i * IMAGE_INTS + 2],
                            data.images[i * IMAGE_INTS],
                            data.images[i * IMAGE_INTS + 1]);
        }
        for (int set = 0; set < data.portalSetCount(); set++) {
            int at = set * PORTAL_SET_INTS;
            if (references.model(data.portalSets[at])) {
                omitted +=
                        portalLength(
                                0,
                                data.portalSets[at + 1],
                                data.portalSets[at + 2],
                                data.portalSets[at + 3],
                                data.portalSets[at + 4]);
            }
        }
        var animations = data.animations;
        for (int i = 0; i < animations.count(); i++) {
            if (references.model(animations.models[i])) {
                omitted +=
                        Animations.entryBytes(
                                animations.sources[i].length, animations.animations[i].length);
            }
        }
        var liquids = data.liquids;
        for (int i = 0; i < liquids.count(); i++) {
            if (references.model(owner(data, liquids.meshes[i]))) {
                omitted += Liquids.entryBytes(liquids.grids[i * 2], liquids.grids[i * 2 + 1]);
            }
        }
        return data.bytes() - omitted + references.bytes();
    }

    /** The model that holds mesh {@code mesh}. */
    static int owner(Data data, int mesh) {
        for (int m = 0; m < data.modelCount(); m++) {
            int first = data.models[m * 2];
            if (mesh >= first && mesh < first + data.models[m * 2 + 1]) {
                return m;
            }
        }
        throw new IllegalArgumentException("Mesh without a model");
    }

    private static Reference writeTimed(
            Path directory,
            Identity identity,
            Data data,
            References references,
            BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(data);
        Objects.requireNonNull(references);
        Objects.requireNonNull(canceled);
        for (int m : references.models) {
            require(m < data.modelCount(), "Referenced model");
        }
        for (int i : references.images) {
            require(i < data.imageCount(), "Referenced image");
        }
        long length = fileLength(data, references);
        require(length <= MAX_BYTES, "Objects file length");
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
                output.write(encodeHeader(identity, data, references, length));
                for (int r = 0; r < references.models.length; r++) {
                    output.writeInt(Integer.reverseBytes(references.models[r]));
                    output.writeLong(Long.reverseBytes(references.modelDigests[r].high()));
                    output.writeLong(Long.reverseBytes(references.modelDigests[r].low()));
                }
                for (int r = 0; r < references.images.length; r++) {
                    output.writeInt(Integer.reverseBytes(references.images[r]));
                    output.writeLong(Long.reverseBytes(references.imageDigests[r].high()));
                    output.writeLong(Long.reverseBytes(references.imageDigests[r].low()));
                }
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
                // Carried meshes only, in mesh order: their vertices, then colours, then indices.
                boolean[] carried = new boolean[data.meshCount()];
                int carriedIndices = 0;
                for (int mesh = 0; mesh < carried.length; mesh++) {
                    carried[mesh] = !references.model(owner(data, mesh));
                    if (carried[mesh]) {
                        carriedIndices += data.meshes[mesh * 6 + 3];
                    }
                }
                for (int mesh = 0; mesh < carried.length; mesh++) {
                    if (!carried[mesh]) {
                        continue;
                    }
                    check(canceled);
                    int first = data.meshes[mesh * 6] * VERTEX_FLOATS;
                    int end = first + data.meshes[mesh * 6 + 1] * VERTEX_FLOATS;
                    for (int i = first; i < end; i++) {
                        output.writeInt(
                                Integer.reverseBytes(Float.floatToRawIntBits(data.vertices[i])));
                    }
                }
                for (int mesh = 0; mesh < carried.length; mesh++) {
                    if (!carried[mesh]) {
                        continue;
                    }
                    int first = data.meshes[mesh * 6];
                    for (int i = first; i < first + data.meshes[mesh * 6 + 1]; i++) {
                        output.writeInt(Integer.reverseBytes(data.colours[i]));
                    }
                }
                for (int mesh = 0; mesh < carried.length; mesh++) {
                    if (!carried[mesh]) {
                        continue;
                    }
                    int first = data.meshes[mesh * 6 + 2];
                    for (int i = first; i < first + data.meshes[mesh * 6 + 3]; i++) {
                        output.writeShort(Short.reverseBytes(data.indices[i]));
                    }
                }
                if ((carriedIndices & 1) != 0) {
                    output.writeShort(0);
                }
                int from = 0;
                for (int image = 0; image < data.imageCount(); image++) {
                    int stored =
                            ObjectTextureFormats.chainBytes(
                                    data.images[image * IMAGE_INTS + 2],
                                    data.images[image * IMAGE_INTS],
                                    data.images[image * IMAGE_INTS + 1]);
                    if (!references.image(image)) {
                        check(canceled);
                        output.write(data.pixels, from, stored);
                    }
                    from += stored;
                }
                check(canceled);
                writeInts(output, data.meshGroups);
                writeInts(output, data.portalSets);
                // Each array of the carried portal sets only, in set order.
                var sets = new PortalSlices(data);
                for (int set = 0; set < data.portalSetCount(); set++) {
                    if (!references.model(data.portalSets[set * PORTAL_SET_INTS])) {
                        writeInts(output, sets.groups(set));
                    }
                }
                for (int set = 0; set < data.portalSetCount(); set++) {
                    if (!references.model(data.portalSets[set * PORTAL_SET_INTS])) {
                        writeFloats(output, sets.boxes(set));
                    }
                }
                for (int set = 0; set < data.portalSetCount(); set++) {
                    if (!references.model(data.portalSets[set * PORTAL_SET_INTS])) {
                        writeInts(output, sets.vertexCounts(set));
                    }
                }
                for (int set = 0; set < data.portalSetCount(); set++) {
                    if (!references.model(data.portalSets[set * PORTAL_SET_INTS])) {
                        writeFloats(output, sets.planes(set));
                    }
                }
                for (int set = 0; set < data.portalSetCount(); set++) {
                    if (!references.model(data.portalSets[set * PORTAL_SET_INTS])) {
                        writeFloats(output, sets.vertices(set));
                    }
                }
                for (int set = 0; set < data.portalSetCount(); set++) {
                    if (!references.model(data.portalSets[set * PORTAL_SET_INTS])) {
                        writeInts(output, sets.refs(set));
                    }
                }
                var animations = data.animations;
                for (int i = 0; i < animations.count(); i++) {
                    if (references.model(animations.models[i])) {
                        continue;
                    }
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
                    if (references.model(owner(data, liquids.meshes[i]))) {
                        continue;
                    }
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
                if (channel.size() != length) {
                    throw new IOException("Objects file length differs");
                }
                WorldStageTimings.add("objectsFile.write.bytes", length);
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
            return new Reference(
                    Math.toIntExact(length), HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (created && !published) {
                Files.deleteIfExists(part);
            }
        }
    }

    /** Reads and fully validates one objects file bound to exactly this tile and reference. */
    static Data read(Path path, Identity identity, Reference reference, BooleanSupplier canceled)
            throws IOException {
        return read(path, identity, reference, WorldStreamAssetPack.Pack.EMPTY, canceled);
    }

    /**
     * As above, taking the models and images the file names by digest from {@code assets}; {@link
     * MissingAssets} when it names one {@code assets} does not hold.
     */
    static Data read(
            Path path,
            Identity identity,
            Reference reference,
            WorldStreamAssetPack.Pack assets,
            BooleanSupplier canceled)
            throws IOException {
        return read(path, identity, reference, assets, canceled, named -> {});
    }

    /**
     * As above, giving {@code named} the file's references once its length and digest held, before
     * any {@link MissingAssets} is raised.
     */
    static Data read(
            Path path,
            Identity identity,
            Reference reference,
            WorldStreamAssetPack.Pack assets,
            BooleanSupplier canceled,
            java.util.function.Consumer<References> named)
            throws IOException {
        long started = WorldStageTimings.start();
        try {
            return readTimed(path, identity, reference, assets, canceled, named);
        } finally {
            WorldStageTimings.stop("child.objectsFile.read", started);
        }
    }

    private static Data readTimed(
            Path path,
            Identity identity,
            Reference reference,
            WorldStreamAssetPack.Pack assets,
            BooleanSupplier canceled,
            java.util.function.Consumer<References> named)
            throws IOException {
        Objects.requireNonNull(assets);
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
                require(i >= 200 && i < 208 || head[i] == 0, "Reserved objects bytes");
            }
            int referencedModels = in.getInt(200), referencedImages = in.getInt(204);
            require(
                    referencedModels >= 0
                            && referencedModels <= MAX_MODELS
                            && referencedImages >= 0
                            && referencedImages <= MAX_IMAGES,
                    "Objects reference counts");
            int referenceBytes = (referencedModels + referencedImages) * REFERENCE_BYTES;
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
                                            + referenceBytes
                                    >= reference.length(),
                    "Objects header counts");
            // Every byte is carried without references; with them the file is shorter by what
            // the assets carry, checked exactly as the body is read.
            require(
                    referenceBytes > 0
                            || (long)
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
            var refModels = new int[referencedModels];
            var refModelDigests = new WorldStreamObjectAssets.Digest[referencedModels];
            for (int r = 0; r < referencedModels; r++) {
                refModels[r] = Integer.reverseBytes(input.readInt());
                refModelDigests[r] =
                        new WorldStreamObjectAssets.Digest(
                                Long.reverseBytes(input.readLong()),
                                Long.reverseBytes(input.readLong()));
            }
            var refImages = new int[referencedImages];
            var refImageDigests = new WorldStreamObjectAssets.Digest[referencedImages];
            for (int r = 0; r < referencedImages; r++) {
                refImages[r] = Integer.reverseBytes(input.readInt());
                refImageDigests[r] =
                        new WorldStreamObjectAssets.Digest(
                                Long.reverseBytes(input.readLong()),
                                Long.reverseBytes(input.readLong()));
            }
            var references = new References(refModels, refModelDigests, refImages, refImageDigests);
            // An asset the pack lacks is noted and its place skipped: the rest is still read, and
            // MissingAssets is raised only once the file's length and digest have held, so a
            // corrupt file is refused as corrupt whatever it names.
            var missing = new java.util.ArrayList<WorldStreamObjectAssets.Digest>();
            var modelParts = new WorldStreamObjectAssets.ModelPart[models];
            var imageParts = new WorldStreamObjectAssets.ImagePart[images];
            boolean[] modelNamed = new boolean[models], imageNamed = new boolean[images];
            for (int r = 0; r < referencedModels; r++) {
                require(refModels[r] < models, "Referenced model");
                modelNamed[refModels[r]] = true;
                var part = assets.models().get(refModelDigests[r]);
                if (part == null) {
                    missing.add(refModelDigests[r]);
                }
                modelParts[refModels[r]] = part;
            }
            for (int r = 0; r < referencedImages; r++) {
                require(refImages[r] < images, "Referenced image");
                imageNamed[refImages[r]] = true;
                var part = assets.images().get(refImageDigests[r]);
                if (part == null) {
                    missing.add(refImageDigests[r]);
                }
                imageParts[refImages[r]] = part;
            }
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
            // Each mesh's model, with every model's meshes inside the mesh table and in order.
            int[] owners = new int[meshes];
            Arrays.fill(owners, -1);
            for (int m = 0; m < models; m++) {
                int first = modelTable[m * 2], count = modelTable[m * 2 + 1];
                require(first >= 0 && count >= 0 && (long) first + count <= meshes, "Model meshes");
                for (int mesh = first; mesh < first + count; mesh++) {
                    require(owners[mesh] < 0, "Model meshes");
                    owners[mesh] = m;
                }
                var part = modelParts[m];
                require(part == null || part.meshes().size() == count, "Referenced model meshes");
            }
            // Meshes cover the vertex and index blocks in order, so carried ones read in turn.
            int vertexAt = 0, indexAt = 0;
            for (int mesh = 0; mesh < meshes; mesh++) {
                int at = mesh * 6;
                require(
                        owners[mesh] >= 0
                                && meshTable[at] == vertexAt
                                && meshTable[at + 1] >= 0
                                && meshTable[at + 2] == indexAt
                                && meshTable[at + 3] >= 0,
                        "Mesh table");
                vertexAt += meshTable[at + 1];
                indexAt += meshTable[at + 3];
                require(vertexAt <= vertices && indexAt <= indices, "Mesh table");
                var part = referencedMesh(modelParts, modelTable, owners, mesh);
                require(
                        part == null
                                || part.vertexCount() == meshTable[at + 1]
                                        && part.indices().length == meshTable[at + 3],
                        "Referenced mesh counts");
            }
            require(vertexAt == vertices && indexAt == indices, "Mesh table");
            float[] vertexData = new float[vertices * VERTEX_FLOATS];
            int[] colourData = new int[vertices];
            short[] indexData = new short[indices];
            int carriedIndices = 0;
            for (int mesh = 0; mesh < meshes; mesh++) {
                check(canceled);
                int at = mesh * 6;
                var part = referencedMesh(modelParts, modelTable, owners, mesh);
                if (modelNamed[owners[mesh]]) {
                    if (part != null) {
                        System.arraycopy(
                                part.vertices(),
                                0,
                                vertexData,
                                meshTable[at] * VERTEX_FLOATS,
                                part.vertices().length);
                    }
                    continue;
                }
                carriedIndices += meshTable[at + 3];
                int end = (meshTable[at] + meshTable[at + 1]) * VERTEX_FLOATS;
                for (int i = meshTable[at] * VERTEX_FLOATS; i < end; i++) {
                    vertexData[i] = Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
                }
            }
            for (int mesh = 0; mesh < meshes; mesh++) {
                int at = mesh * 6;
                var part = referencedMesh(modelParts, modelTable, owners, mesh);
                if (modelNamed[owners[mesh]]) {
                    if (part != null) {
                        System.arraycopy(
                                part.colours(),
                                0,
                                colourData,
                                meshTable[at],
                                part.colours().length);
                    }
                    continue;
                }
                for (int i = meshTable[at]; i < meshTable[at] + meshTable[at + 1]; i++) {
                    colourData[i] = Integer.reverseBytes(input.readInt());
                }
            }
            for (int mesh = 0; mesh < meshes; mesh++) {
                int at = mesh * 6;
                var part = referencedMesh(modelParts, modelTable, owners, mesh);
                if (modelNamed[owners[mesh]]) {
                    if (part != null) {
                        System.arraycopy(
                                part.indices(),
                                0,
                                indexData,
                                meshTable[at + 2],
                                part.indices().length);
                    }
                    continue;
                }
                for (int i = meshTable[at + 2]; i < meshTable[at + 2] + meshTable[at + 3]; i++) {
                    indexData[i] = Short.reverseBytes(input.readShort());
                }
            }
            if ((carriedIndices & 1) != 0) {
                require(input.readShort() == 0, "Index padding");
            }
            byte[] pixelData = new byte[imageBytes];
            int pixelAt = 0;
            for (int image = 0; image < images; image++) {
                check(canceled);
                int width = imageTable[image * IMAGE_INTS],
                        height = imageTable[image * IMAGE_INTS + 1];
                int format = imageTable[image * IMAGE_INTS + 2];
                require(
                        ObjectTextureFormats.valid(format)
                                && width >= 1
                                && width <= MAX_IMAGE_SIDE
                                && height >= 1
                                && height <= MAX_IMAGE_SIDE,
                        "Image table");
                int stored = ObjectTextureFormats.chainBytes(format, width, height);
                require((long) pixelAt + stored <= imageBytes, "Image bytes");
                var part = imageParts[image];
                if (imageNamed[image]) {
                    require(
                            part == null
                                    || part.format() == format
                                            && part.width() == width
                                            && part.height() == height
                                            && part.chain().length == stored,
                            "Referenced image");
                    if (part != null) {
                        System.arraycopy(part.chain(), 0, pixelData, pixelAt, stored);
                    }
                } else {
                    input.readFully(pixelData, pixelAt, stored);
                }
                pixelAt += stored;
            }
            require(pixelAt == imageBytes, "Image bytes");
            check(canceled);
            int[] meshGroupData = ints(input, meshes);
            for (int mesh = 0; mesh < meshes; mesh++) {
                var part = referencedMesh(modelParts, modelTable, owners, mesh);
                require(
                        part == null || part.group() == meshGroupData[mesh],
                        "Referenced mesh group");
            }
            int[] portalSetData = ints(input, portalSets * PORTAL_SET_INTS);
            int[] groupAt = new int[portalSets + 1], portalAt = new int[portalSets + 1];
            int[] vertexOf = new int[portalSets + 1], refAt = new int[portalSets + 1];
            boolean[] setOf = new boolean[models];
            for (int set = 0; set < portalSets; set++) {
                int at = set * PORTAL_SET_INTS;
                int model = portalSetData[at];
                require(model >= 0 && model < models, "Portal set model");
                for (int k = 1; k < PORTAL_SET_INTS; k++) {
                    require(portalSetData[at + k] >= 0, "Portal set counts");
                }
                groupAt[set + 1] = groupAt[set] + portalSetData[at + 1];
                portalAt[set + 1] = portalAt[set] + portalSetData[at + 2];
                vertexOf[set + 1] = vertexOf[set] + portalSetData[at + 3];
                refAt[set + 1] = refAt[set] + portalSetData[at + 4];
                var part = modelParts[model];
                if (part != null) {
                    var p = part.portals();
                    require(
                            p != null
                                    && p.groups().length
                                            == portalSetData[at + 1] * PORTAL_GROUP_INTS
                                    && p.vertexCounts().length == portalSetData[at + 2]
                                    && p.vertices().length == portalSetData[at + 3] * 3
                                    && p.refs().length == portalSetData[at + 4] * PORTAL_REF_INTS,
                            "Referenced portal set");
                }
                setOf[model] = true;
            }
            require(
                    groupAt[portalSets] == portalGroups
                            && portalAt[portalSets] == portals
                            && vertexOf[portalSets] == portalVertices
                            && refAt[portalSets] == portalRefs,
                    "Portal set counts");
            for (int m = 0; m < models; m++) {
                require(
                        modelParts[m] == null || (modelParts[m].portals() != null) == setOf[m],
                        "Referenced portal set");
            }
            int[] portalGroupData = new int[portalGroups * PORTAL_GROUP_INTS];
            float[] portalBoxData = new float[portalGroups * PORTAL_BOX_FLOATS];
            int[] portalVertexCountData = new int[portals];
            float[] portalPlaneData = new float[portals * PORTAL_PLANE_FLOATS];
            float[] portalVertexData = new float[portalVertices * 3];
            int[] portalRefData = new int[portalRefs * PORTAL_REF_INTS];
            for (int set = 0; set < portalSets; set++) {
                int owner = portalSetData[set * PORTAL_SET_INTS];
                var part = modelParts[owner];
                if (modelNamed[owner] && part == null) {
                    continue; // a missing asset: raised after the integrity check
                }
                fill(
                        input,
                        part == null ? null : part.portals().groups(),
                        portalGroupData,
                        groupAt[set] * PORTAL_GROUP_INTS,
                        groupAt[set + 1] * PORTAL_GROUP_INTS);
            }
            for (int set = 0; set < portalSets; set++) {
                int owner = portalSetData[set * PORTAL_SET_INTS];
                var part = modelParts[owner];
                if (modelNamed[owner] && part == null) {
                    continue; // a missing asset: raised after the integrity check
                }
                fill(
                        input,
                        part == null ? null : part.portals().boxes(),
                        portalBoxData,
                        groupAt[set] * PORTAL_BOX_FLOATS,
                        groupAt[set + 1] * PORTAL_BOX_FLOATS);
            }
            for (int set = 0; set < portalSets; set++) {
                int owner = portalSetData[set * PORTAL_SET_INTS];
                var part = modelParts[owner];
                if (modelNamed[owner] && part == null) {
                    continue; // a missing asset: raised after the integrity check
                }
                fill(
                        input,
                        part == null ? null : part.portals().vertexCounts(),
                        portalVertexCountData,
                        portalAt[set],
                        portalAt[set + 1]);
            }
            for (int set = 0; set < portalSets; set++) {
                int owner = portalSetData[set * PORTAL_SET_INTS];
                var part = modelParts[owner];
                if (modelNamed[owner] && part == null) {
                    continue; // a missing asset: raised after the integrity check
                }
                fill(
                        input,
                        part == null ? null : part.portals().planes(),
                        portalPlaneData,
                        portalAt[set] * PORTAL_PLANE_FLOATS,
                        portalAt[set + 1] * PORTAL_PLANE_FLOATS);
            }
            for (int set = 0; set < portalSets; set++) {
                int owner = portalSetData[set * PORTAL_SET_INTS];
                var part = modelParts[owner];
                if (modelNamed[owner] && part == null) {
                    continue; // a missing asset: raised after the integrity check
                }
                fill(
                        input,
                        part == null ? null : part.portals().vertices(),
                        portalVertexData,
                        vertexOf[set] * 3,
                        vertexOf[set + 1] * 3);
            }
            for (int set = 0; set < portalSets; set++) {
                int owner = portalSetData[set * PORTAL_SET_INTS];
                var part = modelParts[owner];
                if (modelNamed[owner] && part == null) {
                    continue; // a missing asset: raised after the integrity check
                }
                fill(
                        input,
                        part == null ? null : part.portals().refs(),
                        portalRefData,
                        refAt[set] * PORTAL_REF_INTS,
                        refAt[set + 1] * PORTAL_REF_INTS);
            }
            var animationList = new java.util.TreeMap<Integer, Object[]>();
            long animationLeft = animationBytes;
            for (int i = 0; i < animated; i++) {
                check(canceled);
                require(animationLeft >= 12, "Animation entry bounds");
                int model = Integer.reverseBytes(input.readInt());
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
                int[] sources = ints(input, count);
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                for (int pad = length; pad % 4 != 0; pad++) {
                    require(input.readByte() == 0, "Animation padding");
                }
                require(
                        animationList.put(model, new Object[] {sources, bytes}) == null,
                        "Animated models ascend");
            }
            require(animationLeft == 0, "Animation section length");
            for (int m = 0; m < models; m++) {
                var part = modelParts[m];
                if (part != null && part.animationSources() != null) {
                    require(
                            animationList.put(
                                            m,
                                            new Object[] {
                                                part.animationSources(), part.animation()
                                            })
                                    == null,
                            "Animated models ascend");
                }
            }
            int[] animatedModels = new int[animationList.size()];
            int[][] animationSources = new int[animatedModels.length][];
            byte[][] animationData = new byte[animatedModels.length][];
            int a = 0;
            for (var entry : animationList.entrySet()) {
                animatedModels[a] = entry.getKey();
                animationSources[a] = (int[]) entry.getValue()[0];
                animationData[a] = (byte[]) entry.getValue()[1];
                a++;
            }
            var liquidList = new java.util.TreeMap<Integer, Object[]>();
            long liquidLeft = liquidBytes;
            for (int i = 0; i < liquidCount; i++) {
                check(canceled);
                require(liquidLeft >= 32, "Liquid entry bounds");
                int mesh = Integer.reverseBytes(input.readInt());
                int kind = Integer.reverseBytes(input.readInt());
                int colour = Integer.reverseBytes(input.readInt());
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
                float[] corner = floats(input, 3);
                float[] heights = floats(input, xv * yv);
                byte[] live = new byte[(xv - 1) * (yv - 1)];
                input.readFully(live);
                for (int pad = live.length; pad % 4 != 0; pad++) {
                    require(input.readByte() == 0, "Liquid padding");
                }
                require(
                        liquidList.put(
                                        mesh,
                                        new Object[] {
                                            new int[] {kind, colour, xv, yv}, corner, heights, live
                                        })
                                == null,
                        "Liquid meshes ascend");
            }
            require(liquidLeft == 0, "Liquid section length");
            check(canceled);
            if (input.read() != -1
                    || channel.size() != reference.length()
                    || !HexFormat.of().formatHex(digest.digest()).equals(reference.sha256())) {
                throw new IOException("Objects bytes or digest differ");
            }
            named.accept(references);
            if (!missing.isEmpty()) {
                throw new MissingAssets(missing);
            }
            for (int m = 0; m < models; m++) {
                var part = modelParts[m];
                if (part == null) {
                    continue;
                }
                for (var lp : part.liquids()) {
                    require(
                            liquidList.put(
                                            modelTable[m * 2] + lp.mesh(),
                                            new Object[] {
                                                new int[] {
                                                    lp.kind(),
                                                    lp.colour(),
                                                    lp.xVertices(),
                                                    lp.yVertices()
                                                },
                                                lp.corner(),
                                                lp.heights(),
                                                lp.live()
                                            })
                                    == null,
                            "Liquid meshes ascend");
                }
            }
            int n = liquidList.size();
            int[] liquidMeshes = new int[n], liquidKinds = new int[n];
            int[] liquidColours = new int[n], liquidGrids = new int[n * 2];
            float[] liquidCorners = new float[n * 3];
            float[][] liquidHeights = new float[n][];
            byte[][] liquidLive = new byte[n][];
            int l = 0;
            for (var entry : liquidList.entrySet()) {
                int[] headInts = (int[]) entry.getValue()[0];
                liquidMeshes[l] = entry.getKey();
                liquidKinds[l] = headInts[0];
                liquidColours[l] = headInts[1];
                liquidGrids[l * 2] = headInts[2];
                liquidGrids[l * 2 + 1] = headInts[3];
                System.arraycopy((float[]) entry.getValue()[1], 0, liquidCorners, l * 3, 3);
                liquidHeights[l] = (float[]) entry.getValue()[2];
                liquidLive[l] = (byte[]) entry.getValue()[3];
                l++;
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

    /** The asset mesh of a referenced model's mesh, or null for a carried mesh. */
    private static WorldStreamObjectAssets.MeshPart referencedMesh(
            WorldStreamObjectAssets.ModelPart[] parts, int[] modelTable, int[] owners, int mesh) {
        var part = parts[owners[mesh]];
        return part == null ? null : part.meshes().get(mesh - modelTable[owners[mesh] * 2]);
    }

    /** Fills {@code target[from, to)} from {@code asset}, or from the stream without one. */
    private static void fill(DataInputStream input, int[] asset, int[] target, int from, int to)
            throws IOException {
        if (asset != null) {
            System.arraycopy(asset, 0, target, from, to - from);
            return;
        }
        for (int i = from; i < to; i++) {
            target[i] = Integer.reverseBytes(input.readInt());
        }
    }

    private static void fill(DataInputStream input, float[] asset, float[] target, int from, int to)
            throws IOException {
        if (asset != null) {
            require(asset.length == to - from, "Referenced portal set");
            System.arraycopy(asset, 0, target, from, to - from);
            return;
        }
        for (int i = from; i < to; i++) {
            target[i] = Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
        }
    }

    /**
     * The header: logical counts of {@code data} (every model, mesh, vertex, index, image byte and
     * portal table row, carried or referenced), the animation and liquid sections as written (the
     * carried models' entries), and the reference counts at 200-207.
     */
    private static byte[] encodeHeader(
            Identity identity, Data data, References references, long length) {
        int animated = 0, animationBytes = 0, liquidCount = 0, liquidBytes = 0;
        for (int i = 0; i < data.animations.count(); i++) {
            if (!references.model(data.animations.models[i])) {
                animated++;
                animationBytes +=
                        (int)
                                Animations.entryBytes(
                                        data.animations.sources[i].length,
                                        data.animations.animations[i].length);
            }
        }
        for (int i = 0; i < data.liquids.count(); i++) {
            if (!references.model(owner(data, data.liquids.meshes[i]))) {
                liquidCount++;
                liquidBytes +=
                        (int)
                                Liquids.entryBytes(
                                        data.liquids.grids[i * 2], data.liquids.grids[i * 2 + 1]);
            }
        }
        var out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt(HEADER_BYTES).putLong(length);
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
        out.putInt(animated).putInt(animationBytes);
        out.putInt(liquidCount).putInt(liquidBytes);
        out.putInt(200, references.models.length).putInt(204, references.images.length);
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
