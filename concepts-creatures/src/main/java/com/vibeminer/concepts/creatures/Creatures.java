package com.vibeminer.concepts.creatures;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesOpenResult;
import java.util.concurrent.CompletableFuture;

/** Build/locale creature knowledge boundary; opening reads no table. */
public interface Creatures {
    CompletableFuture<CreaturesOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token);
}
