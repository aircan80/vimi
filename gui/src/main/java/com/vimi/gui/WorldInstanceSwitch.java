package com.vimi.gui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * In-place zone-in to a global-WMO instance inside the World view, confined to the FX thread.
 *
 * <p>Entering first retires the open world's GPU stream and waits until it has drained; only then
 * does the interior open on the same application GPU owner, and the instance's audio replace the
 * open world's. Leaving first clears the instance audio, then waits until the interior's GPU
 * request has drained before the open world resumes. A leave or abandon during entry cancels it:
 * the late retirement is ignored and no interior opens.
 */
final class WorldInstanceSwitch<T> {
    enum Phase {
        OPEN_WORLD,
        ENTERING,
        INSIDE,
        LEAVING
    }

    /** A live inline interior; closing it releases its GPU request. */
    interface Interior {
        javafx.scene.Node node();

        CompletableFuture<Void> closeAsync();
    }

    /** The World view's side of the switch; every call arrives on the FX thread. */
    interface Hooks<T> {
        /** Stops the open world's GPU stream; completes once its resources have drained. */
        CompletableFuture<Void> retireWorld();

        /** Opens the instance interior; called only after the open world has drained. */
        Interior openInterior(T target);

        /** Shows the open interior in place of the World viewport. */
        void showInterior(T target, Interior interior);

        /** Plays the target's recorded instance audio, or stops instance audio when null. */
        void audio(T target);

        /** Restores the open world (or its map page) after the interior has drained. */
        void resumeWorld(boolean worldOpen);

        void status(String message);
    }

    private final Hooks<T> hooks;
    private final Executor fx;
    private Phase phase = Phase.OPEN_WORLD;
    private long generation;
    private T target;
    private Interior interior;
    private boolean worldOpen;
    private CompletableFuture<Void> drained = CompletableFuture.completedFuture(null);

    WorldInstanceSwitch(Hooks<T> hooks, Executor fx) {
        this.hooks = Objects.requireNonNull(hooks);
        this.fx = Objects.requireNonNull(fx);
    }

    Phase phase() {
        return phase;
    }

    /** True while the World view belongs to an instance (entering, inside or leaving). */
    boolean engaged() {
        return phase != Phase.OPEN_WORLD;
    }

    T target() {
        return target;
    }

    /** Completes once the last interior has released its GPU request. */
    CompletableFuture<Void> drained() {
        return drained.copy();
    }

    /**
     * Starts a zone-in. {@code worldOpen} says whether Leave returns to the open-world explorer
     * (true) or to the map page (false). Refused while another switch is under way.
     */
    boolean enter(T next, boolean worldOpen) {
        Objects.requireNonNull(next);
        if (phase != Phase.OPEN_WORLD) {
            hooks.status("An instance switch is already under way.");
            return false;
        }
        phase = Phase.ENTERING;
        target = next;
        this.worldOpen = worldOpen;
        long current = ++generation;
        hooks.status("Leaving the open world…");
        CompletableFuture<Void> retired;
        try {
            retired = Objects.requireNonNull(hooks.retireWorld());
        } catch (RuntimeException failed) {
            retired = CompletableFuture.failedFuture(failed);
        }
        retired.whenComplete(
                (ignored, failure) -> fx.execute(() -> retired(current, failure)));
        return true;
    }

    private void retired(long current, Throwable failure) {
        if (current != generation || phase != Phase.ENTERING) {
            return; // canceled by leave or abandon
        }
        if (failure != null) {
            phase = Phase.OPEN_WORLD;
            target = null;
            hooks.status(
                    "The open world could not be confirmed stopped ("
                            + WorldDiagnostics.brief(failure)
                            + "); the instance was not entered.");
            hooks.resumeWorld(worldOpen);
            return;
        }
        Interior opened;
        try {
            opened = Objects.requireNonNull(hooks.openInterior(target));
        } catch (RuntimeException failed) {
            phase = Phase.OPEN_WORLD;
            target = null;
            hooks.status("The instance could not be opened (" + WorldDiagnostics.brief(failed) + ").");
            hooks.resumeWorld(worldOpen);
            return;
        }
        interior = opened;
        phase = Phase.INSIDE;
        hooks.showInterior(target, opened);
        hooks.audio(target);
    }

    /**
     * Cancels an entry that is still waiting for the open world to drain (the view was hidden);
     * an open interior is kept. The open world resumes when the view is shown again.
     */
    void cancelEntry() {
        if (phase == Phase.ENTERING) {
            leave();
        }
    }

    /** Leaves the instance and resumes the open world once the interior has drained. */
    void leave() {
        switch (phase) {
            case OPEN_WORLD, LEAVING -> {
                return;
            }
            case ENTERING -> {
                generation++;
                phase = Phase.OPEN_WORLD;
                target = null;
                hooks.status("Instance entry canceled.");
                hooks.resumeWorld(worldOpen);
                return;
            }
            case INSIDE -> {
                // Stop the instance's tracks before anything else resumes.
                hooks.audio(null);
                phase = Phase.LEAVING;
                long current = ++generation;
                hooks.status("Leaving the instance…");
                var closing = close();
                closing.whenComplete(
                        (ignored, failure) -> fx.execute(() -> left(current, failure)));
            }
        }
    }

    private void left(long current, Throwable failure) {
        if (current != generation || phase != Phase.LEAVING) {
            return;
        }
        phase = Phase.OPEN_WORLD;
        target = null;
        if (failure != null) {
            hooks.status(
                    "The instance renderer could not be confirmed stopped ("
                            + WorldDiagnostics.brief(failure)
                            + ").");
        }
        hooks.resumeWorld(worldOpen);
    }

    /**
     * Drops any instance without resuming the open world (map change, invalidation or closing the
     * view). Returns when the interior, if any, has drained.
     */
    CompletableFuture<Void> abandon() {
        if (phase == Phase.OPEN_WORLD) {
            return drained();
        }
        if (phase == Phase.INSIDE) {
            hooks.audio(null);
        }
        generation++;
        phase = Phase.OPEN_WORLD;
        target = null;
        return interior == null ? drained() : close();
    }

    private CompletableFuture<Void> close() {
        var current = interior;
        interior = null;
        CompletableFuture<Void> closing;
        try {
            closing = Objects.requireNonNull(current.closeAsync());
        } catch (RuntimeException failed) {
            closing = CompletableFuture.failedFuture(failed);
        }
        drained = closing;
        return closing.copy();
    }
}
