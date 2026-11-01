package com.vibeminer.store;

import java.util.concurrent.*;

/** Small assertion helpers shared by all object-store test suites. */
public final class StoreAssertions {
    private StoreAssertions() { }
    public static final Cancellation NONE = Cancellation.NONE;
    public static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static ObjectKey key(byte[] bytes) { return new ObjectKey(1, Format.hash("MD5", bytes)); }
    public static void failure(CompletableFuture<?> future, String code) {
        try { future.join(); throw new AssertionError("Expected " + code); }
        catch (CompletionException e) { check(e.getCause() instanceof StoreException s && s.code().equals(code)); }
    }
}
