package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.store.ObjectKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** One bounded cancellable background download-all job per opened build. */
public final class DownloadAllJobs {
    public enum Stage {
        SCANNING,
        FETCHING
    }

    public enum Outcome {
        COMPLETED,
        INCOMPLETE,
        CANCELED,
        DISK_FULL,
        STORE_FAILED,
        NEEDS_NETWORK,
        FAILED
    }

    public record Options(
            int scanBatchKeys,
            int fetchBatch,
            int maxJobs,
            long freeReserveBytes,
            Duration progressInterval) {
        public Options {
            if (scanBatchKeys < 1
                    || scanBatchKeys > 1000000
                    || fetchBatch < 1
                    || fetchBatch > 1000000
                    || maxJobs < 1
                    || maxJobs > 64
                    || freeReserveBytes < 0
                    || progressInterval == null
                    || progressInterval.isNegative()) {
                throw new IllegalArgumentException("Invalid download-all bounds");
            }
        }

        public Options() {
            this(4096, 4096, 4, 1L << 30, Duration.ofMillis(100));
        }
    }

    public record Progress(
            BuildRef build,
            Stage stage,
            long storedObjects,
            long totalObjects,
            long storedBytes,
            long totalBytes,
            long failedObjects,
            long cdnBytes) {}

    public record Result(
            BuildRef build,
            Outcome outcome,
            Progress finalProgress,
            long notFound,
            long unavailable,
            long refused,
            long unsupported,
            String code,
            String message) {}

    private final Object gate = new Object();
    private final ObjectSource source;
    private final Function<Cancellation, CompletableFuture<Long>> freeBytes;
    private final Options options;
    private final Map<BuildRef, Job> running = new HashMap<>();
    private final LinkedHashMap<BuildRef, Result> results = new LinkedHashMap<>();
    private boolean disposed;
    private CompletableFuture<Void> closing;

    public DownloadAllJobs(
            ObjectSource source, Function<Cancellation, CompletableFuture<Long>> freeBytes) {
        this(source, freeBytes, new Options());
    }

    public DownloadAllJobs(
            ObjectSource source,
            Function<Cancellation, CompletableFuture<Long>> freeBytes,
            Options options) {
        this.source = java.util.Objects.requireNonNull(source);
        this.freeBytes = java.util.Objects.requireNonNull(freeBytes);
        this.options = java.util.Objects.requireNonNull(options);
    }

    public Job start(Build build, Consumer<Progress> progress) {
        java.util.Objects.requireNonNull(build);
        if (!(build instanceof LedgerSource ledger)) {
            throw new IllegalArgumentException("Only opened builds have an object ledger");
        }
        synchronized (gate) {
            if (disposed) {
                throw new IllegalStateException("Download-all jobs are closed");
            }
            Job existing = running.get(build.build());
            if (existing != null) {
                return existing;
            }
            if (running.size() >= options.maxJobs()) {
                throw new IllegalStateException("Too many download-all jobs");
            }
            Job job = new Job(build, ledger, progress);
            running.put(build.build(), job);
            Thread.startVirtualThread(job::run);
            return job;
        }
    }

    public Job find(BuildRef build) {
        synchronized (gate) {
            return running.get(build);
        }
    }

    public CompletableFuture<Result> stop(BuildRef build) {
        Job job;
        Result result;
        synchronized (gate) {
            job = running.get(build);
            result = results.get(build);
        }
        if (job == null) {
            return CompletableFuture.completedFuture(result);
        }
        job.cancel();
        return job.completion();
    }

