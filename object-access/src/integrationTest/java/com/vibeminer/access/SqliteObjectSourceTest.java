package com.vibeminer.access;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/** Runs the existing source contract through the production SQLite storage interfaces. */
final class SqliteObjectSourceTest extends ObjectSourceContracts {
    @TempDir Path directory;

    @Override
    protected AccessScenario create() {
        return AccessScenario.sqlite(directory);
    }
}
