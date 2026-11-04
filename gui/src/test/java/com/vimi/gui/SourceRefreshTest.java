package com.vimi.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic parallel/coalescing/failure/cancellation contracts without toolkit or network. */
public final class SourceRefreshTest {
    private SourceRefreshTest() {}

    public static void main(String[] args) throws Exception {
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var disabledCalls = new AtomicInteger();
        var progress = new AtomicReference<>(new SourceRefresh.Progress("Downloading", .25));
        try (var coordinator =
                new SourceRefresh(
                        List.of(
                                new SourceRefresh.Provider(
                                        "first",
                                        "First",
                                        () -> true,
                                        () -> {
                                            calls.incrementAndGet();
                                            return first;
                                        },
                                        progress::get,
                                        () ->
                                                first.completeExceptionally(
                                                        new java.util.concurrent
                                                                .CancellationException())),
                                new SourceRefresh.Provider(
                                        "second",
                                        "Second",
                                        () -> true,
                                        () -> {
                                            calls.incrementAndGet();
                                            return second;
                                        },
                                        () -> null,
                                        () ->
                                                second.completeExceptionally(
                                                        new java.util.concurrent
                                                                .CancellationException())),
                                new SourceRefresh.Provider(
                                        "disabled",
                                        "Disabled",
                                        () -> false,
                                        () -> {
                                            disabledCalls.incrementAndGet();
                                            return CompletableFuture.completedFuture(null);
                                        },
                                        () -> null,
                                        () -> {})))) {
            var run = coordinator.start();
            require(
                    calls.get() == 2,
                    "All enabled providers start without waiting for earlier futures");
            require(disabledCalls.get() == 0, "Disabled provider is never called");
            run.completion().complete(null);
            require(run.busy(), "A completion observer cannot forge the private run state");
            require(
                    coordinator.start() == run && calls.get() == 2,
                    "Startup/manual refresh coalesces");
            require(run.rows().getFirst().fraction() == .25, "Popup reads cached progress");
            progress.set(new SourceRefresh.Progress("Preparing", .8));
            require(
                    run.rows().getFirst().message().equals("Preparing"),
                    "Preparation progress updates independently");
            first.completeExceptionally(
                    new IllegalStateException("Offline; last valid data retained"));
            require(run.busy(), "One failure does not terminate other provider work");
            require(
                    run.rows().getFirst().state() == SourceRefresh.State.FAILED,
                    "Failure is scoped to its provider");
            second.complete(null);
            run.completion().get(2, TimeUnit.SECONDS);
            require(
                    run.rows().get(1).state() == SourceRefresh.State.COMPLETE,
                    "Other provider completes successfully");
            require(
                    run.rows().get(2).state() == SourceRefresh.State.DISABLED,
                    "Disabled source remains visible");
        }
        var pending = new CompletableFuture<Void>();
        var cancels = new AtomicInteger();
        var coordinator =
                new SourceRefresh(
                        List.of(
                                new SourceRefresh.Provider(
                                        "cancel",
                                        "Cancelable",
                                        () -> true,
                                        () -> pending,
                                        () -> null,
                                        () -> {
                                            cancels.incrementAndGet();
                                            pending.completeExceptionally(
                                                    new java.util.concurrent
                                                            .CancellationException());
                                        })));
        var run = coordinator.start();
        coordinator.close();
        run.completion().get(2, TimeUnit.SECONDS);
        require(
                cancels.get() == 1 && run.rows().getFirst().state() == SourceRefresh.State.CANCELED,
                "Shutdown cancels accepted work");
        try {
            coordinator.start();
            throw new AssertionError("Closed coordinator restarted");
        } catch (IllegalStateException expected) {
            /* expected */
        }
        try (var failures =
                new SourceRefresh(
                        List.of(
                                new SourceRefresh.Provider(
                                        "throw",
                                        "Throw",
                                        () -> true,
                                        () -> {
                                            throw new IllegalArgumentException(
                                                    "Provider rejected request");
                                        },
                                        () -> null,
                                        () -> {})))) {
            var failed = failures.start();
            failed.completion().get(2, TimeUnit.SECONDS);
            require(
                    failed.rows().getFirst().state() == SourceRefresh.State.FAILED,
                    "Synchronous provider refusal is isolated");
        }
        var summary = new AtomicReference<String>("1 newer cache imported; reopen the build to apply");
        try (var summaries =
                new SourceRefresh(
                        List.of(
                                new SourceRefresh.Provider(
                                        "summary",
                                        "Summary",
                                        () -> true,
                                        () -> CompletableFuture.completedFuture(null),
                                        () -> null,
                                        () -> {},
                                        summary::get),
                                new SourceRefresh.Provider(
                                        "broken-summary",
                                        "Broken summary",
                                        () -> true,
                                        () -> CompletableFuture.completedFuture(null),
                                        () -> null,
                                        () -> {},
                                        () -> {
                                            throw new IllegalStateException("summary failed");
                                        })))) {
            var completed = summaries.start();
            completed.completion().get(2, TimeUnit.SECONDS);
            require(
                    completed.rows().getFirst().state() == SourceRefresh.State.COMPLETE
                            && completed.rows().getFirst().message().equals(summary.get()),
                    "Provider completion summary replaces the generic message");
            require(
                    completed.rows().get(1).state() == SourceRefresh.State.COMPLETE
                            && completed.rows().get(1).message().equals("Up to date"),
                    "A failing completion summary keeps the generic message");
            summary.set(null);
            var generic = summaries.start();
            generic.completion().get(2, TimeUnit.SECONDS);
            require(
                    generic.rows().getFirst().message().equals("Up to date"),
                    "A null completion summary keeps the generic message");
        }
        System.out.println(
                "Source refresh: parallelism, coalescing, progress, disabled sources, failures,"
                        + " completion summaries and cancellation passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
