package com.vimi.gui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * The real wiring of {@link WorldRenderScaleWatch#forNode}: node → scene → window render scale,
 * checked on the FX queue. Needs the JavaFX toolkit (a desktop session); no window is shown.
 */
public final class WorldRenderScaleUiTest {
    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        var calls = new AtomicInteger();
        var node = new Group();
        var first = onFx(() -> {
            var stage = new Stage();
            stage.setScene(new Scene(new Group(node)));
            stage.setRenderScaleX(1);
            stage.setRenderScaleY(1);
            WorldRenderScaleWatch.forNode(node, calls::incrementAndGet);
            return stage;
        });
        settle();
        check(calls.get() == 0, "Installing requests nothing");

        // Monitor move: both axes change in one FX turn; one request after the FX queue runs.
        onFx(() -> {
            first.setRenderScaleX(1.5);
            first.setRenderScaleY(1.5);
            return null;
        });
        settle();
        check(calls.get() == 1, "One request for an X and Y change: " + calls.get());

        // Scene replacement: the node moves into another window's scene at another scale.
        var second = onFx(() -> {
            var stage = new Stage();
            stage.setRenderScaleX(2);
            stage.setRenderScaleY(2);
            var group = new Group();
            stage.setScene(new Scene(group));
            group.getChildren().add(node); // removes it from the first scene
            return stage;
        });
        settle();
        check(calls.get() == 2, "Moving to a window at another scale requests once: " + calls.get());
        onFx(() -> {
            first.setRenderScaleX(3);
            first.setRenderScaleY(3);
            return null;
        });
        settle();
        check(calls.get() == 2, "The old window is no longer watched");
        onFx(() -> {
            second.setRenderScaleY(1.25);
            return null;
        });
        settle();
        check(calls.get() == 3, "The new window is watched");
        Platform.exit();
        System.out.println("WORLD_RENDER_SCALE_UI_PASS");
    }

    private interface FxCall<T> {
        T call() throws Exception;
    }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /** Runs two FX turns, so a check posted by the watch has run. */
    private static void settle() throws Exception {
        onFx(() -> null);
        onFx(() -> null);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private WorldRenderScaleUiTest() {}
}
