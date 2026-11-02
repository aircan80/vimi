package com.vibeminer.concepts.items;

import static com.vibeminer.concepts.items.ItemsWorld.BUILD;
import static com.vibeminer.concepts.items.ItemsWorld.ITEM;
import static com.vibeminer.concepts.items.ItemsWorld.PRIORITY;
import static com.vibeminer.concepts.items.ItemsWorld.SPARSE;
import static com.vibeminer.concepts.items.ItemsWorld.TOKEN;
import static com.vibeminer.concepts.items.ItemsWorld.columns;
import static com.vibeminer.concepts.items.ItemsWorld.data;
import static com.vibeminer.concepts.items.ItemsWorld.open;
import static com.vibeminer.concepts.items.ItemsWorld.row;
import static com.vibeminer.concepts.items.ItemsWorld.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemsServiceTest {
    @Test
    void anchoredItemOrderIncludesZeroMaxAndCopiesButNeverSparseOnly() {
        var copy =
                new Db2Types.Row(
                        0xffffffffL, true, 0L, List.of("4294967295", "2", "7", "13", "0"), null);
        try (var h =
                open(
                        data(
                                List.of(row(0, "0", "2", "7", "13", "0"), copy),
                                List.of(
                                        row(0, "0", "Blade", "4", "500", "13"),
                                        row(99, "99", "Orphan", "4", "500", "13"))))) {
            var page = h.list(new ItemQuery(), PRIORITY, TOKEN).join();
            assertEquals(
                    List.of(0L, 0xffffffffL),
                    page.entries().stream().map(ItemSummary::id).toList());
            assertEquals(ItemNameState.MISSING_SPARSE, page.entries().get(1).name().state());
            assertTrue(page.entries().get(1).isCopy());
            assertEquals(0L, page.entries().get(1).copySourceId());
            assertEquals(ItemResultState.ABSENT, h.get(99, PRIORITY, TOKEN).join().state());
        }
    }

    @Test
    void literalSearchKeepsWhitespaceAndUnicodePolicyAndDoesNotOpenFiles() {
        var files = new MemoryFiles();
        var source =
                data(
                        List.of(
                                row(1, "1", "2", "7", "13", "123"),
                                row(2, "2", "2", "7", "13", "0"),
                                row(3, "3", "2", "7", "13", "0")),
                        List.of(
                                row(1, "1", "Straße", "4", "500", "13"),
                                row(2, "2", "   ", "4", "500", "13"),
                                row(3, "3", "", "4", "500", "13")));
        try (var h =
                new ItemsService(source, files, new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(BUILD, "enUS", TOKEN)
                        .join()
                        .items()) {
            assertEquals(
                    0, h.list(new ItemQuery("STRASSE"), PRIORITY, TOKEN).join().entries().size());
            assertEquals(
                    1, h.list(new ItemQuery("straße"), PRIORITY, TOKEN).join().entries().size());
            assertEquals(1, h.list(new ItemQuery(" "), PRIORITY, TOKEN).join().entries().size());
        }
    }

    @Test
    void searchKeepsProtectedItemButOmitsProvenAbsent() {
        var source =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Item",
                                                ITEM,
                                                List.of(),
                                                TableTypes.State.PARTIAL,
                                                Map.of(1L, "KEY")),
                                        table(
                                                2,
                                                "ItemSparse",
                                                SPARSE,
                                                List.of(
                                                        row(1, "1", "Sword", "1", "1", "1"),
                                                        row(2, "2", "Sword", "1", "1", "1")))));
        try (var h = open(source)) {
            var page = h.list(new ItemQuery("Sword"), PRIORITY, TOKEN).join();
            assertEquals(2, page.entries().size());
            assertEquals(ItemRowState.PROTECTED, page.entries().getFirst().item());
            assertEquals("KEY", page.entries().getFirst().itemKey());
            assertEquals(ItemResultState.PROTECTED, h.get(1, PRIORITY, TOKEN).join().state());
        }
    }

    @Test
    void badNameIsTypedAndNoGuessing() {
        var badColumns = columns("ID", "Other_lang");
        var source =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Item",
                                                ITEM,
                                                List.of(row(1, "1", "2", "7", "13", "0"))),
                                        table(
                                                2,
                                                "ItemSparse",
                                                badColumns,
                                                List.of(row(1, "1", "Sword")))));
        try (var h = open(source)) {
            assertEquals(
                    ItemsPageState.NAME_UNSUPPORTED,
                    h.list(new ItemQuery(), PRIORITY, TOKEN).join().state());
            assertEquals(
                    ItemResultState.NAME_UNSUPPORTED, h.get(1, PRIORITY, TOKEN).join().state());
        }
    }

    @Test
    void duplicatePlainColumnIsAmbiguous() {
        var c = new java.util.ArrayList<>(ITEM);
        c.add(ITEM.get(1));
        var source =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Item",
                                                c,
                                                List.of(row(1, "1", "2", "7", "13", "0", "2"))),
                                        table(2, "ItemSparse", SPARSE, List.of())));
        try (var h = open(source)) {
            assertEquals(
                    ItemsPageState.SCHEMA_AMBIGUOUS,
                    h.list(new ItemQuery(), PRIORITY, TOKEN).join().state());
        }
    }

    @Test
    void cursorsBindLimitQueryLocaleAndSourceIdentity() {
        try (var h =
                open(
                        data(
                                List.of(
                                        row(1, "1", "2", "7", "13", "0"),
                                        row(2, "2", "2", "7", "13", "0")),
                                List.of()))) {
            var first = h.list(new ItemQuery("", null, 1, null), PRIORITY, TOKEN).join();
            assertNotNull(first.next());
            var next = h.list(new ItemQuery("", null, 1, first.next()), PRIORITY, TOKEN).join();
            assertEquals(2, next.entries().getFirst().id());
            assertNull(next.next());
            assertEquals(
                    ItemsPageState.INVALID_CURSOR,
                    h.list(new ItemQuery("", null, 2, first.next()), PRIORITY, TOKEN)
                            .join()
                            .state());
            var cursor =
                    ItemQueries.decodeCursor(
                            first.next(),
                            ItemQueries.binding(BUILD, "enUS", new ItemQuery("", null, 1, null)));
            var stale =
                    ItemQueries.encodeCursor(
                            new ItemCursor(cursor.b(), cursor.a(), cursor.i(), "changed"));
            assertEquals(
                    ItemsPageState.STALE,
                    h.list(new ItemQuery("", null, 1, stale), PRIORITY, TOKEN).join().state());
        }
    }

    @Test
    void scanBudgetReturnsContinuationEvenWithoutMatches() {
        var source =
                data(
                        List.of(row(1, "1", "2", "7", "13", "0"), row(2, "2", "2", "7", "13", "0")),
                        List.of(
                                row(1, "1", "Apple", "1", "1", "1"),
                                row(2, "2", "Pear", "1", "1", "1")));
        try (var h =
                new ItemsService(
                                source,
                                new MemoryFiles(),
                                new BlpImages(new MemoryGeneratedStore()),
                                new ItemsOptions(1, 200000, 4000000, 16, 32, 8))
                        .forBuild(BUILD, "enUS", TOKEN)
                        .join()
                        .items()) {
            var first = h.list(new ItemQuery("Pear"), PRIORITY, TOKEN).join();
            assertTrue(first.entries().isEmpty());
            assertEquals(1, first.scanned());
            assertNotNull(first.next());
            assertEquals(
                    2,
                    h.list(new ItemQuery("Pear", null, 50, first.next()), PRIORITY, TOKEN)
                            .join()
                            .entries()
                            .getFirst()
                            .id());
        }
    }

    @Test
    void detailPreservesFactsAndNoIconReason() {
        try (var h =
                open(
                        data(
                                List.of(row(1, "1", "2", "7", "13", "0")),
                                List.of(row(1, "1", "Blade", "42", "500", "13"))))) {
            var d = h.get(1, PRIORITY, TOKEN).join().detail();
            assertEquals(ItemIconState.NO_ICON, d.icon().state());
            assertEquals(48, d.facts().facts().size());
            assertEquals(
                    "42",
                    d.facts().facts().stream()
                            .filter(f -> f.key().equals("quality"))
                            .findFirst()
                            .orElseThrow()
                            .values()
                            .getFirst()
                            .value());
            assertEquals(ItemDefaultAppearanceState.UNKNOWN, d.appearances().defaultState());
            assertEquals(1, d.icons().size());
        }
    }

    @Test
    void invalidArgumentsArePublicArgumentErrors() {
        var service =
                new ItemsService(
                        new MemoryTables(),
                        new MemoryFiles(),
                        new BlpImages(new MemoryGeneratedStore()));
        assertThrows(IllegalArgumentException.class, () -> service.forBuild(BUILD, "bad", TOKEN));
        try (var h = open(data(List.of(), List.of()))) {
            assertThrows(IllegalArgumentException.class, () -> h.get(-1, PRIORITY, TOKEN));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            h.list(
                                    new ItemQuery("", null, ItemQueries.MAX_LIMIT + 1, null),
                                    PRIORITY,
                                    TOKEN));
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> h.list(new ItemQuery(), PRIORITY, canceled));
        }
    }
}
