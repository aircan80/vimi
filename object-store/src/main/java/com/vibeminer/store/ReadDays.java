package com.vibeminer.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, best-effort read notifications. Readers do no database work or admission waiting. */
final class ReadDays {
    static final int CAPACITY = 65_536;
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    interface Wakeup {
        void cancel();
    }

    interface Scheduler {
        Wakeup schedule(Runnable action, long delay, TimeUnit unit);
    }

    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(
                        1,
                        action -> {
                            Thread thread = new Thread(action, "vimi-read-day-timer");
                            thread.setDaemon(true);
                            return thread;
                        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    static Wakeup schedule(Runnable action, long delay, TimeUnit unit) {
        var future = TIMER.schedule(action, delay, unit);
        return () -> future.cancel(false);
    }

    private final ConcurrentHashMap<ObjectKey, Boolean> keys = new ConcurrentHashMap<>();
    private final AtomicInteger reserved = new AtomicInteger();
    private final int capacity;

    ReadDays() {
        this(CAPACITY);
    }

    ReadDays(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Read-day capacity must be positive");
        }
        this.capacity = capacity;
    }

    void offer(ObjectKey key) {
        if (keys.containsKey(key)) {
            return;
        }
        int count;
        do {
            count = reserved.get();
            if (count >= capacity) {
                return;
            }
        } while (!reserved.compareAndSet(count, count + 1));
        if (keys.putIfAbsent(key, Boolean.TRUE) != null) {
            reserved.decrementAndGet();
        }
    }

    List<ObjectKey> drain() {
        List<ObjectKey> result = new ArrayList<>();
        // Concurrent offers must not extend a single flush indefinitely.
        int remaining = capacity;
        for (ObjectKey key : keys.keySet()) {
            if (remaining-- == 0) {
                break;
            }
            if (keys.remove(key) != null) {
                reserved.decrementAndGet();
                result.add(key);
            }
        }
        return result;
    }

    boolean isEmpty() {
        return keys.isEmpty();
    }

    int size() {
        return keys.size();
    }

    /** Called only after all accepted readers have finished. */
    void clear() {
        keys.clear();
        reserved.set(0);
    }
}
