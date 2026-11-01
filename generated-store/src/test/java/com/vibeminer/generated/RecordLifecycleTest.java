package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.GeneratedTypes.CompactReport;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.Read;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class RecordLifecycleTest {
    private static final GeneratedKey KEY = GeneratedKey.hash(new byte[] {1});
    private static final RecordFileOptions SMALL = new RecordFileOptions(100, 10000, 100, 1, 1, 9);

    private static final class GateStore implements GeneratedStore {
        final MemoryGeneratedStore memory = new MemoryGeneratedStore();
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> gate = new CompletableFuture<>();

        @Override
        public CompletableFuture<Boolean> has(GeneratedKey key, Cancellation token) {
            return memory.has(key, token);
        }

        @Override
        public CompletableFuture<Read> read(GeneratedKey key, Cancellation token) {
            return memory.read(key, token);
        }

        @Override
        public CompletableFuture<Read> readRange(
                GeneratedKey key, long offset, int length, Cancellation token) {
            return memory.readRange(key, offset, length, token);
        }

        @Override
        public CompletableFuture<List<PutOutcome>> put(List<Put> batch, Cancellation token) {
            entered.countDown();
            return gate.thenCompose(ignored -> memory.put(batch, token));
        }

        @Override
        public CompletableFuture<Void> delete(List<GeneratedKey> keys, Cancellation token) {
            return memory.delete(keys, token);
        }

        @Override
        public CompletableFuture<CompactReport> compact(
                double fraction, int packs, Cancellation token) {
            return memory.compact(fraction, packs, token);
        }
    }

    @Test
    void busyWriterRejectsConcurrentCallsAndCloseDoesNotResurrectIt() throws Exception {
        GateStore store = new GateStore();
        RecordFileWriter writer = new RecordFileWriter(store, SMALL);
        try {
            writer.add(1, new byte[] {1}).join();
            var pending = writer.add(2, new byte[] {2});
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            assertThrows(CompletionException.class, () -> writer.add(3, new byte[] {3}).join());
            writer.close();
            store.gate.complete(null);
            pending.join();
            assertThrows(CompletionException.class, () -> writer.complete(KEY, new byte[0]).join());
            assertFalse(store.memory.has(KEY).join());
        } finally {
            store.gate.complete(null);
            writer.close();
        }
    }

    @Test
    void storageFailurePermanentlyFinishesWriterWithoutManifest() throws Exception {
        GateStore store = new GateStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, SMALL)) {
            writer.add(1, new byte[] {1}).join();
            var pending = writer.add(2, new byte[] {2});
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            store.gate.completeExceptionally(
                    new GeneratedStoreException(GeneratedTypes.WRITE_FAILED, "scripted"));
            assertThrows(CompletionException.class, pending::join);
            assertThrows(CompletionException.class, () -> writer.add(3, new byte[] {3}).join());
            assertFalse(store.memory.has(KEY).join());
        } finally {
            store.gate.complete(null);
        }
    }

    @Test
    void cancellationAfterMutationFinishesWriter() throws Exception {
        GateStore store = new GateStore();
        Cancellation token = new Cancellation();
        try (RecordFileWriter writer = new RecordFileWriter(store, SMALL)) {
            writer.add(1, new byte[] {1}).join();
            var pending = writer.add(2, new byte[] {2}, token);
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            token.cancel();
            store.gate.complete(null);
            assertTrue(pending.handle((value, failure) -> failure != null).join());
            assertThrows(CompletionException.class, () -> writer.complete(KEY, new byte[0]).join());
        } finally {
            store.gate.complete(null);
        }
    }

    @Test
    void asynchronousInputIsOwnedBeforeTheCallerCanChangeIt() throws Exception {
        GateStore store = new GateStore();
        try (RecordFileWriter writer = new RecordFileWriter(store, SMALL)) {
            writer.add(1, new byte[] {1}).join();
            byte[] bytes = {2};
            var pending = writer.add(2, bytes);
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            bytes[0] = 9;
            store.gate.complete(null);
            pending.join();
            writer.complete(KEY, new byte[0]).join();
            assertArrayEquals(
                    new byte[] {2},
                    RecordFile.open(store, KEY).join().file().find(2).join().bytes());
        } finally {
            store.gate.complete(null);
        }
    }

    @Test
    void cancellationWhileQueuedLeavesWriterUsable() throws Exception {
        int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        CountDownLatch entered = new CountDownLatch(workers), release = new CountDownLatch(1);
        List<CompletableFuture<Void>> blockers = new ArrayList<>();
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        try (RecordFileWriter writer = new RecordFileWriter(store)) {
            try {
                for (int i = 0; i < workers; i++) {
                    blockers.add(
                            RecordTasks.submit(
                                    () -> {
                                        entered.countDown();
                                        try {
                                            if (!release.await(10, TimeUnit.SECONDS)) {
                                                throw new AssertionError("Timed out");
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new AssertionError(e);
                                        }
                                        return null;
                                    }));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Cancellation token = new Cancellation();
                CompletableFuture<Void> queued = writer.add(1, new byte[] {1}, token);
                token.cancel();
                release.countDown();
                assertTrue(queued.handle((value, error) -> error != null).join());
                writer.add(1, new byte[] {1}).join();
                writer.complete(KEY, new byte[0]).join();
                assertEquals(1, RecordFile.open(store, KEY).join().file().count());
            } finally {
                release.countDown();
                CompletableFuture.allOf(blockers.toArray(CompletableFuture[]::new)).join();
            }
        }
    }

    @Test
    void boundedWorkersRejectOverflowWithoutRunningRejectedWork() throws Exception {
        int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        CountDownLatch entered = new CountDownLatch(workers), release = new CountDownLatch(1);
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                accepted.add(
                        RecordTasks.submit(
                                () -> {
                                    entered.countDown();
                                    try {
                                        if (!release.await(10, TimeUnit.SECONDS)) {
                                            throw new AssertionError("Timed out");
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(e);
                                    }
                                    return null;
                                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) {
                accepted.add(RecordTasks.submit(() -> null));
            }
            CompletionException e =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    RecordTasks.submit(
                                                    () -> {
                                                        throw new AssertionError(
                                                                "Rejected work ran");
                                                    })
                                            .join());
            assertEquals(GeneratedTypes.LIMIT, ((GeneratedStoreException) e.getCause()).code());
        } finally {
            release.countDown();
            CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).join();
        }
    }
}
