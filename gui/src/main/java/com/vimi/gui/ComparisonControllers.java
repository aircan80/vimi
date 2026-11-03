package com.vimi.gui;

import java.util.concurrent.CompletableFuture;

/** Presentation-only bridge; the application service owns sessions and input contexts. */
final class ComparisonControllers {
    private ComparisonControllers() {}

    static CompareView.Controller from(BuildComparisons comparisons) {
        return (request, token) -> {
            if (comparisons == null)
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Local comparison is unavailable in this application mode."));
            return comparisons
                    .start(
                            new BuildComparisons.Request(
                                    request.before(),
                                    request.beforeLocale(),
                                    request.after(),
                                    request.afterLocale(),
                                    request.area(),
                                    request.tableId()),
                            token)
                    .thenApply(
                            handle ->
                                    new CompareView.Session() {
                                        public com.vibeminer.concepts.comparison.ComparisonTypes
                                                        .Descriptor
                                                before() {
                                            return handle.before();
                                        }

                                        public com.vibeminer.concepts.comparison.ComparisonTypes
                                                        .Descriptor
                                                after() {
                                            return handle.after();
                                        }

                                        public com.vibeminer.concepts.comparison.ComparisonTypes
                                                        .Progress
                                                progress() {
                                            return handle.progress();
                                        }

                                        public CompletableFuture<
                                                        com.vibeminer.concepts.comparison
                                                                .ComparisonTypes.ResultPage>
                                                page(long offset, int limit) {
                                            return handle.page(offset, limit);
                                        }

                                        public void cancel() {
                                            handle.cancel();
                                        }

                                        public CompletableFuture<Void> closeAsync() {
                                            return handle.closeAsync();
                                        }
                                    });
        };
    }
}
