package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.DiskGeneratedStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedFileCatalogDiskTest {
    @TempDir Path directory;

    @Test
    void reopeningTheNativeStoreRestoresTheCatalogWithoutResolverCalls() {
        var build = new PreparedCatalogFixture(1000);
        var first = DiskGeneratedStore.open(directory).join();
        try {
            new PreparedFileCatalog(first)
                    .prepare(build, build.identity(), 1000, (done, total) -> {}, Cancellation.NONE)
                    .join();
        } finally {
            first.closeAsync().join();
        }
        int calls = build.resolutions.get(), scans = build.enumerations.get();
        var second = DiskGeneratedStore.open(directory).join();
        try {
            var cache = new PreparedFileCatalog(second);
            var restored = cache.restore(build.identity(), Cancellation.NONE).join();
            assertNotNull(restored);
            assertTrue(restored.restored());
            assertEquals(1000, restored.files());
            assertEquals(
                    1000,
                    cache.page(restored, 999, 1, Cancellation.NONE).join().getFirst().fileDataId());
            assertEquals(calls, build.resolutions.get());
            assertEquals(scans, build.enumerations.get());
        } finally {
            second.closeAsync().join();
        }
    }
}
