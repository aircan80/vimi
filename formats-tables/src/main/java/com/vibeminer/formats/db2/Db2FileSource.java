package com.vibeminer.formats.db2;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** Serialized layer4 source with four256KiB LRU blocks; holes never become cached plaintext. */
public final class Db2FileSource implements Db2ByteSource, AutoCloseable {
    public static final int BLOCK_SIZE = 256 << 10, MAX_BLOCKS = 4;

    public enum OpenState {
        OPEN,
        UNKNOWN_FILE,
        NOT_IN_LOCALE,
        NOT_IN_ENCODING,
        MISSING,
        NEEDS_NETWORK,
        UNAVAILABLE,
        CORRUPT,
        UNSUPPORTED,
        WRONG_KEY,
        STORE_FAILED,
        TOO_LARGE,
        PROTECTED,
        REJECTED
    }

    public record Open(
            OpenState state,
            Db2FileSource source,
            String code,
            String message,
            Instant observedAt) {}

    private final FileReadStream stream;
    private final Semaphore gate = new Semaphore(1);
    private final List<Db2Types.Hole> holes;
    private final LinkedHashMap<Long, byte[]> blocks = new LinkedHashMap<>(4, .75f, true);
    private final java.util.Set<Long> unkept = new java.util.HashSet<>();
    private volatile boolean closed;

    private Db2FileSource(FileReadStream stream) {
        this.stream = stream;
        holes =
                stream.holes().stream()
                        .map(h -> new Db2Types.Hole(h.offset(), h.length(), h.keyName()))
                        .toList();
    }

    public static CompletableFuture<Open> open(BuildFiles files, FileRef file, Cancellation token) {
        return files.openRead(file, token)
                .thenApply(
                        open -> {
                            if (open.stream() == null) {
                                return new Open(
                                        state(open.state()),
                                        null,
                                        open.code(),
                                        open.message(),
                                        open.observedAt());
                            }
                            try {
                                return new Open(
                                        OpenState.OPEN,
                                        new Db2FileSource(open.stream()),
                                        null,
                                        null,
                                        null);
                            } catch (RuntimeException e) {
                                open.stream().close();
                                throw e;
                            }
                        });
    }

    private static OpenState state(FileReadState state) {
        try {
            return OpenState.valueOf(state.name());
        } catch (IllegalArgumentException e) {
            return OpenState.CORRUPT;
        }
    }

    @Override
    public long length() {
        return stream.length();
    }

    @Override
    public List<Db2Types.Hole> holes() {
        return holes;
    }

    public List<String> chunkKeys() {
        return stream.chunkKeys();
    }

    @Override
    public CompletableFuture<Void> readRange(
            long offset, ByteBuffer destination, Cancellation token) {
        if (offset < 0 || offset > length() || destination.remaining() > length() - offset) {
            throw new IllegalArgumentException("Range outside file");
        }
        return CompletableFuture.runAsync(
                () -> {
                    boolean acquired = false;
                    try {
                        while (!(acquired =
                                gate.tryAcquire(20, java.util.concurrent.TimeUnit.MILLISECONDS))) {
                            token.check();
                        }
                        token.check();
                        if (closed) {
                            throw new IllegalStateException("Source closed");
                        }
                        if (!destination.hasRemaining()) {
                            return;
                        }
                        if (destination.remaining() < BLOCK_SIZE) {
                            long first = offset / BLOCK_SIZE,
                                    last = (offset + destination.remaining() - 1) / BLOCK_SIZE;
                            boolean safe = true;
                            for (long index = first; index <= last; index++) {
                                long start = index * BLOCK_SIZE,
                                        end = Math.min(length(), start + BLOCK_SIZE);
                                if (unkept.contains(index)
                                        || holes.stream()
                                                .anyMatch(
                                                        h ->
                                                                h.offset() < end
                                                                        && h.offset() + h.length()
                                                                                > start)) {
                                    safe = false;
                                    break;
                                }
                            }
                            if (safe) {
                                long pos = offset;
                                int originalPosition = destination.position();
                                while (destination.hasRemaining()) {
                                    long index = pos / BLOCK_SIZE;
                                    byte[] bytes = blocks.get(index);
                                    if (bytes == null) {
                                        var block =
                                                ByteBuffer.allocate(
                                                        (int)
                                                                Math.min(
                                                                        BLOCK_SIZE,
                                                                        length()
                                                                                - index
                                                                                        * BLOCK_SIZE));
                                        try {
                                            fill(index * BLOCK_SIZE, block, token);
                                        } catch (Db2SourceException failure) {
                                            java.util.Arrays.fill(block.array(), (byte) 0);
                                            unkept.add(index);
                                            destination.position(originalPosition);
                                            fill(offset, destination, token);
                                            return;
                                        }
                                        bytes = block.array();
                                        blocks.put(index, bytes);
                                        while (blocks.size() > MAX_BLOCKS) {
                                            var evicted =
                                                    blocks.remove(
                                                            blocks.keySet().iterator().next());
                                            java.util.Arrays.fill(evicted, (byte) 0);
                                        }
                                    }
                                    int within = (int) (pos % BLOCK_SIZE),
                                            n =
                                                    Math.min(
                                                            destination.remaining(),
                                                            bytes.length - within);
                                    destination.put(bytes, within, n);
                                    pos += n;
                                }
                                return;
                            }
                        }
                        fill(offset, destination, token);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException();
                    } finally {
                        if (acquired) {
                            gate.release();
                        }
                    }
                },
                command -> Thread.ofVirtual().start(command));
    }

    private void fill(long offset, ByteBuffer destination, Cancellation token) {
        stream.seek(offset);
        while (destination.hasRemaining()) {
            token.check();
            int n;
            try {
                n = stream.read(destination, token).join();
            } catch (RuntimeException error) {
                Throwable cause = error;
                while (cause instanceof java.util.concurrent.CompletionException
                        && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof com.vibeminer.files.FileHoleException hole) {
                    throw new Db2SourceException(
                            OpenState.PROTECTED, "DB2_SOURCE_PROTECTED", hole.getMessage(), null);
                }
                if (cause instanceof com.vibeminer.files.FileReadException failure) {
                    throw new Db2SourceException(
                            state(failure.state()),
                            failure.code(),
                            failure.getMessage(),
                            failure.observedAt());
                }
                throw error;
            }
            if (n <= 0) {
                throw new Db2SourceException(
                        OpenState.CORRUPT,
                        "DB2_SOURCE_SHORT",
                        "The file ended before its declared length",
                        null);
            }
        }
    }

    @Override
    public void close() {
        gate.acquireUninterruptibly();
        try {
            if (!closed) {
                closed = true;
                for (var block : blocks.values()) {
                    java.util.Arrays.fill(block, (byte) 0);
                }
                blocks.clear();
                unkept.clear();
                stream.close();
            }
        } finally {
            gate.release();
        }
    }
}
