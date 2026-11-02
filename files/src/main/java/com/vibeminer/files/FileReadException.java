package com.vibeminer.files;

import com.vibeminer.files.FileTypes.FileReadState;
import java.io.IOException;
import java.time.Instant;

public final class FileReadException extends IOException {
    private static final long serialVersionUID = 1L;
    private final FileReadState state;
    private final String code;
    private final Instant observedAt;

    public FileReadException(FileReadState state, String code, String message, Instant observedAt) {
        super(message == null ? code : message);
        this.state = state;
        this.code = code;
        this.observedAt = observedAt;
    }

    public FileReadState state() {
        return state;
    }

    public String code() {
        return code;
    }

    public Instant observedAt() {
        return observedAt;
    }
}
