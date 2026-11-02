package com.vibeminer.formats.db2;

import com.vibeminer.access.Cancellation;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Immutable decoded bytes, with explicit unreadable original chunk ranges. */
public interface Db2ByteSource {
    long length();

    List<Db2Types.Hole> holes();

    CompletableFuture<Void> readRange(long offset, ByteBuffer destination, Cancellation token);

    static Db2ByteSource memory(byte[] bytes) {
        byte[] owned = bytes.clone();
        return new Db2ByteSource() {
            @Override
            public long length() {
                return owned.length;
            }

            @Override
            public List<Db2Types.Hole> holes() {
                return List.of();
            }

            @Override
            public CompletableFuture<Void> readRange(
                    long offset, ByteBuffer destination, Cancellation token) {
                token.check();
                if (offset < 0
                        || offset > owned.length
                        || destination.remaining() > owned.length - offset) {
                    return CompletableFuture.failedFuture(new IndexOutOfBoundsException());
                }
                destination.put(owned, (int) offset, destination.remaining());
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
