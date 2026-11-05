package com.vibeminer.concepts.effects;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded client-text projection. A tokenizer splits the template into prose, value tokens,
 * plural/gender forms and markup. Readable prose is always kept; a value that cannot be resolved
 * from reviewed source rows is shown inline as {@code ?} with its cause recorded. Expressions,
 * conditionals and contextual values are never evaluated.
 */
final class SpellText {
    private static final int LIMIT = 16384, TOKENS = 256, CONDITION = 128;

    /** At most this many other spells may be referenced by one description. */
    static final int CROSS_SPELLS = 8;

    /** Highest supported effect token index ($s1–$s5). */
    static final int EFFECT_SLOTS = 5;

    private SpellText() {}

    record Amount(
            BigDecimal base,
            List<SpellTexts.Source> sources,
            String issue,
            boolean percentageOnly) {
        Amount(BigDecimal base, List<SpellTexts.Source> sources, String issue) {
            this(base, sources, issue, false);
        }

        Amount {
            sources = List.copyOf(sources);
        }
    }

    record Value(String text, List<SpellTexts.Source> sources, String issue) {
        Value {
            sources = List.copyOf(sources);
        }
    }

    /** Source values for the described spell and any spell it references. */
    record Values(Map<Long, Map<Integer, Amount>> amounts, Map<Long, Value> durations) {
        Values {
            amounts = Map.copyOf(amounts);
            var copy = new LinkedHashMap<Long, Value>();
            durations.forEach(
                    (spell, value) -> {
                        if (value != null) {
                            copy.put(spell, value);
                        }
                    });
            durations = Map.copyOf(copy);
        }
    }

    private sealed interface Part permits Literal, Token, Forms, Unknown, Markup {}

    private record Literal(String text) implements Part {}

    /**
     * A value token: {@code $[spell]<kind>[index]}, optionally after {@code $/N;} or {@code $*N;}.
     */
    private record Token(
            String raw,
            int end,
            Long spell,
            char kind,
            Integer index,
            BigDecimal divisor,
            BigDecimal multiplier)
            implements Part {}

    /** {@code $lsingular:plural;} or {@code $gmale:female;}. */
    private record Forms(String raw, boolean gender, String first, String second) implements Part {}

    /** Recognized client syntax that is never evaluated. */
    private record Unknown(String raw, String issue) implements Part {}

    /** Display markup with no value: colours, links and textures. */
    private record Markup(String raw, String text, String issue) implements Part {}

    /** What a template needs from source rows: effect amounts and durations per spell. */
    record Needs(Set<Long> amounts, Set<Long> durations, boolean parsed) {
        boolean any() {
            return !amounts.isEmpty() || !durations.isEmpty();
        }
    }

    /** Source rows the template could use; {@code self} stands for the described spell. */
    static Needs needs(long self, String raw) {
        var parts = raw == null || raw.length() > LIMIT ? null : parse(raw);
        var amounts = new LinkedHashSet<Long>();
        var durations = new LinkedHashSet<Long>();
        if (parts == null) {
            return new Needs(Set.of(), Set.of(), false);
        }
        var cross = new LinkedHashSet<Long>();
        for (var part : parts) {
            if (part instanceof Token token) {
                long spell = token.spell() == null ? self : token.spell();
                if (spell != self) {
                    if (!cross.contains(spell) && cross.size() >= CROSS_SPELLS) {
                        continue;
                    }
                    cross.add(spell);
                }
                if (token.kind() == 's' && token.index() != null) {
                    amounts.add(spell);
                } else if (token.kind() == 'd'
                        && token.divisor() == null
                        && token.multiplier() == null) {
                    durations.add(spell);
                }
            }
        }
        return new Needs(Set.copyOf(amounts), Set.copyOf(durations), true);
    }

    static SpellTexts.Description project(
            long spell, String raw, Map<Integer, Amount> amounts, List<String> issues) {
        return project(spell, raw, amounts, null, issues);
    }

    static SpellTexts.Description project(
            long spell,
            String raw,
            Map<Integer, Amount> amounts,
            Value duration,
            List<String> issues) {
        var durations = new LinkedHashMap<Long, Value>();
        durations.put(spell, duration);
        return project(spell, raw, new Values(Map.of(spell, amounts), durations), issues);
    }

