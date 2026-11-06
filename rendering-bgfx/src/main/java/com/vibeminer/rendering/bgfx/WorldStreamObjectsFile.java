package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
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
 * Immutable per-tile BASIC_EXTERIOR_V1 objects file {@code tile-<txn>.wso}, version 4. Structural
 * only: the application's closed source bridge is its sole producer, and the OBJECTS command (or
 * the terrain tile header) names its exact length and SHA-256.
 *
 * <p>Little-endian layout: a 256-byte header, then the model, mesh, range (first index, index
 * count, image, material class), placement (model, translation, scale and a row-major rotation) and
 * image (width, height, format) tables, then 32-byte vertices (position, normal, uv floats), u16
 * mesh-relative indices padded to four bytes, and image data. Each image's data is its full mip
 * chain, top level down to 1 x 1 and tightly packed, in its format: RGBA8 texels ({@link
 * ObjectTextureMips}, issue #265) or BC1, BC2 or BC3 blocks ({@link ObjectTextureFormats}, issue
 * #374). Every count, offset, index and float is validated; reserved bytes must be zero.
 */
final class WorldStreamObjectsFile {
    static final int VERSION = 4, HEADER_BYTES = 256, MAX_BYTES = 28 << 20;
    // Opaque buildings (24 roots, 256 meshes, 1,024 ranges, 64 placements, 64 images, 98,304
    // vertices, 294,912 indices), plus cutout and blended building surfaces (256 ranges, 32
    // images, 32,768 vertices, 98,304 indices), plus BASIC doodads (96 models and meshes, 512
    // ranges and placements, 96 images, 65,536 vertices, 196,608 indices). Creature models share
    // whatever room remains. Every table at its cap is about 27 MB.
    static final int MAX_MODELS = 120, MAX_MESHES = 352, MAX_RANGES = 1792, MAX_PLACEMENTS = 576;
    static final int MAX_IMAGES = 192, MAX_VERTICES = 196_608, MAX_INDICES = 589_824;

    /**
     * Largest image side. 512 is reachable only by chains charged compressed (issue #374): the
     * producer keeps RGBA8 chains at most 256, and every image still fits the GPU byte budget.
     */
    static final int MAX_IMAGE_SIDE = 512, MAX_MESH_VERTICES = 65_536;

    /** Largest side of a chain uploaded as RGBA8, stored so or decoded from unsampled blocks. */
    static final int MAX_RGBA_SIDE = 256;

    /**
     * Texels of whole mip chains (issue #265) at the HIGH texture tier's per-tile budgets (#267),
     * which are themselves the chains of a 2048 x 1024 (buildings), 1024 x 512 (cutout and blended
     * surfaces) and 1024 x 1024 (doodads) texture: 4,893,355 texels, about 18.7 MiB.
     */
    static final int MAX_PIXELS =
            ObjectTextureMips.chainPixels(2048, 1024)
                    + ObjectTextureMips.chainPixels(1024, 512)
                    + ObjectTextureMips.chainPixels(1024, 1024);

    /** Image data bytes per tile: MAX_PIXELS as RGBA8; compressed chains take less of it. */
    static final int MAX_IMAGE_BYTES = MAX_PIXELS * 4;

    /**
     * GPU bytes all images may take as the child uploads them: each image in a format the file's
     * {@code compressedFormats} names at its stored size, every other image at its decoded RGBA8
     * size, because a child that does not sample a format decodes it (up to 8x). The child refuses
     * a file that names a format it does not sample, so its GPU memory never exceeds this.
     */
    static final int MAX_DECODED_BYTES = MAX_IMAGE_BYTES;

    /** Smallest side of a compressed image's top level: one 4 x 4 block. */
    static final int MIN_COMPRESSED_SIDE = 4;

    /** Per image: width, height, format ({@link ObjectTextureFormats}). */
    static final int IMAGE_INTS = 3;

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

        /** Per image: width, height, format. */
        final int[] images;

        final float[] vertices;
        final short[] indices;

        /** Every image's packed chain in its format, in image order. */
        final byte[] pixels;

        final int draws;

        /**
         * Compressed formats this file charges at their stored size (bit per {@link
         * ObjectTextureFormats} code): those its session's child samples as blocks (#374).
         */
        final int compressedFormats;

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
            this(
                    models,
                    meshes,
                    ranges,
                    placementModels,
                    placements,
                    images,
                    vertices,
                    indices,
                    pixels,
                    0);
        }

        Data(
                int[] models,
                int[] meshes,
                int[] ranges,
                int[] placementModels,
                double[] placements,
                int[] images,
                float[] vertices,
                short[] indices,
                byte[] pixels,
                int compressedFormats) {
            require((compressedFormats & ~0xE) == 0, "Compressed format mask");
            this.compressedFormats = compressedFormats;
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
            return images.length / IMAGE_INTS;
        }

        int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }

        int indexCount() {
            return indices.length;
        }

        int imageBytes() {
            return pixels.length;
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
                    imageBytes());
        }

        private int validate() {
            int modelCount = modelCount(), meshCount = meshCount(), rangeCount = rangeCount();
            int placementCount = placementCount(), imageCount = imageCount();
            int vertexCount = vertexCount(), indexCount = indexCount(), imageBytes = imageBytes();
            require(
                    models.length % 2 == 0
                            && meshes.length % 6 == 0
                            && ranges.length % RANGE_INTS == 0
                            && images.length % IMAGE_INTS == 0
                            && vertices.length % VERTEX_FLOATS == 0
                            && imageBytes % 4 == 0
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
                            && imageBytes >= 1
                            && imageBytes <= MAX_IMAGE_BYTES,
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
            long byteSum = 0, decodedSum = 0;
            for (int i = 0; i < imageCount; i++) {
                int width = images[i * IMAGE_INTS], height = images[i * IMAGE_INTS + 1];
                int format = images[i * IMAGE_INTS + 2];
                require(side(width) && side(height), "Image size");
                require(ObjectTextureFormats.valid(format), "Image format");
                // D3D11 refuses block-compressed textures whose top level is under one block.
                require(
                        !ObjectTextureFormats.compressed(format)
                                || width >= MIN_COMPRESSED_SIDE && height >= MIN_COMPRESSED_SIDE,
                        "Compressed image size");
                require(
                        (compressedFormats & (1 << format)) != 0
                                || width <= MAX_RGBA_SIDE && height <= MAX_RGBA_SIDE,
                        "RGBA8 image size");
                byteSum += ObjectTextureFormats.chainBytes(format, width, height);
                decodedSum +=
                        (compressedFormats & (1 << format)) != 0
                                ? ObjectTextureFormats.chainBytes(format, width, height)
                                : 4L * ObjectTextureMips.chainPixels(width, height);
            }
            require(byteSum == imageBytes, "Image data");
            require(decodedSum <= MAX_DECODED_BYTES, "Decoded image budget");
            // BC1 alpha is not sampled on every backend (bgfx Vulkan uses the RGB block format),
            // so only opaque ranges, which ignore alpha, may sample a BC1 image.
            for (int r = 0; r < rangeCount; r++) {
                require(
                        ranges[r * RANGE_INTS + 3] == OPAQUE
                                || images[ranges[r * RANGE_INTS + 2] * IMAGE_INTS + 2]
                                        != ObjectTextureFormats.BC1,
                        "BC1 image sampled with alpha");
            }
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
            int imageBytes) {
        long bytes =
                HEADER_BYTES
                        + models * 8L
                        + meshes * 24L
                        + ranges * (RANGE_INTS * 4L)
                        + placements * (8L + PLACEMENT_DOUBLES * 8L)
                        + images * (IMAGE_INTS * 4L)
                        + vertices * (long) VERTEX_BYTES
                        + (indices * 2L + 3) / 4 * 4
                        + imageBytes;
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
            int indices = in.getInt(136), imageBytes = in.getInt(140), draws = in.getInt(144);
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
                            && imageBytes >= 1
                            && imageBytes <= MAX_IMAGE_BYTES
                            && imageBytes % 4 == 0
                            && (in.getInt(148) & ~0xE) == 0
                            && length(
                                            models,
                                            meshes,
                                            ranges,
                                            placements,
                                            images,
                                            vertices,
                                            indices,
                                            imageBytes)
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
            int[] imageTable = ints(input, images * IMAGE_INTS);
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
            byte[] pixelData = new byte[imageBytes];
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
                            pixelData,
                            in.getInt(148));
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
        out.putInt(data.indexCount()).putInt(data.imageBytes()).putInt(data.draws);
        out.putInt(data.compressedFormats);
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
