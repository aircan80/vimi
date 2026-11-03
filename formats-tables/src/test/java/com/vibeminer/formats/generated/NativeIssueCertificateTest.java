package com.vibeminer.formats.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Table;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.generated.RecordFile;
import com.vibeminer.generated.RecordFileOptions;
import com.vibeminer.generated.RecordFileWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class NativeIssueCertificateTest {
    private static final Cancellation TOKEN = Cancellation.NONE;
    private static final com.vibeminer.generated.Cancellation LOWER =
            com.vibeminer.generated.Cancellation.NONE;
    private static final String KEY = "0000000000000001";

    private record Fixture(Db2Table table, GeneratedTableSource source) {
        GeneratedTableContent content(AtomicInteger closed, boolean scoped) {
            return GeneratedTableContent.nativeDb2(
                    source,
                    table,
                    scoped ? List.of(KEY) : List.of(),
                    closed::incrementAndGet,
                    List.of(),
                    scoped ? List.of(KEY) : List.of());
        }
    }

    private static Fixture fixture(String scope) {
        var document =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var limits = new Db2Types.Limits();
        var table =
                Db2Reader.open(
                                Db2ByteSource.memory(
                                        Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true)),
                                document,
                                "1.2.3.4",
                                limits,
                                TOKEN)
                        .join();
        var block =
                document.selectFile("1.2.3.4", table.header().layoutHash(), TOKEN)
                        .blocks()
                        .getFirst();
        return new Fixture(
                table,
                GeneratedTableSource.forNativeBlock(block, "enUS", "1".repeat(32), limits, scope));
    }

    @Test
    void nativeIdentityAndNodeMatchIndependentFixedTupleFixture() throws Exception {
        var source = fixture("build-a").source();
        try (var input = getClass().getResourceAsStream("/native-issue-identity-v1.hex")) {
            assertEquals(
                    new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip(),
                    java.util.HexFormat.of().formatHex(source.identityBytes(false)));
        }
        assertEquals(
                "c7e265a39142e8b8fb1aca612e85e5d71a6fd64ce160c48b99aa27bb926ae4b3",
                source.baseHash(false));
        assertEquals(
                "17e73ef9af56a7949997acf5435fcd89",
                new GeneratedTables(new MemoryGeneratedStore())
                        .identityOf(source, TactKeyStatus.NONE, List.of(), false));
        assertEquals(
                "d125c0b80420935cb32c9c12f37e880eb7e55a5e3b76e1548397c8057b5b7248",
                source.readerOptions());
    }

    @Test
    void nativeRowsRejectBrokenShapesUnsignedIdsAndIssueLocations() {
        var f = fixture(null);
        var content = f.content(new AtomicInteger(), false);
        for (var row :
                List.of(
                        new Db2Types.Row(7, false, null, List.of("7"), null),
                        new Db2Types.Row(-1, false, null, List.of("7", "9"), null),
                        new Db2Types.Row(0x100000000L, false, null, List.of("7", "9"), null),
                        new Db2Types.Row(7, true, null, List.of("7", "9"), null),
                        new Db2Types.Row(7, true, -1L, List.of("7", "9"), null),
                        new Db2Types.Row(
                                7,
                                false,
                                null,
                                List.of("7", "9"),
                                List.of(new Db2Types.TextIssue(2, "unknown", ""))))) {
            assertThrows(
                    com.vibeminer.formats.Db2FormatException.class, () -> content.validateRow(row));
        }
    }

    @Test
    void unscopedCertificateBindsActualGraphAndIsSharedAcrossRequestedBuildScopes() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var first = fixture("build-a");
        var closed = new AtomicInteger();
        var saved =
                tables.store(
                                first.source(),
                                TactKeyStatus.NONE,
                                first.content(closed, false),
                                TOKEN)
                        .join();
        var second = fixture("build-b");
        var reopened =
                new GeneratedTables(memory)
                        .openPreparedLocal(second.source(), TactKeyStatus.NONE, TOKEN)
                        .join();
        assertEquals(1, closed.get());
        assertEquals(saved.identity(), reopened.identity());
        assertFalse(reopened.scoped());
        var certificate = reopened.issueCertificate();
        assertEquals(second.source().baseHash(false), certificate.sourceIdentitySha256());
        assertNotEquals(second.source().baseHash(true), certificate.sourceIdentitySha256());
        assertEquals(2, certificate.emittedRowCount());
        assertNull(reopened.readPage(0, 1, TOKEN).join().getFirst().textIssues());
        int ranges = memory.rangeReads();
        tables.savedArtifacts(second.source(), TactKeyStatus.NONE, TOKEN).join();
        assertEquals(ranges, memory.rangeReads(), "Inventory never reads row segments");
        assertNull(
                tables.openPreparedLocal(first.source().legacy(), TactKeyStatus.NONE, TOKEN)
                        .join());
    }

    @Test
    void scopedCertificateBindsScopeAndEffectiveKeyChain() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var fixture = fixture("build-a");
        var stored =
                tables.store(
                                fixture.source(),
                                name -> "key-a",
                                fixture.content(new AtomicInteger(), true),
                                TOKEN)
                        .join();
        assertTrue(stored.scoped());
        assertEquals(
                fixture.source().baseHash(true), stored.issueCertificate().sourceIdentitySha256());
        assertEquals(stored.identity(), stored.issueCertificate().graphIdentity());
        assertNull(
                tables.openPreparedLocal(fixture("build-b").source(), name -> "key-a", TOKEN)
                        .join());
        assertNull(tables.openPreparedLocal(fixture.source(), name -> "key-b", TOKEN).join());
    }

    @Test
    void genericContentCannotPublishOrReturnAnExistingCertifiedHitAndAlwaysCloses() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var fixture = fixture(null);
        var closed = new AtomicInteger();
        tables.store(fixture.source(), TactKeyStatus.NONE, fixture.content(closed, false), TOKEN)
                .join();
        int puts = memory.puts();
        var keys = memory.keys();
        var generic =
                new GeneratedTableContent(
                        fixture.table().header(),
                        fixture.table().sections(),
                        fixture.table().completion(),
                        List.of(),
                        fixture.table()::readOrdinals,
                        closed::incrementAndGet,
                        List.of(),
                        List.of());
        assertThrows(
                CompletionException.class,
                () -> tables.store(fixture.source(), TactKeyStatus.NONE, generic, TOKEN).join());
        assertEquals(2, closed.get());
        assertEquals(puts, memory.puts());
        assertEquals(keys, memory.keys());
        var legacy =
                tables.store(fixture.source().legacy(), TactKeyStatus.NONE, generic, TOKEN).join();
        assertNull(legacy.issueCertificate());
        assertNull(legacy.readPage(0, 1, TOKEN).join().getFirst().textIssues());
    }

    @Test
    void changedDefinitionOrOptionsCannotSealNativeContent() {
        var fixture = fixture(null);
        var changed =
                new GeneratedTableSource(
                        null,
                        "1".repeat(32),
                        "2".repeat(64),
                        new Db2Types.Limits(),
                        null,
                        GeneratedTableSource.IssuePolicy.NATIVE_V1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GeneratedTableContent.nativeDb2(
                                changed,
                                fixture.table(),
                                List.of(),
                                () -> {},
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GeneratedTableContent.nativeDb2(
                                fixture.source().legacy(),
                                fixture.table(),
                                List.of(),
                                () -> {},
                                List.of(),
                                List.of()));
    }

    @Test
    void crossedCertificateFieldsAndCountsFailBothReadAndInventoryWithoutRepair() {
        for (String field :
                List.of(
                        "policyId",
                        "producerId",
                        "sourceIdentitySha256",
                        "graphIdentity",
                        "resolvedBlockSha256",
                        "readerOptionsSha256",
                        "actualColumnsSha256",
                        "flattenedColumnCount",
                        "emittedRowCount",
                        "format")) {
            var memory = new MemoryGeneratedStore();
            var tables = new GeneratedTables(memory);
            var fixture = fixture("build-a");
            var saved =
                    tables.store(
                                    fixture.source(),
                                    TactKeyStatus.NONE,
                                    fixture.content(new AtomicInteger(), false),
                                    TOKEN)
                            .join();
            var node = GeneratedKey.parse(saved.identity());
            var manifest =
                    GeneratedKey.hash(
                            ByteBuffer.allocate(21)
                                    .put("table".getBytes(StandardCharsets.US_ASCII))
                                    .put(node.bytes())
                                    .array());
            var file = RecordFile.open(memory, manifest, LOWER).join().file();
            var metadata =
                    new java.util.LinkedHashMap<>(Json.object(Json.parse(file.metadata(), 16384)));
            var certificate =
                    new java.util.LinkedHashMap<>(Json.object(metadata.get("IssueCertificate")));
            certificate.put(
                    Character.toUpperCase(field.charAt(0)) + field.substring(1),
                    field.equals("emittedRowCount") || field.equals("flattenedColumnCount")
                            ? 999
                            : "crossed");
            metadata.put("IssueCertificate", certificate);
            var rows = file.read(0, 2, LOWER).join();
            memory.delete(List.of(manifest), LOWER).join();
            try (var writer = new RecordFileWriter(memory, new RecordFileOptions())) {
                for (var row : rows) {
                    writer.add(row.id(), row.bytes(), LOWER).join();
                }
                writer.complete(manifest, Json.bytes(metadata), LOWER).join();
            }
            int puts = memory.puts();
            var keys = memory.keys();
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    tables.openPreparedLocal(
                                                    fixture.source(), TactKeyStatus.NONE, TOKEN)
                                            .join(),
                            field);
            assertInstanceOf(
                    com.vibeminer.generated.GeneratedStoreException.class, failure.getCause());
            assertThrows(
                    CompletionException.class,
                    () -> tables.savedArtifacts(fixture.source(), TactKeyStatus.NONE, TOKEN).join(),
                    field);
            assertEquals(puts, memory.puts());
            assertEquals(keys, memory.keys());
        }
    }

    @Test
    void nativeDecodeFailurePublishesNoCertifiedNodeAndClosesOwner() {
        var document =
                Db2Fixture.definition(
                        "int ID\nlocstring Name\nint Value",
                        "$id,noninline$ID<u32>\nName\nValue<u32>");
        var bytes = Db2Fixture.sparse();
        bytes[300] = (byte) 0xff;
        var limits = new Db2Types.Limits();
        var table =
                Db2Reader.open(Db2ByteSource.memory(bytes), document, "1.2.3.4", limits, TOKEN)
                        .join();
        var block =
                document.selectFile("1.2.3.4", table.header().layoutHash(), TOKEN)
                        .blocks()
                        .getFirst();
        var source =
                GeneratedTableSource.forNativeBlock(block, "enUS", "1".repeat(32), limits, null);
        var closed = new AtomicInteger();
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var content =
                GeneratedTableContent.nativeDb2(
                        source, table, List.of(), closed::incrementAndGet, List.of(), List.of());
        assertThrows(
                CompletionException.class,
                () -> tables.store(source, TactKeyStatus.NONE, content, TOKEN).join());
        assertEquals(1, closed.get());
        assertNull(tables.openPreparedLocal(source, TactKeyStatus.NONE, TOKEN).join());
        assertTrue(memory.keys().isEmpty());
    }

    @Test
    void canceledNativeStoreClosesOwnerAndPublishesNoNode() {
        var memory = new MemoryGeneratedStore();
        var tables = new GeneratedTables(memory);
        var fixture = fixture(null);
        var token = new Cancellation();
        token.cancel();
        var closed = new AtomicInteger();
        assertThrows(
                RuntimeException.class,
                () ->
                        tables.store(
                                        fixture.source(),
                                        TactKeyStatus.NONE,
                                        fixture.content(closed, false),
                                        token)
                                .join());
        assertEquals(1, closed.get());
        assertTrue(memory.keys().isEmpty());
    }
}
