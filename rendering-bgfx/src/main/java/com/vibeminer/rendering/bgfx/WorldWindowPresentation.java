package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_VSYNC;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.bgfx_request_screen_shot;
import static org.lwjgl.bgfx.BGFX.bgfx_reset;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.bgfx.BGFXCallbackInterface;
import org.lwjgl.bgfx.BGFXCallbackVtbl;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.system.MemoryUtil;

/**
 * #381 spike (opt-in): native presentation of the World helper into its child window. The owner
 * keeps every tile, object and draw path; this class swaps the readback target for the window's
 * swap chain: bgfx is initialised on the child HWND with vsync, the backbuffer follows the child's
 * size, and a frame without a scene draw is cleared so a present never shows undefined content.
 *
 * <p>Diagnostics for the proof only: with {@code -Dvimi.world.presentProbe=<file>} the helper
 * records present intervals, reads the swap chain back once (a bgfx screen shot of the backbuffer)
 * after {@link #SHOT_AFTER_DRAWS} scene draws, and writes both to that file on close.
 */
final class WorldWindowPresentation implements AutoCloseable {
    static final String PROBE_PROPERTY = "vimi.world.presentProbe";
    static final int SHOT_AFTER_DRAWS = 240;
    private static final short BACKBUFFER = (short) 0xffff;

    private final WorldChildWindow window;
    private final Path probe;
    private final List<Long> intervals = new ArrayList<>();
    private final AtomicReference<int[]> shot = new AtomicReference<>();
    private BGFXCallbackInterface callbacks;
    private int width, height, draws;
    private long lastPresent;
    private boolean shotRequested;
    private int[] shotResult;

    WorldWindowPresentation(WorldChildWindow window) {
        this.window = Objects.requireNonNull(window);
        String path = System.getProperty(PROBE_PROPERTY);
        probe = path == null || path.isBlank() ? null : Path.of(path);
    }

    /** bgfx on the child window: its size, vsync, one frame of latency, float depth. */
    void configure(BGFXInit init) {
        int[] size = window.clientSize();
        width = size[0];
        height = size[1];
        init.platformData().nwh(window.hwnd());
        init.resolution()
                .width(width)
                .height(height)
                .reset(BGFX_RESET_VSYNC)
                .maxFrameLatency((byte) 1)
                .formatDepthStencil(BGFX_TEXTURE_FORMAT_D32F);
        if (probe != null) {
            callbacks = probeCallbacks(shot);
            init.callback(callbacks);
        }
    }

    /** Follows the child's size (the GUI places it); returns {width, height} for this frame. */
    int[] frameSize() {
        int[] size = window.clientSize();
        if (size[0] != width || size[1] != height) {
            width = size[0];
            height = size[1];
            bgfx_reset(width, height, BGFX_RESET_VSYNC, BGFX_TEXTURE_FORMAT_COUNT);
        }
        return new int[] {width, height};
    }

    /** A scene draw was submitted this frame; the proof's screen shot follows the first 240. */
    void drew() {
        draws++;
        if (probe != null && !shotRequested && draws >= SHOT_AFTER_DRAWS) {
            shotRequested = true;
            bgfx_request_screen_shot(BACKBUFFER, "world-present");
        }
    }

