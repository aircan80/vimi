package com.vimi.gui;

import com.vibeminer.concepts.world.AuthoredGlobalSource;
import com.vibeminer.rendering.bgfx.WorldGlobalStatus;
import com.vibeminer.rendering.bgfx.WorldGlobalTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic GLOBAL request lifecycle on the shared GPU owner; no process or user data. */
public final class GpuPreviewGlobalTest {
    private GpuPreviewGlobalTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vimi-gpu-global-test-");
        try {
            unavailablePreparationFailsWithBoundedMessageAndFreesOwner(root);
            cancellationDuringPreparationNeverLaunches(root);
            replacementWaitsForHeldGlobalPreparation(root);
            messagesNeverExposeRawCauses();
            onlyPureCancellationChainsEndAStoppedPreparationClosed(root);
            capabilityUnavailableChildFailsWithItsMessageAndKeepsTheOwner(root);
            readyMessagesSayWhatWasLeftOut();
            aNetworkReadFailsWithTheRetryMessage(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println(
                "GPU GLOBAL lifecycle: unavailable source, preparation cancellation, same-owner"
                        + " replacement and bounded messages passed");
    }

    private static GpuPreviewService service(Path root, AtomicInteger launches) {
        var service =
                new GpuPreviewService(
                        root,
                        (scene, mesh, appearance, parts) -> {
                            throw new AssertionError("No model scene expected");
                        },
                        scene -> {
                            throw new AssertionError("No model child expected");
                        },
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1));
        service.globalStarter(
                argv -> {
                    launches.incrementAndGet();
                    throw new java.io.IOException("No GLOBAL child in this test");
                });
        return service;
    }

