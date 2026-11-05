package com.vibeminer.concepts.world;

import com.vibeminer.formats.tables.CapturedRowsTypes;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Explicit negative source outcomes; generic provider/program failures are never classified. */
final class WorldSourceRefusals {
    /** Most nested join links accepted in one cancellation chain; deeper is fatal. */
    private static final int CANCELLATION_LINKS = 8;

    private WorldSourceRefusals() {}

    enum Site {
        WATER_OUTPUT_CREDIT(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        WATER_ROOT_BINDING(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        OUTPUT_CREDIT(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        GLOBAL_ASSET_CAPACITY(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        GEOMETRY_CREDIT(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        SOURCE_WORKER(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        MATERIAL_POOL(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        ID_INSPECTION(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        GLOBAL_ASSET_PROFILE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        GLOBAL_ASSET_BINDING(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        GLOBAL_PROFILE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        GLOBAL_WDT_BINDING(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        NATIVE_CAPTURE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        STRICT_ROWS(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        MAP_PROFILE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        NATIVE_ISSUES(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        NUMERIC_WDT(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        NUMERIC_ROOT(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        TERRAIN_PROFILE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        SOURCE_INPUT(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        /** Missing, protected or over-limit source; only BASIC_EXTERIOR derived reads use it. */
        SOURCE_UNAVAILABLE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        OWNER_RETIRED(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        SCOPE_RETIRED(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        MAP_BINDING(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        SOURCE_BINDING(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED),
        MATERIAL_BINDING(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED);

        private final WorldTerrainStreaming.Refusal.Reason reason;

        Site(WorldTerrainStreaming.Refusal.Reason reason) {
            this.reason = reason;
        }

        WorldTerrainStreaming.Refusal.Reason reason() {
            return reason;
        }
    }

    static WorldTerrainStreaming.Refusal at(Site site) {
        return WorldTerrainStreaming.refusal(site);
    }

    private record Leaf(Throwable value, boolean clean, boolean bounded) {}

    private static Leaf inspect(Throwable failure) {
        boolean clean = true;
        for (int depth = 0; depth < 8; depth++) {
            if (failure == null
                    || failure.getClass() != CompletionException.class
                            && failure.getClass() != ExecutionException.class) {
                return new Leaf(failure, clean, true);
            }
            clean &= failure.getSuppressed().length == 0;
            failure = failure.getCause();
        }
        boolean bounded =
                failure == null
                        || failure.getClass() != CompletionException.class
                                && failure.getClass() != ExecutionException.class;
        return new Leaf(failure, clean, bounded);
    }

    static boolean defaultStrictUnsupported(Throwable failure) {
        var leaf = inspect(failure);
        return leaf.bounded
                && leaf.clean
                && leaf.value instanceof CapturedRowsTypes.BoundedReadUnsupportedException marker
                && marker.getSuppressed().length == 0
                && marker.getCause() == null;
    }

    /**
     * Called only at the public source boundary; no arbitrary-cause traversal or state inference.
     */
    static RuntimeException boundary(RuntimeException failure) {
        var leaf = inspect(failure);
        if (!leaf.bounded) {
            return failure;
        }
        var value = leaf.value;
        if (!leaf.clean || value != null && value.getSuppressed().length != 0) {
            return fatalAuxiliary(failure);
        }
        if (value instanceof WorldTerrainStreaming.Refusal refusal) {
            if (!leaf.clean || refusal.getSuppressed().length != 0 || refusal.getCause() != null) {
                return fatalAuxiliary(failure);
            }
            return refusal;
        }
        if (value instanceof CancellationException canceled) {
            var original = leaf.clean ? cancellationOrigin(canceled) : null;
            return original != null ? original : fatalAuxiliary(failure);
        }
        if (WorldTerrainTextureSource.ownedSourceChanged(value)) {
            if (!leaf.clean || value.getSuppressed().length != 0 || value.getCause() != null) {
                return fatalAuxiliary(failure);
            }
            return at(Site.MATERIAL_BINDING);
        }
        return failure;
    }

    /**
     * On current JDKs (25 here) {@code CompletableFuture.join()} and {@code get()} rethrow a stored
     * cancellation as a new {@code CancellationException} whose cause is the stored one, so each
     * nested join adds a link. Accepts only a bounded chain of exact {@code CancellationException}
     * links, none with suppressed failures, that ends in a cause-free link. A lone cause-free stop
     * is returned unchanged; a longer chain returns a fresh cause-free stop. Such a chain carries
     * nothing but cancellation, so it cannot hide an auxiliary failure. A subclass is rejected
     * before any of its accessors run; any other cause, a suppressed failure, a cycle or an
     * over-bound chain returns null. The GUI applies the same rule in {@code
     * com.vimi.gui.CleanCancellation}; keep the two in step.
     */
    private static CancellationException cancellationOrigin(CancellationException failure) {
        Throwable link = failure;
        for (int depth = 0; depth < CANCELLATION_LINKS; depth++) {
            if (link.getClass() != CancellationException.class
                    || link.getSuppressed().length != 0) {
                return null;
            }
            var cause = link.getCause();
            if (cause == null) {
                // A lone stop is returned as is. A chain's deepest link is the instance stored in
                // a shared future, so return a fresh cause-free stop that no other joiner holds.
                return depth == 0 ? failure : new CancellationException();
            }
            link = cause;
        }
        return null;
    }

    private static IllegalStateException fatalAuxiliary(RuntimeException failure) {
        return new IllegalStateException(
                "Source outcome accompanied by an unexpected failure", failure);
    }
}
