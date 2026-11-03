package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Exact native geometry binding, independent of source receipts and camera/origin rebasing. */
public final class TerrainGeometryFingerprint {
    public static final String REVISION = "native-terrain-geometry-v1";
    private static final byte[] DOMAIN =
            "VIMI_TERRAIN_GEOMETRY_V1\0".getBytes(StandardCharsets.US_ASCII);

    private TerrainGeometryFingerprint() {}

    public static String sha256(int tileX, int tileY, TerrainGrid grid) {
        Objects.requireNonNull(grid);
        if (grid.chunks().size() != 256) {
            throw new IllegalArgumentException("Geometry binding requires full canonical grid");
        }
        int indices = 0;
        for (var chunk : grid.chunks()) {
            if (chunk.ordinal() != chunk.y() * 16 + chunk.x()) {
                throw new IllegalArgumentException("Noncanonical source grid order");
            }
            indices += (64 - Long.bitCount(chunk.holes())) * 12;
        }
        MessageDigest digest = header(tileX, tileY, 256, 256 * 145, indices);
        for (var chunk : grid.chunks()) {
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    var p = chunk.position(row, col);
                    put(digest, Float.floatToRawIntBits(p.x()));
                    put(digest, Float.floatToRawIntBits(p.y()));
                    put(digest, Float.floatToRawIntBits(p.z()));
                }
            }
        }
        for (var chunk : grid.chunks()) {
            int base = chunk.ordinal() * 145;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    if (!chunk.hole(row, col)) {
                        int a = base + row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                        put(digest, a);
                        put(digest, m);
                        put(digest, b);
                        put(digest, b);
                        put(digest, m);
                        put(digest, e);
                        put(digest, e);
                        put(digest, m);
                        put(digest, d);
                        put(digest, d);
                        put(digest, m);
                        put(digest, a);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(TerrainScene.Tile tile) {
        Objects.requireNonNull(tile);
        MessageDigest digest =
                header(tile.x(), tile.y(), tile.chunks(), tile.vertexCount(), tile.indexCount());
        var vertices = tile.vertices();
        while (vertices.hasRemaining()) {
            put(digest, Float.floatToRawIntBits(vertices.get()));
        }
        var indices = tile.indices();
        while (indices.hasRemaining()) {
            put(digest, indices.get());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest header(int x, int y, int chunks, int vertices, int indices) {
        if (x < 0 || x >= 64 || y < 0 || y >= 64) {
            throw new IllegalArgumentException("Invalid geometry tile coordinates");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            put(digest, x);
            put(digest, y);
            put(digest, chunks);
            put(digest, vertices);
            put(digest, indices);
            return digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Required SHA-256 unavailable", impossible);
        }
    }

    private static void put(MessageDigest digest, int value) {
        digest.update((byte) value);
        digest.update((byte) (value >>> 8));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 24));
    }
}
