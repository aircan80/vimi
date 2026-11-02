package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shared bounded foreign-key and referenced-by semantics. No raw evidence is used as a semantic
 * key.
 */
public final class TableRelations {
    private TableRelations() {}

    public record RelationTable(
            TableTypes.Entry entry,
            List<Db2Types.Column> columns,
            TableSchema schema,
            long rowCount,
            TableQueries.RowReader read) {}

    public enum SchemaState {
        RESOLVED,
        NO_DEFINITION,
        UNRESOLVED
    }

    public record RelationSchema(SchemaState state, TableSchema schema) {}

    public interface Source {
        CompletableFuture<RelationTable> open(TableTypes.Ref ref, Cancellation token);

        CompletableFuture<TableTypes.RowResult> get(
                TableTypes.Ref ref, long id, Cancellation token);

        CompletableFuture<List<TableTypes.Entry>> list(Cancellation token);

        CompletableFuture<RelationSchema> schema(TableTypes.Entry table, Cancellation token);
    }

    private static TableTypes.FollowResult answer(
            TableTypes.FollowOutcome outcome,
            TableTypes.RowResult row,
            TableSchema.ForeignKey reference,
            TableTypes.Entry target,
            String code,
            String key) {
        return new TableTypes.FollowResult(
                outcome, row, reference, target, List.of(), null, code, null, key);
    }

