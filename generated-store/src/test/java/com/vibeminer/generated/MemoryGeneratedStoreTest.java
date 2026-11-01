package com.vibeminer.generated;

final class MemoryGeneratedStoreTest extends GeneratedStoreContract {
    @Override
    protected GeneratedStore create() {
        return new MemoryGeneratedStore();
    }

    @Override
    protected void damage(GeneratedKey key, byte[] bytes) {
        ((MemoryGeneratedStore) store).damage(key);
    }
}