    static SpellTexts.Description project(
            long spell, String raw, Values values, List<String> issues) {
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
        var parts = parse(raw);
        if (parts == null) {
            return new SpellTexts.Description(
                    spell,
                    raw,
                    null,
                    SpellTexts.State.UNRESOLVED,
                    List.of(),
                    List.of("Spell substitution count exceeds the supported bound."));
        }
        var allIssues = new ArrayList<>(issues);
        var substitutions = new ArrayList<SpellTexts.Substitution>();
        var display = new StringBuilder();
        boolean resolved = true;
        // The most recent number shown, for $l plural forms; null when it is unknown.
        BigDecimal lastNumber = null;
        boolean sawNumber = false;
        var crossSpells = new LinkedHashSet<Long>();
        for (var part : parts) {
            switch (part) {
                case Literal literal -> display.append(literal.text());
                case Markup markup -> {
                    display.append(markup.text());
                    if (markup.issue() != null && !allIssues.contains(markup.issue())) {
                        allIssues.add(markup.issue());
                    }
                }
                case Unknown unknown -> {
                    resolved = false;
                    sawNumber = true;
                    lastNumber = null;
                    display.append('?');
                    substitutions.add(
                            new SpellTexts.Substitution(
                                    unknown.raw(), null, null, List.of(), unknown.issue()));
                }
                case Forms forms -> {
                    String value;
                    String issue = null;
                    if (forms.gender()) {
                        value = forms.first() + "/" + forms.second();
                        issue = "Gendered text depends on the character; both forms are shown";
                    } else if (sawNumber && lastNumber != null) {
                        value =
                                lastNumber.abs().compareTo(BigDecimal.ONE) == 0
                                        ? forms.first()
                                        : forms.second();
                    } else {
                        value = forms.first() + "/" + forms.second();
                        issue = "The preceding amount is unknown; both plural forms are shown";
                    }
                    display.append(value);
                    substitutions.add(
                            new SpellTexts.Substitution(
                                    forms.raw(), null, value, List.of(), issue));
                }
                case Token token -> {
                    long source = token.spell() == null ? spell : token.spell();
                    String value = null;
                    String issue;
                    List<SpellTexts.Source> sources = List.of();
                    boolean bounded = true;
                    if (source != spell && !crossSpells.contains(source)) {
                        bounded = crossSpells.size() < CROSS_SPELLS;
                        if (bounded) {
                            crossSpells.add(source);
                        }
                    }
                    BigDecimal number = null;
                    if (!bounded) {
                        issue = "Too many other spells are referenced";
                    } else if (token.kind() == 'd') {
                        var duration = values.durations().get(source);
                        issue = "Duration is unavailable";
                        if (token.divisor() != null || token.multiplier() != null) {
                            issue = "Scaled durations are unsupported";
                        } else if (duration != null) {
                            value = duration.text();
                            issue = duration.issue();
                            sources = duration.sources();
                        }
                    } else if (token.kind() == 's' && token.index() != null) {
                        var amount =
                                values.amounts().getOrDefault(source, Map.of()).get(token.index());
                        issue =
                                values.amounts().containsKey(source)
                                        ? "Effect amount is unavailable"
                                        : "Effect amounts for spell " + source + " are unavailable";
                        if (amount != null) {
                            sources = amount.sources();
                            issue = amount.issue();
                            if (amount.percentageOnly()
                                    && (token.end() == raw.length()
                                            || raw.charAt(token.end()) != '%')) {
                                issue =
                                        "This fixed scalar profile requires a literal percent"
                                                + " suffix";
                            }
                            if (issue == null && amount.base() != null) {
                                number = scaled(amount.base().abs(), token);
                                if (number == null) {
                                    issue =
                                            "Non-integral or out-of-range effect amount is"
                                                    + " unsupported";
                                } else {
                                    value = number.toPlainString();
                                }
                            } else if (issue == null) {
                                issue = "Effect base amount is unreadable";
                            }
                        }
                    } else {
                        issue = "Unsupported spell token";
                    }
                    if (value == null) {
                        number = null;
                        resolved = false;
                    }
                    sawNumber = true;
                    lastNumber = number;
                    display.append(value == null ? "?" : value);
                    substitutions.add(
                            new SpellTexts.Substitution(
                                    token.raw(),
                                    token.kind() == 's' ? token.index() : null,
                                    value,
                                    sources,
                                    value == null ? issue : null));
                }
            }
        }
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
                allIssues);
    }

    /**
     * The shown amount: an integral magnitude, or a divided/multiplied magnitude with at most two
     * decimals. Null when the client would need rounding rules that are not reviewed.
     */
    private static BigDecimal scaled(BigDecimal magnitude, Token token) {
        var value = magnitude;
        if (token.multiplier() != null) {
            value = value.multiply(token.multiplier());
        }
        if (token.divisor() != null) {
            if (token.divisor().signum() == 0) {
                return null;
            }
            value = value.divide(token.divisor(), 6, RoundingMode.HALF_EVEN);
        }
        value = value.stripTrailingZeros();
        boolean scaledToken = token.divisor() != null || token.multiplier() != null;
        if (value.scale() > (scaledToken ? 2 : 0)
                || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return null;
        }
        return value.scale() < 0 ? value.setScale(0) : value;
    }

    /** Splits a template into parts, or null when it exceeds the token bound. */
    private static List<Part> parse(String raw) {
        var parts = new ArrayList<Part>();
        var literal = new StringBuilder();
        int tokens = 0;
        int at = 0;
        while (at < raw.length()) {
            char c = raw.charAt(at);
            Part part = null;
            int next = at + 1;
            if (c == '$' && at + 1 < raw.length()) {
                var found = dollar(raw, at);
                if (found != null) {
                    part = found.part();
                    next = found.end();
                }
            } else if (c == '|' && at + 1 < raw.length()) {
                var found = pipe(raw, at);
                if (found != null) {
                    part = found.part();
                    next = found.end();
                }
            }
            if (part == null) {
                literal.append(c);
            } else {
                if (!literal.isEmpty()) {
                    parts.add(new Literal(literal.toString()));
                    literal.setLength(0);
                }
                if (part instanceof Literal text) {
                    literal.append(text.text());
                } else {
                    if (!(part instanceof Markup) && ++tokens > TOKENS) {
                        return null;
                    }
                    parts.add(part);
                }
            }
            at = next;
        }
        if (!literal.isEmpty()) {
            parts.add(new Literal(literal.toString()));
        }
        return parts;
    }

    private record Found(Part part, int end) {}

    private static Found dollar(String raw, int at) {
        int i = at + 1;
        char c = raw.charAt(i);
        if (c == '{') {
            int end = balanced(raw, i, '{', '}');
            return end < 0
                    ? null
                    : new Found(
                            new Unknown(
                                    raw.substring(at, end), "Client expressions are not evaluated"),
                            end);
        }
        if (c == '?') {
            int end = conditional(raw, i + 1);
            return end < 0
                    ? null
                    : new Found(
                            new Unknown(
                                    raw.substring(at, end),
                                    "Conditional client text depends on the character"),
                            end);
        }
        if (c == '@') {
            int end = i + 1;
            while (end < raw.length() && isLetter(raw.charAt(end))) {
                end++;
            }
            while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
                end++;
            }
            return end == i + 1
                    ? null
                    : new Found(
                            new Unknown(
                                    raw.substring(at, end),
                                    "Embedded spell text references are not resolved"),
                            end);
        }
        if (c == '<') {
            int end = raw.indexOf('>', i);
            return end < 0 || end - i > 64
                    ? null
                    : new Found(
                            new Unknown(
                                    raw.substring(at, end + 1),
                                    "Named client variables are not resolved"),
                            end + 1);
        }
        if (c == '/' || c == '*') {
            int semicolon = raw.indexOf(';', i + 1);
            if (semicolon < 0 || semicolon - i > 24) {
                return null;
            }
            BigDecimal factor;
            try {
                factor = new BigDecimal(raw.substring(i + 1, semicolon));
            } catch (NumberFormatException invalid) {
                return null;
            }
            var value = value(raw, at, semicolon + 1);
            if (value == null || !(value.part() instanceof Token token)) {
                return null;
            }
            return new Found(
                    new Token(
                            raw.substring(at, value.end()),
                            value.end(),
                            token.spell(),
                            token.kind(),
                            token.index(),
                            c == '/' ? factor : null,
                            c == '*' ? factor : null),
                    value.end());
        }
        if (c == 'l' || c == 'L' || c == 'g' || c == 'G') {
            int semicolon = raw.indexOf(';', i + 1);
            if (semicolon > 0 && semicolon - i <= 64) {
                String body = raw.substring(i + 1, semicolon);
                int colon = body.indexOf(':');
                if (colon >= 0 && body.indexOf(':', colon + 1) < 0 && body.indexOf('$') < 0) {
                    return new Found(
                            new Forms(
                                    raw.substring(at, semicolon + 1),
                                    c == 'g' || c == 'G',
                                    body.substring(0, colon),
                                    body.substring(colon + 1)),
                            semicolon + 1);
                }
            }
        }
        return value(raw, at, i);
    }

    /** {@code $[spell]<letter>[digit]} starting at {@code from}; {@code at} is the dollar sign. */
    private static Found value(String raw, int at, int from) {
        int i = from;
        int digits = i;
        while (i < raw.length() && Character.isDigit(raw.charAt(i)) && i - digits < 10) {
            i++;
        }
        Long spell = null;
        if (i > digits) {
            spell = Long.parseLong(raw.substring(digits, i));
            if (spell < 1 || spell > 0xffffffffL) {
                return null;
            }
        }
        if (i >= raw.length() || !isLetter(raw.charAt(i))) {
            return null;
        }
        char kind = Character.toLowerCase(raw.charAt(i));
        int end = i + 1;
        if (end < raw.length() && isLetter(raw.charAt(end))) {
            // A named variable such as $SP or $AP; not a single-letter value.
            while (end < raw.length() && (isLetter(raw.charAt(end)))) {
                end++;
            }
            while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
                end++;
            }
            return new Found(new Unknown(raw.substring(at, end), "Unsupported spell token"), end);
        }
        Integer index = null;
        if (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            int digit = raw.charAt(end) - '0';
            end++;
            if (digit >= 1 && digit <= EFFECT_SLOTS) {
                index = digit - 1;
            } else if (kind == 's') {
                return new Found(
                        new Unknown(raw.substring(at, end), "Effect index is unsupported"), end);
            }
        }
        if (kind == 's' && index == null) {
            return new Found(new Unknown(raw.substring(at, end), "Unsupported spell token"), end);
        }
        return new Found(
                new Token(raw.substring(at, end), end, spell, kind, index, null, null), end);
    }

    /** {@code condition[...]} branches after {@code $?}, with else and else-if; end or -1. */
    private static int conditional(String raw, int from) {
        int end = branch(raw, from);
        if (end < 0) {
            return -1;
        }
        while (true) {
            if (end < raw.length() && raw.charAt(end) == '[') {
                int last = balanced(raw, end, '[', ']');
                return last < 0 ? end : last;
            }
            if (end < raw.length() && raw.charAt(end) == '?') {
                int next = branch(raw, end + 1);
                if (next > 0) {
                    end = next;
                    continue;
                }
            }
            return end;
        }
    }

    /** A non-empty condition followed by one bracketed branch; end or -1. */
    private static int branch(String raw, int from) {
        int i = from;
        while (i < raw.length()
                && raw.charAt(i) != '['
                && i - from <= CONDITION
                && !Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        if (i == from || i >= raw.length() || raw.charAt(i) != '[') {
            return -1;
        }
        return balanced(raw, i, '[', ']');
    }

    /** End index after the bracket that closes the one at {@code open}, or -1. */
    private static int balanced(String raw, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < raw.length() && i - open <= LIMIT; i++) {
            char c = raw.charAt(i);
            if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    private static Found pipe(String raw, int at) {
        char c = raw.charAt(at + 1);
        switch (c) {
            case '|' -> {
                return new Found(new Literal("|"), at + 2);
            }
            case 'n' -> {
                return new Found(new Markup("|n", "\n", null), at + 2);
            }
            case 'r', 'R' -> {
                return new Found(new Markup(raw.substring(at, at + 2), "", null), at + 2);
            }
            case 'c', 'C' -> {
                int end = at + 10;
                if (end <= raw.length() && raw.substring(at + 2, end).matches("[0-9A-Fa-f]{8}")) {
                    return new Found(new Markup(raw.substring(at, end), "", null), end);
                }
                return null;
            }
            case 'T' -> {
                int end = raw.indexOf("|t", at + 2);
                if (end < 0) {
                    return null;
                }
                return new Found(
                        new Markup(
                                raw.substring(at, end + 2),
                                "",
                                "Inline texture markup is omitted from the preview."),
                        end + 2);
            }
            case 'H' -> {
                int end = raw.indexOf("|h", at + 2);
                if (end < 0) {
                    return null;
                }
                return new Found(new Markup(raw.substring(at, end + 2), "", null), end + 2);
            }
            case 'h' -> {
                return new Found(new Markup("|h", "", null), at + 2);
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
}
