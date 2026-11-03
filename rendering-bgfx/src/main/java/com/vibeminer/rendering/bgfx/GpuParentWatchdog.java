package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Parent/control loss is observed even while native initialization or presentation is blocked. */
final class GpuParentWatchdog implements AutoCloseable {
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final CompletableFuture<Void> deadlineDrained = new CompletableFuture<>();
    private final long graceMillis;
    private final Runnable forcedExit;
    private final InputStream control;

    GpuParentWatchdog(
            CompletableFuture<?> parentExit,
            InputStream control,
            Duration grace,
            Runnable forcedExit) {
        this.control = java.util.Objects.requireNonNull(control);
        graceMillis = grace.toMillis();
        if (graceMillis < 1) {
            throw new IllegalArgumentException("Positive GPU shutdown grace required");
        }
        this.forcedExit = java.util.Objects.requireNonNull(forcedExit);
        parentExit.whenComplete((ignored, failure) -> requestStop());
        Thread.ofVirtual().name("gpu-parent-control").start(() -> readControl(control));
    }

    boolean stopping() {
        return stopping.get();
    }

    private void requestStop() {
        if (finished.get() || !stopping.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual()
                .name("gpu-child-exit-deadline")
                .start(
                        () -> {
                            try {
                                Thread.sleep(graceMillis);
                                if (!finished.get()) {
                                    forcedExit.run();
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                deadlineDrained.complete(null);
                            }
                        });
    }

    private void readControl(InputStream input) {
        try {
            int length = 0;
            while (true) {
                int value = input.read();
                if (value == -1 || value == '\n' || ++length > 64) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Any lost or invalid private control channel terminates this renderer.
        } finally {
            requestStop();
        }
    }

    CompletableFuture<Void> deadlineDrained() {
        return deadlineDrained.copy();
    }

    @Override
    public void close() {
        finished.set(true);
        try {
            control.close();
        } catch (IOException ignored) {
            /* Child is already terminating. */
        }
        if (!stopping.get()) {
            deadlineDrained.complete(null);
        }
    }
}
