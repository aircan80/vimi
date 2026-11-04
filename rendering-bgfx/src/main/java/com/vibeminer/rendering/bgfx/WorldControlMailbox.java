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
        if (transactions.size() == TRANSACTIONS) {
            failed = true;
            close = true;
            camera = null;
            transactions.clear();
            return Result.OVERFLOW;
        }
        transactions.addLast(command);
        return Result.ACCEPTED;
    }

    synchronized WorldStreamControl.Camera takeCamera() {
        var result = camera;
        camera = null;
        return result;
    }

    synchronized WorldStreamControl.Command takeTransaction() {
        return transactions.pollFirst();
    }

    synchronized boolean closing() {
        return close;
    }

    synchronized boolean failed() {
        return failed;
    }
}
