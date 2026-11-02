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
}
