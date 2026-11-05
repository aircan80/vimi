package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.db2.Db2FileSource;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Table;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions.DefinitionTable;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTable;
import com.vibeminer.formats.generated.GeneratedTableContent;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.generated.GeneratedTableStore;
import com.vibeminer.formats.generated.KeyStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

final class ClientBuildTables implements BuildTables {
    private final com.vibeminer.files.Files fileSystem;
    private final BuildFiles files;
    private final String version;
    private final TableDefinitions definitions;
    private final GeneratedTableStore generated;
    private final TablesOptions options;
    private final BackgroundBuilds backgroundBuilds;
    private final Db2Types.Limits limits;
    private final TreeMap<Long, DefinitionTable> manifest = new TreeMap<>();
    private final TableDefinitions.FetchBudget fetchBudget = new TableDefinitions.FetchBudget();
    private final java.util.concurrent.Semaphore asking = new java.util.concurrent.Semaphore(1);
    private final ProtectedIds protectedIds;
    private final Map<Long, CompletableFuture<Void>> background = new HashMap<>();
    private final Object gate = new Object();
    private final java.util.Set<Cancellation> preloads = new java.util.HashSet<>();
    private boolean closed, closeStarted, closeCallbacks, filesClosing;
    private Throwable closeFailure;
    private int active, closeCaptureDrains;
    private final java.util.Set<CapturedRowsHandle> captures = new java.util.HashSet<>();
    private final java.util.Set<Cancellation> captureRequests = new java.util.HashSet<>();
    java.util.function.BiConsumer<CapturedRowsTypes.Capture, CompletableFuture<Void>>
            captureDeliveryProbe = (value, finished) -> {};

    int pendingCaptures() {
        synchronized (gate) {
            return captureRequests.size();
        }
    }

    int capturedHandles() {
        synchronized (gate) {
            return captures.size();
        }
    }

    public static final class NativeIdWitness implements NativeIdDomainTypes.Witness {
        private final long selectedId, ordinal, fileId, length;
        private final String key, layout, receiptFingerprint, excludedDigest, evidenceDigest;
        private final int excludedCount;

        private NativeIdWitness(
                long selectedId,
                Db2Table.IdDomainObservation domain,
                CapturedRowsTypes.Receipt receipt,
                String fingerprint) {
            this.selectedId = selectedId;
            ordinal = domain.ordinal();
            fileId = receipt.table().fileDataId();
            key = receipt.table().contentKey();
            length = receipt.completion().consumedBytes();
            layout = receipt.header().layoutHash();
            receiptFingerprint = fingerprint;
            excludedDigest = domain.excludedDigest();
            excludedCount = domain.excludedCount();
            evidenceDigest =
                    com.vibeminer.formats.internal.Json.sha256(
                            com.vibeminer.formats.db2.NativeIssueIdentity.tuple(
                                    NativeIdDomainTypes.REVISION,
                                    fingerprint,
                                    Long.toString(selectedId),
                                    Long.toString(ordinal),
                                    key,
                                    Long.toString(length),
                                    layout,
                                    Integer.toString(excludedCount),
                                    excludedDigest));
        }

        public long selectedId() {
            return selectedId;
        }

        public long readableOrdinal() {
            return ordinal;
        }

        public long sourceFileDataId() {
            return fileId;
        }

        public String sourceContentKey() {
            return key;
        }

        public long consumedLength() {
            return length;
        }

        public String layoutHash() {
            return layout;
        }

        public String receiptFingerprint() {
            return receiptFingerprint;
        }

        public int excludedCount() {
            return excludedCount;
        }

        public String excludedDigest() {
            return excludedDigest;
        }

        public String evidenceDigest() {
            return evidenceDigest;
        }

        public String profileRevision() {
            return NativeIdDomainTypes.REVISION;
        }
    }

    private static NativeIdDomainTypes.Result idResult(NativeIdDomainTypes.State state) {
        return new NativeIdDomainTypes.Result(state, null);
    }

    private NativeIdDomainTypes.Result inspectNativeId(
            CapturedRowsTypes.Receipt receipt,
            GeneratedTable saved,
            long selectedId,
            NativeIdDomainTypes.SourceAccess access,
            Cancellation token) {
        java.util.Objects.requireNonNull(access);
        token.check();
        if (receipt.issueChannelEvidence().state()
                        != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                || saved.source().issuePolicy() != GeneratedTableSource.IssuePolicy.NATIVE_V1) {
            return NativeIdDomainTypes.Result.unsupported();
        }
        if (receipt.header().declaredRecords() > 256
                || receipt.rowCount() > 256
                || receipt.sections().size() > 16
                || receipt.columns().size() > 128
                || receipt.effectiveKeys().size() > 32
                || receipt.holeKeys().size() > 16
                || receipt.completion().consumedBytes() > (1 << 20)) {
            return idResult(NativeIdDomainTypes.State.LIMIT_EXCEEDED);
        }
        if (receipt.header().declaredCopyRecords() != 0
                || !List.of("WDC4", "WDC5").contains(receipt.header().format())) {
            return NativeIdDomainTypes.Result.unsupported();
        }
        if (!nativeIdRuleProfile()) {
            return idResult(NativeIdDomainTypes.State.LIMIT_EXCEEDED);
        }
        String fingerprint;
        try {
            fingerprint = NativeIdReceiptFingerprint.sha256(receipt);
        } catch (UnsupportedOperationException bound) {
            return idResult(NativeIdDomainTypes.State.LIMIT_EXCEEDED);
        }
        if (!saved.header().equals(receipt.header())
                || !saved.completion().equals(receipt.completion())
                || !saved.sections().equals(receipt.sections())
                || !issueEvidence(saved).equals(receipt.issueChannelEvidence())
                || !saved.identity().equals(receipt.graphIdentity())
                || !saved.holeKeys().equals(receipt.holeKeys())) {
            return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
        }
        var before = files.stat(FileRef.id(receipt.table().fileDataId()), token).join();
        if (before.state() != FileTypes.FileLookupState.FOUND || before.file() == null) {
            return idResult(NativeIdDomainTypes.State.UNAVAILABLE);
        }
        if (before.file().fileDataId() != receipt.table().fileDataId()
                || !receipt.table().contentKey().equalsIgnoreCase(before.file().contentKey())) {
            return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
        }
        if (before.file().size() != null && before.file().size() > (1 << 20)) {
            return idResult(NativeIdDomainTypes.State.LIMIT_EXCEEDED);
        }
        if (!nativeIdDefinitionMatches(receipt, saved, token) || !nativeIdKeysMatch(receipt)) {
            return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
        }
        var opened =
                Db2FileSource.openBounded(
                                files, FileRef.id(receipt.table().fileDataId()), 1 << 20, 16, token)
                        .join();
        if (opened.source() == null) {
            return idResult(
                    opened.state() == Db2FileSource.OpenState.TOO_LARGE
                            ? NativeIdDomainTypes.State.LIMIT_EXCEEDED
                            : NativeIdDomainTypes.State.UNAVAILABLE);
        }
        NativeIdWitness witness;
        try (var source = opened.source()) {
            if (source.length() != receipt.completion().consumedBytes()
                    || before.file().size() != null && before.file().size() != source.length()) {
                return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
            }
            var definition =
                    resolve(
                            receipt.table().fileDataId(),
                            receipt.header().layoutHash(),
                            false,
                            token);
            if (definition.state() != TableDefinitions.State.RESOLVED) {
                return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
            }
            var nativeTable =
                    Db2Reader.open(
                                    source,
                                    definition.definition().document(),
                                    version,
                                    nativeIdLimits(),
                                    token)
                            .join();
            try {
                if (!nativeTable.header().equals(receipt.header())
                        || !nativeTable.sections().equals(receipt.sections())
                        || !sameStructuralCompletion(nativeTable.completion(), receipt.completion())
                        || !source.holes().stream()
                                .map(Db2Types.Hole::name)
                                .distinct()
                                .sorted()
                                .toList()
                                .equals(receipt.holeKeys().stream().distinct().sorted().toList())) {
                    return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
                }
                var observation = nativeTable.inspectIdDomain(selectedId, token);
                if (observation.state() != Db2Table.IdDomainState.READABLE_UNIQUE) {
                    return idResult(
                            switch (observation.state()) {
                                case PROTECTED_ID -> NativeIdDomainTypes.State.PROTECTED_ID;
                                case NOT_OBSERVED -> NativeIdDomainTypes.State.NOT_OBSERVED;
                                default -> NativeIdDomainTypes.State.UNSUPPORTED_DOMAIN;
                            });
                }
                witness = new NativeIdWitness(selectedId, observation, receipt, fingerprint);
            } finally {
                nativeTable = null;
            }
        } catch (RuntimeException failure) {
            var cause = unwrap(failure);
            if (cause instanceof Db2FormatException format) {
                return idResult(
                        format.code().equals("DB2_LIMIT")
                                ? NativeIdDomainTypes.State.LIMIT_EXCEEDED
                                : NativeIdDomainTypes.State.UNSUPPORTED_DOMAIN);
            }
            throw failure;
        }
        token.check();
        var after = files.stat(FileRef.id(receipt.table().fileDataId()), token).join();
        if (after.state() != FileTypes.FileLookupState.FOUND
                || after.file() == null
                || after.file().fileDataId() != receipt.table().fileDataId()
                || !receipt.table().contentKey().equalsIgnoreCase(after.file().contentKey())
                || after.file().size() != null && after.file().size() != witness.consumedLength()
                || !nativeIdDefinitionMatches(receipt, saved, token)
                || !nativeIdKeysMatch(receipt)) {
            return idResult(NativeIdDomainTypes.State.SOURCE_CHANGED);
        }
        token.check();
        return new NativeIdDomainTypes.Result(NativeIdDomainTypes.State.READABLE_UNIQUE, witness);
    }

    private boolean nativeIdDefinitionMatches(
            CapturedRowsTypes.Receipt receipt, GeneratedTable saved, Cancellation token) {
        var definition =
                resolve(receipt.table().fileDataId(), receipt.header().layoutHash(), false, token);
        if (definition.state() != TableDefinitions.State.RESOLVED) {
            return false;
        }
        var selected =
                definition
                        .definition()
                        .document()
                        .selectFile(version, receipt.header().layoutHash(), token);
        if (selected.blocks().size() != 1 || selected.blocks().getFirst().fields().size() > 128) {
            return false;
        }
        for (var field : selected.blocks().getFirst().fields()) {
            var column = field.column();
            if (column.name().length() > 128
                    || column.family().length() > 32
                    || column.reference() != null
                            && (column.reference().table().length() > 128
                                    || column.reference().column().length() > 128)) {
                return false;
            }
        }
        return selected.blocks().size() == 1
                && saved.source()
                        .equals(
                                GeneratedTableSource.forNativeBlock(
                                        selected.blocks().getFirst(),
                                        locale(),
                                        receipt.table().contentKey(),
                                        limits,
                                        encodingScope()));
    }

