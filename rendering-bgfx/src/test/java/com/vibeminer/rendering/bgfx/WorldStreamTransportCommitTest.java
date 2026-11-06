package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportCommitTest {
    @TempDir Path directory;

    @Test
    void queuedCommitOwnsBoundedCallerSnapshotAndRejectsCancellationBeforeItsWorkerRuns()
            throws Exception {
        Path source = directory.resolve("Commit.java");
        Files.writeString(
                source,
                """
            class Commit {
              public static void main(String[] a) throws Exception {
                String n="WS2 "+a[0]+" ";
                System.out.print(n+"SESSION TERRAIN_BASIC_V1\\n"+n+"CREDIT 1 0 1\\n");System.out.flush();
                var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while((line=input.readLine())!=null){
                  String[] p=line.split(" ");
                  if(p[2].equals("OFFER")){
                    System.out.print(n+"INPUT_CONSUMED "+p[3]+" TILE\\n"+n+"STAGED "+p[3]+" "+p[4]+" "+p[5]+" "+p[6]+" 2 0\\n");
                  }else if(p[2].equals("COMMIT")){
                    byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a[1]).resolve("commit-"+p[3]+".wsc"));
                    var b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    if(b.getInt(64)!=1 || b.get(72)!=0 || b.getLong(80)!=1){throw new AssertionError("Caller snapshot changed");}
                    System.out.print(n+"INPUT_CONSUMED "+p[3]+" COMMIT\\n"+n+"COMMITTED "+p[3]+" "+b.getLong(40)+" "+b.getLong(48)+"\\n"+n+"CREDIT 2 1 1\\n");
                  }else if(p[2].equals("CANCEL")){throw new AssertionError("Pending/active commit cancellation must be local refusal");}
                  else if(p[2].equals("CLOSE")){System.out.print(n+"CLOSED\\n");System.out.flush();return;}
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
                                            arguments.get(1))
                                    .start();
                        },
                        WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                        5000);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var file = new AtomicReference<Path>();
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> session.commit(1, new WorldStreamTransport.Resident[10]));
            WorldStreamTransport.TileReservation ticket = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ticket == null && System.nanoTime() < until) {
                ticket = session.tryReserveTile(7, 19).orElse(null);
                if (ticket == null) {
                    Thread.sleep(2);
                }
            }
            if (ticket == null) {
                throw new AssertionError("No initial credit");
            }
            var resident =
                    ticket.produce(
                                    (destination, stopping) -> {
                                        var c = destination.context();
                                        file.set(c.path());
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
                                                        c.path().getParent(),
                                                        header,
                                                        tile,
                                                        null,
                                                        stopping);
                                        try (var original =
                                                WorldStreamPacketFile.finalized(
                                                        expected, c.path(), c.identity())) {
                                            return destination.adopt(original.move(), header);
                                        }
                                    })
                            .get(3, TimeUnit.SECONDS);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Files.exists(file.get()) && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertFalse(Files.exists(file.get()));
            var enqueue =
                    WorldStreamTransport.Session.class.getDeclaredMethod(
                            "enqueueFile", Runnable.class);
            enqueue.setAccessible(true);
            enqueue.invoke(
                    session,
                    (Runnable)
                            () -> {
                                held.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException error) {
                                    throw new AssertionError(error);
                                }
                            });
            assertTrue(held.await(2, TimeUnit.SECONDS));
            var caller = new WorldStreamTransport.Resident[] {resident};
            var committing = session.commit(1, caller);
            caller[0] = null;
            var chosen = ticket;
            var refusal =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> chosen.cancel().get(1, TimeUnit.SECONDS));
            assertTrue(
                    refusal.getCause() instanceof WorldStreamTransport.CancellationRefusal reason
                            && reason.reason()
                                    == WorldStreamTransport.CancellationReason.COMMIT_PENDING);
            var stop = chosen.getClass().getDeclaredField("stop");
            stop.setAccessible(true);
            assertFalse(stop.getBoolean(chosen));
            assertFalse(committing.isDone());
            release.countDown();
            var receipt = committing.get(3, TimeUnit.SECONDS);
            assertEquals(1, receipt.contentRevision());
            assertEquals(1, receipt.policy());
            assertSame(resident, receipt.ordered().getFirst());
            assertThrows(UnsupportedOperationException.class, () -> receipt.ordered().clear());
            var committed =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> chosen.cancel().get(1, TimeUnit.SECONDS));
            assertTrue(
                    committed.getCause() instanceof WorldStreamTransport.CancellationRefusal reason
                            && reason.reason()
                                    == WorldStreamTransport.CancellationReason.ALREADY_COMMITTED);
            assertTrue(
                    session.takeLatestFrame()
                            .isEmpty()); // No camera ACK or mapped pixels from this child.
            session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }
}
