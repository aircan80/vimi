package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Kind;
import org.junit.jupiter.api.Test;

final class MemoryStoreTest {
    @Test
    void satisfiesReaderWriterAndHistoryContracts() {
        StoreContract.verify(new MemoryStore());
    }

    @Test
    void satisfiesGeneratedContracts() {
        MemoryStore s = new MemoryStore(Kind.GENERATED);
        StoreContract.generated(s, s, s);
    }

    @Test
    void satisfiesStagingContracts() {
        MemoryStore s = new MemoryStore();
        StoreContract.staging(s, s);
    }

    @Test
    void satisfiesFailedStagingContracts() {
        MemoryStore s = new MemoryStore();
        StoreContract.failedStaging(s, s);
    }

    @Test
    void satisfiesStagingBudgets() {
        StoreContract.stagingBudget(new MemoryStore());
    }

    @Test
    void drainsBeforeClose() {
        MemoryStore s = new MemoryStore();
        StoreContract.drainOnClose(s, s, s::closeAsync);
    }

    @Test
    void satisfiesClosedStoreContracts() {
        MemoryStore s = new MemoryStore();
        s.closeAsync().join();
        StoreContract.closed(s, s, s);
    }

    @Test
    void satisfiesVerifierContracts() {
        StoreContract.verifier(Verification.CDN, Kind.CDN);
        StoreContract.verifier(Verification.GENERATED, Kind.GENERATED);
    }
}
