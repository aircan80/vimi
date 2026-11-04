package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.generated.GeneratedTable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Tracks one exact saved reader; source loss invalidates instead of selecting a new graph. */
@SuppressWarnings("try")
final class CapturedRowsHandle implements CapturedRows {
    @FunctionalInterface
    interface NativeInspector {
        NativeIdDomainTypes.Result inspect(
                long id, NativeIdDomainTypes.SourceAccess access, Cancellation token);
    }

    private static final class InspectionCancel {
        final Cancellation token = new Cancellation();
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        private boolean requested, sealed;
        volatile Throwable failure;

        void request() {
            synchronized (this) {
                if (requested || sealed) {
                    return;
                }
                requested = true;
            }
            Thread.startVirtualThread(
                    () -> {
                        try {
                            token.cancel();
                        } catch (RuntimeException | Error error) {
                            failure = error;
                        } finally {
                            drained.complete(null);
                        }
                    });
        }

        synchronized boolean canceled() {
            return requested || token.isCanceled();
        }

        void sealAndDrain() {
            synchronized (this) {
                sealed = true;
                if (!requested) {
                    drained.complete(null);
                }
            }
            drained.join();
        }
    }

    // One process-wide native-ID inspection phase; no source-heavy backlog behind public captures.
    private static final java.util.concurrent.atomic.AtomicBoolean ID_INSPECTING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private NativeInspector inspector;
    private boolean inspecting, inspectorCloseDispatched;
    private Throwable inspectionFailure;
    private final CapturedRowsTypes.Receipt receipt;
    private GeneratedTable table;
    private final Consumer<CapturedRowsHandle> released;
    private final Cancellation lifetime = new Cancellation();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean closing, invalidated, notified;
    private int active;

    CapturedRowsHandle(
            CapturedRowsTypes.Receipt receipt,
            GeneratedTable table,
            Consumer<CapturedRowsHandle> released) {
        this(receipt, table, released, null);
    }

    CapturedRowsHandle(
            CapturedRowsTypes.Receipt receipt,
            GeneratedTable table,
            Consumer<CapturedRowsHandle> released,
            NativeInspector inspector) {
        validateEvidence(receipt, table);
        this.inspector = inspector;
        this.receipt = receipt;
        this.table = table;
        this.released = released;
    }

    private static void validateEvidence(CapturedRowsTypes.Receipt receipt, GeneratedTable table) {
        var evidence = receipt.issueChannelEvidence();
        var certificate = table.issueCertificate();
        if (!receipt.graphIdentity().equals(table.identity())
                || !receipt.columns().equals(table.header().columns())
                || receipt.rowCount() != table.rowCount()) {
            throw new IllegalArgumentException("Captured graph disagrees with receipt");
        }
        if (evidence.state() == CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                && (certificate == null
                        || !evidence.graphIdentity().equals(table.identity())
                        || !evidence.sourceIdentitySha256()
                                .equals(certificate.sourceIdentitySha256())
                        || !evidence.resolvedBlockSha256().equals(receipt.blockIdentity())
                        || !evidence.resolvedBlockSha256().equals(certificate.resolvedBlockSha256())
                        || !evidence.readerOptionsSha256().equals(receipt.readerOptions())
                        || !evidence.readerOptionsSha256().equals(certificate.readerOptionsSha256())
                        || !evidence.actualColumnsSha256().equals(certificate.actualColumnsSha256())
                        || !receipt.sourceIdentity()
                                .equals(
                                        (table.scoped()
                                                ? table.source().scopedIdentityText()
                                                : table.source().identityText())))) {
            throw new IllegalArgumentException("Native capture evidence disagrees with graph");
        }
    }

    @Override
    public CapturedRowsTypes.Receipt receipt() {
        return receipt;
    }

