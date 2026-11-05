package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainStreaming;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.AuthoredPacketDestination;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Uses the actual native Map preparation and actual checked WDT/ADT/BLP source factory. */
public final class WorldStreamSourceBridgeTest {
    private WorldStreamSourceBridgeTest() {}
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    public static void main(String[] args) throws Exception {
        successfulMove(false, false, false);
        successfulMove(true, false, true);
        successfulMove(false, true, false);
        successfulMove(true, false, false);
        untakenResidentCloseLeavesMovedPacketOwned();
        rejectLateView(2);
        rejectLateView(3);
        fatalCleanupAfterMove();
        destinationStoppingCancelsActualSourceAdmission();
        cleanSourceRetirementMapsButProviderFailureStaysFatal();
        stickySourceCleanupHealthIsFatalBeforeFileMove();
        exteriorsAreOnByDefault();
        terrainProducerNeverWaitsForObjects();
    }

    private static void exteriorsAreOnByDefault() {
        check(WorldStreamGpuOwner.exteriorsEnabled(null), "Exteriors are on without the property");
        check(WorldStreamGpuOwner.exteriorsEnabled("true"), "true keeps exteriors on");
        check(WorldStreamGpuOwner.exteriorsEnabled(""), "An empty value keeps exteriors on");
        check(!WorldStreamGpuOwner.exteriorsEnabled("false"), "false turns exteriors off");
        check(!WorldStreamGpuOwner.exteriorsEnabled("FALSE"), "The opt-out ignores letter case");
        check(WorldStreamGpuOwner.exteriorsEnabled("no"), "Only false opts out");
        var spec = WorldStreamGpuOwner.spec("0".repeat(64));
        check(spec.exteriorObjects() == WorldStreamGpuOwner.EXTERIORS,
                "The GUI session profile follows the property");
        if (System.getProperty("vimi.world.exteriors") == null) {
            check(WorldStreamGpuOwner.EXTERIORS && spec.exteriorObjects(),
                    "The default GUI session draws exterior buildings");
        }
    }

    /**
     * The terrain producer never prepares or names exterior objects, even under the exterior
     * profile: objects are delivered late by the controller, so a tile's terrain packet never waits
     * for them. The standalone preparer degrades an empty tile to "no objects" and propagates only
     * cancellation.
     */
    @SuppressWarnings("try")
    private static void terrainProducerNeverWaitsForObjects() throws Exception {
        var fixture = new Fixture();
        var root = Files.createTempDirectory("bridge-exterior-");
        var path = root.resolve("tile-1.wst");
        try {
            var scope = fixture.scope();
            try (var producer = new WorldStreamSourceBridge.Producer(
                    fixture.source, null, scope, TILE, () -> true)) {
                var destination = AuthoredPacketDestination.controlled(
                        path, scope.scopeSha256(), true).destination();
                try (var packet = producer.prepare(destination, () -> false);
                        var resident = producer.takePrepared()) {
                    check(Files.exists(path), "Exterior-profile terrain packet produced at once");
                    try (var names = Files.list(root)) {
                        check(names.noneMatch(name -> name.toString().endsWith(".wso")),
                                "The terrain packet carries no objects file");
                    }
                    check(resident.exteriorPlacements() == 0 && !resident.exteriorFailed(),
                            "No buildings until late objects attach");
                    resident.exteriorAttached(6);
                    check(resident.exteriorPlacements() == 6, "Late attach is recorded");
                    resident.exteriorFailedLate();
                    check(resident.exteriorFailed(), "Late failure is recorded");
                }
                check(!Files.exists(path), "Transport retirement deletes the packet");
            }
            var empty = WorldStreamSourceBridge.prepareExterior(
                    fixture.source, scope, TILE, new Cancellation());
            check(empty.exterior() == null && !empty.failed() && empty.placements() == 0,
                    "A tile without placed objects prepares to no objects, not a failure");
            var canceled = new Cancellation();
            canceled.cancel();
            try {
                WorldStreamSourceBridge.prepareExterior(fixture.source, scope, TILE, canceled);
                throw new AssertionError("Canceled object preparation must not complete");
            } catch (CancellationException expected) {
                // Only cancellation propagates.
            }
            check(fixture.pool.stats().leaseClaims() == 0, "No material lease leaked");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(root);
        }
    }

