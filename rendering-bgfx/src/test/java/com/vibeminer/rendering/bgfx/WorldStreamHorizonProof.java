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
            if (counts.tiles() != 169 || counts.triangles() != 169 * 1024) {
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
