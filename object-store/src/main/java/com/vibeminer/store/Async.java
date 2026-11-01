package com.vibeminer.store;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class Async {
    private Async() { }
    static ThreadPoolExecutor pool(String name, int threads) {
        AtomicInteger number = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, name + "-" + number.incrementAndGet()); t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
    interface Work<T> { T run() throws Exception; }
    static <T> CompletableFuture<T> submit(Executor executor, Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try { executor.execute(() -> {
            try { result.complete(work.run()); } catch (Throwable e) { result.completeExceptionally(e); }
        }); } catch (RejectedExecutionException e) {
            result.completeExceptionally(new StoreException("OBJECT_STORE_LIMIT", "Worker queue is full or closed", e));
        }
        return result;
    }
}
