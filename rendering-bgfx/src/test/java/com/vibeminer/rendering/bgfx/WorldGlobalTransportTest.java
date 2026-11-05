package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scripted fake child: pure protocol/lifecycle of the parent transport, no GPU or process. */
class WorldGlobalTransportTest {
    @TempDir Path directory;

    private static final GlobalWmoAuthoredData AUTHORED =
            new GlobalWmoAuthoredData(
                    new float[] {
                        10, -10, -10, 0, 0, 10, 10, -10, 1, 0, 10, -10, 10, 0, 1, 10, 10, 10, 1, 1
                    },
                    new int[] {0, 1, 2, 2, 1, 3},
                    List.of(
                            new byte[] {
                                (byte) 255,
                                0,
                                0,
                                (byte) 255,
                                0,
                                (byte) 255,
                                0,
                                (byte) 255,
                                0,
                                0,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255,
                                (byte) 255
                            }),
                    List.of(new WorldGlobalHashes.Batch(0, 0, 0, 6)));

    /** Fake child whose stdout is scripted and whose stdin is recorded. */
    static final class FakeChild extends Process {
        final PipedOutputStream stdout = new PipedOutputStream();
        final PipedInputStream stdoutReader;
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final ByteArrayOutputStream controls = new ByteArrayOutputStream();
        final CompletableFuture<Void> closeReceived = new CompletableFuture<>();
        volatile List<String> arguments;

        FakeChild() throws IOException {
            stdoutReader = new PipedInputStream(stdout, 4096);
        }

        void emit(String line) throws IOException {
            stdout.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            stdout.flush();
        }

        void exit(int code) throws IOException {
            stdout.close();
            exit.complete(code);
        }

