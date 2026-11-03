package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_create_frame_buffer_from_handles;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_destroy_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Explicit authored-data GPU proof, separate from the production inline frame pipeline. */
final class GpuNativeSmoke {
    private GpuNativeSmoke() {}

    static void run(Path directory) throws IOException {
        Files.createDirectories(directory);
        boolean initialized = false;
        short color = -1, depth = -1, target = -1, staging = -1;
        ByteBuffer pixels = null;
        int width = 640, height = 480;
        long started = System.nanoTime();
        try {
            try (var stack = MemoryStack.stackPush()) {
                var init = BGFXInit.calloc(stack);
                bgfx_init_ctor(init);
                init.type(BGFX_RENDERER_TYPE_VULKAN).fallback(false);
                init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                BGFXPlatform.bgfx_render_frame(0);
                if (!bgfx_init(init)) {
                    throw new IOException("Headless Vulkan initialization failed");
                }
                initialized = true;
            }
            var caps = bgfx_get_caps();
            GpuViewerMain.requireHardware(
                    Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
            if ((caps.supported() & BGFX_CAPS_TEXTURE_READ_BACK) == 0) {
                throw new IOException("Diagnostic texture readback is unsupported");
            }
            color =
                    bgfx_create_texture_2d(
                            width,
                            height,
                            false,
                            1,
                            BGFX_TEXTURE_FORMAT_RGBA8,
                            BGFX_TEXTURE_RT,
                            null,
                            0);
            depth =
                    bgfx_create_texture_2d(
                            width,
                            height,
                            false,
                            1,
                            BGFX_TEXTURE_FORMAT_D32F,
                            BGFX_TEXTURE_RT,
                            null,
                            0);
            target = bgfx_create_frame_buffer_from_handles(new short[] {color, depth}, false);
            staging =
                    bgfx_create_texture_2d(
                            width,
                            height,
                            false,
                            1,
                            BGFX_TEXTURE_FORMAT_RGBA8,
                            BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                            null,
                            0);
            if (color == -1 || depth == -1 || target == -1 || staging == -1) {
                throw new IOException("Headless GPU target creation failed");
            }
            bgfx_set_view_frame_buffer(0, target);
            pixels = MemoryUtil.memAlloc(width * height * 4);
            var mesh =
                    new PreviewMesh(
                            new float[] {0, -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1},
                            new int[] {0, 1, 2, 0, 2, 3},
                            List.of(new PreviewMesh.Part(7, 0, 6)));
            var appearance =
                    new MeshAppearance(
                            mesh,
                            new float[] {0, 1, 1, 1, 1, 0, 0, 0},
                            new float[] {1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0},
                            List.of(new MeshAppearance.Surface(0, 0, false, false, false)),
                            List.of(
                                    new MeshAppearance.Texture(
                                            2,
                                            2,
                                            new int[] {
                                                0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff
                                            })));
            long uploaded = System.nanoTime();
            try (var renderer = new BgfxModelRenderer(GpuModelScene.create(mesh, appearance))) {
                long submitted = System.nanoTime();
                renderer.draw(width, height, StaticMeshRenderer.View.initial(), true);
                bgfx_blit(1, staging, 0, 0, 0, 0, color, 0, 0, 0, 0, width, height, 1);
                int ready = bgfx_read_texture(staging, pixels, 0, 0);
                int frame;
                long deadline = System.nanoTime() + 10_000_000_000L;
                do {
                    frame = bgfx_frame(BGFX_FRAME_NONE);
                    if (System.nanoTime() > deadline) {
                        throw new IOException("GPU diagnostic timed out");
                    }
                } while (Integer.compareUnsigned(frame, ready) < 0);
                long finished = System.nanoTime();
                int colored = 0;
                var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int at = (y * width + x) * 4;
                        int r = pixels.get(at) & 255, g = pixels.get(at + 1) & 255;
                        int b = pixels.get(at + 2) & 255, a = pixels.get(at + 3) & 255;
                        if (r > g + 30 || g > b + 30 || b > r + 30) {
                            colored++;
                        }
                        image.setRGB(
                                x,
                                caps.originBottomLeft() ? height - 1 - y : y,
                                a << 24 | r << 16 | g << 8 | b);
                    }
                }
                if (colored < 1000) {
                    throw new IOException("GPU authored texture did not render");
                }
                ImageIO.write(image, "png", directory.resolve("gpu-preview.png").toFile());
                String report =
                        "{\n  \"renderer\": \""
                                + bgfx_get_renderer_name(caps.rendererType())
                                + "\",\n  \"vendorId\": "
                                + Short.toUnsignedInt(caps.vendorId())
                                + ",\n  \"deviceId\": "
                                + Short.toUnsignedInt(caps.deviceId())
                                + ",\n  \"coloredPixels\": "
                                + colored
                                + ",\n  \"initializationMillis\": "
                                + (uploaded - started) / 1_000_000.0
                                + ",\n  \"uploadMillis\": "
                                + (submitted - uploaded) / 1_000_000.0
                                + ",\n  \"firstFrameWithDiagnosticReadbackMillis\": "
                                + (finished - submitted) / 1_000_000.0
                                + "\n}\n";
                Files.writeString(directory.resolve("gpu-proof.json"), report);
                GpuViewerMain.status("READY", "headless Vulkan authored texture verified");
            }
        } finally {
            if (initialized) {
                if (target != -1) {
                    bgfx_destroy_frame_buffer(target);
                }
                for (short texture : new short[] {color, depth, staging}) {
                    if (texture != -1) {
                        bgfx_destroy_texture(texture);
                    }
                }
                bgfx_shutdown();
            }
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
            }
        }
    }
}
