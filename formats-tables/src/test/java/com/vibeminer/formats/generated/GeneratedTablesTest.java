package com.vibeminer.formats.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class GeneratedTablesTest {
    private static GeneratedTableSource source(String scope) {
        return new GeneratedTableSource(
                null, "a".repeat(32), "definition", new Db2Types.Limits(), scope);
    }

    private static GeneratedTableContent content(
            AtomicInteger closed, List<String> keys, List<String> chunks) {
        var bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var table =
                Db2Reader.open(
                                Db2ByteSource.memory(bytes),
                                Db2Fixture.definition(
                                        "int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>"),
                                "1.2.3.4",
                                new Db2Types.Limits(),
                                Cancellation.NONE)
                        .join();
        return new GeneratedTableContent(
                table.header(),
                table.sections(),
                table.completion(),
                keys,
                table::readOrdinals,
                closed::incrementAndGet,
                List.of(),
                chunks);
    }

    @Test
    void durableRecordRowsReopenAcrossInstancesAndOwnersAlwaysClose() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var closed = new AtomicInteger();
        var table =
                tables.store(
                                source(null),
                                TactKeyStatus.NONE,
                                content(closed, List.of(), List.of()),
                                Cancellation.NONE)
                        .join();
        assertEquals(1, closed.get());
        assertEquals(2, table.rowCount());
        assertEquals("9", table.find(8, Cancellation.NONE).join().values().get(1));
        var reopened =
                new GeneratedTables(memory)
                        .open(source(null), TactKeyStatus.NONE, Cancellation.NONE)
                        .join();
        assertEquals(table.identity(), reopened.identity());
        assertEquals(
                table.readPage(0, 2, Cancellation.NONE).join(),
                reopened.readPage(0, 2, Cancellation.NONE).join());
        assertEquals("01020304", tables.layoutOf("a".repeat(32), Cancellation.NONE).join());
    }

    @Test
    void changedKeyInvalidatesOnlyDependentScope() {
        var tables = new GeneratedTables(new MemoryGeneratedStore());
        String name = "0000000000000001";
        var closed = new AtomicInteger();
        var table =
                tables.store(
                                source("build-a"),
                                TactKeyStatus.NONE,
                                content(closed, List.of(name), List.of(name)),
                                Cancellation.NONE)
                        .join();
        assertTrue(table.scoped());
        assertNull(tables.open(source("build-b"), TactKeyStatus.NONE, Cancellation.NONE).join());
        assertNull(
                tables.open(
                                source("build-a"),
                                new TactKeyStatus(Map.of(name, "changed")),
                                Cancellation.NONE)
                        .join());
        assertEquals(
                table.identity(),
                tables.open(source("build-a"), TactKeyStatus.NONE, Cancellation.NONE)
                        .join()
                        .identity());
    }

    @Test
    void concurrentBuildWaitersCancelIndependently() {
        var tables = new GeneratedTables(new MemoryGeneratedStore());
        var started = new CompletableFuture<Void>();
        var joined = new CompletableFuture<Void>();
        tables.waiterProbe =
                count -> {
                    if (count == 2) {
                        joined.complete(null);
                    }
                };
        var release = new CompletableFuture<GeneratedTableContent>();
        var builds = new AtomicInteger();
        java.util.function.Function<Cancellation, CompletableFuture<GeneratedTableContent>> build =
                token -> {
                    builds.incrementAndGet();
                    started.complete(null);
                    return release;
                };
        var canceled = new Cancellation();
        var first = tables.getOrBuild(source(null), TactKeyStatus.NONE, build, canceled);
        started.join();
        var second = tables.getOrBuild(source(null), TactKeyStatus.NONE, build, Cancellation.NONE);
        joined.join();
        canceled.cancel();
        assertThrows(java.util.concurrent.CompletionException.class, () -> first.join());
        release.complete(content(new AtomicInteger(), List.of(), List.of()));
        assertEquals(2, second.join().rowCount());
        assertEquals(1, builds.get());
    }

    @Test
    void rowCodecRejectsTruncationUnknownFlagsAndInvalidUtf8() {
        var row =
                new Db2Types.Row(
                        7,
                        true,
                        3L,
                        List.of("café", "18446744073709551615"),
                        List.of(new Db2Types.TextIssue(1, "issue", "ff")));
        assertEquals(row, RowCodec.decode(7, RowCodec.encode(row)));
        for (byte[] bad : new byte[][] {{8}, {0, (byte) 128}, {0, 1, 1, (byte) 255}, {0, 0, 0}}) {
            assertThrows(
                    com.vibeminer.generated.GeneratedStoreException.class,
                    () -> RowCodec.decode(1, bad));
        }
    }

    @Test
    void malformedTypedMetadataIsDiscardedAndRebuilt() {
        for (Object wrong :
                List.of(
                        2147483648L,
                        "yes",
                        "null-header",
                        "null-completion",
                        "null-tablehash",
                        "null-scope")) {
            var memory = new MemoryGeneratedStore();
            var tables = new GeneratedTables(memory);
            var table =
                    tables.store(
                                    source(null),
                                    TactKeyStatus.NONE,
                                    content(new AtomicInteger(), List.of(), List.of()),
                                    Cancellation.NONE)
                            .join();
            var node = com.vibeminer.generated.GeneratedKey.parse(table.identity());
            var manifest =
                    com.vibeminer.generated.GeneratedKey.hash(
                            java.nio.ByteBuffer.allocate(21)
                                    .put(
                                            "table"
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .US_ASCII))
                                    .put(node.bytes())
                                    .array());
            var file = com.vibeminer.generated.RecordFile.open(memory, manifest).join().file();
            var metadata =
                    com.vibeminer.formats.internal.Json.object(
                            com.vibeminer.formats.internal.Json.parse(file.metadata(), 1 << 20));
            if (wrong instanceof Long) {
                var header = com.vibeminer.formats.internal.Json.object(metadata.get("Header"));
                header.put("RecordSize", wrong);
                metadata.put("Header", header);
            } else if (wrong.equals("null-header")) {
                metadata.put("Header", null);
            } else if (wrong.equals("null-completion")) {
                metadata.put("Completion", null);
            } else if (wrong.equals("null-tablehash")) {
                var header = com.vibeminer.formats.internal.Json.object(metadata.get("Header"));
                header.put("TableHash", null);
                metadata.put("Header", header);
            } else if (wrong.equals("null-scope")) {
                var completion =
                        com.vibeminer.formats.internal.Json.object(metadata.get("Completion"));
                completion.put("Scope", null);
                metadata.put("Completion", completion);
            } else {
                var sections =
                        new java.util.ArrayList<Object>(
                                com.vibeminer.formats.internal.Json.array(
                                        metadata.get("Sections")));
                var section = com.vibeminer.formats.internal.Json.object(sections.getFirst());
                section.put("IsEncrypted", wrong);
                sections.set(0, section);
                metadata.put("Sections", sections);
            }
            memory.delete(List.of(manifest), com.vibeminer.generated.Cancellation.NONE).join();
            try (var writer = new com.vibeminer.generated.RecordFileWriter(memory)) {
                writer.add(
                                7,
                                RowCodec.encode(
                                        new Db2Types.Row(7, false, null, List.of("7", "9"), null)))
                        .join();
                writer.complete(manifest, com.vibeminer.formats.internal.Json.bytes(metadata))
                        .join();
            }
            assertNull(tables.open(source(null), TactKeyStatus.NONE, Cancellation.NONE).join());
            assertTrue(!memory.has(node).join());
            assertEquals(
                    2,
                    tables.store(
                                    source(null),
                                    TactKeyStatus.NONE,
                                    content(new AtomicInteger(), List.of(), List.of()),
                                    Cancellation.NONE)
                            .join()
                            .rowCount());
        }
    }

    @Test
    void evidenceBudgetSpansEveryGeneratedPageAndOwnerClosesOnFailure() {
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
                        256,
                        base.maxRelationshipBytes(),
                        base.maxRelationshipEntries(),
                        null,
                        null);
        var source = new GeneratedTableSource(null, "a".repeat(32), "definition", limits, null);
        var rows = new java.util.ArrayList<Db2Types.Row>();
        for (int i = 0; i < 300; i++) {
            rows.add(
                    new Db2Types.Row(
                            i,
                            false,
                            null,
                            List.of(""),
                            List.of(new Db2Types.TextIssue(0, "DB2_TEXT_ENCODING_INVALID", "ff"))));
        }
        var seed = content(new AtomicInteger(), List.of(), List.of());
        var closed = new AtomicInteger();
        var content =
                new GeneratedTableContent(
                        seed.header(),
                        seed.sections(),
                        seed.completion(),
                        List.of(),
                        (first, count, token) ->
                                CompletableFuture.completedFuture(
                                        rows.subList(
                                                (int) first,
                                                (int) Math.min(rows.size(), first + count))),
                        closed::incrementAndGet,
                        List.of(),
                        List.of());
        var failure =
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () ->
                                new GeneratedTables(new MemoryGeneratedStore())
                                        .store(
                                                source,
                                                TactKeyStatus.NONE,
                                                content,
                                                Cancellation.NONE)
                                        .join());
        assertEquals(
                "DB2_LIMIT",
                ((com.vibeminer.formats.Db2FormatException) failure.getCause()).code());
        assertEquals(1, closed.get());
    }

    @Test
    void storedRowsSpanSeveralWriterPagesInSourceOrder() {
        var rows = new java.util.ArrayList<Db2Types.Row>();
        for (int i = 0; i < 600; i++) {
            // Descending ids so pages must keep source order, not id order.
            rows.add(
                    new Db2Types.Row(
                            10_000 - i * 3, false, null, List.of(Integer.toString(i)), null));
        }
        var seed = content(new AtomicInteger(), List.of(), List.of());
        var content =
                new GeneratedTableContent(
                        seed.header(),
                        seed.sections(),
                        seed.completion(),
                        List.of(),
                        (first, count, token) ->
                                CompletableFuture.completedFuture(
                                        rows.subList(
                                                (int) first,
                                                (int) Math.min(rows.size(), first + count))),
                        () -> {},
                        List.of(),
                        List.of());
        var table =
                new GeneratedTables(new MemoryGeneratedStore())
                        .store(source(null), TactKeyStatus.NONE, content, Cancellation.NONE)
                        .join();
        assertEquals(600, table.rowCount());
        assertEquals(rows, table.readPage(0, 600, Cancellation.NONE).join());
        assertEquals(rows.get(257), table.find(10_000 - 257 * 3, Cancellation.NONE).join());
    }

    @Test
    void oneLookupReadsEachMutableKeyFingerprintOnlyOnce() {
        var calls = new AtomicInteger();
        KeyStatus status = name -> calls.incrementAndGet() == 1 ? null : "changed";
        var tables = new GeneratedTables(new MemoryGeneratedStore());
        String name = "0000000000000001";
        assertEquals(
                tables.identityOf(source(null), TactKeyStatus.NONE, List.of(name), false),
                tables.identityOf(source(null), status, List.of(name), false));
        assertEquals(1, calls.get());
    }

    @Test
    void olderOptionalDescriptorFieldsUsePinnedCsharpDefaults() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var table =
                tables.store(
                                source(null),
                                TactKeyStatus.NONE,
                                content(new AtomicInteger(), List.of(), List.of()),
                                Cancellation.NONE)
                        .join();
        var node = com.vibeminer.generated.GeneratedKey.parse(table.identity());
        var manifest =
                com.vibeminer.generated.GeneratedKey.hash(
                        java.nio.ByteBuffer.allocate(21)
                                .put("table".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                                .put(node.bytes())
                                .array());
        var file = com.vibeminer.generated.RecordFile.open(memory, manifest).join().file();
        var metadata =
                com.vibeminer.formats.internal.Json.object(
                        com.vibeminer.formats.internal.Json.parse(file.metadata(), 1 << 20));
        var completion = com.vibeminer.formats.internal.Json.object(metadata.get("Completion"));
        completion.remove("TextIssueCount");
        metadata.put("Completion", completion);
        var sections =
                new java.util.ArrayList<Object>(
                        com.vibeminer.formats.internal.Json.array(metadata.get("Sections")));
        var section = com.vibeminer.formats.internal.Json.object(sections.getFirst());
        for (String key : List.of("ProtectedCopyCount", "UnresolvedCopyCount", "IsDecrypted")) {
            section.remove(key);
        }
        sections.set(0, section);
        metadata.put("Sections", sections);
        var rows = table.readPage(0, 10, Cancellation.NONE).join();
        memory.delete(List.of(manifest), com.vibeminer.generated.Cancellation.NONE).join();
        try (var writer = new com.vibeminer.generated.RecordFileWriter(memory)) {
            for (var row : rows) {
                writer.add(row.id(), RowCodec.encode(row)).join();
            }
            writer.complete(manifest, com.vibeminer.formats.internal.Json.bytes(metadata)).join();
        }
        assertEquals(
                2,
                tables.open(source(null), TactKeyStatus.NONE, Cancellation.NONE).join().rowCount());
    }

    @Test
    void savedGraphInventorySharesAcrossBuildsWithoutReadingSegmentsOrChangingObjects() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        tables.store(
                        source("build-a"),
                        TactKeyStatus.NONE,
                        content(new AtomicInteger(), List.of(), List.of()),
                        Cancellation.NONE)
                .join();
        int ranges = memory.rangeReads(), puts = memory.puts();
        var first =
                tables.savedArtifacts(source("build-a"), TactKeyStatus.NONE, Cancellation.NONE)
                        .join();
        var second =
                tables.savedArtifacts(source("build-b"), TactKeyStatus.NONE, Cancellation.NONE)
                        .join();
        assertEquals(new java.util.HashSet<>(memory.keys()), new java.util.HashSet<>(first));
        assertEquals(first, second);
        assertEquals(
                ranges, memory.rangeReads(), "Accounting must not verify or decode row segments");
        assertEquals(puts, memory.puts());
    }

    @Test
    void unreadableInventoryDoesNotDeleteOrRepairProducerData() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var table =
                tables.store(
                                source(null),
                                TactKeyStatus.NONE,
                                content(new AtomicInteger(), List.of(), List.of()),
                                Cancellation.NONE)
                        .join();
        var before = memory.keys();
        memory.damage(com.vibeminer.generated.GeneratedKey.parse(table.identity()));
        assertThrows(
                RuntimeException.class,
                () ->
                        tables.savedArtifacts(source(null), TactKeyStatus.NONE, Cancellation.NONE)
                                .join());
        assertEquals(new java.util.HashSet<>(before), new java.util.HashSet<>(memory.keys()));
    }
}
