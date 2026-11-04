package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.AuthoredTerrainPoolPressure;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainCamera;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Genuine native source/packet/transport with authored child pixels; no GPU/FPS assertion. */
public final class WorldStreamSourceControllerTest {
    private WorldStreamSourceControllerTest() {}

    public static void main(String[] args) throws Exception {
        cameraPublicationSerializesInitialAndFxForwarding();
        initialSeedHeldReplacementCameraAndPresentationDrain();
        supersededHeldSourceCancelsBeforeStalePredicateAndKeepsSession();
        missingRootIsFinitePerDemandAndDoesNotInvalidateCurrentScope();
        heldPresentationBackpressuresTwelveUniqueOwnersAndKeepsFinalFrame();
        poolReleaseRetriesWithoutDemandOrParentCapacityChange();
        queuedGpuOwnerLateCloseIsPartOfControllerDrain();
        closeDuringActualInitialWdtQualificationIsCleanAndWaitsSourceDrain();
    }

    private static void cameraPublicationSerializesInitialAndFxForwarding() throws Exception {
        var fixture=new Fixture(new AuthoredTerrainSource.Files(),1);
        var started=new CompletableFuture<Void>();
        var opening=new CompletableFuture<WorldStreamTransport.Session>();
        var fxDone=new CompletableFuture<Void>();
        Thread fx=null;
        try {
            fixture.start(scope->{
                fixture.gpu.start(scope);
                started.complete(null);
                return new WorldStreamSourceController.GpuOwner() {
                    public CompletableFuture<WorldStreamTransport.Session> session() { return opening.copy(); }
                    public CompletableFuture<Void> closeAsync() { return fixture.gpu.closeAsync(); }
                };
            });
            fixture.controller.camera(new WorldStreamTransport.Camera(1,1,80,50,new TerrainCamera(0,0,110,0,-.8)));
            started.get(5,TimeUnit.SECONDS);
            fixture.gpu.session.initialized().get(5,TimeUnit.SECONDS);
            var field=WorldStreamTransport.Session.class.getDeclaredField("lock");
            field.setAccessible(true);
            Object parentGate=field.get(fixture.gpu.session);
            synchronized(parentGate) {
                opening.complete(fixture.gpu.session);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                boolean initialBlocked=false;
                while(!initialBlocked && System.nanoTime()<until) {
                    for(var entry:Thread.getAllStackTraces().entrySet()) {
                        if(!entry.getKey().getName().equals("world-source-controller")) continue;
                        for(var line:entry.getValue()) {
                            if(line.getClassName().equals(WorldStreamTransport.Session.class.getName())
                                    && line.getMethodName().equals("camera")) initialBlocked=true;
                        }
                    }
                    if(!initialBlocked) Thread.sleep(1);
                }
                check(initialBlocked,"Actual initial Session.camera is held at its metadata gate");
                fx=Thread.ofPlatform().daemon().name("authored-fx-camera").start(()->{
                    try {
                        fixture.controller.camera(new WorldStreamTransport.Camera(2,1,80,50,new TerrainCamera(1,0,110,0,-.8)));
                        fxDone.complete(null);
                    } catch(Throwable failure) { fxDone.completeExceptionally(failure); }
                });
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(fx.getState()!=Thread.State.BLOCKED && System.nanoTime()<until) Thread.sleep(1);
                check(fx.getState()==Thread.State.BLOCKED,"FX metadata update waits the same publication gate");
                var current=WorldStreamSourceController.class.getDeclaredField("camera");
                current.setAccessible(true);
                check(((WorldStreamTransport.Camera)current.get(fixture.controller)).revision()==1,
                        "FX revision2 cannot publish ahead of held initial revision1 forwarding");
            }
            fxDone.get(5,TimeUnit.SECONDS);
            fixture.until(()->fixture.controller.snapshot().seed()!=null);
            var frame=fixture.nextFrame();
            try {
                check(frame.cameraRevision()==2 && fixture.controller.mayPresent(frame),
                        "Actual accepted first frame belongs to latest monotonic camera");
                fixture.controller.presented(frame);
            } finally { fixture.controller.discard(frame); }
        } finally {
            opening.complete(fixture.gpu.session);
            if(fx!=null) fx.join(1000);
            fixture.close();
        }
    }

