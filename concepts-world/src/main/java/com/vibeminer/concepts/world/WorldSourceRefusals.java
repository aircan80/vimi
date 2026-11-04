package com.vibeminer.concepts.world;

import com.vibeminer.formats.tables.CapturedRowsTypes;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Explicit negative source outcomes; generic provider/program failures are never classified. */
final class WorldSourceRefusals {
    private WorldSourceRefusals() {}

    enum Site {
        OUTPUT_CREDIT(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        GEOMETRY_CREDIT(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        SOURCE_WORKER(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        MATERIAL_POOL(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        ID_INSPECTION(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER),
        NATIVE_CAPTURE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        STRICT_ROWS(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        MAP_PROFILE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        NATIVE_ISSUES(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        NUMERIC_WDT(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        NUMERIC_ROOT(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        TERRAIN_PROFILE(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
        SOURCE_INPUT(WorldTerrainStreaming.Refusal.Reason.CAPABILITY_UNAVAILABLE),
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
            if (!leaf.clean
                    || canceled.getSuppressed().length != 0
                    || canceled.getClass() == CancellationException.class
                            && canceled.getCause() != null) {
                return fatalAuxiliary(failure);
            }
            return canceled;
        }
        if (WorldTerrainTextureSource.ownedSourceChanged(value)) {
            if (!leaf.clean || value.getSuppressed().length != 0 || value.getCause() != null) {
                return fatalAuxiliary(failure);
            }
            return at(Site.MATERIAL_BINDING);
        }
        return failure;
    }

    private static IllegalStateException fatalAuxiliary(RuntimeException failure) {
        return new IllegalStateException(
                "Source outcome accompanied by an unexpected failure", failure);
    }
}
