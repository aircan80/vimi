package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileLocales;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Independent consumer items fake; no real service, tables or file acquisition is invoked. */
public final class MemoryItems implements Items {
    public record FakeItem(
            long id,
            ItemName name,
            List<ItemSourceValue> values,
            Long iconFileDataId,
            ItemIcon icon,
            boolean isCopy,
            Long copySourceId,
            String sparseKey,
            TableTypes.Row itemRow,
            TableTypes.Row sparseRow,
            ItemRowState item,
            String itemKey,
            List<Db2Types.Column> itemColumns,
            List<Db2Types.Column> sparseColumns,
            ItemClassLabels labels,
            ItemAppearanceSet appearances,
            List<ItemIconSourced> icons) {
        public FakeItem(long id, ItemName name) {
            this(
                    id,
                    name,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    ItemRowState.FOUND,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public FakeItem {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Unsigned32 id required");
            }
            java.util.Objects.requireNonNull(name);
            java.util.Objects.requireNonNull(item);
            values = values == null ? null : List.copyOf(values);
            itemColumns = itemColumns == null ? null : List.copyOf(itemColumns);
            sparseColumns = sparseColumns == null ? null : List.copyOf(sparseColumns);
            icons = icons == null ? null : List.copyOf(icons);
        }
    }

    private record Key(BuildRef build, String locale) {}

    private record Failure(FileTypes.FilesOpenState state, String code) {}

    private final Map<Key, List<FakeItem>> builds = new ConcurrentHashMap<>();
    private final Map<BuildRef, Failure> failures = new ConcurrentHashMap<>();
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();
    private final int maxScanRows;

    public MemoryItems() {
        this(20000);
    }

    public MemoryItems(int maxScanRows) {
        if (maxScanRows < 1) {
            throw new IllegalArgumentException("Positive scan bound required");
        }
        this.maxScanRows = maxScanRows;
    }

    public MemoryItems add(BuildRef build, String locale, List<FakeItem> items) {
        FileLocales.bitOf(locale);
        if (items.stream().map(FakeItem::id).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Duplicate item id");
        }
        builds.put(new Key(build, locale), List.copyOf(items));
        failures.remove(build);
        return this;
    }

    public MemoryItems addFailing(BuildRef build, FileTypes.FilesOpenState state, String code) {
        if (state == FileTypes.FilesOpenState.OPEN) {
            throw new IllegalArgumentException("Failure must not be OPEN");
        }
        failures.put(build, new Failure(state, code));
        return this;
    }

    public int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    private void count(String method) {
        calls.merge(method, 1, Integer::sum);
    }

    @Override
    public CompletableFuture<ItemsOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        count("forBuild");
        FileLocales.bitOf(locale);
        token.check();
        var failed = failures.get(build);
        if (failed != null) {
            return CompletableFuture.completedFuture(
                    new ItemsOpenResult(
                            build,
                            locale,
                            failed.state(),
                            null,
                            failed.code(),
                            "Scripted failure",
                            null));
        }
        var items = builds.get(new Key(build, locale));
        if (items == null) {
            boolean known = builds.keySet().stream().anyMatch(key -> key.build().equals(build));
            return CompletableFuture.completedFuture(
                    new ItemsOpenResult(
                            build,
                            locale,
                            known
                                    ? FileTypes.FilesOpenState.LOCALE_NOT_IN_BUILD
                                    : FileTypes.FilesOpenState.NEEDS_NETWORK,
                            null,
                            known ? "FILES_LOCALE_NOT_IN_BUILD" : "BUILD_NEEDS_NETWORK",
                            null,
                            null));
        }
        return CompletableFuture.completedFuture(
                new ItemsOpenResult(
                        build,
                        locale,
                        FileTypes.FilesOpenState.OPEN,
                        new Handle(build, locale, items),
                        null,
                        null,
                        null));
    }

    private static ItemSummary summary(FakeItem item) {
        return new ItemSummary(
                item.id(),
                item.isCopy(),
                item.copySourceId(),
                item.name(),
                item.values() == null ? List.of() : item.values(),
                item.iconFileDataId(),
                item.sparseKey(),
                item.item(),
                item.item() == ItemRowState.PROTECTED ? item.itemKey() : null);
    }

    private final class Handle implements BuildItems {
        private final BuildRef build;
        private final String locale;
        private final List<FakeItem> items;
        private volatile boolean closed;

