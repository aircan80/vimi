package com.vibeminer.rendering.bgfx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in native appearance proof (#379): renders small authored World scenes through the real
 * helper on the native GPU and compares each frame with a reference image of the same backend
 * ({@link AppearanceImages}). Authored data only; no game data.
 *
 * <p>Scenes: an authored flat textured terrain tile (every chunk triangulated) seen from above; a
 * strip of authored water on it seen from above; the tile seen from far away, in the fog toward the
 * view cut; and the material proof's view with the exterior object quads (a fully transparent
 * cutout, an opaque quad at full light and an alpha-blended quad, {@link
 * WorldStreamObjectsMaterialProof#quads}).
 *
 * <p>Arguments: output directory, backend ({@code VULKAN} or {@code D3D11}), reference directory,
 * and {@code update} to write the references instead of comparing. On a mismatch the actual frame
 * and a scaled difference image are written to the output directory.
 */
public final class WorldAppearanceProof {
    static final int WIDTH = 480, HEIGHT = 300;

    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        var backend = WorldStreamTransport.Backend.valueOf(arguments[1].toUpperCase(Locale.ROOT));
        Path references = Path.of(arguments[2]).resolve(backend.name().toLowerCase(Locale.ROOT));
        boolean update = arguments.length > 3 && arguments[3].equals("update");
        Files.createDirectories(output);
        var frames = render(output, backend);
        var failures = new ArrayList<String>();
        for (var scene : frames.entrySet()) {
            var file = references.resolve(scene.getKey() + ".png");
            int[] actual = scene.getValue();
            if (update) {
                AppearanceImages.write(file, WIDTH, HEIGHT, actual);
                System.out.println("WORLD_APPEARANCE_REFERENCE_WRITTEN " + scene.getKey());
                continue;
            }
            int[] reference = AppearanceImages.read(file, WIDTH, HEIGHT);
            if (reference == null) {
                failures.add(
                        scene.getKey() + ": no reference for " + backend + " (run with update)");
                continue;
            }
            var difference =
                    AppearanceImages.compare(
                            actual, reference, AppearanceImages.Tolerance.STANDARD.pixelDelta());
            System.out.println("WORLD_APPEARANCE " + scene.getKey() + " " + difference);
            if (!difference.matches(AppearanceImages.Tolerance.STANDARD)) {
                AppearanceImages.write(
                        output.resolve(scene.getKey() + "-actual.png"), WIDTH, HEIGHT, actual);
                if (reference.length == actual.length) {
                    AppearanceImages.write(
                            output.resolve(scene.getKey() + "-diff.png"),
                            WIDTH,
                            HEIGHT,
                            AppearanceImages.diff(actual, reference));
                }
                failures.add(scene.getKey() + ": " + difference);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Appearance differs from the references: " + failures);
        }
        System.out.println(
                "WORLD_APPEARANCE_PASS backend=" + backend + " scenes=" + frames.keySet());
    }

    /** Frames of each scene, in order, from one native helper session. */
    @SuppressWarnings("try")
    static Map<String, int[]> render(Path output, WorldStreamTransport.Backend backend)
            throws Exception {
        var process = new AtomicReference<TeeProcess>();
        var prepared =
                WorldStreamTransport.prepare(
                        output, new WorldStreamTransport.Spec("cd".repeat(32), 1, backend, true));
        var session =
                prepared.launch(
                        controls -> {
                            var command =
                                    new ArrayList<>(
                                            List.of(
                                                    Path.of(
                                                                    System.getProperty("java.home"),
                                                                    "bin",
                                                                    "java")
                                                            .toString(),
                                                    "--enable-native-access=ALL-UNNAMED",
                                                    "-cp",
                                                    System.getProperty("java.class.path"),
                                                    GpuViewerMain.class.getName()));
                            command.addAll(controls);
                            var child = new TeeProcess(new ProcessBuilder(command).start());
                            process.set(child);
                            return child;
                        });
        var frames = new LinkedHashMap<String, int[]>();
        try {
            session.initialized().get(15, TimeUnit.SECONDS);
            prepared.close();
            String renderer = process.get().renderer(Duration.ofSeconds(5));
            String expected =
                    switch (backend) {
                        case VULKAN -> "Vulkan";
                        case D3D11 -> "Direct3D 11";
                        case METAL -> "Metal";
                        case DEFAULT -> throw new IllegalArgumentException("Name a backend");
                    };
            System.out.println("WORLD_APPEARANCE_RENDERER " + renderer);
            if (!renderer.equals(expected)) {
                throw new AssertionError(
                        "The helper used " + renderer + ", not the requested " + backend);
            }
            var resident = grid(reserve(session)).get(8, TimeUnit.SECONDS);
            // Above the tile's centre, looking down at its whole textured surface.
            var above = new com.vibeminer.rendering.world.TerrainCamera(-266, -266, 330, 0, -1.45);
            session.camera(new WorldStreamTransport.Camera(1, 1, WIDTH, HEIGHT, above));
            var committed = session.commit(1, resident).get(5, TimeUnit.SECONDS);
            frames.put("terrain", frame(session, committed.contentRevision(), 1));
            // The references are recorded multisampled (#389): say which mode this device ran.
            System.out.println(
                    "WORLD_APPEARANCE_SAMPLES "
                            + (process.get().singleSampled()
                                    ? 1
                                    : WorldStreamNativeOwner.MSAA_SAMPLES));
            // Low over the water strip near the tile corner (native x, y in [-74, -10] x
            // [-266, -10]), looking down at it.
            var water = new com.vibeminer.rendering.world.TerrainCamera(-42, -138, 120, 0, -1.45);
            session.camera(new WorldStreamTransport.Camera(2, 2, WIDTH, HEIGHT, water));
            frames.put("water", frame(session, committed.contentRevision(), 2));
            // From 400 beyond the tile's +x edge and 150 up, looking back along -x and down: the
            // tile lies 400 to 933 away, so the fog rises across it toward the far edge.
            var fog = new com.vibeminer.rendering.world.TerrainCamera(400, -266, 150, Math.PI, -.3);
            session.camera(new WorldStreamTransport.Camera(3, 3, WIDTH, HEIGHT, fog));
            frames.put("fog", frame(session, committed.contentRevision(), 3));
            var view = WorldStreamObjectsMaterialProof.VIEW;
            var outcome =
                    session.attachObjects(resident, WorldStreamObjectsMaterialProof.quads())
                            .get(10, TimeUnit.SECONDS);
            if (outcome != WorldStreamTransport.ObjectsOutcome.ATTACHED) {
                throw new AssertionError("Authored objects did not attach: " + outcome);
            }
            session.camera(new WorldStreamTransport.Camera(4, 4, WIDTH, HEIGHT, view));
            frames.put("objects", frame(session, committed.contentRevision(), 4));
            var receipt = session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            if (receipt.exitCode().orElseThrow() != 0 || process.get().isAlive()) {
                throw new AssertionError("Actual native process drain disagrees");
            }
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
        return frames;
    }

    /**
     * The authored tile 7,19 (flat at height 10, 256 chunks of 145 vertices) with every chunk
     * triangulated as the client does: each of a chunk's 8 x 8 cells is four triangles around its
     * inner vertex. Same header, atlas and packet path as {@link
     * WorldStreamTransportProof#produce}.
     */
    @SuppressWarnings("try")
    static java.util.concurrent.CompletableFuture<WorldStreamTransport.Resident> grid(
            WorldStreamTransport.TileReservation reservation) {
        return reservation.produce(
                (destination, stopping) -> {
                    var c = destination.context();
                    var flat = WorldStreamTileFileTest.full(false);
                    float[] positions = new float[flat.vertexCount() * 3];
                    flat.vertices().get(positions);
                    int[] indices = new int[256 * 64 * 4 * 3];
                    int at = 0;
                    for (int chunk = 0; chunk < 256; chunk++) {
                        int base = chunk * 145;
                        for (int row = 0; row < 8; row++) {
                            for (int col = 0; col < 8; col++) {
                                int tl = base + row * 17 + col, tr = tl + 1;
                                int bl = tl + 17, br = bl + 1, centre = tl + 9;
                                int[] ring = {tl, tr, br, bl, tl};
                                for (int k = 0; k < 4; k++) {
                                    indices[at++] = centre;
                                    indices[at++] = ring[k];
                                    indices[at++] = ring[k + 1];
                                }
                            }
                        }
                    }
                    var tile =
                            com.vibeminer.rendering.world.TerrainScene.Tile.copyOf(
                                    7, 19, positions, indices, 256);
                    var h = WorldStreamTileFileTest.header(tile, true);
                    var header =
                            new WorldStreamTileFile.Header(
                                    c.nonce(),
                                    c.scope(),
                                    c.transaction(),
                                    c.lease(),
                                    h.x(),
                                    h.y(),
                                    h.chunks(),
                                    h.vertices(),
                                    h.indices(),
                                    h.geometrySha256(),
                                    h.materialDigest(),
                                    h.outcome(),
                                    h.reason(),
                                    h.coverage(),
                                    h.maxX(),
                                    h.maxY(),
                                    h.uvError(),
                                    h.wdt(),
                                    h.root(),
                                    h.tex0(),
                                    h.diffuse());
                    var terrainOnly =
                            WorldStreamTileFile.write(
                                    c.path().getParent(),
                                    header,
                                    tile,
                                    WorldStreamTileFileTest.atlas(),
                                    stopping);
                    // A strip of water 2.5 above the terrain: 16 x 64 cells of 4 x 4 near the
                    // tile's corner, with depths rising across each cell.
                    var expected =
                            WorldStreamTileFile.appendWater(
                                    c.path(),
                                    terrainOnly,
                                    header,
                                    WorldStreamTileWaterTest.water(1024, 0),
                                    stopping);
                    try (var original =
                            WorldStreamPacketFile.finalized(expected, c.path(), c.identity())) {
                        var packet = destination.adopt(original.move(), header);
                        original.close();
                        return packet;
                    }
                });
    }

    /**
     * The helper process, with its stderr copied as the transport reads it, so the proof can see
     * the helper's "World v2 renderer: <name>" diagnostic line.
     */
    static final class TeeProcess extends Process {
        private final Process delegate;
        private final java.io.ByteArrayOutputStream copy = new java.io.ByteArrayOutputStream();
        private final java.io.InputStream errors;

        TeeProcess(Process delegate) {
            this.delegate = delegate;
            errors =
                    new java.io.FilterInputStream(delegate.getErrorStream()) {
                        @Override
                        public int read() throws java.io.IOException {
                            int b = super.read();
                            if (b >= 0) {
                                synchronized (copy) {
                                    copy.write(b);
                                }
                            }
                            return b;
                        }

                        @Override
                        public int read(byte[] buffer, int offset, int length)
                                throws java.io.IOException {
                            int read = super.read(buffer, offset, length);
                            if (read > 0) {
                                synchronized (copy) {
                                    copy.write(buffer, offset, read);
                                }
                            }
                            return read;
                        }
                    };
        }

        /** Whether the helper reported that its render target could not be multisampled. */
        boolean singleSampled() {
            synchronized (copy) {
                return copy.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains("multisampling unavailable");
            }
        }

        /** The renderer name the helper reported on stderr, waiting up to {@code timeout}. */
        String renderer(Duration timeout) throws InterruptedException {
            long until = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < until) {
                String text;
                synchronized (copy) {
                    text = copy.toString(java.nio.charset.StandardCharsets.UTF_8);
                }
                var matcher =
                        java.util.regex.Pattern.compile("World v2 renderer: ([^\r\n]+)")
                                .matcher(text);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
                Thread.sleep(10);
            }
            throw new AssertionError("The helper did not report its renderer");
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return delegate.getOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return delegate.getInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return errors;
        }

        @Override
        public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return delegate.exitValue();
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }

        @Override
        public Process destroyForcibly() {
            return delegate.destroyForcibly();
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public long pid() {
            return delegate.pid();
        }

        @Override
        public ProcessHandle toHandle() {
            return delegate.toHandle();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Process> onExit() {
            return delegate.onExit().thenApply(ignored -> this);
        }
    }

    private static WorldStreamTransport.TileReservation reserve(
            WorldStreamTransport.Session session) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            var value = session.tryReserveTile(7, 19);
            if (value.isPresent()) {
                return value.orElseThrow();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No genuine tile credit");
    }

    private static int[] frame(WorldStreamTransport.Session session, long content, long policy)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            var value = session.takeLatestFrame();
            if (value.isPresent()) {
                var result = value.orElseThrow();
                if (result.content().contentRevision() == content && result.policy() == policy) {
                    if (result.width() != WIDTH || result.height() != HEIGHT) {
                        throw new AssertionError("Frame size differs from the requested camera");
                    }
                    return AppearanceImages.pixels(result.pixels());
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No accepted content/camera frame");
    }

    private WorldAppearanceProof() {}
}
