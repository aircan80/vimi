package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** One coalesced application-owned run; provider work remains independently cancellable. */
final class SourceRefresh implements AutoCloseable {
    enum State {
        WAITING,
        UPDATING,
        COMPLETE,
        FAILED,
        CANCELED,
        DISABLED
    }

    record Progress(String message, double fraction) {}

    record Provider(
            String id,
            String name,
            Supplier<Boolean> enabled,
            Supplier<CompletableFuture<Void>> update,
            Supplier<Progress> progress,
            Runnable cancel) {
        Provider {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(enabled);
            Objects.requireNonNull(update);
            Objects.requireNonNull(progress);
            Objects.requireNonNull(cancel);
        }
    }

    record Row(String id, String name, State state, String message, double fraction) {}

    final class Run {
        private final List<Entry> entries;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile boolean canceled;

        private Run(List<Provider> providers) {
            entries = providers.stream().map(Entry::new).toList();
        }

        List<Row> rows() {
            return entries.stream()
                    .map(
                            entry -> {
                                State state = entry.state;
                                if (state == State.UPDATING) {
                                    Progress value = entry.provider.progress().get();
                                    return new Row(
                                            entry.provider.id(),
                                            entry.provider.name(),
                                            state,
                                            entry.cancelIssue
                                                    + (value == null || value.message() == null
                                                            ? "Checking for updates…"
                                                            : value.message()),
                                            value == null ? -1 : value.fraction());
                                }
                                return new Row(
                                        entry.provider.id(),
                                        entry.provider.name(),
                                        state,
                                        entry.message,
                                        state == State.COMPLETE || state == State.DISABLED
                                                ? 1
                                                : -1);
                            })
                    .toList();
        }

        CompletableFuture<Void> completion() {
            return completion.copy();
        }

        boolean busy() {
            return !completion.isDone();
        }

        void cancel() {
            synchronized (SourceRefresh.this) {
                canceled = true;
                for (Entry entry : entries) {
                    if (entry.state == State.UPDATING) {
                        try {
                            entry.provider.cancel().run();
                        } catch (RuntimeException failure) {
                            entry.cancelIssue =
                                    "Cancellation failed; waiting for provider to finish. ";
                        }
                    }
                }
            }
        }

        private void start() {
            var accepted = new ArrayList<CompletableFuture<Void>>();
            for (Entry entry : entries) {
                synchronized (SourceRefresh.this) {
                    if (canceled || closed) {
                        entry.state = State.CANCELED;
                        entry.message = "Canceled";
                        continue;
                    }
                    try {
                        if (!entry.provider.enabled().get()) {
                            entry.state = State.DISABLED;
                            entry.message = "Disabled";
                            continue;
                        }
                    } catch (RuntimeException failure) {
                        entry.state = State.FAILED;
                        entry.message = failure.getMessage();
                        continue;
                    }
                    entry.state = State.UPDATING;
                    CompletableFuture<Void> task;
                    try {
                        task = Objects.requireNonNull(entry.provider.update().get());
                    } catch (RuntimeException failure) {
                        task = CompletableFuture.failedFuture(failure);
                    }
                    accepted.add(
                            task.handle(
                                    (value, error) -> {
                                        if (error == null) {
                                            entry.message = "Up to date";
                                            entry.state = State.COMPLETE;
                                        } else {
                                            Throwable cause = unwrap(error);
                                            entry.state =
                                                    canceled
                                                                    || cause
                                                                            instanceof
                                                                            java.util.concurrent
                                                                                    .CancellationException
                                                            ? State.CANCELED
                                                            : State.FAILED;
                                            entry.message =
                                                    entry.state == State.CANCELED
                                                            ? "Canceled; completed data retained"
                                                            : cause.getMessage() == null
                                                                    ? cause.getClass()
                                                                            .getSimpleName()
                                                                    : cause.getMessage();
                                        }
                                        return null;
                                    }));
                }
            }
            CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new))
                    .whenComplete((value, error) -> completion.complete(null));
        }
    }

    private static final class Entry {
        final Provider provider;
        volatile State state = State.WAITING;
        volatile String message = "Waiting…";
        volatile String cancelIssue = "";

        Entry(Provider provider) {
            this.provider = provider;
        }
    }

    private final List<Provider> providers;
    private Run current;
    private boolean closed;

    SourceRefresh(List<Provider> providers) {
        if (providers.size() > 16
                || providers.stream().map(Provider::id).distinct().count() != providers.size())
            throw new IllegalArgumentException("Invalid source participants");
        this.providers = List.copyOf(providers);
    }

    synchronized Run start() {
        if (closed) throw new IllegalStateException("Source refresh closed");
        if (current != null && current.busy()) return current;
        current = new Run(providers);
        // Updates expose asynchronous boundaries; discovery/preparation never runs on the caller.
        current.start();
        return current;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (current != null && current.busy()) current.cancel();
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException) {
            if (error.getCause() == null) break;
            error = error.getCause();
        }
        return error;
    }
}
