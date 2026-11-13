package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Objects file v13: models and images named by asset digest, read back from an asset pack. */
class WorldStreamObjectsReferencesTest {
    @TempDir Path directory;

    private static final WorldStreamObjectsFile.Identity IDENTITY =
            new WorldStreamObjectsFile.Identity(
                    new WorldStreamState.Nonce(12, -34),
                    "ab".repeat(32),
                    42,
                    new WorldStreamState.Lease(9, 8, 7),
                    7,
                    19);

    /**
     * Two models: the first with a portal set, an animation entry and a liquid, the second plain;
     * two images, each drawn by one model; one placement each.
     */
    static WorldStreamObjectsFile.Data rich() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(
                2,
                2,
                WorldStreamObjectsFileTest.chain(2, 2, WorldStreamObjectsFileTest.rgba(4, 10)));
        builder.image(
                4,
                1,
                WorldStreamObjectsFileTest.chain(4, 1, WorldStreamObjectsFileTest.rgba(4, 20)));
        builder.model();
        builder.portals(WorldStreamObjectsFileTest.twoRooms());
        builder.mesh(
                FloatBuffer.wrap(quad(0)),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0},
                new int[1],
                IntBuffer.wrap(WorldStreamObjectsFileTest.SMALL_COLOURS),
                0);
        builder.liquid(
                WorldStreamTileWriter.Exterior.Builder.LIQUID_OCEAN,
                0,
                2,
                2,
                0,
                0,
                5,
                new float[] {5, 5, 5, 5},
                new boolean[] {true});
        builder.animate(0, new int[4], new byte[] {1, 2, 3});
        builder.model();
        builder.mesh(
                FloatBuffer.wrap(quad(30)),
                IntBuffer.wrap(new int[] {0, 1, 2}),
                new int[] {0, 3, 1});
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        builder.placement(1, 4, 5, 6, 1, 0, 1);
        return builder.build().data();
    }

    private static float[] quad(float x) {
        float[] vertices = new float[4 * 8];
        for (int v = 0; v < 4; v++) {
            vertices[v * 8] = x + v % 2 * 10;
            vertices[v * 8 + 1] = v / 2 * 10;
            vertices[v * 8 + 5] = 1;
            vertices[v * 8 + 6] = v % 2;
        }
        return vertices;
    }

    /** Everything a Data holds, flattened. */
    static String fingerprint(WorldStreamObjectsFile.Data d) {
        var out = new StringBuilder();
        for (Object a :
                new Object[] {
                    d.models,
                    d.meshes,
                    d.ranges,
                    d.placementModels,
                    d.placements,
                    d.images,
                    d.vertices,
                    d.colours,
                    d.indices,
                    d.meshGroups,
                    d.portalSets,
                    d.portalGroups,
                    d.portalBoxes,
                    d.portalVertexCounts,
                    d.portalPlanes,
                    d.portalVertices,
                    d.portalRefs,
                    d.placementParents,
                    d.placementGroups,
                    d.animations.models,
                    d.liquids.meshes,
                    d.liquids.kinds,
                    d.liquids.colours,
                    d.liquids.grids,
                    d.liquids.corners
                }) {
            out.append(
                            switch (a) {
                                case int[] v -> Arrays.toString(v);
                                case float[] v -> Arrays.toString(v);
                                case short[] v -> Arrays.toString(v);
                                case double[] v -> Arrays.toString(v);
                                default -> throw new AssertionError();
                            })
                    .append('\n');
        }
        out.append(Arrays.hashCode(d.pixels)).append('\n');
        for (int i = 0; i < d.animations.count(); i++) {
            out.append(Arrays.toString(d.animations.sources[i]))
                    .append(Arrays.toString(d.animations.animations[i]))
                    .append('\n');
        }
        for (int i = 0; i < d.liquids.count(); i++) {
            out.append(Arrays.toString(d.liquids.heights[i]))
                    .append(Arrays.toString(d.liquids.live[i]))
                    .append('\n');
        }
        out.append(d.compressedFormats).append(' ').append(d.draws);
        return out.toString();
    }

    /** References to {@code models} and {@code images} of {@code data}, with their payloads. */
    private record Named(
            WorldStreamObjectsFile.References references,
            List<WorldStreamObjectAssets.Payload> payloads) {}

    private static Named named(WorldStreamObjectsFile.Data data, int[] models, int[] images) {
        var payloads = new ArrayList<WorldStreamObjectAssets.Payload>();
        var md = new WorldStreamObjectAssets.Digest[models.length];
        for (int i = 0; i < models.length; i++) {
            var p = WorldStreamObjectAssets.modelPayload(data, models[i]);
            payloads.add(p);
            md[i] = p.digest();
        }
        var id = new WorldStreamObjectAssets.Digest[images.length];
        for (int i = 0; i < images.length; i++) {
            var p = WorldStreamObjectAssets.imagePayload(data, images[i]);
            payloads.add(p);
            id[i] = p.digest();
        }
        return new Named(new WorldStreamObjectsFile.References(models, md, images, id), payloads);
    }

    private WorldStreamObjectsFile.Data roundTrip(
            WorldStreamObjectsFile.Data data, int[] models, int[] images) throws IOException {
        var n = named(data, models, images);
        var objects =
                WorldStreamObjectsFile.write(
                        directory, IDENTITY, data, n.references(), () -> false);
        assertEquals(WorldStreamObjectsFile.fileLength(data, n.references()), objects.length());
        var packRef = WorldStreamAssetPack.write(directory, IDENTITY, n.payloads(), () -> false);
        var pack =
                WorldStreamAssetPack.read(
                        directory.resolve(WorldStreamAssetPack.basename(42)),
                        IDENTITY,
                        packRef,
                        () -> false);
        var read =
                WorldStreamObjectsFile.read(
                        directory.resolve(WorldStreamObjectsFile.basename(42)),
                        IDENTITY,
                        objects,
                        pack,
                        () -> false);
        Files.delete(directory.resolve(WorldStreamObjectsFile.basename(42)));
        Files.delete(directory.resolve(WorldStreamAssetPack.basename(42)));
        return read;
    }

    @Test
    void aFileNamingAssetsReadsBackExactlyTheSetItWasWrittenFrom() throws IOException {
        var data = rich();
        String expected = fingerprint(data);
        for (int[][] choice :
                new int[][][] {
                    {{}, {}},
                    {{0}, {}},
                    {{1}, {}},
                    {{}, {0}},
                    {{}, {1}},
                    {{0, 1}, {0, 1}},
                    {{0}, {1}}
                }) {
            assertEquals(
                    expected,
                    fingerprint(roundTrip(data, choice[0], choice[1])),
                    "models "
                            + Arrays.toString(choice[0])
                            + ", images "
                            + Arrays.toString(choice[1]));
        }
    }

    @Test
    void namingAssetsShortensTheFileByWhatTheyCarry() {
        var data = rich();
        var all = named(data, new int[] {0, 1}, new int[] {0, 1}).references();
        long full = data.bytes();
        long named = WorldStreamObjectsFile.fileLength(data, all);
        assertTrue(named < full, named + " < " + full);
        assertEquals(
                full,
                WorldStreamObjectsFile.fileLength(data, WorldStreamObjectsFile.References.NONE));
    }

    @Test
    void anInlineFileIsTheCurrentLayoutWithoutAReferenceSection() throws IOException {
        var data = rich();
        var reference = WorldStreamObjectsFile.write(directory, IDENTITY, data, () -> false);
        assertEquals(data.bytes(), reference.length());
        var bytes = Files.readAllBytes(directory.resolve(WorldStreamObjectsFile.basename(42)));
        var head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(WorldStreamObjectsFile.VERSION, head.getInt(8));
        assertEquals(0, head.getInt(200));
        assertEquals(0, head.getInt(204));
    }

    @Test
    void aFileNamingAssetsThePackLacksListsThem() throws IOException {
        var data = rich();
        var n = named(data, new int[] {1}, new int[] {0});
        var objects =
                WorldStreamObjectsFile.write(
                        directory, IDENTITY, data, n.references(), () -> false);
        var missing =
                assertThrows(
                        WorldStreamObjectsFile.MissingAssets.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        directory.resolve(WorldStreamObjectsFile.basename(42)),
                                        IDENTITY,
                                        objects,
                                        () -> false));
        assertEquals(
                List.of(n.references().modelDigests()[0], n.references().imageDigests()[0]),
                missing.missing);
    }

    @Test
    void aCorruptFileNamingAbsentAssetsIsRefusedAsCorrupt() throws IOException {
        var data = rich();
        var n = named(data, new int[] {1}, new int[] {0});
        var objects =
                WorldStreamObjectsFile.write(
                        directory, IDENTITY, data, n.references(), () -> false);
        var path = directory.resolve(WorldStreamObjectsFile.basename(42));
        var bytes = Files.readAllBytes(path);
        // A byte of the first placement's x: read as is, caught only by the file's digest.
        int at =
                WorldStreamObjectsFile.HEADER_BYTES
                        + n.references().bytes()
                        + data.modelCount() * 8
                        + data.meshCount() * 24
                        + data.rangeCount() * WorldStreamObjectsFile.RANGE_INTS * 4
                        + 8;
        bytes[at] ^= 1;
        Files.write(path, bytes);
        var refused =
                assertThrows(
                        IOException.class,
                        () -> WorldStreamObjectsFile.read(path, IDENTITY, objects, () -> false));
        assertEquals(IOException.class, refused.getClass(), "corrupt, not missing assets");
        assertEquals("Objects bytes or digest differ", refused.getMessage());
    }

    @Test
    void anAssetThatDoesNotFitTheTablesIsRefused() throws IOException {
        // Model 1 named by model 0's asset: its mesh table does not match.
        var data = rich();
        var zero = WorldStreamObjectAssets.modelPayload(data, 0);
        var refs =
                new WorldStreamObjectsFile.References(
                        new int[] {1},
                        new WorldStreamObjectAssets.Digest[] {zero.digest()},
                        new int[0],
                        new WorldStreamObjectAssets.Digest[0]);
        var objects = WorldStreamObjectsFile.write(directory, IDENTITY, data, refs, () -> false);
        var packRef = WorldStreamAssetPack.write(directory, IDENTITY, List.of(zero), () -> false);
        var pack =
                WorldStreamAssetPack.read(
                        directory.resolve(WorldStreamAssetPack.basename(42)),
                        IDENTITY,
                        packRef,
                        () -> false);
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                WorldStreamObjectsFile.read(
                                        directory.resolve(WorldStreamObjectsFile.basename(42)),
                                        IDENTITY,
                                        objects,
                                        pack,
                                        () -> false));
        assertInstanceOf(IllegalArgumentException.class, refused.getCause());
    }

    @Test
    void aPayloadDecodesToWhatItWasMadeFrom() {
        var data = rich();
        var model =
                WorldStreamObjectAssets.decodeModel(
                        WorldStreamObjectAssets.modelPayload(data, 0).bytes());
        assertEquals(1, model.meshes().size());
        assertEquals(4, model.meshes().get(0).vertexCount());
        assertEquals(2, model.portals().groups().length / WorldStreamObjectsFile.PORTAL_GROUP_INTS);
        assertArrayEquals(new byte[] {1, 2, 3}, model.animation());
        assertEquals(1, model.liquids().size());
        var image =
                WorldStreamObjectAssets.decodeImage(
                        WorldStreamObjectAssets.imagePayload(data, 1).bytes());
        assertEquals(4, image.width());
        assertEquals(1, image.height());
        // An asset's digest is its payload's.
        var payload = WorldStreamObjectAssets.modelPayload(data, 0);
        assertEquals(WorldStreamObjectAssets.digestOf(payload.bytes()), payload.digest());
        assertEquals(WorldStreamObjectAssets.of(data).get(0).digest(), payload.digest());
        assertEquals(WorldStreamObjectAssets.of(data).get(0).bytes(), payload.bytes().length);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamObjectAssets.decodeModel(Arrays.copyOf(payload.bytes(), 20)));
    }

    /** The pack's bytes with {@code change} applied. */
    private byte[] packBytes(
            List<WorldStreamObjectAssets.Payload> payloads,
            java.util.function.Consumer<ByteBuffer> change)
            throws IOException {
        WorldStreamAssetPack.write(directory, IDENTITY, payloads, () -> false);
        var path = directory.resolve(WorldStreamAssetPack.basename(42));
        var bytes = Files.readAllBytes(path);
        Files.delete(path);
        change.accept(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
        return bytes;
    }

    private String refusedPack(byte[] bytes) throws Exception {
        var path = directory.resolve(WorldStreamAssetPack.basename(42));
        Files.write(path, bytes);
        var sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        var reference =
                new WorldStreamObjectsFile.Reference(
                        bytes.length, java.util.HexFormat.of().formatHex(sha));
        try {
            var refused =
                    assertThrows(
                            IOException.class,
                            () ->
                                    WorldStreamAssetPack.read(
                                            path, IDENTITY, reference, () -> false));
            return refused.getCause() == null
                    ? refused.getMessage()
                    : refused.getCause().getMessage();
        } finally {
            Files.delete(path);
        }
    }

    @Test
    void aCorruptPackIsRefused() throws Exception {
        var data = rich();
        var payloads = List.of(WorldStreamObjectAssets.imagePayload(data, 0));
        // The first entry starts at 256: kind, digest (two longs), length, payload.
        assertEquals(
                "Asset pack digest",
                refusedPack(packBytes(payloads, b -> b.put(256 + 24 + 12, (byte) 7))));
        assertEquals("Asset pack digest", refusedPack(packBytes(payloads, b -> b.putLong(260, 1))));
        assertEquals(
                "Asset pack entry bounds", refusedPack(packBytes(payloads, b -> b.putInt(256, 5))));
        assertEquals("Asset pack header", refusedPack(packBytes(payloads, b -> b.putLong(24, 99))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamAssetPack.write(
                                directory,
                                IDENTITY,
                                List.of(payloads.get(0), payloads.get(0)),
                                () -> false));
        // The untouched pack reads.
        var ref = WorldStreamAssetPack.write(directory, IDENTITY, payloads, () -> false);
        var pack =
                WorldStreamAssetPack.read(
                        directory.resolve(WorldStreamAssetPack.basename(42)),
                        IDENTITY,
                        ref,
                        () -> false);
        assertEquals(1, pack.images().size());
    }
}
