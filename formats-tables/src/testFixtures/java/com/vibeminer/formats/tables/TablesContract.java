package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shared observable contract for independent fake and real Files/definitions/generated composition.
 */
public interface TablesContract {
    BuildRef BUILD = new BuildRef("wow", "a".repeat(32), "b".repeat(32));

    Tables create();

    @Test
    default void rowsSchemaExactFiltersStaleCursorAndLifecycle() {
        var tables = create();
        var open = tables.forBuild(BUILD, "enUS", Cancellation.NONE).join();
        var handle = open.tables();
        assertNotNull(handle);
        try {
            var schema =
                    handle.schema(
                                    TableTypes.Ref.forName("numbers"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.State.READY, schema.table().state());
            assertEquals("Value", schema.columns().get(1).name());
            var page =
                    handle.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PageState.OK, page.state());
            assertEquals(List.of("7", "9"), page.rows().getFirst().row().values());
            assertEquals(2, page.rows().size());
            assertFalse(page.rows().getFirst().row().isCopy());
            assertEquals(
                    TableTypes.RowOutcome.FOUND,
                    handle.get(
                                    TableTypes.Ref.id(100),
                                    8,
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .outcome());
            assertEquals(
                    TableTypes.RowOutcome.ABSENT,
                    handle.get(
                                    TableTypes.Ref.id(100),
                                    999,
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .outcome());
            var stale =
                    handle.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(100), -1, 1, List.of(), "old"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PageState.STALE, stale.state());
            var filtered =
                    handle.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(100),
                                            -1,
                                            50,
                                            List.of(
                                                    new TableTypes.Filter(
                                                            "Value",
                                                            TableTypes.FilterOp.EQUALS,
                                                            "9",
                                                            null)),
                                            null),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(2, filtered.rows().size());
            var bad =
                    handle.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.id(100),
                                            -1,
                                            50,
                                            List.of(
                                                    new TableTypes.Filter(
                                                            "unknown",
                                                            TableTypes.FilterOp.EQUALS,
                                                            "9",
                                                            null)),
                                            null),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PageState.BAD_FILTER, bad.state());
            assertEquals("TABLE_FILTER_COLUMN", bad.code());
        } finally {
            handle.close();
        }
        assertThrows(
                IllegalStateException.class,
                () ->
                        handle.check(
                                TableTypes.Ref.id(100),
                                TableTypes.Priority.INTERACTIVE,
                                Cancellation.NONE));
    }
}
