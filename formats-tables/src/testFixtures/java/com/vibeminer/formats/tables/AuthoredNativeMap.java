package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.AsyncCursor;
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
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Authored Map7→WDT1, through the actual native reader, certificate writer and capture factory. */
public final class AuthoredNativeMap implements AutoCloseable {
    private final BuildTables tables;

    public AuthoredNativeMap(BuildFiles borrowed) {
        this(borrowed, "e".repeat(32));
    }

    public AuthoredNativeMap(BuildFiles borrowed, String contentKey) {
        this(borrowed, contentKey, 1);
    }

    public AuthoredNativeMap(BuildFiles borrowed, String contentKey, int rowCount) {
        this(
                borrowed,
                contentKey,
                Db2Fixture.definition(
                        "int ID\nint WdtFileDataID", "$id,noninline$ID<u32>\nWdtFileDataID<u32>"),
                numericRows(rowCount));
    }

    /**
     * Authored Map7 without any WDT field: one row whose only field is the string {@code
     * directory}, under {@code layout} (8 hex digits), as classic Map layouts store it.
     */
    public static AuthoredNativeMap directory(
            BuildFiles borrowed, String contentKey, String directory, String layout) {
        var document =
                com.vibeminer.formats.definitions.DbdDefinitionReader.parse(
                        ("COLUMNS\nint ID\nstring Directory\n\nLAYOUT "
                                        + layout
                                        + "\nBUILD 1.2.3.4\n$id,noninline$ID<u32>\nDirectory\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new AuthoredNativeMap(
                borrowed, contentKey, document, directoryRow(directory, layout));
    }

    private AuthoredNativeMap(
            BuildFiles borrowed,
            String contentKey,
            com.vibeminer.formats.definitions.DbdDocument document,
            byte[] rows) {
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
        var decoded =
                Db2Reader.open(
                                Db2ByteSource.memory(rows),
                                document,
                                "1.2.3.4",
                                limits,
                                Cancellation.NONE)
                        .join();
        var block =
                document.selectFile("1.2.3.4", decoded.header().layoutHash(), Cancellation.NONE)
                        .blocks()
                        .getFirst();
        var build = borrowed.build();
        var source =
                GeneratedTableSource.forNativeBlock(
                        block,
                        borrowed.locale(),
                        contentKey,
                        limits,
                        build.product() + " " + build.buildConfig() + " " + build.cdnConfig());
        var generated = new GeneratedTables(new MemoryGeneratedStore());
        generated
                .store(
                        source,
                        TactKeyStatus.NONE,
                        GeneratedTableContent.nativeDb2(
                                source, decoded, List.of(), () -> {}, List.of(), List.of()),
                        Cancellation.NONE)
                .join();
        var definitions =
                new TableDefinitions(
                        BundledDefinitions.of(
                                List.of(
                                        new BundledDefinitions.DefinitionTable(
                                                "11223344", "Map", "100", true)),
                                Map.of(
                                        "11223344",
                                        new BundledDefinitions.Definition(
                                                "a".repeat(64), "authored", document))),
                        null,
                        null);
        var options =
                new TablesOptions(
                        (ignored, token) -> CompletableFuture.completedFuture("1.2.3.4"),
                        base,
                        ignored -> TactKeyStatus.NONE,
                        new TableWorkScheduler(),
                        10_000,
                        256,
                        1_000_000,
                        true);
        var files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" -> borrowed.build();
                                            case "locale" -> borrowed.locale();
                                            case "listContentInventory" ->
                                                    new AsyncCursor<FileTypes.ContentFile>() {
                                                        boolean ended;

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
                                                                                            100,
                                                                                            null,
                                                                                            contentKey)));
                                                        }

                                                        public void close() {
                                                            ended = true;
                                                        }
                                                    };
                                            case "close" -> null;
                                            default ->
                                                    throw new AssertionError(
                                                            "Prepared fixture may not acquire/decode Map payload: "
                                                                    + method.getName());
                                        });
        tables =
                new ClientBuildTables(
                        null,
                        files,
                        "1.2.3.4",
                        definitions,
                        generated,
                        options,
                        new BackgroundBuilds(),
                        new ProtectedIds());
    }

    /**
     * One 8-byte record whose 32-bit field is a relative pointer (8) to the string table right
     * after it, then the terminated directory and the row ID 7.
     */
    private static byte[] directoryRow(String directory, String layout) {
        byte[] template = Db2Fixture.numeric("WDC5", 0, 32, 8, new long[0], false);
        byte[] text = (directory + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int records = 280;
        byte[] bytes = new byte[template.length + text.length];
        System.arraycopy(template, 0, bytes, 0, records);
        System.arraycopy(text, 0, bytes, records, text.length);
        System.arraycopy(
                template, records, bytes, records + text.length, template.length - records);
        java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(148, text.length)
                .putInt(156, Integer.parseUnsignedInt(layout, 16))
                .putInt(220, text.length);
        return bytes;
    }

    private static byte[] numericRows(int rows) {
        if (rows < 1 || rows > 10_000) {
            throw new IllegalArgumentException("Authored Map row bound");
        }
        byte[] template = Db2Fixture.numeric("WDC5", 0, 32, 1, new long[0], false);
        var original = java.nio.ByteBuffer.wrap(template).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int start = original.getInt(212);
        var out =
                java.nio.ByteBuffer.allocate(start + rows * 12)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(template, 0, start);
        out.putInt(136, rows).putInt(216, rows).putInt(228, rows * 4);
        for (int i = 0; i < rows; i++) {
            out.putLong(1);
        }
        for (int i = 0; i < rows; i++) {
            out.putInt(7 + i);
        }
        return out.array();
    }

    public BuildTables tables() {
        return tables;
    }

    @Override
    public void close() {
        tables.close();
    }
}
