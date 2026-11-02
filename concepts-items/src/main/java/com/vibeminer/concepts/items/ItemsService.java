package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileLocales;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.Files;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.Tables;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Source-backed Item left join ItemSparse, with detail-only facts/labels/files/appearance
 * inspection.
 */
public final class ItemsService implements Items {
    public static final String MODEL_CAP_CODE = "ITEM_MODEL_CAP";
    private final Tables tables;
    private final Files files;
    private final BlpImages images;
    private final ItemsOptions options;

    public ItemsService(Tables tables, Files files, BlpImages images) {
        this(tables, files, images, new ItemsOptions());
    }

    public ItemsService(Tables tables, Files files, BlpImages images, ItemsOptions options) {
        this.tables = java.util.Objects.requireNonNull(tables);
        this.files = java.util.Objects.requireNonNull(files);
        this.images = java.util.Objects.requireNonNull(images);
        this.options = java.util.Objects.requireNonNull(options);
    }

    @Override
    public CompletableFuture<ItemsOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        java.util.Objects.requireNonNull(build);
        FileLocales.bitOf(locale);
        token.check();
        var result = new CompletableFuture<ItemsOpenResult>();
        var lower = java.util.Objects.requireNonNull(tables.forBuild(build, locale, token));
        lower.whenComplete(
                (open, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    BuildItems handle = null;
                    try {
                        token.check();
                        if (open.state() == FileTypes.FilesOpenState.OPEN
                                && open.tables() != null) {
                            handle = new Handle(open.tables());
                        } else if (open.tables() != null) {
                            open.tables().closeAsync().join();
                        }
                        if (!result.complete(
                                        new ItemsOpenResult(
                                                build,
                                                locale,
                                                open.state(),
                                                handle,
                                                open.code(),
                                                open.message(),
                                                open.observedAt()))
                                && handle != null) {
                            handle.closeAsync().join();
                        }
                    } catch (Throwable failure) {
                        try {
                            if (handle != null) {
                                handle.closeAsync().join();
                            } else if (open != null && open.tables() != null) {
                                open.tables().closeAsync().join();
                            }
                        } catch (Throwable closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    private final class Handle implements BuildItems {
        private final TableTypes.Ref item = TableTypes.Ref.forName(ItemLayout.ITEM),
                sparse = TableTypes.Ref.forName(ItemLayout.SPARSE);
        private final BuildTables tables;
        private final RelationIndexes relations;
        private final Semaphore filesGate = new Semaphore(1);
        private BuildFiles buildFiles;
        private BuildModels models;
        private final Object gate = new Object();
        private boolean closed;
        private boolean disposing;
        private int active;
        private final CompletableFuture<Void> drained = new CompletableFuture<>();

        Handle(BuildTables tables) {
            this.tables = tables;
            relations = new RelationIndexes(tables, options.maxRelationIndexEntries());
        }

        @Override
        public BuildRef build() {
            return tables.build();
        }

        @Override
        public String locale() {
            return tables.locale();
        }

        private <T> CompletableFuture<T> call(
                TableTypes.Priority priority, Cancellation token, Supplier<T> action) {
            java.util.Objects.requireNonNull(priority);
            synchronized (gate) {
                if (closed) {
                    throw new IllegalStateException("Items handle closed");
                }
                token.check();
                if (active >= options.maxConcurrentOperations()) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("ITEMS_OPERATION_LIMIT"));
                }
                active++;
            }
            try {
                return CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        T result = action.get();
                                        token.check();
                                        return result;
                                    } finally {
                                        finish();
                                    }
                                },
                                command -> Thread.ofVirtual().start(command))
                        .copy();
            } catch (Throwable failure) {
                finish();
                throw failure;
            }
        }

        private void finish() {
            boolean dispose;
            synchronized (gate) {
                dispose = --active == 0 && closed && !disposing;
                if (dispose) {
                    disposing = true;
                }
            }
            if (dispose) {
                startDisposal();
            }
        }

        private void startDisposal() {
            try {
                Thread.startVirtualThread(this::dispose);
            } catch (Throwable failure) {
                drained.completeExceptionally(failure);
            }
        }

