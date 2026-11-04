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

import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
        if (WorldStreamMain.requested(args)) {
            int result = WorldStreamMain.execute(args);
            if (result != 0) {
                System.exit(result);
            }
            return;
        }
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
        boolean terrain = terrainMode(options);
        long parent = Long.parseLong(options.get("--parent-pid"));
        var parentProcess =
                ProcessHandle.of(parent)
                        .filter(ProcessHandle::isAlive)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "GPU preview parent is unavailable"));
        var latest = new AtomicReference<GpuFrameRequest>();
        var controlFailure = new AtomicReference<IllegalArgumentException>();
        var receivedRevision = new AtomicLong();
        try (var lifetime =
                new GpuParentWatchdog(
                        parentProcess.onExit(),
                        System.in,
                        java.time.Duration.ofSeconds(5),
                        () -> Runtime.getRuntime().halt(2),
                        line -> {
                            try {
                                var request = parseControl(line, terrain);
                                long previous = receivedRevision.get();
                                if (request.revision() > previous) {
                                    receivedRevision.set(request.revision());
                                    latest.set(request);
                                }
                            } catch (IllegalArgumentException invalid) {
                                controlFailure.compareAndSet(null, invalid);
                                throw invalid;
                            }
                        })) {
            try {
                render(options, lifetime, latest, terrain);
            } catch (CancellationException canceled) {
                if (!lifetime.stopping()) {
                    throw canceled;
                }
            }
            if (controlFailure.get() != null) {
                throw controlFailure.get();
            }
        }
    }

    private static void render(
            Map<String, String> options,
            GpuParentWatchdog lifetime,
            AtomicReference<GpuFrameRequest> latest,
            boolean terrain)
            throws IOException {
        if (lifetime.stopping()) {
            return;
        }
        GpuModelScene modelScene = null;
        TerrainScene terrainScene = null;
        Set<Integer> qualified = Set.of();
        GpuTerrainTextureAnnex.Decoded terrainTextures = null;
        TerrainTextureStatus.Code textureInputFailure = TerrainTextureStatus.Code.NONE;
        boolean textureRequested = options.containsKey("--terrain-textures");
        if (terrain) {
            if (textureRequested) {
                var geometry =
                        GpuTerrainSceneFile.readBound(
                                Path.of(options.get("--terrain-scene")), lifetime::stopping);
                terrainScene = geometry.scene();
                try {
                    terrainTextures =
                            GpuTerrainTextureAnnex.read(
                                    Path.of(options.get("--terrain-textures")),
                                    geometry,
                                    lifetime::stopping);
                } catch (GpuTerrainTextureAnnex.Invalid invalid) {
                    textureInputFailure = invalid.code();
                } catch (IOException | java.nio.file.InvalidPathException unavailable) {
                    textureInputFailure = TerrainTextureStatus.Code.INPUT_UNAVAILABLE;
                }
            } else {
                terrainScene =
                        GpuTerrainSceneFile.read(
                                Path.of(options.get("--terrain-scene")), lifetime::stopping);
            }
        } else {
            var payload = GpuSceneFile.read(Path.of(options.get("--scene")));
            modelScene = GpuModelScene.create(payload.mesh(), payload.appearance());
            qualified = payload.visibleParts();
        }
        if (lifetime.stopping()) {
            return;
        }
        try (var frames =
                terrain
                        ? GpuFrameFile.open(
                                Path.of(options.get("--frames")),
                                BgfxTerrainRenderer.MAX_WIDTH,
                                BgfxTerrainRenderer.MAX_HEIGHT)
                        : GpuFrameFile.open(Path.of(options.get("--frames")))) {
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
                try (var pipeline =
                        terrain
                                ? new GpuFramePipeline(
                                        terrainScene, terrainTextures, frames, lifetime::stopping)
                                : new GpuFramePipeline(modelScene, qualified, frames)) {
                    if (textureRequested) {
                        System.out.println(
                                pipeline.textureStatus(textureInputFailure).protocolLine());
                        System.out.flush();
                    }
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

    static boolean terrainMode(Map<String, String> options) {
        if (options.containsKey("--scene") == options.containsKey("--terrain-scene")
                || !options.containsKey("--frames")
                || !options.containsKey("--parent-pid")
                || options.containsKey("--terrain-textures")
                        && !options.containsKey("--terrain-scene")) {
            throw new IllegalArgumentException(
                    "Inline GPU renderer requires exactly one model or terrain scene, frames and parent");
        }
        return options.containsKey("--terrain-scene");
    }

    static GpuFrameRequest parseControl(String line, boolean terrain) {
        return terrain ? GpuWorldRequest.parse(line) : GpuViewRequest.parse(line);
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
                                    "--terrain-scene",
                                    "--terrain-textures",
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
