package com.vibeminer.concepts.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.concurrent.CompletableFuture;

/**
 * Layer6 item-effects boundary; opening acquires one build/locale table handle without reading
 * tables.
 */
public interface ItemEffects {
    CompletableFuture<EffectsTypes.OpenResult> forBuild(
            BuildRef build, String locale, Cancellation cancellation);
}
