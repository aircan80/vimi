package com.vimi.gui;

import com.vibeminer.rendering.models.PreviewMesh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic process/scene lifecycle proof; never starts a graphics process or uses user data.
 */
public final class GpuPreviewServiceTest {
    private GpuPreviewServiceTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vimi-gpu-service-test-");
        try {
            inlineFramesAndCoalescedControls(root);
            normalExitAndOwnerIsolation(root);
            cancelDuringPreparationDoesNotLaunch(root);
            canceledDeliveryStopsOnlyItsRequest(root);
            closeDuringProcessStartDrainsLateChild(root);
            replacementHasOneChildAndIgnoresOldOwner(root);
            newestPendingRequestReplacesIntermediateScenes(root);
            startupTimeoutStopsUnresponsiveChild(root);
            readyWithoutFramesTimesOut(root);
            forcedStopAndBadProtocol(root);
            sizeBoundAndStartFailureCleanScratch(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        System.out.println(
                "GPU preview lifecycle: bounded scene preparation, isolated owners, replacement,"
                        + " cancellation, process drain and fallback passed");
    }

    private static PreviewMesh mesh() {
        return new PreviewMesh(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(1, 0, 3)));
    }

    private static GpuPreviewService service(
            Path root, GpuPreviewService.SceneWriter writer, GpuPreviewService.Starter starter) {
        return new GpuPreviewService(
                root, writer, starter, Duration.ofSeconds(2), Duration.ofMillis(10));
    }

    private static void write(
            Path file,
            PreviewMesh mesh,
            com.vibeminer.rendering.models.MeshAppearance appearance,
            Set<Integer> parts)
            throws IOException {
        Files.writeString(file, "authored scene", StandardCharsets.UTF_8);
    }

