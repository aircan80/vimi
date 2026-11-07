package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Captures World terrain frames of one authored tile for before/after comparisons of the terrain
 * path: an untextured (fallback colour) and a textured tile, each seen close and from 600 to 1100
 * yd away (fogged). Writes each frame's raw ARGB pixels to {@code <name>.argb} in the output
 * directory. Authored data only; the comparison itself is done outside.
 */
final class WorldStreamTerrainCapture {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(555, -777);
    private static final int WIDTH = 800, HEIGHT = 500;

    private WorldStreamTerrainCapture() {}

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Files.createDirectories(Path.of(arguments[0]));
        var views =
                List.of(
                        new TerrainCamera(-100, -260, 60, 0, -0.4),
                        new TerrainCamera(600, -260, 120, Math.PI, -0.12));
        for (boolean textured : new boolean[] {false, true}) {
            Path directory = Files.createTempDirectory(output, "run-");
            var session =
                    new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, false);
            var lease = new WorldStreamState.Lease(0, 1, 1);
            WorldStreamPackedTile tile;
            try (var worker = Executors.newSingleThreadExecutor()) {
                tile =
                        worker.submit(
                                        () ->
                                                WorldStreamNativeProof.prepare(
                                                        NONCE, directory, 1, lease, 7, 19, 120,
                                                        textured))
                                .get(30, TimeUnit.SECONDS);
            }
            Path mapping = directory.resolve("frames");
            try (var frames =
                            WorldFrameFileV2.create(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                    var reader =
                            WorldFrameFileV2.open(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                    var owner =
                            new WorldStreamNativeOwner(
                                    session, frames, WorldStreamNativeProof.BACKEND)) {
                var upload = owner.begin(tile);
                while (!upload.finished()) {
                    owner.step(upload);
                    owner.advance();
                }
                owner.commit(0, 1, List.of(lease));
                long sequence = 0;
                for (int v = 0; v < views.size(); v++) {
                    long revision = v + 1;
                    var command =
                            new WorldStreamControl.Camera(
                                    NONCE, revision, 1, WIDTH, HEIGHT, views.get(v));
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
                                                1, WIDTH, HEIGHT, revision, revision, 1, 1));
                    }
                    if (frame == null) {
                        throw new AssertionError("No frame");
                    }
                    sequence = frame.metadata().sequence();
                    var bytes = ByteBuffer.allocate(WIDTH * HEIGHT * 4).order(ByteOrder.BIG_ENDIAN);
                    bytes.asIntBuffer().put(frame.pixels());
                    String name =
                            (textured ? "textured" : "fallback") + (v == 0 ? "-near" : "-far");
                    Files.write(output.resolve(name + ".argb"), bytes.array());
                    System.out.println("CAPTURED " + name);
                }
            }
        }
    }
}
