package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Authored asymmetric filename/native-coordinate navigation; no application stores or GPU child. */
public final class WorldNavigationUiTest {
    private static final int X = 7, Y = 19;
    private static final double SIZE = WorldFormats.TILE_SIZE;
    private static final int[][] MOVES = {
        {1,0}, {0,0}, {-1,0}, {0,0}, {0,1}, {0,0}, {0,-1}, {0,0},
        {1,1}, {-1,1}, {-1,-1}, {1,-1}, {0,0}
    };
    private static final CompletableFuture<Void> DONE = new CompletableFuture<>();

    private WorldNavigationUiTest() {}

    private static final class Fixture implements BuildWorld {
        final WorldService.MapInfo map = new WorldService.MapInfo(1,"Navigation fixture","Authored",0,1L);
        final List<WorldFormats.Tile> tiles = new ArrayList<>();
        final AtomicReference<Set<String>> allowed = new AtomicReference<>();
        final AtomicReference<AssertionError> failure = new AtomicReference<>();
        Fixture() {
            for(int x=X-3;x<=X+3;x++) for(int y=Y-3;y<=Y+3;y++)
                tiles.add(new WorldFormats.Tile(x,y,10000+x*64+y,0,0));
        }
        public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Catalog(List.of(map),List.of(),List.of()));
        }
        public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo selected,Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Inventory(map,new WorldFormats.World(tiles,false),null));
        }
        public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo selected,WorldFormats.Tile tile,Cancellation token) {
            var expected=allowed.get();
            if(expected!=null && !expected.contains(tile.key()))
                failure.compareAndSet(null,new AssertionError("Wrong native-axis request "+tile.key()+" expected "+expected));
            float maxX=(float)(10000-(tile.x()-X)*SIZE), maxY=(float)(20000-(tile.y()-Y)*SIZE);
            var terrain=new WorldFormats.Terrain(new float[]{maxX,maxY,0,(float)(maxX-SIZE),maxY,0,maxX,(float)(maxY-SIZE),0},new int[]{0,1,2},1);
            return CompletableFuture.completedFuture(new WorldService.LoadedTile(tile,terrain,null));
        }
        public CompletableFuture<Void> closeAsync() { throw new AssertionError("View borrows fixture"); }
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            var fixture=new Fixture();
            var view=new WorldView(() -> fixture,() -> 1);
            var stage=new Stage();
            stage.setScene(new Scene(view,1000,750)); stage.show();
            DONE.whenComplete((value,error) -> Platform.runLater(() -> {view.close();stage.close();}));
            view.ensureLoaded();
            await(fixture,() -> ((ListView<?>)view.lookup("#world-maps")).getItems().size()==1,() -> {
                ((ListView<?>)view.lookup("#world-maps")).getSelectionModel().selectFirst();
                await(fixture,() -> !((Button)view.lookup("#world-open")).isDisabled(),() -> {
                    set(view,"selected",fixture.tiles.stream().filter(t -> t.x()==X&&t.y()==Y).findFirst().orElseThrow());
                    ((Button)view.lookup("#world-open")).fire();
                    ((AnimationTimer)get(view,"timer")).stop();
                    await(fixture,() -> settled(view,keys(0,0)),() -> move(view,fixture,0));
                });
            });
        });
        try { DONE.get(25,TimeUnit.SECONDS); }
        finally { Platform.exit(); }
        System.out.println("World navigation: native +/-X/Y, four diagonals, retained 3x3 tiles and Space/E/Q deterministic timer controls passed");
    }

    private static Set<String> keys(int dx,int dy) {
        var result=new HashSet<String>();
        for(int x=X+dx-1;x<=X+dx+1;x++) for(int y=Y+dy-1;y<=Y+dy+1;y++) result.add(x+","+y);
        return result;
    }

    private static boolean settled(WorldView view,Set<String> expected) {
        @SuppressWarnings("unchecked")
        var loaded=(Map<String,WorldService.LoadedTile>)get(view,"loaded");
        return !(boolean)get(view,"loadingTile") && loaded.keySet().equals(expected);
    }

    private static void move(WorldView view,Fixture fixture,int index) {
        if(index==MOVES.length) { controls(view); DONE.complete(null); return; }
        int dx=MOVES[index][0],dy=MOVES[index][1];
        var expected=keys(dx,dy);
        fixture.allowed.set(expected);
        set(view,"camera",new TerrainRenderer.Camera(10000-(dx+.5)*SIZE,20000-(dy+.5)*SIZE,100,0,0));
        invoke(view,"loadNext");
        await(fixture,() -> settled(view,expected),() -> move(view,fixture,index+1));
    }

    private static void controls(WorldView view) {
        var viewport=view.lookup("#world-viewport");
        var timer=(AnimationTimer)get(view,"timer");
        // Prevent unrelated rendering/discovery while exercising the actual timer's movement branch.
        set(view,"lastRender",Long.MAX_VALUE);
        set(view,"lastNearby",Long.MAX_VALUE);
        for(var keys:List.of(List.of(KeyCode.SPACE),List.of(KeyCode.E),List.of(KeyCode.SPACE,KeyCode.E),List.of(KeyCode.Q),List.of(KeyCode.SPACE,KeyCode.Q))) {
            set(view,"camera",new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0));
            set(view,"lastTick",0L);
            for(var key:keys) key(viewport,key,true);
            timer.handle(1_000_000_000L);
            timer.handle(1_100_000_000L);
            double expected=keys.contains(KeyCode.Q) ? keys.contains(KeyCode.SPACE)?500:492 : 508;
            double actual=((TerrainRenderer.Camera)get(view,"camera")).z();
            require(Math.abs(actual-expected)<1e-9,"Actual key/timer ascent "+keys+": "+actual+" expected "+expected);
            for(var key:keys) key(viewport,key,false);
            timer.handle(1_200_000_000L);
            require(((TerrainRenderer.Camera)get(view,"camera")).z()==actual,"Key release stops movement");
        }
    }

    private static void key(Node target,KeyCode key,boolean pressed) {
        Event.fireEvent(target,new KeyEvent(pressed?KeyEvent.KEY_PRESSED:KeyEvent.KEY_RELEASED,"","",key,false,false,false,false));
    }

    private static void await(Fixture fixture,BooleanSupplier ready,Runnable action) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        var timer=new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(10),event -> {
            try {
                if(fixture.failure.get()!=null) throw fixture.failure.get();
                if(ready.getAsBoolean()) { timer.stop(); action.run(); }
                else if(System.nanoTime()>deadline) throw new AssertionError("Navigation did not settle");
            } catch(Throwable error) {timer.stop();DONE.completeExceptionally(error);}
        }));
        timer.setCycleCount(Timeline.INDEFINITE);timer.play();
    }

    private static Field field(String name) {
        try {var f=WorldView.class.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static Object get(WorldView view,String name) {
        try{return field(name).get(view);}catch(IllegalAccessException error){throw new AssertionError(error);}
    }
    private static void set(WorldView view,String name,Object value) {
        try{field(name).set(view,value);}catch(IllegalAccessException error){throw new AssertionError(error);}
    }
    private static void invoke(WorldView view,String name) {
        try{var m=WorldView.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(view);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
