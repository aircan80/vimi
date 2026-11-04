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
            this(
                    seed,
                    legacy,
                    keyed,
                    initialKey,
                    replacement,
                    false,
                    Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                    Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true));
        }

        Fixture(
                boolean seed,
                boolean legacy,
                boolean keyed,
                String initialKey,
                String replacement,
                boolean certified,
                com.vibeminer.formats.definitions.DbdDocument document,
                byte[] bytes) {
            this(
                    seed,
                    legacy,
                    keyed,
                    initialKey,
                    replacement,
                    certified,
                    document,
                    bytes,
                    List.of());
        }

        Fixture(
                boolean seed,
                boolean legacy,
                boolean keyed,
                String initialKey,
                String replacement,
                boolean certified,
                com.vibeminer.formats.definitions.DbdDocument document,
                byte[] bytes,
                List<Db2Types.Hole> holes) {
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
            var decoded =
                    Db2Reader.open(
                                    projected(bytes, holes),
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
            var legacySource =
                    GeneratedTableSource.forBlock(
                            block,
                            "enUS",
                            CKEY,
                            limits,
                            build.product() + " " + build.buildConfig() + " " + build.cdnConfig());
            source =
                    certified
                            ? GeneratedTableSource.forNativeBlock(
                                    block, "enUS", CKEY, limits, legacySource.encodingScope())
                            : legacySource;
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
                                        certified
                                                ? GeneratedTableContent.nativeDb2(
                                                        source,
                                                        decoded,
                                                        decoded.sections().stream()
                                                                .filter(
                                                                        Db2Types.Section
                                                                                ::isEncrypted)
                                                                .map(Db2Types.Section::key)
                                                                .distinct()
                                                                .sorted()
                                                                .toList(),
                                                        () -> {},
                                                        holes.stream()
                                                                .map(Db2Types.Hole::name)
                                                                .distinct()
                                                                .sorted()
                                                                .toList(),
                                                        List.of())
                                                : content,
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
    void nativeBoundedReadsAcceptExactLimitsAndRejectEachLowerLimitWithoutInvalidation() {
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                        Db2Fixture.numeric("WDC5", 0, 32, 1234, new long[0], true));
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            var receipt = rows.receipt();
            assertEquals(
                    CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                    receipt.issueChannelEvidence().state());
            var legacy = rows.readOrdinalPage(0, 1, Cancellation.NONE).join();
            int[] exact = new int[6];
            for (int dimension = 0; dimension < 6; dimension++) {
                exact[dimension] = minimumPassing(rows, dimension);
                var passing =
                        rows.readOrdinalPage(
                                        0,
                                        1,
                                        limits(dimension, exact[dimension]),
                                        Cancellation.NONE)
                                .join();
                assertEquals(legacy, passing, "Exact limit dimension " + dimension);
                if (exact[dimension] > 1) {
                    var rejected =
                            rows.readOrdinalPage(
                                            0,
                                            1,
                                            limits(dimension, exact[dimension] - 1),
                                            Cancellation.NONE)
                                    .join();
                    assertLimitPage(receipt, rejected);
                    assertEquals(
                            legacy, rows.readOrdinalPage(0, 1, strict(), Cancellation.NONE).join());
                    assertEquals(legacy, rows.readOrdinalPage(0, 1, Cancellation.NONE).join());
                    assertEquals(
                            receipt,
                            rows.receipt(),
                            "A limit is not new native issue evidence or identity");
                }
            }
            assertEquals(2, exact[3]);
            assertEquals(4, exact[5]);
            assertEquals(1, exact[4], "No issue entries in this numeric native row");
            assertTrue(
                    fixture.memory.has(GeneratedKey.parse(fixture.node)).join(),
                    "No graph discard on caller policy refusal");
        }
    }

    @Test
    void boundedIssueCountRefusalPreservesNativeIssueChannelAndLaterReads() {
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nlocstring Name\nint Value\nint Parent",
                                "$id,noninline$ID<u32>\nName\nValue<u32>\n$noninline,relation$Parent<u32>"),
                        sparseText(new byte[] {(byte) 0xff}));
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            var before = rows.readOrdinalPage(0, 1, Cancellation.NONE).join();
            assertEquals(2, before.rows().getFirst().row().textIssues().size());
            assertLimitPage(
                    rows.receipt(),
                    rows.readOrdinalPage(0, 1, limits(4, 1), Cancellation.NONE).join());
            assertEquals(
                    before, rows.readOrdinalPage(0, 1, limits(4, 2), Cancellation.NONE).join());
            assertEquals(before, rows.readOrdinalPage(0, 1, Cancellation.NONE).join());
            assertTrue(fixture.memory.has(GeneratedKey.parse(fixture.node)).join());
        }
    }

    @Test
    void
            generatedTableBoundedAdapterCombinesRecordAndDecodeLimitsWithoutDiscardingEitherOpenMode() {
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                        Db2Fixture.numeric("WDC5", 0, 32, 1234, new long[0], true));
        var before =
                fixture.writable
                        .savedArtifacts(fixture.source, TactKeyStatus.NONE, Cancellation.NONE)
                        .join();
        for (boolean prepared : List.of(false, true)) {
            var table =
                    (prepared
                                    ? fixture.readonly.openPreparedLocal(
                                            fixture.source, TactKeyStatus.NONE, Cancellation.NONE)
                                    : fixture.writable.open(
                                            fixture.source, TactKeyStatus.NONE, Cancellation.NONE))
                            .join();
            var row = table.readPage(0, 1, Cancellation.NONE).join().getFirst();
            int encoded = com.vibeminer.formats.generated.RowCodec.encode(row).length;
            var generousRead =
                    new com.vibeminer.generated.RecordFile.ReadLimits(
                            256 << 10, 128 << 10, 64 << 10);
            var exactDecode =
                    new com.vibeminer.formats.generated.RowCodec.DecodeLimits(encoded, 2, 1, 4);
            assertEquals(
                    List.of(row),
                    table.readPage(0, 1, generousRead, exactDecode, Cancellation.NONE).join());
            for (boolean decodeRestricts : List.of(false, true)) {
                var read =
                        decodeRestricts
                                ? generousRead
                                : new com.vibeminer.generated.RecordFile.ReadLimits(
                                        256 << 10, 128 << 10, encoded - 1);
                var decode =
                        decodeRestricts
                                ? new com.vibeminer.formats.generated.RowCodec.DecodeLimits(
                                        encoded - 1, 2, 1, 4)
                                : exactDecode;
                var error =
                        assertThrows(
                                java.util.concurrent.CompletionException.class,
                                () -> table.readPage(0, 1, read, decode, Cancellation.NONE).join());
                assertTrue(
                        error.getCause()
                                instanceof com.vibeminer.generated.GeneratedStoreException);
                assertEquals(
                        com.vibeminer.generated.GeneratedTypes.LIMIT,
                        ((com.vibeminer.generated.GeneratedStoreException) error.getCause())
                                .code());
                assertEquals(List.of(row), table.readPage(0, 1, Cancellation.NONE).join());
                assertEquals(
                        before,
                        fixture.writable
                                .savedArtifacts(
                                        fixture.source, TactKeyStatus.NONE, Cancellation.NONE)
                                .join());
            }
        }
    }

    @Test
    void boundedDefaultForCustomCapturesRefusesWithoutCallingLegacyReader() {
        var calls = new AtomicInteger();
        CapturedRows custom =
                new CapturedRows() {
                    public CapturedRowsTypes.Receipt receipt() {
                        throw new AssertionError("No receipt access needed");
                    }

                    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                            long first, int count, Cancellation token) {
                        calls.incrementAndGet();
                        throw new AssertionError("No silent unbounded fallback");
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        try (custom) {
            var error =
                    assertThrows(
                            java.util.concurrent.CompletionException.class,
                            () -> custom.readOrdinalPage(0, 1, strict(), Cancellation.NONE).join());
            assertTrue(error.getCause() instanceof UnsupportedOperationException);
            assertTrue(
                    error.getCause() instanceof CapturedRowsTypes.BoundedReadUnsupportedException);
            assertNull(error.getCause().getCause());
            assertEquals(0, error.getCause().getSuppressed().length);
            for (var constructor :
                    CapturedRowsTypes.BoundedReadUnsupportedException.class
                            .getDeclaredConstructors()) {
                assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void arbitraryProviderUnsupportedFailureDoesNotBecomeDefaultCapabilityMarker() {
        var failure = new UnsupportedOperationException("Authored unexpected provider failure");
        CapturedRows custom =
                new CapturedRows() {
                    public CapturedRowsTypes.Receipt receipt() {
                        throw new AssertionError("No receipt access");
                    }

                    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                            long first, int count, Cancellation token) {
                        throw new AssertionError("No fallback");
                    }

                    @Override
                    public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                            long first,
                            int count,
                            CapturedRowsTypes.ReadLimits limits,
                            Cancellation token) {
                        return CompletableFuture.failedFuture(failure);
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        try (custom) {
            var error =
                    assertThrows(
                            java.util.concurrent.CompletionException.class,
                            () -> custom.readOrdinalPage(0, 1, strict(), Cancellation.NONE).join());
            assertTrue(error.getCause() == failure);
            assertFalse(
                    error.getCause() instanceof CapturedRowsTypes.BoundedReadUnsupportedException);
        }
    }

    @Test
    void boundedReadCancellationAndCloseDrainHeldLowerReadWithoutChangingIdentity()
            throws Exception {
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                        Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true));
        try (var tables = fixture.open();
                var rows = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            var receipt = rows.receipt();
            var canceled = new Cancellation();
            canceled.cancel();
            assertEquals(
                    CapturedRowsTypes.ReadState.CANCELED,
                    rows.readOrdinalPage(0, 1, strict(), canceled).join().state());
            assertEquals(
                    CapturedRowsTypes.ReadState.READ,
                    rows.readOrdinalPage(0, 1, strict(), Cancellation.NONE).join().state());
            fixture.delay.set(true);
            var pending = rows.readOrdinalPage(0, 1, strict(), Cancellation.NONE);
            assertTrue(fixture.reading.await(2, TimeUnit.SECONDS));
            var closed = rows.closeAsync();
            assertFalse(closed.isDone(), "Close waits for actual lower owner drain");
            fixture.release.complete(null);
            assertEquals(
                    CapturedRowsTypes.ReadState.CANCELED, pending.get(2, TimeUnit.SECONDS).state());
            closed.get(2, TimeUnit.SECONDS);
            assertEquals(
                    CapturedRowsTypes.ReadState.CLOSED,
                    rows.readOrdinalPage(0, 1, strict(), Cancellation.NONE).join().state());
            assertEquals(receipt, rows.receipt());
        }
    }

    private static CapturedRowsTypes.ReadLimits strict() {
        return limits(-1, 0);
    }

    private static CapturedRowsTypes.ReadLimits limits(int dimension, int maximum) {
        int[] values = {256 << 10, 128 << 10, 64 << 10, 128, 128, 64 << 10};
        if (dimension >= 0) {
            values[dimension] = maximum;
        }
        return new CapturedRowsTypes.ReadLimits(
                values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static int minimumPassing(CapturedRows rows, int dimension) {
        int low = 1,
                high =
                        switch (dimension) {
                            case 0 -> 256 << 10;
                            case 1 -> 128 << 10;
                            case 2, 5 -> 64 << 10;
                            default -> 128;
                        };
        while (low < high) {
            int middle = low + (high - low) / 2;
            var page =
                    rows.readOrdinalPage(0, 1, limits(dimension, middle), Cancellation.NONE).join();
            if (page.state() == CapturedRowsTypes.ReadState.READ) {
                high = middle;
            } else {
                assertEquals(CapturedRowsTypes.ReadState.LIMIT_EXCEEDED, page.state());
                low = middle + 1;
            }
        }
        return low;
    }

    private static void assertLimitPage(
            CapturedRowsTypes.Receipt receipt, CapturedRowsTypes.Page page) {
        assertEquals(CapturedRowsTypes.ReadState.LIMIT_EXCEEDED, page.state());
        assertEquals("CAPTURE_READ_LIMIT", page.code());
        assertEquals(receipt.graphIdentity(), page.identity());
        assertTrue(page.rows().isEmpty());
        assertTrue(page.cellIssues().isEmpty());
        assertNull(page.nextOrdinal());
    }

    @Test
    void auditedReaderStoreReopenCaptureNormalizesOnlyCertifiedRowsIncludingCopies() {
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                        Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true));
        var saved =
                fixture.readonly
                        .openPreparedLocal(fixture.source, TactKeyStatus.NONE, Cancellation.NONE)
                        .join();
        assertNotNull(saved.issueCertificate());
        assertNull(saved.readPage(0, 1, Cancellation.NONE).join().getFirst().textIssues());
        try (var tables = fixture.open();
                var captured = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            assertEquals(
                    CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                    captured.receipt().issueChannelEvidence().state());
            assertEquals(
                    CapturedRowsTypes.Membership.NOT_CERTIFIED, captured.receipt().membership());
            for (int ordinal = 0; ordinal < 2; ordinal++) {
                var page = captured.readOrdinalPage(ordinal, 1, Cancellation.NONE).join();
                assertEquals(CapturedRowsTypes.ReadState.READ, page.state());
                assertEquals(List.of(), page.rows().getFirst().row().textIssues());
            }
        }
    }

    @Test
    void certifiedSparseTextPreservesInvalidIssuesAndNumericSibling() {
        for (boolean invalid : List.of(false, true)) {
            var bytes = Db2Fixture.sparse();
            if (invalid) {
                bytes[300] = (byte) 0xff;
            }
            var fixture =
                    new Fixture(
                            true,
                            false,
                            false,
                            "before",
                            null,
                            true,
                            Db2Fixture.definition(
                                    "int ID\nlocstring Name\nint Value",
                                    "$id,noninline$ID<u32>\nName\nValue<u32>"),
                            bytes);
            try (var tables = fixture.open();
                    var captured =
                            tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
                var page = captured.readOrdinalPage(0, 1, Cancellation.NONE).join();
                assertEquals(CapturedRowsTypes.ReadState.READ, page.state());
                var row = page.rows().getFirst().row();
                assertEquals("123", row.values().getLast());
                if (invalid) {
                    assertEquals("DB2_TEXT_ENCODING_INVALID", row.textIssues().getFirst().code());
                    assertTrue(row.textIssues().getFirst().rawBytesHex().startsWith("ff"));
                } else {
                    assertEquals("café", row.values().get(1));
                    assertEquals(List.of(), row.textIssues());
                }
            }
        }
    }

    @Test
    void nativeCorruptionCannotFallBackToAnOtherwiseReadableLegacyGraph() {
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                        Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true));
        var nativeTable =
                fixture.writable
                        .openPreparedLocal(fixture.source, TactKeyStatus.NONE, Cancellation.NONE)
                        .join();
        fixture.writable
                .store(
                        fixture.source.legacy(),
                        TactKeyStatus.NONE,
                        new GeneratedTableContent(
                                nativeTable.header(),
                                nativeTable.sections(),
                                nativeTable.completion(),
                                nativeTable.keysUsed(),
                                nativeTable::readPage,
                                () -> {},
                                List.of(),
                                List.of()),
                        Cancellation.NONE)
                .join();
        fixture.memory.damage(GeneratedKey.parse(nativeTable.identity()));
        int puts = fixture.memory.puts();
        try (var tables = fixture.open()) {
            var result = tables.capturePreparedLocal(REF, Cancellation.NONE).join();
            assertEquals(CapturedRowsTypes.CaptureState.CORRUPT, result.state());
            assertNull(result.rows());
        }
        assertEquals(puts, fixture.memory.puts());
        assertNotNull(
                fixture.writable
                        .openPreparedLocal(
                                fixture.source.legacy(), TactKeyStatus.NONE, Cancellation.NONE)
                        .join());
    }

    @Test
    void certifiedKnownEmptyTextIsExplicitlyIssueFreeAtCaptureOnly() {
        var original = Db2Fixture.sparse();
        var bytes = new byte[original.length - 4];
        System.arraycopy(original, 0, bytes, 0, 300);
        System.arraycopy(original, 312, bytes, 308, 14);
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(224, 308);
        buffer.putShort(316, (short) 8);
        buffer.putInt(301, 123);
        var fixture =
                new Fixture(
                        true,
                        false,
                        false,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nlocstring Name\nint Value",
                                "$id,noninline$ID<u32>\nName\nValue<u32>"),
                        bytes);
        try (var tables = fixture.open();
                var captured = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            var row =
                    captured.readOrdinalPage(0, 1, Cancellation.NONE)
                            .join()
                            .rows()
                            .getFirst()
                            .row();
            assertEquals("", row.values().get(1));
            assertEquals("123", row.values().getLast());
            assertEquals(List.of(), row.textIssues());
        }
    }

    private static byte[] sparseText(byte[] text) {
        var original = Db2Fixture.sparse();
        int width = (text.length + 1 + 4 + 3) & ~3;
        var bytes = new byte[300 + width + 14];
        System.arraycopy(original, 0, bytes, 0, 300);
        System.arraycopy(text, 0, bytes, 300, text.length);
        System.arraycopy(original, 312, bytes, 300 + width, 14);
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(184, 1);
        buffer.putInt(224, 300 + width);
        buffer.putShort(308 + width, (short) width);
        buffer.putInt(301 + text.length, 123);
        return bytes;
    }

    @Test
    void
            nativeIssueBudgetRetainsInvalidPrefixAndLimitsUnaccountedChannelWithoutLosingNumericSibling() {
        for (int length : List.of(8000, 8171, 8172)) {
            var invalid = new byte[length];
            java.util.Arrays.fill(invalid, (byte) 0xff);
            var fixture =
                    new Fixture(
                            true,
                            false,
                            false,
                            "before",
                            null,
                            true,
                            Db2Fixture.definition(
                                    "int ID\nlocstring Name\nint Value\nint Parent",
                                    "$id,noninline$ID<u32>\nName\nValue<u32>\n$noninline,relation$Parent<u32>"),
                            sparseText(invalid));
            try (var tables = fixture.open();
                    var captured =
                            tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
                var page = captured.readOrdinalPage(0, 1, Cancellation.NONE).join();
                assertEquals(CapturedRowsTypes.ReadState.READ, page.state());
                assertEquals(
                        CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                        captured.receipt().issueChannelEvidence().state());
                assertEquals("123", page.rows().getFirst().row().values().get(2));
                var issues = page.rows().getFirst().row().textIssues();
                if (length == 8000) {
                    assertEquals(2, issues.size());
                    assertEquals("DB2_RELATION_ABSENT", issues.getLast().code());
                    assertEquals(List.of(), page.cellIssues());
                } else {
                    assertEquals(length == 8171 ? 1 : 0, issues.size());
                    assertEquals(-1, page.cellIssues().getFirst().columnIndex());
                    assertEquals(
                            "CAPTURE_ISSUE_CHANNEL_LIMIT", page.cellIssues().getFirst().code());
                }
            }
        }
    }

    @Test
    void legacyExplicitEmptyChannelStaysEmptyWithoutAcquiringNativeEvidence() {
        var fixture = new Fixture(false, false, false);
        var decoded =
                Db2Reader.open(
                                Db2ByteSource.memory(
                                        Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true)),
                                Db2Fixture.definition(
                                        "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                                "1.2.3.4",
                                fixture.source.readerLimits(),
                                Cancellation.NONE)
                        .join();
        var saved =
                fixture.writable
                        .store(
                                fixture.source,
                                TactKeyStatus.NONE,
                                new GeneratedTableContent(
                                        decoded.header(),
                                        decoded.sections(),
                                        decoded.completion(),
                                        List.of(),
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
                                                                                                        row
                                                                                                                .values(),
                                                                                                        List
                                                                                                                .of()))
                                                                                .toList()),
                                        () -> {},
                                        List.of(),
                                        List.of()),
                                Cancellation.NONE)
                        .join();
        int puts = fixture.memory.puts();
        try (var tables = fixture.open();
                var captured = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            assertEquals(
                    CapturedRowsTypes.IssueChannelState.UNKNOWN,
                    captured.receipt().issueChannelEvidence().state());
            assertEquals(saved.identity(), captured.receipt().graphIdentity());
            assertEquals(
                    CapturedRowsTypes.Membership.NOT_CERTIFIED, captured.receipt().membership());
            assertEquals(
                    List.of(),
                    captured.readOrdinalPage(0, 1, Cancellation.NONE)
                            .join()
                            .rows()
                            .getFirst()
                            .row()
                            .textIssues());
        }
        assertEquals(puts, fixture.memory.puts());
    }

    @Test
    void nativeIssueEvidenceAcceptsExactly65536BytesAndLimitsTheNextEightBytes() {
        for (int secondLength : List.of(4076, 4077)) {
            byte[] first = new byte[4075], second = new byte[secondLength];
            java.util.Arrays.fill(first, (byte) 0xff);
            java.util.Arrays.fill(second, (byte) 0xff);
            int start = 204 + 40 + 3 * 28;
            int width = (first.length + second.length + 2 + 4 + 3) & ~3;
            var buffer =
                    java.nio.ByteBuffer.allocate(start + width + 14)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.put("WDC5".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(5);
            buffer.putInt(136, 1)
                    .putInt(140, 3)
                    .putInt(144, 12)
                    .putInt(152, 0x11223344)
                    .putInt(156, 0x01020304);
            buffer.putShort(172, (short) 5).putInt(176, 3).putInt(188, 72).putInt(200, 1);
            buffer.position(204);
            buffer.putLong(0)
                    .putInt(start)
                    .putInt(1)
                    .putInt(0)
                    .putInt(start + width)
                    .putInt(4)
                    .putInt(0)
                    .putInt(1)
                    .putInt(0);
            for (int field = 0; field < 3; field++) {
                buffer.putShort((short) 0).putShort((short) (field * 4));
            }
            for (int field = 0; field < 3; field++) {
                buffer.putShort((short) (field * 32))
                        .putShort((short) 32)
                        .putInt(0)
                        .putInt(0)
                        .putInt(0)
                        .putInt(0)
                        .putInt(0);
            }
            assertEquals(start, buffer.position());
            buffer.put(first).put((byte) 0).put(second).put((byte) 0).putInt(123);
            buffer.position(start + width);
            buffer.putInt(7).putInt(start).putShort((short) width).putInt(7);
            var fixture =
                    new Fixture(
                            true,
                            false,
                            false,
                            "before",
                            null,
                            true,
                            Db2Fixture.definition(
                                    "int ID\nlocstring First\nlocstring Second\nint Value",
                                    "$id,noninline$ID<u32>\nFirst\nSecond\nValue<u32>"),
                            buffer.array());
            try (var tables = fixture.open();
                    var captured =
                            tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
                var page = captured.readOrdinalPage(0, 1, Cancellation.NONE).join();
                assertEquals(CapturedRowsTypes.ReadState.READ, page.state());
                var row = page.rows().getFirst().row();
                assertEquals("123", row.values().getLast());
                assertEquals(secondLength == 4076 ? 2 : 1, row.textIssues().size());
                long retained =
                        row.textIssues().stream()
                                .mapToLong(
                                        issue ->
                                                64L
                                                        + 4L * issue.code().length()
                                                        + 4L * issue.rawBytesHex().length())
                                .sum();
                if (secondLength == 4076) {
                    assertEquals(65536, retained);
                    assertEquals(List.of(), page.cellIssues());
                } else {
                    assertTrue(retained < 65536);
                    assertEquals(-1, page.cellIssues().getFirst().columnIndex());
                    assertEquals(
                            "CAPTURE_ISSUE_CHANNEL_LIMIT", page.cellIssues().getFirst().code());
                }
            }
        }
    }

    private static Db2ByteSource projected(byte[] bytes, List<Db2Types.Hole> holes) {
        return new Db2ByteSource() {
            public long length() {
                return bytes.length;
            }

            public List<Db2Types.Hole> holes() {
                return holes;
            }

            public CompletableFuture<Void> readRange(
                    long offset, java.nio.ByteBuffer destination, Cancellation token) {
                token.check();
                long end = offset + destination.remaining();
                assertTrue(
                        holes.stream()
                                .noneMatch(
                                        hole ->
                                                offset < hole.offset() + hole.length()
                                                        && hole.offset() < end),
                        "The native reader must not touch hidden bytes");
                destination.put(bytes, (int) offset, destination.remaining());
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Test
    void
            certifiedPartialGraphKeepsReadableLocalizedTextAndResolvedTextCopyWithoutMembershipClaims() {
        int start = 320;
        byte[] text = "café\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int hidden = start + 4 + text.length + 4 + 8;
        var buffer =
                java.nio.ByteBuffer.allocate(hidden + 4 + text.length + 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put("WDC5".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(5);
        buffer.putInt(136, 2)
                .putInt(140, 1)
                .putInt(144, 4)
                .putInt(148, text.length * 2)
                .putInt(152, 0x11223344)
                .putInt(156, 0x01020304);
        buffer.putShort(172, (short) 4).putInt(176, 1).putInt(188, 24).putInt(200, 2);
        buffer.position(204);
        buffer.putLong(0)
                .putInt(start)
                .putInt(1)
                .putInt(text.length)
                .putInt(0)
                .putInt(4)
                .putInt(0)
                .putInt(0)
                .putInt(1);
        buffer.putLong(123)
                .putInt(hidden)
                .putInt(1)
                .putInt(text.length)
                .putInt(0)
                .putInt(4)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        buffer.putShort((short) 0).putShort((short) 0);
        buffer.putShort((short) 0)
                .putShort((short) 32)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        buffer.putInt(1).putInt(21); // Clear keyed-physical ID inventory.
        assertEquals(start, buffer.position());
        buffer.putInt(8).put(text).putInt(7).putInt(8).putInt(7);
        assertEquals(hidden, buffer.position());
        buffer.putInt(4 + text.length).put(text).putInt(21);
        String sectionKey = "000000000000007B";
        var fixture =
                new Fixture(
                        true,
                        false,
                        true,
                        "before",
                        null,
                        true,
                        Db2Fixture.definition(
                                "int ID\nlocstring Name", "$id,noninline$ID<u32>\nName"),
                        buffer.array(),
                        List.of(new Db2Types.Hole(hidden, buffer.capacity() - hidden, sectionKey)));
        var saved =
                fixture.readonly
                        .openPreparedLocal(fixture.source, name -> "before", Cancellation.NONE)
                        .join();
        assertTrue(saved.scoped());
        assertNull(saved.readPage(0, 1, Cancellation.NONE).join().getFirst().textIssues());
        try (var tables = fixture.open();
                var captured = tables.capturePreparedLocal(REF, Cancellation.NONE).join().rows()) {
            assertEquals(TableTypes.State.PARTIAL, captured.receipt().table().state());
            assertEquals(1, captured.receipt().completion().excludedEncryptedSections());
            assertEquals(1, captured.receipt().completion().excludedPhysicalRecords());
            assertEquals(2, captured.receipt().rowCount());
            assertEquals(
                    CapturedRowsTypes.Membership.NOT_CERTIFIED, captured.receipt().membership());
            assertEquals(
                    CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                    captured.receipt().issueChannelEvidence().state());
            for (int ordinal = 0; ordinal < 2; ordinal++) {
                var page = captured.readOrdinalPage(ordinal, 1, Cancellation.NONE).join();
                assertEquals(CapturedRowsTypes.ReadState.READ, page.state());
                var row = page.rows().getFirst().row();
                assertEquals("café", row.values().get(1));
                assertEquals(List.of(), row.textIssues());
                assertEquals(ordinal == 1, row.isCopy());
                if (row.isCopy()) {
                    assertEquals(7L, row.copySourceId());
                }
            }
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
            // Keep opening incomplete until capturePreparedLocal has installed its private
            // completion handler and returned the public delivery future. Otherwise the delivery
            // probe can run inline on this test thread and time out before it can be released.
            fixture.delay.set(true);
            var delivery = tables.capturePreparedLocal(REF, Cancellation.NONE);
            assertTrue(fixture.reading.await(5, TimeUnit.SECONDS));
            var openingReleased = CompletableFuture.runAsync(() -> fixture.release.complete(null));
            var candidate = admitted.get(5, TimeUnit.SECONDS);
            assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, candidate.state());
            assertEquals(1, tables.capturedHandles());
            assertTrue(delivery.cancel(true));
            releaseDelivery.countDown();
            ownershipDone.get().get(5, TimeUnit.SECONDS);
            openingReleased.get(5, TimeUnit.SECONDS);
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
