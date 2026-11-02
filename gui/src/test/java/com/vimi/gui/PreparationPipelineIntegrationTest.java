package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildOpener;
import com.vibeminer.builds.MetadataFixtures;
import com.vibeminer.builds.SyntheticBuild;
import com.vibeminer.files.FileFixtures;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.DiskGeneratedStore;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native offline production pipeline: metadata, real Files, key bootstrap, prepareKnown and warmup.
 */
public final class PreparationPipelineIntegrationTest {
    private PreparationPipelineIntegrationTest() {}

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("vimi-preparation-pipeline-");
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
        var cachedMedia = MetadataFixtures.chunked(mediaBody, 'N');
        CdnKey itemContent = new CdnKey(MetadataFixtures.md5(db2)),
                mediaContent = cachedMedia.contentKey(),
                mediaEncoded = cachedMedia.encodedKey();
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
                                                                mediaContent,
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
                                        mediaContent,
                                        mediaBody.length,
                                        mediaEncoded,
                                        cachedMedia.encoded().length),
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
                new ObjectKey(ObjectKey.ENCODED, mediaEncoded.bytes()), cachedMedia.encoded());
        fixture.objects.put(
                new ObjectKey(ObjectKey.ENCODED, root.encodedKey().bytes()), root.encoded());
        fixture.objects.put(
                new ObjectKey(ObjectKey.ENCODED, encoding.encodedKey().bytes()),
                encoding.encoded());
        fixture.objects.put(
                new ObjectKey(ObjectKey.ENCODED, encrypted.key().bytes()), encrypted.bytes());
        var sourceEvidence =
                new BuildInfo.RegionEvidence(
                        "us", fixture.ref.cdnConfig(), true, Instant.EPOCH, Instant.EPOCH);
        var build =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "1.2.3.12345",
                        "us",
                        configKey.hex(),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0,
                        "native-pipeline",
                        Instant.EPOCH,
                        Instant.EPOCH,
                        List.of(sourceEvidence));
        try {
            for (int round = 0; round < 2; round++) {
                var store = ObjectStore.open(directory.resolve("objects")).join();
                var generated = DiskGeneratedStore.open(directory.resolve("generated")).join();
                var online = CachingObjectSource.localOnly(store, store);
                var local = CachingObjectSource.localOnly(store, store);
                var accounting = OverviewAccounting.open(directory, store, generated, online);
                require(accounting != null, "Accounting projection must open");
                var mediaRequests = new AtomicInteger();
                var indexRequests = new AtomicInteger();
                var jobPublished = new CompletableFuture<BuildPreparation.Job>();
                boolean firstAccountingPass = round == 0;
                ObjectSource guarded =
                        (ObjectSource)
                                Proxy.newProxyInstance(
                                        ObjectSource.class.getClassLoader(),
                                        new Class<?>[] {ObjectSource.class},
                                        (proxy, method, values) -> {
                                            List<SourceTypes.ObjectRequest> requests =
                                                    new ArrayList<>();
                                            if (method.getName().equals("getKeys"))
                                                for (Object value : (List<?>) values[0])
                                                    requests.add((SourceTypes.ObjectRequest) value);
                                            else if (method.getName().equals("getKey")
                                                    || method.getName().equals("getRange"))
                                                requests.add((SourceTypes.ObjectRequest) values[0]);
                                            for (var request : requests) {
                                                if (request.key()
                                                        .equals(
                                                                new ObjectKey(
                                                                        ObjectKey.ENCODED,
                                                                        mediaEncoded.bytes()))) {
                                                    mediaRequests.incrementAndGet();
                                                    throw new AssertionError(
                                                            "Preparation/warmup must not acquire"
                                                                    + " media");
                                                }
                                                if (request.key().type() == ObjectKey.ARCHIVE_INDEX
                                                        || request.key().type()
                                                                == ObjectKey.FILE_INDEX) {
                                                    int indexRequest =
                                                            indexRequests.incrementAndGet();
                                                    if (firstAccountingPass && indexRequest == 1) {
                                                        require(
                                                                accounting
                                                                                .index
                                                                                .figures(
                                                                                        build
                                                                                                .identity())
                                                                                .join()
                                                                                .incompleteBuilds()
                                                                        == 1,
                                                                "Verified retained membership must"
                                                                        + " be visible before"
                                                                        + " file-index acquisition"
                                                                        + " completes");
                                                    }
                                                    require(
                                                            jobPublished
                                                                    .join()
                                                                    .metadataReady()
                                                                    .isDone(),
                                                            "Early metadata must publish before"
                                                                + " index acquisition for Files");
                                                }
                                            }
                                            try {
                                                return method.invoke(online, values);
                                            } catch (InvocationTargetException failure) {
                                                throw failure.getCause();
                                            }
                                        });
                var opener = new BuildOpener(guarded);
                boolean restoring = round == 1;
                var enumerations = new AtomicInteger();
                var observedBuilds =
                        new com.vibeminer.builds.Builds() {
                            public CompletableFuture<
                                            com.vibeminer.builds.BuildTypes.MetadataOpenResult>
                                    metadata(
                                            com.vibeminer.builds.BuildTypes.BuildRef ref,
                                            com.vibeminer.access.Cancellation token) {
                                return opener.metadata(ref, token);
                            }

                            public CompletableFuture<
                                            com.vibeminer.builds.BuildTypes.BuildOpenResult>
                                    open(
                                            com.vibeminer.builds.BuildTypes.BuildRef ref,
                                            com.vibeminer.access.Cancellation token) {
                                return opener.open(ref, token)
                                        .thenApply(
                                                result -> {
                                                    if (result.opened() == null) return result;
                                                    var wrapped =
                                                            (com.vibeminer.builds.Build)
                                                                    Proxy.newProxyInstance(
                                                                            com.vibeminer.builds
                                                                                    .Build.class
                                                                                    .getClassLoader(),
                                                                            new Class<?>[] {
                                                                                com.vibeminer.builds
                                                                                        .Build.class
                                                                            },
                                                                            (proxy,
                                                                                    method,
                                                                                    args) -> {
                                                                                if (method.getName()
                                                                                        .equals(
                                                                                                "fileDataIds")) {
                                                                                    enumerations
                                                                                            .incrementAndGet();
                                                                                    require(
                                                                                            !restoring,
                                                                                            "Cached"
                                                                                                + " reopen"
                                                                                                + " must"
                                                                                                + " not enumerate"
                                                                                                + " the full"
                                                                                                + " file"
                                                                                                + " catalog");
                                                                                }
                                                                                try {
                                                                                    return method
                                                                                            .invoke(
                                                                                                    result
                                                                                                            .opened(),
                                                                                                    args);
                                                                                } catch (
                                                                                        InvocationTargetException
                                                                                                failure) {
                                                                                    throw failure
                                                                                            .getCause();
                                                                                }
                                                                            });
                                                    return new com.vibeminer.builds.BuildTypes
                                                            .BuildOpenResult(
                                                            result.build(),
                                                            result.state(),
                                                            wrapped,
                                                            result.part(),
                                                            result.code(),
                                                            result.message(),
                                                            result.observedAt());
                                                });
                            }
                        };
                BuildPreparation engine = null;
                try {
                    if (round == 0)
                        store.put(
                                        fixture.objects.entrySet().stream()
                                                .map(
                                                        entry ->
                                                                new StoreTypes.PutObject(
                                                                        entry.getKey(),
                                                                        entry.getValue()))
                                                .toList())
                                .join();
                    var community =
                            new CommunityFileNames(
                                    online,
                                    generated,
                                    token -> CompletableFuture.completedFuture(false));
                    var history = new PreparationHistory(generated);
                    history.load();
                    if (restoring)
                        require(
                                history.summary(build) != null
                                        && history.summary(build).contains("Tables ready"),
                                "Previous preparation assessment must survive store reopen");
                    engine =
                            new BuildPreparation(
                                    (info, region, locale) ->
                                            new NativePreparationWork(
                                                            info,
                                                            region,
                                                            locale,
                                                            observedBuilds,
                                                            guarded,
                                                            generated,
                                                            community,
                                                            local)
                                                    .withHistory(history)
                                                    .withAccounting(accounting));
                    var job = engine.start(build, sourceEvidence, "enUS");
                    jobPublished.complete(job);
                    job.metadataReady().get(20, TimeUnit.SECONDS);
                    var result = job.completion().get(30, TimeUnit.SECONDS);
                    require(
                            result.ready() == BuildPreparation.Ready.TABLES_READY
                                    && result.activity() == BuildPreparation.Activity.IDLE,
                            "Production pipeline must reach Tables-ready: " + result.problem());
                    require(
                            result.files().files() == 2 && !result.files().presenceAssessed(),
                            "Persisted file baseline counts ROOT entries without a full presence"
                                    + " assessment");
                    require(
                            result.tables().stream()
                                    .anyMatch(
                                            entry ->
                                                    entry.name().equals("Item")
                                                            && entry.state()
                                                                    == TableTypes.State.ENCRYPTED),
                            "Known encrypted DB2 must be an explicit prepared table outcome: "
                                    + result.tables());
                    require(
                            indexRequests.get() > 0 && mediaRequests.get() == 0,
                            "Full Files prerequisite indexes are acquired after metadata, media is"
                                    + " not");
                    require(
                            restoring ? enumerations.get() == 0 : enumerations.get() > 0,
                            "Only the initial preparation enumerates file IDs");
                    require(
                            history.summary(build) != null
                                    && history.summary(build).contains("Tables ready"),
                            "Completed preparation must record its historical assessment");
                    var work = (NativePreparationWork) job.work();
                    require(
                            work.filesHandle() != null
                                    && work.tablesHandle() != null
                                    && work.itemsHandle() != null,
                            "Real workspace handles retained for data-only readers");
                    long accountingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while ((work.accounting().usage() == null
                                    || work.accounting().usage().measuredAt().equals(Instant.EPOCH)
                                    || work.accounting().measuring())
                            && System.nanoTime() < accountingDeadline) Thread.sleep(20);
                    var usage = work.accounting();
                    require(
                            usage.usage() != null
                                    && !usage.usage().measuredAt().equals(Instant.EPOCH),
                            "Background original inventory must publish: " + usage.problem());
                    require(
                            usage.usage().publishedObjects() > 0
                                    && usage.usage().original().onlyThisBuild() > 0,
                            "Stored encoded payloads use the immutable EN ledger");
                    require(
                            !usage.usage().derivedComplete(),
                            "Unregistered assets/providers keep derived attribution incomplete");
                    require(mediaRequests.get() == 0, "Accounting must not acquire media");
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
                            "Protected item data read remains typed, not fatal or media-fetching");
                } finally {
                    if (engine != null) engine.closeAsync().get(20, TimeUnit.SECONDS);
                    accounting.closeAsync().join();
                    opener.closeAsync().join();
                    local.closeAsync().join();
                    online.closeAsync().join();
                    generated.closeAsync().join();
                    store.closeAsync().join();
                }
            }
            System.out.println(
                    "PreparationPipelineIntegration: protected-table preparation and offline cached"
                            + " reopen passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
