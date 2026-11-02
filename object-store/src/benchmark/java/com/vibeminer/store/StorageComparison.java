package com.vibeminer.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Equal-input sequential whole-object workload; production and experimental API costs included. */
public final class StorageComparison {
    private StorageComparison() {}

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "comparison-");
        System.out.println("backend,round,objects,object_bytes,write_ms,read_ms,physical_bytes");
        try {
            for (int round = 0; round < 3; round++) {
                for (int size : new int[] {64 << 10, 128 << 10, 512 << 10, 1 << 20}) {
                    List<StoreTypes.PutObject> objects = new ArrayList<>();
                    var random = new Random(42);
                    for (int i = 0; i < 32; i++) {
                        byte[] bytes = new byte[size];
                        random.nextBytes(bytes);
                        objects.add(
                                new StoreTypes.PutObject(
                                        new ObjectKey(1, Format.hash("MD5", bytes)), bytes));
                    }
                    // Alternate order so one backend does not always get the first run.
                    for (boolean sqlite :
                            round % 2 == 0
                                    ? new boolean[] {true, false}
                                    : new boolean[] {false, true}) {
                        Path folder = directory.resolve(round + "-" + size + "-" + sqlite);
                        Files.createDirectory(folder);
                        long began = System.nanoTime();
                        long written;
                        long read;
                        if (sqlite) {
                            try (var store =
                                    new SqliteBlobPrototype(folder.resolve("catalog.sqlite"))) {
                                for (var object : objects) {
                                    store.putVerified(object, "synthetic-build", Cancellation.NONE);
                                }
                                store.checkpoint();
                                written = System.nanoTime();
                                for (var object : objects) {
                                    if (!Arrays.equals(
                                            object.data(),
                                            store.readVerified(object.key(), Cancellation.NONE))) {
                                        throw new AssertionError("SQLite read differs");
                                    }
                                }
                                read = System.nanoTime();
                            }
                        } else {
                            var options =
                                    new StoreTypes.Options(
                                            StoreTypes.Kind.CDN, 1L << 30, Clock.systemUTC());
                            var store = ObjectStore.open(folder, options).join();
                            try {
                                for (var object : objects) {
                                    if (store.put(List.of(object)).join().getFirst().outcome()
                                            != StoreTypes.PutOutcome.STORED) {
                                        throw new AssertionError("Pack write failed");
                                    }
                                }
                                written = System.nanoTime();
                                for (var object : objects) {
                                    if (!Arrays.equals(
                                            object.data(),
                                            store.read(object.key()).join().bytes())) {
                                        throw new AssertionError("Pack read differs");
                                    }
                                }
                                read = System.nanoTime();
                            } finally {
                                store.closeAsync().join();
                            }
                        }
                        long physical = 0;
                        try (var paths = Files.walk(folder)) {
                            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                                physical += Files.size(file);
                            }
                        }
                        System.out.printf(
                                Locale.ROOT,
                                "%s,%d,%d,%d,%.3f,%.3f,%d%n",
                                sqlite ? "sqlite" : "packs",
                                round,
                                objects.size(),
                                size,
                                (written - began) / 1e6,
                                (read - written) / 1e6,
                                physical);
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
