package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldStageTimings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Helper-side writer of the opt-in World stage timings: with {@code -Dvimi.world.stageTimings=true}
 * and {@code -Dvimi.world.stageTimings.file=<path>} the helper replaces that file with its current
 * table at most once per interval and once more when it ends, so a measurement survives a killed
 * helper. Without both properties it does nothing.
 */
final class WorldStageTimingsDump {
    static final String FILE_PROPERTY = "vimi.world.stageTimings.file";
    static final long INTERVAL_NANOS = 1_000_000_000L;

    private final Path target;
    private final long interval;
    private long lastWrite;
    private boolean written;

    WorldStageTimingsDump(Path target, long intervalNanos) {
        this.target = target;
        this.interval = intervalNanos;
    }

    /** The dump the system properties ask for; inert when either is absent. */
    static WorldStageTimingsDump configured() {
        String file = System.getProperty(FILE_PROPERTY);
        return new WorldStageTimingsDump(
                WorldStageTimings.enabled() && file != null && !file.isBlank()
                        ? Path.of(file)
                        : null,
                INTERVAL_NANOS);
    }

    boolean active() {
        return target != null;
    }

    /** Writes when the interval has passed since the previous write; never throws. */
    void maybeWrite(long now) {
        if (target == null || (written && now - lastWrite < interval)) {
            return;
        }
        lastWrite = now;
        written = true;
        write();
    }

    /** Writes now; never throws, since measurement must not fail the helper. */
    void write() {
        if (target == null) {
            return;
        }
        try {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.writeString(part, WorldStageTimings.snapshot().format(), StandardCharsets.UTF_8);
            Files.move(
                    part,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics only.
        }
    }
}
