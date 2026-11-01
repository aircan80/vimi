package com.vibeminer.store;

/** Stable, language-neutral store failure code. */
public final class StoreException extends RuntimeException {
    private final String code;
    public StoreException(String code, String message) { super(message); this.code = code; }
    public StoreException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
