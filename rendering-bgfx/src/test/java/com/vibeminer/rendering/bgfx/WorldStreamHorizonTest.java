package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLowRes;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class WorldStreamHorizonTest {
    private static byte[] chunk(String tag, byte[] payload) {
        int code = 0;
        for (char c : tag.toCharArray()) {
            code = code << 8 | c;
        }
        return ByteBuffer.allocate(8 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(code)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /** Every tile from 20..44 on both axes, rolling hills. */
    static WorldLowRes.LowRes lowRes() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                chunk(
                        "MVER",
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(18).array()));
        int maof = out.size();
        out.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        int[] offsets = new int[4096];
        for (int x = 20; x <= 44; x++) {
            for (int y = 20; y <= 44; y++) {
                offsets[x * 64 + y] = out.size();
                var heights = ByteBuffer.allocate(545 * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 545; i++) {
                    heights.putShort((short) (50 + 30 * Math.sin((x * 17 + i) * 0.1)));
                }
                out.writeBytes(chunk("MARE", heights.array()));
            }
        }
        byte[] bytes = out.toByteArray();
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4096; i++) {
            b.putInt(maof + 8 + i * 4, offsets[i]);
        }
        return WorldLowRes.read(bytes, () -> false);
    }

    private static WorldStreamHorizon.Packed await(WorldStreamHorizon horizon) throws Exception {
        for (int i = 0; i < 2000; i++) {
            var packed = horizon.take();
            if (packed != null) {
                return packed;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("No horizon built");
    }

    private static double centre(int tile) {
        return (32 - tile - .5) * WorldFormats.TILE_SIZE;
    }

    @Test
    void buildsOffTheCallerAndOnlyWhenTheCameraTileChanges() throws Exception {
        try (var horizon = new WorldStreamHorizon(lowRes())) {
            long before = System.nanoTime();
            horizon.request(centre(32), centre(32));
            // Requesting never waits for the build.
            assertTrue(System.nanoTime() - before < 50_000_000L);
            var first = await(horizon);
            // The whole window: which tiles are drawn at full detail is chosen per frame.
            assertEquals(169, first.tiles());
            assertEquals(169 * (1024 + 128), first.indexCount() / 3);
            assertEquals(first.vertexCount() * 16, first.vertices().remaining());
            assertEquals(first.indexCount() * 4, first.indices().remaining());
            assertTrue(first.vertexCount() <= WorldStreamHorizon.MAX_VERTICES);
            assertTrue(first.indexCount() <= WorldStreamHorizon.MAX_INDICES);
            assertTrue(WorldStreamHorizon.GPU_BUDGET_BYTES < 8 << 20);
            System.out.printf(
                    "Horizon rebuild: %d tiles, %d vertices, %d triangles, %.1f ms%n",
                    first.tiles(),
                    first.vertexCount(),
                    first.indexCount() / 3,
                    first.buildNanos() / 1e6);
            // Same camera tile (another spot in it): no rebuild.
            horizon.request(centre(32) + 100, centre(32) - 100);
            Thread.sleep(100);
            assertNull(horizon.take());
            // A camera tile change rebuilds around the new tile.
            horizon.request(centre(33), centre(32));
            assertEquals(169, await(horizon).tiles());
            assertTrue(!horizon.failed());
        }
    }

    @Test
    void failedBuildsKeepTheWorkerAliveAndRepeatedFailuresStopTheHorizon() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        WorldStreamHorizon.Builder builder =
                (lowRes, x, y, generation, canceled) -> {
                    calls.incrementAndGet();
                    if (failing.get()) {
                        // An Error, not just an exception: the worker must still reset.
                        throw new OutOfMemoryError("test");
                    }
                    return WorldStreamHorizon.build(lowRes, x, y, generation, canceled);
                };
        try (var horizon = new WorldStreamHorizon(lowRes(), builder)) {
            horizon.request(centre(32), centre(32));
            awaitCalls(calls, 1);
            assertTrue(horizon.lastFailure().contains("OutOfMemoryError"));
            assertTrue(!horizon.failed());
            // A failure lets the same camera tile ask again, and a success resets the count.
            failing.set(false);
            horizon.request(centre(32), centre(32));
            assertEquals(169, await(horizon).tiles());
            failing.set(true);
            for (int i = 0; i < WorldStreamHorizon.MAX_FAILURES; i++) {
                int before = calls.get();
                horizon.request(centre(33 + i), centre(32));
                awaitCalls(calls, before + 1);
            }
            for (int i = 0; i < 200 && !horizon.failed(); i++) {
                Thread.sleep(5);
            }
            assertTrue(horizon.failed());
            // A failed horizon builds no more.
            int after = calls.get();
            failing.set(false);
            horizon.request(centre(40), centre(32));
            Thread.sleep(100);
            assertEquals(after, calls.get());
            assertNull(horizon.take());
        }
    }

    private static void awaitCalls(java.util.concurrent.atomic.AtomicInteger calls, int count)
            throws InterruptedException {
        for (int i = 0; i < 2000 && calls.get() < count; i++) {
            Thread.sleep(5);
        }
        assertEquals(count, calls.get());
        // Let the worker record the outcome.
        Thread.sleep(20);
    }

    @Test
    void onlyTheNewestRequestIsKeptAndPositionsAreNearTheCamera() throws Exception {
        try (var horizon = new WorldStreamHorizon(lowRes())) {
            for (int tile = 28; tile <= 36; tile++) {
                horizon.request(centre(tile), centre(32));
            }
            var packed = await(horizon);
            Thread.sleep(200);
            var newest = horizon.take();
            var last = newest != null ? newest : packed;
            // The kept build is centred on the last request: every vertex within the window.
            var vertices = last.vertices().duplicate().order(ByteOrder.nativeOrder());
            double limit = (WorldStreamHorizon.RADIUS + 1) * WorldFormats.TILE_SIZE;
            for (int v = 0; v < last.vertexCount(); v++) {
                float x = vertices.getFloat(v * 16), y = vertices.getFloat(v * 16 + 4);
                assertTrue(Math.abs(x - centre(36)) <= limit, "x " + x);
                assertTrue(Math.abs(y - centre(32)) <= limit, "y " + y);
                assertEquals((byte) 255, vertices.get(v * 16 + 15));
            }
        }
        assertEquals(32, WorldStreamHorizon.tileOf(centre(32)));
        assertEquals(0, WorldStreamHorizon.tileOf(1e9));
        assertEquals(63, WorldStreamHorizon.tileOf(-1e9));
    }

    @Test
    void theChildReadsOnlyTheAnnouncedLengthAndDigest(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        byte[] wdl = WorldStreamLateObjectsTransportTest.wdl(true);
        java.nio.file.Files.write(directory.resolve(WorldStreamControl.Horizon.BASENAME), wdl);
        String sha =
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(wdl));
        var nonce = new WorldStreamState.Nonce(5, 6);
        var lowRes =
                WorldStreamSession.decodeHorizon(
                        directory,
                        new WorldStreamControl.Horizon(nonce, wdl.length, sha),
                        () -> false);
        assertEquals(1, lowRes.tiles().size());
        for (var wrong :
                new WorldStreamControl.Horizon[] {
                    new WorldStreamControl.Horizon(nonce, wdl.length - 1, sha),
                    new WorldStreamControl.Horizon(nonce, wdl.length, "0".repeat(64))
                }) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> WorldStreamSession.decodeHorizon(directory, wrong, () -> false));
        }
        java.nio.file.Files.delete(directory.resolve(WorldStreamControl.Horizon.BASENAME));
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () ->
                        WorldStreamSession.decodeHorizon(
                                directory,
                                new WorldStreamControl.Horizon(nonce, wdl.length, sha),
                                () -> false));
    }
}
