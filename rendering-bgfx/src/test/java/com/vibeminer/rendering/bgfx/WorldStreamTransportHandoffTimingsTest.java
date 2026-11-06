package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.formats.world.WorldStageTimings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in hand-off timings of one tile, reserve to STAGED, over actual pipes and an authored child.
 */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportHandoffTimingsTest {
    @TempDir Path directory;

    static final List<String> LEGS =
            List.of(
                    "handoff.parent.reserveToProduce",
                    "handoff.parent.produceToProducerStart",
                    "handoff.parent.producerRunToOffer",
                    "handoff.parent.offerToInputConsumed",
                    "handoff.parent.inputConsumedToStaged");

    @AfterEach
    void restore() {
        WorldStageTimings.enable(false);
        WorldStageTimings.reset();
    }

    @Test
    void enabledTimingsRecordEveryParentLegOfOneTile() throws Exception {
        WorldStageTimings.reset();
        WorldStageTimings.enable(true);
        stageOneTile();
        var snapshot = WorldStageTimings.snapshot();
        for (String leg : LEGS) {
            assertEquals(1, snapshot.get(leg) == null ? 0 : snapshot.get(leg).count(), leg);
        }
    }

    @Test
    void disabledTimingsRecordNoLeg() throws Exception {
        WorldStageTimings.reset();
        WorldStageTimings.enable(false);
        stageOneTile();
        for (String leg : LEGS) {
            assertNull(WorldStageTimings.snapshot().get(leg), leg);
        }
    }

    private void stageOneTile() throws Exception {
        Path source = directory.resolve("Stage.java");
        Files.writeString(
                source,
                """
            class Stage {
              public static void main(String[] a) throws Exception {
                String n="WS2 "+a[0]+" ";
                System.out.print(n+"SESSION TERRAIN_BASIC_V1\\n"+n+"CREDIT 1 0 1\\n");System.out.flush();
                var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while((line=input.readLine())!=null){
                  String[] p=line.split(" ");
                  if(p[2].equals("OFFER")){
                    System.out.print(n+"INPUT_CONSUMED "+p[3]+" TILE\\n"+n+"STAGED "+p[3]+" "+p[4]+" "+p[5]+" "+p[6]+" 2 0\\n");
                  }else if(p[2].equals("CLOSE")){System.out.print(n+"CLOSED\\n");System.out.flush();return;}
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
        var file = new AtomicReference<Path>();
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
                throw new AssertionError("No initial credit");
            }
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
                                                c.path().getParent(), header, tile, null, stopping);
                                try (var original =
                                        WorldStreamPacketFile.finalized(
                                                expected, c.path(), c.identity())) {
                                    return destination.adopt(original.move(), header);
                                }
                            })
                    .get(3, TimeUnit.SECONDS);
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }
}
