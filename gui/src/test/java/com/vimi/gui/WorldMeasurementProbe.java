package com.vimi.gui;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.files.FileDecodeTimings;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Opt-in real-data World measurement (Phase 0). Opens the prepared local build, drives the
 * production {@link WorldStreamSourceController}, GPU helper and JavaFX presentation over fixed
 * tile neighbourhoods, and prints per-stage timings ({@code -Dvimi.world.stageTimings=true}),
 * helper frame statistics, delivery cadence, exterior object counts and GC pauses. A second mode
 * counts terrain texture refusals over whole maps. Holds the store's writer lock: run it alone,
 * with the application closed. Every run is bounded by {@code probe.deadlineSeconds} (default
 * 570 s).
 *
 * <p>Properties: {@code probe.mode} = stream (default) or refusals; {@code probe.build} (default
 * 70790); {@code probe.scenarios} = name:map:x:y list (default Elwynn around Goldshire and
 * Stormwind); {@code probe.maps} for refusals (default 0,1); {@code probe.out} for the helper's
 * table and the report; {@code probe.gcLog} for the -Xlog:gc file the task writes.
 */
public final class WorldMeasurementProbe {
    private static final long START = System.nanoTime();
    private static final long DEADLINE =
            START + TimeUnit.SECONDS.toNanos(Long.getLong("probe.deadlineSeconds", 570));
    private static final StringBuilder REPORT = new StringBuilder();

    private WorldMeasurementProbe() {}

