package com.vibeminer.concepts.comparison;

import java.io.IOException;

/** A resource-policy outcome, distinct from malformed input or I/O failure. */
final class ComparisonLimit extends IOException {
    private static final long serialVersionUID = 1L;

    ComparisonLimit(String message) {
        super(message);
    }
}
