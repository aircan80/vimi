package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class RecordFileBatchTest {
    private static final GeneratedKey KEY =
            GeneratedKey.hash("batch".getBytes(StandardCharsets.UTF_8));
    private static final byte[] META = {7, 8, 9};

    private static RecordFileOptions small() {
        // Small blocks and segments so batches straddle block, oversize-record and segment
        // boundaries.
        return new RecordFileOptions(10000, 100000, 1000, 512, 2048, 9);
    }

    private record Rows(List<Long> ids, List<byte[]> records) {}

    private static Rows rows(int count, boolean ascending) {
        var random = new Random(count * 31L + (ascending ? 1 : 0));
        var ids = new ArrayList<Long>();
        var records = new ArrayList<byte[]>();
        for (int i = 0; i < count; i++) {
            // Out-of-order ids exercise the writer's duplicate set; some records exceed a block.
            ids.add(ascending ? i * 3L + 1 : (i * 7919L) % 100003);
            byte[] record = new byte[i % 97 == 0 ? 700 : random.nextInt(60)];
            random.nextBytes(record);
            records.add(record);
        }
        return new Rows(ids, records);
    }

    private static TreeMap<String, byte[]> contents(MemoryGeneratedStore store) {
        var result = new TreeMap<String, byte[]>();
        for (var key :
                store.keys().stream().sorted(Comparator.comparing(Object::toString)).toList()) {
            result.put(key.toString(), store.read(key, Cancellation.NONE).join().bytes());
        }
        return result;
    }

    private static TreeMap<String, byte[]> perRow(Rows rows) {
        var store = new MemoryGeneratedStore();
        try (var writer = new RecordFileWriter(store, small())) {
            for (int i = 0; i < rows.ids().size(); i++) {
                writer.add(rows.ids().get(i), rows.records().get(i), Cancellation.NONE).join();
            }
            writer.complete(KEY, META, Cancellation.NONE).join();
        }
        return contents(store);
    }

    private static TreeMap<String, byte[]> batched(Rows rows, int page) {
        var store = new MemoryGeneratedStore();
        try (var writer = new RecordFileWriter(store, small())) {
            for (int from = 0; from < rows.ids().size(); from += page) {
                int to = Math.min(rows.ids().size(), from + page);
                writer.addAll(
                                rows.ids().subList(from, to),
                                rows.records().subList(from, to),
                                Cancellation.NONE)
                        .join();
            }
            writer.complete(KEY, META, Cancellation.NONE).join();
        }
        return contents(store);
    }

    private static void assertSameFiles(TreeMap<String, byte[]> a, TreeMap<String, byte[]> b) {
        assertEquals(a.keySet(), b.keySet());
        for (var key : a.keySet()) {
            assertArrayEquals(a.get(key), b.get(key), key);
        }
    }

    @Test
    void batchedRecordFilesAreByteIdenticalToPerRowAdds() {
        for (int count : new int[] {0, 1, 255, 256, 257, 3000}) {
            for (boolean ascending : new boolean[] {true, false}) {
                var rows = rows(count, ascending);
                var expected = perRow(rows);
                for (int page : new int[] {1, 7, 256, 4096}) {
                    assertSameFiles(expected, batched(rows, page));
                }
            }
        }
    }

    @Test
    void firstRecordFailureLeavesWriterUsable() {
        var store = new MemoryGeneratedStore();
        try (var writer = new RecordFileWriter(store, small())) {
            writer.addAll(List.of(5L), List.of(new byte[] {1}), Cancellation.NONE).join();
            var duplicate =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    List.of(5L, 6L),
                                                    List.of(new byte[] {2}, new byte[] {3}),
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(IllegalArgumentException.class, duplicate.getCause());
            // Validation refusals happen before any mutation.
            assertThrows(
                    CompletionException.class,
                    () ->
                            writer.addAll(
                                            List.of(6L, 7L),
                                            List.of(new byte[] {2}, new byte[100001]),
                                            Cancellation.NONE)
                                    .join());
            assertThrows(
                    CompletionException.class,
                    () -> writer.addAll(List.of(6L), List.of(), Cancellation.NONE).join());
            writer.addAll(List.of(6L), List.of(new byte[] {4}), Cancellation.NONE).join();
            assertEquals(2, writer.complete(KEY, META, Cancellation.NONE).join().count());
        }
    }

    @Test
    void laterDuplicateFinishesWriterBecauseBatchWasPartlyApplied() {
        var store = new MemoryGeneratedStore();
        try (var writer = new RecordFileWriter(store, small())) {
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    List.of(1L, 2L, 1L),
                                                    List.of(
                                                            new byte[] {1},
                                                            new byte[] {2},
                                                            new byte[] {3}),
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            var finished =
                    assertThrows(
                            CompletionException.class,
                            () -> writer.add(9L, new byte[] {9}, Cancellation.NONE).join());
            assertInstanceOf(IllegalStateException.class, finished.getCause());
        }
    }

    @Test
    void batchCannotExceedRecordCountBound() {
        var store = new MemoryGeneratedStore();
        try (var writer =
                new RecordFileWriter(store, new RecordFileOptions(3, 100, 100, 512, 2048, 9))) {
            writer.addAll(List.of(1L, 2L), List.of(new byte[1], new byte[1]), Cancellation.NONE)
                    .join();
            var limit =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    List.of(3L, 4L),
                                                    List.of(new byte[1], new byte[1]),
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(GeneratedStoreException.class, limit.getCause());
            writer.addAll(List.of(3L), List.of(new byte[1]), Cancellation.NONE).join();
            assertEquals(3, writer.complete(KEY, META, Cancellation.NONE).join().count());
        }
    }
}
