package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MemoryTablesReseedTest {
    private static MemoryTables.FakeTable table(String key) {
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(1, "Test", TableTypes.State.READY, true, null, null, key),
                List.of(new Db2Types.Column("ID", "integer", true, null, null)),
                List.of(new Db2Types.Row(7, false, null, List.of("7"), null)),
                null,
                Map.of(),
                true);
    }

    @Test
    void reseedingChangesContentIdentityOnExistingAndNewHandles() {
        var build = new BuildRef("wow", "a".repeat(32), "b".repeat(32));
        var fixture = new MemoryTables().add(build, "enUS", List.of(table("1".repeat(32))));
        try (var old = fixture.forBuild(build, "enUS", Cancellation.NONE).join().tables()) {
            var request = new TableTypes.PageRequest(TableTypes.Ref.id(1));
            var before =
                    old.page(request, TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
            fixture.add(build, "enUS", List.of(table("2".repeat(32))));
            var after =
                    old.page(request, TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
            assertNotEquals(before.identity(), after.identity());
            assertEquals(
                    TableTypes.PageState.STALE,
                    old.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(1),
                                            -1,
                                            1,
                                            List.of(),
                                            before.identity()),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
            try (var fresh = fixture.forBuild(build, "enUS", Cancellation.NONE).join().tables()) {
                assertEquals(
                        after.identity(),
                        fresh.page(request, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                                .join()
                                .identity());
            }
        }
    }
}
