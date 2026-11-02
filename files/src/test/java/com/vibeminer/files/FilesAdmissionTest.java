package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class FilesAdmissionTest {
    @Test
    void saturatedAdmissionFailsWithoutBlockingAndReleasesBeforeContinuations() throws Exception {
        var pool = new Io.Bounded(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var first =
                pool.run(
                        () -> {
                            entered.countDown();
                            release.await();
                            return 1;
                        });
        entered.await();
        var refused = pool.run(() -> 2);
        assertEquals(
                "FILES_OPERATION_LIMIT",
                ((FilesException) assertThrows(CompletionException.class, refused::join).getCause())
                        .code());
        var nested = first.thenCompose(value -> pool.run(() -> value + 1));
        release.countDown();
        assertEquals(2, nested.get(2, TimeUnit.SECONDS));
    }

    @Test
    void nestedWorkNeverWaitsForAnOccupiedParentPermit() {
        var pool = new Io.Bounded(1);
        assertEquals(
                "FILES_OPERATION_LIMIT",
                ((FilesException)
                                assertThrows(
                                                CompletionException.class,
                                                () ->
                                                        pool.run(() -> pool.run(() -> 1).join())
                                                                .join())
                                        .getCause())
                        .code());
        assertEquals(3, pool.run(() -> 3).join());
    }
}
