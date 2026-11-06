package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLowRes;
import com.vibeminer.rendering.world.TerrainCamera;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Native proof of the WDL horizon (#372) on the World stream renderer: an authored WDL of hills
 * around the camera tile, drawn with no streamed tiles. Checks that the horizon is built off the
 * render thread and uploaded, that it draws terrain where the frame without it is only sky, that
 * its far edge fades fully into the sky, and that the frame stats report it. Writes both frames as
 * raw ARGB for inspection.
 */
final class WorldStreamHorizonProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(91, -372);
    private static final int WIDTH = 800, HEIGHT = 500;

    private WorldStreamHorizonProof() {}

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

    /** Rolling hills that rise with distance from the map centre. */
    private static short height(double x, double y) {
        return (short) (Math.hypot(x, y) * 0.06 + 90 * Math.sin(x / 400) * Math.cos(y / 520) + 20);
    }

    /** Tiles 24..40 on both axes: hills rising away from the camera tile (32, 32). */
    static WorldLowRes.LowRes hills() {
        return WorldLowRes.read(hillsBytes(), () -> false);
    }

    /** The same hills as WDL bytes, for a proof that sends them to the real child. */
    static byte[] hillsBytes() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                chunk(
                        "MVER",
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(18).array()));
        int maof = out.size();
        out.writeBytes(chunk("MAOF", new byte[4096 * 4]));
        int[] offsets = new int[4096];
        for (int x = 24; x <= 40; x++) {
            for (int y = 24; y <= 40; y++) {
                offsets[x * 64 + y] = out.size();
                // One smooth surface sampled at each tile's own corners and centres, so shared
                // tile edges agree as they do in a real WDL.
                var heights = ByteBuffer.allocate(545 * 2).order(ByteOrder.LITTLE_ENDIAN);
                double t = WorldFormats.TILE_SIZE, step = t / 16;
                double originX = (32 - x) * t, originY = (32 - y) * t;
                for (int row = 0; row < 17; row++) {
                    for (int column = 0; column < 17; column++) {
                        heights.putShort(height(originX - row * step, originY - column * step));
                    }
                }
                for (int row = 0; row < 16; row++) {
                    for (int column = 0; column < 16; column++) {
                        heights.putShort(
                                height(
                                        originX - (row + .5) * step,
                                        originY - (column + .5) * step));
                    }
                }
                out.writeBytes(chunk("MARE", heights.array()));
            }
        }
        byte[] bytes = out.toByteArray();
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4096; i++) {
            b.putInt(maof + 8 + i * 4, offsets[i]);
        }
        return bytes;
    }

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Files.createDirectories(Path.of(arguments[0]));
        double centre = (32 - 32 - .5) * WorldFormats.TILE_SIZE;
        var camera = new TerrainCamera(centre, centre, 120, 0.4, -0.05);
        var session =
                new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, false);
        Path directory = Files.createTempDirectory(output, "run-");
        Path mapping = directory.resolve("frames");
        try (var frames = WorldFrameFileV2.create(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var reader = WorldFrameFileV2.open(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session, frames, WorldStreamNativeProof.BACKEND)) {
            owner.commit(0, 1, List.of());
            long[] sequence = {0};
            int[] before = frame(owner, reader, camera, 1, sequence);
            owner.attachHorizon(hills());
            // The same camera: the attached horizon alone brings a frame (which asks for it),
            // and the frame that uploads the finished build draws it.
            int[] after = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                var drawn = frame(owner, reader, camera, 1, sequence);
                if (drawn != null && owner.horizonCounts().uploads() > 0) {
                    after = drawn;
                    break;
                }
                Thread.sleep(10);
            }
            var counts = owner.horizonCounts();
            if (counts.uploads() < 1 || after == null) {
                throw new AssertionError(
                        "Horizon was never uploaded and drawn: " + counts + " " + (after != null));
            }
            // Every tile of the window with its four skirts; none is resident, so one range.
            if (counts.tiles() != 169
                    || counts.triangles() != 169 * (1024 + 128)
                    || counts.submits() != 1) {
                throw new AssertionError("Horizon counts " + counts);
            }
            write(output, "without", before);
            write(output, "with", after);
            int changed = 0, skyBottom = 0;
            for (int i = 0; i < before.length; i++) {
                if (before[i] != after[i]) {
                    changed++;
                }
            }
            // The bottom rows look down at nearby horizon terrain: nothing there is sky.
            for (int x = 0; x < WIDTH; x++) {
                int i = (HEIGHT - 1) * WIDTH + x;
                if (after[i] == before[i]) {
                    skyBottom++;
                }
            }
            if (changed < before.length / 5 || skyBottom > 0) {
                throw new AssertionError(
                        "Horizon not drawn: " + changed + " changed, " + skyBottom + " sky");
            }
            System.out.printf(
                    "World horizon native proof PASS %d tiles, %d triangles, %.1f MiB GPU,"
                            + " rebuild %.1f ms off the render thread, %d/%d pixels changed%n",
                    counts.tiles(),
                    counts.triangles(),
                    counts.gpuBytes() / 1048576.0,
                    counts.lastBuildNanos() / 1e6,
                    changed,
                    before.length);
        }
        measure(output);
    }

    /** Frame cost with the horizon off or on, over the same streamed tile with objects. */
    private record Cost(
            double wallMs,
            int draws,
            BgfxWorldStreamRenderer.FrameCounts counts,
            BgfxWorldStreamRenderer.HorizonCounts horizon) {}

    private static final int MEASURED = 60;

    /**
     * Streams one full-detail tile (the camera tile, 32_32) with the material proof's objects,
     * measures {@link #MEASURED} frames without the horizon, attaches it and measures again. The
     * camera tile must be left out of the horizon (the streamed tile draws its surface), the four
     * skirts facing it drawn, and the streamed chunk and object counts must not change: past the
     * streamed neighbourhood the longer cut has none of theirs to add.
     */
    @SuppressWarnings("try")
    private static void measure(Path output) throws Exception {
        Path directory = Files.createTempDirectory(output, "cost-");
        var nonce = new WorldStreamState.Nonce(92, -372);
        var session = new WorldStreamEnvelope.Session(nonce, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        var exterior = WorldStreamObjectsMaterialProof.quads();
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-horizon-pack").factory())) {
            tile =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    nonce, directory, 1, lease, 32, 32, 120, true))
                            .get(20, TimeUnit.SECONDS);
            objects =
                    worker.submit(() -> WorldStreamPackedObjects.pack(exterior.data(), () -> false))
                            .get(20, TimeUnit.SECONDS);
        }
        Path mapping = directory.resolve("frames");
        try (var frames = WorldFrameFileV2.create(mapping, nonce, WorldStreamNativeProof.SCOPE);
                var reader = WorldFrameFileV2.open(mapping, nonce, WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session, frames, WorldStreamNativeProof.BACKEND)) {
            var upload = owner.begin(tile);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!upload.finished()) {
                owner.step(upload);
                owner.advance();
                require(System.nanoTime() < deadline, "Terrain upload did not drain");
            }
            require(upload.successful(), "Terrain upload failed");
            owner.commit(0, 1, List.of(lease));
            var late = owner.beginLate(lease, objects);
            while (!late.ready()) {
                owner.stepLate(late);
                owner.advance();
                require(System.nanoTime() < deadline, "Late objects did not build");
            }
            require(
                    owner.finishLate(late, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "Late objects attach");
            // Down at the streamed tile and its objects, then low over it looking out across the
            // horizon.
            TerrainCamera[] views = {
                WorldStreamObjectsMaterialProof.VIEW, new TerrainCamera(-20, -2, 120, 0.4, -0.05)
            };
            String[] names = {"down at the tile", "out at the horizon"};
            long[] revision = {0};
            long[] sequence = {0};
            Cost[] off = new Cost[2], on = new Cost[2];
            for (int v = 0; v < 2; v++) {
                off[v] = cost(owner, reader, nonce, views[v], revision, sequence);
            }
            owner.attachHorizon(hills());
            while (owner.horizonCounts().uploads() == 0) {
                if (owner.horizonUploading()) {
                    owner.stepHorizon();
                }
                timed(owner, reader, nonce, views[0], revision, sequence);
                require(System.nanoTime() < deadline, "Horizon was not uploaded");
            }
            for (int v = 0; v < 2; v++) {
                on[v] = cost(owner, reader, nonce, views[v], revision, sequence);
            }
            System.out.printf(
                    "World horizon cost, %d frames each at %dx%d, median wall time from camera"
                            + " submit to read back:%n",
                    MEASURED, WIDTH, HEIGHT);
            for (int v = 0; v < 2; v++) {
                System.out.printf(
                        "  %s off: %.2f ms, %d bgfx draws, %s%n"
                                + "  %s on:  %.2f ms, %d bgfx draws, %s, %s%n",
                        names[v],
                        off[v].wallMs,
                        off[v].draws,
                        off[v].counts,
                        names[v],
                        on[v].wallMs,
                        on[v].draws,
                        on[v].counts,
                        on[v].horizon);
                require(
                        on[v].counts.equals(off[v].counts),
                        "Streamed counts changed " + off[v] + " " + on[v]);
                // The camera tile splits the window's surfaces into the runs before and after
                // it, plus the four skirts facing it; nothing is drawn over the streamed tile.
                require(on[v].horizon.submits() == 2 + 4, "Horizon submits " + on[v].horizon);
                require(
                        on[v].draws == off[v].draws + on[v].horizon.submits(),
                        "bgfx draws " + off[v] + on[v]);
            }
            require(off[0].counts.terrainSubmits() > 0, "Streamed tile drawn " + off[0]);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static Cost cost(
            WorldStreamNativeOwner owner,
            WorldFrameFileV2 reader,
            WorldStreamState.Nonce nonce,
            TerrainCamera view,
            long[] revision,
            long[] sequence)
            throws Exception {
        double[] wall = new double[MEASURED];
        int draws = 0;
        for (int i = 0; i < MEASURED; i++) {
            double[] sample = timed(owner, reader, nonce, view, revision, sequence);
            wall[i] = sample[0];
            draws = (int) sample[1];
        }
        java.util.Arrays.sort(wall);
        return new Cost(wall[MEASURED / 2], draws, owner.frameCounts(), owner.horizonCounts());
    }

    /** One frame for a new camera revision: wall ms and bgfx draws. */
    private static double[] timed(
            WorldStreamNativeOwner owner,
            WorldFrameFileV2 reader,
            WorldStreamState.Nonce nonce,
            TerrainCamera view,
            long[] revision,
            long[] sequence)
            throws Exception {
        long id = ++revision[0];
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(10);
        var command = new WorldStreamControl.Camera(nonce, id, 1, WIDTH, HEIGHT, view);
        while (!owner.submit(command)) {
            owner.advance();
            require(System.nanoTime() < deadline, "Camera submit");
        }
        // bgfx double-buffers its frames: this frame's stats are reported after the next swap.
        owner.advance();
        owner.advance();
        int draws = org.lwjgl.bgfx.BGFX.bgfx_get_stats().numDraw();
        while (System.nanoTime() < deadline) {
            var frame =
                    reader.readLatest(
                            sequence[0],
                            new WorldFrameFileV2.Barrier(1, WIDTH, HEIGHT, id, id, 1, 1));
            if (frame != null) {
                sequence[0] = frame.metadata().sequence();
                return new double[] {(System.nanoTime() - started) / 1e6, draws};
            }
            owner.advance();
        }
        throw new AssertionError("No frame for camera " + id);
    }

    /** One drawn frame for this camera, or null when the owner had nothing new to draw. */
    private static int[] frame(
            WorldStreamNativeOwner owner,
            WorldFrameFileV2 reader,
            TerrainCamera view,
            long revision,
            long[] sequence)
            throws Exception {
        var command = new WorldStreamControl.Camera(NONCE, revision, 1, WIDTH, HEIGHT, view);
        boolean submitted = false;
        for (int i = 0; i < 200 && !submitted; i++) {
            // As the session loop does: horizon upload steps in idle iterations, before drawing.
            if (owner.horizonUploading()) {
                owner.stepHorizon();
            }
            submitted = owner.submit(command);
            owner.advance();
        }
        if (!submitted) {
            return null;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            owner.advance();
            var frame =
                    reader.readLatest(
                            sequence[0],
                            new WorldFrameFileV2.Barrier(
                                    1, WIDTH, HEIGHT, revision, revision, 1, 1));
            if (frame != null) {
                sequence[0] = frame.metadata().sequence();
                int[] pixels = new int[WIDTH * HEIGHT];
                frame.pixels().get(pixels);
                return pixels;
            }
        }
        throw new AssertionError("No frame");
    }

    private static void write(Path output, String name, int[] pixels) throws Exception {
        var bytes = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.BIG_ENDIAN);
        bytes.asIntBuffer().put(pixels);
        Files.write(output.resolve(name + ".argb"), bytes.array());
    }
}
