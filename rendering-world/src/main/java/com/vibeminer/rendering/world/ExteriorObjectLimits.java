package com.vibeminer.rendering.world;

/**
 * The one set of per-tile bounds for BASIC exterior objects, shared by the producer
 * (WorldExteriorObjects) and the objects file and renderer (WorldStreamObjectsFile), so the pools
 * and the file's tables are derived from the same numbers and cannot drift apart.
 *
 * <p>Four pools never displace each other: opaque buildings, very large buildings drawn by group,
 * the cutout and blended surfaces of buildings, and doodads (MDDF and WMO set doodads). Creature
 * models share whatever room the file tables leave after them.
 */
public final class ExteriorObjectLimits {
    private ExteriorObjectLimits() {}

    // ---- opaque buildings ----------------------------------------------------------------------

    public static final int BUILDING_PLACEMENTS = 64, BUILDING_ROOTS = 24;

    /**
     * Building meshes, ranges and opaque images per tile. Read without image caps on build 70862,
     * the 5 x 5 tiles around Stormwind City, Dalaran (map 0), Gilneas City, Orgrimmar, Ironforge,
     * Undercity and Thunder Bluff use at most 108 opaque building images on one tile (Stormwind's
     * 48,30, its share of the split city included) and 95 elsewhere; the old 64 capped 339 batches
     * around Dalaran, 208 around Gilneas and 43 around Stormwind. The building pixel budget is
     * unchanged, so more images share the same texels at lower levels.
     */
    public static final int BUILDING_MESHES = 256, BUILDING_RANGES = 1024, BUILDING_IMAGES = 128;

    public static final int BUILDING_VERTICES = 98_304, BUILDING_INDICES = 294_912;

    // ---- very large buildings drawn by group ----------------------------------------------------

    /**
     * The opaque allowance of very large buildings drawn by group: a placement whose recorded
     * bounds cross a tile border and whose root's exterior alone is over the opaque building pool
     * (Stormwind City's 9 exterior groups need 150,358 vertices and 322,080 indices). Every tile
     * that lists such a placement draws only the groups whose MOGI box centre lies in it, so each
     * group is drawn once over the map, from this allowance and never from the building pool: the
     * tile's own buildings keep their whole budget. This allowance and the interior allowance
     * ({@link #LARGE_INTERIOR_VERTICES}) are one pool: every such root's exteriors are admitted
     * first against both together, and its owned interior groups, culled by the root's portal set,
     * take what the exteriors left. On build 70862 the Stormwind tile holding its two largest
     * exterior groups (48,30) needs 111,920 vertices and about 232,000 indices; the other owners
     * need at most 25,383 vertices for exteriors. The Forever Dalaran owner tile (0:31,31), whose
     * 18 exterior groups need 162,681 vertices and 491,961 indices, draws them only from the pool
     * with the interior allowance. A root to be drawn whole that does not fit what the tile's
     * building pool left (a single-tile root over an empty pool, such as Gilneas's 125,413-vertex
     * root on 35,29, or a busy tile) is drawn whole from the same pool, after every split root's
     * exteriors and before any interior.
     */
    public static final int LARGE_VERTICES = 131_072, LARGE_INDICES = 393_216, LARGE_RANGES = 512;

    /**
     * Opaque images of very large buildings drawn by group, apart from the building images; their
     * pixels come from what the tile's own building images leave of the building pixel budget.
     */
    public static final int LARGE_IMAGES = 64;

    /**
     * The interior allowance of very large buildings drawn by group, pooled with the large
     * allowance. Owned interior groups, culled by the root's portal set, are admitted only after
     * every such root's exteriors on the tile, from what they left, so within a tile an exterior
     * never yields geometry or slots to an interior. Pixels are shared: the opaque images of these
     * roots' exteriors and interiors are allotted levels from one list, after the tile's own
     * exterior images, so drawing the interiors can lower a split root's exterior image levels
     * slightly. On build 70862 Stormwind City's 280 interior groups need at most 448,746 vertices
     * and 1,247,094 indices on one owner tile (48,30) with its exteriors, 317,674 vertices and
     * 853,878 indices past the large allowance; the large allowance alone left 115 of them undrawn
     * there.
     */
    public static final int LARGE_INTERIOR_VERTICES = 327_680, LARGE_INTERIOR_INDICES = 983_040;

