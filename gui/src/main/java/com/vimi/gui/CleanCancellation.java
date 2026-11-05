package com.vimi.gui;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Recognizes a failure that carries nothing but a standard cancellation. On current JDKs (25
 * here) {@code CompletableFuture.join()} and {@code get()} rethrow a stored cancellation as a new
 * {@code CancellationException} whose cause is the stored one, so nested joins build a chain of
 * cancellations. Such a chain is still routine cancellation.
 */
final class CleanCancellation {
    private static final int LINKS = 8;

    private CleanCancellation() {}

    /**
     * True only for up to {@value #LINKS} exact {@code CompletionException} or {@code
     * ExecutionException} wrappers around up to {@value #LINKS} exact {@code
     * CancellationException} links that end in a cause-free one, with no suppressed failure
     * anywhere. A subclass is rejected before any of its accessors run, so a cancellation can never
     * hide another failure.
     */
    static boolean is(Throwable failure) {
        Throwable at = failure;
        for (int depth = 0; depth < LINKS; depth++) {
            if (at == null
                    || at.getClass() != CompletionException.class
                            && at.getClass() != ExecutionException.class) {
                break;
            }
            if (at.getSuppressed().length != 0) return false;
            at = at.getCause();
        }
        for (int depth = 0; depth < LINKS; depth++) {
            if (at == null
                    || at.getClass() != CancellationException.class
                    || at.getSuppressed().length != 0) {
                return false;
            }
            at = at.getCause();
            if (at == null) return true;
        }
        return false;
    }
}
