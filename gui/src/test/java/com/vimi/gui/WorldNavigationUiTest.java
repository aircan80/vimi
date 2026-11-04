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
        final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong(1);
        final java.util.concurrent.atomic.AtomicInteger minimapCalls = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean hold, unavailable;
        final java.util.concurrent.atomic.AtomicInteger tileCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger unavailableCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.ConcurrentLinkedQueue<Runnable> releases = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Fixture() {
            for(int x=X-3;x<=X+3;x++) for(int y=Y-3;y<=Y+3;y++)
                tiles.add(new WorldFormats.Tile(x,y,10000+x*64+y,0,20000+x*64+y));
        }
        public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Catalog(List.of(map),List.of(new WorldService.Area(12,"Elwynn Forest",0,1),new WorldService.Area(9,"Northshire Valley",12,1),new WorldService.Area(10,"Northshire Abbey",9,1),new WorldService.Area(11,"Eastvale Logging Camp",12,1)),List.of()));
        }
        public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo selected,Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Inventory(map,new WorldFormats.World(tiles,false),null));
        }
        public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo selected,WorldFormats.Tile tile,Cancellation token) {
            tileCalls.incrementAndGet();
            var expected=allowed.get();
            if(expected!=null && !expected.contains(tile.key()))
                failure.compareAndSet(null,new AssertionError("Wrong native-axis request "+tile.key()+" expected "+expected));
            float maxX=(float)(10000-(tile.x()-X)*SIZE), maxY=(float)(20000-(tile.y()-Y)*SIZE);
            var terrain=new WorldFormats.Terrain(new float[]{maxX,maxY,0,(float)(maxX-SIZE),maxY,0,maxX,(float)(maxY-SIZE),0},new int[]{0,1,2},1);
            if (unavailable) {
                unavailableCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new WorldService.LoadedTile(tile,null,"Authored unavailable neighbor"));
            }
            long areaId=tile.x()==X?(tile.y()==Y?9:10):11;
            var metadata=new WorldFormats.AreaMetadata(List.of(new WorldFormats.AreaChunk(7,7,areaId,(float)(maxX-SIZE/2+16),(float)(maxY-SIZE/2+16),0,null)));
            var value = new WorldService.LoadedTile(tile,terrain,null,metadata);
            if (hold) {
                var result = new CompletableFuture<WorldService.LoadedTile>();
                releases.add(() -> result.complete(value));
                return result;
            }
            return CompletableFuture.completedFuture(value);
        }
        public CompletableFuture<WorldService.Minimap> minimap(WorldFormats.Tile tile, Cancellation token) {
            minimapCalls.incrementAndGet();
            return CompletableFuture.completedFuture(WorldService.Minimap.unavailable("Authored missing artwork"));
        }
        public CompletableFuture<Void> closeAsync() { throw new AssertionError("View borrows fixture"); }
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            var fixture=new Fixture();
            var view=new WorldView(() -> fixture,fixture.revision::get);
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
                    await(fixture,() -> settled(view,keys(0,0)) && !(boolean)get(view,"scenePreparing"),() -> heldBoundary(view,fixture,0));
                });
            });
        });
        try { DONE.get(25,TimeUnit.SECONDS); }
        finally { Platform.exit(); }
        System.out.println("World navigation: native +/-X/Y, four diagonals, retained 3x3 tiles and Space/E and Q/X deterministic timer controls passed");
    }

    private static Set<String> keys(int dx,int dy) {
        var result=new HashSet<String>();
        for(int x=X+dx-1;x<=X+dx+1;x++) for(int y=Y+dy-1;y<=Y+dy+1;y++) result.add(x+","+y);
        return result;
    }

    private static boolean settled(WorldView view,Set<String> expected) {
        @SuppressWarnings("unchecked")
        var loaded=(Map<String,WorldService.LoadedTile>)get(view,"loaded");
        return !(boolean)get(view,"loadingTile") && !(boolean)get(view,"scenePreparing") && loaded.keySet().equals(expected);
    }

    private static final int[][] HELD_MOVES = {{1,0},{0,0},{-1,0},{0,0},{0,-1},{0,0}};

    private static void heldBoundary(WorldView view, Fixture fixture, int index) {
        if (index == HELD_MOVES.length) { move(view,fixture,0); return; }
        var original = get(view, "terrainScene");
        long generation = (long)get(view, "sceneGeneration");
        int calls = fixture.tileCalls.get();
        int dx=HELD_MOVES[index][0], dy=HELD_MOVES[index][1];
        var wanted=keys(dx,dy);
        fixture.hold = true;
        fixture.allowed.set(wanted);
        set(view,"camera",new TerrainRenderer.Camera(10000-(dx+.5)*SIZE,20000-(dy+.5)*SIZE,100,0,0));
        invoke(view,"loadNext");
        releaseBoundary(view, fixture, original, generation, calls, wanted, index, 0);
    }

    private static void releaseBoundary(WorldView view, Fixture fixture, Object original, long generation,
            int calls, Set<String> wanted, int index, int count) {
        await(fixture, () -> !fixture.releases.isEmpty(), () -> {
            require(get(view,"terrainScene") == original, "Held arrivals retain readable scene");
            require((long)get(view,"sceneGeneration") == generation, "No eager/per-arrival scene preparation");
            require(fixture.tileCalls.get() == calls+count+1, "Only missing strip source calls are admitted");
            fixture.releases.remove().run();
            if (count < 2) {
                releaseBoundary(view,fixture,original,generation,calls,wanted,index,count+1);
            } else {
                fixture.hold=false;
                await(fixture, () -> settled(view,wanted), () -> {
                    require(get(view,"terrainScene") != original, "Completed wave publishes replacement");
                    require((long)get(view,"sceneGeneration") == generation+1, "Three arrivals prepare exactly one replacement");
                    require(fixture.tileCalls.get() == calls+3, "Negative/positive strip never reloads retained wanted tiles");
                    heldBoundary(view,fixture,index+1);
                });
            }
        });
    }

    private static void move(WorldView view,Fixture fixture,int index) {
        if(index==MOVES.length) {
            unavailableRetainsGeometry(view,fixture, () -> finish(view,fixture));
            return;
        }
        int dx=MOVES[index][0],dy=MOVES[index][1];
        var expected=keys(dx,dy);
        fixture.allowed.set(expected);
        set(view,"camera",new TerrainRenderer.Camera(10000-(dx+.5)*SIZE,20000-(dy+.5)*SIZE,100,0,0));
        invoke(view,"loadNext");
        await(fixture,() -> settled(view,expected),() -> {
            invoke(view,"requestFrame");
            String expectedArea=dx==0?(dy==0?"Elwynn Forest · Northshire Valley":"Elwynn Forest · Northshire Valley · Northshire Abbey"):"Elwynn Forest · Eastvale Logging Camp";
            require(((javafx.scene.control.Label)view.lookup("#world-current-area")).getText().equals("Terrain area: "+expectedArea),"Moving camera updates recorded zone/subzone: "+expectedArea);
            move(view,fixture,index+1);
        });
    }

    private static void unavailableRetainsGeometry(WorldView view, Fixture fixture, Runnable next) {
        var original = get(view,"terrainScene");
        fixture.unavailable=true;
        fixture.allowed.set(keys(1,0));
        set(view,"camera",new TerrainRenderer.Camera(10000-1.5*SIZE,20000-.5*SIZE,100,0,0));
        invoke(view,"loadNext");
        await(fixture, () -> fixture.unavailableCalls.get()==3 && !(boolean)get(view,"loadingTile"), () -> {
            require(get(view,"terrainScene")==original,"Unavailable incoming strip retains readable old coverage");
            require(!view.lookup("#world-local-activity").isVisible(),"Failed wave retires source activity");
            fixture.unavailable=false;
            next.run();
        });
    }

    private static void finish(WorldView view, Fixture fixture) {
            controls(view);
            invoke(view,"requestFrame");
            await(fixture,() -> !view.lookup("#world-minimap-activity").isVisible(),() -> {
                int before = fixture.minimapCalls.get();
                fixture.revision.incrementAndGet();
                invoke(view,"requestFrame");
                var viewport = (javafx.scene.layout.StackPane)view.lookup("#world-viewport");
                viewport.resize(viewport.getWidth()+17,viewport.getHeight()+11);
                invoke(view,"requestFrame");
                require(!view.lookup("#world-minimap").isVisible(),"Retired source cannot reveal minimap after resize");
                require(((javafx.scene.control.Label)view.lookup("#world-current-area")).getText().equals("Terrain area unavailable"),"Retired source cannot retain current-area name");
                require(fixture.minimapCalls.get()==before,"Retired source cannot request artwork after resize");
                DONE.complete(null);
            });
    }

    private static void controls(WorldView view) {
        var viewport=view.lookup("#world-viewport");
        var timer=(AnimationTimer)get(view,"timer");
        // Prevent unrelated rendering/discovery while exercising the actual timer's movement branch.
        set(view,"lastRender",Long.MAX_VALUE);
        set(view,"lastNearby",Long.MAX_VALUE);
        for(var keys:List.of(List.of(KeyCode.SPACE),List.of(KeyCode.E),List.of(KeyCode.SPACE,KeyCode.E),List.of(KeyCode.Q),List.of(KeyCode.X),List.of(KeyCode.Q,KeyCode.X),List.of(KeyCode.SPACE,KeyCode.Q),List.of(KeyCode.SPACE,KeyCode.X),List.of(KeyCode.E,KeyCode.X),List.of(KeyCode.SPACE,KeyCode.E,KeyCode.Q,KeyCode.X))) {
            set(view,"camera",new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0));
            set(view,"lastTick",0L);
            for(var key:keys) key(viewport,key,true);
            timer.handle(1_000_000_000L);
            timer.handle(1_100_000_000L);
            double expected=500 + 8 * (((keys.contains(KeyCode.SPACE) || keys.contains(KeyCode.E)) ? 1 : 0) - ((keys.contains(KeyCode.Q) || keys.contains(KeyCode.X)) ? 1 : 0));
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
