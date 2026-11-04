package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_BLIT;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.lwjgl.system.MemoryUtil;

/**
 * Fixed frame resources; registered shell precedes fallible initialization. No context ownership.
 */
final class GlobalWmoFrameOwner implements AutoCloseable {
    private static final int WIDTH = 800, HEIGHT = 500, BYTES = WIDTH * HEIGHT * 4;
    private static final short INVALID = -1;
    private static final long MAX_SEQUENCE = Long.MAX_VALUE >>> 1;
    private final Thread thread = Thread.currentThread();
    private final GpuFrameFile frames;
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private short color = INVALID, depth = INVALID, target = INVALID;
    private BgfxGlobalWmoRenderer scene;
    private ByteBuffer packed;
    private boolean attempted, initialized, closed, submitted, freed;
    private long issued, published, sequence;
    private int current;

    private static final class Slot {
        short texture = INVALID;
        ByteBuffer pixels;
        GlobalWmoFrameRequest request;
        int ready;
    }

    record Stats(long directBytes, long framePayloadBytes, int pending, long sequence) {}

    private GlobalWmoFrameOwner(GpuFrameFile frames) {
        this.frames = Objects.requireNonNull(frames);
        if (frames.maxWidth() != WIDTH || frames.maxHeight() != HEIGHT) {
            throw new IllegalArgumentException("Global requires exact800x500 mapping");
        }
    }

    /** No direct/native allocation: register this shell before initialize. Mapping is borrowed. */
    static GlobalWmoFrameOwner allocate(GpuFrameFile frames) {
        return new GlobalWmoFrameOwner(frames);
    }

    /** Adopts Decoded on entry. Caller must close/shutdown/afterContextShutdown even on failure. */
    void initialize(GpuGlobalWmoSceneFile.Decoded decoded, BooleanSupplier canceled)
            throws IOException {
        requireThread();
        Objects.requireNonNull(decoded);
        if (attempted || closed) {
            throw new IllegalStateException("Global initialization already attempted/closed");
        }
        attempted = true;
        try {
            Objects.requireNonNull(canceled);
            var caps = bgfx_get_caps();
            if ((caps.supported() & (BGFX_CAPS_TEXTURE_BLIT | BGFX_CAPS_TEXTURE_READ_BACK))
                            != (BGFX_CAPS_TEXTURE_BLIT | BGFX_CAPS_TEXTURE_READ_BACK)
                    || !bgfx_is_texture_valid(
                            0, false, 1, BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_RT)
                    || !bgfx_is_texture_valid(
                            0,
                            false,
                            1,
                            BGFX_TEXTURE_FORMAT_BGRA8,
                            BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK)) {
                throw new IOException("Global frame capabilities unavailable");
            }
            scene = BgfxGlobalWmoRenderer.upload(decoded, canceled);
            color = texture(BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_RT);
            depth = texture(BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT);
            target =
                    valid(bgfx_create_frame_buffer_from_handles(new short[] {color, depth}, false));
            for (var slot : slots) {
                check(canceled);
                slot.texture =
                        texture(
                                BGFX_TEXTURE_FORMAT_BGRA8,
                                BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK);
                slot.pixels = MemoryUtil.memAlloc(BYTES);
            }
            packed = MemoryUtil.memAlloc(BYTES);
            check(canceled);
            initialized = true;
        } catch (IOException | RuntimeException | Error failure) {
            // upload itself also adopts on failure; Decoded.close is idempotent.
            decoded.close();
            throw failure;
        }
    }

