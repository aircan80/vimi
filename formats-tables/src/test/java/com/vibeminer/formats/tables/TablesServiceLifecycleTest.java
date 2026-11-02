package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.MemoryObjectSource;
import com.vibeminer.access.ObservationAdapters;
import com.vibeminer.access.ObservationSource;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.Files;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.files.names.FileNames;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.FetchedDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class TablesServiceLifecycleTest {
    private static MemoryFiles files() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var result = new MemoryFiles();
        for (long id : new long[] {100, 101}) {
            result.add(
                    TablesContract.BUILD,
                    "enUS",
                    new FileTypes.BuildFile(
                            id,
                            "dbfilesclient/" + id + ".db2",
                            FileTypes.FilePathSource.LISTFILE,
                            (long) bytes.length,
                            "1".repeat(32),
                            FileTypes.FileFormat.DB2,
                            FileTypes.FileFormatSource.EXTENSION,
                            FileTypes.FileStorage.STORED,
                            false),
                    bytes,
                    List.of());
        }
        return result;
    }

    private static BundledDefinitions provider() {
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        return BundledDefinitions.of(
                List.of(
                        new BundledDefinitions.DefinitionTable("11223344", "Numbers", "100", true),
                        new BundledDefinitions.DefinitionTable("55667788", "Other", "101", true)),
                Map.of(
                        "11223344",
                        new BundledDefinitions.Definition("a".repeat(64), "fixture", definition),
                        "55667788",
                        new BundledDefinitions.Definition("a".repeat(64), "fixture", definition)));
    }

    @Test
    void throwingAndNullBuildVersionProvidersCloseOpenedFiles() {
        for (boolean nullFuture : new boolean[] {false, true}) {
            var memory = files();
            var closed = new AtomicInteger();
            Files tracked =
                    new Files() {
                        @Override
                        public FileNames names() {
                            return FileNames.OFF;
                        }

                        @Override
                        public CompletableFuture<FileTypes.FilesOpenResult> forBuild(
                                com.vibeminer.builds.BuildTypes.BuildRef build,
                                String locale,
                                Cancellation token) {
                            return memory.forBuild(build, locale, token)
                                    .thenApply(
                                            open -> {
                                                BuildFiles proxy =
                                                        (BuildFiles)
                                                                Proxy.newProxyInstance(
                                                                        BuildFiles.class
                                                                                .getClassLoader(),
                                                                        new Class<?>[] {
                                                                            BuildFiles.class
                                                                        },
                                                                        (object, method, args) -> {
                                                                            if (method.getName()
                                                                                    .equals(
                                                                                            "close")) {
                                                                                closed
                                                                                        .incrementAndGet();
                                                                            }
                                                                            try {
                                                                                return method
                                                                                        .invoke(
                                                                                                open
                                                                                                        .files(),
                                                                                                args);
                                                                            } catch (
                                                                                    java.lang
                                                                                                    .reflect
                                                                                                    .InvocationTargetException
                                                                                            error) {
                                                                                throw error
                                                                                        .getCause();
                                                                            }
                                                                        });
                                                return new FileTypes.FilesOpenResult(
                                                        build,
                                                        locale,
                                                        open.state(),
                                                        proxy,
                                                        null,
                                                        null,
                                                        null);
                                            });
                        }
                    };
            var options =
                    new TablesOptions(
                            (build, token) -> {
                                if (nullFuture) {
                                    return null;
                                }
                                throw new IllegalStateException("version failed");
                            });
            var service =
                    new TablesService(
                            tracked,
                            new TableDefinitions(provider(), null, null),
                            new GeneratedTables(new MemoryGeneratedStore()),
                            options);
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join());
            assertEquals(1, closed.get());
        }
    }

    @Test
    void oneHandleSerializesAndSharesOneDefinitionFetchBudget() {
        var calls = new AtomicInteger();
        var started = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        byte[] bytes =
                "COLUMNS\nint ID\nint Value\n\nLAYOUT 01020304\nBUILD 1.2.3.4\n$id,noninline$ID<u32>\nValue<u32>"
                        .getBytes(StandardCharsets.UTF_8);
        var source =
                new MemoryObjectSource(
                        List.of(
                                new ObservationSource() {
                                    @Override
                                    public Set<SourceTypes.ObservationKind> kinds() {
                                        return Set.of(SourceTypes.ObservationKind.DEFINITION);
                                    }

                                    @Override
                                    public CompletableFuture<SourceTypes.Observation> fetch(
                                            SourceTypes.ObservationRequest request,
                                            Cancellation token) {
                                        calls.incrementAndGet();
                                        started.complete(null);
                                        return release.thenApply(
                                                ignored ->
                                                        new SourceTypes.Observation(
                                                                request.kind(),
                                                                request.product(),
                                                                request.region(),
                                                                URI.create(
                                                                        ObservationAdapters
                                                                                .definitionUrl(
                                                                                        "c"
                                                                                                .repeat(
                                                                                                        40),
                                                                                        request
                                                                                                .name())),
                                                                Instant.EPOCH,
                                                                bytes));
                                    }
                                }));
        var definitions =
                new TableDefinitions(
                        provider(),
                        new FetchedDefinitions(source),
                        token -> CompletableFuture.completedFuture(true));
        var service =
                new TablesService(
                        files(),
                        definitions,
                        new GeneratedTables(new MemoryGeneratedStore()),
                        new TablesOptions(
                                (build, token) -> CompletableFuture.completedFuture("1.2.3.5")));
        var handle =
                service.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables();
        try {
            var first =
                    handle.check(
                            TableTypes.Ref.id(100),
                            TableTypes.Priority.INTERACTIVE,
                            Cancellation.NONE);
            started.join();
            var second =
                    handle.check(
                            TableTypes.Ref.id(101),
                            TableTypes.Priority.INTERACTIVE,
                            Cancellation.NONE);
            release.complete(null);
            assertEquals(TableTypes.State.READY, first.join().state());
            assertEquals(TableTypes.State.READY, second.join().state());
            handle.check(TableTypes.Ref.id(101), TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                    .join();
            assertEquals(1, calls.get());
        } finally {
            handle.close();
        }
    }

    @Test
    void directRowRefusalFallsBackToTypedGeneratedOutcomeInsteadOfThrowing() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 256, new long[0], true);
        var files =
                new MemoryFiles()
                        .add(
                                TablesContract.BUILD,
                                "enUS",
                                new FileTypes.BuildFile(
                                        100,
                                        "dbfilesclient/numbers.db2",
                                        FileTypes.FilePathSource.LISTFILE,
                                        (long) bytes.length,
                                        "1".repeat(32),
                                        FileTypes.FileFormat.DB2,
                                        FileTypes.FileFormatSource.EXTENSION,
                                        FileTypes.FileStorage.STORED,
                                        false),
                                bytes,
                                List.of());
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u8>");
        var provider =
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        "11223344", "Numbers", "100", true)),
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", definition)));
        var service =
                new TablesService(
                        files,
                        new TableDefinitions(provider, null, null),
                        new GeneratedTables(new MemoryGeneratedStore()),
                        new TablesOptions(
                                (build, token) -> CompletableFuture.completedFuture("1.2.3.4")));
        try (var handle =
                service.forBuild(TablesContract.BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var page =
                    handle.page(
                                    new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PageState.NOT_READABLE, page.state());
            assertEquals(TableTypes.State.DEFINITION_UNUSABLE, page.table().state());
            assertEquals("DB2_SCHEMA_MISMATCH", page.table().reason().code());
            var row =
                    handle.get(
                                    TableTypes.Ref.id(100),
                                    7,
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.RowOutcome.UNKNOWN, row.outcome());
            assertEquals(TableTypes.State.DEFINITION_UNUSABLE, row.table().state());
        }
    }
}
