package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        var digest = new Sink(header(tileX, tileY, 256, 256 * 145, indices));
        for (var chunk : grid.chunks()) {
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    var p = chunk.position(row, col);
                    digest.put(Float.floatToRawIntBits(p.x()));
                    digest.put(Float.floatToRawIntBits(p.y()));
                    digest.put(Float.floatToRawIntBits(p.z()));
                }
            }
        }
        for (var chunk : grid.chunks()) {
            int base = chunk.ordinal() * 145;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    if (!chunk.hole(row, col)) {
                        int a = base + row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                        digest.put(a);
                        digest.put(m);
                        digest.put(b);
                        digest.put(b);
                        digest.put(m);
                        digest.put(e);
                        digest.put(e);
                        digest.put(m);
                        digest.put(d);
                        digest.put(d);
                        digest.put(m);
                        digest.put(a);
                    }
                }
            }
        }
        return digest.hex();
    }

    /** Computed once per immutable tile; later calls return the remembered value. */
    public static String sha256(TerrainScene.Tile tile) {
        Objects.requireNonNull(tile);
        String cached = tile.cachedFingerprint();
        if (cached != null) {
            return cached;
        }
        var digest =
                new Sink(
                        header(
                                tile.x(),
                                tile.y(),
                                tile.chunks(),
                                tile.vertexCount(),
                                tile.indexCount()));
        var vertices = tile.vertices();
        while (vertices.hasRemaining()) {
            digest.put(Float.floatToRawIntBits(vertices.get()));
        }
        var indices = tile.indices();
        while (indices.hasRemaining()) {
            digest.put(indices.get());
        }
        String result = digest.hex();
        tile.cacheFingerprint(result);
        return result;
    }

    /** Little-endian ints into the digest in 16 KiB blocks; same bytes as one int at a time. */
    private static final class Sink {
        private final MessageDigest digest;
        private final ByteBuffer block =
                ByteBuffer.allocate(16 << 10).order(ByteOrder.LITTLE_ENDIAN);

        Sink(MessageDigest digest) {
            this.digest = digest;
        }

        void put(int value) {
            if (!block.hasRemaining()) {
                flush();
            }
            block.putInt(value);
        }

        private void flush() {
            digest.update(block.array(), 0, block.position());
            block.clear();
        }

        String hex() {
            flush();
            return HexFormat.of().formatHex(digest.digest());
        }
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
