package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class RecordFileTest {
    private final MemoryGeneratedStore store = new MemoryGeneratedStore();
    private final GeneratedKey key = GeneratedKey.hash("records".getBytes(StandardCharsets.UTF_8));
    private static final byte[] META = {1, 2, 3};

    private RecordFileOptions small() {
        return new RecordFileOptions(10000, 100000, 1000, 512, 2048, 9);
    }

    private byte[] payload(int i) {
        byte[] bytes = new byte[20 + i % 91];
        new java.util.Random(i).nextBytes(bytes);
        return bytes;
    }

    private RecordFileInfo write(int count) {
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            for (int i = 0; i < count; i++) {
                writer.add((count - i) * 2L, payload(i)).join();
            }
            return writer.complete(key, META).join();
        }
    }

    private RecordFile open() {
        var open = RecordFile.open(store, key).join();
        assertEquals(ReadState.PRESENT, open.state());
        return open.file();
    }

    private void replace(GeneratedKey key, byte[] bytes) {
        store.delete(List.of(key)).join();
        store.put(List.of(new Put(key, bytes))).join();
    }

    private static void corrupt(Runnable work) {
        CompletionException e = assertThrows(CompletionException.class, work::run);
        assertEquals(GeneratedTypes.CORRUPT, ((GeneratedStoreException) e.getCause()).code());
    }

    @Test
    void emptyFileAndEmptyRecordsRoundTrip() {
        write(0);
        RecordFile file = open();
        assertEquals(0, file.count());
        assertArrayEquals(META, file.metadata());
        assertEquals(List.of(), file.read(0, 10).join());
        assertNull(file.find(0).join());
        assertNull(file.findAtOrAfter(0).join());
        assertEquals(List.of(), file.readAll().next().join());
        assertEquals(List.of(key), file.keys());
        store.clear();
        try (RecordFileWriter writer = new RecordFileWriter(store)) {
            writer.add(0, new byte[0]).join();
            writer.complete(key, new byte[0]).join();
        }
        assertArrayEquals(new byte[0], open().find(0).join().bytes());
    }

    @Test
    void pagesAndIdLookupsRoundTripAcrossBlocksSegmentsAndChunks() {
        RecordFileInfo info = write(3000);
        RecordFile file = open();
        assertTrue(info.segments() > 5);
        assertEquals(3, info.idChunks());
        assertEquals(3000, file.count());
        for (int start : new int[] {0, 5, 512, 1023, 1024, 2047, 2999, 3000}) {
            var page = file.read(start, 50).join();
            assertEquals(Math.min(50, 3000 - start), page.size());
            for (int j = 0; j < page.size(); j++) {
                int i = start + j;
                assertEquals(i, page.get(j).ordinal());
                assertEquals((3000 - i) * 2L, page.get(j).id());
                assertArrayEquals(payload(i), page.get(j).bytes());
            }
        }
        for (int i : new int[] {0, 1, 500, 1024, 2048, 2999}) {
            var record = file.find((3000 - i) * 2L).join();
            assertEquals(i, record.ordinal());
            assertArrayEquals(payload(i), record.bytes());
        }
        assertNull(file.find(3).join());
        assertEquals(4, file.findAtOrAfter(3).join().id());
        assertNull(file.findAtOrAfter(6001).join());
        assertEquals(2, file.findAtOrAfter(0).join().id());
    }

    @Test
    void unsignedIdsSortAcrossTheSignBoundary() {
        try (RecordFileWriter writer = new RecordFileWriter(store)) {
            for (long id : new long[] {0xffff_ffffL, 0, 0x8000_0000L, 7}) {
                writer.add(id, new byte[] {(byte) id}).join();
            }
            writer.complete(key, META).join();
        }
        RecordFile file = open();
        assertEquals(0, file.find(0xffff_ffffL).join().ordinal());
        assertEquals(0x8000_0000L, file.findAtOrAfter(8).join().id());
        assertEquals(0xffff_ffffL, file.findAtOrAfter(0xffff_fffeL).join().id());
    }

    @Test
    void pagesTouchOnlyRequiredBlocksAndLookupReadsOneIndexChunk() {
        write(2000);
        RecordFile file = open();
        int before = store.rangeReads();
        long first = file.blockFirstOrdinals().get(3);
        file.read(first, 1).join();
        assertEquals(before + 1, store.rangeReads());
        file.read(first - 1, 2).join();
        assertEquals(before + 3, store.rangeReads());
        file.find(500).join();
        assertEquals(before + 5, store.rangeReads());
        assertEquals(1, store.reads());
    }

    @Test
    void cursorCoalescesRangesAndResumesFromArbitraryOrdinals() {
        RecordFileInfo info = write(3000);
        RecordFile file = open();
        int before = store.rangeReads();
        int ordinal = 0;
        try (var cursor = file.readAll()) {
            while (true) {
                var batch = cursor.next().join();
                if (batch.isEmpty()) {
                    break;
                }
                for (var record : batch) {
                    assertEquals(ordinal, record.ordinal());
                    assertArrayEquals(payload(ordinal++), record.bytes());
                }
            }
        }
        assertEquals(3000, ordinal);
        assertTrue(store.rangeReads() - before <= info.segments());
        try (var cursor = file.readFrom(1457, Cancellation.NONE)) {
            assertEquals(1457, cursor.next().join().getFirst().ordinal());
        }
        try (var cursor = file.readFrom(3000, Cancellation.NONE)) {
            assertEquals(List.of(), cursor.next().join());
        }
    }

    @Test
    void repeatedDataSharesSegmentsAndExistingManifestWins() {
        RecordFileInfo first = write(100);
        int objects = store.keys().size();
        byte[] manifest = store.read(key).join().bytes();
        RecordFileInfo second = write(100);
        assertTrue(second.manifestWasPresent());
        assertEquals(objects, store.keys().size());
        write(10);
        assertArrayEquals(manifest, store.read(key).join().bytes());
        assertEquals(100, open().count());
        assertFalse(first.manifestWasPresent());
    }

    @Test
    void abandonedWriterNeverPublishesAManifest() {
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            for (int i = 0; i < 100; i++) {
                writer.add(i, payload(i)).join();
            }
        }
        assertFalse(store.has(key).join());
        assertFalse(store.keys().isEmpty());
        assertEquals(ReadState.ABSENT, RecordFile.open(store, key).join().state());
    }

    @Test
    void validationFailuresAllowRetryButCompletedWriterDoesNot() {
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            writer.add(1, new byte[] {1}).join();
            assertThrows(CompletionException.class, () -> writer.add(1, new byte[] {2}).join());
            assertThrows(CompletionException.class, () -> writer.add(2, new byte[100001]).join());
            assertThrows(
                    CompletionException.class, () -> writer.complete(key, new byte[1001]).join());
            writer.add(2, new byte[] {2}).join();
            writer.complete(key, META).join();
            assertThrows(CompletionException.class, () -> writer.add(3, new byte[] {3}).join());
        }
        assertEquals(2, open().count());
    }

    @Test
    void cancelBeforeMutationAllowsRetry() {
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        try (RecordFileWriter writer = new RecordFileWriter(store)) {
            assertTrue(writer.add(1, new byte[] {1}, canceled).isCompletedExceptionally());
            writer.add(1, new byte[] {1}).join();
            writer.complete(key, META).join();
        }
        assertEquals(1, open().count());
    }

    @Test
    void corruptManifestHeadersNeverEscapeAsParserExceptions() {
        write(10);
        byte[] good = store.read(key).join().bytes();
        for (int offset : new int[] {0, 4, 6, 8, 16, 20, 24, 28, 32, 36, good.length - 1}) {
            byte[] bad = good.clone();
            bad[offset] ^= (byte) 0x80;
            replace(key, bad);
            assertEquals(
                    ReadState.CORRUPT,
                    RecordFile.open(store, key).join().state(),
                    "offset " + offset);
        }
        for (int n : new int[] {1, 4, 39}) {
            replace(key, Arrays.copyOf(good, n));
            assertEquals(ReadState.CORRUPT, RecordFile.open(store, key).join().state());
        }
    }

    @Test
    void directoryValidationRejectsOutOfRangeFramesAndCounts() {
        write(10);
        byte[] good = store.read(key).join().bytes();
        RecordFormat.Manifest original = RecordFormat.decode(good);
        var f = original.blocks().getFirst().frame();
        var badFrame =
                new RecordFormat.Frame(f.segment(), Integer.MAX_VALUE, f.compressed(), f.length());
        var bad =
                new RecordFormat.Manifest(
                        original.count(),
                        original.segments(),
                        List.of(new RecordFormat.Block(badFrame, 0)),
                        original.chunks(),
                        META);
        replace(key, RecordFormat.encode(bad, 9));
        assertEquals(ReadState.CORRUPT, RecordFile.open(store, key).join().state());
    }

    @Test
    void missingOrDamagedSegmentsFailWithoutReturningWrongRecords() {
        write(100);
        RecordFile file = open();
        GeneratedKey segment = file.keys().get(1);
        byte[] good = store.read(segment).join().bytes();
        store.damage(segment);
        corrupt(() -> file.read(0, 1).join());
        store.delete(List.of(segment)).join();
        corrupt(() -> file.read(0, 1).join());
        replace(segment, Arrays.copyOf(good, 1));
        corrupt(() -> file.read(0, 1).join());
    }

    @Test
    void zstdChecksumIsCheckedEvenWhenStoreChecksPass() {
        write(10);
        RecordFile file = open();
        GeneratedKey segment = file.keys().get(1);
        byte[] bytes = store.read(segment).join().bytes();
        RecordFormat.Manifest manifest = RecordFormat.decode(store.read(key).join().bytes());
        bytes[manifest.blocks().getFirst().frame().compressed() - 1] ^= 1;
        replace(segment, bytes);
        corrupt(() -> file.read(0, 1).join());
    }

    @Test
    void malformedBlocksAndIdIndexesAreCheckedCompletely() {
        write(10);
        RecordFormat.Manifest original = RecordFormat.decode(store.read(key).join().bytes());
        // A valid frame with an impossible record body must fail even for a short page.
        byte[] frame = RecordFormat.compress(new byte[] {1, 127}, 9);
        GeneratedKey segment = GeneratedKey.hash(frame);
        store.put(List.of(new Put(segment, frame))).join();
        var ref = new RecordFormat.Frame(0, 0, frame.length, 2);
        var bad =
                new RecordFormat.Manifest(
                        10,
                        List.of(new RecordFormat.Segment(segment, frame.length)),
                        List.of(new RecordFormat.Block(ref, 0)),
                        List.of(new RecordFormat.Chunk(ref, 10, 1)),
                        META);
        replace(key, RecordFormat.encode(bad, 9));
        RecordFile file = open();
        corrupt(() -> file.read(0, 1).join());
        corrupt(() -> file.find(1).join());
        assertNotNull(original);
    }

    @Test
    void varintsRejectOverflowAndTruncation() {
        for (byte[] bytes :
                new byte[][] {
                    {(byte) 128},
                    {
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        (byte) 128,
                        2
                    }
                }) {
            assertThrows(
                    GeneratedStoreException.class,
                    () -> RecordFormat.readVar(ByteBuffer.wrap(bytes)));
        }
        assertThrows(
                GeneratedStoreException.class,
                () -> RecordFormat.read32(ByteBuffer.wrap(new byte[] {-1, -1, -1, -1, 31})));
    }

    @Test
    void rangesAndPageLimitsAreValidated() {
        write(1);
        RecordFile file = open();
        for (long start : new long[] {-1, 2, Long.MAX_VALUE}) {
            assertThrows(CompletionException.class, () -> file.read(start, 1).join());
        }
        for (int n : new int[] {-1, RecordFile.MAX_PAGE_RECORDS + 1}) {
            assertThrows(CompletionException.class, () -> file.read(0, n).join());
        }
        assertThrows(IllegalArgumentException.class, () -> file.readFrom(-1, Cancellation.NONE));
        assertThrows(CompletionException.class, () -> file.find(0x1_0000_0000L).join());
    }

    @Test
    void concurrentPagesAndLookupsAreIndependent() {
        write(1000);
        RecordFile file = open();
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final int n = i;
            tasks.add(
                    file.find((1000 - n) * 2L)
                            .thenAccept(r -> assertArrayEquals(payload(n), r.bytes())));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
    }

    @Test
    void largeRecordsOccupyTheirOwnBlocks() {
        try (RecordFileWriter writer = new RecordFileWriter(store, small())) {
            writer.add(1, new byte[10]).join();
            writer.add(2, new byte[2000]).join();
            writer.add(3, new byte[10]).join();
            writer.complete(key, META).join();
        }
        assertEquals(List.of(0L, 1L, 2L), open().blockFirstOrdinals());
        assertEquals(2000, open().find(2).join().bytes().length);
    }
}
