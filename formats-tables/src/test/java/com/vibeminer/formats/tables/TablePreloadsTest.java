package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.files.FileTypes.FilesOpenState;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class TablePreloadsTest {
    @Test
    void outwardCompletionCancellationCannotForgeDrainOrSkipHandleClose() {
        var held = new CompletableFuture<TableTypes.OpenResult>();
        var started = new CompletableFuture<Void>();
        var closed = new AtomicInteger();
        Tables tables =
                (build, locale, token) -> {
                    started.complete(null);
                    return held;
                };
        var manager = new TablePreloads(tables);
        var job = manager.start(TablesContract.BUILD, "enUS", null);
        assertSame(job, manager.start(TablesContract.BUILD, "enUS", null));
        started.join();
        job.completion().cancel(false);
        var close = manager.closeAsync();
        assertFalse(close.isDone());
        var progress =
                new TableTypes.PreloadProgress(
                        TableTypes.PreloadStage.DOWNLOADING, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        BuildTables handle =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (object, method, args) -> {
                                    if (method.getName().equals("preload")) {
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.PreloadResult(
                                                        TableTypes.PreloadOutcome.CANCELED,
                                                        progress,
                                                        List.of(),
                                                        null,
                                                        null,
                                                        null));
                                    }
                                    if (method.getName().equals("close")) {
                                        closed.incrementAndGet();
                                        return null;
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        held.complete(
                new TableTypes.OpenResult(
                        TablesContract.BUILD,
                        "enUS",
                        FilesOpenState.OPEN,
                        handle,
                        null,
                        null,
                        null));
        close.join();
        assertEquals(1, closed.get());
        assertEquals(TableTypes.PreloadOutcome.CANCELED, job.completion().join().outcome());
        assertEquals(job.completion().join(), manager.last(TablesContract.BUILD, "enUS"));
    }

    @Test
    void slowThrowingSubscriberCoalescesButStillReceivesFinalAfterStageChanges() {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var handleClosed = new CompletableFuture<Void>();
        var reports = new CopyOnWriteArrayList<TableTypes.PreloadProgress>();
        BuildTables handle =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (object, method, args) -> {
                                    if (method.getName().equals("preload")) {
                                        @SuppressWarnings("unchecked")
                                        var progress =
                                                (java.util.function.Consumer<
                                                                TableTypes.PreloadProgress>)
                                                        args[0];
                                        for (int i = 0; i < 1000; i++) {
                                            progress.accept(
                                                    new TableTypes.PreloadProgress(
                                                            TableTypes.PreloadStage.DOWNLOADING,
                                                            i,
                                                            1000,
                                                            0,
                                                            i,
                                                            1000,
                                                            i,
                                                            0,
                                                            0,
                                                            0));
                                        }
                                        var last =
                                                new TableTypes.PreloadProgress(
                                                        TableTypes.PreloadStage.PREPARING,
                                                        1000,
                                                        1000,
                                                        0,
                                                        1000,
                                                        1000,
                                                        1000,
                                                        1,
                                                        1,
                                                        0);
                                        progress.accept(last);
                                        return CompletableFuture.completedFuture(
                                                new TableTypes.PreloadResult(
                                                        TableTypes.PreloadOutcome.COMPLETED,
                                                        last,
                                                        List.of(),
                                                        null,
                                                        null,
                                                        null));
                                    }
                                    if (method.getName().equals("close")) {
                                        handleClosed.complete(null);
                                        return null;
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        Tables tables =
                (build, locale, token) ->
                        CompletableFuture.completedFuture(
                                new TableTypes.OpenResult(
                                        build,
                                        locale,
                                        FilesOpenState.OPEN,
                                        handle,
                                        null,
                                        null,
                                        null));
        var manager = new TablePreloads(tables);
        var job =
                manager.start(
                        TablesContract.BUILD,
                        "enUS",
                        report -> {
                            reports.add(report);
                            if (reports.size() == 1) {
                                entered.complete(null);
                                release.join();
                            } else if (reports.size() == 2) {
                                throw new AssertionError("subscriber failed");
                            }
                        });
        entered.join();
        handleClosed.join();
        assertFalse(job.completion().isDone());
        release.complete(null);
        var done = job.completion().join();
        assertEquals(TableTypes.PreloadOutcome.COMPLETED, done.outcome());
        assertEquals(done.progress(), reports.getLast());
        assertTrue(reports.size() < 20);
        for (int i = 1; i < reports.size(); i++) {
            assertEquals(reports.get(i), TablePreloads.merge(reports.get(i - 1), reports.get(i)));
        }
        manager.close();
    }
}
