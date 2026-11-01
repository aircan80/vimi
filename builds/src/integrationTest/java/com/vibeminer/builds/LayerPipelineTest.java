package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.MemorySources;
import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.ObjectKind;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Composition contracts across the real storage, access, catalog and build implementations. */
@Timeout(30)
final class LayerPipelineTest {
    @TempDir Path folder;

    @Test
    void recordedDiscoveryOpensAndResolvesAfterOfflineReopenAndCatalogRebuild() throws Exception {
        SyntheticBuild fixture = new SyntheticBuild();
        MemorySources network = new MemorySources();
        fixture.objects.forEach(
                (key, bytes) -> network.add(kind(key), new CdnKey(key.bytes()), bytes));
        network.version(
                "wow",
                "us",
                DocumentKind.VERSIONS,
                text(
                        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                                + "## seqn = 1\nus|"
                                + fixture.buildKey.hex()
                                + "|"
                                + fixture.cdnKey.hex()
                                + "|1.0.0.1000\n"));
        network.version(
                "wow",
                "us",
                DocumentKind.CDNS,
                text(
                        "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n## seqn = 1\n"
                                + "us|tpr/wow|us.cdn.blizzard.com\n"));
        Path data = folder.resolve("objects");
        Path cache = folder.resolve("catalog.sqlite3");
        BuildRef ref;
        DownloadedFigures.Figure expected;
        ObjectStore store = ObjectStore.open(data).join();
        CachingObjectSource source =
                new CachingObjectSource(
                        store,
                        store,
                        store,
                        null,
                        network,
                        List.of(ObservationAdapters.versions(network)));
        Catalog catalog = Catalog.open(source, cache).join();
        BuildOpener opener = new BuildOpener(source);
        DownloadedFigures figures = new DownloadedFigures(source);
        try (var commits = source.subscribe(64)) {
            var refreshed = catalog.refresh("wow", Cancellation.NONE).join();
            assertTrue(refreshed.versions().recorded());
            assertTrue(refreshed.cdns().recorded());
            assertEquals(1, refreshed.list().builds().size());
            var discovered = refreshed.list().builds().getFirst();
            ref =
                    new BuildRef(
                            discovered.product(),
                            discovered.buildConfig(),
                            discovered.regions().getFirst().cdnConfig(),
                            "us");
            assertEquals(
                    2, source.readHistory(new History.Query(), Cancellation.NONE).join().size());
            var opened = opener.open(ref, Cancellation.NONE).join();
            assertEquals(BuildOpenState.OPEN, opened.state(), opened.code());
            assertResolution(opened.opened());
            expected = figures.get(opened.opened(), Cancellation.NONE).join();
            assertEquals(3, expected.totalObjects());
            assertEquals(1, expected.storedObjects());
            int notifications = 0;
            for (var commit = commits.poll(); commit != null; commit = commits.poll()) {
                for (var key : commit.stored()) {
                    assertTrue(store.has(key).join(), "commit must follow durable publication");
                    notifications++;
                }
            }
            assertFalse(commits.takeMissed());
            assertTrue(notifications >= fixture.objects.size());
        } finally {
            figures.closeAsync().join();
            opener.closeAsync().join();
            catalog.closeAsync().join();
            source.closeAsync().join();
            store.closeAsync().join();
        }
        int requests = network.requested().size();
        assertTrue(requests >= fixture.objects.size() + 2);
        // First use the durable projection; then discard it and reconstruct from stored history.
        for (boolean rebuild : new boolean[] {false, true}) {
            if (rebuild) {
                Files.delete(cache);
            }
            store = ObjectStore.open(data).join();
            source = CachingObjectSource.localOnly(store, store);
            catalog = Catalog.open(source, cache).join();
            opener = new BuildOpener(source);
            figures = new DownloadedFigures(source);
            try {
                assertEquals(1, catalog.listBuilds("wow").join().builds().size());
                assertEquals(
                        2,
                        source.readHistory(new History.Query(), Cancellation.NONE).join().size());
                var opened = opener.open(ref, Cancellation.NONE).join();
                assertEquals(BuildOpenState.OPEN, opened.state(), opened.code());
                assertResolution(opened.opened());
                assertEquals(expected, figures.get(opened.opened(), Cancellation.NONE).join());
                assertEquals(requests, network.requested().size());
            } finally {
                figures.closeAsync().join();
                opener.closeAsync().join();
                catalog.closeAsync().join();
                source.closeAsync().join();
                store.closeAsync().join();
            }
        }
    }

    private static void assertResolution(Build opened) {
        assertEquals(FileResolutionState.FOUND, opened.resolve(100).state());
        assertEquals(2, opened.resolve(100).variants().size());
        assertEquals(opened.resolve(100), opened.resolve("interface/icons/one.blp"));
        assertEquals(FileResolutionState.NOT_IN_ENCODING, opened.resolve(200).state());
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ObjectKind kind(ObjectKey key) {
        return switch (key.type()) {
            case ObjectKey.CONFIG -> ObjectKind.CONFIG;
            case ObjectKey.ARCHIVE_INDEX, ObjectKey.FILE_INDEX -> ObjectKind.INDEX;
            case ObjectKey.ENCODED -> ObjectKind.DATA;
            default -> throw new IllegalArgumentException("Unexpected synthetic object " + key);
        };
    }
}
