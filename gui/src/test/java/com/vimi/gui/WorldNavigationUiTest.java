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
            require(((javafx.scene.control.Label)get(view,"currentArea")).getText().equals("Terrain area: "+expectedArea),"Moving camera updates recorded zone/subzone: "+expectedArea);
            // HUD step 2: the zone line shows the zone and the most specific area at once.
            var chain=expectedArea.split(" · ");
            var zone=(javafx.scene.control.Label)view.lookup("#world-zone-name");
            var subzone=(javafx.scene.control.Label)view.lookup("#world-zone-subzone");
            var banner=(javafx.scene.control.Label)view.lookup("#world-zone-banner");
            require(zone.getText().equals(chain[0]) && subzone.getText().equals(chain[chain.length-1]) && subzone.isVisible(),
                    "Zone line: "+zone.getText()+" / "+subzone.getText());
            require(banner.getText().equals(chain[chain.length-1]),"The banner names the area entered: "+banner.getText());
            var camera=(TerrainRenderer.Camera)get(view,"camera");
            var pill=(javafx.scene.control.Label)view.lookup("#world-coordinates");
            require(pill.isVisible() && pill.getText().startsWith(String.format(java.util.Locale.ROOT,"x %.1f  y %.1f  alt %.1f",camera.x(),camera.y(),camera.z()))
                            && pill.getText().contains("\ntile "),
                    "The coordinates pill follows the camera: "+pill.getText());
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
            viewportTakesThePage(view);
            fullscreenAndHud(view);
            invoke(view,"requestFrame");
            await(fixture,() -> !view.lookup("#world-minimap-activity").isVisible(),() -> {
                int before = fixture.minimapCalls.get();
                fixture.revision.incrementAndGet();
                invoke(view,"requestFrame");
                var viewport = (javafx.scene.layout.StackPane)view.lookup("#world-viewport");
                viewport.resize(viewport.getWidth()+17,viewport.getHeight()+11);
                invoke(view,"requestFrame");
                require(!view.lookup("#world-minimap").isVisible(),"Retired source cannot reveal minimap after resize");
                require(((javafx.scene.control.Label)get(view,"currentArea")).getText().equals("Terrain area unavailable")
                                && ((javafx.scene.control.Label)view.lookup("#world-zone-name")).getText().isEmpty()
                                && !view.lookup("#world-zone-text").isVisible(),
                        "Retired source cannot retain current-area name, on the area line or the zone line");
                require(fixture.minimapCalls.get()==before,"Retired source cannot request artwork after resize");
                showInventoryEndsFullscreen(view);
                await(fixture,RACE_CHECKED::get,() -> DONE.complete(null));
            });
    }

    /** Fires a key press on the target, with or without Alt. */
    private static void press(Node target, KeyCode code, boolean alt) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false));
    }

    private static void release(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
    }

    /** F11 / Alt+Enter move the viewport to its own stage and back; Alt+Z hides the HUD. */
    private static void fullscreenAndHud(WorldView view) {
        var viewport=(javafx.scene.layout.Region)view.lookup("#world-viewport");
        var full=(WorldFullscreen)get(view,"fullscreen");
        full.windowed(true);
        var home=viewport.getScene();
        var content=(javafx.scene.layout.BorderPane)get(view,"content");
        press(viewport,KeyCode.F11,false);
        require(full.active() && viewport.getScene()==full.stage().getScene() && viewport.getScene()!=home,
                "F11 moves the viewport onto the full-screen stage");
        require(content.getCenter()!=viewport,"The explorer layout no longer holds the viewport");
        require(viewportActive(view),"The viewport stays active on the full-screen stage");
        require(WorldFullscreen.EXIT_HINT.equals(full.stage().getFullScreenExitHint()),"Exit hint names the keys");
        require(!input(view).keysHeld(),"F11 is never a held flight key");
        var overlay=view.lookup("#world-fullscreen-status");
        require(overlay==null,"The status overlay is not in the window");
        var shown=full.stage().getScene().getRoot().lookup("#world-fullscreen-status");
        require(shown!=null && shown.isVisible(),"Full screen shows a status line");
        var line=(javafx.scene.control.Label)shown;
        var inspection=(javafx.scene.control.Label)get(view,"inspection");
        String inspected=inspection.getText();
        inspection.setText((String)getStatic("INSPECT_HINT"));
        require(line.getText().isEmpty() && line.getOpacity()==0,"The full-screen line never shows the click hint");
        inspection.setText("Terrain · tile 7,19");
        require(line.getText().equals("Terrain · tile 7,19") && line.getOpacity()==1,"It shows an inspect result");
        inspection.setText(inspected);
        require(full.stage().getScene().getStylesheets().equals(home.getStylesheets()),"Full screen keeps the app's styles");
        // Our own move to the full-screen stage takes focus from the viewport; the F11 latch stays.
        invoke(view,"viewportFocusLost");
        for (int i=0;i<30;i++) press(full.stage().getScene().getRoot(),KeyCode.F11,false);
        require(full.active(),"Holding F11 (key repeat) never toggles back, even across the stage's focus change");
        release(full.stage().getScene().getRoot(),KeyCode.F11);
        press(full.stage().getScene().getRoot(),KeyCode.F11,false);
        require(!full.active() && viewport.getScene()==home && content.getCenter()==viewport,
                "F11 again, on the full-screen scene, returns the viewport to the explorer");
        release(viewport,KeyCode.F11);
        press(viewport,KeyCode.ENTER,true);
        require(full.active(),"Alt+Enter enters full screen");
        var stage=full.stage();
        stage.fireEvent(new javafx.stage.WindowEvent(stage,javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        require(!full.active() && !stage.isShowing() && content.getCenter()==viewport,
                "Closing the full-screen stage returns the viewport");
        release(viewport,KeyCode.ENTER);
        press(viewport,KeyCode.F11,false);
        release(viewport,KeyCode.F11);
        require(full.active(),"Entered again");
        view.deactivate();
        require(!full.active() && content.getCenter()==viewport,"Hiding the tab ends full screen");
        view.activate();
        var split=(javafx.scene.control.SplitPane)get(view,"split");
        var hint=view.lookup("#world-hud-hint");
        // The HUD's hideable rows; the HUD layer itself stays for the developer overlay's slot.
        var hud=view.lookup("#world-hud-top-left");
        // The map list already left the split for the explorer (HUD step 1); Alt+Z hides the HUD.
        require(split.getItems().size()==1 && content.getBottom().isVisible() && hud.isVisible() && !hint.isVisible(),
                "The HUD starts shown, without the map list");
        for (int i=0;i<30;i++) press(viewport,KeyCode.Z,true);
        require(!content.getBottom().isVisible() && !content.getBottom().isManaged() && !hud.isVisible(),
                "Alt+Z hides the slim bar and the HUD overlays once, however long it is held");
        require(split.getItems().size()==1 && split.getItems().getFirst()==content.getParent(),"The map list stays out");
        require(hint.isVisible(),"A hint says how to bring the HUD back");
        release(viewport,KeyCode.Z);
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(content.getBottom().isVisible() && hud.isVisible() && !hint.isVisible(),"Alt+Z shows them again");
        var hide=(javafx.scene.control.Button)view.lookup("#world-hide-hud");
        require(hide.getText().equals("Hide HUD (H or Alt+Z)"),"The button names the HUD: "+hide.getText());
        hide.fire();
        require(!hud.isVisible() && !content.getBottom().isVisible(),"The Hide HUD button hides it");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(hud.isVisible() && content.getBottom().isVisible(),"Alt+Z restores after the button");
        // The HUD lives in the viewport, so it comes along to full screen, and Alt+Z works there.
        press(viewport,KeyCode.F11,false);
        release(viewport,KeyCode.F11);
        require(full.active() && full.stage().getScene().getRoot().lookup("#world-hud-top-left")==hud,"The HUD is on the full-screen stage");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(full.active() && !hud.isVisible() && hint.isVisible(),"Alt+Z hides the HUD in full screen");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(hud.isVisible(),"And shows it again");
        press(viewport,KeyCode.F11,false);
        release(viewport,KeyCode.F11);
        require(!full.active() && content.getCenter()==viewport,"Left full screen");
        wasdMovesAfterFullscreen(view,viewport);
        // Alt+Z first, then F11: the hint follows into full screen and names the right keys there.
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(!hud.isVisible() && hint.isVisible() && ((javafx.scene.control.Label)hint).getText().equals("H or Alt+Z shows the HUD · F11 full screen"),
                "Hidden HUD in the window: "+((javafx.scene.control.Label)hint).getText());
        press(viewport,KeyCode.F11,false);
        release(viewport,KeyCode.F11);
        require(full.active() && !hud.isVisible() && hint.isVisible()
                        && full.stage().getScene().getRoot().lookup("#world-hud-hint")==hint
                        && ((javafx.scene.control.Label)hint).getText().equals("H or Alt+Z shows the HUD · F11 or Esc leaves full screen"),
                "F11 with the HUD hidden: one hint, on the full-screen stage, saying how to leave: "
                        +((javafx.scene.control.Label)hint).getText());
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(hud.isVisible() && !hint.isVisible(),"Alt+Z shows the HUD in full screen; no stale hint");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        full.exit();
        require(!full.active() && hint.isVisible() && ((javafx.scene.control.Label)hint).getText().equals("H or Alt+Z shows the HUD · F11 full screen"),
                "Leaving full screen another way (Esc, close) brings the window hint back");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(hud.isVisible() && !hint.isVisible(),"HUD shown again");
        // A latch whose release went elsewhere is cleared when the viewport loses focus.
        press(viewport,KeyCode.Z,true);
        require(!hud.isVisible(),"Alt+Z held");
        set(view,"layoutToggledAt",Long.MIN_VALUE/2); // no full-screen move just happened
        invoke(view,"viewportFocusLost");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(hud.isVisible(),"After a focus loss the next Alt+Z press counts, even without a release first");
        key(viewport,KeyCode.ALT,false);
        closeEndsFullscreen();
        leavingTheSceneEndsFullscreen();
    }

    /** WASD reaches flight input straight after full screen ends: the camera moves. */
    private static void wasdMovesAfterFullscreen(WorldView view, Node viewport) {
        var input=input(view);
        var start=(TerrainRenderer.Camera)get(view,"camera");
        input.idle();
        key(viewport,KeyCode.W,true);
        require(input.keysHeld(),"W is held after leaving full screen");
        long now=System.nanoTime();
        input.pulse(now);
        input.pulse(now+100_000_000L);
        var moved=(TerrainRenderer.Camera)get(view,"camera");
        key(viewport,KeyCode.W,false);
        require(moved.x()!=start.x() || moved.y()!=start.y(),"W moved the camera after full screen: "+start+" -> "+moved);
        input.clear();
        set(view,"camera",start);
    }

    /** A view taken out of its scene while full screen ends full screen and closes the stage. */
    private static void leavingTheSceneEndsFullscreen() {
        var other=new WorldView(() -> null, () -> 0);
        var scene=new Scene(other,320,240);
        set(other,"explorer",true);
        var full=(WorldFullscreen)get(other,"fullscreen");
        full.windowed(true);
        invoke(other,"toggleFullscreen");
        require(full.active() && other.getScene()==scene,"Throwaway view in full screen");
        var stage=full.stage();
        scene.setRoot(new javafx.scene.layout.Pane());
        require(other.getScene()==null && !full.active() && !stage.isShowing(),
                "Leaving the scene ends full screen and closes its stage");
        other.close();
    }

    /**
     * HUD step 1: the viewport is the page. The map list leaves the split while exploring and
     * opens as an overlay panel (Map button or M); the heading becomes the map name top-left; the
     * control rows fold into one slim bar whose "More" toggle shows the rest. Nothing is removed.
     */
    private static void viewportTakesThePage(WorldView view) {
        var viewport=(javafx.scene.layout.StackPane)view.lookup("#world-viewport");
        var content=(javafx.scene.layout.BorderPane)get(view,"content");
        var split=(javafx.scene.control.SplitPane)get(view,"split");
        var sidebar=(Node)get(view,"sidebarPane");
        require(split.getItems().size()==1 && split.getItems().getFirst()==content.getParent() && sidebar.getParent()==null,
                "The map list is not in the split while exploring");
        require(content.getTop()==null && content.getCenter()==viewport,"Nothing sits above the viewport");
        // Acceptance: the view takes at least 80% of the window (it was about 27%). This scene is
        // the World view alone, without the app's header and tabs, so it guards the layout, not
        // the share of a real window. The bar under the view is at most one row of controls.
        view.getScene().getRoot().applyCss();
        view.getScene().getRoot().layout();
        double share=viewport.getWidth()*viewport.getHeight()/(view.getScene().getWidth()*view.getScene().getHeight());
        require(share>=.85,"The viewport takes "+Math.round(share*100)+"% of the World view (no app chrome)");
        var bottomHeight=((javafx.scene.layout.Region)content.getBottom()).getHeight();
        require(bottomHeight<=48,"The bottom is one slim row: "+bottomHeight+" px");
        System.out.println("World HUD step 1: the viewport takes "+Math.round(share*100)+"% of a "
                +(int)view.getScene().getWidth()+"x"+(int)view.getScene().getHeight()+" window");
        var hud=view.lookup("#world-hud");
        require(hud!=null && hud.getParent()==viewport && hud.isVisible() && !hud.isFocusTraversable(),"The HUD is a viewport overlay");
        var mapButton=(Button)view.lookup("#world-hud-map");
        var mapName=(javafx.scene.control.Label)view.lookup("#world-hud-map-name");
        var heading=(javafx.scene.control.Label)get(view,"heading");
        require(mapButton.getText().startsWith("Map") && !mapButton.isFocusTraversable(),"Map button top-left: "+mapButton.getText());
        require(mapName.getText().equals(heading.getText()) && !mapName.getText().isBlank(),"The map name is the former heading: "+mapName.getText());
        var status=(Node)get(view,"status");
        require(isWithin(status,hud),"The status line floats in the HUD");
        // HUD step 2: the status icons replace the slim bar; step 5: the rest lives on menu pages.
        var row=view.lookup("#world-status-icons");
        require(view.lookup("#world-hud-bar")==null && view.lookup("#world-hud-more")==null && isWithin(row,hud),
                "No slim bar and no More drawer: the icon row is the HUD's bottom-left");
        require(content.getBottom() instanceof javafx.scene.layout.VBox bottom && bottom.getChildren().equals(List.of(get(view,"inspection"))),
                "Only an inspect result can sit under the view");
        statusIcons(view,viewport);
        // HUD step 2: the area and position lines leave the bar; the zone text and the coordinates
        // pill show them over the view and keep the full lines as their tooltips.
        require(view.lookup("#world-current-area")==null && view.lookup("#world-position")==null,
                "The full area and position lines are no longer rows");
        var zoneLine=view.lookup("#world-zone-text");
        var pill=(javafx.scene.control.Label)view.lookup("#world-coordinates");
        require(isWithin(zoneLine,hud) && isWithin(pill,hud) && isWithin(view.lookup("#world-zone-banner"),hud),"Zone text, banner and pill are HUD overlays");
        require(pill.getTooltip().getText().equals(((javafx.scene.control.Label)get(view,"position")).getText())
                        && !pill.getTooltip().getText().isBlank(),
                "The pill's tooltip is the full position line: "+pill.getTooltip().getText());
        // The copy goes through the view's hook, so the test never needs the system clipboard
        // (unavailable on a locked desktop); the system clipboard itself is an owner check.
        var copied=new java.util.concurrent.atomic.AtomicReference<String>();
        view.coordinatesCopy(copied::set);
        mouse(pill,javafx.scene.input.MouseEvent.MOUSE_PRESSED,4,4,javafx.scene.input.MouseButton.PRIMARY);
        require(!input(view).looking(),"A press on the pill is not mouse look");
        mouse(pill,javafx.scene.input.MouseEvent.MOUSE_RELEASED,4,4,javafx.scene.input.MouseButton.PRIMARY);
        Event.fireEvent(pill,new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,4,4,4,4,
                javafx.scene.input.MouseButton.PRIMARY,1,false,false,false,false,false,false,false,false,false,true,
                new PickResult(pill,4,4)));
        var camera=(TerrainRenderer.Camera)get(view,"camera");
        require(String.format(java.util.Locale.ROOT,"%.1f %.1f %.1f",camera.x(),camera.y(),camera.z()).equals(copied.get()),
                "A click copies x y z: "+copied.get());
        require(com.vibeminer.concepts.world.WorldBookmarks.parseCoordinates(copied.get()).isPresent(),
                "The travel panel reads the copied coordinates back");
        require(view.getScene().getFocusOwner()==viewport,"Focus stays on the viewport after copying");
        view.coordinatesCopy(WorldCoordinatesPill::systemClipboard);
        // HUD step 5: every control that was a row is on an Escape menu page; nothing is removed.
        var pages=java.util.Map.of(
                "controls",new String[] {"#world-flight-speed","#world-smooth-motion","#world-walk-mode","#world-fly-mode","#world-motion-status",
                        "#world-look-sensitivity","#world-invert-look","#world-look-status","#world-layout-qwerty",
                        "#world-layout-azerty","#world-key-bindings-open"},
                "audio",new String[] {"#world-audio","#world-music","#world-ambience","#world-music-volume",
                        "#world-ambience-volume","#world-audio-variant","#world-audio-status"},
                "display",new String[] {"#world-show-coordinates","#world-hide-hud","#world-fullscreen","#world-gpu-enabled",
                        "#world-object-textures","#world-view-distance","#world-gpu-activity","#world-gpu-retry","#world-gpu-status",
                        "#world-texture-activity","#world-texture-status","#world-texture-retry"},
                "bookmarks",new String[] {"#world-save-bookmark"},
                "instances",new String[] {"#world-instance-status","#world-instance-entrances","#world-instance-enter",
                        "#world-instance-leave"});
        pages.forEach((page,ids) -> {
            var box=view.lookup("#world-menu-page-"+page);
            for (var id : ids) require(view.lookup(id)!=null && isWithin(view.lookup(id),box),"On the "+page+" page: "+id);
        });
        require(isWithin((Node)get(view,"keyHelp"),view.lookup("#world-menu-page-controls")),"The key help is the Controls page footer");
        var inspectHint=(javafx.scene.control.Label)view.lookup("#world-inspect-hint");
        require(inspectHint!=null && isWithin(inspectHint,view.lookup("#world-menu-page-controls"))
                        && inspectHint.getText().equals(WorldView.INSPECT_HINT),
                "The click-to-inspect hint is in the Controls page footer too");
        require(isWithin((Node)get(view,"travel"),view.lookup("#world-menu-page-bookmarks")),"The bookmark list is on the Bookmarks page");
        require(isWithin(view.lookup("#world-back"),view.lookup("#world-menu-main")),"Back to the map list is a menu entry");
        // The long GPU and texture texts show once, on the developer overlay; the Display page keeps
        // Retry, whose tooltip always gives the current reason.
        for (var pair : new String[][] {{"#world-gpu-retry","#world-gpu-status"},{"#world-texture-retry","#world-texture-status"}}) {
            var retry=(Button)view.lookup(pair[0]);
            var text=(javafx.scene.control.Label)view.lookup(pair[1]);
            require(retry.getTooltip()!=null && retry.getTooltip().getText().equals(text.getText()),
                    pair[0]+" explains itself: "+(retry.getTooltip()==null?null:retry.getTooltip().getText()));
            String was=text.getText();
            text.setText("Renderer not responding; Retry");
            require(retry.getTooltip().getText().equals("Renderer not responding; Retry"),pair[0]+" follows the status text");
            text.setText(was);
        }
        for (var id : new String[] {"#world-gpu-status","#world-texture-status"})
            require(!view.lookup(id).isVisible() && !view.lookup(id).isManaged(),"Not shown on the Display page: "+id);
        // The spawn and role controls live in the minimap's tracking popup (#515), not on a page.
        for (var name : new String[] {"spawns","roles"}) {
            var overlay=get(view,name);
            if (overlay==null) continue;
            try {
                var controls=(Node)overlay.getClass().getDeclaredMethod("controls").invoke(overlay);
                require(!isWithin(controls,view.lookup("#world-menu-page-display")),"The "+name+" layers stay in the tracking popup");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        gameMenu(view,viewport);
        cardIsTheFirstEscapeLayer(view,viewport);
        fullscreenEscapeIsTheLastLayer(view,viewport);
        // M toggles the map panel; the map list moves into it and back out.
        var panel=view.lookup("#world-map-panel");
        require(panel!=null && !panel.isVisible(),"Map panel starts closed");
        for (int i=0;i<10;i++) key(viewport,KeyCode.M,true);
        require(panel.isVisible() && isWithin(sidebar,panel) && isWithin(view.lookup("#world-maps"),panel),
                "M opens the map panel with the map list, once however long it is held");
        require(isWithin(view.lookup("#world-map-panel-back"),panel) && ((Button)view.lookup("#world-map-panel-back")).getText().equals(((Button)view.lookup("#world-back")).getText()),"The panel offers the way back to the tile picker");
        require(isWithin(view.lookup("#world-tiles"),panel) && isWithin(view.lookup("#world-open"),panel),
                "The panel holds the tile inventory and its Open button");
        view.getScene().getRoot().applyCss();
        view.getScene().getRoot().layout();
        require(panel.getLayoutBounds().getWidth()<=viewport.getWidth()/2+1 && ((Node)get(view,"tileInventory")).getScaleX()<1,
                "The panel sits on the left of the view: "+panel.getLayoutBounds().getWidth()+" of "+viewport.getWidth());
        require(input(view).axes().equals(com.vibeminer.rendering.world.ExplorerMotion.Input.NONE),
                "M is not movement");
        key(viewport,KeyCode.M,false);
        // Typing in the panel's search never reaches flight input; clicks never inspect or look.
        var search=(Node)get(view,"search");
        key(search,KeyCode.W,true);
        require(!input(view).keysHeld(),"Keys typed in the panel do not fly");
        key(search,KeyCode.W,false);
        mouse(panel,javafx.scene.input.MouseEvent.MOUSE_PRESSED,20,20,javafx.scene.input.MouseButton.SECONDARY);
        require(!input(view).looking(),"A press on the panel is not mouse look");
        mouse(panel,javafx.scene.input.MouseEvent.MOUSE_RELEASED,20,20,javafx.scene.input.MouseButton.SECONDARY);
        key(viewport,KeyCode.M,true);
        key(viewport,KeyCode.M,false);
        require(!panel.isVisible() && sidebar.getParent()==null && split.getItems().size()==1
                && ((Node)get(view,"tileInventory")).getScaleX()==1,"M again closes it; the tile inventory is unscaled again");
        mapButton.fire();
        require(panel.isVisible() && view.getScene().getFocusOwner()==viewport,"The Map button opens it; focus stays on the world");
        // The panel sits under the Map row and the status rows in one column; it never covers them.
        view.getScene().getRoot().applyCss();
        view.getScene().getRoot().layout();
        var statusChip=view.lookup("#world-hud-status");
        double statusBottom=statusChip.localToScene(statusChip.getLayoutBounds()).getMaxY();
        double panelTop=panel.localToScene(panel.getLayoutBounds()).getMinY();
        require(statusChip.isVisible() && panelTop>=statusBottom,"The panel starts below the status rows: "+panelTop+" >= "+statusBottom);
        // Hooks for other HUD pieces: rows join the left column above the panel, with the HUD's
        // input rules; the core rows stay; the hidden state is read-only for followers.
        var hudNode=(WorldHud)get(view,"hud");
        var extra=new javafx.scene.control.Label("extra row");
        hudNode.addTopLeft(extra);
        var column=(javafx.scene.layout.VBox)view.lookup("#world-hud-top-left");
        var left=(javafx.scene.layout.VBox)view.lookup("#world-hud-left");
        require(column.getChildren().getLast()==extra && left.getChildren().indexOf(column)<left.getChildren().indexOf(panel),
                "Added rows go above the map panel");
        key(extra,KeyCode.W,true);
        require(!input(view).keysHeld(),"Added rows stop flight keys");
        key(extra,KeyCode.W,false);
        hudNode.removeTopLeft(panel);
        hudNode.removeTopLeft(statusChip);
        require(left.getChildren().contains(panel) && column.getChildren().contains(statusChip),"Core rows cannot be removed");
        hudNode.removeTopLeft(extra);
        require(!column.getChildren().contains(extra),"Added rows can be removed");
        require(!(hudNode.hiddenProperty() instanceof javafx.beans.value.WritableBooleanValue),"Followers get a read-only hidden state");
        // Alt+Z with the panel open closes it, so the next M opens it again.
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        require(!panel.isVisible() && sidebar.getParent()==null,"Hiding the HUD closes the map panel");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        key(viewport,KeyCode.ALT,false);
        key(viewport,KeyCode.M,true);
        key(viewport,KeyCode.M,false);
        require(panel.isVisible(),"The next M opens the panel at once");
        mapButton.fire();
        require(!panel.isVisible(),"The Map button closes it");
        hideHudKeyAndDeveloperSlot(view,viewport,hud,panel);
    }

    /**
     * H (rebindable) and Alt+Z share one hidden state; the developer overlay sits in the HUD's own
     * slot under the left column and stays when the HUD is hidden.
     */
    private static void hideHudKeyAndDeveloperSlot(WorldView view,Node viewport,Node hud,Node panel) {
        var rows=view.lookup("#world-hud-top-left");
        var bottom=((javafx.scene.layout.BorderPane)get(view,"content")).getBottom();
        var hint=view.lookup("#world-hud-hint");
        var hudNode=(WorldHud)get(view,"hud");
        require(rows.isVisible() && bottom.isVisible() && !hudNode.hidden(),"The HUD starts shown");
        for (int i=0;i<20;i++) key(viewport,KeyCode.H,true);
        require(!rows.isVisible() && !bottom.isVisible() && hint.isVisible() && hudNode.hidden(),
                "H hides the HUD and the slim bar once, however long it is held");
        require(input(view).axes().equals(com.vibeminer.rendering.world.ExplorerMotion.Input.NONE),"H is not movement");
        key(viewport,KeyCode.H,false);
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        key(viewport,KeyCode.ALT,false);
        require(rows.isVisible() && bottom.isVisible() && !hint.isVisible(),"Alt+Z shows what H hid: one state");
        press(viewport,KeyCode.Z,true);
        release(viewport,KeyCode.Z);
        key(viewport,KeyCode.ALT,false);
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(rows.isVisible() && !hint.isVisible(),"H shows what Alt+Z hid");
        // M opens the panel, H closes it with the HUD, the next M opens it again.
        key(viewport,KeyCode.M,true);
        key(viewport,KeyCode.M,false);
        require(panel.isVisible(),"M opens the panel");
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(!panel.isVisible() && !rows.isVisible(),"H with the panel open closes it");
        key(viewport,KeyCode.M,true);
        key(viewport,KeyCode.M,false);
        require(panel.isVisible() && rows.isVisible(),"M shows the HUD and opens the panel");
        key(viewport,KeyCode.M,true);
        key(viewport,KeyCode.M,false);
        // The developer overlay lives in the HUD's slot, under the rows, and ignores the hidden state.
        var overlay=view.devOverlay();
        var slot=view.lookup("#world-hud-dev-slot");
        var left=(javafx.scene.layout.VBox)view.lookup("#world-hud-left");
        require(overlay.getParent()==slot && isWithin(slot,view.lookup("#world-hud")) && !isWithin(slot,rows)
                        && left.getChildren().indexOf(slot)>left.getChildren().indexOf(rows)
                        && left.getChildren().indexOf(slot)<left.getChildren().indexOf(panel),
                "The developer overlay is in the HUD's slot, under the left column's rows");
        require(!overlay.isVisible() && !slot.isVisible() && !slot.isManaged(),"Off, the slot takes no room");
        key(viewport,KeyCode.F3,true);
        key(viewport,KeyCode.F3,false);
        require(overlay.isVisible() && slot.isVisible(),"F3 shows the overlay");
        view.getScene().getRoot().applyCss();
        view.getScene().getRoot().layout();
        double rowsBottom=rows.localToScene(rows.getLayoutBounds()).getMaxY();
        double overlayTop=overlay.localToScene(overlay.getLayoutBounds()).getMinY();
        require(overlayTop>=rowsBottom,"The overlay starts below the Map row and status: "+overlayTop+" >= "+rowsBottom);
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(!rows.isVisible() && overlay.isVisible() && slot.isVisible() && view.lookup("#world-hud").isVisible(),
                "Hiding the HUD keeps the developer overlay");
        view.getScene().getRoot().applyCss();
        view.getScene().getRoot().layout();
        require(overlay.localToScene(overlay.getLayoutBounds()).getMinY()<overlayTop,
                "With the rows hidden the overlay moves up to the top of the column");
        // The hidden-HUD hint sits in the same column, above the overlay: they never overlap.
        var hintSlot=view.lookup("#world-hud-hint-slot");
        require(hint.isVisible() && hint.getParent()==hintSlot && hintSlot.isVisible()
                        && left.getChildren().indexOf(hintSlot)<left.getChildren().indexOf(slot),
                "The hint is in the HUD column, above the developer slot");
        var hintBox=hint.localToScene(hint.getLayoutBounds());
        var overlayBox=overlay.localToScene(overlay.getLayoutBounds());
        require(hintBox.getHeight()>0 && hintBox.getMaxY()<=overlayBox.getMinY() && !hintBox.intersects(overlayBox),
                "Hint and overlay do not overlap: "+hintBox+" / "+overlayBox);
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(rows.isVisible() && overlay.isVisible(),"Shown again, overlay still on");
        // Rebinding: J hides the HUD, H no longer does.
        var controls=input(view).controls();
        view.showControls(controls.unbind(WorldControls.Action.HIDE_HUD).bind(KeyCode.J,WorldControls.Action.HIDE_HUD));
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(rows.isVisible(),"An unbound H does nothing");
        key(viewport,KeyCode.J,true);
        key(viewport,KeyCode.J,false);
        require(!rows.isVisible(),"A rebound hide HUD key works");
        require(((javafx.scene.control.Label)hint).getText().equals("J or Alt+Z shows the HUD · F11 full screen")
                        && ((Button)view.lookup("#world-hide-hud")).getText().equals("Hide HUD (J or Alt+Z)"),
                "The hint and the button follow the rebinding: "+((javafx.scene.control.Label)hint).getText());
        key(viewport,KeyCode.J,true);
        key(viewport,KeyCode.J,false);
        view.showControls(controls.unbind(WorldControls.Action.HIDE_HUD));
        require(((Button)view.lookup("#world-hide-hud")).getText().equals("Hide HUD (Alt+Z)")
                        && ((javafx.scene.control.Label)hint).getText().equals("Alt+Z shows the HUD · F11 full screen"),
                "Unbound, they name Alt+Z alone");
        view.showControls(controls);
        require(((Button)view.lookup("#world-hide-hud")).getText().equals("Hide HUD (H or Alt+Z)"),"H again");
        overlay.show(null);
        require(!slot.isVisible(),"Overlay off again");
        commandActionsAreRouted(view);
    }

    /** Every command action has a branch in the view's command dispatcher (no silent drop). */
    private static void commandActionsAreRouted(WorldView view) {
        int commands=0;
        for (var action : WorldControls.Action.values()) {
            if (!action.command) continue;
            commands++;
            // Twice, so the map panel and the hidden state end where they started.
            require(view.command(action) && view.command(action),"WorldView.command routes "+action);
        }
        // MAP_PANEL, HIDE_HUD, DEV_OVERLAY, INSPECT_PIN, SCREENSHOT (no data folder here, so it
        // only toasts), and the step 7 keys: SPAWNS, TRACKING, MINIMAP, DAY_NIGHT, five speeds
        // and BOOKMARKS (twice: opens the menu page, then closes it).
        require(commands==15,"The command actions are walked: "+commands);
        view.inspectCard().close();
        view.devOverlay().show(null);
    }

    /** HUD step 2: the status icons show the view's own controls and drive them; focus stays on the world. */
    private static void statusIcons(WorldView view, Node viewport) {
        var icons=(WorldStatusIcons)get(view,"icons");
        for (var name : new String[] {"mode","speed","smooth","music","ambience","time","render","texture","mouse"}) {
            var icon=view.lookup("#world-icon-"+name);
            require(icon!=null && !icon.isFocusTraversable(),"Icon "+name+" is in the row and never focused");
        }
        var walk=(javafx.scene.control.CheckBox)get(view,"walkMode");
        var motion=(javafx.scene.control.Label)get(view,"motionStatus");
        var mode=(Button)view.lookup("#world-icon-mode");
        require(icons.mode()==WorldStatusIcons.Mode.FLY && mode.getTooltip().getText().startsWith(motion.getText()),
                "The mode icon shows flight and the motion line: "+mode.getTooltip().getText());
        mode.fire();
        require(walk.isSelected() && icons.mode()!=WorldStatusIcons.Mode.FLY && mode.getTooltip().getText().startsWith(motion.getText()),
                "Clicking the mode icon walks: "+icons.mode()+" · "+motion.getText());
        require(view.getScene().getFocusOwner()==viewport,"Focus stays on the viewport after an icon click");
        mode.fire();
        require(!walk.isSelected() && icons.mode()==WorldStatusIcons.Mode.FLY,"And flies again");
        @SuppressWarnings("unchecked")
        var speed=(javafx.scene.control.ChoiceBox<WorldControls.Speed>)get(view,"flightSpeed");
        var chip=(Button)view.lookup("#world-icon-speed");
        var before=speed.getValue();
        chip.fire();
        var after=speed.getValue();
        require(after==WorldControls.Speed.values()[(before.ordinal()+1)%WorldControls.Speed.values().length],
                "The speed chip steps to the next preset: "+before+" -> "+after);
        require(chip.getText().equals(String.format(java.util.Locale.ROOT,"%.0f",input(view).effectiveSpeed()))
                        && chip.getTooltip().getText().startsWith(after+" preset"),
                "The chip shows the live speed: "+chip.getText()+" · "+chip.getTooltip().getText());
        for (int i=1;i<WorldControls.Speed.values().length;i++) chip.fire();
        require(speed.getValue()==before,"Five clicks come back to the first preset");
        var smooth=(javafx.scene.control.CheckBox)get(view,"smoothMotion");
        var tilde=view.lookup("#world-icon-smooth");
        require(smooth.isSelected()==tilde.isVisible(),"The smooth icon shows only while smoothing is on");
        boolean smoothBefore=smooth.isSelected();
        smooth.fire();
        require(tilde.isVisible()==!smoothBefore,"Turning smoothing over flips the icon");
        smooth.fire();
        var variant=((WorldAudioControls)get(view,"audio")).variant();
        var time=(Button)view.lookup("#world-icon-time");
        require(icons.time()==WorldStatusIcons.Time.CLOCK && variant.getValue()==WorldAudioDirector.Slot.CLOCK,"By clock at first");
        time.fire();
        require(variant.getValue()==WorldAudioDirector.Slot.DAY && icons.time()==WorldStatusIcons.Time.DAY,"Clicking the time icon picks day");
        time.fire();
        require(icons.time()==WorldStatusIcons.Time.NIGHT,"Then night");
        time.fire();
        require(icons.time()==WorldStatusIcons.Time.CLOCK && variant.getValue()==WorldAudioDirector.Slot.CLOCK,"Then the clock again");
        var gpuStatus=(javafx.scene.control.Label)get(view,"gpuStatus");
        var render=(Button)view.lookup("#world-icon-render");
        require(icons.render()==WorldStatusIcons.render(((Node)get(view,"gpuActivity")).isVisible(),
                        ((javafx.scene.control.CheckBox)get(view,"gpuEnabled")).isSelected(),
                        (WorldStatusIcons.Render)get(view,"renderHealth"))
                        && render.getTooltip().getText().equals(gpuStatus.getText().strip()),
                "The render chip follows the GPU line: "+icons.render()+" · "+gpuStatus.getText());
        var textureStatus=(javafx.scene.control.Label)get(view,"textureStatus");
        require(icons.texture()==WorldStatusIcons.texture(((Node)get(view,"textureActivity")).isVisible(),
                        (WorldStatusIcons.Texture)get(view,"textureHealth"))
                        && ((Button)view.lookup("#world-icon-texture")).getTooltip().getText().equals(textureStatus.getText().strip()),
                "The texture chip follows the texture line: "+icons.texture()+" · "+textureStatus.getText());
        // The chips read typed health, not the text: a stream detail full of "unavailable" and
        // "failed on" words leaves them as they were.
        var renderBefore=icons.render();
        var textureBefore=icons.texture();
        String gpuWas=gpuStatus.getText(), textureWas=textureStatus.getText();
        String partial=WorldDevReportTest.DETAIL+"; water tables unavailable; building objects failed on 2";
        gpuStatus.setText(partial);
        textureStatus.setText(partial);
        require(icons.render()==renderBefore && icons.texture()==textureBefore
                        && icons.render()!=WorldStatusIcons.Render.FAILED && icons.texture()!=WorldStatusIcons.Texture.FAILED
                        && render.getTooltip().getText().equals(partial),
                "Detail text never turns a chip red: "+icons.render()+" / "+icons.texture());
        gpuStatus.setText(gpuWas);
        textureStatus.setText(textureWas);
        // Details open where they live: render and texture on the developer overlay's pages, the
        // mouse on the menu's Controls page.
        var overlay=view.devOverlay();
        render.fire();
        require(overlay.isVisible() && overlay.page()==WorldDevReport.Page.RENDER
                        && view.getScene().getFocusOwner()==viewport,
                "The render chip opens the developer overlay's Render page; focus on the world");
        ((Button)view.lookup("#world-icon-texture")).fire();
        require(overlay.page()==WorldDevReport.Page.STREAM,"The texture chip opens the Stream page");
        overlay.show(null);
        var menu=(WorldGameMenu)get(view,"menu");
        ((Button)view.lookup("#world-icon-mouse")).fire();
        require(menu.current()==WorldGameMenu.Page.CONTROLS,"The mouse icon opens the Controls page");
        menu.close();
        require(!menu.shown() && view.getScene().getFocusOwner()==viewport,"Closed again; focus on the world");
        key(viewport,KeyCode.W,true);
        require(input(view).keysHeld(),"WASD works straight after icon clicks");
        key(viewport,KeyCode.W,false);
    }

    /**
     * The inspect card is the first Escape layer: Escape closes it before the map panel, and never
     * opens the menu over it (the viewport's own Escape branch is gone; the scene filter owns it).
     */
    private static void cardIsTheFirstEscapeLayer(WorldView view, Node viewport) {
        var menu=(WorldGameMenu)get(view,"menu");
        var hud=(WorldHud)get(view,"hud");
        var card=view.inspectCard();
        var camera=(TerrainRenderer.Camera)get(view,"camera");
        card.show(WorldInspection.note("Authored card"),camera.x(),camera.y(),camera.z());
        require(card.isOpen() && !menu.shown(),"A card is open, the menu closed");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!card.isOpen() && !menu.shown(),"Escape closes the card and does not open the menu over it");
        card.show(WorldInspection.note("Authored card"),camera.x(),camera.y(),camera.z());
        invoke(view,"toggleMapPanel");
        require(card.isOpen() && hud.panelShown(),"Card and map panel open");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!card.isOpen() && hud.panelShown(),"The card closes before the map panel");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!hud.panelShown() && !menu.shown(),"Then the map panel");
        // A card the hidden HUD hides stays open: Escape moves on to the next layer (the menu).
        card.show(WorldInspection.note("Authored card"),camera.x(),camera.y(),camera.z());
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(card.isOpen() && !card.isVisible() && hud.hidden(),"H hides the card with the HUD");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(card.isOpen() && menu.shown(),"Escape skips the hidden card and opens the menu");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!menu.shown() && card.isOpen(),"Escape closes the menu; the card is still open");
        key(viewport,KeyCode.H,true);
        key(viewport,KeyCode.H,false);
        require(card.isVisible(),"H shows the card again");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!card.isOpen() && !menu.shown(),"Now it shows, Escape closes it");
        require(!input(view).keysHeld(),"Escape never becomes a held key");
    }

    /**
     * In full screen the stage's own Escape exit is off; Escape closes the map panel, then the menu,
     * and only with nothing open leaves full screen. A held Escape never goes on to open the menu.
     */
    private static void fullscreenEscapeIsTheLastLayer(WorldView view, Node viewport) {
        var full=(WorldFullscreen)get(view,"fullscreen");
        var menu=(WorldGameMenu)get(view,"menu");
        var hud=(WorldHud)get(view,"hud");
        var content=(javafx.scene.layout.BorderPane)get(view,"content");
        full.windowed(true);
        press(viewport,KeyCode.F11,false);
        release(viewport,KeyCode.F11);
        require(full.active() && full.stage().getFullScreenExitKeyCombination()==javafx.scene.input.KeyCombination.NO_MATCH,
                "Full screen, with the platform's Escape exit turned off");
        require(view.keyBindingsOwner()==full.stage(),"In full screen the Key bindings dialog is owned by the full-screen stage");
        invoke(view,"toggleMapPanel");
        require(hud.panelShown(),"Map panel open in full screen");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!hud.panelShown() && full.active(),"Escape closes the map panel first and stays in full screen");
        menu.open();
        // The menu is a viewport overlay, so it is on the full-screen stage with the viewport.
        ((Button)viewport.lookup("#world-menu-controls")).fire();
        require(menu.current()==WorldGameMenu.Page.CONTROLS,"A menu page open in full screen");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!menu.shown() && full.active(),"Then the menu, from a page too, still in full screen");
        for (int i=0;i<5;i++) press(viewport,KeyCode.ESCAPE,false);
        require(!full.active() && content.getCenter()==viewport,"With nothing open, Escape leaves full screen last");
        require(view.keyBindingsOwner()==view.getScene().getWindow(),"In the window the dialog is owned by the app window");
        require(!menu.shown(),"A held Escape does not go on to open the menu");
        release(viewport,KeyCode.ESCAPE);
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(menu.shown(),"In the window, the next Escape opens the menu as before");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!menu.shown(),"And closes it");
    }

    /** HUD step 5: layered Escape, the menu's pages and entries, held keys released, focus back. */
    private static void gameMenu(WorldView view, Node viewport) {
        var menu=(WorldGameMenu)get(view,"menu");
        var hud=(WorldHud)get(view,"hud");
        require(!menu.shown() && menu.getParent()==viewport,"The menu is a viewport overlay, closed at first");
        // Nothing open: Escape opens the menu and releases held keys; nothing flies behind it.
        key(viewport,KeyCode.W,true);
        require(input(view).keysHeld(),"W held");
        press(viewport,KeyCode.ESCAPE,false);
        require(menu.shown() && menu.current()==null && !input(view).keysHeld(),"Escape opens the menu and releases W");
        var dim=view.lookup("#world-menu-dim");
        require(dim.isVisible() && dim.getStyle().contains("0.4"),"The world is dimmed to 60%");
        key(viewport,KeyCode.W,false);
        key(viewport,KeyCode.W,true);
        require(!input(view).keysHeld(),"W does not fly while the menu is open");
        key(viewport,KeyCode.W,false);
        for (int i=0;i<10;i++) press(viewport,KeyCode.ESCAPE,false);
        require(menu.shown(),"A held Escape (key repeat) does not close it again");
        release(viewport,KeyCode.ESCAPE);
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!menu.shown() && view.getScene().getFocusOwner()==viewport,"Escape again closes it; focus on the world");
        // An open map panel closes first.
        invoke(view,"toggleMapPanel");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!hud.panelShown() && !menu.shown(),"Escape closes the map panel before anything else");
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(menu.shown(),"Then it opens the menu");
        // Pages: each entry opens its page under a Back header; Back returns; Escape on a page closes.
        for (var page : WorldGameMenu.Page.values()) {
            ((Button)view.lookup("#world-menu-"+page.id())).fire();
            var box=view.lookup("#world-menu-page-"+page.id());
            require(menu.current()==page && box.isVisible()
                            && view.lookup("#world-menu-back").isVisible() && !view.lookup("#world-menu-main").isVisible(),
                    "The "+page+" page shows");
            ((Button)view.lookup("#world-menu-back")).fire();
            require(menu.shown() && menu.current()==null && view.lookup("#world-menu-main").isVisible(),"Back returns to the menu");
        }
        ((Button)view.lookup("#world-menu-controls")).fire();
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!menu.shown(),"Escape on a page closes the menu");
        // Resume closes; Leave instance only inside an instance; Back to map list is the old button.
        press(viewport,KeyCode.ESCAPE,false);
        release(viewport,KeyCode.ESCAPE);
        require(!view.lookup("#world-menu-leave").isVisible(),"No Leave instance outside an instance");
        ((Button)view.lookup("#world-menu-resume")).fire();
        require(!menu.shown() && view.getScene().getFocusOwner()==viewport,"Resume closes the menu");
        // The pages drive the same settings: the layout preset, and coordinates on or off.
        var controls=input(view).controls();
        require(((javafx.scene.control.RadioButton)view.lookup("#world-layout-qwerty")).isSelected(),"QWERTY preset shown");
        ((javafx.scene.control.RadioButton)view.lookup("#world-layout-azerty")).fire();
        require(input(view).controls().keys().equals(WorldControls.Layout.AZERTY.keys()),"The AZERTY radio applies the preset");
        ((javafx.scene.control.RadioButton)view.lookup("#world-layout-qwerty")).fire();
        require(input(view).controls().keys().equals(WorldControls.Layout.QWERTY.keys()),"And back to QWERTY");
        input(view).controls(controls);
        var show=(javafx.scene.control.CheckBox)view.lookup("#world-show-coordinates");
        var pill=view.lookup("#world-coordinates");
        show.fire();
        require(pill.getOpacity()==0 && pill.isMouseTransparent(),"Show coordinates off hides the pill");
        show.fire();
        require(pill.getOpacity()==1 && !pill.isMouseTransparent(),"On shows it again");
        // Typing in a page (the bookmark search) never flies.
        menu.open(WorldGameMenu.Page.BOOKMARKS);
        var search=((Node)get(view,"travel")).lookup(".text-field");
        key(search,KeyCode.W,true);
        require(!input(view).keysHeld(),"Keys typed on a page do not fly");
        key(search,KeyCode.W,false);
        menu.close();
        key(viewport,KeyCode.W,true);
        require(input(view).keysHeld(),"WASD works after the menu closes");
        key(viewport,KeyCode.W,false);
    }

    private static boolean isWithin(Node node, Node ancestor) {
        for (var at=node; at!=null; at=at.getParent()) if (at==ancestor) return true;
        return false;
    }

    /** A view closed while full screen returns nothing and closes its stage. */
    private static void closeEndsFullscreen() {
        var other=new WorldView(() -> null, () -> 0);
        set(other,"explorer",true);
        var full=(WorldFullscreen)get(other,"fullscreen");
        full.windowed(true);
        invoke(other,"toggleFullscreen");
        require(full.active(),"Throwaway view in full screen");
        var stage=full.stage();
        other.close();
        require(!full.active() && !stage.isShowing(),"close() ends full screen and closes its stage");
    }

    /** Leaving the explorer while full screen ends it first; run last (the view leaves the explorer). */
    private static void showInventoryEndsFullscreen(WorldView view) {
        var full=(WorldFullscreen)get(view,"fullscreen");
        invoke(view,"toggleFullscreen");
        require(full.active(),"Full screen before leaving");
        var stage=full.stage();
        invoke(view,"toggleMapPanel");
        require(((Node)get(view,"viewport")).lookup("#world-map-panel").isVisible(),"Map panel open before leaving");
        invoke(view,"showInventory");
        require(!full.active() && !stage.isShowing(),"Leaving the explorer ends full screen");
        var split=(javafx.scene.control.SplitPane)get(view,"split");
        var content=(javafx.scene.layout.BorderPane)get(view,"content");
        require(split.getItems().size()==2 && split.getItems().getFirst()==get(view,"sidebarPane")
                        && split.getItems().get(1)==content.getParent(),
                "Leaving the explorer, even with the map panel open, puts the map list back in the split");
        require(!((Node)get(view,"viewport")).lookup("#world-map-panel").isVisible() && content.getTop()!=null
                        && isWithin((Node)get(view,"status"),content.getTop()),
                "The heading and status line are back above the tile picker");
    }

    private static boolean viewportActive(WorldView view) {
        try{var m=WorldView.class.getDeclaredMethod("viewportActive");m.setAccessible(true);return (boolean)m.invoke(view);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }

    /** The stream request size: first size at once, a new size after one quiet pulse, reset. */
    private static void streamingSizeIsDebouncedPerPulse(WorldView view) {
        var viewport=(javafx.scene.layout.Region)view.lookup("#world-viewport");
        invoke(view,"resetStreamingSize");
        require((long)get(view,"streamingPolicy")==1 && (int)get(view,"streamingWidth")==0,"Reset starts at policy 1 without a size");
        // The request is the viewport at the window's render scale (#425), so expected sizes follow
        // the display's scale (100%, 125%, 150% ...) rather than layout pixels.
        var first=requested(view,640,400);
        viewport.resize(640,400);
        invoke(view,"streamingSize");
        require((int)get(view,"streamingWidth")==first.width() && (int)get(view,"streamingHeight")==first.height()
                && (long)get(view,"streamingPolicy")==1,"The first size applies at once without a policy bump: "
                +get(view,"streamingWidth")+"x"+get(view,"streamingHeight")+", expected "+first.width()+"x"+first.height());
        for (int step=1; step<=5; step++) {
            viewport.resize(640+step*10,400+step*4);
            invoke(view,"streamingSize");
            require(!pulse(view),"A size still changing never settles");
        }
        require((int)get(view,"streamingWidth")==first.width() && (long)get(view,"streamingPolicy")==1,
                "During the drag the old size and policy are requested");
        require(pulse(view),"After one quiet pulse the new size settles and a camera request is made");
        invoke(view,"streamingSize");
        var settled=requested(view,690,420);
        require((int)get(view,"streamingWidth")==settled.width() && (int)get(view,"streamingHeight")==settled.height()
                && (long)get(view,"streamingPolicy")==2,"The settled size is sent once, with one policy bump");
        require(!pulse(view),"Nothing more owed");
        invoke(view,"resetStreamingSize");
        require((long)get(view,"streamingPolicy")==1 && (int)get(view,"streamingWidth")==0,"A new stream resets");
    }

    /** The frame a viewport of this layout size requests at the view's window render scale. */
    private static com.vibeminer.rendering.world.WorldFrameLimits.Size requested(WorldView view, double width, double height) {
        var window=view.getScene().getWindow();
        var frame=com.vibeminer.rendering.world.WorldFrameLimits.frame(width,height,
                window.getRenderScaleX(),window.getRenderScaleY(),1);
        require(frame.width()>=width && frame.height()>=height,"The test sizes fit under the frame cap: "+frame);
        return frame;
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
        var speed=(javafx.scene.control.ChoiceBox<WorldControls.Speed>)get(view,"flightSpeed");
        require(speed.getValue()==WorldControls.Speed.TURBO,"The stored speed preset shows");
        require(view.lookupAll(".label").stream().anyMatch(n -> n instanceof javafx.scene.control.Label l
                && l.getText().startsWith("Z Q S D move")),"The key help follows the stored bindings");
        invert.fire();
        require(saved.size()==1 && WorldControls.decode(saved.getFirst()).equals(stored.withInvertY(false)),
                "A user change saves the whole value: "+saved);
        // #173: the view distance is remembered with the controls; loading it never saves.
        @SuppressWarnings("unchecked")
        var distance=(javafx.scene.control.ChoiceBox<com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance>)
                view.lookup("#world-view-distance");
        var far=com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.FAR;
        var near=com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance.NEAR;
        require(distance.getValue()==near,"The view distance opens NEAR");
        view.showControls(stored.withInvertY(false).withViewDistance(far));
        require(saved.size()==1 && distance.getValue()==far,"A stored FAR shows without saving: "+saved);
        distance.setValue(near);
        require(saved.size()==2 && WorldControls.decode(saved.getLast()).viewDistance()==near
                        && WorldControls.decode(saved.getLast()).equals(stored.withInvertY(false)),
                "Choosing NEAR saves the whole value: "+saved);
        require(distance.getConverter().toString(far).equals("View distance: Far (5 x 5 tiles)")
                        && distance.getConverter().toString(near).equals("View distance: Near (3 x 3 tiles)"),
                "The choices name their neighbourhoods");
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
        var speedChoice=(javafx.scene.control.ChoiceBox<WorldControls.Speed>)get(view,"flightSpeed");
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

    /**
     * Walking with collision over the unqualified fixture mesh (no collision: the body waits) and
     * over an authored qualified ADT18 surface (it stands and walks), then flying and back.
     */
    private static void walk(WorldView view,AnimationTimer timer,Node viewport) {
        var walk=(javafx.scene.control.CheckBox)get(view,"walkMode");
        var fly=(javafx.scene.control.CheckBox)get(view,"flyMode");
        var status=(javafx.scene.control.Label)view.lookup("#world-motion-status");
        require(!walk.isSelected() && !fly.isSelected(),"The free camera is the default");
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,false);
        require(walk.isSelected(),"F toggles walking once per press, ignoring key repeat");
        // The navigation fixture's single-triangle tiles are not the admitted ADT18 layout.
        var held=new TerrainRenderer.Camera(10000-SIZE/2,20000-SIZE/2,500,0,0);
        set(view,"camera",held);
        input(view).idle();
        key(viewport,KeyCode.W,true);
        timer.handle(3_000_000_000L);
        timer.handle(3_100_000_000L);
        key(viewport,KeyCode.W,false);
        var body=input(view).body().body();
        require(body.x()==held.x() && body.status()==com.vibeminer.rendering.world.WalkController.Status.LOADING,
                "Without collision data the body waits: "+body.x()+" "+body.status());
        require(status.getText().contains("waiting for terrain collision"),"Waiting is reported: "+status.getText());
        var surface=com.vibeminer.rendering.world.TerrainGround.of(List.of(
                com.vibeminer.rendering.world.TerrainGround.Tile.of(new com.vibeminer.rendering.world.TerrainScene.Tile(X,Y,
                        WorldFormats.adt(authoredRoot(10000,20000),() -> false)))));
        set(view,"ground",surface);
        // A jump to just above the surface places the body on it.
        double ground=surface.heightAt(held.x(),held.y());
        set(view,"camera",new TerrainRenderer.Camera(held.x(),held.y(),ground+10,0,0));
        input(view).idle();
        long t=4_000_000_000L;
        timer.handle(t);
        timer.handle(t+=100_000_000L);
        // The capsule rests on the highest ground under its footprint (the fixture has steps).
        double r=body.shape().radius(),highest=-Double.MAX_VALUE;
        for(double dx=-r;dx<=r;dx+=r/8) for(double dy=-r;dy<=r;dy+=r/8)
            if(dx*dx+dy*dy<=r*r) highest=Math.max(highest,surface.heightAt(held.x()+dx,held.y()+dy));
        require(body.grounded() && body.z()>=ground-.01 && body.z()<=highest+.05,
                "The body stands on the surface: "+body.z()+" in "+ground+".."+highest);
        require(status.getText().startsWith("Walking · collides with terrain"),"Walking is reported: "+status.getText());
        double startX=body.x();
        key(viewport,KeyCode.W,true);
        for(int i=0;i<10;i++) timer.handle(t+=100_000_000L);
        key(viewport,KeyCode.W,false);
        require(body.x()>startX+1,"W walks the body forward: "+(body.x()-startX));
        var camera=(TerrainRenderer.Camera)get(view,"camera");
        require(camera.x()<body.x(),"The camera follows behind: "+camera.x()+" vs "+body.x());
        key(viewport,KeyCode.G,true);
        key(viewport,KeyCode.G,false);
        timer.handle(t+=100_000_000L);
        require(fly.isSelected() && !walk.isSelected() && status.getText().startsWith("Flying"),
                "G flies: "+status.getText());
        key(viewport,KeyCode.G,true);
        key(viewport,KeyCode.G,false);
        require(walk.isSelected() && !fly.isSelected(),"G again walks");
        // A mode change relabels the status line at once (no pulse needed).
        require(status.getText().endsWith(" · F: fly"),"Walking names the next mode's key: "+status.getText());
        // F, the mode key, moves on: walk, fly, free camera, walk.
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,false);
        require(fly.isSelected() && !walk.isSelected() && status.getText().startsWith("Flying")
                        && status.getText().endsWith(" · F: free camera"),
                "F after walking flies: "+status.getText());
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,false);
        require(!walk.isSelected() && !fly.isSelected(),"F after flying is the free camera");
        require(status.getText().startsWith("Free camera") && status.getText().endsWith(" · F: walk"),
                "The free camera is reported with the key to walk: "+status.getText());
        key(viewport,KeyCode.F,true);
        key(viewport,KeyCode.F,false);
        require(walk.isSelected() && !fly.isSelected(),"F after the free camera walks again");
        walk.fire();
        require(!walk.isSelected() && !fly.isSelected(),"Walking can be turned off from the menu");
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
    private static Object getStatic(String name) {
        try{return field(name).get(null);}catch(IllegalAccessException error){throw new AssertionError(error);}
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
