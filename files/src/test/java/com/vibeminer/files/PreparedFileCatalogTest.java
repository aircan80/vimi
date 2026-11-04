package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.generated.RecordFileWriter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.LongFunction;
import java.util.stream.LongStream;
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

    @Test
    void preparedCatalogBytesMatchWritingEachRecordIndividually() {
        // Several full 4096-record batches plus a partial one.
        var build = new PreparedCatalogFixture(4096 * 3 + 517);
        var store = new MemoryGeneratedStore();
        var prepared =
                new PreparedFileCatalog(store)
                        .prepare(
                                build,
                                build.identity(),
                                4096 * 3 + 517,
                                (done, total) -> {},
                                Cancellation.NONE)
                        .join();
        assertEquals(4096 * 3 + 517, prepared.files());

        var expected = new MemoryGeneratedStore();
        long unknown = 0;
        try (var writer = new RecordFileWriter(expected)) {
            for (long id : build.fileDataIds("enUS")) {
                var variant = build.resolve(id).variants().getFirst();
                unknown += variant.decodedSize() == null ? 1 : 0;
                writer.add(id, PreparedFileCatalog.encode(variant)).join();
            }
            writer.complete(
                            PreparedFileCatalog.key(build.identity()),
                            PreparedFileCatalog.metadata(build.identity(), unknown))
                    .join();
        }
        assertEquals(objects(expected), objects(store));
    }

    @Test
    void duplicateIdInsideABatchFailsAsBeforeAndPublishesNothing() {
        var fixture = new PreparedCatalogFixture(6000);
        // A duplicate early in the second batch, followed in that batch by an unresolvable ID:
        // the writer's duplicate failure is still the one reported, as when records were written
        // one by one.
        var build =
                new Ids(
                        fixture,
                        LongStream.concat(
                                        LongStream.rangeClosed(1, 4200),
                                        LongStream.of(4100, 4201, 999_999))
                                .toArray(),
                        id -> id == 999_999 ? unresolved(id) : fixture.resolve(id));
        var store = new MemoryGeneratedStore();
        var cache = new PreparedFileCatalog(store);
        var failure =
                assertThrows(
                        CompletionException.class,
                        () ->
                                cache.prepare(
                                                build,
                                                fixture.identity(),
                                                6000,
                                                (done, total) -> {},
                                                Cancellation.NONE)
                                        .join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("Duplicate record id", failure.getCause().getMessage());
        assertNull(cache.restore(fixture.identity(), Cancellation.NONE).join());
    }

    @Test
    void unresolvedIdInsideABatchFailsAsBeforeAndPublishesNothing() {
        var fixture = new PreparedCatalogFixture(6000);
        var build =
                new Ids(
                        fixture,
                        LongStream.rangeClosed(1, 6000).toArray(),
                        id -> id == 5000 ? unresolved(id) : fixture.resolve(id));
        var store = new MemoryGeneratedStore();
        var cache = new PreparedFileCatalog(store);
        var failure =
                assertThrows(
                        CompletionException.class,
                        () ->
                                cache.prepare(
                                                build,
                                                fixture.identity(),
                                                6000,
                                                (done, total) -> {},
                                                Cancellation.NONE)
                                        .join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Locale catalog contains an unresolved ID", failure.getCause().getMessage());
        assertNull(cache.restore(fixture.identity(), Cancellation.NONE).join());
    }

    @Test
    void cancellationInsideABatchStopsBeforeTheNextRecordAndPublishesNothing() {
        var fixture = new PreparedCatalogFixture(9000);
        var token = new Cancellation();
        long[] resolved = new long[1];
        var build =
                new Ids(
                        fixture,
                        LongStream.rangeClosed(1, 9000).toArray(),
                        id -> {
                            resolved[0] = id;
                            if (id == 6123) {
                                token.cancel();
                            }
                            return fixture.resolve(id);
                        });
        var store = new MemoryGeneratedStore();
        var cache = new PreparedFileCatalog(store);
        var failure =
                assertThrows(
                        CompletionException.class,
                        () ->
                                cache.prepare(
                                                build,
                                                fixture.identity(),
                                                9000,
                                                (done, total) -> {},
                                                token)
                                        .join());
        assertInstanceOf(CancellationException.class, failure.getCause());
        assertEquals(6123, resolved[0]);
        assertNull(cache.restore(fixture.identity(), Cancellation.NONE).join());
    }

    @Test
    void pendingDuplicateIdStillWinsOverALaterCancellation() {
        var fixture = new PreparedCatalogFixture(6000);
        var token = new Cancellation();
        var build =
                new Ids(
                        fixture,
                        LongStream.concat(
                                        LongStream.rangeClosed(1, 4200),
                                        LongStream.of(4100, 4201, 4202, 4203))
                                .toArray(),
                        id -> {
                            if (id == 4202) {
                                token.cancel();
                            }
                            return fixture.resolve(id);
                        });
        var store = new MemoryGeneratedStore();
        var cache = new PreparedFileCatalog(store);
        var failure =
                assertThrows(
                        CompletionException.class,
                        () ->
                                cache.prepare(
                                                build,
                                                fixture.identity(),
                                                6000,
                                                (done, total) -> {},
                                                token)
                                        .join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("Duplicate record id", failure.getCause().getMessage());
        assertNull(cache.restore(fixture.identity(), Cancellation.NONE).join());
    }

    private static BuildTypes.FileResolution unresolved(long id) {
        return new BuildTypes.FileResolution(
                BuildTypes.FileResolutionState.UNKNOWN_FILE, id, List.of());
    }

    private static Map<String, String> objects(MemoryGeneratedStore store) {
        Map<String, String> objects = new TreeMap<>();
        for (GeneratedKey key : store.keys()) {
            objects.put(key.hex(), HexFormat.of().formatHex(store.read(key).join().bytes()));
        }
        return objects;
    }

    /** The fixture's build with a chosen ID listing and resolver. */
    private record Ids(
            PreparedCatalogFixture fixture,
            long[] ids,
            LongFunction<BuildTypes.FileResolution> resolver)
            implements Build {
        public BuildTypes.BuildRef build() {
            return fixture.build();
        }

        public BuildTypes.BuildMetadata metadata() {
            return fixture.metadata();
        }

        public com.vibeminer.access.SourceTypes.CdnContext cdn() {
            return fixture.cdn();
        }

        public BuildTypes.FileResolution resolve(long id) {
            return resolver.apply(id);
        }

        public BuildTypes.FileResolution resolve(String path) {
            return fixture.resolve(path);
        }

        public Iterable<Long> fileDataIds(String locale, long from) {
            return () -> LongStream.of(ids).filter(id -> id >= from).boxed().iterator();
        }
    }
}
