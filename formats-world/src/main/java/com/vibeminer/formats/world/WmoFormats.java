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
    public static final String REVISION = "wmo17-facts-v2";
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
            MOPY = 0x4d4f5059,
            MODS = 0x4d4f4453,
            MODN = 0x4d4f444e,
            MODI = 0x4d4f4449,
            MODD = 0x4d4f4444,
            MOPV = 0x4d4f5056,
            MOPT = 0x4d4f5054,
            MOPR = 0x4d4f5052,
            MOGI = 0x4d4f4749;

    /** Doodad declaration ceilings; larger or over-budget declarations are not retained. */
    public static final int MAX_DOODAD_SETS = 256, MAX_DOODAD_DEFS = 32768;

    /**
     * Portal declaration ceilings; larger or over-budget declarations are not retained. MOPT and
     * MOPR index with 16-bit fields, so no valid file needs more than 65,535 of either.
     */
    public static final int MAX_PORTALS = 4096, MAX_PORTAL_VERTICES = 16384, MAX_PORTAL_REFS = 8192;

    /**
     * Root group ceiling. Capital-city roots record more than 256 groups (Stormwind: 289, 280 of
     * them interior), so the ceiling is 512; a reader of the groups bounds its own reads.
     */
    public static final int MAX_GROUPS = 512;

    private static final Set<Integer> ROOT_TAGS =
            Set.of(
                    MVER, MOHD, MOMT, GFID, MOTX, MOGN, MOGI, MODS, MODN, MODI, MODD, MOPV, MOPT,
                    MOPR);
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
                    || groups > MAX_GROUPS
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
                    MAX_GROUPS,
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
        private final Doodads doodads;
        private final Portals portals;
        private final long[] groupInfoFlags;
        private final float[] groupInfoBounds;

        private Root(
                RootHeader h,
                List<Material> m,
                long[] g,
                byte[] n,
                List<Chunk> c,
                boolean gp,
                boolean np,
                GroupNames gn,
                Doodads d,
                Portals p,
                long[] gi,
                float[] gb) {
            groupInfoFlags = gi;
            groupInfoBounds = gb;
            groupNames = gn;
            doodads = d;
            portals = p;
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

        /** Recorded doodad sets, definitions and MODI FileDataIDs; see {@link Doodads}. */
        public Doodads doodads() {
            return doodads;
        }

        /** Recorded MOPV, MOPT and MOPR portal declarations; see {@link Portals}. */
        public Portals portals() {
            return portals;
        }

        /**
         * True when the root's MOGI group-info flags were retained: one 32-byte SMOGroupInfo
         * {uint32 flags, CAaBox bounds, int32 nameOffset} per MOHD group (wowdev.wiki WMO), so
         * {@link #groupInfoFlags(int)} answers for every LOD0 group. False when MOGI is absent,
         * duplicated, has a partial record, a record count other than the MOHD group count, or does
         * not fit the root fact budget; a root is never declined because of its MOGI.
         */
        public boolean groupInfoRetained() {
            return groupInfoFlags != null;
        }

        /**
         * The raw MOGI flags recorded for LOD0 group {@code group}, the same bits as that group's
         * MOGP flags (0x8 exterior, 0x2000 interior). Only a hint until the group itself is read:
         * its MOGP flags remain the authority for a group that is read.
         */
        public long groupInfoFlags(int group) {
            if (groupInfoFlags == null) {
                throw new IllegalStateException("Group info not retained");
            }
            return groupInfoFlags[group];
        }

        /**
         * True when every LOD0 group's MOGI bounds were retained with its flags: finite, not
         * inverted, and within the root fact budget. Only a hint about where a group lies, in raw
         * WMO-local axes; the group's own MOGP bounds are the authority once it is read.
         */
        public boolean groupInfoBoundsRetained() {
            return groupInfoBounds != null;
        }

        /** The recorded MOGI lower corner of LOD0 group {@code group}, raw WMO-local axes. */
        public Vec3 groupInfoLower(int group) {
            return groupInfoCorner(group, 0);
        }

        /** The recorded MOGI upper corner of LOD0 group {@code group}, raw WMO-local axes. */
        public Vec3 groupInfoUpper(int group) {
            return groupInfoCorner(group, 3);
        }

        private Vec3 groupInfoCorner(int group, int offset) {
            if (groupInfoBounds == null) {
                throw new IllegalStateException("Group bounds not retained");
            }
            Objects.checkIndex(group, groupInfoBounds.length / 6);
            int at = group * 6 + offset;
            return new Vec3(groupInfoBounds[at], groupInfoBounds[at + 1], groupInfoBounds[at + 2]);
        }
    }

    /**
     * One MOPT portal: a polygon of {@code vertexCount} MOPV vertices from {@code firstVertex}, in
     * WMO-local axes, and its raw recorded C4Plane ({@code normal}, {@code distance}). The plane is
     * not recomputed from the vertices, normalized or checked against them; which side its sign
     * puts a group on is the traversal's convention, confirmed there against real data.
     */
    public record Portal(int firstVertex, int vertexCount, Vec3 normal, float distance) {}

    /**
     * One MOPR reference, read from a group's MOGP range ({@code firstPortal}, {@code
     * portalCount}): the portal leads from that group to {@code group}. {@code side} is the raw
     * signed 16-bit word; its sign says on which side of the portal plane the referencing group
     * lies, which the traversal interprets (this reader does not).
     */
    public record PortalRef(int portal, int group, int side) {}

    /**
     * Recorded portal declarations (wowdev.wiki WMO: MOPV C3Vector[], MOPT 20-byte SMOPortal
     * {uint16 startVertex, uint16 count, C4Plane plane}, MOPR 8-byte SMOPortalRef {uint16
     * portalIndex, uint16 groupIndex, int16 side, uint16 filler}). {@code retained} is false when
     * any of the three is absent, duplicated or has a partial record; when one exceeds {@link
     * #MAX_PORTALS}, {@link #MAX_PORTAL_VERTICES} or {@link #MAX_PORTAL_REFS}; when a portal has
     * fewer than three vertices, a vertex range outside MOPV or a non-finite vertex or plane; when
     * a reference names a portal outside MOPT or a group outside the MOHD group count; or when they
     * do not fit the root fact budget. A root that qualifies without its portals is never declined
     * because of them. Zero-length MOPV, MOPT and MOPR are retained and empty.
     *
     * <p>Deliberately not checked here: the MOHD portal count against the MOPT records (only the
     * records are used), and each group's MOGP {@code firstPortal}/{@code portalCount} range
     * against MOPR, which needs the group files; the portal traversal checks those ranges.
     * Retention is all or nothing: one bad portal leaves the whole set not retained.
     */
    public static final class Portals {
        private static final Portals ABSENT =
                new Portals(false, false, new float[0], List.of(), List.of());
        private final boolean declared, retained;
        private final float[] vertices;
        private final List<Portal> portals;
        private final List<PortalRef> refs;

        private Portals(
                boolean declared,
                boolean retained,
                float[] vertices,
                List<Portal> portals,
                List<PortalRef> refs) {
            this.declared = declared;
            this.retained = retained;
            this.vertices = vertices;
            this.portals = List.copyOf(portals);
            this.refs = List.copyOf(refs);
        }

        /** Any portal chunk (MOPV, MOPT or MOPR) was declared. */
        public boolean declared() {
            return declared;
        }

        public boolean retained() {
            return retained;
        }

        public int vertexCount() {
            return vertices.length / 3;
        }

        public Vec3 vertex(int ordinal) {
            return new Vec3(
                    vertices[ordinal * 3], vertices[ordinal * 3 + 1], vertices[ordinal * 3 + 2]);
        }

        public List<Portal> portals() {
            return portals;
        }

        public List<PortalRef> refs() {
            return refs;
        }
    }

    /** One MODS doodad set: a contiguous MODD range. The 20-byte name is not interpreted. */
    public record DoodadSet(long firstDefinition, long count) {
        public DoodadSet {
            u32(firstDefinition);
            u32(count);
        }
    }

    /**
     * One MODD definition in WMO-local axes, raw. {@code reference} is the low 24 bits (an index
     * into MODI when present, a MODN offset otherwise) and {@code flags} the high 8 bits. The
     * quaternion is stored x, y, z, w; nothing here checks finiteness, unit length or scale.
     */
    public record DoodadDefinition(
            int reference,
            int flags,
            float x,
            float y,
            float z,
            float qx,
            float qy,
            float qz,
            float qw,
            float scale) {}

    /**
     * Recorded doodad declarations. {@code retained} is false when MODS or MODD is absent, when any
     * doodad declaration is duplicated or has a partial record, when one exceeds {@link
     * #MAX_DOODAD_SETS}/{@link #MAX_DOODAD_DEFS}, or when they do not fit the root fact budget: a
     * root that qualifies without its doodads is never declined because of them.
     */
    public static final class Doodads {
        private final boolean declared, retained, namesPresent, fileIdsPresent;
        private final List<DoodadSet> sets;
        private final List<DoodadDefinition> definitions;
        private final long[] fileIds;

        private Doodads(
                boolean declared,
                boolean retained,
                boolean namesPresent,
                boolean fileIdsPresent,
                List<DoodadSet> sets,
                List<DoodadDefinition> definitions,
                long[] fileIds) {
            this.declared = declared;
            this.retained = retained;
            this.namesPresent = namesPresent;
            this.fileIdsPresent = fileIdsPresent;
            this.sets = List.copyOf(sets);
            this.definitions = List.copyOf(definitions);
            this.fileIds = fileIds;
        }

        /** Any doodad chunk (MODS, MODN, MODI or MODD) was declared. */
        public boolean declared() {
            return declared;
        }

        public boolean retained() {
            return retained;
        }

        /** A MODN name block was declared (its offsets are names, not FileDataIDs). */
        public boolean namesPresent() {
            return namesPresent;
        }

        /** A MODI FileDataID table was declared. */
        public boolean fileIdsPresent() {
            return fileIdsPresent;
        }

        public List<DoodadSet> sets() {
            return sets;
        }

        public List<DoodadDefinition> definitions() {
            return definitions;
        }

        public int fileIdCount() {
            return fileIds.length;
        }

        public long fileId(int ordinal) {
            return fileIds[ordinal];
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
     * MaterialByte and bounds words stay raw; {@link #materialIndex()} is the only interpretation
     * of the alternate material-index flag.
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

        /** Batch flag 0x2: the MOMT index is the sixth bounds word, not the material byte. */
        public static final int LARGE_MATERIAL = 0x2;

        /**
         * The batch's MOMT index: the material byte when no flag is set, the sixth bounds word when
         * only {@link #LARGE_MATERIAL} is set (newer groups with more than 255 materials or MPY2
         * triangle records), and -1 for any other flag combination.
         */
        public int materialIndex() {
            return switch (flags) {
                case 0 -> materialByte;
                case LARGE_MATERIAL -> rawBounds.get(5);
                default -> -1;
            };
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
        int namesAt = -1, namesBytes = 0;
        int setsAt = -1, setsBytes = 0, defsAt = -1, defsBytes = 0, idsAt = -1, idsBytes = 0;
        boolean doodadNames = false, doodadDuplicate = false, portalDuplicate = false;
        int vertsAt = -1, vertsBytes = 0, portalsAt = -1, portalsBytes = 0, refsAt = -1;
        int refsBytes = 0, infoAt = -1, infoBytes = 0;
        boolean infoDuplicate = false;
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
                    // Retained after every other declaration has claimed its budget.
                    namesAt = at;
                    namesBytes = chunk.bytes();
                }
                case MODS -> {
                    // A duplicate doodad declaration leaves the doodads not retained; it never
                    // declines the root.
                    doodadDuplicate |= !seen.add(MODS);
                    setsAt = at;
                    setsBytes = chunk.bytes();
                }
                case MODN -> {
                    // A duplicate doodad declaration leaves the doodads not retained; it never
                    // declines the root.
                    doodadDuplicate |= !seen.add(MODN);
                    doodadNames = true;
                }
                case MODI -> {
                    // A duplicate doodad declaration leaves the doodads not retained; it never
                    // declines the root.
                    doodadDuplicate |= !seen.add(MODI);
                    idsAt = at;
                    idsBytes = chunk.bytes();
                }
                case MODD -> {
                    // A duplicate doodad declaration leaves the doodads not retained; it never
                    // declines the root.
                    doodadDuplicate |= !seen.add(MODD);
                    defsAt = at;
                    defsBytes = chunk.bytes();
                }
                case MOPV -> {
                    // Like doodads, a duplicate portal declaration leaves the portals not
                    // retained; it never declines the root.
                    portalDuplicate |= !seen.add(MOPV);
                    vertsAt = at;
                    vertsBytes = chunk.bytes();
                }
                case MOPT -> {
                    portalDuplicate |= !seen.add(MOPT);
                    portalsAt = at;
                    portalsBytes = chunk.bytes();
                }
                case MOPR -> {
                    portalDuplicate |= !seen.add(MOPR);
                    refsAt = at;
                    refsBytes = chunk.bytes();
                }
                case MOGI -> {
                    // Like portals, a duplicate group-info declaration leaves it not retained.
                    infoDuplicate |= !seen.add(MOGI);
                    infoAt = at;
                    infoBytes = chunk.bytes();
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
        // Descriptive only and claimed last: an oversized block or a tight budget leaves names
        // absent rather than declining a root that qualifies without them.
        if (namesAt >= 0
                && namesBytes <= GroupNames.MAX_BLOCK_BYTES
                && budget.tryClaim(64L + namesBytes)) {
            var block = new byte[namesBytes];
            input.get(namesAt, block);
            groupNames = new GroupNames(block);
        }
        var doodads =
                doodads(
                        input,
                        budget,
                        canceled,
                        doodadNames,
                        doodadDuplicate,
                        idsAt,
                        idsBytes,
                        setsAt,
                        setsBytes,
                        defsAt,
                        defsBytes);
        var portals =
                portals(
                        input,
                        budget,
                        canceled,
                        header.groupCount(),
                        portalDuplicate,
                        vertsAt,
                        vertsBytes,
                        portalsAt,
                        portalsBytes,
                        refsAt,
                        refsBytes);
        var groupInfo =
                groupInfo(
                        input,
                        budget,
                        canceled,
                        header.groupCount(),
                        infoDuplicate,
                        infoAt,
                        infoBytes);
        var groupBounds =
                groupInfo == null
                        ? null
                        : groupBounds(input, budget, canceled, groupInfo.length, infoAt);
        return new Root(
                header,
                materials,
                gfids,
                names,
                chunks,
                seen.contains(GFID),
                seen.contains(MOTX),
                groupNames,
                doodads,
                portals,
                groupInfo,
                groupBounds);
    }

    /**
     * MOGI bounds (six floats per group, lower then upper corner), claimed after the flags; null
     * when any is not finite or inverted, or they do not fit the fact budget. The flags stay
     * retained either way.
     */
    private static float[] groupBounds(
            ByteBuffer input, Budget budget, BooleanSupplier canceled, int groups, int infoAt) {
        if (!budget.tryClaim(64L + 24L * groups)) {
            return null;
        }
        var bounds = new float[groups * 6];
        for (int g = 0; g < groups; g++) {
            if ((g & 255) == 0) {
                check(canceled);
            }
            for (int i = 0; i < 6; i++) {
                float value = input.getFloat(infoAt + g * 32 + 4 + i * 4);
                if (!Float.isFinite(value)) {
                    return null;
                }
                bounds[g * 6 + i] = value;
            }
            for (int i = 0; i < 3; i++) {
                if (bounds[g * 6 + i] > bounds[g * 6 + 3 + i]) {
                    return null;
                }
            }
        }
        return bounds;
    }

    /**
     * MOGI flags in group order, claimed last; null when not retained (see Root#groupInfoRetained).
     */
    private static long[] groupInfo(
            ByteBuffer input,
            Budget budget,
            BooleanSupplier canceled,
            long groupCount,
            boolean duplicate,
            int infoAt,
            int infoBytes) {
        if (infoAt < 0
                || duplicate
                || infoBytes % 32 != 0
                || infoBytes / 32 != groupCount
                || !budget.tryClaim(64L + 8L * groupCount)) {
            return null;
        }
        var flags = new long[(int) groupCount];
        for (int g = 0; g < flags.length; g++) {
            if ((g & 255) == 0) {
                check(canceled);
            }
            flags[g] = Integer.toUnsignedLong(input.getInt(infoAt + g * 32));
        }
        return flags;
    }

    /** Portal declarations, claimed after doodads; see {@link Portals} for what is not retained. */
    private static Portals portals(
            ByteBuffer input,
            Budget budget,
            BooleanSupplier canceled,
            long groupCount,
            boolean duplicate,
            int vertsAt,
            int vertsBytes,
            int portalsAt,
            int portalsBytes,
            int refsAt,
            int refsBytes) {
        boolean declared = vertsAt >= 0 || portalsAt >= 0 || refsAt >= 0;
        if (!declared) {
            return Portals.ABSENT;
        }
        var notRetained = new Portals(true, false, new float[0], List.of(), List.of());
        int vertexCount = vertsBytes / 12, portalCount = portalsBytes / 20;
        int refCount = refsBytes / 8;
        if (duplicate
                || vertsAt < 0
                || portalsAt < 0
                || refsAt < 0
                || vertsBytes % 12 != 0
                || portalsBytes % 20 != 0
                || refsBytes % 8 != 0
                || vertexCount > MAX_PORTAL_VERTICES
                || portalCount > MAX_PORTALS
                || refCount > MAX_PORTAL_REFS
                || !budget.tryClaim(
                        192L + 12L * vertexCount + 48L * portalCount + 32L * refCount)) {
            return notRetained;
        }
        var vertices = new float[vertexCount * 3];
        for (int i = 0; i < vertices.length; i++) {
            if ((i & 1023) == 0) {
                check(canceled);
            }
            vertices[i] = input.getFloat(vertsAt + i * 4);
            if (!Float.isFinite(vertices[i])) {
                return notRetained;
            }
        }
        var portals = new ArrayList<Portal>(portalCount);
        for (int i = 0; i < portalCount; i++) {
            check(canceled);
            int at = portalsAt + i * 20;
            int first = Short.toUnsignedInt(input.getShort(at));
            int count = Short.toUnsignedInt(input.getShort(at + 2));
            float nx = input.getFloat(at + 4), ny = input.getFloat(at + 8);
            float nz = input.getFloat(at + 12), distance = input.getFloat(at + 16);
            if (count < 3
                    || first + count > vertexCount
                    || !Float.isFinite(nx)
                    || !Float.isFinite(ny)
                    || !Float.isFinite(nz)
                    || !Float.isFinite(distance)) {
                return notRetained;
            }
            portals.add(new Portal(first, count, new Vec3(nx, ny, nz), distance));
        }
        var refs = new ArrayList<PortalRef>(refCount);
        for (int i = 0; i < refCount; i++) {
            if ((i & 1023) == 0) {
                check(canceled);
            }
            int at = refsAt + i * 8;
            int portal = Short.toUnsignedInt(input.getShort(at));
            int group = Short.toUnsignedInt(input.getShort(at + 2));
            int side = input.getShort(at + 4);
            if (portal >= portalCount || group >= groupCount) {
                return notRetained;
            }
            refs.add(new PortalRef(portal, group, side));
        }
        return new Portals(true, true, vertices, portals, refs);
    }

    /**
     * Doodad declarations, claimed after every required declaration. A duplicate, partial
     * (malformed shape), oversized or over-budget declaration is only not retained; the root is
     * never declined because of its doodads.
     */
    private static Doodads doodads(
            ByteBuffer input,
            Budget budget,
            BooleanSupplier canceled,
            boolean names,
            boolean duplicate,
            int idsAt,
            int idsBytes,
            int setsAt,
            int setsBytes,
            int defsAt,
            int defsBytes) {
        boolean ids = idsAt >= 0;
        int setCount = setsBytes / 32, defCount = defsBytes / 40, idCount = idsBytes / 4;
        boolean declared = setsAt >= 0 || defsAt >= 0 || ids || names;
        if (duplicate
                || (ids && idsBytes % 4 != 0)
                || (setsAt >= 0 && setsBytes % 32 != 0)
                || (defsAt >= 0 && defsBytes % 40 != 0)
                || setsAt < 0
                || defsAt < 0
                || setCount > MAX_DOODAD_SETS
                || defCount > MAX_DOODAD_DEFS
                || idCount > MAX_DOODAD_DEFS
                || !budget.tryClaim(192L + 32L * setCount + 64L * defCount + 8L * idCount)) {
            return new Doodads(declared, false, names, ids, List.of(), List.of(), new long[0]);
        }
        var sets = new ArrayList<DoodadSet>(setCount);
        for (int i = 0; i < setCount; i++) {
            check(canceled);
            int at = setsAt + i * 32 + 20;
            sets.add(
                    new DoodadSet(
                            Integer.toUnsignedLong(input.getInt(at)),
                            Integer.toUnsignedLong(input.getInt(at + 4))));
        }
        var definitions = new ArrayList<DoodadDefinition>(defCount);
        for (int i = 0; i < defCount; i++) {
            if ((i & 1023) == 0) {
                check(canceled);
            }
            int at = defsAt + i * 40, word = input.getInt(at);
            definitions.add(
                    new DoodadDefinition(
                            word & 0xffffff,
                            word >>> 24,
                            input.getFloat(at + 4),
                            input.getFloat(at + 8),
                            input.getFloat(at + 12),
                            input.getFloat(at + 16),
                            input.getFloat(at + 20),
                            input.getFloat(at + 24),
                            input.getFloat(at + 28),
                            input.getFloat(at + 32)));
        }
        var fileIds = new long[idCount];
        for (int i = 0; i < idCount; i++) {
            fileIds[i] = Integer.toUnsignedLong(input.getInt(idsAt + i * 4));
        }
        return new Doodads(true, true, names, ids, sets, definitions, fileIds);
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
