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

@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportTest {
    @TempDir Path directory;
    private final WorldStreamTransport.Spec spec =
            new WorldStreamTransport.Spec("ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN);

    @Test
    void publicInitializationFailsBoundedlyWhileHeldStarterStillOwnsFiles() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var path = new AtomicReference<Path>();
        var prepared = WorldStreamTransport.prepare(directory, spec);
        var session =
                prepared.launch(
                        arguments -> {
                            path.set(Path.of(arguments.get(1)));
                            entered.countDown();
                            release.await();
                            throw new java.io.IOException("authored late no-child return");
                        },
                        50,
                        100);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> session.initialized().get(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> session.completion().get(2, TimeUnit.SECONDS));
            assertFalse(session.terminatedAndDrained().isDone());
            assertTrue(Files.exists(path.get().resolve("frames.wsf")));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            session.camera(
                                    new WorldStreamTransport.Camera(
                                            1, 1, 800, 500, new TerrainCamera(0, 0, 0, 0, 0))));
        } finally {
            release.countDown();
        }
        assertTrue(session.terminatedAndDrained().get(3, TimeUnit.SECONDS).childPid().isEmpty());
        assertFalse(Files.exists(path.get()));
    }

    @Test
    void closedIsNotPermissionToIgnoreTrailingProtocolBytes() throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        var prepared = WorldStreamTransport.prepare(directory, spec);
        var session =
                prepared.launch(
                        arguments -> {
                            var descriptor =
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(
                                                    Path.of(arguments.get(1))
                                                            .resolve("session.wss")),
                                            arguments.get(3));
                            String prefix =
                                    "WS2 "
                                            + HexFormat.of().toHexDigits(descriptor.nonce().high())
                                            + HexFormat.of().toHexDigits(descriptor.nonce().low())
                                            + " ";
                            child.input =
                                    new java.io.ByteArrayInputStream(
                                            (prefix
                                                            + "SESSION TERRAIN_BASIC_V1\n"
                                                            + prefix
                                                            + "CLOSED\n"
                                                            + prefix
                                                            + "CREDIT 1 0 1\n")
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .US_ASCII));
                            return child;
                        });
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> session.completion().get(3, TimeUnit.SECONDS));
        assertEquals(
                0,
                session.terminatedAndDrained().get(3, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertFalse(child.isAlive());
    }

    @Test
    void actualExitBeforeBufferedClosedDoesNotCloseStatusReducerPrematurely() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var child = new WorldStreamLaunchOwnerTest.Child();
        child.killAllowed = false;
        var prepared = WorldStreamTransport.prepare(directory, spec);
        var session =
                prepared.launch(
                        arguments -> {
                            var descriptor =
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(
                                                    Path.of(arguments.get(1))
                                                            .resolve("session.wss")),
                                            arguments.get(3));
                            String prefix =
                                    "WS2 "
                                            + HexFormat.of().toHexDigits(descriptor.nonce().high())
                                            + HexFormat.of().toHexDigits(descriptor.nonce().low())
                                            + " ";
                            String initial =
                                    prefix
                                            + "SESSION TERRAIN_BASIC_V1\n"
                                            + prefix
                                            + "CREDIT 1 0 1\n";
                            byte[] bytes =
                                    (initial + prefix + "CLOSED\n")
                                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                            child.input =
                                    new java.io.InputStream() {
                                        int index;

                                        @Override
                                        public int read() throws java.io.IOException {
                                            if (index == initial.length()) {
                                                held.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException error) {
                                                    throw new java.io.IOException(error);
                                                }
                                            }
                                            return index == bytes.length
                                                    ? -1
                                                    : bytes[index++] & 255;
                                        }
                                    };
                            return child;
                        });
        try {
            session.initialized().get(2, TimeUnit.SECONDS);
            assertTrue(held.await(2, TimeUnit.SECONDS));
            child.exit(0);
            var field = WorldStreamTransport.Session.class.getDeclaredField("owner");
            field.setAccessible(true);
            var owner = (WorldStreamLaunchOwner.Handle) field.get(session);
            owner.childExited().get(2, TimeUnit.SECONDS);
            var lockField = WorldStreamTransport.Session.class.getDeclaredField("lock");
            var exitField = WorldStreamTransport.Session.class.getDeclaredField("actualExit");
            lockField.setAccessible(true);
            exitField.setAccessible(true);
            Object metadataLock = lockField.get(session);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean observed = false;
            while (!observed && System.nanoTime() < until) {
                synchronized (metadataLock) {
                    observed = exitField.getBoolean(session);
                }
                if (!observed) {
                    Thread.sleep(2);
                }
            }
            assertTrue(observed);
            assertFalse(session.terminatedAndDrained().isDone());
            assertTrue(Files.exists(owner.files().framePath()));
            release.countDown();
            session.completion().get(2, TimeUnit.SECONDS);
            assertEquals(
                    0,
                    session.terminatedAndDrained()
                            .get(2, TimeUnit.SECONDS)
                            .exitCode()
                            .orElseThrow());
            assertFalse(Files.exists(owner.files().directory()));
        } finally {
            release.countDown();
            child.exit(0);
            session.closeAsync();
        }
    }

    @Test
    void failedExitObservationNeverAuthorizesDeletingAdvertisedInput() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bootstrap = new AtomicReference<WorldStreamEnvelope.Session>();
        var child = new WorldStreamLaunchOwnerTest.Child();
        child.observesExit = false;
        child.killAllowed = false;
        var prepared = WorldStreamTransport.prepare(directory, spec);
        var session =
                prepared.launch(
                        arguments -> {
                            bootstrap.set(
                                    WorldStreamEnvelope.decodeSession(
                                            Files.readAllBytes(
                                                    Path.of(arguments.get(1))
                                                            .resolve("session.wss")),
                                            arguments.get(3)));
                            entered.countDown();
                            release.await();
                            return child;
                        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var stateField = WorldStreamTransport.Session.class.getDeclaredField("state");
        stateField.setAccessible(true);
        var state = (WorldStreamParentState) stateField.get(session);
        var nonce = bootstrap.get().nonce();
        state.status(new WorldStreamStatus.Session(nonce));
        state.status(new WorldStreamStatus.Credit(nonce, new WorldStreamState.Lease(0, 1, 1)));
        var ticket = state.reserve(7, 19);
        state.beginProducer(ticket);
        state.producerDrained(ticket);
        state.offer(ticket, 4096, "ab".repeat(32), 1, 256);
        release.countDown();
        try {
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> session.terminatedAndDrained().get(3, TimeUnit.SECONDS));
            // Wait for the public completion callback, not merely the underlying failed future.
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> session.completion().get(2, TimeUnit.SECONDS));
            assertTrue(child.isAlive());
            assertThrows(IllegalStateException.class, () -> state.retireTileFile(ticket));
            var field = WorldStreamTransport.Session.class.getDeclaredField("owner");
            field.setAccessible(true);
            var owner = (WorldStreamLaunchOwner.Handle) field.get(session);
            assertFalse(owner.childExited().isDone());
            assertTrue(Files.exists(owner.files().framePath()));
            child.exit(0); // Authored fake exit; test now independently knows cleanup is safe.
            owner.files().closeAsync().get(2, TimeUnit.SECONDS);
        } finally {
            child.exit(0);
        }
    }

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
                        },
                        WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                        5000);
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            prepared.close();
            assertTrue(Files.exists(privateDirectory.get().resolve("frames.wsf")));
            session.camera(
                    new WorldStreamTransport.Camera(
                            1, 1, 800, 500, new TerrainCamera(1, 2, 3, .2, .1)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            String expectedCamera = "CAM 1 1 800 500 1.0 2.0 3.0 0.2 0.1";
            String observedCommands = "";
            while (System.nanoTime() < until) {
                if (Files.exists(observed)) {
                    observedCommands = Files.readString(observed);
                    if (observedCommands.contains(expectedCamera)) {
                        break;
                    }
                }
                Thread.sleep(5);
            }
            assertTrue(observedCommands.contains(expectedCamera));
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
