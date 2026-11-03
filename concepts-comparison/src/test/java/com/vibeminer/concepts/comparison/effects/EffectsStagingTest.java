package com.vibeminer.concepts.comparison.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.BudgetedTempFile;
import com.vibeminer.concepts.comparison.ComparisonService;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.EffectsStaging;
import com.vibeminer.concepts.comparison.SealedCandidateInput;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot.Side;
import com.vibeminer.concepts.comparison.inputs.PreparedEffectRowsSource;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.formats.db2.Db2Types;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectsStagingTest {
    @TempDir Path directory;

    private SortedComparisonInput capture(
            EffectsSnapshot snapshot,
            Side side,
            SourceKind kind,
            List<Db2Types.Row> rows,
            TempBudget budget)
            throws Exception {
        var source =
                new PreparedEffectRowsSource(
                        EffectsFixtures.capture(kind, rows),
                        side,
                        kind,
                        snapshot,
                        budget,
                        Cancellation.NONE);
        return SortedComparisonInput.capture(source, directory, budget, Cancellation.NONE).join();
    }

    private static Db2Types.Row link(long id, long reference, long parent) {
        return new Db2Types.Row(
                id,
                false,
                null,
                List.of(Long.toString(id), Long.toString(reference), Long.toString(parent)),
                List.of());
    }

    private static Db2Types.Row effect(long id, String spell, boolean largeEvidence) {
        String bad = largeEvidence ? "x".repeat(8192) : "-1";
        // Adversarial captured numeric evidence exercises bounded INVALID fields, not a native
        // integer decoder's ordinary output; unrelated verified SpellID remains useful.
        return new Db2Types.Row(
                id,
                false,
                null,
                List.of(
                        Long.toString(id),
                        "1",
                        "2",
                        bad,
                        "4",
                        "5",
                        largeEvidence ? bad : "6",
                        spell,
                        largeEvidence ? bad : "8",
                        "9"),
                List.of());
    }

    private BudgetedTempFile endpoint(
            EffectsSnapshot snapshot,
            Side side,
            TempBudget budget,
            int count,
            boolean largeEvidence)
            throws Exception {
        var links = new ArrayList<Db2Types.Row>();
        for (int i = count; i > 0; i--) {
            links.add(link(i, 77, 123));
        }
        links.add(link(count + 1, 88, 123));
        links.add(link(count + 2, -1, 123));
        if (side == Side.A) {
            links.add(link(9999, 77, 124));
        }
        var own = capture(snapshot, side, SourceKind.BRIDGE, links, budget);
        snapshot.selectMode(side, RelationMode.BRIDGE);
        var effects =
                side == Side.A
                        ? List.of(
                                effect(99, "111", false),
                                effect(88, "777", false),
                                effect(77, "333", largeEvidence))
                        : List.of(effect(99, "111", false), effect(77, "334", largeEvidence));
        var targets = capture(snapshot, side, SourceKind.RETAIL_EFFECT, effects, budget);
        var routedSpool =
                EffectsStaging.routeBridge(
                        own, side, snapshot, directory, budget, Cancellation.NONE);
        var routed =
                EffectsStaging.sortSpool(
                        routedSpool,
                        side,
                        snapshot,
                        directory,
                        budget,
                        Cancellation.NONE,
                        EffectsStaging.StageOrder.ROUTED);
        var candidates =
                EffectsStaging.joinBridge(
                        routed, targets, side, snapshot, directory, budget, Cancellation.NONE);
        var sorted =
                EffectsStaging.sortSpool(
                        candidates,
                        side,
                        snapshot,
                        directory,
                        budget,
                        Cancellation.NONE,
                        EffectsStaging.StageOrder.LINK);
        return EffectsStaging.sealCandidates(
                sorted, snapshot, directory, budget, Cancellation.NONE);
    }

    @Test
    void actualCaptureRouteFanoutFinalSortAndCoreKeepSameBudgetThroughByteShortenedPaging()
            throws Exception {
        var budget = new TempBudget();
        try (var snapshot =
                new EffectsSnapshot(
                        budget,
                        EffectsFixtures.endpoint("A"),
                        EffectsFixtures.endpoint("B"),
                        Cancellation.NONE)) {
            var aFile = endpoint(snapshot, Side.A, budget, 300, true);
            var bFile = endpoint(snapshot, Side.B, budget, 300, true);
            snapshot.sealScope();
            assertTrue(budget.snapshot().peakMemory() <= 30L << 20);
            assertEquals(1L << 20, budget.snapshot().memory());
            var codec = new EffectsStageCodec(snapshot);
            var a =
                    SealedCandidateInput.adopt(
                            aFile,
                            snapshot.descriptor(Side.A),
                            budget,
                            row -> codec.project(Side.A, row));
            var b =
                    SealedCandidateInput.adopt(
                            bFile,
                            snapshot.descriptor(Side.B),
                            budget,
                            row -> codec.project(Side.B, row));
            try (var service =
                    new ComparisonService(
                            directory,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            snapshot.metadataReservation())) {
                var progress = service.start(a, b).join();
                assertEquals(ComparisonTypes.RunState.COMPLETED, progress.state());
                assertEquals(ComparisonTypes.Completeness.UNKNOWN, progress.a().completeness());
                Long offset = 0L;
                int findings = 0, spellChanges = 0;
                boolean shortened = false, observedOnly = false, missingTarget = false;
                while (offset != null) {
                    var page = service.page(offset, 100).join();
                    assertEquals(1L << 20, snapshot.metadataReservation().size());
                    if (page.nextOffset() != null && page.rows().size() < 100) {
                        shortened = true;
                    }
                    for (var result : page.rows()) {
                        findings++;
                        assertFalse(
                                result.membership() == ComparisonTypes.Membership.ADDED
                                        || result.membership()
                                                == ComparisonTypes.Membership.REMOVED);
                        if (result.membership() == ComparisonTypes.Membership.OBSERVED_ONLY_A) {
                            observedOnly = true;
                        }
                        for (var field : result.fields()) {
                            if (field.key().equals("effect.RETAIL_EFFECT.SpellID")
                                    && field.difference() == ComparisonTypes.Difference.CHANGED) {
                                spellChanges++;
                            }
                            if (field.key().equals("effect.target.availability")
                                    && field.b() != null
                                    && "UNKNOWN".equals(field.b().value())) {
                                missingTarget = true;
                            }
                        }
                    }
                    offset = page.nextOffset();
                    assertTrue(budget.snapshot().peakMemory() <= 32L << 20);
                    assertTrue(budget.snapshot().peakDisk() <= 512L << 20);
                }
                assertEquals(300, spellChanges);
                assertTrue(findings >= 302);
                assertTrue(observedOnly);
                assertTrue(missingTarget);
                assertTrue(
                        shortened,
                        "Large source evidence must exercise actual byte-shortened paging");
            }
            assertEquals(1L << 20, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
        }
        assertEquals(0, budget.snapshot().memory());
        budget.close();
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void directRowsSortByOwnIdBeforeParentOrderAndDoNotSelfJoin() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var values = new ArrayList<>(effect(2, "77", false).values());
            values.add("123");
            var other = new ArrayList<>(effect(1, "88", false).values());
            other.add("124");
            var own =
                    capture(
                            snapshot,
                            Side.A,
                            SourceKind.DIRECT_EFFECT,
                            List.of(
                                    new Db2Types.Row(2, false, null, values, List.of()),
                                    new Db2Types.Row(1, false, null, other, List.of())),
                            budget);
            snapshot.selectMode(Side.A, RelationMode.DIRECT);
            var candidates =
                    EffectsStaging.directCandidates(
                            own, Side.A, snapshot, directory, budget, Cancellation.NONE);
            var sorted =
                    EffectsStaging.sortSpool(
                            candidates,
                            Side.A,
                            snapshot,
                            directory,
                            budget,
                            Cancellation.NONE,
                            EffectsStaging.StageOrder.LINK);
            try {
                var page = sorted.read(null, 100, Cancellation.NONE).join();
                assertEquals(
                        List.of(
                                "D" + EffectsStageCodec.ownKey(123) + EffectsStageCodec.ownKey(2),
                                "D" + EffectsStageCodec.ownKey(124) + EffectsStageCodec.ownKey(1)),
                        page.rows().stream().map(row -> row.key()).toList());
            } finally {
                sorted.closeAsync().join();
            }
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void routeQuotaFailureAndCanceledJoinDrainPredecessorsWithoutPublishingSuccessors()
            throws Exception {
        try (var budget = new TempBudget(32L << 20, 512L << 20);
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var own =
                    capture(snapshot, Side.A, SourceKind.BRIDGE, List.of(link(1, 77, 123)), budget);
            snapshot.selectMode(Side.A, RelationMode.BRIDGE);
            long oldBytes = budget.snapshot().disk();
            var blocker =
                    budget.reserveDisk(
                            "authored-quota-blocker", (512L << 20) - oldBytes, Cancellation.NONE);
            assertThrows(
                    Exception.class,
                    () ->
                            EffectsStaging.routeBridge(
                                    own, Side.A, snapshot, directory, budget, Cancellation.NONE));
            blocker.close();
            assertEquals(0, budget.snapshot().disk());
            assertEquals(1L << 20, budget.snapshot().memory());
        }
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var own =
                    capture(snapshot, Side.A, SourceKind.BRIDGE, List.of(link(1, 77, 123)), budget);
            snapshot.selectMode(Side.A, RelationMode.BRIDGE);
            var routed =
                    EffectsStaging.sortSpool(
                            EffectsStaging.routeBridge(
                                    own, Side.A, snapshot, directory, budget, Cancellation.NONE),
                            Side.A,
                            snapshot,
                            directory,
                            budget,
                            Cancellation.NONE,
                            EffectsStaging.StageOrder.ROUTED);
            var targets =
                    capture(
                            snapshot,
                            Side.A,
                            SourceKind.RETAIL_EFFECT,
                            List.of(effect(77, "1", false)),
                            budget);
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    Exception.class,
                    () ->
                            EffectsStaging.joinBridge(
                                    routed, targets, Side.A, snapshot, directory, budget, token));
            assertEquals(0, budget.snapshot().disk());
            assertEquals(1L << 20, budget.snapshot().memory());
        }
    }

    @Test
    void spoolDecoderRejectsMalformedFrameBeforeUnboundedObjectAllocation() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var spool =
                    BudgetedTempFile.create(
                            directory, "malformed-", budget, "authored", Cancellation.NONE);
            spool.appendFrame(new byte[] {1, 127, 127, 127, 127}, Cancellation.NONE);
            spool.seal();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            EffectsStaging.sortSpool(
                                    spool,
                                    Side.A,
                                    snapshot,
                                    directory,
                                    budget,
                                    Cancellation.NONE,
                                    EffectsStaging.StageOrder.LINK));
            assertEquals(0, budget.snapshot().disk());
            assertEquals(1L << 20, budget.snapshot().memory());
            assertNotNull(snapshot.endpoint(Side.A));
        }
    }

    @Test
    void failedSpoolDeletionKeepsItsChargeUntilTheOwnerCanRetryCleanup() throws Exception {
        try (var budget = new TempBudget();
                var snapshot =
                        new EffectsSnapshot(
                                budget,
                                EffectsFixtures.endpoint("A"),
                                EffectsFixtures.endpoint("B"),
                                Cancellation.NONE)) {
            var spool =
                    BudgetedTempFile.create(
                            directory, "blocked-", budget, "authored", Cancellation.NONE);
            spool.appendFrame(new byte[] {1, 2, 3}, Cancellation.NONE);
            spool.seal();
            long charged = spool.size();
            Path path = spool.path();
            Files.delete(path);
            Files.createDirectory(path);
            Path blocker = Files.writeString(path.resolve("blocker"), "authored deletion refusal");
            try {
                var failure =
                        assertThrows(
                                Exception.class,
                                () ->
                                        EffectsStaging.sortSpool(
                                                spool,
                                                Side.A,
                                                snapshot,
                                                directory,
                                                budget,
                                                Cancellation.NONE,
                                                EffectsStaging.StageOrder.LINK));
                assertTrue(failure.getSuppressed().length > 0);
                assertEquals(charged, budget.snapshot().disk());
            } finally {
                Files.deleteIfExists(blocker);
                Files.deleteIfExists(path);
                spool.close();
            }
            assertEquals(0, budget.snapshot().disk());
        }
    }
}
