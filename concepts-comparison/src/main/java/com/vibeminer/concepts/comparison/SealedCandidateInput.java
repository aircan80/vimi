package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.InputPage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Ordered session candidates with a twelve MiB combined source/core page allowance. */
public final class SealedCandidateInput implements ComparisonInput {
    public static final long READ_ALLOWANCE_BYTES = 12L << 20;
    public static final long PAGE_BYTES = 2L << 20;
    public static final long DECODED_CANDIDATE_BYTES = 2L << 20;
    public static final long PROJECTOR_BYTES = 3L << 20;
    private final BudgetedTempFile file;
    private final Descriptor descriptor;
    private final TempBudget budget;
    private final Function<Record, Record> projector;
    private final long length;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon().name("comparison-candidates").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Set<Delivery> deliveries = new HashSet<>();
    private CompletableFuture<Void> closing;
    private long position, sequence, observed;
    private String expected, previous;
    private boolean ended, failed;

    private SealedCandidateInput(
            BudgetedTempFile file,
            Descriptor descriptor,
            TempBudget budget,
            Function<Record, Record> projector,
            long length) {
        this.file = file;
        this.descriptor = descriptor;
        this.budget = budget;
        this.projector = projector;
        this.length = length;
    }

    /**
     * Seals and adopts exclusive ownership of one file of (stageRecord, null) frames. Failure
     * leaves the file caller-owned (possibly sealed); success requires the caller to stop using it.
     *
     * <p>The pure deterministic projector must plan allocations before making them: at most three
     * MiB per invocation including its new output, at most two MiB retained output, unchanged key,
     * no I/O or retained source/backlinks. This contract cannot sandbox an arbitrary Java function.
     * The twelve MiB allowance covers frame 1 + decoded stage 2 + retained page 2 + projector 3 +
     * core canonical overlap 2 + decoder/container scratch 2 MiB. Snapshot metadata remains
     * externally owned and charged; this input never closes its budget or metadata reservation. A
     * null projected record excludes an identity under the final paired mask. Such wire records are
     * consumed with cancellation/order/identity limits intact, without an empty intermediate page
     * or a fabricated empty record. Projectors must be repeatable: a nonfitting record is projected
     * again on the next read.
     */
    public static SealedCandidateInput adopt(
            BudgetedTempFile file,
            Descriptor descriptor,
            TempBudget budget,
            Function<Record, Record> projector)
            throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(projector);
        file.requireBudget(budget);
        file.seal();
        long length = Files.size(file.path());
        if (length != file.size()) {
            throw new IOException("Candidate file has an incomplete charged write");
        }
        return new SealedCandidateInput(file, descriptor, budget, projector, length);
    }

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public CompletableFuture<InputPage> read(String cursor, int limit, Cancellation token) {
        return readStandalone(cursor, limit, token, Runnable::run);
    }

    CompletableFuture<InputPage> readStandalone(
            String cursor, int limit, Cancellation token, Executor deliveryExecutor) {
        try {
            validateLimit(limit);
            var allowance =
                    budget.reserveMemory("candidate-standalone", READ_ALLOWANCE_BYTES, token);
            return submit(cursor, limit, token, allowance, true, deliveryExecutor);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<InputPage> read(
            String cursor, int limit, Cancellation token, TempBudget.Reservation allowance) {
        return submit(cursor, limit, token, allowance, false, Runnable::run);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("Candidate page limit is 1..256");
        }
    }

    private synchronized CompletableFuture<InputPage> submit(
            String cursor,
            int limit,
            Cancellation token,
            TempBudget.Reservation allowance,
            boolean owned,
            Executor executor) {
        try {
            validateLimit(limit);
            Objects.requireNonNull(token);
            Objects.requireNonNull(executor);
            Objects.requireNonNull(allowance).requireMemory(budget, READ_ALLOWANCE_BYTES);
            if (closing != null
                    || deliveries.size() >= 8
                    || deliveries.stream().anyMatch(d -> d.allowance == allowance)) {
                throw new IllegalStateException("Candidate input closed or read allowance busy");
            }
        } catch (Throwable error) {
            if (owned && allowance != null) {
                allowance.close();
            }
            return CompletableFuture.failedFuture(error);
        }
        var delivery = new Delivery(allowance, owned, executor);
        deliveries.add(delivery);
        try {
            worker.execute(
                    () -> {
                        synchronized (SealedCandidateInput.this) {
                            /* Publish admission before worker completion. */
                        }
                        try {
                            delivery.store(page(cursor, limit, token, delivery.token, allowance));
                        } catch (Throwable error) {
                            delivery.storeFailure(error);
                        } finally {
                            delivery.finish();
                        }
                    });
        } catch (Throwable error) {
            delivery.storeFailure(error);
            delivery.finish();
        }
        return delivery.result;
    }

    @SuppressWarnings("try")
    private InputPage page(
            String cursor,
            int limit,
            Cancellation caller,
            Cancellation delivery,
            TempBudget.Reservation allowance)
            throws IOException {
        var request = new Cancellation();
        try (var a = caller.onCancel(request::cancel);
                var b = lifetime.onCancel(request::cancel);
                var c = delivery.onCancel(request::cancel)) {
            request.check();
            allowance.requireMemory(budget, READ_ALLOWANCE_BYTES);
            if (failed || ended || !Objects.equals(expected, cursor)) {
                throw new IllegalArgumentException("Stale, failed or exhausted candidate cursor");
            }
            var rows = new ArrayList<Record>();
            long at = position, count = observed, retained = 4096;
            String last = previous;
            try (var input = new RandomAccessFile(file.path().toFile(), "r")) {
                if (input.length() != length) {
                    throw new IOException("Candidate file changed");
                }
                input.seek(at);
                while (rows.size() < limit && input.getFilePointer() < length) {
                    request.check();
                    allowance.requireMemory(budget, READ_ALLOWANCE_BYTES);
                    long start = input.getFilePointer();
                    if (length - start < 4) {
                        throw new IOException("Truncated candidate prefix");
                    }
                    int n = input.readInt();
                    if (n < 1
                            || n > ComparisonService.RECORD_BYTES
                            || n > length - input.getFilePointer()) {
                        throw new IOException("Invalid candidate frame length");
                    }
                    byte[] frame = new byte[n];
                    input.readFully(frame);
                    // Structural validation/estimate precedes all decoded strings/maps/records.
                    ComparisonCodec.classifiedBytes(frame, DECODED_CANDIDATE_BYTES);
                    var pair = ComparisonCodec.pair(frame);
                    if (pair[0] == null || pair[1] != null) {
                        throw new IOException("Expected one candidate source record");
                    }
                    var stage = pair[0];
                    if (stage.key().length() > 4096
                            || last != null && last.compareTo(stage.key()) >= 0) {
                        throw new IOException("Candidate keys are not strictly ordered");
                    }
                    request.check();
                    if (count >= ComparisonService.MAX_KEYS) {
                        throw new ComparisonLimit("Candidate identity limit reached");
                    }
                    var projected = projector.apply(stage);
                    if (projected == null) {
                        last = stage.key();
                        count++;
                        continue;
                    }
                    if (!projected.key().equals(stage.key()) || projected.fields().size() > 4096) {
                        throw new IOException("Projector changed candidate identity/shape");
                    }
                    long bytes = ComparisonCodec.retainedBytes(projected);
                    if (bytes > PAGE_BYTES - 4096) {
                        throw new ComparisonLimit("Projected candidate exceeds page policy");
                    }
                    if (wireBytes(projected) > ComparisonService.RECORD_BYTES) {
                        throw new ComparisonLimit("Projected candidate exceeds frame policy");
                    }
                    if (retained + bytes > PAGE_BYTES) {
                        // Re-decode on the next read; no uncharged pending frame/DTO is retained.
                        input.seek(start);
                        break;
                    }
                    rows.add(projected);
                    retained += bytes;
                    last = stage.key();
                    count++;
                }
                at = input.getFilePointer();
            }
            request.check();
            boolean done = at == length;
            String next = done ? null : Long.toString(sequence + 1);
            var result =
                    new InputPage(
                            descriptor.identity(),
                            rows,
                            next,
                            new Coverage(
                                    Completeness.UNKNOWN,
                                    descriptor.scope(),
                                    List.of(
                                            "Observed candidate identities; membership is not certified")));
            position = at;
            previous = last;
            observed = count;
            if (!done) {
                sequence++;
            }
            expected = next;
            ended = done;
            return result;
        } catch (Throwable error) {
            failed = true;
            throw error;
        }
    }

    // Exact serialized size without allocating an output frame or UTF-8 copies.
    private static long wireBytes(Record record) throws IOException {
        long size = 2L + stringBytes(record.key()) + 4L;
        for (var entry : record.fields().entrySet()) {
            var field = entry.getValue();
            size +=
                    stringBytes(entry.getKey())
                            + stringBytes(field.semantics())
                            + 12L
                            + stringBytes(field.value())
                            + evidenceBytes(field.evidence());
        }
        return size + evidenceBytes(record.evidence());
    }

    private static long evidenceBytes(List<ComparisonTypes.Evidence> evidence) throws IOException {
        if (evidence.size() > 65536) {
            throw new ComparisonLimit("Candidate evidence exceeds policy");
        }
        long size = 4;
        for (var value : evidence) {
            size +=
                    stringBytes(value.source())
                            + stringBytes(value.identity())
                            + stringBytes(value.field())
                            + stringBytes(value.raw());
        }
        return size;
    }

    private static long stringBytes(String value) throws IOException {
        long size = 4;
        if (value == null) {
            return size;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128) {
                size++;
            } else if (c < 2048) {
                size += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IOException("Invalid projected Unicode");
                }
                size += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new IOException("Invalid projected Unicode");
            } else {
                size += 3;
            }
        }
        return size;
    }

    private final class Delivery {
        final TempBudget.Reservation allowance;
        final boolean owned;
        final Executor executor;
        final Cancellation token = new Cancellation();
        final CompletableFuture<InputPage> result = new CompletableFuture<>();
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        InputPage value;
        Throwable error;
        boolean finished, abandoned, claimed;

        Delivery(TempBudget.Reservation allowance, boolean owned, Executor executor) {
            this.allowance = allowance;
            this.owned = owned;
            this.executor = executor;
            result.whenComplete(
                    (v, e) -> {
                        if (result.isCancelled()) {
                            abandon(new java.util.concurrent.CancellationException());
                        }
                    });
        }

        synchronized void store(InputPage page) {
            value = page;
        }

        synchronized void storeFailure(Throwable failure) {
            error = failure;
        }

        void finish() {
            boolean discard;
            synchronized (this) {
                finished = true;
                discard = abandoned || error != null || result.isDone();
            }
            if (discard) {
                discard();
            } else {
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                executor.execute(this::deliver);
                            } catch (Throwable failure) {
                                abandon(failure);
                            }
                        });
            }
        }

        void abandon(Throwable failure) {
            token.cancel();
            boolean discard;
            synchronized (this) {
                if (claimed) {
                    return;
                }
                abandoned = true;
                if (error == null) {
                    error = failure;
                }
                discard = finished;
            }
            if (discard) {
                discard();
            }
        }

        void discard() {
            Throwable failure;
            synchronized (this) {
                if (claimed || !finished) {
                    return;
                }
                claimed = true;
                value = null;
                failure = error == null ? new java.util.concurrent.CancellationException() : error;
            }
            release();
            CompletableFuture.runAsync(() -> result.completeExceptionally(failure));
        }

        void deliver() {
            InputPage page;
            synchronized (this) {
                if (claimed) {
                    return;
                }
                page = abandoned || result.isDone() ? null : value;
                if (page != null) {
                    claimed = true;
                    value = null;
                }
            }
            if (page == null) {
                discard();
                return;
            }
            release();
            result.complete(page);
        }

        void release() {
            synchronized (SealedCandidateInput.this) {
                if (owned) {
                    allowance.close();
                }
                drained.complete(null);
                deliveries.remove(this);
            }
        }
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        lifetime.cancel();
        var drain =
                CompletableFuture.allOf(
                        deliveries.stream().map(d -> d.drained).toArray(CompletableFuture[]::new));
        for (var d : List.copyOf(deliveries)) {
            d.abandon(new java.util.concurrent.CancellationException("Candidate input closing"));
        }
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    Throwable error = null;
                    boolean interrupted = false;
                    while (!worker.isTerminated()) {
                        try {
                            worker.awaitTermination(60, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    try {
                        drain.join();
                        file.close();
                    } catch (Throwable failure) {
                        error = failure;
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (error == null) {
                        closing.complete(null);
                    } else {
                        closing.completeExceptionally(error);
                    }
                });
        return closing.copy();
    }
}
