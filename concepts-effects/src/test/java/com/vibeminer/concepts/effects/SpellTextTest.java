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
        for (String raw : List.of("${$s1*2}", "$/10;123s1", "$?a123[yes][no]", "|cffffff00$s1|r")) {
            assertEquals(
                    null, SpellText.project(100, raw, Map.of(0, amount("5")), List.of()).display());
        }
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
