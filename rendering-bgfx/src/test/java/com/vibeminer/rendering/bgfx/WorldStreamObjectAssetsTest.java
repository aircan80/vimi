package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shareable assets of an objects file and the per-session ledger (#380 stage 4). */
class WorldStreamObjectAssetsTest {
    private static WorldStreamObjectsFile.Data data(WorldStreamTileWriter.Exterior exterior) {
        return exterior.data();
    }

    /** One model of a 4-vertex quad with {@code firstX} as its first vertex's x and one image. */
    private static WorldStreamObjectsFile.Data quad(float firstX, int imageFill, int material) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(
                2,
                2,
                WorldStreamObjectsFileTest.chain(
                        2, 2, WorldStreamObjectsFileTest.rgba(4, imageFill)));
        builder.image(
                2,
                2,
                WorldStreamObjectsFileTest.chain(2, 2, WorldStreamObjectsFileTest.rgba(4, 99)));
        builder.model();
        float[] vertices = new float[4 * 8];
        for (int v = 0; v < 4; v++) {
            vertices[v * 8] = v % 2 * 10;
            vertices[v * 8 + 1] = v / 2 * 10;
            vertices[v * 8 + 5] = 1;
        }
        vertices[0] = firstX;
        builder.mesh(
                FloatBuffer.wrap(vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, material});
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        return data(builder.build());
    }

    @Test
    void equalGeometryAndChainsNameTheSameAssets() {
        var a = WorldStreamObjectAssets.of(quad(0, 10, 0));
        var b = WorldStreamObjectAssets.of(quad(0, 10, 0));
        assertEquals(a, b);
        assertEquals(3, a.size(), "one model, two images");
        assertEquals(WorldStreamObjectAssets.Kind.MODEL, a.get(0).kind());
        // Section tag and mesh count, the mesh's three counts, vertices, colours, u16 indices.
        assertEquals(8 + 12 + 4 * (32 + 4) + 6 * 2, a.get(0).bytes());
    }

    @Test
    void theGeometryDigestFollowsTheVerticesNotTheRangesImage() {
        var base = WorldStreamObjectAssets.of(quad(0, 10, 0));
        assertNotEquals(
                base.get(0).digest(), WorldStreamObjectAssets.of(quad(1, 10, 0)).get(0).digest());
        // The same geometry drawn with the tile's other image slot: the same model asset.
        assertEquals(
                base.get(0).digest(), WorldStreamObjectAssets.of(quad(0, 10, 1)).get(0).digest());
        // A different chain is a different image asset; the other image is unchanged.
        var other = WorldStreamObjectAssets.of(quad(0, 11, 0));
        assertNotEquals(base.get(1).digest(), other.get(1).digest());
        assertEquals(base.get(2).digest(), other.get(2).digest());
    }

    @Test
    void anImageAssetIsTheHelpersTextureIdentity() throws Exception {
        var data = quad(0, 10, 0);
        var assets = WorldStreamObjectAssets.of(data);
        try (var packed = WorldStreamPackedObjects.pack(data, () -> false)) {
            var key = packed.key(0);
            assertEquals(
                    new WorldStreamObjectAssets.Digest(key.high(), key.low()),
                    assets.get(1).digest());
        }
    }

    /** One model of {@link #quad}'s mesh, with the given extras of the same model. */
    private static WorldStreamObjectsFile.Data model(
            com.vibeminer.rendering.world.WmoPortalSet portals, byte[] animation, Float liquid) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(
                2,
                2,
                WorldStreamObjectsFileTest.chain(2, 2, WorldStreamObjectsFileTest.rgba(4, 10)));
        builder.model();
        if (portals != null) {
            builder.portals(portals);
        }
        float[] vertices = new float[4 * 8];
        for (int v = 0; v < 4; v++) {
            vertices[v * 8] = v % 2 * 10;
            vertices[v * 8 + 1] = v / 2 * 10;
            vertices[v * 8 + 5] = 1;
        }
        builder.mesh(
                FloatBuffer.wrap(vertices),
                IntBuffer.wrap(new int[] {0, 1, 2, 2, 1, 3}),
                new int[] {0, 6, 0},
                new int[1],
                IntBuffer.allocate(0),
                portals == null ? -1 : 0);
        if (liquid != null) {
            builder.liquid(
                    WorldStreamTileWriter.Exterior.Builder.LIQUID_OCEAN,
                    0,
                    2,
                    2,
                    0,
                    0,
                    0,
                    new float[] {liquid, liquid, liquid, liquid},
                    new boolean[] {true});
        }
        if (animation != null) {
            builder.animate(0, new int[4], animation);
        }
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        return builder.build().data();
    }

    private static WorldStreamObjectAssets.Digest modelDigest(WorldStreamObjectsFile.Data data) {
        return WorldStreamObjectAssets.of(data).get(0).digest();
    }

    @Test
    void portalsAnimationsAndLiquidsArePartOfAModelsIdentity() {
        var plain = modelDigest(model(null, null, null));
        var rooms = WorldStreamObjectsFileTest.twoRooms();
        var portals = modelDigest(model(rooms, null, null));
        assertNotEquals(plain, portals, "a portal set");
        // A different portal set: the same rooms with the door's side flipped.
        var flipped =
                new com.vibeminer.rendering.world.WmoPortalSet(
                        rooms.groups(),
                        rooms.portals(),
                        java.util.List.of(
                                new com.vibeminer.rendering.world.WmoPortalTraversal.Ref(0, 1, 1),
                                new com.vibeminer.rendering.world.WmoPortalTraversal.Ref(
                                        0, 0, -1)));
        assertNotEquals(portals, modelDigest(model(flipped, null, null)), "another portal set");
        var animated = modelDigest(model(null, new byte[] {1, 2, 3}, null));
        assertNotEquals(plain, animated, "an animation entry");
        assertNotEquals(animated, modelDigest(model(null, new byte[] {1, 2, 4}, null)));
        var wet = modelDigest(model(null, null, 5f));
        assertNotEquals(plain, wet, "a liquid");
        assertNotEquals(wet, modelDigest(model(null, null, 6f)), "another liquid height");
        assertEquals(wet, modelDigest(model(null, null, 5f)), "the same liquid");
    }

    /** One model of two meshes: {@code firstVertices} then 7 minus that, each indexed 0, 1, 2. */
    private static WorldStreamObjectsFile.Data split(int firstVertices) {
        var builder = WorldStreamTileWriter.Exterior.builder();
        builder.image(
                2,
                2,
                WorldStreamObjectsFileTest.chain(2, 2, WorldStreamObjectsFileTest.rgba(4, 10)));
        builder.model();
        for (int vertices : new int[] {firstVertices, 7 - firstVertices}) {
            builder.mesh(
                    FloatBuffer.wrap(new float[vertices * 8]),
                    IntBuffer.wrap(new int[] {0, 1, 2}),
                    new int[] {0, 3, 0});
        }
        builder.placement(0, 1, 2, 3, 1, 1, 0);
        return builder.build().data();
    }

    @Test
    void aMeshBoundaryMovingWithTheSameBytesIsAnotherModel() {
        var four = WorldStreamObjectAssets.of(split(4)).get(0);
        var two = WorldStreamObjectAssets.of(split(3)).get(0);
        assertEquals(four.bytes(), two.bytes(), "the same vertex and index bytes");
        assertNotEquals(four.digest(), two.digest());
    }

    @Test
    void nothingIsDigestedWhileStageTimingsAreOff() {
        boolean was = com.vibeminer.formats.world.WorldStageTimings.enabled();
        var ledger = new WorldStreamObjectAssets.Ledger();
        try {
            com.vibeminer.formats.world.WorldStageTimings.enable(false);
            assertEquals(null, WorldStreamObjectAssets.tallyIfMeasuring(ledger, quad(0, 10, 0)));
            assertEquals(0, ledger.distinct(WorldStreamObjectAssets.Kind.MODEL));
            com.vibeminer.formats.world.WorldStageTimings.enable(true);
            assertEquals(
                    1, WorldStreamObjectAssets.tallyIfMeasuring(ledger, quad(0, 10, 0)).models());
        } finally {
            com.vibeminer.formats.world.WorldStageTimings.enable(was);
        }
    }

    @Test
    void theLedgerCountsWhatEachFileCarriesAndWhatIsNewToTheSession() {
        var ledger = new WorldStreamObjectAssets.Ledger();
        var first = ledger.record(WorldStreamObjectAssets.of(quad(0, 10, 0)));
        assertEquals(1, first.newModels());
        assertEquals(2, first.newImages());
        assertEquals(first.modelBytes(), first.newModelBytes());
        // The same model with one new image: only that image is new.
        var second = ledger.record(WorldStreamObjectAssets.of(quad(0, 11, 0)));
        assertEquals(1, second.models());
        assertEquals(0, second.newModels());
        assertEquals(0, second.newModelBytes());
        assertEquals(1, second.newImages());
        assertEquals(second.imageBytes() / 2, second.newImageBytes());
        assertEquals(1, ledger.distinct(WorldStreamObjectAssets.Kind.MODEL));
        assertEquals(3, ledger.distinct(WorldStreamObjectAssets.Kind.IMAGE));
        assertEquals(
                new WorldStreamObjectAssets.Tally(0, 0, 0, 0, 0, 0, 0, 0),
                ledger.record(List.of()));
    }
}
