package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.InputPage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit session-only UNSORTED capture and two-way external sort before comparison starts. */
public final class SortedComparisonInput implements ComparisonInput {
    public static final int PAGE_ROWS = 256;
    public static final long SOURCE_PAGE_BYTES = 4L << 20;
    public static final long CHUNK_BYTES = 12L << 20;
    public static final int RECORD_BYTES = 1 << 20;
    public static final long MAX_KEYS = 5_000_000;
    private static final long CODEC_BYTES = 8L << 20;
    private static final long READ_PAGE_BYTES = 4L << 20;
    public static final long READ_ALLOWANCE_BYTES = 14L << 20;
    private static final int MAX_RUNS = 1024;

    /**
     * Unsorted canonical pages from one pinned source. Before read, the sorter reserves
     * maxRetainedBytes for the returned page. Implementations must honor that bound before
     * allocating output, and separately bound/charge any internal source buffers. nextCursor is
     * opaque, must advance, and is null only at source exhaustion. Source close drains its reads.
     */
    public interface Source {
        Descriptor descriptor();

        CompletableFuture<UnsortedPage> read(
                String cursor, int maxRows, long maxRetainedBytes, Cancellation token);

        CompletableFuture<Void> closeAsync();
    }

    public record UnsortedPage(
            String identity, List<Record> rows, String nextCursor, Coverage coverage) {
        public UnsortedPage {
            Objects.requireNonNull(identity);
            rows = List.copyOf(rows);
            Objects.requireNonNull(coverage);
        }
    }

    public enum Reason {
        LIMITED,
        STALE,
        INVALID_SOURCE,
        IO,
        CANCELED
    }

    /** Capture never publishes a sorted input or complete coverage on failure. */
    public static final class CaptureFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;
        private final transient Coverage coverage;

        public CaptureFailure(Reason reason, String message, Coverage coverage, Throwable cause) {
            super(message, cause);
            this.reason = reason;
            this.coverage = coverage;
        }

        public Reason reason() {
            return reason;
        }

