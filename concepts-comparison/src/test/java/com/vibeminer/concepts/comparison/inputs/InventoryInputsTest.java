package com.vibeminer.concepts.comparison.inputs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class InventoryInputsTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private static final String CKEY = "c".repeat(32);

    private static ComparisonTypes.Descriptor descriptor(boolean tables) {
        return new ComparisonTypes.Descriptor(
                "snapshot",
                "wowf",
                "enUS",
                tables ? TableInventoryInput.NAMESPACE : FilesInventoryInput.NAMESPACE,
                tables ? TableInventoryInput.SCOPE : FilesInventoryInput.SCOPE,
                Map.of());
    }

    private static BuildFiles files(AsyncCursor<FileTypes.BuildFile> cursor) {
        return (BuildFiles)
                Proxy.newProxyInstance(
                        BuildFiles.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "build" -> BUILD;
                                    case "locale" -> "enUS";
                                    case "listMetadata" -> {
                                        assertEquals(FileTypes.FileFilter.ALL, args[0]);
                                        yield cursor;
                                    }
                                    default ->
                                            throw new AssertionError(
                                                    "Forbidden payload/network/borrowed close call: "
                                                            + method.getName());
                                });
    }

    private static AsyncCursor<FileTypes.BuildFile> pages(
            List<List<FileTypes.BuildFile>> pages, AtomicInteger closed) {
        return new AsyncCursor<>() {
            int offset;

            @Override
            public CompletableFuture<List<FileTypes.BuildFile>> next(
                    int limit, Cancellation token) {
                token.check();
                return CompletableFuture.completedFuture(
                        offset < pages.size() ? pages.get(offset++) : List.of());
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
    }

    private static FileTypes.BuildFile file(long id, String path, FileTypes.FileStorage storage) {
        return new FileTypes.BuildFile(
                id,
                path,
                FileTypes.FilePathSource.LISTFILE,
                12L,
                CKEY,
                FileTypes.FileFormat.BLP,
                FileTypes.FileFormatSource.EXTENSION,
                storage,
                true);
    }

    @Test
    void metadataListingNeverFallsBackToPayloadAndDoesNotClaimCompleteMembership() {
        var closed = new AtomicInteger();
        var input =
                new FilesInventoryInput(
                        files(
                                pages(
                                        List.of(
                                                List.of(
                                                        file(
                                                                1,
                                                                "a.blp",
                                                                FileTypes.FileStorage.STORED)),
                                                List.of(
                                                        file(
                                                                2,
                                                                "renamed.blp",
                                                                FileTypes.FileStorage.NOT_STORED))),
                                        closed)),
                        descriptor(false),
                        null);
        try {
            var first = input.read(null, 1, Cancellation.NONE).join();
            assertEquals("00000000000000000001", first.rows().getFirst().key());
            assertFalse(first.rows().getFirst().fields().containsKey("path"));
            assertFalse(first.rows().getFirst().fields().containsKey("format"));
            assertFalse(first.rows().getFirst().fields().containsKey("storage"));
            assertEquals(
                    "unassessed-per-batch", input.descriptor().dependencies().get("files.names"));
            var second = input.read(first.nextCursor(), 1, Cancellation.NONE).join();
            assertFalse(second.rows().getFirst().fields().containsKey("storage"));
            var end = input.read(second.nextCursor(), 1, Cancellation.NONE).join();
            assertTrue(end.rows().isEmpty());
            assertEquals(ComparisonTypes.Completeness.UNKNOWN, end.coverage().completeness());
            assertTrue(end.coverage().issues().stream().anyMatch(i -> i.contains("not certified")));
        } finally {
            input.closeAsync().join();
        }
        assertEquals(1, closed.get());
    }

    @Test
    void unpinnedNameAndStorageRefreshDoesNotProduceContentOrUnknownFindings(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var a =
                new FilesInventoryInput(
                        files(
                                pages(
                                        List.of(
                                                List.of(
                                                        file(
                                                                1,
                                                                "old.blp",
                                                                FileTypes.FileStorage.NOT_STORED))),
                                        new AtomicInteger())),
                        descriptor(false),
                        null);
        var b =
                new FilesInventoryInput(
                        files(
                                pages(
                                        List.of(
                                                List.of(
                                                        file(
                                                                1,
                                                                "new.blp",
                                                                FileTypes.FileStorage.STORED))),
                                        new AtomicInteger())),
                        descriptor(false),
                        null);
        try (var service = new com.vibeminer.concepts.comparison.ComparisonService(temporary)) {
            var progress = service.start(a, b).join();
            assertEquals(1, progress.observed());
            assertTrue(service.page(0, 10).join().rows().isEmpty());
        }
    }

    @Test
    void frozenNamesRetainPublicationIdentityAndRemainExternalEvidence() {
        var input =
                new FilesInventoryInput(
                        files(
                                pages(
                                        List.of(
                                                List.of(
                                                        file(
                                                                3,
                                                                "image.blp",
                                                                FileTypes.FileStorage.STORED))),
                                        new AtomicInteger())),
                        descriptor(false),
                        "names:abc");
        try {
            var page = input.read(null, 4, Cancellation.NONE).join();
            var path = page.rows().getFirst().fields().get("path");
            assertEquals("image.blp", path.value());
            assertEquals(ComparisonTypes.Aspect.EXTERNAL_EVIDENCE, path.aspect());
            assertEquals("names:abc", input.descriptor().dependencies().get("files.names"));
            assertEquals("BLP", page.rows().getFirst().fields().get("format").value());
        } finally {
            input.closeAsync().join();
        }
    }

    @Test
    void invalidMetadataStaysTypedAndMissingNamesAreNotKnownEmpty() {
        var malformed =
                new FileTypes.BuildFile(
                        4,
                        null,
                        FileTypes.FilePathSource.NONE,
                        -1L,
                        "bad",
                        FileTypes.FileFormat.UNKNOWN,
                        FileTypes.FileFormatSource.NONE,
                        null,
                        false);
        var input =
                new FilesInventoryInput(
                        files(pages(List.of(List.of(malformed)), new AtomicInteger())),
                        descriptor(false),
                        "frozen");
        try {
            var row = input.read(null, 10, Cancellation.NONE).join().rows().getFirst();
            assertEquals(
                    ComparisonTypes.ValueState.INVALID, row.fields().get("decodedSize").state());
            assertEquals(
                    ComparisonTypes.ValueState.INVALID, row.fields().get("contentKey").state());
            assertEquals(ComparisonTypes.ValueState.UNKNOWN, row.fields().get("path").state());
        } finally {
            input.closeAsync().join();
        }
    }

    @Test
    void nonAdvancingIdsAndOversizedPagesRejectInsteadOfFabricatingExhaustion() {
        for (var batches :
                List.of(
                        List.of(List.of(file(2, null, null)), List.of(file(1, null, null))),
                        List.of(List.of(file(1, null, null), file(2, null, null))))) {
            var input =
                    new FilesInventoryInput(
                            files(pages(batches, new AtomicInteger())), descriptor(false), null);
            try {
                if (batches.size() == 2) {
                    var first = input.read(null, 1, Cancellation.NONE).join();
                    assertThrows(
                            RuntimeException.class,
                            () -> input.read(first.nextCursor(), 1, Cancellation.NONE).join());
                } else {
                    assertThrows(
                            RuntimeException.class,
                            () -> input.read(null, 1, Cancellation.NONE).join());
                }
                assertThrows(
                        RuntimeException.class,
                        () -> input.read(null, 1, Cancellation.NONE).join());
            } finally {
                input.closeAsync().join();
            }
        }
    }

    @Test
    void staleCursorAndForeignDescriptorAreRejected() {
        var borrowed = files(pages(List.of(List.of(file(1, null, null))), new AtomicInteger()));
        var wrong =
                new ComparisonTypes.Descriptor(
                        "x",
                        "wow",
                        "enUS",
                        FilesInventoryInput.NAMESPACE,
                        FilesInventoryInput.SCOPE,
                        Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FilesInventoryInput(borrowed, wrong, null));
        var input = new FilesInventoryInput(borrowed, descriptor(false), null);
        try {
            var first = input.read(null, 1, Cancellation.NONE).join();
            assertNotNull(first.nextCursor());
            assertThrows(
                    RuntimeException.class, () -> input.read(null, 1, Cancellation.NONE).join());
        } finally {
            input.closeAsync().join();
        }
    }

    @Test
    void closeCancelsAcceptedReadDrainsCursorAndLeavesBorrowedHandleOpen() throws Exception {
        var started = new CountDownLatch(1);
        var closed = new AtomicInteger();
        AsyncCursor<FileTypes.BuildFile> cursor =
                new AsyncCursor<>() {
                    @Override
                    public CompletableFuture<List<FileTypes.BuildFile>> next(
                            int limit, Cancellation token) {
                        var future = new CompletableFuture<List<FileTypes.BuildFile>>();
                        token.onCancel(
                                () ->
                                        future.completeExceptionally(
                                                new java.util.concurrent.CancellationException()));
                        started.countDown();
                        return future;
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
        var input = new FilesInventoryInput(files(cursor), descriptor(false), null);
        var pending = input.read(null, 10, Cancellation.NONE);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        input.closeAsync().get(5, TimeUnit.SECONDS);
        assertThrows(RuntimeException.class, pending::join);
        assertEquals(1, closed.get());
        assertThrows(RuntimeException.class, () -> input.read(null, 1, Cancellation.NONE).join());
    }

    @Test
    void cancellationCallbackFailureDoesNotSkipCursorCleanup() throws Exception {
        var started = new CountDownLatch(1);
        var closed = new AtomicInteger();
        AsyncCursor<FileTypes.BuildFile> cursor =
                new AsyncCursor<>() {
                    @Override
                    public CompletableFuture<List<FileTypes.BuildFile>> next(
                            int limit, Cancellation token) {
                        var pending = new CompletableFuture<List<FileTypes.BuildFile>>();
                        token.onCancel(
                                () -> {
                                    pending.completeExceptionally(
                                            new java.util.concurrent.CancellationException());
                                    throw new IllegalStateException(
                                            "Authored cancellation failure");
                                });
                        started.countDown();
                        return pending;
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
        var input = new FilesInventoryInput(files(cursor), descriptor(false), null);
        var pending = input.read(null, 1, Cancellation.NONE);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> input.closeAsync().get(5, TimeUnit.SECONDS));
        assertEquals(1, closed.get());
        assertThrows(RuntimeException.class, pending::join);
    }

    private static TableTypes.Entry entry(long id, TableTypes.State state) {
        return new TableTypes.Entry(
                id,
                "Table" + id,
                state,
                true,
                null,
                new TableTypes.Counts(12, 10, 1, 2, "WDC5", "ABCDEF12"),
                CKEY);
    }

    private static TableInventoryInput.KnownInventory receipt(
            TableTypes.PreloadOutcome outcome, List<TableTypes.Entry> entries) {
        return new TableInventoryInput.KnownInventory(
                new TableTypes.PreloadResult(
                        outcome, null, entries, FileTypes.FilesOpenState.OPEN, null, null),
                "preparation:123",
                "definitions:456");
    }

    @Test
    void completedKnownReceiptCertifiesOnlyItsNamedScopeAfterSortedExhaustion() {
        var input =
                new TableInventoryInput(
                        descriptor(true),
                        receipt(
                                TableTypes.PreloadOutcome.COMPLETED,
                                List.of(
                                        entry(9, TableTypes.State.NOT_IN_BUILD),
                                        entry(2, TableTypes.State.PARTIAL))),
                        Map.of());
        try {
            var first = input.read(null, 1, Cancellation.NONE).join();
            assertTrue(first.rows().getFirst().key().endsWith("02"));
            assertEquals(ComparisonTypes.Completeness.UNKNOWN, first.coverage().completeness());
            var last = input.read(first.nextCursor(), 1, Cancellation.NONE).join();
            assertEquals(ComparisonTypes.Completeness.COMPLETE, last.coverage().completeness());
            assertEquals(TableInventoryInput.SCOPE, last.coverage().scope());
            assertTrue(
                    last.coverage().issues().stream().anyMatch(i -> i.contains("not every DB2")));
            assertEquals(
                    ComparisonTypes.Aspect.AVAILABILITY,
                    last.rows().getFirst().fields().get("state").aspect());
            assertFalse(last.rows().getFirst().fields().containsKey("schema"));
            assertEquals(
                    "unassessed-not-captured",
                    input.descriptor().dependencies().get("tables.schemas"));
        } finally {
            input.closeAsync().join();
        }
    }

    @Test
    void incompleteReceiptAndProtectedSchemaRetainReadableCounts() {
        var input =
                new TableInventoryInput(
                        descriptor(true),
                        receipt(
                                TableTypes.PreloadOutcome.INCOMPLETE,
                                List.of(entry(1, TableTypes.State.ENCRYPTED))),
                        Map.of(
                                1L,
                                new TableTypes.SchemaResult(
                                        entry(1, TableTypes.State.ENCRYPTED), null, null)));
        try {
            var page = input.read(null, 3, Cancellation.NONE).join();
            assertEquals(ComparisonTypes.Completeness.PARTIAL, page.coverage().completeness());
            assertEquals("12", page.rows().getFirst().fields().get("declaredRows").value());
            assertEquals(
                    ComparisonTypes.ValueState.PROTECTED,
                    page.rows().getFirst().fields().get("schema").state());
            assertEquals(
                    ComparisonTypes.Aspect.CONTENT,
                    page.rows().getFirst().fields().get("declaredRows").aspect());
        } finally {
            input.closeAsync().join();
        }
    }

    private static TableTypes.SchemaResult schema(TableTypes.Entry entry, String comment) {
        var columns =
                List.of(
                        new Db2Types.Column(
                                "Candidate",
                                "integer",
                                false,
                                new Db2Types.Interpretation(
                                        "raw-physical-integer-v1",
                                        32,
                                        false,
                                        8,
                                        false,
                                        "Preserved raw physical value"),
                                null));
        var candidate =
                new TableSchema.SchemaColumn(
                        "Candidate",
                        TableSchema.ColumnKind.INT,
                        8,
                        false,
                        1,
                        new TableSchema.ForeignKey("Other", "ID"),
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONINLINE,
                        false,
                        WowLocale.parse("enUS"),
                        comment,
                        null);
        return new TableTypes.SchemaResult(
                entry,
                columns,
                new TableSchema(
                        List.of(candidate),
                        List.of("ABCDEF12"),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.parse("enUS"),
                        null));
    }

    @Test
    void schemaPreservesRawPhysicalAndUnverifiedCandidateMetadataWithoutGameSemantics() {
        var entry = entry(1, TableTypes.State.READY);
        var input =
                new TableInventoryInput(
                        descriptor(true),
                        receipt(TableTypes.PreloadOutcome.COMPLETED, List.of(entry)),
                        Map.of(1L, schema(entry, null)));
        try {
            var fields = input.read(null, 1, Cancellation.NONE).join().rows().getFirst().fields();
            assertTrue(fields.get("schema.physical.0").value().contains("raw-physical-integer-v1"));
            assertEquals(
                    ComparisonTypes.ValueState.KNOWN_EMPTY, fields.get("schema.comment").state());
            assertTrue(fields.get("schema.candidate.0").value().contains("Candidate"));
            assertTrue(fields.get("schema.candidate.0").value().contains("Other"));
            assertEquals(
                    ComparisonTypes.Aspect.INTERPRETATION,
                    fields.get("schema.candidate.0").aspect());
        } finally {
            input.closeAsync().join();
        }
    }

    @Test
    void changedSchemaSourceIsUnknownAndOversizedMetadataIsLimited() {
        var original = entry(1, TableTypes.State.READY);
        var changed =
                new TableTypes.Entry(
                        1,
                        "Table1",
                        TableTypes.State.READY,
                        true,
                        null,
                        original.counts(),
                        "d".repeat(32));
        for (var snapshot : List.of(schema(changed, null), schema(original, "x".repeat(9000)))) {
            var input =
                    new TableInventoryInput(
                            descriptor(true),
                            receipt(TableTypes.PreloadOutcome.COMPLETED, List.of(original)),
                            Map.of(1L, snapshot));
            try {
                var fields =
                        input.read(null, 1, Cancellation.NONE).join().rows().getFirst().fields();
                if (snapshot.table() == changed) {
                    assertEquals(ComparisonTypes.ValueState.UNKNOWN, fields.get("schema").state());
                    assertFalse(fields.containsKey("schema.candidate.0"));
                } else {
                    assertEquals(
                            ComparisonTypes.ValueState.LIMITED,
                            fields.get("schema.candidate.0").state());
                }
            } finally {
                input.closeAsync().join();
            }
        }
    }

    @Test
    void missingReceiptIdentityDuplicateIdsAndOversizedInventoryFailBeforeSourceWork() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TableInventoryInput.KnownInventory(
                                receipt(TableTypes.PreloadOutcome.COMPLETED, List.of()).result(),
                                "",
                                "defs"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TableInventoryInput(
                                descriptor(true),
                                receipt(
                                        TableTypes.PreloadOutcome.COMPLETED,
                                        List.of(
                                                entry(1, TableTypes.State.READY),
                                                entry(1, TableTypes.State.READY))),
                                Map.of()));
        var entries = new ArrayList<TableTypes.Entry>();
        for (int i = 1; i <= 8193; i++) {
            entries.add(entry(i, TableTypes.State.NOT_CHECKED));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TableInventoryInput(
                                descriptor(true),
                                receipt(TableTypes.PreloadOutcome.COMPLETED, entries),
                                Map.of()));
    }
}
