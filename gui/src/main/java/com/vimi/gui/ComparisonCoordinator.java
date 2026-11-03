package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonService;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns one dedicated pair, including opening races and dependency-ordered teardown. */
final class ComparisonCoordinator implements BuildComparisons {
    record Opened(ComparisonInput input, AutoCloseable context) {
        Opened {
            Objects.requireNonNull(input);
            Objects.requireNonNull(context);
        }
    }

    interface EndpointOpener extends AutoCloseable {
        Opened open(BuildInfo build, String locale, String area, Cancellation token)
                throws Exception;

        default TempBudget budget() {
            return null;
        }

        default ComparisonService.MemoryPlan memoryPlan() {
            return ComparisonService.MemoryPlan.standard();
        }

        default TempBudget.Reservation retainedMetadata() {
            return null;
        }

        default void prepare(Opened before, Opened after, Cancellation token) throws Exception {}

        @Override
        default void close() {}
    }

    interface Factory {
        EndpointOpener capture(Request request, Cancellation token) throws Exception;
    }

    interface ServiceFactory {
        ComparisonService create(
                Path parent,
                TempBudget budget,
                ComparisonService.MemoryPlan plan,
                TempBudget.Reservation metadata)
                throws java.io.IOException;
    }

    private final ServiceFactory services;
    private final Factory factory;
    private final Path temporaryParent;
    private Run active;
    private boolean closing;
    private CompletableFuture<Void> closed;

    ComparisonCoordinator(Factory factory, Path temporaryParent) throws java.io.IOException {
        this(
                factory,
                temporaryParent,
                (parent, budget, plan, metadata) ->
                        budget == null
                                ? new ComparisonService(parent)
                                : new ComparisonService(parent, budget, plan, metadata));
    }

    ComparisonCoordinator(Factory factory, Path temporaryParent, ServiceFactory services)
            throws java.io.IOException {
        this.services = Objects.requireNonNull(services);
        this.factory = Objects.requireNonNull(factory);
        this.temporaryParent = temporaryParent;
        Files.createDirectories(temporaryParent);
    }

