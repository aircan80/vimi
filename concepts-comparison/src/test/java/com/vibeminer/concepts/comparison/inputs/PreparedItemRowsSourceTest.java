package com.vibeminer.concepts.comparison.inputs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.items.ItemsSnapshot;
import com.vibeminer.concepts.comparison.items.ItemsStageCodec;
import com.vibeminer.concepts.items.PreparedItemTypes;
import com.vibeminer.concepts.items.PreparedItemTypes.Selection;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedItemRowsSourceTest {
    @TempDir Path directory;

    @Test
    void realSorterReleasesHeavySourceAndKeepsExternalMetadataForDecode() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake =
                    new Fake(
                            List.of(
                                    row(9, "Nine", "-1", List.of()),
                                    row(1, "One", "1", List.of())));
            var source = source(fake, snapshot, budget);
            var sorted =
                    SortedComparisonInput.capture(source, directory, budget, new Cancellation())
                            .join();
            try {
                assertTrue(fake.closed);
                assertEquals(1L << 20, budget.snapshot().memory());
                var page = sorted.read(null, 100, new Cancellation()).join();
                assertEquals(2, page.rows().size());
                var codec = new ItemsStageCodec(snapshot);
                assertEquals(1L, codec.decodeSource(page.rows().getFirst()).itemId());
                assertEquals(
                        "-1",
                        cell(
                                        codec.decodeSource(page.rows().getLast()),
                                        PreparedItemTypes.FactId.REQUIRED_LEVEL)
                                .value());
                assertEquals(ComparisonTypes.Completeness.UNKNOWN, page.coverage().completeness());
                assertTrue(budget.snapshot().peakMemory() <= 32L << 20);
            } finally {
                sorted.closeAsync().join();
            }
            assertEquals(0, budget.snapshot().disk());
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }

    @Test
    void oneOrdinalPerReadKeepsPartialMembershipAndUnsignedIdentity() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake =
                    new Fake(
                            List.of(
                                    row(0, "Zero", "-1", List.of()),
                                    row(4294967295L, "Maximum", "7", List.of())));
            var source = source(fake, snapshot, budget);
            try {
                var first = source.read(null, 256, 4L << 20, new Cancellation()).join();
                assertEquals("00000000000000000000", first.rows().getFirst().key());
                assertEquals(
                        5L << 20,
                        budget.snapshot().memory(),
                        "Projection scratch drains before output becomes visible");
                assertEquals(ComparisonTypes.Completeness.UNKNOWN, first.coverage().completeness());
                var second =
                        source.read(first.nextCursor(), 256, 4L << 20, new Cancellation()).join();
                assertEquals("00000000004294967295", second.rows().getFirst().key());
                assertNull(second.nextCursor());
                assertEquals(List.of(0L, 1L), fake.requests);
                assertEquals(
                        "PARTIAL", source.descriptor().dependencies().get("items.source.state"));
            } finally {
                source.closeAsync().join();
            }
            assertTrue(fake.closed);
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }

    @Test
    void smallAllowanceAndStaleCursorFailWithoutSourceRead() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of(row(1, "One", "1", List.of())));
            var source = source(fake, snapshot, budget);
            try {
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> source.read(null, 1, 1, new Cancellation()).join());
                assertTrue(fake.requests.isEmpty());
            } finally {
                source.closeAsync().join();
            }
        }
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of(row(1, "One", "1", List.of())));
            var source = source(fake, snapshot, budget);
            try {
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> source.read("1", 1, 4L << 20, new Cancellation()).join());
                assertTrue(fake.requests.isEmpty());
            } finally {
                source.closeAsync().join();
            }
        }
    }

    @Test
    void graphFailureIsNotEofAndNeverRetries() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of(row(1, "One", "1", List.of())));
            fake.failure = true;
            var source = source(fake, snapshot, budget);
            try {
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> source.read(null, 1, 4L << 20, new Cancellation()).join());
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> source.read(null, 1, 4L << 20, new Cancellation()).join());
                assertEquals(List.of(0L), fake.requests);
            } finally {
                source.closeAsync().join();
            }
        }
    }

    @Test
    void observerCancellationCannotReleaseOwnedReadOrSnapshot() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of(row(1, "One", "1", List.of())));
            fake.pending = new CompletableFuture<>();
            var source = source(fake, snapshot, budget);
            var observer = source.read(null, 1, 4L << 20, new Cancellation());
            assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
            observer.cancel(false);
            assertFalse(fake.closed);
            source.closeAsync().get(5, TimeUnit.SECONDS);
            assertTrue(fake.closed);
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }

    @Test
    void deliveryCallbackMayCloseAndJoinItsSource() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of(row(1, "One", "1", List.of())));
            var source = source(fake, snapshot, budget);
            source.read(null, 1, 4L << 20, new Cancellation())
                    .thenRun(() -> source.closeAsync().join())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(fake.closed);
        }
    }

    @Test
    void wrongSourceReceiptDoesNotTransferCaptureOwnership() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of());
            fake.fileId = 3;
            assertThrows(IllegalArgumentException.class, () -> source(fake, snapshot, budget));
            assertFalse(fake.closed);
            assertEquals(1L << 20, budget.snapshot().memory());
            fake.closeAsync().join();
        }
    }

    @Test
    void unsupportedIdentityHasTypedAdmissionWithoutTakingCapture() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of());
            fake.wrongHash = true;
            var failure =
                    assertThrows(
                            PreparedItemRowsSource.AdmissionFailure.class,
                            () -> source(fake, snapshot, budget));
            assertEquals(
                    com.vibeminer.concepts.items.PreparedItemTypes.IdentityAdmission.INVALID,
                    failure.compilation().identity());
            assertEquals(1572924L, failure.stamp().fileDataId());
            assertFalse(fake.closed);
            assertEquals(1L << 20, budget.snapshot().memory());
            fake.closeAsync().join();
        }
    }

    @Test
    void codecRoundTripPreservesUnknownEmptyInvalidAndIndependentSentinel() throws Exception {
        var unknown = facts(new Fake(List.of(row(1, "", "-1", null))));
        assertEquals(PreparedItemTypes.IssueChannel.UNKNOWN, unknown.issueChannel());
        assertEquals(
                PreparedItemTypes.CellState.UNKNOWN,
                cell(unknown, PreparedItemTypes.FactId.NAME).state());
        assertEquals("-1", cell(unknown, PreparedItemTypes.FactId.REQUIRED_LEVEL).value());
        var empty = facts(new Fake(List.of(row(1, "", "-1", List.of()))));
        assertEquals(
                PreparedItemTypes.CellState.KNOWN_EMPTY,
                cell(empty, PreparedItemTypes.FactId.NAME).state());
        var invalid =
                facts(
                        new Fake(
                                List.of(
                                        row(
                                                1,
                                                "",
                                                "-1",
                                                List.of(
                                                        new Db2Types.TextIssue(
                                                                1,
                                                                "DB2_TEXT_ENCODING_INVALID",
                                                                "ff"))))));
        assertEquals(
                PreparedItemTypes.CellState.INVALID,
                cell(invalid, PreparedItemTypes.FactId.NAME).state());
        assertTrue(
                cell(invalid, PreparedItemTypes.FactId.NAME)
                        .issues()
                        .contains("DB2_TEXT_ENCODING_INVALID"));
        var futureIssue =
                facts(
                        new Fake(
                                List.of(
                                        row(
                                                1,
                                                "",
                                                "-1",
                                                List.of(
                                                        new Db2Types.TextIssue(
                                                                1, "FUTURE_SOURCE_ISSUE", ""))))));
        assertEquals(
                PreparedItemTypes.CellState.UNKNOWN,
                cell(futureIssue, PreparedItemTypes.FactId.NAME).state());
    }

    @Test
    void sourceLimitsAndAttributedMissingTailNeverBecomeKnownEmpty() throws Exception {
        var fake = new Fake(List.of(row(1, "Name", "-1", List.of())));
        fake.limits =
                List.of(
                        new CapturedRowsTypes.CellIssue(
                                0, 1, CapturedRowsTypes.CellState.LIMITED, "CAPTURE_CELL_LIMIT"));
        assertEquals(
                PreparedItemTypes.CellState.LIMITED,
                cell(facts(fake), PreparedItemTypes.FactId.NAME).state());
        var channel = new Fake(List.of(row(1, "", "-1", List.of())));
        channel.limits =
                List.of(
                        new CapturedRowsTypes.CellIssue(
                                0,
                                -1,
                                CapturedRowsTypes.CellState.LIMITED,
                                "CAPTURE_ISSUE_CHANNEL_LIMIT"));
        var limited = facts(channel);
        assertEquals(PreparedItemTypes.IssueChannel.LIMITED, limited.issueChannel());
        assertEquals(
                PreparedItemTypes.CellState.UNKNOWN,
                cell(limited, PreparedItemTypes.FactId.NAME).state());
        assertEquals("-1", cell(limited, PreparedItemTypes.FactId.REQUIRED_LEVEL).value());
        var missing =
                new Fake(
                        List.of(
                                new Db2Types.Row(
                                        8, true, null, List.of("8", "Known name"), List.of())));
        missing.limits =
                List.of(
                        new CapturedRowsTypes.CellIssue(
                                0, 2, CapturedRowsTypes.CellState.LIMITED, "CAPTURE_CELL_LIMIT"));
        var partial = facts(missing);
        assertEquals("Known name", cell(partial, PreparedItemTypes.FactId.NAME).value());
        assertEquals(
                PreparedItemTypes.CellState.LIMITED,
                cell(partial, PreparedItemTypes.FactId.REQUIRED_LEVEL).state());
        assertEquals(
                PreparedItemTypes.CellState.UNKNOWN,
                cell(partial, PreparedItemTypes.FactId.NAME).copy().state());
        var ambiguous =
                facts(
                        new Fake(
                                List.of(
                                        new Db2Types.Row(
                                                8,
                                                false,
                                                null,
                                                List.of("8", "Shifted value"),
                                                List.of()))));
        assertEquals(PreparedItemTypes.RowState.INVALID, ambiguous.rowState());
        assertTrue(
                ambiguous.cells().stream()
                        .noneMatch(c -> c.state() == PreparedItemTypes.CellState.VALUE));
    }

    private static PreparedItemTypes.SourceCell cell(
            PreparedItemTypes.SourceFacts facts, PreparedItemTypes.FactId fact) {
        return facts.cells().stream().filter(c -> c.key().fact() == fact).findFirst().orElseThrow();
    }

    private static PreparedItemTypes.SourceFacts facts(Fake fake) throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var source = source(fake, snapshot, budget);
            try {
                var record =
                        source.read(null, 1, 4L << 20, new Cancellation()).join().rows().getFirst();
                return new ItemsStageCodec(snapshot).decodeSource(record);
            } finally {
                source.closeAsync().join();
            }
        }
    }

    @Test
    void heavyReceiptLimitIsTypedBeforeRegistrationOrOwnershipTransfer() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new ItemsSnapshot(budget, Selection.defaults(), new Cancellation())) {
            var fake = new Fake(List.of());
            fake.comment = "x".repeat(600000);
            var failure =
                    assertThrows(
                            SortedComparisonInput.CaptureFailure.class,
                            () -> source(fake, snapshot, budget));
            assertEquals(SortedComparisonInput.Reason.LIMITED, failure.reason());
            assertEquals(ComparisonTypes.Completeness.UNKNOWN, failure.coverage().completeness());
            assertTrue(snapshot.sources().isEmpty());
            assertFalse(fake.closed);
            assertEquals(1L << 20, budget.snapshot().memory());
            fake.closeAsync().join();
        }
    }

    private static PreparedItemRowsSource source(
            Fake fake, ItemsSnapshot snapshot, TempBudget budget) throws Exception {
        return new PreparedItemRowsSource(
                fake, ItemsSnapshot.Side.A, Map.of(), snapshot, budget, new Cancellation());
    }

    private static Db2Types.Row row(
            long id, String name, String level, List<Db2Types.TextIssue> issues) {
        return new Db2Types.Row(id, false, null, List.of(Long.toString(id), name, level), issues);
    }

    private static TableSchema.SchemaColumn field(
            String name, TableSchema.ColumnKind kind, boolean id) {
        return new TableSchema.SchemaColumn(
                name,
                kind,
                kind == TableSchema.ColumnKind.INT ? 32 : null,
                kind == TableSchema.ColumnKind.INT ? false : null,
                1,
                null,
                id ? TableSchema.KeyStorage.INLINE : TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                true,
                kind == TableSchema.ColumnKind.LOCSTRING ? WowLocale.EN_US : null,
                null,
                null);
    }

    private static final class Fake implements CapturedRows {
        final List<Db2Types.Row> rows;
        final List<Long> requests = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        long fileId = 1572924;
        volatile boolean closed;
        boolean failure;
        boolean wrongHash;
        String comment;
        CompletableFuture<CapturedRowsTypes.Page> pending;
        List<CapturedRowsTypes.CellIssue> limits = List.of();

        Fake(List<Db2Types.Row> rows) {
            this.rows = List.copyOf(rows);
        }

        public CapturedRowsTypes.Receipt receipt() {
            var columns =
                    List.of(
                            new Db2Types.Column("ID", "integer", true, null, null),
                            new Db2Types.Column("Display_lang", "string", false, null, null),
                            new Db2Types.Column("RequiredLevel", "integer", false, null, null));
            var schema =
                    new TableSchema(
                            List.of(
                                    field("ID", TableSchema.ColumnKind.INT, true),
                                    field("Display_lang", TableSchema.ColumnKind.LOCSTRING, false),
                                    field("RequiredLevel", TableSchema.ColumnKind.INT, false)),
                            List.of("ABCD1234"),
                            TableSchema.SchemaMatch.LAYOUT,
                            WowLocale.EN_US,
                            comment);
            var header =
                    new Db2Types.Header(
                            5,
                            wrongHash ? "00000000" : "919BE54E",
                            "ABCD1234",
                            rows.size(),
                            0,
                            12,
                            columns,
                            "WDC5");
            var completion =
                    new Db2Types.Completion(
                            rows.size(), rows.size(), 0, 1, 2, 0, "readable-only", 64, 0);
            return new CapturedRowsTypes.Receipt(
                    new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us"),
                    "enUS",
                    new TableTypes.Entry(
                            fileId,
                            "ItemSparse",
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
                    "e".repeat(32),
                    "source-v1",
                    "options-v1",
                    GeneratedTableSource.READER_REVISION,
                    GeneratedTableSource.ROW_ENCODING,
                    "encoding-v1",
                    rows.size(),
                    header,
                    completion,
                    List.of(),
                    List.of(),
                    List.of(new CapturedRowsTypes.KeyFingerprint("unknown", null)),
                    CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                    CapturedRowsTypes.Membership.NOT_CERTIFIED);
        }

        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                long ordinal, int maximum, Cancellation token) {
            assertEquals(1, maximum);
            assertFalse(closed);
            requests.add(ordinal);
            entered.countDown();
            if (pending != null) {
                token.onCancel(
                        () ->
                                pending.completeExceptionally(
                                        new java.util.concurrent.CancellationException()));
                return pending;
            }
            if (failure) {
                return CompletableFuture.completedFuture(
                        new CapturedRowsTypes.Page(
                                "e".repeat(32),
                                ordinal,
                                CapturedRowsTypes.ReadState.INVALIDATED,
                                List.of(),
                                null,
                                "MISSING",
                                "Source gone"));
            }
            return CompletableFuture.completedFuture(
                    new CapturedRowsTypes.Page(
                            "e".repeat(32),
                            ordinal,
                            CapturedRowsTypes.ReadState.READ,
                            List.of(new TableTypes.Row(ordinal, rows.get((int) ordinal))),
                            ordinal + 1 == rows.size() ? null : ordinal + 1,
                            null,
                            null,
                            limits));
        }

        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }
}
