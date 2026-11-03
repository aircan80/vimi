package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.Difference;
import com.vibeminer.concepts.comparison.ComparisonTypes.Field;
import com.vibeminer.concepts.comparison.ComparisonTypes.FieldDelta;
import com.vibeminer.concepts.comparison.ComparisonTypes.InputPage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Membership;
import com.vibeminer.concepts.comparison.ComparisonTypes.Progress;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.ComparisonTypes.Result;
import com.vibeminer.concepts.comparison.ComparisonTypes.ResultPage;
import com.vibeminer.concepts.comparison.ComparisonTypes.RunState;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One session-only local comparison. Owns input adapters and staging, not borrowed build services.
 */
@SuppressWarnings("try")
public final class ComparisonService implements AutoCloseable {
    public static final int INPUT_PAGE = 256;
    public static final int RESULT_PAGE = 100;
    public static final int RECORD_BYTES = 1048576;
    public static final long BUFFER_BYTES = 32L * 1048576;
    public static final long STAGING_BYTES = 512L * 1048576;
    public static final long MAX_KEYS = 5000000;

    /** Fixed, checked phase policies. Retained metadata stays owned by the composition caller. */
    public static final class MemoryPlan {
        private final long inputPageBytes, resultWorkspaceBytes, retainedResultBytes, metadataBytes;

        private MemoryPlan(long input, long workspace, long retained, long metadata) {
            inputPageBytes = input;
            resultWorkspaceBytes = workspace;
            retainedResultBytes = retained;
            metadataBytes = metadata;
            if (Math.addExact(
                                    Math.addExact(
                                            Math.multiplyExact(2L, input),
                                            Math.addExact(CODEC_MEMORY, INDEX_MEMORY)),
                                    metadata)
                            > BUFFER_BYTES
                    || Math.addExact(Math.addExact(workspace, INDEX_MEMORY), metadata)
                            > BUFFER_BYTES
                    || retained > workspace
                    || Math.addExact(
                                    Math.addExact(candidateBytes(), RECORD_BYTES),
                                    decoderScratchBytes())
                            > workspace
                    || input <= 0
                    || workspace <= 0
                    || retained <= 0
                    || metadata < 0) {
                throw new IllegalArgumentException("Invalid comparison phase plan");
            }
        }

        public static MemoryPlan standard() {
            return new MemoryPlan(14L << 20, (32L << 20) - INDEX_MEMORY, 16L << 20, 0);
        }

        public static MemoryPlan withSnapshotMetadata() {
            return new MemoryPlan(12L << 20, 30L << 20, 12L << 20, 1L << 20);
        }

        public long inputPageBytes() {
            return inputPageBytes;
        }

        public long resultWorkspaceBytes() {
            return resultWorkspaceBytes;
        }

        public long retainedResultBytes() {
            return retainedResultBytes;
        }

        public long metadataBytes() {
            return metadataBytes;
        }

        public long candidateBytes() {
            return 12L << 20;
        }

        public long decoderScratchBytes() {
            return 5L << 20;
        }
    }

    private final ThreadPoolExecutor worker;
    private volatile long retainedInputBytes;
    private volatile long peakRetainedInputBytes;

    static ThreadPoolExecutor newWorker() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                Thread.ofPlatform().daemon().name("comparison").factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private final Cancellation lifetime = new Cancellation();
    private volatile RuntimeException cancellationFailure;
    private final Path directory;
    private final Path staging;
    private static final long INDEX_MEMORY = 512L << 10;
    private static final long CODEC_MEMORY = (4L << 20) - INDEX_MEMORY;
    private final long maxKeys;
    private final TempBudget budget;
    private final boolean ownsBudget;
    private final MemoryPlan memoryPlan;
    private final TempBudget.Reservation retainedMetadata;
    private final BudgetedTempFile resultFile;
    private TempBudget.Reservation indexReservation;
    private long[] offsets;
    private volatile Progress progress =
            new Progress(RunState.RUNNING, 0, 0, null, null, "Not started");
    private ComparisonInput a, b;
    private boolean started;
    private volatile long stagedRows;
    private CompletableFuture<Void> closing;
    private final java.util.Set<PageDelivery> deliveries = new java.util.HashSet<>();
    private final List<java.lang.ref.WeakReference<CompletableFuture<ResultPage>>> pageObservers =
            new ArrayList<>();

