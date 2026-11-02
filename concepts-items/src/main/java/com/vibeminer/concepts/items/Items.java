package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.concurrent.CompletableFuture;

/** Layer6 items; opening reads no table or icon. */
public interface Items {
    CompletableFuture<ItemsOpenResult> forBuild(BuildRef build, String locale, Cancellation token);
}
