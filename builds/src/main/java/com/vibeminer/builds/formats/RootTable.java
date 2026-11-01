package com.vibeminer.builds.formats;

import com.vibeminer.network.CdnKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** TSFM v1/v2 ROOT metadata, preserving every membership and its original block order. */
public final class RootTable {
    public static final long NO_NAMES = 0x10000000L;

    public record Limits(
            long maxDecodedBytes, long maxRecords, int maxBlocks, int maxEntriesPerBlock) {
        public Limits {
            if (maxDecodedBytes < 20 || maxRecords < 0 || maxBlocks < 0 || maxEntriesPerBlock < 0) {
                throw new IllegalArgumentException("Invalid ROOT bounds");
            }
        }

        public Limits() {
            this(512L << 20, 50_000_000, 1_000_000, 2_000_000);
        }
    }

    public record RawFlags(long first, long second, int third) {}

    public record Entry(
            long fileDataId,
            CdnKey contentKey,
            long localeFlags,
            long contentFlags,
            RawFlags raw) {}

    private record Membership(long id, int block, CdnKey key) {}

    private record Block(long locale, long flags, RawFlags raw) {}

    private final int version, fileCount, nameHashConflicts;
    private final long localeUnion;
    private final List<Membership> entries;
    private final List<Block> blocks;
    private final Map<Long, Long> names;

    private RootTable(
            int version,
            List<Membership> entries,
            List<Block> blocks,
            Map<Long, Long> names,
            int files,
            long union,
            int conflicts) {
        this.version = version;
        this.entries = List.copyOf(entries);
        this.blocks = List.copyOf(blocks);
        this.names = Map.copyOf(names);
        fileCount = files;
        localeUnion = union;
        nameHashConflicts = conflicts;
    }

    public int version() {
        return version;
    }

    public int fileCount() {
        return fileCount;
    }

    public long entryCount() {
        return entries.size();
    }

    public int blockCount() {
        return blocks.size();
    }

    public long localeUnion() {
        return localeUnion;
    }

    public int nameHashConflicts() {
        return nameHashConflicts;
    }

    public List<Entry> find(long id) {
        unsigned(id);
        int lo = lower(id);
        List<Entry> result = new ArrayList<>();
        for (int i = lo; i < entries.size() && entries.get(i).id() == id; i++) {
            Membership e = entries.get(i);
            Block block = blocks.get(e.block());
            result.add(new Entry(id, e.key(), block.locale(), block.flags(), block.raw()));
        }
        return List.copyOf(result);
    }

    public Long findByNameHash(long hash) {
        return names.get(hash);
    }

    public Iterable<Long> fileDataIds(long localeMask, long fromId) {
        unsigned(fromId);
        unsigned(localeMask);
        return () ->
                new Iterator<>() {
                    private int at = lower(fromId);
                    private Long next = advance();

                    private Long advance() {
                        while (at < entries.size()) {
                            long id = entries.get(at).id();
                            boolean listed = false;
                            while (at < entries.size() && entries.get(at).id() == id) {
                                listed |=
                                        (blocks.get(entries.get(at++).block()).locale()
                                                        & localeMask)
                                                != 0;
                            }
                            if (listed) {
                                return id;
                            }
                        }
                        return null;
                    }

                    @Override
                    public boolean hasNext() {
                        return next != null;
                    }

                    @Override
                    public Long next() {
                        if (next == null) {
                            throw new NoSuchElementException();
                        }
                        Long value = next;
                        next = advance();
                        return value;
                    }
                };
    }

