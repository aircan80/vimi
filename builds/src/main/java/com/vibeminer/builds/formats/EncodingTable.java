package com.vibeminer.builds.formats;

import com.vibeminer.network.CdnKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** EN v1 metadata validated completely on open, then searched by its bounded page directories. */
public final class EncodingTable {
    public record Limits(
            long maxDecodedBytes,
            long maxRecords,
            long maxCandidateReferences,
            int maxPages,
            int maxIndexBytes,
            int maxPageBytes,
            int maxRecordsPerPage,
            int maxKeysPerContent,
            int maxSpecificationStrings,
            int maxSpecificationBytes,
            int maxTrailerBytes) {
        public Limits {
            if (maxDecodedBytes < 22
                    || maxRecords < 0
                    || maxCandidateReferences < 0
                    || maxPages < 0
                    || maxIndexBytes < 0
                    || maxPageBytes < 1024
                    || maxRecordsPerPage < 1
                    || maxKeysPerContent < 1
                    || maxKeysPerContent > 255
                    || maxSpecificationStrings < 0
                    || maxSpecificationBytes < 0
                    || maxTrailerBytes < 0) {
                throw new IllegalArgumentException("Invalid EN bounds");
            }
        }

        public Limits() {
            this(
                    1L << 30,
                    50_000_000,
                    100_000_000,
                    1_000_000,
                    32 << 20,
                    64 << 10,
                    65536,
                    255,
                    1_000_000,
                    32 << 20,
                    1 << 20);
        }

        public Limits(long bytes) {
            this(
                    bytes,
                    50_000_000,
                    100_000_000,
                    1_000_000,
                    32 << 20,
                    64 << 10,
                    65536,
                    255,
                    1_000_000,
                    32 << 20,
                    1 << 20);
        }
    }

    public record Content(CdnKey key, long decodedSize, List<CdnKey> encodedKeys) {
        public Content {
            encodedKeys = List.copyOf(encodedKeys);
        }
    }

    public record Encoded(CdnKey key, long encodedSize, int specIndex) {}

    private record Directory(CdnKey[] firsts, byte[] hashes, int pageBytes, int pagesOffset) {
        int pageFor(CdnKey key) {
            int lo = 0, hi = firsts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (firsts[mid].compareTo(key) <= 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo - 1;
        }
    }

    private final byte[] data;
    private final Directory content, encoded;
    private final long contentCount, encodedCount;

    private EncodingTable(
            byte[] data, Directory content, Directory encoded, long contents, long encodings) {
        this.data = data;
        this.content = content;
        this.encoded = encoded;
        contentCount = contents;
        encodedCount = encodings;
    }

    public long contentCount() {
        return contentCount;
    }

    public long encodedCount() {
        return encodedCount;
    }

    public static EncodingTable open(byte[] decoded, Limits limits, Runnable cancellation) {
        cancellation.run();
        if (decoded.length > limits.maxDecodedBytes()) {
            throw limit("EN decoded byte bound exceeded");
        }
        if (decoded.length < 22) {
            throw invalid("EN header is truncated");
        }
        if (decoded[0] != 'E' || decoded[1] != 'N') {
            throw invalid("EN signature differs");
        }
        if (decoded[2] != 1 || decoded[3] != 16 || decoded[4] != 16 || decoded[17] != 0) {
            throw FormatSupport.unsupported("EN_UNSUPPORTED", "Unsupported EN layout");
        }
        int
                cp =
                        Short.toUnsignedInt(
                                        ByteBuffer.wrap(decoded, 5, 2)
                                                .order(ByteOrder.BIG_ENDIAN)
                                                .getShort())
                                * 1024,
                ep =
                        Short.toUnsignedInt(
                                        ByteBuffer.wrap(decoded, 7, 2)
                                                .order(ByteOrder.BIG_ENDIAN)
                                                .getShort())
                                * 1024;
        long cn = FormatSupport.u32(decoded, 9),
                en = FormatSupport.u32(decoded, 13),
                specBytes = FormatSupport.u32(decoded, 18);
        if (cp == 0 || ep == 0) {
            throw invalid("EN has zero page size");
        }
        if (cp > limits.maxPageBytes()
                || ep > limits.maxPageBytes()
                || cn + en > limits.maxPages()
                || cn * 32 > limits.maxIndexBytes()
                || en * 32 > limits.maxIndexBytes()
                || specBytes > limits.maxSpecificationBytes()) {
            throw limit("EN page/index/specification bound exceeded");
        }
        long minimum = 22 + specBytes + cn * (32L + cp) + en * (32L + ep);
        if (minimum > limits.maxDecodedBytes()
                || cn + en > limits.maxRecords()
                || cn > limits.maxCandidateReferences()) {
            throw limit("EN minimum work exceeds bounds");
        }
        if (minimum > decoded.length) {
            throw invalid("EN is truncated");
        }
        int specs = 0, start = 22;
        for (int i = 22; i < 22 + specBytes; i++) {
            if ((i & 8191) == 0) {
                cancellation.run();
            }
            int b = Byte.toUnsignedInt(decoded[i]);
            if (b == 0) {
                if (specs == limits.maxSpecificationStrings()) {
                    throw limit("Too many EN specification strings");
                }
                specs++;
                start = i + 1;
            } else if (b < 32 || b > 126) {
                throw FormatSupport.unsupported("EN_UNSUPPORTED", "Non-ASCII EN specification");
            }
        }
        if (start != 22 + specBytes) {
            throw invalid("EN specification table is not terminated");
        }
        int[] at = {(int) (22 + specBytes)};
        long[] counts = new long[3];
        Directory
                content =
                        readTable(
                                decoded,
                                at,
                                (int) cn,
                                cp,
                                true,
                                specs,
                                limits,
                                counts,
                                cancellation),
                encoded =
                        readTable(
                                decoded,
                                at,
                                (int) en,
                                ep,
                                false,
                                specs,
                                limits,
                                counts,
                                cancellation);
        for (int i = at[0]; i < decoded.length; i++) {
            if (i - at[0] == limits.maxTrailerBytes()) {
                throw limit("EN trailer exceeds bound");
            }
            int b = Byte.toUnsignedInt(decoded[i]);
            if (b < 32 || b > 126) {
                throw FormatSupport.unsupported(
                        "EN_UNSUPPORTED", "EN trailer is not printable ASCII");
            }
        }
        cancellation.run();
        return new EncodingTable(decoded.clone(), content, encoded, counts[0], counts[1]);
    }

