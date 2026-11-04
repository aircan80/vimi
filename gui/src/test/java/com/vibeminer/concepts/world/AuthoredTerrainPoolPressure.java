package com.vibeminer.concepts.world;

import java.util.ArrayList;

/** Reserves actual pool credits only. Creates no atlas, receipt or source-positive capability. */
public final class AuthoredTerrainPoolPressure {
    private AuthoredTerrainPoolPressure() {}

    public static AutoCloseable hold(TerrainAtlasPool pool) {
        var held = new ArrayList<TerrainAtlasPool.Reservation>();
        try {
            for (int i = 0; i < TerrainAtlasPool.MAX_ENTRIES; i++)
                held.add(pool.reserve().orElseThrow());
        } catch (RuntimeException | Error failure) {
            held.forEach(TerrainAtlasPool.Reservation::close);
            throw failure;
        }
        return () -> held.forEach(TerrainAtlasPool.Reservation::close);
    }
}
