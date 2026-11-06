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
 * hidden and, when the {@link Pointer} can warp, re-centred after every move so it never leaves
 * the view; on release it returns to where the look began. Holding the left button looks without
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

        void requestFrame();

        void abandonUnusableCandidate();

        void loadNext();

        /** Hides the cursor over the view while mouse look is held; shows it again after. */
        void cursorHidden(boolean hidden);

        /** Cursor capture fell back to relative movement; the view logs the first one. */
        void captureFallback(String reason);
    }

    /**
     * Moves the system cursor and reports where it actually is afterwards, in the same screen
     * units as mouse events. That can differ from the request: the platform puts the cursor on a
     * physical pixel (fractional output scales, odd view sizes), or the move is still in flight.
     */
    @FunctionalInterface
    interface Pointer {
        /** The cursor's position after the move ({x, y}), or null when it cannot be placed. */
        double[] warp(double screenX, double screenY);

        /** No cursor placement: mouse look then uses relative movement without re-centring. */
        Pointer NONE = (x, y) -> null;
    }

    /** A reported position farther than this from the request means the move has not landed. */
    static final double LANDED = 2;

    /** Consecutive looks whose first warp fails before capture is turned off for good. */
    static final int CAPTURE_FAILURES = 3;

    /**
     * Whether the plain wheel may dolly: on the legacy CPU path a terrain scene is loaded; in
     * GPU streaming mode a stream controller (and its ground) is present.
     */
    static boolean wheelReady(boolean legacyScene, boolean streamingMode, boolean streamPresent) {
        return legacyScene || streamingMode && streamPresent;
    }

    /** Default, minimum and maximum flight speed in yards per second. */
    static final double DEFAULT_SPEED = 80, MIN_SPEED = 10, MAX_SPEED = 800;

    static final long FAST_CADENCE_NANOS = 16_000_000,
            SLOW_CADENCE_NANOS = 100_000_000,
            NEARBY_NANOS = 100_000_000;

    private final Host host;
    private final ExplorerMotion motion = new ExplorerMotion();
    private final Set<KeyCode> keys = new HashSet<>();
    private double speed = DEFAULT_SPEED;
    private long lastTick, lastRender, lastNearby;
    private WorldControls controls = WorldControls.DEFAULTS;
    private Pointer pointer = Pointer.NONE;
    // Mouse look: reference point for the next delta (the warp centre, or the last position when
    // the cursor cannot be warped), where the look began, and pixels owed to the next pulse.
    private boolean looking, warping, captureOff;
    private MouseButton lookButton;
    private int captureFailures;
    private double lookX, lookY, anchorX, anchorY, pendingX, pendingY;
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

    double speed() {
        return speed;
    }

    void speed(double next) {
        speed = next;
    }

    /** Effective speed for the status line: the current mode with the held sprint key. */
    double effectiveSpeed() {
        return motion.speed(speed, keys.contains(KeyCode.SHIFT));
    }

    boolean keysHeld() {
        return !keys.isEmpty();
    }

    /** F toggles walking once per physical press; key repeat is ignored. */
    void keyPressed(KeyCode code) {
        if (code == KeyCode.F && keys.add(KeyCode.F)) host.toggleWalk();
        else keys.add(code);
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
     * A look button pressed at a screen position. The right button hides the cursor and, when
     * possible, moves it to the view centre so every later move is measured from where it lands;
     * the left button looks with relative movement. A second button during a look is ignored.
     */
    void lookPressed(MouseButton button, double screenX, double screenY, double centerX, double centerY) {
        if (looking || !looks(button)) return;
        looking = true;
        lookButton = button;
        anchorX = lookX = screenX;
        anchorY = lookY = screenY;
        warping = false;
        if (!captures(button)) return;
        host.cursorHidden(true);
        if (captureOff) return;
        var landed = pointer.warp(centerX, centerY);
        if (landed != null && near(landed, centerX, centerY)) {
            captureFailures = 0;
            warping = true;
            lookX = landed[0];
            lookY = landed[1];
        } else if (++captureFailures >= CAPTURE_FAILURES) {
            captureOff = true;
            host.captureFallback("the cursor could not be placed for " + CAPTURE_FAILURES
                    + " looks in a row; mouse look stays relative");
        } else {
            host.captureFallback("the cursor could not be placed; this look is relative");
        }
    }

    /**
     * A move while looking, with the view centre now (the window may have moved). A move to the
     * reference point is the event our own warp produced and is ignored. After each counted move
     * a capturing look re-centres and measures from where the cursor lands; a warp the platform
     * refuses ends re-centring for this look, and one still in flight is assumed to land.
     */
    void lookMoved(double screenX, double screenY, double centerX, double centerY) {
        if (!looking) return;
        double dx = screenX - lookX, dy = screenY - lookY;
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || dx == 0 && dy == 0) return;
        pendingX += dx;
        pendingY += dy;
        lookX = screenX;
        lookY = screenY;
        if (!warping) return;
        var landed = pointer.warp(centerX, centerY);
        if (landed == null) {
            warping = false;
            host.captureFallback("the cursor could not be re-centred; this look is relative");
        } else if (near(landed, centerX, centerY)) {
            lookX = landed[0];
            lookY = landed[1];
        } else {
            lookX = centerX;
            lookY = centerY;
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
        if (warping) pointer.warp(anchorX, anchorY);
        warping = false;
        lookButton = null;
        if (captured) host.cursorHidden(false);
    }

    private static boolean near(double[] at, double x, double y) {
        return at.length == 2 && Math.abs(at[0] - x) <= LANDED && Math.abs(at[1] - y) <= LANDED;
    }

    /** True when a click should inspect: primary button released without movement. */
    static boolean inspects(MouseButton button, boolean stillSincePress) {
        return button == MouseButton.PRIMARY && stillSincePress;
    }

    /** Wheel dolly, or flight speed with Shift. Non-finite and zero deltas are ignored. */
    void scroll(double delta, boolean shift) {
        if (!Double.isFinite(delta) || delta == 0) return;
        if (shift) {
            speed = Math.clamp(speed * (delta > 0 ? 1.2 : .8), MIN_SPEED, MAX_SPEED);
            return;
        }
        var camera = host.camera();
        if (camera == null || !host.wheelReady()) return;
        double step =
                Math.copySign(
                        Math.min(Math.abs(delta) / 40, 3)
                                * Math.clamp(
                                        motion.speed(speed, false) * .1,
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
                pressed(KeyCode.W) - pressed(KeyCode.S),
                pressed(KeyCode.D) - pressed(KeyCode.A),
                Math.max(pressed(KeyCode.E), pressed(KeyCode.SPACE))
                        - Math.max(pressed(KeyCode.Q), pressed(KeyCode.X)),
                pressed(KeyCode.LEFT) - pressed(KeyCode.RIGHT),
                pressed(KeyCode.UP) - pressed(KeyCode.DOWN),
                keys.contains(KeyCode.SHIFT));
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
        var stepped = motion.step(camera, input, dt, speed, host.ground());
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

    private int pressed(KeyCode code) {
        return keys.contains(code) ? 1 : 0;
    }
}
