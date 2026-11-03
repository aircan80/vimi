package com.vibeminer.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Metadata only: pending payload bytes already reside in the active pack. Owned by the writer. */
final class PendingWrites {
    static final int MAX_OBJECTS = 128;
    static final long MAX_BYTES = 16L << 20;
    static final long MAX_NANOS = 250_000_000;

    record Entry(Format.Entry header, long offset, History.Entry history) {}

    final int pack;
    final long start;
    final long started;
    final List<Entry> entries = new ArrayList<>();
    final List<LibraryOwnership.Attachment> claims = new ArrayList<>();
    final Map<ObjectKey, Boolean> keys = new HashMap<>();
    final CompletableFuture<Void> committed = new CompletableFuture<>();
    long end;

    PendingWrites(int pack, long start, long started) {
        this.pack = pack;
        this.start = start;
        this.end = start;
        this.started = started;
    }

    void add(Format.Entry header, History.Entry history) {
        entries.add(new Entry(header, end, history));
        keys.put(header.key(), !header.tombstone());
        end += Format.entrySize(header.length());
    }

    boolean full() {
        return entries.size() >= MAX_OBJECTS || end - start >= MAX_BYTES;
    }

    boolean expired(long now) {
        return now - started >= MAX_NANOS;
    }

    void finish(Throwable failure) {
        Thread.ofVirtual()
                .name("vimi-store-commit-result")
                .start(
                        () -> {
                            if (failure == null) {
                                committed.complete(null);
                            } else {
                                committed.completeExceptionally(failure);
                            }
                        });
    }
}
