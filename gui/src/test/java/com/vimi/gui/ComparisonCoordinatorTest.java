package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic application lifetime contracts, independent of native data or the JavaFX toolkit.
 */
public final class ComparisonCoordinatorTest {
    private ComparisonCoordinatorTest() {}

    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("vimi-comparison-coordinator-");
        try {
            LocalEffectsComparisonTest.run(directory);
            normalPair(directory);
            cancelOpening(directory);
            cancelReads(directory);
            failedSecondOpen(directory);
            rejectedScope(directory);
            rejectedCoreAdmission(directory);
            failedAdapterClose(directory);
            staleCleanup(directory);
            sourceHint();
            rawPairAdmission(directory);
            preparedScopeAdmission(directory, false);
            preparedScopeAdmission(directory, true);
            System.out.println(
                    "Comparison composition: pair ownership, opening/read cancellation, rejection,"
                            + " failure drain, stale staging and source context passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static BuildInfo build(String key) {
        return new BuildInfo(
                "wowf",
                "Forever",
                "1.60.1." + key,
                "us",
                key,
                BuildInfo.BuildSource.CURRENT,
                true,
                null,
                0);
    }

    private static BuildComparisons.Request request() {
        return new BuildComparisons.Request(build("a"), "enUS", build("b"), "enUS", "Files");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void failure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            throw new AssertionError("Expected failed operation");
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.CancellationException expected) {
        }
    }

    private static void terminal(BuildComparisons.Session session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (session.progress().state() == ComparisonTypes.RunState.RUNNING) {
            if (System.nanoTime() > deadline) throw new AssertionError("Comparison did not drain");
            Thread.sleep(10);
        }
    }

    private static final class Input implements ComparisonInput {
        final String value;
        String scope = "authored-scope";
        final AtomicInteger closed = new AtomicInteger(), active = new AtomicInteger();
        final CountDownLatch entered;
        final boolean closeFails;

        Input(String value, CountDownLatch entered, boolean closeFails) {
            this.value = value;
            this.entered = entered;
            this.closeFails = closeFails;
        }

        public ComparisonTypes.Descriptor descriptor() {
            return new ComparisonTypes.Descriptor(
                    value, "wowf", "enUS", "authored", scope, Map.of());
        }

        public CompletableFuture<ComparisonTypes.InputPage> read(
                String cursor, int limit, Cancellation token) {
            if (entered != null) {
                active.incrementAndGet();
                var pending = new CompletableFuture<ComparisonTypes.InputPage>();
                token.onCancel(
                        () -> {
                            active.decrementAndGet();
                            pending.completeExceptionally(
                                    new java.util.concurrent.CancellationException());
                        });
                entered.countDown();
                return pending;
            }
            token.check();
            var field =
                    new ComparisonTypes.Field(
                            "number-v1",
                            ComparisonTypes.ValueKind.INTEGER,
                            ComparisonTypes.ValueState.VALUE,
                            value,
                            ComparisonTypes.Aspect.CONTENT,
                            List.of());
            return CompletableFuture.completedFuture(
                    new ComparisonTypes.InputPage(
                            value,
                            List.of(
                                    new ComparisonTypes.Record(
                                            "1", Map.of("value", field), List.of())),
                            null,
                            new ComparisonTypes.Coverage(
                                    ComparisonTypes.Completeness.COMPLETE, scope, List.of())));
        }

        public CompletableFuture<Void> closeAsync() {
            require(active.get() == 0, "Pending input reads drain before adapter close");
            require(closed.incrementAndGet() == 1, "An admitted adapter closes exactly once");
            return closeFails
                    ? CompletableFuture.failedFuture(
                            new IllegalStateException("Authored close failure"))
                    : CompletableFuture.completedFuture(null);
        }
    }

    private static ComparisonCoordinator.Opened opened(Input input, AtomicInteger contexts) {
        return new ComparisonCoordinator.Opened(
                input,
                () -> {
                    require(
                            input.closed.get() == 1,
                            "Context remains owned until its adapter drains/closes");
                    contexts.incrementAndGet();
                });
    }

