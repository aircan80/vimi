package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual Java pipes, with authored deterministic child acknowledgements (no GPU/source claim). */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportRejectedCancelTest {
    @TempDir Path directory;

    @Test
    void decoderNackBeforeQueuedCancelRetiresEachCommandAndSessionRemainsUsable() throws Exception {
        Path source = directory.resolve("Reject.java"),
                seen = directory.resolve("seen"),
                release = directory.resolve("release");
        Files.writeString(
                source,
                """
            class Reject {
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
                    System.out.print(n+"INPUT_CONSUMED "+p[3]+" TILE\\n"+n+"REJECTED "+p[3]+" INVALID_PACKET\\n"+n+"CREDIT 2 0 2\\n");
                  } else if(p[2].equals("CANCEL")) {
                    System.out.print(n+"REJECTED "+p[3]+" STALE_TRANSACTION\\n");
                  } else if(p[2].equals("CANCEL_UNUSED")) {
                    System.out.print(n+"CANCELED "+p[3]+" "+p[4]+" "+p[5]+" "+p[6]+"\\n"+n+"CREDIT 3 0 3\\n");
                  } else if(p[2].equals("CLOSE")) {
                    System.out.print(n+"CLOSED\\n"); System.out.flush(); return;
                  }
                  System.out.flush();
                }
              }
            }
            """);
        var child = new AtomicReference<Process>();
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
                            var process =
                                    new ProcessBuilder(
                                                    Path.of(
                                                                    System.getProperty("java.home"),
                                                                    "bin",
                                                                    "java")
                                                            .toString(),
                                                    source.toString(),
                                                    nonce,
                                                    seen.toString(),
                                                    release.toString())
                                            .start();
                            child.set(process);
                            return process;
                        },
                        WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                        5000);
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            var ticket = reserve(session, 7, 19);
            var result =
                    ticket.produce(
                            (destination, stopping) -> {
                                var context = destination.context();
                                var tile = WorldStreamTileFileTest.full(false);
                                var h = WorldStreamTileFileTest.header(tile, false);
                                var header =
                                        new WorldStreamTileFile.Header(
                                                context.nonce(),
                                                context.scope(),
                                                context.transaction(),
                                                context.lease(),
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
                                                context.path().getParent(),
                                                header,
                                                tile,
                                                null,
                                                stopping);
                                // Deliberate post-publication corruption models the child's
                                // INVALID_PACKET path.
                                try (var channel =
                                        java.nio.channels.FileChannel.open(
                                                context.path(),
                                                java.nio.file.StandardOpenOption.WRITE)) {
                                    channel.write(
                                            java.nio.ByteBuffer.wrap(new byte[] {77}),
                                            WorldStreamTileFile.HEADER_BYTES);
                                }
                                try (var original =
                                        WorldStreamPacketFile.finalized(
                                                expected, context.path(), context.identity())) {
                                    return destination.adopt(original.move(), header);
                                }
                            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(seen) && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertTrue(Files.exists(seen));
            var canceled = ticket.cancel();
            Files.writeString(release, "release");
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> canceled.get(3, TimeUnit.SECONDS));
            var successor = reserve(session, 8, 19);
            var oldCancel =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> ticket.cancel().get(1, TimeUnit.SECONDS));
            assertTrue(
                    oldCancel.getCause() instanceof WorldStreamTransport.CancellationRefusal refusal
                            && refusal.reason()
                                    == WorldStreamTransport.CancellationReason.PACKET_REJECTED);
            assertTrue(child.get().isAlive());
            assertFalse(session.completion().isDone());
            successor.cancel().get(3, TimeUnit.SECONDS);
            var receipt = session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            assertEquals(child.get().pid(), receipt.childPid().orElseThrow());
            assertEquals(0, receipt.exitCode().orElseThrow());
        } finally {
            Files.writeString(release, "release");
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }

    private static WorldStreamTransport.TileReservation reserve(
            WorldStreamTransport.Session session, int x, int y) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            var result = session.tryReserveTile(x, y);
            if (result.isPresent()) {
                return result.orElseThrow();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No genuine successor credit");
    }
}
