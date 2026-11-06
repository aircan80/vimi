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
        onlyTheRightButtonLooks();
        capturedLookRecentresAndIgnoresTheSyntheticMove();
        lookIsCoalescedToOneCameraUpdatePerPulse();
        sensitivityAndInvertYApply();
        uncapturedLookFallsBackToRelativeMovement();
        clearEndsTheLookAndDropsPendingMovement();
        controlsEncodeDecodeLeniently();
        onlyAStillPrimaryClickInspects();
        shiftWheelStepsTheSpeedPresets();
        reboundKeysDriveTheirActions();
        bindingsAndSpeedEncodeDecodeLeniently();
        wheelDollysAlongTheViewAndIgnoresBadInput();
        pulseIntegratesHeldKeysAtTheEstablishedRates();
        pulseRequestsFramesAtCadenceAndNearbyLoadsThrottled();
        idleRestartsIntegrationAtZeroSeconds();
        System.out.println(
                "World explorer input: key axes, rebinding and AZERTY, walk toggle,"
                        + " right-button captured look, per-pulse look coalescing,"
                        + " sensitivity/invert-Y, controls codec, inspect click, wheel, speed"
                        + " presets, pulse cadence and idle passed");
    }

    /** Records hook calls; the camera is the only mutable view state the input touches. */
    static final class Host implements WorldExplorerInput.Host {
        TerrainRenderer.Camera camera = START;
        boolean wheelReady = true, fast = true;
        final List<String> calls = new ArrayList<>();
        int toggles;
        final List<Boolean> cursor = new ArrayList<>();

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
        @Override public void cursorHidden(boolean hidden) { cursor.add(hidden); }
        final List<WorldControls> changed = new ArrayList<>();
        @Override public void controlsChanged(WorldControls controls) { changed.add(controls); }

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

    /** Records cursor warps; {@code lands} decides whether a warp succeeds. */
    static final class Pointer implements WorldExplorerInput.Pointer {
        final List<double[]> warps = new ArrayList<>();
        boolean lands = true;

        @Override public boolean warp(double screenX, double screenY) {
            warps.add(new double[] {screenX, screenY});
            return lands;
        }

        double[] last() {
            return warps.getLast();
        }
    }

    private static void onlyTheRightButtonLooks() {
        require(WorldExplorerInput.looks(MouseButton.SECONDARY), "Right button looks");
        require(!WorldExplorerInput.looks(MouseButton.PRIMARY), "Left button never looks");
        require(!WorldExplorerInput.looks(MouseButton.MIDDLE), "Middle button never looks");
        var host = new Host();
        var input = exact(host);
        input.lookMoved(500, 500);
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START) && host.calls.isEmpty(),
                "Moves without a held right button never look");
    }

    private static void capturedLookRecentresAndIgnoresTheSyntheticMove() {
        var host = new Host();
        var pointer = new Pointer();
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(130, 140, 400, 300);
        require(input.looking() && host.cursor.equals(List.of(true)), "Look hides the cursor");
        require(pointer.warps.size() == 1 && pointer.last()[0] == 400 && pointer.last()[1] == 300,
                "Look moves the cursor to the view centre");
        input.lookMoved(400, 300);
        require(pointer.warps.size() == 1, "The warp's own move event is ignored");
        input.lookMoved(425, 290);
        require(pointer.warps.size() == 2 && pointer.last()[0] == 400 && pointer.last()[1] == 300,
                "Each real move re-centres the cursor");
        input.lookMoved(400, 300);
        input.pulse(1_000_000_000L);
        double s = WorldControls.DEFAULT_SENSITIVITY;
        require(near(host.camera.yaw(), -25 * s) && near(host.camera.pitch(), 10 * s),
                "Right turns right and up looks up, measured from the centre: " + host.camera);
        input.lookReleased();
        require(!input.looking() && host.cursor.equals(List.of(true, false)), "Release shows the cursor");
        require(pointer.last()[0] == 130 && pointer.last()[1] == 140,
                "Release returns the cursor to where the look began");
        input.lookReleased();
        require(host.cursor.size() == 2, "A second release does nothing");
    }

    private static void lookIsCoalescedToOneCameraUpdatePerPulse() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.lookPressed(400, 300, 400, 300);
        for (int i = 0; i < 50; i++) input.lookMoved(401, 300);
        require(host.camera.equals(START) && host.calls.isEmpty(),
                "Mouse events alone never touch the camera or request frames");
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -50 * WorldControls.DEFAULT_SENSITIVITY),
                "All 50 moves land in one pulse");
        require(host.calls.equals(List.of("frame")), "One frame per pulse: " + host.calls);
        host.calls.clear();
        input.pulse(1_016_000_000L);
        require(host.calls.isEmpty(), "Nothing owed: no frame");
        // Look while flying: the look is shown at once, not held back by the movement cadence.
        input.holdCadence();
        input.keyPressed(KeyCode.W);
        input.pulse(1_032_000_000L);
        host.calls.clear();
        input.lookMoved(390, 300);
        input.pulse(1_048_000_000L);
        require(host.count("frame") == 1, "A look frames even inside the cadence: " + host.calls);
    }

    private static void sensitivityAndInvertYApply() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.controls(WorldControls.DEFAULTS.withSensitivity(.01).withInvertY(true));
        input.lookPressed(400, 300, 400, 300);
        input.lookMoved(410, 280);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -.1) && near(host.camera.pitch(), -.2),
                "Sensitivity scales and invert-Y flips pitch only: " + host.camera);
    }

    private static void uncapturedLookFallsBackToRelativeMovement() {
        var host = new Host();
        var pointer = new Pointer();
        pointer.lands = false;
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(130, 140, 400, 300);
        input.lookMoved(150, 140);
        input.lookMoved(160, 130);
        input.pulse(1_000_000_000L);
        double s = WorldControls.DEFAULT_SENSITIVITY;
        require(near(host.camera.yaw(), -30 * s) && near(host.camera.pitch(), 10 * s),
                "Without a warp each move is measured from the previous one: " + host.camera);
        require(pointer.warps.size() == 1, "A failed warp is not retried during the look");
        input.lookReleased();
        require(pointer.warps.size() == 1, "Nothing to restore when the cursor never moved");

        // A warp that starts working and then fails mid-look continues relatively.
        var flaky = new Pointer();
        var other = new Host();
        var second = exact(other);
        second.pointer(flaky);
        second.lookPressed(10, 10, 400, 300);
        flaky.lands = false;
        second.lookMoved(405, 300);
        second.lookMoved(415, 300);
        second.pulse(1_000_000_000L);
        require(near(other.camera.yaw(), -15 * s), "Relative after a failed re-centre: " + other.camera);
    }

    private static void clearEndsTheLookAndDropsPendingMovement() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.lookPressed(400, 300, 400, 300);
        input.lookMoved(450, 300);
        input.clear();
        require(!input.looking() && host.cursor.equals(List.of(true, false)),
                "Focus loss ends the look and shows the cursor");
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START), "Pending look is dropped with the keys");
    }

    private static void controlsEncodeDecodeLeniently() {
        var custom = WorldControls.DEFAULTS.withSensitivity(.011).withInvertY(true);
        require(WorldControls.decode(custom.encode()).equals(custom), "Round trip: " + custom.encode());
        require(WorldControls.decode(null).equals(WorldControls.DEFAULTS), "Nothing stored: defaults");
        require(WorldControls.decode("v9;sensitivity=0.01").equals(WorldControls.DEFAULTS),
                "Another version: defaults");
        require(WorldControls.decode("v1;sensitivity=abc;invertY=true;future=1;junk")
                        .equals(WorldControls.DEFAULTS.withInvertY(true)),
                "A malformed value keeps its default; unknown keys are ignored");
        require(WorldControls.DEFAULTS.withSensitivity(5).sensitivity() == WorldControls.MAX_SENSITIVITY
                        && WorldControls.DEFAULTS.withSensitivity(-1).sensitivity() == WorldControls.MIN_SENSITIVITY
                        && WorldControls.DEFAULTS.withSensitivity(Double.NaN).sensitivity()
                                == WorldControls.DEFAULT_SENSITIVITY,
                "Sensitivity is clamped and never non-finite");
        var store = WorldControls.memory();
        require(store.load() == null, "An empty store loads nothing");
        store.save(custom.encode());
        require(WorldControls.decode(store.load()).equals(custom), "The memory store keeps the value");
    }

    private static void onlyAStillPrimaryClickInspects() {
        require(WorldExplorerInput.inspects(MouseButton.PRIMARY, true), "Still primary click inspects");
        require(!WorldExplorerInput.inspects(MouseButton.PRIMARY, false), "A drag never inspects");
        require(!WorldExplorerInput.inspects(MouseButton.SECONDARY, true), "Secondary never inspects");
    }

    private static void shiftWheelStepsTheSpeedPresets() {
        var host = new Host();
        var input = exact(host);
        require(input.controls().speed() == WorldControls.Speed.FLY && input.speed() == 20,
                "Fly at 20 yd/s is the default");
        double[] expected = {7, 14, 20, 60, 200};
        for (var speed : WorldControls.Speed.values())
            require(speed.yardsPerSecond == expected[speed.ordinal()], "Preset " + speed);
        input.scroll(40, true);
        require(input.speed() == 60 && host.changed.getLast().speed() == WorldControls.Speed.FAST,
                "Shift+wheel up steps to the next preset and reports it");
        require(host.camera.equals(START), "Shift+wheel never moves");
        for (int i = 0; i < 10; i++) input.scroll(40, true);
        require(input.speed() == 200 && host.changed.size() == 2, "Turbo is the top; no further reports");
        for (int i = 0; i < 10; i++) input.scroll(-40, true);
        require(input.speed() == 7, "Walk is the slowest preset");
        input.keyPressed(KeyCode.SHIFT);
        require(input.effectiveSpeed() == 7 * ExplorerMotion.FLY_SPRINT && ExplorerMotion.FLY_SPRINT == 2,
                "Held Shift is a modest 2x");
    }

    private static void reboundKeysDriveTheirActions() {
        var host = new Host();
        var input = exact(host);
        input.controls(WorldControls.DEFAULTS.withKeys(WorldControls.Layout.AZERTY.keys()));
        check(input, List.of(KeyCode.Z), 1, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.Q), 0, -1, 0, 0, 0, false);
        check(input, List.of(KeyCode.A), 0, 0, -1, 0, 0, false);
        check(input, List.of(KeyCode.W), 0, 0, 0, 0, 0, false);
        var custom = WorldControls.DEFAULTS.bind(KeyCode.I, WorldControls.Action.FORWARD)
                .bind(KeyCode.W, WorldControls.Action.BACK)
                .bind(KeyCode.G, WorldControls.Action.WALK)
                .unbind(WorldControls.Action.SPRINT)
                .bind(KeyCode.CONTROL, WorldControls.Action.SPRINT);
        input.controls(custom);
        check(input, List.of(KeyCode.I), 1, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.W), -1, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.SHIFT), 0, 0, 0, 0, 0, false);
        check(input, List.of(KeyCode.CONTROL), 0, 0, 0, 0, 0, true);
        input.keyPressed(KeyCode.G);
        input.keyPressed(KeyCode.G);
        require(host.toggles == 1, "The rebound walk key toggles once per press: " + host.toggles);
        input.keyPressed(KeyCode.F);
        require(host.toggles == 2, "F stays bound to walk alongside G");
        input.keyReleased(KeyCode.G);
        input.keyReleased(KeyCode.F);
        require(custom.bind(KeyCode.ESCAPE, WorldControls.Action.UP).equals(custom)
                        && custom.bind(KeyCode.F11, WorldControls.Action.UP).equals(custom)
                        && custom.bind(KeyCode.ALT, WorldControls.Action.UP).equals(custom),
                "Escape, F11 and Alt never bind");
        require(custom.byAction().get(WorldControls.Action.FORWARD).equals(List.of(KeyCode.I)),
                "W moved from forward to back: " + custom.byAction());
    }

    private static void bindingsAndSpeedEncodeDecodeLeniently() {
        var custom = WorldControls.DEFAULTS.withSpeed(WorldControls.Speed.TURBO)
                .withKeys(WorldControls.Layout.AZERTY.keys())
                .bind(KeyCode.NUMPAD8, WorldControls.Action.FORWARD);
        var decoded = WorldControls.decode(custom.encode());
        require(decoded.equals(custom), "Speed and bindings round trip: " + custom.encode());
        require(WorldControls.decode("v1;sensitivity=0.006").keys().equals(WorldControls.DEFAULTS.keys())
                        && WorldControls.decode("v1;sensitivity=0.006").speed() == WorldControls.Speed.FLY,
                "Older values without speed or keys keep the defaults");
        var lenient = WorldControls.decode("v1;speed=WARP;keys=W:FORWARD,NOPE:BACK,S:SIDEWAYS,ESCAPE:UP,junk");
        require(lenient.speed() == WorldControls.Speed.FLY
                        && lenient.keys().equals(java.util.Map.of(KeyCode.W, WorldControls.Action.FORWARD)),
                "Unknown speeds, key codes, actions and reserved keys are dropped: " + lenient);
        require(WorldControls.decode("v1;keys=").keys().isEmpty(), "Every binding can be cleared");
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
        // One notch at the default 20 yd/s is clamp(20 * 0.25, 2, 32) = 5 yards.
        require(Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - 5) < 1e-9, "One notch is 5 yards");
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
        require(Math.abs(host.camera.x() - START.x() - 2) < 1e-9, "W flies 20 yd/s north");
        require(Math.abs(host.camera.z() - START.z() - 2) < 1e-9, "Space climbs at flight speed");
        require(Math.abs(host.camera.yaw() - ExplorerMotion.TURN_RATE * .1) < 1e-12,
                "Left turns at the keyboard turn rate");
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
        require(Math.abs(run - 2) < 1e-9, "A long pulse is capped at 0.1 s: " + run);
    }

    private static void pulseRequestsFramesAtCadenceAndNearbyLoadsThrottled() {
        var host = new Host();
        var input = exact(host);
        input.keyPressed(KeyCode.W);
        long t = 1_000_000_000L;
        input.pulse(t);
        host.calls.clear();
        for (int i = 1; i <= 20; i++) input.pulse(t + i * 5_000_000L);
        // 100 ms of 5 ms pulses: fast cadence (> 16 ms) frames every 4th pulse, nearby every 100 ms.
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
        require(host.calls.equals(List.of("ground:FREE")),
                "The release pulse drops retained velocity without moving: " + host.calls);
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

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
