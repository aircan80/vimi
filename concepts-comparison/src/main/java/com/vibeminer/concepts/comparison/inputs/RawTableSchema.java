package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.SingleText;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Exact-layout admission and raw cells only; no semantic relationship or gameplay interpretation.
 */
public final class RawTableSchema {
    public static final String POLICY = "raw-table-schema-v1";
    public static final int MAX_TEXT = 8192;
    private static final int MAX_COLUMNS = 512;
    private static final int MAX_ROW_TEXT = 65536;
    private static final String SOURCE = "Blizzard.DB2";

    private RawTableSchema() {}

    public record Inputs(
            String format,
            String layout,
            String readerRevision,
            String rowEncoding,
            String integerPolicy,
            String locale,
            String definitionSha256,
            String blockIdentity,
            TableSchema schema,
            List<Db2Types.Column> columns) {
        public Inputs {
            columns = List.copyOf(columns);
            Objects.requireNonNull(schema);
        }
    }

    public record ColumnScope(int index, String name, String exclusion) {}

    private record Cell(
            int index,
            TableSchema.SchemaColumn schema,
            String name,
            ComparisonTypes.ValueKind kind,
            String semantics) {}

    /** A bounded source profile; the capturing caller owns its metadata-memory reservation. */
    public static final class Profile {
        private final String fingerprint;
        private final List<ColumnScope> scope;
        private final List<Cell> assessed;
        private final int width;