        Handle(BuildRef build, String locale, List<FakeItem> items) {
            this.build = build;
            this.locale = locale;
            this.items = items;
        }

        @Override
        public BuildRef build() {
            return build;
        }

        @Override
        public String locale() {
            return locale;
        }

        private void enter(String method, TableTypes.Priority priority, Cancellation token) {
            count(method);
            if (closed) {
                throw new IllegalStateException("Items closed");
            }
            java.util.Objects.requireNonNull(priority);
            token.check();
        }

        private ItemsPage invalid() {
            return new ItemsPage(
                    ItemsPageState.INVALID_CURSOR,
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    "INVALID_CURSOR",
                    "Cursor differs from request/build/limit");
        }

        @Override
        public CompletableFuture<ItemsPage> list(
                ItemQuery query, TableTypes.Priority priority, Cancellation token) {
            ItemQueries.validate(query);
            enter("list", priority, token);
            String binding = ItemQueries.binding(build, locale, query);
            if (query.exactId() != null) {
                if (query.after() != null) {
                    return CompletableFuture.completedFuture(invalid());
                }
                var found =
                        items.stream()
                                .filter(item -> item.id() == query.exactId())
                                .findFirst()
                                .orElse(null);
                return CompletableFuture.completedFuture(
                        new ItemsPage(
                                ItemsPageState.OK,
                                found == null ? List.of() : List.of(summary(found)),
                                null,
                                1,
                                null,
                                null,
                                null,
                                null));
            }
            long after = -1;
            if (query.after() != null) {
                var cursor = ItemQueries.decodeCursor(query.after(), binding);
                if (cursor == null || cursor.a() >= items.size()) {
                    return CompletableFuture.completedFuture(invalid());
                }
                if (!"fake-items".equals(cursor.i())) {
                    return CompletableFuture.completedFuture(
                            new ItemsPage(
                                    ItemsPageState.STALE,
                                    List.of(),
                                    null,
                                    0,
                                    null,
                                    null,
                                    null,
                                    null));
                }
                after = cursor.a();
            }
            var entries = new ArrayList<ItemSummary>();
            long looked = 0, at = after + 1;
            for (;
                    at < items.size() && entries.size() < query.limit() && looked < maxScanRows;
                    at++) {
                token.check();
                looked++;
                var item = items.get((int) at);
                if (ItemQueries.matches(item.name(), query.query())) {
                    entries.add(summary(item));
                }
            }
            String next =
                    at >= items.size()
                            ? null
                            : ItemQueries.encodeCursor(
                                    new ItemCursor(binding, at - 1, "fake-items", null));
            return CompletableFuture.completedFuture(
                    new ItemsPage(
                            ItemsPageState.OK, entries, next, looked, null, null, null, null));
        }

        @Override
        public CompletableFuture<ItemResult> getData(
                long id, TableTypes.Priority priority, Cancellation token) {
            // Seeded fake details are already in memory; this never touches asset services.
            return get(id, priority, token);
        }

        @Override
        public CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
                TableTypes.Priority priority, Cancellation token) {
            enter("warmup", priority, token);
            return CompletableFuture.completedFuture(
                    new com.vibeminer.concepts.core.ConceptWarmup.Result(List.of()));
        }

