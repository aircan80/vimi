package com.vimi.gui;

import com.vibeminer.rendering.world.CollisionWorld;
import com.vibeminer.rendering.world.ExplorerMotion;
import com.vibeminer.rendering.world.FollowCamera;
import com.vibeminer.rendering.world.MovementParity;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.TerrainRenderer;
import com.vibeminer.rendering.world.WalkController;
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
        aDrawnBodyPlaysItsSequencesAndKeepsFramingWhileItStands();
        keysMapToClampedAxes();
        walkToggleIgnoresKeyRepeat();
        theModeKeyCyclesFreeWalkFlyAndSwapsTheBody();
        aPulseTimedBeforeTheLastOneDoesNotStepBackwards();
        storedBindingsKeepTheModeKey();
        commandKeysFireOncePerPressAndNeverMove();
        devOverlayKeyCommandsOncePerPress();
        hudKeysDefaultWithoutClashes();
        clearDropsHeldKeys();
        lookButtons();
        capturedLookIsRelativeAwayFromTheEdge();
        nearTheEdgeItWarpsAndTheEchoIsNotCounted();
        roundingOffsetEchoesNeverDriftOrLoop();
        floatEchoOfADoubleTargetIsRecognised();
        motionBeforeTheEchoStillCounts();
        slowOnePixelMotionAlwaysTurns();
        aMissingEchoTimesOutAndRepeatedMissesStopWarping();
        aRefusedWarpStopsWarping();
        aLateEchoResetsTheMissCount();
        anEchoCoalescedWithMotionLosesAtMostThatMotion();
        fastPreEchoMotionIsMisreadAtMostOncePerWarp();
        aViewPastTheScreenEdgeStillWarpsOnScreen();
        theWindowMovingMidLookWarpsToTheNewCentre();
        leftDragLooksWithoutCapture();
        lookIsCoalescedToOneCameraUpdatePerPulse();
        sensitivityAndInvertYApply();
        clearEndsTheLookAndDropsPendingMovement();
        owedLookIsDroppedWhileNoPulseApplies();
        wheelReadyCoversStreamingMode();
        controlsEncodeDecodeLeniently();
        viewDistanceIsRememberedWithTheControls();
        renderScaleIsRememberedWithTheControls();
        onlyAStillPrimaryClickInspects();
        shiftWheelStepsTheSpeedPresets();
        reboundKeysDriveTheirActions();
        bindingsAndSpeedEncodeDecodeLeniently();
        theSpeedWheelFollowsTheSprintBinding();
        storedBindingsFallBackAndGainNewActions();
        helpFollowsTheBindings();
        wheelDollysAlongTheViewAndIgnoresBadInput();
        pulseIntegratesHeldKeysAtTheEstablishedRates();
        pulseRequestsFramesAtCadenceAndNearbyLoadsThrottled();
        idleRestartsIntegrationAtZeroSeconds();
        walkingPlacesTheBodyAndTheCameraFollowsIt();
        walkFlyAndFirstPersonKeysReachTheirHooks();
        flyingWithABodyGoesAtLeastTaxiSpeed();
        smoothReleaseCoastsThenFramesTheRestingStep();
        theCameraComingToRestIsReportedOncePerStop();
        bodyWheelZoomsTheFollowCamera();
        System.out.println(
                "World explorer input: key axes, rebinding and AZERTY, walk toggle,"
                        + " right-button captured look (edge warps, event echoes, rounding, slow"
                        + " motion, timeouts, window move), left-drag look, per-pulse look"
                        + " coalescing, sensitivity/invert-Y, controls codec, inspect click, wheel,"
                        + " speed presets, pulse cadence and idle passed");
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
        boolean idle = true;
        @Override public boolean idleFrames() { return idle; }
        @Override public void groundStatus(ExplorerMotion.Ground ground) { calls.add("ground:" + ground); }
        @Override public void toggleWalk() { toggles++; }
        int flyToggles;
        @Override public void toggleFly() { flyToggles++; }
        CollisionWorld collision = FLOOR;
        @Override public CollisionWorld collision() { return collision; }
        final List<String> statuses = new ArrayList<>();
        @Override public void bodyStatus(WorldExplorerInput.Mode mode, WalkController.Status status) {
            statuses.add(mode + ":" + status);
        }
        @Override public void requestFrame() { calls.add("frame"); }
        @Override public void abandonUnusableCandidate() { calls.add("abandon"); }
        @Override public void loadNext() { calls.add("nearby"); }
        int rests;
        @Override public void cameraAtRest() { rests++; }
        @Override public void cursorHidden(boolean hidden) { cursor.add(hidden); }
        final List<WorldControls> changed = new ArrayList<>();
        @Override public void controlsChanged(WorldControls controls) { changed.add(controls); }
        final List<String> fallbacks = new ArrayList<>();
        @Override public void captureFallback(String reason) { fallbacks.add(reason); }
        int unavailable;
        @Override public void captureUnavailable() { unavailable++; }
        final List<WorldControls.Action> commands = new ArrayList<>();
        @Override public void command(WorldControls.Action action) { commands.add(action); }

        long count(String call) {
            return calls.stream().filter(call::equals).count();
        }
    }

    /**
     * HUD commands (M: map panel, H: hide the HUD) fire once per physical press, in both layouts,
     * and never move.
     */
    private static void commandKeysFireOncePerPressAndNeverMove() {
        for (var layout : WorldControls.Layout.values()) {
            // Every action keeps a key, so no preset key clashes; only the AZERTY preset leaves
            // the speed digits unbound (its digits need Shift).
            var expected = java.util.EnumSet.allOf(WorldControls.Action.class);
            if (layout == WorldControls.Layout.AZERTY)
                expected.removeIf(action -> action.speed() != null);
            require(new java.util.HashSet<>(layout.keys().values()).equals(expected),
                    layout + ": every action keeps a key, so no preset key clashes: " + layout.keys());
            for (var command : List.of(
                    java.util.Map.entry(KeyCode.M, WorldControls.Action.MAP_PANEL),
                    java.util.Map.entry(KeyCode.H, WorldControls.Action.HIDE_HUD))) {
                var key = command.getKey();
                var host = new Host();
                var input = exact(host);
                input.controls(WorldControls.DEFAULTS.withKeys(layout.keys()));
                require(layout.keys().get(key) == command.getValue(), layout + ": " + key + " is " + command.getValue());
                input.keyPressed(key);
                for (int i = 0; i < 20; i++) input.keyPressed(key);
                require(host.commands.equals(List.of(command.getValue())),
                        layout + ": held " + key + " (key repeat) is one command: " + host.commands);
                require(input.axes().equals(ExplorerMotion.Input.NONE) && host.toggles == 0,
                        layout + ": " + key + " never moves or walks");
                input.keyReleased(key);
                input.keyPressed(key);
                require(host.commands.size() == 2, layout + ": a new press is a new command");
                input.keyReleased(key);
                input.keyPressed(KeyCode.W);
                input.keyPressed(KeyCode.F);
                require(host.commands.size() == 2, layout + ": movement and walk keys are not commands");
            }
        }
        var commands = java.util.EnumSet.of(WorldControls.Action.MAP_PANEL,
                WorldControls.Action.HIDE_HUD, WorldControls.Action.DEV_OVERLAY,
                WorldControls.Action.SPAWNS, WorldControls.Action.TRACKING, WorldControls.Action.MINIMAP,
                WorldControls.Action.DAY_NIGHT, WorldControls.Action.SPEED_STROLL,
                WorldControls.Action.SPEED_MOUNT, WorldControls.Action.SPEED_FLY,
                WorldControls.Action.SPEED_FAST, WorldControls.Action.SPEED_TURBO,
                WorldControls.Action.INSPECT_PIN, WorldControls.Action.SCREENSHOT,
                WorldControls.Action.BOOKMARKS);
        for (var action : WorldControls.Action.values())
            require(action.command == commands.contains(action),
                    "Exactly the HUD keys are commands: " + action);
        var host = new Host();
        var input = exact(host);
        input.controls(WorldControls.DEFAULTS.bind(KeyCode.G, WorldControls.Action.MAP_PANEL));
        input.keyPressed(KeyCode.G);
        require(host.commands.equals(List.of(WorldControls.Action.MAP_PANEL)), "A rebound map key works");
        var older = WorldControls.decode("v1;keys=W:FORWARD;actions=FORWARD,BACK,LEFT,RIGHT,UP,DOWN,"
                + "TURN_LEFT,TURN_RIGHT,LOOK_UP,LOOK_DOWN,SPRINT,WALK");
        require(older.keys().get(KeyCode.M) == WorldControls.Action.MAP_PANEL,
                "A value saved before the map panel existed gains its default key: " + older.keys());
        var taken = WorldControls.decode("v1;keys=M:FORWARD;actions=FORWARD");
        require(taken.keys().get(KeyCode.M) == WorldControls.Action.FORWARD
                        && taken.byAction().get(WorldControls.Action.MAP_PANEL).isEmpty(),
                "A stored binding that already uses M keeps it: " + taken.keys());
        require(older.keys().get(KeyCode.H) == WorldControls.Action.HIDE_HUD,
                "A value saved before hide HUD existed gains H: " + older.keys());
        var takenH = WorldControls.decode("v1;keys=H:WALK;actions=WALK");
        require(takenH.keys().get(KeyCode.H) == WorldControls.Action.WALK
                        && takenH.byAction().get(WorldControls.Action.HIDE_HUD).isEmpty(),
                "A stored binding that already uses H keeps it: " + takenH.keys());
        var rebound = new Host();
        var reboundInput = exact(rebound);
        reboundInput.controls(WorldControls.DEFAULTS.bind(KeyCode.J, WorldControls.Action.HIDE_HUD));
        reboundInput.keyPressed(KeyCode.J);
        require(rebound.commands.equals(List.of(WorldControls.Action.HIDE_HUD)), "A rebound hide HUD key works");
        require(WorldControls.DEFAULTS.help().contains("M map")
                        && WorldControls.DEFAULTS.help().contains("H hides the HUD"),
                "Help names the map and hide HUD keys: " + WorldControls.DEFAULTS.help());
    }

    /** A flat floor at height 40 everywhere near START, known everywhere. */
    static final CollisionWorld FLOOR = new CollisionWorld() {
        @Override
        public void triangles(double x0, double y0, double z0, double x1, double y1, double z1,
                com.vibeminer.rendering.world.TerrainGround.TriangleSink sink) {
            sink.triangle(-10_000, -10_000, 40, 10_000, -10_000, 40, 10_000, 10_000, 40);
            sink.triangle(-10_000, -10_000, 40, 10_000, 10_000, 40, -10_000, 10_000, 40);
        }

        @Override
        public Coverage coverage(double x0, double y0, double x1, double y1) {
            return Coverage.READY;
        }
    };

    private static WorldExplorerInput exact(Host host) {
        var input = new WorldExplorerInput(host);
        input.motion(WorldExplorerInput.Mode.FREE, false);
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

    /**
     * The mode key moves free camera to walk to fly and back to free camera, one press each; the
     * body follows the mode (the murloc walks, the gryphon flies) and the status line names the key
     * and the next mode.
     */
    private static void theModeKeyCyclesFreeWalkFlyAndSwapsTheBody() {
        var mode = WorldExplorerInput.Mode.FREE;
        var seen = new java.util.ArrayList<WorldExplorerInput.Mode>();
        var bodies = new java.util.ArrayList<Long>();
        for (int press = 0; press < 4; press++) {
            mode = mode.next();
            seen.add(mode);
            bodies.add(WorldBodyModel.display(mode));
        }
        require(seen.equals(List.of(WorldExplorerInput.Mode.WALK, WorldExplorerInput.Mode.FLY,
                        WorldExplorerInput.Mode.FREE, WorldExplorerInput.Mode.WALK)),
                "Free camera, then walk, then fly, then free camera: " + seen);
        require(bodies.equals(List.of(WorldBodyModel.MURLOC, WorldBodyModel.GRYPHON,
                        WorldBodyModel.MURLOC, WorldBodyModel.MURLOC)),
                "Walking draws the murloc, flying the gryphon: " + bodies);
        require(WorldExplorerInput.Mode.FREE.nextHint("F").equals(" · F: walk")
                        && WorldExplorerInput.Mode.WALK.nextHint("F").equals(" · F: fly")
                        && WorldExplorerInput.Mode.FLY.nextHint("F").equals(" · F: free camera"),
                "The status line names the key and the next mode");
        // Each physical press of the mode key asks the view for the next mode once.
        var host = new Host();
        var input = exact(host);
        for (int press = 1; press <= 3; press++) {
            input.keyPressed(KeyCode.F);
            input.keyReleased(KeyCode.F);
            require(host.toggles == press, "Press " + press + " asks for the next mode once");
        }
    }

    /**
     * A pulse timed before the last one (a test's fake clock beside the toolkit's pulses) moves
     * neither the body nor the camera, never throws, and keeps the later time: the next pulse
     * after it integrates from there.
     */
    private static void aPulseTimedBeforeTheLastOneDoesNotStepBackwards() {
        var host = new Host();
        var input = exact(host);
        input.motion(WorldExplorerInput.Mode.WALK, false);
        input.keyPressed(KeyCode.W);
        input.pulse(5_000_000_000L);
        input.pulse(6_000_000_000L);
        var body = input.body().body();
        double x = body.x(), y = body.y(), z = body.z();
        var camera = host.camera();
        input.pulse(1_000_000_000L);
        require(body.x() == x && body.y() == y && body.z() == z, "The body did not move backwards");
        require(java.util.Objects.equals(host.camera(), camera), "Nor did the camera");
        // Still before the kept time: nothing again.
        input.pulse(5_900_000_000L);
        require(body.x() == x && body.y() == y && body.z() == z, "Nothing before the kept time either");
        input.keyReleased(KeyCode.W);
    }

    /**
     * Stored bindings name the mode key's action as before (WALK), so a saved value keeps its key
     * and nothing needs migrating; the walk/fly key keeps G.
     */
    private static void storedBindingsKeepTheModeKey() {
        var stored = WorldControls.decode("v1;keys=F:WALK,G:FLY,W:FORWARD");
        require(stored.keys().get(KeyCode.F) == WorldControls.Action.WALK
                        && stored.keys().get(KeyCode.G) == WorldControls.Action.FLY,
                "A saved value keeps F as the mode key and G as walk/fly: " + stored.keys());
        var moved = WorldControls.decode("v1;keys=M:WALK");
        require(moved.keys().get(KeyCode.M) == WorldControls.Action.WALK
                        && moved.help().contains("M next mode"),
                "A rebound mode key stays rebound: " + moved.help());
        require(WorldControls.DEFAULTS.encode().contains("F:WALK"),
                "The stored name of the mode key's action is unchanged: " + WorldControls.DEFAULTS.encode());
    }

    private static void devOverlayKeyCommandsOncePerPress() {
        for (var layout : WorldControls.Layout.values()) {
            var keys = layout.keys();
            require(keys.get(KeyCode.F3) == WorldControls.Action.DEV_OVERLAY,
                    layout + " binds F3 to the developer overlay: " + keys);
            require(keys.values().stream().filter(WorldControls.Action.DEV_OVERLAY::equals).count() == 1,
                    layout + " binds the developer overlay to one key only");
        }
        var host = new Host();
        var input = exact(host);
        input.keyPressed(KeyCode.F3);
        input.keyPressed(KeyCode.F3);
        require(host.commands.equals(List.of(WorldControls.Action.DEV_OVERLAY)) && host.toggles == 0,
                "Auto-repeat F3 commands the overlay once and never walks: " + host.commands);
        require(input.axes().equals(ExplorerMotion.Input.NONE), "F3 moves nothing");
        input.keyReleased(KeyCode.F3);
        input.keyPressed(KeyCode.F3);
        require(host.commands.size() == 2, "A second press commands again");
        input.keyReleased(KeyCode.F3);
        input.keyPressed(KeyCode.F);
        require(host.commands.size() == 2 && host.toggles == 1, "Walk keeps its own toggle hook");
        input.controls(WorldControls.DEFAULTS.unbind(WorldControls.Action.DEV_OVERLAY)
                .bind(KeyCode.F7, WorldControls.Action.DEV_OVERLAY));
        input.keyPressed(KeyCode.F3);
        input.keyPressed(KeyCode.F7);
        require(host.commands.size() == 3, "The overlay follows its rebinding: " + host.commands);
        var older = WorldControls.decode("v1;keys=W:FORWARD;actions=FORWARD,BACK,WALK");
        require(older.keys().get(KeyCode.F3) == WorldControls.Action.DEV_OVERLAY,
                "Stored controls from before the overlay gain its default key: " + older.keys());
        for (var layout : WorldControls.Layout.values())
            require(layout.keys().get(KeyCode.I) == WorldControls.Action.INSPECT_PIN,
                    layout + " binds I to the inspect card pin");
        var pins = new Host();
        var pinInput = exact(pins);
        pinInput.keyPressed(KeyCode.I);
        pinInput.keyPressed(KeyCode.I);
        require(pins.commands.equals(List.of(WorldControls.Action.INSPECT_PIN))
                        && pinInput.axes().equals(ExplorerMotion.Input.NONE),
                "I commands the card once per press and moves nothing: " + pins.commands);
        for (var layout : WorldControls.Layout.values())
            require(layout.keys().get(KeyCode.F12) == WorldControls.Action.SCREENSHOT,
                    layout + " binds F12 to the screenshot");
        var shots = new Host();
        var shotInput = exact(shots);
        shotInput.keyPressed(KeyCode.F12);
        shotInput.keyPressed(KeyCode.F12);
        require(shots.commands.equals(List.of(WorldControls.Action.SCREENSHOT))
                        && shotInput.axes().equals(ExplorerMotion.Input.NONE),
                "F12 commands one screenshot per press and moves nothing: " + shots.commands);
        for (var layout : WorldControls.Layout.values())
            require(layout.keys().get(KeyCode.B) == WorldControls.Action.BOOKMARKS,
                    layout + " binds B to the bookmarks page");
        var help = WorldControls.DEFAULTS.help();
        for (var part : List.of("M map", "H hides the HUD", "F3 developer overlay", "I pins the card",
                "F12 screenshot", "V NPC spawns", "L tracking", "N minimap", "T day/night",
                "1/2/3/4/5 speed", "B bookmarks"))
            require(help.contains(part), "Help names " + part + ": " + help);
        require(WorldControls.DEFAULTS.withKeys(WorldControls.Layout.AZERTY.keys()).help().contains("unbound speed"),
                "AZERTY help says the speed keys are unbound");
        require(WorldControls.DEFAULTS.help().contains("F3 developer overlay"),
                "Help names the overlay key: " + WorldControls.DEFAULTS.help());
    }

    private static void hudKeysDefaultWithoutClashes() {
        var movement = java.util.EnumSet.of(WorldControls.Action.FORWARD, WorldControls.Action.BACK,
                WorldControls.Action.LEFT, WorldControls.Action.RIGHT, WorldControls.Action.UP,
                WorldControls.Action.DOWN, WorldControls.Action.TURN_LEFT, WorldControls.Action.TURN_RIGHT,
                WorldControls.Action.LOOK_UP, WorldControls.Action.LOOK_DOWN, WorldControls.Action.SPRINT,
                WorldControls.Action.WALK, WorldControls.Action.FLY, WorldControls.Action.FIRST_PERSON);
        for (var layout : WorldControls.Layout.values()) {
            var keys = layout.keys();
            require(keys.get(KeyCode.V) == WorldControls.Action.SPAWNS
                            && keys.get(KeyCode.T) == WorldControls.Action.DAY_NIGHT
                            && keys.get(KeyCode.L) == WorldControls.Action.TRACKING
                            && keys.get(KeyCode.N) == WorldControls.Action.MINIMAP,
                    layout + " binds V, L, N and T: " + keys);
            var controls = WorldControls.DEFAULTS.withKeys(keys);
            var bound = controls.byAction();
            for (var action : movement)
                require(!bound.get(action).isEmpty(), layout + ": the new keys take no movement key from " + action);
            for (var action : WorldControls.Action.values())
                require(action.command != movement.contains(action),
                        "Every HUD key is a command and no movement key is: " + action);
            boolean digits = keys.containsKey(KeyCode.DIGIT1);
            require(digits == (layout == WorldControls.Layout.QWERTY),
                    layout + (digits ? " binds" : " leaves unbound") + " the speed digits");
        }
        var qwerty = WorldControls.Layout.QWERTY.keys();
        var speeds = List.of(KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5);
        for (int i = 0; i < speeds.size(); i++)
            require(qwerty.get(speeds.get(i)).speed() == WorldControls.Speed.values()[i],
                    "Digit " + (i + 1) + " selects " + WorldControls.Speed.values()[i]);
        require(WorldControls.Action.SPAWNS.speed() == null, "Only speed actions name a preset");
        var host = new Host();
        var input = exact(host);
        for (var code : List.of(KeyCode.V, KeyCode.T, KeyCode.DIGIT5)) {
            input.keyPressed(code);
            input.keyPressed(code);
            input.keyReleased(code);
        }
        require(host.commands.equals(List.of(WorldControls.Action.SPAWNS, WorldControls.Action.DAY_NIGHT,
                        WorldControls.Action.SPEED_TURBO)) && input.axes().equals(ExplorerMotion.Input.NONE),
                "Each HUD key commands once per press and moves nothing: " + host.commands);
        var repeat = new Host();
        var repeatInput = exact(repeat);
        repeatInput.keyPressed(KeyCode.L);
        repeatInput.keyPressed(KeyCode.W);
        repeatInput.clearMotion();
        repeatInput.keyPressed(KeyCode.L);
        require(repeat.commands.equals(List.of(WorldControls.Action.TRACKING))
                        && repeatInput.axes().equals(ExplorerMotion.Input.NONE),
                "clearMotion drops W but keeps L held, so its repeat does not fire again: " + repeat.commands);
        var stored = WorldControls.decode(WorldControls.DEFAULTS.unbind(WorldControls.Action.SPAWNS)
                .bind(KeyCode.K, WorldControls.Action.SPAWNS).encode());
        require(stored.keys().get(KeyCode.K) == WorldControls.Action.SPAWNS && !stored.keys().containsKey(KeyCode.V),
                "A rebound HUD key persists through the controls codec");
    }

    private static void clearDropsHeldKeys() {
        var input = exact(new Host());
        input.keyPressed(KeyCode.W);
        input.keyPressed(KeyCode.SHIFT);
        input.clear();
        require(!input.keysHeld() && input.axes().equals(ExplorerMotion.Input.NONE),
                "Clear drops every held key");
    }

    /** An 800 x 600 view at the screen origin: centre (400, 300), warp margins 200 / 150. */
    static final WorldExplorerInput.ViewBox VIEW = new WorldExplorerInput.ViewBox(0, 0, 800, 600);

    /** Records cursor warps; {@code works} false refuses them. Echo events are sent by tests. */
    static final class Pointer implements WorldExplorerInput.Pointer {
        final List<double[]> warps = new ArrayList<>();
        boolean works = true;

        @Override public boolean warp(double screenX, double screenY) {
            warps.add(new double[] {screenX, screenY});
            return works;
        }

        double[] last() {
            return warps.getLast();
        }
    }

    private static final double S = WorldControls.DEFAULT_SENSITIVITY;

    private static WorldExplorerInput looking(Host host, Pointer pointer, double x, double y) {
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.SECONDARY, x, y);
        return input;
    }

    private static void lookButtons() {
        require(WorldExplorerInput.looks(MouseButton.SECONDARY) && WorldExplorerInput.looks(MouseButton.PRIMARY),
                "Right and left buttons look");
        require(!WorldExplorerInput.looks(MouseButton.MIDDLE), "Middle button never looks");
        require(WorldExplorerInput.captures(MouseButton.SECONDARY) && !WorldExplorerInput.captures(MouseButton.PRIMARY),
                "Only the right button captures the cursor");
        var host = new Host();
        var input = exact(host);
        input.lookMoved(500, 500, VIEW);
        input.pulse(1_000_000_000L);
        require(host.camera.equals(START) && host.calls.isEmpty(), "Moves without a held look button never look");
        input.lookPressed(MouseButton.MIDDLE, 1, 1);
        require(!input.looking(), "Middle press starts nothing");
    }

    private static void capturedLookIsRelativeAwayFromTheEdge() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        require(input.looking() && host.cursor.equals(List.of(true)), "Right look hides the cursor");
        input.lookMoved(425, 290, VIEW);
        input.lookMoved(425, 290, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -25 * S) && near(host.camera.pitch(), 10 * S),
                "Right turns right and up looks up: " + host.camera);
        require(pointer.warps.isEmpty(), "No warp away from the edge");
        input.lookReleased(MouseButton.PRIMARY);
        require(input.looking(), "Releasing another button keeps the look");
        input.lookReleased(MouseButton.SECONDARY);
        require(!input.looking() && host.cursor.equals(List.of(true, false)) && pointer.warps.size() == 1
                        && pointer.last()[0] == 400 && pointer.last()[1] == 300,
                "Release shows the cursor where the look began, even without a warp");
        input.lookReleased();
        require(host.cursor.size() == 2, "A second release does nothing");
    }

    private static void nearTheEdgeItWarpsAndTheEchoIsNotCounted() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 130, 300);
        input.lookMoved(400, 300, VIEW);
        require(pointer.warps.isEmpty(), "Centre: no warp");
        input.lookMoved(650, 300, VIEW);
        require(pointer.warps.size() == 1 && pointer.last()[0] == 400 && pointer.last()[1] == 300,
                "Within a quarter of the edge the cursor goes back to the centre");
        input.lookMoved(400, 300, VIEW);
        input.lookMoved(405, 300, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -(270 + 250 + 5) * S), "The echo is not motion: " + host.camera.yaw() / -S);
        require(pointer.warps.size() == 1, "One warp, no loop");
        input.lookReleased();
        require(pointer.last()[0] == 130 && pointer.last()[1] == 300, "Release returns the cursor where it began");
        require(host.fallbacks.isEmpty(), "No fallback");
    }

    /**
     * 125 % / 175 % output scales: the robot truncates the target to an integer and the cursor
     * lands on a physical pixel, so the echo arrives at the centre plus a fraction. Counting from
     * the echo's own position keeps every real move exact.
     */
    private static void roundingOffsetEchoesNeverDriftOrLoop() {
        for (double offset : new double[] {.4, -.6, .8}) {
            var host = new Host();
            var pointer = new Pointer();
            var input = looking(host, pointer, 400, 300);
            double x = 400 + offset, counted = 0;
            input.lookMoved(x, 300, VIEW);
            counted += offset;
            double y = 300;
            for (int cycle = 0; cycle < 50; cycle++) {
                input.lookMoved(x + 250, y, VIEW);
                counted += 250;
                input.lookMoved(400 + offset, 300 + offset, VIEW);
                x = 400 + offset;
                y = 300 + offset;
                input.lookMoved(x + 3, 300 + offset, VIEW);
                counted += 3;
                x += 3;
            }
            input.pulse(1_000_000_000L);
            require(Math.abs(host.camera.yaw() / -S - counted) < 1e-6 && near(host.camera.pitch(), 0),
                    "Offset " + offset + ": no drift: " + host.camera.yaw() / -S + " vs " + counted);
            require(pointer.warps.size() == 50, "One warp per edge crossing: " + pointer.warps.size());
        }
    }

    private static void floatEchoOfADoubleTargetIsRecognised() {
        var host = new Host();
        var pointer = new Pointer();
        var view = new WorldExplorerInput.ViewBox(400.8 - 400, 300.8 - 300, 800, 600);
        var input = looking(host, pointer, view.centerX(), view.centerY());
        input.lookMoved(view.centerX() + 300, view.centerY(), view);
        require(pointer.warps.size() == 1, "Warped");
        input.lookMoved((float) view.centerX(), (float) view.centerY(), view);
        input.lookMoved((float) view.centerX() + 2, (float) view.centerY(), view);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -302 * S) && near(host.camera.pitch(), 0),
                "A float-rounded echo is still the echo: " + host.camera.yaw() / -S);
        require(pointer.warps.size() == 1, "No loop");
    }

    /** Events queued before the warp landed are real motion near the old position. */
    private static void motionBeforeTheEchoStillCounts() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        input.lookMoved(700, 300, VIEW);
        input.lookMoved(702, 301, VIEW);
        input.lookMoved(704, 302, VIEW);
        input.lookMoved(400, 300, VIEW);
        input.lookMoved(401, 300, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -(300 + 4 + 1) * S) && near(host.camera.pitch(), -2 * S),
                "Pre-echo motion counts, the echo does not: " + host.camera);
        require(pointer.warps.size() == 1, "One warp");
    }

    private static void slowOnePixelMotionAlwaysTurns() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        double x = 400;
        int counted = 0;
        for (int i = 0; i < 1000; i++) {
            x += 1;
            input.lookMoved(x, 300, VIEW);
            counted++;
            if (pointer.warps.size() > 0 && x > 500) {
                input.lookMoved(400, 300, VIEW);
                x = 400;
                pointer.warps.clear();
            }
        }
        input.pulse(1_000_000_000L);
        require(Math.abs(host.camera.yaw() / -S - counted) < 1e-6, "Every 1 px move turns: " + host.camera.yaw() / -S);
    }

    private static void aMissingEchoTimesOutAndRepeatedMissesStopWarping() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        double x = 650;
        input.lookMoved(x, 300, VIEW);
        for (int i = 0; i < WorldExplorerInput.ECHO_EVENTS; i++) input.lookMoved(x += 1, 300, VIEW);
        require(host.fallbacks.size() == 1, "A missing echo is reported: " + host.fallbacks);
        input.lookMoved(400, 300, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -(250 + WorldExplorerInput.ECHO_EVENTS) * S),
                "Waiting moves count; a late echo afterwards does not: " + host.camera.yaw() / -S);
        input.lookMoved(700, 300, VIEW);
        input.lookMoved(400, 300, VIEW);
        require(host.fallbacks.size() == 1, "An echo that does arrive is a success");
        // Misses in a row turn warping off.
        var other = new Host();
        var quiet = new Pointer();
        var second = looking(other, quiet, 400, 300);
        for (int miss = 1; miss <= WorldExplorerInput.CAPTURE_FAILURES; miss++) {
            double at = 700;
            second.lookMoved(at, 300, VIEW);
            for (int i = 0; i < WorldExplorerInput.ECHO_EVENTS; i++) second.lookMoved(at += 1, 300, VIEW);
            second.lookMoved(at - WorldExplorerInput.ECHO_EVENTS - 200, 300, VIEW);
        }
        require(other.fallbacks.size() == WorldExplorerInput.CAPTURE_FAILURES
                && other.fallbacks.getLast().contains("in a row") && other.unavailable == 1,
                "Capture stops warping and the view is told once: " + other.fallbacks);
        int warps = quiet.warps.size();
        require(warps == WorldExplorerInput.CAPTURE_FAILURES, "One warp per miss: " + warps);
        second.lookMoved(790, 300, VIEW);
        require(quiet.warps.size() == warps, "No more warps");
        second.lookReleased();
        require(quiet.warps.size() == warps, "Nothing restored once warping is off");
    }

    private static void aLateEchoResetsTheMissCount() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        for (int round = 0; round < 5; round++) {
            double at = 700;
            input.lookMoved(at, 300, VIEW);
            for (int i = 0; i < WorldExplorerInput.ECHO_EVENTS; i++) input.lookMoved(at += 1, 300, VIEW);
            input.lookMoved(400, 300, VIEW);
        }
        require(host.unavailable == 0 && host.fallbacks.stream().noneMatch(f -> f.contains("in a row")),
                "Late echoes are successes: five slow warps never turn capture off: " + host.fallbacks);
    }

    /**
     * The platform may merge the echo with the next real motion (target + m). It is still the
     * echo, and at most that event's own motion m is lost, once per warp.
     */
    private static void anEchoCoalescedWithMotionLosesAtMostThatMotion() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        input.lookMoved(650, 300, VIEW);
        input.lookMoved(403, 302, VIEW);
        input.lookMoved(410, 302, VIEW);
        input.pulse(1_000_000_000L);
        double counted = host.camera.yaw() / -S;
        require(Math.abs(counted - (250 + 7)) < 1e-9, "Echo + 3 px taken as the echo; later motion counts: " + counted);
        require(250 + 3 + 7 - counted <= 3 + 1e-9, "At most the coalesced 3 px are lost");
        require(pointer.warps.size() == 1, "No re-warp");
    }

    /**
     * Before the echo, a single real event that jumps more than halfway towards the warp target
     * is indistinguishable from the echo: it becomes the origin and its own motion is lost. That
     * is bounded by one event per warp; the next events count again.
     */
    private static void fastPreEchoMotionIsMisreadAtMostOncePerWarp() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        input.lookMoved(650, 300, VIEW);
        input.lookMoved(520, 300, VIEW);
        input.lookMoved(500, 300, VIEW);
        input.lookMoved(480, 300, VIEW);
        input.pulse(1_000_000_000L);
        double counted = host.camera.yaw() / -S;
        require(Math.abs(counted - (250 - 40)) < 1e-9, "Only the one jump (-130) is lost: " + counted);
    }

    /** A window hanging past the screen's right edge: the warp band and centre stay on screen. */
    private static void aViewPastTheScreenEdgeStillWarpsOnScreen() {
        var view = new WorldExplorerInput.ViewBox(1500, 0, 800, 600);
        var clipped = view.clippedTo(0, 0, 1920, 1080);
        require(clipped.minX() == 1500 && clipped.width() == 420 && clipped.height() == 600,
                "Clipped to the visible part: " + clipped);
        require(view.clippedTo(3000, 0, 4000, 1000) == view, "A box off that screen is kept");
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 1700, 300);
        // The cursor stops at the screen edge (1919): the unclipped band (> 2100) is unreachable.
        input.lookMoved(1919, 300, view);
        require(pointer.warps.isEmpty(), "Unclipped: no warp, turning would stop at the edge");
        input.lookMoved(1919, 300, clipped);
        input.lookMoved(1918, 300, clipped);
        require(pointer.warps.size() == 1 && pointer.last()[0] == 1710 && pointer.last()[0] < 1920,
                "Clipped: the cursor goes back to the on-screen centre: " + pointer.last()[0]);
    }

    private static void aRefusedWarpStopsWarping() {
        var host = new Host();
        var pointer = new Pointer();
        pointer.works = false;
        var input = looking(host, pointer, 400, 300);
        input.lookMoved(700, 300, VIEW);
        input.lookMoved(720, 300, VIEW);
        input.lookMoved(740, 300, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -340 * S), "Relative after a refusal");
        require(pointer.warps.size() == 1 && host.fallbacks.size() == 1 && host.unavailable == 1,
                "Refused once, reported once, shown in the view");
    }

    private static void theWindowMovingMidLookWarpsToTheNewCentre() {
        var host = new Host();
        var pointer = new Pointer();
        var input = looking(host, pointer, 400, 300);
        var moved = new WorldExplorerInput.ViewBox(100, 50, 800, 600);
        input.lookMoved(410, 300, moved);
        input.lookMoved(820, 300, moved);
        require(pointer.last()[0] == 500 && pointer.last()[1] == 350, "Warped to the moved view's centre");
        input.lookMoved(500, 350, moved);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -420 * S), "Counted from real positions: " + host.camera.yaw() / -S);
    }

    private static void leftDragLooksWithoutCapture() {
        var host = new Host();
        var pointer = new Pointer();
        var input = exact(host);
        input.pointer(pointer);
        input.lookPressed(MouseButton.PRIMARY, 50, 50);
        require(input.looking() && host.cursor.isEmpty(), "Left look never hides the cursor");
        input.lookPressed(MouseButton.SECONDARY, 50, 50);
        input.lookMoved(75, 40, VIEW);
        input.lookMoved(5, 40, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), 45 * S) && near(host.camera.pitch(), 10 * S),
                "Left drag looks with relative movement: " + host.camera);
        require(pointer.warps.isEmpty(), "Left look never warps, even at the edge");
        input.lookReleased(MouseButton.SECONDARY);
        require(input.looking(), "Only the button that started the look ends it");
        input.lookReleased(MouseButton.PRIMARY);
        require(!input.looking() && pointer.warps.isEmpty() && host.cursor.isEmpty(), "Release restores nothing");
    }

    private static void lookIsCoalescedToOneCameraUpdatePerPulse() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.lookPressed(MouseButton.SECONDARY, 400, 300);
        for (int i = 1; i <= 50; i++) input.lookMoved(400 + i, 300, VIEW);
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
        input.lookMoved(390, 300, VIEW);
        input.pulse(1_048_000_000L);
        require(host.count("frame") == 1, "A look frames even inside the cadence: " + host.calls);
    }

    private static void sensitivityAndInvertYApply() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.controls(WorldControls.DEFAULTS.withSensitivity(.01).withInvertY(true));
        input.lookPressed(MouseButton.SECONDARY, 400, 300);
        input.lookMoved(410, 280, VIEW);
        input.pulse(1_000_000_000L);
        require(near(host.camera.yaw(), -.1) && near(host.camera.pitch(), -.2),
                "Sensitivity scales and invert-Y flips pitch only: " + host.camera);
    }

    private static void clearEndsTheLookAndDropsPendingMovement() {
        var host = new Host();
        var input = exact(host);
        input.pointer(new Pointer());
        input.lookPressed(MouseButton.SECONDARY, 400, 300);
        input.lookMoved(450, 300, VIEW);
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
        input.lookPressed(MouseButton.PRIMARY, 0, 0);
        host.camera = null;
        input.lookMoved(300, 0, VIEW);
        input.pulse(1_000_000_000L);
        host.camera = START;
        input.pulse(1_016_000_000L);
        require(host.camera.equals(START), "Owed while there was no camera: dropped");
        input.lookMoved(600, 0, VIEW);
        input.idle();
        input.pulse(1_032_000_000L);
        require(host.camera.equals(START), "Owed across a timer stop (idle): dropped");
        input.lookMoved(601, 0, VIEW);
        input.pulse(1_048_000_000L);
        require(near(host.camera.yaw(), -WorldControls.DEFAULT_SENSITIVITY), "Later moves still look");
    }

    private static void wheelReadyCoversStreamingMode() {
        require(WorldExplorerInput.wheelReady(true, false, false), "Legacy scene");
        require(WorldExplorerInput.wheelReady(false, true, true), "GPU streaming with a controller");
        require(!WorldExplorerInput.wheelReady(false, true, false), "Streaming without a controller");
        require(!WorldExplorerInput.wheelReady(false, false, true), "No scene outside streaming");
    }

    private static void viewDistanceIsRememberedWithTheControls() {
        var near = com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.NEAR;
        var far = com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.FAR;
        require(WorldControls.DEFAULTS.viewDistance() == near, "NEAR is the default view distance");
        var chosen = WorldControls.DEFAULTS.withSpeed(WorldControls.Speed.TURBO).withViewDistance(far);
        require(WorldControls.decode(chosen.encode()).equals(chosen), "FAR round-trips: " + chosen.encode());
        require(chosen.withInvertY(true).withSensitivity(.01).withKeys(WorldControls.Layout.AZERTY.keys())
                        .viewDistance() == far, "Other edits keep the view distance");
        require(WorldControls.decode("v1;sensitivity=0.01;speed=TURBO").viewDistance() == near,
                "A value saved before the setting existed opens NEAR");
        require(WorldControls.decode("v1;view=HUGE").viewDistance() == near
                        && WorldControls.decode("v1;view=").viewDistance() == near,
                "An unknown view distance opens NEAR");
        require(new WorldControls(.006, false, null, java.util.Map.of(), null).viewDistance() == near,
                "A null view distance is NEAR");
    }

    private static void renderScaleIsRememberedWithTheControls() {
        var full = WorldControls.RenderScale.FULL;
        var low = WorldControls.RenderScale.LOW;
        require(WorldControls.DEFAULTS.renderScale() == full, "Native (100%) is the default render scale");
        var chosen = WorldControls.DEFAULTS.withSpeed(WorldControls.Speed.TURBO).withRenderScale(low)
                .withViewDistance(com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.FAR);
        require(WorldControls.decode(chosen.encode()).equals(chosen), "50% round-trips: " + chosen.encode());
        require(chosen.withInvertY(true).withSensitivity(.01).withKeys(WorldControls.Layout.AZERTY.keys())
                        .withViewDistance(com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.NEAR)
                        .renderScale() == low, "Other edits keep the render scale");
        require(WorldControls.decode("v1;sensitivity=0.01;view=FAR").renderScale() == full,
                "A value saved before the setting existed opens native");
        require(WorldControls.decode("v1;render=TINY").renderScale() == full
                        && WorldControls.decode("v1;render=").renderScale() == full,
                "An unknown render scale opens native");
        require(new WorldControls(.006, false, null, java.util.Map.of(), null, null).renderScale() == full,
                "A null render scale is native");
        // Every choice is inside the shared frame limits' range, so none is clamped to another.
        for (var scale : WorldControls.RenderScale.values())
            require(scale.factor() >= com.vibeminer.rendering.world.WorldFrameLimits.MIN_RENDER_SCALE
                    && scale.factor() <= 1, "In range: " + scale);
        require(com.vibeminer.rendering.world.WorldFrameLimits.frame(1000, 600, 1.5, 1.5, low.factor())
                        .equals(new com.vibeminer.rendering.world.WorldFrameLimits.Size(750, 450)),
                "50% of a 1000x600 viewport at 150% display scaling is 750x450");
        require(full.toString().equals("Render scale: 100% (native)")
                        && WorldControls.RenderScale.MEDIUM.toString().equals("Render scale: 70%"),
                "The choices name their percentages");
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
        require(store.load().join() == null, "An empty store loads nothing");
        store.save(custom.encode());
        require(WorldControls.decode(store.load().join()).equals(custom), "The memory store keeps the value");
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
        input.scroll(40, true, false);
        require(input.speed() == 60 && host.changed.getLast().speed() == WorldControls.Speed.FAST,
                "Shift+wheel up steps to the next preset and reports it");
        require(host.camera.equals(START), "Shift+wheel never moves");
        for (int i = 0; i < 10; i++) input.scroll(40, true, false);
        require(input.speed() == 200 && host.changed.size() == 2, "Turbo is the top; no further reports");
        for (int i = 0; i < 10; i++) input.scroll(-40, true, false);
        require(input.speed() == 7, "Stroll is the slowest preset");
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

    private static void theSpeedWheelFollowsTheSprintBinding() {
        var host = new Host();
        var input = exact(host);
        input.controls(WorldControls.DEFAULTS.unbind(WorldControls.Action.SPRINT)
                .bind(KeyCode.CONTROL, WorldControls.Action.SPRINT));
        input.scroll(40, false, true);
        require(input.controls().speed() == WorldControls.Speed.FAST && host.camera.equals(START),
                "Control+wheel steps speed when sprint is Control");
        input.scroll(40, true, false);
        require(input.controls().speed() == WorldControls.Speed.FAST && !host.camera.equals(START),
                "Shift+wheel dollies when Shift no longer sprints");
        host.camera = START;
        input.controls(WorldControls.DEFAULTS.unbind(WorldControls.Action.SPRINT)
                .bind(KeyCode.G, WorldControls.Action.SPRINT));
        require(input.controls().sprintModifier() == null, "A letter is no modifier");
        input.keyPressed(KeyCode.G);
        input.scroll(-40, false, false);
        require(input.controls().speed() == WorldControls.Speed.MOUNT && host.camera.equals(START),
                "Holding a non-modifier sprint key turns the wheel into speed");
        input.keyReleased(KeyCode.G);
        input.scroll(40, true, false);
        require(input.controls().speed() == WorldControls.Speed.MOUNT, "Shift alone is just Shift now");
    }

    private static void storedBindingsFallBackAndGainNewActions() {
        require(WorldControls.decode("v1;keys=NOPE:FORWARD,BAD").keys().equals(WorldControls.DEFAULTS.keys()),
                "A stored list with no usable binding falls back to the defaults");
        var older = WorldControls.decode("v1;keys=I:FORWARD");
        require(older.keys().get(KeyCode.I) == WorldControls.Action.FORWARD
                        && older.keys().get(KeyCode.W) == null
                        && older.keys().get(KeyCode.SHIFT) == WorldControls.Action.SPRINT
                        && older.keys().get(KeyCode.F) == WorldControls.Action.WALK,
                "An older value keeps its bindings; actions it never bound get their defaults: " + older.keys());
        var cleared = WorldControls.DEFAULTS.unbind(WorldControls.Action.SPRINT);
        require(WorldControls.decode(cleared.encode()).equals(cleared),
                "An action the user cleared stays cleared (listed as known)");
        var futureLess = WorldControls.decode("v1;keys=W:FORWARD,S:SPRINT;actions=FORWARD,BACK,SPRINT");
        require(futureLess.keys().get(KeyCode.S) == WorldControls.Action.SPRINT
                        && futureLess.byAction().get(WorldControls.Action.BACK).isEmpty()
                        && futureLess.keys().get(KeyCode.A) == WorldControls.Action.LEFT,
                "Known actions keep their stored keys (even none); unknown ones get defaults whose keys are free: "
                        + futureLess.keys());
        require(WorldControls.Speed.STROLL.label.equals("Stroll"), "The 7 yd/s preset is Stroll");
    }

    private static void helpFollowsTheBindings() {
        var help = WorldControls.DEFAULTS.help();
        require(help.startsWith("W A S D move") && help.contains("Shift+wheel changes speed")
                && help.contains("F next mode (free camera, walk, fly)") && help.contains("G walk or fly"),
                "Default help: " + help);
        var azerty = WorldControls.DEFAULTS.withKeys(WorldControls.Layout.AZERTY.keys()).help();
        require(azerty.startsWith("Z Q S D move") && azerty.contains("A/X down"), "AZERTY help: " + azerty);
        var control = WorldControls.DEFAULTS.unbind(WorldControls.Action.SPRINT)
                .bind(KeyCode.CONTROL, WorldControls.Action.SPRINT).unbind(WorldControls.Action.WALK).help();
        require(control.contains("Ctrl+wheel changes speed") && control.contains("unbound next mode"),
                "Rebound help: " + control);
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
                        && lenient.keys().get(KeyCode.W) == WorldControls.Action.FORWARD
                        && !lenient.keys().containsKey(KeyCode.ESCAPE)
                        && lenient.byAction().get(WorldControls.Action.UP).isEmpty()
                        && lenient.keys().get(KeyCode.S) == WorldControls.Action.BACK,
                "Unknown speeds, key codes, actions and reserved keys are dropped; actions with no"
                        + " readable stored key get defaults: " + lenient);
        require(WorldControls.decode(WorldControls.DEFAULTS.withKeys(java.util.Map.of()).encode()).keys().isEmpty(),
                "Every binding can be cleared and stays cleared");
    }

    private static void wheelDollysAlongTheViewAndIgnoresBadInput() {
        var host = new Host();
        var input = exact(host);
        host.camera = new TerrainRenderer.Camera(100, 200, 50, .7, -.8);
        var before = host.camera;
        input.scroll(40, false, false);
        double dx = host.camera.x() - before.x(), dy = host.camera.y() - before.y(),
                dz = host.camera.z() - before.z();
        require(dx > 0 && dy > 0 && dz < 0, "Wheel moves forward along the pitched view");
        // One notch at the default 20 yd/s is clamp(20 * 0.25, 2, 32) = 5 yards.
        require(Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - 5) < 1e-9, "One notch is 5 yards");
        require(host.calls.equals(List.of("ground:FREE", "abandon", "frame", "nearby")),
                "Wheel reports ground, then abandons, frames and loads: " + host.calls);
        input.scroll(-40, false, false);
        require(Math.abs(host.camera.x() - before.x()) < 1e-9
                && Math.abs(host.camera.z() - before.z()) < 1e-9, "Reverse wheel restores");
        var settled = host.camera;
        host.calls.clear();
        input.scroll(Double.NaN, false, false);
        input.scroll(0, false, false);
        host.wheelReady = false;
        input.scroll(40, false, false);
        require(host.camera.equals(settled) && host.calls.isEmpty(),
                "Non-finite, zero or unready wheel input is ignored");
        host.wheelReady = true;
        host.camera = new TerrainRenderer.Camera(1_000_000, 1_000_000, 1_000_000, .7, .8);
        input.scroll(Double.MAX_VALUE, false, false);
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

    /**
     * Walking places the body on the floor under the camera on the first pulse, reports its status
     * every pulse, and the follow camera sits behind it; held keys move it at the run speed, and a
     * camera moved by someone else (a jump) places it again.
     */
    private static void walkingPlacesTheBodyAndTheCameraFollowsIt() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(WorldExplorerInput.Mode.WALK, true);
        input.holdCadence();
        long t = 1_000_000_000L;
        input.pulse(t);
        var walker = input.body().body();
        require(walker.grounded() && Math.abs(walker.z() - 40) < .02, "Stands on the floor: " + walker.z());
        require(Math.abs(host.camera.x() - (START.x() - MovementParity.CAMERA_START_DISTANCE)) < 1e-6,
                "The camera follows behind: " + host.camera);
        require(host.statuses.equals(List.of("WALK:STANDING")), "Status every pulse: " + host.statuses);
        input.keyPressed(KeyCode.W);
        for (int i = 0; i < 60; i++) input.pulse(t += 16_666_667L);
        input.keyReleased(KeyCode.W);
        require(Math.abs(walker.x() - START.x() - MovementParity.RUN_SPEED) < .2,
                "A second at the run speed, no smoothing: " + (walker.x() - START.x()));
        require(host.statuses.getLast().equals("WALK:MOVING"), "Moving: " + host.statuses.getLast());
        require(input.effectiveSpeed() == MovementParity.RUN_SPEED, "Run speed on the speed chip");
        host.camera = new TerrainRenderer.Camera(500, 600, 90, 1, 0);
        input.pulse(t += 16_666_667L);
        require(Math.abs(walker.x() - 500) < 1e-6 && Math.abs(walker.y() - 600) < 1e-6,
                "A jump places the body again: " + walker.x() + ", " + walker.y());
        // Leaving and re-entering a body mode places the body under the free camera again.
        input.motion(WorldExplorerInput.Mode.FREE, true);
        host.camera = new TerrainRenderer.Camera(-300, 0, 70, 0, 0);
        input.motion(WorldExplorerInput.Mode.WALK, true);
        input.pulse(t += 16_666_667L);
        require(Math.abs(walker.x() + 300) < 1e-6, "Re-entering places the body: " + walker.x());
        // Without collision data the body waits.
        host.collision = CollisionWorld.NONE;
        input.motion(WorldExplorerInput.Mode.FREE, true);
        input.motion(WorldExplorerInput.Mode.WALK, true);
        input.pulse(t += 16_666_667L);
        input.pulse(t += 16_666_667L);
        require(host.statuses.getLast().equals("WALK:LOADING"), "Waits for collision: " + host.statuses.getLast());
    }

    /**
     * With a drawn body, standing still keeps requesting frames at the movement cadence so its
     * idle plays; running forward plays Run and backpedalling the walk (backwards).
     */
    private static void aDrawnBodyPlaysItsSequencesAndKeepsFramingWhileItStands() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(WorldExplorerInput.Mode.WALK, true);
        var animator = new com.vibeminer.rendering.world.BodyAnimator(
                java.util.Map.of(0, 1333L, 4, 534L, 5, 533L), java.util.Map.of(4, 2.5f, 5, 8.333333f));
        input.animator(animator);
        long t = 1_000_000_000L;
        input.pulse(t);
        long before = host.count("frame");
        // 400 ms of 5 ms pulses: the idle cadence (80 ms) frames about five times, not 25.
        for (int i = 0; i < 80; i++) input.pulse(t += 5_000_000L);
        long idleFrames = host.count("frame") - before;
        require(idleFrames >= 4 && idleFrames <= 6, "Standing still frames the idle slowly: " + idleFrames);
        require(animator.sequence() == 0 && animator.millis() > 100, "Stand plays: " + animator.millis());
        // Unfocused, or in first person (the body is hidden), standing still frames nothing.
        host.idle = false;
        before = host.count("frame");
        for (int i = 0; i < 80; i++) input.pulse(t += 5_000_000L);
        require(host.count("frame") == before, "No idle frames while unfocused: " + host.calls);
        host.idle = true;
        input.body().camera().distance(0);
        for (int i = 0; i < 4; i++) input.pulse(t += 5_000_000L);
        before = host.count("frame");
        for (int i = 0; i < 80; i++) input.pulse(t += 5_000_000L);
        require(host.count("frame") == before, "No idle frames in first person: " + host.calls);
        input.body().camera().distance(MovementParity.CAMERA_START_DISTANCE);
        input.keyPressed(KeyCode.W);
        for (int i = 0; i < 10; i++) input.pulse(t += 16_666_667L);
        require(animator.sequence() == 5, "Running forward plays Run: " + animator.sequence());
        input.keyReleased(KeyCode.W);
        input.keyPressed(KeyCode.S);
        for (int i = 0; i < 10; i++) input.pulse(t += 16_666_667L);
        require(animator.sequence() == 4, "Backpedalling plays the walk: " + animator.sequence());
        input.keyReleased(KeyCode.S);
        // Without a drawn body nothing frames a body that stands still.
        var plain = new Host();
        var still = new WorldExplorerInput(plain);
        still.motion(WorldExplorerInput.Mode.WALK, true);
        still.pulse(t);
        long once = plain.count("frame");
        for (int i = 0; i < 30; i++) still.pulse(t += 5_000_000L);
        require(plain.count("frame") == once, "No body, no idle frames: " + plain.calls);
    }

    /** F toggles walking, G walking or flying, and C first person, once per press. */
    private static void walkFlyAndFirstPersonKeysReachTheirHooks() {
        var host = new Host();
        var input = exact(host);
        input.keyPressed(KeyCode.G);
        input.keyPressed(KeyCode.G);
        input.keyReleased(KeyCode.G);
        require(host.flyToggles == 1 && host.toggles == 0, "G toggles flying once per press");
        input.motion(WorldExplorerInput.Mode.WALK, false);
        input.keyPressed(KeyCode.C);
        input.keyReleased(KeyCode.C);
        require(input.body().camera().firstPerson(), "C is first person");
        input.body().zoom(0);
        input.keyPressed(KeyCode.C);
        input.keyReleased(KeyCode.C);
        require(input.body().camera().distance() == MovementParity.CAMERA_START_DISTANCE,
                "C again returns to the follow distance: " + input.body().camera().distance());
        require(host.commands.isEmpty(), "Walk, fly and first person are not HUD commands");
        input.motion(WorldExplorerInput.Mode.FLY, false);
        require(input.body().mode() == WalkController.Mode.FLY, "Fly mode flies the body");
        input.motion(WorldExplorerInput.Mode.FREE, false);
        input.keyPressed(KeyCode.C);
        require(input.body().camera().distance() == MovementParity.CAMERA_START_DISTANCE,
                "C does nothing for the free camera");
    }

    /** A flying body goes at least taxi speed; faster presets and sprint take it beyond. */
    private static void flyingWithABodyGoesAtLeastTaxiSpeed() {
        var host = new Host();
        var input = exact(host);
        input.motion(WorldExplorerInput.Mode.FLY, false);
        input.controls(WorldControls.DEFAULTS.withSpeed(WorldControls.Speed.STROLL));
        require(input.flySpeed() == MovementParity.TAXI_SPEED && input.effectiveSpeed() == MovementParity.TAXI_SPEED,
                "Slow presets fly at taxi speed: " + input.flySpeed());
        input.controls(WorldControls.DEFAULTS.withSpeed(WorldControls.Speed.TURBO));
        require(input.flySpeed() == 200, "Turbo flies at 200: " + input.flySpeed());
        input.keyPressed(KeyCode.SHIFT);
        require(input.flySpeed() == 400, "Sprint doubles it: " + input.flySpeed());
        input.keyReleased(KeyCode.SHIFT);
        input.holdCadence();
        long t = 1_000_000_000L;
        input.pulse(t);
        double z = input.body().body().z();
        for (int i = 0; i < 30; i++) input.pulse(t += 16_666_667L);
        require(input.body().body().z() == z && host.statuses.getLast().equals("FLY:FLYING"),
                "No gravity while flying: " + host.statuses.getLast());
        input.keyPressed(KeyCode.W);
        for (int i = 0; i < 6; i++) input.pulse(t += 16_666_667L);
        require(Math.abs(input.body().body().x() - START.x() - 20) < .5,
                "A tenth of a second at 200 yd/s: " + (input.body().body().x() - START.x()));
    }

    /**
     * Smoothed flight keeps moving after release until the velocity decays, and the camera's
     * final position is always framed, even when the movement cadence would hold it back.
     */
    private static void smoothReleaseCoastsThenFramesTheRestingStep() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(WorldExplorerInput.Mode.FREE, true);
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
     * #528: the end of a movement is reported once, on the pulse the camera stops (released
     * flight, smoothed coasting decaying, or a walk), never while moving, never again at rest,
     * and never for a look alone.
     */
    private static void theCameraComingToRestIsReportedOncePerStop() {
        long[] t = {1_000_000_000L};
        for (int mode = 0; mode < 3; mode++) {
            var host = new Host();
            var input = new WorldExplorerInput(host);
            input.motion(mode == 2 ? WorldExplorerInput.Mode.WALK : WorldExplorerInput.Mode.FREE, mode == 1);
            String label = new String[] {"flight", "smooth flight", "walk"}[mode];
            input.pulse(t[0] += 16_000_000L);
            require(host.rests == 0, label + ": a camera that never moved is not a stop");
            for (int stop = 1; stop <= 2; stop++) {
                input.keyPressed(KeyCode.W);
                for (int i = 0; i < 20; i++) input.pulse(t[0] += 16_000_000L);
                require(host.rests == stop - 1, label + ": no stop while moving: " + host.rests);
                input.keyReleased(KeyCode.W);
                var before = host.camera;
                int pulses = 0;
                while (host.rests < stop && pulses++ < 1000) input.pulse(t[0] += 16_000_000L);
                require(host.rests == stop, label + ": the stop is reported");
                var rested = host.camera;
                if (mode == 1) require(rested.x() != before.x() || rested.y() != before.y(),
                        label + ": reported only after coasting ends");
                for (int i = 0; i < 20; i++) input.pulse(t[0] += 16_000_000L);
                require(host.rests == stop && host.camera.equals(rested),
                        label + ": at rest it is not reported again: " + host.rests);
            }
            input.pointer(new Pointer());
            input.lookPressed(MouseButton.SECONDARY, 400, 300);
            for (int i = 1; i <= 20; i++) input.lookMoved(400 + i, 300, VIEW);
            input.pulse(t[0] += 16_000_000L);
            input.pulse(t[0] += 16_000_000L);
            require(host.rests == 2, label + ": a look alone is not a stop: " + host.rests);
        }
    }

    /**
     * With a body the wheel zooms the follow camera (toward the view zooms in) by whole notches
     * of {@link WorldExplorerInput#ZOOM_YARDS}, down to first person; it never moves the camera
     * itself.
     */
    private static void bodyWheelZoomsTheFollowCamera() {
        var host = new Host();
        var input = new WorldExplorerInput(host);
        input.motion(WorldExplorerInput.Mode.WALK, false);
        double start = input.body().camera().distance();
        input.scroll(40, false, false);
        require(host.camera.equals(START), "The wheel does not move the camera itself");
        require(Math.abs(input.body().camera().distance() - (start - WorldExplorerInput.ZOOM_YARDS)) < 1e-9,
                "One notch in: " + input.body().camera().distance());
        input.scroll(-80, false, false);
        require(Math.abs(input.body().camera().distance() - (start + WorldExplorerInput.ZOOM_YARDS)) < 1e-9,
                "Two notches out");
        for (int i = 0; i < 40; i++) input.scroll(120, false, false);
        require(input.body().camera().firstPerson(), "All the way in is first person");
        for (int i = 0; i < 40; i++) input.scroll(-120, false, false);
        require(input.body().camera().distance() == FollowCamera.MAX_DISTANCE, "And out to the farthest");
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
