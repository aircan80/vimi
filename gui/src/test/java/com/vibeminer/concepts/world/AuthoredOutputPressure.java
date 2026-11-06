package com.vibeminer.concepts.world;

import java.util.ArrayList;

/**
 * Fills every output credit of the actual prepared-terrain source with placeholders, so a tile
 * preparation is refused with OUTPUT_CREDIT (RETRY_LATER) until the hold closes. Test pressure
 * only: the placeholders are removed before anything iterates them.
 */
public final class AuthoredOutputPressure {
    private AuthoredOutputPressure() {}

    @SuppressWarnings("unchecked")
    public static AutoCloseable hold(WorldTerrainStreaming streaming)
            throws ReflectiveOperationException {
        var sourceField = WorldTerrainStreaming.class.getDeclaredField("source");
        sourceField.setAccessible(true);
        var prepared = (WorldPreparedTerrain) sourceField.get(streaming);
        var outputsField = WorldPreparedTerrain.class.getDeclaredField("outputs");
        outputsField.setAccessible(true);
        var outputs = (ArrayList<Object>) outputsField.get(prepared);
        var placeholders = new ArrayList<Object>();
        synchronized (prepared) {
            while (outputs.size() < WorldPreparedTerrain.MAX_OUTPUTS) {
                var placeholder = new Object();
                placeholders.add(placeholder);
                outputs.add(placeholder);
            }
        }
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (prepared) {
                for (var placeholder : placeholders) outputs.remove(placeholder);
            }
        };
    }
}
