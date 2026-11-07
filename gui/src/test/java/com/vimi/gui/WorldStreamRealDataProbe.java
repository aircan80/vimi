package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainCamera;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Manual real-data timing for the World view distance (issue #173). Not part of check: it opens
 * the local application data directory (the app must be closed), uses an already prepared build
 * and the real GPU child, and prints only counts, times and byte totals (no paths or names).
 *
 * <p>Properties: {@code vimi.world.viewDistance} (near|far, read by the GPU owner), {@code
 * probe.map} (map id, default 2991), {@code probe.moves} (one-tile camera moves, default 4).
 */
public final class WorldStreamRealDataProbe {
    private static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(6);
    private static long peakHeap;
    private static WorldStreamSourceController controller;

    private WorldStreamRealDataProbe() {}

    public static void main(String[] args) throws Exception {
        long mapId = Long.getLong("probe.map", 2991);
        int moves = Integer.getInteger("probe.moves", 4);
        var distance = WorldStreamGpuOwner.VIEW_DISTANCE;
        var memory = ManagementFactory.getMemoryMXBean();
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(
                () -> peakHeap = Math.max(peakHeap, memory.getHeapMemoryUsage().getUsed()),
                0, 50, TimeUnit.MILLISECONDS);
        var service = BuildApplication.open(BuildApplication.defaultDataDirectory()).join();
        var fx = Executors.newSingleThreadExecutor();
        try {
            var builds = service.listBuilds().get(2, TimeUnit.MINUTES);
            BuildInfo build = null;
            String locale = null;
            String wanted = System.getProperty("probe.build", "");
            for (var candidate : builds) {
                String summary = service.historicalPreparation(candidate);
                if (summary != null)
                    System.out.println("PROBE prepared candidate " + candidate.product() + " "
                            + candidate.version() + " " + summary);
            }
            for (var candidate : builds) {
                String summary = service.historicalPreparation(candidate);
                if (summary != null && !candidate.observations().isEmpty()
                        && (candidate.product() + " " + candidate.version()).contains(wanted)) {
                    build = candidate;
                    locale = summary.substring(summary.lastIndexOf(" · ") + 3).strip();
                    break;
                }
            }
            if (build == null) throw new IllegalStateException("No previously prepared build");
            var region = build.observations().stream()
                    .filter(BuildInfo.RegionEvidence::current).findFirst()
                    .orElse(build.observations().getFirst());
            var preparation = service.preparation();
            var job = preparation.find(build, region, locale);
            if (job == null) job = preparation.start(build, region, locale);
            long preparing = System.nanoTime();
            var prepared = job;
            System.out.println("PROBE build=" + build.product() + " " + build.version() + " " + locale);
            long[] lastLog = {0};
            until(() -> {
                var snap = prepared.snapshot();
                if (System.nanoTime() - lastLog[0] > TimeUnit.SECONDS.toNanos(30)) {
                    lastLog[0] = System.nanoTime();
                    System.out.println("PROBE preparing ready=" + snap.ready() + " phase=" + snap.phase()
                            + " busy=" + snap.busy() + " done=" + snap.done() + "/" + snap.total()
                            + " problem=" + snap.problem());
                }
                return prepared.work() instanceof NativePreparationWork w && w.worldHandle() != null;
            });
            var handle = ((NativePreparationWork) job.work()).worldHandle();
            System.out.printf(Locale.ROOT, "PROBE preparation=%.1fs%n",
                    (System.nanoTime() - preparing) / 1e9);
            var catalog = handle.catalog(Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var map = catalog.maps().stream().filter(m -> m.id() == mapId).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Map not in catalog"));
            var inventory = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var tiles = inventory.world().tiles();
            // Origin: the recorded tile with the most recorded tiles in its 5 x 5 neighborhood.
            var origin = tiles.stream().max(Comparator.comparingLong((WorldFormats.Tile t) ->
                    tiles.stream().filter(o -> WorldStreamNeighborhood.ring(o, t.x(), t.y()) <= 2).count())
                    .thenComparingInt(t -> -t.x()).thenComparingInt(t -> -t.y())).orElseThrow();
            long neighbors = tiles.stream()
                    .filter(o -> WorldStreamNeighborhood.ring(o, origin.x(), origin.y()) <= distance.radius())
                    .count();
            System.out.printf(Locale.ROOT, "PROBE distance=%s tiles=%d neighborhood=%d%n",
                    distance, tiles.size(), neighbors);
            System.gc();
            long baseHeap = memory.getHeapMemoryUsage().getUsed();
            var request = new WorldStreamSourceController.Request(1, handle, map, inventory, origin,
                    null, catalog.areaIndex());
            var gpu = GpuPreviewService.application();
            controller = new WorldStreamSourceController(request, scope -> {
                var owner = new WorldStreamGpuOwner(gpu, scope);
                return new WorldStreamSourceController.GpuOwner() {
                    public CompletableFuture<WorldStreamTransport.Session> session() {
                        return owner.session();
                    }
                    public CompletableFuture<Void> closeAsync() { return owner.closeAsync(); }
                };
            }, () -> {}, fx::execute, System::nanoTime, WorldStreamSourceBridge::prepareExterior,
                    distance.residents());
            until(() -> controller.snapshot().seed() != null);
            var seed = controller.snapshot().seed();
            long started = System.nanoTime();
            var camera = seed.camera();
            long revision = 1;
            var firstDemand = steer(camera, revision, origin, seed, tiles, distance);
            long inner = -1, full;
            while (true) {
                present();
                var snapshot = controller.snapshot();
                if (inner < 0 && snapshot.prepared() >= Math.min(9, neighbors))
                    inner = System.nanoTime() - started;
                if (snapshot.phase() == WorldStreamSourceController.Phase.READY
                        && snapshot.prepared() == snapshot.desired() && snapshot.desired() == neighbors) {
                    full = System.nanoTime() - started;
                    break;
                }
                check(started, snapshot);
                Thread.sleep(5);
            }
            System.gc();
            System.out.printf(Locale.ROOT,
                    "PROBE fill inner9=%.2fs all%d=%.2fs baseHeapMiB=%.1f filledHeapMiB=%.1f%n",
                    inner / 1e9, neighbors, full / 1e9, baseHeap / 1048576.0,
                    memory.getHeapMemoryUsage().getUsed() / 1048576.0);
            // Fly: one tile per move along native -X (increasing filename X), then wait for READY.
            var times = new ArrayList<String>();
            for (int move = 1; move <= moves; move++) {
                camera = new TerrainCamera(camera.x() - WorldFormats.TILE_SIZE, camera.y(),
                        camera.z(), camera.yaw(), camera.pitch());
                long moved = System.nanoTime();
                var demand = steer(camera, ++revision, origin, seed, tiles, distance);
                long center = -1;
                boolean processed = false, shortfall = false;
                while (true) {
                    present();
                    var snapshot = controller.snapshot();
                    var ground = controller.ground();
                    // Ground under the camera is drawn once its tile is resident.
                    if (center < 0 && !Double.isNaN(ground.heightAt(camera.x(), camera.y())))
                        center = System.nanoTime() - moved;
                    // The demand is processed once residents fall short of it (new tiles missing).
                    if (snapshot.prepared() < snapshot.desired()) shortfall = true;
                    if (!processed && (shortfall
                            || System.nanoTime() - moved > TimeUnit.SECONDS.toNanos(2)))
                        processed = true;
                    if (processed && snapshot.phase() == WorldStreamSourceController.Phase.READY
                            && snapshot.prepared() == snapshot.desired()
                            && snapshot.desired() == demand.size()) break;
                    check(moved, snapshot);
                    Thread.sleep(5);
                }
                times.add(shortfall
                        ? String.format(Locale.ROOT, "center=%.2fs,ready=%.2fs",
                                center / 1e9, (System.nanoTime() - moved) / 1e9)
                        : "no-new-tiles");
            }
            System.out.printf(Locale.ROOT, "PROBE moves=%s peakHeapMiB=%.1f (includes backend)%n",
                    times, peakHeap / 1048576.0);
            System.gc();
            System.out.printf(Locale.ROOT, "PROBE settledHeapMiB=%.1f%n",
                    memory.getHeapMemoryUsage().getUsed() / 1048576.0);
            controller.closeAsync().get(1, TimeUnit.MINUTES);
        } finally {
            sampler.shutdownNow();
            fx.shutdownNow();
            service.closeAsync().get(2, TimeUnit.MINUTES);
        }
        System.exit(0);
    }

    private static List<WorldFormats.Tile> steer(TerrainCamera camera, long revision,
            WorldFormats.Tile origin, WorldStreamSourceController.Seed seed,
            List<WorldFormats.Tile> tiles, WorldStreamTransport.ViewDistance distance) {
        controller.camera(new WorldStreamTransport.Camera(revision, 1, 800, 500, camera));
        int x = origin.x() + (int) Math.floor((seed.originX() - camera.x()) / WorldFormats.TILE_SIZE);
        int y = origin.y() + (int) Math.floor((seed.originY() - camera.y()) / WorldFormats.TILE_SIZE);
        var demand = WorldStreamNeighborhood.select(tiles, x, y, camera.yaw(), distance);
        controller.neighborhood(demand);
        return demand;
    }

    /** Any sampled point of the tile has rendered ground: its terrain is resident. */
    private static boolean loaded(com.vibeminer.rendering.world.TerrainGround ground,
            WorldFormats.Tile tile, WorldFormats.Tile origin, WorldStreamSourceController.Seed seed) {
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                double x = seed.originX() - (tile.x() - origin.x() + (i + .5) / 5) * WorldFormats.TILE_SIZE;
                double y = seed.originY() - (tile.y() - origin.y() + (j + .5) / 5) * WorldFormats.TILE_SIZE;
                if (!Double.isNaN(ground.heightAt(x, y))) return true;
            }
        }
        return false;
    }

    private static void present() {
        // The World view pulses the controller from its frame timer; do the same.
        controller.pulse();
        controller.takePresentation().ifPresent(frame -> {
            try {
                if (controller.mayPresent(frame)) controller.presented(frame);
            } finally {
                controller.discard(frame);
            }
        });
    }

    private static void check(long started, WorldStreamSourceController.Snapshot snapshot) {
        if (snapshot.phase() == WorldStreamSourceController.Phase.FAILED
                || System.nanoTime() - started > TIMEOUT_NANOS)
            throw new IllegalStateException("World did not become ready: " + snapshot.phase()
                    + " " + snapshot.prepared() + "/" + snapshot.desired() + " " + snapshot.detail());
    }

    private static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Timed out");
            Thread.sleep(50);
        }
    }
}
