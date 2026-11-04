package com.vibeminer.rendering.world;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/** Structural preview data only. No implementation or descriptor authenticates a game source. */
public final class BasicWmoData {
    public static final int MAX_VERTICES = 512, MAX_INDICES = 1536, MAX_IMAGES = 6;
    public static final int MAX_PIXELS = 393216;
    public static final double NORMAL_ZERO_EPSILON = 1e-12;

    private BasicWmoData() {}

    /** Implementations pin immutable read-only primitive views through synchronous consumption. */
    public interface View {
        Metadata metadata();

        FloatBuffer positions();

        FloatBuffer normals();

        FloatBuffer uv();

        IntBuffer indices();

        List<Batch> batches();

        List<Source> sources();

        int materialCount();

        long materialWord(int material, int word);

        int imageCount();

        Image image(int index);
    }

    public record Batch(int firstIndex, int indexCount, int material) {}

    /** Descriptive identity, not an authenticated receipt or positive source capability. */
    public record Source(
            long id, int role, String key, long length, Long declaredLength, int limit) {}

    /** Pinned borrowed pixels; no independent ownership or source assertion. */
    public record Image(long id, int width, int height, ByteBuffer rgba) {
        public Image {
            rgba = rgba.slice().asReadOnlyBuffer();
        }

        @Override
        public ByteBuffer rgba() {
            return rgba.asReadOnlyBuffer();
        }
    }

    public record Placement(
            float x,
            float y,
            float z,
            float rx,
            float ry,
            float rz,
            int scale,
            int flags,
            int doodadSet,
            int nameSet) {
        public double tx() {
            return 51200.0 / 3.0 - (double) z;
        }

        public double ty() {
            return 51200.0 / 3.0 - (double) x;
        }

        public double tz() {
            return y;
        }

        public double size() {
            return scale / 1024.0;
        }

        public double cosine() {
            return StrictMath.cos(((double) ry) * (StrictMath.PI / 180.0));
        }

        public double sine() {
            return StrictMath.sin(((double) ry) * (StrictMath.PI / 180.0));
        }
    }

    public record Metadata(
            long mapId,
            int tileOrdinal,
            int tileX,
            int tileY,
            int modfOrdinal,
            long uniqueId,
            long owner,
            long generation,
            String scopeDigest,
            String geometryDigest,
            long ambientColor,
            TerrainScene.Origin origin,
            Placement placement) {}

    /** CPU oracle for the named fragment recipe, including cancellation of unit vertex normals. */
    public static double intensity(double x, double y, double z) {
        double squared = x * x + y * y + z * z;
        if (!Double.isFinite(squared) || squared <= NORMAL_ZERO_EPSILON) {
            return 1.0 / 3.0;
        }
        double dot = (-x - y + 2 * z) / (StrictMath.sqrt(squared) * StrictMath.sqrt(6));
        return 1.0 / 3.0 + (2.0 / 3.0) * Math.max(0, dot);
    }

    public static double fog(double eyeDepth) {
        return Math.clamp((eyeDepth - 600) / 1600, 0, .85);
    }

    /** Named fragment rule; terrain retains its separate whole-original-triangle far policy. */
    public static boolean fragmentVisible(double eyeDepth) {
        return Double.isFinite(eyeDepth) && eyeDepth >= .5 && eyeDepth <= 2200;
    }

    public static int shadeByte(int sample, int sky, double intensity, double eyeDepth) {
        double f = fog(eyeDepth);
        return (int) StrictMath.floor(sample * intensity * (1 - f) + sky * f);
    }
}
