package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File ownership only: these modeled files are not codec/source positives. */
class WorldGlobalFileOwnershipTest {
    @TempDir Path directory;
    private static final WorldGlobalLayout.Counts COUNTS =
            new WorldGlobalLayout.Counts(1, 1, 1, 1, 3, 3, 1, 1);

    private static WorldGlobalSceneWriter.ExpectedFile expected(
            WorldGlobalSceneWriter.Destination target) {
        return new WorldGlobalSceneWriter.ExpectedFile(
                new WorldGlobalStatus.Identity(target.nonce(), target.scope(), "a".repeat(64)),
                COUNTS.offsets().actualBytes(),
                COUNTS,
                "b".repeat(64),
                "c".repeat(64));
    }

    private static WorldGlobalSceneWriter.UnadvertisedFile create(
            WorldGlobalSceneWriter.Destination target) throws Exception {
        var wanted = expected(target);
        try (var file =
                FileChannel.open(
                        target.path(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            file.position(wanted.length() - 1);
            file.write(ByteBuffer.wrap(new byte[] {0}));
        }
        // This test really owns CREATE_NEW. The helper itself is not a public path adoption API.
        return WorldGlobalSceneWriter.finalizedCreated(target, wanted);
    }

    @Test
    void moveMakesSourceShellInertAndRequiresLiteralRequestIdentity() throws Exception {
        var identity = new Object();
        var target = WorldGlobalSceneWriter.Destination.create(directory, identity, "d".repeat(64));
        var source = create(target);
        var moved = source.move();
        assertTrue(moved.isFor(target));
        assertEquals(expected(target), moved.expected());
        assertEquals(moved.expected().input().identity(), moved.expected().identity());
        assertFalse(
                moved.isFor(
                        WorldGlobalSceneWriter.Destination.create(
                                directory, identity, target.scope())));
        source.close();
        assertTrue(Files.exists(target.path()));
        assertThrows(IllegalStateException.class, source::move);
        moved.close();
        assertFalse(Files.exists(target.path()));
        source.close();
        moved.close();
        var calls = new AtomicInteger();
        var forbidden =
                new Object() {
                    @Override
                    public boolean equals(Object other) {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }

                    @Override
                    public int hashCode() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldGlobalSceneWriter.Destination.create(
                                directory, forbidden, target.scope()));
        assertEquals(0, calls.get());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldGlobalSceneWriter.Destination.create(
                                Path.of("relative"), new Object(), target.scope()));
    }

    @Test
    void canceledPublicCloseCopyCannotCancelActualDeletionAndNullExecutorClaimsNothing()
            throws Exception {
        var target =
                WorldGlobalSceneWriter.Destination.create(directory, new Object(), "d".repeat(64));
        var source = create(target);
        assertThrows(NullPointerException.class, () -> source.closeAsync(null));
        var moved = source.move();
        var job = new AtomicReference<Runnable>();
        var first = moved.closeAsync(job::set);
        assertNotNull(job.get());
        first.cancel(false);
        var actual =
                moved.closeAsync(
                        command -> {
                            throw new AssertionError("No second cleanup task");
                        });
        assertFalse(actual.isDone());
        assertTrue(Files.exists(target.path()));
        job.get().run();
        actual.get(3, TimeUnit.SECONDS);
        assertFalse(Files.exists(target.path()));
        moved.close();
        source.close();
    }

    @Test
    void executorFatalTerminalizesPrivateCloseBeforeRethrowingSameError() throws Exception {
        var target =
                WorldGlobalSceneWriter.Destination.create(directory, new Object(), "d".repeat(64));
        var source = create(target);
        var calls = new AtomicInteger();
        var fatal =
                new AssertionError() {
                    @Override
                    public String getMessage() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        assertSame(
                fatal,
                assertThrows(
                        AssertionError.class,
                        () ->
                                source.closeAsync(
                                        command -> {
                                            throw fatal;
                                        })));
        var repeated =
                source.closeAsync(
                        command -> {
                            throw new AssertionError("Failed cleanup cannot reschedule");
                        });
        assertTrue(repeated.isCompletedExceptionally());
        assertThrows(UncheckedIOException.class, source::close);
        assertEquals(0, calls.get());
        assertTrue(Files.exists(target.path()));
        Files.delete(
                target.path()); // Failure deliberately kept the actual file; caller test owns it.
    }

    @Test
    void closeMoveRaceHasExactlyOneActualDeletionOwner() throws Exception {
        for (int round = 0; round < 24; round++) {
            var dir = Files.createDirectory(directory.resolve("race-" + round));
            var target =
                    WorldGlobalSceneWriter.Destination.create(dir, new Object(), "d".repeat(64));
            var source = create(target);
            var start = new CountDownLatch(1);
            var moved = new AtomicReference<WorldGlobalSceneWriter.MovedInput>();
            var moving = new CompletableFuture<Void>();
            var closing = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    start.await();
                                    try {
                                        moved.set(source.move());
                                    } catch (IllegalStateException lost) {
                                        /* Close won before MOVE. */
                                    }
                                    moving.complete(null);
                                } catch (Throwable failure) {
                                    moving.completeExceptionally(failure);
                                }
                            });
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    start.await();
                                    source.close();
                                    closing.complete(null);
                                } catch (Throwable failure) {
                                    closing.completeExceptionally(failure);
                                }
                            });
            start.countDown();
            moving.get(3, TimeUnit.SECONDS);
            closing.get(3, TimeUnit.SECONDS);
            if (moved.get() != null) {
                assertTrue(Files.exists(target.path()));
                moved.get().close();
            }
            assertFalse(Files.exists(target.path()));
            source.close();
        }
    }
}
