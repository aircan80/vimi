package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_VSYNC;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
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
    static final int TERRAIN_SAMPLES = 20;

    /**
     * The sky the renderer draws at {@code row} of a {@code height}-row frame (top-origin rows):
     * 91,141,177 at the top plus 80,60,37 times the row fraction, floored (terrain/sky shader).
     */
    static int skyAt(int row, int height) {
        double t = row / (double) height;
        return (int) Math.floor(91 + 80 * t) << 16
                | (int) Math.floor(141 + 60 * t) << 8
                | (int) Math.floor(177 + 37 * t);
    }

    /** Neither cleared black nor within 12 per channel of the sky at that row. */
    static boolean terrainPixel(int rgb, int row, int height) {
        if ((rgb & 0xffffff) == 0) {
            return false;
        }
        int sky = skyAt(row, height);
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((rgb >> shift & 255) - (sky >> shift & 255)) > 12) {
                return true;
            }
        }
        return false;
    }

    /** The loop's frame period: 60 Hz, the readback loop's draw cap too. */
    static final long IDLE_FRAME_NANOS = 16_666_667;

    /** The shortest interval between presents while the window has area: 3/4 of a frame. */
    static final long MIN_PRESENT_NANOS = IDLE_FRAME_NANOS * 3 / 4;

    /**
     * How long to wait before the next present, {@code since} after the previous one. A covered,
     * locked or minimised window lets flip-model present return at once, and a frame alone costs
     * only a few milliseconds, so without pacing the loop would draw at hundreds of hertz. Presents
     * are therefore at least 3/4 of a frame apart (at most 80 per second when unpaced; with vsync
     * the 4 ms slack before the next vblank keeps 60 Hz), and a whole frame apart while the window
     * has no area.
     */
    static long paceNanos(long since, boolean hasArea) {
        if (since < 0) {
            return 0;
        }
        return Math.max(0, (hasArea ? MIN_PRESENT_NANOS : IDLE_FRAME_NANOS) - since);
    }

    /** What a reported window size means for the swap chain. */
    enum SizeChange {
        /** Same size: draw. */
        NONE,
        /** New size: reset the backbuffer, then draw. */
        RESIZE,
        /** No area (minimised, hidden, destroyed): neither draw nor reset. */
        EMPTY
    }

    static SizeChange sizeChange(int width, int height, int[] reported) {
        if (reported[0] < 1 || reported[1] < 1) {
            return SizeChange.EMPTY;
        }
        return reported[0] == width && reported[1] == height ? SizeChange.NONE : SizeChange.RESIZE;
    }

    /** The window the helper presents into; {@link WorldChildWindow}, or a fake in tests. */
    interface Surface extends AutoCloseable {
        long hwnd();

        /** {width, height} in physical pixels; {0, 0} when the window has no area. */
        int[] clientSize();

        @Override
        void close();
    }

    private boolean hasArea = true, closed;
    private static final short BACKBUFFER = (short) 0xffff;

    private final Surface window;
    private final Path probe;
    private final List<Long> intervals = new ArrayList<>();
    private final AtomicReference<int[]> shot = new AtomicReference<>();
    private BGFXCallbackInterface callbacks;
    private int width, height, draws;
    private long lastPresent;
    private boolean shotRequested;
    private int[] shotResult;

    WorldWindowPresentation(Surface window) {
        this.window = Objects.requireNonNull(window);
        String path = System.getProperty(PROBE_PROPERTY);
        probe = path == null || path.isBlank() ? null : Path.of(path);
    }

    /**
     * bgfx on the child window: its size, vsync, one frame of latency, D32F depth (the terrain
     * renderer requires a D32F target, so the backbuffer matches it; no fallback).
     */
    void configure(BGFXInit init) {
        int[] size = window.clientSize();
        width = Math.max(1, size[0]);
        height = Math.max(1, size[1]);
        init.platformData().nwh(window.hwnd());
        init.resolution()
                .width(width)
                .height(height)
                .reset(BGFX_RESET_VSYNC)
                .maxFrameLatency((byte) 1)
                .formatDepthStencil(BGFX_TEXTURE_FORMAT_D32F);
        if (probe != null) {
            if (callbacks == null) {
                callbacks = probeCallbacks(shot);
            }
            init.callback(callbacks);
        }
    }

    /** After {@code bgfx_init}: whether the device has the D32F depth target this path needs. */
    static boolean depthSupported() {
        return bgfx_is_texture_valid(0, false, 1, BGFX_TEXTURE_FORMAT_D32F, BGFX_TEXTURE_RT);
    }

    /**
     * Follows the child's size (the GUI places it). Returns {width, height} to draw at, or null
     * while the window has no area: then nothing is drawn and the swap chain is not resized.
     */
    int[] frameSize() {
        int[] size = window.clientSize();
        switch (sizeChange(width, height, size)) {
            case EMPTY -> {
                hasArea = false;
                return null;
            }
            case RESIZE -> {
                width = size[0];
                height = size[1];
                // TODO(#389): OR the World reset flags (anisotropic filtering, MSAA) in here
                // once they are on main, so a resize keeps them.
                bgfx_reset(width, height, BGFX_RESET_VSYNC, BGFX_TEXTURE_FORMAT_COUNT);
            }
            case NONE -> {}
        }
        hasArea = true;
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

    /** Nanoseconds to wait before the next present at {@code now}; 0 before the first. */
    long paceNanos(long now) {
        return lastPresent == 0 ? 0 : paceNanos(now - lastPresent, hasArea);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
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
            int rgb = shotResult[2], terrain = shotResult[3], ground = shotResult[4];
            boolean sized = shotResult[0] == width && shotResult[1] == height;
            boolean drawn = rgb != 0x000000;
            verdict =
                    String.format(
                            Locale.ROOT,
                            "CHILD_SHOWS_FRAMES %s swapchain %dx%d (window %dx%d) centre rgb(%d,%d,%d)"
                                    + " terrain %d/%d lower samples%s",
                            sized && drawn && terrain > 0 ? "PASS" : "FAIL",
                            shotResult[0],
                            shotResult[1],
                            width,
                            height,
                            rgb >> 16 & 255,
                            rgb >> 8 & 255,
                            rgb & 255,
                            terrain,
                            TERRAIN_SAMPLES,
                            ground < 0
                                    ? ""
                                    : String.format(
                                            Locale.ROOT,
                                            " first rgb(%d,%d,%d)",
                                            ground >> 16 & 255,
                                            ground >> 8 & 255,
                                            ground & 255));
        }
        Files.writeString(
                probe,
                String.format(
                        Locale.ROOT,
                        "%s%nPRESENT_INTERVALS n %d p50Ms %.3f p95Ms %.3f maxMs %.3f under5Ms %d"
                                + " draws %d depth %s%n",
                        verdict,
                        sorted.size(),
                        pct(sorted, .5),
                        pct(sorted, .95),
                        pct(sorted, 1),
                        sorted.stream().filter(interval -> interval < 5_000_000).count(),
                        draws,
                        "D32F"));
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
                                    // Row r of the picture (0 = top) in the shot's memory.
                                    java.util.function.IntBinaryOperator pixel =
                                            (row, column) -> {
                                                int stored = yflip ? h - 1 - row : row;
                                                long at =
                                                        data + (long) stored * pitch + column * 4L;
                                                int b = MemoryUtil.memGetByte(at) & 255;
                                                int g = MemoryUtil.memGetByte(at + 1) & 255;
                                                int r = MemoryUtil.memGetByte(at + 2) & 255;
                                                return r << 16 | g << 8 | b;
                                            };
                                    // Lower half of the centre column: how many samples are
                                    // terrain (neither cleared black nor the sky at that row).
                                    int terrain = 0, firstTerrain = -1;
                                    for (int i = 0; i < TERRAIN_SAMPLES; i++) {
                                        int row = h * (55 + 40 * i / TERRAIN_SAMPLES) / 100;
                                        int rgb = pixel.applyAsInt(row, w / 2);
                                        if (terrainPixel(rgb, row, h)) {
                                            terrain++;
                                            if (firstTerrain < 0) {
                                                firstTerrain = rgb;
                                            }
                                        }
                                    }
                                    shot.set(
                                            new int[] {
                                                w,
                                                h,
                                                pixel.applyAsInt(h / 2, w / 2),
                                                terrain,
                                                firstTerrain
                                            });
                                })
                        .capture_begin((self, w, h, pitch, format, yflip) -> {})
                        .capture_end(self -> {})
                        .capture_frame((self, data, size) -> {});
        return BGFXCallbackInterface.calloc().vtbl(vtbl);
    }
}
