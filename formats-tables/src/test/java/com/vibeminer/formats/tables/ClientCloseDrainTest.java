package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ClientCloseDrainTest {
    @Test
    @SuppressWarnings("unchecked")
    void closeAttemptsLaterCancellationAndWaitsHeldActualCallBeforeFilesRetire() throws Exception {
        var statEntered = new CompletableFuture<Void>();
        var stat = new CompletableFuture<FileTypes.FileStat>();
        var filesClosed = new AtomicInteger();
        var fileFailure = new IllegalStateException("authored file close failure");
        BuildFiles files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (p, m, a) ->
                                        switch (m.getName()) {
                                            case "build" -> TablesContract.BUILD;
                                            case "locale" -> "enUS";
                                            case "stat" -> {
                                                statEntered.complete(null);
                                                yield stat;
                                            }
                                            case "close" -> {
                                                filesClosed.incrementAndGet();
                                                throw fileFailure;
                                            }
                                            default -> throw new AssertionError(m.getName());
                                        });
        var document =
                Db2Fixture.definition(
                        "int ID\\nint Value".replace("\\n", "\n"),
                        "$id,noninline$ID<u32>\\nValue<u32>".replace("\\n", "\n"));
        var definitions =
                new TableDefinitions(
                        BundledDefinitions.of(
                                List.of(
                                        new BundledDefinitions.DefinitionTable(
                                                "11223344", "Numbers", "100", true)),
                                Map.of(
                                        "11223344",
                                        new BundledDefinitions.Definition(
                                                "a".repeat(64), "authored", document))),
                        null,
                        null);
        var client =
                new ClientBuildTables(
                        null,
                        files,
                        "1.2.3.4",
                        definitions,
                        new GeneratedTables(new MemoryGeneratedStore()),
                        new TablesOptions((b, t) -> CompletableFuture.completedFuture("1.2.3.4")),
                        new BackgroundBuilds(),
                        new ProtectedIds());
        var held =
                client.schema(
                        TableTypes.Ref.id(100), TableTypes.Priority.INTERACTIVE, Cancellation.NONE);
        var callbackEntered = new CompletableFuture<Void>();
        var releaseCallback = new CompletableFuture<Void>();
        var firstFailure = new IllegalStateException("authored cancellation failure");
        try {
            statEntered.get(3, TimeUnit.SECONDS);
            // Negative-only fault injection: actual accepted schema IO above remains held.
            var field = ClientBuildTables.class.getDeclaredField("preloads");
            field.setAccessible(true);
            var gateField = ClientBuildTables.class.getDeclaredField("gate");
            gateField.setAccessible(true);
            Object gate = gateField.get(client);
            List<Cancellation> order;
            synchronized (gate) {
                var pending = (Set<Cancellation>) field.get(client);
                pending.add(new Cancellation());
                pending.add(new Cancellation());
                order = new ArrayList<>(pending);
            }
            order.get(0)
                    .onCancel(
                            () -> {
                                throw firstFailure;
                            });
            order.get(1)
                    .onCancel(
                            () -> {
                                callbackEntered.complete(null);
                                releaseCallback.join();
                            });
            var invocation = CompletableFuture.supplyAsync(client::closeAsync);
            try {
                callbackEntered.get(3, TimeUnit.SECONDS);
                assertFalse(invocation.isDone());
                assertEquals(0, filesClosed.get());
                assertFalse(client.whenClosed().isDone());
                releaseCallback.complete(null);
                var drain = invocation.get(3, TimeUnit.SECONDS);
                assertFalse(drain.isDone());
                assertEquals(0, filesClosed.get());
                stat.complete(new FileTypes.FileStat(FileTypes.FileLookupState.UNKNOWN_FILE, null));
                held.get(3, TimeUnit.SECONDS);
                var error =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> drain.get(3, TimeUnit.SECONDS));
                assertSame(firstFailure, error.getCause().getCause());
                assertTrue(List.of(firstFailure.getSuppressed()).contains(fileFailure));
                assertEquals(1, filesClosed.get());
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> client.closeAsync().get(3, TimeUnit.SECONDS));
                assertEquals(1, filesClosed.get());
            } finally {
                releaseCallback.complete(null);
            }
        } finally {
            stat.complete(new FileTypes.FileStat(FileTypes.FileLookupState.UNKNOWN_FILE, null));
            releaseCallback.complete(null);
        }
    }
}
