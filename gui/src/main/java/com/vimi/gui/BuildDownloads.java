package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.DownloadAllJobs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Application-lifetime bulk policy; independent of the selected or prepared workspace. */
public final class BuildDownloads {
    public enum State {
        ON_DEMAND,
        QUEUED,
        OPENING,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        INCOMPLETE,
        FAILED
    }

    public record Snapshot(
            boolean enabled, State state, DownloadAllJobs.Progress progress, String message) {}

    @FunctionalInterface
    interface Runner {
        DownloadAllJobs.Result run(
                DownloadIntents.Entry entry,
                Cancellation token,
                Consumer<DownloadAllJobs.Progress> progress)
                throws Exception;
    }

    private final DownloadIntents persistence;
    private final Runner runner;
    private final Map<String, DownloadIntents.Entry> intents = new LinkedHashMap<>();
    private final Map<String, Snapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    // External commands hold one of 64 permits; only one runner completion and one close
    // can bypass those permits, so lifecycle work cannot be rejected by a full user queue.
    private final ThreadPoolExecutor commands =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    Thread.ofPlatform().daemon().name("vimi-download-policy").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private String active;
    private Cancellation activeToken;
    private boolean closed;
    private final java.util.concurrent.Semaphore requests = new java.util.concurrent.Semaphore(64);
    private final java.util.concurrent.atomic.AtomicBoolean closeRequested =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final CompletableFuture<Void> closing = new CompletableFuture<>();

    static BuildDownloads open(Path database, Runner runner) throws Exception {
        return new BuildDownloads(new DownloadIntents(database), runner);
    }

    private BuildDownloads(DownloadIntents persistence, Runner runner) throws Exception {
        this.persistence = persistence;
        this.runner = runner;
        for (var entry : persistence.load()) {
            intents.put(entry.identity(), entry);
            snapshots.put(entry.identity(), snapshot(entry));
        }
        commands.execute(this::pump);
    }

    public Snapshot snapshot(String identity) {
        return snapshots.getOrDefault(
                identity,
                new Snapshot(false, State.ON_DEMAND, null, "Acquire content as you explore"));
    }

    public CompletableFuture<Void> enable(BuildInfo build, BuildInfo.RegionEvidence evidence) {
        return change(
                new DownloadIntents.Entry(
                        build.identity(),
                        new BuildRef(
                                build.product(),
                                build.config(),
                                evidence.cdnConfig(),
                                evidence.region()),
                        true,
                        false,
                        null));
    }

    public CompletableFuture<Void> pause(String identity) {
        return policy(identity, true, true);
    }

    public CompletableFuture<Void> resume(String identity) {
        return policy(identity, true, false);
    }

    public CompletableFuture<Void> onDemand(String identity) {
        return policy(identity, false, false);
    }

    private CompletableFuture<Void> policy(String identity, boolean enabled, boolean paused) {
        return submit(
                () -> {
                    var before = intents.get(identity);
                    if (before == null) return;
                    apply(
                            new DownloadIntents.Entry(
                                    identity, before.build(), enabled, paused, null));
                });
    }

    private CompletableFuture<Void> change(DownloadIntents.Entry entry) {
        return submit(() -> apply(entry));
    }

    private void apply(DownloadIntents.Entry entry) throws Exception {
        if (!intents.containsKey(entry.identity()) && intents.size() >= 1024)
            throw new IllegalStateException("Too many retained download policies");
        persistence.save(entry); // Durable intent precedes a transfer or cancellation.
        intents.put(entry.identity(), entry);
        if (entry.identity().equals(active)) activeToken.cancel();
        snapshots.put(entry.identity(), snapshot(entry));
        pump();
    }

    private static Snapshot snapshot(DownloadIntents.Entry entry) {
        State state;
        if (!entry.enabled()) {
            state = State.ON_DEMAND;
        } else if (entry.paused()) {
            state = State.PAUSED;
        } else if (entry.outcome() == null) {
            state = State.QUEUED;
        } else {
            state =
                    switch (entry.outcome()) {
                        case "COMPLETED" -> State.COMPLETED;
                        case "INCOMPLETE" -> State.INCOMPLETE;
                        default -> State.FAILED;
                    };
        }
        String message =
                switch (state) {
                    case QUEUED -> "Waiting for background download";
                    case PAUSED -> "Paused; stored content retained";
                    case COMPLETED -> "All published encoded objects stored";
                    case FAILED, INCOMPLETE -> "Download stopped; retry to check missing content";
                    default -> null;
                };
        return new Snapshot(entry.enabled(), state, null, message);
    }

