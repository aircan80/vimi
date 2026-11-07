package com.vimi.gui;

import com.vibeminer.rendering.world.TerrainRenderer;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The camera of the streamed frame on screen. Stream camera requests are recorded by revision; a
 * presented frame's revision selects the camera it was rendered with, keyed to the image it was
 * written into. Overlays and picking use that camera only while that same image is shown, so a
 * CPU frame shown after a mode switch never gets a stale stream camera, while a frozen streamed
 * frame keeps its own camera across a stream restart (Retry, reactivation). An unknown revision
 * keeps the previous presented camera and reports the first miss. FX thread only.
 */
final class WorldPresentedCamera {
    private final WorldCameraRing ring = new WorldCameraRing();
    private final Consumer<String> miss;
    private TerrainRenderer.Camera camera;
    private Object image;
    private boolean missReported;

    /** {@code miss} hears the first presented revision that was never recorded. */
    WorldPresentedCamera(Consumer<String> miss) {
        this.miss = Objects.requireNonNull(miss);
    }

    /** A stream camera request was sent with {@code revision}. */
    void sent(long revision, TerrainRenderer.Camera sent) {
        ring.record(revision, sent);
    }

    /** A streamed frame for {@code revision} is now shown in {@code shown}. */
    void presented(long revision, Object shown) {
        var known = ring.camera(revision);
        if (known.isPresent()) camera = known.get();
        else if (!missReported) {
            missReported = true;
            miss.accept("presented camera revision " + revision + " was not recorded; overlays keep"
                    + " the previous frame's camera");
        }
        if (camera != null) image = shown;
    }

    /** A non-streamed frame was written (possibly into the same image object): forget it. */
    void shownOther() {
        image = null;
    }

    /** A new stream restarts revisions from 1; the frame on screen keeps its camera. */
    void restart() {
        ring.clear();
    }

    /** The camera the overlays should project with while {@code shown} is on screen. */
    TerrainRenderer.Camera frameCamera(TerrainRenderer.Camera live, Object shown) {
        return camera != null && shown != null && shown == image ? camera : live;
    }
}
