package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.CatalogTypes.BuildRegion;
import com.vibeminer.builds.CatalogTypes.CatalogBuild;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

/** Chooses a listed region with an offline-readable CDN route before falling back to local-only. */
public final class BuildRegions {
    public record Choice(BuildRegion region, boolean routed) {}

    private BuildRegions() {}

    public static CompletableFuture<Choice> choose(
            ObjectSource source, CatalogBuild build, String preferred, Cancellation token) {
        var ordered =
                build.regions().stream()
                        .sorted(
                                Comparator.comparing((BuildRegion r) -> !r.current())
                                        .thenComparing(r -> !r.region().equals(preferred))
                                        .thenComparing(BuildRegion::region)
                                        .thenComparing(
                                                BuildRegion::lastSeen, Comparator.reverseOrder()))
                        .toList();
        if (ordered.isEmpty()) {
            return CompletableFuture.completedFuture(new Choice(null, false));
        }
        return CdnsRoutes.newestFirst(source, build.product(), token)
                .thenApply(
                        replies -> {
                            for (BuildRegion region : ordered) {
                                if (CdnsRoutes.route(replies, region.region()) != null) {
                                    return new Choice(region, true);
                                }
                            }
                            return new Choice(ordered.getFirst(), false);
                        });
    }
}
