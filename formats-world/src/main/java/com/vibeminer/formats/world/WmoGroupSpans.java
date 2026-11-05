package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bounded raw group views, without payload copies, source authority or rendering policy.
 *
 * <p>Each stream is a singleton, with two recorded exceptions. A second MOCV is admitted when MOGP
 * declares flag 0x1000000, and a second and third MOTV when it declares 0x2000000 and 0x40000000;
 * these extra sets are validated against the vertex count but not exposed: {@code uv()} and {@code
 * colors()} are always the first recorded set. Triangle materials come from MOPY (2 bytes per
 * triangle) or, in newer groups, MPY2 (4 bytes per triangle), never both; {@link
 * Borrow#triangleBytes()} tells which.
 */
public final class WmoGroupSpans {
    public static final int MAX_BYTES = 1 << 20;
    public static final int MAX_VERTICES = 16384, MAX_TRIANGLES = 16384;
    public static final int MAX_BATCHES = 64, MAX_CHUNKS = 256, MAX_METADATA_BYTES = 64 << 10;
    private static final int MVER = 0x4d564552, MOGP = 0x4d4f4750;
    private static final int MOVT = 0x4d4f5654, MONR = 0x4d4f4e52, MOVI = 0x4d4f5649;
    private static final int MOTV = 0x4d4f5456, MOBA = 0x4d4f4241, MOPY = 0x4d4f5059;
    private static final int MOCV = 0x4d4f4356, MLIQ = 0x4d4c4951, MPY2 = 0x4d505932;

    /** MOGP flags declaring a second MOCV, a second MOTV and a third MOTV. */
    private static final long COLORS2 = 0x1000000L, UV2 = 0x2000000L, UV3 = 0x40000000L;

    private WmoGroupSpans() {}

    /** Caller owns and must exclusively freeze raw for the synchronous callback's lifetime. */
    public static void withGroup(byte[] raw, BooleanSupplier canceled, Consumer<Borrow> use) {
        Objects.requireNonNull(raw);
        Objects.requireNonNull(canceled);
        Objects.requireNonNull(use);
        check(canceled);
        require(raw.length >= 12 && raw.length <= MAX_BYTES, "Group source limit");
        var parser = new Parser(raw, canceled);
        Borrow borrow = null;
        try {
            parser.parse();
            borrow = new Borrow(parser);
            use.accept(borrow);
        } finally {
            if (borrow != null) {
                borrow.expire();
            }
            parser.expire();
        }
    }

    private abstract static class Guard {
        private byte[] raw;
        private BooleanSupplier canceled;
        private final long thread = Thread.currentThread().threadId();
        private final int offset, count;

        Guard(byte[] raw, BooleanSupplier canceled, int offset, int count) {
            this.raw = raw;
            this.canceled = canceled;
            this.offset = offset;
            this.count = count;
        }

        final void check() {
            if (raw == null || thread != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired or foreign-thread group span");
            }
            WmoGroupSpans.check(canceled);
        }

        public final int size() {
            check();
            return count;
        }

        private float floatAt(int index) {
            check();
            int at = offset + Objects.checkIndex(index, count) * 4;
            return Float.intBitsToFloat(
                    (raw[at] & 255)
                            | (raw[at + 1] & 255) << 8
                            | (raw[at + 2] & 255) << 16
                            | (raw[at + 3] & 255) << 24);
        }

        private int unsignedShortAt(int index) {
            check();
            int at = offset + Objects.checkIndex(index, count) * 2;
            return (raw[at] & 255) | (raw[at + 1] & 255) << 8;
        }

        private int unsignedByteAt(int index) {
            check();
            return raw[offset + Objects.checkIndex(index, count)] & 255;
        }

        final void expire() {
            raw = null;
            canceled = null;
        }
    }

    /** Scalar access only: no escaped FloatBuffer can bypass expiry. */
    public static final class Floats extends Guard {
        private Floats(byte[] raw, BooleanSupplier cancel, int offset, int count) {
            super(raw, cancel, offset, count);
        }

        public float get(int index) {
            return ((Guard) this).floatAt(index);
        }
    }

    /** Unsigned16 source indices, never expanded into another array. */
    public static final class Indices extends Guard {
        private Indices(byte[] raw, BooleanSupplier cancel, int offset, int count) {
            super(raw, cancel, offset, count);
        }

        public int get(int index) {
            return ((Guard) this).unsignedShortAt(index);
        }
    }

    /** Unsigned raw bytes; MOCV/MLIQ presence does not imply opacity or liquid semantics. */
    public static final class Bytes extends Guard {
        private Bytes(byte[] raw, BooleanSupplier cancel, int offset, int count) {
            super(raw, cancel, offset, count);
        }

        public int get(int index) {
            return ((Guard) this).unsignedByteAt(index);
        }
    }

    public static final class Borrow {
        private final long thread = Thread.currentThread().threadId();
        private BooleanSupplier canceled;
        private WmoFormats.GroupHeader header;
        private List<WmoFormats.Batch> batches;
        private List<WmoFormats.Chunk> chunks;
        private Floats positions, normals, uv;
        private Indices indices;
        private Bytes triangles, colors, liquid;
        private final int triangleBytes;
        private final int metadataBytes;

        private Borrow(Parser p) {
            canceled = p.canceled;
            header = p.header;
            batches = List.copyOf(p.batches);
            chunks = List.copyOf(p.chunks);
            positions = p.positions;
            normals = p.normals;
            uv = p.uv;
            indices = p.indices;
            triangles = p.triangles;
            colors = p.colors;
            liquid = p.liquid;
            triangleBytes = p.triangleBytes;
            metadataBytes = p.budget;
        }

        private void check() {
            if (canceled == null || thread != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired or foreign-thread group borrow");
            }
            WmoGroupSpans.check(canceled);
        }

        public WmoFormats.GroupHeader header() {
            check();
            return header;
        }

        public List<WmoFormats.Batch> batches() {
            check();
            return batches;
        }

        public List<WmoFormats.Chunk> chunks() {
            check();
            return chunks;
        }

        public Floats positions() {
            check();
            return positions;
        }

        public Floats normals() {
            check();
            return normals;
        }

        public Floats uv() {
            check();
            return uv;
        }

        public Indices indices() {
            check();
            return indices;
        }

        /** Per-triangle MOPY or MPY2 bytes; {@link #triangleBytes()} gives the stride. */
        public Bytes triangles() {
            check();
            return triangles;
        }

        /** 2 for MOPY triangle records, 4 for MPY2. */
        public int triangleBytes() {
            check();
            return triangleBytes;
        }

        public Bytes colors() {
            check();
            return colors;
        }

        public Bytes liquid() {
            check();
            return liquid;
        }

        // Conservative named metadata reservation, not a measurement of JVM/RSS usage.
        int metadataBytes() {
            check();
            return metadataBytes;
        }

        private void expire() {
            canceled = null;
            header = null;
            batches = null;
            chunks = null;
            positions = null;
            normals = null;
            uv = null;
            indices = null;
            triangles = null;
            colors = null;
            liquid = null;
        }
    }

    private static final class Parser {
        private byte[] raw;
        private BooleanSupplier canceled;
        private ByteBuffer input;
        private final ArrayList<Guard> spans = new ArrayList<>();
        private final ArrayList<WmoFormats.Batch> batches = new ArrayList<>();
        private final ArrayList<WmoFormats.Chunk> chunks = new ArrayList<>();
        private WmoFormats.GroupHeader header;
        private Floats positions, normals, uv;
        private Indices indices;
        private Bytes triangles, colors, liquid;
        private int budget = 4096, seen, triangleBytes;
        private int extraUv, extraColors;

        /** Vertex counts of the extra MOTV and MOCV sets, checked once MOVT is known. */
        private final ArrayList<Integer> extraVertices = new ArrayList<>();

        private boolean version, group;

        Parser(byte[] raw, BooleanSupplier canceled) {
            this.raw = raw;
            this.canceled = canceled;
            input = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        }

        private void claim(int bytes) {
            require(bytes >= 0 && budget <= MAX_METADATA_BYTES - bytes, "Group metadata limit");
            budget += bytes;
        }

        private int chunk(int end, boolean nested) {
            check(canceled);
            require(chunks.size() < MAX_CHUNKS && end - input.position() >= 8, "Group chunk limit");
            int tag = input.getInt(), size = input.getInt();
            require(size >= 0 && size <= end - input.position(), "Group chunk span");
            claim(96);
            boolean parsed = nested ? bit(tag) != 0 && tag != MLIQ : tag == MVER || tag == MOGP;
            chunks.add(new WmoFormats.Chunk(tag, size, parsed));
            return tag;
        }

        void parse() {
            while (input.hasRemaining()) {
                int tag = chunk(input.limit(), false);
                int size = chunks.getLast().bytes(), end = input.position() + size;
                if (tag == MVER) {
                    require(!version && size == 4 && input.getInt() == 17, "Group version17");
                    version = true;
                } else if (tag == MOGP) {
                    require(!group && size >= 68, "Group MOGP declaration");
                    group = true;
                    claim(512);
                    header =
                            new WmoFormats.GroupHeader(
                                    u32(), u32(), u32(), vector(), vector(), u16(), u16(), u16(),
                                    u16(), u16(), u16(), u32(), u32(), u32(), u32(), u32());
                    while (input.position() < end) {
                        int nested = chunk(end, true);
                        int bytes = chunks.getLast().bytes(), nestedEnd = input.position() + bytes;
                        mesh(nested, bytes);
                        input.position(nestedEnd);
                    }
                }
                input.position(end);
            }
            require(version && group && (seen & 63) == 63, "Group required streams");
            int vertices = positions.size() / 3;
            for (int count : extraVertices) {
                require(count == vertices, "Group extra set count");
            }
            require(
                    normals.size() == positions.size()
                            && uv.size() == vertices * 2
                            && triangles.size() == indices.size() / 3 * triangleBytes
                            && (colors == null || colors.size() == vertices * 4)
                            && (long) header.batchesA() + header.batchesB() + header.batchesC()
                                    == batches.size(),
                    "Group stream/header counts");
            for (int i = 0; i < indices.size(); i++) {
                require(indices.get(i) < vertices, "Group index domain");
            }
            long work = 0;
            for (var batch : batches) {
                check(canceled);
                work += batch.indexCount();
                require(work <= 3L * MAX_TRIANGLES, "Group batch work limit");
                require(
                        batch.firstIndex() % 3 == 0
                                && batch.indexCount() % 3 == 0
                                && batch.firstIndex() + batch.indexCount() <= indices.size()
                                && batch.firstVertex() <= batch.lastVertex()
                                && batch.lastVertex() < vertices,
                        "Group batch range");
                for (long i = batch.firstIndex();
                        i < batch.firstIndex() + batch.indexCount();
                        i++) {
                    int index = indices.get((int) i);
                    require(
                            index >= batch.firstVertex() && index <= batch.lastVertex(),
                            "Group batch vertex range");
                }
            }
            check(canceled);
        }

        private void mesh(int tag, int bytes) {
            int bit = bit(tag);
            if (bit != 0 && (seen & bit) != 0 && extra(tag, bytes)) {
                return;
            }
            if (bit != 0) {
                require((seen & bit) == 0, "Duplicate group stream");
                seen |= bit;
            }
            switch (tag) {
                case MOVT -> positions = floats(bytes, 3, MAX_VERTICES);
                case MONR -> normals = floats(bytes, 3, MAX_VERTICES);
                case MOTV -> uv = floats(bytes, 2, MAX_VERTICES);
                case MOVI -> {
                    int triangles = count(bytes, 6, MAX_TRIANGLES);
                    claim(256);
                    indices = new Indices(raw, canceled, input.position(), triangles * 3);
                    spans.add(indices);
                }
                case MOPY, MPY2 -> {
                    triangleBytes = tag == MOPY ? 2 : 4;
                    count(bytes, triangleBytes, MAX_TRIANGLES);
                    triangles = bytes(bytes);
                }
                case MOCV -> {
                    count(bytes, 4, MAX_VERTICES);
                    colors = bytes(bytes);
                }
                case MLIQ -> liquid = bytes(bytes);
                case MOBA -> {
                    int count = count(bytes, 24, MAX_BATCHES);
                    for (int i = 0; i < count; i++) {
                        check(canceled);
                        claim(512);
                        var bounds = new ArrayList<Integer>(6);
                        for (int b = 0; b < 6; b++) {
                            bounds.add(u16());
                        }
                        batches.add(
                                new WmoFormats.Batch(
                                        bounds,
                                        u32(),
                                        u16(),
                                        u16(),
                                        u16(),
                                        Byte.toUnsignedInt(input.get()),
                                        Byte.toUnsignedInt(input.get())));
                    }
                }
                default -> {
                    /* Unparsed source facts only. */
                }
            }
        }

        /**
         * Admits a repeated MOTV or MOCV that MOGP declares as an extra set: validated, recorded
         * for the vertex-count check, and otherwise ignored. False leaves the duplicate to fail.
         */
        private boolean extra(int tag, int bytes) {
            long flags = header.flags();
            if (tag == MOTV
                    && extraUv < ((flags & UV2) != 0 ? 1 : 0) + ((flags & UV3) != 0 ? 1 : 0)) {
                extraUv++;
                extraVertices.add(floats(bytes, 2, MAX_VERTICES).size() / 2);
                return true;
            }
            if (tag == MOCV && extraColors < ((flags & COLORS2) != 0 ? 1 : 0)) {
                extraColors++;
                extraVertices.add(count(bytes, 4, MAX_VERTICES));
                return true;
            }
            return false;
        }

        private Floats floats(int bytes, int width, int maximum) {
            int values = count(bytes, width * 4, maximum) * width;
            claim(256);
            var span = new Floats(raw, canceled, input.position(), values);
            spans.add(span);
            for (int i = 0; i < values; i++) {
                require(Float.isFinite(span.get(i)), "Nonfinite group operand");
            }
            return span;
        }

        private Bytes bytes(int bytes) {
            claim(256);
            var span = new Bytes(raw, canceled, input.position(), bytes);
            spans.add(span);
            return span;
        }

        private WorldPlacements.Vec3 vector() {
            return new WorldPlacements.Vec3(input.getFloat(), input.getFloat(), input.getFloat());
        }

        private long u32() {
            return Integer.toUnsignedLong(input.getInt());
        }

        private int u16() {
            return Short.toUnsignedInt(input.getShort());
        }

        private void expire() {
            for (var span : spans) {
                span.expire();
            }
            spans.clear();
            raw = null;
            canceled = null;
            input = null;
            header = null;
            positions = null;
            normals = null;
            uv = null;
            indices = null;
            triangles = null;
            colors = null;
            liquid = null;
            batches.clear();
            chunks.clear();
        }
    }

    private static int count(int bytes, int stride, int maximum) {
        require(bytes % stride == 0 && bytes / stride <= maximum, "Group stream count");
        return bytes / stride;
    }

    private static int bit(int tag) {
        return switch (tag) {
            case MOVT -> 1;
            case MONR -> 2;
            case MOTV -> 4;
            case MOVI -> 8;
            case MOPY, MPY2 -> 16;
            case MOBA -> 32;
            case MOCV -> 64;
            case MLIQ -> 128;
            default -> 0;
        };
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
