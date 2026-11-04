package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.TerrainTextureStatus;
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

/** Real authored source qualification with controlled child acknowledgements and accepted FX pixels. */
public final class WorldTextureUiTest {
    private static final int COLOR = 0xffff2200;
    private WorldTextureUiTest() {}

    private static final class Frames implements GpuPreviewService.Frames {
        final AtomicReference<GpuPreviewService.Frame> next = new AtomicReference<>();
        volatile boolean closed;
        public GpuPreviewService.Frame readLatest(long after) { return next.getAndSet(null); }
        public void close() { closed = true; next.set(null); }
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        var root = Files.createTempDirectory("vimi-world-texture-fx-");
        var children = new CopyOnWriteArrayList<GpuPreviewServiceTest.FakeProcess>();
        var frames = new CopyOnWriteArrayList<Frames>();
        var reads = new AtomicInteger();
        var unsupported = new java.util.concurrent.atomic.AtomicBoolean();
        var borrowedCloses = new AtomicInteger();
        var service = new GpuPreviewService(root, (path, mesh, appearance, parts) -> Files.writeString(path,"unused"),
                (path, mapping) -> {
                    for (var old : children) require(!old.isAlive(), "At most one child context");
                    var child = new GpuPreviewServiceTest.FakeProcess(true);
                    children.add(child);
                    return child;
                }, path -> { var result = new Frames(); frames.add(result); return result; },
                Duration.ofSeconds(20), Duration.ofMillis(10));
        WorldView view = null;
        Stage stage = null;
        try (var fixture = new TerrainGpuPreviewTextureFixture()) {
            // Warm the actual source/cache before opening: no receipt or authority is fabricated.
            fixture.snapshot().close();
            BuildWorld source = new BuildWorld() {
                public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
                    offFx(); return CompletableFuture.completedFuture(new WorldService.Catalog(List.of(TerrainGpuPreviewTextureFixture.mapInfo(), new WorldService.MapInfo(8,"Empty authored map",null,0,1L)),List.of(),List.of()));
                }
                public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo map, Cancellation token) {
                    offFx(); return CompletableFuture.completedFuture(new WorldService.Inventory(map,new WorldFormats.World(map.id()==8?List.of():List.of(TerrainGpuPreviewTextureFixture.TILE),false),null));
                }
                public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
                    offFx(); return CompletableFuture.completedFuture(new WorldService.LoadedTile(tile,fixture.terrain,null));
                }
                public CompletableFuture<WorldTerrainTextureSource.BatchResult> terrainTextures(WorldService.MapInfo map,List<WorldFormats.Tile> tiles,Cancellation token,java.util.function.Consumer<WorldTerrainTextureSource.Progress> progress) {
                    offFx(); reads.incrementAndGet(); return unsupported.get()?CompletableFuture.completedFuture(WorldTerrainTextureSource.BatchResult.unsupported(tiles)):fixture.batch(token,progress);
                }
                public CompletableFuture<Void> closeAsync() { borrowedCloses.incrementAndGet(); return CompletableFuture.completedFuture(null); }
            };
            var ownedView = fx(() -> new WorldView(() -> source, () -> 0, service));
            view = ownedView;
            var ownedStage = fx(() -> { var result = new Stage(); result.setScene(new Scene(ownedView,1100,800)); result.show(); ownedView.ensureLoaded(); return result; });
            stage = ownedStage;
            await(() -> fx(() -> !((ListView<?>)ownedView.lookup("#world-maps")).getItems().isEmpty()),"catalog");
            fx(() -> { ((ListView<?>)ownedView.lookup("#world-maps")).getSelectionModel().selectFirst(); return null; });
            await(() -> fx(() -> !((Button)ownedView.lookup("#world-open")).isDisabled()),"inventory");
            fx(() -> { ((Button)ownedView.lookup("#world-open")).fire(); return null; });
            await(() -> children.size()==1 && fx(() -> get(ownedView,"textureSnapshot") != null),"warm source before first height frame");
            require(fx(() -> ownedView.lookup("#world-texture-activity").isVisible()),"Texture activity while awaiting presentation");
            require(children.size()==1,"Warm textures cannot replace unpresented height child");
            ready(children,0,false);
            publish(children,frames,0,1);
            await(() -> children.size()==2,"First height frame alone automatically promotes textures");
            require(reads.get()==1,"Promotion does not reread source");
            ready(children,1,true);
            await(() -> fx(() -> ((Label)ownedView.lookup("#world-gpu-status")).getText().contains("Waiting for the first")),"typed ACK processed");
            require(fx(() -> ownedView.lookup("#world-texture-activity").isVisible()),"Typed texture ACK alone cannot end activity");
            publish(children,frames,1,1);
            await(() -> fx(() -> !ownedView.lookup("#world-texture-activity").isVisible()),"accepted texture target frame");
            require(fx(() -> ((Label)ownedView.lookup("#world-texture-status")).getText().equals("Basic terrain textures · 256/256 regions")),"Accepted coverage label");
            require(fx(() -> ((ImageView)ownedView.lookup("#world-frame")).getImage().getPixelReader().getArgb(0,0)==COLOR),"Accepted bulk pixel reaches FX image");
            unsupported.set(true);
            fx(() -> { ((Button)ownedView.lookup("#world-texture-retry")).fire(); return null; });
            await(() -> fx(() -> ((Label)ownedView.lookup("#world-texture-status")).getText().startsWith("Terrain textures unavailable")),"zero atlas outcome");
            require(fx(() -> !ownedView.lookup("#world-texture-activity").isVisible()),"Zero-atlas fallback terminates activity");
            await(() -> fixture.stats().leaseClaims()==0,"zero atlas retires previous snapshot");
            unsupported.set(false);
            fx(() -> { ((Button)ownedView.lookup("#world-texture-retry")).fire(); return null; });
            await(() -> fx(() -> get(ownedView,"textureSnapshot") != null),"supported retry");
            // No camera input: ancestor visibility and owner activation must requalify textures.
            int before = reads.get();
            fx(() -> { ownedView.setVisible(false); return null; });
            await(() -> fixture.stats().leaseClaims()==0,"ancestor hide releases all snapshot claims");
            fx(() -> { ownedView.setVisible(true); return null; });
            await(() -> reads.get()>before,"stationary ancestor restore reloads textures");
            fx(() -> { ownedView.deactivate(); return null; });
            await(() -> fixture.stats().leaseClaims()==0,"deactivate releases snapshots");
            int deactivatedReads = reads.get();
            fx(() -> { ownedView.activate(); return null; });
            await(() -> reads.get()>deactivatedReads,"stationary activation reloads textures");
            fx(() -> { ownedStage.setIconified(true); return null; });
            await(() -> fx(() -> ownedStage.isIconified() && !(boolean)get(ownedView,"gpuVisible") && get(ownedView,"textureSnapshot")==null) && fixture.stats().leaseClaims()==0,"iconify releases snapshot claims");
            int iconifiedReads=reads.get();
            fx(() -> { ownedStage.setIconified(false); return null; });
            await(() -> reads.get()>iconifiedReads,"stationary deiconify reloads textures");
            fx(() -> { ((ListView<?>)ownedView.lookup("#world-maps")).getSelectionModel().select(1); return null; });
            await(() -> fixture.stats().leaseClaims()==0,"empty map selection releases previous snapshot");
            await(() -> children.stream().noneMatch(Process::isAlive),"empty map selection retires GPU child");
            require(fx(() -> !((javafx.scene.Node)get(ownedView,"textureActivity")).isVisible()),"Empty map cannot retain stale texture activity");
            fx(() -> { ownedView.close(); ownedStage.close(); return null; });
            await(() -> fixture.stats().leaseClaims()==0 && fixture.stats().borrowPins()==0,"all pool claims drained");
            require(borrowedCloses.get()==0,"WorldView never closes borrowed source");
        } finally {
            var oldView=view; var oldStage=stage;
            fx(() -> { if(oldView!=null)oldView.close(); if(oldStage!=null)oldStage.close(); return null; });
            service.closeAsync().get(10,TimeUnit.SECONDS);
            Platform.exit();
        }
        System.out.println("World texture FX PASS: warm-before-height, automatic promotion, ACK barrier, pixels, stationary hide/restore and ownership");
    }

    private static void ready(List<GpuPreviewServiceTest.FakeProcess> children,int index,boolean textured) throws Exception {
        var child=children.get(index);
        require(child.started.await(5,TimeUnit.SECONDS),"status reader started");
        child.send("VIMI_GPU HELLO Vulkan\n"+(textured?TerrainTextureStatus.of(1,1,256,TerrainTextureStatus.Code.NONE).protocolLine()+"\n":"")+"VIMI_GPU READY Vulkan\n");
    }
    private static void publish(List<GpuPreviewServiceTest.FakeProcess> children,List<Frames> frames,int index,long sequence) throws Exception {
        await(() -> children.get(index).controls.toString().contains("WORLD "),"camera command");
        String[] commands=children.get(index).controls.toString().split("\n");
        String[] p=null; for(String command:commands)if(command.startsWith("WORLD "))p=command.split(" ");
        int width=Integer.parseInt(p[2]),height=Integer.parseInt(p[3]); long revision=Long.parseLong(p[1]);
        int[] pixels=new int[width*height]; java.util.Arrays.fill(pixels,COLOR);
        frames.get(index).next.set(new GpuPreviewService.Frame(sequence,revision,width,height,IntBuffer.wrap(pixels).asReadOnlyBuffer()));
    }
    private static Object get(Object instance,String name) { try { var field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance); } catch(ReflectiveOperationException e){throw new AssertionError(e);} }
    private static void offFx(){require(!Platform.isFxApplicationThread(),"Source/preparation stays off FX");}
    private static <T>T fx(Supplier<T> work){try{var result=new CompletableFuture<T>();Platform.runLater(()->{try{result.complete(work.get());}catch(Throwable e){result.completeExceptionally(e);}});return result.get(5,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    private static void await(BooleanSupplier condition,String stage) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(10);}throw new AssertionError("Timed out: "+stage);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
