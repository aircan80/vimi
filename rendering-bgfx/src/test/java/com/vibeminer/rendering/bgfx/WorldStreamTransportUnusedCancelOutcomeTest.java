package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tile whose producer refuses ends before its offer: the session sends the child an unused cancel
 * and completes the tile with the refusal. The child's CANCELED reply can be read before the file
 * worker completes the result. The tile must still end with the refusal, not a plain cancellation:
 * a caller treats a refusal as a handled outcome (unavailable, retry later), while a cancellation
 * it did not ask for fails World streaming.
 */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportUnusedCancelOutcomeTest {
    @TempDir Path directory;

    @Test
    void aRefusalSurvivesTheCanceledReplyArrivingFirst() throws Exception {
        var session = launch();
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            // Hold the file worker after it queued the unused cancel until the status reader has
            // handled the CANCELED reply: it has once it reads the CREDIT the child sends next.
            var canceledSeen = new AtomicBoolean();
            var replyHandled = new CountDownLatch(1);
            session.statusSeam =
                    change -> {
                        if (change == WorldStreamParentState.Change.CANCELED) {
                            canceledSeen.set(true);
                        } else if (change == WorldStreamParentState.Change.CREDIT
                                && canceledSeen.get()) {
                            replyHandled.countDown();
                        }
                    };
            var held = new CompletableFuture<Boolean>();
            session.unusedCancelSeam =
                    () -> {
                        try {
                            held.complete(replyHandled.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            held.complete(false);
                        }
                    };
            var reservation = reserve(session);
            var result =
                    reservation.produce(
                            (destination, stopping) -> {
                                throw destination.refusal(
                                        WorldStreamTransport.Refusal.CAPABILITY_UNAVAILABLE,
                                        "authored missing source");
                            });
            var failure =
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS))
                            .getCause();
            assertTrue(
                    held.get(5, TimeUnit.SECONDS),
                    "The CANCELED reply was handled before the worker completed the tile");
            var refusal = assertInstanceOf(WorldStreamTransport.PreparationRefusal.class, failure);
            assertEquals(
                    WorldStreamTransport.Refusal.CAPABILITY_UNAVAILABLE,
                    refusal.reason(),
                    "The refusal, not the unused cancel, is the tile's outcome");
            session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }

    /** A child that grants one lease and answers CANCEL_UNUSED at once, then credits it back. */
    private WorldStreamTransport.Session launch() throws Exception {
        Path source = directory.resolve("Unused.java");
        Files.writeString(
                source,
                """
            class Unused {
              public static void main(String[] a) throws Exception {
                String n="WS2 "+a[0]+" ";
                int credit=1;
                System.out.print(n+"SESSION TERRAIN_BASIC_V1\\n"+n+"CREDIT 1 0 1\\n"); System.out.flush();
                var in=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while((line=in.readLine())!=null) {
                  String[] p=line.split(" ");
                  if(p[2].equals("CANCEL_UNUSED")) {
                    credit++;
                    System.out.print(n+"CANCELED "+p[3]+" "+p[4]+" "+p[5]+" "+p[6]+"\\n"
                        +n+"CREDIT "+credit+" 0 "+credit+"\\n");
                  } else if(p[2].equals("CLOSE")) {
                    System.out.print(n+"CLOSED\\n"); System.out.flush(); return;
                  }
                  System.out.flush();
                }
              }
            }
            """);
        var prepared =
                WorldStreamTransport.prepare(
                        directory,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
        return prepared.launch(
                arguments -> {
                    var descriptor =
                            WorldStreamEnvelope.decodeSession(
                                    Files.readAllBytes(
                                            Path.of(arguments.get(1)).resolve("session.wss")),
                                    arguments.get(3));
                    String nonce =
                            HexFormat.of().toHexDigits(descriptor.nonce().high())
                                    + HexFormat.of().toHexDigits(descriptor.nonce().low());
                    return new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    source.toString(),
                                    nonce)
                            .start();
                },
                WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                5000,
                WorldStreamTestChildren.EXIT_GRACE_MILLIS);
    }

    private static WorldStreamTransport.TileReservation reserve(
            WorldStreamTransport.Session session) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            var reserved = session.tryReserveTile(7, 19);
            if (reserved.isPresent()) {
                return reserved.get();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("Missing child credit");
    }
}
