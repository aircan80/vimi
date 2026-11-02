package com.vibeminer.access;

import com.vibeminer.network.CdnKey;
import com.vibeminer.network.MemorySources;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.store.MemoryStore;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.ObjectWriter;
import com.vibeminer.store.SqliteObjectStore;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Path;
import java.util.List;

/**
 * Deterministic same-scenario harness for independent source fake, memory-backed real source and
 * disk.
 */
public final class AccessScenario implements AutoCloseable {
    public final ObjectSource source;
    public final StoreCommits commits;
    public final MemorySources network = new MemorySources();
    private final MemoryObjectSource fake;
    private final CachingObjectSource actual;
    private final ObjectWriter writer;
    private final MemoryStore shared;
    private final ObjectStore disk;
    private final SqliteObjectStore sqlite;

    private AccessScenario(boolean independent, Path folder) {
        this(independent, folder, false);
    }

    private AccessScenario(boolean independent, Path folder, boolean useSqlite) {
        var adapters =
                List.of(
                        ObservationAdapters.versions(network),
                        ObservationAdapters.definitions(network),
                        ObservationAdapters.listfile(network));
        if (independent) {
            fake = new MemoryObjectSource(adapters);
            actual = null;
            source = fake;
            commits = fake;
            writer = null;
            shared = null;
            disk = null;
            sqlite = null;
        } else {
            fake = null;
            shared = new MemoryStore();
            if (folder == null) {
                var store = new MemoryStore();
                writer = store;
                disk = null;
                sqlite = null;
                actual = new CachingObjectSource(store, store, store, shared, network, adapters);
            } else if (useSqlite) {
                disk = null;
                sqlite = SqliteObjectStore.open(folder.resolve("library.sqlite")).join();
                writer = sqlite;
                actual = new CachingObjectSource(sqlite, sqlite, sqlite, shared, network, adapters);
            } else {
                sqlite = null;
                disk = ObjectStore.open(folder).join();
                writer = disk;
                actual = new CachingObjectSource(disk, disk, disk, shared, network, adapters);
            }
            source = actual;
            commits = actual;
        }
    }

    public static AccessScenario independent() {
        return new AccessScenario(true, null);
    }

    public static AccessScenario realMemory() {
        return new AccessScenario(false, null);
    }

    public static AccessScenario disk(Path folder) {
        return new AccessScenario(false, folder);
    }

    public static AccessScenario sqlite(Path folder) {
        return new AccessScenario(false, folder, true);
    }

    public void local(ObjectKey key, byte[] bytes) {
        if (fake != null) {
            fake.put(key, bytes);
        } else {
            writer.put(
                            List.of(
                                    new StoreTypes.PutObject(
                                            key, bytes.length, bytes, StoreTypes.Origin.UNKNOWN)),
                            true,
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
        }
    }

    public void shared(ObjectKey key, byte[] bytes) {
        if (fake != null) {
            fake.shared(key, bytes);
        } else {
            shared.put(
                            List.of(new StoreTypes.PutObject(key, bytes)),
                            true,
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
        }
    }

    public void remote(ObjectKey key, byte[] bytes) {
        if (fake != null) {
            fake.remote(key, bytes);
        } else {
            network.add(kind(key), new CdnKey(key.bytes()), bytes);
        }
    }

    public void version(
            String product, String region, NetworkTypes.DocumentKind kind, byte[] bytes) {
        network.version(product, region, kind, bytes);
    }

    public int transfers() {
        return fake != null ? fake.transfers() : network.requested().size();
    }

    private static NetworkTypes.ObjectKind kind(ObjectKey key) {
        return switch (key.type()) {
            case ObjectKey.CONFIG -> NetworkTypes.ObjectKind.CONFIG;
            case ObjectKey.ARCHIVE_INDEX, ObjectKey.FILE_INDEX -> NetworkTypes.ObjectKind.INDEX;
            default -> NetworkTypes.ObjectKind.DATA;
        };
    }

    @Override
    public void close() {
        if (actual != null) {
            actual.closeAsync().join();
        }
        if (disk != null) {
            disk.closeAsync().join();
        }
        if (sqlite != null) {
            sqlite.closeAsync().join();
        }
    }
}