        private Profile(
                String fingerprint, List<ColumnScope> scope, List<Cell> assessed, int width) {
            this.fingerprint = fingerprint;
            this.scope = List.copyOf(scope);
            this.assessed = List.copyOf(assessed);
            this.width = width;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public List<ColumnScope> scope() {
            return scope;
        }

        public int assessedColumns() {
            return assessed.size();
        }

        /** Conservative bound used before projecting one row into a reserved source-output page. */
        public long maximumRetainedBytes(int identityLength) {
            if (identityLength < 1 || identityLength > 4096) {
                throw new IllegalArgumentException("Invalid evidence identity length");
            }
            long bytes = 8192L + 4L * MAX_ROW_TEXT;
            for (var cell : assessed) {
                bytes += 256L + 4L * (5L + cell.name().length() + cell.semantics().length());
                bytes +=
                        32L + 4L * (SOURCE.length() + identityLength + cell.name().length() + 512L);
            }
            return bytes;
        }

        /**
         * Source identity is explanatory, never part of compared semantics. Overrides preserve
         * native oversized-cell limitations without fabricating an empty text-issue channel.
         */
        public ComparisonTypes.Record project(
                Db2Types.Row row,
                String identity,
                Map<Integer, ComparisonTypes.ValueState> overrides) {
            return project(row, identity, overrides, true);
        }

        /** A limited native issue channel cannot certify unaffected placeholder-bearing cells. */
        public ComparisonTypes.Record project(
                Db2Types.Row row,
                String identity,
                Map<Integer, ComparisonTypes.ValueState> overrides,
                boolean issueChannelComplete) {
            Objects.requireNonNull(row);
            Objects.requireNonNull(overrides);
            if (identity == null
                    || identity.isBlank()
                    || identity.length() > 4096
                    || row.values().size() != width) {
                throw new IllegalArgumentException("Invalid raw row identity/column alignment");
            }
            String key = InventoryFields.key(row.id(), true);
            var issues = new LinkedHashMap<Integer, List<Db2Types.TextIssue>>();
            if (row.textIssues() != null) {
                if (row.textIssues().size() > 4096) {
                    throw new IllegalArgumentException("Raw row issue limit exceeded");
                }
                for (var issue : row.textIssues()) {
                    if (issue.columnIndex() < 0 || issue.columnIndex() >= width) {
                        throw new IllegalArgumentException(
                                "Issue has no unambiguous source column");
                    }
                    issues.computeIfAbsent(issue.columnIndex(), ignored -> new ArrayList<>())
                            .add(issue);
                }
            }
            for (var entry : overrides.entrySet()) {
                if (entry.getKey() < 0
                        || entry.getKey() >= width
                        || entry.getValue() == null
                        || entry.getValue() == ComparisonTypes.ValueState.VALUE
                        || entry.getValue() == ComparisonTypes.ValueState.KNOWN_EMPTY) {
                    throw new IllegalArgumentException("Invalid captured-cell limitation");
                }
            }
            var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
            fields.put(
                    "record.isCopy",
                    field(
                            "raw-record-copy-mode-v1",
                            ComparisonTypes.ValueKind.BOOLEAN,
                            ComparisonTypes.ValueState.VALUE,
                            Boolean.toString(row.isCopy()),
                            ComparisonTypes.Aspect.INTERPRETATION,
                            identity,
                            "isCopy",
                            null));
            var copyState =
                    !row.isCopy()
                            ? ComparisonTypes.ValueState.KNOWN_EMPTY
                            : row.copySourceId() == null
                                    ? ComparisonTypes.ValueState.UNKNOWN
                                    : row.copySourceId() < 0 || row.copySourceId() > 0xffffffffL
                                            ? ComparisonTypes.ValueState.INVALID
                                            : ComparisonTypes.ValueState.VALUE;
            fields.put(
                    "record.copySource",
                    field(
                            "raw-record-copy-source-id-v1",
                            ComparisonTypes.ValueKind.REFERENCE,
                            copyState,
                            copyState == ComparisonTypes.ValueState.VALUE
                                    ? row.copySourceId().toString()
                                    : null,
                            ComparisonTypes.Aspect.INTERPRETATION,
                            identity,
                            "copySourceId",
                            null));
            int retainedText = 0;
            for (var cell : assessed) {
                var raw = row.values().get(cell.index());
                var state = overrides.get(cell.index());
                String issueEvidence = state == null ? null : "Captured cell limitation:" + state;
                if (state == null && issues.containsKey(cell.index())) {
                    var cellIssues = issues.get(cell.index());
                    state =
                            cellIssues.stream()
                                            .anyMatch(
                                                    i ->
                                                            i.code()
                                                                    .equals(
                                                                            "DB2_TEXT_ENCODING_INVALID"))
                                    ? ComparisonTypes.ValueState.INVALID
                                    : ComparisonTypes.ValueState.UNKNOWN;
                    var first = cellIssues.getFirst();
                    issueEvidence =
                            prefix(first.code(), 128) + ":" + prefix(first.rawBytesHex(), 256);
                }
                if (state == null
                        && (row.textIssues() == null || !issueChannelComplete)
                        && (cell.kind() == ComparisonTypes.ValueKind.TEXT
                                || cell.schema().relation() == TableSchema.KeyStorage.NONINLINE)) {
                    state = ComparisonTypes.ValueState.UNKNOWN;
                    issueEvidence =
                            "Text/relationship issue channel not supplied; recorded cell: "
                                    + prefix(raw, 256);
                }
                String value = null;
                if (state == null) {
                    try {
                        if (raw.length() > MAX_TEXT || raw.length() > MAX_ROW_TEXT - retainedText) {
                            state = ComparisonTypes.ValueState.LIMITED;
                            issueEvidence = "Raw cell/row text budget exceeded";
                        } else if (cell.kind() == ComparisonTypes.ValueKind.TEXT) {
                            utf8(raw);
                            state =
                                    raw.isEmpty()
                                            ? ComparisonTypes.ValueState.KNOWN_EMPTY
                                            : ComparisonTypes.ValueState.VALUE;
                            value = raw.isEmpty() ? null : raw;
                        } else if (cell.kind() == ComparisonTypes.ValueKind.DECIMAL) {
                            float number = Float.parseFloat(raw);
                            if (!Float.isFinite(number) || !SingleText.format(number).equals(raw)) {
                                throw new IllegalArgumentException("Noncanonical float32 text");
                            }
                            state = ComparisonTypes.ValueState.VALUE;
                            value = raw;
                        } else {
                            var number =
                                    cell.schema().id() == TableSchema.KeyStorage.NONE
                                            ? integer(raw, cell.schema())
                                            : unsignedRowId(raw);
                            if (cell.schema().id() != TableSchema.KeyStorage.NONE
                                    && !number.equals(BigInteger.valueOf(row.id()))) {
                                throw new IllegalArgumentException(
                                        "Recorded ID cell differs from row identity");
                            }
                            state = ComparisonTypes.ValueState.VALUE;
                            value = number.toString();
                        }
                    } catch (IllegalArgumentException | CharacterCodingException invalid) {
                        state = ComparisonTypes.ValueState.INVALID;
                        issueEvidence = "Invalid recorded cell:" + prefix(raw, 128);
                    }
                }
                if (value != null) {
                    retainedText += value.length();
                }
                fields.put(
                        "cell." + cell.name(),
                        field(
                                cell.semantics(),
                                cell.kind(),
                                state,
                                value,
                                ComparisonTypes.Aspect.CONTENT,
                                identity,
                                cell.name(),
                                issueEvidence));
            }
            return new ComparisonTypes.Record(
                    key,
                    fields,
                    List.of(
                            new ComparisonTypes.Evidence(
                                    SOURCE, identity, "row.id", Long.toString(row.id()))));
        }
    }

