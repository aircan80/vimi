package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.WmoPortalTraversal;
import java.util.BitSet;

/**
 * Per-frame portal culling of instance interiors (#383 step 3b): a placed root with a portal set
 * walks its portals from the camera, moved into the root's own WMO-local axes, and interior meshes
 * of groups the walk does not reach are skipped. Exterior groups and roots without a set draw as
 * before. Every array is reused; nothing is allocated per frame once the scratch has grown. Used by
 * the render thread only.
 */
final class WorldStreamPortalCulling {
    /** World planes of the frame's view: four sides and the far cut, never the near plane. */
    static final int VIEW_PLANES = 5;

    private final double[] world = new double[VIEW_PLANES * 4];
    private final double[] local = new double[VIEW_PLANES * 4];
    private final double[] camera = new double[3];
    private final WmoPortalTraversal.Scratch scratch = new WmoPortalTraversal.Scratch();
    private double cameraX, cameraY, cameraZ;
    private int planes;
    private boolean ready;

    /** Takes this frame's camera and view planes; without a view nothing is culled. */
    void frame(WorldStreamObjectCulling.View view) {
        ready = view != null;
        if (ready) {
            planes = view.portalPlanes(world);
            cameraX = view.x();
            cameraY = view.y();
            cameraZ = view.z();
        }
    }

    /**
     * Walks placement {@code p}'s portals into {@code into}; false (nothing culled) without a view
     * this frame.
     */
    boolean walk(WmoPortalTraversal portals, double[] placements, int p, BitSet into) {
        if (!ready) {
            return false;
        }
        toLocal(placements, p, cameraX, cameraY, cameraZ, world, planes, camera, local);
        portals.visible(camera[0], camera[1], camera[2], local, planes, into, scratch);
        return true;
    }

    /**
     * Placement {@code p}'s view of a world camera and {@code count} world planes ({@code a, b, c,
     * d} each, inside where the value is at least zero) in its WMO-local axes. A placement maps a
     * local point {@code q} to {@code t + s R q} (row-major R, uniform scale s), so the camera
     * moves to {@code Rᵀ(c - t) / s} and a plane {@code (n, d)} to {@code (s Rᵀ n, n·t + d)},
     * normalized.
     */
    static void toLocal(
            double[] placements,
            int p,
            double cx,
            double cy,
            double cz,
            double[] worldPlanes,
            int count,
            double[] cameraOut,
            double[] planesOut) {
        int at = p * WorldStreamObjectsFile.PLACEMENT_DOUBLES;
        double tx = placements[at], ty = placements[at + 1], tz = placements[at + 2];
        double s = placements[at + 3];
        int r = at + 4;
        double dx = cx - tx, dy = cy - ty, dz = cz - tz;
        for (int j = 0; j < 3; j++) {
            cameraOut[j] =
                    (placements[r + j] * dx
                                    + placements[r + 3 + j] * dy
                                    + placements[r + 6 + j] * dz)
                            / s;
        }
        for (int i = 0; i < count; i++) {
            int w = i * 4;
            double nx = worldPlanes[w], ny = worldPlanes[w + 1], nz = worldPlanes[w + 2];
            double a = s * (placements[r] * nx + placements[r + 3] * ny + placements[r + 6] * nz);
            double b =
                    s * (placements[r + 1] * nx + placements[r + 4] * ny + placements[r + 7] * nz);
            double c =
                    s * (placements[r + 2] * nx + placements[r + 5] * ny + placements[r + 8] * nz);
            double d = nx * tx + ny * ty + nz * tz + worldPlanes[w + 3];
            double length = Math.sqrt(a * a + b * b + c * c);
            if (!(length > 0)) {
                length = 1;
            }
            planesOut[w] = a / length;
            planesOut[w + 1] = b / length;
            planesOut[w + 2] = c / length;
            planesOut[w + 3] = d / length;
        }
    }
}
