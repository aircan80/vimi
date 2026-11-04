package com.vibeminer.concepts.locations;

import static com.vibeminer.concepts.locations.MapNavigationSourceTypes.Result;
import static com.vibeminer.concepts.locations.MapNavigationSourceTypes.State;
import static com.vibeminer.concepts.locations.MapNavigationSourceTypes.TableStamp;
import static com.vibeminer.concepts.locations.MapNavigationTypes.Assignment;
import static com.vibeminer.concepts.locations.MapNavigationTypes.MAX_ASSIGNMENTS;
import static com.vibeminer.concepts.locations.MapNavigationTypes.MAX_NODES;
import static com.vibeminer.concepts.locations.MapNavigationTypes.Node;
import static com.vibeminer.concepts.locations.MapNavigationTypes.Parent;
import static com.vibeminer.concepts.locations.MapNavigationTypes.ReadState;
import static com.vibeminer.concepts.locations.MapNavigationTypes.Rect;
import static com.vibeminer.concepts.locations.MapNavigationTypes.RowSource;
import static com.vibeminer.concepts.locations.MapNavigationTypes.Scope;
import static com.vibeminer.concepts.locations.MapNavigationTypes.Table;
import static com.vibeminer.concepts.locations.MapNavigationTypes.TableRead;
import static com.vibeminer.concepts.locations.MapNavigationTypes.UiMapId;
import static com.vibeminer.concepts.locations.MapNavigationTypes.WorldMapId;
import static com.vibeminer.concepts.locations.MapNavigationTypes.WorldRect;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Single-worker strict capture projection. No preparation, generic pages, images or fallback. */
final class MapNavigationSource {
    static final CapturedRowsTypes.ReadLimits LIMITS =
            new CapturedRowsTypes.ReadLimits(256 << 10, 128 << 10, 64 << 10, 128, 128, 64 << 10);
    // Prepared-reader metadata is separately owned by formats-tables (64MiB manifest/16MiB JSON).
    // Exactly one capture is open; raw transient <=4MiB logical, projected stage <=16MiB.
    private static final long MAX_STAGE = 16L << 20;
    private final BuildTables tables;
    private final Cancellation token;
    private final List<String> issues = new ArrayList<>();
    private final List<TableStamp> stamps = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private final List<Assignment> assignments = new ArrayList<>();
    private boolean mapPartial, assignmentPartial;
    private long charged;

    private MapNavigationSource(BuildTables tables, Cancellation token) {
        this.tables = tables;
        this.token = token;
    }

