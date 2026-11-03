package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_NATIVE_WINDOW_HANDLE_TYPE_WAYLAND;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_OPENGL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_VSYNC;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_COUNT;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_type;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_reset;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;

import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWNativeWayland;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

/** Isolated native GPU window: JavaFX and platform GLFW event loops never share a process. */
public final class GpuViewerMain {
    private GpuViewerMain() {}

    public static void main(String[] args) {
        try {
            var options = options(args);
            if (options.containsKey("--headless-smoke")) {
                GpuNativeSmoke.run(Path.of(options.get("--headless-smoke")));
            } else {
                run(options);
            }
            status("CLOSED", "");
        } catch (Exception | LinkageError failure) {
            status("ERROR", "GPU_UNAVAILABLE " + safe(failure.getMessage()));
            System.exit(1);
        }
    }

    private static void run(Map<String, String> options) throws IOException {
        if (!options.containsKey("--scene") || !options.containsKey("--parent-pid")) {
            throw new IllegalArgumentException("GPU viewer requires a scene and parent process");
        }
        long parent = Long.parseLong(options.get("--parent-pid"));
        if (parent < 1 || ProcessHandle.of(parent).filter(ProcessHandle::isAlive).isEmpty()) {
            throw new IllegalArgumentException("GPU preview parent is unavailable");
        }
        var payload = GpuSceneFile.read(Path.of(options.get("--scene")));
        var scene = GpuModelScene.create(payload.mesh(), payload.appearance());
        var stopping = new AtomicBoolean();
        boolean glfw = false, bgfx = false;
        long window = 0;
        var errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        try {
            if (!glfwInit()) {
                throw new IOException("Unable to initialize native window system");
            }
            glfw = true;
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            window = glfwCreateWindow(960, 720, "VibeMiner · Preparing GPU preview…", 0, 0);
            if (window == 0) {
                throw new IOException("Unable to create GPU preview window");
            }
            int[] size = new int[2];
            try (var stack = MemoryStack.stackPush()) {
                var width = stack.mallocInt(1);
                var height = stack.mallocInt(1);
                glfwGetFramebufferSize(window, width, height);
                size[0] = Math.max(1, width.get(0));
                size[1] = Math.max(1, height.get(0));
                var init = BGFXInit.calloc(stack);
                bgfx_init_ctor(init);
                init.type(backend(options.get("--backend"))).fallback(false);
                init.resolution().width(size[0]).height(size[1]).reset(BGFX_RESET_VSYNC);
                platform(init, window);
                // Keep all native window/render calls on this process's main OS thread.
                BGFXPlatform.bgfx_render_frame(0);
                if (!bgfx_init(init)) {
                    throw new IOException("Unable to initialize GPU backend");
                }
                bgfx = true;
            }
            String backend = bgfx_get_renderer_name(bgfx_get_renderer_type());
            status("HELLO", backend);
            glfwSetWindowTitle(
                    window,
                    "VibeMiner GPU · "
                            + backend
                            + " · drag: rotate · scroll: zoom · E: edges · T: textures · A: all surfaces");
            var dirty = new AtomicBoolean(true);
            var resized = new AtomicBoolean();
            double[] camera = {-0.3, 0, 1.15};
            double[] pointer = new double[2];
            boolean[] modes = {false, true, false};
            long targetWindow = window;
            glfwSetFramebufferSizeCallback(
                    window,
                    (handle, width, height) -> {
                        size[0] = width;
                        size[1] = height;
                        resized.set(true);
                        dirty.set(true);
                    });
            glfwSetMouseButtonCallback(
                    window,
                    (handle, button, action, mods) -> {
                        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                            try (var stack = MemoryStack.stackPush()) {
                                var x = stack.mallocDouble(1);
                                var y = stack.mallocDouble(1);
                                glfwGetCursorPos(handle, x, y);
                                pointer[0] = x.get(0);
                                pointer[1] = y.get(0);
                            }
                        }
                    });
            glfwSetCursorPosCallback(
                    window,
                    (handle, x, y) -> {
                        if (glfwGetMouseButton(handle, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
                            camera[0] += (x - pointer[0]) * .012;
                            camera[1] = Math.clamp(camera[1] + (y - pointer[1]) * .008, -1.1, 1.1);
                            dirty.set(true);
                        }
                        pointer[0] = x;
                        pointer[1] = y;
                    });
            glfwSetScrollCallback(
                    window,
                    (handle, x, y) -> {
                        camera[2] = Math.clamp(camera[2] * Math.exp(y * .12), .4, 3);
                        dirty.set(true);
                    });
            glfwSetKeyCallback(
                    window,
                    (handle, key, scan, action, mods) -> {
                        if (action != GLFW_PRESS && action != GLFW_REPEAT) {
                            return;
                        }
                        switch (key) {
                            case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(handle, true);
                            case GLFW_KEY_LEFT -> camera[0] -= .12;
                            case GLFW_KEY_RIGHT -> camera[0] += .12;
                            case GLFW_KEY_UP -> camera[1] = Math.max(-1.1, camera[1] - .08);
                            case GLFW_KEY_DOWN -> camera[1] = Math.min(1.1, camera[1] + .08);
                            case GLFW_KEY_EQUAL, GLFW_KEY_KP_ADD ->
                                    camera[2] = Math.min(3, camera[2] * 1.1);
                            case GLFW_KEY_MINUS, GLFW_KEY_KP_SUBTRACT ->
                                    camera[2] = Math.max(.4, camera[2] / 1.1);
                            case GLFW_KEY_HOME -> {
                                camera[0] = -.3;
                                camera[1] = 0;
                                camera[2] = 1.15;
                            }
                            case GLFW_KEY_E -> modes[0] = !modes[0];
                            case GLFW_KEY_T -> modes[1] = !modes[1];
                            case GLFW_KEY_A -> modes[2] = !modes[2];
                            default -> {
                                return;
                            }
                        }
                        dirty.set(true);
                    });
            Thread.startVirtualThread(() -> readControl(stopping));
            boolean announced = false;
            try (var renderer = new BgfxModelRenderer(scene)) {
                while (!stopping.get()
                        && !glfwWindowShouldClose(targetWindow)
                        && ProcessHandle.of(parent).filter(ProcessHandle::isAlive).isPresent()) {
                    glfwPollEvents();
                    if (size[0] > 0 && size[1] > 0 && dirty.getAndSet(false)) {
                        int width = Math.min(8192, size[0]), height = Math.min(8192, size[1]);
                        if (resized.getAndSet(false)) {
                            bgfx_reset(width, height, BGFX_RESET_VSYNC, BGFX_TEXTURE_FORMAT_COUNT);
                        }
                        Set<Integer> visible =
                                modes[2]
                                        ? Set.of()
                                        : payload.visibleParts().isEmpty()
                                                ? Set.of(Integer.MIN_VALUE)
                                                : payload.visibleParts();
                        renderer.draw(
                                width,
                                height,
                                new StaticMeshRenderer.View(
                                        camera[0], camera[1], camera[2], modes[0], visible),
                                modes[1]);
                        bgfx_frame(BGFX_FRAME_NONE);
                        if (!announced) {
                            status("READY", backend);
                            announced = true;
                        }
                    }
                    glfwWaitEventsTimeout(.05);
                }
            }
        } finally {
            if (bgfx) {
                bgfx_shutdown();
            }
            if (window != 0) {
                Callbacks.glfwFreeCallbacks(window);
                glfwDestroyWindow(window);
            }
            if (glfw) {
                glfwTerminate();
            }
            glfwSetErrorCallback(null);
            errors.free();
        }
    }

