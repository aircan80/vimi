package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.TableTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen public metadata publications only. Never invokes BuildTables list/schema/prepare. */
public final class TableInventoryInput extends SerialInventoryInput {
    public static final String NAMESPACE = "tables.FileDataID.v1";
    public static final String SCOPE = "known-definition-table-inventory-v1";
    private static final String SOURCE = "BuildTables.prepareKnown.cached-publication";
    private static final int MAX_TABLES = 8192, MAX_COLUMNS = 512, SCHEMA_BYTES = 65536;

    /**
     * The owner certifies this receipt came from prepareKnown, not preload or a visible page.
     * Identities pin the exact cached preparation policy and known-definition inventory revision.
     */
    public record KnownInventory(
            TableTypes.PreloadResult result,
            String preparationIdentity,
            String definitionIdentity) {
        public KnownInventory {
            Objects.requireNonNull(result);
            if (preparationIdentity == null
                    || preparationIdentity.isBlank()
                    || definitionIdentity == null
                    || definitionIdentity.isBlank()) {
                throw new IllegalArgumentException(
                        "Pinned known-inventory receipt identities are required");
            }
        }
    }

    private final KnownInventory receipt;
    private final List<TableTypes.Entry> entries;
    private final Map<Long, TableTypes.SchemaResult> schemas;
    private int offset;

    public TableInventoryInput(
            ComparisonTypes.Descriptor descriptor,
            KnownInventory receipt,
            Map<Long, TableTypes.SchemaResult> frozenSchemas) {
        super(checked(descriptor, receipt, frozenSchemas));
        this.receipt = receipt;
        if (receipt.result().tables().size() > MAX_TABLES || frozenSchemas.size() > MAX_TABLES) {
            throw new IllegalArgumentException(
                    "Known table inventory exceeds bounded metadata scope");
        }
        entries =
                receipt.result().tables().stream()
                        .sorted(Comparator.comparingLong(TableTypes.Entry::fileDataId))
                        .toList();
        long previous = -1;
        for (var entry : entries) {
            InventoryFields.key(entry.fileDataId(), false);
            if (entry.fileDataId() <= previous) {
                throw new IllegalArgumentException("Duplicate known table ID");
            }
            previous = entry.fileDataId();
        }
        schemas = Map.copyOf(frozenSchemas);
    }

    private static ComparisonTypes.Descriptor checked(
            ComparisonTypes.Descriptor descriptor,
            KnownInventory receipt,
            Map<Long, TableTypes.SchemaResult> frozenSchemas) {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(receipt);
        if (!NAMESPACE.equals(descriptor.namespace()) || !SCOPE.equals(descriptor.scope())) {
            throw new IllegalArgumentException(
                    "Only the known-definition inventory scope is supported");
        }
        var dependencies = new LinkedHashMap<>(descriptor.dependencies());
        pin(dependencies, "inventory.adapter", "known-table-metadata-v1");
        pin(dependencies, "tables.preparation", receipt.preparationIdentity());
        pin(dependencies, "tables.definitions", receipt.definitionIdentity());
        pin(dependencies, "tables.inventory.policy", "prepareKnown");
        pin(
                dependencies,
                "tables.schemas",
                frozenSchemas.isEmpty() ? "unassessed-not-captured" : "frozen-publications");
        return new ComparisonTypes.Descriptor(
                descriptor.identity(),
                descriptor.product(),
                descriptor.locale(),
                descriptor.namespace(),
                descriptor.scope(),
                dependencies);
    }

