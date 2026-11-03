package com.vibeminer.concepts.comparison;

/** Resource policy outcome for pure adapters used through unchecked Java function contracts. */
public class ComparisonPolicyLimit extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ComparisonPolicyLimit(String message) {
        super(message);
    }
}
