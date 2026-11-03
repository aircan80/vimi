package com.vibeminer.concepts.items;

import com.vibeminer.concepts.items.PreparedItemTypes.Binding;
import com.vibeminer.concepts.items.PreparedItemTypes.CellState;
import com.vibeminer.concepts.items.PreparedItemTypes.Compilation;
import com.vibeminer.concepts.items.PreparedItemTypes.FactId;
import com.vibeminer.concepts.items.PreparedItemTypes.FieldAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.IdColumn;
import com.vibeminer.concepts.items.PreparedItemTypes.IdentityAdmission;
import com.vibeminer.concepts.items.PreparedItemTypes.Omission;
import com.vibeminer.concepts.items.PreparedItemTypes.ScalarKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceProfile;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceQualifiedKey;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceSchema;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles a bounded schema into detached source-cell bindings, without retaining input metadata.
 */
final class PreparedItemCompiler {
    private PreparedItemCompiler() {}

    static Compilation compile(SourceSchema input) {
        java.util.Objects.requireNonNull(input);
        var stamp = input.stamp();
        if (input.header() == null || input.schema() == null || input.columns() == null) {
            return failure(IdentityAdmission.UNKNOWN, "ITEM_SOURCE_METADATA_UNAVAILABLE");
        }
        if (stamp.contentKey() == null || stamp.graphIdentity() == null) {
            return failure(IdentityAdmission.UNKNOWN, "ITEM_SOURCE_SNAPSHOT_UNAVAILABLE");
        }
        if (!stamp.contentKey().matches("[0-9a-f]{32}")
                || !stamp.graphIdentity().matches("[0-9a-f]{32}")) {
            return failure(IdentityAdmission.INVALID, "ITEM_SOURCE_SNAPSHOT_INVALID");
        }
        var header = input.header();
        if (stamp.fileDataId() != stamp.kind().fileDataId()
                || !stamp.tableName().equalsIgnoreCase(stamp.kind().tableName())
                || !stamp.kind().tableHash().equalsIgnoreCase(header.tableHash())
                || WowLocale.tryParse(stamp.locale()) == null
                || !GeneratedTableSource.READER_REVISION.equals(stamp.readerRevision())
                || !GeneratedTableSource.ROW_ENCODING.equals(stamp.rowEncoding())) {
            return failure(IdentityAdmission.INVALID, "ITEM_SOURCE_IDENTITY_INVALID");
        }
        if (input.columns().size() > 512
                || header.columns().size() > 512
                || !header.columns().equals(input.columns())) {
            return failure(IdentityAdmission.INVALID, "ITEM_ID_COLUMN_INVALID");
        }
        var ids = new ArrayList<IdColumn>();
        for (int c = 0; c < input.columns().size(); c++) {
            var column = input.columns().get(c);
            if (column.isId()) {
                if (column.name().length() > 128 || !column.kind().equals("integer")) {
                    return failure(IdentityAdmission.INVALID, "ITEM_ID_COLUMN_INVALID");
                }
                ids.add(new IdColumn(c, column.name()));
            }
        }
        if (ids.size() > 1) {
            return failure(IdentityAdmission.INVALID, "ITEM_ID_COLUMN_INVALID");
        }
        String identity =
                PreparedItemProjection.hash(
                        out -> {
                            PreparedItemProjection.text(out, PreparedItemProjection.ID_POLICY);
                            PreparedItemProjection.text(out, stamp.kind().name());
                            out.writeLong(stamp.fileDataId());
                            PreparedItemProjection.text(
                                    out, header.tableHash().toUpperCase(Locale.ROOT));
                            PreparedItemProjection.text(out, stamp.build().product());
                            PreparedItemProjection.text(out, stamp.locale());
                            PreparedItemProjection.text(out, stamp.readerRevision());
                            PreparedItemProjection.text(out, stamp.rowEncoding());
                        });
        var omissions = new ArrayList<Omission>();
        var bindings = new ArrayList<Binding>();
        String fieldHash = identity;
        try {
            if (!PreparedItemProjection.POLICY_REVISION.equals(input.selection().policyRevision())
                    || !input.schema().locale().code().equals(stamp.locale())
                    || stamp.layoutHash() == null
                    || !stamp.layoutHash().matches("[0-9a-fA-F]{8}")
                    || !stamp.layoutHash().equalsIgnoreCase(header.layoutHash())
                    || stamp.definitionSha256() == null
                    || !stamp.definitionSha256().matches("[0-9a-f]{64}")
                    || stamp.blockIdentity() == null
                    || stamp.blockIdentity().isBlank()
                    || stamp.readerOptions() == null
                    || stamp.readerOptions().isBlank()
                    || input.schema().columns().size() > 512
                    || !List.of("WDC3", "WDC4", "WDC5").contains(header.format())) {
                throw new IllegalArgumentException("Unsupported source field profile");
            }
            var fields = flatten(input);
            fieldHash = fingerprint(input);
            for (var fact : FactId.values()) {
                if (!input.selection().families().contains(fact.family())) {
                    continue;
                }
                if (fact == FactId.NAME
                        && stamp.kind() != PreparedItemTypes.SourceKind.ITEM_SPARSE) {
                    omissions.add(omission(fact, CellState.UNSUPPORTED, "ITEM_NAME_SPARSE_ONLY"));
                    continue;
                }
                var names =
                        fact == FactId.NAME
                                ? List.of(ItemLayout.NAME_FIELD)
                                : ItemFacts.fieldNames(fact.legacyKey());
                var selected = ItemFacts.columns(input.columns(), names);
                if (selected.isEmpty()) {
                    omissions.add(
                            omission(fact, CellState.UNSUPPORTED, "ITEM_FACT_FIELD_UNSUPPORTED"));
                    continue;
                }
                if (selected.size() > ItemFacts.MAXIMUM_VALUES) {
                    omissions.add(omission(fact, CellState.LIMITED, "ITEM_FACT_SOURCE_LIMIT"));
                    continue;
                }
                var candidates = new ArrayList<Binding>();
                boolean rejected = false;
                for (int index : selected) {
                    var field = fields.get(index);
                    var actual = input.columns().get(index);
                    boolean text = fact == FactId.NAME || fact == FactId.RAW_DESCRIPTION;
                    if (!field.verifiedName()
                            || actual.interpretation() != null
                            || actual.narrowing() != null
                            || field.id() != TableSchema.KeyStorage.NONE
                            || text
                                    && (field.kind() != TableSchema.ColumnKind.STRING
                                                    && field.kind()
                                                            != TableSchema.ColumnKind.LOCSTRING
                                            || field.arrayLength() != 1)
                            || !text && field.kind() != TableSchema.ColumnKind.INT
                            || fact != FactId.FLAGS_WORD && field.arrayLength() != 1) {
                        rejected = true;
                        break;
                    }
                    if (!text
                            && (field.bits() == null
                                    || field.unsigned() == null
                                    || field.bits() < 1
                                    || field.bits() > 64)) {
                        rejected = true;
                        break;
                    }
                    String name = actual.name();
                    if (name.length() > 128
                            || field.name().length() > 128
                            || field.foreignKey() != null
                                    && (field.foreignKey().table().length() > 128
                                            || field.foreignKey().column().length() > 128)) {
                        rejected = true;
                        break;
                    }
                    int slot = field.arrayLength() > 1 ? slot(name, field.name()) : -1;
                    String signature =
                            PreparedItemProjection.hash(
                                    out -> {
                                        PreparedItemProjection.text(
                                                out, PreparedItemProjection.POLICY_REVISION);
                                        PreparedItemProjection.text(out, fact.name());
                                        column(out, field);
                                        PreparedItemProjection.text(out, actual.kind());
                                        out.writeInt(slot);
                                    });
                    var key =
                            new SourceQualifiedKey(
                                    fact,
                                    stamp.kind(),
                                    stamp.fileDataId(),
                                    field.name(),
                                    field.arrayLength() > 1,
                                    slot,
                                    signature);
                    candidates.add(
                            new Binding(
                                    key,
                                    index,
                                    text
                                            ? ScalarKind.TEXT
                                            : fact == FactId.FLAGS_WORD
                                                    ? ScalarKind.FLAG_WORD
                                                    : ScalarKind.INTEGER,
                                    field.bits(),
                                    field.unsigned(),
                                    field.relation() != TableSchema.KeyStorage.NONE,
                                    field.foreignKey() == null ? null : field.foreignKey().table(),
                                    field.foreignKey() == null
                                            ? null
                                            : field.foreignKey().column()));
                }
                // Multiple aliases/scalars are ambiguous; arrays must contain one complete source
                // vector.
                if (candidates.stream().map(b -> b.key().field()).distinct().count() != 1
                        || candidates.stream().map(b -> b.key().arraySlot()).distinct().count()
                                != candidates.size()) {
                    rejected = true;
                }
                if (rejected) {
                    omissions.add(
                            omission(fact, CellState.UNSUPPORTED, "ITEM_FACT_PROFILE_UNSUPPORTED"));
                    continue;
                }
                if (bindings.size() + candidates.size() > PreparedItemProjection.MAX_BINDINGS) {
                    omissions.add(omission(fact, CellState.LIMITED, "ITEM_PROFILE_BINDING_LIMIT"));
                    continue;
                }
                var test = new ArrayList<>(bindings);
                test.addAll(candidates);
                if (PreparedItemProjection.estimate(stamp, input.selection(), ids, test, omissions)
                        > PreparedItemProjection.MAX_PROFILE_BYTES - 1024) {
                    omissions.add(omission(fact, CellState.LIMITED, "ITEM_PROFILE_METADATA_LIMIT"));
                    continue;
                }
                bindings.addAll(candidates);
            }
        } catch (RuntimeException invalid) {
            bindings.clear();
            omissions.clear();
            omissions.add(
                    new Omission(
                            null, null, CellState.UNSUPPORTED, "ITEM_SOURCE_SCHEMA_UNSUPPORTED"));
        }
        if (!List.of(SourceState.READABLE, SourceState.PARTIAL).contains(input.state())) {
            bindings.clear();
            omissions.clear();
            omissions.add(new Omission(null, null, CellState.UNKNOWN, "ITEM_SOURCE_NOT_READABLE"));
        }
        var profile =
                new SourceProfile(
                        stamp,
                        input.state(),
                        input.selection(),
                        identity,
                        fieldHash,
                        input.columns().size(),
                        ids,
                        bindings,
                        omissions);
        FieldAdmission state =
                bindings.isEmpty()
                        ? omissions.stream().anyMatch(v -> v.state() == CellState.LIMITED)
                                ? FieldAdmission.LIMITED
                                : FieldAdmission.UNSUPPORTED
                        : omissions.isEmpty() ? FieldAdmission.ADMITTED : FieldAdmission.PARTIAL;
        return new Compilation(IdentityAdmission.ADMITTED, state, profile, omissions);
    }

