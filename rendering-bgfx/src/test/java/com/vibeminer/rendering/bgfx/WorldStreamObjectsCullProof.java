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
 * material proof's late objects (three camera-facing quads in placement 0, the layout ladders in
 * placement 1, then the tile filled to the placement cap behind the camera). With culling on, only
 * placements 0 and 1 are drawn; with it off, every placement is. Both frames must have identical
 * pixels, and the bgfx draw counts must drop by the culled placements' submits. Authored data only.
 */
final class WorldStreamObjectsCullProof {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(321, -654);
    private static final int WIDTH = 800, HEIGHT = 500;
    private static long camera, sequence;

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
            var culledFrame = frame(owner, reader);
            int drawsCulled = culledFrame.draws;
            var culled = owner.frameCounts();
            owner.objectCulling(false);
            var allFrame = frame(owner, reader);
            int drawsAll = allFrame.draws;
            var all = owner.frameCounts();
            owner.objectCulling(true);
            int placements = exterior.placements();
            require(
                    culled.placements() == placements && all.placements() == placements,
                    "Placements tested " + culled + " " + all);
            // Placement 0 draws its cutout and opaque ranges, then its blended one; placement 1 is
            // the layout ladders (#374), one opaque range per level; the rest are quads behind the
            // camera.
            int ladders = WorldStreamObjectsMaterialProof.ladderQuads();
            require(
                    culled.culled() == placements - 2 && culled.objectSubmits() == 3 + ladders,
                    "Culled counts " + culled);
            require(
                    all.culled() == 0 && all.objectSubmits() == 3 * (placements - 1) + ladders,
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
                    Math.abs(red - WorldStreamObjectsMaterialProof.OPAQUE_R) <= 1
                            && Math.abs(
                                            (middle >>> 8 & 255)
                                                    - WorldStreamObjectsMaterialProof.OPAQUE_G)
                                    <= 1,
                    "Visible placement drawn " + Integer.toHexString(middle));
            // The sky, drawn after the opaque surfaces with GEQUAL at the cleared depth, still
            // fills the uncovered top row with its row-0 colour (terrain ends 20 yd ahead).
            int sky = a.get(0);
            require(
                    (sky & 0xffffff) == 0x5b8db1 || (sky & 0xffffff) == 0xb18d5b,
                    "Sky top row " + Integer.toHexString(sky));
            // MSAA resolve: moving the camera 0.01 yd west puts the opaque quad's left edge (eye
            // right -0.9 at depth 5) at x = 400 + focal * -0.89 / 5 = 328.8 for the 90 degree
            // field of view, so pixel 328 is partly covered. Single-sampled, its centre misses the
            // quad and it is the gap's pixel (324); resolved x4 it mixes the gap and quad red.
            var shifted =
                    new TerrainCamera(
                            VIEW.x(), VIEW.y() + 0.01, VIEW.z(), VIEW.yaw(), VIEW.pitch());
            var edgeFrame = frame(owner, reader, shifted).pixels;
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
            return new Result(drawsCulled, drawsAll, culled);
        }
    }

    /** The shift of the red channel, from a pixel known to be the opaque quad's red. */
    private static int red16(int quad) {
        return Math.abs((quad >>> 16 & 255) - 200) <= 1 ? 16 : 0;
    }

    private static int channel(int pixel, int shift) {
        return pixel >>> shift & 255;
    }

    private record Drawn(int draws, IntBuffer pixels) {}

    /** Submits one new camera revision and returns its bgfx draw count and published pixels. */
    private static Drawn frame(WorldStreamNativeOwner owner, WorldFrameFileV2 reader)
            throws Exception {
        return frame(owner, reader, VIEW);
    }

    private static final TerrainCamera VIEW = WorldStreamObjectsMaterialProof.VIEW;

    private static Drawn frame(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, TerrainCamera view)
            throws Exception {
        long revision = ++camera;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var command = new WorldStreamControl.Camera(NONCE, revision, 1, WIDTH, HEIGHT, view);
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
