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

    /**
     * Optional limits apply before block/record allocation and row decoding, after opening the
     * prepared graph. Providers must not emulate this by truncating an unbounded read.
     */
    default CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
            long firstOrdinal,
            int maxRows,
            CapturedRowsTypes.ReadLimits limits,
            Cancellation token) {
        java.util.Objects.requireNonNull(limits);
        java.util.Objects.requireNonNull(token);
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Bounded captured row reads unavailable"));
    }

    /**
     * Optional selected-ID inspection performs configured source I/O and may download data. It does
     * not fetch definitions, decode row values, repair or write generated graphs. Default
     * unsupported performs no I/O and never falls back to ordinary captured reads.
     */
    default CompletableFuture<NativeIdDomainTypes.Result> inspectNativeIdDomain(
            long selectedId, NativeIdDomainTypes.SourceAccess access, Cancellation token) {
        java.util.Objects.requireNonNull(access);
        java.util.Objects.requireNonNull(token);
        return CompletableFuture.completedFuture(NativeIdDomainTypes.Result.unsupported());
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