    /**
     * Ranges and opaque image slots of the interior allowance, pooled with the large allowance's;
     * pixels as {@link #LARGE_IMAGES}. The Dalaran owner tile (0:31,31) registers about 154 opaque
     * images for its split root with every group drawn, so the 64 + 96 slots hold them all.
     */
    public static final int LARGE_INTERIOR_RANGES = 1024, LARGE_INTERIOR_IMAGES = 96;

    // ---- cutout and blended building surfaces ---------------------------------------------------

    public static final int MATERIAL_RANGES = 256, MATERIAL_IMAGES = 32;
    public static final int MATERIAL_VERTICES = 32_768, MATERIAL_INDICES = 98_304;

    // ---- doodads -------------------------------------------------------------------------------

    /**
     * Doodad placements per tile (#308). On Elwynn Forest's 3 x 3 tiles around Goldshire the old
     * 512 was the only doodad cap that bound (every one of 8,253 capped doodads and set doodads),
     * with about 1,350 candidates per tile against 36 distinct models. An obj0 may record more MDDF
     * entries than this ({@code WorldPlacements.MAX_RECORDS_PER_KIND}, 16,384; Gilneas City's 34,29
     * records 4,165 on build 70862): those past it are capped (DOODAD_CAPPED), and the tile's other
     * objects are still drawn. With every model and vertex cap lifted, Gilneas City's 34,29 needs
     * 4,814 MDDF and set doodads on build 70862, past the old 4,096 (718 capped); 5,120 draws them
     * all.
     */
    public static final int DOODAD_PLACEMENTS = 5120;

    /**
     * Records of one kind an obj0 may hold, so the bound of a placement's MODF ordinal (the
     * selected building of the World WMO annex): the same as formats-world's {@code
     * WorldPlacements.MAX_RECORDS_PER_KIND}, which modules without that dependency cannot name.
     */
    public static final int OBJ0_RECORDS_PER_KIND = 16_384;

    /**
     * Distinct doodad models read per tile. Zephras Isle needed 48 and Elwynn about 36, but city
     * tiles want far more: on build 70862, read without a model cap, the 5 x 5 tiles around
     * Stormwind City, Orgrimmar, Ironforge and Undercity read at most 270 distinct doodad models on
     * one tile (Orgrimmar's 28,40), 257 on Ironforge's 41,33 and 233 on Stormwind's 50,31; the MDDF
     * doodads of the tile north of Orgrimmar (28,38) alone read 170. The old 96 left 425 MDDF
     * doodads and 259 set doodads undrawn around Orgrimmar and 714 set doodads around Stormwind. At
     * 256 no MDDF doodad was capped there and one set doodad was (on 28,40). With the Forever
     * Dalaran city drawn whole (#659), its owner tile (0:31,31) reads 642 distinct doodad models
     * with every cap lifted, and Gilneas City's 35,27 reads 372: 256 capped 1,178 set doodads on
     * 31,31 and 183 doodads by model count around Gilneas. 31,31 reads 664 models in all (13 of
     * them failed and 9 empty, each still a read), so 640 left 5 set doodads capped there; at 672
     * none is capped in those windows.
     */
    public static final int DOODAD_MODELS = 672;

    /**
     * Distinct doodad images per tile (#308), 1.25 per model. With billboard-bearing models
     * admitted (#390) Elwynn tiles reached the old 96; read without a model cap, the city tiles
     * above need at most 240 doodad images (Ironforge's 39,33), and the Dalaran owner tile
     * (0:31,31) 536; 1.25 per model of {@link #DOODAD_MODELS} is 840. The doodad pixel budget is
     * separate and unchanged, so more images share the same texels at lower levels: the cap costs
     * file table rows, not GPU memory.
     */
    public static final int DOODAD_IMAGES = 840;

