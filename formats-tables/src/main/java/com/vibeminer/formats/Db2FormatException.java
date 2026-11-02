package com.vibeminer.formats;

/** A stable refusal code for invalid, unsupported or over-limit DB2/DBD input. */
public final class Db2FormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public Db2FormatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
