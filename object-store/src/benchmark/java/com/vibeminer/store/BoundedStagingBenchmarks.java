package com.vibeminer.store;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verified large encoded objects with bounded staging, metadata interleaving and streamed readback.
 */
public final class BoundedStagingBenchmarks {
    private BoundedStagingBenchmarks() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "bounded-");
        Path database = directory.resolve("catalog.sqlite");
        System.out.println(
                "mib,ingest_verify_checkpoint_ms,max_chunk_commit_ms,max_foreground_write_ms,publish_ms,read_ms");
        try (var store = new SqliteBlobPrototype(database);
                var foreground = new SqliteBlobPrototype(database)) {
            var stages = new BoundedBlobStaging(store);
            byte[] chunk = new byte[BoundedBlobStaging.CHUNK];
            byte[] tiny = new byte[] {1};
            for (int size : new int[] {145 << 20, 512 << 20}) {
                MessageDigest md5 = Format.digest("MD5");
                MessageDigest expected = Format.digest("SHA-256");
                for (int offset = 0; offset < size; offset += chunk.length) {
                    fill(chunk, offset);
                    md5.update(chunk);
                    expected.update(chunk);
                }
                ObjectKey key = new ObjectKey(ObjectKey.ENCODED, md5.digest());
                String identity = HexFormat.of().formatHex(key.indexBytes());
                long started = System.nanoTime();
                String id = stages.begin(identity, size, false, Cancellation.NONE);
                double maxChunk = 0;
                double maxForeground = 0;
                for (int offset = 0; offset < size; offset += chunk.length) {
                    fill(chunk, offset); // Input is acquired before opening a write transaction.
                    long began = System.nanoTime();
                    stages.append(id, offset, chunk, Cancellation.NONE);
                    maxChunk = Math.max(maxChunk, (System.nanoTime() - began) / 1e6);
                    began = System.nanoTime();
                    foreground.put(
                            "foreground:" + size + ":" + offset,
                            "foreground",
                            false,
                            1,
                            new ByteArrayInputStream(tiny),
                            Cancellation.NONE);
                    maxForeground = Math.max(maxForeground, (System.nanoTime() - began) / 1e6);
                }
                long publicationStart = System.nanoTime();
                stages.publishVerified(id, "build:" + size, key, Cancellation.NONE);
                double publish = (System.nanoTime() - publicationStart) / 1e6;
                store.checkpoint();
                long written = System.nanoTime();
                MessageDigest actual = Format.digest("SHA-256");
                try (OutputStream output =
                        new DigestOutputStream(OutputStream.nullOutputStream(), actual)) {
                    if (!store.read(identity, 0, size, output, Cancellation.NONE)) {
                        throw new AssertionError("Published object missing");
                    }
                }
                long read = System.nanoTime();
                if (!Arrays.equals(expected.digest(), actual.digest())
                        || store.buildBytes("build:" + size) != size) {
                    throw new AssertionError("Readback or accounting differs");
                }
                System.out.printf(
                        Locale.ROOT,
                        "%d,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                        size >> 20,
                        (written - started) / 1e6,
                        maxChunk,
                        maxForeground,
                        publish,
                        (read - written) / 1e6);
            }
        } finally {
            Files.deleteIfExists(directory.resolve("catalog.sqlite-wal"));
            Files.deleteIfExists(directory.resolve("catalog.sqlite-shm"));
            Files.deleteIfExists(database);
            Files.delete(directory);
        }
    }

    private static void fill(byte[] bytes, int offset) {
        for (int i = 0; i < bytes.length; i++) {
            long position = (long) offset + i;
            long mixed = position * 0x9e3779b97f4a7c15L;
            mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
            bytes[i] = (byte) (mixed ^ (mixed >>> 27));
        }
        if (offset == 0) {
            System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'}, 0, bytes, 0, 9);
        }
    }
}
