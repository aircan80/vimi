package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.vibeminer.concepts.comparison.ComparisonTypes.RunState;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueKind;
import com.vibeminer.concepts.comparison.ComparisonTypes.ValueState;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class ComparisonSharedBudgetTest {
    private static final long MIB = 1L << 20;
    @TempDir Path directory;

    private static Record row(String id, String value) {
        return new Record(
                id,
                Map.of(
                        "value",
                        new Field(
                                "fixture-text-v1",
                                ValueKind.TEXT,
                                ValueState.VALUE,
                                value,
                                Aspect.CONTENT,
                                List.of())),
                List.of());
    }

    private static class Input implements ComparisonInput {
        final Descriptor descriptor;
        final List<Record> rows;
        final AtomicInteger reads = new AtomicInteger();
        boolean closed;

        Input(String identity, List<Record> rows) {
            descriptor =
                    new Descriptor(
                            identity, "wowf", "enUS", "fixture-records", "fixture", Map.of());
            this.rows = rows;
        }

        @Override
        public Descriptor descriptor() {
            return descriptor;
        }

        @Override
        public CompletableFuture<InputPage> read(String cursor, int limit, Cancellation token) {
            reads.incrementAndGet();
            int from = cursor == null ? 0 : Integer.parseInt(cursor),
                    to = Math.min(rows.size(), from + limit);
            return CompletableFuture.completedFuture(
                    new InputPage(
                            descriptor.identity(),
                            rows.subList(from, to),
                            to == rows.size() ? null : Integer.toString(to),
                            new Coverage(
                                    to == rows.size()
                                            ? Completeness.COMPLETE
                                            : Completeness.UNKNOWN,
                                    "fixture",
                                    List.of())));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    /** A sealed sorted-file boundary: file bytes remain charged throughout the core merge. */
    private static final class FileInput extends Input {
        final BudgetedTempFile file;

        FileInput(String id, BudgetedTempFile file) {
            super(id, List.of());
            this.file = file;
        }

        @Override
        public CompletableFuture<InputPage> read(String cursor, int limit, Cancellation token) {
            try (var input = new RandomAccessFile(file.path().toFile(), "r")) {
                input.seek(cursor == null ? 0 : Long.parseLong(cursor));
                var rows = new ArrayList<Record>();
                while (rows.size() < limit && input.getFilePointer() < input.length()) {
                    token.check();
                    int length = input.readInt();
                    byte[] frame = new byte[length];
                    input.readFully(frame);
                    rows.add(ComparisonCodec.pair(frame)[0]);
                }
                long next = input.getFilePointer();
                return CompletableFuture.completedFuture(
                        new InputPage(
                                descriptor.identity(),
                                rows,
                                next == input.length() ? null : Long.toString(next),
                                new Coverage(
                                        next == input.length()
                                                ? Completeness.COMPLETE
                                                : Completeness.UNKNOWN,
                                        "fixture",
                                        List.of())));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            try {
                file.close();
                return CompletableFuture.completedFuture(null);
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private FileInput sorted(String id, String value, TempBudget budget) throws Exception {
        var file =
                BudgetedTempFile.create(
                        directory, "sorted", budget, "sealed-" + id, Cancellation.NONE);
        file.appendFrame(ComparisonCodec.pair(row("01", value), null), Cancellation.NONE);
        file.seal();
        return new FileInput(id, file.transferTo("input-" + id));
    }

    @Test
    void sealedInputFilesAndCoreOutputMustFitTheSameDiskQuota() throws Exception {
        long aBytes = 4 + ComparisonCodec.pair(row("01", "old"), null).length;
        long bBytes = 4 + ComparisonCodec.pair(row("01", "new"), null).length;
        long output = 4 + ComparisonCodec.pair(row("01", "old"), row("01", "new")).length;
        try (var budget = new TempBudget(32 * MIB, aBytes + bBytes + output - 1)) {
            var a = sorted("a", "old", budget);
            var b = sorted("b", "new", budget);
            try (var service = new ComparisonService(directory, budget)) {
                var progress = service.start(a, b).join();
                assertEquals(RunState.LIMITED, progress.state());
                assertEquals(0, progress.stagedBytes());
                assertEquals(aBytes + bBytes, budget.snapshot().disk());
                assertEquals(32 * MIB, budget.snapshot().peakMemory());
                assertTrue(service.page(0, 1).join().rows().isEmpty());
            }
            assertTrue(a.closed && b.closed);
            assertEquals(0, budget.snapshot().disk());
            assertEquals(0, budget.snapshot().memory());
            try (var owner = budget.reserveMemory("still-caller-owned", 1, Cancellation.NONE)) {
                assertEquals(1, owner.size());
            }
        }
    }

    @Test
    void successfulOutputCoexistsWithBothSealedInputsUntilClose() throws Exception {
        try (var budget = new TempBudget()) {
            var a = sorted("a", "old", budget);
            var b = sorted("b", "new", budget);
            long inputs = budget.snapshot().disk();
            try (var service = new ComparisonService(directory, budget)) {
                var progress = service.start(a, b).join();
                assertEquals(RunState.COMPLETED, progress.state());
                assertEquals(inputs + progress.stagedBytes(), budget.snapshot().disk());
                assertEquals(1, service.page(0, 1).join().rows().size());
                assertEquals(inputs + progress.stagedBytes(), budget.snapshot().disk());
            }
            assertEquals(0, budget.snapshot().reservations());
        }
    }

    @Test
    void insufficientSharedMemoryIsLimitedBeforeCallingAnySource() throws Exception {
        try (var budget = new TempBudget(3 * MIB, 1024)) {
            var a = new Input("a", List.of());
            var b = new Input("b", List.of());
            try (var service = new ComparisonService(directory, budget)) {
                assertEquals(RunState.LIMITED, service.start(a, b).join().state());
                assertEquals(0, a.reads.get() + b.reads.get());
                assertEquals(0, budget.snapshot().memory());
            }
            assertTrue(a.closed && b.closed);
            assertEquals(0, budget.snapshot().reservations());
        }
    }

    @Test
    void secondPageAdmissionFailureCancelsAndDrainsAlreadyAcceptedRead() throws Exception {
        try (var budget = new TempBudget(31 * MIB, 1024)) {
            var a =
                    new Input("a", List.of()) {
                        @Override
                        public CompletableFuture<InputPage> read(
                                String cursor, int limit, Cancellation token) {
                            reads.incrementAndGet();
                            assertEquals(18 * MIB, budget.snapshot().memory());
                            var result = new CompletableFuture<InputPage>();
                            token.onCancel(
                                    () ->
                                            result.completeExceptionally(
                                                    new java.util.concurrent
                                                            .CancellationException()));
                            return result;
                        }
                    };
            var b = new Input("b", List.of());
            try (var service = new ComparisonService(directory, budget)) {
                assertEquals(RunState.LIMITED, service.start(a, b).join().state());
                assertEquals(1, a.reads.get());
                assertEquals(0, b.reads.get());
                assertEquals(MIB / 2, budget.snapshot().memory());
                assertEquals(0, service.retainedInputBytes());
            }
            assertEquals(0, budget.snapshot().memory());
        }
    }

    @Test
    void exhaustedPagesAreReleasedBeforeReplacementReservation() throws Exception {
        try (var budget = new TempBudget()) {
            var rows = new ArrayList<Record>();
            for (int i = 0; i < 600; i++) {
                rows.add(row(String.format("%04d", i), "same"));
            }
            var a =
                    new Input("a", rows) {
                        @Override
                        public CompletableFuture<InputPage> read(
                                String cursor, int limit, Cancellation token) {
                            assertTrue(budget.snapshot().memory() <= 32 * MIB);
                            assertTrue(budget.snapshot().memory() >= 18 * MIB);
                            return super.read(cursor, limit, token);
                        }
                    };
            var b = new Input("b", rows);
            try (var service = new ComparisonService(directory, budget)) {
                assertEquals(RunState.COMPLETED, service.start(a, b).join().state());
                assertEquals(600, service.progress().observed());
                assertEquals(32 * MIB, budget.snapshot().peakMemory());
                assertEquals(MIB / 2, budget.snapshot().memory());
                assertTrue(service.page(0, 10).join().rows().isEmpty());
            }
        }
    }

    @Test
    void resultPageAllocationUsesTheSharedBudgetAndReleasesAfterHandoff() throws Exception {
        try (var budget = new TempBudget();
                var service = new ComparisonService(directory, budget)) {
            service.start(
                            new Input("a", List.of(row("01", "old"))),
                            new Input("b", List.of(row("01", "new"))))
                    .join();
            assertEquals(MIB / 2, budget.snapshot().memory());
            try (var held = budget.reserveMemory("other-stage", MIB, Cancellation.NONE)) {
                var failure =
                        assertThrows(
                                java.util.concurrent.CompletionException.class,
                                () -> service.page(0, 1).join());
                assertTrue(failure.getCause() instanceof TempBudget.Limit);
            }
            assertEquals(1, service.page(0, 1).join().rows().size());
            assertEquals(MIB / 2, budget.snapshot().memory());
        }
    }

    @Test
    void rejectedCoreAdmissionLeavesSealedInputsAndSharedBudgetWithCaller() throws Exception {
        try (var budget = new TempBudget()) {
            var a = sorted("a", "old", budget);
            var b = sorted("b", "new", budget);
            long bytes = budget.snapshot().disk();
            var executor = ComparisonService.newWorker();
            executor.shutdown();
            try (var service = new ComparisonService(directory, budget, executor)) {
                assertThrows(RuntimeException.class, () -> service.start(a, b).join());
                assertFalse(service.inputsAccepted());
            }
            assertFalse(a.closed || b.closed);
            assertEquals(bytes, budget.snapshot().disk());
            a.closeAsync().join();
            b.closeAsync().join();
            assertEquals(0, budget.snapshot().reservations());
            try (var paths = Files.list(directory)) {
                assertEquals(0, paths.count());
            }
        }
    }
}
