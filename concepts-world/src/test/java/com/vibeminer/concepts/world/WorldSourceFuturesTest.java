package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldSourceFuturesTest {
    @Test
    void preservesOriginalOutcomeAndExistingDirtyCompletionIdentity() {
        var value = new Object();
        assertSame(value, WorldSourceFutures.await(CompletableFuture.completedFuture(value)));
        var original = new CancellationException();
        var thrown =
                assertThrows(
                        CompletionException.class,
                        () -> WorldSourceFutures.await(CompletableFuture.failedFuture(original)));
        assertSame(original, thrown.getCause());
        assertSame(original, WorldSourceRefusals.boundary(thrown));
        var dirty = new CompletionException("Actual wrapper", original);
        dirty.addSuppressed(new IllegalStateException("Actual cleanup failure"));
        assertSame(
                dirty,
                assertThrows(
                        CompletionException.class,
                        () -> WorldSourceFutures.await(CompletableFuture.failedFuture(dirty))));
        assertTrue(WorldSourceRefusals.boundary(dirty) instanceof IllegalStateException);
    }

    @Test
    void neverReadsForeignCancellationOrCompletionAccessors() {
        var calls = new AtomicInteger();
        var canceled =
                new CancellationException() {
                    @Override
                    public synchronized Throwable getCause() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }

                    @Override
                    public String getMessage() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        var wrapper =
                assertThrows(
                        CompletionException.class,
                        () -> WorldSourceFutures.await(CompletableFuture.failedFuture(canceled)));
        assertSame(canceled, wrapper.getCause());
        assertTrue(WorldSourceRefusals.boundary(wrapper) instanceof IllegalStateException);
        var foreign =
                new CompletionException("Foreign", null) {
                    @Override
                    public synchronized Throwable getCause() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }

                    @Override
                    public String getMessage() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        assertSame(
                foreign,
                assertThrows(
                        CompletionException.class,
                        () -> WorldSourceFutures.await(CompletableFuture.failedFuture(foreign))));
        assertSame(foreign, WorldSourceRefusals.boundary(foreign));
        assertEquals(0, calls.get());
    }
}
