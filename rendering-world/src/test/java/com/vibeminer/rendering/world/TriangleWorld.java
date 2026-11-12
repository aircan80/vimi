package com.vibeminer.rendering.world;

import java.util.ArrayList;
import java.util.List;

/** Authored collision geometry for tests: quads and boxes, known inside a square footprint. */
final class TriangleWorld implements CollisionWorld {
    private final List<double[]> triangles = new ArrayList<>();
    private double known = 1_000;

    /** Only points with |x| and |y| at most {@code half} are known; elsewhere is LOADING. */
    TriangleWorld known(double half) {
        known = half;
        return this;
    }

    TriangleWorld triangle(double... corners) {
        triangles.add(corners.clone());
        return this;
    }

    /** A quad a-b-c-d as two triangles. */
    TriangleWorld quad(double[] a, double[] b, double[] c, double[] d) {
        triangle(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
        return triangle(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2]);
    }

    /** A horizontal floor at height z over the rectangle. */
    TriangleWorld floor(double x0, double y0, double x1, double y1, double z) {
        return quad(
                new double[] {x0, y0, z},
                new double[] {x1, y0, z},
                new double[] {x1, y1, z},
                new double[] {x0, y1, z});
    }

    /** A vertical wall across Y at x, from z0 to z1. */
    TriangleWorld wallX(double x, double y0, double y1, double z0, double z1) {
        return quad(
                new double[] {x, y0, z0},
                new double[] {x, y1, z0},
                new double[] {x, y1, z1},
                new double[] {x, y0, z1});
    }

    /** A solid box: its top and four sides. */
    TriangleWorld box(double x0, double y0, double x1, double y1, double z0, double z1) {
        floor(x0, y0, x1, y1, z1);
        wallX(x0, y0, y1, z0, z1);
        wallX(x1, y0, y1, z0, z1);
        quad(
                new double[] {x0, y0, z0},
                new double[] {x1, y0, z0},
                new double[] {x1, y0, z1},
                new double[] {x0, y0, z1});
        return quad(
                new double[] {x0, y1, z0},
                new double[] {x1, y1, z0},
                new double[] {x1, y1, z1},
                new double[] {x0, y1, z1});
    }

    /** A ramp rising along +X from (x0, z0) at {@code degrees}, over Y, for {@code run} yards. */
    TriangleWorld ramp(double x0, double y0, double y1, double z0, double degrees, double run) {
        double z1 = z0 + Math.tan(Math.toRadians(degrees)) * run;
        return quad(
                new double[] {x0, y0, z0},
                new double[] {x0 + run, y0, z1},
                new double[] {x0 + run, y1, z1},
                new double[] {x0, y1, z0});
    }

    @Override
    public void triangles(
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            TerrainGround.TriangleSink sink) {
        for (var t : triangles) {
            double minX = Math.min(t[0], Math.min(t[3], t[6])),
                    maxX = Math.max(t[0], Math.max(t[3], t[6]));
            double minY = Math.min(t[1], Math.min(t[4], t[7])),
                    maxY = Math.max(t[1], Math.max(t[4], t[7]));
            if (maxX < x0 || minX > x1 || maxY < y0 || minY > y1) {
                continue;
            }
            sink.triangle(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8]);
        }
    }

    @Override
    public Coverage coverage(double x0, double y0, double x1, double y1) {
        return Math.max(Math.max(Math.abs(x0), Math.abs(x1)), Math.max(Math.abs(y0), Math.abs(y1)))
                        <= known
                ? Coverage.READY
                : Coverage.LOADING;
    }
}
