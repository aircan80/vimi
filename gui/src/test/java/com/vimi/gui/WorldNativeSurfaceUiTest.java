package com.vimi.gui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * #381: the World view's native-presentation surface on a real JavaFX window with a recording
 * user32 stand-in (no helper, no GPU): the viewport moves onto an owned transparent stage over the
 * placeholder, the child is placed in physical pixels, hidden with the view, and detach returns
 * everything. Requires a display.
 */
public final class WorldNativeSurfaceUiTest {
    private WorldNativeSurfaceUiTest() {}

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(() -> {
            try {
                run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        try {
            done.get(20, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
        System.out.println("World native surface: overlay reparent, physical placement, hide with the view"
                + " and detach passed");
    }

    private static void run() {
        var image = new ImageView();
        var viewport = new StackPane(image);
        var content = new BorderPane(viewport);
        var stage = new Stage();
        stage.setScene(new Scene(content, 900, 600));
        stage.show();
        var windows = new WorldNativePresenterTest.Fake();
        windows.child = 7;
        var surface = new WorldNativeSurface(viewport, image, content::setCenter,
                new WorldNativePresenter(windows));
        require(!surface.attached(), "Starts detached");
        content.setCenter(surface.placeholder());
        surface.attach(stage);
        content.layout();
        require(surface.attached() && viewport.getScene() == surface.overlay().getScene(),
                "The viewport moves onto the overlay stage");
        require(!image.isVisible(), "The readback image is hidden while the child shows frames");
        require(viewport.getStyle().equals(WorldNativeSurface.HIT_TEST_BACKGROUND),
                "The viewport still takes every mouse event");
        require(surface.pulse(true), "The child is placed");
        var scene = surface.placeholder().localToScene(surface.placeholder().getLayoutBounds());
        double scale = stage.getOutputScaleX();
        String expected = "place 7 " + (int) Math.floor(scene.getMinX() * scale) + ","
                + (int) Math.floor(scene.getMinY() * stage.getOutputScaleY());
        require(windows.calls.stream().anyMatch(c -> c.startsWith(expected)),
                "Placed in physical pixels at the placeholder: " + windows.calls + " expected " + expected);
        var screen = surface.placeholder().localToScreen(surface.placeholder().getLayoutBounds());
        require(surface.overlay().isShowing() && surface.overlay().getX() == screen.getMinX()
                && surface.overlay().getWidth() == screen.getWidth(), "The overlay covers the placeholder");
        windows.calls.clear();
        require(!surface.pulse(false), "Hidden view");
        require(windows.calls.contains("hide 7") && !surface.overlay().isShowing(),
                "The child and the overlay hide with the view: " + windows.calls);
        surface.detach();
        require(!surface.attached() && content.getCenter() == viewport && image.isVisible()
                && viewport.getScene() == stage.getScene(), "Detach returns the viewport and the image");
        surface.detach();
        stage.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
