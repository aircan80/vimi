package com.vibeminer.store;

import java.io.Serial;

/** Stable, language-neutral store failure code. */
public final class StoreException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public StoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    public StoreException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
