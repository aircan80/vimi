package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes.EnsureOutcome;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.FileFilter;
import com.vibeminer.files.FileTypes.FilePathSource;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStorage;
import com.vibeminer.files.names.CommunityFileNames;
import com.vibeminer.files.names.NamesTypes.FileNamesState;
import com.vibeminer.generated.DiskGeneratedStore;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiskFilesPipelineTest {
    @TempDir Path temporary;

    @Test
    void diskObjectsDecodeAndNamesRebuildOfflineAfterGeneratedWipe() throws Exception {
        byte[] content = "BLP2synthetic decoded file".getBytes(StandardCharsets.UTF_8);
        byte[] csv = "1;Textures/Test.blp\n".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
        byte[] release =
                ("{\"tag_name\":\"v1\",\"published_at\":\"2026-10-31T00:00:00Z\",\"assets\":[{\"name\":\""
                                + SourceUrls.ASSET_NAME
                                + "\",\"size\":"
                                + csv.length
                                + ",\"digest\":\"sha256:"
                                + sha
                                + "\",\"browser_download_url\":\""
                                + SourceUrls.asset("v1")
                                + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
        for (int round = 0; round < 3; round++) {
            ObjectStore objects = ObjectStore.open(temporary.resolve("objects")).join();
            DiskGeneratedStore generated =
                    DiskGeneratedStore.open(temporary.resolve("generated")).join();
            CachingObjectSource access = CachingObjectSource.localOnly(objects, objects);
            try {
                if (round == 0) {
                    objects.appendHistory(
                                    new History.Observation(
                                            6,
                                            "wowlistfile",
                                            "latest",
                                            Instant.EPOCH,
                                            SourceUrls.LATEST_RELEASE.toString(),
                                            release),
                                    com.vibeminer.store.Cancellation.NONE)
                            .join();
                    objects.appendHistory(
                                    new History.Observation(
                                            7,
                                            "v1",
                                            "p0",
                                            Instant.EPOCH,
                                            SourceUrls.asset("v1").toString(),
                                            csv),
                                    com.vibeminer.store.Cancellation.NONE)
                            .join();
                }
                CommunityFileNames names =
                        new CommunityFileNames(
                                access, generated, c -> CompletableFuture.completedFuture(true));
                FileSystemOptions defaults = FileSystemOptions.defaults();
                FileSystemOptions options =
                        new FileSystemOptions(
                                defaults.presenceBatch(),
                                defaults.progressInterval(),
                                defaults.clock(),
                                defaults.maxReadBytes(),
                                defaults.keys(),
                                names);
                FilesScenario fixture =
                        new FilesScenario(false, options, ignored -> access)
                                .add(1, null, content, false, List.of());
                if (round == 0) {
                    var body = fixture.encoded.get(1L);
                    objects.put(
                                    List.of(
                                            new PutObject(
                                                    new ObjectKey(
                                                            ObjectKey.ENCODED, body.key().bytes()),
                                                    body.bytes())))
                            .join();
                }
                assertEquals(FileNamesState.READY, names.status(Cancellation.NONE).join().state());
                try (BuildFiles files =
                        fixture.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files()) {
                    var stat =
                            files.stat(FileRef.forPath("textures\\test.BLP"), Cancellation.NONE)
                                    .join();
                    assertEquals(FileStorage.STORED, stat.file().storage());
                    assertEquals(FilePathSource.LISTFILE, stat.file().pathSource());
                    assertArrayEquals(
                            content, files.get(FileRef.id(1), Cancellation.NONE).join().bytes());
                    assertEquals(
                            FileReadState.COMPLETE,
                            files.readRange(FileRef.id(1), 4, 5, Cancellation.NONE).join().state());
                    try (var stream =
                            files.openRead(FileRef.id(1), Cancellation.NONE).join().stream()) {
                        ByteBuffer out = ByteBuffer.allocate(content.length);
                        while (out.hasRemaining()) {
                            stream.read(out, Cancellation.NONE).join();
                        }
                        assertEquals(
                                -1, stream.read(ByteBuffer.allocate(1), Cancellation.NONE).join());
                        assertArrayEquals(content, out.array());
                    }
                    try (var listing = files.list(FileFilter.ALL, Cancellation.NONE)) {
                        assertEquals(1, listing.next(10, Cancellation.NONE).join().size());
                    }
                    assertEquals(
                            EnsureOutcome.COMPLETED,
                            files.ensure(
                                            EnsureSelection.one(FileRef.id(1)),
                                            EnsurePriority.BACKGROUND,
                                            null,
                                            Cancellation.NONE)
                                    .join()
                                    .outcome());
                }
                if (round == 1) {
                    generated
                            .delete(
                                    ((com.vibeminer.files.names.ListfileStore)
                                                    names.current(Cancellation.NONE).join())
                                            .keys())
                            .join();
                }
            } finally {
                access.closeAsync().join();
                generated.closeAsync().join();
                objects.closeAsync().join();
            }
        }
    }

    @Test
    void historyRejectsNoncanonicalReleaseAndPartUrls() throws Exception {
        byte[] csv = "1;A.blp\n".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
        byte[] release =
                ("{\"tag_name\":\"v1\",\"published_at\":\"2026-10-31T00:00:00Z\",\"assets\":[{\"name\":\""
                                + SourceUrls.ASSET_NAME
                                + "\",\"size\":"
                                + csv.length
                                + ",\"digest\":\"sha256:"
                                + sha
                                + "\",\"browser_download_url\":\""
                                + SourceUrls.asset("v1")
                                + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
        ObjectStore objects = ObjectStore.open(temporary.resolve("observations")).join();
        DiskGeneratedStore generated = DiskGeneratedStore.open(temporary.resolve("index")).join();
        CachingObjectSource access = CachingObjectSource.localOnly(objects, objects);
        try {
            objects.appendHistory(
                            new History.Observation(
                                    6,
                                    "wowlistfile",
                                    "latest",
                                    Instant.EPOCH,
                                    "https://example.invalid/release",
                                    release),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            objects.appendHistory(
                            new History.Observation(
                                    7,
                                    "v1",
                                    "p0",
                                    Instant.EPOCH,
                                    "https://example.invalid/asset",
                                    csv),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            assertEquals(
                    FileNamesState.NOT_READY,
                    new CommunityFileNames(
                                    access, generated, c -> CompletableFuture.completedFuture(true))
                            .status(Cancellation.NONE)
                            .join()
                            .state());
            objects.appendHistory(
                            new History.Observation(
                                    6,
                                    "wowlistfile",
                                    "latest",
                                    Instant.EPOCH.plusSeconds(1),
                                    SourceUrls.LATEST_RELEASE.toString(),
                                    release),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            assertEquals(
                    FileNamesState.NOT_READY,
                    new CommunityFileNames(
                                    access, generated, c -> CompletableFuture.completedFuture(true))
                            .status(Cancellation.NONE)
                            .join()
                            .state());
            // Equal times use history record order, not append order. Choose an earlier bad row.
            History.Observation goodPart =
                    new History.Observation(
                            7,
                            "v1",
                            "p0",
                            Instant.EPOCH.plusSeconds(1),
                            SourceUrls.asset("v1").toString(),
                            csv);
            ObjectKey goodRecord = History.objects(goodPart).get(1).key();
            byte[] earlierBad = null;
            for (int i = 0; i < 1000; i++) {
                byte[] candidate = ("bad" + i).getBytes(StandardCharsets.UTF_8);
                History.Observation badPart =
                        new History.Observation(
                                7,
                                "v1",
                                "p0",
                                Instant.EPOCH.plusSeconds(1),
                                SourceUrls.asset("v1").toString(),
                                candidate);
                if (History.objects(badPart).get(1).key().compareTo(goodRecord) < 0) {
                    earlierBad = candidate;
                    break;
                }
            }
            org.junit.jupiter.api.Assertions.assertNotNull(earlierBad);
            objects.appendHistory(
                            new History.Observation(
                                    7,
                                    "v1",
                                    "p0",
                                    Instant.EPOCH.plusSeconds(1),
                                    SourceUrls.asset("v1").toString(),
                                    earlierBad),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            objects.appendHistory(
                            new History.Observation(
                                    7,
                                    "v1",
                                    "p0",
                                    Instant.EPOCH.plusSeconds(1),
                                    SourceUrls.asset("v1").toString(),
                                    csv),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            CommunityFileNames names =
                    new CommunityFileNames(
                            access, generated, c -> CompletableFuture.completedFuture(true));
            assertEquals(FileNamesState.READY, names.status(Cancellation.NONE).join().state());
            assertEquals(
                    "A.blp",
                    names.current(Cancellation.NONE).join().nameOf(1, Cancellation.NONE).join());
        } finally {
            access.closeAsync().join();
            generated.closeAsync().join();
            objects.closeAsync().join();
        }
    }

    @Test
    void repeatedMalformedDeltaDocumentsDoNotHideOlderValidCopy() throws Exception {
        byte[] base = "1;A.blp\n".getBytes(StandardCharsets.UTF_8),
                target = "1;B.blp\n2;C.m2\n".getBytes(StandardCharsets.UTF_8);
        String baseSha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base));
        String targetSha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(target));
        byte[] delta =
                com.vibeminer.files.names.ListfileDelta.encode(
                        new java.io.ByteArrayInputStream(base),
                        new java.io.ByteArrayInputStream(target),
                        baseSha,
                        targetSha,
                        target.length,
                        1 << 20,
                        Cancellation.NONE);
        ObjectStore objects = ObjectStore.open(temporary.resolve("delta-objects")).join();
        DiskGeneratedStore generated =
                DiskGeneratedStore.open(temporary.resolve("delta-index")).join();
        CachingObjectSource access = CachingObjectSource.localOnly(objects, objects);
        try {
            for (String tag : List.of("v1", "v2")) {
                String sha = tag.equals("v1") ? baseSha : targetSha;
                int size = tag.equals("v1") ? base.length : target.length;
                byte[] release =
                        ("{\"tag_name\":\""
                                        + tag
                                        + "\",\"published_at\":\"2026-10-31T00:00:00Z\",\"assets\":[{\"name\":\""
                                        + SourceUrls.ASSET_NAME
                                        + "\",\"size\":"
                                        + size
                                        + ",\"digest\":\"sha256:"
                                        + sha
                                        + "\",\"browser_download_url\":\""
                                        + SourceUrls.asset(tag)
                                        + "\"}]}")
                                .getBytes(StandardCharsets.UTF_8);
                objects.appendHistory(
                                new History.Observation(
                                        6,
                                        "wowlistfile",
                                        "latest",
                                        Instant.EPOCH.plusSeconds(tag.equals("v1") ? 0 : 1),
                                        SourceUrls.LATEST_RELEASE.toString(),
                                        release),
                                com.vibeminer.store.Cancellation.NONE)
                        .join();
            }
            objects.appendHistory(
                            new History.Observation(
                                    7,
                                    "v1",
                                    "p0",
                                    Instant.EPOCH,
                                    SourceUrls.asset("v1").toString(),
                                    base),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            objects.appendHistory(
                            new History.Observation(
                                    8,
                                    "v2",
                                    "delta",
                                    Instant.EPOCH.plusSeconds(1),
                                    "https://example.invalid/delta",
                                    delta),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            assertEquals(
                    "v1",
                    new CommunityFileNames(
                                    access, generated, c -> CompletableFuture.completedFuture(true))
                            .status(Cancellation.NONE)
                            .join()
                            .tag());
            objects.appendHistory(
                            new History.Observation(
                                    8,
                                    "v2",
                                    "delta",
                                    Instant.EPOCH.plusSeconds(1),
                                    SourceUrls.asset("v2").toString(),
                                    delta),
                            com.vibeminer.store.Cancellation.NONE)
                    .join();
            // More than eight distinct candidates at equal time: C# keeps earlier cursor rows.
            ObjectKey validRecord =
                    History.objects(
                                    new History.Observation(
                                            8,
                                            "v2",
                                            "delta",
                                            Instant.EPOCH.plusSeconds(1),
                                            SourceUrls.asset("v2").toString(),
                                            delta))
                            .get(1)
                            .key();
            int inserted = 0;
            for (int i = 0; inserted < 9 && i < 1000; i++) {
                History.Observation malformed =
                        new History.Observation(
                                8,
                                "v2",
                                "delta",
                                Instant.EPOCH.plusSeconds(1),
                                SourceUrls.asset("v2").toString(),
                                ("invalid" + i).getBytes(StandardCharsets.UTF_8));
                if (History.objects(malformed).get(1).key().compareTo(validRecord) > 0) {
                    objects.appendHistory(malformed, com.vibeminer.store.Cancellation.NONE).join();
                    inserted++;
                }
            }
            assertEquals(9, inserted);
            for (int n = 0; n < 12; n++) {
                objects.appendHistory(
                                new History.Observation(
                                        8,
                                        "v2",
                                        "delta",
                                        Instant.EPOCH.plusSeconds(n + 2),
                                        SourceUrls.asset("v2").toString(),
                                        new byte[] {1}),
                                com.vibeminer.store.Cancellation.NONE)
                        .join();
            }
            CommunityFileNames names =
                    new CommunityFileNames(
                            access, generated, c -> CompletableFuture.completedFuture(true));
            assertEquals("v2", names.status(Cancellation.NONE).join().tag());
            assertEquals(
                    "B.blp",
                    names.current(Cancellation.NONE).join().nameOf(1, Cancellation.NONE).join());
            assertEquals(
                    "C.m2",
                    names.current(Cancellation.NONE).join().nameOf(2, Cancellation.NONE).join());
        } finally {
            access.closeAsync().join();
            generated.closeAsync().join();
            objects.closeAsync().join();
        }
    }
}
