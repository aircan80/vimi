package com.vibeminer.concepts.professions;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact reviewed layouts; unsupported optional relationships do not discard core facts. */
final class ProfessionTable {
    private static final Map<String, String> LAYOUTS =
            Map.ofEntries(
                    Map.entry("SkillLine", "6763217C"),
                    Map.entry("SkillLineCategory", "67B4FE22"),
                    Map.entry("SkillLineAbility", "224F7EA0"),
                    Map.entry("TradeSkillCategory", "7F2B6CD0"),
                    Map.entry("SpellReagents", "FF40D202"),
                    Map.entry("SpellTotems", "03B23619"),
                    Map.entry("SpellEffect", "5362E3D4"),
                    Map.entry("SpellName", "782EE721"),
                    Map.entry("SpellMisc", "434B3607"));
    final String name;
    final TableTypes.Entry entry;
    final TableSchema schema;
    final List<Db2Types.Column> columns;

    private ProfessionTable(String name, TableTypes.SchemaResult result) {
        this.name = name;
        entry = result.table();
        schema = result.schema();
        columns = result.columns();
    }

    static ProfessionTable open(
            BuildTables tables, String name, Cancellation token, List<String> issues) {
        token.check();
        var result =
                tables.schema(TableTypes.Ref.forName(name), TableTypes.Priority.INTERACTIVE, token)
                        .join();
        if ((result.table().state() != TableTypes.State.READY
                        && result.table().state() != TableTypes.State.PARTIAL)
                || result.schema() == null
                || result.columns() == null) {
            throw new Projection(
                    ProfessionTypes.State.UNAVAILABLE, name + ": " + result.table().state());
        }
        String expected = LAYOUTS.get(name);
        if (expected == null
                || result.table().counts() == null
                || !expected.equals(result.table().counts().layoutHash())
                || !result.schema().layoutHashes().contains(expected)) {
            throw new Projection(
                    ProfessionTypes.State.UNSUPPORTED, name + ": layout is not reviewed");
        }
        if (result.table().state() == TableTypes.State.PARTIAL) {
            issue(issues, name + " is partial; readable rows are retained.");
        }
        return new ProfessionTable(name, result);
    }

