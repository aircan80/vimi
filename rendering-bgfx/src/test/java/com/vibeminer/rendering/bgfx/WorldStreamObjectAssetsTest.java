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
        assertEquals(4 * (32 + 4) + 6 * 2, a.get(0).bytes(), "vertices, colours and u16 indices");
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
