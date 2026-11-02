package com.vibeminer.formats.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.generated.DiskGeneratedStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
final class GeneratedTablesInteropTest {
    @TempDir Path folder;

    private String csharp(String command, Path data) throws Exception {
        var process =
                new ProcessBuilder(
                                System.getProperty("vimi.dotnet"),
                                System.getProperty("vimi.tables.harness"),
                                command,
                                data.toString())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }

    private static GeneratedTableSource source() {
        return new GeneratedTableSource(
                null, "a".repeat(32), "definition", new Db2Types.Limits(), null);
    }

    @Test
    void originalCsharpIdentityOptionsResolvedFieldsAndRowBytesMatch() throws Exception {
        String output = csharp("goldens", folder.resolve("golden"));
        var lines = output.lines().toList();
        var source = source();
        assertEquals("default-options " + source.readerOptions(), lines.get(0));
        var l = new Db2Types.Limits();
        var empty =
                new Db2Types.Limits(
                        l.maxDecodedBytes(),
                        l.maxRows(),
                        l.maxFields(),
                        l.maxSections(),
                        l.maxRecordBytes(),
                        l.maxAuxiliaryBytes(),
                        l.maxStringBytes(),
                        l.maxIndexBytes(),
                        l.maxColumns(),
                        l.maxCopyDepth(),
                        l.allowPartialEncryptedSections(),
                        l.allowRawTextEvidence(),
                        l.maxRawTextEvidenceBytes(),
                        l.maxRelationshipBytes(),
                        l.maxRelationshipEntries(),
                        null,
                        List.of());
        assertEquals(
                "empty-options "
                        + new GeneratedTableSource(
                                        null, source.contentKey(), source.definition(), empty, null)
                                .readerOptions(),
                lines.get(1));
        var store = DiskGeneratedStore.open(folder.resolve("identity")).join();
        try {
            assertEquals(
                    "identity "
                            + new GeneratedTables(store)
                                    .identityOf(source, TactKeyStatus.NONE, List.of(), false),
                    lines.get(2));
        } finally {
            store.closeAsync().join();
        }
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        assertEquals(
                "resolved " + ResolvedDefinition.hash(definition.blocks().getFirst()),
                lines.get(3));
        var rows =
                List.of(
                        new Db2Types.Row(7, false, null, List.of("7", "9"), null),
                        new Db2Types.Row(7, false, null, List.of("7", "9"), List.of()),
                        new Db2Types.Row(
                                8,
                                true,
                                7L,
                                List.of("café", "18446744073709551615"),
                                List.of(new Db2Types.TextIssue(1, "issue", "ff"))));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(
                    "row " + HexFormat.of().formatHex(RowCodec.encode(rows.get(i))),
                    lines.get(4 + i));
        }
        assertEquals(
                "column " + Json.canonical(new Db2Types.Column("ID", "integer", true, null, null)),
                lines.get(7));
    }

    @Test
    void csharpTablesOpenAndResolveInJava() throws Exception {
        Path data = folder.resolve("csharp");
        String identity = csharp("write", data);
        var store = DiskGeneratedStore.open(data).join();
        try {
            var table =
                    new GeneratedTables(store)
                            .open(source(), TactKeyStatus.NONE, Cancellation.NONE)
                            .join();
            assertNotNull(table);
            assertEquals(identity, table.identity());
            assertEquals(List.of("8", "9"), table.find(8, Cancellation.NONE).join().values());
            assertEquals(2, table.readPage(0, 10, Cancellation.NONE).join().size());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void javaTablesOpenAndResolveInOriginalCsharp() throws Exception {
        Path data = folder.resolve("java");
        var store = DiskGeneratedStore.open(data).join();
        String identity;
        try {
            var header =
                    new Db2Types.Header(
                            5,
                            "11223344",
                            "01020304",
                            1,
                            1,
                            8,
                            List.of(
                                    new Db2Types.Column("ID", "integer", true, null, null),
                                    new Db2Types.Column("Value", "integer", false, null, null)),
                            "WDC5");
            var rows =
                    List.of(
                            new Db2Types.Row(7, false, null, List.of("7", "9"), null),
                            new Db2Types.Row(8, true, 7L, List.of("8", "9"), null));
            var content =
                    new GeneratedTableContent(
                            header,
                            List.of(
                                    new Db2Types.Section(
                                            0, 1, 1, "0000000000000000", false, 0, 0, false)),
                            new Db2Types.Completion(1, 1, 1, 0, 0, 0, "complete", 288, 0),
                            List.of(),
                            (first, count, token) ->
                                    CompletableFuture.completedFuture(
                                            new ArrayList<>(
                                                    rows.subList(
                                                            (int) first,
                                                            (int)
                                                                    Math.min(
                                                                            rows.size(),
                                                                            first + count)))),
                            null,
                            List.of(),
                            List.of());
            identity =
                    new GeneratedTables(store)
                            .store(source(), TactKeyStatus.NONE, content, Cancellation.NONE)
                            .join()
                            .identity();
        } finally {
            store.closeAsync().join();
        }
        assertEquals(identity, csharp("read", data));
        assertTrue(identity.matches("[0-9a-f]{32}"));
    }
}
