package com.vimi.gui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * #381 spike, GUI side. {@code native}: the viewport region is covered by a helper process's native
 * child window (bgfx D3D11, vsync) with a transparent owned FX stage on top for overlays and input.
 * {@code readback}: the helper renders 800 x 500 offscreen and reads it back, and the GUI uploads a
 * WritableImage, the shape of today's path. Both send a camera revision every pulse; the helper's
 * picture colour follows the revision. A java.awt.Robot thread samples the composed screen at the
 * viewport centre and times camera send to the colour appearing on screen (camera-to-photon as
 * composed by DWM). Windows only; shows windows for a few seconds.
 */
public final class NativePresentProof {
    private NativePresentProof() {}

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            System.out.println("NATIVE_PRESENT_SKIPPED not Windows");
            return;
        }
        String mode = args.length > 0 ? args[0] : "native";
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 6;
        System.setProperty("java.awt.headless", "false");
        var done = new CompletableFuture<String>();
        Platform.startup(() -> {
            try {
                run(mode, seconds, done);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        try {
            System.out.println(done.get(seconds + 40, TimeUnit.SECONDS));
        } finally {
            Platform.exit();
            System.exit(0);
        }
    }

    /** The JavaFX window's native handle through Glass. */
    static long hwnd(Stage stage) throws ReflectiveOperationException {
        var helper = Class.forName("com.sun.javafx.stage.WindowHelper");
        var peer = helper.getMethod("getPeer", javafx.stage.Window.class).invoke(null, stage);
        var platform = peer.getClass().getMethod("getPlatformWindow").invoke(peer);
        return (long) platform.getClass().getMethod("getNativeWindow").invoke(platform);
    }

    static int shade(long revision) {
        return (int) (revision * 7 % 200) + 40;
    }

    private static void run(String mode, long seconds, CompletableFuture<String> done) throws Exception {
        boolean nativeMode = mode.equals("native");
        var viewport = new StackPane();
        viewport.setStyle("-fx-background-color: #202020;");
        viewport.setMinSize(0, 0);
        var frame = new WritableImage(800, 500);
        var view = new ImageView(frame);
        view.fitWidthProperty().bind(viewport.widthProperty());
        view.fitHeightProperty().bind(viewport.heightProperty());
        if (!nativeMode) viewport.getChildren().add(view);
        var side = new VBox(new Label("Panels stay JavaFX"));
        side.setPrefWidth(220);
        var root = new BorderPane(viewport, null, null, new Label("Status row"), side);
        var stage = new Stage();
        stage.setTitle("Present spike · " + mode);
        stage.setScene(new Scene(root, 1100, 700));
        stage.show();
        long parent = hwnd(stage);
        if (parent == 0) throw new IllegalStateException("No native window handle");

        var helper = new ProcessBuilder(new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                NativePresentHelper.class.getName(), nativeMode ? Long.toString(parent) : "readback")))
                .redirectErrorStream(true).start();
        var toHelper = new PrintStream(helper.getOutputStream(), true, StandardCharsets.UTF_8);
        Map<Long, Long> sent = new ConcurrentHashMap<>();
        var presented = new ConcurrentLinkedQueue<Long>();
        // Readback: the first pulse after an upload is the one that draws it (lower bound).
        var shown = new ConcurrentLinkedQueue<Long>();
        var uploaded = new AtomicLong();
        var stats = new CompletableFuture<String>();
        var shotLine = new CompletableFuture<String>();
        var ready = new CompletableFuture<String>();
        var pixels = new int[800 * 500];
        Thread.ofPlatform().daemon().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    long now = System.nanoTime();
                    if (line.startsWith("PRESENTED ")) {
                        var at = sent.get(Long.parseLong(line.substring(10).trim()));
                        if (at != null) presented.add(now - at);
                    } else if (line.startsWith("FRAME ")) {
                        long r = Long.parseLong(line.substring(6).trim());
                        var at = sent.get(r);
                        if (at != null) presented.add(now - at);
                        Platform.runLater(() -> {
                            // The readback path's upload: every pixel through the PixelWriter.
                            int s = shade(r), argb = 0xff000000 | s << 16 | 80 << 8 | (255 - s);
                            java.util.Arrays.fill(pixels, argb);
                            frame.getPixelWriter().setPixels(0, 0, 800, 500, PixelFormat.getIntArgbInstance(), pixels, 0, 800);
                            uploaded.set(r);
                        });
                    } else if (line.startsWith("STATS")) stats.complete(line);
                    else if (line.startsWith("CHILD_SHOWS_FRAMES")) {
                        shotLine.complete(line);
                        System.out.println(line);
                    } else if (line.startsWith("CHILD")) ready.complete(line);
                    else System.out.println("helper: " + line);
                }
            } catch (Exception ignored) {
                // Helper gone.
            }
            ready.complete("NO_CHILD");
            stats.complete("NO_STATS");
        });

        var presenter = new WorldNativePresenter(WorldNativePresenter.user32());
        var overlayLabel = new Label("FX overlay above the native child (minimap, markers, selection)");
        overlayLabel.setId("overlay-label");
        overlayLabel.setStyle("-fx-background-color: rgb(16,24,28); -fx-text-fill: white; -fx-padding: 6;");
        var overlayRoot = new StackPane(overlayLabel);
        StackPane.setAlignment(overlayLabel, Pos.TOP_LEFT);
        overlayRoot.setStyle("-fx-background-color: rgba(0,0,0,0.004);");
        var clicks = new AtomicLong();
        overlayRoot.setOnMousePressed(e -> clicks.incrementAndGet());
        var overlay = new Stage(StageStyle.TRANSPARENT);
        overlay.initOwner(stage);
        var overlayScene = new Scene(overlayRoot);
        overlayScene.setFill(Color.TRANSPARENT);
        overlay.setScene(overlayScene);
        Runnable sync = () -> {
            var screen = viewport.localToScreen(viewport.getBoundsInLocal());
            var scene = viewport.localToScene(viewport.getBoundsInLocal());
            if (screen == null || scene == null || !nativeMode) return;
            // The GUI places the helper's child itself (WorldNativePresenter, java.lang.foreign);
            // the helper only resizes its swap chain.
            var placement = WorldNativePresenter.Placement.of(scene.getMinX(), scene.getMinY(),
                    scene.getWidth(), scene.getHeight(), stage.getOutputScaleX(), stage.getOutputScaleY());
            presenter.parent(parent);
            if (!presenter.show(placement)) System.out.println("presenter: child not found yet");
            toHelper.println("SIZE -1 -1 " + placement.width() + " " + placement.height());
            overlay.setX(screen.getMinX());
            overlay.setY(screen.getMinY());
            overlay.setWidth(screen.getWidth());
            overlay.setHeight(screen.getHeight());
        };

        // Camera-to-photon: sample the composed screen at the viewport centre off the FX thread.
        var photons = new ConcurrentLinkedQueue<Long>();
        var captured = new AtomicBoolean();
        var probing = new AtomicBoolean(true);
        var latest = new AtomicLong();
        var probeAt = new double[2];
        ready.thenAccept(line -> Platform.runLater(() -> {
            System.out.println(line);
            sync.run();
            if (nativeMode) overlay.show();
            for (var p : List.of(stage.xProperty(), stage.yProperty(), viewport.widthProperty(),
                    viewport.heightProperty(), stage.outputScaleXProperty()))
                p.addListener((o, a, b) -> sync.run());
            var centre = viewport.localToScreen(viewport.getWidth() / 2, viewport.getHeight() / 2);
            probeAt[0] = centre.getX();
            probeAt[1] = centre.getY();
            Thread.ofPlatform().daemon().name("photon-probe").start(() -> {
                try {
                    var robot = new java.awt.Robot();
                    long seen = 0;
                    while (probing.get()) {
                        var c = robot.getPixelColor((int) probeAt[0], (int) probeAt[1]);
                        long now = System.nanoTime();
                        if (c.getRGB() != 0xff000000) captured.set(true);
                        if (c.getGreen() < 70 || c.getGreen() > 90) continue;
                        // The newest sent revision with this colour that was not yet seen.
                        for (long r = latest.get(); r > seen && r > latest.get() - 60; r--) {
                            if (shade(r) == c.getRed() || Math.abs(shade(r) - c.getRed()) <= 1) {
                                var at = sent.get(r);
                                if (at != null) photons.add(now - at);
                                seen = r;
                                break;
                            }
                        }
                    }
                } catch (Exception failure) {
                    System.out.println("probe failed: " + failure);
                }
            });
            long start = System.nanoTime();
            new AnimationTimer() {
                long revision, counted;
                boolean shotAsked;

                @Override public void handle(long now) {
                    long up = uploaded.get();
                    if (up > counted) {
                        counted = up;
                        var at = sent.get(up);
                        if (at != null) shown.add(System.nanoTime() - at);
                    }
                    long r = ++revision;
                    sent.put(r, System.nanoTime());
                    latest.set(r);
                    toHelper.println("CAM " + r);
                    if (nativeMode && !shotAsked && System.nanoTime() - start > TimeUnit.SECONDS.toNanos(seconds) - 500_000_000L) {
                        shotAsked = true;
                        toHelper.println("SHOT");
                    }
                    if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(seconds)) {
                        stop();
                        probing.set(false);
                        toHelper.println("QUIT");
                        stats.thenAccept(s -> Platform.runLater(() -> {
                            overlay.close();
                            stage.close();
                            done.complete(String.format(java.util.Locale.ROOT,
                                    "PRESENT_SPIKE mode=%s %s | %s | camera->helper-done %s | camera->drawing-pulse %s | camera->screen %s | pulses %d (%.1f/s)",
                                    mode, s, nativeMode ? shotLine.getNow("CHILD_SHOWS_FRAMES FAIL no swap-chain readback") : "child n/a", summary(presented), nativeMode ? "n/a (helper presents)" : summary(shown),
                                    captured.get() ? summary(photons) : "unavailable (screen capture returns black in this session)",
                                    revision, revision * 1e9 / (System.nanoTime() - start)));
                        }));
                    }
                }
            }.start();
        }));
    }

    static String summary(java.util.Collection<Long> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(null);
        if (sorted.isEmpty()) return "n 0";
        return String.format(java.util.Locale.ROOT, "n %d p50Ms %.2f p95Ms %.2f maxMs %.2f", sorted.size(),
                sorted.get(sorted.size() / 2) / 1e6, sorted.get((int) (sorted.size() * .95)) / 1e6,
                sorted.getLast() / 1e6);
    }
}
