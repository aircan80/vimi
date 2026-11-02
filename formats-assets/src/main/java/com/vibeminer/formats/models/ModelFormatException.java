package com.vibeminer.formats.models;

import java.io.Serial;

public final class ModelFormatException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public ModelFormatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
