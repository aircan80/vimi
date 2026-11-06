package com.vibeminer.rendering.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Authored real Vulkan/native/mmap proof only. No user data, window, pipe or GUI integration. */
public final class WorldStreamNativeProof {
    static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(123, -456);
    static final String SCOPE = "cd".repeat(32);
    static final WorldStreamEnvelope.Session SESSION =
            new WorldStreamEnvelope.Session(NONCE, SCOPE, 1);
    private static final TerrainCamera CAMERA = new TerrainCamera(-100, -10, 50, 0, -.6);
    private static final int WIDTH = 256, HEIGHT = 256;

    /**
     * The proof backend: Vulkan unless the {@code vimi.worldProof.backend} system property is
     * {@code d3d11} (Gradle: {@code -PworldProofBackend=d3d11}).
     */
    static final WorldStreamTransport.Backend PROOF_BACKEND =
            proofBackend(System.getProperty("vimi.worldProof.backend", "vulkan"));

    /** The bgfx renderer type of {@link #PROOF_BACKEND}, for in-process native owners. */
    static final int BACKEND =
            PROOF_BACKEND == WorldStreamTransport.Backend.D3D11
                    ? BGFX_RENDERER_TYPE_DIRECT3D11
                    : BGFX_RENDERER_TYPE_VULKAN;

    /** The one parser of the proof backend property, shared by every World GPU proof. */
    static WorldStreamTransport.Backend proofBackend(String name) {
        return switch (name) {
            case "vulkan" -> WorldStreamTransport.Backend.VULKAN;
            case "d3d11" -> WorldStreamTransport.Backend.D3D11;
            default -> throw new IllegalArgumentException("Unknown proof backend " + name);
        };
    }

    static long camera = 1, sequence;
    private static int framesDuringUploads;

    /**
     * Packed input of one fixture tile: 65536 terrain triangles without water plus the atlas mip
     * chain (4,543,828 bytes). The worst case with water is
     * WorldStreamPackedTile.MAX_PAYLOAD_BYTES.
     */
    static final long TERRAIN_FIXTURE_BYTES =
            65536L * WorldStreamPackedTile.INSTANCE_BYTES + WorldStreamAtlasMips.CHAIN_BYTES;

