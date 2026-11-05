package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.CrashProcess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Crash consistency of one generated table's store. Every recovered library must expose either the
 * complete previous state or the complete new table, never a node or manifest that points at
 * missing or partial objects, and later passes must classify it and regenerate what is missing.
 */
@Timeout(120)
final class TableCommitCrashTest {
    @TempDir static Path shared;
    @TempDir Path temporary;

    private static TableCommitScenario.Reference reference;

    /** A table's objects commit as one group of n entries; each point names one entry's step. */
    enum CrashPoint {
        /** Before any byte of the group reaches the pack. */
        BEFORE_PACK_WRITE("ENTRY_STARTED", 1),
        /** Half of the first payload is in the pack. */
        TORN_PAYLOAD("PAYLOAD_PARTIAL", 1),
        /** Two entries are in the pack and the rest are not. */
        PARTIAL_GROUP_WRITTEN("HEADER_WRITTEN", 2),
        /** Every entry is in the pack; the pack is not yet synced. */
        WRITTEN_NOT_SYNCED("HEADER_WRITTEN", -1),
        /** The pack is synced; the index transaction has not begun. */
        SYNCED_NOT_INDEXED("PACK_SYNCED", -1),
        /** The index transaction has applied one entry. */
        MID_TRANSACTION("INDEX_UPDATED", 1),
        /** The index transaction has applied every entry but has not committed. */
        TRANSACTION_UNCOMMITTED("INDEX_UPDATED", -1),
        /** The index transaction committed; the writer has not acknowledged it. */
        COMMITTED("INDEX_COMMITTED", -1);

        final String point;
        private final int entry;

        CrashPoint(String point, int entry) {
            this.point = point;
            this.entry = entry;
        }

        /** The occurrence of {@link #point} in the commit after {@code before} other entries. */
        int occurrence(int entries, int before) {
            return before + (entry < 0 ? entries : entry);
        }

        boolean committed() {
            return this == COMMITTED;
        }
    }

    @BeforeAll
    static void cleanColdReference() {
        try (var scenario = TableCommitScenario.open(shared.resolve("reference")).seed()) {
            reference = scenario.reference();
        }
        for (long id : TableCommitScenario.IDS) {
            assertTrue(
                    reference.objects().get(id).size() >= 4,
                    "segment, manifest, layout and node: " + reference.objects());
        }
    }

    private static int entries(long id) {
        return reference.objects().get(id).size();
    }

    @Test
    void oneTableCommitsAllItsObjectsInOneDurableCommit() {
        try (var scenario = TableCommitScenario.open(temporary.resolve("library")).seed()) {
            var events = Collections.synchronizedList(new ArrayList<TableCommitScenario.Event>());
            scenario.probe((point, key) -> events.add(new TableCommitScenario.Event(point, key)));
            assertEquals(TableTypes.State.READY, scenario.check(100, Cancellation.NONE).state());
            scenario.probe(null);
            var commits = commits(events);
            assertEquals(
                    List.of(reference.objects().get(100L)),
                    commits.stream().map(Set::copyOf).toList(),
                    "one pack sync and one index transaction for the whole table: " + events);
        }
    }

    @Test
    void concurrentTablesEachCommitInOneDurableCommitOfTheirOwn() {
        try (var scenario = TableCommitScenario.open(temporary.resolve("library")).seed()) {
            var events = Collections.synchronizedList(new ArrayList<TableCommitScenario.Event>());
            scenario.probe((point, key) -> events.add(new TableCommitScenario.Event(point, key)));
            var checks = new ArrayList<CompletableFuture<TableTypes.Entry>>();
            for (long id : TableCommitScenario.IDS) {
                checks.add(
                        CompletableFuture.supplyAsync(() -> scenario.check(id, Cancellation.NONE)));
            }
            for (var check : checks) {
                assertEquals(TableTypes.State.READY, check.join().state());
            }
            scenario.probe(null);
            var commits = commits(events).stream().map(Set::copyOf).toList();
            assertEquals(TableCommitScenario.IDS.size(), commits.size(), "" + events);
            for (long id : TableCommitScenario.IDS) {
                assertTrue(commits.contains(reference.objects().get(id)), id + ": " + events);
            }
            scenario.assertRegenerates(reference, false);
        }
    }

    @ParameterizedTest
    @EnumSource(CrashPoint.class)
    void killedTableCommitLeavesThePreviousStateOrTheCompleteTable(CrashPoint point)
            throws Exception {
        Path folder = temporary.resolve("library");
        try (var scenario = TableCommitScenario.open(folder).seed()) {
            scenario.preparePrevious();
        }
        kill(folder, "single", point, point.occurrence(entries(100), 0));
        try (var scenario = TableCommitScenario.open(folder)) {
            assertRecovered(scenario, List.of(100L), point.committed() ? 1 : 0);
            assertTrue(!scenario.graph(reference, 200), "200 was never started");
            scenario.assertRegenerates(reference, true);
        }
        try (var scenario = TableCommitScenario.open(folder)) {
            scenario.assertRegenerates(reference, true);
        }
    }

    @ParameterizedTest
    @EnumSource(CrashPoint.class)
    void killedSecondOfTwoConcurrentTableCommitsKeepsTheFirstComplete(CrashPoint point)
            throws Exception {
        Path folder = temporary.resolve("library");
        try (var scenario = TableCommitScenario.open(folder).seed()) {
            scenario.preparePrevious();
        }
        assertEquals(entries(100), entries(200));
        kill(folder, "concurrent", point, point.occurrence(entries(200), entries(100)));
        try (var scenario = TableCommitScenario.open(folder)) {
            assertRecovered(scenario, List.of(100L, 200L), point.committed() ? 2 : 1);
            scenario.assertRegenerates(reference, true);
        }
    }

