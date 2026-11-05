package com.vibeminer.formats.world;

import com.vibeminer.formats.world.WorldPlacements.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Raw WMO17 declarations. No asset resolution, LOD mapping, material or render admission. */
public final class WmoFormats {
    public static final String REVISION = "wmo17-facts-v1";
    public static final int MAX_SOURCE_BYTES = 8 << 20, MAX_CHUNKS = 1024;
    public static final long MAX_FACT_BYTES = 12L << 20;
    private static final int MVER = 0x4d564552,
            MOHD = 0x4d4f4844,
            MOMT = 0x4d4f4d54,
            GFID = 0x47464944,
            MOTX = 0x4d4f5458,
            MOGN = 0x4d4f474e,
            MOGP = 0x4d4f4750,
            MOVT = 0x4d4f5654,
            MOVI = 0x4d4f5649,
            MONR = 0x4d4f4e52,
            MOTV = 0x4d4f5456,
            MOBA = 0x4d4f4241,
            MOPY = 0x4d4f5059;

    private static final Set<Integer> ROOT_TAGS = Set.of(MVER, MOHD, MOMT, GFID, MOTX, MOGN);
    private static final Set<Integer> GROUP_TAGS = Set.of(MVER, MOGP);
    private static final Set<Integer> MESH_TAGS = Set.of(MOVT, MONR, MOVI, MOTV, MOBA, MOPY);

    private WmoFormats() {}

    /** Independent raw-fact limits; basicPreview narrows shape, never certifies appearance. */
    public record Limits(
            int rootBytes,
            int groupBytes,
            int groups,
            int materials,
            int gfidEntries,
            int vertices,
            int triangles,
            int batches,
            int uvStreams,
            long factBytes) {
        public Limits {
            if (rootBytes < 12
                    || rootBytes > MAX_SOURCE_BYTES
                    || groupBytes < 12
                    || groupBytes > MAX_SOURCE_BYTES
                    || groups < 0
                    || groups > 256
                    || materials < 0
                    || materials > 256
                    || gfidEntries < 0
                    || gfidEntries > 4096
                    || vertices < 0
                    || vertices > 65536
                    || triangles < 0
                    || triangles > 131072
                    || batches < 0
                    || batches > 256
                    || uvStreams < 0
                    || uvStreams > 4
                    || factBytes < 1024
                    || factBytes > MAX_FACT_BYTES) {
                throw invalid("WMO limits outside supported ceilings");
            }
        }

        public static Limits standard() {
            return new Limits(
                    MAX_SOURCE_BYTES,
                    MAX_SOURCE_BYTES,
                    256,
                    256,
                    4096,
                    65536,
                    131072,
                    256,
                    4,
                    MAX_FACT_BYTES);
        }

        public static Limits basicPreview() {
            return new Limits(1024, 32768, 1, 6, 1, 512, 512, 6, 1, 256L << 10);
        }
    }

    /** Raw unsigned reference operands have no inferred file-ID/name-offset domain. */
    public enum ReferenceDomain {
        UNRESOLVED_UNSIGNED32
    }

    public record Chunk(int tag, int bytes, boolean payloadParsed) {
        public Chunk {
            if (bytes < 0 || bytes > MAX_SOURCE_BYTES) {
                throw invalid("Chunk size");
            }
        }
    }

    public record RootHeader(
            List<Long> counts,
            long ambientColor,
            long wmoId,
            Vec3 lower,
            Vec3 upper,
            int flags,
            int lodCount) {
        public RootHeader {
            if (counts.size() != 7) {
                throw invalid("Root count inventory");
            }
            for (long n : counts) {
                u32(n);
            }
            counts = List.copyOf(counts);
            u32(ambientColor);
            u32(wmoId);
            bounds(lower, upper);
            u16(flags);
            u16(lodCount);
        }

        public long materialCount() {
            return counts.get(0);
        }

        public long groupCount() {
            return counts.get(1);
        }
    }

