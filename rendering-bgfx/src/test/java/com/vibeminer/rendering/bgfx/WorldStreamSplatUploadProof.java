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
            WorldStreamNativeProof.mccvForNextPrepare = MCCV;
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
            var camera =
                    new WorldStreamControl.Camera(
                            NONCE, revision, 1, 256, 256, new TerrainCamera(-100, -10, 50, 0, -.6));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            // The owner refuses a draw while no readback slot is free (the upload's last frames
            // may still be in flight): resubmit the same camera until it is drawn.
            while (!owner.submit(camera)) {
                owner.advance();
                require(System.nanoTime() < deadline, "The final camera was never drawn");
            }
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
            // The near camera still sees one layered chunk's blend at its centre.
            int[] near = centre(owner, reader, new TerrainCamera(-100, -10, 14, 0, -1.2));
            require(
                    close(near, blended(maskOf(2, 0))),
                    "Near splat pixel "
                            + java.util.Arrays.toString(near)
                            + ", chunk 2,0 blend "
                            + java.util.Arrays.toString(blended(maskOf(2, 0))));
            String placement = placement(frame(owner, reader, PLACEMENT));
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
                            + " "
                            + placement
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

    /**
     * Every chunk's mask texel encodes its own place: layer 1 weighs 8 x row, layer 2 8 x column,
     * layer 3 nothing. A pixel's blend then names the chunk whose mask it read, unambiguously.
     */
    static int[] maskOf(int row, int column) {
        return new int[] {8 * row, 8 * column, 0};
    }

    private static final int MASK_A = 192;

    /**
     * Chunks with layers: even columns, except anchor rows and columns around the placement view
     * (rows 4 and 5, columns 3 to 5). The view's layered chunks are then rows 4 and 5 of column 4,
     * ringed by chunks without layers: a mask map read one row off shows row 3 or 6, one column off
     * an odd column, and two columns off column 2 or 6, all of which have no layers.
     */
    static final int[] ROW_ANCHORS = {3, 6}, COLUMN_ANCHORS = {2, 6};

    static boolean layered(int row, int column) {
        return column % 2 == 0
                && java.util.Arrays.stream(ROW_ANCHORS).noneMatch(anchor -> anchor == row)
                && java.util.Arrays.stream(COLUMN_ANCHORS).noneMatch(anchor -> anchor == column);
    }

    /**
     * One chunk's side in yards; chunk (row, column) spans x and y of -(index + 1) to -index sides.
     */
    private static final double CHUNK = 533.3333 / 16;

    /**
     * Looking almost straight down from 30 yards at the corner of rows 4 and 5 and columns 3 and 4:
     * the atlas stays magnified at least two times, so no pixel is in the fade band.
     */
    private static final TerrainCamera PLACEMENT =
            new TerrainCamera(-5 * CHUNK, -4 * CHUNK, 30, 0, -1.5);

    /**
     * Every vertex's MCCV (#373): the splat takes the vertex colour, 2 x colour / 255 per channel,
     * where the atlas keeps its baked tint.
     */
    private static final int[] MCCV = {192, 96, 160};

    /**
     * A trailer whose {@link #layered} chunks draw array layers 0 to 3, and whose other chunks have
     * no layers (255, as the parent writes for a missing layer). Every chunk's mask block is {@link
     * #maskOf} its own place; {@code -Dvimi.proof.splatShift=rows,columns} writes each block from
     * another chunk instead, to show the proof fails when the mask map is misplaced.
     */
    static WorldStreamTileFile.Splat blendSplat(int maskSide) {
        byte[] table = new byte[WorldStreamTileFile.SPLAT_TABLE_BYTES];
        for (int chunk = 0; chunk < 256; chunk++) {
            boolean layers = layered(chunk / 16, chunk % 16);
            for (int layer = 0; layer < 4; layer++) {
                table[chunk * 4 + layer] = (byte) (layers ? layer : WorldStreamTileFile.SPLAT_NONE);
            }
        }
        byte[] alpha =
                new byte[com.vibeminer.rendering.world.TerrainTileSplat.alphaBytes(maskSide)];
        int per = maskSide / 16;
        String[] shift = System.getProperty("vimi.proof.splatShift", "0,0").split(",");
        int shiftRows = Integer.parseInt(shift[0].strip()),
                shiftColumns = Integer.parseInt(shift[1].strip());
        for (int y = 0; y < maskSide; y++) {
            for (int x = 0; x < maskSide; x++) {
                int row = Math.floorMod(y / per + shiftRows, 16),
                        column = Math.floorMod(x / per + shiftColumns, 16);
                int[] mask = maskOf(row, column);
                int at = (y * maskSide + x) * 4;
                alpha[at] = (byte) mask[0];
                alpha[at + 1] = (byte) mask[1];
                alpha[at + 2] = (byte) mask[2];
                alpha[at + 3] = (byte) MASK_A;
            }
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
    static int[] blended(int[] mask) {
        double a1 = mask[0] / 255.0, a2 = mask[1] / 255.0, a3 = mask[2] / 255.0;
        double sum = a1 + a2 + a3, w0 = Math.max(0, 1 - sum), total = Math.max(1, w0 + sum);
        double[] weights = {w0, a1, a2, a3};
        int[] result = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            double value = 0;
            for (int layer = 0; layer < 4; layer++) {
                value += weights[layer] * expand(LAYER_565[layer], channel);
            }
            result[channel] = (int) Math.min(255, value / total * 2 * MCCV[channel] / 255.0);
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
        return rgbOf(frame(owner, reader, view)[128 * 256 + 128]);
    }

    private static int[] rgbOf(int pixel) {
        return new int[] {pixel >>> 16 & 255, pixel >>> 8 & 255, pixel & 255};
    }

    /** Every pixel of a 256 x 256 frame drawn from {@code view}, row by row. */
    private static int[] frame(
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
                int[] pixels = new int[256 * 256];
                frame.pixels().get(0, pixels);
                return pixels;
            }
        }
        throw new AssertionError("No frame for camera " + view);
    }

    private static final int ATLAS_PIXEL = -1, OTHER_PIXEL = -2;

    /**
     * The chunk (row x 16 + column) whose mask a pixel's splat blend shows, {@link #ATLAS_PIXEL}
     * for the atlas colour, or {@link #OTHER_PIXEL} for anything else (the fade band, fog).
     */
    private static int classify(int pixel) {
        int[] value = rgbOf(pixel);
        if (close(value, ATLAS)) {
            return ATLAS_PIXEL;
        }
        int found = OTHER_PIXEL;
        for (int chunk = 0; chunk < 256; chunk++) {
            if (close(value, blended(maskOf(chunk / 16, chunk % 16)))) {
                if (found != OTHER_PIXEL) {
                    throw new AssertionError("Two chunks' blends match " + rgb(value));
                }
                found = chunk;
            }
        }
        return found;
    }

    /**
     * Checks every pixel of the near frame against the table: each splat pixel's blend names the
     * chunk whose mask it read, which must be a chunk with layers, and two neighbouring pixels on
     * each side of a chunk border must name neighbouring chunks. Both kinds of border (splat to
     * splat across rows, splat to atlas across columns) and both anchors must be in view.
     */
    private static String placement(int[] pixels) {
        int[] chunks = new int[pixels.length];
        int decoded = 0, minRow = 16, maxRow = -1, minColumn = 16, maxColumn = -1;
        for (int i = 0; i < pixels.length; i++) {
            chunks[i] = classify(pixels[i]);
            if (chunks[i] >= 0) {
                decoded++;
                int row = chunks[i] / 16, column = chunks[i] % 16;
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
                minColumn = Math.min(minColumn, column);
                maxColumn = Math.max(maxColumn, column);
                require(
                        layered(row, column),
                        "Pixel "
                                + (i % 256)
                                + ","
                                + (i / 256)
                                + " shows the mask of chunk "
                                + row
                                + ","
                                + column
                                + ", which has no layers: the mask map is misplaced");
            }
        }
        String seen = "rows " + minRow + "-" + maxRow + " columns " + minColumn + "-" + maxColumn;
        require(
                decoded >= 4096,
                "Too few splat pixels to check placement: " + decoded + ", " + seen);
        int splatBorders = 0, atlasBorders = 0;
        boolean[] rowAnchor = new boolean[ROW_ANCHORS.length],
                columnAnchor = new boolean[COLUMN_ANCHORS.length];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int here = chunks[y * 256 + x];
                if (here >= 0) {
                    int row = here / 16, column = here % 16;
                    for (int a = 0; a < ROW_ANCHORS.length; a++) {
                        rowAnchor[a] |= Math.abs(row - ROW_ANCHORS[a]) == 1;
                    }
                    for (int a = 0; a < COLUMN_ANCHORS.length; a++) {
                        columnAnchor[a] |= Math.abs(column - COLUMN_ANCHORS[a]) == 2;
                    }
                }
                for (int[] step : new int[][] {{1, 0}, {0, 1}}) {
                    int nx = x + step[0], ny = y + step[1];
                    if (nx > 255 || ny > 255) {
                        continue;
                    }
                    int there = chunks[ny * 256 + nx];
                    if (here >= 0 && there >= 0 && here != there) {
                        int rows = Math.abs(here / 16 - there / 16),
                                columns = Math.abs(here % 16 - there % 16);
                        require(
                                rows + columns == 1,
                                "Pixels "
                                        + x
                                        + ","
                                        + y
                                        + " and "
                                        + nx
                                        + ","
                                        + ny
                                        + " show chunks "
                                        + here
                                        + " and "
                                        + there
                                        + ", which are not neighbours");
                        splatBorders++;
                    } else if (here >= 0 && there == ATLAS_PIXEL
                            || here == ATLAS_PIXEL && there >= 0) {
                        atlasBorders++;
                    }
                }
            }
        }
        require(splatBorders > 0, "No splat-to-splat chunk border in view, " + seen);
        require(atlasBorders > 0, "No splat-to-atlas chunk border in view, " + seen);
        for (boolean inView : rowAnchor) {
            require(inView, "A row anchor's neighbour is not in view, " + seen);
        }
        for (boolean inView : columnAnchor) {
            require(inView, "A column anchor's neighbour is not in view, " + seen);
        }
        return "decoded="
                + decoded
                + " splatBorders="
                + splatBorders
                + " atlasBorders="
                + atlasBorders
                + " "
                + seen.replace(' ', '_');
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
