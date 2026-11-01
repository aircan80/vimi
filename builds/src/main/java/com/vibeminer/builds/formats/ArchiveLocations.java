package com.vibeminer.builds.formats;

import com.vibeminer.network.CdnKey;
import com.vibeminer.network.RangePlanner;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        Map<CdnKey, Located> rows = new TreeMap<>();
        Map<CdnKey, Long> loose = new TreeMap<>();
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