    private static void initialSeedHeldReplacementCameraAndPresentationDrain() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var files =
                new AuthoredTerrainSource.Files() {
                    @Override
                    @SuppressWarnings("try")
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1018) {
                            try (var registration = token.onCancel(() -> canceled.complete(null))) {
                                entered.complete(null);
                                release.join();
                                token.check();
                            }
                        }
                        return super.stat(id, token);
                    }
                };
        var fixture = new Fixture(files, 10);
        WorldStreamSourceController.Presentation held = null;
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            check(
                    fixture.controller.snapshot().seed().camera() != null,
                    "First real source produces seed without prior camera");
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller.snapshot().prepared() == 9);
            check(
                    fixture.commitLines().size() == 9,
                    "Nine incrementally committed source-qualified tiles");
            fixture.controller.neighborhood(fixture.tiles.subList(1, 10));
            fixture.until(entered::isDone);
            check(
                    fixture.commitLines().size() == 9,
                    "Demand alone does not remove any outgoing current member");
            fixture.camera(2);
            held = fixture.nextFrame();
            check(
                    held.cameraRevision() == 2 && (held.pixels().get(0) & 255) == 9,
                    "Old complete table keeps camera frames while new root source is held");
            check(fixture.controller.mayPresent(held), "Pre-upload exact frame/source gate");
            release.complete(null);
            fixture.until(() -> fixture.commitLines().size() == 10);
            fixture.pump();
            check(
                    fixture.controller.mayPresent(held),
                    "New COMMITTED cannot invalidate an entered FX handshake");
            check(
                    fixture.controller.presented(held),
                    "Actual old upload ack remains valid through held newer commit");
            held = null;
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    fixture.pool.stats().leaseClaims() == 9,
                    "Outgoing base retires only after replacement pixels accepted");
            fixture.camera(3);
            held = fixture.nextFrame();
            var closing = fixture.controller.closeAsync();
            fixture.until(() -> fixture.gpu.physical != null && fixture.gpu.physical.isDone());
            check(
                    !closing.isDone(),
                    "Actual process drain does not discard a borrowed FX presentation");
            fixture.controller.discard(held);
            held = null;
            fixture.until(closing::isDone);
            closing.get(5, TimeUnit.SECONDS);
            check(
                    fixture.pool.stats().leaseClaims() == 0,
                    "All unique source bases retired on close");
            check(!canceled.isDone(), "Useful held candidate was allowed to finish");
        } catch (Exception | Error failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            release.complete(null);
            if (held != null) fixture.controller.discard(held);
            fixture.close();
        }
    }

    private static void supersededHeldSourceCancelsBeforeStalePredicateAndKeepsSession()
            throws Exception {
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var fixture =
                new Fixture(
                        new AuthoredTerrainSource.Files() {
                            @Override
                            @SuppressWarnings("try")
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                if (id == 1002) {
                                    try (var registration =
                                            token.onCancel(() -> canceled.complete(null))) {
                                        entered.complete(null);
                                        release.join();
                                        token.check();
                                    }
                                }
                                return super.stat(id, token);
                            }
                        },
                        3);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(List.of(fixture.tiles.get(1)));
            fixture.until(entered::isDone);
            fixture.controller.neighborhood(List.of(fixture.tiles.get(2)));
            fixture.until(canceled::isDone);
            check(
                    fixture.files.opens(1004) == 0,
                    "Successor cannot start before actual canceled source work drains");
            release.complete(null);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    !fixture.gpu.session.completion().isDone(),
                    "Explicit stop-before-stale cancellation preserves live session");
            check(
                    fixture.files.opens(1002) == 0 && fixture.files.opens(1004) > 0,
                    "Canceled Root never opens and latest actual Root is qualified");
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    private static void missingRootIsFinitePerDemandAndDoesNotInvalidateCurrentScope()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 3);
        fixture.files.sources.remove(1002L);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            fixture.controller.neighborhood(fixture.tiles);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller
                                            .snapshot()
                                            .detail()
                                            .contains("1 unavailable"));
            int probes = fixture.files.stats.get(1002L).get();
            for (int i = 0; i < 30; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(
                    fixture.files.stats.get(1002L).get() == probes,
                    "Missing Root has finite per-demand outcome, no retry loop");
            check(
                    fixture.controller.snapshot().detail().contains("SOURCE_INPUT"),
                    "Finite actual source-origin reason remains visible");
            check(
                    fixture.pool.stats().leaseClaims() == 2,
                    "Missing geometry never becomes height fallback or drops valid current tiles");
            fixture.camera(2);
            var frame = fixture.nextFrame();
            try {
                check(
                        fixture.controller.mayPresent(frame),
                        "Missing Root leaves existing Map/WDT scope valid");
            } finally {
                fixture.controller.discard(frame);
            }
        } finally {
            fixture.close();
        }
    }

    private static void heldPresentationBackpressuresTwelveUniqueOwnersAndKeepsFinalFrame()
            throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 13);
        try {
            fixture.start();
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.controller.neighborhood(fixture.tiles.subList(0, 9));
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.READY
                                    && fixture.controller.snapshot().prepared() == 9);
            fixture.controller.neighborhood(fixture.tiles.subList(4, 13));
            // Deliberately leave new viewport pixels unaccepted while source/child keep
            // progressing.
            fixture.until(
                    () ->
                            fixture.controller.snapshot().phase()
                                            == WorldStreamSourceController.Phase.WAITING_CAPACITY
                                    && fixture.pool.stats().leaseClaims() == 12);
            check(
                    fixture.files.opens(1024) == 0,
                    "No thirteenth construction owner while old pixels remain presented");
            check(
                    fixture.commitLines().size() == 12,
                    "Current and presented aliases share exactly twelve unique source bases");
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    fixture.files.opens(1024) > 0,
                    "Accepted current pixels unblock remaining actual source acquisition");
            check(
                    fixture.pool.stats().leaseClaims() == 9,
                    "Final accepted table retires outgoing sources without a new camera event");
        } finally {
            fixture.close();
        }
    }

    @SuppressWarnings("try")
    private static void poolReleaseRetriesWithoutDemandOrParentCapacityChange() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var held = AuthoredTerrainPoolPressure.hold(fixture.pool);
        try {
            fixture.start();
            fixture.until(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.WAITING_CAPACITY);
            // Let both refusal/unused-credit acknowledgements settle without advancing retry time.
            for (int i = 0; i < 50; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            long capacity = fixture.gpu.session.capacityRevision();
            held.close();
            check(
                    fixture.gpu.session.capacityRevision() == capacity,
                    "Pool release itself sends no parent capacity event");
            fixture.clock.set(600_000_000L);
            fixture.until(() -> fixture.controller.snapshot().seed() != null);
            fixture.camera(1);
            fixture.untilPresented(
                    () ->
                            fixture.controller.snapshot().phase()
                                    == WorldStreamSourceController.Phase.READY);
            check(
                    fixture.pool.stats().leaseClaims() == 1,
                    "Bounded retry recovers actual source material without new demand");
        } finally {
            held.close();
            fixture.close();
        }
    }

    private static void closeDuringActualInitialWdtQualificationIsCleanAndWaitsSourceDrain()
            throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var fixture =
                new Fixture(
                        new AuthoredTerrainSource.Files() {
                            @Override
                            @SuppressWarnings("try")
                            FileTypes.FileStat stat(long id, Cancellation token) {
                                if (armed.get() && id == 1) {
                                    try (var registration =
                                            token.onCancel(() -> canceled.complete(null))) {
                                        entered.complete(null);
                                        release.join();
                                        token.check();
                                    }
                                }
                                return super.stat(id, token);
                            }
                        },
                        1);
        try {
            fixture.start(
                    scope -> {
                        starts.incrementAndGet();
                        return fixture.gpu.start(scope);
                    },
                    () -> armed.set(true));
            entered.get(5, TimeUnit.SECONDS);
            var closing = fixture.controller.closeAsync();
            canceled.get(5, TimeUnit.SECONDS);
            check(
                    !closing.isDone(),
                    "Initial Map/WDT proof must actually drain before close succeeds");
            check(starts.get() == 0, "No queued GPU owner while initial source scope is held");
            release.complete(null);
            fixture.until(closing::isDone);
            closing.get(5, TimeUnit.SECONDS);
            check(
                    fixture.controller.snapshot().phase()
                            == WorldStreamSourceController.Phase.CLOSED,
                    "Expected early-hide qualification cancellation is clean close, never"
                        + " quarantine");
            check(
                    fixture.pool.stats().leaseClaims() == 0,
                    "Early hide has no issued geometry/material owner");
        } finally {
            release.complete(null);
            fixture.close();
        }
    }

    private static void queuedGpuOwnerLateCloseIsPartOfControllerDrain() throws Exception {
        var fixture = new Fixture(new AuthoredTerrainSource.Files(), 1);
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var physical = new CompletableFuture<Void>();
        try {
            fixture.start(
                    scope -> {
                        entered.complete(null);
                        release.join();
                        return new WorldStreamSourceController.GpuOwner() {
                            public CompletableFuture<WorldStreamTransport.Session> session() {
                                return CompletableFuture.failedFuture(
                                        new java.util.concurrent.CancellationException());
                            }

                            public CompletableFuture<Void> closeAsync() {
                                return physical.copy();
                            }
                        };
                    });
            entered.get(5, TimeUnit.SECONDS);
            var closing = fixture.controller.closeAsync();
            check(!closing.isDone(), "In-flight GPU start is still an owner");
            release.complete(null);
            for (int i = 0; i < 20; i++) {
                fixture.pump();
                Thread.sleep(2);
            }
            check(!closing.isDone(), "Failed/no-session opening is not actual queued-owner drain");
            physical.complete(null);
            fixture.until(closing::isDone);
            closing.get(5, TimeUnit.SECONDS);
            check(
                    fixture.files.opens(2) == 0,
                    "No tile acquisition before actual session initialization");
        } finally {
            release.complete(null);
            physical.complete(null);
            fixture.close();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @SuppressWarnings("try")
    private static final class Fixture implements AutoCloseable {
        final AuthoredTerrainSource.Files files;
        final TerrainAtlasPool pool = new TerrainAtlasPool();
        final AuthoredNativeMap tables;
        final WorldService world;
        final List<WorldFormats.Tile> tiles;
        final Path directory;
        final ConcurrentLinkedQueue<Runnable> fx = new ConcurrentLinkedQueue<>();
        final AtomicLong clock = new AtomicLong();
        final Gpu gpu = new Gpu();
        WorldStreamSourceController controller;

        Fixture(AuthoredTerrainSource.Files files, int count) throws Exception {
            this.files = files;
            tiles = install(files, count);
            tables = new AuthoredNativeMap(files.files());
            world = new WorldService(tables.tables(), files.files(), pool);
            directory = Files.createTempDirectory("world-controller-");
        }

        void start() {
            start(gpu::start);
        }

        void start(WorldStreamSourceController.GpuStarter starter) {
            start(starter, () -> {});
        }

        void start(WorldStreamSourceController.GpuStarter starter, Runnable beforeController) {
            var map = new WorldService.MapInfo(7, "Authored", null, 0, 1L);
            var inventory = world.inventory(map, Cancellation.NONE).join();
            beforeController.run();
            controller =
                    new WorldStreamSourceController(
                            new WorldStreamSourceController.Request(
                                    1,
                                    world,
                                    map,
                                    inventory,
                                    tiles.getFirst(),
                                    null,
                                    WorldCurrentArea.Index.of(List.of())),
                            starter,
                            () -> {},
                            fx::add,
                            clock::get);
        }

        void camera(long revision) {
            var seed = controller.snapshot().seed();
            var initial = seed.camera();
            controller.camera(
                    new WorldStreamTransport.Camera(
                            revision,
                            1,
                            80,
                            50,
                            new TerrainCamera(
                                    initial.x() + revision,
                                    initial.y(),
                                    initial.z(),
                                    initial.yaw(),
                                    initial.pitch())));
        }

        void pump() {
            controller.pulse();
            Runnable task;
            while ((task = fx.poll()) != null) task.run();
            if (controller.snapshot().phase() == WorldStreamSourceController.Phase.FAILED)
                throw new AssertionError(controller.snapshot().detail());
        }

        void until(BooleanSupplier condition) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            boolean matched = false;
            while (!(matched = condition.getAsBoolean()) && System.nanoTime() < until) {
                pump();
                Thread.sleep(2);
            }
            if (!matched) {
                Thread.getAllStackTraces()
                        .forEach(
                                (thread, stack) -> {
                                    if (thread.getName().equals("world-source-controller")) {
                                        System.err.println(thread);
                                        for (var line : stack) System.err.println("  " + line);
                                    }
                                });
                for (String name : List.of("borrowed", "queued", "commit")) {
                    var field = WorldStreamSourceController.class.getDeclaredField(name);
                    field.setAccessible(true);
                    System.err.println(name + "=" + field.get(controller));
                }
                if (gpu.scratch != null)
                    System.err.println(
                            Files.readString(
                                    gpu.scratch.getParent().resolve("authored-commands.log")));
            }
            check(matched, "Timed out: " + controller.snapshot());
        }

        void untilPresented(BooleanSupplier condition) throws Exception {
            until(
                    () -> {
                        controller
                                .takePresentation()
                                .ifPresent(
                                        frame -> {
                                            try {
                                                if (controller.mayPresent(frame))
                                                    controller.presented(frame);
                                            } finally {
                                                controller.discard(frame);
                                            }
                                        });
                        return condition.getAsBoolean();
                    });
        }

        WorldStreamSourceController.Presentation nextFrame() throws Exception {
            var held = new WorldStreamSourceController.Presentation[1];
            until(
                    () -> {
                        held[0] = controller.takePresentation().orElse(null);
                        return held[0] != null;
                    });
            return held[0];
        }

        List<String> commitLines() {
            try {
                var path = gpu.scratch.getParent().resolve("authored-commits.log");
                return Files.exists(path) ? Files.readAllLines(path) : List.of();
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }

        public void close() throws Exception {
            if (controller != null) {
                var closing = controller.closeAsync();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!closing.isDone() && System.nanoTime() < until) {
                    controller.pulse();
                    Thread.sleep(2);
                }
                closing.get(1, TimeUnit.SECONDS);
            }
            world.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }

        final class Gpu implements WorldStreamSourceController.GpuOwner {
            volatile WorldStreamTransport.Session session;
            volatile CompletableFuture<Void> physical;
            volatile Path scratch;

            WorldStreamSourceController.GpuOwner start(String scope) {
                try {
                    var prepared =
                            WorldStreamTransport.prepare(
                                    directory,
                                    new WorldStreamTransport.Spec(
                                            scope, 1, WorldStreamTransport.Backend.VULKAN));
                    session =
                            prepared.launch(
                                    arguments -> {
                                        scratch = Path.of(arguments.get(1));
                                        return new ProcessBuilder(
                                                        Path.of(
                                                                        System.getProperty(
                                                                                "java.home"),
                                                                        "bin",
                                                                        "java")
                                                                .toString(),
                                                        "--enable-native-access=ALL-UNNAMED",
                                                        "-cp",
                                                        System.getProperty("java.class.path"),
                                                        "com.vibeminer.rendering.bgfx.AuthoredWorldControllerChild",
                                                        arguments.get(1),
                                                        arguments.get(3))
                                                .start();
                                    });
                    return this;
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            }

            public CompletableFuture<WorldStreamTransport.Session> session() {
                return CompletableFuture.completedFuture(session);
            }

            public synchronized CompletableFuture<Void> closeAsync() {
                if (physical == null) physical = session.closeAsync().thenApply(receipt -> null);
                return physical.copy();
            }
        }
    }

    private static List<WorldFormats.Tile> install(AuthoredTerrainSource.Files files, int count) {
        var out = new ArrayList<WorldFormats.Tile>();
        byte[] wdt = files.sources.get(1L).clone();
        var index = ByteBuffer.wrap(wdt).order(ByteOrder.LITTLE_ENDIAN);
        byte[] initial = files.sources.get(2L);
        for (int i = 0; i < count; i++) {
            long root = i == 0 ? 2 : 1000 + 2L * i, tex = i == 0 ? 3 : root + 1;
            var tile = new WorldFormats.Tile(7, 19 + i, root, 0, 0, 0, 0, tex, 0, 0);
            out.add(tile);
            int ordinal = tile.x() * 64 + tile.y();
            index.putInt(60 + ordinal * 8, 1)
                    .putInt(32836 + ordinal * 32, (int) root)
                    .putInt(32836 + ordinal * 32 + 12, (int) tex);
            if (i > 0) {
                byte[] geometry = initial.clone();
                var data = ByteBuffer.wrap(geometry).order(ByteOrder.LITTLE_ENDIAN);
                for (int chunk = 0; chunk < 256; chunk++) {
                    int at = 12 + chunk * 724 + 8 + 108;
                    data.putFloat(at, (float) (data.getFloat(at) - i * WorldFormats.TILE_SIZE));
                }
                files.sources.put(root, geometry);
                files.sources.put(tex, files.sources.get(3L));
            }
        }
        files.sources.put(1L, wdt);
        return List.copyOf(out);
    }
}
