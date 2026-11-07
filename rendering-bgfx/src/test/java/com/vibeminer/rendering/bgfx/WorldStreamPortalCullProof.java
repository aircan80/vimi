package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.WmoPortalSet;
import com.vibeminer.rendering.world.WmoPortalTraversal;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Actual GPU proof of portal culling (#383 step 3b) on the production native owner, with authored
 * data only. One placed root with a portal set: room A (interior, local x -5..5) holds the camera,
 * room B (interior, x 5..15) lies behind a small door in A's wall at x = 5, off to the eye's left
 * (y 4.2..4.8, z -0.5..0.5). B's only mesh is an opaque red quad at x = 10 (y -8..8, z -2..2).
 *
 * <ul>
 *   <li>Facing +x the door is in view: the walk reaches B and the quad covers the frame's centre.
 *   <li>Turned 20 degrees right the door leaves the view while the quad still covers the centre:
 *       the walk does not reach B, so its mesh is culled and the centre shows what is behind.
 *   <li>The same view with object culling off draws the quad again (nothing is walked).
 * </ul>
 */
final class WorldStreamPortalCullProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(383, -383);
    private static final int WIDTH = 800, HEIGHT = 500;
    private static final double X = -20, Y = -2, Z = 22;
    private static long camera, sequence;

    private WorldStreamPortalCullProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    static WmoPortalSet rooms() {
        return new WmoPortalSet(
                List.of(
                        new WmoPortalTraversal.Group(
                                WmoPortalTraversal.INTERIOR, -5, -10, -5, 5, 10, 5, 0, 1),
                        new WmoPortalTraversal.Group(
                                WmoPortalTraversal.INTERIOR, 5, -10, -5, 15, 10, 5, 1, 1)),
                List.of(
                        new WmoPortalTraversal.Portal(
                                new double[] {5, 4.2, -.5, 5, 4.8, -.5, 5, 4.8, .5, 5, 4.2, .5},
                                1,
                                0,
                                0,
                                -5)),
                List.of(new WmoPortalTraversal.Ref(0, 1, -1), new WmoPortalTraversal.Ref(0, 0, 1)));
    }

    static WorldStreamTileWriter.Exterior scene() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        var red =
                ByteBuffer.allocate(4).put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
        builder.image(1, 1, WorldStreamObjectsFileTest.chain(1, 1, red.flip()));
        builder.model();
        builder.portals(rooms());
        var vertices = FloatBuffer.allocate(4 * 8);
        double[][] corners = {{-8, -2}, {8, -2}, {8, 2}, {-8, 2}};
        for (var corner : corners) {
            vertices.put(10).put((float) corner[0]).put((float) corner[1]);
            // The fixed light direction as the normal: full light.
            vertices.put(-0.408248290463863f).put(-0.408248290463863f).put(0.816496580927726f);
            vertices.put(.5f).put(.5f);
        }
        builder.mesh(
                vertices.flip(),
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2}),
                new int[] {0, 12, 0},
                new int[] {WorldStreamObjectsFile.OPAQUE},
                IntBuffer.allocate(0),
                1);
        builder.placement(0, X, Y, Z, 1, new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        return builder.build();
    }

    /** Whether a pixel is the quad's red (either byte order). */
    private static boolean red(int pixel) {
        int a = pixel >>> 16 & 255, g = pixel >>> 8 & 255, b = pixel & 255;
        return Math.max(a, b) > 150 && g < 60 && Math.min(a, b) < 60;
    }

    @SuppressWarnings("try")
    static String run(Path base) throws Exception {
        Path directory = Files.createDirectories(base.resolve("portal-cull"));
        var session = new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        var exterior = scene();
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-portal-pack").factory())) {
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
        require(objects.portals(0) != null, "The packed root carries its traversal");
        Path mapping = directory.resolve("frames-portal");
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
            int centre = HEIGHT / 2 * WIDTH + WIDTH / 2;
            var ahead = new TerrainCamera(X, Y, Z, 0, 0);
            var turned = new TerrainCamera(X, Y, Z, Math.toRadians(-20), 0);
            int seen = frame(owner, reader, ahead).get(centre);
            var seenCounts = owner.frameCounts();
            int hidden = frame(owner, reader, turned).get(centre);
            var hiddenCounts = owner.frameCounts();
            owner.objectCulling(false);
            int unculled = frame(owner, reader, turned).get(centre);
            var unculledCounts = owner.frameCounts();
            owner.objectCulling(true);
            require(
                    red(seen)
                            && seenCounts.portalWalks() == 1
                            && seenCounts.portalMeshesCulled() == 0,
                    "Door in view: B drawn " + Integer.toHexString(seen) + " " + seenCounts);
            require(
                    !red(hidden)
                            && hiddenCounts.portalWalks() == 1
                            && hiddenCounts.portalMeshesCulled() == 1,
                    "Door out of view: B culled "
                            + Integer.toHexString(hidden)
                            + " "
                            + hiddenCounts);
            require(
                    red(unculled)
                            && unculledCounts.portalWalks() == 0
                            && unculledCounts.portalMeshesCulled() == 0,
                    "Culling off: B drawn " + Integer.toHexString(unculled) + " " + unculledCounts);
            require(
                    hiddenCounts.objectSubmits() == seenCounts.objectSubmits() - 1,
                    "The culled mesh's submit is saved " + seenCounts + " " + hiddenCounts);
            return "seen="
                    + Integer.toHexString(seen)
                    + " hidden="
                    + Integer.toHexString(hidden)
                    + " unculled="
                    + Integer.toHexString(unculled)
                    + " walks="
                    + seenCounts.portalWalks()
                    + "/"
                    + hiddenCounts.portalWalks()
                    + "/"
                    + unculledCounts.portalWalks()
                    + " meshesCulled="
                    + seenCounts.portalMeshesCulled()
                    + "/"
                    + hiddenCounts.portalMeshesCulled()
                    + "/"
                    + unculledCounts.portalMeshesCulled()
                    + " objectSubmits="
                    + seenCounts.objectSubmits()
                    + "/"
                    + hiddenCounts.objectSubmits()
                    + "/"
                    + unculledCounts.objectSubmits();
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
}
