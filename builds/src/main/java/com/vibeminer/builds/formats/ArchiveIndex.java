package com.vibeminer.builds.formats;

import com.vibeminer.network.CdnKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fully checks footer identity/checksum, TOC, every page, ordering and padding before exposing
 * entries.
 */
public final class ArchiveIndex {
    public enum Kind {
        ARCHIVE,
        FILE_INDEX
    }

    public record Entry(CdnKey key, long encodedSize, long offset) {}

    public record Limits(long maxEntries, int maxPages, long maxIndexBytes, int maxTocBytes) {
        public Limits {
            if (maxEntries < 0 || maxPages < 0 || maxIndexBytes < 28 || maxTocBytes < 0) {
                throw new IllegalArgumentException("Invalid index bounds");
            }
        }

        public Limits() {
            this(10_000_000, 100_000, 512L << 20, 4 << 20);
        }
    }

    private ArchiveIndex() {}

    public static List<Entry> read(
            byte[] index, CdnKey indexKey, Kind kind, Limits limits, Runnable cancellation) {
        cancellation.run();
        int offsetWidth = kind == Kind.ARCHIVE ? 4 : 0,
                recordBytes = 20 + offsetWidth,
                perPage = 4096 / recordBytes;
        if (index.length < 28) {
            throw invalid("Index is shorter than footer");
        }
        int footerAt = index.length - 28;
        byte[] footer = FormatSupport.slice(index, footerAt, 28);
        integrity(FormatSupport.md5(footer), indexKey.bytes(), "Footer differs from index key");
        byte[] inner = FormatSupport.slice(footer, 8, 20);
        Arrays.fill(inner, 12, 20, (byte) 0);
        integrity(
                Arrays.copyOf(FormatSupport.md5(inner), 8),
                FormatSupport.slice(footer, 20, 8),
                "Footer checksum differs");
        if (footer[8] != 1
                || footer[9] != 0
                || footer[10] != 0
                || footer[11] != 4
                || footer[12] != offsetWidth
                || footer[13] != 4
                || footer[14] != 16
                || footer[15] != 8) {
            throw FormatSupport.unsupported(
                    "ARCHIVE_INDEX_UNSUPPORTED", "Unsupported index layout");
        }
        long entries = FormatSupport.le32(footer, 16),
                pages = (entries + perPage - 1) / perPage,
                tocBytes = pages * 24,
                total = pages * 4096 + tocBytes + 28;
        if (entries > limits.maxEntries()
                || pages > limits.maxPages()
                || tocBytes > limits.maxTocBytes()
                || total > limits.maxIndexBytes()) {
            throw FormatSupport.corrupt("ARCHIVE_INDEX_LIMIT", "Index geometry exceeds bounds");
        }
        if (total != index.length) {
            throw FormatSupport.corrupt(
                    "ARCHIVE_INDEX_INTEGRITY", "Index geometry differs from footer");
        }
        int count = (int) pages, tocAt = count * 4096;
        integrity(
                Arrays.copyOf(FormatSupport.md5(index, tocAt, (int) tocBytes), 8),
                FormatSupport.slice(footer, 0, 8),
                "TOC checksum differs");
        CdnKey previousMaximum = null;
        for (int p = 0; p < count; p++) {
            cancellation.run();
            CdnKey maximum = FormatSupport.key(index, tocAt + p * 16);
            if (FormatSupport.zero(index, tocAt + p * 16, 16)
                    || previousMaximum != null && previousMaximum.compareTo(maximum) >= 0) {
                throw invalid("Index maxima are zero or unordered");
            }
            previousMaximum = maximum;
        }
        List<Entry> result = new ArrayList<>((int) entries);
        CdnKey previous = null;
        for (int p = 0; p < count; p++) {
            cancellation.run();
            int pageAt = p * 4096;
            integrity(
                    Arrays.copyOf(FormatSupport.md5(index, pageAt, 4096), 8),
                    FormatSupport.slice(index, tocAt + count * 16 + p * 8, 8),
                    "Index page checksum differs");
            int n = (int) Math.min(perPage, entries - (long) p * perPage);
            for (int r = 0; r < n; r++) {
                int at = pageAt + r * recordBytes;
                CdnKey key = FormatSupport.key(index, at);
                if (FormatSupport.zero(index, at, 16)
                        || previous != null && previous.compareTo(key) >= 0) {
                    throw invalid("Index keys are zero, duplicate or unordered");
                }
                long size = FormatSupport.u32(index, at + 16);
                if (size == 0) {
                    throw invalid("Zero index size");
                }
                long offset = offsetWidth == 0 ? 0 : FormatSupport.u32(index, at + 20);
                result.add(new Entry(key, size, offset));
                previous = key;
            }
            if (!previous.equals(FormatSupport.key(index, tocAt + p * 16))) {
                throw invalid("Last key differs from page maximum");
            }
            if (!FormatSupport.zero(index, pageAt + n * recordBytes, 4096 - n * recordBytes)) {
                throw invalid("Index page padding is nonzero");
            }
        }
        cancellation.run();
        return List.copyOf(result);
    }

    private static void integrity(byte[] actual, byte[] expected, String message) {
        FormatSupport.hash(actual, expected, "ARCHIVE_INDEX_INTEGRITY", message);
    }

    private static com.vibeminer.builds.BuildFormatException invalid(String message) {
        return FormatSupport.corrupt("ARCHIVE_INDEX_INVALID", message);
    }
}
