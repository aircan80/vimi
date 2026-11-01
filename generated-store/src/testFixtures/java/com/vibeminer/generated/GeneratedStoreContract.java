package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consumer contracts shared by the memory fake and real disk store. */
public abstract class GeneratedStoreContract {
    protected GeneratedStore store;

    protected abstract GeneratedStore create();

    protected abstract void damage(GeneratedKey key, byte[] bytes) throws Exception;

    protected void closeStore() {}

    @BeforeEach
    final void start() {
        store = create();
    }

    @AfterEach
    final void stop() {
        closeStore();
    }

    public static GeneratedKey key(int i) {
        return GeneratedKey.hash(("key-" + i).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] bytes(int length) {
        byte[] b = new byte[length];
        new java.util.Random(42).nextBytes(b);
        return b;
    }

    @Test
    void unknownKeysAreAbsent() {
        assertFalse(store.has(key(1)).join());
        assertEquals(ReadState.ABSENT, store.read(key(1)).join().state());
        assertNull(store.read(key(1)).join().bytes());
        assertEquals(ReadState.ABSENT, store.readRange(key(1), 0, 4).join().state());
    }

    @Test
    void firstCommittedValueWinsAndBatchOutcomesStayOrdered() {
        byte[] bytes = bytes(5000);
        Put put = new Put(key(1), bytes);
        bytes[0] ^= 1;
        assertEquals(
                List.of(PutOutcome.STORED, PutOutcome.ALREADY_PRESENT, PutOutcome.STORED),
                store.put(
                                List.of(
                                        put,
                                        new Put(key(1), new byte[] {9}),
                                        new Put(key(2), new byte[] {8})))
                        .join());
        assertArrayEquals(bytes(5000), store.read(key(1)).join().bytes());
        assertTrue(store.has(key(1)).join());
        assertEquals(5000, store.read(key(1)).join().length());
        assertEquals(List.of(), store.put(List.of()).join());
    }

    @Test
    void invalidBatchStoresNothing() {
        CompletionException error =
                assertThrows(
                        CompletionException.class,
                        () ->
                                store.put(
                                                List.of(
                                                        new Put(key(1), new byte[] {1}),
                                                        new Put(key(2), new byte[0])))
                                        .join());
        assertEquals(GeneratedTypes.LIMIT, ((GeneratedStoreException) error.getCause()).code());
        assertFalse(store.has(key(1)).join());
        ArrayList<Put> batch = new ArrayList<>();
        batch.add(new Put(key(1), new byte[] {1}));
        batch.add(null);
        assertThrows(CompletionException.class, () -> store.put(batch).join());
        assertFalse(store.has(key(1)).join());
    }

    @Test
    void batchCountLimitIsValidatedBeforeWriting() {
        List<Put> batch = java.util.Collections.nCopies(4097, new Put(key(1), new byte[] {1}));
        assertThrows(CompletionException.class, () -> store.put(batch).join());
        assertFalse(store.has(key(1)).join());
        assertEquals(4096, store.put(batch.subList(0, 4096)).join().size());
    }

    @Test
    void rangesCrossBlocksAndEnforceBounds() {
        byte[] bytes = bytes(200000);
        store.put(List.of(new Put(key(1), bytes))).join();
        for (int[] r : new int[][] {{0, 1}, {65530, 20}, {199999, 1}, {200000, 0}}) {
            var read = store.readRange(key(1), r[0], r[1]).join();
            assertEquals(200000, read.length());
            assertArrayEquals(java.util.Arrays.copyOfRange(bytes, r[0], r[0] + r[1]), read.bytes());
        }
        for (long offset : new long[] {-1, 200000, Long.MAX_VALUE}) {
            assertThrows(
                    CompletionException.class, () -> store.readRange(key(1), offset, 1).join());
        }
    }

    @Test
    void damagedBytesAreNotReturnedAndBoundsStillTakePrecedence() throws Exception {
        byte[] bytes = bytes(200000);
        store.put(List.of(new Put(key(1), bytes))).join();
        damage(key(1), bytes);
        assertEquals(ReadState.CORRUPT, store.read(key(1)).join().state());
        assertNull(store.read(key(1)).join().bytes());
        assertEquals(ReadState.CORRUPT, store.readRange(key(1), 0, 200000).join().state());
        assertThrows(CompletionException.class, () -> store.readRange(key(1), 199999, 2).join());
        assertTrue(store.has(key(1)).join());
        store.delete(List.of(key(1))).join();
        store.put(List.of(new Put(key(1), bytes))).join();
        assertArrayEquals(bytes, store.read(key(1)).join().bytes());
    }

    @Test
    void deletesAndCompactionPreserveOtherObjects() {
        store.put(List.of(new Put(key(1), new byte[] {1}), new Put(key(2), new byte[] {2}))).join();
        store.delete(List.of(key(1), key(3))).join();
        store.compact(0, 4, Cancellation.NONE).join();
        assertFalse(store.has(key(1)).join());
        assertArrayEquals(new byte[] {2}, store.read(key(2)).join().bytes());
        assertEquals(
                List.of(PutOutcome.STORED),
                store.put(List.of(new Put(key(1), new byte[] {3}))).join());
    }

    @Test
    void canceledCallsDoNoWork() {
        Cancellation token = new Cancellation();
        token.cancel();
        List<java.util.function.Supplier<CompletableFuture<?>>> calls =
                List.of(
                        () -> store.has(key(1), token),
                        () -> store.read(key(1), token),
                        () -> store.readRange(key(1), 0, 1, token),
                        () -> store.put(List.of(new Put(key(1), new byte[] {1})), token),
                        () -> store.delete(List.of(key(1)), token));
        for (var call : calls) {
            try {
                call.get().join();
                throw new AssertionError("Expected cancellation");
            } catch (CancellationException e) {
                /* direct cancellation */
            } catch (CompletionException e) {
                assertTrue(e.getCause() instanceof CancellationException);
            }
        }
        assertFalse(store.has(key(1)).join());
    }

    @Test
    void concurrentCallsAgree() {
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(store.put(List.of(new Put(key(i), bytes(1000)))));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        for (int i = 0; i < 16; i++) {
            assertArrayEquals(bytes(1000), store.read(key(i)).join().bytes());
        }
    }
}
