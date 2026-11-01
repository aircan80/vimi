package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class AccessLifetimeTest {
    @Test
    void closeDrainsPublicOperationsAndInternalGroupsAndRejectsNewCalls() {
        AccessLifetime lifetime = new AccessLifetime();
        CompletableFuture<Integer> inner = new CompletableFuture<>();
        AtomicInteger cleanups = new AtomicInteger();
        CompletableFuture<Integer> result = lifetime.run(() -> inner);
        lifetime.retain();
        CompletableFuture<Void> closed =
                lifetime.close(
                        () -> {
                            cleanups.incrementAndGet();
                            return CompletableFuture.completedFuture(null);
                        });
        assertFalse(closed.isDone());
        lifetime.retain();
        inner.complete(7);
        assertTrue(result.isDone());
        assertEquals(7, result.join());
        assertFalse(closed.isDone());
        lifetime.release();
        assertFalse(closed.isDone());
        lifetime.release();
        closed.join();
        assertEquals(1, cleanups.get());
        assertSame(
                closed,
                lifetime.close(
                        () -> {
                            throw new AssertionError("Second cleanup ran");
                        }));
        CompletionException error =
                assertThrows(
                        CompletionException.class,
                        () -> lifetime.run(() -> CompletableFuture.completedFuture(8)).join());
        assertEquals("OBJECT_ACCESS_CLOSED", error.getCause().getMessage());
        assertThrows(IllegalStateException.class, lifetime::retain);
    }

    @Test
    void cancelingThePublicFutureDoesNotReleaseItsInnerWork() {
        AccessLifetime lifetime = new AccessLifetime();
        CompletableFuture<Integer> inner = new CompletableFuture<>();
        CompletableFuture<Integer> result = lifetime.run(() -> inner);
        result.cancel(false);
        CompletableFuture<Void> closed =
                lifetime.close(() -> CompletableFuture.completedFuture(null));
        assertFalse(closed.isDone());
        inner.complete(9);
        closed.join();
        assertTrue(result.isCancelled());
    }

    @Test
    void aContinuationCanWaitForCloseWithoutHoldingItsOwnOperationOpen() throws Exception {
        AccessLifetime lifetime = new AccessLifetime();
        CompletableFuture<Integer> inner = new CompletableFuture<>();
        CompletableFuture<Integer> result = lifetime.run(() -> inner);
        CompletableFuture<Void> continuation =
                result.thenRun(
                        () -> lifetime.close(() -> CompletableFuture.completedFuture(null)).join());
        inner.complete(1);
        continuation.get(5, TimeUnit.SECONDS);
    }

    @Test
    void starterAndCleanupFailuresArePublishedAndDoNotLeakReferences() {
        AccessLifetime lifetime = new AccessLifetime();
        IllegalStateException expected = new IllegalStateException("starter");
        assertSame(
                expected,
                assertThrows(
                                CompletionException.class,
                                () ->
                                        lifetime.run(
                                                        () -> {
                                                            throw expected;
                                                        })
                                                .join())
                        .getCause());
        AssertionError cleanup = new AssertionError("cleanup");
        assertSame(
                cleanup,
                assertThrows(
                                CompletionException.class,
                                () ->
                                        lifetime.close(
                                                        () -> {
                                                            throw cleanup;
                                                        })
                                                .join())
                        .getCause());
        assertThrows(IllegalStateException.class, lifetime::release);
    }

    @Test
    void closeCanStartInsideAnAcceptedOperationWithoutResurrectingTheClient() {
        AccessLifetime lifetime = new AccessLifetime();
        AtomicInteger cleanups = new AtomicInteger();
        CompletableFuture<Integer> result =
                lifetime.run(
                        () -> {
                            lifetime.close(
                                    () -> {
                                        cleanups.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    });
                            return CompletableFuture.completedFuture(3);
                        });
        assertEquals(3, result.join());
        lifetime.close(
                        () -> {
                            throw new AssertionError("Second cleanup");
                        })
                .join();
        assertEquals(1, cleanups.get());
    }
}
