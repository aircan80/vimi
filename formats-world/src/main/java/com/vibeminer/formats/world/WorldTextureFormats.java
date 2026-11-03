package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Narrow split ADT18 basic-coverage facts; never client height-shader/material authority. */
public final class WorldTextureFormats {
    public static final String REVISION = "adt18-split-basic-coverage-v1";
    public static final int MAX_SOURCE_BYTES = 8 << 20;
    public static final int MAX_MASK_SCRATCH = 7 * 4096;
    public static final int MAX_METADATA_BYTES = 1 << 20;
    private static final int MVER = 0x4d564552,
            MAMP = 0x4d414d50,
            MDID = 0x4d444944,
            MHID = 0x4d484944,
            MCNK = 0x4d434e4b,
            MCLY = 0x4d434c59,
            MCAL = 0x4d43414c;

    private WorldTextureFormats() {}

    public record Inventory(WorldFormats.World world, long flags) {
        public Inventory {
            Objects.requireNonNull(world);
        }
    }

    public enum ChunkIssue {
        NONE,
        CHUNK_SHAPE,
        LAYER_SHAPE,
        LAYER_FLAGS,
        TEXTURE_REFERENCE,
        MASK_INTERVAL,
        MASK_ENCODING,
        MASK_SUM
    }

    public record Layer(long textureIndex, long flags, long alphaOffset, int effectId) {}

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

    private record ChunkSlice(ChunkTexture metadata, Slice alpha) {}

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
        if (!maid || flags == null || flags != 0x3ca) {
            throw invalid("Unsupported basic-coverage WDT flags");
        }
        return new Inventory(world, flags);
    }

    /** Owns one defensive source copy. Alpha decoding never retains a caller's scratch buffer. */
    public static final class TextureData implements AutoCloseable {
        private byte[] source;
        private final List<Long> diffuse, heights;
        private final List<ChunkSlice> chunks;
        private final List<ChunkTexture> metadata;

        private TextureData(
                byte[] source, List<Long> diffuse, List<Long> heights, List<ChunkSlice> chunks) {
            this.source = source;
            this.diffuse = List.copyOf(diffuse);
            this.heights = List.copyOf(heights);
            this.chunks = List.copyOf(chunks);
            this.metadata = chunks.stream().map(ChunkSlice::metadata).toList();
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

        public synchronized AlphaResult decodeAlphas(
                int ordinal, ByteBuffer target, BooleanSupplier canceled) {
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
                if (entry.flags() == 0x100) {
                    valid = end - at == 4096;
                    if (valid) {
                        target.put(source, at, 4096);
                    }
                } else {
                    valid = rle(source, at, end, target, canceled);
                }
                if (!valid) {
                    target.position(start);
                    return new AlphaResult(0, ChunkIssue.MASK_ENCODING);
                }
            }
            for (int pixel = 0; pixel < 4096; pixel++) {
                if (pixel % 256 == 0) {
                    check(canceled);
                }
                int sum = 0;
                for (int layer = 0; layer < count; layer++) {
                    sum += target.get(start + layer * 4096 + pixel) & 255;
                }
                if (sum > 255) {
                    target.position(start);
                    return new AlphaResult(0, ChunkIssue.MASK_SUM);
                }
            }
            return new AlphaResult(count, ChunkIssue.NONE);
        }

        @Override
        public synchronized void close() {
            source = null;
        }
    }

    public static TextureData read(byte[] bytes, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length < 12 || bytes.length > MAX_SOURCE_BYTES) {
            throw invalid("Texture source bound");
        }
        byte[] owned = bytes.clone();
        var input = ByteBuffer.wrap(owned).order(ByteOrder.LITTLE_ENDIAN);
        boolean version = false, amplitude = false;
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
                    if (amplitude || s.length() != 4 || input.getInt(s.start()) != 0) {
                        throw invalid("Unique MAMP0 required");
                    }
                    amplitude = true;
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
                default -> throw invalid("Unsupported texture top-level chunk");
            }
            input.position(s.end());
        }
        if (!version
                || !amplitude
                || diffuse == null
                || heights == null
                || diffuse.size() != heights.size()
                || heights.stream().anyMatch(id -> id != 0)
                || rawChunks.size() != 256) {
            throw invalid("Incomplete split basic-coverage texture declarations");
        }
        var chunks = new ArrayList<ChunkSlice>();
        for (int i = 0; i < rawChunks.size(); i++) {
            check(canceled);
            chunks.add(textureChunk(input, rawChunks.get(i), i, diffuse));
        }
        var result = new TextureData(owned, diffuse, heights, chunks);
        if (result.metadataBytes() > MAX_METADATA_BYTES) {
            throw invalid("Texture metadata byte bound");
        }
        return result;
    }

    private static ChunkSlice textureChunk(
            ByteBuffer input, Slice bounds, int ordinal, List<Long> diffuse) {
        Slice layers = null, alpha = null;
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
                if (p.tag() == MCLY && layers == null) {
                    layers = p.data();
                } else {
                    if (p.tag() == MCAL && alpha == null) {
                        alpha = p.data();
                    } else {
                        issue = ChunkIssue.CHUNK_SHAPE;
                    }
                }
                input.position(p.data().end());
            }
        } catch (IllegalArgumentException invalid) {
            issue = ChunkIssue.CHUNK_SHAPE;
        }
        var entries = new ArrayList<Layer>();
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
                if (i == 0 ? l.flags() != 0 : l.flags() != 0x100 && l.flags() != 0x300) {
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
            if (entries.size() == 1 && alpha.length() != 0) {
                issue = ChunkIssue.MASK_INTERVAL;
            }
        }
        return new ChunkSlice(new ChunkTexture(ordinal, entries, issue), alpha);
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
