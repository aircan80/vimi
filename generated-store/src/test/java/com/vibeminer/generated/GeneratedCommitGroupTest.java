package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class GeneratedCommitGroupTest {
    private static Put put(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Put(GeneratedKey.hash(bytes), bytes);
    }

    /** Records each lower put batch, in order, in front of a memory store. */
    private static GeneratedStore recording(MemoryGeneratedStore memory, List<List<Put>> puts) {
        return (GeneratedStore)
                Proxy.newProxyInstance(
                        GeneratedStore.class.getClassLoader(),
                        new Class<?>[] {GeneratedStore.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("put") && args.length == 2) {
                                @SuppressWarnings("unchecked")
                                var batch = (List<Put>) args[0];
                                puts.add(List.copyOf(batch));
                            }
                            try {
                                return method.invoke(memory, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    private static List<GeneratedKey> keys(List<Put> batch) {
        return batch.stream().map(Put::key).toList();
    }

    @Test
    void collectedObjectsReachTheLowerStoreAsOnePutInOrderOnlyOnCommit() {
        var memory = new MemoryGeneratedStore();
        var puts = new ArrayList<List<Put>>();
        var group = new GeneratedCommitGroup(recording(memory, puts));
        var segment = put("segment");
        var manifest = put("manifest");
        var node = put("node");
        assertEquals(List.of(PutOutcome.STORED), group.put(List.of(segment)).join());
        assertEquals(
                List.of(PutOutcome.STORED, PutOutcome.ALREADY_PRESENT),
                group.put(List.of(manifest, segment)).join());
        group.put(List.of(node)).join();
        assertEquals(List.of(), puts, "nothing reaches the store before commit");
        assertTrue(memory.keys().isEmpty());
        assertEquals(3, group.pending());

        assertEquals(
                List.of(PutOutcome.STORED, PutOutcome.STORED, PutOutcome.STORED),
                group.commit(Cancellation.NONE).join());
        assertEquals(1, puts.size(), "one lower put");
        assertEquals(List.of(segment.key(), manifest.key(), node.key()), keys(puts.getFirst()));
        assertEquals(0, group.pending());
        assertEquals(List.of(), group.commit(Cancellation.NONE).join());
        assertEquals(1, puts.size(), "an empty commit puts nothing");
    }

    @Test
    void readsSeeCollectedObjectsAndPreferTheCopyTheStoreAlreadyHolds() {
        var memory = new MemoryGeneratedStore();
        var group = new GeneratedCommitGroup(memory);
        var fresh = put("fresh segment");
        var shared = put("shared segment");
        memory.put(List.of(shared)).join();
        memory.damage(shared.key());
        group.put(List.of(fresh, shared)).join();

        var read = group.read(fresh.key()).join();
        assertEquals(ReadState.PRESENT, read.state());
        assertArrayEquals(fresh.bytes(), read.bytes());
        var range = group.readRange(fresh.key(), 1, 3).join();
        assertEquals(fresh.bytes().length, range.length());
        assertArrayEquals(java.util.Arrays.copyOfRange(fresh.bytes(), 1, 4), range.bytes());
        assertTrue(group.has(fresh.key()).join());
        assertFalse(memory.has(fresh.key()).join());
        assertThrows(
                CompletionException.class,
                () -> group.readRange(fresh.key(), 2, fresh.bytes().length).join());
        assertEquals(
                ReadState.CORRUPT,
                group.read(shared.key()).join().state(),
                "the store's copy is the one that remains after commit");
        assertEquals(ReadState.ABSENT, group.read(put("other").key()).join().state());

        group.commit(Cancellation.NONE).join();
        assertArrayEquals(fresh.bytes(), memory.read(fresh.key()).join().bytes());
        assertArrayEquals(fresh.bytes(), group.read(fresh.key()).join().bytes());
    }

    @Test
    void overBoundCollectionsCommitEarlierObjectsFirstAndKeepTheOrder() {
        var memory = new MemoryGeneratedStore();
        var puts = new ArrayList<List<Put>>();
        var a = put("aaaa");
        var b = put("bbbb");
        var c = put("cccc");
        var d = put("dddd");
        var group = new GeneratedCommitGroup(recording(memory, puts), 8, 16);
        group.put(List.of(a, b, c)).join();
        assertEquals(List.of(List.of(a.key(), b.key())), puts.stream().map(g -> keys(g)).toList());
        group.put(List.of(d)).join();
        group.commit(Cancellation.NONE).join();
        assertEquals(
                List.of(List.of(a.key(), b.key()), List.of(c.key(), d.key())),
                puts.stream().map(g -> keys(g)).toList());

        puts.clear();
        var counted =
                new GeneratedCommitGroup(recording(new MemoryGeneratedStore(), puts), 1 << 20, 2);
        counted.put(List.of(a, b, c)).join();
        counted.commit(Cancellation.NONE).join();
        assertEquals(
                List.of(List.of(a.key(), b.key()), List.of(c.key())),
                puts.stream().map(g -> keys(g)).toList());
    }

    @Test
    void deletesApplyToCollectedObjectsAndTheStore() {
        var memory = new MemoryGeneratedStore();
        var stored = put("stored");
        var collected = put("collected");
        var kept = put("kept");
        memory.put(List.of(stored)).join();
        var group = new GeneratedCommitGroup(memory);
        group.put(List.of(collected, kept)).join();
        group.delete(List.of(stored.key(), collected.key())).join();
        assertFalse(memory.has(stored.key()).join());
        assertFalse(group.has(collected.key()).join());
        assertEquals(1, group.pending());
        group.commit(Cancellation.NONE).join();
        assertEquals(List.of(kept.key()), memory.keys());
    }

    @Test
    void canceledOrInvalidPutsCollectNothing() {
        var memory = new MemoryGeneratedStore();
        var group = new GeneratedCommitGroup(memory);
        var token = new Cancellation();
        token.cancel();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> group.put(List.of(put("x")), token).join());
        assertThrows(
                CompletionException.class,
                () -> group.put(List.of(new Put(put("y").key(), new byte[0]))).join());
        assertEquals(0, group.pending());
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> {
                    group.put(List.of(put("z"))).join();
                    group.commit(token).join();
                });
        assertTrue(memory.keys().isEmpty(), "a canceled commit stores nothing");
        assertThrows(IllegalArgumentException.class, () -> new GeneratedCommitGroup(memory, 0, 1));
    }

    @Test
    void aSharedBudgetBoundsCollectedBytesAcrossGroupsByCommittingEarly() {
        var memory = new MemoryGeneratedStore();
        var puts = new ArrayList<List<Put>>();
        var budget = new GeneratedCommitGroup.Budget(8);
        var first = new GeneratedCommitGroup(recording(memory, puts), 1 << 20, 128, budget);
        var second = new GeneratedCommitGroup(recording(memory, puts), 1 << 20, 128, budget);
        var a = put("aaaa");
        var b = put("bbbb");
        var c = put("cccc");
        var d = put("dddd");
        first.put(List.of(a, b)).join();
        assertEquals(8, budget.used());
        second.put(List.of(c)).join();
        assertEquals(12, budget.used(), "an empty group may always collect one object");
        first.put(List.of(d)).join();
        assertEquals(
                List.of(List.of(a.key(), b.key())),
                puts.stream().map(g -> keys(g)).toList(),
                "over budget, the group commits what it collected first");
        assertEquals(8, budget.used());
        second.delete(List.of(c.key())).join();
        assertEquals(4, budget.used());
        first.close();
        assertEquals(0, budget.used(), "closing an abandoned group returns its bytes");
        assertEquals(0, first.pending());
        assertFalse(memory.has(d.key()).join(), "a closed group commits nothing more");
        second.put(List.of(c)).join();
        second.commit(Cancellation.NONE).join();
        assertEquals(0, budget.used());
    }
}
