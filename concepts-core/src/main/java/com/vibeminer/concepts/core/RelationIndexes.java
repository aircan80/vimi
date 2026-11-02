package com.vibeminer.concepts.core;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-handle bounded reverse relation indexes; source page identities are checked on every answer.
 */
public final class RelationIndexes {
    public static final int DEFAULT_MAX_ENTRIES = 4_000_000;
    public static final String COLUMN_CODE = "RELATION_INDEX_COLUMN",
            LIMIT_CODE = "RELATION_INDEX_LIMIT";

    public enum State {
        OK,
        NOT_READABLE,
        UNSUPPORTED,
        INDEX_LIMIT,
        STALE
    }

    public record Lookup(
            State state,
            List<TableTypes.Row> rows,
            int matches,
            TableTypes.Entry table,
            String code,
            String message,
            String identity) {
        public Lookup {
            rows = List.copyOf(rows);
        }

        public boolean complete() {
            return state == State.OK && table != null && table.state() == TableTypes.State.READY;
        }
    }

    private record Key(String table, String column) {}

    private record Index(
            State state,
            TableTypes.Entry table,
            String identity,
            long[] pairs,
            int entries,
            String code,
            String message) {}

    private static final class Pending {
        final CompletableFuture<Index> result = new CompletableFuture<>();
        volatile TableTypes.Priority priority;

        Pending(TableTypes.Priority p) {
            priority = p;
        }

        void join(TableTypes.Priority p) {
            if (p == TableTypes.Priority.INTERACTIVE) {
                priority = p;
            }
        }
    }

    private final BuildTables tables;
    private final int maxEntries;
    private final Object gate = new Object();
    private final Map<Key, Pending> indexes = new HashMap<>();
    private int used;
    private final AtomicInteger builds = new AtomicInteger();

    public RelationIndexes(BuildTables tables) {
        this(tables, DEFAULT_MAX_ENTRIES);
    }

    public RelationIndexes(BuildTables tables, int maxEntries) {
        this.tables = Objects.requireNonNull(tables);
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Positive relation entry budget required");
        }
        this.maxEntries = maxEntries;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public int entries() {
        synchronized (gate) {
            return used;
        }
    }

    public int builds() {
        return builds.get();
    }

    public CompletableFuture<Lookup> lookup(
            String table,
            String column,
            long key,
            int maxRows,
            TableTypes.Priority priority,
            Cancellation token) {
        return lookup(table, column, key, maxRows, priority, token, 0);
    }

    public CompletableFuture<Lookup> lookup(
            String table,
            String column,
            long key,
            int maxRows,
            TableTypes.Priority priority,
            Cancellation token,
            int skip) {
        if (table == null
                || table.isBlank()
                || column == null
                || column.isBlank()
                || key < 0
                || key > 0xffff_ffffL
                || maxRows < 1
                || skip < 0) {
            throw new IllegalArgumentException("Invalid relation request");
        }
        Objects.requireNonNull(priority);
        Objects.requireNonNull(token);
        return async(() -> lookupCore(new Key(table, column), key, maxRows, priority, token, skip));
    }