    private static void unavailablePreparationFailsWithBoundedMessageAndFreesOwner(Path root)
            throws Exception {
        var launches = new AtomicInteger();
        var service = service(root, launches);
        var states = new ConcurrentLinkedQueue<GpuPreviewService.Status>();
        var owner = new Object();
        var done =
                service.openGlobal(
                        owner,
                        (parent, cancel) -> {
                            throw new GpuPreviewService.GlobalUnavailable(
                                    "Interior rendering needs local client files");
                        },
                        opened -> {
                            throw new AssertionError("No session may open");
                        },
                        states::add);
        try {
            done.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Unavailable preparation must fail");
        } catch (java.util.concurrent.ExecutionException expected) {
            // Failure keeps its bounded user message.
        }
        require(
                states.stream()
                        .anyMatch(
                                s ->
                                        s.state() == GpuPreviewService.State.FAILED
                                                && s.message()
                                                        .equals(
                                                                "Interior rendering needs local"
                                                                        + " client files")),
                "explicit unavailable message");
        require(launches.get() == 0, "no child launched");
        // The owner remains usable for a successor of the same kind.
        var second =
                service.openGlobal(
                        owner,
                        (parent, cancel) -> {
                            throw new GpuPreviewService.GlobalUnavailable("second");
                        },
                        opened -> {},
                        s -> {});
        try {
            second.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
            // Second request actually ran.
        }
        service.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private static void cancellationDuringPreparationNeverLaunches(Path root) throws Exception {
        var launches = new AtomicInteger();
        var service = service(root, launches);
        var entered = new CountDownLatch(1);
        var observed = new CompletableFuture<Boolean>();
        var owner = new Object();
        var done =
                service.openGlobal(
                        owner,
                        (parent, cancel) -> {
                            entered.countDown();
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (!cancel.isCanceled() && System.nanoTime() < deadline) {
                                java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
                            }
                            observed.complete(cancel.isCanceled());
                            cancel.check();
                            throw new AssertionError("cancellation not observed");
                        },
                        opened -> {
                            throw new AssertionError("No session may open");
                        },
                        s -> {});
        require(entered.await(5, TimeUnit.SECONDS), "preparation entered");
        service.closeOwner(owner).get(10, TimeUnit.SECONDS);
        require(observed.get(5, TimeUnit.SECONDS), "actual Source cancellation token canceled");
        done.handle((ignored, error) -> null).get(5, TimeUnit.SECONDS);
        require(launches.get() == 0, "canceled preparation never launches");
        service.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private static void onlyPureCancellationChainsEndAStoppedPreparationClosed(Path root)
            throws Exception {
        var join = new java.util.concurrent.CancellationException("join");
        join.initCause(new java.util.concurrent.CancellationException());
        require(
                stoppedPreparationEnds(root, new java.util.concurrent.CompletionException(join))
                        == GpuPreviewService.State.CLOSED,
                "a wrapped join cancellation chain is a clean stop");
        require(
                stoppedPreparationEnds(root, new java.util.concurrent.CancellationException())
                        == GpuPreviewService.State.CLOSED,
                "a lone cancellation is a clean stop");
        var withCause = new java.util.concurrent.CancellationException("stopped");
        withCause.initCause(new IllegalStateException("cleanup failed"));
        require(
                stoppedPreparationEnds(root, withCause) == GpuPreviewService.State.FAILED,
                "a cancellation carrying a real failure stays a failure");
        var withSuppressed = new java.util.concurrent.CancellationException();
        withSuppressed.addSuppressed(new IllegalStateException("cleanup failed"));
        require(
                stoppedPreparationEnds(root, withSuppressed) == GpuPreviewService.State.FAILED,
                "a cancellation with a suppressed failure stays a failure");
        var subclassWrapper =
                new java.util.concurrent.CompletionException(
                        new java.util.concurrent.CancellationException()) {};
        require(
                stoppedPreparationEnds(root, subclassWrapper) == GpuPreviewService.State.FAILED,
                "a wrapper subclass is not a standard cancellation wrapper");
    }

    /** Stops a GLOBAL preparation, which then throws {@code thrown}; returns the final state. */
    private static GpuPreviewService.State stoppedPreparationEnds(Path root, RuntimeException thrown)
            throws Exception {
        var launches = new AtomicInteger();
        var service = service(root, launches);
        var entered = new CountDownLatch(1);
        var states = new ConcurrentLinkedQueue<GpuPreviewService.Status>();
        var owner = new Object();
        var done =
                service.openGlobal(
                        owner,
                        (parent, cancel) -> {
                            entered.countDown();
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (!cancel.isCanceled() && System.nanoTime() < deadline) {
                                java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
                            }
                            throw thrown;
                        },
                        opened -> {
                            throw new AssertionError("No session may open");
                        },
                        states::add);
        require(entered.await(5, TimeUnit.SECONDS), "preparation entered");
        boolean closeFailed =
                service.closeOwner(owner)
                        .handle((ignored, error) -> error != null)
                        .get(10, TimeUnit.SECONDS);
        boolean failed = done.handle((ignored, error) -> error != null).get(5, TimeUnit.SECONDS);
        service.closeAsync().get(5, TimeUnit.SECONDS);
        require(launches.get() == 0, "stopped preparation never launches");
        GpuPreviewService.State last = null;
        for (var status : states) {
            if (status.state() == GpuPreviewService.State.CLOSED
                    || status.state() == GpuPreviewService.State.FAILED) {
                last = status.state();
            }
        }
        require(
                failed == (last == GpuPreviewService.State.FAILED) && closeFailed == failed,
                "request and owner close outcomes match the final state " + last);
        return last;
    }

    private static void replacementWaitsForHeldGlobalPreparation(Path root) throws Exception {
        var launches = new AtomicInteger();
        var service = service(root, launches);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var order = new ConcurrentLinkedQueue<String>();
        var first =
                service.openGlobal(
                        new Object(),
                        (parent, cancel) -> {
                            entered.countDown();
                            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (release.getCount() > 0 && System.nanoTime() < until) {
                                java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
                            }
                            order.add("first-retired");
                            throw new GpuPreviewService.GlobalUnavailable("first");
                        },
                        opened -> {},
                        s -> {});
        require(entered.await(5, TimeUnit.SECONDS), "first entered");
        var second =
                service.openGlobal(
                        new Object(),
                        (parent, cancel) -> {
                            order.add("second-started");
                            throw new GpuPreviewService.GlobalUnavailable("second");
                        },
                        opened -> {},
                        s -> {});
        Thread.sleep(100);
        require(order.isEmpty(), "successor waits for the held sole owner");
        release.countDown();
        first.handle((ignored, error) -> null).get(5, TimeUnit.SECONDS);
        second.handle((ignored, error) -> null).get(5, TimeUnit.SECONDS);
        require(
                List.copyOf(order).equals(List.of("first-retired", "second-started")),
                "sole owner order " + order);
        service.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private static void messagesNeverExposeRawCauses() {
        require(
                GpuPreviewService.globalMessage(new GpuPreviewService.GlobalUnavailable("x"))
                        .equals("x"),
                "explicit unavailable text");
        String raw =
                GpuPreviewService.globalMessage(
                        new IllegalStateException("/home/user/secret/path provider failure"));
        require(!raw.contains("/home") && raw.contains("IllegalStateException"), raw);
        require(
                GpuPreviewService.globalMessage(
                                new java.util.concurrent.CompletionException(
                                        new java.util.concurrent.CancellationException()))
                        .equals("Interior preparation canceled"),
                "cancellation message");
    }

    /** Scripted child: consumed, GLOBAL_UNAVAILABLE CAPABILITY, closed, clean exit. */
    private static final class UnavailableChild extends Process {
        private final java.io.ByteArrayInputStream stdout;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();

        UnavailableChild(java.util.List<String> argv) {
            var id =
                    new WorldGlobalStatus.Identity(
                            argv.get(argv.indexOf("--global-nonce") + 1),
                            argv.get(argv.indexOf("--global-scope") + 1),
                            argv.get(argv.indexOf("--global-sha256") + 1));
            String lines =
                    new WorldGlobalStatus.InputConsumed(id.nonce(), id.fileSha256()).protocolLine()
                            + "\n"
                            + new WorldGlobalStatus.Unavailable(
                                            id, WorldGlobalStatus.Code.CAPABILITY)
                                    .protocolLine()
                            + "\n"
                            + new WorldGlobalStatus.Closed(id).protocolLine()
                            + "\n";
            stdout =
                    new java.io.ByteArrayInputStream(
                            lines.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            // Exit once the scripted records are out, as a real child would.
            CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)
                    .execute(() -> exit.complete(0));
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return stdout;
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exit.join();
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
            if (!exit.isDone()) {
                throw new IllegalThreadStateException();
            }
            return exit.join();
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public void destroy() {
            exit.complete(1);
        }

        @Override
        public Process destroyForcibly() {
            exit.complete(1);
            return this;
        }
    }

    private static void capabilityUnavailableChildFailsWithItsMessageAndKeepsTheOwner(Path root)
            throws Exception {
        var launches = new AtomicInteger();
        var service = service(root, launches);
        service.globalStarter(
                argv -> {
                    launches.incrementAndGet();
                    return new UnavailableChild(argv);
                });
        var states = new ConcurrentLinkedQueue<GpuPreviewService.Status>();
        var opened = new AtomicInteger();
        try (var fixture = new AuthoredGlobalSource()) {
            var done =
                    service.openGlobal(
                            new Object(),
                            (parent, cancel) ->
                                    WorldGlobalSourceBridge.prepare(
                                            fixture.source(), fixture.scope(), parent, cancel),
                            session -> opened.incrementAndGet(),
                            states::add);
            try {
                done.get(15, TimeUnit.SECONDS);
                throw new AssertionError("An unavailable GPU must not end as a clean stop");
            } catch (java.util.concurrent.ExecutionException expected) {
                require(
                        expected.getCause() instanceof WorldGlobalTransport.Unavailable,
                        "classified cause " + expected.getCause());
            }
        }
        require(launches.get() == 1 && opened.get() == 1, "one child launched and opened");
        var last = states.stream().reduce((a, b) -> b).orElseThrow();
        require(
                last.state() == GpuPreviewService.State.FAILED
                        && last.message().equals(GpuPreviewService.GPU_UNAVAILABLE),
                "final unavailable state " + last);
        try (var left = Files.list(root)) {
            require(
                    left.noneMatch(
                            p ->
                                    p.getFileName().toString().startsWith("global-frames-")
                                            || p.getFileName()
                                                    .toString()
                                                    .startsWith("global-source-")),
                    "request directories drained");
        }
        // Not fail-closed: the same service still runs a successor request.
        var successor =
                service.openGlobal(
                        new Object(),
                        (parent, cancel) -> {
                            throw new GpuPreviewService.GlobalUnavailable("successor ran");
                        },
                        session -> {},
                        s -> {});
        try {
            successor.get(5, TimeUnit.SECONDS);
            throw new AssertionError("successor must fail with its own message");
        } catch (java.util.concurrent.ExecutionException expected) {
            require(expected.getCause().getMessage().equals("successor ran"), "successor ran");
        }
        service.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private static void aNetworkReadFailsWithTheRetryMessage(Path root) throws Exception {
        var launches = new AtomicInteger();
        var service = service(root, launches);
        var states = new ConcurrentLinkedQueue<GpuPreviewService.Status>();
        try (var fixture = new AuthoredGlobalSource()) {
            fixture.failOpen(AuthoredGlobalSource.GROUP,
                    com.vibeminer.files.FileTypes.FileReadState.NEEDS_NETWORK, null);
            var done = service.openGlobal(new Object(),
                    (parent, cancel) -> WorldGlobalSourceBridge.prepare(
                            fixture.source(), fixture.scope(), parent, cancel),
                    session -> {}, states::add);
            try {
                done.get(15, TimeUnit.SECONDS);
                throw new AssertionError("A network read must not open the interior");
            } catch (java.util.concurrent.ExecutionException expected) {
                require(GpuPreviewService.globalMessage(expected.getCause()).equals(
                        "Can't show this interior yet: some of the interior's files need the"
                                + " network (offline or not downloaded yet); try again when online"),
                        GpuPreviewService.globalMessage(expected.getCause()));
            }
        }
        var last = states.stream().reduce((a, b) -> b).orElseThrow();
        require(last.state() == GpuPreviewService.State.FAILED
                && last.message().startsWith("Can't show this interior yet:"), "state " + last);
        require(launches.get() == 0, "no child for a refused source");
        service.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private static void readyMessagesSayWhatWasLeftOut() {
        require(GpuPreviewService.readyMessage(8, java.util.Map.of(), 0).equals("GPU interior"),
                "complete interior");
        var few = new java.util.TreeMap<String, Integer>(java.util.Map.of(
                "unsupported group layout", 3, "the group file is not readable", 1));
        require(GpuPreviewService.readyMessage(44, few, 0).equals(
                "GPU interior · 4 of 48 WMO groups not drawn (unsupported group layout, …)"),
                GpuPreviewService.readyMessage(44, few, 0));
        require(GpuPreviewService.readyMessage(3, java.util.Map.of("no group file is listed", 5), 2)
                .equals("GPU interior · incomplete: only 3 of 8 WMO groups drawn"
                        + " (no group file is listed) · 2 surfaces with an unsupported blend not drawn"),
                "more than half missing warns, blended surfaces counted");
        require(GpuPreviewService.readyMessage(4, java.util.Map.of("x", 4), 1)
                        .equals("GPU interior · 4 of 8 WMO groups not drawn (x)"
                                + " · 1 surface with an unsupported blend not drawn"),
                "exactly half is not yet the strong warning");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
