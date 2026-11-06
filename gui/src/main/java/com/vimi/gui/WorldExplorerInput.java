package com.vimi.gui;

import com.vibeminer.rendering.world.ExplorerMotion;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

/**
 * Explorer keyboard, mouse-look, wheel and per-pulse motion state, separated from {@link WorldView}
 * so the input mapping is testable without a JavaFX toolkit. It holds held keys, the mouse-look
 * capture, flight speed and the {@link ExplorerMotion} integrator; the view forwards raw event
 * values and supplies the camera, ground and frame/discovery hooks through {@link Host}.
 *
 * <p>Mouse look follows the game client. Holding the right button captures the cursor: it is
 * hidden, every move counts as a plain relative delta between mouse events, and only when it
 * nears the edge of the view is it warped back to the centre so it never leaves; on release it
 * returns to where the look began. The warp's own move event (its echo) is recognised from the
 * events themselves, never from a cursor read-back, so platform rounding of the warp target
 * (fractional output scales, integer robot coordinates) cannot bias the measurement. Holding the left button looks without
 * capture (the cursor stays visible and moves), which suits touchpads; a left click without a drag
 * still inspects. Movement is summed between pulses and applied once per pulse, so a burst of
 * mouse events costs one camera update and at most one frame request. Movement owed while no
 * pulse runs is dropped, never applied later as one jump.
 */
final class WorldExplorerInput {
    /** View hooks; every method is called on the FX thread from an input event or pulse. */
    interface Host {
        TerrainRenderer.Camera camera();

        void camera(TerrainRenderer.Camera next);

        TerrainGround ground();

        /** True when the wheel may dolly; see {@link WorldExplorerInput#wheelReady}. */
        boolean wheelReady();

        /** True when frames are cheap enough for the 16 ms movement cadence. */
        boolean fastCadence();

        void groundStatus(ExplorerMotion.Ground ground);

        void toggleWalk();

        /**
         * A HUD command key was pressed ({@link WorldControls.Action#command}): once per physical
         * press. The view routes it to the HUD piece that owns the action.
         */
        default void command(WorldControls.Action action) {}

        void requestFrame();

        void abandonUnusableCandidate();

        void loadNext();

        /** Hides the cursor over the view while mouse look is held; shows it again after. */
        void cursorHidden(boolean hidden);

        /** The input changed its own controls (Shift+wheel speed); show and persist them. */
        void controlsChanged(WorldControls controls);
        /** Cursor capture fell back to relative movement; the view logs the first one. */
        void captureFallback(String reason);

        /** Warping is off for good: the view says so in its controls. */
        void captureUnavailable();
    }

    /** Moves the system cursor; false when this platform cannot place it (e.g. no robot). */
    @FunctionalInterface
    interface Pointer {
        boolean warp(double screenX, double screenY);

        /** No cursor placement: a captured look then stays purely relative. */
        Pointer NONE = (x, y) -> false;
    }

    /** The view's rectangle in screen units, at the time of an event (the window may move). */
    record ViewBox(double minX, double minY, double width, double height) {
        double centerX() {
            return minX + width / 2;
        }

        double centerY() {
            return minY + height / 2;
        }

        /**
         * The part of this box on a screen's visible area ({@code min}/{@code max} corners), so a
         * view hanging past a monitor edge still has a reachable warp band and an on-screen
         * centre. A box entirely off that area is returned unchanged.
         */
        ViewBox clippedTo(double screenMinX, double screenMinY, double screenMaxX, double screenMaxY) {
            double x0 = Math.max(minX, screenMinX), y0 = Math.max(minY, screenMinY);
            double x1 = Math.min(minX + width, screenMaxX), y1 = Math.min(minY + height, screenMaxY);
            return x1 > x0 && y1 > y0 ? new ViewBox(x0, y0, x1 - x0, y1 - y0) : this;
        }

        /** True when the point lies within {@code EDGE} of the box size from an edge (or outside). */
        boolean nearEdge(double x, double y) {
            double mx = width * EDGE, my = height * EDGE;
            return x < minX + mx || x > minX + width - mx || y < minY + my || y > minY + height - my;
        }
    }

    /** A captured look warps back to the centre only within this fraction of the view of an edge. */
    static final double EDGE = .25;

    /**
     * Events after a warp that may still predate its echo; without an echo by then the warp is
     * taken as failed and the next event becomes the new origin, uncounted.
     */
    static final int ECHO_EVENTS = 8;

