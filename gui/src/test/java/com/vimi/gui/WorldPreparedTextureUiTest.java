package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.GpuTerrainSceneFile;
import com.vibeminer.rendering.bgfx.TerrainTextureStatus;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

/** Authored checked source data and controlled processes; proves presentation ownership, not native RGB. */
public final class WorldPreparedTextureUiTest {
    private static final int FIRST = 0xff337755, MOVED = 0xff2255aa, TARGET = 0xffaa3377;
    private WorldPreparedTextureUiTest() {}
    private static WorldView debugView;
    private static final class Frames implements GpuPreviewService.Frames {
        final AtomicReference<GpuPreviewService.Frame> next = new AtomicReference<>();
        public GpuPreviewService.Frame readLatest(long after) { return next.getAndSet(null); }
        public void close() { next.set(null); }
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        try { scenario(false, false, false); scenario(true, false, false);
            scenario(false, true, false); scenario(false, false, true); scenario(false, false, false, true); }
        finally { Platform.exit(); }
        System.out.println("Prepared terrain bundle FX PASS: initial/full-window and replacement atomicity, old-camera continuity, partial/failure outcomes and ownership");
    }

    private static void scenario(boolean partial, boolean fail, boolean supersede) throws Exception {
        scenario(partial, fail, supersede, false);
    }

