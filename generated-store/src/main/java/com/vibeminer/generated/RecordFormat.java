package com.vibeminer.generated;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Portable VMRF format 1. Block directory entries include four reserved bytes, as in C#. */
final class RecordFormat {
    static final int HEADER = 40, CHUNK_ENTRIES = 1024, MAX = GeneratedTypes.MAX_OBJECT_BYTES;

    record Segment(GeneratedKey key, int length) {}

    record Frame(int segment, int offset, int compressed, int length) {}

    record Block(Frame frame, long first) {}

    record Chunk(Frame frame, int entries, long firstId) {}

    record Manifest(
            long count,
            List<Segment> segments,
            List<Block> blocks,
            List<Chunk> chunks,
            byte[] metadata) {}

    private RecordFormat() {}

    static GeneratedStoreException corrupt(String message) {
        return new GeneratedStoreException(GeneratedTypes.CORRUPT, "Record file: " + message);
    }

    static GeneratedStoreException limit(String message) {
        return new GeneratedStoreException(GeneratedTypes.LIMIT, "Record file: " + message);
    }

    static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] compress(byte[] body, int level) {
        try (ZstdCompressCtx context = new ZstdCompressCtx()) {
            return context.setLevel(level).setChecksum(true).setContentSize(true).compress(body);
        }
    }

    static byte[] decompress(byte[] compressed, int length) {
        if (length < 0 || length > MAX) {
            throw corrupt("Impossible frame size");
        }
        try {
            if (Zstd.getFrameContentSize(compressed) != length) {
                throw corrupt("Frame content size differs from directory");
            }
            byte[] output = Zstd.decompress(compressed, length);
            if (output.length != length) {
                throw corrupt("Decoded frame length differs from directory");
            }
            return output;
        } catch (GeneratedStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GeneratedStoreException(
                    GeneratedTypes.CORRUPT, "Record file: invalid zstd frame", e);
        }
    }

    static void writeVar(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) value | 128);
            value >>>= 7;
        }
        out.write((int) value);
    }

    static long readVar(ByteBuffer in) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (!in.hasRemaining()) {
                throw corrupt("Truncated number");
            }
            int b = Byte.toUnsignedInt(in.get());
            if (shift == 63 && b > 1) {
                throw corrupt("Oversized number");
            }
            value |= (long) (b & 127) << shift;
            if ((b & 128) == 0) {
                return value;
            }
        }
        throw corrupt("Oversized number");
    }

    static long read32(ByteBuffer in) {
        long value = readVar(in);
        if (value < 0 || value > 0xffff_ffffL) {
            throw corrupt("Oversized 32-bit number");
        }
        return value;
    }

    static void id(long id) {
        if (id < 0 || id > 0xffff_ffffL) {
            throw new IllegalArgumentException("Record id must be an unsigned 32-bit value");
        }
    }

    static byte[] encode(Manifest m, int level) {
        long length =
                bodyLength(
                        m.segments().size(),
                        m.blocks().size(),
                        m.chunks().size(),
                        m.metadata().length);
        if (length > MAX) {
            throw limit("Manifest directories exceed 64 MiB");
        }
        ByteBuffer body = buffer(new byte[(int) length]);
        for (Segment segment : m.segments()) {
            body.put(segment.key().bytes()).putInt(segment.length());
        }
        for (Block block : m.blocks()) {
            putFrame(body, block.frame());
            body.putLong(block.first()).putInt(0);
        }
        for (Chunk chunk : m.chunks()) {
            putFrame(body, chunk.frame());
            body.putInt(chunk.entries()).putInt((int) chunk.firstId());
        }
        body.put(m.metadata());
        byte[] compressed = compress(body.array(), level);
        if ((long) HEADER + compressed.length > MAX) {
            throw limit("Manifest exceeds 64 MiB");
        }
        ByteBuffer result = buffer(new byte[HEADER + compressed.length]);
        result.putInt(0x46524d56)
                .putShort((short) 1)
                .putShort((short) 0)
                .putLong(m.count())
                .putInt(m.segments().size())
                .putInt(m.blocks().size())
                .putInt(m.chunks().size())
                .putInt(m.metadata().length)
                .putInt(body.capacity())
                .putInt(compressed.length)
                .put(compressed);
        return result.array();
    }

    private static void putFrame(ByteBuffer body, Frame f) {
        body.putInt(f.segment()).putInt(f.offset()).putInt(f.compressed()).putInt(f.length());
    }

    static Manifest decode(byte[] bytes) {
        if (bytes.length < HEADER || bytes.length > MAX) {
            throw corrupt("Invalid manifest length");
        }
        ByteBuffer h = buffer(bytes);
        if (h.getInt() != 0x46524d56 || h.getShort() != 1 || h.getShort() != 0) {
            throw corrupt("Unknown manifest magic, version or flags");
        }
        long count = h.getLong();
        int ns = h.getInt(),
                nb = h.getInt(),
                nc = h.getInt(),
                nm = h.getInt(),
                length = h.getInt(),
                compressed = h.getInt();
        if (count < 0
                || count > 0xffff_ffffL
                || ns < 0
                || nb < 0
                || nc < 0
                || nm < 0
                || nb > count
                || nc > count
                || (count > 0 && (nb == 0 || nc == 0))
                || ns > (long) nb + nc) {
            throw corrupt("Impossible directory counts");
        }
        if (length < 0
                || length > MAX
                || length != bodyLength(ns, nb, nc, nm)
                || compressed != bytes.length - HEADER) {
            throw corrupt("Manifest length differs from its counts");
        }
        ByteBuffer body =
                buffer(
                        decompress(
                                java.util.Arrays.copyOfRange(bytes, HEADER, bytes.length), length));
        List<Segment> segments = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            byte[] key = new byte[16];
            body.get(key);
            int size = body.getInt();
            if (size <= 0 || size > MAX) {
                throw corrupt("Impossible segment length");
            }
            segments.add(new Segment(new GeneratedKey(key), size));
        }
        List<Block> blocks = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) {
            Frame frame = frame(body, segments);
            long first = body.getLong();
            body.getInt();
            if (first < 0
                    || first >= count
                    || (i == 0 ? first != 0 : first <= blocks.get(i - 1).first())) {
                throw corrupt("Invalid block ordinals");
            }
            blocks.add(new Block(frame, first));
        }
        List<Chunk> chunks = new ArrayList<>(nc);
        long entries = 0;
        for (int i = 0; i < nc; i++) {
            Frame frame = frame(body, segments);
            int n = body.getInt();
            long first = Integer.toUnsignedLong(body.getInt());
            if (n < 1
                    || n > CHUNK_ENTRIES
                    || (i > 0 && first <= chunks.get(i - 1).firstId())
                    || frame.length() > n * 15L) {
                throw corrupt("Invalid id directory");
            }
            chunks.add(new Chunk(frame, n, first));
            entries += n;
        }
        if (entries != count) {
            throw corrupt("Id index count differs");
        }
        byte[] metadata = new byte[nm];
        body.get(metadata);
        return new Manifest(
                count, List.copyOf(segments), List.copyOf(blocks), List.copyOf(chunks), metadata);
    }

    private static Frame frame(ByteBuffer body, List<Segment> segments) {
        Frame f = new Frame(body.getInt(), body.getInt(), body.getInt(), body.getInt());
        if (f.segment() < 0
                || f.segment() >= segments.size()
                || f.offset() < 0
                || f.compressed() <= 0
                || (long) f.offset() + f.compressed() > segments.get(f.segment()).length()
                || f.length() < 1
                || f.length() > MAX) {
            throw corrupt("Frame is outside its segment or size bound");
        }
        return f;
    }

    private static long bodyLength(long s, long b, long c, long m) {
        return s * 20 + b * 28 + c * 24 + m;
    }
}