    /** Exactly sixteen original unsigned words, including uninterpreted operands. */
    public static final class Material {
        private final long[] words;

        private Material(long[] owned) {
            words = owned;
        }

        public long word(int index) {
            return words[index];
        }

        public ReferenceDomain textureDomain() {
            return ReferenceDomain.UNRESOLVED_UNSIGNED32;
        }
    }

    /** GFID is flat recorded order; this reader never guesses LOD dimensions or group filenames. */
    public static final class Root {
        private final RootHeader header;
        private final List<Material> materials;
        private final long[] gfids;
        private final byte[] names;
        private final List<Chunk> chunks;
        private final boolean gfidPresent, namesPresent;
        private final GroupNames groupNames;

        private Root(
                RootHeader h,
                List<Material> m,
                long[] g,
                byte[] n,
                List<Chunk> c,
                boolean gp,
                boolean np,
                GroupNames gn) {
            groupNames = gn;
            header = h;
            materials = List.copyOf(m);
            gfids = g;
            names = n;
            chunks = List.copyOf(c);
            gfidPresent = gp;
            namesPresent = np;
        }

        public RootHeader header() {
            return header;
        }

        public List<Material> materials() {
            return materials;
        }

        public int gfidCount() {
            return gfids.length;
        }

        public long gfid(int ordinal) {
            return gfids[ordinal];
        }

        public boolean gfidPresent() {
            return gfidPresent;
        }

        public boolean textureNameBlockPresent() {
            return namesPresent;
        }

        public ByteBuffer textureNameBlock() {
            return ByteBuffer.wrap(names).asReadOnlyBuffer();
        }

        public List<Chunk> chunks() {
            return chunks;
        }

        /** Recorded MOGN group-name block, or {@link GroupNames#absent()} when not retained. */
        public GroupNames groupNames() {
            return groupNames;
        }
    }

    /**
     * Recorded MOGN bytes, retained only when they fit the root fact budget. A name is read at a
     * recorded MOGP name offset up to its NUL; nothing is inferred from file paths or ordinals.
     */
    public static final class GroupNames {
        public static final int MAX_BLOCK_BYTES = 16 << 10, MAX_NAME_BYTES = 255;
        private static final GroupNames ABSENT = new GroupNames(null);
        private final byte[] block;

        private GroupNames(byte[] block) {
            this.block = block;
        }

        public static GroupNames absent() {
            return ABSENT;
        }

        /** Detached copy of recorded bytes; tests and fact builders only. */
        public static GroupNames of(byte[] recorded) {
            Objects.requireNonNull(recorded);
            if (recorded.length > MAX_BLOCK_BYTES) {
                throw invalid("Group name block exceeds limit");
            }
            return new GroupNames(recorded.clone());
        }

        public boolean present() {
            return block != null;
        }

        /**
         * The NUL-terminated printable-ASCII name at {@code offset}; empty when the block is
         * absent, the offset is outside it, the name is unterminated, over 255 bytes, or not
         * printable ASCII. A recorded empty name returns {@code ""}.
         */
        public java.util.Optional<String> name(long offset) {
            if (block == null || offset < 0 || offset >= block.length) {
                return java.util.Optional.empty();
            }
            int start = (int) offset;
            for (int at = start; at < block.length && at - start <= MAX_NAME_BYTES; at++) {
                int value = block[at] & 255;
                if (value == 0) {
                    return java.util.Optional.of(
                            new String(
                                    block,
                                    start,
                                    at - start,
                                    java.nio.charset.StandardCharsets.US_ASCII));
                }
                if (value < 0x20 || value > 0x7e) {
                    return java.util.Optional.empty();
                }
            }
            return java.util.Optional.empty();
        }
    }

