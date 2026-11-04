package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class WorldStreamTransportTest {
    @TempDir Path directory;
    private final WorldStreamTransport.Spec spec =
            new WorldStreamTransport.Spec("ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN);

    @Test
    void actualJavaProtocolChildCameraCloseAndDrainOwnPrivateFiles() throws Exception {
        Path source = directory.resolve("Protocol.java");
        Files.writeString(
                source,
                """
            class Protocol {
              public static void main(String[] a) throws Exception {
                String prefix="WS2 "+a[0]+" ";
                System.out.print(prefix+"SESSION TERRAIN_BASIC_V1\\n"+prefix+"CREDIT 1 0 1\\n");
                System.out.flush();
                var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while((line=input.readLine())!=null) {
                  String[] p=line.split(" ");
                  if(p[2].equals("CAM")) {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(a[1]),line);
                    System.out.print(prefix+"CAMERA_APPLIED "+p[4]+" "+p[5]+" "+p[6]+"\\n");
                    System.out.flush();
                  } else if(p[2].equals("CLOSE")) {
                    System.out.print(prefix+"CLOSED\\n"); System.out.flush(); return;
                  }
                }
              }
            }
            """);
        var child = new AtomicReference<Process>();
        var privateDirectory = new AtomicReference<Path>();
        Path observed = directory.resolve("camera.txt");
        var prepared = WorldStreamTransport.prepare(directory, spec);
        var session =
                prepared.launch(
                        arguments -> {
                            Path own = Path.of(arguments.get(1));
                            privateDirectory.set(own);
                            var descriptor =
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(own.resolve("session.wss")),
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
                                                    observed.toString())
                                            .start();
                            child.set(process);
                            return process;
                        });
        try {
            session.initialized().get(8, TimeUnit.SECONDS);
            prepared.close();
            assertTrue(Files.exists(privateDirectory.get().resolve("frames.wsf")));
            session.camera(
                    new WorldStreamTransport.Camera(
                            1, 1, 800, 500, new TerrainCamera(1, 2, 3, .2, .1)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(observed) && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertTrue(Files.readString(observed).contains("CAM 1 1 800 500 1.0 2.0 3.0 0.2 0.1"));
            var receipt = session.closeAsync().get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            assertEquals(child.get().pid(), receipt.childPid().orElseThrow());
            assertEquals(0, receipt.exitCode().orElseThrow());
            assertFalse(child.get().isAlive());
            assertFalse(Files.exists(privateDirectory.get()));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            session.camera(
                                    new WorldStreamTransport.Camera(
                                            2, 1, 800, 500, new TerrainCamera(0, 0, 0, 0, 0))));
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void heldExternalStarterCannotReturnFilesToOldPreparation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var own = new AtomicReference<Path>();
        var prepared = WorldStreamTransport.prepare(directory, spec);
        var session =
                prepared.launch(
                        arguments -> {
                            own.set(Path.of(arguments.get(1)));
                            entered.countDown();
                            release.await();
                            throw new java.io.IOException("authored no-child refusal");
                        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(
                    Duration.ofMillis(500),
                    () -> {
                        prepared.close();
                        session.closeAsync();
                    });
            assertTrue(Files.exists(own.get().resolve("frames.wsf")));
            assertFalse(session.terminatedAndDrained().isDone());
        } finally {
            release.countDown();
        }
        var receipt = session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
        assertTrue(receipt.childPid().isEmpty());
        assertTrue(receipt.exitCode().isEmpty());
        assertFalse(Files.exists(own.get()));
        assertThrows(
                java.util.concurrent.ExecutionException.class, () -> session.initialized().get());
        assertThrows(
                java.util.concurrent.ExecutionException.class, () -> session.completion().get());
    }
}
