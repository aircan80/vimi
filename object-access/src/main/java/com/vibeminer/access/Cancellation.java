package com.vibeminer.access;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit cancellation shared by access-layer scheduling and its lower-layer requests. */
public final class Cancellation {
    public static final Cancellation NONE = new Cancellation(false);
    private final boolean mutable;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final com.vibeminer.store.Cancellation store = new com.vibeminer.store.Cancellation();
    private final com.vibeminer.network.Cancellation network =
            new com.vibeminer.network.Cancellation();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Cancellation() {
        this(true);
    }

    private Cancellation(boolean mutable) {
        this.mutable = mutable;
    }

    public void cancel() {
        if (mutable && canceled.compareAndSet(false, true)) {
            store.cancel();
            network.cancel();
            for (Runnable listener : listeners) {
                listener.run();
            }
            listeners.clear();
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
        if (!mutable) {
            return () -> {};
        }
        listeners.add(listener);
        if (isCanceled() && listeners.remove(listener)) {
            listener.run();
        }
        return () -> listeners.remove(listener);
    }
}
