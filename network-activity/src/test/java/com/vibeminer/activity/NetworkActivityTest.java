package com.vibeminer.activity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class NetworkActivityTest {
    @Test
    void phasesBytesAndRacedCloseAreIndependent() {
        var clock = new AtomicLong();
        var meter = new NetworkActivity(clock::get);
        var first = meter.begin(TransferObserver.Kind.CDN);
        var second = meter.begin(TransferObserver.Kind.QUESTIE);
        assertEquals(2, meter.snapshot().waitingResponse());
        first.headersReceived();
        first.readStarted();
        assertEquals(1, meter.snapshot().waitingBody());
        first.readFinished(17);
        assertEquals(1, meter.snapshot().handoff());
        second.close();
        second.close();
        second.readFinished(3); // close raced a read which actually received bytes
        second.headersReceived();
        second.readStarted();
        assertEquals(1, meter.snapshot().active());
        assertEquals(20, meter.snapshot().receivedBytes());
        first.readFinished(-1);
        first.close();
        assertEquals(0, meter.snapshot().active());
        assertEquals(0, meter.snapshot().waitingBody());
        assertEquals(0, meter.snapshot().handoff());
        assertEquals(
                3,
                meter.snapshot().categories().stream()
                        .filter(c -> c.kind() == TransferObserver.Kind.QUESTIE)
                        .findFirst()
                        .orElseThrow()
                        .receivedBytes());
    }

    @Test
    void concurrentTerminalAndPositiveReadCountExactlyOnce() throws Exception {
        var meter = new NetworkActivity();
        for (int i = 0; i < 100; i++) {
            var attempt = meter.begin(TransferObserver.Kind.LISTFILE);
            Thread read =
                    Thread.startVirtualThread(
                            () -> {
                                attempt.readStarted();
                                attempt.readFinished(11);
                            });
            Thread close = Thread.startVirtualThread(attempt::close);
            read.join();
            close.join();
            attempt.close();
        }
        assertEquals(1100, meter.snapshot().receivedBytes());
        assertEquals(0, meter.snapshot().active());
    }

    @Test
    void optionalObserverFailuresCannotEscape() {
        var badBegin =
                TransferObserver.safe(
                        kind -> {
                            throw new IllegalStateException();
                        });
        assertDoesNotThrow(() -> badBegin.begin(TransferObserver.Kind.CDN).close());
        var badMethods =
                TransferObserver.safe(
                                kind ->
                                        new TransferObserver.Attempt() {
                                            public void headersReceived() {
                                                throw new IllegalStateException();
                                            }

                                            public void readStarted() {
                                                throw new IllegalStateException();
                                            }

                                            public void readFinished(int count) {
                                                throw new IllegalStateException();
                                            }

                                            public void close() {
                                                throw new IllegalStateException();
                                            }
                                        })
                        .begin(TransferObserver.Kind.CDN);
        assertDoesNotThrow(
                () -> {
                    badMethods.headersReceived();
                    badMethods.readStarted();
                    badMethods.readFinished(12);
                    badMethods.close();
                });
    }

    @Test
    void rateUsesActualTimeAndResetsAfterHiddenInterval() {
        var clock = new AtomicLong();
        var meter = new NetworkActivity(clock::get);
        var rate = new TransferRate();
        var attempt = meter.begin(TransferObserver.Kind.CDN);
        assertTrue(rate.sample(meter.snapshot()).isEmpty());
        clock.set(250_000_000L);
        attempt.readFinished(100);
        assertEquals(400, rate.sample(meter.snapshot()).orElseThrow());
        clock.set(1_000_000_000L);
        attempt.readFinished(100);
        assertEquals(200, rate.sample(meter.snapshot()).orElseThrow());
        rate.reset();
        clock.set(60_000_000_000L);
        attempt.readFinished(10000);
        assertTrue(rate.sample(meter.snapshot()).isEmpty());
        clock.addAndGet(250_000_000L);
        assertEquals(0, rate.sample(meter.snapshot()).orElseThrow());
        assertEquals(10200, meter.snapshot().receivedBytes());
        assertTrue(rate.sample(meter.snapshot()).isEmpty()); // zero elapsed rebaselines
        attempt.close();
    }
}
