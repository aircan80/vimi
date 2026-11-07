package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import java.util.HashSet;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Private: counts terrain diffuses the 1 MiB source cap refuses on a prepared build's maps. */
public final class TerrainDiffuseCapProbe {
    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable failure) {
            System.out.println("PROBE failed: " + failure);
            failure.printStackTrace(System.out);
            System.out.flush();
            System.exit(2);
        }
    }

    private static void run() throws Exception {
        var service = BuildApplication.open(BuildApplication.defaultDataDirectory()).join();
        try {
            BuildInfo build = null;
            String locale = null;
            for (var candidate : service.listBuilds().get(2, TimeUnit.MINUTES)) {
                String summary = service.historicalPreparation(candidate);
                if (summary != null && candidate.version().contains(System.getProperty("probe.build", "70790"))
                        && !candidate.observations().isEmpty()) {
                    build = candidate;
                    locale = summary.substring(summary.lastIndexOf(" · ") + 3).strip();
                }
            }
            var region = build.observations().getFirst();
            var job = service.preparation().find(build, region, locale);
            if (job == null) job = service.preparation().start(build, region, locale);
            while (!(job.work() instanceof NativePreparationWork w && w.worldHandle() != null)) Thread.sleep(200);
            var handle = ((NativePreparationWork) job.work()).worldHandle();
            var catalog = handle.catalog(Cancellation.NONE).get(5, TimeUnit.MINUTES);
            var reasons = new TreeMap<String, Integer>();
            var failed = new HashSet<Long>();
            var limited = new HashSet<Long>();
            var ok = new HashSet<Long>();
            int tiles = 0;
            for (String mapText : System.getProperty("probe.maps", "2991").split(",")) {
                long mapId = Long.parseLong(mapText.strip());
                var found = catalog.maps().stream().filter(m -> m.id() == mapId).findFirst();
                if (found.isEmpty()) { System.out.println("PROBE map " + mapId + " not in catalog"); continue; }
                var map = found.get();
                System.out.println("PROBE map " + mapId);
                var all = handle.inventory(map, Cancellation.NONE).get(5, TimeUnit.MINUTES).world().tiles();
                for (int at = 0; at < all.size(); at += 9) {
                    var batch = all.subList(at, Math.min(all.size(), at + 9));
                    try (var result = handle.terrainTextures(map, batch, Cancellation.NONE, p -> {})
                            .get(5, TimeUnit.MINUTES)) {
                        for (var tile : result.tiles()) {
                            tiles++;
                            if (tile.receipt() != null)
                                for (var mip : tile.receipt().diffuse()) ok.add(mip.file().fileId());
                            for (var issue : tile.issues()) {
                                if (issue.fileId() <= 0) continue;
                                reasons.merge(issue.reason().name(), 1, Integer::sum);
                                failed.add(issue.fileId());
                                if (issue.reason() == WorldTerrainTextureSource.Reason.LIMIT) limited.add(issue.fileId());
                            }
                        }
                    }
                }
            }
            failed.removeAll(ok);
            System.out.printf(Locale.ROOT,
                    "PROBE tiles=%d diffusesOk=%d diffusesFailed=%d limitRefused=%d reasons=%s%n",
                    tiles, ok.size(), failed.size(), limited.size(), reasons);
        } finally {
            service.closeAsync().get(2, TimeUnit.MINUTES);
        }
        System.exit(0);
    }
}
