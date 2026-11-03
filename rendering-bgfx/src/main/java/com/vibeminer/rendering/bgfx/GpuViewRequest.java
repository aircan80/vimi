package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.util.Set;

/** Small immutable latest-only control record; geometry never travels on the camera pipe. */
record GpuViewRequest(
        long revision, int width, int height, double yaw, double pitch, double zoom, int flags)
        implements GpuFrameRequest {
    GpuViewRequest {
        if (revision < 1
                || width < 1
                || height < 1
                || width > 1280
                || height > 960
                || !Double.isFinite(yaw)
                || !Double.isFinite(pitch)
                || !Double.isFinite(zoom)
                || pitch < -1.1
                || pitch > 1.1
                || zoom < .4
                || zoom > 3
                || (flags & ~15) != 0) {
            throw new IllegalArgumentException("Invalid inline GPU view bounds");
        }
    }

    static GpuViewRequest parse(String line) {
        if (line.length() > 256) {
            throw new IllegalArgumentException("GPU control line too long");
        }
        var fields = line.split(" ");
        if (fields.length != 8 || !fields[0].equals("VIEW")) {
            throw new IllegalArgumentException("Invalid GPU control record");
        }
        return new GpuViewRequest(
                Long.parseLong(fields[1]),
                Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]),
                Double.parseDouble(fields[4]),
                Double.parseDouble(fields[5]),
                Double.parseDouble(fields[6]),
                Integer.parseInt(fields[7]));
    }

    StaticMeshRenderer.View camera(Set<Integer> qualified) {
        Set<Integer> visible =
                (flags & 4) != 0
                        ? Set.of()
                        : qualified.isEmpty() ? Set.of(Integer.MIN_VALUE) : qualified;
        return new StaticMeshRenderer.View(yaw, pitch, zoom, (flags & 1) != 0, visible);
    }

    boolean qualifiedMaterialsOnly() {
        return (flags & 8) != 0 && (flags & 4) == 0;
    }

    boolean textures() {
        return (flags & 2) != 0;
    }
}
