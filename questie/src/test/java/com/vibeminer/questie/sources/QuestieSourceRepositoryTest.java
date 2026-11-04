package com.vibeminer.questie.sources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.vibeminer.activity.NetworkActivity;
import com.vibeminer.generated.Cancellation;
import com.vibeminer.questie.QuestieTypes.Flavor;
import com.vibeminer.questie.QuestieTypes.SourceSnapshot;
import com.vibeminer.questie.internal.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuestieSourceRepositoryTest {
    @TempDir Path directory;
    private HttpServer server;
    private URI base;
    private volatile byte[] archive;
    private volatile byte[] release;
    private volatile byte[] manifest;
    private volatile String etag;
    private volatile int status;
    private volatile String stalledPath;
    private final java.util.concurrent.CountDownLatch bodyStarted =
            new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch releaseBody =
            new java.util.concurrent.CountDownLatch(1);
    private final AtomicInteger downloaded = new AtomicInteger();
    private final AtomicInteger conditional = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        directory = directory.toRealPath();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals(stalledPath)) {
                        exchange.sendResponseHeaders(200, 1000000);
                        try (var output = exchange.getResponseBody()) {
                            output.write(1);
                            output.flush();
                            bodyStarted.countDown();
                            try {
                                releaseBody.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                            }
                        } catch (IOException disconnected) {
                            /* Expected client cancellation. */
                        }
                        return;
                    }
                    byte[] body;
                    int code = 200;
                    if (path.startsWith("/releases/")) {
                        if (status != 0) {
                            code = status;
                            body = new byte[0];
                            exchange.getResponseHeaders().set("Retry-After", "60");
                        } else if (etag.equals(
                                exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                            code = 304;
                            conditional.incrementAndGet();
                            body = new byte[0];
                        } else {
                            body = release;
                            exchange.getResponseHeaders().set("ETag", etag);
                        }
                    } else if (path.equals("/release.json")) {
                        body = manifest;
                    } else if (path.equals("/QuestieDB-Forever.zip")) {
                        downloaded.incrementAndGet();
                        body = archive;
                    } else {
                        code = 404;
                        body = new byte[0];
                    }
                    exchange.sendResponseHeaders(
                            code, code == 304 || body.length == 0 ? -1 : body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        version("v1", "a".repeat(40), false);
    }

    @AfterEach
    void stop() {
        releaseBody.countDown();
        server.stop(0);
    }

    private QuestieSourceRepository open() {
        return QuestieSourceRepository.open(
                        directory.resolve("questie"), base, true, Cancellation.NONE)
                .join();
    }

    private void version(String tag, String commit, boolean traversal) throws Exception {
        archive = zip(traversal);
        etag = "\"" + tag + "\"";
        release =
                Json.bytes(
                        Map.of(
                                "tag_name",
                                tag,
                                "published_at",
                                "2026-11-02T12:00:00Z",
                                "draft",
                                false,
                                "prerelease",
                                false,
                                "assets",
                                List.of(
                                        Map.of(
                                                "name",
                                                "release.json",
                                                "browser_download_url",
                                                base.resolve("release.json").toString(),
                                                "size",
                                                100),
                                        Map.of(
                                                "name",
                                                "QuestieDB-Forever.zip",
                                                "browser_download_url",
                                                base.resolve("QuestieDB-Forever.zip").toString(),
                                                "size",
                                                archive.length))));
        manifest =
                Json.bytes(
                        Map.of(
                                "releases",
                                List.of(),
                                "questiedb",
                                Map.of(
                                        "repository",
                                        "https://github.com/Questie/QuestieDB",
                                        "producerCommit",
                                        commit,
                                        "version",
                                        tag,
                                        "artifacts",
                                        List.of(
                                                Map.of(
                                                        "flavor",
                                                        "Forever",
                                                        "file",
                                                        "QuestieDB-Forever.zip",
                                                        "sha256",
                                                        sha(archive),
                                                        "bytes",
                                                        archive.length)))));
    }

    private static byte[] zip(boolean traversal) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name :
                    List.of(
                            "QuestieDB/QuestieDB_Forever.toc",
                            "QuestieDB/LICENSE.md",
                            "QuestieDB/gpl-3.0.md",
                            traversal ? "QuestieDB/../../escape.txt" : "QuestieDB/Data.lua")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(
                        ("authored fixture " + name)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void activityBalancesRedirectHopWithoutCountingUnreadBody() throws Exception {
        server.createContext(
                "/redirect/releases/latest",
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("Location", base.resolve("releases/latest").toString());
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        var meter = new NetworkActivity();
        try (var repo =
                QuestieSourceRepository.open(
                                directory.resolve("questie"),
                                base.resolve("redirect/"),
                                true,
                                Cancellation.NONE,
                                meter)
                        .join()) {
            repo.refresh(Cancellation.NONE).join();
            assertEquals(release.length, meter.snapshot().receivedBytes());
            assertEquals(0, meter.snapshot().active());
        }
    }

    @Test
    void activityCoversHeaderWaitAndCancellationWithoutSyntheticBytes() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var unblock = new java.util.concurrent.CountDownLatch(1);
        server.createContext(
                "/held/releases/latest",
                exchange -> {
                    entered.countDown();
                    try {
                        unblock.await(3, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        exchange.sendResponseHeaders(200, -1);
                    } catch (IOException disconnected) {
                        /* canceled client */
                    } finally {
                        exchange.close();
                    }
                });
        var meter = new NetworkActivity();
        try (var repo =
                QuestieSourceRepository.open(
                                directory.resolve("questie"),
                                base.resolve("held/"),
                                true,
                                Cancellation.NONE,
                                meter)
                        .join()) {
            var token = new Cancellation();
            var operation = repo.refresh(token);
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, meter.snapshot().waitingResponse());
            assertEquals(0, meter.snapshot().receivedBytes());
            token.cancel();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> operation.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, meter.snapshot().active());
        } finally {
            unblock.countDown();
        }
    }

    @Test
    void activityCountsMetadataArchiveAndNotCacheOrExpandedFiles() throws Exception {
        var meter = new NetworkActivity();
        try (var repo =
                QuestieSourceRepository.open(
                                directory.resolve("questie"), base, true, Cancellation.NONE, meter)
                        .join()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            assertEquals(release.length, meter.snapshot().receivedBytes());
            assertEquals(0, meter.snapshot().active());
            repo.refresh(Cancellation.NONE).join(); // 304 body has no bytes
            assertEquals(release.length, meter.snapshot().receivedBytes());
            repo.acquire(latest, null, Cancellation.NONE).join();
            long expected = (long) release.length + manifest.length + archive.length;
            assertEquals(expected, meter.snapshot().receivedBytes());
            assertEquals(0, meter.snapshot().active());
            var snapshot = repo.snapshots(Cancellation.NONE).join().getFirst();
            assertTrue(repo.verify(snapshot, Cancellation.NONE).join());
            assertEquals(expected, meter.snapshot().receivedBytes());
            repo.acquire(latest, null, Cancellation.NONE).join(); // rechecks upstream manifest
            assertEquals(expected + manifest.length, meter.snapshot().receivedBytes());
            assertEquals(1, downloaded.get());
        }
        assertEquals(0, meter.snapshot().active());
    }

    @Test
    void activityKeepsActualPartialBytesAfterCancelAndDrain() throws Exception {
        var meter = new NetworkActivity();
        try (var repo =
                QuestieSourceRepository.open(
                                directory.resolve("questie"), base, true, Cancellation.NONE, meter)
                        .join()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            stalledPath = "/release.json";
            var token = new Cancellation();
            var operation = repo.acquire(latest, null, token);
            assertTrue(bodyStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (meter.snapshot().receivedBytes() == release.length && System.nanoTime() < end) {
                Thread.sleep(5);
            }
            assertEquals((long) release.length + 1, meter.snapshot().receivedBytes());
            assertEquals(1, meter.snapshot().active());
            token.cancel();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> operation.get(2, java.util.concurrent.TimeUnit.SECONDS));
            repo.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, meter.snapshot().active());
            assertEquals((long) release.length + 1, meter.snapshot().receivedBytes());
        }
    }

    @Test
    void unchangedReleaseUsesEtagAndKeepsOriginalBytesOffline() throws Exception {
        SourceSnapshot snapshot;
        try (var repo = open()) {
            var first = repo.refresh(Cancellation.NONE).join();
            assertTrue(first.changed());
            var same = repo.refresh(Cancellation.NONE).join();
            assertFalse(same.changed());
            assertEquals(1, conditional.get());
            snapshot = repo.acquire(first.latest(), null, Cancellation.NONE).join();
            assertArrayEquals(archive, Files.readAllBytes(snapshot.archive()));
            assertArrayEquals(
                    release, Files.readAllBytes(snapshot.directory().resolve("release-api.json")));
            assertArrayEquals(
                    manifest, Files.readAllBytes(snapshot.directory().resolve("release.json")));
            assertTrue(Files.exists(snapshot.directory().resolve("notices/LICENSE.md")));
            assertTrue(repo.verify(snapshot, Cancellation.NONE).join());
            repo.acquire(first.latest(), Flavor.FOREVER, null, Cancellation.NONE).join();
            assertEquals(1, downloaded.get());
        }
        try (var repo = open()) {
            assertEquals(List.of(snapshot), repo.snapshots(Cancellation.NONE).join());
            assertEquals(1, repo.releases(Cancellation.NONE).join().size());
            status = 429;
            var offline = repo.refresh(Cancellation.NONE).join();
            assertTrue(offline.offline());
            assertEquals("v1", offline.latest().tag());
            assertTrue(offline.message().contains("Retry-After"));
        }
    }

    @Test
    void newVersionRetainsBothImmutableSnapshots() throws Exception {
        try (var repo = open()) {
            var first =
                    repo.acquire(
                                    repo.refresh(Cancellation.NONE).join().latest(),
                                    null,
                                    Cancellation.NONE)
                            .join();
            version("v2", "b".repeat(40), false);
            var check = repo.refresh(Cancellation.NONE).join();
            assertTrue(check.changed());
            assertEquals("v1", check.previousTag());
            var second = repo.acquire(check.latest(), null, Cancellation.NONE).join();
            assertFalse(first.id().equals(second.id()));
            assertEquals(2, repo.snapshots(Cancellation.NONE).join().size());
            assertTrue(Files.exists(first.archive()));
            assertEquals(2, repo.releases(Cancellation.NONE).join().size());
        }
    }

    @Test
    void digestMismatchNeverPublishesAndCleansOnlyOwnedStaging() {
        try (var repo = open()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            archive[archive.length - 1] ^= 1;
            assertThrows(
                    CompletionException.class,
                    () -> repo.acquire(latest, null, Cancellation.NONE).join());
            assertTrue(repo.snapshots(Cancellation.NONE).join().isEmpty());
            assertEquals(0, childCount(directory.resolve("questie/staging-v1")));
        }
    }

    @Test
    void cancellationDoesNotPublishButRepositoryRemainsUsable() {
        try (var repo = open()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            var token = new Cancellation();
            assertThrows(
                    CompletionException.class,
                    () -> repo.acquire(latest, value -> token.cancel(), token).join());
            assertTrue(repo.snapshots(Cancellation.NONE).join().isEmpty());
            assertEquals(0, childCount(directory.resolve("questie/staging-v1")));
            assertTrue(
                    repo.verify(
                                    repo.acquire(latest, null, Cancellation.NONE).join(),
                                    Cancellation.NONE)
                            .join());
        }
    }

    @Test
    void traversalArchiveIsRejectedWithoutWritingOutsideRepository() throws Exception {
        version("v2", "b".repeat(40), true);
        try (var repo = open()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            assertThrows(
                    CompletionException.class,
                    () -> repo.acquire(latest, null, Cancellation.NONE).join());
            assertTrue(repo.snapshots(Cancellation.NONE).join().isEmpty());
            assertFalse(Files.exists(directory.resolve("escape.txt")));
        }
    }

    @Test
    void foreignDirectoryIsRefusedAndExistingFilesArePreserved() throws Exception {
        Files.createDirectory(directory.resolve("questie"));
        Path existing = directory.resolve("questie/important.txt");
        Files.writeString(existing, "keep me");
        assertThrows(CompletionException.class, this::open);
        assertEquals("keep me", Files.readString(existing));
    }

    @Test
    void modifiedOriginalIsDetectedAndNotOverwritten() throws Exception {
        try (var repo = open()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            var snapshot = repo.acquire(latest, null, Cancellation.NONE).join();
            byte[] damaged = Files.readAllBytes(snapshot.archive());
            damaged[0] ^= 1;
            Files.write(snapshot.archive(), damaged);
            assertFalse(repo.verify(snapshot, Cancellation.NONE).join());
            assertThrows(
                    CompletionException.class,
                    () -> repo.acquire(latest, null, Cancellation.NONE).join());
            assertArrayEquals(damaged, Files.readAllBytes(snapshot.archive()));
        }
    }

    @Test
    void stalledMetadataBodyCanBeCanceledAndCloseDrains() throws Exception {
        var repo = open();
        try {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            stalledPath = "/release.json";
            var token = new Cancellation();
            var operation = repo.acquire(latest, null, token);
            assertTrue(bodyStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            token.cancel();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> operation.get(2, java.util.concurrent.TimeUnit.SECONDS));
            repo.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            repo.close();
        }
    }

    @Test
    void stalledArchiveBodyHasFiniteDeadline() throws Exception {
        try (var repo = open()) {
            var latest = repo.refresh(Cancellation.NONE).join().latest();
            stalledPath = "/QuestieDB-Forever.zip";
            var operation = repo.acquire(latest, null, Cancellation.NONE);
            assertTrue(bodyStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> operation.get(4, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("deadline"));
            assertTrue(repo.snapshots(Cancellation.NONE).join().isEmpty());
        }
    }

    @Test
    void rewrittenLocalIdentityCannotOverrideRetainedUpstreamProducer() throws Exception {
        try (var repo = open()) {
            var snapshot =
                    repo.acquire(
                                    repo.refresh(Cancellation.NONE).join().latest(),
                                    null,
                                    Cancellation.NONE)
                            .join();
            Path manifestFile = snapshot.directory().resolve("source-manifest.json");
            var local = Json.object(Json.parse(Files.readAllBytes(manifestFile), 1000000));
            String forgedCommit = "c".repeat(40);
            String forgedId =
                    sha(
                            ("https://github.com/Questie/QuestieDB\n"
                                            + forgedCommit
                                            + "\nForever\n"
                                            + snapshot.sha256())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            local.put("producerCommit", forgedCommit);
            local.put("id", forgedId);
            Files.write(manifestFile, Json.bytes(local));
            Path forgedDirectory = snapshot.directory().resolveSibling(forgedId);
            Files.move(snapshot.directory(), forgedDirectory);
            var forged =
                    new SourceSnapshot(
                            forgedId,
                            snapshot.releaseTag(),
                            forgedCommit,
                            Flavor.FOREVER,
                            snapshot.sha256(),
                            snapshot.bytes(),
                            snapshot.publishedAt(),
                            forgedDirectory);
            assertThrows(
                    CompletionException.class, () -> repo.verify(forged, Cancellation.NONE).join());
        }
    }

    @Test
    void staleEtagCannotValidateDifferentCachedResponseAfterInterruptedPublication()
            throws Exception {
        try (var repo = open()) {
            repo.refresh(Cancellation.NONE).join();
            version("v2", "b".repeat(40), false);
            Files.write(directory.resolve("questie/latest.json"), release);
            version("v1", "a".repeat(40), false);
            var checked = repo.refresh(Cancellation.NONE).join();
            assertEquals("v1", checked.latest().tag());
            assertEquals(0, conditional.get());
        }
    }

    @Test
    void extractedNoticeTamperingIsDetected() throws Exception {
        try (var repo = open()) {
            var snapshot =
                    repo.acquire(
                                    repo.refresh(Cancellation.NONE).join().latest(),
                                    null,
                                    Cancellation.NONE)
                            .join();
            Files.writeString(snapshot.directory().resolve("notices/LICENSE.md"), "altered");
            assertFalse(repo.verify(snapshot, Cancellation.NONE).join());
        }
    }

    @Test
    void crashLeftStagingIsNeverListedOrDeletedAsAnAcquisitionSideEffect() throws Exception {
        try (var repo = open()) {
            Path left = directory.resolve("questie/staging-v1/acquire-crash");
            Files.createDirectory(left);
            Files.writeString(left.resolve("foreign.txt"), "retain");
            assertTrue(repo.snapshots(Cancellation.NONE).join().isEmpty());
            repo.acquire(repo.refresh(Cancellation.NONE).join().latest(), null, Cancellation.NONE)
                    .join();
            assertEquals("retain", Files.readString(left.resolve("foreign.txt")));
            assertEquals(1, repo.snapshots(Cancellation.NONE).join().size());
        }
    }

    @Test
    void linkedSourceRootIsRefusedWithoutTouchingTarget() throws Exception {
        Path target = directory.resolve("target");
        Files.createDirectory(target);
        Files.createSymbolicLink(directory.resolve("questie"), target);
        assertThrows(CompletionException.class, this::open);
        assertEquals(0, childCount(target));
    }

    @Test
    void downloadCountersAndCosmeticReleaseChangesDoNotReportANewVersion() {
        try (var repo = open()) {
            repo.refresh(Cancellation.NONE).join();
            var changed = Json.object(Json.parse(release, 1000000));
            changed.put("name", "A different display title");
            changed.put("body", "Edited release description");
            changed.put("reactions", Map.of("total_count", 12));
            var assets = Json.array(changed.get("assets"));
            for (Object asset : assets) {
                Json.object(asset).put("download_count", 42);
            }
            java.util.Collections.reverse(assets);
            release = Json.bytes(changed);
            etag = "\"new-response-with-counts\"";
            var checked = repo.refresh(Cancellation.NONE).join();
            assertFalse(checked.changed());
            assertFalse(checked.offline());
            assertArrayEquals(release, checked.latest().metadata());
            assertEquals(1, repo.releases(Cancellation.NONE).join().size());
        }
    }

    @Test
    void sameTagAssetReplacementReportsNewRevision() {
        try (var repo = open()) {
            repo.refresh(Cancellation.NONE).join();
            var changed = Json.object(Json.parse(release, 1000000));
            var asset = Json.object(Json.array(changed.get("assets")).getFirst());
            asset.put("id", 12345);
            asset.put("updated_at", "2026-11-02T13:00:00Z");
            asset.put("digest", "sha256:" + "b".repeat(64));
            release = Json.bytes(changed);
            etag = "\"replacement-same-tag\"";
            var checked = repo.refresh(Cancellation.NONE).join();
            assertTrue(checked.changed());
            assertEquals("v1", checked.previousTag());
            assertEquals("v1", checked.latest().tag());
        }
    }

    @Test
    void closeIsIdempotentRejectsNewWorkAndReleasesOwnedDirectory() throws Exception {
        var repo = open();
        repo.refresh(Cancellation.NONE).join();
        repo.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
        repo.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertThrows(CompletionException.class, () -> repo.refresh(Cancellation.NONE).join());
        assertThrows(CompletionException.class, () -> repo.snapshots(Cancellation.NONE).join());
        try (var reopened = open()) {
            assertEquals("v1", reopened.releases(Cancellation.NONE).join().getFirst().tag());
        }
    }

    private static long childCount(Path path) {
        try (var children = Files.list(path)) {
            return children.count();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
