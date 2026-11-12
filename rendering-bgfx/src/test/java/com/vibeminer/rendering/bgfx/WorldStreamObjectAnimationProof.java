package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Actual GPU proof of World object idle animation (#384) on the production native owner: one
 * authored terrain tile whose late objects hold one animated model (a quad whose far edge swings on
 * its own bone over an 800 ms idle) at twelve placements near the camera and one beyond {@link
 * WorldObjectAnimation#RADIUS}. Checks that, with animation on, the near placements draw animated
 * from at most {@link WorldObjectAnimation#PHASES} poses and the far one stays static; that the
 * clock moves pixels (frames 400 ms apart differ) while animation off draws the baked pose; that a
 * repeated clock re-skins nothing; and that the animation revision asks for redraws only while
 * something animates. Writes the frames as PNGs. Authored data only.
 */
final class WorldStreamObjectAnimationProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(384, -384);
    private static final int WIDTH = 800, HEIGHT = 500;
    private static final TerrainCamera VIEW = new TerrainCamera(-30, -2, 22, 0, -.45);
    private static final TerrainCamera AWAY = new TerrainCamera(-30, -2, 22, Math.PI, -.45);
    private static final int NEAR = 12;
    private static long camera, sequence;

    private WorldStreamObjectAnimationProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
        System.out.println("OK " + message);
    }

    /** The animated quad at twelve near placements and one far one. */
    static WorldStreamTileWriter.Exterior exterior() throws Exception {
        var builder = WorldStreamTileWriter.Exterior.builder();
        var top = ByteBuffer.allocate(4 * 4 * 4);
        for (int i = 0; i < 16; i++) {
            top.put((byte) 40).put((byte) 200).put((byte) 90).put((byte) 255);
        }
        top.flip();
        builder.image(4, 4, WorldStreamObjectsFileTest.chain(4, 4, top));
        builder.model();
        var plain = WorldStreamObjectsFileTest.small().data();
        builder.mesh(
                FloatBuffer.wrap(plain.vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3, 2, 1, 0, 3, 1, 2}),
                new int[] {0, 12, 0},
                new int[] {WorldStreamObjectsFile.OPAQUE},
                IntBuffer.wrap(new int[4]));
        for (int i = 0; i < NEAR; i++) {
            builder.placement(0, -12 + i % 4 * 6.5, -14 + i / 4 * 8.5, 10.2, .5, 1, 0);
        }
        builder.placement(0, 110, -4, 10.2, .5, 1, 0);
        builder.animate(
                0,
                new int[] {0, 1, 2, 3},
                WorldObjectAnimationTest.bytes(WorldObjectAnimationTest.swing()));
        return builder.build();
    }

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Files.createDirectories(Path.of(arguments[0]));
        Path directory = Files.createTempDirectory(output, "run-");
        var session = new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        var exterior = exterior();
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-animation-pack").factory())) {
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
        require(objects.animations[0] != null, "the packed set carries the model's idle");
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
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Terrain upload did not drain");
                }
            }
            owner.commit(0, 1, List.of(lease));
            var late = owner.beginLate(lease, objects);
            while (!late.ready()) {
                owner.stepLate(late);
                owner.advance();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Late objects did not build");
                }
            }
            require(
                    owner.finishLate(late, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "the animated objects attach");
            owner.objectAnimation(false);
            owner.animationClockForProof(0);
            int[] baked = frame(owner, reader);
            var off = owner.animationCounts();
            save(output, "animation-off", baked);
            require(
                    off.animated() == 0 && off.animatedStatic() == NEAR + 1 && off.skins() == 0,
                    "animation off draws every placement static: " + off);
            require(owner.animationRevision() == -1, "nothing animated asks for no redraw");
            owner.objectAnimation(true);
            int[] start = frame(owner, reader);
            var first = owner.animationCounts();
            save(output, "animation-0ms", start);
            require(
                    first.animated() == NEAR && first.animatedStatic() == 1,
                    "the near placements animate and the far one stays static: " + first);
            require(
                    first.skins() >= 1
                            && first.skins() <= WorldObjectAnimation.PHASES
                            && first.skinVertices() == first.skins() * 4,
                    "one skin per phase, not per placement: " + first);
            require(
                    first.poseBytes()
                            == first.skins() * 4L * WorldStreamPackedObjects.GPU_VERTEX_BYTES,
                    "pose buffers are charged: " + first);
            require(owner.animationRevision() >= 0, "an animated frame asks for redraws");
            int[] again = frame(owner, reader);
            var repeated = owner.animationCounts();
            require(
                    repeated.skins() == 0 && repeated.animated() == NEAR,
                    "a repeated clock re-skins nothing: " + repeated);
            require(differing(start, again) == 0, "a repeated clock draws the same pixels");
            owner.animationClockForProof(400);
            int[] later = frame(owner, reader);
            var moved = owner.animationCounts();
            save(output, "animation-400ms", later);
            require(moved.skins() == first.skins(), "every pose re-skins when the clock steps");
            int changed = differing(start, later);
            require(changed > 200, "the idle moves pixels: " + changed);
            // Phase 0 at clock 0 is the baked Stand pose; the other phases are elsewhere in the
            // loop, so the crowd does not move in lockstep.
            int phased = differing(baked, start);
            require(phased > 0, "phase-offset placements differ from the baked pose: " + phased);
            // Facing away: the creatures are culled, so nothing animates and a still camera asks
            // for no redraws.
            int[] away = frame(owner, reader, AWAY);
            var behind = owner.animationCounts();
            require(
                    behind.animated() == 0 && owner.animationRevision() == -1,
                    "culled creatures neither animate nor ask for redraws: " + behind);
            require(
                    owner.releaseIdlePosesForProof() == 0,
                    "poses drawn within the last frames are kept");
            for (int i = 0; i < WorldObjectAnimation.IDLE_FRAMES + 1; i++) {
                frame(owner, reader, AWAY);
            }
            long released = owner.releaseIdlePosesForProof();
            require(
                    released == first.poseBytes() && owner.animationCounts().poseBytes() == 0,
                    "poses no frame drew lately give their bytes back: " + released);
            int[] back = frame(owner, reader);
            require(
                    owner.animationCounts().animated() == NEAR,
                    "facing them again re-creates their poses: " + owner.animationCounts());
            require(away.length == back.length, "frames kept their size");
            System.out.println(
                    "ANIMATION pixels changed: 0 -> 400 ms "
                            + changed
                            + ", baked -> phased "
                            + phased
                            + "; counts "
                            + first);
        }
    }

    private static int differing(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                count++;
            }
        }
        return count;
    }

    private static void save(Path output, String name, int[] pixels) throws Exception {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
    }

    private static int[] frame(WorldStreamNativeOwner owner, WorldFrameFileV2 reader)
            throws Exception {
        return frame(owner, reader, VIEW);
    }

    /** Submits one new camera revision and returns its published pixels (ARGB). */
    private static int[] frame(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, TerrainCamera view)
            throws Exception {
        long revision = ++camera;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var command = new WorldStreamControl.Camera(NONCE, revision, 1, WIDTH, HEIGHT, view, null);
        while (!owner.submit(command)) {
            owner.advance();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Camera submit");
            }
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
                var pixels = new int[WIDTH * HEIGHT];
                var buffer = IntBuffer.allocate(WIDTH * HEIGHT);
                buffer.put(frame.pixels()).flip();
                buffer.get(pixels);
                return pixels;
            }
            owner.advance();
        }
        throw new AssertionError("No frame for camera " + revision);
    }
}
