package com.vibeminer.access;

import com.vibeminer.store.ObjectKey;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Publishing side: slow subscribers never block the store writer. */
public final class StoreCommitFeed implements StoreCommits {
    private final CopyOnWriteArrayList<StoreCommitSubscription> subscriptions =
            new CopyOnWriteArrayList<>();

    @Override
    public StoreCommitSubscription subscribe(int capacity) {
        if (capacity < 1 || capacity > 1_000_000) {
            throw new IllegalArgumentException("Invalid commit capacity");
        }
        StoreCommitSubscription s = new StoreCommitSubscription(this, capacity);
        subscriptions.add(s);
        return s;
    }

    void remove(StoreCommitSubscription subscription) {
        subscriptions.remove(subscription);
    }

    public void publish(List<ObjectKey> stored, List<ObjectKey> removed) {
        if (stored.isEmpty() && removed.isEmpty()) {
            return;
        }
        StoreCommit commit = new StoreCommit(stored, removed);
        for (var subscription : subscriptions) {
            subscription.offer(commit);
        }
    }
}
