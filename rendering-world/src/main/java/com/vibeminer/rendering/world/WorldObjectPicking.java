package com.vibeminer.rendering.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact CPU picking of the BASIC exterior object placements one hot-stream objects file draws.
 *
 * <p>A {@link Tile} is compiled from the same validated tables the child uploads: each placement's
 * recorded position, uniform scale and row-major rotation, and each model's drawn index ranges.
 * Only what the {@code world-wmo} pass would rasterize counts as a hit:
 *
 * <ul>
 *   <li>triangles of drawn ranges only, both faces (the pass does not cull);
 *   <li>eye depth above {@link TerrainCamera#NEAR} and at most {@link #FAR_DEPTH} (the fragment
 *       shader discards beyond it);
 *   <li>cutout ranges where the bilinear, repeat-wrapped alpha of their uploaded image is at least
 *       {@link #CUTOUT_REFERENCE} (the shader's alpha test), and blended ranges where it is above
 *       zero; opaque ranges always.
 * </ul>
 *
 * <p>Each model's local bounds are the box of the vertices its drawn ranges reference; they only
 * reject rays early and are never reported as a hit. Pure and immutable; any thread may pick.
 */
public final class WorldObjectPicking {
    /** The {@code world-wmo} fragment shader's discard depth. */
    public static final double FAR_DEPTH = TerrainCamera.WHOLE_TRIANGLE_FAR;

    /** The hot pass's cutout alpha-test reference. */
    public static final float CUTOUT_REFERENCE = 0.5f;

    public static final int OPAQUE = 0, CUTOUT = 1, BLENDED = 2;
    private static final int VERTEX_FLOATS = 8, PLACEMENT_DOUBLES = 13, RANGE_INTS = 4;
    private static final double EPSILON = 1e-12;

    private WorldObjectPicking() {}

    /** The nearest drawn surface under a ray. {@code mesh} and {@code range} are model-relative. */
    public record Hit(
            int placement,
            int model,
            int mesh,
            int range,
            int triangle,
            int material,
            double distance,
            double depth,
            double x,
            double y,
            double z) {}

    /** Retained pick geometry of one objects file. Immutable. */
    public static final class Tile {
        private final int[] models; // first mesh, mesh count
        private final int[] meshRanges; // first range, range count
        private final float[][] positions; // per mesh xyz
        private final float[][] uvs; // per mesh uv
        private final short[][] indices; // per mesh, mesh-relative
        private final int[] ranges; // mesh-relative first index, count, image, class
        private final int[] imageSizes; // width, height
        private final byte[][] alpha; // per image, null when only opaque ranges use it
        private final int[] placementModels;
        private final double[] placements;
        private final double[] bounds; // per model min xyz, max xyz; NaN when it draws nothing
        private final long bytes;

        private Tile(
                int[] models,
                int[] meshRanges,
                float[][] positions,
                float[][] uvs,
                short[][] indices,
                int[] ranges,
                int[] imageSizes,
                byte[][] alpha,
                int[] placementModels,
                double[] placements,
                double[] bounds) {
            this.models = models;
            this.meshRanges = meshRanges;
            this.positions = positions;
            this.uvs = uvs;
            this.indices = indices;
            this.ranges = ranges;
            this.imageSizes = imageSizes;
            this.alpha = alpha;
            this.placementModels = placementModels;
            this.placements = placements;
            this.bounds = bounds;
            long sum =
                    4L * (models.length + meshRanges.length + ranges.length + imageSizes.length)
                            + 4L * placementModels.length
                            + 8L * (placements.length + bounds.length);
            for (int m = 0; m < positions.length; m++) {
                sum += 4L * (positions[m].length + uvs[m].length) + 2L * indices[m].length;
            }
            for (var a : alpha) {
                sum += a == null ? 0 : a.length;
            }
            bytes = sum;
        }

        /**
         * Compiles the uploaded tables of one validated objects file (the layout of the hot
         * stream's {@code WorldStreamObjectsFile}): per model (first mesh, mesh count); per mesh
         * (first vertex, vertex count, first index, index count, first range, range count); per
         * range (mesh-relative first index, index count, image, class); per placement its model and
         * 13 doubles (x, y, z, scale, row-major rotation); per image (width, height); 8 floats per
         * vertex (position, normal, uv); mesh-relative u16 indices; RGBA8 pixels. Positions, UVs
         * and indices are copied, and only the alpha of images that a cutout or blended range
         * samples is kept.
         */
        public static Tile of(
                int[] modelTable,
                int[] meshTable,
                int[] rangeTable,
                int[] placementModels,
                double[] placementTable,
                int[] imageTable,
                float[] vertexData,
                short[] indexData,
                byte[] pixelData) {
            Objects.requireNonNull(modelTable);
            Objects.requireNonNull(meshTable);
            Objects.requireNonNull(rangeTable);
            Objects.requireNonNull(placementModels);
            Objects.requireNonNull(placementTable);
            Objects.requireNonNull(imageTable);
            Objects.requireNonNull(vertexData);
            Objects.requireNonNull(indexData);
            Objects.requireNonNull(pixelData);
            int modelCount = modelTable.length / 2, meshCount = meshTable.length / 6;
            int rangeCount = rangeTable.length / RANGE_INTS, imageCount = imageTable.length / 2;
            require(
                    modelTable.length % 2 == 0
                            && meshTable.length % 6 == 0
                            && rangeTable.length % RANGE_INTS == 0
                            && imageTable.length % 2 == 0
                            && placementTable.length == placementModels.length * PLACEMENT_DOUBLES
                            && vertexData.length % VERTEX_FLOATS == 0,
                    "table shapes");
            var imageOffsets = new long[imageCount + 1];
            for (int i = 0; i < imageCount; i++) {
                require(imageTable[i * 2] > 0 && imageTable[i * 2 + 1] > 0, "image size");
                imageOffsets[i + 1] =
                        imageOffsets[i] + 4L * imageTable[i * 2] * imageTable[i * 2 + 1];
            }
            require(imageOffsets[imageCount] == pixelData.length, "pixel data");
            var sampled = new boolean[imageCount];
            for (int r = 0; r < rangeCount; r++) {
                int image = rangeTable[r * RANGE_INTS + 2], kind = rangeTable[r * RANGE_INTS + 3];
                require(image >= 0 && image < imageCount, "range image");
                require(kind >= OPAQUE && kind <= BLENDED, "range class");
                if (kind != OPAQUE) {
                    sampled[image] = true;
                }
            }
            var alpha = new byte[imageCount][];
            for (int i = 0; i < imageCount; i++) {
                if (!sampled[i]) {
                    continue;
                }
                int count = imageTable[i * 2] * imageTable[i * 2 + 1];
                var a = new byte[count];
                int at = (int) imageOffsets[i] + 3;
                for (int p = 0; p < count; p++) {
                    a[p] = pixelData[at + p * 4];
                }
                alpha[i] = a;
            }
            var positions = new float[meshCount][];
            var uvs = new float[meshCount][];
            var indices = new short[meshCount][];
            var meshRanges = new int[meshCount * 2];
            for (int m = 0; m < meshCount; m++) {
                int firstVertex = meshTable[m * 6], vertices = meshTable[m * 6 + 1];
                int firstIndex = meshTable[m * 6 + 2], indexCount = meshTable[m * 6 + 3];
                int firstRange = meshTable[m * 6 + 4], ranges = meshTable[m * 6 + 5];
                require(
                        firstVertex >= 0
                                && vertices >= 0
                                && (long) (firstVertex + vertices) * VERTEX_FLOATS
                                        <= vertexData.length
                                && firstIndex >= 0
                                && indexCount >= 0
                                && (long) firstIndex + indexCount <= indexData.length
                                && firstRange >= 0
                                && ranges >= 0
                                && (long) firstRange + ranges <= rangeCount,
                        "mesh table");
                var p = new float[vertices * 3];
                var t = new float[vertices * 2];
                for (int v = 0; v < vertices; v++) {
                    int at = (firstVertex + v) * VERTEX_FLOATS;
                    p[v * 3] = vertexData[at];
                    p[v * 3 + 1] = vertexData[at + 1];
                    p[v * 3 + 2] = vertexData[at + 2];
                    t[v * 2] = vertexData[at + 6];
                    t[v * 2 + 1] = vertexData[at + 7];
                }
                positions[m] = p;
                uvs[m] = t;
                indices[m] = Arrays.copyOfRange(indexData, firstIndex, firstIndex + indexCount);
                for (int i : indices[m]) {
                    require((i & 0xffff) < vertices, "mesh index");
                }
                for (int r = firstRange; r < firstRange + ranges; r++) {
                    long first = rangeTable[r * RANGE_INTS], count = rangeTable[r * RANGE_INTS + 1];
                    require(first >= 0 && count >= 0 && first + count <= indexCount, "range span");
                }
                meshRanges[m * 2] = firstRange;
                meshRanges[m * 2 + 1] = ranges;
            }
            var bounds = new double[modelCount * 6];
            for (int model = 0; model < modelCount; model++) {
                int first = modelTable[model * 2], count = modelTable[model * 2 + 1];
                require(first >= 0 && count >= 0 && (long) first + count <= meshCount, "model");
                double[] box = {
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY
                };
                for (int m = first; m < first + count; m++) {
                    for (int r = meshRanges[m * 2];
                            r < meshRanges[m * 2] + meshRanges[m * 2 + 1];
                            r++) {
                        int start = rangeTable[r * RANGE_INTS],
                                end = start + rangeTable[r * RANGE_INTS + 1];
                        for (int i = start; i < end; i++) {
                            int v = indices[m][i] & 0xffff;
                            for (int axis = 0; axis < 3; axis++) {
                                double value = positions[m][v * 3 + axis];
                                box[axis] = Math.min(box[axis], value);
                                box[axis + 3] = Math.max(box[axis + 3], value);
                            }
                        }
                    }
                }
                if (!(box[0] <= box[3])) {
                    Arrays.fill(box, Double.NaN);
                }
                System.arraycopy(box, 0, bounds, model * 6, 6);
            }
            for (int p = 0; p < placementModels.length; p++) {
                require(placementModels[p] >= 0 && placementModels[p] < modelCount, "placement");
                for (int d = 0; d < PLACEMENT_DOUBLES; d++) {
                    require(
                            Double.isFinite(placementTable[p * PLACEMENT_DOUBLES + d]),
                            "transform");
                }
                require(placementTable[p * PLACEMENT_DOUBLES + 3] > 0, "scale");
            }
            return new Tile(
                    modelTable.clone(),
                    meshRanges,
                    positions,
                    uvs,
                    indices,
                    rangeTable.clone(),
                    imageTable.clone(),
                    alpha,
                    placementModels.clone(),
                    placementTable.clone(),
                    bounds);
        }

        public int placements() {
            return placementModels.length;
        }

        public int model(int placement) {
            return placementModels[placement];
        }

        /** Retained heap bytes of the pick geometry (arrays only). */
        public long retainedBytes() {
            return bytes;
        }

        /** x, y, z, scale and the row-major rotation of one placement, as uploaded. */
        public double[] transform(int placement) {
            int at = placement * PLACEMENT_DOUBLES;
            return Arrays.copyOfRange(placements, at, at + PLACEMENT_DOUBLES);
        }

        /**
         * The 8 world corners (x, y, z each) of one placement's drawn local bounds, through its
         * recorded transform; empty when its model draws no triangle. Corner i has local min/max x,
         * y, z chosen by bits 0, 1 and 2 of i.
         */
        public Optional<double[]> corners(int placement) {
            int model = placementModels[placement];
            if (Double.isNaN(bounds[model * 6])) {
                return Optional.empty();
            }
            var out = new double[24];
            for (int i = 0; i < 8; i++) {
                double lx = bounds[model * 6 + ((i & 1) == 0 ? 0 : 3)];
                double ly = bounds[model * 6 + ((i & 2) == 0 ? 1 : 4)];
                double lz = bounds[model * 6 + ((i & 4) == 0 ? 2 : 5)];
                var world = toWorld(placement, lx, ly, lz);
                System.arraycopy(world, 0, out, i * 3, 3);
            }
            return Optional.of(out);
        }

        private double[] toWorld(int placement, double lx, double ly, double lz) {
            int at = placement * PLACEMENT_DOUBLES;
            double s = placements[at + 3];
            int r = at + 4;
            return new double[] {
                placements[at]
                        + s
                                * (placements[r] * lx
                                        + placements[r + 1] * ly
                                        + placements[r + 2] * lz),
                placements[at + 1]
                        + s
                                * (placements[r + 3] * lx
                                        + placements[r + 4] * ly
                                        + placements[r + 5] * lz),
                placements[at + 2]
                        + s
                                * (placements[r + 6] * lx
                                        + placements[r + 7] * ly
                                        + placements[r + 8] * lz)
            };
        }

        /** Bilinear repeat-wrapped alpha in [0, 1] of one image at a UV (texel centers at +0.5). */
        double alpha(int image, double u, double v) {
            var a = alpha[image];
            int w = imageSizes[image * 2], h = imageSizes[image * 2 + 1];
            double fx = u * w - .5, fy = v * h - .5;
            double x0 = Math.floor(fx), y0 = Math.floor(fy);
            double tx = fx - x0, ty = fy - y0;
            int ix = Math.floorMod((long) x0, w), iy = Math.floorMod((long) y0, h);
            int jx = (ix + 1) % w, jy = (iy + 1) % h;
            double a00 = a[iy * w + ix] & 0xff, a10 = a[iy * w + jx] & 0xff;
            double a01 = a[jy * w + ix] & 0xff, a11 = a[jy * w + jx] & 0xff;
            double top = a00 + (a10 - a00) * tx, bottom = a01 + (a11 - a01) * tx;
            return (top + (bottom - top) * ty) / 255;
        }
    }

    /**
     * The nearest drawn surface of {@code tile} along a world ray from the camera eye (the
     * direction need not be unit length; distances are in its units), no farther than {@code
     * maxDistance}. Empty when none is hit.
     */
    public static Optional<Hit> pick(
            Tile tile, TerrainCamera camera, double dx, double dy, double dz, double maxDistance) {
        Objects.requireNonNull(tile);
        Objects.requireNonNull(camera);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 0) || !Double.isFinite(length) || !(maxDistance > 0)) {
            return Optional.empty();
        }
        dx /= length;
        dy /= length;
        dz /= length;
        double ox = camera.x(), oy = camera.y(), oz = camera.z();
        // Broad phase: every placement whose drawn local box the ray enters, nearest entry first.
        var candidates = new ArrayList<double[]>();
        var local = new double[6];
        for (int p = 0; p < tile.placementModels.length; p++) {
            int model = tile.placementModels[p];
            if (Double.isNaN(tile.bounds[model * 6])) {
                continue;
            }
            toLocal(tile, p, ox, oy, oz, dx, dy, dz, local);
            double enter = enter(tile.bounds, model * 6, local, maxDistance);
            if (enter >= 0) {
                candidates.add(new double[] {enter, p});
            }
        }
        candidates.sort((a, b) -> Double.compare(a[0], b[0]));
        Hit best = null;
        double bestT = maxDistance;
        for (var candidate : candidates) {
            if (candidate[0] > bestT) {
                break;
            }
            int p = (int) candidate[1], model = tile.placementModels[p];
            toLocal(tile, p, ox, oy, oz, dx, dy, dz, local);
            int firstMesh = tile.models[model * 2];
            for (int m = firstMesh; m < firstMesh + tile.models[model * 2 + 1]; m++) {
                var pos = tile.positions[m];
                var idx = tile.indices[m];
                int firstRange = tile.meshRanges[m * 2];
                for (int r = firstRange; r < firstRange + tile.meshRanges[m * 2 + 1]; r++) {
                    int start = tile.ranges[r * RANGE_INTS];
                    int end = start + tile.ranges[r * RANGE_INTS + 1];
                    int kind = tile.ranges[r * RANGE_INTS + 3];
                    for (int i = start; i + 2 < end; i += 3) {
                        int a = idx[i] & 0xffff, b = idx[i + 1] & 0xffff, c = idx[i + 2] & 0xffff;
                        var bary = intersect(pos, a, b, c, local, bestT);
                        if (bary == null) {
                            continue;
                        }
                        double t = bary[0];
                        double wx = ox + dx * t, wy = oy + dy * t, wz = oz + dz * t;
                        double depth = camera.eye(wx, wy, wz).depth();
                        if (!(depth > TerrainCamera.NEAR) || depth > FAR_DEPTH) {
                            continue;
                        }
                        if (kind != OPAQUE) {
                            var uv = tile.uvs[m];
                            double w0 = 1 - bary[1] - bary[2];
                            double u = w0 * uv[a * 2] + bary[1] * uv[b * 2] + bary[2] * uv[c * 2];
                            double v =
                                    w0 * uv[a * 2 + 1]
                                            + bary[1] * uv[b * 2 + 1]
                                            + bary[2] * uv[c * 2 + 1];
                            double alpha = tile.alpha(tile.ranges[r * RANGE_INTS + 2], u, v);
                            if (kind == CUTOUT ? alpha < CUTOUT_REFERENCE : !(alpha > 0)) {
                                continue;
                            }
                        }
                        bestT = t;
                        best =
                                new Hit(
                                        p,
                                        model,
                                        m - firstMesh,
                                        r - firstRange,
                                        (i - start) / 3,
                                        kind,
                                        t,
                                        depth,
                                        wx,
                                        wy,
                                        wz);
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The ray in a placement's model space: origin then direction (same parameter t). */
    private static void toLocal(
            Tile tile,
            int p,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double[] out) {
        var t = tile.placements;
        int at = p * PLACEMENT_DOUBLES, r = at + 4;
        double s = t[at + 3];
        double px = ox - t[at], py = oy - t[at + 1], pz = oz - t[at + 2];
        // The rotation is orthonormal (the file reader checks it), so its inverse is its transpose.
        out[0] = (t[r] * px + t[r + 3] * py + t[r + 6] * pz) / s;
        out[1] = (t[r + 1] * px + t[r + 4] * py + t[r + 7] * pz) / s;
        out[2] = (t[r + 2] * px + t[r + 5] * py + t[r + 8] * pz) / s;
        out[3] = (t[r] * dx + t[r + 3] * dy + t[r + 6] * dz) / s;
        out[4] = (t[r + 1] * dx + t[r + 4] * dy + t[r + 7] * dz) / s;
        out[5] = (t[r + 2] * dx + t[r + 5] * dy + t[r + 8] * dz) / s;
    }

    /** Slab test: the clamped entry distance into the box, or -1 when the ray misses it. */
    private static double enter(double[] bounds, int at, double[] ray, double maxDistance) {
        double near = 0, far = maxDistance;
        for (int axis = 0; axis < 3; axis++) {
            double origin = ray[axis], direction = ray[axis + 3];
            double min = bounds[at + axis], max = bounds[at + axis + 3];
            if (Math.abs(direction) < EPSILON) {
                if (origin < min || origin > max) {
                    return -1;
                }
                continue;
            }
            double t0 = (min - origin) / direction, t1 = (max - origin) / direction;
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            near = Math.max(near, t0);
            far = Math.min(far, t1);
            if (near > far) {
                return -1;
            }
        }
        return near;
    }

    /**
     * Two-sided Moller-Trumbore in model space: {t, u, v} for a hit with 0 &lt; t &lt;= limit, or
     * null.
     */
    private static double[] intersect(
            float[] pos, int a, int b, int c, double[] ray, double limit) {
        double ax = pos[a * 3], ay = pos[a * 3 + 1], az = pos[a * 3 + 2];
        double e1x = pos[b * 3] - ax, e1y = pos[b * 3 + 1] - ay, e1z = pos[b * 3 + 2] - az;
        double e2x = pos[c * 3] - ax, e2y = pos[c * 3 + 1] - ay, e2z = pos[c * 3 + 2] - az;
        double dx = ray[3], dy = ray[4], dz = ray[5];
        double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < EPSILON) {
            return null;
        }
        double inv = 1 / det;
        double sx = ray[0] - ax, sy = ray[1] - ay, sz = ray[2] - az;
        double u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) {
            return null;
        }
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0 || u + v > 1) {
            return null;
        }
        double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        if (!(t > 0) || t > limit) {
            return null;
        }
        return new double[] {t, u, v};
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("Object pick tables: " + what);
        }
    }
}
