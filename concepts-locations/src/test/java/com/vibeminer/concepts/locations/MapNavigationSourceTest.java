package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class MapNavigationSourceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private static final class Fixture {
        int active, opens, closes, reads;
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
            var receipt = receipt(name, changed && opens > 2 ? "d" : "c", unknownIssues);
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
            String name, String key, boolean unknownIssues) {
        boolean maps = name.equals("UiMap");
        String layout = maps ? "DB51D55F" : "C9CC8DFB";
        long count = maps ? 2 : 1;
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
        var evidence =
                new CapturedRowsTypes.IssueChannelEvidence(
                        CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                        "native-row-issues-v1",
                        "java-db2-native-producer-v1",
                        key.repeat(32),
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        "d".repeat(64));
        return new CapturedRowsTypes.Receipt(
                BUILD,
                "enUS",
                entry,
                schema,
                columns,
                "a".repeat(64),
                "b".repeat(64),
                key.repeat(32),
                "source",
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
        assertEquals(3, f.reads);
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
}
