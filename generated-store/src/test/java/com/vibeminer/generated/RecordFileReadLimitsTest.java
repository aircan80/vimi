package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class RecordFileReadLimitsTest {
    private final MemoryGeneratedStore store = new MemoryGeneratedStore();
    private final GeneratedKey key = GeneratedKey.hash(new byte[] {9, 7});

    private RecordFile write(byte[]... records) {
        try (var writer =
                new RecordFileWriter(
                        store, new RecordFileOptions(100, 1 << 20, 1024, 1 << 20, 2 << 20, 9))) {
            for (int i = 0; i < records.length; i++) {
                writer.add(i, records[i]).join();
            }
            writer.complete(key, new byte[0]).join();
        }
        return RecordFile.open(store, key).join().file();
    }

    private RecordFormat.Frame frame() {
        return RecordFormat.decode(store.read(key).join().bytes()).blocks().getFirst().frame();
    }

    private static void limited(Runnable read) {
        var failure = assertThrows(CompletionException.class, read::run);
        assertEquals(GeneratedTypes.LIMIT, ((GeneratedStoreException) failure.getCause()).code());
    }

    @Test
    void blockLimitsRejectBeforeAnyCompressedRangeReadAndLeaveLegacyReadable() {
        byte[] payload = new byte[4096];
        new java.util.Random(34).nextBytes(payload);
        var file = write(payload);
        var f = frame();
        int reads = store.rangeReads();
        limited(
                () ->
                        file.read(
                                        0,
                                        1,
                                        new RecordFile.ReadLimits(
                                                f.compressed() - 1, f.length(), payload.length),
                                        Cancellation.NONE)
                                .join());
        assertEquals(reads, store.rangeReads());
        limited(
                () ->
                        file.read(
                                        0,
                                        1,
                                        new RecordFile.ReadLimits(
                                                f.compressed(), f.length() - 1, payload.length),
                                        Cancellation.NONE)
                                .join());
        assertEquals(reads, store.rangeReads());
        assertArrayEquals(payload, file.read(0, 1).join().getFirst().bytes());
    }

    @Test
    void exactLimitsPassAndSelectedRecordCeilingDoesNotDamageTheFile() {
        byte[] payload = new byte[513];
        new java.util.Random(8).nextBytes(payload);
        var file = write(payload);
        var f = frame();
        var exact = new RecordFile.ReadLimits(f.compressed(), f.length(), payload.length);
        assertArrayEquals(
                payload, file.read(0, 1, exact, Cancellation.NONE).join().getFirst().bytes());
        var keys = store.keys();
        limited(
                () ->
                        file.read(
                                        0,
                                        1,
                                        new RecordFile.ReadLimits(
                                                f.compressed(), f.length(), payload.length - 1),
                                        Cancellation.NONE)
                                .join());
        assertEquals(keys, store.keys());
        assertArrayEquals(
                payload, file.read(0, 1, exact, Cancellation.NONE).join().getFirst().bytes());
        assertArrayEquals(payload, file.read(0, 1).join().getFirst().bytes());
    }

    @Test
    void onlySelectedRecordsAreMaterializedAndEmptyPagesNeedNoBlockRead() {
        var file = write(new byte[1024], new byte[] {7});
        var f = frame();
        var limits = new RecordFile.ReadLimits(f.compressed(), f.length(), 1);
        assertArrayEquals(
                new byte[] {7},
                file.read(1, 1, limits, Cancellation.NONE).join().getFirst().bytes());
        int reads = store.rangeReads();
        assertEquals(
                List.of(),
                file.read(2, 1, new RecordFile.ReadLimits(1, 1, 1), Cancellation.NONE).join());
        assertEquals(reads, store.rangeReads());
    }

    @Test
    void canceledReadsDoNotTouchPayloadAndInvalidLimitsRejectSynchronously() {
        var file = write(new byte[] {1});
        var canceled = new Cancellation();
        canceled.cancel();
        int reads = store.rangeReads();
        var error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                file.read(
                                                0,
                                                1,
                                                new RecordFile.ReadLimits(1024, 1024, 1024),
                                                canceled)
                                        .join());
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
        assertEquals(java.util.concurrent.CancellationException.class, cause.getClass());
        assertEquals(reads, store.rangeReads());
        assertThrows(IllegalArgumentException.class, () -> new RecordFile.ReadLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RecordFile.ReadLimits(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RecordFile.ReadLimits(1, 1, 0));
    }
}
