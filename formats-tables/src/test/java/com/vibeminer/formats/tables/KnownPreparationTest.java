package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class KnownPreparationTest {
    private static final com.vibeminer.builds.BuildTypes.BuildRef BUILD = TablesContract.BUILD;

    private static FileTypes.BuildFile file(long id, byte[] bytes) {
        return new FileTypes.BuildFile(
                id,
                "dbfilesclient/t" + id + ".db2",
                FileTypes.FilePathSource.LISTFILE,
                (long) bytes.length,
                String.format("%032x", id),
                FileTypes.FileFormat.DB2,
                FileTypes.FileFormatSource.EXTENSION,
                FileTypes.FileStorage.STORED,
                false);
    }

    private static Tables service(MemoryFiles files, boolean includeUnknownDefinition) {
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var inventory = new ArrayList<BundledDefinitions.DefinitionTable>();
        for (long id : List.of(100L, 200L, 300L, 400L)) {
            inventory.add(
                    new BundledDefinitions.DefinitionTable(
                            "11223344", "T" + id, Long.toString(id), true));
        }
        if (includeUnknownDefinition) {
            inventory.add(
                    new BundledDefinitions.DefinitionTable("55667788", "Unknown", "500", true));
        }
        var provider =
                BundledDefinitions.of(
                        inventory,
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", definition)));
        return new TablesService(
                files,
                new TableDefinitions(provider, null, null),
                new GeneratedTables(new MemoryGeneratedStore()),
                new TablesOptions((build, token) -> CompletableFuture.completedFuture("1.2.3.4"))
                        .withLocalDefinitionsOnly());
    }

    @Test
    void realPassAccountsForReadyProtectedCorruptAbsentAndMissingDefinitions() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var files =
                new MemoryFiles()
                        .add(BUILD, "enUS", file(100, bytes), bytes, List.of())
                        .add(
                                BUILD,
                                "enUS",
                                file(200, bytes),
                                bytes,
                                List.of(
                                        new FileTypes.FileHole(
                                                0, bytes.length, "0123456789ABCDEF")))
                        .add(BUILD, "enUS", file(300, new byte[8]), new byte[8], List.of())
                        .add(BUILD, "enUS", file(500, bytes), bytes, List.of())
                        .add(BUILD, "enUS", file(999, bytes), bytes, List.of());
        var source = service(files, true);
        try (var handle = source.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var result = handle.prepareKnown(null, Cancellation.NONE).join();
            assertEquals(TableTypes.PreloadOutcome.COMPLETED, result.outcome());
            assertEquals(5, result.tables().size());
            var states =
                    result.tables().stream()
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            TableTypes.Entry::fileDataId, TableTypes.Entry::state));
            assertEquals(TableTypes.State.READY, states.get(100L));
            assertEquals(TableTypes.State.ENCRYPTED, states.get(200L));
            assertFalse(TableQueries.isReadable(states.get(300L)));
            assertEquals(TableTypes.State.NOT_IN_BUILD, states.get(400L));
            assertEquals(TableTypes.State.DEFINITION_UNUSABLE, states.get(500L));
            assertFalse(
                    states.containsKey(999L),
                    "Community-named files cannot expand this frozen inventory");
            assertEquals(4, result.progress().tablesTotal());
            assertEquals(4, result.progress().tablesDone());
            assertTrue(result.tables().stream().allMatch(TableTypes.Entry::checked));
            assertEquals(
                    TableTypes.PreloadOutcome.COMPLETED,
                    handle.prepareKnown(null, Cancellation.NONE).join().outcome());
        }
    }

    @Test
    void cancellationIsNotACompletedAssessment() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], false);
        var files = new MemoryFiles().add(BUILD, "enUS", file(100, bytes), bytes, List.of());
        try (var handle =
                service(files, false).forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var token = new Cancellation();
            var result = handle.prepareKnown(progress -> token.cancel(), token).join();
            assertEquals(TableTypes.PreloadOutcome.CANCELED, result.outcome());
        }
    }

    @Test
    void storeFailureDoesNotBecomeTablesReady() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], false);
        var files = new MemoryFiles().add(BUILD, "enUS", file(100, bytes), bytes, List.of());
        files.failure(
                BUILD,
                "enUS",
                100,
                FileTypes.FileRead.failed(
                        FileTypes.FileReadState.STORE_FAILED, 0, "STORE_FAILED", "fixture", null));
        try (var handle =
                service(files, false).forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var result = handle.prepareKnown(null, Cancellation.NONE).join();
            assertEquals(TableTypes.PreloadOutcome.FAILED, result.outcome());
            assertTrue(
                    result.tables().stream()
                            .anyMatch(item -> item.state() == TableTypes.State.STORE_FAILED));
        }
    }

    @Test
    void independentFakeFreezesInventoryAndAccountsForEveryTerminalOutcome() {
        var inventory = new ArrayList<MemoryTables.FakeTable>();
        long id = 0;
        for (var state : TableTypes.State.values()) {
            if (state == TableTypes.State.STORE_FAILED || state == TableTypes.State.NOT_CHECKED) {
                continue;
            }
            inventory.add(
                    new MemoryTables.FakeTable(
                            new TableTypes.Entry(++id, "T" + id, state, false, null, null, null),
                            List.of(),
                            List.of(),
                            null,
                            Map.of(),
                            false));
        }
        var fake = new MemoryTables().add(BUILD, "enUS", inventory);
        try (var handle = fake.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var result =
                    handle.prepareKnown(
                                    progress -> fake.add(BUILD, "enUS", List.of()),
                                    Cancellation.NONE)
                            .join();
            assertEquals(TableTypes.PreloadOutcome.COMPLETED, result.outcome());
            assertEquals(inventory.size(), result.tables().size());
        }
    }

    @Test
    void canceledObserverCannotForgeDrainOrRetireAcceptedEnsure() {
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], false);
        var memory = new MemoryFiles().add(BUILD, "enUS", file(100, bytes), bytes, List.of());
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var disposed = new java.util.concurrent.atomic.AtomicInteger();
        com.vibeminer.files.Files held =
                new com.vibeminer.files.Files() {
                    @Override
                    public com.vibeminer.files.names.FileNames names() {
                        return memory.names();
                    }

                    @Override
                    public CompletableFuture<FileTypes.FilesOpenResult> forBuild(
                            com.vibeminer.builds.BuildTypes.BuildRef build,
                            String locale,
                            Cancellation token) {
                        return memory.forBuild(build, locale, token)
                                .thenApply(
                                        open -> {
                                            var handle =
                                                    (com.vibeminer.files.BuildFiles)
                                                            java.lang.reflect.Proxy
                                                                    .newProxyInstance(
                                                                            com.vibeminer.files
                                                                                    .BuildFiles
                                                                                    .class
                                                                                    .getClassLoader(),
                                                                            new Class<?>[] {
                                                                                com.vibeminer.files
                                                                                        .BuildFiles
                                                                                        .class
                                                                            },
                                                                            (object,
                                                                                    method,
                                                                                    args) -> {
                                                                                if (method.getName()
                                                                                        .equals(
                                                                                                "ensure")) {
                                                                                    entered
                                                                                            .complete(
                                                                                                    null);
                                                                                    return release
                                                                                            .thenCompose(
                                                                                                    unused -> {
                                                                                                        try {
                                                                                                            @SuppressWarnings(
                                                                                                                    "unchecked")
                                                                                                            var
                                                                                                                    future =
                                                                                                                            (CompletableFuture<
                                                                                                                                            FileTypes
                                                                                                                                                    .EnsureResult>)
                                                                                                                                    method
                                                                                                                                            .invoke(
                                                                                                                                                    open
                                                                                                                                                            .files(),
                                                                                                                                                    args);
                                                                                                            return future;
                                                                                                        } catch (
                                                                                                                ReflectiveOperationException
                                                                                                                        error) {
                                                                                                            return CompletableFuture
                                                                                                                    .failedFuture(
                                                                                                                            error);
                                                                                                        }
                                                                                                    });
                                                                                }
                                                                                if (method.getName()
                                                                                        .equals(
                                                                                                "close")) {
                                                                                    disposed
                                                                                            .incrementAndGet();
                                                                                }
                                                                                return method
                                                                                        .invoke(
                                                                                                open
                                                                                                        .files(),
                                                                                                args);
                                                                            });
                                            return new FileTypes.FilesOpenResult(
                                                    build,
                                                    locale,
                                                    open.state(),
                                                    handle,
                                                    null,
                                                    null,
                                                    null);
                                        });
                    }
                };
        // Hold the actual Files boundary while testing table-operation draining.
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var provider =
                BundledDefinitions.of(
                        List.of(
                                new BundledDefinitions.DefinitionTable(
                                        "11223344", "Numbers", "100", true)),
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", definition)));
        var source =
                new TablesService(
                        held,
                        new TableDefinitions(provider, null, null),
                        new GeneratedTables(new MemoryGeneratedStore()),
                        new TablesOptions(
                                (build, token) -> CompletableFuture.completedFuture("1.2.3.4")));
        var handle = source.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
        var observer = handle.prepareKnown(null, Cancellation.NONE);
        entered.join();
        observer.cancel(false);
        var closing = handle.closeAsync();
        assertFalse(closing.isDone());
        assertEquals(0, disposed.get());
        release.complete(null);
        closing.join();
        assertEquals(1, disposed.get());
    }

    @Test
    void uncheckedFakeDataCannotBeClaimedAsCompletedPreparation() {
        var table =
                new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                100,
                                "Unprocessed",
                                TableTypes.State.NOT_CHECKED,
                                false,
                                null,
                                null,
                                null),
                        List.of(),
                        List.of(),
                        null,
                        Map.of(),
                        false);
        var fake = new MemoryTables().add(BUILD, "enUS", List.of(table));
        try (var handle = fake.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            assertEquals(
                    TableTypes.PreloadOutcome.INCOMPLETE,
                    handle.prepareKnown(null, Cancellation.NONE).join().outcome());
        }
    }
}
