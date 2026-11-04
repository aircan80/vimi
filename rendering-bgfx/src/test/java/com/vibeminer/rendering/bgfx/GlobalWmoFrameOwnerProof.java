package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;

/** Authored actual fixed800-stride frame publication; no Source or process authority. */
public final class GlobalWmoFrameOwnerProof {
    private GlobalWmoFrameOwnerProof() {}

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        Files.createDirectories(directory);
        Path run = Files.createTempDirectory(directory, "run-");
        byte[] image = {
            (byte) 255,
            0,
            0,
            (byte) 255,
            0,
            (byte) 255,
            0,
            (byte) 255,
            0,
            0,
            (byte) 255,
            (byte) 255,
            (byte) 255,
            (byte) 255,
            (byte) 255,
            (byte) 255
        };
        var authored =
                new GlobalWmoAuthoredData(
                        new float[] {
                            10, -10, -10, 0, 0, 10, 10, -10, 1, 0, 10, -10, 10, 0, 1, 10, 10, 10, 1,
                            1
                        },
                        new int[] {0, 1, 2, 2, 1, 3},
                        List.of(image),
                        List.of(new WorldGlobalHashes.Batch(0, 0, 0, 6)));
        var destination =
                WorldGlobalSceneWriter.Destination.create(run, new Object(), "7".repeat(64));
        try (var file =
                        WorldGlobalSceneWriter.writeUnadvertised(
                                destination, authored, () -> false);
                var frames = GpuFrameFile.create(run.resolve("frames"), 800, 500)) {
            var decoded =
                    GpuGlobalWmoSceneFile.read(
                            destination.path(), file.expected().input(), () -> false);
            var owner = GlobalWmoFrameOwner.allocate(frames);
            boolean context = false;
            try {
                try (var stack = MemoryStack.stackPush()) {
                    var init = BGFXInit.calloc(stack);
                    bgfx_init_ctor(init);
                    init.type(BGFX_RENDERER_TYPE_VULKAN).fallback(false);
                    init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                    BGFXPlatform.bgfx_render_frame(0);
                    if (!bgfx_init(init)) {
                        throw new AssertionError("Vulkan context unavailable");
                    }
                    context = true;
                }
                var caps = bgfx_get_caps();
                GpuViewerMain.requireHardware(
                        Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
                owner.initialize(decoded, () -> false);
                require(owner.stats().directBytes() == 6_400_000, "fixed direct payload");
                long sequence = 0;
                int samples = 0;
                for (int i = 0; i < 12; i++) {
                    int width = new int[] {800, 317, 160}[i % 3];
                    int height = new int[] {500, 163, 80}[i % 3];
                    var camera = new GlobalWmoCamera(0, (i % 3) * .5, (i % 3) * .2, 0, 0);
                    var request = new GlobalWmoFrameRequest(i + 1, camera, width, height);
                    long deadline = System.nanoTime() + 10_000_000_000L;
                    while (!owner.submit(request)) {
                        owner.advance();
                        require(System.nanoTime() < deadline, "submit deadline");
                    }
                    GpuFrameFile.Frame frame = null;
                    while (frame == null || frame.controlRevision() != request.revision()) {
                        owner.advance();
                        frame = frames.readLatest(sequence);
                        require(System.nanoTime() < deadline, "publication deadline");
                    }
                    sequence = frame.sequence();
                    require(
                            frame.width() == width && frame.height() == height,
                            "ticket dimensions");
                    var pixels = frame.pixels();
                    for (int y : new int[] {height / 4, height * 3 / 4}) {
                        for (int x : new int[] {width / 4, width * 3 / 4}) {
                            double nativeY = camera.y() - (x + .5 - width * .5) * 10 / (width * .8);
                            double nativeZ =
                                    camera.z() - (y + .5 - height * .5) * 10 / (width * .8);
                            double u = (nativeY + 10) / 20, v = (nativeZ + 10) / 20;
                            int actual = pixels.get(y * width + x);
                            for (int c = 0; c < 3; c++) {
                                int expected = sample(image, u, v, c);
                                int channel = (actual >>> (16 - c * 8)) & 255;
                                require(
                                        Math.abs(expected - channel) <= 2,
                                        "fixed stride/UV oracle "
                                                + i
                                                + " "
                                                + x
                                                + ","
                                                + y
                                                + " channel"
                                                + c
                                                + " expected"
                                                + expected
                                                + " actual"
                                                + channel);
                            }
                            require((actual >>> 24) == 255, "opaque");
                            samples++;
                        }
                    }
                    require(owner.stats().directBytes() == 6_400_000, "resize allocation");
                }
                int peakPending = 0;
                for (int i = 0; i < 3; i++) {
                    var camera = new GlobalWmoCamera(0, i, 0, 0, 0);
                    var request =
                            new GlobalWmoFrameRequest(13 + i, camera, 317 - i * 31, 163 - i * 17);
                    require(owner.submit(request), "pipelined admission");
                    peakPending = Math.max(peakPending, owner.stats().pending());
                    owner.advance();
                }
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (owner.pending()) {
                    owner.advance();
                    require(System.nanoTime() < deadline, "pipelined drain");
                }
                var finalFrame = frames.readLatest(sequence);
                require(
                        finalFrame != null
                                && finalFrame.controlRevision() == 15
                                && finalFrame.width() == 255
                                && finalFrame.height() == 129,
                        "pipelined final actual metadata");
                System.out.println(
                        "GLOBAL_FIXED_PIPELINED peakPending=" + peakPending + " finalRevision=15");
                System.out.println(
                        "GLOBAL_FIXED_FRAME_PASS frames=12 samples="
                                + samples
                                + " direct=6400000 mapping=4800256 dx11=PENDING");
            } finally {
                try {
                    owner.close();
                } finally {
                    if (context) {
                        bgfx_shutdown();
                        owner.afterContextShutdown();
                    }
                    decoded.close();
                }
            }
            require(owner.stats().directBytes() == 0, "shutdown direct retirement");
        }
        for (boolean fatal : new boolean[] {false, true}) {
            Path failedRun = Files.createTempDirectory(directory, "partial-");
            var target =
                    WorldGlobalSceneWriter.Destination.create(
                            failedRun, new Object(), "7".repeat(64));
            try (var file =
                            WorldGlobalSceneWriter.writeUnadvertised(
                                    target, authored, () -> false);
                    var frames = GpuFrameFile.create(failedRun.resolve("frames"), 800, 500)) {
                var decoded =
                        GpuGlobalWmoSceneFile.read(
                                target.path(), file.expected().input(), () -> false);
                var owner = GlobalWmoFrameOwner.allocate(frames);
                boolean context = false;
                var original = new AssertionError("Authored partial owner fatal");
                try {
                    try (var stack = MemoryStack.stackPush()) {
                        var init = BGFXInit.calloc(stack);
                        bgfx_init_ctor(init);
                        init.type(BGFX_RENDERER_TYPE_VULKAN).fallback(false);
                        init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                        BGFXPlatform.bgfx_render_frame(0);
                        require(bgfx_init(init), "partial context");
                        context = true;
                    }
                    boolean failed = false;
                    try {
                        owner.initialize(
                                decoded,
                                () -> {
                                    boolean partial = owner.stats().directBytes() == 1_600_000;
                                    if (partial && fatal) {
                                        throw original;
                                    }
                                    return partial;
                                });
                    } catch (java.util.concurrent.CancellationException expected) {
                        require(!fatal, "fatal normalized");
                        failed = true;
                    } catch (AssertionError actual) {
                        require(fatal && actual == original, "fatal identity");
                        failed = true;
                    }
                    require(failed, "partial initialization reached");
                    require(
                            owner.stats().directBytes() == 1_600_000,
                            "registered shell retains partial destination");
                    owner.close();
                    require(
                            owner.stats().directBytes() == 1_600_000,
                            "close cannot free before shutdown");
                } finally {
                    try {
                        owner.close();
                    } finally {
                        if (context) {
                            bgfx_shutdown();
                            owner.afterContextShutdown();
                        }
                        decoded.close();
                    }
                }
                require(owner.stats().directBytes() == 0, "partial shutdown retirement");
            }
        }
        Path failedRead = Files.createTempDirectory(directory, "failed-read-");
        var target =
                WorldGlobalSceneWriter.Destination.create(failedRead, new Object(), "7".repeat(64));
        try (var file = WorldGlobalSceneWriter.writeUnadvertised(target, authored, () -> false);
                var frames = GpuFrameFile.create(failedRead.resolve("frames"), 800, 500)) {
            var decoded =
                    GpuGlobalWmoSceneFile.read(target.path(), file.expected().input(), () -> false);
            var original = new AssertionError("Authored native read issued then failure");
            var driver =
                    new GlobalWmoFrameOwner.Driver() {
                        public int frame() {
                            return org.lwjgl.bgfx.BGFX.bgfx_frame(
                                    org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE);
                        }

                        public int read(short texture, java.nio.ByteBuffer pixels) {
                            org.lwjgl.bgfx.BGFX.bgfx_read_texture(texture, pixels, 0, 0);
                            throw original;
                        }

                        public long nanoTime() {
                            return System.nanoTime();
                        }

                        public void free(java.nio.ByteBuffer pixels) {
                            org.lwjgl.system.MemoryUtil.memFree(pixels);
                        }
                    };
            var owner = GlobalWmoFrameOwner.allocate(frames, driver);
            boolean context = false;
            try {
                try (var stack = MemoryStack.stackPush()) {
                    var init = BGFXInit.calloc(stack);
                    bgfx_init_ctor(init);
                    init.type(BGFX_RENDERER_TYPE_VULKAN).fallback(false);
                    init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                    BGFXPlatform.bgfx_render_frame(0);
                    require(bgfx_init(init), "failed read context");
                    context = true;
                }
                owner.initialize(decoded, () -> false);
                try {
                    owner.submit(
                            new GlobalWmoFrameRequest(
                                    1, new GlobalWmoCamera(0, 0, 0, 0, 0), 317, 163));
                    throw new AssertionError("Expected native read failure");
                } catch (AssertionError failure) {
                    require(failure == original, "read identity");
                }
                try {
                    owner.advance();
                    throw new AssertionError("Expected sticky advance failure");
                } catch (AssertionError failure) {
                    require(failure == original, "advance identity");
                }
                require(frames.readLatest(0) == null, "failed read never publishes");
            } finally {
                try {
                    owner.close();
                } catch (AssertionError failure) {
                    require(failure == original, "close identity");
                } finally {
                    require(
                            owner.stats().directBytes() == 6_400_000,
                            "unknown read retains every destination");
                    if (context) {
                        bgfx_shutdown();
                        owner.afterContextShutdown();
                    }
                    decoded.close();
                }
            }
            require(owner.stats().directBytes() == 0, "unknown read actual shutdown retirement");
        }
        System.out.println(
                "GLOBAL_FIXED_READ_FAILURE_PASS sticky=true noPositiveFrame=true actualShutdownBeforeFree=true");
        System.out.println(
                "GLOBAL_FIXED_PARTIAL_PASS cases=2 sameError=true shutdownBeforeFree=true");
    }

    private static int sample(byte[] rgba, double u, double v, int channel) {
        double x = u * 2 - .5, y = v * 2 - .5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy, result = 0;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int at = (Math.floorMod(iy + dy, 2) * 2 + Math.floorMod(ix + dx, 2)) * 4 + channel;
                result += (rgba[at] & 255) * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
            }
        }
        return (int) Math.round(result);
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
