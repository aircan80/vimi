package com.vibeminer.network;

import java.io.Serial;

/** Admission/lifecycle failures independent of any host's answer. */
public final class NetworkException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String code;

    public NetworkException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
