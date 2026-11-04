package com.vimi.gui;

import com.vibeminer.rendering.bgfx.GpuFrameFile;
import com.vibeminer.rendering.bgfx.GpuSceneFile;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Opt-in authored diagnostic: production v1 child/service/controller, no user data or optimization.
 */
public final class WorldInlineCadenceProof {
    private static final int WIDTH = 800, HEIGHT = 500, MAX_SAMPLES = 4096;
    private static final long SAMPLE_NANOS = 8_000_000_000L;
    private static final long[] sentAt = new long[MAX_SAMPLES];
    private static final List<Accepted> accepted = new ArrayList<>(); // FX-owned
    private static final AtomicLong requested = new AtomicLong(), reads = new AtomicLong();
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private static final AtomicReference<Monitor> monitor = new AtomicReference<>();
    private static final CopyOnWriteArrayList<Process> children = new CopyOnWriteArrayList<>();
    private static final CompletableFuture<Void> ready = new CompletableFuture<>();
    private static final CompletableFuture<Void> finished = new CompletableFuture<>();
    private static TerrainGpuPreview controller;
    private static Stage stage;
    private static AnimationTimer timer;
    private static GpuPreviewService service;
    private static long start,
            end,
            startSequence,
            endSequence,
            startReads,
            endReads,
            startRequested;

    private record Accepted(
            long at, long revision, long newest, long age, long copy, long upload) {}

    private WorldInlineCadenceProof() {}