    /**
     * Doodad models a tile may read beyond {@link #DOODAD_MODELS}, only for set doodads tied to one
     * drawn interior group of their building's portal set: the group's MODR lists them and no other
     * group the tile read does, so the renderer culls them with the group. They share the doodad
     * vertex, index, range and pixel pools, whose images take levels only after every other doodad
     * image. On build 70862 the 5 x 5 tiles around Stormwind City (0:48:31) capped 1,730 interior
     * set doodads when {@link #DOODAD_MODELS} was 96, at most 125 distinct models on one tile
     * (48,30). With 256 models the city tiles above (Stormwind, Orgrimmar, Ironforge, Undercity)
     * read at most 13 models from this allowance (Orgrimmar's 28,40), so it keeps a margin of about
     * five times that.
     */
    public static final int DOODAD_INTERIOR_MODELS = 64;

    /** Doodad image slots of {@link #DOODAD_INTERIOR_MODELS}, 1.25 per model as above. */
    public static final int DOODAD_INTERIOR_IMAGES = 80;

    /**
     * Doodad geometry per tile. Read without caps on build 70862, Gilneas City's 35,27 needs
     * 149,954 doodad vertices and the Dalaran owner tile (0:31,31) 147,442 vertices, 401,700
     * indices and 946 ranges; the old 65,536 vertices capped 10,649 doodads and set doodads by size
     * around Gilneas and 167 on 31,31. These hold them all, at three indices a vertex.
     */
    public static final int DOODAD_RANGES = 1024;

    public static final int DOODAD_VERTICES = 163_840, DOODAD_INDICES = 491_520;

    /**
     * Draw calls per tile: the sum over placements of their model's ranges. Elwynn doodad models
     * average about two ranges, so the old 4,096 doodad placements were given twice as many draws;
     * the renderer culls placements outside the view (#369), so a frame submits far fewer. On build
     * 70862 the 5 x 5 tiles around Dalaran (map 0), Gilneas City, Stormwind City, Orgrimmar and
     * Undercity use at most 7,947 on one tile (Gilneas City's 34,29) with every doodad drawn, so
     * the budget stays at 8,192 though {@link #DOODAD_PLACEMENTS} grew; a tile past it caps only
     * doodads (DOODAD_CAP_DRAWS).
     */
    public static final int DRAWS = 8192;

    // ---- objects file tables: the sum of the pools ----------------------------------------------

    public static final int MODELS = BUILDING_ROOTS + DOODAD_MODELS + DOODAD_INTERIOR_MODELS;
    public static final int MESHES = BUILDING_MESHES + DOODAD_MODELS + DOODAD_INTERIOR_MODELS;
    public static final int RANGES =
            BUILDING_RANGES
                    + LARGE_RANGES
                    + LARGE_INTERIOR_RANGES
                    + MATERIAL_RANGES
                    + DOODAD_RANGES;
    public static final int PLACEMENTS = BUILDING_PLACEMENTS + DOODAD_PLACEMENTS;
    public static final int IMAGES =
            BUILDING_IMAGES
                    + LARGE_IMAGES
                    + LARGE_INTERIOR_IMAGES
                    + MATERIAL_IMAGES
                    + DOODAD_IMAGES
                    + DOODAD_INTERIOR_IMAGES;
    public static final int VERTICES =
            BUILDING_VERTICES
                    + LARGE_VERTICES
                    + LARGE_INTERIOR_VERTICES
                    + MATERIAL_VERTICES
                    + DOODAD_VERTICES;
    public static final int INDICES =
            BUILDING_INDICES
                    + LARGE_INDICES
                    + LARGE_INTERIOR_INDICES
                    + MATERIAL_INDICES
                    + DOODAD_INDICES;
}
