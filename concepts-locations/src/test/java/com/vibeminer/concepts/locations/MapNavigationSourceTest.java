package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class MapNavigationSourceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private static final class Fixture {
        int active, opens, closes, reads, eofReads;
        boolean emptyMaps, emptyAssignments, boundedUnavailable, badInitialEof, badFinalEof;
        UnaryOperator<CapturedRowsTypes.Receipt> initialEdit = UnaryOperator.identity();
        UnaryOperator<CapturedRowsTypes.Receipt> finalEdit = UnaryOperator.identity();
        boolean changed, unsupported, limit, borrowedClosed, unknownIssues;
        boolean assignmentUnavailable, badParent, badFlags, badAssignment, limitedParent;
        Cancellation cancelOnRead;

        BuildTables tables() {
            return (BuildTables)
                    java.lang.reflect.Proxy.newProxyInstance(
                            BuildTables.class.getClassLoader(),
                            new Class<?>[] {BuildTables.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "build" -> BUILD;
                                        case "locale" -> "enUS";
                                        case "closeAsync" -> {
                                            borrowedClosed = true;
                                            yield CompletableFuture.completedFuture(null);
                                        }
                                        case "capturePreparedLocal" ->
                                                capture(((TableTypes.Ref) args[0]).name());
                                        default ->
                                                throw new AssertionError(
                                                        "Unbounded/unexpected source call: "
                                                                + method.getName());
                                    });
        }

        CompletableFuture<CapturedRowsTypes.Capture> capture(String name) {
            opens++;
            if (unsupported || assignmentUnavailable && name.equals("UiMapAssignment")) {
                return CompletableFuture.completedFuture(CapturedRowsTypes.Capture.unsupported());
            }
            assertEquals(0, active++);
            boolean finalCapture = opens > 2;
            var base =
                    receipt(
                            name,
                            changed && finalCapture ? "d" : "c",
                            unknownIssues,
                            name.equals("UiMap") ? emptyMaps : emptyAssignments);
            var receipt = (finalCapture ? finalEdit : initialEdit).apply(base);
            CapturedRows rows =
                    new CapturedRows() {
                        boolean closed;

                        public CapturedRowsTypes.Receipt receipt() {
                            return receipt;
                        }

                        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                                long n, int count, Cancellation t) {
                            throw new AssertionError("Legacy row read");
                        }

                        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                                long n,
                                int count,
                                CapturedRowsTypes.ReadLimits limits,
                                Cancellation t) {
                            assertEquals(MapNavigationSource.LIMITS, limits);
                            assertEquals(1, count);
                            reads++;
                            if (boundedUnavailable) {
                                return CompletableFuture.failedFuture(
                                        new UnsupportedOperationException("strict unavailable"));
                            }
                            if (cancelOnRead != null) {
                                cancelOnRead.cancel();
                            }
                            t.check();
                            if (limit) {
                                return CompletableFuture.completedFuture(
                                        new CapturedRowsTypes.Page(
                                                receipt.graphIdentity(),
                                                n,
                                                CapturedRowsTypes.ReadState.LIMIT_EXCEEDED,
                                                List.of(),
                                                null,
                                                "LIMIT",
                                                null));
                            }
                            if (n == receipt.rowCount()) {
                                eofReads++;
                                var issues =
                                        (finalCapture ? badFinalEof : badInitialEof)
                                                ? List.of(
                                                        new CapturedRowsTypes.CellIssue(
                                                                n,
                                                                0,
                                                                CapturedRowsTypes.CellState.LIMITED,
                                                                "EOF_LIMIT"))
                                                : List.<CapturedRowsTypes.CellIssue>of();
                                return CompletableFuture.completedFuture(
                                        new CapturedRowsTypes.Page(
                                                receipt.graphIdentity(),
                                                n,
                                                CapturedRowsTypes.ReadState.READ,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                issues));
                            }
                            boolean maps = name.equals("UiMap");
                            var values =
                                    maps
                                            ? List.of(
                                                    "Map " + n,
                                                    badParent && n == 1 ? "bad" : "0",
                                                    "0",
                                                    "3",
                                                    badFlags ? "bad" : "0",
                                                    "0")
                                            : List.of(
                                                    "0",
                                                    "0",
                                                    "1",
                                                    "1",
                                                    "0",
                                                    "0",
                                                    "0",
                                                    "100",
                                                    "100",
                                                    "1",
                                                    badAssignment ? "bad" : "1",
                                                    "0",
                                                    "0",
                                                    "0");
                            var row = new Db2Types.Row(n + 1, false, null, values, List.of());
                            var cellIssues =
                                    limitedParent && maps && n == 1
                                            ? List.of(
                                                    new CapturedRowsTypes.CellIssue(
                                                            n,
                                                            1,
                                                            CapturedRowsTypes.CellState.LIMITED,
                                                            "LIMIT"))
                                            : List.<CapturedRowsTypes.CellIssue>of();
                            return CompletableFuture.completedFuture(
                                    new CapturedRowsTypes.Page(
                                            receipt.graphIdentity(),
                                            n,
                                            CapturedRowsTypes.ReadState.READ,
                                            List.of(new TableTypes.Row(n, row)),
                                            n + 1 < receipt.rowCount() ? n + 1 : null,
                                            null,
                                            null,
                                            cellIssues));
                        }

                        public CompletableFuture<Void> closeAsync() {
                            if (!closed) {
                                closed = true;
                                active--;
                                closes++;
                            }
                            return CompletableFuture.completedFuture(null);
                        }
                    };
            return CompletableFuture.completedFuture(
                    new CapturedRowsTypes.Capture(
                            CapturedRowsTypes.CaptureState.CAPTURED, rows, null, null));
        }
    }

    private static CapturedRowsTypes.Receipt receipt(
            String name, String key, boolean unknownIssues, boolean empty) {
        boolean maps = name.equals("UiMap");
        String layout = maps ? "DB51D55F" : "C9CC8DFB";
        long count = empty ? 0 : maps ? 2 : 1;
        var columns = new ArrayList<Db2Types.Column>();
        var fields = new ArrayList<TableSchema.SchemaColumn>();
        if (maps) {
            field(columns, fields, "Name_lang", "string", 1, null);
            field(columns, fields, "ParentUiMapID", "integer", 1, "UiMap");
            field(columns, fields, "System", "integer", 1, null);
            field(columns, fields, "Type", "integer", 1, null);
            field(columns, fields, "Flags", "integer", 1, null);
            field(columns, fields, "VisibilityPlayerConditionID", "integer", 1, null);
        } else {
            field(columns, fields, "UiMin", "float", 2, null);
            field(columns, fields, "UiMax", "float", 2, null);
            field(columns, fields, "Region", "float", 6, null);
            field(columns, fields, "UiMapID", "integer", 1, "UiMap");
            field(columns, fields, "MapID", "integer", 1, "Map");
            field(columns, fields, "WMODoodadPlacementID", "integer", 1, null);
            field(columns, fields, "WMOGroupID", "integer", 1, null);
        }
        var schema =
                new TableSchema(
                        fields,
                        List.of(layout),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.parse("enUS"),
                        null);
        var entry =
                new TableTypes.Entry(
                        maps ? 1 : 2,
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(count, count, 0, 0, "fixture", layout),
                        key.repeat(32));
        var nativeTuple =
                NativeIssueIdentity.tuple(
                        GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                        "native-row-issues-v1",
                        "java-db2-native-producer-v1",
                        "enUS",
                        key.repeat(32),
                        "b".repeat(64),
                        "c".repeat(64),
                        "reader",
                        "rows",
                        null);
        var evidence =
                new CapturedRowsTypes.IssueChannelEvidence(
                        CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                        "native-row-issues-v1",
                        "java-db2-native-producer-v1",
                        key.repeat(32),
                        sha(nativeTuple),
                        "b".repeat(64),
                        "c".repeat(64),
                        NativeIssueIdentity.columns(columns));
        return new CapturedRowsTypes.Receipt(
                BUILD,
                "enUS",
                entry,
                schema,
                columns,
                "a".repeat(64),
                "b".repeat(64),
                key.repeat(32),
                "native-tuple-hex=" + HexFormat.of().formatHex(nativeTuple),
                "c".repeat(64),
                "reader",
                "rows",
                null,
                count,
                new Db2Types.Header(1, "00000000", layout, count, 0, 1, columns, "fixture"),
                new Db2Types.Completion(count, count, 0, 0, 0, 0, "full", 1, 0),
                List.of(),
                List.of(),
                List.of(),
                CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                CapturedRowsTypes.Membership.NOT_CERTIFIED,
                unknownIssues ? CapturedRowsTypes.IssueChannelEvidence.unknown() : evidence);
    }

    private static void field(
            List<Db2Types.Column> columns,
            List<TableSchema.SchemaColumn> fields,
            String name,
            String kind,
            int count,
            String target) {
        for (int i = 0; i < count; i++) {
            columns.add(
                    new Db2Types.Column(
                            count == 1 ? name : name + "[" + i + "]", kind, false, null, null));
        }
        var type =
                switch (kind) {
                    case "string" -> TableSchema.ColumnKind.LOCSTRING;
                    case "float" -> TableSchema.ColumnKind.FLOAT;
                    default -> TableSchema.ColumnKind.INT;
                };
        fields.add(
                new TableSchema.SchemaColumn(
                        name,
                        type,
                        kind.equals("integer") ? 32 : null,
                        kind.equals("integer") ? true : null,
                        count,
                        target == null ? null : new TableSchema.ForeignKey(target, "ID"),
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        WowLocale.parse("enUS"),
                        null,
                        null));
    }

    @Test
    void strictProjectionRevalidatesEverySourceAndClosesSequentialCaptures() {
        var f = new Fixture();
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.READY, result.state());
        assertEquals(2, result.graph().nodes().size());
        assertEquals(4, f.opens);
        assertEquals(4, f.closes);
        assertEquals(7, f.reads);
        assertEquals(4, f.eofReads);
        assertEquals(0, f.active);
        assertFalse(f.borrowedClosed);
    }

    @Test
    void physicallyReadyMalformedOptionalFieldsPropagatePartial() {
        var f = new Fixture();
        f.badParent = true;
        f.badFlags = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.PARTIAL, result.state());
        assertEquals(2, result.graph().nodes().size());
        assertEquals(MapNavigationTypes.ReadState.PARTIAL, result.graph().scope().maps().state());
        assertEquals(
                MapNavigationGraph.RegionState.LIMITED,
                result.graph()
                        .regions(new MapNavigationTypes.UiMapId(1), 100, () -> false)
                        .state());
    }

    @Test
    void limitedParentPreservesDirectChoiceAndPartialCompleteness() {
        var f = new Fixture();
        f.limitedParent = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.PARTIAL, result.state());
        assertEquals(2, result.graph().nodes().size());
        assertEquals(
                MapNavigationGraph.HierarchyState.UNKNOWN_PARENT,
                result.graph().hierarchy(new MapNavigationTypes.UiMapId(2)).state());
    }

    @Test
    void malformedAssignmentPreservesNamesAndMarksProjectionPartial() {
        var f = new Fixture();
        f.badAssignment = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.PARTIAL, result.state());
        assertEquals(2, result.graph().nodes().size());
        assertEquals(
                MapNavigationTypes.ReadState.PARTIAL, result.graph().scope().assignments().state());
    }

    @Test
    void sourceChangesRefusePublication() {
        var f = new Fixture();
        f.changed = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.STALE, result.state());
        assertNull(result.graph());
        assertEquals(0, f.active);
    }

    @Test
    void strictLimitRefusesWithoutInvalidatingOrLeaking() {
        var f = new Fixture();
        f.limit = true;
        assertEquals(
                MapNavigationSourceTypes.State.LIMIT_EXCEEDED,
                MapNavigationSource.read(f.tables(), Cancellation.NONE).state());
        assertEquals(0, f.active);
        f.limit = false;
        assertEquals(
                MapNavigationSourceTypes.State.READY,
                MapNavigationSource.read(f.tables(), Cancellation.NONE).state());
    }

    @Test
    void customUnsupportedHasNoFallback() {
        var f = new Fixture();
        f.unsupported = true;
        assertEquals(
                MapNavigationSourceTypes.State.UNSUPPORTED,
                MapNavigationSource.read(f.tables(), Cancellation.NONE).state());
        assertEquals(0, f.reads);
    }

    @Test
    void unavailableAssignmentsPreserveDirectNavigation() {
        var f = new Fixture();
        f.assignmentUnavailable = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.PARTIAL, result.state());
        assertEquals(2, result.graph().nodes().size());
    }

    @Test
    void cancellationDrainsCapture() {
        var f = new Fixture();
        var token = new Cancellation();
        f.cancelOnRead = token;
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> MapNavigationSource.read(f.tables(), token));
        assertEquals(0, f.active);
        assertFalse(f.borrowedClosed);
    }

    @Test
    void legacyUnknownIssueChannelIsExplicitlyUnsupported() {
        var f = new Fixture();
        f.unknownIssues = true;
        assertEquals(
                MapNavigationSourceTypes.State.UNSUPPORTED,
                MapNavigationSource.read(f.tables(), Cancellation.NONE).state());
        assertEquals(0, f.reads);
        assertEquals(0, f.active);
    }

    private static String sha(byte[] input) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class ReceiptEdit {
        final CapturedRowsTypes.Receipt original;
        String graph, source;
        List<Db2Types.Column> columns;
        TableSchema schema;
        Db2Types.Header header;
        List<Db2Types.Section> sections;
        List<String> holes;
        List<CapturedRowsTypes.KeyFingerprint> keys;

        ReceiptEdit(CapturedRowsTypes.Receipt r) {
            original = r;
            graph = r.graphIdentity();
            source = r.sourceIdentity();
            columns = r.columns();
            schema = r.schema();
            header = r.header();
            sections = r.sections();
            holes = r.holeKeys();
            keys = r.effectiveKeys();
        }

        CapturedRowsTypes.Receipt build() {
            var r = original;
            return new CapturedRowsTypes.Receipt(
                    r.build(),
                    r.locale(),
                    r.table(),
                    schema,
                    columns,
                    r.definitionSha256(),
                    r.blockIdentity(),
                    graph,
                    source,
                    r.readerOptions(),
                    r.readerRevision(),
                    r.rowEncoding(),
                    r.encodingScope(),
                    r.rowCount(),
                    header,
                    r.completion(),
                    sections,
                    holes,
                    keys,
                    r.retention(),
                    r.membership(),
                    r.issueChannelEvidence());
        }
    }

    private static CapturedRowsTypes.Receipt edit(
            CapturedRowsTypes.Receipt r, Consumer<ReceiptEdit> change) {
        var builder = new ReceiptEdit(r);
        change.accept(builder);
        return builder.build();
    }

    @Test
    void nativeCertificateCannotBeBorrowedFromAnotherGraph() {
        var f = new Fixture();
        f.initialEdit = r -> edit(r, b -> b.graph = "d".repeat(32));
        f.finalEdit = f.initialEdit;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.UNSUPPORTED, result.state());
        assertNull(result.graph());
        assertEquals(0, f.reads);
        assertEquals(0, f.active);
    }

    @Test
    void nativeCertificateCannotBeBorrowedFromAnotherColumnStream() {
        var f = new Fixture();
        f.initialEdit =
                r ->
                        edit(
                                r,
                                b -> {
                                    var columns = new ArrayList<>(r.columns());
                                    var c = columns.getFirst();
                                    columns.set(
                                            0,
                                            new Db2Types.Column(
                                                    c.name() + "Other",
                                                    c.kind(),
                                                    c.isId(),
                                                    c.interpretation(),
                                                    c.narrowing()));
                                    b.columns = columns;
                                    b.header =
                                            new Db2Types.Header(
                                                    r.header().schemaVersion(),
                                                    r.header().tableHash(),
                                                    r.header().layoutHash(),
                                                    r.header().declaredRecords(),
                                                    r.header().declaredCopyRecords(),
                                                    r.header().recordSize(),
                                                    columns,
                                                    r.header().format());
                                });
        f.finalEdit = f.initialEdit;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.UNSUPPORTED, result.state());
        assertNull(result.graph());
        assertEquals(0, f.reads);
        assertEquals(0, f.active);
    }

    @Test
    void sourceTupleMustMatchNativeProof() {
        var f = new Fixture();
        f.initialEdit = r -> edit(r, b -> b.source = "native-tuple-hex=00");
        f.finalEdit = f.initialEdit;
        assertEquals(
                MapNavigationSourceTypes.State.UNSUPPORTED,
                MapNavigationSource.read(f.tables(), Cancellation.NONE).state());
        assertEquals(0, f.reads);
        assertEquals(0, f.active);
    }

    @Test
    void finalHeaderSectionHoleAndEffectiveKeyChangesRefusePublication() {
        List<Consumer<ReceiptEdit>> changes =
                List.of(
                        b -> {
                            var h = b.header;
                            b.header =
                                    new Db2Types.Header(
                                            h.schemaVersion(),
                                            h.tableHash(),
                                            h.layoutHash(),
                                            h.declaredRecords(),
                                            h.declaredCopyRecords(),
                                            h.recordSize() + 1,
                                            h.columns(),
                                            h.format());
                        },
                        b ->
                                b.sections =
                                        List.of(
                                                new Db2Types.Section(
                                                        0, 2, 0, "", false, 0, 0, false)),
                        b -> b.holes = List.of("0123456789abcdef"),
                        b ->
                                b.keys =
                                        List.of(
                                                new CapturedRowsTypes.KeyFingerprint(
                                                        "0123456789abcdef", "a".repeat(64))));
        for (var change : changes) {
            var f = new Fixture();
            f.finalEdit = r -> edit(r, change);
            var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
            assertEquals(MapNavigationSourceTypes.State.STALE, result.state());
            assertNull(result.graph());
            assertEquals(0, f.active);
        }
    }

    @Test
    void emptyNativeTablesStillRequireBoundedEofCapability() {
        var f = new Fixture();
        f.emptyMaps = true;
        f.emptyAssignments = true;
        f.boundedUnavailable = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.UNSUPPORTED, result.state());
        assertNull(result.graph());
        assertEquals(1, f.reads);
        assertEquals(0, f.active);
    }

    @Test
    void emptyNativeTablesProbeInitialAndFreshFinalEof() {
        var f = new Fixture();
        f.emptyMaps = true;
        f.emptyAssignments = true;
        var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
        assertEquals(MapNavigationSourceTypes.State.READY, result.state());
        assertEquals(0, result.graph().nodes().size());
        assertEquals(4, f.eofReads);
        assertEquals(4, f.reads);
        assertEquals(4, f.closes);
    }

    @Test
    void initialAndFinalEofLimitationsAreNotEmptyReadyTables() {
        for (boolean finalEof : List.of(false, true)) {
            var f = new Fixture();
            f.emptyMaps = true;
            f.emptyAssignments = true;
            if (finalEof) {
                f.badFinalEof = true;
            } else {
                f.badInitialEof = true;
            }
            var result = MapNavigationSource.read(f.tables(), Cancellation.NONE);
            assertEquals(MapNavigationSourceTypes.State.STALE, result.state());
            assertNull(result.graph());
            assertEquals(0, f.active);
        }
    }

    @Test
    void metadataScalarsAreBoundBeforeNativeHashHelpers() {
        var r = receipt("UiMap", "c", false, false);
        var bad =
                edit(
                        r,
                        b -> {
                            var columns = new ArrayList<>(r.columns());
                            var c = columns.getFirst();
                            columns.set(
                                    0,
                                    new Db2Types.Column(
                                            "x".repeat(129),
                                            c.kind(),
                                            c.isId(),
                                            c.interpretation(),
                                            c.narrowing()));
                            b.columns = columns;
                        });
        var refusal =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> MapCaptureIdentity.validate(bad));
        assertTrue(refusal.getMessage().contains("Oversized scalar"));
    }

    private static void schemaComment(ReceiptEdit b, int count) {
        var fields = new ArrayList<>(b.schema.columns());
        var c = fields.getFirst();
        fields.set(
                0,
                new TableSchema.SchemaColumn(
                        c.name(),
                        c.kind(),
                        c.bits(),
                        c.unsigned(),
                        c.arrayLength(),
                        c.foreignKey(),
                        c.id(),
                        c.relation(),
                        c.verifiedName(),
                        c.locale(),
                        "x".repeat(count),
                        c.fieldComment()));
        b.schema =
                new TableSchema(
                        fields,
                        b.schema.layoutHashes(),
                        b.schema.match(),
                        b.schema.locale(),
                        b.schema.comment());
    }

    @Test
    void boundedLongSchemaCommentsRemainUsableWithoutChangingAggregateCeiling() {
        var f = new Fixture();
        f.initialEdit = r -> edit(r, b -> schemaComment(b, 500));
        f.finalEdit = f.initialEdit;
        assertEquals(
                MapNavigationSourceTypes.State.READY,
                MapNavigationSource.read(f.tables(), Cancellation.NONE).state());
        var r = receipt("UiMap", "c", false, false);
        var tooLong = edit(r, b -> schemaComment(b, 1025));
        var refusal =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> MapCaptureIdentity.validate(tooLong));
        assertTrue(refusal.getMessage().contains("Oversized scalar"));
        var aggregate =
                edit(
                        r,
                        b -> {
                            schemaComment(b, 1024);
                            var field = b.schema.columns().getFirst();
                            b.schema =
                                    new TableSchema(
                                            java.util.Collections.nCopies(128, field),
                                            b.schema.layoutHashes(),
                                            b.schema.match(),
                                            b.schema.locale(),
                                            null);
                        });
        var totalRefusal =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> MapCaptureIdentity.validate(aggregate));
        assertTrue(totalRefusal.getMessage().contains("aggregate bound"));
    }
}