    @ParameterizedTest
    @EnumSource(CrashPoint.class)
    void failedTableCommitLeavesThePreviousStateOrTheCompleteTable(CrashPoint point) {
        Path folder = temporary.resolve("library");
        try (var scenario = TableCommitScenario.open(folder).seed()) {
            scenario.preparePrevious();
            int occurrence = point.occurrence(entries(100), 0);
            var seen = new AtomicInteger();
            scenario.probe(
                    (name, key) -> {
                        if (name.equals(point.point) && seen.incrementAndGet() == occurrence) {
                            throw new IllegalStateException("injected at " + name);
                        }
                    });
            TableTypes.Entry entry = null;
            try {
                entry = scenario.check(100, Cancellation.NONE);
            } catch (CompletionException expected) {
                // A failed store may surface as an exception or as an unreadable entry.
            }
            scenario.probe(null);
            assertEquals(occurrence, seen.get(), "the injected point was reached");
            if (entry != null && entry.state() == TableTypes.State.READY) {
                assertTrue(point.committed(), "READY only for a committed graph: " + entry);
            }
            assertRecovered(scenario, List.of(100L), point.committed() ? 1 : 0);
        }
        try (var scenario = TableCommitScenario.open(folder)) {
            assertRecovered(scenario, List.of(100L), point.committed() ? 1 : 0);
            scenario.assertRegenerates(reference, true);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void canceledTableCommitNeverPublishesAPartialGraph(int entry) {
        assumeTrue(entry <= entries(100));
        Path folder = temporary.resolve("library");
        try (var scenario = TableCommitScenario.open(folder).seed()) {
            scenario.preparePrevious();
            var token = new Cancellation();
            var seen = new AtomicInteger();
            scenario.probe(
                    (name, key) -> {
                        if (name.equals("ENTRY_STARTED") && seen.incrementAndGet() == entry) {
                            token.cancel();
                        }
                    });
            try {
                scenario.check(100, token);
            } catch (CompletionException | java.util.concurrent.CancellationException expected) {
                // Canceled mid-table.
            }
            scenario.probe(null);
            scenario.settle();
            // Cancellation may commit an unreferenced prefix, but never the node without it.
            var objects = reference.objects().get(100L);
            if (scenario.graph(reference, 100)) {
                assertEquals(objects, scenario.present(objects));
            }
            assertEquals(TableTypes.State.READY, scenario.check(100, Cancellation.NONE).state());
            assertTrue(scenario.graph(reference, 100));
            scenario.assertRegenerates(reference, true);
        }
        try (var scenario = TableCommitScenario.open(folder)) {
            scenario.assertRegenerates(reference, true);
        }
    }

    /**
     * The previous tables are complete, and each crashed table is all or nothing: every object its
     * preparation writes is visible, or none is. {@code complete} of them finished.
     */
    private static void assertRecovered(
            TableCommitScenario scenario, List<Long> crashed, int complete) {
        var report = scenario.library.verify(true, com.vibeminer.store.Cancellation.NONE).join();
        assertEquals(0, report.corruptCount(), "" + report);
        assertEquals(0, report.lostCount(), "" + report);
        for (long id : List.of(300L, 400L)) {
            assertTrue(scenario.graph(reference, id), "previous table " + id);
        }
        int finished = 0;
        for (long id : crashed) {
            var objects = reference.objects().get(id);
            var present = scenario.present(objects);
            boolean graph = scenario.graph(reference, id);
            assertEquals(graph ? objects : Set.of(), present, "all or nothing for " + id);
            finished += graph ? 1 : 0;
        }
        assertEquals(complete, finished, "complete tables among " + crashed);
    }

    /**
     * Splits observed write points into durable commits. A commit writes its entries, then syncs
     * the pack once (PACK_SYNCED for each entry), then updates and commits the index once.
     */
    private static List<List<GeneratedKey>> commits(List<TableCommitScenario.Event> events) {
        var commits = new ArrayList<List<GeneratedKey>>();
        var written = new LinkedHashSet<GeneratedKey>();
        int at = 0;
        while (at < events.size()) {
            var event = events.get(at);
            if (!event.point().equals("PACK_SYNCED")) {
                if (event.point().equals("HEADER_WRITTEN")) {
                    written.add(event.key());
                }
                at++;
                continue;
            }
            var group = List.copyOf(written);
            for (String point : List.of("PACK_SYNCED", "INDEX_UPDATED", "INDEX_COMMITTED")) {
                for (var key : group) {
                    assertTrue(at < events.size(), "truncated commit: " + events);
                    assertEquals(
                            new TableCommitScenario.Event(point, key),
                            events.get(at++),
                            "commit of " + group + ": " + events);
                }
            }
            commits.add(group);
            written.clear();
        }
        assertTrue(written.isEmpty(), "uncommitted entries: " + written);
        return commits;
    }

    private static void kill(Path folder, String mode, CrashPoint point, int occurrence)
            throws Exception {
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
                        TableCommitCrashWorker.class.getName(),
                        folder.toString(),
                        mode,
                        point.point,
                        Integer.toString(occurrence)));
        CrashProcess.killAtReady(command, "READY " + point.point + " " + occurrence);
    }
}
