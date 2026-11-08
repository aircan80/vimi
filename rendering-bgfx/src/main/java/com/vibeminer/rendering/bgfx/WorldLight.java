package com.vibeminer.rendering.bgfx;

/**
 * The World light (issue #373): one sun direction, diffuse and ambient colour, shared by the passes
 * that light their surfaces. A surface is its albedo times {@code ambient + diffuse * max(0, n .
 * direction)}, in gamma space as the classic client draws. Until #177 feeds the light tables,
 * {@link #FIXED} is the light the object shader has always used: a third ambient and two thirds
 * diffuse from above and behind the camera's default heading.
 *
 * @param direction unit vector toward the light, native terrain axes (x north, y west, z up)
 * @param diffuse diffuse colour, red, green, blue, 0..1
 * @param ambient ambient colour, red, green, blue, 0..1
 */
record WorldLight(float[] direction, float[] diffuse, float[] ambient) {
    static final WorldLight FIXED =
            new WorldLight(
                    new float[] {-0.408248290463863f, -0.408248290463863f, 0.816496580927726f},
                    new float[] {2 / 3f, 2 / 3f, 2 / 3f},
                    new float[] {1 / 3f, 1 / 3f, 1 / 3f});

    WorldLight {
        if (direction.length != 3 || diffuse.length != 3 || ambient.length != 3) {
            throw new IllegalArgumentException("World light");
        }
        double length =
                Math.sqrt(
                        direction[0] * direction[0]
                                + direction[1] * direction[1]
                                + direction[2] * direction[2]);
        if (!(Math.abs(length - 1) < 1e-3)) {
            throw new IllegalArgumentException("World light direction");
        }
        for (float[] colour : new float[][] {diffuse, ambient}) {
            for (float value : colour) {
                if (!(value >= 0 && value <= 4)) {
                    throw new IllegalArgumentException("World light colour");
                }
            }
        }
        direction = direction.clone();
        diffuse = diffuse.clone();
        ambient = ambient.clone();
    }

    @Override
    public float[] direction() {
        return direction.clone();
    }

    @Override
    public float[] diffuse() {
        return diffuse.clone();
    }

    @Override
    public float[] ambient() {
        return ambient.clone();
    }

    /**
     * The light uniform (u_worldLight, three vec4): direction with w 1 when lighting is on (0 draws
     * every surface at its albedo), diffuse, ambient.
     */
    float[] uniform(boolean lit) {
        return new float[] {
            direction[0], direction[1], direction[2], lit ? 1 : 0,
            diffuse[0], diffuse[1], diffuse[2], 0,
            ambient[0], ambient[1], ambient[2], 0
        };
    }

    /**
     * This light with a sky's recorded light mixed in by its weight (#177 step 2c): LightData's
     * direct colour becomes the diffuse and its ambient colour the ambient (0..255 to 0..1). The
     * direction stays: LightData records colours, not where the light comes from. Weight 0 (no
     * recorded light, or no sky) keeps this light.
     */
    WorldLight withRecorded(WorldStreamTransport.Sky sky) {
        if (sky == null || sky.lightWeight() <= 0) {
            return this;
        }
        double w = sky.lightWeight();
        float[] direct = sky.direct(), recordedAmbient = sky.ambient();
        float[] d = new float[3], a = new float[3];
        for (int i = 0; i < 3; i++) {
            d[i] = (float) (diffuse[i] + (direct[i] / 255 - diffuse[i]) * w);
            a[i] = (float) (ambient[i] + (recordedAmbient[i] / 255 - ambient[i]) * w);
        }
        return new WorldLight(direction, d, a);
    }

    /** The light factor of one channel for a unit normal, as the shader computes it. */
    float factor(int channel, double nx, double ny, double nz) {
        double lambert = Math.max(0, nx * direction[0] + ny * direction[1] + nz * direction[2]);
        return (float) (ambient[channel] + diffuse[channel] * lambert);
    }
}
