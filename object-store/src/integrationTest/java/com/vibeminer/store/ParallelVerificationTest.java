package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
final class ParallelVerificationTest {
    @TempDir Path folder;
    private ObjectStore store;
    private final CountDownLatch release = new CountDownLatch(1);
    private final byte[] baseline = {1, 2, 3};

    @BeforeEach
    void open() {
        store = ObjectStore.open(folder).join();
        store.put(List.of(new PutObject(key(baseline), baseline))).join();
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        store.verificationProbe = null;
        store.writeProbe = null;
        store.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private void pause() {
        try {
            assertTrue(release.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void verificationRunsOffCallerAndWriterWhileCommittedReadsRemainUsable() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> verifier = new AtomicReference<>();
        store.verificationProbe =
                beginning -> {
                    if (beginning) {
                        verifier.set(Thread.currentThread());
                        entered.countDown();
                        pause();
                    }
                };
        byte[] bytes = {4, 5, 6};
        var putting = store.put(List.of(new PutObject(key(bytes), bytes)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertFalse(putting.isDone());
        assertFalse(caller == verifier.get());
        assertTrue(verifier.get().getName().startsWith("vimi-store-verify-"));
        assertEquals(
                ReadState.PRESENT, store.read(key(baseline)).get(10, TimeUnit.SECONDS).state());
        release.countDown();
        assertEquals(PutOutcome.STORED, putting.get(10, TimeUnit.SECONDS).getFirst().outcome());
        assertArrayEquals(bytes, store.read(key(bytes)).join().bytes());
    }

    @Test
    void admittedPreparationDrainsOnCloseAndInputRemainsOwned() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.verificationProbe =
                beginning -> {
                    if (beginning) {
                        entered.countDown();
                        pause();
                    }
                };
        byte[] bytes = {4, 5, 6};
        PutObject input = new PutObject(key(bytes), bytes);
        var putting = store.put(List.of(input));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        bytes[0] = 99;
        input.bytes()[1] = 99;
        var closing = store.closeAsync();
        assertFalse(closing.isDone());
        release.countDown();
        assertEquals(PutOutcome.STORED, putting.get(10, TimeUnit.SECONDS).getFirst().outcome());
        closing.get(10, TimeUnit.SECONDS);
        assertTrue(store.verificationWorkers.isTerminated());
        store = ObjectStore.open(folder).join();
        assertArrayEquals(new byte[] {4, 5, 6}, store.read(input.key()).join().bytes());
    }

    @Test
    void aCanceledAdmittedBatchKeepsRefusalPrecedenceAndNeverAppendsValidItems() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.verificationProbe =
                beginning -> {
                    if (beginning) {
                        entered.countDown();
                        pause();
                    }
                };
        byte[] bytes = {4, 5, 6};
        Cancellation token = new Cancellation();
        var putting =
                store.put(
                        List.of(
                                new PutObject(key(bytes), bytes),
                                new PutObject(key(new byte[] {9}), bytes)),
                        false,
                        token);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        token.cancel();
        release.countDown();
        var results = putting.get(10, TimeUnit.SECONDS);
        assertEquals(PutOutcome.CANCELED, results.get(0).outcome());
        assertEquals(PutOutcome.REFUSED, results.get(1).outcome());
        assertFalse(store.has(key(bytes)).join());
    }

    @Test
    void cpuConcurrencyNeverExceedsConfiguredReferenceBound() throws Exception {
        int workers = store.verificationWorkers.getCorePoolSize();
        assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), workers);
        int batches = Math.min(workers, 8);
        CountDownLatch entered = new CountDownLatch(batches);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        store.verificationProbe =
                beginning -> {
                    if (beginning) {
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        entered.countDown();
                        pause();
                    } else {
                        active.decrementAndGet();
                    }
                };
        ArrayList<CompletableFuture<?>> putting = new ArrayList<>();
        for (int i = 0; i < batches; i++) {
            byte[] bytes = {(byte) (i + 10), 2, 3};
            putting.add(store.put(List.of(new PutObject(key(bytes), bytes))));
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(batches, peak.get());
        assertTrue(peak.get() <= workers);
        release.countDown();
        CompletableFuture.allOf(putting.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void aSlowNextPreparationDoesNotDelayAnAlreadyWrittenGroupsCommit() throws Exception {
        byte[] firstBytes = {4, 5, 6};
        byte[] secondBytes = {7, 8, 9};
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch letWriterFinish = new CountDownLatch(1);
        CountDownLatch preparing = new CountDownLatch(1);
        store.writeProbe =
                (point, key) -> {
                    if (point == WritePoint.HEADER_WRITTEN && key.equals(key(firstBytes))) {
                        writing.countDown();
                        try {
                            assertTrue(letWriterFinish.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                    }
                };
        try {
            var first = store.put(List.of(new PutObject(key(firstBytes), firstBytes)));
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            store.verificationProbe =
                    beginning -> {
                        if (beginning) {
                            preparing.countDown();
                            pause();
                        }
                    };
            var second = store.put(List.of(new PutObject(key(secondBytes), secondBytes)));
            assertTrue(preparing.await(10, TimeUnit.SECONDS));
            letWriterFinish.countDown();
            assertEquals(PutOutcome.STORED, first.get(10, TimeUnit.SECONDS).getFirst().outcome());
            assertFalse(second.isDone());
            release.countDown();
            second.get(10, TimeUnit.SECONDS);
        } finally {
            letWriterFinish.countDown();
        }
    }

    @Test
    void outOfOrderCpuCompletionPreservesWriterAdmissionOrder() throws Exception {
        assumeTrue(store.verificationWorkers.getCorePoolSize() >= 2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondPrepared = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        store.verificationProbe =
                beginning -> {
                    if (beginning && starts.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        pause();
                    } else if (!beginning) {
                        secondPrepared.countDown();
                    }
                };
        byte[] firstBytes = {4, 5, 6}, secondBytes = {7, 8, 9};
        var first = store.put(List.of(new PutObject(key(firstBytes), firstBytes)));
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
        var second = store.put(List.of(new PutObject(key(secondBytes), secondBytes)));
        assertTrue(secondPrepared.await(10, TimeUnit.SECONDS));
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertTrue(
                store.index.find(key(firstBytes)).offset()
                        < store.index.find(key(secondBytes)).offset());
    }

    @Test
    void historyKeyGenerationAndVerificationStayOnTheCpuWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.verificationProbe =
                beginning -> {
                    if (beginning) {
                        entered.countDown();
                        pause();
                    }
                };
        var observation =
                new History.Observation(
                        1, "wow", "us", java.time.Instant.EPOCH, "", new byte[] {4, 5, 6});
        var appended = store.appendHistory(observation, Cancellation.NONE);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertFalse(appended.isDone());
        assertEquals(1, store.stats().join().objects());
        release.countDown();
        var results = appended.get(10, TimeUnit.SECONDS);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == PutOutcome.STORED));
        assertEquals(1, store.readHistory(new History.Query(), Cancellation.NONE).join().size());
    }
}
