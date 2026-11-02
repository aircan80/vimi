package com.vibeminer.files;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

final class Io {
    private static final Bounded DEFAULT = new Bounded(1024);

    private Io() {}

    interface Work<T> {
        T run() throws Exception;
    }

    static <T> CompletableFuture<T> async(Work<T> work) {
        return DEFAULT.run(work);
    }

    /**
     * Fail-fast admission keeps nested cursor/detection work from waiting on saturated parent
     * permits.
     */
    static final class Bounded {
        private final int limit;
        private final AtomicInteger active = new AtomicInteger();

        Bounded(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("Invalid operation limit");
            }
            this.limit = limit;
        }

        <T> CompletableFuture<T> run(Work<T> work) {
            int count = active.incrementAndGet();
            if (count > limit) {
                active.decrementAndGet();
                return CompletableFuture.failedFuture(
                        new FilesException(
                                "FILES_OPERATION_LIMIT", "Too many pending Files operations"));
            }
            CompletableFuture<T> answer = new CompletableFuture<>();
            try {
                Thread.startVirtualThread(
                        () -> {
                            T value = null;
                            Throwable failure = null;
                            try {
                                value = work.run();
                            } catch (Throwable e) {
                                failure = unwrap(e);
                            } finally {
                                active.decrementAndGet();
                            }
                            if (failure == null) {
                                answer.complete(value);
                            } else {
                                answer.completeExceptionally(failure);
                            }
                        });
            } catch (Throwable failure) {
                active.decrementAndGet();
                answer.completeExceptionally(failure);
            }
            return answer;
        }
    }

    static Throwable unwrap(Throwable e) {
        while (e instanceof CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
