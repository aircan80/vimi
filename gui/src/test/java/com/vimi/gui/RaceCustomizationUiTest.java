package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceAppearanceTypes;
import com.vibeminer.concepts.races.RaceCustomizationTypes;
import com.vibeminer.concepts.races.RaceMediaTypes;
import com.vibeminer.concepts.races.RaceTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Authored textures and actual JavaFX rendering; no game assets or native GPU requirement. */
public final class RaceCustomizationUiTest {
    private RaceCustomizationUiTest() {}
    private static final RaceMediaTypes.Variant MODEL = new RaceMediaTypes.Variant(5,0,1,1,1);
    private static final PreviewMesh MESH = new PreviewMesh(
            new float[]{0,0,1,-1,-1,-1,1,-1,-1,0,1,-1},
            new int[]{0,1,2,0,2,3,0,3,1,1,3,2},List.of(new PreviewMesh.Part(0,0,12)));
    private static RaceAppearanceTypes.Result result(int color) {
        return new RaceAppearanceTypes.Result(RaceTypes.State.PARTIAL,
                new MeshAppearance(MESH,new float[]{.5f,0,0,1,1,1,.5f,.5f},
                        List.of(new MeshAppearance.Surface(0,0,false,false,false)),
                        List.of(new MeshAppearance.Texture(1,1,new int[]{color}))),
                Set.of(0),List.of(),List.of("Authored customization fixture"),false,null);
    }
    private static final class Source implements BuildRaces {
        final AtomicInteger defaults = new AtomicInteger(), overrides = new AtomicInteger(), closes = new AtomicInteger();
        volatile CompletableFuture<RaceAppearanceTypes.Result> next = new CompletableFuture<>();
        volatile CountDownLatch cancelEntered, cancelRelease;
        public CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token) { throw new UnsupportedOperationException(); }
        public CompletableFuture<RaceTypes.Overview> overview(long race, Cancellation token) { throw new UnsupportedOperationException(); }
        public CompletableFuture<RaceMediaTypes.Variants> modelVariants(long race,Cancellation token) {
            offFx(); return CompletableFuture.completedFuture(new RaceMediaTypes.Variants(RaceTypes.State.READY,List.of(MODEL),List.of(),null));
        }
        public CompletableFuture<RaceMediaTypes.Preview> preview(long race,long playerClass,long model,Cancellation token) {
            offFx(); return CompletableFuture.completedFuture(new RaceMediaTypes.Preview(RaceTypes.State.PARTIAL,MODEL,MESH,Set.of(0),List.of(),false,null));
        }
        public CompletableFuture<RaceCustomizationTypes.Catalog> customizations(long race,long playerClass,long model,Cancellation token) {
            offFx(); return CompletableFuture.completedFuture(new RaceCustomizationTypes.Catalog(RaceTypes.State.PARTIAL,race,playerClass,model,
                    List.of(new RaceCustomizationTypes.Option(30,"Skin Color",List.of(new RaceCustomizationTypes.Choice(524,"Choice #524"),new RaceCustomizationTypes.Choice(525,"Choice #525")),524)),List.of(),null));
        }
        public CompletableFuture<RaceAppearanceTypes.Result> appearance(long race,long playerClass,long model,Cancellation token) {
            offFx(); defaults.incrementAndGet(); return CompletableFuture.completedFuture(result(0xffb06b42));
        }
        public CompletableFuture<RaceAppearanceTypes.Result> appearance(RaceCustomizationTypes.Request request,Cancellation token) {
            offFx(); require(request.raceId()==3 && request.classId()==1 && request.modelId()==5
                    && request.picks().equals(List.of(new RaceCustomizationTypes.Pick(30,525))),"Exact choice/context request");
            var entered=cancelEntered; var release=cancelRelease;
            if(entered!=null) token.onCancel(() -> {
                offFx(); entered.countDown(); try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("Cancellation release"); }
                catch(InterruptedException failed){Thread.currentThread().interrupt();throw new AssertionError(failed);}
            });
            overrides.incrementAndGet(); return next;
        }
        public CompletableFuture<Void> closeAsync() { closes.incrementAndGet(); return CompletableFuture.completedFuture(null); }
    }
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {});
        Platform.setImplicitExit(false);
        var source = new Source();
        var revision = new java.util.concurrent.atomic.AtomicLong(1);
        var panel = fx(() -> {
            var value=new RaceModelPanel(source,()->source,revision::get,1,3,List.of(new RaceTypes.PlayerClass(1,"Warrior")));
            ((javafx.scene.control.CheckBox)value.lookup("#model-gpu-preview")).setSelected(false);
            return value;
        });
        var stage = fx(() -> { var value=new Stage();var scene=new Scene(panel,850,850);
            scene.getStylesheets().add(RaceCustomizationUiTest.class.getResource("/vimi.css").toExternalForm());
            value.setScene(scene);value.show();return value; });
        try {
            await(() -> source.defaults.get()==1 && picker(panel)!=null && !activity(panel));
            long beforeRotation=fx(() -> pixels(panel));
            fx(() -> { panel.lookup("#model-preview-viewport").fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.RIGHT,
                    false,false,false,false)); return null; });
            await(() -> pixels(panel)!=beforeRotation);
            long original=fx(() -> pixels(panel));
            fx(() -> { picker(panel).getSelectionModel().select(1); return null; });
            await(() -> source.overrides.get()==1);
            require(fx(() -> activity(panel)),"Activity includes pending texture composition");
            require(fx(() -> pixels(panel))==original,"Previous rendered pixels remain during texture preparation");
            source.next.complete(result(0xffefc18b));
            await(() -> !activity(panel) && pixels(panel)!=original);
            var screenshot=fx(() -> {
                var image=stage.getScene().snapshot(null);
                int width=(int)image.getWidth(),height=(int)image.getHeight();
                int[] argb=new int[width*height];
                image.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),argb,0,width);
                var output=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                output.setRGB(0,0,width,height,argb,0,width);
                return output;
            });
            var directory=java.nio.file.Path.of(System.getProperty("vimi.uiReviewDirectory","build/ui-review"));
            java.nio.file.Files.createDirectories(directory);
            javax.imageio.ImageIO.write(screenshot,"png",directory.resolve("race-customization.png").toFile());
            fx(() -> { ((Button)panel.lookup("#race-appearance-reset")).fire();return null; });
            await(() -> source.defaults.get()==2 && !activity(panel) && pixels(panel)==original);
            source.next=new CompletableFuture<>();
            source.cancelEntered=new CountDownLatch(1);source.cancelRelease=new CountDownLatch(1);
            fx(() -> { picker(panel).getSelectionModel().select(1);return null; });
            await(() -> source.overrides.get()==2);
            var stale=source.next;
            fx(() -> { ((Button)panel.lookup("#race-appearance-reset")).fire();return null; });
            require(source.cancelEntered.await(5,TimeUnit.SECONDS),"Actual cancellation callback entered");
            require(fx(() -> activity(panel)),"Held cancellation does not block FX or end activity");
            require(source.defaults.get()==2,"No replacement before original work/cancel retirement");
            stale.complete(result(0xff0044ff));
            require(fx(() -> pixels(panel))==original,"Retired blue result cannot replace accepted pixels while cancellation is held");
            source.cancelRelease.countDown();
            await(() -> source.defaults.get()==3 && !activity(panel) && pixels(panel)==original);
            source.next=new CompletableFuture<>();source.cancelEntered=null;
            fx(() -> { picker(panel).getSelectionModel().select(1);return null; });
            await(() -> source.overrides.get()==3);
            revision.incrementAndGet();source.next.complete(result(0xff00ff00));
            await(() -> !activity(panel) && ((javafx.scene.control.Label)panel.lookup("#race-model-state")).getText().contains("Build changed"));
            require(fx(() -> picker(panel).isDisabled() && pixels(panel)==0),"Workspace-stale choice cannot publish pixels and controls retire");
            require(source.closes.get()==0,"Panel never closes borrowed race source");
            System.out.println("RACE_CUSTOMIZATION_UI_PASS actualChoicePixels reset staleSelection heldCancellationFxResponsive borrowedSourceOpen");
        } finally {
            if(source.cancelRelease!=null)source.cancelRelease.countDown();
            source.next.complete(result(0xff0044ff));
            fx(() -> { panel.close();stage.close();return null; });
            Platform.exit();
        }
    }
    @SuppressWarnings("unchecked")
    private static ComboBox<RaceCustomizationTypes.Choice> picker(RaceModelPanel panel) {
        return (ComboBox<RaceCustomizationTypes.Choice>)panel.lookup("#race-appearance-option-30");
    }
    private static boolean activity(RaceModelPanel panel) { return ((ProgressIndicator)panel.lookup("#race-model-activity")).isVisible(); }
    private static long pixels(RaceModelPanel panel) {
        var image=((ImageView)panel.lookup("#model-preview-image")).getImage();
        if(image==null)return 0;
        long hash=1;var reader=image.getPixelReader();
        for(int y=0;y<(int)image.getHeight();y+=3)for(int x=0;x<(int)image.getWidth();x+=3)hash=31*hash+reader.getArgb(x,y);
        return hash;
    }
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline){if(fx(condition::getAsBoolean))return;Thread.sleep(10);}
        throw new AssertionError("Timed out waiting for authored UI state");
    }
    private static <T>T fx(Callable<T> action)throws Exception {
        var future=new CompletableFuture<T>();Platform.runLater(() -> {try{future.complete(action.call());}catch(Throwable failure){future.completeExceptionally(failure);}});return future.get(5,TimeUnit.SECONDS);
    }
    private static void offFx(){require(!Platform.isFxApplicationThread(),"Provider and cancellation run off FX");}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
