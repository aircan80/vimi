package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Completeness;
import com.vibeminer.concepts.comparison.ComparisonTypes.Coverage;
import com.vibeminer.concepts.comparison.ComparisonTypes.Descriptor;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StreamingItemJoinTest {
    @TempDir Path directory;

    @Test
    void unionRetainsIndependentHalvesAndMetadataThroughSealedSuccessor() throws Exception {
        try (var budget = new TempBudget();
                var metadata =
                        budget.reserveMemory("items-metadata", 1L << 20, Cancellation.NONE)) {
            var a = sorted(budget, 0, 2, 4294967295L);
            var b = sorted(budget, 1, 2);
            var halves = new ArrayList<String>();
            try (var output =
                    StreamingItemJoin.join(
                            a,
                            b,
                            directory,
                            budget,
                            (left, right) -> {
                                halves.add(
                                        (left == null ? "-" : left.key())
                                                + "/"
                                                + (right == null ? "-" : right.key()));
                                return left == null ? right : left;
                            },
                            Cancellation.NONE)) {
                assertEquals(4, halves.size());
                assertTrue(halves.get(0).endsWith("/-"));
                assertTrue(halves.get(1).startsWith("-/"));
                assertEquals(key(2) + "/" + key(2), halves.get(2));
                assertEquals(1L << 20, budget.snapshot().memory());
                assertEquals(1L << 20, metadata.size());
                assertEquals(output.size(), budget.snapshot().disk());
                var ids = new ArrayList<String>();
                try (var in = new DataInputStream(Files.newInputStream(output.path()))) {
                    while (in.available() > 0) {
                        ids.add(ComparisonCodec.pair(in.readNBytes(in.readInt()))[0].key());
                    }
                }
                assertEquals(List.of(key(0), key(1), key(2), key(4294967295L)), ids);
                assertTrue(budget.snapshot().peakMemory() <= ComparisonService.BUFFER_BYTES);
            }
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void mergerFailureDeletesSuccessorAndBothInputs() throws Exception {
        try (var budget = new TempBudget()) {
            var a = sorted(budget, 1, 3);
            var b = sorted(budget, 2, 3);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            StreamingItemJoin.join(
                                    a,
                                    b,
                                    directory,
                                    budget,
                                    (left, right) -> {
                                        throw new IllegalStateException("fixture");
                                    },
                                    Cancellation.NONE));
            assertEquals(0, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void keyChangingMergerIsRejectedAndDrained() throws Exception {
        try (var budget = new TempBudget()) {
            var a = sorted(budget, 1);
            var b = sorted(budget, 1);
            assertThrows(
                    IOException.class,
                    () ->
                            StreamingItemJoin.join(
                                    a,
                                    b,
                                    directory,
                                    budget,
                                    (left, right) -> row(2),
                                    Cancellation.NONE));
            assertEquals(0, budget.snapshot().disk());
            assertEquals(0, budget.snapshot().memory());
        }
    }

    @Test
    void cancellationDuringMergeDrainsEveryOwnedFile() throws Exception {
        try (var budget = new TempBudget()) {
            var a = sorted(budget, 1, 3);
            var b = sorted(budget, 2, 3);
            var token = new Cancellation();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            StreamingItemJoin.join(
                                    a,
                                    b,
                                    directory,
                                    budget,
                                    (left, right) -> {
                                        token.cancel();
                                        return left == null ? right : left;
                                    },
                                    token));
            assertEquals(0, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void exhaustedDependenciesProduceSealedEmptySuccessor() throws Exception {
        try (var budget = new TempBudget()) {
            var a = sorted(budget);
            var b = sorted(budget);
            try (var output =
                    StreamingItemJoin.join(
                            a,
                            b,
                            directory,
                            budget,
                            (left, right) -> {
                                throw new AssertionError("no candidate");
                            },
                            Cancellation.NONE)) {
                assertEquals(0, output.size());
                assertEquals(0, Files.size(output.path()));
                assertEquals(0, budget.snapshot().memory());
            }
            assertEquals(0, budget.snapshot().disk());
        }
    }

    private SortedComparisonInput sorted(TempBudget budget, long... ids) {
        var records = new ArrayList<Record>();
        for (long id : ids) {
            records.add(row(id));
        }
        var descriptor = new Descriptor("fixture", "wowf", "enUS", "item", "fixture", Map.of());
        return SortedComparisonInput.capture(
                        new SortedComparisonInput.Source() {
                            public Descriptor descriptor() {
                                return descriptor;
                            }

                            public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                                    String cursor, int count, long bytes, Cancellation token) {
                                int start = cursor == null ? 0 : Integer.parseInt(cursor);
                                int end = Math.min(records.size(), start + count);
                                return CompletableFuture.completedFuture(
                                        new SortedComparisonInput.UnsortedPage(
                                                "fixture",
                                                records.subList(start, end),
                                                end == records.size() ? null : "" + end,
                                                new Coverage(
                                                        Completeness.UNKNOWN,
                                                        "fixture",
                                                        List.of())));
                            }

                            public CompletableFuture<Void> closeAsync() {
                                return CompletableFuture.completedFuture(null);
                            }
                        },
                        directory,
                        budget,
                        Cancellation.NONE)
                .join();
    }

    private static Record row(long id) {
        return new Record(key(id), Map.of(), List.of());
    }

    private static String key(long id) {
        return String.format(java.util.Locale.ROOT, "%020d", id);
    }
}