    private int lower(long id) {
        int lo = 0, hi = entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(mid).id() < id) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public static RootTable parse(byte[] bytes, Limits limits, Runnable cancellation) {
        cancellation.run();
        Cursor r = new Cursor(bytes);
        if (bytes.length > limits.maxDecodedBytes()) {
            throw limit("Decoded ROOT exceeds byte bound");
        }
        byte[] magic = r.take(4);
        if (magic[0] != 'T' || magic[1] != 'S' || magic[2] != 'F' || magic[3] != 'M') {
            throw FormatSupport.unsupported("ROOT_UNSUPPORTED", "ROOT has no TSFM signature");
        }
        if (shortHeader(bytes)) {
            throw FormatSupport.unsupported(
                    "ROOT_UNSUPPORTED_LAYOUT", "Pre-10.1.7 short TSFM header is unsupported");
        }
        long header = r.u32(), version = r.u32(), total = r.u32(), named = r.u32();
        if ((version != 1 && version != 2)
                || (header != 20 && header != 24)
                || version == 2 && header != 24) {
            throw FormatSupport.unsupported("ROOT_UNSUPPORTED", "Unsupported ROOT version/header");
        }
        if (header == 24 && r.u32() != 0) {
            throw FormatSupport.unsupported(
                    "ROOT_UNSUPPORTED", "Unsupported ROOT header extension");
        }
        if (total > limits.maxRecords()) {
            throw limit("ROOT declared count exceeds bound");
        }
        if (named > total) {
            throw invalid("Named count exceeds total");
        }
        List<Membership> all = new ArrayList<>((int) Math.min(total, r.remaining() / 20));
        List<Block> blocks = new ArrayList<>();
        Map<Long, Long> names = new HashMap<>();
        Set<Long> conflicts = new HashSet<>();
        long observed = 0, observedNamed = 0, union = 0;
        while (r.remaining() > 0) {
            cancellation.run();
            int block = blocks.size();
            if (block >= limits.maxBlocks()) {
                throw limit("ROOT block count exceeds bound");
            }
            long count = r.u32(), first, second = 0, locale;
            int third = 0;
            if (version == 1) {
                first = r.u32();
                locale = r.u32();
            } else {
                locale = r.u32();
                first = r.u32();
                second = r.u32();
                third = r.u8();
            }
            if (count > limits.maxEntriesPerBlock() || count > limits.maxRecords() - observed) {
                throw limit("ROOT membership count exceeds bound");
            }
            if (count > total - observed) {
                throw invalid("ROOT block exceeds declared count");
            }
            long flags = first | second | ((long) third << 17);
            boolean hasNames = (flags & NO_NAMES) == 0;
            long framing = count * (hasNames ? 28L : 20L);
            if (framing > limits.maxDecodedBytes() - r.at) {
                throw limit("ROOT framing exceeds byte bound");
            }
            if (hasNames && count > named - observedNamed) {
                throw invalid("ROOT block exceeds named count");
            }
            if (framing > r.remaining()) {
                throw invalid("Truncated ROOT block");
            }
            int n = (int) count;
            ByteBuffer deltas = ByteBuffer.wrap(r.take(n * 4)).order(ByteOrder.LITTLE_ENDIAN);
            byte[] keys = r.take(n * 16);
            ByteBuffer hashes =
                    ByteBuffer.wrap(hasNames ? r.take(n * 8) : new byte[0])
                            .order(ByteOrder.LITTLE_ENDIAN);
            long next = 0;
            for (int i = 0; i < n; i++) {
                if ((i & 65535) == 65535) {
                    cancellation.run();
                }
                long id = next + Integer.toUnsignedLong(deltas.getInt());
                if (id > 0xffff_ffffL) {
                    throw invalid("FileDataID delta overflows unsigned32");
                }
                next = id + 1;
                all.add(new Membership(id, block, FormatSupport.key(keys, i * 16)));
                if (hasNames) {
                    long hash = hashes.getLong();
                    Long previous = names.putIfAbsent(hash, id);
                    if (previous != null && previous != id) {
                        conflicts.add(hash);
                    }
                }
            }
            observed += count;
            if (hasNames) {
                observedNamed += count;
            }
            blocks.add(
                    new Block(
                            locale,
                            flags,
                            version == 2 ? new RawFlags(first, second, third) : null));
            union |= locale;
        }
        if (observed != total || observedNamed != named) {
            throw invalid("Declared and observed ROOT counts differ");
        }
        cancellation.run();
        all.sort(Comparator.comparingLong(Membership::id).thenComparingInt(Membership::block));
        cancellation.run();
        int files = 0;
        long previous = -1;
        for (Membership e : all) {
            if (e.id() != previous) {
                files++;
                previous = e.id();
            }
        }
        return new RootTable((int) version, all, blocks, names, files, union, conflicts.size());
    }

    private static boolean shortHeader(byte[] bytes) {
        if (bytes.length < 24) {
            return false;
        }
        long total = FormatSupport.le32(bytes, 4),
                named = FormatSupport.le32(bytes, 8),
                first = FormatSupport.le32(bytes, 12);
        return total != 20
                && total != 24
                && named <= total
                && first >= 1
                && first <= total
                && 24 + first * 20 <= bytes.length;
    }

    private static void unsigned(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Unsigned32 value required");
        }
    }

    private static com.vibeminer.builds.BuildFormatException invalid(String message) {
        return FormatSupport.corrupt("ROOT_INVALID", message);
    }

    private static com.vibeminer.builds.BuildFormatException limit(String message) {
        return FormatSupport.corrupt("ROOT_LIMIT", message);
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int at;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int remaining() {
            return bytes.length - at;
        }

        byte[] take(int n) {
            if (n < 0 || n > remaining()) {
                throw invalid("ROOT truncated at decoded offset " + at);
            }
            byte[] result = FormatSupport.slice(bytes, at, n);
            at += n;
            return result;
        }

        long u32() {
            return Integer.toUnsignedLong(
                    ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).getInt());
        }

        int u8() {
            return Byte.toUnsignedInt(take(1)[0]);
        }
    }
}
