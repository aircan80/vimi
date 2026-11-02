package com.vibeminer.concepts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Re-indexing a changed source must also select the new source's column ordinal. */
final class SchemaChangeRelationTest {
    @Test
    void staleBuildRetryRefreshesSchemaBeforeReadingReorderedRows() {
        var pages = new AtomicInteger();
        var schemas = new AtomicInteger();
        var oldColumns =
                List.of(
                        new Db2Types.Column("Parent", "integer", false, null, null),
                        new Db2Types.Column("Other", "integer", false, null, null));
        var newColumns = List.of(oldColumns.get(1), oldColumns.get(0));
        var oldEntry =
                new TableTypes.Entry(
                        1, "Links", TableTypes.State.READY, true, null, null, "a".repeat(32));
        var newEntry =
                new TableTypes.Entry(
                        1, "Links", TableTypes.State.READY, true, null, null, "b".repeat(32));
        BuildTables tables =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("schema")) {
                                        schemas.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.SchemaResult(
                                                        pages.get() < 2 ? oldEntry : newEntry,
                                                        pages.get() < 2 ? oldColumns : newColumns,
                                                        null));
                                    }
                                    if (method.getName().equals("page")) {
                                        int number = pages.incrementAndGet();
                                        if (number == 2) {
                                            return CompletableFuture.completedFuture(
                                                    new TableTypes.Page(
                                                            newEntry,
                                                            TableTypes.PageState.STALE,
                                                            List.of(),
                                                            null,
                                                            "new",
                                                            0,
                                                            null,
                                                            null));
                                        }
                                        var row =
                                                new Db2Types.Row(
                                                        7,
                                                        false,
                                                        null,
                                                        number == 1
                                                                ? List.of("42", "99")
                                                                : List.of("99", "42"),
                                                        null);
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.Page(
                                                        number == 1 ? oldEntry : newEntry,
                                                        TableTypes.PageState.OK,
                                                        List.of(new TableTypes.Row(0, row)),
                                                        number == 1 ? 0L : null,
                                                        number == 1 ? "old" : "new",
                                                        1,
                                                        null,
                                                        null));
                                    }
                                    throw new AssertionError(
                                            "Unexpected method " + method.getName());
                                });
        var indexes = new RelationIndexes(tables);
        var result =
                indexes.lookup(
                                "Links",
                                "Parent",
                                42,
                                10,
                                TableTypes.Priority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertEquals(RelationIndexes.State.OK, result.state());
        assertEquals(1, result.matches());
        assertEquals(7, result.rows().getFirst().row().id());
        assertEquals("new", result.identity());
        assertEquals(2, schemas.get());
        assertEquals(1, indexes.entries());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sourceChangeBeforeFirstPageRefreshesColumnSelection(boolean layoutOnly) {
        var schemas = new AtomicInteger();
        var oldEntry =
                new TableTypes.Entry(
                        1,
                        "Links",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(1, 1, 0, 0, "WDC5", "old"),
                        "a".repeat(32));
        var newEntry =
                new TableTypes.Entry(
                        1,
                        "Links",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(1, 1, 0, 0, "WDC5", "new"),
                        layoutOnly ? "a".repeat(32) : "b".repeat(32));
        var oldColumns =
                List.of(
                        new Db2Types.Column("Parent", "integer", false, null, null),
                        new Db2Types.Column("Other", "integer", false, null, null));
        var newColumns = List.of(oldColumns.get(1), oldColumns.get(0));
        BuildTables tables =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("schema")) {
                                        boolean first = schemas.incrementAndGet() == 1;
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.SchemaResult(
                                                        first ? oldEntry : newEntry,
                                                        first ? oldColumns : newColumns,
                                                        null));
                                    }
                                    if (method.getName().equals("page")) {
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.Page(
                                                        newEntry,
                                                        TableTypes.PageState.OK,
                                                        List.of(
                                                                new TableTypes.Row(
                                                                        0,
                                                                        new Db2Types.Row(
                                                                                7,
                                                                                false,
                                                                                null,
                                                                                List.of("99", "42"),
                                                                                null))),
                                                        null,
                                                        "new",
                                                        1,
                                                        null,
                                                        null));
                                    }
                                    throw new AssertionError(method.getName());
                                });
        var indexes = new RelationIndexes(tables);
        var result =
                indexes.lookup(
                                "Links",
                                "Parent",
                                42,
                                10,
                                TableTypes.Priority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertEquals(RelationIndexes.State.OK, result.state());
        assertEquals(1, result.matches());
        assertEquals(2, schemas.get());
        assertEquals(1, indexes.entries());
    }
}
