package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class StorageFailureTest {
    @TempDir Path folder;
    ObjectStore store;

    @BeforeEach
    void open() {
        CrashFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
    }

    @AfterEach
    void close() throws Exception {
        if (store != null) {
            store.io.fault = null;
            store.database.statementProbe = null;
            store.database.afterStatementProbe = null;
            store.closeAsync().handle((v, e) -> null).get(5, TimeUnit.SECONDS);
        }
    }

    CompletableFuture<List<PutResult>> put() {
        return store.put(
                List.of(new PutObject(key(CrashFixture.target()), CrashFixture.target())),
                true,
                NONE);
    }

    static java.util.stream.Stream<Arguments> fileFailures() {
        return java.util.stream.Stream.of(StoreIo.Point.PACK_WRITE, StoreIo.Point.PACK_SYNC)
                .flatMap(
                        p ->
                                java.util.stream.Stream.of(
                                                "Injected I/O error",
                                                "No space left on device",
                                                "There is not enough space on the disk",
                                                "Disk quota exceeded")
                                        .map(message -> Arguments.of(p, message)));
    }

    @ParameterizedTest
    @MethodSource("fileFailures")
    void failedWriteOrSyncCutsTailAndAllowsRetry(StoreIo.Point point, String message)
            throws Exception {
        Path pack = folder.resolve("packs/pack-000001.vmp");
        long committed = Files.size(pack);
        AtomicBoolean once = new AtomicBoolean();
        store.io.fault =
                (p, path) -> {
                    if (p == point && once.compareAndSet(false, true)) {
                        throw new IOException(message);
                    }
                };
        failure(
                put(),
                message.equals("Injected I/O error")
                        ? "OBJECT_STORE_WRITE_FAILED"
                        : "OBJECT_STORE_DISK_FULL");
        assertEquals(committed, Files.size(pack));
        assertFalse(store.has(key(CrashFixture.target()), NONE).join());
        verifyBaseline();
        store.io.fault = null;
        assertEquals(PutOutcome.STORED, put().join().getFirst().outcome());
        assertNull(store.scrub(true, NONE).join().indexProblem());
    }

    @Test
    void partialPayloadWriteFailureRestoresCommittedLength() throws Exception {
        long committed = Files.size(store.packPath(1));
        java.util.concurrent.atomic.AtomicInteger writes =
                new java.util.concurrent.atomic.AtomicInteger();
        store.writeProbe = (point, key) -> {}; // Enable the internal split-write observation path.
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.PACK_WRITE && writes.incrementAndGet() == 2) {
                        throw new IOException("Injected partial payload write error");
                    }
                };
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        assertEquals(committed, Files.size(store.packPath(1)));
        assertFalse(store.has(key(CrashFixture.target()), NONE).join());
        verifyBaseline();
        store.io.fault = null;
        store.writeProbe = null;
        assertEquals(PutOutcome.STORED, put().join().getFirst().outcome());
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 13})
    void failedIndexCommitRollsBackBeforeTruncation(int code) {
        AtomicBoolean once = new AtomicBoolean();
        store.database.statementProbe =
                sql -> {
                    if (sql.equals("COMMIT") && once.compareAndSet(false, true)) {
                        throw new NativeSqlite.Failure(code, "Injected commit error");
                    }
                };
        failure(put(), code == 13 ? "OBJECT_STORE_DISK_FULL" : "OBJECT_STORE_WRITE_FAILED");
        assertFalse(store.database.inTransaction());
        assertFalse(store.has(key(CrashFixture.target()), NONE).join());
        verifyBaseline();
        store.database.statementProbe = null;
        assertEquals(PutOutcome.STORED, put().join().getFirst().outcome());
    }

    @Test
    void errorAfterActualCommitNeverTruncatesCommittedBytes() throws Exception {
        long before = Files.size(folder.resolve("packs/pack-000001.vmp"));
        AtomicBoolean once = new AtomicBoolean();
        store.database.afterStatementProbe =
                sql -> {
                    if (sql.equals("COMMIT") && once.compareAndSet(false, true)) {
                        throw new NativeSqlite.Failure(10, "Injected post-commit error");
                    }
                };
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        assertTrue(Files.size(folder.resolve("packs/pack-000001.vmp")) > before);
        assertArrayEquals(
                CrashFixture.target(), store.read(key(CrashFixture.target()), NONE).join().bytes());
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().get(5, TimeUnit.SECONDS);
        assertDirty();
        reopen();
        assertTrue(store.openReport().fullIndexCheck());
        assertArrayEquals(
                CrashFixture.target(), store.read(key(CrashFixture.target()), NONE).join().bytes());
        verifyBaseline();
    }

    @Test
    void failedSqliteRollbackStopsFurtherWritesWithoutCuttingUncertainTail() throws Exception {
        store.database.statementProbe =
                sql -> {
                    if (sql.equals("COMMIT") || sql.equals("ROLLBACK")) {
                        throw new NativeSqlite.Failure(10, "Injected transaction failure");
                    }
                };
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().get(5, TimeUnit.SECONDS);
        assertDirty();
        reopen();
        assertTrue(store.openReport().fullIndexCheck());
        assertEquals(1, store.openReport().recoveredEntries());
        verifyBaseline();
        assertArrayEquals(
                CrashFixture.target(), store.read(key(CrashFixture.target()), NONE).join().bytes());
    }

    @Test
    void truncateFailureFailsQueuedWritesAndCloseDrainsDeterministically() throws Exception {
        CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.PACK_SYNC) {
                        paused.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Write not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        throw new IOException("No space left on device");
                    }
                    if (point == StoreIo.Point.PACK_TRUNCATE) {
                        throw new IOException("Injected truncate failure");
                    }
                };
        CompletableFuture<List<PutResult>> first = put();
        CompletableFuture<Void> closing = null;
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS));
            byte[] queued = {8, 7, 6};
            var next = store.put(List.of(new PutObject(key(queued), queued)), true, NONE);
            closing = store.closeAsync();
            release.countDown();
            failure(first, "OBJECT_STORE_DISK_FULL");
            failure(next, "OBJECT_STORE_DISK_FULL");
            closing.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertDirty();
        reopen();
        assertTrue(store.openReport().fullIndexCheck());
        verifyBaseline();
        assertFalse(store.has(key(new byte[] {8, 7, 6}), NONE).join());
        assertEquals(1, store.openReport().recoveredEntries());
    }

    @ParameterizedTest
    @EnumSource(
            value = StoreIo.Point.class,
            names = {"PACK_TRUNCATE", "PACK_SYNC"})
    void failedRollbackIoStopsWriterAndReopenPreservesBaseline(StoreIo.Point failedCut)
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger writes =
                new java.util.concurrent.atomic.AtomicInteger();
        store.writeProbe = (point, key) -> {};
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.PACK_WRITE && writes.incrementAndGet() == 2) {
                        throw new IOException("Injected partial write failure");
                    }
                    if (point == failedCut) {
                        throw new IOException("Injected rollback I/O failure");
                    }
                };
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().join();
        assertDirty();
        reopen();
        assertTrue(store.openReport().fullIndexCheck());
        assertFalse(store.has(key(CrashFixture.target()), NONE).join());
        verifyBaseline();
    }

    @Test
    void failedCutAfterStagedRefusalIsStoreFailureRatherThanObjectRefusal() throws Exception {
        EncodedFixture f = EncodedFixture.sample();
        ObjectStaging stage = store.stage(f.key(), f.bytes().length, Origin.CDN, NONE).join();
        stage.append(f.bytes(), NONE).join();
        byte[] damaged = f.bytes().clone();
        damaged[damaged.length - 1] ^= 1;
        Files.write(
                folder.resolve(
                        "staging/" + HexFormat.of().formatHex(f.key().indexBytes()) + ".part"),
                damaged);
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.PACK_TRUNCATE) {
                        throw new IOException("Injected failed refusal rollback");
                    }
                };
        failure(stage.commit(NONE), "OBJECT_STORE_WRITE_FAILED");
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().join();
        assertDirty();
        reopen();
        verifyBaseline();
    }

    @Test
    void partialPackRollFailureLeavesOnlyAnUnlistedEmptyFile() throws Exception {
        store.scan(NONE).join();
        AtomicBoolean once = new AtomicBoolean();
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.DIRECTORY_SYNC && once.compareAndSet(false, true)) {
                        throw new IOException("Injected directory sync failure");
                    }
                };
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        assertEquals(64, Files.size(folder.resolve("packs/pack-000002.vmp")));
        verifyBaseline();
        store.io.fault = null;
        store.closeAsync().join();
        reopen();
        assertFalse(Files.exists(folder.resolve("packs/pack-000002.vmp")));
        verifyBaseline();
    }

    @Test
    void committedPackRollSurvivesModeResetFailure() throws Exception {
        store.scan(NONE).join();
        AtomicBoolean once = new AtomicBoolean();
        store.database.statementProbe =
                sql -> {
                    if (sql.equals("PRAGMA synchronous=NORMAL")
                            && once.compareAndSet(false, true)) {
                        throw new NativeSqlite.Failure(10, "Injected post-roll reset failure");
                    }
                };
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        assertEquals(64, Files.size(folder.resolve("packs/pack-000002.vmp")));
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().join();
        assertDirty();
        reopen();
        verifyBaseline();
        assertEquals(PutOutcome.STORED, put().join().getFirst().outcome());
    }

    @Test
    void busyOpenWorkersCannotPreventCloseFromDraining() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        List<CompletableFuture<ObjectStore>> opens = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Path other = folder.resolve("blocked-open-" + i);
                CrashFixture.prepare(other);
                try (NativeSqlite db = new NativeSqlite(other.resolve("objects.sqlite3"), false)) {
                    db.exec("UPDATE store SET row_check=row_check+1");
                }
            }
            for (int i = 0; i < 2; i++) {
                Path other = folder.resolve("blocked-open-" + i);
                opens.add(
                        ObjectStore.open(
                                other,
                                new Options(),
                                point -> {
                                    if (point != MaintenancePoint.REBUILD_CREATED) {
                                        return;
                                    }
                                    entered.countDown();
                                    try {
                                        if (!release.await(10, TimeUnit.SECONDS)) {
                                            throw new AssertionError("Blocked opener not released");
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(e);
                                    }
                                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            store.closeAsync().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            for (var opened : opens) {
                opened.get(10, TimeUnit.SECONDS).closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
        reopen();
        verifyBaseline();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"COMMIT", "PRAGMA wal_checkpoint(TRUNCATE)", "PRAGMA synchronous=NORMAL"})
    void failedCloseLeavesDirtyMarkerAndReleasesOwnership(String statement) throws Exception {
        AtomicBoolean once = new AtomicBoolean();
        store.database.statementProbe =
                sql -> {
                    if (sql.equals(statement) && once.compareAndSet(false, true)) {
                        throw new NativeSqlite.Failure(10, "Injected close failure");
                    }
                };
        var closed = store.closeAsync();
        failure(closed, "OBJECT_STORE_WRITE_FAILED");
        assertSame(closed, store.closeAsync());
        assertDirty();
        reopen();
        assertTrue(store.openReport().fullIndexCheck());
        verifyBaseline();
    }

    @Test
    void failedStagingWriteRequiresResumeAndKeepsBudgetConsistent() {
        EncodedFixture f = EncodedFixture.sample();
        ObjectStaging stage = store.stage(f.key(), f.bytes().length, Origin.CDN, NONE).join();
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.STAGING_WRITE) {
                        throw new IOException("Injected staging write failure");
                    }
                };
        failure(stage.append(f.bytes(), NONE), "OBJECT_STORE_WRITE_FAILED");
        failure(stage.append(new byte[] {1}, NONE), "OBJECT_STAGING_REJECTED");
        stage.closeAsync().join();
        assertEquals(0, store.stagingBytes);
        store.io.fault = null;
        stage = store.stage(f.key(), f.bytes().length, Origin.CDN, NONE).join();
        stage.append(f.bytes(), NONE).join();
        assertEquals(PutOutcome.STORED, stage.commit(NONE).join().outcome());
        verifyBaseline();
    }

    @Test
    void failedStagingCutRetainsProvenPrefixForNextHandle() {
        EncodedFixture f = EncodedFixture.sample();
        byte[] bad = f.bytes().clone();
        bad[bad.length - 1] ^= 1;
        ObjectStaging stage = store.stage(f.key(), bad.length, Origin.CDN, NONE).join();
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.STAGING_TRUNCATE) {
                        throw new IOException("Injected staging truncate failure");
                    }
                };
        failure(stage.append(bad, NONE), "OBJECT_STORE_WRITE_FAILED");
        stage.closeAsync().join();
        store.io.fault = null;
        stage = store.stage(f.key(), bad.length, Origin.CDN, NONE).join();
        assertEquals(f.firstChunkEnd(), stage.length());
        stage.append(Arrays.copyOfRange(f.bytes(), f.firstChunkEnd(), f.bytes().length), NONE)
                .join();
        assertEquals(PutOutcome.STORED, stage.commit(NONE).join().outcome());
        verifyBaseline();
    }

    @Test
    void failedStagingUnlinkDoesNotLoseCommittedObjectOrBudget() {
        EncodedFixture fixture = EncodedFixture.sample();
        ObjectStaging stage =
                store.stage(fixture.key(), fixture.bytes().length, Origin.CDN, NONE).join();
        stage.append(fixture.bytes(), NONE).join();
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.STAGING_DELETE) {
                        throw new IOException("Injected staged-file deletion failure");
                    }
                };
        failure(stage.commit(NONE), "OBJECT_STORE_WRITE_FAILED");
        assertTrue(store.has(fixture.key(), NONE).join());
        assertEquals(0, store.stagingBytes);
        store.io.fault = null;
        stage = store.stage(fixture.key(), fixture.bytes().length, Origin.CDN, NONE).join();
        assertEquals(PutOutcome.ALREADY_PRESENT, stage.commit(NONE).join().outcome());
        verifyBaseline();
    }

    @Test
    void failedRecoveryTruncationLeavesTailForNextOpen() throws Exception {
        store.closeAsync().join();
        Path pack = folder.resolve("packs/pack-000001.vmp");
        long before = Files.size(pack);
        Files.write(pack, new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        failure(
                ObjectStore.open(
                        folder,
                        new Options(),
                        null,
                        (point, path) -> {
                            if (point == StoreIo.Point.PACK_TRUNCATE) {
                                throw new IOException("Injected recovery truncate error");
                            }
                        }),
                "OBJECT_STORE_WRITE_FAILED");
        assertEquals(before + 3, Files.size(pack));
        assertDirty();
        reopen();
        assertEquals(3, store.openReport().truncatedBytes());
        verifyBaseline();
    }

    @Test
    void failedReplacementDoesNotDeleteOriginalIndex() throws Exception {
        store.closeAsync().join();
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
            db.exec("UPDATE store SET row_check=row_check+1");
        }
        byte[] original = Files.readAllBytes(folder.resolve("objects.sqlite3"));
        failure(
                ObjectStore.open(
                        folder,
                        new Options(),
                        null,
                        (point, path) -> {
                            if (point == StoreIo.Point.INDEX_REPLACE) {
                                throw new IOException("Injected atomic replace failure");
                            }
                        }),
                "OBJECT_STORE_WRITE_FAILED");
        assertArrayEquals(original, Files.readAllBytes(folder.resolve("objects.sqlite3")));
        reopen();
        verifyBaseline();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "WAL"})
    void sqliteFullCanBeExercisedWithoutFillingHostDisk(String journal) {
        try (NativeSqlite db = new NativeSqlite(folder.resolve("bounded-test.sqlite3"), false)) {
            assertEquals(
                    journal.toLowerCase(java.util.Locale.ROOT),
                    db.query("PRAGMA journal_mode=" + journal).getFirst()[0]);
            db.exec("CREATE TABLE data(id INTEGER PRIMARY KEY, value BLOB)");
            db.exec("INSERT INTO data VALUES(1,x'01')");
            long pages = db.scalar("PRAGMA page_count");
            db.exec("PRAGMA max_page_count=" + pages);
            NativeSqlite.Failure full =
                    assertThrows(
                            NativeSqlite.Failure.class,
                            () ->
                                    db.transaction(
                                            false,
                                            () ->
                                                    db.exec(
                                                            "INSERT INTO data VALUES(2,zeroblob(1048576))")));
            assertEquals(13, full.result & 255);
            assertFalse(db.inTransaction());
            assertEquals(1, db.scalar("SELECT count(*) FROM data"));
            db.exec("PRAGMA max_page_count=4096");
            db.transaction(false, () -> db.exec("INSERT INTO data VALUES(2,x'02')"));
            assertEquals(2, db.scalar("SELECT count(*) FROM data"));
        }
    }

    @Test
    void failedStagingCleanupDoesNotHideFatalDiskFullCode() {
        EncodedFixture f = EncodedFixture.sample();
        ObjectStaging staged = store.stage(f.key(), f.bytes().length, Origin.CDN, NONE).join();
        staged.append(f.bytes(), NONE).join();
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.PACK_SYNC) {
                        throw new IOException("No space left on device");
                    }
                    if (point == StoreIo.Point.PACK_TRUNCATE
                            || point == StoreIo.Point.STAGING_DELETE) {
                        throw new IOException("Injected cleanup failure");
                    }
                };
        failure(staged.commit(NONE), "OBJECT_STORE_DISK_FULL");
        failure(put(), "OBJECT_STORE_DISK_FULL");
        store.closeAsync().join();
        assertDirty();
        reopen();
        verifyBaseline();
        assertArrayEquals(f.bytes(), store.read(f.key(), NONE).join().bytes());
    }

    @Test
    void failedScanMetadataReloadStopsFurtherWrites() {
        AtomicBoolean replayed = new AtomicBoolean();
        store.maintenanceProbe =
                point -> {
                    if (point == MaintenancePoint.SCAN_REPLAYED) {
                        replayed.set(true);
                        throw new StoreException(
                                "OBJECT_STORE_WRITE_FAILED", "Injected replay completion failure");
                    }
                };
        store.database.statementProbe =
                sql -> {
                    if (replayed.get() && sql.equals("SELECT * FROM store")) {
                        throw new NativeSqlite.Failure(10, "Injected scan reload failure");
                    }
                };
        failure(store.scan(NONE), "OBJECT_STORE_WRITE_FAILED");
        failure(put(), "OBJECT_STORE_WRITE_FAILED");
        store.closeAsync().join();
        assertDirty();
        reopen();
        verifyBaseline();
    }

    void reopen() {
        store = ObjectStore.open(folder).join();
    }

    void assertDirty() {
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), true)) {
            assertEquals(0, db.scalar("SELECT clean_close FROM store"));
        }
    }

    void verifyBaseline() {
        assertArrayEquals(
                CrashFixture.baseline(),
                store.read(key(CrashFixture.baseline()), NONE).join().bytes());
        assertEquals(1, store.readHistory(new History.Query(), NONE).join().size());
        assertNull(store.scrub(true, NONE).join().indexProblem());
    }
}
