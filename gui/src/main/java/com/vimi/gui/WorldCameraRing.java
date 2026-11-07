package com.vimi.gui;

import com.vibeminer.rendering.world.TerrainRenderer;
import java.util.Objects;
import java.util.Optional;

/**
 * The cameras sent with the most recent stream camera requests, keyed by camera revision. A
 * streamed frame names the revision it was rendered for, so overlays and picking can use the
 * camera of the frame on screen instead of the live camera, which runs ahead of it while moving.
 * Fixed capacity: the oldest revision is overwritten. FX thread only.
 */
final class WorldCameraRing {
    /** Comfortably more than the camera requests in flight at 60 per second. */
    static final int CAPACITY = 64;

    private final long[] revisions = new long[CAPACITY];
    private final TerrainRenderer.Camera[] cameras = new TerrainRenderer.Camera[CAPACITY];

    /** Records the camera sent with {@code revision}; revisions start at 1. */
    void record(long revision, TerrainRenderer.Camera camera) {
        if (revision <= 0) throw new IllegalArgumentException("Camera revisions start at 1");
        int slot = (int) (revision % CAPACITY);
        revisions[slot] = revision;
        cameras[slot] = Objects.requireNonNull(camera);
    }

    /** The camera sent with exactly {@code revision}, unless it was overwritten or cleared. */
    Optional<TerrainRenderer.Camera> camera(long revision) {
        if (revision <= 0) return Optional.empty();
        int slot = (int) (revision % CAPACITY);
        return revisions[slot] == revision ? Optional.of(cameras[slot]) : Optional.empty();
    }

    /** Forgets every camera, e.g. when a new stream restarts revisions from 1. */
    void clear() {
        java.util.Arrays.fill(revisions, 0);
        java.util.Arrays.fill(cameras, null);
    }
}
