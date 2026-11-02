package com.vibeminer.formats;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileReadStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AssetSources {
    private AssetSources() {}

    /**
     * The adapter does not close the stream. Random reads serialize on a private adapter lock,
     * never the stream monitor. Do not use separate adapters for one stream concurrently.
     */
    public static AssetSource file(FileReadStream stream) {
        Objects.requireNonNull(stream);
        Object transaction = new Object();
        return new AssetSource() {
            public long length() {
                return stream.length();
            }

            public boolean protectedRanges() {
                return !stream.holes().isEmpty();
            }

            public CompletableFuture<byte[]> readAt(long offset, int count, Cancellation token) {
                return AssetTasks.run(
                        () -> {
                            token.check();
                            if (offset < 0
                                    || count < 0
                                    || count > MAX_READ_BYTES
                                    || offset > stream.length() - count) {
                                throw new IllegalArgumentException("Range outside source");
                            }
                            byte[] bytes = new byte[count];
                            ByteBuffer buffer = ByteBuffer.wrap(bytes);
                            synchronized (transaction) {
                                stream.seek(offset);
                                while (buffer.hasRemaining()) {
                                    token.check();
                                    int read = stream.read(buffer, token).join();
                                    if (read < 0) {
                                        throw new java.util.concurrent.CompletionException(
                                                new java.io.EOFException());
                                    }
                                }
                            }
                            return bytes;
                        });
            }
        };
    }
}
