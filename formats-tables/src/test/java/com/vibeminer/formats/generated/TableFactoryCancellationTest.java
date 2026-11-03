package com.vibeminer.formats.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.TablesOptions;
import com.vibeminer.formats.tables.TablesService;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class TableFactoryCancellationTest {
    @Test
    void firstWaiterAndItsFilesCanCloseWhileSharedFactoryKeepsReading() {
        var build = new BuildRef("wow", "a".repeat(32), "b".repeat(32));
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], true);
        var file =
                new FileTypes.BuildFile(
                        100,
                        "dbfilesclient/numbers.db2",
                        FileTypes.FilePathSource.LISTFILE,
                        (long) bytes.length,
                        "1".repeat(32),
                        FileTypes.FileFormat.DB2,
                        FileTypes.FileFormatSource.EXTENSION,
                        FileTypes.FileStorage.STORED,
                        false);
        var files = new MemoryFiles().add(build, "enUS", file, bytes, List.of());
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
        var actual = new GeneratedTables(new MemoryGeneratedStore());
        var both = new CompletableFuture<Void>();
        actual.waiterProbe =
                n -> {
                    if (n == 2) {
                        both.complete(null);
                    }
                };
        var reading = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var retained = new java.util.concurrent.atomic.AtomicReference<GeneratedTableContent>();
        GeneratedTableStore generated =
                new GeneratedTableStore() {
                    @Override
                    public CompletableFuture<GeneratedTable> open(
                            GeneratedTableSource source, KeyStatus keys, Cancellation token) {
                        return actual.open(source, keys, token);
                    }

                    @Override
                    public CompletableFuture<GeneratedTable> store(
                            GeneratedTableSource source,
                            KeyStatus keys,
                            GeneratedTableContent content,
                            Cancellation token) {
                        return actual.store(source, keys, content, token);
                    }

                    @Override
                    public CompletableFuture<GeneratedTable> getOrBuild(
                            GeneratedTableSource source,
                            KeyStatus keys,
                            Function<Cancellation, CompletableFuture<GeneratedTableContent>>
                                    factory,
                            Cancellation token) {
                        return actual.getOrBuild(
                                source,
                                keys,
                                shared ->
                                        factory.apply(shared)
                                                .thenCompose(
                                                        content -> {
                                                            retained.set(content);
                                                            reading.complete(null);
                                                            return release.thenApply(
                                                                    ignored -> {
                                                                        shared.check();
                                                                        return content;
                                                                    });
                                                        }),
                                token);
                    }

                    @Override
                    public CompletableFuture<String> layoutOf(String key, Cancellation token) {
                        return actual.layoutOf(key, token);
                    }

                    @Override
                    public String identityOf(
                            GeneratedTableSource source,
                            KeyStatus keys,
                            List<String> names,
                            boolean encrypted) {
                        return actual.identityOf(source, keys, names, encrypted);
                    }
                };
        var service =
                new TablesService(
                        files,
                        new TableDefinitions(provider, null, null),
                        generated,
                        new TablesOptions(
                                (ref, token) -> CompletableFuture.completedFuture("1.2.3.4")));
        var first = service.forBuild(build, "enUS", Cancellation.NONE).join().tables();
        var second = service.forBuild(build, "enUS", Cancellation.NONE).join().tables();
        var canceled = new Cancellation();
        var one = first.check(TableTypes.Ref.id(100), TableTypes.Priority.INTERACTIVE, canceled);
        reading.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
        var two =
                second.check(
                        TableTypes.Ref.id(100), TableTypes.Priority.INTERACTIVE, Cancellation.NONE);
        both.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
        canceled.cancel();
        assertThrows(CompletionException.class, one::join);
        first.closeAsync().join();
        assertEquals(
                1,
                retained.get().rows().read(0, 1, Cancellation.NONE).join().size(),
                "Shared native source stays readable after the first waiter closes");
        release.complete(null);
        assertEquals(TableTypes.State.READY, two.join().state());
        assertThrows(
                RuntimeException.class,
                () -> retained.get().rows().read(0, 1, Cancellation.NONE).join(),
                "Store closes the native source owner after publication");
        assertTrue(
                second.page(
                                        new TableTypes.PageRequest(TableTypes.Ref.id(100)),
                                        TableTypes.Priority.INTERACTIVE,
                                        Cancellation.NONE)
                                .join()
                                .rows()
                                .size()
                        == 2);
        second.close();
    }
}
