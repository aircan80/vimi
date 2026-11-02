package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Independent layer5 consumer double with shared exact query/relation rules and immutable seeded
 * rows.
 */
public final class MemoryTables implements Tables {
    public record FakeTable(
            TableTypes.Entry entry,
            List<Db2Types.Column> columns,
            List<Db2Types.Row> rows,
            TableSchema schema,
            Map<Long, String> protectedIds,
            boolean generated) {
        public FakeTable {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
            protectedIds = protectedIds == null ? Map.of() : Map.copyOf(protectedIds);
        }
    }

    private record Key(BuildRef build, String locale) {}

    private final Map<Key, TreeMap<Long, FakeTable>> builds = new ConcurrentHashMap<>();
    private final java.util.Set<String> checked = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();

    public MemoryTables add(BuildRef build, String locale, List<FakeTable> tables) {
        WowLocale.parse(locale);
        var byId = new TreeMap<Long, FakeTable>();
        for (var table : tables) {
            if (byId.putIfAbsent(table.entry().fileDataId(), table) != null) {
                throw new IllegalArgumentException("Duplicate fake table");
            }
        }
        builds.put(new Key(build, locale), byId);
        String prefix = build + "|" + locale + "|";
        checked.removeIf(value -> value.startsWith(prefix));
        return this;
    }

    public int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    private void call(String method) {
        calls.merge(method, 1, Integer::sum);
    }

