package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.nio.FloatBuffer;

/** Separate global bounds; shares corrected north/west/up free-flight math, without terrain far. */
record GlobalWmoCamera(double x, double y, double z, double yaw, double pitch) {
    private static final TerrainScene.Origin ORIGIN = new TerrainScene.Origin(0, 0, 0);

    GlobalWmoCamera {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || Math.abs(x) > 8192
                || Math.abs(y) > 8192
                || Math.abs(z) > 8192
                || !Double.isFinite(yaw)
                || Math.abs(yaw) > TerrainScene.MAX_COORDINATE
                || !Double.isFinite(pitch)
                || Math.abs(pitch) > 1.5) {
            throw new IllegalArgumentException("Invalid global camera");
        }
    }

    GlobalWmoCamera move(double forward, double right, double up) {
        var moved = terrain().move(forward, right, up);
        return new GlobalWmoCamera(moved.x(), moved.y(), moved.z(), moved.yaw(), moved.pitch());
    }

    GlobalWmoCamera look(double left, double up) {
        var moved = terrain().look(left, up);
        return new GlobalWmoCamera(moved.x(), moved.y(), moved.z(), moved.yaw(), moved.pitch());
    }

    FloatBuffer modelToEye() {
        return terrain().modelToEye(ORIGIN);
    }

    static FloatBuffer projection(int width, int height) {
        if (width < 1
                || height < 1
                || width > GlobalWmoNativeScene.WIDTH
                || height > GlobalWmoNativeScene.HEIGHT) {
            throw new IllegalArgumentException("Invalid global frame dimensions");
        }
        return TerrainCamera.reversedProjection(width, height);
    }

    private TerrainCamera terrain() {
        return new TerrainCamera(x, y, z, yaw, pitch);
    }

    /**
     * Bounded inspection framing only: never a client entrance, spawn, floor or navigation proof.
     */
    static GlobalWmoCamera initial(
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float[] values = {minX, minY, minZ, maxX, maxY, maxZ};
        for (float value : values) {
            if (!Float.isFinite(value) || Math.abs(value) > 4096) {
                throw new IllegalArgumentException("Invalid global bounds");
            }
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Reversed global bounds");
        }
        double distance =
                Math.max(4, Math.max((maxY - (double) minY) * .8, (maxZ - (double) minZ) * 1.28));
        return new GlobalWmoCamera(
                Math.max(-8192, minX - distance),
                (minY + (double) maxY) * .5,
                (minZ + (double) maxZ) * .5,
                0,
                0);
    }
}
