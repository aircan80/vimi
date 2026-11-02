package com.vibeminer.formats.definitions;

/** A stable definition failure with structured fetch-switch/table provenance for consumers. */
public final class DefinitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public record Problem(String tableId, String definitionFetch) {}

    private final String code;
    private final boolean retryable;
    private final transient Problem definition;

    public DefinitionException(String code, String message) {
        this(code, message, false, null);
    }

    public DefinitionException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public DefinitionException(String code, String message, boolean retryable, Problem definition) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.definition = definition;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Problem definition() {
        return definition;
    }

    public DefinitionException withDefinition(String tableId, String reading) {
        return new DefinitionException(
                code, getMessage(), retryable, new Problem(tableId, reading));
    }
}
