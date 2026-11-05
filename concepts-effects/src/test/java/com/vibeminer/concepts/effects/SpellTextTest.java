package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SpellTextTest {
    private static SpellText.Amount amount(String value) {
        return new SpellText.Amount(
                new BigDecimal(value),
                List.of(new SpellTexts.Source("SpellEffect", 400, "EffectBasePointsF", value)),
                null);
    }

    @Test
    void fixedIntegralAmountsUseMagnitudeWithLiteralPercentAndNoLegacyIncrement() {
        var result =
                SpellText.project(
                        100,
                        "Increases damage by $s1% and reduces cost by $s2%.",
                        Map.of(0, amount("5.0"), 1, amount("-10")),
                        List.of());
        assertEquals("Increases damage by 5% and reduces cost by 10%.", result.display());
        assertEquals(SpellTexts.State.RESOLVED, result.state());
        assertEquals(0, result.substitutions().getFirst().effectIndex());
        assertEquals("5.0", result.substitutions().getFirst().sources().getFirst().value());
    }

    @Test
    void unknownDurationCrossReferenceAndDecimalValuesNeverAppearAsRawTokens() {
        var result =
                SpellText.project(
                        100,
                        "$s1% for $d; another spell is $123s1%.",
                        Map.of(0, amount("1.5")),
                        List.of());
        assertEquals("?% for ?; another spell is ?%.", result.display());
        assertEquals(SpellTexts.State.UNRESOLVED, result.state());
        assertFalse(result.display().contains("$"));
        assertEquals("$s1% for $d; another spell is $123s1%.", result.raw());
    }

    @Test
    void expressionsConditionalsAndMarkupKeepTheSurroundingProse() {
        var amounts = Map.of(0, amount("5"));
        var cases =
                Map.of(
                        "Deals ${$s1*2} damage.", "Deals ? damage.",
                        "Uses $/10;123s1 points.", "Uses ? points.",
                        "Grants $?a123[yes][no] to you.", "Grants ? to you.",
                        "Grants $?s1[A]?s2[B][C] now.", "Grants ? now.",
                        "Gain |cffffff00$s1|r rage.", "Gain 5 rage.",
                        "Line one|nLine two || pipe", "Line one\nLine two | pipe",
                        "Hit $@spelldesc12345 then $SP more.", "Hit ? then ? more.",
                        "Cost $<cost> mana and $d.", "Cost ? mana and ?.");
        cases.forEach(
                (raw, expected) -> {
                    var result = SpellText.project(100, raw, amounts, List.of());
                    assertEquals(expected, result.display(), raw);
                    assertEquals(raw, result.raw());
                });
        var unresolved = SpellText.project(100, "Grants $?a123[yes][no].", amounts, List.of());
        assertEquals(SpellTexts.State.UNRESOLVED, unresolved.state());
        assertEquals("$?a123[yes][no]", unresolved.substitutions().getFirst().token());
        assertEquals(
                "Conditional client text depends on the character",
                unresolved.substitutions().getFirst().issue());
        var markup = SpellText.project(100, "|cffffff00$s1|r%", amounts, List.of());
        assertEquals(SpellTexts.State.RESOLVED, markup.state());
    }

    @Test
    void tokensAreCaseInsensitiveAndPluralAndGenderFormsAreShown() {
        var amounts = Map.of(0, amount("1"), 1, amount("3"), 3, amount("4"), 4, amount("6"));
        var result =
                SpellText.project(
                        100,
                        "$S1 $lpoint:points; then $s2 $Lpoint:points; for $ghim:her; and $s4/$S5.",
                        amounts,
                        List.of());
        assertEquals("1 point then 3 points for him/her and 4/6.", result.display());
        assertEquals(SpellTexts.State.RESOLVED, result.state());
        assertEquals(
                "Gendered text depends on the character; both forms are shown",
                result.substitutions().get(4).issue());
        var unknown = SpellText.project(100, "Lasts $d $lsecond:seconds;.", Map.of(), List.of());
        assertEquals("Lasts ? second/seconds.", unknown.display());
        assertEquals("Uses ?.", SpellText.project(100, "Uses $s6.", amounts, List.of()).display());
    }

    @Test
    void divideAndMultiplyScaleTheRecordedAmount() {
        var amounts = Map.of(0, amount("1500"), 1, amount("3"));
        var result =
                SpellText.project(
                        100,
                        "Lasts $/1000;S1 sec and hits $*2;s2 times; also $/7;s1.",
                        amounts,
                        List.of());
        assertEquals("Lasts 1.5 sec and hits 6 times; also ?.", result.display());
        assertEquals("$/1000;S1", result.substitutions().getFirst().token());
        assertEquals(0, result.substitutions().getFirst().effectIndex());
    }

    @Test
    void crossSpellTokensUseThatSpellsValues() {
        var durations = new java.util.HashMap<Long, SpellText.Value>();
        durations.put(
                123L,
                new SpellText.Value(
                        "6 sec",
                        List.of(new SpellTexts.Source("SpellDuration", 3, "Duration", "6000")),
                        null));
        var values =
                new SpellText.Values(
                        Map.of(100L, Map.of(0, amount("2")), 123L, Map.of(1, amount("9"))),
                        durations);
        var result =
                SpellText.project(
                        100, "$s1 now, $123s2 later for $123d; $124s1.", values, List.of());
        assertEquals("2 now, 9 later for 6 sec; ?.", result.display());
        assertEquals(
                "Effect amounts for spell 124 are unavailable",
                result.substitutions().get(3).issue());
        var needs = SpellText.needs(100, "$s1 $123s2 $123d $/1000;124s1 $d");
        assertEquals(java.util.Set.of(100L, 123L, 124L), needs.amounts());
        assertEquals(java.util.Set.of(100L, 123L), needs.durations());
        var many = new StringBuilder();
        for (int spell = 1; spell <= SpellText.CROSS_SPELLS + 2; spell++) {
            many.append('$').append(1000 + spell).append("s1 ");
        }
        assertEquals(
                SpellText.CROSS_SPELLS, SpellText.needs(100, many.toString()).amounts().size());
    }

    @Test
    void contextualMissingAndOversizedInputsRemainExplicit() {
        var contextual =
                new SpellText.Amount(new BigDecimal("5"), List.of(), "Character context required");
        var result = SpellText.project(100, "$s1% and $s2%", Map.of(0, contextual), List.of());
        assertEquals("?% and ?%", result.display());
        assertEquals("Character context required", result.substitutions().getFirst().issue());
        assertEquals(
                SpellTexts.State.UNAVAILABLE,
                SpellText.project(100, "x".repeat(16385), Map.of(), List.of()).state());
        assertEquals(
                null,
                SpellText.project(100, "$s1 ".repeat(257), Map.of(0, amount("5")), List.of())
                        .display());
        assertEquals(
                "Plain text", SpellText.project(100, "Plain text", Map.of(), List.of()).display());
    }
}
