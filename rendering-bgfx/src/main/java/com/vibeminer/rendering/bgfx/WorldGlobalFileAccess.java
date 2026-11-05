package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

/** One bounded stream/cache at a time; every actual handle is supplied and closed by its caller. */
final class WorldGlobalFileAccess {
    private WorldGlobalFileAccess() {}

    static void stop(BooleanSupplier stopping) {
        if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    static void write(FileChannel file, ByteBuffer bytes, BooleanSupplier stopping)
            throws IOException {
        while (bytes.hasRemaining()) {
            stop(stopping);
            if (file.write(bytes) <= 0) {
                throw new IOException("Global file write made no progress");
            }
        }
    }

    static byte[] read(FileChannel file, int size, BooleanSupplier stopping) throws IOException {
        var bytes = ByteBuffer.allocate(size);
        while (bytes.hasRemaining()) {
            stop(stopping);
            if (file.read(bytes) <= 0) {
                throw new IOException("Global file ended early");
            }
        }
        return bytes.array();
    }

    static void eof(FileChannel file, int length, BooleanSupplier stopping) throws IOException {
        stop(stopping);
        var probe = ByteBuffer.allocate(1);
        if (file.position() != length
                || file.read(probe) != -1
                || probe.position() != 0
                || file.size() != length) {
            throw new IOException("Global file extent differs");
        }
    }

    static WorldGlobalHashes.FileDigest hash(
            FileChannel file, WorldGlobalLayout.Counts counts, BooleanSupplier stopping)
            throws IOException {
        file.position(0);
        int remaining = counts.offsets().actualBytes();
        var buffer = ByteBuffer.allocate(65536);
        var hash = new WorldGlobalHashes.FileHasher(counts);
        while (remaining > 0) {
            stop(stopping);
            buffer.clear().limit(Math.min(buffer.capacity(), remaining));
            int read = file.read(buffer);
            if (read <= 0) {
                throw new IOException("Global file ended early");
            }
            buffer.flip();
            hash.update(buffer);
            remaining -= read;
        }
        eof(file, counts.offsets().actualBytes(), stopping);
        return hash.finish();
    }

    static final class Sink {
        private final FileChannel file;
        private final BooleanSupplier stopping;
        private final ByteBuffer buffer = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        private final CRC32C crc = new CRC32C();

        Sink(FileChannel file, BooleanSupplier stopping) {
            this.file = file;
            this.stopping = stopping;
        }

        void integer(int value) throws IOException {
            space(4);
            buffer.putInt(value);
        }

        void shortValue(int value) throws IOException {
            space(2);
            buffer.putShort((short) value);
        }

        void bytes(byte[] bytes, int offset, int count) throws IOException {
            while (count > 0) {
                if (!buffer.hasRemaining()) {
                    flush();
                }
                int n = Math.min(count, buffer.remaining());
                buffer.put(bytes, offset, n);
                offset += n;
                count -= n;
            }
        }

        private void space(int size) throws IOException {
            if (buffer.remaining() < size) {
                flush();
            }
        }

        void flush() throws IOException {
            buffer.flip();
            crc.update(buffer.asReadOnlyBuffer());
            write(file, buffer, stopping);
            buffer.clear();
        }

        long finish() throws IOException {
            flush();
            return crc.getValue();
        }
    }

    private static final class ReadFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IOException problem;

        ReadFailure(IOException problem) {
            super("Global cached read failed", problem);
            this.problem = problem;
        }
    }

    record MeshCheck(WorldGlobalHashes.MeshDigests hashes, float[] bounds) {}

