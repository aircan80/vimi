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
                                "ab".repeat(32),
                                1,
                                WorldStreamTransport.Backend.valueOf(
                                        System.getProperty("vimi.proof.backend", "VULKAN")
                                                .toUpperCase(java.util.Locale.ROOT)),
                                true));
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
            var objects = quads();
            var outcome = session.attachObjects(resident, objects).get(10, TimeUnit.SECONDS);
            if (outcome != WorldStreamTransport.ObjectsOutcome.ATTACHED) {
                throw new AssertionError("Authored objects did not attach: " + outcome);
            }
            session.camera(new WorldStreamTransport.Camera(2, 2, WIDTH, HEIGHT, VIEW));
            var after = frame(session, committed.contentRevision(), 2);
            int row = HEIGHT / 2;
            // Quad centers (right = -2, 0, 2 at depth 5) and the gap between the cutout and
            // opaque quads (right = -.95) as rays, so they stay put at any field of view.
            int cutoutX = at(-.4), opaqueX = WIDTH / 2, blendedX = at(.4);
            int left = pixel(after, cutoutX, row), baseLeft = pixel(before, cutoutX, row);
            int middle = pixel(after, opaqueX, row);
            int right = pixel(after, blendedX, row), baseRight = pixel(before, blendedX, row);
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
            if ((after.pixels().get(row * WIDTH + blendedX) >>> 24) != 255) {
                throw new AssertionError("Blending must keep the opaque output alpha");
            }
            // CPU picking of the same uploaded tables agrees with these pixels: the transparent
            // cutout and the gap between quads (inside the model's bounds) pick nothing, and the
            // gap pixel is the terrain pixel; the opaque and blended quads pick their ranges.
            var picks = objects.pickTile();
            int gap = at(-.19);
            for (int shift : new int[] {0, 8, 16}) {
                near(
                        channel(pixel(after, gap, row), shift),
                        channel(pixel(before, gap, row), shift),
                        1,
                        "gap between quads draws terrain");
            }
            if (pickAt(picks, cutoutX, row).isPresent() || pickAt(picks, gap, row).isPresent()) {
                throw new AssertionError("A discarded or undrawn pixel must not pick an object");
            }
            var opaque = pickAt(picks, opaqueX, row).orElseThrow();
            var blended = pickAt(picks, blendedX, row).orElseThrow();
            if (opaque.placement() != 0
                    || opaque.range() != 1
                    || opaque.material() != WorldStreamObjectsFile.OPAQUE
                    || Math.abs(opaque.depth() - 5) > 1e-3
                    || blended.placement() != 0
                    || blended.range() != 2
                    || blended.material() != WorldStreamObjectsFile.BLENDED) {
                throw new AssertionError("Picked surfaces disagree: " + opaque + " " + blended);
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
        // The opaque chain has the largest image side, so it is filled in two bounded upload
        // steps; the blended one is non-square, nine levels of 256 x 64 down to 1 x 1.
        builder.image(
                256,
                256,
                WorldStreamObjectsFileTest.chain(256, 256, solid(256, 256, 200, 40, 40, 255)));
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, solid(2, 2, 10, 250, 10, 0)));
        builder.image(
                256, 64, WorldStreamObjectsFileTest.chain(256, 64, solid(256, 64, 0, 0, 255, 128)));
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

    /**
     * Picks through the center of frame pixel (x, y) with the explorer's projection: eye right/up
     * over {@link TerrainCamera#focalPixels}, inverted through {@link TerrainCamera#eye}.
     */
    static java.util.Optional<com.vibeminer.rendering.world.WorldObjectPicking.Hit> pickAt(
            com.vibeminer.rendering.world.WorldObjectPicking.Tile tile, int x, int y) {
        double focal = TerrainCamera.focalPixels(WIDTH);
        double right = (x + .5 - WIDTH * .5) / focal, up = (HEIGHT * .5 - (y + .5)) / focal;
        double cy = Math.cos(VIEW.yaw()), sy = Math.sin(VIEW.yaw());
        double cp = Math.cos(VIEW.pitch()), sp = Math.sin(VIEW.pitch());
        double forward = cp - sp * up, dz = sp + cp * up;
        return com.vibeminer.rendering.world.WorldObjectPicking.pick(
                tile, VIEW, cy * forward + sy * right, sy * forward - cy * right, dz, 2200);
    }

    private static ByteBuffer solid(int width, int height, int r, int g, int b, int a) {
        var pixels = ByteBuffer.allocate(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
        }
        return pixels.flip();
    }

    /** Frame column {@code offset} focal lengths from the center. */
    private static int at(double offset) {
        return (int) Math.round(WIDTH * .5 + offset * TerrainCamera.focalPixels(WIDTH));
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
