package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTableContent;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.formats.generated.TactKeyStatus;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class CapturedRowsTest {
    private static final TableTypes.Ref REF = TableTypes.Ref.id(100);
    private static final String CKEY = "1".repeat(32);
    private static final String KEY = "0000000000000001";

    private static final class Fixture {
        final MemoryGeneratedStore memory = new MemoryGeneratedStore();
        final GeneratedTables writable = new GeneratedTables(memory);
        final AtomicInteger filesClosed = new AtomicInteger();
        final AtomicBoolean delay = new AtomicBoolean();
        final CountDownLatch reading = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final AtomicReference<String> keyValue = new AtomicReference<>("before");
        final GeneratedTableSource source;
        final List<GeneratedKey> artifacts;
        final TableDefinitions definitions;
        final TablesOptions options;
        final BuildFiles files;
        final GeneratedTables readonly;
        final String node;

        Fixture(boolean seed, boolean legacy, boolean keyed) {
            this(seed, legacy, keyed, "before");
        }

        Fixture(boolean seed, boolean legacy, boolean keyed, String initialKey) {
            this(seed, legacy, keyed, initialKey, null);
        }

        Fixture(
                boolean seed,
                boolean legacy,
                boolean keyed,
                String initialKey,
                String replacement) {
            keyValue.set(initialKey);
            var base = new Db2Types.Limits();
            var limits =
                    new Db2Types.Limits(
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
                    Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
            var provider =
                    BundledDefinitions.of(
                            List.of(
                                    new BundledDefinitions.DefinitionTable(
                                            "11223344", "Numbers", "100", true)),
                            Map.of(
                                    "11223344",
                                    new BundledDefinitions.Definition(
                                            "a".repeat(64), "fixture", document)));
            definitions = new TableDefinitions(provider, null, null);
            options =
                    new TablesOptions(
                            (build, token) -> CompletableFuture.completedFuture("1.2.3.4"),
                            base,
                            build -> name -> keyValue.get(),
                            new TableWorkScheduler(),
                            200000,
                            256,
                            1000000,
                            true);
            var bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
            var decoded =
                    Db2Reader.open(
                                    Db2ByteSource.memory(bytes),
                                    document,
                                    "1.2.3.4",
                                    limits,
                                    Cancellation.NONE)
                            .join();
            var block =
                    document.selectFile("1.2.3.4", decoded.header().layoutHash(), Cancellation.NONE)
                            .blocks()
                            .getFirst();
            var build = TablesContract.BUILD;
            source =
                    GeneratedTableSource.forBlock(
                            block,
                            "enUS",
                            CKEY,
                            limits,
                            build.product() + " " + build.buildConfig() + " " + build.cdnConfig());
            if (seed) {
                var content =
                        new GeneratedTableContent(
                                decoded.header(),
                                decoded.sections(),
                                decoded.completion(),
                                keyed ? List.of(KEY) : List.of(),
                                (first, count, token) ->
                                        decoded.readOrdinals(first, count, token)
                                                .thenApply(
                                                        rows ->
                                                                rows.stream()
                                                                        .map(
                                                                                row ->
                                                                                        new Db2Types
                                                                                                .Row(
                                                                                                row
                                                                                                        .id(),
                                                                                                row
                                                                                                        .isCopy(),
                                                                                                row
                                                                                                        .copySourceId(),
                                                                                                replacement
                                                                                                                == null
                                                                                                        ? row
                                                                                                                .values()
                                                                                                        : List
                                                                                                                .of(
                                                                                                                        row.values()
                                                                                                                                .getFirst(),
                                                                                                                        replacement),
                                                                                                legacy
                                                                                                        ? null
                                                                                                        : row
                                                                                                                .textIssues()))
                                                                        .toList()),
                                () -> {},
                                List.of(),
                                List.of());
                var table =
                        writable.store(
                                        source,
                                        keyed ? name -> keyValue.get() : TactKeyStatus.NONE,
                                        content,
                                        Cancellation.NONE)
                                .join();
                node = table.identity();
                artifacts =
                        writable.savedArtifacts(
                                        source,
                                        keyed ? name -> keyValue.get() : TactKeyStatus.NONE,
                                        Cancellation.NONE)
                                .join();
            } else {
                node = null;
                artifacts = List.of();
            }
            var file =
                    new FileTypes.BuildFile(
                            100,
                            "dbfilesclient/numbers.db2",
                            FileTypes.FilePathSource.LISTFILE,
                            (long) bytes.length,
                            CKEY,
                            FileTypes.FileFormat.DB2,
                            FileTypes.FileFormatSource.EXTENSION,
                            FileTypes.FileStorage.STORED,
                            false);
            files =
                    (BuildFiles)
                            Proxy.newProxyInstance(
                                    BuildFiles.class.getClassLoader(),
                                    new Class<?>[] {BuildFiles.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "build" -> TablesContract.BUILD;
                                                case "locale" -> "enUS";
                                                case "listContentInventory" -> {
                                                    assertEquals(
                                                            new FileTypes.FileIdRange(100, 100),
                                                            args[0]);
                                                    yield new com.vibeminer.files.AsyncCursor<
                                                            FileTypes.ContentFile>() {
                                                        boolean ended;

                                                        @Override
                                                        public CompletableFuture<
                                                                        List<FileTypes.ContentFile>>
                                                                next(
                                                                        int limit,
                                                                        Cancellation token) {
                                                            token.check();
                                                            if (ended) {
                                                                return CompletableFuture
                                                                        .completedFuture(List.of());
                                                            }
                                                            ended = true;
                                                            return CompletableFuture
                                                                    .completedFuture(
                                                                            List.of(
                                                                                    new FileTypes
                                                                                            .ContentFile(
                                                                                            file
                                                                                                    .fileDataId(),
                                                                                            file
                                                                                                    .size(),
                                                                                            file
                                                                                                    .contentKey())));
                                                        }

                                                        @Override
                                                        public void close() {
                                                            ended = true;
                                                        }
                                                    };
                                                }
                                                case "close" -> {
                                                    filesClosed.incrementAndGet();
                                                    yield null;
                                                }
                                                default ->
                                                        throw new AssertionError(
                                                                "Capture must not decode/acquire: "
                                                                        + method.getName());
                                            });
            var store =
                    (GeneratedStore)
                            Proxy.newProxyInstance(
                                    GeneratedStore.class.getClassLoader(),
                                    new Class<?>[] {GeneratedStore.class},
                                    (proxy, method, args) -> {
                                        assertEquals(
                                                0,
                                                filesClosed.get(),
                                                "Source read after parent files closed");
                                        if (!List.of("read", "readRange", "has")
                                                .contains(method.getName())) {
                                            throw new AssertionError(
                                                    "Capture attempted cache mutation: "
                                                            + method.getName());
                                        }
                                        if (method.getName().equals("readRange") && delay.get()) {
                                            reading.countDown();
                                            return release.thenCompose(
                                                    ignored ->
                                                            memory.readRange(
                                                                    (GeneratedKey) args[0],
                                                                    (Long) args[1],
                                                                    (Integer) args[2],
                                                                    (com.vibeminer.generated
                                                                                    .Cancellation)
                                                                            args[3]));
                                        }
                                        try {
                                            return method.invoke(memory, args);
                                        } catch (InvocationTargetException failure) {
                                            throw failure.getCause();
                                        }
                                    });
            readonly = new GeneratedTables(store);
        }

        ClientBuildTables open() {
            return new ClientBuildTables(
                    null,
                    files,
                    "1.2.3.4",
                    definitions,
                    readonly,
                    options,
                    new BackgroundBuilds(),
                    new ProtectedIds());
        }
    }

    @Test
    void capturesExactGraphWithReceiptAndOrdinalPagesWithoutPayloadOrWrites() {
        var fixture = new Fixture(true, false, false);
        try (var tables = fixture.open()) {
            var capture = tables.capturePreparedLocal(REF, Cancellation.NONE).join();
            assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, capture.state());
            try (var rows = capture.rows()) {
                var receipt = rows.receipt();
                assertEquals(fixture.node, receipt.graphIdentity());
                assertEquals("a".repeat(64), receipt.definitionSha256());
                assertEquals(CKEY, receipt.table().contentKey());
                assertEquals(CapturedRowsTypes.Membership.NOT_CERTIFIED, receipt.membership());
                assertEquals(
                        CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING, receipt.retention());
                var first = rows.readOrdinalPage(0, 1, Cancellation.NONE).join();
                assertEquals(CapturedRowsTypes.ReadState.READ, first.state());
                assertEquals(0, first.rows().getFirst().ordinal());
                assertNotNull(first.nextOrdinal());
                var last = rows.readOrdinalPage(first.nextOrdinal(), 256, Cancellation.NONE).join();
                assertNull(last.nextOrdinal());
                assertEquals(2, first.rows().size() + last.rows().size());
                assertThrows(UnsupportedOperationException.class, () -> receipt.columns().clear());
                assertThrows(UnsupportedOperationException.class, () -> first.rows().clear());
            }
        }
        assertEquals(1, fixture.filesClosed.get());
    }

    @Test
    void missingSavedGraphDoesNotPrepareOrDecode() {
        var fixture = new Fixture(false, false, false);
        try (var tables = fixture.open()) {
            assertEquals(
                    CapturedRowsTypes.CaptureState.NOT_PREPARED,
                    tables.capturePreparedLocal(REF, Cancellation.NONE).join().state());
        }
    }

    @Test
    void corruptionDuringOpenNeverRepairsNodesManifestsOrSegments() {
        for (int index : new int[] {1, 2, 3}) {
            var fixture = new Fixture(true, false, false);
            var key = fixture.artifacts.get(index);
            fixture.memory.damage(key);
            try (var tables = fixture.open()) {
                assertEquals(
                        CapturedRowsTypes.CaptureState.CORRUPT,
                        tables.capturePreparedLocal(REF, Cancellation.NONE).join().state());
            }
            assertTrue(fixture.memory.keys().contains(key));
            assertTrue(fixture.memory.keys().contains(GeneratedKey.parse(fixture.node)));
        }
    }

    @Test
    void disappearingSegmentInvalidatesHeldReaderWithoutReprepareOrRepair() {
        var fixture = new Fixture(true, false, false);
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            fixture.memory.delete(List.of(fixture.artifacts.get(3))).join();
            assertEquals(
                    CapturedRowsTypes.ReadState.CORRUPT,
                    rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
            assertEquals(
                    CapturedRowsTypes.ReadState.INVALIDATED,
                    rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
            assertTrue(fixture.memory.keys().contains(GeneratedKey.parse(fixture.node)));
            assertEquals(CapturedRowsTypes.Membership.NOT_CERTIFIED, rows.receipt().membership());
        }
    }

    @Test
    void nullIssueChannelAndCopyMetadataArePreservedRatherThanInvented() {
        var fixture = new Fixture(true, true, false);
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            var page = rows.readOrdinalPage(0, 256, Cancellation.NONE).join();
            assertTrue(page.rows().stream().allMatch(row -> row.row().textIssues() == null));
            assertEquals(1, page.rows().size());
            assertEquals(List.of("9"), page.rows().getFirst().row().values().subList(1, 2));
            var copy =
                    rows.readOrdinalPage(page.nextOrdinal(), 256, Cancellation.NONE)
                            .join()
                            .rows()
                            .getFirst()
                            .row();
            assertTrue(copy.isCopy());
            assertEquals(7L, copy.copySourceId());
            assertEquals(8L, copy.id());
            assertNull(copy.textIssues());
        }
    }

    @Test
    void receiptFreezesKeyFingerprintsAndLaterProviderChangesDoNotReopen() {
        var fixture = new Fixture(true, false, true);
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            assertEquals("before", rows.receipt().effectiveKeys().getFirst().fingerprint());
            fixture.keyValue.set("after");
            assertEquals(
                    CapturedRowsTypes.ReadState.READ,
                    rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
            assertEquals("before", rows.receipt().effectiveKeys().getFirst().fingerprint());
        }
    }

    @Test
    void closeOfParentDrainsCapturedReadsBeforeClosingBorrowedFiles() throws Exception {
        var fixture = new Fixture(true, false, false);
        var tables = fixture.open();
        var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows();
        fixture.delay.set(true);
        var page = rows.readOrdinalPage(0, 1, Cancellation.NONE);
        assertTrue(fixture.reading.await(5, TimeUnit.SECONDS));
        var closing = tables.closeAsync();
        assertFalse(closing.isDone());
        assertEquals(0, fixture.filesClosed.get());
        fixture.release.complete(null);
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(CapturedRowsTypes.ReadState.CANCELED, page.join().state());
        assertEquals(1, fixture.filesClosed.get());
        assertEquals(
                CapturedRowsTypes.ReadState.CLOSED,
                rows.readOrdinalPage(0, 1, Cancellation.NONE).join().state());
        rows.closeAsync().join();
    }

    @Test
    void boundsCancellationAndDefaultUnsupportedAreExplicit() {
        var fixture = new Fixture(true, false, false);
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> rows.readOrdinalPage(-1, 1, Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> rows.readOrdinalPage(0, 257, Cancellation.NONE));
            var token = new Cancellation();
            token.cancel();
            assertEquals(
                    CapturedRowsTypes.ReadState.CANCELED,
                    rows.readOrdinalPage(0, 1, token).join().state());
            assertEquals(
                    CapturedRowsTypes.CaptureState.CANCELED,
                    tables.capturePreparedLocal(REF, token).join().state());
        }
        var memory = new MemoryTables().add(TablesContract.BUILD, "enUS", List.of());
        try (var tables =
                memory.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            assertEquals(
                    CapturedRowsTypes.CaptureState.UNSUPPORTED,
                    tables.capturePreparedLocal(REF, Cancellation.NONE).join().state());
        }
    }

    @Test
    void missingNodeAndCorruptLayoutNeverDecodeOrRepair() {
        for (boolean corruptLayout : new boolean[] {true, false}) {
            var fixture = new Fixture(true, false, false);
            if (corruptLayout) {
                fixture.memory.damage(fixture.artifacts.getFirst());
            } else {
                fixture.memory.delete(List.of(GeneratedKey.parse(fixture.node))).join();
            }
            try (var tables = fixture.open()) {
                assertEquals(
                        CapturedRowsTypes.CaptureState.NOT_PREPARED,
                        tables.capturePreparedLocal(REF, Cancellation.NONE).join().state());
            }
            assertTrue(fixture.memory.keys().contains(fixture.artifacts.getFirst()));
        }
    }

    @Test
    void unknownKeyFingerprintIsExplicitAndImmutable() {
        var fixture = new Fixture(true, false, true, null);
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            assertEquals(KEY, rows.receipt().effectiveKeys().getFirst().name());
            assertNull(rows.receipt().effectiveKeys().getFirst().fingerprint());
            fixture.keyValue.set("later");
            assertNull(rows.receipt().effectiveKeys().getFirst().fingerprint());
        }
    }

    @Test
    void nonlocalDefinitionPolicyRefusesBeforeOpeningAnything() {
        var fixture = new Fixture(true, false, false);
        var options =
                new TablesOptions((build, token) -> CompletableFuture.completedFuture("1.2.3.4"));
        try (var tables =
                new ClientBuildTables(
                        null,
                        fixture.files,
                        "1.2.3.4",
                        fixture.definitions,
                        fixture.readonly,
                        options,
                        new BackgroundBuilds(),
                        new ProtectedIds())) {
            assertEquals(
                    CapturedRowsTypes.CaptureState.UNSUPPORTED,
                    tables.capturePreparedLocal(REF, Cancellation.NONE).join().state());
        }
    }

    @Test
    void oversizedCellsAreLimitedSeparatelyWithoutForgingLegacyIssueChannel() {
        var fixture = new Fixture(true, true, false, "before", "x".repeat(70000));
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            var page = rows.readOrdinalPage(0, 256, Cancellation.NONE).join();
            assertEquals(1, page.rows().size());
            assertEquals("7", page.rows().getFirst().row().values().getFirst());
            assertEquals("", page.rows().getFirst().row().values().get(1));
            assertNull(page.rows().getFirst().row().textIssues());
            assertEquals(1, page.cellIssues().getFirst().columnIndex());
            assertEquals(CapturedRowsTypes.CellState.LIMITED, page.cellIssues().getFirst().state());
            assertNotNull(page.nextOrdinal());
        }
    }

    @Test
    void parentCloseCancelsAndDrainsCaptureStillOpeningItsGraph() throws Exception {
        var fixture = new Fixture(true, false, false);
        var tables = fixture.open();
        fixture.delay.set(true);
        var capturing = tables.capturePreparedLocal(REF, Cancellation.NONE);
        assertTrue(fixture.reading.await(5, TimeUnit.SECONDS));
        var closing = tables.closeAsync();
        assertFalse(closing.isDone());
        assertEquals(0, fixture.filesClosed.get());
        fixture.release.complete(null);
        assertEquals(
                CapturedRowsTypes.CaptureState.CANCELED,
                capturing.get(5, TimeUnit.SECONDS).state());
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(1, fixture.filesClosed.get());
    }

    @Test
    void cancelDeliveryWhileOpenHeldKeepsPrivateRequestUntilActualDrain() throws Exception {
        var fixture = new Fixture(true, false, false);
        var tables = fixture.open();
        fixture.delay.set(true);
        var delivery = tables.capturePreparedLocal(REF, Cancellation.NONE);
        assertTrue(fixture.reading.await(5, TimeUnit.SECONDS));
        assertTrue(delivery.cancel(true));
        assertEquals(1, tables.pendingCaptures());
        var closing = tables.closeAsync();
        assertFalse(closing.isDone());
        assertEquals(0, fixture.filesClosed.get());
        fixture.release.complete(null);
        closing.get(5, TimeUnit.SECONDS);
        assertTrue(delivery.isCancelled());
        assertEquals(0, tables.pendingCaptures());
        assertEquals(0, tables.capturedHandles());
        assertEquals(1, fixture.filesClosed.get());
    }

    @Test
    void canceledDeliveryAfterAdmissionClosesLostHandleAndAllowsNewCapture() throws Exception {
        var fixture = new Fixture(true, false, false);
        try (var tables = fixture.open()) {
            var admitted = new CompletableFuture<CapturedRowsTypes.Capture>();
            var ownershipDone = new AtomicReference<CompletableFuture<Void>>();
            var releaseDelivery = new CountDownLatch(1);
            tables.captureDeliveryProbe =
                    (capture, finished) -> {
                        ownershipDone.set(finished);
                        admitted.complete(capture);
                        try {
                            assertTrue(releaseDelivery.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    };
            var delivery = tables.capturePreparedLocal(REF, Cancellation.NONE);
            var candidate = admitted.get(5, TimeUnit.SECONDS);
            assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, candidate.state());
            assertEquals(1, tables.capturedHandles());
            assertTrue(delivery.cancel(true));
            releaseDelivery.countDown();
            ownershipDone.get().get(5, TimeUnit.SECONDS);
            assertEquals(0, tables.pendingCaptures());
            assertEquals(0, tables.capturedHandles());
            assertEquals(0, fixture.filesClosed.get());
            assertEquals(
                    CapturedRowsTypes.ReadState.CLOSED,
                    candidate.rows().readOrdinalPage(0, 1, Cancellation.NONE).join().state());
            tables.captureDeliveryProbe = (capture, finished) -> {};
            try (var next =
                    tables.capturePreparedLocal(REF, Cancellation.NONE)
                            .get(5, TimeUnit.SECONDS)
                            .rows()) {
                assertNotNull(next);
                assertEquals(1, tables.capturedHandles());
            }
        }
    }

    @Test
    void deliveryCallbackCanSynchronouslyCloseParentAfterPrivateCleanup() throws Exception {
        var fixture = new Fixture(true, false, false);
        var tables = fixture.open();
        tables.capturePreparedLocal(REF, Cancellation.NONE)
                .thenAccept(
                        capture -> {
                            assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, capture.state());
                            tables.closeAsync().join();
                        })
                .get(5, TimeUnit.SECONDS);
        assertEquals(0, tables.pendingCaptures());
        assertEquals(0, tables.capturedHandles());
        assertEquals(1, fixture.filesClosed.get());
    }
}
