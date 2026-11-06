package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldPlacements;
import com.vibeminer.store.ReadTimings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Scratch real-data probe: per-source read latency through the World read sequence. */
public final class FileReadLatencyProbe {
    private FileReadLatencyProbe() {}

    public static void main(String[] args) throws Exception {
        var watchdog =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        TimeUnit.SECONDS.sleep(540);
                                        System.out.println("PROBE deadline");
                                        Runtime.getRuntime().halt(3);
                                    } catch (InterruptedException ignored) {
                                    }
                                });
        int code = 0;
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            code = 2;
        }
        System.out.flush();
        watchdog.interrupt();
        Runtime.getRuntime().halt(code);
    }

    private static void run() throws Exception {
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
            var work = (NativePreparationWork) job.work();
            BuildWorld world = work.worldHandle();
            BuildFiles files = work.filesHandle();
            System.out.printf(
                    Locale.ROOT,
                    "PROBE opened in %.1f s%n",
                    (System.nanoTime() - opening) / 1e9);
            var catalog = world.catalog(Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var map =
                    catalog.maps().stream().filter(m -> m.id() == 0).findFirst().orElseThrow();
            var tiles = world.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES).world().tiles();
            int cx = Integer.getInteger("probe.x", 31), cy = Integer.getInteger("probe.y", 49);
            var tileIds = new LinkedHashSet<Long>();
            var modelIds = new LinkedHashSet<Long>();
            for (var tile : tiles) {
                if (Math.abs(tile.x() - cx) > 1 || Math.abs(tile.y() - cy) > 1) continue;
                for (long id : new long[] {tile.rootId(), tile.obj0Id(), tile.tex0Id()}) {
                    if (id > 0) tileIds.add(id);
                }
                if (tile.obj0Id() > 0) {
                    byte[] obj0 = readSource(files, tile.obj0Id(), 8 << 20, null);
                    if (obj0 == null) continue;
                    var meta = WorldPlacements.readObj0(obj0, () -> false);
                    for (var d : meta.doodads())
                        if (d.reference().kind() == WorldPlacements.ReferenceKind.FILE_DATA_ID)
                            modelIds.add(d.reference().value());
                    for (var o : meta.worldObjects())
                        if (o.reference().kind() == WorldPlacements.ReferenceKind.FILE_DATA_ID)
                            modelIds.add(o.reference().value());
                }
            }
            var ids = new ArrayList<Long>(tileIds);
            ids.addAll(modelIds);
            int limit = Integer.getInteger("probe.files", 400);
            if (ids.size() > limit) ids = new ArrayList<>(ids.subList(0, limit));
            System.out.printf(Locale.ROOT, "PROBE %d tile files, %d models; reading %d%n",
                    tileIds.size(), modelIds.size(), ids.size());
            ReadTimings.enabled = true;
            pass("cold-seq", files, ids, 1);
            pass("warm-seq", files, ids, 1);
            pass("warm-par4", files, ids, 4);
            pass("warm-par8", files, ids, 8);
        } finally {
            ReadTimings.enabled = false;
            service.closeAsync().get(2, TimeUnit.MINUTES);
        }
    }

    private static void pass(String name, BuildFiles files, List<Long> ids, int threads)
            throws Exception {
        ReadTimings.reset();
        long[] each = new long[ids.size()];
        long[] bytes = new long[1];
        AtomicInteger next = new AtomicInteger(), failed = new AtomicInteger();
        long began = System.nanoTime();
        var workers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            workers.add(
                    Thread.startVirtualThread(
                            () -> {
                                for (int i; (i = next.getAndIncrement()) < ids.size(); ) {
                                    long s = System.nanoTime();
                                    try {
                                        byte[] got = readSource(files, ids.get(i), 16 << 20, each);
                                        if (got == null) failed.incrementAndGet();
                                        else synchronized (bytes) { bytes[0] += got.length; }
                                    } catch (Exception e) {
                                        failed.incrementAndGet();
                                    }
                                    each[i] = System.nanoTime() - s;
                                }
                            }));
        }
        for (var w : workers) w.join();
        long wall = System.nanoTime() - began;
        long[] sorted = each.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(each).average().orElse(0) / 1e6;
        System.out.printf(
                Locale.ROOT,
                "PASS %s threads=%d files=%d failed=%d bytes=%d wall=%.0f ms mean=%.2f ms p50=%.2f p90=%.2f p99=%.2f max=%.2f%n",
                name, threads, ids.size(), failed.get(), bytes[0], wall / 1e6, mean,
                sorted[sorted.length / 2] / 1e6, sorted[sorted.length * 9 / 10] / 1e6,
                sorted[Math.min(sorted.length - 1, sorted.length * 99 / 100)] / 1e6,
                sorted[sorted.length - 1] / 1e6);
        System.out.print(ReadTimings.report());
    }

    /** Mirrors the World source read: stat, open, chunked reads, EOF read, stat again. */
    private static byte[] readSource(BuildFiles files, long id, int limit, long[] unused)
            throws Exception {
        var ref = FileTypes.FileRef.id(id);
        var tok = Cancellation.NONE;
        long t = ReadTimings.start();
        var before = files.stat(ref, tok).join();
        ReadTimings.stop("probe.stat1", t);
        if (before.state() != FileTypes.FileLookupState.FOUND) return null;
        if (before.file().size() != null && before.file().size() > limit) return null;
        t = ReadTimings.start();
        var opened = files.openRead(ref, tok).join();
        ReadTimings.stop("probe.open", t);
        if (opened.stream() == null) return null;
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE) return null;
            var buffer = ByteBuffer.allocate(Math.toIntExact(stream.length()));
            t = ReadTimings.start();
            while (buffer.hasRemaining()) {
                if (stream.read(buffer, tok).join() <= 0) return null;
            }
            stream.read(ByteBuffer.allocate(1), tok).join();
            ReadTimings.stop("probe.readAll", t);
            t = ReadTimings.start();
            files.stat(ref, tok).join();
            ReadTimings.stop("probe.stat2", t);
            return buffer.array();
        }
    }
}
