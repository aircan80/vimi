package com.vibeminer.formats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class AssetTasksTest {
    @Test
    void zeroProgressFileStreamIsAnEofFailureRatherThanAnEndlessFillLoop() {
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        com.vibeminer.files.FileReadStream stream =
                new com.vibeminer.files.FileReadStream() {
                    public long length() {
                        return 10;
                    }

                    public long position() {
                        return 0;
                    }

                    public void seek(long at) {}

                    public java.util.List<com.vibeminer.files.FileTypes.FileHole> holes() {
                        return java.util.List.of();
                    }

                    public CompletableFuture<Integer> read(
                            java.nio.ByteBuffer destination,
                            com.vibeminer.access.Cancellation token) {
                        if (reads.incrementAndGet() > 1) {
                            throw new AssertionError("Zero-progress read must not be retried");
                        }
                        return CompletableFuture.completedFuture(0);
                    }

                    public void close() {}
                };
        Throwable failure =
                AssetTasks.unwrap(
                        org.junit.jupiter.api.Assertions.assertThrows(
                                RuntimeException.class,
                                () ->
                                        AssetSources.file(stream)
                                                .readAt(
                                                        0,
                                                        1,
                                                        com.vibeminer.access.Cancellation.NONE)
                                                .join()));
        org.junit.jupiter.api.Assertions.assertInstanceOf(java.io.EOFException.class, failure);
        assertEquals(1, reads.get());
    }

    @Test
    void admissionRejectsOverflowWithoutWaitingAndReleasesAfterCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(AssetTasks.MAX_ACTIVE),
                release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> accepted = new ArrayList<>();
        try {
            for (int i = 0; i < AssetTasks.MAX_ACTIVE; i++) {
                accepted.add(
                        AssetTasks.run(
                                () -> {
                                    entered.countDown();
                                    try {
                                        release.await();
                                    } catch (InterruptedException e) {
                                        throw new IllegalStateException(e);
                                    }
                                    return 1;
                                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var rejected =
                    AssetTasks.run(
                            () -> {
                                throw new AssertionError("Overflow work must not start");
                            });
            assertTrue(rejected.isCompletedExceptionally());
            assertEquals(
                    "ASSET_FORMAT_LIMIT",
                    rejected.handle((value, error) -> error.getMessage()).join());
        } finally {
            release.countDown();
            CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).join();
        }
        assertEquals(2, AssetTasks.run(() -> 2).join());
    }
}
