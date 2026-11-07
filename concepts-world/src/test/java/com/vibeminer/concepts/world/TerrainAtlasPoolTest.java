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
        return atlas(0);
    }

    /** The fixture atlas, with splatting inputs of {@code maskSide} when it is not 0. */
    private static TerrainTileAtlas atlas(int maskSide) {
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
                            maskSide > 0,
                            maskSide > 0
                                    ? maskSide
                                    : com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK,
                            () -> false)
                    .atlas();
        }
    }

    @Test
    void reservationPrecedesEveryOutputAndNineteenthIsRefused() throws Exception {
        var pool = new TerrainAtlasPool();
        var leases = new ArrayList<TerrainAtlasPool.Lease>();
        var identities = new ArrayList<Integer>();
        for (int i = 0; i < TerrainAtlasPool.MAX_ENTRIES; i++) {
            try (var reservation = pool.reserve().orElseThrow()) {
                assertEquals(i + 1, pool.stats().entries());
                assertEquals(1, pool.stats().reservedEntries());
                var value = atlas();
                identities.add(System.identityHashCode(value));
                leases.add(reservation.publish(value, String.format("%064x", i)));
            }
        }
        assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
        // Each published entry is charged its own atlas, not the HIGH-splat maximum.
        assertEquals(
                TerrainAtlasPool.MAX_ENTRIES * atlas().retainedBytes(),
                pool.stats().chargedBytes());
        assertTrue(atlas().retainedBytes() < TerrainAtlasPool.ENTRY_BYTES);
        assertTrue(pool.reserve().isEmpty());
        assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().cachedEntries());
        for (int i = 0; i < TerrainAtlasPool.MAX_ENTRIES; i++) {
            int expectedIdentity = identities.get(i);
            try (var hit = pool.cached(String.format("%064x", i)).orElseThrow()) {
                hit.withAtlas(
                        value -> assertEquals(expectedIdentity, System.identityHashCode(value)));
            }
        }
        assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().cachedEntries());
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
        for (int i = 0; i < TerrainAtlasPool.MAX_ENTRIES; i++) {
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
            assertEquals((TerrainAtlasPool.MAX_ENTRIES - 1), pool.stats().leaseClaims());
            assertEquals(1, pool.stats().borrowPins());
            assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
            assertTrue(pool.reserve().isEmpty());
            assertEquals(1, pool.stats().cachedEntries());
            assertThrows(IllegalStateException.class, leases.getFirst()::retain);
            release.countDown();
            finished.get(5, TimeUnit.SECONDS);
            assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
            assertEquals(1, pool.stats().cachedEntries());
            var replacement = pool.reserve().orElseThrow();
            try {
                assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
                replacement.close();
                assertEquals((TerrainAtlasPool.MAX_ENTRIES - 1), pool.stats().entries());
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
        // Three open leases, one atlas: owners are counted per claimed entry, not per claim.
        assertEquals(1, pool.stats().claimedEntries());
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
        for (int i = 1; i < TerrainAtlasPool.MAX_ENTRIES; i++) {
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
            assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
            assertEquals((TerrainAtlasPool.MAX_ENTRIES - 1), pool.stats().cachedEntries());
            assertEquals(1, pool.stats().leaseClaims());
            replacement.close();
            assertEquals((TerrainAtlasPool.MAX_ENTRIES - 1), pool.stats().entries());
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

    @Test
    void theByteBoundEvictsCachedEntriesAcrossMixedMaskSidesAndIsNeverExceeded() throws Exception {
        boolean splat = WorldTerrainTextureSource.splat;
        int mask = WorldTerrainTextureSource.splatMask;
        int high = com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK;
        int standard = com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK;
        var pool = new TerrainAtlasPool();
        var held = new ArrayList<TerrainAtlasPool.Lease>();
        try {
            WorldTerrainTextureSource.splat = true;
            // Mixed settings, every atlas published under a cache key and its lease closed: the
            // pool keeps them cached and evicts the oldest whenever the next charge won't fit.
            int published = 0, refused = 0;
            for (int i = 0; i < 3 * TerrainAtlasPool.MAX_ENTRIES; i++) {
                int side = i % 3 == 0 ? standard : high;
                WorldTerrainTextureSource.splatMask = side;
                var reservation = pool.reserve();
                assertTrue(pool.stats().chargedBytes() <= TerrainAtlasPool.MAX_BYTES);
                if (reservation.isEmpty()) {
                    refused++;
                    continue;
                }
                try (var r = reservation.get()) {
                    assertEquals(side, r.maskSide());
                    // Every fourth one stays leased, so it can't be evicted.
                    var lease = r.publish(atlas(side), String.format("%064x", i));
                    if (i % 4 == 0 && held.size() < 8) {
                        held.add(lease);
                    } else {
                        lease.close();
                    }
                    published++;
                }
                assertTrue(pool.stats().chargedBytes() <= TerrainAtlasPool.MAX_BYTES);
            }
            assertEquals(0, refused, "Unpinned cache entries always make room");
            assertEquals(3 * TerrainAtlasPool.MAX_ENTRIES, published);
            var stats = pool.stats();
            assertTrue(stats.entries() < TerrainAtlasPool.MAX_ENTRIES, "bytes bind before count");
            assertTrue(
                    stats.chargedBytes()
                            > TerrainAtlasPool.MAX_BYTES - TerrainAtlasPool.ENTRY_BYTES);
            // The held leases survive eviction.
            for (var lease : held) {
                lease.withAtlas(atlas -> assertTrue(atlas.retainedBytes() > 0));
            }
            // With the bound filled by pinned HIGH atlases, a reservation is refused, not
            // over-charged, and closing one pinned lease makes room again.
            int leasedCached = held.size();
            var pinned = held;
            WorldTerrainTextureSource.splatMask = high;
            for (int i = 0; ; i++) {
                var reservation = pool.reserve();
                if (reservation.isEmpty()) {
                    break;
                }
                try (var r = reservation.get()) {
                    pinned.add(r.publish(atlas(high), null));
                }
                assertTrue(i < TerrainAtlasPool.MAX_ENTRIES, "the byte bound must refuse");
            }
            assertTrue(pool.stats().chargedBytes() <= TerrainAtlasPool.MAX_BYTES);
            // Only the leased cache entries are left: every unpinned one was evicted.
            assertEquals(leasedCached, pool.stats().cachedEntries());
            pinned.removeLast().close();
            try (var again = pool.reserve().orElseThrow()) {
                assertEquals(high, again.maskSide());
            }
            // An atlas larger than its reservation's charge is refused at publication.
            WorldTerrainTextureSource.splat = false;
            held.forEach(TerrainAtlasPool.Lease::close);
            held.clear();
            try (var r = pool.reserve().orElseThrow()) {
                assertEquals(0, r.maskSide());
                assertThrows(IllegalArgumentException.class, () -> r.publish(atlas(high), null));
            }
        } finally {
            WorldTerrainTextureSource.splat = splat;
            WorldTerrainTextureSource.splatMask = mask;
            held.forEach(TerrainAtlasPool.Lease::close);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void theByteBoundCoversTheNearHighWorkingSet() {
        // Twenty HIGH atlases: the NEAR ring (nine active, one staging), a nine-tile batch, one
        // spare; well under the 34 x HIGH this pool could retain before.
        long high =
                TerrainTileAtlas.maxRetainedBytes(
                        com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK);
        assertEquals(20 * high, TerrainAtlasPool.MAX_BYTES);
        assertTrue(TerrainAtlasPool.MAX_BYTES < TerrainAtlasPool.MAX_ENTRIES * high);
        // FAR and STANDARD keep the count bound.
        assertTrue(
                TerrainAtlasPool.MAX_ENTRIES
                                * TerrainTileAtlas.maxRetainedBytes(
                                        com.vibeminer.rendering.world.TerrainTileSplat
                                                .STANDARD_MASK)
                        <= TerrainAtlasPool.MAX_BYTES);
    }
}
