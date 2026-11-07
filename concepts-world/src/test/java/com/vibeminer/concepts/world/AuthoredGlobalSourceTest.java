package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
class AuthoredGlobalSourceTest {
    @Test
    void fixtureUsesActualNativeSourceFactoriesAndOwnsReturnedResource() throws Exception {
        try (var f = new AuthoredGlobalSource()) {
            var closed = new AtomicInteger();
            try (var written =
                    f.source()
                            .prepareAndWrite(
                                    f.scope(),
                                    Cancellation.NONE,
                                    (borrow, actual) -> {
                                        assertTrue(actual != Cancellation.NONE);
                                        assertEquals(3, borrow.vertices());
                                        assertEquals(6, borrow.indices());
                                        assertEquals(4, borrow.facts().sources().size());
                                        assertEquals(1, borrow.imageCount());
                                        assertEquals(
                                                0,
                                                borrow.imageByte(0, 3),
                                                "the source alpha is kept (#297)");
                                        return (AutoCloseable) closed::incrementAndGet;
                                    })) {
                assertTrue(written.isOpen());
                assertEquals(1, f.payloadCredits());
            }
            assertEquals(1, closed.get());
            assertEquals(0, f.payloadCredits());
            assertEquals(1, f.opened(AuthoredGlobalSource.ROOT));
            assertEquals(0, f.borrowedClosed());
            assertEquals(0, f.fullStats(), "global source reads bind index facts only");
        }
    }

    @Test
    void heldActualReadKeepsSourceDrainPendingUntilOriginalFailureRetires() throws Exception {
        try (var f = new AuthoredGlobalSource()) {
            var gate = f.holdNextRead(AuthoredGlobalSource.GROUP);
            var result = new CompletableFuture<Throwable>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    f.source()
                                            .prepareAndWrite(
                                                    f.scope(),
                                                    Cancellation.NONE,
                                                    (borrow, token) -> {
                                                        throw new AssertionError(
                                                                "Held/canceled read reached writer");
                                                    });
                                    result.completeExceptionally(
                                            new AssertionError("Canceled Source escaped"));
                                } catch (Throwable failure) {
                                    result.complete(failure);
                                }
                            });
            try {
                gate.entered().get(3, TimeUnit.SECONDS);
                var drain = f.closeAsync();
                gate.canceled().get(3, TimeUnit.SECONDS);
                assertFalse(drain.isDone());
                assertFalse(result.isDone());
                var original = new CancellationException();
                gate.fail(original);
                assertSame(original, result.get(3, TimeUnit.SECONDS));
                drain.get(3, TimeUnit.SECONDS);
                assertEquals(0, f.payloadCredits());
                assertTrue(f.closedStreams() > 0);
            } finally {
                f.close();
                result.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void writerBeforeFinalChecksCannotPublishChangedSourceAndClosesUnreturnedResource()
            throws Exception {
        try (var f = new AuthoredGlobalSource()) {
            var written = new AtomicInteger();
            var closed = new AtomicInteger();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.source()
                                    .prepareAndWrite(
                                            f.scope(),
                                            Cancellation.NONE,
                                            (borrow, token) -> {
                                                written.incrementAndGet();
                                                f.markChanged(AuthoredGlobalSource.WDT);
                                                return (AutoCloseable) closed::incrementAndGet;
                                            }));
            assertEquals(1, written.get());
            assertEquals(1, closed.get());
            assertEquals(0, f.payloadCredits());
        }
    }
}
