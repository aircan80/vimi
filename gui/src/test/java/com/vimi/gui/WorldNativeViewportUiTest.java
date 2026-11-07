package com.vimi.gui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * #381 with #415: the real World view and its native-presentation surface keep one owner for the
 * viewport. No helper, no GPU and no native window handle, so it runs on any desktop. Requires a
 * display.
 */
public final class WorldNativeViewportUiTest {
    private WorldNativeViewportUiTest() {}

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(() -> {
            try {
                worldViewOwnsOneViewport();
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
        System.out.println("World view with native presentation: active while the overlay is hidden"
                + ", instance switch keeps its layout, full screen leaves native first passed");
    }

    /**
     * The World view and native presentation (#381) agree on where the viewport lives (#415):
     * the view counts as shown while the hidden overlay waits for its first pulse, an instance
     * switch keeps its own layout when native presentation ends, and full screen takes the viewport
     * only after native presentation has handed it back.
     */
    private static void worldViewOwnsOneViewport() {
        var view = new WorldView(() -> null, () -> 0);
        var stage = new Stage();
        stage.setScene(new Scene(view, 900, 600));
        stage.show();
        set(view, "explorer", true);
        set(view, "active", true);
        var content = (BorderPane) get(view, "content");
        var viewport = (StackPane) get(view, "viewport");
        var surface = (WorldNativeSurface) get(view, "nativeSurface");
        content.setCenter(viewport);
        view.applyCss();
        view.layout();
        require((boolean) call(view, "viewportActive"), "Shown view before native presentation");
        // As the native stream starts: placeholder in the layout, viewport on the hidden overlay.
        content.setCenter(surface.placeholder());
        surface.attach(stage);
        view.layout();
        require(!surface.overlay().isShowing() && (boolean) call(view, "viewportActive"),
                "The view stays shown while the overlay waits for its first pulse, which shows it");
        // An instance switch owns the layout: ending native presentation must not cover it.
        var instances = get(view, "instances");
        setOn(instances, WorldInstanceSwitch.class, "phase", WorldInstanceSwitch.Phase.ENTERING);
        var switchLayout = new StackPane();
        content.setCenter(switchLayout);
        surface.detach();
        require(!surface.attached() && content.getCenter() == switchLayout,
                "Native presentation ending during an instance switch keeps the switch's layout");
        setOn(instances, WorldInstanceSwitch.class, "phase", WorldInstanceSwitch.Phase.OPEN_WORLD);
        content.setCenter(viewport);
        // Full screen while native presentation holds the viewport: it hands it back first.
        content.setCenter(surface.placeholder());
        surface.attach(stage);
        set(view, "nativeStream", true);
        var full = (WorldFullscreen) get(view, "fullscreen");
        full.windowed(true);
        call(view, "toggleFullscreen");
        require(full.active() && !surface.attached() && !(boolean) get(view, "nativeStream")
                        && viewport.getScene() == full.stage().getScene(),
                "Full screen shows the viewport only after native presentation let it go");
        call(view, "toggleFullscreen");
        require(!full.active() && !surface.attached() && content.getCenter() == viewport
                        && viewport.getScene() == stage.getScene(),
                "Leaving full screen returns the viewport to the layout, once");
        view.close();
        stage.close();
    }

    private static Object get(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void set(Object target, String name, Object value) {
        setOn(target, target.getClass(), name, value);
    }

    private static void setOn(Object target, Class<?> type, String name, Object value) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object call(Object target, String name) {
        try {
            var method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
