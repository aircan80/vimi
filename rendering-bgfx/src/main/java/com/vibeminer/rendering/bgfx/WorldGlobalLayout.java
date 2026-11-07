package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.GlobalInteriorLimits;

/** Pure standalone structural bounds. No file, source or native ownership is granted. */
public final class WorldGlobalLayout {
    public static final int HEADER_BYTES = 1024;
    // Profile 3 (#297): images keep their source alpha and materials select their range class.
    public static final int PROFILE = 3, OMISSIONS = 0x1ff;
    public static final int SOURCE_BYTES = 64, MATERIAL_BYTES = 64, GROUP_BYTES = 64;
    public static final int BATCH_BYTES = 16, IMAGE_BYTES = 96, INDEX_BYTES = 4;

    /** The largest file the profile bounds allow (about 48 MiB); real interiors are far smaller. */
    public static final int MAX_ENCODED_BYTES =
            HEADER_BYTES
                    + GlobalInteriorLimits.SOURCES * SOURCE_BYTES
                    + GlobalInteriorLimits.MATERIALS * MATERIAL_BYTES
                    + GlobalInteriorLimits.GROUPS * GROUP_BYTES
                    + GlobalInteriorLimits.BATCHES * BATCH_BYTES
                    + GlobalInteriorLimits.IMAGES * IMAGE_BYTES
                    + GlobalInteriorLimits.VERTICES * 20
                    + GlobalInteriorLimits.INDICES * INDEX_BYTES
                    + GlobalInteriorLimits.PIXELS * 4;

    public static final int MAX_FILE_BYTES = 64 << 20;

    private WorldGlobalLayout() {}

    /** Policy bits, set even when this particular source has no omitted operand. */
    public enum Omission {
        SOURCE_LIGHTING(1),
        VERTEX_COLOR(2),
        SOURCE_ALPHA(4),
        LIQUIDS(8),
        DOODADS(16),
        PORTAL_BSP_CULLING(32),
        FLOORS_COLLISION(64),
        UNBATCHED_TRIANGLES(128),
        OUTDOOR_FOG(256);
        private final int bit;

        Omission(int bit) {
            this.bit = bit;
        }

        public int bit() {
            return bit;
        }
    }

    public record Counts(
            int materials,
            int groups,
            int batches,
            int images,
            int vertices,
            int indices,
            int drawnTriangles,
            int pixels) {
        public Counts {
            require(
                    materials >= 1
                            && materials <= GlobalInteriorLimits.MATERIALS
                            && groups >= 1
                            && groups <= GlobalInteriorLimits.GROUPS
                            && batches >= groups
                            && batches <= GlobalInteriorLimits.BATCHES
                            && images >= 1
                            && images <= GlobalInteriorLimits.IMAGES
                            && images <= materials
                            && vertices >= groups
                            && vertices <= GlobalInteriorLimits.VERTICES
                            && (long) vertices
                                    <= (long) groups * GlobalInteriorLimits.GROUP_VERTICES
                            && indices >= groups * 3
                            && indices <= GlobalInteriorLimits.INDICES
                            && (long) indices <= (long) groups * GlobalInteriorLimits.GROUP_INDICES
                            && indices % 3 == 0
                            && drawnTriangles >= batches
                            && drawnTriangles <= indices / 3
                            && pixels >= images
                            && pixels <= GlobalInteriorLimits.PIXELS
                            && (long) pixels <= (long) images * GlobalInteriorLimits.IMAGE_PIXELS);
        }

        public int sources() {
            return 2 + groups + images;
        }

        public int rawTriangles() {
            return indices / 3;
        }

        public Offsets offsets() {
            return WorldGlobalLayout.offsets(this);
        }
    }

    public record Offsets(
            int source,
            int material,
            int group,
            int batch,
            int image,
            int vertices,
            int indices,
            int rgba,
            int actualBytes) {}

    private static Offsets offsets(Counts c) {
        int source = HEADER_BYTES;
        int material = source + c.sources() * SOURCE_BYTES;
        int group = material + c.materials() * MATERIAL_BYTES;
        int batch = group + c.groups() * GROUP_BYTES;
        int image = batch + c.batches() * BATCH_BYTES;
        int vertices = image + c.images() * IMAGE_BYTES;
        int indices = vertices + c.vertices() * 20;
        int rgba = indices + c.indices() * INDEX_BYTES;
        int actual = rgba + c.pixels() * 4;
        require(actual <= MAX_ENCODED_BYTES && actual <= MAX_FILE_BYTES);
        return new Offsets(source, material, group, batch, image, vertices, indices, rgba, actual);
    }

    static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid standalone global structure");
        }
    }

    static String hex(String value, int chars) {
        require(value != null && value.length() == chars);
        for (int i = 0; i < chars; i++) {
            char c = value.charAt(i);
            require(c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
        }
        return value;
    }

    static void unsigned(long value) {
        require(value >= 0 && value <= 0xffff_ffffL);
    }
}
