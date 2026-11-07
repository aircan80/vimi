package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Optional immutable atlas IPC. No source lookup, lease ownership, GPU upload or fallback policy.
 */
public final class GpuTerrainTextureAnnex {
    public static final int MAX_BYTES = 10 << 20;
    public static final int HEADER_BYTES = 128, DESCRIPTOR_BYTES = 128;
    public static final int MAX_VALID_BYTES =
            HEADER_BYTES + 9 * (DESCRIPTOR_BYTES + TerrainTextureBinding.PIXEL_BYTES);
    private static final byte[] MAGIC = {'V', 'I', 'M', 'I', 'A', 'T', 'X', '1'};

    private GpuTerrainTextureAnnex() {}

    /**
     * Bounded native fallback classification; contains no source paths or arbitrary protocol text.
     */
    public static final class Invalid extends IOException {
        private static final long serialVersionUID = 1L;
        private final TerrainTextureStatus.Code code;

        private Invalid(TerrainTextureStatus.Code code, String message) {
            super(message);
            this.code = code;
        }

        private Invalid(TerrainTextureStatus.Code code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public TerrainTextureStatus.Code code() {
            return code;
        }
    }

    /** Owned decoded immutable pixels; independent of parent source pool claims. */
    public static final class Decoded {
        private final List<TerrainTextureBinding> bindings;

        private Decoded(List<TerrainTextureBinding> bindings) {
            this.bindings = List.copyOf(bindings);
        }

        public List<TerrainTextureBinding> bindings() {
            return bindings;
        }
    }

    private record Declaration(TerrainTextureBinding.Metadata metadata, long offset) {}

