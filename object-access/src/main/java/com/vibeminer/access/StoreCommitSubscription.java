package com.vibeminer.access;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded nonblocking commit queue; missed notifications require a fresh measurement. */
public final class StoreCommitSubscription implements AutoCloseable {
    private final StoreCommitFeed owner;
    private final ArrayBlockingQueue<StoreCommit> queue;
    private final AtomicBoolean missed = new AtomicBoolean(), closed = new AtomicBoolean();

    StoreCommitSubscription(StoreCommitFeed owner, int capacity) {
        this.owner = owner;
        queue = new ArrayBlockingQueue<>(capacity);
    }

    void offer(StoreCommit commit) {
        if (!closed.get() && !queue.offer(commit)) {
            missed.set(true);
        }
    }

    public StoreCommit poll() {
        return queue.poll();
    }

    public boolean takeMissed() {
        return missed.getAndSet(false);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            owner.remove(this);
        }
    }
}
