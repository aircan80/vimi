package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;

import java.util.concurrent.CompletableFuture;

/** Application-owned local comparison sessions; callers never own the shared library. */
public interface BuildComparisons extends AutoCloseable {
    record Request(
            BuildInfo before,
            String beforeLocale,
            BuildInfo after,
            String afterLocale,
            String area,
            Long tableId) {
        public Request(
                BuildInfo before,
                String beforeLocale,
                BuildInfo after,
                String afterLocale,
                String area) {
            this(before, beforeLocale, after, afterLocale, area, null);
        }
    }

    interface Session {
        ComparisonTypes.Descriptor before();

        ComparisonTypes.Descriptor after();

        ComparisonTypes.Progress progress();

        CompletableFuture<ComparisonTypes.ResultPage> page(long offset, int limit);

        void cancel();

        CompletableFuture<Void> closeAsync();
    }

    CompletableFuture<Session> start(Request request, Cancellation cancellation);

    CompletableFuture<Void> releaseCurrent();

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
