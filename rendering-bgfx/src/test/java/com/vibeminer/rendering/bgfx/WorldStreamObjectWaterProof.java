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
 * Actual GPU proof of building water on the production native owner, with authored data only. The
 * portal root of {@link WorldStreamPortalCullProof} (room A holds the camera, room B lies behind a
 * door off to the eye's left) carries water in each room: two tiles one yard below the eye. A
 * second model, water only, is placed 100 yd behind the camera.
 *
 * <ul>
 *   <li>Facing the door: A's and B's water draw (the walk reaches B), the water behind the camera
 *       is culled, and the water changes the lower half of the frame against the same view without
 *       building water.
 *   <li>Turned 20 degrees right: the walk does not reach B, so only A's water draws; B's and the
 *       one behind are skipped.
 * </ul>
 */
final class WorldStreamObjectWaterProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(739, -739);
    private static final int WIDTH = 800, HEIGHT = 500;
    private static final double X = -20, Y = -2, Z = 22;
    private static long camera, sequence;

    private WorldStreamObjectWaterProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    static WorldStreamTileWriter.Exterior scene() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        var red =
                ByteBuffer.allocate(4).put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
        builder.image(1, 1, WorldStreamObjectsFileTest.chain(1, 1, red.flip()));
        builder.model();
        builder.portals(WorldStreamPortalCullProof.rooms());
        // Room A: a small quad far below, and water over x 0..8.3, y -4.2..4.2 at z -1.
        quad(builder, -4, -40, 0);
        water(builder, 0);
        // Room B: its red quad at x 10, and water over x 6..14.3.
        quad(builder, 10, 0, 1);
        water(builder, 6);
        // Water only, without portals.
        builder.model();
        quad(builder, 0, -40, -1);
        water(builder, 0);
        var identity = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
        builder.placement(0, X, Y, Z, 1, identity);
        builder.placement(1, X - 100, Y, Z, 1, identity);
        return builder.build();
    }

    /** A 2 x 2 tile grid at local x {@code x}, y -4.2, z -1, every tile live. */
    private static void water(WorldStreamTileWriter.Exterior.Builder builder, float x) {
        var heights = new float[9];
        java.util.Arrays.fill(heights, -1);
        builder.liquid(
                WorldStreamTileWriter.Exterior.Builder.LIQUID_RIVER,
                0,
                3,
                3,
                x,
                -4.2f,
                0,
                heights,
                new boolean[] {true, true, true, true});
    }

    /** An opaque quad at local x {@code x} (y -8..8, z {@code z} - 2..{@code z} + 2). */
    private static void quad(
            WorldStreamTileWriter.Exterior.Builder builder, float x, float z, int group) {
        var vertices = FloatBuffer.allocate(4 * 8);
        double[][] corners = {{-8, -2}, {8, -2}, {8, 2}, {-8, 2}};
        for (var corner : corners) {
            vertices.put(x).put((float) corner[0]).put((float) (z + corner[1]));
            vertices.put(-0.408248290463863f).put(-0.408248290463863f).put(0.816496580927726f);
            vertices.put(.5f).put(.5f);
        }
        builder.mesh(
                vertices.flip(),
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2}),
                new int[] {0, 12, 0},
                new int[] {WorldStreamObjectsFile.OPAQUE},
                IntBuffer.allocate(0),
                group);
    }

    @SuppressWarnings("try")
    static String run(Path base) throws Exception {
        // A fresh directory each run: an earlier run's packet would refuse the new one.
        Path directory = Files.createTempDirectory(Files.createDirectories(base), "object-water-");
        var session = new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        var exterior = scene();
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-water-pack").factory())) {
            tile =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    NONCE, directory, 1, lease, 7, 19, 120, true))
                            .get(20, TimeUnit.SECONDS);
            objects =
                    worker.submit(() -> WorldStreamPackedObjects.pack(exterior.data(), () -> false))
                            .get(20, TimeUnit.SECONDS);
        }
        require(
                objects.water.runCount() == 3,
                "Three placement liquids: " + objects.water.runCount());
        Path mapping = directory.resolve("frames-water");
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
            var ahead = new TerrainCamera(X, Y, Z, 0, 0);
            var turned = new TerrainCamera(X, Y, Z, Math.toRadians(-20), 0);
            var wet = frame(owner, reader, ahead);
            int[] aheadCounts = owner.waterCounts();
            var turnedFrame = frame(owner, reader, turned);
            int[] turnedCounts = owner.waterCounts();
            owner.objectWater(false);
            var dry = frame(owner, reader, ahead);
            int[] dryCounts = owner.waterCounts();
            owner.objectWater(true);
            int lower = 0, upper = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int i = y * WIDTH + x;
                    if (wet.get(i) != dry.get(i)) {
                        if (y > HEIGHT / 2) {
                            lower++;
                        } else {
                            upper++;
                        }
                    }
                }
            }
            require(
                    aheadCounts[0] == 2 && aheadCounts[1] == 1,
                    "Door in view: A's and B's water drawn, the one behind culled "
                            + java.util.Arrays.toString(aheadCounts));
            require(
                    turnedCounts[0] == 1 && turnedCounts[1] == 2,
                    "Door out of view: B's water skipped with the one behind "
                            + java.util.Arrays.toString(turnedCounts));
            require(dryCounts[0] == 0, "Building water off draws none");
            require(
                    lower > WIDTH * HEIGHT / 20 && upper == 0,
                    "The water covers the lower half, below the eye: " + lower + " / " + upper);
            require(!turnedFrame.equals(wet), "The turned view differs");
            return "ahead="
                    + java.util.Arrays.toString(aheadCounts)
                    + " turned="
                    + java.util.Arrays.toString(turnedCounts)
                    + " waterPixels="
                    + lower;
        }
    }

    private static IntBuffer frame(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, TerrainCamera view)
            throws Exception {
        long revision = ++camera;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var command = new WorldStreamControl.Camera(NONCE, revision, 1, WIDTH, HEIGHT, view);
        while (!owner.submit(command)) {
            owner.advance();
            require(System.nanoTime() < deadline, "Camera submit");
        }
        owner.advance();
        owner.advance();
        while (System.nanoTime() < deadline) {
            var frame =
                    reader.readLatest(
                            sequence,
                            new WorldFrameFileV2.Barrier(
                                    1, WIDTH, HEIGHT, revision, revision, 1, 1));
            if (frame != null) {
                sequence = frame.metadata().sequence();
                var pixels = IntBuffer.allocate(WIDTH * HEIGHT);
                pixels.put(frame.pixels()).flip();
                return pixels;
            }
            owner.advance();
        }
        throw new AssertionError("No frame for camera " + revision);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("WORLD_OBJECT_WATER_PASS " + run(Path.of(args[0])));
    }
}
