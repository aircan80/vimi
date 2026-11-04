package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Batched appends store exactly what the same individual appends would have stored. */
@Timeout(60)
final class RecordFileBatchTest {
    private static final GeneratedKey KEY =
            GeneratedKey.hash("batched".getBytes(StandardCharsets.UTF_8));
    private static final byte[] META = {4, 5, 6};

    private static RecordFileOptions small() {
        return new RecordFileOptions(10000, 100000, 1000, 512, 2048, 9);
    }

    private static byte[][] records(int count, long seed) {
        Random random = new Random(seed);
        byte[][] records = new byte[count][];
        for (int i = 0; i < count; i++) {
            // Mostly small rows, some at or above the block size, and some empty.
            int size =
                    switch (random.nextInt(20)) {
                        case 0 -> 0;
                        case 1 -> 512 + random.nextInt(900);
                        default -> 1 + random.nextInt(120);
                    };
            records[i] = new byte[size];
            random.nextBytes(records[i]);
        }
        return records;
    }

    private static long[] ids(int count, boolean ascending) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = ascending ? 3L * i + 1 : 0xffff_ffffL - 7L * ((i * 7919L) % count);
        }
        return ids;
    }

    private static Map<String, String> objects(MemoryGeneratedStore store) {
        Map<String, String> objects = new TreeMap<>();
        for (GeneratedKey key : store.keys()) {
            objects.put(key.hex(), HexFormat.of().formatHex(store.read(key).join().bytes()));
        }
        return objects;
    }

    private static Map<String, String> individually(long[] ids, byte[][] records) {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            for (int i = 0; i < ids.length; i++) {
                writer.add(ids[i], records[i]).join();
            }
            writer.complete(KEY, META).join();
        }
        return objects(store);
    }

    private static Map<String, String> batched(long[] ids, byte[][] records, int batch) {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            long[] pendingIds = new long[batch];
            byte[][] pending = new byte[batch][];
            for (int start = 0; start < ids.length; start += batch) {
                int n = Math.min(batch, ids.length - start);
                System.arraycopy(ids, start, pendingIds, 0, n);
                System.arraycopy(records, start, pending, 0, n);
                writer.addAll(pendingIds, pending, n, Cancellation.NONE).join();
                // The writer owns its copies; reusing the caller's arrays changes nothing.
                java.util.Arrays.fill(pendingIds, 77);
                for (int i = 0; i < n; i++) {
                    java.util.Arrays.fill(pending[i], (byte) 0x33);
                }
            }
            writer.complete(KEY, META).join();
        }
        return objects(store);
    }

    @Test
    void everyBatchSizeStoresTheSameSegmentsAndManifestAsIndividualAdds() {
        for (boolean ascending : new boolean[] {true, false}) {
            long[] ids = ids(1500, ascending);
            byte[][] expectedRecords = records(ids.length, 2);
            Map<String, String> expected = individually(ids, expectedRecords);
            for (int batch : new int[] {1, 2, 7, 64, 1499, 1500, 4096}) {
                assertEquals(
                        expected,
                        batched(ids, records(ids.length, 2), batch),
                        () -> "batch " + batch + " ascending " + ascending);
            }
        }
    }

    @Test
    void duplicateMidBatchKeepsEarlierRecordsAndTheWriterUsable() {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    new long[] {5, 6, 5, 7},
                                                    new byte[][] {{1}, {2}, {3}, {4}},
                                                    4,
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals("Duplicate record id", failure.getCause().getMessage());
            writer.add(7, new byte[] {4}).join();
            writer.complete(KEY, META).join();
        }
        assertEquals(
                individually(new long[] {5, 6, 7}, new byte[][] {{1}, {2}, {4}}), objects(store));
    }

    @Test
    void invalidRecordMidBatchKeepsEarlierRecordsAndTheWriterUsable() {
        var options = new RecordFileOptions(3, 100, 1000, 512, 2048, 9);
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, options)) {
            var oversized =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    new long[] {1, 2},
                                                    new byte[][] {{1}, new byte[101]},
                                                    2,
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(GeneratedStoreException.class, oversized.getCause());
            var badId =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    new long[] {2, -1},
                                                    new byte[][] {{2}, {9}},
                                                    2,
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(IllegalArgumentException.class, badId.getCause());
            var full =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    writer.addAll(
                                                    new long[] {3, 4},
                                                    new byte[][] {{3}, {4}},
                                                    2,
                                                    Cancellation.NONE)
                                            .join());
            assertInstanceOf(GeneratedStoreException.class, full.getCause());
            writer.complete(KEY, META).join();
        }
        assertEquals(
                individually(new long[] {1, 2, 3}, new byte[][] {{1}, {2}, {3}}), objects(store));
    }

    @Test
    void cancellationBeforeABatchAppendsNothingAndAllowsRetry() {
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            writer.addAll(new long[] {1, 2}, new byte[][] {{1}, {2}}, 2, canceled)
                                    .join());
            writer.addAll(new long[] {1, 2}, new byte[][] {{1}, {2}}, 2, Cancellation.NONE).join();
            writer.complete(KEY, META).join();
        }
        assertEquals(individually(new long[] {1, 2}, new byte[][] {{1}, {2}}), objects(store));
    }
}
