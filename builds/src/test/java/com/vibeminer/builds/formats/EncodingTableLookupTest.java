package com.vibeminer.builds.formats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.builds.BuildFormatException;
import com.vibeminer.network.CdnKey;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Lookups over synthetic multi-page EN tables agree with the original copy-and-rehash page scan and
 * with the rows the table was generated from, for every stored key and many absent ones.
 */
class EncodingTableLookupTest {
    private static final Runnable NONE = () -> {};
    private static final byte[] SPECIFICATIONS = "z\0n\0b:{*=z}\0".getBytes();

    private record ContentRow(CdnKey key, long decoded, List<CdnKey> encoded) {}

    private record EncodedRow(CdnKey key, int spec, long size) {}

    private record Layout(int pagesOffset, int pageBytes, CdnKey[] firsts, byte[] hashes) {}

    private record Synthetic(
            byte[] bytes,
            TreeMap<CdnKey, ContentRow> content,
            TreeMap<CdnKey, EncodedRow> encoded,
            Layout contentLayout,
            Layout encodedLayout) {}

    @ParameterizedTest(name = "seed={0} content={1} encoded={2} cKiB={3} eKiB={4} marker={5}")
    @CsvSource({
        "1, 1, 1, 1, 1, true",
        "2, 1, 1, 1, 1, false",
        "3, 37, 40, 1, 1, true",
        "4, 41, 41, 1, 1, true",
        "5, 600, 900, 1, 1, true",
        "6, 600, 900, 1, 1, false",
        "7, 2500, 3000, 2, 1, true",
        "8, 1800, 2200, 1, 4, true",
        "9, 4000, 5000, 4, 2, false"
    })
    void inPlaceLookupsMatchTheCopyAndRehashScanForEveryProbe(
            long seed,
            int contents,
            int encodings,
            int contentKiB,
            int encodedKiB,
            boolean marker) {
        Synthetic synthetic =
                generate(new Random(seed), contents, encodings, contentKiB, encodedKiB, marker);
        EncodingTable table =
                EncodingTable.open(synthetic.bytes(), new EncodingTable.Limits(), NONE);
        assertEquals(contents, table.contentCount());
        assertEquals(encodings, table.encodedCount());

        Set<CdnKey> probes = probes(synthetic, new Random(~seed));
        for (CdnKey probe : probes) {
            ContentRow expectedContent = synthetic.content().get(probe);
            EncodingTable.Content content = table.findContent(probe);
            assertEquals(
                    referenceContent(synthetic.bytes(), synthetic.contentLayout(), probe),
                    content,
                    probe::hex);
            if (expectedContent == null) {
                assertNull(content, probe::hex);
            } else {
                assertNotNull(content, probe::hex);
                assertEquals(expectedContent.decoded(), content.decodedSize());
                assertEquals(expectedContent.encoded(), content.encodedKeys());
            }

            EncodedRow expectedEncoded = synthetic.encoded().get(probe);
            EncodingTable.Encoded encoded = table.findEncoded(probe);
            assertEquals(
                    referenceEncoded(synthetic.bytes(), synthetic.encodedLayout(), probe),
                    encoded,
                    probe::hex);
            assertEquals(
                    expectedEncoded == null
                            ? null
                            : new EncodingTable.Encoded(
                                    probe, expectedEncoded.size(), expectedEncoded.spec()),
                    encoded,
                    probe::hex);
        }

        List<EncodingTable.Encoded> rows = new ArrayList<>();
        table.encodedRows().forEach(rows::add);
        assertEquals(
                synthetic.encoded().values().stream()
                        .map(row -> new EncodingTable.Encoded(row.key(), row.size(), row.spec()))
                        .toList(),
                rows);
    }

    @ParameterizedTest(name = "seed={0}")
    @CsvSource({"11", "12", "13"})
    void everyChangedPageByteStillFailsAtOpenAndOpenedTablesIgnoreLaterChanges(long seed) {
        Random random = new Random(seed);
        Synthetic synthetic = generate(random, 120, 150, 1, 1, true);
        byte[] original = synthetic.bytes().clone();
        EncodingTable table =
                EncodingTable.open(synthetic.bytes(), new EncodingTable.Limits(), NONE);
        for (Layout layout : List.of(synthetic.contentLayout(), synthetic.encodedLayout())) {
            for (int page = 0; page < layout.firsts().length; page++) {
                int start = layout.pagesOffset() + page * layout.pageBytes();
                // First byte, a byte inside a row, and the final (padding) byte of each page.
                for (int at :
                        new int[] {
                            start,
                            start + 1 + random.nextInt(layout.pageBytes() - 2),
                            start + layout.pageBytes() - 1
                        }) {
                    byte[] damaged = original.clone();
                    damaged[at] ^= (byte) (1 + random.nextInt(255));
                    var failure =
                            assertThrows(
                                    BuildFormatException.class,
                                    () ->
                                            EncodingTable.open(
                                                    damaged, new EncodingTable.Limits(), NONE));
                    assertEquals("EN_INTEGRITY", failure.code(), () -> "offset " + at);
                }
            }
        }
        // The opened table keeps its own verified copy; changing the caller's array is invisible.
        Arrays.fill(synthetic.bytes(), 22 + SPECIFICATIONS.length, original.length, (byte) 0x5a);
        for (ContentRow row : synthetic.content().values()) {
            assertEquals(row.decoded(), table.findContent(row.key()).decodedSize());
        }
        for (EncodedRow row : synthetic.encoded().values()) {
            assertEquals(row.size(), table.findEncoded(row.key()).encodedSize());
        }
    }

