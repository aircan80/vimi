package com.vibeminer.files.names;

public final class ListfileDeltaException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public enum Problem {
        MALFORMED,
        INPUT_MISMATCH,
        BROKEN_CHAIN,
        WRONG_TARGET
    }

    private final Problem problem;

    public ListfileDeltaException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
