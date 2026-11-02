package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/** Bounded shared icon requests, independent of cell recycling and selection. */
final class AsyncIconRequests implements AutoCloseable {
    private final BiFunction<Long, Cancellation, CompletableFuture<BlpImageTypes.Result>> fetch;
    private final int parallel, capacity;
    private final LinkedHashMap<Long, Request> cache = new LinkedHashMap<>(16, .75f, true);
    private final ArrayDeque<Request> queued = new ArrayDeque<>();
    private int active;
    private boolean closed;

    private static final class Request {
        final long id;
        final Cancellation token = new Cancellation();
        final CompletableFuture<BlpImageTypes.Result> answer = new CompletableFuture<>();

        Request(long id) {
            this.id = id;
        }
    }

    AsyncIconRequests(
            int parallel,
            int capacity,
            BiFunction<Long, Cancellation, CompletableFuture<BlpImageTypes.Result>> fetch) {
        if (parallel < 1 || capacity < parallel)
            throw new IllegalArgumentException("Invalid icon limits");
        this.parallel = parallel;
        this.capacity = capacity;
        this.fetch = fetch;
    }

    synchronized CompletableFuture<BlpImageTypes.Result> request(long id) {
        if (closed)
            return CompletableFuture.failedFuture(new IllegalStateException("Icon loader closed"));
        var existing = cache.get(id);
        if (existing != null) return existing.answer.copy();
        if (cache.size() >= capacity) {
            var iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().answer.isDone()) {
                    iterator.remove();
                    break;
                }
            }
            if (cache.size() >= capacity)
                return CompletableFuture.failedFuture(new IllegalStateException("Icon queue full"));
        }
        var request = new Request(id);
        cache.put(id, request);
        queued.add(request);
        pump();
        return request.answer.copy();
    }

    synchronized void retain(Set<Long> interested) {
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var request = entry.getValue();
            if (request.answer.isDone()
                    && (request.answer.isCompletedExceptionally()
                            || request.answer.getNow(null) == null
                            || request.answer.getNow(null).state() != BlpImageTypes.State.READY)) {
                iterator.remove();
                continue;
            }
            if (!interested.contains(entry.getKey()) && !request.answer.isDone()) {
                request.token.cancel();
                request.answer.cancel(false);
                queued.remove(request);
                iterator.remove();
            }
        }
    }

    private void pump() {
        while (!closed && active < parallel && !queued.isEmpty()) {
            var request = queued.removeFirst();
            if (request.answer.isDone()) continue;
            active++;
            CompletableFuture.supplyAsync(
                            () -> fetch.apply(request.id, request.token),
                            command -> Thread.startVirtualThread(command))
                    .thenCompose(value -> value)
                    .whenComplete(
                            (value, error) -> {
                                synchronized (AsyncIconRequests.this) {
                                    active--;
                                    if (!request.answer.isDone()) {
                                        if (error == null) request.answer.complete(value);
                                        else request.answer.completeExceptionally(error);
                                    }
                                    pump();
                                }
                            });
        }
    }

    synchronized void clear() {
        for (var request : cache.values()) {
            request.token.cancel();
            request.answer.cancel(false);
        }
        queued.clear();
        cache.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (var request : cache.values()) {
            request.token.cancel();
            request.answer.cancel(false);
        }
        queued.clear();
        cache.clear();
    }
}
