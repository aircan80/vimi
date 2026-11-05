package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in actual native proof of the hot child's exterior material classes: one authored terrain
 * tile, then late objects with three camera-facing quads side by side, a cutout quad whose texels
 * are all transparent (discarded: terrain stays), an opaque quad (exact texel colour at full light)
 * and an alpha-blended quad (source-alpha over the terrain pixel). The tile is filled to the
 * placement cap behind the camera, so each frame draws the maximum per-tile placements in both
 * passes. Authored data only.
 */
public final class WorldStreamObjectsMaterialProof {
    private static final int WIDTH = 800, HEIGHT = 500;
    private static final TerrainCamera VIEW = new TerrainCamera(-20, -2, 20, 0, -.5);

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var process = new AtomicReference<Process>();
        var prepared =
                WorldStreamTransport.prepare(
                        output,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN, true));
        var session =
                prepared.launch(
                        controls -> {
                            var command =
                                    new ArrayList<>(
                                            List.of(
                                                    Path.of(
                                                                    System.getProperty("java.home"),
                                                                    "bin",
                                                                    "java")
                                                            .toString(),
                                                    "--enable-native-access=ALL-UNNAMED",
                                                    "-cp",
                                                    System.getProperty("java.class.path"),
                                                    GpuViewerMain.class.getName()));
                            command.addAll(controls);
                            var child = new ProcessBuilder(command).start();
                            process.set(child);
                            return child;
                        });
        try {
            session.initialized().get(15, TimeUnit.SECONDS);
            prepared.close();
            var reserved = reserve(session);
            var resident = WorldStreamTransportProof.produce(reserved).get(8, TimeUnit.SECONDS);
            session.camera(new WorldStreamTransport.Camera(1, 1, WIDTH, HEIGHT, VIEW));
            var committed = session.commit(1, resident).get(5, TimeUnit.SECONDS);
            var before = frame(session, committed.contentRevision(), 1);
            var outcome = session.attachObjects(resident, quads()).get(10, TimeUnit.SECONDS);
            if (outcome != WorldStreamTransport.ObjectsOutcome.ATTACHED) {
                throw new AssertionError("Authored objects did not attach: " + outcome);
            }
            session.camera(new WorldStreamTransport.Camera(2, 2, WIDTH, HEIGHT, VIEW));
            var after = frame(session, committed.contentRevision(), 2);
            int row = HEIGHT / 2;
            int left = pixel(after, 144, row), baseLeft = pixel(before, 144, row);
            int middle = pixel(after, 400, row);
            int right = pixel(after, 656, row), baseRight = pixel(before, 656, row);
            // Cutout: every texel is below the reference, so the terrain pixel is untouched.
            for (int shift : new int[] {0, 8, 16}) {
                near(channel(left, shift), channel(baseLeft, shift), 1, "cutout discards");
            }
            // Opaque at full light and no fog: the texel colour, in either channel order.
            int r = Math.abs(channel(middle, 16) - 200) <= 1 ? 16 : 0;
            near(channel(middle, r), 200, 1, "opaque red");
            near(channel(middle, 8), 40, 1, "opaque green");
            near(channel(middle, 16 - r), 40, 1, "opaque blue");
            // Blended: 128/255 of blue (0, 0, 255) over the terrain pixel.
            double a = 128 / 255.0;
            int b = 16 - r;
            near(
                    channel(right, b),
                    (int) Math.round(255 * a + channel(baseRight, b) * (1 - a)),
                    3,
                    "blended blue");
            near(
                    channel(right, r),
                    (int) Math.round(channel(baseRight, r) * (1 - a)),
                    3,
                    "blended red");
            if ((after.pixels().get(row * WIDTH + 656) >>> 24) != 255) {
                throw new AssertionError("Blending must keep the opaque output alpha");
            }
            var receipt = session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            if (receipt.exitCode().orElseThrow() != 0 || process.get().isAlive()) {
                throw new AssertionError("Actual native process drain disagrees");
            }
            System.out.println(
                    "WORLD_OBJECT_MATERIALS_PASS placements="
                            + WorldStreamObjectsFile.MAX_PLACEMENTS
                            + " cutout="
                            + Integer.toHexString(left)
                            + " opaque="
                            + Integer.toHexString(middle)
                            + " blended="
                            + Integer.toHexString(right)
                            + " over="
                            + Integer.toHexString(baseRight));
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
    }

    /** Three quads at eye depth 5, eye-right [-3,-1], [-0.9,0.9] and [1,3], eye-up [-2,2]. */
    static WorldStreamTileWriter.Exterior quads() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, solid(200, 40, 40, 255));
        builder.image(2, 2, solid(10, 250, 10, 0));
        builder.image(2, 2, solid(0, 0, 255, 128));
        builder.model();
        double[][] spans = {{-3, -1}, {-.9, .9}, {1, 3}};
        var vertices = FloatBuffer.allocate(12 * 8);
        var indices = IntBuffer.allocate(18);
        double cp = Math.cos(VIEW.pitch()), sp = Math.sin(VIEW.pitch());
        for (int q = 0; q < 3; q++) {
            for (int corner = 0; corner < 4; corner++) {
                double right = spans[q][corner == 1 || corner == 2 ? 1 : 0];
                double up = corner < 2 ? -2 : 2, depth = 5;
                // Inverse of TerrainCamera.eye at yaw 0: right = -dy, (up, depth) rotate (dz, dx).
                double dx = -sp * up + cp * depth, dy = -right, dz = cp * up + sp * depth;
                // The normal is the fixed light direction, so the light intensity is exactly 1.
                vertices.put((float) dx).put((float) dy).put((float) dz);
                vertices.put(-0.408248290463863f).put(-0.408248290463863f);
                vertices.put(0.816496580927726f).put(.5f).put(.5f);
            }
            int base = q * 4;
            indices.put(base).put(base + 1).put(base + 2).put(base).put(base + 2).put(base + 3);
        }
        vertices.flip();
        indices.flip();
        // Left: cutout (image 1); middle: opaque (image 0); right: blended (image 2).
        builder.mesh(
                vertices,
                indices,
                new int[] {0, 6, 1, 6, 6, 0, 12, 6, 2},
                new int[] {
                    WorldStreamObjectsFile.CUTOUT,
                    WorldStreamObjectsFile.OPAQUE,
                    WorldStreamObjectsFile.BLENDED
                });
        builder.placement(
                0, VIEW.x(), VIEW.y(), VIEW.z(), 1, new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        // Fill the tile to the placement cap behind the camera (looking along +x), so every frame
        // draws all of them in the opaque/cutout pass and again in the sorted blended pass.
        while (builder.placementRoom() > 0) {
            int i = builder.placementCount();
            builder.placement(
                    0,
                    VIEW.x() - 60 - i % 24,
                    VIEW.y() + i / 24,
                    VIEW.z(),
                    1,
                    new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        }
        return builder.build();
    }

    private static ByteBuffer solid(int r, int g, int b, int a) {
        var pixels = ByteBuffer.allocate(16);
        for (int i = 0; i < 4; i++) {
            pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        }
        return pixels.flip();
    }

    private static int pixel(WorldStreamTransport.Frame frame, int x, int y) {
        return frame.pixels().get(y * frame.width() + x);
    }

    private static int channel(int pixel, int shift) {
        return (pixel >>> shift) & 255;
    }

    private static void near(int actual, int expected, int tolerance, String what) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(what + ": " + actual + " != " + expected);
        }
    }

    private static WorldStreamTransport.TileReservation reserve(
            WorldStreamTransport.Session session) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            var value = session.tryReserveTile(7, 19);
            if (value.isPresent()) {
                return value.orElseThrow();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No genuine tile credit");
    }

    private static WorldStreamTransport.Frame frame(
            WorldStreamTransport.Session session, long content, long policy) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            var value = session.takeLatestFrame();
            if (value.isPresent()) {
                var result = value.orElseThrow();
                if (result.content().contentRevision() == content && result.policy() == policy) {
                    return result;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No accepted content/camera frame");
    }

    private WorldStreamObjectsMaterialProof() {}
}
