package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CancellationTest {
    @Test
    void removedListenersAreNotCalledAndNoneIsImmutable() {
        Cancellation token = new Cancellation();
        AtomicInteger calls = new AtomicInteger();
        var registration = token.onCancel(calls::incrementAndGet);
        registration.close();
        registration.close();
        token.cancel();
        Cancellation.NONE.onCancel(calls::incrementAndGet);
        Cancellation.NONE.cancel();
        assertEquals(0, calls.get());
    }

    @Test
    void alreadyCanceledAndRepeatedCancellationNotifyExactlyOnce() {
        Cancellation token = new Cancellation();
        AtomicInteger calls = new AtomicInteger();
        token.cancel();
        token.onCancel(calls::incrementAndGet);
        token.cancel();
        assertEquals(1, calls.get());
    }

    @Test
    void cancellationAndListenerRegistrationCannotMissEachOther() throws Exception {
        for (int i = 0; i < 20; i++) {
            Cancellation token = new Cancellation();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Void> registered = new CompletableFuture<>();
            CompletableFuture<Void> canceled = new CompletableFuture<>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    start.await();
                                    token.onCancel(calls::incrementAndGet);
                                    registered.complete(null);
                                } catch (Throwable e) {
                                    registered.completeExceptionally(e);
                                }
                            });
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    start.await();
                                    token.cancel();
                                    canceled.complete(null);
                                } catch (Throwable e) {
                                    canceled.completeExceptionally(e);
                                }
                            });
            start.countDown();
            CompletableFuture.allOf(registered, canceled).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        }
    }
}
