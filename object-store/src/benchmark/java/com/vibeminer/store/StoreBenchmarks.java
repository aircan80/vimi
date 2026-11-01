package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Opt-in deterministic workloads; reports no user paths or machine identifiers. */
public final class StoreBenchmarks {
    private StoreBenchmarks() {}

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(args[0]);
        int bytes = Integer.parseInt(args[1]);
        String mode = args[2];
        if (count < 2
                || count > 4096
                || bytes < 4
                || bytes > 16 << 20
                || (long) count * bytes > 256L << 20
                || !List.of("all", "write").contains(mode)) {
            throw new IllegalArgumentException(
                    "Use 2-4096 objects, 4 B-16 MiB each, total <=256 MiB, mode all/write");
        }
        List<PutObject> objects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] payload = new byte[bytes];
            for (int at = 0; at < bytes; at++) {
                payload[at] = (byte) (at * 31 + i);
            }
            Format.le(payload).putInt(i);
            objects.add(new PutObject(new ObjectKey(1, Format.hash("MD5", payload)), payload));
        }
        System.out.printf(
                Locale.ROOT,
                "environment,java=%s,os=%s,arch=%s,cpus=%d,objects=%d,bytes=%d%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(),
                count,
                bytes);
        Path directory = Files.createTempDirectory("vimi-benchmark-");
        ObjectStore store = null;
        Options options = new Options(StoreTypes.Kind.CDN, 1L << 20, Clock.systemUTC());
        try {
            store = ObjectStore.open(directory, options).join();
            long began = System.nanoTime();
            List<CompletableFuture<?>> writes = new ArrayList<>();
            for (PutObject object : objects) {
                writes.add(
                        store.put(List.of(object))
                                .thenAccept(
                                        result -> {
                                            if (result.getFirst().outcome() != PutOutcome.STORED) {
                                                throw new AssertionError(
                                                        "Benchmark did not store a unique synthetic object");
                                            }
                                        }));
            }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
            report("write", began, count, (long) count * bytes);
            if (mode.equals("write")) {
                return;
            }
            began = System.nanoTime();
            for (PutObject object : objects) {
                if (!Arrays.equals(object.data(), store.read(object.key()).join().bytes())) {
                    throw new AssertionError("Read payload differs");
                }
            }
            report("read", began, count, (long) count * bytes);
            store.closeAsync().join();
            store = null;
            Files.delete(directory.resolve("objects.sqlite3"));
            began = System.nanoTime();
            store = ObjectStore.open(directory, options).join();
            if (!store.openReport().indexRebuilt() || store.stats().join().objects() != count) {
                throw new AssertionError("Rebuild changed object count");
            }
            report("rebuild", began, count, (long) count * bytes);
            List<ObjectKey> deleted = new ArrayList<>();
            for (int i = 0; i < count; i += 2) {
                deleted.add(objects.get(i).key());
            }
            store.delete(deleted).join();
            store.scan(Cancellation.NONE).join();
            began = System.nanoTime();
            var compacted = store.compact(0, Integer.MAX_VALUE, Cancellation.NONE).join();
            if (compacted.packsCompacted() == 0
                    || store.stats().join().objects() != count - deleted.size()) {
                throw new AssertionError("Compaction did not preserve the expected live count");
            }
            report("compact", began, compacted.moved(), compacted.bytesBefore());
            if (store.verify(true, Cancellation.NONE).join().corruptCount() != 0) {
                throw new AssertionError("Benchmark store failed integrity verification");
            }
        } finally {
            if (store != null) {
                store.closeAsync().join();
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void report(String phase, long began, long objects, long bytes) {
        double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
        System.out.printf(
                Locale.ROOT,
                "%s,seconds=%.6f,objects=%d,bytes=%d,mib_per_second=%.3f%n",
                phase,
                seconds,
                objects,
                bytes,
                bytes / (1024.0 * 1024.0) / seconds);
    }
}
