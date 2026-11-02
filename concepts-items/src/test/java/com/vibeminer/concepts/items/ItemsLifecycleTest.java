package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.Tables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ItemsLifecycleTest {
    private static MemoryTables data() {
        return ItemsWorld.data(
                List.of(ItemsWorld.row(7, "7", "2", "7", "13", "900")),
                List.of(ItemsWorld.row(7, "7", "Name", "4", "500", "13")));
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static BuildTables tracked(
            BuildTables lower,
            CountDownLatch closed,
            CountDownLatch entered,
            CompletableFuture<Void> release) {
        return (BuildTables)
                Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (p, m, a) -> {
                            if (m.getName().equals("closeAsync")) {
                                closed.countDown();
                                return lower.closeAsync();
                            }
                            if (m.getName().equals("schema") && release != null) {
                                entered.countDown();
                                return release.thenCompose(
                                        ignored ->
                                                lower.schema(
                                                        (TableTypes.Ref) a[0],
                                                        (TableTypes.Priority) a[1],
                                                        (Cancellation) a[2]));
                            }
                            return invoke(lower, m, a);
                        });
    }

    @Test
    void acceptedGetDrainsEvenWhenObserverFutureIsCanceled() throws Exception {
        BuildTables lower =
                data().forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
        CountDownLatch closed = new CountDownLatch(1), entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        BuildTables tracked = tracked(lower, closed, entered, release);
        Tables tables =
                (b, l, c) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        b,
                                        l,
                                        FileTypes.FilesOpenState.OPEN,
                                        tracked,
                                        null,
                                        null,
                                        null));
        var handle =
                new ItemsService(
                                tables,
                                new MemoryFiles(),
                                new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .items();
        var get = handle.get(7, ItemsWorld.PRIORITY, Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        get.cancel(false);
        var closing = handle.closeAsync();
        assertFalse(closing.isDone());
        assertEquals(1, closed.getCount());
        release.complete(null);
        closing.join();
        assertEquals(0, closed.getCount());
        assertThrows(
                IllegalStateException.class,
                () -> handle.get(7, ItemsWorld.PRIORITY, Cancellation.NONE));
    }

    @Test
    void cancelingOpenObserverClosesLateLowerHandle() throws Exception {
        BuildTables lower =
                data().forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
        CountDownLatch closed = new CountDownLatch(1);
        BuildTables tracked = tracked(lower, closed, null, null);
        CompletableFuture<TableTypes.OpenResult> gate = new CompletableFuture<>();
        var service =
                new ItemsService(
                        (b, l, c) -> gate,
                        new MemoryFiles(),
                        new BlpImages(new MemoryGeneratedStore()));
        var opening = service.forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE);
        opening.cancel(false);
        gate.complete(
                new TableTypes.OpenResult(
                        ItemsWorld.BUILD,
                        "enUS",
                        FileTypes.FilesOpenState.OPEN,
                        tracked,
                        null,
                        null,
                        null));
        try {
            assertTrue(
                    closed.await(5, TimeUnit.SECONDS),
                    "Canceled opening must retire late-owned tables");
        } finally {
            lower.close();
        }
    }

    @Test
    void explicitOpenCancellationRetiresSuccessfulLowerResult() throws Exception {
        BuildTables lower =
                data().forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
        CountDownLatch closed = new CountDownLatch(1);
        BuildTables tracked = tracked(lower, closed, null, null);
        CompletableFuture<TableTypes.OpenResult> gate = new CompletableFuture<>();
        Cancellation token = new Cancellation();
        var service =
                new ItemsService(
                        (b, l, c) -> gate,
                        new MemoryFiles(),
                        new BlpImages(new MemoryGeneratedStore()));
        var opening = service.forBuild(ItemsWorld.BUILD, "enUS", token);
        token.cancel();
        gate.complete(
                new TableTypes.OpenResult(
                        ItemsWorld.BUILD,
                        "enUS",
                        FileTypes.FilesOpenState.OPEN,
                        tracked,
                        null,
                        null,
                        null));
        try {
            assertThrows(CancellationException.class, opening::join);
            assertTrue(closed.await(5, TimeUnit.SECONDS));
        } finally {
            lower.close();
        }
    }

    @Test
    void listsDoNotOpenFilesAndFailedLazyOpenIsRetriedThenShared() {
        AtomicInteger attempts = new AtomicInteger(), closes = new AtomicInteger();
        var memory = new MemoryFiles();
        byte[] badImage = new byte[148];
        memory.add(
                ItemsWorld.BUILD,
                "enUS",
                new FileTypes.BuildFile(
                        900,
                        "test.blp",
                        FileTypes.FilePathSource.LISTFILE,
                        148L,
                        "a".repeat(32),
                        FileTypes.FileFormat.BLP,
                        FileTypes.FileFormatSource.EXTENSION,
                        FileTypes.FileStorage.STORED,
                        false),
                badImage,
                List.of());
        com.vibeminer.files.Files files =
                new com.vibeminer.files.Files() {
                    public com.vibeminer.files.names.FileNames names() {
                        return com.vibeminer.files.names.FileNames.OFF;
                    }

                    public CompletableFuture<FileTypes.FilesOpenResult> forBuild(
                            com.vibeminer.builds.BuildTypes.BuildRef b, String l, Cancellation c) {
                        if (attempts.incrementAndGet() == 1) {
                            return CompletableFuture.completedFuture(
                                    new FileTypes.FilesOpenResult(
                                            b,
                                            l,
                                            FileTypes.FilesOpenState.UNAVAILABLE,
                                            null,
                                            "SYNTHETIC",
                                            "Temporary failure",
                                            null));
                        }
                        return memory.forBuild(b, l, c)
                                .thenApply(
                                        open -> {
                                            BuildFiles wrapped =
                                                    (BuildFiles)
                                                            Proxy.newProxyInstance(
                                                                    BuildFiles.class
                                                                            .getClassLoader(),
                                                                    new Class<?>[] {
                                                                        BuildFiles.class
                                                                    },
                                                                    (p, m, a) -> {
                                                                        if (m.getName()
                                                                                .equals("close")) {
                                                                            closes
                                                                                    .incrementAndGet();
                                                                        }
                                                                        return invoke(
                                                                                open.files(), m, a);
                                                                    });
                                            return new FileTypes.FilesOpenResult(
                                                    b, l, open.state(), wrapped, null, null, null);
                                        });
                    }
                };
        try (var handle =
                new ItemsService(data(), files, new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .items()) {
            assertEquals(
                    ItemsPageState.OK,
                    handle.list(new ItemQuery(), ItemsWorld.PRIORITY, Cancellation.NONE)
                            .join()
                            .state());
            assertEquals(0, attempts.get());
            assertEquals(
                    ItemIconState.FILES_NOT_OPEN,
                    handle.get(7, ItemsWorld.PRIORITY, Cancellation.NONE)
                            .join()
                            .detail()
                            .icon()
                            .state());
            assertNotEquals(
                    ItemIconState.FILES_NOT_OPEN,
                    handle.get(7, ItemsWorld.PRIORITY, Cancellation.NONE)
                            .join()
                            .detail()
                            .icon()
                            .state());
            handle.get(7, ItemsWorld.PRIORITY, Cancellation.NONE).join();
            assertEquals(2, attempts.get());
        }
        assertEquals(1, closes.get());
    }

    @Test
    void terminalLowerResultCannotPublishAfterExplicitCancellation() {
        var token = new Cancellation();
        var lower = data().forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
        var wrapped =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    Object result = invoke(lower, method, args);
                                    if (method.getName().equals("get")) {
                                        token.cancel();
                                    }
                                    return result;
                                });
        Tables tables =
                (b, l, c) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        b,
                                        l,
                                        FileTypes.FilesOpenState.OPEN,
                                        wrapped,
                                        null,
                                        null,
                                        null));
        try (var handle =
                new ItemsService(
                                tables,
                                new MemoryFiles(),
                                new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .items()) {
            var failure =
                    assertThrows(
                            java.util.concurrent.CompletionException.class,
                            () -> handle.get(123, ItemsWorld.PRIORITY, token).join());
            assertTrue(failure.getCause() instanceof CancellationException);
        }
    }

    @Test
    void immediateObserverCancellationCannotSkipAcceptedWorkRetirement() {
        for (int i = 0; i < 100; i++) {
            var handle =
                    new ItemsService(
                                    data(),
                                    new MemoryFiles(),
                                    new BlpImages(new MemoryGeneratedStore()))
                            .forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE)
                            .join()
                            .items();
            handle.get(123, ItemsWorld.PRIORITY, Cancellation.NONE).cancel(false);
            handle.closeAsync().orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void boundedAdmissionHasNoWaitingQueueAndReleasesAfterObserverCancellation() throws Exception {
        var lower = data().forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var wrapped = tracked(lower, new CountDownLatch(1), entered, release);
        Tables tables =
                (b, l, c) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        b,
                                        l,
                                        FileTypes.FilesOpenState.OPEN,
                                        wrapped,
                                        null,
                                        null,
                                        null));
        var handle =
                new ItemsService(
                                tables,
                                new MemoryFiles(),
                                new BlpImages(new MemoryGeneratedStore()),
                                new ItemsOptions(20000, 200000, 4000000, 16, 32, 8, 1))
                        .forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .items();
        var first = handle.get(123, ItemsWorld.PRIORITY, Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var rejected = handle.get(123, ItemsWorld.PRIORITY, Cancellation.NONE);
        assertTrue(rejected.isDone());
        var error = assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
        assertEquals("ITEMS_OPERATION_LIMIT", error.getCause().getMessage());
        first.cancel(false);
        var closing = handle.closeAsync();
        assertFalse(closing.isDone());
        release.complete(null);
        closing.join();
    }

    @Test
    void idleCloseReturnsWithoutBlockingAndObserversCannotForgeDrain() throws Exception {
        var lower = data().forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE).join().tables();
        var closeEntered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var wrapped =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("closeAsync")) {
                                        closeEntered.countDown();
                                        return release.thenCompose(ignored -> lower.closeAsync());
                                    }
                                    return invoke(lower, method, args);
                                });
        Tables tables =
                (b, l, c) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        b,
                                        l,
                                        FileTypes.FilesOpenState.OPEN,
                                        wrapped,
                                        null,
                                        null,
                                        null));
        var handle =
                new ItemsService(
                                tables,
                                new MemoryFiles(),
                                new BlpImages(new MemoryGeneratedStore()))
                        .forBuild(ItemsWorld.BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .items();
        var returned = new CompletableFuture<CompletableFuture<Void>>();
        Thread.startVirtualThread(() -> returned.complete(handle.closeAsync()));
        try {
            var firstObserver = returned.get(5, TimeUnit.SECONDS);
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            assertFalse(firstObserver.isDone());
            firstObserver.cancel(false);
            var secondObserver = handle.closeAsync();
            assertFalse(secondObserver.isDone());
            release.complete(null);
            secondObserver.get(5, TimeUnit.SECONDS);
        } finally {
            release.complete(null);
            handle.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }
}
