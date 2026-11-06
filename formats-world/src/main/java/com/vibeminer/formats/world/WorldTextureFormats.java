package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Narrow split ADT18 basic-coverage facts; never client height-shader/material authority. */
public final class WorldTextureFormats {
    public static final String REVISION = "adt18-split-basic-coverage-v2";

    /** MPHD flags that select 8-bit (4096-byte) alpha maps: big alpha or height texturing. */
    public static final long BIG_ALPHA_FLAGS = 0x4 | 0x80;

    /** Root MCNK flag: a 4-bit alpha map already holds its own last row and column. */
    public static final long DO_NOT_FIX_ALPHA_MAP = 0x8000;

    /** MCLY flag: this layer blends through an alpha map. */
    public static final long USE_ALPHA_MAP = 0x100;

    /** MCLY flag: this layer's alpha map is run-length compressed. */
    public static final long ALPHA_COMPRESSED = 0x200;

    /** MCLY layer animation bits (rotation, speed and enable). */
    public static final long ANIMATION_FLAGS = 0x7f;

    /** MCLY overbright bit. */
    public static final long OVERBRIGHT = 0x80;

    public static final int MAX_SOURCE_BYTES = 8 << 20;
    public static final int MAX_MASK_SCRATCH = 7 * 4096;
    public static final int MAX_METADATA_BYTES = 1 << 20;
    private static final int MVER = 0x4d564552,
            MAMP = 0x4d414d50,
            MDID = 0x4d444944,
            MHID = 0x4d484944,
            MCNK = 0x4d434e4b,
            MCLY = 0x4d434c59,
            MCAL = 0x4d43414c,
            MCSH = 0x4d435348;

    /** MCSH payload: a 64x64 one-bit shadow map. */
    public static final int SHADOW_BYTES = 512;

    private WorldTextureFormats() {}

    /** How a map stores uncompressed alpha maps, derived from its WDT MPHD flags. */
    public enum AlphaFormat {
        /** 4096 bytes, one 8-bit value per texel. */
        BIG_8BIT,
        /** 2048 bytes, two 4-bit values per byte, low nibble first. */
        SMALL_4BIT;

        public static AlphaFormat of(long mphdFlags) {
            return (mphdFlags & BIG_ALPHA_FLAGS) != 0 ? BIG_8BIT : SMALL_4BIT;
        }
    }

    public record Inventory(WorldFormats.World world, long flags) {
        public Inventory {
            Objects.requireNonNull(world);
        }

        public AlphaFormat alphaFormat() {
            return AlphaFormat.of(flags);
        }
    }

    /** Whether a tex0 chunk carries an MCSH shadow map. Never affects layer coverage. */
    public enum ShadowState {
        ABSENT,
        BITS,
        UNSUPPORTED
    }

    public enum ChunkIssue {
        NONE,
        CHUNK_SHAPE,
        LAYER_SHAPE,
        LAYER_FLAGS,
        TEXTURE_REFERENCE,
        MASK_INTERVAL,
        MASK_ENCODING
    }

    public record Layer(long textureIndex, long flags, long alphaOffset, int effectId) {
        /** Animation bits; carried, not yet rendered. */
        public long animation() {
            return flags & ANIMATION_FLAGS;
        }

        /** Carried, not yet rendered. */
        public boolean overbright() {
            return (flags & OVERBRIGHT) != 0;
        }

        /** The layer's 0x200 bit; it means RLE only in an 8-bit alpha layout. */
        public boolean compressed() {
            return (flags & ALPHA_COMPRESSED) != 0;
        }
    }

    public record ChunkTexture(int ordinal, List<Layer> layers, ChunkIssue issue) {
        public ChunkTexture {
            Objects.requireNonNull(layers);
            Objects.requireNonNull(issue);
            if (ordinal < 0 || ordinal >= 256 || layers.size() > 8) {
                throw new IllegalArgumentException("Invalid texture chunk bounds");
            }
            layers = List.copyOf(layers);
        }
    }

    public record AlphaResult(int maskCount, ChunkIssue issue) {
        public AlphaResult {
            Objects.requireNonNull(issue);
            if (maskCount < 0 || maskCount > 7 || (issue != ChunkIssue.NONE && maskCount != 0)) {
                throw new IllegalArgumentException("Invalid alpha result count");
            }
        }

