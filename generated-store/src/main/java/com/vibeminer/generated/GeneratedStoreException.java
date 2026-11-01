package com.vibeminer.generated;

import java.io.Serial;

/** Stable generated-store failure code; damaged derived data can be regenerated. */
public final class GeneratedStoreException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public GeneratedStoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    public GeneratedStoreException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
