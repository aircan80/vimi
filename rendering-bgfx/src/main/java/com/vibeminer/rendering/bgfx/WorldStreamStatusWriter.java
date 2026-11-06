package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.WorldFrameLimits;
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
    private byte[] camera, frame;
    private boolean finishing, quiet;
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
        if (policy <= 0 || !WorldFrameLimits.fits(width, height)) {
            throw new IllegalArgumentException("Invalid camera acknowledgement");
        }
        byte[] bytes = line("CAMERA_APPLIED " + policy + " " + width + " " + height);
        synchronized (lock) {
            if (finishing || quiet) {
                return false;
            }
            camera = bytes;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Latest-value wake-up hint: the frame mapping holds a newly published sequence. Replaces any
     * unwritten older hint; never reserves reliable credit. The mapping stays the only authority.
     */
    boolean framePublished(long sequence) {
        if (sequence < 1 || sequence > WorldFrameFileV2.MAX_SEQUENCE) {
            throw new IllegalArgumentException("Invalid frame publication hint");
        }
        byte[] bytes = line("FRAME " + sequence);
        synchronized (lock) {
            if (finishing || quiet) {
                return false;
            }
            frame = bytes;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Drops pending latest-value statuses and refuses new ones, so none can follow a terminal
     * reliable status enqueued afterwards.
     */
    void quiet() {
        synchronized (lock) {
            quiet = true;
            camera = null;
            frame = null;
        }
    }

    /** Nonblocking, idempotent. Does not close a stream whose monitor may be held by a write. */
    CompletableFuture<Void> finish() {
        synchronized (lock) {
            finishing = true;
            camera = null;
            frame = null;
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
                    while (reliable.isEmpty() && camera == null && frame == null && !finishing) {
                        lock.wait();
                    }
                    if (!reliable.isEmpty()) {
                        bytes = reliable.removeFirst();
                        counted = true;
                    } else if (finishing) {
                        break;
                    } else if (camera != null) {
                        bytes = camera;
                        camera = null;
                        counted = false;
                    } else {
                        bytes = frame;
                        frame = null;
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
                frame = null;
            }
            drained.completeExceptionally(error);
        }
    }
}
