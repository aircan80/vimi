package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
final class SqliteCancellationTest {
    private static final String LONG_QUERY =
            "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n";
    private static final String SHORT_QUERY =
            "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10000) SELECT sum(x) FROM n";
    @TempDir Path folder;

    private static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);

        void pause() {
            if (!first.getAndSet(false)) {
                return;
            }
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }

        void cancel(Cancellation token) throws Exception {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            token.cancel();
            release.countDown();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static <T> CompletableFuture<T> run(Async.Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                result.complete(work.run());
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        });
        return result;
    }

    private static void canceled(CompletableFuture<?> result) {
        assertInstanceOf(
                CancellationException.class,
                assertThrows(
                                CompletionException.class,
                                () ->
                                        result.handle(
                                                        (value, error) -> {
                                                            if (error != null) {
                                                                throw new CompletionException(
                                                                        error);
                                                            }
                                                            return value;
                                                        })
                                                .join())
                        .getCause());
    }

    @Test
    void activeStatementCancelsAndConnectionCanRunAnotherLongStatement() throws Exception {
        Cancellation token = new Cancellation();
        try (Gate gate = new Gate()) {
            var result =
                    run(
                            () -> {
                                try (NativeSqlite db = new NativeSqlite(":memory:", false)) {
                                    db.progressProbe = gate::pause;
                                    assertThrows(
                                            CancellationException.class,
                                            () ->
                                                    db.withCancellation(
                                                            token, () -> db.scalar(LONG_QUERY)));
                                    db.progressProbe = null;
                                    return db.withCancellation(
                                            new Cancellation(), () -> db.scalar(SHORT_QUERY));
                                }
                            });
            gate.cancel(token);
            assertEquals(50005000L, result.get(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedTransactionsRollBackAndReleaseResources(boolean writeStatement)
            throws Exception {
        Cancellation token = new Cancellation();
        try (Gate gate = new Gate()) {
            var result =
                    run(
                            () -> {
                                try (NativeSqlite db =
                                        new NativeSqlite(folder.resolve("test.db"), false)) {
                                    db.exec("CREATE TABLE values_for_test(x INTEGER)");
                                    db.progressProbe = gate::pause;
                                    assertThrows(
                                            CancellationException.class,
                                            () ->
                                                    db.withCancellation(
                                                            token,
                                                            () -> {
                                                                db.transaction(
                                                                        true,
                                                                        () -> {
                                                                            db.exec(
                                                                                    "INSERT INTO values_for_test VALUES(7)");
                                                                            if (writeStatement) {
                                                                                db.exec(
                                                                                        "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) INSERT INTO values_for_test SELECT x FROM n");
                                                                            } else {
                                                                                db.query(
                                                                                        LONG_QUERY);
                                                                            }
                                                                        });
                                                                return null;
                                                            }));
                                    assertFalse(db.inTransaction());
                                    assertEquals(
                                            0, db.scalar("SELECT count(*) FROM values_for_test"));
                                    db.transaction(
                                            true,
                                            () -> db.exec("INSERT INTO values_for_test VALUES(9)"));
                                    return db.scalar("SELECT sum(x) FROM values_for_test");
                                }
                            });
            gate.cancel(token);
            assertEquals(9, result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void callbackFailuresReturnToJavaWithoutUnwindingAcrossNativeCode() throws Exception {
        try (NativeSqlite db = new NativeSqlite(":memory:", false)) {
            db.progressProbe =
                    () -> {
                        throw new IllegalStateException("probe");
                    };
            assertThrows(
                    IllegalStateException.class,
                    () -> db.withCancellation(new Cancellation(), () -> db.scalar(LONG_QUERY)));
            db.progressProbe = null;
            assertEquals(
                    50005000L,
                    db.withCancellation(new Cancellation(), () -> db.scalar(SHORT_QUERY)));
        }
    }

    @Test
    void publicPutCancellationRollsBackPackAndAllowsRetry() throws Exception {
        ObjectStore store = ObjectStore.open(folder).join();
        Cancellation token = new Cancellation();
        try (Gate gate = new Gate()) {
            AtomicBoolean inject = new AtomicBoolean(true);
            store.database.progressProbe = gate::pause;
            store.database.statementProbe =
                    sql -> {
                        if (sql.startsWith("INSERT INTO objects") && inject.getAndSet(false)) {
                            store.database.query(LONG_QUERY);
                        }
                    };
            byte[] bytes = {1, 2, 3};
            var result = store.put(List.of(new PutObject(key(bytes), bytes)), true, token);
            gate.cancel(token);
            canceled(result);
            store.database.statementProbe = null;
            store.database.progressProbe = null;
            assertFalse(store.has(key(bytes)).join());
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key(bytes), bytes)))
                            .join()
                            .getFirst()
                            .outcome());
            assertTrue(store.verify(true, Cancellation.NONE).join().corrupt().isEmpty());
        } finally {
            store.database.statementProbe = null;
            store.database.progressProbe = null;
            store.closeAsync().join();
        }
    }

    @Test
    void publicScanCancellationRestoresIndexAndAllowsRetry() throws Exception {
        CrashFixture.prepare(folder);
        ObjectStore store = ObjectStore.open(folder).join();
        Cancellation token = new Cancellation();
        try (Gate gate = new Gate()) {
            store.database.progressProbe = gate::pause;
            store.maintenanceProbe =
                    point -> {
                        if (point == MaintenancePoint.SCAN_CLEARED) {
                            store.database.query(LONG_QUERY);
                        }
                    };
            var result = store.scan(token);
            gate.cancel(token);
            canceled(result);
            store.maintenanceProbe = null;
            store.database.progressProbe = null;
            assertTrue(store.has(key(CrashFixture.baseline())).join());
            store.scan(Cancellation.NONE).join();
            assertTrue(store.verify(true, Cancellation.NONE).join().corrupt().isEmpty());
        } finally {
            store.maintenanceProbe = null;
            store.database.progressProbe = null;
            store.closeAsync().join();
        }
    }

    @Test
    void cancellationAfterScopeOrConnectionDisposalCannotAffectNextConnection() throws Exception {
        for (int i = 0; i < 30; i++) {
            Cancellation old = new Cancellation();
            try (NativeSqlite db = new NativeSqlite(":memory:", false)) {
                assertEquals(50005000L, db.withCancellation(old, () -> db.scalar(SHORT_QUERY)));
                var cancel =
                        run(
                                () -> {
                                    old.cancel();
                                    return null;
                                });
                assertEquals(
                        50005000L,
                        db.withCancellation(new Cancellation(), () -> db.scalar(SHORT_QUERY)));
                cancel.get(5, TimeUnit.SECONDS);
            }
            old.cancel();
        }
    }

    @Test
    void cancelingOneReaderDoesNotInterruptAnotherAndCloseWaitsForDisposal() throws Exception {
        CrashFixture.prepare(folder);
        ObjectStore store = ObjectStore.open(folder).join();
        Cancellation token = new Cancellation();
        try (Gate gate = new Gate()) {
            var reading =
                    store.reading(
                            token,
                            index -> {
                                index.db.progressProbe = gate::pause;
                                return index.db.scalar(LONG_QUERY);
                            });
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            assertTrue(store.has(key(CrashFixture.baseline())).get(5, TimeUnit.SECONDS));
            var closed = store.closeAsync();
            assertFalse(closed.isDone());
            gate.cancel(token);
            canceled(reading);
            closed.get(5, TimeUnit.SECONDS);
            token.cancel();
        } finally {
            store.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void lateCancellationDoesNotTurnDurablePutIntoFailure() {
        ObjectStore store = ObjectStore.open(folder).join();
        Cancellation token = new Cancellation();
        byte[] bytes = {4, 5, 6};
        try {
            store.database.afterStatementProbe =
                    sql -> {
                        if (sql.equals("COMMIT")) {
                            token.cancel();
                        }
                    };
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key(bytes), bytes)), true, token)
                            .join()
                            .getFirst()
                            .outcome());
            store.database.afterStatementProbe = null;
            assertTrue(token.isCanceled());
            assertTrue(store.has(key(bytes)).join());
            assertTrue(store.verify(true, Cancellation.NONE).join().corrupt().isEmpty());
        } finally {
            store.database.afterStatementProbe = null;
            store.closeAsync().join();
        }
    }
}
