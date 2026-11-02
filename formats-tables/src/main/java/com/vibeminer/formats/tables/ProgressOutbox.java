package com.vibeminer.formats.tables;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One serial notification drainer; bounded by stage changes plus the newest intermediate snapshot.
 */
final class ProgressOutbox {
    private record Notice(TableTypes.PreloadProgress value, boolean kept) {}

    private final Consumer<TableTypes.PreloadProgress> subscriber;
    private final ArrayDeque<Notice> queue = new ArrayDeque<>();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private boolean draining, finished;
    private TableTypes.PreloadProgress latest;
    private long last;

    ProgressOutbox(Consumer<TableTypes.PreloadProgress> subscriber) {
        this.subscriber = subscriber;
    }

    synchronized void publish(TableTypes.PreloadProgress value) {
        if (finished) {
            return;
        }
        var merged = latest == null ? value : TablePreloads.merge(latest, value);
        boolean stage = latest == null || latest.stage() != merged.stage();
        latest = merged;
        long now = System.nanoTime();
        if (!stage && last != 0 && now - last < 100000000) {
            return;
        }
        last = now;
        enqueue(merged, stage);
    }

    synchronized CompletableFuture<Void> finish(TableTypes.PreloadProgress value) {
        if (!finished) {
            latest = latest == null ? value : TablePreloads.merge(latest, value);
            finished = true;
            enqueue(latest, true);
            if (!draining) {
                done.complete(null);
            }
        }
        return done;
    }

    private void enqueue(TableTypes.PreloadProgress value, boolean kept) {
        if (subscriber == null) {
            return;
        }
        if (!kept && !queue.isEmpty() && !queue.getLast().kept()) {
            queue.removeLast();
        }
        queue.addLast(new Notice(value, kept));
        if (draining) {
            return;
        }
        draining = true;
        CompletableFuture.runAsync(this::drain);
    }

    private void drain() {
        for (; ; ) {
            Notice next;
            synchronized (this) {
                next = queue.pollFirst();
                if (next == null) {
                    draining = false;
                    if (finished) {
                        done.complete(null);
                    }
                    return;
                }
            }
            try {
                subscriber.accept(next.value());
            } catch (Throwable ignored) {
            }
        }
    }
}
