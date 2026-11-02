package com.vibeminer.concepts.items;

import static com.vibeminer.concepts.items.ItemsWorld.BUILD;
import static com.vibeminer.concepts.items.ItemsWorld.ITEM;
import static com.vibeminer.concepts.items.ItemsWorld.PRIORITY;
import static com.vibeminer.concepts.items.ItemsWorld.SPARSE;
import static com.vibeminer.concepts.items.ItemsWorld.TOKEN;
import static com.vibeminer.concepts.items.ItemsWorld.columns;
import static com.vibeminer.concepts.items.ItemsWorld.row;
import static com.vibeminer.concepts.items.ItemsWorld.table;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.formats.tables.MemoryTables;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemListLabelsTest {
    @Test
    void selectedLocaleSuppliesTextAndJoinsSemanticKeysRatherThanRowIds() {
        var data =
                new MemoryTables()
                        .add(
                                BUILD,
                                "deDE",
                                List.of(
                                        table(
                                                1,
                                                "Item",
                                                ITEM,
                                                List.of(row(100, "100", "4", "4", "1", "0"))),
                                        table(
                                                2,
                                                "ItemSparse",
                                                SPARSE,
                                                List.of(row(100, "100", "Helm", "4", "60", "1"))),
                                        table(
                                                3,
                                                "ItemClass",
                                                columns("ID", "ClassID", "ClassName_lang"),
                                                List.of(row(900, "900", "4", "Rüstung"))),
                                        table(
                                                4,
                                                "ItemSubClass",
                                                columns(
                                                        "ID",
                                                        "ClassID",
                                                        "SubClassID",
                                                        "DisplayName_lang"),
                                                List.of(row(901, "901", "4", "4", "Platte")))));
        var forbidden =
                (com.vibeminer.files.Files)
                        java.lang.reflect.Proxy.newProxyInstance(
                                com.vibeminer.files.Files.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.Files.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "Classification list touched media: "
                                                    + method.getName());
                                });
        try (var handle =
                new ItemsService(
                                data,
                                forbidden,
                                new com.vibeminer.formats.images.BlpImages(
                                        new com.vibeminer.generated.MemoryGeneratedStore()))
                        .forBuild(BUILD, "deDE", TOKEN)
                        .join()
                        .items()) {
            var item = handle.list(new ItemQuery(), PRIORITY, TOKEN).join().entries().getFirst();
            var labels = handle.classifications(item, PRIORITY, TOKEN).join();
            assertEquals("Rüstung", labels.itemClass().text());
            assertEquals("Platte", labels.subclass().text());
            assertEquals(900, labels.itemClass().target().row().id());
            assertEquals(labels, handle.classifications(item, PRIORITY, TOKEN).join());
        }
    }
}
