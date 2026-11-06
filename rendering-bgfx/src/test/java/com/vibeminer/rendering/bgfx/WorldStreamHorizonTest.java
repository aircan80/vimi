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
    void buildsOffTheCallerAndOnlyWhenTheCameraTileOrResidentSetChanges() throws Exception {
        try (var horizon = new WorldStreamHorizon(lowRes())) {
            long[] resident = new long[64];
            // The camera tile and its east neighbour are resident.
            resident[(32 * 64 + 32) / 64] |= 1L << ((32 * 64 + 32) % 64);
            resident[(32 * 64 + 33) / 64] |= 1L << ((32 * 64 + 33) % 64);
            long before = System.nanoTime();
            horizon.request(centre(32), centre(32), resident);
            // Requesting never waits for the build.
            assertTrue(System.nanoTime() - before < 50_000_000L);
            var first = await(horizon);
            assertEquals(169 - 2, first.tiles());
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
            // Same camera tile (another spot in it) and same resident set: no rebuild.
            horizon.request(centre(32) + 100, centre(32) - 100, resident.clone());
            Thread.sleep(100);
            assertNull(horizon.take());
            // A camera tile change rebuilds around the new tile.
            horizon.request(centre(33), centre(32), resident);
            var moved = await(horizon);
            assertEquals(169 - 2, moved.tiles());
            // A resident change rebuilds too.
            resident[(33 * 64 + 32) / 64] |= 1L << ((33 * 64 + 32) % 64);
            horizon.request(centre(33), centre(32), resident);
            assertEquals(169 - 3, await(horizon).tiles());
        }
    }

    @Test
    void onlyTheNewestRequestIsKeptAndPositionsAreNearTheCamera() throws Exception {
        try (var horizon = new WorldStreamHorizon(lowRes())) {
            long[] none = new long[64];
            for (int tile = 28; tile <= 36; tile++) {
                horizon.request(centre(tile), centre(32), none);
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
