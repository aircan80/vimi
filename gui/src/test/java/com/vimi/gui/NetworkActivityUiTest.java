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
                        long samplesBeforeHeaders = snapshots.get();
                        for (int i = 0; i < 100; i++) {
                            var old = view;
                            old.close();
                            view = new NetworkActivityView(model);
                            header.getChildren().set(1, view);
                            require(!old.animating(), "Retired header animation stops");
                        }
                        require(snapshots.get() == samplesBeforeHeaders,
                                "Header replacements never create or sample another model");
                        require(
                                model.sampling(),
                                "One stage-owned sampler survives all header rebuilds");
                        view.fire();
                        require(
                                view.getAccessibleText().contains("network activity"),
                                "Accessible purpose and phase");
                        saveScreenshot();
                        verifyApplicationHeaders();
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
            fx(() -> {
                // The window manager may immediately restore a newly shown/minimized window.
                // Assert our policy against the actual property while this FX turn owns it,
                // instead of assuming a fixed-duration native minimization under other tests.
                stage.setIconified(true);
                require(stage.isIconified() && !model.sampling() && !view.animating(),
                        "Actual iconified stage stops animation and the polling timeline");
                long iconifiedSamples = snapshots.get();
                attempt.readFinished(1024);
                require(snapshots.get() == iconifiedSamples,
                        "Network updates do not publish/sample while iconified");
                stage.setIconified(false);
            });
            await(() -> !stage.isIconified() && stage.isShowing() && model.sampling()
                            && model.display().snapshot().receivedBytes() == (2 << 20) + 2048,
                    "Uniconify resumes the sampler with accumulated session bytes");
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
                    "NetworkActivityUiTest PASS: waiting/body bytes/rate/total, reduced motion, 100 header rebuilds, actual generic/overview/prepared headers at 1000px, hidden/iconified/resume/close");
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
        var headerStage = new Stage();
        BuildPreparation pipeline = null;
        NetworkActivityView current = null;
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
            var overview = VimiApp.class.getDeclaredMethod("overviewHeader", BuildInfo.class);
            overview.setAccessible(true);
            var prepared = VimiApp.class.getDeclaredMethod("preparedOverviewHeader", BuildPreparation.Job.class);
            prepared.setAccessible(true);
            var source = new BuildInfo.RegionEvidence("us", "b".repeat(32), true, null, null);
            var build = new BuildInfo("wow_classic_era", "Authored edition with a long name",
                    "1.60.1.70755", "US", "a".repeat(32), BuildInfo.BuildSource.CURRENT,
                    false, null, 0, "authored-header", null, null, java.util.List.of(source));
            pipeline = new BuildPreparation((info, evidence, locale) -> {
                throw new IllegalStateException("Authored unavailable preparation; no source IO");
            }, Runnable::run);
            var job = pipeline.start(build, source, "enUS");
            var root = new VBox();
            var scene = new Scene(root, 1000, 110);
            scene.getStylesheets().add(NetworkActivityUiTest.class.getResource("/vimi.css").toExternalForm());
            headerStage.setScene(scene);
            headerStage.show();
            for (int i = 0; i < 3; i++) {
                var bar = (HBox) switch (i) {
                    case 0 -> generic.invoke(app, null, null);
                    case 1 -> overview.invoke(app, mock.listBuilds().join().getFirst());
                    default -> prepared.invoke(app, job);
                };
                var old = current;
                current = (NetworkActivityView) bar.lookup("#network-activity");
                require(current != null, "Actual app header " + i + " retains activity");
                root.getChildren().setAll(bar);
                root.applyCss();
                root.layout();
                var bounds = current.localToScene(current.getBoundsInLocal());
                require(current.isVisible() && current.isManaged() && bounds.getWidth() > 30,
                        "Actual app header " + i + " presents a visible indicator");
                require(bounds.getMaxX() <= scene.getWidth() + .5 && bounds.getMinX() >= 0,
                        "Actual app header " + i + " indicator stays inside the minimum window");
                require(bounds.getMinX() > scene.getWidth() * .5,
                        "Actual app header " + i + " indicator is on the right");
                require(current.isFocusTraversable() && current.getAccessibleText().contains("network activity"),
                        "Actual app header " + i + " indicator is keyboard-accessible");
                if (old != null) require(!old.animating(), "Actual header replacement retires its animation");
                require(model.sampling(), "One sampler survives each actual wrapper replacement");
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        } finally {
            if (current != null) current.close();
            headerStage.close();
            if (pipeline != null) pipeline.closeAsync().join();
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
