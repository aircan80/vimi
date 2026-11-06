package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Authored codec, tile-header and child-decode tests for the per-tile objects file. */
final class WorldStreamObjectsFileTest {
    @TempDir Path directory;
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(12, -34);
    private static final WorldStreamState.Lease LEASE = new WorldStreamState.Lease(9, 8, 7);
    private static final String SCOPE = "ab".repeat(32);
    private static final WorldStreamObjectsFile.Identity IDENTITY =
            new WorldStreamObjectsFile.Identity(NONCE, SCOPE, 42, LEASE, 7, 19);

    /** One model with a single quad mesh drawn by two ranges, two images and two placements. */
    static WorldStreamTileWriter.Exterior small() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.image(4, 1, chain(4, 1, rgba(4, 20)));
        builder.model();
        float[] vertices = new float[4 * 8];
        for (int v = 0; v < 4; v++) {
            vertices[v * 8] = v % 2 * 10;
            vertices[v * 8 + 1] = v / 2 * 10;
            vertices[v * 8 + 2] = 3;
            vertices[v * 8 + 5] = 1;
            vertices[v * 8 + 6] = v % 2;
            vertices[v * 8 + 7] = v / 2;
        }
        builder.mesh(
                FloatBuffer.wrap(vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 3, 0, 3, 3, 1});
        builder.placement(0, -100.25, -200.5, 12, 1, 1, 0);
        builder.placement(0, -110.25, -205.5, 13, 1.5, 0, 1);
        return builder.build();
    }

    /** The packed mip chain of a top level, generated as the World producers do. */
    static ByteBuffer chain(int width, int height, ByteBuffer top) {
        byte[] bytes = new byte[top.remaining()];
        top.duplicate().get(bytes);
        return ByteBuffer.wrap(
                ObjectTextureMips.build(
                                width,
                                height,
                                bytes,
                                ObjectTextureMips.BLENDED,
                                (below, w, h) -> null,
                                () -> false)
                        .rgba());
    }

    private static ByteBuffer rgba(int pixels, int seed) {
        byte[] bytes = new byte[pixels * 4];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return ByteBuffer.wrap(bytes);
    }

    private static WorldStreamObjectsFile.Data data(WorldStreamTileWriter.Exterior exterior)
            throws Exception {
        var field = WorldStreamTileWriter.Exterior.class.getDeclaredField("data");
        field.setAccessible(true);
        return (WorldStreamObjectsFile.Data) field.get(exterior);
    }

    private Path objectsPath() {
        return directory.resolve("tile-42.wso");
    }

    private WorldStreamObjectsFile.Reference write(WorldStreamTileWriter.Exterior exterior)
            throws Exception {
        return WorldStreamObjectsFile.write(directory, IDENTITY, data(exterior), () -> false);
    }

    @Test
    void roundTripPreservesEveryTableAndBindsToItsTile() throws Exception {
        var exterior = small();
        var reference = write(exterior);
        assertEquals(exterior.bytes(), reference.length());
        assertEquals(reference.length(), Files.size(objectsPath()));
        assertEquals(4, exterior.draws());
        var written = data(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertArrayEquals(written.models, read.models);
        assertArrayEquals(written.meshes, read.meshes);
        assertArrayEquals(written.ranges, read.ranges);
        assertArrayEquals(written.placementModels, read.placementModels);
        assertArrayEquals(written.placements, read.placements);
        assertArrayEquals(written.images, read.images);
        assertArrayEquals(written.vertices, read.vertices);
        assertArrayEquals(written.indices, read.indices);
        assertArrayEquals(written.pixels, read.pixels);
        assertEquals(written.draws, read.draws);
        // A different tile identity (here the next lease generation) never reads this file.
        var foreign =
                new WorldStreamObjectsFile.Identity(
                        NONCE, SCOPE, 42, new WorldStreamState.Lease(9, 9, 7), 7, 19);
        assertThrows(
                IOException.class,
                () -> WorldStreamObjectsFile.read(objectsPath(), foreign, reference, () -> false));
        // Publication never replaces an existing file.
        assertThrows(IOException.class, () -> write(exterior));
    }

    @Test
    void aHighTierSetAtExactlyTheObjectPixelCapWritesAndReads() throws Exception {
        // 56 chains of 256^2 (87,381 texels each) leave 19 of MAX_PIXELS, the HIGH tier's chain
        // total; three 2x2 chains (5), a 2x1 (3) and a 1x1 (1) fill it exactly.
        var builder = WorldStreamTileWriter.Exterior.builder();
        int chain = ObjectTextureMips.chainPixels(256, 256);
        for (int i = 0; i < 56; i++) {
            builder.image(256, 256, ByteBuffer.allocate(chain * 4));
        }
        assertEquals(19, WorldStreamObjectsFile.MAX_PIXELS - 56 * chain);
        for (int i = 0; i < 3; i++) {
            builder.image(2, 2, ByteBuffer.allocate(5 * 4));
        }
        builder.image(2, 1, ByteBuffer.allocate(3 * 4));
        builder.image(1, 1, ByteBuffer.allocate(4));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.image(1, 1, ByteBuffer.allocate(4)),
                "one pixel more is refused");
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        var maximum = builder.build();
        assertTrue(maximum.bytes() <= WorldStreamObjectsFile.MAX_BYTES);
        var reference = write(maximum);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(WorldStreamObjectsFile.MAX_IMAGE_BYTES, read.imageBytes());
    }

    @Test
    void compressedChainsRoundTripByteForByteWithTheirFormat() throws Exception {
        // Distinct bytes per image, so any reordering or re-encoding would show.
        int bc1 = ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC1, 256, 64);
        int bc3 = ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC3, 16, 16);
        var blocks1 = new byte[bc1];
        var blocks3 = new byte[bc3];
        for (int i = 0; i < bc1; i++) {
            blocks1[i] = (byte) (i * 7 + 1);
        }
        for (int i = 0; i < bc3; i++) {
            blocks3[i] = (byte) (i * 13 + 5);
        }
        var builder = WorldStreamTileWriter.Exterior.builder();
        assertEquals(0, builder.image(256, 64, ObjectTextureFormats.BC1, ByteBuffer.wrap(blocks1)));
        assertEquals(1, builder.image(2, 2, chain(2, 2, rgba(4, 1))));
        assertEquals(2, builder.image(16, 16, ObjectTextureFormats.BC3, ByteBuffer.wrap(blocks3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.image(16, 16, ObjectTextureFormats.BC1, ByteBuffer.wrap(blocks3)),
                "a BC1 chain is half the BC3 size");
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.image(16, 16, 4, ByteBuffer.wrap(blocks3)),
                "unknown format");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.image(
                                2,
                                2,
                                ObjectTextureFormats.BC3,
                                ByteBuffer.allocate(
                                        ObjectTextureFormats.chainBytes(
                                                ObjectTextureFormats.BC3, 2, 2))),
                "a compressed top level is at least one block");
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 2});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        var exterior = builder.build();
        var reference = write(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertArrayEquals(
                new int[] {
                    256, 64, ObjectTextureFormats.BC1,
                    2, 2, ObjectTextureFormats.RGBA8,
                    16, 16, ObjectTextureFormats.BC3
                },
                read.images);
        int rgba = ObjectTextureMips.chainPixels(2, 2) * 4;
        assertEquals(bc1 + rgba + bc3, read.imageBytes());
        assertArrayEquals(blocks1, java.util.Arrays.copyOfRange(read.pixels, 0, bc1));
        assertArrayEquals(
                blocks3, java.util.Arrays.copyOfRange(read.pixels, bc1 + rgba, bc1 + rgba + bc3));
        try (var packed = WorldStreamPackedObjects.pack(read, () -> false)) {
            assertEquals(3, packed.imageCount());
            assertEquals(ObjectTextureFormats.BC3, packed.format(2));
            assertEquals(bc1 + rgba, packed.imageOffset(2));
            assertEquals(blocks3[0], packed.pixels.get(packed.imageOffset(2)));
        }
        // A file whose image table names an unknown format never validates.
        var table = read.images.clone();
        table[2] = 7;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamObjectsFile.Data(
                                read.models,
                                read.meshes,
                                read.ranges,
                                read.placementModels,
                                read.placements,
                                table,
                                read.vertices,
                                read.indices,
                                read.pixels));
        // A BC1 image drawn by a cutout or blended range is refused: its alpha is not sampled
        // on every backend.
        for (int kind : new int[] {WorldStreamObjectsFile.CUTOUT, WorldStreamObjectsFile.BLENDED}) {
            var alpha = WorldStreamTileWriter.Exterior.builder();
            alpha.image(256, 64, ObjectTextureFormats.BC1, ByteBuffer.wrap(blocks1));
            alpha.model();
            alpha.mesh(
                    FloatBuffer.wrap(new float[24]),
                    IntBuffer.wrap(new int[] {0, 1, 2}),
                    new int[] {0, 3, 0},
                    new int[] {kind});
            alpha.placement(0, 0, 0, 0, 1, 1, 0);
            assertEquals(
                    "BC1 image sampled with alpha",
                    assertThrows(IllegalArgumentException.class, alpha::build).getMessage());
        }
        // The file carries version 4: the reader accepts no other, so a version 3 file (two-int
        // RGBA8-only image table) fails its header check instead of being misread.
        var bytes = Files.readAllBytes(objectsPath());
        assertEquals(WorldStreamObjectsFile.VERSION, bytes[8]);
        assertEquals(4, WorldStreamObjectsFile.VERSION);
        bytes[8] = 3;
        Files.write(objectsPath(), bytes);
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        objectsPath(), IDENTITY, reference, () -> false));
        assertEquals("Objects header identity", refused.getCause().getMessage());
    }

    /** One BC1 256^2 image drawn by an opaque range; the rest of a minimal tile. */
    private static WorldStreamTileWriter.Exterior.Builder bc1Tile(
            WorldStreamTileWriter.Exterior.Builder builder, int images) {
        int stored = ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC1, 256, 256);
        for (int i = 0; i < images; i++) {
            builder.image(256, 256, ObjectTextureFormats.BC1, ByteBuffer.allocate(stored));
        }
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        return builder;
    }

    @Test
    void aSessionSamplingBc1ChargesBc1ChainsTheirStoredSize() throws Exception {
        int bc1 = 1 << ObjectTextureFormats.BC1;
        // 56 BC1 256^2 chains fill the decoded cap; a BC1-sampling session fits many more.
        var sampling = bc1Tile(WorldStreamTileWriter.Exterior.builder(bc1), 100);
        var exterior = sampling.build();
        var reference = write(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(bc1, read.compressedFormats);
        assertEquals(100, read.imageCount());
        // The same chains for a session that decodes BC1 stop at 56.
        assertThrows(
                IllegalArgumentException.class,
                () -> bc1Tile(WorldStreamTileWriter.Exterior.builder(), 57));
        // Only BC bits are a mask.
        assertThrows(
                IllegalArgumentException.class, () -> WorldStreamTileWriter.Exterior.builder(1));
        // A child that does not sample BC1 refuses the file rather than decode it past the cap.
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamPackedObjects.pack(read, () -> false, 0));
        try (var packed = WorldStreamPackedObjects.pack(read, () -> false, bc1)) {
            assertEquals(100, packed.compressedImages);
            assertEquals(0, packed.decodedImages);
        }
    }

    /**
     * One opaque triangle drawing one image of {@code format} and side, charged by {@code mask}.
     */
    private static WorldStreamObjectsFile.Data oneImage(int mask, int format, int side) {
        var builder = WorldStreamTileWriter.Exterior.builder(mask);
        builder.image(
                side,
                side,
                format,
                ByteBuffer.allocate(ObjectTextureFormats.chainBytes(format, side, side)));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        return builder.build().data();
    }

    @Test
    void only512ChainsChargedAsBlocksMayExceedTheRgba8Side() {
        int bc1 = ObjectTextureFormats.BC1;
        assertEquals(512, oneImage(1 << bc1, bc1, 512).images[0]);
        assertEquals(256, oneImage(0, ObjectTextureFormats.RGBA8, 256).images[0]);
        // An RGBA8 512 chain, or blocks the session decodes, would upload past the RGBA8 cap.
        assertThrows(
                IllegalArgumentException.class,
                () -> oneImage(1 << bc1, ObjectTextureFormats.RGBA8, 512));
        assertThrows(IllegalArgumentException.class, () -> oneImage(0, bc1, 512));
    }

    @Test
    void packingDecodesFormatsTheRendererDoesNotSample() throws Exception {
        // A BC3 image in a session that charged it decoded (mask 0): kept as blocks for a BC3
        // renderer, decoded to RGBA8 on the decode worker for one without BC3.
        var builder = WorldStreamTileWriter.Exterior.builder();
        var blocks =
                ByteBuffer.allocate(ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC3, 8, 8))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int at = 0; at < blocks.capacity(); at += 16) {
            // Alpha endpoints 200 (selectors 0), colour c0 red, c1 black (selectors 0).
            blocks.put(at, (byte) 200).put(at + 1, (byte) 200);
            blocks.putShort(at + 8, (short) 0xF800).putShort(at + 10, (short) 0);
        }
        builder.image(8, 8, ObjectTextureFormats.BC3, blocks.duplicate().clear());
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        var data = builder.build();
        var reference = write(data);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        try (var kept =
                WorldStreamPackedObjects.pack(read, () -> false, 1 << ObjectTextureFormats.BC3)) {
            assertEquals(ObjectTextureFormats.BC3, kept.format(0));
            assertEquals(1, kept.compressedImages);
        }
        try (var decoded = WorldStreamPackedObjects.pack(read, () -> false, 0)) {
            assertEquals(ObjectTextureFormats.RGBA8, decoded.format(0));
            assertEquals(1, decoded.decodedImages);
            assertEquals(ObjectTextureMips.chainPixels(8, 8) * 4, decoded.pixels.capacity());
            assertEquals(255, decoded.pixels.get(0) & 255, "red");
            assertEquals(200, decoded.pixels.get(3) & 255, "BC3 alpha");
        }
    }

    @Test
    void compressedImagesAreChargedTheirDecodedSizeToo() {
        // 56 BC1 256^2 chains take 2.4 MB stored but 19.6 MB decoded, the RGBA8 profile a
        // backend without BC sampling must stay within; a 57th is refused although its 43,704
        // stored bytes would fit.
        var builder = WorldStreamTileWriter.Exterior.builder();
        int stored = ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC1, 256, 256);
        long decoded = 4L * ObjectTextureMips.chainPixels(256, 256);
        assertEquals(56, WorldStreamObjectsFile.MAX_DECODED_BYTES / decoded);
        for (int i = 0; i < 56; i++) {
            builder.image(256, 256, ObjectTextureFormats.BC1, ByteBuffer.allocate(stored));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.image(
                                256, 256, ObjectTextureFormats.BC1, ByteBuffer.allocate(stored)));
        assertFalse(builder.fits(1, ObjectTextureMips.chainPixels(256, 256), 1, 3, 3, 1));
        assertTrue(56L * stored < WorldStreamObjectsFile.MAX_IMAGE_BYTES);
    }

    @Test
    void maximumProfileFitsUnderTheObjectsFileCeiling() throws Exception {
        var builder = WorldStreamTileWriter.Exterior.builder();
        int chain = ObjectTextureMips.chainPixels(256, 256);
        assertEquals(87_381, chain);
        for (int i = 0; i < 24; i++) {
            builder.image(256, 256, ByteBuffer.allocate(chain * 4));
        }
        builder.model();
        // Ten meshes of 16,384 vertices and 49,152 indices reach the vertex and index caps.
        for (int mesh = 0; mesh < 10; mesh++) {
            float[] vertices = new float[16384 * 8];
            int[] indices = new int[49152];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i % 16384;
            }
            builder.mesh(
                    FloatBuffer.wrap(vertices), IntBuffer.wrap(indices), new int[] {0, 49152, 0});
        }
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        var maximum = builder.build();
        assertTrue(maximum.bytes() <= WorldStreamObjectsFile.MAX_BYTES);
        assertEquals(
                WorldStreamObjectsFile.length(1, 10, 10, 1, 24, 163_840, 491_520, 24 * chain * 4),
                maximum.bytes());
        // Every table at its cap still fits the ceiling.
        assertTrue(
                WorldStreamObjectsFile.length(
                                WorldStreamObjectsFile.MAX_MODELS,
                                WorldStreamObjectsFile.MAX_MESHES,
                                WorldStreamObjectsFile.MAX_RANGES,
                                WorldStreamObjectsFile.MAX_PLACEMENTS,
                                WorldStreamObjectsFile.MAX_IMAGES,
                                WorldStreamObjectsFile.MAX_VERTICES,
                                WorldStreamObjectsFile.MAX_INDICES,
                                WorldStreamObjectsFile.MAX_IMAGE_BYTES)
                        <= WorldStreamObjectsFile.MAX_BYTES);
        var reference = write(maximum);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(163_840, read.vertexCount());
        assertEquals(491_520, read.indexCount());
        assertEquals(24 * chain * 4, read.imageBytes());
        // The pixel cap is the HIGH tier's chain budgets: 2,796,203 + 699,051 + 1,398,101.
        assertEquals(4_893_355, WorldStreamObjectsFile.MAX_PIXELS);
        // Every table at its cap needs about 27 MB, under the 28 MiB ceiling with headroom.
        long caps =
                WorldStreamObjectsFile.length(
                        WorldStreamObjectsFile.MAX_MODELS,
                        WorldStreamObjectsFile.MAX_MESHES,
                        WorldStreamObjectsFile.MAX_RANGES,
                        WorldStreamObjectsFile.MAX_PLACEMENTS,
                        WorldStreamObjectsFile.MAX_IMAGES,
                        WorldStreamObjectsFile.MAX_VERTICES,
                        WorldStreamObjectsFile.MAX_INDICES,
                        WorldStreamObjectsFile.MAX_IMAGE_BYTES);
        assertTrue(caps > (25 << 20) && caps <= WorldStreamObjectsFile.MAX_BYTES, "caps " + caps);
        // The ceiling itself is enforced by the reference and by the tile header.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamObjectsFile.Reference(
                                WorldStreamObjectsFile.MAX_BYTES + 1, "00".repeat(32)));
        // Caps refuse one more vertex, pixel or draw before anything is buffered.
        var over = WorldStreamTileWriter.Exterior.builder();
        over.model();
        assertThrows(
                IllegalArgumentException.class,
                () -> over.image(2000, 2000, ByteBuffer.allocate(2000 * 2000 * 4)));
        var draws = WorldStreamTileWriter.Exterior.builder();
        draws.image(1, 1, ByteBuffer.allocate(4));
        draws.model();
        int[] ranges = new int[1024 * 3];
        int[] indices = new int[1024 * 3];
        for (int r = 0; r < 1024; r++) {
            ranges[r * 3] = r * 3;
            ranges[r * 3 + 1] = 3;
            indices[r * 3 + 1] = 1;
            indices[r * 3 + 2] = 2;
        }
        draws.mesh(FloatBuffer.wrap(new float[24]), IntBuffer.wrap(indices), ranges);
        for (int p = 0; p < 5; p++) {
            draws.placement(0, 0, 0, 0, 1, 1, 0);
        }
        var budget = assertThrows(IllegalArgumentException.class, draws::build);
        assertEquals("Object draw budget", budget.getMessage());
    }

    @Test
    void builderReportsRemainingRoomBeforeAWholeModelIsAdded() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        // Version 2 shares one profile among buildings, their cutout and blended surfaces,
        // doodads and creatures.
        assertTrue(
                builder.fits(
                        WorldStreamObjectsFile.MAX_IMAGES,
                        WorldStreamObjectsFile.MAX_PIXELS,
                        WorldStreamObjectsFile.MAX_MESHES,
                        WorldStreamObjectsFile.MAX_VERTICES,
                        WorldStreamObjectsFile.MAX_INDICES,
                        WorldStreamObjectsFile.MAX_RANGES));
        assertFalse(
                builder.fits(WorldStreamObjectsFile.MAX_IMAGES + 1, 0, 1, 3, 3, 1),
                "Image count cap");
        assertFalse(
                builder.fits(0, WorldStreamObjectsFile.MAX_PIXELS + 1L, 1, 3, 3, 1), "Pixel cap");
        assertFalse(
                builder.fits(0, 0, 1, WorldStreamObjectsFile.MAX_VERTICES + 1, 3, 1), "Vertex cap");
        assertFalse(builder.fits(0, 0, 2, 3, 3, 1), "Every mesh needs a range");
        assertEquals(WorldStreamObjectsFile.MAX_PLACEMENTS, builder.placementRoom());
        assertEquals(WorldStreamTileWriter.Exterior.MAX_DRAWS, builder.drawRoom());
        builder.image(2, 2, chain(2, 2, rgba(4, 1)));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 1}),
                new int[] {0, 3, 0, 3, 3, 0});
        assertEquals(2, builder.drawCost(0));
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        assertEquals(WorldStreamObjectsFile.MAX_PLACEMENTS - 2, builder.placementRoom());
        assertEquals(2, builder.placementCount());
        assertEquals(WorldStreamTileWriter.Exterior.MAX_DRAWS - 4, builder.drawRoom());
        assertFalse(
                builder.fits(WorldStreamObjectsFile.MAX_IMAGES, 0, 1, 3, 3, 1),
                "Images already used count");
        assertThrows(IllegalArgumentException.class, () -> builder.drawCost(1));
        assertEquals(4, builder.build().draws());
    }

    @Test
    void anyChangedByteOrDigestIsRefused() throws Exception {
        var reference = write(small());
        try (var channel = FileChannel.open(objectsPath(), StandardOpenOption.WRITE)) {
            // A pixel byte: structurally valid, so only the whole-file digest catches it.
            channel.write(ByteBuffer.wrap(new byte[] {99}), reference.length() - 1);
        }
        var changed =
                assertThrows(
                        IOException.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        objectsPath(), IDENTITY, reference, () -> false));
        assertEquals("Objects bytes or digest differ", changed.getMessage());
        var wrongDigest = new WorldStreamObjectsFile.Reference(reference.length(), "00".repeat(32));
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamObjectsFile.read(
                                objectsPath(), IDENTITY, wrongDigest, () -> false));
    }

    @Test
    void truncationAndLengthDisagreementsAreRefused() throws Exception {
        var reference = write(small());
        try (var channel = FileChannel.open(objectsPath(), StandardOpenOption.WRITE)) {
            channel.truncate(reference.length() - 7);
        }
        var size =
                assertThrows(
                        IOException.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        objectsPath(), IDENTITY, reference, () -> false));
        assertEquals("Objects file size", size.getMessage());
        // Even when the expectation follows the truncated length, the counts no longer fit.
        var shorter = new WorldStreamObjectsFile.Reference(reference.length() - 7, "00".repeat(32));
        assertThrows(
                IOException.class,
                () -> WorldStreamObjectsFile.read(objectsPath(), IDENTITY, shorter, () -> false));
        var header = new WorldStreamObjectsFile.Reference(256, "00".repeat(32));
        try (var channel = FileChannel.open(objectsPath(), StandardOpenOption.WRITE)) {
            channel.truncate(256);
        }
        assertThrows(
                IOException.class,
                () -> WorldStreamObjectsFile.read(objectsPath(), IDENTITY, header, () -> false));
    }

    @Test
    void builderRefusesInvalidIndicesRangesAndTransforms() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, ByteBuffer.allocate(20));
        builder.model();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        builder.mesh(
                                FloatBuffer.wrap(new float[24]),
                                IntBuffer.wrap(new int[] {0, 1, 3}),
                                new int[] {0, 3, 0}));
        var image = WorldStreamTileWriter.Exterior.builder();
        image.image(3, 2, ByteBuffer.allocate(28));
        image.model();
        image.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        image.placement(0, 0, 0, 0, 1, 1, 0);
        assertEquals(
                "Image size",
                assertThrows(IllegalArgumentException.class, image::build).getMessage());
        var transform = WorldStreamTileWriter.Exterior.builder();
        transform.image(2, 2, ByteBuffer.allocate(20));
        transform.model();
        transform.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        transform.placement(0, Double.NaN, 0, 0, 1, 1, 0);
        assertEquals(
                "Placement transform",
                assertThrows(IllegalArgumentException.class, transform::build).getMessage());
    }

    @Test
    void canceledWriteLeavesNoFile() throws Exception {
        var checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamObjectsFile.write(
                                directory,
                                IDENTITY,
                                data(small()),
                                () -> checks.incrementAndGet() > 2));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    // ---- Tile header reference and child-side failure isolation ----

    private static WorldStreamTileFile.Header named(
            WorldStreamTileFile.Header h, WorldStreamObjectsFile.Reference objects) {
        return new WorldStreamTileFile.Header(
                h.nonce(),
                h.scope(),
                h.transaction(),
                h.lease(),
                h.x(),
                h.y(),
                h.chunks(),
                h.vertices(),
                h.indices(),
                h.geometrySha256(),
                h.materialDigest(),
                h.outcome(),
                h.reason(),
                h.coverage(),
                h.maxX(),
                h.maxY(),
                h.uvError(),
                h.wdt(),
                h.root(),
                h.tex0(),
                h.diffuse(),
                objects);
    }

    private WorldStreamControl.Offer packet(WorldStreamObjectsFile.Reference objects)
            throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = named(WorldStreamTileFileTest.header(tile, false), objects);
        var expected = WorldStreamTileFile.write(directory, header, tile, null, () -> false);
        return new WorldStreamControl.Offer(NONCE, 42, LEASE, expected.length(), expected.sha256());
    }

    private static WorldStreamEnvelope.Session session(boolean exteriors) {
        return new WorldStreamEnvelope.Session(NONCE, SCOPE, 1, exteriors);
    }

    @Test
    void tileHeaderNamesObjectsInItsReservedRange() throws Exception {
        var reference = write(small());
        var offer = packet(reference);
        var expected =
                new WorldStreamTileFile.Expected(
                        NONCE, SCOPE, 42, LEASE, offer.length(), offer.sha256());
        try (var payload =
                WorldStreamTileFile.read(directory.resolve("tile-42.wst"), expected, () -> false)) {
            assertEquals(reference, payload.header().objects());
        }
        byte[] bytes =
                WorldStreamTileFile.encodeHeader(
                        named(
                                WorldStreamTileFileTest.header(
                                        WorldStreamTileFileTest.full(false), false),
                                null));
        for (int i = 656; i < 1024; i++) {
            assertEquals(0, bytes[i]);
        }
    }

    @Test
    void validObjectsDecodeBesideTerrain() throws Exception {
        var offer = packet(write(small()));
        try (var tile =
                WorldStreamSession.decodeTile(session(true), directory, offer, () -> false)) {
            assertEquals(1, tile.objectsState());
            try (var source = tile.take()) {
                assertNotNull(source.objects);
                assertEquals(2, source.objects.placementCount());
                assertEquals(4 * 32, source.objects.vertices.capacity());
                assertEquals(6 * 2, source.objects.indices.capacity());
                assertEquals(1, source.triangles);
            }
        }
    }

    @Test
    void objectFailuresKeepTheTerrainTile() throws Exception {
        var reference = write(small());
        var offer = packet(reference);
        // Changed objects bytes: the tile still decodes, with objects reported failed.
        try (var channel = FileChannel.open(objectsPath(), StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {1}), 300);
        }
        assertFailedObjectsKeepTerrain(offer);
        // Truncated objects file.
        try (var channel = FileChannel.open(objectsPath(), StandardOpenOption.WRITE)) {
            channel.truncate(200);
        }
        assertFailedObjectsKeepTerrain(offer);
        // Missing objects file.
        Files.delete(objectsPath());
        assertFailedObjectsKeepTerrain(offer);
    }

    private void assertFailedObjectsKeepTerrain(WorldStreamControl.Offer offer) throws Exception {
        try (var tile =
                WorldStreamSession.decodeTile(session(true), directory, offer, () -> false)) {
            assertEquals(2, tile.objectsState());
            try (var source = tile.take()) {
                assertNull(source.objects);
                assertEquals(1, source.triangles);
                assertEquals(TerrainScene.MAX_VERTICES_PER_TILE, source.header.vertices());
            }
        }
    }

    @Test
    void sessionProfileGovernsObjectsAndStagedStatus() throws Exception {
        var named = packet(write(small()));
        // A terrain-only session never accepts a header that names objects.
        assertThrows(
                IOException.class,
                () -> WorldStreamSession.decodeTile(session(false), directory, named, () -> false));
        Files.delete(directory.resolve("tile-42.wst"));
        var plain = packet(null);
        try (var tile =
                WorldStreamSession.decodeTile(session(true), directory, plain, () -> false)) {
            assertEquals(0, tile.objectsState());
        }
        try (var tile =
                WorldStreamSession.decodeTile(session(false), directory, plain, () -> false)) {
            assertEquals(-1, tile.objectsState());
        }
        // Cancellation inside the objects read is never reported as an objects failure.
        Files.delete(directory.resolve("tile-42.wst"));
        Files.delete(objectsPath());
        var again = packet(write(small()));
        var expected =
                new WorldStreamTileFile.Expected(
                        NONCE, SCOPE, 42, LEASE, again.length(), again.sha256());
        var terrainChecks = new AtomicInteger();
        WorldStreamTileFile.read(
                        directory.resolve("tile-42.wst"),
                        expected,
                        () -> terrainChecks.incrementAndGet() < 0)
                .close();
        var checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamSession.decodeTile(
                                session(true),
                                directory,
                                again,
                                () -> checks.incrementAndGet() > terrainChecks.get()));
        // Envelope and status carry the profile.
        var envelope = WorldStreamEnvelope.encode(session(true));
        assertTrue(
                WorldStreamEnvelope.decodeSession(envelope, WorldStreamEnvelope.sha256(envelope))
                        .exteriorObjects());
        var plainEnvelope = WorldStreamEnvelope.encode(session(false));
        assertFalse(
                WorldStreamEnvelope.decodeSession(
                                plainEnvelope, WorldStreamEnvelope.sha256(plainEnvelope))
                        .exteriorObjects());
        String nonce = String.format("%016x%016x", NONCE.high(), NONCE.low());
        var staged = (WorldStreamStatus.Staged) decode("WS2 " + nonce + " STAGED 42 7 9 8 2 0 2\n");
        assertEquals(2, staged.objects());
        assertEquals(
                -1,
                ((WorldStreamStatus.Staged) decode("WS2 " + nonce + " STAGED 42 7 9 8 2 0\n"))
                        .objects());
        assertThrows(IOException.class, () -> decode("WS2 " + nonce + " STAGED 42 7 9 8 2 0 3\n"));
        assertTrue(
                ((WorldStreamStatus.Session)
                                decode("WS2 " + nonce + " SESSION TERRAIN_EXTERIOR_V1\n"))
                        .exteriorObjects());
    }

    private static WorldStreamStatus.Message decode(String line) throws IOException {
        byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return WorldStreamStatus.decode(bytes, bytes.length);
    }

    @Test
    void packetOwnerDeletesItsObjectsFileWithThePacket() throws Exception {
        var reference = write(small());
        var offer = packet(reference);
        var expected =
                new WorldStreamTileFile.Expected(
                        NONCE, SCOPE, 42, LEASE, offer.length(), offer.sha256());
        var identity = new Object();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamPacketFile.finalized(
                                expected,
                                directory.resolve("tile-42.wst"),
                                identity,
                                directory.resolve("tile-43.wso")));
        var owner =
                WorldStreamPacketFile.finalized(
                        expected, directory.resolve("tile-42.wst"), identity, objectsPath());
        var moved = owner.move();
        assertEquals(objectsPath(), moved.objectsPath());
        moved.close();
        assertFalse(Files.exists(objectsPath()));
        assertFalse(Files.exists(directory.resolve("tile-42.wst")));
    }

    @Test
    void modelMatrixRebasesTheDoublePlacementOnceToFloat() {
        // A far placement whose float position alone would lose the 0.0625 offset.
        double c = Math.cos(0.7), s = Math.sin(0.7);
        double[] placements = {17066.6875, -3412.3125, 55.5, 2, -c, s, 0, -s, -c, 0, 0, 0, 1};
        var origin = new TerrainScene.Origin(17066.625f, -3412.25f, 50f);
        var model = FloatBuffer.allocate(16);
        var normal = FloatBuffer.allocate(16);
        BgfxWorldStreamRenderer.objectMatrices(placements, 0, origin, model, normal);
        assertEquals(0.0625f, model.get(12));
        assertEquals(-0.0625f, model.get(13));
        assertEquals(5.5f, model.get(14));
        // Local (1, 0, 0) maps by the reviewed ADT heading rule: scale * (-cos, -sin, 0).
        assertEquals((float) (-2 * Math.cos(0.7)), model.get(0));
        assertEquals((float) (-2 * Math.sin(0.7)), model.get(1));
        assertEquals((float) (2 * Math.sin(0.7)), model.get(4));
        assertEquals((float) (-2 * Math.cos(0.7)), model.get(5));
        assertEquals(2f, model.get(10));
        assertEquals(1f, model.get(15));
        assertEquals((float) -Math.cos(0.7), normal.get(0));
        assertEquals(1f, normal.get(10));
        // A general row-major rotation (a quarter turn about x) lands column-major.
        double[] tilted = {0, 0, 0, 3, 1, 0, 0, 0, 0, -1, 0, 1, 0};
        BgfxWorldStreamRenderer.objectMatrices(
                tilted, 0, new TerrainScene.Origin(0, 0, 0), model, normal);
        assertEquals(3f, model.get(0));
        assertEquals(3f, model.get(6)); // local y maps to z
        assertEquals(-3f, model.get(9)); // local z maps to -y
        assertEquals(0f, model.get(5));
        assertEquals(1f, normal.get(6));
        assertEquals(-1f, normal.get(9));
    }

    @Test
    void rangeClassesAndRotationsAreValidated() throws Exception {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(1, 1, ByteBuffer.allocate(4));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 1}),
                new int[] {0, 3, 0, 3, 3, 0},
                new int[] {WorldStreamObjectsFile.CUTOUT, WorldStreamObjectsFile.BLENDED});
        double h = Math.sqrt(0.5);
        builder.placement(0, 1, 2, 3, 1.5, new double[] {1, 0, 0, 0, h, -h, 0, h, h});
        var exterior = builder.build();
        var reference = write(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(WorldStreamObjectsFile.CUTOUT, read.ranges[3]);
        assertEquals(WorldStreamObjectsFile.BLENDED, read.ranges[7]);
        assertEquals(h, read.placements[8], 0);
        // An unknown class, a scaled matrix or a reflection is refused.
        var badClass = WorldStreamTileWriter.Exterior.builder();
        badClass.image(1, 1, ByteBuffer.allocate(4));
        badClass.model();
        badClass.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0},
                new int[] {3});
        badClass.placement(0, 0, 0, 0, 1, 1, 0);
        assertEquals(
                "Draw range",
                assertThrows(IllegalArgumentException.class, badClass::build).getMessage());
        for (double[] rotation :
                new double[][] {
                    {2, 0, 0, 0, 2, 0, 0, 0, 2}, {-1, 0, 0, 0, 1, 0, 0, 0, 1}, new double[9]
                }) {
            var bad = WorldStreamTileWriter.Exterior.builder();
            bad.image(1, 1, ByteBuffer.allocate(4));
            bad.model();
            bad.mesh(
                    FloatBuffer.wrap(new float[24]),
                    IntBuffer.wrap(new int[] {0, 1, 2}),
                    new int[] {0, 3, 0});
            bad.placement(0, 0, 0, 0, 1, rotation);
            assertEquals(
                    "Placement transform",
                    assertThrows(IllegalArgumentException.class, bad::build).getMessage());
        }
    }

    @Test
    void aFrameOfMaximumPlacementsAllocatesNothingPerPlacementOnTheStack() {
        // Every slot at the placement cap, drawn twice (opaque/cutout and the blended pass).
        int placements = BgfxWorldStreamRenderer.SLOT_COUNT * WorldStreamObjectsFile.MAX_PLACEMENTS;
        assertTrue(placements > 5_760 - 1);
        double c = Math.cos(0.3), s = Math.sin(0.3);
        double[] table = new double[placements * WorldStreamObjectsFile.PLACEMENT_DOUBLES];
        for (int p = 0; p < placements; p++) {
            System.arraycopy(
                    new double[] {p, -p, 3, 1, -c, s, 0, -s, -c, 0, 0, 0, 1},
                    0,
                    table,
                    p * WorldStreamObjectsFile.PLACEMENT_DOUBLES,
                    WorldStreamObjectsFile.PLACEMENT_DOUBLES);
        }
        var origin = new TerrainScene.Origin(0, 0, 0);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var frame = BgfxWorldStreamRenderer.ObjectFrame.on(stack);
            long pointer = stack.getPointer();
            for (int pass = 0; pass < 2; pass++) {
                for (int p = 0; p < placements; p++) {
                    frame.placement(table, p, origin);
                    frame.material(p % 3);
                    assertEquals(pointer, stack.getPointer(), "placement " + p);
                }
            }
            assertEquals((float) p(table, placements - 1), frame.model.get(12));
            assertEquals(0.5f, frame.material(WorldStreamObjectsFile.CUTOUT).get(0));
            assertEquals(1f, frame.material(WorldStreamObjectsFile.BLENDED).get(1));
        }
        // Negative control: three vec4s per placement, the old pattern, exhaust the default stack.
        assertThrows(
                OutOfMemoryError.class,
                () -> {
                    try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                        for (int p = 0; p < placements; p++) {
                            stack.floats(0, 0, 0, 0);
                            stack.floats(0.5f, 0, 0, 0);
                            stack.floats(0, 1, 0, 0);
                        }
                    }
                });
    }

    private static double p(double[] table, int p) {
        return table[p * WorldStreamObjectsFile.PLACEMENT_DOUBLES];
    }
}
