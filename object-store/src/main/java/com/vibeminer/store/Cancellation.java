package com.vibeminer.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation is independent of the result future. Already appended writes still commit. */
public final class Cancellation {
    public static final Cancellation NONE = new Cancellation(false);
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final boolean mutable;
    private final Object monitor = new Object();
    private final Map<Object, Runnable> listeners = new HashMap<>();

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    Registration onCancel(Runnable listener) {
        if (!mutable) {
            return () -> {};
        }
        Object key = new Object();
        synchronized (monitor) {
            if (!canceled.get()) {
                listeners.put(key, listener);
                return () -> {
                    synchronized (monitor) {
                        listeners.remove(key);
                    }
                };
            }
        }
        listener.run();
        return () -> {};
    }

    public Cancellation() {
        this(true);
    }

    private Cancellation(boolean mutable) {
        this.mutable = mutable;
    }

    public void cancel() {
        if (!mutable || !canceled.compareAndSet(false, true)) {
            return;
        }
        ArrayList<Runnable> callbacks;
        synchronized (monitor) {
            callbacks = new ArrayList<>(listeners.values());
            listeners.clear();
        }
        for (Runnable callback : callbacks) {
            callback.run();
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
