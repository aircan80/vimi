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
import com.vibeminer.rendering.world.WmoPortalSet;
import com.vibeminer.rendering.world.WmoPortalTraversal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    /** The quad's vertex colours, {@code r | g << 8 | b << 16} (#375). */
    static final int[] SMALL_COLOURS = {0x010203, 0, 0xffffff, 0x804020};

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
                new int[] {0, 3, 0, 3, 3, 1},
                new int[2],
                IntBuffer.wrap(SMALL_COLOURS));
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

    static ByteBuffer rgba(int pixels, int seed) {
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
    void anAnimationSectionRoundTripsAndPacksForTheChild() throws Exception {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.image(4, 1, chain(4, 1, rgba(4, 20)));
        builder.model();
        var plain = small().data();
        builder.mesh(
                FloatBuffer.wrap(plain.vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 3, 0, 3, 3, 1},
                new int[2],
                IntBuffer.wrap(SMALL_COLOURS));
        builder.placement(0, -100.25, -200.5, 12, 1, 1, 0);
        byte[] animation = WorldObjectAnimationTest.bytes(WorldObjectAnimationTest.swing());
        builder.animate(0, new int[] {0, 1, 2, 3}, animation);
        var exterior = builder.build();
        var reference = write(exterior);
        assertEquals(exterior.bytes(), reference.length());
        assertEquals(
                small().bytes() - placementBytes() + 12 + 16 + (animation.length + 3) / 4 * 4,
                reference.length());
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertArrayEquals(new int[] {0}, read.animations.models);
        assertArrayEquals(new int[] {0, 1, 2, 3}, read.animations.sources[0]);
        assertArrayEquals(animation, read.animations.animations[0]);
        try (var packed = WorldStreamPackedObjects.pack(read, () -> false)) {
            assertNotNull(packed.animations[0]);
            assertEquals(0, packed.refusedAnimations);
            assertEquals(800, packed.animations[0].animation.durationMillis());
        }
        // A file without animations is as before, but version 10.
        Files.delete(objectsPath());
        var none = write(small());
        assertEquals(
                0,
                WorldStreamObjectsFile.read(objectsPath(), IDENTITY, none, () -> false)
                        .animations
                        .count());
    }

    /**
     * The small set with a liquid on its mesh: a 3 x 2 grid at (0, 0, 5), heights 5, its second
     * tile empty.
     */
    static WorldStreamTileWriter.Exterior wet() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.image(4, 1, chain(4, 1, rgba(4, 20)));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(small().data().vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 3, 0, 3, 3, 1},
                new int[2],
                IntBuffer.wrap(SMALL_COLOURS));
        builder.liquid(
                WorldStreamTileWriter.Exterior.Builder.LIQUID_RIVER,
                0,
                3,
                2,
                0,
                0,
                5,
                new float[] {5, 5, 5, 5, 6, 5},
                new boolean[] {true, false});
        builder.placement(0, -100.25, -200.5, 12, 1, 1, 0);
        builder.placement(0, -110.25, -205.5, 13, 2, 0, 1);
        return builder.build();
    }

    @Test
    void aLiquidSectionRoundTripsAndBuildsWaterForEachPlacement() throws Exception {
        var exterior = wet();
        var reference = write(exterior);
        assertEquals(exterior.bytes(), reference.length());
        assertEquals(small().bytes() + 32 + 6 * 4 + 4, reference.length());
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(1, read.liquids.count());
        assertEquals(0, read.liquids.meshes[0]);
        assertArrayEquals(new byte[] {1, 0}, read.liquids.live[0]);
        try (var packed = WorldStreamPackedObjects.pack(read, () -> false)) {
            var water = packed.water;
            assertEquals(2, water.runCount(), "one run per placement");
            assertEquals(2, water.tiles, "one live tile each");
            assertEquals(6, water.runs[1], "two triangles per tile");
            assertEquals(
                    1, water.runs[WorldObjectWater.RUN_INTS + 2], "the second run's placement");
            assertEquals(2 * 4 * WorldStreamPackedTile.VERTEX_BYTES + 2 * 6 * 4, water.bytes());
            assertTrue(packed.bufferBytes() >= water.bytes(), "charged with the set's buffers");
            // Native positions through each placement: the first turns local (a, b) to (-a, -b),
            // the second (scale 2, a quarter turn) to (2b, -2a); heights are scaled too.
            double unit = com.vibeminer.formats.world.WmoLiquid.UNIT;
            int stride = WorldStreamPackedTile.VERTEX_BYTES;
            var v = water.vertices;
            assertEquals(-100.25, v.getFloat(0), 1e-4);
            assertEquals(-200.5, v.getFloat(4), 1e-4);
            assertEquals(17, v.getFloat(8), 1e-4);
            assertEquals(-100.25 - unit, v.getFloat(stride), 1e-3);
            assertEquals(-110.25, v.getFloat(4 * stride + stride), 1e-3);
            assertEquals(-205.5 - 2 * unit, v.getFloat(4 * stride + stride + 4), 1e-3);
            assertEquals(13 + 2 * 5, v.getFloat(4 * stride + stride + 8), 1e-3);
            // MCCV slot: deep water, river kind; a zero normal: unlit, as terrain water.
            assertEquals(255, v.get(16) & 255);
            assertEquals(WorldStreamObjectsFile.LIQUID_RIVER, v.get(17));
            assertEquals(128, v.get(20) & 255);
            // The quad splits on its (0, 0)--(1, 1) diagonal: corners 0, 1, 3 and 0, 3, 2.
            int[] first = new int[6];
            for (int i = 0; i < 6; i++) {
                first[i] = water.indices.getInt(i * 4);
            }
            assertArrayEquals(new int[] {0, 1, 3, 0, 3, 2}, first);
        }
    }

    /** Two meshes of one model, each with a 3 x 2 liquid. */
    static WorldStreamTileWriter.Exterior twoWet() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.model();
        for (int mesh = 0; mesh < 2; mesh++) {
            builder.mesh(
                    FloatBuffer.wrap(small().data().vertices),
                    IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                    new int[] {0, 6, 0});
            builder.liquid(
                    WorldStreamTileWriter.Exterior.Builder.LIQUID_OCEAN,
                    0,
                    3,
                    2,
                    0,
                    0,
                    5,
                    new float[] {5, 5, 5, 5, 5, 5},
                    new boolean[] {true, true});
        }
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        return builder.build();
    }

    /** The bytes of {@code exterior}'s file with {@code change} applied at a liquid offset. */
    private byte[] liquidBytes(
            WorldStreamTileWriter.Exterior exterior, java.util.function.Consumer<ByteBuffer> change)
            throws Exception {
        Files.deleteIfExists(objectsPath());
        write(exterior);
        var bytes = Files.readAllBytes(objectsPath());
        Files.delete(objectsPath());
        change.accept(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        return bytes;
    }

    @Test
    void aCorruptLiquidSectionIsRefused() throws Exception {
        // wet(): one 60-byte entry at the end: mesh, kind, colour, x and y vertices, corner (3
        // floats), six heights, two tile bytes and two padding bytes.
        int entry = 60;
        java.util.function.IntUnaryOperator at = offset -> -entry + offset;
        assertEquals(
                "Liquid padding",
                refusedFile(liquidBytes(wet(), b -> b.put(b.capacity() - 1, (byte) 1))));
        assertEquals(
                "Liquid tile byte",
                refusedFile(
                        liquidBytes(
                                wet(), b -> b.put(b.capacity() + at.applyAsInt(56), (byte) 2))));
        assertEquals(
                "Liquid mesh",
                refusedFile(liquidBytes(wet(), b -> b.putInt(b.capacity() + at.applyAsInt(0), 5))));
        assertEquals(
                "Liquid entry",
                refusedFile(liquidBytes(wet(), b -> b.putInt(b.capacity() + at.applyAsInt(4), 3))),
                "an unknown kind");
        // A grid larger than the section holds: the entry overruns it.
        assertEquals(
                "Liquid entry bounds",
                refusedFile(
                        liquidBytes(wet(), b -> b.putInt(b.capacity() + at.applyAsInt(12), 4))));
        // Counts and bytes disagree, or the section is longer or shorter than the file.
        assertEquals(
                "Objects liquid counts", refusedFile(liquidBytes(wet(), b -> b.putInt(180, 0))));
        assertEquals(
                "Objects header counts",
                refusedFile(liquidBytes(wet(), b -> b.putInt(184, b.getInt(184) + 4))),
                "a section longer than the file");
        assertEquals(
                "Objects header counts",
                refusedFile(liquidBytes(wet(), b -> b.putInt(184, b.getInt(184) - 4))),
                "a section shorter than the file");
        // Two entries whose meshes do not ascend.
        assertEquals(
                "Liquid meshes ascend",
                refusedFile(liquidBytes(twoWet(), b -> b.putInt(b.capacity() - 2 * entry, 1))));
        // The untouched file reads.
        Files.deleteIfExists(objectsPath());
        var reference = write(twoWet());
        assertEquals(
                2,
                WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false)
                        .liquids
                        .count());
    }

    /** A one-model set whose model (4 vertices) carries five animation bytes (one pad of 3). */
    private static WorldStreamTileWriter.Exterior animated(int[] sources) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(small().data().vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0});
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        builder.animate(0, sources, new byte[] {1, 2, 3, 4, 5});
        return builder.build();
    }

    /** Writes {@code bytes} as the objects file and returns why a read refuses them. */
    private String refusedFile(byte[] bytes) throws Exception {
        Files.write(objectsPath(), bytes);
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        var reference =
                new WorldStreamObjectsFile.Reference(
                        bytes.length, java.util.HexFormat.of().formatHex(digest));
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        objectsPath(), IDENTITY, reference, () -> false));
        return failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage();
    }

    @Test
    void aCorruptAnimationSectionIsRefused() throws Exception {
        write(animated(new int[] {0, 1, 2, 3}));
        var clean = Files.readAllBytes(objectsPath());
        int section = 12 + 16 + 8; // one entry: its three ints, four sources, five bytes padded
        int at = clean.length - section;
        var little = java.nio.ByteOrder.LITTLE_ENDIAN;
        assertEquals(1, ByteBuffer.wrap(clean).order(little).getInt(172));
        assertEquals(section, ByteBuffer.wrap(clean).order(little).getInt(176));
        assertEquals(4, ByteBuffer.wrap(clean).order(little).getInt(at + 4));
        // A padding byte that is not zero.
        var padding = clean.clone();
        padding[padding.length - 1] = 9;
        assertEquals("Animation padding", refusedFile(padding));
        // The header names a shorter section than the file holds.
        var shorter = clean.clone();
        ByteBuffer.wrap(shorter).order(little).putInt(176, section - 4);
        assertEquals("Objects header counts", refusedFile(shorter));
        // The header counts two entries; the section holds one.
        var counts = clean.clone();
        ByteBuffer.wrap(counts).order(little).putInt(172, 2);
        assertEquals("Animation entry bounds", refusedFile(counts));
        // An entry that names more bytes than its section holds.
        var longer = clean.clone();
        ByteBuffer.wrap(longer).order(little).putInt(at + 8, 64);
        assertEquals("Animation entry bounds", refusedFile(longer));
        // A consistent section whose sources do not name every vertex of the model.
        var three = new byte[clean.length - 4];
        System.arraycopy(clean, 0, three, 0, at + 12 + 12);
        System.arraycopy(clean, at + 12 + 16, three, at + 12 + 12, clean.length - (at + 12 + 16));
        var fix = ByteBuffer.wrap(three).order(little);
        fix.putLong(16, three.length);
        fix.putInt(176, section - 4);
        fix.putInt(at + 4, 3);
        assertEquals("Animation sources differ from the model's vertices", refusedFile(three));
        // The builder refuses the same mismatch before anything is written.
        assertThrows(IllegalArgumentException.class, () -> animated(new int[] {0, 1, 2}));
    }

    /** The bytes of one placement row: the second placement {@link #small} has beyond one. */
    private static int placementBytes() {
        return 8 + WorldStreamObjectsFile.PLACEMENT_DOUBLES * 8;
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
        assertArrayEquals(SMALL_COLOURS, written.colours);
        assertArrayEquals(written.colours, read.colours);
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
                                read.colours,
                                read.indices,
                                read.pixels));
        // A BC1 image drawn by a cutout or blended range is refused: its alpha is not sampled
        // on every backend.
        // The check reads the kind, so the unlit flag and the additive kind (#367) count too.
        for (int kind :
                new int[] {
                    WorldStreamObjectsFile.CUTOUT,
                    WorldStreamObjectsFile.BLENDED,
                    WorldStreamObjectsFile.ADDITIVE,
                    WorldStreamObjectsFile.CUTOUT | WorldStreamObjectsFile.UNLIT
                }) {
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
        // An unlit opaque range ignores alpha too, so it may sample BC1: the flag does not change
        // the kind.
        var unlit = WorldStreamTileWriter.Exterior.builder();
        unlit.image(256, 64, ObjectTextureFormats.BC1, ByteBuffer.wrap(blocks1));
        unlit.model();
        unlit.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0},
                new int[] {WorldStreamObjectsFile.OPAQUE | WorldStreamObjectsFile.UNLIT});
        unlit.placement(0, 0, 0, 0, 1, 1, 0);
        assertEquals(
                WorldStreamObjectsFile.OPAQUE | WorldStreamObjectsFile.UNLIT,
                unlit.build().data().ranges[3]);
        // The file carries version 9: the reader accepts no other. A version 3 file (two-int
        // RGBA8-only image table) or a stale version 4 file (#420, before the additive and unlit
        // classes of #367) fails its header check instead of being misread.
        var clean = Files.readAllBytes(objectsPath());
        assertEquals(WorldStreamObjectsFile.VERSION, clean[8]);
        assertEquals(11, WorldStreamObjectsFile.VERSION);
        // Version 5 (#367, before the vertex colours of #375), version 6 (#375, before the
        // portal tables and mesh groups of #383), version 7 (before the INTERIOR_LIGHT range
        // flag of #383) and version 8 (before placement portal ties) are stale as well.
        for (byte stale : new byte[] {3, 4, 5, 6, 7, 8}) {
            var bytes = clean.clone();
            bytes[8] = stale;
            Files.write(objectsPath(), bytes);
            var refused =
                    assertThrows(
                            IOException.class,
                            () ->
                                    WorldStreamObjectsFile.read(
                                            objectsPath(), IDENTITY, reference, () -> false));
            assertEquals("Objects header identity", refused.getCause().getMessage());
        }
    }

    /** Rooms A (x 0..10) and B (x 10..20) joined by a door in the plane x = 10 (#383). */
    static WmoPortalSet twoRooms() {
        return new WmoPortalSet(
                java.util.List.of(
                        new WmoPortalTraversal.Group(0x2000, 0, -5, 0, 10, 5, 5, 0, 1),
                        new WmoPortalTraversal.Group(0x2000, 10, -5, 0, 20, 5, 5, 1, 1)),
                java.util.List.of(
                        new WmoPortalTraversal.Portal(
                                new double[] {10, -1, 0, 10, 1, 0, 10, 1, 2, 10, -1, 2},
                                1,
                                0,
                                0,
                                -10)),
                java.util.List.of(
                        new WmoPortalTraversal.Ref(0, 1, -1), new WmoPortalTraversal.Ref(0, 0, 1)));
    }

    /** A one-triangle mesh of the current model drawn by one opaque range, as group {@code g}. */
    private static void triangle(WorldStreamTileWriter.Exterior.Builder builder, int group) {
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0},
                new int[1],
                IntBuffer.allocate(0),
                group);
    }

    /**
     * Issue #383 step 3a: a building model's portal set and each mesh's group ordinal round-trip
     * through the file exactly (the values are the WMO's own floats); a model without a set has
     * group -1 on every mesh. The traversal built from the read set walks the same rooms.
     */
    @Test
    void portalSetsAndMeshGroupsRoundTrip() throws Exception {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        builder.model();
        assertTrue(builder.portalsFit(twoRooms()));
        builder.portals(twoRooms());
        triangle(builder, 1);
        triangle(builder, 0);
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        builder.placement(1, 0, 0, 0, 1, 1, 0);
        var exterior = builder.build();
        var written = data(exterior);
        assertEquals(1, written.portalSetCount());
        assertArrayEquals(new int[] {-1, 1, 0}, written.meshGroups);
        var reference = write(exterior);
        assertEquals(exterior.bytes(), reference.length());
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertArrayEquals(written.portalSets, read.portalSets);
        assertArrayEquals(written.portalGroups, read.portalGroups);
        assertArrayEquals(written.portalBoxes, read.portalBoxes);
        assertArrayEquals(written.portalVertexCounts, read.portalVertexCounts);
        assertArrayEquals(written.portalPlanes, read.portalPlanes);
        assertArrayEquals(written.portalVertices, read.portalVertices);
        assertArrayEquals(written.portalRefs, read.portalRefs);
        assertArrayEquals(written.meshGroups, read.meshGroups);
        assertEquals(1, read.portalModel(0));
        var expected = twoRooms();
        var carried = read.portalSet(0);
        assertEquals(expected.groups(), carried.groups());
        assertEquals(expected.refs(), carried.refs());
        assertEquals(1, carried.portals().size());
        var portal = carried.portals().getFirst();
        assertArrayEquals(expected.portals().getFirst().vertices(), portal.vertices());
        assertEquals(
                java.util.List.of(1.0, 0.0, 0.0, -10.0),
                java.util.List.of(portal.nx(), portal.ny(), portal.nz(), portal.distance()));
        var t = read.portalSet(0).traversal();
        var looking =
                java.util.List.of(
                        new WmoPortalTraversal.Plane(1, -1, 0, -5),
                        new WmoPortalTraversal.Plane(1, 1, 0, -5),
                        new WmoPortalTraversal.Plane(1, 0, -1, -4),
                        new WmoPortalTraversal.Plane(1, 0, 1, -6));
        var result = t.visible(5, 0, 1, looking);
        assertTrue(result.inside() && result.visible(0) && result.visible(1));
        // The portal tables are charged in the file length.
        assertEquals(
                WorldStreamObjectsFile.length(2, 3, 3, 2, 1, 9, 9, written.imageBytes())
                        + WorldStreamObjectsFile.portalLength(1, 2, 1, 4, 2),
                exterior.bytes());
    }

    /**
     * Rooms A, B and C along x: a quad door A|B at x = 10 and a triangle door B|C at x = 20, so the
     * set differs from {@link #twoRooms} in every table size.
     */
    static WmoPortalSet threeRooms() {
        return new WmoPortalSet(
                java.util.List.of(
                        new WmoPortalTraversal.Group(0x2000, 0, -5, 0, 10, 5, 5, 0, 1),
                        new WmoPortalTraversal.Group(0x2000, 10, -5, 0, 20, 5, 5, 1, 2),
                        new WmoPortalTraversal.Group(0x2000, 20, -5, 0, 30, 5, 5, 3, 1)),
                java.util.List.of(
                        new WmoPortalTraversal.Portal(
                                new double[] {10, -1, 0, 10, 1, 0, 10, 1, 2, 10, -1, 2},
                                1,
                                0,
                                0,
                                -10),
                        new WmoPortalTraversal.Portal(
                                new double[] {20, -1, 0, 20, 1, 0, 20, 0, 2}, 1, 0, 0, -20)),
                java.util.List.of(
                        new WmoPortalTraversal.Ref(0, 1, -1),
                        new WmoPortalTraversal.Ref(0, 0, 1),
                        new WmoPortalTraversal.Ref(1, 2, -1),
                        new WmoPortalTraversal.Ref(1, 1, 1)));
    }

    /** Model 0 without a set, model 1 with {@link #twoRooms}, model 2 with {@link #threeRooms}. */
    private static WorldStreamTileWriter.Exterior twoSets() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.model();
        triangle(builder, -1);
        builder.model();
        builder.portals(twoRooms());
        triangle(builder, 1);
        triangle(builder, 0);
        builder.model();
        builder.portals(threeRooms());
        triangle(builder, 2);
        triangle(builder, 0);
        triangle(builder, 1);
        for (int m = 0; m < 3; m++) {
            builder.placement(m, 0, 0, 0, 1, 1, 0);
        }
        return builder.build();
    }

    private static void assertSameSet(WmoPortalSet expected, WmoPortalSet carried) {
        assertEquals(expected.groups(), carried.groups());
        assertEquals(expected.refs(), carried.refs());
        assertEquals(expected.portals().size(), carried.portals().size());
        for (int p = 0; p < expected.portals().size(); p++) {
            var e = expected.portals().get(p);
            var c = carried.portals().get(p);
            assertArrayEquals(e.vertices(), c.vertices());
            assertEquals(
                    java.util.List.of(e.nx(), e.ny(), e.nz(), e.distance()),
                    java.util.List.of(c.nx(), c.ny(), c.nz(), c.distance()));
        }
    }

    /** A second set reads its own rows of every table: the set-relative prefix offsets. */
    @Test
    void twoPortalSetsOfDifferentSizesRoundTrip() throws Exception {
        var exterior = twoSets();
        var reference = write(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(2, read.portalSetCount());
        assertEquals(1, read.portalModel(0));
        assertEquals(2, read.portalModel(1));
        assertSameSet(twoRooms(), read.portalSet(0));
        assertSameSet(threeRooms(), read.portalSet(1));
        assertArrayEquals(new int[] {-1, 1, 0, 2, 0, 1}, read.meshGroups);
        assertEquals(
                WorldStreamObjectsFile.length(3, 6, 6, 3, 1, 18, 18, read.imageBytes())
                        + WorldStreamObjectsFile.portalLength(2, 5, 3, 11, 6),
                reference.length());
        // The header names the five table counts at 152..171 after the format mask at 148 (none
        // here).
        var head =
                java.nio.ByteBuffer.wrap(Files.readAllBytes(objectsPath()))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, head.getInt(148));
        assertEquals(
                java.util.List.of(2, 5, 3, 11, 6),
                java.util.List.of(
                        head.getInt(152),
                        head.getInt(156),
                        head.getInt(160),
                        head.getInt(164),
                        head.getInt(168)));
    }

    /** The tables of a valid set file, copied so one value can be changed and revalidated. */
    private static final class Tables {
        final WorldStreamObjectsFile.Data d;
        final int[] meshGroups, sets, groups, vertexCounts, refs, parents, tiedGroups;
        final float[] boxes, planes, vertices;

        Tables(WorldStreamObjectsFile.Data d) {
            this.d = d;
            parents = d.placementParents.clone();
            tiedGroups = d.placementGroups.clone();
            meshGroups = d.meshGroups.clone();
            sets = d.portalSets.clone();
            groups = d.portalGroups.clone();
            boxes = d.portalBoxes.clone();
            vertexCounts = d.portalVertexCounts.clone();
            planes = d.portalPlanes.clone();
            vertices = d.portalVertices.clone();
            refs = d.portalRefs.clone();
        }

        String refused() {
            return assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new WorldStreamObjectsFile.Data(
                                            d.models,
                                            d.meshes,
                                            d.ranges,
                                            d.placementModels,
                                            d.placements,
                                            d.images,
                                            d.vertices,
                                            d.colours,
                                            d.indices,
                                            d.pixels,
                                            d.compressedFormats,
                                            meshGroups,
                                            sets,
                                            groups,
                                            boxes,
                                            vertexCounts,
                                            planes,
                                            vertices,
                                            refs,
                                            parents,
                                            tiedGroups))
                    .getMessage();
        }
    }

    /**
     * {@link #twoSets}'s three placements, then four placements of model 0: one tied to interior
     * group 1 of placement 1's set (kept), and three ties the builder drops: to a placement whose
     * model has no set, to a group past the set, and to a placement not yet added.
     */
    private static WorldStreamTileWriter.Exterior tiedSets() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.model();
        triangle(builder, -1);
        builder.model();
        builder.portals(twoRooms());
        triangle(builder, 1);
        triangle(builder, 0);
        builder.model();
        builder.portals(threeRooms());
        triangle(builder, 2);
        triangle(builder, 0);
        triangle(builder, 1);
        var turn = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
        for (int m = 0; m < 3; m++) {
            builder.placement(m, 0, 0, 0, 1, turn);
        }
        builder.placement(0, 1, 0, 0, 1, turn, 1, 1);
        builder.placement(0, 2, 0, 0, 1, turn, 0, 0);
        builder.placement(0, 3, 0, 0, 1, turn, 2, 3);
        builder.placement(0, 4, 0, 0, 1, turn, 9, 0);
        return builder.build();
    }

    @Test
    void placementTiesRoundTripInTheFormerlyReservedInt() throws Exception {
        var exterior = tiedSets();
        var written = data(exterior);
        assertArrayEquals(new int[] {-1, -1, -1, 1, -1, -1, -1}, written.placementParents);
        assertArrayEquals(new int[] {-1, -1, -1, 1, -1, -1, -1}, written.placementGroups);
        // A tie costs no byte: the length is that of the same tables untied.
        assertEquals(
                WorldStreamObjectsFile.length(3, 6, 6, 7, 1, 18, 18, written.imageBytes())
                        + WorldStreamObjectsFile.portalLength(2, 5, 3, 11, 6),
                exterior.bytes());
        var reference = write(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertArrayEquals(written.placementParents, read.placementParents);
        assertArrayEquals(written.placementGroups, read.placementGroups);
        assertEquals((1 + 1) << 16 | 1, WorldStreamObjectsFile.tie(1, 1));
        assertEquals(0, WorldStreamObjectsFile.tie(-1, -1));
        // A stored tie naming a group without a parent is refused while reading.
        var bytes = Files.readAllBytes(objectsPath());
        int placements =
                WorldStreamObjectsFile.HEADER_BYTES
                        + written.modelCount() * 8
                        + written.meshCount() * 24
                        + written.rangeCount() * WorldStreamObjectsFile.RANGE_INTS * 4;
        int tie = placements + 3 * (8 + WorldStreamObjectsFile.PLACEMENT_DOUBLES * 8) + 4;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(tie, 1);
        Files.write(objectsPath(), bytes);
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        objectsPath(), IDENTITY, reference, () -> false));
        assertEquals("Placement tie", refused.getCause().getMessage());
    }

    @Test
    void placementTiesNameAnotherPlacementsInteriorGroup() throws Exception {
        var valid = data(tiedSets());
        var self = new Tables(valid);
        self.parents[3] = 3;
        assertEquals("Placement ties", self.refused());
        var setless = new Tables(valid);
        setless.parents[3] = 0;
        assertEquals("Placement ties", setless.refused());
        var past = new Tables(valid);
        past.tiedGroups[3] = 2;
        assertEquals("Placement ties", past.refused());
        var outside = new Tables(valid);
        outside.parents[3] = 7;
        assertEquals("Placement ties", outside.refused());
        var half = new Tables(valid);
        half.parents[3] = -1;
        assertEquals("Placement ties", half.refused());
        // Group 1 of placement 1's set made exterior: a tie never names an exterior group.
        var exterior = new Tables(valid);
        exterior.groups[WorldStreamObjectsFile.PORTAL_GROUP_INTS] = 0x8;
        assertEquals("Placement ties", exterior.refused());
        var counts = new Tables(valid);
        assertEquals(
                "Placement ties",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new WorldStreamObjectsFile.Data(
                                                valid.models,
                                                valid.meshes,
                                                valid.ranges,
                                                valid.placementModels,
                                                valid.placements,
                                                valid.images,
                                                valid.vertices,
                                                valid.colours,
                                                valid.indices,
                                                valid.pixels,
                                                valid.compressedFormats,
                                                counts.meshGroups,
                                                counts.sets,
                                                counts.groups,
                                                counts.boxes,
                                                counts.vertexCounts,
                                                counts.planes,
                                                counts.vertices,
                                                counts.refs,
                                                new int[] {-1},
                                                new int[] {-1}))
                        .getMessage());
    }

    @Test
    void portalTablesThatDoNotDescribeTheirSetsAreRefused() throws Exception {
        var valid = data(twoSets());
        // Sets out of model order (the tables otherwise intact).
        var order = new Tables(valid);
        order.sets[0] = 2;
        order.sets[WorldStreamObjectsFile.PORTAL_SET_INTS] = 1;
        assertEquals("Portal sets", order.refused());
        // A zero-group set.
        var empty = new Tables(valid);
        empty.sets[1] = 0;
        assertEquals("Portal sets", empty.refused());
        // Vertex counts crossing a set boundary: the first set's door takes a vertex of the
        // second set's (every total unchanged).
        var crossing = new Tables(valid);
        crossing.vertexCounts[0] = 5;
        crossing.vertexCounts[1] = 3;
        assertEquals("Portal sets", crossing.refused());
        // A second-set reference by its absolute group index (2 + 1) instead of the set's own.
        var absolute = new Tables(valid);
        absolute.refs[(2 + 0) * WorldStreamObjectsFile.PORTAL_REF_INTS + 1] = 3;
        assertEquals("Portal set", absolute.refused());
        // A group's reference range past its set's references: C (row 4) takes two from 3.
        var range = new Tables(valid);
        range.groups[4 * WorldStreamObjectsFile.PORTAL_GROUP_INTS + 2] = 2;
        assertEquals("Portal set", range.refused());
        // A side outside a signed 16-bit word.
        var side = new Tables(valid);
        side.refs[2] = 40000;
        assertEquals("Portal set", side.refused());
        // A value past the file's bound.
        var far = new Tables(valid);
        far.vertices[0] = 2e6f;
        assertEquals("Portal value", far.refused());
        // A mesh of a model with a set at -1, and one past its set's groups.
        var unset = new Tables(valid);
        unset.meshGroups[1] = -1;
        assertEquals("Mesh groups", unset.refused());
        var past = new Tables(valid);
        past.meshGroups[1] = 2;
        assertEquals("Mesh groups", past.refused());
    }

    @Test
    void portalHeaderCountsAreBoundedAndConsistent() throws Exception {
        var reference = write(twoSets());
        var clean = Files.readAllBytes(objectsPath());
        java.util.function.BiFunction<Integer, Integer, String> refused =
                (at, value) -> {
                    var bytes = clean.clone();
                    java.nio.ByteBuffer.wrap(bytes)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putInt(at, value);
                    try {
                        Files.write(objectsPath(), bytes);
                        var failure =
                                assertThrows(
                                        IOException.class,
                                        () ->
                                                WorldStreamObjectsFile.read(
                                                        objectsPath(),
                                                        IDENTITY,
                                                        reference,
                                                        () -> false));
                        return failure.getCause().getMessage();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                };
        // Each count is bounded by its cap, and never negative, before anything is allocated.
        assertEquals("Objects portal counts", refused.apply(152, WmoPortalSet.MAX_TILE_SETS + 1));
        assertEquals("Objects portal counts", refused.apply(156, WmoPortalSet.MAX_TILE_GROUPS + 1));
        assertEquals(
                "Objects portal counts", refused.apply(160, WmoPortalSet.MAX_TILE_PORTALS + 1));
        assertEquals(
                "Objects portal counts", refused.apply(164, WmoPortalSet.MAX_TILE_VERTICES + 1));
        assertEquals("Objects portal counts", refused.apply(168, WmoPortalSet.MAX_TILE_REFS + 1));
        assertEquals("Objects portal counts", refused.apply(164, -1));
        // Within the caps but not the file's length.
        assertEquals("Objects header counts", refused.apply(164, 12));
        // Byte 148 is #466's format mask, BC bits only; bytes 172-179 count the animation section
        // (#384), bytes 180-187 the liquid section; the reserved bytes from 188 must be zero.
        assertEquals("Objects header counts", refused.apply(148, 1));
        assertEquals("Objects animation counts", refused.apply(172, 1));
        assertEquals("Objects animation counts", refused.apply(176, 4));
        assertEquals("Objects animation counts", refused.apply(172, -1));
        assertEquals("Objects liquid counts", refused.apply(180, 1));
        assertEquals("Objects liquid counts", refused.apply(184, 4));
        assertEquals("Objects liquid counts", refused.apply(180, -1));
        assertEquals("Reserved objects bytes", refused.apply(188, 1));
        assertEquals("Reserved objects bytes", refused.apply(252, 1));
    }

    @Test
    void portalSetsAndMeshGroupsAreValidated() {
        // A mesh group outside its model's set, or any group on a model without a set.
        var outside = WorldStreamTileWriter.Exterior.builder();
        outside.image(2, 2, chain(2, 2, rgba(4, 10)));
        outside.model();
        outside.portals(twoRooms());
        triangle(outside, 2);
        outside.placement(0, 0, 0, 0, 1, 1, 0);
        assertEquals(
                "Mesh groups",
                assertThrows(IllegalArgumentException.class, outside::build).getMessage());
        var unset = WorldStreamTileWriter.Exterior.builder();
        unset.image(2, 2, chain(2, 2, rgba(4, 10)));
        unset.model();
        triangle(unset, 0);
        unset.placement(0, 0, 0, 0, 1, 1, 0);
        assertEquals(
                "Mesh groups",
                assertThrows(IllegalArgumentException.class, unset::build).getMessage());
        // One set per model, given before its meshes, and within the tile's bounds.
        var twice = WorldStreamTileWriter.Exterior.builder();
        twice.model();
        twice.portals(twoRooms());
        assertThrows(IllegalArgumentException.class, () -> twice.portals(twoRooms()));
        var late = WorldStreamTileWriter.Exterior.builder();
        late.model();
        triangle(late, -1);
        assertThrows(IllegalArgumentException.class, () -> late.portals(twoRooms()));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamTileWriter.Exterior.builder().portals(twoRooms()));
        var full = WorldStreamTileWriter.Exterior.builder();
        for (int m = 0; m < WmoPortalSet.MAX_TILE_SETS; m++) {
            full.model();
            full.portals(twoRooms());
        }
        full.model();
        assertFalse(full.portalsFit(twoRooms()));
        assertThrows(IllegalArgumentException.class, () -> full.portals(twoRooms()));
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
        // Every table at its cap, the portal tables (#383) included, fits the ceiling, which is
        // derived from them (#308): 33 MiB with the vertex colours of #375 and the large allowance,
        // 46 MiB with the interior allowance of roots drawn by group, 50 MiB with the larger doodad
        // pools, 53 MiB with the tile's own interior allowance, 69 MiB with the animation section
        // of
        // #384 (CPU bytes the child parses, not GPU memory).
        long caps =
                WorldStreamObjectsFile.length(
                                WorldStreamObjectsFile.MAX_MODELS,
                                WorldStreamObjectsFile.MAX_MESHES,
                                WorldStreamObjectsFile.MAX_RANGES,
                                WorldStreamObjectsFile.MAX_PLACEMENTS,
                                WorldStreamObjectsFile.MAX_IMAGES,
                                WorldStreamObjectsFile.MAX_VERTICES,
                                WorldStreamObjectsFile.MAX_INDICES,
                                WorldStreamObjectsFile.MAX_IMAGE_BYTES)
                        + WorldStreamObjectsFile.portalLength(
                                WmoPortalSet.MAX_TILE_SETS,
                                WmoPortalSet.MAX_TILE_GROUPS,
                                WmoPortalSet.MAX_TILE_PORTALS,
                                WmoPortalSet.MAX_TILE_VERTICES,
                                WmoPortalSet.MAX_TILE_REFS)
                        + WorldStreamObjectsFile.MAX_ANIMATION_BYTES
                        + WorldStreamObjectsFile.Liquids.maxBytes();
        assertTrue(caps > (25 << 20) && caps <= WorldStreamObjectsFile.MAX_BYTES, "caps " + caps);
        assertEquals(70 << 20, WorldStreamObjectsFile.MAX_BYTES, "the liquid section adds 1 MiB");
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
        // 1,024 ranges per placement: one more placement than the draw budget allows.
        for (int p = 0; p <= WorldStreamObjectsFile.MAX_DRAWS / 1024; p++) {
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
                // Header bytes only: an all-zero attribute digest, which no packet reads back.
                WorldStreamTileFile.encodeHeader(
                        named(
                                WorldStreamTileFileTest.header(
                                        WorldStreamTileFileTest.full(false), false),
                                null),
                        "00".repeat(32));
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
                assertEquals(
                        4 * WorldStreamPackedObjects.GPU_VERTEX_BYTES,
                        source.objects.vertices.capacity());
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
                IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 1, 1, 2, 0, 2, 0, 1}),
                new int[] {0, 3, 0, 3, 3, 0, 6, 3, 0, 9, 3, 0},
                new int[] {
                    // Version 6 (#375): the two-sided flag round-trips.
                    WorldStreamObjectsFile.CUTOUT | WorldStreamObjectsFile.TWO_SIDED,
                    // Version 8 (#383): the interior-light flag round-trips.
                    WorldStreamObjectsFile.BLENDED | WorldStreamObjectsFile.INTERIOR_LIGHT,
                    // Version 5 (#367): additive and the unlit flag round-trip.
                    WorldStreamObjectsFile.ADDITIVE | WorldStreamObjectsFile.UNLIT,
                    WorldStreamObjectsFile.OPAQUE | WorldStreamObjectsFile.UNLIT
                });
        double h = Math.sqrt(0.5);
        builder.placement(0, 1, 2, 3, 1.5, new double[] {1, 0, 0, 0, h, -h, 0, h, h});
        var exterior = builder.build();
        var reference = write(exterior);
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertEquals(
                WorldStreamObjectsFile.CUTOUT | WorldStreamObjectsFile.TWO_SIDED, read.ranges[3]);
        assertEquals(WorldStreamObjectsFile.CUTOUT, WorldStreamObjectsFile.kind(read.ranges[3]));
        assertEquals(
                WorldStreamObjectsFile.BLENDED | WorldStreamObjectsFile.INTERIOR_LIGHT,
                read.ranges[7]);
        assertTrue(WorldStreamObjectsFile.sorted(read.ranges[7]));
        assertEquals(
                WorldStreamObjectsFile.ADDITIVE | WorldStreamObjectsFile.UNLIT, read.ranges[11]);
        assertEquals(WorldStreamObjectsFile.OPAQUE | WorldStreamObjectsFile.UNLIT, read.ranges[15]);
        assertEquals(11, WorldStreamObjectsFile.VERSION);
        assertEquals(h, read.placements[8], 0);
        // An unknown class, a scaled matrix or a reflection is refused.
        // Modulate (4) and unknown flag bits stay refused: 0x800 alone and beside a known kind
        // (0x200 is TWO_SIDED since version 6, 0x400 INTERIOR_LIGHT since version 8).
        for (int unknown :
                new int[] {
                    4,
                    0x800,
                    WorldStreamObjectsFile.OPAQUE | 0x800,
                    4 | WorldStreamObjectsFile.INTERIOR_LIGHT
                }) {
            var badClass = WorldStreamTileWriter.Exterior.builder();
            badClass.image(1, 1, ByteBuffer.allocate(4));
            badClass.model();
            badClass.mesh(
                    FloatBuffer.wrap(new float[24]),
                    IntBuffer.wrap(new int[] {0, 1, 2}),
                    new int[] {0, 3, 0},
                    new int[] {unknown});
            badClass.placement(0, 0, 0, 0, 1, 1, 0);
            assertEquals(
                    "Draw range",
                    assertThrows(IllegalArgumentException.class, badClass::build).getMessage(),
                    "Class " + Integer.toHexString(unknown));
        }
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

    /**
     * Issue #375: a mesh without colours reads back as zero colours; a colour with its fourth byte
     * set, or a colour count that is neither zero nor the vertex count, is refused.
     */
    @Test
    void vertexColoursDefaultToZeroAndAreValidated() throws Exception {
        var plain = WorldStreamTileWriter.Exterior.builder();
        plain.image(1, 1, ByteBuffer.allocate(4));
        plain.model();
        plain.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        plain.placement(0, 0, 0, 0, 1, 1, 0);
        var reference = write(plain.build());
        var read = WorldStreamObjectsFile.read(objectsPath(), IDENTITY, reference, () -> false);
        assertArrayEquals(new int[3], read.colours);

        var short2 = WorldStreamTileWriter.Exterior.builder();
        short2.image(1, 1, ByteBuffer.allocate(4));
        short2.model();
        assertEquals(
                "Exterior vertex colours",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        short2.mesh(
                                                FloatBuffer.wrap(new float[24]),
                                                IntBuffer.wrap(new int[] {0, 1, 2}),
                                                new int[] {0, 3, 0},
                                                new int[1],
                                                IntBuffer.wrap(new int[2])))
                        .getMessage());

        var alpha = WorldStreamTileWriter.Exterior.builder();
        alpha.image(1, 1, ByteBuffer.allocate(4));
        alpha.model();
        alpha.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0},
                new int[1],
                IntBuffer.wrap(new int[] {0, 0x01000000, 0}));
        alpha.placement(0, 0, 0, 0, 1, 1, 0);
        assertEquals(
                "Vertex colour",
                assertThrows(IllegalArgumentException.class, alpha::build).getMessage());
    }

    /** Issue #375: GPU vertices are the file's eight floats, then the colour bytes R, G, B, 0. */
    @Test
    void packedVerticesInterleaveFloatsAndColourBytes() throws Exception {
        var data = data(small());
        try (var packed = WorldStreamPackedObjects.pack(data, () -> false)) {
            assertEquals(36, WorldStreamPackedObjects.GPU_VERTEX_BYTES);
            assertEquals(4 * 36, packed.vertices.capacity());
            for (int v = 0; v < 4; v++) {
                int at = v * 36;
                for (int i = 0; i < 8; i++) {
                    assertEquals(data.vertices[v * 8 + i], packed.vertices.getFloat(at + i * 4));
                }
                int colour = SMALL_COLOURS[v];
                assertEquals((byte) colour, packed.vertices.get(at + 32));
                assertEquals((byte) (colour >> 8), packed.vertices.get(at + 33));
                assertEquals((byte) (colour >> 16), packed.vertices.get(at + 34));
                assertEquals(0, packed.vertices.get(at + 35));
            }
        }
    }

    /**
     * Issue #383 step 3a: the child builds one portal traversal per model that carries a set, from
     * the validated file tables, and keeps each mesh's group ordinal; nothing culls with them yet.
     */
    @Test
    void packedObjectsBuildATraversalPerPortalSet() throws Exception {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(2, 2, chain(2, 2, rgba(4, 10)));
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(new float[24]),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 0});
        builder.model();
        builder.portals(twoRooms());
        triangle(builder, 1);
        builder.placement(0, 0, 0, 0, 1, 1, 0);
        builder.placement(1, 0, 0, 0, 1, 1, 0);
        try (var packed = WorldStreamPackedObjects.pack(data(builder.build()), () -> false)) {
            assertNull(packed.portals(0));
            assertNotNull(packed.portals(1));
            assertEquals(2, packed.portals(1).groupCount());
            assertEquals(1, packed.portalSetCount());
            assertArrayEquals(new int[] {-1, 1}, packed.meshGroups);
        }
        try (var packed = WorldStreamPackedObjects.pack(data(small()), () -> false)) {
            assertEquals(0, packed.portalSetCount());
            assertNull(packed.portals(0));
            assertArrayEquals(new int[] {-1}, packed.meshGroups);
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
            var frame = BgfxWorldStreamRenderer.ObjectFrame.on(stack, false);
            // Instance rows go to bgfx's transient instance data (#382); a reused buffer stands in.
            var rows =
                    java.nio.ByteBuffer.allocateDirect(WorldObjectInstances.STRIDE)
                            .order(java.nio.ByteOrder.nativeOrder());
            long pointer = stack.getPointer();
            for (int pass = 0; pass < 2; pass++) {
                for (int p = 0; p < placements; p++) {
                    rows.clear();
                    WorldObjectInstances.put(table, p, origin, rows);
                    frame.material(p % 3);
                    assertEquals(pointer, stack.getPointer(), "placement " + p);
                }
            }
            assertEquals((float) p(table, placements - 1), rows.getFloat(12));
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
