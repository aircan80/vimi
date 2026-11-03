package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Aspect;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.Field;
import com.vibeminer.concepts.comparison.ComparisonTypes.InputPage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueKind;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SortedComparisonInputTest {
    @TempDir Path temporary;

    private static final class Fixture implements SortedComparisonInput.Source {
        final List<Record> rows;
        final AtomicInteger closes = new AtomicInteger();
        final CompletableFuture<Void> closed;
        String identity = "captured";
        volatile Thread readerThread;

        Fixture(List<Record> rows) {
            this(rows, CompletableFuture.completedFuture(null));
        }

        Fixture(List<Record> rows, CompletableFuture<Void> closed) {
            this.rows = rows;
            this.closed = closed;
        }

        public Descriptor descriptor() {
            return new Descriptor(
                    "captured",
                    "wowf",
                    "enUS",
                    "table:fixture",
                    "held readable rows",
                    Map.of("profile", "authored"));
        }

        public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                String cursor, int maxRows, long maxBytes, Cancellation token) {
            readerThread = Thread.currentThread();
            token.check();
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            var page = new ArrayList<Record>();
            long bytes = 4096;
            int i = start;
            while (i < rows.size() && page.size() < maxRows) {
                var row = rows.get(i);
                long next = ComparisonCodec.retainedBytes(row) + 64;
                if (bytes + next > maxBytes) {
                    break;
                }
                bytes += next;
                page.add(row);
                i++;
            }
            return CompletableFuture.completedFuture(
                    new SortedComparisonInput.UnsortedPage(
                            identity,
                            page,
                            i == rows.size() ? null : Integer.toString(i),
                            new Coverage(
                                    Completeness.PARTIAL,
                                    "held readable rows",
                                    List.of("Protected sections remain unknown"))));
        }

        public CompletableFuture<Void> closeAsync() {
            closes.incrementAndGet();
            return closed;
        }
    }

    @Test
    void sortsUnsignedIdentitiesAndRetainsPartialCoverageWithoutOwningBudget() throws Exception {
        var source =
                new Fixture(
                        List.of(
                                row(4294967295L, "copy"),
                                row(0, "physical"),
                                row(16952, "copy"),
                                row(1, "physical")));
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(source, temporary, budget, Cancellation.NONE)
                            .join();
            assertEquals(
                    1,
                    source.closes.get(),
                    "Original reader drains before sorted input is published");
            assertEquals(0, budget.snapshot().memory());
            var rows = readAll(input, 2);
            assertEquals(
                    List.of(key(0), key(1), key(16952), key(4294967295L)),
                    rows.stream().map(Record::key).toList());
            assertEquals("copy", rows.getLast().fields().get("kind").value());
            input.closeAsync().join();
            input.closeAsync().join();
            assertDrained(budget);
        }
    }

    @Test
    void externalMergeSortsSeveralChunksWithOnlyTwoReaders() throws Exception {
        var rows = new ArrayList<Record>();
        for (int i = 1999; i >= 0; i--) {
            rows.add(row(i, "x".repeat(3000)));
        }
        var source = new Fixture(rows);
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(source, temporary, budget, Cancellation.NONE)
                            .join();
            assertTrue(
                    budget.snapshot().peakDisk() > budget.snapshot().disk(),
                    "Old and replacement runs overlap in shared disk accounting");
            var output = readAll(input, 256);
            assertEquals(2000, output.size());
            for (int i = 0; i < 2000; i++) {
                assertEquals(key(i), output.get(i).key());
            }
            assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
            input.closeAsync().join();
            assertDrained(budget);
        }
    }

    @Test
    void rejectsDuplicateWithinChunkWithoutCollapsingRows() throws Exception {
        assertFailure(
                new Fixture(List.of(row(1, "a"), row(1, "b"))),
                SortedComparisonInput.Reason.INVALID_SOURCE);
    }

    @Test
    void rejectsDuplicateAcrossExternalRuns() throws Exception {
        var rows = new ArrayList<Record>();
        for (int i = 0; i < 2000; i++) {
            rows.add(row(i, "x".repeat(3000)));
        }
        rows.add(row(0, "different"));
        assertFailure(new Fixture(rows), SortedComparisonInput.Reason.INVALID_SOURCE);
    }

    @Test
    void staleIdentityNeverPublishesAnEmptyOrCompleteInput() throws Exception {
        var source = new Fixture(List.of(row(1, "a")));
        source.identity = "changed";
        assertFailure(source, SortedComparisonInput.Reason.STALE);
    }

    @Test
    void nonCanonicalAndOutOfRangeIdentitiesAreRejected() throws Exception {
        for (String value : List.of("1", "00000000004294967296", "0000000000000000000x")) {
            assertFailure(
                    new Fixture(List.of(new Record(value, Map.of(), List.of()))),
                    SortedComparisonInput.Reason.INVALID_SOURCE);
        }
    }

    @Test
    void sharedDiskQuotaIncludesOtherStageAndCleanupReleasesOnlyOwnedReservations()
            throws Exception {
        try (var budget = new TempBudget(ComparisonService.BUFFER_BYTES, 100);
                var other = budget.reserveDisk("other-stage", 90, Cancellation.NONE)) {
            var source = new Fixture(List.of(row(1, "value")));
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    SortedComparisonInput.capture(
                                                    source, temporary, budget, Cancellation.NONE)
                                            .join());
            assertEquals(
                    SortedComparisonInput.Reason.LIMITED,
                    ((SortedComparisonInput.CaptureFailure) failure.getCause()).reason());
            assertEquals(90, other.size());
            assertEquals(90, budget.snapshot().disk());
            assertEquals(0, budget.snapshot().memory());
            assertEquals(1, source.closes.get());
            assertNoScratch();
        }
    }

    @Test
    void memoryQuotaFailureIsTypedAndCleansCapturedSource() throws Exception {
        try (var budget = new TempBudget(4L << 20, 512L << 20)) {
            var source = new Fixture(List.of(row(1, "value")));
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    SortedComparisonInput.capture(
                                                    source, temporary, budget, Cancellation.NONE)
                                            .join());
            assertEquals(
                    SortedComparisonInput.Reason.LIMITED,
                    ((SortedComparisonInput.CaptureFailure) failure.getCause()).reason());
            assertEquals(1, source.closes.get());
            assertDrained(budget);
        }
    }

    @Test
    void laterCompletePageCannotUpgradeEarlierUnknownCoverageOrLoseItsIssue() throws Exception {
        var rows = new ArrayList<Record>();
        for (int i = 0; i < 257; i++) {
            rows.add(row(i, "a"));
        }
        var original = new Fixture(rows);
        SortedComparisonInput.Source source =
                new SortedComparisonInput.Source() {
                    public Descriptor descriptor() {
                        return original.descriptor();
                    }

                    public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                            String cursor, int maxRows, long maxBytes, Cancellation token) {
                        return original.read(cursor, maxRows, maxBytes, token)
                                .thenApply(
                                        page ->
                                                new SortedComparisonInput.UnsortedPage(
                                                        page.identity(),
                                                        page.rows(),
                                                        page.nextCursor(),
                                                        new Coverage(
                                                                cursor == null
                                                                        ? Completeness.UNKNOWN
                                                                        : Completeness.COMPLETE,
                                                                "held readable rows",
                                                                cursor == null
                                                                        ? List.of(
                                                                                "Earlier unknown section")
                                                                        : List.of())));
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return original.closeAsync();
                    }
                };
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(source, temporary, budget, Cancellation.NONE)
                            .join();
            var page = input.read(null, 256, Cancellation.NONE).join();
            assertEquals(Completeness.UNKNOWN, page.coverage().completeness());
            assertEquals(List.of("Earlier unknown section"), page.coverage().issues());
            input.closeAsync().join();
            assertDrained(budget);
        }
    }

    @Test
    void serializedRecordLimitIsTypedBeforeWritingAnOversizeFrame() throws Exception {
        assertFailure(
                new Fixture(List.of(row(1, "界".repeat(400000)))),
                SortedComparisonInput.Reason.LIMITED);
    }

    @Test
    void cancellationWaitsForSourceCloseBeforeCaptureFailureCompletes() throws Exception {
        var drained = new CompletableFuture<Void>();
        var source = new Fixture(List.of(row(1, "value")), drained);
        var token = new Cancellation();
        token.cancel();
        try (var budget = new TempBudget()) {
            var result = SortedComparisonInput.capture(source, temporary, budget, token);
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (source.closes.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, source.closes.get());
            assertFalse(result.isDone());
            drained.complete(null);
            var error = assertThrows(CompletionException.class, result::join);
            assertEquals(
                    SortedComparisonInput.Reason.CANCELED,
                    ((SortedComparisonInput.CaptureFailure) error.getCause()).reason());
            assertDrained(budget);
        }
    }

    @Test
    void emptyPartialSourceStaysPartialAndCloseRejectsFurtherReads() throws Exception {
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(
                                    new Fixture(List.of()), temporary, budget, Cancellation.NONE)
                            .join();
            var page = input.read(null, 256, Cancellation.NONE).join();
            assertTrue(page.rows().isEmpty());
            assertNull(page.nextCursor());
            assertEquals(Completeness.PARTIAL, page.coverage().completeness());
            input.closeAsync().join();
            assertThrows(
                    CompletionException.class, () -> input.read(null, 1, Cancellation.NONE).join());
            assertDrained(budget);
        }
    }

    @Test
    void closeDrainsAcceptedAndRejectedQueuedCommands() throws Exception {
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(
                                    new Fixture(List.of(row(1, "a"))),
                                    temporary,
                                    budget,
                                    Cancellation.NONE)
                            .join();
            var reads = new ArrayList<CompletableFuture<InputPage>>();
            for (int i = 0; i < 32; i++) {
                reads.add(input.read(null, 1, Cancellation.NONE));
            }
            input.closeAsync().join();
            for (var read : reads) {
                read.handle((value, failure) -> null).join();
            }
            assertDrained(budget);
        }
    }

    @Test
    void cancelingCaptureFutureCancelsHeldSourceReadAndWaitsForItsDrain() throws Exception {
        try (var budget = new TempBudget()) {
            var profile = budget.reserveMemory("held-profile", 512, Cancellation.NONE);
            var readEntered = new CompletableFuture<Void>();
            var readerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var closeEntered = new CompletableFuture<Void>();
            var releaseClose = new CompletableFuture<Void>();
            var reads = new CompletableFuture<SortedComparisonInput.UnsortedPage>();
            var closes = new AtomicInteger();
            SortedComparisonInput.Source source =
                    new SortedComparisonInput.Source() {
                        public Descriptor descriptor() {
                            return new Fixture(List.of()).descriptor();
                        }

                        public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                                String cursor, int count, long bytes, Cancellation token) {
                            readerThread.set(Thread.currentThread());
                            var registration =
                                    token.onCancel(
                                            () ->
                                                    reads.completeExceptionally(
                                                            new java.util.concurrent
                                                                    .CancellationException(
                                                                    "held source canceled")));
                            reads.whenComplete((value, failure) -> registration.close());
                            readEntered.complete(null);
                            return reads;
                        }

                        public CompletableFuture<Void> closeAsync() {
                            closes.incrementAndGet();
                            closeEntered.complete(null);
                            return releaseClose.thenRun(profile::close);
                        }
                    };
            var capture =
                    SortedComparisonInput.capture(source, temporary, budget, Cancellation.NONE);
            readEntered.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(capture.cancel(false));
            closeEntered.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(
                    reads.isCompletedExceptionally(),
                    "Private capture cancellation reaches the held source read");
            assertEquals(
                    512,
                    budget.snapshot().memory(),
                    "Source profile remains charged until close drains");
            releaseClose.complete(null);
            awaitDrained(budget);
            assertStopped(readerThread.get());
            assertEquals(1, closes.get());
        }
    }

    @Test
    void canceledDeliveryCannotOrphanAnAlreadySealedSortedInput() throws Exception {
        try (var budget = new TempBudget()) {
            var source = new Fixture(List.of(row(2, "b"), row(1, "a")));
            var delivery = new java.util.concurrent.ArrayBlockingQueue<Runnable>(1);
            var capture =
                    SortedComparisonInput.capture(
                            source, temporary, budget, Cancellation.NONE, delivery::add);
            var deliver = delivery.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(deliver != null, "Capture reached its deterministic delivery boundary");
            assertEquals(1, source.closes.get());
            assertTrue(
                    budget.snapshot().disk() > 0,
                    "Sealed input still owns charged scratch before delivery");
            assertTrue(capture.cancel(false));
            deliver.run();
            awaitDrained(budget);
            assertStopped(source.readerThread);
        }
    }

    @Test
    void tokenCancellationAtSealedDeliveryBoundaryReturnsTypedFailureAndDrains() throws Exception {
        try (var budget = new TempBudget()) {
            var token = new Cancellation();
            var delivery = new java.util.concurrent.ArrayBlockingQueue<Runnable>(1);
            var capture =
                    SortedComparisonInput.capture(
                            new Fixture(List.of(row(1, "a"))),
                            temporary,
                            budget,
                            token,
                            delivery::add);
            var deliver = delivery.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(deliver != null);
            token.cancel();
            deliver.run();
            var error = assertThrows(CompletionException.class, capture::join);
            assertEquals(
                    SortedComparisonInput.Reason.CANCELED,
                    assertInstanceOf(SortedComparisonInput.CaptureFailure.class, error.getCause())
                            .reason());
            assertDrained(budget);
        }
    }

    @Test
    void tokenCancellationWhileSourceCloseIsHeldCannotPublishSealedInput() throws Exception {
        try (var budget = new TempBudget()) {
            var release = new CompletableFuture<Void>();
            var source = new Fixture(List.of(row(1, "a")), release);
            var token = new Cancellation();
            var capture = SortedComparisonInput.capture(source, temporary, budget, token);
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (source.closes.get() == 0) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Capture did not reach source drain");
                }
                Thread.sleep(5);
            }
            token.cancel();
            assertFalse(capture.isDone());
            release.complete(null);
            var error = assertThrows(CompletionException.class, capture::join);
            assertEquals(
                    SortedComparisonInput.Reason.CANCELED,
                    assertInstanceOf(SortedComparisonInput.CaptureFailure.class, error.getCause())
                            .reason());
            assertDrained(budget);
        }
    }

    @Test
    void rejectedDeliveryAlsoDrainsTheUnpublishedInput() throws Exception {
        try (var budget = new TempBudget()) {
            var source = new Fixture(List.of(row(1, "a")));
            var capture =
                    SortedComparisonInput.capture(
                            source,
                            temporary,
                            budget,
                            Cancellation.NONE,
                            task -> {
                                throw new java.util.concurrent.RejectedExecutionException(
                                        "authored delivery rejection");
                            });
            assertThrows(CompletionException.class, capture::join);
            assertEquals(1, source.closes.get());
            assertDrained(budget);
        }
    }

    private static void assertStopped(Thread worker) throws Exception {
        assertTrue(worker != null);
        worker.join(3000);
        assertFalse(worker.isAlive(), "Canceled capture terminates its owned worker");
    }

    private void awaitDrained(TempBudget budget) throws Exception {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (budget.snapshot().reservations() != 0 || scratchCount() != 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Canceled capture did not drain owned resources");
            }
            Thread.sleep(5);
        }
        assertDrained(budget);
    }

    private long scratchCount() throws Exception {
        try (var files = Files.list(temporary)) {
            return files.count();
        }
    }

    @Test
    void borrowedReadRequiresLiveSameBudgetMemoryAndNeverChangesItsOwnership() throws Exception {
        try (var budget = new TempBudget();
                var otherBudget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(
                                    new Fixture(List.of(row(1, "a"))),
                                    temporary,
                                    budget,
                                    Cancellation.NONE)
                            .join();
            try (var wrong =
                            otherBudget.reserveMemory(
                                    "other-page",
                                    SortedComparisonInput.READ_ALLOWANCE_BYTES,
                                    Cancellation.NONE);
                    var disk =
                            budget.reserveDisk(
                                    "disk-is-not-memory",
                                    SortedComparisonInput.READ_ALLOWANCE_BYTES,
                                    Cancellation.NONE);
                    var small =
                            budget.reserveMemory(
                                    "too-small",
                                    SortedComparisonInput.READ_ALLOWANCE_BYTES - 1,
                                    Cancellation.NONE)) {
                var released =
                        budget.reserveMemory(
                                "released-page",
                                SortedComparisonInput.READ_ALLOWANCE_BYTES,
                                Cancellation.NONE);
                released.close();
                for (var invalid : List.of(wrong, disk, small, released)) {
                    assertThrows(
                            CompletionException.class,
                            () -> input.read(null, 1, Cancellation.NONE, invalid).join());
                }
                assertEquals("other-page", wrong.owner());
                assertEquals(SortedComparisonInput.READ_ALLOWANCE_BYTES, wrong.size());
                assertEquals("disk-is-not-memory", disk.owner());
                assertEquals(SortedComparisonInput.READ_ALLOWANCE_BYTES, disk.size());
                assertEquals("too-small", small.owner());
                assertEquals(SortedComparisonInput.READ_ALLOWANCE_BYTES - 1, small.size());
            }
            try (var valid =
                    budget.reserveMemory(
                            "caller-page",
                            SortedComparisonInput.READ_ALLOWANCE_BYTES,
                            Cancellation.NONE)) {
                var page = input.read(null, 1, Cancellation.NONE, valid).join();
                assertEquals(
                        List.of(key(1)),
                        page.rows().stream().map(Record::key).toList(),
                        "Rejected allowances did not consume input");
                assertEquals("caller-page", valid.owner());
                assertEquals(SortedComparisonInput.READ_ALLOWANCE_BYTES, valid.size());
                input.closeAsync().join();
                assertEquals(
                        SortedComparisonInput.READ_ALLOWANCE_BYTES,
                        budget.snapshot().memory(),
                        "Input close must not release borrowed caller allowance");
            }
            assertDrained(budget);
        }
    }

    @Test
    void allowanceIsRevalidatedWhenQueuedWorkerActuallyStarts() throws Exception {
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(
                                    new Fixture(List.of(row(1, "a"))),
                                    temporary,
                                    budget,
                                    Cancellation.NONE)
                            .join();
            var release = new CompletableFuture<Void>();
            var entered = new CompletableFuture<Void>();
            var worker = worker(input);
            worker.execute(
                    () -> {
                        entered.complete(null);
                        release.join();
                    });
            entered.get(3, java.util.concurrent.TimeUnit.SECONDS);
            var allowance =
                    budget.reserveMemory(
                            "borrowed-queued",
                            SortedComparisonInput.READ_ALLOWANCE_BYTES,
                            Cancellation.NONE);
            var read = input.read(null, 1, Cancellation.NONE, allowance);
            allowance.close();
            release.complete(null);
            var failure = assertThrows(CompletionException.class, read::join);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            input.closeAsync().join();
            assertDrained(budget);
        }
    }

    @Test
    void standaloneReadCancellationDoesNotReleasePrivateAllowanceBeforeWorkerCompletion()
            throws Exception {
        try (var budget = new TempBudget()) {
            var input =
                    SortedComparisonInput.capture(
                                    new Fixture(List.of(row(1, "a"))),
                                    temporary,
                                    budget,
                                    Cancellation.NONE)
                            .join();
            var release = new CompletableFuture<Void>();
            var entered = new CompletableFuture<Void>();
            var worker = worker(input);
            worker.execute(
                    () -> {
                        entered.complete(null);
                        release.join();
                    });
            entered.get(3, java.util.concurrent.TimeUnit.SECONDS);
            var read = input.read(null, 1, Cancellation.NONE);
            assertEquals(SortedComparisonInput.READ_ALLOWANCE_BYTES, budget.snapshot().memory());
            assertTrue(read.cancel(false));
            assertEquals(
                    SortedComparisonInput.READ_ALLOWANCE_BYTES,
                    budget.snapshot().memory(),
                    "Canceling caller future is not private-worker completion");
            var completed = new CompletableFuture<Void>();
            worker.execute(() -> completed.complete(null));
            release.complete(null);
            completed.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    0,
                    budget.snapshot().memory(),
                    "Private completion releases standalone allowance even when caller future is canceled");
            input.closeAsync().join();
            assertDrained(budget);
        }
    }

    @Test
    void standaloneReadCannotDecodeWithoutItsOwnFullAllowance() throws Exception {
        try (var budget = new TempBudget(13L << 20, 512L << 20)) {
            var input =
                    SortedComparisonInput.capture(
                                    new Fixture(List.of(row(1, "a"))),
                                    temporary,
                                    budget,
                                    Cancellation.NONE)
                            .join();
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () -> input.read(null, 1, Cancellation.NONE).join());
            assertInstanceOf(TempBudget.Limit.class, failure.getCause());
            assertEquals(0, budget.snapshot().memory());
            input.closeAsync().join();
            assertDrained(budget);
        }
    }

    private static java.util.concurrent.ThreadPoolExecutor worker(SortedComparisonInput input)
            throws Exception {
        // Hold the private operation deterministically without adding test hooks to the public API.
        var field = SortedComparisonInput.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) field.get(input);
    }

    private void assertFailure(Fixture source, SortedComparisonInput.Reason reason)
            throws Exception {
        try (var budget = new TempBudget()) {
            var error =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    SortedComparisonInput.capture(
                                                    source, temporary, budget, Cancellation.NONE)
                                            .join());
            var failure =
                    assertInstanceOf(SortedComparisonInput.CaptureFailure.class, error.getCause());
            assertEquals(reason, failure.reason());
            assertEquals(Completeness.PARTIAL, failure.coverage().completeness());
            assertEquals(1, source.closes.get());
            assertDrained(budget);
        }
    }

    private static List<Record> readAll(ComparisonInput input, int limit) {
        var result = new ArrayList<Record>();
        String cursor = null;
        do {
            var page = input.read(cursor, limit, Cancellation.NONE).join();
            assertEquals(Completeness.PARTIAL, page.coverage().completeness());
            result.addAll(page.rows());
            cursor = page.nextCursor();
        } while (cursor != null);
        return result;
    }

    private static Record row(long id, String value) {
        return new Record(
                key(id),
                Map.of(
                        "kind",
                        new Field(
                                "raw-copy-metadata",
                                ValueKind.TEXT,
                                ValueState.VALUE,
                                value,
                                Aspect.CONTENT,
                                List.of())),
                List.of());
    }

    private static String key(long value) {
        return String.format(java.util.Locale.ROOT, "%020d", value);
    }

    private void assertDrained(TempBudget budget) throws Exception {
        assertEquals(0, budget.snapshot().memory());
        assertEquals(0, budget.snapshot().disk());
        assertEquals(0, budget.snapshot().reservations());
        assertNoScratch();
    }

    private void assertNoScratch() throws Exception {
        try (var children = Files.list(temporary)) {
            assertEquals(0, children.count());
        }
    }
}
