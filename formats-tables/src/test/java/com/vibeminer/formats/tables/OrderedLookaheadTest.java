package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class OrderedLookaheadTest {
    private static String waitForCancel(Cancellation scope, CompletableFuture<Void> started) {
        started.complete(null);
        var gate = new CompletableFuture<Void>();
        var registration = scope.onCancel(() -> gate.complete(null));
        try {
            gate.join();
        } finally {
            registration.close();
        }
        throw new CancellationException();
    }

    @Test
    void resultsComeBackInOrderAndFailuresAreRethrownUnchanged() {
        var failure = new IllegalStateException("task failure");
        var error = new AssertionError("task error");
        try (var ahead = new OrderedLookahead<Integer, String>(3, Cancellation.NONE)) {
            ahead.start(1, scope -> "one");
            ahead.start(
                    2,
                    scope -> {
                        throw failure;
                    });
            ahead.start(
                    3,
                    scope -> {
                        throw error;
                    });
            assertFalse(ahead.hasRoom());
            assertEquals("one", ahead.take(1));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> ahead.take(2)));
            assertSame(error, assertThrows(AssertionError.class, () -> ahead.take(3)));
            assertTrue(ahead.hasRoom());
        }
    }

    @Test
    void aMismatchedTakeLeavesTheTaskForCloseToCancelAndAwait() {
        var started = new CompletableFuture<Void>();
        var finished = new AtomicBoolean();
        var ahead = new OrderedLookahead<Integer, String>(2, Cancellation.NONE);
        ahead.start(
                1,
                scope -> {
                    try {
                        return waitForCancel(scope, started);
                    } finally {
                        finished.set(true);
                    }
                });
        started.join();
        assertThrows(IllegalStateException.class, () -> ahead.take(2));
        assertFalse(finished.get(), "The task is still running");
        ahead.close();
        assertTrue(finished.get(), "close() canceled and awaited the still-started task");
    }

    @Test
    void parentCancellationReachesRunningTasks() {
        var parent = new Cancellation();
        var started = new CompletableFuture<Void>();
        try (var ahead = new OrderedLookahead<Integer, String>(1, parent)) {
            ahead.start(1, scope -> waitForCancel(scope, started));
            started.join();
            parent.cancel();
            assertThrows(CancellationException.class, () -> ahead.take(1));
        }
    }
}