        public boolean admitted() {
            return issue == ChunkIssue.NONE;
        }
    }

    private record Slice(int start, int end) {
        int length() {
            return end - start;
        }
    }

    private record ChunkSlice(
            ChunkTexture metadata, Slice alpha, ShadowState shadow, Slice shadowBits) {}

    private record Part(int tag, Slice data) {}

    public static Inventory inventory(byte[] bytes, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length > 1 << 20) {
            throw invalid("WDT source bound");
        }
        var world = WorldFormats.wdt(bytes, canceled);
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Long flags = null;
        boolean maid = false;
        while (input.hasRemaining()) {
            check(canceled);
            var part = part(input, input.limit());
            if (part.tag() == 0x4d504844) {
                if (flags != null || part.data().length() != 32) {
                    throw invalid("Unique MPHD32 required");
                }
                flags = Integer.toUnsignedLong(input.getInt(part.data().start()));
            }
            if (part.tag() == 0x4d414944) {
                if (maid || part.data().length() != 4096 * 32) {
                    throw invalid("Unique full MAID required");
                }
                maid = true;
            }
            input.position(part.data().end());
        }
        // Any MPHD flags are accepted; only the alpha map layout depends on them.
        if (!maid || flags == null) {
            throw invalid("Split texture inventory requires MPHD and MAID");
        }
        return new Inventory(world, flags);
    }

    /** Owns one defensive source copy. Alpha decoding never retains a caller's scratch buffer. */
    public static final class TextureData implements AutoCloseable {
        private byte[] source;
        private final AlphaFormat format;
        private final int amplifier;
        private final List<Long> diffuse, heights;
        private final List<ChunkSlice> chunks;
        private final List<ChunkTexture> metadata;

        private TextureData(
                byte[] source,
                AlphaFormat format,
                int amplifier,
                List<Long> diffuse,
                List<Long> heights,
                List<ChunkSlice> chunks) {
            this.source = source;
            this.format = format;
            this.amplifier = amplifier;
            this.diffuse = List.copyOf(diffuse);
            this.heights = List.copyOf(heights);
            this.chunks = List.copyOf(chunks);
            this.metadata = chunks.stream().map(ChunkSlice::metadata).toList();
        }

        public AlphaFormat alphaFormat() {
            return format;
        }

        /** MAMP texture amplifier (0 when absent); carried, not yet applied. */
        public int amplifier() {
            return amplifier;
        }

        public List<Long> diffuseIds() {
            return diffuse;
        }

        public List<Long> heightIds() {
            return heights;
        }

        public List<ChunkTexture> chunks() {
            return metadata;
        }

        public synchronized int retainedSourceBytes() {
            return source == null ? 0 : source.length;
        }

        public long metadataBytes() {
            return 4096L
                    + 16L * (diffuse.size() + heights.size())
                    + chunks.stream()
                            .mapToLong(c -> 128L + 64L * c.metadata().layers().size())
                            .sum();
        }

        /**
         * Decodes as if the root MCNK flags were 0, the common case: a 4-bit map's last row and
         * column are fixed. Pass the real flags when they are known.
         */
        public AlphaResult decodeAlphas(int ordinal, ByteBuffer target, BooleanSupplier canceled) {
            return decodeAlphas(ordinal, 0, target, canceled);
        }

        /**
         * Writes one 64x64 8-bit mask per overlay layer. {@code rootChunkFlags} are the root ADT
         * MCNK flags: without {@link #DO_NOT_FIX_ALPHA_MAP}, a 4-bit map's last row and column
         * repeat the ones before them, as the client does. Weights summing past 255 are kept as
         * stored; the consumer clamps the base layer weight.
         */
        public synchronized AlphaResult decodeAlphas(
                int ordinal, long rootChunkFlags, ByteBuffer target, BooleanSupplier canceled) {
            Objects.requireNonNull(target);
            Objects.requireNonNull(canceled);
            check(canceled);
            if (source == null) {
                throw new IllegalStateException("Texture source closed");
            }
            if (ordinal < 0 || ordinal >= chunks.size()) {
                throw new IllegalArgumentException("Invalid texture chunk");
            }
            var chunk = chunks.get(ordinal);
            var row = chunk.metadata();
            if (row.issue() != ChunkIssue.NONE) {
                return new AlphaResult(0, row.issue());
            }
            int count = row.layers().size() - 1, start = target.position();
            if (target.isReadOnly() || target.remaining() < count * 4096) {
                throw new IllegalArgumentException("Insufficient writable mask scratch");
            }
            boolean fix = (rootChunkFlags & DO_NOT_FIX_ALPHA_MAP) == 0;
            for (int layer = 1; layer < row.layers().size(); layer++) {
                check(canceled);
                var entry = row.layers().get(layer);
                int at = chunk.alpha().start() + (int) entry.alphaOffset();
                int end =
                        layer + 1 < row.layers().size()
                                ? chunk.alpha().start()
                                        + (int) row.layers().get(layer + 1).alphaOffset()
                                : chunk.alpha().end();
                boolean valid;
                // Compression applies only to 8-bit maps; a 4-bit layout is always 2048 raw
                // bytes, whatever the layer's 0x200 bit says.
                if (format == AlphaFormat.BIG_8BIT && entry.compressed()) {
                    valid = rle(source, at, end, target, canceled);
                } else if (format == AlphaFormat.BIG_8BIT) {
                    valid = end - at == 4096;
                    if (valid) {
                        target.put(source, at, 4096);
                    }
                } else {
                    valid = end - at == 2048;
                    if (valid) {
                        nibbles(source, at, target, fix);
                    }
                }
                if (!valid) {
                    target.position(start);
                    return new AlphaResult(0, ChunkIssue.MASK_ENCODING);
                }
            }
            return new AlphaResult(count, ChunkIssue.NONE);
        }

        public ShadowState shadowState(int ordinal) {
            if (ordinal < 0 || ordinal >= chunks.size()) {
                throw new IllegalArgumentException("Invalid texture chunk");
            }
            return chunks.get(ordinal).shadow();
        }

        /**
         * Writes the chunk's 64x64 MCSH shadow map, one byte per texel: 255 shadowed, 0 lit. Bits
         * are read least significant first. Without {@link #DO_NOT_FIX_ALPHA_MAP} in the root MCNK
         * flags, the last row and column repeat the ones before them, as for alpha maps. Returns
         * false, writing nothing, when the chunk has no supported shadow map.
         */
        public synchronized boolean decodeShadow(
                int ordinal, long rootChunkFlags, ByteBuffer target, BooleanSupplier canceled) {
            Objects.requireNonNull(target);
            Objects.requireNonNull(canceled);
            check(canceled);
            if (source == null) {
                throw new IllegalStateException("Texture source closed");
            }
            if (shadowState(ordinal) != ShadowState.BITS) {
                return false;
            }
            if (target.isReadOnly() || target.remaining() < 4096) {
                throw new IllegalArgumentException("Insufficient writable shadow scratch");
            }
            var bits = chunks.get(ordinal).shadowBits();
            int base = target.position();
            for (int i = 0; i < SHADOW_BYTES; i++) {
                int packed = source[bits.start() + i] & 255;
                for (int bit = 0; bit < 8; bit++) {
                    target.put((byte) ((packed >>> bit & 1) != 0 ? 255 : 0));
                }
            }
            if ((rootChunkFlags & DO_NOT_FIX_ALPHA_MAP) == 0) {
                fixEdges(target, base);
            }
            return true;
        }

        @Override
        public synchronized void close() {
            source = null;
        }
    }

    /** Reads a map whose uncompressed alpha maps are 8-bit. */
    public static TextureData read(byte[] bytes, BooleanSupplier canceled) {
        return read(bytes, AlphaFormat.BIG_8BIT, canceled);
    }

    /**
     * Reads one split tex0 ADT. Chunks the coverage preview does not use (MTXF, MTXP, MCSH, MCMT,
     * ...) are skipped; MAMP and MHID are optional and carried as data.
     */
    public static TextureData read(byte[] bytes, AlphaFormat format, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(format);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length < 12 || bytes.length > MAX_SOURCE_BYTES) {
            throw invalid("Texture source bound");
        }
        byte[] owned = bytes.clone();
        var input = ByteBuffer.wrap(owned).order(ByteOrder.LITTLE_ENDIAN);
        boolean version = false, amplified = false;
        int amplifier = 0;
        List<Long> diffuse = null, heights = null;
        var rawChunks = new ArrayList<Slice>();
        int count = 0;
        while (input.hasRemaining()) {
            check(canceled);
            if (++count > 4096) {
                throw invalid("Texture chunk count");
            }
            var p = part(input, input.limit());
            var s = p.data();
            switch (p.tag()) {
                case MVER -> {
                    if (version || s.length() != 4 || input.getInt(s.start()) != 18) {
                        throw invalid("Texture version18");
                    }
                    version = true;
                }
                case MAMP -> {
                    // One amplifier byte, then padding.
                    if (amplified || s.length() < 1 || s.length() > 4) {
                        throw invalid("Unique MAMP required");
                    }
                    amplifier = input.get(s.start()) & 255;
                    amplified = true;
                }
                case MDID -> {
                    if (diffuse != null) {
                        throw invalid("Duplicate MDID");
                    }
                    diffuse = ids(input, s);
                }
                case MHID -> {
                    if (heights != null) {
                        throw invalid("Duplicate MHID");
                    }
                    heights = ids(input, s);
                }
                case MCNK -> {
                    if (rawChunks.size() == 256) {
                        throw invalid("Texture MCNK count");
                    }
                    rawChunks.add(s);
                }
                default -> {
                    // Not needed for layer coverage; skipped, still bounded by the chunk count.
                }
            }
            input.position(s.end());
        }
        if (heights == null && diffuse != null) {
            // No height textures declared: carried as zero IDs, one per diffuse.
            heights = Collections.nCopies(diffuse.size(), 0L);
        }
        if (!version
                || diffuse == null
                || diffuse.size() != heights.size()
                || rawChunks.size() != 256) {
            throw invalid("Incomplete split basic-coverage texture declarations");
        }
        var chunks = new ArrayList<ChunkSlice>();
        for (int i = 0; i < rawChunks.size(); i++) {
            check(canceled);
            chunks.add(textureChunk(input, rawChunks.get(i), i, diffuse));
        }
        var result = new TextureData(owned, format, amplifier, diffuse, heights, chunks);
        if (result.metadataBytes() > MAX_METADATA_BYTES) {
            throw invalid("Texture metadata byte bound");
        }
        return result;
    }

    private static ChunkSlice textureChunk(
            ByteBuffer input, Slice bounds, int ordinal, List<Long> diffuse) {
        Slice layers = null, alpha = null, shadowBits = null;
        var shadow = ShadowState.ABSENT;
        ChunkIssue issue = ChunkIssue.NONE;
        int count = 0;
        input.position(bounds.start());
        try {
            while (input.position() < bounds.end()) {
                if (++count > 16) {
                    issue = ChunkIssue.CHUNK_SHAPE;
                    break;
                }
                var p = part(input, bounds.end());
                if (p.tag() == MCLY) {
                    if (layers != null) {
                        issue = ChunkIssue.CHUNK_SHAPE;
                    }
                    layers = p.data();
                } else if (p.tag() == MCAL) {
                    if (alpha != null) {
                        issue = ChunkIssue.CHUNK_SHAPE;
                    }
                    alpha = p.data();
                } else if (p.tag() == MCSH) {
                    if (shadow == ShadowState.ABSENT && p.data().length() == SHADOW_BYTES) {
                        shadow = ShadowState.BITS;
                        shadowBits = p.data();
                    } else {
                        shadow = ShadowState.UNSUPPORTED;
                        shadowBits = null;
                    }
                }
                // MCMT and other sub-chunks do not affect layer coverage.
                input.position(p.data().end());
            }
        } catch (IllegalArgumentException invalid) {
            issue = ChunkIssue.CHUNK_SHAPE;
        }
        var entries = new ArrayList<Layer>();
        if (alpha == null && layers != null && layers.length() == 16) {
            // A lone base layer needs no alpha maps, so MCAL may be omitted.
            alpha = new Slice(layers.end(), layers.end());
        }
        if (layers == null
                || alpha == null
                || layers.length() < 16
                || layers.length() > 128
                || layers.length() % 16 != 0) {
            issue = ChunkIssue.LAYER_SHAPE;
        } else {
            for (int at = layers.start(); at < layers.end(); at += 16) {
                entries.add(
                        new Layer(
                                Integer.toUnsignedLong(input.getInt(at)),
                                        Integer.toUnsignedLong(input.getInt(at + 4)),
                                Integer.toUnsignedLong(input.getInt(at + 8)),
                                        input.getInt(at + 12)));
            }
            for (int i = 0; i < entries.size(); i++) {
                var l = entries.get(i);
                // Animation, overbright and reflection bits are carried, not refused.
                if (i > 0 && (l.flags() & USE_ALPHA_MAP) == 0) {
                    issue = ChunkIssue.LAYER_FLAGS;
                }
                if (l.textureIndex() >= diffuse.size()
                        || diffuse.get((int) l.textureIndex()) == 0) {
                    issue = ChunkIssue.TEXTURE_REFERENCE;
                }
                if (l.alphaOffset() > alpha.length()
                        || i <= 1 && l.alphaOffset() != 0
                        || i > 1 && l.alphaOffset() <= entries.get(i - 1).alphaOffset()) {
                    issue = ChunkIssue.MASK_INTERVAL;
                }
            }
            // A lone base layer has no masks; any MCAL bytes there are ignored, as the client does.
        }
        return new ChunkSlice(new ChunkTexture(ordinal, entries, issue), alpha, shadow, shadowBits);
    }

    private static boolean rle(
            byte[] source, int at, int end, ByteBuffer target, BooleanSupplier canceled) {
        int produced = 0;
        while (produced < 4096) {
            check(canceled);
            if (at >= end) {
                return false;
            }
            int info = source[at++] & 255, count = info & 127;
            if (count == 0 || count > 4096 - produced) {
                return false;
            }
            if ((info & 128) != 0) {
                if (at >= end) {
                    return false;
                }
                byte value = source[at++];
                for (int i = 0; i < count; i++) {
                    target.put(value);
                }
            } else {
                if (count > end - at) {
                    return false;
                }
                target.put(source, at, count);
                at += count;
            }
            produced += count;
        }
        return at == end;
    }

    /** Expands one 2048-byte 4-bit map to 64x64 8-bit values, low nibble first. */
    private static void nibbles(byte[] source, int at, ByteBuffer target, boolean fix) {
        int base = target.position();
        for (int i = 0; i < 2048; i++) {
            int packed = source[at + i] & 255;
            target.put((byte) ((packed & 15) * 17));
            target.put((byte) ((packed >>> 4) * 17));
        }
        if (fix) {
            fixEdges(target, base);
        }
    }

    /** Copies column 62 into column 63 and row 62 into row 63 of one 64x64 map. */
    private static void fixEdges(ByteBuffer target, int base) {
        for (int row = 0; row < 64; row++) {
            target.put(base + row * 64 + 63, target.get(base + row * 64 + 62));
        }
        for (int column = 0; column < 64; column++) {
            target.put(base + 63 * 64 + column, target.get(base + 62 * 64 + column));
        }
    }

    private static List<Long> ids(ByteBuffer input, Slice bounds) {
        if (bounds.length() < 4 || bounds.length() > 64 * 4 || bounds.length() % 4 != 0) {
            throw invalid("Texture ID array bound");
        }
        var result = new ArrayList<Long>();
        for (int at = bounds.start(); at < bounds.end(); at += 4) {
            result.add(Integer.toUnsignedLong(input.getInt(at)));
        }
        return result;
    }

    private static Part part(ByteBuffer input, int end) {
        if (input.position() > end - 8) {
            throw invalid("Truncated texture chunk header");
        }
        int tag = input.getInt(), size = input.getInt(), start = input.position();
        if (size < 0 || size > end - start) {
            throw invalid("Texture chunk range");
        }
        return new Part(tag, new Slice(start, start + size));
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail);
    }
}
