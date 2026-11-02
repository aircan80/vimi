package com.vibeminer.store;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Streaming smoke measurement, not a performance comparison with the production store. */
public final class SqliteBlobBenchmarks {
    private SqliteBlobBenchmarks() {}

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "sqlite-blob-");
        Path database = directory.resolve("prototype.sqlite");
        System.out.println("bytes,write_with_checkpoint_ms,read_ms,database_bytes");
        try (var store = new SqliteBlobPrototype(database)) {
            for (int size : new int[] {64 << 10, 1 << 20, 145 << 20, 256 << 20, 512 << 20}) {
                MessageDigest written = MessageDigest.getInstance("SHA-256");
                MessageDigest read = MessageDigest.getInstance("SHA-256");
                String key = "synthetic:" + size;
                long start = System.nanoTime();
                try (InputStream input = new DigestInputStream(new SyntheticInput(), written)) {
                    store.put(key, "synthetic-build", false, size, input, Cancellation.NONE);
                }
                store.checkpoint();
                long writeEnd = System.nanoTime();
                try (OutputStream output =
                        new DigestOutputStream(OutputStream.nullOutputStream(), read)) {
                    if (!store.read(key, 0, size, output, Cancellation.NONE)) {
                        throw new AssertionError("Missing payload");
                    }
                }
                long readEnd = System.nanoTime();
                if (!Arrays.equals(written.digest(), read.digest())) {
                    throw new AssertionError("Payload digest mismatch");
                }
                System.out.printf(
                        Locale.ROOT,
                        "%d,%.3f,%.3f,%d%n",
                        size,
                        (writeEnd - start) / 1e6,
                        (readEnd - writeEnd) / 1e6,
                        Files.size(database));
            }
        } finally {
            // Only delete files created by this invocation, never a caller's existing database.
            Files.deleteIfExists(directory.resolve("prototype.sqlite-wal"));
            Files.deleteIfExists(directory.resolve("prototype.sqlite-shm"));
            Files.deleteIfExists(database);
            Files.delete(directory);
        }
    }

    private static final class SyntheticInput extends InputStream {
        private long position;

        @Override
        public int read() {
            return value(position++) & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            for (int i = 0; i < length; i++) {
                bytes[offset + i] = value(position++);
            }
            return length;
        }

        private static byte value(long position) {
            long mixed = position * 0x9e3779b97f4a7c15L;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            return (byte) (mixed ^ (mixed >>> 27));
        }
    }
}
