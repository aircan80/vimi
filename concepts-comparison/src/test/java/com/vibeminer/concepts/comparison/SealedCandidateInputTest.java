package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.vibeminer.concepts.comparison.ComparisonTypes.Membership;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.ComparisonTypes.RunState;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueKind;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class SealedCandidateInputTest {
    @TempDir Path directory;

    private static Descriptor descriptor(String side) {
        return new Descriptor(side, "wowf", "enUS", "items", "observed-items", Map.of());
    }

    private static Record row(int id, String value) {
        return new Record(
                String.format("%020d", id),
                Map.of(
                        "value",
                        new Field(
                                "candidate-text-v1",
                                ValueKind.TEXT,
                                ValueState.VALUE,
                                value,
                                Aspect.CONTENT,
                                List.of())),
                List.of());
    }

    private BudgetedTempFile file(TempBudget budget, List<Record> records) throws Exception {
        var file =
                BudgetedTempFile.create(
                        directory, "candidate", budget, "candidate", Cancellation.NONE);
        for (var record : records) {
            file.appendFrame(ComparisonCodec.pair(record, null), Cancellation.NONE);
        }
        return file;
    }

    private SealedCandidateInput input(
            TempBudget budget, List<Record> records, Function<Record, Record> projector)
            throws Exception {
        return SealedCandidateInput.adopt(
                file(budget, records), descriptor("a"), budget, projector);
    }

    private SortedComparisonInput sortedDependency(TempBudget budget, String side, boolean sparse)
            throws Exception {
        var source =
                new SortedComparisonInput.Source() {
                    public Descriptor descriptor() {
                        return SealedCandidateInputTest.descriptor(side);
                    }

                    public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                            String cursor, int maxRows, long maxBytes, Cancellation token) {
                        int at = cursor == null ? 449 : Integer.parseInt(cursor);
                        long bytes = 4096;
                        var rows = new ArrayList<Record>();
                        while (at >= 0 && rows.size() < maxRows) {
                            token.check();
                            int missing = sparse ? 1 : 0;
                            if (at % 3 == missing) {
                                at--;
                                continue;
                            }
                            // Reserve by conservative known shape before constructing each record.
                            long planned = 65536;
                            if (bytes + planned > maxBytes) {
                                break;
                            }
                            rows.add(row(at--, (side + (sparse ? "s" : "i")).repeat(5000)));
                            bytes += planned;
                        }
                        return CompletableFuture.completedFuture(
                                new SortedComparisonInput.UnsortedPage(
                                        side,
                                        rows,
                                        at < 0 ? null : Integer.toString(at),
                                        new Coverage(
                                                Completeness.UNKNOWN,
                                                "observed-items",
                                                List.of())));
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        return SortedComparisonInput.capture(source, directory, budget, Cancellation.NONE).join();
    }

    private static Record union(Record item, Record sparse) {
        var fields = new LinkedHashMap<String, Field>();
        for (boolean s : List.of(false, true)) {
            var record = s ? sparse : item;
            fields.put(
                    s ? "sparse" : "item",
                    record == null
                            ? new Field(
                                    "candidate-text-v1",
                                    ValueKind.TEXT,
                                    ValueState.UNKNOWN,
                                    null,
                                    Aspect.CONTENT,
                                    List.of())
                            : record.fields().get("value"));
        }
        return new Record(item != null ? item.key() : sparse.key(), fields, List.of());
    }

    @Test
    void actualSortUnionCandidateAndCoreRetainMetadataThroughAllByteShortenedPages()
            throws Exception {
        try (var budget = new TempBudget();
                var metadata = budget.reserveMemory("snapshot", 1L << 20, Cancellation.NONE)) {
            var aFile =
                    StreamingItemJoin.join(
                            sortedDependency(budget, "a", false),
                            sortedDependency(budget, "a", true),
                            directory,
                            budget,
                            SealedCandidateInputTest::union,
                            Cancellation.NONE);
            var bFile =
                    StreamingItemJoin.join(
                            sortedDependency(budget, "b", false),
                            sortedDependency(budget, "b", true),
                            directory,
                            budget,
                            SealedCandidateInputTest::union,
                            Cancellation.NONE);
            var a =
                    SealedCandidateInput.adopt(
                            aFile,
                            descriptor("a"),
                            budget,
                            value ->
                                    new Record(
                                            value.key(),
                                            new LinkedHashMap<>(value.fields()),
                                            List.of()));
            var b =
                    SealedCandidateInput.adopt(
                            bFile,
                            descriptor("b"),
                            budget,
                            value ->
                                    new Record(
                                            value.key(),
                                            new LinkedHashMap<>(value.fields()),
                                            List.of()));
            try (var service =
                    new ComparisonService(
                            directory,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                var progress = service.start(a, b).join();
                assertEquals(RunState.COMPLETED, progress.state());
                assertEquals(450, progress.observed());
                assertEquals(Completeness.UNKNOWN, progress.a().completeness());
                assertEquals(Completeness.UNKNOWN, progress.b().completeness());
                assertEquals(29L << 20, budget.snapshot().peakMemory());
                long offset = 0;
                int seen = 0, pages = 0;
                boolean shortPage = false, unknown = false;
                do {
                    var page = service.page(offset, 100).join();
                    pages++;
                    shortPage |= page.rows().size() < 100 && page.nextOffset() != null;
                    assertEquals(1L << 20, metadata.size());
                    for (var finding : page.rows()) {
                        int id = seen++;
                        assertEquals(row(id, "").key(), finding.key());
                        assertEquals(Membership.BOTH, finding.membership());
                        assertTrue(
                                finding.fields().stream()
                                        .anyMatch(
                                                delta -> delta.difference() == Difference.CHANGED));
                        unknown |=
                                finding.fields().stream()
                                        .anyMatch(
                                                delta -> delta.difference() == Difference.UNKNOWN);
                        for (boolean s : List.of(false, true)) {
                            String field = s ? "sparse" : "item";
                            boolean absent = id % 3 == (s ? 1 : 0);
                            var left = finding.a().fields().get(field);
                            var right = finding.b().fields().get(field);
                            assertEquals(
                                    absent ? ValueState.UNKNOWN : ValueState.VALUE, left.state());
                            assertEquals(
                                    absent ? null : ("a" + (s ? "s" : "i")).repeat(5000),
                                    left.value());
                            assertEquals(
                                    absent ? null : ("b" + (s ? "s" : "i")).repeat(5000),
                                    right.value());
                        }
                    }
                    if (page.nextOffset() == null) {
                        break;
                    }
                    offset = page.nextOffset();
                } while (true);
                assertEquals(450, seen);
                assertTrue(pages > 1 && shortPage && unknown);
                assertEquals((31L << 20) + (1L << 19), budget.snapshot().peakMemory());
            }
            assertEquals(1L << 20, budget.snapshot().memory());
            metadata.close();
            drained(budget);
        }
    }

    private void drained(TempBudget budget) throws Exception {
        assertEquals(0, budget.snapshot().memory());
        assertEquals(0, budget.snapshot().disk());
        assertEquals(0, budget.snapshot().reservations());
        try (var paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void actualDecodeProjectionAndShortPagesRewindWithoutLosingOrderedRows() throws Exception {
        try (var budget = new TempBudget()) {
            var source = new ArrayList<Record>();
            for (int i = 0; i < 120; i++) {
                source.add(row(i, "x".repeat(10000)));
            }
            var calls = new AtomicInteger();
            var input =
                    input(
                            budget,
                            source,
                            stage -> {
                                calls.incrementAndGet();
                                return row(
                                        Integer.parseInt(stage.key()),
                                        stage.fields().get("value").value() + "!");
                            });
            int seen = 0, pages = 0;
            String cursor = null;
            do {
                var page = input.read(cursor, 256, Cancellation.NONE).get(5, TimeUnit.SECONDS);
                pages++;
                assertEquals(Completeness.UNKNOWN, page.coverage().completeness());
                assertTrue(page.rows().size() < 120);
                for (var value : page.rows()) {
                    assertEquals(row(seen++, "x".repeat(10000) + "!"), value);
                }
                cursor = page.nextCursor();
            } while (cursor != null);
            assertEquals(120, seen);
            assertTrue(pages > 1);
            assertTrue(calls.get() > seen, "Nonfitting row is re-decoded, not retained pending");
            input.closeAsync().join();
            drained(budget);
        }
    }

    @Test
    void finalMaskCanExcludeIdentitiesWithoutEmptyIntermediatePagesOrFabricatedRecords()
            throws Exception {
        try (var budget = new TempBudget()) {
            var source = new ArrayList<Record>();
            for (int i = 0; i < 520; i++) {
                source.add(row(i, "candidate"));
            }
            var calls = new AtomicInteger();
            var input =
                    input(
                            budget,
                            source,
                            stage -> {
                                calls.incrementAndGet();
                                return Integer.parseInt(stage.key()) >= 510 ? stage : null;
                            });
            var first = input.read(null, 5, Cancellation.NONE).join();
            assertEquals(5, first.rows().size());
            assertEquals(row(510, "candidate"), first.rows().getFirst());
            assertNotNull(first.nextCursor());
            var end = input.read(first.nextCursor(), 256, Cancellation.NONE).join();
            assertEquals(5, end.rows().size());
            assertEquals(row(519, "candidate"), end.rows().getLast());
            assertEquals(null, end.nextCursor());
            assertEquals(520, calls.get());
            input.closeAsync().join();
            var empty = input(budget, List.of(row(1, "discard")), stage -> null);
            var page = empty.read(null, 1, Cancellation.NONE).join();
            assertTrue(page.rows().isEmpty());
            assertEquals(null, page.nextCursor());
            assertEquals(Completeness.UNKNOWN, page.coverage().completeness());
            empty.closeAsync().join();
            drained(budget);
        }
    }

    @Test
    void realTwelveMiBInputsComposeWithCoreAndMetadataThroughResultPaging() throws Exception {
        try (var budget = new TempBudget();
                var metadata = budget.reserveMemory("snapshot", 1L << 20, Cancellation.NONE)) {
            var left = new ArrayList<Record>();
            var right = new ArrayList<Record>();
            for (int i = 0; i < 180; i++) {
                left.add(row(i, "a".repeat(10000)));
                right.add(row(i, "b".repeat(10000)));
            }
            var a = SealedCandidateInput.adopt(file(budget, left), descriptor("a"), budget, v -> v);
            var b =
                    SealedCandidateInput.adopt(
                            file(budget, right), descriptor("b"), budget, v -> v);
            try (var service =
                    new ComparisonService(
                            directory,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                var progress = service.start(a, b).join();
                assertEquals(RunState.COMPLETED, progress.state());
                assertEquals(180, progress.observed());
                long offset = 0;
                int count = 0, pages = 0;
                while (true) {
                    var page = service.page(offset, 100).join();
                    assertEquals(1L << 20, metadata.size());
                    pages++;
                    for (var result : page.rows()) {
                        assertEquals(row(count, "a".repeat(10000)), result.a());
                        assertEquals(row(count++, "b".repeat(10000)), result.b());
                        assertEquals(Membership.BOTH, result.membership());
                        assertEquals(Difference.CHANGED, result.fields().getFirst().difference());
                    }
                    if (page.nextOffset() == null) {
                        break;
                    }
                    offset = page.nextOffset();
                }
                assertEquals(180, count);
                assertTrue(pages > 1);
                assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
            }
            assertEquals(1L << 20, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
            metadata.close();
            drained(budget);
        }
    }

    @Test
    void allowanceMustBeLiveSameBudgetMemoryAndAtLeastTwelveMiB() throws Exception {
        try (var budget = new TempBudget();
                var wrong = new TempBudget()) {
            var input = input(budget, List.of(row(1, "a")), v -> v);
            try (var small = budget.reserveMemory("small", (12L << 20) - 1, Cancellation.NONE);
                    var disk = budget.reserveDisk("disk", 12L << 20, Cancellation.NONE);
                    var other = wrong.reserveMemory("wrong", 12L << 20, Cancellation.NONE)) {
                for (var allowance : List.of(small, disk, other)) {
                    assertThrows(
                            CompletionException.class,
                            () -> input.read(null, 1, Cancellation.NONE, allowance).join());
                }
            }
            var released = budget.reserveMemory("released", 12L << 20, Cancellation.NONE);
            released.close();
            assertThrows(
                    CompletionException.class,
                    () -> input.read(null, 1, Cancellation.NONE, released).join());
            try (var valid = budget.reserveMemory("core-page", 12L << 20, Cancellation.NONE)) {
                assertEquals(1, input.read(null, 1, Cancellation.NONE, valid).join().rows().size());
                assertEquals(12L << 20, valid.size(), "Input does not release borrowed allowance");
            }
            input.closeAsync().join();
            drained(budget);
        }
    }

    @Test
    void malformedOversizedAndPreflightLimitedFramesNeverInvokeProjector() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            try (var budget = new TempBudget()) {
                var file =
                        BudgetedTempFile.create(
                                directory, "invalid", budget, "invalid", Cancellation.NONE);
                if (scenario == 0) {
                    file.appendFrame(new byte[] {1, 0}, Cancellation.NONE);
                } else if (scenario == 1) {
                    file.appendFrame(
                            ComparisonCodec.pair(row(1, "x".repeat(300000)), null),
                            Cancellation.NONE);
                } else if (scenario == 4) {
                    byte[] frame = ComparisonCodec.pair(row(1, "\u00e9"), null);
                    for (int at = 0; at < frame.length; at++) {
                        if ((frame[at] & 255) == 195) {
                            frame[at] = (byte) 255;
                            break;
                        }
                    }
                    file.appendFrame(frame, Cancellation.NONE);
                } else {
                    file.appendFrame(ComparisonCodec.pair(row(1, "a"), null), Cancellation.NONE);
                }
                file.seal();
                if (scenario == 2 || scenario == 3) {
                    try (var raw = new RandomAccessFile(file.path().toFile(), "rw")) {
                        raw.writeInt(scenario == 2 ? ComparisonService.RECORD_BYTES + 1 : 0);
                    }
                }
                var calls = new AtomicInteger();
                var input =
                        SealedCandidateInput.adopt(
                                file,
                                descriptor("a"),
                                budget,
                                value -> {
                                    calls.incrementAndGet();
                                    return value;
                                });
                var failure =
                        assertThrows(
                                CompletionException.class,
                                () -> input.read(null, 1, Cancellation.NONE).join());
                if (scenario == 1) {
                    assertInstanceOf(ComparisonLimit.class, failure.getCause());
                }
                assertEquals(0, calls.get());
                input.closeAsync().join();
                drained(budget);
            }
        }
    }

    @Test
    void identityProjectionAndOrderingViolationsFailWithoutRetainingInput() throws Exception {
        try (var budget = new TempBudget()) {
            var changed = input(budget, List.of(row(1, "a")), ignored -> row(2, "b"));
            assertThrows(
                    CompletionException.class,
                    () -> changed.read(null, 1, Cancellation.NONE).join());
            changed.closeAsync().join();
            var duplicate = input(budget, List.of(row(1, "a"), row(1, "b")), v -> v);
            assertThrows(
                    CompletionException.class,
                    () -> duplicate.read(null, 256, Cancellation.NONE).join());
            duplicate.closeAsync().join();
            drained(budget);
        }
    }

    @Test
    void adoptionRejectsWrongBudgetAndFailedFileWithoutTakingOwnership() throws Exception {
        try (var budget = new TempBudget();
                var other = new TempBudget()) {
            var file = file(budget, List.of(row(1, "a")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SealedCandidateInput.adopt(file, descriptor("a"), other, v -> v));
            assertTrue(Files.exists(file.path()));
            file.close();
            var failed =
                    BudgetedTempFile.create(
                            directory,
                            "failed",
                            budget,
                            "failed",
                            Cancellation.NONE,
                            (channel, header, body, token) -> {
                                while (header.hasRemaining()) {
                                    channel.write(header);
                                }
                                while (body.hasRemaining()) {
                                    channel.write(body);
                                }
                                throw new java.io.IOException("Complete write reported failure");
                            });
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            failed.appendFrame(
                                    ComparisonCodec.pair(row(1, "a"), null), Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SealedCandidateInput.adopt(failed, descriptor("a"), budget, v -> v));
            assertTrue(Files.exists(failed.path()));
            failed.close();
            drained(budget);
        }
    }

    @Test
    void projectedRecordLimitsCoverFirstRowRetainedSizeAndUtf8FrameWithoutEncodingCopy()
            throws Exception {
        for (boolean wireLimit : List.of(false, true)) {
            try (var budget = new TempBudget()) {
                var input =
                        input(
                                budget,
                                List.of(row(1, "small")),
                                value ->
                                        row(
                                                1,
                                                wireLimit
                                                        ? "\u0800".repeat(400000)
                                                        : "x".repeat(600000)));
                var failure =
                        assertThrows(
                                CompletionException.class,
                                () -> input.read(null, 1, Cancellation.NONE).join());
                assertInstanceOf(ComparisonLimit.class, failure.getCause());
                input.closeAsync().join();
                drained(budget);
            }
        }
    }

    @Test
    void heldDeliveryRemainsChargedAndCancellationOrCloseDropsPrivatePage() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            try (var budget = new TempBudget()) {
                var input = input(budget, List.of(row(1, "held")), v -> v);
                var queue = new ArrayBlockingQueue<Runnable>(1);
                var read = input.readStandalone(null, 256, Cancellation.NONE, queue::add);
                var deliver = queue.poll(5, TimeUnit.SECONDS);
                assertNotNull(deliver);
                assertEquals(12L << 20, budget.snapshot().memory());
                if (cancel) {
                    assertTrue(read.cancel(false));
                    assertEquals(0, budget.snapshot().memory());
                }
                input.closeAsync().get(5, TimeUnit.SECONDS);
                assertThrows(java.util.concurrent.CancellationException.class, read::join);
                drained(budget);
                deliver.run();
                drained(budget);
            }
        }
    }

    @Test
    void cancelingObserverDoesNotReleaseAllowanceUntilActualProjectorDrain() throws Exception {
        try (var budget = new TempBudget()) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var input =
                    input(
                            budget,
                            List.of(row(1, "blocked")),
                            v -> {
                                started.countDown();
                                try {
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException error) {
                                    throw new AssertionError(error);
                                }
                                return v;
                            });
            var read = input.read(null, 1, Cancellation.NONE);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            read.cancel(false);
            assertEquals(12L << 20, budget.snapshot().memory());
            var closing = input.closeAsync();
            assertFalse(closing.isDone());
            release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            drained(budget);
        }
    }

    @Test
    void successfulDeliveryCallbackCanCloseAndJoinWithoutDeadlock() throws Exception {
        try (var budget = new TempBudget()) {
            var input = input(budget, List.of(row(1, "callback")), v -> v);
            var value =
                    input.read(null, 1, Cancellation.NONE)
                            .thenApply(
                                    page -> {
                                        assertEquals(0, budget.snapshot().memory());
                                        input.closeAsync().join();
                                        return page.rows().getFirst();
                                    })
                            .get(5, TimeUnit.SECONDS);
            assertEquals(row(1, "callback"), value);
            drained(budget);
        }
    }

    @Test
    void rejectedDeliveryAndCanceledTokenReleaseOwnedResources() throws Exception {
        try (var budget = new TempBudget()) {
            var input = input(budget, List.of(row(1, "a")), v -> v);
            assertThrows(
                    CompletionException.class,
                    () ->
                            input.readStandalone(
                                            null,
                                            1,
                                            Cancellation.NONE,
                                            runnable -> {
                                                throw new java.util.concurrent
                                                        .RejectedExecutionException();
                                            })
                                    .join());
            assertEquals(0, budget.snapshot().memory());
            input.closeAsync().join();
            var second = input(budget, List.of(row(1, "a")), v -> v);
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> second.read(null, 1, token).join());
            second.closeAsync().join();
            drained(budget);
        }
    }
}
