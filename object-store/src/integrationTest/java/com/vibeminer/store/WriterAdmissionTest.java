package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// Opening a store is disk work that a loaded CI runner can stall for seconds; it is bounded only
// by this class timeout, like every other store test. Admission waits keep their own bounds.
@Timeout(60)
final class WriterAdmissionTest {
    @TempDir Path folder;
    private ObjectStore store;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() throws Exception {
        release.countDown();
        if (store != null) {
            store.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    private void blockWriter() throws Exception {
        if (store == null) {
            store = ObjectStore.open(folder).join();
        }
        CountDownLatch entered = new CountDownLatch(1);
        store.write(
                () -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private PutObject object(int number) {
        byte[] bytes = ("admission-" + number).getBytes(StandardCharsets.US_ASCII);
        return new PutObject(key(bytes), bytes);
    }

    private void fillReadyQueue() {
        for (int i = 0; i < WriteQueue.QUEUED_REQUESTS; i++) {
            store.write(() -> null);
        }
    }

    @Test
    void saturatedPublicWritesReturnPendingFuturesAndEventuallyPersist() throws Exception {
        blockWriter();
        List<CompletableFuture<List<StoreTypes.PutResult>>> writes = new ArrayList<>();
        for (int i = 0; i <= WriteQueue.QUEUED_REQUESTS; i++) {
            writes.add(store.put(List.of(object(i))));
        }
        assertEquals(64, store.writer.state().queued());
        assertEquals(1, store.writer.state().waiting());
        assertTrue(writes.stream().noneMatch(CompletableFuture::isDone));
        release.countDown();
        for (var write : writes) {
            assertEquals(PutOutcome.STORED, write.get(5, TimeUnit.SECONDS).getFirst().outcome());
        }
        for (int i = 0; i < writes.size(); i++) {
            assertEquals(
                    ReadState.PRESENT,
                    store.read(object(i).key()).get(5, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void cancellationOfAnUnacceptedPublicWriteDoesNoDiskWork() throws Exception {
        blockWriter();
        fillReadyQueue();
        Cancellation token = new Cancellation();
        var canceled = store.put(List.of(object(1)), true, token);
        token.cancel();
        assertThrows(CancellationException.class, canceled::join);
        assertEquals(0, store.writer.state().waiting());
        assertEquals(0, store.writer.state().retainedBytes());
        var successor = store.put(List.of(object(2)));
        release.countDown();
        assertEquals(PutOutcome.STORED, successor.get(5, TimeUnit.SECONDS).getFirst().outcome());
        assertEquals(
                ReadState.ABSENT, store.read(object(1).key()).get(5, TimeUnit.SECONDS).state());
    }

    @Test
    void closeRejectsWaitersWhileDrainingAcceptedPublicWrites() throws Exception {
        blockWriter();
        List<CompletableFuture<List<StoreTypes.PutResult>>> accepted = new ArrayList<>();
        for (int i = 0; i < WriteQueue.QUEUED_REQUESTS; i++) {
            accepted.add(store.put(List.of(object(i))));
        }
        var waiting = store.put(List.of(object(100)));
        var closed = store.closeAsync();
        failure(waiting, "OBJECT_STORE_CLOSED");
        failure(store.put(List.of(object(101))), "OBJECT_STORE_CLOSED");
        assertFalse(closed.isDone());
        release.countDown();
        closed.get(5, TimeUnit.SECONDS);
        for (var write : accepted) {
            assertEquals(PutOutcome.STORED, write.get(5, TimeUnit.SECONDS).getFirst().outcome());
        }
        store = ObjectStore.open(folder).join();
        assertEquals(
                ReadState.PRESENT, store.read(object(63).key()).get(5, TimeUnit.SECONDS).state());
        assertEquals(
                ReadState.ABSENT, store.read(object(100).key()).get(5, TimeUnit.SECONDS).state());
    }

    @Test
    void waitingStagedAppendOwnsItsInput() throws Exception {
        store = ObjectStore.open(folder).join();
        EncodedFixture fixture = EncodedFixture.sample();
        ObjectStaging staged =
                store.stage(
                                fixture.key(),
                                fixture.bytes().length,
                                StoreTypes.Origin.CDN,
                                Cancellation.NONE)
                        .join();
        blockWriter();
        fillReadyQueue();
        byte[] submitted = fixture.bytes().clone();
        var appended = staged.append(submitted, Cancellation.NONE);
        submitted[submitted.length - 1] ^= 1;
        assertEquals(1, store.writer.state().waiting());
        assertEquals(fixture.bytes().length, store.writer.state().retainedBytes());
        release.countDown();
        appended.get(5, TimeUnit.SECONDS);
        assertEquals(PutOutcome.STORED, staged.commit(Cancellation.NONE).join().outcome());
        assertArrayEquals(fixture.bytes(), store.read(fixture.key()).join().bytes());
    }

    @Test
    void cancelingUnacceptedStagedCommitKeepsHandleUsable() throws Exception {
        store = ObjectStore.open(folder).join();
        EncodedFixture fixture = EncodedFixture.sample();
        ObjectStaging staged =
                store.stage(
                                fixture.key(),
                                fixture.bytes().length,
                                StoreTypes.Origin.CDN,
                                Cancellation.NONE)
                        .join();
        staged.append(fixture.bytes(), Cancellation.NONE).join();
        blockWriter();
        fillReadyQueue();
        Cancellation token = new Cancellation();
        var commit = staged.commit(token);
        token.cancel();
        assertThrows(CancellationException.class, commit::join);
        release.countDown();
        assertEquals(
                PutOutcome.STORED,
                staged.commit(Cancellation.NONE).get(5, TimeUnit.SECONDS).outcome());
    }
}