    private WorldStreamNativeProof() {}

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static TerrainScene.Tile tile(int x, int y) {
        var base = WorldStreamTileFileTest.full(false);
        var vertices = new float[base.vertexCount() * 3];
        base.vertices().get(vertices);
        int[] indices = new int[196608];
        int at = 0;
        for (int chunk = 0; chunk < 256; chunk++) {
            int start = chunk * 145;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int a = start + row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
                    for (int index : new int[] {a, m, b, b, m, e, e, m, d, d, m, a}) {
                        indices[at++] = index;
                    }
                }
            }
        }
        return TerrainScene.Tile.copyOf(x, y, vertices, indices, 256);
    }

    private static WorldStreamTileFile.Source structural(long id) {
        return new WorldStreamTileFile.Source(
                id, String.format("%032x", id), 128, 128L, 1 << 20, 0, 0, 0, null, 0, 0);
    }

    static WorldStreamPackedTile prepare(
            Path directory,
            long tx,
            WorldStreamState.Lease lease,
            int x,
            int y,
            int red,
            boolean textured)
            throws Exception {
        return prepare(NONCE, directory, tx, lease, x, y, red, textured);
    }

    static WorldStreamPackedTile prepare(
            WorldStreamState.Nonce nonce,
            Path directory,
            long tx,
            WorldStreamState.Lease lease,
            int x,
            int y,
            int red,
            boolean textured)
            throws Exception {
        require(
                !Thread.currentThread().getName().equals("main"),
                "Packing must be off native owner");
        var tile = tile(x, y);
        var diffuse =
                new WorldStreamTileFile.Source(
                        4,
                        "04".repeat(16),
                        128,
                        null,
                        1 << 20,
                        256,
                        6,
                        1,
                        WorldStreamTileFile.Encoding.BC1,
                        0,
                        0);
        var header =
                new WorldStreamTileFile.Header(
                        nonce,
                        SCOPE,
                        tx,
                        lease,
                        x,
                        y,
                        256,
                        tile.vertexCount(),
                        tile.indexCount(),
                        TerrainGeometryFingerprint.sha256(tile),
                        textured ? "ef".repeat(32) : null,
                        textured
                                ? WorldStreamTileFile.Outcome.TEXTURED
                                : WorldStreamTileFile.Outcome.HEIGHT_FALLBACK,
                        textured
                                ? WorldStreamTileFile.Reason.NONE
                                : WorldStreamTileFile.Reason.TERMINAL_MATERIAL_UNAVAILABLE,
                        textured ? new long[] {-1, -1, -1, -1} : new long[4],
                        0,
                        0,
                        textured ? 1.0 / 32 : 0,
                        structural(1),
                        structural(2),
                        textured ? structural(3) : null,
                        textured ? List.of(diffuse) : List.of());
        ByteBuffer atlas = null;
        if (textured) {
            atlas = ByteBuffer.allocate(1 << 20);
            for (int i = 0; i < 512 * 512; i++) {
                atlas.put((byte) red).put((byte) 20).put((byte) 30).put((byte) 255);
            }
            atlas.flip();
            atlas = atlas.asReadOnlyBuffer();
        }
        var receipt = WorldStreamTileFile.write(directory, header, tile, atlas, () -> false);
        var input =
                WorldStreamTileFile.read(
                        directory.resolve("tile-" + tx + ".wst"), receipt, () -> false);
        return WorldStreamPackedTile.prepare(input, () -> false);
    }

    static CompletableFuture<WorldStreamPackedTile> prepare(
            ExecutorService worker,
            Path path,
            long tx,
            WorldStreamState.Lease lease,
            int x,
            int y,
            int red,
            boolean textured) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return prepare(path, tx, lease, x, y, red, textured);
                    } catch (Exception failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                },
                worker);
    }

    /** Submits once in-flight readbacks have retired (a resize waits for them), bounded. */
    private static boolean submitted(
            WorldStreamNativeOwner owner, WorldStreamControl.Camera command) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (owner.submit(command)) {
                return true;
            }
            owner.advance();
        }
        return false;
    }

    private static WorldFrameFileV2.Frame frameAt(
            WorldStreamNativeOwner owner,
            WorldFrameFileV2 reader,
            long policy,
            int width,
            int height,
            long camera,
            long content)
            throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            owner.advance();
            var frame =
                    reader.readLatest(
                            0,
                            new WorldFrameFileV2.Barrier(
                                    policy, width, height, camera, camera, content, content));
            if (frame != null) {
                return frame;
            }
        }
        throw new AssertionError("No " + width + "x" + height + " frame");
    }

    static WorldStreamControl.Camera command(long revision, int width, int height, long policy) {
        return new WorldStreamControl.Camera(NONCE, revision, policy, width, height, CAMERA);
    }

    static WorldFrameFileV2.Frame read(WorldFrameFileV2 reader, long content, long floor)
            throws Exception {
        var frame =
                reader.readLatest(
                        sequence,
                        new WorldFrameFileV2.Barrier(
                                1, WIDTH, HEIGHT, 1, Long.MAX_VALUE, floor, content));
        if (frame != null) {
            sequence = frame.metadata().sequence();
        }
        return frame;
    }

    static void upload(
            WorldStreamNativeOwner owner,
            WorldStreamPackedTile packed,
            WorldFrameFileV2 reader,
            long content,
            boolean cancel)
            throws Exception {
        var upload = owner.begin(packed);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int steps = 0;
        while (!upload.finished()) {
            int bytes = owner.step(upload);
            owner.submit(command(++camera, WIDTH, HEIGHT, 1));
            require(bytes <= 262144, "Upload step bytes");
            owner.advance();
            var frame = read(reader, content, 0);
            if (frame != null) {
                framesDuringUploads++;
                require(frame.metadata().content() <= content, "Uncommitted content label");
            }
            if (cancel && ++steps == 2) {
                owner.cancel(upload);
            }
            require(System.nanoTime() < deadline, "Upload callbacks failed to drain");
        }
        require(upload.successful() != cancel, "Upload terminal state");
    }

    static WorldFrameFileV2.Frame accepted(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, long content) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // Reuse the same camera revision after COMMIT: standing-still content must produce a frame.
        owner.submit(command(camera, WIDTH, HEIGHT, 1));
        while (System.nanoTime() < deadline) {
            owner.advance();
            var frame = read(reader, content, content);
            if (frame != null) {
                require(frame.metadata().camera() == camera, "Camera ticket changed");
                return frame;
            }
        }
        throw new AssertionError("No accepted committed content frame");
    }

    private static void color(WorldFrameFileV2.Frame frame, int red) {
        int pixel = frame.pixels().get(128 * WIDTH + 128);
        require(
                Math.abs((pixel >>> 16 & 255) - red) <= 1
                        && Math.abs((pixel >>> 8 & 255) - 20) <= 1
                        && Math.abs((pixel & 255) - 30) <= 1
                        && pixel >>> 24 == 255,
                "Committed pixels " + Integer.toHexString(pixel));
    }

    private static void geographicSides(
            WorldStreamNativeOwner owner, WorldFrameFileV2 reader, Path directory)
            throws Exception {
        // A full native tile spans X/Y [-533.333,0]. Look north from its east and west sides.
        // Independent geometry bounds, not camera projection helpers, determine its screen side.
        for (boolean eastSide : new boolean[] {true, false}) {
            var view = new TerrainCamera(-600, eastSide ? -600 : 67, 150, 0, -.3);
            owner.submit(new WorldStreamControl.Camera(NONCE, ++camera, 1, WIDTH, HEIGHT, view));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            WorldFrameFileV2.Frame frame = null;
            while (System.nanoTime() < deadline) {
                owner.advance();
                var candidate = read(reader, 1, 1);
                if (candidate != null && candidate.metadata().camera() == camera) {
                    frame = candidate;
                    break;
                }
            }
            require(frame != null, "Geographic side camera publication");
            int left = 0, right = 0;
            int[] pixels = new int[WIDTH * HEIGHT];
            frame.pixels().get(pixels);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int argb = pixels[y * WIDTH + x];
                    if (Math.abs((argb >>> 16 & 255) - 180) <= 1
                            && Math.abs((argb >>> 8 & 255) - 20) <= 1
                            && Math.abs((argb & 255) - 30) <= 1) {
                        if (x < WIDTH / 2) {
                            left++;
                        } else {
                            right++;
                        }
                    }
                }
            }
            require(
                    eastSide ? left > 1000 && right == 0 : right > 1000 && left == 0,
                    "Actual streaming geographic side: east="
                            + eastSide
                            + " left="
                            + left
                            + " right="
                            + right);
            var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
            ImageIO.write(
                    image,
                    "png",
                    directory
                            .resolve(eastSide ? "north-from-east.png" : "north-from-west.png")
                            .toFile());
        }
        owner.submit(command(++camera, WIDTH, HEIGHT, 1));
        color(accepted(owner, reader, 1), 180);
    }

    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path mapping = directory.resolve("frames-v2");
        Files.deleteIfExists(mapping);
        long pid = ProcessHandle.current().pid();
        try (var worker =
                        Executors.newSingleThreadExecutor(
                                Thread.ofPlatform().daemon().name("world-proof-pack").factory());
                var frames = WorldFrameFileV2.create(mapping, NONCE, SCOPE);
                var reader = WorldFrameFileV2.open(mapping, NONCE, SCOPE)) {
            var first =
                    prepare(
                                    worker,
                                    directory,
                                    1,
                                    new WorldStreamState.Lease(0, 1, 1),
                                    7,
                                    19,
                                    180,
                                    true)
                            .get(20, TimeUnit.SECONDS);
            try (var owner = new WorldStreamNativeOwner(SESSION, frames, BACKEND)) {
                upload(owner, first, reader, 0, false);
                var active = new ArrayList<WorldStreamState.Lease>();
                active.add(new WorldStreamState.Lease(0, 1, 1));
                owner.commit(0, 1, active);
                var frame = accepted(owner, reader, 1);
                color(frame, 180);
                var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[WIDTH * HEIGHT];
                frame.pixels().get(pixels);
                image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
                ImageIO.write(image, "png", directory.resolve("world-stream-native.png").toFile());
                geographicSides(owner, reader, directory);
                var reusedLease = new WorldStreamState.Lease(0, 2, 2);
                var reused =
                        prepare(worker, directory, 2, reusedLease, 7, 19, 60, true)
                                .get(20, TimeUnit.SECONDS);
                long oldCamera = ++camera;
                require(owner.submit(command(oldCamera, WIDTH, HEIGHT, 1)), "Old draw queued");
                owner.commit(1, 2, List.of());
                var reusedUpload = owner.begin(reused);
                require(owner.step(reusedUpload) == 0, "GPU mutation after queued draw");
                require(reusedUpload.issuedReferences() == 0, "Deferred step issued a reference");
                owner.advance();
                WorldFrameFileV2.Frame oldFrame = read(reader, 1, 1);
                long reuseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!reusedUpload.finished() || oldFrame == null) {
                    if (!reusedUpload.finished()) {
                        owner.step(reusedUpload);
                    }
                    owner.advance();
                    var candidate = read(reader, 1, 1);
                    if (candidate != null && candidate.metadata().camera() == oldCamera) {
                        oldFrame = candidate;
                    }
                    require(
                            System.nanoTime() < reuseDeadline,
                            "Reused slot/readback did not drain");
                }
                require(oldFrame.metadata().camera() == oldCamera, "Old ticket provenance");
                color(oldFrame, 180);
                require(reusedUpload.issuedReferences() > 0, "Deferred upload never resumed");
                owner.commit(2, 3, List.of(reusedLease));
                color(accepted(owner, reader, 3), 60);
                long content = 3, tx = 3;
                int incoming = 1;
                for (int cycle = 0; cycle < 104; cycle++) {
                    var lease = new WorldStreamState.Lease(incoming, cycle + 2, cycle + 2);
                    var packed =
                            prepare(worker, directory, tx++, lease, 7, 19, 80 + cycle % 100, true);
                    while (!packed.isDone()) {
                        owner.submit(command(++camera, WIDTH, HEIGHT, 1));
                        owner.advance();
                        read(reader, content, 0);
                    }
                    boolean cancel = cycle % 7 == 0;
                    upload(owner, packed.get(20, TimeUnit.SECONDS), reader, content, cancel);
                    if (!cancel) {
                        owner.commit(content, content + 1, List.of(lease));
                        content++;
                        frame = accepted(owner, reader, content);
                        color(frame, 80 + cycle % 100);
                        incoming = incoming == 0 ? 1 : 0;
                    }
                    var stats = owner.stats();
                    require(
                            stats.dynamicBuffers() == SESSION.slots()
                                    && stats.backingBuffers()
                                            == stats.contextBuffers() + SESSION.slots() + 1,
                            "Fixed native buffer counts " + stats);
                    require(
                            stats.textures() == stats.contextTextures() + SESSION.slots() + 5,
                            "One atlas per slot plus five fixed target/readbacks");
                    require(
                            stats.upload().references() == 0
                                    && stats.upload().liveUploadBytes() == 0,
                            "Inputs not drained");
                    require(
                            stats.upload().peakUploadBytes() == TERRAIN_FIXTURE_BYTES
                                    && stats.upload().peakUploadBytes()
                                            <= WorldStreamPackedTile.MAX_PAYLOAD_BYTES,
                            "Producer buffer bound " + stats.upload().peakUploadBytes());
                    require(ProcessHandle.current().pid() == pid, "Unexpected process replacement");
                }
                long drawnCamera = ++camera;
                int allocations = owner.reallocations();
                int samplesBefore = owner.stats().msaaSamples();
                require(
                        submitted(owner, command(drawnCamera, 800, 500, 2)),
                        "Resize submit after in-flight readbacks retire");
                require(
                        owner.reallocations() == allocations + 1
                                && owner.targetWidth() == 1024
                                && owner.targetHeight() == 512,
                        "Grown target in 256 px steps: "
                                + owner.targetWidth()
                                + "x"
                                + owner.targetHeight());
                require(
                        !owner.submit(command(++camera, 800, 500, 2)),
                        "Two draws in one bgfx frame");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                WorldFrameFileV2.Frame resized = null;
                while (resized == null && System.nanoTime() < deadline) {
                    owner.advance();
                    resized =
                            reader.readLatest(
                                    sequence,
                                    new WorldFrameFileV2.Barrier(
                                            2, 800, 500, drawnCamera, camera, content, content));
                }
                require(
                        resized != null && resized.metadata().camera() == drawnCamera,
                        "Readback was relabeled with a later undrawn camera");
                require(resized.pixels().remaining() == 400000, "Fixed target max frame");
                int pixel = resized.pixels().get(250 * 800 + 400);
                require(Math.abs((pixel >>> 16 & 255) - 83) <= 1, "Resized actual pixels");
                boolean relabeled = false;
                try {
                    owner.submit(
                            new WorldStreamControl.Camera(
                                    NONCE,
                                    camera,
                                    2,
                                    800,
                                    500,
                                    new TerrainCamera(-99, -10, 50, 0, -.6)));
                } catch (IllegalArgumentException expected) {
                    relabeled = true;
                }
                require(relabeled, "Same camera revision changed its view");
                // Native resolution: the full shared cap, then back down (memory returned).
                long resizePolicy = 2;
                var nativeSizes = new StringBuilder();
                var edges = new StringBuilder();
                for (int[] size : new int[][] {{2560, 1600}, {1920, 1080}, {256, 256}}) {
                    long drawn = ++camera;
                    long epoch = ++resizePolicy;
                    require(
                            submitted(owner, command(drawn, size[0], size[1], epoch)),
                            "Resize to " + size[0] + "x" + size[1]);
                    var big = frameAt(owner, reader, epoch, size[0], size[1], drawn, content);
                    require(
                            big.pixels().remaining() == size[0] * size[1],
                            "Native frame size " + size[0] + "x" + size[1]);
                    int center = big.pixels().get(size[1] / 2 * size[0] + size[0] / 2);
                    require(
                            Math.abs((center >>> 16 & 255) - 83) <= 1,
                            "Native-size pixels " + Integer.toHexString(center));
                    // Edge rows: the crop must take the frame's own first and last rows, never
                    // rows of the larger allocation beyond the view rect (cleared to black).
                    int top = big.pixels().get(size[0] / 2);
                    int bottom = big.pixels().get((size[1] - 1) * size[0] + size[0] / 2);
                    int left = big.pixels().get(size[1] / 2 * size[0]);
                    int right = big.pixels().get(size[1] / 2 * size[0] + size[0] - 1);
                    for (int edge : new int[] {top, bottom, left, right}) {
                        require(
                                (edge & 0xffffff) != 0,
                                "Edge pixel is drawn, not the cleared allocation: "
                                        + Integer.toHexString(edge));
                    }
                    // First row is the sky gradient at that row's elevation (#407), last row the
                    // terrain.
                    float[] sky =
                            WorldFog.STREAM.skyPixel(
                                    CAMERA.pitch(),
                                    TerrainCamera.focalPixels(size[0]),
                                    size[0],
                                    size[1],
                                    size[0] / 2,
                                    0);
                    require(
                            Math.abs((top >>> 16 & 255) - Math.round(sky[0])) <= 1
                                    && Math.abs((top >>> 8 & 255) - Math.round(sky[1])) <= 1
                                    && Math.abs((top & 255) - Math.round(sky[2])) <= 1,
                            "Top row is the frame's own first row: "
                                    + Integer.toHexString(top)
                                    + " sky "
                                    + java.util.Arrays.toString(sky));
                    require(
                            Math.abs((bottom >>> 16 & 255) - 83) <= 1,
                            "Bottom row is the frame's own last row: "
                                    + Integer.toHexString(bottom));
                    edges.append(' ')
                            .append(size[0])
                            .append('x')
                            .append(size[1])
                            .append(":top=")
                            .append(Integer.toHexString(top))
                            .append(",bottom=")
                            .append(Integer.toHexString(bottom));
                    big.release();
                    nativeSizes
                            .append(' ')
                            .append(size[0])
                            .append('x')
                            .append(size[1])
                            .append("->")
                            .append(owner.targetWidth())
                            .append('x')
                            .append(owner.targetHeight());
                }
                require(
                        owner.targetWidth() == 256 && owner.targetHeight() == 256,
                        "Shrunk target returns memory");
                // MSAA is chosen once per device; every reallocation keeps it.
                require(
                        owner.stats().msaaSamples() == samplesBefore,
                        "Reallocation kept "
                                + samplesBefore
                                + " samples: "
                                + owner.stats().msaaSamples());
                require(
                        framesDuringUploads > 20,
                        "Camera frames were starved during incremental uploads");
                var finalStats = owner.stats();
                var closingInput =
                        prepare(
                                        worker,
                                        directory,
                                        tx,
                                        new WorldStreamState.Lease(incoming, 1000, 1000),
                                        7,
                                        19,
                                        60,
                                        true)
                                .get(20, TimeUnit.SECONDS);
                var closingUpload = owner.begin(closingInput);
                owner.step(closingUpload);
                require(
                        closingUpload.retainedInputBytes() == TERRAIN_FIXTURE_BYTES,
                        "Closing upload owner");
                owner.submit(command(++camera, 256, 256, resizePolicy));
                owner.close();
                require(
                        closingUpload.finished() && !closingUpload.successful(),
                        "Closing upload canceled");
                require(
                        closingUpload.outstandingReferences() == 0
                                && closingUpload.issuedReferences()
                                        == closingUpload.releasedReferences()
                                && closingUpload.retainedInputBytes() == 0,
                        "Shutdown callback/input drain");
                System.out.println(
                        "WORLD_NATIVE_OWNER_PASS backend="
                                + System.getProperty("vimi.worldProof.backend", "vulkan")
                                + " pid="
                                + pid
                                + " replacements=104 content="
                                + content
                                + " framesDuringUploads="
                                + framesDuringUploads
                                + " nativeSizes="
                                + nativeSizes.toString().strip()
                                + " edges="
                                + edges.toString().strip()
                                + " reallocations="
                                + owner.reallocations()
                                + " samples="
                                + samplesBefore
                                + " stats="
                                + finalStats);
            }
        }
        // Fresh session mapping and completed prior shutdown are required for a successor context.
        var nextNonce = new WorldStreamState.Nonce(789, -987);
        var nextSession = new WorldStreamEnvelope.Session(nextNonce, SCOPE, 1);
        try (var nextFrames =
                        WorldFrameFileV2.create(
                                directory.resolve("frames-successor"), nextNonce, SCOPE);
                var successor = new WorldStreamNativeOwner(nextSession, nextFrames, BACKEND)) {
            require(
                    successor.stats().dynamicBuffers() == SESSION.slots(),
                    "Successor context resources");
        }
        // Exterior object culling on a further fresh context: draw counts and pixels, culled and
        // not.
        WorldStreamObjectsCullProof.run(directory);
    }
}
