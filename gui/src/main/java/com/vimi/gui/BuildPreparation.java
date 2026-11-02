package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.files.FileLocales;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** App-owned preparation; immutable readiness is independent of dialogs and optional name work. */
public final class BuildPreparation {
    public enum Ready {
        NOT_PREPARED,
        METADATA_READY,
        FILES_READY,
        TABLES_READY
    }

    public enum Phase {
        METADATA,
        FILES,
        KEYS,
        TABLES,
        LOOKUPS,
        IDLE
    }

    public enum Activity {
        RUNNING,
        IDLE,
        STOPPING,
        CANCELED,
        FAILED
    }

    public record FilesSummary(
            long files, long stored, long unknownSize, boolean presenceAssessed) {
        public FilesSummary(long files, long stored, long unknownSize) {
            this(files, stored, unknownSize, true);
        }
    }

    public record Counter(long done, Long total, String unit) {
        public Counter {
            if (done < 0 || total != null && (total < 0 || done > total))
                throw new IllegalArgumentException("Invalid measured counter");
            Objects.requireNonNull(unit);
        }
    }

    public record Snapshot(
            long generation,
            Ready ready,
            Phase phase,
            Activity activity,
            long done,
            Long total,
            String unit,
            BuildTypes.BuildMetadata metadata,
            FilesSummary files,
            List<TableTypes.Entry> tables,
            List<String> issues,
            String problem) {
        public Snapshot {
            tables = List.copyOf(tables);
            issues = List.copyOf(issues);
        }

        public boolean busy() {
            return activity == Activity.RUNNING || activity == Activity.STOPPING;
        }
    }

    public interface Work {
        BuildTypes.BuildMetadata metadata(Cancellation token);

        FilesSummary files(Consumer<Counter> counted, Cancellation token);

        List<String> keys(Cancellation token);

        TableTypes.PreloadResult tables(
                Consumer<TableTypes.PreloadProgress> progress, Cancellation token);

        List<String> warm(Cancellation token);

        default void enrichNames(Cancellation token) {}

        default BuildAccounting accounting() {return null;}

        /** Historical UI receipt only; invoked on the pipeline worker, never grants readiness. */
        default void assessed(Ready ready) {}

        /**
         * Atomic, nonblocking publication only: no I/O/callbacks and no throwing after publication.
         */
        default void commit(Cancellation token) {
            token.check();
        }

        /** Retire unpublished generation resources; invoked on the private pipeline worker. */
        default void abortPending() {}

        /** Schedule retirement of replaced resources after publication, outside the Job lock. */
        default void retireCommitted() {}

        default void close() {}
    }

    @FunctionalInterface
    public interface Factory {
        Work create(BuildInfo build, BuildInfo.RegionEvidence source, String locale);
    }

    private final Factory factory;
    private final Executor executor;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final java.util.Set<Context> contexts = new java.util.LinkedHashSet<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private Job active;
    private boolean closed;
    private long generation;

    public BuildPreparation(Factory factory) {
        this(factory, command -> Thread.startVirtualThread(command));
    }

    BuildPreparation(Factory factory, Executor executor) {
        this.factory = Objects.requireNonNull(factory);
        this.executor = Objects.requireNonNull(executor);
    }

    private static String key(BuildInfo build, BuildInfo.RegionEvidence source, String locale) {
        return build.identity() + ":" + source.region() + ":" + source.cdnConfig() + ":" + locale;
    }

    public synchronized List<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    public synchronized Job find(BuildInfo build, BuildInfo.RegionEvidence source, String locale) {
        return jobs.get(key(build, source, locale));
    }

    public Job start(BuildInfo build, BuildInfo.RegionEvidence source, String locale) {
        return start(build, source, locale, false);
    }

    public Job enrich(BuildInfo build, BuildInfo.RegionEvidence source, String locale) {
        return start(build, source, locale, true);
    }

