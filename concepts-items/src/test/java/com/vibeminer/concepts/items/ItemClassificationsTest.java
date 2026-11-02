package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemClassificationsTest {
    static ItemClassLabels resolve(TableTypes.State state, List<Db2Types.Row> rows) {
        var source =
                new MemoryTables()
                        .add(
                                ItemsWorld.BUILD,
                                "enUS",
                                List.of(
                                        ItemsWorld.table(1, "Item", ItemsWorld.ITEM, List.of()),
                                        ItemsWorld.table(
                                                3,
                                                "ItemClass",
                                                ItemsWorld.columns(
                                                        "ID", "ClassID", "ClassName_lang"),
                                                rows,
                                                state,
                                                Map.of()),
                                        ItemsWorld.table(
                                                4,
                                                "ItemSubClass",
                                                ItemsWorld.columns(
                                                        "ID",
                                                        "ClassID",
                                                        "SubClassID",
                                                        "DisplayName_lang"),
                                                List.of(
                                                        ItemsWorld.row(
                                                                999, "999", "2", "7", "Sword")))));
        try (var tables =
                source.forBuild(ItemsWorld.BUILD, "enUS", ItemsWorld.TOKEN).join().tables()) {
            return ItemClassifications.resolve(
                    tables,
                    new ItemSummary(
                            1,
                            false,
                            null,
                            new ItemName(ItemNameState.VALID, "Blade"),
                            List.of(
                                    new ItemSourceValue("Item", "ClassID", 1, "2"),
                                    new ItemSourceValue("Item", "SubclassID", 2, "7")),
                            0L,
                            null,
                            ItemRowState.FOUND,
                            null),
                    200000,
                    ItemsWorld.PRIORITY,
                    ItemsWorld.TOKEN);
        }
    }

    @Test
    void labelsJoinSemanticColumnsNotRowIds() {
        var result =
                resolve(
                        TableTypes.State.READY,
                        List.of(
                                ItemsWorld.row(999, "999", "2", "Weapon"),
                                ItemsWorld.row(2, "2", "1", "Wrong")));
        assertEquals(ItemClassLabelState.READY, result.itemClass().state());
        assertEquals("Weapon", result.itemClass().text());
        assertEquals(999, result.itemClass().target().row().id());
        assertEquals("Sword", result.subclass().text());
        assertEquals(2, result.subclass().sourceKeys().size());
    }

    @Test
    void duplicateCandidatesAreAmbiguousEvenSameText() {
        var result =
                resolve(
                        TableTypes.State.READY,
                        List.of(
                                ItemsWorld.row(1, "1", "2", "Weapon"),
                                ItemsWorld.row(2, "2", "2", "Weapon")));
        assertEquals(ItemClassLabelState.AMBIGUOUS, result.itemClass().state());
        assertNull(result.itemClass().text());
        assertEquals(2L, result.itemClass().candidates());
    }

    @Test
    void partialCandidateAndAbsenceAreNeverCertain() {
        assertEquals(
                ItemClassLabelState.INCOMPLETE_TARGET,
                resolve(TableTypes.State.PARTIAL, List.of(ItemsWorld.row(9, "9", "2", "Weapon")))
                        .itemClass()
                        .state());
        assertEquals(
                ItemClassLabelState.INCOMPLETE_TARGET,
                resolve(TableTypes.State.PARTIAL, List.of()).itemClass().state());
        assertEquals(
                ItemClassLabelState.NOT_FOUND,
                resolve(TableTypes.State.READY, List.of()).itemClass().state());
    }

    @Test
    void textIssuesEmptyAndTooLongHaveTypedStates() {
        assertEquals(
                ItemClassLabelState.EMPTY_TEXT,
                resolve(TableTypes.State.READY, List.of(ItemsWorld.row(9, "9", "2", "")))
                        .itemClass()
                        .state());
        var invalid =
                new Db2Types.Row(
                        9,
                        false,
                        null,
                        List.of("9", "2", "replacement"),
                        List.of(new Db2Types.TextIssue(2, "DB2_TEXT_UTF8", "FF")));
        assertEquals(
                ItemClassLabelState.INVALID_TEXT,
                resolve(TableTypes.State.READY, List.of(invalid)).itemClass().state());
        var huge =
                resolve(
                                TableTypes.State.READY,
                                List.of(ItemsWorld.row(9, "9", "2", "a".repeat(4097))))
                        .itemClass();
        assertEquals(ItemClassLabelState.UNSUPPORTED, huge.state());
        assertEquals(ItemClassifications.TEXT_LIMIT_CODE, huge.code());
    }
}
