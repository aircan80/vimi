package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.DiskGeneratedStore;
import java.nio.file.Files;
import java.util.Comparator;

/**
 * Explicit native-disk synthetic measurement; not part of normal checks or live-build
 * certification.
 */
public final class PreparedCatalogBenchmark {
    private PreparedCatalogBenchmark() {}

    public static void main(String[] args) throws Exception {
        int count = args.length == 0 ? 1200000 : Integer.parseInt(args[0]);
        var directory = Files.createTempDirectory("vimi-catalog-benchmark-");
        var build = new PreparedCatalogFixture(count);
        try {
            var store = DiskGeneratedStore.open(directory).join();
            long start = System.nanoTime();
            try {
                new PreparedFileCatalog(store)
                        .prepare(
                                build,
                                build.identity(),
                                count,
                                (done, total) -> {},
                                Cancellation.NONE)
                        .join();
            } finally {
                store.closeAsync().join();
            }
            long prepared = System.nanoTime();
            int scans = build.enumerations.get(), resolutions = build.resolutions.get();
            var reopened = DiskGeneratedStore.open(directory).join();
            long opened = System.nanoTime();
            try {
                var result =
                        new PreparedFileCatalog(reopened)
                                .restore(build.identity(), Cancellation.NONE)
                                .join();
                long restored = System.nanoTime();
                if (result == null
                        || result.files() != count
                        || build.enumerations.get() != scans
                        || build.resolutions.get() != resolutions) {
                    throw new AssertionError("Restore repeated file enumeration or lost rows");
                }
                System.out.printf(
                        java.util.Locale.ROOT,
                        "SYNTHETIC %d records: first prepare+close %.3f s; store reopen %.3f s; catalog restore/integrity validation %.3f s; repeat enumerations 0; repeat resolutions 0%n",
                        count,
                        (prepared - start) / 1e9,
                        (opened - prepared) / 1e9,
                        (restored - opened) / 1e9);
            } finally {
                reopened.closeAsync().join();
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