    List<Db2Types.Row> rows(
            BuildTables tables, String filter, long value, int bound, Cancellation token) {
        var result = new ArrayList<Db2Types.Row>();
        long after = -1;
        String identity = null;
        var filters =
                filter == null
                        ? List.<TableTypes.Filter>of()
                        : List.of(
                                new TableTypes.Filter(
                                        filter,
                                        TableTypes.FilterOp.EQUALS,
                                        Long.toString(value),
                                        null));
        if (filter != null) {
            integerField(filter);
        }
        while (true) {
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            TableTypes.Ref.forName(name),
                                            after,
                                            256,
                                            filters,
                                            identity),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            if (page.state() != TableTypes.PageState.OK) {
                throw new Projection(ProfessionTypes.State.UNAVAILABLE, name + ": " + page.state());
            }
            if (identity != null && !Objects.equals(identity, page.identity())) {
                throw new Projection(
                        ProfessionTypes.State.UNAVAILABLE, name + " changed during projection");
            }
            identity = page.identity();
            for (var row : page.rows()) {
                if (result.size() >= bound) {
                    throw new Projection(
                            ProfessionTypes.State.UNSUPPORTED, name + " exceeds projection bounds");
                }
                result.add(row.row());
            }
            if (page.nextAfter() == null) {
                break;
            }
            if (page.nextAfter() <= after) {
                throw new Projection(
                        ProfessionTypes.State.UNAVAILABLE, name + " cursor did not advance");
            }
            after = page.nextAfter();
        }
        return List.copyOf(result);
    }

    Db2Types.Row get(BuildTables tables, long id, Cancellation token, List<String> issues) {
        token.check();
        var found =
                tables.get(TableTypes.Ref.forName(name), id, TableTypes.Priority.INTERACTIVE, token)
                        .join();
        if (found.outcome() == TableTypes.RowOutcome.FOUND && found.row() != null) {
            return found.row().row();
        }
        issue(issues, name + " #" + id + ": " + found.outcome());
        return null;
    }

    private TableSchema.SchemaColumn field(String field) {
        String base = field.contains("[") ? field.substring(0, field.indexOf('[')) : field;
        var matches = schema.columns().stream().filter(c -> c.name().equals(base)).toList();
        if (matches.size() != 1) {
            throw invalid("Missing/ambiguous field " + field);
        }
        var result = matches.getFirst();
        if (field.contains("[")) {
            int slot;
            try {
                slot =
                        Integer.parseInt(
                                field.substring(field.indexOf('[') + 1, field.length() - 1));
            } catch (RuntimeException failure) {
                throw invalid("Invalid array field " + field);
            }
            if (slot < 0 || slot >= result.arrayLength()) {
                throw invalid("Invalid array field " + field);
            }
        } else if (result.arrayLength() != 1) {
            throw invalid("Expected scalar " + field);
        }
        return result;
    }

    private void integerField(String field) {
        var schemaField = field(field);
        if (schemaField.kind() != TableSchema.ColumnKind.INT
                || schemaField.bits() == null
                || !List.of(8, 16, 32, 64).contains(schemaField.bits())
                || !columns.get(index(field)).kind().equals("integer")) {
            throw invalid("Unsupported integer " + field);
        }
    }

    void reference(String field, String target) {
        integerField(field);
        var foreign = field(field).foreignKey();
        if (foreign == null || !target.equals(foreign.table()) || !"ID".equals(foreign.column())) {
            throw invalid("Unverified reference " + field);
        }
    }

    private int index(String field) {
        int result = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(field)) {
                if (result >= 0) {
                    throw invalid("Ambiguous column " + field);
                }
                result = i;
            }
        }
        if (result < 0 || columns.get(result).interpretation() != null) {
            throw invalid("Missing/unsupported column " + field);
        }
        return result;
    }

    String raw(Db2Types.Row row, String field) {
        int at = index(field);
        if (row.values().size() != columns.size()
                || row.textIssues() != null
                        && row.textIssues().stream().anyMatch(i -> i.columnIndex() == at)) {
            throw invalid("Unreadable cell " + field);
        }
        String value = row.values().get(at);
        if (value == null || value.length() > 32768) {
            throw invalid("Invalid cell " + field);
        }
        return value;
    }

    String text(Db2Types.Row row, String field) {
        var kind = field(field).kind();
        if ((kind != TableSchema.ColumnKind.LOCSTRING && kind != TableSchema.ColumnKind.STRING)
                || !columns.get(index(field)).kind().equals("string")) {
            throw invalid("Unsupported text " + field);
        }
        return raw(row, field);
    }

    Long number(Db2Types.Row row, String field) {
        integerField(field);
        try {
            String raw = raw(row, field);
            long value = Long.parseLong(raw);
            if (!Long.toString(value).equals(raw)) {
                throw invalid("Noncanonical integer " + field);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw invalid("Invalid integer " + field);
        }
    }

    BigDecimal decimal(Db2Types.Row row, String field) {
        if (field(field).kind() != TableSchema.ColumnKind.FLOAT
                || !columns.get(index(field)).kind().equals("float")) {
            throw invalid("Unsupported float " + field);
        }
        try {
            return new BigDecimal(raw(row, field));
        } catch (NumberFormatException invalid) {
            throw invalid("Invalid float " + field);
        }
    }

    ProfessionTypes.Source source(Db2Types.Row row, String field) {
        return new ProfessionTypes.Source(name, row.id(), field, raw(row, field));
    }

    Projection invalid(String message) {
        return new Projection(ProfessionTypes.State.UNSUPPORTED, name + ": " + message);
    }

    static void issue(List<String> issues, String message) {
        if (issues.size() < 32 && !issues.contains(message)) {
            issues.add(message);
        }
    }

    static final class Projection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final ProfessionTypes.State state;

        Projection(ProfessionTypes.State state, String message) {
            super(message);
            this.state = state;
        }
    }
}
