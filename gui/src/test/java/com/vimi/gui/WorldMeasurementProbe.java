package com.vimi.gui;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.concepts.world.WorldSky;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Opt-in real-data World measurement (Phase 0, #353; tile prep, #422).
 *
 * <p>Modes ({@code probe.mode}): {@code stream} (default) drives the production {@link
 * WorldStreamSourceController}, GPU helper and JavaFX presentation over fixed tile
 * neighbourhoods; {@code prep} times the real source path alone (terrain prepareAndWrite and
 * exterior preparation per tile, no helper or FX); {@code refusals} counts terrain texture
 * refusals over whole maps; {@code water} counts water outcomes over sampled tiles (#171). All
 * report per-stage timings ({@code -Dvimi.world.stageTimings=true}).
 *
 * <p>What it touches: see {@link WorldMeasurementOpen} and docs/world-measurement-baseline.md. It
 * runs only on a copy of the library ({@code -PdataDirectory}), never prepares anything, and
 * starts no background downloads or provider checks.
 *
 * <p>Bounds. {@code probe.deadlineSeconds} (default and maximum {@value #MAX_DEADLINE_SECONDS} s)
 * stops new work, and bounds the library open too; the run then closes the controller ({@value
 * #CONTROLLER_CLOSE_SECONDS} s), helper ({@value #HELPER_CLOSE_SECONDS} s) and library ({@link
 * WorldMeasurementOpen#CLOSE_SECONDS} s) normally. Deadline plus every close timeout stays inside
 * the task's 10-minute timeout. The main thread holds the watchdog off at all times except while
 * measuring, so the watchdog (after a further {@value #GRACE_SECONDS} s) can only end a stuck
 * measurement, never an open or a close, and it exits without halting. Exit codes ({@link
 * #exitCode}): 0 complete, 2 failed, 3 deadline or incomplete.
 *
 * <p>Other properties: {@code probe.build} (default 70790); {@code probe.scenarios} = name:map:x:y
 * list (vimi tile coordinates; default Elwynn around Goldshire 49,31 and Stormwind City 48,30);
 * {@code probe.size}, {@code probe.passes}, {@code probe.objects} for prep; {@code
 * probe.interiors=false} for exteriors only; {@code probe.root} to list one WMO root's drawn
 * groups; {@code probe.maps} for
 * refusals (default 0,1); {@code probe.out} for the report; {@code probe.gcLog} for the -Xlog:gc
 * file the task writes.
 */
public final class WorldMeasurementProbe {
    static final long MAX_DEADLINE_SECONDS = 400, GRACE_SECONDS = 150;
    static final long CONTROLLER_CLOSE_SECONDS = 45, HELPER_CLOSE_SECONDS = 20;
    private static final long START = System.nanoTime();
    private static final long DEADLINE =
            START
                    + TimeUnit.SECONDS.toNanos(
                            Math.min(
                                    MAX_DEADLINE_SECONDS,
                                    Long.getLong("probe.deadlineSeconds", MAX_DEADLINE_SECONDS)));
    private static final StringBuilder REPORT = new StringBuilder();
    // Held by the main thread except while it measures, and by every open and close: the
    // watchdog never acts while it is non-zero.
    private static final java.util.concurrent.atomic.AtomicInteger BUSY =
            new java.util.concurrent.atomic.AtomicInteger(1);
    // Whoever sets it first (main thread or watchdog) is the only one that exits.
    private static final java.util.concurrent.atomic.AtomicBoolean EXITING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean incomplete;

    private WorldMeasurementProbe() {}

    /** 2 when the run failed, otherwise 3 when it is incomplete or overran, otherwise 0. */
    static int exitCode(boolean failed, boolean incomplete, boolean overran) {
        return failed ? 2 : incomplete || overran ? 3 : 0;
    }

    public static void main(String[] args) {
        var watchdog =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        long left =
                                                DEADLINE
                                                        + TimeUnit.SECONDS.toNanos(GRACE_SECONDS)
                                                        - System.nanoTime();
                                        if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
                                        while (BUSY.get() > 0) Thread.sleep(100);
                                        if (!EXITING.compareAndSet(false, true)) return;
                                        say("PROBE measurement stuck past its deadline; exiting");
                                        finish(3);
                                    } catch (InterruptedException ignored) {
                                        // Normal end.
                                    }
                                });
        boolean failed = false;
        try {
            run();
            gcSummary();
        } catch (Throwable failure) {
            say("PROBE failed: " + failure);
            failure.printStackTrace(System.out);
            failed = true;
        }
        boolean overran = System.nanoTime() > DEADLINE;
        if (!failed && (incomplete || overran)) {
            say("PROBE incomplete: deadline reached or a scenario did not finish");
        }
        if (!EXITING.compareAndSet(false, true)) {
            // The watchdog is already exiting; it never does so while this thread holds BUSY.
            return;
        }
        watchdog.interrupt();
        finish(exitCode(failed, incomplete, overran));
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
        // A normal exit: shutdown hooks run, nothing is halted mid-write.
        System.exit(code);
    }

    /** Measures with the watchdog armed: the only time the main thread releases BUSY. */
    static void measuring(java.util.concurrent.Callable<?> action) throws Exception {
        BUSY.decrementAndGet();
        try {
            action.call();
        } finally {
            BUSY.incrementAndGet();
        }
    }

    /** Runs one open or close; the watchdog waits for it. */
    static <T> T busy(java.util.concurrent.Callable<T> action) throws Exception {
        BUSY.incrementAndGet();
        try {
            return action.call();
        } finally {
            BUSY.decrementAndGet();
        }
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
        String refused =
                WorldMeasurementOpen.libraryRefusal(
                        System.getProperty("vimi.dataDirectory"),
                        BuildApplication.applicationDataDirectory());
        if (refused != null) throw new IllegalStateException(refused);
        long opening = System.nanoTime();
        var opened =
                busy(
                        () ->
                                WorldMeasurementOpen.open(
                                        BuildApplication.defaultDataDirectory(),
                                        System.getProperty("probe.build", "70790"),
                                        null,
                                        issue -> say("PROBE " + issue),
                                        DEADLINE - System.nanoTime(),
                                        List.of("creatures", "displays")
                                                .contains(
                                                        System.getProperty(
                                                                "probe.mode", "stream"))));
        try {
            BuildWorld handle = opened.world();
            var catalog =
                    handle.catalog(Cancellation.NONE)
                            .get(Math.max(1, DEADLINE - System.nanoTime()), TimeUnit.NANOSECONDS);
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE build %s (%s, %s) opened in %.1f s on a library copy; local-only"
                                    + " work, no preparation, no background work; java %s; %d"
                                    + " processors; max heap %d MiB; backend %s",
                            opened.build.version(),
                            opened.receipt.region(),
                            opened.receipt.locale(),
                            seconds(System.nanoTime() - opening),
                            System.getProperty("java.version"),
                            Runtime.getRuntime().availableProcessors(),
                            Runtime.getRuntime().maxMemory() >> 20,
                            WorldStreamGpuOwner.configuredBackend()));
            measuring(
                    () -> {
                        switch (System.getProperty("probe.mode", "stream")) {
                            case "refusals" -> refusals(handle, catalog);
                            case "water" -> water(handle, catalog);
                            case "prep" -> prep(handle, catalog);
                            case "collision" -> collision(opened, handle, catalog);
                            case "walk" -> walk(handle, catalog);
                            case "creatures" -> creatures(opened, handle, catalog);
                            case "displays" -> displays(opened);
                            default -> stream(handle, catalog);
                        }
                        return null;
                    });
        } finally {
            busy(
                    () -> {
                        opened.close();
                        return null;
                    });
        }
    }

    /**
     * With {@code probe.root=<WMO root FileDataID>}, the drawn LOD0 group ordinals of that root on
     * each prepared tile and whether it carries a portal set (#383).
     */
    private static void printRoot(
            String scenario,
            WorldFormats.Tile tile,
            com.vibeminer.concepts.world.WorldExteriorObjects.TileObjects set) {
        long wanted = Long.getLong("probe.root", 0);
        if (wanted == 0) return;
        for (var model : set.models()) {
            if (model.doodad() || model.rootId() != wanted) continue;
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE root %d on %s %d,%d: drawn groups %s; portal set %s",
                            wanted,
                            scenario,
                            tile.x(),
                            tile.y(),
                            model.meshes().stream()
                                    .map(
                                            com.vibeminer.concepts.world.WorldExteriorObjects.Mesh
                                                    ::groupOrdinal)
                                    .sorted()
                                    .toList(),
                            model.portals() == null
                                    ? "none"
                                    : model.portals().groups().size()
                                            + " groups, "
                                            + model.portals().portals().size()
                                            + " portals"));
        }
    }

    /**
     * One prepared tile's building use: vertices and indices of its building (WMO) models, the
     * total of every pool, building placements and its root outcomes, so per-tile budgets can be
     * compared before and after a change.
     */
    private static void printTileUsage(
            String scenario,
            WorldFormats.Tile tile,
            com.vibeminer.concepts.world.WorldExteriorObjects.TileObjects set) {
        long vertices = 0, indices = 0;
        for (var model : set.models()) {
            if (model.doodad()) continue;
            for (var mesh : model.meshes()) {
                vertices += mesh.vertexCount();
                indices += mesh.indices().remaining();
            }
        }
        long buildings =
                set.placements().stream()
                        .filter(
                                p ->
                                        p.kind()
                                                == com.vibeminer.concepts.world.WorldExteriorObjects
                                                        .Kind.BUILDING)
                        .count();
        long wanted = Long.getLong("probe.root", 0);
        boolean drawn =
                set.models().stream().anyMatch(m -> !m.doodad() && m.rootId() == wanted);
        say(
                String.format(
                        Locale.ROOT,
                        "PROBE tile %s %d,%d: building vertices %d, indices %d; all pools vertices"
                                + " %d, indices %d; buildings %d; roots drawn %d, capped %d, by group %d;"
                                + " groups drawn %d, other tile %d%s",
                        scenario,
                        tile.x(),
                        tile.y(),
                        vertices,
                        indices,
                        set.vertices(),
                        set.indices(),
                        buildings,
                        set.count(
                                com.vibeminer.concepts.world.WorldExteriorObjects.Count.ROOT_DRAWN),
                        set.count(
                                com.vibeminer.concepts.world.WorldExteriorObjects.Count.ROOT_CAPPED),
                        set.count(
                                com.vibeminer.concepts.world.WorldExteriorObjects.Count
                                        .ROOT_BY_GROUP),
                        set.count(
                                com.vibeminer.concepts.world.WorldExteriorObjects.Count.GROUP_DRAWN),
                        set.count(
                                com.vibeminer.concepts.world.WorldExteriorObjects.Count
                                        .GROUP_OTHER_TILE),
                        wanted == 0 ? "" : "; root " + wanted + (drawn ? " drawn" : " not drawn")));
    }

    /** As the World view, unless {@code probe.interiors=false} measures exteriors only (#383). */
    static com.vibeminer.concepts.world.WorldExteriorObjects.Interiors probeInteriors(
            WorldService.MapInfo map) {
        return "false".equals(System.getProperty("probe.interiors"))
                ? com.vibeminer.concepts.world.WorldExteriorObjects.Interiors.NONE
                : WorldView.interiorMode(map);
    }

    // ---- Tile prep only: the real source path, no helper, GPU or FX (#422) ----------------

    /**
     * Collision bytes around each scenario: every obj0 placement of its 3x3 tiles, never the
     * capped draw set. WMO groups through {@link com.vibeminer.formats.world.WmoCollision} (solid
     * MOPY triangles and the group BSP), doodads (MDDF and the placed WMO doodad sets, set 0
     * always) through {@link com.vibeminer.formats.models.M2Collision}. Models are counted once
     * (they would be shared), placements per tile. Unreadable sources are counted, not guessed.
     */
    private static void collision(WorldMeasurementOpen opened, BuildWorld handle, WorldService.Catalog catalog)
            throws Exception {
        var scenarios = Scenario.parse(System.getProperty("probe.scenarios",
                "stormwind:0:48:30,ironforge:0:41:33,orgrimmar:1:29:40"));
        var limits = new com.vibeminer.formats.world.WmoFormats.Limits(
                1 << 20, 1 << 20, com.vibeminer.formats.world.WmoFormats.MAX_GROUPS, 256, 4096, 65536, 131072, 256,
                4, 4L << 20);
        for (var scenario : scenarios) {
            var map = catalog.maps().stream().filter(m -> m.id() == scenario.map()).findFirst().orElseThrow();
            var inventory = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var groups = new java.util.HashMap<Long, Long>();
            var models = new java.util.HashMap<Long, Long>();
            var unreadable = new TreeMap<String, Integer>();
            long started = System.nanoTime(), wmoPlacements = 0, doodadPlacements = 0, setDoodads = 0;
            long solid = 0, all = 0, bspGroups = 0, groupCount = 0, modelTriangles = 0, emptyModels = 0;
            for (var tile : inventory.world().tiles()) {
                if (Math.abs(tile.x() - scenario.x()) > 1 || Math.abs(tile.y() - scenario.y()) > 1) continue;
                byte[] obj0 = bytes(opened, tile.obj0Id(), unreadable, "obj0");
                if (obj0 == null) continue;
                var placements = com.vibeminer.formats.world.WorldPlacements.readObj0(obj0, () -> false);
                for (var p : placements.worldObjects()) {
                    wmoPlacements++;
                    if (p.reference().kind() != com.vibeminer.formats.world.WorldPlacements.ReferenceKind.FILE_DATA_ID) {
                        unreadable.merge("wmo by name", 1, Integer::sum);
                        continue;
                    }
                    byte[] rootBytes = bytes(opened, p.reference().value(), unreadable, "wmo root");
                    if (rootBytes == null) continue;
                    com.vibeminer.formats.world.WmoFormats.Root root;
                    try {
                        root = com.vibeminer.formats.world.WmoFormats.root(rootBytes, limits, () -> false);
                    } catch (IllegalArgumentException malformed) {
                        unreadable.merge("wmo root malformed", 1, Integer::sum);
                        continue;
                    }
                    int count = (int) Math.min(root.header().groupCount(), root.gfidCount());
                    for (int g = 0; g < count; g++) {
                        long id = root.gfid(g);
                        if (id <= 0 || groups.containsKey(id)) continue;
                        byte[] groupBytes = bytes(opened, id, unreadable, "wmo group");
                        if (groupBytes == null) {
                            groups.put(id, 0L);
                            continue;
                        }
                        try {
                            var c = com.vibeminer.formats.world.WmoCollision.read(groupBytes, () -> false);
                            groups.put(id, c.retainedBytes());
                            groupCount++;
                            solid += c.solidTriangles();
                            all += c.triangles();
                            if (c.hasBsp()) bspGroups++;
                            else unreadable.merge("BSP: " + c.bspIssue(), 1, Integer::sum);
                        } catch (IllegalArgumentException malformed) {
                            unreadable.merge("wmo group malformed", 1, Integer::sum);
                            groups.put(id, 0L);
                        }
                    }
                    var doodads = root.doodads();
                    if (!doodads.retained()) continue;
                    for (int set = 0; set < doodads.sets().size(); set++) {
                        if (set != 0 && set != p.doodadSet()) continue;
                        var range = doodads.sets().get(set);
                        for (long d = range.firstDefinition(); d < range.firstDefinition() + range.count()
                                && d < doodads.definitions().size(); d++) {
                            var def = doodads.definitions().get((int) d);
                            long id = def.reference() < doodads.fileIdCount() ? doodads.fileId(def.reference()) : 0;
                            setDoodads++;
                            if (id <= 0) {
                                unreadable.merge("set doodad without a file ID", 1, Integer::sum);
                                continue;
                            }
                            if (!models.containsKey(id)) models.put(id, model(opened, id, unreadable));
                        }
                    }
                }
                for (var d : placements.doodads()) {
                    doodadPlacements++;
                    if (d.reference().kind() != com.vibeminer.formats.world.WorldPlacements.ReferenceKind.FILE_DATA_ID) {
                        unreadable.merge("doodad by name", 1, Integer::sum);
                        continue;
                    }
                    long id = d.reference().value();
                    if (!models.containsKey(id)) models.put(id, model(opened, id, unreadable));
                }
            }
            for (var bytes : models.values()) {
                if (bytes < 0) continue;
                if (bytes <= 48) emptyModels++;
            }
            long groupBytes = groups.values().stream().mapToLong(Long::longValue).sum();
            long modelBytes = models.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum();
            // A placement: transform, bounds and model reference (about 96 bytes each).
            long instanceBytes = 96L * (wmoPlacements + doodadPlacements + setDoodads);
            say(String.format(Locale.ROOT,
                    "PROBE collision %s 3x3: %d WMO placements, %d groups read (%d with a valid BSP), %d of %d"
                            + " triangles solid, %.1f MiB; %d MDDF + %d set doodads, %d distinct models (%d without"
                            + " a collision mesh), %.1f MiB; placements %.2f MiB; total %.1f MiB in %.1f s;"
                            + " unreadable %s",
                    scenario.name(), wmoPlacements, groupCount, bspGroups, solid, all, groupBytes / 1048576.0,
                    doodadPlacements, setDoodads, models.size(), emptyModels, modelBytes / 1048576.0,
                    instanceBytes / 1048576.0, (groupBytes + modelBytes + instanceBytes) / 1048576.0,
                    seconds(System.nanoTime() - started), unreadable));
        }
    }

    /** One M2's collision bytes, or -1 when it could not be read. */
    private static long model(WorldMeasurementOpen opened, long id, Map<String, Integer> unreadable) {
        byte[] raw = bytes(opened, id, unreadable, "doodad model");
        if (raw == null) return -1;
        try {
            return com.vibeminer.formats.models.M2Collision.read(raw).retainedBytes();
        } catch (IllegalArgumentException malformed) {
            unreadable.merge("doodad model malformed", 1, Integer::sum);
            return -1;
        }
    }

    /** A local file's bytes, or null (counted under {@code kind} and its read state). */
    private static byte[] bytes(WorldMeasurementOpen opened, long id, Map<String, Integer> unreadable, String kind) {
        if (id <= 0) {
            unreadable.merge(kind + " without a file ID", 1, Integer::sum);
            return null;
        }
        try {
            var read = opened.files().get(com.vibeminer.files.FileTypes.FileRef.id(id), Cancellation.NONE).join();
            if (read.state() != com.vibeminer.files.FileTypes.FileReadState.COMPLETE) {
                unreadable.merge(kind + " " + read.state(), 1, Integer::sum);
                return null;
            }
            var out = new java.io.ByteArrayOutputStream();
            for (var segment : read.segments()) out.writeBytes(segment.bytes());
            return out.toByteArray();
        } catch (RuntimeException failed) {
            unreadable.merge(kind + " failed", 1, Integer::sum);
            return null;
        }
    }

    /**
     * Walks a body over the real terrain of each scenario's 3x3 tiles (the walk collision has
     * terrain only): from the middle tile's centre, 20 s in each of eight headings, then one
     * fall from 300 yd. Reports where each walk ended, how far the feet ever were below the
     * rendered surface, airborne and sliding steps, and whether the body waited for collision.
     */
    private static void walk(BuildWorld handle, WorldService.Catalog catalog) throws Exception {
        var scenarios = Scenario.parse(System.getProperty("probe.scenarios", "elwynn:0:49:31"));
        var source =
                handle.terrainStreaming()
                        .orElseThrow(() -> new IllegalStateException("No terrain streaming"));
        for (var scenario : scenarios) {
            var map = catalog.maps().stream().filter(m -> m.id() == scenario.map()).findFirst().orElseThrow();
            var inventory = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var scope = source.createScope(map, Cancellation.NONE);
            source.activate(scope);
            var heights = new ArrayList<com.vibeminer.rendering.world.TerrainGround.Tile>();
            double[] middle = null;
            for (var tile : inventory.world().tiles()) {
                if (Math.abs(tile.x() - scenario.x()) > 1 || Math.abs(tile.y() - scenario.y()) > 1) continue;
                com.vibeminer.rendering.world.TerrainGround.Tile[] built = {null};
                double[][] centre = {null};
                boolean isMiddle = tile.x() == scenario.x() && tile.y() == scenario.y();
                var written = source.prepareAndWrite(scope, tile, Cancellation.NONE, (borrow, token) -> {
                    built[0] = com.vibeminer.rendering.world.TerrainGround.Tile.of(borrow.geometry());
                    if (isMiddle) {
                        var v = borrow.geometry().vertices();
                        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
                        for (int i = v.position(); i + 2 < v.limit(); i += 3) {
                            x0 = Math.min(x0, v.get(i));
                            x1 = Math.max(x1, v.get(i));
                            y0 = Math.min(y0, v.get(i + 1));
                            y1 = Math.max(y1, v.get(i + 1));
                        }
                        centre[0] = new double[] {(x0 + x1) / 2, (y0 + y1) / 2};
                    }
                    return () -> {};
                });
                written.close();
                if (built[0] != null) heights.add(built[0]);
                if (centre[0] != null) middle = centre[0];
            }
            if (middle == null) {
                say("PROBE walk " + scenario.name() + ": the middle tile has no terrain");
                continue;
            }
            var ground = com.vibeminer.rendering.world.TerrainGround.of(heights);
            var world = com.vibeminer.rendering.world.CollisionWorld.terrain(ground);
            say(String.format(Locale.ROOT, "PROBE walk %s: %d qualified tiles, start %.1f, %.1f", scenario.name(),
                    ground.tiles(), middle[0], middle[1]));
            double dt = com.vibeminer.rendering.world.MovementParity.STEP_SECONDS;
            for (int heading = 0; heading < 8; heading++) {
                double yaw = heading * Math.PI / 4;
                var body = new com.vibeminer.rendering.world.WalkController();
                body.place(middle[0], middle[1], 2_000, 4_000, world);
                double startZ = body.z(), deepest = 0, highest = -1e9, lowest = 1e9;
                int air = 0, sliding = 0, waiting = 0, returned = 0, steps = (int) Math.round(20 / dt);
                var input = new com.vibeminer.rendering.world.WalkController.Input(1, 0, 0, yaw, 0, false, 0);
                long began = System.nanoTime();
                for (int i = 0; i < steps; i++) {
                    body.step(input, dt, world);
                    double surface = ground.heightAt(body.x(), body.y());
                    if (!Double.isNaN(surface)) deepest = Math.min(deepest, body.z() - surface);
                    highest = Math.max(highest, body.z());
                    lowest = Math.min(lowest, body.z());
                    switch (body.status()) {
                        case FALLING -> air++;
                        case SLIDING -> sliding++;
                        case LOADING -> waiting++;
                        case RETURNED -> returned++;
                        default -> {}
                    }
                }
                double travelled = Math.hypot(body.x() - middle[0], body.y() - middle[1]);
                say(String.format(Locale.ROOT,
                        "PROBE walk %s heading %3d deg: start z %.2f, travelled %.1f of %.1f yd, z %.1f..%.1f,"
                                + " feet at most %.2f yd under the surface, steps airborne %d sliding %d"
                                + " waiting %d returned %d of %d, %.2f ms per step",
                        scenario.name(), heading * 45, startZ, travelled,
                        com.vibeminer.rendering.world.MovementParity.RUN_SPEED * 20, lowest, highest, -deepest, air,
                        sliding, waiting, returned, steps, (System.nanoTime() - began) / 1e6 / steps));
            }
            var faller = new com.vibeminer.rendering.world.WalkController();
            faller.place(middle[0], middle[1], 2_000, 4_000, world);
            double floor = faller.z();
            faller.place(middle[0], middle[1], floor + 300, 0, world);
            int fall = 0;
            while (!faller.grounded() && fall < 100_000) {
                faller.step(new com.vibeminer.rendering.world.WalkController.Input(0, 0, 0, 0, 0, false, 0), dt, world);
                fall++;
            }
            say(String.format(Locale.ROOT, "PROBE walk %s: a 300 yd fall landed after %.2f s at %.2f (ground %.2f)",
                    scenario.name(), fall * dt, faller.z(), floor));
        }
    }

    private static void prep(BuildWorld handle, WorldService.Catalog catalog) throws Exception {
        var scenarios =
                Scenario.parse(System.getProperty("probe.scenarios", "elwynn:0:49:31"));
        int size = Integer.getInteger("probe.size", 3);
        int passes = Integer.getInteger("probe.passes", 2);
        boolean objects = !"false".equals(System.getProperty("probe.objects"));
        var detail = WorldStreamGpuOwner.IMAGE_DETAIL;
        var source =
                handle.terrainStreaming()
                        .orElseThrow(() -> new IllegalStateException("No terrain streaming"));
        for (var scenario : scenarios) {
            var map =
                    catalog.maps().stream()
                            .filter(m -> m.id() == scenario.map())
                            .findFirst()
                            .orElseThrow();
            var inventory = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var tiles = new ArrayList<WorldFormats.Tile>();
            int half = size / 2;
            for (var tile : inventory.world().tiles()) {
                if (Math.abs(tile.x() - scenario.x()) <= half
                        && Math.abs(tile.y() - scenario.y()) <= half) tiles.add(tile);
            }
            var scope = source.createScope(map, Cancellation.NONE);
            source.activate(scope);
            var usage = new ModelUsage();
            for (int pass = 1; pass <= passes && beforeDeadline(); pass++) {
                WorldStageTimings.reset();
                FileDecodeTimings.reset();
                long began = System.nanoTime();
                int done = 0;
                for (var tile : tiles) {
                    if (!beforeDeadline()) break;
                    long terrain = WorldStageTimings.start();
                    var written =
                            source.prepareAndWrite(
                                    scope, tile, Cancellation.NONE, (borrow, token) -> () -> {});
                    WorldStageTimings.stop("prep.terrain", terrain);
                    written.close();
                    if (objects) {
                        // The production preparation, without the bridge's copy (about 6 ms per
                        // tile in stream mode), so the probe can see each set's models.
                        long prepared = WorldStageTimings.start();
                        try (var set =
                                source.prepareExteriorObjects(
                                        scope, tile, Cancellation.NONE, detail,
                                        probeInteriors(map), 0)) {
                            WorldStageTimings.stop("prep.objects", prepared);
                            set.tallyCounts();
                            if (pass == 1) usage.add(set);
                            if (pass == 1) printRoot(scenario.name(), tile, set);
                            if (pass == 1) printTileUsage(scenario.name(), tile, set);
                        } catch (RuntimeException failed) {
                            WorldStageTimings.stop("prep.objectsFailed", prepared);
                            say("PROBE objects failed on " + tile + ": " + failed);
                        }
                    }
                    done++;
                }
                if (done < tiles.size()) incomplete = true;
                var decode = FileDecodeTimings.snapshot();
                say(
                        String.format(
                                Locale.ROOT,
                                "PROBE prep %s pass %d (%s): %d of %d tiles (%dx%d around %d,%d) in %.2f s;"
                                        + " BLTE zlib %d chunks, %.1f MiB, %.1f ms",
                                scenario.name(),
                                pass,
                                pass == 1 ? "first in this process" : "repeat",
                                done,
                                tiles.size(),
                                size,
                                size,
                                scenario.x(),
                                scenario.y(),
                                seconds(System.nanoTime() - began),
                                decode.zlibChunks(),
                                decode.zlibBytes() / 1048576.0,
                                decode.zlibNanos() / 1e6));
                var snapshot = WorldStageTimings.snapshot();
                print(snapshot, false);
                if (objects) printCounts(snapshot);
                if (objects && pass == 1) usage.print(scenario.name());
            }
        }
    }

    // ---- Creature and humanoid NPC counts (#553): opt-in, no helper, GPU or FX --------------

    /**
     * The production NPC source, recording each distinct humanoid display's latest answer: an
     * appearance, a stated refusal, or a retry (data not readable yet).
     */
    static final class NpcAnswers implements WorldTileCreatures.Npcs {
        final WorldTileCreatures.Npcs inner;
        final Map<Long, String> outcomes = new ConcurrentHashMap<>();

        NpcAnswers(WorldTileCreatures.Npcs inner) {
            this.inner = inner;
        }

        @Override
        public com.vibeminer.concepts.races.RaceNpcs.Appearance appearance(
                long extendedDisplayInfoId, Cancellation token) throws Exception {
            var answer = inner.appearance(extendedDisplayInfoId, token);
            outcomes.put(extendedDisplayInfoId, outcome(answer));
            return answer;
        }

        static String outcome(com.vibeminer.concepts.races.RaceNpcs.Appearance answer) {
            if (answer == null) return "refused: no NPC appearance";
            if (answer.refusal() != null)
                return (answer.retry() ? "not readable yet: " : "refused: ") + answer.refusal();
            if (answer.result().appearance() == null)
                return "refused: "
                        + (answer.result().message() == null
                                ? "no NPC appearance"
                                : answer.result().message());
            return "appearance built";
        }

        /** Distinct displays per outcome, sorted by outcome. */
        Map<String, Integer> tally() {
            var counts = new TreeMap<String, Integer>();
            outcomes.values().forEach(o -> counts.merge(o, 1, Integer::sum));
            return counts;
        }
    }

    /**
     * The production creature chooser, counting why spawns are not qualified (distinct NPC IDs per
     * reason, with one example detail), so a refusal on real data names its cause.
     */
    static final class ChoiceReasons implements WorldTileCreatures.Chooser {
        final WorldTileCreatures.Chooser inner;
        final Map<Long, String> reasons;
        final Map<String, String> examples;

        ChoiceReasons(WorldTileCreatures.Chooser inner) {
            this(inner, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }

        private ChoiceReasons(WorldTileCreatures.Chooser inner, Map<Long, String> reasons,
                Map<String, String> examples) {
            this.inner = inner;
            this.reasons = reasons;
            this.examples = examples;
        }

        /** The World's per-tile batch, still counted here. */
        @Override
        public WorldTileCreatures.Chooser forNpcs(java.util.Collection<Long> npcIds, Cancellation token)
                throws Exception {
            return new ChoiceReasons(inner.forNpcs(npcIds, token), reasons, examples);
        }

        @Override
        public WorldTileCreatures.Chosen choose(long npcId, Cancellation token) throws Exception {
            var chosen = inner.choose(npcId, token);
            var choice = chosen.choice();
            if (choice.state()
                    != com.vibeminer.concepts.creatures.CreatureModelPolicy.State.QUALIFIED) {
                String key = choice.state() + " " + choice.reason();
                reasons.put(npcId, key);
                examples.putIfAbsent(key, choice.detail());
            }
            return chosen;
        }

        Map<String, Integer> tally() {
            var counts = new TreeMap<String, Integer>();
            reasons.values().forEach(r -> counts.merge(r, 1, Integer::sum));
            return counts;
        }
    }

    /**
     * The production display previews, recording per distinct creature display (#377, #455) its
     * Stand pose outcome and its geoset facts: the model's CreatureGeosetDataID, whether the
     * display has geoset rows, the outcome of the production geoset rule, and for each geoset
     * group of the model whether it has an x00 and an x01 section. Aggregate counts only.
     */
    static final class DisplayCensus implements WorldTileCreatures.Previews {
        final WorldTileCreatures.Previews inner;
        final Map<Long, DisplayModelFacts> facts = new ConcurrentHashMap<>();
        final Map<Long, String> stand = new ConcurrentHashMap<>();
        /** The World profile's Stand outcome (#384): billboard triangles left out. */
        final Map<Long, String> worldStand = new ConcurrentHashMap<>();
        private final Map<Long, com.vibeminer.rendering.models.MeshAppearance> appearances =
                new ConcurrentHashMap<>();
        private final Map<Long, com.vibeminer.concepts.models.DisplayModelTypes.Preview> previews =
                new ConcurrentHashMap<>();

        DisplayCensus(WorldTileCreatures.Previews inner) {
            this.inner = inner;
        }

        /** As the World asks: a humanoid NPC's display is known without its preview. */
        @Override
        public com.vibeminer.concepts.models.DisplayModelTypes.Reference reference(
                long displayId, Cancellation token) throws Exception {
            return inner.reference(displayId, token);
        }

        @Override
        public com.vibeminer.concepts.models.DisplayModelTypes.Preview preview(
                long displayId, Cancellation token) throws Exception {
            var preview = inner.preview(displayId, token);
            if (preview != null && preview.appearance() != null && preview.reference() != null) {
                facts.put(displayId, DisplayModelFacts.of(preview.reference(), preview.appearance().mesh()));
                appearances.put(displayId, preview.appearance());
                previews.put(displayId, preview);
                material.put(displayId, materialOutcome(preview, token));
            }
            return preview;
        }

        @Override
        public com.vibeminer.concepts.models.DisplayModelTypes.Idle idle(
                long displayId, Cancellation token) throws Exception {
            var idle = inner.idle(displayId, token);
            stand.put(displayId, standOutcome(idle, appearances.get(displayId)));
            return idle;
        }

        /**
         * Both Stand answers from one model read: the preview's, and the World's as the World
         * converts it (frame 0, its geoset rule and the triangles it leaves out).
         */
        @Override
        public List<com.vibeminer.concepts.models.DisplayModelTypes.Idle> stands(
                long displayId, Cancellation token) throws Exception {
            var both = inner.stands(displayId, token);
            var appearance = appearances.get(displayId);
            stand.put(displayId, standOutcome(both.get(0), appearance));
            worldStand.put(displayId, worldOutcome(both.get(1), previews.get(displayId), token));
            return both;
        }

        /** The World's own call (creatures mode): the World profile, recorded as the World draws it. */
        @Override
        public com.vibeminer.concepts.models.DisplayModelTypes.Idle worldIdle(
                long displayId, Cancellation token) throws Exception {
            var idle = inner.worldIdle(displayId, token);
            worldStand.put(displayId, worldOutcome(idle, previews.get(displayId), token));
            return idle;
        }

        /** The World conversion's outcome for one display's static model (#361). */
        final Map<Long, String> material = new ConcurrentHashMap<>();

        /** "drawn", or the conversion's refusal, for the static model as the World binds it. */
        static String materialOutcome(
                com.vibeminer.concepts.models.DisplayModelTypes.Preview preview, Cancellation token) {
            var converted = WorldTileCreatures.convert(preview, token);
            return converted.model() != null ? "drawn" : "not drawn: " + converted.refusal().label;
        }

        /**
         * Drawn static models by the sorted range kinds they draw (#361): "with blended", "with
         * additive", both, or neither.
         */
        Map<String, Integer> sortedKinds(Cancellation token) {
            var out = new TreeMap<String, Integer>();
            for (var entry : material.entrySet()) {
                if (!"drawn".equals(entry.getValue())) continue;
                var preview = previews.get(entry.getKey());
                var f = facts.get(entry.getKey());
                if (preview == null || f == null || !f.creature()) continue;
                var model = WorldTileCreatures.convert(preview, token).model();
                if (model == null) continue;
                boolean blended = false, additive = false;
                for (int range : model.blends()) {
                    int kind = range & 0xff;
                    blended |= kind == WorldTileCreatures.BLENDED;
                    additive |= kind == WorldTileCreatures.ADDITIVE;
                }
                out.merge(blended && additive ? "blended and additive" : blended ? "blended"
                        : additive ? "additive" : "neither", 1, Integer::sum);
            }
            return out;
        }

        /**
         * For each sampled creature display the World refuses for want of a surface: the fewest
         * blockers any material-layer-0 batch has against the World batch rule (combiner, blend,
         * an additive or blended texture weight that is not full in every key, texture type, empty monster
         * skin slot, texture file), as one label per display.
         */
        Map<String, Integer> refusalBlockers(com.vibeminer.files.BuildFiles files) {
            var out = new TreeMap<String, Integer>();
            var models = new com.vibeminer.formats.models.BuildModels(files);
            for (var entry : material.entrySet()) {
                if (!entry.getValue().startsWith("not drawn: no opaque")) continue;
                var preview = previews.get(entry.getKey());
                if (preview == null || preview.reference() == null) continue;
                String label;
                try {
                    label = blockers(models, files, preview.reference());
                } catch (RuntimeException failed) {
                    label = "unreadable: " + failed.getClass().getSimpleName();
                }
                // A fading weight's answer at the Stand sample (#384), from the binding's notes.
                if (label.contains("fading texture weight")) {
                    var notes = standWeightNotes(preview);
                    if (!notes.isEmpty()) label += " [" + String.join("; ", notes) + "]";
                }
                if (label.startsWith("no blocker"))
                    label = preview.issues().stream().filter(issue -> issue.startsWith("Texture "))
                            .findFirst().map(issue -> "no blocker; " + reasonKind(issue))
                            .orElse(label);
                out.merge(label, 1, Integer::sum);
            }
            return out;
        }

        static String blockers(com.vibeminer.formats.models.BuildModels models,
                com.vibeminer.files.BuildFiles files,
                com.vibeminer.concepts.models.DisplayModelTypes.Reference reference) {
            var token = Cancellation.NONE;
            var read = models.readModel(
                    com.vibeminer.files.FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
            if (read.model() == null) return "model " + read.state();
            var skin = models.readRestDrawSkin(read, 0, token).join();
            if (skin.skin() == null) return "skin " + skin.state();
            var m = read.model();
            com.vibeminer.formats.models.M2Appearance.ModelFacts facts = null;
            try {
                var opened = files.openRead(
                        com.vibeminer.files.FileTypes.FileRef.id(reference.modelFileDataId()), token).join();
                try (var stream = opened.stream()) {
                    facts = com.vibeminer.formats.models.M2Appearance.readModel(
                            com.vibeminer.formats.AssetSources.file(stream), m, token).join();
                }
            } catch (Exception unavailable) {
                facts = null;
            }
            java.util.TreeSet<String> best = null;
            for (var batch : skin.skin().batches()) {
                if (batch.materialLayer() != 0) continue;
                var set = new java.util.TreeSet<String>();
                if (!com.vibeminer.formats.models.M2Combiners.firstTextureUsesUv0(
                        batch.textureCount(), batch.shaderId())) {
                    var names = batch.shaderId() < 0 || batch.shaderId() > 0xffff ? null
                            : com.vibeminer.formats.models.M2Combiners.name(
                                    batch.textureCount(), batch.shaderId());
                    set.add("combiner " + (names == null ? "unnamed" : names.vertexShader()));
                }
                int blend = m.materials().get(batch.materialIndex()).blendMode();
                if (blend == 2 || blend == 4) {
                    int weight = m.textureWeights().get(batch.textureWeightComboIndex());
                    if (weight != 65535 && (facts == null || weight >= facts.staticWeights().size()
                            || !facts.staticWeights().get(weight)))
                        set.add((blend == 4 ? "additive" : "blended") + " with a fading texture weight");
                } else if (blend > 1) set.add("blend " + blend);
                var texture = m.textures().get(m.textureCombos().get(batch.textureComboIndex()));
                long type = texture.type();
                boolean objectSkin = type == 2 && objectSkinFromVariation(m);
                if (type != 0 && (type < 11 || type > 13) && !objectSkin)
                    set.add("texture type " + type);
                else {
                    long fid = type == 0 ? (texture.fileDataId() == null ? 0 : texture.fileDataId())
                            : objectSkin ? (reference.textureVariations().isEmpty() ? 0
                                    : reference.textureVariations().getFirst())
                            : type - 11 < reference.textureVariations().size()
                                    ? reference.textureVariations().get((int) type - 11) : 0;
                    if (fid <= 0) set.add(type == 0 ? "no texture file"
                            : objectSkin ? "empty object skin slot" : "empty monster skin slot");
                    else {
                        var stat = files.stat(com.vibeminer.files.FileTypes.FileRef.id(fid), token).join();
                        if (stat.state() != com.vibeminer.files.FileTypes.FileLookupState.FOUND)
                            set.add("texture " + stat.state());
                    }
                }
                if (best == null || set.size() < best.size()
                        || set.size() == best.size()
                                && String.join("+", set).compareTo(String.join("+", best)) < 0)
                    best = set;
            }
            if (best == null) return "no layer-0 batch";
            return best.isEmpty() ? "no blocker (geosets or hidden sections)" : String.join(" + ", best);
        }

        /** Whether every texture is a file or the object skin (DisplayModelService's rule). */
        static boolean objectSkinFromVariation(com.vibeminer.formats.models.M2Types.Model m) {
            return m.textures().stream().allMatch(t -> t.type() == 0 || t.type() == 2);
        }

        /**
         * The distinct Stand texture weight notes of one World binding (#384), as reason kinds:
         * hidden, full or alpha-scaled at the Stand sample, no Stand pose, or undecided. A binding
         * restored from the store carries no such notes, so a census after a policy change counts
         * them on its first (cold) run only.
         */
        static java.util.TreeSet<String> standWeightNotes(
                com.vibeminer.concepts.models.DisplayModelTypes.Preview preview) {
            var notes = new java.util.TreeSet<String>();
            for (var issue : preview.issues())
                if (issue.startsWith("Stand texture weight") || issue.startsWith("A fading texture weight"))
                    notes.add(reasonKind(issue));
            return notes;
        }

        /**
         * Sampled creature displays whose World binding met a fading additive or blended weight, by
         * the notes {@link #standWeightNotes} lists, split by the World material outcome.
         */
        Map<String, Integer> standWeightTally() {
            var out = new TreeMap<String, Integer>();
            for (var entry : material.entrySet()) {
                var preview = previews.get(entry.getKey());
                var f = facts.get(entry.getKey());
                if (preview == null || f == null || !f.creature()) continue;
                var notes = standWeightNotes(preview);
                if (notes.isEmpty()) continue;
                out.merge((entry.getValue().equals("drawn") ? "drawn: " : "not drawn: ")
                        + String.join("; ", notes), 1, Integer::sum);
            }
            return out;
        }

        /** Distinct creature displays (no extended display info) per World material outcome. */
        Map<String, Integer> materialTally() {
            return tally(material, false);
        }

        /** The World's outcome for one display: its Stand outcome, refined by the conversion. */
        static String worldOutcome(com.vibeminer.concepts.models.DisplayModelTypes.Idle idle,
                com.vibeminer.concepts.models.DisplayModelTypes.Preview preview, Cancellation token) {
            var outcome = standOutcome(idle, preview == null ? null : preview.appearance());
            if (!outcome.equals("Stand pose")) return outcome;
            // Without the display's preview (or its reference) the World converts nothing here.
            if (preview == null || preview.reference() == null || preview.appearance() == null)
                return "Stand pose, preview not recorded";
            var converted = WorldTileCreatures.convert(new com.vibeminer.concepts.models.DisplayModelTypes.Preview(
                    preview.state(), preview.reference(), idle.animation().pose(preview.appearance(), 0),
                    preview.visibleParts(), preview.issues(), preview.restored(), preview.message()),
                    idle.leftOut(), token);
            if (converted.model() == null) return "Stand pose, not drawn: " + converted.refusal().label;
            return converted.model().billboardTriangles() > 0 ? "Stand pose, billboard triangles left out"
                    : "Stand pose";
        }

        /** "Stand pose", or "rest pose: <reason kind>" as the World's conversion decides it. */
        static String standOutcome(
                com.vibeminer.concepts.models.DisplayModelTypes.Idle idle,
                com.vibeminer.rendering.models.MeshAppearance appearance) {
            if (idle == null) return "rest pose: no idle result";
            if (idle.state() != com.vibeminer.concepts.models.DisplayModelTypes.IdleState.AVAILABLE)
                return "rest pose: " + idle.state() + " " + reasonKind(idle.message());
            if (appearance != null && !idle.animation().matches(appearance))
                return "rest pose: the Stand sequence belongs to another rest surface";
            return "Stand pose";
        }

        /** A reason with its standalone numbers as #, so names such as x00 stay readable. */
        static String reasonKind(String reason) {
            return reason.replaceAll("\\b\\d+\\b", "#").strip();
        }

        /** Distinct creature displays (no extended display info) per World Stand outcome. */
        Map<String, Integer> worldStandTally() {
            return worldStandTally(false);
        }

        Map<String, Integer> worldStandTally(boolean resolvedGeosetsOnly) {
            return tally(worldStand, resolvedGeosetsOnly);
        }

        /** Distinct creature displays (no extended display info) per Stand outcome. */
        Map<String, Integer> standTally(boolean resolvedGeosetsOnly) {
            return tally(stand, resolvedGeosetsOnly);
        }

        private Map<String, Integer> tally(Map<Long, String> outcomes, boolean resolvedGeosetsOnly) {
            var counts = new TreeMap<String, Integer>();
            outcomes.forEach(
                    (display, outcome) -> {
                        var f = facts.get(display);
                        if (f == null || !f.creature()) return;
                        if (resolvedGeosetsOnly && f.rule() != null) return;
                        counts.merge(outcome, 1, Integer::sum);
                    });
            return counts;
        }

        /** Distinct creature displays per geoset case and production rule outcome. */
        Map<String, Integer> geosetTally() {
            var counts = new TreeMap<String, Integer>();
            for (var f : facts.values()) {
                if (!f.creature()) continue;
                counts.merge(
                        f.geosetCase() + " -> " + (f.rule() == null ? "drawn" : "refused: " + reasonKind(f.rule())),
                        1,
                        Integer::sum);
            }
            return counts;
        }

        /**
         * For creature displays whose model records no geoset data (the default path): how
         * many of the models' groups 1 and above have x00 only, x01 only, both or neither, how many
         * have one, two or more sections, how many displays draw with an x00 default, an
         * x00-else-x01 default, an x01 default and an "only section, else x01" default, and how
         * many models have group 0 sections other than 0 (hidden by every rule above).
         */
        String defaultCensus() {
            int displays = 0, x00 = 0, x01Fallback = 0, x01 = 0, onlyOrX01 = 0, group0Variants = 0;
            int onlyX00 = 0, onlyX01 = 0, both = 0, neither = 0, one = 0, two = 0, more = 0;
            for (var f : facts.values()) {
                if (!f.creature() || f.geosetDataId() == null || f.geosetDataId() > 0) continue;
                displays++;
                boolean allX00 = true, allX00OrX01 = true, allX01 = true, allOnlyOrX01 = true;
                for (var group : f.groups().entrySet()) {
                    var values = group.getValue();
                    if (group.getKey() < 1) {
                        if (values.stream().anyMatch(v -> v != 0)) group0Variants++;
                        continue;
                    }
                    boolean a = values.contains(0), b = values.contains(1);
                    if (a && b) both++;
                    else if (a) onlyX00++;
                    else if (b) onlyX01++;
                    else neither++;
                    if (values.size() == 1) one++;
                    else if (values.size() == 2) two++;
                    else more++;
                    allX00 &= a;
                    allX00OrX01 &= a || b;
                    allX01 &= b;
                    allOnlyOrX01 &= b || values.size() == 1;
                }
                if (allX00) x00++;
                if (allX00OrX01) x01Fallback++;
                if (allX01) x01++;
                if (allOnlyOrX01) onlyOrX01++;
            }
            return String.format(
                    Locale.ROOT,
                    "%d displays; groups 1+: x00 only %d, x01 only %d, both %d, neither %d; with one"
                            + " section %d, two %d, three or more %d; displays whose every group 1+ has"
                            + " x00 %d, x00 or x01 %d, x01 %d, x01 or a single section %d; displays with"
                            + " group 0 sections other than 0: %d",
                    displays, onlyX00, onlyX01, both, neither, one, two, more, x00, x01Fallback, x01,
                    onlyOrX01, group0Variants);
        }

        /**
         * The section values of each geoset group 1 and above (for example "1,2,3"), over creature
         * displays whose model records no geoset data: groups per value set.
         */
        Map<String, Integer> groupPatterns() {
            var counts = new TreeMap<String, Integer>();
            for (var f : facts.values()) {
                if (!f.creature() || f.geosetDataId() == null || f.geosetDataId() > 0) continue;
                for (var group : f.groups().entrySet()) {
                    if (group.getKey() < 1) continue;
                    var text = new StringBuilder();
                    for (int v : group.getValue()) text.append(text.isEmpty() ? "" : ",").append(v);
                    counts.merge(text.toString(), 1, Integer::sum);
                }
            }
            return counts;
        }
    }

    /**
     * One display's geoset facts: the model's CreatureGeosetDataID (null when unreadable), its own
     * geoset rows (null when unknown), the production rule's refusal (null when it draws), and per
     * geoset group the section values (ID % 100) the model has.
     */
    record DisplayModelFacts(
            boolean creature,
            Long geosetDataId,
            Map<Integer, Integer> rows,
            String rule,
            Map<Integer, java.util.SortedSet<Integer>> groups) {
        static DisplayModelFacts of(
                com.vibeminer.concepts.models.DisplayModelTypes.Reference reference,
                com.vibeminer.rendering.models.PreviewMesh mesh) {
            var groups = new TreeMap<Integer, java.util.SortedSet<Integer>>();
            for (var part : mesh.parts())
                groups.computeIfAbsent(part.id() / 100, k -> new java.util.TreeSet<>()).add(part.id() % 100);
            boolean creature =
                    reference.extendedDisplayInfoId() != null && reference.extendedDisplayInfoId() == 0;
            return new DisplayModelFacts(
                    creature,
                    reference.creatureGeosetDataId(),
                    reference.displayGeosets(),
                    WorldTileCreatures.visibleGeosets(reference, mesh).refusal(),
                    groups);
        }

        String geosetCase() {
            if (geosetDataId == null) return "ID unreadable";
            String id = geosetDataId > 0 ? "ID > 0" : "ID 0";
            String own = rows == null ? "rows unknown" : rows.isEmpty() ? "no rows" : "rows";
            return id + ", " + own;
        }
    }

    /**
     * A build-wide census of creature displays (#377, #455), independent of spawns: the tables'
     * geoset cases for every creature display (no extended display info), then the production
     * preview and Stand sequence of one creature display per creature model, in a fixed
     * pseudo-random model order, until {@code -Pprobe.limit} models or the deadline. Like the
     * creatures mode, it may add derived entries to the copy's generated store.
     */
    private static void displays(WorldMeasurementOpen opened) throws Exception {
        var issues = new ArrayList<String>();
        var tables = new com.vibeminer.concepts.models.TableProjection(opened.tables());
        int bound = 1 << 21;
        var displayData = tables.read("CreatureDisplayInfo", bound, Cancellation.NONE, issues);
        var modelData = tables.read("CreatureModelData", bound, Cancellation.NONE, issues);
        var rowData = tables.read("CreatureDisplayInfoGeosetData", bound, Cancellation.NONE, issues);
        if (!modelData.has("CreatureGeosetDataID"))
            issues.add(
                    "CreatureModelData has no CreatureGeosetDataID column; every model reads as 0, as"
                            + " the production reference does");
        var geosetDataId = new java.util.HashMap<Long, Long>();
        for (var row : modelData.rows())
            geosetDataId.put(
                    row.id(),
                    modelData.has("CreatureGeosetDataID")
                            ? modelData.number(row, "CreatureGeosetDataID")
                            : 0L);
        var withRows = new java.util.HashSet<Long>();
        for (var row : rowData.rows()) withRows.add(rowData.number(row, "CreatureDisplayInfoID"));
        var census = new DisplayTableCensus();
        // One creature display per model: the lowest display ID.
        var perModel = new TreeMap<Long, Long>();
        for (var row : displayData.rows()) {
            long model = displayData.number(row, "ModelID");
            long extended =
                    displayData.has("ExtendedDisplayInfoID")
                            ? displayData.number(row, "ExtendedDisplayInfoID")
                            : 0L;
            census.add(extended != 0, geosetDataId.get(model), withRows.contains(row.id()));
            if (extended == 0 && geosetDataId.containsKey(model)) perModel.merge(model, row.id(), Math::min);
        }
        say(
                String.format(
                        Locale.ROOT,
                        "PROBE displays: tables CreatureDisplayInfo %d rows, CreatureModelData %d rows (%d with"
                                + " CreatureGeosetDataID > 0), CreatureDisplayInfoGeosetData %d rows for %d displays;"
                                + " issues %s",
                        displayData.rows().size(),
                        modelData.rows().size(),
                        geosetDataId.values().stream().filter(v -> v > 0).count(),
                        rowData.rows().size(),
                        withRows.size(),
                        issues));
        say("PROBE displays: creature displays by geoset case (tables, every display)");
        for (var entry : census.counts.entrySet())
            say(String.format(Locale.ROOT, "PROBE   %6d  %s", entry.getValue(), entry.getKey()));
        var order = sampleOrder(perModel.keySet());
        int limit = Integer.getInteger("probe.limit", Integer.MAX_VALUE);
        var previews = new WorldTileCreatures.DisplayPreviews(opened::displays, () -> "probe");
        var display = new DisplayCensus(previews);
        var unavailable = new TreeMap<String, Integer>();
        int asked = 0;
        long began = System.nanoTime();
        try {
            for (long model : order) {
                if (asked >= limit || !beforeDeadline()) break;
                asked++;
                sample(display, perModel.get(model), unavailable);
            }
        } finally {
            previews.close();
        }
        if (asked < order.size()) incomplete = true;
        say(
                String.format(
                        Locale.ROOT,
                        "PROBE displays: sampled %d of %d creature models (one display each) in %.1f s;"
                                + " %d with a static preview",
                        asked,
                        order.size(),
                        seconds(System.nanoTime() - began),
                        display.facts.size()));
        say("PROBE displays: sampled models without a static preview");
        for (var entry : unavailable.entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: sampled creature displays by Stand outcome (all with a preview)");
        for (var entry : display.standTally(false).entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: sampled creature displays by World material outcome (static model)");
        for (var entry : display.materialTally().entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: World material refusals by the fewest blockers of any layer-0 batch");
        for (var entry : display.refusalBlockers(opened.files()).entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: models with a fading additive or blended weight, by its Stand-sample notes");
        for (var entry : display.standWeightTally().entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: drawn static models by sorted surfaces (blend 2 blended, blend 4 additive)");
        for (var entry : display.sortedKinds(Cancellation.NONE).entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: sampled creature displays by World Stand outcome (all with a preview)");
        for (var entry : display.worldStandTally().entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: sampled creature displays by Stand outcome (geosets resolved)");
        for (var entry : display.standTally(true).entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: sampled creature displays by geoset case and rule outcome");
        for (var entry : display.geosetTally().entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
        say("PROBE displays: sampled geoset default census (ID 0): " + display.defaultCensus());
        say("PROBE displays: sampled geoset groups 1+ by section values (ID 0)");
        for (var entry : display.groupPatterns().entrySet())
            say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
    }

    /**
     * Asks one display's preview and Stand sequence through the census. A preview without an
     * appearance, or a preview call that throws, is counted in {@code unavailable} by reason; an
     * idle call that throws is a rest pose with its failure, as the World's conversion records it.
     * One failing model never ends the census; cancellation still propagates.
     */
    static void sample(DisplayCensus census, long id, Map<String, Integer> unavailable) throws Exception {
        com.vibeminer.concepts.models.DisplayModelTypes.Preview preview;
        try {
            preview = census.preview(id, Cancellation.NONE);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (Exception failed) {
            unavailable.merge("preview failed: " + failed.getClass().getSimpleName(), 1, Integer::sum);
            return;
        }
        if (preview == null || preview.appearance() == null) {
            unavailable.merge(
                    DisplayCensus.reasonKind(
                            preview == null || preview.message() == null
                                    ? "no static preview"
                                    : preview.message()),
                    1,
                    Integer::sum);
            return;
        }
        try {
            census.stands(id, Cancellation.NONE);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (Exception failed) {
            census.stand.put(id, "rest pose: Stand pose failed: " + failed.getClass().getSimpleName());
            census.worldStand.put(id, "rest pose: Stand pose failed: " + failed.getClass().getSimpleName());
        }
    }

    /** Creature displays (no extended display info) per geoset case, from the tables alone. */
    static final class DisplayTableCensus {
        final Map<String, Integer> counts = new TreeMap<>();

        void add(boolean character, Long geosetDataId, boolean rows) {
            String key;
            if (character) key = "character display (extended display info), not counted by case";
            else if (geosetDataId == null) key = "model not in CreatureModelData";
            else key = (geosetDataId > 0 ? "ID > 0" : "ID 0") + ", " + (rows ? "rows" : "no rows");
            counts.merge(key, 1, Integer::sum);
        }
    }

    /** A fixed pseudo-random order of the given IDs (the same on every run). */
    static List<Long> sampleOrder(java.util.Collection<Long> ids) {
        var order = new ArrayList<>(ids);
        order.sort(
                java.util.Comparator.comparingLong((Long id) -> id * 0x9E3779B97F4A7C15L)
                        .thenComparingLong(id -> id));
        return order;
    }

    /**
     * Prepares each scenario's tiles as the World does, buildings and doodads first and then
     * creatures in what they leave (the production bridge and caps), with the production creature
     * chooser, display previews and humanoid NPC appearances, and counts what is drawn and why the
     * rest is not. {@code -Pprobe.passes} (default 2) repeats the tiles so appearances that could
     * not be read on the first pass are asked again, as the World asks on a later tile.
     *
     * <p>Unlike the other modes, this one may add derived entries (decoded images, model
     * previews, race appearances) to the measurement copy's generated store, as the application
     * does: nothing is staged, published or downloaded, but a second run starts warm.
     */
    private static void creatures(
            WorldMeasurementOpen opened, BuildWorld handle, WorldService.Catalog catalog)
            throws Exception {
        var scenarios =
                Scenario.parse(
                        System.getProperty("probe.scenarios", "elwynn:0:49:31,stormwind:0:48:30"));
        int size = Integer.getInteger("probe.size", 3);
        int passes = Integer.getInteger("probe.passes", 2);
        var sources = opened.sources();
        if (sources == null) throw new IllegalStateException("No data sources in this open");
        var detail = WorldStreamGpuOwner.IMAGE_DETAIL;
        var source =
                handle.terrainStreaming()
                        .orElseThrow(() -> new IllegalStateException("No terrain streaming"));
        var build = opened.build;
        var spawns = sources.worldSpawns(build, Cancellation.NONE);
        say(
                "PROBE creatures: world spawns "
                        + spawns.state()
                        + ", "
                        + spawns.spawns()
                        + " spawns; issues "
                        + spawns.issues());
        try (var measured = busy(() -> opened.openCreatures(Cancellation.NONE))) {
            say("PROBE creatures: lookup warmup issues " + measured.warmup());

            var npcs = new NpcAnswers(WorldTileCreatures.npcs(measured::races));
            // The World's chooser: the client record, then EG Link's observed displays.
            var choices = new ChoiceReasons(new WorldNpcDisplays(measured::creatures,
                    NpcPreviews.egLinkBatch(sources, build, "enUS"), build.version()));
            var displays =
                    new DisplayCensus(
                            new WorldTileCreatures.DisplayPreviews(opened::displays, () -> "probe"));
            var tiles =
                    new WorldTileCreatures(
                            token -> sources.worldSpawns(build, token),
                            () -> "probe",
                            choices,
                            displays,
                            npcs);
            try {
                for (var scenario : scenarios) {
                    if (!beforeDeadline()) {
                        say("PROBE skipped " + scenario.name() + ": deadline");
                        incomplete = true;
                        continue;
                    }
                    var map =
                            catalog.maps().stream()
                                    .filter(m -> m.id() == scenario.map())
                                    .findFirst()
                                    .orElseThrow();
                    var inventory = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES);
                    var area = new ArrayList<WorldFormats.Tile>();
                    int half = size / 2;
                    for (var tile : inventory.world().tiles()) {
                        if (Math.abs(tile.x() - scenario.x()) <= half
                                && Math.abs(tile.y() - scenario.y()) <= half) area.add(tile);
                    }
                    var scope = source.createScope(map, Cancellation.NONE);
                    source.activate(scope);
                    for (int pass = 1; pass <= passes && beforeDeadline(); pass++) {
                        long began = System.nanoTime();
                        var sum = WorldTileCreatures.Summary.NONE;
                        int done = 0;
                        for (var tile : area) {
                            if (!beforeDeadline()) break;
                            source.prepareAndWrite(
                                            scope,
                                            tile,
                                            Cancellation.NONE,
                                            (borrow, token) -> () -> {})
                                    .close();
                            var copy =
                                    WorldStreamSourceBridge.prepareExterior(
                                            source,
                                            scope,
                                            tile,
                                            Cancellation.NONE,
                                            (builder, owned, token) ->
                                                    tiles.append(builder, map.id(), owned, token),
                                            detail);
                            if (copy.creatures() != null) sum = sum.plus(copy.creatures());
                            done++;
                        }
                        if (done < area.size()) incomplete = true;
                        say(creatureLine(scenario, pass, done, area.size(), sum, began));
                    }
                }
                // The Creature table as the chooser reads it: its state and row counts, through the
                // lookup of one NPC that was not qualified, so an all-absent result can be told apart
                // from an unreadable table.
                var first = choices.reasons.keySet().stream().sorted().findFirst();
                if (first.isPresent()) {
                    var probeRow =
                            measured.creatures()
                                    .getData(
                                            first.get(),
                                            com.vibeminer.formats.tables.TableTypes.Priority.BACKGROUND,
                                            Cancellation.NONE)
                                    .join();
                    var table = probeRow.creature();
                    say(
                            "PROBE creatures: Creature table "
                                    + (table == null
                                            ? "not resolved"
                                            : table.state() + ", counts " + table.counts())
                                    + "; NPC "
                                    + first.get()
                                    + " "
                                    + probeRow.state());
                }
                say("PROBE creatures: distinct NPC IDs not qualified " + choices.reasons.size());
                for (var entry : choices.tally().entrySet())
                    say(
                            String.format(
                                    Locale.ROOT,
                                    "PROBE   %4d  %s (for example: %s)",
                                    entry.getValue(),
                                    entry.getKey(),
                                    choices.examples.get(entry.getKey())));
                say("PROBE creatures: distinct humanoid NPC displays asked " + npcs.outcomes.size());
                for (var entry : npcs.tally().entrySet())
                    say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
                // The World asks the World profile (#384): its outcomes are what the tiles drew.
                say("PROBE creatures: distinct creature displays by World Stand outcome (all with a preview)");
                for (var entry : displays.worldStandTally(false).entrySet())
                    say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
                say("PROBE creatures: distinct creature displays by World Stand outcome (geosets resolved)");
                for (var entry : displays.worldStandTally(true).entrySet())
                    say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
                say("PROBE creatures: distinct creature displays by geoset case and rule outcome");
                for (var entry : displays.geosetTally().entrySet())
                    say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
                say("PROBE creatures: geoset default census (ID 0): " + displays.defaultCensus());
                say("PROBE creatures: geoset groups 1+ by section values (ID 0)");
                for (var entry : displays.groupPatterns().entrySet())
                    say(String.format(Locale.ROOT, "PROBE   %4d  %s", entry.getValue(), entry.getKey()));
            } finally {
                tiles.close();
            }
        }
    }

    private static String creatureLine(
            Scenario scenario,
            int pass,
            int done,
            int tiles,
            WorldTileCreatures.Summary sum,
            long began) {
        var npc = sum.npcs();
        var refused = new StringBuilder();
        for (var count : WorldTileCreatures.Count.values()) {
            if (count == WorldTileCreatures.Count.DRAWN || sum.count(count) == 0) continue;
            refused.append(refused.isEmpty() ? "" : ", ")
                    .append(count.name())
                    .append(' ')
                    .append(sum.count(count));
        }
        return String.format(
                Locale.ROOT,
                "PROBE creatures %s pass %d: %d of %d tiles in %.1f s; spawns drawn %d in %d models"
                        + " (humanoid NPCs %d: %d with %d surfaces left out, %d item models and %d"
                        + " item records not drawn); not drawn: %s%nPROBE   status line:%s",
                scenario.name(),
                pass,
                done,
                tiles,
                seconds(System.nanoTime() - began),
                sum.drawn(),
                sum.models(),
                npc.drawn(),
                npc.withOmittedSurfaces(),
                npc.omittedSurfaces(),
                npc.attachments(),
                npc.omittedItems(),
                refused.isEmpty() ? "none" : refused,
                WorldStreamSourceController.creatureDetail(sum, done));
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
                        System.getProperty("probe.scenarios", "elwynn:0:49:31,stormwind:0:48:30"));
        Platform.startup(() -> Platform.setImplicitExit(false));
        var gpu = GpuPreviewService.application();
        try {
            for (var scenario : scenarios) {
                if (System.nanoTime() > DEADLINE - TimeUnit.SECONDS.toNanos(45)) {
                    say("PROBE skipped " + scenario.name() + ": deadline");
                    incomplete = true;
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
                                                                + inventory.world().tiles().size()
                                                                + "; first recorded: "
                                                                + inventory.world().tiles().stream()
                                                                        .limit(24)
                                                                        .map(t -> t.x() + "," + t.y())
                                                                        .toList()));
                new StreamRun(scenario, handle, catalog, map, inventory, origin, gpu).run();
            }
        } finally {
            busy(() -> gpu.closeAsync().get(HELPER_CLOSE_SECONDS, TimeUnit.SECONDS));
            Platform.exit();
        }
    }

    /**
     * The recorded sky at a native position at midnight and noon, through the production index:
     * its load failure, or each result's state, coverage and which parts it has, plus the
     * index's source issues.
     */
    static String sky(BuildWorld handle, long mapId, double[] position) throws Exception {
        WorldSky.Index index;
        try {
            index = handle.skyIndex(Cancellation.NONE).get(2, TimeUnit.MINUTES);
        } catch (ExecutionException failed) {
            return "UNAVAILABLE (" + failed.getCause().getMessage() + ")";
        }
        var text = new StringBuilder();
        for (int time : new int[] {0, 1440}) {
            var result = index.at(mapId, position[0], position[1], position[2], time);
            var sample = result.sample();
            text.append(
                    String.format(
                            Locale.ROOT,
                            "%s %s coverage %.2f fog %s light %s water %s (%s); ",
                            time == 0 ? "00:00" : "12:00",
                            result.state(),
                            result.coverage(),
                            sample != null && sample.hasFog(),
                            sample != null && sample.hasLight(),
                            sample != null && sample.hasWater(),
                            result.label()));
        }
        return text.append(index.sourceIssues().size())
                .append(" source issues")
                .append(index.sourceIssues().isEmpty() ? "" : ": " + index.sourceIssues())
                .toString();
    }

    /** One controller lifetime over one neighbourhood; everything controller-facing on FX. */
    private static final class StreamRun {
        final Scenario scenario;
        final BuildWorld handle;
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
            this.handle = handle;
            this.inventory = inventory;
            this.origin = origin;
            var request =
                    new WorldStreamSourceController.Request(
                            1, handle, map, inventory, origin, null, catalog.areaIndex());
            var detail = WorldStreamGpuOwner.IMAGE_DETAIL;
            // As the World view: every map draws its interiors (#280, #383), and their roots
            // carry portal sets the renderer culls with.
            var interiors = probeInteriors(map);
            WorldStreamSourceBridge.ExteriorPreparer preparer =
                    (source, scope, tile, cancellation, formats, creatureBytes) ->
                            WorldStreamSourceBridge.prepareExterior(
                                    source,
                                    scope,
                                    tile,
                                    cancellation,
                                    null,
                                    detail,
                                    interiors,
                                    formats);
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
                            "PROBE scenario %s map %d centre tile %d,%d (view distance %s, exteriors %s, creatures %s, object textures %s)",
                            scenario.name(),
                            scenario.map(),
                            scenario.x(),
                            scenario.y(),
                            WorldStreamGpuOwner.VIEW_DISTANCE,
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
            try {
                measure(allocated);
            } finally {
                // Every path, including failures and the deadline, retires the controller (and
                // with it the helper session and its private files) before the probe moves on.
                fx(
                        () -> {
                            timer.stop();
                            return null;
                        });
                busy(
                        () ->
                                controller
                                        .closeAsync()
                                        .get(CONTROLLER_CLOSE_SECONDS, TimeUnit.SECONDS));
            }
        }

        private void measure(long allocated) throws Exception {
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
            if (!(desired > 0 && prepared == desired && phase.equals("READY")
                    && prepares() >= desired)) {
                incomplete = true;
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
            var position = fx(() -> camera == null ? null : new double[] {camera.x(), camera.y(), camera.z()});
            say("PROBE sky: " + (position == null ? "no camera" : sky(handle, scenario.map(), position)));
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
            if (!(prepared == desired && phase.equals("READY"))) incomplete = true;
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
                    WorldStreamNeighborhood.select(
                            inventory.world().tiles(),
                            x,
                            y,
                            camera.yaw(),
                            WorldStreamGpuOwner.VIEW_DISTANCE);
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
            if (tiles < all.size()) incomplete = true;
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

    // ---- Water outcomes over whole maps (#171) ----------------------------------------------

    /** An issue's kind: its text with every number replaced by {@code #}, so counts group it. */
    static String issueKind(String issue) {
        return issue.replaceAll("\\d+", "#").strip();
    }

    /**
     * Prepares every {@code probe.stride}-th recorded tile of each map (default every 4th) through
     * the production terrain and water preparation, without a helper, and counts the water facts:
     * tile states and reasons, declared, admitted and unresolved layers, cells and issue kinds.
     */
    private static void water(BuildWorld handle, WorldService.Catalog catalog) throws Exception {
        var source =
                handle.terrainStreaming()
                        .orElseThrow(() -> new IllegalStateException("No terrain streaming"));
        var water =
                handle.waterPreview()
                        .orElseThrow(() -> new IllegalStateException("No water preview"));
        int stride = Math.max(1, Integer.getInteger("probe.stride", 4));
        for (String mapText : System.getProperty("probe.maps", "0,1").split(",")) {
            long mapId = Long.parseLong(mapText.strip());
            var map = catalog.maps().stream().filter(m -> m.id() == mapId).findFirst().orElse(null);
            if (map == null) {
                say("PROBE map " + mapId + " not in catalog");
                continue;
            }
            var all = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES).world().tiles();
            var scope = source.createScope(map, Cancellation.NONE);
            source.activate(scope);
            var states = new TreeMap<String, Integer>();
            var issues = new TreeMap<String, Integer>();
            long declared = 0, admitted = 0, unresolved = 0, cells = 0;
            // The bridge's copy: quads, the tile maximum, capped tiles, and the quads a
            // planar-only merge (ignoring depth) would have drawn.
            // total, max, capped tiles, planar-only total, planar-only max, tiles with coarsened depth
            long[] quads = new long[6];
            // The tiles this map's stride selects; fewer done means the budget ran out.
            int tiles = 0, sampled = (all.size() + stride - 1) / stride;
            long began = System.nanoTime();
            long budget = TimeUnit.SECONDS.toNanos(Long.getLong("probe.mapSeconds", 240));
            for (int at = 0; at < all.size(); at += stride) {
                if (System.nanoTime() - began > budget || !beforeDeadline()) break;
                String key;
                try (var written =
                        source.prepareAndWrite(
                                scope, all.get(at), Cancellation.NONE, (borrow, token) -> () -> {})) {
                    try (var prepared =
                            water.prepareAndWrite(written, Cancellation.NONE, (borrow, token) -> {
                                var copy = WorldStreamSourceBridge.WaterCopy.of(borrow, token);
                                int drawn = copy.water().cells();
                                quads[0] += drawn;
                                quads[1] = Math.max(quads[1], drawn);
                                if (copy.capped()) quads[2]++;
                                if (copy.finest() > 1) quads[5]++;
                                int planarOnly = 0;
                                for (int l = 0; l < borrow.layers(); l++) {
                                    boolean full = borrow.width(l) == 8 && borrow.height(l) == 8
                                            && borrow.mask(l) == -1L && borrow.planar(l);
                                    planarOnly += full ? 1 : Long.bitCount(borrow.mask(l));
                                }
                                quads[3] += planarOnly;
                                quads[4] = Math.max(quads[4], planarOnly);
                                return copy;
                            })) {
                        var facts = prepared.facts();
                        key = facts.state() + "/" + facts.reason();
                        declared += facts.declaredLayers();
                        admitted += facts.admittedLayers();
                        unresolved += facts.unresolvedLayers();
                        cells += facts.cells();
                        for (String issue : facts.issues()) {
                            // Kinds only: the text before any number.
                            issues.merge(issueKind(issue), 1, Integer::sum);
                        }
                    } catch (RuntimeException refused) {
                        key = "refused/" + refused.getClass().getSimpleName();
                    }
                } catch (RuntimeException terrainFailed) {
                    key = "terrain-failed/" + terrainFailed.getClass().getSimpleName();
                }
                states.merge(key, 1, Integer::sum);
                tiles++;
            }
            if (tiles < sampled) incomplete = true;
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE water map %d (%s): %d of %d sampled tiles (every %d of %d) in %.1f s;"
                                    + " layers declared %d, admitted %d, unresolved %d; cells %d",
                            mapId,
                            map.directory(),
                            tiles,
                            sampled,
                            stride,
                            all.size(),
                            seconds(System.nanoTime() - began),
                            declared,
                            admitted,
                            unresolved,
                            cells));
            say("PROBE   water states/reasons (one per tile) " + states);
            say("PROBE   water issue kinds " + issues);
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE   water quads %d (tile max %d of %d, capped tiles %d, coarsened depth tiles %d);"
                                    + " planar-only merge would draw %d (tile max %d)",
                            quads[0],
                            quads[1],
                            com.vibeminer.rendering.bgfx.WorldStreamTileWriter.Water.MAX_CELLS,
                            quads[2],
                            quads[5],
                            quads[3],
                            quads[4]));
        }
    }

    /** Models across one scenario's tiles: distinct roots and placements per root (#382). */
    static final class ModelUsage {
        final Map<Long, int[]> placements = new TreeMap<>(); // rootId -> {placements, tiles, doodad}
        final Map<Long, Integer> ranges = new TreeMap<>();
        int tiles;

        void add(com.vibeminer.concepts.world.WorldExteriorObjects.TileObjects set) {
            tiles++;
            var models = set.models();
            var seen = new java.util.HashSet<Long>();
            for (var placement : set.placements()) {
                var model = models.get(placement.model());
                long root = model.rootId();
                var entry = placements.computeIfAbsent(root, k -> new int[3]);
                entry[0]++;
                entry[2] = model.doodad() ? 1 : 0;
                if (seen.add(root)) entry[1]++;
                int r = 0;
                for (var mesh : model.meshes()) r += mesh.ranges().size();
                ranges.put(root, r);
            }
        }

        void print(String name) {
            long draws = 0, instanced = 0;
            int doodadModels = 0;
            for (var entry : placements.entrySet()) {
                int r = ranges.getOrDefault(entry.getKey(), 0);
                draws += (long) entry.getValue()[0] * r;
                instanced += r;
                doodadModels += entry.getValue()[2];
            }
            say(
                    String.format(
                            Locale.ROOT,
                            "PROBE models %s: %d tiles, %d distinct models (%d buildings, %d"
                                    + " doodads); per-placement draws %d vs one instanced draw per"
                                    + " model and range %d (%.1fx)",
                            name,
                            tiles,
                            placements.size(),
                            placements.size() - doodadModels,
                            doodadModels,
                            draws,
                            instanced,
                            instanced == 0 ? 0.0 : (double) draws / instanced));
            var top =
                    placements.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                            .limit(10)
                            .toList();
            var line = new StringBuilder("PROBE   top models by placements (placements/tiles/ranges):");
            for (var entry : top) {
                line.append(
                        String.format(
                                Locale.ROOT,
                                " %s %d/%d/%d;",
                                entry.getValue()[2] == 1 ? "doodad" : "building",
                                entry.getValue()[0],
                                entry.getValue()[1],
                                ranges.getOrDefault(entry.getKey(), 0)));
            }
            say(line.toString());
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
