package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns bounded adapter work, not the borrowed build context. Cursors are session-only tokens. */
@SuppressWarnings("try")
abstract class SerialInventoryInput implements ComparisonInput {
    private final ComparisonTypes.Descriptor descriptor;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().daemon().name("comparison-inventory").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<Void> closing;
    private String expected;
    private long sequence;
    private boolean ended;

    SerialInventoryInput(ComparisonTypes.Descriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor);
    }

    @Override
    public final ComparisonTypes.Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public final synchronized CompletableFuture<ComparisonTypes.InputPage> read(
            String cursor, int limit, Cancellation token) {
        Objects.requireNonNull(token);
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("Inventory page limit is 1..256");
        }
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Inventory input closed"));
        }
        var result = new CompletableFuture<ComparisonTypes.InputPage>();
        var delivered = new CompletableFuture<ComparisonTypes.InputPage>();
        result.whenComplete(
                (value, error) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (error == null) {
                                        delivered.complete(value);
                                    } else {
                                        delivered.completeExceptionally(error);
                                    }
                                }));
        try {
            worker.execute(
                    () -> {
                        var request = new Cancellation();
                        try (var caller = token.onCancel(request::cancel);
                                var owner = lifetime.onCancel(request::cancel)) {
                            request.check();
                            if (ended || !Objects.equals(cursor, expected)) {
                                throw new IllegalArgumentException(
                                        "Stale or exhausted inventory cursor");
                            }
                            var page = page(limit, request);
                            request.check();
                            if (page.rows().size() > limit
                                    || !page.identity().equals(descriptor.identity())) {
                                throw new IllegalStateException(
                                        "Inventory source violated page identity/bounds");
                            }
                            ended = page.nextCursor() == null;
                            expected = ended ? null : Long.toString(++sequence);
                            result.complete(
                                    new ComparisonTypes.InputPage(
                                            descriptor.identity(),
                                            page.rows(),
                                            expected,
                                            page.coverage()));
                        } catch (Throwable error) {
                            ended = true;
                            result.completeExceptionally(error);
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(full);
        }
        return delivered;
    }

    protected abstract ComparisonTypes.InputPage page(int limit, Cancellation token);

    protected void release() {}

    @Override
    public final synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable cancellationFailure = null;
        try {
            lifetime.cancel();
        } catch (Throwable failure) {
            cancellationFailure = failure;
        }
        var cancelError = cancellationFailure;
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        release();
                        if (cancelError == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(cancelError);
                        }
                    } catch (Throwable error) {
                        if (cancelError != null && cancelError != error) {
                            error.addSuppressed(cancelError);
                        }
                        closing.completeExceptionally(error);
                    }
                });
        return closing.copy();
    }
}
