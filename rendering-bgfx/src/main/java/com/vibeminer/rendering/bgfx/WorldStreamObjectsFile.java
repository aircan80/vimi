package com.vibeminer.rendering.bgfx;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable per-tile BASIC_EXTERIOR_V1 objects file {@code tile-<txn>.wso}, version 2. Structural
 * only: the application's closed source bridge is its sole producer, and the OBJECTS command (or
 * the terrain tile header) names its exact length and SHA-256.
 *
 * <p>Little-endian layout: a 256-byte header, then the model, mesh, range (first index, index
 * count, image, material class), placement (model, translation, scale and a row-major rotation)
 * and image tables, then 32-byte vertices (position, normal, uv floats), u16 mesh-relative indices
 * padded to four bytes, and RGBA8 pixels. Every count, offset, index and float is validated;
 * reserved bytes must be zero.
 */
final class WorldStreamObjectsFile {
    static final int VERSION = 2, HEADER_BYTES = 256, MAX_BYTES = 16 << 20;
    // Buildings (24 roots, 256 meshes, 1,024 ranges, 64 placements, 64 images, 98,304 vertices,
    // 294,912 indices, 1 Mi pixels) plus BASIC doodads (48 models and meshes, 512 ranges and
    // placements, 64 images, 65,536 vertices, 196,608 indices, 512 Ki pixels).
    static final int MAX_MODELS = 72, MAX_MESHES = 304, MAX_RANGES = 1536, MAX_PLACEMENTS = 576;
    static final int MAX_IMAGES = 128, MAX_VERTICES = 163_840, MAX_INDICES = 491_520;
    static final int MAX_PIXELS = 3 << 19, MAX_IMAGE_SIDE = 256, MAX_MESH_VERTICES = 65_536;

    /** Range material classes: depth-writing opaque, alpha-tested cutout, sorted alpha blend. */
    static final int OPAQUE = 0, CUTOUT = 1, BLENDED = 2;

    static final int RANGE_INTS = 4, PLACEMENT_DOUBLES = 13;

    /** Draw calls per tile (sum over placements of their model's ranges); bounds a frame. */
    static final int MAX_DRAWS = 4096;

    static final int VERTEX_FLOATS = 8, VERTEX_BYTES = 32;
    static final double MAX_SCALE = 64;
    private static final int SCRATCH = 64 << 10;
    private static final byte[] MAGIC = "VIMIWSO1".getBytes(StandardCharsets.US_ASCII);

    /** Validated immutable object set. Arrays are owned; accessors never expose them mutable. */
    static final class Data {
        /** Per model: first mesh, mesh count. */
        final int[] models;

        /** Per mesh: first vertex, vertex count, first index, index count, first range, ranges. */
        final int[] meshes;

        /** Per range: mesh-relative first index, index count, image, material class. */
        final int[] ranges;

        /** Per placement model ordinal. */
        final int[] placementModels;

        /**
         * Per placement: x, y, z, scale, then a row-major rotation (rows are output X, Y, Z) in
         * native terrain axes.
         */
        final double[] placements;

        /** Per image: width, height. */
        final int[] images;

        final float[] vertices;
        final short[] indices;
        final byte[] pixels;
        final int draws;

        Data(
                int[] models,
                int[] meshes,
                int[] ranges,
                int[] placementModels,
                double[] placements,
                int[] images,
                float[] vertices,
                short[] indices,
                byte[] pixels) {
            this.models = Objects.requireNonNull(models);
            this.meshes = Objects.requireNonNull(meshes);
            this.ranges = Objects.requireNonNull(ranges);
            this.placementModels = Objects.requireNonNull(placementModels);
            this.placements = Objects.requireNonNull(placements);
            this.images = Objects.requireNonNull(images);
            this.vertices = Objects.requireNonNull(vertices);
            this.indices = Objects.requireNonNull(indices);
            this.pixels = Objects.requireNonNull(pixels);
            draws = validate();
        }

        int modelCount() {
            return models.length / 2;
        }

        int meshCount() {
            return meshes.length / 6;
        }

        int rangeCount() {
            return ranges.length / RANGE_INTS;
        }