    public record GroupHeader(
            long nameOffset,
            long descriptionOffset,
            long flags,
            Vec3 lower,
            Vec3 upper,
            int firstPortal,
            int portalCount,
            int batchesA,
            int batchesB,
            int batchesC,
            int rawBatchPadding,
            long rawUnused,
            long liquidType,
            long groupId,
            long tail0,
            long tail1) {
        public GroupHeader {
            u32(nameOffset);
            u32(descriptionOffset);
            u32(flags);
            bounds(lower, upper);
            for (int n :
                    new int[] {
                        firstPortal, portalCount, batchesA, batchesB, batchesC, rawBatchPadding
                    }) {
                u16(n);
            }
            for (long n : new long[] {rawUnused, liquidType, groupId, tail0, tail1}) {
                u32(n);
            }
        }
    }

    /**
     * MaterialByte and bounds words stay raw; alternate material-index flags are not interpreted.
     */
    public record Batch(
            List<Integer> rawBounds,
            long firstIndex,
            int indexCount,
            int firstVertex,
            int lastVertex,
            int flags,
            int materialByte) {
        public Batch {
            if (rawBounds.size() != 6) {
                throw invalid("Batch bounds inventory");
            }
            for (int n : rawBounds) {
                u16(n);
            }
            rawBounds = List.copyOf(rawBounds);
            u32(firstIndex);
            u16(indexCount);
            u16(firstVertex);
            u16(lastVertex);
            if (flags < 0 || flags > 255 || materialByte < 0 || materialByte > 255) {
                throw invalid("Batch byte");
            }
        }
    }

    /** Private owned arrays, read-only views; original positions/normal/UV values are unchanged. */
    public static final class Group {
        private final GroupHeader header;
        private final float[] positions, normals;
        private final List<float[]> uv;
        private final int[] indices;
        private final byte[] triangles;
        private final List<Batch> batches;
        private final List<Chunk> chunks;

        private Group(GroupBuilder b) {
            header = b.header;
            positions = b.positions;
            normals = b.normals;
            uv = List.copyOf(b.uv);
            indices = b.indices;
            triangles = b.triangles;
            batches = List.copyOf(b.batches);
            chunks = List.copyOf(b.chunks);
        }

        public GroupHeader header() {
            return header;
        }

        public FloatBuffer positions() {
            return FloatBuffer.wrap(positions).asReadOnlyBuffer();
        }

        public FloatBuffer normals() {
            return FloatBuffer.wrap(normals).asReadOnlyBuffer();
        }

        public int uvStreams() {
            return uv.size();
        }

        public FloatBuffer uv(int ordinal) {
            return FloatBuffer.wrap(uv.get(ordinal)).asReadOnlyBuffer();
        }

        public IntBuffer indices() {
            return IntBuffer.wrap(indices).asReadOnlyBuffer();
        }

        /** Pairs of raw flag/material bytes; not a visible-triangle filter. */
        public ByteBuffer triangleDeclarations() {
            return ByteBuffer.wrap(triangles).asReadOnlyBuffer();
        }

        public List<Batch> batches() {
            return batches;
        }

        public List<Chunk> chunks() {
            return chunks;
        }
    }

    public static Root root(byte[] bytes, BooleanSupplier canceled) {
        return root(bytes, Limits.standard(), canceled);
    }

    public static Group group(byte[] bytes, BooleanSupplier canceled) {
        return group(bytes, Limits.standard(), canceled);
    }

