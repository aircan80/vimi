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
