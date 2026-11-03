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
import com.vibeminer.concepts.comparison.ComparisonTypes.Evidence;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class ComparisonMemoryPlanTest {
    private static final long MIB = 1L << 20;
    @TempDir Path temporary;

    private static final class Input implements ComparisonInput {
        final TempBudget budget;
        final TempBudget.Reservation metadata;
        final String side;
        final List<Record> rows;
        int pageRows = 30;
        final AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();

        Input(
                TempBudget budget,
                TempBudget.Reservation metadata,
                String side,
                int count,
                int textLength) {
            this.budget = budget;
            this.metadata = metadata;
            this.side = side;
            var values = new ArrayList<Record>();
            for (int i = 0; i < count; i++) {
                values.add(
                        row(
                                String.format(java.util.Locale.ROOT, "%020d", i),
                                side.repeat(textLength)));
            }
            rows = List.copyOf(values);
        }

        public Descriptor descriptor() {
            return new Descriptor(
                    side, "wowf", "enUS", "facts", "held snapshot", Map.of("source", "authored"));
        }

        public CompletableFuture<InputPage> read(String cursor, int count, Cancellation token) {
            throw new AssertionError("Explicit allowance required");
        }

        public CompletableFuture<InputPage> read(
                String cursor, int count, Cancellation token, TempBudget.Reservation allowance) {
            reads.incrementAndGet();
            metadata.requireMemory(budget, MIB);
            assertEquals(MIB, metadata.size());
            allowance.requireMemory(budget, 12 * MIB);
            assertEquals(12 * MIB, allowance.size());
            int start = cursor == null ? 0 : Integer.parseInt(cursor),
                    end = Math.min(rows.size(), start + Math.min(count, pageRows));
            return CompletableFuture.completedFuture(
                    new InputPage(
                            side,
                            rows.subList(start, end),
                            end == rows.size() ? null : Integer.toString(end),
                            new Coverage(
                                    Completeness.UNKNOWN,
                                    "held snapshot",
                                    List.of("Membership uncertified"))));
        }

        public CompletableFuture<Void> closeAsync() {
            closes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void heldMetadataSurvivesScanAndEveryByteShortenedPage() throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot-metadata", MIB, Cancellation.NONE);
            var a = new Input(budget, metadata, "a", 100, 20000);
            var b = new Input(budget, metadata, "b", 100, 20000);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                assertEquals(RunState.COMPLETED, service.start(a, b).join().state());
                assertEquals(29 * MIB, budget.snapshot().peakMemory());
                assertEquals(MIB + MIB / 2, budget.snapshot().memory());
                Long offset = 0L;
                int total = 0, pages = 0;
                while (offset != null) {
                    var delivery = new java.util.concurrent.ArrayBlockingQueue<Runnable>(1);
                    var future = service.page(offset, 100, delivery::add);
                    var handoff = delivery.poll(3, TimeUnit.SECONDS);
                    assertNotNull(handoff);
                    assertEquals(
                            30 * MIB + MIB / 2 + MIB,
                            budget.snapshot().memory(),
                            "Workspace, index and metadata coexist through every handoff");
                    assertEquals("snapshot-metadata", metadata.owner());
                    assertEquals(MIB, metadata.size());
                    handoff.run();
                    var page = future.join();
                    assertTrue(page.rows().size() < 100);
                    assertFalse(page.rows().isEmpty());
                    total += page.rows().size();
                    pages++;
                    offset = page.nextOffset();
                    assertEquals(MIB + MIB / 2, budget.snapshot().memory());
                }
                assertEquals(100, total);
                assertTrue(pages > 1);
                assertTrue(a.reads.get() > 1 && b.reads.get() > 1);
                assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
            }
            assertEquals(1, a.closes.get());
            assertEquals(1, b.closes.get());
            assertEquals(MIB, budget.snapshot().memory());
            assertEquals("snapshot-metadata", metadata.owner());
            metadata.close();
            assertDrained(budget);
        }
    }

    @Test
    void retainedPlanRejectsMissingWrongBudgetDiskReleasedAndNonexactMetadata() throws Exception {
        try (var budget = new TempBudget();
                var other = new TempBudget()) {
            var released = budget.reserveMemory("released", MIB, Cancellation.NONE);
            released.close();
            try (var wrong = other.reserveMemory("wrong-budget", MIB, Cancellation.NONE);
                    var disk = budget.reserveDisk("disk", MIB, Cancellation.NONE);
                    var small = budget.reserveMemory("small", MIB - 1, Cancellation.NONE);
                    var large = budget.reserveMemory("large", MIB + 1, Cancellation.NONE)) {
                for (var invalid : List.of(wrong, disk, small, large, released)) {
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    new ComparisonService(
                                            temporary,
                                            budget,
                                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                                            invalid));
                }
                assertThrows(
                        RuntimeException.class,
                        () ->
                                new ComparisonService(
                                        temporary,
                                        budget,
                                        ComparisonService.MemoryPlan.withSnapshotMetadata(),
                                        null));
                assertEquals(MIB, wrong.size());
                assertEquals(MIB, disk.size());
                assertEquals(MIB - 1, small.size());
                assertEquals(MIB + 1, large.size());
                try (var files = Files.list(temporary)) {
                    assertEquals(
                            0,
                            files.count(),
                            "Failed constructor admission creates no scratch or input ownership");
                }
            }
            assertDrained(budget);
        }
    }

    @Test
    void releasingMetadataBeforeStartDoesNotTransferInputsAndBeforePagingRefusesWork()
            throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            var a = new Input(budget, metadata, "a", 1, 1);
            var b = new Input(budget, metadata, "b", 1, 1);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                metadata.close();
                assertThrows(CompletionException.class, () -> service.start(a, b).join());
                assertFalse(service.inputsAccepted());
            }
            assertEquals(0, a.reads.get());
            assertEquals(0, a.closes.get());
            assertEquals(0, b.closes.get());
            assertDrained(budget);
            metadata = budget.reserveMemory("second-snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .join();
                metadata.close();
                assertThrows(CompletionException.class, () -> service.page(0, 1).join());
                assertEquals(MIB / 2, budget.snapshot().memory());
            }
            assertDrained(budget);
        }
    }

    @Test
    void structuralPreflightCountsSmallObjectWrappersAndRejectsOversizedCandidate()
            throws Exception {
        var fields = new java.util.LinkedHashMap<String, Field>();
        for (int i = 0; i < 3000; i++) {
            fields.put("k" + i, field("x"));
        }
        var frame = ComparisonCodec.pair(new Record("1", fields, List.of()), null);
        assertTrue(
                ComparisonCodec.classifiedBytes(frame, 12 * MIB) > 8L * frame.length,
                "Structural wrappers contribute beyond byte multiplier");
        var oversized = oversizedFrame();
        assertTrue(oversized.length < ComparisonService.RECORD_BYTES);
        assertThrows(
                ComparisonLimit.class, () -> ComparisonCodec.classifiedBytes(oversized, 12 * MIB));
        for (var malformed :
                List.of(new byte[] {2}, new byte[] {1, 0, 0, 0, 50}, new byte[] {0, 0, 7})) {
            assertThrows(
                    java.io.IOException.class,
                    () -> ComparisonCodec.classifiedBytes(malformed, 12 * MIB));
        }
    }

    @Test
    void oversizedFirstPageCandidateIsLimitedBeforeDecodeAndKeepsMetadataHeld() throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .join();
                var frame = oversizedFrame();
                var path = staging(service);
                long old = Files.size(path), next = 4L + frame.length;
                try (var growth =
                        budget.reserveDisk(
                                "authored-corrupt-fixture",
                                Math.max(0, next - old),
                                Cancellation.NONE)) {
                    try (var file = new RandomAccessFile(path.toFile(), "rw")) {
                        file.setLength(0);
                        file.writeInt(frame.length);
                        file.write(frame);
                    }
                    var error =
                            assertThrows(
                                    CompletionException.class, () -> service.page(0, 1).join());
                    assertInstanceOf(ComparisonLimit.class, error.getCause());
                    assertEquals(MIB + MIB / 2, budget.snapshot().memory());
                    assertEquals(MIB, metadata.size());
                    service.close();
                }
            }
            metadata.close();
            assertDrained(budget);
        }
    }

    @Test
    void heldPageDeliveryCancellationAndCloseDoNotLeakOrReleaseBeforeWorkerDrain()
            throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .join();
                var delivery = new java.util.concurrent.ArrayBlockingQueue<Runnable>(1);
                var page = service.page(0, 1, delivery::add);
                var task = delivery.poll(3, TimeUnit.SECONDS);
                assertNotNull(task);
                assertEquals(31 * MIB + MIB / 2, budget.snapshot().memory());
                assertTrue(page.cancel(false));
                assertEquals(MIB + MIB / 2, budget.snapshot().memory());
                service.closeAsync().get(3, TimeUnit.SECONDS);
                task.run();
                assertEquals(MIB, budget.snapshot().memory());
            }
            metadata.close();
            assertDrained(budget);
        }
    }

    @Test
    void malformedFrameFailsBeforeDecodingAndReleasesOnlyItsWorkspace() throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .join();
                try (var file = new RandomAccessFile(staging(service).toFile(), "rw")) {
                    file.seek(4);
                    file.writeByte(2);
                }
                var failure =
                        assertThrows(CompletionException.class, () -> service.page(0, 1).join());
                assertInstanceOf(java.io.IOException.class, failure.getCause());
                assertEquals(MIB + MIB / 2, budget.snapshot().memory());
                assertEquals(MIB, metadata.size());
            }
            metadata.close();
            assertDrained(budget);
        }
    }

    @Test
    void metadataPlanCanonicalInputGuardUsesTwelveMiBRatherThanDefaultFourteen() throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                var a = new Input(budget, metadata, "a", 70, 50000);
                var b = new Input(budget, metadata, "b", 70, 50000);
                a.pageRows = 70;
                b.pageRows = 70;
                assertEquals(RunState.LIMITED, service.start(a, b).join().state());
                assertTrue(service.progress().message().contains("Input page working buffer"));
            }
            metadata.close();
            assertDrained(budget);
        }
    }

    @Test
    void canceledObserverCannotReleaseWorkspaceUntilPrivateWorkerDrains() throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .join();
                var entered = new CompletableFuture<Void>();
                var release = new CompletableFuture<Void>();
                var page =
                        service.page(
                                0,
                                1,
                                Runnable::run,
                                () -> {
                                    entered.complete(null);
                                    release.join();
                                });
                entered.get(3, TimeUnit.SECONDS);
                assertEquals(31 * MIB + MIB / 2, budget.snapshot().memory());
                assertTrue(page.cancel(false));
                assertEquals(
                        31 * MIB + MIB / 2,
                        budget.snapshot().memory(),
                        "Public observer cancellation is not private operation drain");
                release.complete(null);
                service.closeAsync().get(3, TimeUnit.SECONDS);
                assertEquals(
                        MIB,
                        budget.snapshot().memory(),
                        "Private operation and delivery ownership drained before close completes");
            }
            metadata.close();
            assertDrained(budget);
        }
    }

    @Test
    void pageAndProgressCallbacksCanCloseAndJoinWithoutWorkerDeadlock() throws Exception {
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .join();
                service.page(0, 1)
                        .thenApply(
                                page -> {
                                    service.close();
                                    return page;
                                })
                        .get(3, TimeUnit.SECONDS);
            }
            assertEquals(MIB, budget.snapshot().memory());
            metadata.close();
            assertDrained(budget);
        }
        try (var budget = new TempBudget()) {
            var metadata = budget.reserveMemory("snapshot", MIB, Cancellation.NONE);
            try (var service =
                    new ComparisonService(
                            temporary,
                            budget,
                            ComparisonService.MemoryPlan.withSnapshotMetadata(),
                            metadata)) {
                service.start(
                                new Input(budget, metadata, "a", 1, 1),
                                new Input(budget, metadata, "b", 1, 1))
                        .thenApply(
                                progress -> {
                                    service.close();
                                    return progress;
                                })
                        .get(3, TimeUnit.SECONDS);
            }
            metadata.close();
            assertDrained(budget);
        }
    }

    private static byte[] oversizedFrame() throws Exception {
        return ComparisonCodec.pair(
                new Record(
                        "1",
                        Map.of("v", field("x")),
                        java.util.Collections.nCopies(50000, new Evidence("s", "i", null, null))),
                null);
    }

    private static Field field(String value) {
        return new Field(
                "text", ValueKind.TEXT, ValueState.VALUE, value, Aspect.CONTENT, List.of());
    }

    private static Record row(String key, String value) {
        return new Record(key, Map.of("v", field(value)), List.of());
    }

    private static Path staging(ComparisonService service) throws Exception {
        var field = ComparisonService.class.getDeclaredField("staging");
        field.setAccessible(true);
        return (Path) field.get(service);
    }

    private void assertDrained(TempBudget budget) throws Exception {
        assertEquals(0, budget.snapshot().memory());
        assertEquals(0, budget.snapshot().disk());
        assertEquals(0, budget.snapshot().reservations());
        try (var files = Files.list(temporary)) {
            assertEquals(0, files.count());
        }
    }
}