    private static Directory readTable(
            byte[] data,
            int[] cursor,
            int pages,
            int bytes,
            boolean content,
            int specs,
            Limits limits,
            long[] counts,
            Runnable cancellation) {
        int indexAt = cursor[0];
        cursor[0] += pages * 32;
        CdnKey[] firsts = new CdnKey[pages];
        byte[] hashes = new byte[pages * 16];
        for (int i = 0; i < pages; i++) {
            cancellation.run();
            int at = indexAt + i * 32;
            firsts[i] = FormatSupport.key(data, at);
            if (FormatSupport.zero(data, at, 16)
                    || i > 0 && firsts[i].compareTo(firsts[i - 1]) <= 0) {
                throw invalid("EN index keys are zero or unordered");
            }
            System.arraycopy(data, at + 16, hashes, i * 16, 16);
        }
        int pagesAt = cursor[0];
        CdnKey previous = null;
        for (int p = 0; p < pages; p++) {
            cancellation.run();
            if (counts[0] + counts[1] == limits.maxRecords()) {
                throw limit("EN aggregate record bound exhausted");
            }
            int at = cursor[0];
            cursor[0] += bytes;
            byte[] page = FormatSupport.slice(data, at, bytes);
            integrity(FormatSupport.md5(page), FormatSupport.slice(hashes, p * 16, 16));
            CdnKey next = p + 1 < pages ? firsts[p + 1] : null;
            int records = 0, offset = 0;
            while (offset < page.length) {
                if ((records & 1023) == 0) {
                    cancellation.run();
                }
                if (content) {
                    int candidates = Byte.toUnsignedInt(page[offset]);
                    if (candidates == 0) {
                        zero(page, offset, page.length - offset);
                        break;
                    }
                    if (candidates > limits.maxKeysPerContent()) {
                        throw limit("Too many EN encoding candidates");
                    }
                    int width = 22 + 16 * candidates;
                    if (width > page.length - offset) {
                        throw invalid("EN content row crosses page");
                    }
                    CdnKey key =
                            interval(
                                    page,
                                    offset + 6,
                                    records == 0 ? firsts[p] : null,
                                    previous,
                                    next);
                    checkRecord(++records, limits, counts);
                    if (candidates > limits.maxCandidateReferences() - counts[2]) {
                        throw limit("EN candidate-reference bound exceeded");
                    }
                    for (int i = 0; i < candidates; i++) {
                        if (FormatSupport.zero(page, offset + 22 + i * 16, 16)) {
                            throw invalid("Zero EN encoding candidate");
                        }
                    }
                    counts[0]++;
                    counts[2] += candidates;
                    previous = key;
                    offset += width;
                } else {
                    if (page.length - offset < 16) {
                        zero(page, offset, page.length - offset);
                        break;
                    }
                    if (FormatSupport.zero(page, offset, 16)) {
                        int rest = offset + 16;
                        if (page.length - rest >= 4
                                && FormatSupport.u32(page, rest) == 0xffff_ffffL) {
                            if (p != pages - 1) {
                                throw invalid("EN final marker precedes last page");
                            }
                            rest += 4;
                        }
                        zero(page, rest, page.length - rest);
                        break;
                    }
                    if (page.length - offset < 25) {
                        throw invalid("EN encoded row crosses page");
                    }
                    CdnKey key =
                            interval(page, offset, records == 0 ? firsts[p] : null, previous, next);
                    checkRecord(++records, limits, counts);
                    if (FormatSupport.u32(page, offset + 16) >= specs) {
                        throw invalid("EN row refers to missing specification");
                    }
                    counts[1]++;
                    previous = key;
                    offset += 25;
                }
            }
            if (records == 0) {
                throw invalid("Indexed EN page is empty");
            }
        }
        return new Directory(firsts, hashes, bytes, pagesAt);
    }

