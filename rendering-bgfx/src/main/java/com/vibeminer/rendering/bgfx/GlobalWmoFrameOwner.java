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
    private final Driver driver;
    private Throwable terminalFailure, closeFailure, freeFailure;
    private long unknownDirectBytes;

    /** Trusted native operations; deterministic lifecycle tests substitute only this boundary. */
    interface Driver {
        int frame();

        int read(short texture, ByteBuffer pixels);

        long nanoTime();

        void free(ByteBuffer pixels);
    }

    private static final Driver NATIVE =
            new Driver() {
                public int frame() {
                    return bgfx_frame(BGFX_FRAME_NONE);
                }

                public int read(short texture, ByteBuffer pixels) {
                    return bgfx_read_texture(texture, pixels, 0, 0);
                }

                public long nanoTime() {
                    return System.nanoTime();
                }

                public void free(ByteBuffer pixels) {
                    MemoryUtil.memFree(pixels);
                }
            };
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
        boolean fenceKnown;
    }

    record Stats(long directBytes, long framePayloadBytes, int pending, long sequence) {}

    private GlobalWmoFrameOwner(GpuFrameFile frames, Driver driver) {
        this.driver = Objects.requireNonNull(driver);
        this.frames = Objects.requireNonNull(frames);
        if (frames.maxWidth() != WIDTH || frames.maxHeight() != HEIGHT) {
            throw new IllegalArgumentException("Global requires exact800x500 mapping");
        }
    }

    /** No direct/native allocation: register this shell before initialize. Mapping is borrowed. */
    static GlobalWmoFrameOwner allocate(GpuFrameFile frames) {
        return new GlobalWmoFrameOwner(frames, NATIVE);
    }

    static GlobalWmoFrameOwner allocate(GpuFrameFile frames, Driver driver) {
        return new GlobalWmoFrameOwner(frames, driver);
    }

    /** First accepted call adopts Decoded. Foreign/repeated/closed calls reject before adoption. */
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
        try {
            return submitActual(request);
        } catch (RuntimeException | Error failure) {
            terminalFailure = failure;
            throw failure;
        }
    }

    private boolean submitActual(GlobalWmoFrameRequest request) {
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
        available.fenceKnown = false;
        available.ready = driver.read(available.texture, available.pixels);
        available.fenceKnown = true;
        issued = request.revision();
        submitted = true;
        return true;
    }

    boolean advance() throws IOException {
        requireOpen();
        try {
            return advanceActual();
        } catch (IOException | RuntimeException | Error failure) {
            terminalFailure = failure;
            throw failure;
        }
    }

    private boolean advanceActual() throws IOException {
        current = driver.frame();
        submitted = false;
        Slot newest = null;
        for (var slot : slots) {
            if (slot.request != null
                    && slot.fenceKnown
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
            if (slot.request != null && slot.fenceKnown && ready(current, slot.ready)) {
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
        var rowView = source.duplicate();
        for (int y = 0; y < height; y++) {
            int row = flip ? HEIGHT - 1 - y : y;
            int start = row * WIDTH * 4;
            rowView.clear().position(start).limit(start + width * 4);
            target.put(rowView);
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
        long bytes = unknownDirectBytes + (packed == null ? 0 : BYTES);
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
            rethrow(closeFailure);
            return;
        }
        closed = true;
        Throwable failure = terminalFailure;
        try {
            long deadline = driver.nanoTime() + 5_000_000_000L;
            while (pending()) {
                for (var slot : slots) {
                    if (slot.request != null && !slot.fenceKnown) {
                        throw new IllegalStateException("Global readback fence unknown");
                    }
                }
                current = driver.frame();
                for (var slot : slots) {
                    if (slot.request != null && slot.fenceKnown && ready(current, slot.ready)) {
                        slot.request = null;
                    }
                }
                if (pending() && driver.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("Global readback drain timed out");
                }
            }
        } catch (RuntimeException | Error error) {
            if (failure == null) {
                failure = error;
            } else if (failure != error) {
                failure.addSuppressed(error);
            }
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
        closeFailure = failure;
        rethrow(failure);
    }

    /** Trusted enclosing owner calls ONLY after actual bgfx_shutdown returned successfully. */
    void afterContextShutdown() {
        requireThread();
        if (!closed) {
            throw new IllegalStateException("Global owner not closed");
        }
        if (freed) {
            rethrow(freeFailure);
            return;
        }
        freed = true;
        for (var slot : slots) {
            var pixels = slot.pixels;
            slot.pixels = null;
            free(pixels);
            slot.request = null;
        }
        var pixels = packed;
        packed = null;
        free(pixels);
        rethrow(freeFailure);
    }

    private void free(ByteBuffer pixels) {
        if (pixels == null) {
            return;
        }
        try {
            driver.free(pixels);
        } catch (RuntimeException | Error failure) {
            unknownDirectBytes += BYTES;
            if (freeFailure == null) {
                freeFailure = failure;
            } else if (freeFailure != failure) {
                freeFailure.addSuppressed(failure);
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new IllegalStateException("Global owner terminal failure", failure);
        }
    }

    private void requireThread() {
        if (Thread.currentThread() != thread) {
            throw new IllegalStateException("Global owner thread differs");
        }
    }

    private void requireOpen() {
        requireThread();
        rethrow(terminalFailure);
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