    public ComparisonService(Path temporaryParent) throws IOException {
        this(temporaryParent, MAX_KEYS, STAGING_BYTES);
    }

    /** Borrows a whole-job budget shared with prepared inputs/sort runs; never closes it. */
    public ComparisonService(Path temporaryParent, TempBudget budget) throws IOException {
        this(temporaryParent, MAX_KEYS, newWorker(), budget, false, MemoryPlan.standard(), null);
    }

    public ComparisonService(
            Path temporaryParent,
            TempBudget budget,
            MemoryPlan plan,
            TempBudget.Reservation retainedMetadata)
            throws IOException {
        this(temporaryParent, MAX_KEYS, newWorker(), budget, false, plan, retainedMetadata);
    }

    ComparisonService(Path temporaryParent, TempBudget budget, ThreadPoolExecutor worker)
            throws IOException {
        this(temporaryParent, MAX_KEYS, worker, budget, false, MemoryPlan.standard(), null);
    }

    ComparisonService(Path temporaryParent, long maxKeys, long maxStaging) throws IOException {
        this(temporaryParent, maxKeys, maxStaging, newWorker());
    }

    ComparisonService(
            Path temporaryParent, long maxKeys, long maxStaging, ThreadPoolExecutor worker)
            throws IOException {
        this(
                temporaryParent,
                maxKeys,
                worker,
                new TempBudget(BUFFER_BYTES, maxStaging),
                true,
                MemoryPlan.standard(),
                null);
    }

    private ComparisonService(
            Path temporaryParent,
            long maxKeys,
            ThreadPoolExecutor worker,
            TempBudget budget,
            boolean ownsBudget,
            MemoryPlan plan,
            TempBudget.Reservation retainedMetadata)
            throws IOException {
        if (maxKeys < 1 || maxKeys > MAX_KEYS) {
            throw new IllegalArgumentException("Invalid comparison identity bound");
        }
        this.worker = Objects.requireNonNull(worker);
        this.budget = Objects.requireNonNull(budget);
        this.ownsBudget = ownsBudget;
        this.memoryPlan = Objects.requireNonNull(plan);
        this.retainedMetadata = retainedMetadata;
        validateMetadata();
        this.maxKeys = maxKeys;
        directory = Files.createTempDirectory(temporaryParent, "comparison-");
        try {
            resultFile =
                    BudgetedTempFile.create(
                            directory, "results", budget, "core-results", Cancellation.NONE);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (ownsBudget) {
                budget.close();
            }
            throw failure;
        }
        staging = resultFile.path();
    }

    private void validateMetadata() {
        if (memoryPlan.metadataBytes() == 0) {
            if (retainedMetadata != null) {
                throw new IllegalArgumentException(
                        "Standard plan has no retained metadata reservation");
            }
        } else {
            Objects.requireNonNull(
                            retainedMetadata, "Actual retained metadata reservation required")
                    .requireMemory(budget, memoryPlan.metadataBytes());
            if (retainedMetadata.size() != memoryPlan.metadataBytes()) {
                throw new IllegalArgumentException("Retained metadata requires exact planned size");
            }
        }
    }

