package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
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
        Sink digest = header(tileX, tileY, 256, 256 * 145, indices);
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
        return digest.hex();
    }

    /** Computed once per immutable tile; later calls return the remembered value. */
    public static String sha256(TerrainScene.Tile tile) {
        Objects.requireNonNull(tile);
        String cached = tile.cachedFingerprint();
        if (cached != null) {
            return cached;
        }
        Sink digest =
                header(tile.x(), tile.y(), tile.chunks(), tile.vertexCount(), tile.indexCount());
        digest.floats(tile.vertices());
        digest.ints(tile.indices());
        String result = digest.hex();
        tile.cacheFingerprint(result);
        return result;
    }

    private static Sink header(int x, int y, int chunks, int vertices, int indices) {
        if (x < 0 || x >= 64 || y < 0 || y >= 64) {
            throw new IllegalArgumentException("Invalid geometry tile coordinates");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            var sink = new Sink(digest);
            put(sink, x);
            put(sink, y);
            put(sink, chunks);
            put(sink, vertices);
            put(sink, indices);
            return sink;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Required SHA-256 unavailable", impossible);
        }
    }

    private static void put(Sink sink, int value) {
        if (!sink.buffer.hasRemaining()) {
            sink.drain();
        }
        sink.buffer.putInt(value);
    }

    /**
     * Little-endian words batched into one digest update per 8 KiB. The digested byte stream is
     * unchanged; only the per-byte update calls are gone.
     */
    private static final class Sink {
        private final MessageDigest digest;
        private final ByteBuffer buffer =
                ByteBuffer.allocate(8 << 10).order(ByteOrder.LITTLE_ENDIAN);

        private Sink(MessageDigest digest) {
            this.digest = digest;
        }

        private void drain() {
            digest.update(buffer.array(), 0, buffer.position());
            buffer.clear();
        }

        private void floats(FloatBuffer values) {
            while (values.hasRemaining()) {
                if (!buffer.hasRemaining()) {
                    drain();
                }
                int count = Math.min(values.remaining(), buffer.remaining() / 4);
                buffer.asFloatBuffer().put(values.slice(values.position(), count));
                values.position(values.position() + count);
                buffer.position(buffer.position() + count * 4);
            }
        }

        private void ints(IntBuffer values) {
            while (values.hasRemaining()) {
                if (!buffer.hasRemaining()) {
                    drain();
                }
                int count = Math.min(values.remaining(), buffer.remaining() / 4);
                buffer.asIntBuffer().put(values.slice(values.position(), count));
                values.position(values.position() + count);
                buffer.position(buffer.position() + count * 4);
            }
        }

        private String hex() {
            drain();
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
