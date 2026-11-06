package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Actual GLOBAL child process through the public parent transport: first CAM, resize, continuous
 * CAM, cooperative CLOSE drain, close-before-first-CAM and mismatched mapping identity. Authored
 * data only; Vulkan by default.
 */
public final class GlobalWmoChildProof {
    private GlobalWmoChildProof() {}

    private static final byte[] IMAGE = {
        (byte) 255,
        0,
        0,
        (byte) 255,
        0,
        (byte) 255,
        0,
        (byte) 255,
        0,
        0,
        (byte) 255,
        (byte) 255,
        (byte) 255,
        (byte) 255,
        (byte) 255,
        (byte) 255
    };

    private static final GlobalWmoAuthoredData AUTHORED =
            new GlobalWmoAuthoredData(
                    new float[] {
                        10, -10, -10, 0, 0, 10, 10, -10, 1, 0, 10, -10, 10, 0, 1, 10, 10, 10, 1, 1
                    },
                    new int[] {0, 1, 2, 2, 1, 3},
                    List.of(IMAGE),
                    List.of(new WorldGlobalHashes.Batch(0, 0, 0, 6)));

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        Files.createDirectories(directory);
        String backend = arguments.length > 1 ? arguments[1] : "vulkan";
        int samples = cooperative(directory, backend, starter(directory, false));
        closeBeforeFirstCamera(directory, backend);
        mismatchedMapping(directory, backend);
        System.out.println(
                "GLOBAL_CHILD_PROOF_PASS samples="
                        + samples
                        + " firstCam=true resize=true continuous=true closeDrain=true"
                        + " closeBeforeCam=true mismatchedMappingRefused=true backend="
                        + backend);
    }

    private static WorldGlobalTransport.Starter starter(Path directory, boolean tamperNonce) {
        return argv -> {
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("com.vibeminer.rendering.bgfx.GpuViewerMain");
            var copy = new ArrayList<>(argv);
            if (tamperNonce) {
                int at = copy.indexOf("--global-frame-nonce") + 1;
                long value = Long.parseUnsignedLong(copy.get(at), 16) ^ 1L;
                copy.set(at, java.util.HexFormat.of().toHexDigits(value == 0 ? 2 : value));
            }
            command.addAll(copy);
            return new ProcessBuilder(command)
                    .redirectError(
                            ProcessBuilder.Redirect.appendTo(
                                    directory.resolve("child-stderr.log").toFile()))
                    .start();
        };
    }

    private static WorldGlobalSceneWriter.MovedInput input(Path run) throws Exception {
        Path inputDir = Files.createDirectory(run.resolve("global-source-1"));
        var destination =
                WorldGlobalSceneWriter.Destination.create(inputDir, new Object(), "7".repeat(64));
        return WorldGlobalSceneWriter.writeUnadvertised(destination, AUTHORED, () -> false).move();
    }

    private static int cooperative(Path directory, String backend, WorldGlobalTransport.Starter s)
            throws Exception {
        Path run = Files.createTempDirectory(directory, "run-");
        var input = input(run);
        Path inputFile = input.path();
        var session = WorldGlobalTransport.launch(run, input, backend, s);
        session.inputConsumed().get(30, TimeUnit.SECONDS);
        input.close();
        require(!Files.exists(inputFile), "consumed input deleted while child lives");
        int samples = 0;
        var camera = new WorldGlobalTransport.Camera(0, 0, 0, 0, 0);
        int[][] sizes = {{800, 500}, {317, 163}, {160, 80}};
        long floor = 0;
        for (int[] size : sizes) {
            long revision = session.camera(size[0], size[1], camera);
            if (floor == 0) {
                session.ready().get(30, TimeUnit.SECONDS);
            }
            floor = revision;
            var frame = await(session, revision, revision, size[0], size[1]);
            samples += oracle(frame, camera);
        }
        // Continuous movement: the newest frame must eventually match the last revision sent.
        long last = 0;
        var moving = camera;
        for (int i = 0; i < 40; i++) {
            moving = moving.move(0, .02, .01);
            last = session.camera(160, 80, moving);
            Thread.sleep(2);
        }
        samples += oracle(await(session, floor, last, 160, 80), moving);
        session.closeAsync();
        session.terminated().get(30, TimeUnit.SECONDS);
        require(session.poll(1, 160, 80) == null, "no frame after retirement");
        require(!Files.exists(run.resolve("global-source-1")) || isEmpty(run), "input retired");
        try (var left = Files.list(run)) {
            require(
                    left.noneMatch(p -> p.getFileName().toString().startsWith("global-frames-")),
                    "frames directory retired");
        }
        return samples;
    }

    private static boolean isEmpty(Path run) throws Exception {
        try (var entries = Files.list(run.resolve("global-source-1"))) {
            return entries.findAny().isEmpty();
        }
    }

    private static void closeBeforeFirstCamera(Path directory, String backend) throws Exception {
        Path run = Files.createTempDirectory(directory, "early-");
        var input = input(run);
        try {
            var session =
                    WorldGlobalTransport.launch(run, input, backend, starter(directory, false));
            session.inputConsumed().get(30, TimeUnit.SECONDS);
            session.closeAsync();
            session.terminated().get(30, TimeUnit.SECONDS);
            require(!session.ready().isDone(), "no READY without a parent camera");
        } finally {
            input.close();
        }
    }

    private static void mismatchedMapping(Path directory, String backend) throws Exception {
        Path run = Files.createTempDirectory(directory, "mismatch-");
        var input = input(run);
        try {
            var session =
                    WorldGlobalTransport.launch(run, input, backend, starter(directory, true));
            try {
                session.terminated().get(30, TimeUnit.SECONDS);
                throw new AssertionError("Mismatched mapping must not drain healthily");
            } catch (ExecutionException expected) {
                // Actual child refused before input consumption.
            }
            require(
                    !session.inputConsumed().isDone()
                            || session.inputConsumed().isCompletedExceptionally(),
                    "no consumed ack");
        } finally {
            input.close();
        }
    }

    private static WorldGlobalTransport.Frame await(
            WorldGlobalTransport.Session session, long floor, long revision, int width, int height)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        WorldGlobalTransport.Frame newest = null;
        while (newest == null || newest.revision() != revision) {
            var frame = session.poll(floor, width, height);
            if (frame != null) {
                require(frame.width() == width && frame.height() == height, "selected dims");
                require(frame.revision() >= floor && frame.revision() <= revision, "interval");
                newest = frame;
            }
            require(System.nanoTime() < deadline, "frame deadline " + revision);
            Thread.sleep(2);
        }
        return newest;
    }

    private static int oracle(
            WorldGlobalTransport.Frame frame, WorldGlobalTransport.Camera camera) {
        int width = frame.width(), height = frame.height(), samples = 0;
        var pixels = frame.pixels();
        for (int y : new int[] {height / 4, height * 3 / 4}) {
            for (int x : new int[] {width / 4, width * 3 / 4}) {
                double nativeY =
                        camera.y() - (x + .5 - width * .5) * 10 / TerrainCamera.focalPixels(width);
                double nativeZ =
                        camera.z() - (y + .5 - height * .5) * 10 / TerrainCamera.focalPixels(width);
                double u = (nativeY + 10) / 20, v = (nativeZ + 10) / 20;
                int actual = pixels.get(y * width + x);
                for (int c = 0; c < 3; c++) {
                    int expected = sample(u, v, c);
                    int channel = (actual >>> (16 - c * 8)) & 255;
                    require(
                            Math.abs(expected - channel) <= 2,
                            "child UV oracle "
                                    + x
                                    + ","
                                    + y
                                    + " c"
                                    + c
                                    + " "
                                    + expected
                                    + "/"
                                    + channel);
                }
                require((actual >>> 24) == 255, "opaque");
                samples++;
            }
        }
        return samples;
    }

    private static int sample(double u, double v, int channel) {
        double x = u * 2 - .5, y = v * 2 - .5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy, result = 0;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int at = (Math.floorMod(iy + dy, 2) * 2 + Math.floorMod(ix + dx, 2)) * 4 + channel;
                result += (IMAGE[at] & 255) * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
            }
        }
        return (int) Math.round(result);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