        private void dispose() {
            Throwable failure = null;
            try {
                if (buildFiles != null) {
                    buildFiles.close();
                }
            } catch (Throwable error) {
                failure = error;
            }
            try {
                tables.closeAsync().join();
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (failure == null) {
                drained.complete(null);
            } else {
                drained.completeExceptionally(failure);
            }
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            boolean dispose;
            synchronized (gate) {
                closed = true;
                dispose = active == 0 && !disposing;
                if (dispose) {
                    disposing = true;
                }
            }
            if (dispose) {
                startDisposal();
            }
            return drained.copy();
        }

        private record Schemas(
                TableTypes.Entry item,
                TableTypes.Entry sparse,
                List<Db2Types.Column> itemColumns,
                List<Db2Types.Column> sparseColumns) {}

        private Schemas schemas(TableTypes.Priority priority, Cancellation token) {
            var left = tables.schema(item, priority, token).join();
            var right = tables.schema(sparse, priority, token).join();
            return new Schemas(
                    left.table(),
                    right.table(),
                    TableQueries.isReadable(left.table().state())
                            ? (left.columns() == null ? List.of() : left.columns())
                            : null,
                    TableQueries.isReadable(right.table().state())
                            ? (right.columns() == null ? List.of() : right.columns())
                            : null);
        }

        @Override
        public CompletableFuture<ItemsPage> list(
                ItemQuery query, TableTypes.Priority priority, Cancellation token) {
            ItemQueries.validate(query);
            return call(
                    priority,
                    token,
                    () -> {
                        var s = schemas(priority, token);
                        if (s.itemColumns() == null) {
                            return page(
                                    ItemsPageState.ITEM_NOT_READABLE,
                                    List.of(),
                                    null,
                                    0,
                                    s,
                                    null,
                                    null);
                        }
                        boolean search = !query.query().isEmpty();
                        if (search && s.sparseColumns() == null) {
                            return page(
                                    ItemsPageState.SPARSE_NOT_READABLE,
                                    List.of(),
                                    null,
                                    0,
                                    s,
                                    null,
                                    null);
                        }
                        String binding = ItemQueries.binding(build(), locale(), query);
                        ItemCursor cursor = null;
                        if (query.after() != null) {
                            cursor = ItemQueries.decodeCursor(query.after(), binding);
                            if (cursor == null || query.exactId() != null) {
                                return page(
                                        ItemsPageState.INVALID_CURSOR,
                                        List.of(),
                                        null,
                                        0,
                                        s,
                                        "INVALID_CURSOR",
                                        "Cursor differs from request/build/limit");
                            }
                            if (!java.util.Objects.equals(
                                    cursor.o(), (search ? s.item() : s.sparse()).contentKey())) {
                                return page(
                                        ItemsPageState.STALE, List.of(), null, 0, s, null, null);
                            }
                        }
                        try {
                            var layout = ItemLayout.resolve(s.itemColumns(), s.sparseColumns());
                            if (query.exactId() != null) {
                                return exact(layout, query.exactId(), s, priority, token);
                            }
                            return scan(layout, query, binding, cursor, search, s, priority, token);
                        } catch (ItemsSourceException error) {
                            return page(
                                    pageState(error.code()),
                                    List.of(),
                                    null,
                                    0,
                                    s,
                                    error.code(),
                                    error.getMessage());
                        }
                    });
        }

        private ItemsPage page(
                ItemsPageState state,
                List<ItemSummary> entries,
                String next,
                long scanned,
                Schemas s,
                String code,
                String message) {
            return new ItemsPage(
                    state, entries, next, scanned, s.item(), s.sparse(), code, message);
        }

        private ItemsPage exact(
                ItemLayout layout,
                long id,
                Schemas s,
                TableTypes.Priority priority,
                Cancellation token) {
            var found = tables.get(item, id, priority, token).join();
            if (found.outcome() == TableTypes.RowOutcome.ABSENT) {
                return page(ItemsPageState.OK, List.of(), null, 1, s, null, null);
            }
            if (found.outcome() != TableTypes.RowOutcome.FOUND) {
                var right =
                        layout.sparseColumns() == null
                                ? null
                                : tables.get(sparse, id, priority, token).join();
                var summary =
                        layout.unread(
                                id,
                                found.outcome(),
                                found.protectedKey(),
                                right == null || right.row() == null ? null : right.row().row(),
                                right == null ? TableTypes.RowOutcome.UNKNOWN : right.outcome(),
                                right == null ? null : right.protectedKey());
                return page(ItemsPageState.OK, List.of(summary), null, 1, s, null, null);
            }
            return page(
                    ItemsPageState.OK,
                    List.of(summary(layout, found.row().row(), priority, token).summary()),
                    null,
                    1,
                    s,
                    null,
                    null);
        }

        private record Summary(ItemSummary summary, TableTypes.Row sparse) {}

        private Summary summary(
                ItemLayout layout,
                Db2Types.Row row,
                TableTypes.Priority priority,
                Cancellation token) {
            if (layout.sparseColumns() == null) {
                return new Summary(
                        layout.summary(row, null, TableTypes.RowOutcome.UNKNOWN, null), null);
            }
            var right = tables.get(sparse, row.id(), priority, token).join();
            return new Summary(
                    layout.summary(
                            row,
                            right.row() == null ? null : right.row().row(),
                            right.outcome(),
                            right.protectedKey()),
                    right.row());
        }

        private ItemsPage scan(
                ItemLayout layout,
                ItemQuery query,
                String binding,
                ItemCursor cursor,
                boolean search,
                Schemas s,
                TableTypes.Priority priority,
                Cancellation token) {
            var scanned = search ? sparse : item;
            var entries = new ArrayList<ItemSummary>();
            long after = cursor == null ? -1 : cursor.a(), looked = 0;
            String identity = cursor == null ? null : cursor.i();
            boolean end = false;
            while (entries.size() < query.limit() && looked < options.maxScanRows()) {
                token.check();
                int budget = (int) Math.min(1000, options.maxScanRows() - looked);
                if (!search) {
                    budget = Math.min(budget, query.limit() - entries.size());
                }
                var page =
                        tables.page(
                                        new TableTypes.PageRequest(
                                                scanned, after, budget, List.of(), identity),
                                        priority,
                                        token)
                                .join();
                if (page.state() != TableTypes.PageState.OK) {
                    if (page.state() == TableTypes.PageState.STALE) {
                        return page(ItemsPageState.STALE, List.of(), null, looked, s, null, null);
                    }
                    if (page.state() == TableTypes.PageState.NOT_READABLE) {
                        var updated =
                                new Schemas(
                                        search ? s.item() : page.table(),
                                        search ? page.table() : s.sparse(),
                                        s.itemColumns(),
                                        s.sparseColumns());
                        return page(
                                search
                                        ? ItemsPageState.SPARSE_NOT_READABLE
                                        : ItemsPageState.ITEM_NOT_READABLE,
                                List.of(),
                                null,
                                looked,
                                updated,
                                null,
                                null);
                    }
                    return page(
                            ItemsPageState.INVALID_CURSOR,
                            List.of(),
                            null,
                            looked,
                            s,
                            page.code() == null ? "INVALID_CURSOR" : page.code(),
                            page.message());
                }
                identity = page.identity();
                int consumed = 0;
                for (var row : page.rows()) {
                    token.check();
                    consumed++;
                    looked++;
                    after = row.ordinal();
                    if (!search) {
                        entries.add(summary(layout, row.row(), priority, token).summary());
                    } else if (ItemQueries.matches(layout.nameOf(row.row()), query.query())) {
                        var found = tables.get(item, row.row().id(), priority, token).join();
                        if (found.outcome() == TableTypes.RowOutcome.FOUND) {
                            entries.add(
                                    layout.summary(
                                            found.row().row(),
                                            row.row(),
                                            TableTypes.RowOutcome.FOUND,
                                            null));
                        } else if (found.outcome() != TableTypes.RowOutcome.ABSENT) {
                            entries.add(
                                    layout.unread(
                                            row.row().id(),
                                            found.outcome(),
                                            found.protectedKey(),
                                            row.row(),
                                            TableTypes.RowOutcome.FOUND,
                                            null));
                        }
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
            String next =
                    end
                            ? null
                            : ItemQueries.encodeCursor(
                                    new ItemCursor(
                                            binding,
                                            after,
                                            identity,
                                            (search ? s.item() : s.sparse()).contentKey()));
            return page(ItemsPageState.OK, entries, next, looked, s, null, null);
        }

        @Override
        public CompletableFuture<ItemResult> get(
                long id, TableTypes.Priority priority, Cancellation token) {
            return detail(id, priority, token, true);
        }

        @Override
        public CompletableFuture<ItemResult> getData(
                long id, TableTypes.Priority priority, Cancellation token) {
            return detail(id, priority, token, false);
        }

        private CompletableFuture<ItemResult> detail(
                long id, TableTypes.Priority priority, Cancellation token, boolean media) {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Unsigned32 item id required");
            }
            return call(
                    priority,
                    token,
                    () -> {
                        var s = schemas(priority, token);
                        if (s.itemColumns() == null) {
                            return result(
                                    ItemResultState.ITEM_NOT_READABLE, null, s, null, null, null);
                        }
                        ItemSummary summary;
                        TableTypes.Row itemRow, sparseRow;
                        ItemFactSet facts;
                        try {
                            var layout = ItemLayout.resolve(s.itemColumns(), s.sparseColumns());
                            var found = tables.get(item, id, priority, token).join();
                            if (found.outcome() != TableTypes.RowOutcome.FOUND) {
                                return result(
                                        switch (found.outcome()) {
                                            case ABSENT -> ItemResultState.ABSENT;
                                            case PROTECTED -> ItemResultState.PROTECTED;
                                            default -> ItemResultState.UNKNOWN;
                                        },
                                        null,
                                        s,
                                        found.protectedKey(),
                                        null,
                                        null);
                            }
                            itemRow = found.row();
                            var joined = summary(layout, itemRow.row(), priority, token);
                            summary = joined.summary();
                            sparseRow = joined.sparse();
                            facts =
                                    ItemFacts.project(
                                            layout.itemColumns(),
                                            layout.sparseColumns(),
                                            itemRow.row(),
                                            sparseRow == null ? null : sparseRow.row(),
                                            ItemFacts.sparseState(summary.name().state()));
                        } catch (ItemsSourceException error) {
                            return result(
                                    resultState(error.code()),
                                    null,
                                    s,
                                    null,
                                    error.code(),
                                    error.getMessage());
                        }
                        var icon =
                                media
                                        ? icon(summary.iconFileDataId(), token)
                                        : deferredIcon(summary.iconFileDataId());
                        var labels =
                                ItemClassifications.resolve(
                                        tables,
                                        summary,
                                        options.maxLabelScanRows(),
                                        priority,
                                        token);
                        var appearances =
                                new ItemAppearanceReader(
                                                tables, relations, options, priority, token)
                                        .read(id);
                        appearances =
                                new ItemAppearanceSet(
                                        appearances.state(),
                                        appearances.rows(),
                                        appearances.defaultState(),
                                        appearances.defaultIndex(),
                                        appearances.matches(),
                                        appearances.table(),
                                        media
                                                ? models(appearances, token)
                                                : deferredModels(appearances),
                                        appearances.code(),
                                        appearances.message());
                        var icons = new ArrayList<ItemIconSourced>();
                        icons.add(new ItemIconSourced(ItemLayout.ITEM, "IconFileDataID", id, icon));
                        if (appearances.defaultIndex() != null) {
                            var appearance =
                                    appearances.rows().get(appearances.defaultIndex()).appearance();
                            if (appearance.state() == ItemRefState.FOUND
                                    && appearance.id() != null) {
                                icons.add(
                                        new ItemIconSourced(
                                                ItemAppearanceReader.APPEARANCE,
                                                ItemAppearanceReader.ICON_COLUMN,
                                                appearance.id(),
                                                (media
                                                        ? icon(
                                                                appearance.defaultIconFileDataId(),
                                                                token)
                                                        : deferredIcon(
                                                                appearance
                                                                        .defaultIconFileDataId()))));
                            }
                        }
                        return result(
                                ItemResultState.FOUND,
                                new ItemDetail(
                                        summary,
                                        itemRow,
                                        sparseRow,
                                        icon,
                                        facts,
                                        labels,
                                        icons,
                                        appearances),
                                s,
                                null,
                                null,
                                null);
                    });
        }

        private ItemIcon deferredIcon(Long id) {
            return new ItemIcon(
                    id == null
                            ? ItemIconState.FIELD_UNAVAILABLE
                            : id == 0 ? ItemIconState.NO_ICON : ItemIconState.NOT_REQUESTED,
                    id,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null);
        }

        private ItemModelRefs deferredModels(ItemAppearanceSet set) {
            var ids = new java.util.LinkedHashSet<Long>();
            for (var row : set.rows()) {
                if (row.appearance().display() != null) {
                    for (var slot : row.appearance().display().slots()) {
                        for (var file : slot.model().files()) {
                            ids.add(file.fileDataId());
                        }
                    }
                }
            }
            return new ItemModelRefs(
                    ItemModelsState.COMPLETE,
                    ids.stream()
                            .map(
                                    id ->
                                            new ItemModelRef(
                                                    id,
                                                    ItemModelRefState.NOT_READ,
                                                    null,
                                                    null,
                                                    0,
                                                    null,
                                                    null,
                                                    null,
                                                    null))
                            .toList(),
                    null,
                    null);
        }

        @Override
        public CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
                TableTypes.Priority priority, Cancellation token) {
            return call(
                    priority,
                    token,
                    () -> {
                        var entries =
                                new ArrayList<com.vibeminer.concepts.core.ConceptWarmup.Entry>();
                        for (var recipe :
                                List.of(
                                        new String[] {"ItemModifiedAppearance", "ItemID"},
                                        new String[] {
                                            "ItemDisplayInfoMaterialRes", "ItemDisplayInfoID"
                                        },
                                        new String[] {"ModelFileData", "ModelResourcesID"},
                                        new String[] {"TextureFileData", "MaterialResourcesID"})) {
                            token.check();
                            var result =
                                    relations.prepare(recipe[0], recipe[1], priority, token).join();
                            entries.add(
                                    com.vibeminer.concepts.core.ConceptWarmup.relation(
                                            recipe[0], recipe[1], result));
                        }
                        return new com.vibeminer.concepts.core.ConceptWarmup.Result(entries);
                    });
        }

        private ItemResult result(
                ItemResultState state,
                ItemDetail detail,
                Schemas s,
                String key,
                String code,
                String message) {
            return new ItemResult(state, detail, s.item(), s.sparse(), key, code, message);
        }

        private record OpenFiles(
                BuildFiles files, BuildModels models, FileTypes.FilesOpenResult failed) {}

        private OpenFiles files(Cancellation token) {
            boolean acquired = false;
            try {
                while (!(acquired =
                        filesGate.tryAcquire(20, java.util.concurrent.TimeUnit.MILLISECONDS))) {
                    token.check();
                }
                token.check();
                if (buildFiles != null) {
                    return new OpenFiles(buildFiles, models, null);
                }
                var opened = ItemsService.this.files.forBuild(build(), locale(), token).join();
                if (opened.state() != FileTypes.FilesOpenState.OPEN || opened.files() == null) {
                    if (opened.files() != null) {
                        opened.files().close();
                    }
                    return new OpenFiles(null, null, opened);
                }
                buildFiles = opened.files();
                models = new BuildModels(buildFiles);
                return new OpenFiles(buildFiles, models, null);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            } finally {
                if (acquired) {
                    filesGate.release();
                }
            }
        }

        private ItemIcon icon(Long id, Cancellation token) {
            if (id == null) {
                return new ItemIcon(
                        ItemIconState.FIELD_UNAVAILABLE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            if (id == 0) {
                return new ItemIcon(
                        ItemIconState.NO_ICON,
                        0L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            var opened = files(token);
            if (opened.files() == null) {
                return new ItemIcon(
                        ItemIconState.FILES_NOT_OPEN,
                        id,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        opened.failed().code(),
                        opened.failed().message(),
                        opened.failed().observedAt(),
                        null);
            }
            var inspected = images.inspect(opened.files(), FileTypes.FileRef.id(id), token).join();
            var stat = opened.files().stat(FileTypes.FileRef.id(id), token).join();
            return new ItemIcon(
                    ItemIconState.valueOf(inspected.state().name()),
                    id,
                    inspected.state(),
                    inspected.header(),
                    inspected.encoding(),
                    inspected.mips() == null ? null : inspected.mips().size(),
                    inspected.fileState(),
                    inspected.holeKeys(),
                    inspected.code(),
                    inspected.message(),
                    inspected.observedAt(),
                    stat.file() == null ? null : stat.file().contentKey());
        }

        private ItemModelRefs models(ItemAppearanceSet set, Cancellation token) {
            var ids = new java.util.LinkedHashSet<Long>();
            for (var row : set.rows()) {
                if (row.appearance().display() != null) {
                    for (var slot : row.appearance().display().slots()) {
                        for (var file : slot.model().files()) {
                            ids.add(file.fileDataId());
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                return ItemModelRefs.NONE;
            }
            var opened = files(token);
            var refs = new ArrayList<ItemModelRef>();
            int at = 0;
            for (long id : ids) {
                token.check();
                if (at++ >= options.maxModels()) {
                    refs.add(
                            new ItemModelRef(
                                    id,
                                    ItemModelRefState.NOT_READ,
                                    null,
                                    null,
                                    0,
                                    null,
                                    null,
                                    null,
                                    null));
                    continue;
                }
                if (opened.models() == null) {
                    refs.add(
                            new ItemModelRef(
                                    id,
                                    ItemModelRefState.FILES_NOT_OPEN,
                                    null,
                                    null,
                                    0,
                                    opened.failed().code(),
                                    opened.failed().message(),
                                    opened.failed().observedAt(),
                                    null));
                    continue;
                }
                var read = opened.models().readModel(FileTypes.FileRef.id(id), token).join();
                var textures =
                        read.references().stream()
                                .filter(r -> r.kind() == BuildModelTypes.ReferenceKind.TEXTURE)
                                .map(
                                        r ->
                                                new ItemModelTexture(
                                                        r.ordinal(),
                                                        r.textureType(),
                                                        r.fileDataId(),
                                                        r.state()))
                                .toList();
                refs.add(
                        new ItemModelRef(
                                id,
                                ItemModelRefState.READ,
                                read.state(),
                                textures,
                                read.model() == null ? 0 : read.model().skinFileDataIds().size(),
                                read.code(),
                                read.message(),
                                read.observedAt(),
                                read.keyNames()));
            }
            return new ItemModelRefs(
                    ids.size() > options.maxModels()
                            ? ItemModelsState.CAPPED
                            : ItemModelsState.COMPLETE,
                    refs,
                    ids.size() > options.maxModels() ? MODEL_CAP_CODE : null,
                    ids.size() > options.maxModels() ? "Model files exceed detail read cap" : null);
        }
    }

    private static ItemsPageState pageState(String code) {
        return switch (code) {
            case ItemsSourceException.NAME_UNSUPPORTED -> ItemsPageState.NAME_UNSUPPORTED;
            case ItemsSourceException.SCHEMA_AMBIGUOUS -> ItemsPageState.SCHEMA_AMBIGUOUS;
            default -> ItemsPageState.SOURCE_INVALID;
        };
    }

    private static ItemResultState resultState(String code) {
        return switch (code) {
            case ItemsSourceException.NAME_UNSUPPORTED -> ItemResultState.NAME_UNSUPPORTED;
            case ItemsSourceException.SCHEMA_AMBIGUOUS -> ItemResultState.SCHEMA_AMBIGUOUS;
            default -> ItemResultState.SOURCE_INVALID;
        };
    }
}
