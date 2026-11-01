package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RoleContractsTest {
    @TempDir Path folder;

    @Test
    void generatedContracts() {
        ObjectStore store =
                ObjectStore.open(folder, new Options(Kind.GENERATED, 300, Clock.systemUTC()))
                        .join();
        try {
            StoreContract.generated(store, store, store);
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void stagingContracts() {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            StoreContract.staging(store, store);
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void failedStagingContracts() {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            StoreContract.failedStaging(store, store);
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void stagingBudgets() {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            StoreContract.stagingBudget(store);
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void drainsBeforeClose() {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            StoreContract.drainOnClose(store, store, store::closeAsync);
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void closeContracts() {
        ObjectStore store = ObjectStore.open(folder).join();
        store.closeAsync().join();
        StoreContract.closed(store, store, store);
    }
}
