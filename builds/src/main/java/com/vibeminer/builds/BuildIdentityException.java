package com.vibeminer.builds;

import java.io.Serial;

/** Stable reason why a versions row cannot identify a build. */
public final class BuildIdentityException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public BuildIdentityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