    private static void checkRecord(int records, Limits limits, long[] counts) {
        if (records > limits.maxRecordsPerPage() || counts[0] + counts[1] >= limits.maxRecords()) {
            throw limit("EN record bound exceeded");
        }
    }

    private static CdnKey interval(
            byte[] page, int at, CdnKey first, CdnKey previous, CdnKey next) {
        if (FormatSupport.zero(page, at, 16)) {
            throw invalid("Zero EN key");
        }
        CdnKey key = FormatSupport.key(page, at);
        if (first != null && !first.equals(key)
                || previous != null && previous.compareTo(key) >= 0
                || next != null && key.compareTo(next) >= 0) {
            throw invalid("EN key is outside index interval");
        }
        return key;
    }

    private byte[] page(Directory directory, int p) {
        byte[] page =
                FormatSupport.slice(
                        data,
                        directory.pagesOffset() + p * directory.pageBytes(),
                        directory.pageBytes());
        integrity(FormatSupport.md5(page), FormatSupport.slice(directory.hashes(), p * 16, 16));
        return page;
    }

    public Content findContent(CdnKey key) {
        int p = content.pageFor(key);
        if (p < 0) {
            return null;
        }
        byte[] page = page(content, p);
        for (int at = 0; at < page.length; ) {
            int n = Byte.toUnsignedInt(page[at]);
            if (n == 0) {
                return null;
            }
            int width = 22 + 16 * n;
            if (width > page.length - at) {
                throw invalid("Validated EN row changed");
            }
            CdnKey found = FormatSupport.key(page, at + 6);
            int order = found.compareTo(key);
            if (order > 0) {
                return null;
            }
            if (order == 0) {
                List<CdnKey> keys = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    keys.add(FormatSupport.key(page, at + 22 + i * 16));
                }
                return new Content(key, u40(page, at + 1), keys);
            }
            at += width;
        }
        return null;
    }

    public Encoded findEncoded(CdnKey key) {
        int p = encoded.pageFor(key);
        if (p < 0) {
            return null;
        }
        byte[] page = page(encoded, p);
        for (int at = 0; page.length - at >= 25; at += 25) {
            if (FormatSupport.zero(page, at, 16)) {
                return null;
            }
            Encoded row = encodedAt(page, at);
            int order = row.key().compareTo(key);
            if (order > 0) {
                return null;
            }
            if (order == 0) {
                return row;
            }
        }
        return null;
    }

    public Iterable<Encoded> encodedRows() {
        return () ->
                new Iterator<>() {
                    private int p, at;
                    private byte[] page;
                    private Encoded next = advance();

                    private Encoded advance() {
                        while (true) {
                            if (page == null
                                    || at + 25 > page.length
                                    || FormatSupport.zero(page, at, 16)) {
                                if (p == encoded.firsts().length) {
                                    return null;
                                }
                                page = page(encoded, p++);
                                at = 0;
                            }
                            if (at + 25 <= page.length && !FormatSupport.zero(page, at, 16)) {
                                Encoded row = encodedAt(page, at);
                                at += 25;
                                return row;
                            }
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        return next != null;
                    }

                    @Override
                    public Encoded next() {
                        if (next == null) {
                            throw new NoSuchElementException();
                        }
                        Encoded value = next;
                        next = advance();
                        return value;
                    }
                };
    }

    private static Encoded encodedAt(byte[] page, int at) {
        return new Encoded(
                FormatSupport.key(page, at),
                u40(page, at + 20),
                (int) FormatSupport.u32(page, at + 16));
    }

    private static long u40(byte[] bytes, int at) {
        return ((long) Byte.toUnsignedInt(bytes[at]) << 32) | FormatSupport.u32(bytes, at + 1);
    }

    private static void zero(byte[] bytes, int at, int n) {
        if (!FormatSupport.zero(bytes, at, n)) {
            throw invalid("EN padding is nonzero");
        }
    }

    private static void integrity(byte[] actual, byte[] expected) {
        FormatSupport.hash(
                actual, expected, "EN_INTEGRITY", "EN page differs from its indexed MD5");
    }

    private static com.vibeminer.builds.BuildFormatException invalid(String message) {
        return FormatSupport.corrupt("EN_INVALID", message);
    }

    private static com.vibeminer.builds.BuildFormatException limit(String message) {
        return FormatSupport.corrupt("EN_LIMIT", message);
    }
}
