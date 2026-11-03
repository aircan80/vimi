package com.vimi.gui;

import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Manual logical FX scheduler and authored process/frame sources; no graphics process or toolkit.
 */
public final class TerrainGpuPreviewTest {
    private static final TerrainCamera CAMERA = new TerrainCamera(0, 0, 0, 0, 0);

    private TerrainGpuPreviewTest() {}

    public static void main(String[] args) throws Exception {
        acceptedUploadAndBarriers();
        retiredScenesHideAndOwners();
        failureSuppressionAndRetry();
        System.out.println(
                "Terrain GPU controller: accepted-upload readiness, dimension/generation barriers,"
                    + " continuous lag, coalescing, owner isolation and explicit fallback passed");
    }

    private static TerrainScene scene(int tile) {
        return new TerrainScene(
                List.of(
                        TerrainScene.Tile.copyOf(
                                tile,
                                21,
                                new float[] {10, -2, -1, 10, 2, -1, 10, 0, 2},
                                new int[] {0, 1, 2},
                                1)));
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Authored controller condition did not complete");
            }
            Thread.sleep(2);
        }
    }

    private static final class Source implements GpuPreviewService.Frames {
        final AtomicReference<GpuPreviewService.Frame> latest = new AtomicReference<>();
        final AtomicLong consumed = new AtomicLong(),
                rounds = new AtomicLong(),
                consumedRound = new AtomicLong();
        volatile boolean closed;

        public GpuPreviewService.Frame readLatest(long after) {
            long round = rounds.incrementAndGet();
            var frame = latest.getAndSet(null);
            if (frame != null) {
                consumedRound.set(round);
                consumed.set(frame.sequence());
            }
            return frame;
        }

        void offer(long sequence, long revision, int width, int height) throws Exception {
            int[] pixels = new int[width * height];
            java.util.Arrays.fill(pixels, 0xff123400 | (int) sequence & 255);
            latest.set(
                    new GpuPreviewService.Frame(
                            sequence,
                            revision,
                            width,
                            height,
                            IntBuffer.wrap(pixels).asReadOnlyBuffer()));
            await(() -> consumed.get() >= sequence);
            long consumedAt = consumedRound.get();
            await(() -> rounds.get() > consumedAt);
        }

        public void close() {
            closed = true;
            latest.set(null);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("vimi-terrain-controller-");
        final ConcurrentLinkedQueue<Runnable> fx = new ConcurrentLinkedQueue<>();
        final List<GpuPreviewServiceTest.FakeProcess> children = new CopyOnWriteArrayList<>();
        final List<Source> sources = new CopyOnWriteArrayList<>();
        final List<TerrainGpuPreview.Status> statuses = new ArrayList<>();
        final List<GpuPreviewService.Frame> attempts = new ArrayList<>(),
                accepted = new ArrayList<>();
        final AtomicBoolean upload = new AtomicBoolean(), throwUpload = new AtomicBoolean();
        final AtomicInteger fallbacks = new AtomicInteger();
        final GpuPreviewService service;
        final TerrainGpuPreview controller;

        Fixture() throws Exception {
            service =
                    new GpuPreviewService(
                            root,
                            (path, mesh, appearance, parts) ->
                                    Files.writeString(path, "authored model"),
                            (path, mapping) -> {
                                for (var old : children) {
                                    require(
                                            !old.isAlive(),
                                            "Shared service never overlaps children");
                                }
                                var child = new GpuPreviewServiceTest.FakeProcess(true);
                                children.add(child);
                                return child;
                            },
                            path -> {
                                var source = new Source();
                                sources.add(source);
                                return source;
                            },
                            Duration.ofSeconds(10),
                            Duration.ofMillis(20));
            controller =
                    new TerrainGpuPreview(
                            service,
                            fx::add,
                            statuses::add,
                            frame -> {
                                attempts.add(frame);
                                if (throwUpload.get()) {
                                    throw new IllegalStateException("Authored upload failure");
                                }
                                if (!upload.get()) {
                                    return false;
                                }
                                accepted.add(frame);
                                return true;
                            },
                            fallbacks::incrementAndGet);
        }

        GpuPreviewServiceTest.FakeProcess child(int index) throws Exception {
            await(() -> children.size() > index);
            var child = children.get(index);
            require(child.started.await(5, TimeUnit.SECONDS), "Child status reader started");
            return child;
        }

        void ready(int index) throws Exception {
            child(index).send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
            awaitFx(
                    () ->
                            !statuses.isEmpty()
                                    && statuses.getLast()
                                            .message()
                                            .contains("Waiting for the first"));
        }

        void drain() {
            Runnable task;
            int count = 0;
            while ((task = fx.poll()) != null) {
                if (++count > 10000) {
                    throw new AssertionError("Unbounded FX callbacks");
                }
                task.run();
            }
        }

        void awaitFx(BooleanSupplier condition) throws Exception {
            await(
                    () -> {
                        drain();
                        return condition.getAsBoolean();
                    });
        }

        Source source(int index) throws Exception {
            await(() -> sources.size() > index);
            return sources.get(index);
        }

        @Override
        public void close() throws java.io.IOException {
            controller.close();
            try {
                service.closeAsync().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(failure);
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException failure) {
                throw new java.io.IOException(failure);
            }
            drain();
            require(children.stream().noneMatch(Process::isAlive), "All child processes drained");
            require(
                    sources.stream().allMatch(source -> source.closed),
                    "All frame mappings closed");
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void acceptedUploadAndBarriers() throws Exception {
        try (var fixture = new Fixture()) {
            var scene = scene(17);
            var controller = fixture.controller;
            require(
                    controller.request(scene, CAMERA, 40, 30),
                    "Initial request claims pending GPU");
            fixture.ready(0);
            require(
                    !controller.ready()
                            && fixture.statuses.stream().noneMatch(TerrainGpuPreview.Status::ready),
                    "READY process status cannot claim an accepted FX upload");
            fixture.source(0).offer(1, 1, 40, 30);
            fixture.awaitFx(() -> fixture.attempts.size() == 1);
            require(
                    !controller.ready() && fixture.statuses.getLast().busy(),
                    "Rejected upload remains visibly pending");
            fixture.upload.set(true);
            try {
                controller.request(scene, CAMERA, 801, 30);
                throw new AssertionError("Invalid dimensions accepted");
            } catch (IllegalArgumentException expected) {
                /* no generation/revision mutation */
            }
            controller.request(scene, CAMERA.move(1, 0, 0), 40, 30);
            fixture.source(0).offer(2, 2, 40, 30);
            fixture.awaitFx(controller::ready);
            require(
                    fixture.accepted.getLast().controlRevision() == 2,
                    "Validation failure cannot consume control revision");
            controller.request(scene, CAMERA.move(2, 0, 0), 40, 30);
            controller.request(scene, CAMERA.move(3, 0, 0), 40, 30);
            controller.request(scene, CAMERA.move(4, 0, 0), 40, 30);
            fixture.source(0).offer(3, 3, 40, 30);
            fixture.awaitFx(() -> fixture.accepted.getLast().sequence() == 3);
            require(
                    controller.ready(),
                    "Monotonic lagging camera frame is accepted during continuous movement");
            controller.request(scene, CAMERA, 80, 40);
            require(
                    !controller.ready() && fixture.statuses.getLast().busy(),
                    "Resize establishes a presentation barrier");
            int attempts = fixture.attempts.size();
            fixture.source(0).offer(4, 5, 40, 30);
            fixture.drain();
            require(fixture.attempts.size() == attempts, "Old dimensions never reach upload sink");
            fixture.source(0).offer(5, 5, 80, 40);
            fixture.drain();
            require(
                    fixture.attempts.size() == attempts,
                    "Matching dimensions cannot bypass old revision barrier");
            fixture.source(0).offer(6, 6, 80, 40);
            fixture.awaitFx(controller::ready);
            fixture.drain();
            int accepted = fixture.accepted.size();
            fixture.source(0).offer(7, 6, 80, 40);
            fixture.source(0).offer(8, 6, 80, 40);
            require(
                    fixture.fx.size() == 1,
                    "Only one FX frame delivery waits while scheduler is paused");
            fixture.drain();
            require(
                    fixture.accepted.size() == accepted + 1
                            && fixture.accepted.getLast().sequence() == 8,
                    "Only newest waiting frame uploads");
        }
    }

    private static void retiredScenesHideAndOwners() throws Exception {
        try (var fixture = new Fixture()) {
            var first = scene(17);
            var second = scene(18);
            var controller = fixture.controller;
            fixture.upload.set(true);
            controller.request(first, CAMERA, 40, 30);
            fixture.ready(0);
            fixture.source(0).offer(1, 1, 40, 30);
            controller.request(second, CAMERA, 40, 30);
            fixture.child(1);
            fixture.drain();
            require(fixture.attempts.isEmpty(), "Queued retired-scene pixels never reach sink");
            fixture.ready(1);
            fixture.source(1).offer(1, 2, 40, 30);
            fixture.awaitFx(controller::ready);
            controller.hide();
            require(!controller.ready(), "Hidden view relinquishes readiness");
            fixture.drain();
            int accepted = fixture.accepted.size();
            controller.request(second, CAMERA, 40, 30);
            fixture.ready(2);
            fixture.source(2).offer(1, 2, 40, 30);
            fixture.drain();
            require(
                    fixture.accepted.size() == accepted,
                    "Resume drops pre-resume control revisions");
            fixture.source(2).offer(2, 3, 40, 30);
            fixture.awaitFx(controller::ready);
            Object modelOwner = new Object();
            var mesh =
                    new PreviewMesh(
                            new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                            new int[] {0, 1, 2},
                            List.of(new PreviewMesh.Part(1, 0, 3)));
            fixture.service.open(modelOwner, mesh, null, Set.of(), ignored -> {});
            fixture.service.update(
                    modelOwner, new GpuPreviewService.ViewRequest(1, 40, 30, 0, 0, 1, 0));
            var model = fixture.child(3);
            controller.hide();
            controller.close();
            fixture.drain();
            require(
                    model.isAlive(),
                    "Retired terrain controller cannot close a model owner's successor");
            require(
                    fixture.fallbacks.get() == 0,
                    "Intentional scene retirement/hide is not a GPU failure");
        }
    }

    private static void failureSuppressionAndRetry() throws Exception {
        try (var fixture = new Fixture()) {
            var first = scene(17);
            var second = scene(18);
            var controller = fixture.controller;
            fixture.upload.set(true);
            controller.request(first, CAMERA, 40, 30);
            fixture.ready(0);
            fixture.child(0).send("VIMI_GPU ERROR AUTHORED_FAILURE\n");
            fixture.awaitFx(() -> fixture.fallbacks.get() == 1);
            require(
                    !controller.ready() && !fixture.statuses.getLast().busy(),
                    "Failure clears pending state and requests software");
            require(
                    !controller.request(first, CAMERA, 40, 30),
                    "Failed scene does not automatically respawn");
            controller.hide();
            require(
                    !controller.request(first, CAMERA, 40, 30),
                    "Hide/resume cannot erase failed-scene suppression");
            require(fixture.children.size() == 1, "No retry loop during software fallback");
            controller.retry();
            require(
                    controller.request(first, CAMERA, 40, 30),
                    "Explicit retry restores GPU admission");
            fixture.ready(1);
            fixture.throwUpload.set(true);
            fixture.source(1).offer(1, 2, 40, 30);
            fixture.awaitFx(() -> fixture.fallbacks.get() == 2);
            require(
                    !controller.request(first, CAMERA, 40, 30),
                    "FX upload failure also suppresses the scene");
            fixture.throwUpload.set(false);
            require(
                    controller.request(second, CAMERA, 40, 30),
                    "A different immutable scene is an explicit new attempt");
            fixture.ready(2);
            fixture.child(2).destroy();
            fixture.awaitFx(() -> fixture.fallbacks.get() == 3);
            require(
                    !controller.request(second, CAMERA, 40, 30),
                    "Unexpected clean child exit falls back instead of owner ping-pong");
        }
    }
}
