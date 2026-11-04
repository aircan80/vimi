package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Authored delayed providers; no native renderer, real assets or JavaFX toolkit. */
public final class WorldTextureLoadingTest {
    private static final WorldFormats.Tile TILE = new WorldFormats.Tile(7, 19, 2, 0, 0);
    private static final List<WorldFormats.Tile> TILES = List.of(TILE);
    private WorldTextureLoadingTest() {}

    private record Call(long map, Cancellation token,
            CompletableFuture<WorldTerrainTextureSource.BatchResult> result) {}

    public static void main(String[] args) throws Exception {
        var ui = new ConcurrentLinkedQueue<Runnable>();
        var calls = new ConcurrentLinkedQueue<Call>();
        var delivered = new ArrayList<WorldTextureLoading.Result>();
        var status = new ArrayList<WorldTextureLoading.Status>();
        Thread owner = Thread.currentThread();
        BuildWorld source = (BuildWorld) Proxy.newProxyInstance(BuildWorld.class.getClassLoader(),
                new Class<?>[] {BuildWorld.class}, (proxy, method, values) -> {
                    if (!method.getName().equals("terrainTextures")) throw new AssertionError(method.getName());
                    require(Thread.currentThread() != owner, "Provider invocation must stay off the UI owner");
                    var result = new CompletableFuture<WorldTerrainTextureSource.BatchResult>();
                    calls.add(new Call(((WorldService.MapInfo) values[0]).id(), (Cancellation) values[2], result));
                    return result;
                });
        var loader = new WorldTextureLoading(ui::add, status::add, delivered::add);
        try {
            var first = scene();
            var second = scene();
            var latest = scene();
            loader.request(source, map(1), TILES, first);
            await(ui, () -> calls.size() == 1, "first source request");
            loader.request(source, map(2), TILES, second);
            loader.request(source, map(3), TILES, latest);
            await(ui, () -> calls.peek().token().isCanceled(), "asynchronous retirement");
            require(calls.size() == 1, "Only one active read; intermediate view stays a descriptor");
            var oldBatch = WorldTerrainTextureSource.BatchResult.unsupported(TILES);
            calls.peek().result().complete(oldBatch);
            await(ui, () -> calls.size() == 2, "latest source after old request drains");
            require(delivered.isEmpty(), "Retired result must not publish");
            require(calls.stream().map(Call::map).toList().equals(List.of(1L, 3L)),
                    "Intermediate map never reads");
            requireClosed(oldBatch);
            calls.stream().skip(1).findFirst().orElseThrow().result()
                    .complete(WorldTerrainTextureSource.BatchResult.unsupported(TILES));
            await(ui, () -> delivered.size() == 1, "latest snapshot delivery");
            require(delivered.getFirst().snapshot().scene() == latest, "Snapshot belongs to exact current scene");
            require(delivered.getFirst().snapshot().atlasCount() == 0, "Unsupported provider retains honest height fallback");
            delivered.removeFirst().snapshot().close();
            require(status.stream().anyMatch(WorldTextureLoading.Status::busy), "Loading is visible");
            var failedScene = scene();
            loader.request(source, map(4), TILES, failedScene);
            await(ui, () -> calls.size() == 3, "failing request");
            calls.stream().skip(2).findFirst().orElseThrow().result()
                    .completeExceptionally(new IllegalStateException("Authored source failure"));
            await(ui, () -> delivered.size() == 1, "terminal failure outcome");
            var failure = delivered.removeFirst();
            require(failure.scene() == failedScene && failure.snapshot() == null && failure.issue() != null,
                    "Failure carries its exact candidate identity and resolves without an atlas");
            loader.request(source, map(5), TILES, scene());
            await(ui, () -> calls.size() == 4, "last pending read");
            loader.close();
            var afterClose = WorldTerrainTextureSource.BatchResult.unsupported(TILES);
            calls.stream().skip(3).findFirst().orElseThrow().result().complete(afterClose);
            await(ui, () -> isClosed(afterClose), "Close consumes late batch even before UI publication");
            pump(ui);
            require(delivered.isEmpty(), "Closed loader never publishes late work");
        } finally {
            loader.close();
            for (var call : calls) call.result().complete(WorldTerrainTextureSource.BatchResult.unsupported(TILES));
            for (var outcome : delivered) { if (outcome.snapshot() != null) outcome.snapshot().close(); }
        }
        workerStartErrorDoesNotLeavePhantomRetirement();
        idleWaitsProviderAndFxRetirement();
        idleWaitsWorkerFinallyAfterFx();
        idleFailuresDoNotHang();
        System.out.println("WorldTextureLoadingTest PASS: off-owner reads, latest-only work, activity, rejection and late close");
    }

