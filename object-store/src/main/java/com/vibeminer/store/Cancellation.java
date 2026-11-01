package com.vibeminer.store;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation is independent of the result future. Already appended writes still commit. */
public final class Cancellation {
    public static final Cancellation NONE = new Cancellation(false);
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final boolean mutable;

    public Cancellation() {
        this(true);
    }

    private Cancellation(boolean mutable) {
        this.mutable = mutable;
    }

    public void cancel() {
        if (mutable) {
            canceled.set(true);
        }
    }

    public boolean isCanceled() {
        return canceled.get();
    }

    public void check() {
        if (isCanceled()) {
            throw new CancellationException();
        }
    }
}
