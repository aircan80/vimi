package com.vibeminer.rendering.world;

/**
 * The one set of bounds for a WMO-only instance interior, shared by the Source factory, the
 * standalone scene file and the GPU child so they cannot drift apart.
 *
 * <p>Sized from a survey of every WMO-only instance map in a current classic-era build: the largest
 * (Sunken Temple) has 122 groups, about 243k vertices, 360k triangles, 1234 batches and 47 images;
 * the most images (Maraudon) is 64 and the most materials 68. Each bound leaves headroom over that.
 * Images are drawn at most 256 pixels on a side (larger sources use a smaller recorded mip), so the
 * worst-case scene file stays near 48 MiB: 10 MiB of vertices, 6 MiB of 32-bit indices and 32 MiB
 * of RGBA. Real instances use well under half of that.
 */
public final class GlobalInteriorLimits {
    private GlobalInteriorLimits() {}

    /** Drawn WMO groups; batch records keep the group in one byte. */
    public static final int GROUPS = 256;

    /** MOMT materials; batch records keep the material in one byte. */
    public static final int MATERIALS = 256;

    /** Draw calls across all groups. */
    public static final int BATCHES = 4096;

    /** Distinct images, one per referenced texture. */
    public static final int IMAGES = 128;

    /** Largest drawn image side; larger sources are drawn from a smaller recorded mip. */
    public static final int IMAGE_SIDE = 256;

    public static final int IMAGE_PIXELS = IMAGE_SIDE * IMAGE_SIDE;
    public static final int PIXELS = IMAGES * IMAGE_PIXELS;

    public static final int VERTICES = 512 << 10;
    public static final int TRIANGLES = VERTICES;
    public static final int INDICES = TRIANGLES * 3;

    /**
     * Per-group bounds: the 16-bit MOVI index domain. Real dungeon groups reach about 17,300
     * vertices and 22,200 triangles; the group span reader applies its own ceilings first.
     */
    public static final int GROUP_VERTICES = 65536, GROUP_TRIANGLES = 65536, GROUP_BATCHES = 64;

    public static final int GROUP_INDICES = GROUP_TRIANGLES * 3;

    /**
     * Largest absolute WMO-local position and texture coordinate the scene file carries. Texture
     * coordinates repeat; many cave and dungeon groups tile far past 16, and at 4096 a 32-bit float
     * still resolves about an eighth of a texel on a 256-pixel image.
     */
    public static final float POSITION = 4096, UV = 4096;

    /** Raw source sizes read for the root, each group and each image. */
    public static final int ROOT_BYTES = 1 << 20, GROUP_BYTES = 8 << 20, IMAGE_BYTES = 1 << 20;

    /** WDT, root, every group and every image. */
    public static final int SOURCES = 2 + GROUPS + IMAGES;

    /** Root, groups and images: every consumed asset after the WDT. */
    public static final int ASSETS = SOURCES - 1;
}
