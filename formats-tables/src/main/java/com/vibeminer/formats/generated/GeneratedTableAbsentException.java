package com.vibeminer.formats.generated;

/** Exact saved source graph is absent; distinct from corruption and unsupported readers. */
public final class GeneratedTableAbsentException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public GeneratedTableAbsentException() {
        super("Saved table graph unavailable");
    }
}
