package com.vimi.gui;

import com.vibeminer.rendering.world.ExplorerMotion;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

/** Explorer input mapping and pulse cadence against a recording host; no JavaFX toolkit. */
public final class WorldExplorerInputTest {
    private static final TerrainRenderer.Camera START =
            new TerrainRenderer.Camera(100, 200, 50, 0, 0);

    private WorldExplorerInputTest() {}

    public static void main(String[] args) {
        keysMapToClampedAxes();
        walkToggleIgnoresKeyRepeat();
        clearDropsHeldKeys();
        mouseDragLooksTowardTheDragSide();
        onlyAStillPrimaryClickInspects();
        shiftWheelAdjustsBoundedSpeed();
        wheelDollysAlongTheViewAndIgnoresBadInput();
        pulseIntegratesHeldKeysAtTheEstablishedRates();
        pulseRequestsFramesAtCadenceAndNearbyLoadsThrottled();
        idleRestartsIntegrationAtZeroSeconds();
        walkAtRestStillReportsTheGround();
        smoothReleaseCoastsThenFramesTheRestingStep();
        walkWheelMovesAlongTheHeadingOnly();
        System.out.println(
                "World explorer input: key axes, walk toggle, look drag, inspect click, wheel,"
                        + " speed, pulse cadence and idle passed");
    }

    /** Records hook calls; the camera is the only mutable view state the input touches. */
    static final class Host implements WorldExplorerInput.Host {
        TerrainRenderer.Camera camera = START;
        boolean wheelReady = true, fast = true;
        final List<String> calls = new ArrayList<>();
        int toggles;

        @Override public TerrainRenderer.Camera camera() { return camera; }
        @Override public void camera(TerrainRenderer.Camera next) { camera = next; }
        @Override public TerrainGround ground() { return TerrainGround.empty(); }
        @Override public boolean wheelReady() { return wheelReady; }
        @Override public boolean fastCadence() { return fast; }
        @Override public void groundStatus(ExplorerMotion.Ground ground) { calls.add("ground:" + ground); }
        @Override public void toggleWalk() { toggles++; }
        @Override public void requestFrame() { calls.add("frame"); }
        @Override public void abandonUnusableCandidate() { calls.add("abandon"); }
        @Override public void loadNext() { calls.add("nearby"); }

        long count(String call) {
            return calls.stream().filter(call::equals).count();
        }
    }

    private static WorldExplorerInput exact(Host host) {
        var input = new WorldExplorerInput(host);
        input.motion(false, false);
        return input;
    }

