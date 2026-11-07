package com.vibeminer.rendering.world;

/**
 * The one set of per-tile bounds for BASIC exterior objects, shared by the producer
 * (WorldExteriorObjects) and the objects file and renderer (WorldStreamObjectsFile), so the pools
 * and the file's tables are derived from the same numbers and cannot drift apart.
 *
 * <p>Three pools never displace each other: opaque buildings, the cutout and blended surfaces of
 * buildings, and doodads (MDDF and WMO set doodads). Creature models share whatever room the file
 * tables leave after them.
 */
public final class ExteriorObjectLimits {
    private ExteriorObjectLimits() {}

    // ---- opaque buildings ----------------------------------------------------------------------

    public static final int BUILDING_PLACEMENTS = 64, BUILDING_ROOTS = 24;
    public static final int BUILDING_MESHES = 256, BUILDING_RANGES = 1024, BUILDING_IMAGES = 64;
    public static final int BUILDING_VERTICES = 98_304, BUILDING_INDICES = 294_912;

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
    public static final int RANGES = BUILDING_RANGES + MATERIAL_RANGES + DOODAD_RANGES;
    public static final int PLACEMENTS = BUILDING_PLACEMENTS + DOODAD_PLACEMENTS;
    public static final int IMAGES = BUILDING_IMAGES + MATERIAL_IMAGES + DOODAD_IMAGES;
    public static final int VERTICES = BUILDING_VERTICES + MATERIAL_VERTICES + DOODAD_VERTICES;
    public static final int INDICES = BUILDING_INDICES + MATERIAL_INDICES + DOODAD_INDICES;
}
