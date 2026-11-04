package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class NativeIdDomainTest {
    private static BuildFiles borrowed() {
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "build" -> TablesContract.BUILD;
                                    case "locale" -> "enUS";
                                    default ->
                                            throw new AssertionError(
                                                    "Borrowed fixture handle: " + method.getName());
                                });
    }

    private static CapturedRows capture(AuthoredPartialNativeMap fixture) {
        return fixture.tables()
                .capturePreparedLocal(TableTypes.Ref.forName("Map"), Cancellation.NONE)
                .join()
                .rows();
    }

    private static NativeIdDomainTypes.Result inspect(CapturedRows rows, long id) throws Exception {
        return rows.inspectNativeIdDomain(
                        id, NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE, Cancellation.NONE)
                .get(5, TimeUnit.SECONDS);
    }

    private static Object replaceRecord(Object record, String field, Object replacement)
            throws Exception {
        var components = record.getClass().getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] =
                    components[i].getName().equals(field)
                            ? replacement
                            : components[i].getAccessor().invoke(record);
        }
        return record.getClass().getDeclaredConstructor(types).newInstance(values);
    }

    @Test
    void receiptFingerprintBoundsExactSerializedTableNameBeforeHashAllocation() throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture)) {
            var receipt = rows.receipt();
            var table = replaceRecord(receipt.table(), "name", "n".repeat(65536));
            var forged = (CapturedRowsTypes.Receipt) replaceRecord(receipt, "table", table);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> NativeIdReceiptFingerprint.sha256(forged));
            assertEquals(0, fixture.opens.get());
        }
    }

    @Test
    void concurrentCancellationCannotHideUnexpectedNativeInspectorFailure() throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture)) {
            fixture.holdReads = true;
            fixture.holdCancellation = true;
            var external = new Cancellation();
            var inspected =
                    rows.inspectNativeIdDomain(
                            7, NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE, external);
            try {
                fixture.reading.get(5, TimeUnit.SECONDS);
                external.cancel();
                fixture.cancellationEntered.get(5, TimeUnit.SECONDS);
                fixture.readFailure = new NullPointerException("Authored unexpected read failure");
                fixture.releaseRead.complete(null);
                fixture.streamClosed.get(5, TimeUnit.SECONDS);
                assertFalse(inspected.isDone());
                fixture.releaseCancellation.complete(null);
                var failed =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> inspected.get(5, TimeUnit.SECONDS));
                assertTrue(failed.toString().contains("Authored unexpected read failure"));
            } finally {
                fixture.releaseRead.complete(null);
                fixture.releaseCancellation.complete(null);
            }
        }
    }

    @Test
    void unknownOversizedStreamAndUnsafeReadStateRejectBeforeAnyInputRead() throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture)) {
            fixture.unknownSize = true;
            fixture.streamLength = (1L << 20) + 1;
            assertEquals(NativeIdDomainTypes.State.LIMIT_EXCEEDED, inspect(rows, 7).state());
            assertFalse(fixture.reading.isDone());
            assertEquals(1, fixture.closedStreams.get());
            fixture.unknownSize = false;
            fixture.streamLength = null;
            fixture.readState = com.vibeminer.files.FileTypes.FileReadState.CORRUPT;
            assertEquals(NativeIdDomainTypes.State.UNAVAILABLE, inspect(rows, 7).state());
            assertFalse(fixture.reading.isDone());
            assertEquals(2, fixture.closedStreams.get());
            fixture.readState = com.vibeminer.files.FileTypes.FileReadState.PARTIAL;
            assertEquals(NativeIdDomainTypes.State.READABLE_UNIQUE, inspect(rows, 7).state());
        }
    }

    @Test
    void cancellationCallbackFailureIsReportedOnlyAfterRealSourceDrain() throws Exception {
        var fixture = new AuthoredPartialNativeMap(borrowed());
        var rows = capture(fixture);
        try {
            fixture.holdReads = true;
            fixture.holdCancellation = true;
            fixture.throwCancellation = true;
            var external = new Cancellation();
            var result =
                    rows.inspectNativeIdDomain(
                            7, NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE, external);
            fixture.reading.get(5, TimeUnit.SECONDS);
            external.cancel();
            fixture.cancellationEntered.get(5, TimeUnit.SECONDS);
            fixture.releaseRead.complete(null);
            fixture.streamClosed.get(5, TimeUnit.SECONDS);
            var closed = rows.closeAsync();
            assertFalse(closed.isDone());
            fixture.releaseCancellation.complete(null);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS));
        } finally {
            fixture.releaseRead.complete(null);
            fixture.releaseCancellation.complete(null);
            try {
                rows.closeAsync().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.toString().contains("Authored native ID cancellation failure"));
            }
            fixture.close();
        }
    }

    @Test
    void factsRequirePerSectionInventoryEvenWhenGlobalCountMatchesAndLookupFindsReadableId() {
        int start = 204 + 120 + 28 + 16;
        var data = java.nio.ByteBuffer.allocate(start + 36).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'W', 'D', 'C', '5'}).putInt(5);
        data.putInt(136, 3)
                .putInt(140, 1)
                .putInt(144, 8)
                .putInt(152, 0x11223344)
                .putInt(156, 0x01020304)
                .putShort(172, (short) 4)
                .putInt(176, 1)
                .putInt(188, 24)
                .putInt(200, 3);
        data.position(204);
        for (int i = 0; i < 3; i++) {
            data.putLong(i == 0 ? 0 : 122 + i)
                    .putInt(start + i * 12)
                    .putInt(1)
                    .putInt(0)
                    .putInt(0)
                    .putInt(4)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0);
        }
        data.putShort((short) 0).putShort((short) 0);
        data.putShort((short) 0)
                .putShort((short) 32)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        data.putInt(0).putInt(2).putInt(99).putInt(100);
        data.putLong(1).putInt(7).putLong(777).putInt(101).putLong(888).putInt(99);
        var bytes = data.array();
        var projected =
                new com.vibeminer.formats.db2.Db2ByteSource() {
                    public long length() {
                        return bytes.length;
                    }

                    public List<com.vibeminer.formats.db2.Db2Types.Hole> holes() {
                        return List.of(
                                new com.vibeminer.formats.db2.Db2Types.Hole(
                                        start + 12, 12, "000000000000007B"),
                                new com.vibeminer.formats.db2.Db2Types.Hole(
                                        start + 24, 12, "000000000000007C"));
                    }

                    public CompletableFuture<Void> readRange(
                            long offset, java.nio.ByteBuffer target, Cancellation token) {
                        token.check();
                        target.put(bytes, (int) offset, target.remaining());
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var base = new com.vibeminer.formats.db2.Db2Types.Limits();
        var limits =
                new com.vibeminer.formats.db2.Db2Types.Limits(
                        base.maxDecodedBytes(),
                        base.maxRows(),
                        base.maxFields(),
                        base.maxSections(),
                        base.maxRecordBytes(),
                        base.maxAuxiliaryBytes(),
                        base.maxStringBytes(),
                        base.maxIndexBytes(),
                        base.maxColumns(),
                        base.maxCopyDepth(),
                        true,
                        true,
                        base.maxRawTextEvidenceBytes(),
                        base.maxRelationshipBytes(),
                        base.maxRelationshipEntries(),
                        base.definitionSha256(),
                        base.verifiedRawIntegerRules());
        var document =
                Db2Fixture.definition(
                        "int ID\nint WdtFileDataID", "$id,noninline$ID<u32>\nWdtFileDataID<u32>");
        var table =
                com.vibeminer.formats.db2.Db2Reader.open(
                                projected, document, "1.2.3.4", limits, Cancellation.NONE)
                        .join();
        assertTrue(
                table.find(7, Cancellation.NONE).join()
                        instanceof com.vibeminer.formats.db2.Db2Types.Found);
        assertEquals(2, table.protectedIds(256).size());
        assertEquals(2, table.completion().excludedPhysicalRecords());
        assertEquals(
                com.vibeminer.formats.db2.Db2Table.IdDomainState.UNSUPPORTED_DOMAIN,
                table.inspectIdDomain(7, Cancellation.NONE).state());
        // A protected ID conflicting with the readable ID is refused by actual structural open.
        data.putInt(360, 7);
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () ->
                        com.vibeminer.formats.db2.Db2Reader.open(
                                        projected, document, "1.2.3.4", limits, Cancellation.NONE)
                                .join());
    }

    @Test
    void sourceAndKeyChangesAfterStructuralReadStartedCannotPublishWitness() throws Exception {
        for (boolean key : new boolean[] {false, true}) {
            try (var fixture = new AuthoredPartialNativeMap(borrowed());
                    var rows = capture(fixture)) {
                fixture.holdReads = true;
                var inspected =
                        rows.inspectNativeIdDomain(
                                7,
                                NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE,
                                Cancellation.NONE);
                try {
                    fixture.reading.get(5, TimeUnit.SECONDS);
                    if (key) {
                        fixture.keyFingerprint.set("late-key");
                    } else {
                        fixture.contentKey.set("f".repeat(32));
                    }
                    fixture.releaseRead.complete(null);
                    assertEquals(
                            NativeIdDomainTypes.State.SOURCE_CHANGED,
                            inspected.get(5, TimeUnit.SECONDS).state());
                } finally {
                    fixture.releaseRead.complete(null);
                }
            }
        }
    }

    @Test
    void realPartialNativeCaptureInspectsUniqueReadableProtectedAndUnknownIdsWithoutValues()
            throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture)) {
            assertNotNull(rows);
            assertEquals(TableTypes.State.PARTIAL, rows.receipt().table().state());
            assertEquals(
                    CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                    rows.receipt().issueChannelEvidence().state());
            assertEquals(CapturedRowsTypes.Membership.NOT_CERTIFIED, rows.receipt().membership());
            var result = inspect(rows, 7);
            assertEquals(NativeIdDomainTypes.State.READABLE_UNIQUE, result.state());
            assertEquals(0, result.witness().readableOrdinal());
            assertEquals(1, result.witness().excludedCount());
            assertEquals(
                    NativeIdReceiptFingerprint.sha256(rows.receipt()),
                    result.witness().receiptFingerprint());
            assertEquals(NativeIdDomainTypes.State.PROTECTED_ID, inspect(rows, 99).state());
            assertEquals(NativeIdDomainTypes.State.NOT_OBSERVED, inspect(rows, 88).state());
            assertEquals(fixture.opens.get(), fixture.closedStreams.get());
            assertEquals(
                    CapturedRowsTypes.ReadState.READ,
                    rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
        }
    }

    @Test
    void incompleteHiddenInventoryAndCopiesCannotMintWitnessOrInvalidateReadableGraph()
            throws Exception {
        for (boolean copy : new boolean[] {false, true}) {
            try (var fixture = new AuthoredPartialNativeMap(borrowed(), copy, copy);
                    var rows = capture(fixture)) {
                assertEquals(
                        NativeIdDomainTypes.State.UNSUPPORTED_DOMAIN, inspect(rows, 7).state());
                assertEquals(
                        CapturedRowsTypes.ReadState.READ,
                        rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
                assertEquals(
                        CapturedRowsTypes.Membership.NOT_CERTIFIED, rows.receipt().membership());
                assertEquals(copy ? 0 : 1, fixture.opens.get());
            }
        }
    }

    @Test
    void freshSourceKeysLimitsAndHoleScopeRejectWithoutDiscardingSnapshot() throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture)) {
            fixture.declaredSize = (1L << 20) + 1;
            assertEquals(NativeIdDomainTypes.State.LIMIT_EXCEEDED, inspect(rows, 7).state());
            assertEquals(0, fixture.opens.get());
            fixture.declaredSize = null;
            String original = fixture.contentKey.get();
            fixture.contentKey.set("f".repeat(32));
            assertEquals(NativeIdDomainTypes.State.SOURCE_CHANGED, inspect(rows, 7).state());
            fixture.contentKey.set(original);
            fixture.keyFingerprint.set("new-key-fingerprint");
            assertEquals(NativeIdDomainTypes.State.SOURCE_CHANGED, inspect(rows, 7).state());
            assertEquals(0, fixture.opens.get());
            fixture.keyFingerprint.set(null);
            fixture.exposeProtected = true;
            assertEquals(NativeIdDomainTypes.State.SOURCE_CHANGED, inspect(rows, 7).state());
            fixture.exposeProtected = false;
            assertEquals(NativeIdDomainTypes.State.READABLE_UNIQUE, inspect(rows, 7).state());
            assertEquals(
                    CapturedRowsTypes.ReadState.READ,
                    rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
        }
    }

    @Test
    void freshDefinitionChangeRejectsAndRestoringItReusesOriginalGeneratedGraph() throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture)) {
            var field = TableDefinitions.class.getDeclaredField("bundled");
            field.setAccessible(true);
            var original = field.get(fixture.definitions);
            field.set(
                    fixture.definitions,
                    BundledDefinitions.of(
                            List.of(
                                    new BundledDefinitions.DefinitionTable(
                                            "11223344", "Map", "100", true)),
                            Map.of(
                                    "11223344",
                                    new BundledDefinitions.Definition(
                                            "b".repeat(64),
                                            "changed",
                                            Db2Fixture.definition(
                                                    "int ID\nint Changed",
                                                    "$id,noninline$ID<u32>\nChanged<u32>")))));
            assertEquals(NativeIdDomainTypes.State.SOURCE_CHANGED, inspect(rows, 7).state());
            assertEquals(0, fixture.opens.get());
            field.set(fixture.definitions, original);
            var result = inspect(rows, 7);
            assertEquals(NativeIdDomainTypes.State.READABLE_UNIQUE, result.state());
            for (var constructor : result.witness().getClass().getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        }
    }

    @Test
    void oneInspectionCreditSurvivesCancellationAndCloseUntilActualLowerCallbackDrain()
            throws Exception {
        try (var fixture = new AuthoredPartialNativeMap(borrowed());
                var rows = capture(fixture);
                var other = capture(fixture)) {
            fixture.holdReads = true;
            fixture.holdCancellation = true;
            var external = new Cancellation();
            var inspected =
                    rows.inspectNativeIdDomain(
                            7, NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE, external);
            try {
                fixture.reading.get(5, TimeUnit.SECONDS);
                assertEquals(NativeIdDomainTypes.State.BUSY, inspect(other, 7).state());
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> rows.readOrdinalPage(0, 1, Cancellation.NONE).join());
                CompletableFuture.runAsync(external::cancel).get(1, TimeUnit.SECONDS);
                fixture.cancellationEntered.get(5, TimeUnit.SECONDS);
                fixture.releaseRead.complete(null);
                fixture.streamClosed.get(5, TimeUnit.SECONDS);
                assertFalse(inspected.isDone());
                var close =
                        CompletableFuture.supplyAsync(rows::closeAsync).get(1, TimeUnit.SECONDS);
                assertFalse(close.isDone());
                assertFalse(rows.closeAsync().isDone());
                assertEquals(NativeIdDomainTypes.State.BUSY, inspect(other, 7).state());
                assertEquals(1, fixture.opens.get());
                fixture.releaseCancellation.complete(null);
                assertEquals(
                        NativeIdDomainTypes.State.CANCELED,
                        inspected.get(5, TimeUnit.SECONDS).state());
                close.get(5, TimeUnit.SECONDS);
                assertEquals(1, fixture.cancellationCalls.get());
                assertEquals(NativeIdDomainTypes.State.READABLE_UNIQUE, inspect(other, 7).state());
            } finally {
                fixture.releaseRead.complete(null);
                fixture.releaseCancellation.complete(null);
            }
        }
    }

    @Test
    void defaultCapabilityNeverCallsReceiptOrOrdinaryUnboundedReads() {
        var unknown =
                new CapturedRows() {
                    public CapturedRowsTypes.Receipt receipt() {
                        throw new AssertionError("No fallback receipt");
                    }

                    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                            long first, int max, Cancellation token) {
                        throw new AssertionError("No unbounded fallback");
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        assertEquals(
                NativeIdDomainTypes.State.UNSUPPORTED_DOMAIN,
                unknown.inspectNativeIdDomain(
                                7,
                                NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE,
                                Cancellation.NONE)
                        .join()
                        .state());
    }
}
