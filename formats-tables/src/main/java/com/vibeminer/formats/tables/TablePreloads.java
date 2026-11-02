package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.definitions.WowLocale;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bounded table-preload jobs, independent of UI handle lifetime, with coalesced serial
 * notifications.
 */
public final class TablePreloads implements AutoCloseable {
    public record Options(int maxJobs, Duration progressInterval) {
        public Options() {
            this(4, Duration.ofMillis(100));
        }

        public Options {
            if (maxJobs < 1 || progressInterval.isNegative()) {
                throw new IllegalArgumentException("Invalid preload bounds");
            }
        }
    }

    private record Key(BuildRef build, String locale) {}

    private final Tables tables;
    private final Options options;
    private final Object gate = new Object();
    private final Map<Key, Job> running = new HashMap<>();
    private final LinkedHashMap<Key, TableTypes.PreloadResult> results = new LinkedHashMap<>();
    private boolean closed;

    public TablePreloads(Tables tables) {
        this(tables, new Options());
    }

    public TablePreloads(Tables tables, Options options) {
        this.tables = java.util.Objects.requireNonNull(tables);
        this.options = java.util.Objects.requireNonNull(options);
    }

    public Job start(BuildRef build, String locale, Consumer<TableTypes.PreloadProgress> progress) {
        WowLocale.parse(locale);
        java.util.Objects.requireNonNull(build);
        synchronized (gate) {
            if (closed) {
                throw new IllegalStateException("Preloads closed");
            }
            var key = new Key(build, locale);
            var current = running.get(key);
            if (current != null) {
                return current;
            }
            if (running.size() >= options.maxJobs()) {
                throw new IllegalStateException("Preload job bound exceeded");
            }
            var job = new Job(build, locale, progress);
            running.put(key, job);
            CompletableFuture.runAsync(job::run, command -> Thread.ofVirtual().start(command));
            return job;
        }
    }

    public Job find(BuildRef build, String locale) {
        synchronized (gate) {
            return running.get(new Key(build, locale));
        }
    }

    public TableTypes.PreloadResult last(BuildRef build, String locale) {
        synchronized (gate) {
            return results.get(new Key(build, locale));
        }
    }

    public CompletableFuture<TableTypes.PreloadResult> stop(BuildRef build, String locale) {
        var job = find(build, locale);
        if (job == null) {
            return CompletableFuture.completedFuture(last(build, locale));
        }
        job.cancel();
        return job.completion();
    }

