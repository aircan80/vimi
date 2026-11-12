package com.vibeminer.rendering.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Placed collision meshes (WMO groups and M2 collision meshes) in native coordinates, queried by
 * box. Each placement keeps a shared mesh in its own local space and a transform (rotation times
 * scale, then translation); a query keeps only placements whose world box touches the query box,
 * lets the mesh narrow its triangles with the box taken into local space, and emits those triangles
 * transformed. Immutable and thread-safe once built.
 */
public final class ObjectCollision {
    /** A collision mesh in its own local space. */
    public interface Mesh {
        int triangles();

        /** Vertex index of corner {@code corner} (0..2) of {@code triangle}. */
        int corner(int triangle, int corner);

        /** Local coordinate {@code axis} (0 X, 1 Y, 2 Z) of a vertex. */
        float position(int vertex, int axis);

        /** Offers every blocking triangle that may touch the local box (repeats allowed). */
        void query(
                float x0, float y0, float z0, float x1, float y1, float z1, IntConsumer triangle);

        /** Approximate retained bytes, for budgets. */
        long bytes();
    }

    /**
     * One placement: a mesh, its row-major rotation, uniform scale and translation, and a key that
     * names the source placement (two tiles listing the same building give the same key).
     */
    public record Placement(
            Object key, Mesh mesh, double[] rotation, double scale, double x, double y, double z) {
        public Placement {
            Objects.requireNonNull(key);
            Objects.requireNonNull(mesh);
            rotation = rotation.clone();
            if (rotation.length != 9 || !(scale > 0) || !Double.isFinite(scale)) {
                throw new IllegalArgumentException("Invalid collision placement");
            }
            for (double v : rotation) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("Invalid collision placement");
                }
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid collision placement");
            }
        }

        @Override
        public double[] rotation() {
            return rotation.clone();
        }
    }

    /** Grid cell size in yards: about a sixteenth of a terrain tile. */
    static final double CELL = 32;

    private static final ObjectCollision EMPTY = new ObjectCollision(List.of());

    private final Placed[] placed;
    private final double originX, originY;
    private final int columns, rows;
    private final int[][] cells;
    private final long bytes;

    private record Placed(Mesh mesh, double[] m, double[] inverse, double[] box) {}

    private ObjectCollision(List<Placed> placements) {
        placed = placements.toArray(Placed[]::new);
        double x0 = Double.MAX_VALUE,
                y0 = Double.MAX_VALUE,
                x1 = -Double.MAX_VALUE,
                y1 = -Double.MAX_VALUE;
        var meshes =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Mesh, Boolean>());
        long total = 0;
        for (int i = 0; i < placed.length; i++) {
            var b = placed[i].box();
            x0 = Math.min(x0, b[0]);
            y0 = Math.min(y0, b[1]);
            x1 = Math.max(x1, b[3]);
            y1 = Math.max(y1, b[4]);
            if (meshes.add(placed[i].mesh())) {
                total += placed[i].mesh().bytes();
            }
            total += 160;
        }
        if (placed.length == 0) {
            originX = originY = 0;
            columns = rows = 0;
            cells = new int[0][];
        } else {
            originX = x0;
            originY = y0;
            columns = Math.max(1, (int) Math.ceil((x1 - x0) / CELL));
            rows = Math.max(1, (int) Math.ceil((y1 - y0) / CELL));
            if ((long) columns * rows > 1 << 22) {
                throw new IllegalArgumentException("Collision placements spread too far");
            }
            var lists = new ArrayList<List<Integer>>(columns * rows);
            for (int i = 0; i < columns * rows; i++) {
                lists.add(null);
            }
            for (int i = 0; i < placed.length; i++) {
                var b = placed[i].box();
                int lastColumn = Math.min(columns - 1, column(b[3])),
                        lastRow = Math.min(rows - 1, row(b[4]));
                for (int c = Math.max(0, column(b[0])); c <= lastColumn; c++) {
                    for (int r = Math.max(0, row(b[1])); r <= lastRow; r++) {
                        int at = r * columns + c;
                        if (lists.get(at) == null) {
                            lists.set(at, new ArrayList<>());
                        }
                        lists.get(at).add(i);
                    }
                }
            }
            cells = new int[columns * rows][];
            for (int i = 0; i < cells.length; i++) {
                var list = lists.get(i);
                cells[i] =
                        list == null ? null : list.stream().mapToInt(Integer::intValue).toArray();
                if (cells[i] != null) {
                    total += 16 + cells[i].length * 4L;
                }
            }
        }
        bytes = total;
    }

    public static ObjectCollision empty() {
        return EMPTY;
    }

    /** The placements, the first of each key only (a building listed by two tiles counts once). */
    public static ObjectCollision of(List<Placement> placements) {
        return merge(List.of(prepare(placements)));
    }

    /**
     * Placements with their world transforms and boxes worked out once, ready to {@link #merge}
     * again and again without transforming any triangle a second time. Immutable.
     */
    public static final class Prepared {
        private final List<Object> keys;
        private final List<Placed> placed;

        private Prepared(List<Object> keys, List<Placed> placed) {
            this.keys = keys;
            this.placed = placed;
        }

        public int placements() {
            return placed.size();
        }
    }

    /**
     * Works out every placement's transform and world box (the costly part); see {@link Prepared}.
     */
    public static Prepared prepare(List<Placement> placements) {
        var keys = new ArrayList<Object>();
        var placed = new ArrayList<Placed>();
        for (var p : Objects.requireNonNull(placements)) {
            keys.add(p.key());
            placed.add(place(p));
        }
        return new Prepared(List.copyOf(keys), List.copyOf(placed));
    }

    /**
     * One collision set from prepared parts: only the grid is built here. The first placement of
     * each key is kept, so a building two tiles list counts once.
     */
    public static ObjectCollision merge(List<Prepared> parts) {
        var seen = new HashSet<Object>();
        var kept = new ArrayList<Placed>();
        for (var part : Objects.requireNonNull(parts)) {
            for (int i = 0; i < part.keys.size(); i++) {
                if (seen.add(part.keys.get(i))) {
                    kept.add(part.placed.get(i));
                }
            }
        }
        return kept.isEmpty() ? EMPTY : new ObjectCollision(kept);
    }

    public int placements() {
        return placed.length;
    }

    /** The box around every placement (min x, y, z, max x, y, z), or null when empty. */
    public double[] bounds() {
        if (placed.length == 0) {
            return null;
        }
        double[] out = placed[0].box().clone();
        for (var p : placed) {
            for (int a = 0; a < 3; a++) {
                out[a] = Math.min(out[a], p.box()[a]);
                out[a + 3] = Math.max(out[a + 3], p.box()[a + 3]);
            }
        }
        return out;
    }

    /** Approximate retained bytes: each mesh once, the placements and the grid. */
    public long bytes() {
        return bytes;
    }

    /** Emits the blocking triangles of every placement that may touch the box. */
    public void triangles(
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            TerrainGround.TriangleSink sink) {
        if (placed.length == 0) {
            return;
        }
        int c0 = Math.max(0, column(x0)), c1 = Math.min(columns - 1, column(x1));
        int r0 = Math.max(0, row(y0)), r1 = Math.min(rows - 1, row(y1));
        if (c0 > c1 || r0 > r1) {
            return;
        }
        var visited = new java.util.BitSet(placed.length);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int[] list = cells[r * columns + c];
                if (list == null) {
                    continue;
                }
                for (int i : list) {
                    if (visited.get(i)) {
                        continue;
                    }
                    visited.set(i);
                    emit(placed[i], x0, y0, z0, x1, y1, z1, sink);
                }
            }
        }
    }

    private static void emit(
            Placed p,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            TerrainGround.TriangleSink sink) {
        double[] b = p.box();
        if (b[3] < x0 || b[0] > x1 || b[4] < y0 || b[1] > y1 || b[5] < z0 || b[2] > z1) {
            return;
        }
        double[] local = transformBox(p.inverse(), x0, y0, z0, x1, y1, z1);
        var mesh = p.mesh();
        double[] m = p.m();
        var seen = new java.util.BitSet(mesh.triangles());
        mesh.query(
                (float) local[0],
                (float) local[1],
                (float) local[2],
                (float) local[3],
                (float) local[4],
                (float) local[5],
                t -> {
                    if (seen.get(t)) {
                        return;
                    }
                    seen.set(t);
                    double[] w = new double[9];
                    for (int k = 0; k < 3; k++) {
                        int v = mesh.corner(t, k);
                        double a = mesh.position(v, 0),
                                bb = mesh.position(v, 1),
                                c = mesh.position(v, 2);
                        w[k * 3] = m[0] * a + m[1] * bb + m[2] * c + m[3];
                        w[k * 3 + 1] = m[4] * a + m[5] * bb + m[6] * c + m[7];
                        w[k * 3 + 2] = m[8] * a + m[9] * bb + m[10] * c + m[11];
                    }
                    sink.triangle(w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8]);
                });
    }

    private static Placed place(Placement p) {
        double[] r = p.rotation();
        double s = p.scale();
        double[] m = {
            r[0] * s, r[1] * s, r[2] * s, p.x(),
            r[3] * s, r[4] * s, r[5] * s, p.y(),
            r[6] * s, r[7] * s, r[8] * s, p.z()
        };
        // Inverse of s R: R transposed over s (R is a rotation, so orthonormal).
        double[] inverse = {
            r[0] / s, r[3] / s, r[6] / s, 0,
            r[1] / s, r[4] / s, r[7] / s, 0,
            r[2] / s, r[5] / s, r[8] / s, 0
        };
        for (int row = 0; row < 3; row++) {
            inverse[row * 4 + 3] =
                    -(inverse[row * 4] * p.x()
                            + inverse[row * 4 + 1] * p.y()
                            + inverse[row * 4 + 2] * p.z());
        }
        var mesh = p.mesh();
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        boolean any = false;
        for (int t = 0; t < mesh.triangles(); t++) {
            for (int k = 0; k < 3; k++) {
                int v = mesh.corner(t, k);
                double a = mesh.position(v, 0), b = mesh.position(v, 1), c = mesh.position(v, 2);
                double wx = m[0] * a + m[1] * b + m[2] * c + m[3];
                double wy = m[4] * a + m[5] * b + m[6] * c + m[7];
                double wz = m[8] * a + m[9] * b + m[10] * c + m[11];
                x0 = Math.min(x0, wx);
                y0 = Math.min(y0, wy);
                z0 = Math.min(z0, wz);
                x1 = Math.max(x1, wx);
                y1 = Math.max(y1, wy);
                z1 = Math.max(z1, wz);
                any = true;
            }
        }
        double[] box =
                any
                        ? new double[] {x0, y0, z0, x1, y1, z1}
                        : new double[] {p.x(), p.y(), p.z(), p.x(), p.y(), p.z()};
        return new Placed(mesh, m, inverse, box);
    }

    /** The axis-aligned box around a box's eight corners after an affine transform. */
    private static double[] transformBox(
            double[] m, double x0, double y0, double z0, double x1, double y1, double z1) {
        double[] out = {
            Double.MAX_VALUE,
            Double.MAX_VALUE,
            Double.MAX_VALUE,
            -Double.MAX_VALUE,
            -Double.MAX_VALUE,
            -Double.MAX_VALUE
        };
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? x0 : x1,
                    y = (i & 2) == 0 ? y0 : y1,
                    z = (i & 4) == 0 ? z0 : z1;
            for (int a = 0; a < 3; a++) {
                double v = m[a * 4] * x + m[a * 4 + 1] * y + m[a * 4 + 2] * z + m[a * 4 + 3];
                out[a] = Math.min(out[a], v);
                out[a + 3] = Math.max(out[a + 3], v);
            }
        }
        return out;
    }

    private int column(double x) {
        return (int) Math.floor((x - originX) / CELL);
    }

    private int row(double y) {
        return (int) Math.floor((y - originY) / CELL);
    }
}
