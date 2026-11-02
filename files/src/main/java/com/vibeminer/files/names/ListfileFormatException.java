package com.vibeminer.files.names;

/** Refused community CSV; no partial index is published. */
public final class ListfileFormatException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ListfileFormatException(String message) {
        super(message);
    }
}