    static MeshCheck meshHash(
            FileChannel file,
            WorldGlobalLayout.Counts counts,
            List<WorldGlobalHashes.Group> groups,
            List<WorldGlobalHashes.Batch> batches,
            BooleanSupplier stopping)
            throws IOException {
        var cache = new Cache(file, counts.offsets().actualBytes(), stopping);
        float[] bounds = {
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        WorldGlobalHashes.Mesh mesh =
                new WorldGlobalHashes.Mesh() {
                    public WorldGlobalLayout.Counts counts() {
                        return counts;
                    }

                    public List<WorldGlobalHashes.Group> groups() {
                        return groups;
                    }

                    public List<WorldGlobalHashes.Batch> batches() {
                        return batches;
                    }

                    public float vertex(int vertex, int component) {
                        float value =
                                Float.intBitsToFloat(
                                        cache.integer(
                                                counts.offsets().vertices()
                                                        + ((long) vertex * 5 + component) * 4));
                        if (component < 3) {
                            bounds[component] = Math.min(bounds[component], value);
                            bounds[component + 3] = Math.max(bounds[component + 3], value);
                        }
                        return value;
                    }

                    public int index(int ordinal) {
                        return cache.integer(
                                counts.offsets().indices()
                                        + (long) ordinal * WorldGlobalLayout.INDEX_BYTES);
                    }
                };
        try {
            return new MeshCheck(WorldGlobalHashes.mesh(mesh, stopping), bounds);
        } catch (ReadFailure failed) {
            throw failed.problem;
        }
    }

    private static final class Cache {
        private final FileChannel file;
        private final int length;
        private final BooleanSupplier stopping;
        private final ByteBuffer bytes = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        private long first = -1;
        private int used;

        Cache(FileChannel file, int length, BooleanSupplier stopping) {
            this.file = file;
            this.length = length;
            this.stopping = stopping;
        }

        private int at(long position, int count) {
            if (position < 0 || position > length - count) {
                throw new IllegalArgumentException("Global cached range differs");
            }
            if (first < 0 || position < first || position + count > first + used) {
                first = position - position % bytes.capacity();
                int size = (int) Math.min(bytes.capacity(), length - first);
                bytes.clear().limit(size);
                try {
                    while (bytes.hasRemaining()) {
                        stop(stopping);
                        int read = file.read(bytes, first + bytes.position());
                        if (read <= 0) {
                            throw new IOException("Global cached file ended early");
                        }
                    }
                } catch (IOException failure) {
                    throw new ReadFailure(failure);
                }
                used = size;
                bytes.flip();
            }
            return Math.toIntExact(position - first);
        }

        int integer(long position) {
            return bytes.getInt(at(position, 4));
        }
    }

    static final class Cursor {
        private final FileChannel file;
        private final BooleanSupplier stopping;
        private final int length;
        private final ByteBuffer buffer = ByteBuffer.allocate(65536);
        private final WorldGlobalHashes.FileHasher hash;
        private int loaded, consumed;

        Cursor(FileChannel file, WorldGlobalLayout.Counts counts, BooleanSupplier stopping)
                throws IOException {
            this.file = file;
            this.stopping = stopping;
            length = counts.offsets().actualBytes();
            hash = new WorldGlobalHashes.FileHasher(counts);
            buffer.limit(0);
            file.position(0);
        }

        private void refill() throws IOException {
            stop(stopping);
            if (loaded == length) {
                throw new IOException("Global cursor ended early");
            }
            buffer.clear().limit(Math.min(buffer.capacity(), length - loaded));
            int count = file.read(buffer);
            if (count <= 0) {
                throw new IOException("Global cursor ended early");
            }
            buffer.flip();
            hash.update(buffer);
            loaded += count;
        }

        int unsignedByte() throws IOException {
            if (!buffer.hasRemaining()) {
                refill();
            }
            consumed++;
            return Byte.toUnsignedInt(buffer.get());
        }

        int integer() throws IOException {
            return unsignedByte()
                    | unsignedByte() << 8
                    | unsignedByte() << 16
                    | unsignedByte() << 24;
        }

        int unsignedShort() throws IOException {
            return unsignedByte() | unsignedByte() << 8;
        }

        void bytes(byte[] output) throws IOException {
            int at = 0;
            while (at < output.length) {
                if (!buffer.hasRemaining()) {
                    refill();
                }
                int count = Math.min(buffer.remaining(), output.length - at);
                buffer.get(output, at, count);
                at += count;
                consumed += count;
            }
        }

        void expect(byte[] expected) throws IOException {
            for (byte value : expected) {
                if (unsignedByte() != (value & 255)) {
                    throw new IOException("Global metadata changed between passes");
                }
            }
        }

        WorldGlobalHashes.FileDigest finish() throws IOException {
            if (consumed != length || buffer.hasRemaining()) {
                throw new IOException("Global cursor extent differs");
            }
            eof(file, length, stopping);
            return hash.finish();
        }
    }
}
