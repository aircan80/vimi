package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpFormatException;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WmoGroupSpans;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import com.vibeminer.rendering.world.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BASIC_EXTERIOR_V1 source factory (issue #172, slice 1 of docs/world-outdoor-objects-proposal.md).
 *
 * <p>One serialized source operation per recorded ADT tile turns that tile's obj0 MODF placements
 * into an immutable, neutral heap object set: shared per-root exterior meshes in local WMO space,
 * opaque texture1 images and per-placement ADT transforms. Everything outside the documented
 * profile is omitted and counted by reason; nothing is guessed. Selection and omission depend only
 * on obj0 source order, never on a camera or on neighboring tiles. There is no packet, child,
 * renderer or GUI wiring in this slice, and no claim of client lighting or material parity.
 */
final class WorldExteriorObjects {
    static final String RECIPE = "BASIC_EXTERIOR_V1";
    static final int MAX_PLACEMENTS = 64, MAX_ROOTS = 24, MAX_GROUPS = 256, MAX_IMAGES = 64;
    static final int MAX_VERTICES = 98_304, MAX_INDICES = 294_912;
    static final int MAX_RANGES = 1024, MAX_MESHES = 256, MAX_PIXELS = 1 << 20;

    /** Position xyz, unit normal xyz, texture1 uv: 32 bytes per vertex. */
    static final int VERTEX_FLOATS = 8;

    /** Largest mip with both sides at most 256, falling to 128 or 64 near the pixel budget. */
    private static final int[] IMAGE_SIDES = {256, 128, 64};

    /** MODF flags: 0x2 LOD-capable, 0x4 scale-enable, 0x8 direct FileDataID (required). */
    private static final int DIRECT_ID = 0x8, SCALED = 0x4, LOD_CAPABLE = 0x2;

    /** MOGP exterior and interior flags; interior wins when both are present. */
    private static final long EXTERIOR = 0x8, INTERIOR = 0x2000;

    private static final WmoFormats.Limits ROOT_LIMITS =
            new WmoFormats.Limits(
                    1 << 20, 1 << 20, 256, 256, 4096, 65536, 131072, 256, 4, 4L << 20);
    private static final Blp2Types.Limits IMAGE_LIMITS =
            new Blp2Types.Limits(1 << 20, 8192, 65536, 256 << 10, 256 << 10);

    private WorldExteriorObjects() {}

    /**
     * Mutually exclusive outcome buckets. Placements: every obj0 MODF record lands in exactly one
     * PLACEMENT bucket. Roots: every distinct root that was read lands in one ROOT bucket (roots
     * never read because of the root cap only cap their placements). Groups and batches count LOD0
     * groups and their batches for roots that were not failed or capped. Images: every distinct
     * texture1 FileDataID that an otherwise drawable batch needed lands in one IMAGE bucket.
     * Doodads (MDDF) are not in this slice and are counted, not drawn.
     */
    enum Count {
        PLACEMENT_DRAWN,
        PLACEMENT_SHARED_DUPLICATE,
        PLACEMENT_UNSUPPORTED,
        PLACEMENT_CONFLICTING,
        PLACEMENT_CAPPED,
        PLACEMENT_FAILED_ROOT,
        PLACEMENT_EMPTY,
        ROOT_DRAWN,
        ROOT_FAILED,
        ROOT_UNAVAILABLE,
        ROOT_CAPPED,
        ROOT_EMPTY,
        GROUP_DRAWN,
        GROUP_INTERIOR,
        GROUP_UNSUPPORTED,
        GROUP_UNAVAILABLE,
        GROUP_EMPTY,
        BATCH_DRAWN,
        BATCH_UNSUPPORTED,
        BATCH_MISSING_IMAGE,
        IMAGE_DECODED,
        IMAGE_UNAVAILABLE,
        IMAGE_UNDECODABLE,
        IMAGE_CAPPED,
        DOODAD_NOT_IN_SLICE
    }

    /** One drawn placement. Model matrices are built later from these doubles, never here. */
    record Placement(
            int ordinal,
            long uniqueId,
            long rootId,
            int model,
            int flags,
            int doodadSet,
            int nameSet,
            double x,
            double y,
            double z,
            double scale,
            double cos,
            double sin) {}

    /** Index range into one mesh, drawn with one decoded image (index into images()). */
    record DrawRange(int firstIndex, int indexCount, int image, int material) {}

    /** One exterior LOD0 group of a root, in local WMO coordinates. */
    static final class Mesh {
        private final int groupOrdinal;
        private final long groupId, groupFlags;
        private final float[] vertices;
        private final int[] indices;
        private final List<DrawRange> ranges;

        private Mesh(
                int groupOrdinal,
                long groupId,
                long groupFlags,
                float[] vertices,
                int[] indices,
                List<DrawRange> ranges) {
            this.groupOrdinal = groupOrdinal;
            this.groupId = groupId;
            this.groupFlags = groupFlags;
            this.vertices = vertices;
            this.indices = indices;
            this.ranges = List.copyOf(ranges);
        }

        int groupOrdinal() {
            return groupOrdinal;
        }

        long groupId() {
            return groupId;
        }

        long groupFlags() {
            return groupFlags;
        }

        int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }

        FloatBuffer vertices() {
            return FloatBuffer.wrap(vertices).asReadOnlyBuffer();
        }

        IntBuffer indices() {
            return IntBuffer.wrap(indices).asReadOnlyBuffer();
        }

        List<DrawRange> ranges() {
            return ranges;
        }
    }

    /** Shared per-root geometry; several placements may reference one model. */
    record Model(long rootId, List<Mesh> meshes) {
        Model {
            meshes = List.copyOf(meshes);
        }
    }

    /** Decoded opaque RGBA8 (alpha forced to 255 for blend mode 0). */
    static final class Image {
        private final long id;
        private final int width, height, mipLevel;
        private final byte[] rgba;

        private Image(long id, int width, int height, int mipLevel, byte[] rgba) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.mipLevel = mipLevel;
            this.rgba = rgba;
        }

        long id() {
            return id;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        int mipLevel() {
            return mipLevel;
        }

        ByteBuffer rgba() {
            return ByteBuffer.wrap(rgba).asReadOnlyBuffer();
        }
    }

    /** Immutable neutral per-tile set. Close only retires access; it holds no native resource. */
    static final class TileObjects implements AutoCloseable {
        private final long mapId;
        private final int tileX, tileY;
        private final List<Model> models;
        private final List<Placement> placements;
        private final List<Image> images;
        private final int[] counts;
        private final int vertices, indices, ranges, pixels;
        private volatile boolean closed;

        private TileObjects(
                long mapId,
                int tileX,
                int tileY,
                List<Model> models,
                List<Placement> placements,
                List<Image> images,
                int[] counts) {
            this.mapId = mapId;
            this.tileX = tileX;
            this.tileY = tileY;
            this.models = List.copyOf(models);
            this.placements = List.copyOf(placements);
            this.images = List.copyOf(images);
            this.counts = counts.clone();
            int v = 0, i = 0, r = 0, m = 0, p = 0;
            for (var model : this.models) {
                for (var mesh : model.meshes()) {
                    v += mesh.vertexCount();
                    i += mesh.indices.length;
                    r += mesh.ranges.size();
                    m++;
                }
            }
            for (var image : this.images) {
                p += image.width * image.height;
            }
            require(
                    this.placements.size() <= MAX_PLACEMENTS
                            && this.models.size() <= MAX_ROOTS
                            && this.images.size() <= MAX_IMAGES
                            && v <= MAX_VERTICES
                            && i <= MAX_INDICES
                            && r <= MAX_RANGES
                            && m <= MAX_MESHES
                            && p <= MAX_PIXELS,
                    "EXTERIOR_TILE_BOUNDS");
            vertices = v;
            indices = i;
            ranges = r;
            pixels = p;
        }

        private void check() {
            if (closed) {
                throw new IllegalStateException("Exterior object set retired");
            }
        }

        String recipe() {
            check();
            return RECIPE;
        }

        long mapId() {
            check();
            return mapId;
        }

        int tileX() {
            check();
            return tileX;
        }

        int tileY() {
            check();
            return tileY;
        }

        List<Model> models() {
            check();
            return models;
        }

        List<Placement> placements() {
            check();
            return placements;
        }

        List<Image> images() {
            check();
            return images;
        }

        int count(Count count) {
            check();
            return counts[count.ordinal()];
        }

        int vertices() {
            check();
            return vertices;
        }

        int indices() {
            check();
            return indices;
        }

        int ranges() {
            check();
            return ranges;
        }

        int pixels() {
            check();
            return pixels;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Reads and qualifies one recorded tile's outdoor exteriors under BASIC_EXTERIOR. */
    static TileObjects prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldFormats.Tile tile,
            Cancellation token) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(tile);
        Objects.requireNonNull(token);
        return engine.withRecordedTile(
                scope,
                tile,
                WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                token,
                admission -> new Builder(admission).build());
    }

    private enum RootState {
        DRAWN,
        FAILED,
        CAPPED,
        EMPTY
    }

    private record Candidate(int firstIndex, int indexCount, int material, long image) {}

    private record PendingMesh(
            int ordinal,
            long id,
            long flags,
            float[] vertices,
            int[] indices,
            List<Candidate> ranges) {}

    private static final class Builder {
        private final WorldStreamScopes.TileAdmission admission;
        private final Cancellation token;
        private final int[] counts = new int[Count.values().length];
        private final List<Model> models = new ArrayList<>();
        private final List<Placement> placements = new ArrayList<>();
        private final List<Image> images = new ArrayList<>();
        private final Map<Long, Integer> imageSlots = new HashMap<>();
        private final Map<Long, RootState> rootStates = new HashMap<>();
        private final Map<Long, Integer> modelSlots = new HashMap<>();
        private int rootsRead, groupsRead, imagesRead, vertices, indices, ranges, meshes, pixels;

        Builder(WorldStreamScopes.TileAdmission admission) {
            this.admission = admission;
            this.token = admission.cancellation();
        }

        TileObjects build() {
            var recorded = admission.recorded();
            if (recorded.obj0Id() != 0) {
                WorldPlacements.Metadata metadata;
                try (var input = admission.readRecorded(WorldStreamScopes.RecordedInput.OBJ0)) {
                    metadata = WorldPlacements.readObj0(input.bytes(), token::isCanceled);
                }
                counts[Count.DOODAD_NOT_IN_SLICE.ordinal()] = metadata.doodads().size();
                place(metadata.worldObjects());
            }
            return new TileObjects(
                    admission.mapId(),
                    recorded.x(),
                    recorded.y(),
                    models,
                    placements,
                    images,
                    counts);
        }

        private void count(Count count) {
            counts[count.ordinal()]++;
        }

        private void place(List<WorldPlacements.WorldObject> records) {
            // Identical duplicate unique IDs share one placement; conflicting ones are all omitted.
            var byUid = new LinkedHashMap<Long, List<WorldPlacements.WorldObject>>();
            for (var record : records) {
                token.check();
                byUid.computeIfAbsent(record.uniqueId(), ignored -> new ArrayList<>()).add(record);
            }
            var conflicting = new HashMap<Long, Boolean>();
            for (var entry : byUid.entrySet()) {
                var first = entry.getValue().getFirst();
                boolean conflict = false;
                for (var other : entry.getValue()) {
                    conflict |= !sameDeclaration(first, other);
                }
                conflicting.put(entry.getKey(), conflict);
            }
            for (var record : records) {
                token.check();
                var group = byUid.get(record.uniqueId());
                if (conflicting.get(record.uniqueId())) {
                    count(Count.PLACEMENT_CONFLICTING);
                    continue;
                }
                if (group.getFirst() != record) {
                    count(Count.PLACEMENT_SHARED_DUPLICATE);
                    continue;
                }
                var transform = qualify(record);
                if (transform == null) {
                    count(Count.PLACEMENT_UNSUPPORTED);
                    continue;
                }
                long rootId = record.reference().value();
                if (placements.size() >= MAX_PLACEMENTS) {
                    count(Count.PLACEMENT_CAPPED);
                    continue;
                }
                var state = rootStates.get(rootId);
                if (state == null) {
                    if (rootsRead >= MAX_ROOTS) {
                        count(Count.PLACEMENT_CAPPED);
                        continue;
                    }
                    state = readRoot(rootId);
                    rootStates.put(rootId, state);
                }
                switch (state) {
                    case FAILED -> count(Count.PLACEMENT_FAILED_ROOT);
                    case CAPPED -> count(Count.PLACEMENT_CAPPED);
                    case EMPTY -> count(Count.PLACEMENT_EMPTY);
                    case DRAWN -> {
                        count(Count.PLACEMENT_DRAWN);
                        placements.add(
                                new Placement(
                                        record.ordinal(),
                                        record.uniqueId(),
                                        rootId,
                                        modelSlots.get(rootId),
                                        record.flags(),
                                        record.doodadSet(),
                                        record.nameSet(),
                                        transform.originX(),
                                        transform.originY(),
                                        transform.originZ(),
                                        transform.scale(),
                                        transform.cos(),
                                        transform.sin()));
                    }
                }
            }
        }

        private RootState readRoot(long rootId) {
            rootsRead++;
            WmoFormats.Root root;
            var input = derived(rootId, WorldStreamScopes.DerivedKind.WMO_ROOT);
            if (input == null) {
                count(Count.ROOT_UNAVAILABLE);
                return RootState.FAILED;
            }
            try (input) {
                root = WmoFormats.root(input.bytes(), ROOT_LIMITS, token::isCanceled);
            } catch (IllegalArgumentException malformed) {
                count(Count.ROOT_FAILED);
                return RootState.FAILED;
            }
            long groupCount = root.header().groupCount();
            if (root.textureNameBlockPresent()
                    || !root.gfidPresent()
                    || groupCount == 0
                    || root.gfidCount() == 0
                    || root.gfidCount() % groupCount != 0) {
                count(Count.ROOT_FAILED);
                return RootState.FAILED;
            }
            int lod0 = (int) groupCount, reads = 0;
            for (int g = 0; g < lod0; g++) {
                if (root.gfid(g) != 0) {
                    reads++;
                }
            }
            if (reads > MAX_GROUPS - groupsRead) {
                count(Count.ROOT_CAPPED);
                return RootState.CAPPED;
            }
            // Tentative per-root counts merge only if the root is not capped: caps never split.
            int[] local = new int[counts.length];
            var pending = new ArrayList<PendingMesh>();
            int tentativeVertices = 0, tentativeIndices = 0, tentativeRanges = 0;
            for (int g = 0; g < lod0; g++) {
                token.check();
                long groupId = root.gfid(g);
                if (groupId == 0) {
                    local[Count.GROUP_UNSUPPORTED.ordinal()]++;
                    continue;
                }
                groupsRead++;
                var mesh = readGroup(g, groupId, root, local);
                if (mesh == null) {
                    continue;
                }
                pending.add(mesh);
                tentativeVertices += mesh.vertices().length / VERTEX_FLOATS;
                for (var range : mesh.ranges()) {
                    tentativeIndices += range.indexCount();
                }
                tentativeRanges += mesh.ranges().size();
                if (tentativeVertices > MAX_VERTICES - vertices
                        || tentativeIndices > MAX_INDICES - indices
                        || tentativeRanges > MAX_RANGES - ranges
                        || pending.size() > MAX_MESHES - meshes) {
                    count(Count.ROOT_CAPPED);
                    return RootState.CAPPED;
                }
            }
            var built = new ArrayList<Mesh>(pending.size());
            for (var mesh : pending) {
                token.check();
                var drawn = new ArrayList<DrawRange>();
                var kept = new int[mesh.indices().length];
                int used = 0;
                for (var range : mesh.ranges()) {
                    int slot = image(range.image(), local);
                    if (slot < 0) {
                        local[Count.BATCH_MISSING_IMAGE.ordinal()]++;
                        continue;
                    }
                    System.arraycopy(
                            mesh.indices(), range.firstIndex(), kept, used, range.indexCount());
                    drawn.add(new DrawRange(used, range.indexCount(), slot, range.material()));
                    used += range.indexCount();
                    local[Count.BATCH_DRAWN.ordinal()]++;
                }
                if (drawn.isEmpty()) {
                    local[Count.GROUP_EMPTY.ordinal()]++;
                    continue;
                }
                local[Count.GROUP_DRAWN.ordinal()]++;
                built.add(
                        new Mesh(
                                mesh.ordinal(),
                                mesh.id(),
                                mesh.flags(),
                                mesh.vertices(),
                                Arrays.copyOf(kept, used),
                                drawn));
            }
            for (int i = 0; i < counts.length; i++) {
                counts[i] += local[i];
            }
            if (built.isEmpty()) {
                count(Count.ROOT_EMPTY);
                return RootState.EMPTY;
            }
            for (var mesh : built) {
                vertices += mesh.vertexCount();
                indices += mesh.indices.length;
                ranges += mesh.ranges.size();
                meshes++;
            }
            modelSlots.put(rootId, models.size());
            models.add(new Model(rootId, built));
            count(Count.ROOT_DRAWN);
            return RootState.DRAWN;
        }

        private PendingMesh readGroup(int ordinal, long id, WmoFormats.Root root, int[] local) {
            var input = derived(id, WorldStreamScopes.DerivedKind.WMO_GROUP);
            if (input == null) {
                local[Count.GROUP_UNAVAILABLE.ordinal()]++;
                return null;
            }
            var result = new PendingMesh[1];
            var outcome = new Count[1];
            try (input) {
                WmoGroupSpans.withGroup(
                        input.bytes(),
                        token::isCanceled,
                        borrow -> {
                            long flags = borrow.header().flags();
                            if ((flags & INTERIOR) != 0) {
                                outcome[0] = Count.GROUP_INTERIOR;
                                return;
                            }
                            if ((flags & EXTERIOR) == 0) {
                                outcome[0] = Count.GROUP_UNSUPPORTED;
                                return;
                            }
                            result[0] = copyGroup(ordinal, id, flags, root, borrow, local);
                            if (result[0] == null) {
                                outcome[0] = Count.GROUP_UNSUPPORTED;
                            }
                        });
            } catch (IllegalArgumentException malformed) {
                local[Count.GROUP_UNSUPPORTED.ordinal()]++;
                return null;
            }
            if (outcome[0] != null) {
                local[outcome[0].ordinal()]++;
                return null;
            }
            if (result[0].ranges().isEmpty()) {
                local[Count.GROUP_EMPTY.ordinal()]++;
                return null;
            }
            return result[0];
        }

        /**
         * Copies one exterior group; null (unsupported) for unusable normals or overlapping
         * batches.
         */
        private PendingMesh copyGroup(
                int ordinal,
                long id,
                long flags,
                WmoFormats.Root root,
                WmoGroupSpans.Borrow borrow,
                int[] local) {
            var positions = borrow.positions();
            var normals = borrow.normals();
            var uv = borrow.uv();
            int count = positions.size() / 3;
            float[] vertices = new float[count * VERTEX_FLOATS];
            for (int v = 0; v < count; v++) {
                if ((v & 1023) == 0) {
                    token.check();
                }
                double a = normals.get(v * 3), b = normals.get(v * 3 + 1);
                double c = normals.get(v * 3 + 2);
                double length = StrictMath.sqrt(a * a + b * b + c * c);
                if (!(length > 1e-6) || !Double.isFinite(length)) {
                    return null;
                }
                int at = v * VERTEX_FLOATS;
                vertices[at] = positions.get(v * 3);
                vertices[at + 1] = positions.get(v * 3 + 1);
                vertices[at + 2] = positions.get(v * 3 + 2);
                vertices[at + 3] = (float) (a / length);
                vertices[at + 4] = (float) (b / length);
                vertices[at + 5] = (float) (c / length);
                vertices[at + 6] = uv.get(v * 2);
                vertices[at + 7] = uv.get(v * 2 + 1);
            }
            var source = borrow.indices();
            int[] indices = new int[source.size()];
            for (int i = 0; i < indices.length; i++) {
                if ((i & 4095) == 0) {
                    token.check();
                }
                indices[i] = source.get(i);
            }
            // Overlapping recorded batches would draw triangles twice; refuse the whole group.
            var ordered = new ArrayList<>(borrow.batches());
            ordered.sort(java.util.Comparator.comparingLong(WmoFormats.Batch::firstIndex));
            long end = 0;
            for (var batch : ordered) {
                if (batch.firstIndex() < end) {
                    return null;
                }
                end = batch.firstIndex() + batch.indexCount();
            }
            var candidates = new ArrayList<Candidate>();
            for (var batch : borrow.batches()) {
                // Index ranges and vertex domains are already validated by WmoGroupSpans.
                var materials = root.materials();
                if (batch.flags() != 0 || batch.materialByte() >= materials.size()) {
                    local[Count.BATCH_UNSUPPORTED.ordinal()]++;
                    continue;
                }
                var material = materials.get(batch.materialByte());
                if (material.word(2) != 0 || material.word(3) == 0) {
                    local[Count.BATCH_UNSUPPORTED.ordinal()]++;
                    continue;
                }
                candidates.add(
                        new Candidate(
                                (int) batch.firstIndex(),
                                batch.indexCount(),
                                batch.materialByte(),
                                material.word(3)));
            }
            return new PendingMesh(ordinal, id, flags, vertices, indices, candidates);
        }

        /** Image slot for a texture1 FileDataID, or -1 when it is missing for any reason. */
        private int image(long id, int[] local) {
            var known = imageSlots.get(id);
            if (known != null) {
                return known;
            }
            int slot = -1;
            if (imagesRead >= MAX_IMAGES) {
                local[Count.IMAGE_CAPPED.ordinal()]++;
            } else {
                imagesRead++;
                slot = decode(id, local);
            }
            imageSlots.put(id, slot);
            return slot;
        }

        private int decode(long id, int[] local) {
            var input = derived(id, WorldStreamScopes.DerivedKind.WMO_IMAGE);
            if (input == null) {
                local[Count.IMAGE_UNAVAILABLE.ordinal()]++;
                return -1;
            }
            try (input) {
                byte[] raw = input.bytes();
                var header = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                if (raw.length < Blp2Decoder.HEADER_BYTES
                        || header.getInt(0) != 0x32504c42
                        || header.getInt(4) != 1) {
                    local[Count.IMAGE_UNDECODABLE.ordinal()]++;
                    return -1;
                }
                long width = Integer.toUnsignedLong(header.getInt(12));
                long height = Integer.toUnsignedLong(header.getInt(16));
                if (width == 0 || height == 0 || width > 8192 || height > 8192) {
                    local[Count.IMAGE_UNDECODABLE.ordinal()]++;
                    return -1;
                }
                int level = mipFor(width, height, MAX_PIXELS - pixels);
                if (level < 0) {
                    local[Count.IMAGE_CAPPED.ordinal()]++;
                    return -1;
                }
                Blp2Types.Image image;
                try {
                    image =
                            Blp2Decoder.decodeSynchronous(
                                    AssetSource.bytes(raw),
                                    level,
                                    IMAGE_LIMITS,
                                    token,
                                    Blp2Types.MipProfile.STRICT);
                } catch (BlpFormatException undecodable) {
                    local[Count.IMAGE_UNDECODABLE.ordinal()]++;
                    return -1;
                }
                int w = image.width(), h = image.height();
                if (image.mipLevel() != level
                        || w != Math.max(1, width >> level)
                        || h != Math.max(1, height >> level)) {
                    local[Count.IMAGE_UNDECODABLE.ordinal()]++;
                    return -1;
                }
                byte[] rgba = image.rgba();
                for (int i = 3; i < rgba.length; i += 4) {
                    rgba[i] = (byte) 255;
                }
                pixels += w * h;
                images.add(new Image(id, w, h, level, rgba));
                local[Count.IMAGE_DECODED.ordinal()]++;
                return images.size() - 1;
            }
        }

        /** Null only for an unavailable source (missing, protected or over the 1 MiB cap). */
        private WorldStreamScopes.Input derived(long id, WorldStreamScopes.DerivedKind kind) {
            if (id <= 0 || id > 0xffff_ffffL) {
                return null;
            }
            try {
                return admission.readDerivedCandidate(id, kind);
            } catch (WorldTerrainStreaming.Refusal refusal) {
                if (WorldSourceRefusals.Site.SOURCE_INPUT.name().equals(refusal.site())) {
                    return null;
                }
                throw refusal;
            }
        }
    }

    /** Placement policy; null means unsupported. The ADT heading rule is WorldBasicWmo's. */
    private static WorldBasicWmo.Transform qualify(WorldPlacements.WorldObject p) {
        int flags = p.flags();
        if ((flags & DIRECT_ID) == 0
                || (flags & ~(DIRECT_ID | SCALED | LOD_CAPABLE)) != 0
                || p.reference().kind() != WorldPlacements.ReferenceKind.FILE_DATA_ID
                || p.reference().value() <= 0
                || p.rotation().x() != 0
                || p.rotation().z() != 0) {
            return null;
        }
        double scale = 1.0;
        if ((flags & SCALED) != 0) {
            if (p.scale() <= 0) {
                return null;
            }
            scale = p.scale() / 1024.0;
        }
        var transform = new WorldBasicWmo.Transform(p, scale);
        for (double q :
                new double[] {transform.originX(), transform.originY(), transform.originZ()}) {
            if (!Double.isFinite(q) || Math.abs(q) > TerrainScene.MAX_COORDINATE) {
                return null;
            }
        }
        return transform;
    }

    /**
     * Mip level whose sides are both at most 256 (then 128, then 64) and whose pixels fit the
     * remaining tile budget; -1 when none fits. The decoder later refuses absent levels.
     */
    static int mipFor(long width, long height, long remainingPixels) {
        for (int side : IMAGE_SIDES) {
            int level = 0;
            while (Math.max(1, width >> level) > side || Math.max(1, height >> level) > side) {
                level++;
            }
            if (Math.max(1, width >> level) * Math.max(1, height >> level) <= remainingPixels) {
                return level;
            }
        }
        return -1;
    }

    private static boolean sameDeclaration(
            WorldPlacements.WorldObject a, WorldPlacements.WorldObject b) {
        return a.reference().equals(b.reference())
                && a.position().equals(b.position())
                && a.rotation().equals(b.rotation())
                && a.lowerBounds().equals(b.lowerBounds())
                && a.upperBounds().equals(b.upperBounds())
                && a.flags() == b.flags()
                && a.doodadSet() == b.doodadSet()
                && a.nameSet() == b.nameSet()
                && a.scale() == b.scale();
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new IllegalArgumentException(code);
        }
    }
}
