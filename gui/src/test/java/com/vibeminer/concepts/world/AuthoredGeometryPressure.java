package com.vibeminer.concepts.world;

/**
 * Holds the actual geometry cache's single preparation lane, so a tile preparation is refused with
 * GEOMETRY_CREDIT (RETRY_LATER) until the hold closes. Test pressure only; creates nothing.
 */
public final class AuthoredGeometryPressure {
    private AuthoredGeometryPressure() {}

    public static AutoCloseable hold(WorldService world) throws ReflectiveOperationException {
        var field = WorldService.class.getDeclaredField("cache");
        field.setAccessible(true);
        var cache = (WorldGeometryCache) field.get(world);
        var preparing = WorldGeometryCache.class.getDeclaredField("preparing");
        preparing.setAccessible(true);
        synchronized (cache) {
            if (preparing.getBoolean(cache)) {
                throw new IllegalStateException("Geometry lane already busy");
            }
            preparing.setBoolean(cache, true);
        }
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            // End the lane exactly as a real preparation does, waking anyone it turned away.
            cache.endPreparation();
        };
    }
}
