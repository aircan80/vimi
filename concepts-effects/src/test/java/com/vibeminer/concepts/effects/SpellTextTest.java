package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        "Deals ${$s1*$pl} damage.", "Deals ? damage.",
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
        var oversized = SpellText.project(100, "x".repeat(16385), Map.of(), List.of());
        assertEquals(SpellTexts.State.UNRESOLVED, oversized.state());
        assertEquals("x".repeat(16384) + "…", oversized.display());
        var many =
                SpellText.project(
                        100, "Hit $s1 ".repeat(257) + "end.", Map.of(0, amount("5")), List.of());
        assertTrue(many.display().startsWith("Hit 5 Hit 5 "));
        assertTrue(many.display().endsWith("Hit ? end."));
        assertEquals(SpellTexts.State.UNRESOLVED, many.state());
        assertFalse(many.display().contains("$"));
        assertEquals(
                "Plain text", SpellText.project(100, "Plain text", Map.of(), List.of()).display());
    }

    @Test
    void multiDigitIndexesAreNeverReadAsTheirFirstDigit() {
        var amounts = Map.of(0, amount("50"), 1, amount("7"));
        for (String raw :
                List.of("Deals $s10 damage.", "Deals $123s10 damage.", "Deals $S19 damage.")) {
            var result = SpellText.project(100, raw, amounts, List.of());
            assertEquals("Deals ? damage.", result.display(), raw);
            assertEquals("Effect index is unsupported", result.substitutions().getFirst().issue());
        }
        assertEquals(
                "Deals 7 damage.",
                SpellText.project(100, "Deals $s02 damage.", amounts, List.of()).display());
        var longId = SpellText.project(100, "Uses $123456789012s1 now.", amounts, List.of());
        assertEquals("Uses ? now.", longId.display());
        assertEquals("Referenced spell ID is invalid", longId.substitutions().getFirst().issue());
    }

    @Test
    void onlyPlainUnsignedScaleFactorsAreApplied() {
        var amounts = Map.of(0, amount("5"));
        for (String raw :
                List.of(
                        "A $/1E-1000000;s1 b",
                        "A $/1E-10000000;s1 b",
                        "A $/1E+99999999;s1 b",
                        "A $/1E-999999999;s1 b",
                        "A $*-2;s1 b",
                        "A $*+2;s1 b",
                        "A $/0x10;s1 b",
                        "A $*1234567890;s1 b")) {
            long started = System.nanoTime();
            var result = SpellText.project(100, raw, amounts, List.of());
            assertTrue(System.nanoTime() - started < 1_000_000_000L, raw);
            assertEquals("A ? b", result.display(), raw);
        }
        assertEquals("A 10 b", SpellText.project(100, "A $*2;s1 b", amounts, List.of()).display());
        assertEquals(
                "A 2.5 b", SpellText.project(100, "A $/2.0;s1 b", amounts, List.of()).display());
        assertEquals(
                "A ? b",
                SpellText.project(100, "A $*999999999;s1 b", amounts, List.of()).display());
    }

    @Test
    void incompleteSyntaxNeverLeaksRawMarkers() {
        var amounts = Map.of(0, amount("5"));
        var cases =
                Map.of(
                        "Open ${ expression", "Open ? expression",
                        "Open $?a[ branch", "Open ?a[ branch",
                        "Open $<name tail", "Open ?name tail",
                        "Open $/10 tail", "Open ?10 tail",
                        "Icon |Tpath tail", "Icon path tail",
                        "Link |Hspell tail", "Link spell tail",
                        "Forms $lfoo:bar:baz; tail", "Forms ? tail");
        cases.forEach(
                (raw, expected) -> {
                    var result = SpellText.project(100, raw, amounts, List.of());
                    assertEquals(expected, result.display(), raw);
                    assertFalse(result.display().contains("$"), raw);
                    assertFalse(result.display().contains("|"), raw);
                });
    }

    @Test
    void basePointsTotalPeriodicValueAndProcChance() {
        var periodic =
                new SpellText.Amount(
                        new BigDecimal("4"),
                        List.of(
                                new SpellTexts.Source(
                                        "SpellEffect", 7, "EffectAuraPeriod", "3000")),
                        null,
                        false,
                        3000L);
        var durations = new java.util.HashMap<Long, SpellText.Value>();
        durations.put(
                100L,
                new SpellText.Value(
                        "15 sec",
                        List.of(new SpellTexts.Source("SpellDuration", 1, "Duration", "15000")),
                        null,
                        15000L));
        var procs = new java.util.HashMap<Long, SpellText.Value>();
        procs.put(100L, new SpellText.Value("30", List.of(), null));
        var values =
                new SpellText.Values(
                        Map.of(100L, Map.of(0, periodic, 1, amount("10"))), durations, procs);
        var result =
                SpellText.project(
                        100,
                        "Bleeds for $o1 over $d, $m1 per tick, $M2 more; $h% chance.",
                        values,
                        List.of());
        assertEquals(
                "Bleeds for 20 over 15 sec, 4 per tick, 10 more; 30% chance.", result.display());
        assertEquals(SpellTexts.State.RESOLVED, result.state());
        assertEquals(0, result.substitutions().getFirst().effectIndex());
        var needs = SpellText.needs(100, "$o1 $m2 $h $123h");
        assertEquals(java.util.Set.of(100L), needs.amounts());
        assertEquals(java.util.Set.of(100L), needs.durations());
        assertEquals(java.util.Set.of(100L, 123L), needs.procChances());
        var notPeriodic = SpellText.project(100, "Total $o2.", values, List.of());
        assertEquals("Total ?.", notPeriodic.display());
        assertEquals("Effect is not periodic", notPeriodic.substitutions().getFirst().issue());
        var noDuration =
                SpellText.project(
                        100,
                        "Total $o1.",
                        new SpellText.Values(Map.of(100L, Map.of(0, periodic)), Map.of()),
                        List.of());
        assertTrue(
                noDuration.substitutions().getFirst().issue().startsWith("Total periodic value"));
        var partialTick = new java.util.HashMap<Long, SpellText.Value>();
        partialTick.put(100L, new SpellText.Value("10 sec", List.of(), null, 10000L));
        assertEquals(
                "Duration is not a whole number of ticks",
                SpellText.project(
                                100,
                                "Total $o1.",
                                new SpellText.Values(
                                        Map.of(100L, Map.of(0, periodic)), partialTick),
                                List.of())
                        .substitutions()
                        .getFirst()
                        .issue());
        assertEquals(
                "Chance ?%.", SpellText.project(100, "Chance $h%.", Map.of(), List.of()).display());
    }

    private static SpellText.Amount context(String value) {
        return new SpellText.Amount(
                new BigDecimal(value),
                List.of(),
                "Effect requires random, scaling or character context");
    }

    @Test
    void arithmeticExpressionsOverFixedAmountsAreEvaluated() {
        var values =
                new SpellText.Values(
                        Map.of(
                                100L,
                                Map.of(0, amount("-3000"), 1, amount("15"), 2, amount("5")),
                                123L,
                                Map.of(0, amount("40"))),
                        Map.of());
        var cases =
                Map.of(
                        "Lasts ${$m1/-1000} sec.", "Lasts 3 sec.",
                        "Gain ${$m2/10} more.", "Gain ? more.",
                        "Gain ${$m2/10}.1 more.", "Gain 1.5 more.",
                        "Heal ${$s3*2+(4-1)} now.", "Heal 13 now.",
                        "Cross ${$123s1/-10} points.", "Cross ? points.",
                        "Cross ${$123s1/10} points.", "Cross 4 points.",
                        "Odd ${$s2/7}.1 rate.", "Odd ? rate.",
                        "Neg ${$s3-10} value.", "Neg ? value.",
                        "Zero ${$s2/(5-5)} value.", "Zero ? value.",
                        "Lvl ${$s2*$pl} value.", "Lvl ? value.");
        cases.forEach(
                (raw, expected) ->
                        assertEquals(
                                expected,
                                SpellText.project(100, raw, values, List.of()).display(),
                                raw));
        var lasts =
                SpellText.project(100, "Lasts ${$m1/-1000} $lsecond:seconds;.", values, List.of());
        assertEquals("Lasts 3 seconds.", lasts.display());
        assertEquals("${$m1/-1000}", lasts.substitutions().getFirst().token());
        var odd = SpellText.project(100, "Odd ${$s2/7}.1 rate.", values, List.of());
        assertEquals(
                "The expression result needs rounding to 1 decimal place(s)",
                odd.substitutions().getFirst().issue());
        var scaled =
                SpellText.project(
                        100,
                        "Hit ${$s1/10}.",
                        new SpellText.Values(Map.of(100L, Map.of(0, context("40"))), Map.of()),
                        List.of());
        assertEquals("Hit ?.", scaled.display());
        assertTrue(
                scaled.substitutions()
                        .getFirst()
                        .issue()
                        .contains("random, scaling or character context"));
        var needs = SpellText.needs(100, "${$m1/-1000} ${$123s1*2} ${$pl*$s1}");
        assertEquals(java.util.Set.of(100L, 123L), needs.amounts());
        for (String hostile :
                List.of(
                        "${" + "1+".repeat(40) + "1}",
                        "${((((((((((1))))))))))}",
                        "${999999999*999999999}",
                        "${$s99}",
                        "${$12345678901s1}",
                        "${1e9}")) {
            assertEquals(
                    "?", SpellText.project(100, hostile, values, List.of()).display(), hostile);
        }
    }
}
