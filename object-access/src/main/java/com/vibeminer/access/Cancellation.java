package com.vibeminer.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit cancellation shared by access-layer scheduling and its lower-layer requests. */
public final class Cancellation {
    public static final Cancellation NONE = new Cancellation(false);
    private final boolean mutable;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final com.vibeminer.store.Cancellation store = new com.vibeminer.store.Cancellation();
    private final com.vibeminer.network.Cancellation network =
            new com.vibeminer.network.Cancellation();
    private final Object monitor = new Object();
    private final Map<Object, Runnable> listeners = new HashMap<>();

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
        ArrayList<Runnable> notify;
        synchronized (monitor) {
            notify = new ArrayList<>(listeners.values());
            listeners.clear();
        }
        Throwable first = null;
        for (Runnable action : java.util.List.<Runnable>of(store::cancel, network::cancel)) {
            try {
                action.run();
            } catch (RuntimeException | Error error) {
                if (first == null) {
                    first = error;
                }
            }
        }
        for (Runnable listener : notify) {
            try {
                listener.run();
            } catch (RuntimeException | Error error) {
                if (first == null) {
                    first = error;
                }
            }
        }
        if (first instanceof RuntimeException error) {
            throw error;
        }
        if (first instanceof Error error) {
            throw error;
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

    com.vibeminer.store.Cancellation storeToken() {
        return store;
    }

    com.vibeminer.network.Cancellation networkToken() {
        return network;
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public Registration onCancel(Runnable listener) {
        java.util.Objects.requireNonNull(listener);
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
}