    public static void main(String[] args) {
        var watchdog =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        long left = DEADLINE - System.nanoTime();
                                        if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
                                        say("PROBE deadline reached; partial results follow");
                                        finish(3);
                                    } catch (InterruptedException ignored) {
                                        // Normal end.
                                    }
                                });
        int code = 0;
        try {
            run();
            gcSummary();
        } catch (Throwable failure) {
            say("PROBE failed: " + failure);
            failure.printStackTrace(System.out);
            code = 2;
        }
        watchdog.interrupt();
        finish(code);
    }

    private static synchronized void finish(int code) {
        try {
            String out = System.getProperty("probe.out");
            if (out != null) {
                Files.createDirectories(Path.of(out));
                Files.writeString(Path.of(out, "report.txt"), REPORT.toString());
            }
        } catch (Exception ignored) {
            // Report is also on stdout.
        }
        System.out.flush();
        Runtime.getRuntime().halt(code);
    }

    static synchronized void say(String line) {
        System.out.println(line);
        System.out.flush();
        REPORT.append(line).append('\n');
    }

    private static void run() throws Exception {
        if (!WorldStageTimings.enabled()) {
            throw new IllegalStateException("Run with -D" + WorldStageTimings.PROPERTY + "=true");
        }
        listenToGc();
        long opening = System.nanoTime();
        var service = BuildApplication.open(BuildApplication.defaultDataDirectory()).join();
        try {
            BuildInfo build = null;
            String locale = null;
            String wanted = System.getProperty("probe.build", "70790");
            for (var candidate : service.listBuilds().get(2, TimeUnit.MINUTES)) {
                String summary = service.historicalPreparation(candidate);
                if (summary != null
                        && candidate.version().contains(wanted)
                        && !candidate.observations().isEmpty()) {
                    build = candidate;
                    locale = summary.substring(summary.lastIndexOf(" · ") + 3).strip();
                }
            }
            if (build == null) throw new IllegalStateException("No prepared build " + wanted);
            var region = build.observations().getFirst();
            var job = service.preparation().find(build, region, locale);
            if (job == null) job = service.preparation().start(build, region, locale);
            while (!(job.work() instanceof NativePreparationWork w && w.worldHandle() != null)) {
                Thread.sleep(200);
            }
            var handle = ((NativePreparationWork) job.work()).worldHandle();
            var catalog = handle.catalog(Cancellation.NONE).get(5, TimeUnit.MINUTES);
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE build %s opened in %.1f s; java %s; %d processors; max heap %d MiB; backend %s",
                            build.version(),
                            seconds(System.nanoTime() - opening),
                            System.getProperty("java.version"),
                            Runtime.getRuntime().availableProcessors(),
                            Runtime.getRuntime().maxMemory() >> 20,
                            WorldStreamGpuOwner.configuredBackend()));
            String mode = System.getProperty("probe.mode", "stream");
            if (mode.equals("refusals")) {
                refusals(handle, catalog);
            } else {
                stream(handle, catalog);
            }
        } finally {
            service.closeAsync().get(2, TimeUnit.MINUTES);
        }
    }

    // ---- Streaming measurement -------------------------------------------------------------

    record Scenario(String name, long map, int x, int y) {
        static List<Scenario> parse(String text) {
            var result = new ArrayList<Scenario>();
            for (String item : text.split(",")) {
                String[] p = item.strip().split(":");
                result.add(
                        new Scenario(
                                p[0],
                                Long.parseLong(p[1]),
                                Integer.parseInt(p[2]),
                                Integer.parseInt(p[3])));
            }
            return result;
        }
    }

    private static void stream(BuildWorld handle, WorldService.Catalog catalog) throws Exception {
        var scenarios =
                Scenario.parse(
                        System.getProperty("probe.scenarios", "elwynn:0:31:49,stormwind:0:30:48"));
        Platform.startup(() -> Platform.setImplicitExit(false));
        var gpu = GpuPreviewService.application();
        try {
            for (var scenario : scenarios) {
                if (System.nanoTime() > DEADLINE - TimeUnit.SECONDS.toNanos(45)) {
                    say("PROBE skipped " + scenario.name() + ": deadline");
                    continue;
                }
                var map =
                        catalog.maps().stream()
                                .filter(m -> m.id() == scenario.map())
                                .findFirst()
                                .orElseThrow();
                var inventory = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES);
                var origin =
                        inventory.world().tiles().stream()
                                .filter(t -> t.x() == scenario.x() && t.y() == scenario.y())
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "No tile "
                                                                + scenario
                                                                + " in "
                                                                + map
                                                                + "; recorded nearby: "
                                                                + inventory.world().tiles().stream()
                                                                        .filter(
                                                                                t ->
                                                                                        Math.abs(t.x() - scenario.x()) <= 3
                                                                                                && Math.abs(t.y() - scenario.y()) <= 3)
                                                                        .map(t -> t.x() + "," + t.y())
                                                                        .toList()
                                                                + " of "
                                                                + inventory.world().tiles().size()));
                new StreamRun(scenario, handle, catalog, map, inventory, origin, gpu).run();
            }
        } finally {
            gpu.closeAsync().get(30, TimeUnit.SECONDS);
            Platform.exit();
        }
    }

    /** One controller lifetime over one neighbourhood; everything controller-facing on FX. */
    private static final class StreamRun {
        final Scenario scenario;
        final WorldStreamSourceController controller;
        final WorldService.Inventory inventory;
        final WorldStreamImagePresentation images = new WorldStreamImagePresentation(new ImageView());
        final Map<Long, Long> requested = new ConcurrentHashMap<>();
        final AtomicLong control = new AtomicLong();
        final long started = System.nanoTime();
        volatile long firstFrame, filled, lastAccepted, acceptedFrames;
        volatile String phase = "";
        volatile int prepared, desired;
        volatile String area = "";
        // FX-owned camera state.
        TerrainRenderer.Camera camera;
        double originX, originY;
        WorldFormats.Tile origin;
        Motion motion = Motion.NONE;
        long motionStarted;
        TerrainRenderer.Camera motionFrom;
        List<WorldFormats.Tile> lastNeighborhood = List.of();

        enum Motion {
            NONE,
            ROTATE,
            FLY
        }

        StreamRun(
                Scenario scenario,
                BuildWorld handle,
                WorldService.Catalog catalog,
                WorldService.MapInfo map,
                WorldService.Inventory inventory,
                WorldFormats.Tile origin,
                GpuPreviewService gpu) {
            this.scenario = scenario;
            this.inventory = inventory;
            this.origin = origin;
            var request =
                    new WorldStreamSourceController.Request(
                            1, handle, map, inventory, origin, null, catalog.areaIndex());
            var detail = WorldStreamGpuOwner.IMAGE_DETAIL;
            WorldStreamSourceBridge.ExteriorPreparer preparer =
                    (source, scope, tile, cancellation) ->
                            WorldStreamSourceBridge.prepareExterior(
                                    source, scope, tile, cancellation, null, detail);
            var holder = new WorldStreamSourceController[1];
            controller =
                    new WorldStreamSourceController(
                            request,
                            scope -> {
                                var owner = new WorldStreamGpuOwner(gpu, scope);
                                return new WorldStreamSourceController.GpuOwner() {
                                    public CompletableFuture<WorldStreamTransport.Session> session() {
                                        return owner.session();
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        return owner.closeAsync();
                                    }
                                };
                            },
                            () -> changed(holder[0]),
                            Platform::runLater,
                            System::nanoTime,
                            preparer);
            holder[0] = controller;
        }

        void run() throws Exception {
            WorldStageTimings.reset();
            FileDecodeTimings.reset();
            long allocated = allocatedBytes();
            say("");
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE scenario %s map %d centre tile %d,%d (exteriors %s, creatures %s, object textures %s)",
                            scenario.name(),
                            scenario.map(),
                            scenario.x(),
                            scenario.y(),
                            WorldStreamGpuOwner.EXTERIORS,
                            WorldStreamGpuOwner.CREATURES,
                            WorldStreamGpuOwner.IMAGE_DETAIL));
            var timer =
                    new AnimationTimer() {
                        @Override
                        public void handle(long now) {
                            pulse(now);
                        }
                    };
            fx(
                    () -> {
                        timer.start();
                        return null;
                    });
            long fillBudget = TimeUnit.SECONDS.toNanos(Long.getLong("probe.fillSeconds", 150));
            // 1. Fill: stationary camera at the seed until every tile and its objects are done.
            long lastObjects = -1, settledSince = 0;
            while (System.nanoTime() - started < fillBudget && beforeDeadline()) {
                Thread.sleep(250);
                long objects = objectsDone();
                boolean terrainDone = desired > 0 && prepared == desired && firstFrame != 0;
                if (terrainDone && filled == 0) filled = System.nanoTime();
                if (objects != lastObjects) {
                    lastObjects = objects;
                    settledSince = System.nanoTime();
                }
                if (terrainDone
                        && phase.equals("READY")
                        && prepares() >= desired
                        && System.nanoTime() - settledSince > TimeUnit.SECONDS.toNanos(3)) {
                    break;
                }
            }
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE fill: phase %s, %d/%d tiles; first frame %.2f s; all terrain %.2f s; objects settled %.2f s (%d object deliveries); area %s",
                            phase,
                            prepared,
                            desired,
                            stamp(firstFrame),
                            stamp(filled),
                            seconds(settledSince - started),
                            lastObjects,
                            fx(
                                    () ->
                                            camera == null
                                                    ? area
                                                    : controller
                                                            .areaAt(camera.x(), camera.y())
                                                            .label())));
            var fillTimings = WorldStageTimings.snapshot();
            var fillDecode = FileDecodeTimings.snapshot();
            // 2. Rotate in place: frame cost and delivery with every object resident.
            WorldStageTimings.reset();
            long rotateAllocated = allocatedBytes();
            fx(
                    () -> {
                        motion = Motion.ROTATE;
                        motionFrom = camera;
                        motionStarted = System.nanoTime();
                        return null;
                    });
            sleepBounded(TimeUnit.SECONDS.toNanos(Long.getLong("probe.rotateSeconds", 12)));
            var rotateTimings = WorldStageTimings.snapshot();
            long rotateAllocatedBytes = allocatedBytes() - rotateAllocated;
            long rotateNanos = System.nanoTime() - motionStarted;
            // 3. Fly one tile along native -x: streaming while moving.
            WorldStageTimings.reset();
            fx(
                    () -> {
                        motion = Motion.FLY;
                        motionFrom = camera;
                        motionStarted = System.nanoTime();
                        return null;
                    });
            long flyNanos = flyNanos();
            sleepBounded(flyNanos);
            fx(
                    () -> {
                        motion = Motion.NONE;
                        return null;
                    });
            long flyEnd = System.nanoTime();
            long settleBudget = TimeUnit.SECONDS.toNanos(Long.getLong("probe.settleSeconds", 60));
            while (System.nanoTime() - flyEnd < settleBudget && beforeDeadline()) {
                Thread.sleep(250);
                if (prepared == desired && phase.equals("READY")) break;
            }
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE fly: one tile in %.1f s, then %s %d/%d tiles after %.2f s",
                            seconds(flyNanos),
                            phase,
                            prepared,
                            desired,
                            seconds(System.nanoTime() - flyEnd)));
            var flyTimings = WorldStageTimings.snapshot();
            fx(
                    () -> {
                        timer.stop();
                        return null;
                    });
            controller.closeAsync().get(60, TimeUnit.SECONDS);
            long totalAllocated = allocatedBytes() - allocated;
            say("PROBE --- " + scenario.name() + " fill: parent stage timings ---");
            print(fillTimings, false);
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE fill BLTE zlib: %d chunks, %.1f MiB out, %.1f ms",
                            fillDecode.zlibChunks(),
                            fillDecode.zlibBytes() / 1048576.0,
                            fillDecode.zlibNanos() / 1e6));
            say("PROBE --- " + scenario.name() + " fill: exterior object counts (all tiles) ---");
            printCounts(fillTimings);
            say("PROBE --- " + scenario.name() + " rotate: delivery and frame ---");
            print(rotateTimings, true);
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE rotate allocation: %.1f MiB/s",
                            rotateAllocatedBytes / 1048576.0 / Math.max(1e-9, seconds(rotateNanos))));
            say("PROBE --- " + scenario.name() + " fly and settle ---");
            print(flyTimings, false);
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE %s allocation total %.1f MiB over %.1f s",
                            scenario.name(),
                            totalAllocated / 1048576.0,
                            seconds(System.nanoTime() - started)));
            childTable();
        }

        static long flyNanos() {
            return TimeUnit.SECONDS.toNanos(Long.getLong("probe.flySeconds", 10));
        }

        long prepares() {
            var series = WorldStageTimings.snapshot().get("parent.objects.prepare");
            return series == null ? 0 : series.count();
        }

        long objectsDone() {
            var s = WorldStageTimings.snapshot();
            long n = 0;
            for (String name :
                    List.of(
                            "parent.objects.attachRoundTrip",
                            "parent.objects.prepare",
                            "parent.objects.prepareCanceled")) {
                var series = s.get(name);
                if (series != null) n += series.count();
            }
            return n;
        }

        /** FX: what WorldView's timer does for a streaming explorer. */
        void pulse(long now) {
            if (camera != null && motionFrom != null && motion != Motion.NONE) {
                double t = (System.nanoTime() - motionStarted) / 1e9;
                if (motion == Motion.ROTATE) {
                    camera =
                            new TerrainRenderer.Camera(
                                    motionFrom.x(),
                                    motionFrom.y(),
                                    motionFrom.z(),
                                    motionFrom.yaw() + t,
                                    motionFrom.pitch());
                } else {
                    double speed = WorldFormats.TILE_SIZE / seconds(flyNanos());
                    camera =
                            new TerrainRenderer.Camera(
                                    motionFrom.x() - speed * Math.min(t, seconds(flyNanos())),
                                    motionFrom.y(),
                                    Math.max(camera.z(), groundZ() + 40),
                                    Math.PI,
                                    camera.pitch());
                }
                sendCamera();
                neighborhood();
            }
            controller.pulse();
        }

        double groundZ() {
            double h = controller.ground().heightAt(camera.x(), camera.y());
            return Double.isNaN(h) ? camera.z() - 40 : h;
        }

        void sendCamera() {
            long revision = control.incrementAndGet();
            requested.put(revision, System.nanoTime());
            if (requested.size() > 20_000) requested.clear();
            controller.camera(
                    new WorldStreamTransport.Camera(
                            revision, 1, 800, 500, TerrainCamera.from(camera)));
        }

        void neighborhood() {
            int x = origin.x() + (int) Math.floor((originX - camera.x()) / WorldFormats.TILE_SIZE);
            int y = origin.y() + (int) Math.floor((originY - camera.y()) / WorldFormats.TILE_SIZE);
            var next =
                    WorldStreamNeighborhood.select(inventory.world().tiles(), x, y, camera.yaw());
            if (!next.equals(lastNeighborhood)) {
                lastNeighborhood = next;
                controller.neighborhood(next);
            }
        }

        /** FX: WorldView.streamChanged without widgets. */
        void changed(WorldStreamSourceController owner) {
            if (owner == null) return;
            var snapshot = owner.snapshot();
            phase = snapshot.phase().name();
            prepared = snapshot.prepared();
            desired = snapshot.desired();
            if (snapshot.area() != null) area = snapshot.area().label();
            if (camera == null && snapshot.seed() != null) {
                camera = snapshot.seed().camera().software();
                originX = snapshot.seed().originX();
                originY = snapshot.seed().originY();
                sendCamera();
                neighborhood();
            }
            var pending = owner.takePresentation();
            if (pending.isEmpty()) return;
            var frame = pending.get();
            try {
                if (!owner.mayPresent(frame)) return;
                long presenting = System.nanoTime();
                boolean shown =
                        images.present(
                                frame.width(),
                                frame.height(),
                                frame.pixels(),
                                () -> true,
                                () -> owner.presented(frame));
                long now = System.nanoTime();
                WorldStageTimings.record("fx.frame.present", now - presenting);
                if (shown) {
                    acceptedFrames++;
                    if (firstFrame == 0) firstFrame = now;
                    if (lastAccepted != 0) {
                        WorldStageTimings.record("fx.frame.acceptInterval", now - lastAccepted);
                    }
                    lastAccepted = now;
                    Long asked = requested.get(frame.cameraRevision());
                    if (asked != null) {
                        WorldStageTimings.record("fx.frame.cameraToAccepted", now - asked);
                    }
                    WorldStageTimings.value(
                            "fx.frame.revisionLag", control.get() - frame.cameraRevision());
                }
            } finally {
                owner.discard(frame);
            }
        }

        double stamp(long at) {
            return at == 0 ? Double.NaN : seconds(at - started);
        }
    }

    private static void childTable() throws Exception {
        String file = System.getProperty(GpuPreviewService.STAGE_TIMINGS_FILE);
        if (file == null) return;
        var path = Path.of(file);
        if (!Files.exists(path)) {
            say("PROBE helper table missing");
            return;
        }
        say("PROBE --- helper stage timings and bgfx frame statistics (whole helper session) ---");
        for (String line : Files.readAllLines(path)) say("  " + line);
    }

    // ---- Terrain texture refusals over whole maps --------------------------------------------

    private static void refusals(BuildWorld handle, WorldService.Catalog catalog) throws Exception {
        for (String mapText : System.getProperty("probe.maps", "0,1").split(",")) {
            long mapId = Long.parseLong(mapText.strip());
            var map = catalog.maps().stream().filter(m -> m.id() == mapId).findFirst().orElse(null);
            if (map == null) {
                say("PROBE map " + mapId + " not in catalog");
                continue;
            }
            var all = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES).world().tiles();
            var states = new TreeMap<String, Integer>();
            var reasons = new TreeMap<String, Integer>();
            var chunks = new TreeMap<String, Integer>();
            long supported = 0;
            int tiles = 0;
            long began = System.nanoTime();
            long budget = TimeUnit.SECONDS.toNanos(Long.getLong("probe.mapSeconds", 240));
            for (int at = 0; at < all.size(); at += 9) {
                if (System.nanoTime() - began > budget || !beforeDeadline()) break;
                var batch = all.subList(at, Math.min(all.size(), at + 9));
                try (var result =
                        handle.terrainTextures(map, batch, Cancellation.NONE, p -> {})
                                .get(5, TimeUnit.MINUTES)) {
                    for (var tile : result.tiles()) {
                        tiles++;
                        states.merge(tile.state().name(), 1, Integer::sum);
                        supported += tile.supportedChunks();
                        for (var issue : tile.issues()) {
                            reasons.merge(issue.reason().name(), 1, Integer::sum);
                            if (issue.reason() == WorldTerrainTextureSource.Reason.CHUNK) {
                                String key =
                                        issue.chunkReason().name()
                                                + (issue.maskIssue().name().equals("NONE")
                                                        ? ""
                                                        : "/" + issue.maskIssue().name());
                                chunks.merge(key, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE refusals map %d (%s): %d of %d tiles in %.1f s; tile states %s;"
                                    + " textured chunks %d of %d (%.1f%%)",
                            mapId,
                            map.directory(),
                            tiles,
                            all.size(),
                            seconds(System.nanoTime() - began),
                            states,
                            supported,
                            tiles * 256L,
                            tiles == 0 ? 0 : 100.0 * supported / (tiles * 256.0)));
            say("PROBE   issue reasons (one per issue) " + reasons);
            say("PROBE   chunk refusals (composer reason/mask issue) " + chunks);
        }
    }

    // ---- Shared helpers ----------------------------------------------------------------------

    private static final Pattern PAUSE =
            Pattern.compile("(Pause [A-Za-z]+(?: \\([^)]*\\))*).* ([0-9.]+)ms$");

    private static void listenToGc() {
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter emitter)) continue;
            emitter.addNotificationListener(
                    (notification, ignored) -> {
                        if (!notification
                                .getType()
                                .equals(
                                        GarbageCollectionNotificationInfo
                                                .GARBAGE_COLLECTION_NOTIFICATION)) return;
                        var info =
                                GarbageCollectionNotificationInfo.from(
                                        (CompositeData) notification.getUserData());
                        WorldStageTimings.record(
                                "gc.mxbean." + info.getGcName().replace(' ', '_'),
                                TimeUnit.MILLISECONDS.toNanos(info.getGcInfo().getDuration()));
                    },
                    null,
                    null);
        }
    }

    /** Pauses from the -Xlog:gc file the task writes, for the whole run. */
    static Map<String, List<Double>> gcPauses(List<String> lines) {
        var pauses = new TreeMap<String, List<Double>>();
        for (String line : lines) {
            var matcher = PAUSE.matcher(line.strip());
            if (!line.contains("gc ") && !line.contains("gc]")) continue;
            if (matcher.find()) {
                pauses.computeIfAbsent(matcher.group(1), k -> new ArrayList<>())
                        .add(Double.parseDouble(matcher.group(2)));
            }
        }
        return pauses;
    }

    private static void print(WorldStageTimings.Snapshot snapshot, boolean all) {
        var filtered = new TreeMap<String, WorldStageTimings.Series>();
        snapshot.series()
                .forEach(
                        (name, series) -> {
                            if (!name.startsWith("objects.count.")) filtered.put(name, series);
                        });
        for (String line : new WorldStageTimings.Snapshot(filtered).format().split("\n")) {
            say("  " + line);
        }
    }

    private static void printCounts(WorldStageTimings.Snapshot snapshot) {
        var line = new StringBuilder("  ");
        int column = 0;
        for (var entry : snapshot.series().entrySet()) {
            if (!entry.getKey().startsWith("objects.count.")) continue;
            line.append(
                    String.format(
                            Locale.ROOT,
                            "%-38s %9d   ",
                            entry.getKey().substring("objects.count.".length()),
                            entry.getValue().sum()));
            if (++column % 2 == 0) {
                say(line.toString());
                line = new StringBuilder("  ");
            }
        }
        if (column % 2 != 0) say(line.toString());
    }

    static void gcSummary() {
        String log = System.getProperty("probe.gcLog");
        try {
            if (log == null || !Files.exists(Path.of(log))) return;
            var pauses = gcPauses(Files.readAllLines(Path.of(log)));
            say("PROBE --- GUI JVM GC pauses over the whole run (-Xlog:gc) ---");
            for (var entry : pauses.entrySet()) {
                var values = entry.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();
                double total = 0;
                for (double v : values) total += v;
                say(
                        String.format(
                                Locale.ROOT,
                                "  %-60s n=%d p50=%.2f ms p95=%.2f ms max=%.2f ms total=%.1f ms",
                                entry.getKey(),
                                values.length,
                                values[(int) Math.ceil(0.5 * values.length) - 1],
                                values[(int) Math.ceil(0.95 * values.length) - 1],
                                values[values.length - 1],
                                total));
            }
        } catch (Exception failure) {
            say("PROBE gc log unreadable: " + failure);
        }
    }

    private static long allocatedBytes() {
        return ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t
                ? t.getTotalThreadAllocatedBytes()
                : 0;
    }

    private static boolean beforeDeadline() {
        return System.nanoTime() < DEADLINE - TimeUnit.SECONDS.toNanos(20);
    }

    private static void sleepBounded(long nanos) throws InterruptedException {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end && beforeDeadline()) Thread.sleep(50);
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        var value = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        value.complete(action.call());
                    } catch (Throwable failure) {
                        value.completeExceptionally(failure);
                    }
                });
        return value.get(30, TimeUnit.SECONDS);
    }
}
