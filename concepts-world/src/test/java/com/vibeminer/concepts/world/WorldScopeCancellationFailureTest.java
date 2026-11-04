package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.world.WorldFormats;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class WorldScopeCancellationFailureTest {
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", null, 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    @Test
    @SuppressWarnings("try")
    void realThrowingCancelCallbackDrainsAfterReturnedResourceRetiresAndPoisonsFutureWork()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        var scopes = new WorldStreamScopes(tables.handle(), files.files());
        var external = new Cancellation();
        var entered = new CompletableFuture<Void>();
        var returnResource = new CompletableFuture<Void>();
        var callbackEntered = new CompletableFuture<Void>();
        var resourceClosed = new CompletableFuture<Void>();
        var callbackRelease = new CompletableFuture<Void>();
        var callbackFailure = new IllegalStateException("Authored cancellation cleanup failure");
        var closeCount = new AtomicInteger();
        try {
            var scope = scopes.create(MAP, Cancellation.NONE);
            var work =
                    CompletableFuture.supplyAsync(
                            () ->
                                    scopes.withRecordedTile(
                                            scope,
                                            TILE,
                                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                            external,
                                            admission -> {
                                                try (var root =
                                                        admission.readRecorded(
                                                                WorldStreamScopes.RecordedInput
                                                                        .ROOT)) {
                                                    assertTrue(root.bytes().length > 0);
                                                }
                                                try (var registration =
                                                        admission
                                                                .cancellation()
                                                                .onCancel(
                                                                        () -> {
                                                                            callbackEntered
                                                                                    .complete(null);
                                                                            resourceClosed.join();
                                                                            callbackRelease.join();
                                                                            throw callbackFailure;
                                                                        })) {
                                                    entered.complete(null);
                                                    returnResource.join();
                                                }
                                                return (AutoCloseable)
                                                        () -> {
                                                            closeCount.incrementAndGet();
                                                            resourceClosed.complete(null);
                                                        };
                                            }));
            entered.get(5, TimeUnit.SECONDS);
            CompletableFuture.runAsync(external::cancel).get(5, TimeUnit.SECONDS);
            callbackEntered.get(5, TimeUnit.SECONDS);
            returnResource.complete(null);
            resourceClosed.get(5, TimeUnit.SECONDS);
            assertEquals(1, closeCount.get());
            assertFalse(work.isDone());
            assertTrue(working(scopes));
            int opens = files.totalOpens();
            assertThrows(
                    IllegalStateException.class,
                    () -> scopes.recorded(scope, TILE, Cancellation.NONE));
            assertEquals(opens, files.totalOpens());
            var closing = scopes.closeAsync();
            assertFalse(closing.isDone());
            callbackRelease.complete(null);
            var error = assertThrows(ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalStateException);
            assertTrue(
                    contains(
                            error,
                            callbackFailure,
                            Collections.newSetFromMap(new IdentityHashMap<>())));
            assertFalse(working(scopes));
            assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS));
            assertEquals(0, scopes.retainedProofs());
            assertFalse(scopes.isKnown(scope));
            assertThrows(IllegalStateException.class, () -> scopes.create(MAP, Cancellation.NONE));
            assertEquals(opens, files.totalOpens());
        } finally {
            returnResource.complete(null);
            callbackRelease.complete(null);
            assertThrows(
                    ExecutionException.class, () -> scopes.closeAsync().get(5, TimeUnit.SECONDS));
            tables.close();
        }
    }

    @Test
    void endFailureAfterAdmissionReturnedAggregateClosesItBeforeActiveCreditAndKeepsBothErrors()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var closeEntered = new CompletableFuture<Void>();
        var closeRelease = new CompletableFuture<Void>();
        var endFailure = new java.util.concurrent.CancellationException("Authored late end fault");
        var returnedCloseFailure =
                new IllegalStateException("Authored returned resource close failure");
        var closeCount = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scopes = engine(source);
            var scope = source.createScope(MAP, Cancellation.NONE);
            var work =
                    CompletableFuture.supplyAsync(
                            () ->
                                    source.prepareAndWrite(
                                            scope,
                                            TILE,
                                            Cancellation.NONE,
                                            (borrow, cancel) -> {
                                                // Negative fault injection only: no source
                                                // proof/positive factory is forged.
                                                failEnd(scopes, endFailure);
                                                return (AutoCloseable)
                                                        () -> {
                                                            closeCount.incrementAndGet();
                                                            closeEntered.complete(null);
                                                            closeRelease.join();
                                                            throw returnedCloseFailure;
                                                        };
                                            }));
            closeEntered.get(5, TimeUnit.SECONDS);
            assertTrue(working(scopes), "Unreturned aggregate still owns source-active credit");
            assertFalse(work.isDone());
            int opens = files.totalOpens();
            assertThrows(
                    IllegalStateException.class, () -> source.createScope(MAP, Cancellation.NONE));
            assertEquals(opens, files.totalOpens());
            closeRelease.complete(null);
            var error = assertThrows(ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
            assertTrue(
                    error.getCause() instanceof IllegalStateException,
                    "Late cancellation-shaped cleanup failure is fatal, not a clean cancellation");
            assertTrue(
                    contains(
                            error, endFailure, Collections.newSetFromMap(new IdentityHashMap<>())));
            assertTrue(
                    contains(
                            error,
                            returnedCloseFailure,
                            Collections.newSetFromMap(new IdentityHashMap<>())));
            assertEquals(1, closeCount.get());
            assertFalse(working(scopes));
            assertEquals(0, scopes.retainedProofs());
            assertEquals(0, pool.stats().leaseClaims());
            assertThrows(
                    ExecutionException.class, () -> world.closeAsync().get(5, TimeUnit.SECONDS));
        } finally {
            closeRelease.complete(null);
            assertThrows(
                    ExecutionException.class, () -> world.closeAsync().get(5, TimeUnit.SECONDS));
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void scopeCloseAfterCancellationSealRetiresReturnedResourceBeforeHandoff() throws Exception {
        lastGateRejects(true);
    }

    @Test
    void externalCancelAfterRegistrationRemovalRetiresReturnedResourceBeforeHandoff()
            throws Exception {
        lastGateRejects(false);
    }

    private static void lastGateRejects(boolean closeScope) throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        var scopes = new WorldStreamScopes(tables.handle(), files.files());
        var external = new Cancellation();
        var sealed = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var closes = new AtomicInteger();
        try {
            var scope = scopes.create(MAP, Cancellation.NONE);
            var work =
                    CompletableFuture.supplyAsync(
                            () ->
                                    scopes.withRecordedTile(
                                            scope,
                                            TILE,
                                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                            external,
                                            admission -> {
                                                holdSeal(scopes, sealed, release);
                                                return (AutoCloseable) closes::incrementAndGet;
                                            }));
            sealed.get(5, TimeUnit.SECONDS);
            assertTrue(working(scopes));
            if (closeScope) {
                assertFalse(scopes.closeAsync().isDone());
            } else {
                external.cancel();
            }
            release.complete(null);
            assertThrows(ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            assertFalse(working(scopes));
        } finally {
            release.complete(null);
            scopes.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void holdSeal(
            WorldStreamScopes scopes,
            CompletableFuture<Void> entered,
            CompletableFuture<Void> release) {
        try {
            var lane = WorldStreamScopes.class.getDeclaredField("cancelLane");
            lane.setAccessible(true);
            var value = lane.get(scopes);
            var done = value.getClass().getDeclaredField("done");
            done.setAccessible(true);
            ((CompletableFuture<Void>) done.get(value))
                    .thenRun(
                            () -> {
                                entered.complete(null);
                                release.join();
                            });
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static WorldStreamScopes engine(WorldPreparedTerrain source) {
        try {
            var field = WorldPreparedTerrain.class.getDeclaredField("scopes");
            field.setAccessible(true);
            return (WorldStreamScopes) field.get(source);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean working(WorldStreamScopes scopes) {
        try {
            var field = WorldStreamScopes.class.getDeclaredField("working");
            field.setAccessible(true);
            return ((AtomicBoolean) field.get(scopes)).get();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static void failEnd(WorldStreamScopes scopes, Throwable failure) {
        try {
            var lane = WorldStreamScopes.class.getDeclaredField("cancelLane");
            lane.setAccessible(true);
            var value = lane.get(scopes);
            var done = value.getClass().getDeclaredField("done");
            done.setAccessible(true);
            ((CompletableFuture<Void>) done.get(value)).completeExceptionally(failure);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean contains(Throwable current, Throwable expected, Set<Throwable> visited) {
        if (current == expected) {
            return true;
        }
        if (current == null || !visited.add(current)) {
            return false;
        }
        if (contains(current.getCause(), expected, visited)) {
            return true;
        }
        for (var suppressed : current.getSuppressed()) {
            if (contains(suppressed, expected, visited)) {
                return true;
            }
        }
        return false;
    }
}
