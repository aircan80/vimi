package com.vimi.gui;

import com.vibeminer.builds.MetadataFixtures;
import com.vibeminer.builds.SyntheticBuild;
import com.vibeminer.network.NetworkClients;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real authenticated opener, download engine and owned storage; all payloads seeded offline. */
public final class NativeDownloadIntegrationTest {
    private NativeDownloadIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-native-download-");
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        var catalog = LibraryCatalog.open(library.databasePath()).join();
        var network = new NetworkClients();
        var accounting = OverviewAccounting.openUnified(directory, catalog, library);
        BuildDownloads downloads = null;
        try {
            var fixture = new SyntheticBuild();
            var content =
                    MetadataFixtures.chunked(
                            MetadataFixtures.text("download fixture payload"), 'N');
            var root =
                    MetadataFixtures.chunked(
                            MetadataFixtures.root(
                                    2,
                                    List.of(
                                            new MetadataFixtures.RootBlock(
                                                    2,
                                                    0,
                                                    0,
                                                    0,
                                                    List.of(
                                                            new MetadataFixtures.RootRow(
                                                                    123,
                                                                    content.contentKey(),
                                                                    "fixture.txt"))))),
                            'Z');
            var encodingBytes =
                    MetadataFixtures.encoding(
                            List.of(
                                    new MetadataFixtures.EncodingRow(
                                            content.contentKey(),
                                            content.content().length,
                                            content.encodedKey(),
                                            content.encoded().length),
                                    new MetadataFixtures.EncodingRow(
                                            root.contentKey(),
                                            root.content().length,
                                            root.encodedKey(),
                                            root.encoded().length)));
            var encoding = MetadataFixtures.chunked(encodingBytes, 'Z');
            byte[] config =
                    MetadataFixtures.text(
                            "root = "
                                    + root.contentKey().hex()
                                    + "\ninstall = "
                                    + MetadataFixtures.key(1).hex()
                                    + "\nencoding = "
                                    + encoding.contentKey().hex()
                                    + " "
                                    + encoding.encodedKey().hex()
                                    + "\nencoding-size = "
                                    + encodingBytes.length
                                    + " "
                                    + encoding.encoded().length
                                    + "\nbuild-name = WOW-12345patch1.2.3_Synthetic\n");
            var configKey = new ObjectKey(ObjectKey.CONFIG, MetadataFixtures.md5(config));
            fixture.objects.put(configKey, config);
            fixture.objects.put(
                    new ObjectKey(ObjectKey.ENCODED, content.encodedKey().bytes()),
                    content.encoded());
            fixture.objects.put(
                    new ObjectKey(ObjectKey.ENCODED, root.encodedKey().bytes()), root.encoded());
            fixture.objects.put(
                    new ObjectKey(ObjectKey.ENCODED, encoding.encodedKey().bytes()),
                    encoding.encoded());
            var provider =
                    library.forOwner(
                            "fixture:download-source",
                            LibraryCatalog.Scope.GLOBAL,
                            "source",
                            LibraryCatalog.Role.ORIGINAL);
            var stored =
                    provider.put(
                                    fixture.objects.entrySet().stream()
                                            .map(
                                                    e ->
                                                            new StoreTypes.PutObject(
                                                                    e.getKey(), e.getValue()))
                                            .toList())
                            .join();
            require(
                    stored.stream().allMatch(e -> e.outcome() == StoreTypes.PutOutcome.STORED),
                    "All fixture objects authenticated");
            provider.appendHistory(
                            new com.vibeminer.store.History.Observation(
                                    2,
                                    "wow",
                                    "us",
                                    java.time.Instant.EPOCH,
                                    "https://example.invalid/cdns",
                                    MetadataFixtures.text(
                                            "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n"
                                                    + "us|tpr/wow|us.cdn.blizzard.com\n")),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            var initialKeys = keys(library.databasePath());
            var build =
                    new BuildInfo(
                            "wow",
                            "Retail",
                            "fixture",
                            "us",
                            configKey.hex(),
                            BuildInfo.BuildSource.SAVED,
                            false,
                            null,
                            0);
            downloads =
                    BuildDownloads.open(
                            library.databasePath(),
                            new NativeDownloadRunner(
                                    library, network, null, accounting, directory));
            downloads
                    .enable(
                            build,
                            new BuildInfo.RegionEvidence(
                                    "us", fixture.ref.cdnConfig(), true, null, null))
                    .get(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (downloads.snapshot(build.identity()).state() != BuildDownloads.State.COMPLETED
                    && System.nanoTime() < until) Thread.sleep(20);
            var result = downloads.snapshot(build.identity());
            require(
                    result.state() == BuildDownloads.State.COMPLETED,
                    "Native full download succeeds offline: " + result);
            require(
                    result.progress().storedObjects() == 2 && result.progress().totalObjects() == 2,
                    "All EN variants checked");
            require(result.progress().cdnBytes() == 0, "Cached acquisition uses no CDN bytes");
            require(
                    catalog.figures(build.identity(), com.vibeminer.store.Cancellation.NONE)
                            .join()
                            .original()
                            .inventoryComplete(),
                    "Build owns complete published inventory");
            var newKeys = new java.util.HashSet<>(keys(library.databasePath()));
            newKeys.removeAll(initialKeys);
            require(newKeys.isEmpty(), "No duplicate payloads are written: " + newKeys);
            System.out.println(
                    "Native Download all: authenticated offline completion, build ownership and no"
                            + " duplicate bytes passed");
        } finally {
            if (downloads != null) downloads.closeAsync().get(15, TimeUnit.SECONDS);
            accounting.closeAsync().join();
            network.closeAsync().join();
            catalog.close();
            library.closeAsync().join();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static java.util.Set<String> keys(Path database) throws Exception {
        try (var connection = com.vibeminer.store.DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    com.vibeminer.store.Cancellation.NONE,
                    false,
                    sql ->
                            sql.query("SELECT hex(k) FROM storage_objects").stream()
                                    .map(row -> (String) row[0])
                                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