    private synchronized Job start(
            BuildInfo build, BuildInfo.RegionEvidence source, String locale, boolean force) {
        Objects.requireNonNull(build);
        Objects.requireNonNull(source);
        FileLocales.bitOf(locale);
        if (closed) throw new IllegalStateException("Preparation closed");
        if (!build.observations().contains(source))
            throw new IllegalArgumentException("Unknown source evidence");
        var identity = key(build, source, locale);
        var prior = jobs.get(identity);
        if (prior != null
                && (prior.snapshot().busy()
                        || !force
                                && prior.snapshot().ready() == Ready.TABLES_READY
                                && prior.namesProblem() == null)) return prior;
        if (active != null) throw new IllegalStateException("Another build is preparing");
        if (prior == null && contexts.size() >= 4)
            throw new IllegalStateException("Prepared build limit reached; close a build first");
        Context context = prior == null ? new Context() : prior.context;
        contexts.add(context);
        context.generations.removeIf(
                job ->
                        job.retired.isDone()
                                && (!context.enrichingNames || context.namesOwner != job));
        if (force && !context.enrichingNames) context.namesStarted = false;
        var job = new Job(build, source, locale, ++generation, context, prior, force);
        context.generations.add(job);
        jobs.put(identity, job);
        active = job;
        try {
            executor.execute(job::run);
        } catch (Throwable failure) {
            job.fail(failure);
            job.finish();
        }
        return job;
    }

    private final class Context {
        volatile Work work;
        final List<Job> generations = new ArrayList<>();
        final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
        CompletableFuture<Void> names = CompletableFuture.completedFuture(null);
        boolean namesStarted, closing;
        Job namesOwner;
        volatile boolean enrichingNames;
        volatile String namesProblem;
    }

    public final class Job {
        private final BuildPreparation owner = BuildPreparation.this;
        private final BuildInfo build;
        private final BuildInfo.RegionEvidence source;
        private final String locale;
        private final long number;
        private final long startedNanos = System.nanoTime();

        public long elapsedSeconds() {
            return java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(
                    System.nanoTime() - startedNanos);
        }

        private final boolean force;
        private final Context context;
        private final Cancellation token = new Cancellation();
        private final CompletableFuture<Snapshot> result = new CompletableFuture<>();
        private final CompletableFuture<Void> metadataReady = new CompletableFuture<>();
        private final CompletableFuture<Void> retired = new CompletableFuture<>();
        private volatile Snapshot current;
        private int tableStage = -1;

        private Job(
                BuildInfo build,
                BuildInfo.RegionEvidence source,
                String locale,
                long number,
                Context context,
                Job prior,
                boolean force) {
            this.build = build;
            this.source = source;
            this.locale = locale;
            this.number = number;
            this.context = context;
            this.force = force;
            var previous = prior == null ? null : prior.current;
            current =
                    new Snapshot(
                            number,
                            previous == null ? Ready.NOT_PREPARED : previous.ready(),
                            Phase.METADATA,
                            Activity.RUNNING,
                            0,
                            null,
                            "",
                            previous == null ? null : previous.metadata(),
                            previous == null ? null : previous.files(),
                            previous == null ? List.of() : previous.tables(),
                            previous == null ? List.of() : previous.issues(),
                            null);
        }

        public BuildInfo build() {
            return build;
        }

        public String locale() {
            return locale;
        }

        public BuildInfo.RegionEvidence source() {
            return source;
        }

        public Work work() {
            return context.work;
        }

        public Snapshot snapshot() {
            return current;
        }

        public boolean enrichingNames() {
            return context.enrichingNames;
        }

        public String namesProblem() {
            return context.namesProblem;
        }

        public CompletableFuture<Snapshot> completion() {
            return result.copy();
        }

        public CompletableFuture<Void> metadataReady() {
            return metadataReady.copy();
        }

        public void cancel() {
            synchronized (this) {
                var s = current;
                if (!s.busy() && !context.enrichingNames) return;
                current =
                        new Snapshot(
                                number,
                                s.ready(),
                                s.phase(),
                                s.busy() ? Activity.STOPPING : s.activity(),
                                s.done(),
                                s.total(),
                                s.unit(),
                                s.metadata(),
                                s.files(),
                                s.tables(),
                                s.issues(),
                                s.problem());
            }
            token.cancel();
        }

        private synchronized void stage(
                Ready ready,
                Phase phase,
                BuildTypes.BuildMetadata metadata,
                FilesSummary files,
                List<TableTypes.Entry> tables,
                List<String> issues) {
            token.check();
            var s = current;
            if (s.activity() == Activity.STOPPING)
                throw new java.util.concurrent.CancellationException();
            current =
                    new Snapshot(
                            number,
                            s.ready().ordinal() > ready.ordinal() ? s.ready() : ready,
                            phase,
                            Activity.RUNNING,
                            0,
                            null,
                            "",
                            metadata,
                            files,
                            tables,
                            issues,
                            null);
        }

