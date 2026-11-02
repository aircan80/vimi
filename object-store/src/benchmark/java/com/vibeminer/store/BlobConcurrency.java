package com.vibeminer.store;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Measures committed-reader latency during one large SQLite write on another connection. */
public final class BlobConcurrency {
    private BlobConcurrency() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "concurrent-");
        Path database = directory.resolve("catalog.sqlite");
        byte[] bytes = new byte[65536];
        new java.util.Random(4).nextBytes(bytes);
        var object = new StoreTypes.PutObject(new ObjectKey(1, Format.hash("MD5", bytes)), bytes);
        try {
            try (var seed = new SqliteBlobPrototype(database)) {
                seed.putVerified(object, "build", Cancellation.NONE);
            }
            var entered = new CountDownLatch(1);
            try (var reader = SqliteBlobPrototype.reader(database);
                    var executor = Executors.newSingleThreadExecutor()) {
                var writer =
                        executor.submit(
                                () -> {
                                    try (var store = new SqliteBlobPrototype(database)) {
                                        InputStream input =
                                                new InputStream() {
                                                    @Override
                                                    public int read() {
                                                        return 17;
                                                    }

                                                    @Override
                                                    public int read(
                                                            byte[] data, int offset, int length) {
                                                        java.util.Objects.checkFromIndexSize(
                                                                offset, length, data.length);
                                                        Arrays.fill(
                                                                data,
                                                                offset,
                                                                offset + length,
                                                                (byte) 17);
                                                        entered.countDown();
                                                        return length;
                                                    }
                                                };
                                        long began = System.nanoTime();
                                        store.put(
                                                "large-unchecked",
                                                "bulk",
                                                false,
                                                512 << 20,
                                                input,
                                                Cancellation.NONE);
                                        store.checkpoint();
                                        return (System.nanoTime() - began) / 1e6;
                                    }
                                });
                if (!entered.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Writer did not start");
                }
                var samples = new ArrayList<Long>();
                while (!writer.isDone()) {
                    long began = System.nanoTime();
                    if (!Arrays.equals(bytes, reader.readVerified(object.key(), Cancellation.NONE))
                            || reader.buildBytes("build") != bytes.length) {
                        throw new AssertionError("Committed reader changed during bulk write");
                    }
                    samples.add(System.nanoTime() - began);
                }
                double writeMs = writer.get(10, TimeUnit.SECONDS);
                if (reader.buildBytes("bulk") != 512L << 20) {
                    throw new AssertionError("Bulk publication missing");
                }
                samples.sort(Long::compare);
                if (samples.isEmpty()) {
                    throw new AssertionError("No overlapping read samples");
                }
                System.out.printf(
                        Locale.ROOT,
                        "bulk_mib=512,write_checkpoint_ms=%.3f,read_and_account_samples=%d,p50_ms=%.3f,p95_ms=%.3f,p99_ms=%.3f,max_ms=%.3f%n",
                        writeMs,
                        samples.size(),
                        percentile(samples, .50),
                        percentile(samples, .95),
                        percentile(samples, .99),
                        samples.getLast() / 1e6);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static double percentile(ArrayList<Long> samples, double percentile) {
        return samples.get(
                        Math.min(
                                samples.size() - 1,
                                (int) Math.ceil(samples.size() * percentile) - 1))
                / 1e6;
    }
}
