package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Shared exact numeric/filter/cursor rules for real and consumer table implementations. */
public final class TableQueries {
    public static final int MAX_LIMIT = 1000,
            DEFAULT_LIMIT = 50,
            MAX_ROWS_PER_SOURCE = 1000,
            MAX_INTEGER_DIGITS = 20,
            MAX_FILTERS = 8,
            DEFAULT_MAX_SCAN_ROWS = 200000,
            DEFAULT_MAX_REFERENCE_SOURCES = 256;
    public static final long DEFAULT_MAX_REFERENCE_SCAN_ROWS = 1000000;

    private TableQueries() {}

    @FunctionalInterface
    public interface RowReader {
        CompletableFuture<List<Db2Types.Row>> read(long first, int count, Cancellation token);
    }

    public record FilterResult(Predicate<Db2Types.Row> matches, String code, String message) {
        public boolean isBad() {
            return matches == null;
        }
    }

    public static boolean isReadable(TableTypes.State state) {
        return state == TableTypes.State.READY || state == TableTypes.State.PARTIAL;
    }

    public static String asciiLower(String value) {
        var b = new StringBuilder(value);
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                b.setCharAt(i, (char) (c + 32));
            }
        }
        return b.toString();
    }

    public static boolean namesEqual(String a, String b) {
        return asciiLower(a).equals(asciiLower(b));
    }

    public static boolean matches(TableTypes.ListFilter filter, TableTypes.Entry entry) {
        return (filter.states() == null || filter.states().contains(entry.state()))
                && (filter.name() == null
                        || entry.name() != null
                                && asciiLower(entry.name()).contains(asciiLower(filter.name())));
    }

    public static BigInteger parseInteger(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,19}|-[1-9][0-9]{0,19})")) {
            return null;
        }
        return new BigInteger(value);
    }

    public static void validate(TableTypes.PageRequest request) {
        java.util.Objects.requireNonNull(request.table());
        cursor(request.after(), request.limit());
        if (request.filters().size() > 8) {
            throw new IllegalArgumentException("At most8 filters");
        }
        for (var filter : request.filters()) {
            if (filter.column() == null
                    || filter.column().isEmpty()
                    || filter.column().length() > 128
                    || filter.value() == null
                    || filter.value().length() > 256
                    || filter.upper() != null && filter.upper().length() > 256
                    || filter.op() == null
                    || (filter.op() == TableTypes.FilterOp.BETWEEN) != (filter.upper() != null)) {
                throw new IllegalArgumentException("Malformed table filter");
            }
        }
    }

    public static void validate(TableTypes.FollowRequest request) {
        java.util.Objects.requireNonNull(request.table());
        if (request.column() == null
                || request.column().isEmpty()
                || request.id() < 0
                || request.id() > 0xffffffffL) {
            throw new IllegalArgumentException("Malformed follow");
        }
        cursor(request.after(), request.limit());
    }

    public static void validate(TableTypes.ReferencesRequest request) {
        java.util.Objects.requireNonNull(request.table());
        if (request.id() < 0
                || request.id() > 0xffffffffL
                || request.limitPerSource() < 1
                || request.limitPerSource() > 1000) {
            throw new IllegalArgumentException("Malformed references request");
        }
    }

    private static void cursor(long after, int limit) {
        if (after < -1 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid cursor or limit");
        }
    }

    public static String baseName(String name) {
        return name.replaceFirst("\\[[0-9]+\\]$", "");
    }

    public static int[] resolve(List<Db2Types.Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return new int[] {i};
            }
        }
        return java.util.stream.IntStream.range(0, columns.size())
                .filter(
                        i ->
                                baseName(columns.get(i).name()).equals(name)
                                        && !columns.get(i).name().equals(name))
                .toArray();
    }

    public static BigInteger[] domain(TableSchema schema, Db2Types.Column header) {
        if (header.isId()) {
            return new BigInteger[] {BigInteger.ZERO, BigInteger.valueOf(0xffffffffL)};
        }
        var column =
                schema == null
                        ? null
                        : schema.columns().stream()
                                .filter(
                                        c ->
                                                c.name().equals(baseName(header.name()))
                                                        && c.kind() == TableSchema.ColumnKind.INT)
                                .findFirst()
                                .orElse(null);
        return domain(column);
    }

    public static BigInteger[] domain(TableSchema.SchemaColumn column) {
        if (column == null || column.bits() == null || column.unsigned() == null) {
            return new BigInteger[] {
                BigInteger.valueOf(Long.MIN_VALUE),
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
            };
        }
        int bits = column.bits();
        return column.unsigned()
                ? new BigInteger[] {
                    BigInteger.ZERO, BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                }
                : new BigInteger[] {
                    BigInteger.ONE.shiftLeft(bits - 1).negate(),
                    BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                };
    }

    public static FilterResult compileFilters(
            List<Db2Types.Column> columns, TableSchema schema, List<TableTypes.Filter> filters) {
        var tests = new ArrayList<Predicate<Db2Types.Row>>();
        for (var filter : filters) {
            int[] indexes = resolve(columns, filter.column());
            if (indexes.length == 0) {
                return bad("TABLE_FILTER_COLUMN");
            }
            for (int i : indexes) {
                if (columns.get(i).interpretation() != null) {
                    return bad("TABLE_FILTER_RAW");
                }
            }
            String kind = columns.get(indexes[0]).kind();
            for (int i : indexes) {
                if (!columns.get(i).kind().equals(kind)) {
                    return bad("TABLE_FILTER_OP");
                }
            }
            Predicate<String> cell;
            if (kind.equals("integer")) {
                var low = parseInteger(filter.value());
                var high =
                        filter.op() == TableTypes.FilterOp.BETWEEN
                                ? parseInteger(filter.upper())
                                : low;
                if (low == null || high == null || low.compareTo(high) > 0) {
                    return bad("TABLE_FILTER_VALUE");
                }
                var d = domain(schema, columns.get(indexes[0]));
                final var from = low.max(d[0]);
                final var to = high.min(d[1]);
                cell =
                        value -> {
                            var n = parseInteger(value);
                            return n != null && n.compareTo(from) >= 0 && n.compareTo(to) <= 0;
                        };
            } else if (kind.equals("float")) {
                if (filter.op() != TableTypes.FilterOp.BETWEEN) {
                    return bad("TABLE_FILTER_OP");
                }
                Float low = parseFloat(filter.value()), high = parseFloat(filter.upper());
                if (low == null
                        || high == null
                        || !Float.isFinite(low)
                        || !Float.isFinite(high)
                        || low > high) {
                    return bad("TABLE_FILTER_VALUE");
                }
                cell =
                        value -> {
                            var f = parseFloat(value);
                            return f != null && f >= low && f <= high;
                        };
            } else if (kind.equals("string")) {
                if (filter.op() != TableTypes.FilterOp.EQUALS) {
                    return bad("TABLE_FILTER_OP");
                }
                cell = filter.value()::equals;
            } else {
                return bad("TABLE_FILTER_OP");
            }
            tests.add(
                    row -> {
                        for (int i : indexes) {
                            if (i < row.values().size()
                                    && (row.textIssues() == null
                                            || row.textIssues().stream()
                                                    .noneMatch(issue -> issue.columnIndex() == i))
                                    && cell.test(row.values().get(i))) {
                                return true;
                            }
                        }
                        return false;
                    });
        }
        return new FilterResult(row -> tests.stream().allMatch(test -> test.test(row)), null, null);
    }

    private static FilterResult bad(String code) {
        return new FilterResult(null, code, "Filter cannot be applied");
    }

    private static Float parseFloat(String text) {
        if (text == null
                || !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            return null;
        }
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static TableTypes.Page notReadable(TableTypes.Entry table) {
        return new TableTypes.Page(
                table, TableTypes.PageState.NOT_READABLE, List.of(), null, null, 0, null, null);
    }

    public static CompletableFuture<TableTypes.Page> page(
            TableTypes.Entry table,
            String identity,
            TableTypes.PageRequest request,
            long rowCount,
            RowReader read,
            List<Db2Types.Column> columns,
            TableSchema schema,
            int maxScanRows,
            Cancellation token) {
        validate(request);
        if (maxScanRows < 1 || rowCount < 0) {
            throw new IllegalArgumentException("Invalid scan bounds");
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    token.check();
                    if (request.identity() != null && !request.identity().equals(identity)) {
                        return new TableTypes.Page(
                                table,
                                TableTypes.PageState.STALE,
                                List.of(),
                                null,
                                identity,
                                0,
                                null,
                                null);
                    }
                    var filter = compileFilters(columns, schema, request.filters());
                    if (filter.isBad()) {
                        return new TableTypes.Page(
                                table,
                                TableTypes.PageState.BAD_FILTER,
                                List.of(),
                                null,
                                identity,
                                0,
                                filter.code(),
                                filter.message());
                    }
                    if (request.after() >= rowCount) {
                        return new TableTypes.Page(
                                table,
                                TableTypes.PageState.INVALID_CURSOR,
                                List.of(),
                                null,
                                identity,
                                0,
                                null,
                                null);
                    }
                    var found = new ArrayList<TableTypes.Row>();
                    long next = request.after() + 1,
                            stop =
                                    Math.min(
                                            rowCount,
                                            next
                                                    + (request.filters().isEmpty()
                                                            ? request.limit()
                                                            : maxScanRows));
                    while (next < stop && found.size() < request.limit()) {
                        token.check();
                        int n = (int) Math.min(1024, stop - next);
                        var rows = read.read(next, n, token).join();
                        if (rows.size() != n) {
                            throw new IllegalStateException("Row source count disagrees");
                        }
                        for (int i = 0; i < n && found.size() < request.limit(); i++, next++) {
                            if (filter.matches().test(rows.get(i))) {
                                found.add(new TableTypes.Row(next, rows.get(i)));
                            }
                        }
                    }
                    return new TableTypes.Page(
                            table,
                            TableTypes.PageState.OK,
                            found,
                            next < rowCount ? next - 1 : null,
                            identity,
                            next - request.after() - 1,
                            null,
                            null);
                },
                command -> Thread.ofVirtual().start(command));
    }
}
