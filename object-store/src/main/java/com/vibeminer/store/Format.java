package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Origin;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32C;

final class Format {
    static final int BLOCK = 65536, PACK_HEADER = 64, ENTRY_HEADER = 96, WHOLE_LIMIT = 64 << 20;
    static final long MAX_PAYLOAD = 512L << 20;
    static final byte[] EMPTY_SHA = hash("SHA-256", new byte[0]);

    private Format() {}

    static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    static MessageDigest digest(String name) {
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static byte[] hash(String name, byte[] bytes) {
        return digest(name).digest(bytes);
    }

    static long crc(byte[] bytes) {
        return crc(bytes, 0, bytes.length);
    }

    static long crc(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }

    static int blocks(long length) {
        return (int) ((length + BLOCK - 1) / BLOCK);
    }

    static long entrySize(long length) {
        return 96 + length + 4L * blocks(length);
    }

    static byte[] pack(int number, byte[] id, long now, Kind kind) {
        byte[] bytes = new byte[64];
        ByteBuffer b = le(bytes);
        b.put(new byte[] {'V', 'M', 'P', 'K'})
                .putShort((short) 1)
                .putShort((short) 64)
                .putInt(number)
                .putInt(kind.ordinal())
                .put(id)
                .putLong(now);
        b.putInt(60, (int) crc(bytes, 0, 60));
        return bytes;
    }

    record Pack(int number, byte[] id, Kind kind) {}

    static Pack pack(byte[] bytes, int number, Kind expected) {
        if (bytes.length != 64
                || !Arrays.equals(Arrays.copyOf(bytes, 4), new byte[] {'V', 'M', 'P', 'K'})
                || le(bytes).getInt(60) != (int) crc(bytes, 0, 60)) {
            return null;
        }
        ByteBuffer b = le(bytes);
        if (b.getShort(4) != 1
                || b.getShort(6) != 64
                || Integer.toUnsignedLong(b.getInt(12)) > (expected == Kind.LIBRARY ? 2 : 1)
                || !zero(bytes, 40, 60)) {
            throw newer();
        }
        if (b.getInt(8) != number) {
            return null;
        }
        Kind kind = Kind.values()[b.getInt(12)];
        if (kind != expected) {
            throw mismatch();
        }
        return new Pack(number, Arrays.copyOfRange(bytes, 16, 32), kind);
    }

    record Entry(
            ObjectKey key,
            long length,
            byte[] sha,
            long time,
            Origin origin,
            long sequence,
            boolean tombstone) {
        long size() {
            return entrySize(length);
        }

        byte[] header() {
            byte[] bytes = new byte[96];
            ByteBuffer b = le(bytes);
            b.put(new byte[] {'V', 'M', 'O', 'E'})
                    .put((byte) 1)
                    .put((byte) key.type())
                    .put((byte) key.namespace())
                    .put((byte) (tombstone ? 1 : 0))
                    .put(key.bytes())
                    .putLong(length)
                    .put(sha)
                    .putLong(time)
                    .put((byte) origin.ordinal())
                    .put((byte) 16)
                    .putShort((short) 0)
                    .putInt(0)
                    .putLong(sequence);
            b.putInt(92, (int) crc(bytes, 0, 92));
            return bytes;
        }
    }

    static Entry entry(byte[] bytes, Kind kind) {
        if (bytes.length != 96
                || !Arrays.equals(Arrays.copyOf(bytes, 4), new byte[] {'V', 'M', 'O', 'E'})
                || le(bytes).getInt(92) != (int) crc(bytes, 0, 92)) {
            return null;
        }
        ByteBuffer b = le(bytes);
        int type = Byte.toUnsignedInt(bytes[5]),
                flags = Byte.toUnsignedInt(bytes[7]),
                origin = Byte.toUnsignedInt(bytes[72]);
        if (bytes[4] != 1
                || (flags & ~1) != 0
                || bytes[73] != 16
                || origin > 2
                || !known(type, kind)
                || !zero(bytes, 74, 80)
                || !zero(bytes, 88, 92)) {
            throw newer();
        }
        long length = b.getLong(24);
        boolean tombstone = flags == 1;
        if (length < 0 || length > MAX_PAYLOAD || (tombstone ? length != 0 : length == 0)) {
            return null;
        }
        return new Entry(
                new ObjectKey(type, Byte.toUnsignedInt(bytes[6]), Arrays.copyOfRange(bytes, 8, 24)),
                length,
                Arrays.copyOfRange(bytes, 32, 64),
                b.getLong(64),
                Origin.values()[origin],
                b.getLong(80),
                tombstone);
    }

    static boolean known(int type, Kind kind) {
        return kind == Kind.LIBRARY
                ? type >= 1 && type <= 7
                : kind == Kind.CDN ? type >= 1 && type <= 6 : type == 7;
    }

    static boolean zero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    static StoreException newer() {
        return new StoreException(
                "OBJECT_STORE_NEWER_FORMAT", "Checksummed header or index uses a newer format");
    }

    static StoreException mismatch() {
        return new StoreException(
                "OBJECT_STORE_KIND_MISMATCH", "Store kind differs from requested kind");
    }

    static byte[] read(FileChannel file, long offset, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length);
        while (b.hasRemaining()) {
            int n = file.read(b, offset + b.position());
            if (n < 0) {
                break;
            }
        }
        return b.position() == length ? b.array() : Arrays.copyOf(b.array(), b.position());
    }

    static void write(FileChannel file, long offset, byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) {
            file.write(b, offset + b.position());
        }
    }

    static void syncDirectory(Path folder) throws IOException {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static long mix(byte[] b) {
        byte[] reversed = b.clone();
        for (int i = 0; i < b.length; i++) {
            reversed[i] = b[b.length - 1 - i];
        }
        long x = (crc(b) << 32) | crc(reversed);
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    static long packCheck(long seq, long state, long committed) {
        return crc(
                ByteBuffer.allocate(24)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(seq)
                        .putLong(state)
                        .putLong(committed)
                        .array());
    }
}
