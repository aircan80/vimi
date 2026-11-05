package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.vibeminer.access.Cancellation;
import com.vibeminer.store.CrashProcess;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.WalFrames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lost commits, not just a killed process. The library and every application store in it run with
 * {@code synchronous=NORMAL}, so a power loss may drop any WAL frames written after the last sync
 * point while the packs keep every byte the store fsynced. A SIGKILL keeps the page cache, so these
 * tests take the killed session's files and remove what the OS could have lost:
 *
 * <ul>
 *   <li>every suffix of the unsynced WAL frames, one frame at a time (torn transactions too);
 *   <li>one lost frame with later frames kept (reordered writeback);
 *   <li>a pre-commit snapshot of the database and WAL, with all later fsynced pack bytes kept.
 * </ul>
 *
 * After each, a reopened library must verify clean, every record that says something committed
 * (saved inventory, catalog generation, receipt) must agree with what survived or be detectably
 * absent, and a later preparation must rebuild the clean cold reference.
 */
@Timeout(1800)
final class TableLostCommitCrashTest {
    @TempDir static Path shared;
    @TempDir Path temporary;

    private static TableCommitScenario.Reference reference;
    private static Path crashed, control;
    private static int synced, total;
    private static List<Integer> commits;
    private static final java.util.concurrent.atomic.AtomicInteger cases =
            new java.util.concurrent.atomic.AtomicInteger();

    /** What one recovered library says survived, as a rank in the session's order. */
    enum Stage {
        /** The previous state: tables 300 and 400, their inventory, catalog v1, no receipt. */
        PREVIOUS,
        /** One of the session's two table commits. */
        ONE_TABLE,
        /** Both table commits. */
        BOTH_TABLES,
        /** The old inventory's delete, not yet its replacement. */
        INVENTORY_DELETED,
        /** The new four-table inventory. */
        INVENTORY_SAVED,
        /** The catalog generation naming the new graphs. */
        CATALOG_PUBLISHED,
        /** The preparation receipt. */
        RECEIPT_SAVED,
        /** A second build's ownership claim on table 100's existing node. */
        CLAIMED,
        /** A staged download, committed and its staged file deleted. */
        DOWNLOADED
    }

    @BeforeAll
    static void crashOneSession() throws Exception {
        try (var scenario = TableCommitScenario.open(shared.resolve("reference")).seed()) {
            reference = scenario.reference();
        }
        crashed = shared.resolve("crashed").resolve("library");
        control = Files.createDirectories(shared.resolve("control"));
        try (var scenario = TableCommitScenario.open(crashed).seed()) {
            scenario.preparePrevious();
            scenario.catalog
                    .expect(
                            TableLostCommitWorker.OWNER,
                            LibraryCatalog.Scope.BUILD,
                            TableLostCommitWorker.SOURCE,
                            LibraryCatalog.Role.DERIVED)
                    .join();
            TableLostCommitWorker.publishCatalog(
                    scenario, TableLostCommitWorker.PREVIOUS, List.of(300L, 400L));
        }
        int snapshotAt = reference.objects().get(100L).size() + 1;
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Xmx256m");
        String sqlite = System.getProperty("vibeminer.sqlite.library");
        if (sqlite != null) {
            command.add("-Dvibeminer.sqlite.library=" + sqlite);
        }
        command.addAll(
                List.of(
                        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                        "-cp",
                        System.getProperty("java.class.path"),
                        TableLostCommitWorker.class.getName(),
                        crashed.toString(),
                        control.toString(),
                        Integer.toString(snapshotAt)));
        CrashProcess.killAtReady(command, "READY LOST");

        Path database = crashed.resolve("objects.sqlite3");
        var lines = Files.readAllLines(control.resolve("sync-point.txt"));
        var wal = WalFrames.of(database);
        // Cutting WAL frames is only a valid power loss while no checkpoint copied them into the
        // database (NORMAL syncs the WAL first) and none restarted the WAL.
        assertArrayEquals(
                HexFormat.of().parseHex(lines.get(1)), wal.header(), "no WAL restart since sync");
        assertEquals(
                lines.get(2), TableLostCommitWorker.sha256(database), "no checkpoint since sync");
        synced = wal.framesAt(Long.parseLong(lines.get(0)));
        total = wal.frames();
        commits = wal.commits().stream().filter(c -> c > synced).toList();
        assertEquals(total, commits.getLast(), "the session ends on a commit frame");
        // 2 tables, the inventory's delete and put, 3 catalog transactions, the receipt, the
        // claim and the download.
        assertTrue(commits.size() >= 10, "unsynced session commits: " + commits);
        assertTrue(Files.isDirectory(control.resolve("snapshot")), "pre-commit snapshot taken");
    }

