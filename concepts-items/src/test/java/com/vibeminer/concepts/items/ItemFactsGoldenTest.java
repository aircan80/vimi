package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.formats.db2.Db2Types;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemFactsGoldenTest {
    @Test
    void originalPinnedCsharpFactsAreExactlyPreserved() throws Exception {
        var c =
                ItemsWorld.columns(
                        "InventoryType",
                        "Description_lang",
                        "OverallQualityID",
                        "AllowableRaces[0]",
                        "AllowableRaces[1]",
                        "DmgVariance");
        var ic = ItemsWorld.columns("InventoryType");
        var item = ItemsWorld.row(0xffffffffL, "13");
        var source =
                ItemsWorld.row(
                        0xffffffffL,
                        "17",
                        "Literal ß",
                        "4",
                        "18446744073709551615",
                        "-9223372036854775808",
                        "-0");
        var lines = new ArrayList<String>();
        add(lines, "present", ItemFacts.project(ic, c, item, source, ItemSparseState.PRESENT));
        add(
                lines,
                "invalid",
                ItemFacts.project(
                        ic,
                        c,
                        item,
                        new Db2Types.Row(
                                source.id(),
                                false,
                                null,
                                source.values(),
                                List.of(new Db2Types.TextIssue(1, "DB2_TEXT_UTF8", "FF"))),
                        ItemSparseState.PRESENT));
        for (var state :
                List.of(
                        ItemSparseState.MISSING,
                        ItemSparseState.UNKNOWN,
                        ItemSparseState.PROTECTED)) {
            add(
                    lines,
                    pascal(state),
                    ItemFacts.project(
                            ic, state == ItemSparseState.UNKNOWN ? null : c, item, null, state));
        }
        try (var input = getClass().getResourceAsStream("/golden/facts.tsv")) {
            assertEquals(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    String.join("\n", lines) + "\n");
        }
    }

    private static String pascal(Enum<?> value) {
        return java.util.Arrays.stream(value.name().split("_"))
                .map(
                        word ->
                                word.substring(0, 1)
                                        + word.substring(1).toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining());
    }

    private static void add(List<String> rows, String name, ItemFactSet set) {
        rows.add(
                name
                        + "|"
                        + pascal(set.state())
                        + "|"
                        + pascal(set.sparse())
                        + "|"
                        + (set.code() == null ? "" : set.code()));
        for (var fact : set.facts()) {
            rows.add(fact.key() + "|" + pascal(fact.state()) + "|" + pascal(fact.agreement()));
            for (var value : fact.values()) {
                rows.add(
                        value.table()
                                + "|"
                                + value.column()
                                + "|"
                                + value.columnIndex()
                                + "|"
                                + value.rowId()
                                + "|"
                                + Base64.getEncoder()
                                        .encodeToString(
                                                value.value().getBytes(StandardCharsets.UTF_8))
                                + "|"
                                + (value.label() == null ? "" : value.label()));
            }
        }
    }
}
