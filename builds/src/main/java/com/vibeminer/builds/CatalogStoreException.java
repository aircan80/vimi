package com.vibeminer.builds;

import java.io.Serial;

/** The disposable catalog cache cannot be opened; never silently deletes foreign/newer files. */
public final class CatalogStoreException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public CatalogStoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CatalogStoreException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