    /** Stored keys, page boundaries, their 128-bit neighbours, extremes and random misses. */
    private static Set<CdnKey> probes(Synthetic synthetic, Random random) {
        Set<CdnKey> keys = new LinkedHashSet<>();
        keys.add(new CdnKey(new byte[16]));
        keys.add(key(BigInteger.ONE));
        byte[] max = new byte[16];
        Arrays.fill(max, (byte) 0xff);
        keys.add(new CdnKey(max));
        List<CdnKey> stored = new ArrayList<>(synthetic.content().keySet());
        stored.addAll(synthetic.encoded().keySet());
        stored.addAll(Arrays.asList(synthetic.contentLayout().firsts()));
        stored.addAll(Arrays.asList(synthetic.encodedLayout().firsts()));
        for (ContentRow row : synthetic.content().values()) {
            stored.addAll(row.encoded());
        }
        for (CdnKey key : stored) {
            keys.add(key);
            BigInteger value = new BigInteger(1, key.bytes());
            if (value.signum() > 0) {
                keys.add(key(value.subtract(BigInteger.ONE)));
            }
            if (value.bitCount() < 128) {
                keys.add(key(value.add(BigInteger.ONE)));
            }
        }
        for (int i = 0; i < 2000; i++) {
            keys.add(randomKey(random));
        }
        return keys;
    }

