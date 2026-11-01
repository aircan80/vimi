package com.vibeminer.builds;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

final class DiskCatalogTest extends CatalogContract {
    @TempDir Path folder;

    @Override
    protected BuildCatalog create(CatalogSource source) {
        return Catalog.open(source, folder.resolve("catalog.sqlite3")).join();
    }

    @Override
    protected void closeCatalog() {
        ((Catalog) catalog).closeAsync().join();
    }
}
