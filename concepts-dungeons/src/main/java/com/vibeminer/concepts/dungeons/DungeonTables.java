package com.vibeminer.concepts.dungeons;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded verified table facts for build-local projections; no database or asset access. */
final class DungeonTables {
    private final BuildTables tables;

    DungeonTables(BuildTables tables) {
        this.tables = Objects.requireNonNull(tables);
    }

    public record Data(
            String name,
            TableTypes.Entry entry,
            List<Db2Types.Column> columns,
            TableSchema schema,
            List<Db2Types.Row> rows) {
        public Data {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }

        public boolean has(String name) {
            return schema.columns().stream().anyMatch(c -> c.name().equals(name));
        }

        public TableSchema.SchemaColumn field(String name, boolean integer, int array) {
            var fields = schema.columns().stream().filter(c -> c.name().equals(name)).toList();
            if (fields.size() != 1) {
                throw new IllegalArgumentException("Missing/ambiguous " + this.name + "." + name);
            }
            var f = fields.getFirst();
            if (!f.verifiedName()
                    || f.arrayLength() != array
                    || integer != (f.kind() == TableSchema.ColumnKind.INT)
                    || integer && (f.bits() == null || !List.of(8, 16, 32, 64).contains(f.bits()))
                    || !integer
                            && f.kind() != TableSchema.ColumnKind.STRING
                            && f.kind() != TableSchema.ColumnKind.LOCSTRING) {
                throw new IllegalArgumentException("Unsupported " + this.name + "." + name);
            }
            for (int i = 0; i < array; i++) {
                var column = columns.get(index(array == 1 ? name : name + "[" + i + "]"));
                if (!column.kind().equals(integer ? "integer" : "string")
                        || column.interpretation() != null) {
                    throw new IllegalArgumentException(
                            "Unverified physical column " + this.name + "." + name);
                }
            }
            return f;
        }

        public void reference(String field, String target) {
            var f = field(field, true, 1).foreignKey();
            if (f == null || !f.table().equals(target) || !f.column().equals("ID")) {
                throw new IllegalArgumentException("Unverified relationship " + name + "." + field);
            }
        }

        private int index(String field) {
            int at = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(field)) {
                    if (at != -1) {
                        throw new IllegalArgumentException("Ambiguous column " + field);
                    }
                    at = i;
                }
            }
            if (at < 0) {
                throw new IllegalArgumentException("Missing column " + field);
            }
            return at;
        }

        private String value(Db2Types.Row row, String field) {
            int at = index(field);
            if (row.values().size() != columns.size()
                    || row.textIssues() != null
                            && row.textIssues().stream().anyMatch(i -> i.columnIndex() == at)) {
                throw new IllegalArgumentException("Unreadable " + name + "." + field);
            }
            String text = row.values().get(at);
            if (text.length() > 32768) {
                throw new IllegalArgumentException("Text limit");
            }
            return text;
        }

        public String text(Db2Types.Row row, String field) {
            field(field, false, 1);
            return value(row, field);
        }

        public long number(Db2Types.Row row, String field) {
            field(field, true, 1);
            return numberValue(row, field);
        }

        public long element(Db2Types.Row row, String field, int ordinal, int length) {
            field(field, true, length);
            if (ordinal < 0 || ordinal >= length) {
                throw new IllegalArgumentException("Array index");
            }
            return numberValue(row, field + "[" + ordinal + "]");
        }

        private long numberValue(Db2Types.Row row, String field) {
            String raw = value(row, field);
            long n = Long.parseLong(raw);
            if (!Long.toString(n).equals(raw)) {
                throw new IllegalArgumentException("Noncanonical integer");
            }
            return n;
        }
    }

    public Data schema(String name, Cancellation token, List<String> issues) {
        var result =
                tables.schema(TableTypes.Ref.forName(name), TableTypes.Priority.INTERACTIVE, token)
                        .join();
        if (result.table() == null
                || result.columns() == null
                || result.schema() == null
                || result.table().state() != TableTypes.State.READY
                        && result.table().state() != TableTypes.State.PARTIAL) {
            throw new IllegalArgumentException(name + " is unavailable");
        }
        if (result.table().state() == TableTypes.State.PARTIAL) {
            note(issues, name + " is partial; readable rows are retained.");
        }
        return new Data(name, result.table(), result.columns(), result.schema(), List.of());
    }

    public Data read(String name, int maximum, Cancellation token, List<String> issues) {
        var data = schema(name, token, issues);
        var rows = new ArrayList<Db2Types.Row>();
        long after = -1;
        String identity = null;
        while (true) {
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.forName(name),
                                            after,
                                            256,
                                            List.of(),
                                            identity),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (page.state() != TableTypes.PageState.OK) {
                throw new IllegalArgumentException(name + " page " + page.state());
            }
            if (identity != null && !identity.equals(page.identity())) {
                throw new IllegalArgumentException(name + " changed during projection");
            }
            identity = page.identity();
            for (var row : page.rows()) {
                if (rows.size() >= maximum) {
                    throw new IllegalArgumentException(name + " exceeds projection bound");
                }
                rows.add(row.row());
            }
            if (page.nextAfter() == null) {
                break;
            }
            if (page.nextAfter() <= after) {
                throw new IllegalArgumentException(name + " cursor did not advance");
            }
            after = page.nextAfter();
        }
        return new Data(name, data.entry(), data.columns(), data.schema(), rows);
    }

    public Db2Types.Row get(Data data, long id, Cancellation token, List<String> issues) {
        var result =
                tables.get(
                                TableTypes.Ref.forName(data.name()),
                                id,
                                TableTypes.Priority.INTERACTIVE,
                                token)
                        .join();
        if (result.outcome() != TableTypes.RowOutcome.FOUND || result.row() == null) {
            note(issues, data.name() + " #" + id + " is " + result.outcome());
            return null;
        }
        return result.row().row();
    }

    public static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }
}
