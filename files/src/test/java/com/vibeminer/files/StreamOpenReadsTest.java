package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.store.ObjectKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A stream reuses the encoded bytes its open already fetched instead of fetching them again. */
@Timeout(10)
final class StreamOpenReadsTest {
    private final AtomicInteger ranges = new AtomicInteger();

    private ObjectSource counting(ObjectSource inner) {
        return (ObjectSource)
                Proxy.newProxyInstance(
                        ObjectSource.class.getClassLoader(),
                        new Class<?>[] {ObjectSource.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getRange")) {
                                ranges.incrementAndGet();
                            }
                            try {
                                return method.invoke(inner, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    private FilesScenario scenario() {
        return new FilesScenario(false, FileSystemOptions.defaults(), this::counting);
    }

    private static BuildFiles open(FilesScenario s) {
        return s.files.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files();
    }

    private static byte[] pattern(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + seed);
        }
        return bytes;
    }

    private static byte[] readAll(FileReadStream stream) {
        var buffer = ByteBuffer.allocate(Math.toIntExact(stream.length()));
        while (buffer.hasRemaining()) {
            assertTrue(stream.read(buffer, Cancellation.NONE).join() > 0);
        }
        assertEquals(-1, stream.read(ByteBuffer.allocate(1), Cancellation.NONE).join());
        return buffer.array();
    }

    /** Small chunks: open fetches the whole body once and reads fetch nothing more. */
    @Test
    void smallChunkedFileIsFetchedOnceAcrossOpenAndRead() {
        var s = scenario();
        List<FileFixtures.Chunk> chunks = new ArrayList<>();
        byte[] decoded = new byte[0];
        for (int c = 0; c < 4; c++) {
            byte[] part = pattern(3000 + c, c);
            chunks.add(c % 2 == 0 ? FileFixtures.zlib(part) : FileFixtures.raw(part));
            decoded = concat(decoded, part);
        }
        s.addChunks(7, chunks, decoded);
        try (var h = open(s)) {
            var opened = h.openRead(FileRef.id(7), Cancellation.NONE).join();
            assertEquals(FileReadState.COMPLETE, opened.state());
            int atOpen = ranges.get();
            try (var stream = opened.stream()) {
                assertArrayEquals(decoded, readAll(stream));
                assertEquals(atOpen, ranges.get(), "reads fetched bytes the open already had");
                stream.seek(1);
                var again = ByteBuffer.allocate(10);
                stream.read(again, Cancellation.NONE).join();
                assertArrayEquals(java.util.Arrays.copyOfRange(decoded, 1, 11), again.array());
                assertEquals(atOpen, ranges.get());
            }
        }
    }

    /** Chunk hashes are still checked for retained bytes. */
    @Test
    void retainedChunkWithBadHashStillFailsAsCorrupt() {
        var s = scenario();
        byte[] first = pattern(2000, 1), second = pattern(2000, 2);
        s.addChunks(
                8,
                List.of(FileFixtures.raw(first), FileFixtures.raw(second)),
                concat(first, second));
        var body = s.encoded.get(8L);
        byte[] damaged = body.bytes().clone();
        damaged[damaged.length - 1] ^= 1; // last payload byte of the second chunk
        s.objects.put(new ObjectKey(ObjectKey.ENCODED, body.key().bytes()), damaged);
        try (var h = open(s);
                var stream = h.openRead(FileRef.id(8), Cancellation.NONE).join().stream()) {
            var buffer = ByteBuffer.allocate(4000);
            assertEquals(2000, stream.read(buffer, Cancellation.NONE).join());
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () -> stream.read(buffer, Cancellation.NONE).join());
            assertTrue(failure.getCause() instanceof FileReadException);
            assertEquals(FileReadState.CORRUPT, ((FileReadException) failure.getCause()).state());
        }
    }

    /** Encrypted holes are still found at open; readable chunks come from the retained body. */
    @Test
    void holesAreReportedAndReadableChunksNeedNoFetch() {
        var s = scenario();
        byte[] decoded = pattern(6000, 3);
        s.add(9, null, decoded, true, List.of(new FileHole(2000, 2000, "0123456789ABCDEF")));
        try (var h = open(s)) {
            var opened = h.openRead(FileRef.id(9), Cancellation.NONE).join();
            assertEquals(FileReadState.PARTIAL, opened.state());
            int atOpen = ranges.get();
            try (var stream = opened.stream()) {
                assertEquals(List.of(new FileHole(2000, 2000, "0123456789ABCDEF")), stream.holes());
                var head = ByteBuffer.allocate(2000);
                assertEquals(2000, stream.read(head, Cancellation.NONE).join());
                assertArrayEquals(java.util.Arrays.copyOf(decoded, 2000), head.array());
                assertThrows(
                        CompletionException.class,
                        () -> stream.read(ByteBuffer.allocate(1), Cancellation.NONE).join());
                stream.seek(4000);
                var tail = ByteBuffer.allocate(2000);
                assertEquals(2000, stream.read(tail, Cancellation.NONE).join());
                assertArrayEquals(java.util.Arrays.copyOfRange(decoded, 4000, 6000), tail.array());
                assertEquals(atOpen, ranges.get());
            }
        }
    }

    /** Over the retained bound, reads keep fetching their own chunk; results are unchanged. */
    @Test
    void largeChunksAreStillFetchedPerRead() {
        var s = scenario();
        byte[] first = pattern(70000, 4), second = pattern(70000, 5);
        s.addChunks(
                10,
                List.of(FileFixtures.raw(first), FileFixtures.zlib(second)),
                concat(first, second));
        try (var h = open(s);
                var stream = h.openRead(FileRef.id(10), Cancellation.NONE).join().stream()) {
            int atOpen = ranges.get();
            assertArrayEquals(concat(first, second), readAll(stream));
            assertEquals(atOpen + 2, ranges.get());
        }
    }

    @Test
    void closedStreamRefusesReads() {
        var s = scenario();
        byte[] decoded = pattern(100, 6);
        s.addChunks(11, List.of(FileFixtures.raw(decoded)), decoded);
        try (var h = open(s)) {
            var stream = h.openRead(FileRef.id(11), Cancellation.NONE).join().stream();
            stream.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> stream.read(ByteBuffer.allocate(1), Cancellation.NONE));
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
