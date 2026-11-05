package com.vimi.gui;

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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