    boolean submit(GlobalWmoFrameRequest request) {
        requireOpen();
        Objects.requireNonNull(request);
        if (request.revision() <= issued) {
            throw new IllegalArgumentException("Global camera revision is not increasing");
        }
        if (submitted) {
            return false;
        }
        Slot available = null;
        for (var slot : slots) {
            if (slot.request == null) {
                available = slot;
                break;
            }
        }
        if (available == null) {
            return false;
        }
        bgfx_set_view_frame_buffer(0, target);
        bgfx_set_view_rect(0, 0, 0, request.width(), request.height());
        bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x112233ff, 0f, 0);
        scene.draw(request.camera(), request.width(), request.height());
        // Full physical surface; smaller rectangles still have an800-pixel row stride.
        bgfx_blit(1, available.texture, 0, 0, 0, 0, color, 0, 0, 0, 0, WIDTH, HEIGHT, 1);
        available.request = request;
        available.ready = bgfx_read_texture(available.texture, available.pixels, 0, 0);
        issued = request.revision();
        submitted = true;
        return true;
    }

    boolean advance() throws IOException {
        requireOpen();
        current = bgfx_frame(BGFX_FRAME_NONE);
        submitted = false;
        Slot newest = null;
        for (var slot : slots) {
            if (slot.request != null
                    && ready(current, slot.ready)
                    && slot.request.revision() > published
                    && (newest == null || slot.request.revision() > newest.request.revision())) {
                newest = slot;
            }
        }
        boolean wrote = false;
        if (newest != null) {
            if (sequence == MAX_SEQUENCE) {
                throw new IOException("Global sequence exhausted");
            }
            var request = newest.request;
            pack(
                    newest.pixels,
                    packed,
                    request.width(),
                    request.height(),
                    bgfx_get_caps().originBottomLeft());
            frames.write(++sequence, request.revision(), request.width(), request.height(), packed);
            published = request.revision();
            wrote = true;
        }
        for (var slot : slots) {
            if (slot.request != null && ready(current, slot.ready)) {
                slot.request = null;
            }
        }
        return wrote;
    }

    static boolean ready(int current, int ready) {
        return current - ready >= 0;
    }

    static void pack(ByteBuffer source, ByteBuffer target, int width, int height, boolean flip) {
        if (width < 1
                || width > WIDTH
                || height < 1
                || height > HEIGHT
                || source.capacity() < BYTES
                || target.capacity() < width * height * 4) {
            throw new IllegalArgumentException("Global fixed stride bounds");
        }
        target.clear();
        for (int y = 0; y < height; y++) {
            int row = flip ? HEIGHT - 1 - y : y;
            int start = row * WIDTH * 4;
            target.put(source.duplicate().position(start).limit(start + width * 4));
        }
        target.flip();
    }

    boolean pending() {
        requireThread();
        for (var slot : slots) {
            if (slot.request != null) {
                return true;
            }
        }
        return false;
    }

    Stats stats() {
        requireThread();
        int count = 0;
        long bytes = packed == null ? 0 : BYTES;
        for (var slot : slots) {
            if (slot.request != null) {
                count++;
            }
            if (slot.pixels != null) {
                bytes += BYTES;
            }
        }
        return new Stats(bytes, GlobalWmoNativeScene.FRAME_OWNERS, count, sequence);
    }

    /**
     * Bounded native drain/destroy; direct pointers ALWAYS survive until actual context shutdown.
     */
    @Override
    public void close() {
        requireThread();
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (pending()) {
                current = bgfx_frame(BGFX_FRAME_NONE);
                for (var slot : slots) {
                    if (slot.request != null && ready(current, slot.ready)) {
                        slot.request = null;
                    }
                }
                if (pending() && System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("Global readback drain timed out");
                }
            }
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        var renderer = scene;
        scene = null;
        failure =
                cleanup(
                        failure,
                        () -> {
                            if (renderer != null) {
                                renderer.close();
                            }
                        });
        short framebuffer = target;
        target = INVALID;
        failure =
                cleanup(
                        failure,
                        () -> {
                            if (framebuffer != INVALID) {
                                bgfx_destroy_frame_buffer(framebuffer);
                            }
                        });
        for (var slot : slots) {
            short texture = slot.texture;
            slot.texture = INVALID;
            failure =
                    cleanup(
                            failure,
                            () -> {
                                if (texture != INVALID) {
                                    bgfx_destroy_texture(texture);
                                }
                            });
        }
        short c = color, d = depth;
        color = depth = INVALID;
        failure =
                cleanup(
                        failure,
                        () -> {
                            if (c != INVALID) {
                                bgfx_destroy_texture(c);
                            }
                        });
        failure =
                cleanup(
                        failure,
                        () -> {
                            if (d != INVALID) {
                                bgfx_destroy_texture(d);
                            }
                        });
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
    }

    /** Trusted enclosing owner calls ONLY after actual bgfx_shutdown returned successfully. */
    void afterContextShutdown() {
        requireThread();
        if (!closed) {
            throw new IllegalStateException("Global owner not closed");
        }
        if (freed) {
            return;
        }
        freed = true;
        for (var slot : slots) {
            if (slot.pixels != null) {
                MemoryUtil.memFree(slot.pixels);
                slot.pixels = null;
            }
            slot.request = null;
        }
        if (packed != null) {
            MemoryUtil.memFree(packed);
            packed = null;
        }
    }

    private void requireThread() {
        if (Thread.currentThread() != thread) {
            throw new IllegalStateException("Global owner thread differs");
        }
    }

    private void requireOpen() {
        requireThread();
        if (!initialized || closed) {
            throw new IllegalStateException("Global owner not initialized/open");
        }
    }

    private static Throwable cleanup(Throwable prior, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException | Error failure) {
            if (prior == null) {
                return failure;
            }
            if (prior != failure) {
                prior.addSuppressed(failure);
            }
        }
        return prior;
    }

    private static short texture(int format, long flags) throws IOException {
        return valid(bgfx_create_texture_2d(WIDTH, HEIGHT, false, 1, format, flags, null, 0));
    }

    private static short valid(short handle) throws IOException {
        if (handle == INVALID) {
            throw new IOException("Global frame target unavailable");
        }
        return handle;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("Global initialization canceled");
        }
    }
}
