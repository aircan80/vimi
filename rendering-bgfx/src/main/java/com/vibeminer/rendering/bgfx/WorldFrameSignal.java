package com.vibeminer.rendering.bgfx;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coalescing wake-up for one waiting frame reader. Any number of signals before a wait count as
 * one; a wait returns early on a signal and otherwise after its bounded timeout.
 */
final class WorldFrameSignal {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean pending;

    void signal() {
        lock.lock();
        try {
            pending = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if signaled (consuming every pending signal), false on timeout. */
    boolean await(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (!pending && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
            boolean signaled = pending;
            pending = false;
            return signaled;
        } finally {
            lock.unlock();
        }
    }
}
