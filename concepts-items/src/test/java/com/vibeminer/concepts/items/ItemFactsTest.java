package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.db2.Db2Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemFactsTest {
    private static ItemFact fact(ItemFactSet set, String key) {
        return set.facts().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void fixedKeysAndLiteralCellsIncludeSigned64Unsigned64AndNegativeZero() {
        var columns =
                ItemsWorld.columns(
                        "ID",
                        "AllowableRaces[0]",
                        "AllowableRaces[1]",
                        "DmgVariance",
                        "OverallQualityID");
        var row = ItemsWorld.row(8, "8", "18446744073709551615", "-9223372036854775808", "-0", "4");
        var set =
                ItemFacts.project(
                        List.of(), columns, ItemsWorld.row(8), row, ItemSparseState.PRESENT);
        assertEquals(48, set.facts().size());
        assertEquals(
                List.of("18446744073709551615", "-9223372036854775808"),
                fact(set, "allowableRaces").values().stream().map(ItemFactValue::value).toList());
        assertEquals("-0", fact(set, "dmgVariance").values().getFirst().value());
        assertEquals("Epic", fact(set, "quality").values().getFirst().label());
        assertEquals(8, fact(set, "quality").values().getFirst().rowId());
    }

    @Test
    void agreementsNeverOverwriteEitherSource() {
        var c = ItemsWorld.columns("InventoryType");
        var agree =
                ItemFacts.project(
                        c,
                        c,
                        ItemsWorld.row(1, "13"),
                        ItemsWorld.row(1, "13"),
                        ItemSparseState.PRESENT);
        assertEquals(ItemFactAgreement.AGREE, fact(agree, "inventoryType").agreement());
        var disagree =
                ItemFacts.project(
                        c,
                        c,
                        ItemsWorld.row(1, "13"),
                        ItemsWorld.row(1, "17"),
                        ItemSparseState.PRESENT);
        assertEquals(ItemFactAgreement.DISAGREE, fact(disagree, "inventoryType").agreement());
        assertEquals(
                List.of("13", "17"),
                fact(disagree, "inventoryType").values().stream()
                        .map(ItemFactValue::value)
                        .toList());
    }

    @Test
    void malformedTextRetainsTypedEvidenceWithoutReplacementLabel() {
        var c = ItemsWorld.columns("Description_lang", "OverallQualityID");
        var row =
                new Db2Types.Row(
                        1,
                        false,
                        null,
                        List.of("bad", "4"),
                        List.of(
                                new Db2Types.TextIssue(0, "DB2_TEXT_UTF8", "FF"),
                                new Db2Types.TextIssue(1, "DB2_TEXT_UTF8", "FF")));
        var set = ItemFacts.project(List.of(), c, ItemsWorld.row(1), row, ItemSparseState.PRESENT);
        assertEquals("", fact(set, "description").values().getFirst().value());
        assertNull(fact(set, "quality").values().getFirst().label());
    }

    @Test
    void unsupportedAndUnknownSparseRemainDistinct() {
        var c = ItemsWorld.columns("OverallQualityID");
        var unknown =
                ItemFacts.project(
                        List.of(), null, ItemsWorld.row(1), null, ItemSparseState.UNKNOWN);
        var missing =
                ItemFacts.project(List.of(), c, ItemsWorld.row(1), null, ItemSparseState.MISSING);
        assertEquals(ItemFactState.UNKNOWN, fact(unknown, "quality").state());
        assertEquals(ItemFactState.MISSING, fact(missing, "quality").state());
        assertEquals(ItemFactState.UNSUPPORTED, fact(missing, "description").state());
    }

    @Test
    void moreThanSixteenValuesRefusesWholeProjection() {
        var columns = new ArrayList<Db2Types.Column>();
        var values = new ArrayList<String>();
        for (int i = 0; i < 17; i++) {
            columns.add(new Db2Types.Column("Flags[" + i + "]", "integer", false, null, null));
            values.add(Integer.toString(i));
        }
        var set =
                ItemFacts.project(
                        columns,
                        List.of(),
                        new Db2Types.Row(1, false, null, values, null),
                        null,
                        ItemSparseState.MISSING);
        assertEquals(ItemFactsState.LIMIT_EXCEEDED, set.state());
        assertTrue(set.facts().isEmpty());
        assertEquals(ItemFacts.LIMIT_CODE, set.code());
    }

    @Test
    void classificationSourceWidthsRejectNoncanonicalAndOutOfRange() {
        assertEquals(
                ItemClassLabelState.KEY_OUT_OF_RANGE,
                ItemClassifications.sourceKey(
                                List.of(new ItemSourceValue("Item", "ClassID", 0, "128")), false)
                        .state());
        assertEquals(
                ItemClassLabelState.FIELD_UNAVAILABLE,
                ItemClassifications.sourceKey(
                                List.of(new ItemSourceValue("Item", "ClassID", 0, "02")), false)
                        .state());
        assertEquals(
                ItemClassLabelState.KEY_OUT_OF_RANGE,
                ItemClassifications.sourceKey(
                                List.of(
                                        new ItemSourceValue("Item", "ClassID", 0, "2"),
                                        new ItemSourceValue("Item", "SubclassID", 1, "255")),
                                true)
                        .state());
    }
}