    /** How close to a given-up warp target a late echo lands (screen units). */
    static final double LATE_ECHO = 2;

    /** Consecutive warps without an echo before capture stops warping for good. */
    static final int CAPTURE_FAILURES = 3;

    /**
     * Whether the plain wheel may dolly: on the legacy CPU path a terrain scene is loaded; in
     * GPU streaming mode a stream controller (and its ground) is present.
     */
    static boolean wheelReady(boolean legacyScene, boolean streamingMode, boolean streamPresent) {
        return legacyScene || streamingMode && streamPresent;
    }

    /** Wheel dolly per notch, as seconds of travel at the current speed. */
    static final double DOLLY_SECONDS = .25;

    static final long FAST_CADENCE_NANOS = 16_000_000,
            SLOW_CADENCE_NANOS = 100_000_000,
            NEARBY_NANOS = 100_000_000;

    private final Host host;
    private final ExplorerMotion motion = new ExplorerMotion();
    private final Set<KeyCode> keys = new HashSet<>();
    private long lastTick, lastRender, lastNearby;
    private WorldControls controls = WorldControls.DEFAULTS;
    private Pointer pointer = Pointer.NONE;
    private boolean looking, captureOff, awaitingEcho, lateEcho;
    private MouseButton lookButton;
    private int captureFailures, echoWait;
    // Last event position (deltas are measured from it), where the look began, the pending warp
    // target, and pixels owed to the next pulse.
    private double lookX, lookY, anchorX, anchorY, targetX, targetY, pendingX, pendingY;
    private boolean warpedOnce;
    // The camera moved since the last frame this input requested (cadence held it back).
    private boolean unshown;

