package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in actual native startup/control/exit proof; no content or frame acceptance claim. */
public final class WorldStreamTransportProof {
    @SuppressWarnings("try")
    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var process = new AtomicReference<Process>();
        long start = System.nanoTime();
        var prepared =
                WorldStreamTransport.prepare(
                        output,
                        new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN));
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
                            var child = new ProcessBuilder(command).start();
                            process.set(child);
                            return child;
                        });
        try {
            session.initialized().get(15, TimeUnit.SECONDS);
            prepared.close();
            WorldStreamTransport.TileReservation reserved = null;
            long creditDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (reserved == null && System.nanoTime() < creditDeadline) {
                reserved = session.tryReserveTile(7, 19).orElse(null);
                if (reserved == null) {
                    Thread.sleep(2);
                }
            }
            if (reserved == null) {
                throw new AssertionError("No real child tile credit");
            }
            var expired = new AtomicReference<WorldStreamTransport.PacketDestination>();
            try {
                reserved.produce(
                                (destination, stopping) -> {
                                    expired.set(destination);
                                    throw destination.refusal(
                                            WorldStreamTransport.Refusal.RETRY_LATER,
                                            "authored bounded source pressure");
                                })
                        .get(3, TimeUnit.SECONDS);
                throw new AssertionError("Refusal became a resident");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!(expected.getCause()
                                instanceof WorldStreamTransport.PreparationRefusal refusal)
                        || refusal.reason() != WorldStreamTransport.Refusal.RETRY_LATER) {
                    throw expected;
                }
            }
            try {
                expired.get().path();
                throw new AssertionError("Destination escaped callback");
            } catch (IllegalStateException expected) {
            }
            reserved = null;
            creditDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (reserved == null && System.nanoTime() < creditDeadline) {
                reserved = session.tryReserveTile(7, 19).orElse(null);
                if (reserved == null) {
                    Thread.sleep(2);
                }
            }
            if (reserved == null) {
                throw new AssertionError("Refused producer did not recover genuine child credit");
            }
            var resident = produce(reserved).get(8, TimeUnit.SECONDS);
            if (resident.tileX() != 7
                    || resident.tileY() != 19
                    || resident.coveredRectangles() != 256) {
                throw new AssertionError("Real STAGED correlation failed");
            }
            var view = new TerrainCamera(-20, -2, 20, 0, -.5);
            session.camera(new WorldStreamTransport.Camera(1, 1, 800, 500, view));
            var first = session.commit(1, resident).get(5, TimeUnit.SECONDS);
            var textured = frame(session, first.contentRevision(), 1, 800, 500);
            int pixel = textured.pixels().get(250 * 800 + 400);
            for (int shift : new int[] {0, 8, 16}) {
                int actual = (pixel >>> shift) & 255, expected = (0xff112233 >>> shift) & 255;
                if (Math.abs(actual - expected) > 1) {
                    throw new AssertionError(
                            "Actual textured center mismatch: " + Integer.toHexString(pixel));
                }
            }
            if ((pixel >>> 24) != 255 || !textured.pixels().isReadOnly()) {
                throw new AssertionError("Detached opaque frame contract");
            }
            session.camera(new WorldStreamTransport.Camera(2, 2, 480, 300, view));
            var resized = frame(session, first.contentRevision(), 2, 480, 300);
            if (resized.content().policy() != 1) {
                throw new AssertionError("Resize rewrote historical content receipt");
            }
            var empty = session.commit(2).get(5, TimeUnit.SECONDS);
            var stationary = frame(session, empty.contentRevision(), 2, 480, 300);
            int sky = stationary.pixels().get(150 * 480 + 240);
            if ((sky & 255) < 100 || ((sky >>> 16) & 255) < 80) {
                throw new AssertionError("Stationary content commit retained old pixels");
            }
            try {
                reserved.cancel().get(1, TimeUnit.SECONDS);
                throw new AssertionError("Retired committed tile reported CANCELED");
            } catch (java.util.concurrent.ExecutionException expected) {
                if (!(expected.getCause()
                                instanceof WorldStreamTransport.CancellationRefusal refusal)
                        || refusal.reason()
                                != WorldStreamTransport.CancellationReason.ALREADY_RETIRED) {
                    throw expected;
                }
            }
            reserved = reserve(session, 7, 19);
            produce(reserved).get(8, TimeUnit.SECONDS);
            reserved.cancel().get(3, TimeUnit.SECONDS);
            for (int i = 3; i <= 102; i++) {
                session.camera(
                        new WorldStreamTransport.Camera(
                                i, 2, 480, 300, new TerrainCamera(i, 0, 2, .01 * i, .1)));
            }
            // Camera processing is asynchronous. This checks process continuity, not applied count.
            Thread.sleep(100);
            if (!process.get().isAlive()) {
                throw new AssertionError("Native child ended during cameras");
            }
            var receipt = session.closeAsync().get(7, TimeUnit.SECONDS);
            session.completion().get(2, TimeUnit.SECONDS);
            if (receipt.childPid().orElseThrow() != process.get().pid()
                    || receipt.exitCode().orElseThrow() != 0
                    || process.get().isAlive()) {
                throw new AssertionError("Actual native process drain disagrees");
            }
            try (var children = Files.list(output)) {
                if (children.anyMatch(
                        path -> path.getFileName().toString().startsWith("world-v2-"))) {
                    throw new AssertionError("Private files retained after successful drain");
                }
            }
            System.out.println(
                    "WORLD_PUBLIC_LIFECYCLE_PASS pid="
                            + receipt.childPid().orElseThrow()
                            + " submittedCameras=100 elapsedMs="
                            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                            + " stagedTiles=2 refusedProducers=1 canceledTiles=1 commits=2 acceptedFrames=3");
        } finally {
            session.closeAsync();
            session.terminatedAndDrained().get(7, TimeUnit.SECONDS);
        }
    }

    /** One authored textured terrain tile 7,19 (also used by the objects material proof). */
    @SuppressWarnings("try")
    static java.util.concurrent.CompletableFuture<WorldStreamTransport.Resident> produce(
            WorldStreamTransport.TileReservation reservation) {
        return reservation.produce(
                (destination, stopping) -> {
                    var c = destination.context();
                    var originalTile = WorldStreamTileFileTest.full(false);
                    float[] positions = new float[originalTile.vertexCount() * 3];
                    originalTile.vertices().get(positions);
                    var tile =
                            com.vibeminer.rendering.world.TerrainScene.Tile.copyOf(
                                    7, 19, positions, new int[] {0, 1, 9}, 256);
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
                    var expected =
                            WorldStreamTileFile.write(
                                    c.path().getParent(),
                                    header,
                                    tile,
                                    WorldStreamTileFileTest.atlas(),
                                    stopping);
                    try (var original =
                            WorldStreamPacketFile.finalized(expected, c.path(), c.identity())) {
                        var packet = destination.adopt(original.move(), header);
                        original.close();
                        if (!Files.exists(c.path())) {
                            packet.close();
                            throw new AssertionError("MOVE lost input");
                        }
                        return packet;
                    }
                });
    }

    private static WorldStreamTransport.TileReservation reserve(
            WorldStreamTransport.Session session, int x, int y) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            var value = session.tryReserveTile(x, y);
            if (value.isPresent()) {
                return value.orElseThrow();
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No genuine tile credit");
    }

    private static WorldStreamTransport.Frame frame(
            WorldStreamTransport.Session session, long content, long policy, int width, int height)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            var value = session.takeLatestFrame();
            if (value.isPresent()) {
                var result = value.orElseThrow();
                if (result.content().contentRevision() == content
                        && result.policy() == policy
                        && result.width() == width
                        && result.height() == height) {
                    return result;
                }
            }
            Thread.sleep(2);
        }
        throw new AssertionError("No accepted content/camera frame");
    }

    private WorldStreamTransportProof() {}
}