    /**
     * Called before {@code bgfx_frame} when nothing was drawn: present a defined, cleared frame.
     */
    void clearUndrawn() {
        bgfx_set_view_rect(0, 0, 0, width, height);
        bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 0, 0);
        bgfx_touch(0);
    }

    /** Called after each {@code bgfx_frame} (which waited for the display). */
    void presented(long now) {
        if (probe != null && lastPresent != 0 && draws > 0) {
            intervals.add(now - lastPresent);
        }
        lastPresent = now;
        var taken = shot.getAndSet(null);
        if (taken != null) {
            shotResult = taken;
        }
    }

    @Override
    public void close() {
        try {
            if (probe != null) {
                writeProbe();
            }
        } catch (IOException ignored) {
            // Diagnostics only.
        } finally {
            window.close();
        }
    }

    /** After {@code bgfx_shutdown}: callbacks may no longer be called. */
    void afterShutdown() {
        if (callbacks != null) {
            var vtbl = callbacks.vtbl();
            vtbl.fatal().free();
            vtbl.trace_vargs().free();
            vtbl.profiler_begin().free();
            vtbl.profiler_begin_literal().free();
            vtbl.profiler_end().free();
            vtbl.cache_read_size().free();
            vtbl.cache_read().free();
            vtbl.cache_write().free();
            vtbl.screen_shot().free();
            vtbl.capture_begin().free();
            vtbl.capture_end().free();
            vtbl.capture_frame().free();
            vtbl.free();
            callbacks.free();
            callbacks = null;
        }
    }

    private void writeProbe() throws IOException {
        var sorted = new ArrayList<>(intervals);
        sorted.sort(null);
        String verdict;
        if (shotResult == null) {
            verdict = "CHILD_SHOWS_FRAMES FAIL no swap-chain readback after " + draws + " draws";
        } else {
            int rgb = shotResult[2];
            boolean sized = shotResult[0] == width && shotResult[1] == height;
            boolean drawn = rgb != 0x000000;
            verdict =
                    String.format(
                            Locale.ROOT,
                            "CHILD_SHOWS_FRAMES %s swapchain %dx%d (window %dx%d) centre rgb(%d,%d,%d)",
                            sized && drawn ? "PASS" : "FAIL",
                            shotResult[0],
                            shotResult[1],
                            width,
                            height,
                            rgb >> 16 & 255,
                            rgb >> 8 & 255,
                            rgb & 255);
        }
        Files.writeString(
                probe,
                String.format(
                        Locale.ROOT,
                        "%s%nPRESENT_INTERVALS n %d p50Ms %.3f p95Ms %.3f maxMs %.3f draws %d%n",
                        verdict,
                        sorted.size(),
                        pct(sorted, .5),
                        pct(sorted, .95),
                        pct(sorted, 1),
                        draws));
    }

    private static double pct(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * (sorted.size() - 1))))
                / 1e6;
    }

    /** Fatal errors end the helper; the screen shot keeps the size and the centre pixel. */
    private static BGFXCallbackInterface probeCallbacks(AtomicReference<int[]> shot) {
        var vtbl =
                BGFXCallbackVtbl.calloc()
                        .fatal(
                                (self, file, line, code, message) -> {
                                    System.err.println(
                                            "World present fatal: "
                                                    + MemoryUtil.memUTF8Safe(message));
                                    Runtime.getRuntime().halt(70);
                                })
                        .trace_vargs((self, file, line, format, args) -> {})
                        .profiler_begin((self, name, abgr, file, line) -> {})
                        .profiler_begin_literal((self, name, abgr, file, line) -> {})
                        .profiler_end(self -> {})
                        .cache_read_size((self, id) -> 0)
                        .cache_read((self, id, data, size) -> false)
                        .cache_write((self, id, data, size) -> {})
                        .screen_shot(
                                (self, path, w, h, pitch, format, data, size, yflip) -> {
                                    long at = data + (long) (h / 2) * pitch + (w / 2) * 4L;
                                    int b = MemoryUtil.memGetByte(at) & 255;
                                    int g = MemoryUtil.memGetByte(at + 1) & 255;
                                    int r = MemoryUtil.memGetByte(at + 2) & 255;
                                    shot.set(new int[] {w, h, r << 16 | g << 8 | b});
                                })
                        .capture_begin((self, w, h, pitch, format, yflip) -> {})
                        .capture_end(self -> {})
                        .capture_frame((self, data, size) -> {});
        return BGFXCallbackInterface.calloc().vtbl(vtbl);
    }
}
