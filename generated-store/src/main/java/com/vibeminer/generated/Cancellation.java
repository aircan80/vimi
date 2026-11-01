package com.vibeminer.generated;

/** Explicit cancellation independent of the returned future. */
public final class Cancellation {
    public static final Cancellation NONE = new Cancellation(com.vibeminer.store.Cancellation.NONE);
    private final com.vibeminer.store.Cancellation token;

    public Cancellation() {
        this(new com.vibeminer.store.Cancellation());
    }

    private Cancellation(com.vibeminer.store.Cancellation token) {
        this.token = token;
    }

    public void cancel() {
        token.cancel();
    }

    public boolean isCanceled() {
        return token.isCanceled();
    }

    public void check() {
        token.check();
    }

    com.vibeminer.store.Cancellation objectToken() {
        return token;
    }
}
