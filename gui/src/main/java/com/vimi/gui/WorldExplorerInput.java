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
 * so the input mapping is testable without a JavaFX toolkit. It holds held keys, the look-drag
 * anchor, flight speed and the {@link ExplorerMotion} integrator; the view forwards raw event
 * values and supplies the camera, ground and frame/discovery hooks through {@link Host}.
 */
final class WorldExplorerInput {
    /** View hooks; every method is called on the FX thread from an input event or pulse. */
    interface Host {
        TerrainRenderer.Camera camera();

        void camera(TerrainRenderer.Camera next);

        TerrainGround ground();

        /**
         * True when the wheel may dolly. The view answers {@code terrainScene != null}, which is
         * set only on the legacy CPU explorer path, so in GPU streaming mode the plain wheel
         * currently does nothing (as before this extraction; fixed in the #355 controls work).
         */
        boolean wheelReady();

        /** True when frames are cheap enough for the 16 ms movement cadence. */
        boolean fastCadence();

        void groundStatus(ExplorerMotion.Ground ground);

        void toggleWalk();

        void requestFrame();

        void abandonUnusableCandidate();

        void loadNext();
    }

    /** Radians of look per pixel of mouse drag. */
    static final double LOOK_PER_PIXEL = .006;

    /** Default, minimum and maximum flight speed in yards per second. */
    static final double DEFAULT_SPEED = 80, MIN_SPEED = 10, MAX_SPEED = 800;

    static final long FAST_CADENCE_NANOS = 16_000_000,
            SLOW_CADENCE_NANOS = 100_000_000,
            NEARBY_NANOS = 100_000_000;

    private final Host host;
    private final ExplorerMotion motion = new ExplorerMotion();
    private final Set<KeyCode> keys = new HashSet<>();
    private double mouseX, mouseY, speed = DEFAULT_SPEED;
    private long lastTick, lastRender, lastNearby;
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

    /** Drops held keys and retained velocity, e.g. on focus loss or a hidden viewport. */
    void clear() {
        keys.clear();
        motion.reset();
    }

    /** Forgets the previous pulse time so the next pulse integrates zero seconds. */
    void idle() {
        lastTick = 0;
    }

    void mousePressed(double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    void mouseDragged(double x, double y) {
        var camera = host.camera();
        if (camera == null) return;
        host.camera(camera.look((mouseX - x) * LOOK_PER_PIXEL, (mouseY - y) * LOOK_PER_PIXEL));
        mouseX = x;
        mouseY = y;
        frame();
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
     * One animation pulse with a camera present: integrates held keys, then requests a frame at
     * the movement cadence (always for the final resting step) and nearby loading at most every
     * {@link #NEARBY_NANOS}.
     */
    void pulse(long now) {
        var camera = host.camera();
        if (camera == null) return;
        double dt = lastTick == 0 ? 0 : Math.min(.1, (now - lastTick) / 1e9);
        lastTick = now;
        var input = axes();
        boolean steering =
                input.forward() != 0
                        || input.strafe() != 0
                        || input.vertical() != 0
                        || input.turn() != 0
                        || input.tilt() != 0;
        if (!steering && !motion.coasting() && motion.mode() != ExplorerMotion.Mode.WALK) {
            showFinal();
            return;
        }
        var stepped = motion.step(camera, input, dt, speed, host.ground());
        host.groundStatus(stepped.ground());
        if (!stepped.moved()) {
            // Velocity just decayed to zero: the last moving step may still be unframed.
            if (stepped.resting()) showFinal();
            return;
        }
        host.camera(stepped.camera());
        unshown = true;
        long cadence = host.fastCadence() ? FAST_CADENCE_NANOS : SLOW_CADENCE_NANOS;
        // The final settling/coasting step is always shown, not dropped by cadence.
        if (now - lastRender > cadence || stepped.resting()) {
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