    private static void successfulMove(boolean height, boolean partial, boolean rejectedBinding) throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-source-").resolve("tile-1.wst");
        try {
            if (rejectedBinding) {
                // Actual root coordinates no longer match the atlas header grid.
                var root = fixture.files.sources.get(2L).clone();
                java.nio.ByteBuffer.wrap(root).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putFloat(12 + 8 + 104, 1000);
                fixture.files.sources.put(2L, root);
            } else if (height) {
                fixture.files.sources.remove(3L);
            }
            if (partial) {
                fixture.files.sources.remove(11L);
            }
            var scope = fixture.scope();
            try (var producer = new WorldStreamSourceBridge.Producer(
                    fixture.source, scope, TILE, () -> true)) {
                var packet = producer.prepare(
                        AuthoredPacketDestination.create(path, scope.scopeSha256()), () -> false);
                try (packet; var resident = producer.takePrepared()) {
                    check(Files.exists(path), "Moved packet remains owned by transport");
                    check(resident.facts().outcome() == (height
                            ? WorldTerrainStreaming.Outcome.HEIGHT_FALLBACK
                            : WorldTerrainStreaming.Outcome.TEXTURED), "Actual source outcome");
                    if (rejectedBinding) {
                        check(resident.facts().fallback() == WorldTerrainStreaming.Fallback.QUALIFIED_BINDING_REJECTED,
                                "Actual qualified atlas rejected by geometry binding");
                    }
                    resident.withPayload(borrow -> {
                        check(borrow.geometry().vertexCount() > 0, "Checked source geometry");
                        check((borrow.texture() == null) == height, "Checked material binding");
                        if (partial) {
                            check(borrow.material().compositionCacheSha256() == null,
                                    "PARTIAL keeps genuine nullable composition cache identity");
                        }
                    });
                    fixture.world.closeAsync().get(5, TimeUnit.SECONDS);
                    check(Files.exists(path), "Source close cannot delete moved child input");
                    check(!resident.isOpen(), "Source close retires new source borrows");
                }
                check(!Files.exists(path), "Transport retirement deletes packet");
            }
            check(fixture.pool.stats().leaseClaims() == 0, "No material lease leaked");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    @SuppressWarnings("try")
    private static void untakenResidentCloseLeavesMovedPacketOwned() throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-untaken-").resolve("tile-1.wst");
        try {
            var scope = fixture.scope();
            var producer = new WorldStreamSourceBridge.Producer(fixture.source, scope, TILE, () -> true);
            try (var packet = producer.prepare(
                    AuthoredPacketDestination.create(path, scope.scopeSha256()), () -> false)) {
                producer.close();
                check(fixture.pool.stats().leaseClaims() == 0, "Untaken base retired exactly once");
                check(Files.exists(path), "Untaken source retirement cannot delete moved packet");
                try {
                    producer.takePrepared();
                    throw new AssertionError("Retired producer cannot transfer a source holder");
                } catch (IllegalStateException expected) {
                    // No holder can be minted from a retired producer.
                }
                producer.close();
            } finally {
                producer.close();
            }
            check(!Files.exists(path), "File ownership stays with returned packet");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void rejectLateView(int rejectAt) throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-stale-").resolve("tile-1.wst");
        try {
            var scope = fixture.scope();
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            try (var producer = new WorldStreamSourceBridge.Producer(fixture.source, scope, TILE,
                    () -> checks.incrementAndGet() < rejectAt)) {
                try {
                    producer.prepare(AuthoredPacketDestination.create(path, scope.scopeSha256()),
                            () -> false);
                    throw new AssertionError("Stale view must not hand off a packet");
                } catch (CancellationException expected) {
                    check(!Files.exists(path), "Unadvertised packet retired on late view change");
                }
            }
            check(fixture.pool.stats().leaseClaims() == 0, "Late-view base retired");
        } finally {
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void stickySourceCleanupHealthIsFatalBeforeFileMove() throws Exception {
        stickySourceCleanupHealthIsFatalAt(2);
        stickySourceCleanupHealthIsFatalAt(3);
    }

    private static void stickySourceCleanupHealthIsFatalAt(int checkpoint) throws Exception {
        var fixture=new Fixture();
        var path=Files.createTempDirectory("bridge-health-").resolve("tile-1.wst");
        var health=new IllegalStateException("Authored sticky cleanup failure");
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var scope=fixture.scope();
            try(var producer=new WorldStreamSourceBridge.Producer(fixture.source,scope,TILE,()->{
                if(checks.incrementAndGet()==checkpoint) {
                    try {
                        var get=WorldService.class.getDeclaredMethod("preparedTerrain");
                        get.setAccessible(true);
                        var prepared=get.invoke(fixture.world);
                        var field=prepared.getClass().getDeclaredField("scopes");
                        field.setAccessible(true);
                        var engine=field.get(prepared);
                        var failure=engine.getClass().getDeclaredField("cleanupFailure");
                        failure.setAccessible(true);
                        failure.set(engine,health); // Negative fault only, never source positivity.
                    } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
                }
                return true;
            })) {
                try {
                    producer.prepare(AuthoredPacketDestination.create(path,scope.scopeSha256()),()->false);
                    throw new AssertionError("Unknown source cleanup fault cannot become typed retirement");
                } catch(IllegalStateException expected) {
                    check(expected.getCause()==health,"Actual source health guard preserves fatal cleanup cause");
                    check(producer.sourceRefusal()==null,"False isOpen is never origin evidence for cleanup failure");
                    check(!Files.exists(path),"Unadvertised input retires on fatal source health failure");
                }
            }
            check(fixture.pool.stats().leaseClaims()==0,"Source base retires despite sticky source fault");
        } finally {
            try { fixture.world.closeAsync().get(5,TimeUnit.SECONDS); }
            catch(java.util.concurrent.ExecutionException expected) { /* Sticky source cleanup stays fatal. */ }
            fixture.pool.closeAsync().get(5,TimeUnit.SECONDS);
            fixture.tables.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void cleanSourceRetirementMapsButProviderFailureStaysFatal() throws Exception {
        for (boolean retired : new boolean[] {true, false}) {
            var providerFailure = new IllegalStateException("Authored unknown provider failure");
            var fail = new java.util.concurrent.atomic.AtomicBoolean();
            var fixture = new Fixture(new AuthoredTerrainSource.Files() {
                @Override
                com.vibeminer.files.FileTypes.FileStat stat(long id, Cancellation token) {
                    if (fail.get()) { throw providerFailure; }
                    return super.stat(id, token);
                }
            });
            var path = Files.createTempDirectory("bridge-refusal-").resolve("tile-1.wst");
            try {
                var scope = fixture.scope();
                if (retired) { fixture.world.closeAsync().get(5, TimeUnit.SECONDS); }
                else { fail.set(true); }
                try (var producer = new WorldStreamSourceBridge.Producer(
                        fixture.source, scope, TILE, () -> true)) {
                    try {
                        producer.prepare(AuthoredPacketDestination.create(path, scope.scopeSha256()),
                                () -> false);
                        throw new AssertionError("Expected source refusal/failure");
                    } catch (com.vibeminer.rendering.bgfx.WorldStreamTransport.PreparationRefusal expected) {
                        check(retired && expected.reason() == com.vibeminer.rendering.bgfx.WorldStreamTransport.Refusal.SOURCE_INVALIDATED,
                                "Only actual source-origin retirement maps to transport refusal");
                        check(producer.sourceRefusal() != null
                                        && producer.sourceRefusal().reason()
                                                == WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED,
                                "Closed bridge retains only actual clean source-origin metadata");
                    } catch (IllegalStateException expected) {
                        check(!retired && expected == providerFailure,
                                "Unknown provider failure stays fatal, never a refusal");
                        check(producer.sourceRefusal() == null,
                                "Unknown failure cannot mint source-origin metadata");
                    }
                }
            } finally {
                fail.set(false);
                fixture.close();
                Files.deleteIfExists(path);
                Files.deleteIfExists(path.getParent());
            }
        }
    }

    @SuppressWarnings("try")
    private static void destinationStoppingCancelsActualSourceAdmission() throws Exception {
        var entered = new java.util.concurrent.CompletableFuture<Void>();
        var canceled = new java.util.concurrent.CompletableFuture<Void>();
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var fixture = new Fixture(new AuthoredTerrainSource.Files() {
            @Override
            com.vibeminer.files.FileTypes.FileStat stat(long id, Cancellation token) {
                if (id == 2) {
                    try (var registration = token.onCancel(() -> canceled.complete(null))) {
                        entered.complete(null);
                        release.join();
                        token.check();
                    }
                }
                return super.stat(id, token);
            }
        });
        var path = Files.createTempDirectory("bridge-cancel-").resolve("tile-1.wst");
        var scope = fixture.scope();
        var control = AuthoredPacketDestination.controlled(path, scope.scopeSha256());
        var cancellationWorker = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { control.runCancellation(); }
            catch (InterruptedException error) { throw new java.util.concurrent.CompletionException(error); }
        });
        try (var producer = new WorldStreamSourceBridge.Producer(
                fixture.source, scope, TILE, () -> true)) {
            var work = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { producer.prepare(control.destination(), () -> false); }
                catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            entered.get(5, TimeUnit.SECONDS);
            control.stop();
            canceled.get(5, TimeUnit.SECONDS);
            check(!work.isDone(), "Actual lower source work must drain before producer retires");
            release.complete(null);
            try {
                work.get(5, TimeUnit.SECONDS);
                throw new AssertionError("Stopped destination cannot hand off packet");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(!Files.exists(path), "Canceled source never advertises a file");
            }
            check(fixture.pool.stats().leaseClaims() == 0, "Canceled source has no atlas owner");
        } finally {
            release.complete(null);
            control.finish();
            cancellationWorker.get(5, TimeUnit.SECONDS);
            fixture.close();
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void fatalCleanupAfterMove() throws Exception {
        var fixture = new Fixture();
        var path = Files.createTempDirectory("bridge-fatal-").resolve("tile-1.wst");
        var original = new IllegalStateException("Authored post-MOVE failure");
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var scope = fixture.scope();
            try (var producer = new WorldStreamSourceBridge.Producer(fixture.source, scope, TILE,
                    () -> {
                        if (checks.incrementAndGet() == 3) {
                            try {
                                Files.delete(path);
                                Files.createDirectory(path);
                                Files.write(path.resolve("held"), new byte[] {1});
                            } catch (java.io.IOException error) {
                                throw new java.io.UncheckedIOException(error);
                            }
                            throw original;
                        }
                        return true;
                    })) {
                try {
                    producer.prepare(AuthoredPacketDestination.create(path, scope.scopeSha256()),
                            () -> false);
                    throw new AssertionError("Cleanup failure must be fatal");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().equals("Source packet cleanup failed"),
                            "Cleanup errors cannot become expected cancellation/refusal");
                    check(expected.getCause().getSuppressed().length == 1
                                    && expected.getCause().getSuppressed()[0] == original,
                            "Primary failure retained alongside cleanup failure");
                }
            }
            check(fixture.pool.stats().leaseClaims() == 0,
                    "Failed moved-file cleanup cannot skip independent base cleanup");
        } finally {
            fixture.close();
            Files.deleteIfExists(path.resolve("held"));
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.getParent());
        }
    }

    private static void check(boolean valid, String detail) {
        if (!valid) {
            throw new AssertionError(detail);
        }
    }

    @SuppressWarnings("try")
    private static final class Fixture implements AutoCloseable {
        final AuthoredTerrainSource.Files files;
        final TerrainAtlasPool pool = new TerrainAtlasPool();
        final AuthoredNativeMap tables;
        final WorldService world;
        final WorldTerrainStreaming source;

        Fixture() { this(new AuthoredTerrainSource.Files()); }

        Fixture(AuthoredTerrainSource.Files files) {
            this.files = files;
            tables = new AuthoredNativeMap(files.files());
            world = new WorldService(tables.tables(), files.files(), pool);
            source = world.terrainStreaming().orElseThrow();
        }

        WorldTerrainStreaming.Scope scope() {
            return source.createScope(new WorldService.MapInfo(7, "Authored", null, 0, 1L),
                    Cancellation.NONE);
        }

        public void close() throws Exception {
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
        }
    }
}
