package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;

/**
 * Distance fog and the sky gradient that meets it, as the World shaders apply them: one colour for
 * fogged terrain and objects and for the sky at and below the horizon, so the fog never shows an
 * edge against the sky. Colours are in 0..255 gamma units, as the shaders quantize them.
 *
 * <p>{@link #STREAM} reaches full fog at the World view cut ({@link
 * TerrainCamera#WHOLE_TRIANGLE_FAR}), so geometry fades out completely before it is cut (issue
 * #357). Until #177 feeds colours and distances from the light tables, its colours are the
 * explorer's previous constants. {@link #LEGACY} is the previous fog, kept bit-identical for the
 * cold WMO pass that shares the object shaders.
 *
 * @param fog fog and horizon colour, red, green, blue
 * @param top sky colour at and above {@code topElevation}
 * @param start eye depth where fog begins
 * @param end eye depth where fog reaches {@code maximum}
 * @param maximum strongest fog, 0..1
 * @param topElevation elevation in radians above the horizon where the sky is {@code top}
 */
record WorldFog(
        float[] fog, float[] top, float start, float end, float maximum, float topElevation) {
    static final float[] FOG_COLOUR = {153, 184, 196};
    static final float[] SKY_TOP = {91, 141, 177};

    static final WorldFog STREAM =
            new WorldFog(
                    FOG_COLOUR, SKY_TOP, 600, (float) TerrainCamera.WHOLE_TRIANGLE_FAR, 1, 0.75f);

    /** The fog before #357: 0.85 at 2200 yd. The cold pass draws no horizon sky. */
    static final WorldFog LEGACY = new WorldFog(FOG_COLOUR, SKY_TOP, 600, 2200, 0.85f, 0.75f);

    WorldFog {
        if (fog.length != 3
                || top.length != 3
                || !(end > start)
                || !(maximum >= 0 && maximum <= 1)
                || !(topElevation > 0)) {
            throw new IllegalArgumentException("World fog");
        }
        fog = fog.clone();
        top = top.clone();
    }

    /** The shaders' fog factor at an eye depth. */
    float factor(double depth) {
        return (float) Math.clamp((depth - start) / (end - start), 0, maximum);
    }

    /** The fog colour uniform (u_worldFog, u_wmoFog) and the sky horizon colour (u_skyHorizon). */
    float[] colourUniform() {
        return new float[] {fog[0], fog[1], fog[2], 0};
    }

    /** The fog range uniform (u_worldFogRange, u_wmoFogRange). */
    float[] rangeUniform() {
        return new float[] {start, end, maximum, 0};
    }

    /** The sky top colour uniform (u_skyTop). */
    float[] topUniform() {
        return new float[] {top[0], top[1], top[2], 0};
    }

    /**
     * The sky view uniform (u_skyView): camera pitch, the explorer's focal length in pixels (0.8
     * frame widths, as in {@link TerrainCamera#screenY}) and the top colour's elevation.
     */
    float[] viewUniform(double pitch, int width) {
        return new float[] {(float) pitch, 0.8f * width, topElevation, 0};
    }

    /** The shaders' sky colour for one frame row counted from the top, before quantizing. */
    float[] sky(double pitch, int width, int height, int row) {
        double elevation = pitch + Math.atan((height * 0.5 - (row + 0.5)) / (0.8 * width));
        double t = Math.clamp(elevation / topElevation, 0, 1);
        return new float[] {
            (float) (fog[0] + (top[0] - fog[0]) * t),
            (float) (fog[1] + (top[1] - fog[1]) * t),
            (float) (fog[2] + (top[2] - fog[2]) * t)
        };
    }
}