    private void pump() {
        if (closed || active != null) return;
        var candidate =
                intents.values().stream()
                        .filter(e -> e.enabled() && !e.paused() && e.outcome() == null)
                        .findFirst();
        if (candidate.isEmpty()) return;
        var entry = candidate.get();
        active = entry.identity();
        activeToken = new Cancellation();
        var token = activeToken;
        snapshots.put(
                active,
                new Snapshot(true, State.OPENING, null, "Opening build for background download"));
        Thread.startVirtualThread(
                () -> {
                    DownloadAllJobs.Result result = null;
                    Throwable failure = null;
                    try {
                        result =
                                runner.run(
                                        entry,
                                        token,
                                        p -> {
                                            if (!token.isCanceled())
                                                snapshots.compute(
                                                        entry.identity(),
                                                        (identity, previous) ->
                                                                token.isCanceled()
                                                                        ? previous
                                                                        : new Snapshot(
                                                                                true,
                                                                                State.DOWNLOADING,
                                                                                p,
                                                                                null));
                                        });
                    } catch (Throwable error) {
                        failure = error;
                    }
                    var outcome = result;
                    var error = failure;
                    commands.execute(
                            () -> {
                                try {
                                    var current = intents.get(entry.identity());
                                    if (current == entry && !closed && !token.isCanceled()) {
                                        String terminal =
                                                error != null ? "FAILED" : outcome.outcome().name();
                                        var saved =
                                                new DownloadIntents.Entry(
                                                        entry.identity(),
                                                        entry.build(),
                                                        true,
                                                        false,
                                                        terminal);
                                        persistence.save(saved);
                                        intents.put(saved.identity(), saved);
                                        var finalSnapshot = snapshot(saved);
                                        snapshots.put(
                                                saved.identity(),
                                                new Snapshot(
                                                        true,
                                                        finalSnapshot.state(),
                                                        outcome == null
                                                                ? null
                                                                : outcome.finalProgress(),
                                                        error != null
                                                                ? "Download failed; retry available"
                                                                : outcome.message() == null
                                                                        ? finalSnapshot.message()
                                                                        : outcome.message()));
                                    }
                                } catch (Exception persistenceFailure) {
                                    intents.put(
                                            entry.identity(),
                                            new DownloadIntents.Entry(
                                                    entry.identity(),
                                                    entry.build(),
                                                    true,
                                                    false,
                                                    "FAILED"));
                                    snapshots.put(
                                            entry.identity(),
                                            new Snapshot(
                                                    true,
                                                    State.FAILED,
                                                    null,
                                                    "Download status could not be saved"));
                                } finally {
                                    active = null;
                                    activeToken = null;
                                    if (closed) {
                                        commands.shutdown();
                                        closing.complete(null);
                                    } else pump();
                                }
                            });
                });
    }

    @FunctionalInterface
    private interface Command {
        void run() throws Exception;
    }

    private CompletableFuture<Void> submit(Command command) {
        var result = new CompletableFuture<Void>();
        if (!requests.tryAcquire())
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Download policy busy"));
        try {
            commands.execute(
                    () -> {
                        try {
                            if (closed) throw new IllegalStateException("Downloads closed");
                            command.run();
                            result.complete(null);
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        } finally {
                            requests.release();
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            requests.release();
            result.completeExceptionally(error);
        }
        var published = new CompletableFuture<Void>();
        result.whenComplete(
                (value, error) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (error == null) published.complete(null);
                                    else published.completeExceptionally(error);
                                }));
        return published;
    }

    public CompletableFuture<Void> closeAsync() {
        if (!closeRequested.compareAndSet(false, true)) return closing.copy();
        try {
            commands.execute(
                    () -> {
                        closed = true;
                        if (activeToken != null) activeToken.cancel();
                        else {
                            commands.shutdown();
                            closing.complete(null);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
        return closing.copy();
    }
}
