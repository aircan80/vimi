package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Source-neutral structural writer. Only the application's closed source bridge qualifies assets.
 */
public final class WorldStreamTileWriter {
    public enum Outcome {
        TEXTURED,
        HEIGHT_FALLBACK
    }

    public enum Reason {
        NONE,
        TERMINAL_MATERIAL_UNAVAILABLE,
        QUALIFIED_BINDING_REJECTED
    }

    public enum Encoding {
        PALETTED,
        BC1,
        BC2,
        BC3,
        BGRA8
    }

    /** Descriptive consumed-file/mip tuple; construction grants no source authority. */
    public record Source(
            long fileDataId,
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
        public Source {
            new WorldStreamTileFile.Source(
                    fileDataId,
                    contentKey,
                    consumedLength,
                    declaredLength,
                    sourceLimit,
                    originalSide,
                    mipLevel,
                    mipFlags,
                    encoding == null ? null : WorldStreamTileFile.Encoding.valueOf(encoding.name()),
                    alphaDepth,
                    alphaEncoding);
        }

        private WorldStreamTileFile.Source wire() {
            return new WorldStreamTileFile.Source(
                    fileDataId,
                    contentKey,
                    consumedLength,
                    declaredLength,
                    sourceLimit,
                    originalSide,
                    mipLevel,
                    mipFlags,
                    encoding == null ? null : WorldStreamTileFile.Encoding.valueOf(encoding.name()),
                    alphaDepth,
                    alphaEncoding);
        }
    }

    /** Bounded detached descriptions. A cache key is deliberately not an input. */
    public static final class Data {
        private final String scope, profile, geometry;
        private final Outcome outcome;
        private final Reason reason;
        private final long[] coverage;
        private final float maxX, maxY;
        private final double uvError;
        private final WorldStreamTileFile.Source wdt, root, tex0;
        private final List<WorldStreamTileFile.Source> diffuse;

        public Data(
                String scopeSha256,
                String profile,
                String geometrySha256,
                Outcome outcome,
                Reason reason,
                long[] coverage,
                float nativeMaxX,
                float nativeMaxY,
                double maxUvError,
                Source wdt,
                Source root,
                Source tex0,
                List<Source> diffuse) {
            if (!hex(scopeSha256)
                    || !hex(geometrySha256)
                    || profile == null
                    || profile.isEmpty()
                    || profile.length() > 128) {
                throw new IllegalArgumentException("Packet description identity bounds");
            }
            for (int i = 0; i < profile.length(); i++) {
                if (profile.charAt(i) < 0x21 || profile.charAt(i) > 0x7e) {
                    throw new IllegalArgumentException("Packet profile ASCII");
                }
            }
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(diffuse);
            int count = diffuse.size();
            if (coverage.length != 4 || count > 64 || count < 0) {
                throw new IllegalArgumentException("Packet coverage/source bounds");
            }
            var captured = new WorldStreamTileFile.Source[count];
            for (int i = 0; i < count; i++) {
                captured[i] = Objects.requireNonNull(diffuse.get(i)).wire();
            }
            this.diffuse = List.of(captured);
            this.wdt = Objects.requireNonNull(wdt).wire();
            this.root = Objects.requireNonNull(root).wire();
            this.tex0 = tex0 == null ? null : tex0.wire();
            this.scope = scopeSha256;
            this.profile = profile;
            this.geometry = geometrySha256;
            this.outcome = Objects.requireNonNull(outcome);
            this.reason = Objects.requireNonNull(reason);
            this.coverage = coverage.clone();
            maxX = nativeMaxX;
            maxY = nativeMaxY;
            uvError = maxUvError;
        }

        private WorldStreamTileFile.Header header(
                WorldStreamTransport.PacketDestination.Context context,
                TerrainScene.Tile tile,
                String materialDigest,
                WorldStreamObjectsFile.Reference objects) {
            return new WorldStreamTileFile.Header(
                    context.nonce(),
                    scope,
                    context.transaction(),
                    context.lease(),
                    tile.x(),
                    tile.y(),
                    tile.chunks(),
                    tile.vertexCount(),
                    tile.indexCount(),
                    geometry,
                    materialDigest,
                    WorldStreamTileFile.Outcome.valueOf(outcome.name()),
                    WorldStreamTileFile.Reason.valueOf(reason.name()),
                    coverage,
                    maxX,
                    maxY,
                    uvError,
                    wdt,
                    root,
                    tex0,
                    diffuse,
                    objects);
        }
    }

