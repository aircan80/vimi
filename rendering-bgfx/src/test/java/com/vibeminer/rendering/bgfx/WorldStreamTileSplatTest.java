package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The optional splatting trailer of a World tile packet (issue #174 step 2b). */
final class WorldStreamTileSplatTest {
    @TempDir Path directory;

    private Path path() {
        return directory.resolve("tile-42.wst");
    }

    static WorldStreamTileFile.Splat splat(int... layers) {
        return splatWithMask(com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK, layers);
    }

    /** A trailer with a mask of {@code maskSide} (512 or 1024) and these new layers. */
    static WorldStreamTileFile.Splat splatWithMask(int maskSide, int... layers) {
        byte[] table = new byte[WorldStreamTileFile.SPLAT_TABLE_BYTES];
        java.util.Arrays.fill(table, (byte) WorldStreamTileFile.SPLAT_NONE);
        for (int i = 0; i < layers.length; i++) {
            table[i] = (byte) layers[i];
        }
        byte[] alpha =
                new byte[com.vibeminer.rendering.world.TerrainTileSplat.alphaBytes(maskSide)];
        alpha[0] = 7;
        alpha[alpha.length - 1] = 9;
        byte[][] pixels = new byte[layers.length][WorldStreamTileFile.SPLAT_LAYER_BYTES];
        for (int i = 0; i < layers.length; i++) {
            pixels[i][0] = (byte) (i + 1);
        }
        return new WorldStreamTileFile.Splat(table, alpha, layers, pixels);
    }

    @Test
    void aWaterRemainderShorterThanItsHeaderIsRefused() {
        var tile = WorldStreamTileFileTest.full(false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamTileFile.readWater(
                                java.nio.ByteBuffer.allocate(64),
                                WorldStreamTileFileTest.header(tile, true),
                                tile,
                                () -> false));
    }

    @Test
    void flippedOrTruncatedSplatAndWaterTrailersAreRefused() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, true);
        var terrain =
                WorldStreamTileFile.write(
                        directory, header, tile, WorldStreamTileFileTest.atlas(), () -> false);
        var splat = splat(3, 200);
        var withSplat =
                WorldStreamTileFile.appendSplat(path(), terrain, header, splat, () -> false);
        var full =
                WorldStreamTileFile.appendWater(
                        path(),
                        withSplat,
                        header,
                        WorldStreamTileWaterTest.water(3, 0),
                        () -> false);
        byte[] clean = Files.readAllBytes(path());
        int start = terrain.length(), water = withSplat.length();
        // Any flipped trailer byte fails the digest of the replayed expectation.
        for (int offset :
                new int[] {
                    start,
                    start + 8,
                    start + WorldStreamTileFile.SPLAT_HEAD_BYTES,
                    water - 1,
                    water,
                    water + 40,
                    clean.length - 1
                }) {
            byte[] bad = clean.clone();
            bad[offset] ^= 0x10;
            Files.write(path(), bad);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamTileFile.read(path(), full, () -> false),
                    "Flipped byte " + offset);
        }
        // Flipped structure is refused even when the expectation is re-signed: the splat magic,
        // version and count, the water magic, version and geometry identity.
        for (int offset : new int[] {start, start + 8, start + 12, water, water + 8, water + 32}) {
            byte[] bad = clean.clone();
            bad[offset] ^= 0x10;
            Files.write(path(), bad);
            assertThrows(
                    IOException.class,
                    () ->
                            WorldStreamTileFile.read(
                                    path(), WorldStreamTileFileTest.signed(bad), () -> false),
                    "Re-signed flipped byte " + offset);
        }
        // Truncated inside either trailer: refused replayed and re-signed.
        for (int keep :
                new int[] {
                    start + 4,
                    start + WorldStreamTileFile.SPLAT_HEAD_BYTES,
                    water - 1,
                    water + 4,
                    water + 100,
                    clean.length - 1
                }) {
            byte[] cut = Arrays.copyOf(clean, keep);
            Files.write(path(), cut);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamTileFile.read(path(), full, () -> false),
                    "Truncated to " + keep);
            assertThrows(
                    IOException.class,
                    () ->
                            WorldStreamTileFile.read(
                                    path(), WorldStreamTileFileTest.signed(cut), () -> false),
                    "Re-signed truncation to " + keep);
        }
        Files.write(path(), clean);
        try (var payload = WorldStreamTileFile.read(path(), full, () -> false)) {
            assertEquals(3, payload.water().indices().length / 6);
        }
    }

    @Test
    void splatBeforeSlopedManyLayerWaterRoundTrips() throws Exception {
        // #463's water: corner heights differ per quad and a tile may carry many layers; the splat
        // trailer still comes first and both read back exactly from one packet.
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, true);
        var terrain =
                WorldStreamTileFile.write(
                        directory, header, tile, WorldStreamTileFileTest.atlas(), () -> false);
        var base = WorldStreamTileWaterTest.water(5, 0);
        float[] positions = base.positions().clone();
        for (int vertex = 0; vertex < positions.length / 3; vertex++) {
            positions[vertex * 3 + 2] = 11 + vertex * .25f;
        }
        var water = new WorldStreamTileFile.Water(positions, base.depths(), base.indices(), 5);
        var splat = splat(4, 201, 7);
        var withSplat =
                WorldStreamTileFile.appendSplat(path(), terrain, header, splat, () -> false);
        var full = WorldStreamTileFile.appendWater(path(), withSplat, header, water, () -> false);
        assertEquals(
                terrain.length() + splat.bytes() + WorldStreamTileFile.waterBytes(20, 30),
                full.length());
        try (var payload = WorldStreamTileFile.read(path(), full, () -> false)) {
            assertArrayEquals(new int[] {4, 201, 7}, payload.splat().layers());
            assertArrayEquals(positions, payload.water().positions());
            assertArrayEquals(base.indices(), payload.water().indices());
            assertEquals(5, payload.water().layers(), "one layer per quad, as #463 allows");
        }
    }

    @Test
    void splatAndWaterTrailersRoundTripInOrder() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, true);
        var terrain =
                WorldStreamTileFile.write(
                        directory, header, tile, WorldStreamTileFileTest.atlas(), () -> false);
        var splat = splat(3, 200);
        var withSplat =
                WorldStreamTileFile.appendSplat(path(), terrain, header, splat, () -> false);
        assertEquals(terrain.length() + splat.bytes(), withSplat.length());
        var full =
                WorldStreamTileFile.appendWater(
                        path(),
                        withSplat,
                        header,
                        WorldStreamTileWaterTest.water(3, 0),
                        () -> false);
        assertEquals(full.length(), Files.size(path()));
        try (var payload = WorldStreamTileFile.read(path(), full, () -> false)) {
            var read = payload.splat();
            assertArrayEquals(new int[] {3, 200}, read.layers());
            assertEquals(3, read.table()[0]);
            assertEquals((byte) 200, read.table()[1]);
            assertEquals((byte) WorldStreamTileFile.SPLAT_NONE, read.table()[2]);
            assertEquals(7, read.alpha()[0]);
            assertEquals(9, read.alpha()[read.alpha().length - 1]);
            assertEquals(2, read.pixels()[1][0]);
            assertEquals(3, payload.water().indices().length / 6);
        }
    }

    @Test
    void aHighMaskTrailerRoundTripsWithItsSideAndWaterAfterIt() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, true);
        var terrain =
                WorldStreamTileFile.write(
                        directory, header, tile, WorldStreamTileFileTest.atlas(), () -> false);
        int high = com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK;
        var splat = splatWithMask(high, 5);
        assertEquals(high, splat.maskSide());
        var withSplat =
                WorldStreamTileFile.appendSplat(path(), terrain, header, splat, () -> false);
        assertEquals(64 + 1024 + (4 << 20) + 4 + 43_704, splat.bytes());
        var full =
                WorldStreamTileFile.appendWater(
                        path(),
                        withSplat,
                        header,
                        WorldStreamTileWaterTest.water(2, 0),
                        () -> false);
        try (var payload = WorldStreamTileFile.read(path(), full, () -> false)) {
            var read = payload.splat();
            assertEquals(high, read.maskSide());
            assertEquals(4 << 20, read.alpha().length);
            assertEquals(9, read.alpha()[read.alpha().length - 1]);
            assertArrayEquals(new int[] {5}, read.layers());
            assertEquals(2, payload.water().indices().length / 6);
        }
        // Any other mask size is refused.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamTileFile.Splat(
                                new byte[WorldStreamTileFile.SPLAT_TABLE_BYTES],
                                new byte[768 * 768 * 4],
                                new int[0],
                                new byte[0][]));
    }

    @Test
    void aPacketWithoutSplatStillReadsAndHasNone() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var header = WorldStreamTileFileTest.header(tile, true);
        var terrain =
                WorldStreamTileFile.write(
                        directory, header, tile, WorldStreamTileFileTest.atlas(), () -> false);
        try (var payload = WorldStreamTileFile.read(path(), terrain, () -> false)) {
            assertNull(payload.splat());
        }
    }

    @Test
    void splatNeedsAnAtlasAndComesBeforeWater() throws Exception {
        var tile = WorldStreamTileFileTest.full(false);
        var height = WorldStreamTileFileTest.header(tile, false);
        var plain = WorldStreamTileFile.write(directory, height, tile, null, () -> false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamTileFile.appendSplat(
                                path(), plain, height, splat(1), () -> false));
        var withWater =
                WorldStreamTileFile.appendWater(
                        path(), plain, height, WorldStreamTileWaterTest.water(1, 0), () -> false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamTileFile.appendSplat(
                                path(), withWater, height, splat(1), () -> false));
    }

    @Test
    void splatShapeIsBounded() {
        int[] tooMany = new int[WorldStreamTileFile.MAX_SPLAT_LAYERS + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = i;
        }
        assertThrows(IllegalArgumentException.class, () -> splat(tooMany));
        assertThrows(IllegalArgumentException.class, () -> splat(4, 4), "Duplicate layer upload");
        assertThrows(
                IllegalArgumentException.class,
                () -> splat(WorldStreamTileFile.SPLAT_NONE),
                "The none marker is not an array layer");
        // The largest trailer: a HIGH (1024) mask and sixteen new 43,704-byte BC1 layers.
        assertEquals(
                64 + 1024 + (4 << 20) + 16 * (4 + 43_704), WorldStreamTileFile.MAX_SPLAT_BYTES);
    }
}
