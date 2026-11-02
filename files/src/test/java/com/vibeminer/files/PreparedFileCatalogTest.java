package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class PreparedFileCatalogTest {
    @Test
    void secondOpenRestoresAllFactsWithoutAnyFileEnumerationOrPresenceScan() {
        var store = new MemoryGeneratedStore();
        var build = new PreparedCatalogFixture(2500);
        var cache = new PreparedFileCatalog(store);
        assertNull(cache.restore(build.identity(), Cancellation.NONE).join());
        var prepared =
                cache.prepare(build, build.identity(), 2500, (done, total) -> {}, Cancellation.NONE)
                        .join();
        assertEquals(2500, prepared.files());
        assertEquals(1250, prepared.unknownSize());
        assertFalse(prepared.restored());
        int resolutions = build.resolutions.get(), enumerations = build.enumerations.get();
        var reopened =
                new PreparedFileCatalog(store).restore(build.identity(), Cancellation.NONE).join();
        assertNotNull(reopened);
        assertTrue(reopened.restored());
        assertEquals(resolutions, build.resolutions.get());
        assertEquals(enumerations, build.enumerations.get());
        var rows = cache.page(reopened, 0, 3, Cancellation.NONE).join();
        assertEquals(1, rows.getFirst().fileDataId());
        assertEquals(10L, rows.getFirst().decodedSize());
        assertEquals(5L, rows.getFirst().encodedSize());
        assertNull(rows.get(1).decodedSize());
        assertEquals(build.identity().locale(), "enUS");
    }

    @Test
    void changedLocaleAndDamagedSegmentsAreCacheMissesAndCanBeRebuilt() {
        var store = new MemoryGeneratedStore();
        var build = new PreparedCatalogFixture(10);
        var cache = new PreparedFileCatalog(store);
        var prepared =
                cache.prepare(build, build.identity(), 10, (done, total) -> {}, Cancellation.NONE)
                        .join();
        var other = new PreparedFileCatalog.Identity(build.build(), "deDE", build.metadata());
        assertNull(cache.restore(other, Cancellation.NONE).join());
        store.damage(prepared.records().keys().getLast());
        assertNull(cache.restore(build.identity(), Cancellation.NONE).join());
        cache.prepare(build, build.identity(), 10, (done, total) -> {}, Cancellation.NONE).join();
        assertNotNull(cache.restore(build.identity(), Cancellation.NONE).join());
    }

    @Test
    void canceledFirstPassNeverPublishesACompletedCatalog() {
        var store = new MemoryGeneratedStore();
        var build = new PreparedCatalogFixture(2500);
        var cache = new PreparedFileCatalog(store);
        var token = new Cancellation();
        assertThrows(
                CompletionException.class,
                () ->
                        cache.prepare(
                                        build,
                                        build.identity(),
                                        2500,
                                        (done, total) -> token.cancel(),
                                        token)
                                .join());
        assertNull(cache.restore(build.identity(), Cancellation.NONE).join());
    }
}
