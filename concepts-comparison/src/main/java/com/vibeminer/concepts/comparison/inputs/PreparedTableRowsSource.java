package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Converts one immutable prepared graph's ordinal rows; sorting remains a separate explicit phase.
 */
public final class PreparedTableRowsSource implements SortedComparisonInput.Source {
    private final ComparisonTypes.Descriptor descriptor;
    private final TempBudget.Reservation profileMemory;
    private final ThreadPoolExecutor worker;
    private final Cancellation lifetime = new Cancellation();
    private final java.util.Set<CompletableFuture<?>> operations =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private CapturedRows captured;
    private RawTableSchema.Profile profile;
    private final String graphIdentity;
    private final long rowCount;
    private final int width;
    private final String sourceSummary;
    private long ordinal;
    private boolean failed;
    private volatile CompletableFuture<Void> closing;

    /** On successful construction owns captured; on failure the caller still closes it. */
    public PreparedTableRowsSource(
            CapturedRows captured,
            Map<String, String> endpointDependencies,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        Objects.requireNonNull(captured);
        Objects.requireNonNull(endpointDependencies);
        var memory = budget.reserveMemory("raw-schema-profile", 4L << 20, token);
        try {
            var receipt = captured.receipt();
            if (receipt.table().fileDataId() < 1
                    || receipt.table().fileDataId() > 0xffffffffL
                    || receipt.table().name() == null
                    || receipt.table().name().isBlank()
                    || receipt.table().name().length() > 256
                    || receipt.membership() != CapturedRowsTypes.Membership.NOT_CERTIFIED
                    || receipt.retention() != CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING
                    || !receipt.columns().equals(receipt.header().columns())) {
                throw new IllegalArgumentException("Unsupported prepared table receipt");
            }
            profile =
                    RawTableSchema.create(
                            new RawTableSchema.Inputs(
                                    receipt.header().format(),
                                    receipt.header().layoutHash(),
                                    receipt.readerRevision(),
                                    receipt.rowEncoding(),
                                    "recorded-schema-width-and-raw-exclusion-v1",
                                    receipt.locale(),
                                    receipt.definitionSha256(),
                                    receipt.blockIdentity(),
                                    receipt.schema(),
                                    receipt.columns()));
            graphIdentity = receipt.graphIdentity();
            rowCount = receipt.rowCount();
            width = receipt.columns().size();
            sourceSummary =
                    "Recorded source "
                            + receipt.table().state()
                            + "; "
                            + rowCount
                            + " readable rows; "
                            + receipt.completion().excludedEncryptedSections()
                            + " excluded encrypted sections, "
                            + receipt.completion().excludedPhysicalRecords()
                            + " physical and "
                            + receipt.completion().excludedCopyRecords()
                            + " copy rows excluded.";
            var dependencies = new TreeMap<>(endpointDependencies);
            dependencies.put("comparison.policy", "raw-selected-table-v1");
            dependencies.put("comparison.acquisition", "prepared-local-only");
            dependencies.put("comparison.results", "findings-only-session");
            dependencies.put("raw.table.id", Long.toString(receipt.table().fileDataId()));
            dependencies.put("raw.table.name", receipt.table().name());
            dependencies.put("raw.source.state", receipt.table().state().name());
            dependencies.put("raw.readable.rows", Long.toString(rowCount));
            dependencies.put(
                    "raw.declared.physical-records",
                    Long.toString(receipt.header().declaredRecords()));
            dependencies.put(
                    "raw.declared.copy-records",
                    Long.toString(receipt.header().declaredCopyRecords()));
            dependencies.put(
                    "raw.excluded.encrypted-sections",
                    Integer.toString(receipt.completion().excludedEncryptedSections()));
            dependencies.put(
                    "raw.excluded.physical-records",
                    Long.toString(receipt.completion().excludedPhysicalRecords()));
            dependencies.put(
                    "raw.excluded.copy-records",
                    Long.toString(receipt.completion().excludedCopyRecords()));
            dependencies.put("raw.table.contentKey", receipt.table().contentKey());
            dependencies.put("raw.layout", receipt.header().layoutHash());
            dependencies.put("raw.definition.sha256", receipt.definitionSha256());
            dependencies.put("raw.schema.fingerprint", profile.fingerprint());
            dependencies.put("raw.graph.identity", graphIdentity);
            dependencies.put("raw.graph.source", receipt.sourceIdentity());
            dependencies.put("raw.reader.options", receipt.readerOptions());
            dependencies.put("raw.reader.revision", receipt.readerRevision());
            dependencies.put("raw.row.encoding", receipt.rowEncoding());
            dependencies.put(
                    "raw.encoding.scope", Objects.toString(receipt.encodingScope(), "unknown"));
            dependencies.put("raw.membership", "NOT_CERTIFIED");
            dependencies.put("raw.retention", "BEST_EFFORT_INVALIDATING");
            dependencies.put("raw.assessed.columns", Integer.toString(profile.assessedColumns()));
            dependencies.put("raw.field.scope", fieldScope(profile.scope()));
            dependencies.put(
                    "scope.description",
                    "One exact-layout prepared table's verified raw cells;"
                            + " unverified/raw/narrowed columns and gameplay meaning unassessed; membership unknown");
            var keys = new TreeMap<String, String>();
            for (var key : receipt.effectiveKeys()) {
                if (keys.containsKey(key.name())) {
                    throw new IllegalArgumentException("Duplicate captured key identity");
                }
                keys.put(key.name(), key.fingerprint());
            }
            dependencies.put("raw.used-key-status", digest(keys));
            dependencies.put(
                    "raw.unknown-key-status-count",
                    Long.toString(keys.values().stream().filter(Objects::isNull).count()));
            String namespace =
                    "raw-table:"
                            + receipt.table().fileDataId()
                            + ":"
                            + receipt.table().name()
                            + ":row-id-v1";
            String scope = "raw-verified-cells-v1:" + profile.fingerprint();
            descriptor =
                    new ComparisonTypes.Descriptor(
                            digest(dependencies),
                            receipt.build().product(),
                            receipt.locale(),
                            namespace,
                            scope,
                            dependencies);
            profileMemory = memory;
            this.captured = captured;
            worker =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(8),
                            Thread.ofPlatform().daemon().name("comparison-raw-source").factory(),
                            new ThreadPoolExecutor.AbortPolicy());
        } catch (Throwable failure) {
            memory.close();
            throw failure;
        }
    }

    @Override
    public ComparisonTypes.Descriptor descriptor() {
        return descriptor;
    }

    @Override
    @SuppressWarnings("try") // Cancellation links live until the owned worker operation drains.
    public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
            String cursor, int maxRows, long maxRetainedBytes, Cancellation token) {
        Objects.requireNonNull(token);
        if (maxRows < 1
                || maxRows > 256
                || maxRetainedBytes < 1
                || maxRetainedBytes > SortedComparisonInput.SOURCE_PAGE_BYTES) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid raw source page bounds"));
        }
        var result = new CompletableFuture<SortedComparisonInput.UnsortedPage>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Raw source closed"));
            }
            operations.add(result);
            result.whenComplete((value, failure) -> operations.remove(result));
            try {
                worker.execute(
                        () -> {
                            var operation = new Cancellation();
                            try (var parent = lifetime.onCancel(operation::cancel);
                                    var caller = token.onCancel(operation::cancel)) {
                                operation.check();
                                if (failed
                                        || (cursor == null ? 0 : Long.parseLong(cursor))
                                                != ordinal) {
                                    throw failure(
                                            SortedComparisonInput.Reason.STALE,
                                            "Raw ordinal continuation changed");
                                }
                                // Reserve is supplied by the sorting caller before this operation.
                                // Check a
                                // conservative projection bound BEFORE allocating the output DTO
                                // graph.
                                if (profile.maximumRetainedBytes(85) + 4096 > maxRetainedBytes) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Raw row output allowance is too small");
                                }
                                if (ordinal == rowCount) {
                                    deliver(
                                            result,
                                            new SortedComparisonInput.UnsortedPage(
                                                    descriptor.identity(),
                                                    List.of(),
                                                    null,
                                                    coverage()));
                                    return;
                                }
                                var page = captured.readOrdinalPage(ordinal, 1, operation).join();
                                operation.check();
                                if (!graphIdentity.equals(page.identity())
                                        || page.firstOrdinal() != ordinal) {
                                    throw failure(
                                            SortedComparisonInput.Reason.STALE,
                                            "Prepared graph identity changed");
                                }
                                if (page.state() != CapturedRowsTypes.ReadState.READ) {
                                    throw failure(
                                            page.state() == CapturedRowsTypes.ReadState.CANCELED
                                                    ? SortedComparisonInput.Reason.CANCELED
                                                    : SortedComparisonInput.Reason.IO,
                                            "Prepared graph unavailable: "
                                                    + page.state()
                                                    + " ("
                                                    + page.code()
                                                    + ")");
                                }
                                if (page.rows().size() != 1
                                        || page.rows().getFirst().ordinal() != ordinal
                                        || (page.nextOrdinal() == null
                                                ? ordinal + 1 != rowCount
                                                : page.nextOrdinal() != ordinal + 1
                                                        || page.nextOrdinal() >= rowCount)) {
                                    throw failure(
                                            SortedComparisonInput.Reason.INVALID_SOURCE,
                                            "Invalid raw ordinal page framing");
                                }
                                var overrides =
                                        new LinkedHashMap<Integer, ComparisonTypes.ValueState>();
                                boolean issueChannelComplete = true;
                                for (var issue : page.cellIssues()) {
                                    if (issue.ordinal() != ordinal
                                            || issue.columnIndex() >= width) {
                                        throw failure(
                                                SortedComparisonInput.Reason.INVALID_SOURCE,
                                                "Cell issue has ambiguous ordinal/column");
                                    }
                                    if (issue.columnIndex() == -1) {
                                        issueChannelComplete = false;
                                    } else {
                                        overrides.put(
                                                issue.columnIndex(),
                                                ComparisonTypes.ValueState.LIMITED);
                                    }
                                }
                                var row = normalize(page.rows().getFirst().row(), overrides);
                                var record =
                                        profile.project(
                                                row,
                                                descriptor.identity()
                                                        + ":"
                                                        + InventoryFields.key(row.id(), true),
                                                overrides,
                                                issueChannelComplete);
                                if (SortedComparisonInput.retainedBytes(record) + 4096
                                        > maxRetainedBytes) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Projected raw row exceeds declared output allowance");
                                }
                                ordinal++;
                                operation.check();
                                deliver(
                                        result,
                                        new SortedComparisonInput.UnsortedPage(
                                                descriptor.identity(),
                                                List.of(record),
                                                page.nextOrdinal() == null
                                                        ? null
                                                        : Long.toString(ordinal),
                                                coverage()));
                            } catch (Throwable failure) {
                                failed = true;
                                CompletableFuture.runAsync(
                                        () -> result.completeExceptionally(failure));
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                result.completeExceptionally(failure);
            }
        }
        // The private operation completion remains authoritative if its observer is canceled.
        return result.copy();
    }

    private static <T> void deliver(CompletableFuture<T> result, T value) {
        // Caller completion callbacks may close/join this source; never run them on its worker.
        CompletableFuture.runAsync(() -> result.complete(value));
    }

    private Db2Types.Row normalize(
            Db2Types.Row row, Map<Integer, ComparisonTypes.ValueState> overrides)
            throws SortedComparisonInput.CaptureFailure {
        if (row.values().size() == width) {
            return row;
        }
        if (row.values().size() > width) {
            throw failure(
                    SortedComparisonInput.Reason.INVALID_SOURCE, "Raw row alignment is ambiguous");
        }
        // Only typed index evidence can establish that absent slots are trailing. Never guess an
        // interior shift from a short positional list or silently truncate extra cells.
        for (int index = row.values().size(); index < width; index++) {
            if (!overrides.containsKey(index)) {
                throw failure(
                        SortedComparisonInput.Reason.INVALID_SOURCE,
                        "Missing raw cell has no positional evidence");
            }
        }
        var values = new ArrayList<>(row.values());
        while (values.size() < width) {
            values.add("");
        }
        return new Db2Types.Row(
                row.id(), row.isCopy(), row.copySourceId(), values, row.textIssues());
    }

    private ComparisonTypes.Coverage coverage() {
        return new ComparisonTypes.Coverage(
                ComparisonTypes.Completeness.UNKNOWN,
                descriptor.scope(),
                List.of(
                        "Readable prepared-graph rows only; whole-table membership is not certified.",
                        "Raw verified cells only; source loss invalidates capture without repair or fallback.",
                        "Unverified/raw/narrowed columns and gameplay meaning remain unassessed.",
                        sourceSummary));
    }

    private SortedComparisonInput.CaptureFailure failure(
            SortedComparisonInput.Reason reason, String message) {
        return new SortedComparisonInput.CaptureFailure(reason, message, coverage(), null);
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        Throwable canceled = null;
        try {
            lifetime.cancel();
        } catch (Throwable failure) {
            canceled = failure;
        }
        worker.shutdown();
        final Throwable cancellationFailure = canceled;
        Thread.startVirtualThread(
                () -> {
                    Throwable problem = cancellationFailure;
                    boolean interrupted = false;
                    while (!worker.isTerminated()) {
                        try {
                            worker.awaitTermination(60, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    for (var operation : List.copyOf(operations)) {
                        operation.handle((value, failure) -> null).join();
                    }
                    try {
                        captured.closeAsync().join();
                    } catch (Throwable failure) {
                        if (problem == null) {
                            problem = failure;
                        } else {
                            problem.addSuppressed(failure);
                        }
                    } finally {
                        captured = null;
                        profile = null;
                        profileMemory.close();
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (problem == null) {
                        closing.complete(null);
                    } else {
                        closing.completeExceptionally(problem);
                    }
                });
        return closing.copy();
    }

    private static String fieldScope(List<RawTableSchema.ColumnScope> fields) {
        var result = new StringBuilder();
        for (var field : fields) {
            result.append(field.name())
                    .append('=')
                    .append(field.exclusion() == null ? "ASSESSED" : field.exclusion())
                    .append('\n');
            if (result.length() > 131072) {
                throw new IllegalArgumentException("Field scope limit exceeded");
            }
        }
        return result.toString();
    }

    private static String digest(Map<String, String> values) throws IOException {
        if (values.size() > 256) {
            throw new IllegalArgumentException("Dependency count limit exceeded");
        }
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            for (var entry : new TreeMap<>(values).entrySet()) {
                for (var value : new String[] {entry.getKey(), entry.getValue()}) {
                    if (value == null) {
                        out.writeInt(-1);
                        continue;
                    }
                    if (value.length() > 131072 || bytes.size() > 1048576) {
                        throw new IllegalArgumentException("Captured dependency limit exceeded");
                    }
                    var encodedBuffer =
                            StandardCharsets.UTF_8
                                    .newEncoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(
                                            java.nio.charset.CodingErrorAction.REPORT)
                                    .encode(java.nio.CharBuffer.wrap(value));
                    byte[] encoded = new byte[encodedBuffer.remaining()];
                    encodedBuffer.get(encoded);
                    if (encoded.length + 4L > 1048576 - bytes.size()) {
                        throw new IllegalArgumentException(
                                "Captured dependency encoding limit exceeded");
                    }
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            }
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