    static int backend(String requested) {
        if (requested != null) {
            return switch (requested) {
                case "vulkan" -> BGFX_RENDERER_TYPE_VULKAN;
                case "metal" -> BGFX_RENDERER_TYPE_METAL;
                case "d3d11" -> BGFX_RENDERER_TYPE_DIRECT3D11;
                case "opengl" -> BGFX_RENDERER_TYPE_OPENGL;
                default -> throw new IllegalArgumentException("Unknown GPU backend");
            };
        }
        return switch (Platform.get()) {
            case WINDOWS -> BGFX_RENDERER_TYPE_DIRECT3D11;
            case MACOSX -> BGFX_RENDERER_TYPE_METAL;
            default -> BGFX_RENDERER_TYPE_VULKAN;
        };
    }

    private static void platform(BGFXInit init, long window) {
        switch (Platform.get()) {
            case WINDOWS -> init.platformData().nwh(GLFWNativeWin32.glfwGetWin32Window(window));
            case MACOSX -> init.platformData().nwh(GLFWNativeCocoa.glfwGetCocoaWindow(window));
            default -> {
                if (glfwGetPlatform() == GLFW_PLATFORM_WAYLAND) {
                    init.platformData()
                            .ndt(GLFWNativeWayland.glfwGetWaylandDisplay())
                            .nwh(GLFWNativeWayland.glfwGetWaylandWindow(window))
                            .type(BGFX_NATIVE_WINDOW_HANDLE_TYPE_WAYLAND);
                } else {
                    init.platformData()
                            .ndt(GLFWNativeX11.glfwGetX11Display())
                            .nwh(GLFWNativeX11.glfwGetX11Window(window));
                }
            }
        }
    }

    private static void readControl(AtomicBoolean stopping) {
        try {
            var command = new StringBuilder();
            int value;
            while ((value = System.in.read()) != -1) {
                if (value == '\n') {
                    if (!command.toString().equals("CLOSE")) {
                        break;
                    }
                    stopping.set(true);
                    return;
                }
                if (value != '\r') {
                    command.append((char) value);
                }
                if (command.length() > 64) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Lost parent/control channel terminates the preview rather than leaving an orphan.
        } finally {
            stopping.set(true);
        }
    }

    private static Map<String, String> options(String[] args) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length
                    || !Set.of("--scene", "--parent-pid", "--backend", "--headless-smoke")
                            .contains(args[i])
                    || result.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Invalid GPU viewer arguments");
            }
        }
        return result;
    }

    static void status(String kind, String detail) {
        System.out.println("VIMI_GPU " + kind + (detail.isBlank() ? "" : " " + safe(detail)));
        System.out.flush();
    }

    private static String safe(String message) {
        if (message == null) {
            return "Native GPU preview failed";
        }
        return message.replaceAll("[\\p{Cntrl}]", " ")
                .substring(0, Math.min(1024, message.length()));
    }
}
