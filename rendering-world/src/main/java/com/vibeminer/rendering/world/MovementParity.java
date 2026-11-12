package com.vibeminer.rendering.world;

/**
 * Movement values for walking and flying with a body. Each value says where it comes from and
 * whether it is checked against the game client yet. "Emulator" values are the constants open
 * server emulators use for the Classic client (unit speeds, gravity and jump velocity); they are
 * not yet measured on build 70862. "Flagged" values have no known source: they are explorer
 * defaults to be replaced by client measurements.
 */
public final class MovementParity {
    private MovementParity() {}

    /** Running forward, yards per second. Emulator base run speed. */
    public static final double RUN_SPEED = 7.0;

    /** Walking (walk toggle), yards per second. Emulator base walk speed. */
    public static final double WALK_SPEED = 2.5;

    /** Moving backwards, yards per second. Emulator base backpedal ("run back") speed. */
    public static final double BACKPEDAL_SPEED = 4.5;

    /** Swimming forward and backwards, yards per second. Emulator base swim speeds. */
    public static final double SWIM_SPEED = 4.722, SWIM_BACK_SPEED = 2.5;

    /** Keyboard turning, radians per second: half a turn per second. Emulator base turn rate. */
    public static final double TURN_RATE = Math.PI;

    /** Downward acceleration, yards per second squared. Emulator gravity. */
    public static final double GRAVITY = 19.291105;

    /** Fastest fall, yards per second. Emulator terminal velocity. */
    public static final double TERMINAL_VELOCITY = 60.148003;

    /** Upward speed at take-off, yards per second (apex about 1.64 yd). Emulator jump velocity. */
    public static final double JUMP_VELOCITY = 7.95554;

    /** Flight taxi speed, yards per second. The only flight speed Classic Era has. */
    public static final double TAXI_SPEED = 32;

    /**
     * Steepest walkable ground, degrees from horizontal. Flagged: unknown for the client; steeper
     * ground is a wall to walk into and a slope to slide down.
     */
    public static final double SLOPE_LIMIT_DEGREES = 50;

    /**
     * Tallest ledge walked up without jumping, yards. Flagged: unknown for the client (to be
     * measured on stairs).
     */
    public static final double STEP_HEIGHT = 1.0;

    /**
     * Follow camera distance, yards: the client's base maximum, its largest zoom factor (2.6, so 39
     * yd), and the explorer's starting distance (flagged).
     */
    public static final double CAMERA_BASE_DISTANCE = 15,
            CAMERA_MAX_FACTOR = 2.6,
            CAMERA_START_DISTANCE = 6;

    /**
     * Capsule used until a body supplies its own: CreatureModelData's CollisionWidth (radius) and
     * CollisionHeight. On 70862 these two values are the same for the murloc, baby murloc and
     * gryphon models, so they read as the table's default size rather than a body's.
     */
    public static final double DEFAULT_RADIUS = 0.6111111, DEFAULT_HEIGHT = 2.031278;

    /** Fixed simulation step, seconds; frames integrate whole steps of this size. */
    public static final double STEP_SECONDS = 1.0 / 60;
}
