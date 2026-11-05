package com.vibeminer.concepts.effects;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bounded evaluation of the arithmetic inside a client {@code ${...}} expression. Only decimal
 * numbers, {@code + - * /}, unary minus, parentheses and fixed effect amounts ({@code $s1}, {@code
 * $m2}, {@code $123s1}) are accepted. Any other term (character variables, conditionals, functions)
 * leaves the expression unknown rather than guessing a value.
 */
final class Arithmetic {
    private static final int MAX_LENGTH = 256, MAX_TERMS = 32, MAX_DEPTH = 8;
    private static final BigDecimal LIMIT = new BigDecimal("1000000000");

    private Arithmetic() {}

    /** An effect amount named in an expression; {@code spell} is null for the described spell. */
    record Operand(Long spell, int index) {}

    /** The value, or the reason there is none, with the sources of every operand used. */
    record Result(BigDecimal value, String issue, List<SpellTexts.Source> sources) {
        Result {
            sources = List.copyOf(sources);
        }
    }

    private sealed interface Term permits Number, Amount, Symbol {}

    private record Number(BigDecimal value) implements Term {}

    private record Amount(Operand operand, String raw) implements Term {}

    private record Symbol(char value) implements Term {}

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message, null, false, false);
        }
    }

    /** The effect amounts the expression reads, or null when it is not supported arithmetic. */
    static List<Operand> operands(String body) {
        try {
            var operands = new ArrayList<Operand>();
            for (var term : terms(body)) {
                if (term instanceof Amount amount) {
                    operands.add(amount.operand());
                }
            }
            return operands;
        } catch (Failure unsupported) {
            return null;
        }
    }

    static Result evaluate(
            String body, long self, Map<Long, Map<Integer, SpellText.Amount>> amounts) {
        var sources = new ArrayList<SpellTexts.Source>();
        try {
            var parser = new Parser(terms(body), self, amounts, sources);
            var value = parser.sum(0);
            if (parser.at != parser.terms.size()) {
                throw new Failure("The expression is not supported arithmetic");
            }
            return new Result(value, null, sources);
        } catch (Failure failure) {
            return new Result(null, failure.getMessage(), sources);
        }
    }

    private static List<Term> terms(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_LENGTH) {
            throw new Failure("The expression is empty or too long");
        }
        var terms = new ArrayList<Term>();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            if ("+-*/()".indexOf(c) >= 0) {
                terms.add(new Symbol(c));
                i++;
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < body.length() && Character.isDigit(body.charAt(i))) {
                    i++;
                }
                if (i + 1 < body.length()
                        && body.charAt(i) == '.'
                        && Character.isDigit(body.charAt(i + 1))) {
                    i++;
                    while (i < body.length() && Character.isDigit(body.charAt(i))) {
                        i++;
                    }
                }
                if (i - start > 12) {
                    throw new Failure("A number in the expression is too long");
                }
                terms.add(new Number(new BigDecimal(body.substring(start, i))));
            } else if (c == '$') {
                int start = i++;
                int digits = i;
                while (i < body.length() && Character.isDigit(body.charAt(i))) {
                    i++;
                }
                Long spell = null;
                if (i > digits) {
                    if (i - digits > 10) {
                        throw new Failure("A referenced spell ID is invalid");
                    }
                    spell = Long.parseLong(body.substring(digits, i));
                    if (spell < 1 || spell > 0xffffffffL) {
                        throw new Failure("A referenced spell ID is invalid");
                    }
                }
                if (i >= body.length() || "sSmM".indexOf(body.charAt(i)) < 0) {
                    throw new Failure(
                            "The expression uses a variable that is not resolved: "
                                    + body.substring(start, Math.min(body.length(), i + 1)));
                }
                i++;
                int index = i;
                while (i < body.length() && Character.isDigit(body.charAt(i))) {
                    i++;
                }
                if (i < body.length() && Character.isLetter(body.charAt(i))) {
                    throw new Failure(
                            "The expression uses a variable that is not resolved: "
                                    + body.substring(start, i + 1));
                }
                if (i == index || i - index > 2) {
                    throw new Failure("Effect index is unsupported");
                }
                int slot = Integer.parseInt(body.substring(index, i));
                if (slot < 1 || slot > SpellText.EFFECT_SLOTS) {
                    throw new Failure("Effect index is unsupported");
                }
                terms.add(new Amount(new Operand(spell, slot - 1), body.substring(start, i)));
            } else {
                throw new Failure("The expression is not supported arithmetic");
            }
            if (terms.size() > MAX_TERMS) {
                throw new Failure("The expression is too long");
            }
        }
        return terms;
    }

    private static final class Parser {
        final List<Term> terms;
        final long self;
        final Map<Long, Map<Integer, SpellText.Amount>> amounts;
        final List<SpellTexts.Source> sources;
        int at;

        Parser(
                List<Term> terms,
                long self,
                Map<Long, Map<Integer, SpellText.Amount>> amounts,
                List<SpellTexts.Source> sources) {
            this.terms = terms;
            this.self = self;
            this.amounts = amounts;
            this.sources = sources;
        }

        boolean symbol(char c) {
            if (at < terms.size() && terms.get(at) instanceof Symbol s && s.value() == c) {
                at++;
                return true;
            }
            return false;
        }

        BigDecimal sum(int depth) {
            var value = product(depth);
            while (true) {
                if (symbol('+')) {
                    value = bounded(value.add(product(depth)));
                } else if (symbol('-')) {
                    value = bounded(value.subtract(product(depth)));
                } else {
                    return value;
                }
            }
        }

        BigDecimal product(int depth) {
            var value = factor(depth);
            while (true) {
                if (symbol('*')) {
                    value = bounded(value.multiply(factor(depth)));
                } else if (symbol('/')) {
                    var divisor = factor(depth);
                    if (divisor.signum() == 0) {
                        throw new Failure("The expression divides by zero");
                    }
                    try {
                        // Exact: a quotient that does not terminate is never rounded into a
                        // value that looks exact.
                        value = bounded(value.divide(divisor));
                    } catch (ArithmeticException repeating) {
                        throw new Failure("The expression result needs rounding");
                    }
                } else {
                    return value;
                }
            }
        }

        BigDecimal factor(int depth) {
            if (depth > MAX_DEPTH) {
                throw new Failure("The expression is nested too deeply");
            }
            if (symbol('-')) {
                return factor(depth + 1).negate();
            }
            if (symbol('(')) {
                var value = sum(depth + 1);
                if (!symbol(')')) {
                    throw new Failure("The expression is not supported arithmetic");
                }
                return value;
            }
            if (at >= terms.size()) {
                throw new Failure("The expression is not supported arithmetic");
            }
            var term = terms.get(at++);
            if (term instanceof Number number) {
                return bounded(number.value());
            }
            if (term instanceof Amount amount) {
                long spell = amount.operand().spell() == null ? self : amount.operand().spell();
                var found = amounts.getOrDefault(spell, Map.of()).get(amount.operand().index());
                if (found == null) {
                    throw new Failure("Effect amount is unavailable for " + amount.raw());
                }
                sources.addAll(found.sources());
                if (found.percentageOnly()) {
                    // Such an amount is fixed only where it is shown with a literal %; the
                    // evaluator does not track that, so it refuses the operand.
                    throw new Failure(
                            amount.raw() + ": this amount is shown only as a plain percentage");
                }
                if (found.issue() != null || found.base() == null) {
                    throw new Failure(
                            amount.raw()
                                    + ": "
                                    + (found.issue() == null
                                            ? "Effect base amount is unreadable"
                                            : found.issue()));
                }
                return bounded(found.base());
            }
            throw new Failure("The expression is not supported arithmetic");
        }

        private static BigDecimal bounded(BigDecimal value) {
            if (value.abs().compareTo(LIMIT) > 0) {
                throw new Failure("The expression result is out of range");
            }
            return value;
        }
    }
}
