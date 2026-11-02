package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ItemAttributesTest {
    private static ItemFactValue value(String column, String value) {
        return new ItemFactValue("ItemSparse", column, 0, 100, value, null);
    }

    private static ItemFact fact(String key, ItemFactState state, ItemFactValue... values) {
        return new ItemFact(key, state, ItemFactAgreement.SINGLE, List.of(values));
    }

    private static ItemFactSet facts(ItemFact... facts) {
        return new ItemFactSet(
                ItemFactsState.READY, ItemSparseState.PRESENT, List.of(facts), null, null);
    }

    @Test
    void matchesExplicitArraySlotsRatherThanSourceOrder() {
        var result =
                ItemAttributes.from(
                        facts(
                                fact(
                                        "statType",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusStat[1]", "4"),
                                        value("StatModifier_bonusStat[0]", "7")),
                                fact(
                                        "statAmount",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusAmount[0]", "20"),
                                        value("StatModifier_bonusAmount[1]", "-5"))));
        assertEquals(ItemAttributes.State.READY, result.state());
        assertEquals(
                List.of("-5 Strength", "+20 Stamina"),
                result.attributes().stream().map(ItemAttributes.Attribute::text).toList());
        assertEquals(2, result.attributes().getFirst().sources().size());
        assertEquals(100, result.attributes().getFirst().sources().getFirst().rowId());
    }

    @Test
    void allocationInputsAreNotFlatStats() {
        var result =
                ItemAttributes.from(
                        facts(
                                fact(
                                        "statType",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusStat[0]", "7")),
                                fact("statAmount", ItemFactState.UNSUPPORTED),
                                fact(
                                        "statPercentEditor",
                                        ItemFactState.VALUE,
                                        value("StatPercentEditor[0]", "5000"))));
        assertEquals(ItemAttributes.State.REQUIRES_CALCULATION, result.state());
        assertTrue(result.attributes().isEmpty());
    }

    @Test
    void unknownMalformedAndMissingSlotsRemainExplicit() {
        var result =
                ItemAttributes.from(
                        facts(
                                fact(
                                        "statType",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusStat[0]", "999"),
                                        value("StatModifier_bonusStat[1]", "7"),
                                        value("StatModifier_bonusStat[2]", "4")),
                                fact(
                                        "statAmount",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusAmount[0]", "20"),
                                        value("StatModifier_bonusAmount[1]", "invalid"))));
        assertEquals(ItemAttributes.State.PARTIAL, result.state());
        assertTrue(result.attributes().isEmpty());
        assertEquals(3, result.issues().size());
    }

    @Test
    void zeroAndSentinelSlotsDoNotBecomeAttributes() {
        var result =
                ItemAttributes.from(
                        facts(
                                fact(
                                        "statType",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusStat[0]", "7"),
                                        value("StatModifier_bonusStat[1]", "-1")),
                                fact(
                                        "statAmount",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusAmount[0]", "0"),
                                        value("StatModifier_bonusAmount[1]", "99"))));
        assertEquals(ItemAttributes.State.READY, result.state());
        assertTrue(result.attributes().isEmpty());
    }

    @Test
    void ambiguousTablesDoNotSilentlySelectOne() {
        var repeated = value("StatModifier_bonusStat[0]", "7");
        var result =
                ItemAttributes.from(
                        facts(
                                fact("statType", ItemFactState.VALUE, repeated, repeated),
                                fact(
                                        "statAmount",
                                        ItemFactState.VALUE,
                                        value("StatModifier_bonusAmount[0]", "20"))));
        assertEquals(ItemAttributes.State.UNAVAILABLE, result.state());
        assertFalse(result.issues().isEmpty());
        assertEquals(ItemAttributes.State.UNAVAILABLE, ItemAttributes.from(null).state());
    }
}
