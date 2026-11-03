package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_API_VERSION;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_OPENGL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_get_supported_renderers;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.lwjgl.Version;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.system.MemoryStack;

/** Native linkage and packaged-resource proof; deliberately does not initialize a GPU. */
public final class GpuRuntimeProbe {
    private static final String SHADERS = "/com/vibeminer/rendering/bgfx/shaders/";

    private GpuRuntimeProbe() {}

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 0)
            throw new IllegalArgumentException("No probe arguments are supported");
        if (BGFX_API_VERSION != 156) throw new IllegalStateException("Unexpected bgfx API");
        List<Integer> backends = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (var stack = MemoryStack.stackPush()) {
            var renderers = stack.mallocInt(BGFX_RENDERER_TYPE_COUNT);
            int count = Byte.toUnsignedInt(bgfx_get_supported_renderers(renderers));
            if (count < 1 || count > renderers.capacity())
                throw new IllegalStateException("Invalid native backend count: " + count);
            for (int index = 0; index < count; index++) {
                int backend = renderers.get(index);
                backends.add(backend);
                names.add(bgfx_get_renderer_name(backend));
            }
            var init = BGFXInit.calloc(stack);
            bgfx_init_ctor(init);
            if (init.type() < 0 || init.type() > BGFX_RENDERER_TYPE_COUNT)
                throw new IllegalStateException("Native init constructor returned an invalid type");
        }
        verifyBackends(System.getProperty("os.name"), backends);
        verifyShaders();
        System.out.println(
                "Native runtime: LWJGL "
                        + Version.getVersion()
                        + ", bgfx API156; compiled backends="
                        + names
                        + "; 8 packaged shader hashes verified; GPU initialization was not requested");
    }

    static void verifyBackends(String osName, List<Integer> backends) {
        String os = osName.toLowerCase(Locale.ROOT);
        boolean available;
        if (os.contains("mac")) available = backends.contains(BGFX_RENDERER_TYPE_METAL);
        else if (os.contains("win")) available = backends.contains(BGFX_RENDERER_TYPE_DIRECT3D11);
        else if (os.contains("linux"))
            available =
                    backends.contains(BGFX_RENDERER_TYPE_VULKAN)
                            || backends.contains(BGFX_RENDERER_TYPE_OPENGL);
        else throw new IllegalStateException("Unsupported native platform: " + osName);
        if (!available)
            throw new IllegalStateException(
                    "Required compiled backend missing for " + osName + ": " + backends);
    }

    private static void verifyShaders() throws IOException, NoSuchAlgorithmException {
        String manifest = new String(resource("manifest.json"), StandardCharsets.UTF_8);
        if (!Pattern.compile("\\\"bgfxApi\\\"\\s*:\\s*156\\s*,").matcher(manifest).find())
            throw new IllegalStateException("Shader manifest API mismatch");
        for (String profile : List.of("glsl", "spirv", "metal", "dx11")) {
            for (String stage : List.of("vs", "fs")) {
                String path = profile + "/" + stage + "_model.bin";
                byte[] binary = resource(path);
                String magic = stage.equals("vs") ? "VSH" : "FSH";
                if (binary.length < 4
                        || !new String(binary, 0, 3, StandardCharsets.US_ASCII).equals(magic))
                    throw new IllegalStateException("Invalid packaged shader: " + path);
                var entry =
                        Pattern.compile(
                                        "\\\""
                                                + Pattern.quote(path)
                                                + "\\\"\\s*:\\s*\\{[^}]*?\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"",
                                        Pattern.DOTALL)
                                .matcher(manifest);
                String digest =
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
                if (!entry.find() || !entry.group(1).equals(digest))
                    throw new IllegalStateException("Packaged shader hash mismatch: " + path);
            }
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (var input = GpuRuntimeProbe.class.getResourceAsStream(SHADERS + path)) {
            if (input == null) throw new IOException("Missing packaged shader resource: " + path);
            return input.readAllBytes();
        }
    }
}
