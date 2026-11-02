package com.vibeminer.concepts.core;

import java.util.List;

/** Bounded table-only lookup preparation. Issues never imply fabricated game data. */
public final class ConceptWarmup {
    private ConceptWarmup() {}

    public record Entry(
            String table,
            String column,
            boolean ready,
            String code,
            String message,
            String identity) {}

    public record Result(List<Entry> entries) {
        public Result {
            entries = List.copyOf(entries);
        }

        public boolean ready() {
            return entries.stream().allMatch(Entry::ready);
        }
    }

    public static Entry relation(String table, String column, RelationIndexes.Lookup result) {
        return new Entry(
                table,
                column,
                result.complete(),
                result.code() == null && !result.complete() ? result.state().name() : result.code(),
                result.message(),
                result.identity());
    }
}