    @Override
    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
            long first, int maxRows, Cancellation token) {
        return readPage(first, maxRows, null, token);
    }

    @Override
    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
            long first, int maxRows, CapturedRowsTypes.ReadLimits limits, Cancellation token) {
        return readPage(first, maxRows, java.util.Objects.requireNonNull(limits), token);
    }

    private CompletableFuture<CapturedRowsTypes.Page> readPage(
            long first, int maxRows, CapturedRowsTypes.ReadLimits limits, Cancellation token) {
        java.util.Objects.requireNonNull(token);
        if (first < 0 || first > receipt.rowCount() || maxRows < 1 || maxRows > MAX_PAGE_ROWS) {
            throw new IllegalArgumentException("Invalid captured ordinal page");
        }
        GeneratedTable reader;
        synchronized (this) {
            if (closing) {
                return CompletableFuture.completedFuture(
                        failed(first, CapturedRowsTypes.ReadState.CLOSED, "CAPTURE_CLOSED"));
            }
            if (invalidated) {
                return CompletableFuture.completedFuture(
                        failed(
                                first,
                                CapturedRowsTypes.ReadState.INVALIDATED,
                                "CAPTURE_INVALIDATED"));
            }
            if (inspecting || active >= 2) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Too many captured row reads"));
            }
            active++;
            reader = table;
        }
        var future = new CompletableFuture<CapturedRowsTypes.Page>();
        try {
            Thread.startVirtualThread(
                    () -> {
                        try {
                            future.complete(read(reader, first, limits, token));
                        } catch (Throwable failure) {
                            future.completeExceptionally(failure);
                        }
                    });
        } catch (Throwable failure) {
            finish();
            future.completeExceptionally(failure);
        }
        return future;
    }

    @Override
    public CompletableFuture<NativeIdDomainTypes.Result> inspectNativeIdDomain(
            long id, NativeIdDomainTypes.SourceAccess access, Cancellation external) {
        java.util.Objects.requireNonNull(access);
        java.util.Objects.requireNonNull(external);
        if (id < 0 || id > 0xffff_ffffL) {
            throw new IllegalArgumentException("Unsigned ID required");
        }
        NativeInspector action;
        synchronized (this) {
            if (closing) {
                return CompletableFuture.completedFuture(
                        new NativeIdDomainTypes.Result(NativeIdDomainTypes.State.CANCELED, null));
            }
            if (inspector == null || invalidated) {
                return CompletableFuture.completedFuture(NativeIdDomainTypes.Result.unsupported());
            }
            if (active != 0) {
                return CompletableFuture.completedFuture(
                        new NativeIdDomainTypes.Result(NativeIdDomainTypes.State.BUSY, null));
            }
            if (!ID_INSPECTING.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(
                        new NativeIdDomainTypes.Result(NativeIdDomainTypes.State.BUSY, null));
            }
            active++;
            inspecting = true;
            action = inspector;
        }
        var result = new CompletableFuture<NativeIdDomainTypes.Result>();
        var cancellation = new InspectionCancel();
        try {
            Thread.startVirtualThread(
                    () -> {
                        NativeIdDomainTypes.Result value = null;
                        Throwable failure = null;
                        try (var caller = external.onCancel(cancellation::request);
                                var owner = lifetime.onCancel(cancellation::request)) {
                            if (cancellation.canceled()) {
                                throw new java.util.concurrent.CancellationException();
                            }
                            value = action.inspect(id, access, cancellation.token);
                        } catch (Throwable error) {
                            failure = error;
                        } finally {
                            cancellation.sealAndDrain();
                            if (cancellation.failure != null) {
                                if (failure == null
                                        || failure
                                                instanceof
                                                java.util.concurrent.CancellationException) {
                                    failure = cancellation.failure;
                                } else if (failure != cancellation.failure) {
                                    failure.addSuppressed(cancellation.failure);
                                }
                                synchronized (this) {
                                    inspectionFailure = cancellation.failure;
                                }
                            } else if (cancellation.canceled()
                                    && (failure == null
                                            || ClientBuildTables.unwrap(failure)
                                                    instanceof
                                                    java.util.concurrent.CancellationException)) {
                                value =
                                        new NativeIdDomainTypes.Result(
                                                NativeIdDomainTypes.State.CANCELED, null);
                                failure = null;
                            }
                            synchronized (this) {
                                inspecting = false;
                                if (closing && failure == null) {
                                    value =
                                            new NativeIdDomainTypes.Result(
                                                    NativeIdDomainTypes.State.CANCELED, null);
                                    failure = null;
                                }
                            }
                            ID_INSPECTING.set(false);
                            finish();
                        }
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (Throwable failure) {
            synchronized (this) {
                inspecting = false;
            }
            ID_INSPECTING.set(false);
            finish();
            result.completeExceptionally(failure);
        }
        return result;
    }

    private CapturedRowsTypes.Page read(
            GeneratedTable reader,
            long first,
            CapturedRowsTypes.ReadLimits limits,
            Cancellation token) {
        var request = new Cancellation();
        try (var caller = token.onCancel(request::cancel);
                var owner = lifetime.onCancel(request::cancel)) {
            request.check();
            int count = (int) Math.min(1, receipt.rowCount() - first);
            var rows =
                    (limits == null
                                    ? reader.readPage(first, count, request)
                                    : reader.readPage(
                                            first,
                                            count,
                                            new com.vibeminer.generated.RecordFile.ReadLimits(
                                                    limits.maxCompressedBlockBytes(),
                                                    limits.maxDecodedBlockBytes(),
                                                    limits.maxRecordBytes()),
                                            new com.vibeminer.formats.generated.RowCodec
                                                    .DecodeLimits(
                                                    limits.maxRecordBytes(),
                                                    limits.maxColumns(),
                                                    limits.maxIssues(),
                                                    limits.maxCellUtf8Bytes()),
                                            request))
                            .join();
            request.check();
            if (rows.size() != count) {
                throw new IllegalStateException("Captured row count disagrees");
            }
            var result = new java.util.ArrayList<TableTypes.Row>();
            var cellIssues = new java.util.ArrayList<CapturedRowsTypes.CellIssue>();
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (row.id() < 0
                        || row.id() > 0xffffffffL
                        || row.isCopy() != (row.copySourceId() != null)
                        || row.copySourceId() != null
                                && (row.copySourceId() < 0 || row.copySourceId() > 0xffffffffL)) {
                    throw new IllegalStateException("Invalid captured unsigned row ID");
                }
                if (row.values().size() != receipt.columns().size()) {
                    throw new IllegalStateException("Captured row width disagrees");
                }
                if (row.textIssues() != null) {
                    for (var issue : row.textIssues()) {
                        if (issue.columnIndex() < 0 || issue.columnIndex() >= row.values().size()) {
                            throw new IllegalStateException("Captured issue location disagrees");
                        }
                    }
                }
                result.add(new TableTypes.Row(first + i, bound(row, first + i, cellIssues)));
            }
            synchronized (this) {
                if (invalidated) {
                    return failed(
                            first, CapturedRowsTypes.ReadState.INVALIDATED, "CAPTURE_INVALIDATED");
                }
            }
            long next = first + rows.size();
            return new CapturedRowsTypes.Page(
                    receipt.graphIdentity(),
                    first,
                    CapturedRowsTypes.ReadState.READ,
                    result,
                    next < receipt.rowCount() ? next : null,
                    null,
                    null,
                    cellIssues);
        } catch (Throwable failure) {
            Throwable cause = ClientBuildTables.unwrap(failure);
            if (cause instanceof java.util.concurrent.CancellationException
                    || request.isCanceled()) {
                return failed(first, CapturedRowsTypes.ReadState.CANCELED, "CAPTURE_CANCELED");
            }
            if (limits != null
                    && cause instanceof com.vibeminer.generated.GeneratedStoreException e
                    && e.code().equals(com.vibeminer.generated.GeneratedTypes.LIMIT)) {
                return failed(
                        first, CapturedRowsTypes.ReadState.LIMIT_EXCEEDED, "CAPTURE_READ_LIMIT");
            }
            synchronized (this) {
                invalidated = true;
            }
            return failed(first, CapturedRowsTypes.ReadState.CORRUPT, "CAPTURE_SOURCE_INVALID");
        } finally {
            finish();
        }
    }

    private com.vibeminer.formats.db2.Db2Types.Row bound(
            com.vibeminer.formats.db2.Db2Types.Row row,
            long ordinal,
            List<CapturedRowsTypes.CellIssue> limitations) {
        if (row.values().size() > 4096) {
            throw new IllegalStateException("Captured cell inventory exceeds bound");
        }
        var values = new java.util.ArrayList<String>();
        long retained = 1024L + row.values().size() * 128L;
        for (int column = 0; column < row.values().size(); column++) {
            String value = row.values().get(column);
            long size = 64L + 4L * value.length();
            if (value.length() > MAX_CELL_BYTES
                    || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > MAX_CELL_BYTES
                    || retained + size > MAX_ROW_BYTES - 131072) {
                values.add("");
                limitations.add(
                        new CapturedRowsTypes.CellIssue(
                                ordinal,
                                column,
                                CapturedRowsTypes.CellState.LIMITED,
                                "CAPTURE_CELL_LIMIT"));
                retained += 64;
            } else {
                values.add(value);
                retained += size;
            }
        }
        List<com.vibeminer.formats.db2.Db2Types.TextIssue> issues = null;
        if (row.textIssues() == null
                && receipt.issueChannelEvidence().state()
                        == CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE) {
            issues = List.of();
        }
        if (row.textIssues() != null) {
            var kept = new java.util.ArrayList<com.vibeminer.formats.db2.Db2Types.TextIssue>();
            long issueBytes = 0;
            for (var issue : row.textIssues()) {
                long size = 64L + 4L * issue.code().length() + 4L * issue.rawBytesHex().length();
                if (issueBytes + size > 65536) {
                    limitations.add(
                            new CapturedRowsTypes.CellIssue(
                                    ordinal,
                                    -1,
                                    CapturedRowsTypes.CellState.LIMITED,
                                    "CAPTURE_ISSUE_CHANNEL_LIMIT"));
                    break;
                }
                kept.add(issue);
                issueBytes += size;
            }
            issues = List.copyOf(kept);
        }
        return new com.vibeminer.formats.db2.Db2Types.Row(
                row.id(), row.isCopy(), row.copySourceId(), values, issues);
    }

    private CapturedRowsTypes.Page failed(
            long first, CapturedRowsTypes.ReadState state, String code) {
        return new CapturedRowsTypes.Page(
                receipt.graphIdentity(),
                first,
                state,
                List.of(),
                null,
                code,
                state == CapturedRowsTypes.ReadState.LIMIT_EXCEEDED
                        ? "Requested row read limit exceeded; graph remains available; membership remains uncertified"
                        : "Captured source unavailable; membership remains uncertified");
    }

    private void finish() {
        synchronized (this) {
            active--;
        }
        completeClose();
    }

    private void completeClose() {
        boolean notify;
        Throwable sourceFailure;
        synchronized (this) {
            sourceFailure = inspectionFailure;
            notify = closing && active == 0 && !notified;
            if (notify) {
                notified = true;
                table = null;
                inspector = null;
            }
        }
        if (notify) {
            try {
                released.accept(this);
                if (sourceFailure == null) {
                    drained.complete(null);
                } else {
                    drained.completeExceptionally(sourceFailure);
                }
            } catch (Throwable failure) {
                drained.completeExceptionally(failure);
            }
        }
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        boolean sourceInspection, dispatch;
        synchronized (this) {
            closing = true;
            sourceInspection = inspecting;
            dispatch = sourceInspection && !inspectorCloseDispatched;
            inspectorCloseDispatched |= sourceInspection;
        }
        if (sourceInspection && dispatch) {
            Thread.startVirtualThread(
                    () -> {
                        try {
                            lifetime.cancel();
                        } finally {
                            completeClose();
                        }
                    });
        } else if (!sourceInspection) {
            try {
                lifetime.cancel();
            } finally {
                completeClose();
            }
        }
        return drained.copy();
    }
}