    private static void workerStartErrorDoesNotLeavePhantomRetirement() throws Exception {
        var original = new AssertionError("Authored worker creation failure");
        var delivered = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new CompletableFuture<Void>();
        var loader = new WorldTextureLoading(task -> task.run(), ignored -> {}, ignored -> delivered.incrementAndGet());
        try {
            var field = WorldTextureLoading.class.getDeclaredField("worker");field.setAccessible(true);
            ((java.util.concurrent.ThreadPoolExecutor)field.get(loader)).setThreadFactory(task -> { throw original; });
            try {
                loader.request(source(CompletableFuture.completedFuture(
                        WorldTerrainTextureSource.BatchResult.unsupported(TILES)),entered),
                        map(1),TILES,scene());
                throw new AssertionError("Expected original worker creation error");
            } catch (AssertionError observed) { require(observed == original,"Exact fatal Error preserved"); }
            require(!loader.hasActiveWork(), "No phantom active descriptor after worker creation fails");
            require(!entered.isDone() && delivered.get() == 0, "No provider call or fabricated terminal result");
            var viewField = WorldTextureLoading.class.getDeclaredField("view");viewField.setAccessible(true);
            var view = viewField.get(loader);
            var strong = view.getClass().getDeclaredField("strongScene");strong.setAccessible(true);
            require(strong.get(view) == null, "Failed unstarted job retires its strong scene alias");
            try {
                loader.whenIdle().get(1,TimeUnit.SECONDS);
                throw new AssertionError("Failed worker creation cannot certify successful retirement");
            } catch (java.util.concurrent.ExecutionException expected) { }
            expectFailed(loader.whenIdle()); // A fresh fence cannot turn the sticky failure positive.
        } finally { loader.close(); }
    }

    private static BuildWorld source(CompletableFuture<WorldTerrainTextureSource.BatchResult> result,
            CompletableFuture<Void> entered) {
        return (BuildWorld) Proxy.newProxyInstance(BuildWorld.class.getClassLoader(),
                new Class<?>[] {BuildWorld.class}, (proxy, method, values) -> {
                    if (!method.getName().equals("terrainTextures")) throw new AssertionError(method.getName());
                    entered.complete(null);
                    return result;
                });
    }

    private static void idleWaitsProviderAndFxRetirement() throws Exception {
        for (boolean close : List.of(false, true)) {
            var ui = new ConcurrentLinkedQueue<Runnable>();
            var result = new CompletableFuture<WorldTerrainTextureSource.BatchResult>();
            var entered = new CompletableFuture<Void>();
            var loader = new WorldTextureLoading(ui::add, ignored -> {},
                    ignored -> { throw new AssertionError("Retired result published"); });
            try {
                loader.request(source(result, entered), map(1), TILES, scene());
                entered.get(5, TimeUnit.SECONDS);
                var idle = loader.whenIdle();
                var foreign = loader.whenIdle();
                foreign.complete(null);
                if (close) loader.close(); else loader.hide();
                require(!idle.isDone(), "Hide/close cannot retire held original provider work");
                var batch = WorldTerrainTextureSource.BatchResult.unsupported(TILES);
                result.complete(batch);
                await(new ConcurrentLinkedQueue<>(), () -> !ui.isEmpty(), "queued FX completion");
                require(!idle.isDone(), "Worker completion alone cannot retire queued FX mailbox");
                pump(ui);
                idle.get(5, TimeUnit.SECONDS);
                requireClosed(batch);
                require(!loader.hasActiveWork(), "FX retirement clears active descriptor");
            } finally {
                result.complete(WorldTerrainTextureSource.BatchResult.unsupported(TILES));
                loader.close();
                pump(ui);
            }
        }
    }

