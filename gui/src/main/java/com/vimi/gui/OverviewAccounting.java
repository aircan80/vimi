package com.vimi.gui;

import com.vibeminer.access.StoreCommitSubscription;
import com.vibeminer.access.StoreCommits;
import com.vibeminer.accounting.LibraryUsage;
import com.vibeminer.accounting.UsageIndex;
import com.vibeminer.generated.DiskGeneratedStore;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application adapter: no producer depends on accounting and no JavaFX callback performs I/O. */
final class OverviewAccounting {
    final com.vibeminer.accounting.StorageAccounting index;
    final UsageIndex.Presence originals;
    final UsageIndex.Presence generated;
    final UsageIndex.Presence databases;
    private final Path directory;
    private final StoreCommitSubscription commits;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var thread = new Thread(r, "vimi-overview-accounting");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Cancellation token = new Cancellation();
    private volatile LibraryUsage.Totals disk;
    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile String problem;
    private long lastScan;
    private CompletableFuture<Void> closing;

    static OverviewAccounting open(
            Path directory,
            ObjectStore objects,
            DiskGeneratedStore generated,
            StoreCommits commits) {
        try {
            return new OverviewAccounting(directory, objects, generated, commits);
        } catch (RuntimeException failure) {
            System.getLogger(OverviewAccounting.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Storage accounting unavailable; build preparation can continue");
            return null;
        }
    }

    private OverviewAccounting(
            Path directory,
            ObjectStore objects,
            DiskGeneratedStore generatedStore,
            StoreCommits feed) {
        this.directory = directory;
        originals =
                (resources, t) ->
                        objects.storedLengths(
                                        resources.stream().map(r -> objectKey(r.key())).toList(), t)
                                .join();
        generated =
                (resources, t) -> {
                    t.check();
                    var lengths =
                            generatedStore
                                    .storedLengths(
                                            resources.stream()
                                                    .map(r -> GeneratedKey.parse(r.key()))
                                                    .toList(),
                                            com.vibeminer.generated.Cancellation.NONE)
                                    .join();
                    t.check();
                    return lengths;
                };
        databases =
                (resources, t) -> {
                    var lengths = new ArrayList<Long>();
                    for (var resource : resources) {
                        t.check();
                        var path = Path.of(resource.key());
                        if (!path.toAbsolutePath()
                                .normalize()
                                .startsWith(directory.toAbsolutePath().normalize())) {
                            lengths.add(-1L);
                            continue;
                        }
                        try {
                            lengths.add(java.nio.file.Files.size(path));
                        } catch (java.nio.file.NoSuchFileException missing) {
                            lengths.add(-1L);
                        }
                    }
                    return List.copyOf(lengths);
                };
        index = UsageIndex.open(directory.resolve("accounting.sqlite")).join();
        commits = feed.subscribe(64);
        worker.execute(
                () -> {
                    try {
                        index.refresh("objects", originals, token).join();
                        index.refresh("generated", generated, token).join();
                        index.refresh("database", databases, token).join();
                        disk = LibraryUsage.measure(directory, token);
                        lastScan = System.nanoTime();
                        revision.incrementAndGet();
                    } catch (Throwable failure) {
                        problem = "Storage accounting needs refresh";
                    }
                });
        worker.scheduleWithFixedDelay(this::reconcile, 2, 2, TimeUnit.SECONDS);
    }

    private void reconcile() {
        if (token.isCanceled()) {
            return;
        }
        try {
            boolean changed = commits.takeMissed();
            boolean missed = changed;
            for (int batches = 0; batches < 64; batches++) {
                var event = commits.poll();
                if (event == null) {
                    break;
                }
                changed = true;
                var keys = new ArrayList<ObjectKey>(event.stored());
                keys.addAll(event.removed());
                for (int at = 0; at < keys.size(); at += 256) {
                    var page =
                            keys.subList(at, Math.min(keys.size(), at + 256)).stream()
                                    .map(OverviewAccounting::key)
                                    .toList();
                    index.update("objects", page, originals, token).join();
                }
            }
            if (missed) {
                index.refresh("objects", originals, token).join();
            }
            if (System.nanoTime() - lastScan > TimeUnit.MINUTES.toNanos(5)) {
                // Also reconcile writes/repairs made outside this process.
                index.refresh("objects", originals, token).join();
                index.refresh("generated", generated, token).join();
                index.refresh("database", databases, token).join();
                disk = LibraryUsage.measure(directory, token);
                lastScan = System.nanoTime();
                changed = true;
            }
            if (changed) {
                revision.incrementAndGet();
                problem = null;
            }
        } catch (Throwable failure) {
            if (!token.isCanceled()) {
                problem = "Storage measurements are stale; refresh pending";
            }
        }
    }

    LibraryUsage.Totals disk() {
        return disk;
    }

    long revision() {
        return revision.get();
    }

    void changed() {
        revision.incrementAndGet();
    }

    String problem() {
        return problem;
    }

    static String key(ObjectKey key) {
        return HexFormat.of().formatHex(key.indexBytes());
    }

    private static ObjectKey objectKey(String key) {
        byte[] bytes = HexFormat.of().parseHex(key);
        if (bytes.length != 18) {
            throw new IllegalArgumentException("Invalid object key");
        }
        return new ObjectKey(
                Byte.toUnsignedInt(bytes[0]),
                Byte.toUnsignedInt(bytes[1]),
                java.util.Arrays.copyOfRange(bytes, 2, 18));
    }

    static UsageIndex.Inventory keys(List<GeneratedKey> keys) {
        var owned = List.copyOf(keys);
        return () ->
                new UsageIndex.Cursor() {
                    int offset;

                    public List<UsageIndex.Resource> next(Cancellation token) {
                        token.check();
                        var page = new ArrayList<UsageIndex.Resource>();
                        for (int i = 0; i < 256 && offset < owned.size(); i++, offset++) {
                            page.add(
                                    new UsageIndex.Resource(
                                            "generated",
                                            owned.get(offset).hex(),
                                            UsageIndex.Role.DERIVED,
                                            -1));
                        }
                        return List.copyOf(page);
                    }
                };
    }

    synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        token.cancel();
        commits.close();
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.DAYS)) {}
                        index.closeAsync().join();
                        closing.complete(null);
                    } catch (Throwable failure) {
                        closing.completeExceptionally(failure);
                    }
                });
        return closing.copy();
    }
}