    private static void readyWithoutFramesTimesOut(Path root) throws Exception {
        var child = new FakeProcess(true);
        var service =
                new GpuPreviewService(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> child,
                        Duration.ofMillis(100),
                        Duration.ofMillis(10));
        Object owner = new Object();
        var request = service.open(owner, mesh(), null, Set.of(1), ignored -> {});
        service.update(owner, new GpuPreviewService.ViewRequest(1, 32, 32, 0, 0, 1.15, 2));
        await(child.started);
        child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
        try {
            request.get(2, TimeUnit.SECONDS);
            throw new AssertionError("READY without any mapped frame must fail");
        } catch (java.util.concurrent.ExecutionException expected) {
            require(!child.isAlive(), "Frame startup timeout drains renderer");
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
    }

    private static void inlineFramesAndCoalescedControls(Path root) throws Exception {
        var child = new FakeProcess(true);
        var frames = new ConcurrentLinkedQueue<GpuPreviewService.Frame>();
        var received = new ConcurrentLinkedQueue<Long>();
        var released = new AtomicInteger();
        var fixture =
                new GpuPreviewService(
                        root,
                        GpuPreviewServiceTest::write,
                        (scene, mapping) -> child,
                        path ->
                                new GpuPreviewService.Frames() {
                                    public GpuPreviewService.Frame readLatest(long after) {
                                        return frames.poll();
                                    }

                                    public void close() {
                                        released.incrementAndGet();
                                    }
                                },
                        Duration.ofSeconds(2),
                        Duration.ofMillis(20));
        Object owner = new Object();
        var request =
                fixture.open(
                        owner,
                        mesh(),
                        null,
                        Set.of(1),
                        ignored -> {},
                        frame -> received.add(frame.controlRevision()));
        for (int revision = 1; revision <= 100; revision++)
            fixture.update(
                    owner,
                    new GpuPreviewService.ViewRequest(
                            revision, 640, 480, revision * .01, 0, 1.15, 2));
        await(child.started);
        child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
        frames.add(
                new GpuPreviewService.Frame(
                        1,
                        101,
                        1,
                        1,
                        java.nio.IntBuffer.wrap(new int[] {0xff123456}).asReadOnlyBuffer()));
        frames.add(
                new GpuPreviewService.Frame(
                        2,
                        99,
                        1,
                        1,
                        java.nio.IntBuffer.wrap(new int[] {0xff123456}).asReadOnlyBuffer()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((!received.contains(99L) || !child.controls.toString().contains("VIEW 100 "))
                && System.nanoTime() < deadline) Thread.sleep(10);
        require(
                received.equals(new ConcurrentLinkedQueue<>(List.of(99L)))
                        || (received.size() == 1 && received.contains(99L)),
                "Future revisions rejected while a lagging camera frame can be displayed");
        require(child.controls.toString().contains("VIEW 100 "), "Latest control reaches child");
        fixture.closeOwner(owner).get(2, TimeUnit.SECONDS);
        request.get(2, TimeUnit.SECONDS);
        require(released.get() == 1, "Frame mapping closes once after renderer termination");
        fixture.closeAsync().get(2, TimeUnit.SECONDS);
    }

    private static void normalExitAndOwnerIsolation(Path root) throws Exception {
        var child = new FakeProcess(true);
        var path = new AtomicReference<Path>();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            path.set(scene);
                            return child;
                        });
        var ready = new CountDownLatch(1);
        Object owner = new Object();
        var request =
                service.open(
                        owner,
                        mesh(),
                        null,
                        Set.of(1),
                        status -> {
                            if (status.state() == GpuPreviewService.State.READY) ready.countDown();
                        });
        await(child.started);
        child.send("VIMI_GPU HELLO OpenGL\nVIMI_GPU READY OpenGL\n");
        await(ready);
        service.closeOwner(new Object()).get(2, TimeUnit.SECONDS);
        require(child.isAlive(), "An unrelated owner cannot close a preview");
        require(Files.exists(path.get()), "Scene stays available while child lives");
        service.closeOwner(owner).get(2, TimeUnit.SECONDS);
        request.get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Owner close drains process");
        require(!Files.exists(path.get()), "Scene removed only after exit");
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(
                service.open(owner, mesh(), null, Set.of(), ignored -> {})
                        .isCompletedExceptionally(),
                "Closed application rejects native launches");
    }

    private static void cancelDuringPreparationDoesNotLaunch(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var launches = new AtomicInteger();
        var service =
                service(
                        root,
                        (file, mesh, appearance, parts) -> {
                            entered.countDown();
                            try {
                                await(release);
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                            write(file, mesh, appearance, parts);
                        },
                        scene -> {
                            launches.incrementAndGet();
                            return new FakeProcess(true);
                        });
        Object owner = new Object();
        service.open(owner, mesh(), null, Set.of(), ignored -> {});
        await(entered);
        var closing = service.closeOwner(owner);
        require(!closing.isDone(), "Cancellation waits for owned scene preparation");
        release.countDown();
        closing.get(2, TimeUnit.SECONDS);
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(launches.get() == 0, "Canceled preparation does not start child");
    }

    private static void canceledDeliveryStopsOnlyItsRequest(Path root) throws Exception {
        var child = new FakeProcess(true);
        var service = service(root, GpuPreviewServiceTest::write, scene -> child);
        var request = service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
        await(child.started);
        require(request.cancel(false), "Caller may abandon a native preview request");
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Canceled delivery cannot detach a child from cleanup");
    }

    private static void closeDuringProcessStartDrainsLateChild(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var child = new FakeProcess(true);
        var path = new AtomicReference<Path>();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            path.set(scene);
                            entered.countDown();
                            try {
                                await(release);
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                            return child;
                        });
        service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
        await(entered);
        var closing = service.closeAsync();
        require(!closing.isDone(), "Shutdown owns late process launch");
        require(Files.exists(path.get()), "Scratch retained until late launch is drained");
        release.countDown();
        closing.get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Late-created child is stopped");
        require(!Files.exists(path.get()), "Late process scratch removed after termination");
    }

    private static void replacementHasOneChildAndIgnoresOldOwner(Path root) throws Exception {
        var children = new ConcurrentLinkedQueue<FakeProcess>();
        var first = new FakeProcess(true);
        var second = new FakeProcess(true);
        var launches = new AtomicInteger();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            if (launches.getAndIncrement() == 0) {
                                children.add(first);
                                return first;
                            }
                            require(
                                    !first.isAlive(),
                                    "Replacement starts after previous child exits");
                            children.add(second);
                            return second;
                        });
        Object a = new Object(), b = new Object();
        service.open(a, mesh(), null, Set.of(), ignored -> {});
        await(first.started);
        service.open(b, mesh(), null, Set.of(), ignored -> {});
        await(second.started);
        service.closeOwner(a).get(2, TimeUnit.SECONDS);
        require(second.isAlive(), "Obsolete panel cannot close new owner's window");
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(children.size() == 2 && !second.isAlive(), "Application drains selected preview");
    }

    private static void newestPendingRequestReplacesIntermediateScenes(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new FakeProcess(true);
        var last = new FakeProcess(true);
        var calls = new AtomicInteger();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            if (calls.getAndIncrement() == 0) {
                                entered.countDown();
                                try {
                                    await(release);
                                } catch (Exception error) {
                                    throw new IOException(error);
                                }
                                return first;
                            }
                            return last;
                        });
        service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
        await(entered);
        var superseded = service.open(new Object(), mesh(), null, Set.of(1), ignored -> {});
        service.open(new Object(), mesh(), null, Set.of(1), ignored -> {});
        superseded.get(2, TimeUnit.SECONDS);
        release.countDown();
        await(last.started);
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(
                calls.get() == 2,
                "Intermediate pending scene is coalesced without a process launch");
    }

    private static void startupTimeoutStopsUnresponsiveChild(Path root) throws Exception {
        var child = new FakeProcess(true);
        var service =
                new GpuPreviewService(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> child,
                        Duration.ofMillis(20),
                        Duration.ofMillis(10));
        try {
            service.open(new Object(), mesh(), null, Set.of(), ignored -> {})
                    .get(2, TimeUnit.SECONDS);
            throw new AssertionError("Missing first frame must time out");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Readiness timeout. */
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Readiness timeout cannot leave a child running");
    }

    private static void forcedStopAndBadProtocol(Path root) throws Exception {
        var child = new FakeProcess(false);
        var service = service(root, GpuPreviewServiceTest::write, scene -> child);
        var failed = new CountDownLatch(1);
        var request =
                service.open(
                        new Object(),
                        mesh(),
                        null,
                        Set.of(),
                        status -> {
                            if (status.state() == GpuPreviewService.State.FAILED)
                                failed.countDown();
                        });
        await(child.started);
        child.send("VIMI_GPU HELLO Noop\nVIMI_GPU READY Noop\n");
        await(failed);
        try {
            request.get(3, TimeUnit.SECONDS);
            throw new AssertionError("Noop cannot be presented as GPU success");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Expected safe fallback. */
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(
                child.forced.get() == 1 && !child.isAlive(),
                "Unresponsive child is forcibly drained");
    }

    private static void sizeBoundAndStartFailureCleanScratch(Path root) throws Exception {
        var launches = new AtomicInteger();
        var service =
                service(
                        root,
                        (file, mesh, appearance, parts) -> {
                            try (var channel =
                                    FileChannel.open(
                                            file,
                                            StandardOpenOption.CREATE_NEW,
                                            StandardOpenOption.WRITE)) {
                                channel.position(41L << 20);
                                channel.write(ByteBuffer.wrap(new byte[] {1}));
                            }
                        },
                        scene -> {
                            launches.incrementAndGet();
                            throw new IOException("must not launch");
                        });
        try {
            service.open(new Object(), mesh(), null, Set.of(), ignored -> {})
                    .get(2, TimeUnit.SECONDS);
            throw new AssertionError("Oversize rejected");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Expected input bound. */
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(launches.get() == 0, "Oversized scene never launches native code");
        var broken =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            throw new IOException("fixture failure");
                        });
        try {
            broken.open(new Object(), mesh(), null, Set.of(), ignored -> {})
                    .get(2, TimeUnit.SECONDS);
            throw new AssertionError("Start failure reported");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Expected startup fallback. */
        }
        broken.closeAsync().get(2, TimeUnit.SECONDS);
        try (var files = Files.list(root)) {
            require(files.findAny().isEmpty(), "Every terminated attempt reclaims scratch");
        }
    }

    private static void await(CountDownLatch latch) throws Exception {
        require(latch.await(3, TimeUnit.SECONDS), "Fixture reached expected lifecycle boundary");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static final class FakeProcess extends Process {
        private final PipedInputStream output = new PipedInputStream();
        private final PipedOutputStream events;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final boolean graceful;
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger forced = new AtomicInteger();
        final StringBuffer controls = new StringBuffer();
        private final StringBuilder command = new StringBuilder();

        FakeProcess(boolean graceful) throws IOException {
            this.graceful = graceful;
            events = new PipedOutputStream(output);
        }

        void send(String text) throws IOException {
            events.write(text.getBytes(StandardCharsets.UTF_8));
            events.flush();
        }

        private void finish() {
            exit.complete(0);
            try {
                events.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    synchronized (command) {
                        controls.append((char) value);
                        if (value == '\n') {
                            if (graceful && command.toString().equals("CLOSE")) finish();
                            command.setLength(0);
                        } else command.append((char) value);
                    }
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            started.countDown();
            return output;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public int exitValue() {
            if (!exit.isDone()) throw new IllegalThreadStateException();
            return exit.join();
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public void destroy() {
            if (graceful) finish();
        }

        @Override
        public Process destroyForcibly() {
            forced.incrementAndGet();
            finish();
            return this;
        }
    }
}
