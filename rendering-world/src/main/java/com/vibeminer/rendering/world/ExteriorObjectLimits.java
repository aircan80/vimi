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
    public static final int BUILDING_MESHES = 256, BUILDING_RANGES = 1024, BUILDING_IMAGES = 64;
    public static final int BUILDING_VERTICES = 98_304, BUILDING_INDICES = 294_912;

    // ---- very large buildings drawn by group ----------------------------------------------------

    /**
     * The opaque allowance of very large buildings drawn by group: a placement whose recorded
     * bounds cross a tile border and whose root's exterior alone is over the opaque building pool
     * (Stormwind City's 9 exterior groups need 150,358 vertices and 322,080 indices). Every tile
     * that lists such a placement draws only the groups whose MOGI box centre lies in it, so each
     * group is drawn once over the map, from this allowance and never from the building pool: the
     * tile's own buildings keep their whole budget. Its owned interior groups, culled by the root's
     * portal set, come from what its exteriors leave of this allowance and from the interior
     * allowance ({@link #LARGE_INTERIOR_VERTICES}). On build 70862 the Stormwind tile holding its
     * two largest exterior groups (48,30) needs 111,920 vertices and about 232,000 indices; the
     * other owners need at most 25,383 vertices for exteriors.
     */
    public static final int LARGE_VERTICES = 131_072, LARGE_INDICES = 393_216, LARGE_RANGES = 512;

    /**
     * Opaque images of very large buildings drawn by group, apart from the building images; their
     * pixels come from what the tile's own building images leave of the building pixel budget.
     */
    public static final int LARGE_IMAGES = 64;

    /**
     * The interior allowance of very large buildings drawn by group, on top of what their exteriors
     * leave of the large allowance. Owned interior groups, culled by the root's portal set, are
     * admitted only after every such root's exteriors on the tile, so an exterior never draws from
     * it and never yields to an interior. On build 70862 Stormwind City's 280 interior groups need
     * at most 448,746 vertices and 1,247,094 indices on one owner tile (48,30) with its exteriors,
     * 317,674 vertices and 853,878 indices past the large allowance; the large allowance alone left
     * 115 of them undrawn there.
     */
    public static final int LARGE_INTERIOR_VERTICES = 327_680, LARGE_INTERIOR_INDICES = 983_040;

    /** Ranges and opaque image slots of the interior allowance; pixels as {@link #LARGE_IMAGES}. */
    public static final int LARGE_INTERIOR_RANGES = 1024, LARGE_INTERIOR_IMAGES = 64;

    // ---- cutout and blended building surfaces ---------------------------------------------------

    public static final int MATERIAL_RANGES = 256, MATERIAL_IMAGES = 32;
    public static final int MATERIAL_VERTICES = 32_768, MATERIAL_INDICES = 98_304;

    // ---- doodads -------------------------------------------------------------------------------

    /**
     * Doodad placements per tile (#308). On Elwynn Forest's 3 x 3 tiles around Goldshire the old
     * 512 was the only doodad cap that bound (every one of 8,253 capped doodads and set doodads),
     * with about 1,350 candidates per tile against 36 distinct models. One obj0 records at most
     * 4,096 MDDF entries ({@code WorldPlacements.MAX_RECORDS_PER_KIND}).
     */
    public static final int DOODAD_PLACEMENTS = 4096;

    /** Distinct doodad models read per tile; Zephras Isle needed 48, Elwynn about 36. */
    public static final int DOODAD_MODELS = 96;

    /**
     * Distinct doodad images per tile (#308). With billboard-bearing models admitted (#390) Elwynn
     * tiles reached the old 96. The doodad pixel budget is separate and unchanged, so more images
     * share the same texels at lower levels: the cap costs file table rows, not GPU memory.
     */
    public static final int DOODAD_IMAGES = 192;

    public static final int DOODAD_RANGES = 512;
    public static final int DOODAD_VERTICES = 65_536, DOODAD_INDICES = 196_608;

    /**
     * Draw calls per tile: the sum over placements of their model's ranges. Elwynn doodad models
     * average about two ranges, so {@link #DOODAD_PLACEMENTS} needs about twice as many draws; the
     * renderer culls placements outside the view (#369), so a frame submits far fewer.
     */
    public static final int DRAWS = 2 * DOODAD_PLACEMENTS;

    // ---- objects file tables: the sum of the pools ----------------------------------------------

    public static final int MODELS = BUILDING_ROOTS + DOODAD_MODELS;
    public static final int MESHES = BUILDING_MESHES + DOODAD_MODELS;
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
                    + DOODAD_IMAGES;
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
