package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(60)
final class MaintenanceResourceTest {
    @TempDir Path folder;
    ObjectStore store;

    @AfterEach
    void close() {
        if (store != null) {
            store.maintenanceResourcesProbe = null;
            store.database.statementProbe = null;
            store.closeAsync().join();
        }
    }

    private PutObject object(int value) {
        byte[] bytes = new byte[4];
        Format.le(bytes).putInt(value);
        return new PutObject(key(bytes), bytes);
    }

    private void open(long packBytes, int handles) {
        store =
                ObjectStore.open(
                                folder,
                                new Options(
                                        StoreTypes.Kind.CDN,
                                        packBytes,
                                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                                        handles))
                        .join();
    }

    @Test
    void hundredsOfPacksReplayWithTwoHandlesAndPagedDiskMetadata() {
        open(128, 2);
        List<PutObject> objects = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            objects.add(object(i));
        }
        store.put(objects).join();
        AtomicInteger maxHandles = new AtomicInteger();
        AtomicInteger maxRows = new AtomicInteger();
        store.maintenanceResourcesProbe =
                resources -> {
                    if (resources.operation().equals("replay")) {
                        maxHandles.accumulateAndGet(resources.openPackHandles(), Math::max);
                        maxRows.accumulateAndGet(resources.javaRows(), Math::max);
                        assertTrue(resources.openPackHandles() <= 2);
                        assertTrue(resources.javaRows() <= MaintenanceSpool.PAGE_ROWS);
                    }
                };
        var report = store.scan(Cancellation.NONE).join();
        assertEquals(300, report.packs());
        assertEquals(300, report.live());
        assertEquals(2, maxHandles.get());
        assertEquals(256, maxRows.get());
        assertEquals(1, store.database.scalar("PRAGMA temp_store"));
        assertEquals(-2048, store.database.scalar("PRAGMA temp.cache_size"));
        assertEquals(
                0,
                store.database.scalar(
                        "SELECT count(*) FROM sqlite_temp_master WHERE name='replay_heads'"));
        assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
    }

    @Test
    void compactionMovesMoreThanOneMetadataPageWithoutRetainingThePackRows() {
        open(2L << 30, 2);
        List<PutObject> objects = new ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            objects.add(object(i));
        }
        store.put(objects).join();
        List<ObjectKey> deleted = new ArrayList<>();
        for (int i = 0; i < objects.size(); i += 2) {
            deleted.add(objects.get(i).key());
        }
        store.delete(deleted).join();
        store.scan(Cancellation.NONE).join();
        AtomicInteger maxRows = new AtomicInteger();
        AtomicInteger maxHandles = new AtomicInteger();
        store.maintenanceResourcesProbe =
                resources -> {
                    if (resources.operation().equals("compaction")) {
                        maxRows.accumulateAndGet(resources.javaRows(), Math::max);
                        maxHandles.accumulateAndGet(resources.openPackHandles(), Math::max);
                        assertTrue(resources.javaRows() <= MaintenanceSpool.PAGE_ROWS);
                        assertTrue(resources.openPackHandles() <= 2);
                    }
                };
        var report = store.compact(0, 1, Cancellation.NONE).join();
        assertEquals(512, report.moved());
        assertEquals(1, report.packsCompacted());
        assertEquals(256, maxRows.get());
        assertEquals(2, maxHandles.get());
        assertEquals(512, store.stats().join().objects());
        assertEquals(
                0,
                store.database.scalar(
                        "SELECT count(*) FROM sqlite_temp_master WHERE name IN ('compaction_moves','compaction_candidates')"));
        assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
        store.scan(Cancellation.NONE).join();
        assertFalse(store.has(objects.getFirst().key()).join());
        assertEquals(ReadState.PRESENT, store.read(objects.get(1).key()).join().state());
    }

    @Test
    void replaySpoolFailureRollsBackIndexAndCanRetry() {
        open(128, 2);
        store.put(List.of(object(1), object(2))).join();
        AtomicBoolean once = new AtomicBoolean(true);
        store.database.statementProbe =
                sql -> {
                    if (sql.startsWith("UPDATE temp.replay_heads") && once.getAndSet(false)) {
                        throw new NativeSqlite.Failure(13, "Injected replay spool full");
                    }
                };
        failure(store.scan(Cancellation.NONE), "OBJECT_STORE_DISK_FULL");
        store.database.statementProbe = null;
        assertEquals(ReadState.PRESENT, store.read(object(1).key()).join().state());
        assertEquals(2, store.scan(Cancellation.NONE).join().live());
    }

    @Test
    void moveSpoolFailureKeepsSourceAndAllowsRetry() {
        CompactionFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
        AtomicBoolean once = new AtomicBoolean(true);
        store.database.statementProbe =
                sql -> {
                    if (sql.startsWith("INSERT INTO temp.compaction_moves")
                            && once.getAndSet(false)) {
                        throw new NativeSqlite.Failure(13, "Injected move spool full");
                    }
                };
        failure(store.compact(0, 1, Cancellation.NONE), "OBJECT_STORE_DISK_FULL");
        store.database.statementProbe = null;
        assertTrue(Files.exists(store.packPath(2)));
        assertEquals(ReadState.PRESENT, store.read(key(CompactionFixture.kept())).join().state());
        assertEquals(1, store.compact(0, 1, Cancellation.NONE).join().packsCompacted());
    }

    @Test
    void packManifestIsSortedLazyAndIgnoresUnrelatedNames() throws Exception {
        Path packs = folder.resolve("packs");
        Files.createDirectories(packs);
        for (String name :
                List.of(
                        "pack-000009.vmp",
                        "pack-000001.vmp",
                        "pack-999999.vmp",
                        "pack-000000.vmp",
                        "pack-1000000.vmp",
                        "other.bin")) {
            Files.createFile(packs.resolve(name));
        }
        PackFiles files = PackFiles.discover(packs);
        assertEquals(3, files.size());
        assertEquals(List.of(1, 9, 999999), new ArrayList<>(files.keySet()));
        assertEquals(999999, files.lastKey());
        assertEquals(packs.resolve("pack-000009.vmp"), files.get(9));
        assertFalse(files.containsKey(0));
        assertFalse(files.containsKey(1000000));
    }

    @Test
    void diagnosticLimitIndicatesOmissionsWithoutSkippingValidReplay() throws Exception {
        open(2L << 30, 2);
        store.put(List.of(object(1))).join();
        for (int i = 2; i < 112; i++) {
            Files.write(store.packPath(i), new byte[64]);
        }
        var report = store.scan(Cancellation.NONE).join();
        assertEquals(1, report.live());
        assertEquals(100, report.problems().size());
        assertEquals("Additional problems omitted", report.problems().getLast());
        assertEquals(ReadState.PRESENT, store.read(object(1).key()).join().state());
    }

    @Test
    void coldRebuildAndOpenUsePagedPackRowsAcrossManifestPages() throws Exception {
        open(128, 2);
        List<PutObject> objects = new ArrayList<>();
        for (int i = 0; i < 270; i++) {
            objects.add(object(i));
        }
        store.put(objects).join();
        store.closeAsync().join();
        Files.delete(folder.resolve("objects.sqlite3"));
        open(128, 2);
        assertTrue(store.openReport().indexRebuilt());
        assertEquals(270, store.stats().join().objects());
        assertEquals(ReadState.PRESENT, store.read(objects.getLast().key()).join().state());
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, 1_000_000})
    void malformedPackNumbersCannotEscapeTheFixedManifestBound(long number) {
        open(2L << 30, 2);
        store.put(List.of(object(1))).join();
        store.write(
                        () -> {
                            store.database.exec(
                                    "INSERT INTO packs(seq,state,committed_length,live_bytes,row_check) VALUES(?,?,?,?,?)",
                                    number,
                                    1,
                                    64,
                                    0,
                                    Format.packCheck(number, 1, 64));
                            return null;
                        })
                .join();
        store.closeAsync().join();
        open(2L << 30, 2);
        assertTrue(store.openReport().indexRebuilt());
        assertEquals(ReadState.PRESENT, store.read(object(1).key()).join().state());
    }

    @Test
    void pagedReadOnlyValidationUsesOneSnapshotWhileTheWriterRolls() throws Exception {
        open(128, 2);
        store.put(List.of(object(1))).join();
        AtomicBoolean once = new AtomicBoolean(true);
        try (NativeSqlite reader = store.openReader()) {
            reader.afterStatementProbe =
                    sql -> {
                        if (sql.startsWith("SELECT * FROM packs ORDER BY seq")
                                && once.getAndSet(false)) {
                            store.put(List.of(object(2))).join();
                        }
                    };
            assertTrue(new StoreIndex(reader).validate(store.index.id, true, Cancellation.NONE));
            assertFalse(reader.inTransaction());
        }
        assertFalse(once.get());
        assertEquals(2, store.stats().join().objects());
    }
}
