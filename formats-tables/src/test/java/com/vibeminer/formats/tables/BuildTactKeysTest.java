package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BuildTactKeysTest {
    private static List<Db2Types.Column> columns(String name, int count, boolean duplicate) {
        var result = new ArrayList<Db2Types.Column>();
        for (int i = 0; i < count; i++) {
            result.add(new Db2Types.Column(name + "[" + i + "]", "integer", false, null, null));
        }
        if (duplicate) {
            result.add(
                    new Db2Types.Column(
                            name.toLowerCase(java.util.Locale.ROOT) + "[0]",
                            "integer",
                            false,
                            null,
                            null));
        }
        return result;
    }

    private static Db2Types.Row row(long id, int first, int size, boolean extra) {
        var values = new ArrayList<String>();
        values.add(Integer.toString(first));
        for (int i = 1; i < size; i++) {
            values.add("0");
        }
        if (extra) {
            values.add("255");
        }
        return new Db2Types.Row(id, false, null, values, null);
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Db2Types.Column> columns, List<Db2Types.Row> rows) {
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        id, name, TableTypes.State.READY, true, null, null, "a".repeat(32)),
                columns,
                rows,
                null,
                Map.of(),
                true);
    }

    @Test
    void byteColumnsUseFirstCaseInsensitiveMatchAndReturnOwnedKeyBytes() {
        var memory =
                new MemoryTables()
                        .add(
                                TablesContract.BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                BuildTactKeys.TACT_KEY_FILE_DATA_ID,
                                                "TactKey",
                                                columns("Key", 16, true),
                                                List.of(row(7, 1, 16, true))),
                                        table(
                                                BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID,
                                                "TactKeyLookup",
                                                columns("TACTID", 8, false),
                                                List.of(row(7, 9, 8, false)))));
        try (var handle =
                memory.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var keys =
                    BuildTactKeys.load(handle, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            byte[] expected = new byte[16];
            expected[0] = 1;
            assertArrayEquals(expected, keys.find(9));
            byte[] mutated = keys.find(9);
            mutated[0] = 99;
            assertArrayEquals(expected, keys.find(9));
            assertEquals(1, keys.count());
            assertEquals(List.of("0000000000000009"), keys.names());
            assertEquals(64, keys.fingerprintOf(keys.names().getFirst()).length());
        }
    }

    @Test
    void conflictsZerosAndMalformedRowsNeverBecomeUsableKeys() {
        var memory =
                new MemoryTables()
                        .add(
                                TablesContract.BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                BuildTactKeys.TACT_KEY_FILE_DATA_ID,
                                                "TactKey",
                                                columns("Key", 16, false),
                                                List.of(
                                                        row(1, 1, 16, false),
                                                        row(2, 0, 16, false),
                                                        row(3, 2, 16, false),
                                                        row(4, 256, 16, false))),
                                        table(
                                                BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID,
                                                "TactKeyLookup",
                                                columns("TACTID", 8, false),
                                                List.of(
                                                        row(1, 9, 8, false),
                                                        row(2, 8, 8, false),
                                                        row(3, 9, 8, false),
                                                        row(4, 7, 8, false)))));
        try (var handle =
                memory.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var keys =
                    BuildTactKeys.load(handle, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            assertEquals(0, keys.count());
            assertEquals(1, keys.skippedZero());
            assertEquals(1, keys.malformed());
            assertEquals(0, keys.unmatched());
            assertEquals(List.of("0000000000000009"), keys.conflicts());
            assertNull(keys.find(9));
        }
    }

    private static com.vibeminer.formats.hotfix.HotfixRowDecoder.OptionalKey block(
            long name, int first) {
        byte[] key = new byte[16];
        key[0] = (byte) first;
        return new com.vibeminer.formats.hotfix.HotfixRowDecoder.OptionalKey(name, key);
    }

    private static MemoryTables keyTables(List<Db2Types.Row> keys, List<Db2Types.Row> lookups) {
        return new MemoryTables()
                .add(
                        TablesContract.BUILD,
                        "enUS",
                        List.of(
                                table(
                                        BuildTactKeys.TACT_KEY_FILE_DATA_ID,
                                        "TactKey",
                                        columns("Key", 16, false),
                                        keys),
                                table(
                                        BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID,
                                        "TactKeyLookup",
                                        columns("TACTID", 8, false),
                                        lookups)));
    }

    private static BuildTactKeys.KeySet load(MemoryTables memory) {
        try (var handle =
                memory.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            return BuildTactKeys.load(handle, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                    .join();
        }
    }

    @Test
    void deliveredKeysJoinUnderTheSameConflictRule() {
        // Build key 9 = 1; build rows 3 and 4 make name 8 a build conflict.
        var found =
                load(
                        keyTables(
                                List.of(
                                        row(7, 1, 16, false),
                                        row(3, 1, 16, false),
                                        row(4, 2, 16, false)),
                                List.of(
                                        row(7, 9, 8, false),
                                        row(3, 8, 8, false),
                                        row(4, 8, 8, false))));
        assertEquals(List.of("0000000000000008"), found.conflicts());
        assertSame(found, BuildTactKeys.withDelivered(found, List.of()));

        var identical = BuildTactKeys.withDelivered(found, List.of(block(9, 1), block(10, 5)));
        assertEquals(List.of("0000000000000009", "000000000000000A"), identical.names());
        assertEquals(5, identical.find(10)[0]);
        assertEquals(List.of("0000000000000008"), identical.conflicts());

        var differing = BuildTactKeys.withDelivered(found, List.of(block(9, 2)));
        assertNull(differing.find(9), "a different key under a build name is dropped");
        assertEquals(
                List.of("0000000000000008", "0000000000000009"),
                differing.conflicts(),
                "and listed, as the join lists it");

        var withinCache = BuildTactKeys.withDelivered(found, List.of(block(11, 1), block(11, 2)));
        assertNull(withinCache.find(11));
        assertEquals(
                List.of("0000000000000008", "000000000000000B"),
                withinCache.conflicts(),
                "two differing blocks for one name conflict and are listed");

        var zeroFirst = BuildTactKeys.withDelivered(found, List.of(block(12, 0), block(12, 6)));
        assertEquals(6, zeroFirst.find(12)[0], "a zero block does not drop a kept key");
        assertEquals(found.skippedZero() + 1, zeroFirst.skippedZero());

        var alreadyConflicting = BuildTactKeys.withDelivered(found, List.of(block(8, 1)));
        assertNull(alreadyConflicting.find(8), "a name in conflict stays out");
        assertEquals(List.of("0000000000000008"), alreadyConflicting.conflicts());
    }

    @Test
    void deliveredKeysDoNotExtendAKeySetWhoseTablesWereNotRead() {
        var memory =
                new MemoryTables()
                        .add(
                                TablesContract.BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                BuildTactKeys.TACT_KEY_FILE_DATA_ID,
                                                "TactKey",
                                                columns("Key", 16, false),
                                                List.of(row(7, 1, 16, false)))));
        var found = load(memory);
        assertTrue(found.lookupTable().outcome() != BuildTactKeys.Outcome.READ);
        assertSame(found, BuildTactKeys.withDelivered(found, List.of(block(10, 5))));
    }
}
