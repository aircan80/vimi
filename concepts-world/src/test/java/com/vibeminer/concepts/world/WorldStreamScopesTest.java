package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class WorldStreamScopesTest {
    private static final WorldService.MapInfo REQUEST =
            new WorldService.MapInfo(7, "Forged", "Other", 0, 999L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    @Test
    void combinedTerrainFinalFailureDrainsReturnedResourceBeforeNextAdmission() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var cleanupEntered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var retired = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            var writing =
                    CompletableFuture.supplyAsync(
                            () ->
                                    source.prepareAndWrite(
                                            scope,
                                            TILE,
                                            Cancellation.NONE,
                                            (borrow, cancel) -> {
                                                files.changedId = 3;
                                                files.changeAfterRead = true;
                                                return (AutoCloseable)
                                                        () -> {
                                                            retired.incrementAndGet();
                                                            cleanupEntered.complete(null);
                                                            release.join();
                                                        };
                                            }));
            cleanupEntered.get(5, TimeUnit.SECONDS);
            int opens = files.totalOpens();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.prepareAndWrite(
                                    scope,
                                    TILE,
                                    Cancellation.NONE,
                                    (borrow, cancel) -> (AutoCloseable) () -> {}));
            assertEquals(opens, files.totalOpens());
            assertEquals(1, retired.get());
            release.complete(null);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> writing.get(5, TimeUnit.SECONDS));
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            release.complete(null);
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainPartialTextureCoverageRemainsExplicit() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        files.sources.remove(11L);
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            try (var output =
                    source.prepareAndWrite(
                            scope,
                            TILE,
                            Cancellation.NONE,
                            (borrow, cancel) -> {
                                assertEquals(
                                        WorldPreparedTerrain.Outcome.TEXTURED, borrow.outcome());
                                assertEquals(
                                        WorldTerrainTextureSource.State.PARTIAL,
                                        borrow.materialState());
                                assertEquals(128, borrow.supportedChunks());
                                return (AutoCloseable) () -> {};
                            })) {
                output.withPayload(borrow -> assertTrue(borrow.texture() != null));
            }
        } finally {
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainCleanupFailureStillReleasesBaseAndFailsLaterSourceDrain() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var retired = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            var output =
                    source.prepareAndWrite(
                            scope,
                            TILE,
                            Cancellation.NONE,
                            (borrow, cancel) ->
                                    (AutoCloseable)
                                            () -> {
                                                retired.incrementAndGet();
                                                throw new IllegalStateException(
                                                        "Authored packet cleanup failure");
                                            });
            assertThrows(IllegalStateException.class, output::close);
            assertEquals(1, retired.get());
            assertEquals(0, pool.stats().leaseClaims());
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> world.closeAsync().get(5, TimeUnit.SECONDS));
            output.close();
            assertEquals(1, retired.get());
        } finally {
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> world.closeAsync().get(5, TimeUnit.SECONDS));
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainWriterCloseRaceRetiresReturnedResourceBeforeTransfer() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var retired = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            var writing =
                    CompletableFuture.supplyAsync(
                            () ->
                                    source.prepareAndWrite(
                                            scope,
                                            TILE,
                                            Cancellation.NONE,
                                            (borrow, cancel) -> {
                                                entered.complete(null);
                                                release.join();
                                                assertTrue(
                                                        borrow.tile().vertices().remaining() > 0);
                                                return (AutoCloseable) retired::incrementAndGet;
                                            }));
            entered.get(5, TimeUnit.SECONDS);
            var closing = world.closeAsync();
            assertFalse(closing.isDone());
            assertThrows(
                    IllegalStateException.class,
                    () -> source.createScope(REQUEST, Cancellation.NONE));
            release.complete(null);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> writing.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, retired.get());
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            release.complete(null);
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainUnknownFailureNeverWritesHeightAndUnreturnedWriterResourceIsLocal()
            throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation cancel) {
                        if (id == 10 && fail.get()) {
                            throw new IllegalStateException("Authored provider bug");
                        }
                        return super.stat(id, cancel);
                    }
                };
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var written = new AtomicInteger();
        var retired = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            source.prepareAndWrite(
                                    scope,
                                    TILE,
                                    Cancellation.NONE,
                                    (borrow, cancel) -> {
                                        written.incrementAndGet();
                                        return (AutoCloseable) retired::incrementAndGet;
                                    }));
            assertEquals(0, written.get());
            fail.set(false);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.prepareAndWrite(
                                    scope,
                                    TILE,
                                    Cancellation.NONE,
                                    (borrow, cancel) -> {
                                        AutoCloseable scratch = retired::incrementAndGet;
                                        try {
                                            throw new IllegalStateException("Authored writer bug");
                                        } finally {
                                            try {
                                                scratch.close();
                                            } catch (Exception error) {
                                                throw new AssertionError(error);
                                            }
                                        }
                                    }));
            assertEquals(1, retired.get());
            try (var recovered =
                    source.prepareAndWrite(
                            scope,
                            TILE,
                            Cancellation.NONE,
                            (borrow, cancel) -> (AutoCloseable) retired::incrementAndGet)) {
                recovered.withPayload(
                        borrow ->
                                assertEquals(
                                        WorldPreparedTerrain.Outcome.TEXTURED, borrow.outcome()));
            }
            assertEquals(2, retired.get());
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainFactoryOwnsOneBasePerTileAndExpiresWriterBorrows() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var closed = new AtomicInteger();
        var escaped =
                new java.util.concurrent.atomic.AtomicReference<WorldPreparedTerrain.Borrow>();
        var owners = new ArrayList<WorldPreparedTerrain.WrittenTile<AutoCloseable>>();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            var identities = new ArrayList<Integer>();
            for (int i = 0; i < 12; i++) {
                owners.add(
                        source.prepareAndWrite(
                                scope,
                                TILE,
                                Cancellation.NONE,
                                (borrow, cancel) -> {
                                    assertEquals(
                                            WorldPreparedTerrain.Outcome.TEXTURED,
                                            borrow.outcome());
                                    assertEquals(
                                            WorldPreparedTerrain.Fallback.NONE, borrow.fallback());
                                    assertTrue(borrow.texture() != null);
                                    assertEquals(
                                            borrow.root().contentKey(),
                                            borrow.material().root().contentKey());
                                    identities.add(System.identityHashCode(borrow.tile()));
                                    escaped.set(borrow);
                                    return (AutoCloseable) closed::incrementAndGet;
                                }));
                assertThrows(IllegalStateException.class, () -> escaped.get().tile());
            }
            assertEquals(1, identities.stream().distinct().count());
            assertEquals(12, pool.stats().leaseClaims());
            int opens = files.totalOpens();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.prepareAndWrite(
                                    scope, TILE, Cancellation.NONE, (borrow, cancel) -> () -> {}));
            assertEquals(opens, files.totalOpens(), "No I/O before output credit admission");
            for (var owner : owners) {
                owner.close();
                owner.close();
            }
            assertEquals(12, closed.get());
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            for (var owner : owners) {
                owner.close();
            }
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainTerminalHeightRemainsCheckedAndFinalChangesRetireWrittenOutput()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        files.sources.remove(3L);
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var retired = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            try (var output =
                    source.prepareAndWrite(
                            scope,
                            TILE,
                            Cancellation.NONE,
                            (borrow, cancel) -> {
                                assertEquals(
                                        WorldPreparedTerrain.Outcome.HEIGHT_FALLBACK,
                                        borrow.outcome());
                                assertEquals(
                                        WorldPreparedTerrain.Fallback.QUALIFIED_SOURCE_TERMINAL,
                                        borrow.fallback());
                                assertTrue(borrow.root().length() > 0);
                                assertTrue(borrow.texture() == null);
                                return (AutoCloseable) retired::incrementAndGet;
                            })) {
                assertTrue(output.written() != null);
            }
            assertEquals(1, retired.get());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.prepareAndWrite(
                                    scope,
                                    TILE,
                                    Cancellation.NONE,
                                    (borrow, cancel) -> {
                                        files.sources.put(3L, new byte[16]);
                                        return (AutoCloseable) retired::incrementAndGet;
                                    }));
            assertEquals(2, retired.get());
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void combinedTerrainSourceCloseDrainsAnAlreadyAcceptedPayloadBorrow() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var pool = new TerrainAtlasPool();
        var world = new WorldService(tables.handle(), files.files(), pool);
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var retired = new AtomicInteger();
        try {
            var source = world.preparedTerrain();
            var scope = source.createScope(REQUEST, Cancellation.NONE);
            var output =
                    source.prepareAndWrite(
                            scope,
                            TILE,
                            Cancellation.NONE,
                            (borrow, cancel) -> (AutoCloseable) retired::incrementAndGet);
            var read =
                    CompletableFuture.runAsync(
                            () ->
                                    output.withPayload(
                                            borrow -> {
                                                entered.complete(null);
                                                release.join();
                                                assertTrue(
                                                        borrow.tile().vertices().remaining() > 0);
                                                assertTrue(
                                                        borrow.texture().pixels().remaining() > 0);
                                                assertTrue(borrow.areas() != null);
                                            }));
            entered.get(5, TimeUnit.SECONDS);
            var closing = world.closeAsync();
            assertFalse(closing.isDone());
            assertEquals(0, retired.get());
            assertThrows(IllegalStateException.class, () -> output.withPayload(borrow -> {}));
            release.complete(null);
            read.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, retired.get());
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            release.complete(null);
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }

    @Test
    void finalMaterialObservationsRejectChangedFailedInputsAndNewlyAvailableNegativeLookups()
            throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            if (mode == 0) {
                files.sources.put(3L, new byte[16]);
            } else if (mode == 1) {
                files.sources.put(10L, new byte[16]);
            } else if (mode == 2) {
                files.sources.remove(3L);
            } else {
                files.wrongId = 3;
            }
            var tables = new Tables(files);
            var scopes = new WorldStreamScopes(tables.handle(), files.files());
            var pool = new TerrainAtlasPool();
            var helper = new WorldTerrainTextureSource(files.files(), pool);
            int selected = mode;
            var retired = new AtomicInteger();
            try {
                var scope = scopes.create(REQUEST, Cancellation.NONE);
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                scopes.withRecordedTile(
                                        scope,
                                        TILE,
                                        WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                        Cancellation.NONE,
                                        admission -> {
                                            var operation =
                                                    helper.readOwned(
                                                            new WorldService.MapInfo(
                                                                    7, "Authored", null, 0, 1L),
                                                            List.of(TILE),
                                                            admission.cancellation(),
                                                            p -> {});
                                            try (var result = operation.result().join()) {
                                                assertEquals(
                                                        WorldTerrainTextureSource.State.UNAVAILABLE,
                                                        result.tiles().getFirst().state());
                                            } finally {
                                                operation.awaitDrained();
                                            }
                                            admission.observeMaterial(operation);
                                            if (selected < 2) {
                                                files.changedId = selected == 0 ? 3 : 10;
                                                files.changeAfterRead = true;
                                            } else if (selected == 2) {
                                                files.sources.put(3L, new byte[16]);
                                            } else {
                                                files.wrongId = 0;
                                            }
                                            return retired::incrementAndGet;
                                        }));
                assertEquals(1, retired.get(), "Returned unadvertised output retired");
            } finally {
                scopes.closeAsync().get(5, TimeUnit.SECONDS);
                helper.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
                tables.close();
            }
        }
    }

    @Test
    void ownedMaterialDrainKeepsAdmissionBusyAfterResultFailsAndBeforeLowerCallbacksRetire()
            throws Exception {
        var entered = new CountDownLatch(1);
        var lowerEntered = new CountDownLatch(1);
        var releaseRead = new CompletableFuture<Void>();
        var releaseLower = new CompletableFuture<Void>();
        var attached = new java.util.concurrent.atomic.AtomicBoolean();
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 10 && attached.compareAndSet(false, true)) {
                            token.onCancel(
                                    () -> {
                                        lowerEntered.countDown();
                                        releaseLower.join();
                                    });
                            entered.countDown();
                            releaseRead.join();
                            token.check();
                        }
                        return super.stat(id, token);
                    }
                };
        var tables = new Tables(files);
        var scopes = new WorldStreamScopes(tables.handle(), files.files());
        var pool = new TerrainAtlasPool();
        var helper = new WorldTerrainTextureSource(files.files(), pool);
        var external = new Cancellation();
        var operation =
                new java.util.concurrent.atomic.AtomicReference<
                        WorldTerrainTextureSource.Operation>();
        try {
            var scope = scopes.create(REQUEST, Cancellation.NONE);
            var work =
                    CompletableFuture.supplyAsync(
                            () ->
                                    scopes.withRecordedTile(
                                            scope,
                                            TILE,
                                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                            external,
                                            admission -> {
                                                var actual =
                                                        helper.readOwned(
                                                                new WorldService.MapInfo(
                                                                        7,
                                                                        "Authored",
                                                                        "Authored",
                                                                        0,
                                                                        1L),
                                                                List.of(TILE),
                                                                admission.cancellation(),
                                                                p -> {});
                                                operation.set(actual);
                                                try (var batch = actual.result().join()) {
                                                    assertTrue(batch.tiles().size() == 1);
                                                    return (AutoCloseable) () -> {};
                                                } finally {
                                                    actual.awaitDrained();
                                                }
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            external.cancel();
            assertTrue(lowerEntered.await(5, TimeUnit.SECONDS));
            releaseRead.complete(null);
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> operation.get().result().get(5, TimeUnit.SECONDS));
            assertFalse(operation.get().isDrained());
            assertFalse(work.isDone());
            int rootReads = files.opens(2);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            scopes.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        admission.readRecorded(
                                                WorldStreamScopes.RecordedInput.ROOT);
                                        return (AutoCloseable) () -> {};
                                    }));
            assertEquals(
                    rootReads,
                    files.opens(2),
                    "Next geometry cannot read before actual helper drain");
            releaseLower.complete(null);
            assertThrows(java.util.concurrent.CompletionException.class, work::join);
            assertTrue(operation.get().isDrained());
        } finally {
            releaseRead.complete(null);
            releaseLower.complete(null);
            scopes.closeAsync().join();
            helper.closeAsync().join();
            pool.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void admissionMetadataIsDetachedActualNamespaceAndWdtReadObservation() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var snapshot = new WorldStreamScopes.AdmissionMetadata[1];
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            AutoCloseable result =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.BASIC_WMO,
                            Cancellation.NONE,
                            admission -> {
                                snapshot[0] = admission.metadata();
                                return (AutoCloseable) () -> {};
                            });
            result.close();
            source.closeAsync().join();
            var metadata = snapshot[0];
            assertEquals(files.files().build(), metadata.build());
            assertEquals("enUS", metadata.locale());
            assertNotEquals(0, metadata.owner());
            assertEquals(1, metadata.generation());
            assertEquals(7, metadata.mapId());
            assertEquals(scope.digest(), metadata.scopeDigest());
            assertEquals(1, metadata.wdtId());
            assertEquals(scope.wdtKey(), metadata.wdtKey());
            assertEquals(files.sources.get(1L).length, metadata.wdtLength());
            assertEquals((long) files.sources.get(1L).length, metadata.wdtDeclaredLength());
            assertEquals(1 << 20, metadata.wdtSourceLimit());
            for (var field : metadata.getClass().getDeclaredFields()) {
                assertTrue(Modifier.isFinal(field.getModifiers()));
                assertFalse(
                        field.getType() == WorldStreamScopes.class
                                || field.getType() == WorldStreamScopes.Scope.class
                                || field.getType() == BuildTables.class
                                || field.getType() == com.vibeminer.files.BuildFiles.class);
            }
            for (var constructor : metadata.getClass().getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void worldServiceCloseReturnsBeforeHeldLowerCancellationAndRejectsNewWork() throws Exception {
        var entered = new CountDownLatch(1);
        var lowerEntered = new CountDownLatch(1);
        var releaseRead = new CompletableFuture<Void>();
        var releaseLower = new CompletableFuture<Void>();
        var attached = new java.util.concurrent.atomic.AtomicBoolean();
        var lowerCalls = new AtomicInteger();
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 2 && attached.compareAndSet(false, true)) {
                            token.onCancel(
                                    () -> {
                                        lowerCalls.incrementAndGet();
                                        lowerEntered.countDown();
                                        releaseLower.join();
                                    });
                            entered.countDown();
                            releaseRead.join();
                        }
                        return super.stat(id, token);
                    }
                };
        var tables = new Tables(files);
        var service = new WorldService(tables.handle(), files.files());
        try {
            var tile = service.tile(REQUEST, TILE, Cancellation.NONE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var close = CompletableFuture.supplyAsync(service::closeAsync).get(1, TimeUnit.SECONDS);
            assertTrue(lowerEntered.await(5, TimeUnit.SECONDS));
            assertFalse(close.isDone());
            var rejected =
                    CompletableFuture.supplyAsync(() -> service.catalog(Cancellation.NONE))
                            .get(1, TimeUnit.SECONDS);
            assertTrue(rejected.isCompletedExceptionally());
            assertTrue(service.minimap(TILE, Cancellation.NONE).isCompletedExceptionally());
            var repeated = service.closeAsync();
            assertFalse(repeated.isDone());
            releaseRead.complete(null);
            assertThrows(java.util.concurrent.CompletionException.class, tile::join);
            assertFalse(close.isDone());
            releaseLower.complete(null);
            close.join();
            repeated.join();
            assertEquals(1, lowerCalls.get());
            assertEquals(0, files.borrowedClosed.get());
        } finally {
            releaseRead.complete(null);
            releaseLower.complete(null);
            service.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void wmoRecordedRootUsesTwoMiBCeilingBeforeOpenAndRejectsUnusedTex0() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            files.sources.put(2L, new byte[(2 << 20) + 1]);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.BASIC_WMO,
                                    Cancellation.NONE,
                                    admission -> {
                                        admission.readRecorded(
                                                WorldStreamScopes.RecordedInput.ROOT);
                                        throw new AssertionError("Oversized WMO root accepted");
                                    }));
            assertEquals(0, files.opens(2));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.BASIC_WMO,
                                    Cancellation.NONE,
                                    admission -> {
                                        admission.readRecorded(
                                                WorldStreamScopes.RecordedInput.TEX0);
                                        throw new AssertionError("Unused WMO tex0 accepted");
                                    }));
            assertEquals(0, files.opens(3));
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void unchangedRootWithNewScopeCompanionsRebindsMetadataWithoutReread() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var cache = new WorldGeometryCache();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var first =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            var wdt = files.sources.get(1L).clone();
            java.nio.ByteBuffer.wrap(wdt)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(12 + 40 + 8 + 4096 * 8 + 8 + (7 * 64 + 19) * 32 + 12, 4);
            files.sources.put(1L, wdt);
            files.changedId = 1;
            files.changeAfterRead = true;
            var nextScope = source.create(REQUEST, Cancellation.NONE);
            var tuple = new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 4, 0, 0);
            var next =
                    source.withRecordedTile(
                            nextScope,
                            tuple,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            assertEquals(1, files.opens(2));
            assertEquals(tuple, cache.legacyGet(WorldGeometryCache.key(7, null, tuple)).tile());
            first.withTile(
                    old ->
                            next.withTile(
                                    current ->
                                            org.junit.jupiter.api.Assertions.assertSame(
                                                    old, current)));
            first.close();
            next.close();
        } finally {
            cache.closeAsync().join();
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void legacyGeometryIsRequalifiedAndMalformedConstructionDoesNotConsumeCredit() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var cache = new WorldGeometryCache();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var parsed = WorldFormats.adtTile(files.sources.get(2L), () -> false);
            var descriptive = new WorldFormats.Tile(7, 19, 2, 0, 0, 999, 0, 999, 0, 0);
            String key = WorldGeometryCache.key(7, "IgnoredDirectory", TILE);
            cache.legacyPut(
                    key,
                    new WorldService.LoadedTile(
                            descriptive, parsed.terrain(), null, parsed.areas()));
            var prepared =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            assertEquals(1, files.opens(2), "Public legacy arrays never establish source proof");
            org.junit.jupiter.api.Assertions.assertSame(
                    parsed.terrain(), cache.legacyGet(key).terrain());
            assertEquals(
                    TILE,
                    cache.legacyGet(key).tile(),
                    "Current admitted companion tuple replaces old DTO");
            prepared.close();
            cache.closeAsync().join();
            var fresh = new WorldGeometryCache();
            try {
                byte[] original = files.sources.get(2L);
                files.sources.put(2L, new byte[16]);
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                source.withRecordedTile(
                                        scope,
                                        TILE,
                                        WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                        Cancellation.NONE,
                                        fresh::prepare));
                files.sources.put(2L, original);
                var recovered =
                        source.withRecordedTile(
                                scope,
                                TILE,
                                WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                Cancellation.NONE,
                                fresh::prepare);
                recovered.close();
            } finally {
                fresh.closeAsync().join();
            }
        } finally {
            cache.closeAsync().join();
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void twelvePinnedGeometryVersionsSurviveReplacementAndReleaseAllowsNextSourceRead() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var cache = new WorldGeometryCache();
        var outputs = new ArrayList<WorldGeometryCache.Prepared>();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var original = files.sources.get(2L);
            for (int i = 0; i < 12; i++) {
                var changed = original.clone();
                java.nio.ByteBuffer.wrap(changed)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putFloat(20 + 112, 10 + i);
                files.sources.put(2L, changed);
                files.changedId = 2;
                files.changeAfterRead = i % 2 != 0;
                outputs.add(
                        source.withRecordedTile(
                                scope,
                                TILE,
                                WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                Cancellation.NONE,
                                cache::prepare));
            }
            assertEquals(12, files.opens(2));
            assertNotEquals(outputs.getFirst().fingerprint(), outputs.getLast().fingerprint());
            outputs.getFirst().withTile(tile -> assertTrue(tile.vertexCount() > 0));
            files.changeAfterRead = false;
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    cache::prepare));
            assertEquals(12, files.opens(2));
            outputs.removeFirst().close();
            var next =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            outputs.add(next);
            assertEquals(13, files.opens(2));
        } finally {
            for (var output : outputs) {
                output.close();
            }
            cache.closeAsync().join();
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void preparedGeometryReusesActualProofWithoutRootRereadAndRefreshesEqualGeometryAreas()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var cache = new WorldGeometryCache();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var first =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            int rootReads = files.opens(2);
            var repeated =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            assertEquals(
                    rootReads, files.opens(2), "Qualified unchanged root reuses existing geometry");
            first.withTile(
                    old ->
                            repeated.withTile(
                                    current ->
                                            org.junit.jupiter.api.Assertions.assertSame(
                                                    old, current)));
            var changed = files.sources.get(2L).clone();
            java.nio.ByteBuffer.wrap(changed)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(20 + 52, 12345);
            files.sources.put(2L, changed);
            files.changedId = 2;
            files.changeAfterRead = true;
            var refreshed =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            cache::prepare);
            assertEquals(rootReads + 1, files.opens(2));
            assertEquals(first.fingerprint(), refreshed.fingerprint());
            first.withTile(
                    old ->
                            refreshed.withTile(
                                    current ->
                                            org.junit.jupiter.api.Assertions.assertSame(
                                                    old, current)));
            assertEquals(0, first.areas().chunks().getFirst().areaId());
            assertEquals(12345, refreshed.areas().chunks().getFirst().areaId());
            first.close();
            repeated.close();
            refreshed.close();
        } finally {
            cache.closeAsync().join();
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void preparedGeometryOutputCreditRejectsBeforeIoAndCloseDrainsBorrowedFrame() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var cache = new WorldGeometryCache();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var outputs = new ArrayList<WorldGeometryCache.Prepared>();
            for (int i = 0; i < 12; i++) {
                outputs.add(
                        source.withRecordedTile(
                                scope,
                                TILE,
                                WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                Cancellation.NONE,
                                cache::prepare));
            }
            int reads = files.totalOpens();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    cache::prepare));
            assertEquals(reads, files.totalOpens());
            var borrower =
                    CompletableFuture.runAsync(
                            () ->
                                    outputs.getFirst()
                                            .withTile(
                                                    tile -> {
                                                        assertTrue(tile.vertexCount() > 0);
                                                        entered.countDown();
                                                        release.join();
                                                        assertTrue(tile.indexCount() > 0);
                                                    }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var drain = cache.closeAsync();
            assertFalse(drain.isDone());
            assertThrows(
                    IllegalStateException.class, () -> outputs.getFirst().withTile(tile -> {}));
            release.complete(null);
            borrower.join();
            drain.join();
        } finally {
            release.complete(null);
            cache.closeAsync().join();
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void externalCancellationNeverRunsHeldLowerCallbacksOnCallerAndDrainsBeforeNextJob()
            throws Exception {
        var lowerEntered = new CountDownLatch(1);
        var lowerRelease = new CompletableFuture<Void>();
        var useEntered = new CountDownLatch(1);
        var useRelease = new CompletableFuture<Void>();
        var attached = new java.util.concurrent.atomic.AtomicBoolean();
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 2 && attached.compareAndSet(false, true)) {
                            token.onCancel(
                                    () -> {
                                        lowerEntered.countDown();
                                        lowerRelease.join();
                                    });
                        }
                        return super.stat(id, token);
                    }
                };
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var external = new Cancellation();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var work =
                    CompletableFuture.supplyAsync(
                            () ->
                                    source.withRecordedTile(
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
                                                useEntered.countDown();
                                                useRelease.join();
                                                return (AutoCloseable) () -> {};
                                            }));
            assertTrue(useEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture.runAsync(external::cancel).get(1, TimeUnit.SECONDS);
            assertTrue(lowerEntered.await(5, TimeUnit.SECONDS));
            useRelease.complete(null);
            var drain = source.closeAsync();
            assertFalse(drain.isDone());
            assertFalse(work.isDone(), "Source guard includes lower cancellation callback drain");
            lowerRelease.complete(null);
            assertThrows(java.util.concurrent.CompletionException.class, work::join);
            drain.join();
        } finally {
            useRelease.complete(null);
            lowerRelease.complete(null);
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void derivedCandidatesAreFixedBoundReadsAndNeverMintRenderableEvidence() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            // Arbitrary authored bytes are allowed as candidates, not as qualified assets.
            AutoCloseable result =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.BASIC_WMO,
                            Cancellation.NONE,
                            admission -> {
                                try (var candidate =
                                        admission.readDerivedCandidate(
                                                10, WorldStreamScopes.DerivedKind.WMO_ROOT)) {
                                    assertEquals(10, candidate.fileDataId());
                                }
                                int opened = files.opens(11);
                                assertThrows(
                                        IllegalStateException.class,
                                        () ->
                                                admission.readDerivedCandidate(
                                                        11,
                                                        WorldStreamScopes.DerivedKind.WMO_ROOT));
                                assertEquals(opened, files.opens(11));
                                for (int i = 0; i < 6; i++) {
                                    try (var candidate =
                                            admission.readDerivedCandidate(
                                                    11, WorldStreamScopes.DerivedKind.WMO_IMAGE)) {
                                        assertTrue(candidate.bytes().length > 0);
                                    }
                                }
                                assertThrows(
                                        IllegalStateException.class,
                                        () ->
                                                admission.readDerivedCandidate(
                                                        11,
                                                        WorldStreamScopes.DerivedKind.WMO_IMAGE));
                                return (AutoCloseable) () -> {};
                            });
            result.close();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        admission.readDerivedCandidate(
                                                10, WorldStreamScopes.DerivedKind.WMO_ROOT);
                                        throw new AssertionError("Wrong profile accepted");
                                    }));
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void admissionRejectsOversizedInputBeforeOpenAndChangedMapAfterCallback() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var resultClosed = new AtomicInteger();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var original = files.sources.get(2L);
            files.sources.put(2L, new byte[(8 << 20) + 1]);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        admission.readRecorded(
                                                WorldStreamScopes.RecordedInput.ROOT);
                                        throw new AssertionError("Oversized input accepted");
                                    }));
            assertEquals(0, files.opens(2));
            files.sources.put(2L, original);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        tables.identity = "changed-during-admission";
                                        return (AutoCloseable) resultClosed::incrementAndGet;
                                    }));
            assertEquals(1, resultClosed.get());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void heldAdmissionKeepsBusyAndCloseDrainUntilCallbackFinally() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var outputClosed = new AtomicInteger();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            var work =
                    CompletableFuture.supplyAsync(
                            () ->
                                    source.withRecordedTile(
                                            scope,
                                            TILE,
                                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                            Cancellation.NONE,
                                            admission -> {
                                                try (var root =
                                                        admission.readRecorded(
                                                                WorldStreamScopes.RecordedInput
                                                                        .ROOT)) {
                                                    assertTrue(root.bytes().length > 0);
                                                    entered.countDown();
                                                    release.join();
                                                }
                                                return (AutoCloseable)
                                                        outputClosed::incrementAndGet;
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> (AutoCloseable) () -> {}));
            var drain = source.closeAsync();
            assertFalse(drain.isDone());
            release.complete(null);
            assertThrows(java.util.concurrent.CompletionException.class, work::join);
            drain.join();
            assertEquals(1, outputClosed.get());
            assertEquals(files.totalOpens(), files.closed.get());
        } finally {
            release.complete(null);
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void admissionOwnsSerialInputsAndExpiresWithoutRestartingSource() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var escaped = new WorldStreamScopes.TileAdmission[1];
        var escapedInput = new WorldStreamScopes.Input[1];
        var closed = new AtomicInteger();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            AutoCloseable result =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            admission -> {
                                escaped[0] = admission;
                                assertEquals(TILE, admission.recorded());
                                CompletableFuture.runAsync(
                                                () -> {
                                                    assertThrows(
                                                            IllegalStateException.class,
                                                            admission::recorded);
                                                })
                                        .join();
                                try (var root =
                                        admission.readRecorded(
                                                WorldStreamScopes.RecordedInput.ROOT)) {
                                    escapedInput[0] = root;
                                    assertTrue(root.bytes().length > 0);
                                    int before = files.opens(3);
                                    assertThrows(
                                            IllegalStateException.class,
                                            () ->
                                                    admission.readRecorded(
                                                            WorldStreamScopes.RecordedInput.TEX0));
                                    assertEquals(
                                            before,
                                            files.opens(3),
                                            "Credit rejection precedes open");
                                }
                                try (var texture =
                                        admission.readRecorded(
                                                WorldStreamScopes.RecordedInput.TEX0)) {
                                    assertTrue(texture.bytes().length > 0);
                                }
                                return (AutoCloseable) closed::incrementAndGet;
                            });
            assertEquals(0, closed.get());
            assertThrows(IllegalStateException.class, () -> escaped[0].recorded());
            assertThrows(IllegalStateException.class, () -> escapedInput[0].bytes());
            try {
                result.close();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertEquals(1, closed.get());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void admissionFinalBindingChangeClosesReturnedResultAndUnreturnedOutputIsCallbackOwned()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var closed = new AtomicInteger();
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        try (var root =
                                                admission.readRecorded(
                                                        WorldStreamScopes.RecordedInput.ROOT)) {
                                            assertTrue(root.bytes().length > 0);
                                        }
                                        files.changedId = 2;
                                        files.changeAfterRead = true;
                                        return (AutoCloseable) closed::incrementAndGet;
                                    }));
            assertEquals(1, closed.get());
            files.changeAfterRead = false;
            var scratch = java.nio.file.Files.createTempFile("vimi-unreturned-output-", ".tmp");
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        // A real local owner must be released before an exceptional
                                        // non-return.
                                        try (AutoCloseable local =
                                                () -> {
                                                    java.nio.file.Files.delete(scratch);
                                                    closed.incrementAndGet();
                                                }) {
                                            assertTrue(local != null);
                                            throw new IllegalArgumentException(
                                                    "Factory failed before return");
                                        } catch (IllegalArgumentException failure) {
                                            throw failure;
                                        } catch (Exception failure) {
                                            throw new AssertionError(failure);
                                        }
                                    }));
            assertEquals(2, closed.get());
            assertFalse(java.nio.file.Files.exists(scratch));
            AutoCloseable recovered =
                    source.withRecordedTile(
                            scope,
                            TILE,
                            WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                            Cancellation.NONE,
                            admission -> (AutoCloseable) closed::incrementAndGet);
            try {
                recovered.close();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertEquals(3, closed.get());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void derivesNumericWdtOnlyFromQualifiedOwnedMapRowAndRevalidatesEveryRecordedReference() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            assertEquals(1, scope.wdtId(), "Caller-supplied WDT999 is never opened");
            assertEquals(0, files.opens(999));
            assertEquals(TILE, source.recorded(scope, TILE, Cancellation.NONE));
            long[] refs = {2, 0, 0, 0, 0, 3, 0, 0};
            for (int i = 0; i < refs.length; i++) {
                var wrong = refs.clone();
                wrong[i]++;
                var forged =
                        new WorldFormats.Tile(
                                7, 19, wrong[0], wrong[1], wrong[2], wrong[3], wrong[4], wrong[5],
                                wrong[6], wrong[7]);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> source.recorded(scope, forged, Cancellation.NONE));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.recorded(
                                    scope,
                                    new WorldFormats.Tile(8, 19, 2, 0, 0, 0, 0, 3, 0, 0),
                                    Cancellation.NONE));
            assertEquals(
                    1,
                    files.totalOpens(),
                    "Reference verification never reads caller root/tex0/objects");
        } finally {
            source.closeAsync().join();
            tables.close();
        }
        assertEquals(0, files.borrowedClosed.get(), "Source handles remain borrowed");
    }

    @Test
    void rejectsUnknownDuplicateMissingUnqualifiedAndPartialRowsBeforeOpeningFiles() {
        for (int mode = 0; mode < 6; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            switch (mode) {
                case 0 -> tables.mapId = 8;
                case 1 -> tables.duplicate = true;
                case 2 -> tables.wdt = "0";
                case 3 -> tables.qualified = false;
                case 4 -> tables.state = TableTypes.State.PARTIAL;
                case 5 -> tables.pageState = TableTypes.PageState.STALE;
                default -> throw new AssertionError();
            }
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void changedMapRowOrWdtKeyInvalidatesEvenAnExternallyRetainedScope() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var first = source.create(REQUEST, Cancellation.NONE);
            source.activate(first);
            tables.identity = "changed-row-identity";
            assertThrows(
                    IllegalStateException.class,
                    () -> source.recorded(first, TILE, Cancellation.NONE));
            assertEquals(0, source.retainedProofs());
            int reads = tables.pages.get();
            assertThrows(
                    IllegalStateException.class,
                    () -> source.recorded(first, TILE, Cancellation.NONE));
            assertEquals(reads, tables.pages.get(), "Stale token fails before source access");
            var second = source.create(REQUEST, Cancellation.NONE);
            assertNotEquals(first.digest(), second.digest());
            files.changedId = 1;
            files.changeAfterRead = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> source.recorded(second, TILE, Cancellation.NONE));
            assertEquals(0, source.retainedProofs());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void stalePublicTokensCannotRetainHeavyProofsAndOnlyCurrentPlusReplacementSurvive() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var old = new ArrayList<WorldStreamScopes.Scope>();
        try {
            var current = source.create(REQUEST, Cancellation.NONE);
            source.activate(current);
            for (int i = 0; i < 40; i++) {
                old.add(source.create(REQUEST, Cancellation.NONE));
                assertEquals(2, source.retainedProofs());
                assertEquals(263_168, source.retainedMaidBytes());
            }
            for (var scope : old.subList(0, old.size() - 1)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> source.recorded(scope, TILE, Cancellation.NONE));
            }
            for (var field : WorldStreamScopes.Scope.class.getDeclaredFields()) {
                assertTrue(
                        field.getType().isPrimitive() || field.getType() == String.class,
                        "A retained token has neither heavy arrays nor source/service closures");
            }
            for (var constructor : WorldStreamScopes.Scope.class.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        } finally {
            source.closeAsync().join();
            tables.close();
        }
        assertEquals(0, source.retainedProofs());
        assertThrows(
                IllegalStateException.class,
                () -> source.recorded(old.getLast(), TILE, Cancellation.NONE));
    }

    @Test
    void heldSourceReadDoesNotHoldLifecycleMonitorAndCloseDrainsBeforeCompleting()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        tables.held = new CompletableFuture<>();
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var pending =
                CompletableFuture.supplyAsync(() -> source.create(REQUEST, Cancellation.NONE));
        assertTrue(tables.entered.await(2, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> source.create(REQUEST, Cancellation.NONE));
        long start = System.nanoTime();
        var closed = source.closeAsync();
        for (int i = 0; i < 32; i++) {
            assertFalse(source.closeAsync().isDone(), "Repeated close shares pending owner drain");
        }
        assertTrue(
                System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1),
                "Close does not wait for held I/O");
        assertFalse(closed.isDone());
        assertEquals(0, source.retainedProofs());
        tables.held.complete(tables.page());
        assertThrows(java.util.concurrent.CompletionException.class, pending::join);
        closed.get(2, TimeUnit.SECONDS);
        assertEquals(0, files.totalOpens());
        assertEquals(0, files.borrowedClosed.get());
        tables.close();
    }

    @Test
    void unsupportedOrUnknownNativeCaptureNeverFallsBackToGenericPages() {
        for (boolean unsupported : new boolean[] {true, false}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            tables.unsupported = unsupported;
            tables.unknownEvidence = !unsupported;
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        RuntimeException.class, () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void invalidWdtIdentityProtectedBytesAndCancellationDoNotLeaveProofs() {
        for (int mode = 0; mode < 4; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            var token = new Cancellation();
            switch (mode) {
                case 0 -> files.protectedId = 1;
                case 1 -> files.wrongId = 1;
                case 2 -> {
                    files.changedId = 1;
                    files.changeAfterRead = true;
                }
                case 3 -> token.cancel();
                default -> throw new AssertionError();
            }
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(RuntimeException.class, () -> source.create(REQUEST, token));
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void boundedCapabilityRefusalDoesNotFallbackOrTurnIntoMissingTerrain() {
        for (boolean absent : new boolean[] {true, false}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            tables.boundedUnsupported = absent;
            tables.forcedLimit = !absent;
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        RuntimeException.class, () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
                tables.boundedUnsupported = false;
                tables.forcedLimit = false;
                assertEquals(
                        1,
                        source.create(REQUEST, Cancellation.NONE).wdtId(),
                        "A policy refusal does not poison the native graph or source engine");
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void sourceDownloadMayEnrichStoragePathFormatAndUnknownLengthWithoutChangingIdentity() {
        for (boolean wrongLength : new boolean[] {false, true}) {
            var files =
                    new WorldTerrainTextureSourceTest.Files() {
                        @Override
                        FileTypes.FileStat stat(long id, Cancellation token) {
                            var original = super.stat(id, token);
                            if (original.file() == null) {
                                return original;
                            }
                            var file = original.file();
                            boolean downloaded = opens(id) > 0;
                            return new FileTypes.FileStat(
                                    original.state(),
                                    new FileTypes.BuildFile(
                                            file.fileDataId(),
                                            downloaded ? "world/maps/authored/authored.wdt" : null,
                                            downloaded
                                                    ? FileTypes.FilePathSource.LISTFILE
                                                    : FileTypes.FilePathSource.NONE,
                                            downloaded ? file.size() + (wrongLength ? 1 : 0) : null,
                                            file.contentKey(),
                                            downloaded
                                                    ? FileTypes.FileFormat.WDT
                                                    : FileTypes.FileFormat.UNKNOWN,
                                            downloaded
                                                    ? FileTypes.FileFormatSource.EXTENSION
                                                    : FileTypes.FileFormatSource.NONE,
                                            downloaded
                                                    ? FileTypes.FileStorage.STORED
                                                    : FileTypes.FileStorage.NOT_STORED,
                                            false));
                        }
                    };
            var tables = new Tables(files);
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                if (wrongLength) {
                    assertThrows(
                            IllegalStateException.class,
                            () -> source.create(REQUEST, Cancellation.NONE));
                    assertEquals(0, source.retainedProofs());
                } else {
                    var scope = source.create(REQUEST, Cancellation.NONE);
                    assertEquals(1, scope.wdtId());
                    assertEquals(TILE, source.recorded(scope, TILE, Cancellation.NONE));
                    AutoCloseable owned =
                            source.withRecordedTile(
                                    scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                    Cancellation.NONE,
                                    admission -> {
                                        try (var root =
                                                admission.readRecorded(
                                                        WorldStreamScopes.RecordedInput.ROOT)) {
                                            org.junit.jupiter.api.Assertions.assertNull(
                                                    root.declaredLength());
                                            assertEquals(8 << 20, root.sourceLimit());
                                            assertEquals(
                                                    files.sources.get(2L).length,
                                                    root.consumedLength());
                                        }
                                        try (var root =
                                                admission.readRecorded(
                                                        WorldStreamScopes.RecordedInput.ROOT)) {
                                            assertEquals(
                                                    (long) files.sources.get(2L).length,
                                                    root.declaredLength());
                                        }
                                        return (AutoCloseable) () -> {};
                                    });
                    try {
                        owned.close();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void fullUniqueMapProofRejectsSkippedAndEmptyJumpedOrdinals() {
        for (boolean empty : new boolean[] {false, true}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            tables.rowCount = 3;
            tables.skipPage = !empty;
            tables.emptyMiddle = empty;
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(
                        0,
                        files.totalOpens(),
                        "Incomplete uniqueness proof never authorizes WDT access");
                assertEquals(0, source.retainedProofs());
                tables.skipPage = false;
                tables.emptyMiddle = false;
                assertEquals(
                        1,
                        source.create(REQUEST, Cancellation.NONE).wdtId(),
                        "Complete genuine traversal remains usable");
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void metadataInputsAreProfileBoundedBeforeIdentityHashingOrRowReads() {
        for (int mode = 1; mode <= 23; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            tables.metadataMode = mode;
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, tables.pages.get(), "Oversized metadata refuses before row decode");
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
                tables.metadataMode = 0;
                assertEquals(1, source.create(REQUEST, Cancellation.NONE).wdtId());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    private static CapturedRowsTypes.Receipt oversizedMetadata(
            CapturedRowsTypes.Receipt r, int mode) {
        var columns = new ArrayList<>(r.columns());
        var schema = r.schema();
        var header = r.header();
        var completion = r.completion();
        var sections = r.sections();
        var holes = r.holeKeys();
        var keys = r.effectiveKeys();
        var table = r.table();
        String revision = r.readerRevision(),
                encoding = r.rowEncoding(),
                scope = r.encodingScope(),
                identity = r.sourceIdentity();
        switch (mode) {
            case 1 -> {
                while (columns.size() < 129) {
                    columns.add(r.columns().getFirst());
                }
            }
            case 2 ->
                    columns.set(
                            0, new Db2Types.Column("n".repeat(129), "integer", true, null, null));
            case 3 -> columns.set(0, new Db2Types.Column("ID", "k".repeat(33), true, null, null));
            case 4 ->
                    columns.set(
                            0,
                            new Db2Types.Column(
                                    "ID",
                                    "integer",
                                    true,
                                    new Db2Types.Interpretation(
                                            "policy", 32, false, 32, false, "w".repeat(257)),
                                    null));
            case 5 -> revision = "r".repeat(129);
            case 6 -> encoding = "e".repeat(65);
            case 7 -> scope = "s".repeat(257);
            case 8 -> identity = "i".repeat(8257);
            case 9 -> {
                columns.clear();
                for (int i = 0; i < 128; i++) {
                    columns.add(
                            new Db2Types.Column(
                                    "n".repeat(128), "k".repeat(32), false, null, null));
                }
            }
            case 10 ->
                    schema =
                            new TableSchema(
                                    java.util.Collections.nCopies(
                                            129, r.schema().columns().getFirst()),
                                    r.schema().layoutHashes(),
                                    r.schema().match(),
                                    r.schema().locale(),
                                    null);
            case 11 ->
                    schema =
                            new TableSchema(
                                    r.schema().columns(),
                                    r.schema().layoutHashes(),
                                    r.schema().match(),
                                    r.schema().locale(),
                                    "c".repeat(1025));
            case 12 ->
                    header =
                            new Db2Types.Header(
                                    header.schemaVersion(),
                                    header.tableHash(),
                                    header.layoutHash(),
                                    header.declaredRecords(),
                                    header.declaredCopyRecords(),
                                    header.recordSize(),
                                    header.columns(),
                                    "f".repeat(17));
            case 13 ->
                    completion =
                            new Db2Types.Completion(
                                    completion.declaredRecords(),
                                    completion.physicalRows(),
                                    completion.copyRows(),
                                    completion.excludedEncryptedSections(),
                                    completion.excludedPhysicalRecords(),
                                    completion.excludedCopyRecords(),
                                    "s".repeat(65),
                                    completion.consumedBytes(),
                                    completion.textIssueCount());
            case 14 -> sections = java.util.Collections.nCopies(129, sections.getFirst());
            case 15 -> {
                var section = sections.getFirst();
                sections =
                        List.of(
                                new Db2Types.Section(
                                        section.index(),
                                        section.recordCount(),
                                        section.copyCount(),
                                        "k".repeat(65),
                                        section.isEncrypted(),
                                        section.protectedCopyCount(),
                                        section.unresolvedCopyCount(),
                                        section.isDecrypted()));
            }
            case 16 -> holes = java.util.Collections.nCopies(129, "key");
            case 17 -> holes = List.of("k".repeat(65));
            case 18 ->
                    keys =
                            java.util.Collections.nCopies(
                                    129, new CapturedRowsTypes.KeyFingerprint("key", null));
            case 19 -> keys = List.of(new CapturedRowsTypes.KeyFingerprint("key", "f".repeat(65)));
            case 20 ->
                    header =
                            new Db2Types.Header(
                                    header.schemaVersion(),
                                    header.tableHash(),
                                    header.layoutHash(),
                                    header.declaredRecords(),
                                    header.declaredCopyRecords(),
                                    header.recordSize(),
                                    java.util.Collections.nCopies(129, columns.getFirst()),
                                    header.format());
            case 21 -> {
                var c = table.counts();
                table =
                        new TableTypes.Entry(
                                table.fileDataId(),
                                table.name(),
                                table.state(),
                                table.checked(),
                                table.reason(),
                                new TableTypes.Counts(
                                        c.declaredRows(),
                                        c.readableRows(),
                                        c.excludedSections(),
                                        c.excludedRecords(),
                                        "f".repeat(17),
                                        c.layoutHash()),
                                table.contentKey());
            }
            case 22 -> {
                holes = java.util.Collections.nCopies(128, "k".repeat(64));
                keys =
                        java.util.Collections.nCopies(
                                128,
                                new CapturedRowsTypes.KeyFingerprint(
                                        "k".repeat(64), "f".repeat(64)));
            }
            case 23 ->
                    header =
                            new Db2Types.Header(
                                    header.schemaVersion(),
                                    "h".repeat(17),
                                    header.layoutHash(),
                                    header.declaredRecords(),
                                    header.declaredCopyRecords(),
                                    header.recordSize(),
                                    header.columns(),
                                    header.format());
            default -> throw new AssertionError();
        }
        return new CapturedRowsTypes.Receipt(
                r.build(),
                r.locale(),
                table,
                schema,
                columns,
                r.definitionSha256(),
                r.blockIdentity(),
                r.graphIdentity(),
                identity,
                r.readerOptions(),
                revision,
                encoding,
                scope,
                r.rowCount(),
                header,
                completion,
                sections,
                holes,
                keys,
                r.retention(),
                r.membership(),
                r.issueChannelEvidence());
    }

    @Test
    void stableScopeUsesFreshCapturesAndEofProbesWithoutRepeatedDataOrdinals() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        tables.rowCount = 16;
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            assertEquals(
                    16,
                    tables.dataReads.get(),
                    "Initial proof scans the entire unique Map domain once");
            assertEquals(2, tables.captures.get());
            assertEquals(2, tables.eofProbes.get());
            for (int i = 0; i < 5; i++) {
                assertEquals(TILE, source.recorded(scope, TILE, Cancellation.NONE));
            }
            assertEquals(16, tables.dataReads.get());
            assertEquals(7, tables.captures.get());
            assertEquals(7, tables.eofProbes.get());
            assertEquals(1, files.totalOpens(), "No repeated WDT payload read");
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void everyBoundedEofProvenanceAndCapabilityFailureRefusesAnEqualStamp() {
        for (int mode = 1; mode <= 12; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                var scope = source.create(REQUEST, Cancellation.NONE);
                int data = tables.dataReads.get();
                if (mode <= 10) {
                    tables.eofMode = mode;
                } else if (mode == 11) {
                    tables.boundedUnsupported = true;
                } else {
                    tables.unknownEvidence = true;
                }
                assertThrows(
                        RuntimeException.class,
                        () -> source.recorded(scope, TILE, Cancellation.NONE));
                assertEquals(
                        data,
                        tables.dataReads.get(),
                        "No fallback full scan under failed fresh capability");
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void completeSourceStampChangesInvalidateWithoutRescanningData() {
        for (int mode = 1; mode <= 13; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                var scope = source.create(REQUEST, Cancellation.NONE);
                int data = tables.dataReads.get();
                tables.stampMode = mode;
                assertThrows(
                        RuntimeException.class,
                        () -> source.recorded(scope, TILE, Cancellation.NONE));
                assertEquals(data, tables.dataReads.get());
                assertEquals(0, source.retainedProofs());
                tables.stampMode = 0;
                assertThrows(
                        IllegalStateException.class,
                        () -> source.recorded(scope, TILE, Cancellation.NONE));
                source.create(REQUEST, Cancellation.NONE);
                assertTrue(
                        tables.dataReads.get() > data,
                        "Replacement proof performs a complete scan");
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void heldFreshEofCancellationAndCloseDrainWithoutDataRescanOrStaleSuccess() throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                var scope = source.create(REQUEST, Cancellation.NONE);
                int data = tables.dataReads.get();
                tables.held = new CompletableFuture<>();
                var canceled = new Cancellation();
                var pending =
                        CompletableFuture.supplyAsync(() -> source.recorded(scope, TILE, canceled));
                assertTrue(tables.heldEntered.await(2, TimeUnit.SECONDS));
                CompletableFuture<Void> drained = null;
                if (close) {
                    drained = source.closeAsync();
                    assertFalse(drained.isDone());
                } else {
                    canceled.cancel();
                }
                tables.held.complete(tables.page(tables.rowCount));
                assertThrows(java.util.concurrent.CompletionException.class, pending::join);
                assertEquals(data, tables.dataReads.get());
                tables.held = null;
                if (close) {
                    drained.get(2, TimeUnit.SECONDS);
                    assertEquals(0, source.retainedProofs());
                } else {
                    assertEquals(TILE, source.recorded(scope, TILE, Cancellation.NONE));
                    assertEquals(data, tables.dataReads.get());
                }
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void newlyOversizedMetadataCannotReuseAnOtherwiseKnownPrivateProof() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            int reads = tables.pages.get();
            tables.metadataMode = 23;
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> source.recorded(scope, TILE, Cancellation.NONE));
            assertEquals(
                    reads,
                    tables.pages.get(),
                    "No hash/ordinal/EOF read under rejected metadata profile");
            assertEquals(0, source.retainedProofs());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void authoredRepeatedSourceValidationReportsWorkCountsAndTimings() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        tables.rowCount = 256;
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            long start = System.nanoTime();
            var scope = source.create(REQUEST, Cancellation.NONE);
            long initial = System.nanoTime() - start;
            long[] elapsed = new long[7];
            int before = tables.dataReads.get();
            for (int i = 0; i < elapsed.length; i++) {
                start = System.nanoTime();
                source.recorded(scope, TILE, Cancellation.NONE);
                elapsed[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(elapsed);
            System.out.println(
                    "WORLD_SCOPE_AUTHORED rows=256 runs=7 initial_us="
                            + initial / 1000
                            + " median_us="
                            + elapsed[3] / 1000
                            + " p95_us="
                            + elapsed[6] / 1000
                            + " max_us="
                            + elapsed[6] / 1000
                            + " repeated_data_ordinals="
                            + (tables.dataReads.get() - before)
                            + " total_data_ordinals="
                            + tables.dataReads.get()
                            + " captures="
                            + tables.captures.get()
                            + " eof_probes="
                            + tables.eofProbes.get());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    private static CapturedRowsTypes.Receipt changedStamp(CapturedRowsTypes.Receipt r, int mode) {
        var schema = r.schema();
        var header = r.header();
        var completion = r.completion();
        var sections = r.sections();
        var holes = r.holeKeys();
        var keys = r.effectiveKeys();
        var table = r.table();
        String definition = r.definitionSha256(),
                revision = r.readerRevision(),
                encoding = r.rowEncoding();
        long count = r.rowCount();
        switch (mode) {
            case 1 -> definition = "f".repeat(64);
            case 2 ->
                    schema =
                            new TableSchema(
                                    schema.columns(),
                                    schema.layoutHashes(),
                                    schema.match(),
                                    schema.locale(),
                                    "changed");
            case 3 -> {
                var fields = new ArrayList<>(schema.columns());
                var f = fields.getFirst();
                fields.set(
                        0,
                        new TableSchema.SchemaColumn(
                                f.name(),
                                f.kind(),
                                31,
                                f.unsigned(),
                                f.arrayLength(),
                                f.foreignKey(),
                                f.id(),
                                f.relation(),
                                f.verifiedName(),
                                f.locale(),
                                f.columnComment(),
                                f.fieldComment()));
                schema =
                        new TableSchema(
                                fields,
                                schema.layoutHashes(),
                                schema.match(),
                                schema.locale(),
                                schema.comment());
            }
            case 4 ->
                    header =
                            new Db2Types.Header(
                                    header.schemaVersion(),
                                    header.tableHash(),
                                    header.layoutHash(),
                                    header.declaredRecords() + 1,
                                    header.declaredCopyRecords(),
                                    header.recordSize(),
                                    header.columns(),
                                    header.format());
            case 5 ->
                    completion =
                            new Db2Types.Completion(
                                    completion.declaredRecords(),
                                    completion.physicalRows(),
                                    completion.copyRows(),
                                    completion.excludedEncryptedSections(),
                                    completion.excludedPhysicalRecords(),
                                    completion.excludedCopyRecords(),
                                    completion.scope(),
                                    completion.consumedBytes() + 1,
                                    completion.textIssueCount());
            case 6 -> {
                var section = sections.getFirst();
                sections =
                        List.of(
                                new Db2Types.Section(
                                        section.index(),
                                        section.recordCount(),
                                        section.copyCount(),
                                        "changed",
                                        section.isEncrypted(),
                                        section.protectedCopyCount(),
                                        section.unresolvedCopyCount(),
                                        section.isDecrypted()));
            }
            case 7 -> holes = List.of("changed");
            case 8 ->
                    keys = List.of(new CapturedRowsTypes.KeyFingerprint("changed", "1".repeat(64)));
            case 9 -> {
                var c = table.counts();
                table =
                        new TableTypes.Entry(
                                table.fileDataId(),
                                table.name(),
                                table.state(),
                                table.checked(),
                                table.reason(),
                                new TableTypes.Counts(
                                        c.declaredRows(),
                                        c.readableRows() + 1,
                                        c.excludedSections(),
                                        c.excludedRecords(),
                                        c.format(),
                                        c.layoutHash()),
                                table.contentKey());
            }
            case 10 -> revision = revision + "changed";
            case 11 -> encoding = encoding + "changed";
            case 12 ->
                    table =
                            new TableTypes.Entry(
                                    table.fileDataId() + 1,
                                    table.name(),
                                    table.state(),
                                    table.checked(),
                                    table.reason(),
                                    table.counts(),
                                    table.contentKey());
            case 13 -> count++;
            default -> throw new AssertionError();
        }
        return new CapturedRowsTypes.Receipt(
                r.build(),
                r.locale(),
                table,
                schema,
                r.columns(),
                definition,
                r.blockIdentity(),
                r.graphIdentity(),
                r.sourceIdentity(),
                r.readerOptions(),
                revision,
                encoding,
                r.encodingScope(),
                count,
                header,
                completion,
                sections,
                holes,
                keys,
                r.retention(),
                r.membership(),
                r.issueChannelEvidence());
    }

    private static final class Tables {
        final WorldTerrainTextureSourceTest.Files files;
        final AtomicInteger pages = new AtomicInteger(),
                captures = new AtomicInteger(),
                dataReads = new AtomicInteger(),
                eofProbes = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), heldEntered = new CountDownLatch(1);
        volatile long mapId = 7;
        volatile int rowCount = 1, metadataMode, eofMode, stampMode;
        volatile boolean skipPage, emptyMiddle;
        volatile String wdt = "1", identity = "owned-map-row";
        volatile boolean duplicate,
                qualified = true,
                unsupported,
                unknownEvidence,
                boundedUnsupported,
                forcedLimit;
        volatile TableTypes.State state = TableTypes.State.READY;
        volatile TableTypes.PageState pageState = TableTypes.PageState.OK;
        volatile CompletableFuture<CapturedRowsTypes.Page> held;
        CapturedRows last;
        final java.util.Map<String, AuthoredNativeMap> nativeMaps = new java.util.HashMap<>();

        Tables(WorldTerrainTextureSourceTest.Files files) {
            this.files = files;
        }

        CapturedRowsTypes.Page page() {
            return page(0);
        }

        CapturedRowsTypes.Page page(long first) {
            var original =
                    last.readOrdinalPage(first, 1, WorldStreamScopes.READ_LIMITS, Cancellation.NONE)
                            .join();
            if (emptyMiddle && first == 1) {
                return new CapturedRowsTypes.Page(
                        original.identity(),
                        first,
                        CapturedRowsTypes.ReadState.READ,
                        List.of(),
                        2L,
                        null,
                        null);
            }
            if (original.rows().isEmpty()) {
                if (eofMode == 0) {
                    return original;
                }
                var state =
                        switch (eofMode) {
                            case 6 -> CapturedRowsTypes.ReadState.LIMIT_EXCEEDED;
                            case 7 -> CapturedRowsTypes.ReadState.CANCELED;
                            case 8 -> CapturedRowsTypes.ReadState.CLOSED;
                            case 9 -> CapturedRowsTypes.ReadState.CORRUPT;
                            case 10 -> CapturedRowsTypes.ReadState.INVALIDATED;
                            default -> CapturedRowsTypes.ReadState.READ;
                        };
                return new CapturedRowsTypes.Page(
                        eofMode == 1 ? "wrong" : original.identity(),
                        eofMode == 2 ? first - 1 : first,
                        state,
                        eofMode == 3
                                ? List.of(
                                        new TableTypes.Row(
                                                0,
                                                new Db2Types.Row(
                                                        7,
                                                        false,
                                                        null,
                                                        List.of("7", "1"),
                                                        List.of())))
                                : List.of(),
                        eofMode == 4 ? Long.valueOf(first + 1) : null,
                        null,
                        null,
                        eofMode == 5
                                ? List.of(
                                        new CapturedRowsTypes.CellIssue(
                                                first,
                                                -1,
                                                CapturedRowsTypes.CellState.LIMITED,
                                                "bad"))
                                : List.of());
            }
            var row = original.rows().getFirst();
            if (mapId != 7 || !wdt.equals("1")) {
                row =
                        new TableTypes.Row(
                                row.ordinal(),
                                new Db2Types.Row(
                                        mapId,
                                        false,
                                        null,
                                        List.of(Long.toString(mapId), wdt),
                                        row.row().textIssues()));
            }
            return new CapturedRowsTypes.Page(
                    original.identity(),
                    original.firstOrdinal(),
                    pageState == TableTypes.PageState.OK
                            ? original.state()
                            : CapturedRowsTypes.ReadState.INVALIDATED,
                    pageState == TableTypes.PageState.OK
                            ? (duplicate ? List.of(row, row) : List.of(row))
                            : List.of(),
                    skipPage && first == 0 ? Long.valueOf(2) : original.nextOrdinal(),
                    null,
                    null);
        }

        CapturedRowsTypes.Capture capture(Cancellation token) {
            captures.incrementAndGet();
            if (unsupported) {
                return CapturedRowsTypes.Capture.unsupported();
            }
            var fixture =
                    nativeMaps.computeIfAbsent(
                            identity,
                            key -> {
                                try {
                                    return new AuthoredNativeMap(
                                            files.files(),
                                            java.util.HexFormat.of()
                                                    .formatHex(
                                                            java.security.MessageDigest.getInstance(
                                                                            "MD5")
                                                                    .digest(
                                                                            key.getBytes(
                                                                                    java.nio.charset
                                                                                            .StandardCharsets
                                                                                            .UTF_8))),
                                            rowCount);
                                } catch (java.security.NoSuchAlgorithmException impossible) {
                                    throw new AssertionError(impossible);
                                }
                            });
            var actual =
                    fixture.tables()
                            .capturePreparedLocal(TableTypes.Ref.forName("Map"), token)
                            .join();
            assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, actual.state(), actual.message());
            var delegate = actual.rows();
            last = delegate;
            CapturedRows wrapper =
                    new CapturedRows() {
                        public CapturedRowsTypes.Receipt receipt() {
                            var r = delegate.receipt();
                            if (stampMode != 0) {
                                return changedStamp(r, stampMode);
                            }
                            if (metadataMode != 0) {
                                return oversizedMetadata(r, metadataMode);
                            }
                            if (qualified && state == TableTypes.State.READY && !unknownEvidence) {
                                return r;
                            }
                            var entry =
                                    new TableTypes.Entry(
                                            r.table().fileDataId(),
                                            "Map",
                                            state,
                                            true,
                                            null,
                                            r.table().counts(),
                                            r.table().contentKey());
                            var schema =
                                    qualified
                                            ? r.schema()
                                            : new TableSchema(
                                                    List.of(),
                                                    r.schema().layoutHashes(),
                                                    r.schema().match(),
                                                    r.schema().locale(),
                                                    null);
                            return new CapturedRowsTypes.Receipt(
                                    r.build(),
                                    r.locale(),
                                    entry,
                                    schema,
                                    r.columns(),
                                    r.definitionSha256(),
                                    r.blockIdentity(),
                                    r.graphIdentity(),
                                    r.sourceIdentity(),
                                    r.readerOptions(),
                                    r.readerRevision(),
                                    r.rowEncoding(),
                                    r.encodingScope(),
                                    r.rowCount(),
                                    r.header(),
                                    r.completion(),
                                    r.sections(),
                                    r.holeKeys(),
                                    r.effectiveKeys(),
                                    r.retention(),
                                    r.membership(),
                                    unknownEvidence
                                            ? CapturedRowsTypes.IssueChannelEvidence.unknown()
                                            : r.issueChannelEvidence());
                        }

                        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                                long first, int count, Cancellation cancel) {
                            throw new AssertionError("Scope proof may not use unbounded row API");
                        }

                        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                                long first,
                                int count,
                                CapturedRowsTypes.ReadLimits limits,
                                Cancellation cancel) {
                            assertEquals(WorldStreamScopes.READ_LIMITS, limits);
                            if (first < delegate.receipt().rowCount()) {
                                dataReads.incrementAndGet();
                            } else {
                                eofProbes.incrementAndGet();
                            }
                            if (boundedUnsupported) {
                                return CompletableFuture.failedFuture(
                                        new UnsupportedOperationException(
                                                "No bounded row capability"));
                            }
                            if (forcedLimit) {
                                return CompletableFuture.completedFuture(
                                        new CapturedRowsTypes.Page(
                                                delegate.receipt().graphIdentity(),
                                                first,
                                                CapturedRowsTypes.ReadState.LIMIT_EXCEEDED,
                                                List.of(),
                                                null,
                                                "CAPTURE_READ_LIMIT",
                                                "Authored refusal"));
                            }
                            assertEquals(1, count, "Map proof never requests a wide generic page");
                            pages.incrementAndGet();
                            entered.countDown();
                            if (held != null) {
                                heldEntered.countDown();
                                return held;
                            }
                            return CompletableFuture.completedFuture(page(first));
                        }

                        public CompletableFuture<Void> closeAsync() {
                            last = null;
                            return delegate.closeAsync();
                        }
                    };
            return new CapturedRowsTypes.Capture(
                    CapturedRowsTypes.CaptureState.CAPTURED, wrapper, null, null);
        }

        void close() {
            for (var fixture : nativeMaps.values()) {
                fixture.close();
            }
            nativeMaps.clear();
        }

        BuildTables handle() {
            return (BuildTables)
                    Proxy.newProxyInstance(
                            BuildTables.class.getClassLoader(),
                            new Class<?>[] {BuildTables.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "build" -> files.files().build();
                                        case "locale" -> "enUS";
                                        case "capturePreparedLocal" ->
                                                CompletableFuture.completedFuture(
                                                        capture((Cancellation) args[1]));
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected borrowed table API "
                                                                + method.getName());
                                    });
        }
    }
}
