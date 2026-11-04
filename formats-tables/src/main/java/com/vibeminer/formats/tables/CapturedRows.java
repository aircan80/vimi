package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/**
 * One held prepared-local graph without repair or a logical storage lease. Ordinal reads use that
 * graph; optional native-ID inspection has separate explicit source-access semantics.
 */
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
     * prepared graph. Providers must not emulate this by truncating an unbounded read. The default
     * fails with the private-constructed BoundedReadUnsupportedException marker without IO.
     * Providers deliberately declining this capability may delegate to this default; an arbitrary
     * provider UnsupportedOperationException is not that typed default-capability signal.
     */
    default CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
            long firstOrdinal,
            int maxRows,
            CapturedRowsTypes.ReadLimits limits,
            Cancellation token) {
        java.util.Objects.requireNonNull(limits);
        java.util.Objects.requireNonNull(token);
        return CompletableFuture.failedFuture(CapturedRowsTypes.unsupportedBoundedRead());
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