    private static void normalPair(Path directory) throws Exception {
        var inputs = new ArrayList<Input>();
        var contexts = new AtomicInteger();
        var captures = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) -> {
                            captures.incrementAndGet();
                            return (build, locale, area, cancel) -> {
                                var input =
                                        new Input(
                                                build.config().equals("a") ? "1" : "2",
                                                null,
                                                false);
                                inputs.add(input);
                                return opened(input, contexts);
                            };
                        },
                        directory);
        try {
            var session = manager.start(request(), new Cancellation()).get(10, TimeUnit.SECONDS);
            terminal(session);
            require(
                    session.page(0, 100).get(10, TimeUnit.SECONDS).rows().size() == 1,
                    "Changed pair returns a finding");
            failure(manager.start(request(), new Cancellation()));
            manager.releaseCurrent().get(10, TimeUnit.SECONDS);
            session.closeAsync().get(10, TimeUnit.SECONDS);
            require(contexts.get() == 2, "Only the two owned contexts close once");
            var next = manager.start(request(), new Cancellation()).get(10, TimeUnit.SECONDS);
            terminal(next);
            manager.closeAsync().get(10, TimeUnit.SECONDS);
            manager.closeAsync().get(10, TimeUnit.SECONDS);
            require(
                    contexts.get() == 4 && captures.get() == 2,
                    "A drained comparison allows another pair and repeated shutdown is safe");
        } finally {
            manager.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }

    private static void cancelOpening(Path directory) throws Exception {
        var entered = new CountDownLatch(1);
        var contexts = new AtomicInteger();
        var first = new Input("1", null, false);
        var calls = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) ->
                                (build, locale, area, cancel) -> {
                                    if (calls.incrementAndGet() == 1)
                                        return opened(first, contexts);
                                    var pending =
                                            new CompletableFuture<ComparisonCoordinator.Opened>();
                                    cancel.onCancel(
                                            () ->
                                                    pending.completeExceptionally(
                                                            new java.util.concurrent
                                                                    .CancellationException()));
                                    entered.countDown();
                                    return pending.join();
                                },
                        directory);
        var pending = manager.start(request(), new Cancellation());
        require(entered.await(10, TimeUnit.SECONDS), "Second opening started");
        manager.closeAsync().get(10, TimeUnit.SECONDS);
        failure(pending);
        require(
                contexts.get() == 1 && first.closed.get() == 1,
                "Cancel during opening closes the accepted first endpoint");
    }

    private static void cancelReads(Path directory) throws Exception {
        var reads = new CountDownLatch(2);
        var contexts = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) ->
                                (build, locale, area, cancel) ->
                                        opened(
                                                new Input(
                                                        build.config().equals("a") ? "1" : "2",
                                                        reads,
                                                        false),
                                                contexts),
                        directory);
        var session = manager.start(request(), new Cancellation()).get(10, TimeUnit.SECONDS);
        require(reads.await(10, TimeUnit.SECONDS), "Both source reads accepted");
        manager.closeAsync().get(10, TimeUnit.SECONDS);
        require(contexts.get() == 2, "Shutdown drains both pending reads before closing contexts");
        session.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static void failedSecondOpen(Path directory) throws Exception {
        var contexts = new AtomicInteger();
        var first = new Input("1", null, false);
        var calls = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) ->
                                (build, locale, area, cancel) -> {
                                    if (calls.incrementAndGet() == 2)
                                        throw new IllegalStateException(
                                                "Unavailable local metadata");
                                    return opened(first, contexts);
                                },
                        directory);
        failure(manager.start(request(), new Cancellation()));
        manager.closeAsync().get(10, TimeUnit.SECONDS);
        require(
                contexts.get() == 1 && first.closed.get() == 1,
                "Unavailable second endpoint releases the first without starting a run");
    }

    private static void rejectedScope(Path directory) throws Exception {
        var captures = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) -> {
                            captures.incrementAndGet();
                            throw new AssertionError("Should not open");
                        },
                        directory);
        var request = new BuildComparisons.Request(build("a"), "enUS", build("b"), "deDE", "Files");
        failure(manager.start(request, new Cancellation()));
        manager.closeAsync().get(10, TimeUnit.SECONDS);
        require(captures.get() == 0, "Incompatible locale rejects before acquiring contexts");
    }

    private static void preparedScopeAdmission(Path directory, boolean incompatible)
            throws Exception {
        var inputs = new ArrayList<Input>();
        var contexts = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) ->
                                new ComparisonCoordinator.EndpointOpener() {
                                    public ComparisonCoordinator.Opened open(
                                            BuildInfo build,
                                            String locale,
                                            String area,
                                            Cancellation cancel) {
                                        var input = new Input(build.config(), null, false);
                                        inputs.add(input);
                                        return opened(input, contexts);
                                    }

                                    public void prepare(
                                            ComparisonCoordinator.Opened before,
                                            ComparisonCoordinator.Opened after,
                                            Cancellation cancel) {
                                        inputs.get(0).scope = "final-paired-fields";
                                        inputs.get(1).scope =
                                                incompatible
                                                        ? "different-fields"
                                                        : "final-paired-fields";
                                    }
                                },
                        directory);
        try {
            var request =
                    new BuildComparisons.Request(build("a"), "enUS", build("b"), "enUS", "Items");
            if (incompatible) {
                failure(manager.start(request, new Cancellation()));
            } else {
                var session = manager.start(request, new Cancellation()).get(10, TimeUnit.SECONDS);
                terminal(session);
                require(
                        session.before().scope().equals("final-paired-fields"),
                        "Session must expose final paired scope after preparation");
                session.closeAsync().get(10, TimeUnit.SECONDS);
            }
        } finally {
            manager.closeAsync().get(10, TimeUnit.SECONDS);
        }
        require(contexts.get() == 2, "Prepared scope admission closes both contexts");
    }

    private static void rejectedCoreAdmission(Path directory) throws Exception {
        var contexts = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) ->
                                (build, locale, area, cancel) ->
                                        opened(
                                                new Input(
                                                        build.config().equals("a") ? "1" : "2",
                                                        null,
                                                        false),
                                                contexts),
                        directory,
                        (parent, budget, plan, metadata) -> {
                            var service =
                                    new com.vibeminer.concepts.comparison.ComparisonService(parent);
                            service.closeAsync().join();
                            return service;
                        });
        failure(manager.start(request(), new Cancellation()));
        manager.closeAsync().get(10, TimeUnit.SECONDS);
        require(
                contexts.get() == 2,
                "Rejected core admission leaves adapters/context cleanup with the coordinator");
    }

    private static void failedAdapterClose(Path directory) throws Exception {
        var contexts = new AtomicInteger();
        var manager =
                new ComparisonCoordinator(
                        (request, token) ->
                                (build, locale, area, cancel) ->
                                        opened(
                                                new Input(
                                                        build.config().equals("a") ? "1" : "2",
                                                        null,
                                                        true),
                                                contexts),
                        directory);
        var session = manager.start(request(), new Cancellation()).get(10, TimeUnit.SECONDS);
        terminal(session);
        failure(manager.closeAsync());
        require(
                contexts.get() == 2,
                "All contexts close even when adapters report cleanup failures");
    }

    private static void rawPairAdmission(Path directory) throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int scenario = mode;
            var opens = new AtomicInteger();
            var reads = new AtomicInteger();
            var prepares = new AtomicInteger();
            var inputsClosed = new AtomicInteger();
            var contextsClosed = new AtomicInteger();
            var plansClosed = new AtomicInteger();
            var preparing = new CountDownLatch(1);
            var budget = new com.vibeminer.concepts.comparison.TempBudget();
            var manager =
                    new ComparisonCoordinator(
                            (request, token) ->
                                    new ComparisonCoordinator.EndpointOpener() {
                                        public ComparisonCoordinator.Opened open(
                                                BuildInfo build,
                                                String locale,
                                                String area,
                                                Cancellation cancel) {
                                            int side = opens.incrementAndGet();
                                            var descriptor =
                                                    new ComparisonTypes.Descriptor(
                                                            "raw-" + side,
                                                            "wowf",
                                                            "enUS",
                                                            "raw-table:100",
                                                            scenario == 0 && side == 2
                                                                    ? "different-schema"
                                                                    : "same-schema",
                                                            Map.of());
                                            ComparisonInput input =
                                                    new ComparisonInput() {
                                                        public ComparisonTypes.Descriptor
                                                                descriptor() {
                                                            return descriptor;
                                                        }

                                                        public CompletableFuture<
                                                                        ComparisonTypes.InputPage>
                                                                read(
                                                                        String cursor,
                                                                        int limit,
                                                                        Cancellation call) {
                                                            reads.incrementAndGet();
                                                            return CompletableFuture
                                                                    .completedFuture(
                                                                            new ComparisonTypes
                                                                                    .InputPage(
                                                                                    descriptor
                                                                                            .identity(),
                                                                                    List.of(),
                                                                                    null,
                                                                                    new ComparisonTypes
                                                                                            .Coverage(
                                                                                            ComparisonTypes
                                                                                                    .Completeness
                                                                                                    .UNKNOWN,
                                                                                            descriptor
                                                                                                    .scope(),
                                                                                            List
                                                                                                    .of())));
                                                        }

                                                        public CompletableFuture<Void>
                                                                closeAsync() {
                                                            inputsClosed.incrementAndGet();
                                                            return CompletableFuture
                                                                    .completedFuture(null);
                                                        }
                                                    };
                                            return new ComparisonCoordinator.Opened(
                                                    input, contextsClosed::incrementAndGet);
                                        }

                                        public com.vibeminer.concepts.comparison.TempBudget
                                                budget() {
                                            return budget;
                                        }

                                        public void prepare(
                                                ComparisonCoordinator.Opened before,
                                                ComparisonCoordinator.Opened after,
                                                Cancellation call) {
                                            require(
                                                    opens.get() == 2,
                                                    "Both descriptors are captured before raw"
                                                            + " sorting");
                                            require(
                                                    before.input()
                                                            .descriptor()
                                                            .scope()
                                                            .equals(
                                                                    after.input()
                                                                            .descriptor()
                                                                            .scope()),
                                                    "Raw preparation only receives compatible"
                                                            + " schemas");
                                            prepares.incrementAndGet();
                                            preparing.countDown();
                                            if (scenario == 2) {
                                                var held = new CompletableFuture<Void>();
                                                var link =
                                                        call.onCancel(
                                                                () ->
                                                                        held.completeExceptionally(
                                                                                new java.util
                                                                                        .concurrent
                                                                                        .CancellationException()));
                                                try {
                                                    held.join();
                                                } finally {
                                                    link.close();
                                                }
                                            }
                                        }

                                        public void close() {
                                            require(
                                                    inputsClosed.get() == 2
                                                            && contextsClosed.get() == 2,
                                                    "Plan closes after accepted inputs and original"
                                                            + " contexts drain");
                                            budget.close();
                                            plansClosed.incrementAndGet();
                                        }
                                    },
                            directory);
            var opening =
                    manager.start(
                            new BuildComparisons.Request(
                                    build("a"), "enUS", build("b"), "enUS", "Tables", 100L),
                            new Cancellation());
            if (scenario == 1) {
                var session = opening.get(10, TimeUnit.SECONDS);
                terminal(session);
                require(
                        session.progress().state() == ComparisonTypes.RunState.COMPLETED,
                        "Shared-budget raw pair completes");
                session.closeAsync().get(10, TimeUnit.SECONDS);
            } else if (scenario == 0) {
                failure(opening);
                require(
                        prepares.get() == 0 && reads.get() == 0,
                        "Mismatched schemas fail before any sorting or input reads");
            } else {
                require(preparing.await(10, TimeUnit.SECONDS), "Raw preparation is held");
                manager.closeAsync().get(10, TimeUnit.SECONDS);
                failure(opening);
                require(reads.get() == 0, "Canceling preparation does not admit core reads");
            }
            manager.closeAsync().get(10, TimeUnit.SECONDS);
            require(
                    plansClosed.get() == 1 && budget.snapshot().reservations() == 0,
                    "Every pair outcome closes its plan and shared resource budget once");
        }
    }

    private static void staleCleanup(Path directory) throws Exception {
        var parent = directory.resolve("stale");
        Files.createDirectories(parent);
        var own = Files.createDirectory(parent.resolve("comparison-own"));
        Files.writeString(own.resolve("results.tmp"), "authored");
        var foreign = Files.createDirectory(parent.resolve("comparison-unknown"));
        Files.writeString(foreign.resolve("user-note.txt"), "retain");
        var unrelated = Files.createDirectory(parent.resolve("other"));
        Files.writeString(unrelated.resolve("results.tmp"), "retain");
        var sorted = Files.createDirectory(parent.resolve("comparison-sort-123"));
        Files.writeString(sorted.resolve("run-456.tmp"), "authored framed input");
        var generated = Files.createDirectory(parent.resolve("comparison-789"));
        Files.writeString(generated.resolve("results123.tmp"), "authored framed results");
        var protectedSort = Files.createDirectory(parent.resolve("comparison-sort-foreign"));
        Files.writeString(protectedSort.resolve("notes.txt"), "retain foreign file");
        var items = Files.createDirectory(parent.resolve("comparison-items-123"));
        Files.writeString(items.resolve("candidates-789.tmp"), "authored candidate");
        var protectedItems = Files.createDirectory(parent.resolve("comparison-items-foreign"));
        Files.writeString(protectedItems.resolve("notes.txt"), "retain foreign file");
        ComparisonTemporaryFiles.cleanStale(parent);
        require(
                !Files.exists(items) && Files.exists(protectedItems.resolve("notes.txt")),
                "Recognized Items candidates are reclaimed without deleting foreign files");
        require(
                !Files.exists(sorted)
                        && !Files.exists(generated)
                        && Files.exists(protectedSort.resolve("notes.txt")),
                "Recognized sort/result scratch is reclaimed while foreign files remain");
        require(
                !Files.exists(own)
                        && Files.exists(foreign.resolve("user-note.txt"))
                        && Files.exists(unrelated.resolve("results.tmp")),
                "Startup cleanup removes only recognizable owned staging");
    }

    private static void sourceHint() {
        var a =
                new BuildInfo(
                        "wowf",
                        "Forever",
                        "1.60.1.1",
                        "us,eu",
                        "a",
                        BuildInfo.BuildSource.CURRENT,
                        true,
                        null,
                        0,
                        "id",
                        null,
                        null,
                        List.of(
                                new BuildInfo.RegionEvidence(
                                        "eu", "e".repeat(32), true, null, null),
                                new BuildInfo.RegionEvidence(
                                        "us", "b".repeat(32), true, null, null)));
        var receipt =
                new PreparationHistory.Receipt(
                        "id", "wowf", "a", "c".repeat(32), "eu", "enUS", "TABLES_READY");
        require(
                LocalComparisonFactory.evidence(a, "enUS", List.of(receipt))
                        .cdnConfig()
                        .equals("c".repeat(32)),
                "Prepared source context is reused without granting cache validity");
        require(
                LocalComparisonFactory.evidence(a, "deDE", List.of(receipt)).region().equals("us"),
                "A different locale does not inherit the historical context claim");
    }
}
