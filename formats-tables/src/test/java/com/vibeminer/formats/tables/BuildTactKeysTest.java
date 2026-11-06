package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void deliveredKeysJoinUnderTheSameConflictRule() {
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
                                                List.of(row(7, 1, 16, false))),
                                        table(
                                                BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID,
                                                "TactKeyLookup",
                                                columns("TACTID", 8, false),
                                                List.of(row(7, 9, 8, false)))));
        try (var handle =
                memory.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var found =
                    BuildTactKeys.load(handle, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                            .join();
            byte[] same = found.find(9);
            byte[] other = new byte[16];
            other[0] = 2;
            byte[] fresh = new byte[16];
            fresh[3] = 5;
            assertSame(found, BuildTactKeys.withDelivered(found, Map.of()));
            var identical = BuildTactKeys.withDelivered(found, Map.of(9L, same, 10L, fresh));
            assertEquals(List.of("0000000000000009", "000000000000000A"), identical.names());
            assertArrayEquals(fresh, identical.find(10));
            assertEquals(List.of(), identical.conflicts());
            var conflicting =
                    BuildTactKeys.withDelivered(found, Map.of(9L, other, 11L, new byte[16]));
            assertNull(conflicting.find(9), "a different key under a known name is dropped");
            assertEquals(List.of("0000000000000009"), conflicting.conflicts());
            assertEquals(found.skippedZero() + 1, conflicting.skippedZero());
            assertEquals(0, conflicting.count());
        }
    }
}
