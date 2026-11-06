package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;

/**
 * Distance fog and the sky it fades into, as the World shaders apply them. The sky is a gradient by
 * view elevation: {@code horizon} at and below the horizon, rising to {@code top} at {@code
 * topElevation}. Fogged terrain and objects fade toward the sky colour at their own elevation as
 * seen from the camera, so at full fog every surface equals the sky behind it, at any elevation,
 * and the cut never shows. Colours are in 0..255 gamma units, as the shaders quantize them.
 *
 * <p>{@link #STREAM} reaches full fog at the World view cut ({@link
 * TerrainCamera#WHOLE_TRIANGLE_FAR}) (issue #357). Until #177 feeds colours and distances from the
 * light tables, its colours are the explorer's previous constants; the renderer takes a new value
 * per frame. {@link #LEGACY} is the previous flat fog, kept within one quantization level for the
 * cold WMO pass that shares the object shaders.
 *
 * @param horizon sky and fog colour at and below the horizon, red, green, blue
 * @param top sky colour at and above {@code topElevation}
 * @param start eye depth where fog begins
 * @param end eye depth where fog reaches {@code maximum}, at most {@code cut}
 * @param maximum strongest fog, 0..1
 * @param cut eye depth past which nothing is drawn
 * @param topElevation elevation in radians above the horizon where the sky is {@code top}
 */
record WorldFog(
        float[] horizon,
        float[] top,
        float start,
        float end,
        float maximum,
        float cut,
        float topElevation) {
    static final float[] FOG_COLOUR = {153, 184, 196};
    static final float[] SKY_TOP = {91, 141, 177};

    static final WorldFog STREAM = stream(FOG_COLOUR, SKY_TOP, 600, 2200, 0.75f);

    /** The fog before #357: flat, 0.85 at 2200 yd. The cold pass draws no horizon sky. */
    static final WorldFog LEGACY =
            new WorldFog(FOG_COLOUR, FOG_COLOUR, 600, 2200, 0.85f, 2200, 0.75f);

    WorldFog {
        if (horizon.length != 3
                || top.length != 3
                || !(end > start)
                || !(end <= cut)
                || !(maximum >= 0 && maximum <= 1)
                || !(topElevation > 0)) {
            throw new IllegalArgumentException("World fog");
        }
        horizon = horizon.clone();
        top = top.clone();
    }

    /**
     * Streaming fog: full (1.0) by {@code end}, clamped to the World cut, so geometry always fades
     * out completely before it is cut.
     */
    static WorldFog stream(
            float[] horizon, float[] top, float start, float end, float topElevation) {
        float cut = (float) TerrainCamera.WHOLE_TRIANGLE_FAR;
        return new WorldFog(horizon, top, start, Math.min(end, cut), 1, cut, topElevation);
    }

    /**
     * This fog with the WDL horizon drawn (#372, option (a)): fog reaches its maximum at {@code
     * horizonEnd} instead of the streamed-terrain cut, and the cut moves out to it as well, so
     * terrain, objects and the horizon all fade into the sky together and nothing is sliced. The
     * frame's CPU culling of streamed chunks and objects uses this cut too, so they are drawn out
     * to {@code horizonEnd}; past the streamed neighbourhood there is nothing of theirs to draw.
     *
     * <p>Callers must not rely on {@code end() == cut()} afterwards: #177's recorded fog may later
     * keep a shorter end with the cut at the horizon.
     */
    WorldFog withHorizon(float horizonEnd) {
        if (!(horizonEnd >= end)) {
            throw new IllegalArgumentException("Horizon fog must reach at least the current end");
        }
        return new WorldFog(horizon, top, start, horizonEnd, maximum, horizonEnd, topElevation);
    }

    @Override
    public float[] horizon() {
        return horizon.clone();
    }

    @Override
    public float[] top() {
        return top.clone();
    }

    /** The shaders' fog factor at an eye depth. */
    float factor(double depth) {
        return (float) Math.clamp((depth - start) / (end - start), 0, maximum);
    }

    /** The sky colour at an elevation in radians, before quantizing. */
    float[] sky(double elevation) {
        double t = Math.clamp(elevation / topElevation, 0, 1);
        return new float[] {
            (float) (horizon[0] + (top[0] - horizon[0]) * t),
            (float) (horizon[1] + (top[1] - horizon[1]) * t),
            (float) (horizon[2] + (top[2] - horizon[2]) * t)
        };
    }

    /** Elevation of a direction (native x, y, z, z up) above the horizon. */
    static double elevation(double dx, double dy, double dz) {
        return Math.atan2(dz, Math.max(Math.hypot(dx, dy), 1e-6));
    }

    /**
     * The shaders' sky colour of frame pixel (x, row from the top), before quantizing: the
     * elevation of its view ray through the camera pitch and the explorer's focal length.
     */
    float[] skyPixel(double pitch, double focal, int width, int height, int x, int row) {
        double right = (x + 0.5 - width * 0.5) / focal, up = (height * 0.5 - (row + 0.5)) / focal;
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double forward = cp - sp * up;
        return sky(Math.atan2(cp * up + sp, Math.hypot(right, forward)));
    }

    /** The fog uniform (u_worldFog, u_wmoFog): horizon colour and the top colour's elevation. */
    float[] fogUniform() {
        return new float[] {horizon[0], horizon[1], horizon[2], topElevation};
    }

    /** The sky top uniform (u_worldSkyTop, u_wmoSkyTop, u_skyTop). */
    float[] topUniform() {
        return new float[] {top[0], top[1], top[2], 0};
    }

    /** The sky horizon uniform (u_skyHorizon): the same colour the fog fades into there. */
    float[] horizonUniform() {
        return new float[] {horizon[0], horizon[1], horizon[2], 0};
    }

    /** The fog range uniform (u_worldFogRange, u_wmoFogRange): start, end, maximum, cut. */
    float[] rangeUniform() {
        return new float[] {start, end, maximum, cut};
    }

    /**
     * The sky view uniform (u_skyView): camera pitch, the explorer's focal length in pixels ({@link
     * TerrainCamera#focalPixels}) and the top colour's elevation.
     */
    float[] viewUniform(double pitch, int width) {
        return new float[] {
            (float) pitch, (float) TerrainCamera.focalPixels(width), topElevation, 0
        };
    }
}
