package com.vibeminer.rendering.world;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The walking body's animation clock: which sequence it plays ({@link BodyAnimation#choose}) and
 * where in that sequence's loop it is. The clock runs at {@link BodyAnimation#rate} (the body's
 * speed over the sequence's recorded move speed); a new sequence starts from its beginning. A body
 * backpedalling with no WalkBackwards sequence plays its walk backwards, as the client does.
 *
 * <p>Not thread-safe; one instance per body.
 */
public final class BodyAnimator {
    private final Map<Integer, Long> durations;
    private final Map<Integer, Float> moveSpeeds;
    private final Set<Integer> available;
    private int sequence = -1;
    private double clock;

    /**
     * @param durations each available sequence's loop length in milliseconds, by AnimationData ID
     * @param moveSpeeds each sequence's recorded move speed (0 when it records none)
     */
    public BodyAnimator(Map<Integer, Long> durations, Map<Integer, Float> moveSpeeds) {
        this.durations = Map.copyOf(durations);
        this.moveSpeeds = Map.copyOf(moveSpeeds);
        available = this.durations.keySet();
        if (!available.contains(BodyAnimation.STAND)) {
            throw new IllegalArgumentException("A body needs a Stand sequence");
        }
        for (long duration : this.durations.values()) {
            if (duration <= 0) {
                throw new IllegalArgumentException("Invalid sequence duration");
            }
        }
    }

    /** The sequences this body has. */
    public Set<Integer> available() {
        return available;
    }

    /**
     * Advances the clock by {@code seconds} for a body doing this; see {@link BodyAnimation#choose}
     * for the other arguments.
     */
    public void step(
            WalkController.Mode mode,
            WalkController.Status status,
            double speed,
            boolean backward,
            boolean swimming,
            double seconds) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(status);
        int next = BodyAnimation.choose(mode, status, speed, backward, swimming, available);
        if (next != sequence) {
            sequence = next;
            clock = 0;
        }
        long duration = durations.get(sequence);
        double rate = BodyAnimation.rate(moveSpeeds.getOrDefault(sequence, 0f), speed);
        // Walking backwards without its own sequence: the walk plays in reverse.
        boolean reversed = backward && sequence != BodyAnimation.WALK_BACKWARDS && speed > .05;
        double advance = Math.max(0, seconds) * 1000 * rate * (reversed ? -1 : 1);
        clock = ((clock + advance) % duration + duration) % duration;
    }

    /** The playing sequence (Stand before the first step). */
    public int sequence() {
        return sequence < 0 ? BodyAnimation.STAND : sequence;
    }

    /** Where in the playing sequence's loop the body is, in whole milliseconds. */
    public long millis() {
        long duration = durations.get(sequence());
        return Math.min(duration - 1, (long) clock);
    }
}
