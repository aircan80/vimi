package com.vibeminer.files;

final class RealFilesContractsTest extends FilesContracts {
    @Override
    protected FilesScenario create() {
        return new FilesScenario(false);
    }
}
