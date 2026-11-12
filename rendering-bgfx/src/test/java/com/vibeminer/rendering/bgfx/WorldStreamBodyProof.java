package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.world.CollisionWorld;
import com.vibeminer.rendering.world.FollowCamera;
import com.vibeminer.rendering.world.ObjectTextureMips;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainGround;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * The walking body drawn by the real World renderer over an authored tile: its model attached once,
 * then posed per frame by the camera's body tail and seen from the follow camera (and from the
 * side), standing, walking and running at four times of each loop. Writes every frame as a PNG to
 * the output directory and checks that the body draws (pixels differ from the frame without it),
 * that a hidden body draws nothing, that the clock moves the legs (frames at different times
 * differ), and that a repeated pose is not uploaded again.
 *
 * <p>Arguments: the output directory, then optionally a body file ({@link WorldStreamBodyFile},
 * e.g. the measurement probe's {@code body-441.wsb} from a real build). Without one an authored
 * two-part body swings its leg.
 */
final class WorldStreamBodyProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(808, -909);
    private static final int WIDTH = 640, HEIGHT = 400;
    // The authored tile is flat at z 10 over x and y from -533 to 0.
    private static final double X = -150, Y = -260, GROUND = 10;

    private WorldStreamBodyProof() {}

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Files.createDirectories(Path.of(arguments[0]));
        var body =
                arguments.length > 1
                        ? WorldStreamBodyFile.decode(Files.readAllBytes(Path.of(arguments[1])))
                        : authored();
        System.out.println(
                "BODY "
                        + (arguments.length > 1 ? Path.of(arguments[1]).getFileName() : "authored")
                        + ": "
                        + body.vertexCount()
                        + " vertices, "
                        + body.indices().length / 3
                        + " triangles, "
                        + body.blends().length
                        + " ranges, sequences "
                        + java.util.Arrays.toString(body.sequences())
                        + ", scale "
                        + body.scale());
        Path directory = Files.createTempDirectory(output, "run-");
        var session = new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        WorldStreamPackedTile tile;
        try (var worker = Executors.newSingleThreadExecutor()) {
            tile =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    NONCE, directory, 1, lease, 7, 19, 120, true))
                            .get(30, TimeUnit.SECONDS);
        }
        // The height of the eye above the feet, as BodyMotion places it.
        double eye = height(body) * .9;
        Path mapping = directory.resolve("frames");
        try (var frames = WorldFrameFileV2.create(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var reader = WorldFrameFileV2.open(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session, frames, WorldStreamNativeProof.BACKEND)) {
            var upload = owner.begin(tile);
            while (!upload.finished()) {
                owner.step(upload);
                owner.advance();
            }
            owner.commit(0, 1, List.of(lease));
            // The body counts toward the objects' accounting: refused when its bytes do not fit,
            // and its texture handles held once attached.
            int heldBefore = owner.objectTexturesHeld();
            owner.objectBudgetForProof(WorldStreamBodyDraw.gpuBytes(body) - 1);
            boolean refused = false;
            try {
                owner.attachBody(body);
            } catch (java.io.IOException expected) {
                refused = true;
            }
            check(refused, "a body over the objects' budget is refused");
            check(owner.objectTexturesHeld() == heldBefore, "a refused body holds nothing");
            owner.objectBudgetForProof(session.objectBudgetBytes());
            owner.attachBody(body);
            check(
                    owner.objectTexturesHeld() == heldBefore + body.images().size(),
                    "the body's textures count as held object textures");
            System.out.println(
                    "BODY accounting: "
                            + body.images().size()
                            + " texture handles and "
                            + WorldStreamBodyDraw.gpuBytes(body)
                            + " bytes charged to the objects");
            var shooter = new Shooter(owner, reader);
            double yaw = .5;
            var follow = new FollowCamera();
            follow.distance(Math.max(3, height(body) * 3));
            var behind =
                    TerrainCamera.from(
                            follow.place(
                                    X,
                                    Y,
                                    GROUND + eye,
                                    yaw,
                                    -.25,
                                    0,
                                    CollisionWorld.terrain(TerrainGround.empty())));
            double side = Math.max(2.5, height(body) * 2.2);
            var beside =
                    new TerrainCamera(
                            X + Math.cos(yaw + Math.PI / 2) * side,
                            Y + Math.sin(yaw + Math.PI / 2) * side,
                            GROUND + eye * .7,
                            yaw - Math.PI / 2,
                            -.12);
            int[] none = shooter.shoot(behind, null);
            save(output, "follow-none", none);
            var hiddenPose = pose(yaw, 0, 0, true);
            int[] hidden = shooter.shoot(behind, hiddenPose);
            check(differing(none, hidden) == 0, "a hidden body draws nothing");
            check(owner.bodySubmits() == 0, "a hidden body submits nothing");
            var summary = new StringBuilder();
            for (int sequence : new int[] {0, 4, 5, 135}) {
                var animation = body.animation(sequence);
                if (animation == null) {
                    System.out.println("BODY sequence " + sequence + " not in the model");
                    continue;
                }
                int[] previous = null;
                int moving = 0;
                for (int k = 0; k < 4; k++) {
                    long millis = animation.durationMillis() * k / 4;
                    // Fly is posed in the air, a third of the body's height above the ground.
                    var pose =
                            sequence == 135
                                    ? new WorldStreamTransport.Body(
                                            X,
                                            Y,
                                            GROUND + height(body) / 3,
                                            yaw,
                                            sequence,
                                            millis,
                                            false)
                                    : pose(yaw, sequence, millis, false);
                    long uploads = owner.bodyUploads();
                    int[] seen = shooter.shoot(behind, pose);
                    check(owner.bodyUploads() == uploads + 1, "a new pose uploads once");
                    check(owner.bodySubmits() > 0, "the body submits its ranges");
                    int drawn = differing(none, seen);
                    check(drawn > 200, "the body covers pixels from the follow camera: " + drawn);
                    save(output, "follow-" + name(sequence) + "-" + k, seen);
                    int[] fromSide = shooter.shoot(beside, pose);
                    check(
                            owner.bodyUploads() == uploads + 1,
                            "the same pose is not uploaded again");
                    save(output, "side-" + name(sequence) + "-" + k, fromSide);
                    if (previous != null && differing(previous, fromSide) > 50) {
                        moving++;
                    }
                    previous = fromSide;
                    summary.append(
                            String.format(
                                    "BODY %s t=%d ms: %d body pixels from behind%n",
                                    name(sequence), millis, drawn));
                }
                if (sequence != 0) {
                    check(moving >= 2, name(sequence) + " moves between its four times");
                }
            }
            System.out.print(summary);
            if (arguments.length > 2) {
                // A swap: the second body replaces the first and holds only its own textures.
                var other = WorldStreamBodyFile.decode(Files.readAllBytes(Path.of(arguments[2])));
                owner.attachBody(other);
                check(
                        owner.objectTexturesHeld() == heldBefore + other.images().size(),
                        "a swapped body holds only its own textures");
                var otherAnimation = other.animation(0);
                long millis = otherAnimation == null ? 0 : otherAnimation.durationMillis() / 2;
                int[] seen = shooter.shoot(behind, pose(yaw, 0, millis, false));
                check(differing(none, seen) > 200, "the swapped body draws");
                save(output, "swapped-stand", seen);
                System.out.println(
                        "BODY swap to " + Path.of(arguments[2]).getFileName() + " drawn");
            }
            System.out.println("BODY proof passed; frames in " + output);
        }
    }

    private static String name(int sequence) {
        return switch (sequence) {
            case 0 -> "stand";
            case 4 -> "walk";
            case 5 -> "run";
            case 135 -> "fly";
            default -> "sequence" + sequence;
        };
    }

    private static WorldStreamTransport.Body pose(
            double yaw, int sequence, long millis, boolean hidden) {
        return new WorldStreamTransport.Body(X, Y, GROUND, yaw, sequence, millis, hidden);
    }

    /** The body's height: the highest drawn vertex times its scale. */
    private static double height(WorldStreamBodyFile.Body body) {
        double top = 0;
        float[] v = body.vertices();
        for (int i = 2; i < v.length; i += WorldStreamBodyFile.VERTEX_FLOATS) {
            top = Math.max(top, v[i]);
        }
        return Math.max(.5, top * body.scale());
    }

    /** Draws one frame per call, each with a new camera revision, and reads it back. */
    private static final class Shooter {
        private final WorldStreamNativeOwner owner;
        private final WorldFrameFileV2 reader;
        private long revision, sequence;

        Shooter(WorldStreamNativeOwner owner, WorldFrameFileV2 reader) {
            this.owner = owner;
            this.reader = reader;
        }

        int[] shoot(TerrainCamera view, WorldStreamTransport.Body body) throws java.io.IOException {
            long current = ++revision;
            var command =
                    new WorldStreamControl.Camera(
                            NONCE, current, 1, WIDTH, HEIGHT, view, null, body);
            while (!owner.submit(command)) {
                owner.advance();
            }
            WorldFrameFileV2.Frame frame = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (frame == null && System.nanoTime() < deadline) {
                owner.advance();
                frame =
                        reader.readLatest(
                                sequence,
                                new WorldFrameFileV2.Barrier(
                                        1, WIDTH, HEIGHT, current, current, 1, 1));
            }
            check(frame != null, "a frame for camera " + current);
            sequence = frame.metadata().sequence();
            int[] pixels = new int[WIDTH * HEIGHT];
            frame.pixels().get(pixels);
            return pixels;
        }
    }

    private static int differing(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            int x = a[i], y = b[i];
            int d =
                    Math.abs((x >> 16 & 255) - (y >> 16 & 255))
                            + Math.abs((x >> 8 & 255) - (y >> 8 & 255))
                            + Math.abs((x & 255) - (y & 255));
            if (d > 12) {
                count++;
            }
        }
        return count;
    }

    private static void save(Path output, String name, int[] pixels) throws Exception {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Body proof: " + what);
        }
    }

    /**
     * An authored body: a torso box on bone 0 and a leg box on bone 1, which swings about Y over
     * each 800 ms loop for Walk and Run; Stand holds still.
     */
    static WorldStreamBodyFile.Body authored() {
        var vertices = new ArrayList<Float>();
        var indices = new ArrayList<Integer>();
        var bones = new ArrayList<Integer>();
        box(vertices, indices, bones, -.3f, -.3f, .6f, .3f, .3f, 1.4f, 0);
        box(vertices, indices, bones, -.1f, .05f, 0f, .1f, .25f, .6f, 1);
        int count = vertices.size() / 8;
        float[] model = new float[vertices.size()];
        for (int i = 0; i < model.length; i++) {
            model[i] = vertices.get(i);
        }
        float[] rest = new float[count * 3], normals = new float[count * 3];
        int[] influenceBones = new int[count * 4], influenceWeights = new int[count * 4];
        int[] sources = new int[count];
        for (int v = 0; v < count; v++) {
            System.arraycopy(model, v * 8, rest, v * 3, 3);
            System.arraycopy(model, v * 8 + 3, normals, v * 3, 3);
            influenceBones[v * 4] = bones.get(v);
            influenceWeights[v * 4] = 255;
            sources[v] = v;
        }
        int[] triangles = indices.stream().mapToInt(Integer::intValue).toArray();
        var mesh =
                new PreviewMesh(
                        rest, triangles, List.of(new PreviewMesh.Part(0, 0, triangles.length)));
        var animations = new ArrayList<PreviewAnimation>();
        for (int sequence : new int[] {0, 4, 5}) {
            float swing = sequence == 0 ? 0 : (float) Math.sin(sequence == 4 ? .4 : .7);
            float w = (float) Math.sqrt(1 - swing * swing);
            var root = new PreviewAnimation.Bone(-1, 0, 0, 0, null, null, null, false);
            var leg =
                    new PreviewAnimation.Bone(
                            0,
                            0,
                            0,
                            .6f,
                            null,
                            new PreviewAnimation.Track(
                                    PreviewAnimation.Interpolation.LINEAR,
                                    -1,
                                    4,
                                    new int[] {0, 400, 800},
                                    new float[] {0, swing, 0, w, 0, -swing, 0, w, 0, swing, 0, w}),
                            null,
                            false);
            animations.add(
                    new PreviewAnimation(
                            "Body",
                            800,
                            new long[0],
                            List.of(root, leg),
                            mesh,
                            normals,
                            influenceBones,
                            influenceWeights,
                            sequence == 0 ? 0 : sequence == 4 ? 2.5f : 7f));
        }
        byte[] chain = new byte[ObjectTextureMips.chainPixels(4, 4) * 4];
        for (int i = 0; i < chain.length; i += 4) {
            chain[i] = (byte) 230;
            chain[i + 1] = (byte) 120;
            chain[i + 2] = (byte) 40;
            chain[i + 3] = (byte) 255;
        }
        return new WorldStreamBodyFile.Body(
                model,
                triangles,
                new int[] {0, triangles.length, 0},
                new int[] {WorldStreamObjectsFile.OPAQUE},
                List.of(new WorldStreamBodyFile.Image(4, 4, chain)),
                sources,
                1,
                new int[] {0, 4, 5},
                animations);
    }

    /** An axis-aligned box of 24 vertices (flat normals) on one bone. */
    private static void box(
            List<Float> vertices,
            List<Integer> indices,
            List<Integer> bones,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            int bone) {
        float[][] corners = new float[8][];
        for (int i = 0; i < 8; i++) {
            corners[i] =
                    new float[] {
                        (i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1
                    };
        }
        int[][] faces = {
            {0, 2, 6, 4, -1, 0, 0}, {1, 5, 7, 3, 1, 0, 0}, {0, 4, 5, 1, 0, -1, 0},
            {2, 3, 7, 6, 0, 1, 0}, {0, 1, 3, 2, 0, 0, -1}, {4, 6, 7, 5, 0, 0, 1}
        };
        for (int[] face : faces) {
            int base = vertices.size() / 8;
            for (int k = 0; k < 4; k++) {
                float[] c = corners[face[k]];
                vertices.addAll(
                        List.of(
                                c[0],
                                c[1],
                                c[2],
                                (float) face[4],
                                (float) face[5],
                                (float) face[6],
                                k == 1 || k == 2 ? 1f : 0f,
                                k >= 2 ? 1f : 0f));
                bones.add(bone);
            }
            indices.addAll(List.of(base, base + 1, base + 2, base, base + 2, base + 3));
        }
    }
}
