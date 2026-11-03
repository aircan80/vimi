package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.definitions.TableSchema;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Source-specific bounded bootstrap before comparison; these are not whole-process limits. */
final class PreparedTactKeys {
    static final long MAX_SCANNED_BYTES = 32L << 20, MAX_RETAINED_BYTES = 8L << 20;

    private PreparedTactKeys() {}

    static CompletableFuture<BuildTactKeys.KeySet> load(BuildTables tables, Cancellation caller) {
        return load(tables, caller, MAX_SCANNED_BYTES, MAX_RETAINED_BYTES);
    }

    static CompletableFuture<BuildTactKeys.KeySet> load(
            BuildTables tables, Cancellation caller, long scanLimit, long retainedLimit) {
        if (scanLimit < 1
                || scanLimit > MAX_SCANNED_BYTES
                || retainedLimit < 1
                || retainedLimit > MAX_RETAINED_BYTES) {
            throw new IllegalArgumentException("Invalid bootstrap bounds");
        }
        java.util.Objects.requireNonNull(tables);
        java.util.Objects.requireNonNull(caller);
        var request = new Cancellation();
        var registration = caller.onCancel(request::cancel);
        var delivery = new CompletableFuture<BuildTactKeys.KeySet>();
        delivery.whenComplete(
                (value, error) -> {
                    if (delivery.isCancelled()) {
                        request.cancel();
                    }
                });
        Runnable work =
                () -> {
                    try {
                        var result = read(tables, request, scanLimit, retainedLimit);
                        registration.close();
                        delivery.complete(result);
                    } catch (Throwable failure) {
                        registration.close();
                        delivery.completeExceptionally(failure);
                    }
                };
        try {
            Thread.startVirtualThread(work);
        } catch (Throwable failure) {
            registration.close();
            delivery.completeExceptionally(failure);
        }
        return delivery;
    }

    private static BuildTactKeys.KeySet read(
            BuildTables tables, Cancellation token, long scanLimit, long retainedLimit) {
        CapturedRows keys = null, lookup = null;
        try {
            token.check();
            var keyCapture =
                    tables.capturePreparedLocal(
                                    TableTypes.Ref.id(BuildTactKeys.TACT_KEY_FILE_DATA_ID), token)
                            .join();
            keys = keyCapture.rows();
            token.check();
            var lookupCapture =
                    tables.capturePreparedLocal(
                                    TableTypes.Ref.id(BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID),
                                    token)
                            .join();
            lookup = lookupCapture.rows();
            token.check();
            var budget = new Budget(scanLimit, retainedLimit);
            var keyRead =
                    read(
                            tables,
                            keyCapture,
                            BuildTactKeys.TACT_KEY_FILE_DATA_ID,
                            "TactKey",
                            "Key",
                            16,
                            lookup != null,
                            budget,
                            token);
            var lookupRead =
                    read(
                            tables,
                            lookupCapture,
                            BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID,
                            "TactKeyLookup",
                            "TACTID",
                            8,
                            keys != null,
                            budget,
                            token);
            return BuildTactKeys.join(keyRead, lookupRead);
        } finally {
            try {
                if (lookup != null) {
                    lookup.closeAsync().join();
                }
            } finally {
                if (keys != null) {
                    keys.closeAsync().join();
                }
            }
        }
    }

    private static BuildTactKeys.Read empty(TableTypes.Entry entry, BuildTactKeys.Outcome outcome) {
        return new BuildTactKeys.Read(
                new BuildTactKeys.Table(entry, outcome), Map.of(), java.util.Set.of(), 0);
    }