    private static void pin(Map<String, String> values, String key, String value) {
        var previous = values.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) {
            throw new IllegalArgumentException("Conflicting dependency " + key);
        }
    }

    @Override
    protected ComparisonTypes.InputPage page(int limit, Cancellation token) {
        var records = new ArrayList<ComparisonTypes.Record>();
        int end = Math.min(entries.size(), offset + limit);
        for (; offset < end; offset++) {
            token.check();
            records.add(record(entries.get(offset)));
        }
        boolean exhausted = offset == entries.size();
        boolean certified = receipt.result().outcome() == TableTypes.PreloadOutcome.COMPLETED;
        var complete =
                exhausted && certified
                        ? ComparisonTypes.Completeness.COMPLETE
                        : certified
                                ? ComparisonTypes.Completeness.UNKNOWN
                                : ComparisonTypes.Completeness.PARTIAL;
        var issues = new ArrayList<String>();
        issues.add(
                "Scope is the pinned known-definition inventory, not every DB2 in the game or complete table rows.");
        if (!certified) {
            issues.add(
                    "Known-inventory preparation was not completed; missing entries are unassessed.");
        }
        if (!exhausted) {
            issues.add("Known-inventory enumeration is still in progress.");
        }
        return new ComparisonTypes.InputPage(
                descriptor().identity(),
                records,
                exhausted ? null : "continue",
                new ComparisonTypes.Coverage(complete, SCOPE, issues));
    }

    private ComparisonTypes.Record record(TableTypes.Entry entry) {
        var key = InventoryFields.key(entry.fileDataId(), false);
        var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
        String identity = descriptor().identity() + ":" + key;
        fields.put(
                "name",
                InventoryFields.text(
                        "definition-table-name-v1",
                        entry.name(),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "name"));
        fields.put(
                "state",
                InventoryFields.text(
                        "prepared-table-availability-v1",
                        entry.state() == null ? null : entry.state().name(),
                        ComparisonTypes.Aspect.AVAILABILITY,
                        SOURCE,
                        identity,
                        "state"));
        fields.put(
                "checked",
                InventoryFields.field(
                        "table-assessment-at-publication-v1",
                        ComparisonTypes.ValueKind.BOOLEAN,
                        ComparisonTypes.ValueState.VALUE,
                        Boolean.toString(entry.checked()),
                        ComparisonTypes.Aspect.AVAILABILITY,
                        SOURCE,
                        identity,
                        "checked"));
        fields.put("contentKey", InventoryFields.contentKey(entry.contentKey(), SOURCE, identity));
        fields.put(
                "reasonCode",
                InventoryFields.field(
                        "table-outcome-code-v1",
                        ComparisonTypes.ValueKind.TEXT,
                        entry.reason() == null
                                ? ComparisonTypes.ValueState.KNOWN_EMPTY
                                : entry.reason().code() == null
                                        ? ComparisonTypes.ValueState.UNKNOWN
                                        : ComparisonTypes.ValueState.VALUE,
                        entry.reason() == null ? null : entry.reason().code(),
                        ComparisonTypes.Aspect.AVAILABILITY,
                        SOURCE,
                        identity,
                        "reason.code"));
        var counts = entry.counts();
        fields.put(
                "declaredRows",
                InventoryFields.number(
                        "db2-declared-row-count-v1",
                        counts == null ? null : counts.declaredRows(),
                        ComparisonTypes.Aspect.CONTENT,
                        SOURCE,
                        identity,
                        "counts.declaredRows"));
        fields.put(
                "readableRows",
                InventoryFields.number(
                        "db2-readable-row-count-v1",
                        counts == null ? null : counts.readableRows(),
                        ComparisonTypes.Aspect.AVAILABILITY,
                        SOURCE,
                        identity,
                        "counts.readableRows"));
        fields.put(
                "excludedSections",
                InventoryFields.number(
                        "db2-excluded-section-count-v1",
                        counts == null ? null : (long) counts.excludedSections(),
                        ComparisonTypes.Aspect.AVAILABILITY,
                        SOURCE,
                        identity,
                        "counts.excludedSections"));
        fields.put(
                "excludedRecords",
                InventoryFields.number(
                        "db2-excluded-record-count-v1",
                        counts == null ? null : counts.excludedRecords(),
                        ComparisonTypes.Aspect.AVAILABILITY,
                        SOURCE,
                        identity,
                        "counts.excludedRecords"));
        fields.put(
                "layout",
                InventoryFields.text(
                        "db2-layout-reference-v1",
                        counts == null ? null : counts.layoutHash(),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "counts.layoutHash"));
        fields.put(
                "format",
                InventoryFields.text(
                        "db2-format-reference-v1",
                        counts == null ? null : counts.format(),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "counts.format"));
        addSchema(fields, entry, identity);
        return new ComparisonTypes.Record(
                key,
                fields,
                List.of(
                        new ComparisonTypes.Evidence(
                                SOURCE,
                                identity,
                                "FileDataID",
                                Long.toString(entry.fileDataId()))));
    }

    private void addSchema(
            Map<String, ComparisonTypes.Field> fields, TableTypes.Entry entry, String identity) {
        if (schemas.isEmpty()) {
            return;
        }
        var supplied = schemas.get(entry.fileDataId());
        var state = schemaState(entry);
        if (supplied == null
                || !sameSource(entry, supplied.table())
                || supplied.schema() == null
                || supplied.columns() == null) {
            fields.put(
                    "schema",
                    InventoryFields.field(
                            "frozen-schema-publication-v1",
                            ComparisonTypes.ValueKind.TEXT,
                            state,
                            null,
                            ComparisonTypes.Aspect.INTERPRETATION,
                            SOURCE,
                            identity,
                            "schema"));
            return;
        }
        var schema = supplied.schema();
        fields.put(
                "schema",
                InventoryFields.text(
                        "frozen-schema-publication-v1",
                        "available",
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "schema"));
        fields.put(
                "schema.match",
                InventoryFields.text(
                        "definition-match-reference-v1",
                        schema.match().name(),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "schema.match"));
        fields.put(
                "schema.locale",
                InventoryFields.text(
                        "definition-locale-reference-v1",
                        schema.locale().toString(),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "schema.locale"));
        String layouts =
                schema.layoutHashes().size() > 64
                        ? null
                        : InventoryFields.metadata(schema.layoutHashes().toArray());
        fields.put(
                "schema.layouts",
                InventoryFields.field(
                        "definition-layout-references-v1",
                        ComparisonTypes.ValueKind.TEXT,
                        layouts == null
                                ? ComparisonTypes.ValueState.LIMITED
                                : ComparisonTypes.ValueState.VALUE,
                        layouts,
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "schema.layoutHashes"));
        fields.put(
                "schema.comment",
                InventoryFields.field(
                        "definition-comment-v1",
                        ComparisonTypes.ValueKind.TEXT,
                        schema.comment() == null
                                ? ComparisonTypes.ValueState.KNOWN_EMPTY
                                : ComparisonTypes.ValueState.VALUE,
                        schema.comment(),
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        "schema.comment"));
        int[] budget = {SCHEMA_BYTES};
        for (int i = 0; i < Math.min(MAX_COLUMNS, schema.columns().size()); i++) {
            var c = schema.columns().get(i);
            String metadata =
                    InventoryFields.metadata(
                            c.name(),
                            c.kind(),
                            c.bits(),
                            c.unsigned(),
                            c.arrayLength(),
                            c.foreignKey() == null ? null : c.foreignKey().table(),
                            c.foreignKey() == null ? null : c.foreignKey().column(),
                            c.id(),
                            c.relation(),
                            c.verifiedName(),
                            c.locale(),
                            c.columnComment(),
                            c.fieldComment());
            schemaField(
                    fields,
                    "schema.candidate." + i,
                    "definition-column-metadata-v1",
                    metadata,
                    identity,
                    budget);
        }
        for (int i = 0; i < Math.min(MAX_COLUMNS, supplied.columns().size()); i++) {
            Db2Types.Column c = supplied.columns().get(i);
            var raw = c.interpretation();
            String metadata =
                    InventoryFields.metadata(
                            c.name(),
                            c.kind(),
                            c.isId(),
                            raw == null ? null : raw.policy(),
                            raw == null ? null : raw.physicalBits(),
                            raw == null ? null : raw.physicalSigned(),
                            raw == null ? null : raw.candidateBits(),
                            raw == null ? null : raw.candidateUnsigned(),
                            raw == null ? null : raw.warning(),
                            c.narrowing() == null ? null : c.narrowing().storedBits(),
                            c.narrowing() == null ? null : c.narrowing().usedBits());
            schemaField(
                    fields,
                    "schema.physical." + i,
                    "physical-column-metadata-v1",
                    metadata,
                    identity,
                    budget);
        }
        if (schema.columns().size() > MAX_COLUMNS || supplied.columns().size() > MAX_COLUMNS) {
            fields.put(
                    "schema.remaining",
                    InventoryFields.field(
                            "schema-column-budget-v1",
                            ComparisonTypes.ValueKind.TEXT,
                            ComparisonTypes.ValueState.LIMITED,
                            null,
                            ComparisonTypes.Aspect.INTERPRETATION,
                            SOURCE,
                            identity,
                            "schema.remaining"));
        }
    }

    private static void schemaField(
            Map<String, ComparisonTypes.Field> fields,
            String key,
            String meaning,
            String metadata,
            String identity,
            int[] budget) {
        int bytes =
                metadata == null
                        ? SCHEMA_BYTES + 1
                        : metadata.getBytes(StandardCharsets.UTF_8).length;
        boolean allowed = bytes <= budget[0];
        if (allowed) {
            budget[0] -= bytes;
        }
        fields.put(
                key,
                InventoryFields.field(
                        meaning,
                        ComparisonTypes.ValueKind.TEXT,
                        allowed
                                ? ComparisonTypes.ValueState.VALUE
                                : ComparisonTypes.ValueState.LIMITED,
                        allowed ? metadata : null,
                        ComparisonTypes.Aspect.INTERPRETATION,
                        SOURCE,
                        identity,
                        key));
    }

    private static ComparisonTypes.ValueState schemaState(TableTypes.Entry entry) {
        if (entry.state() == TableTypes.State.ENCRYPTED
                || entry.state() == TableTypes.State.WRONG_KEY) {
            return ComparisonTypes.ValueState.PROTECTED;
        }
        if (entry.state() == TableTypes.State.NO_DEFINITION
                || entry.state() == TableTypes.State.DEFINITION_UNUSABLE
                || entry.state() == TableTypes.State.UNSUPPORTED) {
            return ComparisonTypes.ValueState.UNSUPPORTED;
        }
        return ComparisonTypes.ValueState.UNKNOWN;
    }

    private static boolean sameSource(TableTypes.Entry expected, TableTypes.Entry schema) {
        return schema != null
                && schema.fileDataId() == expected.fileDataId()
                && expected.contentKey() != null
                && expected.contentKey().matches("[0-9a-fA-F]{32}")
                && expected.contentKey().equalsIgnoreCase(schema.contentKey())
                && expected.counts() != null
                && schema.counts() != null
                && expected.counts().layoutHash() != null
                && expected.counts().layoutHash().matches("[0-9a-fA-F]{8}")
                && expected.counts().layoutHash().equalsIgnoreCase(schema.counts().layoutHash());
    }
}
