package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class TableQueriesTest {
    @Test
    void canonicalIntegerAndUnsigned64FiltersNeverRoundOrWrap() {
        assertEquals(
                "18446744073709551615",
                TableQueries.parseInteger("18446744073709551615").toString());
        for (String value : List.of("+1", "01", "-0", " 1", "1.0", "123456789012345678901")) {
            assertNull(TableQueries.parseInteger(value));
        }
        var columns = List.of(new Db2Types.Column("Value", "integer", false, null, null));
        var filter =
                TableQueries.compileFilters(
                        columns,
                        null,
                        List.of(
                                new TableTypes.Filter(
                                        "Value",
                                        TableTypes.FilterOp.EQUALS,
                                        "18446744073709551615",
                                        null)));
        assertTrue(
                filter.matches()
                        .test(
                                new Db2Types.Row(
                                        1, false, null, List.of("18446744073709551615"), null)));
        assertFalse(
                filter.matches()
                        .test(
                                new Db2Types.Row(
                                        1, false, null, List.of("18446744073709551614"), null)));
    }

    @Test
    void arraysMatchAnyElementButRawAndTypedTextIssuesNeverBecomeKeys() {
        var columns =
                List.of(
                        new Db2Types.Column("Name[0]", "string", false, null, null),
                        new Db2Types.Column("Name[1]", "string", false, null, null));
        var filter =
                TableQueries.compileFilters(
                        columns,
                        null,
                        List.of(
                                new TableTypes.Filter(
                                        "Name", TableTypes.FilterOp.EQUALS, "wanted", null)));
        assertTrue(
                filter.matches()
                        .test(new Db2Types.Row(1, false, null, List.of("", "wanted"), null)));
        assertFalse(
                filter.matches()
                        .test(
                                new Db2Types.Row(
                                        1,
                                        false,
                                        null,
                                        List.of("", "wanted"),
                                        List.of(
                                                new Db2Types.TextIssue(
                                                        1, "DB2_TEXT_ENCODING_INVALID", "ff")))));
        var raw =
                List.of(
                        new Db2Types.Column(
                                "Value",
                                "integer",
                                false,
                                new Db2Types.Interpretation("raw", 32, false, 8, false, "warning"),
                                null));
        assertEquals(
                "TABLE_FILTER_RAW",
                TableQueries.compileFilters(
                                raw,
                                null,
                                List.of(
                                        new TableTypes.Filter(
                                                "Value", TableTypes.FilterOp.EQUALS, "1", null)))
                        .code());
    }

    @Test
    void filteredPageContinuesAfterScanBudgetEvenWithNoMatches() {
        var entry = new TableTypes.Entry(1, "One", TableTypes.State.READY, true, null, null, null);
        var rows =
                List.of(
                        new Db2Types.Row(1, false, null, List.of("1"), null),
                        new Db2Types.Row(2, false, null, List.of("2"), null),
                        new Db2Types.Row(3, false, null, List.of("3"), null));
        var page =
                TableQueries.page(
                                entry,
                                "identity",
                                new TableTypes.PageRequest(
                                        TableTypes.Ref.id(1),
                                        -1,
                                        50,
                                        List.of(
                                                new TableTypes.Filter(
                                                        "Value",
                                                        TableTypes.FilterOp.EQUALS,
                                                        "99",
                                                        null)),
                                        null),
                                rows.size(),
                                (first, count, token) ->
                                        CompletableFuture.completedFuture(
                                                rows.subList((int) first, (int) first + count)),
                                List.of(new Db2Types.Column("Value", "integer", false, null, null)),
                                null,
                                2,
                                Cancellation.NONE)
                        .join();
        assertTrue(page.rows().isEmpty());
        assertEquals(2, page.scanned());
        assertEquals(1L, page.nextAfter());
    }

    @Test
    void foreignKeysFollowAndReferencedByUseExactDeclaredColumns() {
        var sourceSchema =
                TableSchema.fromBlock(
                        Db2Fixture.definition(
                                        "int ID\nint<Target::ID> Value",
                                        "$id,noninline$ID<u32>\nValue<u32>")
                                .blocks()
                                .getFirst(),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US);
        var targetSchema =
                TableSchema.fromBlock(
                        Db2Fixture.definition("int ID", "$id,noninline$ID<u32>")
                                .blocks()
                                .getFirst(),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US);
        var source =
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                100,
                                "Source",
                                TableTypes.State.READY,
                                true,
                                null,
                                null,
                                "1".repeat(32)),
                        List.of(
                                new Db2Types.Column("ID", "integer", true, null, null),
                                new Db2Types.Column("Value", "integer", false, null, null)),
                        List.of(new Db2Types.Row(7, false, null, List.of("7", "9"), null)),
                        sourceSchema,
                        Map.of(),
                        false);
        var target =
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                101,
                                "Target",
                                TableTypes.State.READY,
                                true,
                                null,
                                null,
                                "2".repeat(32)),
                        List.of(new Db2Types.Column("ID", "integer", true, null, null)),
                        List.of(new Db2Types.Row(9, false, null, List.of("9"), null)),
                        targetSchema,
                        Map.of(),
                        false);
        var tables = new MemoryTables().add(TablesContract.BUILD, "enUS", List.of(source, target));
        try (var handle =
                tables.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var followed =
                    handle.follow(
                                    new TableTypes.FollowRequest(
                                            TableTypes.Ref.id(100), 7, "Value"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.FollowOutcome.FOUND, followed.outcome());
            assertEquals(9, followed.rows().getFirst().row().id());
            var references =
                    handle.referencedBy(
                                    new TableTypes.ReferencesRequest(TableTypes.Ref.id(101), 9),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.ReferencesOutcome.SEARCHED, references.outcome());
            assertEquals(List.of(7L), references.sources().getFirst().rowIds());
            assertEquals(
                    TableTypes.ReferenceScope.COMPLETE, references.sources().getFirst().scope());
        }
    }
}
