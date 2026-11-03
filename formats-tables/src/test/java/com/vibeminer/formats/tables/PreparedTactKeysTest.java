package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class PreparedTactKeysTest {
    private static final long KEY = BuildTactKeys.TACT_KEY_FILE_DATA_ID,
            LOOKUP = BuildTactKeys.TACT_KEY_LOOKUP_FILE_DATA_ID;

    private static Db2Types.Row row(long id, int first, int width) {
        var values = new ArrayList<String>();
        values.add(Integer.toString(first));
        for (int i = 1; i < width; i++) {
            values.add("0");
        }
        return new Db2Types.Row(id, false, null, values, null);
    }

    private static class Rows implements CapturedRows {
        final CapturedRowsTypes.Receipt receipt;
        final List<Db2Types.Row> data;
        final AtomicInteger closed = new AtomicInteger(), reads = new AtomicInteger();
        boolean invalidPage, limited;

        Rows(long id, List<Db2Types.Row> data, boolean signed, boolean partial) {
            this(id, data, signed, partial, TablesContract.BUILD, "enUS", data.size());
        }

        Rows(
                long id,
                List<Db2Types.Row> data,
                boolean signed,
                boolean partial,
                com.vibeminer.builds.BuildTypes.BuildRef build,
                String locale,
                long count) {
            this.data = List.copyOf(data);
            String field = id == KEY ? "Key" : "TACTID";
            int width = id == KEY ? 16 : 8;
            var columns = new ArrayList<Db2Types.Column>();
            for (int i = 0; i < width; i++) {
                columns.add(
                        new Db2Types.Column(field + "[" + i + "]", "integer", false, null, null));
            }
            var schema =
                    new TableSchema(
                            List.of(
                                    new TableSchema.SchemaColumn(
                                            field,
                                            TableSchema.ColumnKind.INT,
                                            8,
                                            !signed,
                                            width,
                                            null,
                                            TableSchema.KeyStorage.NONE,
                                            TableSchema.KeyStorage.NONE,
                                            true,
                                            null,
                                            null,
                                            null)),
                            List.of("01020304"),
                            TableSchema.SchemaMatch.LAYOUT,
                            WowLocale.parse(locale),
                            null);
            String graph = String.format("%032x", id);
            var entry =
                    new TableTypes.Entry(
                            id,
                            id == KEY ? "TactKey" : "TactKeyLookup",
                            partial ? TableTypes.State.PARTIAL : TableTypes.State.READY,
                            true,
                            null,
                            new TableTypes.Counts(count, count, 0, 0, "WDC5", "01020304"),
                            "a".repeat(32));
            var header =
                    new Db2Types.Header(5, "11223344", "01020304", count, 0, 16, columns, "WDC5");
            receipt =
                    new CapturedRowsTypes.Receipt(
                            build,
                            locale,
                            entry,
                            schema,
                            columns,
                            "b".repeat(64),
                            "block",
                            graph,
                            "authored-fixture",
                            "options",
                            "reader",
                            "rows",
                            null,
                            count,
                            header,
                            new Db2Types.Completion(count, count, 0, 0, 0, 0, "local", 0, 0),
                            List.of(),
                            List.of(),
                            List.of(),
                            CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                            CapturedRowsTypes.Membership.NOT_CERTIFIED);
        }

        @Override
        public CapturedRowsTypes.Receipt receipt() {
            return receipt;
        }

        @Override
        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                long first, int maxRows, Cancellation token) {
            token.check();
            reads.incrementAndGet();
            assertEquals(256, maxRows);
            int from = Math.toIntExact(first), to = Math.min(data.size(), from + 1);
            var rows = new ArrayList<TableTypes.Row>();
            for (int i = from; i < to; i++) {
                rows.add(new TableTypes.Row(i, data.get(i)));
            }
            return CompletableFuture.completedFuture(
                    new CapturedRowsTypes.Page(
                            invalidPage ? "changed" : receipt.graphIdentity(),
                            first,
                            CapturedRowsTypes.ReadState.READ,
                            rows,
                            to < data.size() ? (long) to : null,
                            null,
                            null,
                            limited
                                    ? List.of(
                                            new CapturedRowsTypes.CellIssue(
                                                    first,
                                                    0,
                                                    CapturedRowsTypes.CellState.LIMITED,
                                                    "authored-limit"))
                                    : List.of()));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static BuildTables owner(Map<Long, CapturedRowsTypes.Capture> captures) {
        return (BuildTables)
                Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "build" -> TablesContract.BUILD;
                                    case "locale" -> "enUS";
                                    case "capturePreparedLocal" -> {
                                        var ref = (TableTypes.Ref) args[0];
                                        assertNull(ref.name());
                                        if (captures.containsKey(ref.fileDataId())) {
                                            yield CompletableFuture.completedFuture(
                                                    captures.get(ref.fileDataId()));
                                        }
                                        yield CompletableFuture.completedFuture(
                                                new CapturedRowsTypes.Capture(
                                                        CapturedRowsTypes.CaptureState.NOT_PREPARED,
                                                        null,
                                                        "authored-missing",
                                                        null));
                                    }
                                    default ->
                                            throw new AssertionError(
                                                    "Prepared bootstrap called forbidden normal API: "
                                                            + method.getName());
                                });
    }

    private static CapturedRowsTypes.Capture captured(Rows rows) {
        return new CapturedRowsTypes.Capture(
                CapturedRowsTypes.CaptureState.CAPTURED, rows, null, null);
    }

    private static BuildTables owner(Rows keys, Rows lookup) {
        return owner(Map.of(KEY, captured(keys), LOOKUP, captured(lookup)));
    }

    @Test
    void capturedOnlyShortPagesPreserveSignedBytesAndReturnOwnedKeys() {
        var keys = new Rows(KEY, List.of(row(7, -128, 16)), true, false);
        var lookup = new Rows(LOOKUP, List.of(row(7, 9, 8)), false, false);
        var result = BuildTactKeys.loadPreparedLocal(owner(keys, lookup), Cancellation.NONE).join();
        byte[] expected = new byte[16];
        expected[0] = (byte) 128;
        assertArrayEquals(expected, result.find(9));
        result.find(9)[0] = 1;
        assertArrayEquals(expected, result.find(9));
        assertEquals(1, result.count());
        assertEquals(64, result.fingerprintOf(result.names().getFirst()).length());
        assertEquals(1, keys.closed.get());
        assertEquals(1, lookup.closed.get());
    }

    @Test
    void conflictsZerosMalformedAndIdJoinKeepExistingPrecedence() {
        var keys =
                new Rows(
                        KEY,
                        List.of(row(4, 256, 16), row(2, 0, 16), row(1, 1, 16), row(3, 2, 16)),
                        false,
                        false);
        var lookup =
                new Rows(
                        LOOKUP,
                        List.of(row(1, 9, 8), row(2, 8, 8), row(3, 9, 8), row(4, 7, 8)),
                        false,
                        false);
        var result = BuildTactKeys.loadPreparedLocal(owner(keys, lookup), Cancellation.NONE).join();
        assertEquals(0, result.count());
        assertEquals(1, result.skippedZero());
        assertEquals(1, result.malformed());
        assertEquals(0, result.unmatched());
        assertEquals(List.of("0000000000000009"), result.conflicts());
    }

    @Test
    void partialReadableRowsRemainUsefulAndMissingJoinIsUnknown() {
        var keys = new Rows(KEY, List.of(row(7, 1, 16)), false, true);
        var lookup = new Rows(LOOKUP, List.of(row(7, 9, 8), row(8, 10, 8)), false, false);
        var result = BuildTactKeys.loadPreparedLocal(owner(keys, lookup), Cancellation.NONE).join();
        assertEquals(1, result.count());
        assertEquals(1, result.unknown());
        assertEquals(0, result.unmatched());
    }

    @Test
    void unavailableBootstrapReturnsEmptyKeySetAndClosesOtherCapture() {
        var keys = new Rows(KEY, List.of(row(7, 1, 16)), false, false);
        var result =
                BuildTactKeys.loadPreparedLocal(
                                owner(Map.of(KEY, captured(keys))), Cancellation.NONE)
                        .join();
        assertEquals(0, result.count());
        assertEquals(BuildTactKeys.Outcome.NOT_NEEDED, result.keyTable().outcome());
        assertEquals(BuildTactKeys.Outcome.NOT_PREPARED, result.lookupTable().outcome());
        assertEquals(0, keys.reads.get());
        assertEquals(1, keys.closed.get());
    }

    @Test
    void receiptWrongEndpointLocaleOrIdAndStalePagesAreRejected() {
        for (int invalid = 0; invalid < 4; invalid++) {
            var build =
                    invalid == 0
                            ? new com.vibeminer.builds.BuildTypes.BuildRef(
                                    TablesContract.BUILD.product(),
                                    "c".repeat(32),
                                    TablesContract.BUILD.cdnConfig())
                            : TablesContract.BUILD;
            var keys =
                    new Rows(
                            invalid == 2 ? LOOKUP : KEY,
                            List.of(row(7, 1, 16)),
                            false,
                            false,
                            build,
                            invalid == 1 ? "deDE" : "enUS",
                            1);
            keys.invalidPage = invalid == 3;
            var lookup = new Rows(LOOKUP, List.of(row(7, 9, 8)), false, false);
            var result =
                    BuildTactKeys.loadPreparedLocal(owner(keys, lookup), Cancellation.NONE).join();
            assertEquals(0, result.count());
            assertEquals(BuildTactKeys.Outcome.SOURCE_INVALID, result.keyTable().outcome());
            assertEquals(1, keys.closed.get());
            assertEquals(1, lookup.closed.get());
        }
    }

    @Test
    void rowAndByteQuotasAreTypedAndLimitedCellsNeverBecomeKeyBytes() {
        var oversized =
                new Rows(
                        KEY,
                        List.of(),
                        false,
                        false,
                        TablesContract.BUILD,
                        "enUS",
                        BuildTactKeys.MAX_ROWS + 1L);
        var lookup = new Rows(LOOKUP, List.of(row(7, 9, 8)), false, false);
        assertEquals(
                BuildTactKeys.Outcome.TOO_MANY_ROWS,
                BuildTactKeys.loadPreparedLocal(owner(oversized, lookup), Cancellation.NONE)
                        .join()
                        .keyTable()
                        .outcome());
        assertEquals(0, oversized.reads.get());
        for (boolean scan : new boolean[] {true, false}) {
            var keys = new Rows(KEY, List.of(row(7, 1, 16)), false, false);
            var other = new Rows(LOOKUP, List.of(row(7, 9, 8)), false, false);
            var result =
                    PreparedTactKeys.load(
                                    owner(keys, other),
                                    Cancellation.NONE,
                                    scan ? 32 : PreparedTactKeys.MAX_SCANNED_BYTES,
                                    scan ? PreparedTactKeys.MAX_RETAINED_BYTES : 32)
                            .join();
            assertEquals(BuildTactKeys.Outcome.RESOURCE_LIMIT, result.keyTable().outcome());
            assertEquals(0, result.count());
            assertEquals(1, keys.closed.get());
            assertEquals(1, other.closed.get());
        }
        var limited = new Rows(KEY, List.of(row(7, 1, 16)), false, false);
        limited.limited = true;
        var result =
                BuildTactKeys.loadPreparedLocal(
                                owner(
                                        limited,
                                        new Rows(LOOKUP, List.of(row(7, 9, 8)), false, false)),
                                Cancellation.NONE)
                        .join();
        assertEquals(0, result.count());
        assertEquals(1, result.malformed());
    }

    @Test
    void canceledDeliveryRequestsCancellationAndStillDrainsBothCaptures() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var completed = new CountDownLatch(1);
        var keys =
                new Rows(KEY, List.of(row(7, 1, 16)), false, false) {
                    @Override
                    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                            long first, int maxRows, Cancellation token) {
                        entered.countDown();
                        return release.thenCompose(
                                ignored -> super.readOrdinalPage(first, maxRows, token));
                    }

                    @Override
                    public CompletableFuture<Void> closeAsync() {
                        var result = super.closeAsync();
                        completed.countDown();
                        return result;
                    }
                };
        var lookup = new Rows(LOOKUP, List.of(row(7, 9, 8)), false, false);
        var delivery = BuildTactKeys.loadPreparedLocal(owner(keys, lookup), Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(delivery.cancel(true));
        release.complete(null);
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(1, keys.closed.get());
        assertEquals(1, lookup.closed.get());
    }
}
