package com.vibeminer.formats.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadException;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class Db2FileSourceTest {
    private static BuildFiles files(FileReadStream stream) {
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (object, method, args) -> {
                            if (method.getName().equals("openRead")) {
                                return CompletableFuture.completedFuture(
                                        new FileTypes.FileOpen(
                                                FileTypes.FileReadState.COMPLETE,
                                                stream,
                                                null,
                                                null,
                                                null));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    @Test
    void failedSpeculativeBlockDoesNotPoisonReadableExactRanges() {
        var oversized = new AtomicInteger();
        var closed = new AtomicInteger();
        var stream =
                new FileReadStream() {
                    long at;

                    @Override
                    public long length() {
                        return 1024;
                    }

                    @Override
                    public long position() {
                        return at;
                    }

                    @Override
                    public void seek(long position) {
                        at = position;
                    }

                    @Override
                    public List<FileTypes.FileHole> holes() {
                        return List.of();
                    }

                    @Override
                    public CompletableFuture<Integer> read(
                            ByteBuffer destination, Cancellation token) {
                        if (destination.remaining() > 4) {
                            oversized.incrementAndGet();
                            return CompletableFuture.failedFuture(
                                    new FileReadException(
                                            FileTypes.FileReadState.UNAVAILABLE,
                                            "CDN_OFFLINE",
                                            "Speculative distant range failed",
                                            null));
                        }
                        int count = destination.remaining();
                        while (destination.hasRemaining()) {
                            destination.put((byte) at++);
                        }
                        return CompletableFuture.completedFuture(count);
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
        var source =
                Db2FileSource.open(files(stream), FileTypes.FileRef.id(1), Cancellation.NONE)
                        .join()
                        .source();
        var first = ByteBuffer.allocate(4);
        source.readRange(0, first, Cancellation.NONE).join();
        var second = ByteBuffer.allocate(4);
        source.readRange(4, second, Cancellation.NONE).join();
        assertEquals(1, oversized.get());
        assertEquals(0, first.get(0));
        assertEquals(4, second.get(0));
        source.close();
        source.close();
        assertEquals(1, closed.get());
    }

    @Test
    void typedFilesOpenKeepsDatedReadFailureAndClosesStream() {
        var closed = new AtomicInteger();
        var at = java.time.Instant.EPOCH;
        var stream =
                new FileReadStream() {
                    @Override
                    public long length() {
                        return 288;
                    }

                    @Override
                    public long position() {
                        return 0;
                    }

                    @Override
                    public void seek(long position) {}

                    @Override
                    public List<FileTypes.FileHole> holes() {
                        return List.of();
                    }

                    @Override
                    public CompletableFuture<Integer> read(
                            ByteBuffer destination, Cancellation token) {
                        return CompletableFuture.failedFuture(
                                new FileReadException(
                                        FileTypes.FileReadState.MISSING,
                                        "CDN_NOT_FOUND",
                                        "Dated404",
                                        at));
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        try (var opened =
                Db2Reader.open(
                                files(stream),
                                FileTypes.FileRef.id(1),
                                definition,
                                "1.2.3.4",
                                new Db2Types.Limits(),
                                Cancellation.NONE)
                        .join()) {
            assertEquals(Db2FileSource.OpenState.MISSING, opened.state());
            assertEquals("CDN_NOT_FOUND", opened.code());
            assertEquals(at, opened.observedAt());
            assertNull(opened.table());
        }
        assertEquals(1, closed.get());
    }
}
