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
        var delivered = new ArrayList<WorldTextureSnapshot>();
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
            require(delivered.getFirst().scene() == latest, "Snapshot belongs to exact current scene");
            require(delivered.getFirst().atlasCount() == 0, "Unsupported provider retains honest height fallback");
            delivered.removeFirst().close();
            require(status.stream().anyMatch(WorldTextureLoading.Status::busy), "Loading is visible");
            loader.request(source, map(4), TILES, scene());
            await(ui, () -> calls.size() == 3, "last pending read");
            loader.close();
            var afterClose = WorldTerrainTextureSource.BatchResult.unsupported(TILES);
            calls.stream().skip(2).findFirst().orElseThrow().result().complete(afterClose);
            await(ui, () -> isClosed(afterClose), "Close consumes late batch even before UI publication");
            pump(ui);
            require(delivered.isEmpty(), "Closed loader never publishes late work");
        } finally {
            loader.close();
            for (var call : calls) call.result().complete(WorldTerrainTextureSource.BatchResult.unsupported(TILES));
            for (var snapshot : delivered) snapshot.close();
        }
        System.out.println("WorldTextureLoadingTest PASS: off-owner reads, latest-only work, activity, rejection and late close");
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