    public static Root root(byte[] bytes, Limits limits, BooleanSupplier canceled) {
        var input = input(bytes, limits.rootBytes(), canceled);
        var budget = new Budget(limits.factBytes());
        var chunks = new ArrayList<Chunk>();
        var seen = new HashSet<Integer>();
        var materials = new ArrayList<Material>();
        RootHeader header = null;
        long[] gfids = new long[0];
        byte[] names = new byte[0];
        GroupNames groupNames = GroupNames.absent();
        while (input.hasRemaining()) {
            check(canceled);
            var chunk = chunk(input, input.limit(), chunks, budget, ROOT_TAGS);
            int at = input.position(), end = at + chunk.bytes();
            switch (chunk.tag()) {
                case MVER -> {
                    unique(seen, MVER);
                    version(input, chunk.bytes());
                }
                case MOHD -> {
                    unique(seen, MOHD);
                    if (chunk.bytes() != 64) {
                        throw invalid("MOHD requires 64 bytes");
                    }
                    var counts = new ArrayList<Long>();
                    for (int i = 0; i < 7; i++) {
                        counts.add(uint(input));
                    }
                    if (counts.get(0) > limits.materials() || counts.get(1) > limits.groups()) {
                        throw invalid("Root counts exceed limit");
                    }
                    budget.claim(512);
                    long ambient = uint(input), id = uint(input);
                    var lower = vector(input);
                    var upper = vector(input);
                    header =
                            new RootHeader(
                                    counts,
                                    ambient,
                                    id,
                                    lower,
                                    upper,
                                    ushort(input),
                                    ushort(input));
                }
                case MOMT -> {
                    unique(seen, MOMT);
                    int count = count(chunk.bytes(), 64, limits.materials());
                    budget.claim(256L * count);
                    for (int i = 0; i < count; i++) {
                        check(canceled);
                        var words = new long[16];
                        for (int w = 0; w < 16; w++) {
                            words[w] = uint(input);
                        }
                        materials.add(new Material(words));
                    }
                }
                case GFID -> {
                    unique(seen, GFID);
                    int count = count(chunk.bytes(), 4, limits.gfidEntries());
                    budget.claim(64L + 8L * count);
                    gfids = new long[count];
                    for (int i = 0; i < count; i++) {
                        check(canceled);
                        gfids[i] = uint(input);
                    }
                }
                case MOTX -> {
                    unique(seen, MOTX);
                    if (chunk.bytes() > 65536) {
                        throw invalid("Texture name block exceeds limit");
                    }
                    budget.claim(64L + chunk.bytes());
                    names = new byte[chunk.bytes()];
                    input.get(names);
                }
                case MOGN -> {
                    unique(seen, MOGN);
                    // Descriptive only: an oversized block or tight budget leaves names absent
                    // rather than declining a root that qualified before names were read.
                    if (chunk.bytes() <= GroupNames.MAX_BLOCK_BYTES
                            && budget.tryClaim(64L + chunk.bytes())) {
                        var block = new byte[chunk.bytes()];
                        input.get(block);
                        groupNames = new GroupNames(block);
                    }
                }
                default -> {
                    /* All unparsed declarations remain in chunks, never inferred empty. */
                }
            }
            input.position(end);
        }
        check(canceled);
        if (!seen.contains(MVER)
                || header == null
                || !seen.contains(MOMT)
                || materials.size() != header.materialCount()) {
            throw invalid("Root missing required declarations or material count mismatch");
        }
        return new Root(
                header,
                materials,
                gfids,
                names,
                chunks,
                seen.contains(GFID),
                seen.contains(MOTX),
                groupNames);
    }

