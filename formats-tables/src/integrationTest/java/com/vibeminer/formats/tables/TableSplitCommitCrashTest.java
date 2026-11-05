package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.TableCommitScenario.Config;
import com.vibeminer.formats.tables.TableCommitScenario.Reference;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.WriteProbes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Node-last ordering where one table's put really splits into several durable commits, at every
 * layer that can split it (see {@link Config}). A crash between split commits, or a refused object,
 * may leave an ordered prefix of the table's objects, but never its node without everything the
 * node references.
 */
@Timeout(300)
final class TableSplitCommitCrashTest {
    @TempDir static Path shared;
    @TempDir Path temporary;

    private static final Map<Config, Reference> REFERENCES = new EnumMap<>(Config.class);
    private static final Map<Config, List<TableCommitScenario.Event>> TRACES =
            new EnumMap<>(Config.class);

    /** Which boundary between split commits a kill lands on. */
    enum Boundary {
        FIRST,
        MIDDLE,
        LAST
    }

    /** Where around that boundary the writer is killed. */
    enum Moment {
        /** Commit j is durable; commit j+1 has not started. */
        AFTER_COMMIT,
        /** Commit j+1's pack is synced; its index transaction has not begun. */
        NEXT_SYNCED
    }

    /** Which of table 100's objects verification refuses. */
    enum Refused {
        FIRST,
        MIDDLE,
        BEFORE_NODE
    }

    private static synchronized Reference reference(Config config) {
        return REFERENCES.computeIfAbsent(
                config,
                c -> {
                    try (var scenario =
                            TableCommitScenario.open(shared.resolve(c.name()), c).seed()) {
                        return scenario.reference();
                    }
                });
    }

    /**
     * Table 100's write points when stored after the previous state, as every test here does. Where
     * a split falls depends on what the packs already hold, so this is a dry run of exactly that
     * sequence, not the reference run.
     */
    private static synchronized List<TableCommitScenario.Event> trace(Config config) {
        return TRACES.computeIfAbsent(
                config,
                c -> {
                    try (var scenario =
                            TableCommitScenario.open(shared.resolve(c.name() + "-dry"), c).seed()) {
                        scenario.preparePrevious();
                        var events =
                                java.util.Collections.synchronizedList(
                                        new ArrayList<TableCommitScenario.Event>());
                        scenario.probe(
                                (point, key) ->
                                        events.add(new TableCommitScenario.Event(point, key)));
                        assertEquals(
                                TableTypes.State.READY,
                                scenario.check(100, Cancellation.NONE).state());
                        scenario.probe(null);
                        return List.copyOf(events);
                    }
                });
    }

    /** Table 100's durable commits after the previous state, each a list of keys in write order. */
    private static List<List<GeneratedKey>> splits(Config config) {
        return TableCommitCrashTest.commits(trace(config));
    }

    private static List<GeneratedKey> written(Config config) {
        var order = new LinkedHashSet<GeneratedKey>();
        for (var event : trace(config)) {
            if (event.point().equals("ENTRY_STARTED")) {
                order.add(event.key());
            }
        }
        return List.copyOf(order);
    }

    private static Set<GeneratedKey> union(List<List<GeneratedKey>> commits, int count) {
        var keys = new LinkedHashSet<GeneratedKey>();
        for (int i = 0; i < count; i++) {
            keys.addAll(commits.get(i));
        }
        return keys;
    }

    private static GeneratedKey node(Config config) {
        return GeneratedKey.parse(reference(config).identities().get(100L));
    }

    @ParameterizedTest
    @EnumSource(value = Config.class, names = "DEFAULT", mode = EnumSource.Mode.EXCLUDE)
    void eachConfigSplitsTheTableIntoSeveralCommitsWithTheNodeInTheLast(Config config) {
        var commits = splits(config);
        assertTrue(commits.size() >= 3, config + " splits: " + commits.size());
        var node = node(config);
        assertTrue(commits.getLast().contains(node), "node is in the last commit");
        for (var commit : commits.subList(0, commits.size() - 1)) {
            assertFalse(commit.contains(node), "node is in no earlier commit");
        }
        assertEquals(reference(config).objects().get(100L), union(commits, commits.size()));
        assertEquals(
                written(config).getLast(), node, "the node is the last object written, as well");
    }