    private boolean nativeIdKeysMatch(CapturedRowsTypes.Receipt receipt) {
        var supplied = options.keys().apply(build());
        for (var key : receipt.effectiveKeys()) {
            if (!java.util.Objects.equals(key.fingerprint(), supplied.fingerprintOf(key.name()))) {
                return false;
            }
        }
        return true;
    }

    private boolean nativeIdRuleProfile() {
        if (limits.rules().size() > 128) {
            return false;
        }
        for (var rule : limits.rules()) {
            if (rule.expectedDefinitionSha256().length() > 64
                    || rule.tableHash().length() > 8
                    || rule.layoutHash().length() > 8
                    || rule.fieldName().length() > 128) {
                return false;
            }
        }
        return true;
    }

    private Db2Types.Limits nativeIdLimits() {
        return new Db2Types.Limits(
                Math.min(limits.maxDecodedBytes(), 1 << 20),
                Math.min(limits.maxRows(), 256),
                Math.min(limits.maxFields(), 128),
                Math.min(limits.maxSections(), 16),
                Math.min(limits.maxRecordBytes(), 64 << 10),
                Math.min(limits.maxAuxiliaryBytes(), 64 << 10),
                Math.min(limits.maxStringBytes(), 64 << 10),
                Math.min(limits.maxIndexBytes(), 512 << 10),
                Math.min(limits.maxColumns(), 128),
                1,
                limits.allowPartialEncryptedSections(),
                limits.allowRawTextEvidence(),
                Math.min(limits.maxRawTextEvidenceBytes(), 64 << 10),
                Math.min(limits.maxRelationshipBytes(), 64 << 10),
                Math.min(limits.maxRelationshipEntries(), 256),
                limits.definitionSha256(),
                limits.verifiedRawIntegerRules());
    }

    private static boolean sameStructuralCompletion(
            Db2Types.Completion actual, Db2Types.Completion saved) {
        return actual.declaredRecords() == saved.declaredRecords()
                && actual.physicalRows() == saved.physicalRows()
                && actual.copyRows() == saved.copyRows()
                && actual.excludedEncryptedSections() == saved.excludedEncryptedSections()
                && actual.excludedPhysicalRecords() == saved.excludedPhysicalRecords()
                && actual.excludedCopyRecords() == saved.excludedCopyRecords()
                && actual.scope().equals(saved.scope())
                && actual.consumedBytes() == saved.consumedBytes();
    }

    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    /** Test seam: false re-prepares READY known tables, so tests can compare both passes. */
    volatile boolean reuseShallowReady = true;

    /** Test seam: observes each table the preload pass prepares in full. */
    volatile java.util.function.LongConsumer deepPrepared = id -> {};

    /**
     * Shallow preparations of the known pass that may run ahead of the in-order consumer. They take
     * no scheduler slot: each is a content stat, a definition lookup and a generated-store open.
     */
    static final int SHALLOW_CONCURRENCY = 8;

    /**
     * Full preparations of a preload pass that may run ahead of the in-order consumer. Reading and
     * building still take background scheduler slots, so this mainly overlaps store and slot waits.
     */
    static final int DEEP_CONCURRENCY = 4;

    /** Test seam: observes every progress report a preload pass emits, before throttling. */
    volatile Consumer<TableTypes.PreloadProgress> emittedProgress = value -> {};

    /** Test seam: 1 runs the shallow known pass sequentially on the calling thread. */
    volatile int shallowConcurrency = SHALLOW_CONCURRENCY;

    /**
     * Test seam: 1 runs the preload pass's full preparations sequentially on the calling thread.
     */
    volatile int deepConcurrency = DEEP_CONCURRENCY;

    ClientBuildTables(
            com.vibeminer.files.Files fileSystem,
            BuildFiles files,
            String version,
            TableDefinitions definitions,
            GeneratedTableStore generated,
            TablesOptions options,
            BackgroundBuilds backgroundBuilds,
            ProtectedIds protectedIds) {
        this.fileSystem = fileSystem;
        this.files = files;
        this.version = version;
        this.definitions = definitions;
        this.generated = generated;
        this.options = options;
        this.backgroundBuilds = backgroundBuilds;
        this.protectedIds = protectedIds;
        var l = options.limits();
        limits =
                new Db2Types.Limits(
                        l.maxDecodedBytes(),
                        l.maxRows(),
                        l.maxFields(),
                        l.maxSections(),
                        l.maxRecordBytes(),
                        l.maxAuxiliaryBytes(),
                        l.maxStringBytes(),
                        l.maxIndexBytes(),
                        l.maxColumns(),
                        l.maxCopyDepth(),
                        true,
                        true,
                        l.maxRawTextEvidenceBytes(),
                        l.maxRelationshipBytes(),
                        l.maxRelationshipEntries(),
                        l.definitionSha256(),
                        l.verifiedRawIntegerRules());
        for (var table : definitions.tables()) {
            if (table.db2FileDataId() != null) {
                long id = Long.parseLong(table.db2FileDataId());
                if (id != 0) {
                    manifest.putIfAbsent(id, table);
                }
            }
        }
    }

    @Override
    public BuildRef build() {
        return files.build();
    }

    @Override
    public String locale() {
        return files.locale();
    }

    private String encodingScope() {
        return build().product() + " " + build().buildConfig() + " " + build().cdnConfig();
    }

    private long id(TableTypes.Ref ref) {
        if (ref.fileDataId() != null) {
            return ref.fileDataId();
        }
        for (var entry : manifest.entrySet()) {
            if (entry.getValue().name() != null
                    && TableQueries.namesEqual(entry.getValue().name(), ref.name())) {
                return entry.getKey();
            }
        }
        return 0;
    }

    private record Prepared(
            TableTypes.Entry entry,
            TableDefinitions.Resolution definition,
            GeneratedTable stored,
            Db2Table direct,
            Db2FileSource owner,
            String identity)
            implements AutoCloseable {
        List<Db2Types.Column> columns() {
            return stored != null
                    ? stored.header().columns()
                    : direct != null ? direct.header().columns() : null;
        }

        long count() {
            return stored != null
                    ? stored.rowCount()
                    : direct != null ? direct.readableRowCount() : 0;
        }

        CompletableFuture<List<Db2Types.Row>> read(long first, int count, Cancellation token) {
            return stored != null
                    ? stored.readPage(first, count, token)
                    : direct.readOrdinals(first, count, token);
        }

        @Override
        public void close() {
            if (owner != null) {
                owner.close();
            }
        }
    }

