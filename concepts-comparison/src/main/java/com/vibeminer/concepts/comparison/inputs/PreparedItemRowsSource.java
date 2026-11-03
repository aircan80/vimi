package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.items.ItemsSnapshot;
import com.vibeminer.concepts.comparison.items.ItemsStageCodec;
import com.vibeminer.concepts.items.PreparedItemProjection;
import com.vibeminer.concepts.items.PreparedItemTypes.CellState;
import com.vibeminer.concepts.items.PreparedItemTypes.Compilation;
import com.vibeminer.concepts.items.PreparedItemTypes.IdentityAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.IssueChannel;
import com.vibeminer.concepts.items.PreparedItemTypes.IssueOverride;
import com.vibeminer.concepts.items.PreparedItemTypes.PresenceEvidence;
import com.vibeminer.concepts.items.PreparedItemTypes.RowState;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceProfile;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceRow;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceSchema;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceStamp;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One captured dependency, projected independently; owns no endpoint/library or snapshot owner. */
public final class PreparedItemRowsSource implements SortedComparisonInput.Source {
    private static final long PROFILE_BYTES = 4L << 20, PROJECT_BYTES = 3L << 20;
    private final ComparisonTypes.Descriptor descriptor;
    private final TempBudget budget;
    private final TempBudget.Reservation profileMemory;
    private final ItemsStageCodec codec;
    private final ItemsSnapshot.SourceRef sourceRef;
    private final long maximumOutputBytes;
    private final ThreadPoolExecutor worker;
    private final Cancellation lifetime = new Cancellation();
    private final java.util.Set<CompletableFuture<?>> operations =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private CapturedRows captured;
    private SourceProfile profile;
    private final String graphIdentity;
    private final long rowCount;
    private final int width;
    private final String sourceSummary;
    private long ordinal;
    private boolean failed;
    private volatile CompletableFuture<Void> closing;

    /** Typed identity failure; caller retains capture and may register an omitted half. */
    public static final class AdmissionFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient SourceStamp stamp;
        private final transient Compilation compilation;

        private AdmissionFailure(SourceStamp stamp, Compilation compilation) {
            super("Prepared Item identity not admitted: " + compilation.identity());
            this.stamp = stamp;
            this.compilation = compilation;
        }

        public SourceStamp stamp() {
            return stamp;
        }