    public static void main(String[] args) throws Exception {
        Path output =
                Path.of(args.length == 0 ? "build/world-inline-cadence" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        Path temporary = Files.createTempDirectory("vimi-authored-cadence-");
        // Construct bounded authored geometry before FX starts:9 full tiles /589824 triangles.
        TerrainScene scene = scene();
        service =
                new GpuPreviewService(
                        temporary,
                        GpuSceneFile::write,
                        (geometry, frames) -> {
                            if (children.stream().anyMatch(Process::isAlive))
                                throw new AssertionError("Native overlap");
                            var command = new ArrayList<String>();
                            command.add(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString());
                            command.addAll(
                                    List.of(
                                            "--enable-native-access=ALL-UNNAMED",
                                            "-cp",
                                            System.getProperty("java.class.path"),
                                            "com.vibeminer.rendering.bgfx.GpuViewerMain",
                                            "--terrain-scene",
                                            geometry.toAbsolutePath().toString(),
                                            "--frames",
                                            frames.toAbsolutePath().toString(),
                                            "--parent-pid",
                                            Long.toString(ProcessHandle.current().pid()),
                                            "--backend",
                                            "vulkan"));
                            var process = new ProcessBuilder(command).start();
                            children.add(process);
                            return process;
                        },
                        path -> {
                            var mapping = GpuFrameFile.create(path, WIDTH, HEIGHT);
                            monitor.set(new Monitor(path));
                            return new GpuPreviewService.Frames() {
                                public GpuPreviewService.Frame readLatest(long after)
                                        throws java.io.IOException {
                                    var frame = mapping.readLatest(after);
                                    if (frame == null) return null;
                                    reads.incrementAndGet();
                                    return new GpuPreviewService.Frame(
                                            frame.sequence(),
                                            frame.controlRevision(),
                                            frame.width(),
                                            frame.height(),
                                            frame.pixels());
                                }

                                public void close() throws java.io.IOException {
                                    mapping.close();
                                }
                            };
                        },
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(3));
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    var image = new WritableImage(WIDTH, HEIGHT);
                    var imageView = new ImageView(image);
                    stage = new Stage();
                    stage.setTitle("Authored World warm-camera cadence diagnostic");
                    stage.setScene(
                            new Scene(new javafx.scene.layout.StackPane(imageView), WIDTH, HEIGHT));
                    stage.show();
                    controller =
                            new TerrainGpuPreview(
                                    service,
                                    Platform::runLater,
                                    status -> {
                                        if (status.ready()) ready.complete(null);
                                    },
                                    frame -> {
                                        long before = System.nanoTime();
                                        image.getPixelWriter()
                                                .setPixels(
                                                        0,
                                                        0,
                                                        WIDTH,
                                                        HEIGHT,
                                                        PixelFormat.getIntArgbPreInstance(),
                                                        frame.pixels(),
                                                        WIDTH);
                                        long now = System.nanoTime(),
                                                revision = frame.controlRevision();
                                        if (start != 0 && end == 0 && now >= start) {
                                            if (accepted.size() == MAX_SAMPLES)
                                                throw new AssertionError("Sample cap");
                                            accepted.add(
                                                    new Accepted(
                                                            now,
                                                            revision,
                                                            requested.get(),
                                                            now - sentAt[(int) revision],
                                                            frame.bridgeNanos(),
                                                            now - before));
                                        }
                                        return true;
                                    },
                                    () -> {
                                        failure.compareAndSet(
                                                null,
                                                new AssertionError("Unexpected software fallback"));
                                        ready.completeExceptionally(failure.get());
                                        finished.completeExceptionally(failure.get());
                                    });
                    send(scene, 0);
                });
        try {
            ready.get(25, TimeUnit.SECONDS);
            fx(
                    () -> {
                        long warmStart = System.nanoTime();
                        timer =
                                new AnimationTimer() {
                                    public void handle(long now) {
                                        if (start == 0 && now - warmStart >= 2_000_000_000L) {
                                            start = now;
                                            startSequence = monitor.get().latest.get();
                                            startReads = reads.get();
                                            startRequested = requested.get();
                                        }
                                        if (start != 0 && now - start >= SAMPLE_NANOS) {
                                            stop();
                                            end = System.nanoTime();
                                            endSequence = monitor.get().latest.get();
                                            endReads = reads.get();
                                            finished.complete(null);
                                            return;
                                        }
                                        send(scene, (now - warmStart) / 1e9);
                                    }
                                };
                        timer.start();
                        return null;
                    });
            finished.get(15, TimeUnit.SECONDS);
            if (failure.get() != null) throw new AssertionError(failure.get());
            require(
                    children.size() == 1 && children.getFirst().isAlive(),
                    "Same live child throughout sample");
            String report = fx(() -> report(scene));
            Files.writeString(output.resolve("cadence.txt"), report);
            StringBuilder csv =
                    new StringBuilder(
                            "time_ms,revision,latest_requested,revision_lag,request_age_ms,bridge_copy_ms,fx_upload_ms\n");
            fx(
                    () -> {
                        for (var sample : accepted)
                            csv.append(
                                    String.format(
                                            Locale.ROOT,
                                            "%.3f,%d,%d,%d,%.3f,%.3f,%.3f%n",
                                            (sample.at - start) / 1e6,
                                            sample.revision,
                                            sample.newest,
                                            sample.newest - sample.revision,
                                            sample.age / 1e6,
                                            sample.copy / 1e6,
                                            sample.upload / 1e6));
                        return null;
                    });
            Files.writeString(output.resolve("accepted.csv"), csv);
            System.out.print(report);
        } finally {
            try {
                fx(
                        () -> {
                            if (timer != null) timer.stop();
                            if (controller != null) controller.close();
                            if (stage != null) stage.close();
                            return null;
                        });
            } finally {
                try {
                    if (monitor.get() != null) monitor.get().close();
                } finally {
                    try {
                        service.closeAsync().get(10, TimeUnit.SECONDS);
                    } finally {
                        Platform.exit();
                    }
                }
            }
        }
        require(children.stream().noneMatch(Process::isAlive), "All owned children terminated");
        Files.deleteIfExists(temporary);
    }

    private static void send(TerrainScene scene, double t) {
        long revision = requested.incrementAndGet();
        if (revision >= MAX_SAMPLES) throw new AssertionError("Request cap");
        sentAt[(int) revision] = System.nanoTime();
        require(
                controller.request(
                        scene,
                        new TerrainCamera(
                                -300 + 20 * Math.sin(t * .7),
                                -266 + 20 * Math.cos(t * .6),
                                140,
                                .1 * Math.sin(t * .9),
                                -.45 + .04 * Math.cos(t * .8)),
                        WIDTH,
                        HEIGHT),
                "Production controller request accepted");
    }

    private static String report(TerrainScene scene) {
        require(accepted.size() > 1, "Accepted real FX frames");
        var gaps = new ArrayList<Double>();
        var ages = new ArrayList<Double>();
        var lags = new ArrayList<Double>();
        var copies = new ArrayList<Double>();
        var uploads = new ArrayList<Double>();
        for (int i = 0; i < accepted.size(); i++) {
            var s = accepted.get(i);
            ages.add(s.age / 1e6);
            lags.add((double) (s.newest - s.revision));
            copies.add(s.copy / 1e6);
            uploads.add(s.upload / 1e6);
            if (i > 0) gaps.add((s.at - accepted.get(i - 1).at) / 1e6);
        }
        return "WORLD_V1_WARM_CAMERA_CADENCE authoredHeightOnly Vulkan"
                   + " productionChildServiceController JavaFxPrismSoftware\n"
                   + "sceneTiles="
                + scene.tiles().size()
                + " triangles="
                + scene.tiles().stream().mapToInt(TerrainScene.Tile::triangleCount).sum()
                + " output=800x500 continuousWarmupMs=2000 durationMs="
                + (end - start) / 1e6
                + " pid="
                + children.getFirst().pid()
                + " childCount="
                + children.size()
                + "\n"
                + "cameraRequests="
                + (requested.get() - startRequested)
                + " nativeMappedSequenceAdvance="
                + (endSequence - startSequence)
                + " serviceReads="
                + (endReads - startReads)
                + " fxAccepted="
                + accepted.size()
                + "\n"
                + stats("acceptedGapMs", gaps)
                + stats("requestAgeAtFxAcceptanceMs", ages)
                + stats("revisionLag", lags)
                + stats("bridgeReadCopyMs", copies)
                + stats("fxSetPixelsMs", uploads)
                + "Limits: nativeMappedSequenceAdvance counts published mmap frames, not draw"
                + " submissions; no GPU timestamps.\n"
                + "This controlled local height-shaded test excludes WorldView/source loading,"
                + " textures, streaming/replacement, network, input dispatch and other"
                + " hardware/backends.\n"
                + "Raw stamp observer polls 3x8 bytes every2ms; statistics/profiling add overhead."
                + " No universal FPS or latency guarantee.\n";
    }

    private static String stats(String name, List<Double> values) {
        values.sort(Double::compare);
        return String.format(
                Locale.ROOT,
                "%s p50=%.3f p95=%.3f max=%.3f%n",
                name,
                values.get((values.size() - 1) / 2),
                values.get((int) Math.floor((values.size() - 1) * .95)),
                values.getLast());
    }

    private static TerrainScene scene() {
        var tiles = new ArrayList<TerrainScene.Tile>();
        double tileSize = 1600d / 3;
        for (int tx = 31; tx <= 33; tx++)
            for (int ty = 31; ty <= 33; ty++) {
                float[] vertices = new float[37120 * 3];
                int[] indices = new int[196608];
                int vi = 0, ii = 0;
                for (int chunk = 0; chunk < 256; chunk++) {
                    int base = chunk * 145;
                    double ox = (32 - tx) * tileSize - (chunk / 16) * tileSize / 16;
                    double oy = (32 - ty) * tileSize - (chunk % 16) * tileSize / 16;
                    for (int r = 0; r < 17; r++)
                        for (int c = 0; c < (r % 2 == 0 ? 9 : 8); c++) {
                            float x = (float) (ox - r * tileSize / 256),
                                    y =
                                            (float)
                                                    (oy
                                                            - c * tileSize / 128
                                                            - (r % 2) * tileSize / 256);
                            vertices[vi++] = x;
                            vertices[vi++] = y;
                            vertices[vi++] = (float) (20 * Math.sin(x / 90) * Math.cos(y / 110));
                        }
                    for (int r = 0; r < 8; r++)
                        for (int c = 0; c < 8; c++) {
                            int a = base + r * 17 + c, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                            for (int index : new int[] {a, m, b, b, m, e, e, m, d, d, m, a})
                                indices[ii++] = index;
                        }
                }
                tiles.add(TerrainScene.Tile.copyOf(tx, ty, vertices, indices, 256));
            }
        return new TerrainScene(tiles);
    }

    /** Statistics-only scalar reads of the existing private v1 mapping; no extra image copy. */
    private static final class Monitor implements AutoCloseable {
        final FileChannel channel;
        final AtomicLong latest = new AtomicLong();
        final Thread thread;
        volatile boolean closed;

        Monitor(Path path) throws java.io.IOException {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            thread =
                    Thread.ofVirtual()
                            .name("authored-native-frame-counter")
                            .start(
                                    () -> {
                                        var bytes =
                                                ByteBuffer.allocate(8)
                                                        .order(ByteOrder.LITTLE_ENDIAN);
                                        try {
                                            while (!closed) {
                                                for (int slot = 0; slot < 3; slot++) {
                                                    bytes.clear();
                                                    int count =
                                                            channel.read(
                                                                    bytes,
                                                                    64L
                                                                            + slot
                                                                                    * (64L
                                                                                            + WIDTH
                                                                                                    * HEIGHT
                                                                                                    * 4));
                                                    if (count == 8) {
                                                        long stamp = bytes.flip().getLong();
                                                        if (stamp > 0 && (stamp & 1) == 0)
                                                            latest.accumulateAndGet(
                                                                    stamp >>> 1, Math::max);
                                                    }
                                                }
                                                Thread.sleep(2);
                                            }
                                        } catch (java.io.IOException | InterruptedException error) {
                                            if (!closed) failure.compareAndSet(null, error);
                                        }
                                    });
        }

        public void close() throws java.io.IOException {
            closed = true;
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(error);
            } finally {
                channel.close();
            }
            require(!thread.isAlive(), "Observer terminated");
        }
    }

    private static <T> T fx(Supplier<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        result.complete(work.get());
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