    public static CompletableFuture<TableTypes.FollowResult> follow(
            Source source, TableTypes.FollowRequest request, int maxScanRows, Cancellation token) {
        TableQueries.validate(request);
        if (maxScanRows < 1) {
            throw new IllegalArgumentException("Positive scan limit required");
        }
        return async(
                () -> {
                    var row = source.get(request.table(), request.id(), token).join();
                    var from = source.open(request.table(), token).join();
                    boolean found = row.outcome() == TableTypes.RowOutcome.FOUND;
                    if (from.schema() == null) {
                        return answer(
                                found
                                        ? TableTypes.FollowOutcome.NOT_A_REFERENCE
                                        : TableTypes.FollowOutcome.SOURCE_ROW,
                                row,
                                null,
                                null,
                                null,
                                null);
                    }
                    var own =
                            from.schema().columns().stream()
                                    .filter(column -> declaresElement(column, request.column()))
                                    .findFirst()
                                    .orElse(null);
                    var reference = own == null ? null : own.foreignKey();
                    if (reference == null) {
                        return answer(
                                TableTypes.FollowOutcome.NOT_A_REFERENCE,
                                row,
                                null,
                                null,
                                null,
                                null);
                    }
                    int index = -1;
                    if (from.columns() != null) {
                        for (int i = 0; i < from.columns().size(); i++) {
                            if (from.columns().get(i).name().equals(request.column())) {
                                index = i;
                            }
                        }
                    }
                    if (from.columns() != null && index < 0) {
                        return answer(
                                TableTypes.FollowOutcome.NOT_A_REFERENCE,
                                row,
                                reference,
                                null,
                                "TABLE_RELATION_COLUMN",
                                null);
                    }
                    if (index >= 0 && from.columns().get(index).interpretation() != null) {
                        return answer(
                                TableTypes.FollowOutcome.NOT_A_REFERENCE,
                                row,
                                reference,
                                null,
                                "TABLE_RELATION_RAW",
                                null);
                    }
                    if (!found) {
                        return answer(
                                TableTypes.FollowOutcome.SOURCE_ROW,
                                row,
                                reference,
                                null,
                                null,
                                null);
                    }
                    var targetRef = TableTypes.Ref.forName(reference.table());
                    var to = source.open(targetRef, token).join();
                    var target =
                            to.schema() == null
                                    ? null
                                    : to.schema().columns().stream()
                                            .filter(
                                                    c ->
                                                            c.name().equals(reference.column())
                                                                    && c.kind()
                                                                            == TableSchema
                                                                                    .ColumnKind.INT)
                                            .findFirst()
                                            .orElse(null);
                    if (target == null) {
                        return answer(
                                TableTypes.FollowOutcome.UNKNOWN,
                                row,
                                reference,
                                to.entry(),
                                to.schema() == null ? null : "TABLE_RELATION_TARGET_COLUMN",
                                null);
                    }
                    BigInteger value = cell(row.row().row(), index);
                    if (value == null) {
                        return answer(
                                TableTypes.FollowOutcome.UNKNOWN,
                                row,
                                reference,
                                to.entry(),
                                "TABLE_RELATION_VALUE",
                                null);
                    }
                    boolean isId =
                            target.id() != TableSchema.KeyStorage.NONE
                                    || to.columns() != null
                                            && to.columns().stream()
                                                    .anyMatch(
                                                            column ->
                                                                    column.name()
                                                                                    .equals(
                                                                                            reference
                                                                                                    .column())
                                                                            && column.isId());
                    Db2Types.Column header =
                            to.columns() == null
                                    ? null
                                    : to.columns().stream()
                                            .filter(c -> c.name().equals(reference.column()))
                                            .findFirst()
                                            .orElse(null);
                    var domain =
                            header != null
                                    ? TableQueries.domain(to.schema(), header)
                                    : isId
                                            ? new BigInteger[] {
                                                BigInteger.ZERO, BigInteger.valueOf(0xffffffffL)
                                            }
                                            : TableQueries.domain(target);
                    if (value.compareTo(domain[0]) < 0 || value.compareTo(domain[1]) > 0) {
                        return answer(
                                TableTypes.FollowOutcome.KEY_OUT_OF_RANGE,
                                row,
                                reference,
                                to.entry(),
                                null,
                                null);
                    }
                    if (isId) {
                        var hit = source.get(targetRef, value.longValueExact(), token).join();
                        if (hit.outcome() == TableTypes.RowOutcome.FOUND) {
                            return new TableTypes.FollowResult(
                                    TableTypes.FollowOutcome.FOUND,
                                    row,
                                    reference,
                                    hit.table(),
                                    hit.row().ordinal() > request.after()
                                            ? List.of(hit.row())
                                            : List.of(),
                                    null,
                                    null,
                                    null,
                                    null);
                        }
                        return answer(
                                hit.outcome() == TableTypes.RowOutcome.ABSENT
                                        ? TableTypes.FollowOutcome.ABSENT
                                        : TableTypes.FollowOutcome.UNKNOWN,
                                row,
                                reference,
                                hit.table(),
                                hit.outcome() == TableTypes.RowOutcome.PROTECTED
                                        ? "TABLE_RELATION_PROTECTED"
                                        : null,
                                hit.protectedKey());
                    }
                    if (!TableQueries.isReadable(to.entry().state())
                            || to.read() == null
                            || to.columns() == null) {
                        return answer(
                                TableTypes.FollowOutcome.UNKNOWN,
                                row,
                                reference,
                                to.entry(),
                                null,
                                null);
                    }
                    int[] indexes = TableQueries.resolve(to.columns(), reference.column());
                    if (indexes.length == 0) {
                        return answer(
                                TableTypes.FollowOutcome.UNKNOWN,
                                row,
                                reference,
                                to.entry(),
                                "TABLE_RELATION_TARGET_COLUMN",
                                null);
                    }
                    for (int i : indexes) {
                        if (to.columns().get(i).interpretation() != null) {
                            return answer(
                                    TableTypes.FollowOutcome.NOT_A_REFERENCE,
                                    row,
                                    reference,
                                    to.entry(),
                                    "TABLE_RELATION_RAW",
                                    null);
                        }
                    }
                    if (to.rowCount() > maxScanRows) {
                        return answer(
                                TableTypes.FollowOutcome.UNINDEXED,
                                row,
                                reference,
                                to.entry(),
                                "TABLE_RELATION_UNINDEXED",
                                null);
                    }
                    var rows = new ArrayList<TableTypes.Row>();
                    boolean earlier = false;
                    for (long first = 0;
                            first < to.rowCount() && rows.size() <= request.limit(); ) {
                        token.check();
                        var batch =
                                to.read()
                                        .read(
                                                first,
                                                (int) Math.min(1024, to.rowCount() - first),
                                                token)
                                        .join();
                        if (batch.isEmpty()) {
                            throw new IllegalStateException("Short relation source");
                        }
                        for (int n = 0; n < batch.size(); n++) {
                            long ordinal = first + n;
                            if (!anyEquals(batch.get(n), indexes, value)) {
                                continue;
                            }
                            if (ordinal <= request.after()) {
                                earlier = true;
                                continue;
                            }
                            rows.add(new TableTypes.Row(ordinal, batch.get(n)));
                            if (rows.size() > request.limit()) {
                                break;
                            }
                        }
                        first += batch.size();
                    }
                    if (rows.isEmpty() && !earlier) {
                        return answer(
                                to.entry().state() == TableTypes.State.READY
                                        ? TableTypes.FollowOutcome.ABSENT
                                        : TableTypes.FollowOutcome.UNKNOWN,
                                row,
                                reference,
                                to.entry(),
                                null,
                                null);
                    }
                    boolean more = rows.size() > request.limit();
                    if (more) {
                        rows.removeLast();
                    }
                    return new TableTypes.FollowResult(
                            TableTypes.FollowOutcome.FOUND,
                            row,
                            reference,
                            to.entry(),
                            rows,
                            more ? rows.getLast().ordinal() : null,
                            null,
                            null,
                            null);
                });
    }

