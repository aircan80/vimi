package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * End-to-end native proof of the WDL horizon (#372) through the real World child process: one
 * committed tile, then the HORIZON command with an authored WDL. Checks the child's one {@code
 * HORIZON_DONE 1 DRAWN}, its upload diagnostic with non-zero tiles and triangles, and that the next
 * frame of the same camera shows the horizon where the frame before it showed sky.
 */
final class WorldStreamHorizonSessionProof {
    private static final TerrainCamera VIEW = new TerrainCamera(-100, -10, 140, 0.4, -0.05);
    private static final Pattern UPLOADED =
            Pattern.compile("World horizon uploaded: (\\d+) tiles, (\\d+) triangles, .*");

    private WorldStreamHorizonSessionProof() {}

    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args[0]));
        Path directory = Files.createTempDirectory(Path.of(args[0]), "horizon-session-");
        var nonce = WorldStreamSessionProof.NONCE;
        try (var frames =
                        WorldFrameFileV2.create(
                                directory.resolve("frames"), nonce, WorldStreamSessionProof.SCOPE);
                var reader =
                        WorldFrameFileV2.open(
                                directory.resolve("frames"),
                                nonce,
                                WorldStreamSessionProof.SCOPE)) {
            var child = WorldStreamSessionProof.launch(directory, false);
            var uploaded = new java.util.concurrent.CompletableFuture<int[]>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var errors =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        child.getErrorStream(),
                                                        StandardCharsets.UTF_8))) {
                                    for (String line; (line = errors.readLine()) != null; ) {
                                        System.err.println(line);
                                        var match = UPLOADED.matcher(line);
                                        if (match.matches()) {
                                            uploaded.complete(
                                                    new int[] {
                                                        Integer.parseInt(match.group(1)),
                                                        Integer.parseInt(match.group(2))
                                                    });
                                        }
                                    }
                                } catch (Exception error) {
                                    uploaded.completeExceptionally(error);
                                }
                            });
            try {
                var statuses = new WorldStreamSessionProof.Statuses(child);
                statuses.await("SESSION ");
                statuses.await("CREDIT ");
                var first = statuses.credit.get();
                WorldStreamSessionProof.send(
                        child, new WorldStreamControl.Camera(nonce, 1, 1, 400, 250, VIEW));
                WorldStreamSessionProof.packet(directory, 1, first, 180);
                WorldStreamSessionProof.offer(child, directory, 1, first);
                statuses.await("STAGED 1 ");
                WorldStreamSessionProof.commit(child, directory, 2, 0, first);
                statuses.await("COMMITTED 2 0 1");
                int[] before = frame(reader, 1, 400, 250);
                // The horizon file and command, as the parent transport writes them.
                byte[] wdl = wdl();
                Files.write(directory.resolve(WorldStreamControl.Horizon.BASENAME), wdl);
                String sha =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(wdl));
                WorldStreamSessionProof.send(
                        child, new WorldStreamControl.Horizon(nonce, wdl.length, sha));
                statuses.await("HORIZON_DONE 1 DRAWN");
                int[] counts = uploaded.get(20, TimeUnit.SECONDS);
                WorldStreamSessionProof.require(
                        counts[0] > 0 && counts[1] > 0, "Horizon stats are zero");
                WorldStreamSessionProof.send(
                        child, new WorldStreamControl.Camera(nonce, 2, 1, 400, 250, VIEW));
                int[] after = frame(reader, 2, 400, 250);
                int changed = 0;
                for (int i = 0; i < before.length; i++) {
                    if (before[i] != after[i]) {
                        changed++;
                    }
                }
                WorldStreamSessionProof.require(
                        changed > before.length / 10,
                        "Horizon not visible: " + changed + " of " + before.length);
                // The real child's render loop sends its heartbeat (#306).
                statuses.await("ALIVE ");
                // A second HORIZON in the same session is ignored: no second reply, no failure.
                WorldStreamSessionProof.send(
                        child, new WorldStreamControl.Horizon(nonce, wdl.length, sha));
                WorldStreamSessionProof.send(child, new WorldStreamControl.Close(nonce));
                statuses.await("CLOSED");
                WorldStreamSessionProof.require(
                        !statuses.observed.toString().matches("(?s).*HORIZON_DONE.*HORIZON_DONE.*"),
                        "A repeated HORIZON was answered");
                WorldStreamSessionProof.require(
                        child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
                        "Child failed clean close");
                System.out.println(
                        "WORLD_HORIZON_SESSION_PASS pid="
                                + child.pid()
                                + " outcome=DRAWN tiles="
                                + counts[0]
                                + " triangles="
                                + counts[1]
                                + " changed="
                                + changed
                                + "/"
                                + before.length);
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    /** The native horizon proof's hills, as WDL bytes. */
    private static byte[] wdl() {
        return WorldStreamHorizonProof.hillsBytes();
    }

    private static long sequence;

    private static int[] frame(WorldFrameFileV2 reader, long camera, int width, int height)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            var frame =
                    reader.readLatest(
                            sequence,
                            new WorldFrameFileV2.Barrier(1, width, height, camera, camera, 1, 1));
            if (frame != null) {
                sequence = frame.metadata().sequence();
                int[] pixels = new int[width * height];
                frame.pixels().get(pixels);
                return pixels;
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No frame for camera " + camera);
    }
}
