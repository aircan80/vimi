package com.vibeminer.formats.db2;

import java.time.Instant;

/** A typed layer4 failure after the decoded stream opened. */
public final class Db2SourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Db2FileSource.OpenState state;
    private final String code;
    private final transient Instant observedAt;

    public Db2SourceException(
            Db2FileSource.OpenState state, String code, String message, Instant observedAt) {
        super(message == null ? code : message);
        this.state = state;
        this.code = code;
        this.observedAt = observedAt;
    }

    public Db2FileSource.OpenState state() {
        return state;
    }

    public String code() {
        return code;
    }

    public Instant observedAt() {
        return observedAt;
    }
}
