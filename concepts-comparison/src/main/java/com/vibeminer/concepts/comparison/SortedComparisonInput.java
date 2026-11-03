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
     * core.start.
     */
    public static CompletableFuture<SortedComparisonInput> capture(
            Source source, Path parent, TempBudget budget, Cancellation token) {
        Objects.requireNonNull(token);
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
        input.worker.execute(
                () -> {
                    Throwable failure = null;
                    try {
                        input.captureAndSort(token);
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
                    if (failure != null) {
                        try {
                            input.cleanup();
                        } catch (Throwable cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        input.worker.shutdown();
                        var terminal = failure;
                        CompletableFuture.runAsync(() -> result.completeExceptionally(terminal));
                    } else {
                        CompletableFuture.runAsync(() -> result.complete(input));
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
                    long pageBytes = 4096;
                    for (var row : page.rows()) {
                        if (row == null) {
                            throw failure(Reason.INVALID_SOURCE, "Null unsorted record");
                        }
                        pageBytes =
                                Math.addExact(pageBytes, ComparisonCodec.retainedBytes(row) + 64);
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
                        long bytes = ComparisonCodec.retainedBytes(row) + 64;
                        if (bytes > CHUNK_BYTES) {
                            throw failure(Reason.LIMITED, "Record exceeds sort chunk policy");
                        }
                        if (chunkMemory.size() + bytes > CHUNK_BYTES && !chunk.isEmpty()) {
                            indexMemory.grow(2048, token);
                            runs.add(writeChunk(chunk, token));
                            chunk.clear();
                            chunkMemory.shrink(chunkMemory.size());
                        }
                        chunkMemory.grow(bytes, token);
                        chunk.add(row);
                    }
                    coverage = page.coverage();
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

    /** Core owns its planned 14 MiB page allowance; this adapter does not double-charge it. */
    @Override
    public CompletableFuture<InputPage> read(String cursor, int limit, Cancellation token) {
        if (limit < 1 || limit > PAGE_ROWS) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid sorted page size"));
        }
        if (closing.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Sorted input closed"));
        }
        var result = new CompletableFuture<InputPage>();
        try {
            synchronized (worker) {
                if (closing.get()) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Sorted input closed"));
                }
                worker.execute(
                        () -> {
                            try {
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
                                    var row = readBody(reader, pendingFrameLength);
                                    pendingFrameLength = null;
                                    var actual = ComparisonCodec.retainedBytes(row) + 64;
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
                                CompletableFuture.runAsync(() -> result.complete(page));
                            } catch (Throwable caught) {
                                CompletableFuture.runAsync(
                                        () -> result.completeExceptionally(caught));
                            }
                        });
            }
        } catch (Throwable rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
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
                failure instanceof TempBudget.Limit || failure instanceof ComparisonLimit
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
                        CompletableFuture.runAsync(
                                () -> {
                                    if (terminal == null) {
                                        closed.complete(null);
                                    } else {
                                        closed.completeExceptionally(terminal);
                                    }
                                });
                    });
            return closed;
        }
    }
}