    private static Synthetic generate(
            Random random,
            int contents,
            int encodings,
            int contentKiB,
            int encodedKiB,
            boolean marker) {
        TreeMap<CdnKey, ContentRow> content = new TreeMap<>();
        while (content.size() < contents) {
            CdnKey key = randomKey(random);
            List<CdnKey> candidates = new ArrayList<>();
            for (int i = 1 + random.nextInt(random.nextInt(8) == 0 ? 12 : 3); i > 0; i--) {
                candidates.add(randomKey(random));
            }
            content.put(key, new ContentRow(key, random.nextLong(1L << 40), candidates));
        }
        TreeMap<CdnKey, EncodedRow> encoded = new TreeMap<>();
        while (encoded.size() < encodings) {
            CdnKey key = randomKey(random);
            encoded.put(key, new EncodedRow(key, random.nextInt(3), random.nextLong(1L << 40)));
        }
        int contentBytes = contentKiB * 1024, encodedBytes = encodedKiB * 1024;
        List<byte[]> contentPages = new ArrayList<>(), encodedPages = new ArrayList<>();
        List<CdnKey> contentFirsts = new ArrayList<>(), encodedFirsts = new ArrayList<>();

        ByteBuffer page = null;
        for (ContentRow row : content.values()) {
            int width = 22 + 16 * row.encoded().size();
            // Sometimes close a page early so pages end at varied fill levels.
            if (page == null
                    || page.remaining() < width
                    || page.position() > 0 && random.nextInt(60) == 0) {
                page = ByteBuffer.allocate(contentBytes).order(ByteOrder.BIG_ENDIAN);
                contentPages.add(page.array());
                contentFirsts.add(row.key());
            }
            page.put((byte) row.encoded().size());
            u40(page, row.decoded());
            page.put(row.key().bytes());
            for (CdnKey candidate : row.encoded()) {
                page.put(candidate.bytes());
            }
        }

        int perPage = encodedBytes / 25;
        List<EncodedRow> sortedEncoded = new ArrayList<>(encoded.values());
        for (int start = 0; start < sortedEncoded.size(); ) {
            int rows = Math.min(perPage, sortedEncoded.size() - start);
            if (random.nextInt(4) == 0) {
                rows = 1 + random.nextInt(rows);
            }
            ByteBuffer encodedPage = ByteBuffer.allocate(encodedBytes).order(ByteOrder.BIG_ENDIAN);
            for (int i = start; i < start + rows; i++) {
                EncodedRow row = sortedEncoded.get(i);
                encodedPage.put(row.key().bytes()).putInt(row.spec());
                u40(encodedPage, row.size());
            }
            start += rows;
            if (start == sortedEncoded.size() && marker && encodedPage.remaining() >= 20) {
                encodedPage.put(new byte[16]).putInt(-1);
            }
            encodedPages.add(encodedPage.array());
            encodedFirsts.add(sortedEncoded.get(start - rows).key());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) 'E')
                .put((byte) 'N')
                .put((byte) 1)
                .put((byte) 16)
                .put((byte) 16)
                .putShort((short) contentKiB)
                .putShort((short) encodedKiB)
                .putInt(contentPages.size())
                .putInt(encodedPages.size())
                .put((byte) 0)
                .putInt(SPECIFICATIONS.length);
        out.writeBytes(header.array());
        out.writeBytes(SPECIFICATIONS);
        Layout contentLayout = write(out, contentFirsts, contentPages, contentBytes);
        Layout encodedLayout = write(out, encodedFirsts, encodedPages, encodedBytes);
        out.writeBytes("trailer".getBytes());
        return new Synthetic(out.toByteArray(), content, encoded, contentLayout, encodedLayout);
    }

    private static Layout write(
            ByteArrayOutputStream out, List<CdnKey> firsts, List<byte[]> pages, int pageBytes) {
        byte[] hashes = new byte[pages.size() * 16];
        for (int i = 0; i < pages.size(); i++) {
            byte[] hash = FormatSupport.md5(pages.get(i));
            System.arraycopy(hash, 0, hashes, i * 16, 16);
            out.writeBytes(firsts.get(i).bytes());
            out.writeBytes(hash);
        }
        int pagesOffset = out.size();
        pages.forEach(out::writeBytes);
        return new Layout(pagesOffset, pageBytes, firsts.toArray(CdnKey[]::new), hashes);
    }

    // Reference oracle: the lookup as it was before in-place search, copying each page and
    // re-verifying its MD5 on every call.

    private static int referencePage(Layout layout, CdnKey key) {
        int lo = 0, hi = layout.firsts().length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (layout.firsts()[mid].compareTo(key) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1;
    }

    private static byte[] referenceSlice(byte[] data, Layout layout, int p) {
        byte[] page =
                FormatSupport.slice(
                        data, layout.pagesOffset() + p * layout.pageBytes(), layout.pageBytes());
        FormatSupport.hash(
                FormatSupport.md5(page),
                FormatSupport.slice(layout.hashes(), p * 16, 16),
                "EN_INTEGRITY",
                "EN page differs from its indexed MD5");
        return page;
    }

    private static EncodingTable.Content referenceContent(byte[] data, Layout layout, CdnKey key) {
        int p = referencePage(layout, key);
        if (p < 0) {
            return null;
        }
        byte[] page = referenceSlice(data, layout, p);
        for (int at = 0; at < page.length; ) {
            int n = Byte.toUnsignedInt(page[at]);
            if (n == 0) {
                return null;
            }
            int order = FormatSupport.key(page, at + 6).compareTo(key);
            if (order > 0) {
                return null;
            }
            if (order == 0) {
                List<CdnKey> keys = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    keys.add(FormatSupport.key(page, at + 22 + i * 16));
                }
                return new EncodingTable.Content(key, u40(page, at + 1), keys);
            }
            at += 22 + 16 * n;
        }
        return null;
    }

    private static EncodingTable.Encoded referenceEncoded(byte[] data, Layout layout, CdnKey key) {
        int p = referencePage(layout, key);
        if (p < 0) {
            return null;
        }
        byte[] page = referenceSlice(data, layout, p);
        for (int at = 0; page.length - at >= 25; at += 25) {
            if (FormatSupport.zero(page, at, 16)) {
                return null;
            }
            EncodingTable.Encoded row =
                    new EncodingTable.Encoded(
                            FormatSupport.key(page, at),
                            u40(page, at + 20),
                            (int) FormatSupport.u32(page, at + 16));
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

    private static long u40(byte[] bytes, int at) {
        return ((long) Byte.toUnsignedInt(bytes[at]) << 32) | FormatSupport.u32(bytes, at + 1);
    }

    private static void u40(ByteBuffer out, long n) {
        out.put((byte) (n >>> 32)).putInt((int) n);
    }

    private static CdnKey randomKey(Random random) {
        byte[] bytes = new byte[16];
        do {
            random.nextBytes(bytes);
        } while (FormatSupport.zero(bytes, 0, 16));
        return new CdnKey(bytes);
    }

    private static CdnKey key(BigInteger value) {
        byte[] raw = value.toByteArray(), bytes = new byte[16];
        int length = Math.min(raw.length, 16);
        System.arraycopy(raw, raw.length - length, bytes, 16 - length, length);
        return new CdnKey(bytes);
    }
}
