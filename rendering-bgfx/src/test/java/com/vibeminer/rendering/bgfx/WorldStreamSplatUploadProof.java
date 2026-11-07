package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Native proof of the splatting upload path (issue #174 step 2b-1): a textured tile with a splat
 * trailer creates the shared layer array and the slot's mask and table textures, uploads in bounded
 * steps beside the atlas, and still renders through the unchanged shader; a tile without a trailer
 * in another slot uploads as before.
 */
public final class WorldStreamSplatUploadProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(123, -456);
    private static final String SCOPE = "cd".repeat(32);

    private static long cameraRevision = 1;

    private WorldStreamSplatUploadProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "splat-");
        Path mapping = directory.resolve("frames-v2");
        var session = new WorldStreamEnvelope.Session(NONCE, SCOPE, 1);
        try (var worker =
                        Executors.newSingleThreadExecutor(
                                Thread.ofPlatform().daemon().name("world-proof-pack").factory());
                var frames = WorldFrameFileV2.create(mapping, NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(mapping, NONCE, SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session,
                                frames,
                                GpuViewerMain.backend(
                                        System.getProperty("vimi.proof.backend", "vulkan")))) {
            var splatted = new WorldStreamState.Lease(0, 1, 1);
            var plain = new WorldStreamState.Lease(1, 1, 2);
            WorldStreamNativeProof.splatForNextPrepare = WorldStreamTileSplatTest.splat(0, 1, 2);
            var packed =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    directory, 1, splatted, 7, 19, 180, true))
                            .get(30, TimeUnit.SECONDS);
            int[] peak = {0};
            upload(owner, packed, peak);
            require(owner.splatArrayCreated(), "Shared splat layer array created");
            boolean forced = Boolean.getBoolean(BgfxWorldStreamRenderer.SPLAT_RGBA8_PROPERTY);
            require(
                    owner.splatBlocks() == !forced,
                    "Layer array format: BC1 unless forced to the RGBA8 fallback");
            require(owner.splatted(0), "Splat inputs uploaded for the slot");
            var second =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    directory, 2, plain, 8, 19, 60, true))
                            .get(30, TimeUnit.SECONDS);
            owner.commit(0, 1, List.of(splatted));
            upload(owner, second, peak);
            require(!owner.splatted(1), "A packet without splat inputs uploads as before");
            owner.commit(1, 2, List.of(splatted, plain));
            long revision = ++cameraRevision;
            owner.submit(
                    new WorldStreamControl.Camera(
                            NONCE,
                            revision,
                            1,
                            256,
                            256,
                            new TerrainCamera(-100, -10, 50, 0, -.6)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            WorldFrameFileV2.Frame frame = null;
            long sequence = 0;
            while (frame == null && System.nanoTime() < deadline) {
                owner.advance();
                var candidate =
                        reader.readLatest(
                                sequence,
                                new WorldFrameFileV2.Barrier(1, 256, 256, 1, Long.MAX_VALUE, 2, 2));
                if (candidate != null && candidate.metadata().camera() == revision) {
                    frame = candidate;
                }
            }
            require(frame != null, "The unchanged shader still renders the splatted tile");
            var stats = owner.stats();
            require(stats.upload().liveUploadBytes() == 0, "Inputs drained");
            System.out.println(
                    "WORLD_SPLAT_UPLOAD_PASS backend="
                            + System.getProperty("vimi.proof.backend", "vulkan")
                            + " layers="
                            + (owner.splatBlocks() ? "BC1-256" : "RGBA8-128")
                            + " peakStepBytes="
                            + peak[0]
                            + " textures="
                            + stats.textures()
                            + " gpuHeapBytes="
                            + stats.gpuHeapBytes());
        }
    }

    private static void upload(
            WorldStreamNativeOwner owner, WorldStreamPackedTile packed, int[] peak)
            throws Exception {
        var upload = owner.begin(packed);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!upload.finished()) {
            int bytes = owner.step(upload);
            peak[0] = Math.max(peak[0], bytes);
            require(bytes <= 262144, "Upload step bytes " + bytes);
            owner.submit(
                    new WorldStreamControl.Camera(
                            NONCE,
                            ++cameraRevision,
                            1,
                            256,
                            256,
                            new TerrainCamera(-100, -10, 50, 0, -.6)));
            owner.advance();
            require(System.nanoTime() < deadline, "Upload did not drain");
        }
        require(upload.successful(), "Upload succeeded");
    }
}
