package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.util.Objects;

/** Latest-only free-flight camera; source geometry remains in an immutable scene file. */
record GpuWorldRequest(long revision, int width, int height, TerrainCamera camera)
        implements GpuFrameRequest {
    GpuWorldRequest {
        Objects.requireNonNull(camera);
        if (revision < 1
                || width < 1
                || height < 1
                || width > BgfxTerrainRenderer.MAX_WIDTH
                || height > BgfxTerrainRenderer.MAX_HEIGHT) {
            throw new IllegalArgumentException("Invalid terrain control bounds");
        }
    }

    static GpuWorldRequest parse(String line) {
        if (line.length() > 256) {
            throw new IllegalArgumentException("Terrain control line too long");
        }
        var fields = line.split(" ");
        if (fields.length != 9 || !fields[0].equals("WORLD")) {
            throw new IllegalArgumentException("Invalid terrain control record");
        }
        return new GpuWorldRequest(
                Long.parseLong(fields[1]),
                Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]),
                new TerrainCamera(
                        Double.parseDouble(fields[4]),
                        Double.parseDouble(fields[5]),
                        Double.parseDouble(fields[6]),
                        Double.parseDouble(fields[7]),
                        Double.parseDouble(fields[8])));
    }
}
