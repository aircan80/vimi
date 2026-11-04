package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class LocationMapsCloseTest {
    @Test
    void closeReturnsWithoutRunningBlockedCancellationOrHoldingLifecycleMonitor() throws Exception {
        var capture = new CompletableFuture<CapturedRowsTypes.Capture>();
        var captureEntered = new CountDownLatch(1);
        var cancellationEntered = new CountDownLatch(1);
        var cancellationReleased = new CountDownLatch(1);
        var releaseCancellation = new CountDownLatch(1);
        var cancellationThread = new AtomicReference<Thread>();
        var borrowedClosed = new AtomicBoolean();
        var tables =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    return switch (method.getName()) {
                                        case "build" ->
                                                new BuildRef(
                                                        "wowf",
                                                        "a".repeat(32),
                                                        "b".repeat(32),
                                                        "us");
                                        case "locale" -> "enUS";
                                        case "capturePreparedLocal" -> {
                                            var token = (Cancellation) args[1];
                                            token.onCancel(
                                                    () -> {
                                                        cancellationThread.set(
                                                                Thread.currentThread());
                                                        cancellationEntered.countDown();
                                                        try {
                                                            if (!releaseCancellation.await(
                                                                    10, TimeUnit.SECONDS)) {
                                                                throw new AssertionError(
                                                                        "Cancellation test gate timed out");
                                                            }
                                                        } catch (InterruptedException failure) {
                                                            Thread.currentThread().interrupt();
                                                            throw new AssertionError(failure);
                                                        }
                                                        cancellationReleased.countDown();
                                                    });
                                            captureEntered.countDown();
                                            yield capture;
                                        }
                                        case "close", "closeAsync" -> {
                                            borrowedClosed.set(true);
                                            throw new AssertionError(
                                                    "Borrowed tables must remain caller owned");
                                        }
                                        default -> throw new AssertionError(method.getName());
                                    };
                                });
        var service = new LocationMapsService(tables);
        var navigation = service.navigation(Cancellation.NONE);
        var closeReturned = new CountDownLatch(1);
        var closing = new AtomicReference<CompletableFuture<Void>>();
        Thread caller =
                Thread.ofPlatform()
                        .daemon()
                        .name("authored-ui-close-caller")
                        .unstarted(
                                () -> {
                                    closing.set(service.closeAsync());
                                    closeReturned.countDown();
                                });
        try {
            assertTrue(captureEntered.await(5, TimeUnit.SECONDS));
            caller.start();
            assertTrue(
                    closeReturned.await(1, TimeUnit.SECONDS),
                    "Caller waited on lower cancellation");
            assertTrue(cancellationEntered.await(5, TimeUnit.SECONDS));
            assertNotEquals(caller, cancellationThread.get());
            assertFalse(closing.get().isDone());
            var secondClose =
                    CompletableFuture.supplyAsync(service::closeAsync).get(1, TimeUnit.SECONDS);
            assertFalse(secondClose.isDone(), "Close must drain accepted source work");
            var rejected =
                    CompletableFuture.supplyAsync(() -> service.navigation(Cancellation.NONE))
                            .get(1, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
            releaseCancellation.countDown();
            assertTrue(cancellationReleased.await(5, TimeUnit.SECONDS));
            assertFalse(closing.get().isDone(), "Cancellation signal is not source drain");
            capture.completeExceptionally(new java.util.concurrent.CancellationException());
            closing.get().get(5, TimeUnit.SECONDS);
            secondClose.get(5, TimeUnit.SECONDS);
            navigation.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
            assertTrue(navigation.isCompletedExceptionally());
            assertFalse(borrowedClosed.get());
        } finally {
            releaseCancellation.countDown();
            capture.completeExceptionally(new java.util.concurrent.CancellationException());
            service.closeAsync().get(5, TimeUnit.SECONDS);
            caller.join(5000);
        }
    }
}
