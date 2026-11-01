package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.CatalogTypes.BuildList;
import com.vibeminer.builds.CatalogTypes.CatalogRefresh;
import com.vibeminer.builds.CatalogTypes.SummaryRefresh;
import java.util.concurrent.CompletableFuture;

/** Catalog boundary: listing is offline; only explicit refresh calls observe outside sources. */
public interface BuildCatalog {
    CompletableFuture<BuildList> listBuilds(String product, Cancellation cancellation);

    CompletableFuture<CatalogRefresh> refresh(String product, Cancellation cancellation);

    CompletableFuture<SummaryRefresh> refreshSummary(Cancellation cancellation);

    default CompletableFuture<BuildList> listBuilds(String product) {
        return listBuilds(product, Cancellation.NONE);
    }
}
