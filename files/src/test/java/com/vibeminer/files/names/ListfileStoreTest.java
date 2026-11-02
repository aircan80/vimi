package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ListfileStoreTest {
    static final byte[] CSV = "0;A/blp\n9;a\\BLP\n4294967295;é".getBytes(StandardCharsets.UTF_8);

    static ListfileStore.Release release() {
        return new ListfileStore.Release(
                "v1", ListfileDeltaTest.sha(CSV), Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
    }

    @Test
    void boundedIndexUnsignedLookupsCollisionsAndReopen() {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        ListfileStore index =
                ListfileStore.buildSync(
                        store,
                        release(),
                        new ByteArrayInputStream(CSV),
                        false,
                        Cancellation.NONE,
                        (seed, name) -> 0);
        assertEquals("é", index.nameOf(4294967295L, Cancellation.NONE).join());
        assertEquals(List.of(0L, 9L), index.idsOf("a/Blp", Cancellation.NONE).join());
        assertEquals(List.of(), index.idsOf("other", Cancellation.NONE).join());
        assertEquals(
                List.of(9L, 4294967295L),
                index.from(1, 10, Cancellation.NONE).join().stream()
                        .map(NamesTypes.NameEntry::id)
                        .toList());
        ListfileStore.Open reopened =
                ListfileStore.openSync(
                        store, "v1", release().sha256(), Cancellation.NONE, (seed, name) -> 0);
        assertEquals(GeneratedTypes.ReadState.PRESENT, reopened.state());
        assertEquals(List.of(0L, 9L), reopened.index().idsOf("A/BLP", Cancellation.NONE).join());
    }

    @Test
    void shaRefusalNeverPublishesPathsManifest() {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        ListfileStore.Release release =
                new ListfileStore.Release("v1", "0".repeat(64), Instant.EPOCH, Instant.EPOCH);
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () ->
                        ListfileStore.build(
                                        store,
                                        release,
                                        new ByteArrayInputStream(CSV),
                                        false,
                                        Cancellation.NONE)
                                .join());
        assertEquals(
                GeneratedTypes.ReadState.ABSENT,
                ListfileStore.open(store, "v1", release.sha256(), Cancellation.NONE)
                        .join()
                        .state());
    }

    @Test
    void damagedLookupLosesNameAndRepairReplacesOnlyBrokenSegments() {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        ListfileStore index =
                ListfileStore.build(
                                store,
                                release(),
                                new ByteArrayInputStream(CSV),
                                false,
                                Cancellation.NONE)
                        .join();
        GeneratedKey segment =
                index.keys().stream()
                        .filter(
                                k ->
                                        !ListfileStore.manifestKeys("v1", release().sha256())
                                                .contains(k))
                        .findFirst()
                        .orElseThrow();
        store.damage(segment);
        assertNull(index.nameOf(0, Cancellation.NONE).join());
        assertTrue(index.isDamaged());
        ListfileStore repaired =
                ListfileStore.build(
                                store,
                                release(),
                                new ByteArrayInputStream(CSV),
                                true,
                                Cancellation.NONE)
                        .join();
        assertEquals("A/blp", repaired.nameOf(0, Cancellation.NONE).join());
    }

    @Test
    void indexAlreadyPresentDoesNotReadCsv() {
        MemoryGeneratedStore store = new MemoryGeneratedStore();
        ListfileStore.build(
                        store, release(), new ByteArrayInputStream(CSV), false, Cancellation.NONE)
                .join();
        InputStream forbidden =
                new InputStream() {
                    public int read() {
                        throw new AssertionError("Must reuse manifest");
                    }
                };
        assertEquals(
                3,
                ListfileStore.build(store, release(), forbidden, false, Cancellation.NONE)
                        .join()
                        .count());
    }
}