    public static Profile create(Inputs input) {
        Objects.requireNonNull(input);
        if (input.columns().isEmpty()
                || input.columns().size() > MAX_COLUMNS
                || input.schema().columns().size() > MAX_COLUMNS
                || input.layout() == null
                || !input.layout().matches("[0-9A-Fa-f]{8}")
                || input.definitionSha256() == null
                || !input.definitionSha256().matches("[0-9a-f]{64}")
                || WowLocale.tryParse(input.locale()) == null
                || !input.schema().locale().code().equals(input.locale())) {
            throw new IllegalArgumentException("Unsupported captured raw schema");
        }
        for (String required :
                java.util.Arrays.asList(
                        input.format(),
                        input.readerRevision(),
                        input.rowEncoding(),
                        input.integerPolicy(),
                        input.blockIdentity())) {
            if (required == null || required.isBlank() || required.length() > 1024) {
                throw new IllegalArgumentException("Captured interpretation identity required");
            }
        }
        var scope = new ArrayList<ColumnScope>();
        var assessed = new ArrayList<Cell>();
        var names = new HashSet<String>();
        int index = 0;
        for (var column : input.schema().columns()) {
            checkColumn(column);
            if (column.kind() == TableSchema.ColumnKind.LOCSTRING
                    ? column.locale() != input.schema().locale()
                    : column.locale() != null) {
                throw new IllegalArgumentException("Column locale scope mismatch");
            }
            for (int element = 0; element < column.arrayLength(); element++) {
                if (index >= input.columns().size()) {
                    throw new IllegalArgumentException("Schema array/column shape mismatch");
                }
                final int arrayIndex = element;
                var actual = input.columns().get(index);
                var name =
                        column.arrayLength() == 1
                                ? column.name()
                                : column.name() + "[" + element + "]";
                var kind =
                        switch (column.kind()) {
                            case INT -> "integer";
                            case FLOAT -> "float";
                            case STRING, LOCSTRING -> "string";
                        };
                if (!name.equals(actual.name())
                        || !kind.equals(actual.kind())
                        || actual.isId() != (column.id() != TableSchema.KeyStorage.NONE)
                        || !names.add(name)) {
                    throw new IllegalArgumentException("Flattened column interpretation mismatch");
                }
                var excluded =
                        !column.verifiedName()
                                ? "UNVERIFIED_NAME"
                                : actual.interpretation() != null
                                        ? "RAW_INTEGER_INTERPRETATION"
                                        : actual.narrowing() != null ? "CANDIDATE_NARROWING" : null;
                scope.add(new ColumnScope(index, name, excluded));
                if (excluded == null) {
                    var valueKind =
                            switch (column.kind()) {
                                case INT ->
                                        column.foreignKey() == null
                                                ? ComparisonTypes.ValueKind.INTEGER
                                                : ComparisonTypes.ValueKind.REFERENCE;
                                case FLOAT -> ComparisonTypes.ValueKind.DECIMAL;
                                case STRING, LOCSTRING -> ComparisonTypes.ValueKind.TEXT;
                            };
                    assessed.add(
                            new Cell(
                                    index,
                                    column,
                                    name,
                                    valueKind,
                                    (column.id() == TableSchema.KeyStorage.NONE
                                                    ? "raw-cell-v1:"
                                                    : "raw-id-cell-unsigned32-decoder-v1:")
                                            + digest(
                                                    out -> {
                                                        schemaColumn(out, column);
                                                        out.writeInt(arrayIndex);
                                                    })));
                }
                index++;
            }
        }
        if (index != input.columns().size() || assessed.isEmpty()) {
            throw new IllegalArgumentException("No compatible assessed raw-column scope");
        }
        String fingerprint =
                digest(
                        out -> {
                            text(out, POLICY);
                            text(out, input.format());
                            text(out, input.layout().toUpperCase(Locale.ROOT));
                            text(out, input.readerRevision());
                            text(out, input.rowEncoding());
                            text(out, input.integerPolicy());
                            text(out, input.locale());
                            text(out, "exact-requested-locale-v1");
                            text(out, input.definitionSha256());
                            text(out, input.blockIdentity());
                            out.writeInt(input.schema().columns().size());
                            for (var column : input.schema().columns()) {
                                schemaColumn(out, column);
                            }
                            out.writeInt(input.columns().size());
                            for (var column : input.columns()) {
                                actualColumn(out, column);
                            }
                            text(out, "verified-raw-cell-scope-v1");
                            text(out, "id-cell-unsigned32-decoder-v1");
                            out.writeInt(MAX_TEXT);
                            out.writeInt(MAX_ROW_TEXT);
                            out.writeInt(scope.size());
                            for (var column : scope) {
                                out.writeInt(column.index());
                                text(out, column.name());
                                text(out, column.exclusion());
                            }
                        });
        return new Profile(fingerprint, scope, assessed, index);
    }