        @Override
        public CompletableFuture<ItemResult> get(
                long id, TableTypes.Priority priority, Cancellation token) {
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Unsigned32 id required");
            }
            enter("get", priority, token);
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                if (item.id() != id) {
                    continue;
                }
                if (item.item() != ItemRowState.FOUND) {
                    return CompletableFuture.completedFuture(
                            new ItemResult(
                                    item.item() == ItemRowState.PROTECTED
                                            ? ItemResultState.PROTECTED
                                            : ItemResultState.UNKNOWN,
                                    null,
                                    null,
                                    null,
                                    item.item() == ItemRowState.PROTECTED ? item.itemKey() : null,
                                    null,
                                    null));
                }
                var itemRow =
                        item.itemRow() == null
                                ? new TableTypes.Row(
                                        i,
                                        new Db2Types.Row(
                                                id,
                                                item.isCopy(),
                                                item.copySourceId(),
                                                List.of(Long.toString(id)),
                                                null))
                                : item.itemRow();
                boolean present =
                        item.name().state() == ItemNameState.VALID
                                || item.name().state() == ItemNameState.EMPTY
                                || item.name().state() == ItemNameState.INVALID;
                var sparseRow =
                        item.sparseRow() != null
                                ? item.sparseRow()
                                : present
                                        ? new TableTypes.Row(
                                                i,
                                                new Db2Types.Row(
                                                        id,
                                                        item.isCopy(),
                                                        item.copySourceId(),
                                                        List.of(Long.toString(id)),
                                                        null))
                                        : null;
                var icon =
                        item.icon() != null
                                ? item.icon()
                                : new ItemIcon(
                                        item.iconFileDataId() == null
                                                ? ItemIconState.FIELD_UNAVAILABLE
                                                : item.iconFileDataId() == 0
                                                        ? ItemIconState.NO_ICON
                                                        : ItemIconState.FILE_FAILED,
                                        item.iconFileDataId(),
                                        item.iconFileDataId() != null && item.iconFileDataId() > 0
                                                ? BlpImageTypes.State.FILE_FAILED
                                                : null,
                                        null,
                                        null,
                                        null,
                                        item.iconFileDataId() != null && item.iconFileDataId() > 0
                                                ? FileTypes.FileReadState.UNKNOWN_FILE
                                                : null,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null);
                var sparseState =
                        sparseRow != null
                                ? ItemSparseState.PRESENT
                                : ItemFacts.sparseState(item.name().state());
                var sparseColumns =
                        sparseState == ItemSparseState.UNKNOWN
                                ? null
                                : item.sparseColumns() == null
                                        ? defaultColumns(
                                                sparseRow == null
                                                        ? 1
                                                        : sparseRow.row().values().size())
                                        : item.sparseColumns();
                var facts =
                        ItemFacts.project(
                                item.itemColumns() == null
                                        ? defaultColumns(itemRow.row().values().size())
                                        : item.itemColumns(),
                                sparseColumns,
                                itemRow.row(),
                                sparseRow == null ? null : sparseRow.row(),
                                sparseState);
                var labels =
                        item.labels() == null
                                ? new ItemClassLabels(
                                        defaultLabel(item, false), defaultLabel(item, true))
                                : item.labels();
                var appearances =
                        item.appearances() == null
                                ? new ItemAppearanceSet(
                                        ItemAppearancesState.TABLE_NOT_READABLE,
                                        List.of(),
                                        ItemDefaultAppearanceState.UNKNOWN,
                                        null,
                                        0,
                                        new TableTypes.Entry(
                                                0,
                                                "ItemModifiedAppearance",
                                                TableTypes.State.NO_DEFINITION,
                                                true,
                                                null,
                                                null,
                                                null),
                                        ItemModelRefs.NONE,
                                        null,
                                        null)
                                : item.appearances();
                var detail =
                        new ItemDetail(
                                summary(item),
                                itemRow,
                                sparseRow,
                                icon,
                                facts,
                                labels,
                                item.icons() == null
                                        ? List.of(
                                                new ItemIconSourced(
                                                        "Item", "IconFileDataID", id, icon))
                                        : item.icons(),
                                appearances);
                return CompletableFuture.completedFuture(
                        new ItemResult(
                                ItemResultState.FOUND, detail, null, null, null, null, null));
            }
            return CompletableFuture.completedFuture(
                    new ItemResult(ItemResultState.ABSENT, null, null, null, null, null, null));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<Db2Types.Column> defaultColumns(int count) {
        var columns = new ArrayList<Db2Types.Column>();
        columns.add(new Db2Types.Column("ID", "integer", true, null, null));
        for (int i = 1; i < count; i++) {
            columns.add(new Db2Types.Column("Cell" + i, "integer", false, null, null));
        }
        return columns;
    }

    private static ItemClassLabel defaultLabel(FakeItem item, boolean subclass) {
        var source =
                ItemClassifications.sourceKey(
                        item.values() == null ? List.of() : item.values(), subclass);
        return new ItemClassLabel(
                source.state() == null ? ItemClassLabelState.TABLE_NOT_READABLE : source.state(),
                source.keys(),
                null,
                null,
                null,
                null,
                0,
                source.state() == null
                        ? new TableTypes.Entry(
                                0,
                                subclass ? "ItemSubClass" : "ItemClass",
                                TableTypes.State.NO_DEFINITION,
                                true,
                                null,
                                null,
                                null)
                        : null,
                null,
                null);
    }
}
