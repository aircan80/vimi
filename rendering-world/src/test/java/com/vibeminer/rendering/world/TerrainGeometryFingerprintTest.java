package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class TerrainGeometryFingerprintTest {
    private static byte[] root() {
        int chunkBytes = 8 + 128 + 8 + 580;
        var bytes = ByteBuffer.allocate(12 + 256 * chunkBytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x4d564552).putInt(4).putInt(18);
        for (int i = 0; i < 256; i++) {
            bytes.putInt(0x4d434e4b).putInt(chunkBytes - 8);
            int start = bytes.position();
            int x = i % 16, y = i / 16;
            boolean high = i % 2 == 0;
            bytes.putInt(start, high ? 0x10000 : 0);
            bytes.putInt(start + 4, x).putInt(start + 8, y);
            bytes.putLong(start + 20, high && i % 3 == 0 ? 1L << (i % 64) : 0);
            bytes.putShort(start + 60, (short) (!high && i % 5 == 0 ? 1 << (i % 16) : 0));
            bytes.putFloat(start + 104, (float) (123.125 - y * WorldFormats.TILE_SIZE / 16));
            bytes.putFloat(start + 108, (float) (-800.25 - x * WorldFormats.TILE_SIZE / 16));
            bytes.putFloat(start + 112, 50.25f);
            bytes.position(start + 128);
            bytes.putInt(0x4d435654).putInt(580);
            for (int sample = 0; sample < 145; sample++) {
                bytes.putFloat(i / 8f + sample / 16f);
            }
        }
        return bytes.array();
    }

    @Test
    void streamedGridMatchesUnchangedLegacyGeometryIncludingBothHoleFormats() {
        byte[] source = root();
        var grid = WorldFormats.adtGrid(source, () -> false);
        var tile = new TerrainScene.Tile(7, 19, WorldFormats.adt(source, () -> false));
        String expected = TerrainGeometryFingerprint.sha256(tile);
        assertEquals(expected, TerrainGeometryFingerprint.sha256(7, 19, grid));
        assertNotEquals(expected, TerrainGeometryFingerprint.sha256(19, 7, grid));
        byte[] changed = source.clone();
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putLong(12 + 8 + 20, 3);
        assertNotEquals(
                expected,
                TerrainGeometryFingerprint.sha256(
                        7, 19, WorldFormats.adtGrid(changed, () -> false)));
        float[] vertices = WorldFormats.adt(source, () -> false).vertices();
        int[] indices = WorldFormats.adt(source, () -> false).triangles();
        vertices[0] += .125f;
        assertNotEquals(
                expected,
                TerrainGeometryFingerprint.sha256(
                        TerrainScene.Tile.copyOf(7, 19, vertices, indices, 256)));
        vertices[0] -= .125f;
        int original = indices[0];
        indices[0] = indices[1];
        indices[1] = original;
        assertNotEquals(
                expected,
                TerrainGeometryFingerprint.sha256(
                        TerrainScene.Tile.copyOf(7, 19, vertices, indices, 256)));
    }

    @Test
    void canonicalWireHasIndependentHardcodedDigestAndPreservesSignedZero() {
        float[] vertices = {-0f, 1.25f, -2.5f, 3.5f, 4.25f, 5.125f, -6.25f, 7f, 8.5f};
        int[] indices = {0, 1, 2};
        // Independent Python hashlib/struct oracle: domain + <5i header + <9f + <3I, 93 bytes.
        String expected = "d2acae5fb63cc590825541ff01e6c177ae0c8d289dd4c401749c0a33e6270e71";
        assertEquals(
                expected,
                TerrainGeometryFingerprint.sha256(
                        TerrainScene.Tile.copyOf(7, 19, vertices, indices, 1)));
        vertices[0] = +0f;
        assertNotEquals(
                expected,
                TerrainGeometryFingerprint.sha256(
                        TerrainScene.Tile.copyOf(7, 19, vertices, indices, 1)));
    }

    @Test
    void noncanonicalOrPartialGridCannotCertifyInitialSourceProfile() {
        byte[] source = root();
        var bytes = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(12 + 8 + 4, 1);
        bytes.putInt(12 + (8 + 128 + 8 + 580) + 8 + 4, 0);
        var noncanonical = WorldFormats.adtGrid(source, () -> false);
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainGeometryFingerprint.sha256(7, 19, noncanonical));
        var canonical = WorldFormats.adtGrid(root(), () -> false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainGeometryFingerprint.sha256(
                                7, 19, new TerrainGrid(canonical.chunks().subList(0, 255))));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainGeometryFingerprint.sha256(64, 19, canonical));
    }

    /** The digest one little-endian int at a time, as before block hashing. */
    private static String reference(TerrainScene.Tile tile) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(
                "VIMI_TERRAIN_GEOMETRY_V1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var ints = new java.util.ArrayList<Integer>();
        ints.addAll(
                java.util.List.of(
                        tile.x(), tile.y(), tile.chunks(), tile.vertexCount(), tile.indexCount()));
        var vertices = tile.vertices();
        while (vertices.hasRemaining()) {
            ints.add(Float.floatToRawIntBits(vertices.get()));
        }
        var indices = tile.indices();
        while (indices.hasRemaining()) {
            ints.add(indices.get());
        }
        for (int value : ints) {
            digest.update((byte) value);
            digest.update((byte) (value >>> 8));
            digest.update((byte) (value >>> 16));
            digest.update((byte) (value >>> 24));
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void blockHashingMatchesTheIntAtATimeDigestAndIsRememberedPerTile() throws Exception {
        byte[] source = root();
        var parsed = WorldFormats.adtTileGrid(source, () -> false);
        var tile = new TerrainScene.Tile(7, 19, parsed.terrain());
        String expected = reference(tile);
        assertEquals(expected, TerrainGeometryFingerprint.sha256(tile));
        assertEquals(expected, TerrainGeometryFingerprint.sha256(tile));
        assertEquals(expected, TerrainGeometryFingerprint.sha256(7, 19, parsed.grid()));
        // One walk gives the same geometry and grid as the separate parses.
        var legacy = WorldFormats.adt(source, () -> false);
        assertEquals(
                java.util.Arrays.toString(legacy.vertices()),
                java.util.Arrays.toString(parsed.terrain().vertices()));
        assertEquals(
                java.util.Arrays.toString(legacy.triangles()),
                java.util.Arrays.toString(parsed.terrain().triangles()));
        assertEquals(
                TerrainGeometryFingerprint.sha256(7, 19, WorldFormats.adtGrid(source, () -> false)),
                TerrainGeometryFingerprint.sha256(7, 19, parsed.grid()));
        assertEquals(
                parsed.areas().chunks().size(),
                WorldFormats.adtAreas(source, () -> false).chunks().size());
        // An equal copy hashes the same; the views are read-only.
        var copy = TerrainScene.Tile.copyOf(7, 19, legacy.vertices(), legacy.triangles(), 256);
        assertEquals(expected, TerrainGeometryFingerprint.sha256(copy));
        assertThrows(
                java.nio.ReadOnlyBufferException.class,
                () -> parsed.terrain().vertexView().put(0, 1f));
        assertThrows(
                java.nio.ReadOnlyBufferException.class,
                () -> parsed.terrain().triangleView().put(0, 1));
        float[] caller = legacy.vertices();
        var owned = new WorldFormats.Terrain(caller, legacy.triangles(), 256);
        caller[0] += 1;
        assertEquals(legacy.vertices()[0], owned.vertexView().get(0));
    }
}
