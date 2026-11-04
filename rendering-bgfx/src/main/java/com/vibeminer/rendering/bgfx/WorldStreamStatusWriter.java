package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Bounded pipe ownership. The native owner never writes, flushes or closes this stream. */
final class WorldStreamStatusWriter {
    private final Object lock = new Object();
    private final OutputStream output;
    private final WorldStatusDeadline deadline;
    private final String prefix;
    private final ArrayDeque<byte[]> reliable = new ArrayDeque<>(16);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private byte[] camera;
    private boolean finishing;
    private volatile Throwable failure;

    WorldStreamStatusWriter(
            WorldStreamState.Nonce nonce, OutputStream output, WorldStatusDeadline deadline) {
        this.output = Objects.requireNonNull(output);
        this.deadline = Objects.requireNonNull(deadline);
        Objects.requireNonNull(nonce);
        prefix =
                "WS2 "
                        + HexFormat.of().toHexDigits(nonce.high())
                        + HexFormat.of().toHexDigits(nonce.low())
                        + " ";
        Thread.ofVirtual().name("world-v2-status-writer").start(this::writeLoop);
    }

    /** Includes the in-flight status in its capacity; false requires session termination. */
    boolean reliable(String body) {
        byte[] bytes = line(body);
        synchronized (lock) {
            if (finishing || !deadline.enqueue()) {
                return false;
            }
            reliable.addLast(bytes);
            lock.notifyAll();
            return true;
        }
    }

    /** Camera replacement never reserves reliable credit or refreshes reliable write progress. */
    boolean cameraApplied(long policy, int width, int height) {
        if (policy <= 0 || width < 1 || width > 800 || height < 1 || height > 500) {
            throw new IllegalArgumentException("Invalid camera acknowledgement");
        }
        byte[] bytes = line("CAMERA_APPLIED " + policy + " " + width + " " + height);
        synchronized (lock) {
            if (finishing) {
                return false;
            }
            camera = bytes;
            lock.notifyAll();
            return true;
        }
    }

    /** Nonblocking, idempotent. Does not close a stream whose monitor may be held by a write. */
    CompletableFuture<Void> finish() {
        synchronized (lock) {
            finishing = true;
            camera = null;
            lock.notifyAll();
        }
        return drained.copy();
    }

    boolean drained() {
        return drained.isDone();
    }

    Throwable failure() {
        return failure;
    }

    private byte[] line(String body) {
        Objects.requireNonNull(body);
        if (body.isEmpty() || body.length() > 256) {
            throw new IllegalArgumentException("Invalid status body");
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("Status must be one ASCII line");
            }
        }
        byte[] bytes = (prefix + body + "\n").getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 256) {
            throw new IllegalArgumentException("Status line exceeds protocol limit");
        }
        return bytes;
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] bytes;
                boolean counted;
                synchronized (lock) {
                    while (reliable.isEmpty() && camera == null && !finishing) {
                        lock.wait();
                    }
                    if (!reliable.isEmpty()) {
                        bytes = reliable.removeFirst();
                        counted = true;
                    } else if (finishing) {
                        break;
                    } else {
                        bytes = camera;
                        camera = null;
                        counted = false;
                    }
                }
                output.write(bytes);
                output.flush();
                if (counted) {
                    deadline.written();
                }
            }
            drained.complete(null);
        } catch (IOException | InterruptedException | RuntimeException error) {
            failure = error;
            synchronized (lock) {
                finishing = true;
                reliable.clear();
                camera = null;
            }
            drained.completeExceptionally(error);
        }
    }
}
