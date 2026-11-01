package com.vibeminer.access;

/** In-process committed changes, never another process's writes. */
public interface StoreCommits {
    StoreCommitSubscription subscribe(int capacity);

    default StoreCommitSubscription subscribe() {
        return subscribe(1024);
    }
}
