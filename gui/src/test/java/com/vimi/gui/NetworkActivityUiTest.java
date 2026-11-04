package com.vimi.gui;

import com.vibeminer.activity.NetworkActivity;
import com.vibeminer.activity.TransferObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Authored telemetry/branding only. No HTTP, source assets, library or user preferences writes. */
public final class NetworkActivityUiTest {
    private static Stage stage;
    private static NetworkActivityModel model;
    private static NetworkActivityView view;
    private static HBox header;
    private static final AtomicLong snapshots = new AtomicLong();
    private static final NetworkActivity meter =
            new NetworkActivity(
                    () -> {
                        snapshots.incrementAndGet();
                        return System.nanoTime();
                    });
    private static final SimpleBooleanProperty reducedMotion = new SimpleBooleanProperty(false);

    private NetworkActivityUiTest() {}

    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    started.complete(null);
                });
        started.get(10, TimeUnit.SECONDS);
        try {
            fx(
                    () -> {
                        stage = new Stage();
                        model = new NetworkActivityModel(meter, stage, reducedMotion);
                        view = new NetworkActivityView(model);
                        header =
                                new HBox(
                                        12,
                                        new Label("VibeMiner · authored activity fixture"),
                                        view);
                        var scene =
                                new Scene(
                                        new VBox(
                                                header,
                                                new Label(
                                                        "Local preparation keeps its own progress indicator.")),
                                        850,
                                        180);
                        scene.getStylesheets()
                                .add(
                                        NetworkActivityUiTest.class
                                                .getResource("/vimi.css")
                                                .toExternalForm());
                        stage.setScene(scene);
                        stage.show();
                    });
            var attempt = meter.begin(TransferObserver.Kind.CDN);
            await(() -> model.display().status().equals("Waiting for network"), "header waiting");
            fx(() -> require(view.animating(), "Active request animates the pickaxe"));
            attempt.headersReceived();
            attempt.readStarted();
            await(() -> model.display().snapshot().waitingBody() == 1, "body waiting");
            attempt.readFinished(2 << 20);
            attempt.readStarted();
            await(() -> model.display().total().equals("2.0 MiB received"), "actual byte total");
            fx(
                    () -> {
                        require(
                                ((Label) view.lookup("#network-activity-amount"))
                                        .getText()
                                        .contains("/s"),
                                "Measured rate shown");
                        reducedMotion.set(true);
                        require(
                                !view.animating() && model.display().snapshot().active() == 1,
                                "Reduced motion keeps static active state");
                        reducedMotion.set(false);
                        for (int i = 0; i < 100; i++) {
                            var old = view;
                            old.close();
                            view = new NetworkActivityView(model);
                            header.getChildren().set(1, view);
                            require(!old.animating(), "Retired header animation stops");
                        }
                        require(
                                model.sampling(),
                                "One stage-owned sampler survives all header rebuilds");
                        view.fire();
                        require(
                                view.getAccessibleText().contains("network activity"),
                                "Accessible purpose and phase");
                        saveScreenshot();
                        stage.hide();
                        require(
                                !view.animating() && !model.sampling(),
                                "Hidden stage stops animation and sampling");
                    });
            long paused = snapshots.get();
            Thread.sleep(600);
            require(snapshots.get() == paused, "No hidden sampling timer remains");
            attempt.readFinished(1024);
            fx(() -> stage.show());
            await(
                    () -> model.display().total().contains("2.0 MiB"),
                    "session total persists across hide");
            attempt.close();
            await(() -> model.display().status().equals("Network idle"), "terminal idle");
            fx(
                    () -> {
                        require(!view.animating(), "Idle branding is static");
                        require(
                                model.display().total().contains("2.0 MiB"),
                                "Idle retains session total");
                        view.close();
                        model.close();
                        require(!model.sampling(), "Shutdown disposes sampler");
                        stage.close();
                    });
            System.out.println(
                    "NetworkActivityUiTest PASS: waiting/body bytes/rate/total, reduced motion, 100 header rebuilds, hidden/resume/close");
        } finally {
            fx(
                    () -> {
                        if (view != null) view.close();
                        if (model != null) model.close();
                        if (stage != null) stage.close();
                    });
            Platform.exit();
        }
    }

    private static void verifyApplicationHeaders() {
        var app = new VimiApp();
        try {
            var service = VimiApp.class.getDeclaredField("service");
            service.setAccessible(true);
            var mock = new MockBuildService();
            service.set(app, mock);
            var sampler = VimiApp.class.getDeclaredField("networkActivityModel");
            sampler.setAccessible(true);
            sampler.set(app, model);
            var generic = VimiApp.class.getDeclaredMethod("header", String.class, javafx.scene.Node.class);
            generic.setAccessible(true);
            var first = (HBox) generic.invoke(app, null, null);
            require(first.lookup("#network-activity") != null, "Generic app header retains activity");
            var overview = VimiApp.class.getDeclaredMethod("overviewHeader", BuildInfo.class);
            overview.setAccessible(true);
            var second = (HBox) overview.invoke(app, mock.listBuilds().join().getFirst());
            require(second.lookup("#network-activity") != null, "Overview wrapper retains activity");
            var scene = new Scene(new VBox(second), 1000, 100);
            scene.getStylesheets().add(NetworkActivityUiTest.class.getResource("/vimi.css").toExternalForm());
            scene.getRoot().applyCss(); scene.getRoot().layout();
            var widget = second.lookup("#network-activity");
            var bounds = widget.localToScene(widget.getBoundsInLocal());
            require(bounds.getMaxX() <= 1000.5 && bounds.getMinX() >= 0,
                    "Top-right activity stays within the 1000px minimum window");
            ((NetworkActivityView) widget).close();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        } finally {
            try {
                var field = VimiApp.class.getDeclaredField("preparations");
                field.setAccessible(true);
                ((MockPreparationService) field.get(app)).close();
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static void saveScreenshot() {
        try {
            var image = stage.getScene().snapshot(null);
            var output =
                    new java.awt.image.BufferedImage(
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++)
                for (int x = 0; x < output.getWidth(); x++)
                    output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            var path = Path.of("build", "network-activity-ui", "authored-mining.png");
            Files.createDirectories(path.getParent());
            javax.imageio.ImageIO.write(output, "png", path.toFile());
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var result = new CompletableFuture<Boolean>();
            Platform.runLater(() -> result.complete(condition.getAsBoolean()));
            if (result.get(2, TimeUnit.SECONDS)) return;
            Thread.sleep(20);
        }
        throw new AssertionError(message);
    }

    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        action.run();
                        done.complete(null);
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
        done.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
