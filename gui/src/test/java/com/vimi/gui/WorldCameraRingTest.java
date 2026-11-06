package com.vimi.gui;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.util.List;

/** Camera ring by stream camera revision, and overlay projection against it; no display. */
public final class WorldCameraRingTest {
    private WorldCameraRingTest() {}

    public static void main(String[] args) {
        exactRevisionsOnly();
        overwrittenAndClearedRevisionsAreUnknown();
        overlaysFollowThePresentedFrameNotTheLiveCamera();
        presentedCameraFollowsTheImageOnScreen();
        System.out.println(
                "World camera ring: exact revisions, overwrite, clear, presented-frame overlay"
                        + " projection, image keying, CPU fallback, restart and unknown revisions passed");
    }

    private static TerrainRenderer.Camera turned(double yaw) {
        return new TerrainRenderer.Camera(0, 0, 0, yaw, 0);
    }

    private static void exactRevisionsOnly() {
        var ring = new WorldCameraRing();
        require(ring.camera(1).isEmpty() && ring.camera(0).isEmpty() && ring.camera(-3).isEmpty(),
                "Nothing recorded, nothing known");
        ring.record(1, turned(.1));
        ring.record(2, turned(.2));
        require(ring.camera(1).orElseThrow().equals(turned(.1)), "Revision 1");
        require(ring.camera(2).orElseThrow().equals(turned(.2)), "Revision 2");
        require(ring.camera(3).isEmpty(), "A revision never sent is unknown");
        ring.record(2, turned(.25));
        require(ring.camera(2).orElseThrow().equals(turned(.25)), "A resent revision replaces its camera");
        try {
            ring.record(0, turned(0));
            throw new AssertionError("Revision 0 must be refused");
        } catch (IllegalArgumentException expected) {
            // Revisions start at 1.
        }
    }

    private static void overwrittenAndClearedRevisionsAreUnknown() {
        var ring = new WorldCameraRing();
        int n = WorldCameraRing.CAPACITY;
        for (long r = 1; r <= n + 5; r++) ring.record(r, turned(r * .001));
        for (long r = 1; r <= 5; r++)
            require(ring.camera(r).isEmpty(), "Revision " + r + " was overwritten by " + (r + n));
        for (long r = 6; r <= n + 5; r++)
            require(ring.camera(r).orElseThrow().equals(turned(r * .001)), "Recent revision " + r);
        ring.clear();
        for (long r = 1; r <= n + 5; r++) require(ring.camera(r).isEmpty(), "Cleared " + r);
    }

    /**
     * While turning, the live camera runs a few revisions ahead of the frame on screen. A marker
     * projected with the presented frame's camera lands where that frame drew its ground point;
     * the live camera would put it elsewhere.
     */
    private static void overlaysFollowThePresentedFrameNotTheLiveCamera() {
        var ring = new WorldCameraRing();
        for (long r = 1; r <= 8; r++) ring.record(r, turned(r * .02));
        var live = ring.camera(8).orElseThrow();
        var presented = ring.camera(5).orElseThrow();
        var index = WorldSpawnOverlayTest.index(List.of(WorldSpawnOverlayTest.spawn(1, 1, 100, -20, 0.0)));
        var spawns = index.near(0, 0, 0, 300, 256).placed();
        var drawn = TerrainCamera.from(presented).eye(100, -20, 0);
        double frameX = TerrainCamera.screenX(drawn, 800, 500);
        var withPresented = WorldSpawnOverlay.project(spawns, presented, 800, 500, 800, 500);
        var withLive = WorldSpawnOverlay.project(spawns, live, 800, 500, 800, 500);
        require(withPresented.size() == 1 && Math.abs(withPresented.getFirst().x() - frameX) < 1e-9,
                "The presented frame's camera puts the marker on its drawn point");
        require(withLive.size() == 1, "The marker is in view for the live camera too");
        double drift = Math.abs(withLive.getFirst().x() - frameX);
        require(drift > 20, "The live camera would draw it " + drift + " px off the terrain on screen");
    }

    private static void presentedCameraFollowsTheImageOnScreen() {
        var misses = new java.util.ArrayList<String>();
        var presented = new WorldPresentedCamera(misses::add);
        var live = turned(.9);
        var streamed = new Object();
        require(presented.frameCamera(live, streamed) == live, "Nothing presented: live camera");
        presented.sent(1, turned(.1));
        presented.sent(2, turned(.2));
        presented.presented(1, streamed);
        require(presented.frameCamera(live, streamed).equals(turned(.1)),
                "The revision sent is the revision looked up for the shown image");
        // GPU -> CPU: a CPU frame is a different image, or the same object rewritten.
        var cpu = new Object();
        require(presented.frameCamera(live, cpu) == live, "A CPU frame uses the live camera");
        presented.shownOther();
        require(presented.frameCamera(live, streamed) == live,
                "A non-streamed frame written into the streamed image forgets its camera");
        // CPU -> GPU again: the next streamed frame brings its own camera.
        presented.presented(2, streamed);
        require(presented.frameCamera(live, streamed).equals(turned(.2)), "Streaming again uses its camera");
        // Retry / reactivation: the stream restarts, the frozen frame keeps its camera.
        presented.restart();
        require(presented.frameCamera(live, streamed).equals(turned(.2)),
                "A restart never snaps overlays on the frozen frame to the live camera");
        presented.presented(1, streamed);
        require(presented.frameCamera(live, streamed).equals(turned(.2)) && misses.size() == 1,
                "An unrecorded revision keeps the previous camera and is reported: " + misses);
        presented.presented(7, streamed);
        require(misses.size() == 1, "Only the first miss is reported");
        presented.sent(1, turned(.3));
        presented.presented(1, streamed);
        require(presented.frameCamera(live, streamed).equals(turned(.3)), "Recorded revisions resume");
        require(presented.frameCamera(live, null) == live, "No image: live camera");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
