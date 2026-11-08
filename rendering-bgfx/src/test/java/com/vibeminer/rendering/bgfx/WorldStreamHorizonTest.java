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

    /** A height for tile (x, y) at its MARE sample {@code i}: 17 x 17 corners, then centres. */
    interface Heights {
        int at(int x, int y, int i);
    }

    /** Every tile from 20..44 on both axes, rolling hills. */
    static WorldLowRes.LowRes lowRes() {
        return lowRes((x, y, i) -> (int) (50 + 30 * Math.sin((x * 17 + i) * 0.1)));
    }

    /** Every tile from 20..44 on both axes, with the given heights. */
    static WorldLowRes.LowRes lowRes(Heights field) {
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
                    heights.putShort((short) field.at(x, y, i));
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

    /**
     * A WDL ramp rising 10 yd per 1/16 tile toward native north (+x), then one toward west (+y):
     * the surface normals lean the other way, (-0.287, 0, 0.958) and (0, -0.287, 0.958), so the
     * World light lights the slope that faces it. Skirt vertices carry only vertical faces and face
     * straight up.
     */
    @Test
    void horizonNormalsLeanAwayFromTheRampsRise() {
        // Native x falls by one step (TILE_SIZE / 16) per corner row and 16 per tile x, so a
        // height of -10 per step rises toward +x; a centre is half a step on.
        var north =
                lowRes(
                        (x, y, i) ->
                                i < 289
                                        ? 8000 - (x * 16 + i / 17) * 10
                                        : 8000 - (x * 16 + (i - 289) / 16) * 10 - 5);
        var west =
                lowRes(
                        (x, y, i) ->
                                i < 289
                                        ? 8000 - (y * 16 + i % 17) * 10
                                        : 8000 - (y * 16 + (i - 289) % 16) * 10 - 5);
        double k = 10 / (WorldFormats.TILE_SIZE / 16), lean = -k / Math.sqrt(1 + k * k);
        double up = 1 / Math.sqrt(1 + k * k);
        expectNormals(north, new double[] {lean, 0, up});
        expectNormals(west, new double[] {0, lean, up});
    }

    private static void expectNormals(WorldLowRes.LowRes lowRes, double[] expected) {
        var packed = WorldStreamHorizon.build(lowRes, 32, 32, 1, () -> false);
        var vertices = packed.vertices().duplicate().order(ByteOrder.nativeOrder());
        int stride = WorldStreamHorizon.VERTEX_BYTES, leaning = 0;
        for (int v = 0; v < packed.vertexCount(); v++) {
            double[] n = new double[3];
            for (int c = 0; c < 3; c++) {
                n[c] = ((vertices.get(v * stride + 20 + c) & 255) - 127.5) / 127.5;
            }
            boolean ramp = true, flat = true;
            for (int c = 0; c < 3; c++) {
                ramp &= Math.abs(n[c] - expected[c]) < 0.02;
                flat &= Math.abs(n[c] - (c == 2 ? 1 : 0)) < 0.02;
            }
            assertTrue(
                    ramp || flat,
                    "vertex "
                            + v
                            + " normal "
                            + java.util.Arrays.toString(n)
                            + " expected "
                            + java.util.Arrays.toString(expected));
            if (ramp) {
                leaning++;
            }
        }
        // Every surface vertex (545 per tile) leans; only skirt vertices face up.
        assertTrue(
                leaning >= packed.tiles() * 545,
                "leaning " + leaning + " of " + packed.vertexCount());
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
            assertEquals(
                    first.vertexCount() * WorldStreamHorizon.VERTEX_BYTES,
                    first.vertices().remaining());
            assertEquals(first.indexCount() * 4, first.indices().remaining());
            assertTrue(first.vertexCount() <= WorldStreamHorizon.MAX_VERTICES);
            assertTrue(first.indexCount() <= WorldStreamHorizon.MAX_INDICES);
            assertTrue(WorldStreamHorizon.GPU_BUDGET_BYTES < 10 << 20);
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

    /**
     * #560: the horizon is uploaded into buffers made with the terrain mesh layout, so its vertices
     * must be exactly that layout's size. A layout change fails here, not only in a GPU proof.
     */
    @Test
    void horizonVerticesAreTheTerrainMeshLayoutsStride() {
        assertEquals(WorldStreamPackedTile.VERTEX_BYTES, WorldStreamHorizon.VERTEX_BYTES);
    }

    @Test
    void theHorizonIsOneFixedBudgetLineBesideThePerSlotTerrainBudgets() {
        // One double-buffered allocation for the session, whatever the view distance: 9.2 MiB
        // with the terrain mesh's 24-byte vertices (#560).
        assertEquals(9_645_168, WorldStreamHorizon.GPU_BUDGET_BYTES);
        long perSlot = WorldStreamPackedTile.MAX_GEOMETRY_BYTES + WorldStreamAtlasMips.CHAIN_BYTES;
        long far = WorldStreamTransport.ViewDistance.FAR.slots() * perSlot;
        // Under three terrain slots, and about a tenth of the FAR terrain total (78.2 MB).
        assertTrue(WorldStreamHorizon.GPU_BUDGET_BYTES < 3 * perSlot);
        assertTrue(WorldStreamHorizon.GPU_BUDGET_BYTES * 9 < far);
        // The window reaches past the widest streamed neighbourhood, so FAR still has a horizon.
        assertTrue(WorldStreamHorizon.RADIUS > WorldStreamTransport.ViewDistance.FAR.radius() + 2);
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

    @Test
    void aFailedHorizonTakesTheFrameFogBackToTheStreamCut() throws Exception {
        var failing = new java.util.concurrent.atomic.AtomicBoolean(false);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        WorldStreamHorizon.Builder builder =
                (lowRes, x, y, generation, canceled) -> {
                    calls.incrementAndGet();
                    if (failing.get()) {
                        throw new IllegalStateException("test");
                    }
                    return WorldStreamHorizon.build(lowRes, x, y, generation, canceled);
                };
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {200, 120, 60}, new float[] {40, 60, 140}, 0, 0, 0);
        try (var horizon = new WorldStreamHorizon(lowRes(), builder)) {
            horizon.request(centre(32), centre(32));
            var ranges = await(horizon).ranges();
            // Nothing uploaded, or no horizon: the stream cut.
            assertTrue(!BgfxWorldStreamRenderer.horizonDrawn(horizon, null));
            assertTrue(!BgfxWorldStreamRenderer.horizonDrawn(null, ranges));
            assertEquals(
                    WorldStreamHorizon.FOG_END,
                    BgfxWorldStreamRenderer.frameFog(
                                    sky, BgfxWorldStreamRenderer.horizonDrawn(horizon, ranges))
                            .cut());
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
            // The uploaded ranges stay, but the frame neither draws the horizon nor fogs out to it.
            assertTrue(!BgfxWorldStreamRenderer.horizonDrawn(horizon, ranges));
            assertEquals(
                    WorldFog.STREAM.cut(),
                    BgfxWorldStreamRenderer.frameFog(
                                    sky, BgfxWorldStreamRenderer.horizonDrawn(horizon, ranges))
                            .cut());
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
            int stride = WorldStreamHorizon.VERTEX_BYTES;
            for (int v = 0; v < last.vertexCount(); v++) {
                float x = vertices.getFloat(v * stride), y = vertices.getFloat(v * stride + 4);
                assertTrue(Math.abs(x - centre(36)) <= limit, "x " + x);
                assertTrue(Math.abs(y - centre(32)) <= limit, "y " + y);
                assertEquals((byte) 255, vertices.get(v * stride + 15));
                // The earth albedo, no MCCV, and a unit normal facing up: the World light (#177)
                // lights the horizon in the shader, as it lights terrain.
                assertEquals(104, vertices.get(v * stride + 12) & 255, "red of vertex " + v);
                assertEquals(100, vertices.get(v * stride + 13) & 255, "green of vertex " + v);
                assertEquals(76, vertices.get(v * stride + 14) & 255, "blue of vertex " + v);
                assertEquals(0, vertices.getInt(v * stride + 16), "MCCV of vertex " + v);
                double[] n = new double[3];
                for (int c = 0; c < 3; c++) {
                    n[c] = ((vertices.get(v * stride + 20 + c) & 255) - 127.5) / 127.5;
                }
                double length = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                assertTrue(Math.abs(length - 1) < 0.02, "unit normal at vertex " + v);
                assertTrue(n[2] > 0, "normal faces up at vertex " + v);
                assertEquals((byte) 255, vertices.get(v * stride + 23));
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
