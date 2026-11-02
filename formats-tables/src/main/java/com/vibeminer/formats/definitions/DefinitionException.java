package com.vibeminer.formats.definitions;

/** A stable definition resolution or schema refusal. */
public final class DefinitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public DefinitionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
