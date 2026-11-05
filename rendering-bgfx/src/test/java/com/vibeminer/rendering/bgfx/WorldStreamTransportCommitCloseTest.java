package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
class WorldStreamTransportCommitCloseTest {
    @TempDir Path directory;
    private volatile Path sessionDirectory;

    @Test
    void queuedUnadvertisedCommitDuringCloseDoesNotTurnCleanChildDrainIntoFailure()
            throws Exception {
        var session = launch();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            var method =
                    WorldStreamTransport.Session.class.getDeclaredMethod(
                            "enqueueFile", Runnable.class);
            method.setAccessible(true);
            method.invoke(
                    session,
                    (Runnable)
                            () -> {
                                entered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException failed) {
                                    throw new AssertionError(failed);
                                }
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var commit = session.commit(1);
            var closed = session.closeAsync();
            assertFalse(closed.isDone());
            release.countDown();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> commit.get(3, TimeUnit.SECONDS));
            closed.get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            session.terminatedAndDrained().get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            session.closeAsync();
        }
    }

    private WorldStreamTransport.Session launch() throws Exception {
        var prepared =
                WorldStreamTransport.prepare(
                        directory,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
        // These cases assert how a clean CLOSE/CLOSED drain is normalized. The production grace
        // (250 ms from close request to signal) is a helper budget; a loaded Windows runner can
        // exceed it for a JVM child round trip, which kills the child before CLOSED and turns the
        // clean drain into "EOF before CLOSED". The kill path is covered by its own tests.
        return prepared.launch(
                arguments -> {
                    sessionDirectory = Path.of(arguments.get(1));
                    var descriptor =
                            WorldStreamEnvelope.decodeSession(
                                    Files.readAllBytes(
                                            Path.of(arguments.get(1)).resolve("session.wss")),
                                    arguments.get(3));
                    String nonce =
                            HexFormat.of().toHexDigits(descriptor.nonce().high())
                                    + HexFormat.of().toHexDigits(descriptor.nonce().low());
                    return new ProcessBuilder(
                                    WorldStreamTestChildren.command(
                                            WorldStreamTestChildren.Closing.class, nonce))
                            .start();
                },
                WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                8000,
                2000);
    }

    @Test
    void stoppedPostWriteCommitDeletesOwnedBytesBeforeNormalizingCleanCancellation()
            throws Exception {
        var session = launch();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
            holdFiles(session, entered, release);
            var closed = session.closeAsync();
            Path owned = sessionDirectory.resolve("commit-7.wsc");
            Files.write(owned, new byte[] {1, 2, 3});
            var result =
                    failedCommit(session, owned, new java.util.concurrent.CancellationException());
            assertFalse(Files.exists(owned));
            assertTrue(result.isCompletedExceptionally());
            release.countDown();
            closed.get(5, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            session.closeAsync();
        }
    }

    @Test
    // Two child JVMs in sequence: two startup guards, plus the test body.
    @Timeout(2 * WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
    void stoppedDirtyCancellationAndFailedOwnedCleanupRemainFatal() throws Exception {
        for (boolean dirty : new boolean[] {true, false}) {
            var session = launch();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            Path owned = null;
            try {
                session.initialized()
                        .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
                owned = sessionDirectory.resolve("commit-7.wsc");
                holdFiles(session, entered, release);
                session.closeAsync();
                var stop = new java.util.concurrent.CancellationException();
                if (dirty) {
                    Files.write(owned, new byte[] {1});
                    stop.addSuppressed(new java.io.IOException("Unknown cleanup"));
                } else {
                    Files.createDirectory(owned);
                    Files.write(owned.resolve("held"), new byte[] {1});
                }
                var result = failedCommit(session, owned, stop);
                assertTrue(result.isCompletedExceptionally());
                release.countDown();
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> session.completion().get(5, TimeUnit.SECONDS));
                if (dirty) {
                    session.terminatedAndDrained().get(5, TimeUnit.SECONDS);
                } else {
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> session.terminatedAndDrained().get(5, TimeUnit.SECONDS));
                }
            } finally {
                release.countDown();
                session.closeAsync();
                if (!dirty && owned != null) {
                    Files.deleteIfExists(owned.resolve("held"));
                    Files.deleteIfExists(owned);
                }
            }
        }
    }

    private static void holdFiles(
            WorldStreamTransport.Session session, CountDownLatch entered, CountDownLatch release)
            throws Exception {
        var enqueue = session.getClass().getDeclaredMethod("enqueueFile", Runnable.class);
        enqueue.setAccessible(true);
        enqueue.invoke(
                session,
                (Runnable)
                        () -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException failure) {
                                throw new AssertionError(failure);
                            }
                        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
    }

    private static java.util.concurrent.CompletableFuture<?> failedCommit(
            WorldStreamTransport.Session session, Path path, Throwable failure) throws Exception {
        var operationType =
                Class.forName(
                        "com.vibeminer.rendering.bgfx.WorldStreamTransport$Session$CommitOperation");
        var constructor = operationType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var result =
                new java.util.concurrent.CompletableFuture<WorldStreamTransport.CommitReceipt>();
        var operation = constructor.newInstance(result, java.util.List.of(), null, path);
        var created = operationType.getDeclaredField("created");
        created.setAccessible(true);
        created.setBoolean(operation, true);
        var registered = session.getClass().getDeclaredField("commitOperation");
        registered.setAccessible(true);
        registered.set(session, operation);
        var method =
                session.getClass()
                        .getDeclaredMethod(
                                "failedCommit",
                                operationType,
                                java.util.concurrent.CompletableFuture.class,
                                Throwable.class);
        method.setAccessible(true);
        method.invoke(session, operation, result, failure);
        return result;
    }
}
