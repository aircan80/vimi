package com.vibeminer.concepts.comparison.inputs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonService;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawTableSchemaTest {
    @TempDir Path directory;
    private static final String DOCUMENT = "a".repeat(64);

    @Test
    void commentsAndSelectionProvenanceDoNotChangeStructuralFingerprint() {
        var first = integer("Count", 16, false);
        var commented =
                new TableSchema.SchemaColumn(
                        first.name(),
                        first.kind(),
                        first.bits(),
                        first.unsigned(),
                        1,
                        null,
                        first.id(),
                        first.relation(),
                        true,
                        null,
                        "Changed descriptive comment",
                        "Another annotation");
        var a = create(List.of(first), List.of(actual("Count", "integer")));
        var input = inputs(List.of(commented), List.of(actual("Count", "integer")));
        var second =
                new TableSchema(
                        input.schema().columns(),
                        List.of("ABCD1234"),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.EN_US,
                        "New table comment");
        var b = RawTableSchema.create(replace(input, second, "ABCD1234", DOCUMENT));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertTrue(a.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void schemaMeaningLayoutAndDefinitionChangesCannotShareAdmissionIdentity() {
        var actual = List.of(actual("Count", "integer"));
        var signed = create(List.of(integer("Count", 16, false)), actual);
        var unsigned = create(List.of(integer("Count", 16, true)), actual);
        assertNotEquals(signed.fingerprint(), unsigned.fingerprint());
        var original = inputs(List.of(integer("Count", 16, false)), actual);
        assertNotEquals(
                signed.fingerprint(),
                RawTableSchema.create(replace(original, original.schema(), "ABCD1235", DOCUMENT))
                        .fingerprint());
        assertNotEquals(
                signed.fingerprint(),
                RawTableSchema.create(
                                replace(original, original.schema(), "ABCD1234", "b".repeat(64)))
                        .fingerprint());
        var referenced = reference("Count", "CreatureDisplayInfo");
        assertNotEquals(signed.fingerprint(), create(List.of(referenced), actual).fingerprint());
    }

    @Test
    void fingerprintUsesLengthPrefixesAndRejectsInvalidUnicode() {
        var input =
                inputs(List.of(integer("Count", 16, false)), List.of(actual("Count", "integer")));
        var a =
                new RawTableSchema.Inputs(
                        "ab",
                        input.layout(),
                        "c",
                        input.rowEncoding(),
                        input.integerPolicy(),
                        input.locale(),
                        DOCUMENT,
                        "block",
                        input.schema(),
                        input.columns());
        var b =
                new RawTableSchema.Inputs(
                        "a",
                        input.layout(),
                        "bc",
                        input.rowEncoding(),
                        input.integerPolicy(),
                        input.locale(),
                        DOCUMENT,
                        "block",
                        input.schema(),
                        input.columns());
        assertNotEquals(
                RawTableSchema.create(a).fingerprint(), RawTableSchema.create(b).fingerprint());
        var invalid =
                new RawTableSchema.Inputs(
                        "\ud800",
                        input.layout(),
                        "reader",
                        input.rowEncoding(),
                        input.integerPolicy(),
                        input.locale(),
                        DOCUMENT,
                        "block",
                        input.schema(),
                        input.columns());
        assertThrows(IllegalArgumentException.class, () -> RawTableSchema.create(invalid));
    }

    @Test
    void signedUnsignedAndRecordedReferenceZeroPreserveTheirTypes() {
        var profile =
                create(
                        List.of(
                                integer("Signed", 16, false),
                                integer("Unsigned", 64, true),
                                reference("DisplayID", "CreatureDisplayInfo")),
                        List.of(
                                actual("Signed", "integer"),
                                actual("Unsigned", "integer"),
                                actual("DisplayID", "integer")));
        var row =
                profile.project(
                        row(0, List.of("-32768", "18446744073709551615", "0"), List.of()),
                        "graph-a:0",
                        Map.of());
        assertEquals("00000000000000000000", row.key());
        assertEquals("-32768", row.fields().get("cell.Signed").value());
        assertEquals("18446744073709551615", row.fields().get("cell.Unsigned").value());
        assertEquals(
                ComparisonTypes.ValueKind.REFERENCE, row.fields().get("cell.DisplayID").kind());
        assertEquals("0", row.fields().get("cell.DisplayID").value());
    }

    @Test
    void arraysKeepElementOrderAndRejectFlattenedShapeMismatch() {
        var base = integer("Ranks", 16, true);
        var array =
                new TableSchema.SchemaColumn(
                        base.name(),
                        base.kind(),
                        base.bits(),
                        base.unsigned(),
                        2,
                        null,
                        base.id(),
                        base.relation(),
                        true,
                        null,
                        null,
                        null);
        var profile =
                create(
                        List.of(array),
                        List.of(actual("Ranks[0]", "integer"), actual("Ranks[1]", "integer")));
        var record = profile.project(row(1, List.of("3", "0"), List.of()), "graph:1", Map.of());
        assertEquals("3", record.fields().get("cell.Ranks[0]").value());
        assertEquals("0", record.fields().get("cell.Ranks[1]").value());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        create(
                                List.of(array),
                                List.of(
                                        actual("Ranks[1]", "integer"),
                                        actual("Ranks[0]", "integer"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> profile.project(row(1, List.of("3"), List.of()), "graph:1", Map.of()));
    }

    @Test
    void emptyTextAndMissingIssueChannelRemainDistinctWithoutLosingIntegers() {
        var profile = textAndCount();
        var missing = profile.project(row(1, List.of("", "7"), null), "graph-a:1", Map.of());
        assertEquals(ComparisonTypes.ValueState.UNKNOWN, missing.fields().get("cell.Name").state());
        assertEquals("7", missing.fields().get("cell.Count").value());
        var known = profile.project(row(1, List.of("", "7"), List.of()), "graph-b:1", Map.of());
        assertEquals(
                ComparisonTypes.ValueState.KNOWN_EMPTY, known.fields().get("cell.Name").state());
        var whitespace =
                profile.project(row(1, List.of("  Name\n", "7"), List.of()), "graph-b:1", Map.of());
        assertEquals("  Name\n", whitespace.fields().get("cell.Name").value());
    }

    @Test
    void invalidTextAndUnknownIssueCodesAffectOnlyTheirRecordedColumn() {
        var profile = textAndCount();
        for (String code : List.of("DB2_TEXT_ENCODING_INVALID", "FUTURE_CELL_ISSUE")) {
            var record =
                    profile.project(
                            row(
                                    1,
                                    List.of("", "7"),
                                    List.of(new Db2Types.TextIssue(0, code, "ff"))),
                            "graph:1",
                            Map.of());
            assertEquals(
                    code.equals("DB2_TEXT_ENCODING_INVALID")
                            ? ComparisonTypes.ValueState.INVALID
                            : ComparisonTypes.ValueState.UNKNOWN,
                    record.fields().get("cell.Name").state());
            assertEquals("7", record.fields().get("cell.Count").value());
            assertTrue(record.fields().get("cell.Name").evidence().getFirst().raw().contains(code));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        profile.project(
                                row(
                                        1,
                                        List.of("", "7"),
                                        List.of(
                                                new Db2Types.TextIssue(
                                                        2, "DB2_TEXT_ENCODING_INVALID", "ff"))),
                                "graph:1",
                                Map.of()));
    }

    @Test
    void absentNoninlineRelationshipIsUnknownRatherThanZeroOrEmpty() {
        var base = integer("Parent", 32, true);
        var relation =
                new TableSchema.SchemaColumn(
                        base.name(),
                        base.kind(),
                        base.bits(),
                        base.unsigned(),
                        1,
                        null,
                        base.id(),
                        TableSchema.KeyStorage.NONINLINE,
                        true,
                        null,
                        null,
                        null);
        var profile =
                create(
                        List.of(relation, integer("Count", 16, false)),
                        List.of(actual("Parent", "integer"), actual("Count", "integer")));
        var record =
                profile.project(
                        row(
                                1,
                                List.of("", "8"),
                                List.of(new Db2Types.TextIssue(0, "DB2_RELATION_ABSENT", ""))),
                        "graph:1",
                        Map.of());
        assertEquals(
                ComparisonTypes.ValueState.UNKNOWN, record.fields().get("cell.Parent").state());
        assertEquals("8", record.fields().get("cell.Count").value());
        var noChannel = profile.project(row(1, List.of("0", "8"), null), "graph:1", Map.of());
        assertEquals(
                ComparisonTypes.ValueState.UNKNOWN, noChannel.fields().get("cell.Parent").state());
    }

    @Test
    void float32NegativeZeroAndTinyValueStayExactWhileInvalidCellsSurviveAsInvalid() {
        var column =
                new TableSchema.SchemaColumn(
                        "Value",
                        TableSchema.ColumnKind.FLOAT,
                        null,
                        null,
                        1,
                        null,
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        null,
                        null,
                        null);
        var profile = create(List.of(column), List.of(actual("Value", "float")));
        for (String value : List.of("-0", "0", "1E-45")) {
            assertEquals(
                    value,
                    profile.project(row(1, List.of(value), List.of()), "graph:1", Map.of())
                            .fields()
                            .get("cell.Value")
                            .value());
        }
        for (String value : List.of("NaN", "Infinity", "1.000", " 1")) {
            assertEquals(
                    ComparisonTypes.ValueState.INVALID,
                    profile.project(row(1, List.of(value), List.of()), "graph:1", Map.of())
                            .fields()
                            .get("cell.Value")
                            .state());
        }
        var integers =
                create(List.of(integer("Count", 8, false)), List.of(actual("Count", "integer")));
        for (String value : List.of("128", "-129", "+1", "01", "-0")) {
            assertEquals(
                    ComparisonTypes.ValueState.INVALID,
                    integers.project(row(1, List.of(value), List.of()), "graph:1", Map.of())
                            .fields()
                            .get("cell.Count")
                            .state());
        }
    }

    @Test
    void unverifiedRawAndNarrowedColumnsAreExcludedOnceFromFieldScope() {
        var good = integer("Count", 32, true);
        var bad =
                new TableSchema.SchemaColumn(
                        "Unknown",
                        TableSchema.ColumnKind.INT,
                        32,
                        true,
                        1,
                        null,
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        false,
                        null,
                        null,
                        null);
        var profile =
                create(
                        List.of(good, bad, integer("Raw", 8, true), integer("Narrow", 8, true)),
                        List.of(
                                actual("Count", "integer"),
                                actual("Unknown", "integer"),
                                new Db2Types.Column(
                                        "Raw",
                                        "integer",
                                        false,
                                        new Db2Types.Interpretation(
                                                "raw-physical-integer-v1",
                                                32,
                                                false,
                                                8,
                                                true,
                                                "Warning"),
                                        null),
                                new Db2Types.Column(
                                        "Narrow",
                                        "integer",
                                        false,
                                        null,
                                        new Db2Types.Narrowing(32, 8))));
        assertEquals(1, profile.assessedColumns());
        assertEquals(3, profile.scope().stream().filter(c -> c.exclusion() != null).count());
        var record =
                profile.project(
                        row(1, List.of("1", "2", "3", "4"), List.of()), "graph:1", Map.of());
        assertFalse(record.fields().containsKey("cell.Unknown"));
        assertFalse(record.fields().containsKey("cell.Raw"));
        assertFalse(record.fields().containsKey("cell.Narrow"));
    }

    @Test
    void nativeOversizeLimitAndValueLimitRetainOtherUsableCells() {
        var profile = textAndCount();
        var nativeLimit =
                profile.project(
                        row(1, List.of("", "2"), List.of()),
                        "graph:1",
                        Map.of(0, ComparisonTypes.ValueState.LIMITED));
        assertEquals(
                ComparisonTypes.ValueState.LIMITED, nativeLimit.fields().get("cell.Name").state());
        assertEquals("2", nativeLimit.fields().get("cell.Count").value());
        var tooLong =
                profile.project(
                        row(1, List.of("x".repeat(RawTableSchema.MAX_TEXT + 1), "2"), List.of()),
                        "graph:1",
                        Map.of());
        assertEquals(ComparisonTypes.ValueState.LIMITED, tooLong.fields().get("cell.Name").state());
        assertEquals("2", tooLong.fields().get("cell.Count").value());
    }

    @Test
    void limitedIssueChannelCannotCertifyTextCellsWithoutDiscardingKnownIntegers() {
        var profile = textAndCount();
        var record =
                profile.project(
                        row(1, List.of("Name", "7"), List.of()), "graph:1", Map.of(), false);
        assertEquals(ComparisonTypes.ValueState.UNKNOWN, record.fields().get("cell.Name").state());
        assertEquals("7", record.fields().get("cell.Count").value());
        var invalid =
                profile.project(
                        row(
                                1,
                                List.of("", "7"),
                                List.of(
                                        new Db2Types.TextIssue(
                                                0, "DB2_TEXT_ENCODING_INVALID", "ff"))),
                        "graph:1",
                        Map.of(),
                        false);
        assertEquals(ComparisonTypes.ValueState.INVALID, invalid.fields().get("cell.Name").state());
    }

    @Test
    void copyMetadataPreservesOwnIdAndKnownNoCopySource() {
        var profile = textAndCount();
        var ordinary =
                profile.project(row(7, List.of("Name", "1"), List.of()), "graph:7", Map.of());
        assertEquals(
                ComparisonTypes.ValueState.KNOWN_EMPTY,
                ordinary.fields().get("record.copySource").state());
        var copy =
                profile.project(
                        new Db2Types.Row(9, true, 7L, List.of("Name", "1"), List.of()),
                        "graph:9",
                        Map.of());
        assertEquals("00000000000000000009", copy.key());
        assertEquals("7", copy.fields().get("record.copySource").value());
        assertEquals(
                ComparisonTypes.Aspect.INTERPRETATION, copy.fields().get("record.isCopy").aspect());
    }

    @Test
    void endpointOnlyEvidenceAndKnownNoCopyMetadataDoNotProduceFalseFindings() throws Exception {
        var profile = textAndCount();
        var before =
                profile.project(
                        row(1, List.of("Name", "1"), List.of()), "different-graph-a:1", Map.of());
        var after =
                profile.project(
                        row(1, List.of("Name", "1"), List.of()), "different-graph-b:1", Map.of());
        try (var service = new ComparisonService(directory)) {
            var progress = service.start(input("a", before), input("b", after)).join();
            assertEquals(ComparisonTypes.RunState.COMPLETED, progress.state());
            assertEquals(1, progress.observed());
            assertTrue(service.page(0, 100).join().rows().isEmpty());
        }
    }

    private static ComparisonInput input(String identity, ComparisonTypes.Record row) {
        var descriptor =
                new ComparisonTypes.Descriptor(
                        identity, "wowf", "enUS", "raw-table:1", "test", Map.of());
        return new ComparisonInput() {
            public ComparisonTypes.Descriptor descriptor() {
                return descriptor;
            }

            public CompletableFuture<ComparisonTypes.InputPage> read(
                    String cursor, int limit, Cancellation token) {
                return CompletableFuture.completedFuture(
                        new ComparisonTypes.InputPage(
                                identity,
                                List.of(row),
                                null,
                                new ComparisonTypes.Coverage(
                                        ComparisonTypes.Completeness.UNKNOWN, "test", List.of())));
            }

            public CompletableFuture<Void> closeAsync() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static RawTableSchema.Profile textAndCount() {
        var text =
                new TableSchema.SchemaColumn(
                        "Name",
                        TableSchema.ColumnKind.STRING,
                        null,
                        null,
                        1,
                        null,
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        null,
                        null,
                        null);
        return create(
                List.of(text, integer("Count", 16, false)),
                List.of(actual("Name", "string"), actual("Count", "integer")));
    }

    private static TableSchema.SchemaColumn integer(String name, int bits, boolean unsigned) {
        return new TableSchema.SchemaColumn(
                name,
                TableSchema.ColumnKind.INT,
                bits,
                unsigned,
                1,
                null,
                TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                true,
                null,
                null,
                null);
    }

    private static TableSchema.SchemaColumn reference(String name, String table) {
        return new TableSchema.SchemaColumn(
                name,
                TableSchema.ColumnKind.INT,
                16,
                false,
                1,
                new TableSchema.ForeignKey(table, "ID"),
                TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                true,
                null,
                null,
                null);
    }

    private static Db2Types.Column actual(String name, String kind) {
        return new Db2Types.Column(name, kind, false, null, null);
    }

    private static Db2Types.Row row(long id, List<String> values, List<Db2Types.TextIssue> issues) {
        return new Db2Types.Row(id, false, null, values, issues);
    }

    private static RawTableSchema.Inputs inputs(
            List<TableSchema.SchemaColumn> columns, List<Db2Types.Column> actual) {
        return new RawTableSchema.Inputs(
                "WDC5",
                "ABCD1234",
                "reader-v1",
                "row-v2",
                "integers-v1",
                "enUS",
                DOCUMENT,
                "selected-block-v1",
                new TableSchema(
                        columns,
                        List.of("ABCD1234"),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.EN_US,
                        null),
                actual);
    }

    private static RawTableSchema.Profile create(
            List<TableSchema.SchemaColumn> columns, List<Db2Types.Column> actual) {
        return RawTableSchema.create(inputs(columns, actual));
    }

    private static RawTableSchema.Inputs replace(
            RawTableSchema.Inputs input, TableSchema schema, String layout, String document) {
        return new RawTableSchema.Inputs(
                input.format(),
                layout,
                input.readerRevision(),
                input.rowEncoding(),
                input.integerPolicy(),
                input.locale(),
                document,
                input.blockIdentity(),
                schema,
                input.columns());
    }
}
