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
    private boolean closed;
    private int active;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

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
        boolean done;
        synchronized (gate) {
            active--;
            done = closed && active == 0;
        }
        if (done) {
            files.close();
            drained.complete(null);
        }
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
        long id = id(ref);
        var stat = id == 0 ? null : files.stat(FileRef.id(id), token).join();
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
                        GeneratedTableSource.forBlock(
                                selection.blocks().getFirst(),
                                locale(),
                                content,
                                limits,
                                encodingScope());
                if (options.localDefinitionsOnly()
                        || !definitions
                                .refreshDue(manifest.get(id).tableId(), version, layout, token)
                                .join()) {
                    var hit = generated.open(source, keys, token).join();
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
                        GeneratedTableSource.forBlock(
                                selected.blocks().getFirst(),
                                locale(),
                                content,
                                limits,
                                encodingScope());
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
                slot.close();
                var hit =
                        generated
                                .getOrBuild(
                                        source,
                                        keys,
                                        cancel -> buildContent(id, chosen, priority, cancel),
                                        token)
                                .join();
                backgroundBuilds.stored(source);
                return checked(id, content, definition, hit, null, null, hit.identity());
            } finally {
                if (!keep) {
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

    private CompletableFuture<GeneratedTableContent> buildContent(
            long id,
            TableDefinitions.Resolution definition,
            TableTypes.Priority priority,
            Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    var workSlot = options.scheduler().enter(priority, token).join();
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
                        return new GeneratedTableContent(
                                table.header(),
                                table.sections(),
                                table.completion(),
                                List.copyOf(used),
                                table::readOrdinals,
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
                            GeneratedTableSource.forBlock(
                                    selection.blocks().getFirst(),
                                    locale(),
                                    content,
                                    limits,
                                    encodingScope());
                    return generated
                            .savedArtifacts(source, options.keys().apply(build()), t)
                            .join();
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
        boolean close;
        List<Cancellation> stopping;
        synchronized (gate) {
            closed = true;
            stopping = List.copyOf(preloads);
            close = active == 0;
        }
        for (var cancellation : stopping) {
            cancellation.cancel();
        }
        if (close) {
            files.close();
            drained.complete(null);
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
            work = preloadCore(delivery::publish, local, known);
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
                        for (var table : eligible) {
                            token.check();
                            synchronized (gate) {
                                if (closed) {
                                    throw new java.util.concurrent.CancellationException();
                                }
                            }
                            var file = byId.get(table.fileDataId());
                            TableTypes.Entry checked;
                            if (known
                                    && file != null
                                    && file.state() == FileTypes.FileEnsureState.CANCELED) {
                                throw new java.util.concurrent.CancellationException();
                            }
                            if (file != null
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
                            } else {
                                try (var p =
                                        prepare(
                                                TableTypes.Ref.id(table.fileDataId()),
                                                TableTypes.Priority.BACKGROUND,
                                                false,
                                                false,
                                                token)) {
                                    checked = p.entry();
                                }
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
                    }
                });
    }

    private List<TableTypes.Entry> knownEntries(Cancellation token) {
        var entries = new ArrayList<TableTypes.Entry>();
        // Manifest is copied from the definition provider at handle creation. Community filename
        // enrichment cannot expand or change the denominator during this pass.
        for (long id : List.copyOf(manifest.keySet())) {
            token.check();
            try (var prepared =
                    prepare(
                            TableTypes.Ref.id(id),
                            TableTypes.Priority.BACKGROUND,
                            false,
                            true,
                            token)) {
                var item = prepared.entry();
                entries.add(
                        new TableTypes.Entry(
                                item.fileDataId(),
                                item.name(),
                                item.state(),
                                true,
                                item.reason(),
                                item.counts(),
                                item.contentKey()));
            }
        }
        return List.copyOf(entries);
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
