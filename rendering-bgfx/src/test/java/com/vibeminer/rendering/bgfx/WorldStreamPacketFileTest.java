package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class WorldStreamPacketFileTest {
    @TempDir Path directory;

    private record Written(
            WorldStreamPacketFile shell,
            WorldStreamTileFile.Expected expected,
            Path path,
            Object identity) {}

    private Written write(Path parent) throws Exception {
        var tile =
                com.vibeminer.rendering.world.TerrainScene.Tile.copyOf(
                        7, 19, new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[] {0, 1, 2}, 1);
        var header = WorldStreamTileFileTest.header(tile, false);
        var expected = WorldStreamTileFile.write(parent, header, tile, null, () -> false);
        var path = parent.resolve("tile-42.wst");
        var identity = new Object();
        return new Written(
                WorldStreamPacketFile.finalized(expected, path, identity),
                expected,
                path,
                identity);
    }

    @Test
    void moveMakesSourceShellInertBeforeTransportOpensItsInput() throws Exception {
        var written = write(directory);
        var moved = written.shell().move();
        assertSame(written.expected(), moved.expected());
        assertSame(written.identity(), moved.destinationIdentity());
        assertEquals(written.path(), moved.path());
        written.shell().close();
        assertTrue(Files.exists(written.path()));
        // Simulates opening input after source closure; the full reader checks actual bytes.
        var payload = WorldStreamTileFile.read(moved.path(), moved.expected(), () -> false);
        assertEquals(3, payload.tile().vertexCount());
        payload.close();
        assertThrows(IllegalStateException.class, written.shell()::move);
        moved.close();
        assertFalse(Files.exists(written.path()));
        moved.close();
        written.shell().close();
    }

    @Test
    void queuedDeletionDoesNotRunOnCallerAndCopiedFutureCancellationCannotForgeDrain()
            throws Exception {
        var written = write(directory);
        var moved = written.shell().move();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var lane = Executors.newSingleThreadExecutor()) {
            lane.execute(
                    () -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var first = moved.closeAsync(lane);
            assertFalse(first.isDone());
            assertTrue(Files.exists(written.path()));
            first.cancel(true);
            var actual = moved.closeAsync(lane);
            assertFalse(actual.isDone());
            written.shell().close();
            assertTrue(Files.exists(written.path()));
            release.countDown();
            actual.get(2, TimeUnit.SECONDS);
            assertFalse(Files.exists(written.path()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingUnmovedFileWinsBeforeMoveAndRepeatedCloseWaitsActualCleanup() throws Exception {
        var written = write(directory);
        var queued = new AtomicReference<Runnable>();
        var closing = written.shell().closeAsync(queued::set);
        assertThrows(IllegalStateException.class, written.shell()::move);
        assertFalse(closing.isDone());
        assertTrue(Files.exists(written.path()));
        var second =
                written.shell()
                        .closeAsync(
                                command -> {
                                    throw new AssertionError("Only one cleanup may be queued");
                                });
        assertFalse(second.isDone());
        queued.get().run();
        closing.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        assertFalse(Files.exists(written.path()));
    }

    @Test
    void nullExecutorDoesNotConsumeOwnershipAndRejectedLaneCannotReportDrain() throws Exception {
        var written = write(directory);
        assertThrows(NullPointerException.class, () -> written.shell().closeAsync(null));
        var moved = written.shell().move();
        var failed =
                moved.closeAsync(
                        command -> {
                            throw new java.util.concurrent.RejectedExecutionException(
                                    "Authored stopped lane");
                        });
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> failed.get(2, TimeUnit.SECONDS));
        assertTrue(Files.exists(written.path()));
        assertThrows(java.io.UncheckedIOException.class, moved::close);
        // The test owns the now-failed destination and explicitly removes it after proving failure.
        Files.delete(written.path());
    }

    @Test
    void finalizationRefusesMismatchedOrUnownedShapeWithoutDeletingAnything() throws Exception {
        var written = write(directory);
        var other = directory.resolve("tile-43.wst");
        Files.write(other, new byte[] {4, 5, 6});
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamPacketFile.finalized(written.expected(), other, new Object()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamPacketFile.finalized(
                                written.expected(),
                                written.path(),
                                "Not a detached identity token"));
        assertThrows(
                java.io.IOException.class,
                () ->
                        WorldStreamPacketFile.finalized(
                                new WorldStreamTileFile.Expected(
                                        written.expected().nonce(),
                                        written.expected().scope(),
                                        42,
                                        written.expected().lease(),
                                        4096,
                                        written.expected().sha256()),
                                written.path(),
                                new Object()));
        assertEquals(3, Files.size(other));
        assertTrue(Files.exists(written.path()));
        written.shell().close();
    }

    @Test
    void concurrentSourceCloseAndMoveAlwaysHaveExactlyOneDeletingOwner() throws Exception {
        for (int i = 0; i < 32; i++) {
            var parent = Files.createDirectory(directory.resolve("race-" + i));
            var written = write(parent);
            var start = new CountDownLatch(1);
            var result = new AtomicReference<WorldStreamPacketFile.Moved>();
            var move =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    start.await();
                                    result.set(written.shell().move());
                                } catch (IllegalStateException closed) {
                                    // Source close atomically won ownership.
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                            });
            var close =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    start.await();
                                    written.shell().close();
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                            });
            start.countDown();
            CompletableFuture.allOf(move, close).get(5, TimeUnit.SECONDS);
            if (result.get() != null) {
                assertTrue(Files.exists(written.path()), "The source cannot delete MOVED input");
                result.get().close();
            }
            assertFalse(Files.exists(written.path()));
        }
    }
}
