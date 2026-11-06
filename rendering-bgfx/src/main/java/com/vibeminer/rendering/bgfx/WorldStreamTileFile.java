package com.vibeminer.rendering.bgfx;

import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    private static final int SCRATCH = 64 << 10, IO_CHUNK = 256 << 10;
    private static final byte[] MAGIC =
            "VIMIWST2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] WATER_MAGIC =
            "VIMIWTR1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final int WATER_HEADER_BYTES = 128, MAX_WATER_CELLS = 8192, MAX_WATER_LAYERS = 128;
    static final int MAX_WATER_BYTES = waterBytes(MAX_WATER_CELLS * 4, MAX_WATER_CELLS * 6);

    static int waterBytes(int vertices, int indices) {
        return WATER_HEADER_BYTES + vertices * 13 + indices * 4;
    }

    /**
     * Optional static flat water trailer. Structural only: the closed application bridge is the
     * sole producer from an actual Source water preparation joined to this exact terrain payload.
     * Positions use native terrain axes (x north, y west, z up); every cell is four corners
     * NW,NE,SW,SE with the fixed NE--SW diagonal indices base,base+2,base+1 / base+1,base+2,base+3.
     */
    record Water(float[] positions, byte[] depths, int[] indices, int layers) {
        Water {
            Objects.requireNonNull(positions);
            Objects.requireNonNull(depths);
            Objects.requireNonNull(indices);
            int vertices = depths.length;
            require(
                    vertices % 4 == 0
                            && vertices >= 4
                            && vertices <= MAX_WATER_CELLS * 4
                            && positions.length == vertices * 3
                            && indices.length == vertices / 4 * 6
                            && layers >= 1
                            && layers <= MAX_WATER_LAYERS
                            && layers <= vertices / 4,
                    "Water counts");
            for (float value : positions) {
                require(Float.isFinite(value) && Math.abs(value) <= 1_000_000, "Water position");
            }
            for (int cell = 0; cell < vertices / 4; cell++) {
                int base = cell * 4, at = cell * 6;
                require(
                        indices[at] == base
                                && indices[at + 1] == base + 2
                                && indices[at + 2] == base + 1
                                && indices[at + 3] == base + 1
                                && indices[at + 4] == base + 2
                                && indices[at + 5] == base + 3,
                        "Water cell indices");
            }
        }

        int vertices() {
            return depths.length;
        }

        int cells() {
            return depths.length / 4;
        }

        int bytes() {
            return waterBytes(depths.length, indices.length);
        }
    }

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
            List<Source> diffuse,
            // Optional BASIC_EXTERIOR objects file named by exact length and SHA-256.
            WorldStreamObjectsFile.Reference objects) {
        /** Terrain-only header, without an objects file. */
        Header(
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
            this(
                    nonce,
                    scope,
                    transaction,
                    lease,
                    x,
                    y,
                    chunks,
                    vertices,
                    indices,
                    geometrySha256,
                    materialDigest,
                    outcome,
                    reason,
                    coverage,
                    maxX,
                    maxY,
                    uvError,
                    wdt,
                    root,
                    tex0,
                    diffuse,
                    null);
        }

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
        private Water water;

        private Payload(Header header, TerrainScene.Tile tile, byte[] atlas, Water water) {
            this.header = header;
            this.tile = tile;
            this.atlas = atlas;
            this.water = water;
        }

        /** Validated optional water joined to this exact terrain payload, or null. */
        Water water() {
            tile();
            return water;
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
            water = null;
        }
    }

    private WorldStreamTileFile() {}

    static Payload read(Path path, Expected expected, BooleanSupplier canceled) throws IOException {
        long started = WorldStageTimings.start();
        try {
            return readTimed(path, expected, canceled);
        } finally {
            WorldStageTimings.stop("child.tileFile.read", started);
        }
    }

    private static Payload readTimed(Path path, Expected expected, BooleanSupplier canceled)
            throws IOException {
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
            fill(channel, first, canceled);
            digest.update(first.array());
            var header = decodeHeader(first.array(), expected);
            // Only after the complete fixed header/session/count checks may body buffers exist.
            // Each section is read whole, in bounded chunks, straight into its final buffer.
            var geometry = ByteBuffer.allocate(header.geometryBytes());
            fill(channel, geometry, canceled);
            digest.update(geometry.array());
            byte[] atlas = null;
            if (header.outcome() == Outcome.TEXTURED) {
                atlas = new byte[ATLAS_BYTES];
                fill(channel, ByteBuffer.wrap(atlas), canceled);
                digest.update(atlas);
            }
            int trailer = Math.toIntExact(expected.length() - (long) header.totalBytes());
            ByteBuffer water = null;
            if (trailer != 0) {
                water = ByteBuffer.allocate(trailer);
                fill(channel, water, canceled);
                digest.update(water.array());
            }
            check(canceled);
            if (channel.read(ByteBuffer.allocate(1)) != -1
                    || channel.size() != expected.length()
                    || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw new IOException("Packet bytes or full digest differ");
            }
            var tile = readGeometry(geometry, header, canceled);
            if (atlas != null) {
                validateAtlas(header, tile, ByteBuffer.wrap(atlas).asReadOnlyBuffer(), canceled);
            }
            return new Payload(
                    header,
                    tile,
                    atlas,
                    water == null ? null : readWater(water, header, tile, canceled));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid World tile packet", invalid);
        }
    }

    /** Writes all of {@code buffer} in bounded chunks, checking cancellation between them. */
    private static void drain(FileChannel channel, ByteBuffer buffer, BooleanSupplier canceled)
            throws IOException {
        int end = buffer.limit();
        while (buffer.position() < end) {
            check(canceled);
            buffer.limit(Math.min(end, buffer.position() + IO_CHUNK));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    /** Fills {@code buffer} from the channel's current position, refusing a short file. */
    private static void fill(FileChannel channel, ByteBuffer buffer, BooleanSupplier canceled)
            throws IOException {
        int end = buffer.limit();
        do {
            check(canceled);
            buffer.limit(Math.min(end, buffer.position() + IO_CHUNK));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Truncated packet");
                }
            }
        } while (buffer.limit() < end);
        buffer.flip();
    }

    private static TerrainScene.Tile readGeometry(
            ByteBuffer bytes, Header header, BooleanSupplier canceled) throws IOException {
        float[] vertices = new float[header.vertices() * 3];
        int[] indices = new int[header.indices()];
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().get(vertices);
        bytes.position(vertices.length * 4).asIntBuffer().get(indices);
        check(canceled);
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
        long started = WorldStageTimings.start();
        try {
            return writeTimed(directory, header, tile, atlas, canceled);
        } finally {
            WorldStageTimings.stop("tileFile.write.total", started);
        }
    }

    private static Expected writeTimed(
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
        long fingerprint = WorldStageTimings.start();
        require(
                tile.x() == header.x()
                        && tile.y() == header.y()
                        && tile.chunks() == header.chunks()
                        && tile.vertexCount() == header.vertices()
                        && tile.indexCount() == header.indices()
                        && TerrainGeometryFingerprint.sha256(tile).equals(header.geometrySha256()),
                "Geometry/header mismatch");
        WorldStageTimings.stop("tileFile.write.geometryFingerprint", fingerprint);
        if (header.outcome() == Outcome.TEXTURED) {
            validateAtlas(header, tile, atlas, canceled);
        } else {
            require(atlas == null, "Height packet atlas");
        }
        long streaming = WorldStageTimings.start();
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
                var body =
                        ByteBuffer.allocate(HEADER_BYTES + header.geometryBytes())
                                .order(ByteOrder.LITTLE_ENDIAN);
                body.put(encodeHeader(header));
                body.asFloatBuffer().put(tile.vertices());
                body.position(HEADER_BYTES + tile.vertexCount() * 12);
                body.asIntBuffer().put(tile.indices());
                body.clear();
                digest.update(body.array());
                drain(channel, body, canceled);
                if (atlas != null) {
                    digest.update(atlas.duplicate());
                    drain(channel, atlas.duplicate(), canceled);
                }
                WorldStageTimings.stop("tileFile.write.encodeDigestWrite", streaming);
                WorldStageTimings.add("tileFile.write.bytes", channel.size());
                // No fsync: a session-private scratch file the child reads back and deletes. A
                // torn or short file fails the exact length and full digest on read.
            }
            check(canceled);
            // No replace-existing option. Private session owner serializes publication per tx.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Packet exists");
            }
            long moving = WorldStageTimings.start();
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            WorldStageTimings.stop("tileFile.write.rename", moving);
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

    private static Water readWater(
            ByteBuffer bytes, Header header, TerrainScene.Tile tile, BooleanSupplier canceled)
            throws IOException {
        long length = bytes.remaining();
        byte[] head = new byte[WATER_HEADER_BYTES];
        bytes.get(0, head);
        var in = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int cells = in.getInt(12), vertices = in.getInt(16), indexCount = in.getInt(20);
        require(
                Arrays.equals(WATER_MAGIC, Arrays.copyOf(head, 8))
                        && in.getInt(8) == 1
                        && cells >= 1
                        && cells <= MAX_WATER_CELLS
                        && vertices == cells * 4
                        && indexCount == cells * 6
                        && in.getInt(28) == 0
                        && length == waterBytes(vertices, indexCount)
                        && HexFormat.of().formatHex(head, 32, 64).equals(header.geometrySha256())
                        && in.getLong(64) == header.root().id()
                        && HexFormat.of()
                                .formatHex(head, 72, 88)
                                .equals(header.root().contentKey()),
                "Water trailer identity");
        zero(head, 88, WATER_HEADER_BYTES);
        float[] positions = new float[vertices * 3];
        byte[] depths = new byte[vertices];
        int[] indices = new int[indexCount];
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(WATER_HEADER_BYTES).asFloatBuffer().get(positions);
        bytes.position(WATER_HEADER_BYTES + positions.length * 4).get(depths);
        bytes.asIntBuffer().get(indices);
        check(canceled);
        var water = new Water(positions, depths, indices, in.getInt(24));
        require(onFootprint(water, footprint(tile)), "Water outside terrain tile");
        return water;
    }

    /** Terrain vertex XY bounds {minX, maxX, minY, maxY}; shared by writer and reader. */
    static float[] footprint(TerrainScene.Tile tile) {
        var terrain = tile.vertices();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int at = 0; at + 2 < terrain.limit(); at += 3) {
            minX = Math.min(minX, terrain.get(at));
            maxX = Math.max(maxX, terrain.get(at));
            minY = Math.min(minY, terrain.get(at + 1));
            maxY = Math.max(maxY, terrain.get(at + 1));
        }
        return new float[] {minX, maxX, minY, maxY};
    }

    /** Water must lie on its terrain tile: the vertex footprint plus one liquid cell. */
    static boolean onFootprint(Water water, float[] bounds) {
        float slack = (float) (com.vibeminer.formats.world.WorldFormats.TILE_SIZE / 128);
        var positions = water.positions();
        for (int v = 0; v < water.vertices(); v++) {
            float x = positions[v * 3], y = positions[v * 3 + 1];
            if (!(x >= bounds[0] - slack
                    && x <= bounds[1] + slack
                    && y >= bounds[2] - slack
                    && y <= bounds[3] + slack)) {
                return false;
            }
        }
        return true;
    }

    /** The unadvertised packet no longer matches its own expectation: never advertise it. */
    static final class PacketChanged extends IOException {
        private static final long serialVersionUID = 1L;

        PacketChanged(String message) {
            super(message);
        }
    }

    private static byte[] encodeWater(Header header, Water water) {
        var out = ByteBuffer.allocate(water.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        out.put(WATER_MAGIC).putInt(1).putInt(water.cells()).putInt(water.vertices());
        out.putInt(water.indices().length).putInt(water.layers()).putInt(0);
        out.put(HexFormat.of().parseHex(header.geometrySha256()));
        out.putLong(header.root().id());
        out.put(HexFormat.of().parseHex(header.root().contentKey()));
        out.position(WATER_HEADER_BYTES);
        for (float value : water.positions()) {
            out.putFloat(value);
        }
        out.put(water.depths());
        for (int value : water.indices()) {
            out.putInt(value);
        }
        require(!out.hasRemaining(), "Water encoding length");
        return out.array();
    }

    /**
     * Appends one water trailer to this caller-owned, finalized and still UNADVERTISED terrain
     * packet. Re-verifies the existing bytes against their expectation first. On any failure the
     * file is truncated back to its terrain-only form before rethrowing; if that also fails the
     * caller must discard the packet.
     */
    static Expected appendWater(
            Path path, Expected expected, Header header, Water water, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(header);
        Objects.requireNonNull(water);
        Objects.requireNonNull(canceled);
        basename(path, expected.transaction());
        require(
                header.totalBytes() == expected.length()
                        && header.transaction() == expected.transaction()
                        && header.nonce().equals(expected.nonce())
                        && header.lease().equals(expected.lease()),
                "Water append expectation");
        byte[] trailer = encodeWater(header, water);
        try (var channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != expected.length()) {
                throw new PacketChanged("Packet size differs before water append");
            }
            var digest = digest();
            var scratch = ByteBuffer.allocate(SCRATCH);
            long at = 0;
            while (at < expected.length()) {
                check(canceled);
                scratch.clear();
                int read = channel.read(scratch, at);
                if (read < 0) {
                    throw new PacketChanged("Truncated packet before water append");
                }
                digest.update(scratch.array(), 0, read);
                at += read;
            }
            MessageDigest prior;
            try {
                prior = (MessageDigest) digest.clone();
            } catch (CloneNotSupportedException impossible) {
                throw new AssertionError(impossible);
            }
            if (!HexFormat.of().formatHex(prior.digest()).equals(expected.sha256())) {
                throw new PacketChanged("Packet bytes changed before water append");
            }
            try {
                check(canceled);
                var body = ByteBuffer.wrap(trailer);
                long position = expected.length();
                while (body.hasRemaining()) {
                    position += channel.write(body, position);
                }
                check(canceled);
            } catch (IOException | RuntimeException | Error primary) {
                try {
                    channel.truncate(expected.length());
                } catch (IOException | RuntimeException | Error cleanup) {
                    // Restore unknown: the caller must discard this packet, never advertise it.
                    var unknown = new PacketChanged("Water rollback failed");
                    unknown.addSuppressed(primary);
                    unknown.addSuppressed(cleanup);
                    throw unknown;
                }
                throw primary;
            }
            digest.update(trailer);
            return new Expected(
                    expected.nonce(),
                    expected.scope(),
                    expected.transaction(),
                    expected.lease(),
                    Math.addExact(expected.length(), trailer.length),
                    HexFormat.of().formatHex(digest.digest()));
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
        if (h.objects() != null) {
            out.position(656).putInt(h.objects().length()).putInt(0);
            out.put(HexFormat.of().parseHex(h.objects().sha256()));
        }
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
                            && in.getLong(16) <= expected.length(),
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
            WorldStreamObjectsFile.Reference objects = null;
            if (in.getInt(656) == 0) {
                zero(bytes, 656, 1024);
            } else {
                zero(bytes, 660, 664);
                zero(bytes, 696, 1024);
                objects =
                        new WorldStreamObjectsFile.Reference(
                                in.getInt(656), HexFormat.of().formatHex(bytes, 664, 696));
            }
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
                            diffuse,
                            objects);
            require(
                    in.getInt(120) == header.geometryBytes()
                            && header.totalBytes() == in.getLong(16)
                            && (header.totalBytes() == expected.length()
                                    || expected.length() - header.totalBytes() >= waterBytes(4, 6)
                                            && expected.length() - header.totalBytes()
                                                    <= MAX_WATER_BYTES)
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
