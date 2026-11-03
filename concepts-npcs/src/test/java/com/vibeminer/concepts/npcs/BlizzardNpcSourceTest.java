package com.vibeminer.concepts.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class BlizzardNpcSourceTest {
    @Test
    void partialTablePagesRemainUsableAndTextIssuesAffectOnlyTheirField() throws Exception {
        var columns =
                List.of(
                        new Db2Types.Column("Name_lang", "string", false, null, null),
                        new Db2Types.Column("Title_lang", "string", false, null, null));
        var fields =
                columns.stream()
                        .map(
                                c ->
                                        new TableSchema.SchemaColumn(
                                                c.name(),
                                                TableSchema.ColumnKind.LOCSTRING,
                                                null,
                                                null,
                                                1,
                                                null,
                                                TableSchema.KeyStorage.NONE,
                                                TableSchema.KeyStorage.NONE,
                                                true,
                                                WowLocale.parse("enUS"),
                                                null,
                                                null))
                        .toList();
        var schema =
                new TableSchema(
                        fields,
                        List.of(),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.parse("enUS"),
                        null);
        var entry =
                new TableTypes.Entry(
                        1,
                        "Creature",
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(4, 2, 1, 2, "WDC5", "test"),
                        null);
        var tables =
                (BuildTables)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("schema")) {
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.SchemaResult(
                                                        entry, columns, schema));
                                    }
                                    if (method.getName().equals("page")) {
                                        var request = (TableTypes.PageRequest) args[0];
                                        boolean first = request.after() == -1;
                                        var row =
                                                new Db2Types.Row(
                                                        first ? 1 : 2,
                                                        false,
                                                        null,
                                                        List.of(
                                                                first ? "Build name" : "Other",
                                                                first ? "" : "Invalid title"),
                                                        first
                                                                ? List.of()
                                                                : List.of(
                                                                        new Db2Types.TextIssue(
                                                                                1,
                                                                                "DB2_TEXT_ENCODING_INVALID",
                                                                                "ff")));
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.Page(
                                                        entry,
                                                        TableTypes.PageState.OK,
                                                        List.of(
                                                                new TableTypes.Row(
                                                                        first ? 0 : 1, row)),
                                                        first ? 0L : null,
                                                        "identity",
                                                        1,
                                                        null,
                                                        null));
                                    }
                                    throw new AssertionError(
                                            "Unexpected borrowed-handle operation: "
                                                    + method.getName());
                                });
        var result =
                NpcSources.blizzard(tables, "wowf", "1.60.1.70707", "enUS")
                        .loader()
                        .load(Cancellation.NONE);
        assertEquals(2, result.size());
        assertEquals("Build name", result.getFirst().name());
        assertEquals("", result.getFirst().subname());
        assertEquals("Other", result.getLast().name());
        assertNull(result.getLast().subname());
    }
}
