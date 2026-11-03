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
