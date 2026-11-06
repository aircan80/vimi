package com.vibeminer.formats.hotfix;

/** Structural cache refusal only; underlying source failures retain their original type. */
public final class HotfixFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final long records, recordLimit;

    HotfixFormatException(String code, String message) {
        this(code, message, 0, 0);
    }

    HotfixFormatException(String code, String message, long records, long recordLimit) {
        super(message);
        this.code = code;
        this.records = records;
        this.recordLimit = recordLimit;
    }

    public String code() {
        return code;
    }

    /** For {@code HOTFIX_RECORD_LIMIT}: records the container holds; otherwise 0. */
    public long records() {
        return records;
    }

    /** For {@code HOTFIX_RECORD_LIMIT}: the record bound that was exceeded; otherwise 0. */
    public long recordLimit() {
        return recordLimit;
    }
}
