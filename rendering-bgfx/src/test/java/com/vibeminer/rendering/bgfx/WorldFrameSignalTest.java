package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class WorldFrameSignalTest {
    @Test
    void signalWakesAWaitingReaderLongBeforeItsFallback() throws Exception {
        var signal = new WorldFrameSignal();
        var waiting = new CompletableFuture<Long>();
        var reader =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        long start = System.nanoTime();
                                        assertTrue(signal.await(60_000));
                                        waiting.complete(System.nanoTime() - start);
                                    } catch (Throwable error) {
                                        waiting.completeExceptionally(error);
                                    }
                                });
        Thread.sleep(50);
        signal.signal();
        long waited = waiting.get(5, TimeUnit.SECONDS);
        reader.join();
        assertTrue(waited < TimeUnit.SECONDS.toNanos(5), "woken by the signal, not the fallback");
    }

    @Test
    void signalsBeforeAWaitCoalesceIntoOneWakeUp() throws Exception {
        var signal = new WorldFrameSignal();
        for (int i = 0; i < 100; i++) {
            signal.signal();
        }
        assertTrue(signal.await(60_000));
        long start = System.nanoTime();
        assertFalse(signal.await(20));
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(15));
    }

    @Test
    void absentSignalFallsBackToTheBoundedPoll() throws Exception {
        var signal = new WorldFrameSignal();
        assertFalse(signal.await(0));
        assertFalse(signal.await(5));
        signal.signal();
        assertTrue(signal.await(0));
        assertEquals(false, signal.await(0));
    }
}
