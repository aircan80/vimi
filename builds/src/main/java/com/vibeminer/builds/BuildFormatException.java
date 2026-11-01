package com.vibeminer.builds;

import java.io.Serial;

public final class BuildFormatException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final BuildFailure failure;
    private final String code;

    public BuildFormatException(BuildFailure failure, String code, String message) {
        super(message);
        this.failure = failure;
        this.code = code;
    }

    public BuildFailure failure() {
        return failure;
    }

    public String code() {
        return code;
    }
}