    private Lookup lookupCore(
            Key key,
            long value,
            int maxRows,
            TableTypes.Priority priority,
            Cancellation token,
            int skip) {
        TableTypes.Entry last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            token.check();
            Index index = index(key, priority, token);
            if (index.state != State.OK) {
                return result(index, List.of(), 0);
            }
            last = index.table;
            int first = lower(index.pairs, value << 32),
                    end =
                            value == 0xffff_ffffL
                                    ? index.pairs.length
                                    : lower(index.pairs, (value + 1) << 32),
                    count = end - first;
            int from = first + Math.min(skip, count),
                    take = Math.min(count - (from - first), maxRows);
            List<TableTypes.Row> rows = new ArrayList<>(take);
            boolean stale = false;
            if (take == 0) {
                TableTypes.Page page = page(key.table, -1, 1, index.identity, priority, token);
                if (page.state() == TableTypes.PageState.NOT_READABLE) {
                    return notReadable(page.table());
                }
                stale = page.state() != TableTypes.PageState.OK;
            }
            for (int i = from; i < from + take && !stale; i++) {
                long ordinal = index.pairs[i] & 0xffff_ffffL;
                TableTypes.Page page =
                        page(key.table, ordinal - 1, 1, index.identity, priority, token);
                if (page.state() == TableTypes.PageState.NOT_READABLE) {
                    return notReadable(page.table());
                }
                if (page.state() != TableTypes.PageState.OK
                        || page.rows().size() != 1
                        || page.rows().getFirst().ordinal() != ordinal) {
                    stale = true;
                } else {
                    rows.add(page.rows().getFirst());
                }
            }
            if (!stale) {
                return result(index, rows, count);
            }
            drop(key, index);
        }
        return new Lookup(
                State.STALE, List.of(), 0, last, null, "Relation source changed twice", null);
    }

    private static Lookup result(Index index, List<TableTypes.Row> rows, int count) {
        return new Lookup(
                index.state, rows, count, index.table, index.code, index.message, index.identity);
    }

    private static Lookup notReadable(TableTypes.Entry table) {
        return new Lookup(State.NOT_READABLE, List.of(), 0, table, null, null, null);
    }

    private TableTypes.Page page(
            String table,
            long after,
            int limit,
            String identity,
            TableTypes.Priority priority,
            Cancellation token) {
        token.check();
        TableTypes.Page page =
                tables.page(
                                new TableTypes.PageRequest(
                                        TableTypes.Ref.forName(table),
                                        after,
                                        limit,
                                        List.of(),
                                        identity),
                                priority,
                                token)
                        .join();
        token.check();
        return page;
    }

    private Index index(Key key, TableTypes.Priority priority, Cancellation token) {
        for (; ; ) {
            token.check();
            Pending pending;
            boolean owner = false;
            synchronized (gate) {
                pending = indexes.get(key);
                if (pending == null) {
                    pending = new Pending(priority);
                    indexes.put(key, pending);
                    owner = true;
                } else {
                    pending.join(priority);
                }
            }
            if (owner) {
                try {
                    Index built = build(key, pending, token);
                    if (built.state != State.OK && built.state != State.INDEX_LIMIT) {
                        forget(key, pending);
                    }
                    pending.result.complete(built);
                    return built;
                } catch (Throwable failure) {
                    forget(key, pending);
                    Throwable actual = unwrap(failure);
                    pending.result.completeExceptionally(actual);
                    throw propagate(actual);
                }
            }
            try {
                return await(pending.result, token);
            } catch (CancellationException e) {
                token.check();
                if (!pending.result.isCompletedExceptionally()) {
                    throw e;
                } /* Canceled owner: a live waiter starts a fresh scan. */
            }
        }
    }

    private Index build(Key key, Pending pending, Cancellation token) {
        builds.incrementAndGet();
        token.check();
        TableTypes.SchemaResult schema =
                tables.schema(TableTypes.Ref.forName(key.table), pending.priority, token).join();
        token.check();
        if (!TableQueries.isReadable(schema.table().state())) {
            return new Index(State.NOT_READABLE, schema.table(), null, null, 0, null, null);
        }
        List<Db2Types.Column> columns = schema.columns() == null ? List.of() : schema.columns();
        int at = -1;
        for (int i = 0; i < columns.size(); i++) {
            Db2Types.Column c = columns.get(i);
            if (!c.name().equals(key.column)
                    || !c.kind().equals("integer")
                    || c.interpretation() != null) {
                continue;
            }
            if (at >= 0) {
                at = -1;
                break;
            }
            at = i;
        }
        if (at < 0) {
            return new Index(
                    State.UNSUPPORTED,
                    schema.table(),
                    null,
                    null,
                    0,
                    COLUMN_CODE,
                    "Relation needs one plain integer column");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            Pairs pairs = new Pairs();
            int reserved = 0;
            boolean kept = false;
            TableTypes.Entry entry = schema.table();
            String identity = null;
            try {
                long after = -1;
                boolean stale = false;
                for (; ; ) {
                    TableTypes.Page page =
                            page(key.table, after, 1000, identity, pending.priority, token);
                    entry = page.table();
                    if (page.state() == TableTypes.PageState.NOT_READABLE) {
                        return new Index(State.NOT_READABLE, entry, null, null, 0, null, null);
                    }
                    if (page.state() != TableTypes.PageState.OK) {
                        stale = true;
                        break;
                    }
                    identity = page.identity();
                    int valid = 0;
                    for (TableTypes.Row row : page.rows()) {
                        if (keyOf(row.row(), at) != null) {
                            valid++;
                        }
                    }
                    synchronized (gate) {
                        if ((long) used + valid > maxEntries) {
                            return new Index(
                                    State.INDEX_LIMIT,
                                    entry,
                                    null,
                                    null,
                                    0,
                                    LIMIT_CODE,
                                    "Relation entry budget exceeded");
                        }
                        used += valid;
                        reserved += valid;
                    }
                    for (TableTypes.Row row : page.rows()) {
                        Long value = keyOf(row.row(), at);
                        if (value == null) {
                            continue;
                        }
                        if (row.ordinal() < 0 || row.ordinal() > 0xffff_ffffL) {
                            throw new IllegalStateException("Relation ordinal exceeds uint32");
                        }
                        pairs.add((value << 32) | row.ordinal());
                    }
                    if (page.nextAfter() == null || page.rows().isEmpty()) {
                        break;
                    }
                    if (page.nextAfter() <= after) {
                        throw new IllegalStateException("Nonadvancing relation cursor");
                    }
                    after = page.nextAfter();
                }
                if (!stale) {
                    long[] sorted = pairs.sorted();
                    kept = true;
                    return new Index(State.OK, entry, identity, sorted, reserved, null, null);
                }
                if (attempt == 1) {
                    return new Index(
                            State.STALE,
                            entry,
                            null,
                            null,
                            0,
                            null,
                            "Relation source changed while indexing twice");
                }
            } finally {
                if (!kept) {
                    synchronized (gate) {
                        used -= reserved;
                    }
                }
            }
        }
        throw new AssertionError();
    }

    private static Long keyOf(Db2Types.Row row, int at) {
        if (at >= row.values().size()
                || row.textIssues() != null
                        && row.textIssues().stream().anyMatch(issue -> issue.columnIndex() == at)) {
            return null;
        }
        return ConceptQueries.tryParseId(row.values().get(at));
    }

    private void forget(Key key, Pending pending) {
        synchronized (gate) {
            indexes.remove(key, pending);
        }
    }

    private void drop(Key key, Index index) {
        synchronized (gate) {
            Pending p = indexes.get(key);
            if (p != null
                    && p.result.isDone()
                    && !p.result.isCompletedExceptionally()
                    && p.result.getNow(null) == index) {
                indexes.remove(key);
                used -= index.entries;
            }
        }
    }

    private static int lower(long[] pairs, long key) {
        int lo = 0, hi = pairs.length;
        while (lo < hi) {
            int m = lo + (hi - lo) / 2;
            if (Long.compareUnsigned(pairs[m], key) < 0) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    private static final class Pairs {
        long[] values = new long[1024];
        int count;

        void add(long value) {
            if (count == values.length) {
                values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            }
            values[count++] = value;
        }

        long[] sorted() {
            long[] out = Arrays.copyOf(values, count);
            for (int i = 0; i < out.length; i++) {
                out[i] ^= Long.MIN_VALUE;
            }
            Arrays.sort(out);
            for (int i = 0; i < out.length; i++) {
                out[i] ^= Long.MIN_VALUE;
            }
            return out;
        }
    }

    @SuppressWarnings("try")
    private static <T> T await(CompletableFuture<T> future, Cancellation token) {
        CompletableFuture<Void> canceled = new CompletableFuture<>();
        try (var registration =
                token.onCancel(() -> canceled.completeExceptionally(new CancellationException()))) {
            CompletableFuture.anyOf(future, canceled).join();
            return future.join();
        } catch (CompletionException e) {
            throw propagate(unwrap(e));
        }
    }

    private static final java.util.concurrent.Semaphore ADMISSION =
            new java.util.concurrent.Semaphore(1024);

    private static <T> CompletableFuture<T> async(java.util.function.Supplier<T> work) {
        if (!ADMISSION.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.RejectedExecutionException(
                            "Concept relation operation limit"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    T value = null;
                    Throwable error = null;
                    try {
                        value = work.get();
                    } catch (Throwable e) {
                        error = unwrap(e);
                    } finally {
                        ADMISSION.release();
                    }
                    if (error == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
        return result;
    }

    private static Throwable unwrap(Throwable e) {
        while (e instanceof CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }

    private static RuntimeException propagate(Throwable e) {
        if (e instanceof RuntimeException r) {
            return r;
        }
        if (e instanceof Error r) {
            throw r;
        }
        return new CompletionException(e);
    }
}
