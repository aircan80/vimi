package com.vimi.gui;

import com.vibeminer.access.StoreCommitSubscription;
import com.vibeminer.access.StoreCommits;
import com.vibeminer.accounting.LibraryUsage;
import com.vibeminer.accounting.UsageIndex;
import com.vibeminer.generated.DiskGeneratedStore;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
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
    private final boolean unified;
    private final ObjectStore sharedObjects;
    private final com.vibeminer.store.LibraryCatalog catalog;
    private final java.util.Map<String, Indexing> inventories = new java.util.HashMap<>();
    private DerivedSqliteConnection watcher;
    private long lastDataVersion = -1;

    private record Indexing(String version, CompletableFuture<Void> work) {}

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

    static OverviewAccounting openUnified(
            Path directory, com.vibeminer.store.LibraryCatalog catalog, ObjectStore objects) {
        return new OverviewAccounting(directory, catalog, objects);
    }

    private OverviewAccounting(
            Path directory, com.vibeminer.store.LibraryCatalog catalog, ObjectStore objects) {
        this.directory = directory;
        this.catalog = catalog;
        this.sharedObjects = objects;
        unified = true;
        originals = (resources, t) -> java.util.Collections.nCopies(resources.size(), -1L);
        generated = originals;
        databases = originals;
        commits = null;
        index =
                com.vibeminer.accounting.UnifiedStorageAccounting.open(
                                objects.databasePath(), catalog, objects)
                        .join();
        worker.scheduleWithFixedDelay(this::reconcile, 0, 2, TimeUnit.SECONDS);
    }

    boolean unified() {
        return unified;
    }

    /** Coalesces early metadata accounting with the later Files/workspace producer. */
    synchronized CompletableFuture<Void> indexBuild(
            BuildInfo info, com.vibeminer.builds.Build opened) {
        token.check();
        String version = opened.metadata().encodingContentKey().hex();
        Indexing active = inventories.get(info.identity());
        if (active != null) {
            if (active.version().equals(version)) return active.work().copy();
            return active.work()
                    .handle((value, failure) -> null)
                    .thenCompose(ignored -> indexBuild(info, opened));
        }
        CompletableFuture<Void> work =
                com.vibeminer.builds.BuildObjectInventory.index(
                        catalog, info.identity(), opened, token);
        var published = new CompletableFuture<Void>();
        inventories.put(info.identity(), new Indexing(version, published));
        work.whenComplete(
                (value, failure) -> {
                    synchronized (OverviewAccounting.this) {
                        Indexing current = inventories.get(info.identity());
                        if (current != null && current.work() == published)
                            inventories.remove(info.identity());
                    }
                    if (failure == null) {
                        changed();
                        published.complete(null);
                    } else {
                        published.completeExceptionally(failure);
                    }
                });
        return published.copy();
    }

    private OverviewAccounting(
            Path directory,
            ObjectStore objects,
            DiskGeneratedStore generatedStore,
            StoreCommits feed) {
        this.directory = directory;
        unified = false;
        sharedObjects = null;
        catalog = null;
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
            if (unified) {
                if (watcher == null) {
                    watcher = DerivedSqliteConnection.openReadOnly(sharedObjects.databasePath());
                }
                long version =
                        watcher.run(
                                Cancellation.NONE,
                                false,
                                session ->
                                        ((Number)
                                                        session.query("PRAGMA data_version")
                                                                .getFirst()[0])
                                                .longValue());
                if (version != lastDataVersion) {
                    lastDataVersion = version;
                    revision.incrementAndGet();
                }
                if (disk == null || System.nanoTime() - lastScan > TimeUnit.MINUTES.toNanos(5)) {
                    disk = LibraryUsage.measure(directory, token);
                    lastScan = System.nanoTime();
                }
                problem = null;
                return;
            }
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

    /** Stop shared inventory work before preparation starts draining its retained contexts. */
    void cancelUpdates() {
        token.cancel();
    }

    synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        token.cancel();
        if (commits != null) commits.close();
        var watcherClosed = new CompletableFuture<Void>();
        worker.execute(
                () -> {
                    try {
                        if (watcher != null) {
                            watcher.close();
                            watcher = null;
                        }
                        watcherClosed.complete(null);
                    } catch (Throwable failure) {
                        watcherClosed.completeExceptionally(failure);
                    }
                });
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.DAYS)) {}
                        watcherClosed.join();
                        CompletableFuture<?>[] imports;
                        synchronized (OverviewAccounting.this) {
                            imports =
                                    inventories.values().stream()
                                            .map(Indexing::work)
                                            .toArray(CompletableFuture[]::new);
                        }
                        CompletableFuture.allOf(imports).handle((value, failure) -> null).join();
                        index.closeAsync().join();
                        closing.complete(null);
                    } catch (Throwable failure) {
                        closing.completeExceptionally(failure);
                    }
                });
        return closing.copy();
    }
}
