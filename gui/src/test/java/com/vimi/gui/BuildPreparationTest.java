package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.network.CdnKey;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Headless deterministic preparation state/lifecycle contracts; no native libraries or endpoints.
 */
public final class BuildPreparationTest {
    private BuildPreparationTest() {}

    private static final Thread CALLER = Thread.currentThread();
    static final BuildInfo.RegionEvidence SOURCE =
            new BuildInfo.RegionEvidence("us", "b".repeat(32), true, Instant.EPOCH, Instant.EPOCH);

    static BuildInfo build(int id) {
        return new BuildInfo(
                "wow",
                "Retail",
                "Version",
                "us",
                "a".repeat(32),
                BuildInfo.BuildSource.CURRENT,
                false,
                null,
                0,
                "identity" + id,
                Instant.EPOCH,
                Instant.EPOCH,
                List.of(SOURCE));
    }

    static BuildTypes.BuildMetadata metadata() {
        return new BuildTypes.BuildMetadata(
                "Actual",
                CdnKey.parse("1".repeat(32)),
                CdnKey.parse("2".repeat(32)),
                CdnKey.parse("3".repeat(32)),
                2,
                3,
                List.of("enUS"));
    }

    static void require(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    static void waitFor(CountDownLatch latch) throws Exception {
        require(latch.await(5, TimeUnit.SECONDS), "Expected worker checkpoint");
    }

    static void argument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected argument rejection");
        } catch (IllegalArgumentException expected) {
        }
    }

    static void state(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected state rejection");
        } catch (IllegalStateException expected) {
        }
    }

    static final class Fake implements BuildPreparation.Work {
        final Thread caller = CALLER;
        final java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger metadataCalls = new AtomicInteger(),
                fileCalls = new AtomicInteger(),
                tableCalls = new AtomicInteger(),
                closes = new AtomicInteger(),
                namesCalls = new AtomicInteger(),
                commits = new AtomicInteger(),
                aborts = new AtomicInteger();
        final CountDownLatch metadataEntered = new CountDownLatch(1),
                filesEntered = new CountDownLatch(1),
                namesEntered = new CountDownLatch(1),
                tablesEntered = new CountDownLatch(1);
        volatile CompletableFuture<Void> metadataGate = CompletableFuture.completedFuture(null),
                filesGate = CompletableFuture.completedFuture(null),
                namesGate = CompletableFuture.completedFuture(null),
                tablesGate = CompletableFuture.completedFuture(null);
        volatile boolean cancelKeys, failWarm, failFiles, failNames, cancelWarm, failRetirement;
        volatile String tableName = "Unavailable";
        volatile Consumer<BuildPreparation.Counter> oldCount;

        void worker(String name) {
            require(Thread.currentThread() != caller, "No " + name + " work on caller/FX thread");
            order.add(name);
        }

        public BuildTypes.BuildMetadata metadata(Cancellation token) {
            worker("metadata");
            metadataCalls.incrementAndGet();
            metadataEntered.countDown();
            metadataGate.join();
            token.check();
            return BuildPreparationTest.metadata();
        }

        public BuildPreparation.FilesSummary files(
                Consumer<BuildPreparation.Counter> counted, Cancellation token) {
            worker("files");
            fileCalls.incrementAndGet();
            oldCount = counted;
            filesEntered.countDown();
            filesGate.join();
            token.check();
            counted.accept(new BuildPreparation.Counter(1, 3L, "files"));
            if (failFiles) throw new IllegalStateException("Files failed");
            counted.accept(new BuildPreparation.Counter(3, 3L, "files"));
            return new BuildPreparation.FilesSummary(3, 1, 2);
        }

        public List<String> keys(Cancellation token) {
            worker("keys");
            if (cancelKeys) token.cancel();
            return List.of("Missing optional keys");
        }

        public TableTypes.PreloadResult tables(
                Consumer<TableTypes.PreloadProgress> counted, Cancellation token) {
            worker("tables");
            tableCalls.incrementAndGet();
            tablesEntered.countDown();
            tablesGate.join();
            token.check();
            var progress =
                    new TableTypes.PreloadProgress(
                            TableTypes.PreloadStage.PREPARING, 1, 1, 0, 0, 0, 0, 1, 1, 1);
            counted.accept(progress);
            return new TableTypes.PreloadResult(
                    TableTypes.PreloadOutcome.COMPLETED,
                    progress,
                    List.of(
                            new TableTypes.Entry(
                                    1,
                                    tableName,
                                    TableTypes.State.NEEDS_NETWORK,
                                    true,
                                    null,
                                    null,
                                    null)),
                    null,
                    null,
                    null);
        }

        public List<String> warm(Cancellation token) {
            worker("warm");
            if (failWarm) throw new IllegalStateException("Warm failed");
            if (cancelWarm) token.cancel();
            return List.of("Expected unavailable lookup");
        }

        public void enrichNames(Cancellation token) {
            worker("names");
            namesCalls.incrementAndGet();
            namesEntered.countDown();
            namesGate.join();
            if (failNames) throw new IllegalStateException("Names failed");
            token.check();
        }

        public void commit(Cancellation token) {
            token.check();
            commits.incrementAndGet();
        }

        public void retireCommitted() {
            worker("retire");
            if (failRetirement) throw new IllegalStateException("Retirement failed");
        }

        public void abortPending() {
            worker("abort");
            aborts.incrementAndGet();
        }

        public void close() {
            worker("close");
            closes.incrementAndGet();
        }
    }

    public static void main(String[] args) throws Exception {
        phaseOrderAndIndependentMetadataEvent();
        canceledBeforeTablesPreservesFiles();
        expectedUnavailableCompletesTablesReady();
        fatalRetryReusesWorkAndHighestReadiness();
        namesDoNotDelayReadyOrAnotherBuild();
        staleCallbacksDoNotMutateRetry();
        closeAndReleaseObserversCannotForgeDrain();
        contextBudgetRequiresExplicitRelease();
        throwingFactoryAndRejectedStartAreRetired();
        filesFailureRetainsMetadataAndNamesFailureIsIndependent();
        forceEnrichmentCreatesGenerationAndRetainsPreviousReadiness();
        canceledEnrichmentAbortsPendingAndKeepsPublishedLedger();
        retirementFailureDoesNotInvalidateCommittedWorkspace();
        System.out.println("BuildPreparation: 13 deterministic contracts passed");
    }

    static void phaseOrderAndIndependentMetadataEvent() throws Exception {
        var work = new Fake();
        work.metadataGate = new CompletableFuture<>();
        work.filesGate = new CompletableFuture<>();
        var engine = new BuildPreparation((b, s, l) -> work);
        var job = engine.start(build(1), SOURCE, "enUS");
        waitFor(work.metadataEntered);
        var callback = job.metadataReady().thenApply(ignored -> job.completion().join());
        work.metadataGate.complete(null);
        await(job.metadataReady());
        waitFor(work.filesEntered);
        require(
                job.snapshot().ready() == BuildPreparation.Ready.METADATA_READY,
                "Metadata published before Files");
        require(!job.completion().isDone(), "Metadata event is independent of full completion");
        work.filesGate.complete(null);
        await(callback);
        require(
                work.order.indexOf("metadata") < work.order.indexOf("files")
                        && work.order.indexOf("files") < work.order.indexOf("keys")
                        && work.order.indexOf("keys") < work.order.indexOf("tables"),
                "Strict pipeline ordering");
        await(engine.closeAsync());
    }

    static void canceledBeforeTablesPreservesFiles() throws Exception {
        var work = new Fake();
        work.cancelKeys = true;
        var engine = new BuildPreparation((b, s, l) -> work);
        var job = engine.start(build(1), SOURCE, "enUS");
        var result = await(job.completion());
        require(
                result.activity() == BuildPreparation.Activity.CANCELED
                        && result.ready() == BuildPreparation.Ready.FILES_READY,
                "Cancellation retains Files readiness");
        require(work.tableCalls.get() == 0, "Cancel prevents Tables start");
        await(engine.closeAsync());
    }

    static void expectedUnavailableCompletesTablesReady() throws Exception {
        var work = new Fake();
        var engine = new BuildPreparation((b, s, l) -> work);
        var job = engine.start(build(1), SOURCE, "enUS");
        var result = await(job.completion());
        require(
                result.ready() == BuildPreparation.Ready.TABLES_READY
                        && result.activity() == BuildPreparation.Activity.IDLE,
                "Completed preload outcomes include expected unavailable tables");
        require(
                result.tables().getFirst().state() == TableTypes.State.NEEDS_NETWORK
                        && result.issues().size() == 2,
                "Outcomes and warnings retained");
        require(engine.start(build(1), SOURCE, "enUS") == job, "Completed work is reused");
        await(engine.closeAsync());
    }

    static void fatalRetryReusesWorkAndHighestReadiness() throws Exception {
        var work = new Fake();
        work.failWarm = true;
        var factories = new AtomicInteger();
        var engine =
                new BuildPreparation(
                        (b, s, l) -> {
                            factories.incrementAndGet();
                            return work;
                        });
        var first = engine.start(build(1), SOURCE, "enUS");
        var failed = await(first.completion());
        require(
                failed.activity() == BuildPreparation.Activity.FAILED
                        && failed.ready() == BuildPreparation.Ready.FILES_READY
                        && failed.tables().isEmpty(),
                "Fatal warm failure keeps usable Files but does not publish pending table outcomes");
        work.failWarm = false;
        var retry = engine.start(build(1), SOURCE, "enUS");
        require(
                retry != first && retry.snapshot().generation() > failed.generation(),
                "Retry is new generation");
        require(
                await(retry.completion()).ready() == BuildPreparation.Ready.TABLES_READY,
                "Retry succeeds");
        require(
                factories.get() == 1 && work.metadataCalls.get() == 1 && work.fileCalls.get() == 1,
                "Same Work and valid milestone data reused");
        await(engine.closeAsync());
        require(work.closes.get() == 1, "One context closes once across generations");
    }

    static void namesDoNotDelayReadyOrAnotherBuild() throws Exception {
        var firstWork = new Fake();
        firstWork.namesGate = new CompletableFuture<>();
        var otherWork = new Fake();
        var engine =
                new BuildPreparation(
                        (b, s, l) -> b.identity().equals("identity1") ? firstWork : otherWork);
        var first = engine.start(build(1), SOURCE, "enUS");
        waitFor(firstWork.namesEntered);
        require(
                await(first.completion()).ready() == BuildPreparation.Ready.TABLES_READY
                        && first.enrichingNames(),
                "Names separate from readiness");
        var second = engine.start(build(2), SOURCE, "enUS");
        require(
                await(second.completion()).ready() == BuildPreparation.Ready.TABLES_READY,
                "Another initial pipeline is not held by names");
        var closing = engine.closeAsync();
        require(
                !closing.isDone() && firstWork.closes.get() == 0,
                "Resources wait for names to retire");
        firstWork.namesGate.complete(null);
        await(closing);
        require(
                firstWork.closes.get() == 1 && otherWork.closes.get() == 1,
                "All retained contexts close");
    }

    static void staleCallbacksDoNotMutateRetry() throws Exception {
        var work = new Fake();
        work.failWarm = true;
        var engine = new BuildPreparation((b, s, l) -> work);
        var first = engine.start(build(1), SOURCE, "enUS");
        await(first.completion());
        var old = work.oldCount;
        work.failWarm = false;
        work.tablesGate = new CompletableFuture<>();
        var retry = engine.start(build(1), SOURCE, "enUS");
        var before = retry.snapshot();
        old.accept(new BuildPreparation.Counter(99, null, "stale files"));
        require(
                !retry.snapshot().unit().equals("stale files")
                        && first.snapshot().activity() == BuildPreparation.Activity.FAILED,
                "Old-generation progress cannot change new state");
        work.tablesGate.complete(null);
        await(retry.completion());
        await(engine.closeAsync());
    }

    static void closeAndReleaseObserversCannotForgeDrain() throws Exception {
        var work = new Fake();
        work.namesGate = new CompletableFuture<>();
        var engine = new BuildPreparation((b, s, l) -> work);
        var job = engine.start(build(1), SOURCE, "enUS");
        await(job.completion());
        waitFor(work.namesEntered);
        var release = engine.release(job);
        release.complete(null);
        var close = engine.closeAsync();
        require(!close.isDone(), "Engine drain includes released-but-closing contexts");
        close.cancel(false);
        var actual = engine.closeAsync();
        require(!actual.isDone(), "Close observer cannot forge drain");
        work.namesGate.complete(null);
        await(actual);
        await(engine.release(job));
        require(work.closes.get() == 1, "Repeated release closes once");
    }

    static void contextBudgetRequiresExplicitRelease() throws Exception {
        var engine = new BuildPreparation((b, s, l) -> new Fake());
        var jobs = new java.util.ArrayList<BuildPreparation.Job>();
        for (int i = 0; i < 4; i++) {
            var job = engine.start(build(i), SOURCE, "enUS");
            await(job.completion());
            jobs.add(job);
        }
        state(() -> engine.start(build(5), SOURCE, "enUS"));
        await(engine.release(jobs.getFirst()));
        require(
                await(engine.start(build(5), SOURCE, "enUS").completion()).ready()
                        == BuildPreparation.Ready.TABLES_READY,
                "Explicit release restores bounded capacity");
        await(engine.closeAsync());
    }

    static void throwingFactoryAndRejectedStartAreRetired() throws Exception {
        var engine =
                new BuildPreparation(
                        (b, s, l) -> {
                            throw new IllegalStateException("Factory failed");
                        });
        var failed = engine.start(build(1), SOURCE, "enUS");
        require(
                await(failed.completion()).activity() == BuildPreparation.Activity.FAILED,
                "Throwing factory produces terminal state");
        require(
                failed.metadataReady().isCompletedExceptionally(),
                "Metadata event fails explicitly");
        await(engine.closeAsync());
        var rejected =
                new BuildPreparation(
                        (b, s, l) -> {
                            throw new AssertionError("No factory on rejected start");
                        },
                        command -> {
                            throw new java.util.concurrent.RejectedExecutionException("Full");
                        });
        var job = rejected.start(build(1), SOURCE, "enUS");
        require(
                await(job.completion()).activity() == BuildPreparation.Activity.FAILED,
                "Rejected start retires admission");
        await(rejected.closeAsync());
        argument(
                () ->
                        new BuildPreparation((b, s, l) -> new Fake())
                                .start(build(1), SOURCE, "wrong"));
    }

    static void filesFailureRetainsMetadataAndNamesFailureIsIndependent() throws Exception {
        var work = new Fake();
        work.failFiles = true;
        var engine = new BuildPreparation((b, s, l) -> work);
        var first = engine.start(build(1), SOURCE, "enUS");
        require(
                await(first.completion()).ready() == BuildPreparation.Ready.METADATA_READY,
                "Files failure keeps authenticated metadata");
        await(first.metadataReady());
        work.failFiles = false;
        work.failNames = true;
        var retry = engine.start(build(1), SOURCE, "enUS");
        require(
                await(retry.completion()).ready() == BuildPreparation.Ready.TABLES_READY,
                "Name failure cannot fail initial preparation");
        waitFor(work.namesEntered);
        await(engine.closeAsync());
        require(
                retry.namesProblem() != null && retry.namesProblem().contains("Names failed"),
                "Independent name diagnostic retained");
        require(
                work.metadataCalls.get() == 1 && work.fileCalls.get() == 2,
                "Retry reuses metadata, retries only invalid Files milestone");
    }

    static void forceEnrichmentCreatesGenerationAndRetainsPreviousReadiness() throws Exception {
        var work = new Fake();
        var engine = new BuildPreparation((b, s, l) -> work);
        var first = engine.start(build(1), SOURCE, "enUS");
        await(first.completion());
        // Independent names must retire before checking a fresh enrichment's name refresh.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (first.enrichingNames() && System.nanoTime() < deadline)
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        require(!first.enrichingNames(), "Initial names retired");
        require(
                engine.start(build(1), SOURCE, "enUS") == first,
                "Ordinary start reuses completed context");
        work.failWarm = true;
        work.tableName = "Pending must not publish";
        var enriched = engine.enrich(build(1), SOURCE, "enUS");
        var outcome = await(enriched.completion());
        require(
                enriched != first && outcome.generation() > first.snapshot().generation(),
                "Explicit enrich creates generation");
        require(
                outcome.activity() == BuildPreparation.Activity.FAILED
                        && outcome.ready() == BuildPreparation.Ready.TABLES_READY
                        && outcome.tables().getFirst().name().equals("Unavailable"),
                "Failed enrichment retains highest previous readiness and exact previous table outcomes");
        require(
                work.metadataCalls.get() == 1
                        && work.fileCalls.get() == 1
                        && work.tableCalls.get() == 2,
                "Enrich reuses core metadata/files and refreshes preparation");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (work.namesCalls.get() < 2 && System.nanoTime() < deadline)
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        require(
                work.namesCalls.get() == 2,
                "Enrich starts separate name refresh when no prior refresh is running");
        await(engine.closeAsync());
    }

    static void canceledEnrichmentAbortsPendingAndKeepsPublishedLedger() throws Exception {
        var work = new Fake();
        var engine = new BuildPreparation((b, s, l) -> work);
        var first = engine.start(build(1), SOURCE, "enUS");
        var original = await(first.completion());
        work.tableName = "Pending replacement";
        work.cancelWarm = true;
        var replacement = engine.enrich(build(1), SOURCE, "enUS");
        var canceled = await(replacement.completion());
        require(
                canceled.activity() == BuildPreparation.Activity.CANCELED
                        && canceled.ready() == BuildPreparation.Ready.TABLES_READY,
                "Canceled enrichment keeps highest valid readiness");
        require(
                canceled.tables().equals(original.tables()),
                "Pending ledger must never replace previously committed outcomes");
        require(
                work.commits.get() == 1 && work.aborts.get() == 1,
                "Only initial generation commits; canceled generation aborts");
        await(engine.closeAsync());
    }

    static void retirementFailureDoesNotInvalidateCommittedWorkspace() throws Exception {
        var work = new Fake();
        work.failRetirement = true;
        var engine = new BuildPreparation((b, s, l) -> work);
        var job = engine.start(build(1), SOURCE, "enUS");
        var result = await(job.completion());
        require(
                result.ready() == BuildPreparation.Ready.TABLES_READY
                        && result.activity() == BuildPreparation.Activity.IDLE,
                "Post-publication retirement failure cannot invalidate committed readiness");
        require(
                result.issues().stream().anyMatch(issue -> issue.contains("Retirement failed")),
                "Retirement warning remains visible");
        require(
                work.commits.get() == 1 && work.aborts.get() == 0,
                "Committed workspace is not aborted");
        await(engine.closeAsync());
    }
}
