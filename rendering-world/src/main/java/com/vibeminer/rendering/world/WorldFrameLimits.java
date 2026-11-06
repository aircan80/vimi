package com.vibeminer.rendering.world;

/**
 * The one frame-size cap of the World explorer stream: viewport, protocol, helper render target,
 * frame mapping and presentation all check against these limits.
 *
 * <p>Memory at the cap (2560x1600, 16.4 MB per BGRA frame); everything below follows the actual
 * frame size, so a smaller window uses proportionally less:
 *
 * <ul>
 *   <li>Helper, about 147 MB: colour and depth targets (2 x 16.4 MB on the GPU, 4x each with MSAA
 *       x4), three readback textures (49 MB) and their CPU buffers (49 MB), and one crop buffer
 *       (16.4 MB).
 *   <li>Frame mapping: 49 MB logical for three slots, created sparse, so disk and memory hold only
 *       the bytes of frames actually written.
 *   <li>GUI: up to three pooled pixel arrays (49 MB heap) and two presentation images (33 MB), plus
 *       the JavaFX texture.
 * </ul>
 */
public final class WorldFrameLimits {
    /** 2560x1600: a full 1440p (or 16:10 1600p) window at native resolution. */
    public static final int MAX_WIDTH = 2560, MAX_HEIGHT = 1600;

    /** Lowest render scale a user setting may ask for (slow GPUs). */
    public static final double MIN_RENDER_SCALE = 0.5;

    /** Frame pixel size. */
    public record Size(int width, int height) {
        public Size {
            if (!fits(width, height)) {
                throw new IllegalArgumentException("World frame size " + width + "x" + height);
            }
        }
    }

    private WorldFrameLimits() {}

    private static double scale(double value) {
        return Double.isFinite(value) && value > 0 ? value : 1;
    }

    public static boolean fits(int width, int height) {
        return width >= 1 && width <= MAX_WIDTH && height >= 1 && height <= MAX_HEIGHT;
    }

    /**
     * The frame for a viewport of {@code width x height} layout units shown at {@code scaleX} and
     * {@code scaleY} device pixels per unit (HiDPI; each axis its own), rendered at {@code
     * renderScale} (1 = native, clamped to [{@link #MIN_RENDER_SCALE}, 1]). Keeps the aspect ratio
     * when the cap applies.
     */
    public static Size frame(
            double width, double height, double scaleX, double scaleY, double renderScale) {
        double render =
                Math.clamp(Double.isFinite(renderScale) ? renderScale : 1, MIN_RENDER_SCALE, 1);
        double w = Math.max(1, Double.isFinite(width) ? width : 1) * scale(scaleX) * render;
        double h = Math.max(1, Double.isFinite(height) ? height : 1) * scale(scaleY) * render;
        double fit = Math.min(1, Math.min(MAX_WIDTH / w, MAX_HEIGHT / h));
        return new Size(
                Math.clamp(Math.round(w * fit), 1, MAX_WIDTH),
                Math.clamp(Math.round(h * fit), 1, MAX_HEIGHT));
    }
}
