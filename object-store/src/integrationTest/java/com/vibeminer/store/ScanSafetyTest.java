package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.ScanReport;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class ScanSafetyTest {
    @TempDir Path folder;
    ObjectStore store;

    @BeforeEach
    void open() {
        CrashFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
    }

    @AfterEach
    void close() {
        store.closeAsync().join();
    }

    Path orphan(byte[] id, boolean tornTail) throws Exception {
        byte[] bytes = CrashFixture.target();
        Format.Entry entry =
                new Format.Entry(
                        key(bytes),
                        bytes.length,
                        Format.hash("SHA-256", bytes),
                        0,
                        Origin.CDN,
                        store.index.next,
                        false);
        Path path = folder.resolve("packs/pack-000002.vmp");
        try (FileChannel file =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Format.write(file, 0, Format.pack(2, id, 0, Kind.CDN));
            Format.write(file, 64, entry.header());
            Format.write(file, 160, bytes);
            Format.write(
                    file,
                    160 + bytes.length,
                    Format.le(new byte[4]).putInt((int) Format.crc(bytes)).array());
            if (tornTail) {
                Format.write(file, 64 + entry.size(), new byte[] {1, 2, 3});
            }
            file.force(true);
        }
        return path;
    }

    @ParameterizedTest
    @EnumSource(
            value = MaintenancePoint.class,
            names = {"SCAN_SEALED", "SCAN_CLEARED", "REPLAY_ENTRY", "SCAN_REPLAYED"})
    void canceledScanRetainsOriginalRowsAndCanBeRetried(MaintenancePoint point) throws Exception {
        orphan(store.index.id, false);
        Cancellation canceled = new Cancellation();
        store.maintenanceProbe =
                p -> {
                    if (p == point) {
                        canceled.cancel();
                    }
                };
        assertThrows(CancellationException.class, () -> store.scan(canceled).join());
        store.maintenanceProbe = null;
        assertEquals(3, store.stats().join().objects());
        assertFalse(store.has(key(CrashFixture.target()), NONE).join());
        assertEquals(1, store.readHistory(new History.Query(), NONE).join().size());
        assertNull(store.scrub(true, NONE).join().indexProblem());
        assertEquals(4, store.scan(NONE).join().live());
        assertTrue(store.has(key(CrashFixture.target()), NONE).join());
        assertEquals(4, store.scan(NONE).join().live());
        assertNull(store.scrub(true, NONE).join().indexProblem());
    }

    @Test
    void readersSeeOldRowsUntilOneAtomicScanCommit() throws Exception {
        orphan(store.index.id, false);
        CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
        ReadOnlyStore reader = ObjectStore.openReadOnly(folder, new Options()).join();
        store.maintenanceProbe =
                point -> {
                    if (point != MaintenancePoint.SCAN_REPLAYED) {
                        return;
                    }
                    paused.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Reader did not release scan");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                };
        CompletableFuture<ScanReport> scan = store.scan(NONE);
        try {
            assertTrue(paused.await(10, TimeUnit.SECONDS));
            assertFalse(scan.isDone());
            assertEquals(3, reader.stats().get(5, TimeUnit.SECONDS).objects());
            assertFalse(reader.has(key(CrashFixture.target()), NONE).get(5, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    reader.readHistory(new History.Query(), NONE).get(5, TimeUnit.SECONDS).size());
            release.countDown();
            assertEquals(4, scan.get(10, TimeUnit.SECONDS).live());
            assertEquals(4, reader.stats().join().objects());
            assertTrue(reader.has(key(CrashFixture.target()), NONE).join());
        } finally {
            release.countDown();
            scan.handle((value, error) -> null).get(10, TimeUnit.SECONDS);
            reader.closeAsync().join();
            store.maintenanceProbe = null;
        }
    }

    @Test
    void scanReplaysUnlistedPacksAndReportsTornTailAndInvalidPack() throws Exception {
        Path pack = orphan(store.index.id, true);
        Files.write(folder.resolve("packs/pack-000003.vmp"), new byte[] {1, 2, 3});
        long size = Files.size(pack);
        ScanReport report = store.scan(NONE).join();
        assertEquals(4, report.live());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("Torn header")));
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("Invalid pack header")));
        assertEquals(size, Files.size(pack));
        assertEquals(4, store.scan(NONE).join().live());
    }

    @Test
    void foreignPackRefusesScanBeforeSealingOrReplacingRows() throws Exception {
        byte[] foreign = store.index.id.clone();
        foreign[0] ^= 1;
        Path path = orphan(foreign, false);
        byte[] before = Files.readAllBytes(path);
        long digest = store.index.objectDigest;
        var packs = store.database.query("SELECT state FROM packs WHERE seq=1");
        failure(store.scan(NONE), "OBJECT_STORE_FOREIGN_PACK");
        assertEquals(digest, store.index.objectDigest);
        assertEquals(
                packs.getFirst()[0],
                store.database.query("SELECT state FROM packs WHERE seq=1").getFirst()[0]);
        assertArrayEquals(before, Files.readAllBytes(path));
        assertEquals(3, store.stats().join().objects());
        store.closeAsync().join();
        byte[] index = Files.readAllBytes(folder.resolve("objects.sqlite3"));
        failure(ObjectStore.open(folder), "OBJECT_STORE_FOREIGN_PACK");
        failure(ObjectStore.openReadOnly(folder, new Options()), "OBJECT_STORE_FOREIGN_PACK");
        assertArrayEquals(index, Files.readAllBytes(folder.resolve("objects.sqlite3")));
    }

    @Test
    void newerUnlistedPackIsRejectedBeforeAnyLiveIndexChanges() throws Exception {
        Path path = orphan(store.index.id, false);
        byte[] bytes = Files.readAllBytes(path);
        bytes[40] = 1;
        Format.le(bytes).putInt(60, (int) Format.crc(bytes, 0, 60));
        Files.write(path, bytes);
        long digest = store.index.objectDigest;
        failure(store.scan(NONE), "OBJECT_STORE_NEWER_FORMAT");
        assertEquals(digest, store.index.objectDigest);
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertEquals(3, store.stats().join().objects());
        store.closeAsync().join();
        byte[] index = Files.readAllBytes(folder.resolve("objects.sqlite3"));
        failure(ObjectStore.open(folder), "OBJECT_STORE_NEWER_FORMAT");
        failure(ObjectStore.openReadOnly(folder, new Options()), "OBJECT_STORE_NEWER_FORMAT");
        assertArrayEquals(index, Files.readAllBytes(folder.resolve("objects.sqlite3")));
    }

    @Test
    void canceledBeforeStartDoesNotSealActivePack() {
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        assertThrows(CancellationException.class, () -> store.scan(canceled).join());
        assertEquals(0, store.database.scalar("SELECT state FROM packs WHERE seq=1"));
        assertEquals(3, store.stats().join().objects());
    }

    @Test
    void exceptionDuringReplayRollsBackAndAllowsRetry() {
        store.maintenanceProbe =
                point -> {
                    if (point == MaintenancePoint.REPLAY_ENTRY) {
                        throw new StoreException(
                                "OBJECT_STORE_WRITE_FAILED", "Injected replay failure");
                    }
                };
        failure(store.scan(NONE), "OBJECT_STORE_WRITE_FAILED");
        store.maintenanceProbe = null;
        assertEquals(3, store.stats().join().objects());
        assertEquals(1, store.readHistory(new History.Query(), NONE).join().size());
        assertNull(store.scrub(true, NONE).join().indexProblem());
        assertEquals(3, store.scan(NONE).join().live());
    }

    @Test
    void failedReplacementRetainsOldMainFileAndCanBeRetried() throws Exception {
        store.closeAsync().join();
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
            db.exec("UPDATE store SET row_check=row_check+1");
        }
        byte[] original = Files.readAllBytes(folder.resolve("objects.sqlite3"));
        failure(
                ObjectStore.open(
                        folder,
                        new Options(),
                        point -> {
                            if (point == MaintenancePoint.REBUILD_BEFORE_REPLACE) {
                                throw new StoreException(
                                        "OBJECT_STORE_WRITE_FAILED",
                                        "Injected replacement failure");
                            }
                        }),
                "OBJECT_STORE_WRITE_FAILED");
        assertArrayEquals(original, Files.readAllBytes(folder.resolve("objects.sqlite3")));
        store = ObjectStore.open(folder).join();
        assertEquals(3, store.stats().join().objects());
        assertNull(store.scrub(true, NONE).join().indexProblem());
    }
}
