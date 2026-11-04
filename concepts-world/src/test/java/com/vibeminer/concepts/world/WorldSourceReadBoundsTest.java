package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Negative raw-input guards only; no geometry/material/source-positive capability is forged. */
@SuppressWarnings("try")
class WorldSourceReadBoundsTest {
    @Test
    void contradictoryLaterLengthCannotIncreaseAllocationBeyondFirstBoundedSnapshot()
            throws Exception {
        var calls = new AtomicInteger();
        var closed = new AtomicInteger();
        var maximum = new AtomicInteger();
        var files = files(32, closed, maximum, () -> calls.incrementAndGet() < 4 ? 32 : 65);
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                try (var input = read(source, 64)) {
                    assertTrue(input != null);
                }
                assertEquals(
                        1,
                        calls.get(),
                        "Length is an immutable observation, never queried for allocation again");
                assertTrue(
                        maximum.get() <= 64,
                        "Contradictory provider cannot bypass the caller's small cap");
                assertEquals(1, closed.get());
            } finally {
                source.closeAsync().join();
            }
        } finally {
            tables.close();
        }
    }

    @Test
    void extraByteAfterDeclaredStreamLengthRejectsUnderreportedInput() throws Exception {
        var closed = new AtomicInteger();
        var maximum = new AtomicInteger();
        var files = files(33, closed, maximum, () -> 32);
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(RuntimeException.class, () -> read(source, 64));
                assertEquals(
                        1, closed.get(), "Rejected extra data still retires the actual stream");
                assertTrue(maximum.get() <= 64);
            } finally {
                source.closeAsync().join();
            }
        } finally {
            tables.close();
        }
    }

    private static WorldTerrainTextureSourceTest.Files files(
            int actual,
            AtomicInteger streamCloses,
            AtomicInteger maximum,
            java.util.function.LongSupplier length) {
        return new WorldTerrainTextureSourceTest.Files() {
            @Override
            FileTypes.FileStat stat(long id, Cancellation token) {
                if (id != 2) {
                    return super.stat(id, token);
                }
                return new FileTypes.FileStat(
                        FileTypes.FileLookupState.FOUND,
                        new FileTypes.BuildFile(
                                2,
                                null,
                                FileTypes.FilePathSource.NONE,
                                32L,
                                "2".repeat(32),
                                FileTypes.FileFormat.UNKNOWN,
                                FileTypes.FileFormatSource.NONE,
                                FileTypes.FileStorage.STORED,
                                false));
            }

            @Override
            FileReadStream open(long id, Cancellation token) {
                if (id != 2) {
                    return super.open(id, token);
                }
                return new FileReadStream() {
                    int position;

                    public long length() {
                        return length.getAsLong();
                    }

                    public long position() {
                        return position;
                    }

                    public void seek(long value) {
                        position = Math.toIntExact(value);
                    }

                    public List<FileTypes.FileHole> holes() {
                        return List.of();
                    }

                    public CompletableFuture<Integer> read(ByteBuffer target, Cancellation cancel) {
                        maximum.accumulateAndGet(target.capacity(), Math::max);
                        if (target.capacity() > 64) {
                            throw new AssertionError(
                                    "Small source cap bypassed before provider read");
                        }
                        int n = Math.min(actual - position, target.remaining());
                        for (int i = 0; i < n; i++) {
                            target.put((byte) 0);
                        }
                        position += n;
                        return CompletableFuture.completedFuture(n);
                    }

                    public void close() {
                        streamCloses.incrementAndGet();
                    }
                };
            }
        };
    }

    private static AutoCloseable read(WorldStreamScopes source, int limit) throws Exception {
        var method =
                WorldStreamScopes.class.getDeclaredMethod(
                        "read", long.class, int.class, Cancellation.class);
        method.setAccessible(true);
        try {
            return (AutoCloseable) method.invoke(source, 2L, limit, Cancellation.NONE);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }
}
