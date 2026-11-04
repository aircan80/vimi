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
@Timeout(20)
class WorldStreamTransportFileCancelTest {
    @TempDir Path directory;

    @Test
    void canceledAckAndCreditCannotCompleteCancelBeforeActualInputDeletion() throws Exception {
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
                        });
        var fileEntered = new CountDownLatch(1);
        var releaseFile = new CountDownLatch(1);
        try {
            session.initialized().get(8, TimeUnit.SECONDS);
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
                                    return destination.adopt(original.move(), header);
                                }
                            });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(seen) && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertTrue(Files.exists(seen));
            var enqueue =
                    WorldStreamTransport.Session.class.getDeclaredMethod(
                            "enqueueFile", Runnable.class);
            enqueue.setAccessible(true);
            enqueue.invoke(
                    session,
                    (Runnable)
                            () -> {
                                fileEntered.countDown();
                                try {
                                    releaseFile.await();
                                } catch (InterruptedException error) {
                                    throw new AssertionError(error);
                                }
                            });
            assertTrue(fileEntered.await(2, TimeUnit.SECONDS));
            Files.writeString(release, "release");
            result.get(3, TimeUnit.SECONDS);
            long before = session.capacityRevision();
            var canceled = ticket.cancel();
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (session.capacityRevision() == before && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertTrue(
                    session.capacityRevision() > before); // Reader consumed CANCELED then CREDIT.
            assertFalse(canceled.isDone());
            assertTrue(Files.exists(packetPath.get()));
            assertTrue(session.tryReserveTile(8, 19).isEmpty());
            releaseFile.countDown();
            canceled.get(3, TimeUnit.SECONDS);
            assertFalse(Files.exists(packetPath.get()));
            ticket.cancel()
                    .get(1, TimeUnit.SECONDS); // Already canceled is bounded and emits no new
            // command.
            session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            releaseFile.countDown();
            Files.writeString(release, "release");
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }
}
