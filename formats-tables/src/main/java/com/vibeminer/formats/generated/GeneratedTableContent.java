package com.vibeminer.formats.generated;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Provisional decoded content; owner always closes after store, including failed/already-present
 * writes.
 */
public record GeneratedTableContent(
        Db2Types.Header header,
        List<Db2Types.Section> sections,
        Db2Types.Completion completion,
        List<String> keysUsed,
        Rows rows,
        AutoCloseable owner,
        List<String> holeKeys,
        List<String> chunkKeys) {
    @FunctionalInterface
    public interface Rows {
        CompletableFuture<List<Db2Types.Row>> read(long first, int count, Cancellation token);
    }

    public GeneratedTableContent {
        sections = List.copyOf(sections);
        keysUsed = List.copyOf(keysUsed);
        holeKeys = holeKeys == null ? List.of() : List.copyOf(holeKeys);
        chunkKeys = chunkKeys == null ? List.of() : List.copyOf(chunkKeys);
    }

    public boolean encrypted() {
        return !holeKeys.isEmpty() || !chunkKeys.isEmpty();
    }
}
