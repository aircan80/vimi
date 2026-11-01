package com.vibeminer.access;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

final class DiskObjectSourceTest extends ObjectSourceContracts {
    @TempDir Path folder;

    @Override
    protected AccessScenario create() {
        return AccessScenario.disk(folder);
    }
}
