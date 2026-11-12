package com.vibeminer.rendering.world;

import java.util.Objects;

/**
 * Blocking geometry around a moving body, queried by box. Collision never depends on what is drawn:
 * a source answers from its own retained data, and reports {@link Coverage#LOADING} while any part
 * of the box has no collision data yet, so a body waits instead of falling through geometry that
 * has not arrived.
 */
public interface CollisionWorld {
    /** Whether a box is fully known. */
    enum Coverage {
        READY,
        LOADING
    }

    /**
     * Emits every blocking triangle that may touch the box, in native coordinates (either winding;
     * callers treat triangles as two-sided).
     */
    void triangles(
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            TerrainGround.TriangleSink sink);

    /** READY when every point of the box's XY footprint has collision data. */
    Coverage coverage(double x0, double y0, double x1, double y1);

    /** Nothing to collide with; every box is still loading. */
    CollisionWorld NONE =
            new CollisionWorld() {
                @Override
                public void triangles(
                        double x0,
                        double y0,
                        double z0,
                        double x1,
                        double y1,
                        double z1,
                        TerrainGround.TriangleSink sink) {}

                @Override
                public Coverage coverage(double x0, double y0, double x1, double y1) {
                    return Coverage.LOADING;
                }
            };

    /**
     * The retained terrain: rendered cells block, holes are open, and a box is LOADING while part
     * of it lies outside every retained chunk.
     */
    static CollisionWorld terrain(TerrainGround ground) {
        Objects.requireNonNull(ground);
        return new CollisionWorld() {
            @Override
            public void triangles(
                    double x0,
                    double y0,
                    double z0,
                    double x1,
                    double y1,
                    double z1,
                    TerrainGround.TriangleSink sink) {
                ground.triangles(x0, y0, x1, y1, sink);
            }

            @Override
            public Coverage coverage(double x0, double y0, double x1, double y1) {
                return ground.covers(x0, y0, x1, y1) ? Coverage.READY : Coverage.LOADING;
            }
        };
    }
}