    private static BuildTactKeys.Read read(
            BuildTables owner,
            CapturedRowsTypes.Capture capture,
            long id,
            String name,
            String column,
            int width,
            boolean needed,
            Budget budget,
            Cancellation token) {
        if (capture.rows() == null) {
            var state =
                    switch (capture.state()) {
                        case NOT_PREPARED -> BuildTactKeys.Outcome.NOT_PREPARED;
                        case UNSUPPORTED -> BuildTactKeys.Outcome.UNSUPPORTED;
                        case CORRUPT -> BuildTactKeys.Outcome.SOURCE_INVALID;
                        default -> BuildTactKeys.Outcome.NOT_READABLE;
                    };
            var entry =
                    new TableTypes.Entry(
                            id, name, TableTypes.State.NOT_CHECKED, false, null, null, null);
            return empty(entry, state);
        }
        var receipt = capture.rows().receipt();
        var entry = receipt.table();
        if (!receipt.build().equals(owner.build())
                || !receipt.locale().equals(owner.locale())
                || entry.fileDataId() != id
                || !receipt.header().columns().equals(receipt.columns())
                || receipt.graphIdentity().isBlank()
                || receipt.sourceIdentity().isBlank()
                || entry.contentKey() == null
                || !entry.contentKey().matches("[0-9a-f]{32}")) {
            return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
        }
        if (!TableQueries.isReadable(entry.state())) {
            return empty(entry, BuildTactKeys.Outcome.NOT_READABLE);
        }
        if (!needed) {
            return empty(entry, BuildTactKeys.Outcome.NOT_NEEDED);
        }
        if (receipt.rowCount() > BuildTactKeys.MAX_ROWS) {
            return empty(entry, BuildTactKeys.Outcome.TOO_MANY_ROWS);
        }
        int[] indexes = new int[width];
        for (int i = 0; i < width; i++) {
            indexes[i] = -1;
            String wanted = column + "[" + i + "]";
            for (int c = 0; c < receipt.columns().size(); c++) {
                if (receipt.columns().get(c).name().equalsIgnoreCase(wanted)) {
                    indexes[i] = c;
                    break;
                }
            }
            if (indexes[i] < 0) {
                return empty(entry, BuildTactKeys.Outcome.MISSING_COLUMNS);
            }
            if (!receipt.columns().get(indexes[i]).kind().equals("integer")) {
                return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
            }
        }
        boolean signed =
                receipt.schema().columns().stream()
                        .anyMatch(
                                c ->
                                        c.name().equalsIgnoreCase(column)
                                                && c.kind() == TableSchema.ColumnKind.INT
                                                && Integer.valueOf(8).equals(c.bits())
                                                && Boolean.FALSE.equals(c.unsigned()));
        var rows = new HashMap<Long, byte[]>();
        var bad = new HashSet<Long>();
        int malformed = 0;
        long first = 0;
        while (true) {
            token.check();
            var page = capture.rows().readOrdinalPage(first, 256, token).join();
            token.check();
            if (page.state() != CapturedRowsTypes.ReadState.READ
                    || !receipt.graphIdentity().equals(page.identity())
                    || page.firstOrdinal() != first
                    || page.rows().size() > 256) {
                return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
            }
            if (page.rows().size() > receipt.rowCount() - first
                    || (page.rows().isEmpty() && first != receipt.rowCount())) {
                return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
            }
            for (int n = 0; n < page.rows().size(); n++) {
                var indexed = page.rows().get(n);
                var row = indexed.row();
                if (indexed.ordinal() != first + n || row.id() < 0 || row.id() > 0xffffffffL) {
                    return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
                }
                long scanned = 64;
                for (String value : row.values()) {
                    scanned += 16L + 4L * value.length();
                }
                if (row.textIssues() != null) {
                    for (var issue : row.textIssues()) {
                        scanned +=
                                64L
                                        + 4L * issue.code().length()
                                        + 4L * issue.rawBytesHex().length();
                    }
                }
                if (!budget.scan(scanned)) {
                    return empty(entry, BuildTactKeys.Outcome.RESOURCE_LIMIT);
                }
                if (bad.contains(row.id())) {
                    malformed++;
                    continue;
                }
                boolean limited =
                        page.cellIssues().stream()
                                .anyMatch(
                                        issue ->
                                                issue.ordinal() == indexed.ordinal()
                                                        && Arrays.stream(indexes)
                                                                .anyMatch(
                                                                        index ->
                                                                                index
                                                                                        == issue
                                                                                                .columnIndex()));
                boolean invalid =
                        row.textIssues() != null
                                && row.textIssues().stream()
                                        .anyMatch(
                                                issue ->
                                                        Arrays.stream(indexes)
                                                                .anyMatch(
                                                                        index ->
                                                                                index
                                                                                        == issue
                                                                                                .columnIndex()));
                byte[] bytes =
                        limited || invalid
                                ? null
                                : BuildTactKeys.bytes(row.values(), indexes, signed);
                if (bytes == null) {
                    if (!bad.contains(row.id()) && !budget.retain(64)) {
                        return empty(entry, BuildTactKeys.Outcome.RESOURCE_LIMIT);
                    }
                    bad.add(row.id());
                    rows.remove(row.id());
                    malformed++;
                    continue;
                }
                var previous = rows.get(row.id());
                if (previous == null) {
                    if (!budget.retain(112L + width)) {
                        return empty(entry, BuildTactKeys.Outcome.RESOURCE_LIMIT);
                    }
                    rows.put(row.id(), bytes);
                } else if (!Arrays.equals(previous, bytes)) {
                    if (!budget.retain(64)) {
                        return empty(entry, BuildTactKeys.Outcome.RESOURCE_LIMIT);
                    }
                    rows.remove(row.id());
                    bad.add(row.id());
                    malformed += 2;
                }
            }
            long next = first + page.rows().size();
            if (page.nextOrdinal() == null) {
                if (next != receipt.rowCount()) {
                    return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
                }
                break;
            }
            if (page.nextOrdinal() != next || next <= first || next >= receipt.rowCount()) {
                return empty(entry, BuildTactKeys.Outcome.SOURCE_INVALID);
            }
            first = next;
        }
        return new BuildTactKeys.Read(
                new BuildTactKeys.Table(entry, BuildTactKeys.Outcome.READ), rows, bad, malformed);
    }

    private static final class Budget {
        long scanned, retained;
        final long scanLimit, retainedLimit;

        Budget(long scanLimit, long retainedLimit) {
            this.scanLimit = scanLimit;
            this.retainedLimit = retainedLimit;
        }

        boolean scan(long bytes) {
            if (bytes < 0 || bytes > scanLimit - scanned) {
                return false;
            }
            scanned += bytes;
            return true;
        }

        boolean retain(long bytes) {
            if (bytes < 0 || bytes > retainedLimit - retained) {
                return false;
            }
            retained += bytes;
            return true;
        }
    }
}
