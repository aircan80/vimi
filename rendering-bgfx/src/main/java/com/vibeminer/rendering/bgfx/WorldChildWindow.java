package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.MSG;
import org.lwjgl.system.windows.RECT;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;

/**
 * #381 (Windows only, opt-in): the World helper's child window inside the application window,
 * created on its own thread that only pumps messages. Cross-process parenting attaches the two
 * processes' input queues, so the render loop never owns a window and cannot stall the GUI. The
 * child is disabled (never takes focus or input). It is created hidden, the size of the parent, so
 * it never covers the application window before the GUI has placed it over the viewport and shown
 * it. The render loop reads the size each frame: a minimised, hidden (not shown yet) or destroyed
 * window reports no area, and the loop then neither draws nor resizes.
 */
final class WorldChildWindow implements WorldWindowPresentation.Surface {
    /** The class name the GUI looks the child up by (WorldNativePresenter.CHILD_CLASS). */
    static final String CLASS_NAME = "VimiWorldPresent";

    /** Private message: destroy the window on its own thread and end the message loop. */
    private static final int CLOSE = User32.WM_USER + 1;

    private final long hwnd, parent;
    private final Thread thread;
    private final WindowProc proc;
    private final AtomicBoolean destroyed;
    private boolean closed;

    private WorldChildWindow(
            long hwnd, long parent, Thread thread, WindowProc proc, AtomicBoolean destroyed) {
        this.hwnd = hwnd;
        this.parent = parent;
        this.thread = thread;
        this.proc = proc;
        this.destroyed = destroyed;
    }

    static WorldChildWindow create(long parent) throws IOException {
        if (Platform.get() != Platform.WINDOWS || parent == 0) {
            throw new IOException("Native World presentation needs a Windows parent window");
        }
        var created = new CompletableFuture<Long>();
        var destroyed = new AtomicBoolean();
        // The window procedure lives as long as the window; it is freed after the thread ends.
        var proc =
                WindowProc.create(
                        (window, message, w, l) -> {
                            if (message == CLOSE) {
                                User32.DestroyWindow(null, window);
                                return 0;
                            }
                            if (message == User32.WM_NCDESTROY) {
                                // Ours, or the parent's destruction taking its children along.
                                // It arrives as a sent message inside GetMessage, after which
                                // nothing is posted: post WM_QUIT so the loop ends.
                                destroyed.set(true);
                                postQuitMessage();
                            }
                            return User32.DefWindowProc(window, message, w, l);
                        });
        var thread =
                Thread.ofPlatform()
                        .daemon()
                        .name("world-present-window")
                        .start(
                                () -> {
                                    try (var stack = MemoryStack.stackPush()) {
                                        var cls =
                                                WNDCLASSEX
                                                        .calloc(stack)
                                                        .cbSize(WNDCLASSEX.SIZEOF)
                                                        .lpfnWndProc(proc)
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
                                                                | User32.WS_DISABLED,
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
                                        while (!destroyed.get()
                                                && User32.GetMessage(null, msg, 0, 0, 0)) {
                                            User32.TranslateMessage(msg);
                                            User32.DispatchMessage(msg);
                                        }
                                    } catch (Throwable failure) {
                                        created.completeExceptionally(failure);
                                    }
                                });
        try {
            return new WorldChildWindow(
                    created.get(10, TimeUnit.SECONDS), parent, thread, proc, destroyed);
        } catch (Exception failure) {
            // No window to own the procedure: end the thread if it is still creating, then free.
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!thread.isAlive()) {
                proc.free();
            }
            throw new IOException("Child window creation failed", failure);
        }
    }

    /** True while the window's message thread runs; for tests. */
    boolean pumping() {
        return thread.isAlive();
    }

    /** user32 PostQuitMessage(0), which LWJGL does not bind; through java.lang.foreign. */
    @SuppressWarnings("restricted")
    private static void postQuitMessage() {
        try {
            var linker = java.lang.foreign.Linker.nativeLinker();
            var user32 =
                    java.lang.foreign.SymbolLookup.libraryLookup(
                            "user32", java.lang.foreign.Arena.global());
            var post =
                    linker.downcallHandle(
                            user32.find("PostQuitMessage").orElseThrow(),
                            java.lang.foreign.FunctionDescriptor.ofVoid(
                                    java.lang.foreign.ValueLayout.JAVA_INT));
            post.invokeExact(0);
        } catch (Throwable unavailable) {
            // The loop also checks the destroyed flag after each message.
        }
    }

    @Override
    public long hwnd() {
        return hwnd;
    }

    /**
     * The window's size in physical pixels, {width, height}; {0, 0} when it has no area: minimised
     * (with its parent), hidden, or destroyed. The child has no frame, so its window rectangle is
     * its client area.
     */
    @Override
    public int[] clientSize() {
        if (destroyed.get()
                || User32.IsIconic(parent)
                || User32.IsIconic(hwnd)
                || !User32.IsWindowVisible(hwnd)) {
            return new int[] {0, 0};
        }
        try (var stack = MemoryStack.stackPush()) {
            var area = RECT.calloc(stack);
            if (!User32.GetWindowRect(null, hwnd, area)) {
                return new int[] {0, 0};
            }
            return new int[] {
                Math.max(0, area.right() - area.left()), Math.max(0, area.bottom() - area.top())
            };
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!destroyed.get()) {
            User32.PostMessage(null, hwnd, CLOSE, 0, 0);
        }
        try {
            thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!thread.isAlive()) {
            proc.free();
        }
    }
}
