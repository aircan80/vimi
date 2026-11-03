package com.vimi.gui;

import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.MetadataFixtures;
import com.vibeminer.builds.SyntheticBuild;
import com.vibeminer.files.FileFixtures;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.History;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Default application composition and real preparation over one offline unified pack library. */
public final class FullPreparationLibraryIntegrationTest {
    private FullPreparationLibraryIntegrationTest() {}

    private record Fixture(ObjectKey buildConfig, ObjectKey media) {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-full-library-preparation-");
        try {
            Fixture fixture = seed(directory);
            String identity = null;
            long generatedAfterFirst = 0;
            for (int round = 0; round < 2; round++) {
                var service = BuildApplication.open(directory).get(20, TimeUnit.SECONDS);
                try {
                    require(
                            service.preparation() != null,
                            "Default native application wires real preparation");
                    var build =
                            service.listBuilds().get(15, TimeUnit.SECONDS).stream()
                                    .filter(
                                            value ->
                                                    value.config()
                                                            .equals(fixture.buildConfig().hex()))
                                    .findFirst()
                                    .orElseThrow();
                    if (identity == null) identity = build.identity();
                    require(
                            identity.equals(build.identity()),
                            "Canonical build identity survives reopen");
                    var job =
                            service.preparation()
                                    .start(build, build.observations().getFirst(), "enUS");
                    job.metadataReady().get(20, TimeUnit.SECONDS);
                    var snapshot = job.completion().get(40, TimeUnit.SECONDS);
                    require(
                            snapshot.ready() == BuildPreparation.Ready.TABLES_READY
                                    && snapshot.activity() == BuildPreparation.Activity.IDLE,
                            "Default pipeline reaches Tables-ready: " + snapshot.problem());
                    require(
                            snapshot.files().files() == 2,
                            "Prepared file catalog exposes the two authored ROOT entries");
                    require(
                            snapshot.tables().stream()
                                    .anyMatch(
                                            entry ->
                                                    entry.name().equals("Item")
                                                            && entry.state()
                                                                    == TableTypes.State.ENCRYPTED),
                            "Protected Item table remains an explicit typed outcome");
                    var work = (NativePreparationWork) job.work();
                    require(
                            work.filesHandle() != null
                                    && work.tablesHandle() != null
                                    && work.itemsHandle() != null
                                    && work.creaturesHandle() != null
                                    && work.effectsHandle() != null,
                            "Files/tables/items/creatures/effects are retained native services");
                    require(
                            work.itemsHandle()
                                            .getData(
                                                    1,
                                                    TableTypes.Priority.INTERACTIVE,
                                                    com.vibeminer.access.Cancellation.NONE)
                                            .join()
                                            .state()
                                    == com.vibeminer.concepts.items.ItemResultState
                                            .ITEM_NOT_READABLE,
                            "Item queries report unreadable protected data rather than fabricated"
                                    + " rows");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while ((work.accounting() == null
                                    || work.accounting().usage() == null
                                    || work.accounting().measuring())
                            && System.nanoTime() < deadline) {
                        Thread.sleep(20);
                    }
                    require(
                            work.accounting() != null && work.accounting().usage() != null,
                            "Workspace accounting is wired to live catalog data");
                    require(
                            work.accounting().usage().derived().databaseBytes() == 0,
                            "Relational query database bytes are excluded from object attribution");
                    var load = service.refreshLoadSnapshot(build).get(10, TimeUnit.SECONDS);
                    require(
                            load.entry().status() == BuildRegistry.Status.LOADED,
                            "Preparation persists the table-backed metadata load status");
                    require(
                            load.usage() != null && load.usage().original().storedBytes() > 0,
                            "Cached original metadata/data belongs to the build and is measured");
                    try (var database =
                            DerivedSqliteConnection.openReadOnly(
                                    directory.resolve("library/objects.sqlite3"))) {
                        String owner = identity;
                        long generated =
                                database.run(
                                        Cancellation.NONE,
                                        false,
                                        session ->
                                                ((Number)
                                                                session.query(
                                                                                "SELECT count(*)"
                                                                                    + " FROM"
                                                                                    + " storage_objects"
                                                                                    + " WHERE"
                                                                                    + " role='DERIVED'")
                                                                        .getFirst()[0])
                                                        .longValue());
                        long attributed =
                                database.run(
                                        Cancellation.NONE,
                                        false,
                                        session ->
                                                ((Number)
                                                                session.query(
                                                                                "SELECT"
                                                                                    + " count(DISTINCT"
                                                                                    + " r.k) FROM"
                                                                                    + " catalog_refs"
                                                                                    + " r JOIN"
                                                                                    + " catalog_expected"
                                                                                    + " e ON"
                                                                                    + " e.active=r.generation"
                                                                                    + " JOIN"
                                                                                    + " storage_objects"
                                                                                    + " o ON"
                                                                                    + " o.k=r.k"
                                                                                    + " WHERE"
                                                                                    + " e.owner=?"
                                                                                    + " AND o.role='DERIVED'",
                                                                                owner)
                                                                        .getFirst()[0])
                                                        .longValue());
                        require(
                                generated > 0 && attributed > 0,
                                "Generated file/table artifacts live in shared packs with build"
                                        + " ownership");
                        long redundantInventories =
                                database.run(
                                        Cancellation.NONE,
                                        false,
                                        session ->
                                                ((Number)
                                                                session.query(
                                                                                "SELECT count(*)"
                                                                                    + " FROM"
                                                                                    + " catalog_expected"
                                                                                    + " WHERE"
                                                                                    + " owner=? AND"
                                                                                    + " role='DERIVED'"
                                                                                    + " AND source"
                                                                                    + " NOT GLOB"
                                                                                    + " 'storage:*'",
                                                                                owner)
                                                                        .getFirst()[0])
                                                        .longValue());
                        require(
                                redundantInventories == 0,
                                "Unified accounting uses publication metadata, not artifact graph"
                                    + " scans");
                        require(
                                work.accounting().usage().derivedComplete(),
                                "All current library derived objects have authoritative ownership");
                        long mediaReads =
                                database.run(
                                        Cancellation.NONE,
                                        false,
                                        session ->
                                                ((Number)
                                                                session.query(
                                                                                "SELECT count(*)"
                                                                                    + " FROM"
                                                                                    + " catalog_refs"
                                                                                    + " r JOIN"
                                                                                    + " catalog_expected"
                                                                                    + " e ON"
                                                                                    + " e.active=r.generation"
                                                                                    + " WHERE"
                                                                                    + " e.owner=?"
                                                                                    + " AND e.source"
                                                                                    + " GLOB"
                                                                                    + " 'storage:*'"
                                                                                    + " AND r.k=?",
                                                                                owner,
                                                                                fixture.media()
                                                                                        .indexBytes())
                                                                        .getFirst()[0])
                                                        .longValue());
                        require(
                                mediaReads == 0,
                                "Preparation and accounting do not acquire cached media content");
                        String readiness =
                                database.run(
                                        Cancellation.NONE,
                                        false,
                                        session ->
                                                (String)
                                                        session.query(
                                                                        "SELECT readiness FROM"
                                                                            + " builds_preparation_receipts"
                                                                            + " WHERE identity=?",
                                                                        owner)
                                                                .getFirst()[0]);
                        require(
                                "TABLES_READY".equals(readiness),
                                "Preparation readiness is persisted in the shared SQL receipt"
                                        + " table");
                        if (round == 0) generatedAfterFirst = generated;
                        else
                            require(
                                    generated >= generatedAfterFirst,
                                    "Reopen restores retained generated artifact graph");
                    }
                    require(
                            service.historicalPreparation(build) != null
                                    && service.historicalPreparation(build)
                                            .contains("Tables ready"),
                            "Preparation receipts persist through the shared library receipt"
                                + " table");
                } finally {
                    service.closeAsync().get(20, TimeUnit.SECONDS);
                }
            }
            require(
                    !Files.exists(directory.resolve("objects"))
                            && !Files.exists(directory.resolve("generated")),
                    "Native composition does not create separate legacy object or generated"
                            + " stores");
            require(
                    !Files.exists(directory.resolve("accounting.sqlite")),
                    "Live accounting does not create a second presence projection");
            System.out.println(
                    "FullPreparationLibraryIntegration: default files/tables/research services,"
                            + " unified ownership/accounting and reopen passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static Fixture seed(Path directory) throws Exception {
        var fixture = new SyntheticBuild();
        byte[] db2 = new byte[128];
        System.arraycopy(MetadataFixtures.text("WDC5"), 0, db2, 0, 4);
        var encrypted =
                FileFixtures.chunked(
                        List.of(
                                FileFixtures.encrypted(
                                        0x1122334455667788L,
                                        new byte[16],
                                        FileFixtures.raw(db2),
                                        0)));
        byte[] mediaBody = new byte[128];
        System.arraycopy(new byte[] {'M', 'D', '2', '0'}, 0, mediaBody, 0, 4);
        var media = MetadataFixtures.chunked(mediaBody, 'N');
        CdnKey itemContent = new CdnKey(MetadataFixtures.md5(db2));
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
                                                                999,
                                                                media.contentKey(),
                                                                "creature/never-read.m2"),
                                                        new MetadataFixtures.RootRow(
                                                                841626,
                                                                itemContent,
                                                                "dbfilesclient/item.db2"))))),
                        'Z');
        byte[] encodingBytes =
                MetadataFixtures.encoding(
                        List.of(
                                new MetadataFixtures.EncodingRow(
                                        itemContent,
                                        db2.length,
                                        encrypted.key(),
                                        encrypted.bytes().length),
                                new MetadataFixtures.EncodingRow(
                                        media.contentKey(),
                                        mediaBody.length,
                                        media.encodedKey(),
                                        media.encoded().length),
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
        var mediaKey = new ObjectKey(ObjectKey.ENCODED, media.encodedKey().bytes());
        fixture.objects.put(mediaKey, media.encoded());
        fixture.objects.put(
                new ObjectKey(ObjectKey.ENCODED, root.encodedKey().bytes()), root.encoded());
        fixture.objects.put(
                new ObjectKey(ObjectKey.ENCODED, encoding.encodedKey().bytes()),
                encoding.encoded());
        fixture.objects.put(
                new ObjectKey(ObjectKey.ENCODED, encrypted.key().bytes()), encrypted.bytes());
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            catalog.retainOwner("fixture:source", LibraryCatalog.Scope.GLOBAL).join();
            var source =
                    library.forOwner(
                            "fixture:source",
                            LibraryCatalog.Scope.GLOBAL,
                            "source",
                            LibraryCatalog.Role.ORIGINAL);
            var outcomes =
                    source.put(
                                    fixture.objects.entrySet().stream()
                                            .map(
                                                    entry ->
                                                            new StoreTypes.PutObject(
                                                                    entry.getKey(),
                                                                    entry.getValue()))
                                            .toList())
                            .join();
            require(
                    outcomes.stream()
                            .allMatch(result -> result.outcome() == StoreTypes.PutOutcome.STORED),
                    "Authored source objects are authentic and globally cached");
            Instant now = Instant.now();
            String versions =
                    "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                            + "us|"
                            + configKey.hex()
                            + "|"
                            + fixture.ref.cdnConfig()
                            + "|1.2.3.12345\n";
            source.appendHistory(
                            new History.Observation(
                                    1,
                                    "wow",
                                    "us",
                                    now,
                                    "https://example.invalid/versions",
                                    versions.getBytes(StandardCharsets.UTF_8)),
                            Cancellation.NONE)
                    .join();
            byte[] csv =
                    "999;creature/never-read.m2\n841626;dbfilesclient/item.db2\n"
                            .getBytes(StandardCharsets.UTF_8);
            String tag = "fixture-v1";
            String digest =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
            String release =
                    "{\"tag_name\":\""
                            + tag
                            + "\",\"published_at\":\""
                            + now
                            + "\",\"assets\":[{\"name\":\""
                            + SourceUrls.ASSET_NAME
                            + "\",\"size\":"
                            + csv.length
                            + ",\"digest\":\"sha256:"
                            + digest
                            + "\",\"browser_download_url\":\""
                            + SourceUrls.asset(tag)
                            + "\"}]}";
            source.appendHistory(
                            new History.Observation(
                                    SourceTypes.ObservationKind.LISTFILE_RELEASE.historyKind(),
                                    "wowlistfile",
                                    "latest",
                                    now,
                                    SourceUrls.LATEST_RELEASE.toString(),
                                    release.getBytes(StandardCharsets.UTF_8)),
                            Cancellation.NONE)
                    .join();
            source.appendHistory(
                            new History.Observation(
                                    SourceTypes.ObservationKind.LISTFILE_PART.historyKind(),
                                    tag,
                                    SourceTypes.ObservationRequest.partRegion(0),
                                    now,
                                    SourceUrls.asset(tag).toString(),
                                    csv),
                            Cancellation.NONE)
                    .join();
        } finally {
            library.closeAsync().join();
        }
        return new Fixture(configKey, mediaKey);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
