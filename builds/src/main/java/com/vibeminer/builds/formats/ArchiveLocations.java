package com.vibeminer.builds.formats;

import com.vibeminer.network.CdnKey;
import com.vibeminer.network.RangePlanner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Earliest configured archive, then lowest offset; size conflicts anywhere invalidate the index
 * set.
 */
public final class ArchiveLocations {
    public record Indexed(CdnKey archive, List<ArchiveIndex.Entry> entries) {
        public Indexed {
            entries = List.copyOf(entries);
        }
    }

    public record Located(RangePlanner.Location location, int archiveRank) {}

    public record Limits(int maxArchives, long maxEntries, long maxLooseEntries) {
        public Limits {
            if (maxArchives < 0 || maxEntries < 0 || maxLooseEntries < 0) {
                throw new IllegalArgumentException("Invalid location bounds");
            }
        }

        public Limits() {
            this(100000, 16_000_000, 4_000_000);
        }
    }

    private final Map<CdnKey, Located> rows;
    private final Map<CdnKey, Long> loose;
    private final int archiveCount;

    private ArchiveLocations(Map<CdnKey, Located> rows, Map<CdnKey, Long> loose, int archives) {
        this.rows = Map.copyOf(rows);
        this.loose = Map.copyOf(loose);
        archiveCount = archives;
    }

    public int archiveCount() {
        return archiveCount;
    }

    public int entryCount() {
        return rows.size();
    }

    public int looseCount() {
        return loose.size();
    }

    public Located locate(CdnKey key) {
        return rows.get(key);
    }

    public Long looseSize(CdnKey key) {
        return loose.get(key);
    }

    public static ArchiveLocations build(
            List<Indexed> archives,
            List<ArchiveIndex.Entry> fileIndex,
            Limits limits,
            Runnable cancellation) {
        cancellation.run();
        if (archives.size() > limits.maxArchives()) {
            throw limit();
        }
        long total = 0;
        for (Indexed archive : archives) {
            total += archive.entries().size();
            if (total > limits.maxEntries()) {
                throw limit();
            }
        }
        if (fileIndex != null && fileIndex.size() > limits.maxLooseEntries()) {
            throw limit();
        }
        Map<CdnKey, Located> rows = new HashMap<>();
        Map<CdnKey, Long> loose = new HashMap<>();
        for (int a = 0; a < archives.size(); a++) {
            cancellation.run();
            Indexed archive = archives.get(a);
            for (ArchiveIndex.Entry entry : archive.entries()) {
                cancellation.run();
                validate(entry);
                Located previous = rows.get(entry.key());
                if (previous != null) {
                    if (previous.location().size() != entry.encodedSize()) {
                        throw conflict();
                    }
                    if (previous.archiveRank() < a
                            || previous.location().offset() <= entry.offset()) {
                        continue;
                    }
                }
                rows.put(
                        entry.key(),
                        new Located(
                                new RangePlanner.Location(
                                        archive.archive(), entry.offset(), entry.encodedSize()),
                                a));
            }
        }
        if (fileIndex != null) {
            for (ArchiveIndex.Entry entry : fileIndex) {
                cancellation.run();
                validate(entry);
                Long previous = loose.putIfAbsent(entry.key(), entry.encodedSize());
                if (previous != null && previous != entry.encodedSize()) {
                    throw conflict();
                }
                Located found = rows.get(entry.key());
                if (found != null && found.location().size() != entry.encodedSize()) {
                    throw conflict();
                }
            }
        }
        return new ArchiveLocations(rows, loose, archives.size());
    }

    /** Bounded, authenticated-input projection; independent of source readers and workspaces. */
    public long projectionLength(List<CdnKey> archives) {
        if (archives.size() != archiveCount) {
            throw new IllegalArgumentException("Archive list changed");
        }
        return 52L + 16L * archives.size() + 28L * rows.size() + 20L * loose.size();
    }

