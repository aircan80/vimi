package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.concurrent.CompletableFuture;

/** Shared opens and cached successful builds, independently cancelable per caller. */
public interface Builds {
    CompletableFuture<BuildOpenResult> open(BuildRef build, Cancellation cancellation);

    /** Compatible fallback for providers that only implement full opening. */
    default CompletableFuture<BuildTypes.MetadataOpenResult> metadata(
            BuildRef build, Cancellation cancellation) {
        return open(build, cancellation)
                .thenApply(
                        result ->
                                new BuildTypes.MetadataOpenResult(
                                        result.build(),
                                        result.state(),
                                        result.opened() == null ? null : result.opened().metadata(),
                                        result.part(),
                                        result.code(),
                                        result.message(),
                                        result.observedAt()));
    }

    default CompletableFuture<BuildOpenResult> open(BuildRef build) {
        return open(build, Cancellation.NONE);
    }
}
