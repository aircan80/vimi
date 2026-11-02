package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.effects.EffectsTypes.PageState;
import com.vibeminer.concepts.effects.EffectsTypes.Query;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables.FakeTable;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.TableWorkScheduler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ItemEffectsLifecycleTest {
    private static BuildTables real(EffectsWorld world) {
        return world.tables.forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
    }

    private static BuildTables proxy(
            BuildTables real, BiFunction<String, Object[], Object> intercept) {
        return (BuildTables)
                Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (object, method, args) -> {
                            Object answer = intercept.apply(method.getName(), args);
                            if (answer != null) {
                                return answer;
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            }
                        });
    }

    private static ItemEffects service(BuildTables tables) {
        return new ItemEffectsService(
                (b, l, t) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        b, l, FilesOpenState.OPEN, tables, null, null, null)));
    }

    @Test
    void closeWaitsAcceptedCallEvenAfterOutwardFutureCancellation() throws Exception {
        EffectsWorld world =
                new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
        BuildTables real = real(world);
        CompletableFuture<TableTypes.RowResult> held = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        BuildTables tables =
                proxy(
                        real,
                        (method, args) -> {
                            if (method.equals("get")
                                    && ((TableTypes.Ref) args[0]).name().equals("Item")) {
                                entered.countDown();
                                return held;
                            }
                            if (method.equals("closeAsync")) {
                                closed.incrementAndGet();
                                return real.closeAsync();
                            }
                            return null;
                        });
        var handle =
                service(tables)
                        .forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .effects();
        var page = handle.get(1, new Query(), EffectsWorld.NOW, Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        page.cancel(false);
        var closing = handle.closeAsync();
        assertFalse(closing.isDone());
        assertEquals(0, closed.get());
        assertThrows(
                IllegalStateException.class,
                () -> handle.get(1, new Query(), EffectsWorld.NOW, Cancellation.NONE));
        held.complete(
                real.get(TableTypes.Ref.forName("Item"), 1, EffectsWorld.NOW, Cancellation.NONE)
                        .join());
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(1, closed.get());
        assertTrue(page.isCancelled());
    }

    @Test
    void completionCallbackCanSynchronouslyCloseWithoutHoldingAdmissionLifetime() throws Exception {
        EffectsWorld world =
                new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
        var handle = world.open();
        var done =
                handle.get(1, new Query(), EffectsWorld.NOW, Cancellation.NONE)
                        .thenApply(
                                page -> {
                                    handle.closeAsync().join();
                                    return page;
                                });
        assertEquals(PageState.OK, done.get(5, TimeUnit.SECONDS).state());
    }

    @Test
    void canceledOrAbandonedOpenClosesLateTableHandle() {
        for (boolean tokenCancellation : List.of(false, true)) {
            EffectsWorld world =
                    new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
            BuildTables real = real(world);
            AtomicInteger closed = new AtomicInteger();
            BuildTables wrapped =
                    proxy(
                            real,
                            (method, args) -> {
                                if (method.equals("closeAsync")) {
                                    closed.incrementAndGet();
                                    return real.closeAsync();
                                }
                                if (method.equals("close")) {
                                    closed.incrementAndGet();
                                    real.close();
                                    return Boolean.TRUE;
                                }
                                return null;
                            });
            CompletableFuture<TableTypes.OpenResult> held = new CompletableFuture<>();
            ItemEffects service = new ItemEffectsService((b, l, t) -> held);
            Cancellation token = new Cancellation();
            var result = service.forBuild(EffectsWorld.BUILD, "enUS", token);
            if (tokenCancellation) {
                token.cancel();
            } else {
                result.cancel(false);
            }
            held.complete(
                    new TableTypes.OpenResult(
                            EffectsWorld.BUILD,
                            "enUS",
                            FilesOpenState.OPEN,
                            wrapped,
                            null,
                            null,
                            null));
            assertEquals(1, closed.get());
            assertThrows(RuntimeException.class, result::join);
        }
    }

    @Test
    void explicitCancellationAtTerminalItemOrNameReplyCannotPublishGameFacts() {
        for (String terminal : List.of("Item", "SpellName")) {
            EffectsWorld world =
                    new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
            BuildTables real = real(world);
            Cancellation token = new Cancellation();
            BuildTables wrapped =
                    proxy(
                            real,
                            (method, args) -> {
                                if (method.equals("get")
                                        && terminal.equals(((TableTypes.Ref) args[0]).name())) {
                                    var value =
                                            terminal.equals("Item")
                                                    ? real.get(
                                                                    TableTypes.Ref.forName("Item"),
                                                                    99,
                                                                    EffectsWorld.NOW,
                                                                    Cancellation.NONE)
                                                            .join()
                                                    : real.get(
                                                                    (TableTypes.Ref) args[0],
                                                                    (long) args[1],
                                                                    EffectsWorld.NOW,
                                                                    Cancellation.NONE)
                                                            .join();
                                    token.cancel();
                                    return CompletableFuture.completedFuture(value);
                                }
                                return null;
                            });
            try (var handle =
                    service(wrapped)
                            .forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .effects()) {
                Throwable failure =
                        EffectsTasks.unwrap(
                                assertThrows(
                                        RuntimeException.class,
                                        () ->
                                                handle.get(1, new Query(), EffectsWorld.NOW, token)
                                                        .join()));
                assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
            }
        }
    }

    @Test
    void invalidNullProviderResultFailsItsReturnedFutureRatherThanLeavingItPending() {
        ItemEffects service =
                new ItemEffectsService(
                        (build, locale, token) -> CompletableFuture.completedFuture(null));
        var result = service.forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE);
        assertTrue(result.isCompletedExceptionally());
        assertThrows(RuntimeException.class, result::join);
    }

    @Test
    void explicitCancellationAtFailedOpenReplyDoesNotPublishFailureAsCompletedCall() {
        Cancellation token = new Cancellation();
        ItemEffects service =
                new ItemEffectsService(
                        (build, locale, cancel) -> {
                            cancel.cancel();
                            return CompletableFuture.completedFuture(
                                    new TableTypes.OpenResult(
                                            build,
                                            locale,
                                            FilesOpenState.NEEDS_NETWORK,
                                            null,
                                            "OFFLINE",
                                            "offline",
                                            null));
                        });
        Throwable failure =
                EffectsTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () -> service.forBuild(EffectsWorld.BUILD, "enUS", token).join()));
        assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
    }

    @Test
    void layerFiveBusyFailureIsPropagatedAndDoesNotLeakHandleLifetime() {
        TableWorkScheduler scheduler = new TableWorkScheduler(1, 1, 0);
        try (var slot = scheduler.enter(EffectsWorld.NOW, Cancellation.NONE).join()) {
            assertNotNull(slot);
            Throwable busy =
                    scheduler
                            .enter(EffectsWorld.NOW, Cancellation.NONE)
                            .handle((value, error) -> error)
                            .join();
            assertInstanceOf(TableWorkScheduler.BusyException.class, busy);
            EffectsWorld world =
                    new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
            BuildTables real = real(world);
            BuildTables tables =
                    proxy(
                            real,
                            (method, args) ->
                                    method.equals("get")
                                            ? CompletableFuture.failedFuture(busy)
                                            : null);
            var handle =
                    service(tables)
                            .forBuild(EffectsWorld.BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .effects();
            Throwable failure =
                    EffectsTasks.unwrap(
                            assertThrows(
                                    RuntimeException.class,
                                    () ->
                                            handle.get(
                                                            1,
                                                            new Query(),
                                                            EffectsWorld.NOW,
                                                            Cancellation.NONE)
                                                    .join()));
            assertInstanceOf(TableWorkScheduler.BusyException.class, failure);
            handle.closeAsync().join();
        }
    }

    @Test
    void operationAdmissionIsFailFastAndRecoversAfterDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(EffectsTasks.MAX_ACTIVE),
                release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> accepted = new ArrayList<>();
        try {
            for (int i = 0; i < EffectsTasks.MAX_ACTIVE; i++) {
                accepted.add(
                        EffectsTasks.run(
                                () -> {
                                    entered.countDown();
                                    try {
                                        release.await();
                                    } catch (InterruptedException e) {
                                        throw new IllegalStateException(e);
                                    }
                                    return 1;
                                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var refused =
                    EffectsTasks.run(
                            () -> {
                                throw new AssertionError("Overflow work must never start");
                            });
            assertTrue(refused.isCompletedExceptionally());
            assertEquals(
                    "ITEM_EFFECTS_OPERATION_LIMIT",
                    refused.handle((v, e) -> e.getMessage()).join());
        } finally {
            release.countDown();
            CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).join();
        }
        assertEquals(7, EffectsTasks.run(() -> 7).join());
    }

    @Test
    void changedSourceMakesOldCursorStaleOnExistingAndNewHandles() {
        EffectsWorld world =
                new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
        try (var original = world.open()) {
            var first = original.get(1, new Query(2), EffectsWorld.NOW, Cancellation.NONE).join();
            var tables = new ArrayList<>(EffectsWorld.retail());
            FakeTable bridge = tables.get(1);
            var entry = bridge.entry();
            tables.set(
                    1,
                    new FakeTable(
                            new TableTypes.Entry(
                                    entry.fileDataId(),
                                    entry.name(),
                                    entry.state(),
                                    true,
                                    entry.reason(),
                                    entry.counts(),
                                    "e".repeat(32)),
                            bridge.columns(),
                            bridge.rows(),
                            bridge.schema(),
                            bridge.protectedIds(),
                            true));
            world.with(tables.toArray(FakeTable[]::new));
            try (var changed = world.open()) {
                assertEquals(
                        PageState.STALE,
                        changed.get(
                                        1,
                                        new Query(2, first.next()),
                                        EffectsWorld.NOW,
                                        Cancellation.NONE)
                                .join()
                                .state());
            }
            assertEquals(
                    PageState.STALE,
                    original.get(1, new Query(2, first.next()), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
            assertEquals(
                    PageState.OK,
                    original.get(1, new Query(2), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @Test
    void changedLinkAndOutOfBoundsCursorAreNeverRebased() {
        EffectsWorld world =
                new EffectsWorld().with(EffectsWorld.retail().toArray(FakeTable[]::new));
        try (var handle = world.open()) {
            var first = handle.get(1, new Query(2), EffectsWorld.NOW, Cancellation.NONE).join();
            var decoded =
                    ItemEffectQueries.decodeCursor(
                            first.next(),
                            ItemEffectQueries.binding(EffectsWorld.BUILD, "enUS", 1, 2));
            String past =
                    ItemEffectQueries.encodeCursor(
                            new ItemEffectQueries.Cursor(
                                    decoded.b(), Integer.MAX_VALUE, decoded.i(), decoded.l()));
            assertEquals(
                    PageState.INVALID_CURSOR,
                    handle.get(1, new Query(2, past), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
            world.with(
                    EffectsWorld.itemTable(List.of(EffectsWorld.item(1))),
                    EffectsWorld.classicTable(List.of(EffectsWorld.classic(7, 1, 0))),
                    EffectsWorld.nameTable(List.of(EffectsWorld.name(0, "Zero"))));
            assertEquals(
                    PageState.STALE,
                    handle.get(1, new Query(2, first.next()), EffectsWorld.NOW, Cancellation.NONE)
                            .join()
                            .state());
        }
    }
}
