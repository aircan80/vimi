package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Aspect;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.Difference;
import com.vibeminer.concepts.comparison.ComparisonTypes.Field;
import com.vibeminer.concepts.comparison.ComparisonTypes.Membership;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.ComparisonTypes.RunState;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueKind;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real capture, multirun sorting, borrowed core pages and result framing together. */
final class SortedCoreCompositionTest {
    private static final String PAYLOAD = "x".repeat(6000);
    private static final int ROWS = 1500;
    @TempDir Path directory;

    private static String key(long id) {
        return String.format(Locale.ROOT, "%020d", id);
    }

    private static String value(String side, long id) {
        return side + ":" + id + ":" + PAYLOAD;
    }

    private static Record row(String side, long id) {
        return new Record(
                key(id),
                Map.of(
                        "value",
                        new Field(
                                "authored-record-value-v1",
                                ValueKind.TEXT,
                                ValueState.VALUE,
                                value(side, id),
                                Aspect.CONTENT,
                                List.of())),
                List.of());
    }

    /** Generates a bounded reversed page only after the sorter's shared allowance exists. */
    private static final class Source implements SortedComparisonInput.Source {
        final String side;
        final int firstId;
        final Completeness certainty;
        final TempBudget budget;
        final AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();

        Source(String side, int firstId, Completeness certainty, TempBudget budget) {
            this.side = side;
            this.firstId = firstId;
            this.certainty = certainty;
            this.budget = budget;
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(
                    side,
                    "wowf",
                    "enUS",
                    "authored-raw-records",
                    "held-readable-fixture",
                    Map.of("profile", "authored-v1"));
        }

        @Override
        public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                String cursor, int maxRows, long maxBytes, Cancellation token) {
            token.check();
            assertEquals(0, closes.get());
            assertTrue(
                    budget.snapshot().memory() >= maxBytes,
                    "Source output is reserved before allocation");
            reads.incrementAndGet();
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            var records = new ArrayList<Record>();
            long planned = 8192;
            long perRow = 4096L + 4L * (PAYLOAD.length() + 64);
            int at = from;
            while (at < ROWS && records.size() < maxRows && planned + perRow <= maxBytes) {
                token.check();
                long id = firstId + ROWS - 1L - at;
                var record = row(side, id);
                assertTrue(SortedComparisonInput.retainedBytes(record) <= perRow);
                records.add(record);
                planned += perRow;
                at++;
            }
            assertFalse(
                    records.isEmpty(), "Authored row must fit the explicit source-page allowance");
            return CompletableFuture.completedFuture(
                    new SortedComparisonInput.UnsortedPage(
                            side,
                            records,
                            at == ROWS ? null : Integer.toString(at),
                            new Coverage(
                                    certainty,
                                    "held-readable-fixture",
                                    List.of("Only readable source membership is assessed"))));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void multirunSortedInputsAndCoreUseOneBudgetWithoutDoubleChargingOrLosingUnknowns()
            throws Exception {
        var budget = new TempBudget();
        SortedComparisonInput a = null, b = null;
        try {
            var left = new Source("a", 1, Completeness.UNKNOWN, budget);
            var right = new Source("b", 2, Completeness.PARTIAL, budget);
            // Three or more 12 MiB runs force at least two two-way merge passes on each side.
            assertTrue(
                    SortedComparisonInput.retainedBytes(row("a", 1)) * ROWS
                            > 2 * SortedComparisonInput.CHUNK_BYTES);
            a = SortedComparisonInput.capture(left, directory, budget, Cancellation.NONE).join();
            assertEquals(1, left.closes.get());
            assertEquals(0, budget.snapshot().memory());
            assertTrue(budget.snapshot().disk() > 0);
            b = SortedComparisonInput.capture(right, directory, budget, Cancellation.NONE).join();
            assertEquals(1, right.closes.get());
            assertTrue(left.reads.get() > 2 && right.reads.get() > 2);
            assertEquals(
                    0,
                    budget.snapshot().memory(),
                    "No source/sorter metadata allowance is held into core.start");
            long sealedInputs = budget.snapshot().disk();
            try (var service = new ComparisonService(directory, budget)) {
                var progress = service.start(a, b).join();
                assertEquals(RunState.COMPLETED, progress.state());
                assertEquals(ROWS + 1L, progress.observed());
                assertEquals(Completeness.UNKNOWN, progress.a().completeness());
                assertEquals(Completeness.PARTIAL, progress.b().completeness());
                assertEquals(sealedInputs + progress.stagedBytes(), budget.snapshot().disk());
                assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
                assertEquals(ComparisonService.BUFFER_BYTES, budget.snapshot().peakMemory());
                assertTrue(budget.snapshot().peakDisk() <= ComparisonService.STAGING_BYTES);
                long offset = 0;
                int seen = 0, pages = 0;
                do {
                    var page = service.page(offset, 100).join();
                    pages++;
                    assertFalse(page.rows().isEmpty());
                    for (var result : page.rows()) {
                        long id = ++seen;
                        assertEquals(key(id), result.key());
                        assertFalse(
                                result.membership() == Membership.ADDED
                                        || result.membership() == Membership.REMOVED);
                        if (id == 1) {
                            assertEquals(Membership.OBSERVED_ONLY_A, result.membership());
                        } else if (id == ROWS + 1L) {
                            assertEquals(Membership.OBSERVED_ONLY_B, result.membership());
                        } else {
                            assertEquals(Membership.BOTH, result.membership());
                            assertEquals(
                                    Difference.CHANGED, result.fields().getFirst().difference());
                        }
                        if (result.a() != null) {
                            assertEquals(value("a", id), result.a().fields().get("value").value());
                        }
                        if (result.b() != null) {
                            assertEquals(value("b", id), result.b().fields().get("value").value());
                        }
                    }
                    if (page.nextOffset() == null) {
                        break;
                    }
                    offset = page.nextOffset();
                } while (true);
                assertEquals(ROWS + 1, seen);
                assertTrue(pages > 1);
                assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
            }
            assertEquals(1, left.closes.get());
            assertEquals(1, right.closes.get());
            assertEquals(0, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
            assertEquals(0, budget.snapshot().reservations());
            try (var paths = Files.list(directory)) {
                assertEquals(0, paths.count());
            }
        } finally {
            if (a != null) {
                a.closeAsync().join();
            }
            if (b != null) {
                b.closeAsync().join();
            }
            budget.close();
        }
    }
}
