package com.vibeminer.concepts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class RelationIndexesTest {
    static final TableTypes.Priority PRIORITY = TableTypes.Priority.INTERACTIVE;

    static final class Source {
        final List<Db2Types.Row> rows = new ArrayList<>();
        List<Db2Types.Column> columns =
                List.of(new Db2Types.Column("Parent", "integer", false, null, null));
        volatile TableTypes.State state = TableTypes.State.READY;
        volatile String identity = "one";
        volatile int staleReads;
        volatile CompletableFuture<Void> hold;
        final CountDownLatch entered = new CountDownLatch(1);
        final List<TableTypes.Priority> priorities =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger schemaCalls = new AtomicInteger(), pageCalls = new AtomicInteger();

        TableTypes.Entry entry() {
            return new TableTypes.Entry(1, "Links", state, true, null, null, "a".repeat(32));
        }

        Source add(long id, String value) {
            rows.add(new Db2Types.Row(id, false, null, List.of(value), null));
            return this;
        }

        BuildTables tables() {
            return (BuildTables)
                    Proxy.newProxyInstance(
                            BuildTables.class.getClassLoader(),
                            new Class<?>[] {BuildTables.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("schema")) {
                                    schemaCalls.incrementAndGet();
                                    return CompletableFuture.completedFuture(
                                            new TableTypes.SchemaResult(entry(), columns, null));
                                }
                                if (method.getName().equals("page")) {
                                    var request = (TableTypes.PageRequest) args[0];
                                    var priority = (TableTypes.Priority) args[1];
                                    var token = (Cancellation) args[2];
                                    pageCalls.incrementAndGet();
                                    priorities.add(priority);
                                    entered.countDown();
                                    CompletableFuture<Void> wait = hold;
                                    CompletableFuture<TableTypes.Page> reply =
                                            new CompletableFuture<>();
                                    var registration =
                                            token.onCancel(
                                                    () ->
                                                            reply.completeExceptionally(
                                                                    new CancellationException()));
                                    Runnable produce =
                                            () -> {
                                                try {
                                                    token.check();
                                                    if (request.limit() == 1 && staleReads > 0) {
                                                        staleReads--;
                                                        identity += "x";
                                                    }
                                                    if (request.identity() != null
                                                            && !request.identity()
                                                                    .equals(identity)) {
                                                        reply.complete(
                                                                new TableTypes.Page(
                                                                        entry(),
                                                                        TableTypes.PageState.STALE,
                                                                        List.of(),
                                                                        null,
                                                                        identity,
                                                                        0,
                                                                        null,
                                                                        null));
                                                        return;
                                                    }
                                                    if (state != TableTypes.State.READY
                                                            && state != TableTypes.State.PARTIAL) {
                                                        reply.complete(
                                                                new TableTypes.Page(
                                                                        entry(),
                                                                        TableTypes.PageState
                                                                                .NOT_READABLE,
                                                                        List.of(),
                                                                        null,
                                                                        null,
                                                                        0,
                                                                        null,
                                                                        null));
                                                        return;
                                                    }
                                                    int from = (int) (request.after() + 1),
                                                            to =
                                                                    Math.min(
                                                                            rows.size(),
                                                                            from + request.limit());
                                                    List<TableTypes.Row> found = new ArrayList<>();
                                                    for (int i = from; i < to; i++) {
                                                        found.add(
                                                                new TableTypes.Row(i, rows.get(i)));
                                                    }
                                                    reply.complete(
                                                            new TableTypes.Page(
                                                                    entry(),
                                                                    TableTypes.PageState.OK,
                                                                    found,
                                                                    to < rows.size()
                                                                            ? (long) to - 1
                                                                            : null,
                                                                    identity,
                                                                    found.size(),
                                                                    null,
                                                                    null));
                                                } catch (Throwable e) {
                                                    reply.completeExceptionally(e);
                                                }
                                            };
                                    if (wait == null) {
                                        produce.run();
                                    } else {
                                        wait.whenComplete((v, e) -> produce.run());
                                    }
                                    reply.whenComplete((v, e) -> registration.close());
                                    return reply;
                                }
                                throw new AssertionError(
                                        "Unexpected lower method " + method.getName());
                            });
        }
    }

    @Test
    void unsignedMatchesOrderSkipAndInvalidCells() {
        Source source =
                new Source()
                        .add(1, "4294967295")
                        .add(2, "0")
                        .add(3, "4294967295")
                        .add(4, "-1")
                        .add(5, "01");
        source.rows.add(
                new Db2Types.Row(
                        6,
                        false,
                        null,
                        List.of("4294967295"),
                        List.of(new Db2Types.TextIssue(0, "bad", "ff"))));
        RelationIndexes index = new RelationIndexes(source.tables());
        var result =
                index.lookup("Links", "Parent", 4294967295L, 1, PRIORITY, Cancellation.NONE, 1)
                        .join();
        assertEquals(RelationIndexes.State.OK, result.state());
        assertEquals(2, result.matches());
        assertEquals(3, result.rows().getFirst().row().id());
        assertTrue(result.complete());
        assertEquals(3, index.entries());
        assertEquals(
                0,
                index.lookup("Links", "Parent", 7, 10, PRIORITY, Cancellation.NONE)
                        .join()
                        .matches());
        assertEquals(1, index.builds());
    }

    @Test
    void partialAbsenceIsNotProofAndUnreadableIsRetried() {
        Source source = new Source().add(1, "2");
        source.state = TableTypes.State.ENCRYPTED;
        RelationIndexes index = new RelationIndexes(source.tables());
        assertEquals(
                RelationIndexes.State.NOT_READABLE,
                index.lookup("Links", "Parent", 2, 10, PRIORITY, Cancellation.NONE).join().state());
        source.state = TableTypes.State.PARTIAL;
        var result = index.lookup("Links", "Parent", 3, 10, PRIORITY, Cancellation.NONE).join();
        assertEquals(RelationIndexes.State.OK, result.state());
        assertFalse(result.complete());
        assertEquals(2, index.builds());
    }

    @Test
    void budgetRefusalReleasesReservationsAndIsSticky() {
        Source source = new Source();
        for (int i = 0; i < 1001; i++) {
            source.add(i, "2");
        }
        RelationIndexes index = new RelationIndexes(source.tables(), 1000);
        assertEquals(
                RelationIndexes.State.INDEX_LIMIT,
                index.lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE).join().state());
        assertEquals(0, index.entries());
        assertEquals(
                RelationIndexes.LIMIT_CODE,
                index.lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE).join().code());
        assertEquals(1, index.builds());
    }

    @Test
    void duplicateAndRawColumnsAreUnsupported() {
        Source source = new Source();
        source.columns =
                List.of(
                        new Db2Types.Column("Parent", "integer", false, null, null),
                        new Db2Types.Column("Parent", "integer", false, null, null));
        assertEquals(
                RelationIndexes.State.UNSUPPORTED,
                new RelationIndexes(source.tables())
                        .lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE)
                        .join()
                        .state());
    }

    @Test
    void staleReadbackRebuildsOnceAndFreesOldIndex() {
        Source source = new Source().add(1, "2");
        source.staleReads = 1;
        RelationIndexes index = new RelationIndexes(source.tables());
        assertEquals(
                RelationIndexes.State.OK,
                index.lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE).join().state());
        assertEquals(2, index.builds());
        assertEquals(1, index.entries());
        Source repeated = new Source().add(1, "2");
        repeated.staleReads = 2;
        RelationIndexes twice = new RelationIndexes(repeated.tables());
        assertEquals(
                RelationIndexes.State.STALE,
                twice.lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE).join().state());
        assertEquals(0, twice.entries());
    }

    @Test
    void canceledWaiterDoesNotCancelOwner() throws Exception {
        Source source = new Source().add(1, "2");
        source.hold = new CompletableFuture<>();
        RelationIndexes index = new RelationIndexes(source.tables());
        var owner = index.lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE);
        assertTrue(source.entered.await(5, TimeUnit.SECONDS));
        Cancellation cancel = new Cancellation();
        var waiter = index.lookup("Links", "Parent", 2, 1, PRIORITY, cancel);
        cancel.cancel();
        assertThrows(CancellationException.class, waiter::join);
        assertFalse(owner.isDone());
        source.hold.complete(null);
        assertEquals(RelationIndexes.State.OK, owner.join().state());
        assertEquals(1, index.builds());
    }

    @Test
    void canceledOwnerLetsLiveWaiterRestartAndReturnsBudget() throws Exception {
        Source source = new Source().add(1, "2");
        source.hold = new CompletableFuture<>();
        RelationIndexes index = new RelationIndexes(source.tables());
        Cancellation cancel = new Cancellation();
        var owner = index.lookup("Links", "Parent", 2, 1, TableTypes.Priority.BACKGROUND, cancel);
        assertTrue(source.entered.await(5, TimeUnit.SECONDS));
        var waiter = index.lookup("Links", "Parent", 2, 1, PRIORITY, Cancellation.NONE);
        cancel.cancel();
        assertThrows(CancellationException.class, owner::join);
        source.hold.complete(null);
        assertEquals(RelationIndexes.State.OK, waiter.join().state());
        assertEquals(2, index.builds());
        assertEquals(1, index.entries());
    }
}
