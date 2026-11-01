package com.vibeminer.builds;

import java.io.Serial;

/** Local admission/lifecycle failure, distinct from a build's metadata outcome. */
public final class BuildOpenException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public BuildOpenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