    @Override
    public CompletableFuture<Session> start(Request request, Cancellation cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(cancellation);
        if (request.before() == null
                || request.after() == null
                || !request.before().product().equals(request.after().product())
                || request.beforeLocale() == null
                || !request.beforeLocale().equals(request.afterLocale())
                || !java.util.Set.of("Files", "Tables", "Items").contains(request.area())
                || request.tableId() != null
                        && (!request.area().equals("Tables")
                                || request.tableId() < 1
                                || request.tableId() > 0xffffffffL)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Choose the same product and locale, and Files, Tables or Items for"
                                + " this inventory slice."));
        }
        var run = new Run(request, cancellation);
        synchronized (this) {
            if (closing || active != null)
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                closing
                                        ? "Comparisons closed"
                                        : "Close the current comparison before starting another."));
            active = run;
        }
        try {
            Thread.startVirtualThread(() -> open(run));
        } catch (Throwable failure) {
            run.setup.complete(null);
            fail(run, failure);
        }
        return run.opened.copy();
    }

    private void open(Run run) {
        Throwable failure = null;
        try {
            run.callerLink = run.caller.onCancel(run.token::cancel);
            run.token.check();
            var opener = factory.capture(run.request, run.token);
            run.opener = opener;
            run.a =
                    opener.open(
                            run.request.before(),
                            run.request.beforeLocale(),
                            run.request.area(),
                            run.token);
            run.token.check();
            run.b =
                    opener.open(
                            run.request.after(),
                            run.request.afterLocale(),
                            run.request.area(),
                            run.token);
            run.token.check();
            var a = run.a.input().descriptor();
            var b = run.b.input().descriptor();
            if (!a.product().equals(b.product())
                    || !a.locale().equals(b.locale())
                    || !a.namespace().equals(b.namespace())
                    || !a.scope().equals(b.scope()))
                throw new IllegalArgumentException(
                        run.request.tableId() == null
                                ? "Captured inventory scopes are incompatible."
                                : "Raw rows require matching table identity, layout, definition and"
                                        + " assessed field policy.");
            opener.prepare(run.a, run.b, run.token);
            run.token.check();
            var preparedA = run.a.input().descriptor();
            var preparedB = run.b.input().descriptor();
            if (!a.product().equals(preparedA.product())
                    || !a.locale().equals(preparedA.locale())
                    || !a.namespace().equals(preparedA.namespace())
                    || !b.product().equals(preparedB.product())
                    || !b.locale().equals(preparedB.locale())
                    || !b.namespace().equals(preparedB.namespace())
                    || !preparedA.scope().equals(preparedB.scope()))
                throw new IllegalArgumentException("Prepared comparison scopes are incompatible.");
            a = preparedA;
            b = preparedB;
            run.service =
                    services.create(
                            temporaryParent,
                            opener.budget(),
                            opener.memoryPlan(),
                            opener.retainedMetadata());
            run.mergeLink = run.token.onCancel(run.service::cancel);
            run.token.check();
            var completion = run.service.start(run.a.input(), run.b.input());
            run.admitted = run.service.inputsAccepted();
            if (!run.admitted) {
                completion.join();
                throw new IllegalStateException("Comparison inputs were not admitted");
            }
            run.token.check();
            run.session = new LiveSession(run, a, b);
        } catch (Throwable error) {
            failure = error;
        } finally {
            run.setup.complete(null);
        }
        if (failure != null) {
            fail(run, failure);
            return;
        }
        if (!run.opened.complete(run.session)) close(run);
    }

    private void fail(Run run, Throwable failure) {
        close(run)
                .whenComplete(
                        (ignored, cleanup) -> {
                            if (cleanup != null) failure.addSuppressed(unwrap(cleanup));
                            run.opened.completeExceptionally(unwrap(failure));
                        });
    }

    private CompletableFuture<Void> close(Run run) {
        synchronized (run) {
            if (run.closed != null) return run.closed.copy();
            run.closed = new CompletableFuture<>();
        }
        requestCancel(run);
        run.setup.whenComplete(
                (ignored, setupError) -> {
                    try {
                        Thread.startVirtualThread(() -> cleanup(run));
                    } catch (Throwable failure) {
                        synchronized (ComparisonCoordinator.this) {
                            if (active == run) active = null;
                        }
                        run.closed.completeExceptionally(failure);
                    }
                });
        return run.closed.copy();
    }

    private static void requestCancel(Run run) {
        try {
            run.token.cancel();
        } catch (Throwable failure) {
            synchronized (run) {
                run.cancelFailure = append(run.cancelFailure, failure);
            }
        }
        if (run.service != null) {
            try {
                run.service.cancel();
            } catch (Throwable failure) {
                synchronized (run) {
                    run.cancelFailure = append(run.cancelFailure, failure);
                }
            }
        }
    }

    private void cleanup(Run run) {
        Throwable failure = run.cancelFailure;
        if (run.service != null) {
            try {
                run.service.closeAsync().join();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        }
        if (!run.admitted) {
            if (run.b != null)
                try {
                    run.b.input().closeAsync().join();
                } catch (Throwable error) {
                    failure = append(failure, error);
                }
            if (run.a != null)
                try {
                    run.a.input().closeAsync().join();
                } catch (Throwable error) {
                    failure = append(failure, error);
                }
        }
        if (run.b != null)
            try {
                run.b.context().close();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        if (run.a != null)
            try {
                run.a.context().close();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        if (run.opener != null) {
            try {
                run.opener.close();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        }
        if (run.mergeLink != null)
            try {
                run.mergeLink.close();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        if (run.callerLink != null)
            try {
                run.callerLink.close();
            } catch (Throwable error) {
                failure = append(failure, error);
            }
        synchronized (this) {
            if (active == run) active = null;
        }
        if (failure == null) run.closed.complete(null);
        else run.closed.completeExceptionally(failure);
    }

    private static Throwable append(Throwable previous, Throwable next) {
        next = unwrap(next);
        if (previous == null) return next;
        if (previous != next) previous.addSuppressed(next);
        return previous;
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    @Override
    public synchronized CompletableFuture<Void> releaseCurrent() {
        return active == null ? CompletableFuture.completedFuture(null) : close(active);
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closed != null) return closed.copy();
        closing = true;
        closed = active == null ? CompletableFuture.completedFuture(null) : close(active);
        return closed.copy();
    }

    private static final class Run {
        final Request request;
        final Cancellation caller, token = new Cancellation();
        final CompletableFuture<Session> opened = new CompletableFuture<>();
        final CompletableFuture<Void> setup = new CompletableFuture<>();
        volatile Opened a, b;
        volatile EndpointOpener opener;
        volatile ComparisonService service;
        volatile Session session;
        volatile boolean admitted;
        volatile AutoCloseable callerLink, mergeLink;
        volatile Throwable cancelFailure;
        CompletableFuture<Void> closed;

        Run(Request request, Cancellation caller) {
            this.request = request;
            this.caller = caller;
        }
    }

    private final class LiveSession implements Session {
        private final Run run;
        private final ComparisonTypes.Descriptor a, b;

        LiveSession(Run run, ComparisonTypes.Descriptor a, ComparisonTypes.Descriptor b) {
            this.run = run;
            this.a = a;
            this.b = b;
        }

        public ComparisonTypes.Descriptor before() {
            return a;
        }

        public ComparisonTypes.Descriptor after() {
            return b;
        }

        public ComparisonTypes.Progress progress() {
            return run.service.progress();
        }

        public CompletableFuture<ComparisonTypes.ResultPage> page(long offset, int limit) {
            return run.service.page(offset, limit);
        }

        public void cancel() {
            requestCancel(run);
        }

        public CompletableFuture<Void> closeAsync() {
            return close(run);
        }
    }
}
