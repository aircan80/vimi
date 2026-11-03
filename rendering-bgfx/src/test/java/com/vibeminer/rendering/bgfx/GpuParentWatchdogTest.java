package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
final class GpuParentWatchdogTest {
    @Test
    void parentLossHasIndependentDeadlineEvenWhenNativeMainNeverResponds() throws Exception {
        var parentExit = new CompletableFuture<Void>();
        var forced = new CompletableFuture<Void>();
        try (var control = new PipedInputStream();
                var writer = new PipedOutputStream(control);
                var watchdog =
                        new GpuParentWatchdog(
                                parentExit,
                                control,
                                Duration.ofMillis(20),
                                () -> forced.complete(null))) {
            writer.write('X');
            writer.flush();
            parentExit.complete(null);
            assertTrue(watchdog.stopping());
            forced.get(1, TimeUnit.SECONDS);
            watchdog.deadlineDrained().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void controlLossRequestsExitButNormalCleanupPreventsForcedTermination() throws Exception {
        var forced = new AtomicInteger();
        var watchdog =
                new GpuParentWatchdog(
                        new CompletableFuture<>(),
                        new ByteArrayInputStream(
                                "CLOSE\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                        Duration.ofMillis(100),
                        forced::incrementAndGet);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!watchdog.stopping()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Control stop was not observed");
            }
            Thread.sleep(1);
        }
        watchdog.close();
        watchdog.deadlineDrained().get(1, TimeUnit.SECONDS);
        assertEquals(0, forced.get());
    }

    @Test
    void knownSoftwareAdaptersCannotBeAdvertisedAsHardwareReady() throws Exception {
        for (int vendor : new int[] {1, 5}) {
            assertThrows(IOException.class, () -> GpuViewerMain.requireHardware(vendor, 0));
        }
        assertThrows(IOException.class, () -> GpuViewerMain.requireHardware(0x1414, 0x008c));
        GpuViewerMain.requireHardware(0x8086, 0xa780);
    }
}
