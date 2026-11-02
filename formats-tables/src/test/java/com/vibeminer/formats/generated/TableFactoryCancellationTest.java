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
import java.util.concurrent.atomic.AtomicInteger;
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
        var closed = new AtomicInteger();
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
                                                .thenApply(
                                                        content ->
                                                                new GeneratedTableContent(
                                                                        content.header(),
                                                                        content.sections(),
                                                                        content.completion(),
                                                                        content.keysUsed(),
                                                                        (first,
                                                                                count,
                                                                                readToken) -> {
                                                                            reading.complete(null);
                                                                            return release
                                                                                    .thenCompose(
                                                                                            ignored -> {
                                                                                                shared
                                                                                                        .check();
                                                                                                readToken
                                                                                                        .check();
                                                                                                return content.rows()
                                                                                                        .read(
                                                                                                                first,
                                                                                                                count,
                                                                                                                readToken);
                                                                                            });
                                                                        },
                                                                        () -> {
                                                                            closed
                                                                                    .incrementAndGet();
                                                                            content.owner().close();
                                                                        },
                                                                        content.holeKeys(),
                                                                        content.chunkKeys())),
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
        reading.join();
        var two =
                second.check(
                        TableTypes.Ref.id(100), TableTypes.Priority.INTERACTIVE, Cancellation.NONE);
        both.join();
        canceled.cancel();
        assertThrows(CompletionException.class, one::join);
        first.closeAsync().join();
        assertEquals(0, closed.get());
        release.complete(null);
        assertEquals(TableTypes.State.READY, two.join().state());
        assertEquals(1, closed.get());
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