    public static Group group(byte[] bytes, Limits limits, BooleanSupplier canceled) {
        var input = input(bytes, limits.groupBytes(), canceled);
        var budget = new Budget(limits.factBytes());
        var b = new GroupBuilder();
        var seen = new HashSet<Integer>();
        while (input.hasRemaining()) {
            check(canceled);
            var c = chunk(input, input.limit(), b.chunks, budget, GROUP_TAGS);
            int end = input.position() + c.bytes();
            if (c.tag() == MVER) {
                unique(seen, MVER);
                version(input, c.bytes());
            } else if (c.tag() == MOGP) {
                unique(seen, MOGP);
                if (c.bytes() < 68) {
                    throw invalid("MOGP header truncated");
                }
                budget.claim(512);
                long name = uint(input), description = uint(input), flags = uint(input);
                var lower = vector(input);
                var upper = vector(input);
                b.header =
                        new GroupHeader(
                                name,
                                description,
                                flags,
                                lower,
                                upper,
                                ushort(input),
                                ushort(input),
                                ushort(input),
                                ushort(input),
                                ushort(input),
                                ushort(input),
                                uint(input),
                                uint(input),
                                uint(input),
                                uint(input),
                                uint(input));
                while (input.position() < end) {
                    check(canceled);
                    var nested = chunk(input, end, b.chunks, budget, MESH_TAGS);
                    int nestedEnd = input.position() + nested.bytes();
                    nested(input, nested, b, limits, budget, canceled);
                    input.position(nestedEnd);
                }
            }
            input.position(end);
        }
        if (!seen.contains(MVER)
                || b.header == null
                || !b.seen.containsAll(Set.of(MOVT, MONR, MOVI, MOBA, MOPY))) {
            throw invalid("Group missing required declarations");
        }
        int vertices = b.positions.length / 3;
        if (b.normals.length != b.positions.length
                || b.triangles.length != b.indices.length / 3 * 2
                || (long) b.header.batchesA() + b.header.batchesB() + b.header.batchesC()
                        != b.batches.size()) {
            throw invalid("Group shape/header counts disagree");
        }
        for (var uv : b.uv) {
            if (uv.length != vertices * 2) {
                throw invalid("UV shape disagrees");
            }
        }
        for (int index : b.indices) {
            check(canceled);
            if (index >= vertices) {
                throw invalid("Index outside vertex inventory");
            }
        }
        long batchWork = 0;
        for (var batch : b.batches) {
            if (batchWork > 3L * limits.triangles() - batch.indexCount()) {
                throw invalid("WMO batch index inspection work limit");
            }
            batchWork += batch.indexCount();
            check(canceled);
            if (batch.firstIndex() % 3 != 0
                    || batch.firstIndex() + batch.indexCount() > b.indices.length
                    || batch.indexCount() % 3 != 0
                    || batch.firstVertex() > batch.lastVertex()
                    || batch.lastVertex() >= vertices) {
                throw invalid("Batch range outside geometry");
            }
            for (long i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i++) {
                check(canceled);
                int index = b.indices[(int) i];
                if (index < batch.firstVertex() || index > batch.lastVertex()) {
                    throw invalid("Batch index outside declared vertex range");
                }
            }
        }
        check(canceled);
        return new Group(b);
    }

    private static void nested(
            ByteBuffer input,
            Chunk c,
            GroupBuilder b,
            Limits limits,
            Budget budget,
            BooleanSupplier canceled) {
        if (c.tag() != MOTV && Set.of(MOVT, MONR, MOVI, MOBA, MOPY).contains(c.tag())) {
            unique(b.seen, c.tag());
        }
        switch (c.tag()) {
            case MOVT ->
                    b.positions = floats(input, c.bytes(), 3, limits.vertices(), budget, canceled);
            case MONR ->
                    b.normals = floats(input, c.bytes(), 3, limits.vertices(), budget, canceled);
            case MOTV -> {
                if (b.uv.size() >= limits.uvStreams()) {
                    throw invalid("UV stream count exceeds limit");
                }
                b.uv.add(floats(input, c.bytes(), 2, limits.vertices(), budget, canceled));
            }
            case MOVI -> {
                int count = count(c.bytes(), 6, limits.triangles()) * 3;
                budget.claim(64L + count * 4L);
                b.indices = new int[count];
                for (int i = 0; i < count; i++) {
                    check(canceled);
                    b.indices[i] = ushort(input);
                }
            }
            case MOPY -> {
                count(c.bytes(), 2, limits.triangles());
                budget.claim(64L + c.bytes());
                b.triangles = new byte[c.bytes()];
                input.get(b.triangles);
            }
            case MOBA -> {
                int count = count(c.bytes(), 24, limits.batches());
                budget.claim(512L * count);
                for (int i = 0; i < count; i++) {
                    check(canceled);
                    var words = new ArrayList<Integer>();
                    for (int w = 0; w < 6; w++) {
                        words.add(ushort(input));
                    }
                    b.batches.add(
                            new Batch(
                                    words,
                                    uint(input),
                                    ushort(input),
                                    ushort(input),
                                    ushort(input),
                                    Byte.toUnsignedInt(input.get()),
                                    Byte.toUnsignedInt(input.get())));
                }
            }
            default -> {
                /* Includes MOCV/MLIQ/portals: presence retained, no appearance claim. */
            }
        }
    }

