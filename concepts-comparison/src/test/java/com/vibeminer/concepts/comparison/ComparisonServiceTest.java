package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Aspect;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.Difference;
import com.vibeminer.concepts.comparison.ComparisonTypes.Field;
import com.vibeminer.concepts.comparison.ComparisonTypes.InputPage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Membership;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.ComparisonTypes.RunState;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueKind;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ComparisonServiceTest {
    @TempDir Path temporary;

    @Test
    void mergesOrderedPagesAndClassifiesOnlyCertifiedAbsence() throws Exception {
        var a = new Input("a", Completeness.COMPLETE, List.of(row("01", "same"), row("03", "old")));
        var b =
                new Input(
                        "b",
                        Completeness.COMPLETE,
                        List.of(row("01", "same"), row("02", "new"), row("03", "changed")));
        try (var service = new ComparisonService(temporary)) {
            assertEquals(RunState.COMPLETED, service.start(a, b).join().state());
            var page = service.page(0, 100).join();
            assertEquals(
                    List.of("02", "03"),
                    page.rows().stream().map(ComparisonTypes.Result::key).toList());
            assertEquals(Membership.ADDED, page.rows().get(0).membership());
            assertEquals(Difference.CHANGED, page.rows().get(1).fields().getFirst().difference());
        }
        assertTrue(a.closed);
        assertTrue(b.closed);
        try (var paths = Files.list(temporary)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void partialExhaustionIsObservedOnlyAndUnknownIsNotEmpty() throws Exception {
        var a = new Input("a", Completeness.PARTIAL, List.of(row("01", "old")));
        var b = new Input("b", Completeness.UNKNOWN, List.of(row("02", "new")));
        try (var service = new ComparisonService(temporary)) {
            service.start(a, b).join();
            var rows = service.page(0, 100).join().rows();
            assertEquals(Membership.OBSERVED_ONLY_A, rows.get(0).membership());
            assertEquals(Membership.OBSERVED_ONLY_B, rows.get(1).membership());
        }
    }

    @Test
    void unknownAndChangedFactsCoexistWithoutInventingFieldRemoval() throws Exception {
        Field unknown =
                new Field(
                        "name",
                        ValueKind.TEXT,
                        ValueState.PROTECTED,
                        null,
                        Aspect.CONTENT,
                        List.of());
        var a =
                new Input(
                        "a",
                        Completeness.COMPLETE,
                        List.of(
                                new Record(
                                        "01",
                                        Map.of(
                                                "known",
                                                field("old"),
                                                "unknown",
                                                unknown,
                                                "extra",
                                                field("x")),
                                        List.of())));
        var b =
                new Input(
                        "b",
                        Completeness.COMPLETE,
                        List.of(
                                new Record(
                                        "01",
                                        Map.of("known", field("new"), "unknown", field("")),
                                        List.of())));
        try (var service = new ComparisonService(temporary)) {
            service.start(a, b).join();
            var fields = service.page(0, 1).join().rows().getFirst().fields();
            assertEquals(
                    List.of(Difference.UNKNOWN, Difference.CHANGED, Difference.UNKNOWN),
                    fields.stream().map(ComparisonTypes.FieldDelta::difference).toList());
        }
    }

    @Test
    void semanticsChangeIsIncomparableAndAvailabilityKeepsOwnAspect() throws Exception {
        var a = new Record("1", Map.of("value", field("a")), List.of());
        var changed =
                new Field(
                        "different-semantics",
                        ValueKind.TEXT,
                        ValueState.VALUE,
                        "a",
                        Aspect.INTERPRETATION,
                        List.of());
        var b = new Record("1", Map.of("value", changed), List.of());
        try (var service = new ComparisonService(temporary)) {
            service.start(
                            new Input("a", Completeness.COMPLETE, List.of(a)),
                            new Input("b", Completeness.COMPLETE, List.of(b)))
                    .join();
            assertEquals(
                    Difference.INCOMPARABLE,
                    service.page(0, 1).join().rows().getFirst().fields().getFirst().difference());
        }
    }

    @Test
    void keyAndStagingLimitsDowngradeCoverageAndDoNotClaimRemoval() throws Exception {
        for (boolean keyLimit : new boolean[] {true, false}) {
            try (var service =
                    new ComparisonService(temporary, keyLimit ? 1 : 100, keyLimit ? 100000 : 100)) {
                var progress =
                        service.start(
                                        new Input(
                                                "a",
                                                Completeness.COMPLETE,
                                                List.of(row("1", "a"), row("2", "b"))),
                                        new Input("b", Completeness.COMPLETE, List.of()))
                                .join();
                assertEquals(RunState.LIMITED, progress.state());
                assertEquals(Completeness.PARTIAL, progress.a().completeness());
                for (var row : service.page(0, 100).join().rows()) {
                    assertEquals(Membership.OBSERVED_ONLY_A, row.membership());
                }
            }
        }
    }

    @Test
    void oversizedFieldBecomesLimitedWhileSmallFactsSurvive() throws Exception {
        var big =
                new Record(
                        "1",
                        Map.of("big", field("x".repeat(1048576)), "small", field("yes")),
                        List.of());
        try (var service = new ComparisonService(temporary)) {
            service.start(
                            new Input("a", Completeness.COMPLETE, List.of(big)),
                            new Input("b", Completeness.COMPLETE, List.of(big)))
                    .join();
            var result = service.page(0, 1).join().rows().getFirst();
            assertEquals(ValueState.LIMITED, result.a().fields().get("big").state());
            assertEquals("yes", result.a().fields().get("small").value());
            assertEquals(Difference.UNKNOWN, result.fields().getFirst().difference());
        }
    }

    @Test
    void rejectsDuplicateUnsortedAndChangedIdentityPages() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int invalid = mode;
            var bad =
                    new Input(
                            "a",
                            Completeness.COMPLETE,
                            List.of(row("2", "a"), row(mode == 0 ? "2" : "1", "b"))) {
                        @Override
                        public CompletableFuture<InputPage> read(
                                String cursor, int limit, Cancellation token) {
                            if (invalid == 2) {
                                return CompletableFuture.completedFuture(
                                        new InputPage(
                                                "changed",
                                                List.of(),
                                                null,
                                                new Coverage(
                                                        Completeness.COMPLETE,
                                                        "scope",
                                                        List.of())));
                            }
                            return super.read(cursor, limit, token);
                        }
                    };
            try (var service = new ComparisonService(temporary)) {
                assertEquals(
                        RunState.FAILED,
                        service.start(bad, new Input("b", Completeness.COMPLETE, List.of()))
                                .join()
                                .state());
            }
        }
    }

    @Test
    void paginationCrossesSparseCheckpointAndAllRowsAreImmutable() throws Exception {
        var rows = new ArrayList<Record>();
        for (int i = 0; i < 257; i++) {
            rows.add(row(String.format("%04d", i), "v"));
        }
        try (var service = new ComparisonService(temporary)) {
            service.start(
                            new Input("a", Completeness.COMPLETE, rows),
                            new Input(
                                    "b",
                                    Completeness.COMPLETE,
                                    rows.stream().map(r -> row(r.key(), "changed")).toList()))
                    .join();
            var page = service.page(99, 100).join();
            assertEquals(100, page.rows().size());
            assertEquals("0099", page.rows().getFirst().key());
            assertEquals(199L, page.nextOffset());
            assertEquals("0256", service.page(256, 100).join().rows().getFirst().key());
            assertThrows(UnsupportedOperationException.class, () -> page.rows().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> page.rows().getFirst().a().fields().clear());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.page(0, 101).join());
        }
    }

    @Test
    void twoInitialReadsStartBeforeEitherCompletesAndCloseDrainsBeforeAdapters() throws Exception {
        var requested = new CountDownLatch(2);
        var release = new CompletableFuture<InputPage>();
        var inputs = new ArrayList<Input>();
        for (String identity : List.of("a", "b")) {
            inputs.add(
                    new Input(identity, Completeness.COMPLETE, List.of()) {
                        @Override
                        public CompletableFuture<InputPage> read(
                                String cursor, int limit, Cancellation token) {
                            requested.countDown();
                            return release.thenApply(
                                    ignored ->
                                            new InputPage(
                                                    identity,
                                                    List.of(),
                                                    null,
                                                    new Coverage(
                                                            Completeness.COMPLETE,
                                                            "scope",
                                                            List.of())));
                        }
                    });
        }
        var service = new ComparisonService(temporary);
        var run = service.start(inputs.get(0), inputs.get(1));
        assertTrue(requested.await(5, TimeUnit.SECONDS));
        var closing = service.closeAsync();
        assertFalse(closing.isDone());
        assertFalse(inputs.getFirst().closed);
        release.complete(
                new InputPage(
                        "unused",
                        List.of(),
                        null,
                        new Coverage(Completeness.COMPLETE, "scope", List.of())));
        closing.get(5, TimeUnit.SECONDS);
        assertTrue(inputs.stream().allMatch(input -> input.closed));
        assertNotNull(run.join());
        assertEquals(RunState.CLOSED, service.progress().state());
    }

    @Test
    void canceledFailureCannotCertifyAbsenceAndSingleUseIsEnforced() throws Exception {
        try (var service = new ComparisonService(temporary)) {
            service.cancel();
            var input = new Input("a", Completeness.COMPLETE, List.of());
            assertEquals(
                    RunState.CANCELED,
                    service.start(input, new Input("b", Completeness.COMPLETE, List.of()))
                            .join()
                            .state());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.start(input, input).join());
        }
    }

    @Test
    void queuedPageCommandsAreBoundedAndCloseCannotBeRejected() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CompletableFuture<InputPage>();
        var inputs = new ArrayList<Input>();
        for (String identity : List.of("a", "b")) {
            inputs.add(
                    new Input(identity, Completeness.COMPLETE, List.of()) {
                        @Override
                        public CompletableFuture<InputPage> read(
                                String cursor, int limit, Cancellation token) {
                            entered.countDown();
                            return release.thenApply(
                                    ignored ->
                                            new InputPage(
                                                    identity,
                                                    List.of(),
                                                    null,
                                                    new Coverage(
                                                            Completeness.COMPLETE,
                                                            "scope",
                                                            List.of())));
                        }
                    });
        }
        var service = new ComparisonService(temporary);
        service.start(inputs.get(0), inputs.get(1));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var pages = new ArrayList<CompletableFuture<ComparisonTypes.ResultPage>>();
        for (int i = 0; i < 8; i++) {
            pages.add(service.page(0, 1));
        }
        assertThrows(
                java.util.concurrent.CompletionException.class, () -> service.page(0, 1).join());
        var close = service.closeAsync();
        release.complete(
                new InputPage(
                        "release",
                        List.of(),
                        null,
                        new Coverage(Completeness.COMPLETE, "scope", List.of())));
        close.get(5, TimeUnit.SECONDS);
        assertTrue(pages.stream().allMatch(CompletableFuture::isDone));
    }

    @Test
    void oversizedInputPageProducesTypedLimitRatherThanFalseCompletion() throws Exception {
        var rows = new ArrayList<Record>();
        for (int i = 0; i < 256; i++) {
            rows.add(row(String.format("%04d", i), "x".repeat(20000)));
        }
        try (var service = new ComparisonService(temporary)) {
            var progress =
                    service.start(
                                    new Input("a", Completeness.COMPLETE, rows),
                                    new Input("b", Completeness.COMPLETE, List.of()))
                            .join();
            assertEquals(RunState.LIMITED, progress.state());
            assertEquals(Completeness.PARTIAL, progress.b().completeness());
        }
    }

    @Test
    void incompatibleEndpointsAreRejectedBeforeSourceReads() throws Exception {
        var bad =
                new Input("a", Completeness.COMPLETE, List.of()) {
                    @Override
                    public Descriptor descriptor() {
                        return new Descriptor("a", "wow", "enUS", "files", "scope", Map.of());
                    }

                    @Override
                    public CompletableFuture<InputPage> read(
                            String cursor, int limit, Cancellation token) {
                        throw new AssertionError("Must not read incompatible source");
                    }
                };
        try (var service = new ComparisonService(temporary)) {
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            service.start(bad, new Input("b", Completeness.COMPLETE, List.of()))
                                    .join());
        }
        assertFalse(bad.closed);
    }

    @Test
    void manyKnownEqualRowsDoNotFillStagingAndSparseFindingsRemainPageable() throws Exception {
        var left = new ArrayList<Record>();
        var right = new ArrayList<Record>();
        for (int i = 0; i < 10000; i++) {
            String key = String.format("%08d", i);
            left.add(row(key, "equal"));
            right.add(row(key, i == 1 || i == 9998 ? "changed" : "equal"));
        }
        try (var service = new ComparisonService(temporary, 10000, 4096)) {
            var progress =
                    service.start(
                                    new Input("a", Completeness.COMPLETE, left),
                                    new Input("b", Completeness.COMPLETE, right))
                            .join();
            assertEquals(RunState.COMPLETED, progress.state());
            assertEquals(10000, progress.observed());
            var page = service.page(0, 1).join();
            assertEquals("00000001", page.rows().getFirst().key());
            assertEquals(1L, page.nextOffset());
            var last = service.page(1, 100).join();
            assertEquals("00009998", last.rows().getFirst().key());
            assertEquals(null, last.nextOffset());
        }
    }

    @Test
    void resultByteBudgetShortensPagesWithoutLosingFindings() throws Exception {
        var left = new ArrayList<Record>();
        var right = new ArrayList<Record>();
        for (int i = 0; i < 100; i++) {
            String key = String.format("%04d", i);
            left.add(row(key, "a".repeat(20000)));
            right.add(row(key, "b".repeat(20000)));
        }
        var a =
                new Input("a", Completeness.COMPLETE, left) {
                    @Override
                    public CompletableFuture<InputPage> read(
                            String cursor, int limit, Cancellation token) {
                        return super.read(cursor, Math.min(50, limit), token);
                    }
                };
        var b =
                new Input("b", Completeness.COMPLETE, right) {
                    @Override
                    public CompletableFuture<InputPage> read(
                            String cursor, int limit, Cancellation token) {
                        return super.read(cursor, Math.min(50, limit), token);
                    }
                };
        try (var service = new ComparisonService(temporary)) {
            assertEquals(RunState.COMPLETED, service.start(a, b).join().state());
            var first = service.page(0, 100).join();
            assertTrue(first.rows().size() < 100);
            assertNotNull(first.nextOffset());
            int total = first.rows().size();
            Long next = first.nextOffset();
            while (next != null) {
                var page = service.page(next, 100).join();
                total += page.rows().size();
                next = page.nextOffset();
            }
            assertEquals(100, total);
        }
    }

    @Test
    void certifiedOppositeEmptyScopeConfirmsRemoval() throws Exception {
        try (var service = new ComparisonService(temporary)) {
            service.start(
                            new Input("a", Completeness.COMPLETE, List.of(row("1", "known"))),
                            new Input("b", Completeness.COMPLETE, List.of()))
                    .join();
            assertEquals(
                    Membership.REMOVED, service.page(0, 1).join().rows().getFirst().membership());
        }
    }

    @Test
    void adapterCloseFailureStillClosesOtherInputAndRemovesStaging() throws Exception {
        var bad =
                new Input("a", Completeness.COMPLETE, List.of(row("1", "a"))) {
                    @Override
                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("Authored close failure"));
                    }
                };
        var other = new Input("b", Completeness.COMPLETE, List.of());
        var service = new ComparisonService(temporary);
        service.start(bad, other).join();
        assertThrows(
                java.util.concurrent.CompletionException.class, () -> service.closeAsync().join());
        assertTrue(other.closed);
        try (var paths = Files.list(temporary)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void exhaustedCanonicalPagesAreReleasedBeforeReplacementReadAndAllocation() throws Exception {
        var first = new ArrayList<Record>();
        var other = new ArrayList<Record>();
        var second = new ArrayList<Record>();
        String value = "x".repeat(25000);
        for (int i = 0; i < 128; i++) {
            first.add(row(String.format("%04d", i), value));
            other.add(row(String.format("%04d", 128 + i), value));
            second.add(row(String.format("%04d", 256 + i), value));
        }
        try (var service = new ComparisonService(temporary)) {
            var a =
                    new Input("a", Completeness.COMPLETE, List.of()) {
                        @Override
                        public CompletableFuture<InputPage> read(
                                String cursor, int limit, Cancellation token) {
                            if (cursor != null) {
                                assertTrue(
                                        service.retainedInputBytes() < 14L * 1048576,
                                        "Exhausted page retained during replacement read");
                            }
                            return CompletableFuture.completedFuture(
                                    new InputPage(
                                            "a",
                                            cursor == null ? first : second,
                                            cursor == null ? "next" : null,
                                            new Coverage(
                                                    cursor == null
                                                            ? Completeness.UNKNOWN
                                                            : Completeness.COMPLETE,
                                                    "scope",
                                                    List.of())));
                        }
                    };
            assertEquals(
                    RunState.COMPLETED,
                    service.start(a, new Input("b", Completeness.COMPLETE, other)).join().state());
            assertTrue(
                    service.peakRetainedInputBytes()
                            <= ComparisonService.BUFFER_BYTES
                                    - 4L * ComparisonService.RECORD_BYTES);
            assertEquals(0, service.retainedInputBytes());
        }
    }

    @Test
    void preStartPagesRejectImmediatelyWithoutConsumingAdmissionQueue() throws Exception {
        try (var service = new ComparisonService(temporary)) {
            for (int i = 0; i < 100; i++) {
                assertTrue(service.page(0, 1).isCompletedExceptionally());
            }
            assertEquals(
                    RunState.COMPLETED,
                    service.start(
                                    new Input("a", Completeness.COMPLETE, List.of()),
                                    new Input("b", Completeness.COMPLETE, List.of()))
                            .join()
                            .state());
        }
    }

    @Test
    void rejectedExecutorAdmissionDoesNotTakeInputOwnershipOrStayRunning() throws Exception {
        var executor = ComparisonService.newWorker();
        executor.shutdown();
        var a = new Input("a", Completeness.COMPLETE, List.of());
        var b = new Input("b", Completeness.COMPLETE, List.of());
        try (var service = new ComparisonService(temporary, 100, 4096, executor)) {
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.start(a, b).join());
            assertEquals(RunState.FAILED, service.progress().state());
            assertTrue(service.page(0, 1).isCompletedExceptionally());
        }
        assertFalse(a.closed);
        assertFalse(b.closed);
    }

    @Test
    void changedWinningSourceIsExplicitEvidenceFieldAndRemainsFinding() throws Exception {
        var oldWinner =
                new Field(
                        "name-winning-provider-v1",
                        ValueKind.TEXT,
                        ValueState.VALUE,
                        "QUESTIE",
                        Aspect.EXTERNAL_EVIDENCE,
                        List.of());
        var newWinner =
                new Field(
                        "name-winning-provider-v1",
                        ValueKind.TEXT,
                        ValueState.VALUE,
                        "EG_LINK",
                        Aspect.EXTERNAL_EVIDENCE,
                        List.of());
        var a = new Record("1", Map.of("name", field("same"), "winner", oldWinner), List.of());
        var b = new Record("1", Map.of("name", field("same"), "winner", newWinner), List.of());
        try (var service = new ComparisonService(temporary)) {
            service.start(
                            new Input("a", Completeness.COMPLETE, List.of(a)),
                            new Input("b", Completeness.COMPLETE, List.of(b)))
                    .join();
            var finding = service.page(0, 1).join().rows().getFirst();
            assertEquals(Difference.EQUAL, finding.fields().getFirst().difference());
            assertEquals(Difference.CHANGED, finding.fields().get(1).difference());
            assertEquals(Aspect.EXTERNAL_EVIDENCE, finding.fields().get(1).aspect());
        }
    }

    @Test
    void endpointOnlyEvidenceAnnotationsDoNotFillStaging() throws Exception {
        var a =
                new Record(
                        "1",
                        Map.of("name", field("same")),
                        List.of(new ComparisonTypes.Evidence("table", "endpoint-a", null, "same")));
        var b =
                new Record(
                        "1",
                        Map.of("name", field("same")),
                        List.of(new ComparisonTypes.Evidence("table", "endpoint-b", null, "same")));
        try (var service = new ComparisonService(temporary)) {
            var progress =
                    service.start(
                                    new Input("a", Completeness.COMPLETE, List.of(a)),
                                    new Input("b", Completeness.COMPLETE, List.of(b)))
                            .join();
            assertEquals(1, progress.observed());
            assertEquals(0, progress.stagedBytes());
            assertTrue(service.page(0, 1).join().rows().isEmpty());
        }
    }

    private static Field field(String value) {
        return new Field(
                "name", ValueKind.TEXT, ValueState.VALUE, value, Aspect.CONTENT, List.of());
    }

    private static Record row(String key, String value) {
        return new Record(key, Map.of("name", field(value)), List.of());
    }

    private static class Input implements ComparisonInput {
        private final String identity;
        private final Completeness coverage;
        private final List<Record> rows;
        volatile boolean closed;

        Input(String identity, Completeness coverage, List<Record> rows) {
            this.identity = identity;
            this.coverage = coverage;
            this.rows = List.copyOf(rows);
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(
                    identity, "wowf", "enUS", "files", "scope", Map.of("revision", "1"));
        }

        @Override
        public CompletableFuture<InputPage> read(String cursor, int limit, Cancellation token) {
            token.check();
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(rows.size(), from + limit);
            return CompletableFuture.completedFuture(
                    new InputPage(
                            identity,
                            rows.subList(from, to),
                            to < rows.size() ? Integer.toString(to) : null,
                            new Coverage(
                                    to == rows.size() ? coverage : Completeness.UNKNOWN,
                                    "scope",
                                    List.of())));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }
}