    public CompletableFuture<Void> closeAsync() {
        List<Job> jobs;
        synchronized (gate) {
            closed = true;
            jobs = List.copyOf(running.values());
        }
        for (var job : jobs) {
            job.cancel();
        }
        return CompletableFuture.allOf(
                jobs.stream().map(job -> job.completion).toArray(CompletableFuture[]::new));
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    public final class Job {
        private final BuildRef build;
        private final String locale;
        private final Consumer<TableTypes.PreloadProgress> subscriber;
        private final Cancellation cancel = new Cancellation();
        private final CompletableFuture<TableTypes.PreloadResult> completion =
                new CompletableFuture<>();
        private final Object lock = new Object();
        private TableTypes.PreloadProgress latest =
                new TableTypes.PreloadProgress(
                        TableTypes.PreloadStage.OPENING, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        private final ArrayDeque<Notice> outbox = new ArrayDeque<>();
        private boolean draining, finished;
        private long lastReport;
        private final CompletableFuture<Void> drained = new CompletableFuture<>();

        private record Notice(TableTypes.PreloadProgress value, boolean kept) {}

        Job(BuildRef build, String locale, Consumer<TableTypes.PreloadProgress> subscriber) {
            this.build = build;
            this.locale = locale;
            this.subscriber = subscriber;
        }

        public BuildRef build() {
            return build;
        }

        public String locale() {
            return locale;
        }

        public CompletableFuture<TableTypes.PreloadResult> completion() {
            return completion.copy();
        }

        public TableTypes.PreloadProgress latest() {
            synchronized (lock) {
                return latest;
            }
        }

        public void cancel() {
            cancel.cancel();
        }

        private void run() {
            TableTypes.PreloadResult result;
            publish(latest, true);
            try {
                cancel.check();
                var open = tables.forBuild(build, locale, cancel).join();
                if (open.state() != FilesOpenState.OPEN || open.tables() == null) {
                    if (open.tables() != null) {
                        open.tables().close();
                    }
                    result =
                            new TableTypes.PreloadResult(
                                    TableTypes.PreloadOutcome.FAILED,
                                    latest(),
                                    List.of(),
                                    open.state(),
                                    open.code(),
                                    open.message());
                } else {
                    try (var handle = open.tables()) {
                        result = handle.preload(value -> publish(value, false), cancel).join();
                    }
                }
            } catch (Throwable e) {
                var cause = ClientBuildTables.unwrap(e);
                result =
                        new TableTypes.PreloadResult(
                                cancel.isCanceled()
                                                || cause
                                                        instanceof
                                                        java.util.concurrent.CancellationException
                                        ? TableTypes.PreloadOutcome.CANCELED
                                        : TableTypes.PreloadOutcome.FAILED,
                                latest(),
                                List.of(),
                                null,
                                cancel.isCanceled() ? null : "TABLE_PRELOAD_FAILED",
                                cause.getMessage());
            }
            synchronized (lock) {
                latest = merge(latest, result.progress());
                finished = true;
                queue(latest, true);
                if (!draining) {
                    drained.complete(null);
                }
            }
            var terminal =
                    new TableTypes.PreloadResult(
                            result.outcome(),
                            latest(),
                            result.tables(),
                            result.openState(),
                            result.code(),
                            result.message());
            drained.join();
            synchronized (gate) {
                var key = new Key(build, locale);
                running.remove(key, this);
                results.put(key, terminal);
                while (results.size() > 64) {
                    results.remove(results.keySet().iterator().next());
                }
            }
            completion.complete(terminal);
        }

        private void publish(TableTypes.PreloadProgress value, boolean force) {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                var next = merge(latest, value);
                boolean stage = next.stage() != latest.stage();
                latest = next;
                long now = System.nanoTime();
                if (!force
                        && !stage
                        && lastReport != 0
                        && now - lastReport < options.progressInterval().toNanos()) {
                    return;
                }
                lastReport = now;
                queue(next, force || stage);
            }
        }

        private void queue(TableTypes.PreloadProgress value, boolean kept) {
            if (subscriber == null) {
                return;
            }
            if (!kept && !outbox.isEmpty() && !outbox.getLast().kept()) {
                outbox.removeLast();
            }
            outbox.addLast(new Notice(value, kept));
            if (draining) {
                return;
            }
            draining = true;
            CompletableFuture.runAsync(this::drain);
        }

        private void drain() {
            for (; ; ) {
                Notice notice;
                synchronized (lock) {
                    notice = outbox.pollFirst();
                    if (notice == null) {
                        draining = false;
                        if (finished) {
                            drained.complete(null);
                        }
                        return;
                    }
                }
                try {
                    subscriber.accept(notice.value());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static TableTypes.PreloadProgress merge(
            TableTypes.PreloadProgress a, TableTypes.PreloadProgress b) {
        return new TableTypes.PreloadProgress(
                a.stage().ordinal() > b.stage().ordinal() ? a.stage() : b.stage(),
                Math.max(a.filesDone(), b.filesDone()),
                Math.max(a.filesTotal(), b.filesTotal()),
                Math.max(a.filesFailed(), b.filesFailed()),
                Math.max(a.bytesDone(), b.bytesDone()),
                Math.max(a.bytesTotal(), b.bytesTotal()),
                Math.max(a.cdnBytes(), b.cdnBytes()),
                Math.max(a.tablesDone(), b.tablesDone()),
                Math.max(a.tablesTotal(), b.tablesTotal()),
                Math.max(a.tablesFailed(), b.tablesFailed()));
    }
}
