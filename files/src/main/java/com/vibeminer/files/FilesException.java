package com.vibeminer.files;

/** Operational admission failure; file content outcomes remain FileRead states. */
public final class FilesException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public FilesException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