        private synchronized void progress(Phase phase, Counter counter) {
            var s = current;
            if (token.isCanceled() || s.phase() != phase || s.activity() != Activity.RUNNING)
                return;
            if (s.unit().equals(counter.unit()) && counter.done() < s.done()) return;
            current =
                    new Snapshot(
                            number,
                            s.ready(),
                            phase,
                            s.activity(),
                            counter.done(),
                            counter.total(),
                            counter.unit(),
                            s.metadata(),
                            s.files(),
                            s.tables(),
                            s.issues(),
                            null);
        }

        private synchronized void tableProgress(TableTypes.PreloadProgress value) {
            if (value.stage().ordinal() < tableStage) return;
            tableStage = value.stage().ordinal();
            progress(
                    Phase.TABLES,
                    value.stage() == TableTypes.PreloadStage.RESTORING
                            ? new Counter(
                                    value.tablesDone(),
                                    (long) value.tablesTotal(),
                                    "cached tables restored")
                            : value.stage() == TableTypes.PreloadStage.PREPARING
                                    ? new Counter(
                                            value.tablesDone(),
                                            (long) value.tablesTotal(),
                                            "tables processed")
                                    : new Counter(
                                            value.filesDone(),
                                            value.filesTotal(),
                                            "table files downloaded"));
        }

        private void startNames(Work work) {
            CompletableFuture<Void> done;
            synchronized (BuildPreparation.this) {
                if (context.namesStarted
                        && (context.enrichingNames || context.namesProblem == null)) return;
                token.check();
                context.namesStarted = true;
                context.enrichingNames = true;
                context.namesProblem = null;
                done = new CompletableFuture<>();
                context.names = done;
                context.namesOwner = this;
            }
            try {
                executor.execute(
                        () -> {
                            try {
                                token.check();
                                work.enrichNames(token);
                            } catch (Throwable failure) {
                                context.namesProblem = message(failure);
                            } finally {
                                context.enrichingNames = false;
                                done.complete(null);
                            }
                        });
            } catch (Throwable failure) {
                context.namesProblem = message(failure);
                context.enrichingNames = false;
                done.complete(null);
            }
        }

        private void run() {
            try {
                token.check();
                if (context.work == null)
                    context.work =
                            Objects.requireNonNull(
                                    factory.create(build, source, locale),
                                    "Preparation factory returned no work");
                Work work = context.work;
                token.check();
                if (current.ready() == Ready.TABLES_READY && !force) {
                    startNames(work);
                    var s = current;
                    current =
                            new Snapshot(
                                    number,
                                    s.ready(),
                                    Phase.IDLE,
                                    Activity.IDLE,
                                    s.done(),
                                    s.total(),
                                    s.unit(),
                                    s.metadata(),
                                    s.files(),
                                    s.tables(),
                                    s.issues(),
                                    null);
                    publishMetadata(null);
                    return;
                }
                var metadata = current.metadata();
                var files = current.files();
                if (metadata == null)
                    metadata = Objects.requireNonNull(work.metadata(token), "No metadata result");
                stage(
                        Ready.METADATA_READY,
                        Phase.FILES,
                        metadata,
                        files,
                        current.tables(),
                        current.issues());
                work.assessed(Ready.METADATA_READY);
                publishMetadata(null);
                token.check();
                if (files == null)
                    files =
                            Objects.requireNonNull(
                                    work.files(value -> progress(Phase.FILES, value), token),
                                    "No Files summary");
                stage(
                        Ready.FILES_READY,
                        Phase.KEYS,
                        metadata,
                        files,
                        current.tables(),
                        current.issues());
                work.assessed(Ready.FILES_READY);
                var issues = new ArrayList<>(work.keys(token));
                token.check();
                startNames(work);
                stage(Ready.FILES_READY, Phase.TABLES, metadata, files, current.tables(), issues);
                var prepared =
                        Objects.requireNonNull(
                                work.tables(this::tableProgress, token),
                                "No table preparation result");
                token.check();
                if (prepared.outcome() != TableTypes.PreloadOutcome.COMPLETED)
                    throw new IllegalStateException(
                            "Table preparation "
                                    + prepared.outcome()
                                    + ": "
                                    + prepared.code()
                                    + " "
                                    + prepared.message());
                stage(Ready.FILES_READY, Phase.LOOKUPS, metadata, files, current.tables(), issues);
                issues.addAll(work.warm(token));
                synchronized (this) {
                    token.check();
                    if (current.activity() == Activity.STOPPING)
                        throw new java.util.concurrent.CancellationException();
                    work.commit(token);
                    current =
                            new Snapshot(
                                    number,
                                    Ready.TABLES_READY,
                                    Phase.IDLE,
                                    Activity.IDLE,
                                    prepared.tables().size(),
                                    (long) prepared.tables().size(),
                                    "tables",
                                    metadata,
                                    files,
                                    prepared.tables(),
                                    issues,
                                    null);
                }
                work.assessed(Ready.TABLES_READY);
                try {
                    work.retireCommitted();
                } catch (Throwable retirement) {
                    synchronized (this) {
                        var s = current;
                        var warnings = new ArrayList<>(s.issues());
                        warnings.add("Previous context retirement: " + message(retirement));
                        current =
                                new Snapshot(
                                        number,
                                        s.ready(),
                                        s.phase(),
                                        s.activity(),
                                        s.done(),
                                        s.total(),
                                        s.unit(),
                                        s.metadata(),
                                        s.files(),
                                        s.tables(),
                                        warnings,
                                        s.problem());
                    }
                }
            } catch (Throwable failure) {
                try {
                    if (context.work != null) context.work.abortPending();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                fail(failure);
            } finally {
                finish();
            }
        }

        private void publishMetadata(Throwable failure) {
            if (metadataReady.isDone()) return;
            // Complete on another worker: callbacks may await later readiness without blocking
            // Files.
            try {
                Thread.startVirtualThread(
                        () -> {
                            if (failure == null) metadataReady.complete(null);
                            else metadataReady.completeExceptionally(failure);
                        });
            } catch (Throwable startFailure) {
                metadataReady.completeExceptionally(startFailure);
            }
            while (!metadataReady.isDone())
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        }

        private void finish() {
            synchronized (BuildPreparation.this) {
                if (active == this) active = null;
            }
            if (!metadataReady.isDone())
                publishMetadata(
                        new IllegalStateException(
                                current.problem() == null
                                        ? "Metadata was not prepared"
                                        : current.problem()));
            retired.complete(null); // Resource retirement precedes arbitrary completion callbacks.
            result.complete(current);
        }

        private synchronized void fail(Throwable failure) {
            var s = current;
            current =
                    new Snapshot(
                            number,
                            s.ready(),
                            s.phase(),
                            token.isCanceled() ? Activity.CANCELED : Activity.FAILED,
                            s.done(),
                            s.total(),
                            s.unit(),
                            s.metadata(),
                            s.files(),
                            s.tables(),
                            s.issues(),
                            message(failure));
        }
    }

