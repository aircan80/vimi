package com.vimi.gui;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.Optional;

/**
 * #381 spike (opt-in, {@code -Dvimi.world.nativePresent=true}, Windows only): the GUI side of
 * native child-window presentation. The World helper creates a {@code WS_CHILD} window of class
 * {@link #CHILD_CLASS} under the application window and presents into it; this class finds that
 * window, places it over the viewport in parent-client physical pixels and shows or hides it. It
 * uses java.lang.foreign only, so the GUI gains no native dependency. Every failure reports
 * unavailable and the view keeps the readback path, which stays the default.
 */
final class WorldNativePresenter {
    static final String PROPERTY = "vimi.world.nativePresent";
    static final String CHILD_CLASS = "VimiWorldPresent";

    /** A rectangle in the parent window's client area, physical pixels. */
    record Placement(int x, int y, int width, int height) {
        Placement {
            if (width < 1 || height < 1) throw new IllegalArgumentException("Empty placement");
        }

        /**
         * The viewport's scene-space bounds (the scene fills the window's client area) at the
         * window's output scale, rounded outward so no frame pixel row is lost to rounding.
         */
        static Placement of(double sceneX, double sceneY, double width, double height,
                double scaleX, double scaleY) {
            if (!(scaleX > 0) || !(scaleY > 0) || !(width > 0) || !(height > 0)
                    || !Double.isFinite(sceneX + sceneY + width + height + scaleX + scaleY))
                throw new IllegalArgumentException("Invalid viewport bounds");
            int x0 = (int) Math.floor(sceneX * scaleX), y0 = (int) Math.floor(sceneY * scaleY);
            int x1 = (int) Math.ceil((sceneX + width) * scaleX), y1 = (int) Math.ceil((sceneY + height) * scaleY);
            return new Placement(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
        }
    }

    /** The few user32 calls the GUI makes; replaced in tests. */
    interface Windows {
        long findChild(long parent, String className);

        boolean place(long child, Placement placement);

        boolean show(long child, boolean visible);
    }

    /** True when the property asks for native presentation on a platform that can do it. */
    static boolean requested(String property, String osName) {
        return Boolean.parseBoolean(property) && osName != null && osName.startsWith("Windows");
    }

    static boolean requested() {
        return requested(System.getProperty(PROPERTY), System.getProperty("os.name"));
    }

    /** The JavaFX window's native handle through Glass (JavaFX on the classpath); empty if not. */
    static Optional<Long> windowHandle(javafx.stage.Window window) {
        try {
            var helper = Class.forName("com.sun.javafx.stage.WindowHelper");
            var peer = helper.getMethod("getPeer", javafx.stage.Window.class).invoke(null, window);
            if (peer == null) return Optional.empty();
            var platform = peer.getClass().getMethod("getPlatformWindow").invoke(peer);
            long handle = (long) platform.getClass().getMethod("getNativeWindow").invoke(platform);
            return handle == 0 ? Optional.empty() : Optional.of(handle);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    private final Windows windows;
    private long parent, child;
    private Placement placed;
    private boolean shown;

    WorldNativePresenter(Windows windows) {
        this.windows = Objects.requireNonNull(windows);
    }

    /** Uses the application window {@code handle} as the parent the helper's child lives in. */
    void parent(long handle) {
        if (handle != parent) {
            parent = handle;
            child = 0;
            placed = null;
            shown = false;
        }
    }

    /** The helper's child window, once it exists; 0 before. */
    long child() {
        if (child == 0 && parent != 0) child = windows.findChild(parent, CHILD_CLASS);
        return child;
    }

    /**
     * Places and shows the child over the viewport. Only changes are sent. False when there is
     * no child (yet) or the platform refused, in which case the caller keeps the readback view.
     */
    boolean show(Placement placement) {
        long window = child();
        if (window == 0) return false;
        if (!placement.equals(placed)) {
            if (!windows.place(window, placement)) {
                child = 0;
                return false;
            }
            placed = placement;
        }
        if (!shown) {
            if (!windows.show(window, true)) return false;
            shown = true;
        }
        return true;
    }

    /** Hides the child (tab hidden, minimised, instance, fallback). */
    void hide() {
        if (child != 0 && shown) windows.show(child, false);
        shown = false;
    }

    boolean shown() {
        return shown;
    }

    /** user32 through java.lang.foreign; unavailable (all calls fail) off Windows. */
    @SuppressWarnings("restricted")
    static Windows user32() {
        try {
            var linker = Linker.nativeLinker();
            var user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            MethodHandle find = linker.downcallHandle(user32.find("FindWindowExW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle setPos = linker.downcallHandle(user32.find("SetWindowPos").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            MethodHandle showWindow = linker.downcallHandle(user32.find("ShowWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            return new Windows() {
                @Override public long findChild(long parent, String className) {
                    try (var arena = Arena.ofConfined()) {
                        var name = arena.allocateFrom(className, java.nio.charset.StandardCharsets.UTF_16LE);
                        return (long) find.invokeExact(parent, 0L, name, MemorySegment.NULL);
                    } catch (Throwable failed) {
                        return 0;
                    }
                }

                @Override public boolean place(long child, Placement p) {
                    // HWND_BOTTOM among the parent's children; never activate; asynchronous so a
                    // busy helper thread cannot stall the FX thread.
                    int flags = 0x0010 /* NOACTIVATE */ | 0x4000 /* ASYNCWINDOWPOS */;
                    try {
                        return (int) setPos.invokeExact(child, 1L, p.x(), p.y(), p.width(), p.height(), flags) != 0;
                    } catch (Throwable failed) {
                        return false;
                    }
                }

                @Override public boolean show(long child, boolean visible) {
                    try {
                        // SW_SHOWNA (8) or SW_HIDE (0); the return value is the previous state.
                        int ignored = (int) showWindow.invokeExact(child, visible ? 8 : 0);
                        return true;
                    } catch (Throwable failed) {
                        return false;
                    }
                }
            };
        } catch (RuntimeException | LinkageError unavailable) {
            return new Windows() {
                @Override public long findChild(long parent, String className) {
                    return 0;
                }

                @Override public boolean place(long child, Placement placement) {
                    return false;
                }

                @Override public boolean show(long child, boolean visible) {
                    return false;
                }
            };
        }
    }
}
