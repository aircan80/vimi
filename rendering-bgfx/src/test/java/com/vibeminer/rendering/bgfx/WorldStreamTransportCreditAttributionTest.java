package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #534: the status reader counts a CREDIT as a capacity change of the reservation whose lease it
 * returned, so that reservation's own credit never looks like capacity freed elsewhere, while every
 * other reservation sees it.
 */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportCreditAttributionTest {
    @TempDir Path directory;

    @Test
    void aCreditCountsForTheReservationWhoseLeaseItReturnedOnly() throws Exception {
        Path source = directory.resolve("Credits.java");
        Files.writeString(
                source,
                """
            class Credits {
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
                                            nonce)
                                    .start();
                        },
                        WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                        5000,
                        WorldStreamTestChildren.EXIT_GRACE_MILLIS);
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            var first = reserve(session);
            assertFalse(first.capacityChangedElsewhere());
            long before = session.capacityRevision();
            first.cancel().get(5, TimeUnit.SECONDS);
            // The next reservation needs the CREDIT that returned the first one's lease.
            var second = reserve(session);
            assertTrue(session.capacityRevision() > before, "The CREDIT changed capacity");
            assertFalse(
                    first.capacityChangedElsewhere(),
                    "The credit returning its own lease is the first reservation's change");
            assertFalse(second.capacityChangedElsewhere());
            second.cancel().get(5, TimeUnit.SECONDS);
            var third = reserve(session);
            assertFalse(
                    second.capacityChangedElsewhere(),
                    "The credit returning its own lease is the second reservation's change");
            assertTrue(
                    first.capacityChangedElsewhere(),
                    "The second reservation's credit is capacity freed elsewhere for the first");
            third.cancel().get(5, TimeUnit.SECONDS);
            session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
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