    @TestFactory
    Stream<DynamicTest> everyLostSuffixOfUnsyncedWalFramesRecovers() {
        var ranks = new TreeMap<Integer, Stage>();
        var tests = new ArrayList<DynamicTest>();
        for (int k = synced; k <= total; k++) {
            int kept = k;
            tests.add(
                    DynamicTest.dynamicTest(
                            "keep "
                                    + kept
                                    + " of "
                                    + total
                                    + " frames (lose "
                                    + (total - kept)
                                    + ")",
                            () -> ranks.put(kept, recover(kept, wal -> wal.keep(kept)))));
        }
        tests.add(
                DynamicTest.dynamicTest(
                        "survivors only grow with the frames kept",
                        () -> {
                            assertEquals(total - synced + 1, ranks.size(), "" + ranks);
                            assertEquals(Stage.PREVIOUS, ranks.get(synced), "" + ranks);
                            assertEquals(Stage.DOWNLOADED, ranks.get(total), "" + ranks);
                            Stage previous = Stage.PREVIOUS;
                            for (var rank : ranks.entrySet()) {
                                assertTrue(
                                        rank.getValue().compareTo(previous) >= 0,
                                        "lost commits form a suffix: " + ranks);
                                previous = rank.getValue();
                            }
                            for (Stage stage : Stage.values()) {
                                assertTrue(ranks.containsValue(stage), stage + ": " + ranks);
                            }
                        }));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> aLostFrameDiscardsEveryLaterFrameItWasWrittenBefore() {
        var tests = new ArrayList<DynamicTest>();
        int previous = synced;
        for (int commit : commits) {
            int start = previous, lost = commit - 1;
            tests.add(
                    DynamicTest.dynamicTest(
                            "lose commit frame " + commit + " of " + total + ", keep the rest",
                            () -> {
                                Stage prefix = recover(start, wal -> wal.keep(start));
                                Stage reordered = recover(total, wal -> wal.lose(lost));
                                assertEquals(prefix, reordered, "only the earlier prefix survives");
                                // A torn sector: the header (and salts) survived, the page did not,
                                // so only the checksum chain can reject it.
                                Stage torn = recover(total, wal -> wal.tear(lost));
                                assertEquals(prefix, torn, "a torn page ends the valid prefix");
                            }));
            previous = commit;
        }
        return tests.stream();
    }

    @Test
    void preCommitSnapshotWithLaterFsyncedPackBytesRecovers() throws Exception {
        Stage stage =
                recover(
                        -1,
                        wal -> {
                            Path database = wal.path().resolveSibling("objects.sqlite3");
                            for (String name : List.of("objects.sqlite3", "objects.sqlite3-wal")) {
                                Files.copy(
                                        control.resolve("snapshot").resolve(name),
                                        database.resolveSibling(name),
                                        StandardCopyOption.REPLACE_EXISTING);
                            }
                        });
        assertEquals(Stage.ONE_TABLE, stage, "the index as it was before the second table commit");
    }

    private interface Loss {
        void apply(WalFrames wal) throws Exception;
    }

    /**
     * Copies the killed session, applies {@code loss}, reopens, checks consistency, then rebuilds
     * whatever the lost commits removed. Returns what survived.
     */
    private Stage recover(int label, Loss loss) throws Exception {
        Path folder =
                temporary.resolve(
                        "library-"
                                + (label < 0 ? "snapshot" : label)
                                + "-"
                                + cases.incrementAndGet());
        copy(crashed, folder);
        loss.apply(WalFrames.of(folder.resolve("objects.sqlite3")));
        // Power loss: no process holds the shared-memory index, so SQLite rebuilds it from the WAL.
        Files.deleteIfExists(folder.resolve("objects.sqlite3-shm"));
        Stage stage;
        try (var scenario = TableCommitScenario.open(folder)) {
            var report =
                    scenario.library.verify(true, com.vibeminer.store.Cancellation.NONE).join();
            assertEquals(0, report.corruptCount(), "" + report);
            assertEquals(0, report.lostCount(), "" + report);
            stage = classify(scenario);
            // Detect and rebuild: without its receipt the application prepares again.
            String receipt = TableLostCommitWorker.receipt(scenario.library.databasePath());
            if (!"TABLES".equals(receipt)) {
                var pass = TableLostCommitWorker.prepare(scenario);
                assertEquals(reference.ledger(), pass.tables(), "the rebuilt ledger");
            }
            scenario.assertRegenerates(reference, true);
            assertCatalogNames(scenario, TableCommitScenario.IDS);
            assertEquals("TABLES", TableLostCommitWorker.receipt(scenario.library.databasePath()));
            // A lost claim is claimed again; a lost download is fetched again (its staged file is
            // gone: the policy's allowed loss).
            if (!TableLostCommitWorker.claimed(scenario)) {
                TableLostCommitWorker.claim(scenario);
            }
            if (!TableLostCommitWorker.downloaded(scenario)) {
                TableLostCommitWorker.download(scenario);
            }
        }
        try (var scenario = TableCommitScenario.open(folder)) {
            assertEquals(Stage.DOWNLOADED, classify(scenario), "the rebuilt state persists");
            scenario.assertRegenerates(reference, true);
        }
        return stage;
    }

    /**
     * Fails unless the recovered records form one prefix of the session: each record that says
     * something committed agrees with what it names, and nothing later survives without everything
     * earlier.
     */
    private static Stage classify(TableCommitScenario scenario) throws Exception {
        for (long id : List.of(300L, 400L)) {
            assertTrue(scenario.graph(reference, id), "previous table " + id);
        }
        // Each crashed table is all or nothing.
        int tables = 0;
        for (long id : List.of(100L, 200L)) {
            var objects = reference.objects().get(id);
            boolean graph = scenario.graph(reference, id);
            assertEquals(graph ? objects : java.util.Set.of(), scenario.present(objects), "" + id);
            tables += graph ? 1 : 0;
        }
        var restored =
                new PreparedTablesCache(scenario.generated)
                        .restore(TableCommitScenario.INVENTORY, null, Cancellation.NONE)
                        .join();
        int inventory = restored == null ? 0 : restored.tables().size();
        boolean next =
                scenario.catalog
                        .current(
                                TableLostCommitWorker.OWNER,
                                TableLostCommitWorker.SOURCE,
                                TableLostCommitWorker.NEXT)
                        .join();
        boolean previous =
                scenario.catalog
                        .current(
                                TableLostCommitWorker.OWNER,
                                TableLostCommitWorker.SOURCE,
                                TableLostCommitWorker.PREVIOUS)
                        .join();
        String receipt = TableLostCommitWorker.receipt(scenario.library.databasePath());
        String state =
                "tables="
                        + tables
                        + " inventory="
                        + inventory
                        + " catalog="
                        + (next ? "v2" : previous ? "v1" : "none")
                        + " receipt="
                        + receipt
                        + " claimed="
                        + TableLostCommitWorker.claimed(scenario)
                        + " downloaded="
                        + TableLostCommitWorker.downloaded(scenario);
        // A saved inventory names only graphs that exist, and they verify.
        if (restored != null) {
            var verified =
                    new PreparedTablesCache(scenario.generated)
                            .restore(
                                    TableCommitScenario.INVENTORY,
                                    scenario.tables(),
                                    null,
                                    Cancellation.NONE)
                            .join();
            assertNotNull(verified, "a surviving inventory verifies: " + state);
        }
        // A published catalog generation names only objects that exist.
        if (next) {
            assertCatalogNames(scenario, TableCommitScenario.IDS);
        } else {
            assertTrue(previous, "the previous generation stays current: " + state);
            assertCatalogNames(scenario, List.of(300L, 400L));
        }
        Map<Stage, String> allowed =
                Map.of(
                        Stage.PREVIOUS,
                                "tables=0 inventory=2 catalog=v1 receipt=null claimed=false downloaded=false",
                        Stage.ONE_TABLE,
                                "tables=1 inventory=2 catalog=v1 receipt=null claimed=false downloaded=false",
                        Stage.BOTH_TABLES,
                                "tables=2 inventory=2 catalog=v1 receipt=null claimed=false downloaded=false",
                        Stage.INVENTORY_DELETED,
                                "tables=2 inventory=0 catalog=v1 receipt=null claimed=false downloaded=false",
                        Stage.INVENTORY_SAVED,
                                "tables=2 inventory=4 catalog=v1 receipt=null claimed=false downloaded=false",
                        Stage.CATALOG_PUBLISHED,
                                "tables=2 inventory=4 catalog=v2 receipt=null claimed=false downloaded=false",
                        Stage.RECEIPT_SAVED,
                                "tables=2 inventory=4 catalog=v2 receipt=TABLES claimed=false downloaded=false",
                        Stage.CLAIMED,
                                "tables=2 inventory=4 catalog=v2 receipt=TABLES claimed=true downloaded=false",
                        Stage.DOWNLOADED,
                                "tables=2 inventory=4 catalog=v2 receipt=TABLES claimed=true downloaded=true");
        for (var entry : allowed.entrySet()) {
            if (entry.getValue().equals(state)) {
                return entry.getKey();
            }
        }
        return fail("not a prefix of the session: " + state);
    }

    private static void assertCatalogNames(TableCommitScenario scenario, List<Long> ids) {
        var keys = TableLostCommitWorker.catalogKeys(reference, ids);
        var lengths =
                scenario.generated
                        .storedLengths(keys, com.vibeminer.generated.Cancellation.NONE)
                        .join();
        for (int i = 0; i < keys.size(); i++) {
            assertTrue(lengths.get(i) >= 0, "catalog names a stored object: " + keys.get(i));
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (!path.getFileName().toString().endsWith("-shm")) {
                    Files.copy(path, target);
                }
            }
        }
    }
}