    public synchronized CompletableFuture<Progress> start(
            ComparisonInput left, ComparisonInput right) {
        if (started || closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Comparison already started or closed"));
        }
        try {
            validateMetadata();
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        var x = left.descriptor();
        var y = right.descriptor();
        if (!x.product().equals(y.product())
                || !x.locale().equals(y.locale())
                || !x.namespace().equals(y.namespace())
                || !x.scope().equals(y.scope())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Incompatible comparison scope"));
        }
        var result = new CompletableFuture<Progress>();
        try {
            worker.execute(
                    () -> {
                        // The caller still holds this monitor until accepted input ownership is
                        // published.
                        synchronized (ComparisonService.this) {
                            /* Admission publication barrier. */
                        }
                        try {
                            var completed = run(x, y);
                            CompletableFuture.runAsync(() -> result.complete(completed));
                        } catch (Throwable failure) {
                            CompletableFuture.runAsync(() -> result.completeExceptionally(failure));
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            progress =
                    new Progress(
                            RunState.FAILED,
                            0,
                            0,
                            null,
                            null,
                            "Comparison admission rejected; no inputs owned");
            return CompletableFuture.failedFuture(rejected);
        }
        progress = new Progress(RunState.RUNNING, 0, 0, null, null, "Comparison admitted");
        started = true;
        a = left;
        b = right;
        return result;
    }

    long retainedInputBytes() {
        return retainedInputBytes;
    }

    long peakRetainedInputBytes() {
        return peakRetainedInputBytes;
    }

    /** Whether start accepted input ownership; retained after close to prevent double cleanup. */
    public synchronized boolean inputsAccepted() {
        return started;
    }

    public Progress progress() {
        return progress;
    }

    public void cancel() {
        requestCancel();
    }

    private void requestCancel() {
        try {
            lifetime.cancel();
        } catch (RuntimeException failure) {
            cancellationFailure = failure;
        }
    }

    public synchronized CompletableFuture<ResultPage> page(long offset, int limit) {
        if (closing != null || !started) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Comparison not started or closed"));
        }
        if (offset < 0 || limit < 1 || limit > RESULT_PAGE) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid result page"));
        }
        try {
            validateMetadata();
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        return submitPage(offset, limit, Runnable::run);
    }

    private Progress run(Descriptor x, Descriptor y) throws IOException {
        var left = new Cursor(a, x, "core-a-page");
        var right = new Cursor(b, y, "core-b-page");
        long count = 0, bytes = 0;
        RunState state = RunState.COMPLETED;
        String message = "Session-only findings; known-equal rows omitted; no persistent report";
        validateMetadata();
        try (var workspace = budget.reserveMemory("core-codec", CODEC_MEMORY, lifetime)) {
            indexReservation = budget.reserveMemory("core-index", INDEX_MEMORY, lifetime);
            offsets = new long[Math.toIntExact((maxKeys + RESULT_PAGE - 1) / RESULT_PAGE)];
            // Start both initial reads before waiting: no more than two source reads in flight.
            left.request();
            right.request();
            while (true) {
                lifetime.check();
                validateMetadata();
                left.fill();
                right.fill();
                Record l = left.peek(), r = right.peek();
                if (l == null && r == null) {
                    break;
                }
                if (count >= maxKeys) {
                    state = RunState.LIMITED;
                    message = "Observed identity limit reached";
                    break;
                }
                int order = l == null ? 1 : r == null ? -1 : l.key().compareTo(r.key());
                Record la = order <= 0 ? l : null, rb = order >= 0 ? r : null;
                if (!knownEqual(la, rb)) {
                    byte[] frame = ComparisonCodec.pair(la, rb);
                    if (frame.length > RECORD_BYTES) {
                        state = RunState.LIMITED;
                        message = "Result staging limit reached";
                        break;
                    }
                    resultFile.appendFrame(frame, lifetime);
                    if (stagedRows % RESULT_PAGE == 0) {
                        offsets[Math.toIntExact(stagedRows / RESULT_PAGE)] = bytes;
                    }
                    bytes += 4L + frame.length;
                    stagedRows++;
                }
                count++;
                if (order <= 0) {
                    left.take();
                }
                if (order >= 0) {
                    right.take();
                }
                progress =
                        new Progress(
                                RunState.RUNNING,
                                count,
                                bytes,
                                left.coverage,
                                right.coverage,
                                "Comparing local captured inputs; findings-only results");
            }
        } catch (ComparisonLimit | TempBudget.Limit | ComparisonPolicyLimit limited) {
            state = RunState.LIMITED;
            message = limited.getMessage();
            requestCancel();
        } catch (java.util.concurrent.CancellationException canceled) {
            state = RunState.CANCELED;
            message = "Comparison canceled; partial session results";
        } catch (Exception failure) {
            state = lifetime.isCanceled() ? RunState.CANCELED : RunState.FAILED;
            requestCancel();
            message = "Comparison input/staging failed: " + failure.getClass().getSimpleName();
        } finally {
            left.drain();
            right.drain();
            try {
                resultFile.seal();
            } catch (IOException sealFailure) {
                state = RunState.FAILED;
                message = "Comparison result writer failed to seal";
            }
        }
        if (state != RunState.COMPLETED) {
            left.incomplete(message);
            right.incomplete(message);
        }
        progress =
                new Progress(
                        state,
                        count,
                        bytes,
                        left.coverage,
                        right.coverage,
                        message + "; findings-only, known-equal rows omitted");
        return progress;
    }

    private ResultPage read(long offset, int limit, Cancellation token) throws IOException {
        validateMetadata();
        var current = progress;
        if (current.state() == RunState.RUNNING || !started) {
            throw new IllegalStateException("Results available after run drains");
        }
        long total = stagedRows;
        if (offset >= total) {
            return new ResultPage(offset, List.of(), null, current);
        }
        var result = new ArrayList<Result>();
        long base = offset / RESULT_PAGE * RESULT_PAGE;
        long retainedBytes = 0;
        try (var file = new RandomAccessFile(staging.toFile(), "r")) {
            file.seek(offsets[Math.toIntExact(offset / RESULT_PAGE)]);
            for (long index = base; index < Math.min(total, offset + limit); index++) {
                token.check();
                validateMetadata();
                int n = file.readInt();
                if (n < 1 || n > RECORD_BYTES || n > file.length() - file.getFilePointer()) {
                    throw new IOException("Invalid result frame");
                }
                if (index < offset) {
                    file.seek(file.getFilePointer() + n);
                    continue;
                }
                byte[] frame = new byte[n];
                file.readFully(frame);
                long candidate =
                        ComparisonCodec.classifiedBytes(frame, memoryPlan.candidateBytes());
                boolean fits =
                        candidate <= memoryPlan.retainedResultBytes() - retainedBytes
                                && candidate + n + memoryPlan.decoderScratchBytes()
                                        <= memoryPlan.resultWorkspaceBytes() - retainedBytes;
                if (!fits) {
                    if (result.isEmpty()) {
                        throw new ComparisonLimit(
                                "First result candidate exceeds page memory plan");
                    }
                    break;
                }
                token.check();
                var pair = ComparisonCodec.pair(frame);
                result.add(classify(pair[0], pair[1], current));
                retainedBytes += candidate;
            }
        }
        long next = offset + result.size();
        return new ResultPage(offset, result, next < total ? next : null, current);
    }

    static Result classify(Record a, Record b, Progress progress) {
        var membership =
                a == null
                        ? (complete(progress.a()) ? Membership.ADDED : Membership.OBSERVED_ONLY_B)
                        : b == null
                                ? (complete(progress.b())
                                        ? Membership.REMOVED
                                        : Membership.OBSERVED_ONLY_A)
                                : Membership.BOTH;
        var keys = new TreeSet<String>();
        if (a != null) {
            keys.addAll(a.fields().keySet());
        }
        if (b != null) {
            keys.addAll(b.fields().keySet());
        }
        var fields = new ArrayList<FieldDelta>();
        for (String key : keys) {
            Field x = a == null ? null : a.fields().get(key),
                    y = b == null ? null : b.fields().get(key);
            Difference d;
            if (x == null || y == null) {
                d = Difference.UNKNOWN;
            } else {
                if (!x.semantics().equals(y.semantics())
                        || x.kind() != y.kind()
                        || x.aspect() != y.aspect()) {
                    d = Difference.INCOMPARABLE;
                } else {
                    if (!known(x) || !known(y)) {
                        d = Difference.UNKNOWN;
                    } else {
                        d =
                                x.state() == y.state() && Objects.equals(x.value(), y.value())
                                        ? Difference.EQUAL
                                        : Difference.CHANGED;
                    }
                }
            }
            fields.add(new FieldDelta(key, x, y, d, x != null ? x.aspect() : y.aspect()));
        }
        return new Result(a != null ? a.key() : b.key(), membership, a, b, fields);
    }

    private static boolean knownEqual(Record a, Record b) {
        if (a == null
                || b == null
                || a.fields().isEmpty()
                || !a.fields().keySet().equals(b.fields().keySet())) {
            return false;
        }
        for (var entry : a.fields().entrySet()) {
            Field x = entry.getValue(), y = b.fields().get(entry.getKey());
            if (!known(x)
                    || !known(y)
                    || x.kind() != y.kind()
                    || x.aspect() != y.aspect()
                    || !x.semantics().equals(y.semantics())
                    || x.state() != y.state()
                    || !Objects.equals(x.value(), y.value())) {
                return false;
            }
        }
        return true;
    }

    private static boolean known(Field f) {
        return f.state() == ValueState.VALUE || f.state() == ValueState.KNOWN_EMPTY;
    }

    private static boolean complete(Coverage c) {
        return c != null && c.completeness() == Completeness.COMPLETE;
    }

    private final class Cursor {
        final ComparisonInput input;
        final Descriptor descriptor;
        List<Record> rows = List.of();
        int index;
        String cursor, last;
        boolean exhausted;
        CompletableFuture<InputPage> pending;
        Coverage coverage;
        long pageBytes;
        final String owner;
        TempBudget.Reservation pendingMemory, pageMemory;

        Cursor(ComparisonInput input, Descriptor descriptor, String owner) {
            this.input = input;
            this.descriptor = descriptor;
            this.owner = owner;
            coverage =
                    new Coverage(
                            Completeness.UNKNOWN,
                            descriptor.scope(),
                            List.of("Not fully assessed"));
        }

        void request() throws IOException {
            if (!exhausted && pending == null) {
                pendingMemory = budget.reserveMemory(owner, memoryPlan.inputPageBytes(), lifetime);
                try {
                    pending =
                            Objects.requireNonNull(
                                    input.read(cursor, INPUT_PAGE, lifetime, pendingMemory));
                } catch (RuntimeException | Error failure) {
                    pendingMemory.close();
                    pendingMemory = null;
                    throw failure;
                }
            }
        }

        void fill() throws IOException {
            if (index < rows.size()) {
                return;
            }
            releasePage();
            if (exhausted) {
                return;
            }
            request();
            InputPage page;
            try {
                page = pending.join();
            } catch (java.util.concurrent.CompletionException failure) {
                if (failure.getCause() instanceof TempBudget.Limit limited) {
                    throw limited;
                }
                if (failure.getCause() instanceof ComparisonLimit limited) {
                    throw limited;
                }
                if (failure.getCause() instanceof ComparisonPolicyLimit limited) {
                    throw limited;
                }
                throw failure;
            }
            pending = null;
            pageMemory = pendingMemory;
            pendingMemory = null;
            lifetime.check();
            if (!descriptor.identity().equals(page.identity())
                    || !descriptor.scope().equals(page.coverage().scope())
                    || page.rows().size() > INPUT_PAGE) {
                throw new IOException("Unstable input page identity/scope/size");
            }
            if (page.nextCursor() != null
                    && (page.nextCursor().equals(cursor)
                            || page.rows().isEmpty()
                            || page.coverage().completeness() == Completeness.COMPLETE)) {
                throw new IOException("Invalid continuation/completeness");
            }
            var bounded = new ArrayList<Record>();
            long retained = 0;
            for (var row : page.rows()) {
                if (last != null && last.compareTo(row.key()) >= 0) {
                    throw new IOException("Input keys not strictly ordered and unique");
                }
                last = row.key();
                var safe = ComparisonCodec.bounded(row, RECORD_BYTES / 2);
                retained += ComparisonCodec.retainedBytes(safe);
                if (retained > memoryPlan.inputPageBytes()) {
                    throw new ComparisonLimit("Input page working buffer limit reached");
                }
                long size = ComparisonCodec.retainedBytes(safe);
                retainedInputBytes += size;
                pageBytes += size;
                peakRetainedInputBytes = Math.max(peakRetainedInputBytes, retainedInputBytes);
                if (retainedInputBytes > 2L * memoryPlan.inputPageBytes()) {
                    throw new ComparisonLimit("Canonical input buffer limit reached");
                }
                bounded.add(safe);
            }
            rows = List.copyOf(bounded);
            index = 0;
            cursor = page.nextCursor();
            exhausted = cursor == null;
            coverage = page.coverage();
        }

        Record peek() {
            return index < rows.size() ? rows.get(index) : null;
        }

        void take() {
            index++;
        }

        void incomplete(String issue) {
            coverage = new Coverage(Completeness.PARTIAL, descriptor.scope(), List.of(issue));
        }

        void drain() {
            if (pending != null) {
                try {
                    pending.join();
                } catch (Exception ignored) {
                    /* Drain failed input operation before adapter closure. */
                }
                pending = null;
            }
            if (pendingMemory != null) {
                pendingMemory.close();
                pendingMemory = null;
            }
            releasePage();
        }

        private void releasePage() {
            rows = List.of();
            index = 0;
            retainedInputBytes -= pageBytes;
            pageBytes = 0;
            if (pageMemory != null) {
                pageMemory.close();
                pageMemory = null;
            }
        }
    }

    // Package-private held-delivery boundary used by deterministic lifetime fixtures.
    synchronized CompletableFuture<ResultPage> page(
            long offset, int limit, java.util.concurrent.Executor deliveryExecutor) {
        if (closing != null || !started || offset < 0 || limit < 1 || limit > RESULT_PAGE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Invalid/closed result page"));
        }
        try {
            validateMetadata();
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        return submitPage(offset, limit, deliveryExecutor);
    }

    synchronized CompletableFuture<ResultPage> page(
            long offset,
            int limit,
            java.util.concurrent.Executor deliveryExecutor,
            Runnable afterReservation) {
        if (closing != null || !started || offset < 0 || limit < 1 || limit > RESULT_PAGE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Invalid/closed result page"));
        }
        try {
            validateMetadata();
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        return submitPage(offset, limit, deliveryExecutor, afterReservation);
    }

    private CompletableFuture<ResultPage> submitPage(
            long offset, int limit, java.util.concurrent.Executor executor) {
        return submitPage(offset, limit, executor, () -> {});
    }

    private CompletableFuture<ResultPage> submitPage(
            long offset,
            int limit,
            java.util.concurrent.Executor executor,
            Runnable afterReservation) {
        pageObservers.removeIf(
                reference -> {
                    var observer = reference.get();
                    return observer == null || observer.isDone();
                });
        if (deliveries.size() >= 8 || pageObservers.size() >= 16) {
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.RejectedExecutionException(
                            "Comparison delivery limit"));
        }
        var delivery = new PageDelivery(executor);
        deliveries.add(delivery);
        pageObservers.add(new java.lang.ref.WeakReference<>(delivery.result));
        try {
            worker.execute(
                    () -> {
                        synchronized (ComparisonService.this) {
                            /* Admission publication barrier. */
                        }
                        try {
                            delivery.token.check();
                            validateMetadata();
                            delivery.workspace =
                                    budget.reserveMemory(
                                            "core-result-page",
                                            memoryPlan.resultWorkspaceBytes(),
                                            delivery.token);
                            afterReservation.run();
                            var value = read(offset, limit, delivery.token);
                            delivery.store(value);
                            value = null;
                        } catch (Throwable failure) {
                            delivery.storeFailure(failure);
                        } finally {
                            delivery.workerFinished();
                        }
                    });
        } catch (Throwable rejected) {
            delivery.storeFailure(rejected);
            delivery.workerFinished();
        }
        return delivery.result;
    }

    private final class PageDelivery {
        final java.util.concurrent.Executor executor;
        final Cancellation token = new Cancellation();
        final CompletableFuture<ResultPage> result = new CompletableFuture<>();
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        TempBudget.Reservation workspace;
        ResultPage page;
        Throwable failure;
        boolean finished, abandoned, claimed;

        PageDelivery(java.util.concurrent.Executor executor) {
            this.executor = Objects.requireNonNull(executor);
            result.whenComplete(
                    (value, error) -> {
                        if (result.isCancelled()) {
                            abandon(new java.util.concurrent.CancellationException());
                        }
                    });
        }

        synchronized void store(ResultPage value) {
            page = value;
        }

        synchronized void storeFailure(Throwable error) {
            failure = error;
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
                            } catch (Throwable error) {
                                abandon(error);
                            }
                        });
            }
        }

        void abandon(Throwable reason) {
            token.cancel();
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

        void discard() {
            Throwable error;
            synchronized (this) {
                if (claimed || !finished) {
                    return;
                }
                claimed = true;
                page = null;
                error =
                        failure == null
                                ? new java.util.concurrent.CancellationException()
                                : failure;
            }
            release();
            CompletableFuture.runAsync(() -> result.completeExceptionally(error));
        }

        void deliver() {
            try {
                validateMetadata();
            } catch (Throwable error) {
                abandon(error);
                return;
            }
            ResultPage value;
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
            release();
            result.complete(value);
        }

        void release() {
            synchronized (ComparisonService.this) {
                if (workspace != null) {
                    workspace.close();
                    workspace = null;
                }
                drained.complete(null);
                deliveries.remove(this);
            }
        }
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        requestCancel();
        var observers =
                pageObservers.stream()
                        .map(java.lang.ref.WeakReference::get)
                        .filter(Objects::nonNull)
                        .toList();
        var deliveryDrain =
                CompletableFuture.allOf(
                        deliveries.stream()
                                .map(delivery -> delivery.drained)
                                .toArray(CompletableFuture[]::new));
        for (var delivery : List.copyOf(deliveries)) {
            delivery.abandon(new java.util.concurrent.CancellationException("Comparison closing"));
        }
        // Dedicated closer cannot be rejected by the bounded command queue; accepted work drains
        // first.
        closing = new CompletableFuture<>();
        worker.shutdown();
        Thread.ofPlatform()
                .daemon()
                .name("comparison-close")
                .start(
                        () -> {
                            Throwable problem = cancellationFailure;
                            boolean interrupted = false;
                            boolean terminated = false;
                            while (!terminated) {
                                try {
                                    terminated = worker.awaitTermination(60, TimeUnit.SECONDS);
                                } catch (InterruptedException interruption) {
                                    interrupted = true;
                                }
                            }
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            deliveryDrain.join();
                            // Wait only for each public future's terminal state, never for its
                            // callbacks: a consumer may itself call close().join().
                            while (observers.stream().anyMatch(observer -> !observer.isDone())) {
                                java.util.concurrent.locks.LockSupport.parkNanos(
                                        TimeUnit.MILLISECONDS.toNanos(1));
                            }
                            pageObservers.clear();
                            for (var input :
                                    a == b
                                            ? new ComparisonInput[] {a}
                                            : new ComparisonInput[] {a, b}) {
                                if (input != null) {
                                    try {
                                        input.closeAsync().join();
                                    } catch (Exception failure) {
                                        if (problem == null) {
                                            problem = failure;
                                        } else {
                                            problem.addSuppressed(failure);
                                        }
                                    }
                                }
                            }
                            try {
                                resultFile.close();
                                Files.deleteIfExists(directory);
                            } catch (IOException failure) {
                                if (problem == null) {
                                    problem = failure;
                                } else {
                                    problem.addSuppressed(failure);
                                }
                            }
                            offsets = null;
                            if (indexReservation != null) {
                                indexReservation.close();
                                indexReservation = null;
                            }
                            if (ownsBudget) {
                                try {
                                    budget.close();
                                } catch (RuntimeException failure) {
                                    if (problem == null) {
                                        problem = failure;
                                    } else {
                                        problem.addSuppressed(failure);
                                    }
                                }
                            }
                            progress =
                                    new Progress(
                                            RunState.CLOSED,
                                            progress.observed(),
                                            progress.stagedBytes(),
                                            progress.a(),
                                            progress.b(),
                                            "Session closed; temporary results removed");
                            if (problem == null) {
                                closing.complete(null);
                            } else {
                                closing.completeExceptionally(problem);
                            }
                        });
        return closing;
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
