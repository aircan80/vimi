package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Actual GPU proof of the unlit and additive object surfaces (issue #367), on a fresh native
 * context: two camera-facing quads at eye depth 5 over one authored terrain tile, drawn before and
 * after their late objects attach.
 *
 * <ul>
 *   <li>Left, an unlit opaque quad whose normals face away from the light: lit it would be a third
 *       of its texel colour; unlit it is the texel colour exactly.
 *   <li>Right, an unlit additive quad of blue (0, 0, 200) at alpha 128: the frame's pixel becomes
 *       the pixel without it plus 200 x 128 / 255 blue, red and green unchanged.
 * </ul>
 *
 * Authored data only.
 */
final class WorldStreamMaterialClassProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(424, -242);
    private static final int WIDTH = 800, HEIGHT = 500;
    private static final TerrainCamera VIEW = new TerrainCamera(-20, -2, 20, 0, -0.5);

    private WorldStreamMaterialClassProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    static WorldStreamTileWriter.Exterior quads() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, solid(2, 2, 200, 100, 50, 255)));
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, solid(2, 2, 0, 0, 200, 128)));
        // #449: an additive red quad listed before an opaque-alpha blended green quad over the
        // same pixels, in the middle.
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, solid(2, 2, 200, 0, 0, 128)));
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, solid(2, 2, 0, 200, 0, 255)));
        builder.model();
        double[][] spans = {{-1.8, -0.6}, {0.6, 1.8}, {-0.4, 0.4}, {-0.4, 0.4}};
        var vertices = FloatBuffer.allocate(16 * 8);
        var indices = IntBuffer.allocate(24);
        double cp = Math.cos(VIEW.pitch()), sp = Math.sin(VIEW.pitch());
        for (int q = 0; q < 4; q++) {
            for (int corner = 0; corner < 4; corner++) {
                double right = spans[q][corner == 1 || corner == 2 ? 1 : 0];
                double up = corner < 2 ? -1 : 1, depth = 5;
                // Inverse of TerrainCamera.eye at yaw 0: right = -dy, (up, depth) rotate (dz, dx).
                double dx = -sp * up + cp * depth, dy = -right, dz = cp * up + sp * depth;
                vertices.put((float) dx).put((float) dy).put((float) dz);
                // Facing away from the fixed light: a lit surface would get a third of its colour.
                vertices.put(0.408248290463863f).put(0.408248290463863f);
                vertices.put(-0.816496580927726f).put(.5f).put(.5f);
            }
            int base = q * 4;
            indices.put(base).put(base + 1).put(base + 2).put(base).put(base + 2).put(base + 3);
        }
        vertices.flip();
        indices.flip();
        builder.mesh(
                vertices,
                indices,
                new int[] {0, 6, 0, 6, 6, 1, 12, 6, 2, 18, 6, 3},
                new int[] {
                    WorldStreamObjectsFile.OPAQUE | WorldStreamObjectsFile.UNLIT,
                    // Unlit too, so its full blue is added.
                    WorldStreamObjectsFile.ADDITIVE | WorldStreamObjectsFile.UNLIT,
                    WorldStreamObjectsFile.ADDITIVE | WorldStreamObjectsFile.UNLIT,
                    WorldStreamObjectsFile.BLENDED | WorldStreamObjectsFile.UNLIT
                });
        builder.placement(
                0, VIEW.x(), VIEW.y(), VIEW.z(), 1, new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        return builder.build();
    }

    private static ByteBuffer solid(int width, int height, int r, int g, int b, int a) {
        var pixels = ByteBuffer.allocate(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        }
        return pixels.flip();
    }

    /** Returns "unlit=<argb> additive=<before>-><after>" for the pass line. */
    @SuppressWarnings("try")
    static String run(Path base) throws Exception {
        Path directory = Files.createDirectories(base.resolve("objects-material-classes"));
        var session = new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-material-pack").factory())) {
            tile =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    NONCE, directory, 1, lease, 7, 19, 120, true))
                            .get(20, TimeUnit.SECONDS);
            var exterior = quads();
            objects =
                    worker.submit(() -> WorldStreamPackedObjects.pack(exterior.data(), () -> false))
                            .get(20, TimeUnit.SECONDS);
        }
        Path mapping = directory.resolve("frames");
        try (var frames = WorldFrameFileV2.create(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var reader = WorldFrameFileV2.open(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session, frames, WorldStreamNativeProof.BACKEND)) {
            var upload = owner.begin(tile);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!upload.finished()) {
                owner.step(upload);
                owner.advance();
                require(System.nanoTime() < deadline, "Terrain upload did not drain");
            }
            owner.commit(0, 1, List.of(lease));
            var before = frame(owner, reader, 1, new long[] {0});
            var late = owner.beginLate(lease, objects);
            while (!late.ready()) {
                owner.stepLate(late);
                owner.advance();
                require(System.nanoTime() < deadline, "Late objects did not build");
            }
            require(
                    owner.finishLate(late, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "Late objects attach");
            var after = frame(owner, reader, 2, new long[] {0});
            double focal = TerrainCamera.focalPixels(WIDTH);
            int row = HEIGHT / 2;
            int unlitX = (int) (WIDTH / 2.0 + focal * -1.2 / 5);
            int additiveX = (int) (WIDTH / 2.0 + focal * 1.2 / 5);
            int unlit = after[row * WIDTH + unlitX];
            int red = Math.abs((unlit >>> 16 & 255) - 200) <= 1 ? 16 : 0;
            require(
                    Math.abs((unlit >>> red & 255) - 200) <= 1
                            && Math.abs((unlit >>> 8 & 255) - 100) <= 1
                            && Math.abs((unlit >>> (16 - red) & 255) - 50) <= 1,
                    "Unlit surface is its texel colour: " + Integer.toHexString(unlit));
            int was = before[row * WIDTH + additiveX], now = after[row * WIDTH + additiveX];
            int blue = 16 - red;
            int added = (int) Math.round(200 * 128 / 255.0);
            int expectedBlue = Math.min(255, (was >>> blue & 255) + added);
            require(
                    Math.abs((now >>> blue & 255) - expectedBlue) <= 2
                            && Math.abs((now >>> 8 & 255) - (was >>> 8 & 255)) <= 1
                            && Math.abs((now >>> red & 255) - (was >>> red & 255)) <= 1,
                    "Additive surface adds its colour: "
                            + Integer.toHexString(was)
                            + " -> "
                            + Integer.toHexString(now));
            // #449: the blended green covers what is behind it, then the additive red adds on top,
            // although the additive range comes first in the file.
            int middle = after[row * WIDTH + WIDTH / 2];
            require(
                    Math.abs((middle >>> red & 255) - added) <= 2
                            && Math.abs((middle >>> 8 & 255) - 200) <= 2
                            && (middle >>> blue & 255) <= 2,
                    "Additive after blended: " + Integer.toHexString(middle));
            return "unlit="
                    + Integer.toHexString(unlit)
                    + " blendedThenAdditive="
                    + Integer.toHexString(middle)
                    + " additive="
                    + Integer.toHexString(was)
                    + "->"
                    + Integer.toHexString(now);
        }
    }

    private static int[] frame(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, long revision, long[] sequence)
            throws Exception {
        var command = new WorldStreamControl.Camera(NONCE, revision, 1, WIDTH, HEIGHT, VIEW);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!owner.submit(command)) {
            owner.advance();
            require(System.nanoTime() < deadline, "Camera submit");
        }
        while (System.nanoTime() < deadline) {
            owner.advance();
            var frame =
                    reader.readLatest(
                            sequence[0],
                            new WorldFrameFileV2.Barrier(
                                    1, WIDTH, HEIGHT, revision, revision, 1, 1));
            if (frame != null) {
                sequence[0] = frame.metadata().sequence();
                var pixels = new int[WIDTH * HEIGHT];
                frame.pixels().get(pixels);
                return pixels;
            }
        }
        throw new AssertionError("No frame for camera " + revision);
    }
}
