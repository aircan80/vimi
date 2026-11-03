package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot;
import com.vibeminer.concepts.comparison.effects.EffectsStageCodec;
import com.vibeminer.concepts.effects.PreparedItemEffectProjection;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CellState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Compilation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueChannel;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueOverride;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.PresenceEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RowState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceProfile;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceRow;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceSchema;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceStamp;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
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
public final class PreparedEffectRowsSource implements SortedComparisonInput.Source {
    private static final long PROFILE_BYTES = 4L << 20, PROJECT_BYTES = 3L << 20;
    private final ComparisonTypes.Descriptor descriptor;
    private final TempBudget budget;
    private final TempBudget.Reservation profileMemory;
    private final EffectsStageCodec codec;
    private final EffectsSnapshot.SourceRef sourceRef;
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
            super("Prepared effect identity not admitted: " + compilation.identity());
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
     * Success owns captured/profile lease; all constructor failures leave captured caller-owned.
     */
    @SuppressWarnings("try")
    public PreparedEffectRowsSource(
            CapturedRows captured,
            EffectsSnapshot.Side side,
            SourceKind kind,
            EffectsSnapshot snapshot,
            TempBudget budget,
            Cancellation token)
            throws IOException {
        Objects.requireNonNull(captured);
        Objects.requireNonNull(kind);
        this.budget = Objects.requireNonNull(budget);
        if (snapshot.budget() != budget) {
            throw new IllegalArgumentException("Effects source requires snapshot shared budget");
        }
        var memory = budget.reserveMemory("effect-source-profile", PROFILE_BYTES, token);
        try (var scratch = budget.reserveMemory("effect-source-compile", PROJECT_BYTES, token)) {
            var receipt = captured.receipt();
            validateReceipt(receipt);
            var endpoint = snapshot.endpoint(side);
            if (!receipt.build().equals(endpoint.build())
                    || !receipt.locale().equals(endpoint.locale())
                    || receipt.table().fileDataId() != kind.profile().fileDataId()) {
                throw new IllegalArgumentException("Captured effect receipt differs from endpoint");
            }
            var keys = new TreeMap<String, String>();
            for (var key : receipt.effectiveKeys()) {
                if (keys.containsKey(key.name())) {
                    throw new IllegalArgumentException("Duplicate effect key status");
                }
                keys.put(key.name(), key.fingerprint());
            }
            var provenance = new TreeMap<String, String>();
            IssueEvidenceMetadata.put(provenance, "effects.issueEvidence", receipt);
            var nativeEvidence = receipt.issueChannelEvidence();
            if (nativeEvidence.state() == CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE) {
                validateNativeEvidence(receipt);
            }
            var issueEvidence =
                    nativeEvidence.state() == CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                            ? new PreparedItemEffectTypes.IssueEvidence(
                                    nativeEvidence.state().name(),
                                    nativeEvidence.policyId(),
                                    nativeEvidence.producerId(),
                                    provenance.get("effects.issueEvidence.digest"))
                            : new PreparedItemEffectTypes.IssueEvidence(
                                    "UNKNOWN", null, null, null);
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
                            receipt.graphIdentity(),
                            receipt.readerRevision(),
                            receipt.readerOptions(),
                            receipt.rowEncoding(),
                            digest(keys),
                            issueEvidence);
            var state =
                    receipt.table().state() == TableTypes.State.PARTIAL
                            ? SourceState.PARTIAL
                            : SourceState.READABLE;
            var compilation =
                    PreparedItemEffectProjection.compile(
                            new SourceSchema(
                                    stamp,
                                    receipt.header(),
                                    receipt.schema(),
                                    receipt.columns(),
                                    state));
            if (compilation.identity() != IdentityAdmission.ADMITTED) {
                throw new AdmissionFailure(stamp, compilation);
            }
            profile = compilation.profile();
            graphIdentity = receipt.graphIdentity();
            rowCount = receipt.rowCount();
            width = receipt.columns().size();
            sourceSummary =
                    "Recorded effect dependency "
                            + state
                            + "; "
                            + rowCount
                            + " readable rows; membership is not certified.";
            provenance.put("readable.rows", Long.toString(rowCount));
            provenance.put(
                    "excluded.sections",
                    Integer.toString(receipt.completion().excludedEncryptedSections()));
            provenance.put(
                    "excluded.physical",
                    Long.toString(receipt.completion().excludedPhysicalRecords()));
            provenance.put(
                    "excluded.copies", Long.toString(receipt.completion().excludedCopyRecords()));
            provenance.put("encoding.scope", Objects.toString(receipt.encodingScope(), "unknown"));
            provenance.put(
                    "unknown-key-status-count",
                    Long.toString(keys.values().stream().filter(Objects::isNull).count()));
            token.check();
            var dependencies = new TreeMap<String, String>();
            dependencies.putAll(provenance);
            dependencies.put("effects.endpointSession", endpoint.sessionId());
            dependencies.put("effects.side", side.name());
            dependencies.put("effects.graph", graphIdentity);
            dependencies.put(
                    "effects.sourceHash",
                    sha256(receipt.sourceIdentity().getBytes(StandardCharsets.UTF_8)));
            dependencies.put("effects.content", stamp.contentKey());
            descriptor =
                    new ComparisonTypes.Descriptor(
                            digest(dependencies),
                            receipt.build().product(),
                            receipt.locale(),
                            "effects-private-ownID.v1",
                            profile.identityFingerprint(),
                            dependencies);
            codec = new EffectsStageCodec(snapshot);
            maximumOutputBytes = 1L << 20;
            worker =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(8),
                            Thread.ofPlatform().daemon().name("effect-source").factory(),
                            new ThreadPoolExecutor.AbortPolicy());
            profileMemory = memory;
            sourceRef = snapshot.register(side, stamp, state, compilation, provenance);
            this.captured = captured;
        } catch (IOException | RuntimeException | Error failure) {
            memory.close();
            throw failure;
        }
    }

    private static void validateNativeEvidence(CapturedRowsTypes.Receipt r) {
        var e = r.issueChannelEvidence();
        if (!e.graphIdentity().equals(r.graphIdentity())
                || !e.resolvedBlockSha256().equals(r.blockIdentity())
                || !e.readerOptionsSha256().equals(r.readerOptions())
                || !e.actualColumnsSha256()
                        .equals(
                                com.vibeminer.formats.db2.NativeIssueIdentity.columns(
                                        r.columns()))) {
            throw new IllegalArgumentException(
                    "Native effect issue evidence differs from captured context");
        }
        boolean matched = false;
        for (var scope : java.util.Arrays.asList((String) null, r.encodingScope())) {
            // All admitted effect profiles are numeric; their generated source locale is null.
            byte[] tuple =
                    com.vibeminer.formats.db2.NativeIssueIdentity.tuple(
                            com.vibeminer.formats.generated.GeneratedTableSource
                                    .NATIVE_IDENTITY_FORMAT,
                            e.policyId(),
                            e.producerId(),
                            null,
                            r.table().contentKey(),
                            r.blockIdentity(),
                            r.readerOptions(),
                            r.readerRevision(),
                            r.rowEncoding(),
                            scope);
            if (r.sourceIdentity().equals("native-tuple-hex=" + HexFormat.of().formatHex(tuple))
                    && e.sourceIdentitySha256().equals(sha256(tuple))) {
                matched = true;
            }
        }
        if (!matched) {
            throw new IllegalArgumentException("Native effect source/scope evidence differs");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public EffectsSnapshot.SourceRef sourceRef() {
        return sourceRef;
    }

    private static void validateReceipt(CapturedRowsTypes.Receipt r) throws IOException {
        if (r.rowCount() > 5_000_000
                || r.columns().size() > 11
                || r.schema().columns().size() > 11
                || r.sections().size() > 4096
                || r.effectiveKeys().size() > 256
                || r.table().reason() != null && r.table().reason().keyNames().size() > 4096
                || r.holeKeys().size() > 4096
                || r.schema().layoutHashes().size() > 64) {
            throw metadataLimit();
        }
        if (r.sourceIdentity().length() > 8192
                || r.encodingScope() != null && r.encodingScope().length() > 512) {
            throw metadataLimit();
        }
        for (var column : r.columns()) {
            if (column.name().length() > 128
                    || column.kind().length() > 32
                    || column.interpretation() != null
                            && (column.interpretation().policy().length() > 128
                                    || column.interpretation().warning().length() > 512)) {
                throw metadataLimit();
            }
        }
        if ((r.table().fileDataId() != SourceKind.BRIDGE.profile().fileDataId()
                        && r.table().fileDataId()
                                != SourceKind.RETAIL_EFFECT.profile().fileDataId())
                || r.retention() != CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING
                || r.membership() != CapturedRowsTypes.Membership.NOT_CERTIFIED
                || !List.of(TableTypes.State.READY, TableTypes.State.PARTIAL)
                        .contains(r.table().state())
                || !r.columns().equals(r.header().columns())
                || r.table().counts() == null
                || !Objects.equals(r.table().counts().layoutHash(), r.header().layoutHash())
                || !Objects.equals(r.table().counts().format(), r.header().format())) {
            throw new IllegalArgumentException("Unsupported captured effect receipt");
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
                "Captured effect metadata exceeds source envelope",
                new ComparisonTypes.Coverage(
                        ComparisonTypes.Completeness.UNKNOWN,
                        "effects-source-v1",
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
                    new IllegalArgumentException("Invalid Effect source page bounds"));
        }
        var result = new CompletableFuture<SortedComparisonInput.UnsortedPage>();
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Effect source closed"));
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
                                                    "effect-source-projection",
                                                    PROJECT_BYTES,
                                                    operation)) {
                                operation.check();
                                if (failed
                                        || (cursor == null ? 0 : Long.parseLong(cursor))
                                                != ordinal) {
                                    throw failure(
                                            SortedComparisonInput.Reason.STALE,
                                            "Effect ordinal continuation changed");
                                }
                                // Reserve is supplied by the sorting caller before this operation.
                                // Check a
                                // conservative projection bound BEFORE allocating the output DTO
                                // graph.
                                if (maximumOutputBytes > maxRetainedBytes) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Effect output allowance is too small");
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
                                            "Invalid Effect ordinal page framing");
                                }
                                if (page.cellIssues().size() > 1024
                                        || page.rows().getFirst().row().textIssues() != null
                                                && page.rows().getFirst().row().textIssues().size()
                                                        > 4096) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Captured effect issue inventory exceeds source bound");
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
                                        PreparedItemEffectProjection.projectSource(
                                                profile,
                                                new SourceRow(
                                                        row.id(),
                                                        RowState.PRESENT,
                                                        row,
                                                        channel,
                                                        overrides,
                                                        PresenceEvidence.unknown()));
                                var record = codec.encodeSource(sourceRef, facts);
                                if (SortedComparisonInput.retainedBytes(record) + 4096
                                        > maxRetainedBytes) {
                                    throw failure(
                                            SortedComparisonInput.Reason.LIMITED,
                                            "Projected effect row exceeds declared output allowance");
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
                                        failure instanceof EffectsSnapshot.Limit
                                                ? new SortedComparisonInput.CaptureFailure(
                                                        SortedComparisonInput.Reason.LIMITED,
                                                        "Effect staging record exceeds source bound",
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
                        "Observed prepared item effect rows only; membership is not certified.",
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
