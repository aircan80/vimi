package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end native proof of the walking body through the real World child process: one committed
 * tile, a camera whose body tail poses a body before any model is attached (nothing is drawn), then
 * the BODY_MODEL command with an authored body file as the parent transport writes it. Checks the
 * child's one {@code BODY_DONE 1 DRAWN}, that the next frame shows the body, that a hidden pose
 * shows the frame without it, and that a repeated BODY_MODEL is not answered.
 */
final class WorldStreamBodySessionProof {
    private static final TerrainCamera VIEW = new TerrainCamera(-126, -260, 12, 0, -.2);

    private WorldStreamBodySessionProof() {}

    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args[0]));
        Path directory = Files.createTempDirectory(Path.of(args[0]), "body-session-");
        var nonce = WorldStreamSessionProof.NONCE;
        var session =
                new WorldStreamEnvelope.Session(nonce, WorldStreamSessionProof.SCOPE, 1, true);
        var pose = new WorldStreamTransport.Body(-120, -260, 10, Math.PI, 4, 200, false);
        var hidden = new WorldStreamTransport.Body(-120, -260, 10, Math.PI, 4, 200, true);
        try (var frames =
                        WorldFrameFileV2.create(
                                directory.resolve("frames"), nonce, WorldStreamSessionProof.SCOPE);
                var reader =
                        WorldFrameFileV2.open(
                                directory.resolve("frames"),
                                nonce,
                                WorldStreamSessionProof.SCOPE)) {
            var child = WorldStreamSessionProof.launch(directory, session);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    child.getErrorStream().transferTo(System.err);
                                } catch (Exception ignored) {
                                    // The child's diagnostics are only echoed.
                                }
                            });
            try {
                var statuses = new WorldStreamSessionProof.Statuses(child);
                statuses.await("SESSION ");
                statuses.await("CREDIT ");
                var first = statuses.credit.get();
                WorldStreamSessionProof.send(child, camera(nonce, 1, pose));
                WorldStreamSessionProof.packet(directory, 1, first, 180);
                WorldStreamSessionProof.offer(child, directory, 1, first);
                statuses.await("STAGED 1 ");
                WorldStreamSessionProof.commit(child, directory, 2, 0, first);
                statuses.await("COMMITTED 2 0 1");
                int[] before = frame(reader, 1, 400, 250);
                byte[] file = WorldStreamBodyFile.encode(WorldStreamBodyProof.authored());
                Files.write(directory.resolve(WorldStreamControl.BodyModel.BASENAME), file);
                String sha =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(file));
                var command = new WorldStreamControl.BodyModel(nonce, file.length, sha);
                WorldStreamSessionProof.send(child, command);
                statuses.await("BODY_DONE 1 DRAWN");
                WorldStreamSessionProof.send(child, camera(nonce, 2, pose));
                int[] drawn = frame(reader, 2, 400, 250);
                int changed = differing(before, drawn);
                WorldStreamSessionProof.require(
                        changed > 500, "Body not visible: " + changed + " pixels changed");
                WorldStreamSessionProof.send(child, camera(nonce, 3, hidden));
                int[] none = frame(reader, 3, 400, 250);
                WorldStreamSessionProof.require(
                        differing(before, none) == 0, "A hidden body was drawn");
                WorldStreamSessionProof.send(child, command);
                WorldStreamSessionProof.send(child, new WorldStreamControl.Close(nonce));
                statuses.await("CLOSED");
                WorldStreamSessionProof.require(
                        !statuses.observed.toString().matches("(?s).*BODY_DONE.*BODY_DONE.*"),
                        "A repeated BODY_MODEL was answered");
                WorldStreamSessionProof.require(
                        child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
                        "Child failed clean close");
                failedBody(Path.of(args[0]));
                System.out.println(
                        "WORLD_BODY_SESSION_PASS pid="
                                + child.pid()
                                + " outcome=DRAWN changed="
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

    /**
     * A second session sent a body file that passes its digest but not the body file's checks: the
     * child answers {@code BODY_DONE 1 FAILED} and keeps drawing frames for posed cameras.
     */
    @SuppressWarnings("try")
    private static void failedBody(Path parent) throws Exception {
        Path directory = Files.createTempDirectory(parent, "body-failed-");
        var nonce = WorldStreamSessionProof.NONCE;
        var session =
                new WorldStreamEnvelope.Session(nonce, WorldStreamSessionProof.SCOPE, 1, true);
        var pose = new WorldStreamTransport.Body(-120, -260, 10, Math.PI, 4, 200, false);
        long saved = sequence;
        sequence = 0;
        try (var frames =
                        WorldFrameFileV2.create(
                                directory.resolve("frames"), nonce, WorldStreamSessionProof.SCOPE);
                var reader =
                        WorldFrameFileV2.open(
                                directory.resolve("frames"),
                                nonce,
                                WorldStreamSessionProof.SCOPE)) {
            var child = WorldStreamSessionProof.launch(directory, session);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    child.getErrorStream().transferTo(System.err);
                                } catch (Exception ignored) {
                                    // Echo only.
                                }
                            });
            try {
                var statuses = new WorldStreamSessionProof.Statuses(child);
                statuses.await("SESSION ");
                statuses.await("CREDIT ");
                var first = statuses.credit.get();
                WorldStreamSessionProof.send(child, camera(nonce, 1, pose));
                WorldStreamSessionProof.packet(directory, 1, first, 180);
                WorldStreamSessionProof.offer(child, directory, 1, first);
                statuses.await("STAGED 1 ");
                WorldStreamSessionProof.commit(child, directory, 2, 0, first);
                statuses.await("COMMITTED 2 0 1");
                frame(reader, 1, 400, 250);
                byte[] file = WorldStreamBodyFile.encode(WorldStreamBodyProof.authored());
                java.nio.ByteBuffer.wrap(file).putInt(4, 0); // no vertices
                Files.write(directory.resolve(WorldStreamControl.BodyModel.BASENAME), file);
                String sha =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(file));
                WorldStreamSessionProof.send(
                        child, new WorldStreamControl.BodyModel(nonce, file.length, sha));
                statuses.await("BODY_DONE 1 FAILED");
                WorldStreamSessionProof.send(child, camera(nonce, 2, pose));
                frame(reader, 2, 400, 250);
                WorldStreamSessionProof.send(child, new WorldStreamControl.Close(nonce));
                statuses.await("CLOSED");
                WorldStreamSessionProof.require(
                        child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
                        "Child failed clean close after a failed body");
                System.out.println("WORLD_BODY_SESSION_FAILED_BODY_PASS pid=" + child.pid());
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            sequence = saved;
        }
    }

    private static WorldStreamControl.Camera camera(
            WorldStreamState.Nonce nonce, long revision, WorldStreamTransport.Body body) {
        return new WorldStreamControl.Camera(nonce, revision, 1, 400, 250, VIEW, null, body);
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
