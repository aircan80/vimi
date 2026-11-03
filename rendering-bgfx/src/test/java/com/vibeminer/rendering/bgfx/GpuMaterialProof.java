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
import java.util.Set;
import javax.imageio.ImageIO;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Explicit opt-in authored-data material proof. Opens no native window or application library.
 * Diagnostic readback belongs only to this test harness, never the production presentation path.
 */
public final class GpuMaterialProof {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;
    private static final int CLEAR = 0xff14202a;
    private static final int[] QUADRANTS = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff};
    private static final float[] UV = {0, 1, 1, 1, 1, 0, 0, 0};
    private static final StaticMeshRenderer.View VIEW =
            new StaticMeshRenderer.View(0, 0, 1, false, Set.of());

    private GpuMaterialProof() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one diagnostic output directory");
        }
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        try (var proof = new Harness()) {
            proof.checkOrientationAndLighting(directory);
            proof.checkReverseWinding();
            proof.checkCutoutDepthAndOpaqueAlpha();
            proof.checkVisibilityIdsAndEmptySentinel();
            proof.checkWrapAndClamp();
            proof.checkMaximumIndex();
            proof.checkRichCombinerAndUnlit(directory);
            proof.checkVertexEnvironmentOracle(directory);
            proof.checkAdditiveDepthAndOrder();
            proof.checkRichOrdinalFirstWins();
            proof.checkRigidCardCameraAndDiagnostics(directory);
            var caps = bgfx_get_caps();
            GpuViewerMain.requireHardware(
                    Short.toUnsignedInt(caps.vendorId()), Short.toUnsignedInt(caps.deviceId()));
            String report =
                    "{\n  \"renderer\": \""
                            + bgfx_get_renderer_name(caps.rendererType())
                            + "\",\n  \"vendorId\": "
                            + Short.toUnsignedInt(caps.vendorId())
                            + ",\n  \"deviceId\": "
                            + Short.toUnsignedInt(caps.deviceId())
                            + ",\n  \"framesVerified\": "
                            + proof.frames
                            + ",\n  \"cases\": [\"orientation-nearest\", \"flat-smooth-lighting\","
                            + " \"reverse-winding\", \"alpha-127-128-depth\", \"opaque-alpha-zero\","
                            + " \"equal-depth-first-wins\", \"part-id-ordinal\", \"empty-sentinel\", \"independent-wrap-clamp\","
                            + " \"maximum-index-65535\", \"rich-unlit-NA\", \"vertex-ENV-oracle\","
                            + " \"additive-alpha-depth-order\", \"rigid-camera-card\", \"rich-neutral-diagnostic\"]\n}\n";
            Files.writeString(directory.resolve("gpu-material-proof.json"), report);
            System.out.println("GPU material proof passed: " + proof.frames + " authored frames");
        }
    }

    private static final class Harness implements AutoCloseable {
        private short color = -1;
        private short depth = -1;
        private short target = -1;
        private short staging = -1;
        private ByteBuffer pixels;
        private boolean initialized;
        private int frames;

        Harness() throws IOException {
            try {
                try (var stack = MemoryStack.stackPush()) {
                    var init = BGFXInit.calloc(stack);
                    bgfx_init_ctor(init);
                    init.type(BGFX_RENDERER_TYPE_VULKAN);
                    // A headless device has no backbuffer; offscreen attachments set frame size.
                    init.resolution().width(0).height(0).reset(BGFX_RESET_NONE);
                    BGFXPlatform.bgfx_render_frame(0);
                    if (!bgfx_init(init)) {
                        throw new IOException("Opt-in GPU material proof requires headless Vulkan");
                    }
                    initialized = true;
                }
                if (bgfx_get_caps().rendererType() != BGFX_RENDERER_TYPE_VULKAN
                        || (bgfx_get_caps().supported() & BGFX_CAPS_TEXTURE_READ_BACK) == 0) {
                    throw new IOException("Vulkan diagnostic texture readback is unavailable");
                }
                color =
                        bgfx_create_texture_2d(
                                WIDTH,
                                HEIGHT,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_RGBA8,
                                BGFX_TEXTURE_RT,
                                null,
                                0);
                depth =
                        bgfx_create_texture_2d(
                                WIDTH,
                                HEIGHT,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_D32F,
                                BGFX_TEXTURE_RT,
                                null,
                                0);
                target = bgfx_create_frame_buffer_from_handles(new short[] {color, depth}, false);
                staging =
                        bgfx_create_texture_2d(
                                WIDTH,
                                HEIGHT,
                                false,
                                1,
                                BGFX_TEXTURE_FORMAT_RGBA8,
                                BGFX_TEXTURE_BLIT_DST | BGFX_TEXTURE_READ_BACK,
                                null,
                                0);
                if (color == -1 || depth == -1 || target == -1 || staging == -1) {
                    throw new IOException("GPU material proof target creation failed");
                }
                pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
                bgfx_set_view_frame_buffer(0, target);
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        private int[] render(PreviewMesh mesh, MeshAppearance appearance, Set<Integer> visible)
                throws IOException {
            return render(
                    mesh,
                    appearance,
                    new StaticMeshRenderer.View(0, 0, 1, false, visible),
                    appearance != null);
        }

        private int[] render(
                PreviewMesh mesh,
                MeshAppearance appearance,
                StaticMeshRenderer.View view,
                boolean textured)
                throws IOException {
            try (var renderer = new BgfxModelRenderer(GpuModelScene.create(mesh, appearance))) {
                renderer.draw(WIDTH, HEIGHT, view, textured);
                bgfx_blit(1, staging, 0, 0, 0, 0, color, 0, 0, 0, 0, WIDTH, HEIGHT, 1);
                int ready = bgfx_read_texture(staging, pixels, 0, 0);
                long deadline = System.nanoTime() + 10_000_000_000L;
                int frame;
                do {
                    frame = bgfx_frame(BGFX_FRAME_NONE);
                    if (System.nanoTime() > deadline) {
                        throw new IOException("GPU material proof readback timed out");
                    }
                } while (Integer.compareUnsigned(frame, ready) < 0);
                int[] result = new int[WIDTH * HEIGHT];
                boolean invertY = bgfx_get_caps().originBottomLeft();
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int at = (y * WIDTH + x) * 4;
                        int pixel =
                                (pixels.get(at + 3) & 255) << 24
                                        | (pixels.get(at) & 255) << 16
                                        | (pixels.get(at + 1) & 255) << 8
                                        | pixels.get(at + 2) & 255;
                        result[(invertY ? HEIGHT - 1 - y : y) * WIDTH + x] = pixel;
                    }
                }
                frames++;
                return result;
            }
        }

        private void checkOrientationAndLighting(Path directory) throws IOException {
            var mesh = quad(false);
            var flat = appearance(mesh, UV, new float[0], QUADRANTS, false, false, false);
            int[] flatGpu = render(mesh, flat, Set.of());
            compareInterior(
                    flatGpu,
                    StaticMeshRenderer.render(flat, WIDTH, HEIGHT, VIEW, () -> false).argb(),
                    3,
                    "flat diffuse orientation/nearest");
            var smooth =
                    appearance(
                            mesh,
                            UV,
                            new float[] {1, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0},
                            QUADRANTS,
                            false,
                            false,
                            false);
            int[] smoothGpu = render(mesh, smooth, Set.of());
            compareInterior(
                    smoothGpu,
                    StaticMeshRenderer.render(smooth, WIDTH, HEIGHT, VIEW, () -> false).argb(),
                    3,
                    "perspective-interpolated smooth intensity");
            require(
                    Math.abs(
                                    channel(flatGpu[100 * WIDTH + 90], 16)
                                            - channel(smoothGpu[100 * WIDTH + 90], 16))
                            > 5,
                    "Smooth-light fixture must distinguish the shader paths");
            writeImage(directory.resolve("gpu-material-orientation.png"), flatGpu);
        }

        private void checkReverseWinding() throws IOException {
            var mesh = quad(true);
            var appearance = appearance(mesh, UV, new float[0], QUADRANTS, false, false, false);
            compareInterior(
                    render(mesh, appearance, Set.of()),
                    StaticMeshRenderer.render(appearance, WIDTH, HEIGHT, VIEW, () -> false).argb(),
                    3,
                    "reverse winding remains visible");
        }

        private void checkCutoutDepthAndOpaqueAlpha() throws IOException {
            for (int alpha : new int[] {127, 128}) {
                var pair = layered(alpha, true);
                int[] image = render(pair.mesh(), pair, Set.of());
                int center = image[128 * WIDTH + 128];
                require(
                        alpha == 127
                                ? channel(center, 0) > 150 && channel(center, 16) == 0
                                : channel(center, 16) > 150 && channel(center, 0) == 0,
                        "Alpha " + alpha + " must preserve cutout and depth-write ordering");
                require((center >>> 24) == 255, "Rendered model output is opaque");
            }
            var opaque = layered(0, false);
            int center = render(opaque.mesh(), opaque, Set.of())[128 * WIDTH + 128];
            require(
                    channel(center, 16) > 150 && channel(center, 0) == 0,
                    "Non-cutout alpha-zero texel remains opaque and writes depth");
            var coincident = layered(255, false, 0, 0);
            int tied = render(coincident.mesh(), coincident, Set.of())[128 * WIDTH + 128];
            require(
                    channel(tied, 16) > 150 && channel(tied, 0) == 0,
                    "Equal-depth surfaces keep the first recorded section under strict LESS");
        }

        private void checkVisibilityIdsAndEmptySentinel() throws IOException {
            var pair = layered(255, false);
            int[] recorded = render(pair.mesh(), pair, Set.of(7));
            require(
                    channel(recorded[128 * WIDTH + 128], 0) > 150,
                    "Recorded ID7 selects section ordinal1's blue material");
            int[] ordinal = render(pair.mesh(), pair, Set.of(1));
            require(ordinal[128 * WIDTH + 128] == CLEAR, "Ordinal1 is not recorded ID7");
            int[] hidden = render(pair.mesh(), pair, Set.of(Integer.MIN_VALUE));
            require(
                    hidden[128 * WIDTH + 128] == CLEAR,
                    "Empty-recorded sentinel hides all surfaces");
            int[] all = render(pair.mesh(), pair, Set.of());
            require(
                    channel(all[128 * WIDTH + 128], 16) > 150,
                    "Explicit all-surfaces selection shows the front red section");
        }

        private void checkWrapAndClamp() throws IOException {
            var mesh = quad(false);
            float[] outside = {-.25f, 1.25f, -.25f, 1.25f, -.25f, 1.25f, -.25f, 1.25f};
            for (boolean wrapU : List.of(false, true)) {
                for (boolean wrapV : List.of(false, true)) {
                    var appearance =
                            appearance(mesh, outside, new float[0], QUADRANTS, wrapU, wrapV, false);
                    compareInterior(
                            render(mesh, appearance, Set.of()),
                            StaticMeshRenderer.render(appearance, WIDTH, HEIGHT, VIEW, () -> false)
                                    .argb(),
                            3,
                            "independent wrap/clamp U=" + wrapU + " V=" + wrapV);
                }
            }
        }

        private void checkMaximumIndex() throws IOException {
            var positions = new float[65536 * 3];
            positions[1] = -1;
            positions[2] = -1;
            positions[4] = 1;
            positions[5] = -1;
            positions[65535 * 3 + 2] = 1;
            var mesh =
                    new PreviewMesh(
                            positions,
                            new int[] {0, 1, 65535},
                            List.of(new PreviewMesh.Part(65535, 0, 3)));
            int[] result = render(mesh, null, Set.of(65535));
            int center = result[128 * WIDTH + 128];
            require(
                    center != CLEAR && channel(center, 16) > 100,
                    "Native unsigned16 compaction preserves index65535");
        }

        private void checkRichCombinerAndUnlit(Path directory) throws IOException {
            var mesh = quad(false);
            var unlit =
                    new MeshAppearance(
                            mesh,
                            UV,
                            List.of(
                                    new MeshAppearance.Surface(
                                            0,
                                            material(
                                                    MeshAppearance.Lighting.UNLIT,
                                                    MeshAppearance.Blend.OPAQUE,
                                                    0))),
                            List.of(new MeshAppearance.Texture(1, 1, new int[] {0x00123456})));
            assertPixel(
                    render(mesh, unlit, VIEW, true),
                    128,
                    128,
                    18,
                    52,
                    86,
                    0,
                    "Unlit opaque preserves RGB and ignores zero texture alpha");
            assertPixel(
                    render(
                            mesh,
                            unlit,
                            new StaticMeshRenderer.View(.4, .3, 1, false, Set.of()),
                            true),
                    128,
                    128,
                    18,
                    52,
                    86,
                    0,
                    "Unlit color is camera independent");
            var environment =
                    new MeshAppearance.Material(
                            MeshAppearance.Combiner.OPAQUE_MOD2X_NA,
                            List.of(
                                    stage(0, MeshAppearance.Coordinate.UV0),
                                    stage(1, MeshAppearance.Coordinate.ENV_SPHERE)),
                            MeshAppearance.Lighting.LIT,
                            MeshAppearance.Blend.OPAQUE);
            var appearance =
                    new MeshAppearance(
                            mesh,
                            UV,
                            new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                            List.of(new MeshAppearance.Surface(0, environment)),
                            List.of(
                                    new MeshAppearance.Texture(1, 1, new int[] {0x00cc9966}),
                                    new MeshAppearance.Texture(1, 1, new int[] {0x00cc6633})));
            int[] image = render(mesh, appearance, VIEW, true);
            // Known native normal +Z gives legacy intensity .28+.72*.6=.712.
            // RGB=2*(204,153,102)*(204,102,51)/255*.712, alpha ignored.
            assertPixel(
                    image,
                    128,
                    128,
                    232,
                    87,
                    29,
                    1,
                    "NA modulation, alpha ignore and deferred clamping");
            writeImage(directory.resolve("gpu-rich-na.png"), image);
        }

        private void checkVertexEnvironmentOracle(Path directory) throws IOException {
            var mesh =
                    new PreviewMesh(
                            new float[] {1, -1, -1, -1, 1, -1, 0, 0, 1},
                            new int[] {0, 1, 2},
                            List.of(new PreviewMesh.Part(0, 0, 3)));
            float h = (float) Math.sqrt(.5);
            int[] gradient = new int[256 * 256];
            for (int y = 0; y < 256; y++)
                for (int x = 0; x < 256; x++) gradient[y * 256 + x] = (x << 16) | (y << 8) | 31;
            var environment =
                    new MeshAppearance.Material(
                            MeshAppearance.Combiner.OPAQUE_MOD2X_NA,
                            List.of(
                                    stage(0, MeshAppearance.Coordinate.UV0),
                                    stage(1, MeshAppearance.Coordinate.ENV_SPHERE)),
                            MeshAppearance.Lighting.LIT,
                            MeshAppearance.Blend.OPAQUE);
            var appearance =
                    new MeshAppearance(
                            mesh,
                            new float[6],
                            new float[] {1, 0, 0, h, h, 0, h, 0, h},
                            List.of(new MeshAppearance.Surface(0, environment)),
                            List.of(
                                    new MeshAppearance.Texture(1, 1, new int[] {0x00404040}),
                                    new MeshAppearance.Texture(256, 256, gradient)));
            var view = new StaticMeshRenderer.View(0, 0, Math.sqrt(3) * 3.2 / 3, false, Set.of());
            int[] image = render(mesh, appearance, view, true);
            // Independent eye fixture P=(-1,-1,-2),(1,-1,-4),(0,1,-3).
            // At pixel center (101.5,180.5), projected triangle barycentrics give
            // perspective weights (.4617316017,.2322943723,.3059740260).
            // PER-VERTEX ENV => UV(.5391215616,.5525187074), texel(138,141).
            // Independently interpolated legacy light=.7687447431 yields RGB(53,54,12).
            assertPixel(
                    image,
                    101,
                    180,
                    53,
                    54,
                    12,
                    1,
                    "Vertex ENV interpolation and signed sphere convention");
            writeImage(directory.resolve("gpu-rich-env-interior.png"), image);
        }

        private void checkAdditiveDepthAndOrder() throws IOException {
            for (int alpha : new int[] {0, 128}) {
                var pair = additivePair(alpha, 0);
                assertPixel(
                        render(pair.mesh(), pair, VIEW, true),
                        128,
                        128,
                        alpha == 0 ? 10 : 60,
                        alpha == 0 ? 20 : 60,
                        alpha == 0 ? 30 : 60,
                        1,
                        "Additive alpha, coplanar LEQUAL and opaque-first phase");
            }
            var hidden = additivePair(128, -.5f);
            assertPixel(
                    render(hidden.mesh(), hidden, VIEW, true),
                    128,
                    128,
                    10,
                    20,
                    30,
                    0,
                    "Opaque foreground occludes additive behind it");
            var two = twoAdditiveCards();
            assertPixel(
                    render(two.mesh(), two, VIEW, true),
                    128,
                    128,
                    60,
                    60,
                    60,
                    1,
                    "Front additive triangle cannot depth-occlude later glow");
        }

        private void checkRichOrdinalFirstWins() throws IOException {
            var pair = additivePair(255, 0);
            var appearance =
                    new MeshAppearance(
                            pair.mesh(),
                            new float[16],
                            List.of(
                                    new MeshAppearance.Surface(
                                            1,
                                            material(
                                                    MeshAppearance.Lighting.UNLIT,
                                                    MeshAppearance.Blend.OPAQUE,
                                                    1)),
                                    new MeshAppearance.Surface(
                                            0,
                                            material(
                                                    MeshAppearance.Lighting.UNLIT,
                                                    MeshAppearance.Blend.OPAQUE,
                                                    0))),
                            List.of(
                                    new MeshAppearance.Texture(1, 1, new int[] {0xffff0000}),
                                    new MeshAppearance.Texture(1, 1, new int[] {0xff0000ff})));
            assertPixel(
                    render(appearance.mesh(), appearance, VIEW, true),
                    128,
                    128,
                    255,
                    0,
                    0,
                    0,
                    "Rich opaque phase follows section ordinal, not material-list order");
        }

        private void checkRigidCardCameraAndDiagnostics(Path directory) throws IOException {
            var card = rigidCard(.002f);
            int[] first = render(card.mesh(), card, VIEW, true);
            int[] turned =
                    render(
                            card.mesh(),
                            card,
                            new StaticMeshRenderer.View(.7, .4, 1, false, Set.of()),
                            true);
            int[] reversed =
                    render(
                            card.mesh(),
                            card,
                            new StaticMeshRenderer.View(-1.1, -.5, 1, false, Set.of()),
                            true);
            for (int y : new int[] {115, 155})
                for (int x : new int[] {115, 140}) {
                    require(
                            first[y * WIDTH + x] == turned[y * WIDTH + x]
                                    && first[y * WIDTH + x] == reversed[y * WIDTH + x],
                            "Rigid card UV corners remain screen aligned around nonorigin pivot");
                }
            // Textures OFF overrides source ADD/unlit/alpha with flat neutral opaque diagnostic.
            int[] neutral = render(card.mesh(), card, VIEW, false);
            assertPixel(
                    neutral,
                    128,
                    128,
                    151,
                    140,
                    122,
                    1,
                    "Card neutral diagnostic uses eye-space legacy light");
            var depth = rigidCard(.5f);
            int[] shifted = render(depth.mesh(), depth, VIEW, true);
            int count = 0;
            for (int x = 0; x < WIDTH; x++) if (shifted[128 * WIDTH + x] != CLEAR) count++;
            // Radius = sqrt(3)*sqrt(.25^2+.5^2+.5^2), depth=radius*3.2+.5.
            // Projected full width is about34.36px; flattening offsetX gives about38.49px.
            require(
                    count >= 33 && count <= 35,
                    "Billboard local X contributes eye depth rather than being flattened");
            writeImage(directory.resolve("gpu-rich-billboard.png"), first);
            writeImage(directory.resolve("gpu-rich-billboard-neutral.png"), neutral);
        }

        @Override
        public void close() {
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
                initialized = false;
            }
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
                pixels = null;
            }
        }
    }

    private static MeshAppearance.TextureStage stage(
            int texture, MeshAppearance.Coordinate coordinate) {
        return new MeshAppearance.TextureStage(texture, coordinate, false, false);
    }

    private static MeshAppearance.Material material(
            MeshAppearance.Lighting lighting, MeshAppearance.Blend blend, int texture) {
        return new MeshAppearance.Material(
                MeshAppearance.Combiner.DIFFUSE,
                List.of(stage(texture, MeshAppearance.Coordinate.UV0)),
                lighting,
                blend);
    }

    private static MeshAppearance additivePair(int alpha, float x) {
        var mesh =
                new PreviewMesh(
                        new float[] {
                            x, -1, -1, x, 1, -1, x, 1, 1, x, -1, 1, 0, -1, -1, 0, 1, -1, 0, 1, 1, 0,
                            -1, 1
                        },
                        new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7},
                        List.of(new PreviewMesh.Part(0, 0, 6), new PreviewMesh.Part(0, 6, 6)));
        return new MeshAppearance(
                mesh,
                new float[16],
                List.of(
                        new MeshAppearance.Surface(
                                0,
                                material(
                                        MeshAppearance.Lighting.UNLIT,
                                        MeshAppearance.Blend.ADDITIVE,
                                        0)),
                        new MeshAppearance.Surface(
                                1,
                                material(
                                        MeshAppearance.Lighting.UNLIT,
                                        MeshAppearance.Blend.OPAQUE,
                                        1))),
                List.of(
                        new MeshAppearance.Texture(1, 1, new int[] {alpha << 24 | 0x0064503c}),
                        new MeshAppearance.Texture(1, 1, new int[] {0xff0a141e})));
    }

    private static MeshAppearance twoAdditiveCards() {
        var mesh =
                new PreviewMesh(
                        new float[] {
                            .3f, -1, -1, .3f, 1, -1, .3f, 1, 1, .3f, -1, 1, .1f, -1, -1, .1f, 1, -1,
                            .1f, 1, 1, .1f, -1, 1, 0, -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1
                        },
                        new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 9, 10, 8, 10, 11},
                        List.of(new PreviewMesh.Part(0, 0, 12), new PreviewMesh.Part(0, 12, 6)));
        return new MeshAppearance(
                mesh,
                new float[24],
                List.of(
                        new MeshAppearance.Surface(
                                0,
                                material(
                                        MeshAppearance.Lighting.UNLIT,
                                        MeshAppearance.Blend.ADDITIVE,
                                        0)),
                        new MeshAppearance.Surface(
                                1,
                                material(
                                        MeshAppearance.Lighting.UNLIT,
                                        MeshAppearance.Blend.OPAQUE,
                                        1))),
                List.of(
                        new MeshAppearance.Texture(1, 1, new int[] {0x4064503c}),
                        new MeshAppearance.Texture(1, 1, new int[] {0xff0a141e})));
    }

    private static MeshAppearance rigidCard(float offsetX) {
        var mesh =
                new PreviewMesh(
                        new float[] {
                            1 + offsetX,
                            1.75f,
                            2.5f,
                            1 + offsetX,
                            2.25f,
                            2.5f,
                            1 + offsetX,
                            2.25f,
                            3.5f,
                            1 + offsetX,
                            1.75f,
                            3.5f
                        },
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(0, 0, 6)));
        return new MeshAppearance(
                mesh,
                UV,
                new float[0],
                List.of(
                        new MeshAppearance.Surface(
                                0,
                                material(
                                        MeshAppearance.Lighting.UNLIT,
                                        MeshAppearance.Blend.ADDITIVE,
                                        0))),
                List.of(new MeshAppearance.Texture(2, 2, QUADRANTS)),
                List.of(
                        new MeshAppearance.RigidBillboard(
                                36, new MeshAppearance.Vec3(1, 2, 3), new int[] {0, 1, 2, 3})));
    }

    private static void assertPixel(
            int[] image, int x, int y, int r, int g, int b, int tolerance, String label) {
        int pixel = image[y * WIDTH + x];
        require(
                Math.abs(channel(pixel, 16) - r) <= tolerance
                        && Math.abs(channel(pixel, 8) - g) <= tolerance
                        && Math.abs(channel(pixel, 0) - b) <= tolerance
                        && channel(pixel, 24) == 255,
                label
                        + " expectedRGB("
                        + r
                        + ","
                        + g
                        + ","
                        + b
                        + ") actual0x"
                        + Integer.toHexString(pixel));
    }

    private static PreviewMesh quad(boolean reverse) {
        return new PreviewMesh(
                new float[] {0, -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1},
                reverse ? new int[] {0, 2, 1, 0, 3, 2} : new int[] {0, 1, 2, 0, 2, 3},
                List.of(new PreviewMesh.Part(42, 0, 6)));
    }

    private static MeshAppearance appearance(
            PreviewMesh mesh,
            float[] uv,
            float[] normals,
            int[] colors,
            boolean wrapU,
            boolean wrapV,
            boolean alphaKey) {
        return new MeshAppearance(
                mesh,
                uv,
                normals,
                List.of(new MeshAppearance.Surface(0, 0, wrapU, wrapV, alphaKey)),
                List.of(new MeshAppearance.Texture(2, 2, colors)));
    }

    private static MeshAppearance layered(int alpha, boolean cutout) {
        return layered(alpha, cutout, .2f, -.2f);
    }

    private static MeshAppearance layered(int alpha, boolean cutout, float front, float back) {
        var mesh =
                new PreviewMesh(
                        new float[] {
                            front, -1, -1, front, 1, -1, front, 1, 1, front, -1, 1,
                            back, -1, -1, back, 1, -1, back, 1, 1, back, -1, 1
                        },
                        new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7},
                        List.of(new PreviewMesh.Part(42, 0, 6), new PreviewMesh.Part(7, 6, 6)));
        return new MeshAppearance(
                mesh,
                new float[16],
                List.of(
                        new MeshAppearance.Surface(0, 0, false, false, cutout),
                        new MeshAppearance.Surface(1, 1, false, false, false)),
                List.of(
                        new MeshAppearance.Texture(1, 1, new int[] {alpha << 24 | 0x00ff0000}),
                        new MeshAppearance.Texture(1, 1, new int[] {0xff0000ff})));
    }

    private static void compareInterior(int[] actual, int[] expected, int tolerance, String label) {
        for (int y : new int[] {100, 175}) {
            for (int x : new int[] {90, 165}) {
                int at = y * WIDTH + x;
                for (int shift : new int[] {0, 8, 16, 24}) {
                    require(
                            Math.abs(channel(actual[at], shift) - channel(expected[at], shift))
                                    <= tolerance,
                            label
                                    + " at("
                                    + x
                                    + ","
                                    + y
                                    + ") shift"
                                    + shift
                                    + " expected0x"
                                    + Integer.toHexString(expected[at])
                                    + " actual0x"
                                    + Integer.toHexString(actual[at]));
                }
            }
        }
    }

    private static int channel(int pixel, int shift) {
        return pixel >>> shift & 255;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }

    private static void writeImage(Path file, int[] pixels) throws IOException {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ImageIO.write(image, "png", file.toFile());
    }
}