    private static Compilation failure(IdentityAdmission state, String code) {
        var omissions = List.of(new Omission(null, null, CellState.UNKNOWN, code));
        return new Compilation(state, FieldAdmission.UNSUPPORTED, null, omissions);
    }

    private static Omission omission(FactId fact, CellState state, String code) {
        return new Omission(fact.family(), fact, state, code);
    }

    private static int slot(String name, String field) {
        String base = field + "[";
        if (!name.startsWith(base) || !name.endsWith("]")) {
            throw new IllegalArgumentException("Invalid array column");
        }
        return Integer.parseInt(name.substring(base.length(), name.length() - 1));
    }

    private static List<TableSchema.SchemaColumn> flatten(SourceSchema input) {
        var fields = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : input.schema().columns()) {
            if (field.name() == null
                    || field.name().length() > 128
                    || field.arrayLength() < 1
                    || field.arrayLength() > 512
                    || field.foreignKey() != null
                            && (field.foreignKey().table().length() > 128
                                    || field.foreignKey().column().length() > 128)) {
                throw new IllegalArgumentException("Invalid schema field");
            }
            for (int index = 0; index < field.arrayLength(); index++) {
                if (fields.size() >= input.columns().size()) {
                    throw new IllegalArgumentException("Flattened schema differs");
                }
                var actual = input.columns().get(fields.size());
                if (actual.name().length() > 128
                        || actual.interpretation() != null
                                && actual.interpretation().policy().length() > 128) {
                    throw new IllegalArgumentException("Column metadata exceeds bound");
                }
                String name =
                        field.arrayLength() == 1 ? field.name() : field.name() + "[" + index + "]";
                String kind =
                        switch (field.kind()) {
                            case INT -> "integer";
                            case FLOAT -> "float";
                            case STRING, LOCSTRING -> "string";
                        };
                if (!name.equals(actual.name())
                        || !kind.equals(actual.kind())
                        || actual.isId() != (field.id() != TableSchema.KeyStorage.NONE)) {
                    throw new IllegalArgumentException("Flattened column mismatch");
                }
                fields.add(field);
            }
        }
        if (fields.size() != input.columns().size()) {
            throw new IllegalArgumentException("Flattened source count differs");
        }
        return fields;
    }

    private static String fingerprint(SourceSchema input) {
        return PreparedItemProjection.hash(
                out -> {
                    var stamp = input.stamp();
                    PreparedItemProjection.text(out, PreparedItemProjection.POLICY_REVISION);
                    PreparedItemProjection.text(out, PreparedItemProjection.ID_POLICY);
                    PreparedItemProjection.text(out, ItemFacts.POLICY_REVISION);
                    PreparedItemProjection.text(out, input.stamp().kind().name());
                    out.writeLong(input.stamp().fileDataId());
                    PreparedItemProjection.text(
                            out, input.header().tableHash().toUpperCase(Locale.ROOT));
                    PreparedItemProjection.text(out, input.header().format());
                    PreparedItemProjection.text(out, stamp.layoutHash().toUpperCase(Locale.ROOT));
                    PreparedItemProjection.text(out, stamp.definitionSha256());
                    PreparedItemProjection.text(out, stamp.blockIdentity());
                    PreparedItemProjection.text(out, stamp.readerRevision());
                    PreparedItemProjection.text(out, stamp.readerOptions());
                    PreparedItemProjection.text(out, stamp.rowEncoding());
                    PreparedItemProjection.text(out, stamp.locale());
                    out.writeInt(PreparedItemProjection.MAX_TEXT);
                    out.writeInt(PreparedItemProjection.MAX_BINDINGS);
                    out.writeInt(PreparedItemProjection.MAX_PROFILE_BYTES);
                    out.writeInt(input.selection().families().size());
                    for (var family : PreparedItemTypes.FactFamily.values()) {
                        if (input.selection().families().contains(family)) {
                            PreparedItemProjection.text(out, family.name());
                        }
                    }
                    out.writeInt(input.schema().columns().size());
                    for (var field : input.schema().columns()) {
                        column(out, field);
                    }
                    out.writeInt(input.columns().size());
                    for (var c : input.columns()) {
                        PreparedItemProjection.text(out, c.name());
                        PreparedItemProjection.text(out, c.kind());
                        out.writeBoolean(c.isId());
                        out.writeBoolean(c.interpretation() != null);
                        if (c.interpretation() != null) {
                            var v = c.interpretation();
                            PreparedItemProjection.text(out, v.policy());
                            out.writeInt(v.physicalBits());
                            out.writeBoolean(v.physicalSigned());
                            out.writeInt(v.candidateBits());
                            out.writeBoolean(v.candidateUnsigned());
                        }
                        out.writeBoolean(c.narrowing() != null);
                        if (c.narrowing() != null) {
                            out.writeInt(c.narrowing().storedBits());
                            out.writeInt(c.narrowing().usedBits());
                        }
                    }
                });
    }

    private static void column(DataOutputStream out, TableSchema.SchemaColumn c)
            throws IOException {
        PreparedItemProjection.text(out, c.name());
        PreparedItemProjection.text(out, c.kind().name());
        out.writeBoolean(c.bits() != null);
        if (c.bits() != null) {
            out.writeInt(c.bits());
        }
        out.writeBoolean(c.unsigned() != null);
        if (c.unsigned() != null) {
            out.writeBoolean(c.unsigned());
        }
        out.writeInt(c.arrayLength());
        out.writeBoolean(c.foreignKey() != null);
        if (c.foreignKey() != null) {
            PreparedItemProjection.text(out, c.foreignKey().table());
            PreparedItemProjection.text(out, c.foreignKey().column());
        }
        PreparedItemProjection.text(out, c.id().name());
        PreparedItemProjection.text(out, c.relation().name());
        out.writeBoolean(c.verifiedName());
        PreparedItemProjection.text(out, c.locale() == null ? null : c.locale().code());
    }
}