        String controls() {
            synchronized (controls) {
                return controls.toString(StandardCharsets.US_ASCII);
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    synchronized (controls) {
                        controls.write(b);
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    synchronized (controls) {
                        controls.write(b, off, len);
                    }
                    if (controls().contains("GLOBAL_CLOSE ")) {
                        closeReceived.complete(null);
                    }
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return stdoutReader;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException impossible) {
                throw new AssertionError(impossible);
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException pending) {
                return false;
            } catch (ExecutionException impossible) {
                throw new AssertionError(impossible);
            }
        }

        @Override
        public int exitValue() {
            if (!exit.isDone()) {
                throw new IllegalThreadStateException();
            }
            return exit.join();
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public void destroy() {
            exit.complete(137);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }
    }

    private record Started(
            WorldGlobalTransport.Session session,
            FakeChild child,
            WorldGlobalSceneWriter.MovedInput input) {}

    private Started start(String name) throws Exception {
        Path run = Files.createDirectory(directory.resolve(name));
        Path inputDir = Files.createDirectory(run.resolve("global-source-1"));
        var destination =
                WorldGlobalSceneWriter.Destination.create(inputDir, new Object(), "7".repeat(64));
        var input =
                WorldGlobalSceneWriter.writeUnadvertised(destination, AUTHORED, () -> false).move();
        var child = new FakeChild();
        var session =
                WorldGlobalTransport.launch(
                        run,
                        input,
                        null,
                        argv -> {
                            child.arguments = argv;
                            return child;
                        });
        return new Started(session, child, input);
    }

    private static String consumed(WorldGlobalSceneWriter.MovedInput input) {
        var id = input.expected().identity();
        return new WorldGlobalStatus.InputConsumed(id.nonce(), id.fileSha256()).protocolLine();
    }

    private static String ready(WorldGlobalSceneWriter.MovedInput input, int vertexDelta) {
        var e = input.expected();
        var c = e.counts();
        return new WorldGlobalStatus.Ready(
                        e.identity(),
                        c.vertices() + vertexDelta,
                        c.indices() / 3,
                        c.drawnTriangles(),
                        c.groups(),
                        c.batches(),
                        c.images())
                .protocolLine();
    }

    private static String closed(WorldGlobalSceneWriter.MovedInput input) {
        return new WorldGlobalStatus.Closed(input.expected().identity()).protocolLine();
    }

    private static void expectFailure(WorldGlobalTransport.Session session, String reason) {
        var failure =
                assertThrows(
                        ExecutionException.class,
                        () -> session.terminated().get(15, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains(reason), failure.getMessage());
    }

    private void withStarted(String name, Consumer<Started> scenario) throws Exception {
        var started = start(name);
        try {
            scenario.accept(started);
        } finally {
            started.input().close();
        }
    }

    @Test
    void cooperativeLifecycleSendsCanonicalControlsAndRetiresMapping() throws Exception {
        var started = start("normal");
        var child = started.child();
        var session = started.session();
        assertEquals(
                started.input().path().toString(),
                child.arguments.get(child.arguments.indexOf("--global-scene") + 1));
        child.emit(consumed(started.input()));
        session.inputConsumed().get(5, TimeUnit.SECONDS);
        long revision = session.camera(320, 200, new WorldGlobalTransport.Camera(-40, 0, 0, 0, 0));
        assertEquals(1, revision);
        child.emit(ready(started.input(), 0));
        session.ready().get(5, TimeUnit.SECONDS);
        session.closeAsync();
        child.closeReceived.get(5, TimeUnit.SECONDS);
        child.emit(closed(started.input()));
        child.exit(0);
        session.terminated().get(15, TimeUnit.SECONDS);
        assertTrue(session.retired() && session.exited());
        String nonce = started.input().expected().identity().nonce();
        assertEquals(
                "GLOBAL_CAM "
                        + nonce
                        + " 1 320 200 -40.0 0.0 0.0 0.0 0.0\n"
                        + "GLOBAL_CLOSE "
                        + nonce
                        + "\n",
                child.controls());
        try (var left = Files.list(directory.resolve("normal"))) {
            assertFalse(
                    left.anyMatch(p -> p.getFileName().toString().startsWith("global-frames-")));
        }
        started.input().close();
    }

    @Test
    void trailingRecordAfterClosedIsFatalBeforeHealthIsDecided() throws Exception {
        withStarted(
                "trailing",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        s.session().closeAsync();
                        s.child().closeReceived.get(5, TimeUnit.SECONDS);
                        s.child().emit(closed(s.input()));
                        // Actual exit can precede pipe drain: the trailing record arrives later.
                        s.child().exit.complete(0);
                        Thread.sleep(300);
                        s.child().emit(closed(s.input()));
                        s.child().stdout.close();
                        expectFailure(s.session(), "PROTOCOL");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void exitWithoutParentCloseIsUnexpectedEvenWithClosedStatus() throws Exception {
        withStarted(
                "unexpected",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        s.child().emit(closed(s.input()));
                        s.child().exit(0);
                        expectFailure(s.session(), "UNEXPECTED_EXIT");
                        assertTrue(s.session().retired());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    private static String unavailable(WorldGlobalSceneWriter.MovedInput input) {
        return new WorldGlobalStatus.Unavailable(
                        input.expected().identity(), WorldGlobalStatus.Code.CAPABILITY)
                .protocolLine();
    }

    @Test
    void capabilityUnavailableFailsReadyButRetiresHealthilyWithoutParentClose() throws Exception {
        withStarted(
                "unavailable",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        s.session()
                                .camera(320, 200, new WorldGlobalTransport.Camera(0, 0, 0, 0, 0));
                        s.child().emit(unavailable(s.input()));
                        var failed =
                                assertThrows(
                                        ExecutionException.class,
                                        () -> s.session().ready().get(5, TimeUnit.SECONDS));
                        var cause =
                                assertInstanceOf(
                                        WorldGlobalTransport.Unavailable.class, failed.getCause());
                        assertEquals(WorldGlobalStatus.Code.CAPABILITY, cause.code());
                        s.child().emit(closed(s.input()));
                        s.child().exit(0);
                        s.session().terminated().get(15, TimeUnit.SECONDS);
                        assertTrue(s.session().retired() && s.session().exited());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void unavailableBeforeConsumedAfterReadyOrForAnotherIdentityIsProtocolFailure()
            throws Exception {
        withStarted(
                "unavailable-early",
                s -> {
                    try {
                        s.child().emit(unavailable(s.input()));
                        expectFailure(s.session(), "PROTOCOL");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
        withStarted(
                "unavailable-late",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        s.child().emit(ready(s.input(), 0));
                        s.session().ready().get(5, TimeUnit.SECONDS);
                        s.child().emit(unavailable(s.input()));
                        expectFailure(s.session(), "PROTOCOL");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
        withStarted(
                "unavailable-foreign",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        var id = s.input().expected().identity();
                        s.child()
                                .emit(
                                        new WorldGlobalStatus.Unavailable(
                                                        new WorldGlobalStatus.Identity(
                                                                "f".repeat(32),
                                                                id.scope(),
                                                                id.fileSha256()),
                                                        WorldGlobalStatus.Code.CAPABILITY)
                                                .protocolLine());
                        expectFailure(s.session(), "PROTOCOL");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void readyWithWrongCountsOrBeforeConsumedIsProtocolFailure() throws Exception {
        withStarted(
                "counts",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        s.child().emit(ready(s.input(), 1));
                        expectFailure(s.session(), "PROTOCOL");
                        assertTrue(s.session().ready().isCompletedExceptionally());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
        withStarted(
                "order",
                s -> {
                    try {
                        s.child().emit(ready(s.input(), 0));
                        expectFailure(s.session(), "PROTOCOL");
                        assertTrue(s.session().inputConsumed().isCompletedExceptionally());
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void missingClosedAndNonzeroExitAreUnhealthy() throws Exception {
        withStarted(
                "missing",
                s -> {
                    try {
                        s.child().emit(consumed(s.input()));
                        s.session().closeAsync();
                        s.child().closeReceived.get(5, TimeUnit.SECONDS);
                        s.child().exit(0);
                        expectFailure(s.session(), "CLOSED_MISSING");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
        withStarted(
                "nonzero",
                s -> {
                    try {
                        s.session().closeAsync();
                        s.child().closeReceived.get(5, TimeUnit.SECONDS);
                        s.child().emit(closed(s.input()));
                        s.child().exit(1);
                        expectFailure(s.session(), "EXIT");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void camerasCoalesceToTheLatestAndPollingNeedsReady() throws Exception {
        withStarted(
                "coalesce",
                s -> {
                    try {
                        var camera = new WorldGlobalTransport.Camera(0, 0, 0, 0, 0);
                        assertEquals(null, s.session().poll(1, 10, 10));
                        long last = 0;
                        for (int i = 0; i < 50; i++) {
                            last = s.session().camera(100, 50, camera.move(0, i * .1, 0));
                        }
                        assertEquals(50, last);
                        s.session().closeAsync();
                        s.child().closeReceived.get(5, TimeUnit.SECONDS);
                        String text = s.child().controls();
                        long cams = text.lines().filter(l -> l.startsWith("GLOBAL_CAM ")).count();
                        assertTrue(cams <= 50, text);
                        assertTrue(
                                text.endsWith(
                                        "GLOBAL_CLOSE "
                                                + s.input().expected().identity().nonce()
                                                + "\n"));
                        s.child().emit(closed(s.input()));
                        s.child().exit(0);
                        s.session().terminated().get(15, TimeUnit.SECONDS);
                        // No camera admission after close.
                        assertEquals(50, s.session().camera(100, 50, camera));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }
}
