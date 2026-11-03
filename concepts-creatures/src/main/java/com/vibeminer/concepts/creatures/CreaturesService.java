package com.vibeminer.concepts.creatures;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.ConceptQueries;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearance;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearanceState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDetail;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplaySlot;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureModel;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureModelState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureRecordFacts;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSlotState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureSummary;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTexture;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTextureEvidence;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTextureState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesOpenResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPage;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPageState;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileLocales;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.Files;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.TableTypes.Priority;
import com.vibeminer.formats.tables.Tables;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Creature facts via layer five. Lists never read display tables, models or image bytes. */
public final class CreaturesService implements Creatures {
    private final Tables tables;
    private final Files files;
    private final BlpImages images;
    private final CreaturesOptions options;

    public CreaturesService(Tables tables, Files files, BlpImages images) {
        this(tables, files, images, new CreaturesOptions());
    }

    public CreaturesService(
            Tables tables, Files files, BlpImages images, CreaturesOptions options) {
        this.tables = Objects.requireNonNull(tables);
        this.files = Objects.requireNonNull(files);
        this.images = Objects.requireNonNull(images);
        this.options = Objects.requireNonNull(options);
    }

    @Override
    public CompletableFuture<CreaturesOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        Objects.requireNonNull(build);
        FileLocales.bitOf(locale);
        token.check();
        CompletableFuture<CreaturesOpenResult> result = new CompletableFuture<>();
        tables.forBuild(build, locale, token)
                .whenComplete(
                        (open, error) -> {
                            if (error != null) {
                                result.completeExceptionally(CreatureWork.unwrap(error));
                                return;
                            }
                            Handle handle =
                                    open.state() == FileTypes.FilesOpenState.OPEN
                                                    && open.tables() != null
                                            ? new Handle(open.tables())
                                            : null;
                            try {
                                if (handle == null && open.tables() != null) {
                                    open.tables().closeAsync().join();
                                }
                                token.check();
                                if (!result.complete(
                                                new CreaturesOpenResult(
                                                        build,
                                                        locale,
                                                        open.state(),
                                                        handle,
                                                        open.code(),
                                                        open.message(),
                                                        open.observedAt()))
                                        && handle != null) {
                                    handle.closeAsync();
                                }
                            } catch (Throwable failed) {
                                if (handle != null) {
                                    handle.closeAsync();
                                }
                                result.completeExceptionally(failed);
                            }
                        });
        return result;
    }

    private final class Handle implements BuildCreatures {
        private final BuildTables tables;
        private final Object gate = new Object();
        private int active;
        private boolean closed, cleaning;
        private final CompletableFuture<Void> drained = new CompletableFuture<>();
        private final ReentrantLock filesGate = new ReentrantLock();
        private BuildFiles buildFiles;
        private BuildModels models;

        Handle(BuildTables tables) {
            this.tables = tables;
        }

        @Override
        public BuildRef build() {
            return tables.build();
        }

        @Override
        public String locale() {
            return tables.locale();
        }

        private <T> CompletableFuture<T> run(
                Priority priority, Cancellation token, Supplier<T> action) {
            Objects.requireNonNull(priority);
            Objects.requireNonNull(token);
            synchronized (gate) {
                if (closed) {
                    throw new IllegalStateException("Creature handle closed");
                }
                active++;
            }
            CompletableFuture<T> outward = new CompletableFuture<>();
            CreatureWork.submit(
                            () -> {
                                token.check();
                                T value = action.get();
                                token.check();
                                return value;
                            })
                    .whenComplete(
                            (value, error) -> {
                                synchronized (gate) {
                                    active--;
                                    cleanup();
                                }
                                if (error == null) {
                                    outward.complete(value);
                                } else {
                                    outward.completeExceptionally(CreatureWork.unwrap(error));
                                }
                            });
            return outward;
        }

        private void cleanup() {
            if (!closed || active != 0 || cleaning) {
                return;
            }
            cleaning = true;
            Thread.startVirtualThread(
                    () -> {
                        Throwable error = null;
                        try {
                            if (buildFiles != null) {
                                buildFiles.close();
                            }
                        } catch (Throwable e) {
                            error = e;
                        }
                        try {
                            tables.closeAsync().join();
                        } catch (Throwable e) {
                            if (error == null) {
                                error = e;
                            } else {
                                error.addSuppressed(e);
                            }
                        }
                        if (error == null) {
                            drained.complete(null);
                        } else {
                            drained.completeExceptionally(CreatureWork.unwrap(error));
                        }
                    });
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            synchronized (gate) {
                closed = true;
                cleanup();
                return drained.copy();
            }
        }

        private CreaturesPage page(
                CreaturesPageState state,
                List<CreatureSummary> entries,
                String next,
                long scanned,
                TableTypes.Entry table,
                TableTypes.RowOutcome exact,
                String key,
                String code,
                String message) {
            return new CreaturesPage(
                    state, entries, next, scanned, table, exact, key, code, message);
        }

        @Override
        public CompletableFuture<CreaturesPage> list(
                CreatureQuery query, Priority priority, Cancellation token) {
            CreatureQueries.validate(query);
            return run(
                    priority,
                    token,
                    () -> {
                        var schema =
                                tables.schema(
                                                TableTypes.Ref.forName(CreatureLayout.TABLE),
                                                priority,
                                                token)
                                        .join();
                        var entry = schema.table();
                        if (!TableQueries.isReadable(entry.state())) {
                            return page(
                                    CreaturesPageState.CREATURE_NOT_READABLE,
                                    List.of(),
                                    null,
                                    0,
                                    entry,
                                    null,
                                    null,
                                    null,
                                    null);
                        }
                        String binding = CreatureQueries.binding(build(), locale(), query);
                        ConceptQueries.Cursor cursor = null;
                        if (query.after() != null) {
                            cursor = CreatureQueries.decodeCursor(query.after(), binding);
                            if (cursor == null || query.exactId() != null) {
                                return page(
                                        CreaturesPageState.INVALID_CURSOR,
                                        List.of(),
                                        null,
                                        0,
                                        entry,
                                        null,
                                        null,
                                        "INVALID_CURSOR",
                                        "Cursor differs from request, page size or build");
                            }
                        }
                        try {
                            var layout =
                                    CreatureLayout.resolve(
                                            schema.columns() == null ? List.of() : schema.columns(),
                                            schema.schema());
                            if (query.exactId() != null) {
                                var found =
                                        tables.get(
                                                        TableTypes.Ref.forName(
                                                                CreatureLayout.TABLE),
                                                        query.exactId(),
                                                        priority,
                                                        token)
                                                .join();
                                return page(
                                        CreaturesPageState.OK,
                                        found.outcome() == TableTypes.RowOutcome.FOUND
                                                ? List.of(layout.summary(found.row().row()))
                                                : List.of(),
                                        null,
                                        1,
                                        entry,
                                        found.outcome(),
                                        found.protectedKey(),
                                        null,
                                        null);
                            }
                            return scan(layout, query, binding, cursor, entry, priority, token);
                        } catch (CreatureLayout.Failure e) {
                            return page(
                                    e.code.equals(CreatureLayout.UNSUPPORTED)
                                            ? CreaturesPageState.SCHEMA_UNSUPPORTED
                                            : CreaturesPageState.SOURCE_INVALID,
                                    List.of(),
                                    null,
                                    0,
                                    entry,
                                    null,
                                    null,
                                    e.code,
                                    e.getMessage());
                        }
                    });
        }

        private CreaturesPage scan(
                CreatureLayout layout,
                CreatureQuery query,
                String binding,
                ConceptQueries.Cursor cursor,
                TableTypes.Entry entry,
                Priority priority,
                Cancellation token) {
            List<CreatureSummary> entries = new ArrayList<>();
            long after = cursor == null ? -1 : cursor.a(), looked = 0;
            String identity = cursor == null ? null : cursor.i();
            boolean end = false;
            while (entries.size() < query.limit() && looked < options.maxScanRows()) {
                int budget = (int) Math.min(1000, options.maxScanRows() - looked);
                if (query.query().isEmpty()) {
                    budget = Math.min(budget, query.limit() - entries.size());
                }
                var page =
                        tables.page(
                                        new TableTypes.PageRequest(
                                                TableTypes.Ref.forName(CreatureLayout.TABLE),
                                                after,
                                                budget,
                                                List.of(),
                                                identity),
                                        priority,
                                        token)
                                .join();
                if (page.state() != TableTypes.PageState.OK) {
                    return page(
                            switch (page.state()) {
                                case STALE -> CreaturesPageState.STALE;
                                case NOT_READABLE -> CreaturesPageState.CREATURE_NOT_READABLE;
                                default -> CreaturesPageState.INVALID_CURSOR;
                            },
                            List.of(),
                            null,
                            looked,
                            page.table(),
                            null,
                            null,
                            page.code(),
                            page.message());
                }
                identity = page.identity();
                int consumed = 0;
                for (var row : page.rows()) {
                    token.check();
                    consumed++;
                    looked++;
                    after = row.ordinal();
                    if (CreatureQueries.matches(layout.nameOf(row.row()), query.query())) {
                        entries.add(layout.summary(row.row()));
                    }
                    if (entries.size() == query.limit()) {
                        break;
                    }
                }
                if (page.nextAfter() == null && consumed == page.rows().size()
                        || page.rows().isEmpty()) {
                    end = true;
                    break;
                }
            }
            return page(
                    CreaturesPageState.OK,
                    entries,
                    end ? null : CreatureQueries.encodeCursor(binding, after, identity),
                    looked,
                    entry,
                    null,
                    null,
                    null,
                    null);
        }

        @Override
        public CompletableFuture<CreatureResult> get(
                long id, Priority priority, Cancellation token) {
            return detail(id, priority, token, true);
        }

        @Override
        public CompletableFuture<CreatureResult> getData(
                long id, Priority priority, Cancellation token) {
            return detail(id, priority, token, false);
        }

        private CompletableFuture<CreatureResult> detail(
                long id, Priority priority, Cancellation token, boolean media) {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Creature ID is uint32");
            }
            return run(
                    priority,
                    token,
                    () -> {
                        var schema =
                                tables.schema(
                                                TableTypes.Ref.forName(CreatureLayout.TABLE),
                                                priority,
                                                token)
                                        .join();
                        var entry = schema.table();
                        if (!TableQueries.isReadable(entry.state())) {
                            return new CreatureResult(
                                    CreatureResultState.CREATURE_NOT_READABLE,
                                    null,
                                    entry,
                                    null,
                                    null,
                                    null);
                        }
                        CreatureSummary summary;
                        TableTypes.Row row;
                        try {
                            var layout =
                                    CreatureLayout.resolve(
                                            schema.columns() == null ? List.of() : schema.columns(),
                                            schema.schema());
                            var found =
                                    tables.get(
                                                    TableTypes.Ref.forName(CreatureLayout.TABLE),
                                                    id,
                                                    priority,
                                                    token)
                                            .join();
                            if (found.outcome() != TableTypes.RowOutcome.FOUND) {
                                return new CreatureResult(
                                        switch (found.outcome()) {
                                            case ABSENT -> CreatureResultState.ABSENT;
                                            case PROTECTED -> CreatureResultState.PROTECTED;
                                            default -> CreatureResultState.UNKNOWN;
                                        },
                                        null,
                                        entry,
                                        found.protectedKey(),
                                        null,
                                        null);
                            }
                            row = found.row();
                            summary = layout.summary(row.row());
                        } catch (CreatureLayout.Failure e) {
                            return new CreatureResult(
                                    e.code.equals(CreatureLayout.UNSUPPORTED)
                                            ? CreatureResultState.SCHEMA_UNSUPPORTED
                                            : CreatureResultState.SOURCE_INVALID,
                                    null,
                                    entry,
                                    null,
                                    e.code,
                                    e.getMessage());
                        }
                        Call call = new Call(priority, token);
                        List<CreatureDisplayChain> displays = new ArrayList<>();
                        for (var slot : summary.slots()) {
                            displays.add(chain(call, slot, media));
                        }
                        return new CreatureResult(
                                CreatureResultState.FOUND,
                                new CreatureDetail(
                                        summary,
                                        row,
                                        CreatureLayout.classificationLabel(
                                                summary.facts().getFirst().value()),
                                        displays),
                                entry,
                                null,
                                null,
                                null);
                    });
        }

        @Override
        public CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
                Priority priority, Cancellation token) {
            return run(
                    priority,
                    token,
                    () -> {
                        var schema =
                                tables.schema(
                                                TableTypes.Ref.forName(CreatureLayout.TABLE),
                                                priority,
                                                token)
                                        .join();
                        boolean ready = TableQueries.isReadable(schema.table().state());
                        String code = ready ? null : "CREATURE_TABLE_NOT_READABLE";
                        if (ready) {
                            try {
                                CreatureLayout.resolve(
                                        schema.columns() == null ? List.of() : schema.columns(),
                                        schema.schema());
                            } catch (CreatureLayout.Failure failure) {
                                ready = false;
                                code = failure.code;
                            }
                        }
                        return new com.vibeminer.concepts.core.ConceptWarmup.Result(
                                List.of(
                                        new com.vibeminer.concepts.core.ConceptWarmup.Entry(
                                                CreatureLayout.TABLE,
                                                null,
                                                ready,
                                                code,
                                                null,
                                                schema.table().contentKey())));
                    });
        }

        private record Target(TableTypes.Entry entry, List<Db2Types.Column> columns, Integer edge) {
            boolean readable() {
                return TableQueries.isReadable(entry.state());
            }
        }

        private final class Call {
            final Priority priority;
            final Cancellation token;
            final Map<CreatureDisplayRules.Role, Target> targets = new HashMap<>();
            final Map<String, TableTypes.RowResult> rows = new HashMap<>();
            final Map<Long, BuildModelTypes.ModelRead> models = new HashMap<>();
            final Map<Long, BuildModelTypes.SkinRead<M2Types.Skin>> skins = new HashMap<>();
            final Map<Long, BlpImageTypes.Inspection> textures = new HashMap<>();

            Call(Priority priority, Cancellation token) {
                this.priority = priority;
                this.token = token;
            }

            TableTypes.RowResult row(CreatureDisplayRules.Role role, long id) {
                return rows.computeIfAbsent(
                        role + ":" + id,
                        ignored ->
                                tables.get(
                                                TableTypes.Ref.forName(
                                                        CreatureDisplayRules.table(role)),
                                                id,
                                                priority,
                                                token)
                                        .join());
            }
        }

        private Target target(Call call, CreatureDisplayRules.Role role) {
            return call.targets.computeIfAbsent(
                    role,
                    ignored -> {
                        var schema =
                                tables.schema(
                                                TableTypes.Ref.forName(
                                                        CreatureDisplayRules.table(role)),
                                                call.priority,
                                                call.token)
                                        .join();
                        List<Db2Types.Column> columns =
                                schema.columns() == null ? List.of() : schema.columns();
                        return new Target(
                                schema.table(),
                                columns,
                                TableQueries.isReadable(schema.table().state())
                                        ? CreatureDisplayRules.edgeColumn(
                                                role,
                                                schema.table().counts() == null
                                                        ? null
                                                        : schema.table().counts().layoutHash(),
                                                columns,
                                                schema.schema())
                                        : null);
                    });
        }

        private CreatureDisplayChain chain(Call call, CreatureDisplaySlot slot, boolean media) {
            Chain b = new Chain(slot);
            if (slot.state() == CreatureSlotState.NO_DECLARED_DISPLAY) {
                return b.result(CreatureDisplayChainState.NO_DECLARED_DISPLAY);
            }
            if (slot.state() == CreatureSlotState.INVALID_DISPLAY_REFERENCE) {
                return b.result(CreatureDisplayChainState.INVALID_DISPLAY_REFERENCE);
            }
            var display = target(call, CreatureDisplayRules.Role.DISPLAY);
            b.display = display.entry();
            if (!display.readable()) {
                return b.result(CreatureDisplayChainState.DISPLAY_NOT_READABLE);
            }
            if (display.edge() == null) {
                b.code = CreatureDisplayRules.UNSUPPORTED;
                b.message = "Display layout does not qualify ModelID as verified u16 scalar";
                return b.result(CreatureDisplayChainState.DISPLAY_UNSUPPORTED);
            }
            var found = call.row(CreatureDisplayRules.Role.DISPLAY, slot.displayId());
            b.display = found.table();
            if (found.outcome() != TableTypes.RowOutcome.FOUND) {
                b.key = found.protectedKey();
                return b.result(
                        switch (found.outcome()) {
                            case ABSENT -> CreatureDisplayChainState.DISPLAY_ABSENT;
                            case PROTECTED -> CreatureDisplayChainState.DISPLAY_PROTECTED;
                            default -> CreatureDisplayChainState.DISPLAY_UNKNOWN;
                        });
            }
            b.displayRow = found.row();
            b.displayFacts =
                    CreatureDisplayRules.facts(
                            CreatureDisplayRules.Role.DISPLAY,
                            display.columns(),
                            b.displayRow.row());
            b.textures =
                    CreatureDisplayRules.textureEvidence(display.columns(), b.displayRow.row());
            Long modelId =
                    CreatureDisplayRules.edge(
                            b.displayRow.row(),
                            display.edge(),
                            CreatureDisplayRules.Role.DISPLAY,
                            slot.displayId());
            if (modelId == null) {
                b.code = CreatureDisplayRules.INTEGRITY;
                b.message = "Display ModelID is not canonical u16";
                return b.result(CreatureDisplayChainState.DISPLAY_INVALID);
            }
            b.modelId = modelId;
            if (modelId == 0) {
                return b.result(CreatureDisplayChainState.NO_DECLARED_MODEL);
            }
            var model = target(call, CreatureDisplayRules.Role.MODEL);
            b.modelData = model.entry();
            if (!model.readable()) {
                return b.result(CreatureDisplayChainState.MODEL_NOT_READABLE);
            }
            if (model.edge() == null) {
                b.code = CreatureDisplayRules.UNSUPPORTED;
                b.message = "Model layout does not qualify FileDataID as verified u32 scalar";
                return b.result(CreatureDisplayChainState.MODEL_UNSUPPORTED);
            }
            var modelRow = call.row(CreatureDisplayRules.Role.MODEL, modelId);
            b.modelData = modelRow.table();
            if (modelRow.outcome() != TableTypes.RowOutcome.FOUND) {
                b.key = modelRow.protectedKey();
                return b.result(
                        switch (modelRow.outcome()) {
                            case ABSENT -> CreatureDisplayChainState.MODEL_ABSENT;
                            case PROTECTED -> CreatureDisplayChainState.MODEL_PROTECTED;
                            default -> CreatureDisplayChainState.MODEL_UNKNOWN;
                        });
            }
            b.modelRow = modelRow.row();
            b.modelFacts =
                    CreatureDisplayRules.facts(
                            CreatureDisplayRules.Role.MODEL, model.columns(), b.modelRow.row());
            Long file =
                    CreatureDisplayRules.edge(
                            b.modelRow.row(),
                            model.edge(),
                            CreatureDisplayRules.Role.MODEL,
                            modelId);
            if (file == null) {
                b.code = CreatureDisplayRules.INTEGRITY;
                b.message = "Model FileDataID is not canonical u32";
                return b.result(CreatureDisplayChainState.MODEL_INVALID);
            }
            b.file = file;
            if (file == 0) {
                return b.result(CreatureDisplayChainState.NO_DECLARED_MODEL_FILE);
            }
            if (media) {
                b.model = model(call, file, b.textures);
            }
            return b.result(CreatureDisplayChainState.METADATA_RESOLVED);
        }

        private final class Chain {
            final CreatureDisplaySlot slot;
            TableTypes.Entry display, modelData;
            TableTypes.Row displayRow, modelRow;
            Long modelId, file;
            String key, code, message;
            CreatureRecordFacts displayFacts, modelFacts;
            CreatureTextureEvidence textures;
            CreatureModel model;

            Chain(CreatureDisplaySlot slot) {
                this.slot = slot;
            }

            CreatureDisplayChain result(CreatureDisplayChainState state) {
                return new CreatureDisplayChain(
                        slot,
                        state,
                        display,
                        displayRow,
                        modelId,
                        modelData,
                        modelRow,
                        file,
                        key,
                        code,
                        message,
                        displayFacts,
                        modelFacts,
                        textures,
                        model);
            }
        }

        private FileTypes.FilesOpenResult ensureFiles(Cancellation token) {
            for (; ; ) {
                token.check();
                try {
                    if (filesGate.tryLock(25, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
            }
            try {
                if (buildFiles != null) {
                    return null;
                }
                var opened = files.forBuild(build(), locale(), token).join();
                if (opened.state() != FileTypes.FilesOpenState.OPEN || opened.files() == null) {
                    return opened;
                }
                buildFiles = opened.files();
                models = new BuildModels(buildFiles);
                return null;
            } finally {
                filesGate.unlock();
            }
        }

        private CreatureModel model(Call call, long file, CreatureTextureEvidence evidence) {
            var failed = ensureFiles(call.token);
            if (failed != null) {
                return new CreatureModel(
                        CreatureModelState.FILES_NOT_OPEN,
                        file,
                        null,
                        CreatureDisplayRules.appearance(
                                CreatureAppearanceState.MODEL_NOT_READ,
                                null,
                                null,
                                null,
                                null,
                                null),
                        List.of(),
                        null,
                        failed.code(),
                        failed.message(),
                        failed.observedAt(),
                        null,
                        null,
                        null);
            }
            var read =
                    call.models.computeIfAbsent(
                            file, ignored -> models.readModel(FileRef.id(file), call.token).join());
            if (read.state() != BuildModelTypes.ReadState.READ || read.model() == null) {
                return new CreatureModel(
                        CreatureModelState.READ_FAILED,
                        file,
                        read.state(),
                        CreatureDisplayRules.appearance(
                                CreatureAppearanceState.MODEL_NOT_READ,
                                null,
                                null,
                                null,
                                null,
                                null),
                        List.of(),
                        null,
                        read.code(),
                        read.message(),
                        read.observedAt(),
                        read.keyNames(),
                        null,
                        null);
            }
            var m2 = read.model();
            var noSkin = CreatureDisplayRules.geometryRefusal(m2);
            if (noSkin != null) {
                return new CreatureModel(
                        CreatureModelState.READ,
                        file,
                        read.state(),
                        noSkin,
                        CreatureDisplayRules.textures(read, evidence, null),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        read.references());
            }
            long skin = m2.skinFileDataIds().getFirst();
            var skinRead =
                    call.skins.computeIfAbsent(
                            file, ignored -> models.readSkin(read, 0, call.token).join());
            if (skinRead.state() != BuildModelTypes.ReadState.READ || skinRead.skin() == null) {
                return new CreatureModel(
                        CreatureModelState.READ,
                        file,
                        read.state(),
                        CreatureDisplayRules.appearance(
                                CreatureAppearanceState.SKIN_NOT_READ,
                                skinRead.code(),
                                skinRead.message(),
                                null,
                                null,
                                null),
                        CreatureDisplayRules.textures(read, evidence, null),
                        skin,
                        null,
                        null,
                        null,
                        null,
                        skinRead.state(),
                        skinRead.keyNames(),
                        read.references());
            }
            var batches = CreatureDisplayRules.drawnTextures(m2, skinRead.skin());
            List<CreatureTexture> textures =
                    CreatureDisplayRules.textures(read, evidence, batches.drawn());
            var refusal = CreatureDisplayRules.refusal(evidence, textures, batches.refusal());
            if (refusal != null) {
                return new CreatureModel(
                        CreatureModelState.READ,
                        file,
                        read.state(),
                        CreatureDisplayRules.counted(refusal, batches.blended()),
                        textures,
                        skin,
                        null,
                        null,
                        null,
                        null,
                        skinRead.state(),
                        null,
                        read.references());
            }
            List<CreatureTexture> inspected = new ArrayList<>();
            CreatureAppearance stop = null;
            for (var texture : textures) {
                if (!texture.drawn() || texture.state() != CreatureTextureState.RESOLVED) {
                    inspected.add(texture);
                    continue;
                }
                var header =
                        call.textures.computeIfAbsent(
                                texture.fileDataId(),
                                id ->
                                        images.inspect(buildFiles, FileRef.id(id), call.token)
                                                .join());
                inspected.add(
                        new CreatureTexture(
                                texture.ordinal(),
                                texture.type(),
                                texture.flags(),
                                texture.filename(),
                                texture.source(),
                                texture.variation(),
                                texture.fileDataId(),
                                texture.state(),
                                texture.drawn(),
                                texture.reference(),
                                header.state(),
                                header.fileState(),
                                header.holeKeys(),
                                header.code()));
                if (header.state() != BlpImageTypes.State.READY && stop == null) {
                    stop =
                            CreatureDisplayRules.appearance(
                                    CreatureDisplayRules.textureFailure(header.state()),
                                    header.code(),
                                    header.message(),
                                    texture.ordinal(),
                                    null,
                                    null);
                }
            }
            if (stop == null) {
                stop =
                        CreatureDisplayRules.appearance(
                                CreatureAppearanceState.READY, null, null, null, null, null);
            }
            return new CreatureModel(
                    CreatureModelState.READ,
                    file,
                    read.state(),
                    CreatureDisplayRules.counted(stop, batches.blended()),
                    inspected,
                    skin,
                    null,
                    null,
                    null,
                    null,
                    skinRead.state(),
                    null,
                    read.references());
        }
    }
}
