package com.vibeminer.files.names;

import java.time.Instant;

/** Community names are labels, never proof that a build contains a file. */
public final class NamesTypes {
    private NamesTypes() {}

    public enum FileNamesState {
        OFF,
        NOT_READY,
        READY,
        FAILED
    }

    public record FileNamesStatus(
            FileNamesState state,
            String tag,
            Instant publishedAt,
            Instant fetchedAt,
            Instant checkedAt,
            int count,
            String code,
            String message,
            Instant observedAt) {
        public FileNamesStatus(FileNamesState state) {
            this(state, null, null, null, null, 0, null, null, null);
        }
    }

    public record NameEntry(long id, String name) {}
}
