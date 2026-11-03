package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

/**
 * Offscreen GPU renderer for the inline JavaFX viewport; owns no desktop window or application
 * data.
 */
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
        if (!options.containsKey("--scene")
                || !options.containsKey("--frames")
                || !options.containsKey("--parent-pid")) {
            throw new IllegalArgumentException(
                    "Inline GPU renderer requires scene, frames and parent");
        }
        long parent = Long.parseLong(options.get("--parent-pid"));
        var parentProcess =
                ProcessHandle.of(parent)
                        .filter(ProcessHandle::isAlive)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "GPU preview parent is unavailable"));
        var latest = new AtomicReference<GpuViewRequest>();
        var receivedRevision = new AtomicLong();
        try (var lifetime =
                new GpuParentWatchdog(
                        parentProcess.onExit(),
                        System.in,
                        java.time.Duration.ofSeconds(5),
                        () -> Runtime.getRuntime().halt(2),
                        line -> {
                            try {
                                var request = GpuViewRequest.parse(line);
                                long previous = receivedRevision.get();
                                if (request.revision() > previous) {
                                    receivedRevision.set(request.revision());
                                    latest.set(request);
                                }
                            } catch (IllegalArgumentException invalid) {
                                status("ERROR", "INVALID_VIEW " + safe(invalid.getMessage()));
                                throw invalid;
                            }
                        })) {
            render(options, lifetime, latest);
        }
    }

    private static void render(
            Map<String, String> options,
            GpuParentWatchdog lifetime,
            AtomicReference<GpuViewRequest> latest)
            throws IOException {
        if (lifetime.stopping()) {
            return;
        }
        var payload = GpuSceneFile.read(Path.of(options.get("--scene")));
        var scene = GpuModelScene.create(payload.mesh(), payload.appearance());
        if (lifetime.stopping()) {
            return;
        }
        try (var frames = GpuFrameFile.open(Path.of(options.get("--frames")))) {
            boolean initialized = false;
            try {
                try (var stack = MemoryStack.stackPush()) {
                    var init = BGFXInit.calloc(stack);
                    bgfx_init_ctor(init);
                    init.type(backend(options.get("--backend"))).fallback(false);
                    // Offscreen bgfx requires no default backbuffer, including on Metal and D3D.
                    init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                    BGFXPlatform.bgfx_render_frame(0);
                    if (!bgfx_init(init)) {
                        throw new IOException("Unable to initialize GPU backend");
                    }
                    initialized = true;
                }
                var caps = bgfx_get_caps();
                requireHardware(
                        Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
                String backend = bgfx_get_renderer_name(caps.rendererType());
                if (lifetime.stopping()) {
                    return;
                }
                status("HELLO", backend);
                long submittedRevision = 0, nextRender = 0;
                boolean announced = false;
                try (var pipeline = new GpuFramePipeline(scene, payload.visibleParts(), frames)) {
                    while (!lifetime.stopping()) {
                        var view = latest.get();
                        long now = System.nanoTime();
                        if (view != null
                                && view.revision() > submittedRevision
                                && now >= nextRender
                                && pipeline.submit(view)) {
                            submittedRevision = view.revision();
                            nextRender = now + 33_333_333L;
                        }
                        boolean published = pipeline.advance();
                        if (published && !announced) {
                            status("READY", backend);
                            announced = true;
                        }
                        if (!pipeline.pending()) {
                            java.util.concurrent.locks.LockSupport.parkNanos(
                                    view == null || view.revision() <= submittedRevision
                                            ? 20_000_000L
                                            : 2_000_000L);
                        }
                    }
                }
            } finally {
                if (initialized) {
                    bgfx_shutdown();
                }
            }
        }
    }

    static int backend(String requested) {
        if (requested != null) {
            return switch (requested) {
                case "vulkan" -> BGFX_RENDERER_TYPE_VULKAN;
                case "metal" -> BGFX_RENDERER_TYPE_METAL;
                case "d3d11" -> BGFX_RENDERER_TYPE_DIRECT3D11;
                default -> throw new IllegalArgumentException("Unknown inline GPU backend");
            };
        }
        return switch (Platform.get()) {
            case WINDOWS -> BGFX_RENDERER_TYPE_DIRECT3D11;
            case MACOSX -> BGFX_RENDERER_TYPE_METAL;
            default -> BGFX_RENDERER_TYPE_VULKAN;
        };
    }

    static void requireHardware(int vendor, int device) throws IOException {
        if (vendor == 1 || vendor == 5 || vendor == 0x1414 && device == 0x008c) {
            throw new IOException("The graphics driver selected a software rasterizer");
        }
    }

    private static Map<String, String> options(String[] args) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length
                    || !Set.of(
                                    "--scene",
                                    "--frames",
                                    "--parent-pid",
                                    "--backend",
                                    "--headless-smoke")
                            .contains(args[i])
                    || result.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Invalid GPU renderer arguments");
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
