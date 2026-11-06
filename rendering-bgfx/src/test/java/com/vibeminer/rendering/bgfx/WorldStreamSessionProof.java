package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import com.vibeminer.rendering.world.TerrainCamera;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Authored owned-child protocol/pipe proof. No GUI, application stores or network. */
@SuppressWarnings("try")
public final class WorldStreamSessionProof {
    static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(123, -456);
    static final String SCOPE = "cd".repeat(32);
    static final WorldStreamEnvelope.Session SESSION =
            new WorldStreamEnvelope.Session(NONCE, SCOPE, 1);
    static final TerrainCamera CAMERA = new TerrainCamera(-100, -10, 50, 0, -.6);
    private static long frameSequence;

    private WorldStreamSessionProof() {}

    static void require(boolean valid, String message) {
        if (!valid) {
            throw new AssertionError(message);
        }
    }

    static String sha(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            var scratch = new byte[8192];
            for (int n; (n = input.read(scratch)) >= 0; ) {
                digest.update(scratch, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Process launch(Path directory, boolean blocked) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        if (blocked) {
            return new ProcessBuilder(
                            java,
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            WorldStreamSessionProof.class.getName(),
                            "child",
                            directory.toString(),
                            "true")
                    .start();
        }
        Path bootstrap = directory.resolve("session.wss");
        Files.write(bootstrap, WorldStreamEnvelope.encode(SESSION));
        return new ProcessBuilder(
                        java,
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        GpuViewerMain.class.getName(),
                        "--world-session-v2",
                        directory.toString(),
                        "--session-sha256",
                        sha(bootstrap),
                        "--frames",
                        directory.resolve("frames").toString(),
                        "--parent-pid",
                        Long.toString(ProcessHandle.current().pid()),
                        "--backend",
                        "vulkan")
                .start();
    }

    private static void child(Path directory, boolean instrument) throws Exception {
        var output = new FileOutputStream(FileDescriptor.out);
        OutputStream stream = output;
        if (instrument) {
            stream =
                    new OutputStream() {
                        private long completed;

                        @Override
                        public void write(int value) {
                            throw new AssertionError("bulk only");
                        }

                        @Override
                        public void write(byte[] bytes) throws java.io.IOException {
                            output.write(bytes);
                        }

                        @Override
                        public void flush() throws java.io.IOException {
                            output.flush();
                            System.err.println("WRITE " + ++completed);
                        }
                    };
        }
        try (var frames = WorldFrameFileV2.open(directory.resolve("frames"), NONCE, SCOPE)) {
            if (!WorldStreamSession.run(
                    SESSION,
                    directory,
                    System.in,
                    stream,
                    frames,
                    BGFX_RENDERER_TYPE_VULKAN,
                    ProcessHandle.current().parent().orElseThrow().pid(),
                    () -> Runtime.getRuntime().halt(73))) {
                System.exit(1);
            }
        }
    }

    static void send(Process child, WorldStreamControl.Command command) throws Exception {
        child.getOutputStream().write(WorldStreamControl.encode(command));
        child.getOutputStream().flush();
    }

    static final class Statuses {
        final ArrayBlockingQueue<String> lines = new ArrayBlockingQueue<>(64);
        final AtomicReference<WorldStreamState.Lease> credit = new AtomicReference<>();
        final StringBuilder observed = new StringBuilder();

        Statuses(Process child) {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var reader =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        child.getInputStream(),
                                                        StandardCharsets.US_ASCII))) {
                                    for (String line; (line = reader.readLine()) != null; ) {
                                        require(
                                                line.length() <= 255 && line.startsWith("WS2 "),
                                                "Invalid status framing");
                                        byte[] encoded =
                                                (line + "\n").getBytes(StandardCharsets.US_ASCII);
                                        var decoded =
                                                WorldStreamStatus.decode(encoded, encoded.length);
                                        require(
                                                decoded.nonce().equals(NONCE),
                                                "Wrong status session");
                                        var body = line.substring(37);
                                        if (body.startsWith("CREDIT ")) {
                                            var fields = body.split(" ");
                                            credit.set(
                                                    new WorldStreamState.Lease(
                                                            Integer.parseInt(fields[2]),
                                                            Long.parseLong(fields[3]),
                                                            Long.parseLong(fields[1])));
                                        }
                                        require(
                                                lines.offer(body, 2, TimeUnit.SECONDS),
                                                "Status fixture queue overflow");
                                    }
                                } catch (Exception error) {
                                    lines.offer("FIXTURE_FAILURE " + error);
                                }
                            });
        }

        String await(String prefix) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < until) {
                String line = lines.poll(1, TimeUnit.SECONDS);
                if (line != null) {
                    observed.append(line).append('\n');
                    if (line.startsWith(prefix)) {
                        return line;
                    }
                }
            }
            throw new AssertionError("Missing status " + prefix + " after\n" + observed);
        }
    }

    static void packet(Path directory, long tx, WorldStreamState.Lease lease, int red)
            throws Exception {
        var thread =
                Thread.ofVirtual()
                        .name("session-proof-packet")
                        .start(
                                () -> {
                                    try (var ignored =
                                            WorldStreamNativeProof.prepare(
                                                    directory, tx, lease, 7, 19, red, true)) {
                                        // Writer/codec/packing fixture verifies ownership; the
                                        // child independently decodes.
                                    } catch (Exception error) {
                                        throw new RuntimeException(error);
                                    }
                                });
        thread.join();
        require(
                Files.isRegularFile(directory.resolve("tile-" + tx + ".wst")),
                "Fixture packet missing");
    }

    static void offer(Process child, Path directory, long tx, WorldStreamState.Lease lease)
            throws Exception {
        Path path = directory.resolve("tile-" + tx + ".wst");
        send(
                child,
                new WorldStreamControl.Offer(
                        NONCE, tx, lease, Math.toIntExact(Files.size(path)), sha(path)));
    }

    static void commit(
            Process child, Path directory, long tx, long expected, WorldStreamState.Lease lease)
            throws Exception {
        var descriptor =
                new WorldStreamEnvelope.Commit(
                        NONCE,
                        tx,
                        expected,
                        expected + 1,
                        1,
                        List.of(
                                new WorldStreamEnvelope.Resident(
                                        lease.slot(), lease.generation())));
        Path file = directory.resolve("commit-" + tx + ".wsc");
        Files.write(file, WorldStreamEnvelope.encode(descriptor));
        send(child, new WorldStreamControl.Commit(NONCE, tx, sha(file)));
    }

    private static int frame(
            WorldFrameFileV2 reader, long content, long camera, long policy, int w, int h, int red)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var frame =
                    reader.readLatest(
                            frameSequence,
                            new WorldFrameFileV2.Barrier(
                                    policy, w, h, camera, camera, content, content));
            if (frame != null) {
                frameSequence = frame.metadata().sequence();
                int pixel = frame.pixels().get((h / 2) * w + w / 2);
                require(
                        Math.abs((pixel >>> 16 & 255) - red) <= 1 && pixel >>> 24 == 255,
                        "Wrong actual content pixels: " + Integer.toHexString(pixel));
                return pixel;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No accepted frame content=" + content + " camera=" + camera);
    }

    private static void eof(Path base) throws Exception {
        Path directory = Files.createTempDirectory(base, "eof-");
        try (var frames = WorldFrameFileV2.create(directory.resolve("frames"), NONCE, SCOPE)) {
            var child = launch(directory, false);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    child.getErrorStream()
                                            .transferTo(java.io.OutputStream.nullOutputStream());
                                } catch (Exception ignored) {
                                    // Owned process closure ends this observer.
                                }
                            });
            try {
                var statuses = new Statuses(child);
                statuses.await("CREDIT ");
                child.getOutputStream().close();
                statuses.await("CLOSED");
                require(
                        child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
                        "EOF did not close cleanly");
                System.out.println("WORLD_EOF_PASS pid=" + child.pid());
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    require(
                            child.waitFor(10, TimeUnit.SECONDS),
                            "Owned EOF fixture survived cleanup");
                }
            }
        }
    }

    private static void blockedStatus(Path base) throws Exception {
        Path directory = Files.createTempDirectory(base, "blocked-status-");
        try (var frames = WorldFrameFileV2.create(directory.resolve("frames"), NONCE, SCOPE)) {
            var child = launch(directory, true);
            var completed = new AtomicLong();
            var errors = new StringBuilder();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var reader = child.errorReader()) {
                                    for (String line; (line = reader.readLine()) != null; ) {
                                        if (line.startsWith("WRITE ")) {
                                            completed.set(Long.parseLong(line.substring(6)));
                                        }
                                        synchronized (errors) {
                                            if (errors.length() < 64 << 10) {
                                                errors.append(line).append('\n');
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // The owned child terminates by its watchdog, which closes the
                                    // pipe.
                                }
                            });
            try {
                long start = System.nanoTime(), expectedWrites = 2;
                int commands = 0;
                while (commands < 4096 && child.isAlive()) {
                    long until =
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(commands == 0 ? 10 : 1);
                    while (completed.get() < expectedWrites
                            && child.isAlive()
                            && System.nanoTime() < until) {
                        Thread.sleep(2);
                    }
                    if (completed.get() < expectedWrites) {
                        break;
                    }
                    commands++;
                    send(
                            child,
                            new WorldStreamControl.CancelUnused(
                                    NONCE,
                                    commands,
                                    new WorldStreamState.Lease(0, commands, commands)));
                    expectedWrites += 2;
                }
                require(
                        commands > 10 && commands < 4096,
                        "Fixture did not fill the real stdout pipe");
                // stdout is deliberately NEVER read, so write+flush cannot complete.
                require(
                        child.waitFor(15, TimeUnit.SECONDS),
                        "Blocked writer watchdog did not terminate owned child");
                require(
                        child.exitValue() == 73,
                        "Expected watchdog halt, exit=" + child.exitValue() + " stderr=" + errors);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                require(
                        elapsed >= 9_000 && elapsed < 25_000,
                        "Unexpected watchdog timing " + elapsed);
                Files.writeString(directory.resolve("stderr-progress.log"), errors);
                System.out.println(
                        "WORLD_BLOCKED_STATUS_PASS pid="
                                + child.pid()
                                + " commands="
                                + commands
                                + " completedWrites="
                                + completed.get()
                                + " elapsedMillis="
                                + elapsed
                                + " exit=73 stdoutNeverRead=true");
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    require(
                            child.waitFor(10, TimeUnit.SECONDS),
                            "Owned blocked fixture survived cleanup");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            child(Path.of(args[1]), Boolean.parseBoolean(args[2]));
            return;
        }
        Files.createDirectories(Path.of(args[0]));
        Path directory = Files.createTempDirectory(Path.of(args[0]), "session-");
        try (var frames = WorldFrameFileV2.create(directory.resolve("frames"), NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(directory.resolve("frames"), NONCE, SCOPE)) {
            var child = launch(directory, false);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    child.getErrorStream().transferTo(System.err);
                                } catch (Exception error) {
                                    throw new RuntimeException(error);
                                }
                            });
            try {
                var statuses = new Statuses(child);
                statuses.await("SESSION ");
                statuses.await("CREDIT ");
                var first = statuses.credit.get();
                send(child, new WorldStreamControl.Camera(NONCE, 1, 1, 256, 256, CAMERA));
                packet(directory, 1, first, 180);
                offer(child, directory, 1, first);
                statuses.await("INPUT_CONSUMED 1 TILE");
                statuses.await("STAGED 1 ");
                commit(child, directory, 2, 0, first);
                statuses.await("COMMITTED 2 0 1");
                statuses.await("CREDIT ");
                frame(reader, 1, 1, 1, 256, 256, 180);
                long pid = child.pid();
                for (int i = 2; i <= 12; i++) {
                    send(child, new WorldStreamControl.Camera(NONCE, i, 1, 256, 256, CAMERA));
                    frame(reader, 1, i, 1, 256, 256, 180);
                }
                var second = statuses.credit.get();
                packet(directory, 3, second, 100);
                offer(child, directory, 3, second);
                statuses.await("STAGED 3 ");
                commit(child, directory, 4, 1, second);
                statuses.await("COMMITTED 4 1 2");
                statuses.await("CREDIT ");
                frame(reader, 2, 12, 1, 256, 256, 100);
                var unused = statuses.credit.get();
                send(child, new WorldStreamControl.CancelUnused(NONCE, 5, unused));
                statuses.await("CANCELED 5 ");
                statuses.await("CREDIT ");
                send(child, new WorldStreamControl.Offer(NONCE, 3, second, 4096, "00".repeat(32)));
                statuses.await("REJECTED 3 STALE_TRANSACTION");
                Path badCommit = directory.resolve("commit-6.wsc");
                Files.write(badCommit, new byte[256]);
                String badDigest = sha(badCommit);
                send(child, new WorldStreamControl.Commit(NONCE, 6, badDigest));
                statuses.await("INPUT_CONSUMED 6 COMMIT");
                statuses.await("REJECTED 6 INVALID_COMMIT");
                Files.delete(badCommit);
                send(child, new WorldStreamControl.Commit(NONCE, 6, badDigest));
                statuses.await("REJECTED 6 STALE_TRANSACTION");
                var canceled = statuses.credit.get();
                packet(directory, 7, canceled, 30);
                offer(child, directory, 7, canceled);
                commit(child, directory, 8, 2, second);
                send(
                        child,
                        new WorldStreamControl.Cancel(
                                NONCE, 7, canceled.slot(), canceled.generation()));
                statuses.await("INPUT_CONSUMED 7 TILE");
                statuses.await("CANCELED 7 ");
                statuses.await("CREDIT ");
                if (!statuses.observed.toString().contains("COMMITTED 8 2 3")) {
                    statuses.await("COMMITTED 8 2 3");
                }
                frame(reader, 3, 12, 1, 256, 256, 100);
                send(child, new WorldStreamControl.Camera(NONCE, 13, 2, 800, 500, CAMERA));
                frame(reader, 3, 13, 2, 800, 500, 100);
                send(
                        child,
                        new WorldStreamControl.Camera(
                                NONCE, 14, 2, 800, 500, new TerrainCamera(-100, -10, 50, 0, 1)));
                // The analytical horizon gradient (WorldFog) at the frame's centre pixel.
                var analytical =
                        WorldFog.STREAM.skyPixel(
                                1,
                                com.vibeminer.rendering.world.TerrainCamera.focalPixels(800),
                                800,
                                500,
                                400,
                                250);
                int sky = frame(reader, 3, 14, 2, 800, 500, (int) Math.floor(analytical[0]));
                require(
                        (sky >>> 8 & 255) == (int) Math.floor(analytical[1])
                                && (sky & 255) == (int) Math.floor(analytical[2]),
                        "Changed view did not expose analytical sky");
                send(child, new WorldStreamControl.Camera(NONCE, 15, 2, 800, 500, CAMERA));
                frame(reader, 3, 15, 2, 800, 500, 100);
                require(child.pid() == pid && child.isAlive(), "Child replaced unexpectedly");
                send(child, new WorldStreamControl.Close(NONCE));
                statuses.await("CLOSED");
                require(
                        child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
                        "Child failed clean close");
                Files.writeString(directory.resolve("statuses.log"), statuses.observed);
                System.out.println(
                        "WORLD_SESSION_PASS pid="
                                + pid
                                + " content=3 cameras=15 canceled=2 resize=800x500");
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    require(
                            child.waitFor(10, TimeUnit.SECONDS),
                            "Owned fixture child did not terminate");
                }
            }
        }
        eof(Path.of(args[0]));
        blockedStatus(Path.of(args[0]));
    }
}
