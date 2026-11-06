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
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Authored asymmetric filename/native-coordinate navigation; no application stores or GPU child. */
public final class WorldNavigationUiTest {
    private static final int X = 7, Y = 19;
    private static final double SIZE = WorldFormats.TILE_SIZE;
    // Default flight preset over one 0.1 s timer step.
    private static final double STEP = WorldControls.Speed.FLY.yardsPerSecond * .1;
    private static final int[][] MOVES = {
        {1,0}, {0,0}, {-1,0}, {0,0}, {0,1}, {0,0}, {0,-1}, {0,0},
        {1,1}, {-1,1}, {-1,-1}, {1,-1}, {0,0}
    };
    private static final CompletableFuture<Void> DONE = new CompletableFuture<>();
    private static final java.util.concurrent.atomic.AtomicBoolean RACE_CHECKED = new java.util.concurrent.atomic.AtomicBoolean();

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
            streamingSizeIsDebouncedPerPulse(view);
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
                await(fixture,RACE_CHECKED::get,() -> DONE.complete(null));
            });
    }

    /** The stream request size: first size at once, a new size after one quiet pulse, reset. */
    private static void streamingSizeIsDebouncedPerPulse(WorldView view) {
        var viewport=(javafx.scene.layout.Region)view.lookup("#world-viewport");
        invoke(view,"resetStreamingSize");
        require((long)get(view,"streamingPolicy")==1 && (int)get(view,"streamingWidth")==0,"Reset starts at policy 1 without a size");
        viewport.resize(640,400);
        invoke(view,"streamingSize");
        require((int)get(view,"streamingWidth")==640 && (int)get(view,"streamingHeight")==400
                && (long)get(view,"streamingPolicy")==1,"The first size applies at once without a policy bump");
        for (int step=1; step<=5; step++) {
            viewport.resize(640+step*10,400+step*4);
            invoke(view,"streamingSize");
            require(!pulse(view),"A size still changing never settles");
        }
        require((int)get(view,"streamingWidth")==640 && (long)get(view,"streamingPolicy")==1,
                "During the drag the old size and policy are requested");
        require(pulse(view),"After one quiet pulse the new size settles and a camera request is made");
        invoke(view,"streamingSize");
        // 690 x 420 fits the 800 x 500 cap unscaled.
        require((int)get(view,"streamingWidth")==690 && (int)get(view,"streamingHeight")==420
                && (long)get(view,"streamingPolicy")==2,"The settled size is sent once, with one policy bump");
        require(!pulse(view),"Nothing more owed");
        invoke(view,"resetStreamingSize");
        require((long)get(view,"streamingPolicy")==1 && (int)get(view,"streamingWidth")==0,"A new stream resets");
    }

    private static boolean pulse(WorldView view) {
        try{var m=WorldView.class.getDeclaredMethod("streamingSizePulse");m.setAccessible(true);return (boolean)m.invoke(view);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }

    /** Showing stored controls sets every control without saving a half-applied value. */
    private static void storedControlsLoadWithoutBeingOverwritten(WorldView view) {
        var saved=new ArrayList<String>();
        view.controls(new WorldControls.Store() {
            public java.util.concurrent.CompletableFuture<String> load() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
            public void save(String value) { saved.add(value); }
        });
        var stored=WorldControls.DEFAULTS.withSensitivity(.013).withInvertY(true).withSpeed(WorldControls.Speed.TURBO)
                .withKeys(WorldControls.Layout.AZERTY.keys());
        view.showControls(stored);
        var invert=(javafx.scene.control.CheckBox)view.lookup("#world-invert-look");
        var slider=(javafx.scene.control.Slider)view.lookup("#world-look-sensitivity");
        require(saved.isEmpty(),"Loading controls never saves: "+saved);
        require(invert.isSelected() && Math.abs(slider.getValue()-.013)<1e-12 && input(view).controls().equals(stored),
                "Stored invert-Y and sensitivity both apply");
        require(slider.getBlockIncrement()==.001,"Slider steps suit its range");
        @SuppressWarnings("unchecked")
        var speed=(javafx.scene.control.ChoiceBox<WorldControls.Speed>)view.lookup("#world-flight-speed");
        require(speed.getValue()==WorldControls.Speed.TURBO,"The stored speed preset shows");
        require(view.lookupAll(".label").stream().anyMatch(n -> n instanceof javafx.scene.control.Label l
                && l.getText().startsWith("Z Q S D move")),"The key help follows the stored bindings");
        invert.fire();
        require(saved.size()==1 && WorldControls.decode(saved.getFirst()).equals(stored.withInvertY(false)),
                "A user change saves the whole value: "+saved);
        view.showControls(WorldControls.DEFAULTS);
        view.controls(WorldControls.memory());
        controlsLoadRace(view, invert);
    }

    /** A slow load never overwrites an edit made meanwhile; a load that wins applies. */
    private static void controlsLoadRace(WorldView view, javafx.scene.control.CheckBox invert) {
        var stored=WorldControls.DEFAULTS.withInvertY(true).withSensitivity(.015);
        var slow=new java.util.concurrent.CompletableFuture<String>();
        var savedSlow=new ArrayList<String>();
        view.controls(new WorldControls.Store() {
            public java.util.concurrent.CompletableFuture<String> load() { return slow; }
            public void save(String value) { savedSlow.add(value); }
        });
        require(!invert.isSelected(),"Defaults shown while the load is pending");
        invert.fire();
        require(savedSlow.size()==1,"The edit saves at once");
        require(input(view).controls().invertY(),"The edit applies");
        // The load completes after the edit; its result is applied on a later FX turn.
        slow.complete(stored.encode());
        var fast=new java.util.concurrent.CompletableFuture<String>();
        Platform.runLater(() -> {
            try {
                // Later test steps may have changed controls since; the slow load must not have
                // applied its distinctive sensitivity.
                require(input(view).controls().sensitivity()!=stored.sensitivity(),
                        "A load finishing after an edit is ignored: "+input(view).controls());
                view.controls(new WorldControls.Store() {
                    public java.util.concurrent.CompletableFuture<String> load() { return fast; }
                    public void save(String value) { throw new AssertionError("A load never saves"); }
                });
                fast.complete(stored.encode());
                Platform.runLater(() -> {
                    try {
                        require(input(view).controls().equals(stored),"A load before any edit applies");
                        view.showControls(WorldControls.DEFAULTS);
                        view.controls(WorldControls.memory());
                        RACE_CHECKED.set(true);
                    } catch (Throwable error) { DONE.completeExceptionally(error); }
                });
            } catch (Throwable error) { DONE.completeExceptionally(error); }
        });
    }

    private static void controls(WorldView view) {
        var viewport=view.lookup("#world-viewport");
        var timer=(AnimationTimer)get(view,"timer");
        // Prevent unrelated rendering/discovery while exercising the actual timer's movement branch.
        input(view).holdCadence();
        var smooth=(javafx.scene.control.CheckBox)view.lookup("#world-smooth-motion");
        require(smooth.isSelected(),"Smoothed motion is the default");
        // The exact oracles below pin the established unsmoothed free-flight displacement.
        smooth.fire();
        require(!smooth.isSelected(),"Smoothing can be disabled");
        for(var keys:List.of(List.of(KeyCode.SPACE),List.of(KeyCode.E),List.of(KeyCode.SPACE,KeyCode.E),List.of(KeyCode.Q),List.of(KeyCode.X),List.of(KeyCode.Q,KeyCode.X),List.of(KeyCode.SPACE,KeyCode.Q),List.of(KeyCode.SPACE,KeyCode.X),List.of(KeyCode.E,KeyCode.X),List.of(KeyCode.SPACE,KeyCode.E,KeyCode.Q,KeyCode.X))) {
            set(view,"camera",new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0));
            input(view).idle();
            for(var key:keys) key(viewport,key,true);
            timer.handle(1_000_000_000L);
            timer.handle(1_100_000_000L);
            double expected=500 + STEP * (((keys.contains(KeyCode.SPACE) || keys.contains(KeyCode.E)) ? 1 : 0) - ((keys.contains(KeyCode.Q) || keys.contains(KeyCode.X)) ? 1 : 0));
            double actual=((TerrainRenderer.Camera)get(view,"camera")).z();
            require(Math.abs(actual-expected)<1e-9,"Actual key/timer ascent "+keys+": "+actual+" expected "+expected);
            for(var key:keys) key(viewport,key,false);
            timer.handle(1_200_000_000L);
            require(((TerrainRenderer.Camera)get(view,"camera")).z()==actual,"Key release stops movement");
        }
        // Actual viewport key events and timer, with independent north/east direction oracles.
        for (var code : List.of(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.A, KeyCode.D, KeyCode.W)) {
            var start = new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0);
            set(view,"camera",start);
            input(view).idle();
            key(viewport,code,true);
            timer.handle(2_000_000_000L);
            timer.handle(2_100_000_000L);
            key(viewport,code,false);
            var moved=(TerrainRenderer.Camera)get(view,"camera");
            if (code == KeyCode.LEFT || code == KeyCode.RIGHT) {
                double wanted = (code == KeyCode.LEFT ? .1 : -.1) * com.vibeminer.rendering.world.ExplorerMotion.TURN_RATE;
                require(Math.abs(moved.yaw()-wanted)<1e-12,"Actual turn key geographic yaw: "+code);
                var nativeCamera=com.vibeminer.rendering.world.TerrainCamera.from(moved);
                var east=nativeCamera.eye(start.x()+10,start.y()-4,start.z());
                require(code == KeyCode.RIGHT ? east.right()<4 : east.right()>4,
                        "Actual key turns toward the same side in native projection: "+code);
            } else if (code == KeyCode.W) {
                require(Math.abs(moved.x()-start.x()-STEP)<1e-9,"W moves north at yaw zero");
            } else {
                double wanted = code == KeyCode.D ? -STEP : STEP;
                require(Math.abs(moved.y()-start.y()-wanted)<1e-9,"Actual strafe key geographic direction: "+code);
            }
        }
        for (double delta : new double[] {-25,25}) {
            var start=new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0);
            set(view,"camera",start);
            mouse(viewport,javafx.scene.input.MouseEvent.MOUSE_PRESSED,50,50,javafx.scene.input.MouseButton.PRIMARY);
            mouse(viewport,javafx.scene.input.MouseEvent.MOUSE_DRAGGED,50+delta,50,javafx.scene.input.MouseButton.PRIMARY);
            mouse(viewport,javafx.scene.input.MouseEvent.MOUSE_RELEASED,50+delta,50,javafx.scene.input.MouseButton.PRIMARY);
            require(viewport.getCursor()==null,"Left-drag look keeps the cursor visible");
            timer.handle(2_200_000_000L);
            var left=(TerrainRenderer.Camera)get(view,"camera");
            require(Math.abs(left.yaw()+delta*WorldControls.DEFAULT_SENSITIVITY)<1e-12,"Left drag looks without capture");
            set(view,"camera",start);
            mouse(viewport,javafx.scene.input.MouseEvent.MOUSE_PRESSED,50,50,javafx.scene.input.MouseButton.SECONDARY);
            require(viewport.getCursor()==javafx.scene.Cursor.NONE,"Right-button look hides the cursor");
            mouse(viewport,javafx.scene.input.MouseEvent.MOUSE_DRAGGED,50+delta,50,javafx.scene.input.MouseButton.SECONDARY);
            require(start.equals(get(view,"camera")),"Look waits for the next pulse");
            timer.handle(2_300_000_000L);
            mouse(viewport,javafx.scene.input.MouseEvent.MOUSE_RELEASED,50+delta,50,javafx.scene.input.MouseButton.SECONDARY);
            require(viewport.getCursor()==null,"Releasing the right button shows the cursor");
            var moved=(TerrainRenderer.Camera)get(view,"camera");
            require(Math.abs(moved.yaw()+delta*WorldControls.DEFAULT_SENSITIVITY)<1e-12,"Right-button look turns toward actual screen side");
        }
        storedControlsLoadWithoutBeingOverwritten(view);
        var before = new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,.7,-.8);
        set(view,"camera",before);
        double originalSpeed = input(view).speed();
        scroll(viewport,40,false);
        var zoomed = (TerrainRenderer.Camera)get(view,"camera");
        double dx=zoomed.x()-before.x(),dy=zoomed.y()-before.y(),dz=zoomed.z()-before.z();
        require(dx>0 && dy>0 && dz<0,"Wheel moves forward along pitched view, including native altitude");
        require(Math.abs(Math.atan2(dy,dx)-before.yaw())<1e-8,"Wheel preserves view yaw");
        require(Math.abs(dz/Math.hypot(dx,dy)-Math.tan(before.pitch()))<1e-8,"Wheel follows pitch rather than a flat strafe");
        require(input(view).speed()==originalSpeed,"Normal wheel preserves flight speed");
        scroll(viewport,-40,false);
        var restored=(TerrainRenderer.Camera)get(view,"camera");
        require(Math.abs(restored.x()-before.x())<1e-8&&Math.abs(restored.y()-before.y())<1e-8&&Math.abs(restored.z()-before.z())<1e-8,"Reverse wheel restores view position");
        scroll(viewport,40,true);
        require(restored.equals(get(view,"camera")),"Shift+wheel does not zoom");
        require(input(view).speed()>originalSpeed,"Shift+wheel adjusts flight speed");
        @SuppressWarnings("unchecked")
        var speedChoice=(javafx.scene.control.ChoiceBox<WorldControls.Speed>)view.lookup("#world-flight-speed");
        require(speedChoice.getValue()==input(view).controls().speed(),"The speed control shows the Shift+wheel preset");
        speedChoice.setValue(WorldControls.Speed.TURBO);
        require(input(view).speed()==200,"Choosing a preset sets the flight speed");
        speedChoice.setValue(WorldControls.DEFAULTS.speed());
        require(input(view).speed()==WorldControls.Speed.FLY.yardsPerSecond,"The preset control is reset after the test");
        keyBindingEditor();
        scroll(viewport,Double.NaN,false);
        require(restored.equals(get(view,"camera")),"Nonfinite wheel input is ignored");
        set(view,"camera",new TerrainRenderer.Camera(1_000_000,1_000_000,1_000_000,.7,.8));
        scroll(viewport,Double.MAX_VALUE,false);
        var bounded=(TerrainRenderer.Camera)get(view,"camera");
        require(bounded.x()<=1_000_000&&bounded.y()<=1_000_000&&bounded.z()<=1_000_000,"Wheel remains within camera coordinate bounds");
        walk(view,timer,viewport);
        set(view,"camera",before);
        input(view).controls(WorldControls.DEFAULTS);
    }

    /** The key editor's capture: a press binds, Escape cancels, reserved keys keep waiting. */
    private static void keyBindingEditor() {
        var editor=new WorldKeyBindings(WorldControls.DEFAULTS);
        editor.capture(WorldControls.Action.FORWARD);
        var press=new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.I,false,false,false,false);
        editor.captured(press);
        require(press.isConsumed(),"A captured key never reaches the dialog buttons");
        require(editor.controls().keys().get(KeyCode.I)==WorldControls.Action.FORWARD,"Captured key binds");
        editor.capture(WorldControls.Action.BACK);
        editor.captured(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.W,false,false,false,false));
        require(editor.hint().startsWith("W moved from Forward to Back."),"A moved key is named: "+editor.hint());
        var idle=new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.K,false,false,false,false);
        editor.captured(idle);
        require(!idle.isConsumed() && !editor.controls().keys().containsKey(KeyCode.K),"Without a capture keys pass through");
        editor.capture(WorldControls.Action.BACK);
        editor.captured(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.F11,false,false,false,false));
        require(!editor.controls().keys().containsKey(KeyCode.F11),"Reserved keys never bind");
        editor.captured(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
        editor.captured(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.J,false,false,false,false));
        require(!editor.controls().keys().containsKey(KeyCode.J),"Escape cancels the capture");
    }

    /** Walk mode over an authored qualified ADT18 surface and over the unqualified fixture mesh. */
    private static void walk(WorldView view,AnimationTimer timer,Node viewport) {
        var walk=(javafx.scene.control.CheckBox)view.lookup("#world-walk-mode");
        var status=(javafx.scene.control.Label)view.lookup("#world-motion-status");
        require(!walk.isSelected(),"Free flight is the default");
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,false);
        require(walk.isSelected(),"F toggles walking once per press, ignoring key repeat");
        // The navigation fixture's single-triangle tiles are not the admitted ADT18 layout.
        var held=new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0);
        set(view,"camera",held);
        input(view).idle();
        key(viewport,KeyCode.SPACE,true);
        key(viewport,KeyCode.W,true);
        timer.handle(3_000_000_000L);
        timer.handle(3_100_000_000L);
        key(viewport,KeyCode.SPACE,false);
        key(viewport,KeyCode.W,false);
        var unqualified=(TerrainRenderer.Camera)get(view,"camera");
        require(unqualified.z()==500,"Walking without qualified ground holds altitude and ignores Space");
        require(Math.abs(unqualified.x()-held.x()-.7)<1e-9,"Walking uses the walk speed, not flight speed");
        require(status.getText().contains("altitude held"),"Unavailable ground is reported: "+status.getText());
        var surface=com.vibeminer.rendering.world.TerrainGround.of(List.of(
                com.vibeminer.rendering.world.TerrainGround.Tile.of(new com.vibeminer.rendering.world.TerrainScene.Tile(X,Y,
                        WorldFormats.adt(authoredRoot(10000,20000),() -> false)))));
        set(view,"ground",surface);
        input(view).idle();
        timer.handle(4_000_000_000L);
        timer.handle(4_100_000_000L);
        var grounded=(TerrainRenderer.Camera)get(view,"camera");
        double eye=surface.heightAt(grounded.x(),grounded.y())+com.vibeminer.rendering.world.ExplorerMotion.EYE_HEIGHT;
        require(Double.isFinite(eye) && Math.abs(grounded.z()-eye)<1e-9,"Walking follows the rendered surface: "+grounded.z()+" vs "+eye);
        require(status.getText().startsWith("Walking · eye"),"Following ground is reported: "+status.getText());
        walk.fire();
        require(!walk.isSelected(),"Walking can be turned off");
        require(status.getText().startsWith("Free flight"),"Free flight is reported");
        smoothDefault(view);
    }

    private static void smoothDefault(WorldView view) {
        var smooth=(javafx.scene.control.CheckBox)view.lookup("#world-smooth-motion");
        smooth.fire();
        require(smooth.isSelected(),"Smoothing restored");
    }

    /** Authored split-root ADT18 bytes whose chunk grid covers one tile below the given maxima. */
    private static byte[] authoredRoot(float maxX,float maxY) {
        int chunkBytes=8+128+8+580;
        var bytes=java.nio.ByteBuffer.allocate(12+256*chunkBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x4d564552).putInt(4).putInt(18);
        for(int i=0;i<256;i++) {
            bytes.putInt(0x4d434e4b).putInt(chunkBytes-8);
            int start=bytes.position(),x=i%16,y=i/16;
            bytes.putInt(start+4,x).putInt(start+8,y);
            bytes.putFloat(start+104,(float)(maxX-y*SIZE/16));
            bytes.putFloat(start+108,(float)(maxY-x*SIZE/16));
            bytes.putFloat(start+112,40);
            bytes.position(start+128);
            bytes.putInt(0x4d435654).putInt(580);
            for(int sample=0;sample<145;sample++) bytes.putFloat(sample%9);
        }
        return bytes.array();
    }

    private static void mouse(Node node,javafx.event.EventType<javafx.scene.input.MouseEvent> type,double x,double y,
            javafx.scene.input.MouseButton button) {
        boolean down=type!=javafx.scene.input.MouseEvent.MOUSE_RELEASED;
        Event.fireEvent(node,new javafx.scene.input.MouseEvent(type,x,y,x,y,button,1,
                false,false,false,false,down&&button==javafx.scene.input.MouseButton.PRIMARY,
                false,down&&button==javafx.scene.input.MouseButton.SECONDARY,false,false,true,new PickResult(node,x,y)));
    }

    private static void scroll(Node target,double delta,boolean shift) {
        Event.fireEvent(target,new ScrollEvent(ScrollEvent.SCROLL,10,10,10,10,shift,false,false,false,false,false,
                0,delta,0,delta,ScrollEvent.HorizontalTextScrollUnits.NONE,0,
                ScrollEvent.VerticalTextScrollUnits.NONE,0,0,new PickResult(target,10,10)));
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
    private static WorldExplorerInput input(WorldView view) {return (WorldExplorerInput)get(view,"input");}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
