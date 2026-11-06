package com.vibeminer.rendering.world;

/**
 * The one frame-size cap of the World explorer stream: viewport, protocol, helper render target,
 * frame mapping and presentation all check against these limits.
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

    public static boolean fits(int width, int height) {
        return width >= 1 && width <= MAX_WIDTH && height >= 1 && height <= MAX_HEIGHT;
    }

    /**
     * The frame for a viewport of {@code width x height} layout units shown at {@code outputScale}
     * device pixels per unit (HiDPI), rendered at {@code renderScale} (1 = native, clamped to
     * [{@link #MIN_RENDER_SCALE}, 1]). Keeps the aspect ratio when the cap applies.
     */
    public static Size frame(double width, double height, double outputScale, double renderScale) {
        double scale =
                (Double.isFinite(outputScale) && outputScale > 0 ? outputScale : 1)
                        * Math.clamp(
                                Double.isFinite(renderScale) ? renderScale : 1,
                                MIN_RENDER_SCALE,
                                1);
        double w = Math.max(1, Double.isFinite(width) ? width : 1) * scale;
        double h = Math.max(1, Double.isFinite(height) ? height : 1) * scale;
        double fit = Math.min(1, Math.min(MAX_WIDTH / w, MAX_HEIGHT / h));
        return new Size(
                Math.clamp(Math.round(w * fit), 1, MAX_WIDTH),
                Math.clamp(Math.round(h * fit), 1, MAX_HEIGHT));
    }
}
