package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PreparedTablesCacheTest {
    private static final TableTypes.Entry READABLE =
            new TableTypes.Entry(
                    10,
                    "Item",
                    TableTypes.State.READY,
                    true,
                    null,
                    new TableTypes.Counts(2, 2, 0, 0, "WDC5", "ABCDEF01"),
                    "content");

    private static TableTypes.PreloadResult result() {
        var encrypted =
                new TableTypes.Entry(
                        20,
                        "Protected",
                        TableTypes.State.ENCRYPTED,
                        true,
                        new TableTypes.Reason(
                                "PROTECTED",
                                "Needs key",
                                Instant.EPOCH,
                                null,
                                null,
                                List.of("0123456789ABCDEF")),
                        null,
                        "other");
        var absent =
                new TableTypes.Entry(
                        30, "Absent", TableTypes.State.NOT_IN_BUILD, true, null, null, null);
        return new TableTypes.PreloadResult(
                TableTypes.PreloadOutcome.COMPLETED,
                new TableTypes.PreloadProgress(
                        TableTypes.PreloadStage.PREPARING, 0, 0, 0, 0, 0, 0, 2, 2, 1),
                List.of(READABLE, encrypted, absent),
                com.vibeminer.files.FileTypes.FilesOpenState.OPEN,
                null,
                null);
    }

    private static BuildTables local(TableTypes.Entry readable, AtomicInteger checks) {
        return (BuildTables)
                Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("check")) {
                                checks.incrementAndGet();
                                return CompletableFuture.completedFuture(readable);
                            }
                            throw new AssertionError(
                                    "Restore attempted full preparation/acquisition: "
                                            + method.getName());
                        });
    }

    @Test
    void fastRestoreReadsOnlyInventoryAndPreservesAllOutcomes() {
        var store = new MemoryGeneratedStore();
        new PreparedTablesCache(store).save("v1", result(), Cancellation.NONE).join();
        var reads = new AtomicInteger();
        var inventoryOnly =
                (GeneratedStore)
                        Proxy.newProxyInstance(
                                GeneratedStore.class.getClassLoader(),
                                new Class<?>[] {GeneratedStore.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("read") && args.length == 2) {
                                        assertEquals(PreparedTablesCache.key("v1"), args[0]);
                                        reads.incrementAndGet();
                                        return store.read(
                                                (com.vibeminer.generated.GeneratedKey) args[0],
                                                (com.vibeminer.generated.Cancellation) args[1]);
                                    }
                                    throw new AssertionError(
                                            "Reopen touched table artifacts: " + method.getName());
                                });
        var updates = new java.util.ArrayList<TableTypes.PreloadProgress>();
        var restored =
                new PreparedTablesCache(inventoryOnly)
                        .restore("v1", updates::add, Cancellation.NONE)
                        .join();
        assertNotNull(restored);
        assertEquals(result().tables(), restored.tables());
        assertEquals(1, reads.get());
        assertEquals(1, restored.progress().tablesFailed());
        assertEquals(2, updates.size());
        assertEquals(0, updates.getFirst().tablesDone());
        assertEquals(2, updates.getLast().tablesDone());
    }

    @Test
    void fastRestoreRejectsChangedIdentityCorruptionAndCancellation() {
        var store = new MemoryGeneratedStore();
        var cache = new PreparedTablesCache(store);
        cache.save("v1", result(), Cancellation.NONE).join();
        assertNull(cache.restore("v2", null, Cancellation.NONE).join());
        var token = new Cancellation();
        token.cancel();
        assertThrows(CompletionException.class, () -> cache.restore("v1", null, token).join());
        store.damage(PreparedTablesCache.key("v1"));
        assertNull(cache.restore("v1", null, Cancellation.NONE).join());
    }

    @Test
    void restoresCompletedLedgerAndVerifiesOnlyReadableLocalArtifacts() {
        var store = new MemoryGeneratedStore();
        var cache = new PreparedTablesCache(store);
        var checks = new AtomicInteger();
        cache.save("build-locale-definitions-keys-v1", result(), Cancellation.NONE).join();
        var updates = new java.util.ArrayList<TableTypes.PreloadProgress>();
        var restored =
                new PreparedTablesCache(store)
                        .restore(
                                "build-locale-definitions-keys-v1",
                                local(READABLE, checks),
                                updates::add,
                                Cancellation.NONE)
                        .join();
        assertNotNull(restored);
        assertEquals(result().tables(), restored.tables());
        assertEquals(1, checks.get());
        assertEquals("TABLES_RESTORED", restored.code());
        assertEquals(2, restored.progress().tablesTotal());
        assertEquals(TableTypes.PreloadStage.RESTORING, restored.progress().stage());
        assertEquals(0, updates.getFirst().tablesDone());
        assertEquals(2, updates.getLast().tablesDone());
        assertEquals(
                true,
                updates.stream()
                        .allMatch(update -> update.stage() == TableTypes.PreloadStage.RESTORING));
        assertNull(
                cache.restore(
                                "changed-definitions-or-keys",
                                local(READABLE, checks),
                                null,
                                Cancellation.NONE)
                        .join());
        assertEquals(1, checks.get());
    }

    @Test
    void missingReadableGraphInvalidatesInventoryRatherThanClaimingReady() {
        var cache = new PreparedTablesCache(new MemoryGeneratedStore());
        cache.save("v1", result(), Cancellation.NONE).join();
        var missing =
                new TableTypes.Entry(
                        10, "Item", TableTypes.State.NOT_STORED, true, null, null, "content");
        assertNull(
                cache.restore("v1", local(missing, new AtomicInteger()), null, Cancellation.NONE)
                        .join());
    }

    @Test
    void cancellationOrFailedPassNeverReplacesCompletedInventory() {
        var store = new MemoryGeneratedStore();
        var cache = new PreparedTablesCache(store);
        cache.save("v1", result(), Cancellation.NONE).join();
        var token = new Cancellation();
        token.cancel();
        assertThrows(CompletionException.class, () -> cache.save("v1", result(), token).join());
        var failed =
                new TableTypes.PreloadResult(
                        TableTypes.PreloadOutcome.FAILED,
                        result().progress(),
                        result().tables(),
                        null,
                        null,
                        null);
        assertThrows(
                CompletionException.class,
                () -> cache.save("v1", failed, Cancellation.NONE).join());
        assertNotNull(
                cache.restore("v1", local(READABLE, new AtomicInteger()), null, Cancellation.NONE)
                        .join());
        store.damage(PreparedTablesCache.key("v1"));
        assertNull(
                cache.restore("v1", local(READABLE, new AtomicInteger()), null, Cancellation.NONE)
                        .join());
    }
}