    /**
     * Detached validated BASIC_EXTERIOR_V1 object set for one tile: shared model meshes in local
     * WMO space, RGBA8 images and per-placement double transforms in native terrain axes (x north,
     * y west, z up). Construction is structural only; the application's closed bridge is its sole
     * producer.
     */
    public static final class Exterior {
        /** Per-tile draw budget: the sum over placements of their model's ranges. */
        public static final int MAX_DRAWS = WorldStreamObjectsFile.MAX_DRAWS;

        private final WorldStreamObjectsFile.Data data;

        private Exterior(WorldStreamObjectsFile.Data data) {
            this.data = data;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int placements() {
            return data.placementCount();
        }

        public int draws() {
            return data.draws;
        }

        public int bytes() {
            return data.bytes();
        }

        WorldStreamObjectsFile.Data data() {
            return data;
        }

        /**
         * Pick geometry compiled from exactly the validated tables this set uploads: placement
         * transforms, drawn ranges and the alpha of cutout and blended images.
         */
        public com.vibeminer.rendering.world.WorldObjectPicking.Tile pickTile() {
            return com.vibeminer.rendering.world.WorldObjectPicking.Tile.of(
                    data.models,
                    data.meshes,
                    data.ranges,
                    data.placementModels,
                    data.placements,
                    data.images,
                    data.vertices,
                    data.indices,
                    data.pixels);
        }

        /** Bounded accumulation; every add is checked against the per-tile file profile. */
        public static final class Builder {
            private final java.util.ArrayList<int[]> models = new java.util.ArrayList<>();
            private final java.util.ArrayList<int[]> meshes = new java.util.ArrayList<>();
            private final java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>();
            private final java.util.ArrayList<double[]> placements = new java.util.ArrayList<>();
            private final java.util.ArrayList<Integer> placementModels =
                    new java.util.ArrayList<>();
            private final java.util.ArrayList<int[]> images = new java.util.ArrayList<>();
            private final java.util.ArrayList<float[]> vertexBlocks = new java.util.ArrayList<>();
            private final java.util.ArrayList<int[]> colourBlocks = new java.util.ArrayList<>();
            private final java.util.ArrayList<short[]> indexBlocks = new java.util.ArrayList<>();
            private final java.util.ArrayList<byte[]> pixelBlocks = new java.util.ArrayList<>();
            private int vertices, indices, imageBytes, draws;

            /** RGBA8 bytes of every image so far once decoded (the fallback's GPU size). */
            private long decodedBytes;

            private boolean built;

            private Builder() {}

            /**
             * Whether one more whole model with these totals still fits the per-tile file profile,
             * so a caller can add a model completely or not at all. {@code pixelCount} counts RGBA8
             * chain texels (4 bytes each), an upper bound for compressed chains. Image sides are
             * checked by {@link #image}; this checks counts only.
             */
            public boolean fits(
                    int imageCount,
                    long pixelCount,
                    int meshCount,
                    int vertexCount,
                    int indexCount,
                    int rangeCount) {
                open();
                return imageCount >= 0
                        && pixelCount >= 0
                        && meshCount >= 1
                        && vertexCount >= 0
                        && indexCount >= 0
                        && rangeCount >= meshCount
                        && models.size() < WorldStreamObjectsFile.MAX_MODELS
                        && imageCount <= WorldStreamObjectsFile.MAX_IMAGES - images.size()
                        && pixelCount * 4 <= WorldStreamObjectsFile.MAX_IMAGE_BYTES - imageBytes
                        && pixelCount * 4 <= WorldStreamObjectsFile.MAX_DECODED_BYTES - decodedBytes
                        && meshCount <= WorldStreamObjectsFile.MAX_MESHES - meshes.size()
                        && vertexCount <= WorldStreamObjectsFile.MAX_VERTICES - vertices
                        && indexCount <= WorldStreamObjectsFile.MAX_INDICES - indices
                        && rangeCount <= WorldStreamObjectsFile.MAX_RANGES - ranges.size();
            }

            /** Placements that can still be added. */
            public int placementRoom() {
                return WorldStreamObjectsFile.MAX_PLACEMENTS - placements.size();
            }

            /** Draw calls that placements can still add (the sum of their models' ranges). */
            public int drawRoom() {
                return MAX_DRAWS - draws;
            }

            /** Draw calls one placement of {@code model} costs: its ranges added so far. */
            public int drawCost(int model) {
                if (model < 0 || model >= models.size()) {
                    throw new IllegalArgumentException("Exterior placement model");
                }
                int first = models.get(model)[0], count = models.get(model)[1], cost = 0;
                for (int mesh = first; mesh < first + count; mesh++) {
                    cost += meshes.get(mesh)[5];
                }
                return cost;
            }

            /** Placements added so far. */
            public int placementCount() {
                return placements.size();
            }

            /**
             * Copies one RGBA8 image given as its full packed mip chain (power-of-two sides at most
             * 256, see {@link com.vibeminer.rendering.world.ObjectTextureMips}); returns its index.
             */
            public int image(int width, int height, java.nio.ByteBuffer rgba) {
                return image(
                        width,
                        height,
                        com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8,
                        rgba);
            }

            /**
             * Copies one image given as its full packed mip chain in {@code format} (see {@link
             * com.vibeminer.rendering.world.ObjectTextureFormats}): RGBA8 texels or the source's
             * own BC1, BC2 or BC3 blocks, carried byte for byte; returns its index. Compressed
             * images need both sides at least 4, and a BC1 image may only be drawn by opaque ranges
             * (checked by {@link #build}).
             */
            public int image(int width, int height, int format, java.nio.ByteBuffer chain) {
                open();
                Objects.requireNonNull(chain);
                if (width < 1
                        || height < 1
                        || width > WorldStreamObjectsFile.MAX_IMAGE_SIDE
                        || height > WorldStreamObjectsFile.MAX_IMAGE_SIDE
                        || !com.vibeminer.rendering.world.ObjectTextureFormats.valid(format)
                        || com.vibeminer.rendering.world.ObjectTextureFormats.compressed(format)
                                && (width < WorldStreamObjectsFile.MIN_COMPRESSED_SIDE
                                        || height < WorldStreamObjectsFile.MIN_COMPRESSED_SIDE)) {
                    throw new IllegalArgumentException("Exterior image exceeds file profile");
                }
                int count =
                        com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                                format, width, height);
                long decoded =
                        4L
                                * com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(
                                        width, height);
                if (images.size() == WorldStreamObjectsFile.MAX_IMAGES
                        || count > WorldStreamObjectsFile.MAX_IMAGE_BYTES - imageBytes
                        || decoded > WorldStreamObjectsFile.MAX_DECODED_BYTES - decodedBytes
                        || chain.remaining() != count) {
                    throw new IllegalArgumentException("Exterior image exceeds file profile");
                }
                var copy = new byte[count];
                chain.duplicate().get(copy);
                images.add(new int[] {width, height, format});
                pixelBlocks.add(copy);
                imageBytes += count;
                decodedBytes += decoded;
                return images.size() - 1;
            }

            /** Starts a new shared model; returns its index. Following meshes belong to it. */
            public int model() {
                open();
                if (models.size() == WorldStreamObjectsFile.MAX_MODELS) {
                    throw new IllegalArgumentException("Exterior models exceed file profile");
                }
                models.add(new int[] {meshes.size(), 0});
                return models.size() - 1;
            }

            /**
             * Copies one mesh of the current model: 8 floats per vertex (position, normal, uv),
             * mesh-relative indices and opaque (first index, index count, image) range triples.
             */
            public void mesh(
                    java.nio.FloatBuffer vertexData,
                    java.nio.IntBuffer indexData,
                    int[] drawRanges) {
                Objects.requireNonNull(drawRanges);
                mesh(vertexData, indexData, drawRanges, new int[drawRanges.length / 3]);
            }

            /**
             * As {@link #mesh(java.nio.FloatBuffer, java.nio.IntBuffer, int[])}, with one material
             * class per range: 0 opaque, 1 alpha-key cutout, 2 alpha blended, 3 additive, each
             * optionally with the unlit flag 0x100 (#367) and the two-sided flag 0x200 (#375).
             */
            public void mesh(
                    java.nio.FloatBuffer vertexData,
                    java.nio.IntBuffer indexData,
                    int[] drawRanges,
                    int[] blends) {
                mesh(vertexData, indexData, drawRanges, blends, java.nio.IntBuffer.allocate(0));
            }

            /**
             * As {@link #mesh(java.nio.FloatBuffer, java.nio.IntBuffer, int[], int[])}, with vertex
             * colours: none (all zero) or one {@code r | g << 8 | b << 16} per vertex (#375).
             */
            public void mesh(
                    java.nio.FloatBuffer vertexData,
                    java.nio.IntBuffer indexData,
                    int[] drawRanges,
                    int[] blends,
                    java.nio.IntBuffer colourData) {
                open();
                Objects.requireNonNull(vertexData);
                Objects.requireNonNull(indexData);
                Objects.requireNonNull(drawRanges);
                Objects.requireNonNull(blends);
                Objects.requireNonNull(colourData);
                if (blends.length * 3 != drawRanges.length) {
                    throw new IllegalArgumentException("Exterior range classes");
                }
                int vertexFloats = vertexData.remaining(), indexCount = indexData.remaining();
                int count = vertexFloats / WorldStreamObjectsFile.VERTEX_FLOATS;
                if (colourData.remaining() != 0 && colourData.remaining() != count) {
                    throw new IllegalArgumentException("Exterior vertex colours");
                }
                if (models.isEmpty()
                        || meshes.size() == WorldStreamObjectsFile.MAX_MESHES
                        || vertexFloats % WorldStreamObjectsFile.VERTEX_FLOATS != 0
                        || count > WorldStreamObjectsFile.MAX_MESH_VERTICES
                        || count > WorldStreamObjectsFile.MAX_VERTICES - vertices
                        || indexCount > WorldStreamObjectsFile.MAX_INDICES - indices
                        || drawRanges.length % 3 != 0
                        || drawRanges.length / 3
                                > WorldStreamObjectsFile.MAX_RANGES - ranges.size()) {
                    throw new IllegalArgumentException("Exterior mesh exceeds file profile");
                }
                var floats = new float[vertexFloats];
                vertexData.duplicate().get(floats);
                var colours = new int[count];
                colourData.duplicate().get(colours, 0, colourData.remaining());
                var shorts = new short[indexCount];
                var source = indexData.duplicate();
                for (int i = 0; i < indexCount; i++) {
                    int index = source.get();
                    if (index < 0 || index >= count) {
                        throw new IllegalArgumentException("Exterior mesh index");
                    }
                    shorts[i] = (short) index;
                }
                meshes.add(
                        new int[] {
                            vertices,
                            count,
                            indices,
                            indexCount,
                            ranges.size(),
                            drawRanges.length / 3
                        });
                for (int r = 0; r < drawRanges.length; r += 3) {
                    ranges.add(
                            new int[] {
                                drawRanges[r], drawRanges[r + 1], drawRanges[r + 2], blends[r / 3]
                            });
                }
                vertexBlocks.add(floats);
                colourBlocks.add(colours);
                indexBlocks.add(shorts);
                vertices += count;
                indices += indexCount;
                models.get(models.size() - 1)[1]++;
            }

            /**
             * One placement of a model; native terrain axes, scale and the reviewed ADT heading
             * rule from cos/sin.
             */
            public void placement(
                    int model, double x, double y, double z, double scale, double cos, double sin) {
                placement(
                        model, x, y, z, scale, new double[] {-cos, sin, 0, -sin, -cos, 0, 0, 0, 1});
            }

            /** One placement with a row-major rotation (rows are output X, Y, Z). */
            public void placement(
                    int model, double x, double y, double z, double scale, double[] rotation) {
                open();
                Objects.requireNonNull(rotation);
                if (placements.size() == WorldStreamObjectsFile.MAX_PLACEMENTS
                        || rotation.length != 9) {
                    throw new IllegalArgumentException("Exterior placements exceed file profile");
                }
                if (model >= 0 && model < models.size()) {
                    draws += drawCost(model);
                }
                placementModels.add(model);
                var row = new double[WorldStreamObjectsFile.PLACEMENT_DOUBLES];
                row[0] = x;
                row[1] = y;
                row[2] = z;
                row[3] = scale;
                System.arraycopy(rotation, 0, row, 4, 9);
                placements.add(row);
            }

            /** Validates the whole set against the reader's exact checks. */
            public Exterior build() {
                open();
                built = true;
                var modelTable = new int[models.size() * 2];
                for (int i = 0; i < models.size(); i++) {
                    modelTable[i * 2] = models.get(i)[0];
                    modelTable[i * 2 + 1] = models.get(i)[1];
                }
                var meshTable = new int[meshes.size() * 6];
                for (int i = 0; i < meshes.size(); i++) {
                    System.arraycopy(meshes.get(i), 0, meshTable, i * 6, 6);
                }
                int stride = WorldStreamObjectsFile.RANGE_INTS;
                var rangeTable = new int[ranges.size() * stride];
                for (int i = 0; i < ranges.size(); i++) {
                    System.arraycopy(ranges.get(i), 0, rangeTable, i * stride, stride);
                }
                int doubles = WorldStreamObjectsFile.PLACEMENT_DOUBLES;
                var models = new int[placementModels.size()];
                var transforms = new double[placements.size() * doubles];
                for (int i = 0; i < models.length; i++) {
                    models[i] = placementModels.get(i);
                    System.arraycopy(placements.get(i), 0, transforms, i * doubles, doubles);
                }
                int imageInts = WorldStreamObjectsFile.IMAGE_INTS;
                var imageTable = new int[images.size() * imageInts];
                for (int i = 0; i < images.size(); i++) {
                    System.arraycopy(images.get(i), 0, imageTable, i * imageInts, imageInts);
                }
                var vertexData = new float[vertices * WorldStreamObjectsFile.VERTEX_FLOATS];
                int at = 0;
                for (var block : vertexBlocks) {
                    System.arraycopy(block, 0, vertexData, at, block.length);
                    at += block.length;
                }
                var colourData = new int[vertices];
                at = 0;
                for (var block : colourBlocks) {
                    System.arraycopy(block, 0, colourData, at, block.length);
                    at += block.length;
                }
                var indexData = new short[indices];
                at = 0;
                for (var block : indexBlocks) {
                    System.arraycopy(block, 0, indexData, at, block.length);
                    at += block.length;
                }
                var pixelData = new byte[imageBytes];
                at = 0;
                for (var block : pixelBlocks) {
                    System.arraycopy(block, 0, pixelData, at, block.length);
                    at += block.length;
                }
                return new Exterior(
                        new WorldStreamObjectsFile.Data(
                                modelTable,
                                meshTable,
                                rangeTable,
                                models,
                                transforms,
                                imageTable,
                                vertexData,
                                colourData,
                                indexData,
                                pixelData));
            }

            private void open() {
                if (built) {
                    throw new IllegalStateException("Exterior builder already built");
                }
            }
        }
    }