    @Override
    public CompletableFuture<TableTypes.OpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        WowLocale.parse(locale);
        token.check();
        call("forBuild");
        var tables = builds.get(new Key(build, locale));
        if (tables == null) {
            return CompletableFuture.completedFuture(
                    new TableTypes.OpenResult(
                            build, locale, FilesOpenState.NEEDS_NETWORK, null, null, null, null));
        }
        return CompletableFuture.completedFuture(
                new TableTypes.OpenResult(
                        build,
                        locale,
                        FilesOpenState.OPEN,
                        new Handle(build, locale),
                        null,
                        null,
                        null));
    }

    private final class Handle implements BuildTables {
        private final BuildRef build;
        private final String locale;
        private volatile boolean closed;

        Handle(BuildRef build, String locale) {
            this.build = build;
            this.locale = locale;
        }

        @Override
        public BuildRef build() {
            return build;
        }

        @Override
        public String locale() {
            return locale;
        }

        private TreeMap<Long, FakeTable> tables() {
            return builds.get(new Key(build, locale));
        }

        private void enter(String method, Cancellation token) {
            if (closed) {
                throw new IllegalStateException("Tables closed");
            }
            token.check();
            call(method);
        }

        private FakeTable find(TableTypes.Ref ref) {
            if (ref.fileDataId() != null) {
                return tables().get(ref.fileDataId());
            }
            return tables().values().stream()
                    .filter(
                            t ->
                                    t.entry().name() != null
                                            && TableQueries.namesEqual(
                                                    t.entry().name(), ref.name()))
                    .findFirst()
                    .orElse(null);
        }

        private String key(long id) {
            return build + "|" + locale + "|" + id;
        }

        private TableTypes.Entry entry(TableTypes.Ref ref, boolean check) {
            var fake = find(ref);
            if (fake == null) {
                return new TableTypes.Entry(
                        ref.fileDataId() == null ? 0 : ref.fileDataId(),
                        ref.name(),
                        ref.fileDataId() == null
                                ? TableTypes.State.NO_DEFINITION
                                : TableTypes.State.NOT_IN_BUILD,
                        check,
                        null,
                        null,
                        null);
            }
            var e = fake.entry();
            boolean done = check || fake.generated() || checked.contains(key(e.fileDataId()));
            if (check
                    && (TableQueries.isReadable(e.state())
                            || e.state() == TableTypes.State.ENCRYPTED)) {
                checked.add(key(e.fileDataId()));
            }
            TableTypes.State state = e.state();
            if (!done) {
                state =
                        switch (e.state()) {
                            case NO_DEFINITION,
                                    DEFINITION_UNUSABLE,
                                    NOT_IN_BUILD,
                                    NOT_IN_LOCALE,
                                    NOT_IN_ENCODING ->
                                    e.state();
                            case NEEDS_NETWORK, MISSING, UNAVAILABLE -> TableTypes.State.NOT_STORED;
                            default -> TableTypes.State.NOT_CHECKED;
                        };
            }
            return new TableTypes.Entry(
                    e.fileDataId(),
                    e.name(),
                    state,
                    done,
                    e.reason(),
                    done ? e.counts() : null,
                    e.contentKey());
        }

        @Override
        public CompletableFuture<TableTypes.Entry> check(
                TableTypes.Ref ref, TableTypes.Priority priority, Cancellation token) {
            enter("check", token);
            return CompletableFuture.completedFuture(entry(ref, true));
        }

        @Override
        public CompletableFuture<TableTypes.SchemaResult> schema(
                TableTypes.Ref ref, TableTypes.Priority priority, Cancellation token) {
            enter("schema", token);
            var fake = find(ref);
            return CompletableFuture.completedFuture(
                    new TableTypes.SchemaResult(
                            entry(ref, true),
                            fake == null ? null : fake.columns(),
                            fake == null ? null : fake.schema()));
        }

        private String identity(FakeTable table) {
            return "fake:"
                    + build.buildConfig()
                    + ":"
                    + locale
                    + ":"
                    + table.entry().fileDataId()
                    + ":"
                    + (table.entry().contentKey() == null ? "" : table.entry().contentKey());
        }

        @Override
        public CompletableFuture<TableTypes.Page> page(
                TableTypes.PageRequest request, TableTypes.Priority priority, Cancellation token) {
            enter("page", token);
            TableQueries.validate(request);
            var e = entry(request.table(), true);
            if (!TableQueries.isReadable(e.state())) {
                return CompletableFuture.completedFuture(TableQueries.notReadable(e));
            }
            var fake = find(request.table());
            return TableQueries.page(
                    e,
                    identity(fake),
                    request,
                    fake.rows().size(),
                    (first, n, t) -> {
                        t.check();
                        return CompletableFuture.completedFuture(
                                List.copyOf(
                                        fake.rows()
                                                .subList(
                                                        (int) first,
                                                        (int)
                                                                Math.min(
                                                                        fake.rows().size(),
                                                                        first + n))));
                    },
                    fake.columns(),
                    fake.schema(),
                    200000,
                    token);
        }

        @Override
        public CompletableFuture<TableTypes.RowResult> get(
                TableTypes.Ref ref, long id, TableTypes.Priority priority, Cancellation token) {
            enter("get", token);
            if (id < 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Unsigned32 ID required");
            }
            var e = entry(ref, true);
            var f = find(ref);
            if (f != null && TableQueries.isReadable(e.state())) {
                for (int i = 0; i < f.rows().size(); i++) {
                    if (f.rows().get(i).id() == id) {
                        return CompletableFuture.completedFuture(
                                new TableTypes.RowResult(
                                        e,
                                        TableTypes.RowOutcome.FOUND,
                                        new TableTypes.Row(i, f.rows().get(i)),
                                        null));
                    }
                }
            }
            if (f != null && f.protectedIds().containsKey(id)) {
                return CompletableFuture.completedFuture(
                        new TableTypes.RowResult(
                                e,
                                TableTypes.RowOutcome.PROTECTED,
                                null,
                                f.protectedIds().get(id)));
            }
            return CompletableFuture.completedFuture(
                    new TableTypes.RowResult(
                            e,
                            e.state() == TableTypes.State.READY
                                    ? TableTypes.RowOutcome.ABSENT
                                    : TableTypes.RowOutcome.UNKNOWN,
                            null,
                            null));
        }

        @Override
        public AsyncCursor<TableTypes.Entry> list(
                TableTypes.ListFilter filter, TableTypes.Priority priority, Cancellation token) {
            enter("list", token);
            var entries =
                    tables().keySet().stream()
                            .map(id -> entry(TableTypes.Ref.id(id), false))
                            .filter(e -> TableQueries.matches(filter, e))
                            .toList();
            return new AsyncCursor<>() {
                int at;
                boolean ended;

                @Override
                public synchronized CompletableFuture<List<TableTypes.Entry>> next(
                        int limit, Cancellation t) {
                    t.check();
                    if (limit < 1) {
                        throw new IllegalArgumentException("Positive page size required");
                    }
                    if (ended) {
                        return CompletableFuture.completedFuture(List.of());
                    }
                    int end = Math.min(entries.size(), at + limit);
                    var page = List.copyOf(entries.subList(at, end));
                    at = end;
                    return CompletableFuture.completedFuture(page);
                }

                @Override
                public void close() {
                    ended = true;
                }
            };
        }

        private TableRelations.Source source(TableTypes.Priority priority) {
            return new TableRelations.Source() {
                @Override
                public CompletableFuture<TableRelations.RelationTable> open(
                        TableTypes.Ref ref, Cancellation token) {
                    var f = find(ref);
                    return CompletableFuture.completedFuture(
                            new TableRelations.RelationTable(
                                    entry(ref, true),
                                    f == null ? null : f.columns(),
                                    f == null ? null : f.schema(),
                                    f == null ? 0 : f.rows().size(),
                                    f == null
                                            ? null
                                            : (first, n, t) -> {
                                                t.check();
                                                return CompletableFuture.completedFuture(
                                                        List.copyOf(
                                                                f.rows()
                                                                        .subList(
                                                                                (int) first,
                                                                                (int)
                                                                                        Math.min(
                                                                                                f.rows()
                                                                                                        .size(),
                                                                                                first
                                                                                                        + n))));
                                            }));
                }

                @Override
                public CompletableFuture<TableTypes.RowResult> get(
                        TableTypes.Ref ref, long id, Cancellation token) {
                    return Handle.this.get(ref, id, priority, token);
                }

                @Override
                public CompletableFuture<List<TableTypes.Entry>> list(Cancellation token) {
                    return CompletableFuture.completedFuture(
                            tables().keySet().stream()
                                    .map(id -> entry(TableTypes.Ref.id(id), false))
                                    .toList());
                }

                @Override
                public CompletableFuture<TableRelations.RelationSchema> schema(
                        TableTypes.Entry e, Cancellation token) {
                    var f = tables().get(e.fileDataId());
                    return CompletableFuture.completedFuture(
                            new TableRelations.RelationSchema(
                                    f.schema() != null
                                            ? TableRelations.SchemaState.RESOLVED
                                            : e.state() == TableTypes.State.NO_DEFINITION
                                                    ? TableRelations.SchemaState.NO_DEFINITION
                                                    : TableRelations.SchemaState.UNRESOLVED,
                                    f.schema()));
                }
            };
        }

        @Override
        public CompletableFuture<TableTypes.FollowResult> follow(
                TableTypes.FollowRequest request,
                TableTypes.Priority priority,
                Cancellation token) {
            enter("follow", token);
            return TableRelations.follow(source(priority), request, 200000, token);
        }

        @Override
        public CompletableFuture<TableTypes.ReferencesResult> referencedBy(
                TableTypes.ReferencesRequest request,
                TableTypes.Priority priority,
                Cancellation token) {
            enter("referencedBy", token);
            return TableRelations.referencedBy(
                    source(priority), request, 200000, 256, 1000000, token);
        }

        @Override
        public CompletableFuture<TableTypes.PreloadResult> preload(
                Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
            enter("preload", Cancellation.NONE);
            var failures = new ArrayList<TableTypes.Entry>();
            int done = 0;
            TableTypes.PreloadProgress report =
                    new TableTypes.PreloadProgress(
                            TableTypes.PreloadStage.DOWNLOADING,
                            0,
                            tables().size(),
                            0,
                            0,
                            0,
                            0,
                            0,
                            tables().size(),
                            0);
            emit(progress, report);
            for (long id : tables().keySet()) {
                if (token.isCanceled() || closed) {
                    return CompletableFuture.completedFuture(
                            new TableTypes.PreloadResult(
                                    TableTypes.PreloadOutcome.CANCELED,
                                    report,
                                    failures,
                                    null,
                                    null,
                                    null));
                }
                var e = entry(TableTypes.Ref.id(id), true);
                if (!TableQueries.isReadable(e.state())
                        && e.state() != TableTypes.State.ENCRYPTED) {
                    failures.add(e);
                }
                done++;
                report =
                        new TableTypes.PreloadProgress(
                                TableTypes.PreloadStage.PREPARING,
                                done,
                                tables().size(),
                                failures.size(),
                                0,
                                0,
                                0,
                                done,
                                tables().size(),
                                failures.size());
                emit(progress, report);
            }
            return CompletableFuture.completedFuture(
                    new TableTypes.PreloadResult(
                            failures.isEmpty()
                                    ? TableTypes.PreloadOutcome.COMPLETED
                                    : TableTypes.PreloadOutcome.INCOMPLETE,
                            report,
                            failures,
                            null,
                            null,
                            null));
        }

        @Override
        public CompletableFuture<TableTypes.PreloadResult> prepareKnown(
                Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
            enter("prepareKnown", Cancellation.NONE);
            var inventory = new TreeMap<>(tables());
            var ledger = new ArrayList<TableTypes.Entry>();
            int done = 0;
            int total =
                    (int)
                            inventory.values().stream()
                                    .filter(
                                            table ->
                                                    table.entry().state()
                                                                    != TableTypes.State.NOT_IN_BUILD
                                                            && table.entry().state()
                                                                    != TableTypes.State
                                                                            .NOT_IN_LOCALE)
                                    .count();
            TableTypes.PreloadProgress report =
                    new TableTypes.PreloadProgress(
                            TableTypes.PreloadStage.PREPARING, 0, 0, 0, 0, 0, 0, 0, total, 0);
            for (var table : inventory.values()) {
                if (token.isCanceled() || closed) {
                    return CompletableFuture.completedFuture(
                            new TableTypes.PreloadResult(
                                    TableTypes.PreloadOutcome.CANCELED,
                                    report,
                                    ledger,
                                    null,
                                    null,
                                    null));
                }
                var item = table.entry();
                ledger.add(
                        new TableTypes.Entry(
                                item.fileDataId(),
                                item.name(),
                                item.state(),
                                true,
                                item.reason(),
                                item.counts(),
                                item.contentKey()));
                if (item.state() != TableTypes.State.NOT_IN_BUILD
                        && item.state() != TableTypes.State.NOT_IN_LOCALE) {
                    done++;
                }
                int unavailable =
                        (int)
                                ledger.stream()
                                        .filter(
                                                value ->
                                                        value.state()
                                                                        != TableTypes.State
                                                                                .NOT_IN_BUILD
                                                                && value.state()
                                                                        != TableTypes.State
                                                                                .NOT_IN_LOCALE
                                                                && !TableQueries.isReadable(
                                                                        value.state()))
                                        .count();
                report =
                        new TableTypes.PreloadProgress(
                                TableTypes.PreloadStage.PREPARING,
                                done,
                                total,
                                unavailable,
                                0,
                                0,
                                0,
                                done,
                                total,
                                unavailable);
                emit(progress, report);
            }
            return CompletableFuture.completedFuture(
                    new TableTypes.PreloadResult(
                            token.isCanceled()
                                    ? TableTypes.PreloadOutcome.CANCELED
                                    : ledger.stream()
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
                                                    : TableTypes.PreloadOutcome.COMPLETED,
                            report,
                            ledger,
                            null,
                            null,
                            null));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void emit(
            Consumer<TableTypes.PreloadProgress> progress, TableTypes.PreloadProgress report) {
        if (progress != null) {
            try {
                progress.accept(report);
            } catch (Throwable ignored) {
            }
        }
    }
}