    private static void idleWaitsWorkerFinallyAfterFx() throws Exception {
        var ui = new ConcurrentLinkedQueue<Runnable>();
        var scheduled = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var result = CompletableFuture.completedFuture(WorldTerrainTextureSource.BatchResult.unsupported(TILES));
        var loader = new WorldTextureLoading(task -> {
            ui.add(task);
            scheduled.complete(null);
            release.join();
        }, ignored -> {}, delivered -> delivered.snapshot().close());
        try {
            loader.request(source(result, new CompletableFuture<>()), map(1), TILES, scene());
            scheduled.get(5, TimeUnit.SECONDS);
            var idle = loader.whenIdle();
            pump(ui);
            require(!loader.hasActiveWork() && !idle.isDone(),
                    "Empty active pointer and retired FX mailbox do not prove worker finally");
            release.complete(null);
            idle.get(5, TimeUnit.SECONDS);
        } finally { release.complete(null); loader.close(); pump(ui); }
    }

    private static void idleFailuresDoNotHang() throws Exception {
        var completed = CompletableFuture.completedFuture(WorldTerrainTextureSource.BatchResult.unsupported(TILES));
        var scheduling = new WorldTextureLoading(task -> { throw new IllegalStateException("Rejected FX queue"); },
                ignored -> {}, ignored -> {});
        try {
            scheduling.request(source(completed, new CompletableFuture<>()), map(1), TILES, scene());
            expectFailed(scheduling.whenIdle());
        } finally { scheduling.close(); }
        var rejected = new WorldTextureLoading(task -> task.run(), ignored -> {}, ignored -> {});
        try {
            var field = WorldTextureLoading.class.getDeclaredField("worker");
            field.setAccessible(true);
            ((java.util.concurrent.ThreadPoolExecutor) field.get(rejected)).shutdown();
            rejected.request(source(CompletableFuture.completedFuture(
                    WorldTerrainTextureSource.BatchResult.unsupported(TILES)), new CompletableFuture<>()),
                    map(1), TILES, scene());
            expectFailed(rejected.whenIdle());
        } finally { rejected.close(); }
        var ui = new ConcurrentLinkedQueue<Runnable>();
        var delivery = new WorldTextureLoading(ui::add, ignored -> {},
                ignored -> { throw new IllegalStateException("Authored consumer cleanup failure"); });
        try {
            delivery.request(source(CompletableFuture.completedFuture(
                    WorldTerrainTextureSource.BatchResult.unsupported(TILES)), new CompletableFuture<>()),
                    map(1), TILES, scene());
            var idle = delivery.whenIdle();
            await(new ConcurrentLinkedQueue<>(), () -> !ui.isEmpty(), "failing FX delivery");
            try { pump(ui); throw new AssertionError("Delivery must throw"); }
            catch (IllegalStateException expected) { }
            expectFailed(idle);
        } finally { delivery.close(); pump(ui); }
    }

    private static void expectFailed(CompletableFuture<Void> future) throws Exception {
        try { future.get(5, TimeUnit.SECONDS); throw new AssertionError("Expected exceptional retirement"); }
        catch (java.util.concurrent.ExecutionException expected) { }
    }

    private static WorldService.MapInfo map(long id) {
        return new WorldService.MapInfo(id, "Authored", null, 0, 1L);
    }

    private static TerrainScene scene() {
        return new TerrainScene(List.of(TerrainScene.Tile.copyOf(7, 19,
                new float[] {10, -1, 0, 10, 1, 0, 10, 0, 1}, new int[] {0, 1, 2}, 1)));
    }

    private static void requireClosed(WorldTerrainTextureSource.BatchResult batch) {
        require(isClosed(batch), "Retired batch must release its owned claims");
    }

    private static boolean isClosed(WorldTerrainTextureSource.BatchResult batch) {
        try { batch.retainAtlas(7, 19); return false; }
        catch (IllegalStateException expected) { return true; }
    }

    private static void await(ConcurrentLinkedQueue<Runnable> ui, BooleanSupplier condition,
            String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            pump(ui);
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static void pump(ConcurrentLinkedQueue<Runnable> ui) {
        Runnable task;
        while ((task = ui.poll()) != null) task.run();
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