    private static void keysMapToClampedAxes() {
        var input = exact(new Host());
        require(input.axes().equals(ExplorerMotion.Input.NONE), "No keys, no axes");
        check(input, List.of(KeyCode.W), 1, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.S), -1, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.W, KeyCode.S), 0, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.D), 0, 1, 0, 0, 0, false);
        check(input, List.of(KeyCode.A), 0, -1, 0, 0, 0, false);
        check(input, List.of(KeyCode.SPACE, KeyCode.E), 0, 0, 1, 0, 0, false);
        check(input, List.of(KeyCode.Q, KeyCode.X), 0, 0, -1, 0, 0, false);
        check(input, List.of(KeyCode.SPACE, KeyCode.X), 0, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.LEFT), 0, 0, 0, 1, 0, false);
        check(input, List.of(KeyCode.RIGHT), 0, 0, 0, -1, 0, false);
        check(input, List.of(KeyCode.UP), 0, 0, 0, 0, 1, false);
        check(input, List.of(KeyCode.DOWN), 0, 0, 0, 0, -1, false);
        check(input, List.of(KeyCode.SHIFT, KeyCode.W), 1, 0, 0, 0, 0, true);
        check(input, List.of(KeyCode.Z), 0, 0, 0, 0, 0, false);
    }

    private static void check(WorldExplorerInput input, List<KeyCode> held,
            int forward, int strafe, int vertical, int turn, int tilt, boolean sprint) {
        held.forEach(input::keyPressed);
        var axes = input.axes();
        require(axes.equals(new ExplorerMotion.Input(forward, strafe, vertical, turn, tilt, sprint)),
                "Axes for " + held + ": " + axes);
        held.forEach(input::keyReleased);
        require(!input.keysHeld(), "Released keys are no longer held: " + held);
    }

    private static void walkToggleIgnoresKeyRepeat() {
        var host = new Host();
        var input = exact(host);
        input.keyPressed(KeyCode.F);
        input.keyPressed(KeyCode.F);
        require(host.toggles == 1, "Auto-repeat F toggles walking once");
        input.keyReleased(KeyCode.F);
        input.keyPressed(KeyCode.F);
        require(host.toggles == 2, "A second press toggles again");
    }

    private static void clearDropsHeldKeys() {
        var input = exact(new Host());
        input.keyPressed(KeyCode.W);
        input.keyPressed(KeyCode.SHIFT);
        input.clear();
        require(!input.keysHeld() && input.axes().equals(ExplorerMotion.Input.NONE),
                "Clear drops every held key");
    }

    private static void mouseDragLooksTowardTheDragSide() {
        var host = new Host();
        var input = exact(host);
        input.mousePressed(50, 50);
        input.mouseDragged(75, 40);
        require(Math.abs(host.camera.yaw() + 25 * WorldExplorerInput.LOOK_PER_PIXEL) < 1e-12,
                "Dragging right turns right (negative yaw): " + host.camera.yaw());
        require(Math.abs(host.camera.pitch() - 10 * WorldExplorerInput.LOOK_PER_PIXEL) < 1e-12,
                "Dragging up pitches up: " + host.camera.pitch());
        require(host.count("frame") == 1, "Each drag requests one frame");
        input.mouseDragged(75, 40);
        require(Math.abs(host.camera.yaw() + 25 * WorldExplorerInput.LOOK_PER_PIXEL) < 1e-12,
                "Drag deltas are relative to the previous event");
        host.camera = null;
        input.mouseDragged(0, 0);
        require(host.camera == null && host.count("frame") == 2, "No camera: drag is ignored");
    }

    private static void onlyAStillPrimaryClickInspects() {
        require(WorldExplorerInput.inspects(MouseButton.PRIMARY, true), "Still primary click inspects");
        require(!WorldExplorerInput.inspects(MouseButton.PRIMARY, false), "A drag never inspects");
        require(!WorldExplorerInput.inspects(MouseButton.SECONDARY, true), "Secondary never inspects");
    }

    private static void shiftWheelAdjustsBoundedSpeed() {
        var host = new Host();
        var input = exact(host);
        require(input.speed() == WorldExplorerInput.DEFAULT_SPEED, "Default flight speed");
        input.scroll(40, true);
        require(Math.abs(input.speed() - 96) < 1e-9, "Shift+wheel up speeds up by 20%");
        require(host.camera.equals(START), "Shift+wheel never moves");
        for (int i = 0; i < 100; i++) input.scroll(40, true);
        require(input.speed() == WorldExplorerInput.MAX_SPEED, "Speed is capped");
        for (int i = 0; i < 100; i++) input.scroll(-40, true);
        require(input.speed() == WorldExplorerInput.MIN_SPEED, "Speed has a floor");
        input.keyPressed(KeyCode.SHIFT);
        require(input.effectiveSpeed() == WorldExplorerInput.MIN_SPEED * ExplorerMotion.FLY_SPRINT,
                "Held Shift reports the sprint speed");
    }

    private static void wheelDollysAlongTheViewAndIgnoresBadInput() {
        var host = new Host();
        var input = exact(host);
        host.camera = new TerrainRenderer.Camera(100, 200, 50, .7, -.8);
        var before = host.camera;
        input.scroll(40, false);
        double dx = host.camera.x() - before.x(), dy = host.camera.y() - before.y(),
                dz = host.camera.z() - before.z();
        require(dx > 0 && dy > 0 && dz < 0, "Wheel moves forward along the pitched view");
        // One notch at 80 yd/s is clamp(8, 2, 32) = 8 yards.
        require(Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - 8) < 1e-9, "One notch is 8 yards");
        require(host.calls.equals(List.of("ground:FREE", "abandon", "frame", "nearby")),
                "Wheel reports ground, then abandons, frames and loads: " + host.calls);
        input.scroll(-40, false);
        require(Math.abs(host.camera.x() - before.x()) < 1e-9
                && Math.abs(host.camera.z() - before.z()) < 1e-9, "Reverse wheel restores");
        var settled = host.camera;
        host.calls.clear();
        input.scroll(Double.NaN, false);
        input.scroll(0, false);
        host.wheelReady = false;
        input.scroll(40, false);
        require(host.camera.equals(settled) && host.calls.isEmpty(),
                "Non-finite, zero or unready wheel input is ignored");
        host.wheelReady = true;
        host.camera = new TerrainRenderer.Camera(1_000_000, 1_000_000, 1_000_000, .7, .8);
        input.scroll(Double.MAX_VALUE, false);
        require(host.camera.x() <= 1_000_000 && host.camera.z() <= 1_000_000,
                "Wheel stays inside camera bounds");
    }

    private static void pulseIntegratesHeldKeysAtTheEstablishedRates() {
        var host = new Host();
        var input = exact(host);
        input.holdCadence();
        input.keyPressed(KeyCode.W);
        input.keyPressed(KeyCode.SPACE);
        input.keyPressed(KeyCode.LEFT);
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START), "The first pulse integrates zero seconds");
        input.pulse(1_100_000_000L);
        require(Math.abs(host.camera.x() - START.x() - 8) < 1e-9, "W flies 80 yd/s north");
        require(Math.abs(host.camera.z() - START.z() - 8) < 1e-9, "Space climbs at flight speed");
        require(Math.abs(host.camera.yaw() - .1) < 1e-12, "Left turns 1 rad/s");
        input.keyReleased(KeyCode.W);
        input.keyReleased(KeyCode.SPACE);
        input.keyReleased(KeyCode.LEFT);
        var still = host.camera;
        input.pulse(1_200_000_000L);
        require(host.camera.equals(still), "Released keys stop unsmoothed flight");
        input.pulse(1_250_000_000L);
        input.keyPressed(KeyCode.W);
        input.pulse(1_500_000_000L);
        double run = Math.hypot(host.camera.x() - still.x(), host.camera.y() - still.y());
        require(Math.abs(run - 8) < 1e-9, "A long pulse is capped at 0.1 s: " + run);
    }

    private static void pulseRequestsFramesAtCadenceAndNearbyLoadsThrottled() {
        var host = new Host();
        var input = exact(host);
        input.keyPressed(KeyCode.W);
        long t = 1_000_000_000L;
        input.pulse(t);
        host.calls.clear();
        for (int i = 1; i <= 20; i++) input.pulse(t + i * 5_000_000L);
        // 100 ms of 5 ms pulses: fast cadence (> 16 ms) frames on pulses 1, 5, 9, 13 and 17;
        // nearby loads every 100 ms.
        require(host.count("frame") == 5, "Fast cadence frames: " + host.count("frame"));
        require(host.count("abandon") == 20, "Every moving pulse checks the candidate");
        require(host.count("nearby") == 1, "Nearby loads are throttled: " + host.count("nearby"));
        host.calls.clear();
        host.fast = false;
        for (int i = 21; i <= 40; i++) input.pulse(t + i * 5_000_000L);
        require(host.count("frame") == 1, "Slow cadence frames every 100 ms: " + host.count("frame"));
        input.keyReleased(KeyCode.W);
        host.calls.clear();
        input.pulse(t + 61 * 5_000_000L);
        require(host.calls.equals(List.of("ground:FREE", "frame")),
                "The release pulse stops and frames the position the cadence held back: " + host.calls);
        host.calls.clear();
        input.pulse(t + 62 * 5_000_000L);
        require(host.calls.isEmpty(), "At rest in flight no hooks fire: " + host.calls);
    }

    private static void idleRestartsIntegrationAtZeroSeconds() {
        var host = new Host();
        var input = exact(host);
        input.holdCadence();
        input.keyPressed(KeyCode.W);
        input.pulse(1_000_000_000L);
        input.pulse(1_050_000_000L);
        var moved = host.camera;
        input.idle();
        input.pulse(9_000_000_000L);
        require(host.camera.equals(moved), "After idle the next pulse integrates zero seconds");
        host.camera = null;
        input.pulse(9_050_000_000L);
        require(host.camera == null, "No camera: the pulse does nothing");
    }

    /** Walking keeps sampling the ground every pulse, even with no key held. */
    private static void walkAtRestStillReportsTheGround() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(true, false);
        input.pulse(1_000_000_000L);
        input.pulse(1_016_000_000L);
        require(host.camera.equals(START), "No keys: walking holds the camera");
        require(host.calls.equals(List.of("ground:UNAVAILABLE", "ground:UNAVAILABLE")),
                "Each walking pulse reports the ground, never frames: " + host.calls);
    }

    /**
     * Smoothed flight keeps moving after release until the velocity decays, and the camera's
     * final position is always framed, even when the movement cadence would hold it back.
     */
    private static void smoothReleaseCoastsThenFramesTheRestingStep() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(false, true);
        input.holdCadence();
        input.keyPressed(KeyCode.W);
        long t = 1_000_000_000L;
        for (int i = 0; i < 30; i++) input.pulse(t += 16_000_000L);
        input.keyReleased(KeyCode.W);
        host.calls.clear();
        var released = host.camera;
        int pulses = 0;
        while (pulses < 1000) {
            pulses++;
            int before = host.calls.size();
            input.pulse(t += 16_000_000L);
            if (host.calls.size() == before) break;
        }
        require(pulses < 1000, "Coasting comes to rest");
        require(host.camera.x() > released.x() + 1, "Released flight coasts forward: " + (host.camera.x() - released.x()));
        require(host.count("frame") == 1, "Only the resting step frames under a held cadence: " + host.calls);
        require(host.calls.getLast().equals("frame") && host.calls.lastIndexOf("abandon") < host.calls.size() - 1,
                "The frame is requested after the final move: " + host.calls.subList(Math.max(0, host.calls.size() - 5), host.calls.size()));
        var rested = host.camera;
        host.calls.clear();
        input.pulse(t += 16_000_000L);
        require(host.camera.equals(rested) && host.calls.isEmpty(), "At rest nothing more is owed: " + host.calls);
    }

    /**
     * Walking dollies along the heading only (pitch ignored) by one notch of walk speed times
     * 0.1 s: 0.7 yd, above the 0.5 yd walking floor. With no ground the altitude is held.
     */
    private static void walkWheelMovesAlongTheHeadingOnly() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(true, false);
        host.camera = new TerrainRenderer.Camera(100, 200, 50, .7, -.8);
        input.scroll(40, false);
        double dx = host.camera.x() - 100, dy = host.camera.y() - 200;
        double notch = Math.max(.5, ExplorerMotion.WALK_SPEED * .1);
        require(Math.abs(Math.hypot(dx, dy) - notch) < 1e-9 && host.camera.z() == 50,
                "Walk notch is " + notch + " yd along the heading: " + host.camera);
        require(Math.abs(Math.atan2(dy, dx) - .7) < 1e-9, "Along the heading");
        require(host.calls.getFirst().equals("ground:UNAVAILABLE"), "Walk wheel reports the ground");
        // A fractional notch scales the step; a tiny one would still respect the 0.5 yd floor
        // only through the clamp on the speed term, never below a whole-notch fraction.
        host.camera = START;
        input.scroll(4, false);
        require(Math.abs(Math.hypot(host.camera.x() - START.x(), host.camera.y() - START.y())
                - .1 * notch) < 1e-9, "A tenth of a notch moves a tenth");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
