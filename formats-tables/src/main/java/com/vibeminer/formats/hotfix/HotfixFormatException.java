package com.vibeminer.formats.hotfix;

/** Structural cache refusal only; underlying source failures retain their original type. */
public final class HotfixFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    HotfixFormatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