    WorldExplorerInput(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    /** Applies the walk and smoothing toggles; drops retained velocity. */
    void motion(boolean walk, boolean smooth) {
        motion.mode(walk ? ExplorerMotion.Mode.WALK : ExplorerMotion.Mode.FLY);
        motion.smooth(smooth);
    }

    WorldControls controls() {
        return controls;
    }

    void controls(WorldControls next) {
        controls = Objects.requireNonNull(next);
    }

    void pointer(Pointer next) {
        pointer = Objects.requireNonNull(next);
    }

    /** Flight speed of the current preset in yards per second. */
    double speed() {
        return controls.speed().yardsPerSecond;
    }

    /** Effective speed for the status line: the current mode with the held sprint key. */
    double effectiveSpeed() {
        return motion.speed(speed(), held(WorldControls.Action.SPRINT));
    }

    /** True while the sprint key is held (the HUD speed chip brightens). */
    boolean sprinting() {
        return held(WorldControls.Action.SPRINT);
    }

    /** The held keys' names, sorted, comma-separated; for the developer overlay. */
    String heldKeys() {
        return keys.stream().map(KeyCode::getName).sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    boolean keysHeld() {
        return !keys.isEmpty();
    }

    /**
     * The walk toggle and HUD commands fire once per physical press of their key; key repeat is
     * ignored.
     */
    void keyPressed(KeyCode code) {
        boolean fresh = keys.add(code);
        if (!fresh) return;
        var action = controls.keys().get(code);
        if (action == WorldControls.Action.WALK) host.toggleWalk();
        else if (action != null && action.command) host.command(action);
    }

    void keyReleased(KeyCode code) {
        keys.remove(code);
    }

    /** Drops held keys, retained velocity and any mouse look, e.g. on focus loss. */
    void clear() {
        keys.clear();
        motion.reset();
        lookReleased();
        pendingX = pendingY = 0;
    }

    /** Forgets the previous pulse time so the next pulse integrates zero seconds. */
    void idle() {
        lastTick = 0;
        pendingX = pendingY = 0;
    }

    boolean looking() {
        return looking;
    }

    /** Whether a press of {@code button} starts mouse look: the right or the left button. */
    static boolean looks(MouseButton button) {
        return button == MouseButton.SECONDARY || button == MouseButton.PRIMARY;
    }

    /** Whether a look with {@code button} captures the cursor: only the right button. */
    static boolean captures(MouseButton button) {
        return button == MouseButton.SECONDARY;
    }

    /**
     * A look button pressed at a screen position. The right button hides the cursor (it is
     * warped back only near the view edge); the left button looks without capture. A second
     * button during a look is ignored.
     */
    void lookPressed(MouseButton button, double screenX, double screenY) {
        if (looking || !looks(button)) return;
        looking = true;
        lookButton = button;
        anchorX = lookX = screenX;
        anchorY = lookY = screenY;
        awaitingEcho = lateEcho = warpedOnce = false;
        if (captures(button)) host.cursorHidden(true);
    }

    /**
     * A move while looking. Deltas are relative to the previous event. While a warp's echo is
     * awaited, an event nearer the warp target than the previous position is the echo (or already
     * after it): it becomes the new origin and is not counted. Events still near the old position
     * are real motion from before the warp. A captured look nearing the view edge warps to the
     * centre.
     */
    void lookMoved(double screenX, double screenY, ViewBox view) {
        if (!looking || !Double.isFinite(screenX) || !Double.isFinite(screenY)) return;
        if (lateEcho && Math.hypot(screenX - targetX, screenY - targetY) <= LATE_ECHO
                && Math.hypot(screenX - lookX, screenY - lookY) > LATE_ECHO) {
            // A warp given up on landed after all: a new origin, not motion.
            lateEcho = false;
            captureFailures = 0;
            lookX = screenX;
            lookY = screenY;
            return;
        }
        if (awaitingEcho) {
            double toTarget = Math.hypot(screenX - targetX, screenY - targetY);
            double toLast = Math.hypot(screenX - lookX, screenY - lookY);
            if (toTarget < toLast) {
                awaitingEcho = false;
                captureFailures = 0;
                lookX = screenX;
                lookY = screenY;
                return;
            }
            if (++echoWait >= ECHO_EVENTS) {
                // No echo yet: count this move as real (it is near the old position) and carry on
                // relatively; an echo that still lands later is recognised once.
                awaitingEcho = false;
                lateEcho = true;
                if (++captureFailures >= CAPTURE_FAILURES) {
                    captureOff = true;
                    host.captureUnavailable();
                    host.captureFallback("the cursor could not be re-centred " + CAPTURE_FAILURES
                            + " times in a row; mouse look stays relative");
                } else {
                    host.captureFallback("the cursor could not be re-centred; this move is relative");
                }
            }
        }
        boolean timedOut = lateEcho && !awaitingEcho && echoWait >= ECHO_EVENTS;
        if (timedOut) echoWait = 0;
        double dx = screenX - lookX, dy = screenY - lookY;
        pendingX += dx;
        pendingY += dy;
        lookX = screenX;
        lookY = screenY;
        // A warp given up on this very event is not retried until the next one.
        if (timedOut || !captures(lookButton) || captureOff || awaitingEcho || view == null
                || !view.nearEdge(screenX, screenY)) return;
        targetX = view.centerX();
        targetY = view.centerY();
        if (pointer.warp(targetX, targetY)) {
            awaitingEcho = true;
            lateEcho = false;
            warpedOnce = true;
            echoWait = 0;
        } else {
            captureOff = true;
            host.captureUnavailable();
            host.captureFallback("the cursor cannot be placed on this platform; mouse look stays relative");
        }
    }

    /** The look button released (or focus lost): restore the cursor where the look began. */
    void lookReleased(MouseButton button) {
        if (looking && button == lookButton) lookReleased();
    }

    void lookReleased() {
        if (!looking) return;
        looking = false;
        boolean captured = captures(lookButton);
        // The cursor comes back where the look began, as in the client, warped or not.
        if (captured && !captureOff) pointer.warp(anchorX, anchorY);
        awaitingEcho = lateEcho = warpedOnce = false;
        lookButton = null;
        if (captured) host.cursorHidden(false);
    }

    /** True when a click should inspect: primary button released without movement. */
    static boolean inspects(MouseButton button, boolean stillSincePress) {
        return button == MouseButton.PRIMARY && stillSincePress;
    }

    /**
     * Wheel dolly, or a speed preset step while the sprint modifier (Shift, or whatever modifier
     * SPRINT is bound to) or the held sprint key is down.
     */
    void scroll(double delta, boolean shift, boolean control) {
        if (!Double.isFinite(delta) || delta == 0) return;
        var modifier = controls.sprintModifier();
        boolean speedWheel = held(WorldControls.Action.SPRINT)
                || shift && modifier == KeyCode.SHIFT
                || control && modifier == KeyCode.CONTROL;
        if (speedWheel) {
            var next = controls.speed().step(delta > 0 ? 1 : -1);
            if (next != controls.speed()) {
                controls = controls.withSpeed(next);
                host.controlsChanged(controls);
            }
            return;
        }
        var camera = host.camera();
        if (camera == null || !host.wheelReady()) return;
        double step =
                Math.copySign(
                        Math.min(Math.abs(delta) / 40, 3)
                                * Math.clamp(
                                        motion.speed(speed(), false) * DOLLY_SECONDS,
                                        motion.mode() == ExplorerMotion.Mode.WALK ? .5 : 2,
                                        32),
                        delta);
        var zoomed = motion.dolly(camera, step, host.ground());
        host.groundStatus(zoomed.ground());
        if (zoomed.moved()) {
            host.camera(zoomed.camera());
            host.abandonUnusableCandidate();
            frame();
            host.loadNext();
        }
    }

    /** Current key state as motion axes; overlapping keys on one axis never double speed. */
    ExplorerMotion.Input axes() {
        return new ExplorerMotion.Input(
                axis(WorldControls.Action.FORWARD, WorldControls.Action.BACK),
                axis(WorldControls.Action.RIGHT, WorldControls.Action.LEFT),
                axis(WorldControls.Action.UP, WorldControls.Action.DOWN),
                axis(WorldControls.Action.TURN_LEFT, WorldControls.Action.TURN_RIGHT),
                axis(WorldControls.Action.LOOK_UP, WorldControls.Action.LOOK_DOWN),
                held(WorldControls.Action.SPRINT));
    }

    /**
     * One animation pulse with a camera present: applies the mouse look summed since the last
     * pulse, integrates held keys, then requests a frame (always after a look or for the final
     * resting step, otherwise at the movement cadence) and nearby loading at most every
     * {@link #NEARBY_NANOS}.
     */
    void pulse(long now) {
        var camera = host.camera();
        if (camera == null) {
            pendingX = pendingY = 0;
            return;
        }
        double dt = lastTick == 0 ? 0 : Math.min(.1, (now - lastTick) / 1e9);
        lastTick = now;
        boolean looked = pendingX != 0 || pendingY != 0;
        if (looked) {
            double s = controls.sensitivity();
            // Mouse right turns right (negative yaw); mouse up looks up unless inverted.
            camera = camera.look(-pendingX * s, (controls.invertY() ? pendingY : -pendingY) * s);
            pendingX = pendingY = 0;
            host.camera(camera);
        }
        var input = axes();
        boolean steering =
                input.forward() != 0
                        || input.strafe() != 0
                        || input.vertical() != 0
                        || input.turn() != 0
                        || input.tilt() != 0;
        if (!steering && !motion.coasting() && motion.mode() != ExplorerMotion.Mode.WALK) {
            if (looked) frame();
            else showFinal();
            return;
        }
        var stepped = motion.step(camera, input, dt, speed(), host.ground());
        host.groundStatus(stepped.ground());
        if (!stepped.moved()) {
            if (looked) frame();
            // Velocity just decayed to zero: the last moving step may still be unframed.
            else if (stepped.resting()) showFinal();
            return;
        }
        host.camera(stepped.camera());
        unshown = true;
        long cadence = host.fastCadence() ? FAST_CADENCE_NANOS : SLOW_CADENCE_NANOS;
        // A look and the final settling/coasting step are always shown, not dropped by cadence.
        if (looked || now - lastRender > cadence || stepped.resting()) {
            lastRender = now;
            frame();
        }
        host.abandonUnusableCandidate();
        if (now - lastNearby > NEARBY_NANOS) {
            lastNearby = now;
            host.loadNext();
        }
    }

    private void frame() {
        unshown = false;
        host.requestFrame();
    }

    /** Frames a position the cadence held back once motion has stopped. */
    private void showFinal() {
        if (unshown) frame();
    }

    /** Test hook: suppress cadence-driven frames and discovery while exercising motion. */
    void holdCadence() {
        lastRender = lastNearby = Long.MAX_VALUE;
    }

    private boolean held(WorldControls.Action action) {
        var bindings = controls.keys();
        for (var code : keys) {
            if (bindings.get(code) == action) return true;
        }
        return false;
    }

    private int axis(WorldControls.Action positive, WorldControls.Action negative) {
        return (held(positive) ? 1 : 0) - (held(negative) ? 1 : 0);
    }
}
