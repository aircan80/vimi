package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code synchronous} mode of every index commit on a unified library (see
 * docs/durability-policy.md). Data commits run NORMAL behind a pack fsync. Every commit that a file
 * action outside the database depends on must run FULL: pack allocation, the compaction intent and
 * switch (before the source pack is deleted), scan, and the open and close markers. A crash test
 * cannot tell a lost NORMAL switch from a durable one without a real power loss, so this test fails
 * instead when such a step is relaxed.
 */
@SuppressWarnings("try")
final class LibraryDurabilityModeTest {
    private static final int NORMAL = 1, FULL = 2;

    @TempDir Path folder;

    /** Index commits with their mode, interleaved with maintenance points, in order. */
    private static final class Trace {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        void watch(NativeSqlite db) {
            db.statementProbe =
                    sql -> {
                        if (sql.equals("COMMIT")) {
                            events.add("COMMIT " + db.scalar("PRAGMA synchronous"));
                        }
                    };
        }

        void point(MaintenancePoint point) {
            events.add(point.name());
        }

        List<String> take() {
            synchronized (events) {
                var copy = List.copyOf(events);
                events.clear();
                return copy;
            }
        }
    }

    private static List<Integer> commits(List<String> events) {
        return events.stream()
                .filter(e -> e.startsWith("COMMIT "))
                .map(e -> Integer.parseInt(e.substring(7)))
                .toList();
    }

    private static void assertModes(List<Integer> expected, List<String> events, String step) {
        assertEquals(expected, commits(events), step + ": " + events);
    }

    private static void assertAll(int mode, List<String> events, String step) {
        var commits = commits(events);
        assertFalse(commits.isEmpty(), step + " commits: " + events);
        assertEquals(Collections.nCopies(commits.size(), mode), commits, step + ": " + events);
    }

    /** A small raw-mode BLTE payload, as a CDN download stores it. */
    private static byte[] blte(int length, int seed) {
        byte[] bytes = new byte[length];
        new java.util.Random(seed).nextBytes(bytes);
        System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'}, 0, bytes, 0, 9);
        return bytes;
    }

    private static ObjectKey key(byte[] bytes) {
        return new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
    }

    @Test
    void destructiveLibraryStepsCommitFullAndDataCommitsNormal() throws Exception {
        var trace = new Trace();
        ObjectStore store;
        ObjectStore.indexOpened = trace::watch;
        try {
            store =
                    ObjectStore.open(folder, new Options(Kind.LIBRARY, 300_000, Clock.systemUTC()))
                            .join();
        } finally {
            ObjectStore.indexOpened = null;
        }
        // Ownership initialization and the clean-close marker.
        assertAll(FULL, trace.take(), "open");
        store.maintenanceProbe = trace::point;
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            var a =
                    store.forOwner(
                            "a",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            var b =
                    store.forOwner(
                            "b",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            byte[] first = blte(100_003, 1), second = blte(100_003, 2), third = blte(100_003, 3);

            // The first put allocates a pack (FULL listing), then commits its rows (NORMAL).
            assertEquals(
                    PutOutcome.STORED,
                    a.put(List.of(new PutObject(key(first), first))).join().getFirst().outcome());
            assertModes(List.of(FULL, NORMAL), trace.take(), "first put");

            // Claims, staged downloads and deletes are data commits behind a pack fsync.
            assertEquals(
                    PutOutcome.ALREADY_PRESENT,
                    b.put(List.of(new PutObject(key(first), first))).join().getFirst().outcome());
            assertModes(List.of(NORMAL), trace.take(), "claimExisting");
            var stage =
                    a.stage(key(second), second.length, StoreTypes.Origin.CDN, Cancellation.NONE)
                            .join();
            stage.append(second, Cancellation.NONE).join();
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertAll(NORMAL, trace.take(), "staged commit");
            assertEquals(
                    StoreTypes.DeleteOutcome.DELETED,
                    store.delete(List.of(key(first)), Cancellation.NONE)
                            .join()
                            .getFirst()
                            .outcome());
            assertAll(NORMAL, trace.take(), "delete");

            // A full pack rolls over: the new pack's listing and the seal are FULL.
            assertEquals(
                    PutOutcome.STORED,
                    a.put(List.of(new PutObject(key(third), third))).join().getFirst().outcome());
            assertModes(List.of(FULL, NORMAL), trace.take(), "rollover put");

            // Compaction: the target's intent row and the switch are FULL, and the source pack is
            // deleted only after the switch commit.
            var before = packs();
            store.compact(0, 10, Cancellation.NONE).join();
            var compaction = trace.take();
            assertAll(FULL, compaction, "compaction");
            int listed = compaction.indexOf(MaintenancePoint.COMPACT_TARGET_LISTED.name());
            int switching = compaction.indexOf(MaintenancePoint.COMPACT_SWITCHING.name());
            int deleted = compaction.indexOf(MaintenancePoint.COMPACT_OLD_DELETED.name());
            assertTrue(
                    listed > 0 && compaction.get(listed - 1).equals("COMMIT " + FULL),
                    "the intent row commits FULL before the target exists: " + compaction);
            assertTrue(
                    switching > listed
                            && deleted > switching + 1
                            && compaction.get(switching + 1).equals("COMMIT " + FULL),
                    "the switch commits FULL before the source pack is deleted: " + compaction);
            assertFalse(packs().containsAll(before), "a source pack was compacted away");

            store.scan(Cancellation.NONE).join();
            assertAll(FULL, trace.take(), "scan");
            assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
        }
        trace.take();
        store.closeAsync().join();
        var close = commits(trace.take());
        assertEquals(FULL, close.getLast(), "the clean-close marker: " + close);
    }

    private List<Path> packs() throws java.io.IOException {
        try (var files = Files.list(folder.resolve("packs"))) {
            return files.sorted().toList();
        }
    }
}
