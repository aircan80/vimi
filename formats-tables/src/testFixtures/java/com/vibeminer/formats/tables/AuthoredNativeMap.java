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
                Db2Fixture.definition(
                        "int ID\nint WdtFileDataID", "$id,noninline$ID<u32>\nWdtFileDataID<u32>");
        var decoded =
                Db2Reader.open(
                                Db2ByteSource.memory(
                                        Db2Fixture.numeric("WDC5", 0, 32, 1, new long[0], false)),
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

    public BuildTables tables() {
        return tables;
    }

    @Override
    public void close() {
        tables.close();
    }
}
