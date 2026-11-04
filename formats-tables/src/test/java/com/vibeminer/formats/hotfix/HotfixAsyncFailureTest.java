package com.vibeminer.formats.hotfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Types;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HotfixAsyncFailureTest {
    @Test
    void publicInspectionTerminatesWithoutCallingForeignFailureAccessors() throws Exception {
        var getters = new AtomicInteger();
        var original =
                new AssertionError() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String getMessage() {
                        getters.incrementAndGet();
                        throw new AssertionError("Foreign message accessor invoked");
                    }
                };
        var source =
                new Db2ByteSource() {
                    public long length() {
                        return 44;
                    }

                    public List<Db2Types.Hole> holes() {
                        return List.of();
                    }

                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer bytes, Cancellation token) {
                        return CompletableFuture.failedFuture(original);
                    }
                };
        var result =
                HotfixCacheReader.inspect(
                        source, 70707, new HotfixCacheReader.Limits(), Cancellation.NONE);
        try {
            Throwable failure = result.handle((value, error) -> error).get(3, TimeUnit.SECONDS);
            if (failure != null && failure.getClass() == CompletionException.class) {
                failure = failure.getCause();
            }
            assertSame(original, failure);
            assertEquals(0, getters.get());
        } finally {
            result.cancel(false);
        }
    }
}
