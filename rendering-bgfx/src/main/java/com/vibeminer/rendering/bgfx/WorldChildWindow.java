package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.MSG;
import org.lwjgl.system.windows.RECT;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;

/**
 * #381 spike (Windows only, opt-in): the World helper's child window inside the application window,
 * created on its own thread that only pumps messages. Cross-process parenting attaches the two
 * processes' input queues, so the render loop never owns a window and cannot stall the GUI. The
 * child is disabled (never takes focus or input) and starts the size of the parent's client area;
 * the GUI then places it over the viewport. The render loop reads the client size each frame.
 */
final class WorldChildWindow implements AutoCloseable {
    /** The class name the GUI looks the child up by (WorldNativePresenter.CHILD_CLASS). */
    static final String CLASS_NAME = "VimiWorldPresent";

    /** Private message: destroy the window on its own thread and end the message loop. */
    private static final int CLOSE = User32.WM_USER + 1;

    private final long hwnd;
    private final Thread thread;
    private WindowProc proc;

    private WorldChildWindow(long hwnd, Thread thread) {
        this.hwnd = hwnd;
        this.thread = thread;
    }

    static WorldChildWindow create(long parent) throws IOException {
        if (Platform.get() != Platform.WINDOWS || parent == 0) {
            throw new IOException("Native World presentation needs a Windows parent window");
        }
        var created = new CompletableFuture<Long>();
        var holder = new WindowProc[1];
        var closing = new boolean[1];
        var thread =
                Thread.ofPlatform()
                        .daemon()
                        .name("world-present-window")
                        .start(
                                () -> {
                                    try (var stack = MemoryStack.stackPush()) {
                                        holder[0] =
                                                WindowProc.create(
                                                        (window, message, w, l) -> {
                                                            if (message == CLOSE) {
                                                                User32.DestroyWindow(null, window);
                                                                closing[0] = true;
                                                                return 0;
                                                            }
                                                            return User32.DefWindowProc(
                                                                    window, message, w, l);
                                                        });
                                        var cls =
                                                WNDCLASSEX
                                                        .calloc(stack)
                                                        .cbSize(WNDCLASSEX.SIZEOF)
                                                        .lpfnWndProc(holder[0])
                                                        .lpszClassName(stack.UTF16(CLASS_NAME));
                                        User32.RegisterClassEx(null, cls);
                                        var area = RECT.calloc(stack);
                                        User32.GetWindowRect(null, parent, area);
                                        long window =
                                                User32.CreateWindowEx(
                                                        null,
                                                        0,
                                                        CLASS_NAME,
                                                        "",
                                                        User32.WS_CHILD
                                                                | User32.WS_CLIPSIBLINGS
                                                                | User32.WS_DISABLED
                                                                | User32.WS_VISIBLE,
                                                        0,
                                                        0,
                                                        Math.max(1, area.right() - area.left()),
                                                        Math.max(1, area.bottom() - area.top()),
                                                        parent,
                                                        0,
                                                        0,
                                                        0);
                                        if (window == 0) {
                                            created.completeExceptionally(
                                                    new IOException(
                                                            "Child window creation failed"));
                                            return;
                                        }
                                        created.complete(window);
                                        var msg = MSG.calloc(stack);
                                        while (!closing[0]
                                                && User32.GetMessage(null, msg, 0, 0, 0)) {
                                            User32.TranslateMessage(msg);
                                            User32.DispatchMessage(msg);
                                        }
                                    } catch (Throwable failure) {
                                        created.completeExceptionally(failure);
                                    }
                                });
        try {
            var child = new WorldChildWindow(created.get(10, TimeUnit.SECONDS), thread);
            child.proc = holder[0];
            return child;
        } catch (Exception failure) {
            throw new IOException("Child window creation failed", failure);
        }
    }

    long hwnd() {
        return hwnd;
    }

    /** The client size in physical pixels, {width, height}, at least 1 x 1. */
    int[] clientSize() {
        try (var stack = MemoryStack.stackPush()) {
            var area = RECT.calloc(stack);
            // The child has no frame, so its window rectangle is its client area.
            if (!User32.GetWindowRect(null, hwnd, area)) {
                return new int[] {1, 1};
            }
            return new int[] {
                Math.max(1, area.right() - area.left()), Math.max(1, area.bottom() - area.top())
            };
        }
    }

    @Override
    public void close() {
        User32.PostMessage(null, hwnd, CLOSE, 0, 0);
        try {
            thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (proc != null && !thread.isAlive()) {
            proc.free();
        }
    }
}
