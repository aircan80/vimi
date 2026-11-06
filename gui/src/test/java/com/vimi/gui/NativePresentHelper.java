package com.vimi.gui;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_VSYNC;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_COUNT;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_reset;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.MSG;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;

/**
 * #381 spike, helper side (a separate process, like the World helper). Creates a WS_CHILD window
 * under a parent HWND owned by another process, on a thread that only pumps messages, and presents
 * bgfx D3D11 frames into it with vsync. No readback.
 *
 * <p>stdin: {@code SIZE x y w h} (parent-client physical pixels), {@code CAM revision}, {@code HIDE},
 * {@code QUIT}. stdout: {@code CHILD hwnd backend}, {@code PRESENTED revision}, and a final
 * {@code STATS} line with present intervals.
 */
public final class NativePresentHelper {
    private NativePresentHelper() {}

    public static void main(String[] args) throws Exception {
        if (args[0].equals("readback")) {
            readback();
            return;
        }
        long parent = Long.parseLong(args[0]);
        var commands = new ConcurrentLinkedQueue<String>();
        var created = new CompletableFuture<Long>();
        var windowThread = Thread.ofPlatform().daemon().name("present-child-window").start(() -> {
            try (var stack = MemoryStack.stackPush()) {
                var proc = WindowProc.create(User32::DefWindowProc);
                var name = stack.UTF16("VimiWorldPresentSpike");
                var cls = WNDCLASSEX.calloc(stack).cbSize(WNDCLASSEX.SIZEOF).lpfnWndProc(proc)
                        .lpszClassName(name);
                if (User32.RegisterClassEx(null, cls) == 0) throw new IllegalStateException("RegisterClassEx");
                long hwnd = User32.CreateWindowEx(null, 0, "VimiWorldPresentSpike", "",
                        User32.WS_CHILD | User32.WS_CLIPSIBLINGS | User32.WS_DISABLED | User32.WS_VISIBLE,
                        0, 0, 64, 64, parent, 0, 0, 0);
                if (hwnd == 0) throw new IllegalStateException("CreateWindowEx");
                created.complete(hwnd);
                var msg = MSG.calloc(stack);
                while (User32.GetMessage(null, msg, 0, 0, 0)) {
                    User32.TranslateMessage(msg);
                    User32.DispatchMessage(msg);
                }
            } catch (Throwable failure) {
                created.completeExceptionally(failure);
            }
        });
        long hwnd = created.get(10, TimeUnit.SECONDS);
        Thread.ofPlatform().daemon().name("present-commands").start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) commands.add(line);
            } catch (Exception ignored) {
                // Parent gone.
            }
            commands.add("QUIT");
        });
        int width = 64, height = 64;
        boolean visible = true;
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.calloc(stack);
            bgfx_init_ctor(init);
            init.type(BGFX_RENDERER_TYPE_DIRECT3D11).fallback(false);
            init.platformData().nwh(hwnd);
            init.resolution().width(width).height(height).reset(BGFX_RESET_VSYNC)
                    .maxFrameLatency((byte) 1).formatDepthStencil(BGFX_TEXTURE_FORMAT_COUNT);
            BGFXPlatform.bgfx_render_frame(0);
            if (!bgfx_init(init)) throw new IllegalStateException("bgfx_init with a child window");
        }
        out("CHILD " + hwnd + " " + bgfx_get_renderer_name(bgfx_get_caps().rendererType()));
        var intervals = new ArrayList<Long>();
        long last = 0, revision = 0, announced = 0, frame = 0;
        boolean running = true;
        while (running) {
            for (String line; (line = commands.poll()) != null; ) {
                var p = line.split(" ");
                switch (p[0]) {
                    case "SIZE" -> {
                        int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                        width = Math.max(1, Integer.parseInt(p[3]));
                        height = Math.max(1, Integer.parseInt(p[4]));
                        User32.SetWindowPos(null, hwnd, User32.HWND_BOTTOM, x, y, width, height,
                                User32.SWP_NOACTIVATE | User32.SWP_ASYNCWINDOWPOS | User32.SWP_SHOWWINDOW);
                        bgfx_reset(width, height, BGFX_RESET_VSYNC, BGFX_TEXTURE_FORMAT_COUNT);
                        visible = true;
                    }
                    case "HIDE" -> {
                        User32.ShowWindow(hwnd, User32.SW_HIDE);
                        visible = false;
                    }
                    case "CAM" -> revision = Long.parseLong(p[1]);
                    case "QUIT" -> running = false;
                    default -> { }
                }
            }
            if (!visible) {
                Thread.sleep(5);
                last = 0;
                continue;
            }
            frame++;
            // A colour that follows the camera revision, so the picture visibly tracks input.
            int shade = (int) (revision * 7 % 200) + 40;
            bgfx_set_view_rect(0, 0, 0, width, height);
            bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH,
                    (shade << 24) | (80 << 16) | ((255 - shade) << 8) | 0xff, 0, 0);
            bgfx_touch(0);
            bgfx_frame(org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE);
            long now = System.nanoTime();
            if (last != 0) intervals.add(now - last);
            last = now;
            if (revision > announced) {
                announced = revision;
                out("PRESENTED " + revision);
            }
        }
        intervals.sort(null);
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        out(String.format(java.util.Locale.ROOT, "STATS frames %d meanMs %.3f p50Ms %.3f p95Ms %.3f maxMs %.3f",
                frame, mean, pct(intervals, .5), pct(intervals, .95), pct(intervals, 1)));
        bgfx_shutdown();
        User32.PostMessage(null, hwnd, User32.WM_CLOSE, 0, 0);
        windowThread.join(1000);
    }

    /**
     * Comparison: the readback path's shape. Headless bgfx renders an 800 x 500 BGRA8 target,
     * blits it to a readback texture, reads it to CPU memory and only then reports the frame; the
     * GUI then uploads a WritableImage. Same colour per camera revision as the native mode.
     */
    static void readback() throws Exception {
        var commands = new ConcurrentLinkedQueue<String>();
        Thread.ofPlatform().daemon().name("readback-commands").start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) commands.add(line);
            } catch (Exception ignored) {
                // Parent gone.
            }
            commands.add("QUIT");
        });
        int width = 800, height = 500;
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.calloc(stack);
            bgfx_init_ctor(init);
            init.type(BGFX_RENDERER_TYPE_DIRECT3D11).fallback(false);
            init.resolution().width(0).height(0).reset(org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE).maxFrameLatency((byte) 1);
            BGFXPlatform.bgfx_render_frame(0);
            if (!bgfx_init(init)) throw new IllegalStateException("headless bgfx_init");
        }
        out("CHILD readback " + bgfx_get_renderer_name(bgfx_get_caps().rendererType()));
        short target = org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer(width, height,
                org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA8, org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT);
        short color = org.lwjgl.bgfx.BGFX.bgfx_get_texture(target, (byte) 0);
        short readable = org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d(width, height, false, 1,
                org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA8,
                org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST | org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK, null, 0);
        var pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
        var copy = new byte[width * height * 4];
        long revision = 0, announced = 0;
        int frame = 0;
        boolean running = true;
        while (running) {
            for (String line; (line = commands.poll()) != null; ) {
                var p = line.split(" ");
                if (p[0].equals("CAM")) revision = Long.parseLong(p[1]);
                else if (p[0].equals("QUIT")) running = false;
            }
            if (revision == announced) {
                Thread.sleep(1);
                frame = bgfx_frame(org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE);
                continue;
            }
            long drawn = revision;
            int shade = (int) (drawn * 7 % 200) + 40;
            org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer(0, target);
            bgfx_set_view_rect(0, 0, 0, width, height);
            bgfx_set_view_clear(0, BGFX_CLEAR_COLOR, (shade << 24) | (80 << 16) | ((255 - shade) << 8) | 0xff, 0, 0);
            bgfx_touch(0);
            org.lwjgl.bgfx.BGFX.bgfx_blit(1, readable, 0, 0, 0, 0, color, 0, 0, 0, 0, width, height, 1);
            int ready = org.lwjgl.bgfx.BGFX.bgfx_read_texture(readable, pixels, 0, 0);
            do {
                frame = bgfx_frame(org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE);
            } while (frame < ready);
            pixels.get(0, copy);
            announced = drawn;
            out("FRAME " + drawn);
        }
        org.lwjgl.system.MemoryUtil.memFree(pixels);
        out("STATS readback");
        bgfx_shutdown();
    }

    private static double pct(java.util.List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * (sorted.size() - 1)))) / 1e6;
    }

    private static void out(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