    private <T> CompletableFuture<T> operation(
            Cancellation token, Function<Cancellation, T> action) {
        synchronized (gate) {
            if (closed) {
                throw new IllegalStateException("Table handle closed");
            }
            active++;
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        token.check();
                        return action.apply(token);
                    } finally {
                        finish();
                    }
                },
                command -> Thread.ofVirtual().start(command));
    }

    private void finish() {
        synchronized (gate) {
            active--;
        }
        retireFilesIfReady();
    }

    private void recordCloseFailure(Throwable failure) {
        synchronized (gate) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else if (closeFailure != failure) {
                closeFailure.addSuppressed(failure);
            }
        }
    }

    private void retireFilesIfReady() {
        synchronized (gate) {
            if (!closed
                    || closeCallbacks
                    || active != 0
                    || closeCaptureDrains != 0
                    || filesClosing) {
                return;
            }
            filesClosing = true;
        }
        try {
            files.close();
        } catch (RuntimeException | Error failure) {
            recordCloseFailure(failure);
        }
        Throwable failure;
        synchronized (gate) {
            failure = closeFailure;
        }
        if (failure == null) {
            drained.complete(null);
        } else {
            drained.completeExceptionally(
                    new IllegalStateException("Table close cleanup failed", failure));
        }
    }

    CompletableFuture<Void> whenClosed() {
        return drained.copy();
    }

    private TableTypes.Entry entry(
            long id,
            TableTypes.State state,
            boolean checked,
            String code,
            String message,
            java.time.Instant at,
            String content) {
        var table = manifest.get(id);
        return new TableTypes.Entry(
                id,
                table == null ? null : table.name(),
                state,
                checked,
                code == null
                        ? null
                        : new TableTypes.Reason(code, message, at, null, null, List.of()),
                null,
                content);
    }

    private TableDefinitions.Resolution resolve(
            long id, String layout, boolean ask, Cancellation token) {
        var table = manifest.get(id);
        if (table == null) {
            return null;
        }
        ask = ask && !options.localDefinitionsOnly();
        boolean acquired = false;
        try {
            if (ask) {
                while (!(acquired =
                        asking.tryAcquire(20, java.util.concurrent.TimeUnit.MILLISECONDS))) {
                    token.check();
                }
                token.check();
            }
            return definitions
                    .resolve(
                            table.tableId(),
                            version,
                            layout,
                            WowLocale.parse(locale()),
                            ask
                                    ? TableDefinitions.Lookup.ask(
                                            fetchBudget,
                                            message -> CompletableFuture.completedFuture(null),
                                            true)
                                    : TableDefinitions.Lookup.LOCAL,
                            token)
                    .join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        } finally {
            if (acquired) {
                asking.release();
            }
        }
    }

    private Prepared failed(TableTypes.Entry entry, TableDefinitions.Resolution definition) {
        return new Prepared(entry, definition, null, null, null, null);
    }

    @SuppressWarnings("try")
    private Prepared prepare(
            TableTypes.Ref ref,
            TableTypes.Priority priority,
            boolean direct,
            boolean shallow,
            Cancellation token) {
        return prepare(ref, priority, direct, shallow, false, token);
    }

    @SuppressWarnings("try")
    private Prepared prepare(
            TableTypes.Ref ref,
            TableTypes.Priority priority,
            boolean direct,
            boolean shallow,
            boolean nativeOnly,
            Cancellation token) {
        long id = id(ref);
        // Preparation needs only lookup state, content key and storage: skip name and format.
        var stat = id == 0 ? null : files.statContent(FileRef.id(id), token).join();
        String content = stat == null || stat.file() == null ? null : stat.file().contentKey();
        if (stat == null) {
            return failed(
                    entry(
                            0,
                            TableTypes.State.NO_DEFINITION,
                            !shallow,
                            "TABLE_NOT_IN_MANIFEST",
                            "Unknown definition name",
                            null,
                            null),
                    null);
        }
        if (stat.state() != FileTypes.FileLookupState.FOUND) {
            TableTypes.State state =
                    switch (stat.state()) {
                        case NOT_IN_LOCALE -> TableTypes.State.NOT_IN_LOCALE;
                        case NOT_IN_ENCODING -> TableTypes.State.NOT_IN_ENCODING;
                        default -> TableTypes.State.NOT_IN_BUILD;
                    };
            return failed(entry(id, state, !shallow, null, null, null, content), null);
        }
        if (!manifest.containsKey(id)) {
            return failed(
                    entry(
                            id,
                            TableTypes.State.NO_DEFINITION,
                            !shallow,
                            "TABLE_NOT_IN_MANIFEST",
                            "No manifest definition",
                            null,
                            content),
                    null);
        }
        String layout = generated.layoutOf(content, token).join();
        var definition = resolve(id, layout, !shallow, token);
        if (definition.state() != TableDefinitions.State.RESOLVED) {
            return failed(definitionEntry(id, content, definition, !shallow), definition);
        }
        KeyStatus keys = options.keys().apply(build());
        GeneratedTableSource source = null;
        if (layout != null) {
            var selection = definition.definition().document().selectFile(version, layout, token);
            if (selection.blocks().size() == 1) {
                source =
                        GeneratedTableSource.forNativeBlock(
                                selection.blocks().getFirst(),
                                locale(),
                                content,
                                limits,
                                encodingScope());
                if (options.localDefinitionsOnly()
                        || !definitions
                                .refreshDue(manifest.get(id).tableId(), version, layout, token)
                                .join()) {
                    var hit = openPreferred(source, keys, token, false, nativeOnly);
                    if (hit != null) {
                        backgroundBuilds.stored(source);
                        return checked(id, content, definition, hit, null, null, hit.identity());
                    }
                }
            }
        }
        if (shallow) {
            return failed(
                    entry(
                            id,
                            stat.file().storage() == FileTypes.FileStorage.STORED
                                    ? TableTypes.State.NOT_CHECKED
                                    : TableTypes.State.NOT_STORED,
                            false,
                            null,
                            null,
                            null,
                            content),
                    definition);
        }
        try (var slot = options.scheduler().enter(priority, token).join()) {
            var opened = Db2FileSource.open(files, FileRef.id(id), token).join();
            if (opened.source() == null) {
                return failed(
                        entry(
                                id,
                                map(opened.state().name(), opened.code()),
                                true,
                                opened.code(),
                                opened.message(),
                                opened.observedAt(),
                                content),
                        definition);
            }
            var owner = opened.source();
            boolean keep = false;
            java.util.concurrent.atomic.AtomicReference<Opened> handoff = null;
            try {
                if (owner.length() > 0
                        && owner.holes().stream().mapToLong(Db2Types.Hole::length).sum()
                                == owner.length()) {
                    var known =
                            entry(
                                    id,
                                    TableTypes.State.ENCRYPTED,
                                    true,
                                    "DB2_SOURCE_PROTECTED",
                                    "Every byte needs an unknown TACT key",
                                    null,
                                    content);
                    return failed(
                            new TableTypes.Entry(
                                    known.fileDataId(),
                                    known.name(),
                                    known.state(),
                                    true,
                                    new TableTypes.Reason(
                                            known.reason().code(),
                                            known.reason().message(),
                                            null,
                                            null,
                                            Db2FileSource.OpenState.PROTECTED,
                                            owner.holes().stream()
                                                    .map(Db2Types.Hole::name)
                                                    .distinct()
                                                    .sorted()
                                                    .toList()),
                                    null,
                                    content),
                            definition);
                }
                int size = (int) Math.min(204, owner.length());
                var bytes = java.nio.ByteBuffer.allocate(size);
                owner.readRange(0, bytes, token).join();
                String actual = Db2Reader.layoutHash(bytes.array());
                if (actual != null && !actual.equalsIgnoreCase(layout == null ? "" : layout)) {
                    definition = resolve(id, actual, true, token);
                    if (definition.state() != TableDefinitions.State.RESOLVED) {
                        return failed(definitionEntry(id, content, definition, true), definition);
                    }
                }
                var table =
                        Db2Reader.open(
                                        owner,
                                        definition.definition().document(),
                                        version,
                                        limits,
                                        token)
                                .join();
                var selected =
                        definition
                                .definition()
                                .document()
                                .selectFile(version, table.header().layoutHash(), token);
                source =
                        GeneratedTableSource.forNativeBlock(
                                selected.blocks().getFirst(),
                                locale(),
                                content,
                                limits,
                                encodingScope());
                var saved = openPreferred(source, keys, token, false, nativeOnly);
                if (saved != null) {
                    backgroundBuilds.stored(source);
                    return checked(id, content, definition, saved, null, null, saved.identity());
                }
                var used = new java.util.TreeSet<String>(owner.chunkKeys());
                for (var s : table.sections()) {
                    if (s.isEncrypted()) {
                        used.add(s.key());
                    }
                }
                var holeKeys =
                        owner.holes().stream()
                                .map(Db2Types.Hole::name)
                                .distinct()
                                .sorted()
                                .toList();
                boolean encrypted = !owner.chunkKeys().isEmpty() || !holeKeys.isEmpty();
                String identity = generated.identityOf(source, keys, List.copyOf(used), encrypted);
                Map<Long, String> protectedMap = table.protectedIds(ProtectedIds.MAX_IDS);
                if (protectedMap != null) {
                    protectedIds.put(identity, protectedMap);
                }
                if (direct
                        && priority == TableTypes.Priority.INTERACTIVE
                        && table.unresolvedCopies() == 0
                        && !backgroundBuilds.hasFailed(source)) {
                    var result = checked(id, content, definition, null, table, owner, identity);
                    keep = true;
                    startBackground(id, source);
                    return result;
                }
                final var chosen = definition;
                final var chosenSource = source;
                // The first build reuses the reader opened above instead of opening and parsing
                // the file again. Whoever takes the handoff owns the source. Its decoded stream may
                // outlive this handle's files, so a shared build that outlives this call can still
                // read it.
                final var offered =
                        new java.util.concurrent.atomic.AtomicReference<>(new Opened(owner, table));
                handoff = offered;
                slot.close();
                var hit =
                        generated
                                .getOrBuild(
                                        source,
                                        keys,
                                        cancel ->
                                                buildContent(
                                                        id,
                                                        chosen,
                                                        chosenSource,
                                                        priority,
                                                        cancel,
                                                        offered),
                                        token)
                                .join();
                backgroundBuilds.stored(source);
                return checked(id, content, definition, hit, null, null, hit.identity());
            } finally {
                if (handoff != null) {
                    releaseUntaken(handoff);
                } else if (!keep) {
                    owner.close();
                }
            }
        } catch (RuntimeException failure) {
            Throwable e = unwrap(failure);
            if (e instanceof java.util.concurrent.CancellationException) {
                throw failure;
            }
            if (e instanceof TableWorkScheduler.BusyException) {
                throw failure;
            }
            if (e instanceof com.vibeminer.formats.db2.Db2SourceException sourceFailure) {
                return failed(
                        entry(
                                id,
                                map(sourceFailure.state().name(), sourceFailure.code()),
                                true,
                                sourceFailure.code(),
                                sourceFailure.getMessage(),
                                sourceFailure.observedAt(),
                                content),
                        definition);
            }
            String code =
                    e instanceof Db2FormatException d
                            ? d.code()
                            : e instanceof com.vibeminer.files.FileReadException f
                                    ? f.code()
                                    : e instanceof com.vibeminer.generated.GeneratedStoreException g
                                            ? g.code()
                                            : "TABLE_READ_FAILED";
            return failed(
                    entry(
                            id,
                            map(
                                    e instanceof com.vibeminer.files.FileReadException f
                                            ? f.state().name()
                                            : e
                                                            instanceof
                                                            com.vibeminer.generated
                                                                    .GeneratedStoreException
                                                    ? "STORE_FAILED"
                                                    : "REJECTED",
                                    code),
                            true,
                            code,
                            e.getMessage(),
                            e instanceof com.vibeminer.files.FileReadException f
                                    ? f.observedAt()
                                    : null,
                            content),
                    definition);
        }
    }

    private GeneratedTable openPreferred(
            GeneratedTableSource source, KeyStatus keys, Cancellation token, boolean local) {
        return openPreferred(source, keys, token, local, false);
    }

    private GeneratedTable openPreferred(
            GeneratedTableSource source,
            KeyStatus keys,
            Cancellation token,
            boolean local,
            boolean nativeOnly) {
        var hit =
                (local
                                ? generated.openPreparedLocal(source, keys, token)
                                : generated.open(source, keys, token))
                        .join();
        if (hit == null && !nativeOnly) {
            hit =
                    (local
                                    ? generated.openPreparedLocal(source.legacy(), keys, token)
                                    : generated.open(source.legacy(), keys, token))
                            .join();
        }
        return hit;
    }

    private static CapturedRowsTypes.IssueChannelEvidence issueEvidence(GeneratedTable table) {
        var c = table.issueCertificate();
        if (c == null) {
            return CapturedRowsTypes.IssueChannelEvidence.unknown();
        }
        return new CapturedRowsTypes.IssueChannelEvidence(
                CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                c.policyId(),
                c.producerId(),
                c.graphIdentity(),
                c.sourceIdentitySha256(),
                c.resolvedBlockSha256(),
                c.readerOptionsSha256(),
                c.actualColumnsSha256());
    }

    /** A DB2 source with the reader already opened over it, offered to one build. */
    private record Opened(Db2FileSource owner, Db2Table table) {}

    /** Closes an offered source that no build took. */
    private static void releaseUntaken(
            java.util.concurrent.atomic.AtomicReference<Opened> handoff) {
        var left = handoff.getAndSet(null);
        if (left != null) {
            left.owner().close();
        }
    }

    private GeneratedTableContent offeredContent(
            Opened offered,
            GeneratedTableSource generatedSource,
            TableWorkScheduler.Slot workSlot) {
        try {
            var source = offered.owner();
            var used = new java.util.TreeSet<String>(source.chunkKeys());
            for (var section : offered.table().sections()) {
                if (section.isEncrypted()) {
                    used.add(section.key());
                }
            }
            var holes =
                    source.holes().stream().map(Db2Types.Hole::name).distinct().sorted().toList();
            return GeneratedTableContent.nativeDb2(
                    generatedSource,
                    offered.table(),
                    List.copyOf(used),
                    () -> {
                        try {
                            offered.owner().close();
                        } finally {
                            workSlot.close();
                        }
                    },
                    holes,
                    source.chunkKeys());
        } catch (Throwable error) {
            try {
                offered.owner().close();
            } finally {
                workSlot.close();
            }
            throw error;
        }
    }

    private CompletableFuture<GeneratedTableContent> buildContent(
            long id,
            TableDefinitions.Resolution definition,
            GeneratedTableSource generatedSource,
            TableTypes.Priority priority,
            Cancellation token,
            java.util.concurrent.atomic.AtomicReference<Opened> handoff) {
        return CompletableFuture.supplyAsync(
                () -> {
                    var workSlot = options.scheduler().enter(priority, token).join();
                    Opened offered;
                    try {
                        offered = handoff.getAndSet(null);
                    } catch (Throwable error) {
                        workSlot.close();
                        throw error;
                    }
                    if (offered != null) {
                        return offeredContent(offered, generatedSource, workSlot);
                    }
                    com.vibeminer.files.FileTypes.FilesOpenResult opened;
                    try {
                        opened = fileSystem.forBuild(build(), locale(), token).join();
                    } catch (Throwable error) {
                        workSlot.close();
                        throw error;
                    }
                    if (opened.files() == null) {
                        workSlot.close();
                        throw new IllegalStateException("Shared table build files did not open");
                    }
                    var handle = opened.files();
                    Db2FileSource source = null;
                    try {
                        var read = Db2FileSource.open(handle, FileRef.id(id), token).join();
                        if (read.source() == null) {
                            throw new IllegalStateException(
                                    "Shared table source did not open: " + read.code());
                        }
                        source = read.source();
                        var table =
                                Db2Reader.open(
                                                source,
                                                definition.definition().document(),
                                                version,
                                                limits,
                                                token)
                                        .join();
                        var used = new java.util.TreeSet<String>(source.chunkKeys());
                        for (var section : table.sections()) {
                            if (section.isEncrypted()) {
                                used.add(section.key());
                            }
                        }
                        var holes =
                                source.holes().stream()
                                        .map(Db2Types.Hole::name)
                                        .distinct()
                                        .sorted()
                                        .toList();
                        final var owner = source;
                        return GeneratedTableContent.nativeDb2(
                                generatedSource,
                                table,
                                List.copyOf(used),
                                () -> {
                                    try {
                                        owner.close();
                                    } finally {
                                        try {
                                            handle.close();
                                        } finally {
                                            workSlot.close();
                                        }
                                    }
                                },
                                holes,
                                source.chunkKeys());
                    } catch (Throwable error) {
                        if (source != null) {
                            source.close();
                        }
                        try {
                            handle.close();
                        } finally {
                            workSlot.close();
                        }
                        throw error;
                    }
                },
                command -> Thread.ofVirtual().start(command));
    }

    private void startBackground(long id, GeneratedTableSource source) {
        synchronized (gate) {
            if (closed || background.containsKey(id) || !backgroundBuilds.tryStart(source)) {
                return;
            }
            var done = new CompletableFuture<Void>();
            background.put(id, done);
            active++;
            CompletableFuture.runAsync(
                    () -> {
                        boolean stored = false, failure = false;
                        try (var prepared =
                                prepare(
                                        TableTypes.Ref.id(id),
                                        TableTypes.Priority.BACKGROUND,
                                        false,
                                        false,
                                        Cancellation.NONE)) {
                            stored = prepared.stored() != null;
                            failure = !stored;
                            done.complete(null);
                        } catch (Throwable error) {
                            failure = !(unwrap(error) instanceof TableWorkScheduler.BusyException);
                            done.completeExceptionally(error);
                        } finally {
                            backgroundBuilds.end(source, stored, failure);
                            synchronized (gate) {
                                background.remove(id);
                            }
                            finish();
                        }
                    },
                    command -> Thread.ofVirtual().start(command));
        }
    }

    private Prepared checked(
            long id,
            String content,
            TableDefinitions.Resolution definition,
            GeneratedTable stored,
            Db2Table direct,
            Db2FileSource owner,
            String identity) {
        var completion = stored != null ? stored.completion() : direct.completion();
        var header = stored != null ? stored.header() : direct.header();
        long readable = stored != null ? stored.rowCount() : direct.readableRowCount();
        var sections = stored != null ? stored.sections() : direct.sections();
        var holeKeys =
                stored != null
                        ? stored.holeKeys()
                        : owner.holes().stream().map(Db2Types.Hole::name).toList();
        var missing = new java.util.TreeSet<String>(holeKeys);
        int hidden = 0;
        for (var section : sections) {
            if (section.isEncrypted() && !section.isDecrypted()) {
                hidden++;
                missing.add(section.key());
            }
        }
        var state =
                readable == 0 && hidden > 0 && hidden == sections.size()
                        ? TableTypes.State.ENCRYPTED
                        : hidden > 0 || !missing.isEmpty()
                                ? TableTypes.State.PARTIAL
                                : TableTypes.State.READY;
        TableTypes.Reason reason =
                state == TableTypes.State.READY
                        ? null
                        : new TableTypes.Reason(
                                state == TableTypes.State.ENCRYPTED
                                        ? "TABLE_ALL_SECTIONS_KEYED"
                                        : null,
                                state == TableTypes.State.ENCRYPTED
                                        ? "Every section needs a key this build does not have"
                                        : null,
                                null,
                                null,
                                Db2FileSource.OpenState.OPEN,
                                List.copyOf(missing));
        var counts =
                new TableTypes.Counts(
                        completion.declaredRecords() + header.declaredCopyRecords(),
                        readable,
                        hidden,
                        completion.excludedPhysicalRecords() + completion.excludedCopyRecords(),
                        header.format(),
                        header.layoutHash());
        return new Prepared(
                new TableTypes.Entry(
                        id, manifest.get(id).name(), state, true, reason, counts, content),
                definition,
                stored,
                direct,
                owner,
                identity);
    }

    private TableTypes.Entry definitionEntry(
            long id, String content, TableDefinitions.Resolution d, boolean checked) {
        var state =
                switch (d.state()) {
                    case NOT_IN_PROVIDER, NO_MATCH, FETCHING_OFF -> TableTypes.State.NO_DEFINITION;
                    default -> TableTypes.State.DEFINITION_UNUSABLE;
                };
        return new TableTypes.Entry(
                id,
                manifest.get(id).name(),
                state,
                checked,
                new TableTypes.Reason(
                        d.problem() == null ? null : d.problem().code(),
                        d.problem() == null ? null : d.problem().getMessage(),
                        null,
                        d.state(),
                        null,
                        List.of()),
                null,
                content);
    }

    static TableTypes.State map(String state, String code) {
        if ("REJECTED".equals(state)) {
            if (code != null && code.startsWith("DB2_SCHEMA_")) {
                return TableTypes.State.DEFINITION_UNUSABLE;
            }
            return switch (code == null ? "" : code) {
                case "DB2_UNSUPPORTED" -> TableTypes.State.UNSUPPORTED;
                case "DB2_LIMIT" -> TableTypes.State.TOO_LARGE;
                case "DB2_ENCRYPTED", "BLTE_RANGE_UNAVAILABLE" -> TableTypes.State.ENCRYPTED;
                default -> TableTypes.State.REJECTED;
            };
        }
        return switch (state) {
            case "UNKNOWN_FILE" -> TableTypes.State.NOT_IN_BUILD;
            case "PROTECTED" -> TableTypes.State.ENCRYPTED;
            default -> {
                try {
                    yield TableTypes.State.valueOf(state);
                } catch (IllegalArgumentException e) {
                    yield TableTypes.State.REJECTED;
                }
            }
        };
    }

    @Override
    public CompletableFuture<NativePreparationTypes.Result> prepareNativeEvidence(
            TableTypes.Ref ref,
            TableTypes.Priority priority,
            NativePreparationTypes.SourceAccess access,
            java.util.function.Consumer<NativePreparationTypes.Progress> progress,
            Cancellation token) {
        java.util.Objects.requireNonNull(ref);
        java.util.Objects.requireNonNull(priority);
        java.util.Objects.requireNonNull(access);
        java.util.Objects.requireNonNull(progress);
        return operation(
                token,
                t -> {
                    progress.accept(
                            new NativePreparationTypes.Progress(
                                    NativePreparationTypes.Phase.PREPARING));
                    try (var prepared = prepare(ref, priority, false, false, true, t)) {
                        t.check();
                        var table = prepared.stored();
                        if (table == null) {
                            return new NativePreparationTypes.Result(
                                    NativePreparationTypes.State.UNAVAILABLE,
                                    access,
                                    prepared.entry(),
                                    null);
                        }
                        progress.accept(
                                new NativePreparationTypes.Progress(
                                        NativePreparationTypes.Phase.VALIDATING));
                        var source = table.source();
                        var evidence = issueEvidence(table);
                        if (source.issuePolicy() != GeneratedTableSource.IssuePolicy.NATIVE_V1
                                || evidence.state()
                                        != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE) {
                            throw new IllegalStateException(
                                    "Native preparation lacks producer evidence");
                        }
                        var stat = files.stat(FileRef.id(prepared.entry().fileDataId()), t).join();
                        if (stat.file() == null
                                || !source.contentKey().equals(stat.file().contentKey())) {
                            return new NativePreparationTypes.Result(
                                    NativePreparationTypes.State.SOURCE_CHANGED,
                                    access,
                                    prepared.entry(),
                                    null);
                        }
                        var definition =
                                resolve(
                                        prepared.entry().fileDataId(),
                                        table.header().layoutHash(),
                                        false,
                                        t);
                        if (definition.state() != TableDefinitions.State.RESOLVED) {
                            return new NativePreparationTypes.Result(
                                    NativePreparationTypes.State.SOURCE_CHANGED,
                                    access,
                                    prepared.entry(),
                                    null);
                        }
                        var selected =
                                definition
                                        .definition()
                                        .document()
                                        .selectFile(version, table.header().layoutHash(), t);
                        if (selected.blocks().size() != 1
                                || !source.equals(
                                        GeneratedTableSource.forNativeBlock(
                                                selected.blocks().getFirst(),
                                                locale(),
                                                source.contentKey(),
                                                limits,
                                                encodingScope()))) {
                            return new NativePreparationTypes.Result(
                                    NativePreparationTypes.State.SOURCE_CHANGED,
                                    access,
                                    prepared.entry(),
                                    null);
                        }
                        var current =
                                generated
                                        .openPreparedLocal(source, options.keys().apply(build()), t)
                                        .join();
                        if (current == null
                                || !current.identity().equals(table.identity())
                                || !source.equals(current.source())
                                || !evidence.equals(issueEvidence(current))
                                || current.rowCount() != table.rowCount()
                                || !current.completion().equals(table.completion())
                                || !current.header().equals(table.header())
                                || !current.sections().equals(table.sections())
                                || !current.keysUsed().equals(table.keysUsed())
                                || !current.holeKeys().equals(table.holeKeys())) {
                            return new NativePreparationTypes.Result(
                                    NativePreparationTypes.State.SOURCE_CHANGED,
                                    access,
                                    prepared.entry(),
                                    null);
                        }
                        var finalStat =
                                files.stat(FileRef.id(prepared.entry().fileDataId()), t).join();
                        if (finalStat.state() != FileTypes.FileLookupState.FOUND
                                || finalStat.file() == null
                                || finalStat.file().fileDataId() != prepared.entry().fileDataId()
                                || !source.contentKey().equals(finalStat.file().contentKey())) {
                            return new NativePreparationTypes.Result(
                                    NativePreparationTypes.State.SOURCE_CHANGED,
                                    access,
                                    prepared.entry(),
                                    null);
                        }
                        t.check();
                        return new NativePreparationTypes.Result(
                                NativePreparationTypes.State.READY,
                                access,
                                prepared.entry(),
                                new NativePreparationTypes.Stamp(
                                        table.identity(),
                                        source.contentKey(),
                                        table.header().layoutHash(),
                                        evidence,
                                        table.rowCount(),
                                        table.completion()));
                    }
                });
    }

    @Override
    public CompletableFuture<TableTypes.Entry> check(
            TableTypes.Ref table, TableTypes.Priority priority, Cancellation token) {
        return operation(
                token,
                t -> {
                    try (var p = prepare(table, priority, false, false, t)) {
                        return p.entry();
                    }
                });
    }

    @Override
    public CompletableFuture<TableTypes.SchemaResult> schema(
            TableTypes.Ref table, TableTypes.Priority priority, Cancellation token) {
        return operation(
                token,
                t -> {
                    try (var p = prepare(table, priority, true, false, t)) {
                        return new TableTypes.SchemaResult(
                                p.entry(),
                                p.columns(),
                                p.definition() == null ? null : p.definition().schema());
                    }
                });
    }

    private TableTypes.Entry damaged(
            TableTypes.Entry entry, com.vibeminer.generated.GeneratedStoreException failure) {
        return new TableTypes.Entry(
                entry.fileDataId(),
                entry.name(),
                TableTypes.State.STORE_FAILED,
                true,
                new TableTypes.Reason(
                        failure.code(), failure.getMessage(), null, null, null, List.of()),
                null,
                entry.contentKey());
    }

    private CompletableFuture<List<Db2Types.Row>> scheduledRead(
            Prepared prepared,
            long first,
            int count,
            TableTypes.Priority priority,
            Cancellation token) {
        return options.scheduler()
                .enter(priority, token)
                .thenCompose(
                        slot ->
                                prepared.read(first, count, token)
                                        .whenComplete((rows, error) -> slot.close()));
    }

    @Override
    public CompletableFuture<CapturedRowsTypes.Capture> capturePreparedLocal(
            TableTypes.Ref ref, Cancellation token) {
        java.util.Objects.requireNonNull(ref);
        return captureOperation(
                token,
                t -> {
                    try {
                        if (!options.localDefinitionsOnly()) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.UNSUPPORTED,
                                    "CAPTURE_LOCAL_DEFINITIONS_REQUIRED");
                        }
                        long tableId = id(ref);
                        if (tableId == 0 || !manifest.containsKey(tableId)) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.SCHEMA_UNSUPPORTED,
                                    "CAPTURE_UNKNOWN_TABLE");
                        }
                        String content;
                        try (var inventory =
                                files.listContentInventory(
                                        new FileTypes.FileIdRange(tableId, tableId), t)) {
                            var candidates = inventory.next(2, t).join();
                            if (candidates.isEmpty()) {
                                return captureFailure(
                                        CapturedRowsTypes.CaptureState.SOURCE_UNAVAILABLE,
                                        "CAPTURE_NO_LOCAL_SOURCE");
                            }
                            if (candidates.size() != 1
                                    || candidates.getFirst().fileDataId() != tableId) {
                                return captureFailure(
                                        CapturedRowsTypes.CaptureState.CORRUPT,
                                        "CAPTURE_CONTENT_IDENTITY_MISMATCH");
                            }
                            content = candidates.getFirst().contentKey();
                            if (content == null) {
                                return captureFailure(
                                        CapturedRowsTypes.CaptureState.SOURCE_UNAVAILABLE,
                                        "CAPTURE_NO_CONTENT_KEY");
                            }
                        }
                        String layout = generated.savedLayoutOf(content, t).join();
                        if (layout == null) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.NOT_PREPARED,
                                    "CAPTURE_NO_SAVED_LAYOUT");
                        }
                        var definition = resolve(tableId, layout, false, t);
                        if (definition.state() != TableDefinitions.State.RESOLVED) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.SCHEMA_UNSUPPORTED,
                                    "CAPTURE_NO_LOCAL_DEFINITION");
                        }
                        var selection =
                                definition.definition().document().selectFile(version, layout, t);
                        if (selection.blocks().size() != 1) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.SCHEMA_UNSUPPORTED,
                                    "CAPTURE_AMBIGUOUS_BLOCK");
                        }
                        var block = selection.blocks().getFirst();
                        var source =
                                GeneratedTableSource.forNativeBlock(
                                        block, locale(), content, limits, encodingScope());
                        var answers = new java.util.LinkedHashMap<String, String>();
                        var supplied = options.keys().apply(build());
                        KeyStatus fixed =
                                name -> {
                                    if (!answers.containsKey(name)) {
                                        answers.put(name, supplied.fingerprintOf(name));
                                    }
                                    return answers.get(name);
                                };
                        var table = openPreferred(source, fixed, t, true);
                        if (table == null) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.NOT_PREPARED,
                                    "CAPTURE_NO_SAVED_GRAPH");
                        }
                        if (!layout.equalsIgnoreCase(table.header().layoutHash())
                                || !(source.equals(table.source())
                                        || source.legacy().equals(table.source()))) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.CORRUPT,
                                    "CAPTURE_GRAPH_IDENTITY_MISMATCH");
                        }
                        source = table.source();
                        var prepared =
                                checked(
                                        tableId,
                                        content,
                                        definition,
                                        table,
                                        null,
                                        null,
                                        table.identity());
                        var keys =
                                table.keysUsed().stream()
                                        .sorted()
                                        .map(
                                                name ->
                                                        new CapturedRowsTypes.KeyFingerprint(
                                                                name, fixed.fingerprintOf(name)))
                                        .toList();
                        var schema =
                                com.vibeminer.formats.definitions.TableSchema.fromBlock(
                                        block, definition.match(), WowLocale.parse(locale()));
                        var receipt =
                                new CapturedRowsTypes.Receipt(
                                        build(),
                                        locale(),
                                        prepared.entry(),
                                        schema,
                                        table.header().columns(),
                                        definition.definition().sha256(),
                                        com.vibeminer.formats.generated.ResolvedDefinition.hash(
                                                block),
                                        table.identity(),
                                        table.scoped()
                                                ? source.scopedIdentityText()
                                                : source.identityText(),
                                        source.readerOptions(),
                                        GeneratedTableSource.READER_REVISION,
                                        GeneratedTableSource.ROW_ENCODING,
                                        table.scoped() ? source.encodingScope() : null,
                                        table.rowCount(),
                                        table.header(),
                                        table.completion(),
                                        table.sections(),
                                        table.holeKeys(),
                                        keys,
                                        CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                                        CapturedRowsTypes.Membership.NOT_CERTIFIED,
                                        issueEvidence(table));
                        t.check();
                        synchronized (gate) {
                            if (closed) {
                                return captureFailure(
                                        CapturedRowsTypes.CaptureState.CANCELED,
                                        "CAPTURE_PARENT_CLOSED");
                            }
                            if (captures.size() >= 8) {
                                return captureFailure(
                                        CapturedRowsTypes.CaptureState.SOURCE_UNAVAILABLE,
                                        "CAPTURE_HANDLE_LIMIT");
                            }
                            var handle =
                                    new CapturedRowsHandle(
                                            receipt,
                                            table,
                                            this::releaseCapture,
                                            receipt.issueChannelEvidence().state()
                                                            == CapturedRowsTypes.IssueChannelState
                                                                    .VERIFIED_NATIVE
                                                    ? (selectedId, access, cancellation) ->
                                                            inspectNativeId(
                                                                    receipt,
                                                                    table,
                                                                    selectedId,
                                                                    access,
                                                                    cancellation)
                                                    : null);
                            captures.add(handle);
                            active++;
                            return new CapturedRowsTypes.Capture(
                                    CapturedRowsTypes.CaptureState.CAPTURED,
                                    handle,
                                    null,
                                    "Prepared local graph; membership is not certified");
                        }
                    } catch (Throwable failure) {
                        Throwable cause = unwrap(failure);
                        if (cause instanceof java.util.concurrent.CancellationException
                                || t.isCanceled()) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.CANCELED, "CAPTURE_CANCELED");
                        }
                        if (cause instanceof UnsupportedOperationException) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.UNSUPPORTED,
                                    "CAPTURE_CAPABILITY_UNSUPPORTED");
                        }
                        if (cause
                                        instanceof
                                        com.vibeminer.generated.GeneratedStoreException
                                                generatedFailure
                                && generatedFailure
                                        .code()
                                        .equals(com.vibeminer.generated.GeneratedTypes.CORRUPT)) {
                            return captureFailure(
                                    CapturedRowsTypes.CaptureState.CORRUPT,
                                    "CAPTURE_GRAPH_CORRUPT");
                        }
                        return captureFailure(
                                CapturedRowsTypes.CaptureState.SOURCE_UNAVAILABLE,
                                "CAPTURE_LOCAL_SOURCE_FAILED");
                    }
                });
    }

    private CompletableFuture<CapturedRowsTypes.Capture> captureOperation(
            Cancellation caller, Function<Cancellation, CapturedRowsTypes.Capture> action) {
        java.util.Objects.requireNonNull(caller);
        var request = new Cancellation();
        var delivery = new CompletableFuture<CapturedRowsTypes.Capture>();
        CompletableFuture<CapturedRowsTypes.Capture> owned;
        Cancellation.Registration registration;
        synchronized (gate) {
            if (closed) {
                throw new IllegalStateException("Table handle closed");
            }
            registration = caller.onCancel(request::cancel);
            captureRequests.add(request);
            // Private completion ownership outlives caller delivery cancellation and worker finish.
            active++;
            try {
                owned = operation(request, action);
            } catch (Throwable failure) {
                captureRequests.remove(request);
                registration.close();
                active--;
                throw failure;
            }
        }
        // Observe abandonment without returning this dependent stage to the caller.
        delivery.whenComplete(
                (value, error) -> {
                    if (delivery.isCancelled()) {
                        request.cancel();
                    }
                });
        owned.whenComplete(
                (value, error) -> {
                    var finished = new CompletableFuture<Void>();
                    try {
                        CapturedRowsTypes.Capture result =
                                error == null
                                        ? value
                                        : captureFailure(
                                                request.isCanceled()
                                                        ? CapturedRowsTypes.CaptureState.CANCELED
                                                        : CapturedRowsTypes.CaptureState
                                                                .SOURCE_UNAVAILABLE,
                                                request.isCanceled()
                                                        ? "CAPTURE_CANCELED"
                                                        : "CAPTURE_LOCAL_SOURCE_FAILED");
                        try {
                            try {
                                captureDeliveryProbe.accept(result, finished);
                            } catch (Throwable probeFailure) {
                                if (result.rows() != null) {
                                    result.rows().closeAsync().join();
                                }
                                result =
                                        captureFailure(
                                                CapturedRowsTypes.CaptureState.SOURCE_UNAVAILABLE,
                                                "CAPTURE_DELIVERY_FAILED");
                            }
                            if (request.isCanceled() || delivery.isDone()) {
                                if (result.rows() != null) {
                                    result.rows().closeAsync().join();
                                }
                                result =
                                        captureFailure(
                                                CapturedRowsTypes.CaptureState.CANCELED,
                                                "CAPTURE_DELIVERY_ABANDONED");
                            }
                        } finally {
                            synchronized (gate) {
                                captureRequests.remove(request);
                            }
                            registration.close();
                            // Release before delivery callbacks, which may close/join the parent.
                            finish();
                        }
                        // Cancellation may win after the earlier check; that handle is still
                        // privately owned.
                        if (!delivery.complete(result) && result.rows() != null) {
                            result.rows().closeAsync().join();
                        }
                        finished.complete(null);
                    } catch (Throwable failure) {
                        delivery.completeExceptionally(failure);
                        finished.completeExceptionally(failure);
                    }
                });
        return delivery;
    }

    private static CapturedRowsTypes.Capture captureFailure(
            CapturedRowsTypes.CaptureState state, String code) {
        return new CapturedRowsTypes.Capture(
                state,
                null,
                code,
                "Prepared-local capture unavailable; no fallback or membership claim");
    }

    private void releaseCapture(CapturedRowsHandle handle) {
        synchronized (gate) {
            captures.remove(handle);
        }
        finish();
    }

    @Override
    public CompletableFuture<List<com.vibeminer.generated.GeneratedKey>> savedArtifacts(
            TableTypes.Ref table, Cancellation token) {
        return operation(
                token,
                t -> {
                    long id = id(table);
                    var stat = files.stat(FileRef.id(id), t).join();
                    String content = stat.file() == null ? null : stat.file().contentKey();
                    if (content == null || !manifest.containsKey(id)) {
                        throw new IllegalStateException("No saved table source");
                    }
                    String layout = generated.savedLayoutOf(content, t).join();
                    if (layout == null) {
                        throw new IllegalStateException("No saved table layout");
                    }
                    var definition = resolve(id, layout, false, t);
                    if (definition.state() != TableDefinitions.State.RESOLVED) {
                        throw new IllegalStateException("No local table definition");
                    }
                    var selection =
                            definition.definition().document().selectFile(version, layout, t);
                    if (selection.blocks().size() != 1) {
                        throw new IllegalStateException("Ambiguous saved table definition");
                    }
                    var source =
                            GeneratedTableSource.forNativeBlock(
                                    selection.blocks().getFirst(),
                                    locale(),
                                    content,
                                    limits,
                                    encodingScope());
                    var keys = options.keys().apply(build());
                    try {
                        return generated.savedArtifacts(source, keys, t).join();
                    } catch (java.util.concurrent.CompletionException failure) {
                        if (!(unwrap(failure)
                                instanceof
                                com.vibeminer.formats.generated.GeneratedTableAbsentException)) {
                            throw failure;
                        }
                        return generated.savedArtifacts(source.legacy(), keys, t).join();
                    }
                });
    }

    record HotfixMetadata(
            TableTypes.Entry entry,
            Db2Types.Header header,
            com.vibeminer.formats.definitions.TableSchema schema,
            long rows,
            String identity,
            Db2Reader.HotfixHeaderFacts raw) {}

    CompletableFuture<HotfixMetadata> hotfixMetadata(
            TableTypes.Ref ref, TableTypes.Priority priority, Cancellation token) {
        return hotfixMetadata(ref, priority, token, true);
    }

    CompletableFuture<HotfixMetadata> hotfixMetadata(
            TableTypes.Ref ref,
            TableTypes.Priority priority,
            Cancellation token,
            boolean readRawHeader) {
        return operation(
                token,
                t -> {
                    try (var prepared = prepare(ref, priority, false, false, t)) {
                        if (!TableQueries.isReadable(prepared.entry().state())
                                || prepared.definition() == null
                                || prepared.definition().schema() == null) {
                            return null;
                        }
                        var nativeHeader =
                                prepared.stored() != null
                                        ? prepared.stored().header()
                                        : prepared.direct().header();
                        if (!readRawHeader) {
                            return new HotfixMetadata(
                                    prepared.entry(),
                                    nativeHeader,
                                    prepared.definition().schema(),
                                    prepared.count(),
                                    prepared.identity(),
                                    null);
                        }
                        long id = prepared.entry().fileDataId();
                        var before = files.stat(FileRef.id(id), t).join();
                        if (before.file() == null
                                || !java.util.Objects.equals(
                                        before.file().contentKey(),
                                        prepared.entry().contentKey())) {
                            throw new IllegalStateException("Hotfix base source changed");
                        }
                        var opened = files.openRead(FileRef.id(id), t).join();
                        if (opened.stream() == null) {
                            throw new IllegalStateException("Hotfix base header unavailable");
                        }
                        Db2Reader.HotfixHeaderFacts raw;
                        long observedLength;
                        try (var stream = opened.stream()) {
                            long length = stream.length();
                            observedLength = length;
                            if (length < 72
                                    || length > limits.maxDecodedBytes()
                                    || before.file().size() != null
                                            && before.file().size() != length) {
                                throw new IllegalStateException(
                                        "Hotfix base header length differs");
                            }
                            int size = (int) Math.min(204, length);
                            var headerHoles = stream.holes();
                            if (headerHoles.size() > limits.maxSections()) {
                                throw new IllegalStateException("Hotfix header hole limit");
                            }
                            for (var hole : headerHoles) {
                                t.check();
                                if (hole.offset() < size && hole.length() > 0) {
                                    throw new IllegalStateException(
                                            "Hotfix base header is protected");
                                }
                            }
                            var prefix = java.nio.ByteBuffer.allocate(size);
                            while (prefix.hasRemaining()) {
                                t.check();
                                int prior = prefix.position();
                                int count = stream.read(prefix, t).join();
                                t.check();
                                if (count <= 0
                                        || prefix.limit() != size
                                        || count != prefix.position() - prior) {
                                    throw new IllegalStateException(
                                            "Hotfix base header read differs");
                                }
                            }
                            raw = Db2Reader.hotfixHeaderFacts(prefix.array());
                        }
                        t.check();
                        var after = files.stat(FileRef.id(id), t).join();
                        if (after.file() == null
                                || !java.util.Objects.equals(
                                        after.file().contentKey(), prepared.entry().contentKey())
                                || after.file().size() != null
                                        && after.file().size() != observedLength
                                || !raw.format().equals(nativeHeader.format())
                                || !raw.tableHash().equalsIgnoreCase(nativeHeader.tableHash())
                                || !raw.layoutHash().equalsIgnoreCase(nativeHeader.layoutHash())) {
                            throw new IllegalStateException(
                                    "Hotfix base header/schema source differs");
                        }
                        return new HotfixMetadata(
                                prepared.entry(),
                                nativeHeader,
                                prepared.definition().schema(),
                                prepared.count(),
                                prepared.identity(),
                                raw);
                    }
                });
    }

    @Override
    public CompletableFuture<TableTypes.Page> page(
            TableTypes.PageRequest request, TableTypes.Priority priority, Cancellation token) {
        TableQueries.validate(request);
        return operation(
                token,
                t -> {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try (var p =
                                prepare(
                                        request.table(),
                                        priority,
                                        attempt == 0 && request.filters().isEmpty(),
                                        false,
                                        t)) {
                            if (!TableQueries.isReadable(p.entry().state())) {
                                return TableQueries.notReadable(p.entry());
                            }
                            try {
                                TableQueries.RowReader read =
                                        request.filters().isEmpty()
                                                ? p::read
                                                : (first, count, cancel) ->
                                                        scheduledRead(
                                                                p, first, count, priority, cancel);
                                return TableQueries.page(
                                                p.entry(),
                                                p.identity(),
                                                request,
                                                p.count(),
                                                read,
                                                p.columns(),
                                                p.definition().schema(),
                                                options.maxScanRows(),
                                                t)
                                        .join();
                            } catch (RuntimeException error) {
                                Throwable cause = unwrap(error);
                                if (p.direct() != null
                                        && (cause instanceof Db2FormatException
                                                || cause
                                                        instanceof
                                                        com.vibeminer.formats.db2
                                                                .Db2SourceException)) {
                                    continue;
                                }
                                if (cause
                                        instanceof
                                        com.vibeminer.generated.GeneratedStoreException failure) {
                                    return TableQueries.notReadable(damaged(p.entry(), failure));
                                }
                                throw error;
                            }
                        }
                    }
                    throw new IllegalStateException("Direct table fallback did not terminate");
                });
    }

    @Override
    public CompletableFuture<TableTypes.RowResult> get(
            TableTypes.Ref table, long id, TableTypes.Priority priority, Cancellation token) {
        if (id < 0 || id > 0xffffffffL) {
            throw new IllegalArgumentException("Unsigned32 ID required");
        }
        return operation(
                token,
                t -> {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try (var p = prepare(table, priority, attempt == 0, false, t)) {
                            if (p.direct() == null && p.stored() == null) {
                                return new TableTypes.RowResult(
                                        p.entry(), TableTypes.RowOutcome.UNKNOWN, null, null);
                            }
                            try {
                                if (p.direct() != null) {
                                    var found = p.direct().findWithOrdinal(id, t).join();
                                    if (found.lookup() instanceof Db2Types.Found row) {
                                        return new TableTypes.RowResult(
                                                p.entry(),
                                                TableTypes.RowOutcome.FOUND,
                                                new TableTypes.Row(found.ordinal(), row.row()),
                                                null);
                                    }
                                    if (p.entry().state() == TableTypes.State.READY) {
                                        return new TableTypes.RowResult(
                                                p.entry(),
                                                TableTypes.RowOutcome.ABSENT,
                                                null,
                                                null);
                                    }
                                    if (found.lookup() instanceof Db2Types.Protected protectedRow) {
                                        return new TableTypes.RowResult(
                                                p.entry(),
                                                TableTypes.RowOutcome.PROTECTED,
                                                null,
                                                protectedRow.key());
                                    }
                                } else {
                                    var found = p.stored().findWithOrdinal(id, t).join();
                                    if (found != null) {
                                        return new TableTypes.RowResult(
                                                p.entry(),
                                                TableTypes.RowOutcome.FOUND,
                                                new TableTypes.Row(
                                                        found.ordinal(),
                                                        ((Db2Types.Found) found.lookup()).row()),
                                                null);
                                    }
                                    if (p.entry().state() == TableTypes.State.READY) {
                                        return new TableTypes.RowResult(
                                                p.entry(),
                                                TableTypes.RowOutcome.ABSENT,
                                                null,
                                                null);
                                    }
                                }
                                String key = protectedKey(p, id, priority, t);
                                return new TableTypes.RowResult(
                                        p.entry(),
                                        key == null
                                                ? TableTypes.RowOutcome.UNKNOWN
                                                : TableTypes.RowOutcome.PROTECTED,
                                        null,
                                        key);
                            } catch (RuntimeException error) {
                                Throwable cause = unwrap(error);
                                if (p.direct() != null
                                        && (cause instanceof Db2FormatException
                                                || cause
                                                        instanceof
                                                        com.vibeminer.formats.db2
                                                                .Db2SourceException)) {
                                    continue;
                                }
                                if (cause
                                        instanceof
                                        com.vibeminer.generated.GeneratedStoreException failure) {
                                    return new TableTypes.RowResult(
                                            damaged(p.entry(), failure),
                                            TableTypes.RowOutcome.UNKNOWN,
                                            null,
                                            null);
                                }
                                throw error;
                            }
                        }
                    }
                    throw new IllegalStateException("Direct table fallback did not terminate");
                });
    }

    @SuppressWarnings("try")
    private String protectedKey(
            Prepared prepared, long id, TableTypes.Priority priority, Cancellation token) {
        var known = protectedIds.get(prepared.identity());
        if (known != null) {
            return known.get(id);
        }
        try (var slot = options.scheduler().enter(priority, token).join();
                var opened =
                        Db2Reader.open(
                                        files,
                                        FileRef.id(prepared.entry().fileDataId()),
                                        prepared.definition().definition().document(),
                                        version,
                                        limits,
                                        token)
                                .join()) {
            if (opened.table() == null) {
                return null;
            }
            var ids = opened.table().protectedIds(ProtectedIds.MAX_IDS);
            if (ids != null) {
                protectedIds.put(prepared.identity(), ids);
                return ids.get(id);
            }
            var lookup = opened.table().find(id, token).join();
            return lookup instanceof Db2Types.Protected hit ? hit.key() : null;
        }
    }

    @Override
    public AsyncCursor<TableTypes.Entry> list(
            TableTypes.ListFilter filter, TableTypes.Priority priority, Cancellation token) {
        java.util.Objects.requireNonNull(filter);
        var all = operation(token, t -> listCore(filter, priority, t));
        return new AsyncCursor<>() {
            int at;
            boolean ended;

            @Override
            public synchronized CompletableFuture<List<TableTypes.Entry>> next(
                    int limit, Cancellation t) {
                if (limit < 1) {
                    throw new IllegalArgumentException("Positive page size required");
                }
                if (ended) {
                    return CompletableFuture.completedFuture(List.of());
                }
                return all.thenApply(
                        entries -> {
                            synchronized (this) {
                                t.check();
                                int end = Math.min(entries.size(), at + limit);
                                var page = List.copyOf(entries.subList(at, end));
                                at = end;
                                return page;
                            }
                        });
            }

            @Override
            public synchronized void close() {
                ended = true;
            }
        };
    }

    private List<TableTypes.Entry> listCore(
            TableTypes.ListFilter filter, TableTypes.Priority priority, Cancellation token) {
        var ids = new java.util.TreeSet<Long>(manifest.keySet());
        try (var cursor =
                files.list(
                        new FileTypes.FileFilter(
                                0,
                                0xffffffffL,
                                "dbfilesclient/",
                                java.util.Set.of(FileTypes.FileFormat.DB2),
                                null,
                                null),
                        token)) {
            for (; ; ) {
                var batch = cursor.next(1024, token).join();
                if (batch.isEmpty()) {
                    break;
                }
                for (var file : batch) {
                    ids.add(file.fileDataId());
                }
            }
        }
        var result = new ArrayList<TableTypes.Entry>();
        for (long id : ids) {
            token.check();
            try (var p = prepare(TableTypes.Ref.id(id), priority, false, true, token)) {
                if (TableQueries.matches(filter, p.entry())) {
                    result.add(p.entry());
                }
            }
        }
        return List.copyOf(result);
    }

    static Throwable unwrap(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        List<Cancellation> stopping;
        List<CapturedRowsHandle> stoppingCaptures;
        synchronized (gate) {
            if (closeStarted) {
                return drained.copy();
            }
            closed = true;
            closeStarted = true;
            closeCallbacks = true;
            var stoppingAll = new ArrayList<>(preloads);
            stoppingAll.addAll(captureRequests);
            stopping = List.copyOf(stoppingAll);
            stoppingCaptures = List.copyOf(captures);
        }
        try {
            for (var cancellation : stopping) {
                try {
                    cancellation.cancel();
                } catch (RuntimeException | Error failure) {
                    recordCloseFailure(failure);
                }
            }
            for (var capture : stoppingCaptures) {
                synchronized (gate) {
                    closeCaptureDrains++;
                }
                try {
                    capture.closeAsync()
                            .whenComplete(
                                    (ignored, failure) -> {
                                        if (failure != null) {
                                            recordCloseFailure(failure);
                                        }
                                        synchronized (gate) {
                                            closeCaptureDrains--;
                                        }
                                        retireFilesIfReady();
                                    });
                } catch (RuntimeException | Error failure) {
                    recordCloseFailure(failure);
                    synchronized (gate) {
                        closeCaptureDrains--;
                    }
                }
            }
        } finally {
            synchronized (gate) {
                closeCallbacks = false;
            }
            retireFilesIfReady();
        }
        return drained.copy();
    }

    private TableRelations.Source relations(TableTypes.Priority priority) {
        return new TableRelations.Source() {
            @Override
            public CompletableFuture<TableRelations.RelationTable> open(
                    TableTypes.Ref ref, Cancellation token) {
                return operation(
                        token,
                        t -> {
                            try (var p = prepare(ref, priority, false, false, t)) {
                                return new TableRelations.RelationTable(
                                        p.entry(),
                                        p.columns(),
                                        p.definition() == null ? null : p.definition().schema(),
                                        p.count(),
                                        p.stored() == null
                                                ? null
                                                : (first, count, cancel) ->
                                                        scheduledRead(
                                                                p, first, count, priority, cancel));
                            }
                        });
            }

            @Override
            public CompletableFuture<TableTypes.RowResult> get(
                    TableTypes.Ref ref, long id, Cancellation token) {
                return ClientBuildTables.this.get(ref, id, priority, token);
            }

            @Override
            public CompletableFuture<List<TableTypes.Entry>> list(Cancellation token) {
                return operation(token, t -> listCore(TableTypes.ListFilter.ALL, priority, t));
            }

            @Override
            public CompletableFuture<TableRelations.RelationSchema> schema(
                    TableTypes.Entry table, Cancellation token) {
                return operation(
                        token,
                        t -> {
                            if (table.state() == TableTypes.State.NO_DEFINITION) {
                                return new TableRelations.RelationSchema(
                                        TableRelations.SchemaState.NO_DEFINITION, null);
                            }
                            if (table.state() == TableTypes.State.DEFINITION_UNUSABLE) {
                                return new TableRelations.RelationSchema(
                                        TableRelations.SchemaState.UNRESOLVED, null);
                            }
                            var definition =
                                    resolve(
                                            table.fileDataId(),
                                            table.counts() == null
                                                    ? null
                                                    : table.counts().layoutHash(),
                                            false,
                                            t);
                            if (definition == null
                                    || definition.state()
                                            == TableDefinitions.State.NOT_IN_PROVIDER) {
                                return new TableRelations.RelationSchema(
                                        TableRelations.SchemaState.NO_DEFINITION, null);
                            }
                            return new TableRelations.RelationSchema(
                                    definition.state() == TableDefinitions.State.RESOLVED
                                                    && definition.schema() != null
                                            ? TableRelations.SchemaState.RESOLVED
                                            : TableRelations.SchemaState.UNRESOLVED,
                                    definition.schema());
                        });
            }
        };
    }

    @Override
    public CompletableFuture<TableTypes.FollowResult> follow(
            TableTypes.FollowRequest request, TableTypes.Priority priority, Cancellation token) {
        return operation(
                token,
                t ->
                        TableRelations.follow(
                                        relations(priority), request, options.maxScanRows(), t)
                                .join());
    }

    @Override
    public CompletableFuture<TableTypes.ReferencesResult> referencedBy(
            TableTypes.ReferencesRequest request,
            TableTypes.Priority priority,
            Cancellation token) {
        return operation(
                token,
                t ->
                        TableRelations.referencedBy(
                                        relations(priority),
                                        request,
                                        options.maxScanRows(),
                                        options.maxReferenceSources(),
                                        options.maxReferenceScanRows(),
                                        t)
                                .join());
    }

    @Override
    public CompletableFuture<TableTypes.PreloadResult> preload(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        return runPreload(progress, token, false);
    }

    @Override
    public CompletableFuture<TableTypes.PreloadResult> prepareKnown(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        return runPreload(progress, token, true).copy();
    }

    private CompletableFuture<TableTypes.PreloadResult> runPreload(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token, boolean known) {
        var local = new Cancellation();
        synchronized (gate) {
            if (closed) {
                throw new IllegalStateException("Table handle closed");
            }
            preloads.add(local);
        }
        var registration = token.onCancel(local::cancel);
        var delivery = new ProgressOutbox(progress);
        CompletableFuture<TableTypes.PreloadResult> work;
        try {
            var probe = emittedProgress;
            work =
                    preloadCore(
                            value -> {
                                probe.accept(value);
                                delivery.publish(value);
                            },
                            local,
                            known);
        } catch (RuntimeException error) {
            registration.close();
            synchronized (gate) {
                preloads.remove(local);
            }
            throw error;
        }
        return work.whenComplete(
                        (value, error) -> {
                            registration.close();
                            synchronized (gate) {
                                preloads.remove(local);
                            }
                        })
                .thenCompose(
                        result -> delivery.finish(result.progress()).thenApply(ignored -> result));
    }

    private CompletableFuture<TableTypes.PreloadResult> preloadCore(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token, boolean known) {
        return operation(
                Cancellation.NONE,
                ignored -> {
                    var failures = new ArrayList<TableTypes.Entry>();
                    var ledger = new ArrayList<TableTypes.Entry>();
                    var current =
                            new java.util.concurrent.atomic.AtomicReference<>(
                                    new TableTypes.PreloadProgress(
                                            TableTypes.PreloadStage.DOWNLOADING,
                                            0,
                                            0,
                                            0,
                                            0,
                                            0,
                                            0,
                                            0,
                                            0,
                                            0));
                    // Full prepares still running ahead of this loop; closed on every exit.
                    OrderedLookahead<Long, TableTypes.Entry> ahead = null;
                    try {
                        token.check();
                        var entries =
                                known
                                        ? knownEntries(token)
                                        : listCore(
                                                TableTypes.ListFilter.ALL,
                                                TableTypes.Priority.BACKGROUND,
                                                token);
                        var eligible =
                                entries.stream()
                                        .filter(
                                                e ->
                                                        (known
                                                                        || (e.state()
                                                                                        != TableTypes
                                                                                                .State
                                                                                                .NO_DEFINITION
                                                                                && e.state()
                                                                                        != TableTypes
                                                                                                .State
                                                                                                .DEFINITION_UNUSABLE))
                                                                && e.state()
                                                                        != TableTypes.State
                                                                                .NOT_IN_BUILD
                                                                && e.state()
                                                                        != TableTypes.State
                                                                                .NOT_IN_LOCALE)
                                        .toList();
                        if (known) {
                            for (var item : entries) {
                                if (item.state() == TableTypes.State.NOT_IN_BUILD
                                        || item.state() == TableTypes.State.NOT_IN_LOCALE) {
                                    ledger.add(item);
                                }
                            }
                        }
                        var requested =
                                known
                                        ? eligible.stream()
                                                .filter(
                                                        item ->
                                                                !TableQueries.isReadable(
                                                                        item.state()))
                                                .toList()
                                        : entries;
                        var report =
                                new TableTypes.PreloadProgress(
                                        TableTypes.PreloadStage.DOWNLOADING,
                                        0,
                                        requested.size(),
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        eligible.size(),
                                        0);
                        emit(progress, report);
                        current.set(report);
                        var ensured =
                                files.ensure(
                                                FileTypes.EnsureSelection.many(
                                                        requested.stream()
                                                                .map(
                                                                        e ->
                                                                                FileRef.id(
                                                                                        e
                                                                                                .fileDataId()))
                                                                .toList()),
                                                FileTypes.EnsurePriority.BACKGROUND,
                                                p -> {
                                                    var old = current.get();
                                                    var next =
                                                            new TableTypes.PreloadProgress(
                                                                    TableTypes.PreloadStage
                                                                            .DOWNLOADING,
                                                                    Math.max(
                                                                            old.filesDone(),
                                                                            p.filesDone()),
                                                                    Math.max(
                                                                            old.filesTotal(),
                                                                            p.filesTotal()),
                                                                    old.filesFailed(),
                                                                    Math.max(
                                                                            old.bytesDone(),
                                                                            p.bytesDone()),
                                                                    Math.max(
                                                                            old.bytesTotal(),
                                                                            p.bytesTotal()),
                                                                    Math.max(
                                                                            old.cdnBytes(),
                                                                            p.cdnBytes()),
                                                                    0,
                                                                    eligible.size(),
                                                                    0);
                                                    current.set(next);
                                                    emit(progress, next);
                                                },
                                                token)
                                        .join();
                        if (known && ensured.outcome() == FileTypes.EnsureOutcome.CANCELED) {
                            throw new java.util.concurrent.CancellationException();
                        }
                        var byId = new HashMap<Long, FileTypes.FileEnsured>();
                        for (var file : ensured.files()) {
                            if (file.fileDataId() != null) {
                                byId.put(file.fileDataId(), file);
                            }
                        }
                        long filesFailed =
                                ensured.files().stream()
                                        .filter(
                                                f ->
                                                        f.state()
                                                                        != FileTypes.FileEnsureState
                                                                                .STORED
                                                                && f.state()
                                                                        != FileTypes.FileEnsureState
                                                                                .ALREADY_STORED)
                                        .count();
                        int done = 0;
                        // The shallow pass already opened a READY table's existing generated graph
                        // with the same local-only definition lookup a full prepare uses, so
                        // preparing it again yields the same entry. With fetching enabled a full
                        // prepare may ask for a newer definition instead.
                        boolean reuseReady =
                                known && reuseShallowReady && options.localDefinitionsOnly();
                        java.util.function.Predicate<TableTypes.Entry> fullPrepare =
                                table -> {
                                    if (reuseReady && table.state() == TableTypes.State.READY) {
                                        return false;
                                    }
                                    var file = byId.get(table.fileDataId());
                                    return file == null
                                            || file.state() == FileTypes.FileEnsureState.STORED
                                            || file.state()
                                                    == FileTypes.FileEnsureState.ALREADY_STORED
                                            || (known && TableQueries.isReadable(table.state()));
                                };
                        // A known pass stops at its first canceled file; nothing after it runs.
                        int stop = eligible.size();
                        if (known) {
                            for (int i = 0; i < eligible.size(); i++) {
                                var file = byId.get(eligible.get(i).fileDataId());
                                if (file != null
                                        && file.state() == FileTypes.FileEnsureState.CANCELED) {
                                    stop = i;
                                    break;
                                }
                            }
                        }
                        int limit = deepConcurrency;
                        // Full prepares are independent: run a bounded window of them ahead while
                        // this loop still consumes, records and reports every table in list order.
                        if (limit > 1) {
                            ahead = new OrderedLookahead<>(limit, token);
                        }
                        int issued = 0;
                        for (var table : eligible) {
                            token.check();
                            synchronized (gate) {
                                if (closed) {
                                    throw new java.util.concurrent.CancellationException();
                                }
                            }
                            if (ahead != null) {
                                while (issued < stop && ahead.hasRoom()) {
                                    var next = eligible.get(issued++);
                                    if (!fullPrepare.test(next)) {
                                        continue;
                                    }
                                    token.check();
                                    synchronized (gate) {
                                        if (closed) {
                                            throw new java.util.concurrent.CancellationException();
                                        }
                                    }
                                    long nextId = next.fileDataId();
                                    deepPrepared.accept(nextId);
                                    ahead.start(nextId, scope -> deepEntry(nextId, scope));
                                }
                            }
                            var file = byId.get(table.fileDataId());
                            TableTypes.Entry checked;
                            if (known
                                    && file != null
                                    && file.state() == FileTypes.FileEnsureState.CANCELED) {
                                throw new java.util.concurrent.CancellationException();
                            }
                            if (reuseReady && table.state() == TableTypes.State.READY) {
                                checked = table;
                            } else if (file != null
                                    && file.state() != FileTypes.FileEnsureState.STORED
                                    && file.state() != FileTypes.FileEnsureState.ALREADY_STORED
                                    && !(known && TableQueries.isReadable(table.state()))) {
                                checked =
                                        entry(
                                                table.fileDataId(),
                                                file.state() == FileTypes.FileEnsureState.REFUSED
                                                        ? TableTypes.State.CORRUPT
                                                        : file.state()
                                                                                == FileTypes
                                                                                        .FileEnsureState
                                                                                        .UNKNOWN_FILE
                                                                        || file.state()
                                                                                == FileTypes
                                                                                        .FileEnsureState
                                                                                        .CANCELED
                                                                ? TableTypes.State.NOT_IN_BUILD
                                                                : map(
                                                                        file.state().name(),
                                                                        file.code()),
                                                true,
                                                file.code(),
                                                "File ensure failed",
                                                null,
                                                table.contentKey());
                            } else if (ahead != null) {
                                checked = ahead.take(table.fileDataId());
                            } else {
                                deepPrepared.accept(table.fileDataId());
                                checked = deepEntry(table.fileDataId(), token);
                            }
                            if (known) {
                                ledger.add(checked);
                            }
                            if (!TableQueries.isReadable(checked.state())
                                    && checked.state() != TableTypes.State.ENCRYPTED) {
                                failures.add(checked);
                            }
                            done++;
                            var p = ensured.progress();
                            report =
                                    new TableTypes.PreloadProgress(
                                            TableTypes.PreloadStage.PREPARING,
                                            p.filesDone(),
                                            p.filesTotal(),
                                            filesFailed,
                                            p.bytesDone(),
                                            p.bytesTotal(),
                                            p.cdnBytes(),
                                            done,
                                            eligible.size(),
                                            failures.size());
                            current.set(report);
                            emit(progress, report);
                        }
                        token.check();
                        if (known) {
                            ledger.sort(
                                    java.util.Comparator.comparingLong(
                                            TableTypes.Entry::fileDataId));
                        }
                        return new TableTypes.PreloadResult(
                                known
                                        ? (ledger.stream()
                                                        .anyMatch(
                                                                item ->
                                                                        item.state()
                                                                                == TableTypes.State
                                                                                        .STORE_FAILED)
                                                ? TableTypes.PreloadOutcome.FAILED
                                                : ledger.stream()
                                                                .anyMatch(
                                                                        item ->
                                                                                item.state()
                                                                                        == TableTypes
                                                                                                .State
                                                                                                .NOT_CHECKED)
                                                        ? TableTypes.PreloadOutcome.INCOMPLETE
                                                        : TableTypes.PreloadOutcome.COMPLETED)
                                        : filesFailed == 0 && failures.isEmpty()
                                                ? TableTypes.PreloadOutcome.COMPLETED
                                                : TableTypes.PreloadOutcome.INCOMPLETE,
                                current.get(),
                                known ? ledger : failures,
                                null,
                                null,
                                null);
                    } catch (RuntimeException e) {
                        var cause = unwrap(e);
                        boolean canceled =
                                token.isCanceled()
                                        || cause
                                                instanceof
                                                java.util.concurrent.CancellationException;
                        emit(progress, current.get());
                        return new TableTypes.PreloadResult(
                                canceled
                                        ? TableTypes.PreloadOutcome.CANCELED
                                        : TableTypes.PreloadOutcome.FAILED,
                                current.get(),
                                known ? ledger : failures,
                                null,
                                canceled ? null : "TABLE_PRELOAD_FAILED",
                                canceled ? null : cause.getMessage());
                    } finally {
                        if (ahead != null) {
                            ahead.close();
                        }
                    }
                });
    }

    private List<TableTypes.Entry> knownEntries(Cancellation token) {
        var entries = new ArrayList<TableTypes.Entry>();
        // Manifest is copied from the definition provider at handle creation. Community filename
        // enrichment cannot expand or change the denominator during this pass.
        var ids = List.copyOf(manifest.keySet());
        int limit = shallowConcurrency;
        if (limit <= 1) {
            for (long id : ids) {
                token.check();
                entries.add(shallowEntry(id, token));
            }
            return List.copyOf(entries);
        }
        // Each shallow prepare is independent; run a bounded window ahead and keep list order.
        try (var ahead = new OrderedLookahead<Long, TableTypes.Entry>(limit, token)) {
            int next = 0;
            for (long id : ids) {
                token.check();
                while (next < ids.size() && ahead.hasRoom()) {
                    token.check();
                    long started = ids.get(next++);
                    ahead.start(started, scope -> shallowEntry(started, scope));
                }
                entries.add(ahead.take(id));
            }
        }
        return List.copyOf(entries);
    }

    private TableTypes.Entry deepEntry(long id, Cancellation token) {
        try (var p =
                prepare(
                        TableTypes.Ref.id(id),
                        TableTypes.Priority.BACKGROUND,
                        false,
                        false,
                        token)) {
            return p.entry();
        }
    }

    private TableTypes.Entry shallowEntry(long id, Cancellation token) {
        try (var prepared =
                prepare(
                        TableTypes.Ref.id(id),
                        TableTypes.Priority.BACKGROUND,
                        false,
                        true,
                        token)) {
            var item = prepared.entry();
            return new TableTypes.Entry(
                    item.fileDataId(),
                    item.name(),
                    item.state(),
                    true,
                    item.reason(),
                    item.counts(),
                    item.contentKey());
        }
    }

    private static void emit(
            Consumer<TableTypes.PreloadProgress> progress, TableTypes.PreloadProgress value) {
        if (progress != null) {
            try {
                progress.accept(value);
            } catch (Throwable ignored) {
            }
        }
    }
}