        public Coverage coverage() {
            return coverage;
        }
    }

    private Source source;
    private final Descriptor descriptor;
    private final TempBudget budget;
    private final Path directory;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8),
                    runnable -> {
                        var thread = new Thread(runnable, "comparison-sort");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private final List<BudgetedTempFile> files = new ArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private Coverage coverage;
    private BudgetedTempFile sorted;
    private DataInputStream reader;
    private long nextOrdinal;
    private boolean exhausted;
    private final java.util.Set<ReadDelivery> readDeliveries = new java.util.HashSet<>();
    private Integer pendingFrameLength;

    private SortedComparisonInput(Source source, Path parent, TempBudget budget)
            throws IOException {
        this.source = Objects.requireNonNull(source);
        descriptor = Objects.requireNonNull(source.descriptor());
        this.budget = Objects.requireNonNull(budget);
        directory = Files.createTempDirectory(parent, "comparison-sort-");
        coverage =
                new Coverage(
                        Completeness.UNKNOWN, descriptor.scope(), List.of("Capture not completed"));
    }

    /**
     * Takes ownership of source. Complete capture/sort before passing the returned input to
     * core.start. Cancel the supplied token and await this future when the caller needs a drain
     * barrier. Canceling the future also cancels capture and disposes an undeliverable input, but
     * CompletableFuture cancellation itself completes immediately, before asynchronous cleanup.
     */
    public static CompletableFuture<SortedComparisonInput> capture(
            Source source, Path parent, TempBudget budget, Cancellation token) {
        return capture(source, parent, budget, token, Runnable::run);
    }

    // Inject delivery only for deterministic ownership-race fixtures; delivery is dispatched
    // from the common pool, never from the sort worker that closeAsync must drain.
    static CompletableFuture<SortedComparisonInput> capture(
            Source source,
            Path parent,
            TempBudget budget,
            Cancellation token,
            java.util.concurrent.Executor delivery) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(delivery);
        SortedComparisonInput input;
        try {
            input = new SortedComparisonInput(source, parent, budget);
        } catch (Throwable failure) {
            return source.closeAsync()
                    .handle(
                            (ignored, cleanup) -> {
                                if (cleanup != null) {
                                    failure.addSuppressed(cleanup);
                                }
                                throw new CompletionException(failure);
                            });
        }
        var result = new CompletableFuture<SortedComparisonInput>();
        var link = token.onCancel(input.lifetime::cancel);
        result.whenComplete(
                (value, failure) -> {
                    if (result.isCancelled()) {
                        input.lifetime.cancel();
                    }
                });
        input.worker.execute(
                () -> {
                    Throwable failure = null;
                    try {
                        input.captureAndSort(input.lifetime);
                    } catch (Throwable caught) {
                        failure = input.classify(caught);
                    }
                    try {
                        input.drainSource();
                    } catch (Throwable cleanup) {
                        if (failure == null) {
                            failure = input.classify(cleanup);
                        } else {
                            failure.addSuppressed(cleanup);
                        }
                    }
                    if (failure == null && input.lifetime.isCanceled()) {
                        failure = input.classify(new java.util.concurrent.CancellationException());
                    }
                    if (failure != null) {
                        try {
                            input.cleanup();
                        } catch (Throwable cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        input.worker.shutdown();
                        link.close();
                        var terminal = failure;
                        CompletableFuture.runAsync(() -> result.completeExceptionally(terminal));
                    } else {
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        delivery.execute(
                                                () -> {
                                                    link.close();
                                                    if (input.lifetime.isCanceled()) {
                                                        input.closeAsync()
                                                                .whenComplete(
                                                                        (ignored, cleanup) -> {
                                                                            var canceled =
                                                                                    input.classify(
                                                                                            new java
                                                                                                    .util
                                                                                                    .concurrent
                                                                                                    .CancellationException());
                                                                            if (cleanup != null) {
                                                                                canceled
                                                                                        .addSuppressed(
                                                                                                cleanup);
                                                                            }
                                                                            result
                                                                                    .completeExceptionally(
                                                                                            canceled);
                                                                        });
                                                    } else {
                                                        if (!result.complete(input)) {
                                                            input.closeAsync();
                                                        }
                                                    }
                                                });
                                    } catch (Throwable rejected) {
                                        link.close();
                                        input.closeAsync()
                                                .whenComplete(
                                                        (ignored, cleanup) -> {
                                                            if (cleanup != null) {
                                                                rejected.addSuppressed(cleanup);
                                                            }
                                                            result.completeExceptionally(rejected);
                                                        });
                                    }
                                });
                    }
                });
        return result;
    }

    private void drainSource() {
        var owned = source;
        try {
            owned.closeAsync().join();
        } finally {
            source = null;
        }
    }

    @SuppressWarnings(
            "try") // Reservations enforce peak lifetimes even when their handles are not read.
    private void captureAndSort(Cancellation token) throws Exception {
        var runs = new ArrayList<BudgetedTempFile>();
        try (var chunkMemory = budget.reserveMemory("sort-chunk", 0, token);
                var indexMemory = budget.reserveMemory("sort-run-index", 0, token)) {
            var chunk = new ArrayList<Record>();
            long count = 0;
            String cursor = null;
            boolean firstPage = true;
            while (true) {
                check(token);
                try (var pageMemory =
                        budget.reserveMemory("sort-source-page", SOURCE_PAGE_BYTES, token)) {
                    var page = source.read(cursor, PAGE_ROWS, SOURCE_PAGE_BYTES, token).join();
                    check(token);
                    if (page == null || !descriptor.identity().equals(page.identity())) {
                        throw failure(Reason.STALE, "Unsorted page identity changed");
                    }
                    if (!descriptor.scope().equals(page.coverage().scope())) {
                        throw failure(Reason.STALE, "Unsorted page coverage scope changed");
                    }
                    if (page.rows().size() > PAGE_ROWS) {
                        throw failure(Reason.INVALID_SOURCE, "Unsorted page exceeds row limit");
                    }
                    long pageBytes =
                            4096L
                                    + 4L
                                            * (page.identity().length()
                                                    + page.coverage().scope().length()
                                                    + (page.nextCursor() == null
                                                            ? 0
                                                            : page.nextCursor().length()));
                    for (var issue : page.coverage().issues()) {
                        pageBytes = Math.addExact(pageBytes, 64L + 4L * issue.length());
                    }
                    if (pageBytes > SOURCE_PAGE_BYTES) {
                        throw failure(
                                Reason.LIMITED, "Unsorted page metadata exceeds reserved buffer");
                    }
                    for (var row : page.rows()) {
                        if (row == null) {
                            throw failure(Reason.INVALID_SOURCE, "Null unsorted record");
                        }
                        pageBytes = Math.addExact(pageBytes, retainedBytes(row));
                        if (pageBytes > SOURCE_PAGE_BYTES) {
                            throw failure(Reason.LIMITED, "Unsorted page exceeds reserved buffer");
                        }
                    }
                    for (var row : page.rows()) {
                        check(token);
                        if (++count > MAX_KEYS) {
                            throw failure(Reason.LIMITED, "Unsorted identity limit reached");
                        }
                        validateKey(row.key());
                        long bytes = retainedBytes(row);
                        if (bytes > CHUNK_BYTES) {
                            throw failure(Reason.LIMITED, "Record exceeds sort chunk policy");
                        }
                        if (chunkMemory.size() + bytes > CHUNK_BYTES && !chunk.isEmpty()) {
                            indexMemory.grow(2048, token);
                            runs.add(writeChunk(chunk, token));
                            chunk.clear();
                            chunk.trimToSize();
                            chunkMemory.shrink(chunkMemory.size());
                        }
                        chunkMemory.grow(bytes, token);
                        chunk.add(row);
                    }
                    coverage = mergeCoverage(firstPage ? null : coverage, page.coverage());
                    firstPage = false;
                    var next = page.nextCursor();
                    if (next == null) {
                        break;
                    }
                    if (next.equals(cursor) || page.rows().isEmpty()) {
                        throw failure(Reason.INVALID_SOURCE, "Unsorted cursor did not advance");
                    }
                    cursor = next;
                }
            }
            if (!chunk.isEmpty()) {
                indexMemory.grow(2048, token);
                runs.add(writeChunk(chunk, token));
                chunk.clear();
                chunk.trimToSize();
                chunkMemory.shrink(chunkMemory.size());
            }
            // Keep at most two record heads and one output writer, regardless of run count.
            while (runs.size() > 1) {
                var nextPass = new ArrayList<BudgetedTempFile>();
                for (int i = 0; i < runs.size(); i += 2) {
                    check(token);
                    if (i + 1 == runs.size()) {
                        nextPass.add(runs.get(i));
                    } else {
                        var output = merge(runs.get(i), runs.get(i + 1), token);
                        nextPass.add(output);
                        runs.get(i).close();
                        runs.get(i + 1).close();
                    }
                }
                runs = nextPass;
            }
            sorted = runs.isEmpty() ? null : runs.getFirst();
            if (sorted != null) {
                sorted.transferTo("sort-sealed-input");
            }
            files.removeIf(file -> file != sorted);
            ((ArrayList<BudgetedTempFile>) files).trimToSize();
        }
    }

    private Coverage mergeCoverage(Coverage previous, Coverage next) throws CaptureFailure {
        if (next.issues().size() > 64
                || next.issues().stream().mapToLong(String::length).sum() > 16384) {
            throw failure(Reason.LIMITED, "Unsorted coverage diagnostics exceed metadata policy");
        }
        var state = next.completeness();
        if (previous != null) {
            if (previous.completeness() == Completeness.UNKNOWN || state == Completeness.UNKNOWN) {
                state = Completeness.UNKNOWN;
            } else {
                if (previous.completeness() == Completeness.PARTIAL
                        || state == Completeness.PARTIAL) {
                    state = Completeness.PARTIAL;
                }
            }
        }
        var issues = new java.util.LinkedHashSet<String>();
        if (previous != null) {
            issues.addAll(previous.issues());
        }
        issues.addAll(next.issues());
        if (issues.size() > 64 || issues.stream().mapToLong(String::length).sum() > 16384) {
            throw failure(Reason.LIMITED, "Captured coverage diagnostics exceed metadata policy");
        }
        return new Coverage(state, descriptor.scope(), List.copyOf(issues));
    }

    @SuppressWarnings("try") // Charge sorting/encoding scratch before allocation.
    private BudgetedTempFile writeChunk(List<Record> chunk, Cancellation token) throws Exception {
        if (files.size() >= MAX_RUNS) {
            throw failure(Reason.LIMITED, "Sort run count limit reached");
        }
        // TimSort may allocate half a reference array; reserve before sorting.
        try (var sorting =
                        budget.reserveMemory(
                                "sort-reference-work", 8L * chunk.size() + 128, token);
                var codec = budget.reserveMemory("sort-codec", CODEC_BYTES, token)) {
            chunk.sort(Comparator.comparing(Record::key));
            var file = createFile(token);
            String previous = null;
            for (var row : chunk) {
                check(token);
                if (row.key().equals(previous)) {
                    throw failure(Reason.INVALID_SOURCE, "Duplicate canonical identity");
                }
                append(file, row, token);
                previous = row.key();
            }
            file.seal();
            return file;
        }
    }

    @SuppressWarnings("try") // Two decoded heads plus encoder are independently reserved.
    private BudgetedTempFile merge(BudgetedTempFile a, BudgetedTempFile b, Cancellation token)
            throws Exception {
        try (var aMemory = budget.reserveMemory("sort-merge-a", CODEC_BYTES, token);
                var bMemory = budget.reserveMemory("sort-merge-b", CODEC_BYTES, token);
                var codec = budget.reserveMemory("sort-merge-codec", CODEC_BYTES, token);
                var left = new DataInputStream(Files.newInputStream(a.path()));
                var right = new DataInputStream(Files.newInputStream(b.path()))) {
            var output = createFile(token);
            var x = readRecord(left);
            var y = readRecord(right);
            while (x != null || y != null) {
                check(token);
                int order = x == null ? 1 : y == null ? -1 : x.key().compareTo(y.key());
                if (order == 0) {
                    throw failure(Reason.INVALID_SOURCE, "Duplicate identity across sort runs");
                }
                if (order < 0) {
                    append(output, x, token);
                    x = null;
                    x = readRecord(left);
                } else {
                    append(output, y, token);
                    y = null;
                    y = readRecord(right);
                }
            }
            output.seal();
            return output;
        }
    }

    private BudgetedTempFile createFile(Cancellation token) throws IOException {
        var file = BudgetedTempFile.create(directory, "run-", budget, "sort-run", token);
        files.add(file);
        return file;
    }

    private static void append(BudgetedTempFile file, Record row, Cancellation token)
            throws IOException {
        // Canonical adapters must bound fields before capture. Do not silently mutate their facts.
        var bytes = ComparisonCodec.pair(row, null);
        if (bytes.length > RECORD_BYTES) {
            throw new ComparisonLimit("Sort record frame exceeds 1 MiB");
        }
        file.appendFrame(bytes, token);
    }

    private static Record readRecord(DataInputStream input) throws IOException {
        var length = readLength(input);
        if (length == null) {
            return null;
        }
        return readBody(input, length);
    }

    private static Integer readLength(DataInputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        int length =
                (first << 24)
                        | (input.readUnsignedByte() << 16)
                        | (input.readUnsignedByte() << 8)
                        | input.readUnsignedByte();
        if (length < 1 || length > RECORD_BYTES) {
            throw new IOException("Invalid sorted frame length");
        }
        return length;
    }

    private static Record readBody(DataInputStream input, int length) throws IOException {
        var bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Truncated sorted frame");
        }
        var pair = ComparisonCodec.pair(bytes);
        if (pair[0] == null || pair[1] != null) {
            throw new IOException("Invalid sorted record framing");
        }
        return pair[0];
    }

    /** Conservative canonical-record/object accounting shared with bounded source adapters. */
    public static long retainedBytes(Record record) {
        return ComparisonCodec.retainedBytes(Objects.requireNonNull(record)) + 64;
    }

    private void validateKey(String key) throws CaptureFailure {
        // Framework canonical fixed-width unsigned decimal keys, including zero.
        if (key.length() != 20
                || !key.chars().allMatch(c -> c >= '0' && c <= '9')
                || key.compareTo("00000000004294967295") > 0) {
            throw failure(Reason.INVALID_SOURCE, "Expected canonical 20-digit identity");
        }
    }

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    /**
     * Standalone reads own an allowance until the private worker finishes, independently of
     * public-future cancellation.
     */
    @Override
    public CompletableFuture<InputPage> read(String cursor, int limit, Cancellation token) {
        return readStandalone(cursor, limit, token, Runnable::run);
    }

    // Deterministic delivery boundary for ownership fixtures, without changing public APIs.
    CompletableFuture<InputPage> readStandalone(
            String cursor, int limit, Cancellation token, java.util.concurrent.Executor delivery) {
        if (limit < 1 || limit > PAGE_ROWS) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid sorted page size"));
        }
        try {
            var allowance =
                    budget.reserveMemory("sort-standalone-read", READ_ALLOWANCE_BYTES, token);
            return readPlanned(cursor, limit, token, allowance, true, delivery);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Borrowed allowance remains entirely caller-owned; require its same-budget live memory plan
     * before reading.
     */
    @Override
    public CompletableFuture<InputPage> read(
            String cursor, int limit, Cancellation token, TempBudget.Reservation allowance) {
        return readPlanned(cursor, limit, token, allowance, false, Runnable::run);
    }

    private CompletableFuture<InputPage> readPlanned(
            String cursor,
            int limit,
            Cancellation token,
            TempBudget.Reservation allowance,
            boolean owned,
            java.util.concurrent.Executor deliveryExecutor) {
        try {
            Objects.requireNonNull(token);
            Objects.requireNonNull(deliveryExecutor);
            Objects.requireNonNull(allowance).requireMemory(budget, READ_ALLOWANCE_BYTES);
        } catch (Throwable failure) {
            if (owned && allowance != null) {
                allowance.close();
            }
            return CompletableFuture.failedFuture(failure);
        }
        if (limit < 1 || limit > PAGE_ROWS) {
            if (owned) {
                allowance.close();
            }
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid sorted page size"));
        }
        if (closing.get()) {
            if (owned) {
                allowance.close();
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Sorted input closed"));
        }
        var delivery = new ReadDelivery(allowance, owned, Objects.requireNonNull(deliveryExecutor));
        try {
            synchronized (worker) {
                if (closing.get()) {
                    if (owned) {
                        allowance.close();
                    }
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Sorted input closed"));
                }
                if (readDeliveries.size() >= 16
                        || readDeliveries.stream()
                                .anyMatch(active -> active.allowance == allowance)) {
                    if (owned) {
                        allowance.close();
                    }
                    return CompletableFuture.failedFuture(
                            new IllegalStateException(
                                    "Sorted read allowance already in use or command limit reached"));
                }
                readDeliveries.add(delivery);
                worker.execute(
                        () -> {
                            try {
                                allowance.requireMemory(budget, READ_ALLOWANCE_BYTES);
                                check(token);
                                var wanted = cursor == null ? 0 : Long.parseLong(cursor);
                                if (wanted != nextOrdinal) {
                                    throw new IOException(
                                            "Sorted input cursor is not the current continuation");
                                }
                                if (reader == null && sorted != null) {
                                    reader =
                                            new DataInputStream(
                                                    Files.newInputStream(sorted.path()));
                                }
                                var rows = new ArrayList<Record>();
                                long retained = 4096;
                                while (!exhausted && rows.size() < limit) {
                                    check(token);
                                    if (reader == null) {
                                        exhausted = true;
                                        break;
                                    }
                                    if (pendingFrameLength == null) {
                                        pendingFrameLength = readLength(reader);
                                    }
                                    if (pendingFrameLength == null) {
                                        exhausted = true;
                                        break;
                                    }
                                    // Bound framing/decoder overhead before allocation. A large
                                    // record may occupy
                                    // a short page alone; returned records remain within the 4 MiB
                                    // retained cap.
                                    long predicted = 8L * pendingFrameLength + 65536;
                                    if (!rows.isEmpty() && retained + predicted > READ_PAGE_BYTES) {
                                        break;
                                    }
                                    allowance.requireMemory(budget, READ_ALLOWANCE_BYTES);
                                    var row = readBody(reader, pendingFrameLength);
                                    pendingFrameLength = null;
                                    var actual = retainedBytes(row);
                                    if (actual + retained > READ_PAGE_BYTES) {
                                        throw new ComparisonLimit(
                                                "Sorted page record exceeds retained budget");
                                    }
                                    rows.add(row);
                                    retained += actual;
                                    nextOrdinal++;
                                }
                                var pageCoverage =
                                        !exhausted
                                                        && coverage.completeness()
                                                                == Completeness.COMPLETE
                                                ? new Coverage(
                                                        Completeness.PARTIAL,
                                                        coverage.scope(),
                                                        coverage.issues())
                                                : coverage;
                                var page =
                                        new InputPage(
                                                descriptor.identity(),
                                                rows,
                                                exhausted ? null : Long.toString(nextOrdinal),
                                                pageCoverage);
                                delivery.store(page);
                                page = null;
                            } catch (Throwable caught) {
                                delivery.storeFailure(caught);
                            } finally {
                                delivery.workerFinished();
                            }
                        });
            }
        } catch (Throwable rejected) {
            delivery.storeFailure(rejected);
            delivery.workerFinished();
        }
        return delivery.result;
    }

    /** Owns a privately produced page until actual delivery or post-worker abandonment. */
    private final class ReadDelivery {
        final TempBudget.Reservation allowance;
        final boolean owned;
        final java.util.concurrent.Executor executor;
        final CompletableFuture<InputPage> result = new CompletableFuture<>();
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        InputPage page;
        Throwable failure;
        boolean finished, abandoned, claimed;

        ReadDelivery(
                TempBudget.Reservation allowance,
                boolean owned,
                java.util.concurrent.Executor executor) {
            this.allowance = allowance;
            this.owned = owned;
            this.executor = executor;
            result.whenComplete(
                    (value, error) -> {
                        if (result.isCancelled()) {
                            abandon(new java.util.concurrent.CancellationException());
                        }
                    });
        }

        synchronized void store(InputPage value) {
            page = value;
        }

        synchronized void storeFailure(Throwable value) {
            failure = value;
        }

        void workerFinished() {
            boolean discard;
            synchronized (this) {
                finished = true;
                discard = abandoned || failure != null || result.isDone();
            }
            if (discard) {
                discard();
            } else {
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                executor.execute(this::deliver);
                            } catch (Throwable rejected) {
                                abandon(rejected);
                            }
                        });
            }
        }

        void abandon(Throwable reason) {
            boolean discard;
            synchronized (this) {
                if (claimed) {
                    return;
                }
                abandoned = true;
                if (failure == null) {
                    failure = reason;
                }
                discard = finished;
            }
            if (discard) {
                discard();
            }
        }

        private void discard() {
            Throwable reason;
            synchronized (this) {
                if (claimed || !finished) {
                    return;
                }
                claimed = true;
                page = null;
                reason =
                        failure == null
                                ? new java.util.concurrent.CancellationException()
                                : failure;
            }
            release();
            CompletableFuture.runAsync(() -> result.completeExceptionally(reason));
        }

        private void deliver() {
            InputPage value;
            synchronized (this) {
                if (claimed) {
                    return;
                }
                if (abandoned || result.isDone()) {
                    value = null;
                } else {
                    claimed = true;
                    value = page;
                    page = null;
                }
            }
            if (value == null) {
                discard();
                return;
            }
            // No consumer callback runs while this adapter owns its allowance. The private
            // page remains charged through the queued delivery and transitions directly here.
            release();
            result.complete(value);
        }

        private void release() {
            synchronized (worker) {
                if (owned) {
                    allowance.close();
                }
                drained.complete(null);
                readDeliveries.remove(this);
            }
        }
    }

    private void check(Cancellation token) {
        lifetime.check();
        token.check();
    }

    private CaptureFailure failure(Reason reason, String message) {
        return new CaptureFailure(
                reason,
                message,
                new Coverage(Completeness.PARTIAL, descriptor.scope(), List.of(message)),
                null);
    }

    private CaptureFailure classify(Throwable failure) {
        while ((failure instanceof CompletionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof CaptureFailure typed) {
            return typed;
        }
        var reason =
                failure instanceof TempBudget.Limit
                                || failure instanceof ComparisonLimit
                                || failure instanceof ComparisonPolicyLimit
                        ? Reason.LIMITED
                        : failure instanceof java.util.concurrent.CancellationException
                                ? Reason.CANCELED
                                : Reason.IO;
        return new CaptureFailure(
                reason,
                failure.getMessage() == null ? "Sort capture failed" : failure.getMessage(),
                new Coverage(
                        Completeness.PARTIAL,
                        descriptor.scope(),
                        List.of("Sort capture incomplete")),
                failure);
    }

    private void cleanup() throws IOException {
        IOException failure = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                failure = e;
            }
            reader = null;
        }
        for (var file : files) {
            try {
                file.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        synchronized (worker) {
            if (!closing.compareAndSet(false, true)) {
                return closed;
            }
            lifetime.cancel();
            var deliveryDrain =
                    CompletableFuture.allOf(
                            readDeliveries.stream()
                                    .map(delivery -> delivery.drained)
                                    .toArray(CompletableFuture[]::new));
            for (var delivery : List.copyOf(readDeliveries)) {
                delivery.abandon(
                        new java.util.concurrent.CancellationException("Sorted input closing"));
            }
            // Accepted queued reads now only fail their lifetime check; drain them without
            // leaving a full command queue that could reject the cleanup operation.
            Runnable queued;
            while ((queued = worker.getQueue().poll()) != null) {
                queued.run();
            }
            worker.execute(
                    () -> {
                        Throwable failure = null;
                        try {
                            cleanup();
                        } catch (Throwable e) {
                            failure = e;
                        }
                        worker.shutdown();
                        var terminal = failure;
                        deliveryDrain.whenComplete(
                                (ignored, drainFailure) ->
                                        CompletableFuture.runAsync(
                                                () -> {
                                                    if (terminal == null) {
                                                        closed.complete(null);
                                                    } else {
                                                        closed.completeExceptionally(terminal);
                                                    }
                                                }));
                    });
            return closed;
        }
    }
}
