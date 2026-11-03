package com.vibeminer.concepts.comparison.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot.Side;
import com.vibeminer.concepts.comparison.inputs.PreparedEffectRowsSource;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CellState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedEffectRowsSourceTest {
    @TempDir Path directory;

    private static Db2Types.Row bridge(long id) {
        return new Db2Types.Row(
                id, false, null, List.of(Long.toString(id), "77", "123"), List.of());
    }

    private static final class Fake implements CapturedRows {
        private final List<Db2Types.Row> rows;
        private final CapturedRowsTypes.Receipt receipt;
        boolean closed;
        boolean wrongPageIdentity;
        boolean limitedReference;
        boolean hold;
        final CountDownLatch started = new CountDownLatch(1);
        final CompletableFuture<CapturedRowsTypes.Page> pending = new CompletableFuture<>();

        Fake(List<Db2Types.Row> rows, boolean nativeEvidence, boolean badNativeBinding)
                throws Exception {
            this.rows = rows;
            var schema = EffectsFixtures.schema(SourceKind.BRIDGE);
            var s = schema.stamp();
            byte[] tuple =
                    NativeIssueIdentity.tuple(
                            GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                            "native-row-issues-v1",
                            "java-db2-native-producer-v1",
                            null,
                            s.contentKey(),
                            s.blockIdentity(),
                            s.readerOptions(),
                            s.readerRevision(),
                            s.rowEncoding(),
                            null);
            String source = "native-tuple-hex=" + HexFormat.of().formatHex(tuple);
            var evidence =
                    nativeEvidence
                            ? new CapturedRowsTypes.IssueChannelEvidence(
                                    CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                                    "native-row-issues-v1",
                                    "java-db2-native-producer-v1",
                                    s.graphIdentity(),
                                    HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(tuple)),
                                    s.blockIdentity(),
                                    badNativeBinding ? "9".repeat(64) : s.readerOptions(),
                                    NativeIssueIdentity.columns(schema.columns()))
                            : CapturedRowsTypes.IssueChannelEvidence.unknown();
            receipt =
                    new CapturedRowsTypes.Receipt(
                            s.build(),
                            s.locale(),
                            new TableTypes.Entry(
                                    s.fileDataId(),
                                    s.tableName(),
                                    TableTypes.State.PARTIAL,
                                    true,
                                    null,
                                    new TableTypes.Counts(
                                            10, rows.size(), 1, 5, "WDC5", s.layoutHash()),
                                    s.contentKey()),
                            schema.schema(),
                            schema.columns(),
                            s.definitionSha256(),
                            s.blockIdentity(),
                            s.graphIdentity(),
                            source,
                            s.readerOptions(),
                            s.readerRevision(),
                            s.rowEncoding(),
                            null,
                            rows.size(),
                            schema.header(),
                            new Db2Types.Completion(
                                    10, rows.size(), 0, 1, 5, 0, "readable-only", 100, 0),
                            List.of(),
                            List.of(),
                            List.of(),
                            CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                            CapturedRowsTypes.Membership.NOT_CERTIFIED,
                            evidence);
        }

        @Override
        public CapturedRowsTypes.Receipt receipt() {
            return receipt;
        }

        CapturedRowsTypes.Page page(long at) {
            var issues =
                    limitedReference
                            ? List.of(
                                    new CapturedRowsTypes.CellIssue(
                                            at,
                                            1,
                                            CapturedRowsTypes.CellState.LIMITED,
                                            "CAPTURE_CELL_LIMIT"))
                            : List.<CapturedRowsTypes.CellIssue>of();
            return new CapturedRowsTypes.Page(
                    wrongPageIdentity ? "0".repeat(32) : receipt.graphIdentity(),
                    at,
                    CapturedRowsTypes.ReadState.READ,
                    List.of(new TableTypes.Row(at, rows.get((int) at))),
                    at + 1 == rows.size() ? null : at + 1,
                    null,
                    null,
                    issues);
        }

        @Override
        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                long at, int max, Cancellation token) {
            assertEquals(1, max);
            started.countDown();
            return hold ? pending : CompletableFuture.completedFuture(page(at));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void realSorterDrainsHeavyCaptureAndKeepsSnapshotForDetachedDecode() throws Exception {
        var budget = new TempBudget();
        try (var snapshot =
                new EffectsSnapshot(
                        budget,
                        EffectsFixtures.endpoint("A"),
                        EffectsFixtures.endpoint("B"),
                        Cancellation.NONE)) {
            var fake = new Fake(List.of(bridge(9), bridge(1)), true, false);
            var source =
                    new PreparedEffectRowsSource(
                            fake, Side.A, SourceKind.BRIDGE, snapshot, budget, Cancellation.NONE);
            var sorted =
                    SortedComparisonInput.capture(source, directory, budget, Cancellation.NONE)
                            .join();
            try {
                assertTrue(fake.closed);
                assertEquals(1L << 20, budget.snapshot().memory());
                var page = sorted.read(null, 100, Cancellation.NONE).join();
                assertEquals(
                        List.of(EffectsStageCodec.ownKey(1), EffectsStageCodec.ownKey(9)),
                        page.rows().stream().map(r -> r.key()).toList());
                var decoded = new EffectsStageCodec(snapshot).decodeSource(page.rows().getFirst());
                assertEquals(IdentityAdmission.ADMITTED, decoded.identity());
                assertEquals("77", decoded.cells().get(1).value());
                assertTrue(budget.snapshot().peakMemory() <= 32L << 20);
                assertEquals("NOT_CERTIFIED", fake.receipt().membership().name());
            } finally {
                sorted.closeAsync().join();
            }
            assertEquals(0, budget.snapshot().disk());
        }
        assertEquals(0, budget.snapshot().memory());
        budget.close();
    }

    @Test
    void nativeBindingFailureLeavesCaptureCallerOwnedAndTransfersNoSnapshotMetadata()
            throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var bad = new Fake(List.of(bridge(1)), true, true);
            long before = snapshot.estimatedRetainedBytes();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new PreparedEffectRowsSource(
                                    bad,
                                    Side.A,
                                    SourceKind.BRIDGE,
                                    snapshot,
                                    budget,
                                    Cancellation.NONE));
            assertFalse(bad.closed);
            assertEquals(before, snapshot.estimatedRetainedBytes());
            assertEquals(1L << 20, budget.snapshot().memory());
            bad.closeAsync().join();
        }
    }

    @Test
    void unknownNativeChannelAndCellLimitationCannotCertifyInlineJoinReference() throws Exception {
        for (boolean nativeEvidence : List.of(false, true)) {
            try (var budget = new TempBudget();
                    var snapshot =
                            new EffectsSnapshot(
                                    budget,
                                    EffectsFixtures.endpoint("A"),
                                    EffectsFixtures.endpoint("B"),
                                    Cancellation.NONE)) {
                var fake = new Fake(List.of(bridge(1)), nativeEvidence, false);
                fake.limitedReference = nativeEvidence;
                var source =
                        new PreparedEffectRowsSource(
                                fake,
                                Side.A,
                                SourceKind.BRIDGE,
                                snapshot,
                                budget,
                                Cancellation.NONE);
                try {
                    var page = source.read(null, 1, 4L << 20, Cancellation.NONE).join();
                    var facts =
                            new EffectsStageCodec(snapshot).decodeSource(page.rows().getFirst());
                    assertEquals(
                            nativeEvidence ? CellState.LIMITED : CellState.UNKNOWN,
                            facts.cells().get(1).state());
                    if (!nativeEvidence) {
                        assertEquals(IdentityAdmission.UNKNOWN, facts.identity());
                    }
                } finally {
                    source.closeAsync().join();
                }
                assertEquals(1L << 20, budget.snapshot().memory());
            }
        }
    }

    @Test
    void sourceIdentityLossIsTerminalAndOwnedCloseWaitsForActualReadDrain() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var fake = new Fake(List.of(bridge(1)), true, false);
            fake.hold = true;
            var source =
                    new PreparedEffectRowsSource(
                            fake, Side.A, SourceKind.BRIDGE, snapshot, budget, Cancellation.NONE);
            var reading = source.read(null, 1, 4L << 20, Cancellation.NONE);
            assertTrue(fake.started.await(5, TimeUnit.SECONDS));
            var closing = source.closeAsync();
            assertFalse(closing.isDone());
            assertFalse(fake.closed);
            fake.pending.complete(fake.page(0));
            assertThrows(RuntimeException.class, reading::join);
            closing.get(5, TimeUnit.SECONDS);
            assertTrue(fake.closed);
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }

    @Test
    void duplicateOwnIdsAcrossDifferentReferencesAreRejectedBeforeRouting() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var duplicate = new Db2Types.Row(1, false, null, List.of("1", "88", "124"), List.of());
            var fake = new Fake(List.of(bridge(1), duplicate), true, false);
            var source =
                    new PreparedEffectRowsSource(
                            fake, Side.A, SourceKind.BRIDGE, snapshot, budget, Cancellation.NONE);
            assertThrows(
                    RuntimeException.class,
                    () ->
                            SortedComparisonInput.capture(
                                            source, directory, budget, Cancellation.NONE)
                                    .join());
            assertTrue(fake.closed);
            assertEquals(0, budget.snapshot().disk());
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }
}
