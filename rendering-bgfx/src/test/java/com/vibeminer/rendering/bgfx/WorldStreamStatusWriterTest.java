package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorldStreamStatusWriterTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, 2);

    @Test
    void capacityIncludesBlockedInFlightAndFinishNeverClosesOrWaitsForItsMonitor()
            throws Exception {
        var output = new HeldOutput();
        var clock = new WorldStatusDeadline();
        var writer = new WorldStreamStatusWriter(NONCE, output, clock);
        assertTrue(writer.reliable("SESSION TERRAIN_BASIC_V1"));
        assertTrue(output.entered.await(2, TimeUnit.SECONDS));
        for (int i = 1; i < 16; i++) {
            assertTrue(writer.reliable("INPUT_CONSUMED " + i + " TILE"));
        }
        assertEquals(16, clock.outstanding());
        assertFalse(writer.reliable("CLOSED"));
        var finish = assertTimeoutPreemptively(Duration.ofSeconds(1), writer::finish);
        assertFalse(finish.isDone());
        assertFalse(writer.drained());
        assertFalse(writer.reliable("CLOSED"));
        output.release.countDown();
        finish.get(3, TimeUnit.SECONDS);
        assertTrue(writer.drained());
        assertEquals(0, clock.outstanding());
        assertEquals(16, output.bytes.toString(StandardCharsets.US_ASCII).lines().count());
        assertFalse(output.closed);
    }

    @Test
    void cameraFloodRemainsLatestOnlyAndDoesNotRefreshReliableProgress() throws Exception {
        var output = new HeldOutput();
        var clock = new WorldStatusDeadline();
        var writer = new WorldStreamStatusWriter(NONCE, output, clock);
        writer.reliable("SESSION TERRAIN_BASIC_V1");
        assertTrue(output.entered.await(2, TimeUnit.SECONDS));
        for (int i = 1; i <= 10_000; i++) {
            assertTrue(writer.cameraApplied(i, 800, 500));
        }
        assertEquals(1, clock.outstanding());
        writer.reliable("CLOSED");
        var finish = writer.finish();
        output.release.countDown();
        finish.get(3, TimeUnit.SECONDS);
        var lines = output.bytes.toString(StandardCharsets.US_ASCII).lines().toList();
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("SESSION TERRAIN_BASIC_V1"));
        assertTrue(lines.get(1).endsWith("CLOSED"));
    }

    @Test
    void presentedHintsCoalesceToLatestAndNeverFollowQuietTerminal() throws Exception {
        var output = new HeldOutput();
        var clock = new WorldStatusDeadline();
        var writer = new WorldStreamStatusWriter(NONCE, output, clock);
        writer.reliable("SESSION TERRAIN_BASIC_V1");
        assertTrue(output.entered.await(2, TimeUnit.SECONDS));
        for (int i = 1; i <= 500; i++) {
            assertTrue(writer.presented(i));
        }
        assertEquals(1, clock.outstanding(), "Presented hints never take reliable credit");
        output.release.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (output.lines().size() < 2 && System.nanoTime() < until) {
            Thread.sleep(1);
        }
        assertEquals(
                java.util.List.of("SESSION TERRAIN_BASIC_V1", "PRESENTED 500"), output.lines());
        writer.quiet();
        assertFalse(writer.presented(501));
        writer.reliable("CLOSED");
        writer.finish().get(3, TimeUnit.SECONDS);
        assertEquals("CLOSED", output.lines().getLast());
        assertThrows(IllegalArgumentException.class, () -> writer.presented(0));
    }

    @Test
    void frameHintsCoalesceToLatestAndNeverFollowQuietTerminal() throws Exception {
        var output = new HeldOutput();
        var clock = new WorldStatusDeadline();
        var writer = new WorldStreamStatusWriter(NONCE, output, clock);
        writer.reliable("SESSION TERRAIN_BASIC_V1");
        assertTrue(output.entered.await(2, TimeUnit.SECONDS));
        for (int i = 1; i <= 1000; i++) {
            assertTrue(writer.framePublished(i));
        }
        assertTrue(writer.cameraApplied(1, 800, 500));
        assertEquals(1, clock.outstanding());
        output.release.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (output.lines().size() < 3 && System.nanoTime() < until) {
            Thread.sleep(1);
        }
        assertEquals(
                java.util.List.of(
                        "SESSION TERRAIN_BASIC_V1", "CAMERA_APPLIED 1 800 500", "FRAME 1000"),
                output.lines());
        writer.quiet();
        assertFalse(writer.framePublished(1001));
        assertFalse(writer.cameraApplied(2, 800, 500));
        writer.reliable("CLOSED");
        writer.finish().get(3, TimeUnit.SECONDS);
        assertEquals("CLOSED", output.lines().getLast());
        assertEquals(4, output.lines().size());
        assertThrows(IllegalArgumentException.class, () -> writer.framePublished(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> writer.framePublished(WorldFrameFileV2.MAX_SEQUENCE + 1));
    }

    @Test
    void quietDropsPendingHintsQueuedBehindAReliableWrite() throws Exception {
        var output = new HeldOutput();
        var writer = new WorldStreamStatusWriter(NONCE, output, new WorldStatusDeadline());
        writer.reliable("SESSION TERRAIN_BASIC_V1");
        assertTrue(output.entered.await(2, TimeUnit.SECONDS));
        assertTrue(writer.framePublished(5));
        assertTrue(writer.cameraApplied(1, 800, 500));
        writer.quiet();
        writer.reliable("CLOSED");
        var finish = writer.finish();
        output.release.countDown();
        finish.get(3, TimeUnit.SECONDS);
        assertEquals(java.util.List.of("SESSION TERRAIN_BASIC_V1", "CLOSED"), output.lines());
    }

    @Test
    void invalidLinesRejectBeforeCreditAndWriteFailureFinishesExceptionally() throws Exception {
        var clock = new WorldStatusDeadline();
        var writer =
                new WorldStreamStatusWriter(
                        NONCE,
                        new OutputStream() {
                            @Override
                            public void write(int value) throws IOException {
                                throw new IOException("authored closed pipe");
                            }
                        },
                        clock);
        assertThrows(IllegalArgumentException.class, () -> writer.reliable("CLOSED\nSESSION"));
        assertThrows(IllegalArgumentException.class, () -> writer.reliable("X".repeat(256)));
        assertEquals(0, clock.outstanding());
        assertTrue(writer.reliable("SESSION TERRAIN_BASIC_V1"));
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> writer.finish().get(3, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, writer.failure());
        assertFalse(writer.reliable("CLOSED"));
    }

    private static final class HeldOutput extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean closed;

        @Override
        public synchronized void write(byte[] value) throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("authored gate timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
            bytes.write(value);
        }

        synchronized java.util.List<String> lines() {
            return bytes.toString(StandardCharsets.US_ASCII)
                    .lines()
                    .map(line -> line.substring(line.indexOf(' ', 4) + 1))
                    .toList();
        }

        @Override
        public void write(int value) {
            throw new AssertionError("bulk write required");
        }

        @Override
        public synchronized void close() {
            closed = true;
        }
    }
}