        int placementCount() {
            return placementModels.length;
        }

        int imageCount() {
            return images.length / 2;
        }

        int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }

        int indexCount() {
            return indices.length;
        }

        int pixelCount() {
            return pixels.length / 4;
        }

        int bytes() {
            return length(
                    modelCount(),
                    meshCount(),
                    rangeCount(),
                    placementCount(),
                    imageCount(),
                    vertexCount(),
                    indexCount(),
                    pixelCount());
        }

        private int validate() {
            int modelCount = modelCount(), meshCount = meshCount(), rangeCount = rangeCount();
            int placementCount = placementCount(), imageCount = imageCount();
            int vertexCount = vertexCount(), indexCount = indexCount(), pixelCount = pixelCount();
            require(
                    models.length % 2 == 0
                            && meshes.length % 6 == 0
                            && ranges.length % RANGE_INTS == 0
                            && images.length % 2 == 0
                            && vertices.length % VERTEX_FLOATS == 0
                            && pixels.length % 4 == 0
                            && placements.length == placementCount * PLACEMENT_DOUBLES
                            && modelCount >= 1
                            && modelCount <= MAX_MODELS
                            && meshCount >= modelCount
                            && meshCount <= MAX_MESHES
                            && rangeCount >= meshCount
                            && rangeCount <= MAX_RANGES
                            && placementCount >= 1
                            && placementCount <= MAX_PLACEMENTS
                            && imageCount >= 1
                            && imageCount <= MAX_IMAGES
                            && vertexCount >= 3
                            && vertexCount <= MAX_VERTICES
                            && indexCount >= 3
                            && indexCount <= MAX_INDICES
                            && pixelCount >= 1
                            && pixelCount <= MAX_PIXELS,
                    "Object counts");
            int meshAt = 0;
            for (int m = 0; m < modelCount; m++) {
                require(models[m * 2] == meshAt && models[m * 2 + 1] >= 1, "Model meshes");
                meshAt += models[m * 2 + 1];
                require(meshAt <= meshCount, "Model meshes");
            }
            require(meshAt == meshCount, "Model meshes");
            int vertexAt = 0, indexAt = 0, rangeAt = 0;
            int[] meshRanges = new int[meshCount];
            for (int mesh = 0; mesh < meshCount; mesh++) {
                int at = mesh * 6;
                int vertexes = meshes[at + 1], indexes = meshes[at + 3], count = meshes[at + 5];
                require(
                        meshes[at] == vertexAt
                                && vertexes >= 3
                                && vertexes <= MAX_MESH_VERTICES
                                && meshes[at + 2] == indexAt
                                && indexes >= 3
                                && indexes % 3 == 0
                                && meshes[at + 4] == rangeAt
                                && count >= 1
                                && (long) vertexAt + vertexes <= vertexCount
                                && (long) indexAt + indexes <= indexCount
                                && (long) rangeAt + count <= rangeCount,
                        "Mesh table");
                for (int i = indexAt; i < indexAt + indexes; i++) {
                    require(Short.toUnsignedInt(indices[i]) < vertexes, "Mesh index");
                }
                for (int r = rangeAt; r < rangeAt + count; r++) {
                    int first = ranges[r * RANGE_INTS],
                            length = ranges[r * RANGE_INTS + 1],
                            image = ranges[r * RANGE_INTS + 2],
                            blend = ranges[r * RANGE_INTS + 3];
                    require(
                            first >= 0
                                    && first % 3 == 0
                                    && length >= 3
                                    && length % 3 == 0
                                    && (long) first + length <= indexes
                                    && image >= 0
                                    && image < imageCount
                                    && blend >= OPAQUE
                                    && blend <= BLENDED,
                            "Draw range");
                }
                meshRanges[mesh] = count;
                vertexAt += vertexes;
                indexAt += indexes;
                rangeAt += count;
            }
            require(
                    vertexAt == vertexCount && indexAt == indexCount && rangeAt == rangeCount,
                    "Mesh table");
            for (float value : vertices) {
                require(Float.isFinite(value) && Math.abs(value) <= 1_000_000, "Vertex value");
            }
            long draws = 0;
            for (int p = 0; p < placementCount; p++) {
                int model = placementModels[p];
                require(model >= 0 && model < modelCount, "Placement model");
                int at = p * PLACEMENT_DOUBLES;
                double x = placements[at], y = placements[at + 1], z = placements[at + 2];
                double scale = placements[at + 3];
                require(
                        Double.isFinite(x)
                                && Double.isFinite(y)
                                && Double.isFinite(z)
                                && Math.abs(x) <= 1_000_000
                                && Math.abs(y) <= 1_000_000
                                && Math.abs(z) <= 1_000_000
                                && Double.isFinite(scale)
                                && scale > 0
                                && scale <= MAX_SCALE
                                && rotation(placements, at + 4),
                        "Placement transform");
                for (int mesh = models[model * 2];
                        mesh < models[model * 2] + models[model * 2 + 1];
                        mesh++) {
                    draws += meshRanges[mesh];
                }
            }
            require(draws <= MAX_DRAWS, "Object draw budget");
            long pixelSum = 0;
            for (int i = 0; i < imageCount; i++) {
                int width = images[i * 2], height = images[i * 2 + 1];
                require(side(width) && side(height), "Image size");
                pixelSum += (long) width * height;
            }
            require(pixelSum == pixelCount, "Image pixels");
            require(bytes() <= MAX_BYTES, "Objects file ceiling");
            return (int) draws;
        }
    }

    /** Exact identity of the one terrain tile this file belongs to. */
    record Identity(
            WorldStreamState.Nonce nonce,
            String scope,
            long transaction,
            WorldStreamState.Lease lease,
            int x,
            int y) {
        Identity {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(lease);
            require(
                    scope != null
                            && scope.matches("[0-9a-f]{64}")
                            && transaction > 0
                            && transaction < Long.MAX_VALUE
                            && x >= 0
                            && x < 64
                            && y >= 0
                            && y < 64,
                    "Objects identity");
        }

        static Identity of(WorldStreamTileFile.Header header) {
            return new Identity(
                    header.nonce(),
                    header.scope(),
                    header.transaction(),
                    header.lease(),
                    header.x(),
                    header.y());
        }
    }

    /** Length and whole-file SHA-256, as named in the terrain tile header. */
    record Reference(int length, String sha256) {
        Reference {
            require(
                    length >= HEADER_BYTES
                            && length <= MAX_BYTES
                            && sha256 != null
                            && sha256.matches("[0-9a-f]{64}"),
                    "Objects reference");
        }
    }

    private WorldStreamObjectsFile() {}

    static String basename(long transaction) {
        return "tile-" + transaction + ".wso";
    }

    /** A proper rotation: finite, orthonormal rows within 1e-6 and determinant +1. */
    static boolean rotation(double[] m, int at) {
        for (int i = 0; i < 9; i++) {
            if (!Double.isFinite(m[at + i])) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double dot = 0;
                for (int k = 0; k < 3; k++) {
                    dot += m[at + i * 3 + k] * m[at + j * 3 + k];
                }
                if (Math.abs(dot - (i == j ? 1 : 0)) > 1e-6) {
                    return false;
                }
            }
        }
        double det =
                m[at] * (m[at + 4] * m[at + 8] - m[at + 5] * m[at + 7])
                        - m[at + 1] * (m[at + 3] * m[at + 8] - m[at + 5] * m[at + 6])
                        + m[at + 2] * (m[at + 3] * m[at + 7] - m[at + 4] * m[at + 6]);
        return Math.abs(det - 1) <= 1e-6;
    }

    static int length(
            int models,
            int meshes,
            int ranges,
            int placements,
            int images,
            int vertices,
            int indices,
            int pixels) {
        long bytes =
                HEADER_BYTES
                        + models * 8L
                        + meshes * 24L
                        + ranges * (RANGE_INTS * 4L)
                        + placements * (8L + PLACEMENT_DOUBLES * 8L)
                        + images * 8L
                        + vertices * (long) VERTEX_BYTES
                        + (indices * 2L + 3) / 4 * 4
                        + pixels * 4L;
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    /** CREATE_NEW publication of {@code tile-<txn>.wso} in directory; never replaces a file. */
    static Reference write(Path directory, Identity identity, Data data, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(data);
        Objects.requireNonNull(canceled);
        check(canceled);
        Path target = directory.resolve(basename(identity.transaction()));
        Path part = directory.resolve(basename(identity.transaction()) + ".part");
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
                output.write(encodeHeader(identity, data));
                for (int value : data.models) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int value : data.meshes) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int value : data.ranges) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int p = 0; p < data.placementCount(); p++) {
                    output.writeInt(Integer.reverseBytes(data.placementModels[p]));
                    output.writeInt(0);
                    for (int i = 0; i < PLACEMENT_DOUBLES; i++) {
                        output.writeLong(
                                Long.reverseBytes(
                                        Double.doubleToRawLongBits(
                                                data.placements[p * PLACEMENT_DOUBLES + i])));
                    }
                }
                for (int value : data.images) {
                    output.writeInt(Integer.reverseBytes(value));
                }
                for (int i = 0; i < data.vertices.length; i++) {
                    if ((i & 4095) == 0) {
                        check(canceled);
                    }
                    output.writeInt(
                            Integer.reverseBytes(Float.floatToRawIntBits(data.vertices[i])));
                }
                for (int i = 0; i < data.indices.length; i++) {
                    if ((i & 4095) == 0) {
                        check(canceled);
                    }
                    output.writeShort(Short.reverseBytes(data.indices[i]));
                }
                if ((data.indices.length & 1) != 0) {
                    output.writeShort(0);
                }
                for (int at = 0; at < data.pixels.length; at += SCRATCH) {
                    check(canceled);
                    output.write(data.pixels, at, Math.min(SCRATCH, data.pixels.length - at));
                }
                output.flush();
                if (channel.size() != data.bytes()) {
                    throw new IOException("Objects file length differs");
                }
                channel.force(false);
            }
            check(canceled);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Objects file exists");
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new Reference(data.bytes(), HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (created && !published) {
                Files.deleteIfExists(part);
            }
        }
    }

    /** Reads and fully validates one objects file bound to exactly this tile and reference. */
    static Data read(Path path, Identity identity, Reference reference, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(reference);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (path.getFileName() == null
                || !path.getFileName().toString().equals(basename(identity.transaction()))) {
            throw new IOException("Unexpected objects file basename");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Objects file is not a regular private file");
        }
        try (var channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != reference.length()) {
                throw new IOException("Objects file size");
            }
            var digest = digest();
            var input =
                    new DataInputStream(
                            new DigestInputStream(
                                    new BufferedInputStream(
                                            Channels.newInputStream(channel), SCRATCH),
                                    digest));
            byte[] head = new byte[HEADER_BYTES];
            input.readFully(head);
            var in = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            require(
                    Arrays.equals(MAGIC, Arrays.copyOf(head, 8))
                            && in.getInt(8) == VERSION
                            && in.getInt(12) == HEADER_BYTES
                            && in.getLong(16) == reference.length()
                            && in.getLong(24) == identity.nonce().high()
                            && in.getLong(32) == identity.nonce().low()
                            && HexFormat.of().formatHex(head, 40, 72).equals(identity.scope())
                            && in.getLong(72) == identity.transaction()
                            && in.getLong(80) == identity.lease().generation()
                            && in.getInt(88) == identity.lease().slot()
                            && in.getInt(92) == identity.x()
                            && in.getInt(96) == identity.y()
                            && in.getInt(100) == 0
                            && in.getLong(104) == identity.lease().serial(),
                    "Objects header identity");
            for (int i = 152; i < HEADER_BYTES; i++) {
                require(head[i] == 0, "Reserved objects bytes");
            }
            int models = in.getInt(112), meshes = in.getInt(116), ranges = in.getInt(120);
            int placements = in.getInt(124), images = in.getInt(128), vertices = in.getInt(132);
            int indices = in.getInt(136), pixels = in.getInt(140), draws = in.getInt(144);
            require(
                    models >= 1
                            && models <= MAX_MODELS
                            && meshes >= 1
                            && meshes <= MAX_MESHES
                            && ranges >= 1
                            && ranges <= MAX_RANGES
                            && placements >= 1
                            && placements <= MAX_PLACEMENTS
                            && images >= 1
                            && images <= MAX_IMAGES
                            && vertices >= 3
                            && vertices <= MAX_VERTICES
                            && indices >= 3
                            && indices <= MAX_INDICES
                            && pixels >= 1
                            && pixels <= MAX_PIXELS
                            && in.getInt(148) == 0
                            && length(
                                            models,
                                            meshes,
                                            ranges,
                                            placements,
                                            images,
                                            vertices,
                                            indices,
                                            pixels)
                                    == reference.length(),
                    "Objects header counts");
            // Bounded by the counts above before any body allocation.
            int[] modelTable = ints(input, models * 2);
            int[] meshTable = ints(input, meshes * 6);
            int[] rangeTable = ints(input, ranges * RANGE_INTS);
            int[] placementModels = new int[placements];
            double[] transforms = new double[placements * PLACEMENT_DOUBLES];
            for (int p = 0; p < placements; p++) {
                placementModels[p] = Integer.reverseBytes(input.readInt());
                require(input.readInt() == 0, "Reserved placement bytes");
                for (int i = 0; i < PLACEMENT_DOUBLES; i++) {
                    transforms[p * PLACEMENT_DOUBLES + i] =
                            Double.longBitsToDouble(Long.reverseBytes(input.readLong()));
                }
            }
            int[] imageTable = ints(input, images * 2);
            float[] vertexData = new float[vertices * VERTEX_FLOATS];
            for (int i = 0; i < vertexData.length; i++) {
                if ((i & 4095) == 0) {
                    check(canceled);
                }
                vertexData[i] = Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
            }
            short[] indexData = new short[indices];
            for (int i = 0; i < indexData.length; i++) {
                if ((i & 4095) == 0) {
                    check(canceled);
                }
                indexData[i] = Short.reverseBytes(input.readShort());
            }
            if ((indices & 1) != 0) {
                require(input.readShort() == 0, "Index padding");
            }
            byte[] pixelData = new byte[pixels * 4];
            for (int at = 0; at < pixelData.length; at += SCRATCH) {
                check(canceled);
                input.readFully(pixelData, at, Math.min(SCRATCH, pixelData.length - at));
            }
            check(canceled);
            if (input.read() != -1
                    || channel.size() != reference.length()
                    || !HexFormat.of().formatHex(digest.digest()).equals(reference.sha256())) {
                throw new IOException("Objects bytes or digest differ");
            }
            var data =
                    new Data(
                            modelTable,
                            meshTable,
                            rangeTable,
                            placementModels,
                            transforms,
                            imageTable,
                            vertexData,
                            indexData,
                            pixelData);
            require(data.draws == draws, "Object draw count");
            return data;
        } catch (EOFException truncated) {
            throw new IOException("Truncated objects file", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid World objects file", invalid);
        }
    }

    private static byte[] encodeHeader(Identity identity, Data data) {
        var out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt(HEADER_BYTES).putLong(data.bytes());
        out.putLong(identity.nonce().high()).putLong(identity.nonce().low());
        out.put(HexFormat.of().parseHex(identity.scope())).putLong(identity.transaction());
        out.putLong(identity.lease().generation()).putInt(identity.lease().slot());
        out.putInt(identity.x()).putInt(identity.y()).putInt(0).putLong(identity.lease().serial());
        out.putInt(data.modelCount()).putInt(data.meshCount()).putInt(data.rangeCount());
        out.putInt(data.placementCount()).putInt(data.imageCount()).putInt(data.vertexCount());
        out.putInt(data.indexCount()).putInt(data.pixelCount()).putInt(data.draws).putInt(0);
        return out.array();
    }

    private static int[] ints(DataInputStream input, int count) throws IOException {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = Integer.reverseBytes(input.readInt());
        }
        return values;
    }

    private static boolean side(int value) {
        return value >= 1 && value <= MAX_IMAGE_SIDE && Integer.bitCount(value) == 1;
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

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