    private static void scenario(boolean partial, boolean fail, boolean supersede, boolean heldCpu) throws Exception {
        var fixture = heldCpu ? TerrainGpuPreviewTextureFixture.streamingWindow()
                : TerrainGpuPreviewTextureFixture.neighborhood(partial);
        var cpuEntered = new java.util.concurrent.CountDownLatch(1);
        var cpuRelease = new java.util.concurrent.CountDownLatch(1);
        var deliveries = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var children = new CopyOnWriteArrayList<GpuPreviewServiceTest.FakeProcess>();
        var frames = new CopyOnWriteArrayList<Frames>();
        var sceneCounts = new CopyOnWriteArrayList<Integer>();
        var annexes = new CopyOnWriteArrayList<Boolean>();
        var reads = new AtomicInteger();
        var tileReads = new AtomicInteger();
        var sourceGate = new AtomicReference<>(new CompletableFuture<Void>());
        var materialToken = new AtomicReference<Cancellation>();
        var service = new GpuPreviewService(Files.createTempDirectory("vimi-prepared-world-"),
                (path, mesh, appearance, parts) -> { throw new AssertionError("Terrain uses its own codec"); },
                (path, mapping) -> {
                    for (var old : children) require(!old.isAlive(), "One live native owner");
                    sceneCounts.add(GpuTerrainSceneFile.read(path).tiles().size());
                    annexes.add(Files.isRegularFile(path.resolveSibling("terrain.vatx")));
                    var child = new GpuPreviewServiceTest.FakeProcess(true);
                    children.add(child);
                    return child;
                }, path -> { var source = new Frames(); frames.add(source); return source; },
                Duration.ofSeconds(20), Duration.ofMillis(10));
        WorldView view = null;
        Stage stage = null;
        BuildWorld source = new BuildWorld() {
            public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
                offFx(); return CompletableFuture.completedFuture(new WorldService.Catalog(
                        List.of(TerrainGpuPreviewTextureFixture.mapInfo()), List.of(), List.of()));
            }
            public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo map, Cancellation token) {
                offFx(); return CompletableFuture.completedFuture(new WorldService.Inventory(
                        map, new WorldFormats.World(fixture.tiles, false), null));
            }
            public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
                offFx(); tileReads.incrementAndGet(); return CompletableFuture.completedFuture(
                        new WorldService.LoadedTile(tile, fixture.terrain(tile), null));
            }
            public CompletableFuture<WorldTerrainTextureSource.BatchResult> terrainTextures(WorldService.MapInfo map,
                    List<WorldFormats.Tile> tiles, Cancellation token,
                    java.util.function.Consumer<WorldTerrainTextureSource.Progress> progress) {
                offFx(); reads.incrementAndGet(); materialToken.set(token);
                return sourceGate.get().thenCompose(ignored -> fail
                        ? CompletableFuture.failedFuture(new IllegalStateException("Authored unavailable material source"))
                        : fixture.batch(tiles, token, progress));
            }
            public CompletableFuture<Void> closeAsync() { throw new AssertionError("Borrowed source must remain open"); }
        };
        try {
            var ownedView = fx(() -> heldCpu ? new WorldView(() -> source, () -> 0, service,
                    (scene, camera, width, height, textures, canceled) -> {
                        require(!textures.isEmpty(), "Held CPU job owns scoped material borrows");
                        cpuEntered.countDown();
                        try { require(cpuRelease.await(12, TimeUnit.SECONDS), "Release held CPU ownership"); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                        return new TerrainRenderer.Frame(width, height, new int[width * height], 0, false);
                    }, deliveries::add) : new WorldView(() -> source, () -> 0, service));
            view = ownedView;
            debugView = ownedView;
            var ownedStage = fx(() -> {
                var result = new Stage(); result.setScene(new Scene(ownedView, 1100, 800));
                result.show(); ownedView.ensureLoaded(); return result;
            });
            stage = ownedStage;
            await(() -> fx(() -> ((ListView<?>) ownedView.lookup("#world-maps")).getItems().size() == 1), "catalog");
            fx(() -> { ((ListView<?>) ownedView.lookup("#world-maps")).getSelectionModel().selectFirst(); return null; });
            await(() -> fx(() -> !((Button) ownedView.lookup("#world-open")).isDisabled()), "inventory");
            fx(() -> {
                set(ownedView, "selected", fixture.tiles.getFirst());
                ((Button) ownedView.lookup("#world-open")).fire(); return null;
            });
            await(() -> tileReads.get() >= 2 && (reads.get() > 0 || !children.isEmpty()), "initial complete-window preparation");
            require(tileReads.get() == 2, "Initial window gathers both available neighboring sources before material read");
            require(children.isEmpty(), "No untextured initial child launches while material source is held");
            require(fx(() -> ownedView.lookup("#world-first-frame-activity").isVisible()
                    && ownedView.lookup("#world-texture-activity").isVisible()), "Cold geometry and material activity remains visible");
            sourceGate.get().complete(null);
            await(() -> children.size() == 1, "first combined child");
            require(sceneCounts.getFirst() == 2, "Initial publication is the full available two-tile window");
            require(annexes.getFirst() != fail, "Initial child receives material annex iff qualified");
            ready(children, 0, 2, partial, fail);
            require(fx(() -> ownedView.lookup("#world-texture-activity").isVisible()), "Native acknowledgement alone is not presentation");
            presentUntil(children, frames, 0, 1, FIRST,
                    () -> fx(() -> !ownedView.lookup("#world-texture-activity").isVisible()), "accepted initial bundle");
            if (fail) {
                require(fx(() -> ((Label) ownedView.lookup("#world-texture-status")).getText().contains("unavailable")),
                        "Failed source publishes explicit terminal height fallback");
                require(fixture.stats().leaseClaims() == 0, "Failed source retains no atlases");
                return;
            }
            int covered = partial ? 256 : 512;
            require(fx(() -> ((Label) ownedView.lookup("#world-texture-status")).getText()
                    .equals("Basic terrain textures · " + covered + "/512 regions")), "Full or partial initial coverage remains exact");
            sourceGate.set(new CompletableFuture<>());
            var oldScene = fx(() -> get(ownedView, "terrainScene"));
            fx(() -> {
                var camera = (TerrainRenderer.Camera) get(ownedView, "camera");
                set(ownedView, "camera", new TerrainRenderer.Camera(-2.5 * WorldFormats.TILE_SIZE,
                        camera.y(), camera.z(), camera.yaw(), camera.pitch()));
                invoke(ownedView, "loadNext"); invoke(ownedView, "requestFrame"); return null;
            });
            await(() -> reads.get() == 2, "replacement material read");
            require(children.size() == 1, "New geometry never launches a height-only replacement");
            require(fx(() -> get(ownedView, "terrainScene") == oldScene), "Current textured scene remains published during held source");
            long oldRevision = revision(children.getFirst());
            fx(() -> {
                var camera = (TerrainRenderer.Camera) get(ownedView, "camera");
                set(ownedView, "camera", camera.look(.15, 0));
                invoke(ownedView, "requestFrame"); return null;
            });
            await(() -> revision(children.getFirst()) > oldRevision, "camera controls continue during material wait");
            presentUntil(children, frames, 0, 100, MOVED,
                    () -> pixel(ownedView) == MOVED, "old textured renderer still presents camera-updated frames");
            require(fx(() -> ownedView.lookup("#world-texture-activity").isVisible()), "Old accepted frames cannot clear pending bundle activity");
            if (supersede) {
                var retiringToken = materialToken.get();
                var retiringGate = sourceGate.get();
                sourceGate.set(CompletableFuture.completedFuture(null));
                fx(() -> {
                    var camera = (TerrainRenderer.Camera) get(ownedView, "camera");
                    set(ownedView, "camera", new TerrainRenderer.Camera(-.5 * WorldFormats.TILE_SIZE,
                            camera.y(), camera.z(), camera.yaw(), camera.pitch()));
                    invoke(ownedView, "abandonUnusableCandidate");
                    invoke(ownedView, "loadNext"); invoke(ownedView, "requestFrame"); return null;
                });
                await(retiringToken::isCanceled, "outgoing material source cancellation");
                require(fx(() -> get(ownedView, "pendingPrepared") == null && get(ownedView, "geometryJob") == null),
                        "No third full geometry candidate allocates while canceled source still retains its old job");
                require(children.size() == 1 && reads.get() == 2, "Only a latest descriptor waits behind retiring source");
                retiringGate.complete(null);
            } else {
                var useful = fx(() -> get(ownedView, "pendingPrepared"));
                var usefulToken = materialToken.get();
                fx(() -> {
                    var camera = (TerrainRenderer.Camera) get(ownedView, "camera");
                    set(ownedView, "camera", new TerrainRenderer.Camera(-1.5 * WorldFormats.TILE_SIZE,
                            camera.y(), camera.z(), camera.yaw(), camera.pitch()));
                    invoke(ownedView, "abandonUnusableCandidate"); invoke(ownedView, "loadNext");
                    invoke(ownedView, "requestFrame"); return null;
                });
                require(!usefulToken.isCanceled() && fx(() -> get(ownedView, "pendingPrepared") == useful),
                        "A candidate that still covers the camera finishes instead of starving at a boundary");
                if (heldCpu) {
                    fx(() -> { ((javafx.scene.control.CheckBox) ownedView.lookup("#world-gpu-enabled")).fire(); return null; });
                    require(cpuEntered.await(5, TimeUnit.SECONDS), "Old CPU renderer enters scoped borrow");
                }
                sourceGate.get().complete(null);
                if (heldCpu) {
                    await(() -> fx(() -> get(ownedView, "terrainScene") != oldScene), "New bundle publishes while old CPU work retires");
                    fx(() -> { ((javafx.scene.control.CheckBox) ownedView.lookup("#world-gpu-enabled")).fire(); return null; });
                }
            }
            await(() -> children.size() == 2, "one combined replacement child");
            require(annexes.get(1) && sceneCounts.get(1) == 3, "Replacement contains geometry and all admitted textures together");
            ready(children, 1, 3, partial, false);
            presentUntil(children, frames, 1, 1, TARGET,
                    () -> pixel(ownedView) == TARGET && fx(() -> !ownedView.lookup("#world-texture-activity").isVisible()), "accepted replacement bundle");
            require(children.size() == 2 && reads.get() == (supersede ? 3 : 2),
                    "Only complete useful bundles launch a child; canceled intermediate never publishes");
            if (heldCpu) {
                sourceGate.set(new CompletableFuture<>());
                fx(() -> {
                    var camera = (TerrainRenderer.Camera) get(ownedView, "camera");
                    set(ownedView, "camera", new TerrainRenderer.Camera(-4.5 * WorldFormats.TILE_SIZE,
                            camera.y(), camera.z(), camera.yaw(), camera.pitch()));
                    invoke(ownedView, "loadNext"); invoke(ownedView, "requestFrame"); return null;
                });
                await(() -> tileReads.get() == 4, "Fourth raw tile loads independently");
                fx(() -> { invoke(ownedView, "advanceMaterials"); return null; });
                require(reads.get() == 2 && fx(() -> get(ownedView, "pendingPrepared") == null
                        && get(ownedView, "geometryJob") == null),
                        "Accepted GPU frame cannot admit third geometry/material owner while old CPU borrow lives");
                cpuRelease.countDown();
                await(() -> reads.get() == 3 && !deliveries.isEmpty(), "CPU finally returns ownership credits and wakes pending candidate");
                for (var callback : deliveries) for (var field : callback.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    var captured = field.get(callback);
                    require(captured == ownedView || captured != null && captured.getClass().getSimpleName().equals("CpuDelivery"),
                            "Queued CPU closure captures only current view and detached delivery metadata");
                    if (captured != ownedView) for (var component : captured.getClass().getRecordComponents())
                        require(component.getType().isPrimitive() || component.getType() == TerrainRenderer.Frame.class,
                                "CPU delivery cannot retain an old geometry or atlas owner");
                }
                int before = pixel(ownedView);
                fx(() -> { Runnable callback; while ((callback = deliveries.poll()) != null) callback.run(); return null; });
                require(pixel(ownedView) == before, "Delayed canceled CPU frame cannot replace accepted current GPU frame");
                sourceGate.get().complete(null);
                presentUntil(children, frames, 1, 100, TARGET, () -> children.size() == 3,
                        "Candidate resumes after CPU retirement");
                ready(children, 2, sceneCounts.get(2), false, false);
                presentUntil(children, frames, 2, 1, FIRST, () -> pixel(ownedView) == FIRST, "Third bundle frame");
            }
            require(fixture.stats().entries() <= 18, "Shared atlas bound remains unchanged");
        } finally {
            cpuRelease.countDown();
            sourceGate.get().complete(null);
            var closingView = view; var closingStage = stage;
            fx(() -> { if (closingView != null) closingView.close(); if (closingStage != null) closingStage.close(); return null; });
            service.closeAsync().get(10, TimeUnit.SECONDS);
            require(fixture.stats().leaseClaims() == 0 && fixture.stats().borrowPins() == 0, "Every current/pending claim drains on close");
            fixture.close();
            debugView = null;
        }
    }

    private static void presentUntil(List<GpuPreviewServiceTest.FakeProcess> children,
            List<Frames> frames, int index, long sequence, int color,
            BooleanSupplier accepted, String stage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            publish(children, frames, index, sequence++, color);
            if (accepted.getAsBoolean()) return;
            Thread.sleep(20);
        }
        System.err.println(fx(() -> "DEBUG view " + get(debugView,"scenePreparing") + " material=" + get(debugView,"currentMaterialsReady")
                + " busy=" + get(debugView,"textureSourceBusy") + " pending=" + get(debugView,"texturePendingFrame")
                + " revision=" + get(debugView,"loadedRevision") + "/" + get(debugView,"publishedLoadedRevision")
                + " generation=" + get(debugView,"textureGeneration") + "/" + get(debugView,"presentedTextureGeneration")
                + " wait=" + get(debugView,"geometryPreparationWaiting") + " candidate=" + get(debugView,"pendingPrepared")
                + " gpu=" + ((Label)debugView.lookup("#world-gpu-status")).getText()
                + " text=" + ((Label)debugView.lookup("#world-texture-status")).getText()));
        throw new AssertionError("Timed out: " + stage);
    }

    private static void ready(List<GpuPreviewServiceTest.FakeProcess> children, int index,
            int tiles, boolean partial, boolean fallback) throws Exception {
        var child = children.get(index);
        require(child.started.await(5, TimeUnit.SECONDS), "Status reader starts");
        child.send("VIMI_GPU HELLO Vulkan\n" + (fallback ? "" : TerrainTextureStatus.of(tiles, tiles,
                tiles * (partial ? 128 : 256), TerrainTextureStatus.Code.NONE).protocolLine() + "\n") + "VIMI_GPU READY Vulkan\n");
    }
    private static long revision(GpuPreviewServiceTest.FakeProcess child) {
        long revision = 0;
        for (var line : child.controls.toString().split("\n")) if (line.startsWith("WORLD ")) {
            var fields = line.split(" "); if (fields.length == 9) revision = Long.parseLong(fields[1]);
        }
        return revision;
    }
    private static void publish(List<GpuPreviewServiceTest.FakeProcess> children, List<Frames> frames,
            int index, long sequence, int color) throws Exception {
        await(() -> revision(children.get(index)) > 0, "camera command");
        String[] fields = null;
        for (var line : children.get(index).controls.toString().split("\n")) if (line.startsWith("WORLD ")) fields = line.split(" ");
        int width = Integer.parseInt(fields[2]), height = Integer.parseInt(fields[3]);
        int[] pixels = new int[width * height]; java.util.Arrays.fill(pixels, color);
        frames.get(index).next.set(new GpuPreviewService.Frame(sequence, Long.parseLong(fields[1]), width, height,
                IntBuffer.wrap(pixels).asReadOnlyBuffer()));
    }
    private static int pixel(WorldView view) { return fx(() -> ((ImageView) view.lookup("#world-frame")).getImage().getPixelReader().getArgb(0, 0)); }
    private static Object get(Object owner, String name) { try { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private static void set(Object owner, String name, Object value) { try { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private static void invoke(Object owner, String name) { try { var m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private static <T> T fx(Supplier<T> work) { try { var result = new CompletableFuture<T>(); Platform.runLater(() -> { try { result.complete(work.get()); } catch (Throwable error) { result.completeExceptionally(error); } }); return result.get(5, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); } }
    private static void await(BooleanSupplier ready, String message) throws Exception { long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12); while (System.nanoTime() < deadline) { if (ready.getAsBoolean()) return; Thread.sleep(10); } throw new AssertionError("Timed out: " + message); }
    private static void offFx() { require(!Platform.isFxApplicationThread(), "Sources remain off FX"); }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
