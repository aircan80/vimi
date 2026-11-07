package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Controlled real child pipes hold parent input deletion after an authentic-shaped cancellation.
 */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportOfferCancelBoundaryTest {
    @TempDir Path directory;

    @Test
    void cancelDuringFinalPacketClaimDoesNotFailSessionOrReverseOfferOrder() throws Exception {
        Path source = directory.resolve("Cancel.java"),
                seen = directory.resolve("seen"),
                release = directory.resolve("release");
        Files.writeString(
                source,
                """
            class Cancel {
              public static void main(String[] a) throws Exception {
                String n="WS2 "+a[0]+" ";
                System.out.print(n+"SESSION TERRAIN_BASIC_V1\\n"+n+"CREDIT 1 0 1\\n"); System.out.flush();
                var in=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while((line=in.readLine())!=null) {
                  String[] p=line.split(" ");
                  if(p[2].equals("OFFER")) {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(a[1]),"seen");
                    while(!java.nio.file.Files.exists(java.nio.file.Path.of(a[2]))) { Thread.sleep(2); }
                    System.out.print(n+"INPUT_CONSUMED "+p[3]+" TILE\\n"+n+"STAGED "+p[3]+" "+p[4]+" "+p[5]+" "+p[6]+" 2 0\\n");
                  } else if(p[2].equals("CANCEL")) {
                    System.out.print(n+"CANCELED "+p[3]+" 1 "+p[4]+" "+p[5]+"\\n"+n+"CREDIT 2 0 2\\n");
                  } else if(p[2].equals("CLOSE")) {
                    System.out.print(n+"CLOSED\\n"); System.out.flush(); return;
                  }
                  System.out.flush();
                }
              }
            }
            """);
        var packetPath = new AtomicReference<Path>();
        var prepared =
                WorldStreamTransport.prepare(
                        directory,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
        var session =
                prepared.launch(
                        arguments -> {
                            var descriptor =
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(
                                                    Path.of(arguments.get(1))
                                                            .resolve("session.wss")),
                                            arguments.get(3));
                            String nonce =
                                    HexFormat.of().toHexDigits(descriptor.nonce().high())
                                            + HexFormat.of().toHexDigits(descriptor.nonce().low());
                            return new ProcessBuilder(
                                            Path.of(System.getProperty("java.home"), "bin", "java")
                                                    .toString(),
                                            source.toString(),
                                            nonce,
                                            seen.toString(),
                                            release.toString())
                                    .start();
                        },
                        WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                        5000,
                        WorldStreamTestChildren.EXIT_GRACE_MILLIS);
        var packetReady = new CountDownLatch(1);
        var allowReturn = new CountDownLatch(1);
        var packet = new AtomicReference<WorldStreamTransport.OwnedPacket>();
        var producerThread = new AtomicReference<Thread>();
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            WorldStreamTransport.TileReservation ticket = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ticket == null && System.nanoTime() < until) {
                ticket = session.tryReserveTile(7, 19).orElse(null);
                if (ticket == null) {
                    Thread.sleep(2);
                }
            }
            if (ticket == null) {
                throw new AssertionError("Missing child credit");
            }
            var result =
                    ticket.produce(
                            (destination, stopping) -> {
                                var c = destination.context();
                                packetPath.set(c.path());
                                var tile = WorldStreamTileFileTest.full(false);
                                var h = WorldStreamTileFileTest.header(tile, false);
                                var header =
                                        new WorldStreamTileFile.Header(
                                                c.nonce(),
                                                c.scope(),
                                                c.transaction(),
                                                c.lease(),
                                                h.x(),
                                                h.y(),
                                                h.chunks(),
                                                h.vertices(),
                                                h.indices(),
                                                h.geometrySha256(),
                                                h.materialDigest(),
                                                h.outcome(),
                                                h.reason(),
                                                h.coverage(),
                                                h.maxX(),
                                                h.maxY(),
                                                h.uvError(),
                                                h.wdt(),
                                                h.root(),
                                                h.tex0(),
                                                h.diffuse());
                                var expected =
                                        WorldStreamTileFile.write(
                                                c.path().getParent(), header, tile, null, stopping);
                                try (var original =
                                        WorldStreamPacketFile.finalized(
                                                expected, c.path(), c.identity())) {
                                    var value = destination.adopt(original.move(), header);
                                    packet.set(value);
                                    producerThread.set(Thread.currentThread());
                                    packetReady.countDown();
                                    allowReturn.await();
                                    return value;
                                }
                            });
            assertTrue(packetReady.await(3, TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture<Void> cancellation;
            Object claimMonitor;
            try {
                var monitor = packet.get().getClass().getDeclaredField("ownershipLock");
                monitor.setAccessible(true);
                claimMonitor = monitor.get(packet.get());
            } catch (NoSuchFieldException oldImplementation) {
                claimMonitor = packet.get();
            }
            synchronized (claimMonitor) {
                allowReturn.countDown();
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean claiming = false;
                while (!claiming && System.nanoTime() < until) {
                    for (var frame : producerThread.get().getStackTrace()) {
                        if (frame.getClassName().endsWith("WorldStreamTransport$OwnedPacket")
                                && (frame.getMethodName().equals("claim")
                                        || frame.getMethodName().equals("expected"))) {
                            claiming = true;
                        }
                    }
                    if (!claiming) {
                        Thread.sleep(2);
                    }
                }
                assertTrue(claiming, "Producer blocked at final packet claim after stop decision");
                var requested = new CountDownLatch(1);
                var selected = ticket;
                cancellation = new java.util.concurrent.CompletableFuture<>();
                var completed = cancellation;
                var cancelThread =
                        Thread.ofVirtual()
                                .name("authored-offer-cancel")
                                .start(
                                        () -> {
                                            requested.countDown();
                                            try {
                                                selected.cancel().join();
                                                completed.complete(null);
                                            } catch (Throwable error) {
                                                completed.completeExceptionally(error);
                                            }
                                        });
                assertTrue(requested.await(2, TimeUnit.SECONDS));
                // On the old implementation cancel() passes the unlocked offer boundary.
                // The corrected implementation may instead block behind the atomic offer decision.
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                var stopField = ticket.getClass().getDeclaredField("stop");
                stopField.setAccessible(true);
                while (!stopField.getBoolean(ticket)
                        && cancelThread.getState() != Thread.State.BLOCKED
                        && System.nanoTime() < until) {
                    Thread.sleep(2);
                }
                assertTrue(
                        stopField.getBoolean(ticket)
                                || cancelThread.getState() == Thread.State.BLOCKED,
                        "Cancellation reached the atomic publication boundary");
            }
            Files.writeString(release, "release");
            cancellation.get(4, TimeUnit.SECONDS);
            assertFalse(session.completion().isDone(), "Clean tile cancel must preserve session");
            assertFalse(Files.exists(packetPath.get()));
            session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            allowReturn.countDown();
            Files.writeString(release, "release");
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }
}
