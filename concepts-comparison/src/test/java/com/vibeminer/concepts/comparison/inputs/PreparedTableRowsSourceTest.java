package com.vibeminer.concepts.comparison.inputs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.comparison.ComparisonService;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTableRowsSourceTest {
    @TempDir Path directory;

    @Test
    void ordinalSourcesSortAndCompareReadablePartialCellsWithOneSharedBudget() throws Exception {
        try (var budget = new TempBudget()) {
            var before = new Fake("graph-a", List.of(row(9, "Nine", "9"), row(1, "One", "1")));
            var after =
                    new Fake("graph-b", List.of(row(2, "Two", "2"), row(9, "Nine changed", "9")));
            var a =
                    SortedComparisonInput.capture(
                                    source(before, budget), directory, budget, new Cancellation())
                            .join();
            var b =
                    SortedComparisonInput.capture(
                                    source(after, budget), directory, budget, new Cancellation())
                            .join();
            assertTrue(before.closed);
            assertTrue(after.closed);
            assertEquals(0, budget.snapshot().memory());
            try (var service = new ComparisonService(directory, budget)) {
                var progress = service.start(a, b).join();
                assertEquals(ComparisonTypes.RunState.COMPLETED, progress.state());
                assertEquals(3, progress.observed());
                assertEquals(ComparisonTypes.Completeness.UNKNOWN, progress.a().completeness());
                var results = service.page(0, 100).join().rows();
                assertEquals(3, results.size());
                assertEquals(
                        ComparisonTypes.Membership.OBSERVED_ONLY_A, results.get(0).membership());
                assertEquals(
                        ComparisonTypes.Membership.OBSERVED_ONLY_B, results.get(1).membership());
                assertTrue(
                        results.get(2).fields().stream()
                                .anyMatch(
                                        f ->
                                                f.key().equals("cell.Name")
                                                        && f.difference()
                                                                == ComparisonTypes.Difference
                                                                        .CHANGED));
            }
            assertEquals(0, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
            assertEquals(0, budget.snapshot().reservations());
            assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
            assertEquals(List.of(0L, 1L), before.requests);
        }
    }

    @Test
    void pinnedSchemaScopeIsSeparateFromEndpointGraphIdentity() throws Exception {
        try (var budget = new TempBudget()) {
            var a = source(new Fake("graph-a", List.of()), budget);
            var b = source(new Fake("graph-b", List.of()), budget);
            try {
                assertEquals(a.descriptor().scope(), b.descriptor().scope());
                assertEquals(a.descriptor().namespace(), b.descriptor().namespace());
                assertNotEquals(a.descriptor().identity(), b.descriptor().identity());
                assertEquals("NOT_CERTIFIED", a.descriptor().dependencies().get("raw.membership"));
            } finally {
                a.closeAsync().join();
                b.closeAsync().join();
            }
        }
    }

    @Test
    void typedTrailingMissingSlotPreservesOtherCellsWithoutGuessingInteriorAlignment()
            throws Exception {
        try (var budget = new TempBudget()) {
            var fake =
                    new Fake(
                            "graph",
                            List.of(
                                    new Db2Types.Row(
                                            1, false, null, List.of("Known name"), List.of())));
            fake.issues =
                    List.of(
                            new CapturedRowsTypes.CellIssue(
                                    0,
                                    1,
                                    CapturedRowsTypes.CellState.LIMITED,
                                    "CAPTURE_CELL_LIMIT"));
            var source = source(fake, budget);
            try {
                var record =
                        source.read(null, 256, 4L << 20, new Cancellation())
                                .join()
                                .rows()
                                .getFirst();
                assertEquals("Known name", record.fields().get("cell.Name").value());
                assertEquals(
                        ComparisonTypes.ValueState.LIMITED,
                        record.fields().get("cell.Count").state());
            } finally {
                source.closeAsync().join();
            }
            var ambiguous =
                    source(
                            new Fake(
                                    "graph-ambiguous",
                                    List.of(
                                            new Db2Types.Row(
                                                    1,
                                                    false,
                                                    null,
                                                    List.of("Only value"),
                                                    List.of()))),
                            budget);
            try {
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> ambiguous.read(null, 256, 4L << 20, new Cancellation()).join());
            } finally {
                ambiguous.closeAsync().join();
            }
        }
    }

    @Test
    void limitedIssueChannelCannotTurnPlaceholderIntoKnownEmpty() throws Exception {
        try (var budget = new TempBudget()) {
            var fake = new Fake("graph", List.of(row(1, "", "4")));
            fake.issues =
                    List.of(
                            new CapturedRowsTypes.CellIssue(
                                    0,
                                    -1,
                                    CapturedRowsTypes.CellState.LIMITED,
                                    "CAPTURE_ISSUE_CHANNEL_LIMIT"));
            var source = source(fake, budget);
            try {
                var record =
                        source.read(null, 1, 4L << 20, new Cancellation()).join().rows().getFirst();
                assertEquals(
                        ComparisonTypes.ValueState.UNKNOWN,
                        record.fields().get("cell.Name").state());
                assertEquals("4", record.fields().get("cell.Count").value());
            } finally {
                source.closeAsync().join();
            }
        }
    }

    @Test
    void graphLossAndStaleReceiptCannotBecomeEndOfFileOrRetry() throws Exception {
        for (boolean stale : List.of(false, true)) {
            try (var budget = new TempBudget()) {
                var fake = new Fake("graph", List.of(row(1, "Name", "1")));
                fake.bad =
                        stale
                                ? new CapturedRowsTypes.Page(
                                        "different-graph",
                                        0,
                                        CapturedRowsTypes.ReadState.READ,
                                        List.of(new TableTypes.Row(0, fake.rows.getFirst())),
                                        null,
                                        null,
                                        null)
                                : new CapturedRowsTypes.Page(
                                        "graph",
                                        0,
                                        CapturedRowsTypes.ReadState.INVALIDATED,
                                        List.of(),
                                        null,
                                        "MISSING",
                                        "Source removed");
                var source = source(fake, budget);
                try {
                    var error =
                            assertThrows(
                                    java.util.concurrent.CompletionException.class,
                                    () ->
                                            source.read(null, 1, 4L << 20, new Cancellation())
                                                    .join());
                    assertTrue(error.getCause() instanceof SortedComparisonInput.CaptureFailure);
                    assertEquals(
                            ComparisonTypes.Completeness.UNKNOWN,
                            ((SortedComparisonInput.CaptureFailure) error.getCause())
                                    .coverage()
                                    .completeness());
                    assertThrows(
                            java.util.concurrent.CompletionException.class,
                            () -> source.read(null, 1, 4L << 20, new Cancellation()).join());
                    assertEquals(1, fake.calls.get());
                } finally {
                    source.closeAsync().join();
                }
            }
        }
    }

    @Test
    void undersizedOutputAllowanceFailsBeforeNativeReadOrProjection() throws Exception {
        try (var budget = new TempBudget()) {
            var fake = new Fake("graph", List.of(row(1, "Name", "1")));
            var source = source(fake, budget);
            try {
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> source.read(null, 1, 32, new Cancellation()).join());
                assertEquals(0, fake.calls.get());
            } finally {
                source.closeAsync().join();
            }
        }
    }

    @Test
    void cancelingObserverCannotLoseOwnedReadAndCloseDrainsBeforeReleasingProfile()
            throws Exception {
        try (var budget = new TempBudget()) {
            var fake = new Fake("graph", List.of(row(1, "Name", "1")));
            fake.held = new CompletableFuture<>();
            var source = source(fake, budget);
            var page = source.read(null, 1, 4L << 20, new Cancellation());
            assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
            assertTrue(page.cancel(false));
            source.closeAsync().get(5, TimeUnit.SECONDS);
            assertTrue(fake.closed);
            assertEquals(0, budget.snapshot().memory());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> source.read(null, 1, 4L << 20, new Cancellation()).join());
        }
    }

    @Test
    void deliveryCallbackCanCloseAndJoinItsSourceWithoutWorkerDeadlock() throws Exception {
        try (var budget = new TempBudget()) {
            var source = source(new Fake("graph", List.of(row(1, "Name", "1"))), budget);
            source.read(null, 1, 4L << 20, new Cancellation())
                    .thenAccept(page -> source.closeAsync().join())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(0, budget.snapshot().memory());
        }
    }

    private static PreparedTableRowsSource source(Fake fake, TempBudget budget) throws Exception {
        return new PreparedTableRowsSource(
                fake, Map.of("build.identity", "authored-fixture"), budget, new Cancellation());
    }

    private static Db2Types.Row row(long id, String name, String count) {
        return new Db2Types.Row(id, false, null, List.of(name, count), List.of());
    }

    private static final class Fake implements CapturedRows {
        final String graph;
        final List<Db2Types.Row> rows;
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final List<Long> requests = new ArrayList<>();
        volatile boolean closed;
        volatile CompletableFuture<CapturedRowsTypes.Page> held;
        CapturedRowsTypes.Page bad;
        List<CapturedRowsTypes.CellIssue> issues = List.of();

        Fake(String graph, List<Db2Types.Row> rows) {
            this.graph = graph;
            this.rows = List.copyOf(rows);
        }

        public CapturedRowsTypes.Receipt receipt() {
            var columns =
                    List.of(
                            new Db2Types.Column("Name", "string", false, null, null),
                            new Db2Types.Column("Count", "integer", false, null, null));
            var schema =
                    new TableSchema(
                            List.of(
                                    new TableSchema.SchemaColumn(
                                            "Name",
                                            TableSchema.ColumnKind.STRING,
                                            null,
                                            null,
                                            1,
                                            null,
                                            TableSchema.KeyStorage.NONE,
                                            TableSchema.KeyStorage.NONE,
                                            true,
                                            null,
                                            null,
                                            null),
                                    new TableSchema.SchemaColumn(
                                            "Count",
                                            TableSchema.ColumnKind.INT,
                                            32,
                                            true,
                                            1,
                                            null,
                                            TableSchema.KeyStorage.NONE,
                                            TableSchema.KeyStorage.NONE,
                                            true,
                                            null,
                                            null,
                                            null)),
                            List.of("ABCD1234"),
                            TableSchema.SchemaMatch.LAYOUT,
                            WowLocale.EN_US,
                            null);
            var header =
                    new Db2Types.Header(
                            1, "00000001", "ABCD1234", rows.size(), 0, 8, columns, "WDC5");
            var completion =
                    new Db2Types.Completion(
                            rows.size(), rows.size(), 0, 1, 2, 0, "readable-only", 64, 0);
            return new CapturedRowsTypes.Receipt(
                    new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us"),
                    "enUS",
                    new TableTypes.Entry(
                            100,
                            "Demo",
                            TableTypes.State.PARTIAL,
                            true,
                            null,
                            new TableTypes.Counts(
                                    rows.size() + 2, rows.size(), 1, 2, "WDC5", "ABCD1234"),
                            "c".repeat(32)),
                    schema,
                    columns,
                    "d".repeat(64),
                    "block-v1",
                    graph,
                    "source-v1",
                    "reader-options-v1",
                    "reader-v1",
                    "rows-v1",
                    "encoding-v1",
                    rows.size(),
                    header,
                    completion,
                    List.of(),
                    List.of("key-name"),
                    List.of(new CapturedRowsTypes.KeyFingerprint("key-name", null)),
                    CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                    CapturedRowsTypes.Membership.NOT_CERTIFIED);
        }

        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                long first, int maximum, Cancellation token) {
            assertFalse(closed);
            assertEquals(1, maximum);
            calls.incrementAndGet();
            requests.add(first);
            entered.countDown();
            if (held != null) {
                token.onCancel(
                        () ->
                                held.completeExceptionally(
                                        new java.util.concurrent.CancellationException()));
                return held;
            }
            if (bad != null) {
                return CompletableFuture.completedFuture(bad);
            }
            return CompletableFuture.completedFuture(
                    new CapturedRowsTypes.Page(
                            graph,
                            first,
                            CapturedRowsTypes.ReadState.READ,
                            List.of(new TableTypes.Row(first, rows.get(Math.toIntExact(first)))),
                            first + 1 == rows.size() ? null : first + 1,
                            null,
                            null,
                            issues));
        }

        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }
}