    /**
     * Release a retained context. Closing is asynchronous and waits for every generation and names.
     */
    public CompletableFuture<Void> release(Job job) {
        Objects.requireNonNull(job);
        if (job.owner != this)
            throw new IllegalArgumentException("Job belongs to another preparation engine");
        Context context = job.context;
        synchronized (this) {
            jobs.entrySet().removeIf(entry -> entry.getValue().context == context);
        }
        return closeContext(context);
    }

    private CompletableFuture<Void> closeContext(Context context) {
        List<Job> generations;
        synchronized (this) {
            if (context.closing) return context.closedFuture.copy();
            context.closing = true;
            generations = List.copyOf(context.generations);
        }
        var cancellationFailures = new ArrayList<Throwable>();
        for (var job : generations) {
            try {
                job.cancel();
            } catch (Throwable failure) {
                cancellationFailures.add(failure);
            }
        }
        try {
            Thread.startVirtualThread(
                    () -> {
                        Throwable failure = null;
                        try {
                            for (var job : generations) job.retired.join();
                            context.names.join();
                            if (context.work != null) context.work.close();
                        } catch (Throwable error) {
                            failure = error;
                        }
                        for (var error : cancellationFailures) {
                            if (failure == null) failure = error;
                            else failure.addSuppressed(error);
                        }
                        synchronized (BuildPreparation.this) {
                            contexts.remove(context);
                        }
                        if (failure == null) context.closedFuture.complete(null);
                        else context.closedFuture.completeExceptionally(failure);
                    });
        } catch (Throwable failure) {
            context.closedFuture.completeExceptionally(failure);
        }
        return context.closedFuture.copy();
    }

    public CompletableFuture<Void> closeAsync() {
        List<Context> retained;
        synchronized (this) {
            if (closed) return drained.copy();
            closed = true;
            retained = List.copyOf(contexts);
        }
        var closing = retained.stream().map(this::closeContext).toList();
        CompletableFuture.allOf(closing.toArray(CompletableFuture[]::new))
                .whenComplete(
                        (ignored, error) -> {
                            if (error == null) drained.complete(null);
                            else drained.completeExceptionally(error);
                        });
        return drained.copy();
    }

    private static String message(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
