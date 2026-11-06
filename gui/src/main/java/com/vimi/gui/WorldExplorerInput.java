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
 * <p>Mouse look follows the game client: it is active only while the right button is held, the
 * cursor is hidden, and when the {@link Pointer} can warp it is re-centred after every move so
 * it never leaves the view; on release it returns to where the look began. Movement is summed
 * between pulses and applied once per pulse, so a burst of mouse events costs one camera update
 * and at most one frame request.
 */
final class WorldExplorerInput {
    /** View hooks; every method is called on the FX thread from an input event or pulse. */
    interface Host {
        TerrainRenderer.Camera camera();

        void camera(TerrainRenderer.Camera next);

        TerrainGround ground();

        /** True when the wheel may dolly: an explorer scene or stream is present. */
        boolean wheelReady();

        /** True when frames are cheap enough for the 16 ms movement cadence. */
        boolean fastCadence();

        void groundStatus(ExplorerMotion.Ground ground);

        void toggleWalk();

        void requestFrame();

        void abandonUnusableCandidate();

        void loadNext();

        /** Hides the cursor over the view while mouse look is held; shows it again after. */
        void cursorHidden(boolean hidden);

        /** The input changed its own controls (Shift+wheel speed); show and persist them. */
        void controlsChanged(WorldControls controls);
    }

    /** Moves the system cursor; false when this platform cannot place it (e.g. no robot). */
    @FunctionalInterface
    interface Pointer {
        boolean warp(double screenX, double screenY);

        /** No cursor placement: mouse look then uses relative movement without re-centring. */
        Pointer NONE = (x, y) -> false;
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
    // Mouse look: reference point for the next delta (the warp centre, or the last position when
    // the cursor cannot be warped), where the look began, and pixels owed to the next pulse.
    private boolean looking, warping;
    private double lookX, lookY, anchorX, anchorY, pendingX, pendingY;

    WorldExplorerInput(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    ExplorerMotion.Mode mode() {
        return motion.mode();
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

    boolean keysHeld() {
        return !keys.isEmpty();
    }

    /** The walk toggle fires once per physical press of its key; key repeat is ignored. */
    void keyPressed(KeyCode code) {
        boolean fresh = keys.add(code);
        if (fresh && controls.keys().get(code) == WorldControls.Action.WALK) host.toggleWalk();
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
    }

    boolean looking() {
        return looking;
    }

    /** Whether a press of {@code button} starts mouse look: only the right (secondary) button. */
    static boolean looks(MouseButton button) {
        return button == MouseButton.SECONDARY;
    }

    /**
     * Right button pressed at a screen position: hide the cursor and, when possible, move it to
     * the view centre so that every later move is measured from there.
     */
    void lookPressed(double screenX, double screenY, double centerX, double centerY) {
        if (looking) return;
        looking = true;
        anchorX = screenX;
        anchorY = screenY;
        warping = pointer.warp(centerX, centerY);
        lookX = warping ? centerX : screenX;
        lookY = warping ? centerY : screenY;
        host.cursorHidden(true);
    }

    /**
     * A move while looking. A move to the reference point itself is the event our own warp
     * produced and is ignored. When a warp fails the look continues with relative movement.
     */
    void lookMoved(double screenX, double screenY) {
        if (!looking) return;
        double dx = screenX - lookX, dy = screenY - lookY;
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || dx == 0 && dy == 0) return;
        pendingX += dx;
        pendingY += dy;
        if (!warping || !pointer.warp(lookX, lookY)) {
            warping = false;
            lookX = screenX;
            lookY = screenY;
        }
    }

    /** Right button released (or focus lost): restore the cursor where the look began. */
    void lookReleased() {
        if (!looking) return;
        looking = false;
        if (warping) pointer.warp(anchorX, anchorY);
        warping = false;
        host.cursorHidden(false);
    }

    /** True when a click should inspect: primary button released without movement. */
    static boolean inspects(MouseButton button, boolean stillSincePress) {
        return button == MouseButton.PRIMARY && stillSincePress;
    }

    /** Wheel dolly, or flight speed with Shift. Non-finite and zero deltas are ignored. */
    void scroll(double delta, boolean shift) {
        if (!Double.isFinite(delta) || delta == 0) return;
        if (shift) {
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
            host.requestFrame();
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
        if (camera == null) return;
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
            if (looked) host.requestFrame();
            return;
        }
        var stepped = motion.step(camera, input, dt, speed(), host.ground());
        host.groundStatus(stepped.ground());
        if (!stepped.moved()) {
            if (looked) host.requestFrame();
            return;
        }
        host.camera(stepped.camera());
        long cadence = host.fastCadence() ? FAST_CADENCE_NANOS : SLOW_CADENCE_NANOS;
        // A look and the final settling/coasting step are always shown, not dropped by cadence.
        if (looked || now - lastRender > cadence || stepped.resting()) {
            lastRender = now;
            host.requestFrame();
        }
        host.abandonUnusableCandidate();
        if (now - lastNearby > NEARBY_NANOS) {
            lastNearby = now;
            host.loadNext();
        }
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
