package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class ReadDaysTest {
    private ObjectKey key(int value) {
        byte[] bytes = new byte[16];
        Format.le(bytes).putInt(value);
        return new ObjectKey(1, bytes);
    }

    @Test
    void duplicateKeysCoalesceAndCapacityDropsOnlyNewKeys() {
        ReadDays days = new ReadDays(2);
        for (int i = 0; i < 100; i++) {
            days.offer(key(1));
        }
        days.offer(key(2));
        days.offer(key(3));
        assertEquals(2, days.size());
        var drained = days.drain();
        assertTrue(drained.containsAll(List.of(key(1), key(2))));
        assertTrue(days.isEmpty());
        days.offer(key(3));
        assertEquals(List.of(key(3)), days.drain());
    }

    @Test
    void defaultCapacityIsExactly65536UniqueKeys() {
        ReadDays days = new ReadDays();
        for (int i = 0; i <= ReadDays.CAPACITY; i++) {
            days.offer(key(i));
        }
        assertEquals(65_536, days.size());
        assertEquals(65_536, days.drain().size());
        assertTrue(days.isEmpty());
    }

    @Test
    void concurrentAdmissionCannotOverrunOrLeakReservations() throws Exception {
        ReadDays days = new ReadDays(32);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> done = new ArrayList<>();
        for (int worker = 0; worker < 16; worker++) {
            int offset = worker * 1000;
            CompletableFuture<Void> result = new CompletableFuture<>();
            done.add(result);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    assertTrue(start.await(5, TimeUnit.SECONDS));
                                    for (int i = 0; i < 1000; i++) {
                                        days.offer(key(offset + i));
                                    }
                                    result.complete(null);
                                } catch (Throwable error) {
                                    result.completeExceptionally(error);
                                }
                            });
        }
        start.countDown();
        CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals(32, days.size());
        assertEquals(32, days.drain().size());
        for (int i = 0; i < 32; i++) {
            days.offer(key(i));
        }
        assertEquals(32, days.drain().size());
    }

    @Test
    void concurrentDrainingStaysBoundedAndLeavesCapacityReusable() throws Exception {
        ReadDays days = new ReadDays(16);
        CompletableFuture<Void> offered = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                for (int i = 0; i < 10_000; i++) {
                                    days.offer(key(i));
                                }
                                offered.complete(null);
                            } catch (Throwable error) {
                                offered.completeExceptionally(error);
                            }
                        });
        for (int i = 0; i < 100; i++) {
            assertTrue(days.drain().size() <= 16);
        }
        offered.get(5, TimeUnit.SECONDS);
        days.drain();
        for (int i = 0; i < 16; i++) {
            days.offer(key(i));
        }
        assertEquals(16, days.size());
        assertEquals(16, days.drain().size());
    }
}
