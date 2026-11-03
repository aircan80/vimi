package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** One held prepared-local graph; no repair, decode, acquisition or logical storage lease. */
public interface CapturedRows extends AutoCloseable {
    int MAX_PAGE_ROWS = 256;
    int MAX_ROW_BYTES = 1 << 20;
    int MAX_CELL_BYTES = 64 << 10;

    CapturedRowsTypes.Receipt receipt();

    /**
     * May return one row even when more requested; nextOrdinal is authoritative. Inspect
     * cellIssues.
     */
    CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
            long firstOrdinal, int maxRows, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
