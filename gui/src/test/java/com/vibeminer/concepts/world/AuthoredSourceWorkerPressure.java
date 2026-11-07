package com.vibeminer.concepts.world;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the actual source's single operation lane, so scope creation and tile preparation are
 * refused with SOURCE_WORKER (RETRY_LATER) until the hold closes. Release sends no event, as when
 * another operation ends. Test pressure only.
 */
public final class AuthoredSourceWorkerPressure {
    private AuthoredSourceWorkerPressure() {}

    public static AutoCloseable hold(WorldTerrainStreaming streaming)
            throws ReflectiveOperationException {
        var sourceField = WorldTerrainStreaming.class.getDeclaredField("source");
        sourceField.setAccessible(true);
        var prepared = sourceField.get(streaming);
        var scopesField = WorldPreparedTerrain.class.getDeclaredField("scopes");
        scopesField.setAccessible(true);
        var scopes = scopesField.get(prepared);
        var workingField = WorldStreamScopes.class.getDeclaredField("working");
        workingField.setAccessible(true);
        var working = (AtomicBoolean) workingField.get(scopes);
        if (!working.compareAndSet(false, true)) {
            throw new IllegalStateException("Source lane already busy");
        }
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) working.set(false);
        };
    }
}
