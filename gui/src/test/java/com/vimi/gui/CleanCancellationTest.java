package com.vimi.gui;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Pure cancellation chains from nested JDK joins are clean; anything else is not. */
public final class CleanCancellationTest {
    private CleanCancellationTest() {}

    public static void main(String[] args) throws Exception {
        var original = new CancellationException();
        require(CleanCancellation.is(original), "Plain cancellation");
        var stored = new CompletableFuture<Void>();
        stored.completeExceptionally(original);
        var joined = thrown(stored::join);
        require(joined.getClass() == CancellationException.class && joined.getCause() == original,
                "JDK join wraps a stored cancellation: " + joined);
        require(CleanCancellation.is(joined), "One join link");
        var nested = new CompletableFuture<Void>();
        nested.completeExceptionally(joined);
        require(CleanCancellation.is(thrown(nested::join)), "Two join links");
        require(CleanCancellation.is(new CompletionException(joined)), "Completion wrapper");
        require(CleanCancellation.is(new ExecutionException(joined)), "Execution wrapper");
        require(CleanCancellation.is(thrown(() -> stored.thenApply(v -> v).join())),
                "Dependent stage");

        var middle = new IllegalStateException("cleanup failed", original);
        var dirty = new CancellationException("join");
        dirty.initCause(middle);
        require(!CleanCancellation.is(dirty), "Real failure in the middle");
        var endsInFailure = new CancellationException("join");
        endsInFailure.initCause(new IllegalStateException("not a stop"));
        require(!CleanCancellation.is(endsInFailure), "Chain ending in a failure");
        for (int at = 0; at < 3; at++) {
            var chain = chain(3);
            Throwable link = chain;
            for (int i = 0; i < at; i++) link = link.getCause();
            link.addSuppressed(new IllegalStateException("auxiliary"));
            require(!CleanCancellation.is(chain), "Suppressed failure on link " + at);
        }
        var wrapper = new CompletionException(chain(2));
        wrapper.addSuppressed(new IllegalStateException("auxiliary"));
        require(!CleanCancellation.is(wrapper), "Suppressed failure on a wrapper");
        var foreign = new CancellationException() {
            @Override
            public synchronized Throwable getCause() {
                throw new AssertionError("arbitrary cause accessor");
            }
        };
        var overForeign = new CancellationException("join");
        overForeign.initCause(foreign);
        require(!CleanCancellation.is(foreign) && !CleanCancellation.is(overForeign),
                "Subclass rejected without calling its accessors");
        require(!CleanCancellation.is(new CompletionException(null)), "Empty wrapper");
        require(!CleanCancellation.is(new RuntimeException()), "Unrelated failure");

        var first = new CancellationException("join");
        var second = new CancellationException("join");
        first.initCause(second);
        second.initCause(first);
        require(!CleanCancellation.is(first), "Cycle");
        require(CleanCancellation.is(chain(8)), "Eight links");
        require(!CleanCancellation.is(chain(9)), "Over-bound chain");
        require(!CleanCancellation.is(chain(10_000)), "Very deep chain");
        System.out.println("CleanCancellationTest PASS");
    }

    private static CancellationException chain(int links) {
        var link = new CancellationException();
        for (int i = 1; i < links; i++) {
            var outer = new CancellationException("join");
            outer.initCause(link);
            link = outer;
        }
        return link;
    }

    private static RuntimeException thrown(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("Expected a failure");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
