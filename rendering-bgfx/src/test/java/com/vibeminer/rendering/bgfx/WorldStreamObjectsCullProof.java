package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_get_stats;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Actual GPU proof (Vulkan, or D3D11 on request; see WorldStreamNativeProof.BACKEND) of CPU
 * exterior object culling on the production native owner: one authored terrain tile with the
 * material proof's late objects (three camera-facing quads in placement 0, then the tile filled to
 * the placement cap behind the camera). With culling on, only placement 0 is drawn; with it off,
 * every placement is. Both frames must have identical pixels, and the bgfx draw counts must drop by
 * the culled placements' submits. Authored data only.
 */
final class WorldStreamObjectsCullProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(321, -654);
    private static final int WIDTH = 800, HEIGHT = 500;
    private static long camera, sequence;
    private static int farFogPixel;

    private WorldStreamObjectsCullProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    record Result(int drawsCulled, int drawsAll, BgfxWorldStreamRenderer.FrameCounts culled) {}

    @SuppressWarnings("try")
    static Result run(Path base) throws Exception {
        Path directory = Files.createDirectories(base.resolve("objects-cull"));
        var session = new WorldStreamEnvelope.Session(NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        var exterior = WorldStreamObjectsMaterialProof.quads();
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-cull-pack").factory())) {
            tile =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    NONCE, directory, 1, lease, 7, 19, 120, true))
                            .get(20, TimeUnit.SECONDS);
            objects =
                    worker.submit(() -> WorldStreamPackedObjects.pack(exterior.data(), () -> false))
                            .get(20, TimeUnit.SECONDS);
        }
        Path mapping = directory.resolve("frames-cull");
        Result result;
        try (var frames = WorldFrameFileV2.create(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var reader = WorldFrameFileV2.open(mapping, NONCE, WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session, frames, WorldStreamNativeProof.BACKEND)) {
            var upload = owner.begin(tile);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!upload.finished()) {
                owner.step(upload);
                owner.advance();
                require(System.nanoTime() < deadline, "Terrain upload did not drain");
            }
            require(upload.successful(), "Terrain upload failed");
            owner.commit(0, 1, List.of(lease));
            var late = owner.beginLate(lease, objects);
            while (!late.ready()) {
                owner.stepLate(late);
                owner.advance();
                require(System.nanoTime() < deadline, "Late objects did not build");
            }
            require(
                    owner.finishLate(late, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "Late objects attach");
            var culledFrame = frame(owner, reader, NONCE, WorldStreamObjectsMaterialProof.VIEW);
            int drawsCulled = culledFrame.draws;
            var culled = owner.frameCounts();
            owner.objectCulling(false);
            var allFrame = frame(owner, reader, NONCE, WorldStreamObjectsMaterialProof.VIEW);
            int drawsAll = allFrame.draws;
            var all = owner.frameCounts();
            owner.objectCulling(true);
            int placements = exterior.placements();
            require(
                    culled.placements() == placements && all.placements() == placements,
                    "Placements tested " + culled + " " + all);
            // Placement 0 draws its cutout and opaque ranges, then its blended one; the rest are
            // behind the camera.
            require(
                    culled.culled() == placements - 1 && culled.objectSubmits() == 3,
                    "Culled counts " + culled);
            require(
                    all.culled() == 0 && all.objectSubmits() == 3 * placements,
                    "Unculled counts " + all);
            // Terrain eye, projection and rebase plus object eye and projection: once per frame,
            // whatever the number of slots and placements drawn.
            require(
                    culled.cameraUniforms() == 5 && all.cameraUniforms() == 5,
                    "Camera uniforms per frame " + culled + " " + all);
            require(
                    drawsAll - drawsCulled == all.objectSubmits() - culled.objectSubmits(),
                    "bgfx draw counts " + drawsCulled + " " + drawsAll);
            var a = culledFrame.pixels;
            var b = allFrame.pixels;
            int differing = 0;
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                if (a.get(i) != b.get(i)) {
                    differing++;
                }
            }
            require(differing == 0, "Culling changed " + differing + " pixels");
            // The opaque quad in the middle is drawn (material proof colour), so the comparison is
            // not of two empty frames.
            int middle = a.get(HEIGHT / 2 * WIDTH + 400);
            int red = Math.max(middle >>> 16 & 255, middle & 255);
            require(
                    Math.abs(red - 200) <= 1 && Math.abs((middle >>> 8 & 255) - 40) <= 1,
                    "Visible placement drawn " + Integer.toHexString(middle));
            // The sky, drawn after the opaque surfaces with GEQUAL at the cleared depth, fills the
            // uncovered top-left pixel (terrain ends 20 yd ahead) with the horizon gradient colour
            // of its view ray, as WorldFog computes it.
            int sky = a.get(0);
            var expected =
                    WorldFog.STREAM.skyPixel(
                            WorldStreamObjectsMaterialProof.VIEW.pitch(),
                            TerrainCamera.focalPixels(WIDTH),
                            WIDTH,
                            HEIGHT,
                            0,
                            0);
            int rgb =
                    (int) Math.floor(expected[0]) << 16
                            | (int) Math.floor(expected[1]) << 8
                            | (int) Math.floor(expected[2]);
            int bgr = (rgb & 255) << 16 | rgb & 0xff00 | rgb >>> 16;
            require(
                    (sky & 0xffffff) == rgb || (sky & 0xffffff) == bgr,
                    "Sky top row " + Integer.toHexString(sky) + " != " + Integer.toHexString(rgb));
            // MSAA resolve: moving the camera 0.01 yd west puts the opaque quad's left edge (eye
            // right -0.9 at depth 5) at x = 400 + focal * -0.89 / 5 = 328.8 for the 90 degree
            // field of view, so pixel 328 is partly covered. Single-sampled, its centre misses the
            // quad and it is the gap's pixel (324); resolved x4 it mixes the gap and quad red.
            var shifted =
                    new TerrainCamera(
                            VIEW.x(), VIEW.y() + 0.01, VIEW.z(), VIEW.yaw(), VIEW.pitch());
            var edgeFrame = frame(owner, reader, NONCE, shifted).pixels;
            double edgeX = WIDTH / 2.0 + TerrainCamera.focalPixels(WIDTH) * -0.89 / 5;
            int edgePixel = (int) Math.floor(edgeX);
            require(edgeX - edgePixel > 0.6 && edgeX - edgePixel < 0.95, "Edge position " + edgeX);
            int samples = owner.stats().msaaSamples();
            int row = HEIGHT / 2;
            int edge = channel(edgeFrame.get(row * WIDTH + edgePixel), red16(middle));
            int gap = channel(edgeFrame.get(row * WIDTH + edgePixel - 4), red16(middle));
            int inside = channel(edgeFrame.get(row * WIDTH + edgePixel + 6), red16(middle));
            if (samples == 4) {
                require(
                        edge > Math.min(gap, inside) + 8 && edge < Math.max(gap, inside) - 8,
                        "Resolved MSAA edge " + gap + " " + edge + " " + inside);
            } else {
                require(samples == 1 && Math.abs(edge - gap) <= 1, "Single-sampled edge");
            }
            System.out.println(
                    "WORLD_OBJECT_CULL_PASS samples="
                            + samples
                            + " edgeRed="
                            + gap
                            + "/"
                            + edge
                            + "/"
                            + inside
                            + " placements="
                            + placements
                            + " culled="
                            + culled.culled()
                            + " objectSubmits="
                            + culled.objectSubmits()
                            + "/"
                            + all.objectSubmits()
                            + " cameraUniforms="
                            + all.cameraUniforms()
                            + " bgfxDraws="
                            + drawsCulled
                            + "/"
                            + drawsAll);
            result = new Result(drawsCulled, drawsAll, culled);
        }
        int partial = coverage(base);
        System.out.println(
                "WORLD_OBJECT_COVERAGE_PASS partialEdgePixels="
                        + partial
                        + " fogAt2190="
                        + Integer.toHexString(farFogPixel));
        return result;
    }

    private static final WorldStreamState.Nonce COVERAGE_NONCE =
            new WorldStreamState.Nonce(987, -321);

    /** Camera of the coverage quad: level, looking along native +X. */
    static final TerrainCamera COVERAGE_VIEW = new TerrainCamera(-20, -2, 20, 0, 0);

    /**
     * One cutout quad at eye depth 5 in front of {@link #COVERAGE_VIEW}, eye-right [-1.5, 1.5],
     * eye-up [-1, 1], yellow at full light. Its 2 x 2 image is opaque in the left texels and
     * transparent in the right ones; texture u runs between the texel centres along right + up / 2,
     * so the bilinear alpha falls through the 0.5 reference on a slanted line across the quad.
     */
    static WorldStreamTileWriter.Exterior coverageQuad() {
        var builder = WorldStreamTileWriter.Exterior.builder();
        var pixels = java.nio.ByteBuffer.allocate(16);
        for (int i = 0; i < 4; i++) {
            pixels.put((byte) 250).put((byte) 250).put((byte) 20);
            pixels.put((byte) (i % 2 == 0 ? 255 : 0));
        }
        builder.image(2, 2, WorldStreamObjectsFileTest.chain(2, 2, pixels.flip()));
        // Model 0 at eye depth 5; model 1, the same quad scaled to eye depth FAR_FOG_DEPTH and
        // raised to the frame's top rows, keeping its angular size (placement scales are capped).
        for (int model = 0; model < 2; model++) {
            double k = model == 0 ? 1 : FAR_FOG_DEPTH / 5;
            builder.model();
            var vertices = java.nio.FloatBuffer.allocate(4 * 8);
            double[][] corners = {{-1.5, -1}, {1.5, -1}, {1.5, 1}, {-1.5, 1}};
            for (var corner : corners) {
                double right = corner[0], up = corner[1];
                // Level camera at yaw 0: depth is +x, right is -y, up is +z.
                vertices.put((float) (5 * k)).put((float) (-right * k));
                vertices.put((float) ((up + (model == 0 ? 0 : 1.5)) * k));
                vertices.put(-0.408248290463863f).put(-0.408248290463863f);
                vertices.put(0.816496580927726f);
                vertices.put((float) (0.5 + (right + up / 2) / 8)).put(.5f);
            }
            vertices.flip();
            var indices = java.nio.IntBuffer.allocate(6).put(new int[] {0, 1, 2, 0, 2, 3}).flip();
            builder.mesh(
                    vertices,
                    indices,
                    new int[] {0, 6, 0},
                    new int[] {WorldStreamObjectsFile.CUTOUT});
            builder.placement(
                    model,
                    COVERAGE_VIEW.x(),
                    COVERAGE_VIEW.y(),
                    COVERAGE_VIEW.z(),
                    1,
                    new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        }
        return builder.build();
    }

    /** Eye depth of the fogged far quad: 10 yd inside the 2200 yd cut. */
    static final double FAR_FOG_DEPTH = 2190;

    /**
     * A fresh context drawing only {@link #coverageQuad}. Multisampled, cutouts use alpha to
     * coverage, so the quad's alpha edge resolves with pixels that mix yellow and the background;
     * single-sampled, the plain alpha test leaves none. Returns the count of such pixels.
     */
    @SuppressWarnings("try")
    static int coverage(Path base) throws Exception {
        Path directory = Files.createDirectories(base.resolve("objects-coverage"));
        var session =
                new WorldStreamEnvelope.Session(
                        COVERAGE_NONCE, WorldStreamNativeProof.SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        WorldStreamPackedTile tile;
        WorldStreamPackedObjects objects;
        try (var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().name("world-coverage-pack").factory())) {
            tile =
                    worker.submit(
                                    () ->
                                            WorldStreamNativeProof.prepare(
                                                    COVERAGE_NONCE,
                                                    directory,
                                                    1,
                                                    lease,
                                                    7,
                                                    19,
                                                    120,
                                                    true))
                            .get(20, TimeUnit.SECONDS);
            var quad = coverageQuad();
            objects =
                    worker.submit(() -> WorldStreamPackedObjects.pack(quad.data(), () -> false))
                            .get(20, TimeUnit.SECONDS);
        }
        Path mapping = directory.resolve("frames-coverage");
        sequence = 0;
        try (var frames =
                        WorldFrameFileV2.create(
                                mapping, COVERAGE_NONCE, WorldStreamNativeProof.SCOPE);
                var reader =
                        WorldFrameFileV2.open(
                                mapping, COVERAGE_NONCE, WorldStreamNativeProof.SCOPE);
                var owner =
                        new WorldStreamNativeOwner(
                                session, frames, WorldStreamNativeProof.BACKEND)) {
            var upload = owner.begin(tile);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!upload.finished()) {
                owner.step(upload);
                owner.advance();
                require(System.nanoTime() < deadline, "Terrain upload did not drain");
            }
            owner.commit(0, 1, List.of(lease));
            var late = owner.beginLate(lease, objects);
            while (!late.ready()) {
                owner.stepLate(late);
                owner.advance();
                require(System.nanoTime() < deadline, "Late objects did not build");
            }
            require(
                    owner.finishLate(late, true) == WorldStreamStatus.LateOutcome.ATTACHED,
                    "Coverage quad attach");
            var pixels = frame(owner, reader, COVERAGE_NONCE, COVERAGE_VIEW).pixels;
            int samples = owner.stats().msaaSamples();
            int yellow = 0, partial = 0;
            for (int y = 200; y < 300; y++) {
                // Left of the quad (it spans x 280..520) is the background of this row.
                int background = pixels.get(y * WIDTH + 150) >>> 8 & 255;
                for (int x = 300; x < 500; x++) {
                    int pixel = pixels.get(y * WIDTH + x);
                    int r = pixel >>> 16 & 255, g = pixel >>> 8 & 255, b = pixel & 255;
                    if (g >= 249 && Math.max(r, b) >= 249 && Math.min(r, b) <= 21) {
                        yellow++;
                    } else if (g > background + 12 && g < 240) {
                        partial++;
                    }
                }
            }
            require(yellow > 2000, "Coverage quad drawn: " + yellow);
            require(
                    samples > 1 ? partial >= 50 : partial == 0,
                    "Cutout edge pixels " + partial + " at " + samples + " samples");
            // Fog near the cut, above the horizon. The far quad (rows 50..210, x 280..520) is at
            // 2190 yd; its opaque part at pixel (300, 100) fades almost fully toward the sky at
            // its own elevation, as WorldFog computes it, so it matches the sky beside it (pixel
            // 540, 100) and the sky there is a mid-gradient colour (0 < t < 1).
            double focal = TerrainCamera.focalPixels(WIDTH);
            int fx = 300, fy = 100, sx = 540;
            double rightEye = (fx + 0.5 - WIDTH * 0.5) / focal;
            double upEye = (HEIGHT * 0.5 - (fy + 0.5)) / focal;
            float fog = WorldFog.STREAM.factor(FAR_FOG_DEPTH);
            float[] fogSky =
                    WorldFog.STREAM.sky(
                            WorldFog.elevation(
                                    FAR_FOG_DEPTH,
                                    -rightEye * FAR_FOG_DEPTH,
                                    upEye * FAR_FOG_DEPTH));
            int far = pixels.get(fy * WIDTH + fx);
            int besideSky = pixels.get(fy * WIDTH + sx);
            int[] quadColour = {250, 250, 20};
            int[] expected = new int[3];
            for (int c = 0; c < 3; c++) {
                expected[c] = (int) Math.floor(quadColour[c] * (1 - fog) + fogSky[c] * fog);
            }
            float[] skyExpected =
                    WorldFog.STREAM.skyPixel(COVERAGE_VIEW.pitch(), focal, WIDTH, HEIGHT, sx, fy);
            double t =
                    (skyExpected[0] - WorldFog.FOG_COLOUR[0])
                            / (WorldFog.SKY_TOP[0] - WorldFog.FOG_COLOUR[0]);
            int[] got = rgb(far, expected[0]), sky = rgb(besideSky, (int) skyExpected[0]);
            boolean fogged = true, skyMatches = true, seamless = true;
            for (int c = 0; c < 3; c++) {
                fogged &= Math.abs(got[c] - expected[c]) <= 1;
                skyMatches &= Math.abs(sky[c] - (int) Math.floor(skyExpected[c])) <= 1;
                seamless &= Math.abs(got[c] - sky[c]) <= 3;
            }
            require(
                    fog > 0.99 && t > 0.2 && t < 0.8 && fogged && skyMatches && seamless,
                    "Fog at "
                            + FAR_FOG_DEPTH
                            + " yd: "
                            + Integer.toHexString(far)
                            + " expected "
                            + java.util.Arrays.toString(expected)
                            + ", sky beside "
                            + Integer.toHexString(besideSky)
                            + " expected "
                            + java.util.Arrays.toString(skyExpected)
                            + " t="
                            + t);
            farFogPixel = far;
            return partial;
        }
    }

    /** A pixel's red, green, blue, in whichever channel order puts red nearest {@code red}. */
    private static int[] rgb(int pixel, int red) {
        int hi = pixel >>> 16 & 255, g = pixel >>> 8 & 255, lo = pixel & 255;
        return Math.abs(hi - red) <= Math.abs(lo - red)
                ? new int[] {hi, g, lo}
                : new int[] {lo, g, hi};
    }

    /** The shift of the red channel, from a pixel known to be the opaque quad's red. */
    private static int red16(int quad) {
        return Math.abs((quad >>> 16 & 255) - 200) <= 1 ? 16 : 0;
    }

    private static int channel(int pixel, int shift) {
        return pixel >>> shift & 255;
    }

    private static final TerrainCamera VIEW = WorldStreamObjectsMaterialProof.VIEW;

    private record Drawn(int draws, IntBuffer pixels) {}

    /** Submits one new camera revision and returns its bgfx draw count and published pixels. */
    private static Drawn frame(
            WorldStreamNativeOwner owner,
            WorldFrameFileV2 reader,
            WorldStreamState.Nonce nonce,
            TerrainCamera view)
            throws Exception {
        long revision = ++camera;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var command = new WorldStreamControl.Camera(nonce, revision, 1, WIDTH, HEIGHT, view);
        while (!owner.submit(command)) {
            owner.advance();
            require(System.nanoTime() < deadline, "Camera submit");
        }
        // bgfx double-buffers its frames: the stats of the frame rendered by this advance are
        // reported once the next frame has been swapped in.
        owner.advance();
        owner.advance();
        int draws = bgfx_get_stats().numDraw();
        while (System.nanoTime() < deadline) {
            var frame =
                    reader.readLatest(
                            sequence,
                            new WorldFrameFileV2.Barrier(
                                    1, WIDTH, HEIGHT, revision, revision, 1, 1));
            if (frame != null) {
                sequence = frame.metadata().sequence();
                var pixels = IntBuffer.allocate(WIDTH * HEIGHT);
                pixels.put(frame.pixels()).flip();
                return new Drawn(draws, pixels);
            }
            owner.advance();
        }
        throw new AssertionError("No frame for camera " + revision);
    }
}