    public CompletableFuture<Void> closeAsync() {
        List<Job> jobs;
        synchronized (gate) {
            if (closing != null) {
                return closing;
            }
            disposed = true;
            closing = new CompletableFuture<>();
            jobs = new ArrayList<>(running.values());
        }
        jobs.forEach(Job::cancel);
        CompletableFuture<Void> result = closing;
        CompletableFuture.allOf(
                        jobs.stream().map(Job::completion).toArray(CompletableFuture[]::new))
                .whenComplete(
                        (v, e) -> {
                            if (e == null) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(e);
                            }
                        });
        return result;
    }

    private void retire(Job job, Result result) {
        synchronized (gate) {
            running.remove(job.build.build(), job);
            results.put(job.build.build(), result);
            while (results.size() > 64) {
                results.remove(results.keySet().iterator().next());
            }
        }
    }

    public final class Job {
        private final Object progressGate = new Object();
        private final Build build;
        private final LedgerSource ledgerSource;
        private final Consumer<Progress> progress;
        private final Cancellation token = new Cancellation();
        private final CompletableFuture<Result> completion = new CompletableFuture<>();
        private final List<Queued> outbox = new ArrayList<>();
        private CompletableFuture<Void> drained;
        private boolean draining, finished, delivered;
        private Stage stage = Stage.SCANNING;
        private Progress latest;
        private long storedObjects,
                totalObjects,
                storedBytes,
                totalBytes,
                notFound,
                unavailable,
                refused,
                unsupported,
                cdnDone,
                cdnBatch,
                batchDone,
                batchSize,
                shownStored,
                lastDelivered;
        private int batch;

        private record Queued(Progress snapshot, boolean kept) {}

        private Job(Build build, LedgerSource ledgerSource, Consumer<Progress> progress) {
            this.build = build;
            this.ledgerSource = ledgerSource;
            this.progress = progress;
            latest = new Progress(build.build(), Stage.SCANNING, 0, 0, 0, 0, 0, 0);
        }

        public BuildRef build() {
            return build.build();
        }

        public CompletableFuture<Result> completion() {
            return completion;
        }

        public Progress latest() {
            synchronized (progressGate) {
                return latest;
            }
        }

        public void cancel() {
            token.cancel();
        }

        private void run() {
            Result result;
            try {
                result = runCore();
            } catch (Throwable thrown) {
                Throwable error = CatalogTasks.unwrap(thrown);
                if (error instanceof CancellationException && token.isCanceled()) {
                    result = finish(Outcome.CANCELED, null, "Download all was turned off");
                } else if (error instanceof com.vibeminer.store.StoreException store) {
                    result =
                            finish(
                                    store.code().equals("OBJECT_STORE_DISK_FULL")
                                            ? Outcome.DISK_FULL
                                            : Outcome.STORE_FAILED,
                                    store.code(),
                                    store.getMessage());
                } else if (error instanceof BuildFormatException format) {
                    result = finish(Outcome.FAILED, format.code(), format.getMessage());
                } else {
                    result =
                            finish(
                                    Outcome.FAILED,
                                    "DOWNLOAD_ALL_FAILED",
                                    error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            }
            CompletableFuture<Void> wait;
            synchronized (progressGate) {
                wait =
                        draining
                                ? (drained == null
                                        ? (drained = new CompletableFuture<>())
                                        : drained)
                                : CompletableFuture.completedFuture(null);
            }
            wait.join();
            retire(this, result);
            completion.complete(result);
        }

        private Result runCore() {
            CdnContext cdn = build.cdn();
            if (cdn == null) {
                return finish(
                        Outcome.NEEDS_NETWORK,
                        "OBJECT_ACCESS_NO_CDN_CONTEXT",
                        (build.cdnNote() == null ? "The build has no CDN route" : build.cdnNote())
                                + "; nothing fetched");
            }
            token.check();
            ObjectLedger ledger = ledgerSource.ledger();
            synchronized (progressGate) {
                totalObjects = ledger.count();
                totalBytes = ledger.totalBytes();
            }
            publish(true);
            BitSet missing = new BitSet(ledger.count());
            int missingCount = 0;
            long missingBytes = 0;
            for (int start = 0; start < ledger.count(); start += options.scanBatchKeys()) {
                token.check();
                List<ObjectKey> keys = new ArrayList<>();
                for (int i = start;
                        i < Math.min(start + options.scanBatchKeys(), ledger.count());
                        i++) {
                    keys.add(new ObjectKey(ObjectKey.ENCODED, ledger.key(i).bytes()));
                }
                List<Boolean> has = DownloadedFigures.await(source.hasKeys(keys, token), token);
                if (has.size() != keys.size()) {
                    throw new IllegalStateException("Source answered a different key count");
                }
                synchronized (progressGate) {
                    for (int i = 0; i < keys.size(); i++) {
                        int row = start + i;
                        if (has.get(i)) {
                            storedObjects++;
                            storedBytes += ledger.size(row);
                        } else {
                            missing.set(row);
                            missingCount++;
                            missingBytes = Math.addExact(missingBytes, ledger.size(row));
                        }
                    }
                }
                publish(false);
            }
            if (missingCount == 0) {
                return finish(Outcome.COMPLETED, null, null);
            }
            long free;
            try {
                free = DownloadedFigures.await(freeBytes.apply(token), token);
            } catch (RuntimeException e) {
                if (CatalogTasks.unwrap(e) instanceof CancellationException && token.isCanceled()) {
                    throw e;
                }
                return finish(
                        Outcome.STORE_FAILED,
                        "DOWNLOAD_ALL_FREE_SPACE_UNKNOWN",
                        "Free space could not be read");
            }
            long need =
                    missingBytes > Long.MAX_VALUE - options.freeReserveBytes()
                            ? Long.MAX_VALUE
                            : missingBytes + options.freeReserveBytes();
            if (free < need) {
                return finish(
                        Outcome.DISK_FULL,
                        "DOWNLOAD_ALL_NOT_ENOUGH_SPACE",
                        "Not enough free space for the missing objects and reserve");
            }
            PendingOrder order = new PendingOrder(missingCount);
            int at = 0;
            for (int row = missing.nextSetBit(0); row >= 0; row = missing.nextSetBit(row + 1)) {
                if ((at & 1023) == 0) {
                    token.check();
                }
                LedgerSource.Located located = ledgerSource.locate(ledger.key(row));
                order.rows[at] = row;
                order.archives[at] = located == null ? Integer.MAX_VALUE : located.archiveRank();
                order.offsets[at] = located == null ? 0 : located.location().offset();
                at++;
            }
            order.sort(token);
            synchronized (progressGate) {
                stage = Stage.FETCHING;
            }
            publish(true);
            String firstCode = null;
            for (int start = 0; start < missingCount; start += options.fetchBatch()) {
                token.check();
                List<ObjectRequest> requests = new ArrayList<>();
                for (int i = start; i < Math.min(start + options.fetchBatch(), missingCount); i++) {
                    int row = order.rows[i];
                    LedgerSource.Located located =
                            order.archives[i] == Integer.MAX_VALUE
                                    ? null
                                    : ledgerSource.locate(ledger.key(row));
                    requests.add(
                            new ObjectRequest(
                                    new ObjectKey(ObjectKey.ENCODED, ledger.key(row).bytes()),
                                    ledger.size(row),
                                    located == null ? List.of() : List.of(located.location()),
                                    0));
                }
                int current;
                synchronized (progressGate) {
                    current = ++batch;
                    cdnBatch = 0;
                    batchDone = 0;
                    batchSize = requests.size();
                }
                List<ObjectResult> results =
                        DownloadedFigures.await(
                                source.getKeys(
                                        requests,
                                        cdn,
                                        FetchPriority.BACKGROUND,
                                        value -> onProgress(current, value),
                                        token),
                                token);
                if (results.size() != requests.size()) {
                    throw new IllegalStateException("Source answered a different result count");
                }
                Outcome stop = null;
                String stopCode = null, stopMessage = null;
                synchronized (progressGate) {
                    cdnDone += cdnBatch;
                    cdnBatch = 0;
                    batchDone = 0;
                    batch++;
                    for (int i = 0; i < results.size(); i++) {
                        ObjectResult result = results.get(i);
                        switch (result.state()) {
                            case PRESENT -> {
                                storedObjects++;
                                storedBytes += requests.get(i).declaredLength();
                            }
                            case NOT_FOUND -> {
                                notFound++;
                                if (firstCode == null) {
                                    firstCode = result.code();
                                }
                            }
                            case UNAVAILABLE -> {
                                if ("CDN_OFFLINE".equals(result.code())
                                        || "OBJECT_ACCESS_NO_CDN_CONTEXT".equals(result.code())) {
                                    if (stop == null) {
                                        stop = Outcome.NEEDS_NETWORK;
                                        stopCode = result.code();
                                        stopMessage = result.message();
                                    }
                                } else {
                                    unavailable++;
                                    if (firstCode == null) {
                                        firstCode = result.code();
                                    }
                                }
                            }
                            case REFUSED -> {
                                refused++;
                                if (firstCode == null) {
                                    firstCode = result.code();
                                }
                            }
                            case UNSUPPORTED -> {
                                unsupported++;
                                if (firstCode == null) {
                                    firstCode = result.code();
                                }
                            }
                            case NEEDS_NETWORK -> {
                                if (stop == null) {
                                    stop = Outcome.NEEDS_NETWORK;
                                    stopCode = result.code();
                                    stopMessage = result.message();
                                }
                            }
                            case STORE_FAILED -> {
                                if ("OBJECT_STORE_DISK_FULL".equals(result.code())) {
                                    stop = Outcome.DISK_FULL;
                                    stopCode = result.code();
                                    stopMessage = result.message();
                                } else if (stop == null) {
                                    stop =
                                            token.isCanceled()
                                                    ? Outcome.CANCELED
                                                    : Outcome.STORE_FAILED;
                                    stopCode = result.code();
                                    stopMessage = result.message();
                                }
                            }
                        }
                    }
                }
                if (stop != null) {
                    return finish(stop, stopCode, stopMessage);
                }
                publish(false);
            }
            long failed;
            synchronized (progressGate) {
                failed = notFound + unavailable + refused + unsupported;
            }
            return finish(
                    failed == 0 ? Outcome.COMPLETED : Outcome.INCOMPLETE,
                    firstCode,
                    failed == 0
                            ? null
                            : failed + " objects could not be fetched; a rerun retries them");
        }

        private void onProgress(int forBatch, ObjectProgress value) {
            synchronized (progressGate) {
                if (forBatch != batch) {
                    return;
                }
                long done = Math.clamp(value.done(), 0, batchSize);
                if (value.cdnBytes() <= cdnBatch && done <= batchDone) {
                    return;
                }
                cdnBatch = Math.max(cdnBatch, value.cdnBytes());
                batchDone = Math.max(batchDone, done);
                publish(false);
            }
        }

        private void publish(boolean force) {
            synchronized (progressGate) {
                if (finished) {
                    return;
                }
                latest = snapshot(false);
                long now = System.nanoTime();
                if (!force
                        && delivered
                        && now - lastDelivered < options.progressInterval().toNanos()) {
                    return;
                }
                delivered = true;
                lastDelivered = now;
                queue(latest, force);
            }
        }

        private Result finish(Outcome outcome, String code, String message) {
            synchronized (progressGate) {
                cdnDone += cdnBatch;
                cdnBatch = 0;
                batchDone = 0;
                batch++;
                latest = snapshot(true);
                finished = true;
                queue(latest, true);
                return new Result(
                        build.build(),
                        outcome,
                        latest,
                        notFound,
                        unavailable,
                        refused,
                        unsupported,
                        code,
                        message);
            }
        }

        private Progress snapshot(boolean terminal) {
            long failed = notFound + unavailable + refused + unsupported,
                    done = storedObjects + failed + batchDone,
                    stored = storedObjects + batchDone;
            if (!terminal) {
                stored = Math.max(stored, shownStored);
            }
            shownStored = stored;
            return new Progress(
                    build.build(),
                    stage,
                    stored,
                    totalObjects,
                    storedBytes,
                    totalBytes,
                    terminal ? failed : done - stored,
                    cdnDone + cdnBatch);
        }

        private void queue(Progress value, boolean kept) {
            if (progress == null) {
                return;
            }
            if (!kept && !outbox.isEmpty() && !outbox.getLast().kept()) {
                outbox.set(outbox.size() - 1, new Queued(value, false));
            } else {
                outbox.add(new Queued(value, kept));
            }
            if (!draining) {
                draining = true;
                Thread.startVirtualThread(this::drain);
            }
        }

        private void drain() {
            while (true) {
                Progress next;
                synchronized (progressGate) {
                    if (outbox.isEmpty()) {
                        draining = false;
                        if (drained != null) {
                            drained.complete(null);
                            drained = null;
                        }
                        return;
                    }
                    next = outbox.removeFirst().snapshot();
                }
                try {
                    progress.accept(next);
                } catch (Throwable ignored) {
                    /* Callers cannot stop downloads by throwing from progress. */
                }
            }
        }
    }

    /** Three primitive arrays, 16 bytes per missing row, sorted in place without boxed indices. */
    private static final class PendingOrder {
        final int[] rows, archives;
        final long[] offsets;

        PendingOrder(int count) {
            rows = new int[count];
            archives = new int[count];
            offsets = new long[count];
        }

        void sort(Cancellation token) {
            sort(0, rows.length - 1, token);
        }

        private void sort(int lo, int hi, Cancellation token) {
            while (lo < hi) {
                token.check();
                int i = lo, j = hi, pivot = (lo + hi) >>> 1, pa = archives[pivot], pr = rows[pivot];
                long po = offsets[pivot];
                while (i <= j) {
                    while (compare(i, pa, po, pr) < 0) {
                        i++;
                    }
                    while (compare(j, pa, po, pr) > 0) {
                        j--;
                    }
                    if (i <= j) {
                        swap(i++, j--);
                    }
                }
                if (j - lo < hi - i) {
                    if (lo < j) {
                        sort(lo, j, token);
                    }
                    lo = i;
                } else {
                    if (i < hi) {
                        sort(i, hi, token);
                    }
                    hi = j;
                }
            }
        }

        private int compare(int index, int archive, long offset, int row) {
            int order = Integer.compare(archives[index], archive);
            if (order == 0) {
                order = Long.compare(offsets[index], offset);
            }
            return order == 0 ? Integer.compare(rows[index], row) : order;
        }

        private void swap(int a, int b) {
            int row = rows[a];
            rows[a] = rows[b];
            rows[b] = row;
            int archive = archives[a];
            archives[a] = archives[b];
            archives[b] = archive;
            long offset = offsets[a];
            offsets[a] = offsets[b];
            offsets[b] = offset;
        }
    }
}
