package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
@SuppressWarnings("try")
class WorldStreamTransportProducerTest {
    @TempDir Path directory;

    @Test
    void perTileCancelWaitsActualSourceCallbackFinallyDespiteProducerAndFutureCompletion()
            throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        var ended = new CountDownLatch(1);
        var commands = new ByteArrayOutputStream();
        child.output = commands;
        child.onExit().thenRun(ended::countDown);
        var path = new AtomicReference<Path>();
        var prepared =
                WorldStreamTransport.prepare(
                        directory,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
        var session =
                prepared.launch(
                        arguments -> {
                            path.set(Path.of(arguments.get(1)));
                            var descriptor =
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(path.get().resolve("session.wss")),
                                            arguments.get(3));
                            String prefix =
                                    "WS2 "
                                            + HexFormat.of().toHexDigits(descriptor.nonce().high())
                                            + HexFormat.of().toHexDigits(descriptor.nonce().low())
                                            + " ";
                            byte[] initial =
                                    (prefix
                                                    + "SESSION TERRAIN_BASIC_V1\n"
                                                    + prefix
                                                    + "CREDIT 1 0 1\n")
                                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                            child.input =
                                    new java.io.InputStream() {
                                        int offset;

                                        @Override
                                        public int read() throws java.io.IOException {
                                            if (offset < initial.length) {
                                                return initial[offset++] & 255;
                                            }
                                            try {
                                                ended.await();
                                            } catch (InterruptedException error) {
                                                throw new java.io.IOException(error);
                                            }
                                            return -1;
                                        }
                                    };
                            return child;
                        },
                        1000,
                        100);
        var source = new Cancellation();
        var producerReady = new CountDownLatch(1);
        var lowerEntered = new CountDownLatch(1);
        var releaseLower = new CountDownLatch(1);
        var releaseProducer = new CountDownLatch(1);
        var producerReturned = new CountDownLatch(1);
        var registration =
                source.onCancel(
                        () -> {
                            lowerEntered.countDown();
                            try {
                                releaseLower.await();
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            }
                        });
        try {
            session.initialized().get(2, TimeUnit.SECONDS);
            WorldStreamTransport.TileReservation ticket = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ticket == null && System.nanoTime() < until) {
                ticket = session.tryReserveTile(7, 19).orElse(null);
                if (ticket == null) {
                    Thread.sleep(2);
                }
            }
            if (ticket == null) {
                throw new AssertionError("No authored credit");
            }
            var result =
                    ticket.produce(
                            (destination, stopping) -> {
                                try (var stop = destination.onStopping(source::cancel)) {
                                    producerReady.countDown();
                                    releaseProducer.await();
                                    throw new java.util.concurrent.CancellationException(
                                            "authored canceled source");
                                } finally {
                                    producerReturned.countDown();
                                }
                            });
            assertTrue(producerReady.await(2, TimeUnit.SECONDS));
            ticket.cancel();
            assertTrue(lowerEntered.await(2, TimeUnit.SECONDS));
            releaseProducer.countDown();
            assertTrue(producerReturned.await(2, TimeUnit.SECONDS));
            assertTrue(session.tryReserveTile(8, 19).isEmpty());
            assertFalse(
                    commands.toString(java.nio.charset.StandardCharsets.US_ASCII)
                            .contains("CANCEL_UNUSED"));
            var closing = session.closeAsync();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> closing.get(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS)); // Logical failure is NOT source
            // callback/resource drain.
            assertFalse(session.terminatedAndDrained().isDone());
            assertTrue(Files.exists(path.get().resolve("frames.wsf")));
        } finally {
            releaseProducer.countDown();
            releaseLower.countDown();
            registration.close();
            session.closeAsync();
            session.terminatedAndDrained().get(4, TimeUnit.SECONDS);
        }
        assertFalse(child.isAlive());
        assertFalse(Files.exists(path.get()));
    }
}
