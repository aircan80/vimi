package com.vibeminer.generated;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Shared bounded workers: record compression/parsing and disk waits never run on a UI thread. */
final class RecordTasks {
    private static final int WORKERS =
            Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    private static final ThreadPoolExecutor EXECUTOR =
            new ThreadPoolExecutor(
                    WORKERS,
                    WORKERS,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    Thread.ofPlatform().daemon().name("generated-record-", 0).factory());

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private RecordTasks() {}

    static <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            EXECUTOR.execute(
                    () -> {
                        T result;
                        try {
                            result = work.get();
                        } catch (Throwable failure) {
                            Throwable cause = failure;
                            while (cause instanceof CompletionException
                                    && cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            Throwable actual = cause;
                            Thread.startVirtualThread(() -> future.completeExceptionally(actual));
                            return;
                        }
                        Thread.startVirtualThread(() -> future.complete(result));
                    });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(
                    new GeneratedStoreException(
                            GeneratedTypes.LIMIT, "Record worker queue is full", e));
        }
        return future;
    }
}