    private static boolean declaresElement(TableSchema.SchemaColumn column, String flattened) {
        if (!column.isArray()) {
            return column.name().equals(flattened);
        }
        String prefix = column.name() + "[";
        if (!flattened.startsWith(prefix) || !flattened.endsWith("]")) {
            return false;
        }
        String digits = flattened.substring(prefix.length(), flattened.length() - 1);
        if (!digits.matches("0|[1-9][0-9]*")) {
            return false;
        }
        try {
            return Integer.parseInt(digits) < column.arrayLength();
        } catch (NumberFormatException error) {
            return false;
        }
    }

    static BigInteger cell(Db2Types.Row row, int index) {
        if (index < 0
                || index >= row.values().size()
                || row.textIssues() != null
                        && row.textIssues().stream().anyMatch(i -> i.columnIndex() == index)) {
            return null;
        }
        return TableQueries.parseInteger(row.values().get(index));
    }

    static boolean anyEquals(Db2Types.Row row, int[] indexes, BigInteger wanted) {
        for (int i : indexes) {
            if (wanted.equals(cell(row, i))) {
                return true;
            }
        }
        return false;
    }

    public static CompletableFuture<TableTypes.ReferencesResult> referencedBy(
            Source source,
            TableTypes.ReferencesRequest request,
            int maxScanRows,
            int maxSources,
            long maxTotalScanRows,
            Cancellation token) {
        TableQueries.validate(request);
        if (maxScanRows < 1 || maxSources < 0 || maxTotalScanRows < 0) {
            throw new IllegalArgumentException("Invalid relation bounds");
        }
        return async(
                () -> {
                    var row = source.get(request.table(), request.id(), token).join();
                    if (row.outcome() != TableTypes.RowOutcome.FOUND) {
                        return new TableTypes.ReferencesResult(
                                TableTypes.ReferencesOutcome.SOURCE_ROW, row, List.of(), List.of());
                    }
                    var self = source.open(request.table(), token).join();
                    if (row.table().name() == null) {
                        return new TableTypes.ReferencesResult(
                                TableTypes.ReferencesOutcome.SEARCHED, row, List.of(), List.of());
                    }
                    var sources = new ArrayList<TableTypes.ReferenceSource>();
                    var unresolved = new ArrayList<TableTypes.Entry>();
                    int candidates = 0;
                    long scanned = 0;
                    for (var listed :
                            source.list(token).join().stream()
                                    .sorted(
                                            java.util.Comparator.comparingLong(
                                                    TableTypes.Entry::fileDataId))
                                    .toList()) {
                        token.check();
                        if (listed.state() == TableTypes.State.NO_DEFINITION
                                || listed.state() == TableTypes.State.NOT_IN_BUILD
                                || listed.state() == TableTypes.State.NOT_IN_LOCALE) {
                            continue;
                        }
                        var schema = source.schema(listed, token).join();
                        if (schema.state() == SchemaState.NO_DEFINITION) {
                            continue;
                        }
                        if (schema.state() != SchemaState.RESOLVED) {
                            unresolved.add(listed);
                            continue;
                        }
                        for (var column : schema.schema().columns()) {
                            var fk = column.foreignKey();
                            if (fk == null
                                    || !TableQueries.namesEqual(fk.table(), row.table().name())) {
                                continue;
                            }
                            String code = null;
                            RelationTable table = null;
                            List<Long> ids = new ArrayList<>();
                            boolean truncated = false;
                            TableTypes.ReferenceScope scope = TableTypes.ReferenceScope.NOT_CHECKED;
                            if (++candidates > maxSources) {
                                code = "TABLE_RELATION_SOURCE_LIMIT";
                            } else {
                                table =
                                        source.open(TableTypes.Ref.id(listed.fileDataId()), token)
                                                .join();
                                if (TableQueries.isReadable(table.entry().state())
                                        && table.read() != null
                                        && table.columns() != null) {
                                    int[] indexes =
                                            TableQueries.resolve(table.columns(), column.name());
                                    if (indexes.length == 0) {
                                        code = "TABLE_RELATION_COLUMN";
                                    }
                                    for (int i : indexes) {
                                        if (table.columns().get(i).interpretation() != null) {
                                            code = "TABLE_RELATION_RAW";
                                        }
                                    }
                                    BigInteger wanted = null;
                                    int target = -1;
                                    if (self.columns() != null) {
                                        for (int i = 0; i < self.columns().size(); i++) {
                                            if (self.columns().get(i).name().equals(fk.column())) {
                                                target = i;
                                            }
                                        }
                                    }
                                    if (target < 0) {
                                        code = "TABLE_RELATION_TARGET_COLUMN";
                                    } else if (self.columns().get(target).interpretation()
                                            != null) {
                                        code = "TABLE_RELATION_RAW";
                                    } else {
                                        wanted = cell(row.row().row(), target);
                                        if (wanted == null) {
                                            code = "TABLE_RELATION_VALUE";
                                        }
                                    }
                                    if (code == null) {
                                        var domain =
                                                TableQueries.domain(
                                                        table.schema(),
                                                        table.columns().get(indexes[0]));
                                        scope =
                                                table.entry().state() == TableTypes.State.READY
                                                        ? TableTypes.ReferenceScope.COMPLETE
                                                        : TableTypes.ReferenceScope.PARTIAL;
                                        if (wanted.compareTo(domain[0]) >= 0
                                                && wanted.compareTo(domain[1]) <= 0) {
                                            if (table.rowCount() > maxScanRows) {
                                                code = "TABLE_RELATION_UNINDEXED";
                                            } else if (table.rowCount()
                                                    > maxTotalScanRows - scanned) {
                                                code = "TABLE_RELATION_BUDGET";
                                            } else {
                                                scanned += table.rowCount();
                                                for (long first = 0;
                                                        first < table.rowCount() && !truncated; ) {
                                                    token.check();
                                                    var batch =
                                                            table.read()
                                                                    .read(
                                                                            first,
                                                                            (int)
                                                                                    Math.min(
                                                                                            1024,
                                                                                            table
                                                                                                            .rowCount()
                                                                                                    - first),
                                                                            token)
                                                                    .join();
                                                    if (batch.isEmpty()) {
                                                        throw new IllegalStateException(
                                                                "Short relation source");
                                                    }
                                                    for (var match : batch) {
                                                        if (anyEquals(match, indexes, wanted)) {
                                                            if (ids.size()
                                                                    == request.limitPerSource()) {
                                                                truncated = true;
                                                                break;
                                                            }
                                                            ids.add(match.id());
                                                        }
                                                    }
                                                    first += batch.size();
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (code != null) {
                                scope = TableTypes.ReferenceScope.NOT_CHECKED;
                            }
                            sources.add(
                                    new TableTypes.ReferenceSource(
                                            table == null ? listed : table.entry(),
                                            column.name(),
                                            fk,
                                            scope,
                                            ids,
                                            truncated,
                                            code));
                        }
                    }
                    boolean none =
                            unresolved.isEmpty()
                                    && sources.stream()
                                            .allMatch(
                                                    s ->
                                                            s.scope()
                                                                            == TableTypes
                                                                                    .ReferenceScope
                                                                                    .COMPLETE
                                                                    && s.rowIds().isEmpty());
                    return new TableTypes.ReferencesResult(
                            none
                                    ? TableTypes.ReferencesOutcome.NO_REFERENCES
                                    : TableTypes.ReferencesOutcome.SEARCHED,
                            row,
                            sources,
                            unresolved);
                });
    }

    private static <T> CompletableFuture<T> async(java.util.function.Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, command -> Thread.ofVirtual().start(command));
    }
}
