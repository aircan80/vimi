package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldTextureFormats;
import com.vibeminer.rendering.world.TerrainAtlasComposer;
import com.vibeminer.rendering.world.TerrainTileAtlas;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TerrainAtlasPoolTest {
    private static final String KEY = "0".repeat(64);

    private static int tag(String value) {
        int result = 0;
        for (char c : value.toCharArray()) {
            result = (result << 8) | c;
        }
        return result;
    }

    private static TerrainTileAtlas atlas() {
        var chunks = new ArrayList<TerrainGrid.Chunk>();
        var bytes = ByteBuffer.allocate(48 + 40 * 256).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(tag("MVER")).putInt(4).putInt(18);
        bytes.putInt(tag("MAMP")).putInt(4).putInt(0);
        bytes.putInt(tag("MDID")).putInt(4).putInt(1);
        bytes.putInt(tag("MHID")).putInt(4).putInt(0);
        for (int i = 0; i < 256; i++) {
            chunks.add(
                    new TerrainGrid.Chunk(
                            i,
                            i % 16,
                            i / 16,
                            0,
                            -i / 16f,
                            -i % 16f,
                            0,
                            new float[145],
                            0,
                            TerrainGrid.ColorState.ABSENT,
                            new byte[0]));
            bytes.putInt(tag("MCNK")).putInt(32);
            bytes.putInt(tag("MCLY")).putInt(16).putInt(0).putInt(0).putInt(0).putInt(0);
            bytes.putInt(tag("MCAL")).putInt(0);
        }
        byte[] pixels = new byte[64];
        java.util.Arrays.fill(pixels, (byte) 125);
        var mip =
                new TerrainAtlasComposer.DiffuseMip(
                        1, "0".repeat(32), TerrainAtlasComposer.MipProfile.STRICT, 4, 0, pixels);
        try (var textures = WorldTextureFormats.read(bytes.array(), () -> false)) {
            return TerrainAtlasComposer.compose(
                            new WorldFormats.Tile(7, 19, 1, 0, 0, 0, 0, 2, 0, 0),
                            new TerrainGrid(chunks),
                            textures,
                            Map.of(1L, mip),
                            () -> false)
                    .atlas();
        }
    }

    @Test
    void reservationPrecedesEveryOutputAndNineteenthIsRefused() throws Exception {
        var pool = new TerrainAtlasPool();
        var leases = new ArrayList<TerrainAtlasPool.Lease>();
        var identities = new ArrayList<Integer>();
        for (int i = 0; i < 18; i++) {
            try (var reservation = pool.reserve().orElseThrow()) {
                assertEquals(i + 1, pool.stats().entries());
                assertEquals(1, pool.stats().reservedEntries());
                var value = atlas();
                identities.add(System.identityHashCode(value));
                leases.add(reservation.publish(value, String.format("%064x", i)));
            }
        }
        assertEquals(18, pool.stats().entries());
        // Each published entry is charged its own atlas, not the HIGH-splat maximum.
        assertEquals(18 * atlas().retainedBytes(), pool.stats().chargedBytes());
        assertTrue(atlas().retainedBytes() < TerrainAtlasPool.ENTRY_BYTES);
        assertTrue(pool.reserve().isEmpty());
        assertEquals(18, pool.stats().cachedEntries());
        for (int i = 0; i < 18; i++) {
            int expectedIdentity = identities.get(i);
            try (var hit = pool.cached(String.format("%064x", i)).orElseThrow()) {
                hit.withAtlas(
                        value -> assertEquals(expectedIdentity, System.identityHashCode(value)));
            }
        }
        assertEquals(18, pool.stats().cachedEntries());
        var closing = pool.closeAsync();
        assertFalse(closing.isDone());
        assertThrows(IllegalStateException.class, pool::reserve);
        for (var lease : leases) {
            lease.close();
            lease.close();
        }
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(0, pool.stats().entries());
    }

    @Test
    void callbackPinSurvivesConcurrentHandleClosureAndCannotBecomeNineteenthPayload()
            throws Exception {
        var pool = new TerrainAtlasPool();
        var leases = new ArrayList<TerrainAtlasPool.Lease>();
        for (int i = 0; i < 18; i++) {
            try (var reservation = pool.reserve().orElseThrow()) {
                leases.add(reservation.publish(atlas(), i == 0 ? KEY : null));
            }
        }
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CompletableFuture<Void>();
        Thread worker =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        leases.getFirst()
                                                .withAtlas(
                                                        value -> {
                                                            entered.countDown();
                                                            await(release);
                                                            assertEquals(
                                                                    125,
                                                                    value.pixels().get(0) & 255);
                                                        });
                                        finished.complete(null);
                                    } catch (Throwable failure) {
                                        finished.completeExceptionally(failure);
                                    }
                                });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            leases.getFirst().close();
            assertEquals(17, pool.stats().leaseClaims());
            assertEquals(1, pool.stats().borrowPins());
            assertEquals(18, pool.stats().entries());
            assertTrue(pool.reserve().isEmpty());
            assertEquals(1, pool.stats().cachedEntries());
            assertThrows(IllegalStateException.class, leases.getFirst()::retain);
            release.countDown();
            finished.get(5, TimeUnit.SECONDS);
            assertEquals(18, pool.stats().entries());
            assertEquals(1, pool.stats().cachedEntries());
            var replacement = pool.reserve().orElseThrow();
            try {
                assertEquals(18, pool.stats().entries());
                replacement.close();
                assertEquals(17, pool.stats().entries());
            } finally {
                replacement.close();
            }
        } finally {
            release.countDown();
            worker.join(5000);
            leases.forEach(TerrainAtlasPool.Lease::close);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void cacheClaimsShareStorageEvictOnlyUnpinnedEntriesAndCloseAsynchronously() throws Exception {
        var pool = new TerrainAtlasPool();
        TerrainAtlasPool.Lease original;
        try (var reservation = pool.reserve().orElseThrow()) {
            original = reservation.publish(atlas(), KEY);
        }
        var hit = pool.cached(KEY).orElseThrow();
        var retained = hit.retain();
        assertEquals(1, pool.stats().entries());
        assertEquals(3, pool.stats().leaseClaims());
        assertEquals(1, pool.stats().cachedEntries());
        original.close();
        hit.close();
        assertThrows(IllegalArgumentException.class, () -> pool.cached("wrong"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        retained.withAtlas(
                                value -> {
                                    throw new IllegalStateException("Authored failed writer");
                                }));
        assertEquals(0, pool.stats().borrowPins());
        for (int i = 1; i < 18; i++) {
            try (var reservation = pool.reserve().orElseThrow()) {
                var lease = reservation.publish(atlas(), String.format("%064x", i));
                lease.close();
            }
        }
        // Oldest KEY is pinned: skip it and evict the oldest cache-only key1 instead.
        var replacement = pool.reserve().orElseThrow();
        try {
            assertTrue(pool.cached(String.format("%064x", 1)).isEmpty());
            try (var preserved = pool.cached(KEY).orElseThrow()) {
                preserved.withAtlas(value -> assertEquals(125, value.pixels().get(0) & 255));
            }
            assertEquals(18, pool.stats().entries());
            assertEquals(17, pool.stats().cachedEntries());
            assertEquals(1, pool.stats().leaseClaims());
            replacement.close();
            assertEquals(17, pool.stats().entries());
        } finally {
            replacement.close();
        }
        var closing = pool.closeAsync();
        assertEquals(0, pool.stats().cachedEntries());
        assertEquals(1, pool.stats().entries());
        assertFalse(closing.isDone());
        retained.close();
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(0, pool.stats().chargedBytes());
        assertThrows(IllegalStateException.class, () -> retained.withAtlas(value -> {}));
    }

    @Test
    void closeDoesNotRunFutureConsumersOnLeaseCloserAndReservationsDrain() throws Exception {
        var pool = new TerrainAtlasPool();
        var reservation = pool.reserve().orElseThrow();
        var closing = pool.closeAsync();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callback =
                closing.thenRun(
                        () -> {
                            entered.countDown();
                            await(release);
                        });
        try {
            assertFalse(closing.isDone());
            reservation.close(); // Must return even if a completion consumer deliberately blocks.
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(callback.isDone());
            assertEquals(0, pool.stats().entries());
        } finally {
            release.countDown();
            callback.get(5, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Authored gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    @SuppressWarnings("try")
    void reservationsChargeTheCurrentSplatSettingsBound() throws Exception {
        boolean splat = WorldTerrainTextureSource.splat;
        int mask = WorldTerrainTextureSource.splatMask;
        var pool = new TerrainAtlasPool();
        try {
            long none = com.vibeminer.rendering.world.TerrainTileAtlas.maxRetainedBytes(0);
            long standard =
                    com.vibeminer.rendering.world.TerrainTileAtlas.maxRetainedBytes(
                            com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK);
            long high =
                    com.vibeminer.rendering.world.TerrainTileAtlas.maxRetainedBytes(
                            com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK);
            assertEquals(TerrainAtlasPool.ENTRY_BYTES, high);
            // 4 MiB for the HIGH mask map, 1 MiB for STANDARD.
            assertEquals(3L << 20, high - standard);
            WorldTerrainTextureSource.splat = false;
            try (var r = pool.reserve().orElseThrow()) {
                assertEquals(none, pool.stats().chargedBytes());
            }
            WorldTerrainTextureSource.splat = true;
            WorldTerrainTextureSource.splatMask =
                    com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK;
            try (var r = pool.reserve().orElseThrow()) {
                assertEquals(standard, pool.stats().chargedBytes());
            }
            WorldTerrainTextureSource.splatMask =
                    com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK;
            try (var r = pool.reserve().orElseThrow()) {
                assertEquals(high, pool.stats().chargedBytes());
            }
        } finally {
            WorldTerrainTextureSource.splat = splat;
            WorldTerrainTextureSource.splatMask = mask;
            pool.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
