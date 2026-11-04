package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Structural packet IO only. Wire fields and hashes never authenticate an asset source. */
final class WorldStreamTileFile {
    static final int HEADER_BYTES = 4096, ATLAS_BYTES = 1_048_576;
    private static final int SCRATCH = 64 << 10;
    private static final byte[] MAGIC =
            "VIMIWST2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    enum Encoding {
        PALETTED,
        BC1,
        BC2,
        BC3,
        BGRA8
    }

    static boolean supported(Encoding encoding, int depth, int alpha) {
        if (encoding == null) {
            return false;
        }
        return switch (encoding) {
            case PALETTED ->
                    alpha == 0 && (depth == 0 || depth == 1 || depth == 4 || depth == 8)
                            || alpha == 8 && (depth == 0 || depth == 8);
            case BC1 -> alpha == 0 && (depth == 0 || depth == 1);
            case BC2 -> alpha == 1 && (depth == 4 || depth == 8);
            case BC3 -> alpha == 7 && (depth == 8 || depth == 72);
            case BGRA8 -> alpha == 0 && depth == 8 || alpha == 8 && depth == 1;
        };
    }

    static boolean mipFlags(int flags) {
        return flags == 0 || flags == 1 || flags == 16 || flags == 17;
    }

    enum Outcome {
        TEXTURED,
        HEIGHT_FALLBACK
    }

    enum Reason {
        NONE,
        TERMINAL_MATERIAL_UNAVAILABLE,
        QUALIFIED_BINDING_REJECTED
    }

    record Source(
            long id,
            String contentKey,
            long consumedLength,
            Long declaredLength,
            int sourceLimit,
            int originalSide,
            int mipLevel,
            int mipFlags,
            Encoding encoding,
            int alphaDepth,
            int alphaEncoding) {
        Source {
            require(
                    id > 0
                            && id <= 0xffff_ffffL
                            && hex(contentKey, 32)
                            && consumedLength >= 12
                            && consumedLength <= sourceLimit
                            && sourceLimit >= 12
                            && sourceLimit <= 8 << 20
                            && (declaredLength == null || declaredLength == consumedLength),
                    "Source tuple");
            if (encoding == null) {
                require(
                        (originalSide | mipLevel | mipFlags | alphaDepth | alphaEncoding) == 0,
                        "Structural source mip fields");
            } else {
                require(
                        mipLevel >= 0
                                && mipLevel <= 12
                                && originalSide == (4 << mipLevel)
                                && supported(encoding, alphaDepth, alphaEncoding)
                                && (WorldStreamTileFile.mipFlags(mipFlags)
                                        || mipFlags == 2
                                                && alphaDepth == 8
                                                && (encoding == Encoding.BC2 && alphaEncoding == 1
                                                        || encoding == Encoding.BC3
                                                                && alphaEncoding == 7)),
                        "Diffuse mip tuple");
            }
        }
    }

    record Header(
            WorldStreamState.Nonce nonce,
            String scope,
            long transaction,
            WorldStreamState.Lease lease,
            int x,
            int y,
            int chunks,
            int vertices,
            int indices,
            String geometrySha256,
            // Opaque presentation identity; never a successful composition-cache receipt.
            String materialDigest,
            Outcome outcome,
            Reason reason,
            long[] coverage,
            float maxX,
            float maxY,
            double uvError,
            Source wdt,
            Source root,
            Source tex0,
            List<Source> diffuse) {
        Header {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(diffuse);
            require(
                    hex(scope, 64)
                            && transaction > 0
                            && transaction < Long.MAX_VALUE
                            && x >= 0
                            && x < 64
                            && y >= 0
                            && y < 64
                            && chunks >= 1
                            && chunks <= 256
                            && vertices > 0
                            && vertices <= TerrainScene.MAX_VERTICES_PER_TILE
                            && indices >= 0
                            && indices <= TerrainScene.MAX_INDICES_PER_TILE
                            && indices % 3 == 0
                            && hex(geometrySha256, 64)
                            && coverage.length == 4
                            && diffuse.size() <= 64,
                    "Tile header bounds");
            coverage = coverage.clone();
            diffuse = List.copyOf(diffuse);
            structural(wdt, 3 << 20);
            structural(root, 8 << 20);
            if (tex0 != null) {
                structural(tex0, 8 << 20);
            }
            if (outcome == Outcome.TEXTURED) {
                require(
                        reason == Reason.NONE
                                && hex(materialDigest, 64)
                                && !materialDigest.equals("0".repeat(64))
                                && tex0 != null
                                && !diffuse.isEmpty()
                                && chunks == 256
                                && vertices == TerrainScene.MAX_VERTICES_PER_TILE
                                && (coverage[0] | coverage[1] | coverage[2] | coverage[3]) != 0
                                && Float.isFinite(maxX)
                                && Float.isFinite(maxY)
                                && Math.abs(maxX) <= 1_000_000
                                && Math.abs(maxY) <= 1_000_000
                                && Double.isFinite(uvError)
                                && uvError >= 0
                                && uvError <= TerrainTextureBinding.MAX_UV_ERROR_TEXELS,
                        "Textured header");
            } else {
                require(
                        reason != Reason.NONE
                                && materialDigest == null
                                && diffuse.isEmpty()
                                && (coverage[0] | coverage[1] | coverage[2] | coverage[3]) == 0
                                && Float.floatToRawIntBits(maxX) == 0
                                && Float.floatToRawIntBits(maxY) == 0
                                && Double.doubleToRawLongBits(uvError) == 0,
                        "Height fallback header");
            }
            var identities = new HashMap<Long, Source>();
            for (var source : new Source[] {wdt, root, tex0}) {
                if (source != null) {
                    identity(identities, source);
                }
            }
            var ids = new HashSet<Long>();
            for (var source : diffuse) {
                require(
                        source.encoding() != null
                                && source.sourceLimit() <= 1 << 20
                                && ids.add(source.id()),
                        "Diffuse source domain");
                identity(identities, source);
            }
        }

        @Override
        public long[] coverage() {
            return coverage.clone();
        }

        int geometryBytes() {
            return vertices * 12 + indices * 4;
        }

        int totalBytes() {
            return HEADER_BYTES + geometryBytes() + (outcome == Outcome.TEXTURED ? ATLAS_BYTES : 0);
        }
    }

    record Expected(
            WorldStreamState.Nonce nonce,
            String scope,
            long transaction,
            WorldStreamState.Lease lease,
            int length,
            String sha256) {
        Expected {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            require(
                    hex(scope, 64)
                            && transaction > 0
                            && transaction < Long.MAX_VALUE
                            && length >= HEADER_BYTES
                            && length <= WorldStreamEnvelope.MAX_PACKET_BYTES
                            && hex(sha256, 64),
                    "Packet expectation");
        }
    }

    static final class Payload implements AutoCloseable {
        private final Header header;
        private TerrainScene.Tile tile;
        private byte[] atlas;

        private Payload(Header header, TerrainScene.Tile tile, byte[] atlas) {
            this.header = header;
            this.tile = tile;
            this.atlas = atlas;
        }

        Header header() {
            return header;
        }

        TerrainScene.Tile tile() {
            if (tile == null) {
                throw new IllegalStateException("Decoded packet closed");
            }
            return tile;
        }

        ByteBuffer atlas() {
            tile();
            return atlas == null ? null : ByteBuffer.wrap(atlas).asReadOnlyBuffer();
        }

        @Override
        public void close() {
            tile = null;
            atlas = null;
        }
    }

    private WorldStreamTileFile() {}

    static Payload read(Path path, Expected expected, BooleanSupplier canceled) throws IOException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(canceled);
        check(canceled);
        basename(path, expected.transaction());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Packet is not a regular private file");
        }
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != expected.length()) {
                throw new IOException("Packet file size");
            }
            var digest = digest();
            var first = ByteBuffer.allocate(HEADER_BYTES);
            while (first.hasRemaining()) {
                check(canceled);
                if (channel.read(first) < 0) {
                    throw new IOException("Truncated packet header");
                }
            }
            digest.update(first.array());
            var header = decodeHeader(first.array(), expected);
            // Only after the complete fixed header/session/count checks may body buffers exist.
            var input =
                    new DataInputStream(
                            new DigestInputStream(
                                    new BufferedInputStream(
                                            Channels.newInputStream(channel), SCRATCH),
                                    digest));
            var tile = readGeometry(input, header, canceled);
            byte[] atlas = null;
            if (header.outcome() == Outcome.TEXTURED) {
                atlas = new byte[ATLAS_BYTES];
                for (int at = 0; at < atlas.length; at += SCRATCH) {
                    check(canceled);
                    input.readFully(atlas, at, Math.min(SCRATCH, atlas.length - at));
                }
                validateAtlas(header, tile, ByteBuffer.wrap(atlas).asReadOnlyBuffer(), canceled);
            }
            check(canceled);
            if (input.read() != -1
                    || channel.size() != expected.length()
                    || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw new IOException("Packet bytes or full digest differ");
            }
            return new Payload(header, tile, atlas);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid World tile packet", invalid);
        }
    }

    private static TerrainScene.Tile readGeometry(
            DataInputStream input, Header header, BooleanSupplier canceled) throws IOException {
        float[] vertices = new float[header.vertices() * 3];
        int[] indices = new int[header.indices()];
        for (int i = 0; i < vertices.length; i++) {
            if ((i & 1023) == 0) {
                check(canceled);
            }
            vertices[i] = Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
        }
        for (int i = 0; i < indices.length; i++) {
            if ((i & 1023) == 0) {
                check(canceled);
            }
            indices[i] = Integer.reverseBytes(input.readInt());
        }
        var tile =
                TerrainScene.Tile.copyOf(
                        header.x(), header.y(), vertices, indices, header.chunks());
        if (!TerrainGeometryFingerprint.sha256(tile).equals(header.geometrySha256())) {
            throw new IOException("Geometry fingerprint differs");
        }
        // Raw arrays cannot escape this lexical phase; exactly one defensive Tile copy remains.
        return tile;
    }

    static Expected write(
            Path directory,
            Header header,
            TerrainScene.Tile tile,
            ByteBuffer atlas,
            BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(canceled);
        Objects.requireNonNull(header);
        Objects.requireNonNull(tile);
        check(canceled);
        require(
                tile.x() == header.x()
                        && tile.y() == header.y()
                        && tile.chunks() == header.chunks()
                        && tile.vertexCount() == header.vertices()
                        && tile.indexCount() == header.indices()
                        && TerrainGeometryFingerprint.sha256(tile).equals(header.geometrySha256()),
                "Geometry/header mismatch");
        if (header.outcome() == Outcome.TEXTURED) {
            validateAtlas(header, tile, atlas, canceled);
        } else {
            require(atlas == null, "Height packet atlas");
        }
        Path target = directory.resolve("tile-" + header.transaction() + ".wst");
        Path part = directory.resolve("tile-" + header.transaction() + ".wst.part");
        var digest = digest();
        boolean created = false, published = false;
        try {
            try (var channel =
                    FileChannel.open(
                            part,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                var output =
                        new DataOutputStream(
                                new DigestOutputStream(
                                        new BufferedOutputStream(
                                                Channels.newOutputStream(channel), SCRATCH),
                                        digest));
                output.write(encodeHeader(header));
                var vertices = tile.vertices();
                for (int i = 0; vertices.hasRemaining(); i++) {
                    if ((i & 1023) == 0) {
                        check(canceled);
                    }
                    output.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertices.get())));
                }
                var indices = tile.indices();
                for (int i = 0; indices.hasRemaining(); i++) {
                    if ((i & 1023) == 0) {
                        check(canceled);
                    }
                    output.writeInt(Integer.reverseBytes(indices.get()));
                }
                if (atlas != null) {
                    var pixels = atlas.duplicate();
                    byte[] scratch = new byte[SCRATCH];
                    while (pixels.hasRemaining()) {
                        check(canceled);
                        int count = Math.min(scratch.length, pixels.remaining());
                        pixels.get(scratch, 0, count);
                        output.write(scratch, 0, count);
                    }
                }
                output.flush();
                channel.force(false);
            }
            check(canceled);
            // No replace-existing option. Private session owner serializes publication per tx.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Packet exists");
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new Expected(
                    header.nonce(),
                    header.scope(),
                    header.transaction(),
                    header.lease(),
                    header.totalBytes(),
                    HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (created && !published) {
                Files.deleteIfExists(part);
            }
        }
    }

    private static void validateAtlas(
            Header h, TerrainScene.Tile tile, ByteBuffer atlas, BooleanSupplier canceled) {
        require(
                atlas != null && atlas.isReadOnly() && atlas.remaining() == ATLAS_BYTES,
                "Atlas pixel ownership");
        var metadata =
                TerrainTextureBinding.fromSnapshot(
                        0,
                        tile,
                        h.maxX(),
                        h.maxY(),
                        h.coverage(),
                        new TerrainTextureBinding.Sources(
                                h.root().id(),
                                h.root().contentKey(),
                                h.tex0().id(),
                                h.tex0().contentKey()),
                        canceled);
        require(metadata.maxSourceUvErrorTexels() <= h.uvError(), "Understated source UV error");
        TerrainTextureBinding.validatePixels(
                TerrainTextureBinding.borrow(metadata, atlas), canceled);
    }

    static byte[] encodeHeader(Header h) {
        var out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putInt(2).putInt(HEADER_BYTES).putLong(h.totalBytes());
        out.putLong(h.nonce().high()).putLong(h.nonce().low());
        out.put(HexFormat.of().parseHex(h.scope())).putLong(h.transaction());
        out.putLong(h.lease().serial()).putInt(h.lease().slot()).putInt(h.x()).putInt(h.y());
        out.putInt(h.chunks()).putLong(h.lease().generation());
        out.putInt(h.vertices()).putInt(h.indices()).putInt(h.geometryBytes());
        out.putInt(h.outcome() == Outcome.TEXTURED ? ATLAS_BYTES : 0);
        out.put(HexFormat.of().parseHex(h.geometrySha256()));
        out.put(
                h.materialDigest() == null
                        ? new byte[32]
                        : HexFormat.of().parseHex(h.materialDigest()));
        out.putInt(h.outcome() == Outcome.TEXTURED ? 1 : 0).putInt(1);
        out.putInt(h.outcome() == Outcome.TEXTURED ? 1 : 2);
        out.putInt(
                switch (h.reason()) {
                    case NONE -> 0;
                    case TERMINAL_MATERIAL_UNAVAILABLE -> 1;
                    case QUALIFIED_BINDING_REJECTED -> 2;
                });
        for (long word : h.coverage()) {
            out.putLong(word);
        }
        out.putFloat(h.maxX()).putFloat(h.maxY()).putDouble(h.uvError()).putInt(h.diffuse().size());
        putSource(out, 512, h.wdt());
        putSource(out, 560, h.root());
        putSource(out, 608, h.tex0());
        for (int i = 0; i < h.diffuse().size(); i++) {
            putSource(out, 1024 + i * 48, h.diffuse().get(i));
        }
        return out.array();
    }

    private static Header decodeHeader(byte[] bytes, Expected expected) throws IOException {
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            require(
                    Arrays.equals(MAGIC, Arrays.copyOf(bytes, 8))
                            && in.getInt(8) == 2
                            && in.getInt(12) == HEADER_BYTES
                            && in.getLong(16) == expected.length(),
                    "Header magic/size");
            var nonce = new WorldStreamState.Nonce(in.getLong(24), in.getLong(32));
            String scope = HexFormat.of().formatHex(bytes, 40, 72);
            var lease = new WorldStreamState.Lease(in.getInt(88), in.getLong(104), in.getLong(80));
            require(
                    nonce.equals(expected.nonce())
                            && scope.equals(expected.scope())
                            && in.getLong(72) == expected.transaction()
                            && lease.equals(expected.lease()),
                    "Header expectation");
            zero(bytes, 260, 512);
            zero(bytes, 656, 1024);
            int count = in.getInt(256);
            require(count >= 0 && count <= 64, "Diffuse count");
            zero(bytes, 1024 + count * 48, HEADER_BYTES);
            var diffuse = new java.util.ArrayList<Source>(count);
            for (int i = 0; i < count; i++) {
                diffuse.add(getSource(in, 1024 + i * 48, false));
            }
            Outcome outcome =
                    switch (in.getInt(200)) {
                        case 1 -> Outcome.TEXTURED;
                        case 2 -> Outcome.HEIGHT_FALLBACK;
                        default -> throw new IllegalArgumentException("Outcome");
                    };
            Reason reason =
                    switch (in.getInt(204)) {
                        case 0 -> Reason.NONE;
                        case 1 -> Reason.TERMINAL_MATERIAL_UNAVAILABLE;
                        case 2 -> Reason.QUALIFIED_BINDING_REJECTED;
                        default -> throw new IllegalArgumentException("Fallback reason");
                    };
            var header =
                    new Header(
                            nonce,
                            scope,
                            in.getLong(72),
                            lease,
                            in.getInt(92),
                            in.getInt(96),
                            in.getInt(100),
                            in.getInt(112),
                            in.getInt(116),
                            HexFormat.of().formatHex(bytes, 128, 160),
                            outcome == Outcome.TEXTURED
                                    ? HexFormat.of().formatHex(bytes, 160, 192)
                                    : null,
                            outcome,
                            reason,
                            new long[] {
                                in.getLong(208), in.getLong(216), in.getLong(224), in.getLong(232)
                            },
                            in.getFloat(240),
                            in.getFloat(244),
                            in.getDouble(248),
                            getSource(in, 512, true),
                            getSource(in, 560, true),
                            getSource(in, 608, true),
                            diffuse);
            require(
                    in.getInt(120) == header.geometryBytes()
                            && header.totalBytes() == expected.length()
                            && in.getInt(124) == (outcome == Outcome.TEXTURED ? ATLAS_BYTES : 0)
                            && in.getInt(192) == (outcome == Outcome.TEXTURED ? 1 : 0)
                            && in.getInt(196) == 1,
                    "Header body/revision");
            if (outcome == Outcome.HEIGHT_FALLBACK) {
                zero(bytes, 160, 192);
            }
            return header;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("Invalid World tile header", invalid);
        }
    }

    private static void putSource(ByteBuffer out, int at, Source s) {
        if (s == null) {
            return;
        }
        out.position(at).putInt((int) s.id()).put(HexFormat.of().parseHex(s.contentKey()));
        out.putInt((int) s.consumedLength())
                .putInt(s.declaredLength() == null ? -1 : s.declaredLength().intValue());
        out.putInt(s.sourceLimit())
                .putShort((short) s.originalSide())
                .put((byte) s.mipLevel())
                .put((byte) s.mipFlags());
        out.put((byte) encoding(s.encoding()))
                .put((byte) s.alphaDepth())
                .put((byte) s.alphaEncoding());
    }

    private static Source getSource(ByteBuffer in, int at, boolean structural) {
        byte[] bytes = in.array();
        long id = Integer.toUnsignedLong(in.getInt(at));
        if (id == 0) {
            zero(bytes, at, at + 48);
            return null;
        }
        zero(bytes, at + 39, at + 48);
        int code = bytes[at + 36] & 255;
        require(structural == (code == 0), "Source encoding domain");
        Encoding encoding =
                switch (code) {
                    case 0 -> null;
                    case 1 -> Encoding.PALETTED;
                    case 2 -> Encoding.BC1;
                    case 3 -> Encoding.BC2;
                    case 4 -> Encoding.BC3;
                    case 5 -> Encoding.BGRA8;
                    default -> throw new IllegalArgumentException("Encoding");
                };
        long declared = Integer.toUnsignedLong(in.getInt(at + 24));
        return new Source(
                id,
                HexFormat.of().formatHex(bytes, at + 4, at + 20),
                Integer.toUnsignedLong(in.getInt(at + 20)),
                declared == 0xffff_ffffL ? null : declared,
                in.getInt(at + 28),
                Short.toUnsignedInt(in.getShort(at + 32)),
                bytes[at + 34] & 255,
                bytes[at + 35] & 255,
                encoding,
                bytes[at + 37] & 255,
                bytes[at + 38] & 255);
    }

    private static int encoding(Encoding encoding) {
        if (encoding == null) {
            return 0;
        }
        return switch (encoding) {
            case PALETTED -> 1;
            case BC1 -> 2;
            case BC2 -> 3;
            case BC3 -> 4;
            case BGRA8 -> 5;
        };
    }

    private static void structural(Source source, int limit) {
        require(
                source != null && source.encoding() == null && source.sourceLimit() <= limit,
                "Structural source");
    }

    private static void identity(HashMap<Long, Source> identities, Source source) {
        var old = identities.putIfAbsent(source.id(), source);
        require(
                old == null
                        || old.contentKey().equals(source.contentKey())
                                && old.consumedLength() == source.consumedLength()
                                && Objects.equals(old.declaredLength(), source.declaredLength()),
                "Conflicting source identity");
    }

    private static boolean hex(String text, int length) {
        return text != null && text.matches("[0-9a-f]{" + length + "}");
    }

    private static void zero(byte[] bytes, int first, int end) {
        for (int i = first; i < end; i++) {
            require(bytes[i] == 0, "Reserved packet bytes");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void basename(Path path, long transaction) throws IOException {
        if (path.getFileName() == null
                || !path.getFileName().toString().equals("tile-" + transaction + ".wst")) {
            throw new IOException("Unexpected tile packet basename");
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
