package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.concurrent.CompletableFuture;

/** Shared opens and cached successful builds, independently cancelable per caller. */
public interface Builds {
    CompletableFuture<BuildOpenResult> open(BuildRef build, Cancellation cancellation);

    default CompletableFuture<BuildOpenResult> open(BuildRef build) {
        return open(build, Cancellation.NONE);
    }
}
