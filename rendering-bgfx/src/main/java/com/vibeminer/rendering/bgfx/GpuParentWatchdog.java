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
    private final java.util.function.Consumer<String> commands;

    GpuParentWatchdog(
            CompletableFuture<?> parentExit,
            InputStream control,
            Duration grace,
            Runnable forcedExit) {
        this(
                parentExit,
                control,
                grace,
                forcedExit,
                ignored -> {
                    throw new IllegalArgumentException("Unknown GPU control command");
                });
    }

    GpuParentWatchdog(
            CompletableFuture<?> parentExit,
            InputStream control,
            Duration grace,
            Runnable forcedExit,
            java.util.function.Consumer<String> commands) {
        this.commands = java.util.Objects.requireNonNull(commands);
        java.util.Objects.requireNonNull(control);
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
            var line = new StringBuilder();
            int value;
            while (!finished.get() && (value = input.read()) != -1) {
                if (finished.get()) {
                    break;
                }
                if (value == '\n') {
                    String command = line.toString().strip();
                    if (command.equals("CLOSE")) {
                        break;
                    }
                    commands.accept(command);
                    line.setLength(0);
                } else if (value != '\r') {
                    if (value > 127 || line.length() >= 256) {
                        throw new IOException("Invalid GPU control line");
                    }
                    line.append((char) value);
                }
            }
        } catch (IOException | RuntimeException ignored) {
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
        // The control reader is virtual/daemon. Do not close System.in here: a blocked
        // buffered read may hold its close lock and delay process failure delivery.
        if (!stopping.get()) {
            deadlineDrained.complete(null);
        }
    }
}
