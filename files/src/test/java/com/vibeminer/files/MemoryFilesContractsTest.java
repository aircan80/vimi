package com.vibeminer.files;

final class MemoryFilesContractsTest extends FilesContracts {
    @Override
    protected FilesScenario create() {
        return new FilesScenario(true);
    }
}
