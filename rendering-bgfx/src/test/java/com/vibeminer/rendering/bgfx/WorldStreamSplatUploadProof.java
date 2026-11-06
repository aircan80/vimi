package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Native proof of terrain splatting (issue #174 steps 2b-1 and 2b-2): a textured tile with a splat
 * trailer creates the shared layer array and the slot's mask and table textures and uploads in
 * bounded steps beside the atlas; a tile without a trailer in another slot uploads as before. Then
 * the shader's blend is checked in actual pixels: near ground shows the layers blended by the mask
 * and tinted by its MCCV, exactly as the atlas composer weighs them; a chunk whose table has no
 * layers keeps the atlas texel; and ground where the atlas is no longer magnified fades to it.
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
            int maskSide =
                    "high".equals(System.getProperty("vimi.proof.splatMask"))
                            ? com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK
                            : com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK;
            WorldStreamNativeProof.splatForNextPrepare = blendSplat(maskSide);
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
            long maskBytes = owner.splatMaskBytes();
            require(
                    maskBytes
                            == com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(
                                            maskSide, maskSide)
                                    * 4L,
                    "One slot's mask chain at its tier's side: " + maskBytes);
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
            require(frame != null, "The splatted tile renders");
            // Only the splatted tile from here: the plain one covers the same ground.
            owner.commit(2, 3, List.of(splatted));
            int[] near = centre(owner, reader, new TerrainCamera(-100, -10, 14, 0, -1.2));
            int[] expected = blended();
            require(
                    close(near, expected),
                    "Near splat pixel "
                            + java.util.Arrays.toString(near)
                            + ", blend "
                            + java.util.Arrays.toString(expected));
            int[] missing = centre(owner, reader, new TerrainCamera(-100, -45, 14, 0, -1.2));
            require(
                    close(missing, ATLAS),
                    "A chunk without layers keeps the atlas: "
                            + java.util.Arrays.toString(missing));
            int[] far = centre(owner, reader, new TerrainCamera(-100, -10, 160, 0, -1.2));
            require(
                    close(far, ATLAS),
                    "Ground the atlas no longer magnifies fades to it: "
                            + java.util.Arrays.toString(far));
            var stats = owner.stats();
            require(stats.upload().liveUploadBytes() == 0, "Inputs drained");
            System.out.println(
                    "WORLD_SPLAT_UPLOAD_PASS backend="
                            + System.getProperty("vimi.proof.backend", "vulkan")
                            + " layers="
                            + (owner.splatBlocks() ? "BC1-256" : "RGBA8-128")
                            + " mask="
                            + maskSide
                            + " maskBytesPerSlot="
                            + maskBytes
                            + " near="
                            + rgb(near)
                            + " missing="
                            + rgb(missing)
                            + " far="
                            + rgb(far)
                            + " peakStepBytes="
                            + peak[0]
                            + " textures="
                            + stats.textures()
                            + " gpuHeapBytes="
                            + stats.gpuHeapBytes());
        }
    }

    /** The fixture atlas colour (WorldStreamNativeProof.prepare with red 180). */
    private static final int[] ATLAS = {180, 20, 30};

    /** Solid layer colours, each exact as a BC1 565 endpoint: red, green, blue, yellow. */
    private static final int[] LAYER_565 = {0xf800, 0x07e0, 0x001f, 0xffe0};

    /** Mask texel of every splatted chunk: layers 1 to 3 weights and the MCCV brightness. */
    private static final int MASK_R = 64, MASK_G = 32, MASK_B = 0, MASK_A = 192;

    /**
     * A trailer whose chunks in even atlas columns draw array layers 0 to 3 under one uniform mask,
     * and whose odd columns have no layers (255, as the parent writes for a missing layer).
     */
    static WorldStreamTileFile.Splat blendSplat(int maskSide) {
        byte[] table = new byte[WorldStreamTileFile.SPLAT_TABLE_BYTES];
        for (int chunk = 0; chunk < 256; chunk++) {
            boolean layers = chunk % 2 == 0;
            for (int layer = 0; layer < 4; layer++) {
                table[chunk * 4 + layer] = (byte) (layers ? layer : WorldStreamTileFile.SPLAT_NONE);
            }
        }
        byte[] alpha =
                new byte[com.vibeminer.rendering.world.TerrainTileSplat.alphaBytes(maskSide)];
        for (int at = 0; at < alpha.length; at += 4) {
            alpha[at] = (byte) MASK_R;
            alpha[at + 1] = (byte) MASK_G;
            alpha[at + 2] = (byte) MASK_B;
            alpha[at + 3] = (byte) MASK_A;
        }
        byte[][] pixels = new byte[4][];
        for (int layer = 0; layer < 4; layer++) {
            // Every block of every level is the solid colour: c0 = colour > c1 = 0, indices 0.
            var chain =
                    java.nio.ByteBuffer.allocate(WorldStreamTileFile.SPLAT_LAYER_BYTES)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            while (chain.hasRemaining()) {
                chain.putShort((short) LAYER_565[layer]).putShort((short) 0).putInt(0);
            }
            pixels[layer] = chain.array();
        }
        return new WorldStreamTileFile.Splat(table, alpha, new int[] {0, 1, 2, 3}, pixels);
    }

    /** The composer's blend of the solid layers under the mask, tinted by its MCCV, 0..255. */
    static int[] blended() {
        double a1 = MASK_R / 255.0, a2 = MASK_G / 255.0, a3 = MASK_B / 255.0;
        double sum = a1 + a2 + a3, w0 = Math.max(0, 1 - sum), total = Math.max(1, w0 + sum);
        double[] weights = {w0, a1, a2, a3};
        int[] result = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            double value = 0;
            for (int layer = 0; layer < 4; layer++) {
                value += weights[layer] * expand(LAYER_565[layer], channel);
            }
            result[channel] = (int) Math.min(255, value / total * MASK_A / 128.0);
        }
        return result;
    }

    /** One 565 channel expanded to 8 bits, as BC1 decoding does. */
    private static int expand(int rgb565, int channel) {
        return switch (channel) {
            case 0 -> (rgb565 >>> 11 << 3) | (rgb565 >>> 13);
            case 1 -> (rgb565 >>> 5 & 63) << 2 | (rgb565 >>> 9 & 3);
            default -> (rgb565 & 31) << 3 | (rgb565 >>> 2 & 7);
        };
    }

    private static boolean close(int[] actual, int[] expected) {
        for (int i = 0; i < 3; i++) {
            if (Math.abs(actual[i] - expected[i]) > 2) {
                return false;
            }
        }
        return true;
    }

    private static String rgb(int[] value) {
        return String.format("%02x%02x%02x", value[0], value[1], value[2]);
    }

    /** The centre pixel of a frame drawn from {@code view}, as {r, g, b}. */
    private static int[] centre(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, TerrainCamera view)
            throws Exception {
        long revision = ++cameraRevision;
        owner.submit(new WorldStreamControl.Camera(NONCE, revision, 1, 256, 256, view));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            owner.advance();
            var frame =
                    reader.readLatest(
                            0, new WorldFrameFileV2.Barrier(1, 256, 256, 1, Long.MAX_VALUE, 3, 3));
            if (frame != null && frame.metadata().camera() == revision) {
                int pixel = frame.pixels().get(128 * 256 + 128);
                return new int[] {pixel >>> 16 & 255, pixel >>> 8 & 255, pixel & 255};
            }
        }
        throw new AssertionError("No frame for camera " + view);
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