    /** Caller pins SAME qualified atlas borrows for the entire synchronous write. */
    public static void write(
            Path destination,
            GpuTerrainSceneFile.FileDigest geometry,
            TerrainScene scene,
            List<TerrainTextureBinding> borrowed,
            BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(scene);
        Objects.requireNonNull(borrowed);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (borrowed.isEmpty() || borrowed.size() > 9) {
            throw new IllegalArgumentException("Annex requires 1..9 borrowed atlases");
        }
        var bindings = List.copyOf(borrowed);
        geometry.requireScene(scene, canceled);
        int previous = -1;
        for (var binding : bindings) {
            check(canceled);
            var meta = binding.metadata();
            int ordinal = meta.tileOrdinal();
            if (ordinal <= previous
                    || ordinal >= scene.tiles().size()
                    || !geometry.tileFingerprint(ordinal).equals(meta.rootGeometrySha256())) {
                throw new IllegalArgumentException("Texture binding order or geometry differs");
            }
            TerrainTextureBinding.floatUvError(
                    meta, scene.tiles().get(ordinal), scene.origin(), canceled);
            TerrainTextureBinding.validatePixels(binding, canceled);
            previous = ordinal;
        }
        long size =
                HEADER_BYTES
                        + (long) bindings.size()
                                * (DESCRIPTOR_BYTES + TerrainTextureBinding.PIXEL_BYTES);
        boolean owned = false;
        try (var channel =
                FileChannel.open(
                        destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            owned = true;
            var sha = digest();
            var hashing =
                    new DigestOutputStream(
                            new BufferedOutputStream(Channels.newOutputStream(channel), 64 << 10),
                            sha);
            var output = new DataOutputStream(hashing);
            output.write(MAGIC);
            integer(output, 1);
            integer(output, 0);
            number(output, size);
            output.write(geometry.sha256());
            hashing.on(false);
            output.write(new byte[32]);
            hashing.on(true);
            integer(output, 1);
            integer(output, bindings.size());
            integer(output, DESCRIPTOR_BYTES);
            integer(output, 512);
            integer(output, 1);
            integer(output, 1);
            output.write(new byte[16]);
            long offset = HEADER_BYTES + (long) bindings.size() * DESCRIPTOR_BYTES;
            for (var binding : bindings) {
                check(canceled);
                var meta = binding.metadata();
                integer(output, meta.tileOrdinal());
                integer(output, meta.tileX());
                integer(output, meta.tileY());
                integer(output, 0);
                integer(output, Float.floatToRawIntBits(meta.nativeMaxX()));
                integer(output, Float.floatToRawIntBits(meta.nativeMaxY()));
                integer(output, 1600);
                integer(output, 3);
                var coverage = meta.rectangleCoverage();
                while (coverage.hasRemaining()) {
                    number(output, coverage.get());
                }
                number(output, offset);
                number(output, TerrainTextureBinding.PIXEL_BYTES);
                output.write(HexFormat.of().parseHex(meta.sources().rootContentKey()));
                output.write(HexFormat.of().parseHex(meta.sources().tex0ContentKey()));
                integer(output, (int) meta.sources().rootFileDataId());
                integer(output, (int) meta.sources().tex0FileDataId());
                // The descriptor's last field: rectangles with nothing to draw (no layers).
                number(output, meta.emptyRectangles());
                offset += TerrainTextureBinding.PIXEL_BYTES;
            }
            byte[] buffer = new byte[64 << 10];
            for (var binding : bindings) {
                var pixels = binding.pixels();
                while (pixels.hasRemaining()) {
                    check(canceled);
                    int count = Math.min(buffer.length, pixels.remaining());
                    pixels.get(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            check(canceled);
            output.flush();
            if (channel.size() != size) {
                throw new IOException("Annex snapshot size changed");
            }
            channel.position(56);
            var checksum = ByteBuffer.wrap(sha.digest());
            while (checksum.hasRemaining()) {
                channel.write(checksum);
            }
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

    /** Pair/hash/grid/alpha validation completes before returning any uploadable atlas. */
    public static Decoded read(
            Path source, GpuTerrainSceneFile.Decoded geometry, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Annex must be a regular file");
        }
        try (var channel =
                FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < HEADER_BYTES + DESCRIPTOR_BYTES + TerrainTextureBinding.PIXEL_BYTES
                    || size > MAX_BYTES) {
                throw new Invalid(TerrainTextureStatus.Code.BINDING, "Annex size outside bounds");
            }
            var sha = digest();
            var hashing =
                    new DigestInputStream(
                            new BufferedInputStream(Channels.newInputStream(channel), 64 << 10),
                            sha);
            var input = new DataInputStream(hashing);
            if (!Arrays.equals(input.readNBytes(8), MAGIC)
                    || integer(input) != 1
                    || integer(input) != 0
                    || number(input) != size) {
                throw new Invalid(TerrainTextureStatus.Code.BINDING, "Invalid annex header");
            }
            if (!MessageDigest.isEqual(input.readNBytes(32), geometry.file().sha256())) {
                throw new Invalid(
                        TerrainTextureStatus.Code.BINDING,
                        "Annex belongs to different geometry bytes");
            }
            hashing.on(false);
            byte[] expected = input.readNBytes(32);
            hashing.on(true);
            if (expected.length != 32 || integer(input) != 1) {
                throw new Invalid(
                        TerrainTextureStatus.Code.BINDING, "Invalid annex checksum/recipe");
            }
            int count = integer(input);
            if (count < 1
                    || count > 9
                    || count > geometry.scene().tiles().size()
                    || integer(input) != DESCRIPTOR_BYTES
                    || integer(input) != 512
                    || integer(input) != 1
                    || integer(input) != 1
                    || !Arrays.equals(input.readNBytes(16), new byte[16])
                    || size
                            != HEADER_BYTES
                                    + (long) count
                                            * (DESCRIPTOR_BYTES
                                                    + TerrainTextureBinding.PIXEL_BYTES)) {
                throw new Invalid(
                        TerrainTextureStatus.Code.BINDING, "Invalid annex declaration or length");
            }
            var declarations = new ArrayList<Declaration>(count);
            int previous = -1;
            for (int at = 0; at < count; at++) {
                check(canceled);
                int ordinal = integer(input),
                        x = integer(input),
                        y = integer(input),
                        flags = integer(input);
                float maxX = Float.intBitsToFloat(integer(input)),
                        maxY = Float.intBitsToFloat(integer(input));
                int numerator = integer(input), denominator = integer(input);
                long[] coverage = new long[4];
                for (int word = 0; word < 4; word++) {
                    coverage[word] = number(input);
                }
                long offset = number(input), length = number(input);
                byte[] root = input.readNBytes(16), tex0 = input.readNBytes(16);
                long rootId = Integer.toUnsignedLong(integer(input)),
                        tex0Id = Integer.toUnsignedLong(integer(input));
                long empty = number(input);
                long requiredOffset =
                        HEADER_BYTES
                                + (long) count * DESCRIPTOR_BYTES
                                + (long) at * TerrainTextureBinding.PIXEL_BYTES;
                if (ordinal <= previous
                        || ordinal >= geometry.scene().tiles().size()
                        || flags != 0
                        || numerator != 1600
                        || denominator != 3
                        || offset != requiredOffset
                        || length != TerrainTextureBinding.PIXEL_BYTES
                        || empty < 0
                        || empty > 255
                        || root.length != 16
                        || tex0.length != 16) {
                    throw new Invalid(
                            TerrainTextureStatus.Code.BINDING, "Invalid annex tile descriptor");
                }
                var tile = geometry.scene().tiles().get(ordinal);
                if (tile.x() != x || tile.y() != y) {
                    throw new Invalid(
                            TerrainTextureStatus.Code.BINDING,
                            "Annex tile key differs from geometry");
                }
                var sources =
                        new TerrainTextureBinding.Sources(
                                rootId,
                                HexFormat.of().formatHex(root),
                                tex0Id,
                                HexFormat.of().formatHex(tex0));
                var meta =
                        TerrainTextureBinding.fromSnapshot(
                                ordinal,
                                tile,
                                maxX,
                                maxY,
                                coverage,
                                (int) empty,
                                sources,
                                canceled);
                TerrainTextureBinding.floatUvError(meta, tile, geometry.scene().origin(), canceled);
                declarations.add(new Declaration(meta, offset));
                previous = ordinal;
            }
            var bindings = new ArrayList<TerrainTextureBinding>(count);
            for (var declaration : declarations) {
                check(canceled);
                byte[] pixels = new byte[TerrainTextureBinding.PIXEL_BYTES];
                for (int at = 0; at < pixels.length; at += 64 << 10) {
                    check(canceled);
                    input.readFully(pixels, at, Math.min(64 << 10, pixels.length - at));
                }
                // Owned parser array is transferred into an immutable view, never aggregate-cloned.
                var binding =
                        TerrainTextureBinding.borrow(
                                declaration.metadata(), ByteBuffer.wrap(pixels).asReadOnlyBuffer());
                TerrainTextureBinding.validatePixels(binding, canceled);
                bindings.add(binding);
            }
            if (input.read() != -1 || !MessageDigest.isEqual(expected, sha.digest())) {
                throw new Invalid(
                        TerrainTextureStatus.Code.CHECKSUM, "Annex checksum or EOF differs");
            }
            check(canceled);
            return new Decoded(bindings);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new Invalid(
                    TerrainTextureStatus.Code.MAPPING, "Invalid annex atlas/grid binding", invalid);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Terrain annex canceled");
        }
    }

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
}
