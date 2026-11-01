package com.vibeminer.store;

import org.junit.jupiter.api.Test;

final class MemoryStoreTest {
    @Test void satisfiesReaderWriterAndHistoryContracts() {
        StoreContract.verify(new MemoryStore());
    }
}
