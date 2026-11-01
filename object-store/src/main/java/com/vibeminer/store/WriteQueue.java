package com.vibeminer.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serial I/O with FIFO asynchronous admission and bounded retained input. */
final class WriteQueue {
    static final int QUEUED_REQUESTS = 64;
    static final int WAITING_REQUESTS = 64;
    static final long RETAINED_BYTES = 512L << 20;

    record State(int queued, int waiting, long retainedBytes) {}

    private final Object monitor = new Object();
    private final Deque<Request<?>> queued = new ArrayDeque<>();
    private final Deque<Request<?>> waiting = new ArrayDeque<>();
    private final ThreadPoolExecutor worker = Async.pool("vimi-store-writer", 1);
    private final int queueLimit;
    private final int waitingLimit;
    private final long byteLimit;
    private boolean draining;
    private boolean closed;
    private long retainedBytes;

    WriteQueue() {
        this(QUEUED_REQUESTS, WAITING_REQUESTS, RETAINED_BYTES);
    }

    WriteQueue(int queueLimit, int waitingLimit, long byteLimit) {
        if (queueLimit < 1 || waitingLimit < 0 || byteLimit < 0) {
            throw new IllegalArgumentException("Invalid writer admission bounds");
        }
        this.queueLimit = queueLimit;
        this.waitingLimit = waitingLimit;
        this.byteLimit = byteLimit;
    }

    <T> CompletableFuture<T> submit(long bytes, Cancellation cancellation, Async.Work<T> work) {
        return submit(bytes, cancellation, work, null, null);
    }

    <T> CompletableFuture<T> submit(
            long bytes,
            Cancellation cancellation,
            Async.Work<T> work,
            Runnable preparation,
            CompletableFuture<?> readiness) {
        Request<T> request;
        boolean admitted;
        synchronized (monitor) {
            if (closed) {
                return CompletableFuture.failedFuture(ObjectStore.closed());
            }
            if (bytes < 0 || bytes > byteLimit - retainedBytes) {
                return limit("Writer retained-payload budget is in use");
            }
            boolean mustWait = queued.size() >= queueLimit;
            if (mustWait && cancellation.isCanceled()) {
                return CompletableFuture.failedFuture(new CancellationException());
            }
            if (mustWait && waiting.size() >= waitingLimit) {
                return limit("Writer admission-waiter budget is in use");
            }
            request = new Request<>(bytes, work, preparation, readiness);
            admitted = !mustWait;
            retainedBytes += bytes;
            if (mustWait) {
                request.waiting = true;
                waiting.addLast(request);
                request.registration = cancellation.onCancel(() -> cancelWaiting(request));
            } else {
                queued.addLast(request);
            }
        }
        if (admitted) {
            start(request);
        }
        return request.result;
    }

    private void start(Request<?> request) {
        Runnable preparation;
        CompletableFuture<?> readiness;
        synchronized (monitor) {
            preparation = request.preparation;
            request.preparation = null;
            readiness = request.readiness;
        }
        if (preparation != null) {
            readiness.whenComplete((ignored, error) -> wake());
            try {
                preparation.run();
            } catch (Throwable error) {
                readiness.completeExceptionally(error);
            }
        }
        wake();
    }

    private void wake() {
        synchronized (monitor) {
            if (!draining && !queued.isEmpty() && queued.getFirst().ready()) {
                draining = true;
                worker.execute(this::drain);
            } else if (closed && !draining && queued.isEmpty()) {
                worker.shutdown();
            }
        }
    }

    boolean hasReadyWork() {
        synchronized (monitor) {
            return !queued.isEmpty() && queued.getFirst().ready();
        }
    }

    // Internal deterministic preparation barrier for diagnostics and component tests.
    CompletableFuture<Void> preparations() {
        synchronized (monitor) {
            return CompletableFuture.allOf(
                    queued.stream()
                            .map(request -> request.readiness)
                            .filter(Objects::nonNull)
                            .toArray(CompletableFuture[]::new));
        }
    }

    private static <T> CompletableFuture<T> limit(String message) {
        return CompletableFuture.failedFuture(new StoreException("OBJECT_STORE_LIMIT", message));
    }

    private void cancelWaiting(Request<?> request) {
        synchronized (monitor) {
            if (!request.waiting || !waiting.remove(request)) {
                return;
            }
            discard(request);
        }
        request.fail(new CancellationException());
    }

    private void discard(Request<?> request) {
        request.waiting = false;
        request.registration.close();
        request.work = null;
        request.preparation = null;
        request.readiness = null;
        retainedBytes -= request.bytes;
    }

    private void drain() {
        while (true) {
            Request<?> request;
            List<Request<?>> promoted = new ArrayList<>();
            synchronized (monitor) {
                request = queued.peekFirst();
                if (request == null || !request.ready()) {
                    draining = false;
                    if (closed && request == null) {
                        worker.shutdown();
                    }
                    return;
                }
                queued.removeFirst();
                while (queued.size() < queueLimit && !waiting.isEmpty()) {
                    Request<?> admitted = waiting.removeFirst();
                    admitted.waiting = false;
                    admitted.registration.close();
                    queued.addLast(admitted);
                    promoted.add(admitted);
                }
            }
            for (Request<?> admitted : promoted) {
                start(admitted);
            }
            Runnable publish = request.perform();
            synchronized (monitor) {
                request.work = null;
                request.readiness = null;
                retainedBytes -= request.bytes;
            }
            // User continuations must never occupy the sole disk writer or an admission lock.
            Thread.ofVirtual().name("vimi-store-completion").start(publish);
        }
    }

    void shutdown() {
        Deque<Request<?>> rejected = new ArrayDeque<>();
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            while (!waiting.isEmpty()) {
                Request<?> request = waiting.removeFirst();
                discard(request);
                rejected.addLast(request);
            }
            if (!draining && queued.isEmpty()) {
                worker.shutdown();
            }
        }
        for (Request<?> request : rejected) {
            request.fail(ObjectStore.closed());
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, unit);
    }

    State state() {
        synchronized (monitor) {
            return new State(queued.size(), waiting.size(), retainedBytes);
        }
    }

    private static final class Request<T> {
        final long bytes;
        final CompletableFuture<T> result = new CompletableFuture<>();
        Async.Work<T> work;
        Runnable preparation;
        CompletableFuture<?> readiness;
        boolean waiting;
        Cancellation.Registration registration = () -> {};

        Request(
                long bytes,
                Async.Work<T> work,
                Runnable preparation,
                CompletableFuture<?> readiness) {
            this.bytes = bytes;
            this.work = work;
            this.preparation = preparation;
            this.readiness = readiness;
        }

        boolean ready() {
            return readiness == null || readiness.isDone();
        }

        Runnable perform() {
            try {
                T value = work.run();
                return () -> result.complete(value);
            } catch (Throwable error) {
                return () -> result.completeExceptionally(error);
            }
        }

        void fail(Throwable error) {
            Thread.ofVirtual()
                    .name("vimi-store-admission-result")
                    .start(() -> result.completeExceptionally(error));
        }
    }
}