    public void writeProjection(
            java.io.DataOutputStream output,
            List<CdnKey> archives,
            byte[] identity,
            Runnable cancellation)
            throws java.io.IOException {
        if (identity.length != 32 || archives.size() != archiveCount) {
            throw new IllegalArgumentException("Invalid projection identity");
        }
        output.writeInt(0x564d4958);
        output.writeInt(1);
        output.write(identity);
        output.writeInt(archiveCount);
        output.writeInt(rows.size());
        output.writeInt(loose.size());
        for (var archive : archives) {
            output.write(archive.bytes());
        }
        for (var entry : rows.entrySet()) {
            cancellation.run();
            output.write(entry.getKey().bytes());
            output.writeInt(entry.getValue().archiveRank());
            output.writeInt((int) entry.getValue().location().offset());
            output.writeInt((int) entry.getValue().location().size());
        }
        for (var entry : loose.entrySet()) {
            cancellation.run();
            output.write(entry.getKey().bytes());
            output.writeInt(entry.getValue().intValue());
        }
    }

    public static ArchiveLocations readProjection(
            byte[] bytes, List<CdnKey> archives, byte[] identity, Runnable cancellation)
            throws java.io.IOException {
        var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        if (input.readInt() != 0x564d4958
                || input.readInt() != 1
                || !java.security.MessageDigest.isEqual(input.readNBytes(32), identity)) {
            throw new java.io.IOException("Index projection identity differs");
        }
        int archiveCount = input.readInt(),
                rowCount = input.readInt(),
                looseCount = input.readInt();
        var limits = new Limits();
        if (archiveCount != archives.size()
                || archiveCount > limits.maxArchives()
                || rowCount < 0
                || rowCount > limits.maxEntries()
                || looseCount < 0
                || looseCount > limits.maxLooseEntries()
                || bytes.length != 52L + 16L * archiveCount + 28L * rowCount + 20L * looseCount) {
            throw new java.io.IOException("Invalid index projection bounds");
        }
        for (var archive : archives) {
            if (!java.util.Arrays.equals(input.readNBytes(16), archive.bytes())) {
                throw new java.io.IOException("Index projection archives differ");
            }
        }
        Map<CdnKey, Located> rows = HashMap.newHashMap(rowCount);
        Map<CdnKey, Long> loose = HashMap.newHashMap(looseCount);
        for (int i = 0; i < rowCount; i++) {
            cancellation.run();
            var key = new CdnKey(input.readNBytes(16));
            int rank = input.readInt();
            long offset = Integer.toUnsignedLong(input.readInt()),
                    length = Integer.toUnsignedLong(input.readInt());
            if (rank < 0
                    || rank >= archiveCount
                    || length == 0
                    || rows.putIfAbsent(
                                    key,
                                    new Located(
                                            new RangePlanner.Location(
                                                    archives.get(rank), offset, length),
                                            rank))
                            != null) {
                throw new java.io.IOException("Invalid index projection entry");
            }
        }
        for (int i = 0; i < looseCount; i++) {
            cancellation.run();
            var key = new CdnKey(input.readNBytes(16));
            long length = Integer.toUnsignedLong(input.readInt());
            if (length == 0
                    || loose.putIfAbsent(key, length) != null
                    || rows.containsKey(key) && rows.get(key).location().size() != length) {
                throw new java.io.IOException("Invalid loose index projection entry");
            }
        }
        cancellation.run();
        return new ArchiveLocations(rows, loose, archiveCount);
    }

    private static void validate(ArchiveIndex.Entry entry) {
        if (entry.encodedSize() < 1
                || entry.encodedSize() > 0xffff_ffffL
                || entry.offset() < 0
                || entry.offset() > 0xffff_ffffL) {
            throw new IllegalArgumentException("Index widths are unsigned32");
        }
    }

    private static com.vibeminer.builds.BuildFormatException conflict() {
        return FormatSupport.corrupt(
                "ARCHIVE_INDEX_SIZE_CONFLICT", "One EKey has different sizes across indexes");
    }

    private static com.vibeminer.builds.BuildFormatException limit() {
        return FormatSupport.corrupt("ARCHIVE_INDEX_LIMIT", "Index set exceeds bounds");
    }
}