    private static void checkColumn(TableSchema.SchemaColumn column) {
        if (column.name() == null
                || !column.name().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || column.arrayLength() < 1
                || column.arrayLength() > MAX_COLUMNS
                || column.kind() == null
                || column.id() == null
                || column.relation() == null) {
            throw new IllegalArgumentException("Invalid selected raw-column metadata");
        }
        if (column.kind() == TableSchema.ColumnKind.INT) {
            if (column.bits() == null
                    || column.bits() < 1
                    || column.bits() > 64
                    || column.unsigned() == null) {
                throw new IllegalArgumentException("Integer width/signedness unavailable");
            }
        } else if (column.bits() != null
                || column.unsigned() != null
                || column.foreignKey() != null) {
            throw new IllegalArgumentException("Noninteger column carries integer metadata");
        }
        if ((column.id() != TableSchema.KeyStorage.NONE
                        || column.relation() != TableSchema.KeyStorage.NONE)
                && (column.kind() != TableSchema.ColumnKind.INT
                        || column.arrayLength() != 1
                        || column.id() != TableSchema.KeyStorage.NONE
                                && column.relation() != TableSchema.KeyStorage.NONE)) {
            throw new IllegalArgumentException("Invalid ID/relationship shape");
        }
        if (column.foreignKey() != null
                && (column.foreignKey().table() == null || column.foreignKey().column() == null)) {
            throw new IllegalArgumentException("Incomplete reference target");
        }
    }