    static Stream<Arguments> kills() {
        var arguments = new ArrayList<Arguments>();
        for (var config : Config.values()) {
            if (config == Config.DEFAULT) {
                continue;
            }
            for (var boundary : Boundary.values()) {
                for (var moment : Moment.values()) {
                    arguments.add(Arguments.of(config, boundary, moment));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("kills")
    void killBetweenSplitCommitsNeverExposesTheNodeWithoutItsGraph(
            Config config, Boundary boundary, Moment moment) throws Exception {
        var reference = reference(config);
        var commits = splits(config);
        int k = commits.size();
        int j =
                switch (boundary) {
                    case FIRST -> 0;
                    case MIDDLE -> (k - 2) / 2;
                    case LAST -> k - 2;
                };
        int through = moment == Moment.AFTER_COMMIT ? j : j + 1;
        int occurrence = union(commits, through + 1).size();
        String point = moment == Moment.AFTER_COMMIT ? "INDEX_COMMITTED" : "PACK_SYNCED";
        Path folder = temporary.resolve("library");
        try (var scenario = TableCommitScenario.open(folder, config).seed()) {
            scenario.preparePrevious();
        }
        TableCommitCrashTest.kill(folder, "single", point, occurrence, config);
        try (var scenario = TableCommitScenario.open(folder, config)) {
            var report =
                    scenario.library.verify(true, com.vibeminer.store.Cancellation.NONE).join();
            assertEquals(0, report.corruptCount(), "" + report);
            assertEquals(0, report.lostCount(), "" + report);
            for (long id : List.of(300L, 400L)) {
                assertTrue(scenario.graph(reference, id), "previous table " + id);
            }
            var objects = reference.objects().get(100L);
            assertEquals(
                    union(commits, j + 1),
                    scenario.present(objects),
                    "exactly the first " + (j + 1) + " of " + k + " commits are durable");
            assertFalse(scenario.graph(reference, 100), "the node is in the last commit");
            scenario.assertRegenerates(reference, true);
        }
    }

    static Stream<Arguments> refusals() {
        var arguments = new ArrayList<Arguments>();
        for (var config : Config.values()) {
            for (var refused : Refused.values()) {
                arguments.add(Arguments.of(config, refused));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void refusedObjectNeverLetsTheNodeCommit(Config config, Refused refused) {
        var reference = reference(config);
        var order = written(config);
        var node = node(config);
        var key =
                order.get(
                        switch (refused) {
                            case FIRST -> 0;
                            case MIDDLE -> order.size() / 2;
                            case BEFORE_NODE -> order.size() - 2;
                        });
        assertNotEquals(node, key);
        var commits = splits(config);
        int containing = 0;
        while (!commits.get(containing).contains(key)) {
            containing++;
        }
        var target = new ObjectKey(ObjectKey.GENERATED, key.bytes());
        Path folder = temporary.resolve("library");
        try (var scenario = TableCommitScenario.open(folder, config).seed()) {
            scenario.preparePrevious();
            WriteProbes.refuse(
                    scenario.library, k -> k.equals(target) ? "OBJECT_FIXTURE_REFUSED" : null);
            TableTypes.Entry entry = null;
            try {
                entry = scenario.check(100, Cancellation.NONE);
            } catch (CompletionException expected) {
                // A refused store may surface as an exception or as an unreadable entry.
            }
            WriteProbes.refuse(scenario.library, null);
            assertTrue(
                    entry == null || entry.state() != TableTypes.State.READY,
                    "a refused table is not READY: " + entry);
            assertFalse(scenario.graph(reference, 100), "no node after a refusal");
            var present = scenario.present(reference.objects().get(100L));
            assertFalse(present.contains(key));
            int durable = 0;
            while (durable < containing && present.containsAll(commits.get(durable))) {
                durable++;
            }
            assertEquals(
                    union(commits, durable),
                    present,
                    "only whole commits before the refused object's put are durable");
            assertEquals(0, scenario.tableStore.collectedBytes());
        }
        try (var scenario = TableCommitScenario.open(folder, config)) {
            scenario.assertRegenerates(reference, true);
        }
    }
}
