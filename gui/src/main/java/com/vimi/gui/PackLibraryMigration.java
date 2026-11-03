package com.vimi.gui;

import com.vibeminer.accounting.LegacyCatalogOwners;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.History;
import com.vibeminer.store.LegacyStoreLease;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.ReadOnlyStore;
import com.vibeminer.store.StoreTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Preserves prior intent and borrows legacy payloads read-only; never deletes source stores. */
final class PackLibraryMigration implements AutoCloseable {
    private static final String OWNER = "library:migration";
    private static final String SOURCE = "legacy-intent";
    private static final String VERSION = "pack-library-v1";
    private ReadOnlyStore legacy;
    private LegacyStoreLease legacyLease;

    ReadOnlyStore legacyObjects() {
        return legacy;
    }

    static CompletableFuture<PackLibraryMigration> open(
            Path directory, ObjectStore library, LibraryCatalog catalog) {
        var result = new CompletableFuture<PackLibraryMigration>();
        Thread.startVirtualThread(() -> {
            var migration = new PackLibraryMigration();
            try {
                Path oldObjects = directory.resolve("objects");
                if (Files.isRegularFile(oldObjects.resolve("objects.sqlite3"))) {
                    migration.legacyLease = LegacyStoreLease.acquire(oldObjects);
                    migration.legacy = ObjectStore.openReadOnly(oldObjects.toAbsolutePath(),new StoreTypes.Options()).join();
                }
                catalog.expect(OWNER,LibraryCatalog.Scope.GLOBAL,SOURCE,LibraryCatalog.Role.ORIGINAL).join();
                if (!catalog.current(OWNER,SOURCE,VERSION).join()) {
                    migration.copyHistory(library);
                    copyReceipt(directory,library);
                    LegacyCatalogOwners.copy(directory.resolve("accounting.sqlite"),catalog,Cancellation.NONE).join();
                    // Only mark complete after every required preservation step succeeds.
                    catalog.replace(OWNER,SOURCE,VERSION,(limit,token) -> List.of(),Cancellation.NONE).join();
                }
                if (!result.complete(migration)) {
                    migration.close();
                }
            } catch (Throwable failure) {
                try {
                    migration.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void copyHistory(ObjectStore library) {
        if (legacy == null) {
            return;
        }
        var target = library.forOwner(OWNER,LibraryCatalog.Scope.GLOBAL,"history",LibraryCatalog.Role.ORIGINAL);
        History.Entry after = null;
        while (true) {
            var page = legacy.readHistory(new History.Query(null,null,null,after,256),Cancellation.NONE).join();
            if (page.isEmpty()) {
                return;
            }
            for (History.Entry entry : page) {
                for (ObjectKey key : List.of(entry.document(),entry.record())) {
                    var source = legacy.read(key).join();
                    if (source.state() != StoreTypes.ReadState.PRESENT) {
                        throw new IllegalStateException("Prior build history cannot be preserved: " + source.state());
                    }
                    var outcome = target.put(List.of(new StoreTypes.PutObject(key,source.bytes()))).join().getFirst();
                    requireStored(outcome);
                }
                after = entry;
            }
        }
    }

    private static void copyReceipt(Path directory, ObjectStore library) {
        Path folder = directory.resolve("generated");
        if (!Files.isRegularFile(folder.resolve("objects.sqlite3"))) {
            return;
        }
        LegacyStoreLease lease = LegacyStoreLease.acquire(folder);
        try {
            var source = ObjectStore.openReadOnly(folder.toAbsolutePath(),new StoreTypes.Options(
                    StoreTypes.Kind.GENERATED,256L << 20,java.time.Clock.systemUTC())).join();
            try {
                var generatedKey = GeneratedKey.hash("vimi-preparation-history-v1".getBytes(StandardCharsets.UTF_8));
                var key = new ObjectKey(ObjectKey.GENERATED,generatedKey.bytes());
                var read = source.read(key).join();
                if (read.state() == StoreTypes.ReadState.ABSENT) {
                    return;
                }
                if (read.state() != StoreTypes.ReadState.PRESENT) {
                    throw new IllegalStateException("Prior saved build receipts cannot be preserved: " + read.state());
                }
                var target = library.forOwner("library:saved-builds",LibraryCatalog.Scope.GLOBAL,"receipts",LibraryCatalog.Role.DERIVED);
                requireStored(target.put(List.of(new StoreTypes.PutObject(key,read.bytes().length,read.bytes(),StoreTypes.Origin.UNKNOWN))).join().getFirst());
            } finally {
                source.closeAsync().join();
            }
        } finally {
            lease.close();
        }
    }

    private static void requireStored(StoreTypes.PutResult result) {
        if (result.outcome() != StoreTypes.PutOutcome.STORED && result.outcome() != StoreTypes.PutOutcome.ALREADY_PRESENT) {
            throw new IllegalStateException("Required library import failed: " + result.outcome());
        }
    }

    @Override
    public void close() {
        try {
            if (legacy != null) {
                legacy.closeAsync().join();
                legacy = null;
            }
        } finally {
            if (legacyLease != null) {
                legacyLease.close();
                legacyLease = null;
            }
        }
    }
}