    private static BigInteger unsignedRowId(String raw) {
        if (raw.length() > 10 || !raw.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Noncanonical unsigned row ID");
        }
        var value = new BigInteger(raw);
        if (value.compareTo(BigInteger.valueOf(0xffffffffL)) > 0) {
            throw new IllegalArgumentException("Row ID exceeds unsigned32");
        }
        return value;
    }

    private static BigInteger integer(String raw, TableSchema.SchemaColumn column) {
        if (raw.length() > 21 || !raw.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Noncanonical integer text");
        }
        var value = new BigInteger(raw);
        int width = column.bits();
        var minimum =
                column.unsigned() ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(width - 1).negate();
        var maximum =
                BigInteger.ONE
                        .shiftLeft(column.unsigned() ? width : width - 1)
                        .subtract(BigInteger.ONE);
        if (value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0
                || !value.toString().equals(raw)) {
            throw new IllegalArgumentException("Recorded integer outside schema range");
        }
        return value;
    }

    private static ComparisonTypes.Field field(
            String semantics,
            ComparisonTypes.ValueKind kind,
            ComparisonTypes.ValueState state,
            String value,
            ComparisonTypes.Aspect aspect,
            String identity,
            String column,
            String diagnostic) {
        return new ComparisonTypes.Field(
                semantics,
                kind,
                state,
                value,
                aspect,
                List.of(new ComparisonTypes.Evidence(SOURCE, identity, column, diagnostic)));
    }

    private static void schemaColumn(DataOutputStream out, TableSchema.SchemaColumn column)
            throws IOException {
        text(out, column.name());
        text(out, column.kind().name());
        integer(out, column.bits());
        nullableBoolean(out, column.unsigned());
        out.writeInt(column.arrayLength());
        text(out, column.foreignKey() == null ? null : column.foreignKey().table());
        text(out, column.foreignKey() == null ? null : column.foreignKey().column());
        text(out, column.id().name());
        text(out, column.relation().name());
        out.writeBoolean(column.verifiedName());
        text(out, column.locale() == null ? null : column.locale().code());
    }

    private static void actualColumn(DataOutputStream out, Db2Types.Column column)
            throws IOException {
        text(out, column.name());
        text(out, column.kind());
        out.writeBoolean(column.isId());
        out.writeBoolean(column.interpretation() != null);
        if (column.interpretation() != null) {
            var value = column.interpretation();
            text(out, value.policy());
            out.writeInt(value.physicalBits());
            out.writeBoolean(value.physicalSigned());
            out.writeInt(value.candidateBits());
            out.writeBoolean(value.candidateUnsigned());
        }
        out.writeBoolean(column.narrowing() != null);
        if (column.narrowing() != null) {
            out.writeInt(column.narrowing().storedBits());
            out.writeInt(column.narrowing().usedBits());
        }
    }

    private static void integer(DataOutputStream out, Integer value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeInt(value);
        }
    }

    private static void nullableBoolean(DataOutputStream out, Boolean value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeBoolean(value);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        if (value.length() > 1024) {
            throw new IllegalArgumentException("Raw schema text limit exceeded");
        }
        var bytes = utf8(value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] utf8(String value) throws CharacterCodingException {
        var encoded =
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static String digest(Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out =
                    new DataOutputStream(
                            new java.io.OutputStream() {
                                @Override
                                public void write(int value) throws IOException {
                                    if (bytes.size() >= 1048576) {
                                        throw new IOException("Schema fingerprint limit exceeded");
                                    }
                                    bytes.write(value);
                                }

                                @Override
                                public void write(byte[] value, int offset, int length)
                                        throws IOException {
                                    if (length > 1048576 - bytes.size()) {
                                        throw new IOException("Schema fingerprint limit exceeded");
                                    }
                                    bytes.write(value, offset, length);
                                }
                            })) {
                writer.write(out);
            }
            if (bytes.size() > 1048576) {
                throw new IllegalArgumentException("Schema fingerprint limit exceeded");
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("Invalid raw schema fingerprint input", invalid);
        }
    }

    private static String prefix(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