    private static final class GroupBuilder {
        GroupHeader header;
        float[] positions, normals;
        int[] indices;
        byte[] triangles;
        final List<float[]> uv = new ArrayList<>();
        final List<Batch> batches = new ArrayList<>();
        final List<Chunk> chunks = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
    }

    private static final class Budget {
        final long maximum;
        long bytes;

        Budget(long maximum) {
            this.maximum = maximum;
            claim(2048); // Fixed builders, small sets and empty-container overhead.
        }

        void claim(long count) {
            if (count < 0 || bytes > maximum - count) {
                throw invalid("WMO facts byte budget exceeded");
            }
            bytes += count;
        }

        boolean tryClaim(long count) {
            if (count < 0 || bytes > maximum - count) {
                return false;
            }
            bytes += count;
            return true;
        }
    }

    private static ByteBuffer input(byte[] bytes, int maximum, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length < 12 || bytes.length > maximum) {
            throw invalid("WMO source byte limit");
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Chunk chunk(
            ByteBuffer input, int end, List<Chunk> chunks, Budget budget, Set<Integer> parsedTags) {
        if (chunks.size() >= MAX_CHUNKS || end - input.position() < 8) {
            throw invalid("WMO chunk header/count");
        }
        int tag = input.getInt(), size = input.getInt();
        if (size < 0 || size > end - input.position()) {
            throw invalid("WMO chunk interval");
        }
        budget.claim(64);
        var chunk = new Chunk(tag, size, parsedTags.contains(tag));
        chunks.add(chunk);
        return chunk;
    }

    private static float[] floats(
            ByteBuffer input,
            int bytes,
            int width,
            int maximum,
            Budget budget,
            BooleanSupplier canceled) {
        int count = count(bytes, 4 * width, maximum) * width;
        budget.claim(64L + 4L * count);
        var values = new float[count];
        for (int i = 0; i < count; i++) {
            check(canceled);
            values[i] = input.getFloat();
            if (!Float.isFinite(values[i])) {
                throw invalid("Nonfinite WMO value");
            }
        }
        return values;
    }

    private static int count(int bytes, int stride, int maximum) {
        if (bytes % stride != 0 || bytes / stride > maximum) {
            throw invalid("WMO array shape/count exceeds limit");
        }
        return bytes / stride;
    }

    private static void version(ByteBuffer input, int bytes) {
        if (bytes != 4 || input.getInt() != 17) {
            throw invalid("WMO version17 required");
        }
    }

    private static void unique(Set<Integer> seen, int tag) {
        if (!seen.add(tag)) {
            throw invalid("Duplicate WMO singleton");
        }
    }

    private static long uint(ByteBuffer input) {
        return Integer.toUnsignedLong(input.getInt());
    }

    private static int ushort(ByteBuffer input) {
        return Short.toUnsignedInt(input.getShort());
    }

    private static Vec3 vector(ByteBuffer input) {
        return new Vec3(input.getFloat(), input.getFloat(), input.getFloat());
    }

    private static void bounds(Vec3 lower, Vec3 upper) {
        Objects.requireNonNull(lower);
        Objects.requireNonNull(upper);
        if (lower.x() > upper.x() || lower.y() > upper.y() || lower.z() > upper.z()) {
            throw invalid("Inverted WMO bounds");
        }
    }

    private static void u32(long value) {
        if (value < 0 || value > 0xffffffffL) {
            throw invalid("Unsigned32 WMO value");
        }
    }

    private static void u16(int value) {
        if (value < 0 || value > 65535) {
            throw invalid("Unsigned16 WMO value");
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
