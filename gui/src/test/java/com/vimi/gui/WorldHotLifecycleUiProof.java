package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.files.FileReadStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

/** Actual native source qualification and actual child lifecycle; authored data only, no user stores. */
public final class WorldHotLifecycleUiProof {
    private WorldHotLifecycleUiProof() {}

    public static void main(String[] args) throws Exception {
        var files = new HeldFiles();
        var install = WorldStreamSourceControllerTest.class.getDeclaredMethod("install", AuthoredTerrainSource.Files.class, int.class);
        install.setAccessible(true);
        install.invoke(null, files, 1);
        var pool = new TerrainAtlasPool();
        var tables = new AuthoredNativeMap(files.files());
        var world = new WorldService(tables.tables(), files.files(), pool);
        var map = new WorldService.MapInfo(7, "Authored", null, 0, 1L);
        var inventory = world.inventory(map, Cancellation.NONE).get(5, TimeUnit.SECONDS);
        var owner = new AtomicReference<WorldView>();
        var window = new AtomicReference<Stage>();
        Platform.startup(() -> Platform.setImplicitExit(false));
        var gpu = GpuPreviewService.application();
        try {
            fx(() -> {
                var view = new WorldView(() -> world, () -> 0, gpu);
                owner.set(view);
                set(view, "handle", world);
                set(view, "revision", 0L);
                set(view, "catalog", new WorldService.Catalog(List.of(map), List.of(), List.of()));
                set(view, "inventory", inventory);
                set(view, "selected", inventory.world().tiles().getFirst());
                ((CheckBox)get(view,"gpuEnabled")).setSelected(true);
                var stage = new Stage(); window.set(stage);
                stage.setScene(new Scene(view, 800, 600)); stage.show();
                files.hold.set(true);
                call(view, "openExplorer");
                return null;
            });
            files.entered.get(5, TimeUnit.SECONDS);
            fx(() -> { owner.get().setVisible(false); return null; });
            await(() -> fx(() -> get(owner.get(), "streaming") == null),
                    "hidden initial qualification retires before camera seed");
            check(!fx(() -> ((CompletableFuture<?>)get(owner.get(), "streamingRetirement")).isDone()),
                    "Held source work is not an actual retirement certificate");
            check(children().isEmpty(), "No native process launches from hidden initial qualification");
            files.release.countDown();
            await(() -> fx(() -> ((CompletableFuture<?>)get(owner.get(), "streamingRetirement")).isDone()),
                    "held initial qualification actual drain");
            check(children().isEmpty() && pool.stats().leaseClaims() == 0,
                    "Hidden canceled qualification owns no child or atlas");
            fx(() -> { owner.get().setVisible(true); return null; });
            await(() -> ready(owner.get()), "initial actual HOT presentation after hidden startup");
            long initial = onlyChild();
            check(fx(() -> (boolean)get(owner.get(), "streamingMode")), "Initial native route is HOT");
            check(pool.stats().leaseClaims() == 1, "One qualified visible atlas owner");
            fx(() -> { ((CheckBox)get(owner.get(),"gpuEnabled")).fire(); return null; });
            await(() -> !fx(() -> (boolean)get(owner.get(),"streamingMode")) && children().isEmpty()
                    && fx(() -> get(owner.get(),"terrainScene") != null), "software after actual child retirement");
            fx(() -> { ((CheckBox)get(owner.get(),"gpuEnabled")).fire(); return null; });
            await(() -> ready(owner.get()), "HOT reentry after legacy drains");
            long reentered = onlyChild();
            check(reentered != initial, "Software toggle uses a newly owned native session");
            // #173: the Display page's view distance restarts the stream at the new size.
            var near = com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.NEAR;
            var far = com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.FAR;
            check(fx(() -> owner.get().streamingViewDistance()) == near, "NEAR is the default view distance");
            // On HIGH object textures NEAR composes 1024 splat masks; FAR keeps 512 on any tier.
            var high = com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH;
            var standard = com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD;
            fx(() -> { textures(owner.get()).setValue(high); return null; });
            await(() -> ready(owner.get()) && onlyChild() != reentered, "NEAR + HIGH restream presents");
            check(com.vibeminer.concepts.world.WorldTerrainStreaming.splatMaskSide() == 1024, "NEAR + HIGH masks are 1024");
            long nearHigh = onlyChild();
            fx(() -> { distance(owner.get()).setValue(far); return null; });
            await(() -> ready(owner.get()) && fx(() -> owner.get().streamingViewDistance()) == far
                    && fx(() -> sessionDistance(owner.get())) == far, "FAR restream presents");
            check(com.vibeminer.concepts.world.WorldTerrainStreaming.splatMaskSide() == 512, "FAR + HIGH masks stay 512");
            long farChild = onlyChild();
            check(farChild != nearHigh, "FAR runs in a newly owned session after the NEAR one drained");
            fx(() -> { distance(owner.get()).setValue(near); return null; });
            await(() -> ready(owner.get()) && fx(() -> sessionDistance(owner.get())) == near, "NEAR restream presents");
            check(com.vibeminer.concepts.world.WorldTerrainStreaming.splatMaskSide() == 1024, "Back on NEAR + HIGH, 1024 again");
            long nearAgain = onlyChild();
            check(nearAgain != farChild, "Back to NEAR is a fresh session too");
            fx(() -> { textures(owner.get()).setValue(standard); return null; });
            await(() -> ready(owner.get()) && onlyChild() != nearAgain, "NEAR + STANDARD restream presents");
            long second = onlyChild();
            fx(() -> { owner.get().setVisible(false); return null; });
            await(() -> children().isEmpty() && pool.stats().leaseClaims() == 0, "hidden owner and source claims drain");
            fx(() -> { owner.get().setVisible(true); return null; });
            await(() -> ready(owner.get()), "visible resumed session");
            check(onlyChild() != second, "Visibility resume starts after old actual drain");
            fx(() -> { owner.get().close(); window.get().close(); return null; });
            gpu.closeAsync().get(10, TimeUnit.SECONDS);
            world.closeAsync().get(10, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            check(children().isEmpty() && pool.stats().leaseClaims() == 0, "Final child/source ownership is empty");
            System.out.println("Actual authored HOT World UI: initial qualification/frame, software/on, NEAR/FAR restream, hide/resume, final drain PASS");
        } finally {
            files.release.countDown();
            if (owner.get() != null) fx(() -> { owner.get().close(); if(window.get()!=null)window.get().close(); return null; });
            gpu.closeAsync().get(10, TimeUnit.SECONDS);
            world.closeAsync().get(10, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
            tables.close();
            Platform.exit();
        }
    }

    private static final class HeldFiles extends AuthoredTerrainSource.Files {
        final java.util.concurrent.atomic.AtomicBoolean hold = new java.util.concurrent.atomic.AtomicBoolean();
        final CompletableFuture<Void> entered = new CompletableFuture<>();
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        @Override FileReadStream open(long id, Cancellation token) {
            if (id == 1 && hold.compareAndSet(true, false)) {
                entered.complete(null);
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Initial source never released");
                } catch (InterruptedException failed) { throw new AssertionError(failed); }
            }
            return super.open(id, token);
        }
    }

    private static boolean ready(WorldView view) throws Exception {
        return fx(() -> {
            var controller = (WorldStreamSourceController)get(view,"streaming");
            return controller != null && controller.snapshot().phase() == WorldStreamSourceController.Phase.READY
                    && ((ImageView)get(view,"image")).getImage() != null;
        });
    }
    @SuppressWarnings("unchecked")
    private static javafx.scene.control.ChoiceBox<com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance> distance(WorldView view) {
        return (javafx.scene.control.ChoiceBox<com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance>)get(view,"viewDistance");
    }
    @SuppressWarnings("unchecked")
    private static javafx.scene.control.ChoiceBox<com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail> textures(WorldView view) {
        return (javafx.scene.control.ChoiceBox<com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail>)get(view,"objectTextures");
    }
    private static com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance sessionDistance(WorldView view) {
        var controller=(WorldStreamSourceController)get(view,"streaming");
        var session=controller==null?null:(com.vibeminer.rendering.bgfx.WorldStreamTransport.Session)get(controller,"session");
        return session==null?null:session.viewDistance();
    }
    private static long onlyChild() { var values=children();check(values.size()==1,"Exactly one actual child: "+values);return values.iterator().next(); }
    private static Set<Long> children() { return RendererChildren.live(); }
    private interface Checked { boolean get() throws Exception; }
    private static void await(Checked condition, String message) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){if(condition.get())return;Thread.sleep(20);}
        throw new AssertionError("Timed out: "+message);
    }
    private static <T> T fx(Supplier<T> action) throws Exception {
        var value=new CompletableFuture<T>();Platform.runLater(() -> {try{value.complete(action.get());}catch(Throwable failure){value.completeExceptionally(failure);}});
        return value.get(5,TimeUnit.SECONDS);
    }
    private static Object get(Object value,String name){try{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    private static void set(Object value,String name,Object data){try{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,data);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    private static void call(Object value,String name){try{var m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(value);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
