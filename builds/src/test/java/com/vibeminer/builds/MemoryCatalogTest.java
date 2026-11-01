package com.vibeminer.builds;

final class MemoryCatalogTest extends CatalogContract {
    @Override
    protected BuildCatalog create(CatalogSource source) {
        return new MemoryCatalog(source);
    }

    @Override
    protected void closeCatalog() {
        ((MemoryCatalog) catalog).closeAsync().join();
    }
}
