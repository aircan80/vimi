package com.vimi.gui;

import com.vibeminer.rendering.world.TerrainRenderer;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;

/** Actual FX controls plus a held authored CPU renderer; no source files, GPU or game assets. */
public final class WorldCpuRetirementUiTest {
    private WorldCpuRetirementUiTest() {}

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        var entered = new CompletableFuture<Void>();
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var wrongThread = new AtomicBoolean();
        var uiFailure = new CompletableFuture<Void>();
        var owner = new AtomicReference<WorldView>();
        try {
            fx(() -> {
                var view = new WorldView(() -> null, () -> 0, null,
                        (scene, camera, width, height, textures, canceled) -> {
                            calls.incrementAndGet();
                            entered.complete(null);
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Held renderer was never released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                            throw new IllegalStateException("Authored CPU failure");
                        }, ignored -> {});
                owner.set(view);
                var status = (Label) get(view, "status");
                status.textProperty().addListener((observable, previous, next) -> {
                    if (!Platform.isFxApplicationThread()) wrongThread.set(true);
                    if (next.contains("Software renderer failed")) uiFailure.complete(null);
                });
                var activity = (ProgressIndicator) get(view, "firstFrameActivity");
                activity.setVisible(true);
                activity.visibleProperty().addListener((observable, previous, next) -> {
                    if (!Platform.isFxApplicationThread()) wrongThread.set(true);
                });
                queue(view);
                call(view, "kick");
            });
            entered.get(5, TimeUnit.SECONDS);
            var fence = new AtomicReference<CompletableFuture<?>>();
            fx(() -> {
                var view = owner.get();
                fence.set((CompletableFuture<?>) get(view, "cpuRetirement"));
                queue(view);
                call(view, "kick");
                require(!fence.get().isDone(), "Held CPU A still owns its retirement fence");
            });
            release.countDown();
            try {
                fence.get().get(5, TimeUnit.SECONDS);
                throw new AssertionError("CPU failure must fail actual retirement");
            } catch (ExecutionException expected) {
                require(expected.getCause() instanceof java.io.IOException,
                        "Retirement publishes detached bounded failure");
            }
            uiFailure.get(5, TimeUnit.SECONDS);
            fx(() -> {
                var view = owner.get();
                require(!wrongThread.get(), "Failure UI must run only on FX");
                require(calls.get() == 1, "Sticky CPU failure must discard queued B without rendering it");
                require(((AtomicReference<?>) get(view, "latest")).get() == null,
                        "Queued Render ownership must retire before failure fence completes");
                require(get(view, "cpuRetirementFailure") != null, "Failure is sticky");
                queue(view);
                call(view, "kick");
                require(((AtomicReference<?>) get(view, "latest")).get() == null,
                        "Retry cannot admit another Render after unknown CPU failure");
                require(get(view, "cpuRetirement") == fence.get(),
                        "Retry must not replace the terminal ownership fence");
            });
            require(calls.get() == 1, "No successor renderer after failure");
            System.out.println("CPU retirement UI: held A/queued B, exceptional fence, FX-only failure, sticky retry passed");
        } finally {
            release.countDown();
            if (owner.get() != null) fx(() -> owner.get().close());
            Platform.exit();
        }
    }

    private static void queue(WorldView view) {
        try {
            var type = Class.forName("com.vimi.gui.WorldView$Render");
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object render = constructor.newInstance(0L, 0L,
                    new TerrainRenderer.Scene(List.of()), new TerrainRenderer.Camera(0, 0, 0, 0, 0),
                    32, 32, null, 0L);
            @SuppressWarnings("unchecked")
            var latest = (AtomicReference<Object>) get(view, "latest");
            require(latest.getAndSet(render) == null, "Only one queued authored Render");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object get(Object value, String name) {
        try {
            Field field = value.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void call(Object value, String name) {
        try {
            var method = value.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void fx(Runnable action) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); result.complete(null); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        result.get(5, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
