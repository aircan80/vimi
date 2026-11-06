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
        lookButtons();
        capturedLookRecentresAndIgnoresTheSyntheticMove();
        fractionalLandingNeverDriftsOrLoops();
        aLandingStillInFlightIsAOneOff();
        repeatedPressFailuresTurnCaptureOffAndLogOnce();
        theWindowMovingMidLookRecentresOnTheNewCentre();
        leftDragLooksWithoutCapture();
        lookIsCoalescedToOneCameraUpdatePerPulse();
        sensitivityAndInvertYApply();
        uncapturedLookFallsBackToRelativeMovement();
        clearEndsTheLookAndDropsPendingMovement();
        owedLookIsDroppedWhileNoPulseApplies();
        wheelReadyCoversStreamingMode();
        controlsEncodeDecodeLeniently();
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
                "World explorer input: key axes, walk toggle, right-button captured look"
                        + " (landing, in-flight, failures, window move), left-drag look,"
                        + " per-pulse look coalescing, sensitivity/invert-Y, controls codec,"
                        + " inspect click, wheel, speed, pulse cadence and idle passed");
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
        final List<String> fallbacks = new ArrayList<>();
        @Override public void captureFallback(String reason) { fallbacks.add(reason); }

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

    /**
     * Records cursor warps. {@code lands} false refuses a warp (null); otherwise the cursor lands at
     * the request plus {@code offset} (a physical-pixel rounding), or stays at {@code stuck} while a
     * move is still in flight.
     */
    static final class Pointer implements WorldExplorerInput.Pointer {
        final List<double[]> warps = new ArrayList<>();
        boolean lands = true;
        double offset;
        double[] stuck;

        @Override public double[] warp(double screenX, double screenY) {
            warps.add(new double[] {screenX, screenY});
            if (!lands) return null;
            if (stuck != null) return stuck.clone();
            return new double[] {screenX + offset, screenY + offset};
        }

        double[] last() {
            return warps.getLast();
        }
    }

    private static void lookButtons() {
        require(WorldExplorerInput.looks(MouseButton.SECONDARY) && WorldExplorerInput.looks(MouseButton.PRIMARY),
                "Right and left buttons look");
        require(!WorldExplorerInput.looks(MouseButton.MIDDLE), "Middle button never looks");
        require(WorldExplorerInput.captures(MouseButton.SECONDARY) && !WorldExplorerInput.captures(MouseButton.PRIMARY),
                "Only the right button captures the cursor");
        var host = new Host();
        var input = exact(host);
        input.lookMoved(500, 500, 400, 300);
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START) && host.calls.isEmpty(),
                "Moves without a held look button never look");
        input.lookPressed(MouseButton.MIDDLE, 1, 1, 400, 300);
        require(!input.looking(), "Middle press starts nothing");
    }

    private static void capturedLookRecentresAndIgnoresTheSyntheticMove() {
        var host = new Host();
        var pointer = new Pointer();
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.SECONDARY, 130, 140, 400, 300);
        require(input.looking() && host.cursor.equals(List.of(true)), "Look hides the cursor");
        require(pointer.warps.size() == 1 && pointer.last()[0] == 400 && pointer.last()[1] == 300,
                "Look moves the cursor to the view centre");
        input.lookMoved(400, 300, 400, 300);
        require(pointer.warps.size() == 1, "The warp's own move event is ignored");
        input.lookMoved(425, 290, 400, 300);
        require(pointer.warps.size() == 2 && pointer.last()[0] == 400 && pointer.last()[1] == 300,
                "Each real move re-centres the cursor");
        input.lookMoved(400, 300, 400, 300);
        input.pulse(1_000_000_000L);
        double s = WorldControls.DEFAULT_SENSITIVITY;
        require(near(host.camera.yaw(), -25 * s) && near(host.camera.pitch(), 10 * s),
                "Right turns right and up looks up, measured from the centre: " + host.camera);
        input.lookReleased(MouseButton.PRIMARY);
        require(input.looking(), "Releasing another button keeps the look");
        input.lookReleased(MouseButton.SECONDARY);
        require(!input.looking() && host.cursor.equals(List.of(true, false)), "Release shows the cursor");
        require(pointer.last()[0] == 130 && pointer.last()[1] == 140,
                "Release returns the cursor to where the look began");
        input.lookReleased();
        require(host.cursor.size() == 2, "A second release does nothing");
        require(host.fallbacks.isEmpty(), "No fallback when capture works");
    }

    /**
     * At 125 % or 150 % output scale (or an odd view size at 100 %) the cursor lands half a unit
     * from the centre. Moves are measured from where it landed, so its own move event is still
     * ignored, nothing drifts, and there is exactly one warp per real move.
     */
    private static void fractionalLandingNeverDriftsOrLoops() {
        var host = new Host();
        var pointer = new Pointer();
        pointer.offset = .5;
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
        input.lookMoved(400.5, 300.5, 400, 300);
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START) && pointer.warps.size() == 1,
                "The event at the landed position is the warp's own: no motion, no re-warp");
        for (int i = 0; i < 100; i++) {
            input.lookMoved(401.5, 300.5, 400, 300);
            input.lookMoved(400.5, 300.5, 400, 300);
        }
        input.pulse(1_016_000_000L);
        require(near(host.camera.yaw(), -100 * WorldControls.DEFAULT_SENSITIVITY) && near(host.camera.pitch(), 0),
                "100 one-unit moves turn exactly 100 units, with no drift: " + host.camera);
        require(pointer.warps.size() == 101, "One warp per real move, no loop: " + pointer.warps.size());
        require(host.fallbacks.isEmpty(), "Rounding is not a failure");
    }

    /** A warp still in flight (the cursor not there yet) is assumed to land: capture continues. */
    private static void aLandingStillInFlightIsAOneOff() {
        var host = new Host();
        var pointer = new Pointer();
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
        pointer.stuck = new double[] {430, 300};
        input.lookMoved(430, 300, 400, 300);
        pointer.stuck = null;
        input.lookMoved(400, 300, 400, 300);
        input.lookMoved(405, 300, 400, 300);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -35 * WorldControls.DEFAULT_SENSITIVITY),
                "Measured from the centre once the late move lands: " + host.camera);
        require(pointer.warps.size() == 3 && host.fallbacks.isEmpty(), "Capture stays on");
        input.lookReleased();
        input.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
        require(pointer.warps.size() == 5, "The next look captures again");
    }

    private static void repeatedPressFailuresTurnCaptureOffAndLogOnce() {
        var host = new Host();
        var pointer = new Pointer();
        pointer.stuck = new double[] {0, 0};
        var input = exact(host);
        input.pointer(pointer);
        for (int i = 1; i <= WorldExplorerInput.CAPTURE_FAILURES; i++) {
            input.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
            input.lookMoved(20, 10, 400, 300);
            require(pointer.warps.size() == i, "A failed press look stays relative: no re-centring");
            input.lookReleased();
        }
        require(host.fallbacks.size() == WorldExplorerInput.CAPTURE_FAILURES
                        && host.fallbacks.getLast().contains("in a row"),
                "Each relative look reports why; the last says capture is off: " + host.fallbacks);
        pointer.stuck = null;
        input.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
        require(pointer.warps.size() == WorldExplorerInput.CAPTURE_FAILURES, "Capture stays off");
        require(host.cursor.getLast(), "The cursor is still hidden while looking");
        input.lookReleased();
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -30 * WorldControls.DEFAULT_SENSITIVITY), "Relative looks still turn");
        // One success in between resets the count.
        var other = new Pointer();
        var second = exact(new Host());
        second.pointer(other);
        other.stuck = new double[] {0, 0};
        second.lookPressed(MouseButton.SECONDARY, 1, 1, 400, 300);
        second.lookReleased();
        other.stuck = null;
        second.lookPressed(MouseButton.SECONDARY, 1, 1, 400, 300);
        second.lookReleased();
        other.stuck = new double[] {0, 0};
        for (int i = 0; i < WorldExplorerInput.CAPTURE_FAILURES - 1; i++) {
            second.lookPressed(MouseButton.SECONDARY, 1, 1, 400, 300);
            second.lookReleased();
        }
        other.stuck = null;
        int before = other.warps.size();
        second.lookPressed(MouseButton.SECONDARY, 1, 1, 400, 300);
        require(other.warps.size() > before, "A success resets the failure count");
    }

    private static void theWindowMovingMidLookRecentresOnTheNewCentre() {
        var host = new Host();
        var pointer = new Pointer();
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
        input.lookMoved(410, 300, 400, 300);
        // The window moved 100 units right: the next move is measured from the old landing, then
        // the cursor goes to the new centre.
        input.lookMoved(405, 300, 500, 300);
        require(pointer.last()[0] == 500, "Re-centred on the moved view");
        input.lookMoved(520, 300, 500, 300);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -(10 + 5 + 20) * WorldControls.DEFAULT_SENSITIVITY),
                "Movement is counted from where the cursor really was: " + host.camera);
    }

    private static void leftDragLooksWithoutCapture() {
        var host = new Host();
        var pointer = new Pointer();
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.PRIMARY, 50, 50, 400, 300);
        require(input.looking() && pointer.warps.isEmpty() && host.cursor.isEmpty(),
                "Left look never warps or hides the cursor");
        input.lookPressed(MouseButton.SECONDARY, 50, 50, 400, 300);
        require(pointer.warps.isEmpty(), "A second button during a look is ignored");
        input.lookMoved(75, 40, 400, 300);
        input.lookMoved(80, 40, 400, 300);
        input.pulse(1_000_000_000L);
        double s = WorldControls.DEFAULT_SENSITIVITY;
        require(near(host.camera.yaw(), -30 * s) && near(host.camera.pitch(), 10 * s),
                "Left drag looks with relative movement: " + host.camera);
        input.lookReleased(MouseButton.SECONDARY);
        require(input.looking(), "Only the button that started the look ends it");
        input.lookReleased(MouseButton.PRIMARY);
        require(!input.looking() && pointer.warps.isEmpty() && host.cursor.isEmpty(),
                "Release restores nothing it never moved");
    }

    private static void lookIsCoalescedToOneCameraUpdatePerPulse() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.lookPressed(MouseButton.SECONDARY, 400, 300, 400, 300);
        for (int i = 0; i < 50; i++) input.lookMoved(401, 300, 400, 300);
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
        input.lookMoved(390, 300, 400, 300);
        input.pulse(1_048_000_000L);
        require(host.count("frame") == 1, "A look frames even inside the cadence: " + host.calls);
    }

    private static void sensitivityAndInvertYApply() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.controls(WorldControls.DEFAULTS.withSensitivity(.01).withInvertY(true));
        input.lookPressed(MouseButton.SECONDARY, 400, 300, 400, 300);
        input.lookMoved(410, 280, 400, 300);
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
        input.lookPressed(MouseButton.SECONDARY, 130, 140, 400, 300);
        input.lookMoved(150, 140, 400, 300);
        input.lookMoved(160, 130, 400, 300);
        input.pulse(1_000_000_000L);
        double s = WorldControls.DEFAULT_SENSITIVITY;
        require(near(host.camera.yaw(), -30 * s) && near(host.camera.pitch(), 10 * s),
                "Without a warp each move is measured from the previous one: " + host.camera);
        require(pointer.warps.size() == 1, "A failed warp is not retried during the look");
        input.lookReleased();
        require(pointer.warps.size() == 1, "Nothing to restore when the cursor never moved");

        // A warp that starts working and then is refused mid-look continues relatively.
        var flaky = new Pointer();
        var other = new Host();
        var second = exact(other);
        second.pointer(flaky);
        second.lookPressed(MouseButton.SECONDARY, 10, 10, 400, 300);
        flaky.lands = false;
        second.lookMoved(405, 300, 400, 300);
        second.lookMoved(415, 300, 400, 300);
        second.pulse(1_000_000_000L);
        require(near(other.camera.yaw(), -15 * s), "Relative after a refused re-centre: " + other.camera);
        require(other.fallbacks.size() == 1, "The refusal is reported");
    }

    private static void clearEndsTheLookAndDropsPendingMovement() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.lookPressed(MouseButton.SECONDARY, 400, 300, 400, 300);
        input.lookMoved(450, 300, 400, 300);
        input.clear();
        require(!input.looking() && host.cursor.equals(List.of(true, false)),
                "Focus loss ends the look and shows the cursor");
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START), "Pending look is dropped with the keys");
    }

    /** Movement owed while pulses skip (no camera yet, timer stopped) never lands as a jump. */
    private static void owedLookIsDroppedWhileNoPulseApplies() {
        var host = new Host();
        var input = exact(host);
        input.lookPressed(MouseButton.PRIMARY, 0, 0, 400, 300);
        host.camera = null;
        input.lookMoved(300, 0, 400, 300);
        input.pulse(1_000_000_000L);
        host.camera = START;
        input.pulse(1_016_000_000L);
        require(host.camera.equals(START), "Owed while there was no camera: dropped");
        input.lookMoved(600, 0, 400, 300);
        input.idle();
        input.pulse(1_032_000_000L);
        require(host.camera.equals(START), "Owed across a timer stop (idle): dropped");
        input.lookMoved(601, 0, 400, 300);
        input.pulse(1_048_000_000L);
        require(near(host.camera.yaw(), -WorldControls.DEFAULT_SENSITIVITY), "Later moves still look");
    }

    private static void wheelReadyCoversStreamingMode() {
        require(WorldExplorerInput.wheelReady(true, false, false), "Legacy scene");
        require(WorldExplorerInput.wheelReady(false, true, true), "GPU streaming with a controller");
        require(!WorldExplorerInput.wheelReady(false, true, false), "Streaming without a controller");
        require(!WorldExplorerInput.wheelReady(false, false, true), "No scene outside streaming");
    }

    private static void controlsEncodeDecodeLeniently() {
        var custom = new WorldControls(.011, true);
        require(WorldControls.decode(custom.encode()).equals(custom), "Round trip: " + custom.encode());
        require(WorldControls.decode(null).equals(WorldControls.DEFAULTS), "Nothing stored: defaults");
        require(WorldControls.decode("v9;sensitivity=0.01").equals(WorldControls.DEFAULTS),
                "Another version: defaults");
        require(WorldControls.decode("v1;sensitivity=abc;invertY=true;future=1;junk")
                        .equals(new WorldControls(WorldControls.DEFAULT_SENSITIVITY, true)),
                "A malformed value keeps its default; unknown keys are ignored");
        require(new WorldControls(5, false).sensitivity() == WorldControls.MAX_SENSITIVITY
                        && new WorldControls(-1, false).sensitivity() == WorldControls.MIN_SENSITIVITY
                        && new WorldControls(Double.NaN, false).sensitivity()
                                == WorldControls.DEFAULT_SENSITIVITY,
                "Sensitivity is clamped and never non-finite");
        var store = WorldControls.memory();
        require(store.load().join() == null, "An empty store loads nothing");
        store.save(custom.encode());
        require(WorldControls.decode(store.load().join()).equals(custom), "The memory store keeps the value");
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

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
