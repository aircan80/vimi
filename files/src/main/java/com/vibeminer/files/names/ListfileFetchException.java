package com.vibeminer.files.names;

import java.time.Instant;

final class ListfileFetchException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    final String code;
    final Instant at;

    ListfileFetchException(String code, String message, Instant at) {
        super(message);
        this.code = code;
        this.at = at;
    }
}
