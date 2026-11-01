package com.vibeminer.builds;

import com.vibeminer.builds.formats.RootNameHash;
import com.vibeminer.network.CdnKey;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;

/** Synthetic portable format writers; no production/parser code generates these layouts. */
public final class MetadataFixtures {
    private MetadataFixtures() {}

    public static CdnKey key(int id) {
        return CdnKey.parse(String.format(java.util.Locale.ROOT, "%032x", id));
    }

    public static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] md5(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static byte[] raw(byte[] bytes) {
        ByteBuffer out = ByteBuffer.allocate(9 + bytes.length);
        out.put(text("BLTE")).putInt(0).put((byte) 'N').put(bytes);
        return out.array();
    }

    public record BlteObject(
            byte[] encoded, CdnKey encodedKey, CdnKey contentKey, byte[] content) {}

    public static BlteObject chunked(byte[] content, int mode) {
        byte[] body;
        if (mode == 'Z') {
            Deflater deflater = new Deflater();
            deflater.setInput(content);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            body = compressed.toByteArray();
        } else {
            body = content;
        }
        byte[] chunk = new byte[body.length + 1];
        chunk[0] = (byte) mode;
        System.arraycopy(body, 0, chunk, 1, body.length);
        ByteBuffer header = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
        header.put(text("BLTE"))
                .putInt(36)
                .put((byte) 15)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 1)
                .putInt(chunk.length)
                .putInt(content.length)
                .put(md5(chunk));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.writeBytes(header.array());
        result.writeBytes(chunk);
        return new BlteObject(
                result.toByteArray(),
                new CdnKey(md5(header.array())),
                new CdnKey(md5(content)),
                content.clone());
    }

    public record RootRow(long id, CdnKey key, String path) {}

    public record RootBlock(long locale, long first, long second, int third, List<RootRow> rows) {}

    public static byte[] root(int version, List<RootBlock> blocks) {
        long total = blocks.stream().mapToLong(b -> b.rows().size()).sum(),
                named =
                        blocks.stream()
                                .filter(
                                        b ->
                                                ((b.first() | b.second() | ((long) b.third() << 17))
                                                                & 0x10000000L)
                                                        == 0)
                                .mapToLong(b -> b.rows().size())
                                .sum();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header =
                ByteBuffer.allocate(version == 1 ? 20 : 24).order(ByteOrder.LITTLE_ENDIAN);
        header.put(text("TSFM"))
                .putInt(header.capacity())
                .putInt(version)
                .putInt((int) total)
                .putInt((int) named);
        if (version != 1) {
            header.putInt(0);
        }
        out.writeBytes(header.array());
        for (RootBlock block : blocks) {
            ByteBuffer bh =
                    ByteBuffer.allocate(version == 1 ? 12 : 17).order(ByteOrder.LITTLE_ENDIAN);
            bh.putInt(block.rows().size());
            if (version == 1) {
                bh.putInt((int) block.first()).putInt((int) block.locale());
            } else {
                bh.putInt((int) block.locale())
                        .putInt((int) block.first())
                        .putInt((int) block.second())
                        .put((byte) block.third());
            }
            out.writeBytes(bh.array());
            ByteBuffer deltas =
                    ByteBuffer.allocate(block.rows().size() * 4).order(ByteOrder.LITTLE_ENDIAN);
            long next = 0;
            for (RootRow row : block.rows()) {
                deltas.putInt((int) (row.id() - next));
                next = row.id() + 1;
            }
            out.writeBytes(deltas.array());
            for (RootRow row : block.rows()) {
                out.writeBytes(row.key().bytes());
            }
            if (((block.first() | block.second() | ((long) block.third() << 17)) & 0x10000000L)
                    == 0) {
                ByteBuffer names =
                        ByteBuffer.allocate(block.rows().size() * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (RootRow row : block.rows()) {
                    names.putLong(RootNameHash.of(row.path()));
                }
                out.writeBytes(names.array());
            }
        }
        return out.toByteArray();
    }

    public record EncodingRow(CdnKey content, long decoded, CdnKey encoded, long size) {}

    public static byte[] encoding(List<EncodingRow> input) {
        List<EncodingRow> content = new ArrayList<>(input);
        content.sort(Comparator.comparing(EncodingRow::content));
        List<EncodingRow> encoded = new ArrayList<>(input);
        encoded.sort(Comparator.comparing(EncodingRow::encoded));
        ByteBuffer cp = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN),
                ep = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
        for (EncodingRow row : content) {
            cp.put((byte) 1);
            u40(cp, row.decoded());
            cp.put(row.content().bytes()).put(row.encoded().bytes());
        }
        for (EncodingRow row : encoded) {
            ep.put(row.encoded().bytes()).putInt(0);
            u40(ep, row.size());
        }
        ep.put(new byte[16]).putInt(-1);
        ByteBuffer out =
                ByteBuffer.allocate(22 + 2 + 32 + 1024 + 32 + 1024).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) 'E')
                .put((byte) 'N')
                .put((byte) 1)
                .put((byte) 16)
                .put((byte) 16)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(1)
                .putInt(1)
                .put((byte) 0)
                .putInt(2)
                .put((byte) 'n')
                .put((byte) 0)
                .put(content.getFirst().content().bytes())
                .put(md5(cp.array()))
                .put(cp.array())
                .put(encoded.getFirst().encoded().bytes())
                .put(md5(ep.array()))
                .put(ep.array());
        return out.array();
    }

    private static void u40(ByteBuffer out, long n) {
        out.put((byte) (n >>> 32)).putInt((int) n);
    }

    public record IndexRow(CdnKey key, long size, long offset) {}

    public record Index(byte[] bytes, CdnKey key) {}

    public static Index index(List<IndexRow> input, boolean loose) {
        List<IndexRow> rows = new ArrayList<>(input);
        rows.sort(Comparator.comparing(IndexRow::key));
        int width = loose ? 20 : 24, per = 4096 / width, pages = (rows.size() + per - 1) / per;
        byte[] data = new byte[pages * 4096], toc = new byte[pages * 24];
        ByteBuffer dataBuffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        for (int p = 0; p < pages; p++) {
            int start = p * per, end = Math.min(rows.size(), start + per);
            dataBuffer.position(p * 4096);
            for (int i = start; i < end; i++) {
                IndexRow row = rows.get(i);
                dataBuffer.put(row.key().bytes()).putInt((int) row.size());
                if (!loose) {
                    dataBuffer.putInt((int) row.offset());
                }
            }
            System.arraycopy(rows.get(end - 1).key().bytes(), 0, toc, p * 16, 16);
            System.arraycopy(
                    md5(Arrays.copyOfRange(data, p * 4096, (p + 1) * 4096)),
                    0,
                    toc,
                    pages * 16 + p * 8,
                    8);
        }
        byte[] footer = new byte[28];
        System.arraycopy(md5(toc), 0, footer, 0, 8);
        footer[8] = 1;
        footer[11] = 4;
        footer[12] = (byte) (loose ? 0 : 4);
        footer[13] = 4;
        footer[14] = 16;
        footer[15] = 8;
        ByteBuffer.wrap(footer, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(rows.size());
        System.arraycopy(md5(Arrays.copyOfRange(footer, 8, 28)), 0, footer, 20, 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(data);
        out.writeBytes(toc);
        out.writeBytes(footer);
        return new Index(out.toByteArray(), new CdnKey(md5(footer)));
    }
}
