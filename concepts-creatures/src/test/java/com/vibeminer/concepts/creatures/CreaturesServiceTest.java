package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResultState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPageState;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.Tables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class CreaturesServiceTest {
    static final TableTypes.Priority P = TableTypes.Priority.INTERACTIVE;

    @Test
    void listDoesNotOpenDisplaysOrFilesAndDetailCoalescesRepeatedEdges() {
        var world =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(
                                                CreatureWorld.creature(
                                                        10, "Hogger", 100, 100, 0, -1))),
                                CreatureWorld.displays(List.of(CreatureWorld.display(100, "33"))),
                                CreatureWorld.models(List.of(CreatureWorld.model(33, "7001"))));
        try (var handle = world.open()) {
            assertEquals(
                    1,
                    handle.list(new CreatureQuery(), P, Cancellation.NONE).join().entries().size());
            assertEquals(1, world.tables.calls("schema"));
            assertEquals(0, world.tables.calls("get"));
            var result = handle.get(10, P, Cancellation.NONE).join();
            assertEquals(CreatureResultState.FOUND, result.state());
            assertEquals(
                    List.of(
                            CreatureDisplayChainState.METADATA_RESOLVED,
                            CreatureDisplayChainState.METADATA_RESOLVED,
                            CreatureDisplayChainState.NO_DECLARED_DISPLAY,
                            CreatureDisplayChainState.INVALID_DISPLAY_REFERENCE),
                    result.detail().displays().stream().map(c -> c.state()).toList());
            assertEquals(3, world.tables.calls("get"));
            assertEquals(7001L, result.detail().displays().getFirst().modelFileDataId());
            assertEquals(
                    CreatureTypes.CreatureModelState.FILES_NOT_OPEN,
                    result.detail().displays().getFirst().model().state());
        }
    }

    @Test
    void missingZeroAndPartialEdgesRetainPrefixes() {
        var creature =
                CreatureWorld.table(
                        841631,
                        "Creature",
                        "6E14C900",
                        CreatureWorld.creatureFields(),
                        List.of(CreatureWorld.creature(10, "x", 100, 200, 300, 999)),
                        TableTypes.State.PARTIAL,
                        Map.of(20L, "0123456789ABCDEF"));
        var display =
                CreatureWorld.table(
                        1108759,
                        "CreatureDisplayInfo",
                        "7275F5F6",
                        CreatureWorld.displayFields(),
                        List.of(
                                CreatureWorld.display(100, "0"),
                                CreatureWorld.display(200, "33"),
                                CreatureWorld.display(300, "44")),
                        TableTypes.State.PARTIAL,
                        Map.of(999L, "ABCDEF0123456789"));
        var world =
                new CreatureWorld()
                        .with(
                                creature,
                                display,
                                CreatureWorld.models(List.of(CreatureWorld.model(33, "0"))));
        try (var handle = world.open()) {
            var chains = handle.get(10, P, Cancellation.NONE).join().detail().displays();
            assertEquals(
                    List.of(
                            CreatureDisplayChainState.NO_DECLARED_MODEL,
                            CreatureDisplayChainState.NO_DECLARED_MODEL_FILE,
                            CreatureDisplayChainState.MODEL_ABSENT,
                            CreatureDisplayChainState.DISPLAY_PROTECTED),
                    chains.stream().map(c -> c.state()).toList());
            assertEquals(0L, chains.get(1).modelFileDataId());
            assertEquals("ABCDEF0123456789", chains.get(3).protectedKey());
            assertNull(chains.get(3).displayRow());
            assertEquals(
                    CreatureResultState.PROTECTED,
                    handle.get(20, P, Cancellation.NONE).join().state());
            assertEquals(
                    CreatureResultState.UNKNOWN,
                    handle.get(21, P, Cancellation.NONE).join().state());
        }
    }

    @Test
    void wrongProfileAndNoncanonicalRowsAreExplicit() {
        var wrong =
                CreatureWorld.table(
                        841631,
                        "Creature",
                        "6E14C900",
                        CreatureWorld.displayFields(),
                        List.of(),
                        TableTypes.State.READY,
                        Map.of());
        try (var handle = new CreatureWorld().with(wrong).open()) {
            assertEquals(
                    CreaturesPageState.SCHEMA_UNSUPPORTED,
                    handle.list(new CreatureQuery(), P, Cancellation.NONE).join().state());
        }
        var row = CreatureWorld.creature(10, "x");
        var cells = new java.util.ArrayList<>(row.values());
        cells.set(5, "128");
        try (var handle =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(
                                                new com.vibeminer.formats.db2.Db2Types.Row(
                                                        10, false, null, cells, null))))
                        .open()) {
            assertEquals(
                    CreatureResultState.SOURCE_INVALID,
                    handle.get(10, P, Cancellation.NONE).join().state());
            assertEquals(
                    CreaturesPageState.SOURCE_INVALID,
                    handle.list(new CreatureQuery(), P, Cancellation.NONE).join().state());
        }
    }

    @Test
    void closingDrainsAcceptedOperationAndRejectsNewWork() throws Exception {
        var world =
                new CreatureWorld()
                        .with(CreatureWorld.creatures(List.of(CreatureWorld.creature(10, "x"))));
        var real =
                world.tables
                        .forBuild(CreatureWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<TableTypes.RowResult>();
        var closed = new AtomicBoolean();
        BuildTables wrapped =
                (BuildTables)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("get")) {
                                        entered.countDown();
                                        return release;
                                    }
                                    if (method.getName().equals("closeAsync")) {
                                        closed.set(true);
                                        return real.closeAsync();
                                    }
                                    try {
                                        return method.invoke(real, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });
        Tables tables =
                (build, locale, token) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        build,
                                        locale,
                                        FileTypes.FilesOpenState.OPEN,
                                        wrapped,
                                        null,
                                        null,
                                        null));
        var service =
                new CreaturesService(
                        tables, world.files, new BlpImages(new MemoryGeneratedStore()));
        var handle =
                service.forBuild(CreatureWorld.BUILD, "enUS", Cancellation.NONE).join().creatures();
        var pending = handle.get(10, P, Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var close = handle.closeAsync();
        assertFalse(close.isDone());
        assertFalse(closed.get());
        assertThrows(IllegalStateException.class, () -> handle.get(10, P, Cancellation.NONE));
        pending.cancel(false);
        assertFalse(close.isDone());
        release.complete(
                real.get(TableTypes.Ref.forName("Creature"), 10, P, Cancellation.NONE).join());
        close.get(5, TimeUnit.SECONDS);
        assertTrue(closed.get());
    }

    @Test
    void invalidLocaleIsArgumentFailure() {
        var world = new CreatureWorld();
        assertThrows(
                IllegalArgumentException.class,
                () -> world.service.forBuild(CreatureWorld.BUILD, "bad", Cancellation.NONE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MemoryCreatures()
                                .forBuild(CreatureWorld.BUILD, "bad", Cancellation.NONE));
    }

    @Test
    void boundedSearchResumesAndReseededIdentityIsStale() {
        var world =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(
                                                CreatureWorld.creature(10, "none"),
                                                CreatureWorld.creature(20, "none"),
                                                CreatureWorld.creature(30, "Hogger"))));
        var service =
                new CreaturesService(
                        world.tables,
                        world.files,
                        new BlpImages(new MemoryGeneratedStore()),
                        new CreaturesOptions(1));
        try (var handle =
                service.forBuild(CreatureWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .creatures()) {
            var first =
                    handle.list(new CreatureQuery("HOG", null, 2, null), P, Cancellation.NONE)
                            .join();
            assertEquals(1, first.scanned());
            assertTrue(first.entries().isEmpty());
            var second =
                    handle.list(
                                    new CreatureQuery("HOG", null, 2, first.next()),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(1, second.scanned());
            assertTrue(second.entries().isEmpty());
            var third =
                    handle.list(
                                    new CreatureQuery("HOG", null, 2, second.next()),
                                    P,
                                    Cancellation.NONE)
                            .join();
            assertEquals(30, third.entries().getFirst().id());
            assertNull(third.next());
            world.with(
                    CreatureWorld.creatures(
                            List.of(
                                    CreatureWorld.creature(10, "changed"),
                                    CreatureWorld.creature(20, "none"),
                                    CreatureWorld.creature(30, "Hogger"))));
            assertEquals(
                    CreaturesPageState.STALE,
                    handle.list(
                                    new CreatureQuery("HOG", null, 2, first.next()),
                                    P,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @Test
    void canceledOpeningFutureDisposesLateLowerHandle() throws Exception {
        var world = new CreatureWorld().with(CreatureWorld.creatures(List.of()));
        var real =
                world.tables
                        .forBuild(CreatureWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var closed = new CountDownLatch(1);
        BuildTables wrapped =
                (BuildTables)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("closeAsync")) {
                                        closed.countDown();
                                        return real.closeAsync();
                                    }
                                    try {
                                        return method.invoke(real, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });
        var lower = new CompletableFuture<TableTypes.OpenResult>();
        Tables tables = (build, locale, token) -> lower;
        var service =
                new CreaturesService(
                        tables, world.files, new BlpImages(new MemoryGeneratedStore()));
        var opening = service.forBuild(CreatureWorld.BUILD, "enUS", Cancellation.NONE);
        opening.cancel(false);
        lower.complete(
                new TableTypes.OpenResult(
                        CreatureWorld.BUILD,
                        "enUS",
                        FileTypes.FilesOpenState.OPEN,
                        wrapped,
                        null,
                        null,
                        null));
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }
}
