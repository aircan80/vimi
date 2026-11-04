package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Private immutable terrain IPC snapshot. File bounds are separate from resident renderer budgets.
 */
public final class GpuTerrainSceneFile {
    public static final int MAX_BYTES = 16 << 20;
    public static final int HEADER_BYTES = 64;
    public static final int TILE_HEADER_BYTES = 32;
    public static final long MAX_VALID_BYTES =
            HEADER_BYTES
                    + 9L * TILE_HEADER_BYTES
                    + 9L
                            * (TerrainScene.MAX_VERTICES_PER_TILE * 12L
                                    + TerrainScene.MAX_INDICES_PER_TILE * 4L);
    private static final byte[] MAGIC = {'V', 'I', 'M', 'I', 'T', 'R', 'N', '1'};
    private static final int VERSION = 1;

    private GpuTerrainSceneFile() {}

    /** Metadata from a validated read or owned write; never retains another geometry copy. */
    public static final class FileDigest {
        private final long length;
        private final byte[] sha;
        private final java.util.List<String> tiles;

        private FileDigest(long length, byte[] sha, java.util.List<String> tiles) {
            this.length = length;
            this.sha = sha.clone();
            this.tiles = java.util.List.copyOf(tiles);
        }

        public long length() {
            return length;
        }

        public byte[] sha256() {
            return sha.clone();
        }

        public String sha256Hex() {
            return java.util.HexFormat.of().formatHex(sha);
        }

        String tileFingerprint(int ordinal) {
            return tiles.get(ordinal);
        }

        void requireScene(TerrainScene scene, BooleanSupplier canceled) throws IOException {
            if (!tiles.equals(fingerprints(scene, canceled))) {
                throw new IOException("Geometry digest belongs to a different immutable scene");
            }
        }
    }

    /** Full-file identity was calculated from the same bytes used to decode this scene. */
    public static final class Decoded {
        private final TerrainScene scene;
        private final FileDigest file;

        private Decoded(TerrainScene scene, FileDigest file) {
            this.scene = scene;
            this.file = file;
        }

        public TerrainScene scene() {
            return scene;
        }

        public FileDigest file() {
            return file;
        }
    }

    private record ReadResult(TerrainScene scene, FileDigest file) {}

    private static java.util.List<String> fingerprints(
            TerrainScene scene, BooleanSupplier canceled) {
        var hashes = new ArrayList<String>(scene.tiles().size());
        for (var tile : scene.tiles()) {
            check(canceled);
            hashes.add(TerrainGeometryFingerprint.sha256(tile));
        }
        check(canceled);
        return hashes;
    }

    public static FileDigest writeBound(
            Path destination, TerrainScene scene, BooleanSupplier canceled) throws IOException {
        return writeCore(destination, scene, canceled, true);
    }

    public static Decoded readBound(Path source, BooleanSupplier canceled) throws IOException {
        var result = readCore(source, canceled, true);
        return new Decoded(result.scene(), result.file());
    }

    public static void write(Path destination, TerrainScene scene) throws IOException {
        write(destination, scene, () -> false);
    }

    public static void write(Path destination, TerrainScene scene, BooleanSupplier canceled)
            throws IOException {
        writeCore(destination, scene, canceled, false);
    }