    /**
     * Caller-owned published objects file for one destination. Close deletes it unless a terrain
     * packet adopted it; after adoption the packet owner deletes both files together.
     */
    public static final class ObjectsFile implements AutoCloseable {
        private final Path path;
        private final WorldStreamObjectsFile.Reference reference;
        private final WorldStreamTransport.PacketDestination destination;
        private boolean adopted, closed;

        private ObjectsFile(
                Path path,
                WorldStreamObjectsFile.Reference reference,
                WorldStreamTransport.PacketDestination destination) {
            this.path = path;
            this.reference = reference;
            this.destination = destination;
        }

        public int bytes() {
            return reference.length();
        }

        private synchronized WorldStreamObjectsFile.Reference claim(
                WorldStreamTransport.PacketDestination target) {
            if (adopted || closed || target != destination) {
                throw new IllegalStateException("Foreign, adopted or closed objects file");
            }
            return reference;
        }

        private synchronized void adopted() {
            adopted = true;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (adopted || closed) {
                    return;
                }
                closed = true;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        }
    }

    /**
     * Off-FX structural IO: publishes this destination's {@code tile-<txn>.wso} before its terrain
     * packet is written. Only sessions in the exterior profile admit it.
     */
    public static ObjectsFile writeObjectsUnadvertised(
            WorldStreamTransport.PacketDestination destination,
            Exterior exterior,
            BooleanSupplier stopping)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(exterior);
        Objects.requireNonNull(stopping);
        if (!destination.exteriorObjects()) {
            throw new IllegalStateException("Session profile has no exterior objects");
        }
        var context = destination.context();
        var identity =
                new WorldStreamObjectsFile.Identity(
                        context.nonce(),
                        context.scope(),
                        context.transaction(),
                        context.lease(),
                        context.x(),
                        context.y());
        var directory = context.path().getParent();
        var reference = WorldStreamObjectsFile.write(directory, identity, exterior.data, stopping);
        return new ObjectsFile(
                directory.resolve(WorldStreamObjectsFile.basename(context.transaction())),
                reference,
                destination);
    }

    /**
     * Detached validated copy of one actual Source water preparation for a single terrain tile.
     * Construction is structural only; the application's closed bridge is its sole producer.
     */
    public static final class Water {
        public static final int MAX_CELLS = WorldStreamTileFile.MAX_WATER_CELLS;
        private final WorldStreamTileFile.Water wire;

        /** Copies every array. Native axes: x north, y west, z up; fixed per-cell indices. */
        public Water(float[] positions, byte[] depths, int[] indices, int layers) {
            wire =
                    new WorldStreamTileFile.Water(
                            positions.clone(), depths.clone(), indices.clone(), layers);
        }

        public int cells() {
            return wire.cells();
        }

        /** Copies: native x north, y west, z up per vertex. */
        public float[] positions() {
            return wire.positions().clone();
        }

        public byte[] depths() {
            return wire.depths().clone();
        }

        public int[] indices() {
            return wire.indices().clone();
        }
    }

    /**
     * One tile's splatting inputs for the writer (issue #174 step 2): its distinct layer diffuses
     * (file id and content key), the 16 x 16 chunk table of four indices into that list (255 for
     * none), the RGBA8 mask map (512 or 1024 per side, by tier), and a source of a diffuse's 256 x
     * 256 BC1 layer chain ({@link com.vibeminer.rendering.world.TerrainLayerBlocks}), consulted
     * only for layers new to the shared array.
     */
    public record SplatInput(
            long[] fileIds,
            String[] contentKeys,
            byte[] table,
            ByteBuffer alpha,
            java.util.function.LongFunction<java.util.Optional<Diffuse>> decode) {
        public SplatInput {
            Objects.requireNonNull(fileIds);
            Objects.requireNonNull(contentKeys);
            Objects.requireNonNull(table);
            Objects.requireNonNull(alpha);
            Objects.requireNonNull(decode);
            if (fileIds.length != contentKeys.length
                    || fileIds.length > 64
                    || table.length != WorldStreamTileFile.SPLAT_TABLE_BYTES
                    || alpha.remaining() != WorldStreamTileFile.SPLAT_ALPHA_BYTES
                            && alpha.remaining()
                                    != com.vibeminer.rendering.world.TerrainTileSplat
                                            .MAX_ALPHA_BYTES) {
                throw new IllegalArgumentException("Splat input shape");
            }
        }
    }

    /** One layer diffuse as its {@link com.vibeminer.rendering.world.TerrainLayerBlocks} chain. */
    public record Diffuse(ByteBuffer chain) {
        public Diffuse {
            Objects.requireNonNull(chain);
            if (chain.remaining() != WorldStreamTileFile.SPLAT_LAYER_BYTES) {
                throw new IllegalArgumentException("Splat layer chain size");
            }
        }
    }

    /** Source R owns this unadvertised file until its independently pinned one-shot MOVE. */
    public static final class PacketFile implements AutoCloseable {
        private final WorldStreamPacketFile file;
        private final float[] footprint;
        private WorldStreamTransport.PacketDestination destination;
        private WorldStreamTileFile.Header writtenHeader;
        private WorldStreamTileFile.Splat splat;
        private boolean waterAttached, waterDropped;

        /**
         * Resolves this textured packet's splatting inputs against the session's shared layer array
         * and holds the trailer for {@link #move}. Keys without a layer, beyond the per-packet
         * upload cap, or whose decode fails keep the atlas (255); never fails the tile.
         */
        public synchronized void splat(SplatInput input) {
            Objects.requireNonNull(input);
            if (destination == null) {
                throw new IllegalStateException("Packet file already moved or closed");
            }
            if (writtenHeader.outcome() != WorldStreamTileFile.Outcome.TEXTURED) {
                return;
            }
            var keys = new java.util.ArrayList<WorldStreamDiffuseArray.Key>();
            for (int i = 0; i < input.fileIds().length; i++) {
                keys.add(
                        new WorldStreamDiffuseArray.Key(
                                input.fileIds()[i], input.contentKeys()[i]));
            }
            var binding = destination.bindSplat(keys);
            int[] layers = binding.layers().clone();
            var uploadLayers = new java.util.ArrayList<Integer>();
            var uploadPixels = new java.util.ArrayList<byte[]>();
            for (int layer : binding.uploads()) {
                int index = -1;
                for (int i = 0; i < layers.length; i++) {
                    if (layers[i] == layer) {
                        index = i;
                    }
                }
                byte[] pixels = null;
                try {
                    var decoded = input.decode().apply(input.fileIds()[index]);
                    if (decoded.isPresent()) {
                        pixels = new byte[WorldStreamTileFile.SPLAT_LAYER_BYTES];
                        decoded.get().chain().duplicate().get(pixels);
                    }
                } catch (RuntimeException failed) {
                    if (failed instanceof java.util.concurrent.CancellationException) {
                        throw failed;
                    }
                    pixels = null;
                }
                if (pixels == null) {
                    destination.dropSplatUpload(layer);
                    layers[index] = -1;
                    continue;
                }
                uploadLayers.add(layer);
                uploadPixels.add(pixels);
            }
            byte[] table = gpuTable(input.table(), layers);
            byte[] alpha = new byte[input.alpha().remaining()];
            input.alpha().duplicate().get(alpha);
            splat =
                    new WorldStreamTileFile.Splat(
                            table,
                            alpha,
                            uploadLayers.stream().mapToInt(Integer::intValue).toArray(),
                            uploadPixels.toArray(byte[][]::new));
        }

        /**
         * The packet's chunk table of array layers from the tile's table of diffuse list indices
         * and each diffuse's array layer ({@code -1} when it has none: past the per-packet cap, or
         * its decode failed). A chunk keeps the atlas, all four entries {@link
         * WorldStreamTileFile#SPLAT_NONE}, when any layer it uses has no array layer: the shader
         * blends a chunk's layers by its mask, so it cannot draw one with a layer missing (issue
         * #174 step 2b-2).
         */
        static byte[] gpuTable(byte[] source, int[] layers) {
            byte[] table = new byte[WorldStreamTileFile.SPLAT_TABLE_BYTES];
            for (int chunk = 0; chunk < table.length / 4; chunk++) {
                boolean whole = true;
                for (int i = chunk * 4; i < chunk * 4 + 4; i++) {
                    int entry = source[i] & 255;
                    whole &=
                            entry == WorldStreamTileFile.SPLAT_NONE
                                    || (entry < layers.length && layers[entry] >= 0);
                }
                for (int i = chunk * 4; i < chunk * 4 + 4; i++) {
                    int entry = source[i] & 255;
                    table[i] =
                            (byte)
                                    (whole && entry != WorldStreamTileFile.SPLAT_NONE
                                            ? layers[entry]
                                            : WorldStreamTileFile.SPLAT_NONE);
                }
            }
            return table;
        }

        /** Descriptive: the splat trailer this packet will carry, or null. */
        synchronized WorldStreamTileFile.Splat splatTrailer() {
            return splat;
        }

        /** Descriptive: whether the moved packet carries a water trailer. */
        public synchronized boolean waterAttached() {
            return waterAttached;
        }

        /** Descriptive: a water append failed and was rolled back to terrain only. */
        public synchronized boolean waterDropped() {
            return waterDropped;
        }

        private PacketFile(
                WorldStreamPacketFile file,
                WorldStreamTransport.PacketDestination destination,
                WorldStreamTileFile.Header writtenHeader,
                float[] footprint) {
            this.file = file;
            this.footprint = footprint;
            this.destination = destination;
            this.writtenHeader = writtenHeader;
        }

        /** Off FX, after source final checks, inside the source's accepted resource callback. */
        public WorldStreamTransport.OwnedPacket move() {
            return move(null, () -> false);
        }

        /**
         * As {@link #move()}, first appending optional water to this still-unadvertised packet. A
         * water IO failure that restores the terrain-only file degrades to terrain only; water is
         * optional and never removes terrain. Cancellation still propagates.
         */
        public WorldStreamTransport.OwnedPacket move(Water water, BooleanSupplier stopping) {
            Objects.requireNonNull(stopping);
            WorldStreamPacketFile.Moved moved;
            WorldStreamTransport.PacketDestination target;
            WorldStreamTileFile.Header header;
            synchronized (this) {
                if (destination == null) {
                    throw new IllegalStateException("Packet file already moved or closed");
                }
                WorldStreamTileFile.Expected replacement = null;
                boolean watered = false;
                if (splat != null) {
                    try {
                        replacement =
                                WorldStreamTileFile.appendSplat(
                                        file.path(),
                                        file.expected(),
                                        writtenHeader,
                                        splat,
                                        stopping);
                    } catch (IOException failed) {
                        throw new java.io.UncheckedIOException(failed);
                    }
                }
                if (water != null && !WorldStreamTileFile.onFootprint(water.wire, footprint)) {
                    // Every reader check runs here first: off-tile water never costs terrain.
                    waterDropped = true;
                } else if (water != null) {
                    var before = replacement != null ? replacement : file.expected();
                    var path = file.path();
                    try {
                        replacement =
                                WorldStreamTileFile.appendWater(
                                        path, before, writtenHeader, water.wire, stopping);
                        watered = true;
                    } catch (WorldStreamTileFile.PacketChanged changed) {
                        throw new java.io.UncheckedIOException(changed);
                    } catch (IOException
                            | IllegalArgumentException
                            | java.nio.BufferOverflowException failed) {
                        // Pre-write refusals leave the file untouched; IO failures were truncated
                        // back by appendWater. Either way the terrain-only file is exact.
                        if (stopping.getAsBoolean()) {
                            var canceled = new java.util.concurrent.CancellationException();
                            canceled.addSuppressed(failed);
                            throw canceled;
                        }
                        // The terrain (and splat) file is exact again: keep what preceded water.
                        replacement = splat != null ? replacement : null;
                        waterDropped = true;
                    }
                }
                try {
                    moved = replacement == null ? file.move() : file.moveReplacing(replacement);
                } catch (IOException failed) {
                    throw new java.io.UncheckedIOException(failed);
                }
                waterAttached = watered;
                target = destination;
                header = writtenHeader;
                destination = null;
                writtenHeader = null;
            }
            try {
                return target.adopt(moved, header);
            } catch (RuntimeException | Error failure) {
                try {
                    moved.close();
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                destination = null;
                writtenHeader = null;
            }
            file.close();
        }
    }

    /**
     * Off-FX synchronous structural IO inside the actual source admission. The caller pins exact
     * geometry/atlas bytes through return. Only its source engine may authorize the final handoff.
     */
    public static PacketFile writeUnadvertised(
            WorldStreamTransport.PacketDestination destination,
            Data data,
            TerrainScene.Tile geometry,
            TerrainTextureBinding texture,
            BooleanSupplier stopping)
            throws IOException {
        return writeUnadvertised(destination, data, geometry, texture, null, stopping);
    }

    /**
     * As above, naming an optional already published objects file in the tile header. On success
     * the packet owner adopts it; on failure the caller still owns and closes it.
     */
    public static PacketFile writeUnadvertised(
            WorldStreamTransport.PacketDestination destination,
            Data data,
            TerrainScene.Tile geometry,
            TerrainTextureBinding texture,
            ObjectsFile objects,
            BooleanSupplier stopping)
            throws IOException {
        Objects.requireNonNull(destination);
        var reference = objects == null ? null : objects.claim(destination);
        Objects.requireNonNull(data);
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(stopping);
        var context = destination.context();
        if (!data.scope.equals(context.scope())
                || geometry.x() != context.x()
                || geometry.y() != context.y()) {
            throw new IllegalArgumentException("Packet source scope/tile differs from destination");
        }
        if (stopping.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
        WorldStreamTileFile.Header header;
        if (data.outcome == Outcome.TEXTURED) {
            Objects.requireNonNull(texture);
            var mapping = texture.metadata();
            if (mapping.tileX() != geometry.x()
                    || mapping.tileY() != geometry.y()
                    || !mapping.rootGeometrySha256().equals(data.geometry)
                    || mapping.sources().rootFileDataId() != data.root.id()
                    || !mapping.sources().rootContentKey().equals(data.root.contentKey())
                    || data.tex0 == null
                    || mapping.sources().tex0FileDataId() != data.tex0.id()
                    || !mapping.sources().tex0ContentKey().equals(data.tex0.contentKey())
                    || Float.floatToRawIntBits(mapping.nativeMaxX())
                            != Float.floatToRawIntBits(data.maxX)
                    || Float.floatToRawIntBits(mapping.nativeMaxY())
                            != Float.floatToRawIntBits(data.maxY)
                    || Double.doubleToRawLongBits(mapping.maxSourceUvErrorTexels())
                            != Double.doubleToRawLongBits(data.uvError)) {
                throw new IllegalArgumentException("Packet material differs from checked mapping");
            }
            var coverage = mapping.rectangleCoverage();
            for (int i = 0; i < 4; i++) {
                if (coverage.get(i) != data.coverage[i]) {
                    throw new IllegalArgumentException(
                            "Packet coverage differs from checked mapping");
                }
            }
            // The template's opaque digest is never published; the actual payload identity is
            // computed incrementally from these exact descriptions and pinned pixels.
            var template = data.header(context, geometry, "ff".repeat(32), reference);
            String digest =
                    WorldStreamMaterialDigest.compute(
                            template, texture.pixels(), data.profile, stopping);
            header = data.header(context, geometry, digest, reference);
        } else {
            if (texture != null) {
                throw new IllegalArgumentException("Height packet has an atlas");
            }
            header = data.header(context, geometry, null, reference);
        }
        WorldStreamTileFile.Expected expected = null;
        WorldStreamPacketFile owner = null;
        try {
            expected =
                    WorldStreamTileFile.write(
                            context.path().getParent(),
                            header,
                            geometry,
                            texture == null ? null : texture.pixels(),
                            stopping);
            owner =
                    WorldStreamPacketFile.finalized(
                            expected,
                            context.path(),
                            context.identity(),
                            objects == null ? null : objects.path);
            if (objects != null) {
                objects.adopted();
            }
            return new PacketFile(
                    owner, destination, header, WorldStreamTileFile.footprint(geometry));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (owner != null) {
                    owner.close();
                } else if (expected != null) {
                    Files.deleteIfExists(context.path());
                }
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static boolean hex(String value) {
        return value != null && value.length() == 64 && value.matches("[0-9a-f]{64}");
    }

    private WorldStreamTileWriter() {}
}