    static Result read(BuildTables tables, Cancellation token) {
        var source = new MapNavigationSource(tables, token);
        try {
            return source.read();
        } catch (Refusal refusal) {
            return new Result(refusal.state, null, List.of(), List.of(refusal.code));
        } catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof java.util.concurrent.CancellationException c) {
                throw c;
            }
            if (failure.getCause() instanceof UnsupportedOperationException) {
                return Result.unsupported();
            }
            return new Result(State.UNAVAILABLE, null, List.of(), List.of("CAPTURE_FAILED"));
        }
    }

    private Result read() {
        scan("UiMap", MAX_NODES);
        try {
            scan("UiMapAssignment", MAX_ASSIGNMENTS);
        } catch (Refusal refusal) {
            if (refusal.state == State.STALE || refusal.state == State.LIMIT_EXCEEDED) {
                throw refusal;
            }
            assignmentPartial = true;
            issue("ASSIGNMENT_UNAVAILABLE");
            assignments.clear();
        }
        // Reopen every positive dependency only after all scans; a changed source never publishes.
        for (var expected : stamps) {
            token.check();
            var capture =
                    tables.capturePreparedLocal(TableTypes.Ref.forName(expected.table()), token)
                            .join();
            if (capture.state() != CapturedRowsTypes.CaptureState.CAPTURED) {
                refuse(State.STALE, "SOURCE_CHANGED");
            }
            try (var rows = capture.rows()) {
                var receipt = rows.receipt();
                if (!stamp(expected.table(), receipt).equals(expected)) {
                    refuse(State.STALE, "SOURCE_CHANGED");
                }
                probeEof(rows, receipt);
            }
        }
        token.check();
        var mapRead = tableRead(stamps.getFirst(), Table.UI_MAP, mapPartial);
        var assignmentStamp =
                stamps.stream().filter(s -> s.table().equals("UiMapAssignment")).findFirst();
        var assignmentRead =
                assignmentStamp
                        .map(s -> tableRead(s, Table.ASSIGNMENT, assignmentPartial))
                        .orElseGet(
                                () ->
                                        new TableRead(
                                                Table.ASSIGNMENT,
                                                null,
                                                null,
                                                ReadState.UNAVAILABLE));
        var finalNodes = new ArrayList<Node>();
        for (var n : nodes) {
            token.check();
            finalNodes.add(
                    new Node(
                            n.id(),
                            n.name(),
                            n.parent(),
                            n.system(),
                            n.type(),
                            n.flags(),
                            n.visibilityConditionId(),
                            new RowSource(mapRead, n.source().rowId())));
        }
        var finalAssignments = new ArrayList<Assignment>();
        for (var a : assignments) {
            token.check();
            finalAssignments.add(
                    new Assignment(
                            a.uiMap(),
                            a.worldMap(),
                            a.world(),
                            a.ui(),
                            a.unscoped(),
                            new RowSource(assignmentRead, a.source().rowId())));
        }
        var graph =
                new MapNavigationGraph(
                        new Scope(tables.build(), tables.locale(), mapRead, assignmentRead),
                        finalNodes,
                        finalAssignments,
                        token::isCanceled);
        token.check();
        return new Result(
                mapPartial || assignmentPartial ? State.PARTIAL : State.READY,
                graph,
                stamps,
                issues);
    }

    private void scan(String name, int maximum) {
        token.check();
        var capture = tables.capturePreparedLocal(TableTypes.Ref.forName(name), token).join();
        if (capture.state() == CapturedRowsTypes.CaptureState.CANCELED) {
            throw new java.util.concurrent.CancellationException();
        }
        if (capture.state() != CapturedRowsTypes.CaptureState.CAPTURED) {
            refuse(
                    capture.state() == CapturedRowsTypes.CaptureState.UNSUPPORTED
                            ? State.UNSUPPORTED
                            : State.UNAVAILABLE,
                    "CAPTURE_UNAVAILABLE");
        }
        try (var rows = capture.rows()) {
            var receipt = rows.receipt();
            var stamp = stamp(name, receipt);
            if (receipt.rowCount() > maximum) {
                refuse(State.LIMIT_EXCEEDED, "ROW_COUNT_LIMIT");
            }
            var data = MapTable.captured(name, receipt);
            if (name.equals("UiMap")) {
                data.reference("ParentUiMapID", "UiMap");
            } else {
                data.reference("UiMapID", "UiMap");
                data.reference("MapID", "Map");
            }
            boolean partial = receipt.table().state() != TableTypes.State.READY;
            var read =
                    tableRead(
                            stamp, name.equals("UiMap") ? Table.UI_MAP : Table.ASSIGNMENT, partial);
            for (long ordinal = 0; ordinal < receipt.rowCount(); ordinal++) {
                token.check();
                var page = rows.readOrdinalPage(ordinal, 1, LIMITS, token).join();
                if (page.state() == CapturedRowsTypes.ReadState.CANCELED) {
                    throw new java.util.concurrent.CancellationException();
                }
                if (page.state() == CapturedRowsTypes.ReadState.LIMIT_EXCEEDED) {
                    refuse(State.LIMIT_EXCEEDED, "ROW_READ_LIMIT");
                }
                if (page.state() != CapturedRowsTypes.ReadState.READ
                        || !page.identity().equals(stamp.graphIdentity())
                        || page.firstOrdinal() != ordinal
                        || page.rows().size() != 1
                        || page.rows().getFirst().ordinal() != ordinal
                        || !Objects.equals(
                                page.nextOrdinal(),
                                ordinal + 1 < receipt.rowCount() ? ordinal + 1 : null)) {
                    refuse(State.STALE, "CAPTURE_CHANGED");
                }
                var row = page.rows().getFirst().row();
                if (row.textIssues() == null) {
                    refuse(State.UNSUPPORTED, "UNKNOWN_ISSUE_CHANNEL");
                }
                if (!page.cellIssues().isEmpty()) {
                    partial = true;
                    issue("LIMITED_CELL");
                    var textIssues = new ArrayList<>(row.textIssues());
                    for (var limitation : page.cellIssues()) {
                        if (limitation.columnIndex() < 0) {
                            for (int i = 0; i < row.values().size(); i++) {
                                textIssues.add(new Db2Types.TextIssue(i, "LIMITED", ""));
                            }
                        } else {
                            textIssues.add(
                                    new Db2Types.TextIssue(
                                            limitation.columnIndex(), "LIMITED", ""));
                        }
                    }
                    row =
                            new Db2Types.Row(
                                    row.id(),
                                    row.isCopy(),
                                    row.copySourceId(),
                                    row.values(),
                                    textIssues);
                }
                if (name.equals("UiMap")) {
                    var node = node(data, row, read);
                    charge(2048L + (node.name() == null ? 0 : 8L * node.name().length()));
                    nodes.add(node);
                } else {
                    try {
                        var assignment = assignment(data, row, read);
                        charge(1536);
                        assignments.add(assignment);
                    } catch (MapTable.Projection | IllegalArgumentException failure) {
                        partial = true;
                        issue("INVALID_ASSIGNMENT");
                    }
                }
            }
            probeEof(rows, receipt);
            stamps.add(stamp);
            if (name.equals("UiMap")) {
                mapPartial |= partial;
            } else {
                assignmentPartial |= partial;
            }
        } catch (MapTable.Projection failure) {
            refuse(State.UNSUPPORTED, "UNQUALIFIED_SCHEMA");
        }
    }

    private Node node(MapTable data, Db2Types.Row row, TableRead read) {
        var id = new UiMapId(row.id());
        String name = null;
        Parent parent = Parent.unknown();
        Integer system = null, type = null;
        Long flags = null, visibility = null;
        try {
            name = data.text(row, "Name_lang");
            if (name.length() > 256) {
                throw new IllegalArgumentException();
            }
        } catch (MapTable.Projection | IllegalArgumentException e) {
            mapPartial = true;
            issue("UNKNOWN_NAME");
            name = null;
        }
        try {
            long p = data.number(row, "ParentUiMapID");
            parent = p == 0 ? Parent.root() : Parent.of(new UiMapId(p));
        } catch (MapTable.Projection | IllegalArgumentException e) {
            mapPartial = true;
            issue("UNKNOWN_PARENT");
        }
        try {
            system = small(data.number(row, "System"));
        } catch (RuntimeException e) {
            mapPartial = true;
            issue("UNKNOWN_SYSTEM");
        }
        try {
            type = small(data.number(row, "Type"));
        } catch (RuntimeException e) {
            mapPartial = true;
            issue("UNKNOWN_TYPE");
        }
        try {
            flags = unsigned(data.number(row, "Flags"));
        } catch (RuntimeException e) {
            mapPartial = true;
            issue("UNKNOWN_FLAGS");
        }
        try {
            visibility = unsigned(data.number(row, "VisibilityPlayerConditionID"));
        } catch (RuntimeException e) {
            mapPartial = true;
            issue("UNKNOWN_VISIBILITY");
        }
        return new Node(
                id, name, parent, system, type, flags, visibility, new RowSource(read, row.id()));
    }

    private Assignment assignment(MapTable d, Db2Types.Row r, TableRead read) {
        return new Assignment(
                new UiMapId(d.number(r, "UiMapID")),
                new WorldMapId(d.number(r, "MapID")),
                new WorldRect(
                        decimal(d, r, "Region[0]"),
                        decimal(d, r, "Region[1]"),
                        decimal(d, r, "Region[3]"),
                        decimal(d, r, "Region[4]")),
                new Rect(
                        decimal(d, r, "UiMin[0]"),
                        decimal(d, r, "UiMin[1]"),
                        decimal(d, r, "UiMax[0]"),
                        decimal(d, r, "UiMax[1]")),
                d.number(r, "WMODoodadPlacementID") == 0 && d.number(r, "WMOGroupID") == 0,
                new RowSource(read, r.id()));
    }

    private static double decimal(MapTable d, Db2Types.Row r, String f) {
        return d.decimal(r, f).doubleValue();
    }

    private static int small(long n) {
        if (n < 0 || n > 255) {
            throw new IllegalArgumentException();
        }
        return (int) n;
    }

    private static long unsigned(long n) {
        if (n < 0 || n > 0xffffffffL) {
            throw new IllegalArgumentException();
        }
        return n;
    }

    private void charge(long bytes) {
        if (charged > MAX_STAGE - bytes) {
            refuse(State.LIMIT_EXCEEDED, "PROJECTION_LIMIT");
        }
        charged += bytes;
    }

    private void issue(String code) {
        if (issues.size() < 64 && !issues.contains(code)) {
            issues.add(code);
        }
    }

    private static TableRead tableRead(TableStamp stamp, Table kind, boolean partial) {
        return new TableRead(
                kind,
                stamp.contentKey(),
                stamp.layout(),
                partial ? ReadState.PARTIAL : ReadState.READY);
    }

    private TableStamp stamp(String name, CapturedRowsTypes.Receipt r) {
        token.check();
        if (!r.build().equals(tables.build())
                || !r.locale().equals(tables.locale())
                || !name.equals(r.table().name())
                || r.table().counts() == null
                || !List.of(TableTypes.State.READY, TableTypes.State.PARTIAL)
                        .contains(r.table().state())
                || r.issueChannelEvidence().state()
                        != CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE
                || r.columns().size() > 128
                || r.schema().columns().size() > 128
                || r.rowCount() != r.table().counts().readableRows()) {
            refuse(State.UNSUPPORTED, "UNQUALIFIED_RECEIPT");
        }
        try {
            MapCaptureIdentity.validate(r);
        } catch (UnsupportedOperationException | IllegalArgumentException failure) {
            refuse(State.UNSUPPORTED, "UNQUALIFIED_NATIVE_BINDING");
        }
        return new TableStamp(
                name,
                r.table().contentKey(),
                r.table().counts().layoutHash(),
                r.graphIdentity(),
                digest(r),
                digest(List.of(r.schema(), r.columns(), r.definitionSha256())),
                r.rowCount());
    }

    private void probeEof(CapturedRows rows, CapturedRowsTypes.Receipt receipt) {
        token.check();
        var eof = rows.readOrdinalPage(receipt.rowCount(), 1, LIMITS, token).join();
        if (eof.state() == CapturedRowsTypes.ReadState.CANCELED) {
            throw new java.util.concurrent.CancellationException();
        }
        if (eof.state() == CapturedRowsTypes.ReadState.LIMIT_EXCEEDED) {
            refuse(State.LIMIT_EXCEEDED, "EOF_READ_LIMIT");
        }
        if (eof.state() != CapturedRowsTypes.ReadState.READ
                || !eof.identity().equals(receipt.graphIdentity())
                || eof.firstOrdinal() != receipt.rowCount()
                || !eof.rows().isEmpty()
                || !eof.cellIssues().isEmpty()
                || eof.nextOrdinal() != null) {
            refuse(State.STALE, "CAPTURE_EOF_CHANGED");
        }
        token.check();
    }

    private static String digest(Object value) {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            var bounded = new BoundedDigest(hash);
            var out = new java.io.DataOutputStream(bounded);
            out.writeUTF("vimi-maps-capture-stamp-v2");
            encode(value, out, 0);
            out.flush();
            return HexFormat.of().formatHex(hash.digest());
        } catch (ReflectiveOperationException
                | java.io.IOException
                | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class BoundedDigest extends java.io.OutputStream {
        private final java.security.MessageDigest hash;
        private int bytes;

        BoundedDigest(java.security.MessageDigest hash) {
            this.hash = hash;
        }

        private void claim(int count) {
            if (count < 0 || bytes > MapCaptureIdentity.MAX_CANONICAL_BYTES - count) {
                refuse(State.LIMIT_EXCEEDED, "STAMP_LIMIT");
            }
            bytes += count;
        }

        @Override
        public void write(int value) {
            claim(1);
            hash.update((byte) value);
        }

        @Override
        public void write(byte[] value, int offset, int count) {
            claim(count);
            hash.update(value, offset, count);
        }
    }

    private static void encode(Object value, java.io.DataOutputStream out, int depth)
            throws java.io.IOException, ReflectiveOperationException {
        if (depth > 16) {
            refuse(State.LIMIT_EXCEEDED, "STAMP_DEPTH");
        }
        if (value == null) {
            out.writeByte(0);
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 256) {
                refuse(State.LIMIT_EXCEEDED, "STAMP_LIST");
            }
            out.writeByte(1);
            out.writeInt(list.size());
            for (var item : list) {
                encode(item, out, depth + 1);
            }
        } else if (value.getClass().isRecord()) {
            out.writeByte(2);
            var fields = value.getClass().getRecordComponents();
            out.writeInt(fields.length);
            for (var field : fields) {
                encode(field.getAccessor().invoke(value), out, depth + 1);
            }
        } else if (value instanceof java.time.Instant instant) {
            out.writeByte(3);
            out.writeLong(instant.getEpochSecond());
            out.writeInt(instant.getNano());
        } else {
            String text;
            if (value instanceof Enum<?> e) {
                out.writeByte(4);
                text = e.name();
            } else if (value instanceof String s) {
                out.writeByte(5);
                text = s;
            } else if (value instanceof Number || value instanceof Boolean) {
                out.writeByte(6);
                text = String.valueOf(value);
            } else {
                throw new UnsupportedOperationException("Canonical stamp type");
            }
            // All receipt/schema strings were admitted by the prehash scalar/aggregate profile.
            if (text.length() > 8256) {
                refuse(State.LIMIT_EXCEEDED, "STAMP_SCALAR");
            }
            byte[] encoded = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(encoded.length);
            out.write(encoded);
        }
    }

    private static void refuse(State state, String code) {
        throw new Refusal(state, code);
    }

    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1;
        final State state;
        final String code;

        Refusal(State state, String code) {
            super(code);
            this.state = state;
            this.code = code;
        }
    }
}
