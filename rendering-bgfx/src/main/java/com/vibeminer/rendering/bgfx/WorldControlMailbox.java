package com.vibeminer.rendering.bgfx;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Fixed metadata-only reader/owner handoff. No callbacks, IO or native resources under its lock.
 */
final class WorldControlMailbox {
    static final int TRANSACTIONS = 4;

    enum Result {
        ACCEPTED,
        STALE_CAMERA,
        WRONG_SESSION,
        OVERFLOW,
        CLOSED
    }

    private final WorldStreamState.Nonce nonce;
    private final ArrayDeque<WorldStreamControl.Command> transactions = new ArrayDeque<>(4);
    private WorldStreamControl.Camera camera;
    // Separate one-deep lane: a late objects command never queues behind or ahead of tiles.
    private WorldStreamControl.LateObjects objects;
    // Separate at-most-once lane for the horizon WDL (#372); a repeat is ignored, never fatal.
    private WorldStreamControl.Horizon horizon;
    private boolean horizonSeen;
    // Separate lane for the walking body's model; a newer one replaces a pending one (the parent
    // sends the next only after the previous BODY_DONE, so at most one is pending).
    private WorldStreamControl.BodyModel body;
    private long revision, policy;
    private int width, height;
    private boolean close, failed;

    WorldControlMailbox(WorldStreamState.Nonce nonce) {
        this.nonce = Objects.requireNonNull(nonce);
    }

    synchronized Result offer(WorldStreamControl.Command command) {
        Objects.requireNonNull(command);
        if (!nonce.equals(command.nonce())) {
            return Result.WRONG_SESSION;
        }
        if (command instanceof WorldStreamControl.Close) {
            close = true;
            camera = null;
            objects = null;
            horizon = null;
            body = null;
            transactions.clear();
            return Result.ACCEPTED;
        }
        if (close) {
            return Result.CLOSED;
        }
        if (command instanceof WorldStreamControl.Camera update) {
            if (update.revision() <= revision
                    || update.policy() < policy
                    || update.policy() == policy
                            && (update.width() != width || update.height() != height)) {
                return Result.STALE_CAMERA;
            }
            revision = update.revision();
            policy = update.policy();
            width = update.width();
            height = update.height();
            camera = update;
            return Result.ACCEPTED;
        }
        if (command instanceof WorldStreamControl.Horizon wdl) {
            if (!horizonSeen) {
                horizonSeen = true;
                horizon = wdl;
            }
            return Result.ACCEPTED;
        }
        if (command instanceof WorldStreamControl.BodyModel model) {
            body = model;
            return Result.ACCEPTED;
        }
        if (command instanceof WorldStreamControl.LateObjects late) {
            if (objects != null) {
                return overflow();
            }
            objects = late;
            return Result.ACCEPTED;
        }
        if (transactions.size() == TRANSACTIONS) {
            return overflow();
        }
        transactions.addLast(command);
        return Result.ACCEPTED;
    }

    private Result overflow() {
        failed = true;
        close = true;
        camera = null;
        objects = null;
        transactions.clear();
        return Result.OVERFLOW;
    }

    synchronized WorldStreamControl.Horizon takeHorizon() {
        var result = horizon;
        horizon = null;
        return result;
    }

    synchronized WorldStreamControl.BodyModel takeBody() {
        var result = body;
        body = null;
        return result;
    }

    synchronized WorldStreamControl.LateObjects takeObjects() {
        var result = objects;
        objects = null;
        return result;
    }

    synchronized WorldStreamControl.Camera takeCamera() {
        var result = camera;
        camera = null;
        return result;
    }

    synchronized WorldStreamControl.Command takeTransaction() {
        return transactions.pollFirst();
    }

    /** Established cancellation may bypass a deferred file decode; new transactions never do. */
    synchronized WorldStreamControl.Cancel takeCancellation() {
        var iterator = transactions.iterator();
        while (iterator.hasNext()) {
            var command = iterator.next();
            if (command instanceof WorldStreamControl.Cancel cancel) {
                iterator.remove();
                return cancel;
            }
        }
        return null;
    }

    synchronized boolean closing() {
        return close;
    }

    synchronized boolean failed() {
        return failed;
    }
}