        public Compilation compilation() {
            return compilation;
        }
    }

    /**
     * On success owns captured; constructor failure leaves it caller-owned. Snapshot stays
     * external.
     */
    @SuppressWarnings("try")
    public PreparedItemRowsSource(
            CapturedRows captured,
            ItemsSnapshot.Side side,
            Map<String, String> endpointDependencies,
            ItemsSnapshot snapshot,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        Objects.requireNonNull(captured);
        Objects.requireNonNull(side);
        Objects.requireNonNull(snapshot);
        this.budget = Objects.requireNonNull(budget);
        if (snapshot.budget() != budget) {
            throw new IllegalArgumentException("Items source requires snapshot shared budget");
        }
        Objects.requireNonNull(endpointDependencies);
        if (endpointDependencies.size() > 192) {
            throw new IllegalArgumentException("Too many endpoint dependencies");
        }
        long endpointBytes = 0;
        for (var entry : endpointDependencies.entrySet()) {
            endpointBytes += 128L + 4L * entry.getKey().length();
            if (entry.getValue() != null) {
                endpointBytes += 4L * entry.getValue().length();
            }
            if (endpointBytes > 262144) {
                throw new IllegalArgumentException("Endpoint metadata exceeds source bound");
            }
        }
        var memory = budget.reserveMemory("item-source-profile", PROFILE_BYTES, token);
        try (var compilationScratch =
                budget.reserveMemory("item-source-compile", PROJECT_BYTES, token)) {
            var receipt = captured.receipt();
            validateReceipt(receipt);
            var kind =
                    receipt.table().fileDataId() == SourceKind.ITEM.fileDataId()
                            ? SourceKind.ITEM
                            : SourceKind.ITEM_SPARSE;
            var keys = new TreeMap<String, String>();
            for (var key : receipt.effectiveKeys()) {
                if (keys.containsKey(key.name())) {
                    throw new IllegalArgumentException("Duplicate key status");
                }
                keys.put(key.name(), key.fingerprint());
            }
            var stamp =
                    new SourceStamp(
                            kind,
                            receipt.build(),
                            receipt.locale(),
                            receipt.table().fileDataId(),
                            receipt.table().name(),
                            receipt.table().contentKey(),
                            receipt.header().layoutHash(),
                            receipt.definitionSha256(),
                            receipt.blockIdentity(),
                            receipt.readerRevision(),
                            receipt.readerOptions(),
                            receipt.rowEncoding(),
                            receipt.graphIdentity(),
                            digest(keys));
            var state =
                    receipt.table().state() == TableTypes.State.PARTIAL
                            ? SourceState.PARTIAL
                            : SourceState.READABLE;
            var compilation =
                    PreparedItemProjection.compile(
                            new SourceSchema(
                                    stamp,
                                    receipt.header(),
                                    receipt.schema(),
                                    receipt.columns(),
                                    state,
                                    snapshot.selection()));
            if (compilation.identity() != IdentityAdmission.ADMITTED) {
                throw new AdmissionFailure(stamp, compilation);
            }
            profile = compilation.profile();
            graphIdentity = receipt.graphIdentity();
            rowCount = receipt.rowCount();
            width = receipt.columns().size();
            sourceSummary =
                    "Recorded dependency "
                            + state
                            + "; "
                            + rowCount
                            + " readable rows; excluded sections "
                            + receipt.completion().excludedEncryptedSections()
                            + "; membership is not certified.";
            var dependencies = new TreeMap<String, String>(endpointDependencies);
            dependencies.put("items.source.kind", kind.name());
            dependencies.put("items.source.side", side.name());
            dependencies.put("items.graph", graphIdentity);
            dependencies.put("items.graph.source", receipt.sourceIdentity());
            dependencies.put(
                    "items.encoding.scope", Objects.toString(receipt.encodingScope(), "unknown"));
            dependencies.put("items.readable.rows", Long.toString(rowCount));
            dependencies.put(
                    "items.excluded.sections",
                    Integer.toString(receipt.completion().excludedEncryptedSections()));
            dependencies.put(
                    "items.excluded.physical",
                    Long.toString(receipt.completion().excludedPhysicalRecords()));
            dependencies.put(
                    "items.excluded.copies",
                    Long.toString(receipt.completion().excludedCopyRecords()));
            dependencies.put("items.contentKey", stamp.contentKey());
            dependencies.put("items.identity.policy", profile.identityFingerprint());
            dependencies.put("items.fields.policy", profile.fieldFingerprint());
            dependencies.put("items.source.state", state.name());
            dependencies.put("items.membership", "NOT_CERTIFIED");
            dependencies.put("items.retention", "BEST_EFFORT_INVALIDATING");
            dependencies.put("items.used-key-status", stamp.effectiveKeysFingerprint());
            dependencies.put(
                    "items.unknown-key-status-count",
                    Long.toString(keys.values().stream().filter(Objects::isNull).count()));
            dependencies.put("items.buildConfig", receipt.build().buildConfig());
            dependencies.put("items.cdnConfig", receipt.build().cdnConfig());
            dependencies.put("items.region", receipt.build().region());
            dependencies.put("items.reader", stamp.readerRevision());
            dependencies.put("items.rowEncoding", stamp.rowEncoding());
            dependencies.put("items.definition", stamp.definitionSha256());
            dependencies.put("items.block", stamp.blockIdentity());
            dependencies.put("items.readerOptions", stamp.readerOptions());
            dependencies.put(
                    "scope.description",
                    "One prepared item dependency; recorded sourced facts only; membership unknown");
            descriptor =
                    new ComparisonTypes.Descriptor(
                            digest(dependencies),
                            receipt.build().product(),
                            receipt.locale(),
                            "items.source:" + kind.name() + ":row-id-v1",
                            "items.source-facts-v1:"
                                    + profile.identityFingerprint()
                                    + ":"
                                    + profile.fieldFingerprint(),
                            dependencies);
            // Registration is last: failure before this point never publishes partial source
            // metadata.
            sourceRef = snapshot.register(side, stamp, state, compilation);
            codec = new ItemsStageCodec(snapshot);
            maximumOutputBytes = Math.addExact(codec.maximumSourceRetainedBytes(sourceRef), 4096);
            profileMemory = memory;
            this.captured = captured;
            worker =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(8),
                            Thread.ofPlatform().daemon().name("comparison-item-source").factory(),
                            new ThreadPoolExecutor.AbortPolicy());
        } catch (Throwable failure) {
            memory.close();
            throw failure;
        }
    }

    public ItemsSnapshot.SourceRef sourceRef() {
        return sourceRef;
    }

    private static void validateReceipt(CapturedRowsTypes.Receipt r) throws IOException {
        if (r.columns().size() > 512
                || r.schema().columns().size() > 512
                || r.sections().size() > 4096
                || r.effectiveKeys().size() > 256
                || r.table().reason() != null && r.table().reason().keyNames().size() > 4096
                || r.holeKeys().size() > 4096
                || r.schema().layoutHashes().size() > 64) {
            throw metadataLimit();
        }
        if ((r.table().fileDataId() != SourceKind.ITEM.fileDataId()
                        && r.table().fileDataId() != SourceKind.ITEM_SPARSE.fileDataId())
                || r.retention() != CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING
                || r.membership() != CapturedRowsTypes.Membership.NOT_CERTIFIED
                || !List.of(TableTypes.State.READY, TableTypes.State.PARTIAL)
                        .contains(r.table().state())
                || !r.columns().equals(r.header().columns())
                || r.table().counts() == null
                || !Objects.equals(r.table().counts().layoutHash(), r.header().layoutHash())
                || !Objects.equals(r.table().counts().format(), r.header().format())) {
            throw new IllegalArgumentException("Unsupported captured Item receipt");
        }
        // Bound held receipt to half the envelope, leaving room for descriptor/profile references.
        long bytes =
                131072L
                        + 1024L
                                * (r.columns().size()
                                        + r.schema().columns().size()
                                        + r.sections().size()
                                        + r.effectiveKeys().size()
                                        + r.holeKeys().size());
        var text = new ArrayList<String>();
        for (var c : r.schema().columns()) {
            text.add(c.name());
            text.add(c.columnComment());
            text.add(c.fieldComment());
            if (c.foreignKey() != null) {
                text.add(c.foreignKey().table());
                text.add(c.foreignKey().column());
            }
        }
        for (var c : r.columns()) {
            text.add(c.name());
            text.add(c.kind());
            if (c.interpretation() != null) {
                text.add(c.interpretation().policy());
                text.add(c.interpretation().warning());
            }
        }
        text.add(r.schema().comment());
        text.add(r.sourceIdentity());
        text.add(r.encodingScope());
        text.add(r.completion().scope());
        if (r.table().reason() != null) {
            text.add(r.table().reason().code());
            text.add(r.table().reason().message());
            text.addAll(r.table().reason().keyNames());
        }
        for (var section : r.sections()) {
            text.add(section.key());
        }
        text.addAll(r.holeKeys());
        text.addAll(r.schema().layoutHashes());
        for (var k : r.effectiveKeys()) {
            text.add(k.name());
            text.add(k.fingerprint());
        }
        for (var s : text) {
            if (s != null) {
                bytes += 64L + 4L * s.length();
            }
            if (bytes > PROFILE_BYTES / 2) {
                throw metadataLimit();
            }
        }
    }

    private static SortedComparisonInput.CaptureFailure metadataLimit() {
        return new SortedComparisonInput.CaptureFailure(
                SortedComparisonInput.Reason.LIMITED,
                "Captured Item metadata exceeds source envelope",
                new ComparisonTypes.Coverage(
                        ComparisonTypes.Completeness.UNKNOWN,
                        "items-source-v1",
                        List.of("Metadata limit; membership unassessed")),
                null);
    }

    private static String boundedCode(String code) {
        return code.length() <= 128 && !code.isBlank() ? code : "CAPTURE_CELL_LIMIT";
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
                    new IllegalArgumentException("Invalid Item source page bounds"));
        }
        var result = new CompletableFuture<SortedComparisonInput.UnsortedPage>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Item source closed"));
            }
            operations.add(result);
            result.whenComplete((value, failure) -> operations.remove(result));
            try {
                worker.execute(
                        () -> {
                            var operation = new Cancellation();
                            var turnover = new CompletableFuture<Void>();
                            try (var parent = lifetime.onCancel(operation::cancel);
                                    var caller = token.onCancel(operation::cancel);
                                    var scratch =
                                            budget.reserveMemory(
                                                    "item-source-projection",
                                                    PROJECT_BYTES,
                                                    operation)) {
                                operation.check();
                                if (failed
                                        || (cursor == null ? 0 : Long.parseLong(cursor))
                                                != ordinal) {
                                    throw failure(
                                            SortedComparisonInput.Reason.STALE,
                                            "Item ordinal continuation changed");
                                }
                                // Reserve is supplied by the sorting caller before this operation.
                                // Check a
                                // conservative projection bound BEFORE allocating the output DTO
                                // graph.
                                if (maximumOutputBytes > maxRetainedBytes) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Item output allowance is too small");
                                }
                                if (ordinal == rowCount) {
                                    deliver(
                                            turnover,
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
                                            "Invalid Item ordinal page framing");
                                }
                                if (page.cellIssues().size() > 1024
                                        || page.rows().getFirst().row().textIssues() != null
                                                && page.rows().getFirst().row().textIssues().size()
                                                        > 4096) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Captured Item issue inventory exceeds source bound");
                                }
                                var overrides = new ArrayList<IssueOverride>();
                                var channel =
                                        page.rows().getFirst().row().textIssues() == null
                                                ? IssueChannel.UNKNOWN
                                                : IssueChannel.KNOWN;
                                for (var issue : page.cellIssues()) {
                                    if (issue.ordinal() != ordinal
                                            || issue.columnIndex() >= width) {
                                        throw failure(
                                                SortedComparisonInput.Reason.INVALID_SOURCE,
                                                "Cell issue has ambiguous source location");
                                    }
                                    if (issue.columnIndex() == -1) {
                                        channel = IssueChannel.LIMITED;
                                    }
                                    overrides.add(
                                            new IssueOverride(
                                                    issue.columnIndex(),
                                                    CellState.LIMITED,
                                                    boundedCode(issue.code())));
                                }
                                var row = page.rows().getFirst().row();
                                var facts =
                                        PreparedItemProjection.projectSource(
                                                profile,
                                                new SourceRow(
                                                        row.id(),
                                                        RowState.PRESENT,
                                                        row,
                                                        channel,
                                                        overrides,
                                                        PresenceEvidence.unknown()));
                                var record = codec.encode(sourceRef, facts);
                                if (SortedComparisonInput.retainedBytes(record) + 4096
                                        > maxRetainedBytes) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Projected Item row exceeds declared output allowance");
                                }
                                ordinal++;
                                operation.check();
                                deliver(
                                        turnover,
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
                                Throwable reported =
                                        failure instanceof ItemsStageCodec.Limit
                                                ? new SortedComparisonInput.CaptureFailure(
                                                        SortedComparisonInput.Reason.LIMITED,
                                                        "Item staging record exceeds source bound",
                                                        coverage(),
                                                        failure)
                                                : failure;
                                CompletableFuture.runAsync(
                                        () -> result.completeExceptionally(reported));
                            } finally {
                                turnover.complete(null);
                            }
                        });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                result.completeExceptionally(failure);
            }
        }
        // The private operation completion remains authoritative if its observer is canceled.
        return result.copy();
    }

    private static <T> void deliver(
            CompletableFuture<Void> turnover, CompletableFuture<T> result, T value) {
        // Caller completion callbacks may close/join this source; never run them on its worker.
        turnover.thenRunAsync(() -> result.complete(value));
    }

    private ComparisonTypes.Coverage coverage() {
        return new ComparisonTypes.Coverage(
                ComparisonTypes.Completeness.UNKNOWN,
                descriptor.scope(),
                List.of(
                        "Observed prepared Item/ItemSparse rows only; membership is not certified.",
                        "Source loss invalidates capture without repair or fallback.",
                        "Only admitted recorded facts are assessed; gameplay meaning remains unassessed.",
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
