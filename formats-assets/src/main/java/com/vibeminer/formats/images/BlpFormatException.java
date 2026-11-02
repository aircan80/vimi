package com.vibeminer.formats.images;

import java.io.Serial;

public final class BlpFormatException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public BlpFormatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