    private static FileDigest writeCore(
            Path destination, TerrainScene scene, BooleanSupplier canceled, boolean bound)
            throws IOException {
        java.util.Objects.requireNonNull(scene);
        java.util.Objects.requireNonNull(canceled);
        check(canceled);
        long bytes =
                HEADER_BYTES
                        + (long) scene.tiles().size() * TILE_HEADER_BYTES
                        + scene.payloadBytes();
        if (bytes > MAX_BYTES) {
            throw new IOException("Terrain snapshot exceeds file cap");
        }
        boolean owned = false;
        try (var channel =
                FileChannel.open(
                        destination,
                        bound
                                ? java.util.Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        StandardOpenOption.READ)
                                : java.util.Set.of(
                                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            owned = true;
            var sha = digest();
            var hashing =
                    new DigestOutputStream(
                            new BufferedOutputStream(Channels.newOutputStream(channel), 64 << 10),
                            sha);
            var output = new DataOutputStream(hashing);
            output.write(MAGIC);
            integer(output, VERSION);
            integer(output, scene.tiles().size());
            number(output, bytes);
            integer(output, 0);
            integer(output, 0);
            hashing.on(false);
            output.write(new byte[32]);
            hashing.on(true);
            for (var tile : scene.tiles()) {
                check(canceled);
                integer(output, tile.x());
                integer(output, tile.y());
                integer(output, tile.vertexCount());
                integer(output, tile.indexCount());
                integer(output, tile.chunks());
                integer(output, 0);
                number(output, tile.payloadBytes());
            }
            for (var tile : scene.tiles()) {
                check(canceled);
                int at = 0;
                var vertices = tile.vertices();
                while (vertices.hasRemaining()) {
                    if ((at++ & 4095) == 0) {
                        check(canceled);
                    }
                    integer(output, Float.floatToRawIntBits(vertices.get()));
                }
                var indices = tile.indices();
                while (indices.hasRemaining()) {
                    if ((at++ & 4095) == 0) {
                        check(canceled);
                    }
                    integer(output, indices.get());
                }
            }
            check(canceled);
            output.flush();
            if (channel.size() != bytes) {
                throw new IOException("Terrain snapshot size changed");
            }
            channel.position(32);
            var checksum = ByteBuffer.wrap(sha.digest());
            while (checksum.hasRemaining()) {
                channel.write(checksum);
            }
            if (!bound) {
                return null;
            }
            channel.position(0);
            var full = digest();
            var buffer = ByteBuffer.allocate(64 << 10);
            long read = 0;
            while (read < bytes) {
                check(canceled);
                buffer.clear().limit((int) Math.min(buffer.capacity(), bytes - read));
                int count = channel.read(buffer);
                if (count <= 0) {
                    throw new IOException("Bound geometry hash did not advance");
                }
                read += count;
                full.update(buffer.flip());
            }
            if (channel.size() != bytes) {
                throw new IOException("Bound geometry size changed");
            }
            return new FileDigest(bytes, full.digest(), fingerprints(scene, canceled));
        } catch (IOException | RuntimeException | Error failure) {
            if (owned) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    public static TerrainScene read(Path source) throws IOException {
        return read(source, () -> false);
    }

    public static TerrainScene read(Path source, BooleanSupplier canceled) throws IOException {
        return readCore(source, canceled, false).scene();
    }

    private static ReadResult readCore(Path source, BooleanSupplier canceled, boolean bound)
            throws IOException {
        java.util.Objects.requireNonNull(canceled);
        check(canceled);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Terrain snapshot must be a regular file");
        }
        try (var channel =
                FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < HEADER_BYTES + TILE_HEADER_BYTES || size > MAX_BYTES) {
                throw new IOException("Terrain snapshot length exceeds bounds");
            }
            var sha = digest();
            var full = bound ? digest() : null;
            java.io.InputStream raw =
                    new BufferedInputStream(Channels.newInputStream(channel), 64 << 10);
            if (bound) {
                raw = new DigestInputStream(raw, full);
            }
            var hashing = new DigestInputStream(raw, sha);
            var input = new DataInputStream(hashing);
            if (!Arrays.equals(input.readNBytes(8), MAGIC) || integer(input) != VERSION) {
                throw new IOException("Invalid terrain snapshot format");
            }
            int count = integer(input);
            long declared = number(input);
            int flags = integer(input), reserved = integer(input);
            if (count < 1
                    || count > TerrainScene.MAX_TILES
                    || declared != size
                    || flags != 0
                    || reserved != 0) {
                throw new IOException("Invalid terrain snapshot header");
            }
            hashing.on(false);
            byte[] expected = input.readNBytes(32);
            hashing.on(true);
            if (expected.length != 32) {
                throw new IOException("Truncated terrain checksum");
            }
            var declarations = new ArrayList<Declaration>(count);
            long calculated = HEADER_BYTES + (long) count * TILE_HEADER_BYTES;
            boolean[] seen = new boolean[4096];
            for (int at = 0; at < count; at++) {
                check(canceled);
                int x = integer(input),
                        y = integer(input),
                        vertices = integer(input),
                        indices = integer(input);
                int chunks = integer(input), padding = integer(input);
                long payload = number(input);
                if (x < 0
                        || x > 63
                        || y < 0
                        || y > 63
                        || vertices < 1
                        || vertices > TerrainScene.MAX_VERTICES_PER_TILE
                        || indices < 0
                        || indices > TerrainScene.MAX_INDICES_PER_TILE
                        || indices % 3 != 0
                        || chunks < 1
                        || chunks > 256
                        || padding != 0
                        || payload != vertices * 12L + indices * 4L
                        || seen[y * 64 + x]) {
                    throw new IOException("Invalid terrain tile declaration");
                }
                seen[y * 64 + x] = true;
                calculated = Math.addExact(calculated, payload);
                if (calculated > size || calculated > MAX_BYTES) {
                    throw new IOException("Terrain payload exceeds declared size");
                }
                declarations.add(new Declaration(x, y, vertices, indices, chunks));
            }
            if (calculated != size) {
                throw new IOException("Terrain snapshot has trailing or missing payload");
            }
            var tiles = new ArrayList<TerrainScene.Tile>(count);
            for (var declaration : declarations) {
                check(canceled);
                // Only one tile's raw arrays coexist with its defensive copy; no full-file byte
                // array.
                float[] vertices = new float[Math.multiplyExact(declaration.vertices, 3)];
                int[] indices = new int[declaration.indices];
                for (int at = 0; at < vertices.length; at++) {
                    if ((at & 4095) == 0) {
                        check(canceled);
                    }
                    vertices[at] = Float.intBitsToFloat(integer(input));
                }
                for (int at = 0; at < indices.length; at++) {
                    if ((at & 4095) == 0) {
                        check(canceled);
                    }
                    indices[at] = integer(input);
                }
                tiles.add(
                        TerrainScene.Tile.copyOf(
                                declaration.x,
                                declaration.y,
                                vertices,
                                indices,
                                declaration.chunks));
            }
            if (input.read() != -1 || !MessageDigest.isEqual(expected, sha.digest())) {
                throw new IOException("Terrain snapshot checksum or EOF differs");
            }
            var result = new TerrainScene(tiles);
            check(canceled);
            return new ReadResult(
                    result,
                    bound
                            ? new FileDigest(size, full.digest(), fingerprints(result, canceled))
                            : null);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid terrain snapshot geometry", invalid);
        }
    }

    private record Declaration(int x, int y, int vertices, int indices, int chunks) {}

    private static void integer(DataOutputStream output, int value) throws IOException {
        output.writeInt(Integer.reverseBytes(value));
    }

    private static int integer(DataInputStream input) throws IOException {
        return Integer.reverseBytes(input.readInt());
    }

    private static void number(DataOutputStream output, long value) throws IOException {
        output.writeLong(Long.reverseBytes(value));
    }

    private static long number(DataInputStream input) throws IOException {
        return Long.reverseBytes(input.readLong());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Terrain snapshot canceled");
        }
    }
}
