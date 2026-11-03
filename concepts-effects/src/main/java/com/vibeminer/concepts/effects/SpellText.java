package com.vibeminer.concepts.effects;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded client-text projection; expressions and contextual values are never evaluated. */
final class SpellText {
    private static final int LIMIT = 16384, TOKENS = 256;
    private static final Pattern SIMPLE =
            Pattern.compile("\\$(?:[A-Za-z][A-Za-z0-9]*|[0-9]+[A-Za-z]+[0-9]*)");

    private SpellText() {}

    record Amount(BigDecimal base, List<SpellTexts.Source> sources, String issue) {
        Amount {
            sources = List.copyOf(sources);
        }
    }

    static SpellTexts.Description project(
            long spell, String raw, Map<Integer, Amount> amounts, List<String> issues) {
        if (raw == null || raw.length() > LIMIT || !ItemEffectProfiles.wellFormed(raw)) {
            return new SpellTexts.Description(
                    spell,
                    raw != null && raw.length() <= LIMIT ? raw : null,
                    null,
                    SpellTexts.State.UNAVAILABLE,
                    List.of(),
                    issues);
        }
        if (!raw.contains("$") && !raw.contains("|")) {
            return new SpellTexts.Description(
                    spell, raw, raw, SpellTexts.State.READY, List.of(), issues);
        }
        // Conditional/arithmetic/markup syntax cannot safely be split into ordinary prose.
        String remainder = SIMPLE.matcher(raw).replaceAll("");
        if (remainder.contains("$") || remainder.contains("|")) {
            return new SpellTexts.Description(
                    spell,
                    raw,
                    null,
                    SpellTexts.State.UNRESOLVED,
                    List.of(),
                    List.of("This description uses an unsupported client expression or markup."));
        }
        var substitutions = new ArrayList<SpellTexts.Substitution>();
        var display = new StringBuilder();
        var matcher = SIMPLE.matcher(raw);
        int at = 0;
        boolean resolved = true;
        while (matcher.find()) {
            if (substitutions.size() == TOKENS) {
                return new SpellTexts.Description(
                        spell,
                        raw,
                        null,
                        SpellTexts.State.UNRESOLVED,
                        List.of(),
                        List.of("Spell substitution count exceeds the supported bound."));
            }
            display.append(raw, at, matcher.start());
            String token = matcher.group();
            Integer index = token.matches("\\$s[1-3]") ? token.charAt(2) - '1' : null;
            Amount amount = index == null ? null : amounts.get(index);
            String value = null;
            String issue =
                    index == null ? "Unsupported spell token" : "Effect amount is unavailable";
            if (amount != null) {
                issue = amount.issue();
                if (issue == null && amount.base() != null) {
                    BigDecimal absolute = amount.base().abs();
                    if (absolute.stripTrailingZeros().scale() <= 0
                            && absolute.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
                        value = absolute.toPlainString();
                        value = new BigDecimal(value).toBigIntegerExact().toString();
                    } else {
                        issue = "Non-integral or out-of-range effect amount is unsupported";
                    }
                }
            }
            resolved &= value != null;
            display.append(value == null ? "?" : value);
            substitutions.add(
                    new SpellTexts.Substitution(
                            token,
                            index,
                            value,
                            amount == null ? List.of() : amount.sources(),
                            value == null ? issue : null));
            at = matcher.end();
        }
        display.append(raw, at, raw.length());
        if (display.length() > LIMIT) {
            return new SpellTexts.Description(
                    spell,
                    raw,
                    null,
                    SpellTexts.State.UNRESOLVED,
                    substitutions,
                    List.of("Rendered spell text exceeds the supported bound."));
        }
        return new SpellTexts.Description(
                spell,
                raw,
                display.toString(),
                resolved ? SpellTexts.State.RESOLVED : SpellTexts.State.UNRESOLVED,
                substitutions,
                issues);
    }
}
