package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureFormats;
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

    /**
     * Image formats (issue #374): {@code rgba8} (default), {@code bc} (BC1 opaque and cutout, BC3
     * blended, uploaded as blocks where the backend samples them) or {@code bc-fallback} (the same
     * blocks with the child forced onto its RGBA8 decode path).
     */
    static final String IMAGES = System.getProperty("vimi.proof.objectImages", "rgba8");

    /** Opaque texel colour: exact in RGBA8 and as the 565 colour (25, 10, 5) BC1 expands. */
    static final int OPAQUE_R = 206, OPAQUE_G = 40, OPAQUE_B = 41;

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var process = new AtomicReference<Process>();
        var backend =
                WorldStreamTransport.Backend.valueOf(
                        System.getProperty("vimi.proof.backend", "VULKAN")
                                .toUpperCase(java.util.Locale.ROOT));
        var prepared =
                WorldStreamTransport.prepare(
                        output, new WorldStreamTransport.Spec("ab".repeat(32), 1, backend, true));
        if (!List.of("rgba8", "bc", "bc-fallback").contains(IMAGES)) {
            throw new IllegalArgumentException("vimi.proof.objectImages: rgba8, bc or bc-fallback");
        }
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
                                                    "-D"
                                                            + BgfxWorldStreamRenderer
                                                                    .OBJECTS_RGBA8_PROPERTY
                                                            + "="
                                                            + IMAGES.equals("bc-fallback"),
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
            int left = pixel(after, 144, row), baseLeft = pixel(before, 144, row);
            int middle = pixel(after, 400, row);
            int right = pixel(after, 656, row), baseRight = pixel(before, 656, row);
            // Cutout: every texel is below the reference, so the terrain pixel is untouched.
            for (int shift : new int[] {0, 8, 16}) {
                near(channel(left, shift), channel(baseLeft, shift), 1, "cutout discards");
            }
            // Opaque at full light and no fog: the texel colour, in either channel order.
            int r = Math.abs(channel(middle, 16) - OPAQUE_R) <= 1 ? 16 : 0;
            near(channel(middle, r), OPAQUE_R, 1, "opaque red");
            near(channel(middle, 8), OPAQUE_G, 1, "opaque green");
            near(channel(middle, 16 - r), OPAQUE_B, 1, "opaque blue");
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
            // CPU picking of the same uploaded tables agrees with these pixels: the transparent
            // cutout and the gap between quads (inside the model's bounds) pick nothing, and the
            // gap pixel is the terrain pixel; the opaque and blended quads pick their ranges.
            var picks = objects.pickTile();
            int gap = 278;
            for (int shift : new int[] {0, 8, 16}) {
                near(
                        channel(pixel(after, gap, row), shift),
                        channel(pixel(before, gap, row), shift),
                        1,
                        "gap between quads draws terrain");
            }
            if (pickAt(picks, 144, row).isPresent() || pickAt(picks, gap, row).isPresent()) {
                throw new AssertionError("A discarded or undrawn pixel must not pick an object");
            }
            var opaque = pickAt(picks, 400, row).orElseThrow();
            var blended = pickAt(picks, 656, row).orElseThrow();
            if (opaque.placement() != 0
                    || opaque.range() != 1
                    || opaque.material() != WorldStreamObjectsFile.OPAQUE
                    || Math.abs(opaque.depth() - 5) > 1e-3
                    || blended.placement() != 0
                    || blended.range() != 2
                    || blended.material() != WorldStreamObjectsFile.BLENDED) {
                throw new AssertionError("Picked surfaces disagree: " + opaque + " " + blended);
            }
            // The child names each compressed format's upload path once, at renderer start.
            String path =
                    switch (IMAGES) {
                        case "bc" -> "BC1 compressed, BC2 compressed, BC3 compressed";
                        case "bc-fallback" -> "BC1 decoded to RGBA8, BC2 decoded to RGBA8";
                        default -> "World object textures:";
                    };
            if (!session.stderrSnapshot().contains(path)) {
                throw new AssertionError(
                        "Object texture path not reported: " + session.stderrSnapshot());
            }
            var receipt = session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            if (receipt.exitCode().orElseThrow() != 0 || process.get().isAlive()) {
                throw new AssertionError("Actual native process drain disagrees");
            }
            System.out.println(
                    "WORLD_OBJECT_MATERIALS_PASS backend="
                            + backend
                            + " images="
                            + IMAGES
                            + " placements="
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
        // The opaque chain has the largest image side (two bounded RGBA8 upload steps, one as
        // BC1); the blended one is non-square, nine levels of 256 x 64 down to 1 x 1.
        if (IMAGES.equals("rgba8")) {
            builder.image(
                    256,
                    256,
                    WorldStreamObjectsFileTest.chain(
                            256, 256, solid(256, 256, OPAQUE_R, OPAQUE_G, OPAQUE_B, 255)));
            builder.image(
                    2, 2, WorldStreamObjectsFileTest.chain(2, 2, solid(2, 2, 10, 250, 10, 0)));
            builder.image(
                    256,
                    64,
                    WorldStreamObjectsFileTest.chain(256, 64, solid(256, 64, 0, 0, 255, 128)));
        } else {
            int color = 25 << 11 | 10 << 5 | 5, blue = 0x001F;
            // BC1 four-colour, every selector 0: the opaque colour. BC1 is opaque-only (#374).
            builder.image(
                    256,
                    256,
                    ObjectTextureFormats.BC1,
                    blocks(ObjectTextureFormats.BC1, 256, 256, bc1(color, 0, 0)));
            // BC3 at the smallest compressed side: alpha 0 everywhere, so the cutout discards.
            builder.image(
                    4,
                    4,
                    ObjectTextureFormats.BC3,
                    blocks(ObjectTextureFormats.BC3, 4, 4, bc3(0, blue)));
            // BC3: alpha 128 everywhere (both endpoints), colour blue.
            builder.image(
                    256,
                    64,
                    ObjectTextureFormats.BC3,
                    blocks(ObjectTextureFormats.BC3, 256, 64, bc3(128, blue)));
        }
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
     * over a focal length of 0.8 frame widths, inverted through {@link TerrainCamera#eye}.
     */
    static java.util.Optional<com.vibeminer.rendering.world.WorldObjectPicking.Hit> pickAt(
            com.vibeminer.rendering.world.WorldObjectPicking.Tile tile, int x, int y) {
        double focal = WIDTH * .8;
        double right = (x + .5 - WIDTH * .5) / focal, up = (HEIGHT * .5 - (y + .5)) / focal;
        double cy = Math.cos(VIEW.yaw()), sy = Math.sin(VIEW.yaw());
        double cp = Math.cos(VIEW.pitch()), sp = Math.sin(VIEW.pitch());
        double forward = cp - sp * up, dz = sp + cp * up;
        return com.vibeminer.rendering.world.WorldObjectPicking.pick(
                tile, VIEW, cy * forward + sy * right, sy * forward - cy * right, dz, 2200);
    }

    private static byte[] bc1(int c0, int c1, int selectors) {
        return ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort((short) c0)
                .putShort((short) c1)
                .putInt(selectors)
                .array();
    }

    /** One BC3 block of constant alpha and colour. */
    private static byte[] bc3(int alpha, int color) {
        var block = ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        block.put((byte) alpha).put((byte) alpha).put(new byte[6]).put(bc1(color, 0, 0));
        return block.array();
    }

    /** A whole compressed chain repeating one block at every block of every level. */
    private static ByteBuffer blocks(int format, int width, int height, byte[] block) {
        var chain = ByteBuffer.allocate(ObjectTextureFormats.chainBytes(format, width, height));
        while (chain.hasRemaining()) {
            chain.put(block);
        }
        return chain.flip();
    }

    private static ByteBuffer solid(int width, int height, int r, int g, int b, int a) {
        var pixels = ByteBuffer.allocate(width * height * 4);
        for (int i = 0; i < width * height; i++) {
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
