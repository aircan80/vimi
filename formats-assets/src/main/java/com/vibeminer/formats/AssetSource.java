package com.vibeminer.formats;

import com.vibeminer.access.Cancellation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Caller-owned bounded random-access source. Implementations must return exactly the requested
 * bytes.
 */
public interface AssetSource {
    int MAX_READ_BYTES = 65536;

    long length();

    CompletableFuture<byte[]> readAt(long offset, int count, Cancellation cancellation);

    default boolean protectedRanges() {
        return false;
    }

    /** A source over a private copy of {@code source}: later changes to the array are not seen. */
    static AssetSource bytes(byte[] source) {
        return over(source.clone());
    }

    /**
     * A source over {@code source} itself, without copying it (#403). The caller must not change
     * the array while the source is in use: decoding a file one level at a time then costs no copy
     * of the whole file per level. Reads still return fresh arrays, so nothing reached through the
     * source can change the caller's bytes.
     */
    static AssetSource borrowed(byte[] source) {
        return over(java.util.Objects.requireNonNull(source));
    }

    private static AssetSource over(byte[] owned) {
        return new AssetSource() {
            public long length() {
                return owned.length;
            }

            public CompletableFuture<byte[]> readAt(long offset, int count, Cancellation token) {
                try {
                    token.check();
                    if (offset < 0
                            || count < 0
                            || count > MAX_READ_BYTES
                            || offset > owned.length - count) {
                        throw new IllegalArgumentException("Range outside source");
                    }
                    return CompletableFuture.completedFuture(
                            Arrays.copyOfRange(owned, (int) offset, (int) offset + count));
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        };
    }

    static AssetSource channel(SeekableByteChannel channel) throws IOException {
        long length = channel.size();
        return new AssetSource() {
            public long length() {
                try {
                    return channel.size();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }

            public CompletableFuture<byte[]> readAt(long offset, int count, Cancellation token) {
                try {
                    token.check();
                    if (offset < 0
                            || count < 0
                            || count > MAX_READ_BYTES
                            || offset > length - count) {
                        throw new IllegalArgumentException("Range outside source");
                    }
                    byte[] bytes = new byte[count];
                    synchronized (channel) {
                        channel.position(offset);
                        ByteBuffer output = ByteBuffer.wrap(bytes);
                        while (output.hasRemaining()) {
                            token.check();
                            if (channel.read(output) < 0) {
                                throw new java.io.EOFException();
                            }
                        }
                    }
                    return CompletableFuture.completedFuture(bytes);
                } catch (IOException | RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        };
    }
}
